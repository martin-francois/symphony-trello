package ch.fmartin.symphony.trello.agent;

import static com.google.common.base.Preconditions.checkArgument;

import ch.fmartin.symphony.trello.config.EffectiveConfig;
import ch.fmartin.symphony.trello.config.StateNames;
import ch.fmartin.symphony.trello.domain.Card;
import ch.fmartin.symphony.trello.tracker.CardLookupResult;
import ch.fmartin.symphony.trello.tracker.TrelloClient;
import ch.fmartin.symphony.trello.tracker.TrelloException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PrimitiveIterator;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.jboss.logging.Logger;
import org.jspecify.annotations.Nullable;

@ApplicationScoped
public class TrelloHandoffToolHandler {
    private static final Logger LOG = Logger.getLogger(TrelloHandoffToolHandler.class);

    static final String ADD_COMMENT = "trello_add_comment";
    static final String UPSERT_WORKPAD = "trello_upsert_workpad";
    static final String UPDATE_BLOCKER_RECHECK_STATUS = "trello_update_blocker_recheck_status";
    static final String MOVE_CURRENT_CARD = "trello_move_current_card";
    static final String UPSERT_CHECKLIST_ITEM = "trello_upsert_checklist_item";
    static final String ADD_URL_ATTACHMENT = "trello_add_url_attachment";
    static final String WORKPAD_MARKER = TrelloClient.WORKPAD_MARKER;
    static final String DUPLICATE_WORKPADS_NOTE_PREFIX = "> Duplicate Codex workpads found: ";
    static final String DUPLICATE_BLOCKER_RECHECK_NOTE_PREFIX = "> Duplicate Symphony blocker recheck statuses found: ";
    static final String BLOCKER_RECHECK_CHECKING = "Checking whether this card is still blocked...";
    static final String RESUMED_WORK_PREFIX = "No longer blocked; working on ";
    static final String BLOCKER_RECHECK_FOOTER_PREFIX =
            "_Managed by Symphony · [View the comment explaining why this card was previously blocked](";
    static final String BLOCKER_RECHECK_FOOTER_SUFFIX = ")_";
    private static final String TRELLO_CARD_URL_PREFIX = "https://trello.com/c/";
    private static final String TRELLO_COMMENT_FRAGMENT = "#comment-";
    private static final String RECHECK_STATUS_CHECKING = "checking";
    private static final String RECHECK_STATUS_RESUMED = "resumed";
    private static final int VISIBLE_TASK_SUMMARY_CODE_POINT_LIMIT = 120;
    private static final int TASK_SUMMARY_BODY_CODE_POINT_LIMIT = VISIBLE_TASK_SUMMARY_CODE_POINT_LIMIT - 1;
    private static final int ELLIPSIS_CODE_POINT_COUNT = 3;
    private static final int MAX_ACTION_ID_LENGTH = 128;
    private static final Pattern LOGICAL_LINE_BREAK = Pattern.compile("\\r\\n|[\\n\\r\\x{0085}\\x{2028}\\x{2029}]");

    private final ObjectMapper json;
    private final TrelloClient trello;

    public TrelloHandoffToolHandler(ObjectMapper json, TrelloClient trello) {
        this.json = json;
        this.trello = trello;
    }

    public boolean shouldEnableExperimentalApi(EffectiveConfig config) {
        return config.trelloTools().enabled();
    }

    public ArrayNode toolSpecs(EffectiveConfig config) {
        ArrayNode tools = json.createArrayNode();
        if (!writesEnabled(config)) {
            return tools;
        }
        if (config.trelloTools().allowComments()) {
            tools.add(tool(
                    ADD_COMMENT,
                    "Add a handoff comment to the current Trello card. The current card is fixed by Symphony; do not include a card id.",
                    objectSchema(
                            Map.of(
                                    "text",
                                    stringSchema(
                                            "Concise human-readable comment text to add to the current Trello card.")),
                            List.of("text"))));
            tools.add(tool(
                    UPSERT_WORKPAD,
                    "Create or update the single Codex workpad comment on the current Trello card. The text is Markdown and must summarize current plan, acceptance criteria, progress, validation, blockers, and handoff notes.",
                    objectSchema(
                            Map.of(
                                    "text",
                                    stringSchema(
                                            "Full Markdown workpad body. Symphony ensures it starts with ## Codex Workpad.")),
                            List.of("text"))));
            tools.add(tool(
                    UPDATE_BLOCKER_RECHECK_STATUS,
                    "Maintain one managed status while rechecking the newest exact Blocked: or Blocked by handoff. Set checking before the recheck, then set resumed only after that blocker no longer applies.",
                    objectSchema(
                            Map.of(
                                    "status",
                                    enumStringSchema(
                                            "Recheck lifecycle state. Use checking before rechecking the blocker and resumed only after the recheck succeeds.",
                                            List.of(RECHECK_STATUS_CHECKING, RECHECK_STATUS_RESUMED))),
                            List.of("status"))));
        }
        if (config.trelloTools().allowChecklists()) {
            tools.add(tool(
                    UPSERT_CHECKLIST_ITEM,
                    "Create or update one checklist item on the current Trello card. The current card is fixed by Symphony; do not include a card id.",
                    objectSchema(
                            Map.of(
                                    "checklist_name",
                                    stringSchema("Exact checklist name on the current Trello card."),
                                    "item_name",
                                    stringSchema("Exact checklist item text to create or update."),
                                    "complete",
                                    booleanSchema("Whether the checklist item should be complete.")),
                            List.of("checklist_name", "item_name", "complete"))));
        }
        if (config.trelloTools().allowUrlAttachments()) {
            tools.add(tool(
                    ADD_URL_ATTACHMENT,
                    "Attach one http or https URL without credentials, query string, or fragment to the current Trello card. The current card is fixed by Symphony; do not include a card id.",
                    objectSchema(
                            Map.of(
                                    "url",
                                    stringSchema(
                                            "HTTP or HTTPS URL without credentials, query string, or fragment to attach to the current Trello card."),
                                    "name",
                                    stringSchema("Optional attachment display name.")),
                            List.of("url"))));
        }
        if (moveAllowlistConfigured(config)) {
            tools.add(tool(
                    MOVE_CURRENT_CARD,
                    "Move the current Trello card to one configured board-local handoff list. Use list_name unless the workflow explicitly gives a Trello list_id.",
                    objectSchema(
                            Map.of(
                                    "list_name",
                                    stringSchema("Allowed destination list name, for example Human Review."),
                                    "list_id",
                                    stringSchema("Allowed destination Trello list id.")),
                            List.of())));
        }
        return tools;
    }

    public ObjectNode handle(EffectiveConfig config, Card card, JsonNode params) {
        String tool = params.path("tool").asText("");
        if (!ADD_COMMENT.equals(tool)
                && !UPSERT_WORKPAD.equals(tool)
                && !UPDATE_BLOCKER_RECHECK_STATUS.equals(tool)
                && !MOVE_CURRENT_CARD.equals(tool)
                && !UPSERT_CHECKLIST_ITEM.equals(tool)
                && !ADD_URL_ATTACHMENT.equals(tool)) {
            return failure("unsupported_tool", "Unsupported Trello handoff tool: " + tool);
        }
        if (!config.trelloTools().enabled()) {
            return failure("trello_tools_disabled", "Trello handoff tools are disabled by trello_tools.enabled.");
        }
        if (!config.trelloTools().allowWrites()) {
            return failure("trello_writes_disabled", "Trello writes are disabled by trello_tools.allow_writes.");
        }
        if (blank(card.id())) {
            return failure("missing_card_context", "The active worker session has no Trello card id.");
        }

        try {
            return switch (tool) {
                case ADD_COMMENT -> addComment(config, card, params.path("arguments"));
                case UPSERT_WORKPAD -> upsertWorkpad(config, card, params.path("arguments"));
                case UPDATE_BLOCKER_RECHECK_STATUS ->
                    updateBlockerRecheckStatus(config, card, params.path("arguments"));
                case MOVE_CURRENT_CARD -> moveCurrentCard(config, card, params.path("arguments"));
                case UPSERT_CHECKLIST_ITEM -> upsertChecklistItem(config, card, params.path("arguments"));
                case ADD_URL_ATTACHMENT -> addUrlAttachment(config, card, params.path("arguments"));
                default -> throw new IllegalStateException("unreachable");
            };
        } catch (TrelloException e) {
            return failure(e.code(), e.getMessage());
        } catch (RuntimeException e) {
            return failure("trello_tool_failed", e.getMessage());
        }
    }

    private ObjectNode addComment(EffectiveConfig config, Card card, JsonNode arguments) {
        if (!config.trelloTools().allowComments()) {
            return failure("trello_comments_disabled", "Trello comments are disabled by trello_tools.allow_comments.");
        }
        String text = TrelloMarkdown.escapeLeadingHashtags(requiredText(arguments, "text"));
        trello.addComment(config, card.id(), text);
        return success(Map.of("status", "comment_added", "card_id", card.id()));
    }

    private ObjectNode upsertChecklistItem(EffectiveConfig config, Card card, JsonNode arguments) {
        if (!config.trelloTools().allowChecklists()) {
            return failure(
                    "trello_checklists_disabled", "Trello checklists are disabled by trello_tools.allow_checklists.");
        }
        String checklistName = requiredText(arguments, "checklist_name").strip();
        String itemName = requiredText(arguments, "item_name").strip();
        if (unsafeToolLine(checklistName) || unsafeToolLine(itemName)) {
            return failure("invalid_checklist_item", "Checklist names and item names must be one non-control line.");
        }

        TrelloClient.ChecklistItemWrite result = trello.upsertChecklistItem(
                config, card.id(), checklistName, itemName, requiredBoolean(arguments, "complete"));
        return success(Map.of(
                "status",
                "checklist_item_" + result.status(),
                "card_id",
                card.id(),
                "checklist_id",
                result.checklistId(),
                "check_item_id",
                result.checkItemId(),
                "complete",
                Boolean.toString(result.complete())));
    }

    private ObjectNode updateBlockerRecheckStatus(EffectiveConfig config, Card card, JsonNode arguments) {
        if (!config.trelloTools().allowComments()) {
            return failure("trello_comments_disabled", "Trello comments are disabled by trello_tools.allow_comments.");
        }
        String requestedStatus = requiredText(arguments, "status");
        if (!RECHECK_STATUS_CHECKING.equals(requestedStatus) && !RECHECK_STATUS_RESUMED.equals(requestedStatus)) {
            return failure("invalid_blocker_recheck_status", "Blocker recheck status must be checking or resumed.");
        }

        CardLookupResult lookup = trello.fetchCardStateForWorkpad(config, card.id());
        if (lookup instanceof CardLookupResult.Missing) {
            return failure(
                    "trello_blocker_recheck_card_missing",
                    "Cannot update blocker recheck status because the Trello card is missing.");
        }
        if (lookup instanceof CardLookupResult.Failed failed) {
            return failure("trello_blocker_recheck_refresh_failed", failed.message());
        }
        if (!(lookup instanceof CardLookupResult.Found found)) {
            return failure("trello_blocker_recheck_refresh_failed", "Could not refresh the current Trello card.");
        }

        BlockerRecheckComments comments = blockerRecheckComments(found.card());
        return RECHECK_STATUS_CHECKING.equals(requestedStatus)
                ? beginBlockerRecheck(config, found.card(), comments)
                : confirmResumedWork(config, found.card(), comments);
    }

    private ObjectNode beginBlockerRecheck(EffectiveConfig config, Card currentCard, BlockerRecheckComments comments) {
        Card.Comment blocker = comments.blocker();
        if (blocker == null) {
            return success(Map.of("status", "blocker_recheck_not_needed", "card_id", currentCard.id()));
        }
        if (!safeActionId(blocker.id())) {
            return failure(
                    "trello_blocker_recheck_missing_source_id",
                    "The newest blocker handoff has no usable Trello action id.");
        }
        if (!safeShortLink(currentCard.shortLink())) {
            return failure(
                    "trello_blocker_recheck_missing_card_link",
                    "The current Trello card has no usable short link for the managed status comment.");
        }

        List<Card.Comment> managedComments = comments.managedComments();
        Card.Comment managed = newestAddressableComment(managedComments);
        if (managed == null && !managedComments.isEmpty()) {
            return failure(
                    "trello_blocker_recheck_missing_action_id",
                    "The managed blocker recheck comments have no Trello action ids.");
        }
        if (managedTracksBlocker(managed, currentCard, blocker.id())) {
            if (isResumedStatus(managed.text())) {
                return updateExistingBlockerRecheckComment(
                        config,
                        currentCard,
                        blockerRecheckText(currentCard, resumedWorkText(currentCard.title()), blocker.id()),
                        "resumed_work_already_confirmed",
                        managedComments,
                        managed);
            }
            if (isCheckingStatus(managed.text())) {
                return updateExistingBlockerRecheckComment(
                        config,
                        currentCard,
                        blockerRecheckText(currentCard, BLOCKER_RECHECK_CHECKING, blocker.id()),
                        "blocker_recheck_already_started",
                        managedComments,
                        managed);
            }
        }

        String text = blockerRecheckText(currentCard, BLOCKER_RECHECK_CHECKING, blocker.id());
        if (managed != null) {
            return updateExistingBlockerRecheckComment(
                    config, currentCard, text, "blocker_recheck_started", managedComments, managed);
        }
        if (currentCard.comments().size() >= TrelloClient.WORKPAD_COMMENT_ACTION_LIMIT) {
            return failure(
                    "trello_blocker_recheck_comment_window_incomplete",
                    "Cannot safely create blocker recheck status because the fetched Trello comment window is full and an older managed status may exist.");
        }
        Map<String, Object> created = trello.addComment(config, currentCard.id(), text);
        String actionId = string(created.get("id"));
        if (blank(actionId)) {
            return failure(
                    "trello_blocker_recheck_missing_action_id",
                    "Trello created the blocker recheck status without returning its action id.");
        }
        return success(blockerRecheckResult(
                "blocker_recheck_started", currentCard.id(), actionId, 0, false, DuplicateCleanup.none()));
    }

    private ObjectNode confirmResumedWork(EffectiveConfig config, Card currentCard, BlockerRecheckComments comments) {
        Card.Comment blocker = comments.blocker();
        List<Card.Comment> managedComments = comments.managedComments();
        Card.Comment managed = newestAddressableComment(managedComments);
        if (blocker == null || managed == null || !safeActionId(blocker.id())) {
            return failure(
                    "trello_blocker_recheck_not_started",
                    "Confirm resumed work only after starting a recheck for the newest blocker handoff.");
        }
        if (!managedTracksBlocker(managed, currentCard, blocker.id())) {
            return failure(
                    "trello_blocker_recheck_stale",
                    "A newer blocker handoff must enter checking status before resumed work can be confirmed.");
        }
        String text = blockerRecheckText(currentCard, resumedWorkText(currentCard.title()), blocker.id());
        if (isResumedStatus(managed.text())) {
            return updateExistingBlockerRecheckComment(
                    config, currentCard, text, "resumed_work_already_confirmed", managedComments, managed);
        }
        if (!isCheckingStatus(managed.text())) {
            return failure(
                    "trello_blocker_recheck_not_started",
                    "Confirm resumed work only after starting a recheck for the newest blocker handoff.");
        }

        return updateExistingBlockerRecheckComment(
                config, currentCard, text, "resumed_work_confirmed", managedComments, managed);
    }

    private ObjectNode updateExistingBlockerRecheckComment(
            EffectiveConfig config,
            Card currentCard,
            String text,
            String status,
            List<Card.Comment> managedComments,
            Card.Comment primary) {
        int duplicatesFound = managedComments.size() - 1;
        boolean destructiveAllowed = config.trelloTools().allowDestructiveOperations();
        String authoritativeText = duplicatesFound > 0 && !destructiveAllowed
                ? blockerRecheckTextWithManualCleanup(text, duplicatesFound)
                : text;
        if (!authoritativeText.equals(primary.text())) {
            trello.updateComment(config, primary.id(), authoritativeText);
        }
        DuplicateCleanup cleanup = destructiveAllowed
                ? removeDuplicateManagedComments(config, managedComments, primary, "blocker_recheck_duplicate_delete")
                : DuplicateCleanup.none();
        return success(blockerRecheckResult(
                status, currentCard.id(), primary.id(), duplicatesFound, destructiveAllowed, cleanup));
    }

    private static Map<String, String> blockerRecheckResult(
            String status,
            String cardId,
            String actionId,
            int duplicatesFound,
            boolean destructiveAllowed,
            DuplicateCleanup cleanup) {
        // LinkedHashMap keeps model-visible tool result fields in a stable diagnostic order.
        Map<String, String> result = new LinkedHashMap<>();
        result.put("status", status);
        result.put("card_id", cardId);
        result.put("action_id", actionId);
        result.put("duplicate_statuses_found", Integer.toString(duplicatesFound));
        result.put("duplicate_statuses_removed", Integer.toString(cleanup.removed()));
        result.put("duplicate_statuses_delete_failed", Integer.toString(cleanup.deleteFailed()));
        result.put("duplicate_statuses_cleanup_status", cleanupStatus(duplicatesFound, destructiveAllowed, cleanup));
        return result;
    }

    private static String blockerRecheckManualCleanupNote(int duplicatesFound) {
        String existence = duplicatesFound == 1 ? "comment exists" : "comments exist";
        return String.join(
                "",
                "\n\n",
                DUPLICATE_BLOCKER_RECHECK_NOTE_PREFIX,
                Integer.toString(duplicatesFound),
                " other managed status ",
                existence,
                " on this card. Deleting Trello comments is disabled by",
                " trello_tools.allow_destructive_operations, so delete the duplicate blocker recheck",
                " comments manually and keep this one.");
    }

    private static String blockerRecheckTextWithManualCleanup(String text, int duplicatesFound) {
        int footerStart = text.lastIndexOf("\n\n" + BLOCKER_RECHECK_FOOTER_PREFIX);
        checkArgument(footerStart >= 0, "Managed blocker-recheck text must contain its footer");
        return text.substring(0, footerStart)
                + blockerRecheckManualCleanupNote(duplicatesFound)
                + text.substring(footerStart);
    }

    private static BlockerRecheckComments blockerRecheckComments(Card card) {
        List<Card.Comment> comments = card.comments();
        Card.Comment newestOrdinary = null;
        // Trello returns comment actions newest first. Keep the first ordinary comment even while
        // scanning the rest of the deep window for Symphony-managed statuses.
        for (Card.Comment comment : comments) {
            if (newestOrdinary == null && !excludedFromBlockerClassification(comment, card)) {
                newestOrdinary = comment;
            }
        }
        List<Card.Comment> managedComments = comments.stream()
                .filter(comment -> isManagedBlockerRecheckComment(comment, card))
                .toList();
        Card.Comment blocker =
                newestOrdinary != null && isExactBlockerHandoff(newestOrdinary.text()) ? newestOrdinary : null;
        return new BlockerRecheckComments(blocker, managedComments);
    }

    private static Card.@Nullable Comment newestAddressableComment(List<Card.Comment> comments) {
        for (Card.Comment comment : comments) {
            if (!blank(comment.id())) {
                return comment;
            }
        }
        return null;
    }

    private static boolean excludedFromBlockerClassification(Card.Comment comment, Card card) {
        String text = comment.text();
        if (text == null) {
            return false;
        }
        // Managed-family identity starts at byte zero, symmetric with each family's own classifier.
        return text.startsWith(WORKPAD_MARKER)
                || text.startsWith(TrelloClient.PREREQUISITE_STATUS_COMMENT_MARKER)
                || text.startsWith(TrelloClient.WAITING_COMMENT_MARKER)
                || isManagedBlockerRecheckComment(comment, card);
    }

    private static boolean isExactBlockerHandoff(String text) {
        String firstLine = firstNonBlankLine(text).toLowerCase(Locale.ROOT);
        return firstLine.startsWith("blocked:")
                || firstLine.equals("blocked by")
                || firstLine.startsWith("blocked by ")
                || firstLine.startsWith("blocked by:");
    }

    private static String firstNonBlankLine(String text) {
        if (text == null) {
            return "";
        }
        for (String line : LOGICAL_LINE_BREAK.split(text, -1)) {
            String stripped = line.strip();
            if (!stripped.isEmpty()) {
                return stripped;
            }
        }
        return "";
    }

    private static boolean isManagedBlockerRecheckComment(Card.Comment candidate, Card card) {
        String sourceActionId = blockerActionId(candidate.text(), card);
        if (sourceActionId == null) {
            return false;
        }
        return card.comments().stream()
                .anyMatch(source -> isQualifyingBlockerSource(candidate, sourceActionId, source));
    }

    private static boolean isQualifyingBlockerSource(
            Card.Comment candidate, String sourceActionId, Card.Comment source) {
        return !sourceActionId.equals(candidate.id())
                && sourceActionId.equals(source.id())
                && isExactBlockerHandoff(source.text());
    }

    private static boolean managedTracksBlocker(Card.@Nullable Comment managed, Card card, String blockerActionId) {
        return managed != null
                && managed.text() != null
                && blockerActionId.equals(blockerActionId(managed.text(), card));
    }

    private static boolean isCheckingStatus(String text) {
        return text != null && text.startsWith(BLOCKER_RECHECK_CHECKING + "\n\n");
    }

    private static boolean isResumedStatus(String text) {
        return text != null && text.startsWith(RESUMED_WORK_PREFIX);
    }

    private static String blockerRecheckText(Card card, String visibleStatus, String blockerActionId) {
        return visibleStatus + "\n\n" + blockerRecheckFooter(card, blockerActionId);
    }

    private static String blockerRecheckFooter(Card card, String blockerActionId) {
        return BLOCKER_RECHECK_FOOTER_PREFIX
                + TRELLO_CARD_URL_PREFIX
                + card.shortLink()
                + TRELLO_COMMENT_FRAGMENT
                + blockerActionId
                + BLOCKER_RECHECK_FOOTER_SUFFIX;
    }

    private static @Nullable String blockerActionId(String text, Card card) {
        if (text == null || !safeShortLink(card.shortLink())) {
            return null;
        }
        String linkPrefix =
                BLOCKER_RECHECK_FOOTER_PREFIX + TRELLO_CARD_URL_PREFIX + card.shortLink() + TRELLO_COMMENT_FRAGMENT;
        int footerStart = text.lastIndexOf("\n\n" + linkPrefix);
        if (footerStart < 0 || !text.endsWith(BLOCKER_RECHECK_FOOTER_SUFFIX)) {
            return null;
        }
        int actionIdStart = footerStart + 2 + linkPrefix.length();
        int actionIdEnd = text.length() - BLOCKER_RECHECK_FOOTER_SUFFIX.length();
        String actionId = text.substring(actionIdStart, actionIdEnd);
        return safeActionId(actionId) ? actionId : null;
    }

    private static String resumedWorkText(String title) {
        String summary = shortTaskSummary(title);
        String terminator = endsSentence(summary) ? "" : ".";
        return RESUMED_WORK_PREFIX + summary + terminator;
    }

    private static String shortTaskSummary(String title) {
        StringBuilder plain = new StringBuilder();
        boolean previousWhitespace = true;
        if (title != null) {
            PrimitiveIterator.OfInt codePoints = title.codePoints().iterator();
            while (codePoints.hasNext()) {
                int codePoint = codePoints.nextInt();
                if (unsafeSummaryCodePoint(codePoint) || Character.isWhitespace(codePoint)) {
                    if (!previousWhitespace) {
                        plain.append(' ');
                        previousWhitespace = true;
                    }
                } else {
                    plain.appendCodePoint(codePoint);
                    previousWhitespace = false;
                }
            }
        }
        String summary = plain.toString().strip();
        if (summary.isEmpty()) {
            return "this card";
        }
        int codePoints = summary.codePointCount(0, summary.length());
        if (codePoints <= TASK_SUMMARY_BODY_CODE_POINT_LIMIT) {
            return summary;
        }
        int end = summary.offsetByCodePoints(0, TASK_SUMMARY_BODY_CODE_POINT_LIMIT - ELLIPSIS_CODE_POINT_COUNT);
        return summary.substring(0, end).stripTrailing() + "...";
    }

    private static boolean unsafeSummaryCodePoint(int codePoint) {
        int type = Character.getType(codePoint);
        return Character.isISOControl(codePoint)
                || type == Character.FORMAT
                || type == Character.LINE_SEPARATOR
                || type == Character.PARAGRAPH_SEPARATOR
                || type == Character.SURROGATE
                || type == Character.PRIVATE_USE
                || type == Character.UNASSIGNED
                || "\\`*_{}[]()<>|~&:/@".indexOf(codePoint) >= 0;
    }

    private static boolean endsSentence(String summary) {
        int last = summary.codePointBefore(summary.length());
        return last == '.' || last == '!' || last == '?';
    }

    private static boolean safeActionId(String actionId) {
        return safeIdentifier(actionId, MAX_ACTION_ID_LENGTH);
    }

    private static boolean safeShortLink(String shortLink) {
        return safeIdentifier(shortLink, 64);
    }

    private static boolean safeIdentifier(String value, int maxLength) {
        if (blank(value) || value.length() > maxLength) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char c = value.charAt(index);
            boolean asciiLetter = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
            if (!asciiLetter && !(c >= '0' && c <= '9') && c != '-' && c != '_') {
                return false;
            }
        }
        return true;
    }

    private ObjectNode addUrlAttachment(EffectiveConfig config, Card card, JsonNode arguments) {
        if (!config.trelloTools().allowUrlAttachments()) {
            return failure(
                    "trello_url_attachments_disabled",
                    "Trello URL attachments are disabled by trello_tools.allow_url_attachments.");
        }
        String url = requiredText(arguments, "url").strip();
        if (!validAttachmentUrl(url)) {
            return failure(
                    "invalid_url_attachment",
                    "Provide an http or https URL without credentials, query string, or fragment.");
        }
        String name = text(arguments, "name");
        if (name != null) {
            name = name.strip();
        }
        if (unsafeToolLine(name)) {
            return failure("invalid_url_attachment_name", "Attachment names must be one non-control line.");
        }

        TrelloClient.UrlAttachmentWrite result = trello.addUrlAttachment(config, card.id(), url, name);
        return success(
                Map.of("status", "url_attachment_added", "card_id", card.id(), "attachment_id", result.attachmentId()));
    }

    private ObjectNode upsertWorkpad(EffectiveConfig config, Card card, JsonNode arguments) {
        if (!config.trelloTools().allowComments()) {
            return failure("trello_comments_disabled", "Trello comments are disabled by trello_tools.allow_comments.");
        }
        String text = workpadText(requiredText(arguments, "text"));
        CardLookupResult lookup = trello.fetchCardStateForWorkpad(config, card.id());
        if (lookup instanceof CardLookupResult.Missing) {
            return failure("trello_workpad_card_missing", "Cannot update workpad because the Trello card is missing.");
        }
        if (lookup instanceof CardLookupResult.Failed failed) {
            return failure("trello_workpad_refresh_failed", failed.message());
        }
        if (!(lookup instanceof CardLookupResult.Found found)) {
            return failure("trello_workpad_refresh_failed", "Could not refresh the current Trello card.");
        }
        return upsertWorkpadComment(config, card.id(), found.card(), text);
    }

    private ObjectNode upsertWorkpadComment(EffectiveConfig config, String cardId, Card currentCard, String text) {
        List<Card.Comment> workpads =
                currentCard.comments().stream().filter(this::isWorkpadComment).toList();
        if (workpads.isEmpty()) {
            return createWorkpad(config, cardId, currentCard, text);
        }
        // Trello lists comment actions newest first, so the first addressable workpad is the
        // newest one and stays the authoritative comment deterministically.
        Card.Comment primary = workpads.stream()
                .filter(workpad -> !blank(workpad.id()))
                .findFirst()
                .orElseGet(workpads::getFirst);
        if (blank(primary.id())) {
            return failure("trello_workpad_missing_action_id", "Existing workpad comment has no Trello action id.");
        }
        int duplicatesFound = workpads.size() - 1;
        boolean destructiveAllowed = config.trelloTools().allowDestructiveOperations();
        // Without the destructive opt-in, the duplicates stay on the card, so the canonical
        // workpad itself must tell the next agent or human that manual cleanup is required.
        String authoritativeText =
                duplicatesFound > 0 && !destructiveAllowed ? text + manualCleanupNote(duplicatesFound) : text;
        // The authoritative update runs before any duplicate cleanup: if it fails, every workpad
        // stays in place and no comment content is lost to a delete that ran first.
        trello.updateComment(config, primary.id(), authoritativeText);
        // Deleting Trello comments is a destructive operation and disallowed by default. Without
        // the opt-in, duplicate workpads stay in place and only the primary comment is updated.
        DuplicateCleanup cleanup = destructiveAllowed
                ? removeDuplicateManagedComments(config, workpads, primary, "workpad_duplicate_delete")
                : DuplicateCleanup.none();
        return success(Map.of(
                "status",
                "workpad_updated",
                "card_id",
                cardId,
                "action_id",
                primary.id(),
                "duplicate_workpads_found",
                Integer.toString(duplicatesFound),
                "duplicate_workpads_removed",
                Integer.toString(cleanup.removed()),
                "duplicate_workpads_delete_failed",
                Integer.toString(cleanup.deleteFailed()),
                "duplicate_workpads_cleanup_status",
                cleanupStatus(duplicatesFound, destructiveAllowed, cleanup)));
    }

    private static String cleanupStatus(int duplicatesFound, boolean destructiveAllowed, DuplicateCleanup cleanup) {
        if (duplicatesFound == 0) {
            return "not_needed";
        }
        if (!destructiveAllowed) {
            return "skipped_destructive_operations_disabled";
        }
        return cleanup.removed() == duplicatesFound ? "removed" : "delete_failed";
    }

    private static String manualCleanupNote(int duplicatesFound) {
        String existence = duplicatesFound == 1 ? "comment exists" : "comments exist";
        return System.lineSeparator()
                + System.lineSeparator()
                + DUPLICATE_WORKPADS_NOTE_PREFIX
                + duplicatesFound
                + " other Codex workpad "
                + existence
                + " on this card. Deleting Trello comments is disabled by"
                + " trello_tools.allow_destructive_operations, so delete the duplicate workpad"
                + " comments manually and keep this one.";
    }

    /**
     * A managed comment family should have one authoritative comment, so destructive-policy cleanup
     * removes duplicates only after the authoritative state is safe. Failed or unaddressable deletes
     * stay visible and are reported instead of disappearing from the cleanup totals.
     */
    private DuplicateCleanup removeDuplicateManagedComments(
            EffectiveConfig config, List<Card.Comment> managedComments, Card.Comment primary, String logEvent) {
        int removed = 0;
        int deleteFailed = 0;
        for (Card.Comment duplicate : managedComments) {
            if (duplicate.equals(primary)) {
                continue;
            }
            if (blank(duplicate.id())) {
                deleteFailed++;
                LOG.warnf("%s outcome=missing_action_id", logEvent);
                continue;
            }
            try {
                trello.deleteComment(config, duplicate.id());
                removed++;
            } catch (RuntimeException e) {
                deleteFailed++;
                LOG.warnf(e, "%s outcome=failed action_id=%s", logEvent, duplicate.id());
            }
        }
        return new DuplicateCleanup(removed, deleteFailed);
    }

    private record DuplicateCleanup(int removed, int deleteFailed) {
        static DuplicateCleanup none() {
            return new DuplicateCleanup(0, 0);
        }
    }

    private boolean isWorkpadComment(Card.Comment comment) {
        return comment.text() != null && comment.text().startsWith(WORKPAD_MARKER);
    }

    private ObjectNode createWorkpad(EffectiveConfig config, String cardId, Card currentCard, String text) {
        if (currentCard.comments().size() >= TrelloClient.WORKPAD_COMMENT_ACTION_LIMIT) {
            return failure(
                    "trello_workpad_comment_window_incomplete",
                    "Cannot safely create a workpad because the fetched Trello comment window is full and an older workpad may exist.");
        }
        Map<String, Object> created = trello.addComment(config, cardId, text);
        return success(Map.of("status", "workpad_created", "card_id", cardId, "action_id", string(created.get("id"))));
    }

    private static String workpadText(String text) {
        String trimmed = stripManualCleanupNotes(text.strip());
        if (trimmed.startsWith(WORKPAD_MARKER)) {
            int markerEnd = markerLineEnd(trimmed);
            return trimmed.substring(0, markerEnd) + TrelloMarkdown.escapeLeadingHashtags(trimmed.substring(markerEnd));
        }
        return WORKPAD_MARKER
                + System.lineSeparator()
                + System.lineSeparator()
                + TrelloMarkdown.escapeLeadingHashtags(trimmed);
    }

    /**
     * Agents often echo the previous workpad body into the next upsert, so a cleanup note from an
     * earlier update is dropped before the fresh state is decided. This keeps the note from
     * accumulating and removes it once the duplicates are gone.
     */
    private static String stripManualCleanupNotes(String text) {
        if (!text.contains(DUPLICATE_WORKPADS_NOTE_PREFIX)) {
            return text;
        }
        return text.lines()
                .filter(line -> !line.startsWith(DUPLICATE_WORKPADS_NOTE_PREFIX))
                .collect(Collectors.joining("\n"))
                .strip();
    }

    private static int markerLineEnd(String text) {
        int lineEnd = text.indexOf('\n');
        return lineEnd < 0 ? text.length() : lineEnd;
    }

    private ObjectNode moveCurrentCard(EffectiveConfig config, Card card, JsonNode arguments) {
        if (!moveAllowlistConfigured(config)) {
            return failure(
                    "trello_move_allowlist_required",
                    "Trello card moves require trello_tools.allowed_move_list_ids or allowed_move_list_names.");
        }
        String listId = text(arguments, "list_id");
        String listName = text(arguments, "list_name");
        if (blank(listId) && blank(listName)) {
            return failure("missing_destination_list", "Provide list_name or list_id for the destination list.");
        }

        BoardListMatch target = resolveAllowedTarget(config, listId, listName);
        if (target.list() == null) {
            return failure("trello_move_not_allowed", target.error());
        }

        trello.moveCardToList(config, card.id(), target.list().id());
        return success(Map.of(
                "status",
                "card_moved",
                "card_id",
                card.id(),
                "list_id",
                target.list().id(),
                "list_name",
                target.list().name()));
    }

    private BoardListMatch resolveAllowedTarget(EffectiveConfig config, String listId, String listName) {
        List<TrelloClient.BoardList> lists = trello.fetchBoardLists(config);
        List<TrelloClient.BoardList> openLists =
                lists.stream().filter(list -> !list.closed()).toList();
        if (!blank(listId)) {
            return openLists.stream()
                    .filter(list -> list.id().equals(listId))
                    .findAny()
                    .map(list -> allowedTargetById(config, list, openLists))
                    .orElseGet(() -> new BoardListMatch(null, "Destination list is not open on the configured board."));
        }

        List<TrelloClient.BoardList> nameMatches = openLists.stream()
                .filter(list -> StateNames.normalize(list.name()).equals(StateNames.normalize(listName)))
                .toList();
        if (nameMatches.size() > 1) {
            return new BoardListMatch(
                    null,
                    "Destination list name matches multiple open Trello lists. Rename the duplicate lists or move by list_id.");
        }
        if (nameMatches.isEmpty()) {
            return new BoardListMatch(null, "Destination list is not open on the configured board.");
        }
        return allowedTarget(config, nameMatches.getFirst());
    }

    private BoardListMatch allowedTargetById(
            EffectiveConfig config, TrelloClient.BoardList list, List<TrelloClient.BoardList> openLists) {
        if (allowedById(config, list)) {
            return new BoardListMatch(list, null);
        }
        if (allowedByName(config, list) && hasUniqueOpenListName(list, openLists)) {
            return new BoardListMatch(list, null);
        }
        if (allowedByName(config, list)) {
            return new BoardListMatch(
                    null,
                    "Destination list name matches multiple open Trello lists. Allow the exact list_id before moving by list_id.");
        }
        return new BoardListMatch(null, "Destination list is not included in the configured Trello move allowlist.");
    }

    private BoardListMatch allowedTarget(EffectiveConfig config, TrelloClient.BoardList list) {
        return allowedById(config, list) || allowedByName(config, list)
                ? new BoardListMatch(list, null)
                : new BoardListMatch(null, "Destination list is not included in the configured Trello move allowlist.");
    }

    private boolean hasUniqueOpenListName(TrelloClient.BoardList list, List<TrelloClient.BoardList> openLists) {
        String normalized = StateNames.normalize(list.name());
        return openLists.stream()
                        .filter(candidate -> normalized.equals(StateNames.normalize(candidate.name())))
                        .count()
                == 1;
    }

    private boolean allowedById(EffectiveConfig config, TrelloClient.BoardList list) {
        return config.trelloTools().allowedMoveListIds().contains(list.id());
    }

    private boolean allowedByName(EffectiveConfig config, TrelloClient.BoardList list) {
        String normalized = StateNames.normalize(list.name());
        return config.trelloTools().allowedMoveListNames().contains(normalized);
    }

    private boolean writesEnabled(EffectiveConfig config) {
        return config.trelloTools().enabled() && config.trelloTools().allowWrites();
    }

    private boolean moveAllowlistConfigured(EffectiveConfig config) {
        return !config.trelloTools().allowedMoveListIds().isEmpty()
                || !config.trelloTools().allowedMoveListNames().isEmpty();
    }

    private ObjectNode tool(String name, String description, ObjectNode inputSchema) {
        return object("namespace", "symphony", "name", name, "description", description, "inputSchema", inputSchema);
    }

    private ObjectNode objectSchema(Map<String, ObjectNode> properties, List<String> required) {
        ObjectNode schema = object("type", "object", "additionalProperties", false);
        ObjectNode propertySchema = json.createObjectNode();
        properties.forEach(propertySchema::set);
        schema.set("properties", propertySchema);
        schema.set("required", json.valueToTree(required));
        return schema;
    }

    private ObjectNode stringSchema(String description) {
        return object("type", "string", "minLength", 1, "description", description);
    }

    private ObjectNode enumStringSchema(String description, List<String> values) {
        ObjectNode schema = stringSchema(description);
        schema.set("enum", json.valueToTree(values));
        return schema;
    }

    private ObjectNode booleanSchema(String description) {
        return object("type", "boolean", "description", description);
    }

    private ObjectNode success(Map<String, String> payload) {
        ObjectNode result = object("success", true);
        result.set("contentItems", json.createArrayNode().add(inputText(toJson(payload))));
        return result;
    }

    private ObjectNode failure(String code, String message) {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("error", code);
        payload.put("message", blank(message) ? code : message);
        ObjectNode result = object("success", false);
        result.set("contentItems", json.createArrayNode().add(inputText(toJson(payload))));
        return result;
    }

    private ObjectNode inputText(String text) {
        return object("type", "inputText", "text", text);
    }

    private String toJson(Map<String, String> payload) {
        try {
            return json.writeValueAsString(payload);
        } catch (Exception e) {
            return payload.toString();
        }
    }

    private static String string(Object value) {
        return value == null ? "" : value.toString();
    }

    private ObjectNode object(Object... keyValues) {
        ObjectNode node = json.createObjectNode();
        for (int i = 0; i < keyValues.length; i += 2) {
            node.set(keyValues[i].toString(), json.valueToTree(keyValues[i + 1]));
        }
        return node;
    }

    private static String requiredText(JsonNode node, String key) {
        String value = text(node, key);
        checkArgument(!blank(value), "Missing required argument: %s", key);
        return value;
    }

    private static boolean requiredBoolean(JsonNode node, String key) {
        JsonNode value = node.path(key);
        checkArgument(!value.isMissingNode() && !value.isNull(), "Missing required argument: %s", key);
        checkArgument(value.isBoolean(), "Argument %s must be a boolean", key);
        return value.asBoolean();
    }

    private static String text(JsonNode node, String key) {
        JsonNode value = node.path(key);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        checkArgument(value.isTextual(), "Argument %s must be a string", key);
        return value.textValue();
    }

    private static boolean validAttachmentUrl(String value) {
        if (unsafeToolLine(value)) {
            return false;
        }
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme();
            return ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    && !blank(uri.getHost())
                    && uri.getRawUserInfo() == null
                    && uri.getRawQuery() == null
                    && uri.getRawFragment() == null;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static boolean unsafeToolLine(String value) {
        if (value == null) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char c = value.charAt(index);
            if (c < ' ' || c == 0x7F) {
                return true;
            }
        }
        return false;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private record BoardListMatch(TrelloClient.BoardList list, String error) {}

    private record BlockerRecheckComments(Card.@Nullable Comment blocker, List<Card.Comment> managedComments) {
        private BlockerRecheckComments {
            managedComments = List.copyOf(managedComments);
        }
    }
}
