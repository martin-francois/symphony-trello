package ch.fmartin.symphony.trello.agent;

import static com.google.common.base.Preconditions.checkArgument;

import ch.fmartin.symphony.trello.config.EffectiveConfig;
import ch.fmartin.symphony.trello.config.FollowUpRelationship;
import ch.fmartin.symphony.trello.config.StateNames;
import ch.fmartin.symphony.trello.domain.Card;
import ch.fmartin.symphony.trello.tracker.TrelloClient;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/// Creates one actionable Trello card for out-of-scope work that Codex found while working on the
/// current card, then links both cards and records their relationship.
///
/// Codex never names a card id or list id. The source card is the session card, the destination
/// list comes from `trello_tools.follow_up_cards`, and every write stays on the configured board.
/// The tool never deletes anything; a partial failure is repaired by calling it again, because a
/// card it created earlier is found through its metadata footer and its missing links are added.
final class TrelloFollowUpCardTool {
    static final String NAME = "trello_create_follow_up_card";
    static final String PREREQUISITE_CHECKLIST_NAME = "Must finish first";
    static final String FOLLOW_UP_ATTACHMENT_PREFIX = "Follow-up: ";
    static final String SOURCE_ATTACHMENT_PREFIX = "Follow-up of: ";
    static final String ACCEPTANCE_CRITERIA_HEADING = "## Acceptance criteria";
    static final int MAX_TITLE_CODE_POINTS = 200;
    static final int MAX_DESCRIPTION_CODE_POINTS = 4000;
    static final int MAX_ACCEPTANCE_CRITERIA = 10;
    static final int MAX_ACCEPTANCE_CRITERION_CODE_POINTS = 300;
    static final Duration RATE_WINDOW = Duration.ofHours(1);
    // Trello's POST /labels needs a color; sky is neutral and differs from the default priority colors.
    static final String NEW_LABEL_COLOR = "sky";
    static final String STATUS_CREATED = "follow_up_card_created";
    static final String STATUS_EXISTS = "follow_up_card_exists";
    private static final String CHECKLIST_ITEM_CREATED = "created";
    private static final Set<String> ARGUMENT_NAMES =
            Set.of("title", "description", "acceptance_criteria", "relationship");

    private final TrelloClient trello;
    private final Clock clock;
    // Creation times, oldest first: the hourly window drops entries from the front as they expire.
    private final Deque<Instant> recentCreations = new ArrayDeque<>();
    // One lock for the whole process: the duplicate check, the limits, and the create must not
    // interleave between two sessions, and follow-up creation is rare enough to serialize.
    private final Object creationLock = new Object();

    TrelloFollowUpCardTool(TrelloClient trello, Clock clock) {
        this.trello = trello;
        this.clock = clock;
    }

    /// Links are part of every follow-up, so the tool is offered only when it can attach them.
    static boolean advertised(EffectiveConfig config) {
        return config.trelloTools().followUpCards().enabled()
                && config.trelloTools().allowUrlAttachments();
    }

    Outcome handle(
            EffectiveConfig config,
            Card card,
            JsonNode arguments,
            Function<String, Optional<TrelloClient.BoardList>> allowedMoveTarget) {
        EffectiveConfig.FollowUpCardsConfig policy = config.trelloTools().followUpCards();
        if (!policy.enabled()) {
            return Outcome.failure(
                    "trello_follow_up_cards_disabled",
                    "Follow-up cards are disabled by trello_tools.follow_up_cards.enabled.");
        }
        if (!config.trelloTools().allowUrlAttachments()) {
            return Outcome.failure(
                    "trello_url_attachments_disabled",
                    "Follow-up cards link both cards with URL attachments, which trello_tools.allow_url_attachments disables.");
        }
        Request request;
        try {
            request = Request.parse(arguments);
        } catch (IllegalArgumentException e) {
            return Outcome.failure("invalid_follow_up_card", e.getMessage());
        }
        if (!FollowUpCardMetadata.validShortLink(card.shortLink())) {
            return Outcome.failure(
                    "trello_follow_up_missing_card_link", "The current Trello card has no usable short link.");
        }
        if (request.relationship().prerequisiteSide() != FollowUpRelationship.PrerequisiteSide.NONE
                && !config.trelloTools().allowChecklists()) {
            return Outcome.failure(
                    "trello_checklists_disabled",
                    "Prerequisite follow-ups use the Must finish first checklist, which trello_tools.allow_checklists disables.");
        }

        synchronized (creationLock) {
            List<TrelloClient.BoardList> openLists = trello.fetchBoardLists(config).stream()
                    .filter(list -> !list.closed())
                    .toList();
            Checked<TrelloClient.BoardList> destination = destinationList(config, policy, openLists);
            Checked<Optional<TrelloClient.BoardList>> blockedTarget =
                    blockedTarget(config, policy, request.relationship(), allowedMoveTarget);
            Optional<Outcome> refusal = destination
                    .failure()
                    .or(blockedTarget::failure)
                    .or(() -> prerequisiteConflict(
                            config, card.id(), request.relationship().sourceCardWaits()));
            if (refusal.isPresent()) {
                return refusal.get();
            }
            var plan = new Plan(config, policy, card, request, destination.value(), blockedTarget.value(), openLists);
            List<ExistingFollowUp> existing = existingFollowUps(config, card.shortLink());
            String normalizedTitle = StateNames.normalize(request.title());
            return existing.stream()
                    .filter(followUp ->
                            StateNames.normalize(followUp.card().name()).equals(normalizedTitle))
                    .findAny()
                    .map(followUp -> repair(plan, followUp))
                    .orElseGet(() -> create(plan, existing));
        }
    }

    private Outcome repair(Plan plan, ExistingFollowUp followUp) {
        FollowUpRelationship recorded = followUp.metadata().relationship();
        if (recorded != plan.request().relationship()) {
            return Outcome.failure(
                    "trello_follow_up_relationship_conflict",
                    "A follow-up card with this title already exists for the current card with relationship "
                            + recorded.value() + ".");
        }
        if (recorded.prerequisiteSide() == FollowUpRelationship.PrerequisiteSide.FOLLOW_UP_CARD) {
            Optional<Outcome> conflict =
                    prerequisiteConflict(plan.config(), followUp.card().id(), true);
            if (conflict.isPresent()) {
                return conflict.get();
            }
        }
        for (String labelId : labelIds(plan.config(), plan.labelNames())) {
            if (!followUp.card().labelIds().contains(labelId)) {
                trello.addLabelToCard(plan.config(), followUp.card().id(), labelId);
            }
        }
        return linkAndRelate(
                plan, new FollowUpRef(followUp.card().id(), followUp.card().shortLink()), STATUS_EXISTS);
    }

    private Outcome create(Plan plan, List<ExistingFollowUp> existing) {
        long unfinished = existing.stream()
                .filter(followUp -> unfinished(plan, followUp.card()))
                .count();
        if (unfinished >= plan.policy().maxCardsPerSourceCard()) {
            return Outcome.failure(
                    "trello_follow_up_limit_reached",
                    "The current card already has " + unfinished
                            + " unfinished follow-up cards, the limit set by trello_tools.follow_up_cards.max_cards_per_source_card.");
        }
        Instant now = clock.instant();
        pruneRateWindow(now);
        if (recentCreations.size() >= plan.policy().maxCardsPerHour()) {
            return Outcome.failure(
                    "trello_follow_up_rate_limited",
                    "Symphony created " + recentCreations.size()
                            + " follow-up cards in the last hour, the limit set by trello_tools.follow_up_cards.max_cards_per_hour.");
        }
        List<String> labelIds = labelIds(plan.config(), plan.labelNames());
        var metadata =
                new FollowUpCardMetadata(plan.card().shortLink(), plan.request().relationship());
        TrelloClient.CreatedCard created = trello.createCard(
                plan.config(),
                plan.destination().id(),
                plan.request().title(),
                metadata.appendTo(plan.request().descriptionBody()),
                labelIds);
        recentCreations.addLast(now);
        return linkAndRelate(plan, new FollowUpRef(created.id(), created.shortLink()), STATUS_CREATED);
    }

    /// A follow-up in a terminal list is finished and no longer counts toward the per-card limit;
    /// a card in an archived list is effectively archived and does not count either.
    private static boolean unfinished(Plan plan, TrelloClient.BoardCard followUp) {
        return plan.openLists().stream()
                .filter(list -> list.id().equals(followUp.listId()))
                .findAny()
                .filter(list -> !TrelloClient.isTerminalList(plan.config(), list))
                .isPresent();
    }

    private Outcome linkAndRelate(Plan plan, FollowUpRef followUp, String status) {
        EffectiveConfig config = plan.config();
        Card card = plan.card();
        FollowUpRelationship relationship = plan.request().relationship();
        String followUpUrl = FollowUpCardMetadata.cardUrl(followUp.shortLink());
        String sourceUrl = FollowUpCardMetadata.cardUrl(card.shortLink());
        ensureCardLink(config, followUp.id(), sourceUrl, SOURCE_ATTACHMENT_PREFIX + oneLine(card.title()));
        ensureCardLink(
                config,
                card.id(),
                followUpUrl,
                FOLLOW_UP_ATTACHMENT_PREFIX + plan.request().title());
        String movedTo = "";
        switch (relationship.prerequisiteSide()) {
            case FOLLOW_UP_CARD ->
                trello.addChecklistItemIfMissing(config, followUp.id(), PREREQUISITE_CHECKLIST_NAME, sourceUrl);
            case SOURCE_CARD -> {
                TrelloClient.ChecklistItemWrite item =
                        trello.addChecklistItemIfMissing(config, card.id(), PREREQUISITE_CHECKLIST_NAME, followUpUrl);
                // The blocked handoff belongs to the moment the prerequisite is recorded. A retry that
                // finds the item already there must not repeat the comment or undo a person's unblock.
                if (plan.blockedTarget().isPresent() && CHECKLIST_ITEM_CREATED.equals(item.status())) {
                    TrelloClient.BoardList blocked = plan.blockedTarget().get();
                    trello.addComment(config, card.id(), blockedHandoffText(followUpUrl));
                    trello.moveCardToList(config, card.id(), blocked.id());
                    movedTo = blocked.name();
                }
            }
            case NONE -> {
                // A related follow-up has no required order, so it gets no prerequisite checklist.
            }
        }
        // LinkedHashMap keeps model-visible tool result fields in a stable diagnostic order.
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("status", status);
        payload.put("card_id", card.id());
        payload.put("follow_up_card_id", followUp.id());
        payload.put("follow_up_card_url", followUpUrl);
        payload.put("list_name", plan.destination().name());
        payload.put("relationship", relationship.value());
        payload.put("current_card_must_wait", Boolean.toString(relationship.sourceCardWaits()));
        payload.put("current_card_moved_to", movedTo);
        payload.put(
                "workpad_note",
                "Follow-up card created: " + followUpUrl + " (" + plan.request().title() + "). "
                        + relationship.sourceCardNote());
        return Outcome.success(payload);
    }

    private void ensureCardLink(EffectiveConfig config, String cardId, String targetUrl, String name) {
        boolean linked = trello.fetchAttachmentUrls(config, cardId).stream()
                .anyMatch(url -> url.equals(targetUrl) || url.startsWith(targetUrl + "/"));
        if (!linked) {
            trello.addUrlAttachment(config, cardId, targetUrl, truncate(name, MAX_TITLE_CODE_POINTS));
        }
    }

    private Optional<Outcome> prerequisiteConflict(EffectiveConfig config, String cardId, boolean receivesItem) {
        if (!receivesItem) {
            return Optional.empty();
        }
        return trello.prerequisiteChecklistConflict(config, cardId, PREREQUISITE_CHECKLIST_NAME)
                .map(message -> Outcome.failure("trello_follow_up_prerequisite_checklist_conflict", message));
    }

    private List<ExistingFollowUp> existingFollowUps(EffectiveConfig config, String sourceShortLink) {
        List<ExistingFollowUp> followUps = new ArrayList<>();
        for (TrelloClient.BoardCard boardCard : trello.fetchOpenBoardCards(config)) {
            FollowUpCardMetadata.parse(boardCard.description())
                    .filter(metadata -> metadata.sourceShortLink().equals(sourceShortLink))
                    .ifPresent(metadata -> followUps.add(new ExistingFollowUp(boardCard, metadata)));
        }
        return List.copyOf(followUps);
    }

    private List<String> labelIds(EffectiveConfig config, List<String> labelNames) {
        if (labelNames.isEmpty()) {
            return List.of();
        }
        List<TrelloClient.BoardLabel> boardLabels = new ArrayList<>(trello.fetchBoardLabels(config));
        List<String> ids = new ArrayList<>();
        for (String name : labelNames) {
            String normalized = StateNames.normalize(name);
            TrelloClient.BoardLabel label = boardLabels.stream()
                    .filter(candidate -> StateNames.normalize(candidate.name()).equals(normalized))
                    .findAny()
                    .orElseGet(() -> {
                        TrelloClient.BoardLabel createdLabel = trello.createBoardLabel(config, name, NEW_LABEL_COLOR);
                        boardLabels.add(createdLabel);
                        return createdLabel;
                    });
            if (!ids.contains(label.id())) {
                ids.add(label.id());
            }
        }
        return List.copyOf(ids);
    }

    private static Checked<TrelloClient.BoardList> destinationList(
            EffectiveConfig config,
            EffectiveConfig.FollowUpCardsConfig policy,
            List<TrelloClient.BoardList> openLists) {
        String normalizedName = StateNames.normalize(policy.listName());
        List<TrelloClient.BoardList> matches = openLists.stream()
                .filter(list -> policy.listId() == null
                        ? StateNames.normalize(list.name()).equals(normalizedName)
                        : list.id().equals(policy.listId()))
                .toList();
        if (matches.size() > 1) {
            return Checked.failed(
                    "trello_follow_up_list_ambiguous",
                    "The follow-up list name matches more than one open Trello list. Rename the duplicates or set trello_tools.follow_up_cards.list_id.");
        }
        if (matches.isEmpty()) {
            return Checked.failed(
                    "trello_follow_up_list_missing",
                    "The configured follow-up list is not open on the configured board.");
        }
        TrelloClient.BoardList list = matches.getFirst();
        if (TrelloClient.isActiveList(config, list) || TrelloClient.isTerminalList(config, list)) {
            return Checked.failed(
                    "trello_follow_up_list_not_allowed",
                    "The follow-up list must not be an active or terminal list, so a human decides when follow-up work starts.");
        }
        return Checked.of(list);
    }

    private static Checked<Optional<TrelloClient.BoardList>> blockedTarget(
            EffectiveConfig config,
            EffectiveConfig.FollowUpCardsConfig policy,
            FollowUpRelationship relationship,
            Function<String, Optional<TrelloClient.BoardList>> allowedMoveTarget) {
        if (!relationship.sourceCardWaits() || !policy.moveCurrentCardToBlocked()) {
            return Checked.of(Optional.empty());
        }
        String blockedState = config.tracker().blockedState();
        if (blockedState == null || blockedState.isBlank()) {
            return Checked.failed(
                    "trello_follow_up_blocked_list_missing",
                    "trello_tools.follow_up_cards.move_current_card_to_blocked needs tracker.blocked_state.");
        }
        if (!config.trelloTools().allowComments()) {
            return Checked.failed(
                    "trello_comments_disabled",
                    "Moving the current card to the blocked list needs a handoff comment, which trello_tools.allow_comments disables.");
        }
        return allowedMoveTarget
                .apply(blockedState)
                .map(target -> Checked.of(Optional.of(target)))
                .orElseGet(() -> Checked.failed(
                        "trello_move_not_allowed",
                        "The blocked list must be open and included in the configured Trello move allowlist."));
    }

    private void pruneRateWindow(Instant now) {
        Instant windowStart = now.minus(RATE_WINDOW);
        while (!recentCreations.isEmpty() && !recentCreations.peekFirst().isAfter(windowStart)) {
            recentCreations.removeFirst();
        }
    }

    static String blockedHandoffText(String followUpUrl) {
        return "Blocked by [the follow-up card](" + followUpUrl + "): it must finish first. Symphony added it to the "
                + PREREQUISITE_CHECKLIST_NAME
                + " checklist on this card, and Symphony dispatches this card again once that card is done and"
                + " the card is back in an active list.";
    }

    private static String oneLine(@Nullable String text) {
        if (text == null) {
            return "";
        }
        var line = new StringBuilder();
        text.codePoints().forEach(codePoint -> line.appendCodePoint(lineBreakOrControl(codePoint) ? ' ' : codePoint));
        return line.toString().strip();
    }

    private static boolean lineBreakOrControl(int codePoint) {
        int type = Character.getType(codePoint);
        return Character.isISOControl(codePoint)
                || type == Character.LINE_SEPARATOR
                || type == Character.PARAGRAPH_SEPARATOR;
    }

    private static String truncate(String text, int maxCodePoints) {
        if (text.codePointCount(0, text.length()) <= maxCodePoints) {
            return text;
        }
        return text.substring(0, text.offsetByCodePoints(0, maxCodePoints)).stripTrailing();
    }

    /// Validated tool arguments. Every text value is checked before any Trello request.
    record Request(String title, String descriptionBody, FollowUpRelationship relationship) {
        static Request parse(JsonNode arguments) {
            checkArgument(arguments.isObject(), "arguments must be an object");
            arguments
                    .fieldNames()
                    .forEachRemaining(name -> checkArgument(
                            ARGUMENT_NAMES.contains(name),
                            "Unsupported argument %s; Symphony chooses the board, list, labels, and cards",
                            name));
            String title = requiredLine(arguments, "title", MAX_TITLE_CODE_POINTS);
            String description = requiredText(arguments, "description").replace("\r\n", "\n");
            checkArgument(
                    description.codePointCount(0, description.length()) <= MAX_DESCRIPTION_CODE_POINTS,
                    "description must be at most %s characters",
                    MAX_DESCRIPTION_CODE_POINTS);
            checkArgument(
                    description
                            .codePoints()
                            .noneMatch(codePoint ->
                                    codePoint != '\n' && codePoint != '\t' && lineBreakOrControl(codePoint)),
                    "description must not contain control characters");
            checkArgument(
                    description.lines().noneMatch(line -> line.strip().startsWith(FollowUpCardMetadata.FOOTER_PREFIX)),
                    "description must not contain the Managed by Symphony footer; Symphony writes it");
            List<String> criteria = acceptanceCriteria(arguments);
            FollowUpRelationship relationship = relationship(arguments);
            var body = new StringBuilder(description.strip());
            body.append("\n\n").append(ACCEPTANCE_CRITERIA_HEADING).append("\n\n");
            criteria.forEach(criterion -> body.append("- ").append(criterion).append('\n'));
            return new Request(title, TrelloMarkdown.escapeLeadingHashtags(body.toString()), relationship);
        }

        private static List<String> acceptanceCriteria(JsonNode arguments) {
            JsonNode node = arguments.path("acceptance_criteria");
            checkArgument(node.isArray(), "acceptance_criteria must be an array of strings");
            checkArgument(
                    !node.isEmpty() && node.size() <= MAX_ACCEPTANCE_CRITERIA,
                    "acceptance_criteria must contain between 1 and %s items",
                    MAX_ACCEPTANCE_CRITERIA);
            List<String> criteria = new ArrayList<>();
            for (JsonNode item : node) {
                checkArgument(item.isTextual(), "acceptance_criteria items must be strings");
                criteria.add(
                        validLine(item.textValue(), "acceptance_criteria item", MAX_ACCEPTANCE_CRITERION_CODE_POINTS));
            }
            return List.copyOf(criteria);
        }

        private static FollowUpRelationship relationship(JsonNode arguments) {
            JsonNode node = arguments.path("relationship");
            if (node.isMissingNode() || node.isNull()) {
                return FollowUpRelationship.RELATED;
            }
            checkArgument(node.isTextual(), "relationship must be a string");
            return FollowUpRelationship.fromValue(node.textValue())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "relationship must be one of " + String.join(", ", FollowUpRelationship.toolValues())));
        }

        private static String requiredText(JsonNode arguments, String key) {
            JsonNode node = arguments.path(key);
            checkArgument(node.isTextual() && !node.textValue().isBlank(), "Missing required argument: %s", key);
            return node.textValue();
        }

        private static String requiredLine(JsonNode arguments, String key, int maxCodePoints) {
            return validLine(requiredText(arguments, key), key, maxCodePoints);
        }

        private static String validLine(String value, String name, int maxCodePoints) {
            String stripped = Objects.requireNonNull(value).strip();
            checkArgument(!stripped.isEmpty(), "%s must not be blank", name);
            checkArgument(
                    stripped.codePoints().noneMatch(TrelloFollowUpCardTool::lineBreakOrControl),
                    "%s must be one line",
                    name);
            checkArgument(
                    stripped.codePointCount(0, stripped.length()) <= maxCodePoints,
                    "%s must be at most %s characters",
                    name,
                    maxCodePoints);
            return stripped;
        }
    }

    sealed interface Outcome {
        static Outcome success(Map<String, String> payload) {
            return new Success(payload);
        }

        static Outcome failure(String code, String message) {
            return new Failure(code, message);
        }

        record Success(Map<String, String> payload) implements Outcome {
            public Success {
                // A read-only LinkedHashMap copy keeps the insertion order, the model-visible field order.
                payload = Collections.unmodifiableMap(new LinkedHashMap<>(payload));
            }
        }

        record Failure(String code, String message) implements Outcome {}
    }

    /// A value read from the board, or the structured failure that stops the tool before any write.
    private record Checked<T>(@Nullable T value, Optional<Outcome> failure) {
        static <T> Checked<T> of(T value) {
            return new Checked<>(value, Optional.empty());
        }

        static <T> Checked<T> failed(String code, String message) {
            return new Checked<>(null, Optional.of(Outcome.failure(code, message)));
        }
    }

    private record Plan(
            EffectiveConfig config,
            EffectiveConfig.FollowUpCardsConfig policy,
            Card card,
            Request request,
            TrelloClient.BoardList destination,
            Optional<TrelloClient.BoardList> blockedTarget,
            List<TrelloClient.BoardList> openLists) {
        Plan {
            openLists = List.copyOf(openLists);
        }

        List<String> labelNames() {
            List<String> names = new ArrayList<>();
            if (!policy.label().isBlank()) {
                names.add(policy.label());
            }
            String relationshipLabel = policy.relationshipLabels().get(request.relationship());
            if (relationshipLabel != null) {
                names.add(relationshipLabel);
            }
            return List.copyOf(names);
        }
    }

    private record FollowUpRef(String id, String shortLink) {}

    private record ExistingFollowUp(TrelloClient.BoardCard card, FollowUpCardMetadata metadata) {}
}
