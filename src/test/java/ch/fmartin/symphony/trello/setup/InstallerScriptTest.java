package ch.fmartin.symphony.trello.setup;

import static ch.fmartin.symphony.trello.setup.InstallerScriptFixture.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ch.fmartin.symphony.trello.setup.InstallerScriptFixture.ProcessResult;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

final class InstallerScriptTest {
    @TempDir
    Path temporaryDirectory;

    @ParameterizedTest(name = "{0} syntax")
    @ValueSource(strings = {"install.sh", "uninstall.sh"})
    void posixInstallAndUninstallScriptsHaveValidBashSyntax(String script) throws Exception {
        // given
        assumeTrue(commandExists("bash"));

        // when
        ProcessResult result = run(Map.of(), "bash", "-n", script);

        // then
        result.assertSuccess();
    }

    @MethodSource("posixPublicDryRunScenarios")
    @ParameterizedTest(name = "{0}")
    void posixPublicDryRunCommandsStopBeforeChangingFiles(String name, String[] command, String[] expectedOutput)
            throws Exception {
        // given
        assumeTrue(commandExists("bash"));

        // when
        ProcessResult result = run(Map.of(), command);

        // then
        result.assertSuccess();
        assertThat(result.output()).contains(expectedOutput);
    }

    private static Stream<Arguments> posixPublicDryRunScenarios() {
        return Stream.of(
                Arguments.of(
                        "install dry-run",
                        new String[] {"bash", "install.sh", "--dry-run", "--no-onboard"},
                        new String[] {
                            "Symphony for Trello installer",
                            "Dry run: no files changed.",
                            "Symphony would install the command here:",
                            "Command PATH setup"
                        }),
                Arguments.of("uninstall dry-run", new String[] {"bash", "uninstall.sh", "--dry-run"}, new String[] {
                    "Symphony for Trello uninstall", "Trello boards were not deleted or archived."
                }));
    }

    @Test
    void posixInstallerRejectsUnsafeCommandDirectoriesBeforeDryRunPlan() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        Path home = temporaryDirectory.resolve("install-bin-home");
        Path symphonyHome = temporaryDirectory.resolve("install-bin-symphony-home");
        Path app = temporaryDirectory.resolve("install-bin-app");
        Path file = temporaryDirectory.resolve("install-bin-file");
        Path symlink = temporaryDirectory.resolve("install-bin-config-link");
        Files.createDirectories(home);
        Files.createDirectories(symphonyHome.resolve("config"));
        Files.writeString(file, "not a directory", StandardCharsets.UTF_8);
        Files.createSymbolicLink(symlink, symphonyHome.resolve("config"));
        Map<String, String> environment = Map.of("HOME", home.toString(), "SYMPHONY_HOME", symphonyHome.toString());

        List<UnsafeCommandDirectory> cases = posixUnsafeCommandDirectories(
                home,
                file,
                symlink,
                "install-bin-config-link",
                new UnsafeCommandDirectory(
                        "config-overlap",
                        symphonyHome.resolve("config").toString(),
                        "--bin-dir must not overlap Symphony app, config, workspace, or state directories."));

        // when
        List<ProcessResult> results = cases.stream()
                .map(commandDirectory -> runUnchecked(
                        environment,
                        "bash",
                        "install.sh",
                        "--dry-run",
                        "--no-onboard",
                        "--prefix",
                        app.toString(),
                        "--bin-dir",
                        commandDirectory.value()))
                .toList();

        // then
        for (int index = 0; index < cases.size(); index++) {
            UnsafeCommandDirectory commandDirectory = cases.get(index);
            ProcessResult result = results.get(index);
            assertThat(result.exitCode()).as(commandDirectory.name()).isEqualTo(2);
            assertThat(result.output())
                    .as(commandDirectory.name())
                    .contains(commandDirectory.expectedMessage())
                    .doesNotContain("WOULD install command", "Command PATH setup");
        }
    }

    @Test
    void posixUninstallDryRunPrintsPlannedWorkerStopsWithoutStopping() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        Path home = temporaryDirectory.resolve("would-stop-home");
        Path symphonyHome = temporaryDirectory.resolve("would-stop-symphony-home");
        Path app = symphonyHome.resolve("app");
        Path stateHome = symphonyHome.resolve("state");
        Files.createDirectories(home);
        Files.createDirectories(stateHome);
        Files.createDirectories(app.resolve("target").resolve("quarkus-app"));
        Files.writeString(app.resolve(".symphony-trello-install"), "marker", StandardCharsets.UTF_8);
        String markerArgument =
                "-Dsymphony.trello.managed.app_home=" + app.toAbsolutePath().normalize();
        String jarArgument = app.toAbsolutePath()
                .normalize()
                .resolve("target")
                .resolve("quarkus-app")
                .resolve("quarkus-run.jar")
                .toString();
        Process worker = new ProcessBuilder(
                        "bash", "-c", "while :; do sleep 1; done", "--", markerArgument, jarArgument)
                .start();
        try {
            Files.writeString(
                    stateHome.resolve("WORKFLOW.would-stop.md.abcdef123456.pid"),
                    Long.toString(worker.pid()),
                    StandardCharsets.UTF_8);
            Map<String, String> environment = Map.of("HOME", home.toString(), "SYMPHONY_HOME", symphonyHome.toString());

            // when
            ProcessResult result =
                    runUnchecked(environment, "bash", "uninstall.sh", "--dry-run", "--yes", "--prefix", app.toString());

            // then
            assertThat(result.exitCode()).as(result.output()).isZero();
            assertThat(result.output())
                    .contains("WOULD STOP  WORKFLOW.would-stop.md pid=" + worker.pid())
                    .doesNotContain("WOULD STOP  WORKFLOW.would-stop.md.")
                    .doesNotContain("\n  STOP  ");
            assertThat(worker.isAlive()).as("dry run must not stop the worker").isTrue();
        } finally {
            worker.destroyForcibly();
        }
    }

    @Test
    void posixUninstallSkipsStaleUnmanagedPidWithHashFreeLabelWithoutStoppingIt() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        Path home = temporaryDirectory.resolve("stale-skip-home");
        Path symphonyHome = temporaryDirectory.resolve("stale-skip-symphony-home");
        Path app = symphonyHome.resolve("app");
        Path stateHome = symphonyHome.resolve("state");
        Files.createDirectories(home);
        Files.createDirectories(stateHome);
        Files.createDirectories(app.resolve("target").resolve("quarkus-app"));
        Files.writeString(app.resolve(".symphony-trello-install"), "marker", StandardCharsets.UTF_8);
        Process unmanaged = new ProcessBuilder("bash", "-c", "while :; do sleep 1; done").start();
        try {
            Files.writeString(
                    stateHome.resolve("WORKFLOW.stale-skip.md.abcdef123456.pid"),
                    Long.toString(unmanaged.pid()),
                    StandardCharsets.UTF_8);
            Map<String, String> environment = Map.of("HOME", home.toString(), "SYMPHONY_HOME", symphonyHome.toString());

            // when
            ProcessResult result =
                    runUnchecked(environment, "bash", "uninstall.sh", "--yes", "--prefix", app.toString());

            // then
            assertThat(result.exitCode()).as(result.output()).isZero();
            assertThat(result.output())
                    .contains("SKIP  stale pid does not belong to this install: WORKFLOW.stale-skip.md pid="
                            + unmanaged.pid())
                    .doesNotContain("WORKFLOW.stale-skip.md.abcdef123456");
            assertThat(unmanaged.isAlive())
                    .as("an unmanaged process must never be stopped")
                    .isTrue();
        } finally {
            unmanaged.destroyForcibly();
        }
    }

    @Test
    void posixUninstallerRejectsUnsafeCommandDirectoriesBeforeDryRunPlan() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        Path home = temporaryDirectory.resolve("uninstall-bin-home");
        Path symphonyHome = temporaryDirectory.resolve("uninstall-bin-symphony-home");
        Path app = temporaryDirectory.resolve("uninstall-bin-app");
        Path file = temporaryDirectory.resolve("uninstall-bin-file");
        Path symlink = temporaryDirectory.resolve("uninstall-bin-state-link");
        Files.createDirectories(home);
        Files.createDirectories(symphonyHome.resolve("state"));
        Files.writeString(file, "not a directory", StandardCharsets.UTF_8);
        Files.createSymbolicLink(symlink, symphonyHome.resolve("state"));
        Map<String, String> environment = Map.of("HOME", home.toString(), "SYMPHONY_HOME", symphonyHome.toString());

        List<UnsafeCommandDirectory> cases = posixUnsafeCommandDirectories(
                home,
                file,
                symlink,
                "uninstall-bin-state-link",
                new UnsafeCommandDirectory(
                        "state-overlap",
                        symphonyHome.resolve("state").toString(),
                        "--bin-dir must not overlap Symphony app, config, workspace, or state directories."));
        List<UnsafeCommandDirectory> rejectedCases = cases.stream()
                .filter(InstallerScriptTest::unsafeUninstallCommandDirectoryCase)
                .toList();

        // when
        List<ProcessResult> results = rejectedCases.stream()
                .map(commandDirectory -> runUnchecked(
                        environment,
                        "bash",
                        "uninstall.sh",
                        "--dry-run",
                        "--yes",
                        "--prefix",
                        app.toString(),
                        "--bin-dir",
                        commandDirectory.value()))
                .toList();

        // then
        for (int index = 0; index < rejectedCases.size(); index++) {
            UnsafeCommandDirectory commandDirectory = rejectedCases.get(index);
            ProcessResult result = results.get(index);
            assertThat(result.exitCode()).as(commandDirectory.name()).isEqualTo(2);
            assertThat(result.output())
                    .as(commandDirectory.name())
                    .contains(commandDirectory.expectedMessage())
                    .doesNotContain("Installed CLI:", "Will remove if present:");
        }
    }

    @Test
    void posixUninstallerAllowsLegacySymlinkedCommandDirectory() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        Path home = temporaryDirectory.resolve("uninstall-symlink-bin-home");
        Path app = temporaryDirectory.resolve("uninstall-symlink-bin-app");
        Path realBin = temporaryDirectory.resolve("uninstall-symlink-bin-target");
        Path symlinkBin = temporaryDirectory.resolve("uninstall-symlink-bin-link");
        Files.createDirectories(home);
        Files.createDirectories(realBin);
        Files.createSymbolicLink(symlinkBin, realBin);
        Map<String, String> environment = Map.of("HOME", home.toString());

        // when
        ProcessResult result = run(
                environment,
                "bash",
                "uninstall.sh",
                "--dry-run",
                "--yes",
                "--prefix",
                app.toString(),
                "--bin-dir",
                symlinkBin.toString());

        // then
        assertThat(result.exitCode()).as(result.output()).isZero();
        assertThat(result.output()).contains("Installed CLI:", "Will remove if present:");
    }

    @Test
    void posixInstallerRejectsUnsafeAppPathsBeforeDryRunPlan() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        Path home = temporaryDirectory.resolve("install-app-home");
        Path symphonyHome = temporaryDirectory.resolve("install-app-symphony-home");
        Path file = temporaryDirectory.resolve("install-app-file");
        Path symlink = temporaryDirectory.resolve("install-app-link");
        Path safeBin = temporaryDirectory.resolve("install-app-bin");
        Files.createDirectories(home);
        Files.writeString(file, "not a directory", StandardCharsets.UTF_8);
        Files.createSymbolicLink(symlink, temporaryDirectory.resolve("install-app-target"));
        Map<String, String> environment = Map.of("HOME", home.toString(), "SYMPHONY_HOME", symphonyHome.toString());

        List<UnsafeInstallerPath> cases = posixUnsafeAppPaths(
                home,
                file,
                symlink,
                "install-app-link",
                new UnsafeInstallerPath(
                        "local-data-overlap",
                        symphonyHome.toString(),
                        "--prefix must not overlap Symphony config, workspace, or state directories."));

        // when
        List<ProcessResult> results =
                runPosixAppPathCases(environment, "install.sh", List.of("--dry-run", "--no-onboard"), cases, safeBin);

        // then
        assertUnsafeAppPathFailures(
                cases, results, OutputStyle.RAW, "Install:", "WOULD clone or update:", "Command PATH setup");
    }

    @Test
    void posixInstallerAcceptsDedicatedAppPathDirectlyUnderHome() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        Path home = temporaryDirectory.resolve("install-home-child-home");
        Path app = home.resolve("symphony-trello");
        Path safeBin = temporaryDirectory.resolve("install-home-child-bin");
        Path fakeBin = createFakeToolchain(temporaryDirectory);
        Files.createDirectories(home);
        Map<String, String> environment = Map.of(
                "HOME",
                home.toString(),
                "PATH",
                fakeBin + File.pathSeparator + System.getenv("PATH"),
                "SYMPHONY_TRELLO_TEST_OS",
                "Linux",
                "SYMPHONY_TRELLO_TEST_ARCH",
                "x86_64");

        // when
        ProcessResult result = run(
                environment,
                "bash",
                "install.sh",
                "--dry-run",
                "--no-onboard",
                "--prefix",
                app.toString(),
                "--bin-dir",
                safeBin.toString());

        // then
        result.assertSuccess();
        assertThat(result.output()).contains("Install: " + app, "WOULD unpack release archive into: " + app);
    }

    @Test
    void posixInstallerRejectsAppPathInsideRootScopedConfigDirectory() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        Path home = temporaryDirectory.resolve("install-root-config-home");
        Path app = home.resolve(".local/share/symphony-trello/app");
        Path safeBin = temporaryDirectory.resolve("install-root-config-bin");
        Map<String, String> environment = Map.of("HOME", home.toString(), "SYMPHONY_TRELLO_CONFIG_DIR", "/");

        // when
        ProcessResult result = run(
                environment,
                "bash",
                "install.sh",
                "--dry-run",
                "--no-onboard",
                "--prefix",
                app.toString(),
                "--bin-dir",
                safeBin.toString());

        // then
        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.output())
                .contains("--prefix must not overlap Symphony config, workspace, or state directories.")
                .doesNotContain("WOULD clone or update:", "Dry run: no files changed.");
    }

    @Test
    void posixInstallerRejectsUnsafeSymphonyHomeBeforeDryRunPlan() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        Path home = temporaryDirectory.resolve("install-home-base-home");
        Path safeBin = temporaryDirectory.resolve("install-home-base-bin");
        Files.createDirectories(home);

        List<UnsafeInstallerPath> cases = List.of(
                new UnsafeInstallerPath("root", "/", "--prefix must point to a dedicated app checkout directory."),
                new UnsafeInstallerPath(
                        "root-home", "/root", "--prefix must point to a dedicated app checkout directory."),
                new UnsafeInstallerPath(
                        "home", home.toString(), "--prefix must point to a dedicated app checkout directory."),
                new UnsafeInstallerPath(
                        "control", temporaryDirectory.resolve("home\nline").toString(), "control characters"));

        // when
        List<ProcessResult> results = cases.stream()
                .map(appPath -> {
                    Map<String, String> environment = Map.of("HOME", home.toString(), "SYMPHONY_HOME", appPath.value());
                    return runUnchecked(
                            environment,
                            "bash",
                            "install.sh",
                            "--dry-run",
                            "--no-onboard",
                            "--bin-dir",
                            safeBin.toString());
                })
                .toList();

        // then
        for (int index = 0; index < cases.size(); index++) {
            UnsafeInstallerPath appPath = cases.get(index);
            ProcessResult result = results.get(index);
            assertThat(result.exitCode()).as(appPath.name()).isEqualTo(2);
            assertThat(result.output())
                    .as(appPath.name())
                    .contains(appPath.expectedMessage())
                    .doesNotContain("Install:", "WOULD clone or update:", "Command PATH setup");
        }
    }

    @Test
    void posixUninstallerRejectsUnsafeAppPathsBeforeDryRunPlan() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        Path home = temporaryDirectory.resolve("uninstall-app-home");
        Path symphonyHome = temporaryDirectory.resolve("uninstall-app-symphony-home");
        Path file = temporaryDirectory.resolve("uninstall-app-file");
        Path symlink = temporaryDirectory.resolve("uninstall-app-link");
        Path safeBin = temporaryDirectory.resolve("uninstall-app-bin");
        Files.createDirectories(home);
        Files.writeString(file, "not a directory", StandardCharsets.UTF_8);
        Files.createSymbolicLink(symlink, temporaryDirectory.resolve("uninstall-app-target"));
        Map<String, String> environment = Map.of("HOME", home.toString(), "SYMPHONY_HOME", symphonyHome.toString());

        List<UnsafeInstallerPath> cases = posixUnsafeAppPaths(home, file, symlink, "uninstall-app-link");

        // when
        List<ProcessResult> results =
                runPosixAppPathCases(environment, "uninstall.sh", List.of("--dry-run", "--yes"), cases, safeBin);

        // then
        assertUnsafeAppPathFailures(
                cases, results, OutputStyle.RAW, "App checkout:", "Will remove if present:", "Installed CLI:");
    }

    @Test
    void posixUninstallerAcceptsDedicatedAppPathDirectlyUnderHome() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        Path home = temporaryDirectory.resolve("uninstall-home-child-home");
        Path app = home.resolve("symphony-trello");
        Path safeBin = temporaryDirectory.resolve("uninstall-home-child-bin");
        Files.createDirectories(home);
        Map<String, String> environment = Map.of("HOME", home.toString());

        // when
        ProcessResult result = run(
                environment,
                "bash",
                "uninstall.sh",
                "--dry-run",
                "--yes",
                "--prefix",
                app.toString(),
                "--bin-dir",
                safeBin.toString());

        // then
        result.assertSuccess();
        assertThat(result.output()).contains("App checkout: " + app, "Will remove if present:");
    }

    @Test
    void posixInstallerAllowsDefaultCommandDirectoryWhenRunFromThatDirectory() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        Path installScript = Path.of("install.sh").toAbsolutePath();
        Path home = temporaryDirectory.resolve("install-run-from-bin-home");
        Path binDirectory = home.resolve(".local/bin");
        Files.createDirectories(binDirectory);
        Map<String, String> environment = Map.of("HOME", home.toString());

        // when
        ProcessResult result =
                run(environment, binDirectory, "bash", installScript.toString(), "--dry-run", "--no-onboard");

        // then
        result.assertSuccess();
        assertThat(result.output()).contains("Dry run: no files changed.");
    }

    @Test
    void posixUninstallerAllowsDefaultCommandDirectoryWhenRunFromThatDirectory() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        Path uninstallScript = Path.of("uninstall.sh").toAbsolutePath();
        Path home = temporaryDirectory.resolve("uninstall-run-from-bin-home");
        Path binDirectory = home.resolve(".local/bin");
        Files.createDirectories(binDirectory);
        Map<String, String> environment = Map.of("HOME", home.toString());

        // when
        ProcessResult result = run(environment, binDirectory, "bash", uninstallScript.toString(), "--dry-run", "--yes");

        // then
        result.assertSuccess();
        assertThat(result.output()).contains("Trello boards were not deleted or archived.");
    }

    @Test
    void posixUninstallerRejectsAppPathInsideRootScopedConfigDirectory() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        Path home = temporaryDirectory.resolve("uninstall-root-config-home");
        Path app = home.resolve(".local/share/symphony-trello/app");
        Path safeBin = temporaryDirectory.resolve("uninstall-root-config-bin");
        Map<String, String> environment = Map.of("HOME", home.toString(), "SYMPHONY_TRELLO_CONFIG_DIR", "/");

        // when
        ProcessResult result = run(
                environment,
                "bash",
                "uninstall.sh",
                "--dry-run",
                "--yes",
                "--prefix",
                app.toString(),
                "--bin-dir",
                safeBin.toString());

        // then
        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.output())
                .contains("--bin-dir must not overlap Symphony app, config, workspace, or state directories.")
                .doesNotContain("Will remove if present:", "Trello boards were not deleted or archived.");
    }

    @Test
    void posixInstallerRejectsMissingHomeBeforeResolvingDefaultPaths() throws Exception {
        // given
        assumeTrue(commandExists("bash"));

        // when
        ProcessResult result = runWithoutHome(
                "bash",
                "install.sh",
                "--dry-run",
                "--no-onboard",
                "--prefix",
                temporaryDirectory.resolve("app").toString(),
                "--bin-dir",
                temporaryDirectory.resolve("bin").toString());

        // then
        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.output())
                .contains("HOME must be set to a user home directory before running the installer.")
                .doesNotContain("unbound variable", "/.local/share/symphony-trello");
    }

    @Test
    void posixInstallerRejectsEmptyHomeBeforeResolvingDefaultPaths() throws Exception {
        // given
        assumeTrue(commandExists("bash"));

        // when
        ProcessResult result = run(
                Map.of("HOME", ""),
                "bash",
                "install.sh",
                "--dry-run",
                "--no-onboard",
                "--prefix",
                temporaryDirectory.resolve("app").toString(),
                "--bin-dir",
                temporaryDirectory.resolve("bin").toString());

        // then
        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.output())
                .contains("HOME must be set to a user home directory before running the installer.")
                .doesNotContain("/.local/share/symphony-trello");
    }

    @Test
    void posixUninstallerRejectsMissingHomeBeforeResolvingDefaultPaths() throws Exception {
        // given
        assumeTrue(commandExists("bash"));

        // when
        ProcessResult result = runWithoutHome(
                "bash",
                "uninstall.sh",
                "--dry-run",
                "--yes",
                "--prefix",
                temporaryDirectory.resolve("app").toString(),
                "--bin-dir",
                temporaryDirectory.resolve("bin").toString());

        // then
        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.output())
                .contains("HOME must be set to a user home directory before running the uninstaller.")
                .doesNotContain("unbound variable", "/.local/share/symphony-trello");
    }

    @Test
    void posixUninstallerRejectsEmptyHomeBeforeResolvingDefaultPaths() throws Exception {
        // given
        assumeTrue(commandExists("bash"));

        // when
        ProcessResult result = run(
                Map.of("HOME", ""),
                "bash",
                "uninstall.sh",
                "--dry-run",
                "--yes",
                "--prefix",
                temporaryDirectory.resolve("app").toString(),
                "--bin-dir",
                temporaryDirectory.resolve("bin").toString());

        // then
        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.output())
                .contains("HOME must be set to a user home directory before running the uninstaller.")
                .doesNotContain("/.local/share/symphony-trello");
    }

    @MethodSource("invalidPosixInstallerSourceInputs")
    @ParameterizedTest(name = "{0}")
    void posixInstallerRejectsInvalidSourceInputsBeforeDryRunPlan(
            String name, String[] sourceArgs, String expectedMessage) throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        Path prefix = temporaryDirectory
                .resolve(name.replaceAll("[^A-Za-z0-9]+", "-"))
                .resolve("app");
        String[] command = Stream.concat(
                        Stream.of("bash", "install.sh", "--dry-run", "--no-onboard", "--prefix", prefix.toString()),
                        Stream.of(sourceArgs))
                .toArray(String[]::new);

        // when
        ProcessResult result = run(Map.of(), command);

        // then
        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.output()).contains(expectedMessage).doesNotContain("WOULD clone or update:");
        assertThat(prefix).doesNotExist();
    }

    private static Stream<Arguments> invalidPosixInstallerSourceInputs() {
        return Stream.of(
                Arguments.of("blank repo", new String[] {"--repo", ""}, "--repo must not be blank."),
                Arguments.of("option-looking repo", new String[] {"--repo", "--prefix"}, "Missing value for --repo"),
                Arguments.of(
                        "option-looking repo with following token",
                        new String[] {"--repo", "--prefix", "/tmp/ignored"},
                        "Missing value for --repo"),
                Arguments.of(
                        "malformed repo",
                        new String[] {"--repo", "not-a-url"},
                        "--repo must be a URL or existing local Git repository path."),
                Arguments.of("blank ref", new String[] {"--ref", ""}, "--ref must not be blank."),
                Arguments.of(
                        "option-looking ref with following token",
                        new String[] {"--ref", "--prefix", "/tmp/ignored"},
                        "Missing value for --ref"),
                Arguments.of(
                        "path traversal ref",
                        new String[] {"--ref", "../main"},
                        "--ref must be a branch, tag, or commit without Git namespace prefixes or path traversal."),
                Arguments.of(
                        "remote namespace ref",
                        new String[] {"--ref", "origin/main"},
                        "--ref must be a branch, tag, or commit without Git namespace prefixes or path traversal."),
                Arguments.of(
                        "full namespace ref",
                        new String[] {"--ref", "refs/heads/main"},
                        "--ref must be a branch, tag, or commit without Git namespace prefixes or path traversal."),
                Arguments.of(
                        "lock component ref",
                        new String[] {"--ref", "foo.lock/bar"},
                        "--ref must be a branch, tag, or commit without Git namespace prefixes or path traversal."),
                Arguments.of(
                        "trailing dot ref",
                        new String[] {"--ref", "foo."},
                        "--ref must be a branch, tag, or commit without Git namespace prefixes or path traversal."),
                Arguments.of(
                        "unsupported ref characters",
                        new String[] {"--ref", "main;echo-x"},
                        "--ref contains unsupported characters."),
                Arguments.of(
                        "control character ref",
                        new String[] {"--ref", "main\nother"},
                        "--ref must not contain whitespace or control characters."));
    }

    @Test
    void posixInstallerAcceptsScpStyleRepoAndRedactsCredentialUrlBeforeDryRunPlan() throws Exception {
        // given
        assumeTrue(commandExists("bash"));

        // when
        ProcessResult scpRepo = run(
                Map.of(),
                "bash",
                "install.sh",
                "--dry-run",
                "--no-onboard",
                "--repo",
                "deploy@example.com:org/repo.git");
        ProcessResult credentialRepo = run(
                Map.of(),
                "bash",
                "install.sh",
                "--dry-run",
                "--no-onboard",
                "--repo",
                "https://user:secret-token@example.com/org/repo.git");
        ProcessResult buildMetadataRef =
                run(Map.of(), "bash", "install.sh", "--dry-run", "--no-onboard", "--ref", "v1.2.3+hotfix.1");

        // then
        scpRepo.assertSuccess();
        assertThat(scpRepo.output()).contains("Repository: deploy@example.com:org/repo.git");
        credentialRepo.assertSuccess();
        assertThat(credentialRepo.output())
                .contains("Repository: https://<redacted>@example.com/org/repo.git")
                .doesNotContain("user:secret-token");
        buildMetadataRef.assertSuccess();
        assertThat(buildMetadataRef.output()).contains("Ref: v1.2.3+hotfix.1");
    }

    @Test
    void posixInstallerRejectsExistingNonGitRepoPathBeforeDryRunPlan() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        Path notGitRepository = temporaryDirectory.resolve("not-git-repository");
        Path prefix = temporaryDirectory.resolve("non-git-repo-prefix").resolve("app");
        Files.createDirectories(notGitRepository);

        // when
        ProcessResult result = run(
                Map.of(),
                "bash",
                "install.sh",
                "--dry-run",
                "--no-onboard",
                "--prefix",
                prefix.toString(),
                "--repo",
                notGitRepository.toString());

        // then
        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.output())
                .contains("--repo must be a URL or existing local Git repository path.")
                .doesNotContain("WOULD clone or update:");
        assertThat(prefix).doesNotExist();
    }

    @Test
    void posixInstallerAcceptsLocalGitRepoPathWithSpacesBeforeDryRunPlan() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        Path localRepository = temporaryDirectory.resolve("local repo with spaces");
        Path prefix = temporaryDirectory.resolve("local-repo-space-prefix").resolve("app");
        Files.createDirectories(localRepository.resolve(".git"));

        // when
        ProcessResult result = run(
                Map.of(),
                "bash",
                "install.sh",
                "--dry-run",
                "--no-onboard",
                "--prefix",
                prefix.toString(),
                "--repo",
                localRepository.toString());

        // then
        result.assertSuccess();
        assertThat(result.output()).contains("Repository: " + localRepository, "WOULD clone or update:");
        assertThat(prefix).doesNotExist();
    }

    @Test
    void posixInstallerRedactsCredentialRepoUrlInRealGitStepLabels() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        Path fakeBin = createFakeToolchain(temporaryDirectory);
        Path prefix = temporaryDirectory.resolve("credential-repo-label-prefix").resolve("app");
        Path binDirectory = temporaryDirectory.resolve("credential-repo-label-bin");
        Path fakeLog = temporaryDirectory.resolve("credential-repo-label.log");
        writeExecutable(
                fakeBin.resolve("git"),
                """
                #!/usr/bin/env bash
                set -euo pipefail
                case "${1:-}" in
                  ls-remote) exit 1 ;;
                  clone)
                    app="${@: -1}"
                    mkdir -p "$app/.git" "$app/target/quarkus-app"
                    printf '#!/usr/bin/env bash\\nexit 0\\n' > "$app/mvnw"
                    chmod +x "$app/mvnw"
                    exit 0
                    ;;
                  -C) exit 0 ;;
                  *) exit 0 ;;
                esac
                """);
        Map<String, String> environment = Map.of(
                "PATH",
                fakeBin + File.pathSeparator + System.getenv("PATH"),
                "SYMPHONY_TRELLO_TEST_OS",
                "Linux",
                "SYMPHONY_TRELLO_TEST_ARCH",
                "x86_64",
                "SYMPHONY_FAKE_LOG",
                fakeLog.toString());

        // when
        ProcessResult result = run(
                environment,
                "bash",
                "install.sh",
                "--no-onboard",
                "--prefix",
                prefix.toString(),
                "--bin-dir",
                binDirectory.toString(),
                "--repo",
                "https://user:secret-token@example.com/org/repo.git");

        // then
        result.assertSuccess();
        assertThat(result.output())
                .contains("Repository: https://<redacted>@example.com/org/repo.git")
                .doesNotContain("user:secret-token");
    }

    @Test
    void posixInstallerDryRunReportsConcreteMissingPrerequisiteActions() throws Exception {
        // given
        assumeTrue(Files.exists(Path.of("/bin/bash")));
        Map<String, String> environment = Map.of(
                "PATH", temporaryDirectory.resolve("empty-bin").toString(),
                "SYMPHONY_TRELLO_TEST_OS", "Linux",
                "SYMPHONY_TRELLO_TEST_ARCH", "x86_64");

        // when
        ProcessResult result = run(environment, "/bin/bash", "install.sh", "--dry-run");

        // then
        assertThat(result.exitCode()).isZero();
        assertThat(result.output())
                .contains(
                        "Install source: release-archive",
                        "Version: " + installerDefaultRef().substring(1),
                        "WOULD download release archive:",
                        "WOULD verify SHA3-256 checksum from:",
                        "WOULD offer to install Java 25+ JDK",
                        "WOULD offer to install Codex CLI with Symphony-managed npm:",
                        "Node.js/npm installed: no");
    }

    @Test
    void posixInstallerNormalizesLeadingVVersionBeforeReleaseAssetUrls() throws Exception {
        // given
        assumeTrue(Files.exists(Path.of("/bin/bash")));
        String version = installerDefaultRef().substring(1);
        Map<String, String> environment = Map.of(
                "PATH",
                temporaryDirectory.resolve("empty-bin").toString(),
                "SYMPHONY_TRELLO_TEST_OS",
                "Linux",
                "SYMPHONY_TRELLO_TEST_ARCH",
                "x86_64",
                "SYMPHONY_TRELLO_VERSION",
                "v" + version);

        // when
        ProcessResult result = run(environment, "/bin/bash", "install.sh", "--dry-run", "--no-onboard");

        // then
        result.assertSuccess();
        assertThat(result.output())
                .contains(
                        "Version: " + version,
                        "releases/download/v" + version + "/symphony-trello-" + version + ".tar.gz",
                        "releases/download/v" + version + "/checksums.txt")
                .doesNotContain("vv" + version);
    }

    @Test
    void posixInstallerRejectsRemovedUpdatePathOption() throws Exception {
        // given
        assumeTrue(Files.exists(Path.of("/bin/bash")));

        // when
        ProcessResult result = run(Map.of(), "/bin/bash", "install.sh", "--dry-run", "--update-path", "--no-onboard");

        // then
        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.output()).contains("Unknown option: --update-path");
    }

    @Test
    void posixInstallerNoOnboardFailsCleanlyWithoutTerminalWhenPrerequisitePromptWouldRun() throws Exception {
        // given
        assumeTrue(Files.exists(Path.of("/bin/bash")));
        Path fakeBin = temporaryDirectory.resolve("noninteractive-bin");
        Files.createDirectories(fakeBin);
        writeExecutable(fakeBin.resolve("git"), "#!/usr/bin/env bash\nexit 0\n");
        writeExecutable(fakeBin.resolve("apt-get"), "#!/usr/bin/env bash\nexit 0\n");
        Map<String, String> environment = Map.of(
                "PATH", fakeBin + File.pathSeparator + "/bin",
                "SYMPHONY_TRELLO_TEST_OS", "Linux",
                "SYMPHONY_TRELLO_TEST_ARCH", "x86_64");

        // when
        ProcessResult result = run(environment, "/bin/bash", "install.sh", "--no-onboard");

        // then
        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.output())
                .contains(
                        "Java 25+ JDK is missing.",
                        "This step needs an interactive terminal.",
                        "install the missing prerequisite manually first")
                .doesNotContain("/dev/tty: No such device or address", "pass --no-onboard");
    }

    @Test
    void posixInstallerDoesNotOfferPathSetupWhenBinDirIsAlreadyOnPath() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        assumeTrue(commandExists("git"));
        Path sourceRepository = createSourceRepository(temporaryDirectory);
        Path fakeBin = createFakeToolchain(temporaryDirectory);
        Path symphonyHome = temporaryDirectory.resolve("path-present-home");
        Path binDirectory = temporaryDirectory.resolve("path-present-bin");
        Path fakeLog = temporaryDirectory.resolve("path-present.log");
        Map<String, String> environment = Map.of(
                "PATH", fakeBin + File.pathSeparator + binDirectory + File.pathSeparator + System.getenv("PATH"),
                "SYMPHONY_TRELLO_REPO_URL", sourceRepository.toUri().toString(),
                "SYMPHONY_HOME", symphonyHome.toString(),
                "SYMPHONY_FAKE_LOG", fakeLog.toString());

        // when
        ProcessResult result =
                run(environment, "bash", "install.sh", "--no-onboard", "--bin-dir", binDirectory.toString());

        // then
        result.assertSuccess();
        assertThat(result.output()).doesNotContain("Command PATH setup", "not on PATH");
    }

    @Test
    void posixInstallerAddsPathSetupByDefaultWithoutDuplicates() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        assumeTrue(commandExists("git"));
        assumeTrue(commandExists("script"));
        Path installScript = Path.of("install.sh").toAbsolutePath();
        Path sourceRepository = createSourceRepository(temporaryDirectory);
        Path fakeBin = createFakeToolchain(temporaryDirectory);
        Path home = temporaryDirectory.resolve("path-accepted-home");
        Path symphonyHome = temporaryDirectory.resolve("path-accepted-symphony-home");
        Path binDirectory = temporaryDirectory.resolve("path-accepted-bin");
        Path fakeLog = temporaryDirectory.resolve("path-accepted.log");
        Files.createDirectories(home);
        Map<String, String> environment = Map.of(
                "PATH", fakeBin + File.pathSeparator + System.getenv("PATH"),
                "HOME", home.toString(),
                "SHELL", "/bin/bash",
                "SYMPHONY_TRELLO_REPO_URL", sourceRepository.toUri().toString(),
                "SYMPHONY_HOME", symphonyHome.toString(),
                "SYMPHONY_FAKE_LOG", fakeLog.toString());

        // when
        ProcessResult firstInstall = runWithPseudoTerminal(
                environment,
                "",
                "bash " + shellQuote(installScript.toString()) + " --no-onboard --bin-dir "
                        + shellQuote(binDirectory.toString()));
        ProcessResult secondInstall = runWithPseudoTerminal(
                environment,
                "",
                "bash " + shellQuote(installScript.toString()) + " --no-onboard --bin-dir "
                        + shellQuote(binDirectory.toString()));

        // then
        firstInstall.assertSuccess();
        secondInstall.assertSuccess();
        Path profile = home.resolve(".bashrc");
        Path loginProfile = home.resolve(".profile");
        String expectedLine = "export PATH='" + binDirectory + "':\"$PATH\"";
        assertThat(firstInstall.output())
                .contains(
                        "Command PATH setup",
                        "Added " + binDirectory + " to PATH in " + profile,
                        "Added " + binDirectory + " to PATH in " + loginProfile);
        assertThat(secondInstall.output()).contains("PATH setup already exists in " + profile);
        assertThat(Files.readString(profile, StandardCharsets.UTF_8))
                .contains("# >>> Symphony for Trello PATH >>>", expectedLine, "# <<< Symphony for Trello PATH <<<")
                .containsOnlyOnce(expectedLine);
        assertThat(Files.readString(loginProfile, StandardCharsets.UTF_8))
                .contains("# >>> Symphony for Trello PATH >>>", expectedLine, "# <<< Symphony for Trello PATH <<<")
                .containsOnlyOnce(expectedLine);
    }

    @Test
    void posixPipedInstallerAddsPathSetupByDefault() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        assumeTrue(commandExists("git"));
        assumeTrue(commandExists("script"));
        Path installScript = Path.of("install.sh").toAbsolutePath();
        Path sourceRepository = createSourceRepository(temporaryDirectory);
        Path fakeBin = createFakeToolchain(temporaryDirectory);
        Path home = temporaryDirectory.resolve("path-piped-home");
        Path symphonyHome = temporaryDirectory.resolve("path-piped-symphony-home");
        Path binDirectory = temporaryDirectory.resolve("path-piped-bin");
        Path fakeLog = temporaryDirectory.resolve("path-piped.log");
        Files.createDirectories(home);
        Map<String, String> environment = Map.of(
                "PATH", fakeBin + File.pathSeparator + System.getenv("PATH"),
                "HOME", home.toString(),
                "SHELL", "/bin/bash",
                "SYMPHONY_TRELLO_REPO_URL", sourceRepository.toUri().toString(),
                "SYMPHONY_HOME", symphonyHome.toString(),
                "SYMPHONY_FAKE_LOG", fakeLog.toString());

        // when
        ProcessResult result = runWithPseudoTerminal(
                environment,
                "",
                "/bin/cat " + shellQuote(installScript.toString()) + " | bash -s -- --no-onboard --bin-dir "
                        + shellQuote(binDirectory.toString()));

        // then
        result.assertSuccess();
        assertThat(result.output()).contains("Command PATH setup", "Added " + binDirectory + " to PATH");
        assertThat(home.resolve(".bashrc"))
                .content(StandardCharsets.UTF_8)
                .contains("export PATH='" + binDirectory + "':\"$PATH\"");
        assertThat(home.resolve(".profile"))
                .content(StandardCharsets.UTF_8)
                .contains("export PATH='" + binDirectory + "':\"$PATH\"");
    }

    @Test
    void posixInstallerAddsPathSetupBeforeGuidedSetupFailure() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        assumeTrue(commandExists("git"));
        assumeTrue(commandExists("script"));
        Path installScript = Path.of("install.sh").toAbsolutePath();
        Path sourceRepository = createSourceRepository(temporaryDirectory);
        Path fakeBin = createFakeToolchain(temporaryDirectory);
        writeExecutable(
                fakeBin.resolve("java"),
                """
                #!/usr/bin/env bash
                set -euo pipefail
                if [[ "${1:-}" == "-version" ]]; then
                  echo 'openjdk version "25.0.1" 2026-04-21' >&2
                  exit 0
                fi
                if [[ "$*" == *"ch.fmartin.symphony.trello.setup.TrelloBoardSetupMain setup-local"* ]]; then
                  echo "setup-local failed before PATH test" >&2
                  exit 7
                fi
                echo "java $*" >> "${SYMPHONY_FAKE_LOG:?}"
                exit 0
                """);
        Path home = temporaryDirectory.resolve("path-before-setup-failure-home");
        Path symphonyHome = temporaryDirectory.resolve("path-before-setup-failure-symphony-home");
        Path binDirectory = temporaryDirectory.resolve("path-before-setup-failure-bin");
        Path fakeLog = temporaryDirectory.resolve("path-before-setup-failure.log");
        Files.createFile(temporaryDirectory.resolve("codex-authenticated"));
        Files.createDirectories(home);
        Map<String, String> environment = Map.of(
                "PATH", fakeBin + File.pathSeparator + System.getenv("PATH"),
                "HOME", home.toString(),
                "SHELL", "/bin/bash",
                "SYMPHONY_TRELLO_REPO_URL", sourceRepository.toUri().toString(),
                "SYMPHONY_HOME", symphonyHome.toString(),
                "SYMPHONY_FAKE_LOG", fakeLog.toString());

        // when
        ProcessResult result = runWithPseudoTerminal(
                environment,
                "",
                "bash " + shellQuote(installScript.toString()) + " --bin-dir " + shellQuote(binDirectory.toString()));

        // then
        assertThat(result.exitCode()).as(result.output()).isEqualTo(7);
        assertThat(result.output())
                .containsSubsequence(
                        "OK  Command installed: " + binDirectory.resolve("symphony-trello"),
                        "Added " + binDirectory + " to PATH in " + home.resolve(".bashrc"),
                        "Added " + binDirectory + " to PATH in " + home.resolve(".profile"),
                        "Starting setup...",
                        "setup-local failed before PATH test");
        assertThat(home.resolve(".bashrc"))
                .content(StandardCharsets.UTF_8)
                .contains("export PATH='" + binDirectory + "':\"$PATH\"");
        assertThat(home.resolve(".profile"))
                .content(StandardCharsets.UTF_8)
                .contains("export PATH='" + binDirectory + "':\"$PATH\"");
    }

    @Test
    void posixInstallerGuidedSetupWithoutTerminalSuggestsNoOnboardWhenPrerequisitesExist() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        assumeTrue(commandExists("git"));
        Path sourceRepository = createSourceRepository(temporaryDirectory);
        Path fakeBin = createFakeToolchain(temporaryDirectory);
        Path home = temporaryDirectory.resolve("guided-no-tty-home");
        Path symphonyHome = temporaryDirectory.resolve("guided-no-tty-symphony-home");
        Path binDirectory = temporaryDirectory.resolve("guided-no-tty-bin");
        Path fakeLog = temporaryDirectory.resolve("guided-no-tty.log");
        Files.createFile(temporaryDirectory.resolve("codex-authenticated"));
        Files.createDirectories(home);
        Map<String, String> environment = Map.of(
                "PATH", fakeBin + File.pathSeparator + System.getenv("PATH"),
                "HOME", home.toString(),
                "SHELL", "/bin/bash",
                "SYMPHONY_TRELLO_REPO_URL", sourceRepository.toUri().toString(),
                "SYMPHONY_HOME", symphonyHome.toString(),
                "SYMPHONY_FAKE_LOG", fakeLog.toString());

        // when
        ProcessResult result = run(environment, "bash", "install.sh", "--bin-dir", binDirectory.toString());

        // then
        assertThat(result.exitCode()).as(result.output()).isEqualTo(2);
        assertThat(result.output())
                .contains("Starting setup...", "This step needs an interactive terminal.", "pass --no-onboard")
                .doesNotContain("install the missing prerequisite manually first");
    }

    @Test
    void posixInstallerLeavesProfileUnchangedWhenPathSetupIsDisabled() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        assumeTrue(commandExists("git"));
        assumeTrue(commandExists("script"));
        Path installScript = Path.of("install.sh").toAbsolutePath();
        Path sourceRepository = createSourceRepository(temporaryDirectory);
        Path fakeBin = createFakeToolchain(temporaryDirectory);
        Path home = temporaryDirectory.resolve("path-declined-home");
        Path symphonyHome = temporaryDirectory.resolve("path-declined-symphony-home");
        Path binDirectory = temporaryDirectory.resolve("path-declined-bin");
        Path fakeLog = temporaryDirectory.resolve("path-declined.log");
        Files.createDirectories(home);
        Map<String, String> environment = Map.of(
                "PATH", fakeBin + File.pathSeparator + System.getenv("PATH"),
                "HOME", home.toString(),
                "SHELL", "/bin/bash",
                "SYMPHONY_TRELLO_REPO_URL", sourceRepository.toUri().toString(),
                "SYMPHONY_HOME", symphonyHome.toString(),
                "SYMPHONY_FAKE_LOG", fakeLog.toString());

        // when
        ProcessResult result = runWithPseudoTerminal(
                environment,
                "",
                "bash " + shellQuote(installScript.toString()) + " --no-onboard --no-update-path --bin-dir "
                        + shellQuote(binDirectory.toString()));
        ProcessResult status =
                run(environment, binDirectory.resolve("symphony-trello").toString(), "status");

        // then
        result.assertSuccess();
        status.assertSuccess();
        assertThat(result.output())
                .contains(
                        "NOTE  " + binDirectory + " is not on PATH for this shell.",
                        "Suggested profile files:",
                        home.resolve(".bashrc").toString());
        assertThat(home.resolve(".bashrc")).doesNotExist();
        assertThat(home.resolve(".profile")).doesNotExist();
    }

    @Test
    void posixUninstallDryRunPlanSeparatesRemovalsFromPreservedData() throws Exception {
        // given
        assumeTrue(commandExists("bash"));

        // when
        String output = posixUninstallDryRunPlanOutput("uninstall-plan", "--remove-all-local-data");

        // then
        int removeSection = output.indexOf("Will remove if present:");
        int preserveSection = output.indexOf("Will preserve:");
        assertThat(removeSection).as(output).isNotNegative();
        assertThat(preserveSection).as(output).isGreaterThan(removeSection);
        String removePlan = output.substring(removeSection, preserveSection);
        String preservePlan = output.substring(preserveSection);
        assertThat(removePlan)
                .contains("CONFIG", "WORKSPACES", "STATE/LOGS", "WORKERS         Managed Symphony workers");
        assertThat(preservePlan)
                .contains("AUTH", "TRELLO")
                .doesNotContain("CONFIG          ", "WORKSPACES      ", "STATE/LOGS      ");
    }

    @Test
    void posixUninstallDryRunPreservesLocalDataByDefault() throws Exception {
        // given
        assumeTrue(commandExists("bash"));

        // when
        String output = posixUninstallDryRunPlanOutput("uninstall-default");

        // then
        int preserveSection = output.indexOf("Will preserve:");
        assertThat(preserveSection).as(output).isNotNegative();
        assertThat(output.substring(preserveSection)).contains("CONFIG", "WORKSPACES", "STATE/LOGS", "AUTH", "TRELLO");
        assertThat(output.substring(0, preserveSection)).doesNotContain("CONFIG          ");
    }

    private String posixUninstallDryRunPlanOutput(String prefix, String... extraFlags) throws Exception {
        Path home = temporaryDirectory.resolve(prefix + "-home");
        Path symphonyHome = temporaryDirectory.resolve(prefix + "-symphony-home");
        Path binDirectory = temporaryDirectory.resolve(prefix + "-bin");
        Files.createDirectories(home);
        Files.createDirectories(symphonyHome.resolve("state"));
        Map<String, String> environment = Map.of(
                "PATH", System.getenv("PATH"),
                "HOME", home.toString(),
                "SYMPHONY_HOME", symphonyHome.toString());
        List<String> command = new ArrayList<>(List.of("bash", "uninstall.sh", "--dry-run", "--yes"));
        command.addAll(List.of(extraFlags));
        command.addAll(List.of("--bin-dir", binDirectory.toString()));
        ProcessResult result = run(environment, command.toArray(String[]::new));
        result.assertSuccess();
        return result.output();
    }

    @Test
    void posixWrapperReportsActionableErrorWhenJavaIsMissing() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        assumeTrue(commandExists("git"));
        Path sourceRepository = createSourceRepository(temporaryDirectory);
        Path fakeBin = createFakeToolchain(temporaryDirectory);
        Path home = temporaryDirectory.resolve("missing-java-home");
        Path symphonyHome = temporaryDirectory.resolve("missing-java-symphony-home");
        Path binDirectory = temporaryDirectory.resolve("missing-java-bin");
        Path fakeLog = temporaryDirectory.resolve("missing-java.log");
        Files.createDirectories(home);
        Map<String, String> environment = Map.of(
                "PATH", fakeBin + File.pathSeparator + System.getenv("PATH"),
                "HOME", home.toString(),
                "SHELL", "/bin/bash",
                "SYMPHONY_TRELLO_REPO_URL", sourceRepository.toUri().toString(),
                "SYMPHONY_HOME", symphonyHome.toString(),
                "SYMPHONY_FAKE_LOG", fakeLog.toString());
        ProcessResult install = run(
                environment,
                "bash",
                "install.sh",
                "--no-onboard",
                "--no-update-path",
                "--bin-dir",
                binDirectory.toString());
        Path runtimeBin = createWrapperRuntimePathWithoutJava(temporaryDirectory);
        try {
            Map<String, String> environmentWithoutJava = Map.of(
                    "PATH",
                    runtimeBin.toString(),
                    "HOME",
                    home.toString(),
                    "SHELL",
                    "/bin/bash",
                    "SYMPHONY_HOME",
                    symphonyHome.toString());
            ProcessResult javaLookup =
                    run(environmentWithoutJava, runtimeBin.resolve("bash").toString(), "-c", "command -v java");

            // when
            ProcessResult status = run(
                    environmentWithoutJava,
                    binDirectory.resolve("symphony-trello").toString(),
                    "status");

            // then
            install.assertSuccess();
            assertThat(javaLookup.exitCode())
                    .as("the controlled runtime PATH must not resolve java, but found: %s", javaLookup.output())
                    .isNotZero();
            assertThat(status.exitCode()).as(status.output()).isEqualTo(2);
            assertThat(status.output())
                    .contains("Symphony for Trello needs Java 25+ on PATH", "rerun the installer")
                    .doesNotContain("exec: java: not found");
        } finally {
            // Remove the host-tool links before @TempDir cleanup: JUnit warns in the build log
            // whenever it deletes a symlink that resolves to a location outside the temp dir.
            deleteWrapperRuntimePathWithoutJava(runtimeBin);
        }
    }

    @Test
    void powerShellWrapperSourceGuardsMissingJavaBeforeLaunch() throws Exception {
        // given
        // Runtime coverage for the missing-java branch is POSIX-only: the PowerShell wrapper only
        // exists after a full install.ps1 run, and pwsh is not available in every verification
        // environment. This pins the generated wrapper text so the guard cannot silently drop.
        String installer = Files.readString(Path.of("install.ps1"), StandardCharsets.UTF_8);

        // when
        int javaGuard = installer.indexOf("if (-not (Get-Command java -ErrorAction SilentlyContinue))");
        int javaLaunch = installer.indexOf("& java \"-Dsymphony.trello.app.home=");

        // then
        assertThat(javaGuard)
                .as("generated PowerShell wrapper must guard the java lookup")
                .isNotNegative();
        assertThat(javaLaunch)
                .as("generated PowerShell wrapper must launch java")
                .isNotNegative();
        assertThat(javaGuard)
                .as("the java guard must run before the java launch")
                .isLessThan(javaLaunch);
        assertThat(installer)
                .contains(
                        "Symphony for Trello needs Java 25+ on PATH, but no java command was found.",
                        "Install a Java 25+ JDK or rerun the installer, then try again:");
    }

    /**
     * A runtime PATH for negative wrapper tests holding only the commands the installed POSIX
     * wrapper needs before its java lookup. Hosts commonly have /usr/bin/java or /bin/java, so a
     * host-directory PATH cannot prove the missing-java branch runs.
     */
    private static Path createWrapperRuntimePathWithoutJava(Path temporaryDirectory) throws IOException {
        Path runtimeBin = temporaryDirectory.resolve("runtime-bin-without-java");
        Files.createDirectories(runtimeBin);
        for (String tool : List.of("bash", "mkdir")) {
            Files.createSymbolicLink(runtimeBin.resolve(tool), hostTool(tool));
        }
        return runtimeBin;
    }

    private static void deleteWrapperRuntimePathWithoutJava(Path runtimeBin) throws IOException {
        try (Stream<Path> links = Files.list(runtimeBin)) {
            for (Path link : links.toList()) {
                Files.delete(link);
            }
        }
    }

    private static Path hostTool(String tool) {
        return Stream.of(System.getenv("PATH").split(File.pathSeparator))
                .filter(directory -> !directory.isBlank())
                .map(directory -> Path.of(directory).resolve(tool))
                .filter(Files::isExecutable)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Required tool is missing on the host PATH: " + tool));
    }

    @Test
    void posixNonInteractiveInstallerUpdatesProfileByDefault() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        assumeTrue(commandExists("git"));
        Path sourceRepository = createSourceRepository(temporaryDirectory);
        Path fakeBin = createFakeToolchain(temporaryDirectory);
        Path home = temporaryDirectory.resolve("path-noninteractive-home");
        Path symphonyHome = temporaryDirectory.resolve("path-noninteractive-symphony-home");
        Path binDirectory = temporaryDirectory.resolve("path-noninteractive-bin");
        Path fakeLog = temporaryDirectory.resolve("path-noninteractive.log");
        Files.createDirectories(home);
        Map<String, String> environment = Map.of(
                "PATH", fakeBin + File.pathSeparator + System.getenv("PATH"),
                "HOME", home.toString(),
                "SHELL", "/bin/bash",
                "SYMPHONY_TRELLO_REPO_URL", sourceRepository.toUri().toString(),
                "SYMPHONY_HOME", symphonyHome.toString(),
                "SYMPHONY_FAKE_LOG", fakeLog.toString());

        // when
        ProcessResult result =
                run(environment, "bash", "install.sh", "--no-onboard", "--bin-dir", binDirectory.toString());

        // then
        result.assertSuccess();
        assertThat(result.output())
                .contains(
                        "Added " + binDirectory + " to PATH in " + home.resolve(".bashrc"),
                        "Added " + binDirectory + " to PATH in " + home.resolve(".profile"));
        assertThat(home.resolve(".bashrc"))
                .content(StandardCharsets.UTF_8)
                .contains("export PATH='" + binDirectory + "':\"$PATH\"");
        assertThat(home.resolve(".profile"))
                .content(StandardCharsets.UTF_8)
                .contains("export PATH='" + binDirectory + "':\"$PATH\"");
    }

    @Test
    void posixUninstallerDryRunReportsManagedPathProfileCleanup() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        Path home = temporaryDirectory.resolve("path-cleanup-dry-run-home");
        Path symphonyHome = temporaryDirectory.resolve("path-cleanup-dry-run-symphony-home");
        Path binDirectory = temporaryDirectory.resolve("path-cleanup-dry-run-bin");
        Files.createDirectories(home);
        String pathLine = "export PATH='" + binDirectory + "':\"$PATH\"";
        String managedBlock =
                """
                before
                # >>> Symphony for Trello PATH >>>
                %s
                # <<< Symphony for Trello PATH <<<
                after
                """
                        .formatted(pathLine);
        Files.writeString(home.resolve(".bashrc"), managedBlock, StandardCharsets.UTF_8);
        Files.writeString(home.resolve(".profile"), managedBlock, StandardCharsets.UTF_8);
        Map<String, String> environment = Map.of(
                "HOME", home.toString(),
                "SHELL", "/bin/bash",
                "SYMPHONY_HOME", symphonyHome.toString());

        // when
        ProcessResult result =
                run(environment, "bash", "uninstall.sh", "--dry-run", "--yes", "--bin-dir", binDirectory.toString());

        // then
        result.assertSuccess();
        assertThat(result.output())
                .contains(
                        "WOULD remove managed PATH setup from " + home.resolve(".bashrc"),
                        "WOULD remove managed PATH setup from " + home.resolve(".profile"));
        assertThat(home.resolve(".bashrc")).content(StandardCharsets.UTF_8).isEqualTo(managedBlock);
        assertThat(home.resolve(".profile")).content(StandardCharsets.UTF_8).isEqualTo(managedBlock);
    }

    @Test
    void posixUninstallerKeepsProfileWithUnterminatedManagedPathBlock() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        Path home = temporaryDirectory.resolve("path-cleanup-corrupt-home");
        Path symphonyHome = temporaryDirectory.resolve("path-cleanup-corrupt-symphony-home");
        Path binDirectory = temporaryDirectory.resolve("path-cleanup-corrupt-bin");
        Files.createDirectories(home);
        String profile =
                """
                before
                # >>> Symphony for Trello PATH >>>
                export PATH='%s':"$PATH"
                alias kept='still here'
                """
                        .formatted(binDirectory);
        Files.writeString(home.resolve(".bashrc"), profile, StandardCharsets.UTF_8);
        Map<String, String> environment = Map.of(
                "HOME", home.toString(),
                "SHELL", "/bin/bash",
                "SYMPHONY_HOME", symphonyHome.toString());

        // when
        ProcessResult result = run(environment, "bash", "uninstall.sh", "--yes", "--bin-dir", binDirectory.toString());

        // then
        result.assertSuccess();
        assertThat(result.output())
                .contains("SKIP  managed PATH setup in " + home.resolve(".bashrc") + " is missing its end marker");
        assertThat(home.resolve(".bashrc")).content(StandardCharsets.UTF_8).isEqualTo(profile);
    }

    @Test
    void posixUninstallerKeepsManagedPathBlocksForDifferentBinDirectories() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        Path home = temporaryDirectory.resolve("path-cleanup-other-bin-home");
        Path symphonyHome = temporaryDirectory.resolve("path-cleanup-other-bin-symphony-home");
        Path binDirectory = temporaryDirectory.resolve("path-cleanup-other-bin-current");
        Path otherBinDirectory = temporaryDirectory.resolve("path-cleanup-other-bin-other");
        Files.createDirectories(home);
        String currentPathLine = "export PATH='" + binDirectory + "':\"$PATH\"";
        String otherPathLine = "export PATH='" + otherBinDirectory + "':\"$PATH\"";
        String profile =
                """
                before
                # >>> Symphony for Trello PATH >>>
                %s
                # <<< Symphony for Trello PATH <<<
                middle
                # >>> Symphony for Trello PATH >>>
                %s
                # <<< Symphony for Trello PATH <<<
                after
                """
                        .formatted(otherPathLine, currentPathLine);
        Files.writeString(home.resolve(".bashrc"), profile, StandardCharsets.UTF_8);

        // when
        ProcessResult result = runPosixUninstall(home, symphonyHome, binDirectory);

        // then
        result.assertSuccess();
        assertRemovedPathSetup(result, home);
        assertThat(home.resolve(".bashrc"))
                .content(StandardCharsets.UTF_8)
                .isEqualTo(
                        """
                        before
                        # >>> Symphony for Trello PATH >>>
                        %s
                        # <<< Symphony for Trello PATH <<<
                        middle
                        after
                        """
                                .formatted(otherPathLine));
    }

    @Test
    void posixUninstallerContinuesWhenManagedPathProfileCannotBeWritten() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        Path home = temporaryDirectory.resolve("path-cleanup-read-only-home");
        Path symphonyHome = temporaryDirectory.resolve("path-cleanup-read-only-symphony-home");
        Path binDirectory = temporaryDirectory.resolve("path-cleanup-read-only-bin");
        Files.createDirectories(home);
        String pathLine = "export PATH='" + binDirectory + "':\"$PATH\"";
        String profile =
                """
                before
                # >>> Symphony for Trello PATH >>>
                %s
                # <<< Symphony for Trello PATH <<<
                after
                """
                        .formatted(pathLine);
        Path shellProfile = home.resolve(".bashrc");
        Files.writeString(shellProfile, profile, StandardCharsets.UTF_8);
        assertThat(shellProfile.toFile().setWritable(false, false)).isTrue();
        assumeFalse(Files.isWritable(shellProfile));
        Map<String, String> environment = Map.of(
                "HOME", home.toString(),
                "SHELL", "/bin/bash",
                "SYMPHONY_HOME", symphonyHome.toString());

        // when
        ProcessResult result = run(environment, "bash", "uninstall.sh", "--yes", "--bin-dir", binDirectory.toString());

        // then
        result.assertSuccess();
        assertThat(result.output())
                .contains("NOTE  Could not remove managed PATH setup from " + home.resolve(".bashrc"));
        assertThat(shellProfile).content(StandardCharsets.UTF_8).isEqualTo(profile);
    }

    @Test
    void posixUninstallerContinuesWhenManagedPathProfileCannotBeRead() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        assumeFalse(isWindows());
        assumeTrue(commandExists("runuser"));
        assumeTrue(canRunAsNobody());
        Path home = temporaryDirectory.resolve("path-cleanup-unreadable-home");
        Path symphonyHome = temporaryDirectory.resolve("path-cleanup-unreadable-symphony-home");
        Path binDirectory = temporaryDirectory.resolve("path-cleanup-unreadable-bin");
        temporaryDirectory.toFile().setReadable(true, false);
        temporaryDirectory.toFile().setExecutable(true, false);
        Files.createDirectories(home);
        home.toFile().setReadable(true, false);
        home.toFile().setExecutable(true, false);
        String pathLine = "export PATH='" + binDirectory + "':\"$PATH\"";
        String profile =
                """
                before
                # >>> Symphony for Trello PATH >>>
                %s
                # <<< Symphony for Trello PATH <<<
                after
                """
                        .formatted(pathLine);
        Path shellProfile = home.resolve(".bashrc");
        Files.writeString(shellProfile, profile, StandardCharsets.UTF_8);
        assertThat(shellProfile.toFile().setReadable(false, false)).isTrue();

        // when
        ProcessResult result = run(
                Map.of(),
                "runuser",
                "-u",
                "nobody",
                "--",
                "env",
                "HOME=" + home,
                "SHELL=/bin/bash",
                "SYMPHONY_HOME=" + symphonyHome,
                "bash",
                "uninstall.sh",
                "--yes",
                "--bin-dir",
                binDirectory.toString());

        // then
        result.assertSuccess();
        assertThat(result.output())
                .contains("NOTE  Could not remove managed PATH setup from " + home.resolve(".bashrc"));
        assertThat(shellProfile).content(StandardCharsets.UTF_8).isEqualTo(profile);
    }

    @Test
    void posixUninstallerRemovesLegacyManagedPathProfileBlocks() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        Path home = temporaryDirectory.resolve("path-cleanup-legacy-home");
        Path symphonyHome = temporaryDirectory.resolve("path-cleanup-legacy-symphony-home");
        Path binDirectory = temporaryDirectory.resolve("path-cleanup-legacy-bin");
        Files.createDirectories(home);
        String pathLine = "export PATH='" + binDirectory + "':\"$PATH\"";
        String legacyBlock =
                """
                before
                # Symphony for Trello
                %s
                after
                """
                        .formatted(pathLine);
        Files.writeString(home.resolve(".bashrc"), legacyBlock, StandardCharsets.UTF_8);
        Files.writeString(home.resolve(".profile"), "unchanged\n", StandardCharsets.UTF_8);

        // when
        ProcessResult result = runPosixUninstall(home, symphonyHome, binDirectory);

        // then
        result.assertSuccess();
        assertRemovedPathSetup(result, home);
        assertThat(home.resolve(".bashrc"))
                .content(StandardCharsets.UTF_8)
                .isEqualTo(
                        """
                        before
                        after
                        """);
        assertThat(home.resolve(".profile")).content(StandardCharsets.UTF_8).isEqualTo("unchanged\n");
    }

    private static ProcessResult runPosixUninstall(Path home, Path symphonyHome, Path binDirectory) throws Exception {
        return run(
                uninstallEnvironment(home, symphonyHome),
                "bash",
                "uninstall.sh",
                "--yes",
                "--bin-dir",
                binDirectory.toString());
    }

    private static Map<String, String> uninstallEnvironment(Path home, Path symphonyHome) {
        return Map.of("HOME", home.toString(), "SHELL", "/bin/bash", "SYMPHONY_HOME", symphonyHome.toString());
    }

    private static void assertRemovedPathSetup(ProcessResult result, Path home) {
        assertThat(result.output()).contains("OK  Removed managed PATH setup from " + home.resolve(".bashrc"));
    }

    private static boolean canRunAsNobody() {
        try {
            return run(Map.of(), "runuser", "-u", "nobody", "--", "true").exitCode() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    void posixInstallerContinuesWhenAutomaticPathSetupCannotWriteProfile() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        assumeTrue(commandExists("git"));
        Path sourceRepository = createSourceRepository(temporaryDirectory);
        Path fakeBin = createFakeToolchain(temporaryDirectory);
        Path homeFile = temporaryDirectory.resolve("not-a-directory-home");
        Path symphonyHome = temporaryDirectory.resolve("path-unwritable-symphony-home");
        Path binDirectory = temporaryDirectory.resolve("path-unwritable-bin");
        Path fakeLog = temporaryDirectory.resolve("path-unwritable.log");
        Files.writeString(homeFile, "not a directory\n", StandardCharsets.UTF_8);
        Map<String, String> environment = Map.of(
                "PATH", fakeBin + File.pathSeparator + System.getenv("PATH"),
                "HOME", homeFile.toString(),
                "SHELL", "/bin/bash",
                "SYMPHONY_TRELLO_REPO_URL", sourceRepository.toUri().toString(),
                "SYMPHONY_HOME", symphonyHome.toString(),
                "SYMPHONY_FAKE_LOG", fakeLog.toString());

        // when
        ProcessResult result =
                run(environment, "bash", "install.sh", "--no-onboard", "--bin-dir", binDirectory.toString());

        // then
        result.assertSuccess();
        assertThat(result.output())
                .contains(
                        "NOTE  Could not update PATH in " + homeFile.resolve(".bashrc"),
                        "Add this line to a shell profile file so future shells can run symphony-trello:",
                        "export PATH='" + binDirectory + "':\"$PATH\"");
    }

    @Test
    void posixInstallerDryRunOffersJava25WhenJava24IsOnPath() throws Exception {
        // given
        assumeTrue(Files.exists(Path.of("/bin/bash")));
        Path fakeBin = temporaryDirectory.resolve("java-24-bin");
        Files.createDirectories(fakeBin);
        String success = """
                #!/bin/sh
                exit 0
                """;
        writeExecutable(fakeBin.resolve("git"), success);
        writeExecutable(fakeBin.resolve("codex"), success);
        writeExecutable(fakeBin.resolve("apt-get"), success);
        writeCommandProxy(fakeBin, "sed", "/bin/sed");
        writeCommandProxy(fakeBin, "head", "/usr/bin/head");
        writeExecutable(
                fakeBin.resolve("java"),
                """
                #!/bin/sh
                echo 'openjdk version "24.0.2"' >&2
                """);
        writeExecutable(
                fakeBin.resolve("javac"),
                """
                #!/bin/sh
                echo 'javac 24.0.2'
                """);
        Map<String, String> environment = Map.of(
                "PATH", fakeBin.toString(),
                "SYMPHONY_TRELLO_TEST_EUID", "0",
                "SYMPHONY_TRELLO_TEST_OS", "Linux",
                "SYMPHONY_TRELLO_TEST_ARCH", "x86_64");

        // when
        ProcessResult result = run(environment, "/bin/bash", "install.sh", "--dry-run", "--no-onboard");

        // then
        result.assertSuccess();
        assertThat(result.output())
                .contains(
                        "NEEDED  Java 25+ JDK",
                        "OK      Codex CLI available",
                        "WOULD offer to install Java 25+ JDK with: apt-get update && apt-get install -y openjdk-25-jdk")
                .doesNotContain("WOULD offer to install Git");
    }

    @Test
    void posixInstallerProposesPackageManagerCommandWithoutSudoWhenRoot() throws Exception {
        // given
        assumeTrue(Files.exists(Path.of("/bin/bash")));
        assumeTrue(commandExists("script"));
        Path installScript = Path.of("install.sh").toAbsolutePath();
        Path fakeBin = temporaryDirectory.resolve("package-manager-bin");
        Files.createDirectories(fakeBin);
        writeExecutable(
                fakeBin.resolve("apt-get"),
                """
                #!/usr/bin/env bash
                exit 0
                """);
        Map<String, String> environment = Map.of(
                "PATH", fakeBin.toString(),
                "SYMPHONY_TRELLO_TEST_EUID", "0",
                "SYMPHONY_TRELLO_TEST_OS", "Linux",
                "SYMPHONY_TRELLO_TEST_ARCH", "x86_64");

        // when
        ProcessResult result = runWithPseudoTerminal(
                environment,
                "n\n",
                "/bin/bash " + shellQuote(installScript.toString()) + " --no-onboard --from-source");

        // then
        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.output())
                .contains("Git is missing.", "Proposed install command:")
                .contains("  apt-get update && apt-get install -y git")
                .doesNotContain("sudo apt-get");
    }

    @Test
    void posixInstallerExplainsManualRootCommandWhenNonRootWithoutPrivilegeHelper() throws Exception {
        // given
        assumeTrue(Files.exists(Path.of("/bin/bash")));
        Path fakeBin = temporaryDirectory.resolve("package-manager-without-sudo");
        Files.createDirectories(fakeBin);
        writeExecutable(
                fakeBin.resolve("apt-get"),
                """
                #!/usr/bin/env bash
                exit 0
                """);
        Map<String, String> environment = Map.of(
                "PATH", fakeBin.toString(),
                "SYMPHONY_TRELLO_TEST_EUID", "1000",
                "SYMPHONY_TRELLO_TEST_OS", "Linux",
                "SYMPHONY_TRELLO_TEST_ARCH", "x86_64");

        // when
        ProcessResult result = run(environment, "/bin/bash", "install.sh", "--no-onboard", "--from-source");

        // then
        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.output())
                .contains(
                        "Git is missing.",
                        "Automatic install requires root, sudo, or doas.",
                        "Run this command as root:",
                        "  apt-get update && apt-get install -y git")
                .doesNotContain("Proposed install command:", "sudo apt-get");
    }

    @Test
    void posixInstallerDryRunShowsManualPlanWhenNonRootWithoutPrivilegeHelper() throws Exception {
        // given
        assumeTrue(Files.exists(Path.of("/bin/bash")));
        Path fakeBin = temporaryDirectory.resolve("dry-run-package-manager-without-sudo");
        Files.createDirectories(fakeBin);
        writeExecutable(
                fakeBin.resolve("apt-get"),
                """
                #!/usr/bin/env bash
                exit 0
                """);
        Map<String, String> environment = Map.of(
                "PATH", fakeBin.toString(),
                "SYMPHONY_TRELLO_TEST_EUID", "1000",
                "SYMPHONY_TRELLO_TEST_OS", "Linux",
                "SYMPHONY_TRELLO_TEST_ARCH", "x86_64");

        // when
        ProcessResult result =
                run(environment, "/bin/bash", "install.sh", "--dry-run", "--no-onboard", "--from-source");

        // then
        assertThat(result.exitCode()).isZero();
        assertThat(result.output())
                .contains(
                        "NEEDED  Git",
                        "WOULD offer to install Git",
                        "NEEDED  Java 25+ JDK",
                        "WOULD offer to install Java 25+ JDK",
                        "Dry run: no files changed.")
                .doesNotContain("sudo apt-get", "doas apt-get");
    }

    @MethodSource("codexInstallDryRunCases")
    @ParameterizedTest(name = "{0}")
    void posixInstallerDryRunReportsSelectedCodexInstallPath(
            String name, Map<String, String> commandStubs, Map<String, String> extraEnvironment, String expected)
            throws Exception {
        // given
        assumeTrue(Files.exists(Path.of("/bin/bash")));
        Path fakeBin = temporaryDirectory.resolve(name.replaceAll("[^a-zA-Z0-9]+", "-"));
        Files.createDirectories(fakeBin);
        for (Map.Entry<String, String> stub : commandStubs.entrySet()) {
            writeExecutable(fakeBin.resolve(stub.getKey()), stub.getValue());
        }
        Map<String, String> environment = new LinkedHashMap<>();
        environment.put("PATH", fakeBin.toString());
        environment.put("SYMPHONY_TRELLO_TEST_OS", extraEnvironment.getOrDefault("SYMPHONY_TRELLO_TEST_OS", "Linux"));
        environment.put("SYMPHONY_TRELLO_TEST_ARCH", "x86_64");
        environment.putAll(extraEnvironment);

        // when
        ProcessResult result = run(environment, "/bin/bash", "install.sh", "--dry-run");

        // then
        assertThat(result.exitCode()).isZero();
        assertThat(result.output()).contains(expected);
    }

    private static Stream<Arguments> codexInstallDryRunCases() {
        String success = """
                #!/usr/bin/env bash
                exit 0
                """;
        return Stream.of(
                Arguments.of("existing codex", Map.of("codex", success), Map.of(), "OK      Codex CLI available"),
                Arguments.of("npm with node present", Map.of("npm", success), Map.of(), "Node.js/npm installed: yes"),
                Arguments.of("npm needs node", Map.of(), Map.of(), "Node.js/npm installed: no"));
    }

    @Test
    void posixInstallerFindsManagedCodexFromInterruptedPreviousRun() throws Exception {
        // given
        assumeTrue(Files.exists(Path.of("/bin/bash")));
        Path fakeBin = temporaryDirectory.resolve("managed-codex-rerun-tools");
        Files.createDirectories(fakeBin);
        writeExecutable(
                fakeBin.resolve("git"),
                """
                #!/usr/bin/env bash
                exit 0
                """);
        writeExecutable(
                fakeBin.resolve("java"),
                """
                #!/usr/bin/env bash
                echo 'openjdk version "25.0.1"' >&2
                """);
        writeExecutable(
                fakeBin.resolve("javac"),
                """
                #!/usr/bin/env bash
                echo 'javac 25.0.1'
                """);
        writeCommandProxy(fakeBin, "sed", "/bin/sed");
        writeCommandProxy(fakeBin, "head", "/usr/bin/head");
        Path symphonyHome = temporaryDirectory.resolve("managed-codex-rerun-home");
        Path managedCodex = symphonyHome.resolve("npm/bin/codex");
        Files.createDirectories(managedCodex.getParent());
        writeExecutable(
                managedCodex, """
                #!/usr/bin/env bash
                exit 0
                """);
        Map<String, String> environment = Map.of(
                "PATH",
                fakeBin.toString(),
                "SYMPHONY_HOME",
                symphonyHome.toString(),
                "SYMPHONY_TRELLO_TEST_OS",
                "Linux",
                "SYMPHONY_TRELLO_TEST_ARCH",
                "x86_64");

        // when
        ProcessResult result = run(environment, "/bin/bash", "install.sh", "--dry-run");

        // then
        assertThat(result.exitCode()).as(result.output()).isZero();
        assertThat(result.output())
                .contains("OK      Codex CLI available")
                .doesNotContain("WOULD offer to install Codex CLI");
    }

    @Test
    void posixInstallerRunsAptUpdateOnlyForFirstAcceptedPackageInstall() throws Exception {
        // given
        assumeTrue(Files.exists(Path.of("/bin/bash")));
        assumeTrue(commandExists("script"));
        Path installScript = Path.of("install.sh").toAbsolutePath();
        Path fakeBin = temporaryDirectory.resolve("apt-update-once-bin");
        Files.createDirectories(fakeBin);
        writeCommandProxy(fakeBin, "bash", "/bin/bash");
        writeCommandProxy(fakeBin, "uname", "/bin/uname");
        writeCommandProxy(fakeBin, "sed", "/bin/sed");
        writeCommandProxy(fakeBin, "head", "/usr/bin/head");
        Path aptLog = temporaryDirectory.resolve("apt-update-once.log");
        writeExecutable(
                fakeBin.resolve("apt-get"),
                """
                #!/bin/bash
                set -euo pipefail
                echo "$*" >> "${SYMPHONY_FAKE_LOG:?}"
                if [[ "${1:-}" == "install" && "$*" == *" git"* ]]; then
                  /bin/cat > "${SYMPHONY_FAKE_BIN:?}/git" <<'GIT'
                #!/bin/bash
                exit 0
                GIT
                  /bin/chmod +x "${SYMPHONY_FAKE_BIN}/git"
                fi
                """);
        Map<String, String> environment = Map.of(
                "PATH",
                fakeBin.toString(),
                "SYMPHONY_FAKE_BIN",
                fakeBin.toString(),
                "SYMPHONY_FAKE_LOG",
                aptLog.toString(),
                "SYMPHONY_TRELLO_TEST_EUID",
                "0",
                "SYMPHONY_TRELLO_TEST_OS",
                "Linux",
                "SYMPHONY_TRELLO_TEST_ARCH",
                "x86_64");

        // when
        ProcessResult result = runWithPseudoTerminal(
                environment,
                "y\nn\n",
                "/bin/bash " + shellQuote(installScript.toString()) + " --no-onboard --from-source");

        // then
        assertThat(result.exitCode()).as(result.output()).isEqualTo(2);
        assertThat(result.output())
                .contains("  apt-get update && apt-get install -y git", "  apt-get install -y openjdk-25-jdk")
                .doesNotContain("  apt-get update && apt-get install -y openjdk-25-jdk");
        assertThat(Files.readString(aptLog, StandardCharsets.UTF_8))
                .contains("update", "install -y git")
                .doesNotContain("openjdk-25-jdk");
    }

    @Test
    void posixInstallerOffersSingleCodexCommandWhenNodeIsMissing() throws Exception {
        // given
        assumeTrue(Files.exists(Path.of("/bin/bash")));
        assumeTrue(commandExists("script"));
        Path installScript = Path.of("install.sh").toAbsolutePath();
        Path fakeBin = temporaryDirectory.resolve("codex-node-missing-bin");
        Files.createDirectories(fakeBin);
        writeCommandProxy(fakeBin, "bash", "/bin/bash");
        writeCommandProxy(fakeBin, "uname", "/bin/uname");
        writeCommandProxy(fakeBin, "sed", "/bin/sed");
        writeCommandProxy(fakeBin, "head", "/usr/bin/head");
        writeExecutable(
                fakeBin.resolve("git"), """
                #!/bin/bash
                exit 0
                """);
        writeExecutable(
                fakeBin.resolve("java"),
                """
                #!/bin/bash
                echo 'openjdk version "25.0.1"' >&2
                """);
        writeExecutable(
                fakeBin.resolve("javac"),
                """
                #!/bin/bash
                echo 'javac 25.0.1'
                """);
        writeExecutable(
                fakeBin.resolve("apt-get"), """
                #!/bin/bash
                exit 0
                """);
        Path symphonyHome = temporaryDirectory.resolve("codex-node-missing-home");
        Path binDirectory = temporaryDirectory.resolve("codex-node-missing-bin-dir");
        Map<String, String> environment = Map.of(
                "PATH", fakeBin.toString(),
                "SYMPHONY_HOME", symphonyHome.toString(),
                "SYMPHONY_TRELLO_TEST_EUID", "0",
                "SYMPHONY_TRELLO_TEST_OS", "Linux",
                "SYMPHONY_TRELLO_TEST_ARCH", "x86_64");

        // when
        ProcessResult result = runWithPseudoTerminal(
                environment,
                "n\n",
                "/bin/bash " + shellQuote(installScript.toString()) + " --bin-dir "
                        + shellQuote(binDirectory.toString()));

        // then
        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.output())
                .contains(
                        "Codex CLI is missing and needs Node.js with npm.",
                        "Install Codex CLI with Symphony-managed npm.",
                        "Install location: " + symphonyHome.resolve("npm"),
                        "Command link: " + binDirectory.resolve("codex"),
                        "This keeps system-wide npm packages unchanged.",
                        "Node.js/npm install:",
                        "apt-get update && apt-get install -y nodejs npm",
                        "Codex CLI install:",
                        "npm install --global --prefix",
                        "Run now? [y/N]")
                .doesNotContain(
                        "Node.js/npm is missing.",
                        "Selected install path:",
                        "Install method:",
                        "Install Codex CLI this way now? [y/N]",
                        "apt-get update && apt-get install -y nodejs npm && mkdir -p");
    }

    @Test
    void posixInstallerDryRunUsesAbsoluteManagedCodexPathsWhenHomeIsRelative() throws Exception {
        // given
        assumeTrue(Files.exists(Path.of("/bin/bash")));
        Path installScript = Path.of("install.sh").toAbsolutePath();
        Path fakeBin = temporaryDirectory.resolve("relative-home-tools");
        Files.createDirectories(fakeBin);
        String success = """
                #!/usr/bin/env bash
                exit 0
                """;
        writeExecutable(fakeBin.resolve("git"), success);
        writeExecutable(fakeBin.resolve("java"), success);
        writeExecutable(fakeBin.resolve("javac"), success);
        writeExecutable(fakeBin.resolve("npm"), success);
        Path workingDirectory = temporaryDirectory.resolve("relative-home-cwd");
        Files.createDirectories(workingDirectory);
        Map<String, String> environment = Map.of(
                "PATH", fakeBin.toString(),
                "SYMPHONY_HOME", "relative-home",
                "SYMPHONY_TRELLO_TEST_OS", "Linux",
                "SYMPHONY_TRELLO_TEST_ARCH", "x86_64");

        // when
        ProcessResult result = run(environment, workingDirectory, "/bin/bash", installScript.toString(), "--dry-run");

        // then
        assertThat(result.exitCode()).isZero();
        assertThat(result.output()).contains("Install location: " + workingDirectory.resolve("relative-home/npm"));
    }

    @Test
    void posixInstallerRejectsUnsupportedPlatforms() throws Exception {
        // given
        assumeTrue(Files.exists(Path.of("/bin/bash")));
        Map<String, String> environment = Map.of(
                "SYMPHONY_TRELLO_TEST_OS", "FreeBSD",
                "SYMPHONY_TRELLO_TEST_ARCH", "riscv64");

        // when
        ProcessResult result = run(environment, "/bin/bash", "install.sh", "--dry-run", "--no-onboard");

        // then
        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.output())
                .contains(
                        "Unsupported platform: FreeBSD riscv64",
                        "Supported platforms: macOS arm64/amd64, Linux arm64/amd64, and WSL2 through the Linux path.");
    }

    @Test
    void powershellInstallerAcceptsPublicDryRunFlagsWhenAvailable() throws Exception {
        // given
        List<String> pwsh = powershellCommand();
        assumeFalse(pwsh.isEmpty());

        // when
        ProcessResult result = run(
                nonWindowsPowerShellEnvironment(),
                command(pwsh, "-NoProfile", "-File", "./install.ps1", "--dry-run", "--no-onboard")
                        .toArray(String[]::new));

        // then
        result.assertSuccess();
        assertThat(result.output()).contains("Symphony for Trello installer", "Dry run: no files changed.");
    }

    @Test
    void powershellInstallerNormalizesLeadingVVersionBeforeReleaseAssetUrlsWhenAvailable() throws Exception {
        // given
        List<String> pwsh = powershellCommand();
        assumeFalse(pwsh.isEmpty());
        String version = installerDefaultRef().substring(1);
        Map<String, String> environment = new LinkedHashMap<>(nonWindowsPowerShellEnvironment());
        environment.put("SYMPHONY_TRELLO_VERSION", "v" + version);

        // when
        ProcessResult result = run(
                environment,
                command(pwsh, "-NoProfile", "-File", "./install.ps1", "--dry-run", "--no-onboard")
                        .toArray(String[]::new));

        // then
        result.assertSuccess();
        assertThat(result.output())
                .contains(
                        "Version: " + version,
                        "releases/download/v" + version + "/symphony-trello-" + version + ".zip",
                        "releases/download/v" + version + "/checksums.txt")
                .doesNotContain("vv" + version);
    }

    @Test
    void powershellInstallerRejectsRemovedUpdatePathOptionWhenAvailable() throws Exception {
        // given
        List<String> pwsh = powershellCommand();
        assumeFalse(pwsh.isEmpty());

        // when
        ProcessResult result = run(
                nonWindowsPowerShellEnvironment(),
                command(pwsh, "-NoProfile", "-File", "./install.ps1", "--dry-run", "--update-path", "--no-onboard")
                        .toArray(String[]::new));

        // then
        assertThat(result.exitCode()).isNotZero();
        assertThat(result.output()).contains("Unknown option: --update-path");
    }

    @Test
    void powershellInstallerAcceptsPublicFlagsThroughScriptblockWhenAvailable() throws Exception {
        // given
        List<String> pwsh = powershellCommand();
        assumeFalse(pwsh.isEmpty());

        // when
        ProcessResult result = run(
                nonWindowsPowerShellEnvironment(),
                command(
                                pwsh,
                                "-NoProfile",
                                "-Command",
                                "& ([scriptblock]::Create((Get-Content -Raw './install.ps1'))) --dry-run --no-onboard")
                        .toArray(String[]::new));

        // then
        result.assertSuccess();
        assertThat(result.output()).contains("Symphony for Trello installer", "Dry run: no files changed.");
    }

    @Test
    void powershellInstallerRejectsBlankPathValuesThroughScriptblockWhenAvailable() throws Exception {
        // given
        List<String> pwsh = powershellCommand();
        assumeFalse(pwsh.isEmpty());

        // when
        ProcessResult result = run(
                nonWindowsPowerShellEnvironment(),
                command(
                                pwsh,
                                "-NoProfile",
                                "-Command",
                                "& ([scriptblock]::Create((Get-Content -Raw './install.ps1'))) "
                                        + "--dry-run --no-onboard --prefix '' --bin-dir ''")
                        .toArray(String[]::new));

        // then
        assertThat(result.exitCode()).isNotZero();
        assertThat(result.output())
                .contains("Missing value for --prefix")
                .doesNotContain("Install:", "WOULD clone or update:", "/--bin-dir");
    }

    @Test
    void powershellInstallerRejectsUnsafeAppPathsWhenAvailable() throws Exception {
        // given
        List<String> pwsh = powershellCommand();
        assumeFalse(pwsh.isEmpty());
        Path home = temporaryDirectory.resolve("ps-install-app-home");
        Path symphonyHome = temporaryDirectory.resolve("ps-install-app-symphony-home");
        Path file = temporaryDirectory.resolve("ps-install-app-file");
        Path symlink = temporaryDirectory.resolve("ps-install-app-link");
        Path safeBin = temporaryDirectory.resolve("ps-install-app-bin");
        Files.createDirectories(home);
        Files.writeString(file, "not a directory", StandardCharsets.UTF_8);
        Files.createSymbolicLink(symlink, temporaryDirectory.resolve("ps-install-app-target"));
        Map<String, String> environment = new LinkedHashMap<>(nonWindowsPowerShellEnvironment());
        environment.put("USERPROFILE", home.toString());
        environment.put("SYMPHONY_HOME", symphonyHome.toString());
        List<UnsafeInstallerPath> cases = powershellUnsafeAppPaths(
                file,
                symlink,
                "ps-install-app-link",
                new UnsafeInstallerPath(
                        "local-data-overlap",
                        symphonyHome.toString(),
                        "--prefix must not overlap Symphony config, workspace"));

        // when
        List<ProcessResult> results = runPowerShellAppPathCases(
                environment, pwsh, "./install.ps1", List.of("--dry-run", "--no-onboard"), cases, safeBin);

        // then
        assertUnsafeAppPathFailures(
                cases, results, OutputStyle.NORMALIZED, "Install:", "WOULD clone or update:", "Command PATH setup");
    }

    @Test
    void powershellInstallerAcceptsDedicatedAppPathDirectlyUnderHomeWhenAvailable() throws Exception {
        // given
        List<String> pwsh = powershellCommand();
        assumeFalse(pwsh.isEmpty());
        Path home = temporaryDirectory.resolve("ps-install-home-child-home");
        Path app = home.resolve("symphony-trello");
        Path safeBin = temporaryDirectory.resolve("ps-install-home-child-bin");
        Files.createDirectories(home);
        Map<String, String> environment = new LinkedHashMap<>(nonWindowsPowerShellEnvironment());
        environment.put("USERPROFILE", home.toString());

        // when
        ProcessResult result = run(
                environment,
                command(
                                pwsh,
                                "-NoProfile",
                                "-File",
                                "./install.ps1",
                                "--dry-run",
                                "--no-onboard",
                                "--prefix",
                                app.toString(),
                                "--bin-dir",
                                safeBin.toString())
                        .toArray(String[]::new));

        // then
        result.assertSuccess();
        assertThat(result.output()).contains("Install: " + app, "WOULD unpack release archive to: " + app);
    }

    @Test
    void powershellInstallerRejectsUnsafeSymphonyHomeWhenAvailable() throws Exception {
        // given
        List<String> pwsh = powershellCommand();
        assumeFalse(pwsh.isEmpty());
        Path home = temporaryDirectory.resolve("ps-install-home-base-home");
        Path safeBin = temporaryDirectory.resolve("ps-install-home-base-bin");
        Files.createDirectories(home);

        List<UnsafeInstallerPath> cases = List.of(
                new UnsafeInstallerPath("root", "/", "--prefix must point to a dedicated app checkout directory."),
                new UnsafeInstallerPath(
                        "root-home", "/root", "--prefix must point to a dedicated app checkout directory."),
                new UnsafeInstallerPath(
                        "control", temporaryDirectory.resolve("home\nline").toString(), "control characters"));

        // when
        List<ProcessResult> results = cases.stream()
                .map(appPath -> {
                    Map<String, String> environment = new LinkedHashMap<>(nonWindowsPowerShellEnvironment());
                    environment.put("HOME", home.toString());
                    environment.put("USERPROFILE", home.toString());
                    environment.put("SYMPHONY_HOME", appPath.value());
                    return runUnchecked(
                            environment,
                            command(
                                            pwsh,
                                            "-NoProfile",
                                            "-File",
                                            "./install.ps1",
                                            "--dry-run",
                                            "--no-onboard",
                                            "--bin-dir",
                                            safeBin.toString())
                                    .toArray(String[]::new));
                })
                .toList();

        // then
        for (int index = 0; index < cases.size(); index++) {
            UnsafeInstallerPath appPath = cases.get(index);
            ProcessResult result = results.get(index);
            assertThat(result.exitCode()).as(appPath.name()).isNotZero();
            assertThat(normalizedWhitespace(result.output()))
                    .as(appPath.name())
                    .contains(appPath.expectedMessage())
                    .doesNotContain("Install:", "WOULD clone or update:", "Command PATH setup");
        }
    }

    @Test
    void powershellInstallerRejectsUnsafeCommandDirectoriesWhenAvailable() throws Exception {
        // given
        List<String> pwsh = powershellCommand();
        assumeFalse(pwsh.isEmpty());
        Path home = temporaryDirectory.resolve("ps-install-bin-home");
        Path symphonyHome = temporaryDirectory.resolve("ps-install-bin-symphony-home");
        Path app = temporaryDirectory.resolve("ps-install-bin-app");
        Path file = temporaryDirectory.resolve("ps-install-bin-file");
        Path symlink = temporaryDirectory.resolve("ps-install-bin-workspaces-link");
        Files.createDirectories(home);
        Files.createDirectories(symphonyHome.resolve("workspaces"));
        Files.writeString(file, "not a directory", StandardCharsets.UTF_8);
        Files.createSymbolicLink(symlink, symphonyHome.resolve("workspaces"));
        Map<String, String> environment = new LinkedHashMap<>(nonWindowsPowerShellEnvironment());
        environment.put("USERPROFILE", home.toString());
        environment.put("SYMPHONY_HOME", symphonyHome.toString());
        List<UnsafeCommandDirectory> cases = powershellUnsafeCommandDirectories(
                file,
                symlink,
                "ps-install-bin-workspaces-link",
                new UnsafeCommandDirectory(
                        "workspace-overlap",
                        symphonyHome.resolve("workspaces").toString(),
                        "--bin-dir must not overlap Symphony app, config, workspace"));

        // when
        List<ProcessResult> results = cases.stream()
                .map(commandDirectory -> runUnchecked(
                        environment,
                        command(
                                        pwsh,
                                        "-NoProfile",
                                        "-File",
                                        "./install.ps1",
                                        "--dry-run",
                                        "--no-onboard",
                                        "--prefix",
                                        app.toString(),
                                        "--bin-dir",
                                        commandDirectory.value())
                                .toArray(String[]::new)))
                .toList();

        // then
        for (int index = 0; index < cases.size(); index++) {
            UnsafeCommandDirectory commandDirectory = cases.get(index);
            ProcessResult result = results.get(index);
            assertThat(result.exitCode()).as(commandDirectory.name()).isNotZero();
            assertThat(normalizedWhitespace(result.output()))
                    .as(commandDirectory.name())
                    .contains(commandDirectory.expectedMessage())
                    .doesNotContain("WOULD install CLI executable", "Command PATH setup");
        }
    }

    @Test
    void powershellInstallerAllowsDefaultCommandDirectoryWhenRunFromThatDirectoryWhenAvailable() throws Exception {
        // given
        List<String> pwsh = powershellCommandForDifferentWorkingDirectory();
        assumeFalse(pwsh.isEmpty());
        Path installScript = Path.of("install.ps1").toAbsolutePath();
        Path home = temporaryDirectory.resolve("ps-install-run-from-bin-home");
        Path binDirectory = home.resolve(".local/bin");
        Files.createDirectories(binDirectory);
        Map<String, String> environment = new LinkedHashMap<>(nonWindowsPowerShellEnvironment());
        environment.put("HOME", home.toString());
        environment.put("USERPROFILE", "");

        // when
        ProcessResult result = run(
                environment,
                binDirectory,
                command(pwsh, "-NoProfile", "-File", installScript.toString(), "--dry-run", "--no-onboard")
                        .toArray(String[]::new));

        // then
        result.assertSuccess();
        assertThat(result.output()).contains("Dry run: no files changed.");
    }

    @MethodSource("invalidPowerShellInstallerSourceInputs")
    @ParameterizedTest(name = "{0}")
    void powershellInstallerRejectsInvalidSourceInputsBeforeDryRunPlanWhenAvailable(
            String name, String[] sourceArgs, String expectedMessage) throws Exception {
        // given
        List<String> pwsh = powershellCommand();
        assumeFalse(pwsh.isEmpty());
        Path prefix = temporaryDirectory
                .resolve(name.replaceAll("[^A-Za-z0-9]+", "-"))
                .resolve("app");
        String[] command = Stream.concat(
                        command(
                                pwsh,
                                "-NoProfile",
                                "-File",
                                "./install.ps1",
                                "--dry-run",
                                "--no-onboard",
                                "--prefix",
                                prefix.toString())
                                .stream(),
                        Stream.of(sourceArgs))
                .toArray(String[]::new);

        // when
        ProcessResult result = run(nonWindowsPowerShellEnvironment(), command);

        // then
        assertThat(result.exitCode()).isNotZero();
        assertThat(result.output()).contains(expectedMessage).doesNotContain("WOULD clone or update:");
        assertThat(prefix).doesNotExist();
    }

    private static Stream<Arguments> invalidPowerShellInstallerSourceInputs() {
        return Stream.of(
                Arguments.of("blank repo", new String[] {"--repo", ""}, "--repo must not be blank."),
                Arguments.of(
                        "malformed repo",
                        new String[] {"--repo", "not-a-url"},
                        "--repo must be a URL or existing local Git repository path."),
                Arguments.of("blank ref", new String[] {"--ref", ""}, "--ref must not be blank."),
                Arguments.of("path traversal ref", new String[] {"--ref", "../main"}, "Git namespace prefixes"),
                Arguments.of("remote namespace ref", new String[] {"--ref", "origin/main"}, "Git namespace prefixes"),
                Arguments.of("lock component ref", new String[] {"--ref", "foo.lock/bar"}, "Git namespace prefixes"),
                Arguments.of("trailing dot ref", new String[] {"--ref", "foo."}, "Git namespace prefixes"),
                Arguments.of(
                        "unsupported ref characters",
                        new String[] {"--ref", "main;echo-x"},
                        "--ref contains unsupported characters."));
    }

    @Test
    void powershellInstallerAcceptsScpStyleRepoAndRedactsCredentialUrlBeforeDryRunPlanWhenAvailable() throws Exception {
        // given
        List<String> pwsh = powershellCommand();
        assumeFalse(pwsh.isEmpty());

        // when
        ProcessResult scpRepo = run(
                nonWindowsPowerShellEnvironment(),
                command(
                                pwsh,
                                "-NoProfile",
                                "-File",
                                "./install.ps1",
                                "--dry-run",
                                "--no-onboard",
                                "--repo",
                                "deploy@example.com:org/repo.git")
                        .toArray(String[]::new));
        ProcessResult credentialRepo = run(
                nonWindowsPowerShellEnvironment(),
                command(
                                pwsh,
                                "-NoProfile",
                                "-File",
                                "./install.ps1",
                                "--dry-run",
                                "--no-onboard",
                                "--repo",
                                "https://user:secret-token@example.com/org/repo.git")
                        .toArray(String[]::new));
        ProcessResult buildMetadataRef = run(
                nonWindowsPowerShellEnvironment(),
                command(
                                pwsh,
                                "-NoProfile",
                                "-File",
                                "./install.ps1",
                                "--dry-run",
                                "--no-onboard",
                                "--ref",
                                "v1.2.3+hotfix.1")
                        .toArray(String[]::new));

        // then
        scpRepo.assertSuccess();
        assertThat(scpRepo.output()).contains("Repository: deploy@example.com:org/repo.git");
        credentialRepo.assertSuccess();
        assertThat(credentialRepo.output())
                .contains("Repository: https://<redacted>@example.com/org/repo.git")
                .doesNotContain("user:secret-token");
        buildMetadataRef.assertSuccess();
        assertThat(buildMetadataRef.output()).contains("Ref: v1.2.3+hotfix.1");
    }

    @Test
    void powershellInstallerRejectsExistingNonGitRepoPathBeforeDryRunPlanWhenAvailable() throws Exception {
        // given
        List<String> pwsh = powershellCommand();
        assumeFalse(pwsh.isEmpty());
        Path notGitRepository = temporaryDirectory.resolve("ps-not-git-repository");
        Path prefix = temporaryDirectory.resolve("ps-non-git-repo-prefix").resolve("app");
        Files.createDirectories(notGitRepository);

        // when
        ProcessResult result = run(
                nonWindowsPowerShellEnvironment(),
                command(
                                pwsh,
                                "-NoProfile",
                                "-File",
                                "./install.ps1",
                                "--dry-run",
                                "--no-onboard",
                                "--prefix",
                                prefix.toString(),
                                "--repo",
                                notGitRepository.toString())
                        .toArray(String[]::new));

        // then
        assertThat(result.exitCode()).isNotZero();
        assertThat(result.output())
                .contains("--repo must be a URL or existing local Git repository path.")
                .doesNotContain("WOULD clone or update:");
        assertThat(prefix).doesNotExist();
    }

    @Test
    void powershellInstallerAcceptsLocalGitRepoPathWithSpacesBeforeDryRunPlanWhenAvailable() throws Exception {
        // given
        List<String> pwsh = powershellCommand();
        assumeFalse(pwsh.isEmpty());
        Path localRepository = temporaryDirectory.resolve("ps local repo with spaces");
        Path prefix = temporaryDirectory.resolve("ps-local-repo-space-prefix").resolve("app");
        Files.createDirectories(localRepository.resolve(".git"));

        // when
        ProcessResult result = run(
                nonWindowsPowerShellEnvironment(),
                command(
                                pwsh,
                                "-NoProfile",
                                "-File",
                                "./install.ps1",
                                "--dry-run",
                                "--no-onboard",
                                "--prefix",
                                prefix.toString(),
                                "--repo",
                                localRepository.toString())
                        .toArray(String[]::new));

        // then
        result.assertSuccess();
        assertThat(result.output()).contains("Repository: " + localRepository, "WOULD clone or update:");
        assertThat(prefix).doesNotExist();
    }

    @Test
    void powershellInstallerRejectsBlankSourceValueThroughScriptblockWhenAvailable() throws Exception {
        // given
        List<String> pwsh = powershellCommand();
        assumeFalse(pwsh.isEmpty());
        Path prefix = temporaryDirectory.resolve("ps-scriptblock-source").resolve("app");

        // when
        ProcessResult result = run(
                nonWindowsPowerShellEnvironment(),
                command(
                                pwsh,
                                "-NoProfile",
                                "-Command",
                                "& ([scriptblock]::Create((Get-Content -Raw './install.ps1'))) --dry-run --no-onboard"
                                        + " --repo '' --prefix "
                                        + powerShellLiteral(prefix.toString()))
                        .toArray(String[]::new));

        // then
        assertThat(result.exitCode()).isNotZero();
        assertThat(result.output()).contains("Missing value for --repo").doesNotContain("WOULD clone or update:");
        assertThat(prefix).doesNotExist();
    }

    @Test
    void powershellInstallerAcceptsDashPrefixedRelativePathValuesThroughScriptblockWhenAvailable() throws Exception {
        // given
        List<String> pwsh = powershellCommand();
        assumeFalse(pwsh.isEmpty());
        Path binDirectory = temporaryDirectory.resolve("-dash-bin");
        Path prefix = temporaryDirectory.resolve("-dash-prefix-app");

        // when
        ProcessResult result = run(
                nonWindowsPowerShellEnvironment(),
                command(
                                pwsh,
                                "-NoProfile",
                                "-Command",
                                "& ([scriptblock]::Create((Get-Content -Raw './install.ps1'))) --dry-run --no-onboard"
                                        + " --prefix "
                                        + powerShellLiteral(prefix.toString())
                                        + " --bin-dir "
                                        + powerShellLiteral(binDirectory.toString()))
                        .toArray(String[]::new));

        // then
        result.assertSuccess();
        assertThat(result.output()).contains(prefix.toString(), binDirectory.toString(), "Dry run: no files changed.");
    }

    @Test
    void powershellInstallerAcceptsPublicValueFlagsWhenAvailable() throws Exception {
        // given
        List<String> pwsh = powershellCommand();
        assumeFalse(pwsh.isEmpty());
        Path home = temporaryDirectory.resolve("ps home $value (quoted)");
        Path bin = temporaryDirectory.resolve("ps bin & tools");

        // when
        ProcessResult result = run(
                nonWindowsPowerShellEnvironment(),
                command(
                                pwsh,
                                "-NoProfile",
                                "-File",
                                "./install.ps1",
                                "--dry-run",
                                "--no-onboard",
                                "--prefix",
                                home.resolve("app").toString(),
                                "--bin-dir",
                                bin.toString(),
                                "--repo",
                                "https://example.invalid/symphony-trello.git",
                                "--ref",
                                "feature/test-ref")
                        .toArray(String[]::new));

        // then
        result.assertSuccess();
        assertThat(result.output())
                .contains(
                        "Install: " + home.resolve("app"),
                        "Repository: https://example.invalid/symphony-trello.git",
                        "Ref: feature/test-ref",
                        "Command: " + bin + "\\symphony-trello.ps1",
                        "WOULD clone or update: " + home.resolve("app"),
                        "WOULD build packaged Quarkus app with Maven wrapper",
                        "WOULD install CLI executable: " + bin + "\\symphony-trello.ps1",
                        "Dry run: no files changed.",
                        "Symphony would install the command here:")
                .doesNotContain("Unknown option");
    }

    @Test
    void powershellInstallerDryRunReportsDefaultUserPathSetupWhenAvailable() throws Exception {
        // given
        List<String> pwsh = powershellCommand();
        assumeFalse(pwsh.isEmpty());
        Path bin = temporaryDirectory.resolve("ps default path bin");

        // when
        ProcessResult result = run(
                nonWindowsPowerShellEnvironment(),
                command(
                                pwsh,
                                "-NoProfile",
                                "-File",
                                "./install.ps1",
                                "--dry-run",
                                "--no-onboard",
                                "--bin-dir",
                                bin.toString())
                        .toArray(String[]::new));

        // then
        result.assertSuccess();
        assertThat(result.output()).contains("WOULD add " + bin + " to the current user PATH.");
    }

    @Test
    void powershellInstallerDryRunSkipsPathSetupWhenBinDirIsAlreadyOnPathWhenAvailable() throws Exception {
        // given
        List<String> pwsh = powershellCommand();
        assumeFalse(pwsh.isEmpty());
        Path bin = temporaryDirectory.resolve("ps path present bin");
        Map<String, String> environment = new LinkedHashMap<>(nonWindowsPowerShellEnvironment());
        environment.put("PATH", bin + File.pathSeparator + System.getenv("PATH"));

        // when
        ProcessResult result = run(
                environment,
                command(
                                pwsh,
                                "-NoProfile",
                                "-File",
                                "./install.ps1",
                                "--dry-run",
                                "--no-onboard",
                                "--bin-dir",
                                bin.toString())
                        .toArray(String[]::new));

        // then
        result.assertSuccess();
        assertThat(result.output()).doesNotContain("WOULD offer to add", "not on PATH");
    }

    @Test
    void powershellInstallerRejectsNonWindowsWithoutTestOverrideWhenAvailable() throws Exception {
        // given
        assumeTrue(dockerDaemonIsUsable());
        String[] command = {
            "docker",
            "run",
            "--rm",
            "-v",
            Path.of(".").toAbsolutePath().normalize() + ":/workspace",
            "-w",
            "/workspace",
            "mcr.microsoft.com/dotnet/sdk:8.0",
            "pwsh",
            "-NoProfile",
            "-File",
            "./install.ps1",
            "--dry-run",
            "--no-onboard"
        };

        // when
        ProcessResult result = run(Map.of(), command);

        // then
        assertThat(result.exitCode()).as(result.output()).isEqualTo(1);
        assertThat(result.output()).contains("install.ps1 supports Windows PowerShell setup only");
    }

    @Test
    void powershellInstallerAcceptsPublicValueFlagsThroughScriptblockWhenAvailable() throws Exception {
        // given
        List<String> pwsh = powershellCommand();
        assumeFalse(pwsh.isEmpty());
        Path home = temporaryDirectory.resolve("scriptblock home");
        Path bin = temporaryDirectory.resolve("scriptblock bin");

        // when
        ProcessResult result = run(
                nonWindowsPowerShellEnvironment(),
                command(
                                pwsh,
                                "-NoProfile",
                                "-Command",
                                "& ([scriptblock]::Create((Get-Content -Raw './install.ps1'))) --dry-run --no-onboard"
                                        + " --prefix "
                                        + powerShellLiteral(home.resolve("app").toString())
                                        + " --bin-dir "
                                        + powerShellLiteral(bin.toString())
                                        + " --repo https://example.invalid/symphony-trello.git --ref main")
                        .toArray(String[]::new));

        // then
        result.assertSuccess();
        assertThat(result.output())
                .contains("Install: " + home.resolve("app"), "Command: " + bin + "\\symphony-trello.ps1");
    }

    @Test
    void powershellUninstallerAcceptsPublicDryRunFlagsWhenAvailable() throws Exception {
        // given
        List<String> pwsh = powershellCommand();
        assumeFalse(pwsh.isEmpty());
        Path home = temporaryDirectory.resolve("uninstall ps home");
        Path bin = temporaryDirectory.resolve("uninstall ps bin");

        // when
        ProcessResult result = run(
                nonWindowsPowerShellEnvironment(),
                command(
                                pwsh,
                                "-NoProfile",
                                "-File",
                                "./uninstall.ps1",
                                "--dry-run",
                                "--prefix",
                                home.resolve("app").toString(),
                                "--bin-dir",
                                bin.toString())
                        .toArray(String[]::new));

        // then
        result.assertSuccess();
        assertThat(result.output())
                .contains(
                        "Symphony for Trello uninstall",
                        "App checkout: " + home.resolve("app"),
                        "Installed CLI: " + bin.resolve("symphony-trello.ps1"),
                        "Trello boards were not deleted or archived.");
    }

    @Test
    void powershellUninstallerAcceptsMultiplePublicFlagsThroughScriptblockWhenAvailable() throws Exception {
        // given
        List<String> pwsh = powershellCommand();
        assumeFalse(pwsh.isEmpty());

        // when
        ProcessResult result = run(
                nonWindowsPowerShellEnvironment(),
                command(
                                pwsh,
                                "-NoProfile",
                                "-Command",
                                "& ([scriptblock]::Create((Get-Content -Raw './uninstall.ps1'))) "
                                        + "--dry-run --yes --yes-local-data --remove-config --remove-workspaces --remove-state")
                        .toArray(String[]::new));

        // then
        result.assertSuccess();
        assertThat(result.output())
                .contains("Symphony for Trello uninstall", "Trello boards were not deleted or archived.");
    }

    @Test
    void powershellUninstallerRejectsBlankPathValuesThroughScriptblockWhenAvailable() throws Exception {
        // given
        List<String> pwsh = powershellCommand();
        assumeFalse(pwsh.isEmpty());

        // when
        ProcessResult result = run(
                nonWindowsPowerShellEnvironment(),
                command(
                                pwsh,
                                "-NoProfile",
                                "-Command",
                                "& ([scriptblock]::Create((Get-Content -Raw './uninstall.ps1'))) "
                                        + "--dry-run --yes --prefix '' --bin-dir ''")
                        .toArray(String[]::new));

        // then
        assertThat(result.exitCode()).isNotZero();
        assertThat(result.output())
                .contains("Missing value for --prefix")
                .doesNotContain("App checkout:", "Will remove if present:", "/--bin-dir");
    }

    @Test
    void powershellUninstallerRejectsUnsafeAppPathsWhenAvailable() throws Exception {
        // given
        List<String> pwsh = powershellCommand();
        assumeFalse(pwsh.isEmpty());
        Path home = temporaryDirectory.resolve("ps-uninstall-app-home");
        Path symphonyHome = temporaryDirectory.resolve("ps-uninstall-app-symphony-home");
        Path file = temporaryDirectory.resolve("ps-uninstall-app-file");
        Path symlink = temporaryDirectory.resolve("ps-uninstall-app-link");
        Path safeBin = temporaryDirectory.resolve("ps-uninstall-app-bin");
        Files.createDirectories(home);
        Files.writeString(file, "not a directory", StandardCharsets.UTF_8);
        Files.createSymbolicLink(symlink, temporaryDirectory.resolve("ps-uninstall-app-target"));
        Map<String, String> environment = new LinkedHashMap<>(nonWindowsPowerShellEnvironment());
        environment.put("USERPROFILE", home.toString());
        environment.put("SYMPHONY_HOME", symphonyHome.toString());
        List<UnsafeInstallerPath> cases = powershellUnsafeAppPaths(file, symlink, "ps-uninstall-app-link");

        // when
        List<ProcessResult> results = runPowerShellAppPathCases(
                environment, pwsh, "./uninstall.ps1", List.of("--dry-run", "--yes"), cases, safeBin);

        // then
        assertUnsafeAppPathFailures(
                cases, results, OutputStyle.NORMALIZED, "App checkout:", "Will remove if present:", "Installed CLI:");
    }

    @Test
    void powershellUninstallerAcceptsDedicatedAppPathDirectlyUnderHomeWhenAvailable() throws Exception {
        // given
        List<String> pwsh = powershellCommand();
        assumeFalse(pwsh.isEmpty());
        Path home = temporaryDirectory.resolve("ps-uninstall-home-child-home");
        Path app = home.resolve("symphony-trello");
        Path safeBin = temporaryDirectory.resolve("ps-uninstall-home-child-bin");
        Files.createDirectories(home);
        Map<String, String> environment = new LinkedHashMap<>(nonWindowsPowerShellEnvironment());
        environment.put("USERPROFILE", home.toString());

        // when
        ProcessResult result = run(
                environment,
                command(
                                pwsh,
                                "-NoProfile",
                                "-File",
                                "./uninstall.ps1",
                                "--dry-run",
                                "--yes",
                                "--prefix",
                                app.toString(),
                                "--bin-dir",
                                safeBin.toString())
                        .toArray(String[]::new));

        // then
        result.assertSuccess();
        assertThat(result.output()).contains("App checkout: " + app, "Will remove if present:");
    }

    @Test
    void powershellUninstallerRejectsUnsafeCommandDirectoriesWhenAvailable() throws Exception {
        // given
        List<String> pwsh = powershellCommand();
        assumeFalse(pwsh.isEmpty());
        Path home = temporaryDirectory.resolve("ps-uninstall-bin-home");
        Path symphonyHome = temporaryDirectory.resolve("ps-uninstall-bin-symphony-home");
        Path app = temporaryDirectory.resolve("ps-uninstall-bin-app");
        Path file = temporaryDirectory.resolve("ps-uninstall-bin-file");
        Path symlink = temporaryDirectory.resolve("ps-uninstall-bin-state-link");
        Files.createDirectories(home);
        Files.createDirectories(symphonyHome.resolve("state"));
        Files.writeString(file, "not a directory", StandardCharsets.UTF_8);
        Files.createSymbolicLink(symlink, symphonyHome.resolve("state"));
        Map<String, String> environment = new LinkedHashMap<>(nonWindowsPowerShellEnvironment());
        environment.put("USERPROFILE", home.toString());
        environment.put("SYMPHONY_HOME", symphonyHome.toString());
        List<UnsafeCommandDirectory> cases = powershellUnsafeCommandDirectories(
                file,
                symlink,
                "ps-uninstall-bin-state-link",
                new UnsafeCommandDirectory(
                        "state-overlap",
                        symphonyHome.resolve("state").toString(),
                        "--bin-dir must not overlap Symphony app, config, workspace"));
        List<UnsafeCommandDirectory> rejectedCases = cases.stream()
                .filter(InstallerScriptTest::unsafeUninstallCommandDirectoryCase)
                .toList();

        // when
        List<ProcessResult> results = rejectedCases.stream()
                .map(commandDirectory -> runUnchecked(
                        environment,
                        command(
                                        pwsh,
                                        "-NoProfile",
                                        "-File",
                                        "./uninstall.ps1",
                                        "--dry-run",
                                        "--yes",
                                        "--prefix",
                                        app.toString(),
                                        "--bin-dir",
                                        commandDirectory.value())
                                .toArray(String[]::new)))
                .toList();

        // then
        for (int index = 0; index < rejectedCases.size(); index++) {
            UnsafeCommandDirectory commandDirectory = rejectedCases.get(index);
            ProcessResult result = results.get(index);
            assertThat(result.exitCode()).as(commandDirectory.name()).isNotZero();
            assertThat(normalizedWhitespace(result.output()))
                    .as(commandDirectory.name())
                    .contains(commandDirectory.expectedMessage())
                    .doesNotContain("Installed CLI:", "Will remove if present:");
        }
    }

    @Test
    void powershellUninstallerAllowsLegacySymlinkedCommandDirectoryWhenAvailable() throws Exception {
        // given
        List<String> pwsh = powershellCommand();
        assumeFalse(pwsh.isEmpty());
        Path home = temporaryDirectory.resolve("ps-uninstall-symlink-bin-home");
        Path app = temporaryDirectory.resolve("ps-uninstall-symlink-bin-app");
        Path realBin = temporaryDirectory.resolve("ps-uninstall-symlink-bin-target");
        Path symlinkBin = temporaryDirectory.resolve("ps-uninstall-symlink-bin-link");
        Files.createDirectories(home);
        Files.createDirectories(realBin);
        Files.createSymbolicLink(symlinkBin, realBin);
        Map<String, String> environment = new LinkedHashMap<>(nonWindowsPowerShellEnvironment());
        environment.put("USERPROFILE", home.toString());

        // when
        ProcessResult result = run(
                environment,
                command(
                                pwsh,
                                "-NoProfile",
                                "-File",
                                "./uninstall.ps1",
                                "--dry-run",
                                "--yes",
                                "--prefix",
                                app.toString(),
                                "--bin-dir",
                                symlinkBin.toString())
                        .toArray(String[]::new));

        // then
        assertThat(result.exitCode()).as(result.output()).isZero();
        assertThat(normalizedWhitespace(result.output())).contains("Installed CLI:", "Will remove if present:");
    }

    @Test
    void powershellUninstallerAllowsDefaultCommandDirectoryWhenRunFromThatDirectoryWhenAvailable() throws Exception {
        // given
        List<String> pwsh = powershellCommandForDifferentWorkingDirectory();
        assumeFalse(pwsh.isEmpty());
        Path uninstallScript = Path.of("uninstall.ps1").toAbsolutePath();
        Path home = temporaryDirectory.resolve("ps-uninstall-run-from-bin-home");
        Path binDirectory = home.resolve(".local/bin");
        Files.createDirectories(binDirectory);
        Map<String, String> environment = new LinkedHashMap<>(nonWindowsPowerShellEnvironment());
        environment.put("HOME", home.toString());
        environment.put("USERPROFILE", "");

        // when
        ProcessResult result = run(
                environment,
                binDirectory,
                command(pwsh, "-NoProfile", "-File", uninstallScript.toString(), "--dry-run", "--yes")
                        .toArray(String[]::new));

        // then
        result.assertSuccess();
        assertThat(result.output()).contains("Trello boards were not deleted or archived.");
    }

    @Test
    void powershellUninstallerAcceptsDashPrefixedRelativePathValuesThroughScriptblockWhenAvailable() throws Exception {
        // given
        List<String> pwsh = powershellCommand();
        assumeFalse(pwsh.isEmpty());
        Path binDirectory = temporaryDirectory.resolve("-dash-bin");
        Path prefix = temporaryDirectory.resolve("-dash-prefix-app");

        // when
        ProcessResult result = run(
                nonWindowsPowerShellEnvironment(),
                command(
                                pwsh,
                                "-NoProfile",
                                "-Command",
                                "& ([scriptblock]::Create((Get-Content -Raw './uninstall.ps1'))) --dry-run --yes"
                                        + " --prefix "
                                        + powerShellLiteral(prefix.toString())
                                        + " --bin-dir "
                                        + powerShellLiteral(binDirectory.toString()))
                        .toArray(String[]::new));

        // then
        result.assertSuccess();
        assertThat(result.output())
                .contains(prefix.toString(), binDirectory.toString(), "Trello boards were not deleted or archived.");
    }

    @Test
    void powershellUninstallerSkipsInvalidStalePidFilesWhenAvailable() throws Exception {
        // given
        List<String> pwsh = powershellCommand();
        assumeFalse(pwsh.isEmpty());
        Path home = temporaryDirectory.resolve("invalid pid ps home");
        Path prefix = home.resolve("app");
        Path stateHome = home.resolve("state");
        Files.createDirectories(prefix);
        Files.createDirectories(stateHome);
        Files.createFile(prefix.resolve(".symphony-trello-install"));
        Path pidFile = stateHome.resolve("broken.pid");
        Files.writeString(pidFile, "not-a-pid\n", StandardCharsets.UTF_8);

        // when
        ProcessResult result = run(
                nonWindowsPowerShellEnvironment(),
                command(
                                pwsh,
                                "-NoProfile",
                                "-File",
                                "./uninstall.ps1",
                                "--yes",
                                "--symphony-home",
                                home.toString(),
                                "--prefix",
                                prefix.toString(),
                                "--bin-dir",
                                temporaryDirectory.resolve("invalid pid ps bin").toString())
                        .toArray(String[]::new));

        // then
        result.assertSuccess();
        assertThat(result.output()).contains("SKIP  invalid stale pid", "REMOVE  " + prefix);
        assertThat(pidFile).doesNotExist();
        assertThat(prefix).doesNotExist();
    }

    @Test
    void posixUninstallerRequiresSeparateConfirmationForLocalUserData() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        Path symphonyHome = temporaryDirectory.resolve("protected-user-data-home");
        Files.createDirectories(symphonyHome.resolve("config"));
        Files.writeString(symphonyHome.resolve("config/.env"), "TRELLO_API_KEY=secret\n", StandardCharsets.UTF_8);
        Map<String, String> environment = Map.of("SYMPHONY_HOME", symphonyHome.toString());

        // when
        ProcessResult result = run(environment, "bash", "uninstall.sh", "--yes", "--remove-config");

        // then
        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.output()).contains("matching confirmation flag");
        assertThat(symphonyHome.resolve("config/.env")).exists();
    }

    @Test
    void posixUninstallerRefusesToRemoveAppDirectoryContainingPreservedCurrentData() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        Path symphonyHome = temporaryDirectory.resolve("embedded-current-layout-home");
        Files.createDirectories(symphonyHome.resolve("config"));
        Files.createDirectories(symphonyHome.resolve("workspaces"));
        Files.createDirectories(symphonyHome.resolve("state"));
        Files.createFile(symphonyHome.resolve(".symphony-trello-install"));
        Files.writeString(symphonyHome.resolve("config/.env"), "TRELLO_API_KEY=secret\n", StandardCharsets.UTF_8);
        Map<String, String> environment = Map.of("SYMPHONY_HOME", symphonyHome.toString());

        // when
        ProcessResult result = run(
                environment,
                "bash",
                "uninstall.sh",
                "--yes",
                "--prefix",
                symphonyHome.toString(),
                "--bin-dir",
                temporaryDirectory.resolve("bin").toString());

        // then
        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.output())
                .contains(
                        "Refusing to remove app directory because it contains current local data",
                        "CONFIG     " + symphonyHome.resolve("config"),
                        "WORKSPACES " + symphonyHome.resolve("workspaces"),
                        "STATE/LOGS " + symphonyHome.resolve("state"));
        assertThat(symphonyHome.resolve("config/.env")).exists();
    }

    @Test
    void posixUninstallerRejectsAppDirectoryThatWouldContainCurrentDataPaths() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        Path symphonyHome = temporaryDirectory.resolve("embedded-empty-home");
        Files.createDirectories(symphonyHome);
        Files.createFile(symphonyHome.resolve(".symphony-trello-install"));
        Files.writeString(symphonyHome.resolve("app.txt"), "managed app file\n", StandardCharsets.UTF_8);
        Map<String, String> environment = Map.of("SYMPHONY_HOME", symphonyHome.toString());

        // when
        ProcessResult result = run(
                environment,
                "bash",
                "uninstall.sh",
                "--yes",
                "--prefix",
                symphonyHome.toString(),
                "--bin-dir",
                temporaryDirectory.resolve("bin").toString());

        // then
        assertThat(result.exitCode()).as(result.output()).isEqualTo(2);
        assertThat(result.output())
                .contains("--prefix must not overlap Symphony config, workspace, or state directories.")
                .doesNotContain("REMOVE  " + symphonyHome);
        assertThat(symphonyHome).exists();
    }

    @Test
    void posixUninstallerCleansSelectedLocalDataWhenUnmarkedAppRemovalIsDeclined() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        assumeTrue(commandExists("script"));
        Path uninstallScript = Path.of("uninstall.sh").toAbsolutePath();
        Path symphonyHome = temporaryDirectory.resolve("unmarked-declined-home");
        Path appHome = symphonyHome.resolve("app");
        Path configDirectory = symphonyHome.resolve("config");
        Files.createDirectories(appHome);
        Files.createDirectories(configDirectory);
        Files.writeString(configDirectory.resolve(".env"), "TRELLO_API_KEY=secret\n", StandardCharsets.UTF_8);
        Map<String, String> environment = Map.of("SYMPHONY_HOME", symphonyHome.toString());

        // when
        ProcessResult result = runWithPseudoTerminal(
                environment,
                "n\n",
                "bash " + shellQuote(uninstallScript.toString()) + " --yes-local-data --remove-config --prefix "
                        + shellQuote(appHome.toString()) + " --bin-dir "
                        + shellQuote(temporaryDirectory.resolve("bin").toString()));

        // then
        assertThat(result.exitCode()).as(result.output()).isZero();
        assertThat(result.output()).contains("Skipped installer-managed files.", "REMOVE  " + configDirectory);
        assertThat(appHome).exists();
        assertThat(configDirectory).doesNotExist();
    }

    @Test
    void posixUninstallerAllowsRemovingAppDirectoryContainingCurrentDataWithExplicitCleanupScopes() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        Path symphonyHome = temporaryDirectory.resolve("embedded-cleanup-home");
        Files.createDirectories(symphonyHome.resolve("config"));
        Files.createDirectories(symphonyHome.resolve("workspaces"));
        Files.createDirectories(symphonyHome.resolve("state"));
        Files.createFile(symphonyHome.resolve(".symphony-trello-install"));
        Files.writeString(symphonyHome.resolve("config/.env"), "TRELLO_API_KEY=secret\n", StandardCharsets.UTF_8);
        Map<String, String> environment = Map.of("SYMPHONY_HOME", symphonyHome.toString());

        // when
        ProcessResult result = run(
                environment,
                "bash",
                "uninstall.sh",
                "--yes",
                "--yes-local-data",
                "--remove-config",
                "--remove-workspaces",
                "--remove-state",
                "--prefix",
                symphonyHome.toString(),
                "--bin-dir",
                temporaryDirectory.resolve("bin").toString());

        // then
        assertThat(result.exitCode()).as(result.output()).isZero();
        assertThat(result.output()).contains("REMOVE  " + symphonyHome);
        assertThat(symphonyHome).doesNotExist();
    }

    @Test
    void posixUninstallerPromptsBeforeRemovingAppDirectoryContainingCleanupScopedCurrentData() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        assumeTrue(commandExists("script"));
        Path uninstallScript = Path.of("uninstall.sh").toAbsolutePath();
        Path symphonyHome = temporaryDirectory.resolve("embedded-prompted-cleanup-home");
        Files.createDirectories(symphonyHome.resolve("config"));
        Files.createDirectories(symphonyHome.resolve("workspaces"));
        Files.createDirectories(symphonyHome.resolve("state"));
        Files.createFile(symphonyHome.resolve(".symphony-trello-install"));
        Files.writeString(symphonyHome.resolve("config/.env"), "TRELLO_API_KEY=secret\n", StandardCharsets.UTF_8);
        Map<String, String> environment = Map.of("SYMPHONY_HOME", symphonyHome.toString());

        // when
        ProcessResult result = runWithPseudoTerminal(
                environment,
                "y\ny\n",
                "bash " + shellQuote(uninstallScript.toString()) + " --remove-config --remove-workspaces"
                        + " --remove-state --prefix " + shellQuote(symphonyHome.toString())
                        + " --bin-dir "
                        + shellQuote(temporaryDirectory.resolve("bin").toString()));

        // then
        assertThat(result.exitCode()).as(result.output()).isZero();
        assertThat(result.output()).contains("local data selected for cleanup", "REMOVE  " + symphonyHome);
        assertThat(symphonyHome).doesNotExist();
    }

    @Test
    void posixUninstallerRefusesCanonicalEquivalentHomeDirectoryCleanup() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        Path symphonyHome = temporaryDirectory.resolve("home-equivalent");
        Files.createDirectories(symphonyHome);
        Map<String, String> environment = Map.of(
                "HOME", symphonyHome.toString(),
                "SYMPHONY_HOME", temporaryDirectory.resolve("safe-home").toString(),
                "SYMPHONY_TRELLO_CONFIG_DIR", symphonyHome + "/");

        // when
        ProcessResult result = run(
                environment,
                "bash",
                "uninstall.sh",
                "--dry-run",
                "--yes",
                "--yes-local-data",
                "--remove-config",
                "--bin-dir",
                temporaryDirectory.resolve("safe-home-equivalent-bin").toString());

        // then
        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.output()).contains("Refusing dangerous removal path");
        assertThat(symphonyHome).exists();
    }

    @Test
    void installersDeclareExpectedManagedRuntimeCapabilities() throws Exception {
        // given
        String posixInstaller = Files.readString(Path.of("install.sh"), StandardCharsets.UTF_8);
        String powershellInstaller = Files.readString(Path.of("install.ps1"), StandardCharsets.UTF_8);
        String posixUninstaller = Files.readString(Path.of("uninstall.sh"), StandardCharsets.UTF_8);
        String powershellUninstaller = Files.readString(Path.of("uninstall.ps1"), StandardCharsets.UTF_8);

        // when
        var commandNames = List.of("setup-local", "start", "stop", "status", "logs");

        // then
        assertThat(commandNames)
                .allSatisfy(command -> assertThat(posixInstaller).contains(command))
                .allSatisfy(command -> assertThat(powershellInstaller).contains(command));
        assertThat(posixInstaller)
                .contains(
                        "SYMPHONY_HOME",
                        "CONFIG_DIR",
                        "WORKSPACE_ROOT",
                        "STATE_HOME",
                        ".symphony-trello-install",
                        "Java 25+ JDK",
                        "javac",
                        "SYMPHONY_TRELLO_DOTENV",
                        "codex login --device-auth",
                        "Run \\`$login_command\\`, then rerun this installer.",
                        "npm install --global --prefix",
                        "CODEX_NPM_BIN_DIR",
                        "SYMPHONY_TRELLO_APP_HOME",
                        "INSTALLED_CONFIG_DIR",
                        "INSTALLED_WORKSPACE_ROOT",
                        "INSTALLED_STATE_HOME",
                        "-Dsymphony.trello.installed.config.dir",
                        "-Dsymphony.trello.installed.workspace.root",
                        "-Dsymphony.trello.installed.state.home",
                        "absolutize_path",
                        "append_path_setup_to_profile",
                        "TrelloBoardSetupMain",
                        "-DskipTests clean package",
                        "Stopping managed workers before update",
                        "Restarting managed workers after update",
                        "start --all",
                        "pid_command_line",
                        "is_live_managed_pid",
                        "-Dsymphony.trello.managed.app_home",
                        "exec_setup_cli \"\\$@\"")
                .doesNotContain(
                        ",,}",
                        "printf -v quoted '%q'",
                        "workflow_state_name",
                        "default_workflow",
                        "wait_for_exit",
                        "has_cli_option",
                        "cli_option_value",
                        "setup_local_lifecycle_subcommand",
                        "is_managed_pid",
                        "mapfile",
                        "nohup");
        assertThat(posixInstaller).doesNotContain("[[:space:]");
        assertThat(powershellInstaller)
                .contains(
                        "[Alias(\"-dry-run\", \"dry-run\", \"--dry-run\")]",
                        "[Alias(\"-no-onboard\", \"no-onboard\", \"--no-onboard\")]",
                        "LASTEXITCODE",
                        "SYMPHONY_TRELLO_DOTENV",
                        "SYMPHONY_TRELLO_APP_HOME",
                        "Install-CodexOrExit",
                        "if (-not $NoOnboard) {",
                        "Java 25+ JDK",
                        "javac",
                        "SymphonyHome",
                        "$ConfigDir",
                        "$WorkspaceRoot",
                        "$StateHome",
                        "symphony-trello.cmd",
                        ".symphony-trello-install",
                        "TrelloBoardSetupMain",
                        "-DskipTests clean package",
                        "Stopping managed workers before update",
                        "Restarting managed workers after update",
                        "start --all",
                        "Invoke-Step \"$BinDir\\symphony-trello.ps1 setup-local\"",
                        "`$InstalledConfigDir",
                        "`$InstalledWorkspaceRoot",
                        "`$InstalledStateHome",
                        "-Dsymphony.trello.installed.config.dir",
                        "-Dsymphony.trello.installed.workspace.root",
                        "-Dsymphony.trello.installed.state.home",
                        "Add-BinDirToUserPath",
                        "Join-Path `$ConfigDir \".env\"",
                        "Assert-AvailableAfterInstall",
                        "Open a new PowerShell window with the Symphony bin directory on PATH.",
                        "Run '$loginCommand', then rerun this installer.",
                        "codex.cmd",
                        "codex.ps1",
                        "$CodexCommand = Join-Path `$CodexNpmPrefix \"codex.ps1\"")
                .contains("ConvertTo-PowerShellLiteral")
                .contains("$PrefixLiteral = ConvertTo-PowerShellLiteral $Prefix")
                .contains("`$AppHome = $PrefixLiteral")
                .contains("`$env:SYMPHONY_TRELLO_COMMAND = $CommandLiteral")
                .contains("`$env:SYMPHONY_TRELLO_COMMAND = `$commandPath")
                .doesNotContain(
                        "SHA256]::HashData",
                        "Test-CliOption",
                        "Get-CliOptionValue",
                        "Test-SetupLocalLifecycleSubcommand",
                        "Get-WorkflowStateName",
                        "Get-DefaultWorkflow",
                        "Get-DefaultEnv",
                        "Stop-AndWaitManagedProcess",
                        "Start-Process -FilePath \"java\"",
                        "-Dsymphony.trello.managed.app_home",
                        "Test-ManagedPid");
        assertThat(posixUninstaller)
                .contains(
                        "/dev/tty",
                        "WOULD STOP",
                        "STOP",
                        "kill",
                        "wait_for_exit",
                        "absolutize_path",
                        "SKIP  stale pid does not belong to this install: $(worker_label \"$pid_file\")",
                        "--remove-config",
                        "--remove-workspaces",
                        "--remove-state",
                        "--yes-local-data",
                        ".symphony-trello-install",
                        "Trello boards were not deleted or archived.");
        assertThat(posixUninstaller).doesNotContain(",,}");
        assertThat(powershellUninstaller)
                .contains(
                        "WOULD STOP",
                        "[Alias(\"-dry-run\", \"dry-run\", \"--dry-run\")]",
                        "[Alias(\"-yes\", \"--yes\")]",
                        "[Alias(\"-yes-local-data\", \"yes-local-data\", \"--yes-local-data\")]",
                        "[Alias(\"-remove-config\", \"remove-config\", \"--remove-config\")]",
                        "symphony-trello.cmd",
                        "SymphonyHome",
                        "RemoveConfig",
                        "RemoveWorkspaces",
                        "RemoveState",
                        "RemoveAllLocalData",
                        "YesLocalData",
                        ".symphony-trello-install",
                        "SKIP  stale pid does not belong to this install: $(Get-WorkerLabel $pidFile.BaseName)",
                        "Stop-AndWaitManagedProcess",
                        "Stop-Process",
                        "Wait-Process",
                        "Test-ManagedPid",
                        "Remove-ManagedCodexArtifacts",
                        "Test-ManagedCodexWrapper",
                        "[int]::TryParse",
                        "SKIP  invalid stale pid",
                        "Test-Path -LiteralPath $safePath",
                        "Remove-Item -Recurse -Force -LiteralPath $safePath",
                        "Assert-AppRemovalPreservesCurrentData",
                        "Test-SameOrInsidePath",
                        "Test-SameFileContent",
                        "[System.IO.Path]::GetFullPath",
                        "(Get-Location).Path")
                .doesNotContain(
                        "Remove-ManagedPath \"$BinDir\\codex.exe\"",
                        "Remove-ManagedPath \"$BinDir\\codex.cmd\"",
                        "Remove-ManagedPath \"$BinDir\\codex.ps1\"");
    }

    @Test
    void installersOfferPathSetupBeforeGuidedSetupCanAbort() throws Exception {
        // given
        String posixInstaller = Files.readString(Path.of("install.sh"), StandardCharsets.UTF_8);
        String powershellInstaller = Files.readString(Path.of("install.ps1"), StandardCharsets.UTF_8);

        // when
        int posixPathSetup = posixInstaller.indexOf("\noffer_path_setup\n\nif [[ \"$NO_ONBOARD\" == false ]]");
        int powershellPathSetup = powershellInstaller.indexOf("\nOffer-PathSetup\n\nif (-not $NoOnboard)");

        // then
        assertThat(posixPathSetup)
                .as("POSIX installer must offer PATH setup before setup-local or restart can abort")
                .isNotNegative();
        assertThat(powershellPathSetup)
                .as("PowerShell installer must offer PATH setup before setup-local or restart can abort")
                .isNotNegative();
    }

    @Test
    void posixInstallerAddsPathSetupAfterInstallingCodexWithUserLocalNpm() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        assumeTrue(commandExists("git"));
        assumeTrue(commandExists("script"));
        Path installScript = Path.of("install.sh").toAbsolutePath();
        Path sourceRepository = createSourceRepository(temporaryDirectory);
        Path fakeBin = createFakeToolchain(temporaryDirectory);
        Files.delete(fakeBin.resolve("codex"));
        writeCommandProxy(fakeBin, "bash", "/bin/bash");
        writeExecutable(
                fakeBin.resolve("git"),
                """
                #!/usr/bin/env bash
                exec /usr/bin/git "$@"
                """);
        writeCommandProxy(fakeBin, "mkdir", "/bin/mkdir");
        writeCommandProxy(fakeBin, "uname", "/bin/uname");
        writeCommandProxy(fakeBin, "chmod", "/bin/chmod");
        writeCommandProxy(fakeBin, "dirname", "/usr/bin/dirname");
        writeCommandProxy(fakeBin, "sed", "/bin/sed");
        writeCommandProxy(fakeBin, "head", "/usr/bin/head");
        writeCommandProxy(fakeBin, "cat", "/bin/cat");
        writeCommandProxy(fakeBin, "ln", "/bin/ln");
        writeCommandProxy(fakeBin, "tr", "/usr/bin/tr");
        writeCommandProxy(fakeBin, "sleep", "/bin/sleep");
        writeCommandProxy(fakeBin, "nohup", "/usr/bin/nohup");
        writeCommandProxy(fakeBin, "env", "/usr/bin/env");
        writeCommandProxy(fakeBin, "rm", "/bin/rm");
        writeCommandProxy(fakeBin, "basename", "/usr/bin/basename");
        writeCommandProxy(fakeBin, "sha256sum", "/usr/bin/sha256sum");
        writeCommandProxy(fakeBin, "awk", "/usr/bin/awk");
        writeCommandProxy(fakeBin, "grep", "/bin/grep");
        writeExecutable(
                fakeBin.resolve("npm"),
                """
                #!/usr/bin/env bash
                set -euo pipefail
                prefix=""
                while [[ $# -gt 0 ]]; do
                  case "${1:-}" in
                    --prefix) prefix="${2:?missing prefix}"; shift 2 ;;
                    *) shift ;;
                  esac
                done
                mkdir -p "$prefix/bin"
                printf '#!/usr/bin/env bash\\nexit 0\\n' > "$prefix/bin/codex"
                chmod +x "$prefix/bin/codex"
                echo "npm $*" >> "${SYMPHONY_FAKE_LOG:?}"
                """);
        Path symphonyHome = temporaryDirectory.resolve("npm-codex-home");
        Path binDirectory = temporaryDirectory.resolve("npm-codex-bin");
        Path fakeLog = temporaryDirectory.resolve("npm-codex-fake-tools.log");
        Path uninstallScript = Path.of("uninstall.sh").toAbsolutePath();
        Path home = temporaryDirectory.resolve("npm-codex-user-home");
        Map<String, String> environment = Map.of(
                "PATH", fakeBin.toString(),
                "HOME", home.toString(),
                "SYMPHONY_TRELLO_REPO_URL", sourceRepository.toUri().toString(),
                "SYMPHONY_HOME", symphonyHome.toString(),
                "SYMPHONY_FAKE_LOG", fakeLog.toString());

        // when
        ProcessResult install = runWithPseudoTerminal(
                environment,
                "y\napi-key\napi-token\nNpm Codex Board\n\n\n",
                "bash " + shellQuote(installScript.toString()) + " --bin-dir " + shellQuote(binDirectory.toString()));
        Map<String, String> uninstallEnvironment = new LinkedHashMap<>(environment);
        uninstallEnvironment.put("PATH", fakeBin + ":/bin:/usr/bin");
        ProcessResult uninstall = run(
                uninstallEnvironment,
                "bash",
                uninstallScript.toString(),
                "--yes",
                "--bin-dir",
                binDirectory.toString());

        // then
        assertThat(install.exitCode()).as(install.output()).isZero();
        assertThat(uninstall.exitCode()).as(uninstall.output()).isZero();
        assertThat(install.output())
                .contains(
                        "Install Codex CLI with Symphony-managed npm.",
                        "Install location: " + symphonyHome.resolve("npm"),
                        "This keeps system-wide npm packages unchanged.",
                        "Added " + binDirectory + " to PATH in " + home.resolve(".bashrc"),
                        "Added " + binDirectory + " to PATH in " + home.resolve(".profile"));
        assertThat(binDirectory.resolve("codex")).doesNotExist();
        assertThat(symphonyHome.resolve("npm")).doesNotExist();
    }

    @ParameterizedTest(name = "pinned ref type: {0}")
    @ValueSource(strings = {"tag", "sha"})
    void posixInstallerRerunsPinnedNonBranchRefsWithoutPulling(String refType) throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        assumeTrue(commandExists("git"));
        Path installScript = Path.of("install.sh").toAbsolutePath();
        Path sourceRepository = createSourceRepository(temporaryDirectory);
        String ref = pinnedRef(sourceRepository, refType);
        Path fakeBin = createFakeToolchain(temporaryDirectory);
        Path symphonyHome = temporaryDirectory.resolve("pinned-" + refType + "-home");
        Path installPrefix = symphonyHome.resolve("app");
        Path binDirectory = temporaryDirectory.resolve("pinned-" + refType + "-bin");
        Path fakeLog = temporaryDirectory.resolve("pinned-" + refType + "-fake-tools.log");
        Map<String, String> environment = Map.of(
                "PATH", fakeBin + System.getProperty("path.separator") + System.getenv("PATH"),
                "SYMPHONY_TRELLO_REPO_URL", sourceRepository.toUri().toString(),
                "SYMPHONY_TRELLO_REF", ref,
                "SYMPHONY_HOME", symphonyHome.toString(),
                "SYMPHONY_FAKE_LOG", fakeLog.toString());

        // when
        ProcessResult install = run(
                environment, "bash", installScript.toString(), "--no-onboard", "--bin-dir", binDirectory.toString());
        addSourceRepositoryCommit(sourceRepository, "AFTER_PINNED_REF", "newer\n");
        ProcessResult rerun = run(
                environment, "bash", installScript.toString(), "--no-onboard", "--bin-dir", binDirectory.toString());

        // then
        assertThat(install.exitCode()).as(install.output()).isZero();
        assertThat(rerun.exitCode()).as(rerun.output()).isZero();
        assertThat(installPrefix.resolve("AFTER_PINNED_REF")).doesNotExist();
    }

    @Test
    void posixInstallerUpdateRunsCleanBuildAndRestartsManagedWorkers() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        assumeTrue(commandExists("git"));
        assumeTrue(commandExists("script"));
        Path installScript = Path.of("install.sh").toAbsolutePath();
        Path sourceRepository = createSourceRepository(temporaryDirectory);
        Path fakeBin = createFakeToolchain(temporaryDirectory);
        Path symphonyHome = temporaryDirectory.resolve("update-restart-home");
        Path binDirectory = temporaryDirectory.resolve("update-restart-bin");
        Path fakeLog = temporaryDirectory.resolve("update-restart-fake-tools.log");
        Files.createFile(temporaryDirectory.resolve("codex-authenticated"));
        Map<String, String> environment = Map.of(
                "PATH", fakeBin + System.getProperty("path.separator") + System.getenv("PATH"),
                "SYMPHONY_TRELLO_REPO_URL", sourceRepository.toUri().toString(),
                "SYMPHONY_HOME", symphonyHome.toString(),
                "SYMPHONY_FAKE_LOG", fakeLog.toString());

        try {

            // when
            ProcessResult install = runWithPseudoTerminal(
                    environment,
                    "api-key\napi-token\nDocs Queue\n\n\n",
                    "bash " + shellQuote(installScript.toString()) + " --bin-dir "
                            + shellQuote(binDirectory.toString()));
            addSourceRepositoryCommit(sourceRepository, "UPDATED", "updated\n");
            ProcessResult update = runWithPseudoTerminal(
                    environment,
                    "api-key\napi-token\nDocs Queue\n\n\n",
                    "bash " + shellQuote(installScript.toString()) + " --bin-dir "
                            + shellQuote(binDirectory.toString()));

            // then
            assertThat(install.exitCode()).as(install.output()).isZero();
            assertThat(update.exitCode()).as(update.output()).isZero();
            assertThat(update.output())
                    .contains(
                            "Stopping managed workers before update...",
                            "Restarting managed workers after update...",
                            "Stopped WORKFLOW.docs-queue.md",
                            "Starting setup...");
            assertThat(Files.readString(fakeLog, StandardCharsets.UTF_8))
                    .contains("mvnw -q -f " + symphonyHome.resolve("app/pom.xml") + " -DskipTests clean package")
                    .containsSubsequence(
                            "TrelloBoardSetupMain stop",
                            "mvnw -q -f " + symphonyHome.resolve("app/pom.xml") + " -DskipTests clean package",
                            "TrelloBoardSetupMain setup-local",
                            "TrelloBoardSetupMain start",
                            "--all");
        } finally {
            if (Files.exists(binDirectory.resolve("symphony-trello"))) {
                run(environment, binDirectory.resolve("symphony-trello").toString(), "stop");
            }
        }
    }

    @Test
    void posixNoOnboardUpdateRestartsStoppedWorkersWithStartAll() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        assumeTrue(commandExists("git"));
        assumeTrue(commandExists("script"));
        Path installScript = Path.of("install.sh").toAbsolutePath();
        Path sourceRepository = createSourceRepository(temporaryDirectory);
        Path fakeBin = createFakeToolchain(temporaryDirectory);
        Path symphonyHome = temporaryDirectory.resolve("no-onboard-update-home");
        Path binDirectory = temporaryDirectory.resolve("no-onboard-update-bin");
        Path fakeLog = temporaryDirectory.resolve("no-onboard-update-fake-tools.log");
        Files.createFile(temporaryDirectory.resolve("codex-authenticated"));
        Map<String, String> environment = Map.of(
                "PATH", fakeBin + System.getProperty("path.separator") + System.getenv("PATH"),
                "SYMPHONY_TRELLO_REPO_URL", sourceRepository.toUri().toString(),
                "SYMPHONY_HOME", symphonyHome.toString(),
                "SYMPHONY_FAKE_LOG", fakeLog.toString());

        try {
            ProcessResult install = runWithPseudoTerminal(
                    environment,
                    "n\napi-key\napi-token\nDocs Queue\n",
                    "bash " + shellQuote(installScript.toString()) + " --bin-dir "
                            + shellQuote(binDirectory.toString()));
            addSourceRepositoryCommit(sourceRepository, "UPDATED", "updated\n");

            // when
            ProcessResult update = run(
                    environment,
                    "bash",
                    installScript.toString(),
                    "--no-onboard",
                    "--bin-dir",
                    binDirectory.toString());

            // then
            assertThat(install.exitCode()).as(install.output()).isZero();
            assertThat(update.exitCode()).as(update.output()).isZero();
            assertThat(update.output())
                    .contains(
                            "Stopping managed workers before update...", "Restarting managed workers after update...");
            assertThat(Files.readString(fakeLog, StandardCharsets.UTF_8))
                    .containsSubsequence("TrelloBoardSetupMain stop", "TrelloBoardSetupMain start", "--all");
        } finally {
            if (Files.exists(binDirectory.resolve("symphony-trello"))) {
                run(environment, binDirectory.resolve("symphony-trello").toString(), "stop");
            }
        }
    }

    @Test
    void posixUpdateRemovesStalePidFilesWhenInstalledCommandIsMissing() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        assumeTrue(commandExists("git"));
        assumeTrue(commandExists("sleep"));
        Path installScript = Path.of("install.sh").toAbsolutePath();
        Path sourceRepository = createSourceRepository(temporaryDirectory);
        Path fakeBin = createFakeToolchain(temporaryDirectory);
        Path symphonyHome = temporaryDirectory.resolve("stale-pid-missing-command-home");
        Path binDirectory = temporaryDirectory.resolve("stale-pid-missing-command-bin");
        Path fakeLog = temporaryDirectory.resolve("stale-pid-missing-command-fake-tools.log");
        Files.createFile(temporaryDirectory.resolve("codex-authenticated"));
        Map<String, String> environment = Map.of(
                "PATH", fakeBin + System.getProperty("path.separator") + System.getenv("PATH"),
                "SYMPHONY_TRELLO_REPO_URL", sourceRepository.toUri().toString(),
                "SYMPHONY_HOME", symphonyHome.toString(),
                "SYMPHONY_FAKE_LOG", fakeLog.toString());

        ProcessResult install = run(
                environment, "bash", installScript.toString(), "--no-onboard", "--bin-dir", binDirectory.toString());
        Path command = binDirectory.resolve("symphony-trello");
        Path stalePid = symphonyHome.resolve("state/WORKFLOW.stale.pid");
        Path zeroPid = symphonyHome.resolve("state/WORKFLOW.zero.pid");
        Path reusedPid = symphonyHome.resolve("state/WORKFLOW.reused.pid");
        Files.writeString(stalePid, "999999\n", StandardCharsets.UTF_8);
        Files.writeString(zeroPid, "0\n", StandardCharsets.UTF_8);
        Files.delete(command);
        addSourceRepositoryCommit(sourceRepository, "UPDATED", "updated\n");
        Process unrelated = new ProcessBuilder("sleep", "60").start();

        try {
            Files.writeString(reusedPid, unrelated.pid() + "\n", StandardCharsets.UTF_8);

            // when
            ProcessResult update = run(
                    environment,
                    "bash",
                    installScript.toString(),
                    "--no-onboard",
                    "--bin-dir",
                    binDirectory.toString());

            // then
            assertThat(install.exitCode()).as(install.output()).isZero();
            assertThat(update.exitCode()).as(update.output()).isZero();
            assertThat(update.output())
                    .contains("Removing stale managed worker pid files before update...")
                    .doesNotContain("Stopping managed workers before update...")
                    .doesNotContain("Stop the running Symphony worker processes manually");
            assertThat(command).isExecutable();
            assertThat(stalePid).doesNotExist();
            assertThat(zeroPid).doesNotExist();
            assertThat(reusedPid).doesNotExist();
            assertThat(Files.readString(fakeLog, StandardCharsets.UTF_8)).doesNotContain("TrelloBoardSetupMain stop");
        } finally {
            unrelated.destroyForcibly();
        }
    }

    @Test
    void posixInstallerGeneratedCommandHandlesPathsWithShellMetacharacters() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        assumeTrue(commandExists("git"));
        Path installScript = Path.of("install.sh").toAbsolutePath();
        Path sourceRepository = createSourceRepository(temporaryDirectory);
        Path fakeBin = createFakeToolchain(temporaryDirectory);
        Path symphonyHome = temporaryDirectory.resolve("home $value 'quoted'");
        Path binDirectory = temporaryDirectory.resolve("bin $value 'quoted'");
        Path fakeLog = temporaryDirectory.resolve("metachar-fake-tools.log");
        Map<String, String> environment = Map.of(
                "PATH", fakeBin + System.getProperty("path.separator") + System.getenv("PATH"),
                "SYMPHONY_TRELLO_REPO_URL", sourceRepository.toUri().toString(),
                "SYMPHONY_TRELLO_REF", "main",
                "SYMPHONY_HOME", symphonyHome.toString(),
                "SYMPHONY_FAKE_LOG", fakeLog.toString());

        // when
        ProcessResult install = run(
                environment, "bash", installScript.toString(), "--no-onboard", "--bin-dir", binDirectory.toString());
        assertThat(install.exitCode()).as(install.output()).isZero();
        ProcessResult syntax = run(
                environment,
                "bash",
                "-n",
                binDirectory.resolve("symphony-trello").toString());
        ProcessResult status =
                run(environment, binDirectory.resolve("symphony-trello").toString(), "status");

        // then
        assertThat(syntax.exitCode()).isZero();
        assertThat(status.exitCode()).isZero();
        assertThat(status.output()).contains("No managed Symphony process found");
        assertThat(binDirectory.resolve("symphony-trello"))
                .content(StandardCharsets.UTF_8)
                .contains("APP_HOME='"
                        + symphonyHome
                                .resolve("app")
                                .toAbsolutePath()
                                .normalize()
                                .toString()
                                .replace("'", "'\\''")
                        + "'");
        assertThat(binDirectory.resolve("symphony-trello"))
                .content(StandardCharsets.UTF_8)
                .contains("export SYMPHONY_TRELLO_COMMAND='"
                        + binDirectory
                                .resolve("symphony-trello")
                                .toAbsolutePath()
                                .normalize()
                                .toString()
                                .replace("'", "'\\''")
                        + "'");
    }

    @Test
    void posixInstallerPersistsHomebrewOpenJdkPathInWrapper() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        assumeTrue(commandExists("git"));
        Path sourceRepository = createSourceRepository(temporaryDirectory);
        Path fakeBin = temporaryDirectory.resolve("fake-brew-bin");
        Path brewOpenJdkBin = temporaryDirectory
                .resolve("homebrew")
                .resolve("opt")
                .resolve("openjdk@25")
                .resolve("bin");
        Path symphonyHome = temporaryDirectory.resolve("homebrew-home");
        Path binDirectory = temporaryDirectory.resolve("homebrew-bin");
        Path fakeLog = temporaryDirectory.resolve("homebrew-installer.log");
        Files.createDirectories(fakeBin);
        Files.createDirectories(brewOpenJdkBin);
        writeExecutable(
                fakeBin.resolve("brew"),
                """
                #!/usr/bin/env bash
                set -euo pipefail
                if [[ "${1:-}" == "--prefix" && "${2:-}" == "openjdk@25" ]]; then
                  printf '%s\\n' "$SYMPHONY_FAKE_BREW_OPENJDK_PREFIX"
                  exit 0
                fi
                echo "brew $*" >> "${SYMPHONY_FAKE_LOG:?}"
                exit 0
                """);
        writeExecutable(
                brewOpenJdkBin.resolve("java"),
                """
                #!/usr/bin/env bash
                set -euo pipefail
                echo 'openjdk version "25.0.1" 2026-04-21' >&2
                """);
        writeExecutable(
                brewOpenJdkBin.resolve("javac"),
                """
                #!/usr/bin/env bash
                set -euo pipefail
                echo 'javac 25.0.1'
                """);
        Map<String, String> environment = Map.of(
                "PATH", fakeBin + ":/usr/bin:/bin",
                "SYMPHONY_TRELLO_TEST_OS", "Darwin",
                "SYMPHONY_TRELLO_TEST_ARCH", "arm64",
                "SYMPHONY_TRELLO_REPO_URL", sourceRepository.toUri().toString(),
                "SYMPHONY_HOME", symphonyHome.toString(),
                "SYMPHONY_FAKE_LOG", fakeLog.toString(),
                "SYMPHONY_FAKE_BREW_OPENJDK_PREFIX", brewOpenJdkBin.getParent().toString());

        // when
        ProcessResult install =
                run(environment, "bash", "install.sh", "--no-onboard", "--bin-dir", binDirectory.toString());

        // then
        assertThat(install.exitCode()).as(install.output()).isZero();
        assertThat(binDirectory.resolve("symphony-trello"))
                .content(StandardCharsets.UTF_8)
                .contains(
                        "BREW_OPENJDK_BIN='" + brewOpenJdkBin.toAbsolutePath().normalize() + "'",
                        "export PATH=\"$BREW_OPENJDK_BIN:$PATH\"");
    }

    @Test
    void posixInstallerRefusesUnmarkedUnrelatedExistingCheckout() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        assumeTrue(commandExists("git"));
        Path sourceRepository = createSourceRepository(temporaryDirectory);
        Path existingCheckout = createUnmarkedCheckout("unrelated-posix-checkout");
        Path fakeBin = createFakeToolchain(temporaryDirectory);
        Path fakeLog = temporaryDirectory.resolve("unrelated-posix.log");
        Map<String, String> environment = Map.of(
                "PATH", fakeBin + File.pathSeparator + System.getenv("PATH"),
                "SYMPHONY_TRELLO_REPO_URL", sourceRepository.toUri().toString(),
                "SYMPHONY_FAKE_LOG", fakeLog.toString());

        // when
        ProcessResult result = run(
                environment,
                "bash",
                "install.sh",
                "--no-onboard",
                "--prefix",
                existingCheckout.toString(),
                "--bin-dir",
                temporaryDirectory.resolve("unrelated-posix-bin").toString());

        // then
        assertThat(result.exitCode()).as(result.output()).isEqualTo(2);
        assertThat(result.output())
                .contains("Refusing to update existing Git checkout without Symphony installer marker");
        assertThat(existingCheckout.resolve(".symphony-trello-install")).doesNotExist();
    }

    @Test
    void posixInstallerUpdatesMarkedCheckoutOriginBeforeFetching() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        assumeTrue(commandExists("git"));
        Path oldRepository = createSourceRepository(temporaryDirectory.resolve("old-origin"));
        Path sourceRepository = createSourceRepository(temporaryDirectory.resolve("new-origin"));
        Path fakeBin = createFakeToolchain(temporaryDirectory);
        Path symphonyHome = temporaryDirectory.resolve("remote-migration-home");
        Path appHome = symphonyHome.resolve("app");
        Path binDirectory = temporaryDirectory.resolve("remote-migration-bin");
        Path fakeLog = temporaryDirectory.resolve("remote-migration.log");
        Files.createDirectories(appHome);
        run(Map.of(), "git", "-C", appHome.toString(), "init", "-b", "main").assertSuccess();
        run(
                        Map.of(),
                        "git",
                        "-C",
                        appHome.toString(),
                        "remote",
                        "add",
                        "origin",
                        oldRepository.toUri().toString())
                .assertSuccess();
        Files.createFile(appHome.resolve(".symphony-trello-install"));
        Map<String, String> environment = Map.of(
                "PATH", fakeBin + File.pathSeparator + System.getenv("PATH"),
                "SYMPHONY_TRELLO_REPO_URL", sourceRepository.toUri().toString(),
                "SYMPHONY_HOME", symphonyHome.toString(),
                "SYMPHONY_FAKE_LOG", fakeLog.toString());

        // when
        ProcessResult result =
                run(environment, "bash", "install.sh", "--no-onboard", "--bin-dir", binDirectory.toString());

        // then
        result.assertSuccess();
        ProcessResult origin = run(Map.of(), "git", "-C", appHome.toString(), "remote", "get-url", "origin");
        origin.assertSuccess();
        assertThat(origin.output().trim()).isEqualTo(sourceRepository.toUri().toString());
        assertThat(result.output()).contains("remote set-url origin " + sourceRepository.toUri());
    }

    @Test
    void posixInstallerReplacesMarkedArchiveInstallWhenSwitchingToSourceCheckout() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        assumeTrue(commandExists("git"));
        Path sourceRepository = createSourceRepository(temporaryDirectory);
        Path fakeBin = createFakeToolchain(temporaryDirectory);
        Path symphonyHome = temporaryDirectory.resolve("archive-to-source-home");
        Path appHome = symphonyHome.resolve("app");
        Path binDirectory = temporaryDirectory.resolve("archive-to-source-bin");
        Path fakeLog = temporaryDirectory.resolve("archive-to-source.log");
        Files.createDirectories(appHome);
        Files.writeString(
                appHome.resolve(".symphony-trello-install"), "installer-managed archive\n", StandardCharsets.UTF_8);
        Files.writeString(appHome.resolve("old-archive-file.txt"), "old archive content\n", StandardCharsets.UTF_8);
        Map<String, String> environment = Map.of(
                "PATH", fakeBin + File.pathSeparator + System.getenv("PATH"),
                "SYMPHONY_HOME", symphonyHome.toString(),
                "SYMPHONY_FAKE_LOG", fakeLog.toString());

        // when
        ProcessResult result = run(
                environment,
                "bash",
                "install.sh",
                "--no-onboard",
                "--from-source",
                "--repo",
                sourceRepository.toUri().toString(),
                "--ref",
                "main",
                "--bin-dir",
                binDirectory.toString());

        // then
        result.assertSuccess();
        assertThat(appHome.resolve(".git")).isDirectory();
        assertThat(appHome.resolve("old-archive-file.txt")).doesNotExist();
        assertThat(result.output()).contains("rm -rf " + appHome, "git clone");
    }

    @Test
    void powershellInstallerRefusesUnmarkedUnrelatedExistingCheckoutWhenAvailable() throws Exception {
        // given
        List<String> pwsh = powershellCommand();
        assumeFalse(pwsh.isEmpty());
        assumeTrue(commandExists("git"));
        Path sourceRepository = createSourceRepository(temporaryDirectory);
        Path existingCheckout = createUnmarkedCheckout("unrelated-powershell-checkout");
        Path fakeBin = createFakeToolchain(temporaryDirectory);
        Path fakeLog = temporaryDirectory.resolve("unrelated-powershell.log");
        Map<String, String> environment = new LinkedHashMap<>(nonWindowsPowerShellEnvironment());
        environment.put("PATH", fakeBin + File.pathSeparator + System.getenv("PATH"));
        environment.put("SYMPHONY_FAKE_LOG", fakeLog.toString());

        // when
        ProcessResult result = run(
                environment,
                command(
                                pwsh,
                                "-NoProfile",
                                "-File",
                                "./install.ps1",
                                "--no-onboard",
                                "--prefix",
                                existingCheckout.toString(),
                                "--bin-dir",
                                temporaryDirectory
                                        .resolve("unrelated-powershell-bin")
                                        .toString(),
                                "--repo",
                                sourceRepository.toUri().toString())
                        .toArray(String[]::new));

        // then
        assertThat(result.exitCode()).as(result.output()).isEqualTo(1);
        assertThat(result.output())
                .contains(
                        "Refusing to update existing Git checkout without Symphony installer",
                        "marker:",
                        "Use",
                        "a dedicated app checkout path");
        assertThat(existingCheckout.resolve(".symphony-trello-install")).doesNotExist();
    }

    @Test
    void powershellInstallerReplacesMarkedArchiveInstallWhenSwitchingToSourceCheckoutWhenAvailable() throws Exception {
        // given
        List<String> pwsh = powershellCommand();
        assumeFalse(pwsh.isEmpty());
        assumeTrue(commandExists("git"));
        Path sourceRepository = temporaryDirectory.resolve("ps source executable cmd");
        Files.createDirectories(sourceRepository);
        Files.writeString(sourceRepository.resolve("pom.xml"), "<project />\n", StandardCharsets.UTF_8);
        Files.writeString(
                sourceRepository.resolve("mvnw.cmd"),
                """
                #!/usr/bin/env bash
                set -euo pipefail
                echo "mvnw.cmd $*" >> "${SYMPHONY_FAKE_LOG:?}"
                app_home="$(cd "$(dirname "$0")" && pwd -P)"
                mkdir -p "$app_home/target/quarkus-app/app" "$app_home/target/quarkus-app/lib/main" "$app_home/target/quarkus-app/quarkus"
                : > "$app_home/target/quarkus-app/quarkus-run.jar"
                """,
                StandardCharsets.UTF_8);
        sourceRepository.resolve("mvnw.cmd").toFile().setExecutable(true);
        run(Map.of(), "git", "-C", sourceRepository.toString(), "init", "-b", "main")
                .assertSuccess();
        run(Map.of(), "git", "-C", sourceRepository.toString(), "config", "user.name", "Test User")
                .assertSuccess();
        run(Map.of(), "git", "-C", sourceRepository.toString(), "config", "user.email", "test@example.invalid")
                .assertSuccess();
        run(Map.of(), "git", "-C", sourceRepository.toString(), "add", ".").assertSuccess();
        run(Map.of(), "git", "-C", sourceRepository.toString(), "commit", "-m", "Initial test source")
                .assertSuccess();
        Path fakeBin = createFakeToolchain(temporaryDirectory);
        Path symphonyHome = temporaryDirectory.resolve("ps-archive-to-source-home");
        Path appHome = symphonyHome.resolve("app");
        Path binDirectory = temporaryDirectory.resolve("ps-archive-to-source-bin");
        Path fakeLog = temporaryDirectory.resolve("ps-archive-to-source.log");
        Files.createDirectories(appHome);
        Files.writeString(
                appHome.resolve(".symphony-trello-install"), "installer-managed archive\n", StandardCharsets.UTF_8);
        Files.writeString(appHome.resolve("old-archive-file.txt"), "old archive content\n", StandardCharsets.UTF_8);
        Map<String, String> environment = new LinkedHashMap<>(nonWindowsPowerShellEnvironment());
        environment.put("PATH", fakeBin + File.pathSeparator + System.getenv("PATH"));
        environment.put("SYMPHONY_HOME", symphonyHome.toString());
        environment.put("SYMPHONY_FAKE_LOG", fakeLog.toString());

        // when
        ProcessResult result = run(
                environment,
                command(
                                pwsh,
                                "-NoProfile",
                                "-File",
                                "./install.ps1",
                                "--no-onboard",
                                "--from-source",
                                "--repo",
                                sourceRepository.toUri().toString(),
                                "--ref",
                                "main",
                                "--bin-dir",
                                binDirectory.toString())
                        .toArray(String[]::new));

        // then
        result.assertSuccess();
        assertThat(appHome.resolve(".git")).isDirectory();
        assertThat(appHome.resolve("old-archive-file.txt")).doesNotExist();
        assertThat(result.output()).contains("remove existing release archive app " + appHome, "git clone");
    }

    @Test
    void posixStartResolvesRelativeEnvAndWorkflowFromCallerDirectory() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        assumeTrue(commandExists("git"));
        assumeTrue(commandExists("timeout"));
        Path installScript = Path.of("install.sh").toAbsolutePath();
        Path uninstallScript = Path.of("uninstall.sh").toAbsolutePath();
        Path sourceRepository = createSourceRepository(temporaryDirectory);
        Path fakeBin = createFakeToolchain(temporaryDirectory);
        Path symphonyHome = temporaryDirectory.resolve("relative-home");
        Path binDirectory = temporaryDirectory.resolve("relative-bin");
        Path callerDirectory = temporaryDirectory.resolve("caller");
        Path fakeLog = temporaryDirectory.resolve("relative-fake-tools.log");
        Files.createDirectories(callerDirectory);
        Files.writeString(callerDirectory.resolve(".env.relative"), "TRELLO_API_KEY=key\n", StandardCharsets.UTF_8);
        Files.writeString(callerDirectory.resolve("WORKFLOW.relative.md"), "# Relative\n", StandardCharsets.UTF_8);
        Map<String, String> environment = Map.of(
                "PATH", fakeBin + System.getProperty("path.separator") + System.getenv("PATH"),
                "SYMPHONY_TRELLO_REPO_URL", sourceRepository.toUri().toString(),
                "SYMPHONY_TRELLO_REF", "main",
                "SYMPHONY_HOME", symphonyHome.toString(),
                "SYMPHONY_FAKE_LOG", fakeLog.toString());

        // when
        ProcessResult install = run(
                environment, "bash", installScript.toString(), "--no-onboard", "--bin-dir", binDirectory.toString());
        Files.writeString(
                symphonyHome.resolve("config/connected-boards.json"),
                """
                {"boards":[{"boardId":"board-1","boardName":"Relative","workflowPath":"%s","envPath":"%s"}]}
                """
                        .formatted(
                                callerDirectory.resolve("WORKFLOW.relative.md"),
                                callerDirectory.resolve(".env.relative")),
                StandardCharsets.UTF_8);
        ProcessResult start = run(
                environment,
                callerDirectory,
                binDirectory.resolve("symphony-trello").toString(),
                "start",
                "--workflow",
                callerDirectory.resolve("WORKFLOW.relative.md").toString());
        ProcessResult status = run(
                environment,
                callerDirectory,
                binDirectory.resolve("symphony-trello").toString(),
                "status",
                "--workflow",
                callerDirectory.resolve("WORKFLOW.relative.md").toString());
        ProcessResult logs = run(
                environment,
                callerDirectory,
                "timeout",
                "2",
                binDirectory.resolve("symphony-trello").toString(),
                "logs",
                "--workflow",
                callerDirectory.resolve("WORKFLOW.relative.md").toString(),
                "--follow");
        Path pidFile = singleFile(symphonyHome.resolve("state"), ".pid");
        long managedPid =
                Long.parseLong(Files.readString(pidFile, StandardCharsets.UTF_8).trim());
        ProcessResult stop = run(
                environment,
                callerDirectory,
                binDirectory.resolve("symphony-trello").toString(),
                "stop",
                "--workflow",
                callerDirectory.resolve("WORKFLOW.relative.md").toString());
        ProcessResult uninstall =
                run(environment, "bash", uninstallScript.toString(), "--yes", "--bin-dir", binDirectory.toString());

        // then
        assertThat(install.exitCode()).isZero();
        assertThat(start.exitCode()).isZero();
        assertThat(status.output()).contains("running WORKFLOW.relative.md");
        assertThat(logs.exitCode()).as(logs.output()).isEqualTo(124);
        assertThat(stop.output()).contains("Stopped WORKFLOW.relative.md");
        assertThat(uninstall.exitCode()).isZero();
        assertThat(processStopsWithin(managedPid, 5)).isTrue();
        assertThat(fakeLog)
                .content()
                .contains(
                        "setup-cli cwd=" + callerDirectory,
                        callerDirectory.resolve("WORKFLOW.relative.md").toString(),
                        "dotenv=" + callerDirectory.resolve(".env.relative"));
    }

    private Path createUnmarkedCheckout(String name) throws Exception {
        Path checkout = temporaryDirectory.resolve(name);
        Files.createDirectories(checkout);
        Files.writeString(checkout.resolve("README.md"), "unrelated\n", StandardCharsets.UTF_8);
        run(Map.of(), "git", "-C", checkout.toString(), "init", "-b", "main").assertSuccess();
        run(Map.of(), "git", "-C", checkout.toString(), "config", "user.name", "Test User")
                .assertSuccess();
        run(Map.of(), "git", "-C", checkout.toString(), "config", "user.email", "test@example.invalid")
                .assertSuccess();
        run(Map.of(), "git", "-C", checkout.toString(), "add", ".").assertSuccess();
        run(Map.of(), "git", "-C", checkout.toString(), "commit", "-m", "Initial unrelated checkout")
                .assertSuccess();
        run(
                        Map.of(),
                        "git",
                        "-C",
                        checkout.toString(),
                        "remote",
                        "add",
                        "origin",
                        "https://example.invalid/unrelated.git")
                .assertSuccess();
        return checkout;
    }

    private static ProcessResult runWithoutHome(String... command) throws Exception {
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.environment().remove("HOME");
        return run(processBuilder, "", 60);
    }

    private static ProcessResult runUnchecked(Map<String, String> environment, String... command) {
        try {
            return run(environment, command);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String normalizedWhitespace(String text) {
        return text.replace("|", "").replaceAll("\\s+", " ");
    }

    private static List<ProcessResult> runPosixAppPathCases(
            Map<String, String> environment,
            String script,
            List<String> fixedArgs,
            List<UnsafeInstallerPath> cases,
            Path safeBin) {
        return cases.stream()
                .map(appPath -> {
                    List<String> command = new ArrayList<>(List.of("bash", script));
                    command.addAll(fixedArgs);
                    command.add("--prefix");
                    command.add(appPath.value());
                    command.add("--bin-dir");
                    command.add(safeBin.toString());
                    return runUnchecked(environment, command.toArray(String[]::new));
                })
                .toList();
    }

    private static List<ProcessResult> runPowerShellAppPathCases(
            Map<String, String> environment,
            List<String> pwsh,
            String script,
            List<String> fixedArgs,
            List<UnsafeInstallerPath> cases,
            Path safeBin) {
        return cases.stream()
                .map(appPath -> {
                    List<String> command = new ArrayList<>(command(pwsh, "-NoProfile", "-File", script));
                    command.addAll(fixedArgs);
                    command.add("--prefix");
                    command.add(appPath.value());
                    command.add("--bin-dir");
                    command.add(safeBin.toString());
                    return runUnchecked(environment, command.toArray(String[]::new));
                })
                .toList();
    }

    private static void assertUnsafeAppPathFailures(
            List<UnsafeInstallerPath> cases, List<ProcessResult> results, OutputStyle outputStyle, String... absent) {
        for (int index = 0; index < cases.size(); index++) {
            UnsafeInstallerPath appPath = cases.get(index);
            ProcessResult result = results.get(index);
            assertThat(result.exitCode()).as(appPath.name()).isNotZero();
            assertThat(outputStyle.apply(result.output()))
                    .as(appPath.name())
                    .contains(appPath.expectedMessage())
                    .doesNotContain(absent);
        }
    }

    private List<UnsafeCommandDirectory> posixUnsafeCommandDirectories(
            Path home, Path file, Path symlink, String symlinkName, UnsafeCommandDirectory extraCase) {
        List<UnsafeCommandDirectory> cases = new ArrayList<>(List.of(
                new UnsafeCommandDirectory("root", "/", "--bin-dir must point to a dedicated command directory."),
                new UnsafeCommandDirectory(
                        "home", home.toString(), "--bin-dir must point to a dedicated command directory."),
                new UnsafeCommandDirectory(
                        "checkout",
                        Path.of("").toAbsolutePath().normalize().toString(),
                        "--bin-dir must point to a dedicated command directory."),
                new UnsafeCommandDirectory("blank", "", "--bin-dir must not be blank."),
                new UnsafeCommandDirectory("whitespace", "   ", "--bin-dir must not be blank."),
                new UnsafeCommandDirectory("relative", "relative-bin", "--bin-dir must be an absolute path."),
                new UnsafeCommandDirectory(
                        "control",
                        temporaryDirectory.resolve("bin\nline").toString(),
                        "--bin-dir must not contain control characters."),
                new UnsafeCommandDirectory("file", file.toString(), "--bin-dir must be a directory.")));
        cases.addAll(unsafeCommandDirectorySymlinkCases(symlink, symlinkName));
        cases.add(extraCase);
        return List.copyOf(cases);
    }

    private List<UnsafeCommandDirectory> powershellUnsafeCommandDirectories(
            Path file, Path symlink, String symlinkName, UnsafeCommandDirectory extraCase) {
        List<UnsafeCommandDirectory> cases = new ArrayList<>(List.of(
                new UnsafeCommandDirectory("root", "/", "--bin-dir must point to a dedicated command directory."),
                new UnsafeCommandDirectory("relative", "relative-bin", "--bin-dir must be an absolute path."),
                new UnsafeCommandDirectory("drive-relative", "C:relative-bin", "--bin-dir must be an absolute path."),
                new UnsafeCommandDirectory("root-relative", "\\relative-bin", "--bin-dir must be an absolute path."),
                new UnsafeCommandDirectory(
                        "control",
                        temporaryDirectory.resolve("bin\nline").toString(),
                        "--bin-dir must not contain control characters."),
                new UnsafeCommandDirectory("file", file.toString(), "--bin-dir must be a directory.")));
        cases.addAll(unsafeCommandDirectorySymlinkCases(symlink, symlinkName));
        cases.add(extraCase);
        return List.copyOf(cases);
    }

    private List<UnsafeCommandDirectory> unsafeCommandDirectorySymlinkCases(Path symlink, String symlinkName) {
        return List.of(
                new UnsafeCommandDirectory("symlink", symlink.toString(), "--bin-dir must not be a symlink."),
                new UnsafeCommandDirectory(
                        "symlink-parent", symlink.resolve("bin").toString(), "--bin-dir must not be a symlink."),
                new UnsafeCommandDirectory(
                        "symlink-traversal", symlink.resolve("../bin").toString(), "--bin-dir must not be a symlink."),
                new UnsafeCommandDirectory(
                        "symlink-after-traversal",
                        temporaryDirectory
                                .resolve("missing")
                                .resolve("../" + symlinkName)
                                .resolve("bin")
                                .toString(),
                        "--bin-dir must not be a symlink."));
    }

    private List<UnsafeInstallerPath> posixUnsafeAppPaths(
            Path home, Path file, Path symlink, String symlinkName, UnsafeInstallerPath extraCase) {
        List<UnsafeInstallerPath> cases = new ArrayList<>(posixUnsafeAppPaths(home, file, symlink, symlinkName));
        cases.add(extraCase);
        return List.copyOf(cases);
    }

    private List<UnsafeInstallerPath> posixUnsafeAppPaths(Path home, Path file, Path symlink, String symlinkName) {
        List<UnsafeInstallerPath> cases = new ArrayList<>(List.of(
                new UnsafeInstallerPath("root", "/", "--prefix must point to a dedicated app checkout directory."),
                new UnsafeInstallerPath(
                        "root-home", "/root", "--prefix must point to a dedicated app checkout directory."),
                new UnsafeInstallerPath(
                        "home", home.toString(), "--prefix must point to a dedicated app checkout directory."),
                new UnsafeInstallerPath(
                        "checkout",
                        Path.of("").toAbsolutePath().normalize().toString(),
                        "--prefix must point to a dedicated app checkout directory."),
                new UnsafeInstallerPath("blank", "", "--prefix must not be blank."),
                new UnsafeInstallerPath("whitespace", "   ", "--prefix must not be blank."),
                new UnsafeInstallerPath("relative", "relative-app", "--prefix must be an absolute path."),
                new UnsafeInstallerPath(
                        "control", temporaryDirectory.resolve("app\nline").toString(), "control characters"),
                new UnsafeInstallerPath("file", file.toString(), "--prefix must be a directory.")));
        cases.addAll(unsafeAppSymlinkCases(symlink, symlinkName));
        return List.copyOf(cases);
    }

    private List<UnsafeInstallerPath> unsafeAppSymlinkCases(Path symlink, String symlinkName) {
        return List.of(
                new UnsafeInstallerPath("symlink", symlink.toString(), "--prefix must not be a symlink."),
                new UnsafeInstallerPath(
                        "symlink-parent", symlink.resolve("app").toString(), "--prefix must not be a symlink."),
                new UnsafeInstallerPath(
                        "symlink-traversal", symlink.resolve("../app").toString(), "--prefix must not be a symlink."),
                new UnsafeInstallerPath(
                        "symlink-after-traversal",
                        temporaryDirectory
                                .resolve("missing")
                                .resolve("../" + symlinkName)
                                .toString(),
                        "--prefix must not be a symlink."));
    }

    private List<UnsafeInstallerPath> powershellUnsafeAppPaths(
            Path file, Path symlink, String symlinkName, UnsafeInstallerPath extraCase) {
        List<UnsafeInstallerPath> cases = new ArrayList<>(powershellUnsafeAppPaths(file, symlink, symlinkName));
        cases.add(extraCase);
        return List.copyOf(cases);
    }

    private List<UnsafeInstallerPath> powershellUnsafeAppPaths(Path file, Path symlink, String symlinkName) {
        List<UnsafeInstallerPath> cases = new ArrayList<>(List.of(
                new UnsafeInstallerPath("root", "/", "--prefix must point to a dedicated app checkout directory."),
                new UnsafeInstallerPath(
                        "root-home", "/root", "--prefix must point to a dedicated app checkout directory."),
                new UnsafeInstallerPath(
                        "checkout",
                        Path.of("").toAbsolutePath().normalize().toString(),
                        "--prefix must point to a dedicated app checkout directory."),
                new UnsafeInstallerPath("relative", "relative-app", "--prefix must be an absolute path."),
                new UnsafeInstallerPath("drive-relative", "C:relative-app", "--prefix must be an absolute path."),
                new UnsafeInstallerPath("root-relative", "\\relative-app", "--prefix must be an absolute path."),
                new UnsafeInstallerPath(
                        "control", temporaryDirectory.resolve("app\nline").toString(), "control characters"),
                new UnsafeInstallerPath("file", file.toString(), "--prefix must be a directory.")));
        cases.addAll(unsafeAppSymlinkCases(symlink, symlinkName));
        return List.copyOf(cases);
    }

    private enum OutputStyle {
        RAW {
            @Override
            String apply(String output) {
                return output;
            }
        },
        NORMALIZED {
            @Override
            String apply(String output) {
                return normalizedWhitespace(output);
            }
        };

        abstract String apply(String output);
    }

    private static boolean unsafeUninstallCommandDirectoryCase(UnsafeCommandDirectory commandDirectory) {
        return !commandDirectory.name().startsWith("symlink");
    }

    private static List<String> powershellCommandForDifferentWorkingDirectory() {
        return powershellCommand().stream()
                .map(command ->
                        command.contains(File.separator) && !Path.of(command).isAbsolute()
                                ? Path.of(command).toAbsolutePath().toString()
                                : command)
                .toList();
    }

    private record UnsafeCommandDirectory(String name, String value, String expectedMessage) {}

    private record UnsafeInstallerPath(String name, String value, String expectedMessage) {}
}
