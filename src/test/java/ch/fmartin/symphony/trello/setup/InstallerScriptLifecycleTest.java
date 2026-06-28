package ch.fmartin.symphony.trello.setup;

import static ch.fmartin.symphony.trello.CliExitCodes.SETUP_FAILURE;
import static ch.fmartin.symphony.trello.setup.InstallerScriptFixture.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ch.fmartin.symphony.trello.setup.InstallerScriptFixture.ProcessResult;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class InstallerScriptLifecycleTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<LinkedHashMap<String, Object>> JSON_OBJECT = new TypeReference<>() {};

    @TempDir
    Path temporaryDirectory;

    @Test
    void posixInstallerLifecycleInstallsUpdatesStartsAndUninstallsWithTestDoubles() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        assumeTrue(commandExists("git"));
        assumeTrue(commandExists("script"));
        Path installScript = Path.of("install.sh").toAbsolutePath();
        Path uninstallScript = Path.of("uninstall.sh").toAbsolutePath();
        Path sourceRepository = createSourceRepository(temporaryDirectory);
        Path fakeBin = createFakeToolchain(temporaryDirectory);
        Path symphonyHome = temporaryDirectory.resolve("home");
        Path installPrefix = symphonyHome.resolve("app");
        Path configDirectory = symphonyHome.resolve("config");
        Path workspaceRoot = symphonyHome.resolve("workspaces");
        Path binDirectory = temporaryDirectory.resolve("bin");
        Path stateHome = symphonyHome.resolve("state");
        Path fakeLog = temporaryDirectory.resolve("fake-tools.log");
        Map<String, String> environment = Map.of(
                "PATH",
                fakeBin + System.getProperty("path.separator") + System.getenv("PATH"),
                "SYMPHONY_TRELLO_REPO_URL",
                sourceRepository.toUri().toString(),
                "SYMPHONY_TRELLO_REF",
                "main",
                "SYMPHONY_HOME",
                symphonyHome.toString(),
                "SYMPHONY_FAKE_LOG",
                fakeLog.toString(),
                "SYMPHONY_FAKE_SLOW_TERM",
                "true");

        // when
        ProcessResult install = runWithPseudoTerminal(
                environment,
                "n\napi-key\napi-token\nLifecycle Board\n",
                "bash " + shellQuote(installScript.toString()) + " --bin-dir " + shellQuote(binDirectory.toString()));
        Path installedCommand = binDirectory.resolve("symphony-trello");
        Path callerDirectory = temporaryDirectory.resolve("posix caller");
        Files.createDirectories(callerDirectory);
        List<ProcessResult> picocliHelpResults = new ArrayList<>();
        for (String[] command : installedPicocliHelpCommands()) {
            picocliHelpResults.add(run(environment, commandWithPrefix(installedCommand.toString(), command)));
        }
        ProcessResult directBoardSetup = run(
                environment,
                callerDirectory,
                installedCommand.toString(),
                "new-board",
                "--name",
                "Wrapper Dispatch Board",
                "--key",
                "key",
                "--token",
                "token",
                "--workflow",
                "relative workflow.md",
                "--workspace-root",
                "relative workspaces");
        ProcessResult unknownCommand = run(environment, installedCommand.toString(), "definitely-not-a-command");
        ProcessResult statusAfterInstall = run(environment, installedCommand.toString(), "status");
        ProcessResult noArgStart = run(environment, installedCommand.toString(), "start");
        Path isolatedConfigDirectory = temporaryDirectory.resolve("isolated-config");
        Path isolatedCallerDirectory = temporaryDirectory.resolve("isolated caller");
        Files.createDirectories(isolatedConfigDirectory);
        Files.createDirectories(isolatedCallerDirectory);
        Files.writeString(
                isolatedConfigDirectory.resolve(ConnectedBoardManifest.FILE_NAME),
                """
                {"boards":[{"boardId":"isolated-board","boardName":"Isolated Board","workflowPath":"%s","envPath":"%s"}]}
                """
                        .formatted(
                                configDirectory.resolve("WORKFLOW.lifecycle-board.md"),
                                configDirectory.resolve(".env")),
                StandardCharsets.UTF_8);
        Path isolatedConfigArgument = isolatedConfigDirectory.resolve(".");
        ProcessResult isolatedSetupLocalHelp = run(
                environment,
                isolatedCallerDirectory,
                installedCommand.toString(),
                "setup-local",
                "--config-dir",
                isolatedConfigArgument.toString(),
                "--help");
        ProcessResult isolatedSetupLocalCheck = run(
                environment,
                isolatedCallerDirectory,
                installedCommand.toString(),
                "setup-local",
                "check",
                "--config-dir",
                isolatedConfigArgument.toString());
        ProcessResult isolatedStatus = run(
                environment,
                isolatedCallerDirectory,
                installedCommand.toString(),
                "status",
                "--config-dir",
                isolatedConfigArgument.toString());
        ProcessResult isolatedDiagnostics = run(
                environment,
                isolatedCallerDirectory,
                installedCommand.toString(),
                "diagnostics",
                "--config-dir",
                isolatedConfigArgument.toString());
        Path environmentWorkspaceRoot = temporaryDirectory.resolve("environment workspaces");
        Path environmentStateHome = temporaryDirectory.resolve("environment state");
        Map<String, String> environmentPathOverrides = new LinkedHashMap<>(environment);
        environmentPathOverrides.put("SYMPHONY_TRELLO_WORKSPACE_ROOT", environmentWorkspaceRoot.toString());
        environmentPathOverrides.put("SYMPHONY_TRELLO_STATE_HOME", environmentStateHome.toString());
        ProcessResult isolatedStatusWithEnvironmentRoots = run(
                environmentPathOverrides,
                isolatedCallerDirectory,
                installedCommand.toString(),
                "status",
                "--config-dir",
                isolatedConfigArgument.toString());
        ProcessResult update = run(
                environment, "bash", installScript.toString(), "--no-onboard", "--bin-dir", binDirectory.toString());
        addSourceRepositoryCommit(sourceRepository, "UPGRADE_MARKER", "updated\n");
        ProcessResult secondUpdate = run(
                environment, "bash", installScript.toString(), "--no-onboard", "--bin-dir", binDirectory.toString());
        String markerContentAfterUpdate =
                Files.readString(installPrefix.resolve("UPGRADE_MARKER"), StandardCharsets.UTF_8);
        ProcessResult statusWhileRunning = run(environment, installedCommand.toString(), "status");
        Path pidFile = singleFile(stateHome, ".pid");
        long managedPid =
                Long.parseLong(Files.readString(pidFile, StandardCharsets.UTF_8).trim());
        ProcessResult stop = run(
                environment,
                installedCommand.toString(),
                "stop",
                "--workflow",
                configDirectory.resolve("WORKFLOW.lifecycle-board.md").toString());
        ProcessResult restart = run(environment, installedCommand.toString(), "start");
        Path restartedPidFile = singleFile(stateHome, ".pid");
        long restartedManagedPid = Long.parseLong(
                Files.readString(restartedPidFile, StandardCharsets.UTF_8).trim());
        ProcessResult uninstall =
                run(environment, "bash", uninstallScript.toString(), "--yes", "--bin-dir", binDirectory.toString());

        // then
        assertThat(install.exitCode()).isZero();
        assertThat(install.output())
                .contains(
                        "Codex CLI is installed but not logged in.",
                        "Can this machine open a browser for Codex login?",
                        "RUN  codex login --device-auth",
                        "Starting setup...",
                        "Command installed")
                .doesNotContain("Device auth");
        assertThat(picocliHelpResults).allSatisfy(result -> {
            assertThat(result.exitCode()).as(result.output()).isZero();
            assertThat(result.output()).containsAnyOf("Usage: symphony-trello", "symphony-trello test");
        });
        assertThat(directBoardSetup.exitCode()).as(directBoardSetup.output()).isZero();
        assertThat(unknownCommand.exitCode()).isEqualTo(SETUP_FAILURE);
        assertThat(unknownCommand.output())
                .contains("setup_failed code=setup_invalid_arguments")
                .contains("Unmatched argument: 'definitely-not-a-command'")
                .doesNotContain("at index", "from index");
        assertThat(statusAfterInstall.output())
                .contains("running WORKFLOW.lifecycle-board.md pid=")
                .doesNotContain("running WORKFLOW.lifecycle-board.md.");
        assertThat(noArgStart.output()).contains("already running", "WORKFLOW.lifecycle-board.md");
        assertThat(isolatedSetupLocalHelp.exitCode())
                .as(isolatedSetupLocalHelp.output())
                .isZero();
        assertThat(isolatedSetupLocalCheck.output())
                .doesNotContain("setup-local check does not support --workspace-root");
        assertThat(isolatedStatus.exitCode()).as(isolatedStatus.output()).isZero();
        assertThat(isolatedDiagnostics.exitCode())
                .as(isolatedDiagnostics.output())
                .isZero();
        assertThat(isolatedStatusWithEnvironmentRoots.exitCode())
                .as(isolatedStatusWithEnvironmentRoots.output())
                .isZero();
        assertThat(update.exitCode()).isZero();
        assertThat(secondUpdate.exitCode()).isZero();
        assertThat(markerContentAfterUpdate).isEqualTo("updated\n");
        assertThat(statusWhileRunning.output())
                .contains("running WORKFLOW.lifecycle-board.md pid=")
                .doesNotContain("running WORKFLOW.lifecycle-board.md.");
        assertThat(stop.exitCode()).isZero();
        assertThat(stop.output())
                .contains("Stopped WORKFLOW.lifecycle-board.md")
                .doesNotContain("Stopped WORKFLOW.lifecycle-board.md.");
        assertThat(processStopsWithin(managedPid, 5)).isTrue();
        assertThat(restart.exitCode()).isZero();
        assertThat(restart.output()).contains("Started Symphony for Trello");
        assertThat(uninstall.exitCode()).isZero();
        assertThat(uninstall.output()).contains("STOP", "REMOVE  " + binDirectory.resolve("symphony-trello"));
        assertThat(processStopsWithin(restartedManagedPid, 5)).isTrue();
        assertThat(installPrefix).doesNotExist();
        assertThat(binDirectory.resolve("symphony-trello")).doesNotExist();
        assertThat(configDirectory).exists();
        assertThat(configDirectory.resolve(".env"))
                .content(StandardCharsets.UTF_8)
                .contains("TRELLO_API_KEY=api-key");
        assertThat(configDirectory.resolve("WORKFLOW.lifecycle-board.md")).exists();
        assertThat(configDirectory.resolve(ConnectedBoardManifest.FILE_NAME))
                .content(StandardCharsets.UTF_8)
                .contains(configDirectory.resolve("WORKFLOW.lifecycle-board.md").toString());
        assertThat(workspaceRoot).exists();
        assertThat(stateHome).exists();
        ProcessResult cleanup = run(
                environment,
                "bash",
                uninstallScript.toString(),
                "--yes",
                "--yes-local-data",
                "--remove-config",
                "--remove-workspaces",
                "--remove-state",
                "--bin-dir",
                binDirectory.toString());
        assertThat(cleanup.exitCode()).isZero();
        assertThat(cleanup.output())
                .contains("REMOVE  " + configDirectory, "REMOVE  " + workspaceRoot, "REMOVE  " + stateHome);
        assertThat(configDirectory).doesNotExist();
        assertThat(workspaceRoot).doesNotExist();
        assertThat(stateHome).doesNotExist();
        assertThat(regularFilesUnder(binDirectory)).isEmpty();
        assertThat(fakeLog)
                .content()
                .contains(
                        "codex login --device-auth",
                        "setup-local key=api-key token=api-token board=Lifecycle Board",
                        "setup-cli",
                        "setup-cli cwd=" + callerDirectory,
                        "-Dsymphony.trello.config.dir=" + configDirectory,
                        "new-board --name Wrapper Dispatch Board",
                        "--workflow relative workflow.md",
                        "--workspace-root relative workspaces",
                        "dotenv=" + configDirectory.resolve(".env"),
                        "definitely-not-a-command",
                        "mvnw -q -f " + installPrefix.resolve("pom.xml") + " -DskipTests clean package",
                        "jar-start",
                        "app-present-before-exit",
                        "jar-stopped")
                .doesNotContain("new-board --workflow");
        List<String> isolatedInvocations = Files.readString(fakeLog, StandardCharsets.UTF_8)
                .lines()
                .filter(line -> line.startsWith("setup-cli "))
                .filter(line -> line.contains("TrelloBoardSetupMain status")
                        || line.contains("TrelloBoardSetupMain diagnostics"))
                .filter(line -> line.contains("--config-dir " + isolatedConfigArgument))
                .filter(line -> line.contains("--workspace-root " + isolatedConfigDirectory.resolve("workspaces")))
                .toList();
        assertThat(isolatedInvocations)
                .allSatisfy(line -> assertThat(line)
                        .contains(
                                "--workspace-root " + isolatedConfigDirectory.resolve("workspaces"),
                                "--state-home " + isolatedConfigDirectory.resolveSibling("state"))
                        .doesNotContain(
                                "--workspace-root " + isolatedConfigArgument.resolve("workspaces"),
                                "--state-home " + isolatedConfigArgument.resolveSibling("state"),
                                "--workspace-root " + workspaceRoot,
                                "--state-home " + stateHome))
                .hasSize(2);
        List<String> isolatedSetupLocalInvocations = Files.readString(fakeLog, StandardCharsets.UTF_8)
                .lines()
                .filter(line -> line.startsWith("setup-cli "))
                .filter(line -> line.contains("TrelloBoardSetupMain setup-local"))
                .filter(line -> line.contains("--config-dir " + isolatedConfigArgument))
                .filter(line -> line.contains("--help"))
                .toList();
        assertThat(isolatedSetupLocalInvocations).singleElement().satisfies(line -> assertThat(line)
                .contains("--workspace-root " + isolatedConfigDirectory.resolve("workspaces"))
                .doesNotContain(
                        "--workspace-root " + isolatedConfigArgument.resolve("workspaces"),
                        "state_env=" + isolatedConfigDirectory.resolveSibling("state"),
                        "--workspace-root " + workspaceRoot));
        List<String> isolatedSetupLocalCheckInvocations = Files.readString(fakeLog, StandardCharsets.UTF_8)
                .lines()
                .filter(line -> line.startsWith("setup-cli "))
                .filter(line -> line.contains("TrelloBoardSetupMain setup-local"))
                .filter(line -> line.contains("check"))
                .filter(line -> line.contains("--config-dir " + isolatedConfigArgument))
                .toList();
        assertThat(isolatedSetupLocalCheckInvocations).singleElement().satisfies(line -> assertThat(line)
                .doesNotContain(
                        "--workspace-root " + isolatedConfigDirectory.resolve("workspaces"),
                        "--workspace-root " + isolatedConfigArgument.resolve("workspaces"),
                        "--workspace-root " + workspaceRoot));
        List<String> environmentRootInvocations = Files.readString(fakeLog, StandardCharsets.UTF_8)
                .lines()
                .filter(line -> line.startsWith("setup-cli "))
                .filter(line -> line.contains("TrelloBoardSetupMain status"))
                .filter(line -> line.contains("--config-dir " + isolatedConfigArgument))
                .filter(line -> line.contains("--workspace-root " + environmentWorkspaceRoot))
                .toList();
        assertThat(environmentRootInvocations).singleElement().satisfies(line -> assertThat(line)
                .contains("--state-home " + environmentStateHome)
                .doesNotContain(
                        "--workspace-root " + isolatedConfigDirectory.resolve("workspaces"),
                        "--state-home " + isolatedConfigDirectory.resolveSibling("state"),
                        "--workspace-root " + workspaceRoot,
                        "--state-home " + stateHome));
    }

    @Test
    void powershellInstallerLifecycleInstallsStartsStopsAndUninstallsWithFakeJavaOnWindows() throws Exception {
        // given
        assumeTrue(isWindows());
        assumeTrue(commandExists("pwsh"));
        assumeTrue(commandExists("git"));
        Path installScript = Path.of("install.ps1").toAbsolutePath();
        Path uninstallScript = Path.of("uninstall.ps1").toAbsolutePath();
        Path sourceRepository = createWindowsSourceRepository(temporaryDirectory);
        Path fakeBin = createFakeWindowsToolchain(temporaryDirectory);
        Path symphonyHome = temporaryDirectory.resolve("home $value & (demo)");
        Path installPrefix = symphonyHome.resolve("app");
        Path configDirectory = symphonyHome.resolve("config");
        Path workspaceRoot = symphonyHome.resolve("workspaces");
        Path stateHome = symphonyHome.resolve("state");
        Path binDirectory = temporaryDirectory.resolve("bin $value & (demo) é");
        Path workflow = temporaryDirectory.resolve("WORKFLOW $value & (demo).md");
        Path envFile = temporaryDirectory.resolve(".env $value & (demo)");
        Path fakeLog = temporaryDirectory.resolve("fake powershell tools.log");
        Map<String, String> environment = Map.of(
                "PATH", fakeBin + System.getProperty("path.separator") + System.getenv("PATH"),
                "SYMPHONY_FAKE_LOG", fakeLog.toString(),
                "SYMPHONY_FAKE_JAVA", fakeBin.resolve("fake-java.ps1").toString());

        // when
        ProcessResult install = run(
                environment,
                "pwsh",
                "-NoProfile",
                "-File",
                installScript.toString(),
                "--no-onboard",
                "--symphony-home",
                symphonyHome.toString(),
                "--prefix",
                installPrefix.toString(),
                "--bin-dir",
                binDirectory.toString(),
                "--repo",
                sourceRepository.toUri().toString(),
                "--ref",
                "main");
        Path installedScript = binDirectory.resolve("symphony-trello.ps1");
        Path installedCmdShim = binDirectory.resolve("symphony-trello.cmd");
        Path callerDirectory = temporaryDirectory.resolve("windows caller");
        Files.createDirectories(callerDirectory);
        List<ProcessResult> picocliHelpResults = new ArrayList<>();
        for (String[] command : installedPicocliHelpCommands()) {
            picocliHelpResults.add(run(environment, powerShellFileCommand(installedScript.toString(), command)));
        }
        ProcessResult directBoardSetup =
                run(environment, callerDirectory, powerShellFileCommand(installedScript.toString(), new String[] {
                    "new-board",
                    "--name",
                    "Wrapper Dispatch Board",
                    "--key",
                    "key",
                    "--token",
                    "token",
                    "--workflow",
                    "relative workflow.md",
                    "--workspace-root",
                    "relative workspaces"
                }));
        ProcessResult cmdShimBoardSetup =
                run(environment, callerDirectory, commandPromptCommand(installedCmdShim.toString(), new String[] {
                    "new-board",
                    "--name",
                    "Command Prompt Wrapper Dispatch Board",
                    "--key",
                    "key",
                    "--token",
                    "token",
                    "--workflow",
                    "cmd workflow.md",
                    "--workspace-root",
                    "cmd workspaces"
                }));
        ProcessResult unknownCommand =
                run(environment, "pwsh", "-NoProfile", "-File", installedScript.toString(), "definitely-not-a-command");
        Files.writeString(workflow, "# Windows workflow\n", StandardCharsets.UTF_8);
        Files.writeString(envFile, "TRELLO_API_KEY=key\n", StandardCharsets.UTF_8);
        ProcessResult start = run(
                environment,
                "pwsh",
                "-NoProfile",
                "-File",
                installedScript.toString(),
                "start",
                "--env",
                envFile.toString(),
                "--workflow",
                workflow.toString());
        ProcessResult status = run(
                environment,
                "pwsh",
                "-NoProfile",
                "-File",
                installedScript.toString(),
                "status",
                "--workflow",
                workflow.toString());
        ProcessResult logs = run(
                environment,
                "pwsh",
                "-NoProfile",
                "-Command",
                "& "
                        + powerShellLiteral(installedScript.toString())
                        + " logs --workflow "
                        + powerShellLiteral(workflow.toString())
                        + " | Select-Object -First 1");
        ProcessResult stop = run(
                environment,
                "pwsh",
                "-NoProfile",
                "-File",
                installedScript.toString(),
                "stop",
                "--workflow",
                workflow.toString());
        ProcessResult uninstall = run(
                environment,
                "pwsh",
                "-NoProfile",
                "-File",
                uninstallScript.toString(),
                "--yes",
                "--symphony-home",
                symphonyHome.toString(),
                "--prefix",
                installPrefix.toString(),
                "--bin-dir",
                binDirectory.toString());

        // then
        assertThat(install.exitCode()).as(install.output()).isZero();
        assertThat(picocliHelpResults).allSatisfy(result -> {
            assertThat(result.exitCode()).as(result.output()).isZero();
            assertThat(result.output()).containsAnyOf("Usage: symphony-trello", "symphony-trello test");
        });
        assertThat(directBoardSetup.exitCode()).as(directBoardSetup.output()).isZero();
        assertThat(cmdShimBoardSetup.exitCode()).as(cmdShimBoardSetup.output()).isZero();
        assertThat(unknownCommand.exitCode()).isEqualTo(SETUP_FAILURE);
        assertThat(unknownCommand.output())
                .contains("setup_failed code=setup_invalid_arguments")
                .contains("Unmatched argument: 'definitely-not-a-command'")
                .doesNotContain("at index", "from index");
        assertThat(start.exitCode()).as(start.output()).isZero();
        assertThat(status.output()).contains("running WORKFLOW $value & (demo).md");
        assertThat(logs.output()).contains("fake wrapper log");
        assertThat(stop.output()).contains("Stopped WORKFLOW $value & (demo).md");
        String fakeLogContent = Files.readString(fakeLog, StandardCharsets.UTF_8);
        Map<String, Object> powerShellSetup = setupCliEvent(fakeLogContent, "new-board", "Wrapper Dispatch Board");
        assertThat(String.valueOf(powerShellSetup.get("cwd"))).contains("windows caller");
        assertThat(String.valueOf(powerShellSetup.get("dotenv"))).contains("home $value & (demo)\\config\\.env");
        assertThat(eventArguments(powerShellSetup))
                .contains(
                        "-Dsymphony.trello.config.dir=",
                        "home $value & (demo)\\config",
                        "-Dsymphony.trello.shell=powershell",
                        "new-board --name Wrapper Dispatch Board",
                        "--workflow relative workflow.md",
                        "--workspace-root relative workspaces");
        Map<String, Object> commandPromptSetup =
                setupCliEvent(fakeLogContent, "new-board", "Command Prompt Wrapper Dispatch Board");
        assertThat(String.valueOf(commandPromptSetup.get("cwd"))).contains("windows caller");
        assertThat(String.valueOf(commandPromptSetup.get("dotenv"))).contains("home $value & (demo)\\config\\.env");
        assertThat(eventArguments(commandPromptSetup))
                .contains(
                        "-Dsymphony.trello.config.dir=",
                        "home $value & (demo)\\config",
                        "-Dsymphony.trello.shell=cmd",
                        "new-board --name Command Prompt Wrapper Dispatch Board",
                        "--workflow cmd workflow.md",
                        "--workspace-root cmd workspaces");
        assertThat(fakeLogContent).doesNotContain("new-board --workflow");
        assertThat(uninstall.exitCode()).as(uninstall.output()).isZero();
        assertThat(installPrefix).doesNotExist();
        assertThat(binDirectory.resolve("symphony-trello.ps1")).doesNotExist();
        assertThat(configDirectory).exists();
        assertThat(workspaceRoot).exists();
        assertThat(stateHome).exists();
        ProcessResult cleanup = run(
                environment,
                "pwsh",
                "-NoProfile",
                "-File",
                uninstallScript.toString(),
                "--yes",
                "--yes-local-data",
                "--remove-config",
                "--remove-workspaces",
                "--remove-state",
                "--symphony-home",
                symphonyHome.toString(),
                "--prefix",
                installPrefix.toString(),
                "--bin-dir",
                binDirectory.toString());
        assertThat(cleanup.exitCode()).as(cleanup.output()).isZero();
        assertThat(configDirectory).doesNotExist();
        assertThat(workspaceRoot).doesNotExist();
        assertThat(stateHome).doesNotExist();
        assertThat(eventArguments(setupCliEvent(fakeLogContent, "start", "--env")))
                .contains(".env $value & (demo)", "--workflow", "WORKFLOW $value & (demo).md");
        assertThat(fakeLogContent).contains("mvnw.cmd -q -f", "-DskipTests clean package");
    }

    private static Map<String, Object> setupCliEvent(String log, String... requiredFragments) throws IOException {
        return structuredEvents(log).stream()
                .filter(event -> "setup-cli".equals(event.get("event")))
                .filter(event -> containsAll(eventArguments(event), requiredFragments))
                .findAny()
                .orElseThrow(() -> new AssertionError(
                        "Expected setup-cli event containing: " + String.join(", ", requiredFragments)));
    }

    private static List<Map<String, Object>> structuredEvents(String log) throws IOException {
        List<Map<String, Object>> events = new ArrayList<>();
        for (String line : log.lines().toList()) {
            if (line.stripLeading().startsWith("{")) {
                events.add(JSON.readValue(line, JSON_OBJECT));
            }
        }
        return events;
    }

    private static boolean containsAll(String value, String... fragments) {
        for (String fragment : fragments) {
            if (!value.contains(fragment)) {
                return false;
            }
        }
        return true;
    }

    private static String eventArguments(Map<String, Object> event) {
        Object args = event.get("args");
        assertThat(args).as("structured fake-tool event args").isInstanceOf(List.class);
        return ((List<?>) args).stream().map(String::valueOf).collect(Collectors.joining(" "));
    }
}
