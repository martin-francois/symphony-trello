package ch.fmartin.symphony.trello.setup;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

final class SetupOptionFactory {
    private SetupOptionFactory() {}

    static LocalSetup.Options options(Path configDir) {
        return options(configDir, false, Optional.empty(), List.of(), false, false);
    }

    static LocalSetup.Options options(
            Path configDir,
            boolean nonInteractive,
            Optional<Boolean> githubMode,
            List<Path> additionalWritableRoots,
            boolean allowAllPaths,
            boolean dangerFullAccess) {
        return LocalSetup.Options.from(
                new LocalSetupRequest(
                        LocalSetupRequest.Action.SETUP,
                        false,
                        nonInteractive,
                        false,
                        false,
                        githubMode,
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        List.of(),
                        List.of(),
                        null,
                        true,
                        null,
                        Optional.empty(),
                        Optional.of(configDir.resolve("workspaces")),
                        Optional.of(configDir),
                        Optional.empty(),
                        Optional.empty(),
                        1,
                        false,
                        Optional.of(configDir.resolve(".env")),
                        additionalWritableRoots,
                        allowAllPaths,
                        dangerFullAccess,
                        true,
                        URI.create("http://127.0.0.1:1/")),
                Map.of(
                        "SYMPHONY_TRELLO_CONFIG_DIR", configDir.toString(),
                        "SYMPHONY_TRELLO_CALLER_DIR", configDir.toString()));
    }
}
