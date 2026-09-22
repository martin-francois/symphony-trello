package ch.fmartin.symphony.trello.telemetry;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/// The bundled privacy document, readable offline through `symphony-trello telemetry privacy`.
/// The same file is published as `docs/telemetry-privacy.md`; the build copies it into the jar.
public final class TelemetryPrivacyText {
    public static final String RESOURCE = "telemetry-privacy.md";

    private TelemetryPrivacyText() {}

    public static String load() {
        try (InputStream stream = TelemetryPrivacyText.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException("bundled privacy text " + RESOURCE + " is missing");
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("bundled privacy text could not be read", exception);
        }
    }
}
