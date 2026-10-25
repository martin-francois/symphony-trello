package ch.fmartin.symphony.trello.setup;

import com.google.common.base.CharMatcher;
import com.google.common.base.Splitter;
import com.google.common.collect.Comparators;
import java.math.BigInteger;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.NullMarked;

/// A Symphony release version such as `1.4.0` or `1.5.0-SNAPSHOT`. Workflow body migration uses it to
/// tell an update from a downgrade and to reject downgrades across major versions.
@NullMarked
record SymphonyVersion(int major, int minor, int patch, String qualifier) implements Comparable<SymphonyVersion> {
    /// Version reported when the classes do not run from the packaged application, such as in tests.
    static final String UNPACKAGED_VERSION = "0.1.0-SNAPSHOT";

    private static final CharMatcher ASCII_DIGIT = CharMatcher.inRange('0', '9');
    private static final CharMatcher QUALIFIER_CHARACTER = ASCII_DIGIT
            .or(CharMatcher.inRange('a', 'z'))
            .or(CharMatcher.inRange('A', 'Z'))
            .or(CharMatcher.anyOf(".-"));
    private static final Splitter DOT_SPLITTER = Splitter.on('.');
    private static final int VERSION_NUMBER_COUNT = 3;
    private static final Comparator<SymphonyVersion> ORDER = Comparator.comparingInt(SymphonyVersion::major)
            .thenComparingInt(SymphonyVersion::minor)
            .thenComparingInt(SymphonyVersion::patch)
            // A release sorts after its own pre-releases, as in Semantic Versioning.
            .thenComparing(SymphonyVersion::release)
            .thenComparing(
                    SymphonyVersion::preReleaseIdentifiers,
                    Comparators.lexicographical(SymphonyVersion::comparePreReleaseIdentifiers))
            // Keeps compareTo consistent with equals when SemVer ranks two spellings alike, such as rc.01 and rc.1.
            .thenComparing(SymphonyVersion::qualifier);

    static Optional<SymphonyVersion> parse(String text) {
        String version = text.strip();
        int buildSeparator = version.indexOf('+');
        if (buildSeparator >= 0) {
            String buildMetadata = version.substring(buildSeparator + 1);
            if (buildMetadata.isEmpty()
                    || !QUALIFIER_CHARACTER.matchesAllOf(buildMetadata)
                    || DOT_SPLITTER.splitToStream(buildMetadata).anyMatch(String::isEmpty)) {
                return Optional.empty();
            }
            // Build identifiers have no precedence and must not hide a release's major version.
            version = version.substring(0, buildSeparator);
        }
        int qualifierSeparator = version.indexOf('-');
        String qualifier = qualifierSeparator < 0 ? "" : version.substring(qualifierSeparator + 1);
        List<String> numbers =
                DOT_SPLITTER.splitToList(qualifierSeparator < 0 ? version : version.substring(0, qualifierSeparator));
        boolean validQualifier =
                qualifierSeparator < 0 || (!qualifier.isEmpty() && QUALIFIER_CHARACTER.matchesAllOf(qualifier));
        boolean validNumbers = numbers.size() == VERSION_NUMBER_COUNT
                && numbers.stream().allMatch(number -> !number.isEmpty() && ASCII_DIGIT.matchesAllOf(number));
        if (!validQualifier || !validNumbers) {
            return Optional.empty();
        }
        try {
            return Optional.of(new SymphonyVersion(
                    Integer.parseInt(numbers.getFirst()),
                    Integer.parseInt(numbers.get(1)),
                    Integer.parseInt(numbers.get(2)),
                    qualifier));
        } catch (NumberFormatException e) {
            // Digits beyond int range are not a Symphony version; callers treat it like any other
            // unrecognized version text.
            return Optional.empty();
        }
    }

    /// The version of the running Symphony application, as written into the packaged jar manifest.
    static String currentText() {
        String version = SymphonyVersion.class.getPackage().getImplementationVersion();
        return version == null ? UNPACKAGED_VERSION : version;
    }

    boolean release() {
        return qualifier.isEmpty();
    }

    private List<String> preReleaseIdentifiers() {
        return DOT_SPLITTER.splitToList(qualifier);
    }

    /// Semantic Versioning 2.0.0 precedence: numeric identifiers compare as numbers and sort before
    /// alphanumeric ones, which compare in ASCII order. So `rc.10` follows `rc.9`.
    private static int comparePreReleaseIdentifiers(String left, String right) {
        boolean leftNumeric = numericIdentifier(left);
        boolean rightNumeric = numericIdentifier(right);
        if (leftNumeric && rightNumeric) {
            return new BigInteger(left).compareTo(new BigInteger(right));
        }
        if (leftNumeric != rightNumeric) {
            return leftNumeric ? -1 : 1;
        }
        return left.compareTo(right);
    }

    private static boolean numericIdentifier(String identifier) {
        return !identifier.isEmpty() && ASCII_DIGIT.matchesAllOf(identifier);
    }

    @Override
    public int compareTo(SymphonyVersion other) {
        return ORDER.compare(this, other);
    }

    @Override
    public String toString() {
        return major + "." + minor + "." + patch + (release() ? "" : "-" + qualifier);
    }
}
