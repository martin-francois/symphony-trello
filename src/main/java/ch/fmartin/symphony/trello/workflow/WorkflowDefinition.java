package ch.fmartin.symphony.trello.workflow;

import static com.google.common.base.Strings.nullToEmpty;

import java.nio.file.Path;
import java.util.Map;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@NullMarked
public record WorkflowDefinition(Path path, Map<String, Object> config, String promptTemplate) {
    public WorkflowDefinition(Path path, Map<String, Object> config, @Nullable String promptTemplate) {
        this.path = path;
        this.config = Map.copyOf(config);
        this.promptTemplate = nullToEmpty(promptTemplate).trim();
    }
}
