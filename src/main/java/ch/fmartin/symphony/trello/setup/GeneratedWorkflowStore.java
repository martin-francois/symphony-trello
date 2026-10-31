package ch.fmartin.symphony.trello.setup;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
        validateManifestPath(manifestPath);
        Path manifest = manifestPath.toAbsolutePath().normalize();
        return new GeneratedWorkflowStore(manifest.resolveSibling(FILE_NAME));
    }

    static void validateManifestPath(Path manifestPath) {
        Path manifest = manifestPath.toAbsolutePath();
        Path store = manifest.normalize().resolveSibling(FILE_NAME);
        boolean collision;
        try {
            collision =
                    prospectiveRealPath(manifest, new HashSet<>()).equals(prospectiveRealPath(store, new HashSet<>()))
                            || (Files.exists(manifest) && Files.exists(store) && Files.isSameFile(manifest, store));
        } catch (IOException e) {
            throw new TrelloBoardSetupException(
                    "setup_invalid_arguments",
                    "Cannot resolve the connected-board manifest path safely. Pass --manifest with a different path.",
                    e);
        }
        if (collision) {
            throw new TrelloBoardSetupException(
                    "setup_invalid_arguments",
                    "The connected-board manifest resolves to " + FILE_NAME
                            + " and that file is reserved for generated workflow provenance. Pass --manifest with "
                            + ConnectedBoardManifest.FILE_NAME + " or another filename instead.");
        }
    }

    /// Resolve aliases before setup creates either file, including a link to a missing target.
    private static Path prospectiveRealPath(Path path, Set<Path> visitedLinks) throws IOException {
        try {
            return path.toRealPath();
        } catch (NoSuchFileException e) {
            Path absolute = path.toAbsolutePath();
            Path parent = absolute.getParent();
            if (parent == null) {
                throw e;
            }
            // Resolve parents before interpreting "..": a directory symlink can change its meaning.
            Path prospective = prospectiveRealPath(parent, visitedLinks).resolve(absolute.getFileName());
            if (Files.isSymbolicLink(prospective)) {
                if (!visitedLinks.add(prospective)) {
                    throw new IOException("symbolic link cycle in manifest path", e);
                }
                return prospectiveRealPath(
                        prospective.resolveSibling(Files.readSymbolicLink(prospective)), visitedLinks);
            }
            return prospective;
        }
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
            if (object.has(ConnectedBoardManifest.BOARDS_FIELD)) {
                throw new TrelloBoardSetupException(
                        "setup_manifest_unavailable",
                        storePath + " contains a connected-board manifest, not generated workflow provenance. "
                                + "Back up this file and the workflows, then move the connected-board data to "
                                + ConnectedBoardManifest.FILE_NAME + " or another manifest filename before retrying. "
                                + "Keep the original backup and separate any provenance fields; no files were changed.");
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
