package ch.fmartin.symphony.trello.setup;

import static ch.fmartin.symphony.trello.setup.InstallerScriptFixture.run;
import static ch.fmartin.symphony.trello.setup.InstallerScriptFixture.shellQuote;
import static ch.fmartin.symphony.trello.setup.InstallerScriptFixture.writeExecutable;
import static org.assertj.core.api.Assertions.assertThat;

import ch.fmartin.symphony.trello.setup.InstallerScriptFixture.ProcessResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

final class ContainerRuntimeScriptTest {
    @TempDir
    Path tempDir;

    @MethodSource("containerWrappers")
    @ParameterizedTest
    void usesExplicitPodmanRuntime(ContainerWrapper wrapper) throws Exception {
        // given
        Path invocation = tempDir.resolve("invocation.txt");
        installRecordingRuntime("podman", invocation);
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
            assertThat(invocationArguments).contains("-e\nHOME=/tmp", "--disable-version-check");
        }
    }

    @MethodSource("containerWrappers")
    @ParameterizedTest
    void defaultsToDockerRuntime(ContainerWrapper wrapper) throws Exception {
        // given
        Path invocation = tempDir.resolve("invocation.txt");
        installRecordingRuntime("docker", invocation);
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
            assertThat(invocationArguments).contains("-e\nHOME=/tmp", "--disable-version-check");
        }
    }

    @MethodSource("containerWrappers")
    @ParameterizedTest
    void rejectsUnsupportedContainerRuntime(ContainerWrapper wrapper) throws Exception {
        // given
        Path invocation = tempDir.resolve("invocation.txt");
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

    @MethodSource("containerRuntimeRequirements")
    @ParameterizedTest
    void rejectsMissingSelectedContainerRuntime(String script, String requiredMessage) throws Exception {
        // given
        Path invocation = tempDir.resolve("invocation.txt");
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

    private void installRecordingRuntime(String name, Path invocation) throws Exception {
        writeExecutable(
                tempDir.resolve(name),
                """
                #!/usr/bin/env bash
                set -euo pipefail
                printf '%%s\n' "$@" > "%s"
                """
                        .formatted(invocation));
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

    private ProcessResult runWrapper(String script, Map<String, String> environment) throws Exception {
        var processBuilder = new ProcessBuilder(wrapper(script).toString(), "--version");
        processBuilder.directory(Path.of(".").toAbsolutePath().normalize().toFile());
        processBuilder.environment().remove("SYMPHONY_TRELLO_CONTAINER_RUNTIME");
        processBuilder.environment().remove("SYMPHONY_TRELLO_PWSH_DOCKER_IMAGE");
        processBuilder.environment().putAll(environment);
        return run(processBuilder, "", 30);
    }

    private static Stream<ContainerWrapper> containerWrappers() {
        return Stream.of(
                new ContainerWrapper(
                        "betterleaks-docker.sh",
                        "(?m)^ghcr\\.io/betterleaks/betterleaks:v[0-9]+\\.[0-9]+\\.[0-9]+$",
                        "ghcr.io/betterleaks/betterleaks"),
                new ContainerWrapper(
                        "semgrep-docker.sh",
                        "(?m)^docker\\.io/semgrep/semgrep:[0-9]+\\.[0-9]+\\.[0-9]+$",
                        "docker.io/semgrep/semgrep"),
                new ContainerWrapper(
                        "pwsh-docker.sh",
                        "(?m)^mcr\\.microsoft\\.com/dotnet/sdk:[0-9]+\\.[0-9]+$",
                        "mcr.microsoft.com/dotnet/sdk"));
    }

    private static Stream<Arguments> containerRuntimeRequirements() {
        return Stream.of(
                Arguments.of("betterleaks-docker.sh", "podman is required to run BetterLeaks in a container"),
                Arguments.of("semgrep-docker.sh", "podman is required to run Semgrep in a container"),
                Arguments.of(
                        "pwsh-docker.sh",
                        "podman is required to run PowerShell through mcr.microsoft.com/dotnet/sdk:"));
    }

    private static Path wrapper(String script) {
        return Path.of("scripts", script).toAbsolutePath().normalize();
    }

    private record ContainerWrapper(String script, String imagePattern, String imageRepository) {}
}
