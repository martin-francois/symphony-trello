package ch.fmartin.symphony.trello.setup;

import static ch.fmartin.symphony.trello.CliExitCodes.SETUP_FAILURE;
import static ch.fmartin.symphony.trello.setup.InstallerScriptFixture.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ch.fmartin.symphony.trello.setup.InstallerScriptFixture.ProcessResult;
import ch.fmartin.symphony.trello.setup.InstallerScriptFixture.PseudoTerminalResult;
import ch.fmartin.symphony.trello.telemetry.TelemetryEnvironment;
import ch.fmartin.symphony.trello.telemetry.TelemetryNotice;
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
import java.util.StringJoiner;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@ExtendWith(WindowsUserPathGuard.class)
@ResourceLock("windows-user-path")
final class InstallerScriptLifecycleTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() {};

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
        Path homeDirectory = temporaryDirectory.resolve("installer-home");
        Path symphonyHome = temporaryDirectory.resolve("data-home/symphony-trello");
        Path installPrefix = symphonyHome.resolve("app");
        Path configDirectory = temporaryDirectory.resolve("config-home/symphony-trello");
        Path workspaceRoot = symphonyHome.resolve("workspaces");
        Path binDirectory = temporaryDirectory.resolve("bin");
        Path stateHome = temporaryDirectory.resolve("state-home/symphony-trello");
        Path userService = homeDirectory.resolve(".config/systemd/user/symphony-trello.service");
        Path autostartEnvironment = configDirectory.resolve("autostart.env");
        Path fakeLog = temporaryDirectory.resolve("fake-tools.log");
        Map<String, String> environment = new LinkedHashMap<>(Map.of(
                "PATH",
                fakeBin + System.getProperty("path.separator") + System.getenv("PATH"),
                "HOME",
                homeDirectory.toString(),
                "USER",
                "symphony-test",
                "SYMPHONY_TRELLO_REPO_URL",
                sourceRepository.toUri().toString(),
                "SYMPHONY_TRELLO_REF",
                "main",
                "SYMPHONY_HOME",
                symphonyHome.toString(),
                "SYMPHONY_FAKE_LOG",
                fakeLog.toString(),
                "SYMPHONY_FAKE_SLOW_TERM",
                FAKE_SLOW_TERM_DELAYED,
                "TRELLO_API_KEY",
                "runtime-key-100%",
                "TRELLO_API_TOKEN",
                "runtime-token"));
        environment.put("SYMPHONY_TRELLO_CONFIG_DIR", configDirectory.toString());
        environment.put("SYMPHONY_TRELLO_WORKSPACE_ROOT", workspaceRoot.toString());
        environment.put("SYMPHONY_TRELLO_STATE_HOME", stateHome.toString());
        environment.put("SYMPHONY_TRELLO_INSTALLER_COMPLETION", "preexisting");
        environment.put(TelemetryEnvironment.DISABLED_VARIABLE, "1");

        // when
        PseudoTerminalResult install = runWithPseudoTerminal(
                environment,
                "n\napi-key\napi-token\nLifecycle Board\n",
                "bash " + shellQuote(installScript.toString()) + " --bin-dir " + shellQuote(binDirectory.toString()));
        String userServiceContentAfterInstall = Files.readString(userService);
        String autostartEnvironmentAfterInstall = Files.readString(autostartEnvironment);
        Path installedCommand = binDirectory.resolve("symphony-trello");
        String installedCommandContent = Files.readString(installedCommand);
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
                                configDirectory.resolve(".env")));
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
        Path telemetryState = stateHome.resolve("telemetry.json");
        String telemetryPreference = "{\"format_version\":1,\"mode\":\"DISABLED\",\"preference_revision\":1}\n";
        Files.writeString(telemetryState, telemetryPreference);
        LegacyStateFiles legacyStateFiles =
                moveManagedStateFilesToLegacy(configDirectory, stateHome, List.of(".log", ".log.err"));
        ProcessResult update = run(
                environment, "bash", installScript.toString(), "--no-onboard", "--bin-dir", binDirectory.toString());
        assertThat(update.exitCode()).as(update.output()).isZero();
        MigrationObservation migration = observeMigration(legacyStateFiles, stateHome);
        ProcessResult logsAfterMigration = run(
                environment,
                installedCommand.toString(),
                "logs",
                "--workflow",
                configDirectory.resolve("WORKFLOW.lifecycle-board.md").toString());
        addSourceRepositoryCommit(sourceRepository, "UPGRADE_MARKER", "updated\n");
        ProcessResult secondUpdate = run(
                environment, "bash", installScript.toString(), "--no-onboard", "--bin-dir", binDirectory.toString());
        String markerContentAfterUpdate = Files.readString(installPrefix.resolve("UPGRADE_MARKER"));
        ProcessResult statusWhileRunning = run(environment, installedCommand.toString(), "status");
        Path pidFile = singleFile(stateHome, ".pid");
        long managedPid = Long.parseLong(Files.readString(pidFile).trim());
        ProcessResult stop = run(
                environment,
                installedCommand.toString(),
                "stop",
                "--workflow",
                configDirectory.resolve("WORKFLOW.lifecycle-board.md").toString());
        ProcessResult restart = run(environment, installedCommand.toString(), "start");
        int fakeLogLengthBeforeUninstall = Files.readString(fakeLog).length();
        Path restartedPidFile = singleFile(stateHome, ".pid");
        long restartedManagedPid =
                Long.parseLong(Files.readString(restartedPidFile).trim());
        ProcessResult uninstall =
                run(environment, "bash", uninstallScript.toString(), "--yes", "--bin-dir", binDirectory.toString());

        // then
        assertThat(install.exitCode()).isZero();
        assertThat(installedCommandContent).contains("exec java " + LocalWorkerManager.ENABLE_NATIVE_ACCESS_FLAG + " ");
        assertThat(install.transcript())
                .contains("Usage reporting disabled (" + TelemetryEnvironment.DISABLED_VARIABLE + ")");
        assertThat(fakeLog).content(StandardCharsets.UTF_8).contains(TelemetryNotice.DISABLE_ARGUMENTS + " --yes");
        assertThat(install.transcript())
                .containsSubsequence(
                        "Install plan",
                        "[1/4] Checking prerequisites",
                        "Codex CLI is installed but not logged in.",
                        "Can this machine open a browser for Codex login?",
                        "RUN  codex login --device-auth",
                        "OK  Codex CLI logged in",
                        "[2/4] Installing Symphony",
                        "OK  Command installed",
                        "[3/4] Running setup",
                        "OK  Setup complete",
                        "[4/4] Starting managed workers",
                        "User systemd service enabled: symphony-trello.service",
                        "User lingering enabled for reboot autostart.",
                        "You're good to go - your Trello board is now a queue for Codex work.")
                .doesNotContain("Device auth");
        assertThat(install.transcript())
                .containsOnlyOnce("You're good to go - your Trello board is now a queue for Codex work.");
        assertThat(install.transcript().stripTrailing())
                .containsSubsequence(
                        "symphony-trello logs --workflow '" + configDirectory.resolve("WORKFLOW.lifecycle-board.md")
                                + "'",
                        " tutorial --offer",
                        GuidedTutorial.OFFER_PROMPT,
                        "You can start it later with:")
                .endsWith("symphony-trello tutorial");
        assertThat(userServiceContentAfterInstall)
                .contains(
                        "Description=Symphony for Trello managed local workers",
                        "Type=oneshot",
                        "RemainAfterExit=yes",
                        "Environment=\"PATH=",
                        "EnvironmentFile=-" + autostartEnvironment,
                        "symphony-trello\" start --all",
                        "symphony-trello\" stop",
                        "Restart=on-failure",
                        "RestartSec=10s",
                        "StartLimitBurst=12",
                        "StartLimitIntervalSec=300");
        assertThat(autostartEnvironmentAfterInstall)
                .contains(
                        "Installer-managed Symphony for Trello autostart environment.",
                        "SYMPHONY_HOME=\"" + symphonyHome + "\"",
                        "TRELLO_API_KEY=\"runtime-key-100%\"",
                        "TRELLO_API_TOKEN=\"runtime-token\"")
                .doesNotContain("\nHOME=", "\nUSER=", "SYMPHONY_TRELLO_INSTALLER_COMPLETION");
        assertThat(picocliHelpResults).allSatisfy(result -> {
            assertThat(result.exitCode()).as(result.output()).isZero();
            assertThat(result.output()).containsAnyOf("Usage: symphony-trello", "symphony-trello " + FAKE_APP_VERSION);
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
        assertSuccessfulMigration(migration, "POSIX");
        assertThat(logsAfterMigration.exitCode())
                .as(logsAfterMigration.output())
                .isZero();
        assertThat(secondUpdate.exitCode()).isZero();
        assertThat(install.transcript()).doesNotContain("Checking generated workflows...");
        assertThat(update.output()).contains("Checking generated workflows...");
        assertThat(fakeLog)
                .content()
                .contains("TrelloBoardSetupMain migrate-workflows --from-version " + FAKE_APP_VERSION
                        + " --non-interactive");
        assertThat(telemetryState)
                .as("an installer rerun leaves the stored usage-reporting preference untouched")
                .content()
                .isEqualTo(telemetryPreference);
        assertThat(markerContentAfterUpdate).isEqualTo("updated\n");
        assertThat(statusWhileRunning.output())
                .contains("running WORKFLOW.lifecycle-board.md pid=")
                .doesNotContain("running WORKFLOW.lifecycle-board.md.");
        assertThat(stop.exitCode()).isZero();
        assertThat(stop.output())
                .contains("Stopped WORKFLOW.lifecycle-board.md")
                .doesNotContain("Stopped WORKFLOW.lifecycle-board.md.");
        assertThat(processStopsWithin(managedPid, 5))
                .as("the managed process stops within 5 seconds")
                .isTrue();
        assertThat(restart.exitCode()).isZero();
        assertThat(restart.output()).contains("Started Symphony for Trello");
        assertThat(uninstall.exitCode()).isZero();
        assertThat(uninstall.output())
                .contains("STOP", "REMOVE  " + binDirectory.resolve("symphony-trello"), "REMOVE  " + userService);
        String fakeLogAfterUninstall = Files.readString(fakeLog);
        String fakeLogDuringUninstall = fakeLogAfterUninstall.substring(fakeLogLengthBeforeUninstall);
        assertUninstallStoppedWorkerBeforeDisablingService(
                uninstall, fakeLogDuringUninstall, "WORKFLOW.lifecycle-board.md", restartedManagedPid);
        assertThat(userService).doesNotExist();
        assertThat(autostartEnvironment).doesNotExist();
        assertThat(processStopsWithin(restartedManagedPid, 5))
                .as("the restarted managed process stops during uninstall within 5 seconds")
                .isTrue();
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
                        "systemctl --user show-environment",
                        "systemctl --user daemon-reload",
                        "systemctl --user enable symphony-trello.service",
                        "systemctl --user restart symphony-trello.service",
                        "loginctl enable-linger",
                        "systemctl --user disable --now symphony-trello.service",
                        "setup-cli",
                        "setup-cli cwd=" + callerDirectory,
                        "-Dsymphony.trello.config.dir=" + configDirectory,
                        "new-board --name Wrapper Dispatch Board",
                        "--workflow relative workflow.md",
                        "--workspace-root relative workspaces",
                        "dotenv=" + configDirectory.resolve(".env"),
                        "definitely-not-a-command",
                        "mvnw -q -f " + installPrefix.resolve("pom.xml") + " -DskipTests clean package",
                        "completion_mode=defer",
                        "jar-start",
                        "app-present-before-exit",
                        "jar-stopped")
                .doesNotContain("new-board --workflow");
        assertThat(fakeLog).content().containsSubsequence("completion_mode=defer", "completion_mode=print");
        List<String> managedStartInvocations = Files.readString(fakeLog)
                .lines()
                .filter(line -> line.contains("TrelloBoardSetupMain start"))
                .filter(line -> line.contains(" --workflow ") || line.contains(" --all "))
                .toList();
        assertThat(managedStartInvocations).isNotEmpty().allSatisfy(line -> assertThat(line)
                .contains("--state-home " + stateHome, "completion_mode=")
                .doesNotContain("completion_mode=preexisting", "completion_mode=defer", "completion_mode=print"));
        List<String> isolatedInvocations = Files.readString(fakeLog)
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
        List<String> isolatedSetupLocalInvocations = Files.readString(fakeLog)
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
                        " state_env=" + isolatedConfigDirectory.resolveSibling("state") + " ",
                        "--workspace-root " + workspaceRoot));
        List<String> isolatedSetupLocalCheckInvocations = Files.readString(fakeLog)
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
        List<String> environmentRootInvocations = Files.readString(fakeLog)
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
    void posixUninstallerKillsWorkerThatOutlastsTermGraceBeforeDisablingService() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        Path fakeBin = createFakeToolchain(temporaryDirectory);
        Path homeDirectory = temporaryDirectory.resolve("outlasting-home");
        Path symphonyHome = temporaryDirectory.resolve("outlasting-symphony-home");
        Path app = symphonyHome.resolve("app").toAbsolutePath().normalize();
        Path stateHome = symphonyHome.resolve("state");
        Path userService = homeDirectory.resolve(".config/systemd/user/symphony-trello.service");
        Path fakeLog = temporaryDirectory.resolve("fake-tools.log");
        Files.createDirectories(app.resolve("target/quarkus-app"));
        Files.writeString(app.resolve(".symphony-trello-install"), "marker");
        Files.createDirectories(stateHome);
        Files.createDirectories(userService.getParent());
        Files.writeString(userService, "[Unit]\n");
        // The worker's TERM handler never finishes, so the uninstaller's grace always runs out while it is
        // still inside the handler: the interleaving behind #778, forced instead of left to host load.
        ProcessBuilder workerBuilder = new ProcessBuilder(
                        fakeBin.resolve("java").toString(),
                        "-Dsymphony.trello.managed.app_home=" + app,
                        "-jar",
                        app.resolve("target/quarkus-app/quarkus-run.jar").toString())
                .directory(app.toFile())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD);
        workerBuilder.environment().put("SYMPHONY_FAKE_LOG", fakeLog.toString());
        workerBuilder.environment().put("SYMPHONY_FAKE_SLOW_TERM", FAKE_SLOW_TERM_UNTIL_KILLED);
        Process worker = workerBuilder.start();
        try {
            assertThat(fileContainsWithin(fakeLog, "jar-start pid=" + worker.pid() + " ", FAKE_WORKER_READY_SECONDS))
                    .as(
                            "the fake worker installs its TERM handler and reports jar-start within %s seconds",
                            FAKE_WORKER_READY_SECONDS)
                    .isTrue();
            Files.writeString(
                    stateHome.resolve("WORKFLOW.outlasting.md.abcdef123456.pid"), Long.toString(worker.pid()));
            Map<String, String> environment = Map.of(
                    "PATH",
                    fakeBin + System.getProperty("path.separator") + System.getenv("PATH"),
                    "HOME",
                    homeDirectory.toString(),
                    "SYMPHONY_HOME",
                    symphonyHome.toString(),
                    "SYMPHONY_FAKE_LOG",
                    fakeLog.toString());

            // when
            ProcessResult uninstall = run(environment, "bash", "uninstall.sh", "--yes", "--prefix", app.toString());

            // then
            assertThat(uninstall.exitCode()).as(uninstall.output()).isZero();
            assertThat(uninstall.output()).contains("KILL  pid=" + worker.pid() + " did not stop after SIGTERM");
            String fakeLogAfterUninstall = Files.readString(fakeLog);
            assertUninstallStoppedWorkerBeforeDisablingService(
                    uninstall, fakeLogAfterUninstall, "WORKFLOW.outlasting.md", worker.pid());
            assertThat(fakeLogAfterUninstall)
                    .as("the worker was killed inside its TERM handler, after the app check and before jar-stopped")
                    .contains("app-present-before-exit")
                    .doesNotContain("jar-stopped");
            assertThat(app).doesNotExist();
            assertThat(userService).doesNotExist();
        } finally {
            worker.destroyForcibly();
        }
    }

    private static String scriptFunctions(String script, String... names) {
        var selected = new StringJoiner(", ");
        for (String name : names) {
            selected.add(powerShellLiteral(name));
        }
        return """
                $ErrorActionPreference = 'Stop'
                $tokens = $null
                $errors = $null
                $ast = [Management.Automation.Language.Parser]::ParseFile(%s, [ref]$tokens, [ref]$errors)
                $names = @(%s)
                foreach ($function in $ast.FindAll({ param($node)
                  $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -in $names
                }, $false)) { . ([scriptblock]::Create($function.Extent.Text)) }
                """
                .formatted(powerShellLiteral(Path.of(script).toAbsolutePath().toString()), selected);
    }

    @Test
    void powershellInstallerStartsWorkersWithoutWaitingForAScheduledTaskOnWindows() throws Exception {
        // given
        assumeTrue(isWindows());
        Path script = temporaryDirectory.resolve("immediate-start.ps1");
        Path proof = temporaryDirectory.resolve("started.txt");
        Path bin = Files.createDirectory(temporaryDirectory.resolve("bin"));
        Files.writeString(
                bin.resolve("symphony-trello.ps1"),
                "[IO.File]::WriteAllText(" + powerShellLiteral(proof.toString()) + ", ($args -join ' '))");
        Files.writeString(
                script,
                """
                %s
                function Write-AutostartLauncher {}
                function Install-ScheduledTaskAutostart { return '' }
                function Start-ScheduledTask {}
                function Write-Status {}
                function Invoke-Step($Label, $Action) { & $Action }
                $DryRun = $false
                $ScheduledTaskName = 'Synthetic deferred task'
                $BinDir = %s
                Start-ManagedWorkers
                """
                        .formatted(
                                scriptFunctions("install.ps1", "Enable-WindowsAutostart", "Start-ManagedWorkers"),
                                powerShellLiteral(bin.toString())));

        // when
        ProcessResult result = run(Map.of(), "pwsh", "-NoProfile", "-File", script.toString());

        // then
        result.assertSuccess();
        assertThat(proof).hasContent("start --all");
    }

    @CsvSource({"pwsh,false", "pwsh,true", "powershell.exe,false", "powershell.exe,true"})
    @ParameterizedTest
    void powershellLegacyMigrationRejectsAnotherConfigurationBeforeStoppingOnWindows(
            String launcher, boolean normalizedCommand) throws Exception {
        // given
        assumeTrue(isWindows());
        Path script = temporaryDirectory.resolve("shared-legacy.ps1");
        Path proof = temporaryDirectory.resolve("stop-invoked.txt");
        Files.writeString(
                script,
                """
                $ErrorActionPreference = 'Stop'
                %s
                function Test-WindowsPlatform { return $true }
                function Get-Process { return @{ Id = 42 } }
                function Get-CimInstance($Class, $Filter) {
                  $workflow = if ($Filter -match '41$') { $WorkflowA } else { $WorkflowB }
                  if ($NormalizeWorkflow) { $workflow = [IO.Path]::GetFullPath($workflow) }
                  return @{ CommandLine = 'java "-Dsymphony.trello.managed.app_home=' + $Prefix +
                    '" -jar "' + (Join-Path $Prefix 'target\\quarkus-app\\quarkus-run.jar') + '" "' + $workflow + '"' }
                }
                function Invoke-Step($Label, $Action) { [IO.File]::WriteAllText(%s, 'stop called') }
                $Prefix = Join-Path %s 'app'
                $NormalizeWorkflow = %s
                $ConfigDir = Join-Path %s 'config-a é'
                $ConfigB = Join-Path %s 'config-b'
                $StateHome = Join-Path %s 'new-state'
                $Legacy = Join-Path %s 'state'
                $BinDir = Join-Path $Prefix 'bin'
                $WorkflowA = Join-Path $ConfigDir '.\\WORKFLOW.md'
                $WorkflowB = Join-Path $ConfigB 'WORKFLOW.md'
                New-Item -ItemType Directory -Path $ConfigDir, $ConfigB, $Legacy | Out-Null
                [IO.File]::WriteAllText((Join-Path $ConfigDir 'connected-boards.json'),
                  (@{ boards = @(@{ workflowPath = $WorkflowA }) } | ConvertTo-Json -Depth 4))
                [IO.File]::WriteAllText((Join-Path $ConfigB 'connected-boards.json'),
                  (@{ boards = @(@{ workflowPath = $WorkflowB }) } | ConvertTo-Json -Depth 4))
                $PidA = Join-Path $Legacy 'WORKFLOW.a.aaaaaaaaaaaa.pid'
                $PidB = Join-Path $Legacy 'WORKFLOW.b.bbbbbbbbbbbb.pid'
                [IO.File]::WriteAllText($PidA, '41')
                [IO.File]::WriteAllText($PidB, '42')
                if (-not (Test-LegacyPidOwnership $PidA)) { throw 'cannot select owned worker: expected configuration A' }
                try { Stop-LegacyManagedWorkers $Legacy } catch { Write-Output $_.Exception.Message; exit 0 }
                throw 'cannot validate migration: expected refusal before invoking stop'
                """
                        .formatted(
                                scriptFunctions(
                                        "install.ps1",
                                        "Test-LegacyPidOwnership",
                                        "Get-ManagedPidFiles",
                                        "Stop-LegacyManagedWorkers"),
                                powerShellLiteral(proof.toString()),
                                powerShellLiteral(temporaryDirectory.toString()),
                                normalizedCommand ? "$true" : "$false",
                                powerShellLiteral(temporaryDirectory.toString()),
                                powerShellLiteral(temporaryDirectory.toString()),
                                powerShellLiteral(temporaryDirectory.toString()),
                                powerShellLiteral(temporaryDirectory.toString())));

        Files.writeString(script, "\uFEFF" + Files.readString(script));

        // when
        ProcessResult result =
                run(Map.of(), launcher, "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", script.toString());

        // then
        result.assertSuccess();
        assertThat(result.output()).contains("cannot migrate managed worker state", "selected configuration");
        assertThat(proof).doesNotExist();
        assertThat(temporaryDirectory.resolve("state/WORKFLOW.a.aaaaaaaaaaaa.pid"))
                .hasContent("41");
        assertThat(temporaryDirectory.resolve("state/WORKFLOW.b.bbbbbbbbbbbb.pid"))
                .hasContent("42");
    }

    @Test
    void powershellCodexShimPreservesUnicodePrefixAndArgumentsOnWindows() throws Exception {
        // given
        assumeTrue(isWindows());
        assumeTrue(commandExists("pwsh"));
        Path script = temporaryDirectory.resolve("codex-shim-probe.ps1");
        Path proof = temporaryDirectory.resolve("arguments.txt");
        Path binDirectory = temporaryDirectory.resolve("bin é & (demo)");
        Path npmPrefix = temporaryDirectory.resolve("npm O'Brien é & (demo)");
        Path npmPackage = Files.createDirectories(npmPrefix.resolve("node_modules/@openai/codex"));
        Files.writeString(npmPackage.resolve("package.json"), "{\"bin\":{\"codex\":\"cli.mjs\"}}");
        Files.writeString(
                npmPackage.resolve("cli.mjs"),
                "import fs from 'node:fs'; fs.writeFileSync(" + JSON.writeValueAsString(proof.toString())
                        + ", process.argv.slice(2).join('|')); process.exit(17);");
        Files.writeString(
                script,
                """
                %s
                function Test-Command { return $true }
                function Invoke-VisibleStep {}
                function Invoke-Step($Label, $Action) { & $Action }
                $BinDir = Join-Path %s 'bin é & (demo)'
                $CodexNpmPrefix = %s
                Install-CodexWithUserLocalNpm
                %s
                foreach ($wrapper in @('codex.cmd', 'codex.ps1')) {
                  if (-not (Test-ManagedCodexWrapper (Join-Path $BinDir $wrapper))) {
                    throw "The uninstaller did not recognize its generated $wrapper shim."
                  }
                }
                $CodexNpmPrefix = Join-Path $CodexNpmPrefix 'unrelated'
                foreach ($wrapper in @('codex.cmd', 'codex.ps1')) {
                  if (Test-ManagedCodexWrapper (Join-Path $BinDir $wrapper)) {
                    throw "The uninstaller claimed a $wrapper shim for another installation."
                  }
                }
                """
                        .formatted(
                                scriptFunctions(
                                        "install.ps1",
                                        "Write-PowerShellScript",
                                        "ConvertTo-PowerShellLiteral",
                                        "Install-CodexWithUserLocalNpm",
                                        "Get-CodexNpmInstallCommand"),
                                powerShellLiteral(temporaryDirectory.toString()),
                                powerShellLiteral(npmPrefix.toString()),
                                scriptFunctions("uninstall.ps1", "Test-ManagedCodexWrapper")));

        run(Map.of(), "pwsh", "-NoProfile", "-File", script.toString()).assertSuccess();

        // when
        ProcessResult result = run(
                Map.of(),
                "cmd.exe",
                "/d",
                "/s",
                "/c",
                "\"\"" + binDirectory.resolve("codex.cmd") + "\" --version \"two words\" é \"\" model=\\\"gpt-5\\\"\"");

        // then
        assertThat(result.exitCode()).as(result.output()).isEqualTo(17);
        assertThat(proof).hasContent("--version|two words|é||model=\"gpt-5\"");
    }

    @Test
    void powershellStartupFallbackPreservesUnicodePathsOnWindows() throws Exception {
        // given
        assumeTrue(isWindows());
        assumeTrue(commandExists("pwsh"));
        Path script = temporaryDirectory.resolve("startup-probe.ps1");
        Path proof = temporaryDirectory.resolve("started.txt");
        Files.writeString(
                script,
                """
                %s
                function Write-AutostartEnvironmentFile {}
                function Write-Status {}
                $DryRun = $false
                $BinDir = Join-Path %s 'bin é & (demo)'
                $StartupFolder = Join-Path %s 'startup é'
                $AutostartScriptPath = Join-Path $BinDir 'symphony-trello-autostart.ps1'
                $AutostartEnvironmentPath = Join-Path $BinDir 'absent-env.ps1'
                $StartupCommandPath = Join-Path $StartupFolder 'symphony-trello.cmd'
                New-Item -ItemType Directory -Path $BinDir | Out-Null
                "[IO.File]::WriteAllText(%s, (`$args -join ' '))" |
                  Write-PowerShellScript -Path (Join-Path $BinDir 'symphony-trello.ps1')
                Install-StartupFolderCommand | Out-Null
                & $StartupCommandPath
                exit $LASTEXITCODE
                """
                        .formatted(
                                scriptFunctions(
                                        "install.ps1",
                                        "Write-PowerShellScript",
                                        "ConvertTo-PowerShellLiteral",
                                        "Write-AutostartLauncher",
                                        "Get-StartAllCommand",
                                        "Install-StartupFolderCommand"),
                                powerShellLiteral(temporaryDirectory.toString()),
                                powerShellLiteral(temporaryDirectory.toString()),
                                powerShellLiteral(proof.toString())));

        // when
        ProcessResult result = run(Map.of(), "pwsh", "-NoProfile", "-File", script.toString());

        // then
        result.assertSuccess();
        assertThat(proof).hasContent("start --all");
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
        Path configDirectory = temporaryDirectory.resolve("config home $value/symphony-trello");
        Path workspaceRoot = symphonyHome.resolve("workspaces");
        Path stateHome = temporaryDirectory.resolve("state home $value/symphony-trello");
        Path binDirectory = temporaryDirectory.resolve("bin $value & (demo) é");
        Path workflow = temporaryDirectory.resolve("WORKFLOW $value & (demo).md");
        Path envFile = temporaryDirectory.resolve(".env $value & (demo)");
        Path fakeLog = temporaryDirectory.resolve("fake powershell tools.log");
        Map<String, String> environment = Map.of(
                "PATH", fakeBin + System.getProperty("path.separator") + System.getenv("PATH"),
                "SYMPHONY_FAKE_LOG", fakeLog.toString(),
                "SYMPHONY_FAKE_JAVA", fakeBin.resolve("fake-java.ps1").toString(),
                "SYMPHONY_TRELLO_CONFIG_DIR", configDirectory.toString(),
                "SYMPHONY_TRELLO_STATE_HOME", stateHome.toString());

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
        install.assertSuccess();
        Path canonicalInstallPrefix = installPrefix.toRealPath();
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
        Files.writeString(workflow, "# Windows workflow\n");
        Files.writeString(envFile, "TRELLO_API_KEY=key\n");
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
        LegacyStateFiles legacyStateFiles = moveManagedStateFilesToLegacy(configDirectory, stateHome, List.of(".log"));
        ProcessResult update = run(
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
        assertThat(update.exitCode()).as(update.output()).isZero();
        assertThat(stateHome.resolve("WORKFLOW.md.abcdef123456.pid"))
                .as("the installer restores managed PID tracking before returning")
                .isRegularFile();
        MigrationObservation migration = observeMigration(legacyStateFiles, stateHome);
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
        assertThat(picocliHelpResults).allSatisfy(result -> {
            assertThat(result.exitCode()).as(result.output()).isZero();
            assertThat(result.output()).containsAnyOf("Usage: symphony-trello", "symphony-trello " + FAKE_APP_VERSION);
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
        assertThat(update.exitCode()).as(update.output()).isZero();
        assertSuccessfulMigration(migration, "PowerShell");
        assertThat(stop.output()).contains("Stopped WORKFLOW $value & (demo).md");
        assertThat(install.output()).doesNotContain("Checking generated workflows...");
        assertThat(update.output()).contains("Checking generated workflows...");
        String fakeLogContent = Files.readString(fakeLog);
        assertThat(eventArguments(setupCliEvent(fakeLogContent, "migrate-workflows", "--non-interactive")))
                .contains("migrate-workflows --from-version " + FAKE_APP_VERSION + " --non-interactive");
        Map<String, Object> powerShellSetup = setupCliEvent(fakeLogContent, "new-board", "Wrapper Dispatch Board");
        assertThat(String.valueOf(powerShellSetup.get("cwd"))).contains("windows caller");
        assertThat(String.valueOf(powerShellSetup.get("dotenv"))).contains("config home $value\\symphony-trello\\.env");
        assertThat(eventArguments(powerShellSetup))
                .contains(
                        "-Dsymphony.trello.config.dir=",
                        "config home $value\\symphony-trello",
                        "-Dsymphony.trello.shell=powershell",
                        "new-board --name Wrapper Dispatch Board",
                        "--workflow relative workflow.md",
                        "--workspace-root relative workspaces");
        Map<String, Object> commandPromptSetup =
                setupCliEvent(fakeLogContent, "new-board", "Command Prompt Wrapper Dispatch Board");
        assertThat(String.valueOf(commandPromptSetup.get("cwd"))).contains("windows caller");
        assertThat(String.valueOf(commandPromptSetup.get("dotenv")))
                .contains("config home $value\\symphony-trello\\.env");
        assertThat(eventArguments(commandPromptSetup))
                .contains(
                        "-Dsymphony.trello.config.dir=",
                        "config home $value\\symphony-trello",
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
        assertThat(fakeLogContent)
                .contains("mvnw.cmd -q -f", "-DskipTests clean package", "build-cwd=" + canonicalInstallPrefix);
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

    private static LegacyStateFiles moveManagedStateFilesToLegacy(
            Path configDirectory, Path stateHome, List<String> logSuffixes) throws IOException {
        Path legacyStateHome = configDirectory.resolveSibling("state");
        Files.createDirectories(legacyStateHome);
        Path currentPid = singleFile(stateHome, ".pid");
        Path legacyPid = legacyStateHome.resolve(currentPid.getFileName());
        Files.move(currentPid, legacyPid);
        List<Path> legacyLogs = new ArrayList<>();
        for (String suffix : logSuffixes) {
            Path currentLog = singleFile(stateHome, suffix);
            Path legacyLog = legacyStateHome.resolve(currentLog.getFileName());
            Files.move(currentLog, legacyLog);
            legacyLogs.add(legacyLog);
        }
        return new LegacyStateFiles(legacyPid, List.copyOf(legacyLogs));
    }

    private static MigrationObservation observeMigration(LegacyStateFiles legacyStateFiles, Path stateHome)
            throws IOException {
        boolean legacyLogsPreserved = true;
        for (Path legacyLog : legacyStateFiles.logs()) {
            legacyLogsPreserved &= Files.isRegularFile(legacyLog);
        }
        return new MigrationObservation(
                Files.notExists(legacyStateFiles.pid()),
                Files.isRegularFile(singleFile(stateHome, ".pid")),
                legacyLogsPreserved);
    }

    private static void assertSuccessfulMigration(MigrationObservation migration, String platform) {
        assertThat(migration.legacyPidRemoved())
                .as("the %s update removes legacy managed-worker PID tracking", platform)
                .isTrue();
        assertThat(migration.configuredPidCreated())
                .as("the %s update restarts managed-worker PID tracking in the configured state home", platform)
                .isTrue();
        assertThat(migration.legacyLogsPreserved())
                .as("the %s update keeps legacy worker logs as history", platform)
                .isTrue();
    }

    private record LegacyStateFiles(Path pid, List<Path> logs) {}

    private record MigrationObservation(
            boolean legacyPidRemoved, boolean configuredPidCreated, boolean legacyLogsPreserved) {}
}
