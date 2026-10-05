package ch.fmartin.symphony.trello.telemetry;

import java.util.EnumSet;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/// A fixed set of heartbeat values, each with the spelling it has on the wire. Stored-report
/// validation reads the values from the enum, so adding a value is one change.
interface WireVocabulary {
    String wireName();

    /// The value with this wire spelling, or empty for text outside the vocabulary.
    static <E extends Enum<E> & WireVocabulary> Optional<E> fromWireName(
            Class<E> vocabulary, @Nullable String wireName) {
        for (E value : EnumSet.allOf(vocabulary)) {
            if (value.wireName().equals(wireName)) {
                return Optional.of(value);
            }
        }
        return Optional.empty();
    }
}
