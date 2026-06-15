package ch.fmartin.symphony.trello.setup;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Fluent builder for the picocli command-line argument arrays exercised by the CLI tests.
 *
 * <p>Each entry point seeds the subcommand token(s); fluent option methods append their flag and
 * value in call order. Because picocli ignores option order, the only contract this builder must
 * honour is reproducing every flag and value exactly (including multiplicity), which the ordered
 * token list does by construction. Use {@link #option(String, String)} or {@link #flag(String)}
 * for any flag without a convenience method.
 */
final class SetupCommandBuilder {
    private final List<String> tokens = new ArrayList<>();

    private SetupCommandBuilder(String... subcommand) {
        Collections.addAll(tokens, subcommand);
    }

    static SetupCommandBuilder command(String... subcommand) {
        return new SetupCommandBuilder(subcommand);
    }

    static SetupCommandBuilder newBoard() {
        return new SetupCommandBuilder("new-board");
    }

    static SetupCommandBuilder importBoard() {
        return new SetupCommandBuilder("import-board");
    }

    static SetupCommandBuilder listWorkspaces() {
        return new SetupCommandBuilder("list-workspaces");
    }

    static SetupCommandBuilder diagnostics() {
        return new SetupCommandBuilder("diagnostics");
    }

    static SetupCommandBuilder status() {
        return new SetupCommandBuilder("status");
    }

    static SetupCommandBuilder start() {
        return new SetupCommandBuilder("start");
    }

    static SetupCommandBuilder stop() {
        return new SetupCommandBuilder("stop");
    }

    static SetupCommandBuilder logs() {
        return new SetupCommandBuilder("logs");
    }

    static SetupCommandBuilder setupLocal() {
        return new SetupCommandBuilder("setup-local");
    }

    static SetupCommandBuilder setupLocalCheck() {
        return new SetupCommandBuilder("setup-local", "check");
    }

    static SetupCommandBuilder setupLocalRepairPort() {
        return new SetupCommandBuilder("setup-local", "repair-port");
    }

    static SetupCommandBuilder setupLocalConfigureGithub() {
        return new SetupCommandBuilder("setup-local", "configure-github");
    }

    SetupCommandBuilder endpoint(String value) {
        return option("--endpoint", value);
    }

    SetupCommandBuilder key(String value) {
        return option("--key", value);
    }

    SetupCommandBuilder token(String value) {
        return option("--token", value);
    }

    SetupCommandBuilder board(String value) {
        return option("--board", value);
    }

    SetupCommandBuilder boardName(String value) {
        return option("--board-name", value);
    }

    SetupCommandBuilder name(String value) {
        return option("--name", value);
    }

    SetupCommandBuilder active(String... values) {
        for (String value : values) {
            option("--active", value);
        }
        return this;
    }

    SetupCommandBuilder inProgress(String value) {
        return option("--in-progress", value);
    }

    SetupCommandBuilder noInProgress() {
        return flag("--no-in-progress");
    }

    SetupCommandBuilder terminal(String... values) {
        for (String value : values) {
            option("--terminal", value);
        }
        return this;
    }

    SetupCommandBuilder blocked(String value) {
        return option("--blocked", value);
    }

    SetupCommandBuilder workflow(Object pathOrString) {
        return option("--workflow", String.valueOf(pathOrString));
    }

    SetupCommandBuilder manifest(Object pathOrString) {
        return option("--manifest", String.valueOf(pathOrString));
    }

    SetupCommandBuilder env(Object pathOrString) {
        return option("--env", String.valueOf(pathOrString));
    }

    SetupCommandBuilder workspaceId(String value) {
        return option("--workspace-id", value);
    }

    SetupCommandBuilder workspaceRoot(Object pathOrString) {
        return option("--workspace-root", String.valueOf(pathOrString));
    }

    SetupCommandBuilder configDir(Object pathOrString) {
        return option("--config-dir", String.valueOf(pathOrString));
    }

    SetupCommandBuilder stateHome(Object pathOrString) {
        return option("--state-home", String.valueOf(pathOrString));
    }

    SetupCommandBuilder appHome(Object pathOrString) {
        return option("--app-home", String.valueOf(pathOrString));
    }

    SetupCommandBuilder output(Object pathOrString) {
        return option("--output", String.valueOf(pathOrString));
    }

    SetupCommandBuilder serverPort(int value) {
        return option("--server-port", String.valueOf(value));
    }

    SetupCommandBuilder serverPort(String value) {
        return option("--server-port", value);
    }

    SetupCommandBuilder maxAgents(int value) {
        return option("--max-agents", String.valueOf(value));
    }

    SetupCommandBuilder maxAgents(String value) {
        return option("--max-agents", value);
    }

    SetupCommandBuilder codexModel(String value) {
        return option("--codex-model", value);
    }

    SetupCommandBuilder codexReasoningEffort(String value) {
        return option("--codex-reasoning-effort", value);
    }

    SetupCommandBuilder force() {
        return flag("--force");
    }

    SetupCommandBuilder github() {
        return flag("--github");
    }

    SetupCommandBuilder noGithub() {
        return flag("--no-github");
    }

    SetupCommandBuilder dryRun() {
        return flag("--dry-run");
    }

    SetupCommandBuilder json() {
        return flag("--json");
    }

    SetupCommandBuilder showPrivateContext() {
        return flag("--show-private-context");
    }

    /** Generic escape hatch for any flag/value pair without a convenience method. */
    SetupCommandBuilder option(String flag, String value) {
        tokens.add(flag);
        tokens.add(value);
        return this;
    }

    /** Generic escape hatch for any value-less flag without a convenience method. */
    SetupCommandBuilder flag(String flag) {
        tokens.add(flag);
        return this;
    }

    /** Appends raw argument tokens verbatim, in order, for opaque flag/value sequences. */
    SetupCommandBuilder args(Iterable<String> rawTokens) {
        for (String token : rawTokens) {
            tokens.add(token);
        }
        return this;
    }

    List<String> toList() {
        return List.copyOf(tokens);
    }

    String[] toArgs() {
        return tokens.toArray(new String[0]);
    }
}
