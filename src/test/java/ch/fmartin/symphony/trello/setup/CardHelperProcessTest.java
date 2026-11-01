package ch.fmartin.symphony.trello.setup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import ch.fmartin.symphony.trello.CliExitCodes;
import ch.fmartin.symphony.trello.process.PlatformEnvironment;
import ch.fmartin.symphony.trello.testsupport.CliRunResult;
import ch.fmartin.symphony.trello.testsupport.CredentialSentinels;
import ch.fmartin.symphony.trello.testsupport.FakeTrelloBoard;
import ch.fmartin.symphony.trello.testsupport.FakeTrelloServer;
import ch.fmartin.symphony.trello.testsupport.IsolatedCliLauncher;
import ch.fmartin.symphony.trello.testsupport.TestEnv;
import com.google.common.base.Splitter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import picocli.CommandLine;

final class CardHelperProcessTest {
    @TempDir
    Path temporary;

    @AfterEach
    void stoppedHelpersReleaseTheirLogFiles() throws IOException {
        if (!PlatformEnvironment.current().windows()) {
            return;
        }
        Files.walkFileTree(temporary, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                if (List.of("stdout.log", "stderr.log")
                        .contains(file.getFileName().toString())) {
                    try {
                        Files.delete(file);
                    } catch (IOException failure) {
                        CommandResult owners = new ProcessCommandRunner()
                                .run(
                                        "powershell.exe",
                                        "-NoProfile",
                                        "-NonInteractive",
                                        "-ExecutionPolicy",
                                        "Bypass",
                                        "-File",
                                        Path.of("src/test/resources/windows-file-lock-owners.ps1")
                                                .toAbsolutePath()
                                                .toString(),
                                        "-Path",
                                        file.toString());
                        failure.addSuppressed(new IOException("cannot delete stopped helper log: " + owners.output()));
                        throw failure;
                    }
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void helperStartsWithAnIsolatedOrRelativeApplicationClasspath(boolean isolatedLoader) throws Exception {
        // given
        Path config = Files.createDirectory(temporary.resolve("config"));
        var paths =
                new LocalWorkerPaths(temporary, config, temporary.resolve("workspaces"), temporary.resolve("state"));
        Path transcript = temporary.resolve("source-launcher.log");
        String launcherClasspath = Path.of(IsolatedCliLauncher.class
                        .getProtectionDomain()
                        .getCodeSource()
                        .getLocation()
                        .toURI())
                .toString();
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-cp");
        command.add(isolatedLoader ? launcherClasspath : relativeApplicationClasspath());
        if (isolatedLoader) {
            command.add(IsolatedCliLauncher.class.getName());
        }
        command.addAll(List.of(
                TrelloBoardSetupMain.class.getName(),
                "card-helper",
                "start",
                "--config-dir",
                config.toString(),
                "--state-home",
                paths.stateHome().toString()));
        var builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(transcript.toFile());
        builder.environment().put("SYMPHONY_TEST_APPLICATION_CLASSPATH", System.getProperty("java.class.path"));
        builder.environment().put("SYMPHONY_TRELLO_APP_HOME", paths.appHome().toString());
        var manager = new CardHelperManager();
        try (var out = new PrintStream(OutputStream.nullOutputStream())) {
            Process launcher = builder.start();
            try {

                // when
                boolean exited = launcher.waitFor(Duration.ofSeconds(30));

                // then
                assertThat(exited)
                        .as("source launcher exits after helper readiness")
                        .isTrue();
                assertThat(launcher.exitValue())
                        .withFailMessage(Files.readString(transcript))
                        .isZero();
                assertThat(transcript).content().contains("Started card helper.");
                assertThat(CardHelperFiles.from(paths).ready()).isRegularFile();
            } finally {
                launcher.destroyForcibly();
                manager.stop(paths, out);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void installedWrapperMarkersKeepExplicitConfigHelperCommandsTogether(boolean userStateOverride) throws Exception {
        // given
        Path installedConfig = Files.createDirectories(temporary.resolve("installed/config"));
        Path installedState = temporary.resolve("installed/state");
        Path config = Files.createDirectories(temporary.resolve("isolated/config"));
        Path selectedState = userStateOverride ? temporary.resolve("user-state") : config.resolveSibling("state");
        var environment = Map.of(
                "SYMPHONY_TRELLO_CONFIG_DIR", installedConfig.toString(),
                "SYMPHONY_TRELLO_STATE_HOME", userStateOverride ? selectedState.toString() : installedState.toString(),
                "SYMPHONY_TRELLO_APP_HOME", temporary.toString());
        var paths = new LocalWorkerPaths(temporary, config, temporary.resolve("workspaces"), selectedState);
        var manager = new CardHelperManager();
        try (var out = new PrintStream(OutputStream.nullOutputStream())) {
            manager.start(paths, out);
            try {

                // when
                CliRunResult status = installedCli(
                        environment,
                        installedState,
                        List.of("card-helper", "status", "--config-dir", config.toString()));
                CliRunResult card = installedCli(
                        environment, installedState, List.of("card", "list", "--config-dir", config.toString()));

                // then
                status.assertSuccess().stdoutContains("Card helper is running.");
                card.assertFailure(CliExitCodes.SETUP_FAILURE)
                        .stderrContains("cannot resolve connected board")
                        .stderrDoesNotContain("card_helper_unavailable");
                assertThat(CardHelperFiles.from(paths).ready()).isRegularFile();
            } finally {
                manager.stop(paths, out);
            }
        }
    }

    private CliRunResult installedCli(Map<String, String> environment, Path installedState, List<String> args)
            throws Exception {
        Path stdout = Files.createTempFile(temporary, "installed-cli-", ".stdout");
        Path stderr = Files.createTempFile(temporary, "installed-cli-", ".stderr");
        List<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-D" + InstalledCliDefaults.INSTALLED_STATE_HOME_PROPERTY + "=" + installedState,
                "-cp",
                System.getProperty("java.class.path"),
                TrelloBoardSetupMain.class.getName()));
        command.addAll(args);
        var builder =
                new ProcessBuilder(command).redirectOutput(stdout.toFile()).redirectError(stderr.toFile());
        builder.environment().putAll(environment);
        builder.environment().remove("SYMPHONY_TRELLO_MANIFEST");
        Process process = builder.start();
        try {
            assertThat(process.waitFor(Duration.ofSeconds(30)))
                    .as("installed CLI command completes within thirty seconds")
                    .isTrue();
            return new CliRunResult(process.exitValue(), Files.readString(stdout), Files.readString(stderr));
        } finally {
            process.destroyForcibly();
        }
    }

    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    @ParameterizedTest
    void shutdownUsesRetainedIdentityAfterConfigurationIsMoved(boolean all, boolean customManifest) throws Exception {
        // given
        Path config = Files.createDirectory(temporary.resolve("config"));
        Path movedConfig = temporary.resolve("moved-config");
        var paths = new LocalWorkerPaths(temporary, config, temporary.resolve("workspaces"), temporary.resolve("state"))
                .withManifestPath(config.resolve(customManifest ? "custom.json" : ConnectedBoardManifest.FILE_NAME));
        var manager = new CardHelperManager();
        try (var bytes = new ByteArrayOutputStream();
                var out = new PrintStream(bytes, false, StandardCharsets.UTF_8)) {
            manager.start(paths, out);
            long pid = CardHelperProtocol.json()
                    .readValue(Files.readAllBytes(CardHelperFiles.from(paths).ready()), CardHelperProtocol.Ready.class)
                    .pid();
            Files.move(config, movedConfig);
            try {

                // when
                if (all) {
                    manager.stopAll(paths, out);
                } else {
                    manager.stop(paths, out);
                }

                // then
                assertThat(ProcessHandle.of(pid).filter(ProcessHandle::isAlive)).isEmpty();
                assertThat(bytes.toString(StandardCharsets.UTF_8)).contains("Stopped card helper.");
                assertThat(config).doesNotExist();
            } finally {
                Files.move(movedConfig, config);
                manager.stop(paths, out);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void unavailableHelperIsAnExpectedCliFailure(boolean previouslyStarted) throws Exception {
        // given
        Path config = Files.createDirectory(temporary.resolve("config"));
        Path state = temporary.resolve("state");
        var paths = new LocalWorkerPaths(temporary, config, temporary.resolve("workspaces"), state);
        if (previouslyStarted) {
            var manager = new CardHelperManager();
            try (var out = new PrintStream(OutputStream.nullOutputStream())) {
                manager.start(paths, out);
                manager.stop(paths, out);
            }
        }

        // when
        CliRunResult result = installedCli(
                Map.of("SYMPHONY_TRELLO_CONFIG_DIR", config.toString()),
                state,
                List.of("card", "list", "--json", "--config-dir", config.toString(), "--state-home", state.toString()));

        // then
        result.assertFailure(CliExitCodes.SETUP_FAILURE)
                .stderrContains("code=setup_card_helper_unavailable", "trusted terminal")
                .stderrDoesNotContain("Troubleshooting report", "GitHub issue");
        assertThat(result.stdout()).isEmpty();
        assertThat(state.resolve("troubleshooting")).doesNotExist();
        assertThat(config.resolve(DiagnosticsTokenHasher.KEY_FILE_NAME)).doesNotExist();
    }

    private static String relativeApplicationClasspath() {
        Path caller = Path.of("").toAbsolutePath();
        List<String> entries = new ArrayList<>();
        for (String entry : Splitter.on(File.pathSeparatorChar).split(System.getProperty("java.class.path"))) {
            Path absolute = Path.of(entry).toAbsolutePath();
            entries.add(
                    absolute.getRoot().equals(caller.getRoot())
                            ? caller.relativize(absolute).toString()
                            : absolute.toString());
        }
        return String.join(File.pathSeparator, entries);
    }

    @Test
    void updateCycleRestartsAllManifestsAndKeepsOtherConfigurationsRunning() throws Exception {
        // given
        Path config = Files.createDirectory(temporary.resolve("config"));
        Path otherConfig = Files.createDirectory(temporary.resolve("other-config"));
        var paths =
                new LocalWorkerPaths(temporary, config, temporary.resolve("workspaces"), temporary.resolve("state"));
        var custom = paths.withManifestPath(config.resolve("custom.json"));
        var other = new LocalWorkerPaths(temporary, otherConfig, paths.workspaceRoot(), paths.stateHome());
        var manager = new CardHelperManager();
        try (var out = new PrintStream(OutputStream.nullOutputStream())) {
            var commands = new CommandLine(new CardHelperCommands(manager, Map.of(), out));
            try {
                manager.start(paths, out);
                manager.start(custom, out);
                manager.start(other, out);
                byte[] oldReady =
                        Files.readAllBytes(CardHelperFiles.from(custom).ready());
                long defaultPid = CardHelperProtocol.json()
                        .readValue(
                                Files.readAllBytes(CardHelperFiles.from(paths).ready()), CardHelperProtocol.Ready.class)
                        .pid();
                long customPid = CardHelperProtocol.json()
                        .readValue(oldReady, CardHelperProtocol.Ready.class)
                        .pid();
                byte[] otherReady =
                        Files.readAllBytes(CardHelperFiles.from(other).ready());

                // when
                int stop = commands.execute(
                        "stop",
                        "--all",
                        "--config-dir",
                        config.toString(),
                        "--state-home",
                        paths.stateHome().toString());

                // then
                assertThat(stop).isZero();
                assertThat(ProcessHandle.of(defaultPid).filter(ProcessHandle::isAlive))
                        .isEmpty();
                assertThat(ProcessHandle.of(customPid).filter(ProcessHandle::isAlive))
                        .isEmpty();

                // when
                int start = commands.execute(
                        "start",
                        "--all",
                        "--config-dir",
                        config.toString(),
                        "--state-home",
                        paths.stateHome().toString());

                // then
                assertThat(start).isZero();
                assertThat(CardHelperFiles.from(paths).ready()).exists();
                assertThat(Files.readAllBytes(CardHelperFiles.from(custom).ready()))
                        .isNotEqualTo(oldReady);
                assertThat(Files.readAllBytes(CardHelperFiles.from(other).ready()))
                        .isEqualTo(otherReady);
            } finally {
                manager.stop(paths, out);
                manager.stop(custom, out);
                manager.stop(other, out);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {ConnectedBoardManifest.FILE_NAME, "custom-boards.json"})
    void detachedHelperServesCardsWithoutAnyWorkerAndStopsIndependently(String manifestName) throws Exception {
        // given
        var board = new FakeTrelloBoard("board-1").list("inbox", "Inbox");
        try (var trello = new FakeTrelloServer().on("/1/", board).startEmpty();
                var output = new ByteArrayOutputStream();
                var out = new PrintStream(output, false, StandardCharsets.UTF_8)) {
            Path config = Files.createDirectory(temporary.resolve("config with spaces"));
            Path workflow = config.resolve("WORKFLOW.queue.md");
            Path credentials = config.resolve(".env");
            Files.writeString(
                    credentials,
                    TestEnv.trelloCredentials(
                            CredentialSentinels.TRELLO_API_KEY,
                            CredentialSentinels.TRELLO_API_TOKEN,
                            trello.endpoint()));
            Files.writeString(
                    workflow,
                    """
                    ---
                    tracker:
                      kind: trello
                      endpoint: %s
                      board_id: board-1
                    ---
                    Queue
                    """
                            .formatted(trello.endpoint()));
            new ConnectedBoardRepository(config.resolve(manifestName))
                    .save(new ConnectedBoardManifest(List.of(ConnectedBoardBuilder.connectedBoard(workflow)
                            .withBoardId("board-1")
                            .withEnvPath(credentials)
                            .build())));
            var paths = new LocalWorkerPaths(
                            temporary, config, temporary.resolve("workspaces"), temporary.resolve("state"))
                    .withManifestPath(config.resolve(manifestName));
            var files = CardHelperFiles.from(paths);
            var manager = new CardHelperManager();
            var client = new CardHelperClient(Map.of(), Duration.ofSeconds(15));
            try {
                manager.start(paths, out);
                var ready = CardHelperProtocol.json()
                        .readValue(Files.readAllBytes(files.ready()), CardHelperProtocol.Ready.class);
                assertThat(ready.pid()).isNotEqualTo(ProcessHandle.current().pid());

                // when
                int exit = client.run(request(config, "board-1"), files, out);
                Throwable missingBoard = catchThrowable(() -> client.run(request(config, "unknown"), files, out));

                // then
                assertThat(exit).isZero();
                assertThat(missingBoard)
                        .isInstanceOf(TrelloBoardSetupException.class)
                        .hasMessage(ConnectedWorkflowResolver.notConnected().getMessage());
                assertThat(output.toString(StandardCharsets.UTF_8)).contains("Started card helper.", "board-1");
                assertThat(paths.stateHome().resolve("workers")).doesNotExist();
            } finally {
                manager.stop(paths, out);
            }
            manager.status(paths, out);
            assertThat(output.toString(StandardCharsets.UTF_8))
                    .contains("Stopped card helper.", "Card helper is stopped.");
        }
    }

    private static CardCommandRequest request(Path config, String board) {
        return new CardCommandRequest(
                Optional.of(board),
                Optional.empty(),
                Optional.of(config),
                Optional.empty(),
                true,
                new CardCommandRequest.ListCards(Optional.empty()));
    }
}
