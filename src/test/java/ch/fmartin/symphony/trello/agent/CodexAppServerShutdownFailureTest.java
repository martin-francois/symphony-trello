package ch.fmartin.symphony.trello.agent;

import static org.assertj.core.api.Assertions.assertThat;

import ch.fmartin.symphony.trello.process.RuntimeProcessShutdown;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class CodexAppServerShutdownFailureTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void unconfirmedCloseTakesPrecedenceOverProtocolFailure(boolean timeout) {
        // given
        Exception protocolFailure =
                timeout ? new TimeoutException("response timed out") : new Exception("protocol failed");
        var shutdownFailure = new RuntimeProcessShutdown.UnconfirmedExitException();

        // when
        AgentRunResult result;
        try (AutoCloseable _ = () -> {
            throw shutdownFailure;
        }) {
            throw protocolFailure;
        } catch (Exception failure) {
            result = CodexAppServerClient.sessionFailure(failure);
        }

        // then
        assertThat(protocolFailure.getSuppressed()).containsExactly(shutdownFailure);
        assertThat(result.failureCategory()).isEqualTo(AgentRunResult.FailureCategory.SHUTDOWN_INCOMPLETE);
    }
}
