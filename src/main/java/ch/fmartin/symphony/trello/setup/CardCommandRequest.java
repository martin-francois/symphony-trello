package ch.fmartin.symphony.trello.setup;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/// One `symphony-trello card` command: which connected board it works on, whether it prints JSON,
/// and the card operation itself.
record CardCommandRequest(
        Optional<String> board, Optional<Path> workflow, Optional<Path> configDir, boolean json, Operation operation) {
    static final String COMMAND = "card";
    static final String LIST = "list";
    static final String SHOW = "show";
    static final String CREATE = "create";
    static final String MOVE = "move";
    static final String COMMENT = "comment";
    static final String ARCHIVE = "archive";
    static final List<String> SUBCOMMANDS = List.of(LIST, SHOW, CREATE, MOVE, COMMENT, ARCHIVE);

    /// A card operation with the arguments the user typed. `listOrRole` values name an open list
    /// or a list role such as `human-review`.
    sealed interface Operation {
        /// The command name in selection errors, such as `card move`.
        String commandName();
    }

    record ListCards(Optional<String> listOrRole) implements Operation {
        @Override
        public String commandName() {
            return COMMAND + " " + LIST;
        }
    }

    record ShowCard(String card) implements Operation {
        @Override
        public String commandName() {
            return COMMAND + " " + SHOW;
        }
    }

    record CreateCard(String title, String description, String listOrRole) implements Operation {
        @Override
        public String commandName() {
            return COMMAND + " " + CREATE;
        }
    }

    record MoveCard(String card, String listOrRole) implements Operation {
        @Override
        public String commandName() {
            return COMMAND + " " + MOVE;
        }
    }

    record CommentOnCard(String card, String text) implements Operation {
        @Override
        public String commandName() {
            return COMMAND + " " + COMMENT;
        }
    }

    /// `confirmTitle` is the card title as the user confirmed it; the archive goes ahead only when
    /// it matches the card's current title.
    record ArchiveCard(String card, String confirmTitle) implements Operation {
        @Override
        public String commandName() {
            return COMMAND + " " + ARCHIVE;
        }
    }
}
