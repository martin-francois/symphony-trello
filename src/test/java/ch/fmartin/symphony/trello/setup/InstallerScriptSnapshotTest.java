package ch.fmartin.symphony.trello.setup;

import static ch.fmartin.symphony.trello.setup.InstallerScriptFixture.HostEnvironment.CLEARED;
import static ch.fmartin.symphony.trello.setup.InstallerScriptFixture.createFakeToolchain;
import static ch.fmartin.symphony.trello.setup.InstallerScriptFixture.createSourceRepository;
import static ch.fmartin.symphony.trello.setup.InstallerScriptFixture.hostCommandDirectory;
import static ch.fmartin.symphony.trello.setup.InstallerScriptFixture.installerDefaultRef;
import static ch.fmartin.symphony.trello.setup.InstallerScriptFixture.run;
import static ch.fmartin.symphony.trello.setup.InstallerScriptFixture.runPseudoTerminalDialog;
import static ch.fmartin.symphony.trello.setup.InstallerScriptFixture.searchPathEntries;
import static ch.fmartin.symphony.trello.setup.InstallerScriptFixture.shellQuote;
import static ch.fmartin.symphony.trello.setup.LocalSetupFixtureSupport.availablePort;
import static ch.fmartin.symphony.trello.testsupport.CredentialSentinels.TRELLO_API_KEY;
import static ch.fmartin.symphony.trello.testsupport.CredentialSentinels.TRELLO_API_TOKEN;
import static ch.fmartin.symphony.trello.testsupport.TestRepositoryUrls.HTTPS;
import static org.assertj.core.api.Assertions.assertThat;

import ch.fmartin.symphony.trello.setup.InstallerScriptFixture.ProcessResult;
import ch.fmartin.symphony.trello.setup.InstallerScriptFixture.PseudoTerminalResult;
import ch.fmartin.symphony.trello.setup.InstallerScriptFixture.TerminalAnswer;
import ch.fmartin.symphony.trello.testsupport.FakeTrelloServer;
import ch.fmartin.symphony.trello.testsupport.TerminalSnapshots;
import ch.fmartin.symphony.trello.testsupport.TranscriptNormalizer;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/// End-to-end snapshots of the POSIX installer and the command it installs.
///
/// The in-process suite in [TrelloBoardSetupMainSnapshotTest] covers every command. This small
/// layer covers what only a real process boundary shows: the `install.sh` plan, the guided
/// installation in a real pseudo-terminal, and dispatch through the installed wrapper. Every child
/// starts from an empty environment and a PATH that exposes only the commands the scenario needs,
/// so locale, terminal type, color settings, and optional host tools cannot change a transcript.
@EnabledOnOs(
        value = OS.LINUX,
        disabledReason =
                "The baselines record the Linux install.sh path; run scripts/snapshot-tests-docker.sh elsewhere")
final class InstallerScriptSnapshotTest {
    private static final TerminalSnapshots SNAPSHOTS = TerminalSnapshots.repository();
    private static final String BOARD_NAME = "Snapshot Queue";
    private static final List<String> HOST_COMMANDS = List.of(
            "awk",
            "basename",
            "bash",
            "cat",
            "chmod",
            "cp",
            "cut",
            "date",
            "df",
            "dirname",
            "env",
            "find",
            "git",
            "grep",
            "head",
            "id",
            "ln",
            "ls",
            "mkdir",
            "mktemp",
            "mv",
            "readlink",
            "rm",
            // util-linux script provides the pseudo-terminal; the fixture starts it from this PATH check.
            "script",
            "sed",
            "sleep",
            "sort",
            "stat",
            "stty",
            "tail",
            "tee",
            "touch",
            "tr",
            "uname",
            "wc");

    @TempDir
    Path temporaryDirectory;

    @Test
    void dryRunWithoutOnboarding() throws Exception {
        // given
        Map<String, String> environment = baseEnvironment(fakeToolchainPath());

        // when
        ProcessResult result = run(
                CLEARED,
                environment,
                temporaryDirectory,
                "bash",
                installScript().toString(),
                "--dry-run",
                "--no-onboard");

        // then
        SNAPSHOTS.verifyStreams(
                "installer/dry-run-no-onboard", result.exitCode(), result.stdout(), result.stderr(), normalizer());
    }

    @Test
    void guidedInstallationInPseudoTerminal() throws Exception {
        // given
        Map<String, String> environment = sourceCheckoutEnvironment();
        environment.put("TERM", "xterm-256color");
        Path binDirectory = temporaryDirectory.resolve("bin");

        // when
        PseudoTerminalResult install;
        try {
            install = runPseudoTerminalDialog(
                    CLEARED,
                    environment,
                    temporaryDirectory,
                    "bash " + shellQuote(installScript().toString()) + " --bin-dir "
                            + shellQuote(binDirectory.toString()),
                    List.of(
                            new TerminalAnswer("Can this machine open a browser for Codex login? [Y/n] ", "n"),
                            new TerminalAnswer("Trello API key: ", TRELLO_API_KEY),
                            new TerminalAnswer("Trello API token: ", TRELLO_API_TOKEN),
                            new TerminalAnswer("Trello board name: ", BOARD_NAME)));
        } finally {
            stopFakeWorkers(environment, binDirectory);
        }

        // then
        SNAPSHOTS.verifyTerminal("installer/guided-install", install.exitCode(), install.transcript(), normalizer());
        assertThat(installedConfig().resolve(".env"))
                .content(StandardCharsets.UTF_8)
                .contains(TRELLO_API_KEY, TRELLO_API_TOKEN);
    }

    @Test
    void installedCommandRootHelp() throws Exception {
        // given
        Path command = installWithTestClasspath();

        // when
        ProcessResult result =
                run(CLEARED, baseEnvironment(realJavaPath()), temporaryDirectory, command.toString(), "--help");

        // then
        SNAPSHOTS.verifyStreams(
                "installer/installed-root-help", result.exitCode(), result.stdout(), result.stderr(), normalizer());
    }

    @Test
    void installedCommandCreatesBoard() throws Exception {
        // given
        Path command = installWithTestClasspath();
        int serverPort = availablePort();
        try (FakeTrelloServer trello = new FakeTrelloServer().start()) {
            String[] newBoard = {
                command.toString(),
                "new-board",
                "--endpoint",
                trello.endpoint(),
                "--key",
                TRELLO_API_KEY,
                "--token",
                TRELLO_API_TOKEN,
                "--name",
                BOARD_NAME,
                "--repository-url",
                HTTPS,
                "--server-port",
                String.valueOf(serverPort)
            };

            // when
            ProcessResult result = run(CLEARED, baseEnvironment(realJavaPath()), temporaryDirectory, newBoard);

            // then
            SNAPSHOTS.verifyStreams(
                    "installer/installed-new-board",
                    result.exitCode(),
                    result.stdout(),
                    result.stderr(),
                    TranscriptNormalizer.builder()
                            .temporaryRoot(temporaryDirectory, TranscriptNormalizer.TEMPORARY_ROOT)
                            .literal("HTTP status port: " + serverPort, "HTTP status port: <SERVER_PORT>")
                            .build());
            assertThat(trello.createdLists()).isNotEmpty();
            assertThat(installedConfig().resolve(".env"))
                    .content(StandardCharsets.UTF_8)
                    .contains(TRELLO_API_KEY, TRELLO_API_TOKEN);
        }
    }

    private Path installWithTestClasspath() throws Exception {
        Path binDirectory = temporaryDirectory.resolve("bin");
        ProcessResult install = run(
                CLEARED,
                sourceCheckoutEnvironment(),
                temporaryDirectory,
                "bash",
                installScript().toString(),
                "--no-onboard",
                "--bin-dir",
                binDirectory.toString());
        install.assertSuccess();
        writePathingJar(symphonyHome().resolve("app/target/quarkus-app/quarkus-run.jar"));
        return binDirectory.resolve("symphony-trello");
    }

    /// The fake source checkout builds an empty application. Replace its launcher jar with one
    /// whose manifest points at this test run's compiled classes and dependencies, so the
    /// installed wrapper starts the real command-line application.
    private static void writePathingJar(Path jar) throws IOException {
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        List<String> entries = new ArrayList<>();
        for (String entry : searchPathEntries(classpath)) {
            entries.add(Path.of(entry).toUri().toString());
        }
        var manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.CLASS_PATH, String.join(" ", entries));
        Files.createDirectories(jar.getParent());
        try (OutputStream output = Files.newOutputStream(jar);
                var ignored = new JarOutputStream(output, manifest)) {
            // The manifest is the whole jar.
        }
    }

    private void stopFakeWorkers(Map<String, String> environment, Path binDirectory) throws Exception {
        Path command = binDirectory.resolve("symphony-trello");
        if (Files.isExecutable(command)) {
            run(CLEARED, environment, temporaryDirectory, command.toString(), "stop");
        }
    }

    private Map<String, String> sourceCheckoutEnvironment() throws Exception {
        Map<String, String> environment = baseEnvironment(fakeToolchainPath());
        environment.put(
                "SYMPHONY_TRELLO_REPO_URL",
                createSourceRepository(temporaryDirectory).toString());
        environment.put("SYMPHONY_TRELLO_REF", "main");
        environment.put("SYMPHONY_HOME", symphonyHome().toString());
        environment.put(
                "SYMPHONY_FAKE_LOG",
                temporaryDirectory.resolve("fake-tools.log").toString());
        return environment;
    }

    private Map<String, String> baseEnvironment(String path) throws IOException {
        Path home = temporaryDirectory.resolve("user-home");
        Files.createDirectories(home);
        // Mutable so scenarios can add variables; insertion order keeps failure output readable.
        Map<String, String> environment = new LinkedHashMap<>();
        environment.put("PATH", path);
        environment.put("HOME", home.toString());
        environment.put("USER", "symphony-test");
        environment.put("SHELL", "/bin/bash");
        environment.put("LC_ALL", "C");
        environment.put("SYMPHONY_TRELLO_TEST_ARCH", "x86_64");
        return environment;
    }

    private String fakeToolchainPath() throws IOException {
        return createFakeToolchain(temporaryDirectory) + File.pathSeparator + hostCommands();
    }

    private String realJavaPath() throws IOException {
        return Path.of(System.getProperty("java.home"), "bin") + File.pathSeparator + hostCommands();
    }

    private Path hostCommands() throws IOException {
        Path directory = temporaryDirectory.resolve("host-commands");
        return Files.isDirectory(directory) ? directory : hostCommandDirectory(directory, HOST_COMMANDS);
    }

    private TranscriptNormalizer normalizer() throws IOException {
        return TranscriptNormalizer.builder()
                .temporaryRoot(temporaryDirectory, TranscriptNormalizer.TEMPORARY_ROOT)
                .literal(installerVersion(), "<INSTALLER_VERSION>")
                .build();
    }

    /// The release version `install.sh` installs by default; Release Please bumps it every release.
    private static String installerVersion() throws IOException {
        return installerDefaultRef().substring("v".length());
    }

    private Path installedConfig() {
        return symphonyHome().resolve("config");
    }

    private Path symphonyHome() {
        return temporaryDirectory.resolve("home");
    }

    private static Path installScript() {
        return Path.of("install.sh").toAbsolutePath();
    }
}
