package ch.fmartin.symphony.trello.agent;

import ch.fmartin.symphony.trello.prompt.PromptRenderer;
import ch.fmartin.symphony.trello.tracker.CardLookupResult;
import ch.fmartin.symphony.trello.tracker.TrackerClient;
import ch.fmartin.symphony.trello.tracker.TrelloClient;
import ch.fmartin.symphony.trello.workspace.HookRunner;
import ch.fmartin.symphony.trello.workspace.Workspace;
import ch.fmartin.symphony.trello.workspace.WorkspaceManager;
import jakarta.enterprise.context.ApplicationScoped;
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
    private final Map<String, Thread> active = new ConcurrentHashMap<>();

    public LocalAgentRunner(
            WorkspaceManager workspaceManager,
            HookRunner hooks,
            CodexAppServerClient codex,
            TrackerClient tracker,
            PromptRenderer prompts) {
        this.workspaceManager = workspaceManager;
        this.hooks = hooks;
        this.codex = codex;
        this.tracker = tracker;
        this.prompts = prompts;
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
            return codex.runSession(
                    request.config(),
                    request.card(),
                    workspace.path(),
                    request.prompt(),
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
