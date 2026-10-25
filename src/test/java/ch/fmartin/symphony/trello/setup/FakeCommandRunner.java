package ch.fmartin.symphony.trello.setup;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;

final class FakeCommandRunner implements CommandRunner {
    private static final int NO_MATCHED_PREFIX_LENGTH = -1;
    private final Map<List<String>, Queue<CommandResult>> results = new LinkedHashMap<>();
    private final Map<List<String>, CommandResult> prefixResults = new HashMap<>();
    private final List<List<String>> commands = new ArrayList<>();
    private final List<List<String>> interactiveCommands = new ArrayList<>();

    FakeCommandRunner returns(int exitCode, String output, String... command) {
        results.computeIfAbsent(List.of(command), ignored -> new ArrayDeque<>())
                .add(new CommandResult(exitCode, output));
        return this;
    }

    /// Answers every command that starts with `prefix` and has no exact result, for commands whose
    /// trailing arguments, such as a generated file path, the test cannot know in advance. When
    /// several prefixes match, the longest one wins.
    FakeCommandRunner returnsForPrefix(int exitCode, String output, String... prefix) {
        prefixResults.put(List.of(prefix), new CommandResult(exitCode, output));
        return this;
    }

    List<List<String>> commands() {
        return commands;
    }

    List<List<String>> interactiveCommands() {
        return interactiveCommands;
    }

    @Override
    public CommandResult run(String... command) {
        List<String> commandLine = List.of(command);
        commands.add(commandLine);
        Queue<CommandResult> queue = results.get(commandLine);
        if (queue == null || queue.isEmpty()) {
            var matchingResult = new CommandResult(
                    CommandResult.COMMAND_NOT_FOUND_EXIT_CODE, "missing: " + Arrays.toString(command));
            int longestPrefix = NO_MATCHED_PREFIX_LENGTH;
            for (var prefixResult : prefixResults.entrySet()) {
                int prefixLength = prefixResult.getKey().size();
                if (prefixLength > longestPrefix && startsWith(commandLine, prefixResult.getKey())) {
                    longestPrefix = prefixLength;
                    matchingResult = prefixResult.getValue();
                }
            }
            return matchingResult;
        }
        CommandResult result = queue.peek();
        if (queue.size() > 1) {
            return queue.remove();
        }
        return result;
    }

    private static boolean startsWith(List<String> commandLine, List<String> prefix) {
        return commandLine.size() >= prefix.size()
                && commandLine.subList(0, prefix.size()).equals(prefix);
    }

    @Override
    public CommandResult runInteractive(String... command) {
        interactiveCommands.add(List.of(command));
        return run(command);
    }
}
