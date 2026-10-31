package ch.fmartin.symphony.trello.workflow;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.nio.file.Path;

/// Wraps front matter in the smallest workflow file the loader accepts, for tests that only vary the
/// YAML between the `---` lines.
final class FrontMatterWorkflows {
    static final Path WORKFLOW = Path.of("WORKFLOW.md");

    private FrontMatterWorkflows() {}

    static byte[] workflow(String frontMatter) {
        return ("---\n" + frontMatter + "---\nBody\n").getBytes(UTF_8);
    }
}
