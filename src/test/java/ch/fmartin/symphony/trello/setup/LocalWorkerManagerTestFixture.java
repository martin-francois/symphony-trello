package ch.fmartin.symphony.trello.setup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
        this.healthChecker = mock(LocalHealthChecker.class);
        when(healthChecker.boardHealth(any()))
                .thenReturn(new BoardHealth(BoardHealthKind.STOPPED, 18080, Optional.empty(), Optional.empty()));
        when(healthChecker.managedHealthPort(any(), anyInt(), nullable(Path.class)))
                .thenReturn(18080);
        when(healthChecker.workflowHealth(any(), nullable(String.class), nullable(String.class), anyInt()))
                .thenReturn(new BoardHealth(BoardHealthKind.STOPPED, 18080, Optional.empty(), Optional.empty()));
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
                  board_id: %s
                server:
                  port: 18080
                ---
                # %s
                """
                        .formatted(boardId, boardName),
                StandardCharsets.UTF_8);
        return new ConnectedBoard(
                boardId,
                boardId,
                boardName,
                "https://trello.com/b/" + boardId,
                workflow.toAbsolutePath().normalize(),
                paths.defaultEnvPath(),
                paths.workspaceRoot(),
                18080,
                false,
                List.of(),
                false);
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
