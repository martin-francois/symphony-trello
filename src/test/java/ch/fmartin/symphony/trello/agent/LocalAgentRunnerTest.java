package ch.fmartin.symphony.trello.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.fmartin.symphony.trello.TestCards;
import ch.fmartin.symphony.trello.config.ConfigResolver;
import ch.fmartin.symphony.trello.config.EffectiveConfig;
import ch.fmartin.symphony.trello.domain.Card;
import ch.fmartin.symphony.trello.prompt.PromptRenderer;
import ch.fmartin.symphony.trello.tracker.CardLookupResult;
import ch.fmartin.symphony.trello.tracker.TrackerClient;
import ch.fmartin.symphony.trello.workflow.WorkflowDefinition;
import ch.fmartin.symphony.trello.workspace.CodexSkillInstaller;
import ch.fmartin.symphony.trello.workspace.HookRunner;
import ch.fmartin.symphony.trello.workspace.WorkspaceManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;

final class LocalAgentRunnerTest {
    @TempDir
    Path tempDir;

    @Test
    void createsWorkspaceRunsHooksAndPassesPromptToCodexClient() throws Exception {
        // given
        CodexAppServerClient codex = mock();
        when(codex.runSession(any(), any(), any(), any(), any(), any(), any())).thenReturn(AgentRunResult.ok());
        var runner = runner(codex, CardLookupResult.Found::new);
        EffectiveConfig config = config(Map.of(
                "before_run", "rm -rf .codex && echo before > before.txt", "after_run", "echo after > after.txt"));
        Card card = TestCards.card("card-1", "TRELLO-local", "Ready for Codex");
        String renderedPrompt = "Use " + CodexSkillInstaller.installedSkillPath("commit");

        // when
        AgentRunResult result = runner.run(
                new AgentRunner.AgentRunRequest(card, null, renderedPrompt, config, "worker-success", event -> {}));

        // then
        ArgumentCaptor<Path> workspace = ArgumentCaptor.forClass(Path.class);
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        Path expectedWorkspace = config.workspace().root().resolve("TRELLO-local");
        assertThat(result).isEqualTo(AgentRunResult.ok());
        verify(codex)
                .runSession(
                        eq(config),
                        eq(card),
                        workspace.capture(),
                        prompt.capture(),
                        eq("worker-success"),
                        any(),
                        any());
        assertThat(prompt.getValue())
                .startsWith(renderedPrompt)
                .contains("## Repository Source Context")
                .contains("No workflow repository default is configured");
        assertThat(workspace.getValue())
                .isEqualTo(expectedWorkspace.toAbsolutePath().normalize());
        assertThat(Files.readString(expectedWorkspace.resolve("before.txt"))).contains("before");
        assertThat(Files.readString(expectedWorkspace.resolve("after.txt"))).contains("after");
        assertThat(expectedWorkspace.resolve(CodexSkillInstaller.installedSkillPath("commit")))
                .content()
                .contains("configure it from the authenticated GitHub login");
    }

    @Test
    void passesResolvedLiteralUrlDefaultToCodexPrompt() {
        // given
        String repositoryUrl = "https://example.invalid/team/project.git";
        EffectiveConfig config = configWithRepository(Map.of("default_url", repositoryUrl));

        // when
        String prompt = promptPassedToCodex(config, "Card task");

        // then
        assertThat(prompt)
                .contains("## Repository Source Context")
                .contains("- Selected by: workflow repository.default_url")
                .contains("- Type: URL")
                .contains("- Credential-free remote: " + repositoryUrl)
                .contains("only when the current Trello card supplies no explicit source")
                .contains("no unambiguous repository identity in ordinary task context");
    }

    @Test
    void passesResolvedEnvironmentUrlDefaultToCodexPrompt() {
        // given
        String repositoryUrl = "ssh://git@example.invalid/team/project.git";
        EffectiveConfig config =
                configWithRepository(Map.of("default_url", "$REPOSITORY_URL"), Map.of("REPOSITORY_URL", repositoryUrl));

        // when
        String prompt = promptPassedToCodex(config, "Card task");

        // then
        assertThat(prompt).contains("- Type: URL").contains("- Credential-free remote: " + repositoryUrl);
    }

    @Test
    void passesResolvedRelativePathDefaultToCodexPrompt() {
        // given
        Path workflow = tempDir.resolve("configs/WORKFLOW.md");
        Path expectedPath = workflow.getParent()
                .resolve("checkouts/project")
                .toAbsolutePath()
                .normalize();
        EffectiveConfig config = configWithRepository(workflow, Map.of("default_path", "checkouts/project"), Map.of());

        // when
        String prompt = promptPassedToCodex(config, "Card task");

        // then
        assertThat(prompt).contains("- Type: local path").contains("- Resolved local path: " + expectedPath);
    }

    @Test
    void passesResolvedAbsolutePathDefaultToCodexPrompt() {
        // given
        Path repositoryPath =
                tempDir.resolve("absolute-repository").toAbsolutePath().normalize();
        EffectiveConfig config = configWithRepository(Map.of("default_path", repositoryPath.toString()));

        // when
        String prompt = promptPassedToCodex(config, "Card task");

        // then
        assertThat(prompt).contains("- Type: local path").contains("- Resolved local path: " + repositoryPath);
    }

    @Test
    void passesResolvedEnvironmentPathDefaultToCodexPrompt() {
        // given
        Path repositoryPath =
                tempDir.resolve("environment-repository").toAbsolutePath().normalize();
        EffectiveConfig config = configWithRepository(
                Map.of("default_path", "$REPOSITORY_PATH"), Map.of("REPOSITORY_PATH", repositoryPath.toString()));

        // when
        String prompt = promptPassedToCodex(config, "Card task");

        // then
        assertThat(prompt).contains("- Type: local path").contains("- Resolved local path: " + repositoryPath);
    }

    @Test
    void includesUrlIdentityAndConfiguredPathCheckoutCandidateWhenBothAreConfigured() {
        // given
        String repositoryUrl = "https://example.invalid/team/project.git";
        Path lowerPriorityPath =
                tempDir.resolve("lower-priority-repository").toAbsolutePath().normalize();
        EffectiveConfig config = configWithRepository(
                Map.of("default_url", repositoryUrl, "default_path", lowerPriorityPath.toString()));

        // when
        String prompt = promptPassedToCodex(config, "Card task");

        // then
        assertThat(prompt)
                .contains("- Type: URL")
                .contains("- Credential-free remote: " + repositoryUrl)
                .doesNotContain("- Type: local path")
                .contains("- Configured checkout path candidate: " + lowerPriorityPath)
                .contains("use that configured path before\n   searching for another local checkout")
                .contains(
                        "only when read-only remote inspection confirms that it\n   matches the selected repository identity");
        assertThat(config.repository().defaultPath()).isEqualTo(lowerPriorityPath);
    }

    @MethodSource("missingOrBlankUrlDefaults")
    @ParameterizedTest(name = "{0}")
    void usesPathWhenEnvironmentUrlDefaultIsMissingOrBlank(String name, Map<String, String> environment) {
        // given
        Path repositoryPath = tempDir.resolve(name).toAbsolutePath().normalize();
        EffectiveConfig config = configWithRepository(
                Map.of("default_url", "$REPOSITORY_URL", "default_path", repositoryPath.toString()), environment);

        // when
        String prompt = promptPassedToCodex(config, "Card task");

        // then
        assertThat(prompt)
                .contains("- Type: local path")
                .contains("- Resolved local path: " + repositoryPath)
                .doesNotContain("- Type: URL");
    }

    @MethodSource("missingOrBlankRepositoryDefaults")
    @ParameterizedTest(name = "{0}")
    void reportsNoDefaultWhenEnvironmentUrlAndPathDefaultsAreMissingOrBlank(
            String ignored, Map<String, String> environment) {
        // given
        EffectiveConfig config = configWithRepository(
                Map.of("default_url", "$REPOSITORY_URL", "default_path", "$REPOSITORY_PATH"), environment);

        // when
        String prompt = promptPassedToCodex(config, "Card task");

        // then
        assertThat(prompt)
                .contains("No workflow repository default is configured")
                .doesNotContain("Resolved URL:")
                .doesNotContain("Resolved local path:");
    }

    @Test
    void fullyQualifiedIssueTargetWithoutRepositoryDefaultIsNotARepositorySourceBlocker() {
        // given
        String issueTarget = "https://github.example/team/project/issues/123";
        EffectiveConfig config = configWithRepository(Map.of());
        Card card = TestCards.cardWithText(
                "card-1",
                "TRELLO-direct-issue-target",
                "Ready for Codex",
                "Update the linked issue",
                "Add a status note to " + issueTarget + ". Do not change repository files.",
                List.of());

        // when
        String prompt = promptPassedToCodex(config, "Task target: " + issueTarget, card);

        // then
        assertThat(prompt)
                .contains("Task target: " + issueTarget)
                .contains("- Status: no selected source")
                .contains("A fully qualified GitHub issue or pull request URL is a direct external target")
                .contains("do not require a repository checkout for that API action")
                .contains("The absence of a selected repository is not a blocker by itself");
    }

    @Test
    void repositoryChangingCardReceivesCompleteCheckoutPreparationOrder() {
        // given
        EffectiveConfig config = configWithRepository(Map.of());
        String cardTask = "Change README.md in https://github.example/target/project and prepare a pull request.";

        // when
        String prompt = promptPassedToCodex(config, cardTask);

        // then
        String normalizedPrompt = prompt.replaceAll("\\s+", " ");
        assertThat(normalizedPrompt)
                .containsSubsequence(
                        "## Repository Checkout Preparation",
                        "If a configured checkout path candidate is shown above",
                        "Otherwise, search the local checkouts that this run can access",
                        "If one matching local repository exists, reuse it and do not clone the repository again",
                        "If no matching local repository exists, clone the selected repository",
                        "fetch the remote default branch before creating the task worktree",
                        "Create a separate task worktree",
                        "freshly fetched remote default branch")
                .contains("Match a local checkout by repository identity from its configured Git remotes")
                .contains(
                        "Do not match by directory name, current branch, proximity, prior cards, or workspace residue")
                .contains("If the card explicitly requests another branch, ref, base, or checkout arrangement")
                .contains("follow that instruction instead of the default-branch worktree behavior");
    }

    @Test
    void runtimeNoSourceContextSupersedesLegacyUnconditionalMissingSourceBlocker() {
        // given
        String persistedLegacyPrompt =
                """
                If no source is selected or the selected source is missing, unreadable, unclonable, or lacks required
                repository/auth context, move the Trello card to `Blocked` with path-safe guidance instead of
                guessing.

                Summarize the supplied release schedule. No repository action.
                """
                        .strip();
        EffectiveConfig config = configWithRepository(Map.of());

        // when
        String prompt = promptPassedToCodex(config, persistedLegacyPrompt);

        // then
        assertThat(prompt)
                .startsWith(persistedLegacyPrompt)
                .containsSubsequence(
                        "If no source is selected or the selected source is missing",
                        "## Repository Source Context",
                        "- Status: no selected source",
                        "This final runtime repository-source context is authoritative",
                        "Ignore any earlier unconditional instruction to block solely because no repository source is selected",
                        "Classify the current task before deciding whether the missing source is a blocker")
                .contains("Repository-independent work without repository-relative references can run");
    }

    @MethodSource("repositoryNeedDecisionScenarios")
    @ParameterizedTest(name = "{0}")
    void repositoryNeedDecisionTableReachesAgentBoundary(
            String scenario,
            String cardTask,
            Map<String, Object> repository,
            String expectedStatus,
            List<String> expectedInstructions) {
        // given
        EffectiveConfig config = configWithRepository(repository);

        // when
        String prompt = promptPassedToCodex(config, cardTask);

        // then
        assertThat(prompt)
                .as(scenario)
                .contains(cardTask)
                .contains(expectedStatus)
                .contains(expectedInstructions.toArray(String[]::new));
    }

    @MethodSource("directTargetsWithMalformedFallback")
    @ParameterizedTest(name = "{0}")
    void directApiTargetOverridesMalformedWorkflowFallbackWithoutCheckout(String scenario, String cardTask) {
        // given
        EffectiveConfig config = configWithRepository(Map.of("default_url", "ftp://source.example/team/default.git"));

        // when
        String prompt = promptPassedToCodex(config, cardTask);

        // then
        assertThat(prompt)
                .as(scenario)
                .contains(cardTask)
                .contains("- Status: invalid workflow fallback")
                .contains("exactly one unambiguous repository identity")
                .contains("ignore this unused workflow fallback")
                .contains("A fully qualified GitHub issue or pull request URL is a direct external target")
                .contains("For an API-only action, act directly on that target and do not create a checkout")
                .doesNotContain("ftp://source.example/team/default.git");
    }

    @MethodSource("repositoryChangingIdentityFormsWithMalformedFallback")
    @ParameterizedTest(name = "{0}")
    void repositoryChangingCardIdentityOverridesMalformedWorkflowFallback(String scenario, String cardTask) {
        // given
        EffectiveConfig config = configWithRepository(Map.of("default_url", "ftp://source.example/team/default.git"));

        // when
        String prompt = promptPassedToCodex(config, cardTask);

        // then
        assertThat(prompt)
                .as(scenario)
                .contains(cardTask)
                .contains("- Status: invalid workflow fallback")
                .contains("ignore this unused workflow fallback and use the card repository identity")
                .contains("full repository, issue, pull request, or file URL; owner/repository notation")
                .contains("prepare a checkout only because the requested work needs repository files")
                .doesNotContain("ftp://source.example/team/default.git");
    }

    @Test
    void malformedWorkflowUrlWithNoCardIdentityBlocksAndDoesNotPromoteConfiguredPath() {
        // given
        Path defaultPath =
                tempDir.resolve("lower-priority-checkout").toAbsolutePath().normalize();
        EffectiveConfig config = configWithRepository(
                Map.of("default_url", "ftp://source.example/team/default.git", "default_path", defaultPath.toString()));
        String cardTask = "Add a status note to #123. Do not change files.";

        // when
        String prompt = promptPassedToCodex(config, cardTask);

        // then
        assertThat(prompt)
                .contains(cardTask)
                .contains("- Status: invalid workflow fallback")
                .contains("If the card supplies no repository identity, block unconditionally")
                .contains("even when the task would otherwise be repository-independent")
                .contains("A lower-priority configured path never establishes repository identity")
                .contains("Do not promote it over this invalid selected workflow URL")
                .contains("- Configured checkout path candidate: " + defaultPath)
                .contains("only after card context supplies exactly one repository identity")
                .doesNotContain("if this fallback is required");
    }

    @Test
    void conflictingCardIdentitiesBlockInsteadOfOverridingMalformedWorkflowFallback() {
        // given
        EffectiveConfig config = configWithRepository(Map.of("default_url", "ftp://source.example/team/default.git"));
        String cardTask = "Change files in https://github.example/team/one and https://github.example/team/two.";

        // when
        String prompt = promptPassedToCodex(config, cardTask);

        // then
        assertThat(prompt)
                .contains(cardTask)
                .contains("If card context supplies conflicting repository identities, block")
                .contains("Do not select one arbitrarily")
                .doesNotContain("if this fallback is required");
    }

    @Test
    void fullyQualifiedIssueTargetRemainsDirectWhenDefaultNamesAnotherRepository() {
        // given
        String issueTarget = "https://github.example/target/project/issues/123";
        EffectiveConfig config = configWithRepository(Map.of("default_url", "https://source.example/team/default.git"));

        // when
        String prompt = promptPassedToCodex(config, "Add a status note to " + issueTarget + ". No file changes.");

        // then
        assertThat(prompt)
                .contains("Add a status note to " + issueTarget)
                .contains("- Repository identity: source.example/team/default")
                .contains("A fully qualified GitHub issue or pull request URL remains its own direct target")
                .contains("even when it names a repository other than the selected source")
                .contains(
                        "Do not create or reuse a task checkout unless the classified task requires repository files");
    }

    @Test
    void defaultPathRequiresAnExplicitUnambiguousCompatibleRemoteBeforeResolvingShorthand() {
        // given
        Path repositoryPath = tempDir.resolve("local-default").toAbsolutePath().normalize();
        EffectiveConfig config = configWithRepository(Map.of("default_path", repositoryPath.toString()));

        // when
        String prompt = promptPassedToCodex(config, "Add a status note to #123. Do not change files.");

        // then
        assertLocalSourceIdentityPolicy(prompt);
    }

    @Test
    void fileUrlRequiresAnExplicitUnambiguousCompatibleRemoteBeforeResolvingShorthand() {
        // given
        String repositoryUrl = tempDir.resolve("file-url-default")
                .toAbsolutePath()
                .normalize()
                .toUri()
                .toString();
        EffectiveConfig config = configWithRepository(Map.of("default_url", repositoryUrl));

        // when
        String prompt = promptPassedToCodex(config, "Add a status note to #123. Do not change files.");

        // then
        assertLocalSourceIdentityPolicy(prompt);
    }

    @Test
    void explicitRemoteKeepsConfiguredPathCandidateButSuppressesDifferentWorkflowUrl() {
        // given
        String cardRepository = "https://example.invalid/card-specific.git";
        Path defaultPath = tempDir.resolve("default-checkout");
        EffectiveConfig config = configWithRepository(
                Map.of("default_url", "https://example.invalid/default.git", "default_path", defaultPath.toString()));
        Card card = TestCards.cardWithText(
                "card-1",
                "TRELLO-explicit-source",
                "Ready for Codex",
                "Implement feature",
                "Repository URL: " + cardRepository,
                List.of());

        // when
        String prompt = promptPassedToCodex(config, "Card task", card);

        // then
        assertThat(prompt)
                .startsWith("Card task")
                .contains("- Selected by: explicit Trello card source")
                .contains("- Credential-free remote: " + cardRepository)
                .contains("Explicit Trello card repository sources are authoritative and suppress workflow defaults")
                .doesNotContain("https://example.invalid/default.git")
                .contains("- Configured checkout path candidate: "
                        + defaultPath.toAbsolutePath().normalize())
                .contains("never establishes or replaces repository identity")
                .contains("only when read-only inspection of its Git remotes confirms")
                .contains("matches the repository identity already selected from the card")
                .contains(
                        "Do not assume that it matches merely because the path and a workflow URL were configured together");
    }

    @Test
    void explicitRemoteKeepsMatchingConfiguredPathCandidateAtFinalPromptBoundary() {
        // given
        String cardRepository = "https://example.invalid/team/project.git";
        Path defaultPath = tempDir.resolve("matching-checkout");
        EffectiveConfig config =
                configWithRepository(Map.of("default_url", cardRepository, "default_path", defaultPath.toString()));
        Card card = TestCards.cardWithText(
                "card-1",
                "TRELLO-explicit-matching-source",
                "Ready for Codex",
                "Implement feature",
                "Repository URL: " + cardRepository,
                List.of());

        // when
        String prompt = promptPassedToCodex(config, "Card task", card);

        // then
        assertThat(prompt)
                .contains("- Selected by: explicit Trello card source")
                .contains("- Credential-free remote: " + cardRepository)
                .contains("- Configured checkout path candidate: "
                        + defaultPath.toAbsolutePath().normalize())
                .containsSubsequence(
                        "Configured checkout path candidate",
                        "use that configured path before",
                        "searching for another local checkout")
                .contains("Never use a configured path for a different repository");
    }

    @Test
    void explicitLocalSourceDoesNotReceiveASecondPathCandidateAtFinalPromptBoundary() {
        // given
        Path selectedPath =
                tempDir.resolve("selected-checkout").toAbsolutePath().normalize();
        Path defaultPath =
                tempDir.resolve("configured-checkout").toAbsolutePath().normalize();
        EffectiveConfig config = configWithRepository(Map.of("default_path", defaultPath.toString()));
        Card card = TestCards.cardWithText(
                "card-1",
                "TRELLO-explicit-local-source",
                "Ready for Codex",
                "Implement feature",
                "Repository path: " + selectedPath,
                List.of());

        // when
        String prompt = promptPassedToCodex(config, "Card task", card);

        // then
        assertThat(prompt)
                .contains("- Selected by: explicit Trello card source")
                .contains("- Resolved local path: " + selectedPath)
                .doesNotContain("Configured checkout path candidate")
                .doesNotContain(defaultPath.toString());
    }

    @Test
    void invalidExplicitCardSourceDoesNotFallBackToWorkflowDefaultInPrompt() {
        // given
        Path defaultPath =
                tempDir.resolve("configured-checkout").toAbsolutePath().normalize();
        EffectiveConfig config = configWithRepository(
                Map.of("default_url", "https://example.invalid/default.git", "default_path", defaultPath.toString()));
        Card card = TestCards.cardWithText(
                "card-1",
                "TRELLO-invalid-source",
                "Ready for Codex",
                "Implement feature",
                "Repository URL: ftp://example.invalid/team/project.git",
                List.of());

        // when
        String prompt = promptPassedToCodex(config, "Card task", card);

        // then
        assertThat(prompt)
                .contains("- Status: invalid selected source")
                .contains("- Code: repository_remote_unsupported")
                .contains("Do not use workflow defaults or other lower-priority fallbacks")
                .doesNotContain("https://example.invalid/default.git")
                .doesNotContain(defaultPath.toString())
                .doesNotContain("Configured checkout path candidate")
                .doesNotContain("## Repository Checkout Preparation");
    }

    @Test
    void explicitCardSourceSuppressesCredentialBearingWorkflowDefaultInPrompt() {
        // given
        String secretDefault = "https://token@example.invalid/team/default.git?access_token=secret";
        EffectiveConfig config = configWithRepository(Map.of("default_url", secretDefault));
        Card card = TestCards.cardWithText(
                "card-1",
                "TRELLO-secret-default",
                "Ready for Codex",
                "Implement feature",
                "Repository URL: https://example.invalid/card.git",
                List.of());

        // when
        String prompt = promptPassedToCodex(config, "Card task", card);

        // then
        assertThat(prompt)
                .contains("- Credential-free remote: https://example.invalid/card.git")
                .doesNotContain("token")
                .doesNotContain("access_token")
                .doesNotContain("secret");
    }

    @Test
    void doesNotCreateOrInspectRepositoryPathWhileAddingRuntimePromptContext() {
        // given
        Path missingParent = tempDir.resolve("missing-parent");
        Path repositoryPath = missingParent.resolve("repository");
        EffectiveConfig config = configWithRepository(Map.of("default_path", repositoryPath.toString()));
        assertThat(missingParent).doesNotExist();

        // when
        String prompt = promptPassedToCodex(config, "Card task");

        // then
        assertThat(prompt)
                .contains("- Resolved local path: "
                        + repositoryPath.toAbsolutePath().normalize());
        assertThat(missingParent).doesNotExist();
    }

    @Test
    void runtimePromptWarnsAgainstEchoingResolvedPrivateDefaultsToTrello() {
        // given
        String privateRepositoryUrl = "https://private.example.invalid/team/project.git";
        EffectiveConfig config = configWithRepository(Map.of("default_url", privateRepositoryUrl));

        // when
        String prompt = promptPassedToCodex(config, "Card task");

        // then
        assertThat(prompt)
                .contains("- Credential-free remote: " + privateRepositoryUrl)
                .contains(
                        "Do not copy private repository URLs, local host paths, credentials, or source details from this context into Trello-visible comments or status text.");
    }

    @Test
    void runtimePromptWarnsAgainstEchoingResolvedPrivatePathsToTrello() {
        // given
        Path privateRepositoryPath =
                tempDir.resolve("private/project").toAbsolutePath().normalize();
        EffectiveConfig config = configWithRepository(Map.of("default_path", privateRepositoryPath.toString()));

        // when
        String prompt = promptPassedToCodex(config, "Card task");

        // then
        assertThat(prompt)
                .contains("- Resolved local path: " + privateRepositoryPath)
                .contains(
                        "Do not copy private repository URLs, local host paths, credentials, or source details from this context into Trello-visible comments or status text.");
    }

    @Test
    void credentialBearingWorkflowDefaultIsSanitizedInCodexPrompt() {
        // given
        String secretDefault = "https://token@example.invalid/team/project.git?access_token=secret";
        EffectiveConfig config = configWithRepository(Map.of("default_url", secretDefault));

        // when
        String prompt = promptPassedToCodex(config, "Card task");

        // then
        assertThat(prompt)
                .contains("- Status: invalid workflow fallback")
                .contains("- Code: repository_remote_credentials_unsupported")
                .contains("Use a credential helper or SSH")
                .doesNotContain("token")
                .doesNotContain("access_token")
                .doesNotContain("secret");
    }

    @Test
    void leavesWorkspaceEmptyWhenPromptDoesNotReferenceBundledSkills() throws Exception {
        // given
        CodexAppServerClient codex = mock();
        when(codex.runSession(any(), any(), any(), any(), any(), any(), any())).thenReturn(AgentRunResult.ok());
        var runner = runner(codex, CardLookupResult.Found::new);
        EffectiveConfig config = config(Map.of());
        Card card = TestCards.card("card-1", "TRELLO-plain", "Ready for Codex");

        // when
        AgentRunResult result = runner.run(new AgentRunner.AgentRunRequest(
                card, null, "prompt without shipped skill paths", config, "worker-plain", event -> {}));

        // then
        assertThat(result).isEqualTo(AgentRunResult.ok());
        assertThat(config.workspace().root().resolve("TRELLO-plain").resolve(".codex"))
                .doesNotExist();
    }

    @Test
    void repositoryDefaultUrlSkillPathDoesNotTriggerBundledSkillInstallation() {
        // given
        String repositoryUrl = "https://example.invalid/.codex/skills/symphony-trello-commit.git";
        EffectiveConfig config = configWithRepository(Map.of("default_url", repositoryUrl));

        // when
        String prompt = promptPassedToCodex(config, "hand-authored prompt", "TRELLO-url-skill-default");

        // then
        Path workspace = config.workspace().root().resolve("TRELLO-url-skill-default");
        assertThat(prompt).contains("hand-authored prompt").contains("- Credential-free remote: " + repositoryUrl);
        assertThat(workspace.resolve(".codex")).doesNotExist();
        assertThat(workspace.resolve(".git/info/exclude")).doesNotExist();
    }

    @Test
    void repositoryDefaultPathSkillPathDoesNotTriggerBundledSkillInstallation() {
        // given
        Path repositoryPath = tempDir.resolve(".codex/skills/symphony-trello-source")
                .toAbsolutePath()
                .normalize();
        EffectiveConfig config = configWithRepository(Map.of("default_path", repositoryPath.toString()));

        // when
        String prompt = promptPassedToCodex(config, "hand-authored prompt", "TRELLO-path-skill-default");

        // then
        Path workspace = config.workspace().root().resolve("TRELLO-path-skill-default");
        assertThat(prompt).contains("hand-authored prompt").contains("- Resolved local path: " + repositoryPath);
        assertThat(workspace.resolve(".codex")).doesNotExist();
        assertThat(workspace.resolve(".git/info/exclude")).doesNotExist();
    }

    @Test
    void explicitRemoteCandidateSkillPathDoesNotTriggerBundledSkillInstallation() {
        // given
        String repositoryUrl = "https://example.invalid/team/project.git";
        Path repositoryPath = tempDir.resolve(".codex/skills/symphony-trello-candidate")
                .toAbsolutePath()
                .normalize();
        EffectiveConfig config =
                configWithRepository(Map.of("default_url", repositoryUrl, "default_path", repositoryPath.toString()));
        Card card = TestCards.cardWithText(
                "card-1",
                "TRELLO-candidate-skill-path",
                "Ready for Codex",
                "Implement feature",
                "Repository URL: " + repositoryUrl,
                List.of());

        // when
        String prompt = promptPassedToCodex(config, "hand-authored prompt", card);

        // then
        Path workspace = config.workspace().root().resolve("TRELLO-candidate-skill-path");
        assertThat(prompt)
                .contains("hand-authored prompt")
                .contains("- Configured checkout path candidate: " + repositoryPath);
        assertThat(workspace.resolve(".codex")).doesNotExist();
        assertThat(workspace.resolve(".git/info/exclude")).doesNotExist();
    }

    @Test
    void returnsFailureWhenRequiredHookFailsAndStillRemovesWorkerFromActiveSet() throws Exception {
        // given
        CodexAppServerClient codex = mock();
        var runner = runner(codex, CardLookupResult.Found::new);
        EffectiveConfig config = config(Map.of("before_run", "echo broken && exit 9"));

        // when
        AgentRunResult result = runner.run(new AgentRunner.AgentRunRequest(
                TestCards.card("card-1", "TRELLO-hook", "Ready for Codex"),
                null,
                "prompt",
                config,
                "worker-failed-hook",
                event -> {}));
        runner.cancel("worker-failed-hook");

        // then
        assertThat(result).isEqualTo(AgentRunResult.fail("Hook before_run failed: exit_code=9 output=broken\n"));
        verifyNoInteractions(codex);
    }

    @Test
    void cancelInterruptsActiveWorkerThread() throws Exception {
        // given
        CodexAppServerClient codex = mock();
        CountDownLatch entered = new CountDownLatch(1);
        AtomicReference<Boolean> interrupted = new AtomicReference<>(false);
        doAnswer(invocation -> {
                    entered.countDown();
                    try {
                        blockUntilInterruptedOrTimedOut();
                        return AgentRunResult.ok();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        interrupted.set(true);
                        return AgentRunResult.fail("interrupted");
                    }
                })
                .when(codex)
                .runSession(any(), any(), any(), any(), any(), any(), any());
        var runner = runner(codex, CardLookupResult.Found::new);
        EffectiveConfig config = config(Map.of());
        var result = new AtomicReference<AgentRunResult>();
        Thread worker = Thread.ofVirtual()
                .start(() -> result.set(runner.run(new AgentRunner.AgentRunRequest(
                        TestCards.card("card-1", "TRELLO-cancel", "Ready for Codex"),
                        null,
                        "prompt",
                        config,
                        "worker-cancel",
                        event -> {}))));
        assertThat(entered.await(5, TimeUnit.SECONDS))
                .as("the active worker enters its Codex session within 5 seconds")
                .isTrue();

        // when
        runner.cancel("worker-cancel");
        assertThat(worker.join(Duration.ofSeconds(5)))
                .as("the cancelled worker terminates within 5 seconds")
                .isTrue();

        // then
        assertThat(interrupted).hasValue(true);
        assertThat(result).hasValue(AgentRunResult.fail("interrupted"));
    }

    @Test
    void completedDuplicateIdentityCannotUnregisterNewerActiveWorker() throws Exception {
        // given
        CodexAppServerClient codex = mock();
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch secondEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicBoolean secondInterrupted = new AtomicBoolean();
        when(codex.runSession(any(), any(), any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            Card card = invocation.getArgument(1);
            if (card.id().equals("card-first")) {
                firstEntered.countDown();
                assertThat(releaseFirst.await(5, TimeUnit.SECONDS))
                        .as("the test releases the first duplicate-identity worker within 5 seconds")
                        .isTrue();
                return AgentRunResult.ok();
            }
            secondEntered.countDown();
            try {
                blockUntilInterruptedOrTimedOut();
                return AgentRunResult.ok();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                secondInterrupted.set(true);
                return AgentRunResult.fail("interrupted");
            }
        });
        var runner = runner(codex, CardLookupResult.Found::new);
        EffectiveConfig config = config(Map.of());
        AtomicReference<AgentRunResult> firstResult = new AtomicReference<>();
        AtomicReference<AgentRunResult> secondResult = new AtomicReference<>();
        Thread first = Thread.ofVirtual()
                .start(() -> firstResult.set(runner.run(new AgentRunner.AgentRunRequest(
                        TestCards.card("card-first", "TRELLO-duplicate-first", "Ready for Codex"),
                        null,
                        "prompt",
                        config,
                        "worker-duplicate",
                        event -> {}))));
        assertThat(firstEntered.await(5, TimeUnit.SECONDS))
                .as("the first duplicate-identity worker starts within 5 seconds")
                .isTrue();
        Thread second = Thread.ofVirtual()
                .start(() -> secondResult.set(runner.run(new AgentRunner.AgentRunRequest(
                        TestCards.card("card-second", "TRELLO-duplicate-second", "Ready for Codex"),
                        null,
                        "prompt",
                        config,
                        "worker-duplicate",
                        event -> {}))));
        assertThat(secondEntered.await(5, TimeUnit.SECONDS))
                .as("the replacement duplicate-identity worker starts within 5 seconds")
                .isTrue();
        releaseFirst.countDown();
        assertThat(first.join(Duration.ofSeconds(5)))
                .as("the superseded duplicate-identity worker terminates within 5 seconds")
                .isTrue();

        // when
        runner.cancel("worker-duplicate");
        assertThat(second.join(Duration.ofSeconds(5)))
                .as("the cancelled replacement worker terminates within 5 seconds")
                .isTrue();

        // then
        assertThat(firstResult).hasValue(AgentRunResult.ok());
        assertThat(secondInterrupted)
                .as("cancelling a duplicate identity interrupts the currently registered worker")
                .isTrue();
        assertThat(secondResult).hasValue(AgentRunResult.fail("interrupted"));
    }

    @Test
    void continuesSameSessionWhileCardRemainsActiveAndMaxTurnsAllowsIt() throws Exception {
        // given
        CodexAppServerClient codex = mock();
        AtomicReference<CodexAppServerClient.TurnController> controller = new AtomicReference<>();
        when(codex.runSession(any(), any(), any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            controller.set(invocation.getArgument(6));
            return AgentRunResult.ok();
        });
        var runner = runner(codex, CardLookupResult.Found::new);
        EffectiveConfig config = config(Map.of(), Map.of("max_turns", 2));

        // when
        AgentRunResult result = runner.run(new AgentRunner.AgentRunRequest(
                TestCards.card("card-1", "TRELLO-active", "Ready for Codex"),
                null,
                "prompt",
                config,
                "worker-multiturn",
                event -> {}));

        // then
        assertThat(result).isEqualTo(AgentRunResult.ok());
        assertThat(controller.get().afterSuccessfulTurn(1).nextPrompt()).contains("continuation turn 2 of at most 2");
        assertThat(controller.get().afterSuccessfulTurn(2)).isEqualTo(CodexAppServerClient.TurnDecision.stop());
    }

    @Test
    void stopsSameSessionWhenCardLeavesActiveStates() throws Exception {
        // given
        CodexAppServerClient codex = mock();
        AtomicReference<CodexAppServerClient.TurnController> controller = new AtomicReference<>();
        when(codex.runSession(any(), any(), any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            controller.set(invocation.getArgument(6));
            return AgentRunResult.ok();
        });
        var runner = runner(
                codex,
                ignored -> new CardLookupResult.Found(TestCards.card("card-1", "TRELLO-review", "Human Review")));
        EffectiveConfig config = config(Map.of(), Map.of("max_turns", 2));

        // when
        AgentRunResult result = runner.run(new AgentRunner.AgentRunRequest(
                TestCards.card("card-1", "TRELLO-review", "Ready for Codex"),
                null,
                "prompt",
                config,
                "worker-stop",
                event -> {}));

        // then
        assertThat(result).isEqualTo(AgentRunResult.ok());
        assertThat(controller.get().afterSuccessfulTurn(1)).isEqualTo(CodexAppServerClient.TurnDecision.stop());
    }

    private LocalAgentRunner runner(CodexAppServerClient codex, CardLookup lookup) {
        return new LocalAgentRunner(
                new WorkspaceManager(new HookRunner()), new HookRunner(), codex, tracker(lookup), new PromptRenderer());
    }

    private String promptPassedToCodex(EffectiveConfig config, String renderedPrompt) {
        return promptPassedToCodex(config, renderedPrompt, "TRELLO-runtime-prompt");
    }

    private String promptPassedToCodex(EffectiveConfig config, String renderedPrompt, String cardIdentifier) {
        return promptPassedToCodex(config, renderedPrompt, TestCards.card("card-1", cardIdentifier, "Ready for Codex"));
    }

    private String promptPassedToCodex(EffectiveConfig config, String renderedPrompt, Card card) {
        CodexAppServerClient codex = mock();
        AtomicReference<String> prompt = new AtomicReference<>();
        when(codex.runSession(any(), any(), any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            prompt.set(invocation.getArgument(3));
            return AgentRunResult.ok();
        });
        var runner = runner(codex, CardLookupResult.Found::new);

        AgentRunResult result = runner.run(new AgentRunner.AgentRunRequest(
                card, null, renderedPrompt, config, "worker-runtime-prompt", event -> {}));

        assertThat(result).isEqualTo(AgentRunResult.ok());
        assertThat(prompt).hasValueSatisfying(value -> assertThat(value).isNotBlank());
        return prompt.get();
    }

    private TrackerClient tracker(CardLookup lookup) {
        return new TrackerClient() {
            @Override
            public String resolveBoardId(EffectiveConfig config) {
                return config.tracker().boardId();
            }

            @Override
            public List<Card> fetchCandidateCards(EffectiveConfig config) {
                return List.of();
            }

            @Override
            public List<Card> fetchTerminalCards(EffectiveConfig config) {
                return List.of();
            }

            @Override
            public Map<String, CardLookupResult> fetchCardStatesByIds(EffectiveConfig config, List<String> cardIds) {
                return Map.of(
                        cardIds.getFirst(),
                        lookup.lookup(TestCards.card(cardIds.getFirst(), "TRELLO-card", "Ready for Codex")));
            }
        };
    }

    private EffectiveConfig config(Map<String, String> hooks) {
        return config(hooks, Map.of());
    }

    private EffectiveConfig config(Map<String, String> hooks, Map<String, Object> agent) {
        return config(hooks, agent, Map.of(), Map.of(), tempDir.resolve("WORKFLOW.md"));
    }

    private EffectiveConfig configWithRepository(Map<String, Object> repository) {
        return configWithRepository(repository, Map.of());
    }

    private EffectiveConfig configWithRepository(Map<String, Object> repository, Map<String, String> environment) {
        return configWithRepository(tempDir.resolve("WORKFLOW.md"), repository, environment);
    }

    private EffectiveConfig configWithRepository(
            Path workflow, Map<String, Object> repository, Map<String, String> environment) {
        return config(Map.of(), Map.of(), repository, environment, workflow);
    }

    private EffectiveConfig config(
            Map<String, String> hooks,
            Map<String, Object> agent,
            Map<String, Object> repository,
            Map<String, String> environment,
            Path workflowPath) {
        return new ConfigResolver(name -> Optional.ofNullable(environment.get(name)))
                .resolve(new WorkflowDefinition(workflowPath, workflowConfig(hooks, agent, repository), ""));
    }

    private Map<String, Object> workflowConfig(
            Map<String, String> hooks, Map<String, Object> agent, Map<String, Object> repository) {
        return Map.of(
                "tracker",
                Map.of(
                        "kind",
                        "trello",
                        "api_key",
                        "key",
                        "api_token",
                        "token",
                        "board_id",
                        "board-1",
                        "active_states",
                        List.of("Ready for Codex", "In Progress"),
                        "terminal_states",
                        List.of("Done")),
                "workspace",
                Map.of("root", tempDir.resolve("workspaces").toString()),
                "agent",
                agent,
                "hooks",
                hooks,
                "repository",
                repository);
    }

    private static Stream<Arguments> missingOrBlankUrlDefaults() {
        return Stream.of(
                Arguments.of("missing-url-default", Map.of()),
                Arguments.of("blank-url-default", Map.of("REPOSITORY_URL", "   ")));
    }

    private static Stream<Arguments> missingOrBlankRepositoryDefaults() {
        return Stream.of(
                Arguments.of("missing-defaults", Map.of()),
                Arguments.of("blank-defaults", Map.of("REPOSITORY_URL", " ", "REPOSITORY_PATH", "\t")));
    }

    private static void assertLocalSourceIdentityPolicy(String prompt) {
        assertThat(prompt)
                .contains("Add a status note to #123. Do not change files.")
                .contains("- Status: selected")
                .contains("- Type: local path")
                .contains(
                        "use this local source only when read-only inspection finds exactly one explicit, unambiguous compatible remote")
                .contains("If no such remote supplies identity, block")
                .contains("Request a fully qualified repository URL together with the issue or pull request number")
                .doesNotContain("A selected source supplies repository identity for repository-relative references");
    }

    private static Stream<Arguments> repositoryNeedDecisionScenarios() {
        Map<String, Object> noDefault = Map.of();
        Map<String, Object> validDefault = Map.of("default_url", "https://source.example/team/default.git");
        return Stream.of(
                Arguments.of(
                        "repository-independent task without a default runs",
                        "Summarize the supplied release schedule. No repository action.",
                        noDefault,
                        "- Status: no selected source",
                        List.of(
                                "The absence of a selected repository is not a blocker by itself",
                                "Repository-independent work without repository-relative references can run")),
                Arguments.of(
                        "fully qualified issue URL without a default stays direct",
                        "Add a status note to https://github.example/target/project/issues/123. No file changes.",
                        noDefault,
                        "- Status: no selected source",
                        List.of(
                                "A fully qualified GitHub issue or pull request URL is a direct external target",
                                "do not require a repository checkout for that API action")),
                Arguments.of(
                        "bare issue reference without identity blocks",
                        "Add a status note to #123. Do not change files.",
                        noDefault,
                        "- Status: no selected source",
                        List.of(
                                "Block only when the required repository identity is absent",
                                "Do not infer repository identity from unrelated checkouts")),
                Arguments.of(
                        "bare issue reference uses valid selected identity without checkout",
                        "Add a status note to #123. Do not change files.",
                        validDefault,
                        "- Status: selected",
                        List.of(
                                "- Repository identity: source.example/team/default",
                                "This selected remote source supplies repository identity for repository-relative references",
                                "Do not create or reuse a task checkout unless the classified task requires repository files")),
                Arguments.of(
                        "repository-changing task without an identity blocks",
                        "Change the requested source file and prepare a pull request.",
                        noDefault,
                        "- Status: no selected source",
                        List.of(
                                "Block only when the required repository identity is absent",
                                "when the required checkout cannot be prepared")),
                Arguments.of(
                        "repository-changing task with an unlabelled full repository URL runs",
                        "Change README.md in https://github.example/target/project and prepare a pull request.",
                        noDefault,
                        "- Status: no selected source",
                        List.of(
                                "A single unambiguous repository identity in the Trello card is sufficient",
                                "derive its normal credential-free clone URL",
                                "prepare a writable checkout when the requested work needs repository files")),
                Arguments.of(
                        "malformed configured default blocks an independent task",
                        "Summarize the supplied release schedule. No repository action.",
                        Map.of("default_url", "ftp://source.example/team/default.git"),
                        "- Status: invalid workflow fallback",
                        List.of(
                                "If the card supplies no repository identity, block unconditionally",
                                "even when the task would otherwise be repository-independent",
                                "Do not treat it as absent or use a lower-priority fallback")),
                Arguments.of(
                        "unambiguous card repository overrides a malformed workflow fallback",
                        "Change README.md in https://github.example/target/project and prepare a pull request.",
                        Map.of("default_url", "ftp://source.example/team/default.git"),
                        "- Status: invalid workflow fallback",
                        List.of(
                                "If card context supplies exactly one repository identity",
                                "ignore this unused workflow fallback and use the card repository identity",
                                "derive the card repository's normal credential-free clone URL")),
                Arguments.of(
                        "valid default keeps independent task independent and guards later unusability",
                        "Summarize the supplied release schedule. No repository action.",
                        validDefault,
                        "- Status: selected",
                        List.of(
                                "does not by itself make the task repository-changing",
                                "Do not create or reuse a task checkout unless the classified task requires repository files",
                                "If this workflow fallback is actually needed",
                                "Do not block because an unused fallback is unavailable")));
    }

    private static Stream<Arguments> directTargetsWithMalformedFallback() {
        return Stream.of(
                Arguments.of(
                        "full issue URL remains checkout-free",
                        "Comment on https://github.example/target/project/issues/123. Do not change files."),
                Arguments.of(
                        "full pull-request URL remains checkout-free",
                        "Comment on https://github.example/target/project/pull/456. Do not change files."));
    }

    private static Stream<Arguments> repositoryChangingIdentityFormsWithMalformedFallback() {
        return Stream.of(
                Arguments.of(
                        "full repository URL",
                        "Change README.md in https://github.example/target/project and prepare a pull request."),
                Arguments.of(
                        "full issue URL",
                        "Fix the code requested by https://github.example/target/project/issues/123."),
                Arguments.of(
                        "full pull-request URL", "Update files for https://github.example/target/project/pull/456."),
                Arguments.of(
                        "full repository file URL", "Edit https://github.example/target/project/blob/main/README.md."),
                Arguments.of("owner/repository", "Change README.md in target/project and prepare a pull request."));
    }

    private static void blockUntilInterruptedOrTimedOut() throws InterruptedException {
        new CountDownLatch(1).await(30, TimeUnit.SECONDS);
    }

    @FunctionalInterface
    private interface CardLookup {
        CardLookupResult lookup(Card card);
    }
}
