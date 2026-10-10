package ch.fmartin.symphony.trello.setup;

import ch.fmartin.symphony.trello.process.ExecutableResolver;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/// Starts the command in its arguments from a JVM whose `PATH` the test controls, either by bare
/// name as `ProcessBuilder` sees it or through [ExecutableResolver], and prints the outcome.
public final class BareCommandProbe {
    static final String BARE = "bare";
    static final String RESOLVED = "resolved";
    static final String LAUNCH_FAILED = "launch failed";
    static final String EXIT_PREFIX = "exit ";

    private BareCommandProbe() {}

    public static void main(String[] arguments) throws InterruptedException {
        List<String> command = Arrays.asList(arguments).subList(1, arguments.length);
        try {
            List<String> launch = RESOLVED.equals(arguments[0])
                    ? ExecutableResolver.forCurrentProcess().launchCommand(command)
                    : command;
            Process process = new ProcessBuilder(launch).inheritIO().start();
            System.out.println(EXIT_PREFIX + process.waitFor());
        } catch (IOException e) {
            System.out.println(LAUNCH_FAILED);
        }
    }
}
