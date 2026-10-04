package ch.fmartin.symphony.trello.agent;

import static org.assertj.core.api.Assertions.assertThat;

import ch.fmartin.symphony.trello.config.ConfigResolver;
import ch.fmartin.symphony.trello.config.EffectiveConfig;
import ch.fmartin.symphony.trello.domain.Card;
import ch.fmartin.symphony.trello.testsupport.FakeTrelloBoard;
import ch.fmartin.symphony.trello.testsupport.FakeTrelloServer;
import ch.fmartin.symphony.trello.tracker.TrelloClient;
import ch.fmartin.symphony.trello.workflow.WorkflowDefinition;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/// Drives `trello_create_follow_up_card` through the handoff tool handler against a stateful fake
/// Trello board, so each test checks the board state the tool leaves behind.
final class TrelloFollowUpCardToolTest {
    private static final String BOARD_ID = "board-1";
    private static final String SOURCE_ID = "card-source";
    private static final String SOURCE_SHORT_LINK = "SYNTH201";
    private static final String SOURCE_URL = "https://trello.com/c/" + SOURCE_SHORT_LINK;
    private static final String SOURCE_TITLE = "Implement feature";
    private static final String INBOX_ID = "list-inbox";
    private static final String READY_ID = "list-ready";
    private static final String IN_PROGRESS_ID = "list-progress";
    private static final String BLOCKED_ID = "list-blocked";
    private static final String FOLLOW_UP_TITLE = "Cache board labels between polls";
    private static final String MANAGED_FOOTER_PREFIX =
            "_Managed by Symphony · Follow-up of [the source card](" + SOURCE_URL + ") · ";

    private final ObjectMapper json = new ObjectMapper();
    private final FakeTrelloBoard board = new FakeTrelloBoard(BOARD_ID);
    private FakeTrelloServer server;
    private TrelloHandoffToolHandler handler;

    @TempDir
    Path tempDir;

    @BeforeEach
    void startBoard() throws IOException {
        board.list(INBOX_ID, "Inbox")
                .list(READY_ID, "Ready for Codex")
                .list(IN_PROGRESS_ID, "In Progress")
                .list(BLOCKED_ID, "Blocked")
                .list("list-review", "Human Review")
                .list("list-done", "Done");
        board.card(SOURCE_ID, SOURCE_SHORT_LINK, SOURCE_TITLE, IN_PROGRESS_ID);
        server = new FakeTrelloServer().on("/1/", board).startEmpty();
        handler = new TrelloHandoffToolHandler(json, new TrelloClient(json));
    }

    @AfterEach
    void stopBoard() {
        server.stop();
    }

    @Test
    void advertisesTheFollowUpToolWithItsSchemaOnlyWhenFollowUpCardsAndLinksAreEnabled() {
        // given
        EffectiveConfig enabled = config(Map.of(), Map.of());
        EffectiveConfig disabled = config(Map.of(), Map.of("enabled", false));
        EffectiveConfig withoutLinks = config(Map.of("allow_url_attachments", false), Map.of());

        // when
        ArrayNode enabledTools = handler.toolSpecs(enabled);
        ArrayNode disabledTools = handler.toolSpecs(disabled);
        ArrayNode toolsWithoutLinks = handler.toolSpecs(withoutLinks);

        // then
        assertThat(disabledTools.findValuesAsText("name")).doesNotContain(TrelloFollowUpCardTool.NAME);
        assertThat(toolsWithoutLinks.findValuesAsText("name")).doesNotContain(TrelloFollowUpCardTool.NAME);
        JsonNode schema = assertThat(enabledTools)
                .filteredOn(tool ->
                        TrelloFollowUpCardTool.NAME.equals(tool.path("name").asText()))
                .singleElement()
                .actual()
                .path("inputSchema");
        assertThat(schema.path("additionalProperties").asBoolean(true))
                .as("the follow-up tool must reject undeclared arguments such as a card id")
                .isFalse();
        assertThat(schema.path("required")).hasToString("[\"title\",\"description\",\"acceptance_criteria\"]");
        assertThat(schema.path("properties").path("relationship").path("enum"))
                .hasToString("[\"related\",\"follow_up_waits_for_current\",\"current_waits_for_follow_up\"]");
        assertThat(schema.path("properties")
                        .path("acceptance_criteria")
                        .path("maxItems")
                        .asInt())
                .isEqualTo(TrelloFollowUpCardTool.MAX_ACCEPTANCE_CRITERIA);
    }

    @Test
    void disabledFollowUpCardsFailWithoutAnyTrelloRequest() {
        // given
        EffectiveConfig config = config(Map.of(), Map.of("enabled", false));

        // when
        ObjectNode result = call(config, arguments(FOLLOW_UP_TITLE, "related"));

        // then
        assertFailure(result, "trello_follow_up_cards_disabled");
        assertThat(board.requests()).isEmpty();
    }

    @Test
    void disabledTrelloWritesFailBeforeTheFollowUpPolicyIsConsulted() {
        // given
        EffectiveConfig config = config(Map.of("allow_writes", false), Map.of());

        // when
        ObjectNode result = call(config, arguments(FOLLOW_UP_TITLE, "related"));

        // then
        assertFailure(result, "trello_writes_disabled");
        assertThat(board.requests()).isEmpty();
    }

    @Test
    void createsARelatedFollowUpInTheInboxWithBothLinksTheLabelAndTheMetadataFooter() throws IOException {
        // given
        EffectiveConfig config = config(Map.of(), Map.of());

        // when
        ObjectNode result = call(config, arguments(FOLLOW_UP_TITLE, "related"));

        // then
        Map<String, String> payload = successPayload(result);
        FakeTrelloBoard.FakeCard followUp = board.cardByName(FOLLOW_UP_TITLE).orElseThrow();
        assertThat(payload)
                .containsEntry("status", "follow_up_card_created")
                .containsEntry("follow_up_card_id", followUp.id())
                .containsEntry("follow_up_card_url", followUp.url())
                .containsEntry("list_name", "Inbox")
                .containsEntry("relationship", "related")
                .containsEntry("current_card_must_wait", "false")
                .containsEntry("current_card_moved_to", "");
        assertThat(payload.get("workpad_note")).contains(followUp.url()).contains(FOLLOW_UP_TITLE);
        assertThat(followUp.listId()).isEqualTo(INBOX_ID);
        assertThat(followUp.description())
                .startsWith("Polling reads the board labels on every tick.")
                .contains("## Acceptance criteria\n\n- Labels are fetched once per minute\n- Tests cover the cache\n")
                .endsWith("\n\n" + MANAGED_FOOTER_PREFIX + "Related work, no required order_");
        assertThat(board.labels())
                .singleElement()
                .satisfies(label -> assertThat(label.name()).isEqualTo("follow-up"))
                .satisfies(label -> assertThat(followUp.labelIds()).containsExactly(label.id()));
        assertThat(followUp.attachments())
                .singleElement()
                .satisfies(attachment -> assertThat(attachment.url()).isEqualTo(SOURCE_URL))
                .satisfies(attachment -> assertThat(attachment.name()).isEqualTo("Follow-up of: " + SOURCE_TITLE));
        assertThat(board.cardByName(SOURCE_TITLE).orElseThrow().attachments())
                .singleElement()
                .satisfies(attachment -> assertThat(attachment.url()).isEqualTo(followUp.url()))
                .satisfies(attachment -> assertThat(attachment.name()).isEqualTo("Follow-up: " + FOLLOW_UP_TITLE));
        assertThat(followUp.checklists()).isEmpty();
        assertThat(board.cardByName(SOURCE_TITLE).orElseThrow().checklists()).isEmpty();
    }

    @Test
    void followUpThatWaitsForTheCurrentCardGetsTheCurrentCardAsItsPrerequisite() {
        // given
        EffectiveConfig config = config(Map.of(), Map.of());

        // when
        ObjectNode result = call(config, arguments(FOLLOW_UP_TITLE, "follow_up_waits_for_current"));

        // then
        assertThat(successPayload(result)).containsEntry("current_card_must_wait", "false");
        FakeTrelloBoard.FakeCard followUp = board.cardByName(FOLLOW_UP_TITLE).orElseThrow();
        assertThat(followUp.checklists())
                .singleElement()
                .satisfies(checklist -> assertThat(checklist.name()).isEqualTo("Must finish first"))
                .satisfies(checklist -> assertThat(checklist.items())
                        .singleElement()
                        .isEqualTo(new FakeTrelloBoard.ChecklistItem(
                                checklist.items().getFirst().id(), SOURCE_URL, false)));
        assertThat(followUp.description())
                .endsWith(MANAGED_FOOTER_PREFIX + "This card must wait for the source card to finish first_");
        assertThat(board.cardByName(SOURCE_TITLE).orElseThrow().checklists()).isEmpty();
    }

    @Test
    void currentCardThatWaitsForTheFollowUpGetsThePrerequisiteWithoutMovingByDefault() {
        // given
        EffectiveConfig config = config(Map.of(), Map.of());

        // when
        ObjectNode result = call(config, arguments(FOLLOW_UP_TITLE, "current_waits_for_follow_up"));

        // then
        FakeTrelloBoard.FakeCard followUp = board.cardByName(FOLLOW_UP_TITLE).orElseThrow();
        FakeTrelloBoard.FakeCard source = board.cardByName(SOURCE_TITLE).orElseThrow();
        assertThat(successPayload(result))
                .containsEntry("current_card_must_wait", "true")
                .containsEntry("current_card_moved_to", "");
        assertThat(source.checklists())
                .singleElement()
                .satisfies(checklist -> assertThat(checklist.name()).isEqualTo("Must finish first"))
                .satisfies(checklist -> assertThat(checklist.items())
                        .extracting(FakeTrelloBoard.ChecklistItem::name)
                        .containsExactly(followUp.url()));
        assertThat(source.listId()).isEqualTo(IN_PROGRESS_ID);
        assertThat(source.comments()).isEmpty();
        assertThat(followUp.checklists()).isEmpty();
    }

    @Test
    void configuredBlockedMovePutsTheCurrentCardInBlockedWithAnExactBlockerHandoff() {
        // given
        EffectiveConfig config = config(Map.of(), Map.of("move_current_card_to_blocked", true));

        // when
        ObjectNode result = call(config, arguments(FOLLOW_UP_TITLE, "current_waits_for_follow_up"));

        // then
        FakeTrelloBoard.FakeCard followUp = board.cardByName(FOLLOW_UP_TITLE).orElseThrow();
        FakeTrelloBoard.FakeCard source = board.cardByName(SOURCE_TITLE).orElseThrow();
        assertThat(successPayload(result)).containsEntry("current_card_moved_to", "Blocked");
        assertThat(source.listId()).isEqualTo(BLOCKED_ID);
        assertThat(source.comments())
                .singleElement()
                .asString()
                .startsWith("Blocked by [the follow-up card](" + followUp.url() + "): it must finish first.");
    }

    @Test
    void blockedMoveOutsideTheMoveAllowlistFailsBeforeAnyWrite() {
        // given
        EffectiveConfig config = config(
                Map.of("allowed_move_list_names", List.of("Human Review")),
                Map.of("move_current_card_to_blocked", true));

        // when
        ObjectNode result = call(config, arguments(FOLLOW_UP_TITLE, "current_waits_for_follow_up"));

        // then
        assertFailure(result, "trello_move_not_allowed");
        assertThat(board.writeRequests()).isEmpty();
    }

    @Test
    void currentCardChecklistWithNotesUnderThePrerequisiteNameFailsBeforeAnyWrite() {
        // given
        board.cardByName(SOURCE_TITLE).orElseThrow().checklist("Must finish first", "Ask design first");
        EffectiveConfig config = config(Map.of(), Map.of());

        // when
        ObjectNode result = call(config, arguments(FOLLOW_UP_TITLE, "current_waits_for_follow_up"));

        // then
        assertFailure(result, "trello_follow_up_prerequisite_checklist_conflict");
        assertThat(board.writeRequests()).isEmpty();
    }

    @Test
    void configuredRelationshipLabelIsAddedNextToTheFollowUpLabel() {
        // given
        board.label("label-existing", "Follow-Up");
        EffectiveConfig config =
                config(Map.of(), Map.of("relationship_labels", Map.of("related", "Related follow-up")));

        // when
        call(config, arguments(FOLLOW_UP_TITLE, "related"));

        // then
        assertThat(board.labels())
                .extracting(FakeTrelloBoard.Label::name)
                .containsExactly("Follow-Up", "Related follow-up");
        assertThat(board.cardByName(FOLLOW_UP_TITLE).orElseThrow().labelIds())
                .containsExactly("label-existing", board.labels().getLast().id());
    }

    @Test
    void emptyFollowUpLabelDisablesTheLabel() {
        // given
        EffectiveConfig config = config(Map.of(), Map.of("label", ""));

        // when
        call(config, arguments(FOLLOW_UP_TITLE, "related"));

        // then
        assertThat(board.labels()).isEmpty();
        assertThat(board.cardByName(FOLLOW_UP_TITLE).orElseThrow().labelIds()).isEmpty();
    }

    @Test
    void repeatedCallForTheSameTitleReturnsTheExistingCardWithoutDuplicateWrites() {
        // given
        EffectiveConfig config = config(Map.of(), Map.of());
        call(config, arguments(FOLLOW_UP_TITLE, "related"));
        int writesAfterFirstCall = board.writeRequests().size();

        // when
        ObjectNode result = call(config, arguments("  cache BOARD labels between polls ", "related"));

        // then
        assertThat(successPayload(result)).containsEntry("status", "follow_up_card_exists");
        assertThat(board.createdCards()).hasSize(1);
        assertThat(board.writeRequests()).hasSize(writesAfterFirstCall);
    }

    @Test
    void retryAfterAFailedLinkCompletesTheExistingCardInsteadOfCreatingAnother() {
        // given
        EffectiveConfig config = config(Map.of(), Map.of());
        board.failNext("POST /cards/" + SOURCE_ID + "/attachments", 1);
        ObjectNode failed = call(config, arguments(FOLLOW_UP_TITLE, "related"));

        // when
        ObjectNode retried = call(config, arguments(FOLLOW_UP_TITLE, "related"));

        // then
        assertThat(failed.path("success").asBoolean(true))
                .as("the injected attachment failure must surface as a tool failure")
                .isFalse();
        assertThat(successPayload(retried)).containsEntry("status", "follow_up_card_exists");
        FakeTrelloBoard.FakeCard followUp = board.createdCards().getFirst();
        assertThat(board.createdCards()).hasSize(1);
        assertThat(board.cardByName(SOURCE_TITLE).orElseThrow().attachments())
                .extracting(FakeTrelloBoard.Attachment::url)
                .containsExactly(followUp.url());
        assertThat(followUp.attachments())
                .extracting(FakeTrelloBoard.Attachment::url)
                .containsExactly(SOURCE_URL);
    }

    @Test
    void sameTitleWithAnotherRelationshipIsAConflictInsteadOfASilentChange() {
        // given
        EffectiveConfig config = config(Map.of(), Map.of());
        call(config, arguments(FOLLOW_UP_TITLE, "related"));
        int writesAfterFirstCall = board.writeRequests().size();

        // when
        ObjectNode result = call(config, arguments(FOLLOW_UP_TITLE, "current_waits_for_follow_up"));

        // then
        assertFailure(result, "trello_follow_up_relationship_conflict");
        assertThat(board.writeRequests()).hasSize(writesAfterFirstCall);
    }

    @Test
    void perSourceCardLimitCountsOnlyCardsWithThisCardsSymphonyMetadata() {
        // given
        board.card("card-manual", "SYNTH202", "Manual note", INBOX_ID)
                .description("Found while working on " + SOURCE_URL + ", related work.");
        board.card("card-edited", "SYNTH203", "Edited footer", INBOX_ID)
                .description("Body\n\n" + MANAGED_FOOTER_PREFIX + "Something a human typed_");
        board.card("card-other", "SYNTH204", "Other source", INBOX_ID)
                .description(
                        """
                        Body

                        _Managed by Symphony · Follow-up of [the source card](https://trello.com/c/SYNTH299)\
                         · Related work, no required order_""");
        EffectiveConfig config = config(Map.of(), Map.of("max_cards_per_source_card", 1));
        call(config, arguments(FOLLOW_UP_TITLE, "related"));

        // when
        ObjectNode result = call(config, arguments("Document the label cache", "related"));

        // then
        assertFailure(result, "trello_follow_up_limit_reached");
        assertThat(board.createdCards())
                .extracting(FakeTrelloBoard.FakeCard::name)
                .containsExactly(FOLLOW_UP_TITLE);
    }

    @Test
    void manuallyLinkedCardWithTheSameTitleIsAPlainReferenceAndDoesNotSuppressCreation() {
        // given
        board.card("card-manual", "SYNTH202", FOLLOW_UP_TITLE, INBOX_ID)
                .description("See " + SOURCE_URL)
                .attachment("Source", SOURCE_URL);
        EffectiveConfig config = config(Map.of(), Map.of());

        // when
        ObjectNode result = call(config, arguments(FOLLOW_UP_TITLE, "related"));

        // then
        assertThat(successPayload(result)).containsEntry("status", "follow_up_card_created");
        assertThat(board.createdCards()).hasSize(1);
    }

    @Test
    void retryAfterAFailedPrerequisiteWritesTheBlockedHandoffOnce() {
        // given
        EffectiveConfig config = config(Map.of(), Map.of("move_current_card_to_blocked", true));
        board.failNext("POST /cards/" + SOURCE_ID + "/checklists", 1);
        ObjectNode failed = call(config, arguments(FOLLOW_UP_TITLE, "current_waits_for_follow_up"));

        // when
        ObjectNode retried = call(config, arguments(FOLLOW_UP_TITLE, "current_waits_for_follow_up"));

        // then
        FakeTrelloBoard.FakeCard source = board.cardByName(SOURCE_TITLE).orElseThrow();
        assertThat(failed.path("success").asBoolean(true))
                .as("the injected checklist failure must surface as a tool failure")
                .isFalse();
        assertThat(successPayload(retried))
                .containsEntry("status", TrelloFollowUpCardTool.STATUS_EXISTS)
                .containsEntry("current_card_moved_to", "Blocked");
        assertThat(source.listId()).isEqualTo(BLOCKED_ID);
        assertThat(source.comments()).singleElement().asString().startsWith("Blocked by ");
    }

    @Test
    void retryAfterAPersonUnblockedTheCurrentCardDoesNotBlockItAgain() {
        // given
        EffectiveConfig config = config(Map.of(), Map.of("move_current_card_to_blocked", true));
        call(config, arguments(FOLLOW_UP_TITLE, "current_waits_for_follow_up"));
        FakeTrelloBoard.FakeCard source = board.cardByName(SOURCE_TITLE).orElseThrow();
        source.moveTo(IN_PROGRESS_ID);

        // when
        ObjectNode retried = call(config, arguments(FOLLOW_UP_TITLE, "current_waits_for_follow_up"));

        // then
        assertThat(successPayload(retried)).containsEntry("current_card_moved_to", "");
        assertThat(source.listId()).isEqualTo(IN_PROGRESS_ID);
        assertThat(source.comments()).hasSize(1);
    }

    @Test
    void repairDoesNotAddAPrerequisiteToAFollowUpChecklistThatHoldsNotes() {
        // given
        board.card("card-existing", "SYNTH206", FOLLOW_UP_TITLE, INBOX_ID)
                .description(
                        "Body\n\n" + MANAGED_FOOTER_PREFIX + "This card must wait for the source card to finish first_")
                .checklist("Must finish first", "Ask design first");
        EffectiveConfig config = config(Map.of(), Map.of());

        // when
        ObjectNode result = call(config, arguments(FOLLOW_UP_TITLE, "follow_up_waits_for_current"));

        // then
        assertFailure(result, "trello_follow_up_prerequisite_checklist_conflict");
        assertThat(board.writeRequests()).isEmpty();
    }

    @Test
    void finishedFollowUpsDoNotCountTowardThePerSourceCardLimit() {
        // given
        board.card("card-done", "SYNTH207", "Finished follow-up", "list-done")
                .description("Body\n\n" + MANAGED_FOOTER_PREFIX + "Related work, no required order_");
        EffectiveConfig config = config(Map.of(), Map.of("max_cards_per_source_card", 1));

        // when
        ObjectNode result = call(config, arguments(FOLLOW_UP_TITLE, "related"));

        // then
        assertThat(successPayload(result)).containsEntry("status", TrelloFollowUpCardTool.STATUS_CREATED);
    }

    @Test
    void concurrentCallsForTheSameFollowUpCreateOneCard() throws Exception {
        // given
        var bothRead = new CyclicBarrier(2);
        var readsWithoutPartner = new AtomicInteger();
        TrelloClient pausingClient = new TrelloClient(json) {
            @Override
            public List<BoardCard> fetchOpenBoardCards(EffectiveConfig config) {
                List<BoardCard> cards = super.fetchOpenBoardCards(config);
                try {
                    // Without the creation lock both calls meet here before either creates a card.
                    bothRead.await(1, TimeUnit.SECONDS);
                } catch (BrokenBarrierException | TimeoutException e) {
                    // With the lock the second call cannot arrive while the first one waits here.
                    readsWithoutPartner.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return cards;
            }
        };
        var concurrentHandler = new TrelloHandoffToolHandler(json, pausingClient);
        EffectiveConfig config = config(Map.of(), Map.of());
        ObjectNode params = json.createObjectNode();
        params.put("tool", TrelloFollowUpCardTool.NAME);
        params.set("arguments", arguments(FOLLOW_UP_TITLE, "related"));

        // when
        CompletableFuture<ObjectNode> first =
                CompletableFuture.supplyAsync(() -> concurrentHandler.handle(config, sourceCard(), params));
        CompletableFuture<ObjectNode> second =
                CompletableFuture.supplyAsync(() -> concurrentHandler.handle(config, sourceCard(), params));
        CompletableFuture.allOf(first, second).get(10, TimeUnit.SECONDS);

        // then
        assertThat(board.createdCards()).hasSize(1);
        assertThat(List.of(
                        successPayload(first.get()).get("status"),
                        successPayload(second.get()).get("status")))
                .containsExactlyInAnyOrder(TrelloFollowUpCardTool.STATUS_CREATED, TrelloFollowUpCardTool.STATUS_EXISTS);
    }

    @Test
    void hourlyRateLimitIsSharedAcrossSourceCardsAndReopensAfterTheWindow() {
        // given
        var clock = new MutableClock(Instant.parse("2026-10-01T10:00:00Z"));
        var tool = new TrelloFollowUpCardTool(new TrelloClient(json), clock);
        board.card("card-second", "SYNTH205", "Second source", IN_PROGRESS_ID);
        EffectiveConfig config = config(Map.of(), Map.of("max_cards_per_hour", 1));
        tool.handle(config, sourceCard(), arguments(FOLLOW_UP_TITLE, "related"), name -> Optional.empty());

        // when
        TrelloFollowUpCardTool.Outcome limited = tool.handle(
                config,
                card("card-second", "SYNTH205", "Second source"),
                arguments("Other work", "related"),
                name -> Optional.empty());
        clock.advance(TrelloFollowUpCardTool.RATE_WINDOW);
        TrelloFollowUpCardTool.Outcome reopened = tool.handle(
                config,
                card("card-second", "SYNTH205", "Second source"),
                arguments("Other work", "related"),
                name -> Optional.empty());

        // then
        assertThat(limited).isInstanceOfSatisfying(TrelloFollowUpCardTool.Outcome.Failure.class, failure -> assertThat(
                        failure.code())
                .isEqualTo("trello_follow_up_rate_limited"));
        assertThat(reopened).isInstanceOf(TrelloFollowUpCardTool.Outcome.Success.class);
        assertThat(board.createdCards()).hasSize(2);
    }

    @MethodSource("rejectedDestinations")
    @ParameterizedTest(name = "{0}")
    void unusableDestinationListFailsBeforeAnyWrite(String scenario, Map<String, Object> followUpCards, String code) {
        // given
        board.list("list-inbox-copy", "inbox ");
        EffectiveConfig config = config(Map.of(), followUpCards);

        // when
        ObjectNode result = call(config, arguments(FOLLOW_UP_TITLE, "related"));

        // then
        assertFailure(result, code);
        assertThat(board.writeRequests()).isEmpty();
    }

    @MethodSource("invalidArguments")
    @ParameterizedTest(name = "{0}")
    void invalidArgumentsFailBeforeAnyTrelloRequest(String scenario, ObjectNode arguments) {
        // given
        EffectiveConfig config = config(Map.of(), Map.of());

        // when
        ObjectNode result = call(config, arguments);

        // then
        assertFailure(result, "invalid_follow_up_card");
        assertThat(board.requests()).isEmpty();
    }

    @Test
    void disabledUrlAttachmentsOrChecklistsFailBeforeAnyTrelloRequest() {
        // given
        EffectiveConfig noAttachments = config(Map.of("allow_url_attachments", false), Map.of());
        EffectiveConfig noChecklists = config(Map.of("allow_checklists", false), Map.of());

        // when
        ObjectNode attachmentsResult = call(noAttachments, arguments(FOLLOW_UP_TITLE, "related"));
        ObjectNode checklistsResult = call(noChecklists, arguments(FOLLOW_UP_TITLE, "follow_up_waits_for_current"));

        // then
        assertFailure(attachmentsResult, "trello_url_attachments_disabled");
        assertFailure(checklistsResult, "trello_checklists_disabled");
        assertThat(board.requests()).isEmpty();
    }

    @Test
    void descriptionStartingWithAnIssueNumberIsEscapedSoTrelloDoesNotRenderAHeading() {
        // given
        EffectiveConfig config = config(Map.of(), Map.of());
        ObjectNode arguments = arguments(FOLLOW_UP_TITLE, "related");
        arguments.put("description", "#2076 shows the same label refetch.");

        // when
        call(config, arguments);

        // then
        assertThat(board.cardByName(FOLLOW_UP_TITLE).orElseThrow().description())
                .startsWith("\\#2076 shows the same label refetch.");
    }

    private static List<Arguments> rejectedDestinations() {
        return List.of(
                Arguments.of(
                        "active list", Map.of("list_name", "Ready for Codex"), "trello_follow_up_list_not_allowed"),
                Arguments.of("terminal list", Map.of("list_name", "Done"), "trello_follow_up_list_not_allowed"),
                Arguments.of("missing list", Map.of("list_name", "Icebox"), "trello_follow_up_list_missing"),
                Arguments.of("duplicate list name", Map.of(), "trello_follow_up_list_ambiguous"));
    }

    private static List<Arguments> invalidArguments() {
        var mapper = new ObjectMapper();
        ObjectNode multiLineTitle = baseArguments(mapper, "Title\nsecond line");
        ObjectNode noCriteria = baseArguments(mapper, FOLLOW_UP_TITLE);
        noCriteria.putArray("acceptance_criteria");
        ObjectNode tooManyCriteria = baseArguments(mapper, FOLLOW_UP_TITLE);
        ArrayNode criteria = tooManyCriteria.putArray("acceptance_criteria");
        for (int index = 0; index <= TrelloFollowUpCardTool.MAX_ACCEPTANCE_CRITERIA; index++) {
            criteria.add("Criterion " + index);
        }
        ObjectNode forgedFooter = baseArguments(mapper, FOLLOW_UP_TITLE);
        forgedFooter.put("description", "Body\n\n" + MANAGED_FOOTER_PREFIX + "Related work, no required order_");
        ObjectNode unknownRelationship = baseArguments(mapper, FOLLOW_UP_TITLE);
        unknownRelationship.put("relationship", "blocks");
        ObjectNode cardIdArgument = baseArguments(mapper, FOLLOW_UP_TITLE);
        cardIdArgument.put("card_id", "card-other");
        ObjectNode controlCharacter = baseArguments(mapper, FOLLOW_UP_TITLE);
        controlCharacter.put("description", "Body\u0007");
        return List.of(
                Arguments.of("multi-line title", multiLineTitle),
                Arguments.of("empty acceptance criteria", noCriteria),
                Arguments.of("too many acceptance criteria", tooManyCriteria),
                Arguments.of("forged metadata footer", forgedFooter),
                Arguments.of("unknown relationship", unknownRelationship),
                Arguments.of("control character in description", controlCharacter),
                Arguments.of("card id argument", cardIdArgument));
    }

    private static ObjectNode baseArguments(ObjectMapper mapper, String title) {
        ObjectNode arguments = mapper.createObjectNode();
        arguments.put("title", title);
        arguments.put("description", "Polling reads the board labels on every tick.");
        arguments.putArray("acceptance_criteria").add("Labels are fetched once per minute");
        return arguments;
    }

    private ObjectNode arguments(String title, String relationship) {
        ObjectNode arguments = json.createObjectNode();
        arguments.put("title", title);
        arguments.put("description", "Polling reads the board labels on every tick.");
        arguments
                .putArray("acceptance_criteria")
                .add("Labels are fetched once per minute")
                .add("Tests cover the cache");
        arguments.put("relationship", relationship);
        return arguments;
    }

    private ObjectNode call(EffectiveConfig config, ObjectNode arguments) {
        ObjectNode params = json.createObjectNode();
        params.put("tool", TrelloFollowUpCardTool.NAME);
        params.set("arguments", arguments);
        return handler.handle(config, sourceCard(), params);
    }

    private Map<String, String> successPayload(ObjectNode result) {
        assertThat(result.path("success").asBoolean(false))
                .as("tool call must succeed, result: %s", result)
                .isTrue();
        return payload(result);
    }

    private void assertFailure(ObjectNode result, String code) {
        assertThat(result.path("success").asBoolean(true))
                .as("tool call must fail with %s, result: %s", code, result)
                .isFalse();
        assertThat(payload(result)).containsEntry("error", code);
    }

    private Map<String, String> payload(ObjectNode result) {
        try {
            JsonNode node = json.readTree(
                    result.path("contentItems").get(0).path("text").asText());
            Map<String, String> payload = new LinkedHashMap<>();
            node.properties()
                    .forEach(entry ->
                            payload.put(entry.getKey(), entry.getValue().asText()));
            return payload;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private EffectiveConfig config(Map<String, Object> trelloToolOverrides, Map<String, Object> followUpOverrides) {
        Map<String, Object> followUpCards = new LinkedHashMap<>();
        followUpCards.put("enabled", true);
        followUpCards.putAll(followUpOverrides);
        Map<String, Object> trelloTools = new LinkedHashMap<>();
        trelloTools.put("enabled", true);
        trelloTools.put("allow_writes", true);
        trelloTools.put("allowed_move_list_names", List.of("Blocked", "Human Review"));
        trelloTools.put("allow_checklists", true);
        trelloTools.put("allow_url_attachments", true);
        trelloTools.putAll(trelloToolOverrides);
        trelloTools.put("follow_up_cards", followUpCards);
        return new ConfigResolver()
                .resolve(new WorkflowDefinition(
                        tempDir.resolve("WORKFLOW.md"),
                        Map.of(
                                "tracker",
                                Map.of(
                                        "kind",
                                        "trello",
                                        "endpoint",
                                        server.endpoint(),
                                        "api_key",
                                        "key",
                                        "api_token",
                                        "token",
                                        "board_id",
                                        BOARD_ID,
                                        "active_states",
                                        List.of("Ready for Codex", "In Progress"),
                                        "blocked_state",
                                        "Blocked"),
                                "trello_tools",
                                trelloTools),
                        ""))
                .withResolvedBoardId(BOARD_ID);
    }

    private static Card sourceCard() {
        return card(SOURCE_ID, SOURCE_SHORT_LINK, SOURCE_TITLE);
    }

    private static Card card(String id, String shortLink, String title) {
        return new Card(
                id,
                "TRELLO-" + shortLink,
                title,
                "Description",
                null,
                "In Progress",
                "list",
                IN_PROGRESS_ID,
                "In Progress",
                false,
                BOARD_ID,
                false,
                false,
                1,
                shortLink,
                "https://trello.com/c/" + shortLink,
                null,
                "https://trello.com/c/" + shortLink,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-02T00:00:00Z"),
                null,
                false,
                BigDecimal.ONE);
    }

    // GitHub issue #858 replaces this and the orchestrator copy with one shared test clock.
    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
