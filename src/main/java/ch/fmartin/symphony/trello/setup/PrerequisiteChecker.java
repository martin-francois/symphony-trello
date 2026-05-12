package ch.fmartin.symphony.trello.setup;

import java.util.Optional;
import java.util.regex.Pattern;

final class PrerequisiteChecker {
    private static final Pattern JAVA_VERSION = Pattern.compile(
            "(?m)^\\s*(?:openjdk|java|javac)\\b[^\\r\\n]*?(?:version\\s+\"?)?(?<version>[0-9]+)(?:\\.[0-9]+)?");

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
        var matcher = JAVA_VERSION.matcher(output);
        if (!matcher.find()) {
            return Optional.empty();
        }
        return Optional.of(Integer.parseInt(matcher.group("version")));
    }
}
