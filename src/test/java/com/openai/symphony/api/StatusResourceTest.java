package com.openai.symphony.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openai.symphony.config.ConfigResolver;
import com.openai.symphony.orchestrator.CardDebugDetails;
import com.openai.symphony.orchestrator.RuntimeSnapshot;
import com.openai.symphony.orchestrator.SymphonyOrchestrator;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Response;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class StatusResourceTest {
    @Test
    void rendersEscapedHtmlStatusRows() {
        // given
        var resource = new StatusResource(new FakeOrchestrator(snapshotWithRunningCard(), Optional.empty()));

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
        var resource = new StatusResource(new FakeOrchestrator(snapshot, Optional.empty()));

        // when
        Object state = resource.state();

        // then
        assertThat(state).isSameAs(snapshot);
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
        var resource = new StatusResource(new FakeOrchestrator(snapshotWithRunningCard(), Optional.of(details)));

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
        var orchestrator = new FakeOrchestrator(snapshotWithRunningCard(), Optional.empty());
        var resource = new StatusResource(orchestrator);
        var mapper = new ApiExceptionMapper();

        // when
        Response refresh = resource.refresh();
        Response notFound = mapper.toResponse(new NotFoundException("missing"));
        Response internal = mapper.toResponse(new IllegalStateException("boom"));

        // then
        assertThat(orchestrator.refreshRequested).isTrue();
        assertThat(refresh.getStatus()).isEqualTo(202);
        assertThat(notFound.getStatus()).isEqualTo(404);
        assertThat(notFound.getEntity().toString()).contains("card_not_found").contains("missing");
        assertThat(internal.getStatus()).isEqualTo(500);
        assertThat(internal.getEntity().toString()).contains("internal_error").contains("boom");
    }

    private static RuntimeSnapshot snapshotWithRunningCard() {
        return new RuntimeSnapshot(
                Instant.parse("2026-05-05T00:00:00Z"),
                new RuntimeSnapshot.Counts(1, 0),
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

    private static final class FakeOrchestrator extends SymphonyOrchestrator {
        private final RuntimeSnapshot snapshot;
        private final Optional<CardDebugDetails> details;
        private final AtomicBoolean refreshRequested = new AtomicBoolean();

        private FakeOrchestrator(RuntimeSnapshot snapshot, Optional<CardDebugDetails> details) {
            super(null, new ConfigResolver(), null, null, null, null);
            this.snapshot = snapshot;
            this.details = details;
        }

        @Override
        public synchronized RuntimeSnapshot snapshot() {
            return snapshot;
        }

        @Override
        public synchronized Optional<CardDebugDetails> cardDetails(String cardIdentifier) {
            return details.filter(detail -> detail.cardIdentifier().equals(cardIdentifier));
        }

        @Override
        public void requestRefresh() {
            refreshRequested.set(true);
        }
    }
}
