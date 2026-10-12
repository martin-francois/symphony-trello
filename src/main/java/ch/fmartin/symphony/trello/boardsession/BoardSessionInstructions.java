package ch.fmartin.symphony.trello.boardsession;

import static ch.fmartin.symphony.trello.TextCharacterMatchers.UNSAFE_SINGLE_LINE_CHARACTERS;

import com.google.common.base.CharMatcher;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.jspecify.annotations.NullMarked;

/// Board-session context for the interactive Codex session.
///
/// [#developerInstructions] reaches Codex as a command-line configuration value before the first
/// user request. Command-line quoting differs between platforms, so that text keeps only letters,
/// digits, spaces, and a few punctuation characters; Trello names are shown with other characters
/// replaced by spaces, and the exact names come from the board overview tool. The MCP server
/// instructions repeat the context with exact names because they travel over HTTP.
@NullMarked
public final class BoardSessionInstructions {
    public static final String MCP_SERVER_NAME = "symphony_trello";
    static final int COMMAND_LINE_NAME_LIMIT = 80;
    static final int COMMAND_LINE_LIST_LIMIT = 20;

    /// Characters that keep their meaning in a command-line argument on every supported platform
    /// and cannot make Codex read the value as TOML instead of plain text.
    static final CharMatcher COMMAND_LINE_SAFE_CHARACTERS = CharMatcher.forPredicate(Character::isLetterOrDigit)
            .or(CharMatcher.anyOf(" -_.,:/+@"))
            .precomputed();

    private static final String FIXED_INSTRUCTIONS = String.join(
            " ",
            "Symphony for Trello started this Codex session for one connected Trello board.",
            "There is no current card: do not assume that the user means a particular card until they name it.",
            "Use the " + MCP_SERVER_NAME + " MCP tools for every Trello read or change, and call",
            BoardSessionTools.BOARD_OVERVIEW + " before the first Trello action to get the exact list names,",
            "the open lists, and the operations the workflow allows.",
            "Only manage cards on the selected board.",
            "When a request to create a card does not name a list and the workflow has no default list for new",
            "cards, ask the user which list to use instead of guessing.",
            "A card created in a queue list can be picked up by a running Symphony worker for this board.",
            "Before " + BoardSessionTools.ARCHIVE_CARD + ", show the user the card title and wait for explicit",
            "confirmation.",
            "When a tool reports that the workflow disables an operation, tell the user which trello_tools setting",
            "controls it instead of working around it.",
            "Never ask for, print, or search for Trello API keys or tokens.",
            "The user may also ask questions or request work that is not about Trello.");

    private BoardSessionInstructions() {}

    /// Developer instructions for the Codex command line: fixed rules plus the selected board and its
    /// workflow list roles, limited to [#COMMAND_LINE_SAFE_CHARACTERS].
    public static String developerInstructions(BoardSessionContext context) {
        BoardListRoles roles = context.listRoles();
        return String.join(
                " ",
                FIXED_INSTRUCTIONS,
                "Selected board: " + commandLineName(context.boardName()) + ", short link "
                        + commandLineName(context.boardShortLink()) + ", workflow file "
                        + commandLineName(context.workflowFileName()) + ".",
                "Workflow list roles, with unusual characters in names shown as spaces:",
                "active lists: " + commandLineLists(roles.activeLists()) + ".",
                "queue lists for new work: " + commandLineLists(roles.queueLists()) + ".",
                "in-progress list: " + commandLineOptionalList(roles.inProgressList()) + ".",
                "review list: " + commandLineOptionalList(roles.reviewList()) + ".",
                "blocked list: " + commandLineOptionalList(roles.blockedList()) + ".",
                "terminal lists: " + commandLineLists(roles.terminalLists()) + ".",
                "default list for new cards: "
                        + roles.defaultNewCardList()
                                .map(BoardSessionInstructions::commandLineName)
                                .orElse("none, ask the user")
                        + ".");
    }

    /// Board-specific instructions that the MCP server returns when Codex connects. They use the
    /// exact Trello names on single lines.
    public static String serverInstructions(BoardSessionContext context) {
        BoardListRoles roles = context.listRoles();
        return String.join(
                "\n",
                "This server manages one Trello board for an interactive Codex session started by Symphony for Trello.",
                "Selected board: " + singleLine(context.boardName()) + " (short link "
                        + singleLine(context.boardShortLink()) + ", workflow file "
                        + singleLine(context.workflowFileName()) + ").",
                "There is no current card.",
                "Active lists: " + listText(roles.activeLists()) + ".",
                "Queue lists for new work: " + listText(roles.queueLists()) + ".",
                "In-progress list: " + optionalListText(roles.inProgressList()) + ".",
                "Review list: " + optionalListText(roles.reviewList()) + ".",
                "Blocked list: " + optionalListText(roles.blockedList()) + ".",
                "Terminal lists: " + listText(roles.terminalLists()) + ".",
                "Default list for new cards: "
                        + roles.defaultNewCardList()
                                .map(BoardSessionInstructions::singleLine)
                                .orElse("none, ask the user which list to use")
                        + ".",
                "Call " + BoardSessionTools.BOARD_OVERVIEW + " for the open lists and the allowed operations.");
    }

    static String commandLineName(String value) {
        String safe = CharMatcher.whitespace()
                .trimAndCollapseFrom(COMMAND_LINE_SAFE_CHARACTERS.negate().replaceFrom(value, ' '), ' ');
        String bounded = safe.length() > COMMAND_LINE_NAME_LIMIT ? safe.substring(0, COMMAND_LINE_NAME_LIMIT) : safe;
        return bounded.isEmpty() ? "unnamed" : bounded;
    }

    private static String commandLineLists(List<String> lists) {
        if (lists.isEmpty()) {
            return "none";
        }
        String shown = lists.stream()
                .limit(COMMAND_LINE_LIST_LIMIT)
                .map(BoardSessionInstructions::commandLineName)
                .collect(Collectors.joining(", "));
        return lists.size() > COMMAND_LINE_LIST_LIMIT ? shown + ", and more" : shown;
    }

    private static String commandLineOptionalList(Optional<String> list) {
        return list.map(BoardSessionInstructions::commandLineName).orElse("none");
    }

    private static String listText(List<String> lists) {
        return lists.isEmpty()
                ? "none"
                : lists.stream().map(BoardSessionInstructions::singleLine).collect(Collectors.joining(", "));
    }

    private static String optionalListText(Optional<String> list) {
        return list.map(BoardSessionInstructions::singleLine).orElse("none");
    }

    private static String singleLine(String value) {
        return UNSAFE_SINGLE_LINE_CHARACTERS.replaceFrom(value, ' ').strip();
    }
}
