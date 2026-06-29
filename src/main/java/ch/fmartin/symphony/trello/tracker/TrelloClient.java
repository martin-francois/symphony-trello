package ch.fmartin.symphony.trello.tracker;

import static com.google.common.collect.ImmutableSet.toImmutableSet;

import ch.fmartin.symphony.trello.config.EffectiveConfig;
import ch.fmartin.symphony.trello.config.StateNames;
import ch.fmartin.symphony.trello.config.WholeNumbers;
import ch.fmartin.symphony.trello.config.WholeNumbers.Classified;
import ch.fmartin.symphony.trello.config.WholeNumbers.Kind;
import ch.fmartin.symphony.trello.domain.BlockerRef;
import ch.fmartin.symphony.trello.domain.Card;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.core.Response.Status;
import jakarta.ws.rs.core.Response.Status.Family;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jboss.logging.Logger;

@ApplicationScoped
public class TrelloClient implements TrackerClient {
    private static final Logger LOG = Logger.getLogger(TrelloClient.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final TypeReference<List<Map<String, Object>>> LIST_MAP_TYPE = new TypeReference<>() {};
    private static final String CARD_FIELDS =
            "id,name,desc,idList,idBoard,closed,idShort,shortLink,shortUrl,url,labels,dateLastActivity,due,dueComplete,pos,badges";
    private static final String COMMENT_ACTION_FIELDS = "data,date,memberCreator";
    private static final String COMMENT_ACTION_FIELDS_WITH_ID = "id,data,date,memberCreator";
    public static final String WORKPAD_MARKER = "## Codex Workpad";
    public static final String WAITING_COMMENT_MARKER = "## Symphony Waiting for Prerequisites";
    public static final String PREREQUISITE_STATUS_COMMENT_MARKER = "## Symphony Prerequisite Status";
    public static final int RECENT_COMMENT_ACTION_LIMIT = 20;
    public static final int WORKPAD_COMMENT_ACTION_LIMIT = 1000;

    private final ObjectMapper json;
    private final HttpClient httpClient;

    public TrelloClient(ObjectMapper json) {
        this.json = json;
        this.httpClient =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    @Override
    public String resolveBoardId(EffectiveConfig config) {
        Map<String, Object> board = getMap(
                config, "boards/" + encodeSegment(config.tracker().boardId()), Map.of("fields", "id,name,closed"));
        if (bool(board.get("closed"))) {
            throw new TrelloException("trello_board_closed", "Configured Trello board is archived");
        }
        return requiredString(board, "id", "trello_unknown_payload");
    }

    @Override
    public List<Card> fetchCandidateCards(EffectiveConfig config) {
        BoardContext context = boardContext(config);
        List<Map<String, Object>> payload = getList(
                config,
                "boards/" + encodeSegment(context.boardId()) + "/cards/open",
                Map.of("fields", CARD_FIELDS, "filter", "open"));
        List<Card> candidates = new ArrayList<>();
        Set<String> cardsWithComments = new HashSet<>();
        for (Map<String, Object> cardPayload : payload) {
            normalize(cardPayload, context, config).ifPresent(card -> {
                if (!isActive(card, config) || isTerminal(card, config)) {
                    return;
                }
                if (hasComments(cardPayload)) {
                    cardsWithComments.add(card.id());
                }
                candidates.add(
                        hasChecklistItems(cardPayload)
                                ? card.withChecklists(fetchChecklists(config, card.id()))
                                : card);
            });
        }
        return enrichPrerequisites(config, candidates, false, cardsWithComments);
    }

    @Override
    public List<Card> fetchTerminalCards(EffectiveConfig config) {
        BoardContext context = boardContext(config);
        List<Map<String, Object>> boardCards = getList(
                config,
                "boards/" + encodeSegment(context.boardId()) + "/cards/all",
                Map.of("fields", CARD_FIELDS, "filter", "all"));
        List<Card> normalized = boardCards.stream()
                .map(card -> normalize(card, context, config))
                .flatMap(Optional::stream)
                .filter(card -> isTerminal(card, config))
                // Keep this mutable: archived terminal cards are appended below before de-duplication.
                .collect(Collectors.toCollection(ArrayList::new));

        Set<String> archivedListIds = context.lists().values().stream()
                .filter(BoardList::closed)
                .map(BoardList::id)
                .collect(toImmutableSet());
        for (String listId : archivedListIds) {
            List<Map<String, Object>> listCards = getList(
                    config,
                    "lists/" + encodeSegment(listId) + "/cards",
                    Map.of("fields", CARD_FIELDS, "filter", "all"));
            appendTerminalCards(normalized, listCards, context, config);
        }

        // Preserve Trello encounter order while de-duplicating cards returned by board and archived-list endpoints.
        return normalized.stream()
                .collect(Collectors.toMap(Card::id, Function.identity(), (left, right) -> left, LinkedHashMap::new))
                .values()
                .stream()
                .toList();
    }

    private void appendTerminalCards(
            List<Card> cards, List<Map<String, Object>> payloads, BoardContext context, EffectiveConfig config) {
        for (Map<String, Object> payload : payloads) {
            normalize(payload, context, config)
                    .filter(card -> isTerminal(card, config))
                    .ifPresent(cards::add);
        }
    }

    @Override
    public Map<String, CardLookupResult> fetchCardStatesByIds(EffectiveConfig config, List<String> cardIds) {
        return fetchCardStatesByIds(config, cardIds, false);
    }

    @Override
    public Map<String, CardLookupResult> fetchCardStatesForPromptByIds(EffectiveConfig config, List<String> cardIds) {
        Map<String, CardLookupResult> results = fetchCardStatesByIds(config, cardIds, true);
        List<Card> foundCards = results.values().stream()
                .filter(CardLookupResult.Found.class::isInstance)
                .map(CardLookupResult.Found.class::cast)
                .map(CardLookupResult.Found::card)
                .toList();
        Map<String, Card> enriched =
                enrichPrerequisites(config, foundCards, true, cardsWithComments(foundCards)).stream()
                        .collect(Collectors.toMap(
                                Card::id, Function.identity(), (left, right) -> left, LinkedHashMap::new));
        Map<String, CardLookupResult> updated = new LinkedHashMap<>();
        results.forEach((cardId, result) -> {
            if (result instanceof CardLookupResult.Found found) {
                updated.put(
                        cardId,
                        new CardLookupResult.Found(
                                enriched.getOrDefault(found.card().id(), found.card())));
            } else {
                updated.put(cardId, result);
            }
        });
        return updated;
    }

    @Override
    public Card prepareForDispatch(EffectiveConfig config, Card card) {
        String inProgressState = config.tracker().inProgressState();
        if (blank(inProgressState)
                || StateNames.normalize(inProgressState).equals(StateNames.normalize(card.state()))) {
            return card;
        }

        BoardContext context = boardContext(config);
        Optional<BoardList> target = context.lists().values().stream()
                .filter(list -> !list.closed())
                .filter(list -> StateNames.normalize(list.name()).equals(StateNames.normalize(inProgressState)))
                .findAny();
        BoardList targetList = target.orElseThrow(() -> new TrelloException(
                "trello_in_progress_list_not_found", "Configured in-progress list was not found on the Trello board"));
        if (!shouldMoveBeforeDispatch(config, card, targetList)) {
            return card;
        }

        moveCardToList(config, card.id(), targetList.id());
        CardLookupResult refreshed =
                fetchCardStatesForPromptByIds(config, List.of(card.id())).get(card.id());
        if (refreshed instanceof CardLookupResult.Found found) {
            if (!targetList.id().equals(found.card().listId())) {
                throw new TrelloException(
                        "trello_in_progress_move_not_visible",
                        "Card was not visible in the configured in-progress list after Trello accepted the move");
            }
            return found.card();
        }
        if (refreshed instanceof CardLookupResult.Failed failed) {
            throw new TrelloException(failed.code(), failed.message());
        }
        throw new TrelloException("trello_card_missing", "Card disappeared after moving to in-progress list");
    }

    @Override
    public void releaseFromDispatch(EffectiveConfig config, Card card) {
        releaseFromDispatch(config, card, card);
    }

    @Override
    public void releaseFromDispatch(EffectiveConfig config, Card card, Card dispatchSource) {
        String inProgressState = config.tracker().inProgressState();
        if (blank(inProgressState)
                || !StateNames.normalize(inProgressState).equals(StateNames.normalize(card.state()))) {
            return;
        }

        BoardContext context = boardContext(config);
        releaseTarget(config, card, dispatchSource, context)
                .ifPresent(target -> moveCardToList(config, card.id(), target.id()));
    }

    private Map<String, CardLookupResult> fetchCardStatesByIds(
            EffectiveConfig config, List<String> cardIds, boolean includeOlderWorkpad) {
        BoardContext context = boardContext(config);
        Map<String, CardLookupResult> results = new LinkedHashMap<>();
        for (String cardId : cardIds) {
            try {
                Map<String, Object> payload =
                        cardWithComments(config, cardId, RECENT_COMMENT_ACTION_LIMIT, includeOlderWorkpad);
                if (includeOlderWorkpad) {
                    payload = includeOlderWorkpadComment(config, cardId, payload);
                }
                Optional<Card> card = normalize(payload, context, config);
                results.put(
                        cardId,
                        card.<CardLookupResult>map(CardLookupResult.Found::new)
                                .orElseGet(() -> new CardLookupResult.Failed(
                                        cardId, "trello_unknown_payload", "Card payload could not be normalized")));
            } catch (TrelloException e) {
                if (isNotFound(e)) {
                    results.put(cardId, new CardLookupResult.Missing(cardId));
                } else {
                    results.put(cardId, new CardLookupResult.Failed(cardId, e.code(), e.getMessage()));
                }
            }
        }
        return results;
    }

    public CardLookupResult fetchCardStateForWorkpad(EffectiveConfig config, String cardId) {
        BoardContext context = boardContext(config);
        try {
            Map<String, Object> payload = cardWithComments(config, cardId, WORKPAD_COMMENT_ACTION_LIMIT);
            Optional<Card> card = normalize(payload, context, config);
            return card.<CardLookupResult>map(CardLookupResult.Found::new)
                    .orElseGet(() -> new CardLookupResult.Failed(
                            cardId, "trello_unknown_payload", "Card payload could not be normalized"));
        } catch (TrelloException e) {
            if (isNotFound(e)) {
                return new CardLookupResult.Missing(cardId);
            }
            return new CardLookupResult.Failed(cardId, e.code(), e.getMessage());
        }
    }

    private Map<String, Object> cardWithComments(EffectiveConfig config, String cardId, int actionLimit) {
        return cardWithComments(config, cardId, actionLimit, false);
    }

    private Map<String, Object> cardWithComments(
            EffectiveConfig config, String cardId, int actionLimit, boolean includePromptContext) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("fields", CARD_FIELDS);
        query.put("attachments", includePromptContext ? "true" : "false");
        if (includePromptContext) {
            query.put("attachment_fields", "name,url");
            query.put("checklists", "all");
            query.put("checklist_fields", "id,name");
            query.put("checkItem_fields", "id,name,state");
        }
        query.put("actions", "commentCard");
        query.put("actions_limit", Integer.toString(actionLimit));
        query.put(
                "action_fields",
                actionLimit == WORKPAD_COMMENT_ACTION_LIMIT ? COMMENT_ACTION_FIELDS_WITH_ID : COMMENT_ACTION_FIELDS);
        return getMap(config, "cards/" + encodeSegment(cardId), query);
    }

    private List<Card.Checklist> fetchChecklists(EffectiveConfig config, String cardId) {
        List<Map<String, Object>> payload = getList(
                config,
                "cards/" + encodeSegment(cardId) + "/checklists",
                Map.of("fields", "id,name", "checkItem_fields", "id,name,state"));
        return payload.stream().map(TrelloClient::checklist).toList();
    }

    private Map<String, Object> includeOlderWorkpadComment(
            EffectiveConfig config, String cardId, Map<String, Object> recentPayload) {
        List<Map<String, Object>> recentActions = actionMaps(recentPayload);
        if (hasWorkpadComment(recentActions) || recentActions.size() < RECENT_COMMENT_ACTION_LIMIT) {
            return recentPayload;
        }

        Map<String, Object> deepPayload;
        try {
            deepPayload = cardWithComments(config, cardId, WORKPAD_COMMENT_ACTION_LIMIT);
        } catch (TrelloException e) {
            if (isNotFound(e)) {
                throw e;
            }
            return recentPayload;
        }
        Optional<Map<String, Object>> workpad = actionMaps(deepPayload).stream()
                .filter(action -> commentText(action).startsWith(WORKPAD_MARKER))
                .findFirst();
        return workpad.map(existingWorkpad -> payloadWithWorkpadAction(recentPayload, recentActions, existingWorkpad))
                .orElse(recentPayload);
    }

    private static Map<String, Object> payloadWithWorkpadAction(
            Map<String, Object> recentPayload,
            List<Map<String, Object>> recentActions,
            Map<String, Object> existingWorkpad) {
        List<Map<String, Object>> mergedActions = new ArrayList<>();
        mergedActions.add(existingWorkpad);
        mergedActions.addAll(recentActions);
        Map<String, Object> mergedPayload = new LinkedHashMap<>(recentPayload);
        mergedPayload.put("actions", mergedActions);
        return mergedPayload;
    }

    private List<Card> enrichPrerequisites(
            EffectiveConfig config, List<Card> cards, boolean includeReferenceContext, Set<String> cardsWithComments) {
        if (cards.isEmpty()) {
            return List.of();
        }
        List<PrerequisiteAnalysis> analyses = cards.stream()
                .map(card -> analyzePrerequisites(card, includeReferenceContext))
                .toList();
        Map<String, CardLookupResult> lookupResults = lookupReferencedCards(config, analyses);
        List<Card> enriched = new ArrayList<>();
        for (PrerequisiteAnalysis analysis : analyses) {
            enriched.add(enrichPrerequisiteCard(
                    config,
                    analysis,
                    lookupResults,
                    cardsWithComments.contains(analysis.card().id())));
        }
        return enriched;
    }

    private static PrerequisiteAnalysis analyzePrerequisites(Card card, boolean includeReferenceContext) {
        PrerequisitePlan plan = prerequisitePlan(card);
        Map<String, ReferencedText> promptReferences =
                includeReferenceContext ? promptReferenceTexts(card, plan) : Map.of();
        return new PrerequisiteAnalysis(card, plan, promptReferences);
    }

    private Map<String, CardLookupResult> lookupReferencedCards(
            EffectiveConfig config, List<PrerequisiteAnalysis> analyses) {
        List<String> lookupIds =
                analyses.stream().flatMap(TrelloClient::lookupIds).distinct().toList();
        return lookupIds.isEmpty() ? Map.of() : fetchCardStatesByIds(config, lookupIds);
    }

    private static Stream<String> lookupIds(PrerequisiteAnalysis analysis) {
        Stream<String> prerequisiteLookupIds = analysis.plan().items().stream()
                .map(TrelloChecklistClassifier.PrerequisiteItem::reference)
                .map(TrelloCardReference::lookupId);
        Stream<String> promptLookupIds = analysis.promptReferences().values().stream()
                .map(ReferencedText::reference)
                .map(TrelloCardReference::lookupId);
        return Stream.concat(prerequisiteLookupIds, promptLookupIds);
    }

    private Card enrichPrerequisiteCard(
            EffectiveConfig config,
            PrerequisiteAnalysis analysis,
            Map<String, CardLookupResult> lookupResults,
            boolean mayHaveWaitingComment) {
        Card card = analysis.card();
        ResolvedPrerequisites resolved = resolvePrerequisites(config, card, analysis.plan(), lookupResults);
        List<Card.TrelloReference> references = promptReferences(config, analysis.promptReferences(), lookupResults);
        Card enriched = card.withRelationships(card.checklists(), references, resolved.problems(), resolved.blockers());
        syncPrerequisiteWaitingFeedback(config, enriched, mayHaveWaitingComment);
        return enriched;
    }

    private static Set<String> cardsWithComments(List<Card> cards) {
        return cards.stream()
                .filter(card -> !card.comments().isEmpty())
                .map(Card::id)
                .collect(Collectors.toUnmodifiableSet());
    }

    private static PrerequisitePlan prerequisitePlan(Card card) {
        List<TrelloChecklistClassifier.PrerequisiteItem> items = new ArrayList<>();
        List<Card.PrerequisiteProblem> problems = new ArrayList<>();
        for (Card.Checklist checklist : card.checklists()) {
            TrelloChecklistClassifier.ChecklistAnalysis analysis = TrelloChecklistClassifier.analyze(checklist);
            items.addAll(analysis.prerequisites());
            problems.addAll(analysis.problems());
        }
        return new PrerequisitePlan(List.copyOf(items), List.copyOf(problems));
    }

    private ResolvedPrerequisites resolvePrerequisites(
            EffectiveConfig config, Card card, PrerequisitePlan plan, Map<String, CardLookupResult> lookupResults) {
        List<Card.PrerequisiteProblem> problems = new ArrayList<>(plan.problems());
        // Preserve prerequisite checklist order so generated blockers follow the operator-authored checklist.
        Map<String, BlockerRef> blockers = LinkedHashMap.newLinkedHashMap(
                plan.items().size() + (plan.problems().isEmpty() ? 0 : 1));
        boolean continueChecklistSync = true;
        for (TrelloChecklistClassifier.PrerequisiteItem item : plan.items()) {
            TrelloCardReference reference = item.reference();
            CardLookupResult result = lookupResults.get(reference.lookupId());
            BlockerRef blocker = blockerRef(config, card, reference, result, problems);
            blockers.putIfAbsent(blocker.id() == null ? reference.key() : blocker.id(), blocker);
            if (continueChecklistSync) {
                continueChecklistSync = syncPrerequisiteItem(config, card, item, result, problems);
            }
        }
        if (!plan.problems().isEmpty()) {
            String blockerId = "ambiguous-prerequisite:" + card.id();
            blockers.putIfAbsent(
                    blockerId, new BlockerRef(blockerId, "Ambiguous prerequisite checklist", null, card.cardUrl()));
        }
        return new ResolvedPrerequisites(List.copyOf(problems), List.copyOf(blockers.values()));
    }

    private BlockerRef blockerRef(
            EffectiveConfig config,
            Card card,
            TrelloCardReference reference,
            CardLookupResult result,
            List<Card.PrerequisiteProblem> problems) {
        if (result instanceof CardLookupResult.Found found) {
            Card blocker = found.card();
            if (Objects.equals(card.id(), blocker.id())) {
                problems.add(new Card.PrerequisiteProblem(
                        "trello_prerequisite_self_reference",
                        "A Trello card cannot use itself as a prerequisite.",
                        null));
                return unresolvedBlocker(reference, "Self prerequisite");
            }
            if (isCrossBoard(config, blocker)) {
                problems.add(new Card.PrerequisiteProblem(
                        "trello_prerequisite_cross_board",
                        "A prerequisite card is outside the configured Trello board.",
                        null));
                return unresolvedBlocker(reference, "Unsupported cross-board prerequisite");
            }
            return new BlockerRef(blocker.id(), blocker.identifier(), blocker.state(), blocker.cardUrl());
        }
        if (result instanceof CardLookupResult.Failed failed) {
            problems.add(
                    new Card.PrerequisiteProblem(failed.code(), "Could not resolve a prerequisite Trello card.", null));
            return unresolvedBlocker(reference, "Unresolved prerequisite");
        }
        problems.add(new Card.PrerequisiteProblem(
                "trello_prerequisite_missing", "A prerequisite Trello card is missing or inaccessible.", null));
        return unresolvedBlocker(reference, "Missing prerequisite");
    }

    private static BlockerRef unresolvedBlocker(TrelloCardReference reference, String identifier) {
        return new BlockerRef(reference.lookupId(), identifier, null, reference.url());
    }

    private boolean syncPrerequisiteItem(
            EffectiveConfig config,
            Card card,
            TrelloChecklistClassifier.PrerequisiteItem item,
            CardLookupResult result,
            List<Card.PrerequisiteProblem> problems) {
        if (blank(item.item().id())) {
            return true;
        }
        boolean targetComplete = isResolvedTerminal(config, card, result);
        if (item.item().complete() == targetComplete) {
            return true;
        }
        try {
            updateCheckItemState(config, card.id(), item.item().id(), targetComplete);
            return true;
        } catch (RuntimeException e) {
            problems.add(new Card.PrerequisiteProblem(
                    "trello_prerequisite_checklist_sync_failed",
                    "Could not update a prerequisite checklist item.",
                    item.checklist().name()));
            LOG.warnf(
                    "card_id=%s checklist_id=%s check_item_id=%s prerequisite_sync=failed reason=%s",
                    card.id(), item.checklist().id(), item.item().id(), e.getMessage());
            return false;
        }
    }

    private boolean isResolvedTerminal(EffectiveConfig config, Card currentCard, CardLookupResult result) {
        if (!(result instanceof CardLookupResult.Found found)) {
            return false;
        }
        Card prerequisite = found.card();
        return isSupportedPrerequisite(config, currentCard, prerequisite) && isTerminal(prerequisite, config);
    }

    private static boolean isSupportedPrerequisite(EffectiveConfig config, Card currentCard, Card prerequisite) {
        return !Objects.equals(currentCard.id(), prerequisite.id()) && isOnConfiguredBoard(config, prerequisite);
    }

    private static boolean isOnConfiguredBoard(EffectiveConfig config, Card card) {
        return card.boardId() == null
                || Objects.equals(card.boardId(), config.tracker().resolvedBoardId());
    }

    private static boolean isCrossBoard(EffectiveConfig config, Card card) {
        return card.boardId() != null
                && !Objects.equals(card.boardId(), config.tracker().resolvedBoardId());
    }

    private List<Card.TrelloReference> promptReferences(
            EffectiveConfig config,
            Map<String, ReferencedText> references,
            Map<String, CardLookupResult> lookupResults) {
        if (references.isEmpty()) {
            return List.of();
        }
        return references.values().stream()
                .map(reference -> promptReference(
                        config,
                        reference,
                        lookupResults.get(reference.reference().lookupId())))
                .toList();
    }

    private static Map<String, ReferencedText> promptReferenceTexts(Card card, PrerequisitePlan plan) {
        ReferenceAccumulator references = new ReferenceAccumulator();
        references.add("title", card.title());
        references.add("description", card.description());
        for (Card.Checklist checklist : card.checklists()) {
            references.addChecklist(checklist);
        }
        for (Card.Comment comment : card.comments()) {
            references.add("comment", comment.text());
        }
        for (Card.Attachment attachment : card.attachments()) {
            references.addAttachment(attachment);
        }
        for (TrelloChecklistClassifier.PrerequisiteItem item : plan.items()) {
            references.addPrerequisite(item.reference());
        }
        return references.asMap();
    }

    private static Card.TrelloReference promptReference(
            EffectiveConfig config, ReferencedText reference, CardLookupResult result) {
        if (result instanceof CardLookupResult.Found found) {
            Card card = found.card();
            Boolean terminal = card.boardId() != null
                            && !Objects.equals(card.boardId(), config.tracker().resolvedBoardId())
                    ? null
                    : isTerminal(card, config);
            String status = terminal == null ? "unsupported_cross_board" : "found";
            return new Card.TrelloReference(
                    reference.source(),
                    reference.text(),
                    reference.reference().lookupId(),
                    card.identifier(),
                    card.title(),
                    card.state(),
                    card.cardUrl(),
                    status,
                    terminal);
        }
        if (result instanceof CardLookupResult.Failed failed) {
            return new Card.TrelloReference(
                    reference.source(),
                    reference.text(),
                    reference.reference().lookupId(),
                    null,
                    null,
                    null,
                    reference.reference().url(),
                    failed.code(),
                    null);
        }
        return new Card.TrelloReference(
                reference.source(),
                reference.text(),
                reference.reference().lookupId(),
                null,
                null,
                null,
                reference.reference().url(),
                "missing",
                null);
    }

    private void syncPrerequisiteWaitingFeedback(EffectiveConfig config, Card card, boolean mayHaveWaitingComment) {
        if (!config.tracker().blockerEnforcedStates().contains(StateNames.normalize(card.state()))) {
            return;
        }
        boolean waiting = card.blockedBy().stream()
                .anyMatch(blocker -> blocker.state() == null
                        || !config.tracker().terminalStates().contains(StateNames.normalize(blocker.state())));
        if (!waiting && card.prerequisiteProblems().isEmpty()) {
            if (!card.checklists().isEmpty() || mayHaveWaitingComment) {
                clearPrerequisiteWaitingComment(config, card);
            }
        } else if (waiting || !card.prerequisiteProblems().isEmpty()) {
            upsertPrerequisiteWaitingComment(config, card, prerequisiteWaitingText(card));
        }
    }

    private void upsertPrerequisiteWaitingComment(EffectiveConfig config, Card card, String text) {
        try {
            prerequisiteWaitingComment(config, card.id())
                    .ifPresentOrElse(
                            comment -> updateOrCreatePrerequisiteWaitingComment(config, card, comment, text),
                            () -> addComment(config, card.id(), text));
        } catch (RuntimeException e) {
            LOG.warnf("card_id=%s prerequisite_waiting_comment=failed reason=%s", card.id(), e.getMessage());
        }
    }

    private void updateOrCreatePrerequisiteWaitingComment(
            EffectiveConfig config, Card card, Card.Comment existing, String text) {
        if (text.equals(existing.text())) {
            return;
        }
        if (blank(existing.id())) {
            addComment(config, card.id(), text);
            return;
        }
        updateComment(config, existing.id(), text);
    }

    private void clearPrerequisiteWaitingComment(EffectiveConfig config, Card card) {
        try {
            Optional<Card.Comment> existing = prerequisiteWaitingComment(config, card.id());
            String text = resolvedPrerequisiteStatusText();
            existing.filter(comment -> !blank(comment.id()))
                    .filter(comment -> !text.equals(comment.text()))
                    .ifPresent(comment -> updateComment(config, comment.id(), text));
        } catch (RuntimeException e) {
            LOG.warnf("card_id=%s prerequisite_waiting_comment_clear=failed reason=%s", card.id(), e.getMessage());
        }
    }

    private Optional<Card.Comment> prerequisiteWaitingComment(EffectiveConfig config, String cardId) {
        Map<String, Object> payload = getMap(
                config,
                "cards/" + encodeSegment(cardId),
                Map.of(
                        "fields",
                        CARD_FIELDS,
                        "attachments",
                        "false",
                        "actions",
                        "commentCard",
                        "actions_limit",
                        Integer.toString(WORKPAD_COMMENT_ACTION_LIMIT),
                        "action_fields",
                        COMMENT_ACTION_FIELDS_WITH_ID));
        return comments(payload).stream()
                .filter(comment -> isManagedPrerequisiteStatusComment(comment.text()))
                .findFirst();
    }

    private static boolean isManagedPrerequisiteStatusComment(String text) {
        return text != null
                && (text.startsWith(PREREQUISITE_STATUS_COMMENT_MARKER) || text.startsWith(WAITING_COMMENT_MARKER));
    }

    private static String prerequisiteWaitingText(Card card) {
        List<String> lines = new ArrayList<>();
        lines.add(PREREQUISITE_STATUS_COMMENT_MARKER);
        lines.add("");
        lines.add("Status: waiting for prerequisites.");
        lines.add("");
        lines.add("Symphony has not started this Trello card because prerequisite checklists are not resolved.");
        if (!card.blockedBy().isEmpty()) {
            lines.add("");
            lines.add("Waiting for:");
            for (BlockerRef blocker : card.blockedBy()) {
                lines.add("- " + waitingBlockerText(blocker));
            }
        }
        if (!card.prerequisiteProblems().isEmpty()) {
            lines.add("");
            lines.add("Checklist cleanup needed:");
            for (Card.PrerequisiteProblem problem : card.prerequisiteProblems()) {
                lines.add("- " + problem.message()
                        + (blank(problem.checklist()) ? "" : " Checklist: " + problem.checklist() + "."));
            }
        }
        lines.add("");
        lines.add(
                "Fix by making prerequisite checklist items exactly one bare Trello card reference each, moving notes to a separate checklist, or writing non-prerequisite Trello references as Markdown links.");
        return String.join("\n", lines);
    }

    private static String resolvedPrerequisiteStatusText() {
        return String.join(
                "\n",
                PREREQUISITE_STATUS_COMMENT_MARKER,
                "",
                "Status: prerequisites resolved.",
                "",
                "Symphony may start this Trello card when other dispatch rules allow.");
    }

    private static String waitingBlockerText(BlockerRef blocker) {
        String identifier = blank(blocker.identifier()) ? blocker.id() : blocker.identifier();
        String state = blank(blocker.state()) ? "unresolved" : blocker.state();
        return identifier + " (" + state + ")";
    }

    private void updateCheckItemState(EffectiveConfig config, String cardId, String checkItemId, boolean complete) {
        putMap(
                config,
                "cards/" + encodeSegment(cardId) + "/checkItem/" + encodeSegment(checkItemId),
                Map.of("state", complete ? "complete" : "incomplete"));
    }

    private static boolean hasWorkpadComment(List<Map<String, Object>> actions) {
        return actions.stream().anyMatch(action -> commentText(action).startsWith(WORKPAD_MARKER));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> actionMaps(Map<String, Object> payload) {
        Object actions = payload.get("actions");
        if (!(actions instanceof List<?> actionList)) {
            return List.of();
        }
        return actionList.stream()
                .filter(Map.class::isInstance)
                .map(action -> (Map<String, Object>) action)
                .toList();
    }

    public static boolean isActive(Card card, EffectiveConfig config) {
        if (card.closed() || Boolean.TRUE.equals(card.listClosed()) || Boolean.TRUE.equals(card.boardClosed())) {
            return false;
        }
        if (card.boardId() != null
                && !Objects.equals(card.boardId(), config.tracker().resolvedBoardId())) {
            return false;
        }
        if (card.listId() != null && !config.tracker().activeListIds().isEmpty()) {
            return config.tracker().activeListIds().contains(card.listId());
        }
        return config.tracker().activeStates().stream()
                .map(StateNames::normalize)
                .anyMatch(state -> state.equals(StateNames.normalize(card.state())));
    }

    public static boolean isTerminal(Card card, EffectiveConfig config) {
        if (isOpenListBacked(card)
                && card.listId() != null
                && !config.tracker().terminalListIds().isEmpty()) {
            return config.tracker().terminalListIds().contains(card.listId());
        }
        return config.tracker().terminalStates().contains(StateNames.normalize(card.state()));
    }

    private static boolean isOpenListBacked(Card card) {
        return "list".equals(card.stateSource())
                && !card.closed()
                && !Boolean.TRUE.equals(card.listClosed())
                && !Boolean.TRUE.equals(card.boardClosed());
    }

    public static Comparator<Card> dispatchComparator(EffectiveConfig config) {
        return dispatchComparator(config, Map.of());
    }

    public static Comparator<Card> dispatchComparator(EffectiveConfig config, Map<String, Integer> priorityOverrides) {
        return Comparator.comparingInt((Card card) -> activeOrder(card, config))
                .thenComparingInt(card -> dispatchPriority(priorityOverrides, card))
                .thenComparing(card -> card.position() == null ? BigDecimal.valueOf(Long.MAX_VALUE) : card.position())
                .thenComparing(card -> card.createdAt() == null ? Instant.MAX : card.createdAt())
                .thenComparing(Card::identifier);
    }

    private static int dispatchPriority(Map<String, Integer> priorityOverrides, Card card) {
        Integer override = priorityOverrides.get(card.id());
        if (override != null) {
            return override;
        }
        Integer priority = card.priority();
        return priority == null ? Integer.MAX_VALUE : priority;
    }

    private static int activeOrder(Card card, EffectiveConfig config) {
        if (card.listId() != null && !config.tracker().activeListIds().isEmpty()) {
            int index = config.tracker().activeListIds().indexOf(card.listId());
            return index < 0
                    ? Integer.MAX_VALUE
                    : config.tracker().activeListIds().size() - 1 - index;
        }
        List<String> active = config.tracker().activeStates().stream()
                .map(StateNames::normalize)
                .toList();
        int index = active.indexOf(StateNames.normalize(card.state()));
        return index < 0 ? Integer.MAX_VALUE : active.size() - 1 - index;
    }

    private BoardContext boardContext(EffectiveConfig config) {
        Map<String, Object> board = getMap(
                config, "boards/" + encodeSegment(config.tracker().boardId()), Map.of("fields", "id,name,closed"));
        String boardId = requiredString(board, "id", "trello_unknown_payload");
        boolean boardClosed = bool(board.get("closed"));
        Map<String, BoardList> listMap = fetchBoardLists(config.withResolvedBoardId(boardId)).stream()
                .collect(Collectors.toMap(
                        BoardList::id, Function.identity(), (left, right) -> left, LinkedHashMap::new));
        return new BoardContext(boardId, boardClosed, listMap);
    }

    public List<BoardList> fetchBoardLists(EffectiveConfig config) {
        String boardId = config.tracker().resolvedBoardId();
        return getList(
                        config,
                        "boards/" + encodeSegment(boardId) + "/lists",
                        Map.of("filter", "all", "fields", "id,name,closed,pos"))
                .stream()
                .map(this::toList)
                .toList();
    }

    public Map<String, Object> addComment(EffectiveConfig config, String cardId, String text) {
        return postMap(config, "cards/" + encodeSegment(cardId) + "/actions/comments", Map.of("text", text));
    }

    public Map<String, Object> updateComment(EffectiveConfig config, String actionId, String text) {
        return putMap(config, "actions/" + encodeSegment(actionId) + "/text", Map.of("value", text));
    }

    public Map<String, Object> deleteComment(EffectiveConfig config, String actionId) {
        return request("DELETE", config, "actions/" + encodeSegment(actionId), Map.of(), MAP_TYPE, false);
    }

    public Map<String, Object> moveCardToList(EffectiveConfig config, String cardId, String listId) {
        return putMap(config, "cards/" + encodeSegment(cardId) + "/idList", Map.of("value", listId));
    }

    private static boolean shouldMoveBeforeDispatch(EffectiveConfig config, Card card, BoardList target) {
        if (card.listId() != null && !config.tracker().activeListIds().isEmpty()) {
            int currentIndex = config.tracker().activeListIds().indexOf(card.listId());
            int targetIndex = config.tracker().activeListIds().indexOf(target.id());
            return currentIndex >= 0 && targetIndex >= 0 && currentIndex < targetIndex;
        }
        List<String> active = config.tracker().activeStates().stream()
                .map(StateNames::normalize)
                .toList();
        int currentIndex = active.indexOf(StateNames.normalize(card.state()));
        int targetIndex = active.indexOf(StateNames.normalize(target.name()));
        return currentIndex >= 0 && targetIndex >= 0 && currentIndex < targetIndex;
    }

    private static Optional<BoardList> releaseTarget(
            EffectiveConfig config, Card card, Card dispatchSource, BoardContext context) {
        if (hasDispatchSource(config, dispatchSource)) {
            return releaseTargetFromDispatchSource(config, card, dispatchSource, context);
        }
        return releaseTarget(config, card, context);
    }

    private static Optional<BoardList> releaseTargetFromDispatchSource(
            EffectiveConfig config, Card card, Card dispatchSource, BoardContext context) {
        if (dispatchSource.listId() != null) {
            return Optional.ofNullable(context.lists().get(dispatchSource.listId()))
                    .filter(list -> !list.closed())
                    .filter(list -> !Objects.equals(list.id(), card.listId()))
                    .filter(list -> isDispatchSourceList(config, dispatchSource, list));
        }
        String sourceState = StateNames.normalize(dispatchSource.state());
        if (!isActiveState(config, sourceState)) {
            return Optional.empty();
        }
        return context.lists().values().stream()
                .filter(list -> !list.closed())
                .filter(list -> sourceState.equals(StateNames.normalize(list.name())))
                .findAny();
    }

    private static boolean hasDispatchSource(EffectiveConfig config, Card dispatchSource) {
        String sourceState = StateNames.normalize(dispatchSource.state());
        if (StateNames.normalize(config.tracker().inProgressState()).equals(sourceState)) {
            return false;
        }
        return config.tracker().activeListIds().contains(dispatchSource.listId()) || isActiveState(config, sourceState);
    }

    private static boolean isDispatchSourceList(EffectiveConfig config, Card dispatchSource, BoardList list) {
        if (!config.tracker().activeListIds().isEmpty()) {
            return config.tracker().activeListIds().contains(list.id());
        }
        return isActiveState(config, StateNames.normalize(dispatchSource.state()));
    }

    private static boolean isActiveState(EffectiveConfig config, String normalizedState) {
        return config.tracker().activeStates().stream()
                .map(StateNames::normalize)
                .anyMatch(normalizedState::equals);
    }

    private static Optional<BoardList> releaseTarget(EffectiveConfig config, Card card, BoardContext context) {
        if (card.listId() != null && !config.tracker().activeListIds().isEmpty()) {
            int currentIndex = config.tracker().activeListIds().indexOf(card.listId());
            if (currentIndex > 0) {
                return Optional.ofNullable(context.lists()
                                .get(config.tracker().activeListIds().get(currentIndex - 1)))
                        .filter(list -> !list.closed());
            }
        }

        List<String> active = config.tracker().activeStates().stream()
                .map(StateNames::normalize)
                .toList();
        int inProgressIndex =
                active.indexOf(StateNames.normalize(config.tracker().inProgressState()));
        if (inProgressIndex <= 0) {
            return Optional.empty();
        }
        Set<String> targetNames = Set.of(active.get(inProgressIndex - 1));
        return context.lists().values().stream()
                .filter(list -> !list.closed())
                .filter(list -> targetNames.contains(StateNames.normalize(list.name())))
                .findAny();
    }

    private Optional<Card> normalize(Map<String, Object> payload, BoardContext context, EffectiveConfig config) {
        String id = string(payload.get("id"));
        String name = string(payload.get("name"));
        String boardId = string(payload.get("idBoard"));
        String listId = string(payload.get("idList"));
        if (blank(id) || blank(name) || blank(boardId)) {
            LOG.warnf("trello_card outcome=skipped reason=missing_required_fields card_id=%s", id);
            return Optional.empty();
        }
        BoardList list = listId == null ? null : context.lists().get(listId);
        boolean cardClosed = bool(payload.get("closed"));
        String state;
        String source;
        if (context.boardClosed()) {
            state = "ArchivedBoard";
            source = "board_closed";
        } else if (cardClosed) {
            state = "Archived";
            source = "card_closed";
        } else if (list != null && list.closed()) {
            state = "ArchivedList";
            source = "list_closed";
        } else if (list != null) {
            state = list.name();
            source = "list";
        } else {
            state = "Unknown";
            source = "unknown";
        }

        List<String> labels = labels(payload);
        Integer priority = labels.stream()
                .map(label -> config.tracker().priorityLabels().get(StateNames.normalize(label)))
                .filter(Objects::nonNull)
                .min(Integer::compareTo)
                .orElse(null);

        return Optional.of(new Card(
                id,
                identifier(config, payload),
                name,
                string(payload.get("desc")),
                priority,
                state,
                source,
                listId,
                list == null ? null : list.name(),
                list == null ? null : list.closed(),
                boardId,
                context.boardClosed(),
                cardClosed,
                integer(payload.get("idShort"), "idShort"),
                string(payload.get("shortLink")),
                string(payload.get("shortUrl")),
                null,
                string(payload.get("url")),
                labels,
                labelIds(payload),
                List.of(),
                checklists(payload),
                attachments(payload),
                List.of(),
                List.of(),
                List.<BlockerRef>of(),
                comments(payload),
                createdAtFromObjectId(id),
                instant(payload.get("dateLastActivity")),
                instant(payload.get("due")),
                nullableBool(payload.get("dueComplete")),
                decimal(payload.get("pos"))));
    }

    private static List<Card.Comment> comments(Map<String, Object> payload) {
        Object actions = payload.get("actions");
        if (!(actions instanceof List<?> actionList)) {
            return List.of();
        }
        return actionList.stream()
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .map(TrelloClient::comment)
                .flatMap(Optional::stream)
                .toList();
    }

    @SuppressWarnings("unchecked")
    private static List<Card.Checklist> checklists(Map<String, Object> payload) {
        Object checklists = payload.get("checklists");
        if (!(checklists instanceof List<?> checklistList)) {
            return List.of();
        }
        return checklistList.stream()
                .filter(Map.class::isInstance)
                .map(checklist -> checklist((Map<String, Object>) checklist))
                .toList();
    }

    @SuppressWarnings("unchecked")
    private static Card.Checklist checklist(Map<String, Object> payload) {
        Object checkItems = payload.get("checkItems");
        List<Card.ChecklistItem> items = checkItems instanceof List<?> itemList
                ? itemList.stream()
                        .filter(Map.class::isInstance)
                        .map(item -> checklistItem((Map<String, Object>) item))
                        .toList()
                : List.of();
        return new Card.Checklist(string(payload.get("id")), string(payload.get("name")), items);
    }

    private static Card.ChecklistItem checklistItem(Map<String, Object> payload) {
        return new Card.ChecklistItem(
                string(payload.get("id")),
                string(payload.get("name")),
                "complete".equalsIgnoreCase(string(payload.get("state"))));
    }

    @SuppressWarnings("unchecked")
    private static List<Card.Attachment> attachments(Map<String, Object> payload) {
        Object attachments = payload.get("attachments");
        if (!(attachments instanceof List<?> attachmentList)) {
            return List.of();
        }
        return attachmentList.stream()
                .filter(Map.class::isInstance)
                .map(attachment -> attachment((Map<String, Object>) attachment))
                .toList();
    }

    private static Card.Attachment attachment(Map<String, Object> payload) {
        return new Card.Attachment(string(payload.get("id")), string(payload.get("name")), string(payload.get("url")));
    }

    private static Optional<Card.Comment> comment(Map<?, ?> action) {
        String text = commentText(action);
        if (blank(text)) {
            return Optional.empty();
        }
        return Optional.of(new Card.Comment(
                string(action.get("id")),
                text,
                commentAuthor(action.get("memberCreator")),
                instant(action.get("date"))));
    }

    private static String commentText(Map<?, ?> action) {
        Object data = action.get("data");
        if (!(data instanceof Map<?, ?> dataMap)) {
            return "";
        }
        String text = string(dataMap.get("text"));
        return text == null ? "" : text;
    }

    private static String commentAuthor(Object memberCreator) {
        if (!(memberCreator instanceof Map<?, ?> member)) {
            return null;
        }
        String fullName = string(member.get("fullName"));
        return blank(fullName) ? string(member.get("username")) : fullName;
    }

    private Map<String, Object> getMap(EffectiveConfig config, String path, Map<String, String> query) {
        return request("GET", config, path, query, MAP_TYPE, true);
    }

    private List<Map<String, Object>> getList(EffectiveConfig config, String path, Map<String, String> query) {
        return request("GET", config, path, query, LIST_MAP_TYPE, true);
    }

    private Map<String, Object> postMap(EffectiveConfig config, String path, Map<String, String> query) {
        // Do not automatically retry writes; a network failure after Trello applied a comment could duplicate it.
        return request("POST", config, path, query, MAP_TYPE, false);
    }

    private Map<String, Object> putMap(EffectiveConfig config, String path, Map<String, String> query) {
        return request("PUT", config, path, query, MAP_TYPE, false);
    }

    private <T> T request(
            String method,
            EffectiveConfig config,
            String path,
            Map<String, String> query,
            TypeReference<T> type,
            boolean retry) {
        URI uri = uri(config, path, query);
        int maxAttempts = retry ? Math.max(1, config.tracker().maxApiRetries() + 1) : 1;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                        .timeout(config.tracker().requestTimeout())
                        .header("Accept", "application/json")
                        .header("Authorization", authorization(config));
                HttpRequest request =
                        switch (method) {
                            case "GET" -> builder.GET().build();
                            case "POST" ->
                                builder.POST(HttpRequest.BodyPublishers.noBody())
                                        .build();
                            case "PUT" ->
                                builder.PUT(HttpRequest.BodyPublishers.noBody()).build();
                            case "DELETE" -> builder.DELETE().build();
                            default -> throw new IllegalArgumentException("Unsupported Trello method: " + method);
                        };
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (isSuccessfulStatus(response.statusCode())) {
                    return json.readValue(response.body(), type);
                }
                if (isRateLimited(response.statusCode()) && attempt < maxAttempts) {
                    LOG.warn(rateLimitWarning(config));
                    sleep(backoff(config, attempt, response));
                    continue;
                }
                if (isRateLimited(response.statusCode())) {
                    LOG.warn(rateLimitWarning(config));
                }
                throw statusException(response.statusCode());
            } catch (IOException e) {
                if (attempt == maxAttempts) {
                    throw new TrelloException("trello_api_request", "Trello request failed", e);
                }
                sleep(backoff(config, attempt, null));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new TrelloException("trello_api_request", "Trello request interrupted", e);
            }
        }
        throw new TrelloException("trello_api_request", "Trello request failed after retries");
    }

    private static TrelloException statusException(int statusCode) {
        if (isRateLimited(statusCode)) {
            return new TrelloException("trello_api_rate_limited", "Trello rate limit exceeded", statusCode);
        }
        Status status = Status.fromStatusCode(statusCode);
        if (status == null) {
            return new TrelloException("trello_api_status", "Trello returned HTTP " + statusCode, statusCode);
        }
        return switch (status) {
            case UNAUTHORIZED -> new TrelloException("trello_auth_failed", "Trello authentication failed", statusCode);
            case FORBIDDEN -> new TrelloException("trello_permission_denied", "Trello permission denied", statusCode);
            case NOT_FOUND -> new TrelloException("trello_card_not_found", "Trello resource not found", statusCode);
            default -> new TrelloException("trello_api_status", "Trello returned HTTP " + statusCode, statusCode);
        };
    }

    private static boolean isRateLimited(int statusCode) {
        return statusCode == Status.TOO_MANY_REQUESTS.getStatusCode();
    }

    private static boolean isNotFound(TrelloException exception) {
        return exception.statusCode() == Status.NOT_FOUND.getStatusCode();
    }

    private static boolean isSuccessfulStatus(int statusCode) {
        return Family.SUCCESSFUL == Family.familyOf(statusCode);
    }

    static String rateLimitWarning(EffectiveConfig config) {
        return "Trello rate limit reached. Current polling.interval_ms is %d in %s. If this happens often, increase polling.interval_ms, especially when running more than 5-10 boards with the same Trello token."
                .formatted(config.polling().interval().toMillis(), config.workflowPath());
    }

    private static Duration backoff(EffectiveConfig config, int attempt, HttpResponse<?> response) {
        Optional<Duration> retryAfter = response == null
                ? Optional.empty()
                : response.headers().firstValue("Retry-After").flatMap(TrelloClient::parseRetryAfter);
        return retryAfter.orElseGet(() -> exponentialBackoffWithJitter(config, attempt));
    }

    private static Duration exponentialBackoffWithJitter(EffectiveConfig config, int attempt) {
        long base = config.tracker().apiRetryBaseDelay().toMillis();
        long jitter = ThreadLocalRandom.current().nextLong(Math.max(1L, base));
        return Duration.ofMillis((base * (1L << Math.min(attempt - 1, 8))) + jitter);
    }

    private static Optional<Duration> parseRetryAfter(String value) {
        try {
            return Optional.of(Duration.ofSeconds(Long.parseLong(value)));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    // Blocking out the retry window is inherent: Trello publishes no event when a rate-limit
    // window or outage ends, so the only choices are honoring Retry-After/backoff or failing
    // the request (see docs/adr/0053-sleep-based-waits-kept-as-polling-boundaries.md).
    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TrelloException("trello_api_request", "Trello retry sleep interrupted", e);
        }
    }

    private static boolean hasChecklistItems(Map<String, Object> payload) {
        return badgeCount(payload, "checkItems") > 0;
    }

    private static boolean hasComments(Map<String, Object> payload) {
        return badgeCount(payload, "comments") > 0;
    }

    private static int badgeCount(Map<String, Object> payload, String name) {
        Object badges = payload.get("badges");
        if (!(badges instanceof Map<?, ?> badgeMap)) {
            return 0;
        }
        Object value = badgeMap.get(name);
        if (value == null) {
            return 0;
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static URI uri(EffectiveConfig config, String path, Map<String, String> query) {
        String normalizedPath = path.startsWith("/") ? path.substring(1) : path;
        if (normalizedPath.contains("..") || normalizedPath.contains("?") || normalizedPath.contains("#")) {
            throw new TrelloException("trello_unsupported_operation", "Invalid Trello API path");
        }
        String queryString = query.entrySet().stream()
                .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                .collect(Collectors.joining("&"));
        String endpoint = config.tracker().endpoint().replaceAll("/+$", "");
        return URI.create(endpoint + "/" + normalizedPath + (queryString.isBlank() ? "" : "?" + queryString));
    }

    private static String authorization(EffectiveConfig config) {
        return authorization(config.tracker().apiKey(), config.tracker().apiToken());
    }

    public static String authorization(String apiKey, String apiToken) {
        return "OAuth oauth_consumer_key=\"%s\", oauth_token=\"%s\"".formatted(apiKey, apiToken);
    }

    private BoardList toList(Map<String, Object> payload) {
        return new BoardList(
                requiredString(payload, "id", "trello_unknown_payload"),
                requiredString(payload, "name", "trello_unknown_payload"),
                bool(payload.get("closed")));
    }

    private static String identifier(EffectiveConfig config, Map<String, Object> payload) {
        String shortLink = string(payload.get("shortLink"));
        String id = string(payload.get("id"));
        if (!blank(shortLink)) {
            return config.tracker().cardIdentifierPrefix() + "-" + shortLink;
        }
        return blank(id) ? null : config.tracker().cardIdentifierPrefix() + "-" + id;
    }

    @SuppressWarnings("unchecked")
    private static List<String> labels(Map<String, Object> payload) {
        Object labels = payload.get("labels");
        if (!(labels instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .filter(Map.class::isInstance)
                .map(label -> string(((Map<String, Object>) label).get("name")))
                .filter(label -> !blank(label))
                .map(StateNames::normalize)
                .toList();
    }

    @SuppressWarnings("unchecked")
    private static List<String> labelIds(Map<String, Object> payload) {
        Object labels = payload.get("labels");
        if (!(labels instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .filter(Map.class::isInstance)
                .map(label -> string(((Map<String, Object>) label).get("id")))
                .filter(label -> !blank(label))
                .toList();
    }

    private static Instant createdAtFromObjectId(String id) {
        if (id == null || id.length() < 8) {
            return null;
        }
        try {
            long epochSeconds = Long.parseLong(id.substring(0, 8), 16);
            return Instant.ofEpochSecond(epochSeconds);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Instant instant(Object value) {
        if (value == null || value.toString().isBlank()) {
            return null;
        }
        return Instant.parse(value.toString());
    }

    private static BigDecimal decimal(Object value) {
        if (value == null) {
            return null;
        }
        return new BigDecimal(value.toString());
    }

    private static Integer integer(Object value, String field) {
        if (value == null) {
            return null;
        }
        // Trello integer fields such as idShort are whole numbers in valid payloads. Classify through
        // the shared WholeNumbers helper so a fractional or out-of-range value is rejected as a
        // malformed payload instead of being silently truncated by Number.intValue().
        Classified classified = WholeNumbers.classify(value.toString());
        if (classified.kind() != Kind.WHOLE) {
            throw new TrelloException(
                    "trello_unknown_payload", "Trello payload field " + field + " is not a whole number: " + value);
        }
        return classified.value();
    }

    private static Boolean nullableBool(Object value) {
        return value == null ? null : bool(value);
    }

    private static boolean bool(Object value) {
        return value != null && Boolean.parseBoolean(value.toString());
    }

    private static String requiredString(Map<String, Object> payload, String key, String errorCode) {
        String value = string(payload.get(key));
        if (blank(value)) {
            throw new TrelloException(errorCode, "Trello payload is missing " + key);
        }
        return value;
    }

    private static String string(Object value) {
        return value == null ? null : value.toString();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String encodeSegment(String value) {
        return encode(value).replace("+", "%20");
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private record BoardContext(String boardId, boolean boardClosed, Map<String, BoardList> lists) {}

    public record BoardList(String id, String name, boolean closed) {}

    private record PrerequisitePlan(
            List<TrelloChecklistClassifier.PrerequisiteItem> items, List<Card.PrerequisiteProblem> problems) {}

    private record PrerequisiteAnalysis(
            Card card, PrerequisitePlan plan, Map<String, ReferencedText> promptReferences) {}

    private record ResolvedPrerequisites(List<Card.PrerequisiteProblem> problems, List<BlockerRef> blockers) {}

    private record ReferencedText(String source, String text, TrelloCardReference reference) {}

    private static final class ReferenceAccumulator {
        // Preserve first-seen reference order for stable prompt context and duplicate handling.
        private final Map<String, ReferencedText> references = new LinkedHashMap<>();

        void add(String source, String text) {
            if (blank(text)) {
                return;
            }
            TrelloCardReferenceParser.referencesIn(text).forEach(reference -> add(source, text, reference));
        }

        void addExact(String source, String text) {
            if (blank(text)) {
                return;
            }
            TrelloCardReferenceParser.exactReference(text).ifPresent(reference -> add(source, text, reference));
        }

        void addChecklist(Card.Checklist checklist) {
            String source = "checklist:" + nullToEmpty(checklist.name());
            for (Card.ChecklistItem item : checklist.items()) {
                addExact(source, item.text());
                add(source, item.text());
            }
        }

        void addAttachment(Card.Attachment attachment) {
            add("attachment", attachment.name());
            add("attachment", attachment.url());
        }

        void addPrerequisite(TrelloCardReference reference) {
            add("checklist", reference.url(), reference);
        }

        void add(String source, String text, TrelloCardReference reference) {
            references.putIfAbsent(reference.key(), new ReferencedText(source, text, reference));
        }

        Map<String, ReferencedText> asMap() {
            return references;
        }
    }
}
