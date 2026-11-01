package ch.fmartin.symphony.trello.setup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

final class ConnectedBoardDisconnectTest {
    @TempDir
    Path temporary;

    @ParameterizedTest
    @ValueSource(strings = {"workflow", "dotenv", "environment"})
    void stoppedBoardsDisconnectUsingRegisteredPortsWhenCurrentConfigurationIsInvalid(String source) throws Exception {
        // given
        Map<String, String> environment =
                source.equals("environment") ? Map.of("SYMPHONY_HTTP_PORT", "invalid") : Map.of();
        var fixture = new LocalWorkerManagerTestFixture(temporary, environment);
        ConnectedBoard board = fixture.connectedBoard("board-1", "Queue");
        fixture.save(board);
        Files.writeString(board.workflowPath(), "---\nserver:\n  port: 0\n---\n");
        if (source.equals("dotenv")) {
            Files.writeString(board.envPath(), "SYMPHONY_HTTP_PORT=invalid\n");
        }
        var editor = new WorkflowConfigEditor();
        var checker = spy(new LocalHealthChecker(environment, editor));
        doReturn(fixture.stopped(board))
                .when(checker)
                .workflowHealth(board.workflowPath(), board.boardId(), board.boardKey(), board.serverPort());
        var manager = new LocalWorkerManager(
                environment,
                editor,
                checker,
                fixture.platform,
                new LocalLogTailer(),
                fixture.credentialPreflight,
                mock(CardHelperManager.class),
                new ManagedWorkflowImport(editor, environment));
        try (var bytes = new ByteArrayOutputStream();
                var out = new PrintStream(bytes, false, StandardCharsets.UTF_8)) {

            // when
            int exit = manager.disconnect(fixture.stopRequest("Queue"), out);

            // then
            assertThat(exit).isZero();
            assertThat(new ConnectedBoardRepository(fixture.paths.manifestPath())
                            .loadForLifecycle()
                            .boards())
                    .isEmpty();
            verify(checker).workflowHealth(board.workflowPath(), board.boardId(), board.boardKey(), board.serverPort());
            verify(fixture.platform, never()).stop(anyLong(), any(), any());
        }
    }

    @CsvSource({"true,true", "true,false", "false,true", "false,false"})
    @ParameterizedTest
    void unavailableWorkerIdentityPreservesRecoveryState(boolean disconnect, boolean emptyArguments) throws Exception {
        // given
        var platform = mock(ProcessHandleManagedProcessPlatform.class, CALLS_REAL_METHODS);
        doReturn(true).when(platform).isAlive(42);
        doReturn(emptyArguments ? Optional.of(List.of()) : Optional.empty())
                .when(platform)
                .commandArguments(42);
        var fixture = new LocalWorkerManagerTestFixture(temporary, Map.of(), new WorkflowConfigEditor(), platform);
        ConnectedBoard board = fixture.connectedBoard("board-1", "Queue");
        fixture.save(board);
        var files = fixture.writeManagedPid(board, 42);
        new ManagedProcessStore(fixture.paths.stateHome()).recordWorkflowPath(board.workflowPath());
        try (var bytes = new ByteArrayOutputStream();
                var out = new PrintStream(bytes, false, StandardCharsets.UTF_8)) {

            // when
            Throwable thrown = catchThrowable(() -> {
                if (disconnect) {
                    fixture.manager.disconnect(fixture.stopRequest("Queue"), out);
                } else {
                    fixture.manager.stop(fixture.stopRequest("Queue"), out);
                }
            });

            // then
            assertThat(thrown)
                    .isInstanceOf(TrelloBoardSetupException.class)
                    .hasMessageContaining("cannot inspect worker", "retry");
            assertThat(new ConnectedBoardRepository(fixture.paths.manifestPath())
                            .loadForLifecycle()
                            .boards())
                    .containsExactly(board);
            assertThat(files.pidFile()).hasContent("42");
            assertThat(files.workflowPathFile()).isRegularFile();
            verify(platform, never()).stop(anyLong(), any(), any());
        }
    }

    @CsvSource({"true,true", "true,false", "false,true", "false,false"})
    @ParameterizedTest
    void staleForeignTrackingDoesNotHideAHealthyUntrackedWorker(boolean disconnect, boolean verified) throws Exception {
        // given
        var fixture = new LocalWorkerManagerTestFixture(temporary);
        ConnectedBoard board = fixture.connectedBoard("board-1", "Queue");
        fixture.save(board);
        var files = fixture.writeManagedPid(board, 42);
        new ManagedProcessStore(fixture.paths.stateHome()).recordWorkflowPath(board.workflowPath());
        when(fixture.platform.isAlive(42)).thenReturn(true);
        when(fixture.platform.isManaged(42, fixture.paths.appHome(), board.workflowPath()))
                .thenReturn(false);
        when(fixture.healthChecker.boardHealth(board)).thenReturn(fixture.sameWorkflowWithPid(board, 43));
        when(fixture.platform.isAlive(43)).thenReturn(true);
        when(fixture.platform.isManaged(43, fixture.paths.appHome(), board.workflowPath()))
                .thenReturn(verified);
        when(fixture.platform.stop(43, Duration.ofSeconds(15), Duration.ofSeconds(5)))
                .thenReturn(true);
        try (var bytes = new ByteArrayOutputStream();
                var out = new PrintStream(bytes, false, StandardCharsets.UTF_8)) {

            // when
            Throwable thrown = catchThrowable(() -> {
                if (disconnect) {
                    fixture.manager.disconnect(fixture.stopRequest("Queue"), out);
                } else {
                    fixture.manager.stop(fixture.stopRequest("Queue"), out);
                }
            });

            // then
            List<ConnectedBoard> remaining = new ConnectedBoardRepository(fixture.paths.manifestPath())
                    .loadForLifecycle()
                    .boards();
            if (verified) {
                assertThat(thrown).isNull();
                verify(fixture.platform).stop(43, Duration.ofSeconds(15), Duration.ofSeconds(5));
                assertThat(remaining).containsExactlyElementsOf(disconnect ? List.of() : List.of(board));
                assertThat(files.pidFile()).doesNotExist();
                assertThat(files.workflowPathFile()).doesNotExist();
            } else {
                assertThat(thrown).isInstanceOf(TrelloBoardSetupException.class).hasMessageContaining("no managed pid");
                assertThat(remaining).containsExactly(board);
                assertThat(files.pidFile()).hasContent("42");
                assertThat(files.workflowPathFile()).isRegularFile();
                verify(fixture.platform, never()).stop(anyLong(), any(), any());
            }
            verify(fixture.healthChecker).boardHealth(board);
            verify(fixture.platform, never()).stop(42, Duration.ofSeconds(15), Duration.ofSeconds(5));
        }
    }

    @CsvSource({"true,true", "true,false", "false,true", "false,false"})
    @ParameterizedTest
    void failedStatusResponsePreservesConnectionAndRecoveryRecords(boolean disconnect, boolean staleTracking)
            throws Exception {
        // given
        var server =
                HttpServer.create(new InetSocketAddress(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), 0), 0);
        server.createContext("/api/v1/local-status", exchange -> {
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        server.start();
        try (var bytes = new ByteArrayOutputStream();
                var out = new PrintStream(bytes, false, StandardCharsets.UTF_8)) {
            var fixture = new LocalWorkerManagerTestFixture(temporary);
            ConnectedBoard board = fixture.connectedBoard("board-1", "Queue")
                    .withServerPort(server.getAddress().getPort());
            var editor = new WorkflowConfigEditor();
            editor.updateServerPort(board.workflowPath(), board.serverPort());
            fixture.save(board);
            var files = fixture.managedFiles(board);
            new ManagedProcessStore(fixture.paths.stateHome()).recordWorkflowPath(board.workflowPath());
            if (staleTracking) {
                fixture.writeManagedPid(board, 42);
                when(fixture.platform.isAlive(42)).thenReturn(true);
                when(fixture.platform.isManaged(42, fixture.paths.appHome(), board.workflowPath()))
                        .thenReturn(false);
            }
            var manager = new LocalWorkerManager(
                    Map.of(),
                    editor,
                    new LocalHealthChecker(Map.of(), editor),
                    fixture.platform,
                    new LocalLogTailer(),
                    fixture.credentialPreflight,
                    mock(CardHelperManager.class),
                    new ManagedWorkflowImport(editor, Map.of()));

            // when
            Throwable thrown = catchThrowable(() -> {
                if (disconnect) {
                    manager.disconnect(fixture.stopRequest("Queue"), out);
                } else {
                    manager.stop(fixture.stopRequest("Queue"), out);
                }
            });

            // then
            assertThat(thrown)
                    .isInstanceOf(TrelloBoardSetupException.class)
                    .hasMessageContaining("cannot verify worker shutdown");
            assertThat(new ConnectedBoardRepository(fixture.paths.manifestPath())
                            .loadForLifecycle()
                            .boards())
                    .containsExactly(board);
            assertThat(files.workflowPathFile()).isRegularFile();
            assertThat(LocalHealthChecker.portAcceptsConnections(board.serverPort()))
                    .as("the failed status endpoint remains live throughout the attempted stop")
                    .isTrue();
            verify(fixture.platform, never()).stop(anyLong(), any(), any());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void disconnectStopsARegisteredWorkerEvenWhenItsWorkflowAndCredentialsAreMissing() throws Exception {
        // given
        var fixture = new LocalWorkerManagerTestFixture(temporary);
        ConnectedBoard board = fixture.connectedBoard("board-1", "Queue");
        fixture.save(board);
        fixture.stubStoppableManagedPid(board, 42);
        Files.delete(board.workflowPath());
        Files.delete(board.envPath());
        try (var bytes = new ByteArrayOutputStream();
                var out = new PrintStream(bytes, false, StandardCharsets.UTF_8)) {

            // when
            int exit = fixture.manager.disconnect(fixture.stopRequest("Queue"), out);

            // then
            assertThat(exit).isZero();
            assertThat(new ConnectedBoardRepository(fixture.paths.manifestPath())
                            .loadForLifecycle()
                            .boards())
                    .isEmpty();
            assertThat(fixture.managedFiles(board).pidFile()).doesNotExist();
            assertThat(bytes.toString(StandardCharsets.UTF_8))
                    .contains("Disconnected \"Queue\"", "Trello board unchanged");
        }
    }

    @Test
    void aFailedStopLeavesTheBoardRegisteredForRecovery() throws Exception {
        // given
        var fixture = new LocalWorkerManagerTestFixture(temporary);
        ConnectedBoard board = fixture.connectedBoard("board-1", "Queue");
        fixture.save(board);
        fixture.stubManagedPid(board, 42);
        try (var bytes = new ByteArrayOutputStream();
                var out = new PrintStream(bytes, false, StandardCharsets.UTF_8)) {

            // when
            Throwable thrown = catchThrowable(() -> fixture.manager.disconnect(fixture.stopRequest("Queue"), out));

            // then
            assertThat(thrown).isInstanceOf(TrelloBoardSetupException.class).hasMessageContaining("did not stop");
            assertThat(new ConnectedBoardRepository(fixture.paths.manifestPath())
                            .loadForLifecycle()
                            .boards())
                    .containsExactly(board);
            assertThat(fixture.managedFiles(board).pidFile()).isRegularFile();
        }
    }
}
