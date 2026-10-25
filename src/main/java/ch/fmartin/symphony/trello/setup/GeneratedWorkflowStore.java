package ch.fmartin.symphony.trello.setup;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.NullMarked;

/// Version provenance for generated workflow bodies (ADR 0095). The store keeps one record per
/// workflow path: the latest body that Symphony generated, migrated, or confirmed as current for that
/// workflow, the version that produced it, and the inputs it was rendered from. It lives beside the
/// connected-board manifest, so it follows the same config directory and survives uninstall.
@NullMarked
final class GeneratedWorkflowStore {
    static final String FILE_NAME = "generated-workflows.json";
    /// Written with every save so that a future incompatible store format can be told apart; readers
    /// of format 1 do not need it because they ignore unknown fields.
    static final int FORMAT_VERSION = 1;

    private final ObjectMapper json;
    private final Path storePath;

    private GeneratedWorkflowStore(Path storePath) {
        this.storePath = storePath.toAbsolutePath().normalize();
        this.json = ConnectedBoardRepository.jsonMapper();
    }

    static GeneratedWorkflowStore inConfigDir(Path configDir) {
        return new GeneratedWorkflowStore(configDir.resolve(FILE_NAME));
    }

    static GeneratedWorkflowStore besideManifest(Path manifestPath) {
        Path manifest = manifestPath.toAbsolutePath().normalize();
        Path store = manifest.resolveSibling(FILE_NAME);
        if (PathsEqual.samePath(store, manifest)) {
            throw new IllegalArgumentException(
                    "cannot record generated workflows: use a manifest filename other than generated-workflows.json");
        }
        return new GeneratedWorkflowStore(store);
    }

    /// Records the body that setup just generated with the running Symphony version. A failure here
    /// must not undo a finished board setup, so it only returns a warning for the caller to print; a
    /// later migration check then treats the workflow as having no recorded provenance.
    static Optional<String> recordGenerated(Path manifestPath, Path workflowPath, GeneratedWorkflowBodyInputs inputs) {
        try {
            besideManifest(manifestPath)
                    .put(GeneratedWorkflowRecord.current(workflowPath, SymphonyVersion.currentText(), inputs));
            return Optional.empty();
        } catch (IOException | RuntimeException e) {
            return Optional.of("  WARN  Could not record which Symphony version generated "
                    + PathNames.fileName(workflowPath) + " in the provenance store. Later updates will ask for a"
                    + " manual workflow migration if the generated workflow changes.");
        }
    }

    Path storePath() {
        return storePath;
    }

    List<GeneratedWorkflowRecord> records() throws IOException {
        return records(load());
    }

    static Optional<GeneratedWorkflowRecord> find(List<GeneratedWorkflowRecord> records, Path workflowPath) {
        return records.stream()
                .filter(record -> PathsEqual.samePath(record.workflowPath(), workflowPath))
                .findAny();
    }

    /// Replaces the record for the same workflow path, or adds it.
    void put(GeneratedWorkflowRecord record) throws IOException {
        ObjectNode document = load();
        List<GeneratedWorkflowRecord> stored = records(document);
        ArrayNode workflows = json.createArrayNode();
        // Keep untouched records as JSON so an older app preserves newer generator inputs.
        for (int index = 0; index < stored.size(); index++) {
            if (!PathsEqual.samePath(stored.get(index).workflowPath(), record.workflowPath())) {
                workflows.add(document.path("workflows").get(index));
            }
        }
        workflows.add(json.valueToTree(record));
        document.put("formatVersion", FORMAT_VERSION);
        document.set("workflows", workflows);
        save(document);
    }

    private List<GeneratedWorkflowRecord> records(ObjectNode document) throws IOException {
        return json.treeToValue(document, StoredRecords.class).workflows();
    }

    private ObjectNode load() throws IOException {
        if (!Files.isRegularFile(storePath)) {
            return json.createObjectNode();
        }
        try {
            JsonNode document = json.readTree(storePath.toFile());
            if (!(document instanceof ObjectNode object)) {
                throw new IOException("cannot read generated workflow store: expected a JSON object");
            }
            return object;
        } catch (JsonProcessingException e) {
            throw new IOException("cannot read generated workflow store: expected valid JSON", e);
        }
    }

    private void save(ObjectNode records) throws IOException {
        Path parent = storePath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temporary = AtomicFiles.siblingTemporaryFile(storePath);
        try {
            json.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), records);
            AtomicFiles.replace(temporary, storePath);
        } finally {
            AtomicFiles.deleteQuietly(temporary);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record StoredRecords(int formatVersion, List<GeneratedWorkflowRecord> workflows) {
        StoredRecords {
            workflows = workflows == null ? List.of() : List.copyOf(workflows);
        }
    }
}
