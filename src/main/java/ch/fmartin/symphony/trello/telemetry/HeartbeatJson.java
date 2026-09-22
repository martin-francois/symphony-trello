package ch.fmartin.symphony.trello.telemetry;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.json.JsonMapper;

/// The single serializer for heartbeat bodies. Auto-detection is off so only the annotated
/// allowlist fields can appear, and the pretty-printed output uses `\n` on every platform so the
/// preview, the log line, and the wire body are byte-identical.
public final class HeartbeatJson {
    private static final ObjectWriter WRITER = JsonMapper.builder()
            .disable(MapperFeature.AUTO_DETECT_GETTERS)
            .disable(MapperFeature.AUTO_DETECT_IS_GETTERS)
            .disable(MapperFeature.AUTO_DETECT_FIELDS)
            .disable(MapperFeature.AUTO_DETECT_CREATORS)
            .build()
            .writer(new DefaultPrettyPrinter().withObjectIndenter(new DefaultIndenter("  ", "\n")));

    private HeartbeatJson() {}

    public static String serialize(HeartbeatEvent event) {
        try {
            return WRITER.writeValueAsString(event);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("heartbeat serialization failed", exception);
        }
    }
}
