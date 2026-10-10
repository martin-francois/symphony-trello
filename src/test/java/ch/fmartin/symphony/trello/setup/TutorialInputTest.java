package ch.fmartin.symphony.trello.setup;

import static org.assertj.core.api.Assertions.assertThat;

import ch.fmartin.symphony.trello.setup.TutorialInput.Answer;
import java.io.ByteArrayOutputStream;
import java.io.InterruptedIOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

final class TutorialInputTest {
    private static final Duration SHORT_WAIT = Duration.ofMillis(20);
    private static final Duration LATCH_WAIT = Duration.ofSeconds(10);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(5);

    @Test
    void timedOutWaitsShareOneTerminalRead() throws Exception {
        // given
        var terminal = new BlockingTerminal("typed later");

        // when
        Optional<Answer> first;
        Optional<Answer> second;
        Optional<Answer> delivered;
        try (var input = new TutorialInput(terminal)) {
            first = input.poll("Check: ", SHORT_WAIT);
            second = input.poll("Check: ", SHORT_WAIT);
            terminal.release();
            delivered = input.poll("Check: ", LATCH_WAIT);
        }

        // then
        assertThat(first).isEmpty();
        assertThat(second).isEmpty();
        assertThat(delivered)
                .hasValueSatisfying(answer -> assertThat(answer.line()).isEqualTo("typed later"));
        assertThat(terminal.prompts())
                .as("a second read while the first one waits would take a line meant for a later prompt")
                .containsExactly("Check: ");
    }

    @Test
    void lineTypedBeforeTheNextPromptAppearedDoesNotAnswerIt() throws Exception {
        // given
        var terminal = new BlockingTerminal("typed for the old prompt");

        // when
        Answer answer;
        try (var input = new TutorialInput(terminal)) {
            input.poll("Check: ", SHORT_WAIT);
            terminal.release();
            waitUntilLineWaiting(input);
            terminal.answerNextWith("typed for the cleanup prompt");
            answer = input.read("Archive? ");
        }

        // then
        assertThat(answer.line()).isEqualTo("typed for the cleanup prompt");
        assertThat(terminal.prompts()).containsExactly("Check: ", "Archive? ");
    }

    @Test
    void waitingReadAnswersTheNextPromptWhenTheLineArrivesAfterItAppeared() throws Exception {
        // given
        var terminal = new BlockingTerminal("typed after the new prompt");
        String nextPrompt = "Next question: ";

        // when
        Answer answer;
        try (var input = new TutorialInput(terminal)) {
            input.poll("First question: ", SHORT_WAIT);
            Optional<Answer> beforeRelease = input.poll(nextPrompt, SHORT_WAIT);
            assertThat(beforeRelease).isEmpty();
            terminal.release();
            answer = input.read(nextPrompt);
        }

        // then
        assertThat(answer.line()).isEqualTo("typed after the new prompt");
        assertThat(terminal.prompts()).containsExactly("First question: ");
        assertThat(terminal.stdout())
                .as("the waiting read shows the new prompt once")
                .isEqualTo("First question: Next question: ");
    }

    @Test
    void showsThePromptAgainOnlyWhenOtherOutputHidItAndStartsANewReadAfterAnAnswer() throws Exception {
        // given
        var terminal = new BlockingTerminal("first answer");

        // when
        Answer secondAnswer;
        try (var input = new TutorialInput(terminal)) {
            input.poll("Check: ", SHORT_WAIT);
            input.showPromptAgain();
            input.poll("Check: ", SHORT_WAIT);
            terminal.release();
            input.read("Check: ");
            terminal.answerNextWith("second answer");
            secondAnswer = input.read("Continue: ");
        }

        // then
        assertThat(terminal.stdout()).isEqualTo("Check: Check: Continue: ");
        assertThat(terminal.prompts()).containsExactly("Check: ", "Continue: ");
        assertThat(secondAnswer.line()).isEqualTo("second answer");
    }

    @Test
    void endOfInputIsAnAnswer() throws Exception {
        // given
        var terminal = new BlockingTerminal(null);
        terminal.release();

        // when
        Answer answer;
        try (var input = new TutorialInput(terminal)) {
            answer = input.read("Question: ");
        }

        // then
        assertThat(answer.line()).as("a null line means standard input ended").isNull();
    }

    private static void waitUntilLineWaiting(TutorialInput input) throws InterruptedException {
        long deadline = System.nanoTime() + LATCH_WAIT.toNanos();
        while (!input.lineWaiting()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("The released read never delivered its line");
            }
            Thread.sleep(POLL_INTERVAL);
        }
    }

    /// A terminal whose first read blocks until the test releases it, like a person who has not
    /// typed anything yet.
    private static final class BlockingTerminal implements Terminal {
        private final CountDownLatch released = new CountDownLatch(1);
        private final List<String> prompts = new CopyOnWriteArrayList<>();
        private final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        private final PrintStream out = new PrintStream(stdout, true, StandardCharsets.UTF_8);
        private volatile String nextLine;

        private BlockingTerminal(String firstLine) {
            this.nextLine = firstLine;
        }

        @Override
        public String readLine(String prompt) throws InterruptedIOException {
            prompts.add(prompt);
            out.print(prompt);
            try {
                if (!released.await(LATCH_WAIT.toMillis(), TimeUnit.MILLISECONDS)) {
                    throw new AssertionError("The test never released the blocked read");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                var failure = new InterruptedIOException("interrupted");
                failure.initCause(e);
                throw failure;
            }
            return nextLine;
        }

        private void release() {
            released.countDown();
        }

        private void answerNextWith(String line) {
            nextLine = line;
        }

        private List<String> prompts() {
            return List.copyOf(prompts);
        }

        private String stdout() {
            return stdout.toString(StandardCharsets.UTF_8);
        }

        @Override
        public char[] readSecret(String prompt) {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public void info(String line) {
            out.println(line);
        }

        @Override
        public void warn(String line) {
            out.println(line);
        }

        @Override
        public void error(String line) {
            out.println(line);
        }

        @Override
        public PrintStream out() {
            return out;
        }

        @Override
        public PrintStream err() {
            return out;
        }
    }
}
