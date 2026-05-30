package ch.fmartin.symphony.trello.setup;

import java.util.Optional;

final class PrerequisiteChecker {
    private final CommandRunner commands;

    PrerequisiteChecker(CommandRunner commands) {
        this.commands = commands;
    }

    Prerequisites check() {
        ToolStatus git = commands.available("git", "--version");
        ToolStatus java = javaStatus();
        ToolStatus codex = commands.available("codex", "--version");
        ToolStatus codexAuth =
                codex.available() ? commands.available("codex", "login", "status") : ToolStatus.unavailable();
        ToolStatus githubCli = commands.available("gh", "--version");
        ToolStatus githubAuth =
                githubCli.available() ? commands.available("gh", "auth", "status") : ToolStatus.unavailable();
        return new Prerequisites(git, java, codex, codexAuth, githubCli, githubAuth);
    }

    private ToolStatus javaStatus() {
        CommandResult java = commands.run("java", "-version");
        CommandResult javac = commands.run("javac", "-version");
        if (!java.success() || !javac.success()) {
            return ToolStatus.unavailable();
        }
        return javaMajor(java.output()).filter(version -> version >= 25).isPresent()
                        && javaMajor(javac.output())
                                .filter(version -> version >= 25)
                                .isPresent()
                ? ToolStatus.found()
                : ToolStatus.unavailable();
    }

    static Optional<Integer> javaMajor(String output) {
        return output.lines()
                .map(String::stripLeading)
                .filter(PrerequisiteChecker::startsWithJavaCommand)
                .map(PrerequisiteChecker::firstInteger)
                .flatMap(Optional::stream)
                .findFirst();
    }

    private static boolean startsWithJavaCommand(String line) {
        return startsWithWord(line, "openjdk") || startsWithWord(line, "java") || startsWithWord(line, "javac");
    }

    private static boolean startsWithWord(String line, String word) {
        return line.equals(word) || line.startsWith(word + " ") || line.startsWith(word + "\t");
    }

    private static Optional<Integer> firstInteger(String line) {
        for (int index = 0; index < line.length(); index++) {
            if (Character.isDigit(line.charAt(index))) {
                return Optional.of(parseIntegerPrefix(line, index));
            }
        }
        return Optional.empty();
    }

    private static int parseIntegerPrefix(String line, int start) {
        int end = start + 1;
        while (end < line.length() && Character.isDigit(line.charAt(end))) {
            end++;
        }
        return Integer.parseInt(line.substring(start, end));
    }
}
