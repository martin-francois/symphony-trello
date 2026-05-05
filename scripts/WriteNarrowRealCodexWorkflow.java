import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes a strict real-Codex live-E2E workflow from an existing generated workflow.
 *
 * <p>The generated board workflows are intentionally useful for real engineering work, so they ask
 * Codex to inspect and verify. This helper preserves the generated Trello/front-matter contract but
 * replaces the prompt body with a narrow handoff-only prompt for deterministic real-Codex protocol
 * checks.
 */
public class WriteNarrowRealCodexWorkflow {
    public static void main(String[] args) throws IOException {
        if (args.length != 4) {
            System.err.println(
                    "Usage: java --source 25 scripts/WriteNarrowRealCodexWorkflow.java <source-workflow> <target-workflow> <label> <run-id>");
            System.exit(2);
        }

        Path source = Path.of(args[0]);
        Path target = Path.of(args[1]);
        String label = args[2];
        String runId = args[3];

        String workflow = Files.readString(source);
        String frontMatter = frontMatter(workflow);

        Files.writeString(target, frontMatter + prompt(label, runId, reviewColumn(frontMatter)));
    }

    private static String frontMatter(String workflow) {
        int first = workflow.indexOf("---");
        if (first != 0) {
            throw new IllegalArgumentException("Workflow must start with YAML front matter");
        }
        int second = workflow.indexOf("---", first + 3);
        if (second < 0) {
            throw new IllegalArgumentException("Workflow front matter is not closed");
        }
        return workflow.substring(0, second + 3) + System.lineSeparator();
    }

    private static String prompt(String label, String runId, String reviewColumn) {
        return """
                # Trello Card

                This is a strict live E2E test for Symphony with real Codex and real Trello.
                Do not inspect the repository, do not edit files, do not run shell commands, and do not create a pull request.
                Use the available Symphony for Trello tools only:

                1. Call trello_add_comment with text: "%s complete for %s".
                2. Call trello_move_current_card with list_name "%s".
                3. Finish immediately after the tool calls.

                Current card: {{ card.identifier }} {{ card.title }}
                """
                .formatted(label, runId, reviewColumn);
    }

    private static String reviewColumn(String frontMatter) {
        List<String> allowedMoveColumns = allowedMoveColumns(frontMatter);
        if (allowedMoveColumns.contains("Human Review")) {
            return "Human Review";
        }
        if (allowedMoveColumns.contains("Review")) {
            return "Review";
        }
        throw new IllegalArgumentException("Workflow must allow either Human Review or Review as a Trello move column");
    }

    private static List<String> allowedMoveColumns(String frontMatter) {
        List<String> values = new ArrayList<>();
        boolean inAllowedMoveColumns = false;
        for (String line : frontMatter.lines().toList()) {
            String stripped = line.strip();
            if (stripped.equals("allowed_move_list_names:")) {
                inAllowedMoveColumns = true;
                continue;
            }
            if (!inAllowedMoveColumns) {
                continue;
            }
            if (line.startsWith("  ") && !line.startsWith("    ")) {
                break;
            }
            if (stripped.startsWith("- ")) {
                values.add(unquoteYamlScalar(stripped.substring(2).strip()));
            }
        }
        return List.copyOf(values);
    }

    private static String unquoteYamlScalar(String value) {
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1).replace("\\\"", "\"").replace("\\\\", "\\");
        }
        return value;
    }
}
