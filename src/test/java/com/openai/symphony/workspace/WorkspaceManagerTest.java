package com.openai.symphony.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openai.symphony.config.ConfigResolver;
import com.openai.symphony.workflow.WorkflowDefinition;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceManagerTest {
    private final WorkspaceManager manager = new WorkspaceManager(new HookRunner());

    @TempDir
    Path tempDir;

    @Test
    void createsSanitizedWorkspaceOnceAndRunsCreateHookOnlyForNewDirectory() throws Exception {
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

        Workspace first = manager.createForCard("TRELLO-a/b", config);
        Workspace second = manager.createForCard("TRELLO-a/b", config);

        assertThat(first.workspaceKey()).isEqualTo("TRELLO-a_b");
        assertThat(first.createdNow()).isTrue();
        assertThat(second.createdNow()).isFalse();
        assertThat(Files.readString(marker)).contains("created");
    }

    @Test
    void rejectsRootItselfAndSiblingPrefixPaths() {
        Path root = tempDir.resolve("root");

        assertThatThrownBy(() -> WorkspaceManager.requireInsideRoot(root, root))
                .isInstanceOfSatisfying(WorkspaceException.class, error -> assertThat(error.code())
                        .isEqualTo("workspace_outside_root"));
        assertThatThrownBy(() -> WorkspaceManager.requireInsideRoot(root, tempDir.resolve("root-sibling/card")))
                .isInstanceOfSatisfying(WorkspaceException.class, error -> assertThat(error.code())
                        .isEqualTo("workspace_outside_root"));
    }
}
