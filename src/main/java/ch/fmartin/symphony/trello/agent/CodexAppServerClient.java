package ch.fmartin.symphony.trello.agent;

import ch.fmartin.symphony.trello.config.EffectiveConfig;
import ch.fmartin.symphony.trello.domain.Card;
import ch.fmartin.symphony.trello.process.ProcessEnvironment;
import ch.fmartin.symphony.trello.time.ApplicationClock;
import ch.fmartin.symphony.trello.workflow.CodexSandboxPolicy;
import ch.fmartin.symphony.trello.workspace.WorkspaceManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.jboss.logging.Logger;

@ApplicationScoped
public class CodexAppServerClient {
    private static final Logger LOG = Logger.getLogger(CodexAppServerClient.class);

    private final ObjectMapper json;
    private final TrelloHandoffToolHandler trelloTools;
    private final Clock clock;
    private final Map<String, RateLimitState> rateLimitsByCodexCommand = new ConcurrentHashMap<>();

    public CodexAppServerClient(ObjectMapper json, TrelloHandoffToolHandler trelloTools) {
        this(json, trelloTools, ApplicationClock.systemUtc());
    }

    @Inject
    public CodexAppServerClient(ObjectMapper json, TrelloHandoffToolHandler trelloTools, Clock clock) {
        this.json = json;
        this.trelloTools = trelloTools;
        this.clock = clock;
    }

    public AgentRunResult runTurn(
            EffectiveConfig config,
            Card card,
            Path workspace,
            String prompt,
            String workerIdentity,
            AgentEventListener listener) {
        return runSession(config, card, workspace, prompt, workerIdentity, listener, turn -> TurnDecision.stop());
    }

    public AgentRunResult runSession(
            EffectiveConfig config,
            Card card,
            Path workspace,
            String prompt,
            String workerIdentity,
            AgentEventListener listener,
            TurnController controller) {
        WorkspaceManager.requireInsideRoot(config.workspace().root(), workspace);
        Process process;
        try {
            ProcessBuilder processBuilder = new ProcessBuilder(
                            "bash", "-lc", config.codex().command())
                    .directory(workspace.toFile())
                    .redirectError(ProcessBuilder.Redirect.PIPE);
            ProcessEnvironment.removeDefaultSecrets(processBuilder);
            ProcessEnvironment.limitGitDiscovery(
                    processBuilder, config.workspace().root());
            process = processBuilder.start();
        } catch (IOException e) {
            return AgentRunResult.fail("codex_not_found: " + e.getMessage());
        }

        try (var session = new AppServerSession(process, config, card, workerIdentity, listener)) {
            ObjectNode capabilities = object();
            if (trelloTools.shouldEnableExperimentalApi(config)) {
                capabilities.put("experimentalApi", true);
            }
            session.start();
            JsonNode init = request(
                    session,
                    "initialize",
                    object(
                            "clientInfo",
                            object("name", "symphony-trello", "version", "0.1.0"),
                            "capabilities",
                            capabilities),
                    config.codex().readTimeout());
            LOG.debugf(
                    "codex_initialize outcome=completed user_agent=%s",
                    init.path("userAgent").asText(""));
            session.notify("initialized");

            JsonNode thread = request(
                    session,
                    "thread/start",
                    threadStartParams(config, workspace),
                    config.codex().readTimeout());
            String threadId = thread.at("/thread/id").asText(null);
            if (threadId == null) {
                return AgentRunResult.fail("codex_protocol_error: missing thread id");
            }

            String nextPrompt = prompt;
            int turnNumber = 0;
            while (nextPrompt != null) {
                turnNumber++;
                JsonNode turn = request(
                        session,
                        "turn/start",
                        turnStartParams(config, threadId, workspace, nextPrompt),
                        config.codex().readTimeout());
                String turnId = turn.at("/turn/id").asText(null);
                if (turnId == null) {
                    return AgentRunResult.fail("codex_protocol_error: missing turn id");
                }
                listener.onEvent(event(
                        turnNumber == 1 ? "session_started" : "session_continued",
                        workerIdentity,
                        process.pid(),
                        threadId,
                        turnId,
                        null,
                        Map.of(),
                        turn));

                JsonNode completed;
                try {
                    completed =
                            session.awaitTurnCompleted(turnId, config.codex().turnTimeout());
                } catch (TimeoutException e) {
                    process.destroyForcibly();
                    return AgentRunResult.fail("turn_timeout: " + e.getMessage());
                }
                JsonNode completedTurn = completed.path("turn");
                JsonNode error = completedTurn.path("error");
                String status = completedTurn.path("status").asText("");
                if ("interrupted".equals(status)) {
                    return AgentRunResult.fail("turn_interrupted: " + turnFailureSummary(completedTurn, completed));
                }
                if (!error.isMissingNode() && !error.isNull()) {
                    return turnFailureResult("turn_failed", error, session);
                }
                if ("failed".equals(status)) {
                    return AgentRunResult.fail("turn_failed: " + turnFailureSummary(completedTurn, completed));
                }
                TurnDecision decision = controller.afterSuccessfulTurn(turnNumber);
                if (decision.failureReason() != null) {
                    return AgentRunResult.fail(decision.failureReason());
                }
                nextPrompt = decision.nextPrompt();
            }
            return AgentRunResult.ok();
        } catch (CodexAppServerTerminalException e) {
            process.destroyForcibly();
            return e.failureCategory() == AgentRunResult.FailureCategory.CODEX_USAGE_LIMIT
                    ? AgentRunResult.codexUsageLimit(e.code() + ": " + e.getMessage(), e.retryNotBefore())
                    : AgentRunResult.fail(e.code() + ": " + e.getMessage());
        } catch (TimeoutException e) {
            process.destroyForcibly();
            return AgentRunResult.fail("response_timeout: " + e.getMessage());
        } catch (Exception e) {
            process.destroyForcibly();
            return AgentRunResult.fail("codex_protocol_error: " + e.getMessage());
        }
    }

    @FunctionalInterface
    public interface TurnController {
        TurnDecision afterSuccessfulTurn(int completedTurns);
    }

    public record TurnDecision(String nextPrompt, String failureReason) {
        public static TurnDecision stop() {
            return new TurnDecision(null, null);
        }

        public static TurnDecision continueWith(String prompt) {
            return new TurnDecision(prompt, null);
        }

        public static TurnDecision fail(String reason) {
            return new TurnDecision(null, reason);
        }
    }

    private static final class RateLimitState {
        private JsonNode latest;
    }

    private ObjectNode threadStartParams(EffectiveConfig config, Path workspace) {
        ObjectNode params = object("cwd", workspace.toString(), "serviceName", "symphony-trello", "ephemeral", true);
        putIfPresent(params, "approvalPolicy", config.codex().approvalPolicy());
        putIfPresent(params, "sandbox", config.codex().threadSandbox());
        putIfPresent(params, "model", config.codex().model());
        var toolSpecs = trelloTools.toolSpecs(config);
        if (!toolSpecs.isEmpty()) {
            params.set("dynamicTools", toolSpecs);
        }
        return params;
    }

    private ObjectNode turnStartParams(EffectiveConfig config, String threadId, Path workspace, String prompt) {
        ObjectNode params = object("threadId", threadId, "cwd", workspace.toString());
        params.set("input", json.createArrayNode().add(object("type", "text", "text", prompt)));
        putIfPresent(params, "approvalPolicy", config.codex().approvalPolicy());
        putIfPresent(params, "model", config.codex().model());
        putIfPresent(params, "effort", config.codex().reasoningEffort());
        putIfPresent(params, "sandboxPolicy", sandboxPolicy(config));
        return params;
    }

    private JsonNode sandboxPolicy(EffectiveConfig config) {
        return CodexSandboxPolicy.effectivePolicy(
                json,
                config.codex().turnSandboxPolicy(),
                config.codex().additionalWritableRoots(),
                config.codex().forceDangerFullAccess());
    }

    private void putIfPresent(ObjectNode node, String key, Object value) {
        if (value != null) {
            node.set(key, json.valueToTree(value));
        }
    }

    private JsonNode request(AppServerSession session, String method, ObjectNode params, Duration timeout)
            throws Exception {
        return session.request(method, params, timeout);
    }

    private ObjectNode object(Object... keyValues) {
        ObjectNode node = json.createObjectNode();
        for (int i = 0; i < keyValues.length; i += 2) {
            node.set(keyValues[i].toString(), json.valueToTree(keyValues[i + 1]));
        }
        return node;
    }

    private AgentEvent event(
            String name,
            String workerIdentity,
            Long pid,
            String threadId,
            String turnId,
            String message,
            Map<String, Long> usage,
            JsonNode payload) {
        return new AgentEvent(name, clock.instant(), workerIdentity, pid, threadId, turnId, message, usage, payload);
    }

    private static String summarize(JsonNode node) {
        String message = node.path("message").asText(null);
        return message == null ? node.toString() : message;
    }

    private static String turnFailureSummary(JsonNode turn, JsonNode completed) {
        JsonNode error = turn.path("error");
        if (!error.isMissingNode() && !error.isNull()) {
            return summarize(error);
        }
        String status = turn.path("status").asText(null);
        return status == null ? summarize(completed) : "turn status " + status;
    }

    private AgentRunResult turnFailureResult(String code, JsonNode error, AppServerSession session) {
        return isUsageLimitError(error)
                ? AgentRunResult.codexUsageLimit(
                        code + ": " + turnErrorMessage(error), session.usageLimitRetryNotBefore())
                : AgentRunResult.fail(code + ": " + summarize(error));
    }

    private static String turnErrorMessage(JsonNode error) {
        String message = error.path("message").asText(null);
        return message == null || message.isBlank() ? "Codex turn failed" : message.strip();
    }

    private static boolean isUsageLimitError(JsonNode error) {
        return error.path("codexErrorInfo").isTextual()
                && "usageLimitExceeded".equals(error.path("codexErrorInfo").textValue());
    }

    private static final class CodexAppServerTerminalException extends IOException {
        private final String code;
        private final AgentRunResult.FailureCategory failureCategory;
        private final Optional<Instant> retryNotBefore;

        private CodexAppServerTerminalException(String code, String message) {
            this(code, message, AgentRunResult.FailureCategory.GENERIC, Optional.empty());
        }

        private CodexAppServerTerminalException(
                String code,
                String message,
                AgentRunResult.FailureCategory failureCategory,
                Optional<Instant> retryNotBefore) {
            super(message);
            this.code = code;
            this.failureCategory = failureCategory;
            this.retryNotBefore = retryNotBefore;
        }

        private String code() {
            return code;
        }

        private AgentRunResult.FailureCategory failureCategory() {
            return failureCategory;
        }

        private Optional<Instant> retryNotBefore() {
            return retryNotBefore;
        }
    }

    private final class AppServerSession implements AutoCloseable {
        private final Process process;
        private final EffectiveConfig config;
        private final Card card;
        private final String workerIdentity;
        private final AgentEventListener listener;
        // One session owner sends protocol requests sequentially; reader threads only consume IDs.
        private int nextRequestId;
        private final Map<Integer, CompletableFuture<JsonNode>> responses = new ConcurrentHashMap<>();
        // The turn-completion monitor owns these three maps and the early/late completion handoff.
        private final Map<String, CompletableFuture<JsonNode>> turnCompletions = new HashMap<>();
        private final Map<String, JsonNode> completedTurns = new HashMap<>();
        private final Map<String, Throwable> terminalTurnFailures = new HashMap<>();
        private final Object turnCompletionLock = new Object();
        private final Object writerLock = new Object();
        private final AtomicReference<Throwable> readerFailure = new AtomicReference<>();
        private final RateLimitState rateLimitState;
        private final BufferedWriter writer;

        private AppServerSession(
                Process process,
                EffectiveConfig config,
                Card card,
                String workerIdentity,
                AgentEventListener listener) {
            this.process = process;
            this.config = config;
            this.card = card;
            this.workerIdentity = workerIdentity;
            this.listener = listener;
            this.rateLimitState =
                    rateLimitsByCodexCommand.computeIfAbsent(config.codex().command(), ignored -> new RateLimitState());
            this.writer = process.outputWriter(StandardCharsets.UTF_8);
        }

        void start() {
            Thread.ofVirtual().name("codex-app-server-stdout").start(this::readStdout);
            Thread.ofVirtual().name("codex-app-server-stderr").start(this::readStderr);
        }

        JsonNode request(String method, ObjectNode params, Duration timeout) throws Exception {
            Throwable failure = readerFailure.get();
            if (failure != null) {
                throw unwrapReaderFailure(failure);
            }
            nextRequestId++;
            int id = nextRequestId;
            var future = new CompletableFuture<JsonNode>();
            responses.put(id, future);
            ObjectNode request = object("id", id, "method", method, "params", params);
            write(request);
            failure = readerFailure.get();
            if (failure != null && !future.isDone()) {
                responses.remove(id);
                throw unwrapReaderFailure(failure);
            }
            try {
                return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (ExecutionException e) {
                throw unwrapResponseFailure(e);
            } finally {
                responses.remove(id);
            }
        }

        void notify(String method) throws IOException {
            write(object("method", method));
        }

        JsonNode awaitTurnCompleted(String turnId, Duration timeout) throws Exception {
            CompletableFuture<JsonNode> future;
            synchronized (turnCompletionLock) {
                Throwable terminalFailure = terminalTurnFailures.remove(turnId);
                if (terminalFailure != null) {
                    throw unwrapReaderFailure(terminalFailure);
                }
                JsonNode completed = completedTurns.remove(turnId);
                if (completed != null) {
                    return completed;
                }
                Throwable failure = readerFailure.get();
                if (failure != null) {
                    throw unwrapReaderFailure(failure);
                }
                future = new CompletableFuture<>();
                turnCompletions.put(turnId, future);
            }
            try {
                return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (ExecutionException e) {
                throw unwrapReaderFailure(e);
            } finally {
                synchronized (turnCompletionLock) {
                    turnCompletions.remove(turnId);
                }
            }
        }

        private void write(ObjectNode message) throws IOException {
            synchronized (writerLock) {
                writer.write(json.writeValueAsString(message));
                writer.newLine();
                writer.flush();
            }
        }

        private void readStdout() {
            try (var reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8), 10 * 1024 * 1024)) {
                String line = reader.readLine();
                while (line != null) {
                    handleMessage(json.readTree(line));
                    line = reader.readLine();
                }
                failPending(new CodexAppServerTerminalException(
                        "process_exit", "codex app-server stdout closed before active turn completed"));
            } catch (Exception e) {
                failPending(e);
            }
        }

        private void readStderr() {
            try (var reader =
                    new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line = reader.readLine();
                while (line != null) {
                    LOG.debugf("codex_stderr pid=%d message=%s", process.pid(), line);
                    line = reader.readLine();
                }
            } catch (IOException e) {
                LOG.debugf("codex_stderr pid=%d outcome=reader_failed reason=%s", process.pid(), e.getMessage());
            }
        }

        private void handleMessage(JsonNode message) throws IOException {
            if (message.has("id") && (message.has("result") || message.has("error")) && !message.has("method")) {
                CompletableFuture<JsonNode> future =
                        responses.remove(message.path("id").asInt());
                if (future != null) {
                    if (message.has("error")) {
                        future.completeExceptionally(
                                new IOException(message.path("error").toString()));
                    } else {
                        future.complete(message.path("result"));
                    }
                }
                return;
            }
            if (message.has("id") && message.has("method")) {
                respondToServerRequest(message);
                return;
            }
            if (message.has("method")) {
                handleNotification(message);
            }
        }

        private void respondToServerRequest(JsonNode request) throws IOException {
            int id = request.path("id").asInt();
            String method = request.path("method").asText();
            ObjectNode result =
                    switch (method) {
                        case "item/commandExecution/requestApproval" -> object("decision", "acceptForSession");
                        case "item/fileChange/requestApproval" -> object("decision", "acceptForSession");
                        case "item/permissions/requestApproval" -> object("decision", "cancel");
                        case "item/tool/requestUserInput", "mcpServer/elicitation/request" ->
                            object("answers", object());
                        case "item/tool/call" -> trelloTools.handle(config, card, request.path("params"));
                        default -> null;
                    };
            if (result == null) {
                write(object(
                        "id", id, "error", object("code", -32601, "message", "Unsupported client request: " + method)));
            } else {
                write(object("id", id, "result", result));
            }
        }

        private void handleNotification(JsonNode message) {
            String method = message.path("method").asText();
            JsonNode params = message.path("params");
            String threadId = params.path("threadId").asText(null);
            String turnId = turnId(params);
            Map<String, Long> usage = extractUsage(method, params);
            if (Objects.equals(method, "account/rateLimits/updated")) {
                synchronized (rateLimitState) {
                    JsonNode update = params.path("rateLimits");
                    JsonNode payload = mergedRateLimits(update);
                    boolean accepted = listener.onEventAndReportAccepted(event(
                            method,
                            workerIdentity,
                            process.pid(),
                            threadId,
                            turnId,
                            summarize(params),
                            usage,
                            payload));
                    if (accepted && update.isObject()) {
                        rateLimitState.latest = payload.deepCopy();
                    }
                }
                return;
            }
            listener.onEvent(event(
                    method,
                    workerIdentity,
                    process.pid(),
                    threadId,
                    turnId,
                    notificationSummary(method, params),
                    usage,
                    params));
            if (Objects.equals(method, "turn/completed") && turnId != null) {
                CompletableFuture<JsonNode> future;
                synchronized (turnCompletionLock) {
                    future = turnCompletions.remove(turnId);
                    if (future == null) {
                        completedTurns.put(turnId, params);
                        return;
                    }
                }
                future.complete(params);
            } else if (isTurnFailure(method) && turnId != null) {
                completeTurnExceptionally(turnId, terminalTurnFailure("turn_failed", turnError(params), params));
            } else if (isTurnCancellation(method) && turnId != null) {
                completeTurnExceptionally(
                        turnId, new CodexAppServerTerminalException("turn_cancelled", summarize(params)));
            } else if (isTerminalError(method, params) && turnId != null) {
                completeTurnExceptionally(
                        turnId, terminalTurnFailure("turn_failed", params.path("error"), params.path("error")));
            }
        }

        private JsonNode mergedRateLimits(JsonNode update) {
            if (!update.isObject()) {
                JsonNode latest = rateLimitState.latest;
                return latest == null ? json.getNodeFactory().nullNode() : latest.deepCopy();
            }
            JsonNode previous = rateLimitState.latest;
            ObjectNode merged = previous != null && previous.isObject() ? ((ObjectNode) previous).deepCopy() : object();
            mergeAvailableRateLimitMetadata(merged, update);
            mergeRateLimitWindow(merged, update, "primary");
            mergeRateLimitWindow(merged, update, "secondary");
            return merged;
        }

        private void mergeAvailableRateLimitMetadata(ObjectNode merged, JsonNode update) {
            update.fieldNames().forEachRemaining(name -> {
                if (!Objects.equals(name, "primary") && !Objects.equals(name, "secondary")) {
                    mergeAvailableField(merged, update, name);
                }
            });
        }

        private void mergeRateLimitWindow(ObjectNode merged, JsonNode update, String name) {
            JsonNode incoming = update.get(name);
            if (incoming == null || incoming.isNull() || !incoming.isObject()) {
                return;
            }
            JsonNode previous = merged.get(name);
            ObjectNode window = previous != null && previous.isObject() ? ((ObjectNode) previous).deepCopy() : object();
            incoming.fieldNames().forEachRemaining(field -> mergeAvailableField(window, incoming, field));
            merged.set(name, window);
        }

        private void mergeAvailableField(ObjectNode merged, JsonNode update, String name) {
            JsonNode available = update.get(name);
            if (available != null && !available.isNull()) {
                merged.set(name, available.deepCopy());
            }
        }

        private CodexAppServerTerminalException terminalTurnFailure(
                String code, JsonNode error, JsonNode messageFallback) {
            if (isUsageLimitError(error)) {
                return new CodexAppServerTerminalException(
                        code,
                        turnErrorMessage(error),
                        AgentRunResult.FailureCategory.CODEX_USAGE_LIMIT,
                        usageLimitRetryNotBefore());
            }
            return new CodexAppServerTerminalException(code, summarize(messageFallback));
        }

        private JsonNode turnError(JsonNode params) {
            JsonNode error = params.path("turn").path("error");
            return error.isMissingNode() || error.isNull() ? params : error;
        }

        private String notificationSummary(String method, JsonNode params) {
            JsonNode error =
                    switch (method) {
                        case "turn/completed", "turn/failed" -> turnError(params);
                        case "error" -> params.path("error");
                        default -> json.getNodeFactory().missingNode();
                    };
            return isUsageLimitError(error) ? turnErrorMessage(error) : summarize(params);
        }

        private Optional<Instant> usageLimitRetryNotBefore() {
            JsonNode snapshot;
            synchronized (rateLimitState) {
                snapshot = rateLimitState.latest;
            }
            if (snapshot == null || !snapshot.isObject()) {
                return Optional.empty();
            }
            Instant now = clock.instant();
            return Stream.of(snapshot.path("primary"), snapshot.path("secondary"))
                    .map(window -> exhaustedWindowReset(window, now))
                    .flatMap(Optional::stream)
                    .max(Instant::compareTo);
        }

        private Optional<Instant> exhaustedWindowReset(JsonNode window, Instant now) {
            JsonNode usedPercent = window.path("usedPercent");
            JsonNode resetsAt = window.path("resetsAt");
            if (!window.isObject()
                    || !usedPercent.isIntegralNumber()
                    || !usedPercent.canConvertToInt()
                    || usedPercent.intValue() < 100
                    || !resetsAt.isIntegralNumber()
                    || !resetsAt.canConvertToLong()) {
                return Optional.empty();
            }
            try {
                Instant reset = Instant.ofEpochSecond(resetsAt.longValue());
                return reset.isAfter(now) ? Optional.of(reset) : Optional.empty();
            } catch (DateTimeException | ArithmeticException e) {
                return Optional.empty();
            }
        }

        private boolean isTurnFailure(String method) {
            return Objects.equals(method, "turn/failed");
        }

        private boolean isTurnCancellation(String method) {
            return Objects.equals(method, "turn/cancelled") || Objects.equals(method, "turn/canceled");
        }

        private boolean isTerminalError(String method, JsonNode params) {
            return Objects.equals(method, "error") && !params.path("willRetry").asBoolean(false);
        }

        private void completeTurnExceptionally(String turnId, Throwable failure) {
            CompletableFuture<JsonNode> future;
            synchronized (turnCompletionLock) {
                future = turnCompletions.remove(turnId);
                if (future == null) {
                    terminalTurnFailures.put(turnId, failure);
                    return;
                }
            }
            future.completeExceptionally(failure);
        }

        private void failPending(Throwable failure) {
            readerFailure.compareAndSet(null, failure);
            responses.values().forEach(future -> future.completeExceptionally(failure));
            synchronized (turnCompletionLock) {
                turnCompletions.values().forEach(future -> future.completeExceptionally(failure));
                turnCompletions.clear();
            }
        }

        private String turnId(JsonNode params) {
            String nestedTurnId = params.at("/turn/id").asText(null);
            return nestedTurnId == null ? params.path("turnId").asText(null) : nestedTurnId;
        }

        private Exception unwrapReaderFailure(ExecutionException failure) {
            return unwrapReaderFailure(failure.getCause(), failure);
        }

        private Exception unwrapReaderFailure(Throwable failure) {
            return unwrapReaderFailure(failure, null);
        }

        private Exception unwrapReaderFailure(Throwable failure, Throwable wrapper) {
            if (failure instanceof CodexAppServerTerminalException terminalFailure) {
                addSuppressedWrapper(terminalFailure, wrapper);
                return terminalFailure;
            }
            return new IOException("app-server reader failed", wrapper == null ? failure : wrapper);
        }

        private Exception unwrapResponseFailure(ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof CodexAppServerTerminalException terminalFailure) {
                terminalFailure.addSuppressed(failure);
                return terminalFailure;
            }
            if (cause instanceof Exception exception) {
                exception.addSuppressed(failure);
                return exception;
            }
            return new IOException("app-server request failed", failure);
        }

        private void addSuppressedWrapper(Exception exception, Throwable wrapper) {
            if (wrapper != null) {
                exception.addSuppressed(wrapper);
            }
        }

        private Map<String, Long> extractUsage(String method, JsonNode params) {
            if (!"thread/tokenUsage/updated".equals(method)) {
                return Map.of();
            }
            JsonNode usage = params.path("usage");
            if (usage.isMissingNode() || usage.isNull() || usage.isEmpty()) {
                usage = params.path("tokenUsage").path("total");
            }
            return Map.of(
                    "input_tokens",
                    usage.path("inputTokens").asLong(usage.path("input_tokens").asLong(0)),
                    "output_tokens",
                    usage.path("outputTokens")
                            .asLong(usage.path("output_tokens").asLong(0)),
                    "total_tokens",
                    usage.path("totalTokens").asLong(usage.path("total_tokens").asLong(0)));
        }

        @Override
        public void close() {
            closeWriter();
            process.destroy();
            try {
                if (!process.waitFor(Duration.ofSeconds(2))) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }

        private void closeWriter() {
            synchronized (writerLock) {
                try {
                    writer.close();
                } catch (IOException e) {
                    LOG.debugf("codex_stdin pid=%d outcome=close_failed reason=%s", process.pid(), e.getMessage());
                }
            }
        }
    }
}
