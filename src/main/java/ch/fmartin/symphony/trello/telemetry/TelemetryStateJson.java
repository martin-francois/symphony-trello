package ch.fmartin.symphony.trello.telemetry;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/// Jackson configuration for `telemetry.json`. Instants and dates are ISO-8601 strings; unknown
/// properties are rejected so a newer or foreign file is treated as unreadable rather than partly
/// understood.
final class TelemetryStateJson {
    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .disable(MapperFeature.AUTO_DETECT_GETTERS)
            .disable(MapperFeature.AUTO_DETECT_IS_GETTERS)
            .disable(MapperFeature.AUTO_DETECT_FIELDS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .addModule(new SimpleModule()
                    .addSerializer(Instant.class, ToStringSerializer.instance)
                    .addSerializer(LocalDate.class, ToStringSerializer.instance)
                    .addDeserializer(Instant.class, new InstantDeserializer())
                    .addDeserializer(LocalDate.class, new LocalDateDeserializer()))
            .build();

    private TelemetryStateJson() {}

    static String write(TelemetryState state) throws IOException {
        return MAPPER.writer(new DefaultPrettyPrinter().withObjectIndenter(new DefaultIndenter("  ", "\n")))
                        .writeValueAsString(state)
                + "\n";
    }

    static TelemetryState read(String json) throws IOException {
        TelemetryState state = MAPPER.readValue(json, TelemetryState.class);
        if (state == null) {
            throw new IOException("telemetry state is empty");
        }
        return state;
    }

    /// Jackson deserializers are Serializable, so the parser is a method of each concrete subclass
    /// instead of a lambda field that serialization could not restore.
    private abstract static class IsoDeserializer<T> extends StdDeserializer<T> {
        private static final long serialVersionUID = 1L;

        IsoDeserializer(Class<T> type) {
            super(type);
        }

        abstract T parse(String text);

        @Override
        public T deserialize(JsonParser json, DeserializationContext context) throws IOException {
            String text = json.getValueAsString();
            if (text == null) {
                throw new IOException("telemetry state contains a non-text date or time");
            }
            try {
                return parse(text);
            } catch (DateTimeParseException exception) {
                throw new IOException("telemetry state contains a malformed date or time", exception);
            }
        }
    }

    private static final class InstantDeserializer extends IsoDeserializer<Instant> {
        private static final long serialVersionUID = 1L;

        InstantDeserializer() {
            super(Instant.class);
        }

        @Override
        Instant parse(String text) {
            return Instant.parse(text);
        }
    }

    private static final class LocalDateDeserializer extends IsoDeserializer<LocalDate> {
        private static final long serialVersionUID = 1L;

        LocalDateDeserializer() {
            super(LocalDate.class);
        }

        @Override
        LocalDate parse(String text) {
            return LocalDate.parse(text);
        }
    }
}
