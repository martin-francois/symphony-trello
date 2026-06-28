package ch.fmartin.symphony.trello.workflow;

import java.nio.file.Path;
import java.util.Map;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@NullMarked
public record WorkflowDefinition(Path path, Map<String, Object> config, String promptTemplate) {
    public WorkflowDefinition(Path path, Map<String, Object> config, @Nullable String promptTemplate) {
        this.path = path;
        this.config = Map.copyOf(config);
        this.promptTemplate = promptTemplate == null ? "" : promptTemplate.trim();
    }
}
