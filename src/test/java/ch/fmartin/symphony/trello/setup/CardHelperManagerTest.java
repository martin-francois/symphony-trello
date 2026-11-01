package ch.fmartin.symphony.trello.setup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class CardHelperManagerTest {
    @TempDir
    Path temporary;

    @ParameterizedTest
    @ValueSource(strings = {"start", "stop", "status"})
    void liveUninspectableHelperNeverReportsSuccessOrSignalsTheProcess(String operation) throws Exception {
        // given
        ReadyFixture fixture = readyFixture();
        var manager = new CardHelperManager(fixture.platform(), Duration.ZERO);
        var output = new ByteArrayOutputStream();
        try (var out = new PrintStream(output)) {

            // when
            Throwable failure = catchThrowable(() -> invoke(operation, manager, fixture.paths(), out));

            // then
            assertThat(failure).isInstanceOf(IOException.class).hasMessageContaining("cannot verify card helper");
            assertThat(output.toString(StandardCharsets.UTF_8)).isEmpty();
            verify(fixture.platform(), never()).stop(anyLong(), any(), any());
            verify(fixture.platform(), never()).start(any(), any(), any(), any(), any());
        }
    }

    private static void invoke(String operation, CardHelperManager manager, LocalWorkerPaths paths, PrintStream out)
            throws IOException {
        switch (operation) {
            case "start" -> manager.start(paths, out);
            case "stop" -> manager.stop(paths, out);
            case "status" -> manager.status(paths, out);
            default ->
                throw new IllegalArgumentException("cannot invoke helper test operation: select start, stop or status");
        }
    }

    @Test
    void identifiesTheHelperThroughPlatformArgumentsWithoutJdkArgumentSupport() throws Exception {
        // given
        ReadyFixture fixture = readyFixture();
        when(fixture.platform().commandArguments(fixture.pid()))
                .thenReturn(Optional.of(List.of(
                        "-Dsymphony.trello.card_helper_root=" + fixture.files().root(),
                        CardHelperMain.class.getName())));
        var output = new ByteArrayOutputStream();
        try (var out = new PrintStream(output)) {

            // when
            new CardHelperManager(fixture.platform()).status(fixture.paths(), out);

            // then
            assertThat(output.toString(StandardCharsets.UTF_8)).contains("Card helper is running.");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void staleOrReusedPidDoesNotSignalAnUnrelatedProcess(boolean alive) throws Exception {
        // given
        ReadyFixture fixture = readyFixture();
        when(fixture.platform().isAlive(fixture.pid())).thenReturn(alive);
        when(fixture.platform().commandArguments(fixture.pid())).thenReturn(Optional.of(List.of("another-program")));
        var output = new ByteArrayOutputStream();
        try (var out = new PrintStream(output)) {

            // when
            new CardHelperManager(fixture.platform()).stop(fixture.paths(), out);

            // then
            assertThat(output.toString(StandardCharsets.UTF_8)).contains("Card helper is stopped.");
            verify(fixture.platform(), never()).stop(anyLong(), any(), any());
        }
    }

    private ReadyFixture readyFixture() throws IOException {
        Path config = Files.createDirectory(temporary.resolve("config"));
        var paths =
                new LocalWorkerPaths(temporary, config, temporary.resolve("workspaces"), temporary.resolve("state"));
        var files = CardHelperFiles.from(paths);
        files.initialize(config);
        long pid = 12345;
        Files.write(
                files.ready(),
                CardHelperProtocol.json()
                        .writeValueAsBytes(
                                new CardHelperProtocol.Ready(CardHelperProtocol.VERSION, UUID.randomUUID(), pid)));
        var platform = mock(ManagedProcessPlatform.class);
        when(platform.isAlive(pid)).thenReturn(true);
        when(platform.start(any(), any(), any(), any(), any())).thenReturn(new ManagedProcessHandle(67890));
        when(platform.stop(eq(67890L), any(), any())).thenReturn(true);
        return new ReadyFixture(paths, files, platform, pid);
    }

    @Test
    void stoppedHelperDoesNotAdvertiseReadinessWhenShutdownHooksDoNotRun() throws Exception {
        // given
        ReadyFixture fixture = readyFixture();
        Files.createFile(fixture.files().processLock());
        when(fixture.platform().commandArguments(fixture.pid()))
                .thenReturn(Optional.of(List.of(
                        "-Dsymphony.trello.card_helper_root=" + fixture.files().root(),
                        CardHelperMain.class.getName())));
        when(fixture.platform().stop(eq(fixture.pid()), any(), any())).thenReturn(true);
        try (var out = new PrintStream(OutputStream.nullOutputStream())) {

            // when
            new CardHelperManager(fixture.platform()).stop(fixture.paths(), out);

            // then
            assertThat(fixture.files().ready()).doesNotExist();
        }
    }

    private record ReadyFixture(
            LocalWorkerPaths paths, CardHelperFiles files, ManagedProcessPlatform platform, long pid) {}

    @Test
    void failedReadyHandshakeStopsOnlyTheChildItStarted() throws Exception {
        // given
        Path config = Files.createDirectory(temporary.resolve("config"));
        var paths =
                new LocalWorkerPaths(temporary, config, temporary.resolve("workspaces"), temporary.resolve("state"));
        var platform = mock(ManagedProcessPlatform.class);
        when(platform.start(any(), any(), any(), any(), any())).thenReturn(new ManagedProcessHandle(12345));
        when(platform.stop(eq(12345L), any(), any())).thenReturn(true);
        var manager = new CardHelperManager(platform, Duration.ZERO);
        try (var out = new PrintStream(OutputStream.nullOutputStream())) {

            // when
            Throwable thrown = catchThrowable(() -> manager.start(paths, out));

            // then
            assertThat(thrown).isInstanceOf(IOException.class).hasMessageContaining("cannot start card helper");
            verify(platform).stop(eq(12345L), any(), any());
        }
    }
}
