package ch.fmartin.symphony.trello.setup;

import static ch.fmartin.symphony.trello.TextCharacterMatchers.ISO_CONTROL_CHARACTERS;
import static com.google.common.base.Preconditions.checkArgument;

import ch.fmartin.symphony.trello.process.ProcessEnvironment;
import ch.fmartin.symphony.trello.setup.CodexModelSelectionDefaults.CatalogModel;
import ch.fmartin.symphony.trello.setup.CodexModelSelectionDefaults.ReasoningEffortOption;
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
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

final class CodexModelDefaultsResolver {
    private static final System.Logger LOG = System.getLogger(CodexModelDefaultsResolver.class.getName());
    private static final String CLIENT_NAME = "symphony-trello-setup";
    private static final String DEVELOPMENT_VERSION = "development";
    private static final String SYMPHONY_PREFERRED_CODEX_MODEL = "gpt-5.6-terra";
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
        var reader = new AppServerResponseReader(process.getInputStream());
        try (var writer = process.outputWriter(StandardCharsets.UTF_8)) {
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
                if (pageModels instanceof ArrayNode pageModelArray) {
                    models.addAll(pageModelArray);
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
        ObjectNode params = object("includeHidden", true);
        if (!blank(cursor)) {
            params.put("cursor", cursor);
        }
        return params;
    }

    private CodexModelSelectionDefaults fromModelList(JsonNode models) {
        List<CatalogEntry> entries = catalogEntries(models);
        Map<String, String> reasoningEffortsByModel = new LinkedHashMap<>();
        Map<String, List<ReasoningEffortOption>> reasoningEffortOptionsByModel = new LinkedHashMap<>();
        for (CatalogEntry entry : entries) {
            if (!blank(entry.defaultReasoningEffort())) {
                reasoningEffortsByModel.put(entry.model(), entry.defaultReasoningEffort());
            }
            if (!entry.reasoningEffortOptions().isEmpty()) {
                reasoningEffortOptionsByModel.put(entry.model(), entry.reasoningEffortOptions());
            }
        }
        Optional<CatalogEntry> recommended = recommendedEntry(entries);
        CodexModelDefaults defaults = recommended
                .map(entry -> CodexModelDefaults.partial(entry.model(), entry.defaultReasoningEffort()))
                .orElseGet(CodexModelDefaults::fallback);
        Optional<String> recommendedModel = recommended.map(CatalogEntry::model);
        List<CatalogModel> visibleModels = entries.stream()
                .filter(entry -> !entry.hidden())
                .map(entry -> new CatalogModel(
                        entry.model(),
                        entry.displayName(),
                        recommendedModel.map(entry.model()::equals).orElse(false)))
                .toList();
        return CodexModelSelectionDefaults.fromCatalog(
                defaults, visibleModels, reasoningEffortsByModel, reasoningEffortOptionsByModel);
    }

    /// Parses usable catalog entries in catalog order. A repeated model id keeps its first entry so
    /// the picker label, default selection, and reasoning metadata all come from the same entry.
    private static List<CatalogEntry> catalogEntries(JsonNode models) {
        Map<String, CatalogEntry> firstEntryPerModelInCatalogOrder = new LinkedHashMap<>();
        for (JsonNode model : models) {
            String modelName = validatedCatalogText(model, "model");
            if (!blank(modelName)) {
                // Parse every entry, including repeats, so unsafe metadata anywhere rejects the catalog.
                CatalogEntry entry = catalogEntry(modelName.strip(), model);
                firstEntryPerModelInCatalogOrder.putIfAbsent(entry.model(), entry);
            }
        }
        return List.copyOf(firstEntryPerModelInCatalogOrder.values());
    }

    private static CatalogEntry catalogEntry(String modelName, JsonNode model) {
        return new CatalogEntry(
                modelName,
                displayName(model),
                model.path("hidden").asBoolean(false),
                model.path("isDefault").asBoolean(false),
                validatedCatalogText(model, "defaultReasoningEffort"),
                supportedReasoningEfforts(model));
    }

    /// Returns the display name only when it is safe to print. The name is cosmetic, so an unsafe one
    /// is dropped and the picker shows the model id instead of rejecting the whole catalog.
    private static String displayName(JsonNode model) {
        String displayName = model.path("displayName").asText(null);
        return displayName == null || ISO_CONTROL_CHARACTERS.matchesAnyOf(displayName) ? null : displayName;
    }

    /// Applies the new-workflow precedence from ADR 0025: visible Terra, then the first visible
    /// Codex default, then the first visible entry. Catalog order decides between several defaults.
    private static Optional<CatalogEntry> recommendedEntry(List<CatalogEntry> entries) {
        List<CatalogEntry> visibleEntries =
                entries.stream().filter(entry -> !entry.hidden()).toList();
        return visibleEntries.stream()
                .filter(entry -> SYMPHONY_PREFERRED_CODEX_MODEL.equals(entry.model()))
                .findAny()
                .or(() ->
                        visibleEntries.stream().filter(CatalogEntry::isDefault).findFirst())
                .or(() -> visibleEntries.stream().findFirst());
    }

    private static List<ReasoningEffortOption> supportedReasoningEfforts(JsonNode model) {
        return model.path("supportedReasoningEfforts")
                .valueStream()
                .filter(option -> !blank(option.path("reasoningEffort").asText(null)))
                .map(CodexModelDefaultsResolver::reasoningEffortOption)
                .toList();
    }

    private static ReasoningEffortOption reasoningEffortOption(JsonNode option) {
        return new ReasoningEffortOption(
                option.path("reasoningEffort").asText(null),
                option.path("description").asText(null));
    }

    private static String validatedCatalogText(JsonNode value, String fieldName) {
        String text = value.path(fieldName).asText(null);
        CodexModelSelectionDefaults.checkNoControlCharacters(text, fieldName);
        return text;
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
            var key = (String) entries[i];
            Object value = entries[i + 1];
            switch (value) {
                case JsonNode jsonNode -> node.set(key, jsonNode);
                case String string -> node.put(key, string);
                case Integer integer -> node.put(key, integer);
                case Boolean bool -> node.put(key, bool);
                case null, default -> node.set(key, json.valueToTree(value));
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
        return process.waitFor(PROCESS_STOP_TIMEOUT);
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

    private record CatalogEntry(
            String model,
            String displayName,
            boolean hidden,
            boolean isDefault,
            String defaultReasoningEffort,
            List<ReasoningEffortOption> reasoningEffortOptions) {}

    private static final class AppServerResponseReader {
        private static final Object END_OF_STREAM = new Object();

        private final BlockingQueue<Object> lines = new LinkedBlockingQueue<>();
        // Enqueuing END_OF_STREAM publishes the preceding failure assignment to the reader.
        private Throwable failure;
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
                    Throwable cause = failure;
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
                failure = e;
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
