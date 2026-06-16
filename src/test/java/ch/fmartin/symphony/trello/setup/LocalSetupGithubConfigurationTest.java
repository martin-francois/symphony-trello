package ch.fmartin.symphony.trello.setup;

import static ch.fmartin.symphony.trello.testsupport.ManifestAssertions.assertThatManifest;
import static ch.fmartin.symphony.trello.testsupport.WorkflowAssertions.assertThatWorkflow;
import static org.assertj.core.api.Assertions.assertThat;

import ch.fmartin.symphony.trello.testsupport.SetupRunResult;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class LocalSetupGithubConfigurationTest extends LocalSetupFixtureSupport {
    @Test
    void configureGithubWithExistingManifestUpgradesMatchingBoard() throws Exception {
        // given
        Path firstWorkflow = tempDir.resolve("WORKFLOW.local-first.md");
        Path env = tempDir.resolve(".env");
        SetupRunResult firstResult = connectLocalBoardWithoutGithub(firstWorkflow, env, "Local First");
        prepareNextSetupRunWithGithubAuth();

        // when
        SetupRunResult secondResult = runSetup(
                "--non-interactive",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--board",
                "https://trello.com/b/abc123/local-first",
                "configure-github");

        // then
        firstResult.assertSuccess();
        secondResult.assertSuccess().stdoutContains("GitHub workflow enabled for \"Local First\"");
        assertThat(trello.createdLists()).containsExactly("Merging");
        assertThatWorkflow(firstWorkflow).hasGithubFlow().hasMerging();
        assertThat(commands.stoppedWorkflows).containsExactly(firstWorkflow.toString());
        assertThat(commands.startedWorkflows).containsExactly(firstWorkflow.toString());
    }

    @Test
    void configureGithubPreservesExactUrlLikeBoardNameSelection() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.url-like-name.md");
        Path env = tempDir.resolve(".env");
        String boardName = "https://not-trello.com/b/team";
        SetupRunResult firstResult = connectLocalBoardWithoutGithub(workflow, env, boardName);
        prepareNextSetupRunWithGithubAuth();

        // when
        SetupRunResult secondResult = runSetup(
                "--non-interactive",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--board",
                boardName,
                "configure-github");

        // then
        firstResult.assertSuccess();
        secondResult.assertSuccess().stdoutContains("GitHub workflow enabled for \"" + boardName + "\"");
        assertThat(trello.createdLists()).containsExactly("Merging");
        assertThatWorkflow(workflow).hasGithubFlow().hasMerging();
    }

    @Test
    void githubAliasWithoutExplicitBoardUpgradesExistingNonGithubBoard() throws Exception {
        // given
        Path firstWorkflow = tempDir.resolve("WORKFLOW.local-first.md");
        Path env = tempDir.resolve(".env");
        SetupRunResult firstResult = connectLocalBoardWithoutGithub(firstWorkflow, env, "Local First");
        prepareNextSetupRunWithGithubAuth();

        // when
        SetupRunResult secondResult = runSetup("--non-interactive", "--endpoint", endpoint(), "--github");

        // then
        firstResult.assertSuccess();
        secondResult.assertSuccess().stdoutContains("GitHub workflow enabled for \"Local First\"");
        assertThat(trello.createdLists()).containsExactly("Merging");
        assertThatWorkflow(firstWorkflow).hasGithubFlow().hasMerging();
        assertThat(commands.stoppedWorkflows).containsExactly(firstWorkflow.toString());
        assertThat(commands.startedWorkflows).containsExactly(firstWorkflow.toString());
    }

    @Test
    void configureGithubWithNoStartDoesNotStopAlreadyRunningBoard() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.local-no-start.md");
        Path env = tempDir.resolve(".env");
        SetupRunResult firstResult = runSetup(
                "--non-interactive",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--board-name",
                "Local No Start",
                "--workflow",
                workflow.toString(),
                "--env",
                env.toString(),
                "--no-github");
        commands.githubAuthenticated = true;
        commands.startedWorkflows.clear();
        commands.startedEnvFiles.clear();
        commands.stoppedWorkflows.clear();
        trello.createdLists().clear();

        // when
        SetupRunResult result =
                runSetup("--non-interactive", "--endpoint", endpoint(), "configure-github", "--no-start");

        // then
        firstResult.assertSuccess();
        result.assertSuccess().stdoutContains("Restart skipped.");
        assertThatWorkflow(workflow).hasGithubFlow().hasMerging();
        assertThat(commands.stoppedWorkflows).isEmpty();
        assertThat(commands.startedWorkflows).isEmpty();
    }

    @Test
    void configureGithubWithAddPathUpgradesExistingBoardAndUpdatesCodexAccess() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.local-first.md");
        Path env = tempDir.resolve(".env");
        Path allowedPath = tempDir.resolve("github access path");
        SetupRunResult firstResult = runSetup(
                "--non-interactive",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--board-name",
                "Local First",
                "--workflow",
                workflow.toString(),
                "--env",
                env.toString(),
                "--no-github");
        commands.githubAuthenticated = true;
        commands.startedWorkflows.clear();
        commands.startedEnvFiles.clear();
        trello.createdLists().clear();

        // when
        SetupRunResult secondResult = runSetup(
                "--non-interactive",
                "--endpoint",
                endpoint(),
                "configure-github",
                "--add-path",
                allowedPath.toString());

        // then
        firstResult.assertSuccess();
        secondResult.assertSuccess();
        assertThat(trello.createdLists()).containsExactly("Merging");
        assertThatWorkflow(workflow).hasGithubFlow().hasMerging().hasAdditionalWritableRoot(allowedPath);
        assertThatManifest(tempDir.resolve("config/connected-boards.json"))
                .hasGithubEnabled("Local First")
                .hasAdditionalWritableRoot("Local First", allowedPath);
        assertThat(commands.stoppedWorkflows).containsExactly(workflow.toString());
        assertThat(commands.startedWorkflows).containsExactly(workflow.toString());
    }

    @Test
    void configureGithubWithAddPathUpdatesExistingGithubBoardCodexAccess() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.github-configure-access-rerun.md");
        Path env = tempDir.resolve(".env.github-configure-access-rerun");
        Path allowedPath = tempDir.resolve("github configure allowed path");
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
                "GitHub Configure Access Rerun Queue",
                "--workflow",
                workflow.toString(),
                "--env",
                env.toString(),
                "--github");
        commands.startedWorkflows.clear();
        commands.stoppedWorkflows.clear();
        trello.createdLists().clear();

        // when
        SetupRunResult result = runSetup("--non-interactive", "configure-github", "--add-path", allowedPath.toString());

        // then
        firstResult.assertSuccess();
        result.assertSuccess().stdoutContains("Updated workflow: " + workflow);
        assertThat(trello.createdLists()).isEmpty();
        assertThatWorkflow(workflow).hasAdditionalWritableRoot(allowedPath);
        assertThatManifest(tempDir.resolve("config/connected-boards.json"))
                .hasGithubEnabled("GitHub Configure Access Rerun Queue")
                .hasAdditionalWritableRoot("GitHub Configure Access Rerun Queue", allowedPath);
        assertThat(commands.stoppedWorkflows).containsExactly(workflow.toString());
        assertThat(commands.startedWorkflows).containsExactly(workflow.toString());
    }

    @Test
    void configureGithubWithSelectedGithubBoardAndNonGithubBoardUpdatesSelectedBoardCodexAccess() throws Exception {
        // given
        Path githubWorkflow = tempDir.resolve("WORKFLOW.github-selected-access.md");
        Path localWorkflow = tempDir.resolve("WORKFLOW.local-selected-access.md");
        Path allowedPath = tempDir.resolve("selected github access path");
        writeWorkflow(githubWorkflow, "github-board", 18141);
        writeWorkflow(localWorkflow, "local-board", 18142);
        writeManifest(
                """
                {"boards":[
                  {"boardId":"github-board","boardKey":"gh","boardName":"GitHub Queue","boardUrl":"https://trello.example/gh","workflowPath":"%s","envPath":"%s","workspaceRoot":"%s","serverPort":18141,"githubEnabled":true,"additionalWritableRoots":[],"dangerFullAccess":false},
                  {"boardId":"local-board","boardKey":"local","boardName":"Local Queue","boardUrl":"https://trello.example/local","workflowPath":"%s","envPath":"%s","workspaceRoot":"%s","serverPort":18142,"githubEnabled":false,"additionalWritableRoots":[],"dangerFullAccess":false}
                ]}
                """
                        .formatted(
                                json(githubWorkflow),
                                json(tempDir.resolve(".env.github-selected-access")),
                                json(tempDir.resolve("workspaces-gh")),
                                json(localWorkflow),
                                json(tempDir.resolve(".env.local-selected-access")),
                                json(tempDir.resolve("workspaces-local"))));
        commands.githubAuthenticated = true;

        // when
        SetupRunResult result = runSetup(
                "--non-interactive",
                "configure-github",
                "--board",
                "GitHub Queue",
                "--add-path",
                allowedPath.toString());

        // then
        result.assertSuccess()
                .stdoutContains("Updated workflow: " + githubWorkflow)
                .stdoutDoesNotContain("GitHub workflow enabled for \"Local Queue\"");
        assertThat(githubWorkflow).content(StandardCharsets.UTF_8).contains(allowedPath.toString());
        assertThat(localWorkflow).content(StandardCharsets.UTF_8).doesNotContain(allowedPath.toString());
        assertThat(trello.createdLists()).isEmpty();
    }

    @Test
    void rerunWithGithubSelectedGithubBoardAndAddPathUpdatesSelectedBoardCodexAccess() throws Exception {
        // given
        Path githubWorkflow = tempDir.resolve("WORKFLOW.github-selected-flag-access.md");
        Path localWorkflow = tempDir.resolve("WORKFLOW.local-selected-flag-access.md");
        Path allowedPath = tempDir.resolve("selected github flag access path");
        writeWorkflow(githubWorkflow, "github-board", 18143);
        writeWorkflow(localWorkflow, "local-board", 18144);
        writeManifest(
                """
                {"boards":[
                  {"boardId":"github-board","boardKey":"gh","boardName":"GitHub Flag Queue","boardUrl":"https://trello.example/gh","workflowPath":"%s","envPath":"%s","workspaceRoot":"%s","serverPort":18143,"githubEnabled":true,"additionalWritableRoots":[],"dangerFullAccess":false},
                  {"boardId":"local-board","boardKey":"local","boardName":"Local Flag Queue","boardUrl":"https://trello.example/local","workflowPath":"%s","envPath":"%s","workspaceRoot":"%s","serverPort":18144,"githubEnabled":false,"additionalWritableRoots":[],"dangerFullAccess":false}
                ]}
                """
                        .formatted(
                                json(githubWorkflow),
                                json(tempDir.resolve(".env.github-selected-flag-access")),
                                json(tempDir.resolve("workspaces-gh")),
                                json(localWorkflow),
                                json(tempDir.resolve(".env.local-selected-flag-access")),
                                json(tempDir.resolve("workspaces-local"))));
        commands.githubAuthenticated = true;

        // when
        SetupRunResult result = runSetup(
                "--non-interactive", "--github", "--board", "GitHub Flag Queue", "--add-path", allowedPath.toString());

        // then
        result.assertSuccess().stdoutContains("Updated workflow: " + githubWorkflow);
        assertThat(githubWorkflow).content(StandardCharsets.UTF_8).contains(allowedPath.toString());
        assertThat(localWorkflow).content(StandardCharsets.UTF_8).doesNotContain(allowedPath.toString());
        assertThat(trello.boardLookups()).isEmpty();
        assertThat(trello.createdLists()).isEmpty();
    }

    @Test
    void configureGithubWithExistingManifestPreservesImportedCustomLists() throws Exception {
        // given
        Path config = tempDir.resolve("config");
        Files.createDirectories(config);
        Path workflow = config.resolve("WORKFLOW.custom.md");
        Path env = config.resolve(".env.custom");
        Path manifest = config.resolve("connected-boards.json");
        int port = availablePort();
        Files.writeString(env, "TRELLO_API_KEY=key\nTRELLO_API_TOKEN=token\n", StandardCharsets.UTF_8);
        Files.writeString(
                workflow,
                """
                ---
                tracker:
                  kind: trello
                  board_id: board-1
                  active_states:
                    - Custom Ready
                    - Custom Doing
                  in_progress_state: Custom Doing
                  terminal_states:
                    - Custom Done
                workspace:
                  root: %s
                server:
                  port: %d
                codex:
                  command: codex app-server
                agent:
                  max_concurrent_agents: 4
                ---
                # Custom workflow

                - "Custom Blocked": blocked work. Symphony does not dispatch it while this list is not configured as active.
                """
                        .formatted(tempDir.resolve("workspaces"), port),
                StandardCharsets.UTF_8);
        Files.writeString(
                manifest,
                """
                {
                  "boards": [
                    {
                      "boardId": "board-1",
                      "boardKey": "abc123",
                      "boardName": "Custom Board",
                      "boardUrl": "https://trello.com/b/abc123/board",
                      "workflowPath": "%s",
                      "envPath": "%s",
                      "workspaceRoot": "%s",
                      "serverPort": %d,
                      "githubEnabled": false,
                      "additionalWritableRoots": [],
                      "dangerFullAccess": false
                    }
                  ]
                }
                """
                        .formatted(workflow, env, tempDir.resolve("workspaces"), port),
                StandardCharsets.UTF_8);
        trello.givenRawBoardListsJson(
                """
                [
                  {"id":"list-1","name":"Custom Ready","closed":false,"pos":1},
                  {"id":"list-2","name":"Custom Doing","closed":false,"pos":2},
                  {"id":"list-3","name":"Custom Blocked","closed":false,"pos":3},
                  {"id":"list-4","name":"Custom Done","closed":false,"pos":4}
                ]
                """);
        commands.githubAuthenticated = true;
        commands.startedWorkflows.add(workflow.toString());
        commands.startHealthServer(workflow);

        // when
        SetupRunResult result = runSetup("--non-interactive", "--endpoint", endpoint(), "configure-github");

        // then
        result.assertSuccess().stdoutContains("GitHub workflow enabled for \"Custom Board\"");
        assertThat(trello.createdLists()).containsExactly("Merging");
        assertThat(workflow)
                .content(StandardCharsets.UTF_8)
                .contains(
                        "- \"Custom Ready\"",
                        "- \"Custom Doing\"",
                        "blocked_state: \"Custom Blocked\"",
                        "- \"Custom Done\"",
                        "- \"Merging\"",
                        "max_concurrent_agents: 4");
        assertThat(commands.stoppedWorkflows).containsExactly(workflow.toString());
        assertThat(commands.startedWorkflows).containsExactly(workflow.toString(), workflow.toString());
    }

    @Test
    void configureGithubPreservesDisabledInProgressRouting() throws Exception {
        // given
        Path config = tempDir.resolve("config");
        Files.createDirectories(config);
        Path workflow = config.resolve("WORKFLOW.no-progress.md");
        Path env = config.resolve(".env.no-progress");
        Path manifest = config.resolve("connected-boards.json");
        int port = availablePort();
        Files.writeString(env, "TRELLO_API_KEY=key\nTRELLO_API_TOKEN=token\n", StandardCharsets.UTF_8);
        Files.writeString(
                workflow,
                """
                ---
                tracker:
                  kind: trello
                  board_id: board-1
                  active_states:
                    - Ready for Codex
                  terminal_states:
                    - Done
                workspace:
                  root: %s
                server:
                  port: %d
                codex:
                  command: codex app-server
                agent:
                  max_concurrent_agents: 2
                ---
                # Workflow without in-progress routing
                """
                        .formatted(tempDir.resolve("workspaces"), port),
                StandardCharsets.UTF_8);
        Files.writeString(
                manifest,
                """
                {
                  "boards": [
                    {
                      "boardId": "board-1",
                      "boardKey": "abc123",
                      "boardName": "No Progress Board",
                      "boardUrl": "https://trello.com/b/abc123/board",
                      "workflowPath": "%s",
                      "envPath": "%s",
                      "workspaceRoot": "%s",
                      "serverPort": %d,
                      "githubEnabled": false,
                      "additionalWritableRoots": [],
                      "dangerFullAccess": false
                    }
                  ]
                }
                """
                        .formatted(workflow, env, tempDir.resolve("workspaces"), port),
                StandardCharsets.UTF_8);
        trello.givenRawBoardListsJson(
                """
                [
                  {"id":"list-1","name":"Ready for Codex","closed":false,"pos":1},
                  {"id":"list-2","name":"In Progress","closed":false,"pos":2},
                  {"id":"list-3","name":"Done","closed":false,"pos":3}
                ]
                """);
        commands.githubAuthenticated = true;
        commands.startedWorkflows.add(workflow.toString());
        commands.startHealthServer(workflow);

        // when
        SetupRunResult result = runSetup("--non-interactive", "--endpoint", endpoint(), "configure-github");

        // then
        result.assertSuccess().stdoutContains("GitHub workflow enabled for \"No Progress Board\"");
        assertThat(workflow)
                .content(StandardCharsets.UTF_8)
                .contains("- \"Ready for Codex\"", "- \"Done\"", "- \"Merging\"", "max_concurrent_agents: 2")
                .doesNotContain("in_progress_state:", "- \"In Progress\"");
    }
}
