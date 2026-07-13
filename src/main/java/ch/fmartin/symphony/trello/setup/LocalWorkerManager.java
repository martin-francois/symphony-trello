package ch.fmartin.symphony.trello.setup;

import ch.fmartin.symphony.trello.TrelloEnvironment;
import ch.fmartin.symphony.trello.config.EffectiveConfig;
import ch.fmartin.symphony.trello.config.EnvironmentReferences;
import ch.fmartin.symphony.trello.config.LocalEnvironment;
import com.google.common.util.concurrent.Striped;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
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
import java.util.function.Function;

final class LocalWorkerManager {
    private static final int STARTUP_LOG_BYTE_LIMIT = 128 * 1024;
    private static final Striped<Lock> PROCESS_LOCKS = Striped.lazyWeakLock(1024);

    private final Map<String, String> environment;
    private final WorkflowConfigEditor workflowConfig;
    private final LocalHealthChecker healthChecker;
    private final ManagedProcessPlatform platform;
    private final LocalLogTailer logTailer;
    private final TrelloCredentialPreflight credentialPreflight;

    /**
     * Verifies resolved Trello credentials against the configured endpoint before a worker launch.
     * Implementations throw {@link TrelloBoardSetupException} with code {@code trello_auth_failed}
     * or {@code trello_permission_denied} for credential problems and {@code trello_api_request}
     * for transport problems.
     */
    @FunctionalInterface
    interface TrelloCredentialPreflight {
        void verify(URI endpoint, String apiKey, String apiToken);
    }

    LocalWorkerManager(Map<String, String> environment) {
        this(environment, new WorkflowConfigEditor());
    }

    LocalWorkerManager(Map<String, String> environment, WorkflowConfigEditor workflowConfig) {
        this(
                environment,
                workflowConfig,
                new LocalHealthChecker(environment, workflowConfig),
                platformForCurrentOs(),
                new LocalLogTailer(),
                defaultCredentialPreflight());
    }

    LocalWorkerManager(
            Map<String, String> environment,
            WorkflowConfigEditor workflowConfig,
            LocalHealthChecker healthChecker,
            ManagedProcessPlatform platform,
            LocalLogTailer logTailer,
            TrelloCredentialPreflight credentialPreflight) {
        this.environment = Map.copyOf(environment);
        this.workflowConfig = workflowConfig;
        this.healthChecker = healthChecker;
        this.platform = platform;
        this.logTailer = logTailer;
        this.credentialPreflight = credentialPreflight;
    }

    private static TrelloCredentialPreflight defaultCredentialPreflight() {
        return (endpoint, apiKey, apiToken) -> new TrelloBoardSetup(ConnectedBoardRepository.jsonMapper())
                .getMemberInfo(new TrelloBoardSetup.MemberInfoRequest(
                        endpoint, new TrelloBoardSetup.TrelloCredentials(apiKey, apiToken)));
    }

    int start(StartWorkerRequest request, PrintStream out) throws IOException {
        LocalWorkerPaths paths = LocalWorkerPaths.from(
                request.appHome(), request.configDir(), request.workspaceRoot(), request.stateHome(), environment);
        ConnectedBoardManifest manifest = new ConnectedBoardRepository(paths.manifestPath()).loadForLifecycle();
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
                    "Trello board " + DisplayNames.quotedName(row.boardName())
                            + " is already managed by a running worker for another workflow."
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
                out.println("Skipped workflow file because Trello board "
                        + DisplayNames.quotedName(running.boardName())
                        + " is already managed by another running workflow.");
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
        workflowConfig.requireLaunchableWorkflowFile(board.workflowPath());
        Function<String, Optional<String>> workflowEnvironment =
                WorkflowEnvironmentResolver.resolver(environment, envPath);
        ManagedProcessStore store = new ManagedProcessStore(paths.stateHome());
        ManagedProcessStore.ManagedProcessFiles files = store.files(board.workflowPath());
        Files.createDirectories(paths.stateHome());
        startWithProcessLock(paths, board, envPath, explicitEnvOverride, out, store, files, workflowEnvironment);
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
            ManagedProcessStore.ManagedProcessFiles files,
            Function<String, Optional<String>> workflowEnvironment)
            throws IOException {
        try (var ignored = acquireProcessLock(files)) {
            startLocked(paths, board, envPath, explicitEnvOverride, out, store, files, workflowEnvironment);
        }
    }

    private void startLocked(
            LocalWorkerPaths paths,
            ConnectedBoard board,
            Path envPath,
            boolean explicitEnvOverride,
            PrintStream out,
            ManagedProcessStore store,
            ManagedProcessStore.ManagedProcessFiles files,
            Function<String, Optional<String>> workflowEnvironment)
            throws IOException {
        int healthPort = healthChecker.managedHealthPort(board.workflowPath(), board.serverPort(), envPath);
        Optional<WorkerCredentialUsage> credentialUsage = workerCredentialUsage(board.workflowPath(), envPath);

        Long existingPid = store.readPid(files.pidFile());
        boolean restartManagedWorker = false;
        BoardHealth existingHealth;
        if (existingPid != null && platform.isAlive(existingPid)) {
            if (!platform.isManaged(existingPid, paths.appHome(), board.workflowPath())) {
                String pidToken = pathToken(paths, files.pidFile());
                throw new TrelloBoardSetupException(
                        "setup_worker_pid_unmanaged",
                        "State file belongs to another process for selected workflow file pid=" + existingPid
                                + " pid_file_token=" + pidToken + lookupHint(pidToken));
            }
            BoardHealth health =
                    healthChecker.workflowHealth(board.workflowPath(), board.boardId(), board.boardKey(), healthPort);
            if (health.kind() == BoardHealthKind.SAME_WORKFLOW) {
                handleSameWorkflowWorker(
                        paths,
                        board,
                        envPath,
                        explicitEnvOverride,
                        health,
                        store,
                        files,
                        out,
                        Optional.of(existingPid));
                return;
            }
            existingHealth = health;
            restartManagedWorker = true;
        } else {
            existingHealth =
                    healthChecker.workflowHealth(board.workflowPath(), board.boardId(), board.boardKey(), healthPort);
        }

        if (existingHealth.kind() == BoardHealthKind.SAME_WORKFLOW) {
            handleSameWorkflowWorker(
                    paths, board, envPath, explicitEnvOverride, existingHealth, store, files, out, Optional.empty());
            return;
        }

        boolean workflowServerPortUsed = workflowServerPortUsed(envPath);
        EffectiveConfig launchConfig = workflowConfig.prepareLaunchWorkflow(
                board.workflowPath(), workflowEnvironment, workflowServerPortUsed, paths.configDir());
        credentialUsage.ifPresent(this::validateWorkerCredentials);
        workflowConfig.validateLaunchDispatch(board.workflowPath(), launchConfig);

        if (!restartManagedWorker) {
            rejectPortConflict(board, existingHealth, healthPort);
        }

        verifyTrelloCredentialsBeforeLaunch(launchConfig, credentialUsage);
        if (restartManagedWorker) {
            stopPid(store, files, existingPid);
            BoardHealth postStopHealth =
                    healthChecker.workflowHealth(board.workflowPath(), board.boardId(), board.boardKey(), healthPort);
            if (handlePostStopHealth(paths, board, envPath, explicitEnvOverride, store, files, out, postStopHealth)) {
                return;
            }
        } else if (existingPid != null && !platform.isAlive(existingPid)) {
            store.deletePid(files.pidFile());
        }

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
        BoardHealth health = healthChecker.waitForSameWorkflow(board, healthPort, () -> platform.isAlive(handle.pid()));
        if (health.kind() != BoardHealthKind.SAME_WORKFLOW) {
            stopPid(store, files, handle.pid());
            throwKnownStartupFailure(files, logOffsets, platform.appendsToExistingLogs(), credentialUsage);
            throw new TrelloBoardSetupException(
                    "setup_start_unhealthy",
                    "Symphony start returned successfully, but " + LocalHealthChecker.localStateUrl(health.port())
                            + " did not report the expected workflow and board for "
                            + DisplayNames.quotedName(board.boardName()) + ". Run symphony-trello diagnostics "
                            + "to collect safe troubleshooting details, or rerun symphony-trello logs with the same "
                            + "board or workflow selector for local log output."
                            + startupPrivateContextHint(paths, board, files));
        }
        if (!platform.isAlive(handle.pid())
                || !platform.isManaged(handle.pid(), paths.appHome(), board.workflowPath())) {
            stopStartedProcess(store, files, handle.pid());
            throw new TrelloBoardSetupException(
                    "setup_start_unhealthy",
                    "Symphony reported the expected workflow and board on "
                            + LocalHealthChecker.localStateUrl(health.port())
                            + ", but the newly started managed process is not running for this install. "
                            + "Run symphony-trello diagnostics to collect safe troubleshooting details, "
                            + "or rerun symphony-trello logs with the same board or workflow selector for local log output."
                            + startupPrivateContextHint(paths, board, files));
        }
        out.println("Started Symphony for Trello: " + DisplayNames.quotedName(board.boardName()));
    }

    private boolean workflowServerPortUsed(Path envPath) {
        return healthChecker.externalHttpPortOverrideSource(envPath).isEmpty();
    }

    private boolean handlePostStopHealth(
            LocalWorkerPaths paths,
            ConnectedBoard board,
            Path envPath,
            boolean explicitEnvOverride,
            ManagedProcessStore store,
            ManagedProcessStore.ManagedProcessFiles files,
            PrintStream out,
            BoardHealth postStopHealth)
            throws IOException {
        return switch (postStopHealth.kind()) {
            case STOPPED -> false;
            case PORT_USED, WRONG_WORKFLOW -> {
                rejectPortConflict(board, postStopHealth, postStopHealth.port());
                yield true;
            }
            case SAME_WORKFLOW -> {
                handleSameWorkflowWorker(
                        paths,
                        board,
                        envPath,
                        explicitEnvOverride,
                        postStopHealth,
                        store,
                        files,
                        out,
                        Optional.empty());
                yield true;
            }
        };
    }

    private static void rejectPortConflict(ConnectedBoard board, BoardHealth health, int healthPort) {
        switch (health.kind()) {
            case PORT_USED ->
                throw new TrelloBoardSetupException(
                        "setup_worker_port_in_use",
                        "Local HTTP status port " + healthPort
                                + " is already in use by another process, so the worker for "
                                + DisplayNames.quotedName(board.boardName())
                                + " cannot start.\nFree the port or change the workflow server.port, then rerun symphony-trello start.");
            case WRONG_WORKFLOW -> {
                String servingWorkflow = health.actualWorkflowPath()
                        .map(ignored -> " It currently serves another Symphony workflow.")
                        .orElse("");
                throw new TrelloBoardSetupException(
                        "setup_worker_port_in_use",
                        "Local HTTP status port " + healthPort
                                + " is already serving another Symphony workflow, so the worker for "
                                + DisplayNames.quotedName(board.boardName()) + " cannot start." + servingWorkflow
                                + "\nStop that worker or change the workflow server.port, then rerun symphony-trello start.");
            }
            case SAME_WORKFLOW, STOPPED -> {}
        }
    }

    private void handleSameWorkflowWorker(
            LocalWorkerPaths paths,
            ConnectedBoard board,
            Path envPath,
            boolean explicitEnvOverride,
            BoardHealth health,
            ManagedProcessStore store,
            ManagedProcessStore.ManagedProcessFiles files,
            PrintStream out,
            Optional<Long> recordedManagedPid)
            throws IOException {
        printIgnoredEnvOverride(paths, board, envPath, explicitEnvOverride, out);
        recordedManagedPid.ifPresentOrElse(
                pid -> out.println("Symphony for Trello is already running for "
                        + DisplayNames.quotedName(board.boardName()) + " pid=" + pid),
                () -> out.println("Symphony for Trello is already running for "
                        + DisplayNames.quotedName(board.boardName()) + " at "
                        + LocalHealthChecker.localServerUrl(health.port())));
        if (recordedManagedPid.isEmpty()) {
            repairOrReportUntrackedWorker(paths, board, health, store, files, out);
        }
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
        if (health.kind() != BoardHealthKind.SAME_WORKFLOW) {
            return Optional.empty();
        }
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
                        requiredEnvironmentCredential(references.apiKey(), TrelloEnvironment.API_KEY),
                        requiredEnvironmentCredential(references.apiToken(), TrelloEnvironment.API_TOKEN)));
    }

    private WorkerCredentialUsage workerCredentialUsage(
            Path envPath, Optional<String> apiKeyEnvironment, Optional<String> apiTokenEnvironment) {
        Map<String, String> dotenv = LocalEnvironment.load(envPath);
        return new WorkerCredentialUsage(
                envPath,
                apiKeyEnvironment,
                apiTokenEnvironment,
                credentialSource(apiKeyEnvironment, dotenv),
                credentialSource(apiTokenEnvironment, dotenv),
                dotenvValue(apiKeyEnvironment, dotenv),
                dotenvValue(apiTokenEnvironment, dotenv));
    }

    private static Optional<String> dotenvValue(Optional<String> environmentName, Map<String, String> dotenv) {
        return environmentName.map(dotenv::get).filter(value -> !TrelloCredentialStore.blank(value));
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

    private void verifyTrelloCredentialsBeforeLaunch(
            EffectiveConfig launchConfig, Optional<WorkerCredentialUsage> credentialUsage) {
        try {
            credentialPreflight.verify(
                    URI.create(launchConfig.tracker().endpoint()),
                    launchConfig.tracker().apiKey(),
                    launchConfig.tracker().apiToken());
        } catch (TrelloBoardSetupException e) {
            if (isCredentialFailure(e)) {
                throw withCredentialContext(e, credentialUsage);
            }
            if (isMalformedCredentialRejection(e)) {
                // The preflight request only carries the resolved key and token, so a Trello
                // invalid-request rejection here means the resolved credential values are
                // malformed, not that the request shape is wrong.
                throw withCredentialContext(
                        new TrelloBoardSetupException(
                                "trello_auth_failed",
                                "Trello rejected the resolved API credentials while starting Symphony.",
                                e),
                        credentialUsage);
            }
            // Transport or transient Trello API problems must not block the launch; the worker
            // retries them and the post-start health check still classifies startup failures.
        }
    }

    private static boolean isCredentialFailure(TrelloBoardSetupException e) {
        return "trello_auth_failed".equals(e.code()) || "trello_permission_denied".equals(e.code());
    }

    private static boolean isMalformedCredentialRejection(TrelloBoardSetupException e) {
        return "trello_invalid_request".equals(e.code());
    }

    private static TrelloBoardSetupException withCredentialContext(
            TrelloBoardSetupException exception, Optional<WorkerCredentialUsage> credentialUsage) {
        return credentialUsage
                .map(usage -> exception
                        .withDotenvPath(usage.envPath())
                        .withTrelloCredentialEnvironmentNames(
                                usage.apiKeyEnvironment().orElse(TrelloEnvironment.API_KEY),
                                usage.apiTokenEnvironment().orElse(TrelloEnvironment.API_TOKEN))
                        .withTrelloCredentialSources(usage.apiKeySource(), usage.apiTokenSource()))
                .orElse(exception);
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
        rejectReferenceLookingDotenvCredential(
                usage.envPath(), usage.apiKeyEnvironment(), usage.apiKeySource(), usage.apiKeyDotenvValue());
        rejectReferenceLookingDotenvCredential(
                usage.envPath(), usage.apiTokenEnvironment(), usage.apiTokenSource(), usage.apiTokenDotenvValue());
    }

    /**
     * The worker uses a dotenv value only when the shell environment does not provide the
     * variable, so only a value the dotenv file actually contributes is checked against the
     * shared credential-file contract in {@link TrelloCredentialStore#dotenvCredential}. The
     * check runs before the Trello credential preflight and before the worker launch, so a
     * reference-looking value fails locally instead of reaching Trello as a literal credential.
     */
    private static void rejectReferenceLookingDotenvCredential(
            Path envPath,
            Optional<String> environmentName,
            TrelloBoardSetupException.TrelloCredentialSource source,
            Optional<String> dotenvValue) {
        if (source != TrelloBoardSetupException.TrelloCredentialSource.DOTENV_FILE) {
            return;
        }
        environmentName.ifPresent(name -> {
            try {
                TrelloCredentialStore.dotenvCredential(name, dotenvValue.orElse(null));
            } catch (TrelloBoardSetupException e) {
                // withDotenvPath copies code, message, and cause into a context-enriched copy,
                // and the fresh trace still names this rejection site.
                throw e.withDotenvPath(envPath); // NOPMD - PreserveStackTrace: enriched copy
            }
        });
    }

    private static TrelloBoardSetupException missingWorkerCredentialException(
            String code, String message, WorkerCredentialUsage usage) {
        return new TrelloBoardSetupException(code, message)
                .withDotenvPath(usage.envPath())
                .withTrelloCredentialEnvironmentNames(
                        usage.apiKeyEnvironment().orElse(TrelloEnvironment.API_KEY),
                        usage.apiTokenEnvironment().orElse(TrelloEnvironment.API_TOKEN));
    }

    private static Optional<String> requiredEnvironmentCredential(
            Optional<String> configuredValue, String defaultEnvironmentName) {
        if (configuredValue.isEmpty()) {
            return Optional.of(defaultEnvironmentName);
        }
        return configuredValue.map(String::trim).flatMap(LocalWorkerManager::environmentCredentialName);
    }

    private static Optional<String> environmentCredentialName(String configuredValue) {
        return EnvironmentReferences.referenceName(configuredValue);
    }

    private static void throwKnownStartupFailure(
            ManagedProcessStore.ManagedProcessFiles files,
            StartupLogOffsets logOffsets,
            boolean appendsToExistingLogs,
            Optional<WorkerCredentialUsage> credentialUsage) {
        String logs = startupLogs(files, logOffsets, appendsToExistingLogs);
        if (logs.contains("Trello authentication failed")) {
            throw withCredentialContext(
                    new TrelloBoardSetupException(
                            "trello_auth_failed", "Trello authentication failed while starting Symphony."),
                    credentialUsage);
        }
        if (logs.contains("Trello permission denied")) {
            throw new TrelloBoardSetupException(
                    "trello_permission_denied", "Trello permission denied while starting Symphony.");
        }
        if (logs.contains("Trello board is archived")) {
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
            TrelloBoardSetupException.TrelloCredentialSource apiTokenSource,
            Optional<String> apiKeyDotenvValue,
            Optional<String> apiTokenDotenvValue) {}

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
        ConnectedBoardManifest manifest = loadLifecycleManifest(paths, request.workflow());
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

    void rotateLogsForReplacedBoards(LocalWorkerPaths paths, ConnectedBoard board, List<ConnectedBoard> replacedBoards)
            throws IOException {
        ManagedProcessStore store = new ManagedProcessStore(paths.stateHome());
        for (ConnectedBoard replaced : replacedBoards) {
            if (PathsEqual.samePath(replaced.workflowPath(), board.workflowPath())
                    && !replaced.boardId().equals(board.boardId())) {
                store.rotateLogsForNewBoardIdentity(replaced.workflowPath());
            }
        }
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
        ConnectedBoardManifest manifest = loadLifecycleManifest(paths, request.workflow());
        List<ConnectedBoard> boards =
                selectForStatus(manifest, request.board(), request.workflow(), paths.defaultEnvPath());
        boards = withDefaultEnvForExplicitWorkflow(paths, request.workflow(), boards);
        if (boards.isEmpty()) {
            printPidFileStatus(paths, out);
            return 0;
        }
        ManagedProcessStore store = new ManagedProcessStore(paths.stateHome());
        Set<String> duplicateBoardNames = duplicateBoardNames(boards);
        for (ConnectedBoard board : boards) {
            String boardLabel = statusBoardLabel(board, duplicateBoardNames);
            ManagedProcessStore.ManagedProcessFiles files = store.files(board.workflowPath());
            WorkflowValidation workflowDiagnostics = workflowConfig.diagnosticsValidation(
                    board.workflowPath(), WorkflowEnvironmentResolver.resolver(environment, board.envPath()));
            if (!workflowDiagnostics.ok()) {
                out.println("invalid " + boardLabel + " " + workflowDiagnostics.message()
                        + " in that board's workflow file");
                continue;
            }
            Long pid = store.readPid(files.pidFile());
            BoardHealth health;
            try {
                health = healthChecker.boardHealth(board);
            } catch (TrelloBoardSetupException failure) {
                out.println("invalid " + boardLabel + " local status configuration (" + failure.code() + ")");
                continue;
            } catch (RuntimeException failure) {
                out.println("invalid " + boardLabel + " local status probe failed");
                continue;
            }
            boolean livePid = pid != null && platform.isAlive(pid);
            boolean managedPid = livePid && platform.isManaged(pid, paths.appHome(), board.workflowPath());
            if (managedPid) {
                printManagedStatus(boardLabel, pid, health, out);
            } else {
                verifiedManagedWorkerPid(paths, board, health)
                        .ifPresentOrElse(
                                verifiedPid -> printVerifiedWorkerStatus(boardLabel, pid, health, verifiedPid, out),
                                () -> printUnverifiedStatus(paths, files, boardLabel, pid, livePid, health, out));
            }
        }
        return 0;
    }

    private static void printManagedStatus(String boardLabel, long pid, BoardHealth health, PrintStream out) {
        boolean matchingEndpointPid = health.kind() == BoardHealthKind.SAME_WORKFLOW
                && health.workerPid()
                        .filter(reportedPid -> reportedPid.equals(pid))
                        .isPresent();
        if (matchingEndpointPid) {
            out.println(
                    "running " + boardLabel + " pid=" + pid + " " + LocalHealthChecker.localServerUrl(health.port()));
            return;
        }
        String reason = health.kind() == BoardHealthKind.SAME_WORKFLOW
                ? "WORKER_PID_MISMATCH"
                : health.kind().toString();
        out.println("unhealthy " + boardLabel + " pid=" + pid + " " + LocalHealthChecker.localServerUrl(health.port())
                + " (" + reason + ")");
    }

    private static void printVerifiedWorkerStatus(
            String boardLabel, Long recordedPid, BoardHealth health, long verifiedPid, PrintStream out) {
        String tracking = recordedPid == null ? "untracked, no managed pid" : "stale managed pid=" + recordedPid;
        out.println("running " + boardLabel + " pid=" + verifiedPid + " "
                + LocalHealthChecker.localServerUrl(health.port()) + " (" + tracking + ")");
    }

    private static void printUnverifiedStatus(
            LocalWorkerPaths paths,
            ManagedProcessStore.ManagedProcessFiles files,
            String boardLabel,
            Long pid,
            boolean livePid,
            BoardHealth health,
            PrintStream out) {
        if (livePid) {
            out.println("stale " + boardLabel + " pid=" + pid + " does not belong to this install pid_file_token="
                    + pathToken(paths, files.pidFile()));
        } else if (health.kind() == BoardHealthKind.SAME_WORKFLOW) {
            out.println("unhealthy " + boardLabel + " " + LocalHealthChecker.localServerUrl(health.port())
                    + " (UNVERIFIED_WORKER_PID)");
        } else if (health.kind() == BoardHealthKind.STOPPED) {
            out.println("stopped " + boardLabel);
        } else {
            out.println("unhealthy " + boardLabel + " " + LocalHealthChecker.localServerUrl(health.port()) + " ("
                    + health.kind() + ")");
        }
    }

    private static Set<String> duplicateBoardNames(List<ConnectedBoard> boards) {
        Set<String> seen = new HashSet<>();
        Set<String> duplicates = new HashSet<>();
        for (ConnectedBoard board : boards) {
            if (!seen.add(board.boardName())) {
                duplicates.add(board.boardName());
            }
        }
        return duplicates;
    }

    private static String statusBoardLabel(ConnectedBoard board, Set<String> duplicateBoardNames) {
        String label = DisplayNames.quotedName(board.boardName());
        if (!duplicateBoardNames.contains(board.boardName())) {
            return label;
        }
        // Duplicate connected board names need a safe disambiguator so the user can pick the right
        // --board or --workflow selector.
        return label + " [" + board.boardKey() + " " + PathNames.fileName(board.workflowPath()) + "]";
    }

    int logs(WorkerLogsRequest request, PrintStream out) throws IOException {
        LocalWorkerPaths paths = LocalWorkerPaths.from(
                request.appHome(), request.configDir(), request.workspaceRoot(), request.stateHome(), environment);
        ConnectedBoardManifest manifest = loadLifecycleManifest(paths, request.workflow());
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
                    out.println("Stopped untracked managed worker for " + DisplayNames.quotedName(board.boardName())
                            + " pid=" + verifiedPid.get());
                    return;
                }
                throw new TrelloBoardSetupException(
                        "setup_worker_untracked",
                        "Cannot stop the worker for "
                                + DisplayNames.quotedName(board.boardName())
                                + " because the worker is healthy but has no managed pid. Stop the process manually, then start it again with symphony-trello start."
                                + health.workerPid()
                                        .map(reportedPid -> " Reported worker pid=" + reportedPid)
                                        .orElse(""));
            }
            out.println("Symphony for Trello is already stopped for " + DisplayNames.quotedName(board.boardName()));
            return;
        }
        if (!platform.isManaged(pid, paths.appHome(), board.workflowPath())) {
            // The process is not ours, but the pid file is managed state pointing at a foreign
            // process; remove it so repeated stops do not keep reporting the same stale pid.
            out.println("Skipped unmanaged stale pid for " + DisplayNames.quotedName(board.boardName()) + " pid=" + pid
                    + " pid_file_token=" + pathToken(paths, files.pidFile()));
            removeStalePidFile(paths, store, files.pidFile(), out);
            return;
        }
        stopPid(store, files, pid);
        out.println("Stopped Symphony for Trello for " + DisplayNames.quotedName(board.boardName()));
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
            String label = stateFileLabel(pidFile);
            if (pid != null && platform.isAlive(pid) && platform.isManaged(pid, paths.appHome())) {
                stopPid(store, store.filesFromPidFile(pidFile), pid);
                out.println("Stopped " + label);
            } else if (pid != null && platform.isAlive(pid)) {
                out.println("Skipped unmanaged stale pid " + label + " pid=" + pid + " pid_file_token="
                        + pathToken(paths, pidFile));
                removeStalePidFile(paths, store, pidFile, out);
            } else {
                store.deletePid(pidFile);
            }
        }
    }

    /**
     * Pid-file fallback output runs only when no boards are connected, so no board name exists;
     * the label is the workflow file name without the internal state-file hash suffix.
     */
    private static String stateFileLabel(Path pidFile) {
        String name = PathNames.fileName(pidFile).replaceFirst("\\.pid$", "");
        return name.replaceFirst("\\.[0-9a-f]{12}$", "");
    }

    /**
     * Refusing to kill the unrelated process is the safety contract; the pid file cleanup is only
     * best effort on top. A failed or already-done removal must say so instead of claiming the
     * file was removed.
     */
    private void removeStalePidFile(LocalWorkerPaths paths, ManagedProcessStore store, Path pidFile, PrintStream out) {
        try {
            if (store.deletePid(pidFile)) {
                out.println("Removed the stale managed pid file. The unrelated process was not stopped.");
            } else {
                out.println("The stale managed pid file was already removed. The unrelated process was not stopped.");
            }
        } catch (IOException e) {
            out.println("Could not remove the stale managed pid file. The unrelated process was not stopped.");
            String pidToken = pathToken(paths, pidFile);
            out.println("Remove the stale managed pid file manually, then rerun stop. pid_file_token=" + pidToken
                    + lookupHint(pidToken));
        }
    }

    private static String startupPrivateContextHint(
            LocalWorkerPaths paths, ConnectedBoard board, ManagedProcessStore.ManagedProcessFiles files) {
        String workflowToken = pathToken(paths, board.workflowPath());
        return " Private context tokens: workflow=" + workflowToken + ", stdout_log="
                + pathToken(paths, files.stdoutLog()) + ", stderr_log=" + pathToken(paths, files.stderrLog()) + "."
                + lookupHint(workflowToken);
    }

    private static String lookupHint(String token) {
        return PrivateContextTokens.lookupHint(token);
    }

    private static String pathToken(LocalWorkerPaths paths, Path path) {
        return PrivateContextTokens.pathToken(paths.configDir(), path);
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
            String label = stateFileLabel(pidFile);
            if (pid != null && platform.isAlive(pid) && platform.isManaged(pid, paths.appHome())) {
                out.println("untracked " + label + " pid=" + pid
                        + " (no connected workflow metadata; runtime identity not verified)");
            } else if (pid != null && platform.isAlive(pid)) {
                out.println("stale " + label + " pid=" + pid + " does not belong to this install pid_file_token="
                        + pathToken(paths, pidFile));
            } else {
                out.println("stopped " + label);
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
                    "Multiple connected-board rows reference --workflow. Repair "
                            + ConnectedBoardManifest.FILE_NAME
                            + ", then rerun the command.");
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

    private void validateExplicitWorkflowSelector(Path workflowPath, Path envPath, boolean validateForLaunch) {
        if (validateForLaunch) {
            // start reports unusable explicit workflows through the launch validation path so the
            // error carries the underlying loader or resolver cause; content problems are raised
            // by resolveLaunchConfig before any worker is launched. stop, status, and logs keep
            // the lenient recovery behavior for invalid explicit workflows.
            workflowConfig.requireLaunchableWorkflowFile(workflowPath);
        } else {
            validateWorkerWorkflowPath(workflowPath);
        }
        validateWorkerEnvPath(envPath);
    }

    private static ConnectedBoardManifest loadLifecycleManifest(LocalWorkerPaths paths, Optional<Path> explicitWorkflow)
            throws IOException {
        explicitWorkflow.ifPresent(LocalWorkerManager::requireExistingExplicitWorkflow);
        return new ConnectedBoardRepository(paths.manifestPath()).loadForLifecycle();
    }

    private static void requireExistingExplicitWorkflow(Path workflow) {
        Path workflowPath = workflow.toAbsolutePath().normalize();
        if (!Files.exists(workflowPath)) {
            throw new TrelloBoardSetupException(
                    "setup_invalid_arguments", "--workflow must point to an existing workflow file.");
        }
        validateWorkerWorkflowPath(workflowPath);
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
