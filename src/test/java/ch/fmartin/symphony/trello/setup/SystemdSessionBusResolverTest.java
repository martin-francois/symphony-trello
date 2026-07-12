package ch.fmartin.symphony.trello.setup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
                        "unixexec:path=/bin/false,argv1=old,argv01=new"),
                namedAddress("raw plus in Unix path", "unix:path=/tmp/bus+socket"),
                namedAddress("raw space in Unix path", "unix:path=/tmp/bus socket"),
                namedAddress("raw at sign in Unix path", "unix:path=/tmp/bus@socket"),
                namedAddress("raw equals in Unix path", "unix:path=/tmp/bus=socket"),
                namedAddress("raw colon in Unix path", "unix:path=/tmp/bus:socket"),
                namedAddress("raw UTF-8 in Unix path", "unix:path=/tmp/bus-é"),
                namedAddress("raw UTF-8 in abstract Unix name", "unix:abstract=bus-é"),
                namedAddress("raw IPv6 TCP host", "tcp:host=::1,port=ssh"),
                namedAddress("raw space in unixexec path", "unixexec:path=/tmp/program path"),
                namedAddress("raw plus in unixexec path", "unixexec:path=/tmp/program+path"),
                namedAddress(
                        "raw punctuation and UTF-8 in unixexec argument", "unixexec:path=/bin/false,argv1=value=:é"),
                namedAddress(
                        "decoded NUL suffixes in unixexec values",
                        "unixexec:path=/bin/false%00ignored,argv1=value%00ignored"),
                namedAddress("decoded NUL suffix in Unix path", "unix:path=/tmp/bus%00ignored"),
                namedAddress("decoded NUL suffix in abstract name", "unix:abstract=bus%00ignored"),
                namedAddress("percent-encoded delimiters remain data", "unix:path=/tmp/bus%2Cpart%3Bpart"),
                namedAddress("raw comma remains a parameter delimiter", "unix:path=/tmp/bus,ignored"),
                namedAddress("raw semicolon remains an address delimiter", "unix:path=/tmp/bus;ignored"),
                namedAddress("raw UTF-8 Unix path below byte limit", "unix:path=/" + "a".repeat(103) + "é"),
                namedAddress("raw UTF-8 Unix path at byte limit", "unix:path=/" + "a".repeat(104) + "é"),
                namedAddress("raw UTF-8 abstract name below byte limit", "unix:abstract=" + "a".repeat(103) + "é"),
                namedAddress("raw UTF-8 abstract name at byte limit", "unix:abstract=" + "a".repeat(104) + "é"),
                namedAddress("unixexec empty suffix resolves to argv zero", "unixexec:path=/bin/false,argv=value"),
                namedAddress("unixexec leading plus index", "unixexec:path=/bin/false,argv+1=value"),
                namedAddress("unixexec leading whitespace index", "unixexec:path=/bin/false,argv 1=value"),
                namedAddress("unixexec C form-feed whitespace index", "unixexec:path=/bin/false,argv\f1=value"),
                namedAddress("unixexec negative zero index", "unixexec:path=/bin/false,argv-0=value"),
                namedAddress(
                        "unixexec duplicate textual index overwrites",
                        "unixexec:path=/bin/false,argv1=first,argv1=last"),
                namedAddress("machine PID with leading whitespace", "x-machine-unix:pid= 42"),
                namedAddress("machine PID with leading plus", "x-machine-unix:pid=+42"),
                namedAddress("machine PID with hexadecimal prefix", "x-machine-unix:pid=0x2a"),
                namedAddress("machine PID with binary prefix", "x-machine-unix:pid=0b101010"),
                namedAddress("machine PID with Python octal prefix", "x-machine-unix:pid=0o52"),
                namedAddress("machine PID with C octal prefix", "x-machine-unix:pid=052"),
                namedAddress("machine PID with decoded NUL suffix", "x-machine-unix:pid=42%00ignored"));
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
                namedAddress("unixexec argument hole", "unixexec:path=/bin/false,argv2=value"),
                namedAddress("decoded NUL leaves empty Unix path", "unix:path=%00ignored"),
                namedAddress("bare percent in known value", "unix:path=/tmp/bus%"),
                namedAddress("short percent triplet in known value", "unix:path=/tmp/bus%0"),
                namedAddress("nonhex percent triplet in known value", "unix:path=/tmp/bus%ZZ"),
                namedAddress("non-ASCII percent triplet in known value", "unix:path=/tmp/bus%ＦＦ"),
                namedAddress("malformed percent after decoded NUL remains invalid", "unix:path=/tmp/bus%00ignored%ZZ"),
                namedAddress("raw UTF-8 Unix path above byte limit", "unix:path=/" + "a".repeat(105) + "é"),
                namedAddress("raw UTF-8 abstract above byte limit", "unix:abstract=" + "a".repeat(105) + "é"),
                namedAddress("unixexec index above maximum", "unixexec:path=/bin/false,argv257=value"),
                namedAddress("unixexec index overflow", "unixexec:path=/bin/false,argv18446744073709551616=value"),
                namedAddress("unixexec negative nonzero index", "unixexec:path=/bin/false,argv-1=value"),
                namedAddress("unixexec missing equals", "unixexec:path=/bin/false,argv1"),
                namedAddress("unixexec trailing junk before equals", "unixexec:path=/bin/false,argv1x=value"),
                namedAddress(
                        "unixexec malformed percent after decoded argument NUL",
                        "unixexec:path=/bin/false,argv1=value%00ignored%ZZ"),
                namedAddress("machine PID zero", "x-machine-unix:pid=0"),
                namedAddress("machine PID negative zero", "x-machine-unix:pid=-0"),
                namedAddress("machine PID negative nonzero", "x-machine-unix:pid=-1"),
                namedAddress("machine PID overflow", "x-machine-unix:pid=2147483648"),
                namedAddress("machine PID trailing junk", "x-machine-unix:pid=42x"),
                namedAddress("machine PID empty", "x-machine-unix:pid="),
                namedAddress("machine PID decoded NUL empty prefix", "x-machine-unix:pid=%0042"));
    }

    @Test
    void unixexecResolvesNumericAliasesBySlotAndUsesPathAsMissingArgvZero() {
        // given
        String parameters = "path=/tmp/program path,argv1=first,argv01=last,argv2=value=%2C%3B=:é";

        // when
        SystemdSessionBusResolver.UnixExecAddress result =
                SystemdSessionBusResolver.parseUnixExecAddress(parameters).orElseThrow();

        // then
        assertDbusBytes(result.path(), "/tmp/program path");
        assertThat(result.arguments()).containsOnlyKeys(0, 1, 2);
        assertDbusBytes(result.arguments().get(0), "/tmp/program path");
        assertDbusBytes(result.arguments().get(1), "last");
        assertDbusBytes(result.arguments().get(2), "value=,;=:é");
    }

    @Test
    void unixexecNegativeZeroOverwritesTheResolvedArgvZeroSlot() {
        // given
        String parameters = "path=/bin/false,argv=first,argv-0=last%00ignored";

        // when
        SystemdSessionBusResolver.UnixExecAddress result =
                SystemdSessionBusResolver.parseUnixExecAddress(parameters).orElseThrow();

        // then
        assertThat(result.arguments()).containsOnlyKeys(0);
        assertDbusBytes(result.arguments().get(0), "last");
    }

    @Test
    void unixexecAcceptsAContiguousArgumentVectorThroughIndex256() {
        // given
        StringBuilder parameters = new StringBuilder("path=/bin/false");
        for (int index = 1; index <= 256; index++) {
            parameters.append(",argv").append(index).append("=value-").append(index);
        }

        // when
        SystemdSessionBusResolver.UnixExecAddress result = SystemdSessionBusResolver.parseUnixExecAddress(
                        parameters.toString())
                .orElseThrow();

        // then
        assertThat(result.arguments()).hasSize(257).containsKeys(0, 1, 256);
        assertDbusBytes(result.arguments().get(0), "/bin/false");
        assertDbusBytes(result.arguments().get(256), "value-256");
    }

    private static void assertDbusBytes(SystemdSessionBusResolver.DbusValue actual, String expected) {
        assertThat(actual.bytes()).containsExactlyElementsOf(utf8Bytes(expected));
    }

    private static List<Integer> utf8Bytes(String value) {
        List<Integer> bytes = new ArrayList<>();
        for (byte current : value.getBytes(StandardCharsets.UTF_8)) {
            bytes.add(Byte.toUnsignedInt(current));
        }
        return List.copyOf(bytes);
    }

    private static Arguments namedAddress(String name, String address) {
        return Arguments.of(Named.named(name, address));
    }
}
