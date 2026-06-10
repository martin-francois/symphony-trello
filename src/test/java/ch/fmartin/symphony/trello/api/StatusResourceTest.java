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
        boolean hasInjectAnnotation = constructor.isAnnotationPresent(Inject.class);

        // then
        assertThat(hasInjectAnnotation).isTrue();
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
                .doesNotContain("TRELLO-<abc>");
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
        assertThat(state).isSameAs(snapshot);
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
        CardDebugDetails details = new CardDebugDetails(
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
        when(orchestrator.cardDetails("TRELLO-abc")).thenReturn(Optional.of(details));
        when(orchestrator.cardDetails("missing")).thenReturn(Optional.empty());
        var resource = new StatusResource(orchestrator);

        // when
        Object found = resource.card("TRELLO-abc");

        // then
        assertThat(found).isSameAs(details);
        assertThatThrownBy(() -> resource.card("missing"))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Unknown card: missing");
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
                Response notFound = mapper.toResponse(new NotFoundException("missing"));
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
                new RuntimeSnapshot.Counts(1, 0),
                new RuntimeSnapshot.Routing(List.of("Ready for Codex"), List.of("Done"), List.of("human review")),
                List.of(new RuntimeSnapshot.RunningRow(
                        "card-1",
                        "TRELLO-<abc>",
                        "Ready & Waiting",
                        "thread-turn",
                        2,
                        "turn/started",
                        "working",
                        Instant.parse("2026-05-05T00:00:01Z"),
                        Instant.parse("2026-05-05T00:00:02Z"),
                        Map.of("total_tokens", 12L))),
                List.of(),
                new RuntimeSnapshot.TokenTotals(4, 8, 12, 1.25),
                null);
    }
}
