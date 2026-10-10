package ch.fmartin.symphony.trello.boardsession;

import static ch.fmartin.symphony.trello.TextCharacterMatchers.UNSAFE_SINGLE_LINE_CHARACTERS;

import ch.fmartin.symphony.trello.config.EffectiveConfig;
import ch.fmartin.symphony.trello.config.StateNames;
import ch.fmartin.symphony.trello.domain.Card;
import ch.fmartin.symphony.trello.tracker.CardLookupResult;
import ch.fmartin.symphony.trello.tracker.TrelloCardSelectors;
import ch.fmartin.symphony.trello.tracker.TrelloClient;
import ch.fmartin.symphony.trello.tracker.TrelloException;
import ch.fmartin.symphony.trello.tracker.TrelloMoveTargets;
import ch.fmartin.symphony.trello.tracker.TrelloToolRefusal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/// Board-scoped Trello tools for an interactive Codex session.
///
/// Every card operation first loads the card and rejects it unless it belongs to the selected
/// board. Every tool honors the selected workflow's `trello_tools` policy: a tool the policy does
/// not allow is neither advertised nor executed. Current-card tool names are not reused because a
/// board session has no current card.
@NullMarked
public final class BoardSessionTools {
    static final String BOARD_OVERVIEW = "trello_board_overview";
    static final String LIST_CARDS = "trello_list_cards";
    static final String GET_CARD = "trello_get_card";
    static final String CREATE_CARD = "trello_create_card";
    static final String UPDATE_CARD = "trello_update_card";
    static final String MOVE_CARD = "trello_move_card";
    static final String ADD_CARD_COMMENT = "trello_add_card_comment";
    static final String SET_CARD_CHECKLIST_ITEM = "trello_set_card_checklist_item";
    static final String ARCHIVE_CARD = "trello_archive_card";

    static final int DEFAULT_CARD_LIMIT = 50;
    static final int MAX_CARD_LIMIT = 200;
    /// Trello rejects card names and descriptions longer than this many characters.
    static final int TRELLO_TEXT_LIMIT = 16_384;
    private static final int TOOL_DESCRIPTION_NAME_LIMIT = 100;
    private static final String CARD_ARGUMENT_DESCRIPTION =
            "Card on the selected board: a Trello card id, short link, or https://trello.com/c/ URL.";

    private final ObjectMapper json;
    private final TrelloClient trello;
    private final EffectiveConfig config;
    private final BoardSessionContext context;
    private final String boardId;
    private final List<Tool> tools;

    /// `config` must carry the board id that Trello resolved for the selected workflow.
    public BoardSessionTools(
            ObjectMapper json, TrelloClient trello, EffectiveConfig config, BoardSessionContext context) {
        this.json = json;
        this.trello = trello;
        this.config = config;
        this.context = context;
        this.boardId = Objects.requireNonNull(
                config.tracker().resolvedBoardId(), "The selected board id must be resolved before tools run.");
        this.tools = allTools();
    }

    public List<ToolDefinition> definitions() {
        return tools.stream()
                .filter(tool -> refusal(tool.permission()).isEmpty())
                .map(Tool::definition)
                .toList();
    }

    public ToolResult call(String name, JsonNode arguments) {
        Optional<Tool> tool = tools.stream()
                .filter(candidate -> candidate.definition().name().equals(name))
                .findAny();
        if (tool.isEmpty()) {
            return failure("unsupported_tool", "Unsupported Symphony board-session tool: " + name);
        }
        Optional<TrelloToolRefusal> refused = refusal(tool.orElseThrow().permission());
        if (refused.isPresent()) {
            return failure(refused.orElseThrow().code(), refused.orElseThrow().message());
        }
        JsonNode safeArguments = arguments == null || arguments.isNull() ? json.createObjectNode() : arguments;
        try {
            return success(tool.orElseThrow().handler().handle(safeArguments));
        } catch (ToolFailure e) {
            return failure(e.code, e.getMessage());
        } catch (TrelloException e) {
            return failure(e.code(), e.getMessage());
        }
    }

    /// Returns the policy refusal for a permission the workflow does not grant, or empty when tools
    /// that need it may run. The same answer decides advertisement and execution.
    private Optional<TrelloToolRefusal> refusal(Permission permission) {
        EffectiveConfig.TrelloToolsConfig policy = config.trelloTools();
        if (!policy.enabled()) {
            return Optional.of(TrelloToolRefusal.TOOLS_DISABLED);
        }
        if (permission == Permission.READ) {
            return Optional.empty();
        }
        if (!policy.allowWrites()) {
            return Optional.of(TrelloToolRefusal.WRITES_DISABLED);
        }
        return switch (permission) {
            case COMMENT ->
                policy.allowComments() ? Optional.empty() : Optional.of(TrelloToolRefusal.COMMENTS_DISABLED);
            case CHECKLIST ->
                policy.allowChecklists() ? Optional.empty() : Optional.of(TrelloToolRefusal.CHECKLISTS_DISABLED);
            case MOVE ->
                TrelloMoveTargets.allowlistConfigured(config)
                        ? Optional.empty()
                        : Optional.of(TrelloToolRefusal.MOVE_ALLOWLIST_REQUIRED);
            case READ, WRITE, ARCHIVE -> Optional.empty();
        };
    }

    private Tool tool(
            String name, String description, ObjectNode inputSchema, Permission permission, ToolHandler handler) {
        return new Tool(
                new ToolDefinition(
                        name,
                        description,
                        inputSchema,
                        permission == Permission.READ,
                        permission == Permission.ARCHIVE),
                permission,
                handler);
    }

    private List<Tool> allTools() {
        String board = toolDescriptionName(context.boardName());
        return List.of(
                tool(
                        BOARD_OVERVIEW,
                        "Show the Trello board this session manages (" + board
                                + "): its open lists, the workflow list roles, the default list for new cards, "
                                + "and which Trello operations the workflow allows. Call it before the first Trello "
                                + "action in this session.",
                        objectSchema(Map.of(), List.of()),
                        Permission.READ,
                        arguments -> boardOverview()),
                tool(
                        LIST_CARDS,
                        "List or search open cards on " + board
                                + ". Filter by list_name, by query text found in the card title or description, or both.",
                        objectSchema(
                                Map.of(
                                        "list_name",
                                        stringSchema("Only return cards in this open list."),
                                        "query",
                                        stringSchema(
                                                "Only return cards whose title or description contains this text."),
                                        "limit",
                                        integerSchema(
                                                "Maximum number of cards to return. Defaults to " + DEFAULT_CARD_LIMIT
                                                        + ".",
                                                MAX_CARD_LIMIT)),
                                List.of()),
                        Permission.READ,
                        this::listCards),
                tool(
                        GET_CARD,
                        "Show one card on " + board
                                + " with its description, list, labels, checklists, URL attachments, and recent comments.",
                        objectSchema(Map.of("card", stringSchema(CARD_ARGUMENT_DESCRIPTION)), List.of("card")),
                        Permission.READ,
                        arguments -> cardDetails(scopedCard(arguments))),
                tool(
                        CREATE_CARD,
                        "Create a card on " + board
                                + ". Pass list_name unless the board overview names a default list for new cards; "
                                + "if neither applies, ask the user which list to use.",
                        objectSchema(
                                Map.of(
                                        "title", stringSchema("Card title on one line."),
                                        "description", stringSchema("Optional Markdown card description."),
                                        "list_name", stringSchema("Open list that receives the new card.")),
                                List.of("title")),
                        Permission.WRITE,
                        this::createCard),
                tool(
                        UPDATE_CARD,
                        "Change the title, the description, or both of one card on " + board + ".",
                        objectSchema(
                                Map.of(
                                        "card", stringSchema(CARD_ARGUMENT_DESCRIPTION),
                                        "title", stringSchema("New card title on one line."),
                                        "description", stringSchema("New Markdown card description.")),
                                List.of("card")),
                        Permission.WRITE,
                        this::updateCard),
                tool(
                        MOVE_CARD,
                        "Move one card on " + board
                                + " to a list that the workflow's trello_tools move allowlist permits.",
                        objectSchema(
                                Map.of(
                                        "card", stringSchema(CARD_ARGUMENT_DESCRIPTION),
                                        "list_name", stringSchema("Allowed destination list name."),
                                        "list_id", stringSchema("Allowed destination Trello list id.")),
                                List.of("card")),
                        Permission.MOVE,
                        this::moveCard),
                tool(
                        ADD_CARD_COMMENT,
                        "Add a comment to one card on " + board + ".",
                        objectSchema(
                                Map.of(
                                        "card", stringSchema(CARD_ARGUMENT_DESCRIPTION),
                                        "text", stringSchema("Markdown comment text.")),
                                List.of("card", "text")),
                        Permission.COMMENT,
                        this::addComment),
                tool(
                        SET_CARD_CHECKLIST_ITEM,
                        "Create or update one checklist item on one card on " + board
                                + ". Creates the checklist when it does not exist.",
                        objectSchema(
                                Map.of(
                                        "card", stringSchema(CARD_ARGUMENT_DESCRIPTION),
                                        "checklist_name", stringSchema("Exact checklist name on the card."),
                                        "item_name", stringSchema("Exact checklist item text."),
                                        "complete", booleanSchema("Whether the item is complete.")),
                                List.of("card", "checklist_name", "item_name", "complete")),
                        Permission.CHECKLIST,
                        this::setChecklistItem),
                tool(
                        ARCHIVE_CARD,
                        "Archive one card on " + board
                                + ". Trello keeps archived cards and can restore them. Before calling this tool, show "
                                + "the user the card title and wait for explicit confirmation. confirm_title must "
                                + "equal the current card title exactly.",
                        objectSchema(
                                Map.of(
                                        "card",
                                        stringSchema(CARD_ARGUMENT_DESCRIPTION),
                                        "confirm_title",
                                        stringSchema("The current card title, exactly as the user confirmed it.")),
                                List.of("card", "confirm_title")),
                        Permission.ARCHIVE,
                        this::archiveCard));
    }

    private ObjectNode boardOverview() {
        List<TrelloClient.BoardList> openLists = openLists();
        ObjectNode overview = json.createObjectNode();
        ObjectNode board = overview.putObject("board");
        board.put("name", context.boardName());
        board.put("short_link", context.boardShortLink());
        board.put("workflow_file", context.workflowFileName());
        ArrayNode listNodes = overview.putArray("lists");
        for (TrelloClient.BoardList list : openLists) {
            ObjectNode listNode = listNodes.addObject();
            listNode.put("name", list.name());
            listNode.put("id", list.id());
            listNode.set("roles", json.valueToTree(roles(list.name())));
        }
        overview.set("roles", rolesNode());
        context.listRoles()
                .defaultNewCardList()
                .ifPresentOrElse(
                        list -> overview.put("default_new_card_list", list),
                        () -> overview.putNull("default_new_card_list"));
        overview.set("permissions", permissions(openLists));
        overview.set(
                "available_tools",
                json.valueToTree(
                        definitions().stream().map(ToolDefinition::name).toList()));
        return overview;
    }

    private List<String> roles(String listName) {
        BoardListRoles roles = context.listRoles();
        List<String> matches = new ArrayList<>();
        addRoleIf(matches, "active", roles.activeLists().stream().anyMatch(sameList(listName)));
        addRoleIf(matches, "queue", roles.queueLists().stream().anyMatch(sameList(listName)));
        addRoleIf(
                matches,
                "in_progress",
                roles.inProgressList().filter(sameList(listName)).isPresent());
        addRoleIf(
                matches, "review", roles.reviewList().filter(sameList(listName)).isPresent());
        addRoleIf(
                matches,
                "blocked",
                roles.blockedList().filter(sameList(listName)).isPresent());
        addRoleIf(matches, "terminal", roles.terminalLists().stream().anyMatch(sameList(listName)));
        return List.copyOf(matches);
    }

    private static void addRoleIf(List<String> roles, String role, boolean matches) {
        if (matches) {
            roles.add(role);
        }
    }

    private static Predicate<String> sameList(String listName) {
        String normalized = StateNames.normalize(listName);
        return candidate -> StateNames.normalize(candidate).equals(normalized);
    }

    private ObjectNode rolesNode() {
        BoardListRoles roles = context.listRoles();
        ObjectNode node = json.createObjectNode();
        node.set("active", json.valueToTree(roles.activeLists()));
        node.set("queue", json.valueToTree(roles.queueLists()));
        node.put("in_progress", roles.inProgressList().orElse(null));
        node.put("review", roles.reviewList().orElse(null));
        node.put("blocked", roles.blockedList().orElse(null));
        node.set("terminal", json.valueToTree(roles.terminalLists()));
        return node;
    }

    private ObjectNode permissions(List<TrelloClient.BoardList> openLists) {
        EffectiveConfig.TrelloToolsConfig policy = config.trelloTools();
        ObjectNode node = json.createObjectNode();
        node.put("read", true);
        node.put("writes", policy.allowWrites());
        node.put("create_and_update_cards", policy.allowWrites());
        node.put("comments", policy.allowWrites() && policy.allowComments());
        node.put("checklists", policy.allowWrites() && policy.allowChecklists());
        node.put("archive_with_title_confirmation", policy.allowWrites());
        node.put("delete", false);
        List<String> moveDestinations = policy.allowWrites() && TrelloMoveTargets.allowlistConfigured(config)
                ? openLists.stream()
                        .filter(list -> TrelloMoveTargets.resolve(config, openLists, list.id(), null)
                                        .list()
                                != null)
                        .map(TrelloClient.BoardList::name)
                        .toList()
                : List.of();
        node.set("move_destinations", json.valueToTree(moveDestinations));
        return node;
    }

    private ObjectNode listCards(JsonNode arguments) {
        Optional<String> listName = optionalText(arguments, "list_name");
        Optional<String> query = optionalText(arguments, "query").map(text -> text.toLowerCase(Locale.ROOT));
        int limit = optionalLimit(arguments);
        List<Card> matches = trello.fetchOpenBoardCards(config).stream()
                .filter(card -> listName.map(name -> sameList(name).test(Objects.toString(card.listName(), "")))
                        .orElse(true))
                .filter(card -> query.map(text -> containsText(card, text)).orElse(true))
                .toList();
        ObjectNode result = json.createObjectNode();
        result.put("total_matches", matches.size());
        result.put("truncated", matches.size() > limit);
        ArrayNode cards = result.putArray("cards");
        matches.stream().limit(limit).forEach(card -> cards.add(cardSummary(card)));
        return result;
    }

    private static boolean containsText(Card card, String lowerCaseQuery) {
        return Objects.toString(card.title(), "").toLowerCase(Locale.ROOT).contains(lowerCaseQuery)
                || Objects.toString(card.description(), "")
                        .toLowerCase(Locale.ROOT)
                        .contains(lowerCaseQuery);
    }

    private ObjectNode cardSummary(Card card) {
        ObjectNode node = json.createObjectNode();
        node.put("id", card.id());
        node.put("short_link", card.shortLink());
        node.put("url", card.url());
        node.put("title", card.title());
        node.put("list_name", card.listName());
        node.set("labels", json.valueToTree(card.labels()));
        node.put("due", instantText(card.dueAt()));
        node.put("last_activity", instantText(card.updatedAt()));
        return node;
    }

    private ObjectNode cardDetails(Card card) {
        ObjectNode node = cardSummary(card);
        node.put("description", card.description());
        node.put("closed", card.closed());
        if (card.dueComplete() != null) {
            node.put("due_complete", card.dueComplete());
        }
        ArrayNode checklists = node.putArray("checklists");
        for (Card.Checklist checklist : card.checklists()) {
            ObjectNode checklistNode = checklists.addObject();
            checklistNode.put("name", checklist.name());
            ArrayNode items = checklistNode.putArray("items");
            for (Card.ChecklistItem item : checklist.items()) {
                items.addObject().put("name", item.text()).put("complete", item.complete());
            }
        }
        ArrayNode attachments = node.putArray("attachments");
        for (Card.Attachment attachment : card.attachments()) {
            attachments.addObject().put("name", attachment.name()).put("url", attachment.url());
        }
        ArrayNode comments = node.putArray("recent_comments");
        for (Card.Comment comment : card.comments()) {
            comments.addObject()
                    .put("author", comment.author())
                    .put("date", instantText(comment.createdAt()))
                    .put("text", comment.text());
        }
        return node;
    }

    private ObjectNode createCard(JsonNode arguments) {
        String title = requiredSingleLine(arguments, "title");
        String description = optionalText(arguments, "description").orElse("");
        requireTrelloTextLimit("description", description);
        TrelloClient.BoardList list = optionalText(arguments, "list_name")
                .or(() -> context.listRoles().defaultNewCardList())
                .map(this::openListNamed)
                .orElseThrow(() -> new ToolFailure(
                        "list_name_required",
                        "The workflow has no single default list for new cards. Ask the user which list to use: "
                                + openListNames() + "."));
        Map<String, Object> created = trello.createCard(config, list.id(), title, description);
        ObjectNode result = json.createObjectNode();
        result.put("status", "card_created");
        result.put("card_id", Objects.toString(created.get("id"), null));
        result.put("short_link", Objects.toString(created.get("shortLink"), null));
        result.put("url", Objects.toString(created.get("url"), null));
        result.put("title", title);
        result.put("list_name", list.name());
        return result;
    }

    private TrelloClient.BoardList openListNamed(String listName) {
        List<TrelloClient.BoardList> matches = openLists().stream()
                .filter(list -> sameList(listName).test(list.name()))
                .limit(2)
                .toList();
        return switch (matches.size()) {
            case 0 ->
                throw new ToolFailure(
                        "list_not_found",
                        "No open list on the selected board is named " + listName + ". Open lists: " + openListNames()
                                + ".");
            case 1 -> matches.getFirst();
            default ->
                throw new ToolFailure(
                        "list_name_ambiguous",
                        "Several open lists are named " + listName
                                + ". Ask the user to rename one of them in Trello before creating the card.");
        };
    }

    private String openListNames() {
        return String.join(
                ", ", openLists().stream().map(TrelloClient.BoardList::name).toList());
    }

    private List<TrelloClient.BoardList> openLists() {
        return trello.fetchBoardLists(config).stream()
                .filter(list -> !list.closed())
                .toList();
    }

    private ObjectNode updateCard(JsonNode arguments) {
        Card card = scopedCard(arguments);
        Optional<String> title = optionalText(arguments, "title");
        title.ifPresent(value -> requireSingleLine("title", value));
        Optional<String> description = optionalText(arguments, "description");
        description.ifPresent(value -> requireTrelloTextLimit("description", value));
        if (title.isEmpty() && description.isEmpty()) {
            throw new ToolFailure("missing_update_fields", "Provide title, description, or both.");
        }
        // Keep the title first so request logs read in the same order as the tool arguments.
        Map<String, String> fields = new LinkedHashMap<>();
        title.ifPresent(value -> fields.put("name", value));
        description.ifPresent(value -> fields.put("desc", value));
        trello.updateCard(config, card.id(), fields);
        ObjectNode result = json.createObjectNode();
        result.put("status", "card_updated");
        result.put("card_id", card.id());
        result.set("updated_fields", json.valueToTree(List.copyOf(fields.keySet())));
        return result;
    }

    private ObjectNode moveCard(JsonNode arguments) {
        Card card = scopedCard(arguments);
        Optional<String> listId = optionalText(arguments, "list_id");
        Optional<String> listName = optionalText(arguments, "list_name");
        if (listId.isEmpty() && listName.isEmpty()) {
            throw new ToolFailure("missing_destination_list", "Provide list_name or list_id for the destination list.");
        }
        TrelloMoveTargets.MoveTarget target = TrelloMoveTargets.resolve(
                config, trello.fetchBoardLists(config), listId.orElse(null), listName.orElse(null));
        TrelloClient.BoardList list = target.list();
        if (list == null) {
            throw new ToolFailure("trello_move_not_allowed", Objects.toString(target.error(), "Move not allowed."));
        }
        trello.moveCardToList(config, card.id(), list.id());
        ObjectNode result = json.createObjectNode();
        result.put("status", "card_moved");
        result.put("card_id", card.id());
        result.put("list_id", list.id());
        result.put("list_name", list.name());
        return result;
    }

    private ObjectNode addComment(JsonNode arguments) {
        Card card = scopedCard(arguments);
        String text = requiredText(arguments, "text");
        requireTrelloTextLimit("text", text);
        trello.addComment(config, card.id(), text);
        ObjectNode result = json.createObjectNode();
        result.put("status", "comment_added");
        result.put("card_id", card.id());
        return result;
    }

    private ObjectNode setChecklistItem(JsonNode arguments) {
        Card card = scopedCard(arguments);
        String checklistName = requiredSingleLine(arguments, "checklist_name");
        String itemName = requiredSingleLine(arguments, "item_name");
        boolean complete = requiredBoolean(arguments, "complete");
        TrelloClient.ChecklistItemWrite write =
                trello.upsertChecklistItem(config, card.id(), checklistName, itemName, complete);
        ObjectNode result = json.createObjectNode();
        result.put("status", "checklist_item_" + write.status());
        result.put("card_id", card.id());
        result.put("checklist_id", write.checklistId());
        result.put("check_item_id", write.checkItemId());
        result.put("complete", write.complete());
        return result;
    }

    private ObjectNode archiveCard(JsonNode arguments) {
        Card card = scopedCard(arguments);
        String confirmation = requiredText(arguments, "confirm_title");
        if (card.closed()) {
            throw new ToolFailure("card_already_archived", "The card is already archived.");
        }
        if (!card.title().equals(confirmation)) {
            throw new ToolFailure(
                    "archive_confirmation_mismatch",
                    "confirm_title does not match the current card title. Show the user the current title and ask "
                            + "again before archiving.");
        }
        trello.archiveCard(config, card.id());
        ObjectNode result = json.createObjectNode();
        result.put("status", "card_archived");
        result.put("card_id", card.id());
        result.put("title", card.title());
        result.put("restore", "Trello keeps archived cards. Restore it from the board menu under Archived items.");
        return result;
    }

    private Card scopedCard(JsonNode arguments) {
        String reference = TrelloCardSelectors.lookupId(requiredText(arguments, "card"))
                .orElseThrow(() -> new ToolFailure(
                        "invalid_card", "card must be a Trello card id, short link, or https://trello.com/c/ URL."));
        CardLookupResult lookup = trello.fetchCardDetails(config, reference);
        return switch (lookup) {
            case CardLookupResult.Found found when boardId.equals(found.card().boardId()) -> found.card();
            case CardLookupResult.Found ignored ->
                throw new ToolFailure(
                        "card_not_on_selected_board",
                        "The card belongs to another Trello board. This session only manages cards on the selected board.");
            case CardLookupResult.Missing ignored ->
                throw new ToolFailure("card_not_found", "No Trello card matches the card argument.");
            case CardLookupResult.Failed failed -> throw new ToolFailure(failed.code(), failed.message());
        };
    }

    private static Optional<String> optionalText(JsonNode arguments, String key) {
        JsonNode value = arguments.path(key);
        if (value.isMissingNode() || value.isNull()) {
            return Optional.empty();
        }
        if (!value.isTextual()) {
            throw new ToolFailure("invalid_arguments", key + " must be a string.");
        }
        return Optional.of(value.textValue()).filter(text -> !text.isBlank());
    }

    private static String requiredText(JsonNode arguments, String key) {
        return optionalText(arguments, key)
                .orElseThrow(() -> new ToolFailure("invalid_arguments", "Missing required argument: " + key + "."));
    }

    private static String requiredSingleLine(JsonNode arguments, String key) {
        String value = requiredText(arguments, key).strip();
        requireSingleLine(key, value);
        return value;
    }

    private static void requireSingleLine(String key, String value) {
        if (UNSAFE_SINGLE_LINE_CHARACTERS.matchesAnyOf(value)) {
            throw new ToolFailure("invalid_arguments", key + " must be one line without control characters.");
        }
        requireTrelloTextLimit(key, value);
    }

    private static void requireTrelloTextLimit(String key, String value) {
        if (value.length() > TRELLO_TEXT_LIMIT) {
            throw new ToolFailure(
                    "invalid_arguments", key + " must be at most " + TRELLO_TEXT_LIMIT + " characters long.");
        }
    }

    private static boolean requiredBoolean(JsonNode arguments, String key) {
        JsonNode value = arguments.path(key);
        if (!value.isBoolean()) {
            throw new ToolFailure("invalid_arguments", key + " must be true or false.");
        }
        return value.booleanValue();
    }

    private static int optionalLimit(JsonNode arguments) {
        JsonNode value = arguments.path("limit");
        if (value.isMissingNode() || value.isNull()) {
            return DEFAULT_CARD_LIMIT;
        }
        if (!value.canConvertToInt() || !value.isIntegralNumber() || value.intValue() < 1) {
            throw new ToolFailure("invalid_arguments", "limit must be a positive whole number.");
        }
        return Math.min(value.intValue(), MAX_CARD_LIMIT);
    }

    private static @Nullable String instantText(@Nullable Instant instant) {
        return instant == null ? null : instant.toString();
    }

    private static String toolDescriptionName(String boardName) {
        String singleLine =
                UNSAFE_SINGLE_LINE_CHARACTERS.replaceFrom(boardName, ' ').strip();
        String bounded = singleLine.length() > TOOL_DESCRIPTION_NAME_LIMIT
                ? singleLine.substring(0, TOOL_DESCRIPTION_NAME_LIMIT) + "..."
                : singleLine;
        return "the selected Trello board \"" + bounded + "\"";
    }

    private ObjectNode objectSchema(Map<String, ObjectNode> properties, List<String> required) {
        ObjectNode schema = json.createObjectNode();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        ObjectNode propertyNodes = schema.putObject("properties");
        properties.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> propertyNodes.set(entry.getKey(), entry.getValue()));
        schema.set("required", json.valueToTree(required));
        return schema;
    }

    private ObjectNode stringSchema(String description) {
        ObjectNode schema = json.createObjectNode();
        schema.put("type", "string");
        schema.put("minLength", 1);
        schema.put("description", description);
        return schema;
    }

    private ObjectNode integerSchema(String description, int maximum) {
        ObjectNode schema = json.createObjectNode();
        schema.put("type", "integer");
        schema.put("minimum", 1);
        schema.put("maximum", maximum);
        schema.put("description", description);
        return schema;
    }

    private ObjectNode booleanSchema(String description) {
        ObjectNode schema = json.createObjectNode();
        schema.put("type", "boolean");
        schema.put("description", description);
        return schema;
    }

    private ToolResult success(ObjectNode payload) {
        return new ToolResult(false, payload);
    }

    private ToolResult failure(String code, @Nullable String message) {
        ObjectNode payload = json.createObjectNode();
        payload.put("error", code);
        payload.put("message", message == null || message.isBlank() ? code : message);
        return new ToolResult(true, payload);
    }

    /// What a tool needs from the workflow's `trello_tools` policy. Archiving is a write that the MCP
    /// server also reports as destructive.
    private enum Permission {
        READ,
        WRITE,
        COMMENT,
        CHECKLIST,
        MOVE,
        ARCHIVE
    }

    @FunctionalInterface
    private interface ToolHandler {
        ObjectNode handle(JsonNode arguments);
    }

    private record Tool(ToolDefinition definition, Permission permission, ToolHandler handler) {}

    /// A tool as advertised to Codex, with the MCP read-only and destructive hints.
    public record ToolDefinition(
            String name, String description, ObjectNode inputSchema, boolean readOnly, boolean destructive) {
        public ToolDefinition {
            inputSchema = inputSchema.deepCopy();
        }

        @Override
        public ObjectNode inputSchema() {
            return inputSchema.deepCopy();
        }
    }

    public record ToolResult(boolean error, ObjectNode payload) {
        public ToolResult {
            payload = payload.deepCopy();
        }

        @Override
        public ObjectNode payload() {
            return payload.deepCopy();
        }
    }

    private static final class ToolFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private final String code;

        private ToolFailure(String code, String message) {
            super(message, null, false, false);
            this.code = code;
        }
    }
}
