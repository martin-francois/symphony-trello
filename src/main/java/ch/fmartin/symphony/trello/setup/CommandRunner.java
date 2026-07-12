package ch.fmartin.symphony.trello.setup;

import java.util.Map;
import java.util.Set;

interface CommandRunner {
    CommandResult run(String... command);

    default CommandResult run(Map<String, String> environmentOverrides, String... command) {
        return run(CommandEnvironment.withOverrides(environmentOverrides), command);
    }

    default CommandResult run(CommandEnvironment environment, String... command) {
        return run(command);
    }

    default CommandResult runInteractive(String... command) {
        return run(command);
    }

    default ToolStatus available(String... command) {
        return run(command).success() ? ToolStatus.found() : ToolStatus.unavailable();
    }
}

record CommandEnvironment(Map<String, String> overrides, Set<String> removals) {
    CommandEnvironment {
        overrides = Map.copyOf(overrides);
        removals = Set.copyOf(removals);
    }

    static CommandEnvironment inherited() {
        return new CommandEnvironment(Map.of(), Set.of());
    }

    static CommandEnvironment withOverrides(Map<String, String> overrides) {
        return new CommandEnvironment(overrides, Set.of());
    }
}
