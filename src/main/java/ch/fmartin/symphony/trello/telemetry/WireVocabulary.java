package ch.fmartin.symphony.trello.telemetry;

import static com.google.common.collect.ImmutableSet.toImmutableSet;

import java.util.EnumSet;
import java.util.Set;

/// A fixed set of heartbeat values, each with the spelling it has on the wire. Stored-report
/// validation reads the values from the enum, so adding a value is one change.
interface WireVocabulary {
    String wireName();

    static <E extends Enum<E> & WireVocabulary> Set<String> wireNames(Class<E> vocabulary) {
        return EnumSet.allOf(vocabulary).stream().map(WireVocabulary::wireName).collect(toImmutableSet());
    }
}
