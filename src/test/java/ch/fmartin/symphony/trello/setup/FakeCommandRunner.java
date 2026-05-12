package ch.fmartin.symphony.trello.setup;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;

final class FakeCommandRunner implements CommandRunner {
    private final Map<List<String>, Queue<CommandResult>> results = new LinkedHashMap<>();
    private final List<List<String>> interactiveCommands = new ArrayList<>();

    FakeCommandRunner returns(int exitCode, String output, String... command) {
        results.computeIfAbsent(List.of(command), ignored -> new ArrayDeque<>())
                .add(new CommandResult(exitCode, output));
        return this;
    }

    List<List<String>> interactiveCommands() {
        return interactiveCommands;
    }

    @Override
    public CommandResult run(String... command) {
        Queue<CommandResult> queue = results.get(List.of(command));
        if (queue == null || queue.isEmpty()) {
            return new CommandResult(127, "missing: " + Arrays.toString(command));
        }
        CommandResult result = queue.peek();
        if (queue.size() > 1) {
            return queue.remove();
        }
        return result;
    }

    @Override
    public CommandResult runInteractive(String... command) {
        interactiveCommands.add(List.of(command));
        return run(command);
    }
}
