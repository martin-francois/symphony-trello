package ch.fmartin.symphony.trello.setup;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
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
            if (isDbusOptionallyEscapedByte(unsigned)) {
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
        if (value == null || value.isBlank()) {
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
            case "unixexec" -> validUnixExecAddress(parameterText);
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
        Optional<String> decodedId = decodedAsciiDbusValue(encodedId);
        if (decodedId.isEmpty()) {
            return false;
        }
        String idValue = decodedId.orElseThrow();
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

    private static boolean validUnixExecAddress(String parameterText) {
        Map<String, String> namedParameters = new HashMap<>();
        Set<Integer> argumentIndexes = new HashSet<>();
        int highestArgumentIndex = 0;
        for (String parameter : parameterText.split(",", -1)) {
            if (parameter.startsWith("guid=") || parameter.startsWith("path=")) {
                int separator = parameter.indexOf('=');
                String key = parameter.substring(0, separator);
                if (namedParameters.putIfAbsent(key, parameter.substring(separator + 1)) != null) {
                    return false;
                }
                continue;
            }
            if (!parameter.startsWith("argv")) {
                continue;
            }
            Optional<Integer> argumentIndex = unixExecArgumentIndex(parameter);
            if (argumentIndex.isEmpty()
                    || decodedDbusValue(parameter.substring(parameter.indexOf('=') + 1))
                            .isEmpty()) {
                return false;
            }
            argumentIndexes.add(argumentIndex.orElseThrow());
            highestArgumentIndex = Math.max(highestArgumentIndex, argumentIndex.orElseThrow());
        }
        if (!validNonEmptyDecodedDbusValue(namedParameters.get("path")) || !validOptionalDbusGuid(namedParameters)) {
            return false;
        }
        for (int argumentIndex = 1; argumentIndex <= highestArgumentIndex; argumentIndex++) {
            if (!argumentIndexes.contains(argumentIndex)) {
                return false;
            }
        }
        return true;
    }

    private static Optional<Integer> unixExecArgumentIndex(String parameter) {
        int indexEnd = "argv".length();
        while (indexEnd < parameter.length() && isAsciiDigit(parameter.charAt(indexEnd))) {
            indexEnd++;
        }
        if (indexEnd >= parameter.length() || parameter.charAt(indexEnd) != '=') {
            return Optional.empty();
        }
        String index = parameter.substring("argv".length(), indexEnd);
        try {
            int parsedIndex = index.isEmpty() ? 0 : Integer.parseInt(index);
            return parsedIndex <= DBUS_UNIXEXEC_MAX_ARGUMENT_INDEX ? Optional.of(parsedIndex) : Optional.empty();
        } catch (NumberFormatException invalidIndex) {
            return Optional.empty();
        }
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
        Optional<String> pid = decodedAsciiDbusValue(encodedPid);
        if (pid.isEmpty() || numericValue(pid.orElseThrow()).isEmpty()) {
            return false;
        }
        try {
            return Integer.parseInt(pid.orElseThrow()) > 0;
        } catch (NumberFormatException invalidPid) {
            return false;
        }
    }

    private static Optional<String> decodedAsciiDbusValue(String value) {
        return decodedDbusValue(value).filter(decoded -> decoded.chars().allMatch(character -> character <= 0x7F));
    }

    private static Optional<String> decodedDbusValue(String value) {
        if (value == null) {
            return Optional.empty();
        }
        StringBuilder decoded = new StringBuilder(value.length());
        int index = 0;
        while (index < value.length()) {
            int current = value.charAt(index);
            if (current == '%') {
                if (index + 2 >= value.length()) {
                    return Optional.empty();
                }
                int high = Character.digit(value.charAt(index + 1), 16);
                int low = Character.digit(value.charAt(index + 2), 16);
                if (high < 0 || low < 0) {
                    return Optional.empty();
                }
                current = high * 16 + low;
                index += 3;
            } else {
                if (!isDbusOptionallyEscapedByte(current)) {
                    return Optional.empty();
                }
                index++;
            }
            decoded.append((char) current);
        }
        return Optional.of(decoded.toString());
    }

    private static boolean validNonEmptyDecodedDbusValue(String encodedValue) {
        return validDecodedDbusByteLength(encodedValue, Integer.MAX_VALUE);
    }

    private static boolean validDecodedDbusByteLength(String encodedValue, int maximumBytes) {
        return decodedDbusValue(encodedValue)
                .filter(decoded -> !decoded.isEmpty())
                .filter(decoded -> decoded.length() <= maximumBytes)
                .filter(decoded -> decoded.indexOf('\0') < 0)
                .isPresent();
    }

    private static boolean isAsciiLetter(int character) {
        return (character >= 'a' && character <= 'z') || (character >= 'A' && character <= 'Z');
    }

    private static boolean isAsciiDigit(int character) {
        return character >= '0' && character <= '9';
    }

    private static boolean isDbusOptionallyEscapedByte(int character) {
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
}
