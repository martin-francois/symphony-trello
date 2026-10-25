package ch.fmartin.symphony.trello.setup;

import static ch.fmartin.symphony.trello.setup.InstallerScriptFixture.createFakeWindowsToolchain;
import static ch.fmartin.symphony.trello.setup.InstallerScriptFixture.createWindowsSourceRepository;
import static ch.fmartin.symphony.trello.setup.InstallerScriptFixture.run;

import ch.fmartin.symphony.trello.setup.InstallerScriptFixture.ProcessResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/// Runs the installer in an owned native Windows console. Scheduled tasks and Java setup remain synthetic.
final class WindowsConsoleProofFixture {
    private static final String RESOURCE_DIRECTORY = "/windows-console-proof/";
    private static final int HELPER_TIMEOUT_SECONDS = 180;

    private WindowsConsoleProofFixture() {}

    static Result runInstaller(Path directory) throws Exception {
        Path resources = Files.createDirectories(directory.resolve("console resources"));
        for (String name : List.of(
                "Invoke-PrivateConsoleProof.ps1",
                "PrivateConsoleProof.cs",
                "Invoke-InstallerFixture.ps1",
                "Invoke-SyntheticJava.ps1")) {
            try (var source = WindowsConsoleProofFixture.class.getResourceAsStream(RESOURCE_DIRECTORY + name)) {
                if (source == null) {
                    throw new IOException(
                            "cannot load console proof resource \"" + name + "\": include the native test resources");
                }
                Files.copy(source, resources.resolve(name));
            }
        }
        Path repository = createWindowsSourceRepository(directory);
        Path tools = createFakeWindowsToolchain(directory);
        Path home = Files.createDirectories(directory.resolve("test profile"));
        Path symphonyHome = directory.resolve("installed");
        Path bin = directory.resolve("installed bin");
        Path proof = directory.resolve("console-result.json");
        Path ready = directory.resolve("tutorial.ready");
        Path boundaries = directory.resolve("installer-boundaries.log");
        Path transcript = directory.resolve("installer-transcript.log");
        Path task = directory.resolve("synthetic-task.json");
        var process = new ProcessBuilder(
                "pwsh",
                "-NoProfile",
                "-File",
                resources.resolve("Invoke-PrivateConsoleProof.ps1").toString(),
                "-CommandFile",
                resources.resolve("Invoke-InstallerFixture.ps1").toString(),
                "-ReadyFile",
                ready.toString(),
                "-ResultFile",
                proof.toString());
        process.directory(directory.toFile());
        Map<String, String> environment = process.environment();
        environment.keySet().removeIf(InstallerScriptFixture::isRepositoryEnvironmentControl);
        for (String name : List.of("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS")) {
            environment.remove(name);
        }
        environment.put("PATH", tools + File.pathSeparator + System.getenv("PATH"));
        environment.put("HOME", home.toString());
        environment.put("USERPROFILE", home.toString());
        environment.put(
                "APPDATA",
                Files.createDirectories(home.resolve("AppData/Roaming")).toString());
        environment.put(
                "LOCALAPPDATA",
                Files.createDirectories(home.resolve("AppData/Local")).toString());
        environment.put("SYMPHONY_HOME", symphonyHome.toString());
        environment.put("SYMPHONY_FAKE_LOG", directory.resolve("tools.log").toString());
        environment.put(
                "SYMPHONY_FAKE_JAVA",
                resources.resolve("Invoke-SyntheticJava.ps1").toString());
        environment.put("SYMPHONY_TRELLO_REPO_URL", repository.toUri().toString());
        environment.put("SYMPHONY_TRELLO_REF", "main");
        environment.put(
                "SYMPHONY_CONSOLE_TEST_INSTALLER",
                Path.of("install.ps1").toAbsolutePath().toString());
        environment.put(
                "SYMPHONY_CONSOLE_TEST_JAVA",
                Path.of(System.getProperty("java.home"), "bin", "java.exe").toString());
        environment.put(
                "SYMPHONY_CONSOLE_TEST_CLASSPATH",
                Path.of(ConsoleCancellationProbe.class
                                .getProtectionDomain()
                                .getCodeSource()
                                .getLocation()
                                .toURI())
                        .toString());
        environment.put("SYMPHONY_CONSOLE_TEST_PROBE", ConsoleCancellationProbe.class.getName());
        environment.put("SYMPHONY_CONSOLE_TEST_BIN", bin.toString());
        environment.put("SYMPHONY_CONSOLE_TEST_TASK", task.toString());
        environment.put("SYMPHONY_CONSOLE_TEST_BOUNDARIES", boundaries.toString());
        environment.put("SYMPHONY_CONSOLE_TEST_TRANSCRIPT", transcript.toString());
        ProcessResult result = run(process, "", HELPER_TIMEOUT_SECONDS);
        var mapper = new ObjectMapper();
        JsonNode evidence =
                Files.isRegularFile(proof) ? mapper.readTree(Files.readString(proof)) : mapper.createObjectNode();
        return new Result(
                result,
                evidence,
                bin,
                symphonyHome.resolve("config"),
                task,
                boundaries,
                transcript,
                ready,
                ConsoleCancellationProbe.shutdownPath(ready));
    }

    record Result(
            ProcessResult process,
            JsonNode evidence,
            Path bin,
            Path config,
            Path task,
            Path boundaries,
            Path transcript,
            Path ready,
            Path shutdown) {}
}
