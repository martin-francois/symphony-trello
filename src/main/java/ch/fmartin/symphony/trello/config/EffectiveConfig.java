package ch.fmartin.symphony.trello.config;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

public record EffectiveConfig(
        Path workflowPath,
        TrackerConfig tracker,
        PollingConfig polling,
        WorkspaceConfig workspace,
        RepositoryConfig repository,
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
                        tracker.requiredLabels(),
                        tracker.inProgressState(),
                        tracker.blockedState(),
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
                repository,
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
            List<String> requiredLabels,
            String inProgressState,
            String blockedState,
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
            requiredLabels = List.copyOf(requiredLabels);
            blockerEnforcedStates = List.copyOf(blockerEnforcedStates);
            terminalStates = List.copyOf(terminalStates);
            terminalListIds = List.copyOf(terminalListIds);
            priorityLabels = Map.copyOf(priorityLabels);
        }
    }

    public record PollingConfig(Duration interval) {}

    public record WorkspaceConfig(Path root) {}

    @NullMarked
    public record RepositoryConfig(@Nullable String defaultUrl, @Nullable Path defaultPath) {
        public DefaultSource selectedDefaultSource() {
            if (defaultUrl != null) {
                return DefaultSource.URL;
            }
            if (defaultPath != null) {
                return DefaultSource.PATH;
            }
            return DefaultSource.NONE;
        }
    }

    public enum DefaultSource {
        URL,
        PATH,
        NONE
    }

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
