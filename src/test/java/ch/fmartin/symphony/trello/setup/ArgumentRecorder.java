package ch.fmartin.symphony.trello.setup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/// Stands in for the Node program behind an npm batch shim: writes every argument after the
/// record file to that file, one per line, then exits with [#EXIT_CODE].
public final class ArgumentRecorder {
    static final int EXIT_CODE = 3;

    private ArgumentRecorder() {}

    public static void main(String[] arguments) throws IOException {
        List<String> received = Arrays.asList(arguments).subList(1, arguments.length);
        Files.write(Path.of(arguments[0]), received);
        System.exit(EXIT_CODE);
    }
}
