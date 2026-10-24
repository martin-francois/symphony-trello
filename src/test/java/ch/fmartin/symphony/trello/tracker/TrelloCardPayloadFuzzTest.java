package ch.fmartin.symphony.trello.tracker;

import static ch.fmartin.symphony.trello.tracker.TrelloCardPayloadFuzzInvariants.assertMappingKeepsItsContract;
import static ch.fmartin.symphony.trello.tracker.TrelloCardPayloadFuzzInvariants.mapCardPayload;
import static org.assertj.core.api.Assertions.assertThat;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import java.lang.reflect.Method;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class TrelloCardPayloadFuzzTest {
    private static final Path SEED_CORPUS = Path.of("oss-fuzz/corpora/TrelloCardPayloadFuzzer");
    // pom.xml copies the seed corpus to the Jazzer regression inputs of this method.
    private static final String FUZZ_TEST_METHOD = "cardPayloadMappingReturnsACardOrRejectsTheUnknownPayload";

    @FuzzTest(maxDuration = "10s", maxExecutions = 20_000)
    void cardPayloadMappingReturnsACardOrRejectsTheUnknownPayload(FuzzedDataProvider data) {
        // given
        FuzzedDataProvider fuzzedCardResponse = data;

        // when
        TrelloCardPayloadFuzzInvariants.MappingResult result = mapCardPayload(fuzzedCardResponse);

        // then
        assertMappingKeepsItsContract(result);
    }

    @Test
    void regressionInputsReplayEverySeed() throws NoSuchMethodException, URISyntaxException {
        // given
        Method fuzzTest = TrelloCardPayloadFuzzTest.class.getDeclaredMethod(FUZZ_TEST_METHOD, FuzzedDataProvider.class);

        // when
        URL regressionInputs = TrelloCardPayloadFuzzTest.class.getResource(
                TrelloCardPayloadFuzzTest.class.getSimpleName() + "Inputs/" + FUZZ_TEST_METHOD);

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
