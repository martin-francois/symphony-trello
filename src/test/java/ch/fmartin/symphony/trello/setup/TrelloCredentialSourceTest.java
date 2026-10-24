package ch.fmartin.symphony.trello.setup;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

final class TrelloCredentialSourceTest {
    @MethodSource("pairScenarios")
    @ParameterizedTest(name = "{0}")
    void namesOnePlaceOnlyWhenBothValuesCameFromTheShellOrBothFromTheCredentialFile(PairScenario scenario) {
        // given
        TrelloCredentialSource apiKeySource = scenario.apiKeySource();
        TrelloCredentialSource apiTokenSource = scenario.apiTokenSource();

        // when
        TrelloCredentialSource.Pair pair = TrelloCredentialSource.Pair.of(apiKeySource, apiTokenSource);

        // then
        assertThat(pair).isEqualTo(scenario.expected());
    }

    private static Stream<PairScenario> pairScenarios() {
        return Stream.of(
                new PairScenario(
                        TrelloCredentialSource.SHELL_ENVIRONMENT,
                        TrelloCredentialSource.SHELL_ENVIRONMENT,
                        TrelloCredentialSource.Pair.SHELL_ENVIRONMENT),
                new PairScenario(
                        TrelloCredentialSource.DOTENV_FILE,
                        TrelloCredentialSource.DOTENV_FILE,
                        TrelloCredentialSource.Pair.DOTENV_FILE),
                new PairScenario(
                        TrelloCredentialSource.SHELL_ENVIRONMENT,
                        TrelloCredentialSource.DOTENV_FILE,
                        TrelloCredentialSource.Pair.MIXED),
                new PairScenario(
                        TrelloCredentialSource.DOTENV_FILE,
                        TrelloCredentialSource.SHELL_ENVIRONMENT,
                        TrelloCredentialSource.Pair.MIXED),
                new PairScenario(
                        TrelloCredentialSource.DIRECT_INPUT,
                        TrelloCredentialSource.DIRECT_INPUT,
                        TrelloCredentialSource.Pair.MIXED),
                new PairScenario(
                        TrelloCredentialSource.WORKFLOW_CONFIG,
                        TrelloCredentialSource.WORKFLOW_CONFIG,
                        TrelloCredentialSource.Pair.MIXED),
                new PairScenario(
                        TrelloCredentialSource.MISSING,
                        TrelloCredentialSource.MISSING,
                        TrelloCredentialSource.Pair.MIXED));
    }

    record PairScenario(
            TrelloCredentialSource apiKeySource,
            TrelloCredentialSource apiTokenSource,
            TrelloCredentialSource.Pair expected) {
        @Override
        public String toString() {
            return apiKeySource + " and " + apiTokenSource + " -> " + expected;
        }
    }
}
