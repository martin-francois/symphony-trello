package ch.fmartin.symphony.trello.process;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

final class RuntimeProcessShutdownTest {
    @Test
    void unconfirmedExitRetainsWorkspaceAfterBothBoundedPhases() {
        // given
        ProcessHandle process = mock();
        when(process.isAlive()).thenReturn(true);
        when(process.onExit()).thenReturn(new CompletableFuture<>());

        // when
        assertThatThrownBy(() -> RuntimeProcessShutdown.stop(List.of(process), Duration.ofMillis(1)))
                .isInstanceOf(RuntimeProcessShutdown.UnconfirmedExitException.class)
                .hasMessageContaining("retain the workspace");

        // then
        verify(process).destroy();
        verify(process).destroyForcibly();
    }

    @Test
    void interruptedCallerStillChecksExitAndRestoresInterruption() {
        // given
        ProcessHandle process = mock();
        when(process.isAlive()).thenReturn(true);
        when(process.onExit()).thenReturn(new CompletableFuture<>());
        Thread.currentThread().interrupt();
        try {

            // when
            assertThatThrownBy(() -> RuntimeProcessShutdown.stop(List.of(process), Duration.ofMillis(1)))
                    .isInstanceOf(RuntimeProcessShutdown.UnconfirmedExitException.class);

            // then
            assertThat(Thread.currentThread().isInterrupted())
                    .as("bounded process confirmation must restore the caller's interruption")
                    .isTrue();
            verify(process).destroyForcibly();
        } finally {
            Thread.interrupted();
        }
    }
}
