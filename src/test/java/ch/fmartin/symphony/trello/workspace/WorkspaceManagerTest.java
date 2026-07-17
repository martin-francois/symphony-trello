package ch.fmartin.symphony.trello.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import ch.fmartin.symphony.trello.config.ConfigResolver;
import ch.fmartin.symphony.trello.workflow.WorkflowDefinition;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class WorkspaceManagerTest {
    private final WorkspaceManager manager = new WorkspaceManager(new HookRunner());

    @TempDir
    Path tempDir;

    @Test
    void createsSanitizedWorkspaceOnceAndRunsCreateHookOnlyForNewDirectory() throws Exception {
        // given
        Path marker = tempDir.resolve("work").resolve("TRELLO-a_b").resolve("created.txt");
        var config = new ConfigResolver()
                .resolve(new WorkflowDefinition(
                        tempDir.resolve("WORKFLOW.md"),
                        Map.of(
                                "tracker",
                                Map.of("kind", "trello", "api_key", "k", "api_token", "t", "board_id", "b"),
                                "workspace",
                                Map.of("root", "work"),
                                "hooks",
                                Map.of("after_create", "echo created > created.txt")),
                        ""));

        // when
        Workspace first = manager.createForCard("TRELLO-a/b", config);
        Workspace second = manager.createForCard("TRELLO-a/b", config);

        // then
        Path workspacePath = marker.getParent().toAbsolutePath().normalize();
        assertThat(first).isEqualTo(new Workspace(workspacePath, "TRELLO-a_b", true));
        assertThat(second).isEqualTo(new Workspace(workspacePath, "TRELLO-a_b", false));
        assertThat(Files.readString(marker)).contains("created");
    }

    @Test
    void runsCreateHookBeforeWorkspaceContainsAnyManagedFiles() throws Exception {
        // given
        var config = new ConfigResolver()
                .resolve(new WorkflowDefinition(
                        tempDir.resolve("WORKFLOW.md"),
                        Map.of(
                                "tracker",
                                Map.of("kind", "trello", "api_key", "k", "api_token", "t", "board_id", "b"),
                                "workspace",
                                Map.of("root", "work"),
                                "hooks",
                                Map.of(
                                        "after_create",
                                        "test -z \"$(find . -mindepth 1 -maxdepth 1 -print -quit)\""
                                                + " && echo cloned > checkout.txt")),
                        ""));

        // when
        Workspace workspace = manager.createForCard("TRELLO-populate", config);

        // then
        assertThat(workspace.path().resolve("checkout.txt")).content().contains("cloned");
    }

    @Test
    void rejectsRootItselfAndSiblingPrefixPaths() {
        // given
        Path root = tempDir.resolve("root");

        // when
        var rootItself = catchThrowable(() -> WorkspaceManager.requireInsideRoot(root, root));
        var siblingPrefix =
                catchThrowable(() -> WorkspaceManager.requireInsideRoot(root, tempDir.resolve("root-sibling/card")));

        // then
        assertThat(rootItself).isInstanceOfSatisfying(WorkspaceException.class, error -> assertThat(error.code())
                .isEqualTo("workspace_outside_root"));
        assertThat(siblingPrefix).isInstanceOfSatisfying(WorkspaceException.class, error -> assertThat(error.code())
                .isEqualTo("workspace_outside_root"));
    }

    @Test
    void removesExistingWorkspaceAfterRunningBestEffortRemoveHook() throws Exception {
        // given
        Path marker = tempDir.resolve("removed.txt");
        var config = new ConfigResolver()
                .resolve(new WorkflowDefinition(
                        tempDir.resolve("WORKFLOW.md"),
                        Map.of(
                                "tracker",
                                Map.of("kind", "trello", "api_key", "k", "api_token", "t", "board_id", "b"),
                                "workspace",
                                Map.of("root", "work"),
                                "hooks",
                                Map.of("before_remove", "echo removed > " + marker)),
                        ""));
        Workspace workspace = manager.createForCard("TRELLO-remove/me", config);
        Files.writeString(workspace.path().resolve("nested.txt"), "content");

        // when
        manager.removeForIdentifierIfPresent("TRELLO-remove/me", config);
        manager.removeForIdentifierIfPresent("TRELLO-remove/me", config);

        // then
        assertThat(workspace.path()).doesNotExist();
        assertThat(Files.readString(marker)).contains("removed");
    }

    @Test
    void removesWorkspaceSymlinkWithoutFollowingIt() throws Exception {
        // given
        Path outside = tempDir.resolve("outside");
        Files.createDirectories(outside);
        Path preserved = Files.writeString(outside.resolve("preserved.txt"), "outside");
        var config = new ConfigResolver()
                .resolve(new WorkflowDefinition(
                        tempDir.resolve("WORKFLOW.md"),
                        Map.of(
                                "tracker",
                                Map.of("kind", "trello", "api_key", "k", "api_token", "t", "board_id", "b"),
                                "workspace",
                                Map.of("root", "work")),
                        ""));
        Workspace workspace = manager.createForCard("TRELLO-symlink", config);
        Files.createSymbolicLink(workspace.path().resolve("outside-link"), outside);

        // when
        manager.removeForIdentifierIfPresent("TRELLO-symlink", config);

        // then
        assertThat(workspace.path()).doesNotExist();
        assertThat(preserved).content().isEqualTo("outside");
    }

    @Test
    void rejectsMissingWorkspaceIdentifierBeforeTouchingFilesystem() {
        // given
        String blankIdentifier = " ";

        // when
        Throwable thrown = catchThrowable(() -> WorkspaceManager.sanitize(blankIdentifier));

        // then
        assertThat(thrown).isInstanceOfSatisfying(WorkspaceException.class, error -> assertThat(error.code())
                .isEqualTo("workspace_identifier_missing"));
    }
}
