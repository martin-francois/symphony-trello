package ch.fmartin.symphony.trello.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/// Replaces the volatile values of one snapshot scenario with named placeholders.
///
/// Every replacement is an exact value that the scenario registers; there are no pattern-based
/// rules, so normalization cannot erase wording, whitespace, ordering, quoting, or stream
/// placement. Prefer fixing an input in the fixture over registering a replacement. Credential
/// values are never accepted here: a leaked credential must fail the sentinel check instead of
/// disappearing behind a placeholder.
public final class TranscriptNormalizer {
    /// Placeholder for a scenario's isolated temporary root.
    public static final String TEMPORARY_ROOT = "<TEMP>";
    private static final Pattern PLACEHOLDER = Pattern.compile("<[A-Z][A-Z0-9_]*>");

    private final List<Replacement> replacements;
    private final List<String> leakMarkers;

    private TranscriptNormalizer(List<Replacement> replacements, List<String> leakMarkers) {
        this.replacements = List.copyOf(replacements);
        this.leakMarkers = List.copyOf(leakMarkers);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static TranscriptNormalizer none() {
        return builder().build();
    }

    String normalize(String text) {
        String normalized = text;
        for (Replacement replacement : replacements) {
            normalized = normalized.replace(replacement.value(), replacement.placeholder());
        }
        return normalized;
    }

    /// Fails when a registered temporary root survived normalization, for example because the
    /// command wrapped or folded a long path across lines. Such output depends on the host's
    /// temporary-directory length, so the scenario must be fixed rather than the snapshot updated.
    void assertNoUnnormalizedValues(String scenario, String normalized) {
        if (leakMarkers.isEmpty()) {
            return;
        }
        assertThat(normalized)
                .as(
                        "snapshot %s still contains part of a temporary path after normalization; the output"
                                + " probably wrapped or folded a path, which makes it depend on the host's"
                                + " temporary-directory length",
                        scenario)
                .doesNotContain(leakMarkers);
    }

    public static final class Builder {
        // Registration order breaks ties between equally long values, so normalization is repeatable.
        private final Map<String, String> placeholdersByValue = new LinkedHashMap<>();
        private final List<String> leakMarkers = new ArrayList<>();

        private Builder() {}

        /// Replaces an isolated temporary root, and its real path when it differs, with the
        /// placeholder. The root's own directory name, which JUnit randomizes, must not survive.
        public Builder temporaryRoot(Path root, String placeholder) {
            Path absolute = root.toAbsolutePath().normalize();
            register(absolute.toString(), placeholder);
            try {
                if (Files.exists(absolute)) {
                    register(absolute.toRealPath().toString(), placeholder);
                }
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
            // A path wrapped across lines escapes the exact replacement. The randomized directory
            // name or the parent temporary directory then remains visible and fails the check.
            Path fileName = absolute.getFileName();
            if (fileName != null) {
                leakMarkers.add(fileName.toString());
            }
            Path parent = absolute.getParent();
            if (parent != null && parent.getParent() != null) {
                leakMarkers.add(parent.toString());
            }
            return this;
        }

        /// Replaces one exact generated value, such as a dynamically allocated port written as
        /// `127.0.0.1:12345`, or a release version that changes with every release.
        public Builder literal(String value, String placeholder) {
            register(value, placeholder);
            return this;
        }

        private void register(String value, String placeholder) {
            if (value.isEmpty()) {
                throw new IllegalArgumentException("A normalized value must not be empty");
            }
            for (String sentinel : CredentialSentinels.ALL) {
                if (value.contains(sentinel)) {
                    throw new IllegalArgumentException(
                            "Credential sentinels must never be normalized; a leak must fail the sentinel check");
                }
            }
            if (!PLACEHOLDER.matcher(placeholder).find()) {
                throw new IllegalArgumentException(
                        "A replacement must contain an upper-case placeholder name in angle brackets: " + placeholder);
            }
            String existing = placeholdersByValue.putIfAbsent(value, placeholder);
            if (existing != null && !existing.equals(placeholder)) {
                throw new IllegalArgumentException(
                        "Value is already normalized as " + existing + ", not " + placeholder + ": " + value);
            }
        }

        public TranscriptNormalizer build() {
            // Longer values first, so a nested path is replaced before the root that contains it.
            List<Replacement> ordered = placeholdersByValue.entrySet().stream()
                    .map(entry -> new Replacement(entry.getKey(), entry.getValue()))
                    .sorted(Comparator.comparingInt((Replacement replacement) ->
                                    replacement.value().length())
                            .reversed())
                    .toList();
            return new TranscriptNormalizer(ordered, leakMarkers);
        }
    }

    private record Replacement(String value, String placeholder) {}
}
