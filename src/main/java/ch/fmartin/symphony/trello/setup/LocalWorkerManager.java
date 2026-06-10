package ch.fmartin.symphony.trello.setup;

import ch.fmartin.symphony.trello.config.LocalEnvironment;
import com.google.common.util.concurrent.Striped;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.Lock;

final class LocalWorkerManager {
    private static final int STARTUP_LOG_BYTE_LIMIT = 128 * 1024;
    private static final Striped<Lock> PROCESS_LOCKS = Striped.lazyWeakLock(1024);

    private final Map<String, String> environment;
    private final WorkflowConfigEditor workflowConfig;
    private final LocalHealthChecker healthChecker;
    private final ManagedProcessPlatform platform;
    private final LocalLogTailer logTailer;

    LocalWorkerManager(Map<String, String> environment) {
        this(environment, new WorkflowConfigEditor());
    }

    LocalWorkerManager(Map<String, String> environment, WorkflowConfigEditor workflowConfig) {
        this(
                environment,
                workflowConfig,
                new LocalHealthChecker(environment, workflowConfig),
                platformForCurrentOs(),
                new LocalLogTailer());
    }

    LocalWorkerManager(
            Map<String, String> environment,
            WorkflowConfigEditor workflowConfig,
            LocalHealthChecker healthChecker,
            ManagedProcessPlatform platform,
            LocalLogTailer logTailer) {
        this.environment = Map.copyOf(environment);
        this.workflowConfig = workflowConfig;
        this.healthChecker = healthChecker;
        this.platform = platform;
        this.logTailer = logTailer;
    }

    int start(StartWorkerRequest request, PrintStream out) throws IOException {
        LocalWorkerPaths paths = LocalWorkerPaths.from(
                request.appHome(), request.configDir(), request.workspaceRoot(), request.stateHome(), environment);
        ConnectedBoardManifest manifest = new ConnectedBoardRepository(paths.manifestPath()).load();
        if (!request.all()
                && request.envPath().isPresent()
                && request.board().isEmpty()
                && request.workflow().isEmpty()) {
            throw new TrelloBoardSetupException(
                    "setup_worker_selection_conflict", "--env requires --board or --workflow.");
        }
        if (request.all() || request.plainStart()) {
            startAll(paths, manifest, request, out);
            return 0;
        }
        Optional<Path> explicitEnvPath =
                request.envPath().map(path -> path.toAbsolutePath().normalize());
        Path fallbackEnvPath = paths.defaultEnvPath().toAbsolutePath().normalize();
        ConnectedBoard selectedBoard = selectOne(
                manifest, request.board(), request.workflow(), "start", explicitEnvPath, fallbackEnvPath, true);
        Path envPath = request.envPath()
                .orElseGet(() -> selectedBoard.envPath() == null ? paths.defaultEnvPath() : selectedBoard.envPath())
                .toAbsolutePath()
                .normalize();
        ConnectedBoard board = request.workflow().isPresent() && selectedBoard.envPath() == null
                ? workflowBoard(selectedBoard.workflowPath(), envPath)
                : selectedBoard;
        rejectBoardManagedByAnotherWorkflow(paths, manifest, board);
        start(paths, board, envPath, request.envPath().isPresent(), out);
        return 0;
    }

    private void rejectBoardManagedByAnotherWorkflow(
            LocalWorkerPaths paths, ConnectedBoardManifest manifest, ConnectedBoard board) throws IOException {
        boardManagedByAnotherRunningWorkflow(paths, manifest, board).ifPresent(row -> {
            throw new TrelloBoardSetupException(
                    "setup_worker_board_already_managed",
                    "Trello board \"" + row.boardName()
                            + "\" is already managed by a running worker for another workflow:\n  "
                            + row.workflowPath()
                            + "\nStop that worker first with symphony-trello stop --workflow, or start that workflow instead.");
        });
    }

    private Optional<ConnectedBoard> boardManagedByAnotherRunningWorkflow(
            LocalWorkerPaths paths, ConnectedBoardManifest manifest, ConnectedBoard board) throws IOException {
        for (ConnectedBoard row : manifest.boards()) {
            if (PathsEqual.samePath(row.workflowPath(), board.workflowPath())) {
                continue;
            }
            if (!sameTrelloBoard(row, board)) {
                continue;
            }
            if (isRunningManagedWorker(paths, row)) {
                return Optional.of(row);
            }
        }
        return Optional.empty();
    }

    private static boolean sameTrelloBoard(ConnectedBoard row, ConnectedBoard board) {
        Set<String> rowIdentifiers = stableBoardIdentifiers(row);
        return stableBoardIdentifiers(board).stream().anyMatch(rowIdentifiers::contains);
    }

    /**
     * Collects every stable Trello identifier of a board row. Created-board manifest rows can
     * store the 24-character board id in boardKey, leaving the short link only recoverable from
     * the stored board URL. Identifiers are lowercased to match connected-board selector
     * case handling.
     */
    private static Set<String> stableBoardIdentifiers(ConnectedBoard board) {
        Set<String> identifiers = new HashSet<>();
        addBoardIdentifier(identifiers, board.boardId());
        addBoardIdentifier(identifiers, board.boardKey());
        String urlIdentifier = TrelloBoardIds.parseStoredBoardUrl(board.boardUrl());
        if (!urlIdentifier.equals(board.boardUrl())) {
            addBoardIdentifier(identifiers, urlIdentifier);
        }
        return identifiers;
    }

    private static void addBoardIdentifier(Set<String> identifiers, String identifier) {
        if (identifier != null && !identifier.isBlank()) {
            identifiers.add(identifier.toLowerCase(Locale.ROOT));
        }
    }

    private boolean isRunningManagedWorker(LocalWorkerPaths paths, ConnectedBoard row) throws IOException {
        if (canStopManagedWorker(paths, row)) {
            return true;
        }
        try {
            return healthChecker.boardHealth(row).kind() == BoardHealthKind.SAME_WORKFLOW;
        } catch (TrelloBoardSetupException e) {
            // A row with unresolvable port configuration cannot be probed; do not block this start
            // because of an unrelated broken manifest row.
            return false;
        }
    }

    private void startAll(
            LocalWorkerPaths paths, ConnectedBoardManifest manifest, StartWorkerRequest request, PrintStream out)
            throws IOException {
        if (request.board().isPresent()
                || request.workflow().isPresent()
                || request.envPath().isPresent()) {
            throw new TrelloBoardSetupException(
                    "setup_worker_selection_conflict", "--all cannot be used with --board, --workflow, or --env.");
        }
        if (manifest.boards().isEmpty()) {
            throw new TrelloBoardSetupException(
                    "setup_worker_board_not_found", "No Trello boards are connected to Symphony.");
        }
        for (ConnectedBoard board : manifest.boards()) {
            // Plain branching because the absent branch starts the worker with checked IO.
            Optional<ConnectedBoard> managedElsewhere = boardManagedByAnotherRunningWorkflow(paths, manifest, board);
            if (managedElsewhere.isPresent()) {
                ConnectedBoard running = managedElsewhere.get();
                out.println("Skipped workflow " + PathNames.fileName(board.workflowPath()) + " because Trello board \""
                        + running.boardName() + "\" is already managed by the running workflow:\n  "
                        + running.workflowPath());
                continue;
            }
            Path envPath = (board.envPath() == null ? paths.defaultEnvPath() : board.envPath())
                    .toAbsolutePath()
                    .normalize();
            start(paths, board, envPath, out);
        }
    }

    void start(LocalWorkerPaths paths, ConnectedBoard board, Path envPath, PrintStream out) throws IOException {
        start(paths, board, envPath, false, out);
    }

    private void start(
            LocalWorkerPaths paths, ConnectedBoard board, Path envPath, boolean explicitEnvOverride, PrintStream out)
            throws IOException {
        validateWorkerWorkflowPath(board.workflowPath());
        validateWorkerEnvPath(envPath);
        ManagedProcessStore store = new ManagedProcessStore(paths.stateHome());
        ManagedProcessStore.ManagedProcessFiles files = store.files(board.workflowPath());
        Files.createDirectories(paths.stateHome());
        startWithProcessLock(paths, board, envPath, explicitEnvOverride, out, store, files);
    }

    private static void validateWorkerEnvPath(Path envPath) {
        if (Files.exists(envPath) && !Files.isRegularFile(envPath)) {
            throw new TrelloBoardSetupException(
                    "setup_invalid_arguments", "--env must point to a regular dotenv file.");
        }
    }

    private static void validateWorkerWorkflowPath(Path workflowPath) {
        if (Files.exists(workflowPath) && !Files.isRegularFile(workflowPath)) {
            throw new TrelloBoardSetupException(
                    "setup_invalid_arguments", "--workflow must point to a regular workflow file.");
        }
    }

    private void startWithProcessLock(
            LocalWorkerPaths paths,
            ConnectedBoard board,
            Path envPath,
            boolean explicitEnvOverride,
            PrintStream out,
            ManagedProcessStore store,
            ManagedProcessStore.ManagedProcessFiles files)
            throws IOException {
        try (var ignored = acquireProcessLock(files)) {
            startLocked(paths, board, envPath, explicitEnvOverride, out, store, files);
        }
    }

    private void startLocked(
            LocalWorkerPaths paths,
            ConnectedBoard board,
            Path envPath,
            boolean explicitEnvOverride,
            PrintStream out,
            ManagedProcessStore store,
            ManagedProcessStore.ManagedProcessFiles files)
            throws IOException {
        Long existingPid = store.readPid(files.pidFile());
        int healthPort = healthChecker.managedHealthPort(board.workflowPath(), board.serverPort(), envPath);
        Optional<WorkerCredentialUsage> credentialUsage = workerCredentialUsage(board.workflowPath(), envPath);
        if (existingPid != null && platform.isAlive(existingPid)) {
            if (!platform.isManaged(existingPid, paths.appHome(), board.workflowPath())) {
                throw new TrelloBoardSetupException(
                        "setup_worker_pid_unmanaged",
                        "State file belongs to another process for " + board.workflowPath() + " pid=" + existingPid);
            }
            BoardHealth health =
                    healthChecker.workflowHealth(board.workflowPath(), board.boardId(), board.boardKey(), healthPort);
            if (health.kind() == BoardHealthKind.SAME_WORKFLOW) {
                printIgnoredEnvOverride(paths, board, envPath, explicitEnvOverride, out);
                out.println(
                        "Symphony for Trello is already running for \"" + board.boardName() + "\" pid=" + existingPid);
                return;
            }
            stopPid(store, files, existingPid);
        } else if (existingPid != null) {
            store.deletePid(files.pidFile());
        }

        BoardHealth existingHealth =
                healthChecker.workflowHealth(board.workflowPath(), board.boardId(), board.boardKey(), healthPort);
        if (existingHealth.kind() == BoardHealthKind.SAME_WORKFLOW) {
            printIgnoredEnvOverride(paths, board, envPath, explicitEnvOverride, out);
            out.println("Symphony for Trello is already running for \"" + board.boardName() + "\" at "
                    + LocalHealthChecker.localServerUrl(existingHealth.port()));
            repairOrReportUntrackedWorker(paths, board, existingHealth, store, files, out);
            return;
        }

        boolean workflowServerPortUsed =
                healthChecker.externalHttpPortOverrideSource(envPath).isEmpty();
        workflowConfig.validateStartEnvironmentReferences(
                board.workflowPath(),
                WorkflowEnvironmentResolver.resolver(environment, envPath),
                workflowServerPortUsed);
        credentialUsage.ifPresent(this::validateWorkerCredentials);

        List<String> command = List.of(
                javaExecutable(),
                "-Dsymphony.trello.managed.app_home=" + paths.appHome(),
                "-jar",
                paths.appHome().resolve("target/quarkus-app/quarkus-run.jar").toString(),
                board.workflowPath().toString());
        Map<String, String> processEnvironment = Map.of(
                "SYMPHONY_TRELLO_DOTENV", envPath.toString(),
                "SYMPHONY_TRELLO_CONFIG_DIR", paths.configDir().toString(),
                "SYMPHONY_TRELLO_WORKSPACE_ROOT", paths.workspaceRoot().toString(),
                "SYMPHONY_TRELLO_STATE_HOME", paths.stateHome().toString());
        StartupLogOffsets logOffsets = StartupLogOffsets.capture(files);
        ManagedProcessHandle handle =
                platform.start(command, paths.appHome(), processEnvironment, files.stdoutLog(), files.stderrLog());
        store.writePid(files.pidFile(), handle.pid());
        BoardHealth health = healthChecker.waitForSameWorkflow(board, healthPort);
        if (health.kind() != BoardHealthKind.SAME_WORKFLOW) {
            stopPid(store, files, handle.pid());
            throwKnownStartupFailure(files, logOffsets, platform.appendsToExistingLogs(), credentialUsage);
            throw new TrelloBoardSetupException(
                    "setup_start_unhealthy",
                    "Symphony start returned successfully, but " + LocalHealthChecker.localStateUrl(health.port())
                            + " did not report the expected workflow and board for \"" + board.boardName()
                            + "\". Logs: " + files.stdoutLog() + " and " + files.stderrLog());
        }
        if (!platform.isAlive(handle.pid())
                || !platform.isManaged(handle.pid(), paths.appHome(), board.workflowPath())) {
            stopStartedProcess(store, files, handle.pid());
            throw new TrelloBoardSetupException(
                    "setup_start_unhealthy",
                    "Symphony reported the expected workflow and board on "
                            + LocalHealthChecker.localStateUrl(health.port())
                            + ", but the newly started managed process is not running for this install. Logs: "
                            + files.stdoutLog() + " and " + files.stderrLog());
        }
        out.println("Started Symphony for Trello: \"" + board.boardName() + "\"");
    }

    private void repairOrReportUntrackedWorker(
            LocalWorkerPaths paths,
            ConnectedBoard board,
            BoardHealth health,
            ManagedProcessStore store,
            ManagedProcessStore.ManagedProcessFiles files,
            PrintStream out)
            throws IOException {
        Optional<Long> verifiedPid = verifiedManagedWorkerPid(paths, board, health);
        // Plain branching because the present branch writes the pid file with checked IO.
        if (verifiedPid.isPresent()) {
            store.writePid(files.pidFile(), verifiedPid.get());
            out.println("Restored missing managed worker tracking pid=" + verifiedPid.get());
            return;
        }
        out.println("This worker is untracked because it has no managed pid for this install.");
        health.workerPid()
                .ifPresentOrElse(
                        pid -> out.println(
                                "Stop the worker process manually, then rerun symphony-trello start. Reported worker pid="
                                        + pid),
                        () -> out.println("Stop the worker process manually, then rerun symphony-trello start."));
    }

    private Optional<Long> verifiedManagedWorkerPid(LocalWorkerPaths paths, ConnectedBoard board, BoardHealth health) {
        return health.workerPid()
                .filter(pid -> platform.isAlive(pid) && platform.isManaged(pid, paths.appHome(), board.workflowPath()));
    }

    private static void printIgnoredEnvOverride(
            LocalWorkerPaths paths, ConnectedBoard board, Path envPath, boolean explicitEnvOverride, PrintStream out) {
        if (!explicitEnvOverride) {
            return;
        }
        Path configuredEnvPath = (board.envPath() == null ? paths.defaultEnvPath() : board.envPath())
                .toAbsolutePath()
                .normalize();
        if (PathsEqual.samePath(envPath, configuredEnvPath)) {
            return;
        }
        out.println("Supplied --env was not applied because Symphony for Trello is already running.");
        out.println("Stop and start this worker to use: " + envPath);
    }

    private Optional<WorkerCredentialUsage> workerCredentialUsage(Path workflowPath, Path envPath) {
        return workflowConfig
                .trackerCredentialReferences(workflowPath)
                .map(references -> workerCredentialUsage(
                        envPath,
                        requiredEnvironmentCredential(references.apiKey(), "TRELLO_API_KEY"),
                        requiredEnvironmentCredential(references.apiToken(), "TRELLO_API_TOKEN")));
    }

    private WorkerCredentialUsage workerCredentialUsage(
            Path envPath, Optional<String> apiKeyEnvironment, Optional<String> apiTokenEnvironment) {
        Map<String, String> dotenv = LocalEnvironment.load(envPath);
        return new WorkerCredentialUsage(
                envPath,
                apiKeyEnvironment,
                apiTokenEnvironment,
                credentialSource(apiKeyEnvironment, dotenv),
                credentialSource(apiTokenEnvironment, dotenv));
    }

    private TrelloBoardSetupException.TrelloCredentialSource credentialSource(
            Optional<String> environmentName, Map<String, String> dotenv) {
        return environmentName
                .map(name -> {
                    if (!TrelloCredentialStore.blank(environment.get(name))) {
                        return TrelloBoardSetupException.TrelloCredentialSource.SHELL_ENVIRONMENT;
                    }
                    if (!TrelloCredentialStore.blank(dotenv.get(name))) {
                        return TrelloBoardSetupException.TrelloCredentialSource.DOTENV_FILE;
                    }
                    return TrelloBoardSetupException.TrelloCredentialSource.MISSING;
                })
                .orElse(TrelloBoardSetupException.TrelloCredentialSource.WORKFLOW_CONFIG);
    }

    private void validateWorkerCredentials(WorkerCredentialUsage usage) {
        boolean hasApiKey = usage.apiKeySource() != TrelloBoardSetupException.TrelloCredentialSource.MISSING;
        boolean hasApiToken = usage.apiTokenSource() != TrelloBoardSetupException.TrelloCredentialSource.MISSING;
        if (!hasApiKey && !hasApiToken) {
            throw missingWorkerCredentialException(
                    "setup_worker_missing_trello_credentials", "Missing Trello credentials for worker start.", usage);
        }
        if (!hasApiKey) {
            throw missingWorkerCredentialException(
                    "setup_worker_missing_api_key", "Missing Trello API key for worker start.", usage);
        }
        if (!hasApiToken) {
            throw missingWorkerCredentialException(
                    "setup_worker_missing_api_token", "Missing Trello API token for worker start.", usage);
        }
    }

    private static TrelloBoardSetupException missingWorkerCredentialException(
            String code, String message, WorkerCredentialUsage usage) {
        return new TrelloBoardSetupException(code, message)
                .withDotenvPath(usage.envPath())
                .withTrelloCredentialEnvironmentNames(
                        usage.apiKeyEnvironment().orElse("TRELLO_API_KEY"),
                        usage.apiTokenEnvironment().orElse("TRELLO_API_TOKEN"));
    }

    private static Optional<String> requiredEnvironmentCredential(
            Optional<String> configuredValue, String defaultEnvironmentName) {
        if (configuredValue.isEmpty()) {
            return Optional.of(defaultEnvironmentName);
        }
        return configuredValue.map(String::trim).flatMap(LocalWorkerManager::environmentCredentialName);
    }

    private static Optional<String> environmentCredentialName(String configuredValue) {
        return configuredValue.startsWith("$") && configuredValue.length() > 1
                ? Optional.of(configuredValue.substring(1))
                : Optional.empty();
    }

    private static void throwKnownStartupFailure(
            ManagedProcessStore.ManagedProcessFiles files,
            StartupLogOffsets logOffsets,
            boolean appendsToExistingLogs,
            Optional<WorkerCredentialUsage> credentialUsage) {
        String logs = startupLogs(files, logOffsets, appendsToExistingLogs);
        if (logs.contains("Trello authentication failed")) {
            TrelloBoardSetupException exception = new TrelloBoardSetupException(
                    "trello_auth_failed", "Trello authentication failed while starting Symphony.");
            throw credentialUsage
                    .map(usage -> exception
                            .withDotenvPath(usage.envPath())
                            .withTrelloCredentialEnvironmentNames(
                                    usage.apiKeyEnvironment().orElse("TRELLO_API_KEY"),
                                    usage.apiTokenEnvironment().orElse("TRELLO_API_TOKEN"))
                            .withTrelloCredentialSources(usage.apiKeySource(), usage.apiTokenSource()))
                    .orElse(exception);
        }
        if (logs.contains("Trello permission denied")) {
            throw new TrelloBoardSetupException(
                    "trello_permission_denied", "Trello permission denied while starting Symphony.");
        }
        if (logs.contains("Configured Trello board is closed") || logs.contains("Trello board is archived")) {
            throw new TrelloBoardSetupException(
                    "trello_board_closed", "Configured Trello board is archived while starting Symphony.");
        }
    }

    private static String startupLogs(
            ManagedProcessStore.ManagedProcessFiles files,
            StartupLogOffsets logOffsets,
            boolean appendsToExistingLogs) {
        return startupLog(files.stdoutLog(), logOffsets.stdoutOffset(), appendsToExistingLogs) + "\n"
                + startupLog(files.stderrLog(), logOffsets.stderrOffset(), appendsToExistingLogs);
    }

    private static String startupLog(Path path, StartupLogSnapshot snapshot, boolean appendsToExistingLogs) {
        if (!Files.isRegularFile(path)) {
            return "";
        }
        try {
            long size = Files.size(path);
            long start = startupLogStart(path, snapshot, size, appendsToExistingLogs);
            if (start < 0L) {
                return "";
            }
            try (FileChannel channel = FileChannel.open(path)) {
                channel.position(start);
                ByteBuffer buffer = ByteBuffer.allocate((int) (size - start));
                channel.read(buffer);
                buffer.flip();
                return StandardCharsets.UTF_8.decode(buffer).toString();
            }
        } catch (IOException ignored) {
            return "";
        }
    }

    private static long startupLogStart(
            Path path, StartupLogSnapshot snapshot, long size, boolean appendsToExistingLogs) throws IOException {
        if (!appendsToExistingLogs) {
            return size != snapshot.size() || modifiedAfterSnapshot(path, snapshot)
                    ? Math.max(0L, size - STARTUP_LOG_BYTE_LIMIT)
                    : -1L;
        }
        if (size > snapshot.size()) {
            return Math.max(snapshot.size(), size - STARTUP_LOG_BYTE_LIMIT);
        }
        if (size < snapshot.size() || modifiedAfterSnapshot(path, snapshot)) {
            return Math.max(0L, size - STARTUP_LOG_BYTE_LIMIT);
        }
        return -1L;
    }

    private static boolean modifiedAfterSnapshot(Path path, StartupLogSnapshot snapshot) throws IOException {
        long modifiedMillis = Files.getLastModifiedTime(path).toMillis();
        return modifiedMillis > snapshot.modifiedMillis();
    }

    private record StartupLogOffsets(StartupLogSnapshot stdoutOffset, StartupLogSnapshot stderrOffset) {
        static StartupLogOffsets capture(ManagedProcessStore.ManagedProcessFiles files) {
            return new StartupLogOffsets(
                    StartupLogSnapshot.snapshot(files.stdoutLog()), StartupLogSnapshot.snapshot(files.stderrLog()));
        }
    }

    private record WorkerCredentialUsage(
            Path envPath,
            Optional<String> apiKeyEnvironment,
            Optional<String> apiTokenEnvironment,
            TrelloBoardSetupException.TrelloCredentialSource apiKeySource,
            TrelloBoardSetupException.TrelloCredentialSource apiTokenSource) {}

    private record StartupLogSnapshot(long size, long modifiedMillis) {
        private static StartupLogSnapshot snapshot(Path path) {
            try {
                if (!Files.isRegularFile(path)) {
                    return new StartupLogSnapshot(0L, 0L);
                }
                return new StartupLogSnapshot(
                        Files.size(path), Files.getLastModifiedTime(path).toMillis());
            } catch (IOException ignored) {
                return new StartupLogSnapshot(0L, 0L);
            }
        }
    }

    private void stopStartedProcess(ManagedProcessStore store, ManagedProcessStore.ManagedProcessFiles files, long pid)
            throws IOException {
        if (platform.isAlive(pid)) {
            stopPid(store, files, pid);
        } else {
            store.deletePid(files.pidFile());
        }
    }

    int stop(StopWorkerRequest request, PrintStream out) throws IOException {
        LocalWorkerPaths paths = LocalWorkerPaths.from(
                request.appHome(), request.configDir(), request.workspaceRoot(), request.stateHome(), environment);
        ConnectedBoardManifest manifest = new ConnectedBoardRepository(paths.manifestPath()).load();
        List<ConnectedBoard> boards =
                selectForStop(manifest, request.board(), request.workflow(), paths.defaultEnvPath());
        boards = withDefaultEnvForExplicitWorkflow(paths, request.workflow(), boards);
        ManagedProcessStore store = new ManagedProcessStore(paths.stateHome());
        if (boards.isEmpty()) {
            stopPidFiles(paths, store, out);
            return 0;
        }
        for (ConnectedBoard board : boards) {
            stop(paths, store, board, out);
        }
        return 0;
    }

    void stop(LocalWorkerPaths paths, ConnectedBoard board, PrintStream out) throws IOException {
        stop(paths, new ManagedProcessStore(paths.stateHome()), board, out);
    }

    boolean canStopManagedWorker(LocalWorkerPaths paths, ConnectedBoard board) throws IOException {
        ManagedProcessStore store = new ManagedProcessStore(paths.stateHome());
        ManagedProcessStore.ManagedProcessFiles files = store.files(board.workflowPath());
        Long pid = store.readPid(files.pidFile());
        return pid != null && platform.isAlive(pid) && platform.isManaged(pid, paths.appHome(), board.workflowPath());
    }

    /**
     * True when stop would actually stop a running worker for this board: either a managed pid
     * file exists, or the board is healthy without a pid file and reports a worker pid that
     * verifies as managed for this install. Setup flows use this to decide whether a replaced
     * board needs a restart after its worker was stopped.
     */
    boolean canStopRunningWorker(LocalWorkerPaths paths, ConnectedBoard board) throws IOException {
        if (canStopManagedWorker(paths, board)) {
            return true;
        }
        BoardHealth health = healthChecker.boardHealth(board);
        return health.kind() == BoardHealthKind.SAME_WORKFLOW
                && verifiedManagedWorkerPid(paths, board, health).isPresent();
    }

    int status(WorkerStatusRequest request, PrintStream out) throws IOException {
        LocalWorkerPaths paths = LocalWorkerPaths.from(
                request.appHome(), request.configDir(), request.workspaceRoot(), request.stateHome(), environment);
        ConnectedBoardManifest manifest = new ConnectedBoardRepository(paths.manifestPath()).load();
        List<ConnectedBoard> boards =
                selectForStatus(manifest, request.board(), request.workflow(), paths.defaultEnvPath());
        boards = withDefaultEnvForExplicitWorkflow(paths, request.workflow(), boards);
        if (boards.isEmpty()) {
            printPidFileStatus(paths, out);
            return 0;
        }
        ManagedProcessStore store = new ManagedProcessStore(paths.stateHome());
        for (ConnectedBoard board : boards) {
            WorkflowValidation workflowDiagnostics = workflowConfig.diagnosticsValidation(
                    board.workflowPath(), WorkflowEnvironmentResolver.resolver(environment, board.envPath()));
            if (!workflowDiagnostics.ok()) {
                out.println("invalid \"" + board.boardName() + "\" " + workflowDiagnostics.message() + ": "
                        + board.workflowPath());
                continue;
            }
            ManagedProcessStore.ManagedProcessFiles files = store.files(board.workflowPath());
            Long pid = store.readPid(files.pidFile());
            BoardHealth health = healthChecker.boardHealth(board);
            if (pid != null
                    && platform.isAlive(pid)
                    && platform.isManaged(pid, paths.appHome(), board.workflowPath())
                    && health.kind() == BoardHealthKind.SAME_WORKFLOW) {
                out.println("running \"" + board.boardName() + "\" pid=" + pid + " "
                        + LocalHealthChecker.localServerUrl(health.port()));
            } else if (pid != null
                    && platform.isAlive(pid)
                    && platform.isManaged(pid, paths.appHome(), board.workflowPath())) {
                out.println("unhealthy \"" + board.boardName() + "\" pid=" + pid + " "
                        + LocalHealthChecker.localServerUrl(health.port()) + " (" + health.kind() + ")");
            } else if (pid != null && platform.isAlive(pid)) {
                out.println("stale \"" + board.boardName() + "\" pid=" + pid + " does not belong to this install");
            } else {
                store.deletePid(files.pidFile());
                if (health.kind() == BoardHealthKind.SAME_WORKFLOW) {
                    out.println("running \"" + board.boardName() + "\" "
                            + LocalHealthChecker.localServerUrl(health.port()) + " (untracked, no managed pid)");
                } else {
                    out.println("stopped \"" + board.boardName() + "\"");
                }
            }
        }
        return 0;
    }

    int logs(WorkerLogsRequest request, PrintStream out) throws IOException {
        LocalWorkerPaths paths = LocalWorkerPaths.from(
                request.appHome(), request.configDir(), request.workspaceRoot(), request.stateHome(), environment);
        ConnectedBoardManifest manifest = new ConnectedBoardRepository(paths.manifestPath()).load();
        ConnectedBoard board = selectOne(
                manifest, request.board(), request.workflow(), "logs", Optional.empty(), paths.defaultEnvPath(), false);
        ManagedProcessStore.ManagedProcessFiles files =
                new ManagedProcessStore(paths.stateHome()).files(board.workflowPath());
        List<Path> logFiles = List.of(files.stdoutLog(), files.stderrLog());
        if (request.follow()) {
            logTailer.follow(logFiles, out);
        } else {
            logTailer.printRecent(logFiles, 100, out);
        }
        return 0;
    }

    private void stop(LocalWorkerPaths paths, ManagedProcessStore store, ConnectedBoard board, PrintStream out)
            throws IOException {
        ManagedProcessStore.ManagedProcessFiles files = store.files(board.workflowPath());
        if (!Files.isDirectory(paths.stateHome())) {
            stopLocked(paths, store, board, out, files);
            return;
        }
        stopWithProcessLock(paths, store, board, out, files);
    }

    private void stopLocked(
            LocalWorkerPaths paths,
            ManagedProcessStore store,
            ConnectedBoard board,
            PrintStream out,
            ManagedProcessStore.ManagedProcessFiles files)
            throws IOException {
        Long pid = store.readPid(files.pidFile());
        if (pid == null || !platform.isAlive(pid)) {
            store.deletePid(files.pidFile());
            BoardHealth health = healthChecker.boardHealth(board);
            if (health.kind() == BoardHealthKind.SAME_WORKFLOW) {
                Optional<Long> verifiedPid = verifiedManagedWorkerPid(paths, board, health);
                // Plain branching because the present branch stops the worker with checked IO.
                if (verifiedPid.isPresent()) {
                    stopPid(store, files, verifiedPid.get());
                    out.println(
                            "Stopped untracked managed worker " + files.displayName() + " pid=" + verifiedPid.get());
                    return;
                }
                throw new TrelloBoardSetupException(
                        "setup_worker_untracked",
                        "Cannot stop "
                                + files.displayName()
                                + " because the worker is healthy but has no managed pid. Stop the process manually, then start it again with symphony-trello start."
                                + health.workerPid()
                                        .map(reportedPid -> " Reported worker pid=" + reportedPid)
                                        .orElse(""));
            }
            out.println("Symphony for Trello is already stopped for \"" + board.boardName() + "\"");
            return;
        }
        if (!platform.isManaged(pid, paths.appHome(), board.workflowPath())) {
            out.println("Skipped unmanaged stale pid " + files.displayName() + " pid=" + pid);
            return;
        }
        stopPid(store, files, pid);
        out.println("Stopped " + files.displayName());
    }

    private void stopPid(ManagedProcessStore store, ManagedProcessStore.ManagedProcessFiles files, long pid)
            throws IOException {
        if (!platform.stop(pid, Duration.ofSeconds(15), Duration.ofSeconds(5))) {
            throw new TrelloBoardSetupException("setup_stop_failed", "Managed process did not stop: pid=" + pid);
        }
        store.deletePid(files.pidFile());
    }

    private void stopWithProcessLock(
            LocalWorkerPaths paths,
            ManagedProcessStore store,
            ConnectedBoard board,
            PrintStream out,
            ManagedProcessStore.ManagedProcessFiles files)
            throws IOException {
        try (var ignored = acquireProcessLock(files)) {
            stopLocked(paths, store, board, out, files);
        }
    }

    private static AcquiredProcessLock acquireProcessLock(ManagedProcessStore.ManagedProcessFiles files)
            throws IOException {
        Lock processLock =
                PROCESS_LOCKS.get(files.processLockFile().toAbsolutePath().normalize());
        processLock.lock();
        FileChannel channel = null;
        FileLock fileLock = null;
        try {
            channel = FileChannel.open(files.processLockFile(), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            fileLock = channel.lock();
            if (!fileLock.isValid()) {
                throw new TrelloBoardSetupException(
                        "setup_process_lock_failed", "Could not acquire the managed worker process lock.");
            }
            return new AcquiredProcessLock(processLock, channel, fileLock);
        } catch (IOException | RuntimeException e) {
            closeQuietly(fileLock, e);
            closeQuietly(channel, e);
            processLock.unlock();
            throw e;
        }
    }

    private static void closeQuietly(AutoCloseable closeable, Throwable originalFailure) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception closeFailure) {
            originalFailure.addSuppressed(closeFailure);
        }
    }

    private record AcquiredProcessLock(Lock processLock, FileChannel channel, FileLock fileLock)
            implements AutoCloseable {
        @Override
        public void close() throws IOException {
            try (channel;
                    fileLock) {
                // try-with-resources closes the OS lock and channel before releasing the in-process lock.
            } finally {
                processLock.unlock();
            }
        }
    }

    private void stopPidFiles(LocalWorkerPaths paths, ManagedProcessStore store, PrintStream out) throws IOException {
        List<Path> pidFiles = store.pidFiles();
        if (pidFiles.isEmpty()) {
            out.println("No managed Symphony process found");
            return;
        }
        for (Path pidFile : pidFiles) {
            Long pid = store.readPid(pidFile);
            String name = PathNames.fileName(pidFile).replaceFirst("\\.pid$", "");
            if (pid != null && platform.isAlive(pid) && platform.isManaged(pid, paths.appHome())) {
                stopPid(store, store.filesFromPidFile(pidFile), pid);
                out.println("Stopped " + name);
            } else if (pid != null && platform.isAlive(pid)) {
                out.println("Skipped unmanaged stale pid " + name + " pid=" + pid);
            } else {
                store.deletePid(pidFile);
            }
        }
    }

    private void printPidFileStatus(LocalWorkerPaths paths, PrintStream out) throws IOException {
        ManagedProcessStore store = new ManagedProcessStore(paths.stateHome());
        List<Path> pidFiles = store.pidFiles();
        if (pidFiles.isEmpty()) {
            out.println("No managed Symphony process found");
            return;
        }
        for (Path pidFile : pidFiles) {
            Long pid = store.readPid(pidFile);
            String name = PathNames.fileName(pidFile).replaceFirst("\\.pid$", "");
            if (pid != null && platform.isAlive(pid) && platform.isManaged(pid, paths.appHome())) {
                out.println("running " + name + " pid=" + pid);
            } else if (pid != null && platform.isAlive(pid)) {
                out.println("stale " + name + " pid=" + pid + " does not belong to this install");
            } else {
                store.deletePid(pidFile);
                out.println("stopped " + name);
            }
        }
    }

    private ConnectedBoard selectOne(
            ConnectedBoardManifest manifest,
            Optional<String> board,
            Optional<Path> workflow,
            String command,
            Optional<Path> explicitWorkflowEnvPath,
            Path fallbackWorkflowEnvPath,
            boolean validateServerPort) {
        if (board.isPresent() && workflow.isPresent()) {
            throw new TrelloBoardSetupException(
                    "setup_worker_selection_conflict", "--board and --workflow cannot be used together.");
        }
        return board.map(selector -> selectedBoard(manifest, selector))
                .or(() -> workflow.map(workflowSelector -> selectedWorkflow(
                        manifest,
                        workflowSelector,
                        explicitWorkflowEnvPath,
                        fallbackWorkflowEnvPath,
                        validateServerPort)))
                .orElseGet(() -> defaultSelectedBoard(manifest, command));
    }

    private ConnectedBoard selectedBoard(ConnectedBoardManifest manifest, String selector) {
        List<ConnectedBoard> matches = manifest.findAllByBoard(selector);
        if (matches.isEmpty()) {
            throw new TrelloBoardSetupException(
                    "setup_worker_board_not_found", "No connected Trello board matches \"" + selector + "\".");
        }
        if (matches.size() > 1) {
            throw new TrelloBoardSetupException(
                    "setup_worker_board_ambiguous",
                    "Multiple connected boards match --board. Re-run with a board id, short link, or --workflow.");
        }
        return matches.getFirst();
    }

    private ConnectedBoard selectedWorkflow(
            ConnectedBoardManifest manifest,
            Path workflowSelector,
            Optional<Path> explicitWorkflowEnvPath,
            Path fallbackWorkflowEnvPath,
            boolean validateServerPort) {
        Path workflowPath = workflowSelector.toAbsolutePath().normalize();
        List<ConnectedBoard> matches = manifest.findAllByWorkflow(workflowPath);
        if (matches.size() > 1) {
            throw new TrelloBoardSetupException(
                    "setup_worker_workflow_ambiguous",
                    "Multiple connected-board rows reference --workflow. Repair connected-boards.json, then rerun the command.");
        }
        Path validationEnvPath = explicitWorkflowEnvPath.orElse(fallbackWorkflowEnvPath);
        ConnectedBoard board = matches.isEmpty() ? workflowBoard(workflowPath, validationEnvPath) : matches.getFirst();
        validateExplicitWorkflowSelector(
                workflowPath,
                selectedWorkflowEnvPath(board, explicitWorkflowEnvPath, fallbackWorkflowEnvPath),
                validateServerPort);
        return board;
    }

    private static Path selectedWorkflowEnvPath(
            ConnectedBoard board, Optional<Path> explicitWorkflowEnvPath, Path fallbackWorkflowEnvPath) {
        return explicitWorkflowEnvPath.orElseGet(
                () -> board.envPath() == null ? fallbackWorkflowEnvPath : board.envPath());
    }

    private void validateExplicitWorkflowSelector(Path workflowPath, Path envPath, boolean validateServerPort) {
        validateWorkerWorkflowPath(workflowPath);
        validateWorkerEnvPath(envPath);
        if (!validateServerPort) {
            return;
        }
        boolean workflowServerPortUsed =
                healthChecker.externalHttpPortOverrideSource(envPath).isEmpty();
        WorkflowValidation validation = workflowConfig.diagnosticsValidation(
                workflowPath, WorkflowEnvironmentResolver.resolver(environment, envPath), workflowServerPortUsed);
        if (!validation.ok()) {
            throw new TrelloBoardSetupException(
                    "setup_invalid_arguments",
                    "--workflow must reference a readable workflow file with usable workflow front matter.");
        }
    }

    private ConnectedBoard defaultSelectedBoard(ConnectedBoardManifest manifest, String command) {
        if (manifest.boards().size() == 1) {
            return manifest.boards().getFirst();
        }
        if (manifest.boards().isEmpty()) {
            throw new TrelloBoardSetupException(
                    "setup_worker_board_not_found", "No Trello boards are connected to Symphony.");
        }
        throw new TrelloBoardSetupException(
                "setup_worker_board_required",
                "Multiple Trello boards are connected. Re-run with --board NAME or --workflow PATH for " + command
                        + ".");
    }

    private List<ConnectedBoard> selectForStop(
            ConnectedBoardManifest manifest,
            Optional<String> board,
            Optional<Path> workflow,
            Path explicitWorkflowEnvPath) {
        if (board.isPresent() || workflow.isPresent()) {
            return List.of(
                    selectOne(manifest, board, workflow, "stop", Optional.empty(), explicitWorkflowEnvPath, false));
        }
        return manifest.boards();
    }

    private List<ConnectedBoard> selectForStatus(
            ConnectedBoardManifest manifest,
            Optional<String> board,
            Optional<Path> workflow,
            Path explicitWorkflowEnvPath) {
        if (board.isPresent() || workflow.isPresent()) {
            return List.of(
                    selectOne(manifest, board, workflow, "status", Optional.empty(), explicitWorkflowEnvPath, false));
        }
        return manifest.boards();
    }

    private ConnectedBoard workflowBoard(Path workflowPath, Path envPath) {
        var environmentResolver = WorkflowEnvironmentResolver.resolver(environment, envPath);
        String boardId = workflowConfig
                .boardId(workflowPath, environmentResolver)
                .orElseGet(() -> PathNames.fileName(workflowPath));
        int serverPort = workflowConfig
                .serverPort(workflowPath, environmentResolver)
                .orElse(TrelloBoardSetup.DEFAULT_SERVER_PORT);
        return new ConnectedBoard(
                boardId,
                boardId,
                PathNames.fileName(workflowPath),
                "",
                workflowPath,
                envPath,
                TrelloBoardSetup.DEFAULT_WORKSPACE_ROOT,
                serverPort,
                false,
                List.of(),
                false);
    }

    private List<ConnectedBoard> withDefaultEnvForExplicitWorkflow(
            LocalWorkerPaths paths, Optional<Path> workflow, List<ConnectedBoard> boards) {
        if (workflow.isEmpty()) {
            return boards;
        }
        return boards.stream()
                .map(board ->
                        board.envPath() == null ? workflowBoard(board.workflowPath(), paths.defaultEnvPath()) : board)
                .toList();
    }

    private static ManagedProcessPlatform platformForCurrentOs() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
                ? new WindowsManagedProcessPlatform()
                : new PosixManagedProcessPlatform();
    }

    private static String javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java")
                .toString();
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
