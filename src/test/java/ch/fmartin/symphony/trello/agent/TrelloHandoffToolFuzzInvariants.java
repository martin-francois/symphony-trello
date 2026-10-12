package ch.fmartin.symphony.trello.agent;

import ch.fmartin.symphony.trello.TestCards;
import ch.fmartin.symphony.trello.config.ConfigResolver;
import ch.fmartin.symphony.trello.config.EffectiveConfig;
import ch.fmartin.symphony.trello.config.StateNames;
import ch.fmartin.symphony.trello.tracker.InMemoryTrelloBoard;
import ch.fmartin.symphony.trello.tracker.InMemoryTrelloBoard.Write;
import ch.fmartin.symphony.trello.tracker.InMemoryTrelloBoard.WriteKind;
import ch.fmartin.symphony.trello.tracker.TrelloClient;
import ch.fmartin.symphony.trello.workflow.WorkflowDefinition;
import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/// Runs one fuzzed Trello handoff tool call against an [InMemoryTrelloBoard] and checks that the call
/// writes only what the chosen `trello_tools` configuration allows. The standalone
/// `TrelloHandoffToolFuzzer` and the JUnit `TrelloHandoffToolFuzzTest` share this class, so OSS-Fuzz runs
/// and Maven regression runs check the same properties.
///
/// [FuzzedDataProvider] chooses the configuration, the board lists, the current card's comments and
/// checklists, the tool, its arguments, and optionally one request that Trello answers with an error.
/// Free text comes from the start of the input and every choice from the end.
public final class TrelloHandoffToolFuzzInvariants {
    static final String CURRENT_CARD_ID = "card-current";
    private static final String OTHER_CARD_ID = "card-other";
    private static final String MISSING_CARD_ID = "card-missing";
    private static final String BOARD_ID = "board-1";
    private static final String CURRENT_SHORT_LINK = "abc123";
    private static final String MISSING_LIST_ID = "list-missing";
    private static final int LIST_COUNT = 4;
    private static final int MAX_COMMENTS = 5;
    private static final int MAX_CHECKLISTS = 2;
    private static final int MAX_CHECK_ITEMS = 2;
    private static final int MAX_TEXT_LENGTH = 160;
    private static final int MAX_NAME_LENGTH = 24;
    // The request ordinals that a fault can hit. A tool call sends at most a handful of requests, and
    // larger choices leave the call without a fault.
    private static final int MAX_FAULT_ORDINAL = 8;
    private static final int NO_FAULT_CHOICES = 4;
    private static final List<Integer> FAULT_STATUSES = List.of(401, 403, 404, 500);
    // Odds of rare card states and argument shapes, so most executions reach the tool logic.
    private static final int BLANK_ACTION_ID_ODDS = 11;
    private static final int FULL_COMMENT_WINDOW_ODDS = 11;
    private static final int UNEXPECTED_ARGUMENT_ODDS = 16;
    private static final int NAME_SUFFIX_ODDS = 4;
    private static final List<String> SESSION_CARD_IDS = List.of(
            "",
            MISSING_CARD_ID,
            CURRENT_CARD_ID,
            CURRENT_CARD_ID,
            CURRENT_CARD_ID,
            CURRENT_CARD_ID,
            CURRENT_CARD_ID,
            CURRENT_CARD_ID);
    private static final List<ArgumentsShape> ARGUMENTS_SHAPES = weighted(
            List.of(ArgumentsShape.MISSING, ArgumentsShape.STRING, ArgumentsShape.ARRAY), ArgumentsShape.OBJECT, 13);
    private static final List<ValueShape> VALUE_SHAPES = weighted(
            List.of(
                    ValueShape.ABSENT,
                    ValueShape.JSON_NULL,
                    ValueShape.NUMBER,
                    ValueShape.BOOLEAN,
                    ValueShape.FREE_TEXT),
            ValueShape.DOMAIN_VALUE,
            5);
    // Spellings that collide under StateNames.normalize, so name-based moves meet duplicates.
    private static final List<String> LIST_NAMES =
            List.of("Todo", "In Progress", "Human Review", "human  review", "Done", "Ready for Codex");
    private static final List<String> CHECKLIST_NAMES = List.of("Tasks", "Acceptance", "Tasks ");
    private static final List<String> CHECK_ITEM_NAMES = List.of("Write tests", "Ship it", "write tests");
    private static final List<String> RECHECK_STATUSES = List.of("checking", "resumed", "Checking", "done");
    private static final List<String> ATTACHMENT_URLS = List.of(
            "https://example.com/pull/1",
            "http://example.com/build",
            "https://user:secret@example.com/",
            "https://example.com/?token=1",
            "https://example.com/#fragment",
            "ftp://example.com/file",
            "javascript:alert(1)",
            "https://example.com/a\nb");
    private static final String BLOCKER_PREFIX = "Blocked: ";
    private static final String COMMENT_LINK_FRAGMENT = "#comment-";
    private static final char DELETE = 0x7F;
    private static final Set<String> MANAGED_COMMENT_TOOLS =
            Set.of(TrelloHandoffToolHandler.UPSERT_WORKPAD, TrelloHandoffToolHandler.UPDATE_BLOCKER_RECHECK_STATUS);
    private static final String USAGE_SECTION = CodexUsageWorkpadSection.rechecking("Usage limit reached.");
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ConfigResolver CONFIG_RESOLVER = new ConfigResolver();

    private static final List<String> TOOLS = List.of(
            TrelloHandoffToolHandler.ADD_COMMENT,
            TrelloHandoffToolHandler.UPSERT_WORKPAD,
            TrelloHandoffToolHandler.UPDATE_BLOCKER_RECHECK_STATUS,
            TrelloHandoffToolHandler.MOVE_CURRENT_CARD,
            TrelloHandoffToolHandler.UPSERT_CHECKLIST_ITEM,
            TrelloHandoffToolHandler.ADD_URL_ATTACHMENT);
    private static final Map<String, Set<WriteKind>> TOOL_WRITE_KINDS = Map.of(
            TrelloHandoffToolHandler.ADD_COMMENT,
            EnumSet.of(WriteKind.COMMENT_CREATE),
            TrelloHandoffToolHandler.UPSERT_WORKPAD,
            EnumSet.of(WriteKind.COMMENT_CREATE, WriteKind.COMMENT_UPDATE, WriteKind.COMMENT_DELETE),
            TrelloHandoffToolHandler.UPDATE_BLOCKER_RECHECK_STATUS,
            EnumSet.of(WriteKind.COMMENT_CREATE, WriteKind.COMMENT_UPDATE, WriteKind.COMMENT_DELETE),
            TrelloHandoffToolHandler.MOVE_CURRENT_CARD,
            EnumSet.of(WriteKind.CARD_MOVE),
            TrelloHandoffToolHandler.UPSERT_CHECKLIST_ITEM,
            EnumSet.of(WriteKind.CHECKLIST_CREATE, WriteKind.CHECK_ITEM_CREATE, WriteKind.CHECK_ITEM_UPDATE),
            TrelloHandoffToolHandler.ADD_URL_ATTACHMENT,
            EnumSet.of(WriteKind.URL_ATTACHMENT_CREATE));
    private static final Map<String, List<Argument>> TOOL_ARGUMENTS = Map.of(
            TrelloHandoffToolHandler.ADD_COMMENT,
            List.of(new Argument("text", ArgumentType.TEXT, true)),
            TrelloHandoffToolHandler.UPSERT_WORKPAD,
            List.of(new Argument("text", ArgumentType.TEXT, true)),
            TrelloHandoffToolHandler.UPDATE_BLOCKER_RECHECK_STATUS,
            List.of(new Argument("status", ArgumentType.RECHECK_STATUS, true)),
            TrelloHandoffToolHandler.MOVE_CURRENT_CARD,
            List.of(
                    new Argument("list_name", ArgumentType.LIST_NAME, false),
                    new Argument("list_id", ArgumentType.LIST_ID, false)),
            TrelloHandoffToolHandler.UPSERT_CHECKLIST_ITEM,
            List.of(
                    new Argument("checklist_name", ArgumentType.CHECKLIST_NAME, true),
                    new Argument("item_name", ArgumentType.CHECK_ITEM_NAME, true),
                    new Argument("complete", ArgumentType.BOOLEAN, true)),
            TrelloHandoffToolHandler.ADD_URL_ATTACHMENT,
            List.of(
                    new Argument("url", ArgumentType.URL, true),
                    new Argument("name", ArgumentType.ATTACHMENT_NAME, false)));

    // A refusal comes from a configuration, argument, or card-state check that runs before any write.
    // trello_blocker_recheck_missing_action_id can also follow a created comment whose response has no
    // id, but the in-memory board always returns one.
    private static final Set<String> REFUSAL_CODES = Set.of(
            "unsupported_tool",
            "trello_tools_disabled",
            "trello_writes_disabled",
            "missing_card_context",
            "trello_comments_disabled",
            "trello_checklists_disabled",
            "trello_url_attachments_disabled",
            "trello_move_allowlist_required",
            "missing_destination_list",
            "trello_move_not_allowed",
            "invalid_checklist_item",
            "invalid_url_attachment",
            "invalid_url_attachment_name",
            "invalid_blocker_recheck_status",
            "trello_blocker_recheck_card_missing",
            "trello_blocker_recheck_refresh_failed",
            "trello_blocker_recheck_missing_source_id",
            "trello_blocker_recheck_missing_card_link",
            "trello_blocker_recheck_missing_action_id",
            "trello_blocker_recheck_comment_window_incomplete",
            "trello_blocker_recheck_not_started",
            "trello_blocker_recheck_stale",
            "trello_workpad_managed_section_malformed",
            "trello_workpad_managed_section_forbidden",
            "trello_workpad_card_missing",
            "trello_workpad_refresh_failed",
            "trello_workpad_missing_action_id",
            "trello_workpad_managed_section_non_primary",
            "trello_workpad_managed_section_ambiguous",
            "trello_workpad_comment_window_incomplete",
            "trello_checklist_ambiguous",
            "trello_check_item_ambiguous",
            // GitHub PR #784 reports arguments that break the advertised schema with this code.
            "invalid_tool_arguments");
    // Codes that can follow writes: a Trello request failed part way through, or a workpad cleanup had
    // to restore the authoritative comment.
    private static final Set<String> FAILURE_AFTER_WRITE_CODES = Set.of(
            "trello_workpad_managed_section_cleanup_failed",
            "trello_workpad_managed_section_transfer_rollback_failed",
            "trello_api_status",
            "trello_api_rate_limited",
            "trello_api_request",
            "trello_auth_failed",
            "trello_permission_denied",
            "trello_card_not_found",
            "trello_unknown_payload",
            "trello_unsupported_operation");
    // The handler's catch-all for a runtime exception. Argument validation uses it today, before any
    // write, so it is expected only for arguments outside the advertised schema.
    private static final String UNEXPECTED_FAILURE_CODE = "trello_tool_failed";

    private TrelloHandoffToolFuzzInvariants() {}

    /// Builds one board, configuration, and tool call from the fuzz input and runs the call.
    public static Execution execute(FuzzedDataProvider data) {
        var board = new InMemoryTrelloBoard(BOARD_ID);
        List<String> listNames = boardLists(data, board);
        CurrentCard currentCard = currentCard(data, board);
        otherCard(board);
        Permissions permissions = permissions(data, listNames);
        String sessionCardId = sessionCardId(data);
        String tool = tool(data);
        JsonNode arguments = arguments(data, tool, listNames);
        injectFault(data, board);

        EffectiveConfig config = config(permissions);
        var handler = new TrelloHandoffToolHandler(JSON, board.client());
        ObjectNode params = NODES.objectNode().put("tool", tool);
        params.set("arguments", arguments);
        JsonNode result = handler.handle(config, TestCards.card(sessionCardId, "SYM-1", "Todo"), params);
        return new Execution(
                permissions,
                board.lists(),
                sessionCardId,
                currentCard.shortLink(),
                tool,
                arguments,
                currentCard.commentTexts(),
                result,
                board.writes(),
                board.failedAnyRequest());
    }

    /// Checks the write-scope properties of one execution and the hashtag escaping of every text it used.
    public static void assertCallStaysInWriteScope(Execution execution) {
        for (Write write : execution.writes()) {
            assertWriteAllowed(execution, write);
        }
        assertResultIsStable(execution);
        for (String text : execution.commentTexts()) {
            assertHashtagEscapingOnlyInsertsBackslashes(text);
        }
        JsonNode text = execution.arguments().path("text");
        if (text.isTextual()) {
            assertHashtagEscapingOnlyInsertsBackslashes(text.textValue());
        }
    }

    /// `TrelloMarkdown.escapeLeadingHashtags` may only insert a backslash before a `#` that starts an
    /// issue reference. Every other character, including each line break, stays as it was.
    static void assertHashtagEscapingOnlyInsertsBackslashes(String markdown) {
        String escaped = TrelloMarkdown.escapeLeadingHashtags(markdown);
        int input = 0;
        for (int output = 0; output < escaped.length(); output++) {
            char character = escaped.charAt(output);
            if (input < markdown.length() && markdown.charAt(input) == character) {
                input++;
            } else if (character != '\\' || !startsIssueReference(markdown, input)) {
                throw new AssertionError("hashtag escaping changed the text at output index " + output + ": "
                        + quoted(markdown) + " became " + quoted(escaped));
            }
        }
        if (input != markdown.length()) {
            throw new AssertionError(
                    "hashtag escaping dropped text: " + quoted(markdown) + " became " + quoted(escaped));
        }
    }

    private static void assertWriteAllowed(Execution execution, Write write) {
        Permissions permissions = execution.permissions();
        if (write.kind() == WriteKind.UNKNOWN) {
            throw violation(execution, write, "the request is not a Trello write any handoff tool sends");
        }
        if (!execution.sessionCardId().equals(write.cardId())) {
            throw violation(execution, write, "the write does not land on the current card or its own resources");
        }
        if (!permissions.enabled() || !permissions.allowWrites()) {
            throw violation(execution, write, "trello_tools.enabled and allow_writes must both be true");
        }
        if (!TOOL_WRITE_KINDS.getOrDefault(execution.tool(), Set.of()).contains(write.kind())) {
            throw violation(execution, write, "the tool wrote outside its own write kinds");
        }
        switch (write.kind()) {
            case COMMENT_CREATE, COMMENT_UPDATE, COMMENT_DELETE -> assertCommentWriteAllowed(execution, write);
            case CHECKLIST_CREATE, CHECK_ITEM_CREATE, CHECK_ITEM_UPDATE -> {
                if (!permissions.allowChecklists()) {
                    throw violation(execution, write, "trello_tools.allow_checklists is false");
                }
                if (write.kind() != WriteKind.CHECK_ITEM_UPDATE && !singleLine(write.value())) {
                    throw violation(execution, write, "checklist and item names must be one non-control line");
                }
            }
            case URL_ATTACHMENT_CREATE -> {
                if (!permissions.allowUrlAttachments()) {
                    throw violation(execution, write, "trello_tools.allow_url_attachments is false");
                }
                if (!attachableUrl(write.value())) {
                    throw violation(
                            execution,
                            write,
                            "attached URLs must be http or https without credentials, query string, or fragment");
                }
            }
            case CARD_MOVE -> assertMoveAllowed(execution, write);
            case UNKNOWN -> throw new IllegalStateException("handled above");
        }
    }

    private static void assertCommentWriteAllowed(Execution execution, Write write) {
        if (!execution.permissions().allowComments()) {
            throw violation(execution, write, "trello_tools.allow_comments is false");
        }
        if (write.kind() == WriteKind.COMMENT_DELETE && !execution.permissions().allowDestructiveOperations()) {
            throw violation(execution, write, "trello_tools.allow_destructive_operations is false");
        }
        // A managed comment family may only replace or remove its own comments, and its writes keep the
        // family's identifying text, so a human comment is never edited or deleted.
        if (!MANAGED_COMMENT_TOOLS.contains(execution.tool())) {
            if (write.kind() != WriteKind.COMMENT_CREATE) {
                throw violation(execution, write, "only managed comment tools may edit or delete comments");
            }
            return;
        }
        if (write.kind() != WriteKind.COMMENT_CREATE && !inManagedFamily(execution, write.previousText())) {
            throw violation(execution, write, "the tool edited or deleted a comment outside its managed family");
        }
        if (write.kind() != WriteKind.COMMENT_DELETE && !inManagedFamily(execution, write.value())) {
            throw violation(execution, write, "the written comment lost its managed-family marker");
        }
    }

    private static void assertMoveAllowed(Execution execution, Write write) {
        TrelloClient.BoardList target = null;
        for (TrelloClient.BoardList list : execution.lists()) {
            if (list.id().equals(write.value())) {
                target = list;
            }
        }
        if (target == null) {
            throw violation(execution, write, "the card moved to a list that is not on the board");
        }
        if (target.closed()) {
            throw violation(execution, write, "the card moved to an archived list");
        }
        Permissions permissions = execution.permissions();
        String normalizedName = StateNames.normalize(target.name());
        boolean allowedById = permissions.allowedListIds().contains(target.id());
        boolean allowedByName = false;
        for (String allowedName : permissions.allowedListNames()) {
            allowedByName |= StateNames.normalize(allowedName).equals(normalizedName);
        }
        int openListsWithName = 0;
        for (TrelloClient.BoardList list : execution.lists()) {
            if (!list.closed() && StateNames.normalize(list.name()).equals(normalizedName)) {
                openListsWithName++;
            }
        }
        if (!allowedById && !(allowedByName && openListsWithName == 1)) {
            throw violation(
                    execution,
                    write,
                    "the destination is neither an allowed list id nor the only open list with an allowed name");
        }
    }

    private static void assertResultIsStable(Execution execution) {
        JsonNode result = execution.result();
        JsonNode payload = payload(execution);
        if (result.path("success").asBoolean()) {
            if (!execution.sessionCardId().equals(payload.path("card_id").asText())) {
                throw new AssertionError("a successful tool result names another card: " + describe(execution));
            }
            return;
        }
        String code = payload.path("error").asText("");
        if (REFUSAL_CODES.contains(code)) {
            if (!execution.writes().isEmpty()) {
                throw new AssertionError("refused call " + code + " still wrote to Trello: " + describe(execution));
            }
            return;
        }
        if (UNEXPECTED_FAILURE_CODE.equals(code)) {
            if (!execution.writes().isEmpty()) {
                throw new AssertionError(
                        "a call that failed with " + code + " still wrote to Trello: " + describe(execution));
            }
            if (wellFormedArguments(execution.tool(), execution.arguments())) {
                throw new AssertionError("a call with well-formed arguments failed with an unexpected exception: "
                        + payload.path("message").asText() + " " + describe(execution));
            }
            return;
        }
        if (!FAILURE_AFTER_WRITE_CODES.contains(code)) {
            throw new AssertionError(
                    "tool call failed with an unknown failure code " + quoted(code) + ": " + describe(execution));
        }
    }

    private static JsonNode payload(Execution execution) {
        String text =
                execution.result().path("contentItems").path(0).path("text").asText("");
        try {
            return JSON.readTree(text);
        } catch (IOException e) {
            throw new AssertionError("tool result content is not JSON: " + quoted(text), e);
        }
    }

    /// Whether the arguments match the tool's advertised input schema, with every string non-blank. The
    /// handler must then not fail with its catch-all code.
    private static boolean wellFormedArguments(String tool, JsonNode arguments) {
        List<Argument> expected = TOOL_ARGUMENTS.get(tool);
        if (expected == null || !arguments.isObject()) {
            return false;
        }
        int known = 0;
        for (Argument argument : expected) {
            JsonNode value = arguments.get(argument.name());
            if (value == null) {
                if (argument.required()) {
                    return false;
                }
                continue;
            }
            known++;
            boolean typed = argument.type() == ArgumentType.BOOLEAN
                    ? value.isBoolean()
                    : value.isTextual() && !value.textValue().isBlank();
            if (!typed) {
                return false;
            }
        }
        return known == arguments.size();
    }

    /// A workpad starts with the workpad heading. A blocker recheck status ends with the managed footer
    /// that links a comment on the current card.
    private static boolean inManagedFamily(Execution execution, @Nullable String text) {
        if (text == null) {
            return false;
        }
        if (TrelloHandoffToolHandler.UPSERT_WORKPAD.equals(execution.tool())) {
            return text.startsWith(TrelloHandoffToolHandler.WORKPAD_MARKER);
        }
        return text.endsWith(TrelloHandoffToolHandler.BLOCKER_RECHECK_FOOTER_SUFFIX)
                && text.contains("\n\n" + recheckFooterLink(execution.currentShortLink()));
    }

    private static String recheckFooterLink(String shortLink) {
        return TrelloHandoffToolHandler.BLOCKER_RECHECK_FOOTER_PREFIX
                + InMemoryTrelloBoard.CARD_URL_PREFIX
                + shortLink
                + COMMENT_LINK_FRAGMENT;
    }

    /// The URL rule from SPEC.md Section 17.8, checked independently of the handler.
    private static boolean attachableUrl(@Nullable String url) {
        if (!singleLine(url)) {
            return false;
        }
        try {
            var uri = new URI(url);
            String scheme = uri.getScheme();
            return ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    && uri.getHost() != null
                    && !uri.getHost().isBlank()
                    && uri.getRawUserInfo() == null
                    && uri.getRawQuery() == null
                    && uri.getRawFragment() == null;
        } catch (URISyntaxException e) {
            return false;
        }
    }

    private static boolean singleLine(@Nullable String value) {
        if (value == null) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < ' ' || character == DELETE) {
                return false;
            }
        }
        return true;
    }

    private static AssertionError violation(Execution execution, Write write, String property) {
        return new AssertionError("write scope violated, " + property + ": " + write + " in " + describe(execution));
    }

    private static String describe(Execution execution) {
        return "tool=" + execution.tool() + " arguments=" + execution.arguments() + " " + execution.permissions()
                + " session_card=" + execution.sessionCardId() + " injected_fault=" + execution.failedAnyRequest()
                + " result=" + execution.result();
    }

    private static boolean startsIssueReference(String text, int index) {
        return index + 1 < text.length() && text.charAt(index) == '#' && Character.isDigit(text.charAt(index + 1));
    }

    private static String quoted(String text) {
        return NODES.textNode(text).toString();
    }

    private static List<String> boardLists(FuzzedDataProvider data, InMemoryTrelloBoard board) {
        List<String> names = new ArrayList<>();
        for (int index = 0; index < LIST_COUNT; index++) {
            String name = data.pickValue(LIST_NAMES);
            names.add(name);
            board.list(listId(index), name, data.consumeBoolean());
        }
        return List.copyOf(names);
    }

    private static CurrentCard currentCard(FuzzedDataProvider data, InMemoryTrelloBoard board) {
        String shortLink = data.consumeBoolean() ? CURRENT_SHORT_LINK : "";
        board.card(
                CURRENT_CARD_ID, shortLink, nonBlank(data.consumeString(MAX_NAME_LENGTH), "Fix the build"), listId(0));
        List<String> texts = new ArrayList<>();
        int comments = data.consumeInt(0, MAX_COMMENTS);
        for (int index = 0; index < comments; index++) {
            // An empty action id is rare on Trello but the handler must not address it.
            String actionId = oneIn(data, BLANK_ACTION_ID_ODDS) ? "" : "c" + index;
            String text = commentText(data, shortLink, comments);
            texts.add(text);
            board.olderComment(CURRENT_CARD_ID, actionId, text);
        }
        if (oneIn(data, FULL_COMMENT_WINDOW_ODDS)) {
            // A full comment window makes the handler unsure whether an older managed comment exists.
            for (int index = comments; index < TrelloClient.WORKPAD_COMMENT_ACTION_LIMIT; index++) {
                board.olderComment(CURRENT_CARD_ID, "filler-" + index, "Older note " + index);
            }
        }
        int checklists = data.consumeInt(0, MAX_CHECKLISTS);
        for (int index = 0; index < checklists; index++) {
            String checklistId = "k" + index;
            board.checklist(CURRENT_CARD_ID, checklistId, data.pickValue(CHECKLIST_NAMES));
            int items = data.consumeInt(0, MAX_CHECK_ITEMS);
            for (int item = 0; item < items; item++) {
                board.checkItem(
                        checklistId,
                        checklistId + "-i" + item,
                        data.pickValue(CHECK_ITEM_NAMES),
                        data.consumeBoolean());
            }
        }
        return new CurrentCard(shortLink, List.copyOf(texts));
    }

    private static String commentText(FuzzedDataProvider data, String shortLink, int comments) {
        String free = data.consumeString(MAX_TEXT_LENGTH);
        String referencedId = "c" + data.consumeInt(0, Math.max(0, comments - 1));
        return switch (data.pickValue(CommentKind.values())) {
            case WORKPAD -> workpad(free);
            case WORKPAD_WITH_USAGE_SECTION -> withUsageSection(workpad(free));
            case BLOCKER -> BLOCKER_PREFIX + free;
            case RECHECK_CHECKING ->
                managedRecheckText(TrelloHandoffToolHandler.BLOCKER_RECHECK_CHECKING, shortLink, referencedId);
            case RECHECK_RESUMED ->
                managedRecheckText(TrelloHandoffToolHandler.RESUMED_WORK_PREFIX + free, shortLink, referencedId);
            case RECHECK_FOOTER_ONLY -> managedRecheckText(free, shortLink, referencedId);
            case ORDINARY -> nonBlank(free, "Looks good to me.");
        };
    }

    private static String managedRecheckText(String visibleStatus, String shortLink, String blockerActionId) {
        return visibleStatus + "\n\n" + recheckFooterLink(shortLink) + blockerActionId
                + TrelloHandoffToolHandler.BLOCKER_RECHECK_FOOTER_SUFFIX;
    }

    private static String workpad(String body) {
        return TrelloHandoffToolHandler.WORKPAD_MARKER + "\n\n" + body;
    }

    private static String withUsageSection(String text) {
        return text + "\n\n" + USAGE_SECTION;
    }

    private static String listId(int index) {
        return "list-" + index;
    }

    /// Another card on the same board with a workpad, a blocker, and a checklist whose names match the
    /// current card's, so a write that lands on the wrong card has a plausible target.
    private static void otherCard(InMemoryTrelloBoard board) {
        board.card(OTHER_CARD_ID, "zzz999", "Other card", listId(1))
                .olderComment(OTHER_CARD_ID, "other-c0", workpad("Other plan"))
                .olderComment(OTHER_CARD_ID, "other-c1", BLOCKER_PREFIX + "waiting for review")
                .checklist(OTHER_CARD_ID, "other-k0", CHECKLIST_NAMES.getFirst())
                .checkItem("other-k0", "other-k0-i0", CHECK_ITEM_NAMES.getFirst(), false);
    }

    private static Permissions permissions(FuzzedDataProvider data, List<String> listNames) {
        boolean enabled = data.consumeBoolean();
        boolean allowWrites = data.consumeBoolean();
        boolean allowComments = data.consumeBoolean();
        boolean allowChecklists = data.consumeBoolean();
        boolean allowUrlAttachments = data.consumeBoolean();
        boolean allowDestructiveOperations = data.consumeBoolean();
        List<String> allowedListIds = new ArrayList<>();
        List<String> allowedListNames = new ArrayList<>();
        for (int index = 0; index < LIST_COUNT; index++) {
            if (data.consumeBoolean()) {
                allowedListIds.add(listId(index));
            }
            if (data.consumeBoolean()) {
                allowedListNames.add(listNames.get(index));
            }
        }
        if (data.consumeBoolean()) {
            allowedListIds.add(MISSING_LIST_ID);
        }
        return new Permissions(
                enabled,
                allowWrites,
                allowComments,
                allowChecklists,
                allowUrlAttachments,
                allowDestructiveOperations,
                List.copyOf(allowedListIds),
                List.copyOf(allowedListNames));
    }

    private static String sessionCardId(FuzzedDataProvider data) {
        return data.pickValue(SESSION_CARD_IDS);
    }

    private static String tool(FuzzedDataProvider data) {
        int choice = data.consumeInt(0, TOOLS.size());
        return choice < TOOLS.size() ? TOOLS.get(choice) : data.consumeString(MAX_NAME_LENGTH);
    }

    private static JsonNode arguments(FuzzedDataProvider data, String tool, List<String> listNames) {
        return switch (data.pickValue(ARGUMENTS_SHAPES)) {
            case MISSING -> NODES.missingNode();
            case STRING -> NODES.textNode(data.consumeString(MAX_NAME_LENGTH));
            case ARRAY -> NODES.arrayNode().add(data.consumeString(MAX_NAME_LENGTH));
            case OBJECT -> objectArguments(data, tool, listNames);
        };
    }

    /// One value per argument the tool declares, sometimes plus an argument no tool declares.
    private static ObjectNode objectArguments(FuzzedDataProvider data, String tool, List<String> listNames) {
        ObjectNode arguments = NODES.objectNode();
        for (Argument argument : TOOL_ARGUMENTS.getOrDefault(tool, List.of())) {
            JsonNode value = argumentValue(data, argument.type(), listNames);
            if (value != null) {
                arguments.set(argument.name(), value);
            }
        }
        if (oneIn(data, UNEXPECTED_ARGUMENT_ODDS)) {
            arguments.put("card_id", OTHER_CARD_ID);
        }
        return arguments;
    }

    private static @Nullable JsonNode argumentValue(
            FuzzedDataProvider data, ArgumentType type, List<String> listNames) {
        return switch (data.pickValue(VALUE_SHAPES)) {
            case ABSENT -> null;
            case JSON_NULL -> NODES.nullNode();
            case NUMBER -> NODES.numberNode(data.consumeInt());
            case BOOLEAN -> NODES.booleanNode(data.consumeBoolean());
            case FREE_TEXT -> NODES.textNode(data.consumeString(MAX_TEXT_LENGTH));
            case DOMAIN_VALUE -> typedValue(data, type, listNames);
        };
    }

    private static JsonNode typedValue(FuzzedDataProvider data, ArgumentType type, List<String> listNames) {
        return switch (type) {
            case TEXT -> NODES.textNode(argumentText(data));
            case RECHECK_STATUS -> NODES.textNode(data.pickValue(RECHECK_STATUSES));
            case LIST_NAME ->
                NODES.textNode(data.consumeBoolean() ? data.pickValue(listNames) : data.pickValue(LIST_NAMES));
            case LIST_ID ->
                NODES.textNode(
                        data.consumeBoolean()
                                ? listId(data.consumeInt(0, LIST_COUNT - 1))
                                : data.pickValue(List.of(MISSING_LIST_ID, OTHER_CARD_ID, "")));
            case CHECKLIST_NAME -> NODES.textNode(data.pickValue(CHECKLIST_NAMES) + optionalSuffix(data));
            case CHECK_ITEM_NAME -> NODES.textNode(data.pickValue(CHECK_ITEM_NAMES) + optionalSuffix(data));
            case URL -> NODES.textNode(data.pickValue(ATTACHMENT_URLS));
            case ATTACHMENT_NAME -> NODES.textNode(data.consumeString(MAX_NAME_LENGTH));
            case BOOLEAN -> NODES.booleanNode(data.consumeBoolean());
        };
    }

    private static String argumentText(FuzzedDataProvider data) {
        String free = data.consumeString(MAX_TEXT_LENGTH);
        return switch (data.pickValue(TextKind.values())) {
            case WORKPAD -> workpad(free);
            case WITH_USAGE_SECTION -> withUsageSection(free);
            case ISSUE_REFERENCE -> "#1 " + free;
            case FREE -> free;
        };
    }

    private static String optionalSuffix(FuzzedDataProvider data) {
        return oneIn(data, NAME_SUFFIX_ODDS) ? data.consumeString(MAX_NAME_LENGTH) : "";
    }

    private static void injectFault(FuzzedDataProvider data, InMemoryTrelloBoard board) {
        int ordinal = data.consumeInt(0, MAX_FAULT_ORDINAL + NO_FAULT_CHOICES);
        if (ordinal >= 1 && ordinal <= MAX_FAULT_ORDINAL) {
            board.failRequest(ordinal, data.pickValue(FAULT_STATUSES));
        }
    }

    private static EffectiveConfig config(Permissions permissions) {
        Map<String, Object> tracker = Map.of(
                "kind",
                "trello",
                "endpoint",
                InMemoryTrelloBoard.ENDPOINT,
                "api_key",
                "key",
                "api_token",
                "token",
                "board_id",
                BOARD_ID,
                // A retry would sleep between attempts; one attempt keeps an execution fast.
                "max_api_retries",
                0);
        Map<String, Object> trelloTools = new HashMap<>();
        trelloTools.put("enabled", permissions.enabled());
        trelloTools.put("allow_writes", permissions.allowWrites());
        trelloTools.put("allow_comments", permissions.allowComments());
        trelloTools.put("allow_checklists", permissions.allowChecklists());
        trelloTools.put("allow_url_attachments", permissions.allowUrlAttachments());
        trelloTools.put("allow_destructive_operations", permissions.allowDestructiveOperations());
        trelloTools.put("allowed_move_list_ids", permissions.allowedListIds());
        trelloTools.put("allowed_move_list_names", permissions.allowedListNames());
        return CONFIG_RESOLVER
                .resolve(new WorkflowDefinition(
                        Path.of("WORKFLOW.md"), Map.of("tracker", tracker, "trello_tools", trelloTools), ""))
                .withResolvedBoardId(BOARD_ID);
    }

    private static boolean oneIn(FuzzedDataProvider data, int odds) {
        return data.consumeInt(0, odds - 1) == 0;
    }

    /// The rare choices once each, then the common choice `commonCopies` times, so a uniform pick
    /// lands on the common choice most of the time.
    private static <T> List<T> weighted(List<T> rare, T common, int commonCopies) {
        List<T> choices = new ArrayList<>(rare);
        choices.addAll(Collections.nCopies(commonCopies, common));
        return List.copyOf(choices);
    }

    private static String nonBlank(String value, String fallback) {
        return value.isBlank() ? fallback : value;
    }

    private enum CommentKind {
        WORKPAD,
        WORKPAD_WITH_USAGE_SECTION,
        BLOCKER,
        RECHECK_CHECKING,
        RECHECK_RESUMED,
        RECHECK_FOOTER_ONLY,
        ORDINARY
    }

    private enum ArgumentsShape {
        MISSING,
        STRING,
        ARRAY,
        OBJECT
    }

    private enum ValueShape {
        ABSENT,
        JSON_NULL,
        NUMBER,
        BOOLEAN,
        FREE_TEXT,
        DOMAIN_VALUE
    }

    private enum TextKind {
        WORKPAD,
        WITH_USAGE_SECTION,
        ISSUE_REFERENCE,
        FREE
    }

    private enum ArgumentType {
        TEXT,
        RECHECK_STATUS,
        LIST_NAME,
        LIST_ID,
        CHECKLIST_NAME,
        CHECK_ITEM_NAME,
        URL,
        ATTACHMENT_NAME,
        BOOLEAN
    }

    private record Argument(String name, ArgumentType type, boolean required) {}

    private record CurrentCard(String shortLink, List<String> commentTexts) {}

    /// The `trello_tools` settings one execution runs with.
    public record Permissions(
            boolean enabled,
            boolean allowWrites,
            boolean allowComments,
            boolean allowChecklists,
            boolean allowUrlAttachments,
            boolean allowDestructiveOperations,
            List<String> allowedListIds,
            List<String> allowedListNames) {}

    /// One tool call, the board lists it could move to, the tool result, and every write request it sent.
    public record Execution(
            Permissions permissions,
            List<TrelloClient.BoardList> lists,
            String sessionCardId,
            String currentShortLink,
            String tool,
            JsonNode arguments,
            List<String> commentTexts,
            JsonNode result,
            List<Write> writes,
            boolean failedAnyRequest) {}
}
