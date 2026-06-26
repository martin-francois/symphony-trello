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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
                .contains("only when the current Trello card names no explicit repository URL or local checkout path");
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
    void includesOnlyUrlDefaultWhenUrlAndPathAreConfigured() {
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
                .doesNotContain(lowerPriorityPath.toString());
        assertThat(config.repository().defaultPath()).isNull();
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
    void keepsExplicitCardSourcePromptAuthoritativeWhenWorkflowDefaultExists() {
        // given
        String cardRepository = "https://example.invalid/card-specific.git";
        EffectiveConfig config = configWithRepository(Map.of("default_url", "https://example.invalid/default.git"));
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
                .doesNotContain("https://example.invalid/default.git");
    }

    @Test
    void invalidExplicitCardSourceDoesNotFallBackToWorkflowDefaultInPrompt() {
        // given
        EffectiveConfig config = configWithRepository(Map.of("default_url", "https://example.invalid/default.git"));
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
                .doesNotContain("https://example.invalid/default.git");
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
                .contains("- Status: invalid selected source")
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
        assertThat(result.success()).isFalse();
        assertThat(result.reason()).contains("Hook before_run failed");
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
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

        // when
        runner.cancel("worker-cancel");
        worker.join(TimeUnit.SECONDS.toMillis(5));

        // then
        assertThat(interrupted).hasValue(true);
        assertThat(result.get().success()).isFalse();
        assertThat(result.get().reason()).isEqualTo("interrupted");
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

    private static void blockUntilInterruptedOrTimedOut() throws InterruptedException {
        new CountDownLatch(1).await(30, TimeUnit.SECONDS);
    }

    @FunctionalInterface
    private interface CardLookup {
        CardLookupResult lookup(Card card);
    }
}
