package ch.fmartin.symphony.trello.setup;

import java.io.IOException;
import java.io.PrintStream;

@FunctionalInterface
interface CardCommandRunner {
    int run(CardCommandRequest request, PrintStream out) throws IOException;
}
