package ch.fmartin.symphony.trello.agent;

import static com.google.common.base.Strings.nullToEmpty;

import ch.fmartin.symphony.trello.codex.CodexSkillCatalog;
import ch.fmartin.symphony.trello.prompt.PromptRenderer;
import ch.fmartin.symphony.trello.repository.RepositorySourcePrompt;
import ch.fmartin.symphony.trello.repository.RepositorySourceResolver;
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
    private final RepositorySourceResolver repositorySources;
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
        this(workspaceManager, hooks, codex, tracker, prompts, codexSkills, new RepositorySourceResolver());
    }

    LocalAgentRunner(
            WorkspaceManager workspaceManager,
            HookRunner hooks,
            CodexAppServerClient codex,
            TrackerClient tracker,
            PromptRenderer prompts,
            CodexSkillInstaller codexSkills,
            RepositorySourceResolver repositorySources) {
        this.workspaceManager = workspaceManager;
        this.hooks = hooks;
        this.codex = codex;
        this.tracker = tracker;
        this.prompts = prompts;
        this.codexSkills = codexSkills;
        this.repositorySources = repositorySources;
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
            String prompt = withRepositorySourceContext(request);
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

    private String withRepositorySourceContext(AgentRunRequest request) {
        return withRepositorySourceContext(
                request.prompt(),
                RepositorySourcePrompt.render(
                        repositorySources.select(
                                request.card(), request.config().repository()),
                        request.config().repository()));
    }

    private static String withRepositorySourceContext(String prompt, String repositoryContext) {
        String basePrompt = nullToEmpty(prompt).stripTrailing();
        return basePrompt + "\n\n" + repositoryContext;
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
