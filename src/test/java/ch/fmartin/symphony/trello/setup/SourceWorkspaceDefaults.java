package ch.fmartin.symphony.trello.setup;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/// Exposes the source CLI defaults to the permission-profile boundary tests.
public final class SourceWorkspaceDefaults {
    private SourceWorkspaceDefaults() {}

    public static Path workspaceRoot(String command) {
        return workspaceRoot(command, Map.of());
    }

    public static Path workspaceRoot(String command, Map<String, String> environment) {
        return switch (command) {
            case "setup-local" ->
                LocalSetup.Options.from(
                                new SetupLocalCommandFactory.CommonOptions().request(LocalSetupRequest.Action.SETUP),
                                environment)
                        .workspaceRoot();
            case "new-board" -> new TrelloBoardSetupMain.BoardSetupOptions().workspaceRoot;
            case "start" ->
                LocalWorkerPaths.from(
                                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), environment)
                        .workspaceRoot();
            default -> throw new IllegalArgumentException("unknown test command: " + command);
        };
    }
}
