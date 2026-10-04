package ch.fmartin.symphony.trello.setup;

import static ch.fmartin.symphony.trello.setup.InstallerScriptFixture.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ch.fmartin.symphony.trello.setup.InstallerScriptFixture.ProcessResult;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// Transcript-order contract for installer progress output: one plan, numbered phases, one result line per milestone,
/// and a named failed phase. ADR 0122 records the contract; SPEC.md Section 19.4 states it.
final class InstallerProgressOutputTest {
    private static final String RELEASE_VERSION = "9.9.9";
    private static final String HANDOFF = "You're good to go - your Trello board is now a queue for Codex work.";
    private static final String RUN_LINE = "  RUN  ";
    private static final String ESCAPE = "\u001B";
    private static final int SHA3_256_HEX_LENGTH = 64;
    private static final String[] DRY_RUN_FORBIDDEN_OUTPUT = {
        RUN_LINE,
        ESCAPE,
        "OK  Command installed",
        "OK  Setup complete",
        "Symphony for Trello installed.",
        "Installer stopped during",
        HANDOFF
    };

    @TempDir
    Path temporaryDirectory;

    @Test
    void posixGuidedSourceInstallAndUpdateFollowThePlanAndPhaseOrder() throws Exception {
        // given
        assumeFalse(isWindows());
        assumeTrue(commandExists("bash"));
        assumeTrue(commandExists("git"));
        assumeTrue(commandExists("script"));
        Path installScript = Path.of("install.sh").toAbsolutePath();
        Path sourceRepository = createSourceRepository(temporaryDirectory);
        Path fakeBin = createFakeToolchain(temporaryDirectory);
        Path home = temporaryDirectory.resolve("guided-home");
        Path symphonyHome = temporaryDirectory.resolve("guided-symphony-home");
        Path binDirectory = temporaryDirectory.resolve("guided-bin");
        Path installedCommand = binDirectory.resolve("symphony-trello");
        Path workflow = symphonyHome.resolve("config/WORKFLOW.transcript-board.md");
        Files.createDirectories(home);
        Files.createFile(temporaryDirectory.resolve("codex-authenticated"));
        Map<String, String> environment = Map.of(
                "PATH", binDirectory + File.pathSeparator + fakeBin + File.pathSeparator + System.getenv("PATH"),
                "HOME", home.toString(),
                "USER", "symphony-test",
                "SYMPHONY_TRELLO_REPO_URL", sourceRepository.toUri().toString(),
                "SYMPHONY_TRELLO_REF", "main",
                "SYMPHONY_HOME", symphonyHome.toString(),
                "SYMPHONY_FAKE_LOG", temporaryDirectory.resolve("guided.log").toString());
        String installerCommand =
                "bash " + shellQuote(installScript.toString()) + " --bin-dir " + shellQuote(binDirectory.toString());
        String setupAnswers = "api-key\napi-token\nTranscript Board\n";

        try {

            // when
            ProcessResult install = runWithPseudoTerminal(environment, setupAnswers, installerCommand);
            addSourceRepositoryCommit(sourceRepository, "UPDATED", "updated\n");
            ProcessResult update = runWithPseudoTerminal(environment, setupAnswers, installerCommand);

            // then
            install.assertSuccess();
            update.assertSuccess();
            assertThat(install.output())
                    .containsSubsequence(
                            "Symphony for Trello installer",
                            "Detected ",
                            "Install plan",
                            "Source: Git checkout",
                            "Ref: main",
                            "Install: " + symphonyHome.resolve("app"),
                            "Command: " + installedCommand,
                            "[1/4] Checking prerequisites",
                            "OK  Git",
                            "OK  Java 25+ JDK",
                            "OK  Codex CLI",
                            "[2/4] Installing Symphony",
                            "OK  Source checked out",
                            "OK  App built with Maven wrapper",
                            "OK  Command installed",
                            "[3/4] Running setup",
                            RUN_LINE + installedCommand + " setup-local",
                            "OK  Setup complete",
                            "[4/4] Starting managed workers",
                            "OK  User systemd service enabled: symphony-trello.service",
                            HANDOFF)
                    .containsOnlyOnce("Detected ")
                    .containsOnlyOnce("Install plan")
                    .containsOnlyOnce(symphonyHome.resolve("app").toString())
                    .containsOnlyOnce("Command: ")
                    .containsOnlyOnce(RUN_LINE)
                    .containsOnlyOnce(HANDOFF)
                    .doesNotContain("Update plan", "Installer stopped during");
            assertThat(update.output())
                    .containsSubsequence(
                            "Update plan",
                            "[1/4] Checking prerequisites",
                            "[2/4] Updating Symphony",
                            "Stopped WORKFLOW.transcript-board.md",
                            "OK  Managed workers stopped for the update",
                            "OK  Command installed",
                            "[3/4] Running setup",
                            "OK  Setup complete",
                            "[4/4] Restarting managed workers",
                            HANDOFF)
                    .containsOnlyOnce("Update plan")
                    .containsOnlyOnce(RUN_LINE)
                    .containsOnlyOnce(HANDOFF)
                    .doesNotContain("Install plan");
            assertThat(update.output().stripTrailing()).endsWith("symphony-trello logs --workflow '" + workflow + "'");
        } finally {
            if (Files.isExecutable(installedCommand)) {
                run(environment, installedCommand.toString(), "stop").assertSuccess();
            }
        }
    }

    @Test
    void posixReleaseInstallAndUpdateWithoutOnboardingEndWithOneResultAndNextStep() throws Exception {
        // given
        assumeFalse(isWindows());
        assumeTrue(commandExists("bash"));
        assumeTrue(commandExists("tar"));
        assumeTrue(commandExists("curl") || commandExists("wget"));
        Path releaseAssets = createReleaseAssets(true);
        Map<String, String> environment = releaseEnvironment(releaseAssets);
        Path symphonyHome = Path.of(environment.get("SYMPHONY_HOME"));
        Path binDirectory = temporaryDirectory.resolve("release-bin");

        // when
        ProcessResult install = runReleaseInstaller(environment, binDirectory);
        Files.writeString(
                symphonyHome.resolve("config").resolve(ConnectedBoardManifest.FILE_NAME), "{\"boards\":[]}\n");
        ProcessResult update = runReleaseInstaller(environment, binDirectory);

        // then
        install.assertSuccess();
        update.assertSuccess();
        assertThat(install.output())
                .containsSubsequence(
                        "Install plan",
                        "Source: release archive",
                        "Version: " + RELEASE_VERSION,
                        "Release assets: " + releaseAssets.toUri(),
                        "[1/2] Checking prerequisites",
                        "OK  Java 25+ JDK",
                        "[2/2] Installing Symphony",
                        "OK  Release " + RELEASE_VERSION + " verified and unpacked",
                        "OK  Command installed",
                        "Symphony for Trello installed.",
                        "Next step: connect a Trello board with: ",
                        "symphony-trello' setup-local")
                .containsOnlyOnce(symphonyHome.resolve("app").toString())
                .doesNotContain(RUN_LINE, ESCAPE, "\r", "[3/", "Running setup", "start --all", HANDOFF);
        assertThat(update.output())
                .containsSubsequence(
                        "Update plan",
                        "[1/2] Checking prerequisites",
                        "[2/2] Updating Symphony",
                        "OK  Release " + RELEASE_VERSION + " verified and unpacked",
                        "Symphony for Trello updated.",
                        "Next step: start the connected boards with: ",
                        "symphony-trello' start --all")
                .doesNotContain(RUN_LINE, ESCAPE, "\r", "Symphony for Trello installed.", "setup-local");
        assertThat(symphonyHome.resolve("app/target/quarkus-app/quarkus-run.jar"))
                .isRegularFile();
    }

    @Test
    void posixGuidedDryRunNamesEveryPhaseWithoutClaimingSuccess() throws Exception {
        // given
        assumeFalse(isWindows());
        assumeTrue(commandExists("bash"));
        Path binDirectory = temporaryDirectory.resolve("guided-dry-run-bin");
        Map<String, String> environment = dryRunEnvironment();

        // when
        ProcessResult result =
                run(environment, "bash", "install.sh", "--dry-run", "--bin-dir", binDirectory.toString());

        // then
        result.assertSuccess();
        assertThat(result.output())
                .containsSubsequence(
                        "Install plan",
                        "Dry run: no files changed.",
                        "[1/4] Checking prerequisites",
                        "[2/4] Installing Symphony",
                        "WOULD download release archive:",
                        "WOULD verify SHA3-256 checksum from:",
                        "WOULD unpack release archive into:",
                        "WOULD install command:",
                        "WOULD add " + binDirectory + " to PATH in",
                        "[3/4] Running setup",
                        "WOULD run guided setup: " + binDirectory.resolve("symphony-trello") + " setup-local",
                        "[4/4] Starting managed workers",
                        "WOULD skip autostart and start managed workers with: ",
                        "start --all")
                .containsOnlyOnce("Dry run: no files changed.")
                .doesNotContain(DRY_RUN_FORBIDDEN_OUTPUT);
        assertThat(Path.of(environment.get("SYMPHONY_HOME"))).doesNotExist();
        assertThat(binDirectory).doesNotExist();
    }

    @Test
    void posixNoOnboardDryRunStopsAfterTheInstallPhaseWithoutClaimingSuccess() throws Exception {
        // given
        assumeFalse(isWindows());
        assumeTrue(commandExists("bash"));
        Path binDirectory = temporaryDirectory.resolve("no-onboard-dry-run-bin");
        Map<String, String> environment = dryRunEnvironment();

        // when
        ProcessResult result = run(
                environment, "bash", "install.sh", "--dry-run", "--no-onboard", "--bin-dir", binDirectory.toString());

        // then
        result.assertSuccess();
        assertThat(result.output())
                .containsSubsequence(
                        "Install plan",
                        "Dry run: no files changed.",
                        "[1/2] Checking prerequisites",
                        "[2/2] Installing Symphony",
                        "WOULD download release archive:",
                        "WOULD install command:",
                        "WOULD add " + binDirectory + " to PATH in")
                .containsOnlyOnce("Dry run: no files changed.")
                .doesNotContain(DRY_RUN_FORBIDDEN_OUTPUT)
                .doesNotContain("[3/", "Running setup", "managed workers");
        assertThat(Path.of(environment.get("SYMPHONY_HOME"))).doesNotExist();
        assertThat(binDirectory).doesNotExist();
    }

    @Test
    void posixFailedPhaseIsNamedWithARecoveryStepAndNoCompletionResult() throws Exception {
        // given
        assumeFalse(isWindows());
        assumeTrue(commandExists("bash"));
        assumeTrue(commandExists("tar"));
        assumeTrue(commandExists("curl") || commandExists("wget"));
        Path releaseAssets = createReleaseAssets(false);
        Map<String, String> environment = releaseEnvironment(releaseAssets);

        // when
        ProcessResult result = runReleaseInstaller(environment, temporaryDirectory.resolve("failed-bin"));

        // then
        assertThat(result.exitCode()).as(result.output()).isEqualTo(2);
        assertThat(result.output())
                .containsSubsequence(
                        "[2/2] Installing Symphony",
                        "Release archive checksum verification failed for symphony-trello-" + RELEASE_VERSION
                                + ".tar.gz.",
                        "Installer stopped during [2/2] Installing Symphony.",
                        "Fix the problem above, then rerun the installer.")
                .containsOnlyOnce("Installer stopped during")
                .doesNotContain("verified and unpacked", "OK  Command installed", "Symphony for Trello installed.");
    }

    @Test
    void powershellDryRunNamesTheSamePhasesAsPosixWhenAvailable() throws Exception {
        // given
        List<String> pwsh = powershellCommand();
        assumeFalse(pwsh.isEmpty());

        // when
        ProcessResult guided = run(
                nonWindowsPowerShellEnvironment(),
                command(pwsh, "-NoProfile", "-File", "./install.ps1", "--dry-run")
                        .toArray(String[]::new));
        ProcessResult noOnboard = run(
                nonWindowsPowerShellEnvironment(),
                command(pwsh, "-NoProfile", "-File", "./install.ps1", "--dry-run", "--no-onboard")
                        .toArray(String[]::new));

        // then
        guided.assertSuccess();
        noOnboard.assertSuccess();
        assertThat(guided.output())
                .containsSubsequence(
                        "Symphony for Trello installer",
                        "Detected ",
                        "Install plan",
                        "Source: release archive",
                        "Command: ",
                        "Dry run: no files changed.",
                        "[1/4] Checking prerequisites",
                        "[2/4] Installing Symphony",
                        "WOULD download release archive:",
                        "WOULD install command:",
                        "[3/4] Running setup",
                        "WOULD run guided setup:",
                        "[4/4] Starting managed workers",
                        "WOULD create Windows Scheduled Task:")
                .containsOnlyOnce("Install plan")
                .doesNotContain(RUN_LINE, ESCAPE, "Installer stopped during", HANDOFF);
        assertThat(noOnboard.output())
                .containsSubsequence("[1/2] Checking prerequisites", "[2/2] Installing Symphony")
                .doesNotContain(RUN_LINE, "[3/", "Running setup", "managed workers", "Symphony for Trello installed.");
    }

    @Test
    void powershellSourceInstallAndUpdateWithoutOnboardingEndWithOneResultWhenAvailable() throws Exception {
        // given
        List<String> pwsh = powershellCommand();
        assumeFalse(pwsh.isEmpty());
        assumeTrue(commandExists("git"));
        Path sourceRepository = createPowerShellSourceRepository(temporaryDirectory);
        Path fakeBin = createPowerShellFakeToolchain(temporaryDirectory);
        Path symphonyHome = temporaryDirectory.resolve("ps-source-home");
        Path binDirectory = temporaryDirectory.resolve("ps-source-bin");
        Map<String, String> environment = new LinkedHashMap<>(nonWindowsPowerShellEnvironment());
        environment.put("PATH", fakeBin + File.pathSeparator + System.getenv("PATH"));
        environment.put("SYMPHONY_HOME", symphonyHome.toString());
        environment.put("SYMPHONY_FAKE_JAVA", fakeBin.resolve("fake-java.ps1").toString());
        environment.put(
                "SYMPHONY_FAKE_LOG", temporaryDirectory.resolve("ps-source.log").toString());
        String[] installerCommand = command(
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
                .toArray(String[]::new);

        // when
        ProcessResult install = run(environment, installerCommand);
        ProcessResult update = run(environment, installerCommand);

        // then
        install.assertSuccess();
        update.assertSuccess();
        assertThat(install.output())
                .containsSubsequence(
                        "Install plan",
                        "Source: Git checkout",
                        "Ref: main",
                        "[1/2] Checking prerequisites",
                        "OK  Git",
                        "OK  Java 25+ JDK",
                        "[2/2] Installing Symphony",
                        "OK  Source checked out",
                        "OK  App built with Maven wrapper",
                        "OK  Command installed",
                        "Symphony for Trello installed.",
                        "Next step: connect a Trello board with: ",
                        "setup-local")
                .containsOnlyOnce("Install plan")
                .doesNotContain(RUN_LINE, ESCAPE, "[3/", "Running setup", HANDOFF);
        assertThat(update.output())
                .containsSubsequence(
                        "Update plan",
                        "[2/2] Updating Symphony",
                        "OK  Source checked out",
                        "Symphony for Trello updated.")
                .doesNotContain(RUN_LINE, "Symphony for Trello installed.");
    }

    @Test
    void powershellFailedPhaseIsNamedWithARecoveryStepWhenAvailable() throws Exception {
        // given
        List<String> pwsh = powershellCommand();
        assumeFalse(pwsh.isEmpty());
        Map<String, String> environment = new LinkedHashMap<>(nonWindowsPowerShellEnvironment());
        environment.put("PATH", String.join(File.pathSeparator, "/usr/bin", "/bin"));

        // when
        ProcessResult result = run(
                environment,
                command(pwsh, "-NoProfile", "-File", "./install.ps1", "--no-onboard")
                        .toArray(String[]::new));

        // then
        assertThat(result.exitCode()).as(result.output()).isOne();
        assertThat(result.output())
                .containsSubsequence(
                        "[1/2] Checking prerequisites",
                        "NEEDED  Java 25+ JDK",
                        "Java 25+ JDK is required.",
                        "Installer stopped during [1/2] Checking prerequisites.",
                        "Follow the prerequisite steps above, then rerun the installer.")
                .containsOnlyOnce("Installer stopped during")
                .doesNotContain("[2/2]", "Symphony for Trello installed.");
    }

    private Path createReleaseAssets(boolean validChecksum) throws Exception {
        Path assets = temporaryDirectory.resolve(validChecksum ? "release-assets" : "release-assets-bad-checksum");
        String rootName = "symphony-trello-" + RELEASE_VERSION;
        Path quarkusApp = assets.resolve("staging").resolve(rootName).resolve("target/quarkus-app");
        Files.createDirectories(quarkusApp);
        Files.writeString(quarkusApp.resolve("quarkus-run.jar"), "fake release jar\n");
        Path archive = assets.resolve(rootName + ".tar.gz");
        run(
                        Map.of(),
                        "tar",
                        "-czf",
                        archive.toString(),
                        "-C",
                        assets.resolve("staging").toString(),
                        rootName)
                .assertSuccess();
        String checksum = validChecksum
                ? HexFormat.of().formatHex(MessageDigest.getInstance("SHA3-256").digest(Files.readAllBytes(archive)))
                : "0".repeat(SHA3_256_HEX_LENGTH);
        Files.writeString(assets.resolve("checksums.txt"), checksum + "  " + archive.getFileName() + "\n");
        return assets;
    }

    private Map<String, String> dryRunEnvironment() throws Exception {
        Path fakeBin = createFakeToolchain(temporaryDirectory);
        return Map.of(
                "PATH",
                fakeBin + File.pathSeparator + System.getenv("PATH"),
                "SYMPHONY_HOME",
                temporaryDirectory.resolve("dry-run-home").toString(),
                "SYMPHONY_FAKE_SYSTEMD_UNAVAILABLE",
                "1",
                "SYMPHONY_FAKE_LOG",
                temporaryDirectory.resolve("dry-run.log").toString());
    }

    private Map<String, String> releaseEnvironment(Path releaseAssets) throws Exception {
        Path fakeBin = createFakeToolchain(temporaryDirectory);
        Path checksumBin = temporaryDirectory.resolve("checksum-java-bin");
        Files.createDirectories(checksumBin);
        // The shared fake java only records calls; the installer's SHA3 helper needs a real source launch.
        writeExecutable(
                checksumBin.resolve("java"),
                """
                #!/usr/bin/env bash
                if [[ "${1:-}" == *.java ]]; then
                  exec %s "$@"
                fi
                exec %s "$@"
                """
                        .formatted(
                                shellQuote(Path.of(System.getProperty("java.home"), "bin", "java")
                                        .toString()),
                                shellQuote(fakeBin.resolve("java").toString())));
        Path home = temporaryDirectory.resolve(releaseAssets.getFileName() + "-user-home");
        Files.createDirectories(home);
        return Map.of(
                "PATH",
                checksumBin + File.pathSeparator + fakeBin + File.pathSeparator + System.getenv("PATH"),
                "HOME",
                home.toString(),
                "SYMPHONY_HOME",
                temporaryDirectory
                        .resolve(releaseAssets.getFileName() + "-symphony-home")
                        .toString(),
                "SYMPHONY_TRELLO_RELEASE_BASE_URL",
                releaseAssets.toUri().toString(),
                "SYMPHONY_FAKE_LOG",
                temporaryDirectory.resolve("release.log").toString());
    }

    private static ProcessResult runReleaseInstaller(Map<String, String> environment, Path binDirectory)
            throws Exception {
        return run(
                environment,
                "bash",
                "install.sh",
                "--no-onboard",
                "--version",
                RELEASE_VERSION,
                "--bin-dir",
                binDirectory.toString());
    }
}
