package ch.fmartin.symphony.trello.fuzz;

import ch.fmartin.symphony.trello.workflow.WorkflowException;
import ch.fmartin.symphony.trello.workflow.WorkflowLoader;
import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class WorkflowLoaderFuzzer {
    private static final WorkflowLoader LOADER = new WorkflowLoader();
    private static final int MAX_MARKDOWN_BYTES = 8 * 1024;

    private WorkflowLoaderFuzzer() {}

    public static void fuzzerTestOneInput(FuzzedDataProvider data) throws IOException {
        byte[] markdown = data.consumeBytes(MAX_MARKDOWN_BYTES);
        Path workflow = Files.createTempFile("symphony-workflow-fuzz-", ".md");
        try {
            Files.write(workflow, markdown);
            load(workflow);
        } finally {
            Files.deleteIfExists(workflow);
        }
    }

    private static void load(Path workflow) {
        try {
            LOADER.load(workflow);
        } catch (WorkflowException expected) {
            // Invalid front matter is ordinary fuzzer input; crashes are anything outside the
            // loader's expected parse-failure contract.
        }
    }
}
