package ch.fmartin.symphony.trello.setup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SystemdSessionBusResolverTest {
    private static final String XDG_RUNTIME_DIRECTORY = "XDG_RUNTIME_DIR";
    private static final String DBUS_SESSION_BUS_ADDRESS = "DBUS_SESSION_BUS_ADDRESS";

    @TempDir
    Path tempDir;

    @Test
    void resolvePrefersValidSessionBusWithoutResolvingNumericUserId() throws Exception {
        // given
        Path callerRuntime = tempDir.resolve("caller-runtime");
        Files.createDirectories(callerRuntime);
        String callerSessionBus = "unix:path=/tmp/session-bus";
        CommandRunner commandRunner = mock();
        SystemdSessionBusResolver resolver = new SystemdSessionBusResolver(
                Map.of(XDG_RUNTIME_DIRECTORY, callerRuntime.toString(), DBUS_SESSION_BUS_ADDRESS, callerSessionBus),
                commandRunner);

        // when
        CommandEnvironment result = resolver.resolve();

        // then
        assertThat(result)
                .isEqualTo(new CommandEnvironment(
                        Map.of(DBUS_SESSION_BUS_ADDRESS, callerSessionBus), Set.of(XDG_RUNTIME_DIRECTORY)));
        verifyNoInteractions(commandRunner);
    }

    @Test
    void resolveRemovesInvalidCallerHintsBeforeUsingStandardRuntime() throws Exception {
        // given
        Path runtimeRoot = tempDir.resolve("run/user");
        Path standardRuntime = runtimeRoot.resolve("1000");
        Files.createDirectories(standardRuntime);
        CommandRunner commandRunner = mock();
        when(commandRunner.run("id", "-u")).thenReturn(new CommandResult(0, "1000\n"));
        SystemdSessionBusResolver resolver = new SystemdSessionBusResolver(
                Map.of(
                        XDG_RUNTIME_DIRECTORY,
                        "relative-runtime",
                        DBUS_SESSION_BUS_ADDRESS,
                        "unix:path",
                        "SYMPHONY_TRELLO_TEST_RUNTIME_ROOT",
                        runtimeRoot.toString()),
                commandRunner);

        // when
        CommandEnvironment result = resolver.resolve();

        // then
        assertThat(result)
                .isEqualTo(new CommandEnvironment(
                        Map.of(XDG_RUNTIME_DIRECTORY, standardRuntime.toString()), Set.of(DBUS_SESSION_BUS_ADDRESS)));
        verify(commandRunner).run("id", "-u");
        verifyNoMoreInteractions(commandRunner);
    }

    @Test
    void resolveFallsBackFromInvalidConfiguredUserIdToNumericEffectiveUserId() throws Exception {
        // given
        Path runtimeRoot = tempDir.resolve("run/user");
        Path standardRuntime = runtimeRoot.resolve("1000");
        Files.createDirectories(standardRuntime);
        CommandRunner commandRunner = mock();
        when(commandRunner.run("id", "-u")).thenReturn(new CommandResult(0, "1000\n"));
        SystemdSessionBusResolver resolver = new SystemdSessionBusResolver(
                Map.of(
                        "SYMPHONY_TRELLO_TEST_UID",
                        "not-numeric",
                        "SYMPHONY_TRELLO_TEST_RUNTIME_ROOT",
                        runtimeRoot.toString()),
                commandRunner);

        // when
        CommandEnvironment result = resolver.resolve();

        // then
        assertThat(result)
                .isEqualTo(new CommandEnvironment(
                        Map.of(XDG_RUNTIME_DIRECTORY, standardRuntime.toString()), Set.of(DBUS_SESSION_BUS_ADDRESS)));
        verify(commandRunner).run("id", "-u");
        verifyNoMoreInteractions(commandRunner);
    }
}
