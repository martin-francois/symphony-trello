package ch.fmartin.symphony.trello.setup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.fmartin.symphony.trello.config.ConfigDefaults;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

final class LocalWorkerManagerTestFixture {
    final LocalWorkerPaths paths;
    final ManagedProcessPlatform platform;
    final LocalHealthChecker healthChecker;
    final LocalWorkerManager manager;

    LocalWorkerManagerTestFixture(Path tempDir) {
        this.paths = LocalWorkerPaths.from(
                Optional.of(tempDir.resolve("app")),
                Optional.of(tempDir.resolve("config")),
                Optional.of(tempDir.resolve("workspaces")),
                Optional.of(tempDir.resolve("state")),
                Map.of());
        this.platform = mock(ManagedProcessPlatform.class);
        when(platform.appendsToExistingLogs()).thenReturn(true);
        this.healthChecker = mock(LocalHealthChecker.class);
        when(healthChecker.boardHealth(any()))
                .thenReturn(new BoardHealth(
                        BoardHealthKind.STOPPED,
                        ConfigDefaults.DEFAULT_SERVER_PORT,
                        Optional.empty(),
                        Optional.empty()));
        when(healthChecker.managedHealthPort(any(), anyInt(), nullable(Path.class)))
                .thenReturn(ConfigDefaults.DEFAULT_SERVER_PORT);
        when(healthChecker.workflowHealth(any(), nullable(String.class), nullable(String.class), anyInt()))
                .thenReturn(new BoardHealth(
                        BoardHealthKind.STOPPED,
                        ConfigDefaults.DEFAULT_SERVER_PORT,
                        Optional.empty(),
                        Optional.empty()));
        this.manager = new LocalWorkerManager(
                Map.of(), new WorkflowConfigEditor(), healthChecker, platform, new LocalLogTailer());
    }

    WorkerRunResult start(StartWorkerRequest request) throws Exception {
        var stdout = new ByteArrayOutputStream();
        int exitCode = manager.start(request, printStream(stdout));
        return new WorkerRunResult(exitCode, stdout.toString(StandardCharsets.UTF_8));
    }

    WorkerRunResult stop(StopWorkerRequest request) throws Exception {
        var stdout = new ByteArrayOutputStream();
        int exitCode = manager.stop(request, printStream(stdout));
        return new WorkerRunResult(exitCode, stdout.toString(StandardCharsets.UTF_8));
    }

    WorkerRunResult status(WorkerStatusRequest request) throws Exception {
        var stdout = new ByteArrayOutputStream();
        int exitCode = manager.status(request, printStream(stdout));
        return new WorkerRunResult(exitCode, stdout.toString(StandardCharsets.UTF_8));
    }

    ConnectedBoard connectedBoard(String boardId, String boardName) throws Exception {
        String slug = boardName.toLowerCase();
        Path workflow = paths.configDir().resolve("WORKFLOW." + slug + ".md");
        Files.createDirectories(paths.configDir());
        Files.writeString(
                workflow,
                """
                ---
                tracker:
                  kind: trello
                  board_id: %s
                server:
                  port: %d
                ---
                # %s
                """
                        .formatted(boardId, ConfigDefaults.DEFAULT_SERVER_PORT, boardName),
                StandardCharsets.UTF_8);
        writeEnv(paths.defaultEnvPath());
        return new ConnectedBoard(
                boardId,
                boardId,
                boardName,
                "https://trello.com/b/" + boardId,
                workflow.toAbsolutePath().normalize(),
                paths.defaultEnvPath(),
                paths.workspaceRoot(),
                ConfigDefaults.DEFAULT_SERVER_PORT,
                false,
                List.of(),
                false);
    }

    void writeEnv(Path envPath) throws Exception {
        Path parent = envPath.toAbsolutePath().normalize().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(envPath, "TRELLO_API_KEY=test-key\nTRELLO_API_TOKEN=test-token\n", StandardCharsets.UTF_8);
    }

    void save(ConnectedBoard... boards) throws Exception {
        ConnectedBoardManifest manifest = new ConnectedBoardManifest(List.of(boards));
        new ConnectedBoardRepository(paths.manifestPath()).save(manifest);
    }

    private static PrintStream printStream(ByteArrayOutputStream stdout) {
        return new PrintStream(stdout, true, StandardCharsets.UTF_8);
    }
}

record WorkerRunResult(int exitCode, String stdout) {
    WorkerRunResult assertSuccess() {
        assertThat(exitCode).as("stdout:%n%s", stdout).isZero();
        return this;
    }

    WorkerRunResult stdoutContains(String... expected) {
        assertThat(stdout).contains(expected);
        return this;
    }

    WorkerRunResult stdoutDoesNotContain(String... forbidden) {
        assertThat(stdout).doesNotContain(forbidden);
        return this;
    }
}
