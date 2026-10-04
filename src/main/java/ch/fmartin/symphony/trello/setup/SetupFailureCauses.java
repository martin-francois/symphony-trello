package ch.fmartin.symphony.trello.setup;

import static java.util.function.Predicate.not;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.common.base.Throwables;
import com.google.common.collect.Iterables;
import java.io.IOException;
import java.nio.file.FileSystemException;
import java.util.List;
import java.util.Optional;

/// Names the underlying cause in setup and lifecycle failure messages, so a transient filesystem or
/// network failure can be diagnosed from one occurrence. Setup code that turns an I/O or transport
/// failure into a [TrelloBoardSetupException], a usage error, or a warning renders the cause through
/// this class. docs/adr/0083-setup-failure-cause-summaries.md records the convention and the
/// rejected alternatives.
final class SetupFailureCauses {
    private SetupFailureCauses() {}

    /// Creates a setup failure whose message names the same cause it chains.
    static TrelloBoardSetupException setupFailure(String code, String message, Throwable cause) {
        return new TrelloBoardSetupException(code, withCause(message, cause), cause);
    }

    /// Appends the cause summary to an actionable message: `message (summary)`.
    static String withCause(String message, Throwable cause) {
        return message + " (" + summary(cause) + ")";
    }

    /// Returns the message a command prints for a failure that reached the command boundary. An
    /// unwrapped I/O failure has no actionable text of its own, so its cause summary is the message.
    static String commandBoundaryMessage(Exception failure) {
        return failure instanceof IOException ? summary(failure) : failure.getMessage();
    }

    /// Returns `Type: detail`, followed by `, caused by RootType: detail` when the cause wraps a
    /// different root cause. The JDK HTTP client, for example, throws a message-less
    /// `ConnectException` whose root cause tells a refused connection apart from a DNS failure.
    static String summary(Throwable cause) {
        String summary = typeAndDetail(cause);
        return reportedRootCause(cause)
                .map(rootCause -> summary + ", caused by " + typeAndDetail(rootCause))
                .orElse(summary);
    }

    /// Returns the deepest wrapped cause, or none when `cause` wraps nothing. The search stops at the
    /// first parser failure in the chain: its own causes, such as the SnakeYAML exception under a
    /// Jackson YAML failure, quote the same parsed content.
    private static Optional<Throwable> reportedRootCause(Throwable cause) {
        List<Throwable> causalChain = Throwables.getCausalChain(cause);
        int parserFailureIndex = Iterables.indexOf(causalChain, JsonProcessingException.class::isInstance);
        int rootCauseIndex = parserFailureIndex < 0 ? causalChain.size() - 1 : parserFailureIndex;
        return rootCauseIndex == 0 ? Optional.empty() : Optional.of(causalChain.get(rootCauseIndex));
    }

    private static String typeAndDetail(Throwable throwable) {
        String type = throwable.getClass().getSimpleName();
        return detail(throwable).map(detail -> type + ": " + detail).orElse(type);
    }

    /// A [FileSystemException] message repeats the affected paths, which the wrapping message
    /// already identifies, so only its path-free reason is kept. Parser messages quote the parsed
    /// content, which can hold credentials or board names, so they are dropped.
    private static Optional<String> detail(Throwable throwable) {
        String detail =
                switch (throwable) {
                    case FileSystemException fileSystemFailure -> fileSystemFailure.getReason();
                    case JsonProcessingException _ -> null;
                    default -> throwable.getMessage();
                };
        return Optional.ofNullable(detail).filter(not(String::isBlank));
    }
}
