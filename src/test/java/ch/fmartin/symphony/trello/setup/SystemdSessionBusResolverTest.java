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
import java.util.stream.Stream;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

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

    @MethodSource("syntacticallyUsableCallerAddresses")
    @ParameterizedTest(name = "{0}")
    void resolvePreservesTheCompleteOriginalAddressAfterTheFirstUsableEntry(String callerSessionBus) throws Exception {
        // given
        Path callerRuntime = tempDir.resolve("caller-runtime");
        Files.createDirectories(callerRuntime);
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

    private static Stream<Arguments> syntacticallyUsableCallerAddresses() {
        return Stream.of(
                namedAddress("unknown Unix parameter", "unix:path=/tmp/bus,future-option=ignored"),
                namedAddress("malformed unknown Unix value", "unix:path=/tmp/bus,future-option=%ZZ"),
                namedAddress("unknown Unix parameter before path", "unix:future-option=%ZZ,path=/tmp/bus"),
                namedAddress("unknown raw Unix segment", "unix:path=/tmp/bus,future-segment"),
                namedAddress("usable first and malformed unused later", "unix:path=/tmp/bus;unix:path"),
                namedAddress(
                        "empty and unsupported entries before usable entry",
                        ";autolaunch:;nonce-tcp:host=localhost,port=1234,noncefile=/tmp/nonce;;"
                                + "unix:path=/tmp/bus;"),
                namedAddress("unknown TCP parameter and service port", "tcp:host=localhost,port=ssh,future-option=%ZZ"),
                namedAddress("unknown unixexec parameter", "unixexec:path=/bin/false,future-option=%ZZ"),
                namedAddress("unknown machine parameter", "x-machine-unix:machine=.host,future-option=%ZZ"),
                namedAddress(
                        "unixexec leading-zero alias overwrites numeric argument",
                        "unixexec:path=/bin/false,argv1=old,argv01=new"));
    }

    @MethodSource("malformedFirstRecognizedAddresses")
    @ParameterizedTest(name = "{0}")
    void resolveRejectsMalformedRecognizedFieldsBeforeAnyUsableEntry(String callerSessionBus) throws Exception {
        // given
        Path callerRuntime = tempDir.resolve("caller-runtime");
        Files.createDirectories(callerRuntime);
        CommandRunner commandRunner = mock();
        SystemdSessionBusResolver resolver = new SystemdSessionBusResolver(
                Map.of(XDG_RUNTIME_DIRECTORY, callerRuntime.toString(), DBUS_SESSION_BUS_ADDRESS, callerSessionBus),
                commandRunner);

        // when
        CommandEnvironment result = resolver.resolve();

        // then
        assertThat(result)
                .isEqualTo(new CommandEnvironment(
                        Map.of(XDG_RUNTIME_DIRECTORY, callerRuntime.toString()), Set.of(DBUS_SESSION_BUS_ADDRESS)));
        verifyNoInteractions(commandRunner);
    }

    private static Stream<Arguments> malformedFirstRecognizedAddresses() {
        return Stream.of(
                namedAddress("known field has malformed escape", "unix:path=/tmp/%ZZ"),
                namedAddress("duplicate Unix path", "unix:path=/tmp/first,path=/tmp/second"),
                namedAddress("duplicate TCP host", "tcp:host=first,host=second,port=ssh"),
                namedAddress(
                        "duplicate guid",
                        "x-machine-unix:machine=.host,guid=00112233445566778899aabbccddeeff,"
                                + "guid=ffeeddccbbaa99887766554433221100"),
                namedAddress("malformed first before later usable entry", "unix:path;unix:path=/tmp/bus"),
                namedAddress("unixexec argument hole", "unixexec:path=/bin/false,argv2=value"));
    }

    private static Arguments namedAddress(String name, String address) {
        return Arguments.of(Named.named(name, address));
    }
}
