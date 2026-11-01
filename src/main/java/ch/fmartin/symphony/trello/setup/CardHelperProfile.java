package ch.fmartin.symphony.trello.setup;

import ch.fmartin.symphony.trello.config.ProtectedPaths;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/// Generates the external caller's profile in a trusted terminal. The helper cannot confine an
/// existing parent agent; the operator installs this profile before starting that agent.
final class CardHelperProfile {
    private CardHelperProfile() {}

    static String render(LocalWorkerPaths paths, Map<String, String> environment) throws IOException {
        CardHelperFiles helper = CardHelperFiles.from(paths);
        var repository = new ConnectedBoardRepository(paths.manifestPath());
        Set<Path> denied = new HashSet<>(Set.of(paths.configDir(), paths.manifestPath(), helper.privateState()));
        try (var _ = repository.lock()) {
            for (ConnectedBoard board : repository.loadForLifecycle().boards()) {
                var config = new WorkflowConfigEditor()
                        .prepareConnectedWorkflow(
                                board,
                                WorkflowEnvironmentResolver.resolver(environment, board.envPath()),
                                false,
                                paths.configDir());
                ConnectedWorkflowResolver.requireSelectedBoard(board, config);
                denied.addAll(config.credentialSources().files());
            }
        }
        denied = ProtectedPaths.outermost(ProtectedPaths.aliases(denied));
        Set<Path> requests = ProtectedPaths.aliases(Set.of(helper.requests()));
        for (Path request : requests) {
            if (denied.stream().anyMatch(request::startsWith)) {
                throw new IOException(
                        "cannot generate card profile: place runtime state outside protected configuration and credential paths");
            }
        }
        Map<String, String> rules = new TreeMap<>();
        Set<Path> readOnlyPaths = new HashSet<>(InstalledLaunchPaths.resolve(paths, environment));
        readOnlyPaths.add(paths.stateHome());
        for (Path readOnly : ProtectedPaths.aliases(readOnlyPaths)) {
            if (denied.stream().noneMatch(readOnly::startsWith)) {
                rules.put(readOnly.toString(), "read");
            }
        }
        for (Path path : denied) {
            rules.put(path.toString(), "deny");
        }
        for (Path request : requests) {
            rules.put(request.toString(), "write");
        }
        var json = CardHelperProtocol.json();
        String profile = "symphony-cards-" + helper.root().getFileName();
        var result = new StringBuilder("default_permissions = ")
                .append(json.writeValueAsString(profile))
                .append("\n\n[permissions.")
                .append(profile)
                .append("]\nextends = \":workspace\"\n\n[permissions.")
                .append(profile)
                .append(".filesystem]\n");
        for (var rule : rules.entrySet()) {
            result.append(json.writeValueAsString(rule.getKey()))
                    .append(" = ")
                    .append(json.writeValueAsString(rule.getValue()))
                    .append('\n');
        }
        return result.toString();
    }
}
