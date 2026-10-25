package ch.fmartin.symphony.trello.setup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import ch.fmartin.symphony.trello.testsupport.StatefulFakeTrello;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/// Runs `symphony-trello tutorial` as a real process, so standard input, process exit, and the
/// Ctrl+C shutdown hook behave like they do for a user.
final class TutorialCommandProcessTest {
    private static final Duration PROCESS_TIMEOUT = Duration.ofMinutes(1);
    private static final Duration CLEANUP_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration CONDITION_POLL = Duration.ofMillis(50);
    /// The JVM exits with 128 plus the signal number when SIGINT (2) ends it.
    private static final int EXIT_AFTER_SIGINT = 130;
    private static final List<String> INHERITED_SETUP_ENVIRONMENT = List.of(
            "TRELLO_API_KEY",
            "TRELLO_API_TOKEN",
            "SYMPHONY_TRELLO_DOTENV",
            "SYMPHONY_TRELLO_CONFIG_DIR",
            "SYMPHONY_TRELLO_COMMAND");

    @TempDir
    Path tempDir;

    private final List<Process> tutorialProcesses = new ArrayList<>();
    private final StatefulFakeTrello trello = new StatefulFakeTrello();

    @BeforeEach
    void startTrello() throws IOException {
        trello.start();
    }

    @AfterEach
    void stopTutorialsAndTrello() throws InterruptedException {
        try (trello) {
            for (Process process : tutorialProcesses) {
                process.destroyForcibly();
            }
            boolean allExited = true;
            for (Process process : tutorialProcesses) {
                allExited &= process.waitFor(CLEANUP_TIMEOUT);
            }
            assertThat(allExited)
                    .as("every owned tutorial process exits during cleanup")
                    .isTrue();
        }
    }

    @Test
    void redirectedOfferSkipsInputAndTrelloAccess() throws Exception {
        // given
        Process process = startTutorial(
                "--offer", "--config-dir", tempDir.resolve("missing-config").toString());
        CompletableFuture<String> stdout = readAll(process.getInputStream());
        CompletableFuture<String> stderr = readAll(process.getErrorStream());

        // when
        try (OutputStream stdin = process.getOutputStream()) {
            stdin.write("y\n".getBytes(StandardCharsets.UTF_8));
        }
        boolean exited = process.waitFor(PROCESS_TIMEOUT);

        // then
        assertThat(exited)
                .as("a redirected optional tutorial returns without asking")
                .isTrue();
        assertThat(process.exitValue()).as("stderr:%n%s", stderr.get()).isZero();
        assertThat(stdout.get()).doesNotContain(GuidedTutorial.OFFER_PROMPT, "Guided tutorial");
        assertThat(trello.boardNames()).isEmpty();
        assertThat(tempDir.resolve("missing-config")).doesNotExist();
    }

    @Test
    void ctrlCArchivesTheTutorialBoardBeforeTheProcessExits() throws Exception {
        // given
        assumeFalse(isWindows(), "Windows has no SIGINT for a child process; the hook path is the same");
        String userBoardId = trello.givenBoard("Existing Queue");
        Process process = startTutorial("--no-github");
        CompletableFuture<String> stdout = readAll(process.getInputStream());
        CompletableFuture<String> stderr = readAll(process.getErrorStream());
        waitUntil(() -> trello.receivedRequestStartingWith("GET /cards/"));

        // when
        int killExit = new ProcessBuilder("kill", "-INT", Long.toString(process.pid()))
                .start()
                .waitFor();
        boolean exited = process.waitFor(PROCESS_TIMEOUT);
        process.getOutputStream().close();

        // then
        assertThat(killExit).isZero();
        assertThat(exited).as("the tutorial exits after Ctrl+C").isTrue();
        assertThat(process.exitValue()).isEqualTo(EXIT_AFTER_SIGINT);
        assertThat(stdout.get())
                .as("stderr:%n%s", stderr.get())
                .contains("Step 1 of 3: queue the card", "OK  Archived the temporary tutorial board");
        assertThat(trello.archived(trello.boardIdNamed(GuidedTutorial.BOARD_NAME)))
                .as("Ctrl+C archives the tutorial board")
                .isTrue();
        assertThat(trello.archived(userBoardId))
                .as("Ctrl+C never archives another board")
                .isFalse();
    }

    @Test
    void quitFromStandardInputArchivesTheBoardAndExitsSuccessfully() throws Exception {
        // given
        Process process = startTutorial("--no-github");
        CompletableFuture<String> stdout = readAll(process.getInputStream());
        CompletableFuture<String> stderr = readAll(process.getErrorStream());

        // when
        try (OutputStream stdin = process.getOutputStream()) {
            stdin.write("q\n".getBytes(StandardCharsets.UTF_8));
        }
        boolean exited = process.waitFor(PROCESS_TIMEOUT);

        // then
        assertThat(exited).as("the tutorial exits after q and end of input").isTrue();
        assertThat(process.exitValue()).as("stderr:%n%s", stderr.get()).isZero();
        assertThat(stdout.get())
                .contains(
                        "Stopped the tutorial.",
                        GuidedTutorial.CLEANUP_PROMPT,
                        "OK  Archived the temporary tutorial board");
        assertThat(trello.archived(trello.boardIdNamed(GuidedTutorial.BOARD_NAME)))
                .as("the default cleanup answer archives the tutorial board")
                .isTrue();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void connectedGithubBoardSelectsTheGithubWalkthrough(boolean configuredProperty) throws Exception {
        // given
        Path configDir = Files.createDirectories(tempDir.resolve("config"));
        Files.writeString(
                configDir.resolve(ConnectedBoardManifest.FILE_NAME),
                """
                {"boards":[{"boardId":"000000000000000000000001","boardKey":"SYNTH001","boardName":"GitHub Queue",\
                "boardUrl":"https://trello.com/b/SYNTH001/synthetic-board","workflowPath":"%s","envPath":"%s",\
                "workspaceRoot":"%s","serverPort":18080,"githubEnabled":true,"additionalWritableRoots":[],\
                "dangerFullAccess":false}]}
                """
                        .formatted(
                                json(configDir.resolve("WORKFLOW.md")),
                                json(configDir.resolve(".env")),
                                json(tempDir.resolve("workspaces"))));
        Process process = configuredProperty
                ? startTutorialWithProperty(configDir.toString())
                : startTutorial("--config-dir", configDir.toString());
        CompletableFuture<String> stderr = readAll(process.getErrorStream());
        readAll(process.getInputStream());

        // when
        try (OutputStream stdin = process.getOutputStream()) {
            stdin.write("q\n".getBytes(StandardCharsets.UTF_8));
        }
        boolean exited = process.waitFor(PROCESS_TIMEOUT);

        // then
        assertThat(exited).as("the tutorial exits after q and end of input").isTrue();
        assertThat(process.exitValue()).as("stderr:%n%s", stderr.get()).isZero();
        assertThat(trello.listNames(trello.boardIdNamed(GuidedTutorial.BOARD_NAME)))
                .containsExactlyElementsOf(TrelloBoardSetup.RECOMMENDED_LISTS);
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://trello.com/w/synthetic", "/tmp/workspace", "./workspace"})
    void rejectsWorkspaceReferencesBeforeCreatingTheBoard(String workspace) throws Exception {
        // given
        Process process = startTutorial("--workspace-id", workspace);
        CompletableFuture<String> stderr = readAll(process.getErrorStream());
        readAll(process.getInputStream());

        // when
        process.getOutputStream().close();
        boolean exited = process.waitFor(PROCESS_TIMEOUT);

        // then
        assertThat(exited)
                .as("invalid workspace references end the tutorial process")
                .isTrue();
        assertThat(process.exitValue()).isNotZero();
        assertThat(stderr.get()).contains("--workspace-id");
        assertThat(trello.boardNames()).isEmpty();
    }

    private Process startTutorial(String... options) throws IOException {
        return startTutorialWithProperty("", options);
    }

    private Process startTutorialWithProperty(String configDirectory, String... options) throws IOException {
        List<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java")
                        .toString(),
                "-cp",
                System.getProperty("java.class.path"),
                TrelloBoardSetupMain.class.getName(),
                GuidedTutorial.COMMAND,
                "--endpoint",
                trello.endpoint().toString(),
                "--key",
                "key",
                "--token",
                "token"));
        if (!configDirectory.isEmpty()) {
            command.add(1, "-Dsymphony.trello.config.dir=" + configDirectory);
        }
        command.addAll(List.of(options));
        ProcessBuilder builder = new ProcessBuilder(command).directory(tempDir.toFile());
        INHERITED_SETUP_ENVIRONMENT.forEach(builder.environment()::remove);
        Process process = builder.start();
        tutorialProcesses.add(process);
        return process;
    }

    private static CompletableFuture<String> readAll(InputStream stream) {
        return CompletableFuture.supplyAsync(() -> {
            try (stream) {
                var buffer = new ByteArrayOutputStream();
                stream.transferTo(buffer);
                return buffer.toString(StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    private static void waitUntil(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + PROCESS_TIMEOUT.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("The tutorial never started checking the card");
            }
            Thread.sleep(CONDITION_POLL);
        }
    }

    private static String json(Path path) {
        return path.toAbsolutePath().normalize().toString().replace("\\", "\\\\");
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
