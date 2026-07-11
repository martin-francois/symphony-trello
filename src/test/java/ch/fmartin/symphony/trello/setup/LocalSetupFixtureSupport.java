package ch.fmartin.symphony.trello.setup;

import static org.assertj.core.api.Assertions.assertThat;

import ch.fmartin.symphony.trello.testsupport.FakeTrelloServer;
import ch.fmartin.symphony.trello.testsupport.SetupRunResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.StringReader;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Map;
import java.util.function.IntPredicate;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

abstract class LocalSetupFixtureSupport {
    protected LocalSetupTestFixture fixture;
    protected FakeTrelloServer trello;
    protected LocalSetup setup;
    protected LocalSetupTestFixture.FakeCommands commands;
    protected LocalWorkerManager workerManager;

    @TempDir
    protected Path tempDir;

    @BeforeEach
    void startServer() throws Exception {
        fixture = new LocalSetupTestFixture(tempDir);
        trello = fixture.trello();
        setup = fixture.setup();
        commands = fixture.commands();
        workerManager = fixture.workerManager();
    }

    @AfterEach
    void stopServer() {
        if (fixture != null) {
            fixture.close();
        }
    }

    protected SetupRunResult runSetup(String... args) {
        return fixture.runSetup(args);
    }

    protected SetupRunResult runSetupWithInput(String input, String... args) {
        return fixture.runSetupWithInput(input, args);
    }

    protected SetupRunResult runSetup(LocalSetup localSetup, String... args) {
        return runSetupWithInput(localSetup, "", args);
    }

    protected SetupRunResult runSetupWithProductionDefaultPort(String... args) {
        return fixture.runSetupWithProductionDefaultPort(args);
    }

    protected SetupRunResult runSetupWithProductionDefaultPort(LocalSetup localSetup, String... args) {
        return fixture.runSetupWithProductionDefaultPort(localSetup, args);
    }

    protected SetupRunResult connectLocalBoardWithoutGithub(Path workflow, Path env, String boardName) {
        return runSetup(
                "--non-interactive",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--board-name",
                boardName,
                "--workflow",
                workflow.toString(),
                "--env",
                env.toString(),
                "--no-github");
    }

    protected void prepareNextSetupRunWithGithubAuth() {
        commands.githubAuthenticated = true;
        commands.startedWorkflows.clear();
        commands.startedEnvFiles.clear();
        trello.createdLists().clear();
    }

    protected SetupRunResult runSetupWithInput(LocalSetup localSetup, String input, String... args) {
        var stdout = new ByteArrayOutputStream();
        var stderr = new ByteArrayOutputStream();
        int exitCode = localSetup.run(
                fixture.argsWithFixtureServerPort(args),
                new BufferedReader(new StringReader(input)),
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8));
        return new SetupRunResult(
                exitCode, stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8));
    }

    protected LocalSetup setupWithEnvironment(Map<String, String> environment) {
        return new LocalSetup(
                new TrelloBoardSetup(new ObjectMapper()),
                commands,
                environment,
                new WorkflowConfigEditor(),
                workerManager);
    }

    protected LocalSetup setupWithCodexDefaults(TrelloBoardSetup.CodexModelDefaults codexDefaults) {
        return setupWithCodexSelectionDefaults(CodexModelSelectionDefaults.of(codexDefaults));
    }

    protected LocalSetup setupWithCodexSelectionDefaults(CodexModelSelectionDefaults codexDefaults) {
        return setupWithBoardSetup(new TrelloBoardSetup(new ObjectMapper(), codexDefaults));
    }

    protected LocalSetup setupWithCodexSelectionDefaults(Supplier<CodexModelSelectionDefaults> codexDefaults) {
        return setupWithBoardSetup(new TrelloBoardSetup(new ObjectMapper(), codexDefaults));
    }

    /**
     * Returns a setup whose port-availability checks use the given probe instead of real loopback
     * sockets, so port-selection assertions stay independent of live host port occupancy.
     */
    protected LocalSetup setupWithPortProbe(IntPredicate portInUse) {
        return setupWithBoardSetup(new TrelloBoardSetup(new ObjectMapper()).withPortProbe(portInUse));
    }

    private LocalSetup setupWithBoardSetup(TrelloBoardSetup boardSetup) {
        return new LocalSetup(
                boardSetup,
                commands,
                Map.of(
                        "SYMPHONY_TRELLO_CONFIG_DIR",
                        tempDir.resolve("config").toString(),
                        "SYMPHONY_TRELLO_COMMAND",
                        "symphony-trello"),
                new WorkflowConfigEditor(),
                workerManager);
    }

    protected LocalSetup setupWithOperatingSystem(String osName) {
        return new LocalSetup(
                new TrelloBoardSetup(new ObjectMapper()),
                commands,
                Map.of(
                        "SYMPHONY_TRELLO_CONFIG_DIR",
                        tempDir.resolve("config").toString(),
                        "SYMPHONY_TRELLO_COMMAND",
                        "symphony-trello"),
                new WorkflowConfigEditor(),
                workerManager,
                () -> osName);
    }

    protected void writeWorkflow(Path workflow, String boardId, int port) throws IOException {
        fixture.givenWorkflow(workflow, boardId, port);
    }

    protected void writeManifest(String content) throws IOException {
        fixture.givenManifest(content);
    }

    protected static String json(Path path) {
        return path.toString().replace("\\", "\\\\").replace("\"", "\\\"");
    }

    protected String endpoint() {
        return trello.endpoint();
    }

    protected static int availablePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new AssertionError("Could not allocate test port", e);
        }
    }

    protected static void assertOwnerOnlyWhenPosix(Path path) throws IOException {
        if (path.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            assertThat(Files.getPosixFilePermissions(path))
                    .containsExactlyInAnyOrder(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
        }
    }
}
