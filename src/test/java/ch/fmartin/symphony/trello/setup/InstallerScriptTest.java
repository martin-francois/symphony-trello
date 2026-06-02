package ch.fmartin.symphony.trello.setup;

import static ch.fmartin.symphony.trello.setup.InstallerScriptFixture.*;
import static org.assertj.core.api.Assertions.assertThat;

import ch.fmartin.symphony.trello.setup.InstallerScriptFixture.ProcessResult;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
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
        Assumptions.assumeTrue(commandExists("bash"));

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
        Assumptions.assumeTrue(commandExists("bash"));

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
    void posixInstallerDryRunReportsConcreteMissingPrerequisiteActions() throws Exception {
        // given
        Assumptions.assumeTrue(Files.exists(Path.of("/bin/bash")));
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
                        "WOULD offer to install Git",
                        "WOULD offer to install Java 25+ JDK",
                        "WOULD offer to install Codex CLI with Symphony-managed npm:",
                        "Node.js/npm installed: no");
    }

    @Test
    void posixInstallerRejectsRemovedUpdatePathOption() throws Exception {
        // given
        Assumptions.assumeTrue(Files.exists(Path.of("/bin/bash")));

        // when
        ProcessResult result = run(Map.of(), "/bin/bash", "install.sh", "--dry-run", "--update-path", "--no-onboard");

        // then
        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.output()).contains("Unknown option: --update-path");
    }

    @Test
    void posixInstallerDoesNotOfferPathSetupWhenBinDirIsAlreadyOnPath() throws Exception {
        // given
        Assumptions.assumeTrue(commandExists("bash"));
        Assumptions.assumeTrue(commandExists("git"));
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
        Assumptions.assumeTrue(commandExists("bash"));
        Assumptions.assumeTrue(commandExists("git"));
        Assumptions.assumeTrue(commandExists("script"));
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
                .contains("# Symphony for Trello", expectedLine)
                .containsOnlyOnce(expectedLine);
        assertThat(Files.readString(loginProfile, StandardCharsets.UTF_8))
                .contains("# Symphony for Trello", expectedLine)
                .containsOnlyOnce(expectedLine);
    }

    @Test
    void posixPipedInstallerAddsPathSetupByDefault() throws Exception {
        // given
        Assumptions.assumeTrue(commandExists("bash"));
        Assumptions.assumeTrue(commandExists("git"));
        Assumptions.assumeTrue(commandExists("script"));
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
        Assumptions.assumeTrue(commandExists("bash"));
        Assumptions.assumeTrue(commandExists("git"));
        Assumptions.assumeTrue(commandExists("script"));
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
    void posixInstallerLeavesProfileUnchangedWhenPathSetupIsDisabled() throws Exception {
        // given
        Assumptions.assumeTrue(commandExists("bash"));
        Assumptions.assumeTrue(commandExists("git"));
        Assumptions.assumeTrue(commandExists("script"));
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
    void posixNonInteractiveInstallerUpdatesProfileByDefault() throws Exception {
        // given
        Assumptions.assumeTrue(commandExists("bash"));
        Assumptions.assumeTrue(commandExists("git"));
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
    void posixInstallerContinuesWhenAutomaticPathSetupCannotWriteProfile() throws Exception {
        // given
        Assumptions.assumeTrue(commandExists("bash"));
        Assumptions.assumeTrue(commandExists("git"));
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
        Assumptions.assumeTrue(Files.exists(Path.of("/bin/bash")));
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
                        "OK      Git available",
                        "NEEDED  Java 25+ JDK",
                        "OK      Codex CLI available",
                        "WOULD offer to install Java 25+ JDK with: apt-get update && apt-get install -y openjdk-25-jdk")
                .doesNotContain("WOULD offer to install Git");
    }

    @Test
    void posixInstallerProposesPackageManagerCommandWithoutSudoWhenRoot() throws Exception {
        // given
        Assumptions.assumeTrue(Files.exists(Path.of("/bin/bash")));
        Assumptions.assumeTrue(commandExists("script"));
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
                environment, "n\n", "/bin/bash " + shellQuote(installScript.toString()) + " --no-onboard");

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
        Assumptions.assumeTrue(Files.exists(Path.of("/bin/bash")));
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
        ProcessResult result = run(environment, "/bin/bash", "install.sh", "--no-onboard");

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
        Assumptions.assumeTrue(Files.exists(Path.of("/bin/bash")));
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
        ProcessResult result = run(environment, "/bin/bash", "install.sh", "--dry-run", "--no-onboard");

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
        Assumptions.assumeTrue(Files.exists(Path.of("/bin/bash")));
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
        Assumptions.assumeTrue(Files.exists(Path.of("/bin/bash")));
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
        Assumptions.assumeTrue(Files.exists(Path.of("/bin/bash")));
        Assumptions.assumeTrue(commandExists("script"));
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
                environment, "y\nn\n", "/bin/bash " + shellQuote(installScript.toString()) + " --no-onboard");

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
        Assumptions.assumeTrue(Files.exists(Path.of("/bin/bash")));
        Assumptions.assumeTrue(commandExists("script"));
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
        Assumptions.assumeTrue(Files.exists(Path.of("/bin/bash")));
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
        Assumptions.assumeTrue(Files.exists(Path.of("/bin/bash")));
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
        Assumptions.assumeFalse(pwsh.isEmpty());

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
    void powershellInstallerRejectsRemovedUpdatePathOptionWhenAvailable() throws Exception {
        // given
        List<String> pwsh = powershellCommand();
        Assumptions.assumeFalse(pwsh.isEmpty());

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
        Assumptions.assumeFalse(pwsh.isEmpty());

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
    void powershellInstallerAcceptsPublicValueFlagsWhenAvailable() throws Exception {
        // given
        List<String> pwsh = powershellCommand();
        Assumptions.assumeFalse(pwsh.isEmpty());
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
        Assumptions.assumeFalse(pwsh.isEmpty());
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
        Assumptions.assumeFalse(pwsh.isEmpty());
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
        Assumptions.assumeTrue(dockerDaemonIsUsable());
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
        Assumptions.assumeFalse(pwsh.isEmpty());
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
        Assumptions.assumeFalse(pwsh.isEmpty());
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
        Assumptions.assumeFalse(pwsh.isEmpty());

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
    void powershellUninstallerSkipsInvalidStalePidFilesWhenAvailable() throws Exception {
        // given
        List<String> pwsh = powershellCommand();
        Assumptions.assumeFalse(pwsh.isEmpty());
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
        Assumptions.assumeTrue(commandExists("bash"));
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
        Assumptions.assumeTrue(commandExists("bash"));
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
    void posixUninstallerRemovesAppDirectoryWhenEmbeddedCurrentDataPathsDoNotExist() throws Exception {
        // given
        Assumptions.assumeTrue(commandExists("bash"));
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
        assertThat(result.exitCode()).as(result.output()).isZero();
        assertThat(result.output()).contains("REMOVE  " + symphonyHome).doesNotContain("contains current local data");
        assertThat(symphonyHome).doesNotExist();
    }

    @Test
    void posixUninstallerCleansSelectedLocalDataWhenUnmarkedAppRemovalIsDeclined() throws Exception {
        // given
        Assumptions.assumeTrue(commandExists("bash"));
        Assumptions.assumeTrue(commandExists("script"));
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
        Assumptions.assumeTrue(commandExists("bash"));
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
        Assumptions.assumeTrue(commandExists("bash"));
        Assumptions.assumeTrue(commandExists("script"));
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
        Assumptions.assumeTrue(commandExists("bash"));
        Path symphonyHome = temporaryDirectory.resolve("home-equivalent");
        Files.createDirectories(symphonyHome);
        Map<String, String> environment = Map.of(
                "HOME", symphonyHome.toString(),
                "SYMPHONY_HOME", temporaryDirectory.resolve("safe-home").toString(),
                "SYMPHONY_TRELLO_CONFIG_DIR", symphonyHome + "/");

        // when
        ProcessResult result =
                run(environment, "bash", "uninstall.sh", "--dry-run", "--yes", "--yes-local-data", "--remove-config");

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
                        "--config-dir",
                        "--state-home",
                        "--app-home",
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
                        "--state-home",
                        "--app-home",
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
                        "STOP",
                        "kill",
                        "wait_for_exit",
                        "absolutize_path",
                        "SKIP  stale pid",
                        "--remove-config",
                        "--remove-workspaces",
                        "--remove-state",
                        "--yes-local-data",
                        ".symphony-trello-install",
                        "Trello boards were not deleted or archived.");
        assertThat(posixUninstaller).doesNotContain(",,}");
        assertThat(powershellUninstaller)
                .contains(
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
                        "SKIP  stale pid",
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
        Assumptions.assumeTrue(commandExists("bash"));
        Assumptions.assumeTrue(commandExists("git"));
        Assumptions.assumeTrue(commandExists("script"));
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
        Assumptions.assumeTrue(commandExists("bash"));
        Assumptions.assumeTrue(commandExists("git"));
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
        Assumptions.assumeTrue(commandExists("bash"));
        Assumptions.assumeTrue(commandExists("git"));
        Assumptions.assumeTrue(commandExists("script"));
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
                            "Stopped WORKFLOW.docs-queue.md.fake",
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
        Assumptions.assumeTrue(commandExists("bash"));
        Assumptions.assumeTrue(commandExists("git"));
        Assumptions.assumeTrue(commandExists("script"));
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
        Assumptions.assumeTrue(commandExists("bash"));
        Assumptions.assumeTrue(commandExists("git"));
        Assumptions.assumeTrue(commandExists("sleep"));
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
        Assumptions.assumeTrue(commandExists("bash"));
        Assumptions.assumeTrue(commandExists("git"));
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
        Assumptions.assumeTrue(commandExists("bash"));
        Assumptions.assumeTrue(commandExists("git"));
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
        Assumptions.assumeTrue(commandExists("bash"));
        Assumptions.assumeTrue(commandExists("git"));
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
        Assumptions.assumeTrue(commandExists("bash"));
        Assumptions.assumeTrue(commandExists("git"));
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
    void powershellInstallerRefusesUnmarkedUnrelatedExistingCheckoutWhenAvailable() throws Exception {
        // given
        List<String> pwsh = powershellCommand();
        Assumptions.assumeFalse(pwsh.isEmpty());
        Assumptions.assumeTrue(commandExists("git"));
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
                        "Use an empty --prefix path");
        assertThat(existingCheckout.resolve(".symphony-trello-install")).doesNotExist();
    }

    @Test
    void posixStartResolvesRelativeEnvAndWorkflowFromCallerDirectory() throws Exception {
        // given
        Assumptions.assumeTrue(commandExists("bash"));
        Assumptions.assumeTrue(commandExists("git"));
        Assumptions.assumeTrue(commandExists("timeout"));
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
}
