package ch.fmartin.symphony.trello.fuzz;

import ch.fmartin.symphony.trello.workflow.WorkflowLoader;
import com.code_intelligence.jazzer.api.FuzzedDataProvider;

public final class WorkflowLoaderFuzzer {
    private static final WorkflowLoader LOADER = new WorkflowLoader();

    private WorkflowLoaderFuzzer() {}

    public static void fuzzerTestOneInput(FuzzedDataProvider data) {
        byte[] markdown = data.consumeBytes(WorkflowLoaderInvariants.MAX_MARKDOWN_BYTES);
        WorkflowLoaderInvariants.assertWorkflowLoaderProperties(
                LOADER, markdown, WorkflowLoaderInvariants.parse(LOADER, markdown));
    }
}
