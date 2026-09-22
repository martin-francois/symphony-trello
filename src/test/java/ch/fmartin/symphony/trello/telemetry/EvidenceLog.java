package ch.fmartin.symphony.trello.telemetry;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/// Append-only, sanitized record of what the experiment observed: one JSON line per step with the
/// UTC time, the phase, bounded response fields, and an assertion status. Every string passes the
/// redactor, so neither the personal key nor the capture token can reach the file or the console.
final class EvidenceLog {
    static final String FILE_NAME = "evidence.jsonl";
    static final String REDACTED = "[redacted]";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path file;
    private final Clock clock;
    private final List<Supplier<String>> secrets;

    EvidenceLog(Path file, Clock clock, List<Supplier<String>> secrets) {
        this.file = file;
        this.clock = clock;
        this.secrets = secrets;
    }

    /// Statuses the final table uses; `PENDING` means the provider has not finished, never that
    /// the step passed.
    enum Status {
        OBSERVED,
        FAILED,
        PENDING,
        NOT_EXECUTED
    }

    void record(String phase, String step, Status status, Map<String, ?> fields) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("at", clock.instant().toString());
        line.put("phase", phase);
        line.put("step", step);
        line.put("status", status.name());
        fields.forEach((key, value) -> line.put(key, redact(String.valueOf(value))));
        try {
            Files.writeString(
                    file,
                    JSON.writeValueAsString(line) + "\n",
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException exception) {
            throw new IllegalStateException("evidence not written: " + file.getFileName(), exception);
        }
    }

    String redact(String text) {
        String result = text;
        for (Supplier<String> secret : secrets) {
            String value = secret.get();
            if (!value.isEmpty()) {
                result = result.replace(value, REDACTED);
            }
        }
        return result;
    }
}
