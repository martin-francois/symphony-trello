package ch.fmartin.symphony.trello.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.List;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

final class HeartbeatJsonTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String DISTINCT_ID = "74a69e87-089d-4fd1-8ac2-df8aab052cb1";
    private static final String EVENT_UUID = "95127c85-1c9e-460c-b652-0a3a7899cbde";
    private static final String TIMESTAMP = "2026-09-22T09:15:00Z";

    @Test
    void serializedBodyMatchesTheIllustrativeContractExactly() throws IOException {
        // given
        HeartbeatEvent event = new HeartbeatEvent(
                "phc_token",
                HeartbeatEvent.EVENT_NAME,
                DISTINCT_ID,
                EVENT_UUID,
                TIMESTAMP,
                new HeartbeatProperties(
                        1, "2026-09-22", "1.2.0", "linux", "24.04", "ubuntu", "arm64", 3, 4, 2, true, true));

        // when
        String json = HeartbeatJson.serialize(event);

        // then
        assertThat(json)
                .isEqualTo(
                        """
                        {
                          "api_key" : "phc_token",
                          "event" : "installation_heartbeat",
                          "distinct_id" : "74a69e87-089d-4fd1-8ac2-df8aab052cb1",
                          "uuid" : "95127c85-1c9e-460c-b652-0a3a7899cbde",
                          "timestamp" : "2026-09-22T09:15:00Z",
                          "properties" : {
                            "telemetry_schema_version" : 1,
                            "registered_on" : "2026-09-22",
                            "app_version" : "1.2.0",
                            "os_family" : "linux",
                            "os_release" : "24.04",
                            "linux_distribution" : "ubuntu",
                            "runtime_arch" : "arm64",
                            "connected_board_count" : 3,
                            "board_imports_total" : 4,
                            "board_creations_total" : 2,
                            "$geoip_disable" : true,
                            "$process_person_profile" : true
                          }
                        }""");
        JsonNode parsed = JSON.readTree(json);
        assertThat(parsed.fieldNames())
                .toIterable()
                .containsExactly(envelopeNames().toArray(String[]::new));
        assertThat(parsed.get("properties").fieldNames())
                .toIterable()
                .containsExactly(propertyNames().toArray(String[]::new));
    }

    @Test
    void nullStaysDistinctFromZeroAndIsWrittenExplicitly() throws IOException {
        // given
        HeartbeatEvent event = new HeartbeatEvent(
                null,
                HeartbeatEvent.EVENT_NAME,
                null,
                EVENT_UUID,
                TIMESTAMP,
                new HeartbeatProperties(1, null, null, "windows", "11", null, "x64", null, 0, 0, true, true));

        // when
        JsonNode parsed = JSON.readTree(HeartbeatJson.serialize(event));

        // then
        assertThat(parsed.get("api_key").isNull())
                .as("missing token is an explicit null")
                .isTrue();
        assertThat(parsed.get("distinct_id").isNull())
                .as("missing identity is an explicit null")
                .isTrue();
        assertThat(parsed.get("properties").get("connected_board_count").isNull())
                .as("unknown board count is null, not zero")
                .isTrue();
        assertThat(parsed.get("properties").get("board_imports_total").asInt()).isZero();
        assertThat(parsed.get("properties").get("linux_distribution").isNull())
                .as("non-Linux distribution is null")
                .isTrue();
    }

    @CsvSource({
        "windows, windows, 11, , x64",
        "macos, macos, 26, , arm64",
        "linux, linux, 13, debian, x64",
        "unknown, unknown, unknown, , unknown"
    })
    @ParameterizedTest(name = "{0}")
    void representativePlatformsSerializeWithinTheAllowlist(
            String scenario, String family, String release, String distribution, String arch) throws IOException {
        // given
        HeartbeatProperties properties = new HeartbeatProperties(
                1, "2026-09-22", "1.2.0", family, release, distribution, arch, 1, 1, 0, true, true);
        HeartbeatEvent event = new HeartbeatEvent(
                "phc_token", HeartbeatEvent.EVENT_NAME, DISTINCT_ID, EVENT_UUID, TIMESTAMP, properties);

        // when
        JsonNode parsed = JSON.readTree(HeartbeatJson.serialize(event));

        // then
        assertThat(parsed.get("properties").get("os_family").asText()).isEqualTo(family);
        assertThat(parsed.get("properties").get("os_release").asText()).isEqualTo(release);
        assertThat(parsed.get("properties").get("runtime_arch").asText()).isEqualTo(arch);
        assertThat(parsed.get("properties").get("$geoip_disable").asBoolean())
                .as("$geoip_disable is always true")
                .isTrue();
        assertThat(parsed.get("properties").get("$process_person_profile").asBoolean())
                .as("$process_person_profile is always true")
                .isTrue();
    }

    @Test
    void placeholderPreviewIsNeverSendable() {
        // given
        HeartbeatEvent placeholder = new HeartbeatEvent(
                "phc_token",
                HeartbeatEvent.EVENT_NAME,
                null,
                EVENT_UUID,
                TIMESTAMP,
                new HeartbeatProperties(1, null, null, "linux", "13", "debian", "x64", 0, 0, 0, true, true));

        // when
        ThrowingCallable send = placeholder::requireSendable;

        // then
        assertThatIllegalStateException().isThrownBy(send).withMessageContaining("distinct_id");
    }

    @CsvSource({
        "missing token, , 74a69e87-089d-4fd1-8ac2-df8aab052cb1, 2026-09-22T09:15:00Z, 2026-09-22, token",
        "malformed identity, phc_t, not-a-uuid, 2026-09-22T09:15:00Z, 2026-09-22, distinct_id",
        "malformed timestamp, phc_t, 74a69e87-089d-4fd1-8ac2-df8aab052cb1, yesterday, 2026-09-22, timestamp",
        "missing registration, phc_t, 74a69e87-089d-4fd1-8ac2-df8aab052cb1, 2026-09-22T09:15:00Z, , registration"
    })
    @ParameterizedTest(name = "{0}")
    void sendabilityValidatesEveryIdentityAndTimeField(
            String scenario, String token, String distinctId, String timestamp, String registeredOn, String expected) {
        // given
        HeartbeatEvent event = new HeartbeatEvent(
                token,
                HeartbeatEvent.EVENT_NAME,
                distinctId,
                EVENT_UUID,
                timestamp,
                new HeartbeatProperties(1, registeredOn, null, "linux", "13", "debian", "x64", 0, 0, 0, true, true));

        // when
        ThrowingCallable send = event::requireSendable;

        // then
        assertThatIllegalStateException().isThrownBy(send).withMessageContaining(expected);
    }

    @Test
    void fieldCatalogAndJsonNamesAgree() {
        // given
        List<String> catalogProperties = HeartbeatField.properties().stream()
                .map(HeartbeatField::jsonName)
                .toList();

        // when
        List<String> serialized = propertyNames();

        // then
        assertThat(serialized).containsExactlyElementsOf(catalogProperties);
    }

    private static List<String> envelopeNames() {
        return List.of("api_key", "event", "distinct_id", "uuid", "timestamp", "properties");
    }

    private static List<String> propertyNames() {
        return List.of(
                "telemetry_schema_version",
                "registered_on",
                "app_version",
                "os_family",
                "os_release",
                "linux_distribution",
                "runtime_arch",
                "connected_board_count",
                "board_imports_total",
                "board_creations_total",
                "$geoip_disable",
                "$process_person_profile");
    }
}
