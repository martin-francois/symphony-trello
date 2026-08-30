package ch.fmartin.symphony.trello.setup;

import static ch.fmartin.symphony.trello.setup.GitHubIssueTarget.REPOSITORY;
import static ch.fmartin.symphony.trello.setup.SetupEnvironmentVariables.CONFIG_DIR_ENV;
import static ch.fmartin.symphony.trello.setup.SetupEnvironmentVariables.STATE_HOME_ENV;
import static ch.fmartin.symphony.trello.setup.SetupEnvironmentVariables.SYMPHONY_HOME_ENV;
import static ch.fmartin.symphony.trello.setup.SetupEnvironmentVariables.WORKSPACE_ROOT_ENV;

import com.google.common.base.Splitter;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

final class InstallerLayoutFeedback {
    static final String ENVIRONMENT_NAME = "SYMPHONY_TRELLO_LAYOUT_FEEDBACK";

    private static final String ISSUE_TITLE = "feat: recognize a reusable custom installer path layout";
    private static final Set<String> REPORTABLE_ENVIRONMENT_NAMES =
            Set.of(SYMPHONY_HOME_ENV, CONFIG_DIR_ENV, WORKSPACE_ROOT_ENV, STATE_HOME_ENV);

    private final Map<String, String> environment;
    private final CommandRunner commands;
    private final Function<String, String> properties;

    InstallerLayoutFeedback(Map<String, String> environment, CommandRunner commands) {
        this(environment, commands, System::getProperty);
    }

    InstallerLayoutFeedback(
            Map<String, String> environment, CommandRunner commands, Function<String, String> properties) {
        this.environment = Map.copyOf(environment);
        this.commands = commands;
        this.properties = properties;
    }

    void offer(LocalWorkerPaths paths, Terminal terminal) {
        String configuredNames = environment.get(ENVIRONMENT_NAME);
        if (configuredNames == null || configuredNames.isBlank()) {
            return;
        }
        try {
            terminal.info("");
            terminal.info("Custom installer layout");
            if (!PromptSupport.yes(
                    terminal,
                    "Does this layout follow a reusable OS, deployment, package-manager, or organization standard? [y/N] ")) {
                terminal.info("  OK  Layout feedback skipped");
                return;
            }
            String issueBody = issueBody(paths, configuredNames);
            terminal.info("");
            terminal.info("Proposed GitHub feature request:");
            terminal.info("Title: " + ISSUE_TITLE);
            terminal.info(issueBody);
            String browserUrl = browserUrl(issueBody);
            try {
                createIssueOrPrintLink(terminal, issueBody, browserUrl);
            } catch (IOException | RuntimeException e) {
                terminal.warn("  NOTE  GitHub CLI did not create the feature request.");
                printBrowserLink(terminal, browserUrl);
            }
        } catch (IOException | RuntimeException e) {
            terminal.warn("  NOTE  Layout feedback was skipped.");
        }
    }

    private void createIssueOrPrintLink(Terminal terminal, String issueBody, String browserUrl) throws IOException {
        if (!commands.available("gh", "auth", "status").available()) {
            printBrowserLink(terminal, browserUrl);
            return;
        }
        if (!PromptSupport.yes(terminal, "Create this feature request on GitHub now? [y/N] ")) {
            printBrowserLink(terminal, browserUrl);
            return;
        }
        CommandResult result = commands.run(
                "gh",
                "issue",
                "create",
                "--repo",
                REPOSITORY,
                "--title",
                ISSUE_TITLE,
                "--body",
                issueBody,
                "--label",
                "enhancement");
        if (result.success()) {
            terminal.info("  OK  Feature request created: " + result.output().strip());
        } else {
            terminal.warn("  NOTE  GitHub CLI did not create the feature request.");
            printBrowserLink(terminal, browserUrl);
        }
    }

    String issueBody(LocalWorkerPaths paths, String configuredNames) {
        return String.join(
                "\n",
                List.of(
                        "## Problem",
                        "",
                        "This environment needs explicit installer paths even though the layout follows a reusable convention.",
                        "",
                        "## Desired outcome",
                        "",
                        "Detect this convention automatically so future installations can keep the one-line installer flag-free.",
                        "",
                        "## Sanitized setup",
                        "",
                        "- OS: %s %s (%s)".formatted(property("os.name"), property("os.version"), property("os.arch")),
                        "- Installer: one-line guided installer",
                        "- Wrapper shell: " + property("symphony.trello.shell"),
                        "- Explicit variables: " + reportableEnvironmentNames(configuredNames),
                        "- Config: " + sanitizedPath(paths.configDir()),
                        "- Workspaces: " + sanitizedPath(paths.workspaceRoot()),
                        "- State/logs: " + sanitizedPath(paths.stateHome()),
                        "- Config and state share a parent: "
                                + (sameParent(paths.configDir(), paths.stateHome()) ? "yes" : "no"),
                        "",
                        "Paths outside the user home are intentionally redacted. No credentials, usernames, hostnames, or account details are included."));
    }

    private String browserUrl(String issueBody) {
        return "https://github.com/"
                + REPOSITORY
                + "/issues/new?template=feature_request.yml&title="
                + encode(ISSUE_TITLE)
                + "&problem="
                + encode("The installer needs explicit paths for a reusable environment convention.")
                + "&area="
                + encode("Local installer or onboarding")
                + "&proposal="
                + encode("Detect this layout automatically and keep the standard installation flag-free.")
                + "&compatibility-impact="
                + encode("No visible impact")
                + "&additional-context="
                + encode(issueBody);
    }

    private void printBrowserLink(Terminal terminal, String browserUrl) {
        terminal.info("Open this prefilled feature request when convenient:");
        terminal.info("  " + browserUrl);
    }

    private String reportableEnvironmentNames(String configuredNames) {
        // Preserve installer option order so the reviewed issue matches the setup invocation.
        Set<String> names = new LinkedHashSet<>();
        for (String candidate : Splitter.on(',').split(configuredNames)) {
            String name = candidate.strip();
            if (REPORTABLE_ENVIRONMENT_NAMES.contains(name)) {
                names.add(name);
            }
        }
        return names.isEmpty() ? "none" : String.join(", ", names);
    }

    private String sanitizedPath(Path path) {
        String homeValue = environment.getOrDefault("HOME", environment.get("USERPROFILE"));
        if (homeValue == null || homeValue.isBlank()) {
            return "<outside-home>";
        }
        Path normalizedPath = path.toAbsolutePath().normalize();
        Path normalizedHome = Path.of(homeValue).toAbsolutePath().normalize();
        if (!normalizedPath.startsWith(normalizedHome)) {
            return "<outside-home>";
        }
        Path relative = normalizedHome.relativize(normalizedPath);
        return relative.getNameCount() == 0
                ? "$HOME"
                : "$HOME/" + relative.toString().replace('\\', '/');
    }

    private static boolean sameParent(Path first, Path second) {
        Path firstParent = first.toAbsolutePath().normalize().getParent();
        Path secondParent = second.toAbsolutePath().normalize().getParent();
        return firstParent != null && firstParent.equals(secondParent);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private String property(String name) {
        String value = properties.apply(name);
        return value == null || value.isBlank() ? "unknown" : value.strip();
    }
}
