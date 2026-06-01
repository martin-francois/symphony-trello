package ch.fmartin.symphony.trello.setup;

import static com.google.common.base.Preconditions.checkArgument;

import ch.fmartin.symphony.trello.process.ProcessEnvironment;
import ch.fmartin.symphony.trello.setup.TrelloBoardSetup.CodexModelDefaults;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

final class CodexModelDefaultsResolver {
    private static final System.Logger LOG = System.getLogger(CodexModelDefaultsResolver.class.getName());
    private static final String CLIENT_NAME = "symphony-trello-setup";
    private static final String DEVELOPMENT_VERSION = "development";
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);
    private static final int MAX_MODEL_LIST_PAGES = 20;
    private static final Duration PROCESS_STOP_TIMEOUT = Duration.ofSeconds(2);

    private final ObjectMapper json;
    private final List<String> command;
    private final Map<String, String> environment;
    private final Duration readTimeout;

    CodexModelDefaultsResolver(ObjectMapper json) {
        this(json, List.of("codex", "app-server"));
    }

    CodexModelDefaultsResolver(ObjectMapper json, List<String> command) {
        this(json, command, Map.of());
    }

    CodexModelDefaultsResolver(ObjectMapper json, List<String> command, Map<String, String> environment) {
        this(json, command, environment, READ_TIMEOUT);
    }

    CodexModelDefaultsResolver(
            ObjectMapper json, List<String> command, Map<String, String> environment, Duration readTimeout) {
        this.json = json;
        this.command = List.copyOf(command);
        this.environment = Map.copyOf(environment);
        this.readTimeout = readTimeout;
    }

    CodexModelDefaults resolve() {
        return resolveSelectionDefaults().defaults();
    }

    CodexModelSelectionDefaults resolveSelectionDefaults() {
        try {
            return queryAppServer();
        } catch (IOException | InterruptedException | RuntimeException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return CodexModelSelectionDefaults.of(CodexModelDefaults.unsupportedFirstClassFields());
        }
    }

    private CodexModelSelectionDefaults queryAppServer() throws IOException, InterruptedException {
        ProcessBuilder processBuilder = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD);
        processBuilder.environment().putAll(environment);
        ProcessEnvironment.removeDefaultSecrets(processBuilder);
        Process process = processBuilder.start();
        AppServerResponseReader reader = new AppServerResponseReader(process.getInputStream());
        try (var writer =
                new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8))) {
            send(
                    writer,
                    request(
                            1,
                            "initialize",
                            object(
                                    "clientInfo",
                                    object("name", CLIENT_NAME, "version", implementationVersion()),
                                    "capabilities",
                                    object())));
            readResponse(reader, 1);
            send(writer, notification("initialized", object()));
            ArrayNode models = json.createArrayNode();
            String cursor = null;
            for (int page = 0; page < MAX_MODEL_LIST_PAGES; page++) {
                int id = page + 2;
                send(writer, request(id, "model/list", modelListParams(cursor)));
                JsonNode response = readResponse(reader, id);
                JsonNode result = response.path("result");
                JsonNode pageModels = result.path("data");
                if (pageModels.isArray()) {
                    pageModels.forEach(models::add);
                }
                cursor = result.path("nextCursor").asText(null);
                if (blank(cursor)) {
                    return fromModelList(models);
                }
            }
            throw new IOException("Codex app-server model list did not finish pagination.");
        } finally {
            try {
                stop(process);
            } finally {
                reader.close();
            }
        }
    }

    private ObjectNode modelListParams(String cursor) {
        ObjectNode params = object("includeHidden", false);
        if (!blank(cursor)) {
            params.put("cursor", cursor);
        }
        return params;
    }

    private CodexModelSelectionDefaults fromModelList(JsonNode models) {
        if (!models.isArray() || models.isEmpty()) {
            return CodexModelSelectionDefaults.of(CodexModelDefaults.fallback());
        }
        Map<String, String> reasoningEffortsByModel = new LinkedHashMap<>();
        JsonNode selected = null;
        boolean selectedIsDefault = false;
        for (JsonNode model : models) {
            String modelName = model.path("model").asText(null);
            String reasoningEffort = model.path("defaultReasoningEffort").asText(null);
            if (blank(modelName) || blank(reasoningEffort)) {
                continue;
            }
            reasoningEffortsByModel.put(modelName, reasoningEffort);
            boolean modelIsDefault = model.path("isDefault").asBoolean(false);
            if (selected == null || (!selectedIsDefault && modelIsDefault)) {
                selected = model;
                selectedIsDefault = modelIsDefault;
            }
        }
        if (selected == null) {
            return CodexModelSelectionDefaults.of(CodexModelDefaults.fallback());
        }
        String modelName = selected.path("model").asText(null);
        String reasoningEffort = selected.path("defaultReasoningEffort").asText(null);
        if (blank(modelName) || blank(reasoningEffort)) {
            return CodexModelSelectionDefaults.of(CodexModelDefaults.fallback());
        }
        return new CodexModelSelectionDefaults(
                new CodexModelDefaults(modelName, reasoningEffort), reasoningEffortsByModel);
    }

    private JsonNode readResponse(AppServerResponseReader reader, int id) throws IOException {
        long deadline = System.nanoTime() + readTimeout.toNanos();
        while (true) {
            long remainingNanos = deadline - System.nanoTime();
            if (remainingNanos <= 0) {
                throw new IOException("Timed out waiting for Codex app-server response");
            }
            String line = reader.readLine(Duration.ofNanos(remainingNanos));
            JsonNode message = json.readTree(line);
            if (message.path("id").asInt(-1) == id) {
                if (message.has("error")) {
                    throw new IOException("Codex app-server returned error: " + message.path("error"));
                }
                return message;
            }
        }
    }

    private void send(BufferedWriter writer, JsonNode message) throws IOException {
        writer.write(json.writeValueAsString(message));
        writer.newLine();
        writer.flush();
    }

    private ObjectNode request(int id, String method, JsonNode params) {
        return object("id", id, "method", method, "params", params);
    }

    private ObjectNode notification(String method, JsonNode params) {
        return object("method", method, "params", params);
    }

    private ObjectNode object(Object... entries) {
        ObjectNode node = json.createObjectNode();
        checkArgument(entries.length % 2 == 0, "Object entries must be key/value pairs");
        for (int i = 0; i < entries.length; i += 2) {
            String key = (String) entries[i];
            Object value = entries[i + 1];
            if (value instanceof JsonNode jsonNode) {
                node.set(key, jsonNode);
            } else if (value instanceof String string) {
                node.put(key, string);
            } else if (value instanceof Integer integer) {
                node.put(key, integer);
            } else if (value instanceof Boolean bool) {
                node.put(key, bool);
            } else {
                node.set(key, json.valueToTree(value));
            }
        }
        return node;
    }

    private static void stop(Process process) throws InterruptedException {
        List<ProcessHandle> descendants = process.descendants().toList();
        process.destroy();
        descendants.forEach(ProcessHandle::destroy);
        if (!waitForExit(process)) {
            process.destroyForcibly();
            waitForExit(process);
        }
        for (ProcessHandle descendant : descendants) {
            if (descendant.isAlive() && !waitForExit(descendant)) {
                descendant.destroyForcibly();
                waitForExit(descendant);
            }
        }
    }

    private static boolean waitForExit(Process process) throws InterruptedException {
        return process.waitFor(PROCESS_STOP_TIMEOUT.toNanos(), TimeUnit.NANOSECONDS);
    }

    private static boolean waitForExit(ProcessHandle process) throws InterruptedException {
        try {
            process.onExit().get(PROCESS_STOP_TIMEOUT.toNanos(), TimeUnit.NANOSECONDS);
            return true;
        } catch (ExecutionException e) {
            return !process.isAlive();
        } catch (TimeoutException e) {
            return false;
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String implementationVersion() {
        String version = CodexModelDefaultsResolver.class.getPackage().getImplementationVersion();
        return blank(version) ? DEVELOPMENT_VERSION : version;
    }

    private static final class AppServerResponseReader {
        private static final Object END_OF_STREAM = new Object();

        private final BlockingQueue<Object> lines = new LinkedBlockingQueue<>();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final BufferedReader reader;

        private AppServerResponseReader(InputStream input) {
            reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
            Thread.ofVirtual().name("codex-model-defaults-reader").start(this::readStdout);
        }

        private String readLine(Duration timeout) throws IOException {
            try {
                Object item = lines.poll(timeout.toNanos(), TimeUnit.NANOSECONDS);
                if (item == null) {
                    throw new IOException("Timed out waiting for Codex app-server response");
                }
                if (item == END_OF_STREAM) {
                    Throwable cause = failure.get();
                    if (cause instanceof IOException ioException) {
                        throw ioException;
                    }
                    if (cause != null) {
                        throw new IOException("Codex app-server response reader failed", cause);
                    }
                    throw new IOException("Codex app-server closed stdout");
                }
                return (String) item;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while waiting for Codex app-server response", e);
            }
        }

        private void readStdout() {
            try {
                String line = reader.readLine();
                while (line != null) {
                    lines.add(line);
                    line = reader.readLine();
                }
            } catch (Exception e) {
                failure.set(e);
            } finally {
                lines.add(END_OF_STREAM);
            }
        }

        private void close() {
            try {
                reader.close();
            } catch (IOException e) {
                LOG.log(System.Logger.Level.DEBUG, "Codex app-server response reader cleanup failed", e);
            }
        }
    }
}
