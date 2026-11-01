package ch.fmartin.symphony.trello.setup;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.ITypeConverter;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(
        name = CardHelperCommands.COMMAND,
        description = "Manage the trusted card helper independently of board workers.",
        mixinStandardHelpOptions = true,
        versionProvider = TrelloBoardSetupMain.ProjectVersion.class)
final class CardHelperCommands implements Callable<Integer> {
    static final String COMMAND = "card-helper";

    private final CardHelperManager manager;
    private final Map<String, String> environment;
    private final PrintStream out;

    @Parameters(
            index = "0",
            description = "Action: start, stop, status, or profile.",
            converter = ActionConverter.class)
    Action action;

    @Option(names = "--config-dir", description = "Trusted connected-board configuration directory.")
    Optional<Path> configDir = Optional.empty();

    @Option(names = "--state-home", description = "Managed runtime state directory.")
    Optional<Path> stateHome = Optional.empty();

    @Option(names = "--all", description = "Start or stop every initialized helper for this config directory.")
    boolean all;

    CardHelperCommands(CardHelperManager manager, Map<String, String> environment, PrintStream out) {
        this.manager = manager;
        this.environment = Map.copyOf(environment);
        this.out = out;
    }

    @Override
    public Integer call() throws IOException {
        LocalWorkerPaths paths =
                LocalWorkerPaths.from(Optional.empty(), configDir, Optional.empty(), stateHome, environment);
        if (all) {
            if (action == Action.START) {
                manager.startAll(paths, out);
            } else if (action == Action.STOP) {
                manager.stopAll(paths, out);
            } else {
                throw new TrelloBoardSetupException(
                        "setup_invalid_arguments", "cannot use --all: select card-helper start or stop");
            }
            return 0;
        }
        switch (action) {
            case START -> manager.start(paths, out);
            case STOP -> manager.stop(paths, out);
            case STATUS -> manager.status(paths, out);
            case PROFILE -> out.print(CardHelperProfile.render(paths, environment));
        }
        return 0;
    }

    enum Action {
        START,
        STOP,
        STATUS,
        PROFILE
    }

    static final class ActionConverter implements ITypeConverter<Action> {
        @Override
        public Action convert(String value) {
            return Action.valueOf(value.toUpperCase(Locale.ROOT));
        }
    }
}
