package ch.fmartin.symphony.trello.config;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

public record EffectiveConfig(
        Path workflowPath,
        TrackerConfig tracker,
        PollingConfig polling,
        WorkspaceConfig workspace,
        HooksConfig hooks,
        AgentConfig agent,
        CodexConfig codex,
        TrelloToolsConfig trelloTools,
        ServerConfig server) {

    public EffectiveConfig withResolvedBoardId(String resolvedBoardId) {
        return new EffectiveConfig(
                workflowPath,
                new TrackerConfig(
                        tracker.kind(),
                        tracker.endpoint(),
                        tracker.apiKey(),
                        tracker.apiToken(),
                        tracker.boardId(),
                        resolvedBoardId,
                        tracker.activeStates(),
                        tracker.activeListIds(),
                        tracker.inProgressState(),
                        tracker.blockerEnforcedStates(),
                        tracker.terminalStates(),
                        tracker.terminalListIds(),
                        tracker.priorityLabels(),
                        tracker.cardIdentifierPrefix(),
                        tracker.requestTimeout(),
                        tracker.maxApiRetries(),
                        tracker.apiRetryBaseDelay()),
                polling,
                workspace,
                hooks,
                agent,
                codex,
                trelloTools,
                server);
    }

    public record TrackerConfig(
            String kind,
            String endpoint,
            String apiKey,
            String apiToken,
            String boardId,
            String resolvedBoardId,
            List<String> activeStates,
            List<String> activeListIds,
            String inProgressState,
            List<String> blockerEnforcedStates,
            List<String> terminalStates,
            List<String> terminalListIds,
            Map<String, Integer> priorityLabels,
            String cardIdentifierPrefix,
            Duration requestTimeout,
            int maxApiRetries,
            Duration apiRetryBaseDelay) {
        public TrackerConfig {
            activeStates = List.copyOf(activeStates);
            activeListIds = List.copyOf(activeListIds);
            blockerEnforcedStates = List.copyOf(blockerEnforcedStates);
            terminalStates = List.copyOf(terminalStates);
            terminalListIds = List.copyOf(terminalListIds);
            priorityLabels = Map.copyOf(priorityLabels);
        }
    }

    public record PollingConfig(Duration interval) {}

    public record WorkspaceConfig(Path root) {}

    public record HooksConfig(
            String afterCreate, String beforeRun, String afterRun, String beforeRemove, Duration timeout) {}

    public record AgentConfig(
            int maxConcurrentAgents,
            int maxTurns,
            Duration maxRetryBackoff,
            Map<String, Integer> maxConcurrentAgentsByState) {
        public AgentConfig {
            maxConcurrentAgentsByState = Map.copyOf(maxConcurrentAgentsByState);
        }
    }

    public record CodexConfig(
            String command,
            String model,
            String reasoningEffort,
            Object approvalPolicy,
            Object threadSandbox,
            Object turnSandboxPolicy,
            List<Path> additionalWritableRoots,
            boolean forceDangerFullAccess,
            Duration turnTimeout,
            Duration readTimeout,
            Duration stallTimeout) {
        public CodexConfig {
            additionalWritableRoots = List.copyOf(additionalWritableRoots);
        }
    }

    public record TrelloToolsConfig(
            boolean enabled,
            boolean allowWrites,
            List<String> allowedMoveListIds,
            List<String> allowedMoveListNames,
            boolean allowComments,
            boolean allowChecklists,
            boolean allowUrlAttachments,
            boolean allowDestructiveOperations,
            boolean assumeWriteScope) {
        public TrelloToolsConfig {
            allowedMoveListIds = List.copyOf(allowedMoveListIds);
            allowedMoveListNames = List.copyOf(allowedMoveListNames);
        }
    }

    public record ServerConfig(OptionalInt port) {}
}
