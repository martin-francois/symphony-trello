package ch.fmartin.symphony.trello.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.fmartin.symphony.trello.orchestrator.CardDebugDetails;
import ch.fmartin.symphony.trello.orchestrator.RuntimeSnapshot;
import ch.fmartin.symphony.trello.orchestrator.SymphonyOrchestrator;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Response;
import java.lang.reflect.Constructor;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class StatusResourceTest {
    @Test
    void productionConstructorIsMarkedForCdiInjection() throws Exception {
        // given
        Constructor<StatusResource> constructor =
                StatusResource.class.getConstructor(SymphonyOrchestrator.class, Clock.class);

        // when
        Inject annotation = constructor.getAnnotation(Inject.class);

        // then
        assertThat(annotation).as("@Inject on the production constructor").isNotNull();
    }

    @Test
    void rendersEscapedHtmlStatusRows() {
        // given
        SymphonyOrchestrator orchestrator = mock();
        when(orchestrator.snapshot()).thenReturn(snapshotWithRunningCard());
        var resource = new StatusResource(orchestrator);

        // when
        String html = resource.index();

        // then
        assertThat(html)
                .contains("<code>1</code> running")
                .contains("TRELLO-&lt;abc&gt;")
                .contains("Ready &amp; Waiting")
                .contains("CODEX_&lt;USAGE&gt;&amp;")
                .doesNotContain("TRELLO-<abc>", "CODEX_<USAGE>&");
    }

    @Test
    void rendersRunningRowsInSnapshotOrderAndHandlesAnEmptyList() {
        // given
        RuntimeSnapshot base = snapshotWithRunningCard();
        RuntimeSnapshot.RunningRow first = base.running().getFirst();
        var second = new RuntimeSnapshot.RunningRow(
                "card-2",
                "TRELLO-second",
                "https://trello.com/c/SYNTH003",
                "Ready",
                "thread-second",
                1,
                "turn/completed",
                "done",
                Instant.parse("2026-05-05T00:00:04Z"),
                Instant.parse("2026-05-05T00:00:05Z"),
                Map.of());
        SymphonyOrchestrator orchestrator = mock();
        when(orchestrator.snapshot())
                .thenReturn(withRunningRows(base, List.of(second, first)), withRunningRows(base, List.of()));
        var resource = new StatusResource(orchestrator);

        // when
        String orderedHtml = resource.index();
        String emptyHtml = resource.index();

        // then
        assertThat(orderedHtml).containsSubsequence("TRELLO-second", "TRELLO-&lt;abc&gt;");
        assertThat(emptyHtml).contains("<tbody></tbody>");
    }

    @Test
    void logInfoSnapshotsOuterListAndMapStructure() {
        // given
        Map<String, Object> sourceLog = new LinkedHashMap<>();
        sourceLog.put("path", "session.log");
        List<Map<String, Object>> sourceLogs = new ArrayList<>();
        sourceLogs.add(sourceLog);
        var logInfo = new CardDebugDetails.LogInfo(sourceLogs);

        // when
        List<Map<String, Object>> snapshot = logInfo.codexSessionLogs();
        sourceLog.put("path", "changed.log");
        sourceLogs.clear();

        // then
        assertThat(logInfo.codexSessionLogs()).containsExactlyElementsOf(snapshot);
        assertThat(snapshot).isUnmodifiable().singleElement().satisfies(log -> assertThat(log)
                .containsEntry("path", "session.log")
                .isUnmodifiable());
    }

    @Test
    void statusBannerDoesNotRenderRateLimitAccountProviderOrCommandDetails() {
        // given
        RuntimeSnapshot base = snapshotWithRunningCard();
        var privateSnapshot = new RuntimeSnapshot(
                base.generatedAt(),
                base.counts(),
                base.routing(),
                base.running(),
                base.retrying(),
                base.codexTotals(),
                base.dispatchPause(),
                Map.of(
                        "account", "private-account-marker",
                        "provider", "private-provider-marker",
                        "command", "private-command-marker"));
        SymphonyOrchestrator orchestrator = mock();
        when(orchestrator.snapshot()).thenReturn(privateSnapshot);
        var resource = new StatusResource(orchestrator);

        // when
        String html = resource.index();

        // then
        assertThat(html)
                .contains("CODEX_&lt;USAGE&gt;&amp;", "2026-05-05T00:00:03Z", "2026-05-05T01:00:00Z")
                .doesNotContain("private-account-marker", "private-provider-marker", "private-command-marker");
    }

    @Test
    void returnsSnapshotFromStateEndpoint() {
        // given
        RuntimeSnapshot snapshot = snapshotWithRunningCard();
        SymphonyOrchestrator orchestrator = mock();
        when(orchestrator.snapshot()).thenReturn(snapshot);
        var resource = new StatusResource(orchestrator);

        // when
        Object state = resource.state();

        // then
        assertThat(state).isInstanceOf(StateSnapshotResponse.class);
        var response = (StateSnapshotResponse) state;
        assertThat(response.running()).singleElement().satisfies(row -> assertThat(row.cardUrl())
                .isEqualTo("https://trello.com/c/SYNTH001"));
        assertThat(response.retrying()).singleElement().satisfies(row -> assertThat(row.cardUrl())
                .isEqualTo("https://trello.com/c/SYNTH002"));
    }

    @Test
    void localStatusIncludesWorkflowPathAndBoardIdForLoopbackClientsOnly() {
        // given
        SymphonyOrchestrator orchestrator = mock();
        when(orchestrator.selectedBoardId()).thenReturn("board-1");
        when(orchestrator.selectedConfiguredBoardId()).thenReturn("abc123");
        when(orchestrator.selectedWorkflowPath()).thenReturn(Path.of("/private/workflow.md"));
        var loopbackResource = new StatusResource(orchestrator, () -> true);
        var remoteResource = new StatusResource(orchestrator, () -> false);

        // when
        Object loopbackStatus = loopbackResource.localStatus();
        Throwable remoteFailure = catchThrowable(remoteResource::localStatus);

        // then
        assertThat(loopbackStatus).isInstanceOfSatisfying(Map.class, status -> assertThat(status)
                .containsEntry("boardId", "board-1")
                .containsEntry("configuredBoardId", "abc123")
                .containsEntry("workflowPath", "/private/workflow.md")
                .containsEntry("pid", ProcessHandle.current().pid()));
        assertThat(remoteFailure).isInstanceOf(NotFoundException.class);
    }

    @Test
    void hidesLocalStatusOptionsFromNonLoopbackClients() {
        // given
        SymphonyOrchestrator orchestrator = mock();
        var loopbackResource = new StatusResource(orchestrator, () -> true);
        var remoteResource = new StatusResource(orchestrator, () -> false);

        // when
        var loopbackOptions = loopbackResource.localStatusOptions();
        Throwable remoteFailure = catchThrowable(remoteResource::localStatusOptions);

        // then
        try (loopbackOptions) {
            assertThat(loopbackOptions.getHeaderString("Allow")).isEqualTo("GET, HEAD, OPTIONS");
        }
        assertThat(remoteFailure)
                .as("OPTIONS must not reveal the loopback-only endpoint to remote clients")
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void returnsCardDetailsOrTypedNotFound() {
        // given
        var details = new CardDebugDetails(
                "TRELLO-abc",
                "card-1",
                "running",
                new CardDebugDetails.WorkspaceInfo(Path.of("workspaces/TRELLO-abc")),
                new CardDebugDetails.AttemptInfo(0, null),
                null,
                null,
                new CardDebugDetails.LogInfo(List.of()),
                List.of(),
                null,
                Map.of());
        SymphonyOrchestrator orchestrator = mock();
        when(orchestrator.cardIdentifierPrefix()).thenReturn("TRELLO");
        when(orchestrator.cardDetails("TRELLO-abc")).thenReturn(Optional.of(details));
        when(orchestrator.cardDetails("TRELLO-missing")).thenReturn(Optional.empty());
        var resource = new StatusResource(orchestrator);

        // when
        Object found = resource.card("TRELLO-abc");

        // then
        assertThat(found)
                .isInstanceOfSatisfying(CardDebugDetailsResponse.class, response -> assertThat(response.cardId())
                        .isEqualTo(details.cardId()));
        assertThatThrownBy(() -> resource.card("TRELLO-missing"))
                .isInstanceOf(CardNotFoundException.class)
                .hasMessageContaining("Unknown card: TRELLO-missing");
        assertThatThrownBy(() -> resource.card("missing"))
                .isInstanceOf(NotFoundException.class)
                .isNotInstanceOf(CardNotFoundException.class)
                .hasMessageContaining("Unknown local API route.");
    }

    @Test
    void queuesRefreshAndMapsErrorsToJsonResponses() {
        // given
        SymphonyOrchestrator orchestrator = mock();
        Instant requestedAt = Instant.parse("2026-05-05T12:34:56Z");
        var resource = new StatusResource(orchestrator, null, Clock.fixed(requestedAt, ZoneOffset.UTC));
        var mapper = new ApiExceptionMapper();

        // when
        try (Response refresh = resource.refresh();
                Response notFound = mapper.toResponse(new CardNotFoundException("missing"));
                Response internal = mapper.toResponse(new IllegalStateException("boom"))) {

            // then
            verify(orchestrator).requestRefresh();
            assertThat(refresh.getStatus()).isEqualTo(202);
            assertThat(refresh.getEntity()).isInstanceOfSatisfying(Map.class, entity -> assertThat(entity)
                    .containsEntry("requested_at", requestedAt));
            assertThat(notFound.getStatus()).isEqualTo(404);
            assertThat(notFound.getEntity().toString())
                    .contains("card_not_found")
                    .contains("missing");
            assertThat(internal.getStatus()).isEqualTo(500);
            assertThat(internal.getEntity().toString())
                    .contains("internal_error")
                    .contains("boom");
        }
    }

    private static RuntimeSnapshot snapshotWithRunningCard() {
        return new RuntimeSnapshot(
                Instant.parse("2026-05-05T00:00:00Z"),
                new RuntimeSnapshot.Counts(1, 1),
                new RuntimeSnapshot.Routing(List.of("Ready for Codex"), List.of("Done"), List.of("human review")),
                List.of(new RuntimeSnapshot.RunningRow(
                        "card-1",
                        "TRELLO-<abc>",
                        "https://trello.com/c/SYNTH001",
                        "Ready & Waiting",
                        "thread-turn",
                        2,
                        "turn/started",
                        "working",
                        Instant.parse("2026-05-05T00:00:01Z"),
                        Instant.parse("2026-05-05T00:00:02Z"),
                        Map.of("total_tokens", 12L))),
                List.of(new RuntimeSnapshot.RetryRow(
                        "card-2",
                        "TRELLO-retry",
                        "https://trello.com/c/SYNTH002",
                        3,
                        Instant.parse("2026-05-05T00:01:00Z"),
                        "no available orchestrator slots")),
                new RuntimeSnapshot.TokenTotals(4, 8, 12, 1.25),
                new RuntimeSnapshot.DispatchPause(
                        "CODEX_<USAGE>&", Instant.parse("2026-05-05T00:00:03Z"), Instant.parse("2026-05-05T01:00:00Z")),
                null);
    }

    private static RuntimeSnapshot withRunningRows(
            RuntimeSnapshot snapshot, List<RuntimeSnapshot.RunningRow> runningRows) {
        return new RuntimeSnapshot(
                snapshot.generatedAt(),
                new RuntimeSnapshot.Counts(runningRows.size(), snapshot.counts().retrying()),
                snapshot.routing(),
                runningRows,
                snapshot.retrying(),
                snapshot.codexTotals(),
                snapshot.dispatchPause(),
                snapshot.rateLimits());
    }
}
