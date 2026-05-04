package com.openai.symphony.workflow;

import java.nio.file.Path;
import java.util.Map;

public record WorkflowDefinition(Path path, Map<String, Object> config, String promptTemplate) {
    public WorkflowDefinition {
        config = Map.copyOf(config);
        promptTemplate = promptTemplate == null ? "" : promptTemplate.trim();
    }
}
