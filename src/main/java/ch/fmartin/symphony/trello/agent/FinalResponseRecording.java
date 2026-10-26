package ch.fmartin.symphony.trello.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Objects;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/// Keeps only the completed final answer of the latest accepted turn, never protocol or reasoning.
@NullMarked
final class FinalResponseRecording implements AutoCloseable {
    private final FinalResponseStore store;
    private final Path path;
    private final String worker;
    private final ObjectNode record;
    private @Nullable TurnIdentity current;
    private @Nullable Answer pending;
    private @Nullable Answer sealed;
    private boolean acceptedCompletion;
    private boolean oversized;
    private boolean finished;

    FinalResponseRecording(
            FinalResponseStore store, Path path, AgentRunner.AgentRunRequest request, ObjectNode record) {
        this.store = store;
        this.path = path;
        this.worker = request.workerIdentity();
        this.record = record;
    }

    AgentEventListener listener(AgentEventListener delegate) {
        return new AgentEventListener() {
            @Override
            public void onEvent(AgentEvent event) {
                onEventAndReportAccepted(event);
            }

            @Override
            public boolean onEventAndReportAccepted(AgentEvent event) {
                boolean accepted = delegate.onEventAndReportAccepted(event);
                if (accepted) {
                    accept(event);
                }
                return accepted;
            }

            @Override
            public boolean beforeHandoffMove(CardHandoffMove move) {
                return delegate.beforeHandoffMove(move);
            }

            @Override
            public void afterHandoffMove(CardHandoffMove move, boolean succeeded) {
                delegate.afterHandoffMove(move, succeeded);
            }

            @Override
            public void onSuccessfulTurn(String threadId, String turnId) {
                delegate.onSuccessfulTurn(threadId, turnId);
                seal(threadId, turnId);
            }
        };
    }

    private synchronized void accept(AgentEvent event) {
        if (!worker.equals(event.workerIdentity()) || finished) {
            return;
        }
        if ("turn/started".equals(event.event())
                || "session_started".equals(event.event())
                || "session_continued".equals(event.event())) {
            if (event.threadId() != null
                    && event.turnId() != null
                    && (current == null || !current.matches(event.threadId(), event.turnId()))) {
                current = new TurnIdentity(event.threadId(), event.turnId());
                pending = null;
                sealed = null;
                acceptedCompletion = false;
                oversized = false;
            }
            return;
        }
        JsonNode payload = event.payload();
        if (payload == null) {
            return;
        }
        if ("item/completed".equals(event.event())) {
            JsonNode item = payload.path("item");
            if ("agentMessage".equals(item.path("type").asText())
                    && "final_answer".equals(item.path("phase").asText())
                    && item.path("text").isTextual()
                    && event.threadId() != null
                    && event.turnId() != null) {
                if (current == null) {
                    current = new TurnIdentity(event.threadId(), event.turnId());
                }
                if (!current.matches(event.threadId(), event.turnId())) {
                    return;
                }
                String text = item.path("text").textValue();
                oversized = text.getBytes(StandardCharsets.UTF_8).length > FinalResponseStore.MAX_RESPONSE_BYTES;
                pending = oversized || text.isBlank() ? null : new Answer(current, text);
                sealed = null;
                acceptedCompletion = false;
            }
        } else if ("turn/completed".equals(event.event())) {
            acceptedCompletion = pending != null
                    && pending.identity().matches(event.threadId(), event.turnId())
                    && Objects.equals(
                            event.turnId(), payload.path("turn").path("id").asText(null))
                    && "completed".equals(payload.path("turn").path("status").asText());
        } else if ("turn/failed".equals(event.event())
                || "turn/cancelled".equals(event.event())
                || "turn/interrupted".equals(event.event())) {
            pending = null;
            sealed = null;
            acceptedCompletion = false;
        }
    }

    private synchronized void seal(String threadId, String turnId) {
        sealed = null;
        if (acceptedCompletion && pending != null && pending.identity().matches(threadId, turnId)) {
            sealed = pending;
        }
    }

    synchronized AgentRunResult finish(AgentRunResult result) {
        if (!result.success()) {
            close();
            return result;
        }
        if (Thread.currentThread().isInterrupted() || sealed == null || oversized) {
            close();
            String message =
                    "cannot save local final response: expected an accepted complete final answer within the size limit and a matching successful turn";
            FinalResponseStore.reportFailure(worker, message);
            return AgentRunResult.fail(message);
        }
        store.complete(
                path,
                worker,
                record,
                sealed.identity().thread(),
                sealed.identity().turn(),
                sealed.text());
        finished = true;
        return result;
    }

    @Override
    public synchronized void close() {
        if (!finished) {
            store.abandon(path, worker, record);
            finished = true;
        }
    }

    private record TurnIdentity(String thread, String turn) {
        boolean matches(@Nullable String expectedThread, @Nullable String expectedTurn) {
            return thread.equals(expectedThread) && turn.equals(expectedTurn);
        }
    }

    private record Answer(TurnIdentity identity, String text) {}
}
