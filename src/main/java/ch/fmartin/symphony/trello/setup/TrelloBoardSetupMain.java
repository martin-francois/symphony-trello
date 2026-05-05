package ch.fmartin.symphony.trello.setup;

import ch.fmartin.symphony.trello.config.LocalEnvironment;
import ch.fmartin.symphony.trello.setup.TrelloBoardSetup.ImportBoardRequest;
import ch.fmartin.symphony.trello.setup.TrelloBoardSetup.NewBoardRequest;
import ch.fmartin.symphony.trello.setup.TrelloBoardSetup.TrelloCredentials;
import ch.fmartin.symphony.trello.setup.TrelloBoardSetup.WorkspaceListRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.PrintStream;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public final class TrelloBoardSetupMain {
    private TrelloBoardSetupMain() {}

    public static void main(String... args) {
        int exitCode = run(args, System.out, System.err);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        try {
            SetupCommand command = SetupCommand.parse(args);
            if (command.help()) {
                out.println(usage());
                return 0;
            }

            TrelloBoardSetup setup = new TrelloBoardSetup(new ObjectMapper());
            switch (command.mode()) {
                case NEW_BOARD -> printNewBoardResult(out, setup.createRecommendedBoard(command.newBoardRequest()));
                case IMPORT_BOARD ->
                    printImportBoardResult(out, setup.importExistingBoard(command.importBoardRequest()));
                case LIST_WORKSPACES -> printWorkspaces(out, setup.listWorkspaces(command.workspaceListRequest()));
            }
            return 0;
        } catch (TrelloBoardSetupException | IllegalArgumentException e) {
            err.println("setup_failed code=%s message=%s".formatted(errorCode(e), e.getMessage()));
            return 2;
        }
    }

    private static void printNewBoardResult(PrintStream out, TrelloBoardSetup.NewBoardResult result) {
        out.println("Created Trello board: " + result.boardName());
        if (result.boardUrl() != null && !result.boardUrl().isBlank()) {
            out.println("Board URL: " + result.boardUrl());
        }
        out.println("Board ID for WORKFLOW.md: " + result.boardKey());
        out.println("Created columns: " + String.join(", ", result.columns()));
        out.println("Wrote workflow: " + result.workflowPath().toAbsolutePath().normalize());
        out.println();
        out.println("Next:");
        out.println("  ./mvnw quarkus:dev");
    }

    private static void printWorkspaces(PrintStream out, List<TrelloBoardSetup.WorkspaceInfo> workspaces) {
        if (workspaces.isEmpty()) {
            out.println("No Trello workspaces found for this token.");
            return;
        }
        out.println("Trello workspaces:");
        for (TrelloBoardSetup.WorkspaceInfo workspace : workspaces) {
            out.println("  %s  %s".formatted(workspace.id(), workspace.displayName()));
        }
    }

    private static void printImportBoardResult(PrintStream out, TrelloBoardSetup.ImportBoardResult result) {
        out.println("Imported Trello board: " + result.boardName());
        if (result.boardUrl() != null && !result.boardUrl().isBlank()) {
            out.println("Board URL: " + result.boardUrl());
        }
        out.println("Board ID for WORKFLOW.md: " + result.boardKey());
        out.println("Open columns: " + String.join(", ", result.openColumns()));
        out.println("Active columns: " + String.join(", ", result.activeStates()));
        out.println("Terminal columns: " + String.join(", ", result.terminalStates()));
        out.println("Blocked column: " + optionalColumnName(result.blockedState()));
        out.println("Wrote workflow: " + result.workflowPath().toAbsolutePath().normalize());
        out.println();
        out.println("Next:");
        out.println("  ./mvnw quarkus:dev");
    }

    private static String errorCode(Exception e) {
        return e instanceof TrelloBoardSetupException setupException
                ? setupException.code()
                : "setup_invalid_arguments";
    }

    private static String optionalColumnName(String value) {
        return value == null || value.isBlank() ? "<none>" : value;
    }

    private static String usage() {
        return """
                Usage:
                  ./mvnw -q exec:java -Dexec.args='list-workspaces'
                  ./mvnw -q exec:java -Dexec.args='new-board --name "Symphony Work Queue"'
                  ./mvnw -q exec:java -Dexec.args='import-board --board abc123 --active "Ready for Codex" --terminal Done'

                Credentials:
                  Put TRELLO_API_KEY and TRELLO_API_TOKEN in .env, export them, or pass --key and --token.

                Commands:
                  new-board     Create the recommended board, create columns, and write a workflow file.
                  import-board  Read an existing board and write a workflow file for it.
                  list-workspaces  Print workspace ids available to the token.

                Common options:
                  --workflow PATH       Exact workflow file to write. Default: WORKFLOW.md, or a board-specific name when WORKFLOW.md exists.
                  --workspace-root PATH Where Symphony should create one local work directory per Trello card. Default: ./workspaces
                  --max-agents N        How many cards from this board may run at the same time. Default: 1
                  --force               Overwrite an existing workflow file
                  --endpoint URL        Trello API endpoint. Default: https://api.trello.com/1
                  --help                Show this help

                new-board options:
                  --name NAME           Required Trello board name
                  --workspace-id ID     Optional Trello Workspace id for the new board

                import-board options:
                  --board ID            Required Trello board id or short link
                  --active NAME         Repeatable or comma-separated column name. Defaults to Ready for Codex when present.
                  --terminal NAME       Repeatable or comma-separated column name. Defaults to Done when present.
                  --blocked NAME        Optional blocked column name. Defaults to Blocked when present.
                """;
    }

    private record SetupCommand(
            Mode mode,
            URI endpoint,
            TrelloCredentials credentials,
            Path workflowPath,
            Path workspaceRoot,
            int maxConcurrentAgents,
            boolean force,
            boolean workflowPathExplicit,
            boolean help,
            String boardName,
            String workspaceId,
            String boardId,
            List<String> activeStates,
            List<String> terminalStates,
            String blockedState) {
        private NewBoardRequest newBoardRequest() {
            return new NewBoardRequest(
                    endpoint,
                    credentials,
                    boardName,
                    workspaceId,
                    workflowPath,
                    workspaceRoot,
                    maxConcurrentAgents,
                    force,
                    !workflowPathExplicit);
        }

        private ImportBoardRequest importBoardRequest() {
            return new ImportBoardRequest(
                    endpoint,
                    credentials,
                    boardId,
                    List.copyOf(activeStates),
                    List.copyOf(terminalStates),
                    blockedState,
                    workflowPath,
                    workspaceRoot,
                    maxConcurrentAgents,
                    force);
        }

        private WorkspaceListRequest workspaceListRequest() {
            return new WorkspaceListRequest(endpoint, credentials);
        }

        private static SetupCommand parse(String[] args) {
            List<String> remaining = new ArrayList<>(List.of(args));
            boolean help = remaining.remove("--help") || remaining.remove("-h");
            Mode mode = Mode.NEW_BOARD;
            if (!remaining.isEmpty() && !remaining.getFirst().startsWith("--")) {
                mode = Mode.from(remaining.removeFirst());
            }

            URI endpoint = TrelloBoardSetup.DEFAULT_ENDPOINT;
            Path workflowPath = TrelloBoardSetup.DEFAULT_WORKFLOW_PATH;
            boolean workflowPathExplicit = false;
            Path workspaceRoot = TrelloBoardSetup.DEFAULT_WORKSPACE_ROOT;
            int maxAgents = TrelloBoardSetup.DEFAULT_MAX_CONCURRENT_AGENTS;
            boolean force = false;
            String key = env("TRELLO_API_KEY").orElse(null);
            String token = env("TRELLO_API_TOKEN").orElse(null);
            String boardName = null;
            String workspaceId = null;
            String boardId = null;
            List<String> activeStates = new ArrayList<>();
            List<String> terminalStates = new ArrayList<>();
            String blockedState = null;

            for (int i = 0; i < remaining.size(); i++) {
                String option = remaining.get(i);
                switch (option) {
                    case "--key" -> key = value(remaining, ++i, option);
                    case "--token" -> token = value(remaining, ++i, option);
                    case "--endpoint" -> endpoint = URI.create(value(remaining, ++i, option));
                    case "--workflow" -> {
                        workflowPath = Path.of(value(remaining, ++i, option));
                        workflowPathExplicit = true;
                    }
                    case "--workspace-root" -> workspaceRoot = Path.of(value(remaining, ++i, option));
                    case "--max-agents" -> maxAgents = Integer.parseInt(value(remaining, ++i, option));
                    case "--force" -> force = true;
                    case "--name" -> boardName = value(remaining, ++i, option);
                    case "--workspace-id" -> workspaceId = value(remaining, ++i, option);
                    case "--board" -> boardId = value(remaining, ++i, option);
                    case "--active" -> activeStates.addAll(csv(value(remaining, ++i, option)));
                    case "--terminal" -> terminalStates.addAll(csv(value(remaining, ++i, option)));
                    case "--blocked" ->
                        blockedState = value(remaining, ++i, option).trim();
                    default -> throw new IllegalArgumentException("Unknown option: " + option);
                }
            }

            return new SetupCommand(
                    mode,
                    endpoint,
                    new TrelloCredentials(key, token),
                    workflowPath,
                    workspaceRoot,
                    maxAgents,
                    force,
                    workflowPathExplicit,
                    help,
                    boardName,
                    workspaceId,
                    boardId,
                    activeStates,
                    terminalStates,
                    blockedState);
        }

        private static String value(List<String> args, int index, String option) {
            if (index >= args.size() || args.get(index).startsWith("--")) {
                throw new IllegalArgumentException("Missing value for " + option);
            }
            return args.get(index);
        }

        private static List<String> csv(String value) {
            return List.of(value.split(",")).stream()
                    .map(String::trim)
                    .filter(part -> !part.isEmpty())
                    .toList();
        }

        private static Optional<String> env(String name) {
            return LocalEnvironment.get(name);
        }
    }

    private enum Mode {
        NEW_BOARD,
        IMPORT_BOARD,
        LIST_WORKSPACES;

        private static Mode from(String value) {
            return switch (value.toLowerCase(Locale.ROOT)) {
                case "new-board", "create-board" -> NEW_BOARD;
                case "import-board", "import" -> IMPORT_BOARD;
                case "list-workspaces", "workspaces" -> LIST_WORKSPACES;
                default -> throw new IllegalArgumentException("Unknown command: " + value);
            };
        }
    }
}
