package ch.fmartin.symphony.trello.agent;

import static ch.fmartin.symphony.trello.CommaSeparatedValues.preservingEmptyFields;
import static ch.fmartin.symphony.trello.TestHttpExchange.query;
import static ch.fmartin.symphony.trello.testsupport.FakeTrelloServer.boardJson;
import static ch.fmartin.symphony.trello.testsupport.FakeTrelloServer.jsonEscaped;
import static ch.fmartin.symphony.trello.testsupport.FakeTrelloServer.listsJson;
import static ch.fmartin.symphony.trello.testsupport.FakeTrelloServer.respond;
import static ch.fmartin.symphony.trello.testsupport.FakeTrelloServer.trelloList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.fmartin.symphony.trello.TestCards;
import ch.fmartin.symphony.trello.config.ConfigResolver;
import ch.fmartin.symphony.trello.config.EffectiveConfig;
import ch.fmartin.symphony.trello.domain.Card;
import ch.fmartin.symphony.trello.testsupport.FakeTrelloServer;
import ch.fmartin.symphony.trello.tracker.CardLookupResult;
import ch.fmartin.symphony.trello.tracker.TrelloClient;
import ch.fmartin.symphony.trello.workflow.WorkflowDefinition;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

final class TrelloHandoffToolHandlerTest {
    private static final String BLOCKER_ACTION_ID = "action-blocker";
    private static final String BLOCKER_RECHECK_ACTION_ID = "action-blocker-recheck";
    private static final String CHECKING_STATUS = TrelloHandoffToolHandler.BLOCKER_RECHECK_CHECKING;
    private static final String RESUMED_STATUS_PREFIX = TrelloHandoffToolHandler.RESUMED_WORK_PREFIX;

    private final ObjectMapper json = new ObjectMapper();
    private final AtomicReference<String> commentText = new AtomicReference<>();
    private final AtomicReference<String> updatedCommentText = new AtomicReference<>();
    private final List<String> deletedActionIds = new CopyOnWriteArrayList<>();
    private final List<String> managedCommentCallOrder = new CopyOnWriteArrayList<>();
    private final List<String> checklistRequests = new CopyOnWriteArrayList<>();
    private final AtomicReference<String> movedToListId = new AtomicReference<>();
    private final AtomicReference<String> createdChecklistName = new AtomicReference<>();
    private final AtomicReference<String> createdChecklistItemName = new AtomicReference<>();
    private final AtomicReference<String> createdChecklistItemChecked = new AtomicReference<>();
    private final AtomicReference<String> updatedChecklistItemState = new AtomicReference<>();
    private final AtomicReference<String> attachmentUrl = new AtomicReference<>();
    private final AtomicReference<String> attachmentName = new AtomicReference<>();
    private final AtomicReference<String> attachmentResponse = new AtomicReference<>("[{\"id\":\"attachment-1\"}]");
    private final AtomicReference<String> cardResponse = new AtomicReference<>();
    private final AtomicReference<Integer> cardStatus = new AtomicReference<>();
    private final AtomicReference<Integer> updateStatus = new AtomicReference<>(200);
    private final AtomicReference<Integer> deleteStatus = new AtomicReference<>(200);
    private final AtomicInteger cardFetchCount = new AtomicInteger();
    private FakeTrelloServer trello;

    @TempDir
    Path tempDir;

    @BeforeEach
    void startServer() throws Exception {
        trello = new FakeTrelloServer();
        trello.on("/1/boards/board-1", exchange -> respond(exchange, boardJson("board-1", "Test Board", false)));
        trello.on(
                "/1/boards/board-1/lists",
                exchange -> respond(
                        exchange,
                        listsJson(
                                trelloList("list-ready", "Ready for Codex", 1),
                                trelloList("list-review", "Review", 2),
                                trelloList("list-closed", "Closed Review", true, 3))));
        trello.on("/1/cards/card-1", exchange -> {
            cardFetchCount.incrementAndGet();
            respond(exchange, cardStatus.get(), cardResponseForRequestedFields(exchange));
        });
        trello.on("/1/cards/card-1/actions/comments", exchange -> {
            assertThat(exchange.getRequestMethod()).isEqualTo("POST");
            commentText.set(query(exchange).get("text"));
            respond(exchange, "{\"id\":\"action-1\"}");
        });
        trello.on("/1/actions/action-workpad-older", exchange -> {
            assertThat(exchange.getRequestMethod()).isEqualTo("DELETE");
            managedCommentCallOrder.add("delete:action-workpad-older");
            if (deleteStatus.get() == 200) {
                deletedActionIds.add("action-workpad-older");
            }
            respond(exchange, deleteStatus.get(), "{}");
        });
        trello.on("/1/actions/action-workpad-oldest", exchange -> {
            assertThat(exchange.getRequestMethod()).isEqualTo("DELETE");
            managedCommentCallOrder.add("delete:action-workpad-oldest");
            if (deleteStatus.get() == 200) {
                deletedActionIds.add("action-workpad-oldest");
            }
            respond(exchange, deleteStatus.get(), "{}");
        });
        trello.on("/1/actions/action-workpad/text", exchange -> {
            assertThat(exchange.getRequestMethod()).isEqualTo("PUT");
            managedCommentCallOrder.add("update:action-workpad");
            if (updateStatus.get() == 200) {
                updatedCommentText.set(query(exchange).get("value"));
            }
            respond(exchange, updateStatus.get(), "{\"id\":\"action-workpad\"}");
        });
        trello.on("/1/actions/" + BLOCKER_RECHECK_ACTION_ID + "/text", exchange -> {
            assertThat(exchange.getRequestMethod()).isEqualTo("PUT");
            managedCommentCallOrder.add("update:" + BLOCKER_RECHECK_ACTION_ID);
            if (updateStatus.get() == 200) {
                updatedCommentText.set(query(exchange).get("value"));
            }
            respond(exchange, updateStatus.get(), "{\"id\":\"" + BLOCKER_RECHECK_ACTION_ID + "\"}");
        });
        trello.on("/1/actions/action-blocker-recheck-older", exchange -> {
            assertThat(exchange.getRequestMethod()).isEqualTo("DELETE");
            managedCommentCallOrder.add("delete:action-blocker-recheck-older");
            if (deleteStatus.get() == 200) {
                deletedActionIds.add("action-blocker-recheck-older");
            }
            respond(exchange, deleteStatus.get(), "{}");
        });
        trello.on("/1/actions/action-blocker-recheck-older/text", exchange -> {
            assertThat(exchange.getRequestMethod()).isEqualTo("PUT");
            managedCommentCallOrder.add("update:action-blocker-recheck-older");
            if (updateStatus.get() == 200) {
                updatedCommentText.set(query(exchange).get("value"));
            }
            respond(exchange, updateStatus.get(), "{\"id\":\"action-blocker-recheck-older\"}");
        });
        trello.on("/1/cards/card-1/idList", exchange -> {
            assertThat(exchange.getRequestMethod()).isEqualTo("PUT");
            movedToListId.set(query(exchange).get("value"));
            respond(exchange, "{\"id\":\"card-1\"}");
        });
        trello.on("/1/cards/card-1/checklists", exchange -> {
            checklistRequests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            if ("GET".equals(exchange.getRequestMethod())) {
                respond(exchange, "[]");
                return;
            }
            assertThat(exchange.getRequestMethod()).isEqualTo("POST");
            createdChecklistName.set(query(exchange).get("name"));
            respond(exchange, "{\"id\":\"checklist-created\"}");
        });
        trello.on("/1/checklists/checklist-created/checkItems", exchange -> {
            assertThat(exchange.getRequestMethod()).isEqualTo("POST");
            checklistRequests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            createdChecklistItemName.set(query(exchange).get("name"));
            createdChecklistItemChecked.set(query(exchange).get("checked"));
            respond(exchange, "{\"id\":\"item-created\"}");
        });
        trello.on("/1/cards/card-1/checkItem/item-existing", exchange -> {
            assertThat(exchange.getRequestMethod()).isEqualTo("PUT");
            checklistRequests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            updatedChecklistItemState.set(query(exchange).get("state"));
            respond(exchange, "{\"id\":\"item-existing\"}");
        });
        trello.on("/1/cards/card-1/attachments", exchange -> {
            assertThat(exchange.getRequestMethod()).isEqualTo("POST");
            attachmentUrl.set(query(exchange).get("url"));
            attachmentName.set(query(exchange).get("name"));
            respond(exchange, attachmentResponse.get());
        });
        cardResponse.set(cardJson("[]"));
        cardStatus.set(200);
        trello.startEmpty();
    }

    @AfterEach
    void stopServer() {
        trello.close();
    }

    @Test
    void advertisesPolicyEnabledWriteToolsWhenWritesAreEnabledAndMoveAllowlistExists() {
        // given
        TrelloHandoffToolHandler handler = handler();

        // when
        var tools = handler.toolSpecs(config(List.of("Review"), List.of()));

        // then
        assertThat(tools)
                .extracting(tool -> tool.path("name").asText())
                .containsExactly(
                        TrelloHandoffToolHandler.ADD_COMMENT,
                        TrelloHandoffToolHandler.UPSERT_WORKPAD,
                        TrelloHandoffToolHandler.UPDATE_BLOCKER_RECHECK_STATUS,
                        TrelloHandoffToolHandler.UPSERT_CHECKLIST_ITEM,
                        TrelloHandoffToolHandler.ADD_URL_ATTACHMENT,
                        TrelloHandoffToolHandler.MOVE_CURRENT_CARD);
        assertThat(tools.get(2)
                        .path("inputSchema")
                        .path("properties")
                        .path("status")
                        .path("enum")
                        .toString())
                .isEqualTo("[\"checking\",\"resumed\"]");
        assertThat(tools.get(3).path("inputSchema").path("required").toString())
                .isEqualTo("[\"checklist_name\",\"item_name\",\"complete\"]");
    }

    @Test
    void withholdsChecklistAndAttachmentToolsWhenTheirFlagsAreDisabled() {
        // given
        TrelloHandoffToolHandler handler = handler();

        // when
        var tools = handler.toolSpecs(configWithChecklistAndAttachmentWrites(false, false));

        // then
        assertThat(tools)
                .extracting(tool -> tool.path("name").asText())
                .containsExactly(
                        TrelloHandoffToolHandler.ADD_COMMENT,
                        TrelloHandoffToolHandler.UPSERT_WORKPAD,
                        TrelloHandoffToolHandler.UPDATE_BLOCKER_RECHECK_STATUS,
                        TrelloHandoffToolHandler.MOVE_CURRENT_CARD);
    }

    @Test
    void addsCommentToCurrentCard() {
        // given
        TrelloHandoffToolHandler handler = handler();

        // when
        var result = handler.handle(
                config(List.of("Review"), List.of()),
                TestCards.card("card-1", "TRELLO-abc", "Ready for Codex"),
                json.createObjectNode()
                        .put("tool", TrelloHandoffToolHandler.ADD_COMMENT)
                        .set("arguments", json.createObjectNode().put("text", "Ready for review")));

        // then
        assertThat(result.path("success").asBoolean()).isTrue();
        assertThat(commentText.get()).isEqualTo("Ready for review");
    }

    @Test
    void escapesLeadingHashtagsBeforeAddingCommentToCurrentCard() {
        // given
        TrelloHandoffToolHandler handler = handler();

        // when
        var result = handler.handle(
                config(List.of("Review"), List.of()),
                TestCards.card("card-1", "TRELLO-abc", "Ready for Codex"),
                json.createObjectNode()
                        .put("tool", TrelloHandoffToolHandler.ADD_COMMENT)
                        .set(
                                "arguments",
                                json.createObjectNode().put("text", "- #2076: Fixed\nSee #2077 for follow-up")));

        // then
        assertThat(result.path("success").asBoolean()).isTrue();
        assertThat(commentText.get()).isEqualTo("- \\#2076: Fixed\nSee #2077 for follow-up");
    }

    @Test
    void upsertsChecklistItemOnCurrentCardWhenChecklistWritesAreAllowed() {
        // given
        TrelloHandoffToolHandler handler = handler();

        // when
        var result = upsertChecklistItem(handler, config(List.of("Review"), List.of()));

        // then
        assertThat(result.path("success").asBoolean()).isTrue();
        assertThat(result.path("contentItems").get(0).path("text").asText())
                .contains("checklist_item_created", "card-1", "checklist-created", "item-created");
        assertThat(createdChecklistName.get()).isEqualTo("Release tasks");
        assertThat(createdChecklistItemName.get()).isEqualTo("Publish release");
        assertThat(createdChecklistItemChecked.get()).isEqualTo("true");
    }

    @Test
    void updatesExistingChecklistItemOnCurrentCardWhenChecklistWritesAreAllowed() {
        // given
        TrelloHandoffToolHandler handler = handler();
        trello.remove("/1/cards/card-1/checklists").on("/1/cards/card-1/checklists", exchange -> {
            checklistRequests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            assertThat(exchange.getRequestMethod()).isEqualTo("GET");
            respond(
                    exchange,
                    """
                                    [
                                      {
                                        "id":"checklist-existing",
                                        "name":"Release tasks",
                                        "checkItems":[{"id":"item-existing","name":"Publish release","state":"incomplete"}]
                                      }
                                    ]
                                    """);
        });

        // when
        var result = upsertChecklistItem(handler, config(List.of("Review"), List.of()));

        // then
        assertThat(result.path("success").asBoolean()).isTrue();
        assertThat(result.path("contentItems").get(0).path("text").asText())
                .contains("checklist_item_updated", "card-1", "checklist-existing", "item-existing");
        assertThat(updatedChecklistItemState.get()).isEqualTo("complete");
        assertThat(createdChecklistName.get()).isNull();
    }

    @Test
    void rejectsChecklistToolWhenDisabledBeforeTrelloMutation() {
        // given
        TrelloHandoffToolHandler handler = handler();

        // when
        var result = upsertChecklistItem(handler, configWithChecklistAndAttachmentWrites(false, true));

        // then
        assertThat(result.path("success").asBoolean()).isFalse();
        assertThat(result.path("contentItems").get(0).path("text").asText()).contains("trello_checklists_disabled");
        assertThat(checklistRequests).isEmpty();
        assertThat(createdChecklistName.get()).isNull();
    }

    @Test
    void rejectsChecklistToolWithoutCompletionStateBeforeTrelloMutation() {
        // given
        TrelloHandoffToolHandler handler = handler();

        // when
        var result = handler.handle(
                config(List.of("Review"), List.of()),
                TestCards.card("card-1", "TRELLO-abc", "Ready for Codex"),
                json.createObjectNode()
                        .put("tool", TrelloHandoffToolHandler.UPSERT_CHECKLIST_ITEM)
                        .set(
                                "arguments",
                                json.createObjectNode()
                                        .put("checklist_name", "Release tasks")
                                        .put("item_name", "Publish release")));

        // then
        assertThat(result.path("success").asBoolean()).isFalse();
        assertThat(result.path("contentItems").get(0).path("text").asText()).contains("complete");
        assertThat(checklistRequests).isEmpty();
        assertThat(createdChecklistName.get()).isNull();
    }

    @MethodSource("nonStringToolStringArguments")
    @ParameterizedTest(name = "{0}")
    void rejectsNonStringToolStringArgumentsBeforeTrelloMutation(
            String scenario, String tool, JsonNode arguments, String argumentName) {
        // given
        TrelloHandoffToolHandler handler = handler();

        // when
        var result = handler.handle(
                config(List.of("Review"), List.of()),
                TestCards.card("card-1", "TRELLO-abc", "Ready for Codex"),
                json.createObjectNode().put("tool", tool).set("arguments", arguments));

        // then
        assertThat(result.path("success").asBoolean()).as(scenario).isFalse();
        assertThat(result.path("contentItems").get(0).path("text").asText())
                .contains("Argument " + argumentName + " must be a string");
        assertThat(commentText.get()).isNull();
        assertThat(updatedCommentText.get()).isNull();
        assertThat(checklistRequests).isEmpty();
        assertThat(createdChecklistName.get()).isNull();
        assertThat(createdChecklistItemName.get()).isNull();
        assertThat(attachmentUrl.get()).isNull();
    }

    @Test
    void addsUrlAttachmentToCurrentCardWhenUrlAttachmentsAreAllowed() {
        // given
        TrelloHandoffToolHandler handler = handler();

        // when
        var result = handler.handle(
                config(List.of("Review"), List.of()),
                TestCards.card("card-1", "TRELLO-abc", "Ready for Codex"),
                json.createObjectNode()
                        .put("tool", TrelloHandoffToolHandler.ADD_URL_ATTACHMENT)
                        .set(
                                "arguments",
                                json.createObjectNode()
                                        .put("url", "https://github.com/example/project/pull/12")
                                        .put("name", "Pull request")));

        // then
        assertThat(result.path("success").asBoolean()).isTrue();
        assertThat(result.path("contentItems").get(0).path("text").asText())
                .contains("url_attachment_added", "card-1", "attachment-1");
        assertThat(attachmentUrl.get()).isEqualTo("https://github.com/example/project/pull/12");
        assertThat(attachmentName.get()).isEqualTo("Pull request");
    }

    @Test
    void reportsUnknownPayloadWhenUrlAttachmentResponseHasNoAttachmentId() {
        // given
        attachmentResponse.set("[]");
        TrelloHandoffToolHandler handler = handler();

        // when
        var result = handler.handle(
                config(List.of("Review"), List.of()),
                TestCards.card("card-1", "TRELLO-abc", "Ready for Codex"),
                json.createObjectNode()
                        .put("tool", TrelloHandoffToolHandler.ADD_URL_ATTACHMENT)
                        .set(
                                "arguments",
                                json.createObjectNode()
                                        .put("url", "https://github.com/example/project/pull/12")
                                        .put("name", "Pull request")));

        // then
        assertThat(result.path("success").asBoolean()).isFalse();
        assertThat(result.path("contentItems").get(0).path("text").asText())
                .contains("trello_unknown_payload")
                .doesNotContain("url_attachment_added");
        assertThat(attachmentUrl.get()).isEqualTo("https://github.com/example/project/pull/12");
        assertThat(attachmentName.get()).isEqualTo("Pull request");
    }

    @Test
    void rejectsUrlAttachmentToolWhenDisabledBeforeTrelloMutation() {
        // given
        TrelloHandoffToolHandler handler = handler();

        // when
        var result = handler.handle(
                configWithChecklistAndAttachmentWrites(true, false),
                TestCards.card("card-1", "TRELLO-abc", "Ready for Codex"),
                json.createObjectNode()
                        .put("tool", TrelloHandoffToolHandler.ADD_URL_ATTACHMENT)
                        .set(
                                "arguments",
                                json.createObjectNode().put("url", "https://github.com/example/project/pull/12")));

        // then
        assertThat(result.path("success").asBoolean()).isFalse();
        assertThat(result.path("contentItems").get(0).path("text").asText())
                .contains("trello_url_attachments_disabled");
        assertThat(attachmentUrl.get()).isNull();
    }

    @Test
    void rejectsCredentialBearingUrlAttachmentBeforeTrelloMutation() {
        // given
        TrelloHandoffToolHandler handler = handler();

        // when
        var result = addUrlAttachment(handler, "https://token:secret@example.invalid/private");

        // then
        assertUnsafeUrlAttachmentRejected(result);
    }

    @Test
    void rejectsQueryStringUrlAttachmentBeforeTrelloMutation() {
        // given
        TrelloHandoffToolHandler handler = handler();

        // when
        var result = addUrlAttachment(handler, "https://example.invalid/private?access_token=secret-token");

        // then
        assertUnsafeUrlAttachmentRejected(result);
    }

    @Test
    void rejectsFragmentUrlAttachmentBeforeTrelloMutation() {
        // given
        TrelloHandoffToolHandler handler = handler();

        // when
        var result = addUrlAttachment(handler, "https://example.invalid/private#secret-token");

        // then
        assertUnsafeUrlAttachmentRejected(result);
    }

    @Test
    void createsWorkpadCommentWhenNoMarkerCommentExists() {
        // given
        TrelloHandoffToolHandler handler = handler();
        cardResponse.set(cardJson("[]"));

        // when
        var result = handler.handle(
                config(List.of("Review"), List.of()),
                TestCards.card("card-1", "TRELLO-abc", "Ready for Codex"),
                json.createObjectNode()
                        .put("tool", TrelloHandoffToolHandler.UPSERT_WORKPAD)
                        .set("arguments", json.createObjectNode().put("text", "- Plan: inspect the failure")));

        // then
        assertThat(result.path("success").asBoolean()).isTrue();
        assertThat(result.path("contentItems").get(0).path("text").asText()).contains("workpad_created");
        assertThat(commentText.get()).startsWith(TrelloHandoffToolHandler.WORKPAD_MARKER);
        assertThat(updatedCommentText.get()).isNull();
    }

    @Test
    void preservesWorkpadMarkerWhileEscapingLeadingHashtagsInWorkpadBody() {
        // given
        TrelloHandoffToolHandler handler = handler();
        cardResponse.set(cardJson("[]"));

        // when
        var result = handler.handle(
                config(List.of("Review"), List.of()),
                TestCards.card("card-1", "TRELLO-abc", "Ready for Codex"),
                json.createObjectNode()
                        .put("tool", TrelloHandoffToolHandler.UPSERT_WORKPAD)
                        .set(
                                "arguments",
                                json.createObjectNode()
                                        .put("text", "## Codex Workpad\n\n#2076: Fixed\n- #2077: Follow-up")));

        // then
        assertThat(result.path("success").asBoolean()).isTrue();
        assertThat(commentText.get()).isEqualTo("## Codex Workpad\n\n\\#2076: Fixed\n- \\#2077: Follow-up");
    }

    @Test
    void repositoryMismatchRequeueStartsManagedBlockerRecheckStatus() {
        // given
        TrelloHandoffToolHandler handler = handler();
        cardResponse.set(cardJson(
                "Implement #20",
                actionsJson(commentAction(
                        BLOCKER_ACTION_ID, "Blocked: GitHub issue #20 was not found in the configured repository."))));

        // when
        JsonNode result = updateBlockerRecheckStatus(handler, "checking");

        // then
        assertThat(result.path("success").asBoolean()).isTrue();
        assertThat(commentText.get())
                .startsWith("Checking whether this card is still blocked...")
                .contains("_Managed by Symphony · [View the comment explaining why this card was previously blocked](")
                .contains("https://trello.com/c/SYNTH101#comment-" + BLOCKER_ACTION_ID)
                .doesNotContain("<!--");
        assertThat(updatedCommentText.get()).isNull();
    }

    @Test
    void commentsDisabledRejectsBlockerRecheckBeforeRefreshingOrMutatingTheCard() {
        // given
        TrelloHandoffToolHandler handler = handler();
        cardStatus.set(500);

        // when
        JsonNode result = updateBlockerRecheckStatus(handler, "checking", configWithComments(false));

        // then
        assertThat(result.path("success").asBoolean()).isFalse();
        assertThat(result.path("contentItems").get(0).path("text").asText())
                .contains("trello_comments_disabled")
                .doesNotContain("trello_blocker_recheck_refresh_failed");
        assertNoManagedCommentMutation();
    }

    @Test
    void missingCardFailsBlockerRecheckWithoutMutatingTrello() {
        // given
        TrelloHandoffToolHandler handler = handler();
        cardStatus.set(404);

        // when
        JsonNode result = updateBlockerRecheckStatus(handler, "checking");

        // then
        assertThat(result.path("success").asBoolean()).isFalse();
        assertThat(result.path("contentItems").get(0).path("text").asText())
                .contains("trello_blocker_recheck_card_missing");
        assertNoManagedCommentMutation();
    }

    @Test
    void failedCardRefreshFailsBlockerRecheckWithoutMutatingTrello() {
        // given
        TrelloHandoffToolHandler handler = handler();
        cardStatus.set(500);

        // when
        JsonNode result = updateBlockerRecheckStatus(handler, "checking");

        // then
        assertThat(result.path("success").asBoolean()).isFalse();
        assertThat(result.path("contentItems").get(0).path("text").asText())
                .contains("trello_blocker_recheck_refresh_failed");
        assertNoManagedCommentMutation();
    }

    @Test
    void blockerClassificationUsesTheFirstNonBlankLogicalLine() {
        // given
        TrelloHandoffToolHandler handler = handler();
        cardResponse.set(cardJson(actionsJson(commentAction(
                BLOCKER_ACTION_ID, " \r\n\u2028Blocked by a missing repository issue\nDetails follow."))));

        // when
        JsonNode result = updateBlockerRecheckStatus(handler, "checking");

        // then
        assertThat(result.path("success").asBoolean()).isTrue();
        assertThat(commentText.get()).contains(CHECKING_STATUS);
    }

    @Test
    void blockerClassificationMatchesExactPrefixWithoutCaseSensitivity() {
        // given
        TrelloHandoffToolHandler handler = handler();
        cardResponse.set(cardJson(actionsJson(commentAction(BLOCKER_ACTION_ID, "blocked: missing permission"))));

        // when
        JsonNode result = updateBlockerRecheckStatus(handler, "checking");

        // then
        assertThat(result.path("success").asBoolean()).isTrue();
        assertThat(commentText.get()).contains(CHECKING_STATUS);
    }

    @Test
    void blockerDiscussionStartingWithSimilarWordIsNotAStatusHandoff() {
        // given
        TrelloHandoffToolHandler handler = handler();
        cardResponse.set(cardJson(
                actionsJson(commentAction("action-human", "Blocked workflow ideas belong in a separate discussion."))));

        // when
        JsonNode result = updateBlockerRecheckStatus(handler, "checking");

        // then
        assertThat(result.path("contentItems").get(0).path("text").asText()).contains("blocker_recheck_not_needed");
        assertThat(commentText.get()).isNull();
    }

    @ParameterizedTest(name = "escaped control {0}")
    @ValueSource(strings = {"\\u000b", "\\u000c"})
    void verticalTabAndFormFeedDoNotCreateNewLogicalLinesForBlockerClassification(String escapedControl) {
        // given
        TrelloHandoffToolHandler handler = handler();
        cardResponse.set(cardJson(actionsJson(
                """
                {
                  "id":"action-human",
                  "data":{"text":"Blocked by%sdiscussion that is not an exact status prefix"},
                  "date":"2026-05-05T00:00:00.000Z",
                  "memberCreator":{"fullName":"Codex"}
                }
                """
                        .formatted(escapedControl))));

        // when
        JsonNode result = updateBlockerRecheckStatus(handler, "checking");

        // then
        assertThat(result.path("contentItems").get(0).path("text").asText()).contains("blocker_recheck_not_needed");
        assertThat(commentText.get()).isNull();
    }

    @Test
    void successfulRepositoryRecheckUpdatesManagedStatusToResumedWork() {
        // given
        TrelloHandoffToolHandler handler = handler();
        cardResponse.set(cardJson(
                "Implement #20",
                actionsJson(
                        managedRecheckAction(BLOCKER_RECHECK_ACTION_ID, CHECKING_STATUS, BLOCKER_ACTION_ID),
                        commentAction(
                                BLOCKER_ACTION_ID,
                                "Blocked: GitHub issue #20 was not found in the configured repository."))));

        // when
        JsonNode result = updateBlockerRecheckStatus(handler, "resumed");

        // then
        assertThat(result.path("success").asBoolean()).isTrue();
        assertThat(result.path("contentItems").get(0).path("text").asText()).contains("resumed_work_confirmed");
        assertThat(updatedCommentText.get())
                .contains("No longer blocked; working on Implement #20.")
                .contains("https://trello.com/c/SYNTH101#comment-" + BLOCKER_ACTION_ID)
                .doesNotContain("<!--");
        assertThat(commentText.get()).isNull();
    }

    @Test
    void refusesToClaimResumedWorkBeforeBlockerRecheckStarts() {
        // given
        TrelloHandoffToolHandler handler = handler();
        cardResponse.set(
                cardJson(actionsJson(commentAction(BLOCKER_ACTION_ID, "Blocked by missing repository issue"))));

        // when
        JsonNode result = updateBlockerRecheckStatus(handler, "resumed");

        // then
        assertThat(result.path("success").asBoolean()).isFalse();
        assertThat(result.path("contentItems").get(0).path("text").asText())
                .contains("trello_blocker_recheck_not_started");
        assertThat(commentText.get()).isNull();
        assertThat(updatedCommentText.get()).isNull();
    }

    @Test
    void stillBlockedRecheckCannotConfirmAgainstAnewerBlockerHandoff() {
        // given
        TrelloHandoffToolHandler handler = handler();
        cardResponse.set(cardJson(actionsJson(
                commentAction("action-blocker-new", "Blocked by GitHub issue #20 still being unavailable"),
                managedRecheckAction(BLOCKER_RECHECK_ACTION_ID, CHECKING_STATUS, BLOCKER_ACTION_ID),
                commentAction(BLOCKER_ACTION_ID, "Blocked: GitHub issue #20 was not found"))));

        // when
        JsonNode result = updateBlockerRecheckStatus(handler, "resumed");

        // then
        assertThat(result.path("success").asBoolean()).isFalse();
        assertThat(result.path("contentItems").get(0).path("text").asText()).contains("trello_blocker_recheck_stale");
        assertThat(updatedCommentText.get()).isNull();
        assertThat(commentText.get()).isNull();
    }

    @Test
    void normalPickupDoesNotCreateBlockerRecheckStatus() {
        // given
        TrelloHandoffToolHandler handler = handler();
        cardResponse.set(
                cardJson(actionsJson(commentAction("action-human", "Please implement the requested change."))));

        // when
        JsonNode result = updateBlockerRecheckStatus(handler, "checking");

        // then
        assertThat(result.path("success").asBoolean()).isTrue();
        assertThat(result.path("contentItems").get(0).path("text").asText()).contains("blocker_recheck_not_needed");
        assertThat(commentText.get()).isNull();
        assertThat(updatedCommentText.get()).isNull();
    }

    @Test
    void workpadAndPrerequisiteStatusesAreExcludedBeforeClassifyingBlockerHandoff() {
        // given
        TrelloHandoffToolHandler handler = handler();
        cardResponse.set(cardJson(actionsJson(
                commentAction("action-workpad", "## Codex Workpad\n\nBlocked: internal plan note"),
                commentAction(
                        "action-prerequisite",
                        TrelloClient.PREREQUISITE_STATUS_COMMENT_MARKER + "\n\nBlocked by a prerequisite"),
                commentAction(BLOCKER_ACTION_ID, "Blocked: GitHub issue #20 was not found"))));

        // when
        JsonNode result = updateBlockerRecheckStatus(handler, "checking");

        // then
        assertThat(result.path("success").asBoolean()).isTrue();
        assertThat(commentText.get()).contains(CHECKING_STATUS, "#comment-" + BLOCKER_ACTION_ID);
    }

    @MethodSource("managedFamilyHeadings")
    @ParameterizedTest(name = "managed heading {0}")
    @SuppressWarnings("JUnitValueSource") // A multiline ValueSource is misread as a test body by TestConventionTest.
    void leadingWhitespaceBeforeManagedFamilyHeadingMakesItAnOrdinaryNewerComment(String managedHeading) {
        // given
        TrelloHandoffToolHandler handler = handler();
        cardResponse.set(cardJson(actionsJson(
                commentAction("action-newer", "  " + managedHeading + "\n\nHuman follow-up"),
                commentAction(BLOCKER_ACTION_ID, "Blocked: older repository mismatch"))));

        // when
        JsonNode result = updateBlockerRecheckStatus(handler, "checking");

        // then
        assertThat(result.path("contentItems").get(0).path("text").asText()).contains("blocker_recheck_not_needed");
        assertThat(commentText.get()).isNull();
    }

    @Test
    void newerHumanDiscussionSupersedesOlderBlockerHandoff() {
        // given
        TrelloHandoffToolHandler handler = handler();
        cardResponse.set(cardJson(actionsJson(
                commentAction("action-human", "We discussed the blocked workflow and supplied new context."),
                commentAction(BLOCKER_ACTION_ID, "Blocked: GitHub issue #20 was not found"))));

        // when
        JsonNode result = updateBlockerRecheckStatus(handler, "checking");

        // then
        assertThat(result.path("contentItems").get(0).path("text").asText()).contains("blocker_recheck_not_needed");
        assertThat(commentText.get()).isNull();
        assertThat(updatedCommentText.get()).isNull();
    }

    @Test
    void managedCheckingStatusMakesCrashRetryIdempotent() {
        // given
        TrelloHandoffToolHandler handler = handler();
        cardResponse.set(cardJson(actionsJson(
                managedRecheckAction(BLOCKER_RECHECK_ACTION_ID, CHECKING_STATUS, BLOCKER_ACTION_ID),
                commentAction(BLOCKER_ACTION_ID, "Blocked: transient repository mismatch"))));

        // when
        JsonNode firstRetry = updateBlockerRecheckStatus(handler, "checking");
        JsonNode secondRetry = updateBlockerRecheckStatus(handler, "checking");

        // then
        assertThat(firstRetry.path("contentItems").get(0).path("text").asText())
                .contains("blocker_recheck_already_started");
        assertThat(secondRetry.path("contentItems").get(0).path("text").asText())
                .contains("blocker_recheck_already_started");
        assertThat(commentText.get()).isNull();
        assertThat(updatedCommentText.get()).isNull();
    }

    @Test
    void priorResumedStatusDoesNotRegressToCheckingForTheSameBlocker() {
        // given
        TrelloHandoffToolHandler handler = handler();
        cardResponse.set(cardJson(actionsJson(
                managedRecheckAction(
                        BLOCKER_RECHECK_ACTION_ID,
                        "No longer blocked; working on Implement feature.",
                        BLOCKER_ACTION_ID),
                commentAction(BLOCKER_ACTION_ID, "Blocked: transient repository mismatch"))));

        // when
        JsonNode checking = updateBlockerRecheckStatus(handler, "checking");
        JsonNode resumed = updateBlockerRecheckStatus(handler, "resumed");

        // then
        assertThat(checking.path("contentItems").get(0).path("text").asText())
                .contains("resumed_work_already_confirmed");
        assertThat(resumed.path("contentItems").get(0).path("text").asText())
                .contains("resumed_work_already_confirmed");
        assertThat(commentText.get()).isNull();
        assertThat(updatedCommentText.get()).isNull();
    }

    @Test
    void newerBlockerAfterPriorResumedStatusReentersCheckingOnTheSameManagedComment() {
        // given
        TrelloHandoffToolHandler handler = handler();
        cardResponse.set(cardJson(actionsJson(
                commentAction("action-blocker-new", "Blocked by a new missing permission"),
                managedRecheckAction(
                        BLOCKER_RECHECK_ACTION_ID,
                        "No longer blocked; working on Implement feature.",
                        BLOCKER_ACTION_ID),
                commentAction(BLOCKER_ACTION_ID, "Blocked: old repository mismatch"))));

        // when
        JsonNode result = updateBlockerRecheckStatus(handler, "checking");

        // then
        assertThat(result.path("success").asBoolean()).isTrue();
        assertThat(updatedCommentText.get())
                .contains(CHECKING_STATUS, "#comment-action-blocker-new")
                .doesNotContain("No longer blocked");
        assertThat(commentText.get()).isNull();
    }

    @Test
    void fullCommentWindowRefusesToCreatePossiblyDuplicateManagedStatus() {
        // given
        TrelloHandoffToolHandler handler = handler();
        cardResponse.set(cardJson(fullCommentWindowWithBlocker()));

        // when
        JsonNode result = updateBlockerRecheckStatus(handler, "checking");

        // then
        assertThat(result.path("success").asBoolean()).isFalse();
        assertThat(result.path("contentItems").get(0).path("text").asText())
                .contains("trello_blocker_recheck_comment_window_incomplete");
        assertThat(commentText.get()).isNull();
    }

    @Test
    void blockerWithoutActionIdCannotStartManagedRecheck() {
        // given
        TrelloHandoffToolHandler handler = handler();
        cardResponse.set(cardJson(actionsJson(commentAction(null, "Blocked: missing source action id"))));

        // when
        JsonNode result = updateBlockerRecheckStatus(handler, "checking");

        // then
        assertThat(result.path("success").asBoolean()).isFalse();
        assertThat(result.path("contentItems").get(0).path("text").asText())
                .contains("trello_blocker_recheck_missing_source_id");
        assertThat(commentText.get()).isNull();
    }

    @Test
    void managedStatusesWithoutActionIdsDoNotCauseAnotherStatusToBeCreated() {
        // given
        TrelloHandoffToolHandler handler = handler();
        cardResponse.set(cardJson(actionsJson(
                managedRecheckAction(null, CHECKING_STATUS, BLOCKER_ACTION_ID),
                commentAction(BLOCKER_ACTION_ID, "Blocked: repository mismatch"))));

        // when
        JsonNode result = updateBlockerRecheckStatus(handler, "checking");

        // then
        assertThat(result.path("success").asBoolean()).isFalse();
        assertThat(result.path("contentItems").get(0).path("text").asText())
                .contains("trello_blocker_recheck_missing_action_id");
        assertThat(commentText.get()).isNull();
        assertThat(updatedCommentText.get()).isNull();
    }

    @Test
    void newestAddressableManagedStatusWinsWhenANewerDuplicateHasNoActionId() {
        // given
        TrelloHandoffToolHandler handler = handler();
        cardResponse.set(cardJson(actionsJson(
                managedRecheckAction(null, CHECKING_STATUS, BLOCKER_ACTION_ID),
                managedRecheckAction("action-blocker-recheck-older", CHECKING_STATUS, BLOCKER_ACTION_ID),
                commentAction(BLOCKER_ACTION_ID, "Blocked: repository mismatch"))));

        // when
        JsonNode result = updateBlockerRecheckStatus(handler, "checking");

        // then
        assertThat(result.path("success").asBoolean()).isTrue();
        assertThat(result.path("contentItems").get(0).path("text").asText())
                .contains("\"action_id\":\"action-blocker-recheck-older\"", "\"duplicate_statuses_found\":\"1\"");
        assertThat(updatedCommentText.get()).contains(TrelloHandoffToolHandler.DUPLICATE_BLOCKER_RECHECK_NOTE_PREFIX);
        assertThat(commentText.get()).isNull();
    }

    @Test
    void rejectsUnknownBlockerRecheckStateBeforeReadingOrMutatingTrello() {
        // given
        TrelloHandoffToolHandler handler = handler();

        // when
        JsonNode result = updateBlockerRecheckStatus(handler, "complete");

        // then
        assertThat(result.path("success").asBoolean()).isFalse();
        assertThat(result.path("contentItems").get(0).path("text").asText()).contains("invalid_blocker_recheck_status");
        assertThat(commentText.get()).isNull();
        assertThat(updatedCommentText.get()).isNull();
    }

    @Test
    void duplicateStatusesStayVisibleWithManualCleanupNoteWhenDeletesAreDisabled() {
        // given
        TrelloHandoffToolHandler handler = handler();
        cardResponse.set(cardJson(actionsJson(
                managedRecheckAction(BLOCKER_RECHECK_ACTION_ID, CHECKING_STATUS, BLOCKER_ACTION_ID),
                managedRecheckAction("action-blocker-recheck-older", CHECKING_STATUS, BLOCKER_ACTION_ID),
                commentAction(BLOCKER_ACTION_ID, "Blocked: repository mismatch"))));

        // when
        JsonNode result = updateBlockerRecheckStatus(handler, "checking");

        // then
        assertThat(result.path("contentItems").get(0).path("text").asText())
                .contains("\"duplicate_statuses_found\":\"1\"", "skipped_destructive_operations_disabled");
        assertThat(updatedCommentText.get())
                .contains(TrelloHandoffToolHandler.DUPLICATE_BLOCKER_RECHECK_NOTE_PREFIX)
                .contains("delete the duplicate blocker recheck comments manually")
                .endsWith("https://trello.com/c/SYNTH101#comment-" + BLOCKER_ACTION_ID + ")_");
        assertThat(deletedActionIds).isEmpty();
    }

    @Test
    void destructivePolicyRemovesDuplicateStatusAfterAuthoritativeStateIsSafe() {
        // given
        TrelloHandoffToolHandler handler = handler();
        cardResponse.set(cardJson(actionsJson(
                managedRecheckAction(BLOCKER_RECHECK_ACTION_ID, CHECKING_STATUS, BLOCKER_ACTION_ID),
                managedRecheckAction("action-blocker-recheck-older", CHECKING_STATUS, BLOCKER_ACTION_ID),
                commentAction(BLOCKER_ACTION_ID, "Blocked: repository mismatch"))));

        // when
        JsonNode result = updateBlockerRecheckStatus(handler, "resumed", configWithDestructiveOperations());

        // then
        assertThat(result.path("contentItems").get(0).path("text").asText())
                .contains("\"duplicate_statuses_removed\":\"1\"", "\"duplicate_statuses_cleanup_status\":\"removed\"");
        assertThat(deletedActionIds).containsExactly("action-blocker-recheck-older");
        assertThat(managedCommentCallOrder)
                .as("the authoritative blocker recheck update must run before any duplicate delete")
                .containsExactly("update:" + BLOCKER_RECHECK_ACTION_ID, "delete:action-blocker-recheck-older");
    }

    @Test
    void failedAuthoritativeStatusUpdateDoesNotDeleteDuplicateStatuses() {
        // given
        TrelloHandoffToolHandler handler = handler();
        updateStatus.set(500);
        cardResponse.set(cardJson(actionsJson(
                commentAction("action-blocker-new", "Blocked by a new missing permission"),
                managedRecheckAction(BLOCKER_RECHECK_ACTION_ID, CHECKING_STATUS, BLOCKER_ACTION_ID),
                managedRecheckAction("action-blocker-recheck-older", CHECKING_STATUS, BLOCKER_ACTION_ID),
                commentAction(BLOCKER_ACTION_ID, "Blocked: old repository mismatch"))));

        // when
        JsonNode result = updateBlockerRecheckStatus(handler, "checking", configWithDestructiveOperations());

        // then
        assertThat(result.path("success").asBoolean()).isFalse();
        assertThat(deletedActionIds).isEmpty();
        assertThat(commentText.get()).isNull();
    }

    @Test
    void duplicateStatusDeleteFailureKeepsAuthoritativeUpdateSuccessfulAndReportsFailure() {
        // Expected WARN: duplicate cleanup is best effort after the authoritative status is safe.
        // given
        TrelloHandoffToolHandler handler = handler();
        deleteStatus.set(500);
        cardResponse.set(cardJson(actionsJson(
                managedRecheckAction(BLOCKER_RECHECK_ACTION_ID, CHECKING_STATUS, BLOCKER_ACTION_ID),
                managedRecheckAction("action-blocker-recheck-older", CHECKING_STATUS, BLOCKER_ACTION_ID),
                commentAction(BLOCKER_ACTION_ID, "Blocked: repository mismatch"))));

        // when
        JsonNode result = updateBlockerRecheckStatus(handler, "checking", configWithDestructiveOperations());

        // then
        assertThat(result.path("success").asBoolean()).isTrue();
        assertThat(result.path("contentItems").get(0).path("text").asText())
                .contains("\"duplicate_statuses_delete_failed\":\"1\"", "delete_failed");
        assertThat(deletedActionIds).isEmpty();
    }

    @Test
    void humanCommentUsingVisibleRecheckHeadingIsNeverTreatedAsManaged() {
        // given
        TrelloHandoffToolHandler handler = handler();
        cardResponse.set(cardJson(actionsJson(
                commentAction(
                        "action-human",
                        "## Symphony Blocker Recheck\n\nA human discussion that must not be edited or deleted."),
                commentAction(BLOCKER_ACTION_ID, "Blocked: older repository mismatch"))));

        // when
        JsonNode result = updateBlockerRecheckStatus(handler, "checking", configWithDestructiveOperations());

        // then
        assertThat(result.path("contentItems").get(0).path("text").asText()).contains("blocker_recheck_not_needed");
        assertThat(updatedCommentText.get()).isNull();
        assertThat(deletedActionIds).isEmpty();
    }

    @Test
    void footerLinkForAnotherCardIsNeverTreatedAsManaged() {
        // given
        TrelloHandoffToolHandler handler = handler();
        String otherCardFooter = TrelloHandoffToolHandler.BLOCKER_RECHECK_FOOTER_PREFIX
                + "https://trello.com/c/DIFFERENT_CARD#comment-action-human"
                + TrelloHandoffToolHandler.BLOCKER_RECHECK_FOOTER_SUFFIX;
        cardResponse.set(cardJson(actionsJson(
                commentAction("action-human", CHECKING_STATUS + "\n\n" + otherCardFooter),
                commentAction(BLOCKER_ACTION_ID, "Blocked: older repository mismatch"))));

        // when
        JsonNode result = updateBlockerRecheckStatus(handler, "checking");

        // then
        assertThat(result.path("contentItems").get(0).path("text").asText()).contains("blocker_recheck_not_needed");
        assertThat(commentText.get()).isNull();
        assertThat(updatedCommentText.get()).isNull();
    }

    @Test
    void footerLinkToMissingActionIsNeverTreatedAsManaged() {
        // given
        TrelloHandoffToolHandler handler = handler();
        cardResponse.set(cardJson(actionsJson(
                commentAction("action-human", managedRecheckText(CHECKING_STATUS, "action-missing")),
                commentAction(BLOCKER_ACTION_ID, "Blocked: older repository mismatch"))));

        // when
        JsonNode result = updateBlockerRecheckStatus(handler, "checking");

        // then
        assertThat(result.path("contentItems").get(0).path("text").asText()).contains("blocker_recheck_not_needed");
        assertThat(commentText.get()).isNull();
        assertThat(updatedCommentText.get()).isNull();
        assertThat(deletedActionIds).isEmpty();
    }

    @Test
    void footerLinkToOrdinaryNonBlockerActionIsNeverTreatedAsManaged() {
        // given
        TrelloHandoffToolHandler handler = handler();
        cardResponse.set(cardJson(actionsJson(
                commentAction("action-human", managedRecheckText(CHECKING_STATUS, "action-discussion")),
                commentAction("action-discussion", "The repository mismatch was discussed."),
                commentAction(BLOCKER_ACTION_ID, "Blocked: older repository mismatch"))));

        // when
        JsonNode result = updateBlockerRecheckStatus(handler, "checking");

        // then
        assertThat(result.path("contentItems").get(0).path("text").asText()).contains("blocker_recheck_not_needed");
        assertThat(commentText.get()).isNull();
        assertThat(updatedCommentText.get()).isNull();
        assertThat(deletedActionIds).isEmpty();
    }

    @Test
    void previousPrivateMarkerIsNotRetainedAsACompatibilityAlias() {
        // given
        TrelloHandoffToolHandler handler = handler();
        cardResponse.set(cardJson(actionsJson(
                commentAction(
                        "action-old-marker",
                        "<!-- symphony-trello:blocker-recheck -->\n\n"
                                + CHECKING_STATUS
                                + "\n\n<!-- symphony-blocker-comment: "
                                + BLOCKER_ACTION_ID
                                + " -->"),
                commentAction(BLOCKER_ACTION_ID, "Blocked: older repository mismatch"))));

        // when
        JsonNode result = updateBlockerRecheckStatus(handler, "checking");

        // then
        assertThat(result.path("contentItems").get(0).path("text").asText()).contains("blocker_recheck_not_needed");
        assertThat(commentText.get()).isNull();
        assertThat(updatedCommentText.get()).isNull();
    }

    @Test
    void exactManagedFooterStillIdentifiesStatusWhenVisibleTextHasLeadingWhitespace() {
        // given
        TrelloHandoffToolHandler handler = handler();
        cardResponse.set(cardJson(actionsJson(
                commentAction(BLOCKER_RECHECK_ACTION_ID, "  " + managedRecheckText(CHECKING_STATUS, BLOCKER_ACTION_ID)),
                commentAction(BLOCKER_ACTION_ID, "Blocked: older repository mismatch"))));

        // when
        JsonNode result = updateBlockerRecheckStatus(handler, "checking");

        // then
        assertThat(result.path("contentItems").get(0).path("text").asText()).contains("blocker_recheck_started");
        assertThat(commentText.get()).isNull();
        assertThat(updatedCommentText.get()).isEqualTo(managedRecheckText(CHECKING_STATUS, BLOCKER_ACTION_ID));
    }

    @Test
    void resumedTaskSummaryNeutralizesMarkupControlsAndStaysWithinCodePointLimit() {
        // given
        TrelloHandoffToolHandler handler = handler();
        String unsafeTitle = "Fix [link](https://example.invalid) <script> @reviewer\r\nnext\u2028\u202E **bold** "
                + "\uD83D\uDE80".repeat(150)
                + ".";
        cardResponse.set(cardJson(
                unsafeTitle,
                actionsJson(
                        managedRecheckAction(BLOCKER_RECHECK_ACTION_ID, CHECKING_STATUS, BLOCKER_ACTION_ID),
                        commentAction(BLOCKER_ACTION_ID, "Blocked: repository mismatch"))));

        // when
        JsonNode result = updateBlockerRecheckStatus(handler, "resumed");

        // then
        assertThat(result.path("success").asBoolean()).isTrue();
        String visibleStatus = resumedStatusLine(updatedCommentText.get());
        String summary = visibleStatus.substring(RESUMED_STATUS_PREFIX.length());
        assertThat(summary.codePointCount(0, summary.length())).isLessThanOrEqualTo(120);
        assertThat(visibleStatus)
                .doesNotContain("\r", "\u2028", "\u202E", "[", "]", "(", ")", "<", ">", "**", "https:", "@reviewer")
                .doesNotEndWith("....");
    }

    @Test
    void resumedTaskSummaryDoesNotDuplicateExistingSentencePunctuation() {
        // given
        TrelloHandoffToolHandler handler = handler();
        cardResponse.set(cardJson(
                "Implement #20.",
                actionsJson(
                        managedRecheckAction(BLOCKER_RECHECK_ACTION_ID, CHECKING_STATUS, BLOCKER_ACTION_ID),
                        commentAction(BLOCKER_ACTION_ID, "Blocked: repository mismatch"))));

        // when
        updateBlockerRecheckStatus(handler, "resumed");

        // then
        assertThat(updatedCommentText.get())
                .contains("No longer blocked; working on Implement #20.")
                .doesNotContain("Implement #20..");
    }

    @Test
    void resumedTaskSummaryFinalSentenceHonorsCodePointLimit() {
        // given
        TrelloHandoffToolHandler handler = handler();
        cardResponse.set(cardJson(
                "x".repeat(119),
                actionsJson(
                        managedRecheckAction(BLOCKER_RECHECK_ACTION_ID, CHECKING_STATUS, BLOCKER_ACTION_ID),
                        commentAction(BLOCKER_ACTION_ID, "Blocked: repository mismatch"))));

        // when
        updateBlockerRecheckStatus(handler, "resumed");

        // then
        String visibleStatus = resumedStatusLine(updatedCommentText.get());
        String summary = visibleStatus.substring(RESUMED_STATUS_PREFIX.length());
        assertThat(summary.codePointCount(0, summary.length())).isEqualTo(120);
        assertThat(summary).endsWith(".");
    }

    @Test
    void updatesExistingWorkpadCommentInsteadOfCreatingDuplicate() {
        // given
        TrelloHandoffToolHandler handler = handler();
        cardResponse.set(
                cardJson(
                        """
                [
                  {
                    "id":"action-workpad",
                    "data":{"text":"## Codex Workpad\\n\\nOld plan"},
                    "date":"2026-05-05T00:00:00.000Z",
                    "memberCreator":{"fullName":"Codex"}
                  }
                ]
                """));

        // when
        JsonNode result = upsertWorkpad(handler);

        // then
        assertThat(result.path("success").asBoolean()).isTrue();
        assertThat(result.path("contentItems").get(0).path("text").asText())
                .contains("workpad_updated", "\"duplicate_workpads_cleanup_status\":\"not_needed\"");
        assertThat(updatedCommentText.get()).isEqualTo("## Codex Workpad\n\nUpdated plan");
        assertThat(commentText.get()).isNull();
    }

    @Test
    void agentWorkpadUpsertPreservesUsageSectionUntilOrchestratorCleanup() throws Exception {
        // given
        TrelloHandoffToolHandler handler = handler();
        EffectiveConfig config = config(List.of("Review"), List.of());
        String paused =
                CodexUsageWorkpadSection.paused("Authoritative usage pause.", Instant.parse("2026-07-10T13:00:00Z"));
        cardResponse.set(
                cardJsonWithWorkpad(CodexUsageWorkpadSection.upsert("## Codex Workpad\n\nOld agent plan", paused)));

        // when
        JsonNode agentUpdate = upsertWorkpad(handler, "## Codex Workpad\n\nUpdated agent plan");
        String preserved = updatedCommentText.get();
        cardResponse.set(cardJsonWithWorkpad(preserved));
        updatedCommentText.set(null);
        boolean orchestratorCleanup = handler.updateCodexUsageSection(config, "card-1", null);

        // then
        assertThat(agentUpdate.path("success").asBoolean()).isTrue();
        assertThat(preserved)
                .contains("Updated agent plan", paused)
                .doesNotContain("Old agent plan")
                .containsOnlyOnce(CodexUsageWorkpadSection.START_MARKER);
        assertThat(orchestratorCleanup).isTrue();
        assertThat(updatedCommentText.get()).isEqualTo("## Codex Workpad\n\nUpdated agent plan");
    }

    @Test
    void agentWorkpadUpsertCannotReplaceAuthoritativeUsageSection() throws Exception {
        // given
        TrelloHandoffToolHandler handler = handler();
        String authoritative =
                CodexUsageWorkpadSection.paused("Authoritative usage pause.", Instant.parse("2026-07-10T13:00:00Z"));
        String forged = CodexUsageWorkpadSection.rechecking("Forged agent state.");
        cardResponse.set(cardJsonWithWorkpad(
                CodexUsageWorkpadSection.upsert("## Codex Workpad\n\nOld agent plan", authoritative)));

        // when
        JsonNode result = upsertWorkpad(handler, "## Codex Workpad\n\nUpdated agent plan\n\n" + forged);

        // then
        assertThat(result.path("success").asBoolean()).isFalse();
        assertThat(result.path("contentItems").get(0).path("text").asText())
                .contains("trello_workpad_managed_section_forbidden");
        assertThat(cardFetchCount).hasValue(0);
        assertThat(updatedCommentText.get()).isNull();
        assertThat(managedCommentCallOrder).isEmpty();
    }

    @Test
    void agentWorkpadUpsertCannotCreateForgedUsageSection() {
        // given
        TrelloHandoffToolHandler handler = handler();
        String forged = CodexUsageWorkpadSection.rechecking("Forged agent state.");
        cardResponse.set(cardJson("[]"));

        // when
        JsonNode result = upsertWorkpad(handler, "## Codex Workpad\n\nAgent plan\n\n" + forged);

        // then
        assertThat(result.path("success").asBoolean()).isFalse();
        assertThat(result.path("contentItems").get(0).path("text").asText())
                .contains("trello_workpad_managed_section_forbidden");
        assertThat(cardFetchCount).hasValue(0);
        assertThat(commentText.get()).isNull();
        assertThat(managedCommentCallOrder).isEmpty();
    }

    @Test
    void agentWorkpadUpsertPreservesUsageSectionWhileReportingDuplicateWorkpads() throws Exception {
        // given
        TrelloHandoffToolHandler handler = handler();
        String paused =
                CodexUsageWorkpadSection.paused("Authoritative usage pause.", Instant.parse("2026-07-10T13:00:00Z"));
        cardResponse.set(cardJsonWithDuplicateWorkpads(
                CodexUsageWorkpadSection.upsert("## Codex Workpad\n\nOld agent plan", paused)));

        // when
        JsonNode result = upsertWorkpad(handler, "## Codex Workpad\n\nUpdated agent plan");

        // then
        assertThat(result.path("success").asBoolean()).isTrue();
        assertThat(updatedCommentText.get())
                .contains("Updated agent plan", paused)
                .containsOnlyOnce(CodexUsageWorkpadSection.START_MARKER)
                .containsOnlyOnce(TrelloHandoffToolHandler.DUPLICATE_WORKPADS_NOTE_PREFIX);
    }

    @Test
    void forgedAgentUsageSectionCannotMutateOrDeleteDuplicateWorkpads() throws Exception {
        // given
        TrelloHandoffToolHandler handler = handler();
        String paused =
                CodexUsageWorkpadSection.paused("Authoritative usage pause.", Instant.parse("2026-07-10T13:00:00Z"));
        String forged = CodexUsageWorkpadSection.rechecking("Forged agent state.");
        cardResponse.set(cardJsonWithDuplicateWorkpads(
                CodexUsageWorkpadSection.upsert("## Codex Workpad\n\nOld agent plan", paused)));

        // when
        JsonNode result = handler.handle(
                configWithDestructiveOperations(),
                TestCards.card("card-1", "TRELLO-abc", "Ready for Codex"),
                json.createObjectNode()
                        .put("tool", TrelloHandoffToolHandler.UPSERT_WORKPAD)
                        .set(
                                "arguments",
                                json.createObjectNode()
                                        .put("text", "## Codex Workpad\n\nUpdated agent plan\n\n" + forged)));

        // then
        assertThat(result.path("success").asBoolean()).isFalse();
        assertThat(result.path("contentItems").get(0).path("text").asText())
                .contains("trello_workpad_managed_section_forbidden");
        assertThat(cardFetchCount).hasValue(0);
        assertThat(updatedCommentText.get()).isNull();
        assertThat(deletedActionIds).isEmpty();
        assertThat(managedCommentCallOrder).isEmpty();
    }

    @Test
    void agentWorkpadUpsertRejectsMalformedForgedUsageSection() {
        // given
        TrelloHandoffToolHandler handler = handler();
        cardResponse.set(cardJson("[]"));
        String malformed = "## Codex Workpad\n\nAgent plan\n\n" + CodexUsageWorkpadSection.START_MARKER;

        // when
        JsonNode result = upsertWorkpad(handler, malformed);

        // then
        assertThat(result.path("success").asBoolean()).isFalse();
        assertThat(result.path("contentItems").get(0).path("text").asText())
                .contains("trello_workpad_managed_section_malformed");
        assertThat(cardFetchCount).hasValue(0);
        assertThat(commentText.get()).isNull();
        assertThat(updatedCommentText.get()).isNull();
    }

    @Test
    void agentWorkpadUpsertRejectsManagedMarkersBeforeCleanupNoteNormalization() {
        // given
        TrelloHandoffToolHandler handler = handler();
        cardStatus.set(500);
        String hiddenForgedSection = TrelloHandoffToolHandler.DUPLICATE_WORKPADS_NOTE_PREFIX
                + "1 stale duplicate "
                + CodexUsageWorkpadSection.START_MARKER
                + " forged state "
                + CodexUsageWorkpadSection.END_MARKER;

        // when
        JsonNode result = upsertWorkpad(handler, "## Codex Workpad\n\nAgent plan\n\n" + hiddenForgedSection);

        // then
        assertThat(result.path("success").asBoolean()).isFalse();
        assertThat(result.path("contentItems").get(0).path("text").asText())
                .contains("trello_workpad_managed_section_forbidden");
        assertThat(cardFetchCount).hasValue(0);
        assertThat(commentText.get()).isNull();
        assertThat(managedCommentCallOrder).isEmpty();
    }

    @Test
    void agentWorkpadUpsertRejectsUnmatchedEndMarkerBeforeFetchingCard() {
        // given
        TrelloHandoffToolHandler handler = handler();
        cardStatus.set(500);

        // when
        JsonNode result =
                upsertWorkpad(handler, "## Codex Workpad\n\nAgent plan\n\n" + CodexUsageWorkpadSection.END_MARKER);

        // then
        assertThat(result.path("success").asBoolean()).isFalse();
        assertThat(result.path("contentItems").get(0).path("text").asText())
                .contains("trello_workpad_managed_section_malformed");
        assertThat(cardFetchCount).hasValue(0);
        assertThat(commentText.get()).isNull();
        assertThat(managedCommentCallOrder).isEmpty();
    }

    @Test
    void malformedManagedSectionInOlderWorkpadBlocksAllWritersBeforeMutation() throws Exception {
        // given
        TrelloHandoffToolHandler handler = handler();
        EffectiveConfig config = configWithDestructiveOperations();
        String malformedOlder = "## Codex Workpad\n\nOlder plan\n\n" + CodexUsageWorkpadSection.START_MARKER;
        cardResponse.set(cardJsonWithTwoWorkpads("## Codex Workpad\n\nNewest plan", malformedOlder));
        String paused = CodexUsageWorkpadSection.paused("Usage is unavailable.", Instant.parse("2026-07-10T13:00:00Z"));

        // when
        JsonNode agentResult = handler.handle(
                config,
                TestCards.card("card-1", "TRELLO-abc", "Ready for Codex"),
                json.createObjectNode()
                        .put("tool", TrelloHandoffToolHandler.UPSERT_WORKPAD)
                        .set("arguments", json.createObjectNode().put("text", "## Codex Workpad\n\nUpdated plan")));
        boolean orchestratorResult = handler.updateCodexUsageSection(config, "card-1", paused);

        // then
        assertThat(agentResult.path("success").asBoolean()).isFalse();
        assertThat(agentResult.path("contentItems").get(0).path("text").asText())
                .contains("trello_workpad_managed_section_malformed");
        assertThat(orchestratorResult).isFalse();
        assertThat(updatedCommentText.get()).isNull();
        assertThat(deletedActionIds).isEmpty();
        assertThat(managedCommentCallOrder).isEmpty();
    }

    @Test
    void multipleManagedSectionsAcrossDuplicateWorkpadsBlockAllWritersBeforeMutation() throws Exception {
        // given
        TrelloHandoffToolHandler handler = handler();
        EffectiveConfig config = configWithDestructiveOperations();
        String primarySection =
                CodexUsageWorkpadSection.paused("Primary state.", Instant.parse("2026-07-10T13:00:00Z"));
        String olderSection = CodexUsageWorkpadSection.rechecking("Older state.");
        cardResponse.set(cardJsonWithTwoWorkpads(
                CodexUsageWorkpadSection.upsert("## Codex Workpad\n\nNewest plan", primarySection),
                CodexUsageWorkpadSection.upsert("## Codex Workpad\n\nOlder plan", olderSection)));

        // when
        JsonNode agentResult = handler.handle(
                config,
                TestCards.card("card-1", "TRELLO-abc", "Ready for Codex"),
                json.createObjectNode()
                        .put("tool", TrelloHandoffToolHandler.UPSERT_WORKPAD)
                        .set("arguments", json.createObjectNode().put("text", "## Codex Workpad\n\nUpdated plan")));
        boolean orchestratorResult = handler.updateCodexUsageSection(config, "card-1", null);

        // then
        assertThat(agentResult.path("success").asBoolean()).isFalse();
        assertThat(agentResult.path("contentItems").get(0).path("text").asText())
                .contains("trello_workpad_managed_section_ambiguous");
        assertThat(orchestratorResult).isFalse();
        assertThat(updatedCommentText.get()).isNull();
        assertThat(deletedActionIds).isEmpty();
        assertThat(managedCommentCallOrder).isEmpty();
    }

    @Test
    void destructiveAgentConsolidationPreservesSectionOwnedByOlderWorkpad() throws Exception {
        // given
        TrelloHandoffToolHandler handler = handler();
        String paused =
                CodexUsageWorkpadSection.paused("Authoritative usage pause.", Instant.parse("2026-07-10T13:00:00Z"));
        cardResponse.set(cardJsonWithTwoWorkpads(
                "## Codex Workpad\n\nNewest plan",
                CodexUsageWorkpadSection.upsert("## Codex Workpad\n\nOlder plan", paused)));

        // when
        JsonNode result = handler.handle(
                configWithDestructiveOperations(),
                TestCards.card("card-1", "TRELLO-abc", "Ready for Codex"),
                json.createObjectNode()
                        .put("tool", TrelloHandoffToolHandler.UPSERT_WORKPAD)
                        .set("arguments", json.createObjectNode().put("text", "## Codex Workpad\n\nUpdated plan")));

        // then
        assertThat(result.path("success").asBoolean()).isTrue();
        assertThat(updatedCommentText.get())
                .contains("Updated plan", paused)
                .containsOnlyOnce(CodexUsageWorkpadSection.START_MARKER);
        assertThat(managedCommentCallOrder).containsExactly("update:action-workpad", "delete:action-workpad-older");
    }

    @Test
    void failedOlderOwnerDeleteRollsBackTransferSoAHealthyRetryCanConsolidate() throws Exception {
        // given
        TrelloHandoffToolHandler handler = handler();
        EffectiveConfig config = configWithDestructiveOperations();
        String paused =
                CodexUsageWorkpadSection.paused("Authoritative usage pause.", Instant.parse("2026-07-10T13:00:00Z"));
        String olderWorkpad = CodexUsageWorkpadSection.upsert("## Codex Workpad\n\nOlder plan", paused);
        cardResponse.set(cardJsonWithTwoWorkpads("## Codex Workpad\n\nNewest plan", olderWorkpad));
        deleteStatus.set(500);

        // when
        JsonNode firstResult = handler.handle(
                config,
                TestCards.card("card-1", "TRELLO-abc", "Ready for Codex"),
                json.createObjectNode()
                        .put("tool", TrelloHandoffToolHandler.UPSERT_WORKPAD)
                        .set("arguments", json.createObjectNode().put("text", "## Codex Workpad\n\nUpdated plan")));
        String rolledBackPrimary = updatedCommentText.get();

        // then
        assertThat(firstResult.path("success").asBoolean()).isFalse();
        assertThat(firstResult.path("contentItems").get(0).path("text").asText())
                .contains("trello_workpad_managed_section_cleanup_failed");
        assertThat(rolledBackPrimary).isEqualTo("## Codex Workpad\n\nNewest plan");
        assertThat(managedCommentCallOrder)
                .containsExactly("update:action-workpad", "delete:action-workpad-older", "update:action-workpad");

        // and when Trello recovers and the rolled-back state is read again
        cardResponse.set(cardJsonWithTwoWorkpads(rolledBackPrimary, olderWorkpad));
        deleteStatus.set(200);
        managedCommentCallOrder.clear();
        updatedCommentText.set(null);
        JsonNode retryResult = handler.handle(
                config,
                TestCards.card("card-1", "TRELLO-abc", "Ready for Codex"),
                json.createObjectNode()
                        .put("tool", TrelloHandoffToolHandler.UPSERT_WORKPAD)
                        .set("arguments", json.createObjectNode().put("text", "## Codex Workpad\n\nRetry plan")));

        // then the managed section is transferred and its former owner is removed
        assertThat(retryResult.path("success").asBoolean()).isTrue();
        assertThat(updatedCommentText.get())
                .contains("Retry plan", paused)
                .containsOnlyOnce(CodexUsageWorkpadSection.START_MARKER);
        assertThat(managedCommentCallOrder).containsExactly("update:action-workpad", "delete:action-workpad-older");
    }

    @Test
    void nonDestructiveAgentUpdateFailsClosedWhenOlderWorkpadOwnsSection() throws Exception {
        // given
        TrelloHandoffToolHandler handler = handler();
        String paused =
                CodexUsageWorkpadSection.paused("Authoritative usage pause.", Instant.parse("2026-07-10T13:00:00Z"));
        cardResponse.set(cardJsonWithTwoWorkpads(
                "## Codex Workpad\n\nNewest plan",
                CodexUsageWorkpadSection.upsert("## Codex Workpad\n\nOlder plan", paused)));

        // when
        JsonNode result = upsertWorkpad(handler, "## Codex Workpad\n\nUpdated plan");

        // then
        assertThat(result.path("success").asBoolean()).isFalse();
        assertThat(result.path("contentItems").get(0).path("text").asText())
                .contains("trello_workpad_managed_section_non_primary");
        assertThat(updatedCommentText.get()).isNull();
        assertThat(deletedActionIds).isEmpty();
        assertThat(managedCommentCallOrder).isEmpty();
    }

    @Test
    void nonDestructiveOrchestratorCleanupRetriesWhenOlderWorkpadOwnsSection() throws Exception {
        // given
        TrelloHandoffToolHandler handler = handler();
        String paused =
                CodexUsageWorkpadSection.paused("Authoritative usage pause.", Instant.parse("2026-07-10T13:00:00Z"));
        cardResponse.set(cardJsonWithTwoWorkpads(
                "## Codex Workpad\n\nNewest plan",
                CodexUsageWorkpadSection.upsert("## Codex Workpad\n\nOlder plan", paused)));

        // when
        boolean result = handler.updateCodexUsageSection(config(List.of("Review"), List.of()), "card-1", null);

        // then
        assertThat(result).isFalse();
        assertThat(updatedCommentText.get()).isNull();
        assertThat(deletedActionIds).isEmpty();
        assertThat(managedCommentCallOrder).isEmpty();
    }

    @Test
    void updatesFirstWorkpadAndReportsDuplicatesVisiblyWithoutDestructiveOperationsOptIn() {
        // given
        TrelloHandoffToolHandler handler = handler();
        cardResponse.set(cardJson(duplicateWorkpadActionsJson()));

        // when
        JsonNode result = upsertWorkpad(handler);

        // then
        assertThat(result.path("success").asBoolean()).isTrue();
        assertThat(result.path("contentItems").get(0).path("text").asText())
                .contains(
                        "action-workpad",
                        "\"duplicate_workpads_found\":\"1\"",
                        "\"duplicate_workpads_removed\":\"0\"",
                        "\"duplicate_workpads_delete_failed\":\"0\"",
                        "\"duplicate_workpads_cleanup_status\":\"skipped_destructive_operations_disabled\"");
        assertThat(updatedCommentText.get())
                .as("the card-visible workpad must tell the next agent or human about the manual cleanup")
                .startsWith("## Codex Workpad\n\nUpdated plan")
                .contains(
                        TrelloHandoffToolHandler.DUPLICATE_WORKPADS_NOTE_PREFIX
                                + "1 other Codex workpad comment exists",
                        "delete the duplicate workpad comments manually");
        assertThat(deletedActionIds)
                .as("deleting Trello comments is destructive and needs the explicit opt-in")
                .isEmpty();
        assertThat(commentText.get()).isNull();
    }

    @Test
    void dropsAnEchoedCleanupNoteOnceTheDuplicatesAreGone() {
        // given
        TrelloHandoffToolHandler handler = handler();
        cardResponse.set(
                cardJson(
                        """
                [
                  {
                    "id":"action-workpad",
                    "data":{"text":"## Codex Workpad\\n\\nOld plan"},
                    "date":"2026-05-05T00:00:00.000Z",
                    "memberCreator":{"fullName":"Codex"}
                  }
                ]
                """));
        String echoedNote = TrelloHandoffToolHandler.DUPLICATE_WORKPADS_NOTE_PREFIX
                + "1 other Codex workpad comment exists on this card.";

        // when
        JsonNode result = handler.handle(
                config(List.of("Review"), List.of()),
                TestCards.card("card-1", "TRELLO-abc", "Ready for Codex"),
                json.createObjectNode()
                        .put("tool", TrelloHandoffToolHandler.UPSERT_WORKPAD)
                        .set(
                                "arguments",
                                json.createObjectNode()
                                        .put("text", "## Codex Workpad\n\nUpdated plan\n\n" + echoedNote)));

        // then
        assertThat(result.path("success").asBoolean()).isTrue();
        assertThat(updatedCommentText.get())
                .as("a stale echoed cleanup note must not survive once the duplicates are gone")
                .isEqualTo("## Codex Workpad\n\nUpdated plan");
    }

    @Test
    void removesDuplicateWorkpadsOnlyAfterTheAuthoritativeUpdateSucceeded() {
        // given
        TrelloHandoffToolHandler handler = handler();
        cardResponse.set(cardJson(duplicateWorkpadActionsJson()));

        // when
        JsonNode result = upsertWorkpadWithDestructiveOperations(handler);

        // then
        assertThat(result.path("success").asBoolean()).isTrue();
        assertThat(result.path("contentItems").get(0).path("text").asText())
                .contains(
                        "action-workpad",
                        "\"duplicate_workpads_found\":\"1\"",
                        "\"duplicate_workpads_removed\":\"1\"",
                        "\"duplicate_workpads_delete_failed\":\"0\"",
                        "\"duplicate_workpads_cleanup_status\":\"removed\"");
        assertThat(updatedCommentText.get())
                .as("the destructive opt-in cleans the card, so no manual-cleanup note is added")
                .isEqualTo("## Codex Workpad\n\nUpdated plan");
        assertThat(deletedActionIds).containsExactly("action-workpad-older");
        assertThat(managedCommentCallOrder)
                .as("the authoritative update must run before any duplicate delete")
                .containsExactly("update:action-workpad", "delete:action-workpad-older");
        assertThat(commentText.get()).isNull();
    }

    @Test
    void doesNotDeleteAnyDuplicateWhenTheAuthoritativeUpdateFails() {
        // given
        TrelloHandoffToolHandler handler = handler();
        cardResponse.set(cardJson(duplicateWorkpadActionsJson()));
        updateStatus.set(500);

        // when
        JsonNode result = upsertWorkpadWithDestructiveOperations(handler);

        // then
        assertThat(result.path("success").asBoolean()).isFalse();
        assertThat(deletedActionIds)
                .as("a failed authoritative update must leave every workpad in place")
                .isEmpty();
        assertThat(managedCommentCallOrder).containsExactly("update:action-workpad");
        assertThat(commentText.get()).isNull();
    }

    @Test
    void keepsTheUpsertSuccessfulAndReportsDuplicatesWhoseDeleteFailed() {
        // Expected WARN in the build log (issue #354): logging the failed duplicate-workpad
        // delete is the production behavior under test; muting shared logger categories would be
        // JVM-global state and is unsafe with parallel test execution.
        // given
        TrelloHandoffToolHandler handler = handler();
        cardResponse.set(cardJson(duplicateWorkpadActionsJson()));
        deleteStatus.set(500);

        // when
        JsonNode result = upsertWorkpadWithDestructiveOperations(handler);

        // then
        assertThat(result.path("success").asBoolean()).isTrue();
        assertThat(result.path("contentItems").get(0).path("text").asText())
                .contains(
                        "workpad_updated",
                        "\"duplicate_workpads_found\":\"1\"",
                        "\"duplicate_workpads_removed\":\"0\"",
                        "\"duplicate_workpads_delete_failed\":\"1\"",
                        "\"duplicate_workpads_cleanup_status\":\"delete_failed\"");
        assertThat(updatedCommentText.get()).isEqualTo("## Codex Workpad\n\nUpdated plan");
        assertThat(deletedActionIds).isEmpty();
    }

    @Test
    void removesAllAddressableDuplicatesDeterministically() {
        // given
        TrelloHandoffToolHandler handler = handler();
        cardResponse.set(cardJson(tripleWorkpadActionsJson()));

        // when
        JsonNode result = upsertWorkpadWithDestructiveOperations(handler);

        // then
        assertThat(result.path("success").asBoolean()).isTrue();
        assertThat(result.path("contentItems").get(0).path("text").asText())
                .contains(
                        "action-workpad",
                        "\"duplicate_workpads_found\":\"2\"",
                        "\"duplicate_workpads_removed\":\"2\"",
                        "\"duplicate_workpads_delete_failed\":\"0\"",
                        "\"duplicate_workpads_cleanup_status\":\"removed\"");
        assertThat(deletedActionIds).containsExactly("action-workpad-older", "action-workpad-oldest");
    }

    @Test
    void countsUnaddressableDuplicatesAsFailedCleanup() {
        // Expected WARN in the build log (issue #354): logging the workpad duplicate without an
        // action id is the production behavior under test; muting shared logger categories would
        // be JVM-global state and is unsafe with parallel test execution.
        // given
        TrelloHandoffToolHandler handler = handler();
        cardResponse.set(
                cardJson(
                        """
                [
                  {
                    "id":"action-workpad",
                    "data":{"text":"## Codex Workpad\\n\\nVisible first"},
                    "date":"2026-05-05T00:00:00.000Z",
                    "memberCreator":{"fullName":"Codex"}
                  },
                  {
                    "data":{"text":"## Codex Workpad\\n\\nNo action id"},
                    "date":"2026-05-04T00:00:00.000Z",
                    "memberCreator":{"fullName":"Codex"}
                  },
                  {
                    "id":"action-workpad-older",
                    "data":{"text":"## Codex Workpad\\n\\nVisible second"},
                    "date":"2026-05-03T00:00:00.000Z",
                    "memberCreator":{"fullName":"Codex"}
                  }
                ]
                """));

        // when
        JsonNode result = upsertWorkpadWithDestructiveOperations(handler);

        // then
        assertThat(result.path("success").asBoolean()).isTrue();
        assertThat(result.path("contentItems").get(0).path("text").asText())
                .contains(
                        "\"duplicate_workpads_found\":\"2\"",
                        "\"duplicate_workpads_removed\":\"1\"",
                        "\"duplicate_workpads_delete_failed\":\"1\"",
                        "\"duplicate_workpads_cleanup_status\":\"delete_failed\"");
        assertThat(deletedActionIds)
                .as("a duplicate without an action id cannot be deleted and must not vanish from the totals")
                .containsExactly("action-workpad-older");
    }

    @Test
    void reportsMissingActionIdWithoutDeletingAnyWorkpad() {
        // given
        TrelloHandoffToolHandler handler = handler();
        cardResponse.set(
                cardJson(
                        """
                [
                  {
                    "data":{"text":"## Codex Workpad\\n\\nNo id first"},
                    "date":"2026-05-05T00:00:00.000Z",
                    "memberCreator":{"fullName":"Codex"}
                  },
                  {
                    "data":{"text":"## Codex Workpad\\n\\nNo id second"},
                    "date":"2026-05-04T00:00:00.000Z",
                    "memberCreator":{"fullName":"Codex"}
                  }
                ]
                """));

        // when
        JsonNode result = upsertWorkpadWithDestructiveOperations(handler);

        // then
        assertThat(result.path("success").asBoolean()).isFalse();
        assertThat(result.path("contentItems").get(0).path("text").asText())
                .contains("trello_workpad_missing_action_id");
        assertThat(deletedActionIds).isEmpty();
        assertThat(managedCommentCallOrder).isEmpty();
        assertThat(commentText.get()).as("must not create a third workpad").isNull();
    }

    private JsonNode upsertWorkpadWithDestructiveOperations(TrelloHandoffToolHandler handler) {
        return handler.handle(
                configWithDestructiveOperations(),
                TestCards.card("card-1", "TRELLO-abc", "Ready for Codex"),
                json.createObjectNode()
                        .put("tool", TrelloHandoffToolHandler.UPSERT_WORKPAD)
                        .set("arguments", json.createObjectNode().put("text", "## Codex Workpad\n\nUpdated plan")));
    }

    private static String duplicateWorkpadActionsJson() {
        return """
                [
                  {
                    "id":"action-workpad",
                    "data":{"text":"## Codex Workpad\\n\\nVisible first"},
                    "date":"2026-05-05T00:00:00.000Z",
                    "memberCreator":{"fullName":"Codex"}
                  },
                  {
                    "id":"action-workpad-older",
                    "data":{"text":"## Codex Workpad\\n\\nVisible second"},
                    "date":"2026-05-04T00:00:00.000Z",
                    "memberCreator":{"fullName":"Codex"}
                  }
                ]
                """;
    }

    private static String tripleWorkpadActionsJson() {
        return """
                [
                  {
                    "id":"action-workpad",
                    "data":{"text":"## Codex Workpad\\n\\nVisible first"},
                    "date":"2026-05-05T00:00:00.000Z",
                    "memberCreator":{"fullName":"Codex"}
                  },
                  {
                    "id":"action-workpad-older",
                    "data":{"text":"## Codex Workpad\\n\\nVisible second"},
                    "date":"2026-05-04T00:00:00.000Z",
                    "memberCreator":{"fullName":"Codex"}
                  },
                  {
                    "id":"action-workpad-oldest",
                    "data":{"text":"## Codex Workpad\\n\\nVisible third"},
                    "date":"2026-05-03T00:00:00.000Z",
                    "memberCreator":{"fullName":"Codex"}
                  },
                  {
                    "id":"action-human-comment",
                    "data":{"text":"Looks good, shipping this."},
                    "date":"2026-05-02T00:00:00.000Z",
                    "memberCreator":{"fullName":"Reviewer"}
                  }
                ]
                """;
    }

    @Test
    void failsWorkpadUpsertWhenCardRefreshFailsWithoutCreatingDuplicate() {
        // given
        TrelloHandoffToolHandler handler = handler();
        cardStatus.set(500);

        // when
        var result = handler.handle(
                config(List.of("Review"), List.of()),
                TestCards.card("card-1", "TRELLO-abc", "Ready for Codex"),
                json.createObjectNode()
                        .put("tool", TrelloHandoffToolHandler.UPSERT_WORKPAD)
                        .set("arguments", json.createObjectNode().put("text", "Updated plan")));

        // then
        assertThat(result.path("success").asBoolean()).isFalse();
        assertThat(result.path("contentItems").get(0).path("text").asText()).contains("trello_workpad_refresh_failed");
        assertThat(commentText.get()).isNull();
        assertThat(updatedCommentText.get()).isNull();
    }

    @Test
    void failsWorkpadCreateWhenFetchedCommentWindowMayBeIncomplete() {
        // given
        TrelloHandoffToolHandler handler = handler();
        cardResponse.set(cardJson(commentActions(TrelloClient.WORKPAD_COMMENT_ACTION_LIMIT)));

        // when
        var result = handler.handle(
                config(List.of("Review"), List.of()),
                TestCards.card("card-1", "TRELLO-abc", "Ready for Codex"),
                json.createObjectNode()
                        .put("tool", TrelloHandoffToolHandler.UPSERT_WORKPAD)
                        .set("arguments", json.createObjectNode().put("text", "New plan")));

        // then
        assertThat(result.path("success").asBoolean()).isFalse();
        assertThat(result.path("contentItems").get(0).path("text").asText())
                .contains("trello_workpad_comment_window_incomplete");
        assertThat(commentText.get()).isNull();
        assertThat(updatedCommentText.get()).isNull();
    }

    @Test
    void createsSanitizedCodexUsageSectionInAuthoritativeWorkpad() {
        // given
        TrelloHandoffToolHandler handler = handler();
        Instant nextAttempt = Instant.parse("2026-07-10T13:00:00Z");
        String section =
                CodexUsageWorkpadSection.paused("Usage\n[account](https://example.invalid/private)\u0000", nextAttempt);

        // when
        boolean updated = handler.updateCodexUsageSection(config(List.of("Review"), List.of()), "card-1", section);

        // then
        assertThat(updated).isTrue();
        assertThat(commentText.get())
                .startsWith(TrelloHandoffToolHandler.WORKPAD_MARKER)
                .contains(
                        CodexUsageWorkpadSection.START_MARKER,
                        "Usage \\[account\\]\\(https://example.invalid/private\\)",
                        "2026-07-10T13:00:00Z",
                        CodexUsageWorkpadSection.END_MARKER)
                .doesNotContain("\u0000");
    }

    @Test
    void codexUsageAndBlockerRecheckManagedFamiliesUpdateIndependently() {
        // given
        TrelloClient client = mock();
        TrelloHandoffToolHandler handler = new TrelloHandoffToolHandler(json, client);
        EffectiveConfig config = config(List.of("Review"), List.of());
        Instant commentTime = Instant.parse("2026-07-10T12:00:00Z");
        String originalWorkpad = CodexUsageWorkpadSection.upsert(
                "## Codex Workpad\n\n- Agent plan: keep this.",
                CodexUsageWorkpadSection.paused("Usage is unavailable.", Instant.parse("2026-07-10T13:00:00Z")));
        String recheckingSection = CodexUsageWorkpadSection.rechecking("Usage is unavailable.");
        String recheckingWorkpad = CodexUsageWorkpadSection.upsert(originalWorkpad, recheckingSection);
        Card.Comment managedBlockerRecheck = new Card.Comment(
                BLOCKER_RECHECK_ACTION_ID,
                managedRecheckText(CHECKING_STATUS, BLOCKER_ACTION_ID, "abc"),
                "Symphony",
                commentTime.minusSeconds(1));
        Card.Comment blocker = new Card.Comment(
                BLOCKER_ACTION_ID, "Blocked: repository issue is unavailable.", "Codex", commentTime.minusSeconds(2));
        Card beforeUsageUpdate = TestCards.cardWithComments(
                "card-1",
                "TRELLO-abc",
                "Ready for Codex",
                List.of(
                        new Card.Comment("action-workpad", originalWorkpad, "Codex", commentTime),
                        managedBlockerRecheck,
                        blocker));
        Card beforeBlockerUpdate = TestCards.cardWithComments(
                "card-1",
                "TRELLO-abc",
                "Ready for Codex",
                List.of(
                        new Card.Comment("action-workpad", recheckingWorkpad, "Codex", commentTime),
                        managedBlockerRecheck,
                        blocker));
        when(client.fetchCardStateForWorkpad(any(), eq("card-1")))
                .thenReturn(
                        new CardLookupResult.Found(beforeUsageUpdate), new CardLookupResult.Found(beforeBlockerUpdate));
        List<Card.Comment> writes = new CopyOnWriteArrayList<>();
        when(client.updateComment(any(), anyString(), anyString())).thenAnswer(invocation -> {
            String actionId = invocation.getArgument(1);
            String text = invocation.getArgument(2);
            writes.add(new Card.Comment(actionId, text, "Symphony", commentTime));
            return Map.of("id", actionId);
        });

        // when
        boolean usageUpdated = handler.updateCodexUsageSection(config, "card-1", recheckingSection);
        JsonNode blockerUpdated = updateBlockerRecheckStatus(handler, "resumed", config);

        // then
        assertThat(usageUpdated).isTrue();
        assertThat(blockerUpdated.path("success").asBoolean()).isTrue();
        assertThat(blockerUpdated.path("contentItems").get(0).path("text").asText())
                .contains("resumed_work_confirmed");
        assertThat(writes).extracting(Card.Comment::id).containsExactly("action-workpad", BLOCKER_RECHECK_ACTION_ID);
        assertThat(writes.get(0).text())
                .isEqualTo(recheckingWorkpad)
                .contains("- Agent plan: keep this.", CodexUsageWorkpadSection.START_MARKER, recheckingSection)
                .doesNotContain(
                        TrelloHandoffToolHandler.BLOCKER_RECHECK_FOOTER_PREFIX,
                        TrelloHandoffToolHandler.BLOCKER_RECHECK_FOOTER_SUFFIX);
        assertThat(writes.get(1).text())
                .contains(
                        RESUMED_STATUS_PREFIX,
                        TrelloHandoffToolHandler.BLOCKER_RECHECK_FOOTER_PREFIX,
                        "#comment-" + BLOCKER_ACTION_ID,
                        TrelloHandoffToolHandler.BLOCKER_RECHECK_FOOTER_SUFFIX)
                .doesNotContain(
                        CodexUsageWorkpadSection.START_MARKER,
                        CodexUsageWorkpadSection.END_MARKER,
                        "- Agent plan: keep this.");
    }

    @Test
    void boundsTheSanitizedCodexUsageMessage() {
        // given
        TrelloHandoffToolHandler handler = handler();
        String oversizedMessage = "x".repeat(600);
        String markdownHeavySection =
                CodexUsageWorkpadSection.paused("[".repeat(600), Instant.parse("2026-07-10T13:00:00Z"));

        // when
        boolean updated = handler.updateCodexUsageSection(
                config(List.of("Review"), List.of()),
                "card-1",
                CodexUsageWorkpadSection.paused(oversizedMessage, Instant.parse("2026-07-10T13:00:00Z")));

        // then
        assertThat(updated).isTrue();
        assertThat(commentText.get())
                .contains("- Message: " + "x".repeat(497) + "...\n")
                .doesNotContain("x".repeat(498));
        String markdownHeavyMessage = markdownHeavySection
                .lines()
                .filter(line -> line.startsWith("- Message: "))
                .findFirst()
                .orElseThrow()
                .substring("- Message: ".length());
        assertThat(markdownHeavyMessage.codePointCount(0, markdownHeavyMessage.length()))
                .isLessThanOrEqualTo(500);
        assertThat(markdownHeavyMessage).endsWith("...");
    }

    @Test
    void normalizesUnicodeSeparatorsWhitespaceAndFormatControlsInUsageMessage() {
        // given
        TrelloHandoffToolHandler handler = handler();
        String unsafeMessage =
                "alpha\u2028beta\u2029gamma\u00a0delta\u202eevil\u2066tail\u2069 &#x202e;entity &NewLine;break &lrm;mark";

        // when
        boolean updated = handler.updateCodexUsageSection(
                config(List.of("Review"), List.of()),
                "card-1",
                CodexUsageWorkpadSection.paused(unsafeMessage, Instant.parse("2026-07-10T13:00:00Z")));

        // then
        assertThat(updated).isTrue();
        assertThat(commentText.get())
                .contains("- Message: alpha beta gamma delta evil tail \\&#x202e;entity \\&NewLine;break \\&lrm;mark\n")
                .doesNotContain("\u2028", "\u2029", "\u202e", "\u2066", "\u2069");
    }

    @Test
    void preservesReplacesAndRemovesOnlyTheManagedCodexUsageSectionIdempotently() throws Exception {
        // given
        TrelloHandoffToolHandler handler = handler();
        EffectiveConfig config = config(List.of("Review"), List.of());
        String original = "## Codex Workpad\n\n- User plan: keep this.";
        cardResponse.set(cardJsonWithWorkpad(original));
        String paused = CodexUsageWorkpadSection.paused("Usage is unavailable.", Instant.parse("2026-07-10T13:00:00Z"));

        // when
        boolean created = handler.updateCodexUsageSection(config, "card-1", paused);
        String pausedWorkpad = updatedCommentText.get();
        cardResponse.set(cardJsonWithWorkpad(pausedWorkpad));
        updatedCommentText.set(null);
        boolean unchanged = handler.updateCodexUsageSection(config, "card-1", paused);
        boolean skippedIdenticalWrite = updatedCommentText.get() == null;
        boolean rechecking = handler.updateCodexUsageSection(
                config, "card-1", CodexUsageWorkpadSection.rechecking("Usage is unavailable."));
        String recheckingWorkpad = updatedCommentText.get();
        cardResponse.set(cardJsonWithWorkpad(recheckingWorkpad));
        boolean removed = handler.updateCodexUsageSection(config, "card-1", null);

        // then
        assertThat(created).isTrue();
        assertThat(pausedWorkpad)
                .contains("- User plan: keep this.", "Paused after Codex reported a usage limit.")
                .containsOnlyOnce(CodexUsageWorkpadSection.START_MARKER);
        assertThat(unchanged).isTrue();
        assertThat(skippedIdenticalWrite).isTrue();
        assertThat(rechecking).isTrue();
        assertThat(recheckingWorkpad)
                .contains("- User plan: keep this.", "Rechecking Codex usage availability.")
                .doesNotContain("Paused after Codex reported a usage limit.")
                .containsOnlyOnce(CodexUsageWorkpadSection.START_MARKER);
        assertThat(removed).isTrue();
        assertThat(updatedCommentText.get()).isEqualTo(original);
    }

    @Test
    void serializesAgentAndOrchestratorWorkpadReadModifyWriteForSameCard() throws Exception {
        // given
        TrelloClient client = mock();
        TrelloHandoffToolHandler handler = new TrelloHandoffToolHandler(json, client);
        EffectiveConfig config = config(List.of("Review"), List.of());
        AtomicReference<Card> currentCard =
                new AtomicReference<>(cardWithSingleWorkpad("## Codex Workpad\n\nOld agent plan"));
        AtomicInteger fetches = new AtomicInteger();
        CountDownLatch secondFetchEntered = new CountDownLatch(1);
        when(client.fetchCardStateForWorkpad(any(), eq("card-1"))).thenAnswer(invocation -> {
            if (fetches.incrementAndGet() == 2) {
                secondFetchEntered.countDown();
            }
            return new CardLookupResult.Found(currentCard.get());
        });

        CountDownLatch firstUpdateEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstUpdate = new CountDownLatch(1);
        AtomicBoolean firstUpdate = new AtomicBoolean(true);
        List<String> writes = new CopyOnWriteArrayList<>();
        when(client.updateComment(any(), eq("action-workpad"), anyString())).thenAnswer(invocation -> {
            String value = invocation.getArgument(2);
            if (firstUpdate.compareAndSet(true, false)) {
                firstUpdateEntered.countDown();
                assertThat(releaseFirstUpdate.await(5, TimeUnit.SECONDS)).isTrue();
            }
            writes.add(value);
            currentCard.set(cardWithSingleWorkpad(value));
            return Map.of("id", "action-workpad");
        });

        String agentText = "## Codex Workpad\n\nLatest agent body";
        String paused = CodexUsageWorkpadSection.paused("Usage is unavailable.", Instant.parse("2026-07-10T13:00:00Z"));
        CompletableFuture<JsonNode> agentUpdate = CompletableFuture.supplyAsync(() -> handler.handle(
                config,
                TestCards.card("card-1", "TRELLO-abc", "Ready for Codex"),
                json.createObjectNode()
                        .put("tool", TrelloHandoffToolHandler.UPSERT_WORKPAD)
                        .set("arguments", json.createObjectNode().put("text", agentText))));
        assertThat(firstUpdateEntered.await(5, TimeUnit.SECONDS)).isTrue();

        // when
        CompletableFuture<Boolean> usageUpdate =
                CompletableFuture.supplyAsync(() -> handler.updateCodexUsageSection(config, "card-1", paused));
        boolean secondFetchOvertookFirstWrite = secondFetchEntered.await(250, TimeUnit.MILLISECONDS);
        releaseFirstUpdate.countDown();

        // then
        assertThat(agentUpdate.get(5, TimeUnit.SECONDS).path("success").asBoolean())
                .isTrue();
        assertThat(usageUpdate.get(5, TimeUnit.SECONDS)).isTrue();
        assertThat(secondFetchOvertookFirstWrite)
                .as("the second read must wait until the first read-modify-write has completed")
                .isFalse();
        assertThat(fetches).hasValue(2);
        assertThat(writes).hasSize(2);
        assertThat(currentCard.get().comments().getFirst().text())
                .contains("Latest agent body", paused)
                .containsOnlyOnce(CodexUsageWorkpadSection.START_MARKER);
    }

    @MethodSource("malformedManagedUsageSections")
    @ParameterizedTest(name = "{0}")
    void refusesMalformedManagedUsageSectionsWithoutLosingOrAccumulatingHumanNotes(
            String scenario, String malformedWorkpad) throws Exception {
        // given
        TrelloHandoffToolHandler handler = handler();
        EffectiveConfig config = config(List.of("Review"), List.of());
        String paused = CodexUsageWorkpadSection.paused("Usage is unavailable.", Instant.parse("2026-07-10T13:00:00Z"));
        cardResponse.set(cardJsonWithWorkpad(malformedWorkpad));

        // when
        String removed = CodexUsageWorkpadSection.remove(malformedWorkpad);
        String upserted = CodexUsageWorkpadSection.upsert(malformedWorkpad, paused);
        JsonNode agentUpsert = upsertWorkpad(handler, "## Codex Workpad\n\nReplacement agent plan");
        boolean pauseUpdated = handler.updateCodexUsageSection(config, "card-1", paused);
        boolean cleanupUpdated = handler.updateCodexUsageSection(config, "card-1", null);

        // then
        assertThat(scenario).isNotBlank();
        assertThat(removed).isEqualTo(malformedWorkpad);
        assertThat(upserted).isEqualTo(malformedWorkpad);
        assertThat(agentUpsert.path("success").asBoolean()).isFalse();
        assertThat(agentUpsert.path("contentItems").get(0).path("text").asText())
                .contains("trello_workpad_managed_section_malformed");
        assertThat(pauseUpdated).isFalse();
        assertThat(cleanupUpdated).isFalse();
        assertThat(updatedCommentText.get()).isNull();
        assertThat(commentText.get()).isNull();
        assertThat(managedCommentCallOrder).isEmpty();
        assertThat(malformedWorkpad).contains("Human note after malformed section.");
    }

    @Test
    void repeatedUsageUpdatesWithDuplicateWorkpadsKeepOneBoundedCleanupNotice() throws Exception {
        // given
        TrelloHandoffToolHandler handler = handler();
        EffectiveConfig config = config(List.of("Review"), List.of());
        String original = "## Codex Workpad\n\n- Human plan: keep this.";
        cardResponse.set(cardJsonWithDuplicateWorkpads(original));

        // when
        boolean paused = handler.updateCodexUsageSection(
                config,
                "card-1",
                CodexUsageWorkpadSection.paused("Usage is unavailable.", Instant.parse("2026-07-10T13:00:00Z")));
        String pausedWorkpad = updatedCommentText.get();
        cardResponse.set(cardJsonWithDuplicateWorkpads(pausedWorkpad));

        boolean rechecking = handler.updateCodexUsageSection(
                config, "card-1", CodexUsageWorkpadSection.rechecking("Usage is unavailable."));
        String recheckingWorkpad = updatedCommentText.get();
        cardResponse.set(cardJsonWithDuplicateWorkpads(recheckingWorkpad));

        boolean cleaned = handler.updateCodexUsageSection(config, "card-1", null);
        String cleanedWorkpad = updatedCommentText.get();

        // then
        assertThat(paused).isTrue();
        assertThat(rechecking).isTrue();
        assertThat(cleaned).isTrue();
        assertThat(List.of(pausedWorkpad, recheckingWorkpad, cleanedWorkpad)).allSatisfy(workpad -> {
            assertThat(workpad)
                    .contains("- Human plan: keep this.")
                    .containsOnlyOnce(TrelloHandoffToolHandler.DUPLICATE_WORKPADS_NOTE_PREFIX);
            assertThat(workpad.length()).isLessThan(2_000);
        });
        assertThat(pausedWorkpad).containsOnlyOnce(CodexUsageWorkpadSection.START_MARKER);
        assertThat(recheckingWorkpad).containsOnlyOnce(CodexUsageWorkpadSection.START_MARKER);
        assertThat(cleanedWorkpad)
                .doesNotContain(CodexUsageWorkpadSection.START_MARKER, CodexUsageWorkpadSection.END_MARKER);
    }

    @Test
    void refusesCodexUsageWorkpadCreateWhenCommentWindowMayBeIncomplete() {
        // given
        TrelloHandoffToolHandler handler = handler();
        cardResponse.set(cardJson(commentActions(TrelloClient.WORKPAD_COMMENT_ACTION_LIMIT)));

        // when
        boolean updated = handler.updateCodexUsageSection(
                config(List.of("Review"), List.of()),
                "card-1",
                CodexUsageWorkpadSection.paused("Usage is unavailable.", Instant.parse("2026-07-10T13:00:00Z")));

        // then
        assertThat(updated).isFalse();
        assertThat(commentText.get()).isNull();
        assertThat(updatedCommentText.get()).isNull();
    }

    @Test
    void refusesCodexUsageCleanupWhenFullWindowMayHideTheEntireWorkpad() {
        // given
        TrelloHandoffToolHandler handler = handler();
        cardResponse.set(cardJson(commentActions(TrelloClient.WORKPAD_COMMENT_ACTION_LIMIT)));

        // when
        boolean updated = handler.updateCodexUsageSection(config(List.of("Review"), List.of()), "card-1", null);

        // then
        assertThat(updated).isFalse();
        assertThat(commentText.get()).isNull();
        assertThat(updatedCommentText.get()).isNull();
        assertThat(managedCommentCallOrder).isEmpty();
    }

    @Test
    void refusesCodexUsageSectionInsertWhenCommentWindowMayHideAnOlderOwner() {
        // given
        TrelloHandoffToolHandler handler = handler();
        cardResponse.set(cardJson(fullCommentWindowWithWorkpad()));

        // when
        boolean updated = handler.updateCodexUsageSection(
                config(List.of("Review"), List.of()),
                "card-1",
                CodexUsageWorkpadSection.paused("Usage is unavailable.", Instant.parse("2026-07-10T13:00:00Z")));

        // then
        assertThat(updated).isFalse();
        assertThat(commentText.get()).isNull();
        assertThat(updatedCommentText.get()).isNull();
        assertThat(managedCommentCallOrder).isEmpty();
    }

    @Test
    void refusesCodexUsageSectionCleanupWhenCommentWindowMayHideAnOlderOwner() {
        // given
        TrelloHandoffToolHandler handler = handler();
        cardResponse.set(cardJson(fullCommentWindowWithWorkpad()));

        // when
        boolean updated = handler.updateCodexUsageSection(config(List.of("Review"), List.of()), "card-1", null);

        // then
        assertThat(updated).isFalse();
        assertThat(commentText.get()).isNull();
        assertThat(updatedCommentText.get()).isNull();
        assertThat(managedCommentCallOrder).isEmpty();
    }

    @Test
    void skipsCodexUsageWorkpadIOMutationsWhenWritesAreDisabled() {
        // given
        TrelloHandoffToolHandler handler = handler();

        // when
        boolean updated = handler.updateCodexUsageSection(
                configWithWrites(false),
                "card-1",
                CodexUsageWorkpadSection.paused("Usage is unavailable.", Instant.parse("2026-07-10T13:00:00Z")));

        // then
        assertThat(updated).isFalse();
        assertThat(commentText.get()).isNull();
        assertThat(updatedCommentText.get()).isNull();
    }

    @Test
    void skipsCodexUsageWorkpadIOMutationsWhenCommentWritesAreDisabled() {
        // given
        TrelloHandoffToolHandler handler = handler();

        // when
        boolean updated = handler.updateCodexUsageSection(
                configWithComments(false),
                "card-1",
                CodexUsageWorkpadSection.paused("Usage is unavailable.", Instant.parse("2026-07-10T13:00:00Z")));

        // then
        assertThat(updated).isFalse();
        assertThat(commentText.get()).isNull();
        assertThat(updatedCommentText.get()).isNull();
    }

    @Test
    void movesCurrentCardToAllowedListName() {
        // given
        TrelloHandoffToolHandler handler = handler();

        // when
        var result = handler.handle(
                config(List.of("Review"), List.of()),
                TestCards.card("card-1", "TRELLO-abc", "Ready for Codex"),
                json.createObjectNode()
                        .put("tool", TrelloHandoffToolHandler.MOVE_CURRENT_CARD)
                        .set("arguments", json.createObjectNode().put("list_name", "Review")));

        // then
        assertThat(result.path("success").asBoolean()).isTrue();
        assertThat(movedToListId.get()).isEqualTo("list-review");
    }

    @Test
    void rejectsAmbiguousListNameMoveWithoutCallingTrelloWriteEndpoint() {
        // given
        replaceBoardLists(
                trelloList("list-ready", "Ready for Codex", 1),
                trelloList("list-duplicate-review", "Review", 2),
                trelloList("list-review", "Review", 3));
        TrelloHandoffToolHandler handler = handler();

        // when
        var result = handler.handle(
                config(List.of("Review"), List.of()),
                TestCards.card("card-1", "TRELLO-abc", "Ready for Codex"),
                json.createObjectNode()
                        .put("tool", TrelloHandoffToolHandler.MOVE_CURRENT_CARD)
                        .set("arguments", json.createObjectNode().put("list_name", "Review")));

        // then
        assertThat(result.path("success").asBoolean()).isFalse();
        assertThat(result.path("contentItems").get(0).path("text").asText())
                .contains("trello_move_not_allowed", "matches multiple open Trello lists", "list_id");
        assertThat(movedToListId.get()).isNull();
    }

    @Test
    void movesCurrentCardToAllowedListIdWhenNamesAreNotConfigured() {
        // given
        TrelloHandoffToolHandler handler = handler();

        // when
        var result = moveToListId(handler, "list-review");

        // then
        assertThat(result.path("success").asBoolean()).isTrue();
        assertThat(movedToListId.get()).isEqualTo("list-review");
    }

    @Test
    void movesCurrentCardToAllowedListIdWhenNamesAreDuplicated() {
        // given
        replaceBoardLists(
                trelloList("list-ready", "Ready for Codex", 1),
                trelloList("list-duplicate-review", "Review", 2),
                trelloList("list-review", "Review", 3));
        TrelloHandoffToolHandler handler = handler();

        // when
        var result = moveToListId(handler, "list-review");

        // then
        assertThat(result.path("success").asBoolean()).isTrue();
        assertThat(movedToListId.get()).isEqualTo("list-review");
    }

    @Test
    void rejectsListIdMoveWhenOnlyDuplicateListNameIsAllowed() {
        // given
        replaceBoardLists(
                trelloList("list-ready", "Ready for Codex", 1),
                trelloList("list-duplicate-review", "Review", 2),
                trelloList("list-review", "Review", 3));
        TrelloHandoffToolHandler handler = handler();

        // when
        var result = handler.handle(
                config(List.of("Review"), List.of()),
                TestCards.card("card-1", "TRELLO-abc", "Ready for Codex"),
                json.createObjectNode()
                        .put("tool", TrelloHandoffToolHandler.MOVE_CURRENT_CARD)
                        .set("arguments", json.createObjectNode().put("list_id", "list-review")));

        // then
        assertThat(result.path("success").asBoolean()).isFalse();
        assertThat(result.path("contentItems").get(0).path("text").asText())
                .contains("trello_move_not_allowed", "matches multiple open Trello lists", "exact list_id");
        assertThat(movedToListId.get()).isNull();
    }

    @Test
    void rejectsMoveOutsideAllowlistWithoutCallingTrelloWriteEndpoint() {
        // given
        TrelloHandoffToolHandler handler = handler();

        // when
        var result = handler.handle(
                config(List.of("Done"), List.of()),
                TestCards.card("card-1", "TRELLO-abc", "Ready for Codex"),
                json.createObjectNode()
                        .put("tool", TrelloHandoffToolHandler.MOVE_CURRENT_CARD)
                        .set("arguments", json.createObjectNode().put("list_name", "Review")));

        // then
        assertThat(result.path("success").asBoolean()).isFalse();
        assertThat(result.path("contentItems").get(0).path("text").asText()).contains("trello_move_not_allowed");
        assertThat(movedToListId.get()).isNull();
    }

    @Test
    void withholdsToolsWhenWritesAreDisabled() {
        // given
        TrelloHandoffToolHandler handler = handler();

        // when
        var tools = handler.toolSpecs(configWithWrites(false));

        // then
        assertThat(tools).isEmpty();
    }

    private TrelloHandoffToolHandler handler() {
        return new TrelloHandoffToolHandler(json, new TrelloClient(json));
    }

    private JsonNode updateBlockerRecheckStatus(TrelloHandoffToolHandler handler, String status) {
        return updateBlockerRecheckStatus(handler, status, config(List.of("Review"), List.of()));
    }

    private JsonNode updateBlockerRecheckStatus(
            TrelloHandoffToolHandler handler, String status, EffectiveConfig effectiveConfig) {
        return handler.handle(
                effectiveConfig,
                TestCards.card("card-1", "TRELLO-abc", "Ready for Codex"),
                json.createObjectNode()
                        .put("tool", TrelloHandoffToolHandler.UPDATE_BLOCKER_RECHECK_STATUS)
                        .set("arguments", json.createObjectNode().put("status", status)));
    }

    private JsonNode moveToListId(TrelloHandoffToolHandler handler, String listId) {
        return handler.handle(
                config(List.of(), List.of(listId)),
                TestCards.card("card-1", "TRELLO-abc", "Ready for Codex"),
                json.createObjectNode()
                        .put("tool", TrelloHandoffToolHandler.MOVE_CURRENT_CARD)
                        .set("arguments", json.createObjectNode().put("list_id", listId)));
    }

    private void replaceBoardLists(FakeTrelloServer.TrelloListJson... lists) {
        trello.remove("/1/boards/board-1/lists")
                .on("/1/boards/board-1/lists", exchange -> respond(exchange, listsJson(lists)));
    }

    private JsonNode upsertChecklistItem(TrelloHandoffToolHandler handler, EffectiveConfig config) {
        return handler.handle(
                config,
                TestCards.card("card-1", "TRELLO-abc", "Ready for Codex"),
                json.createObjectNode()
                        .put("tool", TrelloHandoffToolHandler.UPSERT_CHECKLIST_ITEM)
                        .set(
                                "arguments",
                                json.createObjectNode()
                                        .put("checklist_name", "Release tasks")
                                        .put("item_name", "Publish release")
                                        .put("complete", true)));
    }

    private JsonNode upsertWorkpad(TrelloHandoffToolHandler handler) {
        return upsertWorkpad(handler, "## Codex Workpad\n\nUpdated plan");
    }

    private JsonNode upsertWorkpad(TrelloHandoffToolHandler handler, String text) {
        return handler.handle(
                config(List.of("Review"), List.of()),
                TestCards.card("card-1", "TRELLO-abc", "Ready for Codex"),
                json.createObjectNode()
                        .put("tool", TrelloHandoffToolHandler.UPSERT_WORKPAD)
                        .set("arguments", json.createObjectNode().put("text", text)));
    }

    private EffectiveConfig config(List<String> allowedMoveListNames, List<String> allowedMoveListIds) {
        return config(true, allowedMoveListNames, allowedMoveListIds, false);
    }

    private EffectiveConfig configWithWrites(boolean allowWrites) {
        return config(allowWrites, List.of("Review"), List.of(), false);
    }

    private EffectiveConfig configWithComments(boolean allowComments) {
        return config(true, List.of("Review"), List.of(), allowComments, false, false, false);
    }

    private EffectiveConfig configWithChecklistAndAttachmentWrites(
            boolean allowChecklists, boolean allowUrlAttachments) {
        return config(true, List.of("Review"), List.of(), true, allowChecklists, allowUrlAttachments, false);
    }

    private EffectiveConfig configWithDestructiveOperations() {
        return config(true, List.of("Review"), List.of(), true);
    }

    private static Stream<String> managedFamilyHeadings() {
        return Stream.of(
                TrelloHandoffToolHandler.WORKPAD_MARKER,
                TrelloClient.PREREQUISITE_STATUS_COMMENT_MARKER,
                TrelloClient.WAITING_COMMENT_MARKER);
    }

    private static Stream<Arguments> nonStringToolStringArguments() {
        return Stream.of(
                Arguments.of(
                        "comment text object",
                        TrelloHandoffToolHandler.ADD_COMMENT,
                        objectArgument("text", JsonNodeFactory.instance.objectNode()),
                        "text"),
                Arguments.of(
                        "workpad text array",
                        TrelloHandoffToolHandler.UPSERT_WORKPAD,
                        objectArgument(
                                "text", JsonNodeFactory.instance.arrayNode().add("Plan")),
                        "text"),
                Arguments.of(
                        "blocker recheck status object",
                        TrelloHandoffToolHandler.UPDATE_BLOCKER_RECHECK_STATUS,
                        objectArgument("status", JsonNodeFactory.instance.objectNode()),
                        "status"),
                Arguments.of(
                        "checklist name number",
                        TrelloHandoffToolHandler.UPSERT_CHECKLIST_ITEM,
                        checklistArguments(
                                JsonNodeFactory.instance.numberNode(42),
                                JsonNodeFactory.instance.textNode("Publish release")),
                        "checklist_name"),
                Arguments.of(
                        "checklist item object",
                        TrelloHandoffToolHandler.UPSERT_CHECKLIST_ITEM,
                        checklistArguments(
                                JsonNodeFactory.instance.textNode("Release tasks"),
                                JsonNodeFactory.instance.objectNode()),
                        "item_name"),
                Arguments.of(
                        "URL attachment URL number",
                        TrelloHandoffToolHandler.ADD_URL_ATTACHMENT,
                        objectArgument("url", JsonNodeFactory.instance.numberNode(12)),
                        "url"),
                Arguments.of(
                        "URL attachment name object",
                        TrelloHandoffToolHandler.ADD_URL_ATTACHMENT,
                        urlAttachmentArguments(JsonNodeFactory.instance.objectNode()),
                        "name"));
    }

    private static Stream<Arguments> malformedManagedUsageSections() {
        String heading = "## Codex Workpad\n\n- Human plan: keep this.\n\n";
        String humanNote = "\n\n- Human note after malformed section.";
        return Stream.of(
                Arguments.of(
                        "unmatched start marker",
                        heading + CodexUsageWorkpadSection.START_MARKER + "\nOld managed text" + humanNote),
                Arguments.of(
                        "unmatched end marker",
                        heading + "Old managed text\n" + CodexUsageWorkpadSection.END_MARKER + humanNote),
                Arguments.of(
                        "nested start marker",
                        heading
                                + CodexUsageWorkpadSection.START_MARKER
                                + "\nOld managed text\n"
                                + CodexUsageWorkpadSection.START_MARKER
                                + "\nNested managed text\n"
                                + CodexUsageWorkpadSection.END_MARKER
                                + "\nOuter managed text\n"
                                + CodexUsageWorkpadSection.END_MARKER
                                + humanNote),
                Arguments.of(
                        "multiple balanced managed sections",
                        heading
                                + CodexUsageWorkpadSection.START_MARKER
                                + "\nFirst managed text\n"
                                + CodexUsageWorkpadSection.END_MARKER
                                + "\n\n"
                                + CodexUsageWorkpadSection.START_MARKER
                                + "\nSecond managed text\n"
                                + CodexUsageWorkpadSection.END_MARKER
                                + humanNote));
    }

    private static ObjectNode objectArgument(String key, JsonNode value) {
        ObjectNode arguments = JsonNodeFactory.instance.objectNode();
        arguments.set(key, value);
        return arguments;
    }

    private static ObjectNode checklistArguments(JsonNode checklistName, JsonNode itemName) {
        ObjectNode arguments = JsonNodeFactory.instance.objectNode();
        arguments.set("checklist_name", checklistName);
        arguments.set("item_name", itemName);
        arguments.put("complete", true);
        return arguments;
    }

    private static ObjectNode urlAttachmentArguments(JsonNode name) {
        ObjectNode arguments = JsonNodeFactory.instance.objectNode();
        arguments.put("url", "https://github.com/example/project/pull/12");
        arguments.set("name", name);
        return arguments;
    }

    private ObjectNode addUrlAttachment(TrelloHandoffToolHandler handler, String url) {
        return handler.handle(
                config(List.of("Review"), List.of()),
                TestCards.card("card-1", "TRELLO-abc", "Ready for Codex"),
                json.createObjectNode()
                        .put("tool", TrelloHandoffToolHandler.ADD_URL_ATTACHMENT)
                        .set("arguments", json.createObjectNode().put("url", url)));
    }

    private void assertUnsafeUrlAttachmentRejected(ObjectNode result) {
        assertThat(result.path("success").asBoolean()).isFalse();
        assertThat(result.path("contentItems").get(0).path("text").asText())
                .contains("invalid_url_attachment")
                .doesNotContain("token")
                .doesNotContain("secret");
        assertThat(attachmentUrl.get()).isNull();
    }

    private EffectiveConfig config(
            boolean allowWrites,
            List<String> allowedMoveListNames,
            List<String> allowedMoveListIds,
            boolean allowDestructiveOperations) {
        return config(
                allowWrites, allowedMoveListNames, allowedMoveListIds, true, true, true, allowDestructiveOperations);
    }

    private EffectiveConfig config(
            boolean allowWrites,
            List<String> allowedMoveListNames,
            List<String> allowedMoveListIds,
            boolean allowComments,
            boolean allowChecklists,
            boolean allowUrlAttachments,
            boolean allowDestructiveOperations) {
        return new ConfigResolver()
                .resolve(new WorkflowDefinition(
                        tempDir.resolve("WORKFLOW.md"),
                        Map.of(
                                "tracker",
                                Map.of(
                                        "kind",
                                        "trello",
                                        "endpoint",
                                        trello.endpoint(),
                                        "api_key",
                                        "key",
                                        "api_token",
                                        "token",
                                        "board_id",
                                        "board-1"),
                                "trello_tools",
                                Map.of(
                                        "enabled",
                                        true,
                                        "allow_writes",
                                        allowWrites,
                                        "allow_comments",
                                        allowComments,
                                        "allowed_move_list_names",
                                        allowedMoveListNames,
                                        "allowed_move_list_ids",
                                        allowedMoveListIds,
                                        "allow_checklists",
                                        allowChecklists,
                                        "allow_url_attachments",
                                        allowUrlAttachments,
                                        "allow_destructive_operations",
                                        allowDestructiveOperations)),
                        ""))
                .withResolvedBoardId("board-1");
    }

    private void assertNoManagedCommentMutation() {
        assertThat(commentText.get()).isNull();
        assertThat(updatedCommentText.get()).isNull();
        assertThat(deletedActionIds).isEmpty();
        assertThat(movedToListId.get()).isNull();
    }

    private String cardResponseForRequestedFields(HttpExchange exchange) {
        String response = cardResponse.get();
        String actionFields = query(exchange).getOrDefault("action_fields", "");
        if (preservingEmptyFields(actionFields).contains("id")) {
            return response;
        }
        return response.replace("\"id\":\"action-workpad\",", "");
    }

    private static String cardJson(String actionsJson) {
        return cardJson("Implement feature", actionsJson);
    }

    private static String cardJson(String title, String actionsJson) {
        return """
                {
                  "id":"card-1",
                  "name":"%s",
                  "desc":"Description",
                  "idList":"list-ready",
                  "idBoard":"board-1",
                  "closed":false,
                  "idShort":1,
                  "shortLink":"SYNTH101",
                  "shortUrl":"https://trello.com/c/SYNTH101",
                  "url":"https://trello.com/c/SYNTH101",
                  "labels":[],
                  "dateLastActivity":"2026-05-05T00:00:00.000Z",
                  "pos":1,
                  "actions":%s
                }
                """
                .formatted(jsonEscaped(title), actionsJson);
    }

    private static String actionsJson(String... actions) {
        return "[\n" + String.join(",\n", actions) + "\n]";
    }

    private static String commentAction(String actionId, String text) {
        String id = actionId == null ? "" : "\"id\":\"" + jsonEscaped(actionId) + "\",";
        return """
                {
                  %s
                  "data":{"text":"%s"},
                  "date":"2026-05-05T00:00:00.000Z",
                  "memberCreator":{"fullName":"Codex"}
                }
                """
                .formatted(id, jsonEscaped(text));
    }

    private static String managedRecheckAction(String actionId, String visibleStatus, String blockerActionId) {
        return commentAction(actionId, managedRecheckText(visibleStatus, blockerActionId));
    }

    private static String managedRecheckText(String visibleStatus, String blockerActionId) {
        return managedRecheckText(visibleStatus, blockerActionId, "SYNTH101");
    }

    private static String managedRecheckText(String visibleStatus, String blockerActionId, String cardShortLink) {
        return visibleStatus
                + "\n\n"
                + TrelloHandoffToolHandler.BLOCKER_RECHECK_FOOTER_PREFIX
                + "https://trello.com/c/"
                + cardShortLink
                + "#comment-"
                + blockerActionId
                + TrelloHandoffToolHandler.BLOCKER_RECHECK_FOOTER_SUFFIX;
    }

    private static String resumedStatusLine(String comment) {
        int start = comment.indexOf(RESUMED_STATUS_PREFIX);
        if (start < 0) {
            throw new AssertionError("Managed comment has no resumed-work status line");
        }
        int end = comment.indexOf('\n', start);
        return end < 0 ? comment.substring(start) : comment.substring(start, end);
    }

    private static String fullCommentWindowWithBlocker() {
        String regularComments = commentActions(TrelloClient.WORKPAD_COMMENT_ACTION_LIMIT - 1);
        return "[\n"
                + commentAction(BLOCKER_ACTION_ID, "Blocked: repository mismatch")
                + ",\n"
                + regularComments.substring(1);
    }

    private static String fullCommentWindowWithWorkpad() {
        String regularComments = commentActions(TrelloClient.WORKPAD_COMMENT_ACTION_LIMIT - 1);
        return "[\n"
                + commentAction("action-workpad", "## Codex Workpad\n\nVisible plan")
                + ",\n"
                + regularComments.substring(1);
    }

    private String cardJsonWithWorkpad(String text) throws Exception {
        return cardJson("[{\"id\":\"action-workpad\",\"data\":{\"text\":"
                + json.writeValueAsString(text)
                + "},\"date\":\"2026-05-05T00:00:00.000Z\",\"memberCreator\":{\"fullName\":\"Codex\"}}]");
    }

    private String cardJsonWithDuplicateWorkpads(String primaryText) throws Exception {
        return cardJsonWithTwoWorkpads(primaryText, "## Codex Workpad\n\nOlder duplicate");
    }

    private String cardJsonWithTwoWorkpads(String primaryText, String olderText) throws Exception {
        return cardJson("[{\"id\":\"action-workpad\",\"data\":{\"text\":"
                + json.writeValueAsString(primaryText)
                + "},\"date\":\"2026-05-05T00:00:00.000Z\",\"memberCreator\":{\"fullName\":\"Codex\"}},"
                + "{\"id\":\"action-workpad-older\",\"data\":{\"text\":"
                + json.writeValueAsString(olderText)
                + "},\"date\":\"2026-05-04T00:00:00.000Z\",\"memberCreator\":{\"fullName\":\"Codex\"}}]");
    }

    private static Card cardWithSingleWorkpad(String text) {
        return TestCards.cardWithComments(
                "card-1",
                "TRELLO-abc",
                "Ready for Codex",
                List.of(new Card.Comment("action-workpad", text, "Codex", Instant.parse("2026-05-05T00:00:00Z"))));
    }

    private static String commentActions(int count) {
        return IntStream.range(0, count)
                .mapToObj(index ->
                        """
                        {
                          "id":"action-%d",
                          "data":{"text":"Regular comment %d"},
                          "date":"2026-05-05T00:00:00.000Z",
                          "memberCreator":{"fullName":"Codex"}
                        }
                        """
                                .formatted(index, index))
                .collect(Collectors.joining(",", "[", "]"));
    }
}
