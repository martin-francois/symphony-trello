package ch.fmartin.symphony.trello.setup;

import static ch.fmartin.symphony.trello.setup.InstallerScriptFixture.commandExists;
import static ch.fmartin.symphony.trello.setup.InstallerScriptFixture.shellQuote;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/// Types real key sequences into setup prompts through a pseudo-terminal, because line editing,
/// hidden secrets, and Ctrl+C handling only exist when a terminal is attached.
final class StreamTerminalLineEditingTest {
    /// The line editor switches the terminal to application cursor-key mode, so terminals send
    /// `ESC O` sequences for the arrow keys.
    private static final String LEFT_ARROW = LineEditingPromptProbe.ESCAPE + "OD";

    private static final String RIGHT_ARROW = LineEditingPromptProbe.ESCAPE + "OC";
    private static final String UP_ARROW = LineEditingPromptProbe.ESCAPE + "OA";
    private static final String HOME = LineEditingPromptProbe.ESCAPE + "OH";
    private static final String END = LineEditingPromptProbe.ESCAPE + "OF";
    private static final String BACKSPACE = "\u007F";

    /// The left arrow key in normal cursor-key mode, as a terminal without line editing sends it.
    private static final String NORMAL_MODE_LEFT_ARROW = LineEditingPromptProbe.ESCAPE + "[D";

    private static final String CTRL_C = "\u0003";
    private static final String ENTER = "\r";
    private static final String SECRET = "sekrit-token";

    /// The line editor turns on bracketed paste for each prompt; plain stream prompts never write it.
    private static final String LINE_EDITOR_CONTROL_OUTPUT = LineEditingPromptProbe.ESCAPE + "[?2004h";

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final Duration PROMPT_POLL_INTERVAL = Duration.ofMillis(50);

    /// A pseudo-terminal started from a pipe has no size, and the line editor needs one to draw the
    /// prompt. These match a common default terminal window.
    private static final int TERMINAL_COLUMNS = 80;

    private static final int TERMINAL_ROWS = 24;

    @TempDir
    Path temporaryDirectory;

    @BeforeEach
    void requirePseudoTerminal() {
        assumeTrue(commandExists("script"), "util-linux script provides the pseudo-terminal");
    }

    @Test
    void arrowKeysEditInteractivePromptsWhileSecretsStayHiddenAndOutOfHistory() throws Exception {
        // given
        try (var session = PseudoTerminalSession.start(probeCommand())) {

            // when
            session.answer(
                    LineEditingPromptProbe.PATH_PROMPT,
                    "tmp/ac" + LEFT_ARROW + "b" + RIGHT_ARROW + "d" + HOME + "/" + END + BACKSPACE + ENTER);
            session.answer(LineEditingPromptProbe.SECRET_PROMPT, SECRET + ENTER);
            session.answer(LineEditingPromptProbe.RECALL_PROMPT, UP_ARROW + ENTER);
            int exitCode = session.awaitExit();

            // then
            assertThat(exitCode)
                    .as("probe exit code, transcript:%n%s", session.transcript())
                    .isZero();
            assertThat(session.transcript())
                    .contains(
                            LineEditingPromptProbe.pathResult("/tmp/abc"),
                            LineEditingPromptProbe.secretLengthResult(String.valueOf(SECRET.length())),
                            LineEditingPromptProbe.recalledResult("/tmp/abc"))
                    .doesNotContain(SECRET);
        }
    }

    @EnumSource
    @ParameterizedTest
    void ctrlCAtAnInteractivePromptEndsSetupLikeSigintWithoutAStackTrace(InterruptedPrompt interrupted)
            throws Exception {
        // given
        try (var session = PseudoTerminalSession.start(probeCommand())) {
            for (String earlierPrompt : interrupted.earlierPrompts) {
                session.answer(earlierPrompt, "answer" + ENTER);
            }

            // when
            session.answer(interrupted.prompt, "partial" + CTRL_C);
            int exitCode = session.awaitExit();

            // then
            assertThat(exitCode)
                    .as("exit code after Ctrl+C, transcript:%n%s", session.transcript())
                    .isEqualTo(CommandResult.INTERRUPTED_EXIT_CODE);
            assertThat(session.transcript()).doesNotContain("Exception", LineEditingPromptProbe.RECALL_PROMPT);
        }
    }

    @Test
    void redirectedInputInATerminalKeepsPlainLineReading() throws Exception {
        // given
        Path answers = temporaryDirectory.resolve("answers.txt");
        String rawPath = "/tmp/ac" + NORMAL_MODE_LEFT_ARROW + "b";
        Files.writeString(answers, rawPath + "\n" + SECRET + "\n\n");

        try (var session = PseudoTerminalSession.start(probeCommand() + " < " + shellQuote(answers.toString()))) {

            // when
            int exitCode = session.awaitExit();

            // then
            assertThat(exitCode)
                    .as("probe exit code, transcript:%n%s", session.transcript())
                    .isZero();
            assertThat(session.transcript())
                    .contains(
                            LineEditingPromptProbe.pathResult(LineEditingPromptProbe.visible(rawPath)),
                            LineEditingPromptProbe.secretLengthResult(String.valueOf(SECRET.length())),
                            LineEditingPromptProbe.recalledResult(""))
                    .doesNotContain(LINE_EDITOR_CONTROL_OUTPUT, SECRET);
        }
    }

    /// A prompt that receives Ctrl+C, with the prompts the probe asks before it.
    enum InterruptedPrompt {
        PLAIN_LINE(LineEditingPromptProbe.PATH_PROMPT, List.of()),
        HIDDEN_SECRET(LineEditingPromptProbe.SECRET_PROMPT, List.of(LineEditingPromptProbe.PATH_PROMPT));

        private final String prompt;
        private final List<String> earlierPrompts;

        InterruptedPrompt(String prompt, List<String> earlierPrompts) {
            this.prompt = prompt;
            this.earlierPrompts = earlierPrompts;
        }
    }

    private static String probeCommand() {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        return "stty cols " + TERMINAL_COLUMNS + " rows " + TERMINAL_ROWS + " && exec " + shellQuote(java) + " -cp "
                + shellQuote(System.getProperty("java.class.path")) + " " + LineEditingPromptProbe.class.getName();
    }

    /// A child process attached to a pseudo-terminal through util-linux `script`. The terminal merges
    /// output and the echo of typed keys into one transcript.
    private static final class PseudoTerminalSession implements AutoCloseable {
        private final Process process;
        private final ByteArrayOutputStream transcript = new ByteArrayOutputStream();
        private final Thread transcriptReader;
        private int answeredUpTo;

        private PseudoTerminalSession(Process process) {
            this.process = process;
            this.transcriptReader = Thread.ofVirtual().start(() -> copyTranscript(process.getInputStream()));
        }

        static PseudoTerminalSession start(String shellCommand) throws IOException {
            var processBuilder =
                    new ProcessBuilder("script", "-q", "-e", "-c", shellCommand, "/dev/null").redirectErrorStream(true);
            processBuilder.environment().put("TERM", "xterm-256color");
            return new PseudoTerminalSession(processBuilder.start());
        }

        /// Types `keys` only after `prompt` appeared, as a person would. Keys typed earlier would reach
        /// the terminal before the line editor switched it to raw mode.
        void answer(String prompt, String keys) throws IOException, InterruptedException {
            long deadlineNanos = System.nanoTime() + TIMEOUT.toNanos();
            int promptIndex = transcript().indexOf(prompt, answeredUpTo);
            while (promptIndex < 0) {
                if (System.nanoTime() - deadlineNanos > 0 || !process.isAlive()) {
                    fail("Prompt \"%s\" did not appear. Transcript:%n%s".formatted(prompt, transcript()));
                }
                Thread.sleep(PROMPT_POLL_INTERVAL);
                promptIndex = transcript().indexOf(prompt, answeredUpTo);
            }
            answeredUpTo = promptIndex + prompt.length();
            process.getOutputStream().write(keys.getBytes(StandardCharsets.UTF_8));
            process.getOutputStream().flush();
        }

        int awaitExit() throws InterruptedException {
            if (!process.waitFor(TIMEOUT)) {
                fail("Probe did not exit. Transcript:%n%s".formatted(transcript()));
            }
            transcriptReader.join(TIMEOUT);
            return process.exitValue();
        }

        String transcript() {
            return transcript.toString(StandardCharsets.UTF_8);
        }

        private void copyTranscript(InputStream output) {
            try (output) {
                output.transferTo(transcript);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public void close() throws IOException {
            process.getOutputStream().close();
            process.destroyForcibly();
        }
    }
}
