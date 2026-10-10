package ch.fmartin.symphony.trello.testsupport;

import java.util.ArrayList;
import java.util.List;

/// Shared values for tests that start or simulate Windows batch shims such as npm's `codex.cmd`.
public final class WindowsShimFixtures {
    /// An `os.name` value that makes platform checks treat the host as Windows.
    public static final String WINDOWS_OS_NAME = "Windows 11";

    /// The default Windows `PATHEXT` order, which finds npm's `.CMD` shim when no `.EXE` exists.
    public static final String NPM_PATHEXT = ".COM;.EXE;.BAT;.CMD";

    /// Arguments that must reach a batch shim unchanged: Codex-style developer instructions with
    /// spaces, a path with parentheses and a short-name tilde, other punctuation `cmd.exe` leaves
    /// alone inside quotes, a trailing backslash, and an empty argument.
    public static final List<String> CMD_SAFE_ARGUMENTS = List.of(
            "-c",
            "developer_instructions=Selected board: Team board, short link abc123.",
            "C:\\Program Files (x86)\\RUNNER~1\\tool",
            "a;b [x]{y}#$'",
            "C:\\work dir\\",
            "");

    private WindowsShimFixtures() {}

    /// Returns the command line that starts `program` with `arguments`.
    public static List<String> command(String program, List<String> arguments) {
        List<String> command = new ArrayList<>();
        command.add(program);
        command.addAll(arguments);
        return List.copyOf(command);
    }
}
