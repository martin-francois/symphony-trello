package ch.fmartin.symphony.trello.agent;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public record AgentRunResult(
        boolean success, String reason, FailureCategory failureCategory, Optional<Instant> retryNotBefore) {
    public AgentRunResult {
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(failureCategory, "failureCategory");
        Objects.requireNonNull(retryNotBefore, "retryNotBefore");
    }

    public static AgentRunResult ok() {
        return new AgentRunResult(true, "normal", FailureCategory.GENERIC, Optional.empty());
    }

    public static AgentRunResult fail(String reason) {
        return new AgentRunResult(false, reason, FailureCategory.GENERIC, Optional.empty());
    }

    public static AgentRunResult codexUsageLimit(String reason, Optional<Instant> retryNotBefore) {
        return new AgentRunResult(false, reason, FailureCategory.CODEX_USAGE_LIMIT, retryNotBefore);
    }

    public enum FailureCategory {
        GENERIC,
        CODEX_USAGE_LIMIT
    }
}
