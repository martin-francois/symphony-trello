package ch.fmartin.symphony.trello.setup;

import static ch.fmartin.symphony.trello.setup.InstallerScriptFixture.HostEnvironment.CLEARED;
import static ch.fmartin.symphony.trello.setup.InstallerScriptFixture.createFakeToolchain;
import static ch.fmartin.symphony.trello.setup.InstallerScriptFixture.createSourceRepository;
import static ch.fmartin.symphony.trello.setup.InstallerScriptFixture.run;
import static ch.fmartin.symphony.trello.setup.InstallerScriptFixture.runPseudoTerminalDialog;
import static ch.fmartin.symphony.trello.setup.InstallerScriptFixture.shellQuote;
import static ch.fmartin.symphony.trello.setup.InstallerScriptFixture.writeExecutable;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ch.fmartin.symphony.trello.setup.InstallerScriptFixture.TerminalAnswer;
import ch.fmartin.symphony.trello.testsupport.StatefulFakeTrello;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

final class InstallerTutorialOfferTest {
    private static final String BOARD_NAME = "Installed queue";
    private static final String HANDOFF = "You're good to go - your Trello board is now a queue for Codex work.";

    @TempDir
    Path temporaryDirectory;

    @ParameterizedTest
    @ValueSource(strings = {"n", "", "\u0004"})
    void posixInstallerOffersOnlyAfterAutostartAndCompletedHandoff(String answer) throws Exception {
        // given
        assumeTrue(System.getProperty("os.name").equalsIgnoreCase("Linux"), "the fixture uses a POSIX terminal");
        try (var trello = new StatefulFakeTrello().start();
                var fixture = fixture(trello)) {

            // when
            var result = runPseudoTerminalDialog(
                    CLEARED,
                    fixture.environment(),
                    temporaryDirectory,
                    fixture.installerCommand(),
                    List.of(
                            new TerminalAnswer(InstallerScriptFixture.TRELLO_KEY_PROMPT, "synthetic-key"),
                            new TerminalAnswer(InstallerScriptFixture.TRELLO_TOKEN_PROMPT, "synthetic-token"),
                            new TerminalAnswer(InstallerScriptFixture.TRELLO_BOARD_PROMPT, BOARD_NAME),
                            new TerminalAnswer(GuidedTutorial.OFFER_PROMPT, answer, !answer.equals("\u0004"))));

            // then
            result.assertSuccess();
            assertThat(result.transcript())
                    .containsSubsequence("User systemd service enabled:", HANDOFF, GuidedTutorial.OFFER_PROMPT)
                    .doesNotContain("Installer stopped during");
            assertThat(fixture.log())
                    .content(StandardCharsets.UTF_8)
                    .containsSubsequence("systemctl --user enable", "completion_mode=print", "tutorial-offer");
            assertInstallationAndWorkerPreserved(fixture);
            assertThat(trello.boardNames()).isEmpty();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void posixAcceptedTutorialPreservesInstalledWorkerWhenFinishedOrInterrupted(boolean interrupt) throws Exception {
        // given
        assumeTrue(System.getProperty("os.name").equalsIgnoreCase("Linux"), "the fixture uses a POSIX terminal");
        try (var trello = new StatefulFakeTrello().start();
                var fixture = fixture(trello)) {
            String existingBoard = trello.givenBoard("Existing queue");
            List<TerminalAnswer> answers = new ArrayList<>(List.of(
                    new TerminalAnswer(InstallerScriptFixture.TRELLO_KEY_PROMPT, "synthetic-key"),
                    new TerminalAnswer(InstallerScriptFixture.TRELLO_TOKEN_PROMPT, "synthetic-token"),
                    new TerminalAnswer(InstallerScriptFixture.TRELLO_BOARD_PROMPT, BOARD_NAME),
                    new TerminalAnswer(GuidedTutorial.OFFER_PROMPT, "y"),
                    new TerminalAnswer(GuidedTutorial.CHECK_PROMPT, interrupt ? "\u0003" : "q", !interrupt)));
            if (!interrupt) {
                answers.add(new TerminalAnswer(GuidedTutorial.CLEANUP_PROMPT, ""));
            }

            // when
            var result = runPseudoTerminalDialog(
                    CLEARED, fixture.environment(), temporaryDirectory, fixture.installerCommand(), answers);

            // then
            if (interrupt) {
                assertThat(result.exitCode()).as(result.transcript()).isIn(0, 130);
            } else {
                result.assertSuccess();
            }
            assertThat(result.transcript())
                    .containsSubsequence(HANDOFF, GuidedTutorial.OFFER_PROMPT, "Board created:")
                    .doesNotContain("Installer stopped during");
            assertInstallationAndWorkerPreserved(fixture);
            assertThat(trello.archived(trello.boardIdNamed(GuidedTutorial.BOARD_NAME)))
                    .as("tutorial completion or console interruption archives the temporary board")
                    .isTrue();
            assertThat(trello.archived(existingBoard))
                    .as("the existing queue remains active")
                    .isFalse();
        }
    }

    @Test
    void posixTutorialFailurePreservesTheCompletedInstallation() throws Exception {
        // given
        assumeTrue(System.getProperty("os.name").equalsIgnoreCase("Linux"), "the fixture uses a POSIX terminal");
        try (var trello = new StatefulFakeTrello().start();
                var fixture = fixture(trello)) {
            trello.failNext("POST", "lists", 500);

            // when
            var result = runPseudoTerminalDialog(
                    CLEARED,
                    fixture.environment(),
                    temporaryDirectory,
                    fixture.installerCommand(),
                    List.of(
                            new TerminalAnswer(InstallerScriptFixture.TRELLO_KEY_PROMPT, "synthetic-key"),
                            new TerminalAnswer(InstallerScriptFixture.TRELLO_TOKEN_PROMPT, "synthetic-token"),
                            new TerminalAnswer(InstallerScriptFixture.TRELLO_BOARD_PROMPT, BOARD_NAME),
                            new TerminalAnswer(GuidedTutorial.OFFER_PROMPT, "y")));

            // then
            result.assertSuccess();
            assertThat(result.transcript())
                    .containsSubsequence(HANDOFF, "The guided tutorial stopped: Trello write outcome is unknown.")
                    .doesNotContain("Installer stopped during");
            assertInstallationAndWorkerPreserved(fixture);
            assertThat(trello.archived(trello.boardIdNamed(GuidedTutorial.BOARD_NAME)))
                    .as("a failed tutorial archives its own temporary board")
                    .isTrue();
        }
    }

    @EnumSource(PowerShellOffer.class)
    @ParameterizedTest
    void powershellInstallerPreservesCompletedInstallationAfterTheOffer(PowerShellOffer scenario) throws Exception {
        // given
        assumeTrue(System.getProperty("os.name").equalsIgnoreCase("Linux"), "the fixture uses a POSIX terminal");
        List<String> powershell = InstallerScriptFixture.powershellCommand();
        assumeTrue(!powershell.isEmpty(), "the pinned PowerShell runtime is required");
        Path source = InstallerScriptFixture.createPowerShellSourceRepository(temporaryDirectory);
        Path tools = createFakeToolchain(temporaryDirectory);
        Path fakeJava = tools.resolve("java-fixture");
        Files.move(tools.resolve("java"), fakeJava);
        Files.writeString(
                fakeJava,
                Files.readString(fakeJava)
                        .replace(
                                "env -u SYMPHONY_TRELLO_INSTALLER_COMPLETION \"${SYMPHONY_TRELLO_COMMAND:?}\" start",
                                "env -u SYMPHONY_TRELLO_INSTALLER_COMPLETION pwsh -NoProfile -File \"${SYMPHONY_TRELLO_COMMAND:?}\" start"));
        writeExecutable(
                tools.resolve("java"),
                """
                #!/usr/bin/env bash
                set -euo pipefail
                if [[ "$*" == *"TrelloBoardSetupMain tutorial --offer"* ]]; then
                  echo tutorial-offer >> "${SYMPHONY_FAKE_LOG:?}"
                  printf '%s'
                  IFS= read -r answer || true
                  echo "tutorial-answer=${answer:-}" >> "${SYMPHONY_FAKE_LOG:?}"
                  case "${SYMPHONY_FAKE_TUTORIAL_MODE:?}" in
                    FAILURE) exit 23 ;;
                    INTERRUPT)
                      echo 'Tutorial child is ready'
                      IFS= read -r next
                      exit 99
                      ;;
                  esac
                  echo "You can start it later with: symphony-trello tutorial"
                  exit 0
                fi
                exec %s "$@"
                """
                        .formatted(GuidedTutorial.OFFER_PROMPT, shellQuote(fakeJava.toString())));
        Path home = Files.createDirectories(temporaryDirectory.resolve("home"));
        Path installHome = temporaryDirectory.resolve("installed");
        Path bin = temporaryDirectory.resolve("bin");
        Path log = temporaryDirectory.resolve("tools.log");
        Files.createFile(temporaryDirectory.resolve("codex-authenticated"));
        Map<String, String> environment = new HashMap<>();
        environment.put("PATH", tools + File.pathSeparator + System.getenv("PATH"));
        environment.put("HOME", home.toString());
        environment.put("USERPROFILE", home.toString());
        environment.put("TERM", "xterm-256color");
        environment.put("SYMPHONY_TRELLO_REPO_URL", source.toUri().toString());
        environment.put("SYMPHONY_TRELLO_REF", "main");
        environment.put("SYMPHONY_HOME", installHome.toString());
        environment.put("SYMPHONY_FAKE_LOG", log.toString());
        environment.put("SYMPHONY_FAKE_TUTORIAL_MODE", scenario.name());
        var command = new StringJoiner(" ");
        // Windows ACL APIs are the only installer boundary unavailable in the pinned Linux image.
        String installer = Files.readString(Path.of("install.ps1"));
        int protectionStart = installer.indexOf("function Protect-PrivateFile(");
        int protectionEnd = installer.indexOf("function Write-AutostartLauncher", protectionStart);
        assertThat(protectionStart).isNotNegative();
        assertThat(protectionEnd).isGreaterThan(protectionStart);
        Path testInstaller = temporaryDirectory.resolve("install-fixture.ps1");
        Files.writeString(
                testInstaller,
                installer.substring(0, protectionStart)
                        + "function Protect-PrivateFile([string]$Path) { & chmod 600 $Path }\n\n"
                        + installer.substring(protectionEnd));
        String launch = "$env:USERPROFILE = " + InstallerScriptFixture.powerShellLiteral(home.toString())
                + "; & " + InstallerScriptFixture.powerShellLiteral(testInstaller.toString())
                + " -NoUpdatePath -BinDir " + InstallerScriptFixture.powerShellLiteral(bin.toString());
        for (String argument : InstallerScriptFixture.command(powershell, "-NoProfile", "-Command", launch)) {
            command.add(shellQuote(argument));
        }
        List<TerminalAnswer> answers = new ArrayList<>(List.of(
                new TerminalAnswer(InstallerScriptFixture.TRELLO_KEY_PROMPT, "synthetic-key"),
                new TerminalAnswer(InstallerScriptFixture.TRELLO_TOKEN_PROMPT, "synthetic-token"),
                new TerminalAnswer(InstallerScriptFixture.TRELLO_BOARD_PROMPT, BOARD_NAME),
                new TerminalAnswer(GuidedTutorial.OFFER_PROMPT, scenario.answer, scenario != PowerShellOffer.EOF)));
        if (scenario == PowerShellOffer.INTERRUPT) {
            answers.add(new TerminalAnswer("Tutorial child is ready", "\u0003", false));
        }
        try {

            // when
            var result = runPseudoTerminalDialog(
                    CLEARED,
                    environment,
                    temporaryDirectory,
                    InstallerScriptFixture.withTerminalSize(command.toString()),
                    answers);

            // then
            if (scenario != PowerShellOffer.INTERRUPT) {
                result.assertSuccess();
            }
            assertThat(result.transcript())
                    .containsSubsequence("Managed workers started", HANDOFF, GuidedTutorial.OFFER_PROMPT)
                    .doesNotContain("Installer stopped during");
            if (scenario == PowerShellOffer.FAILURE) {
                assertThat(result.transcript()).contains("Installation is complete. Start the tutorial again with:");
            }
            assertThat(log)
                    .content(StandardCharsets.UTF_8)
                    .containsSubsequence("completion_mode=print", "tutorial-offer");
            if (scenario == PowerShellOffer.ACCEPT) {
                assertThat(Files.readAllLines(log)).contains("tutorial-answer=y");
            }
            assertThat(bin.resolve("symphony-trello.ps1")).isRegularFile();
            assertThat(bin.resolve("symphony-trello-autostart.ps1")).isRegularFile();
            assertThat(installHome.resolve("config/connected-boards.json")).isRegularFile();
        } finally {
            if (Files.isRegularFile(bin.resolve("symphony-trello.ps1"))) {
                run(
                                environment,
                                temporaryDirectory,
                                InstallerScriptFixture.command(
                                                powershell,
                                                "-NoProfile",
                                                "-File",
                                                bin.resolve("symphony-trello.ps1")
                                                        .toString(),
                                                "stop",
                                                "--all")
                                        .toArray(String[]::new))
                        .assertSuccess();
            }
        }
    }

    private Fixture fixture(StatefulFakeTrello trello) throws Exception {
        Path source = createSourceRepository(temporaryDirectory);
        Path tools = createFakeToolchain(temporaryDirectory);
        Path fakeJava = tools.resolve("java-fixture");
        Files.move(tools.resolve("java"), fakeJava);
        var detachedWorker = new StringJoiner(" ");
        for (String argument : new PosixManagedProcessPlatform().launchCommand(List.of("bash"))) {
            detachedWorker.add(shellQuote(argument));
        }
        // Match the real worker's session detachment, so closing the installer's PTY does not
        // kill the fake worker before the survivor assertion observes it.
        Files.writeString(fakeJava, Files.readString(fakeJava).replace("bash -c '", detachedWorker + " -c '"));
        writeExecutable(
                tools.resolve("java"),
                """
                #!/usr/bin/env bash
                set -euo pipefail
                if [[ "$*" == *"TrelloBoardSetupMain tutorial --offer"* ]]; then
                  echo tutorial-offer >> "${SYMPHONY_FAKE_LOG:?}"
                  exec %s -cp %s ch.fmartin.symphony.trello.setup.TrelloBoardSetupMain tutorial --offer \
                    --endpoint %s --key synthetic-key --token synthetic-token --no-github
                fi
                exec %s "$@"
                """
                        .formatted(
                                shellQuote(Path.of(System.getProperty("java.home"), "bin", "java")
                                        .toString()),
                                shellQuote(System.getProperty("java.class.path")),
                                shellQuote(trello.endpoint().toString()),
                                shellQuote(fakeJava.toString())));
        Path home = Files.createDirectories(temporaryDirectory.resolve("home"));
        Path installHome = temporaryDirectory.resolve("installed");
        Path bin = temporaryDirectory.resolve("bin");
        Path log = temporaryDirectory.resolve("tools.log");
        Files.createFile(temporaryDirectory.resolve("codex-authenticated"));
        Map<String, String> environment = Map.of(
                "PATH",
                tools + File.pathSeparator + System.getenv("PATH"),
                "HOME",
                home.toString(),
                "USER",
                "symphony-test",
                "TERM",
                "xterm-256color",
                "SYMPHONY_TRELLO_REPO_URL",
                source.toUri().toString(),
                "SYMPHONY_TRELLO_REF",
                "main",
                "SYMPHONY_HOME",
                installHome.toString(),
                "SYMPHONY_FAKE_LOG",
                log.toString());
        String installerCommand = InstallerScriptFixture.withTerminalSize("bash "
                + shellQuote(Path.of("install.sh").toAbsolutePath().toString()) + " --no-update-path --bin-dir "
                + shellQuote(bin.toString()));
        return new Fixture(environment, home, installHome, bin.resolve("symphony-trello"), log, installerCommand);
    }

    enum PowerShellOffer {
        NO("n"),
        DEFAULT_NO(""),
        EOF("\u0004"),
        ACCEPT("y"),
        FAILURE("y"),
        INTERRUPT("y");

        private final String answer;

        PowerShellOffer(String answer) {
            this.answer = answer;
        }
    }

    private static void assertInstallationAndWorkerPreserved(Fixture fixture) throws IOException {
        assertThat(fixture.command()).isExecutable();
        assertThat(fixture.home().resolve(".config/systemd/user/symphony-trello.service"))
                .isRegularFile();
        assertThat(fixture.installHome().resolve("config/connected-boards.json"))
                .isRegularFile();
        Path workerPid = fixture.installHome().resolve("state/WORKFLOW.installed-queue.md.abcdef123456.pid");
        assertThat(ProcessHandle.of(Long.parseLong(Files.readString(workerPid).trim())))
                .hasValueSatisfying(worker -> assertThat(worker.isAlive())
                        .as("optional tutorial work leaves the started managed worker alive")
                        .isTrue());
    }

    private record Fixture(
            Map<String, String> environment,
            Path home,
            Path installHome,
            Path command,
            Path log,
            String installerCommand)
            implements AutoCloseable {
        @Override
        public void close() throws Exception {
            if (Files.isExecutable(command)) {
                run(environment, command.toString(), "stop", "--all").assertSuccess();
            }
        }
    }
}
