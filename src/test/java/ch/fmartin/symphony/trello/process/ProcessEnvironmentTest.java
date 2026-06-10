package ch.fmartin.symphony.trello.process;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ProcessEnvironmentTest {

    @Test
    void removesDefaultTrelloSecretsWithoutChangingOtherEnvironment() {
        // given
        Map<String, String> environment = new HashMap<>();
        environment.put("PATH", "/usr/bin");
        environment.put("TRELLO_API_KEY", "key");
        environment.put("TRELLO_API_TOKEN", "token");

        // when
        ProcessEnvironment.removeDefaultSecrets(environment);

        // then
        assertThat(environment)
                .containsEntry("PATH", "/usr/bin")
                .doesNotContainKeys("TRELLO_API_KEY", "TRELLO_API_TOKEN");
    }

    @TempDir
    Path tempDir;

    @Test
    void limitGitDiscoverySetsCeilingDirectory() {
        // given
        Map<String, String> environment = new HashMap<>();

        // when
        ProcessEnvironment.limitGitDiscovery(environment, tempDir.resolve("workspaces"));

        // then
        assertThat(environment)
                .containsEntry(
                        "GIT_CEILING_DIRECTORIES",
                        tempDir.resolve("workspaces")
                                .toAbsolutePath()
                                .normalize()
                                .toString());
    }

    @Test
    void limitGitDiscoveryStopsRepositoryDiscoveryAboveWorkspacesRoot() throws Exception {
        // given
        Assumptions.assumeTrue(gitAvailable(), "git is required for this discovery boundary test");
        Path parentRepo = tempDir.resolve("parent-repo");
        Path workspacesRoot = parentRepo.resolve("workspaces");
        Path workspace = workspacesRoot.resolve("TRELLO-SYNTH001");
        Files.createDirectories(workspace);
        runProcess(parentRepo, Map.of(), "git", "init", "--quiet", ".");

        // when
        ProcessResult unbounded = runProcess(workspace, Map.of(), "git", "rev-parse", "--show-toplevel");
        ProcessResult bounded = runProcess(
                workspace,
                Map.of(
                        "GIT_CEILING_DIRECTORIES",
                        workspacesRoot.toAbsolutePath().normalize().toString()),
                "git",
                "rev-parse",
                "--show-toplevel");

        // then
        assertThat(unbounded.exitCode()).isZero();
        assertThat(unbounded.stdout().trim()).isEqualTo(parentRepo.toRealPath().toString());
        assertThat(bounded.exitCode()).isNotZero();
    }

    private static boolean gitAvailable() {
        try {
            return new ProcessBuilder("git", "--version").start().waitFor() == 0;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }

    private record ProcessResult(int exitCode, String stdout) {}

    private static ProcessResult runProcess(Path cwd, Map<String, String> extraEnvironment, String... command)
            throws IOException, InterruptedException {
        ProcessBuilder builder =
                new ProcessBuilder(command).directory(cwd.toFile()).redirectErrorStream(true);
        builder.environment().putAll(extraEnvironment);
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = process.waitFor();
        return new ProcessResult(exitCode, output);
    }
}
