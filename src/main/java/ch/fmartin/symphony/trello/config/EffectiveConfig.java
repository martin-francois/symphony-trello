package ch.fmartin.symphony.trello.config;

import static ch.fmartin.symphony.trello.TextCharacterMatchers.UNSAFE_SINGLE_LINE_CHARACTERS;

import ch.fmartin.symphony.trello.workflow.CodexSandboxPolicy;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.regex.Pattern;
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
        ServerConfig server,
        GitHubConfig github,
        CredentialSources credentialSources,
        Set<Path> protectedLaunchPaths) {

    public EffectiveConfig {
        protectedLaunchPaths = Set.copyOf(protectedLaunchPaths);
    }

    public EffectiveConfig withProtectedLaunchPaths(Set<Path> paths) {
        return new EffectiveConfig(
                workflowPath,
                tracker,
                polling,
                workspace,
                repository,
                hooks,
                agent,
                codex,
                trelloTools,
                server,
                github,
                credentialSources,
                paths);
    }

    public EffectiveConfig withCredentialSources(CredentialSources sources) {
        return new EffectiveConfig(
                workflowPath,
                tracker,
                polling,
                workspace,
                repository,
                hooks,
                agent,
                codex,
                trelloTools,
                server,
                github,
                sources,
                protectedLaunchPaths);
    }

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
                server,
                github,
                credentialSources,
                protectedLaunchPaths);
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
    public record RepositoryConfig(
            @Nullable String defaultUrl, @Nullable Path defaultPath, CodexReviewConfig codexReview) {
        /// For callers that only resolve repository sources, where the review loop plays no part.
        public RepositoryConfig(@Nullable String defaultUrl, @Nullable Path defaultPath) {
            this(defaultUrl, defaultPath, CodexReviewConfig.DEFAULTS);
        }

        /// The repository source context shows the default path on one prompt line, so a path with a
        /// control character or a Unicode line or paragraph separator cannot be a default.
        public static boolean usableDefaultPath(Path path) {
            return UNSAFE_SINGLE_LINE_CHARACTERS.matchesNoneOf(path.toString());
        }

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

    /// Review loop that the agent runs before it hands off repository changes; see ADR 0121.
    ///
    /// @param maxRounds how many review rounds may run; `0` turns the loop off
    /// @param extendedMaxRounds the hard round limit while a high-risk blocker or should finding stays open
    /// @param timeout the time budget for the whole loop, shared by every `codex review` run
    /// @param model the reviewer model, or `null` to use the workflow's `codex.model`
    /// @param reasoningEffort the reviewer reasoning effort, or `null` to use `codex.reasoning_effort`
    @NullMarked
    public record CodexReviewConfig(
            int maxRounds,
            int extendedMaxRounds,
            Duration timeout,
            @Nullable String model,
            @Nullable String reasoningEffort) {
        public static final CodexReviewConfig DEFAULTS = new CodexReviewConfig(
                ConfigDefaults.DEFAULT_CODEX_REVIEW_MAX_ROUNDS,
                ConfigDefaults.DEFAULT_CODEX_REVIEW_EXTENDED_MAX_ROUNDS,
                ConfigDefaults.DEFAULT_CODEX_REVIEW_TIMEOUT,
                null,
                null);

        /// Front-matter keys under `repository`; config parsing, setup, and prompt text all use these.
        public static final String MAX_ROUNDS_KEY = "codex_review_max_rounds";

        public static final String EXTENDED_MAX_ROUNDS_KEY = "codex_review_extended_max_rounds";
        public static final String TIMEOUT_MS_KEY = "codex_review_timeout_ms";
        public static final String MODEL_KEY = "codex_review_model";
        public static final String REASONING_EFFORT_KEY = "codex_review_reasoning_effort";
        public static final String MAX_ROUNDS_PATH = "repository." + MAX_ROUNDS_KEY;
        public static final String EXTENDED_MAX_ROUNDS_PATH = "repository." + EXTENDED_MAX_ROUNDS_KEY;
        public static final String TIMEOUT_MS_PATH = "repository." + TIMEOUT_MS_KEY;

        /// Model ids and reasoning efforts go into a shell command in the prompt, so only plain
        /// token characters are allowed; this also keeps quotes and line breaks out of the prompt.
        private static final Pattern COMMAND_TOKEN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/@+-]*");

        /// The findings that may run rounds past the round limit, named the same way in the prompt and in
        /// the generated workflow comment.
        public static final String HIGH_RISK_TOPICS =
                "security, authentication, permissions, data loss, or concurrency";

        /// The allowed command-token characters, in words, for error messages.
        public static final String COMMAND_TOKEN_DESCRIPTION =
                "one word of letters, digits, and . _ : / @ + - characters";

        public boolean enabled() {
            return maxRounds > 0;
        }

        /// Whether `value` can appear unquoted in the `codex review -c` option the prompt shows.
        public static boolean commandToken(String value) {
            return COMMAND_TOKEN.matcher(value).matches();
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

        /// Whether Codex runs this workflow's turns without its sandbox. Nested Codex commands may
        /// bypass the sandbox only when this is true.
        public boolean bypassesSandbox() {
            return CodexSandboxPolicy.bypassesSandbox(
                    threadSandbox, turnSandboxPolicy, additionalWritableRoots, forceDangerFullAccess);
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
            boolean assumeWriteScope,
            FollowUpCardsConfig followUpCards) {
        public TrelloToolsConfig {
            allowedMoveListIds = List.copyOf(allowedMoveListIds);
            allowedMoveListNames = List.copyOf(allowedMoveListNames);
        }
    }

    /// Opt-in policy for `trello_create_follow_up_card` under `trello_tools.follow_up_cards`.
    ///
    /// `label` is the visible label added to every follow-up card; an empty value disables it.
    /// `listId` wins over `listName` when both are set, like the move allowlists prefer ids.
    @NullMarked
    public record FollowUpCardsConfig(
            boolean enabled,
            String listName,
            @Nullable String listId,
            String label,
            Map<FollowUpRelationship, String> relationshipLabels,
            boolean moveCurrentCardToBlocked,
            int maxCardsPerSourceCard,
            int maxCardsPerHour) {
        public FollowUpCardsConfig {
            relationshipLabels = Map.copyOf(relationshipLabels);
        }

        public static FollowUpCardsConfig disabled() {
            return new FollowUpCardsConfig(
                    false,
                    ConfigDefaults.DEFAULT_FOLLOW_UP_LIST_NAME,
                    null,
                    ConfigDefaults.DEFAULT_FOLLOW_UP_LABEL,
                    Map.of(),
                    false,
                    ConfigDefaults.DEFAULT_FOLLOW_UP_MAX_CARDS_PER_SOURCE_CARD,
                    ConfigDefaults.DEFAULT_FOLLOW_UP_MAX_CARDS_PER_HOUR);
        }
    }

    public record ServerConfig(OptionalInt port) {}

    /// Branch-only handoff settings from the workflow's `github` section. A `null` label means the
    /// card-level label signal is disabled.
    @NullMarked
    public record GitHubConfig(PullRequestMode pullRequestMode, @Nullable String noPrLabel) {
        public static final GitHubConfig DEFAULTS =
                new GitHubConfig(PullRequestMode.CREATE, ConfigDefaults.DEFAULT_NO_PR_LABEL);

        /// Workflow key names shared by the resolver, the generated front matter, and the generated prompt.
        public static final String SECTION_KEY = "github";

        public static final String PULL_REQUEST_MODE_KEY = "pull_request_mode";
        public static final String NO_PR_LABEL_KEY = "no_pr_label";

        /// The dotted workflow path of a key in this section, for example `github.no_pr_label`.
        public static String qualifiedKey(String key) {
            return SECTION_KEY + "." + key;
        }
    }
}
