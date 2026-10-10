package ch.fmartin.symphony.trello.boardsession;

import ch.fmartin.symphony.trello.config.ConfigResolver;
import ch.fmartin.symphony.trello.config.EffectiveConfig;
import ch.fmartin.symphony.trello.testsupport.FakeTrelloBoard;
import ch.fmartin.symphony.trello.tracker.TrelloClient;
import ch.fmartin.symphony.trello.workflow.WorkflowDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/// Board-session tools wired to a [FakeTrelloBoard] with synthetic credentials.
final class BoardSessionFixtures {
    static final String API_KEY_SENTINEL = "sentinel-key-603";
    static final String API_TOKEN_SENTINEL = "sentinel-token-603";
    static final String BOARD_NAME = "Symphony Work Queue";

    private BoardSessionFixtures() {}

    static BoardSessionTools tools(FakeTrelloBoard trello, Map<String, Object> policy, BoardListRoles roles) {
        var json = new ObjectMapper();
        return new BoardSessionTools(json, new TrelloClient(json), config(trello, policy), context(roles));
    }

    static BoardSessionContext context(BoardListRoles roles) {
        return new BoardSessionContext(BOARD_NAME, FakeTrelloBoard.BOARD_SHORT_LINK, "WORKFLOW.synthetic.md", roles);
    }

    static EffectiveConfig config(FakeTrelloBoard trello, Map<String, Object> policy) {
        return new ConfigResolver()
                .resolve(new WorkflowDefinition(
                        Path.of("WORKFLOW.synthetic.md").toAbsolutePath(),
                        Map.of(
                                "tracker",
                                Map.of(
                                        "kind",
                                        "trello",
                                        "endpoint",
                                        trello.endpoint(),
                                        "api_key",
                                        API_KEY_SENTINEL,
                                        "api_token",
                                        API_TOKEN_SENTINEL,
                                        "board_id",
                                        FakeTrelloBoard.BOARD_ID),
                                "trello_tools",
                                policy),
                        "Body"))
                .withResolvedBoardId(FakeTrelloBoard.BOARD_ID);
    }

    static BoardListRoles defaultRoles() {
        return BoardListRoles.of(
                List.of("Ready for Codex", "In Progress"),
                List.of("Ready for Codex"),
                Optional.of("In Progress"),
                Optional.of("Human Review"),
                Optional.of("Blocked"),
                List.of("Done"));
    }

    static Map<String, Object> allowAllPolicy() {
        // Insertion order keeps parameterized display names stable.
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("enabled", true);
        values.put("allow_writes", true);
        values.put("allow_comments", true);
        values.put("allow_checklists", true);
        values.put("allowed_move_list_names", List.of("In Progress", "Human Review", "Blocked", "Done"));
        return values;
    }
}
