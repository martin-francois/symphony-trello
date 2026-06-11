package ch.fmartin.symphony.trello.config;

import java.math.BigDecimal;
import java.util.OptionalInt;

/**
 * Precise whole-number classification for numeric configuration values. YAML and environment
 * sources deliver numbers as arbitrary {@link Number} text (Integer, Long, BigInteger, Double),
 * and naive {@code intValue()} silently truncates fractional values such as {@code 18080.5}.
 * Going through BigDecimal keeps huge integers and huge doubles exact, so a fractional value, a
 * whole-but-too-large value, and a non-numeric value each classify distinctly; whole-valued
 * floats such as {@code 18080.0} normalize to their integer value everywhere.
 */
public final class WholeNumbers {
    public enum Kind {
        WHOLE,
        FRACTIONAL,
        OUT_OF_INT_RANGE,
        NOT_A_NUMBER
    }

    public record Classified(Kind kind, int value) {}

    private WholeNumbers() {}

    public static Classified classify(String text) {
        BigDecimal decimal;
        try {
            decimal = new BigDecimal(text.trim());
        } catch (NumberFormatException e) {
            // Also reached for YAML float overflow such as 1e400, which arrives as Infinity.
            return new Classified(Kind.NOT_A_NUMBER, 0);
        }
        try {
            if (decimal.stripTrailingZeros().scale() > 0) {
                return new Classified(Kind.FRACTIONAL, 0);
            }
            return new Classified(Kind.WHOLE, decimal.intValueExact());
        } catch (ArithmeticException e) {
            // stripTrailingZeros itself overflows on pathological exponents such as 100E+2147483647.
            return new Classified(Kind.OUT_OF_INT_RANGE, 0);
        }
    }

    public static OptionalInt wholeInt(String text) {
        Classified classified = classify(text);
        return classified.kind() == Kind.WHOLE ? OptionalInt.of(classified.value()) : OptionalInt.empty();
    }
}
