package ch.fmartin.symphony.trello.agent;

import ch.fmartin.symphony.trello.config.ConfigException;
import ch.fmartin.symphony.trello.config.EffectiveConfig;
import ch.fmartin.symphony.trello.domain.Card;
import ch.fmartin.symphony.trello.process.ProcessEnvironment;
import ch.fmartin.symphony.trello.workspace.WorkspaceManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.jboss.logging.Logger;

@ApplicationScoped
public class CodexAppServerClient {
    private static final Logger LOG = Logger.getLogger(CodexAppServerClient.class);

    private final ObjectMapper json;
    private final TrelloHandoffToolHandler trelloTools;

    public CodexAppServerClient(ObjectMapper json, TrelloHandoffToolHandler trelloTools) {
        this.json = json;
        this.trelloTools = trelloTools;
    }

    public AgentRunResult runTurn(
            EffectiveConfig config,
            Card card,
            Path workspace,
            String prompt,
            String workerIdentity,
            AgentEventListener listener) {
        WorkspaceManager.requireInsideRoot(config.workspace().root(), workspace);
        Process process;
        try {
            ProcessBuilder processBuilder = new ProcessBuilder(
                            "bash", "-lc", config.codex().command())
                    .directory(workspace.toFile())
                    .redirectError(ProcessBuilder.Redirect.PIPE);
            ProcessEnvironment.removeDefaultSecrets(processBuilder);
            process = processBuilder.start();
        } catch (IOException e) {
            return AgentRunResult.fail("codex_not_found: " + e.getMessage());
        }

        AppServerSession session = new AppServerSession(process, config, card, workerIdentity, listener);
        try {
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

            JsonNode turn = request(
                    session,
                    "turn/start",
                    turnStartParams(config, threadId, workspace, prompt),
                    config.codex().readTimeout());
            String turnId = turn.at("/turn/id").asText(null);
            if (turnId == null) {
                return AgentRunResult.fail("codex_protocol_error: missing turn id");
            }
            listener.onEvent(
                    event("session_started", workerIdentity, process.pid(), threadId, turnId, null, Map.of(), turn));

            JsonNode completed =
                    session.awaitTurnCompleted(turnId, config.codex().turnTimeout());
            JsonNode error = completed.at("/turn/error");
            if (!error.isMissingNode() && !error.isNull()) {
                return AgentRunResult.fail("turn_failed: " + summarize(error));
            }
            return AgentRunResult.ok();
        } catch (TimeoutException e) {
            process.destroyForcibly();
            return AgentRunResult.fail("response_timeout: " + e.getMessage());
        } catch (Exception e) {
            process.destroyForcibly();
            return AgentRunResult.fail("codex_protocol_error: " + e.getMessage());
        } finally {
            session.close();
        }
    }

    private ObjectNode threadStartParams(EffectiveConfig config, Path workspace) {
        ObjectNode params = object("cwd", workspace.toString(), "serviceName", "symphony-trello", "ephemeral", true);
        putIfPresent(params, "approvalPolicy", config.codex().approvalPolicy());
        putIfPresent(params, "sandbox", config.codex().threadSandbox());
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
        putIfPresent(params, "sandboxPolicy", sandboxPolicy(config));
        return params;
    }

    private JsonNode sandboxPolicy(EffectiveConfig config) {
        if (config.codex().forceDangerFullAccess()) {
            return object("type", "dangerFullAccess");
        }
        JsonNode configured = config.codex().turnSandboxPolicy() == null
                ? null
                : json.valueToTree(config.codex().turnSandboxPolicy());
        if (config.codex().additionalWritableRoots().isEmpty()) {
            return configured;
        }
        if (configured == null || configured.isNull()) {
            ObjectNode policy = object("type", "workspaceWrite");
            policy.set("writableRoots", writableRoots(config));
            return policy;
        }
        if (configured.isObject()
                && "workspaceWrite".equals(configured.path("type").asText())) {
            ObjectNode merged = configured.deepCopy();
            ArrayNode writableRoots = merged.withArray("writableRoots");
            config.codex().additionalWritableRoots().stream()
                    .map(Path::toString)
                    .filter(root -> !containsText(writableRoots, root))
                    .forEach(writableRoots::add);
            return merged;
        }
        if (configured.isObject()
                && "dangerFullAccess".equals(configured.path("type").asText())) {
            return configured;
        }
        throw new ConfigException(
                "codex_sandbox_policy_conflict",
                "codex.additional_writable_roots requires a workspaceWrite or dangerFullAccess turn_sandbox_policy");
    }

    private ArrayNode writableRoots(EffectiveConfig config) {
        ArrayNode roots = json.createArrayNode();
        config.codex().additionalWritableRoots().stream().map(Path::toString).forEach(roots::add);
        return roots;
    }

    private static boolean containsText(ArrayNode values, String expected) {
        for (JsonNode value : values) {
            if (expected.equals(value.asText())) {
                return true;
            }
        }
        return false;
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

    private static AgentEvent event(
            String name,
            String workerIdentity,
            Long pid,
            String threadId,
            String turnId,
            String message,
            Map<String, Long> usage,
            JsonNode payload) {
        return new AgentEvent(name, Instant.now(), workerIdentity, pid, threadId, turnId, message, usage, payload);
    }

    private static String summarize(JsonNode node) {
        String message = node.path("message").asText(null);
        return message == null ? node.toString() : message;
    }

    private final class AppServerSession implements AutoCloseable {
        private final Process process;
        private final EffectiveConfig config;
        private final Card card;
        private final String workerIdentity;
        private final AgentEventListener listener;
        private final AtomicInteger ids = new AtomicInteger();
        private final Map<Integer, CompletableFuture<JsonNode>> responses = new ConcurrentHashMap<>();
        private final Map<String, CompletableFuture<JsonNode>> turnCompletions = new ConcurrentHashMap<>();
        private final Map<String, JsonNode> completedTurns = new ConcurrentHashMap<>();
        private final Object turnCompletionLock = new Object();
        private final AtomicReference<Throwable> readerFailure = new AtomicReference<>();
        private final CountDownLatch readerStarted = new CountDownLatch(1);
        private BufferedWriter writer;
        private Thread stdoutReader;
        private Thread stderrReader;

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
        }

        void start() throws IOException, InterruptedException {
            writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            stdoutReader = Thread.ofVirtual().name("codex-app-server-stdout").start(this::readStdout);
            stderrReader = Thread.ofVirtual().name("codex-app-server-stderr").start(this::readStderr);
            readerStarted.await(5, TimeUnit.SECONDS);
        }

        JsonNode request(String method, ObjectNode params, Duration timeout) throws Exception {
            Throwable failure = readerFailure.get();
            if (failure != null) {
                throw new IOException("app-server reader failed", failure);
            }
            int id = ids.incrementAndGet();
            CompletableFuture<JsonNode> future = new CompletableFuture<>();
            responses.put(id, future);
            ObjectNode request = object("id", id, "method", method, "params", params);
            write(request);
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }

        void notify(String method) throws IOException {
            write(object("method", method));
        }

        JsonNode awaitTurnCompleted(String turnId, Duration timeout) throws Exception {
            CompletableFuture<JsonNode> future;
            synchronized (turnCompletionLock) {
                JsonNode completed = completedTurns.remove(turnId);
                if (completed != null) {
                    return completed;
                }
                future = new CompletableFuture<>();
                turnCompletions.put(turnId, future);
            }
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }

        private synchronized void write(ObjectNode message) throws IOException {
            writer.write(json.writeValueAsString(message));
            writer.newLine();
            writer.flush();
        }

        private void readStdout() {
            readerStarted.countDown();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8), 10 * 1024 * 1024)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    handleMessage(json.readTree(line));
                }
            } catch (Throwable e) {
                readerFailure.set(e);
                responses.values().forEach(future -> future.completeExceptionally(e));
            }
        }

        private void readStderr() {
            try (BufferedReader reader =
                    new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    LOG.debugf("codex_stderr pid=%d message=%s", process.pid(), line);
                }
            } catch (IOException ignored) {
                // Stderr is diagnostic-only and must not fail the run by itself.
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
            String turnId = params.at("/turn/id").asText(null);
            Map<String, Long> usage = extractUsage(method, params);
            listener.onEvent(
                    event(method, workerIdentity, process.pid(), threadId, turnId, summarize(params), usage, params));
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
            }
        }

        private Map<String, Long> extractUsage(String method, JsonNode params) {
            if (!"thread/tokenUsage/updated".equals(method)) {
                return Map.of();
            }
            JsonNode usage = params.path("usage");
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
            process.destroy();
            try {
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
    }
}
