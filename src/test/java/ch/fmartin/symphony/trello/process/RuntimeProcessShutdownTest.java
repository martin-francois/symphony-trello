package ch.fmartin.symphony.trello.process;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class RuntimeProcessShutdownTest {
    @TempDir
    Path temporary;

    @EnabledOnOs(OS.WINDOWS)
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void releasesNativeWorkingDirectoryBeforeReturning(boolean interrupted) throws Exception {
        // given
        Path workspace = Files.createDirectory(temporary.resolve("workspace"));
        Process process = new ProcessBuilder("bash", "-c", "printf 'ready\\n'; while IFS= read -r line; do :; done")
                .directory(workspace.toFile())
                .start();
        try (var output = process.inputReader()) {
            assertThat(output.readLine()).isEqualTo("ready");

            // when
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
            RuntimeProcessShutdown.stop(process);

            // then
            assertThat(Thread.currentThread().isInterrupted()).isEqualTo(interrupted);
            Thread.interrupted();
            Files.delete(workspace);
            assertThat(workspace).doesNotExist();
        } finally {
            Thread.interrupted();
            process.destroyForcibly();
        }
    }

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
