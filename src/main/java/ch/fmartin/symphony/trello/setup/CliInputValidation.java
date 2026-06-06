package ch.fmartin.symphony.trello.setup;

import com.google.common.base.CharMatcher;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

final class CliInputValidation {
    private static final CharMatcher CONTROL_CHARACTERS =
            CharMatcher.javaIsoControl().precomputed();
    private static final Set<String> STANDARD_STREAM_DEVICE_PATHS = Set.of(
            "/dev/stdin",
            "/dev/stdout",
            "/dev/stderr",
            "/proc/self/fd/0",
            "/proc/self/fd/1",
            "/proc/self/fd/2",
            "/proc/thread-self/fd/0",
            "/proc/thread-self/fd/1",
            "/proc/thread-self/fd/2");
    private static final Pattern DEV_FD_STREAM = Pattern.compile("/dev/fd/[0-2]");
    private static final Pattern PROCESS_FD_STREAM = Pattern.compile("/proc/[1-9][0-9]*/fd/[0-2]");

    private CliInputValidation() {}

    static void rejectControlCharacters(String optionName, Optional<Path> value) {
        value.ifPresent(path -> rejectControlCharacters(optionName, path));
    }

    static void rejectBlankPath(String optionName, Optional<Path> value) {
        value.ifPresent(path -> rejectBlankPath(optionName, path));
    }

    static void rejectBlankPath(String optionName, Optional<Path> value, String message) {
        value.ifPresent(path -> rejectBlankPath(optionName, path, message));
    }

    static void rejectControlCharactersInText(String optionName, Optional<String> value) {
        value.ifPresent(text -> rejectControlCharacters(optionName, text));
    }

    static void rejectControlCharactersInTextValues(String optionName, List<String> values) {
        values.forEach(value -> rejectControlCharacters(optionName, value));
    }

    static void rejectWorkspaceIdReference(String optionName, String value) {
        if (value == null) {
            return;
        }
        if (looksLikeUrlOrPath(value)) {
            throw new TrelloBoardSetupException(
                    "setup_invalid_arguments", optionName + " must be a Trello Workspace id, not a URL or path.");
        }
    }

    static void rejectWorkspaceIdReference(String optionName, Optional<String> value) {
        value.ifPresent(text -> rejectWorkspaceIdReference(optionName, text));
    }

    static void rejectBlankText(String optionName, Optional<String> value) {
        rejectBlankText(optionName, value, optionName + " must not be blank.");
    }

    static void rejectBlankText(String optionName, String value) {
        rejectBlankText(optionName, value, optionName + " must not be blank.");
    }

    static void rejectBlankText(String optionName, String value, String message) {
        if (value != null && value.isBlank()) {
            throw new TrelloBoardSetupException("setup_invalid_arguments", message);
        }
    }

    static void rejectBlankText(String optionName, Optional<String> value, String message) {
        value.map(String::strip).filter(String::isBlank).ifPresent(ignored -> {
            throw new TrelloBoardSetupException("setup_invalid_arguments", message);
        });
    }

    static void rejectBlankTextValues(String optionName, List<String> values) {
        values.forEach(value -> rejectBlankText(optionName, value));
    }

    static void rejectControlCharacters(String optionName, String value) {
        if (value == null) {
            return;
        }
        if (CONTROL_CHARACTERS.matchesAnyOf(value)) {
            throw new TrelloBoardSetupException(
                    "setup_invalid_arguments", optionName + " must not contain control characters.");
        }
    }

    static void rejectControlCharacters(String optionName, Path value) {
        rejectControlCharacters(optionName, value.toString());
    }

    static String safeCliMessage(String message) {
        if (message == null || !CONTROL_CHARACTERS.matchesAnyOf(message)) {
            return message;
        }
        StringBuilder safe = new StringBuilder(message.length());
        message.codePoints().forEach(codePoint -> appendSafeCliCodePoint(safe, codePoint));
        return safe.toString();
    }

    private static void appendSafeCliCodePoint(StringBuilder safe, int codePoint) {
        switch (codePoint) {
            case '\b' -> safe.append("\\b");
            case '\t' -> safe.append("\\t");
            case '\n' -> safe.append("\\n");
            case '\f' -> safe.append("\\f");
            case '\r' -> safe.append("\\r");
            default -> {
                if (Character.isISOControl(codePoint)) {
                    safe.append("\\u");
                    String hex = Integer.toHexString(codePoint).toUpperCase();
                    safe.repeat("0", Math.max(0, 4 - hex.length())).append(hex);
                } else {
                    safe.appendCodePoint(codePoint);
                }
            }
        }
    }

    static void rejectBlankPath(String optionName, Path value) {
        rejectBlankPath(optionName, value, optionName + " must be a file path.");
    }

    static void rejectBlankPath(String optionName, Path value, String message) {
        if (value.toString().isBlank()) {
            throw new TrelloBoardSetupException("setup_invalid_arguments", message);
        }
    }

    static void rejectBlankPaths(String optionName, List<Path> values, String message) {
        values.forEach(path -> rejectBlankPath(optionName, path, message));
    }

    static void rejectRelativePathsExcept(List<Path> values, Predicate<Path> allowedRelativePath, String message) {
        if (values.stream().anyMatch(path -> !path.isAbsolute() && !allowedRelativePath.test(path))) {
            throw new TrelloBoardSetupException("setup_invalid_arguments", message);
        }
    }

    static void rejectRelativePath(String optionName, Optional<Path> value, String message) {
        value.ifPresent(path -> rejectRelativePath(optionName, path, message));
    }

    static void rejectRelativePath(String optionName, Path value, String message) {
        if (!value.isAbsolute()) {
            throw new TrelloBoardSetupException("setup_invalid_arguments", message);
        }
    }

    static void rejectExistingNonDirectoryPath(String optionName, Path value) {
        if (Files.exists(value) && !Files.isDirectory(value)) {
            throw new TrelloBoardSetupException("setup_invalid_arguments", optionName + " must be a directory.");
        }
    }

    static void rejectExistingNonDirectoryPath(String optionName, Optional<Path> value) {
        value.ifPresent(path -> rejectExistingNonDirectoryPath(optionName, path));
    }

    static void rejectDirectoryPath(String optionName, Path value) {
        if (Files.isDirectory(value)) {
            throw new TrelloBoardSetupException("setup_invalid_arguments", optionName + " must be a file path.");
        }
    }

    static void rejectControlCharactersInPaths(String optionName, List<Path> values) {
        values.forEach(path -> rejectControlCharacters(optionName, path));
    }

    static void rejectDashOutputFile(Optional<Path> value) {
        if (value.map(Path::toString).filter("-"::equals).isPresent()) {
            throw new TrelloBoardSetupException(
                    "setup_invalid_arguments", "--output - is not supported. Omit --output to print to stdout.");
        }
    }

    static void rejectStandardStreamOutputFile(Optional<Path> value) {
        if (value.filter(CliInputValidation::isStandardStreamDevicePath).isPresent()) {
            throw new TrelloBoardSetupException(
                    "setup_invalid_arguments",
                    "--output standard stream paths are not supported. Omit --output to print diagnostics to stdout.");
        }
    }

    private static boolean isStandardStreamDevicePath(Path value) {
        return isStandardStreamDevicePath(value.normalize().toString())
                || isStandardStreamDevicePath(value.toAbsolutePath().normalize().toString())
                || symlinkExpandedPathIsStandardStreamDevicePath(value)
                || realPathIsStandardStreamDevicePath(value);
    }

    private static boolean symlinkExpandedPathIsStandardStreamDevicePath(Path value) {
        Set<Path> visitedLinks = new HashSet<>();
        Path absolute = value.toAbsolutePath().normalize();
        Path current = absolute.getRoot() == null ? Path.of("") : absolute.getRoot();
        for (Path element : absolute) {
            current = current.resolve(element).normalize();
            if (isStandardStreamDevicePath(current.toString())) {
                return true;
            }
            SymlinkExpansion expansion = expandSymlinks(current, visitedLinks);
            if (expansion.standardStreamDevicePath()) {
                return true;
            }
            current = expansion.path();
        }
        return false;
    }

    private static SymlinkExpansion expandSymlinks(Path value, Set<Path> visitedLinks) {
        Path current = value;
        while (Files.isSymbolicLink(current)) {
            Path normalizedCurrent = current.toAbsolutePath().normalize();
            if (isStandardStreamDevicePath(normalizedCurrent.toString())) {
                return new SymlinkExpansion(current, true);
            }
            if (!visitedLinks.add(normalizedCurrent)) {
                return new SymlinkExpansion(current, false);
            }
            try {
                Path target = Files.readSymbolicLink(current);
                Path parent = normalizedCurrent.getParent();
                Path resolvedTarget = target.isAbsolute() || parent == null ? target : parent.resolve(target);
                if (isStandardStreamDevicePath(target.normalize().toString())
                        || isStandardStreamDevicePath(resolvedTarget.normalize().toString())) {
                    return new SymlinkExpansion(resolvedTarget, true);
                }
                current = resolvedTarget;
            } catch (IOException ignored) {
                // If the link target cannot be read, the writer handles the filesystem failure.
                return new SymlinkExpansion(current, false);
            }
        }
        return new SymlinkExpansion(
                current, isStandardStreamDevicePath(current.normalize().toString()));
    }

    private record SymlinkExpansion(Path path, boolean standardStreamDevicePath) {}

    private static boolean realPathIsStandardStreamDevicePath(Path value) {
        try {
            return isStandardStreamDevicePath(value.toRealPath().toString());
        } catch (IOException ignored) {
            // New output files do not have a real path yet; the writer handles other filesystem failures.
            return false;
        }
    }

    private static boolean isStandardStreamDevicePath(String value) {
        return STANDARD_STREAM_DEVICE_PATHS.contains(value)
                || DEV_FD_STREAM.matcher(value).matches()
                || PROCESS_FD_STREAM.matcher(value).matches();
    }

    private static boolean looksLikeUrlOrPath(String value) {
        return value.contains("://") || value.contains("/") || value.contains("\\");
    }
}
