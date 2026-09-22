package ch.fmartin.symphony.trello.telemetry;

import ch.fmartin.symphony.trello.telemetry.HeartbeatField.Names;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/// One complete PostHog capture request body. Preview output, log output, and the wire body come
/// from serializing this record once, so nothing can be appended after the preview boundary.
@JsonPropertyOrder({Names.API_KEY, Names.EVENT, Names.DISTINCT_ID, Names.UUID, Names.TIMESTAMP, Names.PROPERTIES})
public record HeartbeatEvent(
        @JsonProperty(Names.API_KEY) @Nullable String apiKey,
        @JsonProperty(Names.EVENT) String event,
        @JsonProperty(Names.DISTINCT_ID) @Nullable String distinctId,
        @JsonProperty(Names.UUID) @Nullable String uuid,
        @JsonProperty(Names.TIMESTAMP) @Nullable String timestamp,
        @JsonProperty(Names.PROPERTIES) HeartbeatProperties properties) {

    public static final String EVENT_NAME = "installation_heartbeat";

    /// Rejects preview placeholders and malformed identities so an incomplete body can never be sent.
    public HeartbeatEvent requireSendable() {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("telemetry project token is missing");
        }
        if (!EVENT_NAME.equals(event)) {
            throw new IllegalStateException("unexpected telemetry event name");
        }
        if (distinctId == null) {
            throw new IllegalStateException("telemetry distinct_id is missing");
        }
        String[] identityParts = distinctId.split("\\.", -1);
        if (identityParts.length > 2) {
            throw new IllegalStateException("telemetry distinct_id is malformed");
        }
        for (String identityPart : identityParts) {
            requireUuid(Names.DISTINCT_ID, identityPart);
        }
        requireUuid(Names.UUID, uuid);
        if (timestamp == null) {
            throw new IllegalStateException("telemetry timestamp is missing");
        }
        String registeredOn = properties.registeredOn();
        if (registeredOn == null) {
            throw new IllegalStateException("telemetry registration date is missing");
        }
        try {
            Instant.parse(timestamp);
            LocalDate.parse(registeredOn);
        } catch (DateTimeParseException exception) {
            throw new IllegalStateException("telemetry timestamp or registration date is malformed", exception);
        }
        return this;
    }

    private static void requireUuid(String field, @Nullable String value) {
        if (value == null) {
            throw new IllegalStateException("telemetry " + field + " is missing");
        }
        try {
            UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("telemetry " + field + " is not a UUID", exception);
        }
    }
}
