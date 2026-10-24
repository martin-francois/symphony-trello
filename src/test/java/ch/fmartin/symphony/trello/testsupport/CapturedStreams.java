package ch.fmartin.symphony.trello.testsupport;

/// The exit code and the separately captured standard output and standard error of one command
/// run, in process or as a child process.
public interface CapturedStreams {
    int exitCode();

    String stdout();

    String stderr();
}
