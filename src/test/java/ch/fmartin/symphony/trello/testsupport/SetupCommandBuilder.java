package ch.fmartin.symphony.trello.testsupport;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class SetupCommandBuilder {
    private final List<String> args;

    private SetupCommandBuilder(String command) {
        args = new ArrayList<>();
        args.add(command);
    }

    public static SetupCommandBuilder command(String command) {
        return new SetupCommandBuilder(command);
    }

    public static SetupCommandBuilder newBoard(String endpoint) {
        return new SetupCommandBuilder("new-board").endpoint(endpoint);
    }

    public static SetupCommandBuilder importBoard(String endpoint) {
        return new SetupCommandBuilder("import-board").endpoint(endpoint);
    }

    public SetupCommandBuilder endpoint(String endpoint) {
        return option("--endpoint", endpoint);
    }

    public SetupCommandBuilder credentials(String key, String token) {
        return option("--key", key).option("--token", token);
    }

    public SetupCommandBuilder name(String name) {
        return option("--name", name);
    }

    public SetupCommandBuilder board(String board) {
        return option("--board", board);
    }

    public SetupCommandBuilder active(String active) {
        return option("--active", active);
    }

    public SetupCommandBuilder terminal(String terminal) {
        return option("--terminal", terminal);
    }

    public SetupCommandBuilder workspaceId(String workspaceId) {
        return option("--workspace-id", workspaceId);
    }

    public SetupCommandBuilder workflow(Path workflow) {
        return workflow(workflow.toString());
    }

    public SetupCommandBuilder workflow(String workflow) {
        return option("--workflow", workflow);
    }

    public SetupCommandBuilder manifest(Path manifest) {
        return option("--manifest", manifest.toString());
    }

    public SetupCommandBuilder env(Path env) {
        return env(env.toString());
    }

    public SetupCommandBuilder env(String env) {
        return option("--env", env);
    }

    public SetupCommandBuilder option(String name, String value) {
        args.add(name);
        args.add(value);
        return this;
    }

    public SetupCommandBuilder flag(String name) {
        args.add(name);
        return this;
    }

    public List<String> args() {
        return List.copyOf(args);
    }

    public String[] build() {
        return args.toArray(String[]::new);
    }
}
