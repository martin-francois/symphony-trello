package ch.fmartin.symphony.trello.setup;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.StreamSupport;

final class ConnectedBoardRepository {
    private final ObjectMapper json;
    private final Path manifestPath;

    ConnectedBoardRepository(Path manifestPath) {
        this(manifestPath, jsonMapper());
    }

    ConnectedBoardRepository(Path manifestPath, ObjectMapper json) {
        this.manifestPath = manifestPath;
        this.json = json;
    }

    ConnectedBoardManifest load() throws IOException {
        if (!Files.isRegularFile(manifestPath)) {
            return new ConnectedBoardManifest(List.of());
        }
        try {
            ConnectedBoardManifest manifest = json.readValue(manifestPath.toFile(), ConnectedBoardManifest.class);
            if (manifest == null) {
                // A literal null document is valid JSON, so readValue returns null instead of
                // failing; callers must never have to null-check an expected config problem.
                throw invalidManifestContent(List.of("Connected-board manifest must be a JSON object."));
            }
            return manifest;
        } catch (JsonProcessingException e) {
            // A hand-edited or corrupted manifest is an expected local configuration problem, not
            // an unexpected failure that needs a troubleshooting report or a raw parser message.
            throw new TrelloBoardSetupException(
                    "setup_manifest_unavailable",
                    "Connected-board manifest is not valid JSON:\n  "
                            + manifestPath.toAbsolutePath().normalize()
                            + "\nRepair or remove "
                            + ConnectedBoardManifest.FILE_NAME
                            + ", then rerun the command.",
                    e);
        }
    }

    ManifestLoadResult loadForCheck() throws IOException {
        if (!Files.isRegularFile(manifestPath)) {
            return new ManifestLoadResult(new ConnectedBoardManifest(List.of()), List.of());
        }
        JsonNode root;
        try {
            root = json.readTree(manifestPath.toFile());
        } catch (JsonProcessingException e) {
            return new ManifestLoadResult(
                    new ConnectedBoardManifest(List.of()),
                    List.of("Connected-board manifest is not valid JSON."),
                    false);
        }
        List<String> warnings = manifestShapeWarnings(root);
        if (root == null || !root.isObject() || !root.path("boards").isArray()) {
            return new ManifestLoadResult(new ConnectedBoardManifest(List.of()), warnings, false);
        }
        // Reject non-object rows before treeToValue: Jackson maps a null row to a null list
        // element, and downstream board iteration must never see a null board.
        if (hasNonObjectBoardRow(root)) {
            return new ManifestLoadResult(new ConnectedBoardManifest(List.of()), unloadable(warnings), false);
        }
        try {
            return new ManifestLoadResult(json.treeToValue(root, ConnectedBoardManifest.class), warnings);
        } catch (IOException | RuntimeException e) {
            return new ManifestLoadResult(new ConnectedBoardManifest(List.of()), unloadable(warnings), false);
        }
    }

    private static List<String> unloadable(List<String> warnings) {
        return append(warnings, "Connected-board manifest contains invalid values and could not be loaded for checks.");
    }

    /**
     * Strict load for lifecycle and setup commands: any invalid manifest shape or incomplete board
     * row is an expected local configuration error here, never a null dereference deeper in the
     * command. Only diagnostics and setup-local check load more leniently, via loadForCheck().
     */
    ConnectedBoardManifest loadForLifecycle() throws IOException {
        ConnectedBoardManifest manifest = load();
        if (!Files.isRegularFile(manifestPath)) {
            return manifest;
        }
        List<String> warnings = loadForCheck().warnings();
        if (!warnings.isEmpty()) {
            throw invalidManifestContent(warnings);
        }
        return manifest;
    }

    private TrelloBoardSetupException invalidManifestContent(List<String> warnings) {
        return new TrelloBoardSetupException(
                "setup_manifest_unavailable",
                "Connected-board manifest is not valid connected-board JSON:\n  "
                        + manifestPath.toAbsolutePath().normalize()
                        + "\n  - " + String.join("\n  - ", warnings)
                        + "\nRepair or remove "
                        + ConnectedBoardManifest.FILE_NAME
                        + ", then rerun the command.");
    }

    void save(ConnectedBoardManifest manifest) throws IOException {
        Path parent = manifestPath.toAbsolutePath().normalize().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        json.writerWithDefaultPrettyPrinter().writeValue(manifestPath.toFile(), manifest);
    }

    void validateWritable() throws IOException {
        Path absolute = manifestPath.toAbsolutePath().normalize();
        if (Files.exists(absolute) && !Files.isRegularFile(absolute)) {
            throw new IOException("Connected-board manifest path is not a regular file.");
        }
        if (Files.isRegularFile(absolute)) {
            try (FileChannel ignored = FileChannel.open(absolute, StandardOpenOption.WRITE)) {
                return;
            }
        }
        Path parent = absolute.getParent();
        if (parent == null) {
            return;
        }
        Files.createDirectories(parent);
        Path probe = Files.createTempFile(parent, ".connected-boards-write-probe-", ".tmp");
        Files.deleteIfExists(probe);
    }

    static void validateManifestPathForCheck(Path manifestPath) {
        CliInputValidation.rejectDirectoryPath("--manifest", manifestPath);
        rejectNonDirectoryManifestParent(manifestPath);
    }

    static void validateManifestPathForSetup(Path manifestPath) {
        validateManifestPathForCheck(manifestPath);
        if (Files.exists(manifestPath) && !Files.isRegularFile(manifestPath)) {
            throw invalidManifestPath();
        }
        if (!Files.isRegularFile(manifestPath)) {
            return;
        }
        try {
            ManifestLoadResult loadResult = new ConnectedBoardRepository(manifestPath).loadForCheck();
            if (!loadResult.warnings().isEmpty()) {
                throw invalidManifestPath();
            }
        } catch (IOException e) {
            throw invalidManifestPath(e);
        }
    }

    Path manifestPath() {
        return manifestPath;
    }

    private static TrelloBoardSetupException invalidManifestPath() {
        return new TrelloBoardSetupException(
                "setup_invalid_arguments", "--manifest must be a readable connected-board manifest JSON file.");
    }

    private static TrelloBoardSetupException invalidManifestPath(IOException cause) {
        return new TrelloBoardSetupException(
                "setup_invalid_arguments", "--manifest must be a readable connected-board manifest JSON file.", cause);
    }

    private static void rejectNonDirectoryManifestParent(Path manifestPath) {
        Path parent = manifestPath.toAbsolutePath().normalize().getParent();
        if (parent != null && Files.exists(parent) && !Files.isDirectory(parent)) {
            throw invalidManifestPath();
        }
    }

    private static boolean hasNonObjectBoardRow(JsonNode root) {
        JsonNode boards = root.path("boards");
        return StreamSupport.stream(boards.spliterator(), false).anyMatch(board -> !board.isObject());
    }

    private static List<String> manifestShapeWarnings(JsonNode root) {
        if (root == null || !root.isObject()) {
            return List.of("Connected-board manifest must be a JSON object.");
        }
        JsonNode boards = root.path("boards");
        if (!boards.isArray()) {
            return List.of("Connected-board manifest field boards must be an array.");
        }
        List<String> warnings = new ArrayList<>();
        for (int index = 0; index < boards.size(); index++) {
            JsonNode board = boards.get(index);
            if (!board.isObject()) {
                warnings.add("Connected-board manifest entry " + (index + 1) + " must be an object.");
                continue;
            }
            String label = boardLabel(board, index);
            requireText(board, "boardId", label, warnings);
            requireText(board, "boardKey", label, warnings);
            requireText(board, "boardName", label, warnings);
            requireText(board, "boardUrl", label, warnings);
            requireText(board, "workflowPath", label, warnings);
            requireText(board, "envPath", label, warnings);
            requireText(board, "workspaceRoot", label, warnings);
            requireBooleanIfPresent(board, "githubEnabled", label, warnings);
            requireBooleanIfPresent(board, "dangerFullAccess", label, warnings);
            requireServerPort(board, label, warnings);
            requireWritableRoots(board, label, warnings);
        }
        return warnings;
    }

    private static void requireText(JsonNode board, String field, String label, List<String> warnings) {
        JsonNode value = board.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            warnings.add(
                    "Connected-board manifest entry " + label + " field " + field + " must be a non-blank string.");
        }
    }

    private static void requireBooleanIfPresent(JsonNode board, String field, String label, List<String> warnings) {
        JsonNode value = board.get(field);
        if (value != null && !value.isBoolean()) {
            warnings.add("Connected-board manifest entry " + label + " field " + field + " must be true or false.");
        }
    }

    private static void requireServerPort(JsonNode board, String label, List<String> warnings) {
        JsonNode value = board.get("serverPort");
        if (value == null || !value.isIntegralNumber()) {
            warnings.add("Connected-board manifest entry " + label + " field serverPort must be a JSON number from "
                    + LocalPort.MIN + " to " + LocalPort.MAX + ".");
            return;
        }
        int port = value.asInt();
        if (!LocalPort.isValid(port)) {
            warnings.add("Connected-board manifest entry " + label + " field serverPort must be between "
                    + LocalPort.MIN + " and " + LocalPort.MAX + ".");
        }
    }

    private static void requireWritableRoots(JsonNode board, String label, List<String> warnings) {
        JsonNode roots = board.get("additionalWritableRoots");
        if (roots == null) {
            return;
        }
        if (!roots.isArray()) {
            warnings.add("Connected-board manifest entry " + label
                    + " field additionalWritableRoots must be an array of path strings.");
            return;
        }
        boolean hasInvalidRoot = StreamSupport.stream(roots.spliterator(), false)
                .anyMatch(root -> !root.isTextual() || root.asText().isBlank());
        if (hasInvalidRoot) {
            warnings.add("Connected-board manifest entry " + label
                    + " field additionalWritableRoots must contain only non-blank path strings.");
        }
    }

    private static String boardLabel(JsonNode board, int index) {
        JsonNode boardName = board.get("boardName");
        return boardName != null && boardName.isTextual() && !boardName.asText().isBlank()
                ? DisplayNames.quotedName(boardName.asText())
                : String.valueOf(index + 1);
    }

    private static List<String> append(List<String> warnings, String warning) {
        List<String> appended = new ArrayList<>(warnings);
        appended.add(warning);
        return List.copyOf(appended);
    }

    static ObjectMapper jsonMapper() {
        SimpleModule module = new SimpleModule()
                .addSerializer(Path.class, new PathJsonSerializer())
                .addDeserializer(Path.class, new PathJsonDeserializer());
        return new ObjectMapper().registerModule(module);
    }

    record ManifestLoadResult(ConnectedBoardManifest manifest, List<String> warnings, boolean usableRows) {
        ManifestLoadResult(ConnectedBoardManifest manifest, List<String> warnings) {
            this(manifest, warnings, true);
        }

        ManifestLoadResult {
            warnings = List.copyOf(warnings);
        }
    }
}
