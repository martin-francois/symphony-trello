package com.openai.symphony.setup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.symphony.setup.TrelloBoardSetup.ImportBoardRequest;
import com.openai.symphony.setup.TrelloBoardSetup.NewBoardRequest;
import com.openai.symphony.setup.TrelloBoardSetup.TrelloCredentials;
import com.openai.symphony.setup.TrelloBoardSetup.WorkspaceListRequest;
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
        out.println("Created lists: " + String.join(", ", result.lists()));
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
        out.println("Open lists: " + String.join(", ", result.openLists()));
        out.println("Active lists: " + String.join(", ", result.activeStates()));
        out.println("Terminal lists: " + String.join(", ", result.terminalStates()));
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

    private static String usage() {
        return """
                Usage:
                  ./mvnw -q exec:java -Dexec.args='list-workspaces'
                  ./mvnw -q exec:java -Dexec.args='new-board --name "Symphony Work Queue"'
                  ./mvnw -q exec:java -Dexec.args='import-board --board abc123 --active "Ready for Codex" --terminal Done'

                Credentials:
                  Export TRELLO_API_KEY and TRELLO_API_TOKEN, or pass --key and --token.

                Commands:
                  new-board     Create the recommended board, create lists, and write WORKFLOW.md.
                  import-board  Read an existing board and write WORKFLOW.md for it.
                  list-workspaces  Print workspace ids available to the token.

                Common options:
                  --workflow PATH       Workflow file to write. Default: WORKFLOW.md
                  --workspace-root PATH Workspace root to put in the workflow. Default: ./workspaces
                  --max-agents N        Initial max_concurrent_agents value. Default: 1
                  --force               Overwrite an existing workflow file
                  --endpoint URL        Trello API endpoint. Default: https://api.trello.com/1
                  --help                Show this help

                new-board options:
                  --name NAME           Required Trello board name
                  --workspace-id ID     Optional Trello Workspace id for the new board

                import-board options:
                  --board ID            Required Trello board id or short link
                  --active LIST         Repeatable or comma-separated. Defaults to Ready for Codex when present.
                  --terminal LIST       Repeatable or comma-separated. Defaults to Done when present.
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
            boolean help,
            String boardName,
            String workspaceId,
            String boardId,
            List<String> activeStates,
            List<String> terminalStates) {
        private NewBoardRequest newBoardRequest() {
            return new NewBoardRequest(
                    endpoint,
                    credentials,
                    boardName,
                    workspaceId,
                    workflowPath,
                    workspaceRoot,
                    maxConcurrentAgents,
                    force);
        }

        private ImportBoardRequest importBoardRequest() {
            return new ImportBoardRequest(
                    endpoint,
                    credentials,
                    boardId,
                    List.copyOf(activeStates),
                    List.copyOf(terminalStates),
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

            for (int i = 0; i < remaining.size(); i++) {
                String option = remaining.get(i);
                switch (option) {
                    case "--key" -> key = value(remaining, ++i, option);
                    case "--token" -> token = value(remaining, ++i, option);
                    case "--endpoint" -> endpoint = URI.create(value(remaining, ++i, option));
                    case "--workflow" -> workflowPath = Path.of(value(remaining, ++i, option));
                    case "--workspace-root" -> workspaceRoot = Path.of(value(remaining, ++i, option));
                    case "--max-agents" -> maxAgents = Integer.parseInt(value(remaining, ++i, option));
                    case "--force" -> force = true;
                    case "--name" -> boardName = value(remaining, ++i, option);
                    case "--workspace-id" -> workspaceId = value(remaining, ++i, option);
                    case "--board" -> boardId = value(remaining, ++i, option);
                    case "--active" -> activeStates.addAll(csv(value(remaining, ++i, option)));
                    case "--terminal" -> terminalStates.addAll(csv(value(remaining, ++i, option)));
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
                    help,
                    boardName,
                    workspaceId,
                    boardId,
                    activeStates,
                    terminalStates);
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
            String value = System.getenv(name);
            return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
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
