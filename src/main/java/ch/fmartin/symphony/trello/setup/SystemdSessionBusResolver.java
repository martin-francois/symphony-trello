package ch.fmartin.symphony.trello.setup;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

final class SystemdSessionBusResolver {
    private static final int UNIX_FILE_TYPE_MASK = 0170000;
    private static final int UNIX_SOCKET_FILE_TYPE = 0140000;
    private static final int DBUS_UNIX_PATH_MAX_BYTES = 107;
    private static final int DBUS_UNIX_ABSTRACT_MAX_BYTES = 106;
    private static final long DBUS_UNIX_ID_MAX = 4_294_967_294L;
    private static final long DBUS_UNIX_LEGACY_INVALID_ID = 65_535L;
    private static final int DBUS_UNIXEXEC_MAX_ARGUMENT_INDEX = 256;
    private static final int DBUS_MACHINE_NAME_MAX_BYTES = 64;
    private static final int DBUS_C_UNSIGNED_BITS = 32;
    private static final BigInteger DBUS_ULONG_MODULUS = BigInteger.ONE.shiftLeft(Long.SIZE);
    private static final BigInteger DBUS_ULONG_MAX = DBUS_ULONG_MODULUS.subtract(BigInteger.ONE);
    private static final BigInteger DBUS_UINT_MODULUS = BigInteger.ONE.shiftLeft(DBUS_C_UNSIGNED_BITS);
    private static final BigInteger DBUS_PID_MAX = BigInteger.valueOf(Integer.MAX_VALUE);
    private static final String SYSTEMD_NUMERIC_WHITESPACE = " \t\n\r";
    private static final String C_NUMERIC_WHITESPACE = SYSTEMD_NUMERIC_WHITESPACE + "\f\u000B";
    private static final int DBUS_GUID_HEX_LENGTH = 32;
    private static final int DBUS_GUID_UUID_LENGTH = 36;
    private static final Set<Integer> DBUS_GUID_UUID_DASH_INDEXES = Set.of(8, 13, 18, 23);
    private static final Set<String> DBUS_UNIX_KEYS = Set.of("guid", "path", "abstract", "uid", "gid");
    private static final Set<String> DBUS_TCP_KEYS = Set.of("guid", "host", "port", "family");
    private static final Set<String> DBUS_MACHINE_KEYS = Set.of("guid", "machine", "pid");
    private static final String XDG_RUNTIME_DIRECTORY = "XDG_RUNTIME_DIR";
    private static final String DBUS_SESSION_BUS_ADDRESS = "DBUS_SESSION_BUS_ADDRESS";

    private final Map<String, String> environment;
    private final CommandRunner commandRunner;

    SystemdSessionBusResolver(Map<String, String> environment, CommandRunner commandRunner) {
        this.environment = Map.copyOf(environment);
        this.commandRunner = commandRunner;
    }

    CommandEnvironment resolve() {
        Optional<String> callerRuntimeDirectory = validRuntimeDirectory(environment.get(XDG_RUNTIME_DIRECTORY));
        Optional<String> callerSessionBusAddress = validDbusAddress(environment.get(DBUS_SESSION_BUS_ADDRESS));
        Set<String> removals = new HashSet<>();
        if (environment.containsKey(XDG_RUNTIME_DIRECTORY) && callerRuntimeDirectory.isEmpty()) {
            removals.add(XDG_RUNTIME_DIRECTORY);
        }
        if (environment.containsKey(DBUS_SESSION_BUS_ADDRESS) && callerSessionBusAddress.isEmpty()) {
            removals.add(DBUS_SESSION_BUS_ADDRESS);
        }
        if (callerSessionBusAddress.isPresent()) {
            return commandEnvironment(callerRuntimeDirectory, callerSessionBusAddress, removals);
        }

        if (callerRuntimeDirectory.isPresent()) {
            return commandEnvironment(callerRuntimeDirectory, runtimeBusAddress(callerRuntimeDirectory), removals);
        }

        Optional<String> standardRuntimeDirectory = standardRuntimeDirectory();
        Optional<String> standardRuntimeBusAddress = runtimeBusAddress(standardRuntimeDirectory);
        return commandEnvironment(standardRuntimeDirectory, standardRuntimeBusAddress, removals);
    }

    private static CommandEnvironment commandEnvironment(
            Optional<String> runtimeDirectory, Optional<String> sessionBusAddress, Set<String> removals) {
        Map<String, String> overrides = new HashMap<>();
        Set<String> effectiveRemovals = new HashSet<>(removals);
        // systemctl prefers <runtime>/systemd/private whenever XDG_RUNTIME_DIR is present. Expose one
        // connection hint so an explicit or derived session-bus address cannot be shadowed by it.
        if (sessionBusAddress.isPresent()) {
            overrides.put(DBUS_SESSION_BUS_ADDRESS, sessionBusAddress.orElseThrow());
            effectiveRemovals.add(XDG_RUNTIME_DIRECTORY);
        } else if (runtimeDirectory.isPresent()) {
            overrides.put(XDG_RUNTIME_DIRECTORY, runtimeDirectory.orElseThrow());
            effectiveRemovals.add(DBUS_SESSION_BUS_ADDRESS);
        }
        effectiveRemovals.removeAll(overrides.keySet());
        return new CommandEnvironment(overrides, effectiveRemovals);
    }

    private static Optional<String> runtimeBusAddress(Optional<String> runtimeDirectory) {
        return runtimeDirectory
                .map(Path::of)
                .map(value -> value.resolve("bus"))
                .filter(SystemdSessionBusResolver::isUnixDomainSocket)
                .map(Path::toString)
                .map(SystemdSessionBusResolver::escapeDbusAddressValue)
                .map(value -> "unix:path=" + value);
    }

    private static String escapeDbusAddressValue(String value) {
        StringBuilder escaped = new StringBuilder(value.length());
        for (byte current : value.getBytes(StandardCharsets.UTF_8)) {
            int unsigned = Byte.toUnsignedInt(current);
            if (isDbusAddressUnescapedByte(unsigned)) {
                escaped.append((char) unsigned);
            } else {
                escaped.append('%');
                escaped.append("0123456789ABCDEF".charAt(unsigned >>> 4));
                escaped.append("0123456789ABCDEF".charAt(unsigned & 0x0F));
            }
        }
        return escaped.toString();
    }

    private Optional<String> standardRuntimeDirectory() {
        Path runtimeRoot = Path.of(environment.getOrDefault("SYMPHONY_TRELLO_TEST_RUNTIME_ROOT", "/run/user"));
        return UserIdResolver.resolve(environment, commandRunner)
                .map(runtimeRoot::resolve)
                .filter(Files::isDirectory)
                .map(Path::toString);
    }

    private static Optional<String> numericValue(String value) {
        if (value == null || value.isEmpty()) {
            return Optional.empty();
        }
        for (int index = 0; index < value.length(); index++) {
            if (!Character.isDigit(value.charAt(index))) {
                return Optional.empty();
            }
        }
        return Optional.of(value);
    }

    private static Optional<String> validRuntimeDirectory(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            Path path = Path.of(value);
            return path.isAbsolute() && Files.isDirectory(path) ? Optional.of(value) : Optional.empty();
        } catch (InvalidPathException invalidPath) {
            return Optional.empty();
        }
    }

    private static boolean isUnixDomainSocket(Path path) {
        try {
            Object mode = Files.getAttribute(path, "unix:mode", LinkOption.NOFOLLOW_LINKS);
            return mode instanceof Integer unixMode && (unixMode & UNIX_FILE_TYPE_MASK) == UNIX_SOCKET_FILE_TYPE;
        } catch (IOException | UnsupportedOperationException | IllegalArgumentException | SecurityException failure) {
            // The fallback is optional; unsupported or unreadable Unix attributes leave the bus address unset.
            return false;
        }
    }

    private static Optional<String> validDbusAddress(String value) {
        if (value == null || value.isBlank() || value.indexOf('\0') >= 0) {
            return Optional.empty();
        }
        for (String address : value.split(";", -1)) {
            int separator = address.indexOf(':');
            if (separator <= 0) {
                continue;
            }
            String transport = address.substring(0, separator);
            if (!isSystemdBusTransport(transport)) {
                continue;
            }
            return isSyntacticallyUsableAddress(transport, address.substring(separator + 1))
                    ? Optional.of(value)
                    : Optional.empty();
        }
        return Optional.empty();
    }

    private static boolean isSystemdBusTransport(String transport) {
        return switch (transport) {
            case "unix", "tcp", "unixexec", "x-machine-unix" -> true;
            default -> false;
        };
    }

    private static Optional<Map<String, String>> recognizedAddressParameters(
            String parameterText, Set<String> recognizedKeys) {
        Map<String, String> parameters = new HashMap<>();
        for (String parameter : parameterText.split(",", -1)) {
            for (String key : recognizedKeys) {
                String prefix = key + "=";
                if (parameter.startsWith(prefix)) {
                    String previous = parameters.putIfAbsent(key, parameter.substring(prefix.length()));
                    if (previous != null) {
                        return Optional.empty();
                    }
                    break;
                }
            }
        }
        return Optional.of(Map.copyOf(parameters));
    }

    private static boolean isSyntacticallyUsableAddress(String transport, String parameterText) {
        // systemctl uses systemd's sd-bus parser, whose supported transport set is narrower than
        // libdbus (notably excluding autolaunch and nonce-tcp). Unknown transport parameters are
        // skipped as raw text; only fields recognized by the selected transport are decoded.
        return switch (transport) {
            case "unix" ->
                recognizedAddressParameters(parameterText, DBUS_UNIX_KEYS)
                        .filter(SystemdSessionBusResolver::validUnixAddress)
                        .isPresent();
            case "tcp" ->
                recognizedAddressParameters(parameterText, DBUS_TCP_KEYS)
                        .filter(parameters -> validNonEmptyDecodedDbusValue(parameters.get("host")))
                        .filter(parameters -> validNonEmptyDecodedDbusValue(parameters.get("port")))
                        .filter(parameters -> validTcpFamily(parameters.get("family")))
                        .filter(SystemdSessionBusResolver::validOptionalDbusGuid)
                        .isPresent();
            case "unixexec" -> parseUnixExecAddress(parameterText).isPresent();
            case "x-machine-unix" ->
                recognizedAddressParameters(parameterText, DBUS_MACHINE_KEYS)
                        .filter(SystemdSessionBusResolver::validMachineAddress)
                        .isPresent();
            default -> false;
        };
    }

    private static boolean validUnixAddress(Map<String, String> parameters) {
        if (!validOptionalDbusGuid(parameters)
                || !validOptionalUnixId(parameters.get("uid"))
                || !validOptionalUnixId(parameters.get("gid"))) {
            return false;
        }
        boolean hasPath = parameters.containsKey("path");
        boolean hasAbstract = parameters.containsKey("abstract");
        if (hasPath == hasAbstract) {
            return false;
        }
        return hasPath
                ? validDecodedDbusByteLength(parameters.get("path"), DBUS_UNIX_PATH_MAX_BYTES)
                : validDecodedDbusByteLength(parameters.get("abstract"), DBUS_UNIX_ABSTRACT_MAX_BYTES);
    }

    private static boolean validOptionalUnixId(String encodedId) {
        if (encodedId == null) {
            return true;
        }
        return decodedAsciiDbusValue(encodedId)
                .filter(SystemdSessionBusResolver::isValidUnixId)
                .isPresent();
    }

    private static boolean isValidUnixId(String idValue) {
        if (numericValue(idValue).isEmpty() || (idValue.length() > 1 && idValue.startsWith("0"))) {
            return false;
        }
        try {
            long id = Long.parseLong(idValue);
            return id <= DBUS_UNIX_ID_MAX && id != DBUS_UNIX_LEGACY_INVALID_ID;
        } catch (NumberFormatException invalidId) {
            return false;
        }
    }

    static Optional<UnixExecAddress> parseUnixExecAddress(String parameterText) {
        Map<String, String> namedParameters = new HashMap<>();
        Map<Integer, DbusValue> arguments = new HashMap<>();
        int highestArgumentIndex = 0;
        for (String parameter : parameterText.split(",", -1)) {
            if (parameter.startsWith("guid=") || parameter.startsWith("path=")) {
                int separator = parameter.indexOf('=');
                String key = parameter.substring(0, separator);
                if (namedParameters.putIfAbsent(key, parameter.substring(separator + 1)) != null) {
                    return Optional.empty();
                }
                continue;
            }
            if (!parameter.startsWith("argv")) {
                continue;
            }
            Optional<UnixExecArgumentKey> argumentKey = unixExecArgumentKey(parameter);
            if (argumentKey.isEmpty()) {
                return Optional.empty();
            }
            UnixExecArgumentKey key = argumentKey.get();
            Optional<DbusValue> argument = decodedDbusValue(parameter.substring(key.valueStart()));
            if (argument.isEmpty()) {
                return Optional.empty();
            }
            arguments.put(key.index(), argument.get());
            highestArgumentIndex = Math.max(highestArgumentIndex, key.index());
        }
        Optional<DbusValue> path = decodedDbusValue(namedParameters.get("path")).filter(value -> !value.isEmpty());
        if (path.isEmpty() || !validOptionalDbusGuid(namedParameters)) {
            return Optional.empty();
        }
        DbusValue executablePath = path.get();
        for (int argumentIndex = 1; argumentIndex <= highestArgumentIndex; argumentIndex++) {
            if (!arguments.containsKey(argumentIndex)) {
                return Optional.empty();
            }
        }
        if (!arguments.isEmpty()) {
            arguments.putIfAbsent(0, executablePath);
        }
        return Optional.of(new UnixExecAddress(executablePath, arguments));
    }

    private static Optional<UnixExecArgumentKey> unixExecArgumentKey(String parameter) {
        int start = "argv".length();
        if (start < parameter.length() && parameter.charAt(start) == '=') {
            return Optional.of(new UnixExecArgumentKey(0, start + 1));
        }
        int cursor = skipNumericWhitespace(parameter, start, C_NUMERIC_WHITESPACE);
        boolean negative = false;
        if (cursor < parameter.length() && (parameter.charAt(cursor) == '+' || parameter.charAt(cursor) == '-')) {
            negative = parameter.charAt(cursor) == '-';
            cursor++;
        }
        int digitsStart = cursor;
        while (cursor < parameter.length() && isAsciiDigit(parameter.charAt(cursor))) {
            cursor++;
        }
        if (cursor == digitsStart || cursor >= parameter.length() || parameter.charAt(cursor) != '=') {
            return Optional.empty();
        }
        BigInteger magnitude = new BigInteger(parameter.substring(digitsStart, cursor));
        if (magnitude.compareTo(DBUS_ULONG_MAX) > 0) {
            return Optional.empty();
        }
        BigInteger parsed = negative && magnitude.signum() != 0 ? DBUS_ULONG_MODULUS.subtract(magnitude) : magnitude;
        // Linux amd64 and arm64 use LP64: strtoul() returns a 64-bit unsigned long, but systemd's
        // parse_exec_address() assigns it to 32-bit C unsigned before checking and selecting the slot.
        BigInteger resolvedSlot = parsed.mod(DBUS_UINT_MODULUS);
        return resolvedSlot.compareTo(BigInteger.valueOf(DBUS_UNIXEXEC_MAX_ARGUMENT_INDEX)) <= 0
                ? Optional.of(new UnixExecArgumentKey(resolvedSlot.intValue(), cursor + 1))
                : Optional.empty();
    }

    private static boolean validMachineAddress(Map<String, String> parameters) {
        if (!validOptionalDbusGuid(parameters)) {
            return false;
        }
        boolean hasMachine = parameters.containsKey("machine");
        boolean hasPid = parameters.containsKey("pid");
        if (hasMachine == hasPid) {
            return false;
        }
        return hasMachine ? validMachineName(parameters.get("machine")) : validMachinePid(parameters.get("pid"));
    }

    private static boolean validMachineName(String encodedMachine) {
        return decodedAsciiDbusValue(encodedMachine)
                .filter(machine -> machine.length() <= DBUS_MACHINE_NAME_MAX_BYTES)
                .filter(SystemdSessionBusResolver::isSystemdMachineName)
                .isPresent();
    }

    private static boolean isSystemdMachineName(String machine) {
        if (machine.equals(".host")) {
            return true;
        }
        boolean dot = true;
        boolean hyphen = true;
        for (int index = 0; index < machine.length(); index++) {
            char character = machine.charAt(index);
            if (character == '.') {
                if (dot || hyphen) {
                    return false;
                }
                dot = true;
                hyphen = false;
            } else if (character == '-') {
                if (dot) {
                    return false;
                }
                dot = false;
                hyphen = true;
            } else if (isAsciiLetter(character) || isAsciiDigit(character)) {
                dot = false;
                hyphen = false;
            } else {
                return false;
            }
        }
        return !dot && !hyphen;
    }

    private static boolean validOptionalDbusGuid(Map<String, String> parameters) {
        String encodedGuid = parameters.get("guid");
        if (encodedGuid == null) {
            return true;
        }
        return decodedAsciiDbusValue(encodedGuid)
                .filter(SystemdSessionBusResolver::isDbusGuid)
                .isPresent();
    }

    private static boolean isDbusGuid(String guid) {
        if (guid.length() == DBUS_GUID_HEX_LENGTH) {
            return guid.chars().allMatch(character -> isAsciiHexDigit((char) character));
        }
        if (guid.length() != DBUS_GUID_UUID_LENGTH) {
            return false;
        }
        for (int index = 0; index < guid.length(); index++) {
            char character = guid.charAt(index);
            if (DBUS_GUID_UUID_DASH_INDEXES.contains(index) ? character != '-' : !isAsciiHexDigit(character)) {
                return false;
            }
        }
        return true;
    }

    private static boolean validTcpFamily(String encodedFamily) {
        if (encodedFamily == null) {
            return true;
        }
        return decodedAsciiDbusValue(encodedFamily)
                .filter(value -> value.equals("ipv4") || value.equals("ipv6"))
                .isPresent();
    }

    private static boolean validMachinePid(String encodedPid) {
        return decodedAsciiDbusValue(encodedPid)
                .flatMap(SystemdSessionBusResolver::systemdUnsignedLong)
                .filter(pid -> pid.signum() > 0)
                .filter(pid -> pid.compareTo(DBUS_PID_MAX) <= 0)
                .isPresent();
    }

    private static Optional<String> decodedAsciiDbusValue(String value) {
        return decodedDbusValue(value).flatMap(DbusValue::asciiText);
    }

    private static Optional<DbusValue> decodedDbusValue(String value) {
        if (value == null) {
            return Optional.empty();
        }
        List<Integer> decoded = new ArrayList<>(value.length());
        int index = 0;
        while (index < value.length()) {
            int current = value.codePointAt(index);
            if (current == '%') {
                if (index + 2 >= value.length()) {
                    return Optional.empty();
                }
                int high = asciiDigitValue(value.charAt(index + 1));
                int low = asciiDigitValue(value.charAt(index + 2));
                if (high < 0 || high > 15 || low < 0 || low > 15) {
                    return Optional.empty();
                }
                current = high * 16 + low;
                index += 3;
            } else {
                if (current == 0) {
                    break;
                }
                if (Character.isSurrogate(value.charAt(index)) && !Character.isSupplementaryCodePoint(current)) {
                    return Optional.empty();
                }
                byte[] rawBytes = new String(Character.toChars(current)).getBytes(StandardCharsets.UTF_8);
                for (byte rawByte : rawBytes) {
                    decoded.add(Byte.toUnsignedInt(rawByte));
                }
                index += Character.charCount(current);
                continue;
            }
            decoded.add(current);
        }
        int cStringLength = decoded.indexOf(0);
        List<Integer> cStringBytes = cStringLength >= 0 ? decoded.subList(0, cStringLength) : decoded;
        return Optional.of(new DbusValue(cStringBytes));
    }

    private static boolean validNonEmptyDecodedDbusValue(String encodedValue) {
        return validDecodedDbusByteLength(encodedValue, Integer.MAX_VALUE);
    }

    private static boolean validDecodedDbusByteLength(String encodedValue, int maximumBytes) {
        return decodedDbusValue(encodedValue)
                .filter(value -> !value.isEmpty())
                .filter(value -> value.byteLength() <= maximumBytes)
                .isPresent();
    }

    private static Optional<BigInteger> systemdUnsignedLong(String value) {
        int start = skipNumericWhitespace(value, 0, SYSTEMD_NUMERIC_WHITESPACE);
        if (start >= value.length()) {
            return Optional.empty();
        }
        boolean hasSign = value.charAt(start) == '+' || value.charAt(start) == '-';
        boolean negative = hasSign && value.charAt(start) == '-';
        int digitsStart = hasSign ? start + 1 : start;
        if (digitsStart >= value.length()) {
            return Optional.empty();
        }

        int base = 10;
        int prefixLength = 0;
        if (!hasSign && startsWithIgnoreCase(value, digitsStart, "0b")) {
            base = 2;
            prefixLength = 2;
        } else if (!hasSign && startsWithIgnoreCase(value, digitsStart, "0o")) {
            base = 8;
            prefixLength = 2;
        } else if (startsWithIgnoreCase(value, digitsStart, "0x")) {
            base = 16;
            prefixLength = 2;
        } else if (value.charAt(digitsStart) == '0' && digitsStart + 1 < value.length()) {
            base = 8;
        }

        int numberStart = digitsStart + prefixLength;
        if (numberStart >= value.length()) {
            return Optional.empty();
        }
        for (int index = numberStart; index < value.length(); index++) {
            if (asciiDigitValue(value.charAt(index)) < 0 || asciiDigitValue(value.charAt(index)) >= base) {
                return Optional.empty();
            }
        }
        BigInteger parsed = new BigInteger(value.substring(numberStart), base);
        if (parsed.compareTo(DBUS_ULONG_MAX) > 0 || negative && parsed.signum() != 0) {
            return Optional.empty();
        }
        return Optional.of(parsed);
    }

    private static int skipNumericWhitespace(String value, int start, String whitespace) {
        int index = start;
        while (index < value.length() && whitespace.indexOf(value.charAt(index)) >= 0) {
            index++;
        }
        return index;
    }

    private static boolean startsWithIgnoreCase(String value, int start, String prefix) {
        return value.regionMatches(true, start, prefix, 0, prefix.length());
    }

    private static int asciiDigitValue(char character) {
        if (character >= '0' && character <= '9') {
            return character - '0';
        }
        if (character >= 'a' && character <= 'f') {
            return character - 'a' + 10;
        }
        if (character >= 'A' && character <= 'F') {
            return character - 'A' + 10;
        }
        return -1;
    }

    private static boolean isAsciiLetter(int character) {
        return (character >= 'a' && character <= 'z') || (character >= 'A' && character <= 'Z');
    }

    private static boolean isAsciiDigit(int character) {
        return character >= '0' && character <= '9';
    }

    private static boolean isDbusAddressUnescapedByte(int character) {
        return isAsciiLetter(character)
                || isAsciiDigit(character)
                || character == '-'
                || character == '_'
                || character == '/'
                || character == '*'
                || character == '.';
    }

    private static boolean isAsciiHexDigit(char character) {
        return isAsciiDigit(character)
                || (character >= 'a' && character <= 'f')
                || (character >= 'A' && character <= 'F');
    }

    record DbusValue(List<Integer> bytes) {
        DbusValue {
            bytes = List.copyOf(bytes);
        }

        int byteLength() {
            return bytes.size();
        }

        boolean isEmpty() {
            return bytes.isEmpty();
        }

        Optional<String> asciiText() {
            StringBuilder text = new StringBuilder(bytes.size());
            for (int value : bytes) {
                if (value > 0x7F) {
                    return Optional.empty();
                }
                text.append((char) value);
            }
            return Optional.of(text.toString());
        }
    }

    record UnixExecAddress(DbusValue path, Map<Integer, DbusValue> arguments) {
        UnixExecAddress {
            arguments = Map.copyOf(arguments);
        }
    }

    private record UnixExecArgumentKey(int index, int valueStart) {}
}
