package ch.fmartin.symphony.trello.setup;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.jspecify.annotations.Nullable;

/// Reads tutorial answers through [Terminal#readLine] while the tutorial keeps checking Trello.
///
/// A terminal read cannot time out, so a waiting step hands the read to one background thread and
/// polls Trello until the line arrives. When Trello shows the expected state first, the read stays
/// pending and answers the next prompt. There is never more than one read in flight. A line that
/// arrived before a new prompt appeared was typed for the old prompt, so it is dropped instead of
/// answering a question the user has not seen yet.
final class TutorialInput implements AutoCloseable {
    private final Terminal terminal;
    private final ExecutorService reader;
    private @Nullable Future<String> pendingLine;
    /// The prompt the user currently sees for the pending read.
    private @Nullable String shownPrompt;
    /// The prompt that was showing when the pending read could last receive a line.
    private @Nullable String answeredPrompt;

    TutorialInput(Terminal terminal) {
        this.terminal = terminal;
        // A daemon thread, because a read blocked on standard input must not keep the CLI alive.
        this.reader = Executors.newSingleThreadExecutor(
                Thread.ofPlatform().daemon().name("tutorial-input").factory());
    }

    /// Waits for the next line. Returns empty when no line arrived within the timeout.
    Optional<Answer> poll(String prompt, Duration timeout) throws IOException {
        Future<String> line = startOrReuseRead(prompt);
        try {
            return Optional.of(take(line.get(timeout.toMillis(), TimeUnit.MILLISECONDS)));
        } catch (TimeoutException e) {
            return Optional.empty();
        } catch (InterruptedException e) {
            throw interrupted(e);
        } catch (ExecutionException e) {
            pendingLine = null;
            throw readFailure(e);
        }
    }

    /// Waits until the next line arrives.
    Answer read(String prompt) throws IOException {
        Future<String> line = startOrReuseRead(prompt);
        try {
            return take(line.get());
        } catch (InterruptedException e) {
            throw interrupted(e);
        } catch (ExecutionException e) {
            pendingLine = null;
            throw readFailure(e);
        }
    }

    /// Prints the prompt again on the next wait, because other output pushed it out of view.
    void showPromptAgain() {
        shownPrompt = null;
    }

    /// Whether a typed line is waiting that no prompt has taken yet.
    boolean lineWaiting() {
        Future<String> line = pendingLine;
        return line != null && line.isDone();
    }

    @Override
    public void close() {
        reader.shutdownNow();
    }

    private Future<String> startOrReuseRead(String prompt) {
        Future<String> line = pendingLine;
        if (line != null && line.isDone() && !prompt.equals(answeredPrompt)) {
            line = null;
        }
        if (line == null) {
            line = reader.submit(() -> terminal.readLine(prompt));
            pendingLine = line;
        } else if (!prompt.equals(shownPrompt)) {
            // The earlier read is still waiting behind an older or scrolled-away prompt.
            terminal.out().print(prompt);
        }
        shownPrompt = prompt;
        answeredPrompt = prompt;
        return line;
    }

    private Answer take(@Nullable String line) {
        pendingLine = null;
        return new Answer(line);
    }

    private static InterruptedIOException interrupted(InterruptedException cause) {
        Thread.currentThread().interrupt();
        var failure = new InterruptedIOException("Interrupted while waiting for tutorial input");
        failure.initCause(cause);
        return failure;
    }

    private static IOException readFailure(ExecutionException e) {
        return e.getCause() instanceof IOException io ? io : new IOException("Could not read tutorial input", e);
    }

    /// One answer. A null line means standard input ended.
    record Answer(@Nullable String line) {}
}
