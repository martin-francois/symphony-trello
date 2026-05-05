import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Patches generated live-E2E workflows to use the deterministic Java Codex app-server double.
 *
 * <p>This helper exists so the reproducible E2E runbook does not rely on Perl/Python-style
 * one-liners for repository-maintained workflow manipulation.
 */
public class PatchLiveE2eWorkflow {
    public static void main(String[] args) throws IOException {
        if (args.length != 5 && args.length != 6) {
            System.err.println(
                    "Usage: java --source 25 scripts/PatchLiveE2eWorkflow.java <workflow> <max-agents> <sleep-ms> <java> <fake-codex-java> [review-column]");
            System.exit(2);
        }

        Path workflow = Path.of(args[0]);
        int maxAgents = positiveInt(args[1], "max-agents");
        int sleepMs = nonNegativeInt(args[2], "sleep-ms");
        String java = args[3];
        String fakeCodex = args[4];
        String reviewColumn = args.length == 6 ? args[5] : "Human Review";

        String command =
                "command: \"SYMPHONY_FAKE_CODEX_SLEEP_MS=%d SYMPHONY_FAKE_CODEX_REVIEW_STATE=%s %s --source 25 %s\""
                        .formatted(sleepMs, shellQuote(reviewColumn), shellQuote(java), shellQuote(fakeCodex));
        String patched = Files.readString(workflow)
                .replaceFirst("(?m)^  max_concurrent_agents: \\d+$", "  max_concurrent_agents: " + maxAgents)
                .replaceFirst("(?m)^  command: codex app-server$", "  " + command);
        Files.writeString(workflow, patched);
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private static int positiveInt(String value, String name) {
        int parsed = parseInt(value, name);
        if (parsed < 1) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return parsed;
    }

    private static int nonNegativeInt(String value, String name) {
        int parsed = parseInt(value, name);
        if (parsed < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        return parsed;
    }

    private static int parseInt(String value, String name) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be an integer", e);
        }
    }
}
