package ch.fmartin.symphony.trello.setup;

import ch.fmartin.symphony.trello.config.EffectiveConfig;
import ch.fmartin.symphony.trello.telemetry.BoardOperation;
import ch.fmartin.symphony.trello.workflow.WorkflowLoader;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

/// Publishes a validated snapshot and its connection while card operations hold the same lock.
/// Startup happens after publication; a failed launch leaves a registered board that can be repaired.
final class ManagedWorkflowImport {
    private final WorkflowConfigEditor workflowConfig;
    private final Map<String, String> environment;
    private final Function<EffectiveConfig, TrelloBoardSetup.BoardInfo> boardLookup;

    ManagedWorkflowImport(WorkflowConfigEditor workflowConfig, Map<String, String> environment) {
        this(workflowConfig, environment, ManagedWorkflowImport::lookupBoard);
    }

    ManagedWorkflowImport(
            WorkflowConfigEditor workflowConfig,
            Map<String, String> environment,
            Function<EffectiveConfig, TrelloBoardSetup.BoardInfo> boardLookup) {
        this.workflowConfig = workflowConfig;
        this.environment = Map.copyOf(environment);
        this.boardLookup = boardLookup;
    }

    ConnectedBoard connect(LocalWorkerPaths paths, Path source, Optional<Path> explicitEnv, StopReplaced stop)
            throws IOException {
        var repository = new ConnectedBoardRepository(paths.manifestPath());
        try (var ignored = repository.lock()) {
            ConnectedBoardManifest manifest = repository.loadForLifecycle();
            Path original = source.toAbsolutePath().normalize();
            Optional<ConnectedBoard> selected = manifest.selectWorkflow(original);
            Optional<ConnectedBoard> alreadyManaged =
                    selected.filter(board -> managed(board.workflowPath(), paths.configDir())
                            && PathsEqual.samePath(board.workflowPath(), original));
            if (alreadyManaged.isPresent()) {
                return alreadyManaged.orElseThrow();
            }
            Path env = explicitEnv
                    .or(() -> selected.map(ConnectedBoard::envPath))
                    .orElseGet(paths::defaultEnvPath)
                    .toAbsolutePath()
                    .normalize();
            Files.createDirectories(paths.configDir());
            Path snapshot = Files.createTempFile(paths.configDir(), ".connect-", ".md");
            try {
                workflowConfig.requireLaunchableWorkflowFile(original);
                Files.copy(original, snapshot, StandardCopyOption.REPLACE_EXISTING);
                EffectiveConfig config = workflowConfig.prepareWorkflowSnapshot(
                        snapshot,
                        original,
                        WorkflowEnvironmentResolver.resolver(environment, env),
                        WorkflowEnvironmentResolver.externalHttpPortOverrideSource(environment, env)
                                .isEmpty(),
                        paths.configDir());
                workflowConfig.validateLaunchDispatch(config);
                int port = LocalHealthChecker.resolveManagedPort(
                        workflowConfig, environment, snapshot, TrelloBoardSetup.DEFAULT_SERVER_PORT, env);
                TrelloBoardSetup.BoardInfo info = boardLookup.apply(config);
                Optional<ConnectedBoard> target = manifest.selectBoardIdIfPresent(info.boardId());
                if (selected.isPresent() && target.isPresent() && !selected.equals(target)) {
                    throw new TrelloBoardSetupException(
                            "setup_connection_conflict",
                            "cannot import workflow: disconnect its previous board before retargeting another connected board");
                }
                Optional<ConnectedBoard> previous = target.or(() -> selected);
                Path destination = previous.map(ConnectedBoard::workflowPath)
                        .filter(path -> managed(path, paths.configDir()))
                        .orElseGet(() -> destination(paths.configDir(), info.boardName()));
                var connected = new ConnectedBoard(
                        info.boardId(),
                        info.boardKey(),
                        info.boardName(),
                        info.boardUrl(),
                        destination,
                        env,
                        config.workspace().root(),
                        port,
                        GeneratedWorkflowBodyInputs.fromMetadata(
                                        new WorkflowLoader().load(snapshot).config(), Optional.empty())
                                .map(GeneratedWorkflowBodyInputs::githubEnabled)
                                .orElse(false),
                        config.codex().additionalWritableRoots(),
                        config.codex().bypassesSandbox(),
                        original);
                List<ConnectedBoard> replacedBoards = new ArrayList<>(manifest.boardsReplacedBy(connected));
                selected.filter(Predicate.not(replacedBoards::contains)).ifPresent(replacedBoards::add);
                if (replacedBoards.stream().noneMatch(board -> PathsEqual.samePath(board.workflowPath(), original))
                        && Files.exists(new ManagedProcessStore(paths.stateHome())
                                .files(original)
                                .pidFile())) {
                    stop.run(connected.withWorkflowPath(original));
                }
                for (ConnectedBoard replaced : replacedBoards) {
                    stop.run(replaced);
                }
                new ManagedProcessStore(paths.stateHome()).rotateLogsForReplacedBoards(connected, replacedBoards);
                ConnectedBoardManifest remaining = selected.map(board -> manifest.withoutBoard(board.boardId()))
                        .orElse(manifest);
                publish(
                        repository,
                        remaining.withBoard(connected),
                        snapshot,
                        destination,
                        original,
                        paths.manifestPath());
                manifest.register(connected)
                        .count(
                                TelemetryInstallations.service(paths, paths.manifestPath(), env, environment),
                                BoardOperation.IMPORT);
                return connected;
            } finally {
                AtomicFiles.deleteQuietly(snapshot);
            }
        }
    }

    private static void publish(
            ConnectedBoardRepository repository,
            ConnectedBoardManifest manifest,
            Path snapshot,
            Path destination,
            Path source,
            Path manifestPath)
            throws IOException {
        var provenance = GeneratedWorkflowStore.besideManifest(manifestPath);
        Optional<byte[]> copiedRecord = provenance.copyRecord(source, destination);
        List<Publication> publications = new ArrayList<>();
        int published = 0;
        boolean retainBackups = false;
        try {
            publications.add(new Publication(destination, snapshot, backup(destination)));
            if (copiedRecord.isPresent()) {
                Path recordSnapshot = AtomicFiles.siblingTemporaryFile(provenance.storePath());
                try {
                    Files.write(recordSnapshot, copiedRecord.orElseThrow());
                    publications.add(
                            new Publication(provenance.storePath(), recordSnapshot, backup(provenance.storePath())));
                } catch (IOException | RuntimeException failure) {
                    AtomicFiles.deleteQuietly(recordSnapshot);
                    throw failure;
                }
            }
            for (Publication publication : publications) {
                AtomicFiles.replace(publication.snapshot(), publication.destination());
                published++;
            }
            repository.save(manifest);
        } catch (IOException | RuntimeException failure) {
            for (int index = published - 1; index >= 0; index--) {
                Publication publication = publications.get(index);
                try {
                    restore(publication.destination(), publication.backup());
                } catch (IOException restoration) {
                    retainBackups = true;
                    failure.addSuppressed(restoration);
                }
            }
            throw failure;
        } finally {
            for (Publication publication : publications) {
                AtomicFiles.deleteQuietly(publication.snapshot());
                if (!retainBackups) {
                    publication.backup().ifPresent(AtomicFiles::deleteQuietly);
                }
            }
        }
    }

    private record Publication(Path destination, Path snapshot, Optional<Path> backup) {}

    private static Optional<Path> backup(Path destination) throws IOException {
        if (!Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        Path backup = AtomicFiles.siblingTemporaryFile(destination);
        try {
            Files.copy(destination, backup, LinkOption.NOFOLLOW_LINKS, StandardCopyOption.REPLACE_EXISTING);
            return Optional.of(backup);
        } catch (IOException | RuntimeException failure) {
            AtomicFiles.deleteQuietly(backup);
            throw failure;
        }
    }

    private static void restore(Path destination, Optional<Path> backup) throws IOException {
        if (backup.isPresent()) {
            AtomicFiles.replace(backup.orElseThrow(), destination);
        } else {
            Files.deleteIfExists(destination);
        }
    }

    static boolean managed(Path workflow, Path configDir) {
        return configDir
                .toAbsolutePath()
                .normalize()
                .equals(workflow.toAbsolutePath().normalize().getParent());
    }

    private static Path destination(Path configDir, String name) {
        int suffix = 1;
        Path destination = configDir.resolve(WorkflowFileNames.generatedFileName(name, "board", suffix));
        while (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            suffix++;
            destination = configDir.resolve(WorkflowFileNames.generatedFileName(name, "board", suffix));
        }
        return destination.toAbsolutePath().normalize();
    }

    private static TrelloBoardSetup.BoardInfo lookupBoard(EffectiveConfig config) {
        var credentials = new TrelloBoardSetup.TrelloCredentials(
                config.tracker().apiKey(), config.tracker().apiToken());
        return new TrelloBoardSetup(ConnectedBoardRepository.jsonMapper())
                .getBoardInfo(new TrelloBoardSetup.BoardInfoRequest(
                        URI.create(config.tracker().endpoint()),
                        credentials,
                        config.tracker().boardId()));
    }

    @FunctionalInterface
    interface StopReplaced {
        void run(ConnectedBoard board) throws IOException;
    }
}
