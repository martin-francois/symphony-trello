package ch.fmartin.symphony.trello.agent;

import static ch.fmartin.symphony.trello.agent.TrelloHandoffToolFuzzInvariants.assertCallStaysInWriteScope;
import static ch.fmartin.symphony.trello.agent.TrelloHandoffToolFuzzInvariants.execute;
import static org.assertj.core.api.Assertions.assertThat;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import java.lang.reflect.Method;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class TrelloHandoffToolFuzzTest {
    private static final Path SEED_CORPUS = Path.of("oss-fuzz/corpora/TrelloHandoffToolFuzzer");
    // pom.xml copies the seed corpus to the Jazzer regression inputs of this method.
    private static final String FUZZ_TEST_METHOD = "handoffToolCallsWriteOnlyWhatTheConfigurationAllows";

    @FuzzTest(maxDuration = "10s", maxExecutions = 20_000)
    void handoffToolCallsWriteOnlyWhatTheConfigurationAllows(FuzzedDataProvider data) {
        // given
        FuzzedDataProvider fuzzedToolCall = data;

        // when
        TrelloHandoffToolFuzzInvariants.Execution execution = execute(fuzzedToolCall);

        // then
        assertCallStaysInWriteScope(execution);
    }

    @Test
    void regressionInputsReplayEverySeed() throws NoSuchMethodException, URISyntaxException {
        // given
        Method fuzzTest = TrelloHandoffToolFuzzTest.class.getDeclaredMethod(FUZZ_TEST_METHOD, FuzzedDataProvider.class);

        // when
        URL regressionInputs = TrelloHandoffToolFuzzTest.class.getResource(
                TrelloHandoffToolFuzzTest.class.getSimpleName() + "Inputs/" + FUZZ_TEST_METHOD);

        // then
        assertThat(fuzzTest.isAnnotationPresent(FuzzTest.class))
                .as("%s must stay the @FuzzTest whose regression inputs pom.xml fills", FUZZ_TEST_METHOD)
                .isTrue();
        assertThat(regressionInputs)
                .as("pom.xml must map %s to the regression inputs of %s", SEED_CORPUS, FUZZ_TEST_METHOD)
                .isNotNull();
        assertThat(Path.of(regressionInputs.toURI()).toFile().list())
                .contains(SEED_CORPUS.toFile().list());
    }
}
