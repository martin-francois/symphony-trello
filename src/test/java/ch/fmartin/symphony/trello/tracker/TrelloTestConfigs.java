package ch.fmartin.symphony.trello.tracker;

import ch.fmartin.symphony.trello.config.ConfigResolver;
import ch.fmartin.symphony.trello.config.EffectiveConfig;
import ch.fmartin.symphony.trello.workflow.WorkflowDefinition;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/// Builds a resolved Trello tracker config that points at a fake Trello server, so client tests
/// share the credential and endpoint shape and list only the settings their scenario needs.
final class TrelloTestConfigs {
    private TrelloTestConfigs() {}

    static EffectiveConfig trackerConfig(
            Path workflowPath, String endpoint, String boardId, Map<String, Object> trackerSettings) {
        Map<String, Object> tracker = new HashMap<>(Map.of(
                "kind", "trello", "endpoint", endpoint, "api_key", "key", "api_token", "token", "board_id", boardId));
        tracker.putAll(trackerSettings);
        return new ConfigResolver().resolve(new WorkflowDefinition(workflowPath, Map.of("tracker", tracker), ""));
    }
}
