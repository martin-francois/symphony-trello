package ch.fmartin.symphony.trello.agent;

import static ch.fmartin.symphony.trello.TestHttpExchange.query;
import static ch.fmartin.symphony.trello.TestHttpExchange.respond;
import static org.assertj.core.api.Assertions.assertThat;

import ch.fmartin.symphony.trello.TestCards;
import ch.fmartin.symphony.trello.config.ConfigResolver;
import ch.fmartin.symphony.trello.config.EffectiveConfig;
import ch.fmartin.symphony.trello.tracker.TrelloClient;
import ch.fmartin.symphony.trello.workflow.WorkflowDefinition;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class TrelloHandoffToolHandlerTest {
    private final ObjectMapper json = new ObjectMapper();
    private final AtomicReference<String> commentText = new AtomicReference<>();
    private final AtomicReference<String> updatedCommentText = new AtomicReference<>();
    private final List<String> deletedActionIds = new CopyOnWriteArrayList<>();
    private final List<String> workpadCallOrder = new CopyOnWriteArrayList<>();
    private final AtomicReference<String> movedToListId = new AtomicReference<>();
    private final AtomicReference<String> cardResponse = new AtomicReference<>();
    private final AtomicReference<Integer> cardStatus = new AtomicReference<>();
    private final AtomicReference<Integer> updateStatus = new AtomicReference<>(200);
    private final AtomicReference<Integer> deleteStatus = new AtomicReference<>(200);
    private HttpServer server;

    @TempDir
    Path tempDir;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext(
                "/1/boards/board-1",
                exchange -> respond(exchange, "{\"id\":\"board-1\",\"name\":\"Test Board\",\"closed\":false}"));
        server.createContext(
                "/1/boards/board-1/lists",
                exchange -> respond(
                        exchange,
                        """
                        [
                          {"id":"list-ready","name":"Ready for Codex","closed":false,"pos":1},
                          {"id":"list-review","name":"Review","closed":false,"pos":2},
                          {"id":"list-closed","name":"Closed Review","closed":true,"pos":3}
                        ]
                        """));
        server.createContext(
                "/1/cards/card-1",
                exchange -> respond(exchange, cardStatus.get(), cardResponseForRequestedFields(exchange)));
        server.createContext("/1/cards/card-1/actions/comments", exchange -> {
            assertThat(exchange.getRequestMethod()).isEqualTo("POST");
            commentText.set(query(exchange).get("text"));
            respond(exchange, "{\"id\":\"action-1\"}");
        });
        server.createContext("/1/actions/action-workpad-older", exchange -> {
            assertThat(exchange.getRequestMethod()).isEqualTo("DELETE");
            workpadCallOrder.add("delete:action-workpad-older");
            if (deleteStatus.get() == 200) {
                deletedActionIds.add("action-workpad-older");
            }
            respond(exchange, deleteStatus.get(), "{}");
        });
        server.createContext("/1/actions/action-workpad-oldest", exchange -> {
            assertThat(exchange.getRequestMethod()).isEqualTo("DELETE");
            workpadCallOrder.add("delete:action-workpad-oldest");
            if (deleteStatus.get() == 200) {
                deletedActionIds.add("action-workpad-oldest");
            }
            respond(exchange, deleteStatus.get(), "{}");
        });
        server.createContext("/1/actions/action-workpad/text", exchange -> {
            assertThat(exchange.getRequestMethod()).isEqualTo("PUT");
            workpadCallOrder.add("update:action-workpad");
            if (updateStatus.get() == 200) {
                updatedCommentText.set(query(exchange).get("value"));
            }
            respond(exchange, updateStatus.get(), "{\"id\":\"action-workpad\"}");
        });
        server.createContext("/1/cards/card-1/idList", exchange -> {
            assertThat(exchange.getRequestMethod()).isEqualTo("PUT");
            movedToListId.set(query(exchange).get("value"));
            respond(exchange, "{\"id\":\"card-1\"}");
        });
        cardResponse.set(cardJson("[]"));
        cardStatus.set(200);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void advertisesCommentAndMoveToolsWhenWritesAreEnabledAndMoveAllowlistExists() {
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
        assertThat(workpadCallOrder)
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
        assertThat(workpadCallOrder).containsExactly("update:action-workpad");
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
        assertThat(workpadCallOrder).isEmpty();
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
    void movesCurrentCardToAllowedListIdWhenNamesAreNotConfigured() {
        // given
        TrelloHandoffToolHandler handler = handler();

        // when
        var result = handler.handle(
                config(List.of(), List.of("list-review")),
                TestCards.card("card-1", "TRELLO-abc", "Ready for Codex"),
                json.createObjectNode()
                        .put("tool", TrelloHandoffToolHandler.MOVE_CURRENT_CARD)
                        .set("arguments", json.createObjectNode().put("list_id", "list-review")));

        // then
        assertThat(result.path("success").asBoolean()).isTrue();
        assertThat(movedToListId.get()).isEqualTo("list-review");
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

    private JsonNode upsertWorkpad(TrelloHandoffToolHandler handler) {
        return handler.handle(
                config(List.of("Review"), List.of()),
                TestCards.card("card-1", "TRELLO-abc", "Ready for Codex"),
                json.createObjectNode()
                        .put("tool", TrelloHandoffToolHandler.UPSERT_WORKPAD)
                        .set("arguments", json.createObjectNode().put("text", "## Codex Workpad\n\nUpdated plan")));
    }

    private EffectiveConfig config(List<String> allowedMoveListNames, List<String> allowedMoveListIds) {
        return config(true, allowedMoveListNames, allowedMoveListIds, false);
    }

    private EffectiveConfig configWithWrites(boolean allowWrites) {
        return config(allowWrites, List.of("Review"), List.of(), false);
    }

    private EffectiveConfig configWithDestructiveOperations() {
        return config(true, List.of("Review"), List.of(), true);
    }

    private EffectiveConfig config(
            boolean allowWrites,
            List<String> allowedMoveListNames,
            List<String> allowedMoveListIds,
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
                                        "http://127.0.0.1:"
                                                + server.getAddress().getPort() + "/1",
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
                                        "allowed_move_list_names",
                                        allowedMoveListNames,
                                        "allowed_move_list_ids",
                                        allowedMoveListIds,
                                        "allow_destructive_operations",
                                        allowDestructiveOperations)),
                        ""))
                .withResolvedBoardId("board-1");
    }

    private String cardResponseForRequestedFields(HttpExchange exchange) {
        String response = cardResponse.get();
        String actionFields = query(exchange).getOrDefault("action_fields", "");
        if (Arrays.asList(actionFields.split(",")).contains("id")) {
            return response;
        }
        return response.replace("\"id\":\"action-workpad\",", "");
    }

    private static String cardJson(String actionsJson) {
        return """
                {
                  "id":"card-1",
                  "name":"Implement feature",
                  "desc":"Description",
                  "idList":"list-ready",
                  "idBoard":"board-1",
                  "closed":false,
                  "idShort":1,
                  "shortLink":"abc",
                  "shortUrl":"https://trello.com/c/SYNTH101",
                  "url":"https://trello.com/c/SYNTH101",
                  "labels":[],
                  "dateLastActivity":"2026-05-05T00:00:00.000Z",
                  "pos":1,
                  "actions":%s
                }
                """
                .formatted(actionsJson);
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
