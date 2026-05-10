package ch.fmartin.symphony.trello.setup;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import java.io.IOException;
import java.nio.file.Path;

final class PathJsonSerializer extends JsonSerializer<Path> {
    @Override
    public void serialize(Path value, JsonGenerator generator, SerializerProvider serializers) throws IOException {
        generator.writeString(value.toString());
    }
}
