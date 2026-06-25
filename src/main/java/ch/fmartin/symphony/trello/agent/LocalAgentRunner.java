package ch.fmartin.symphony.trello.agent;

import ch.fmartin.symphony.trello.codex.CodexSkillCatalog;
import ch.fmartin.symphony.trello.config.EffectiveConfig;
import ch.fmartin.symphony.trello.prompt.PromptRenderer;
import ch.fmartin.symphony.trello.tracker.CardLookupResult;
import ch.fmartin.symphony.trello.tracker.TrackerClient;
import ch.fmartin.symphony.trello.tracker.TrelloClient;
import ch.fmartin.symphony.trello.workspace.CodexSkillInstaller;
import ch.fmartin.symphony.trello.workspace.HookRunner;
import ch.fmartin.symphony.trello.workspace.Workspace;
import ch.fmartin.symphony.trello.workspace.WorkspaceManager;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class LocalAgentRunner implements AgentRunner {
    private final WorkspaceManager workspaceManager;
    private final HookRunner hooks;
    private final CodexAppServerClient codex;
    private final TrackerClient tracker;
    private final PromptRenderer prompts;
    private final CodexSkillInstaller codexSkills;
    private final Map<String, Thread> active = new ConcurrentHashMap<>();

    public LocalAgentRunner(
            WorkspaceManager workspaceManager,
            HookRunner hooks,
            CodexAppServerClient codex,
            TrackerClient tracker,
            PromptRenderer prompts) {
        this(workspaceManager, hooks, codex, tracker, prompts, new CodexSkillInstaller());
    }

    @Inject
    public LocalAgentRunner(
            WorkspaceManager workspaceManager,
            HookRunner hooks,
            CodexAppServerClient codex,
            TrackerClient tracker,
            PromptRenderer prompts,
            CodexSkillInstaller codexSkills) {
        this.workspaceManager = workspaceManager;
        this.hooks = hooks;
        this.codex = codex;
        this.tracker = tracker;
        this.prompts = prompts;
        this.codexSkills = codexSkills;
    }

    @Override
    public AgentRunResult run(AgentRunRequest request) {
        Thread.currentThread().setName("symphony-worker-" + request.workerIdentity());
        active.put(request.workerIdentity(), Thread.currentThread());
        Workspace workspace = null;
        try {
            workspace = workspaceManager.createForCard(request.card().identifier(), request.config());
            hooks.runRequired(
                    "before_run",
                    request.config().hooks().beforeRun(),
                    workspace.path(),
                    request.config().hooks());
            boolean installBundledSkills = usesBundledCodexSkills(request.prompt());
            String prompt = withRepositoryDefaultContext(
                    request.prompt(), request.config().repository());
            if (installBundledSkills) {
                codexSkills.installInto(workspace.path());
            }
            return codex.runSession(
                    request.config(),
                    request.card(),
                    workspace.path(),
                    prompt,
                    request.workerIdentity(),
                    request.listener(),
                    turn -> continuationDecision(request, turn));
        } catch (RuntimeException e) {
            return AgentRunResult.fail(e.getMessage());
        } finally {
            if (workspace != null) {
                hooks.runBestEffort(
                        "after_run",
                        request.config().hooks().afterRun(),
                        workspace.path(),
                        request.config().hooks());
            }
            active.remove(request.workerIdentity());
        }
    }

    /**
     * Shipped skills are installed only when the rendered prompt references their namespaced
     * paths, so workflows whose prompts do not use the shipped skills, such as hand-authored
     * workflows that expect an empty workspace root, keep their workspace shape.
     */
    private static boolean usesBundledCodexSkills(String prompt) {
        return prompt != null && prompt.contains(".codex/skills/" + CodexSkillCatalog.INSTALLED_SKILL_PREFIX);
    }

    private static String withRepositoryDefaultContext(String prompt, EffectiveConfig.RepositoryConfig repository) {
        String basePrompt = prompt == null ? "" : prompt.stripTrailing();
        return basePrompt + "\n\n" + repositoryDefaultContext(repository);
    }

    private static String repositoryDefaultContext(EffectiveConfig.RepositoryConfig repository) {
        return switch (repository.selectedDefaultSource()) {
            case URL ->
                String.join(
                        "\n",
                        "## Workflow Repository Default",
                        "",
                        "This context comes from trusted workflow configuration, not from the Trello card.",
                        "",
                        "- Type: URL",
                        "- Resolved URL: " + repository.defaultUrl(),
                        "- Use this workflow default only when the current Trello card names no explicit repository URL or local checkout path.",
                        "- An explicit Trello card repository source remains authoritative.",
                        "- If the Trello card names an invalid explicit repository source, block with path-safe guidance instead of using this workflow default.",
                        "- Do not copy private repository URLs from this context into Trello-visible comments or status text.");
            case PATH ->
                String.join(
                        "\n",
                        "## Workflow Repository Default",
                        "",
                        "This context comes from trusted workflow configuration, not from the Trello card.",
                        "",
                        "- Type: local path",
                        "- Resolved local path: "
                                + repository.defaultPath().toAbsolutePath().normalize(),
                        "- Use this workflow default only when the current Trello card names no explicit repository URL or local checkout path.",
                        "- An explicit Trello card repository source remains authoritative.",
                        "- If the Trello card names an invalid explicit repository source, block with path-safe guidance instead of using this workflow default.",
                        "- Do not copy local host paths from this context into Trello-visible comments or status text.");
            case NONE ->
                """
                    ## Workflow Repository Default

                    No workflow repository default is configured. If the current Trello card names no explicit repository URL or local checkout path, no repository source is selected.
                    """
                        .stripTrailing();
        };
    }

    private CodexAppServerClient.TurnDecision continuationDecision(AgentRunRequest request, int completedTurns) {
        if (completedTurns >= request.config().agent().maxTurns()) {
            return CodexAppServerClient.TurnDecision.stop();
        }
        CardLookupResult result = tracker.fetchCardStatesByIds(
                        request.config(), List.of(request.card().id()))
                .get(request.card().id());
        if (result instanceof CardLookupResult.Found found) {
            if (TrelloClient.isActive(found.card(), request.config())
                    && !TrelloClient.isTerminal(found.card(), request.config())) {
                return CodexAppServerClient.TurnDecision.continueWith(prompts.continuationPrompt(
                        completedTurns + 1, request.config().agent().maxTurns()));
            }
            return CodexAppServerClient.TurnDecision.stop();
        }
        if (result instanceof CardLookupResult.Failed failed) {
            return CodexAppServerClient.TurnDecision.fail("card_refresh_failed: " + failed.message());
        }
        return CodexAppServerClient.TurnDecision.stop();
    }

    @Override
    public void cancel(String workerIdentity) {
        Thread thread = active.get(workerIdentity);
        if (thread != null) {
            thread.interrupt();
        }
    }
}
