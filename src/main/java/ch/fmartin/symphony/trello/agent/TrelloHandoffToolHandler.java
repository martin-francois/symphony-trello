package ch.fmartin.symphony.trello.agent;

import ch.fmartin.symphony.trello.config.EffectiveConfig;
import ch.fmartin.symphony.trello.config.StateNames;
import ch.fmartin.symphony.trello.domain.Card;
import ch.fmartin.symphony.trello.tracker.TrelloClient;
import ch.fmartin.symphony.trello.tracker.TrelloException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@ApplicationScoped
public class TrelloHandoffToolHandler {
    static final String ADD_COMMENT = "trello_add_comment";
    static final String MOVE_CURRENT_CARD = "trello_move_current_card";

    private final ObjectMapper json;
    private final TrelloClient trello;

    public TrelloHandoffToolHandler(ObjectMapper json, TrelloClient trello) {
        this.json = json;
        this.trello = trello;
    }

    public boolean shouldEnableExperimentalApi(EffectiveConfig config) {
        return config.trelloTools().enabled();
    }

    public ArrayNode toolSpecs(EffectiveConfig config) {
        ArrayNode tools = json.createArrayNode();
        if (!writesEnabled(config)) {
            return tools;
        }
        if (config.trelloTools().allowComments()) {
            tools.add(tool(
                    ADD_COMMENT,
                    "Add a handoff comment to the current Trello card. The current card is fixed by Symphony; do not include a card id.",
                    objectSchema(
                            Map.of(
                                    "text",
                                    stringSchema(
                                            "Concise human-readable comment text to add to the current Trello card.")),
                            List.of("text"))));
        }
        if (moveAllowlistConfigured(config)) {
            tools.add(tool(
                    MOVE_CURRENT_CARD,
                    "Move the current Trello card to one configured board-local handoff column. Use list_name unless the workflow explicitly gives a Trello list_id.",
                    objectSchema(
                            Map.of(
                                    "list_name",
                                    stringSchema("Allowed destination column name, for example Review."),
                                    "list_id",
                                    stringSchema("Allowed destination Trello list id for a board column.")),
                            List.of())));
        }
        return tools;
    }

    public ObjectNode handle(EffectiveConfig config, Card card, JsonNode params) {
        String tool = params.path("tool").asText("");
        if (!ADD_COMMENT.equals(tool) && !MOVE_CURRENT_CARD.equals(tool)) {
            return failure("unsupported_tool", "Unsupported Trello handoff tool: " + tool);
        }
        if (!config.trelloTools().enabled()) {
            return failure("trello_tools_disabled", "Trello handoff tools are disabled by trello_tools.enabled.");
        }
        if (!config.trelloTools().allowWrites()) {
            return failure("trello_writes_disabled", "Trello writes are disabled by trello_tools.allow_writes.");
        }
        if (blank(card.id())) {
            return failure("missing_card_context", "The active worker session has no Trello card id.");
        }

        try {
            return switch (tool) {
                case ADD_COMMENT -> addComment(config, card, params.path("arguments"));
                case MOVE_CURRENT_CARD -> moveCurrentCard(config, card, params.path("arguments"));
                default -> throw new IllegalStateException("unreachable");
            };
        } catch (TrelloException e) {
            return failure(e.code(), e.getMessage());
        } catch (RuntimeException e) {
            return failure("trello_tool_failed", e.getMessage());
        }
    }

    private ObjectNode addComment(EffectiveConfig config, Card card, JsonNode arguments) {
        if (!config.trelloTools().allowComments()) {
            return failure("trello_comments_disabled", "Trello comments are disabled by trello_tools.allow_comments.");
        }
        String text = requiredText(arguments, "text");
        trello.addComment(config, card.id(), text);
        return success(Map.of("status", "comment_added", "card_id", card.id()));
    }

    private ObjectNode moveCurrentCard(EffectiveConfig config, Card card, JsonNode arguments) {
        if (!moveAllowlistConfigured(config)) {
            return failure(
                    "trello_move_allowlist_required",
                    "Trello card moves require trello_tools.allowed_move_list_ids or allowed_move_list_names.");
        }
        String listId = text(arguments, "list_id");
        String listName = text(arguments, "list_name");
        if (blank(listId) && blank(listName)) {
            return failure("missing_destination_list", "Provide list_name or list_id for the destination column.");
        }

        BoardListMatch target = resolveAllowedTarget(config, listId, listName)
                .orElseGet(
                        () -> new BoardListMatch(null, "No configured open destination column matches the request."));
        if (target.list() == null) {
            return failure("trello_move_not_allowed", target.error());
        }

        trello.moveCardToList(config, card.id(), target.list().id());
        return success(Map.of(
                "status",
                "card_moved",
                "card_id",
                card.id(),
                "list_id",
                target.list().id(),
                "list_name",
                target.list().name()));
    }

    private Optional<BoardListMatch> resolveAllowedTarget(EffectiveConfig config, String listId, String listName) {
        List<TrelloClient.BoardList> lists = trello.fetchBoardLists(config);
        List<TrelloClient.BoardList> openLists =
                lists.stream().filter(list -> !list.closed()).toList();
        Optional<TrelloClient.BoardList> target = !blank(listId)
                ? openLists.stream().filter(list -> list.id().equals(listId)).findFirst()
                : openLists.stream()
                        .filter(list -> StateNames.normalize(list.name()).equals(StateNames.normalize(listName)))
                        .findFirst();

        if (target.isEmpty()) {
            return Optional.of(new BoardListMatch(null, "Destination column is not open on the configured board."));
        }
        TrelloClient.BoardList list = target.get();
        if (allowedById(config, list) || allowedByName(config, list)) {
            return Optional.of(new BoardListMatch(list, null));
        }
        return Optional.of(new BoardListMatch(
                null, "Destination column is not included in the configured Trello move allowlist."));
    }

    private boolean allowedById(EffectiveConfig config, TrelloClient.BoardList list) {
        return config.trelloTools().allowedMoveListIds().contains(list.id());
    }

    private boolean allowedByName(EffectiveConfig config, TrelloClient.BoardList list) {
        String normalized = StateNames.normalize(list.name());
        return config.trelloTools().allowedMoveListNames().contains(normalized);
    }

    private boolean writesEnabled(EffectiveConfig config) {
        return config.trelloTools().enabled() && config.trelloTools().allowWrites();
    }

    private boolean moveAllowlistConfigured(EffectiveConfig config) {
        return !config.trelloTools().allowedMoveListIds().isEmpty()
                || !config.trelloTools().allowedMoveListNames().isEmpty();
    }

    private ObjectNode tool(String name, String description, ObjectNode inputSchema) {
        return object("namespace", "symphony", "name", name, "description", description, "inputSchema", inputSchema);
    }

    private ObjectNode objectSchema(Map<String, ObjectNode> properties, List<String> required) {
        ObjectNode schema = object("type", "object", "additionalProperties", false);
        ObjectNode propertySchema = json.createObjectNode();
        properties.forEach(propertySchema::set);
        schema.set("properties", propertySchema);
        schema.set("required", json.valueToTree(required));
        return schema;
    }

    private ObjectNode stringSchema(String description) {
        return object("type", "string", "minLength", 1, "description", description);
    }

    private ObjectNode success(Map<String, String> payload) {
        ObjectNode result = object("success", true);
        result.set("contentItems", json.createArrayNode().add(inputText(toJson(payload))));
        return result;
    }

    private ObjectNode failure(String code, String message) {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("error", code);
        payload.put("message", blank(message) ? code : message);
        ObjectNode result = object("success", false);
        result.set("contentItems", json.createArrayNode().add(inputText(toJson(payload))));
        return result;
    }

    private ObjectNode inputText(String text) {
        return object("type", "inputText", "text", text);
    }

    private String toJson(Map<String, String> payload) {
        try {
            return json.writeValueAsString(payload);
        } catch (Exception e) {
            return payload.toString();
        }
    }

    private ObjectNode object(Object... keyValues) {
        ObjectNode node = json.createObjectNode();
        for (int i = 0; i < keyValues.length; i += 2) {
            node.set(keyValues[i].toString(), json.valueToTree(keyValues[i + 1]));
        }
        return node;
    }

    private static String requiredText(JsonNode node, String key) {
        String value = text(node, key);
        if (blank(value)) {
            throw new IllegalArgumentException("Missing required argument: " + key);
        }
        return value;
    }

    private static String text(JsonNode node, String key) {
        JsonNode value = node.path(key);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private record BoardListMatch(TrelloClient.BoardList list, String error) {}
}
