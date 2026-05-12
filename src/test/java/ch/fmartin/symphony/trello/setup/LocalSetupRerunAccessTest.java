package ch.fmartin.symphony.trello.setup;

import static ch.fmartin.symphony.trello.setup.ManifestAssertions.assertThatManifest;
import static ch.fmartin.symphony.trello.setup.WorkflowAssertions.assertThatWorkflow;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class LocalSetupRerunAccessTest extends LocalSetupFixtureSupport {
    @Test
    void rerunWithExistingSingleBoardAndAddPathUpdatesWorkflowAndManifest() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.access-rerun.md");
        Path env = tempDir.resolve(".env.access-rerun");
        Path allowedPath = tempDir.resolve("allowed path");
        SetupRunResult firstResult = runSetup(
                "--non-interactive",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--board-name",
                "Access Rerun Queue",
                "--workflow",
                workflow.toString(),
                "--env",
                env.toString(),
                "--no-github");
        commands.startedWorkflows.clear();
        commands.stoppedWorkflows.clear();

        // when
        SetupRunResult result = runSetup("--non-interactive", "--add-path", allowedPath.toString(), "--no-github");

        // then
        firstResult.assertSuccess();
        result.assertSuccess().stdoutContains("Updated workflow: " + workflow);
        assertThatWorkflow(workflow).hasAdditionalWritableRoot(allowedPath);
        assertThatManifest(tempDir.resolve("config/connected-boards.json"))
                .hasAdditionalWritableRoot("Access Rerun Queue", allowedPath);
        assertThat(commands.stoppedWorkflows).containsExactly(workflow.toString());
        assertThat(commands.startedWorkflows).containsExactly(workflow.toString());
    }

    @Test
    void rerunWithExistingGithubBoardAndAddPathUpdatesWorkflowAndManifest() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.github-access-rerun.md");
        Path env = tempDir.resolve(".env.github-access-rerun");
        Path allowedPath = tempDir.resolve("github allowed path");
        commands.githubAuthenticated = true;
        SetupRunResult firstResult = runSetup(
                "--non-interactive",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--board-name",
                "GitHub Access Rerun Queue",
                "--workflow",
                workflow.toString(),
                "--env",
                env.toString(),
                "--github");
        commands.startedWorkflows.clear();
        commands.stoppedWorkflows.clear();

        // when
        SetupRunResult result = runSetup("--non-interactive", "--github", "--add-path", allowedPath.toString());

        // then
        firstResult.assertSuccess();
        result.assertSuccess();
        assertThatWorkflow(workflow).hasAdditionalWritableRoot(allowedPath);
        assertThatManifest(tempDir.resolve("config/connected-boards.json"))
                .hasGithubEnabled("GitHub Access Rerun Queue")
                .hasAdditionalWritableRoot("GitHub Access Rerun Queue", allowedPath);
        assertThat(commands.stoppedWorkflows).containsExactly(workflow.toString());
        assertThat(commands.startedWorkflows).containsExactly(workflow.toString());
    }

    @Test
    void rerunWithExistingRunningBoardAndNoStartDoesNotStopWorkerForAccessUpdate() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.no-start-access-rerun.md");
        Path env = tempDir.resolve(".env.no-start-access-rerun");
        Path allowedPath = tempDir.resolve("no start path");
        SetupRunResult firstResult = runSetup(
                "--non-interactive",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--board-name",
                "No Start Access Rerun Queue",
                "--workflow",
                workflow.toString(),
                "--env",
                env.toString(),
                "--no-github");
        commands.startedWorkflows.clear();
        commands.stoppedWorkflows.clear();

        // when
        SetupRunResult result =
                runSetup("--non-interactive", "--add-path", allowedPath.toString(), "--no-github", "--no-start");

        // then
        firstResult.assertSuccess();
        result.assertSuccess().stdoutContains("Restart skipped.", "symphony-trello stop --workflow " + workflow);
        assertThatWorkflow(workflow).hasAdditionalWritableRoot(allowedPath);
        assertThat(commands.stoppedWorkflows).isEmpty();
        assertThat(commands.startedWorkflows).isEmpty();
    }

    @Test
    void rerunWithExistingSingleBoardAndDangerFullAccessUpdatesWorkflowAndManifest() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.danger-rerun.md");
        Path env = tempDir.resolve(".env.danger-rerun");
        SetupRunResult firstResult = runSetup(
                "--non-interactive",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--board-name",
                "Danger Rerun Queue",
                "--workflow",
                workflow.toString(),
                "--env",
                env.toString(),
                "--no-github");
        commands.startedWorkflows.clear();
        commands.stoppedWorkflows.clear();

        // when
        SetupRunResult result = runSetup("--non-interactive", "--danger-full-access", "--no-github");

        // then
        firstResult.assertSuccess();
        result.assertSuccess().stdoutContains("danger-full-access disables Codex's command/filesystem sandbox");
        assertThatWorkflow(workflow).hasDangerFullAccess();
        assertThatManifest(tempDir.resolve("config/connected-boards.json")).hasDangerFullAccess("Danger Rerun Queue");
        assertThat(commands.stoppedWorkflows).containsExactly(workflow.toString());
        assertThat(commands.startedWorkflows).containsExactly(workflow.toString());
    }

    @Test
    void interactiveRerunWithDangerFullAccessRequiresConfirmationBeforeUpdatingExistingBoard() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.danger-confirmation-rerun.md");
        Path env = tempDir.resolve(".env.danger-confirmation-rerun");
        SetupRunResult firstResult = runSetup(
                "--non-interactive",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--board-name",
                "Danger Confirmation Rerun Queue",
                "--workflow",
                workflow.toString(),
                "--env",
                env.toString(),
                "--no-github");
        commands.startedWorkflows.clear();
        commands.stoppedWorkflows.clear();

        // when
        SetupRunResult result = runSetupWithInput("n\n", "--danger-full-access", "--no-github");

        // then
        firstResult.assertSuccess();
        result.assertSuccess()
                .stdoutContains(
                        "Allow Codex to run without its command/filesystem sandbox for this workflow (danger-full-access)? [y/N] ");
        result.stdoutDoesNotContain("danger-full-access disables Codex's command/filesystem sandbox");
        assertThatWorkflow(workflow).hasNoDangerFullAccess();
        assertThatManifest(tempDir.resolve("config/connected-boards.json"))
                .hasNoDangerFullAccess("Danger Confirmation Rerun Queue");
        assertThat(commands.stoppedWorkflows).containsExactly(workflow.toString());
        assertThat(commands.startedWorkflows).containsExactly(workflow.toString());
    }

    @Test
    void rerunWithMultipleBoardsAndAddPathAsksWhichBoardToUpdate() throws Exception {
        // given
        Path firstWorkflow = tempDir.resolve("WORKFLOW.first-access.md");
        Path secondWorkflow = tempDir.resolve("WORKFLOW.second-access.md");
        Path firstEnv = tempDir.resolve(".env.first-access");
        Path secondEnv = tempDir.resolve(".env.second-access");
        Path allowedPath = tempDir.resolve("selected path");
        writeWorkflow(firstWorkflow, "board-1", 18101);
        writeWorkflow(secondWorkflow, "board-2", 18102);
        writeManifest(
                """
                {"boards":[
                  {"boardId":"board-1","boardKey":"one","boardName":"First Access","boardUrl":"https://trello.example/one","workflowPath":"%s","envPath":"%s","workspaceRoot":"%s","serverPort":18101,"githubEnabled":false,"additionalWritableRoots":[],"dangerFullAccess":false},
                  {"boardId":"board-2","boardKey":"two","boardName":"Second Access","boardUrl":"https://trello.example/two","workflowPath":"%s","envPath":"%s","workspaceRoot":"%s","serverPort":18102,"githubEnabled":false,"additionalWritableRoots":[],"dangerFullAccess":false}
                ]}
                """
                        .formatted(
                                json(firstWorkflow),
                                json(firstEnv),
                                json(tempDir.resolve("workspaces-one")),
                                json(secondWorkflow),
                                json(secondEnv),
                                json(tempDir.resolve("workspaces-two"))));

        // when
        SetupRunResult result = runSetupWithInput("2\n", "--add-path", allowedPath.toString(), "--no-github");

        // then
        result.assertSuccess().stdoutContains("Choose the Trello board to update:");
        assertThat(firstWorkflow).content(StandardCharsets.UTF_8).doesNotContain(allowedPath.toString());
        assertThat(secondWorkflow).content(StandardCharsets.UTF_8).contains(allowedPath.toString());
    }

    @Test
    void rerunWithMultipleBoardsAndAddPathBoardSelectorUpdatesMatchingConnectedBoard() throws Exception {
        // given
        Path firstWorkflow = tempDir.resolve("WORKFLOW.first-selector-access.md");
        Path secondWorkflow = tempDir.resolve("WORKFLOW.docs-selector-access.md");
        Path allowedPath = tempDir.resolve("docs selected path");
        writeWorkflow(firstWorkflow, "board-1", 18111);
        writeWorkflow(secondWorkflow, "board-2", 18112);
        writeManifest(
                """
                {"boards":[
                  {"boardId":"board-1","boardKey":"one","boardName":"First Queue","boardUrl":"https://trello.example/one","workflowPath":"%s","envPath":"%s","workspaceRoot":"%s","serverPort":18111,"githubEnabled":false,"additionalWritableRoots":[],"dangerFullAccess":false},
                  {"boardId":"board-2","boardKey":"two","boardName":"Docs Queue","boardUrl":"https://trello.example/two","workflowPath":"%s","envPath":"%s","workspaceRoot":"%s","serverPort":18112,"githubEnabled":false,"additionalWritableRoots":[],"dangerFullAccess":false}
                ]}
                """
                        .formatted(
                                json(firstWorkflow),
                                json(tempDir.resolve(".env.first-selector-access")),
                                json(tempDir.resolve("workspaces-one")),
                                json(secondWorkflow),
                                json(tempDir.resolve(".env.docs-selector-access")),
                                json(tempDir.resolve("workspaces-two"))));

        // when
        SetupRunResult result = runSetup(
                "--non-interactive", "--add-path", allowedPath.toString(), "--board", "Docs Queue", "--no-github");

        // then
        result.assertSuccess()
                .stdoutContains("Updated workflow: " + secondWorkflow, "Start Symphony for the updated workflow:");
        assertThatWorkflow(firstWorkflow).hasNoAdditionalWritableRoot(allowedPath);
        assertThatWorkflow(secondWorkflow).hasAdditionalWritableRoot(allowedPath);
        assertThatManifest(tempDir.resolve("config/connected-boards.json"))
                .hasAdditionalWritableRoot("Docs Queue", allowedPath);
        assertThat(trello.boardLookups()).isEmpty();
        assertThat(commands.startedWorkflows).isEmpty();
        assertThat(commands.stoppedWorkflows).isEmpty();
    }

    @Test
    void rerunWithMultipleBoardsAndDangerFullAccessBoardIdSelectorUpdatesMatchingConnectedBoard() throws Exception {
        // given
        Path firstWorkflow = tempDir.resolve("WORKFLOW.first-danger-selector.md");
        Path secondWorkflow = tempDir.resolve("WORKFLOW.second-danger-selector.md");
        writeWorkflow(firstWorkflow, "board-1", 18121);
        writeWorkflow(secondWorkflow, "board-2", 18122);
        writeManifest(
                """
                {"boards":[
                  {"boardId":"board-1","boardKey":"one","boardName":"First Danger","boardUrl":"https://trello.example/one","workflowPath":"%s","envPath":"%s","workspaceRoot":"%s","serverPort":18121,"githubEnabled":false,"additionalWritableRoots":[],"dangerFullAccess":false},
                  {"boardId":"board-2","boardKey":"two","boardName":"Second Danger","boardUrl":"https://trello.example/two","workflowPath":"%s","envPath":"%s","workspaceRoot":"%s","serverPort":18122,"githubEnabled":false,"additionalWritableRoots":[],"dangerFullAccess":false}
                ]}
                """
                        .formatted(
                                json(firstWorkflow),
                                json(tempDir.resolve(".env.first-danger-selector")),
                                json(tempDir.resolve("workspaces-one")),
                                json(secondWorkflow),
                                json(tempDir.resolve(".env.second-danger-selector")),
                                json(tempDir.resolve("workspaces-two"))));
        commands.startHealthServer(secondWorkflow, "board-2");

        // when
        SetupRunResult result =
                runSetup("--non-interactive", "--danger-full-access", "--board", "board-2", "--no-github");

        // then
        result.assertSuccess();
        assertThatWorkflow(firstWorkflow).hasNoDangerFullAccess();
        assertThatWorkflow(secondWorkflow).hasDangerFullAccess();
        assertThatManifest(tempDir.resolve("config/connected-boards.json")).hasDangerFullAccess("Second Danger");
        assertThat(trello.boardLookups()).isEmpty();
        assertThat(commands.stoppedWorkflows).containsExactly(secondWorkflow.toString());
        assertThat(commands.startedWorkflows).containsExactly(secondWorkflow.toString());
    }

    @Test
    void nonInteractiveRerunWithMultipleBoardsAndAddPathRequiresBoardSelector() throws Exception {
        // given
        Path firstWorkflow = tempDir.resolve("WORKFLOW.first-required-selector.md");
        Path secondWorkflow = tempDir.resolve("WORKFLOW.second-required-selector.md");
        Path allowedPath = tempDir.resolve("needs selector path");
        writeWorkflow(firstWorkflow, "board-1", 18131);
        writeWorkflow(secondWorkflow, "board-2", 18132);
        writeManifest(
                """
                {"boards":[
                  {"boardId":"board-1","boardKey":"one","boardName":"First Required","boardUrl":"https://trello.example/one","workflowPath":"%s","envPath":"%s","workspaceRoot":"%s","serverPort":18131,"githubEnabled":false,"additionalWritableRoots":[],"dangerFullAccess":false},
                  {"boardId":"board-2","boardKey":"two","boardName":"Second Required","boardUrl":"https://trello.example/two","workflowPath":"%s","envPath":"%s","workspaceRoot":"%s","serverPort":18132,"githubEnabled":false,"additionalWritableRoots":[],"dangerFullAccess":false}
                ]}
                """
                        .formatted(
                                json(firstWorkflow),
                                json(tempDir.resolve(".env.first-required-selector")),
                                json(tempDir.resolve("workspaces-one")),
                                json(secondWorkflow),
                                json(tempDir.resolve(".env.second-required-selector")),
                                json(tempDir.resolve("workspaces-two"))));

        // when
        SetupRunResult result = runSetup("--non-interactive", "--add-path", allowedPath.toString(), "--no-github");

        // then
        result.assertFailure(2).stderrContains("setup_board_selection_required", "Re-run with --board NAME");
        assertThat(firstWorkflow).content(StandardCharsets.UTF_8).doesNotContain(allowedPath.toString());
        assertThat(secondWorkflow).content(StandardCharsets.UTF_8).doesNotContain(allowedPath.toString());
    }

    @Test
    void rerunWithAddPathRejectsMixedWorkflowSetupOptionsForConnectedBoard() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.mixed-access-update.md");
        Path allowedPath = tempDir.resolve("mixed access path");
        writeWorkflow(workflow, "board-1", 18133);
        writeManifest(
                """
                {"boards":[
                  {"boardId":"board-1","boardKey":"one","boardName":"Mixed Access","boardUrl":"https://trello.example/one","workflowPath":"%s","envPath":"%s","workspaceRoot":"%s","serverPort":18133,"githubEnabled":false,"additionalWritableRoots":[],"dangerFullAccess":false}
                ]}
                """
                        .formatted(
                                json(workflow),
                                json(tempDir.resolve(".env.mixed-access-update")),
                                json(tempDir.resolve("workspaces-one"))));

        // when
        SetupRunResult result = runSetup(
                "--non-interactive", "--add-path", allowedPath.toString(), "--server-port", "19000", "--no-github");

        // then
        result.assertFailure(2).stderrContains("setup_mixed_codex_access_update", "--server-port");
        assertThat(workflow).content(StandardCharsets.UTF_8).doesNotContain(allowedPath.toString(), "19000");
        assertThat(commands.startedWorkflows).isEmpty();
        assertThat(commands.stoppedWorkflows).isEmpty();
    }

    @Test
    void nonInteractiveRerunRejectsWorkflowOptionsThatWouldBeIgnoredByKeep() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.ignored-rerun-option.md");
        Path env = tempDir.resolve(".env.ignored-rerun-option");
        SetupRunResult firstResult = runSetup(
                "--non-interactive",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--board-name",
                "Ignored Rerun Option",
                "--workflow",
                workflow.toString(),
                "--env",
                env.toString(),
                "--no-github");
        commands.startedWorkflows.clear();
        commands.stoppedWorkflows.clear();

        // when
        SetupRunResult result = runSetup("--non-interactive", "--github", "--server-port", "19000");

        // then
        firstResult.assertSuccess();
        result.assertFailure(2).stderrContains("setup_board_selection_required", "--server-port");
        assertThatWorkflow(workflow).doesNotHaveServerPort(19000);
        assertThat(commands.startedWorkflows).isEmpty();
        assertThat(commands.stoppedWorkflows).isEmpty();
    }

    @Test
    void rerunWithAddPathFailsBeforeChangingWorkflowWhenRunningWorkerIsUntracked() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.untracked-access-update.md");
        Path allowedPath = tempDir.resolve("untracked access path");
        writeWorkflow(workflow, "board-1", 18134);
        writeManifest(
                """
                {"boards":[
                  {"boardId":"board-1","boardKey":"one","boardName":"Untracked Access","boardUrl":"https://trello.example/one","workflowPath":"%s","envPath":"%s","workspaceRoot":"%s","serverPort":18134,"githubEnabled":false,"additionalWritableRoots":[],"dangerFullAccess":false}
                ]}
                """
                        .formatted(
                                json(workflow),
                                json(tempDir.resolve(".env.untracked-access-update")),
                                json(tempDir.resolve("workspaces-one"))));
        commands.startHealthServer(workflow, "board-1");
        when(workerManager.canStopManagedWorker(any(), any())).thenReturn(false);

        // when
        SetupRunResult result = runSetup("--non-interactive", "--add-path", allowedPath.toString(), "--no-github");

        // then
        result.assertFailure(2).stderrContains("setup_worker_untracked", "no managed pid");
        assertThat(workflow).content(StandardCharsets.UTF_8).doesNotContain(allowedPath.toString());
        assertThat(commands.startedWorkflows).isEmpty();
        assertThat(commands.stoppedWorkflows).isEmpty();
    }

    @Test
    void rerunWithAddPathAndUnknownBoardSelectorStillImportsThatBoard() throws Exception {
        // given
        Path firstWorkflow = tempDir.resolve("WORKFLOW.first-unknown-board.md");
        Path env = tempDir.resolve(".env.unknown-board");
        Path allowedPath = tempDir.resolve("new board path");
        SetupRunResult firstResult = runSetup(
                "--non-interactive",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--board-name",
                "First Unknown Selector Queue",
                "--workflow",
                firstWorkflow.toString(),
                "--env",
                env.toString(),
                "--no-github");
        commands.startedWorkflows.clear();
        trello.boardLookups().clear();

        // when
        SetupRunResult result = runSetup(
                "--non-interactive",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--board",
                "https://trello.com/b/newabc/imported",
                "--env",
                env.toString(),
                "--add-path",
                allowedPath.toString(),
                "--no-github");

        // then
        Path importedWorkflow = tempDir.resolve("config").resolve("WORKFLOW.imported-queue.md");
        firstResult.assertSuccess();
        result.assertSuccess().stdoutContains("Board connected: \"Imported Queue\"");
        assertThat(trello.boardLookups()).anySatisfy(path -> assertThat(path).contains("/1/boards/newabc"));
        assertThat(importedWorkflow).content(StandardCharsets.UTF_8).contains(allowedPath.toString());
    }
}
