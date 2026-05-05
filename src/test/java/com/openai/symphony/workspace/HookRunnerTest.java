package com.openai.symphony.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.openai.symphony.config.EffectiveConfig;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HookRunnerTest {
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
    void bestEffortHookIgnoresBlankScriptMissingDirectoryAndFailures() {
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
