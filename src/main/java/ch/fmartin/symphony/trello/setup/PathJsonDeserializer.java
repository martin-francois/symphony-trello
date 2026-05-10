package ch.fmartin.symphony.trello.setup;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;

final class PathJsonDeserializer extends JsonDeserializer<Path> {
    @Override
    public Path deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        String value = parser.getValueAsString();
        if (value != null && value.startsWith("file:")) {
            return Path.of(URI.create(value));
        }
        return Path.of(value);
    }
}
