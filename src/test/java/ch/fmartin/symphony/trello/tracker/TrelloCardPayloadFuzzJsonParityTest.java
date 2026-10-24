package ch.fmartin.symphony.trello.tracker;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

@Execution(ExecutionMode.SAME_THREAD)
@QuarkusTest
final class TrelloCardPayloadFuzzJsonParityTest {
    @Inject
    ObjectMapper productionJson;

    @Test
    void fuzzMapperParsesCardBodiesWithTheSettingsOfTheInjectedMapper() {
        // given
        ObjectMapper fuzzJson = TrelloCardPayloadFuzzInvariants.JSON;

        // when
        Map<String, Boolean> fuzzSettings = parsingSettings(fuzzJson);

        // then
        assertThat(fuzzSettings)
                .as("the fuzz target must parse a card body into the same Java types TrelloClient sees")
                .containsExactlyInAnyOrderEntriesOf(parsingSettings(productionJson));
    }

    private static Map<String, Boolean> parsingSettings(ObjectMapper mapper) {
        Map<String, Boolean> settings = new TreeMap<>();
        for (DeserializationFeature feature : DeserializationFeature.values()) {
            settings.put("DeserializationFeature." + feature, mapper.isEnabled(feature));
        }
        for (MapperFeature feature : MapperFeature.values()) {
            settings.put(
                    "MapperFeature." + feature,
                    mapper.getDeserializationConfig().isEnabled(feature));
        }
        for (JsonParser.Feature feature : JsonParser.Feature.values()) {
            settings.put("JsonParser.Feature." + feature, mapper.getFactory().isEnabled(feature));
        }
        return settings;
    }
}
