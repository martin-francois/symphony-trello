package ch.fmartin.symphony.trello.telemetry;

import static com.google.common.collect.ImmutableSet.toImmutableSet;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/// A fixed set of heartbeat values, each with the spelling it has on the wire. Stored-report
/// validation and the field descriptions read the values from the enum, so adding a value is one
/// change.
interface WireVocabulary {
    String wireName();

    static <E extends Enum<E> & WireVocabulary> Set<String> wireNames(Class<E> vocabulary) {
        return EnumSet.allOf(vocabulary).stream().map(WireVocabulary::wireName).collect(toImmutableSet());
    }

    static <E extends Enum<E> & WireVocabulary> E fromWireName(Class<E> vocabulary, String wireName) {
        return EnumSet.allOf(vocabulary).stream()
                .filter(value -> value.wireName().equals(wireName))
                .findAny()
                .orElseThrow(() ->
                        new IllegalArgumentException("not a " + vocabulary.getSimpleName() + " value: " + wireName));
    }

    /// The values in declaration order as prose, for example "`a`, `b`, or `c`".
    static <E extends Enum<E> & WireVocabulary> String describe(Class<E> vocabulary) {
        List<String> quoted = EnumSet.allOf(vocabulary).stream()
                .map(value -> "`" + value.wireName() + "`")
                .toList();
        return String.join(", ", quoted.subList(0, quoted.size() - 1)) + ", or " + quoted.getLast();
    }
}
