package ch.fmartin.symphony.trello.setup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/// Trusted lifecycle metadata stays in the helper's private directory. It survives stop so an
/// installer can restart every initialized manifest after replacing the application.
final class CardHelperInstallations {
    private static final String FILE_NAME = "installation.json";

    private CardHelperInstallations() {}

    static void save(LocalWorkerPaths paths, CardHelperFiles files) throws IOException {
        var registration = new Registration(
                paths.configDir().toString(), paths.manifestPath().toString());
        CardHelperFiles.publish(
                files.privateState(),
                files.privateState().resolve(FILE_NAME),
                CardHelperProtocol.json().writeValueAsBytes(registration),
                CardHelperProtocol.MAX_REQUEST_BYTES);
    }

    static List<LocalWorkerPaths> find(LocalWorkerPaths paths) throws IOException {
        Path home = paths.stateHome().resolve("card-helper");
        if (!Files.exists(home, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        if (!Files.isDirectory(home, LinkOption.NOFOLLOW_LINKS)) {
            throw invalid();
        }
        List<LocalWorkerPaths> result = new ArrayList<>();
        try (var directories = Files.newDirectoryStream(home)) {
            for (Path root : directories) {
                if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                var files = new CardHelperFiles(root);
                Path metadata = files.privateState().resolve(FILE_NAME);
                if (!Files.exists(metadata, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                if (!Files.isDirectory(files.privateState(), LinkOption.NOFOLLOW_LINKS)) {
                    throw invalid();
                }
                Registration registration = CardHelperProtocol.json()
                        .readValue(
                                CardHelperFiles.read(metadata, CardHelperProtocol.MAX_REQUEST_BYTES),
                                Registration.class);
                if (registration == null || registration.configDir() == null || registration.manifestPath() == null) {
                    throw invalid();
                }
                if (!paths.configDir()
                        .equals(Path.of(registration.configDir())
                                .toAbsolutePath()
                                .normalize())) {
                    continue;
                }
                LocalWorkerPaths selected = paths.withManifestPath(Path.of(registration.manifestPath()));
                if (!CardHelperFiles.from(selected).root().equals(root)) {
                    throw invalid();
                }
                result.add(selected);
            }
        }
        return List.copyOf(result);
    }

    private static IOException invalid() {
        return new IOException(
                "cannot inspect card helper installations: repair the private lifecycle metadata before updating");
    }

    private record Registration(String configDir, String manifestPath) {}
}
