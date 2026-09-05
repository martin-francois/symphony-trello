package ch.fmartin.symphony.trello.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import ch.fmartin.symphony.trello.config.EffectiveConfig;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class HookRunnerTest {
    private final HookRunner hooks = new HookRunner();

    @TempDir
    Path tempDir;

    @Test
    void requiredHookFailureIncludesExitCodeAndOutput() {
        // given
        EffectiveConfig.HooksConfig config = hooksConfig();

        // when
        Throwable thrown =
                catchThrowable(() -> hooks.runRequired("before_run", "echo nope && exit 7", tempDir, config));

        // then
        assertThat(thrown)
                .isInstanceOf(WorkspaceException.class)
                .hasMessageContaining("Hook before_run failed")
                .hasMessageContaining("exit_code=7")
                .hasMessageContaining("nope");
    }

    @Test
    void requiredHookRunsInWorkspaceWithNonLoginShell() throws Exception {
        // given
        EffectiveConfig.HooksConfig config = hooksConfig();

        // when
        hooks.runRequired(
                "before_run",
                "pwd > cwd.txt && if shopt -q login_shell; then exit 9; fi && echo non-login > shell.txt",
                tempDir,
                config);

        // then
        assertThat(tempDir.resolve("cwd.txt")).content(StandardCharsets.UTF_8).contains(tempDir.toString());
        assertThat(tempDir.resolve("shell.txt")).content(StandardCharsets.UTF_8).contains("non-login");
    }

    @Test
    void bestEffortHookIgnoresBlankScriptMissingDirectoryAndFailures() {
        // Expected WARN in the build log (issue #354): logging the failed best-effort hook is the
        // production behavior under test; muting shared logger categories would be JVM-global
        // state and is unsafe with parallel test execution.
        // given
        EffectiveConfig.HooksConfig config = hooksConfig();

        // when
        Throwable thrown = catchThrowable(() -> {
            hooks.runBestEffort("after_run", "", tempDir, config);
            hooks.runBestEffort("after_run", "exit 1", null, config);
            hooks.runBestEffort("after_run", "echo ignored && exit 1", tempDir, config);
        });

        // then
        assertThat(thrown).isNull();
    }

    private static EffectiveConfig.HooksConfig hooksConfig() {
        return new EffectiveConfig.HooksConfig(null, null, null, null, Duration.ofSeconds(2));
    }
}
