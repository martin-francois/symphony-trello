package com.openai.symphony.agent;

import com.openai.symphony.workspace.HookRunner;
import com.openai.symphony.workspace.Workspace;
import com.openai.symphony.workspace.WorkspaceManager;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class LocalAgentRunner implements AgentRunner {
    private final WorkspaceManager workspaceManager;
    private final HookRunner hooks;
    private final CodexAppServerClient codex;
    private final Map<String, Thread> active = new ConcurrentHashMap<>();

    public LocalAgentRunner(WorkspaceManager workspaceManager, HookRunner hooks, CodexAppServerClient codex) {
        this.workspaceManager = workspaceManager;
        this.hooks = hooks;
        this.codex = codex;
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
            return codex.runTurn(
                    request.config(),
                    request.card(),
                    workspace.path(),
                    request.prompt(),
                    request.workerIdentity(),
                    request.listener());
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

    @Override
    public void cancel(String workerIdentity) {
        Thread thread = active.get(workerIdentity);
        if (thread != null) {
            thread.interrupt();
        }
    }
}
