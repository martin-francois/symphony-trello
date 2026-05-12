package ch.fmartin.symphony.trello.setup;

import java.io.IOException;
import java.io.PrintStream;

interface Terminal {
    String readLine(String prompt) throws IOException;

    char[] readSecret(String prompt) throws IOException;

    void info(String line);

    void warn(String line);

    void error(String line);

    PrintStream out();

    PrintStream err();
}
