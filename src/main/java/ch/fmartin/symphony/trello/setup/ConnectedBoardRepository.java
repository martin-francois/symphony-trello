package ch.fmartin.symphony.trello.setup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

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
        return json.readValue(manifestPath.toFile(), ConnectedBoardManifest.class);
    }

    ConnectedBoardManifest loadValidated() throws IOException {
        ConnectedBoardManifest manifest = load();
        if (manifest == null) {
            throw new IOException("Connected-board manifest is not an object.");
        }
        for (ConnectedBoard board : manifest.boards()) {
            validateBoard(board);
        }
        return manifest;
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

    Path manifestPath() {
        return manifestPath;
    }

    private static void validateBoard(ConnectedBoard board) throws IOException {
        if (board == null
                || blank(board.boardId())
                || blank(board.boardKey())
                || blank(board.boardName())
                || board.workflowPath() == null
                || board.serverPort() < 1
                || board.serverPort() > 65535) {
            throw new IOException("Connected-board manifest contains an invalid board entry.");
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    static ObjectMapper jsonMapper() {
        SimpleModule module = new SimpleModule()
                .addSerializer(Path.class, new PathJsonSerializer())
                .addDeserializer(Path.class, new PathJsonDeserializer());
        return new ObjectMapper().registerModule(module);
    }
}
