package ch.fmartin.symphony.trello.setup;

import ch.fmartin.symphony.trello.config.ConfigResolver;
import ch.fmartin.symphony.trello.config.EffectiveConfig;
import ch.fmartin.symphony.trello.workflow.WorkflowDefinition;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/// Selection uses registered metadata alone, so recovery works after a workflow or credential file
/// becomes unreadable. Executing a workflow additionally checks its registered board identity.
@ApplicationScoped
public class ConnectedWorkflowResolver {
    private final Map<String, String> environment;

    public ConnectedWorkflowResolver() {
        this(System.getenv());
    }

    ConnectedWorkflowResolver(Map<String, String> environment) {
        this.environment = Map.copyOf(environment);
    }

    public EffectiveConfig resolveRuntime(WorkflowDefinition workflow, ConfigResolver configResolver) {
        LocalWorkerPaths paths = LocalWorkerPaths.from(
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), environment);
        try {
            ConnectedBoard board = select(
                    new ConnectedBoardRepository(paths.manifestPath()).loadForLifecycle(),
                    Optional.empty(),
                    Optional.of(workflow.path()),
                    "worker");
            if (!ManagedWorkflowImport.managed(board.workflowPath(), paths.configDir())
                    || !PathsEqual.samePath(board.workflowPath(), workflow.path())) {
                throw notConnected();
            }
            EffectiveConfig config = configResolver.resolve(workflow, board.referenceDirectory());
            configResolver.validateForDispatch(config);
            requireSelectedBoard(board, config);
            config = protectCredentials(config, board, paths.configDir());
            return config.withCredentialSources(
                            config.credentialSources().withFiles(Set.of(paths.stateHome(), paths.manifestPath())))
                    .withProtectedLaunchPaths(InstalledLaunchPaths.resolve(paths, environment));
        } catch (IOException failure) {
            throw new TrelloBoardSetupException(
                    "setup_manifest_unavailable",
                    "cannot resolve connected workflow: repair the connected-board manifest from a trusted terminal",
                    failure);
        }
    }

    static EffectiveConfig protectCredentials(EffectiveConfig config, ConnectedBoard board, Path configDir) {
        Set<Path> files = new HashSet<>(Set.of(configDir, board.envPath()));
        if (board.sourceWorkflowPath() != null) {
            files.add(board.sourceWorkflowPath());
        }
        return config.withCredentialSources(config.credentialSources().withFiles(files));
    }

    static ConnectedBoard select(
            ConnectedBoardManifest manifest, Optional<String> board, Optional<Path> workflow, String command) {
        requireSingleSelector(board, workflow);
        return board.map(manifest::selectBoard)
                .or(() -> workflow.map(path -> registeredWorkflow(manifest, path)))
                .orElseGet(() -> defaultBoard(manifest, command));
    }

    static void requireSingleSelector(Optional<String> board, Optional<Path> workflow) {
        if (board.isPresent() && workflow.isPresent()) {
            throw new TrelloBoardSetupException(
                    "setup_worker_selection_conflict",
                    "cannot select connected board: use either --board or --workflow");
        }
    }

    private static ConnectedBoard registeredWorkflow(ConnectedBoardManifest manifest, Path path) {
        return manifest.selectWorkflow(path.toAbsolutePath().normalize())
                .orElseThrow(ConnectedWorkflowResolver::notConnected);
    }

    private static ConnectedBoard defaultBoard(ConnectedBoardManifest manifest, String command) {
        if (manifest.boards().isEmpty()) {
            throw notConnected();
        }
        if (manifest.boards().size() > 1) {
            throw new TrelloBoardSetupException(
                    "setup_worker_board_required",
                    "cannot select connected board: use --board or --workflow for " + command);
        }
        return manifest.boards().getFirst();
    }

    static TrelloBoardSetupException notConnected() {
        return new TrelloBoardSetupException(
                "setup_worker_board_not_found",
                "cannot resolve connected board: run symphony-trello start --workflow PATH from a trusted terminal");
    }

    static void requireSelectedBoard(ConnectedBoard selected, EffectiveConfig config) {
        String configuredBoard =
                TrelloBoardIds.parseStoredBoardUrl(config.tracker().boardId());
        if (!stableBoardIdentifiers(selected).contains(configuredBoard.toLowerCase(Locale.ROOT))) {
            throw new TrelloBoardSetupException(
                    "setup_workflow_board_mismatch",
                    "cannot resolve connected board " + DisplayNames.quotedName(selected.boardName())
                            + ": restore tracker.board_id or reconnect the workflow from a trusted terminal");
        }
    }

    static Set<String> stableBoardIdentifiers(ConnectedBoard board) {
        Set<String> identifiers = new HashSet<>();
        addBoardIdentifier(identifiers, board.boardId());
        addBoardIdentifier(identifiers, board.boardKey());
        String urlIdentifier = TrelloBoardIds.parseStoredBoardUrl(board.boardUrl());
        if (!urlIdentifier.equals(board.boardUrl())) {
            addBoardIdentifier(identifiers, urlIdentifier);
        }
        return Set.copyOf(identifiers);
    }

    private static void addBoardIdentifier(Set<String> identifiers, String identifier) {
        if (identifier != null && !identifier.isBlank()) {
            identifiers.add(identifier.toLowerCase(Locale.ROOT));
        }
    }
}
