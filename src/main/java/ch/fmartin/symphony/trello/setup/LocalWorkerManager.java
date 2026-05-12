package ch.fmartin.symphony.trello.setup;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

final class LocalWorkerManager {
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
        if (request.all()) {
            startAll(paths, manifest, request, out);
            return 0;
        }
        ConnectedBoard board = selectOne(manifest, request.board(), request.workflow(), "start");
        Path envPath = request.envPath()
                .orElse(board.envPath() == null ? paths.defaultEnvPath() : board.envPath())
                .toAbsolutePath()
                .normalize();
        start(paths, board, envPath, out);
        return 0;
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
            Path envPath = (board.envPath() == null ? paths.defaultEnvPath() : board.envPath())
                    .toAbsolutePath()
                    .normalize();
            start(paths, board, envPath, out);
        }
    }

    void start(LocalWorkerPaths paths, ConnectedBoard board, Path envPath, PrintStream out) throws IOException {
        ManagedProcessStore store = new ManagedProcessStore(paths.stateHome());
        ManagedProcessStore.ManagedProcessFiles files = store.files(board.workflowPath());
        Files.createDirectories(paths.stateHome());
        Long existingPid = store.readPid(files.pidFile());
        int healthPort = healthChecker.managedHealthPort(board.workflowPath(), board.serverPort(), envPath);
        if (existingPid != null && platform.isAlive(existingPid)) {
            if (!platform.isManaged(existingPid, paths.appHome(), board.workflowPath())) {
                throw new TrelloBoardSetupException(
                        "setup_worker_pid_unmanaged",
                        "State file belongs to another process for " + board.workflowPath() + " pid=" + existingPid);
            }
            BoardHealth health =
                    healthChecker.workflowHealth(board.workflowPath(), board.boardId(), board.boardKey(), healthPort);
            if (health.kind() == BoardHealthKind.SAME_WORKFLOW) {
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
            out.println("Symphony for Trello is already running for \"" + board.boardName() + "\" at "
                    + LocalHealthChecker.localServerUrl(existingHealth.port()));
            return;
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
        ManagedProcessHandle handle =
                platform.start(command, paths.appHome(), processEnvironment, files.stdoutLog(), files.stderrLog());
        store.writePid(files.pidFile(), handle.pid());
        BoardHealth health = healthChecker.waitForSameWorkflow(board, healthPort);
        if (health.kind() != BoardHealthKind.SAME_WORKFLOW) {
            stopPid(store, files, handle.pid());
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
        List<ConnectedBoard> boards = selectForStop(manifest, request.board(), request.workflow());
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

    int status(WorkerStatusRequest request, PrintStream out) throws IOException {
        LocalWorkerPaths paths = LocalWorkerPaths.from(
                request.appHome(), request.configDir(), request.workspaceRoot(), request.stateHome(), environment);
        ConnectedBoardManifest manifest = new ConnectedBoardRepository(paths.manifestPath()).load();
        List<ConnectedBoard> boards = selectForStatus(manifest, request.board(), request.workflow());
        if (boards.isEmpty()) {
            printPidFileStatus(paths, out);
            return 0;
        }
        ManagedProcessStore store = new ManagedProcessStore(paths.stateHome());
        for (ConnectedBoard board : boards) {
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
        ConnectedBoard board = selectOne(manifest, request.board(), request.workflow(), "logs");
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
        Long pid = store.readPid(files.pidFile());
        if (pid == null || !platform.isAlive(pid)) {
            store.deletePid(files.pidFile());
            BoardHealth health = healthChecker.boardHealth(board);
            if (health.kind() == BoardHealthKind.SAME_WORKFLOW) {
                throw new TrelloBoardSetupException(
                        "setup_worker_untracked",
                        "Cannot stop "
                                + files.displayName()
                                + " because the worker is healthy but has no managed pid. Stop the process manually, then start it again with symphony-trello start.");
            }
            out.println("Stopped " + files.displayName());
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

    private void stopPidFiles(LocalWorkerPaths paths, ManagedProcessStore store, PrintStream out) throws IOException {
        List<Path> pidFiles = store.pidFiles();
        if (pidFiles.isEmpty()) {
            out.println("No managed Symphony process found");
            return;
        }
        for (Path pidFile : pidFiles) {
            Long pid = store.readPid(pidFile);
            String name = pidFile.getFileName().toString().replaceFirst("\\.pid$", "");
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
            String name = pidFile.getFileName().toString().replaceFirst("\\.pid$", "");
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
            ConnectedBoardManifest manifest, Optional<String> board, Optional<Path> workflow, String command) {
        if (board.isPresent() && workflow.isPresent()) {
            throw new TrelloBoardSetupException(
                    "setup_worker_selection_conflict", "--board and --workflow cannot be used together.");
        }
        if (board.isPresent()) {
            return manifest.findByBoard(board.orElseThrow())
                    .orElseThrow(() -> new TrelloBoardSetupException(
                            "setup_worker_board_not_found",
                            "No connected Trello board matches \"" + board.orElseThrow() + "\"."));
        }
        if (workflow.isPresent()) {
            Path workflowPath = workflow.orElseThrow().toAbsolutePath().normalize();
            return manifest.findByWorkflow(workflowPath).orElse(workflowBoard(workflowPath));
        }
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
            ConnectedBoardManifest manifest, Optional<String> board, Optional<Path> workflow) {
        if (board.isPresent() || workflow.isPresent()) {
            return List.of(selectOne(manifest, board, workflow, "stop"));
        }
        return manifest.boards();
    }

    private List<ConnectedBoard> selectForStatus(
            ConnectedBoardManifest manifest, Optional<String> board, Optional<Path> workflow) {
        if (board.isPresent() || workflow.isPresent()) {
            return List.of(selectOne(manifest, board, workflow, "status"));
        }
        return manifest.boards();
    }

    private ConnectedBoard workflowBoard(Path workflowPath) {
        String boardId = workflowConfig
                .boardId(workflowPath)
                .orElseGet(() -> workflowPath.getFileName().toString());
        int serverPort = workflowConfig.serverPort(workflowPath).orElse(TrelloBoardSetup.DEFAULT_SERVER_PORT);
        return new ConnectedBoard(
                boardId,
                boardId,
                workflowPath.getFileName().toString(),
                "",
                workflowPath,
                null,
                TrelloBoardSetup.DEFAULT_WORKSPACE_ROOT,
                serverPort,
                false,
                List.of(),
                false);
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
