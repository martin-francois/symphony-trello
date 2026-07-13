package ch.fmartin.symphony.trello;

import com.google.common.base.Splitter;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Shared comma-delimited parsing with explicit empty-field and Java-trim contracts. */
public final class CommaSeparatedValues {
    private static final Splitter PRESERVING_EMPTY_FIELDS = Splitter.on(',');
    private static final Splitter JAVA_TRIMMED_NON_EMPTY_FIELDS = PRESERVING_EMPTY_FIELDS
            .trimResults(TextCharacterMatchers.JAVA_TRIM_CHARACTERS)
            .omitEmptyStrings();

    private CommaSeparatedValues() {}

    public static List<String> preservingEmptyFields(String value) {
        return PRESERVING_EMPTY_FIELDS.splitToList(value);
    }

    public static List<String> javaTrimmedNonEmptyFields(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return JAVA_TRIMMED_NON_EMPTY_FIELDS.splitToList(value);
    }
}
