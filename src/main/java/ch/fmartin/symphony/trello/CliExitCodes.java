package ch.fmartin.symphony.trello;

/// Process exit codes used by Symphony command-line entry points.
public final class CliExitCodes {
    /// Expected setup or CLI failure, such as invalid arguments, rejected configuration, or a failed
    /// local preflight check.
    public static final int SETUP_FAILURE = 2;

    private CliExitCodes() {}
}
