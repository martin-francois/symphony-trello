package ch.fmartin.symphony.trello.testsupport;

/// Shared values for tests that start or simulate Windows batch shims such as npm's `codex.cmd`.
public final class WindowsShimFixtures {
    /// An `os.name` value that makes platform checks treat the host as Windows.
    public static final String WINDOWS_OS_NAME = "Windows 11";

    /// The default Windows `PATHEXT` order, which finds npm's `.CMD` shim when no `.EXE` exists.
    public static final String NPM_PATHEXT = ".COM;.EXE;.BAT;.CMD";

    private WindowsShimFixtures() {}
}
