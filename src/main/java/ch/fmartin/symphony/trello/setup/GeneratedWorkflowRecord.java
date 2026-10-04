package ch.fmartin.symphony.trello.setup;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.nio.file.Path;
import java.util.Objects;
import org.jspecify.annotations.NullMarked;

/// One generated workflow body with its version provenance. `body` is the exact text that Symphony
/// `symphonyVersion` wrote after the closing front matter line, rendered from `inputs`.
@JsonIgnoreProperties(ignoreUnknown = true)
@NullMarked
record GeneratedWorkflowRecord(
        Path workflowPath, String symphonyVersion, GeneratedWorkflowBodyInputs inputs, String body) {
    GeneratedWorkflowRecord {
        workflowPath = Objects.requireNonNull(workflowPath, "workflowPath")
                .toAbsolutePath()
                .normalize();
        Objects.requireNonNull(symphonyVersion, "symphonyVersion");
        Objects.requireNonNull(inputs, "inputs");
        Objects.requireNonNull(body, "body");
    }

    /// The record for a body that the running Symphony version renders now.
    static GeneratedWorkflowRecord current(
            Path workflowPath, String symphonyVersion, GeneratedWorkflowBodyInputs inputs) {
        return new GeneratedWorkflowRecord(
                workflowPath, symphonyVersion, inputs, TrelloBoardSetup.generatedWorkflowBody(inputs));
    }
}
