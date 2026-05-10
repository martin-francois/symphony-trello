package ch.fmartin.symphony.trello.setup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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

    void save(ConnectedBoardManifest manifest) throws IOException {
        Path parent = manifestPath.toAbsolutePath().normalize().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        json.writerWithDefaultPrettyPrinter().writeValue(manifestPath.toFile(), manifest);
    }

    Path manifestPath() {
        return manifestPath;
    }

    static ObjectMapper jsonMapper() {
        SimpleModule module = new SimpleModule()
                .addSerializer(Path.class, new PathJsonSerializer())
                .addDeserializer(Path.class, new PathJsonDeserializer());
        return new ObjectMapper().registerModule(module);
    }
}
