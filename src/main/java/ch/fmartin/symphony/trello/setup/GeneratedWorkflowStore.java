package ch.fmartin.symphony.trello.setup;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
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
        return new GeneratedWorkflowStore(
                manifestPath.toAbsolutePath().normalize().resolveSibling(FILE_NAME));
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
        return load().workflows();
    }

    static Optional<GeneratedWorkflowRecord> find(List<GeneratedWorkflowRecord> records, Path workflowPath) {
        return records.stream()
                .filter(record -> PathsEqual.samePath(record.workflowPath(), workflowPath))
                .findAny();
    }

    /// Replaces the record for the same workflow path, or adds it.
    void put(GeneratedWorkflowRecord record) throws IOException {
        StoredRecords stored = load();
        save(new StoredRecords(
                FORMAT_VERSION,
                Stream.concat(
                                stored.workflows().stream()
                                        .filter(existing ->
                                                !PathsEqual.samePath(existing.workflowPath(), record.workflowPath())),
                                Stream.of(record))
                        .toList()));
    }

    private StoredRecords load() throws IOException {
        if (!Files.isRegularFile(storePath)) {
            return new StoredRecords(FORMAT_VERSION, List.of());
        }
        try {
            StoredRecords stored = json.readValue(storePath.toFile(), StoredRecords.class);
            if (stored == null) {
                throw new IOException("Generated workflow store must be a JSON object.");
            }
            return stored;
        } catch (JsonProcessingException e) {
            throw new IOException("Generated workflow store is not valid JSON.", e);
        }
    }

    private void save(StoredRecords records) throws IOException {
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
