package ch.fmartin.symphony.trello.setup;

import static ch.fmartin.symphony.trello.setup.InstallerScriptFixture.run;
import static ch.fmartin.symphony.trello.setup.InstallerScriptFixture.shellQuote;
import static ch.fmartin.symphony.trello.setup.InstallerScriptFixture.withEnvironment;
import static ch.fmartin.symphony.trello.setup.InstallerScriptFixture.writeExecutable;
import static org.assertj.core.api.Assertions.assertThat;

import ch.fmartin.symphony.trello.setup.InstallerScriptFixture.ProcessResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.AnnotatedElementContext;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.io.TempDirFactory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

final class ContainerRuntimeScriptTest {
    private static final String SHA256_DIGEST_PATTERN = "@sha256:[a-f0-9]{64}$";
    // The wrappers pass "-e HOME=/tmp", and the recording runtimes write one argument per line.
    private static final String CONTAINER_HOME_ARGUMENTS = "-e\nHOME=/tmp";
    private static final String DOCKER_VERSION_OUTPUT = "Docker version 28.5.1, build e180ab8";
    private static final String PODMAN_VERSION_OUTPUT = "podman version 6.0.2";

    @TempDir
    Path tempDir;

    @MethodSource("containerWrappers")
    @ParameterizedTest
    void usesExplicitPodmanRuntime(ContainerWrapper wrapper) throws Exception {
        // given
        Path invocation = installRecordingRuntime("podman");
        installFailingRuntime("docker");

        // when
        ProcessResult result = runWrapper(wrapper.script(), runtimeEnvironment("podman"));

        // then
        result.assertSuccess();
        String invocationArguments = Files.readString(invocation);
        assertThat(invocationArguments)
                .contains("run", "--security-opt\nlabel=disable", "--userns=keep-id")
                .containsPattern(wrapper.imagePattern());
        if (wrapper.script().equals("semgrep-docker.sh")) {
            assertThat(invocationArguments).contains(CONTAINER_HOME_ARGUMENTS, "--disable-version-check");
        }
    }

    @MethodSource("containerWrappers")
    @ParameterizedTest
    void defaultsToDockerRuntime(ContainerWrapper wrapper) throws Exception {
        // given
        Path invocation = installRecordingRuntime("docker");
        installFailingRuntime("podman");

        // when
        ProcessResult result = runWrapper(wrapper.script(), runtimeEnvironment(null));

        // then
        result.assertSuccess();
        String invocationArguments = Files.readString(invocation);
        assertThat(invocationArguments)
                .contains("run", "--security-opt\nlabel=disable")
                .containsPattern(wrapper.imagePattern())
                .doesNotContain("--userns=keep-id");
        if (wrapper.script().equals("semgrep-docker.sh")) {
            assertThat(invocationArguments).contains(CONTAINER_HOME_ARGUMENTS, "--disable-version-check");
        }
    }

    @MethodSource("containerWrappers")
    @ParameterizedTest
    void rejectsUnsupportedContainerRuntime(ContainerWrapper wrapper) throws Exception {
        // given
        Path invocation = invocationFile();
        installFailingRuntime("docker");
        installFailingRuntime("podman");

        // when
        ProcessResult result = runWrapper(wrapper.script(), runtimeEnvironment("other"));

        // then
        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.output())
                .contains("SYMPHONY_TRELLO_CONTAINER_RUNTIME must be docker or podman")
                .doesNotContain(wrapper.imageRepository());
        assertThat(invocation).doesNotExist();
    }

    @MethodSource("snapshotCiValues")
    @ParameterizedTest
    void snapshotWrapperPreservesWhetherCiIsSet(String ci) throws Exception {
        // given
        Path invocation = installRecordingRuntime("docker");
        var environment = new HashMap<>(runtimeEnvironment(null));
        environment.put("XDG_CACHE_HOME", tempDir.resolve("cache").toString());
        if (ci != null) {
            environment.put("CI", ci);
        }

        // when
        ProcessResult result = runWrapper("snapshot-tests-docker.sh", environment);

        // then
        result.assertSuccess();
        String arguments = Files.readString(invocation);
        if (ci == null) {
            assertThat(arguments).doesNotContain("CI=");
        } else {
            assertThat(arguments).contains("-e\nCI=" + ci + "\n");
        }
    }

    private static Stream<Arguments> snapshotCiValues() {
        return Stream.of(Arguments.of((String) null), Arguments.of("true"), Arguments.of("false"));
    }

    @Test
    void betterLeaksWrapperRunsTheImageAPullRequestPins() throws Exception {
        // given
        Path invocation = installRecordingRuntime("docker");
        String pinnedImage = "ghcr.io/betterleaks/betterleaks:v9.9.9@sha256:" + "a".repeat(64);

        // when
        ProcessResult result = runWrapper(
                "betterleaks-docker.sh",
                defaultRuntimeEnvironmentWith("SYMPHONY_TRELLO_BETTERLEAKS_IMAGE", pinnedImage));

        // then
        result.assertSuccess();
        assertThat(Files.readAllLines(invocation)).contains(pinnedImage);
    }

    @ParameterizedTest
    @ValueSource(strings = {"docker", "podman"})
    void powershellWrapperRunsRuntimeWithAccountHomeInsteadOfTestHome(String runtime) throws Exception {
        // given
        Path invocation = installRecordingRuntime(runtime);
        Path accountHome = tempDir.resolve("account-home");
        installPasswdLookup(accountHome);
        Map<String, String> environment = new HashMap<>(runtimeEnvironment(runtime));
        environment.put("HOME", createTestHome().toString());

        // when
        ProcessResult result = runWrapper("pwsh-docker.sh", environment);

        // then
        result.assertSuccess();
        assertThat(recordedRuntimeHome()).content(StandardCharsets.UTF_8).isEqualTo(accountHome.toString());
        assertThat(invocation).content(StandardCharsets.UTF_8).contains(CONTAINER_HOME_ARGUMENTS);
    }

    @Test
    void powershellWrapperKeepsCallerHomeWithoutGetent() throws Exception {
        // given
        installRecordingRuntime("docker");
        for (String command : List.of("bash", "cut", "dirname", "env", "id")) {
            installHostCommand(command);
        }
        Path testHome = createTestHome();

        // when
        ProcessResult result =
                runWrapper("pwsh-docker.sh", Map.of("PATH", tempDir.toString(), "HOME", testHome.toString()));

        // then
        result.assertSuccess();
        assertThat(recordedRuntimeHome()).content(StandardCharsets.UTF_8).isEqualTo(testHome.toString());
    }

    @Test
    void powerShellWrapperForwardsOutputColorControls() throws Exception {
        // given
        Path invocation = installRecordingRuntime("podman");
        Map<String, String> environment = withEnvironment(
                runtimeEnvironment("podman"),
                Map.of("NO_COLOR", "1", "CLICOLOR", "0", "CLICOLOR_FORCE", "1", "UNRELATED_HOST_CONTROL", "unrelated"));

        // when
        ProcessResult result = runWrapper("pwsh-docker.sh", environment);

        // then
        result.assertSuccess();
        assertThat(invocation)
                .content(StandardCharsets.UTF_8)
                .contains("-e\nNO_COLOR\n", "-e\nCLICOLOR\n", "-e\nCLICOLOR_FORCE\n")
                .doesNotContain("UNRELATED_HOST_CONTROL");
    }

    @Test
    void powershellWrapperKeepsHostUserWhenDockerCommandIsPodman() throws Exception {
        // given
        Path invocation = tempDir.resolve("invocation.txt");
        installRecordingRuntime("docker", invocation, PODMAN_VERSION_OUTPUT);
        installFailingRuntime("podman");

        // when
        List<String> invocationArguments = runPowerShellWrapperWith(
                invocation,
                "HOME",
                tempDir.resolve("missing-installer-test-home").toString());

        // then
        assertThat(invocationArguments).contains("run", "--userns=keep-id");
    }

    @Test
    void powershellWrapperMountsTemporaryDirectoryOutsideTmp(
            @TempDir(factory = TemporaryDirectoryOutsideTmpFactory.class) Path testTemporaryDirectory)
            throws Exception {
        // given
        Path invocation = tempDir.resolve("invocation.txt");
        installRecordingRuntime("docker", invocation);

        // when
        List<String> invocationArguments =
                runPowerShellWrapperWith(invocation, "TMPDIR", testTemporaryDirectory.toString());

        // then
        assertThat(invocationArguments).containsSequence("-v", testTemporaryDirectory + ":" + testTemporaryDirectory);
    }

    @MethodSource("temporaryDirectoriesAlreadyMounted")
    @ParameterizedTest
    void powershellWrapperMountsTemporaryDirectoryOnlyOnce(String temporaryDirectory, String expectedMount)
            throws Exception {
        // given
        Path invocation = tempDir.resolve("invocation.txt");
        installRecordingRuntime("docker", invocation);

        // when
        List<String> invocationArguments = runPowerShellWrapperWith(invocation, "TMPDIR", temporaryDirectory);

        // then
        assertThat(invocationArguments).containsOnlyOnce(expectedMount);
    }

    @ParameterizedTest
    @ValueSource(strings = {"relative-temp-dir", "/nonexistent/symphony-trello-temp-dir"})
    void powershellWrapperSkipsTemporaryDirectoryItCannotMount(String temporaryDirectory) throws Exception {
        // given
        Path invocation = tempDir.resolve("invocation.txt");
        installRecordingRuntime("docker", invocation);

        // when
        List<String> invocationArguments = runPowerShellWrapperWith(invocation, "TMPDIR", temporaryDirectory);

        // then
        assertThat(invocationArguments).noneMatch(argument -> argument.contains(temporaryDirectory));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " < /dev/null", " > /dev/null", " < /dev/null > /dev/null"})
    void powershellWrapperAllocatesTerminalOnlyWhenBothStreamsAreTerminals(String redirection) throws Exception {
        // given
        Path invocation = installRecordingRuntime("podman");
        String command = shellQuote(wrapper("pwsh-docker.sh").toString()) + " --version" + redirection;

        // when
        var result = InstallerScriptFixture.runWithPseudoTerminal(runtimeEnvironment("podman"), "", command);

        // then
        result.assertSuccess();
        List<String> arguments = Files.readAllLines(invocation);
        if (redirection.isEmpty()) {
            assertThat(arguments).containsSubsequence("-i", "-t");
        } else {
            assertThat(arguments).doesNotContain("-i", "-t");
        }
    }

    @MethodSource("containerRuntimeRequirements")
    @ParameterizedTest
    void rejectsMissingSelectedContainerRuntime(String script, String requiredMessage) throws Exception {
        // given
        Path invocation = invocationFile();
        installHostCommand("bash");
        installHostCommand("dirname");
        installHostCommand("git");

        // when
        ProcessResult result =
                runWrapper(script, Map.of("PATH", tempDir.toString(), "SYMPHONY_TRELLO_CONTAINER_RUNTIME", "podman"));

        // then
        assertThat(result.exitCode()).isEqualTo(127);
        assertThat(result.output()).contains(requiredMessage);
        assertThat(invocation).doesNotExist();
    }

    private Path installRecordingRuntime(String name) throws Exception {
        Path invocation = invocationFile();
        installRecordingRuntime(name, invocation, DOCKER_VERSION_OUTPUT);
        return invocation;
    }

    private void installRecordingRuntime(String name, Path invocation) throws Exception {
        installRecordingRuntime(name, invocation, DOCKER_VERSION_OUTPUT);
    }

    private void installRecordingRuntime(String name, Path invocation, String versionOutput) throws Exception {
        writeExecutable(
                tempDir.resolve(name),
                """
                #!/usr/bin/env bash
                set -euo pipefail
                if [[ "$*" == --version ]]; then
                  # Like Podman, refuse to start when HOME names a missing directory.
                  if [[ -n "${HOME:-}" && ! -d "$HOME" ]]; then
                    exit 125
                  fi
                  printf '%%s\n' %s
                  exit 0
                fi
                printf '%%s\n' "$@" > "%s"
                printf '%%s' "$HOME" > "%s"
                """
                        .formatted(shellQuote(versionOutput), invocation, recordedRuntimeHome()));
    }

    private void installPasswdLookup(Path accountHome) throws Exception {
        writeExecutable(
                tempDir.resolve("getent"),
                """
                #!/usr/bin/env bash
                set -euo pipefail
                [ "$1" = passwd ]
                [ "$2" = "$(id -u)" ]
                printf 'tester:x:%%s:%%s::%%s:/bin/sh\n' "$2" "$(id -g)" %s
                """
                        .formatted(shellQuote(accountHome.toString())));
    }

    private Path invocationFile() {
        return tempDir.resolve("invocation.txt");
    }

    private Path recordedRuntimeHome() {
        return tempDir.resolve("runtime-home.txt");
    }

    private Path createTestHome() throws Exception {
        return Files.createDirectories(tempDir.resolve("installer-test-home"));
    }

    private void installFailingRuntime(String name) throws Exception {
        writeExecutable(
                tempDir.resolve(name),
                """
                #!/usr/bin/env bash
                exit 71
                """);
    }

    private void installHostCommand(String name) throws Exception {
        Path executable = Stream.of(System.getenv("PATH").split(System.getProperty("path.separator")))
                .map(Path::of)
                .map(directory -> directory.resolve(name))
                .filter(path -> Files.isRegularFile(path) && Files.isExecutable(path))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(name + " is required to run this test"));
        writeExecutable(
                tempDir.resolve(name),
                """
                #!/bin/sh
                exec %s "$@"
                """
                        .formatted(shellQuote(executable.toAbsolutePath().toString())));
    }

    private Map<String, String> runtimeEnvironment(String runtime) {
        if (runtime == null) {
            return Map.of("PATH", tempDir + ":/usr/bin:/bin");
        }
        return Map.of("PATH", tempDir + ":/usr/bin:/bin", "SYMPHONY_TRELLO_CONTAINER_RUNTIME", runtime);
    }

    private Map<String, String> defaultRuntimeEnvironmentWith(String variable, String value) {
        Map<String, String> environment = new HashMap<>(runtimeEnvironment(null));
        environment.put(variable, value);
        return environment;
    }

    /// Runs `pwsh-docker.sh` with one extra variable and returns the arguments the recording runtime got.
    private List<String> runPowerShellWrapperWith(Path invocation, String variable, String value) throws Exception {
        runWrapper("pwsh-docker.sh", defaultRuntimeEnvironmentWith(variable, value))
                .assertSuccess();
        return Files.readAllLines(invocation);
    }

    private ProcessResult runWrapper(String script, Map<String, String> environment) throws Exception {
        var processBuilder = new ProcessBuilder(wrapper(script).toString(), "--version");
        processBuilder.directory(Path.of(".").toAbsolutePath().normalize().toFile());
        processBuilder.environment().remove("CI");
        processBuilder.environment().remove("SYMPHONY_TRELLO_CONTAINER_RUNTIME");
        processBuilder.environment().remove("SYMPHONY_TRELLO_PWSH_DOCKER_IMAGE");
        processBuilder.environment().remove("SYMPHONY_TRELLO_BETTERLEAKS_IMAGE");
        processBuilder.environment().putAll(environment);
        return run(processBuilder, "", 30);
    }

    private static Stream<ContainerWrapper> containerWrappers() {
        return Stream.of(
                new ContainerWrapper(
                        "betterleaks-docker.sh",
                        "(?m)^ghcr\\.io/betterleaks/betterleaks:v[0-9]+\\.[0-9]+\\.[0-9]+" + SHA256_DIGEST_PATTERN,
                        "ghcr.io/betterleaks/betterleaks"),
                new ContainerWrapper(
                        "semgrep-docker.sh",
                        "(?m)^docker\\.io/semgrep/semgrep:[0-9]+\\.[0-9]+\\.[0-9]+" + SHA256_DIGEST_PATTERN,
                        "docker.io/semgrep/semgrep"),
                new ContainerWrapper(
                        "pwsh-docker.sh",
                        "(?m)^mcr\\.microsoft\\.com/dotnet/sdk:[0-9]+\\.[0-9]+" + SHA256_DIGEST_PATTERN,
                        "mcr.microsoft.com/dotnet/sdk"),
                new ContainerWrapper(
                        "snapshot-tests-docker.sh",
                        "(?m)^docker\\.io/library/maven:[0-9]+\\.[0-9]+\\.[0-9]+-eclipse-temurin-25"
                                + SHA256_DIGEST_PATTERN,
                        "docker.io/library/maven"));
    }

    private static Stream<Arguments> temporaryDirectoriesAlreadyMounted() throws IOException {
        String repositoryRoot = Path.of(".").toRealPath().toString();
        return Stream.of(
                Arguments.of("/tmp", "/tmp:/tmp"),
                Arguments.of("/tmp/", "/tmp:/tmp"),
                Arguments.of(repositoryRoot, repositoryRoot + ":" + repositoryRoot));
    }

    private static Stream<Arguments> containerRuntimeRequirements() {
        return Stream.of(
                Arguments.of("betterleaks-docker.sh", "podman is required to run BetterLeaks in a container"),
                Arguments.of("semgrep-docker.sh", "podman is required to run Semgrep in a container"),
                Arguments.of(
                        "pwsh-docker.sh", "podman is required to run PowerShell through mcr.microsoft.com/dotnet/sdk:"),
                Arguments.of(
                        "snapshot-tests-docker.sh", "podman is required to run the snapshot tests in a container"));
    }

    private static Path wrapper(String script) {
        return Path.of("scripts", script).toAbsolutePath().normalize();
    }

    /// Creates the directory outside `/tmp`, which the wrapper always mounts. The FHS keeps `/var/tmp` on
    /// every Linux host, and macOS has it too.
    static final class TemporaryDirectoryOutsideTmpFactory implements TempDirFactory {
        @Override
        public Path createTempDirectory(AnnotatedElementContext elementContext, ExtensionContext extensionContext)
                throws Exception {
            return Files.createTempDirectory(Path.of("/var/tmp"), "symphony-trello-container-runtime-");
        }
    }

    private record ContainerWrapper(String script, String imagePattern, String imageRepository) {}
}
