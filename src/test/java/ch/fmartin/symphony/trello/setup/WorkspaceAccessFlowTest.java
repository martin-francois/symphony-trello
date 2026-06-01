package ch.fmartin.symphony.trello.setup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

final class WorkspaceAccessFlowTest {
    @TempDir
    Path tempDir;

    @MethodSource("pathInputs")
    @ParameterizedTest(name = "{0}")
    void parsesWorkspaceAccessPaths(String name, String input) throws Exception {
        // given
        LocalSetup.Options options = SetupOptionFactory.options(tempDir);
        RecordingTerminal terminal = new RecordingTerminal("y", input, "n");
        Path expected =
                switch (name) {
                    case "home" -> Path.of(System.getProperty("user.home"));
                    case "home with Windows separator" ->
                        Path.of(System.getProperty("user.home")).resolve("project");
                    case "relative" -> tempDir.resolve("project");
                    case "absolute" -> Path.of("/tmp/symphony-test");
                    default -> throw new IllegalArgumentException(name);
                };

        // when
        List<Path> paths = new WorkspaceAccessFlow().resolve(options, terminal);

        // then
        assertThat(paths).as(name).containsExactly(expected.normalize());
    }

    @Test
    void defaultNoKeepsAdditionalPathsEmpty() throws Exception {
        // given
        LocalSetup.Options options = SetupOptionFactory.options(tempDir);
        RecordingTerminal terminal = new RecordingTerminal("n");

        // when
        List<Path> paths = new WorkspaceAccessFlow().resolve(options, terminal);

        // then
        assertThat(paths).isEmpty();
        assertThat(terminal.stdout())
                .contains(
                        "Workspace access",
                        "This controls which files/folders sandboxed Trello card runs may use.",
                        "Default workspace path:",
                        "Allow cards to access local paths outside Symphony's default workspace? [y/N]")
                .doesNotContain(
                        "Added paths grant read/write access.",
                        "Directories apply recursively.",
                        "Use absolute paths, ~, or paths relative to the current directory.",
                        "Local setup relies on Codex sandbox behavior and normal OS permissions, not OS-level filesystem isolation.",
                        "Additional paths, comma-separated:");
    }

    @Test
    void acceptingAdditionalPathsPrintsDetailsBeforePathPrompt() throws Exception {
        // given
        LocalSetup.Options options = SetupOptionFactory.options(tempDir);
        RecordingTerminal terminal = new RecordingTerminal("y", "project");

        // when
        List<Path> paths = new WorkspaceAccessFlow().resolve(options, terminal);

        // then
        assertThat(paths).containsExactly(tempDir.resolve("project"));
        assertThat(terminal.stdout())
                .contains(
                        "  Added paths grant read/write access.",
                        "  Directories apply recursively.",
                        "  Use absolute paths, ~, or paths relative to the current directory.",
                        "  Local setup relies on Codex sandbox behavior and normal OS permissions, not OS-level filesystem isolation.",
                        "Additional paths, comma-separated:");
    }

    @Test
    void broadFilesystemPathNeedsSecondConfirmation() throws Exception {
        // given
        LocalSetup.Options options = SetupOptionFactory.options(tempDir);

        // when
        List<Path> paths = new WorkspaceAccessFlow().resolve(options, new RecordingTerminal("y", "/", "n"));

        // then
        assertThat(paths).isEmpty();
    }

    @Test
    void nonInteractiveBroadPathRequiresAllowAllPaths() {
        // given

        // when
        Throwable thrown = catchThrowable(() -> {
            LocalSetup.Options options =
                    SetupOptionFactory.options(tempDir, true, Optional.empty(), List.of(Path.of("/")), false, false);
            new WorkspaceAccessFlow().resolve(options, new RecordingTerminal());
        });

        // then
        assertThat(thrown).isInstanceOf(TrelloBoardSetupException.class).hasMessageContaining("--allow-all-paths");
    }

    private static Stream<Arguments> pathInputs() {
        return Stream.of(
                Arguments.of("home", "~"),
                Arguments.of("home with Windows separator", "~\\project"),
                Arguments.of("relative", "project"),
                Arguments.of("absolute", "/tmp/symphony-test"));
    }
}
