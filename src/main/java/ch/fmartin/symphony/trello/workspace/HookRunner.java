package ch.fmartin.symphony.trello.workspace;

import ch.fmartin.symphony.trello.config.EffectiveConfig;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.jboss.logging.Logger;

@ApplicationScoped
public class HookRunner {
    private static final Logger LOG = Logger.getLogger(HookRunner.class);
    private static final int LOG_OUTPUT_LIMIT = 4_096;

    public void runRequired(String name, String script, Path cwd, EffectiveConfig.HooksConfig hooks) {
        if (script == null || script.isBlank()) {
            return;
        }
        HookResult result = run(name, script, cwd, hooks.timeout());
        if (!result.success()) {
            throw new WorkspaceException("hook_failed", "Hook " + name + " failed: " + result.summary());
        }
    }

    public void runBestEffort(String name, String script, Path cwd, EffectiveConfig.HooksConfig hooks) {
        if (script == null || script.isBlank() || cwd == null) {
            return;
        }
        HookResult result = run(name, script, cwd, hooks.timeout());
        if (!result.success()) {
            LOG.warnf("hook=%s outcome=failed reason=%s", name, result.summary());
        }
    }

    private HookResult run(String name, String script, Path cwd, Duration timeout) {
        LOG.infof("hook=%s cwd=%s outcome=started", name, cwd);
        Process process;
        try {
            process = new ProcessBuilder(List.of("bash", "-lc", script))
                    .directory(cwd.toFile())
                    .redirectErrorStream(true)
                    .start();
        } catch (IOException e) {
            return HookResult.failure("start_failed: " + e.getMessage());
        }
        try {
            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                return HookResult.failure("timeout after " + timeout.toMillis() + " ms");
            }
            String output = truncate(new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
            if (process.exitValue() == 0) {
                LOG.infof("hook=%s cwd=%s outcome=completed", name, cwd);
                return HookResult.success(output);
            }
            return HookResult.failure("exit_code=" + process.exitValue() + " output=" + output);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return HookResult.failure("interrupted");
        } catch (IOException e) {
            return HookResult.failure("output_read_failed: " + e.getMessage());
        }
    }

    private static String truncate(String output) {
        if (output == null || output.length() <= LOG_OUTPUT_LIMIT) {
            return output;
        }
        return output.substring(0, LOG_OUTPUT_LIMIT) + "...";
    }

    private record HookResult(boolean success, String summary) {
        static HookResult success(String output) {
            return new HookResult(true, output);
        }

        static HookResult failure(String reason) {
            return new HookResult(false, reason);
        }
    }
}
