package ch.fmartin.symphony.trello.setup;

import com.google.common.base.Splitter;
import com.google.common.base.Strings;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/// The readable form of a card command result. Names, text, and URLs from Trello or the board
/// manifest are escaped, so a card title, comment, or edited manifest URL cannot add lines or terminal
/// control sequences to the output.
@NullMarked
final class CardCommandText {
    private static final Splitter LINES = Splitter.on('\n');

    private CardCommandText() {}

    static String render(BoardCardCommands.Result result) {
        List<String> lines = new ArrayList<>();
        switch (result) {
            case BoardCardCommands.Listed listed -> renderLists(listed, lines);
            case BoardCardCommands.Shown shown -> renderCard(shown.card(), lines);
            case BoardCardCommands.Changed changed -> lines.add(changeLine(changed));
        }
        return String.join(System.lineSeparator(), lines);
    }

    private static void renderLists(BoardCardCommands.Listed listed, List<String> lines) {
        lines.add("Board " + DisplayNames.quotedName(listed.board().name()) + " "
                + DisplayNames.escapedText(listed.board().url()));
        for (BoardCardCommands.ListWithCards list : listed.lists()) {
            lines.add("");
            lines.add(listLine(list.list()));
            if (list.cards().isEmpty()) {
                lines.add("  (no open cards)");
            }
            for (BoardCardCommands.CardSummary card : list.cards()) {
                lines.add("  " + DisplayNames.escapedText(card.shortLink()) + "  "
                        + DisplayNames.quotedName(card.title()));
            }
        }
    }

    private static String listLine(BoardCardCommands.ListView list) {
        String roles = list.roles().isEmpty() ? "none" : String.join(", ", list.roles());
        return "List " + DisplayNames.quotedName(list.name()) + "  roles: " + roles + "  move here: "
                + yesNo(list.moveAllowed()) + "  create here: " + yesNo(list.createAllowed());
    }

    private static void renderCard(BoardCardCommands.CardDetails card, List<String> lines) {
        lines.add("Card " + DisplayNames.quotedName(card.title()));
        lines.add("  URL: " + DisplayNames.escapedText(card.url()));
        lines.add("  List: " + listName(card.list()));
        lines.add("  Archived: " + yesNo(card.archived()));
        if (!card.labels().isEmpty()) {
            lines.add("  Labels: " + DisplayNames.quotedList(card.labels()));
        }
        if (!card.description().isBlank()) {
            lines.add("Description:");
            indented(card.description(), "  ", lines);
        }
        for (BoardCardCommands.ChecklistView checklist : card.checklists()) {
            lines.add("Checklist " + DisplayNames.quotedName(Strings.nullToEmpty(checklist.name())) + ":");
            for (BoardCardCommands.ChecklistItemView item : checklist.items()) {
                lines.add("  [" + (item.complete() ? "x" : " ") + "] "
                        + DisplayNames.escapedText(Strings.nullToEmpty(item.text())));
            }
        }
        if (!card.attachments().isEmpty()) {
            lines.add("Attachments:");
            for (BoardCardCommands.AttachmentView attachment : card.attachments()) {
                lines.add("  " + DisplayNames.quotedName(Strings.nullToEmpty(attachment.name())) + " "
                        + DisplayNames.escapedText(Strings.nullToEmpty(attachment.url())));
            }
        }
        if (!card.comments().isEmpty()) {
            lines.add("Comments, newest first:");
            for (BoardCardCommands.CommentView comment : card.comments()) {
                lines.add("  " + Strings.nullToEmpty(comment.createdAt()) + " "
                        + DisplayNames.quotedName(Strings.nullToEmpty(comment.author())) + ":");
                indented(comment.text(), "    ", lines);
            }
        }
    }

    private static String changeLine(BoardCardCommands.Changed changed) {
        String card = DisplayNames.quotedName(changed.card().title());
        return switch (changed.status()) {
            case CARD_CREATED ->
                "Created card " + card + " in " + listName(changed.list()) + ": "
                        + DisplayNames.escapedText(changed.card().url());
            case CARD_MOVED -> "Moved card " + card + " to " + listName(changed.list()) + ".";
            case COMMENT_ADDED -> "Added a comment to card " + card + ".";
            case CARD_ARCHIVED ->
                "Archived card " + card + ". Trello keeps it under Archived items in the board menu, where it can"
                        + " be restored.";
        };
    }

    private static String listName(BoardCardCommands.@Nullable ListView list) {
        return list == null ? "(a closed or unknown list)" : DisplayNames.quotedName(list.name());
    }

    private static void indented(String text, String indent, List<String> lines) {
        for (String line : LINES.split(text)) {
            lines.add(indent + DisplayNames.escapedText(line));
        }
    }

    private static String yesNo(boolean value) {
        return value ? "yes" : "no";
    }
}
