package ch.fmartin.symphony.trello.setup;

import ch.fmartin.symphony.trello.setup.TrelloBoardSetup.GitHubIntegration;
import java.io.IOException;
import java.util.Locale;
import java.util.function.Supplier;

final class GitHubConfigurator {
    private final CommandRunner commands;
    private final Supplier<String> osName;

    GitHubConfigurator(CommandRunner commands) {
        this(commands, () -> System.getProperty("os.name", ""));
    }

    GitHubConfigurator(CommandRunner commands, Supplier<String> osName) {
        this.commands = commands;
        this.osName = osName;
    }

    GitHubIntegration resolve(LocalSetup.Options options, Prerequisites prerequisites, Terminal terminal)
            throws IOException {
        terminal.info("");
        terminal.info("GitHub integration");
        if (options.githubMode().filter(enabled -> !enabled).isPresent()) {
            terminal.info("  OK  GitHub integration skipped");
            printGithubLaterCommands(terminal);
            return GitHubIntegration.DISABLED;
        }
        if (options.githubMode().filter(Boolean::booleanValue).isPresent()) {
            requireNonInteractiveGithubPrerequisites(options, prerequisites);
            return configure(options, prerequisites, terminal);
        }
        if (prerequisites.githubAuth().available()) {
            terminal.info("  OK  GitHub CLI authenticated");
            terminal.info("  OK  GitHub integration configured");
            return GitHubIntegration.ENABLED;
        }
        if (options.nonInteractive()) {
            terminal.info("  OK  GitHub integration skipped");
            return GitHubIntegration.DISABLED;
        }
        String prompt = prerequisites.githubCli().available()
                ? "GitHub CLI is installed but not logged in.\nAllow Symphony to create pull requests on GitHub? [y/N] "
                : "GitHub CLI is not installed.\nAllow Symphony to create pull requests on GitHub? [y/N] ";
        String answer = terminal.readLine(prompt);
        boolean enabled =
                answer != null && answer.trim().toLowerCase(Locale.ROOT).startsWith("y");
        if (!enabled) {
            terminal.info("");
            terminal.info("OK. To add GitHub later, rerun:");
            printGithubLaterCommands(terminal);
            return GitHubIntegration.DISABLED;
        }
        return configure(options, prerequisites, terminal);
    }

    private static void requireNonInteractiveGithubPrerequisites(
            LocalSetup.Options options, Prerequisites prerequisites) {
        if (options.nonInteractive() && !prerequisites.githubCli().available()) {
            throw new TrelloBoardSetupException(
                    "setup_github_cli_required", "Install the GitHub CLI `gh`, then rerun setup-local --github.");
        }
        if (options.nonInteractive() && !prerequisites.githubAuth().available()) {
            throw new TrelloBoardSetupException(
                    "setup_github_auth_required",
                    "GitHub auth is required for --github in non-interactive setup. Run `gh auth login`, then rerun setup-local --github.");
        }
    }

    private GitHubIntegration configure(LocalSetup.Options options, Prerequisites prerequisites, Terminal terminal)
            throws IOException {
        if (!prerequisites.githubCli().available()) {
            installGithubCli(options, terminal);
        }
        if (!commands.available("gh", "--version").available()) {
            throw new TrelloBoardSetupException(
                    "setup_github_cli_required", "GitHub CLI installation did not make `gh` available on PATH.");
        }
        if (!commands.available("gh", "auth", "status").available()) {
            terminal.info("");
            terminal.info("Starting GitHub login:");
            terminal.info("  gh auth login");
            CommandResult login = commands.runInteractive("gh", "auth", "login");
            if (!login.success() || !commands.available("gh", "auth", "status").available()) {
                throw new TrelloBoardSetupException(
                        "setup_github_auth_required",
                        "GitHub login did not finish successfully. Run `gh auth login`, then rerun setup-local configure-github.");
            }
        }
        CommandResult user = commands.run("gh", "api", "user", "--jq", ".login // \"\"");
        String login = user.success() && !user.output().isBlank()
                ? " as " + user.output().strip()
                : "";
        terminal.info("  OK  GitHub CLI authenticated" + login);
        terminal.info("  OK  GitHub integration configured");
        return GitHubIntegration.ENABLED;
    }

    private void installGithubCli(LocalSetup.Options options, Terminal terminal) throws IOException {
        if (options.nonInteractive()) {
            throw new TrelloBoardSetupException(
                    "setup_github_cli_required", "Install GitHub CLI, then rerun setup-local configure-github.");
        }
        String command = githubCliInstallCommand();
        if (blank(command)) {
            throw new TrelloBoardSetupException(
                    "setup_github_cli_required",
                    "GitHub CLI is missing and no supported package-manager install command was found.");
        }
        terminal.info("");
        terminal.info("GitHub CLI is missing.");
        terminal.info("Proposed install command:");
        terminal.info("  " + command);
        if (!PromptSupport.yes(terminal, "Run this command now? [y/N] ")) {
            throw new TrelloBoardSetupException("setup_github_cli_declined", "GitHub CLI installation was declined.");
        }
        CommandResult result = runPackageInstallCommand(command);
        if (!result.success()) {
            throw new TrelloBoardSetupException(
                    "setup_github_cli_install_failed", "GitHub CLI installation failed: " + result.output());
        }
    }

    private CommandResult runPackageInstallCommand(String command) {
        if (isWindows()) {
            return commands.runInteractive("cmd", "/c", command);
        }
        return commands.runInteractive("bash", "-lc", command);
    }

    private String githubCliInstallCommand() {
        if (isWindows()) {
            return commands.available("winget", "--version").available()
                    ? "winget install --id GitHub.cli --source winget"
                    : "";
        }
        String os = normalizedOsName();
        if (os.contains("mac") && commands.available("brew", "--version").available()) {
            return "brew install gh";
        }
        if (commands.available("apt-get", "--version").available()) {
            return privilegedAptCommand();
        }
        if (commands.available("dnf", "--version").available()) {
            return privilegedPackageCommand("dnf install -y gh");
        }
        if (commands.available("yum", "--version").available()) {
            return privilegedPackageCommand("yum install -y gh");
        }
        if (commands.available("pacman", "--version").available()) {
            return privilegedPackageCommand("pacman -S --needed github-cli");
        }
        if (commands.available("zypper", "--version").available()) {
            return privilegedPackageCommand("zypper install -y gh");
        }
        return "";
    }

    private String privilegedPackageCommand(String command) {
        String prefix = privilegePrefix();
        return prefix == null ? "" : prefix + command;
    }

    private String privilegedAptCommand() {
        String prefix = privilegePrefix();
        return prefix == null ? "" : prefix + "apt-get update && " + prefix + "apt-get install -y gh";
    }

    private String privilegePrefix() {
        if (isRootUser()) {
            return "";
        }
        if (commands.available("sudo", "--version").available()) {
            return "sudo ";
        }
        if (commands.available("doas", "--version").available()) {
            return "doas ";
        }
        return null;
    }

    private boolean isRootUser() {
        CommandResult id = commands.run("id", "-u");
        if (id.success()) {
            return "0".equals(id.output().strip());
        }
        return "root".equals(System.getProperty("user.name", ""));
    }

    private boolean isWindows() {
        return normalizedOsName().contains("win");
    }

    private String normalizedOsName() {
        String value = osName.get();
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private static void printGithubLaterCommands(Terminal terminal) {
        terminal.info("  symphony-trello setup-local configure-github");
        terminal.info("  curl -fsSL https://symphony-trello.fmartin.ch/install.sh | bash");
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
