package ch.fmartin.symphony.trello;

import com.google.common.base.CharMatcher;

/// Shared character classes whose semantics must stay aligned across package boundaries.
public final class TextCharacterMatchers {
    public static final String UNICODE_BYTE_ORDER_MARK = "\uFEFF";
    public static final String UNICODE_NEXT_LINE = "\u0085";
    public static final char UNICODE_LINE_SEPARATOR = '\u2028';
    public static final char UNICODE_PARAGRAPH_SEPARATOR = '\u2029';

    public static final char ARABIC_LETTER_MARK = 0x061C;
    public static final char LEFT_TO_RIGHT_MARK = 0x200E;
    public static final char RIGHT_TO_LEFT_MARK = 0x200F;
    public static final char LEFT_TO_RIGHT_EMBEDDING = 0x202A;
    public static final char RIGHT_TO_LEFT_EMBEDDING = 0x202B;
    public static final char POP_DIRECTIONAL_FORMAT = 0x202C;
    public static final char LEFT_TO_RIGHT_OVERRIDE = 0x202D;
    public static final char RIGHT_TO_LEFT_OVERRIDE = 0x202E;
    public static final char LEFT_TO_RIGHT_ISOLATE = 0x2066;
    public static final char RIGHT_TO_LEFT_ISOLATE = 0x2067;
    public static final char FIRST_STRONG_ISOLATE = 0x2068;
    public static final char POP_DIRECTIONAL_ISOLATE = 0x2069;

    public static final CharMatcher BIDIRECTIONAL_CONTROL_CHARACTERS = CharMatcher.anyOf(
                    String.valueOf(ARABIC_LETTER_MARK)
                            + LEFT_TO_RIGHT_MARK
                            + RIGHT_TO_LEFT_MARK
                            + LEFT_TO_RIGHT_EMBEDDING
                            + RIGHT_TO_LEFT_EMBEDDING
                            + POP_DIRECTIONAL_FORMAT
                            + LEFT_TO_RIGHT_OVERRIDE
                            + RIGHT_TO_LEFT_OVERRIDE
                            + LEFT_TO_RIGHT_ISOLATE
                            + RIGHT_TO_LEFT_ISOLATE
                            + FIRST_STRONG_ISOLATE
                            + POP_DIRECTIONAL_ISOLATE)
            .precomputed();

    public static final CharMatcher ISO_CONTROL_CHARACTERS =
            CharMatcher.javaIsoControl().precomputed();
    public static final CharMatcher UNSAFE_SINGLE_LINE_CHARACTERS = ISO_CONTROL_CHARACTERS
            .or(CharMatcher.anyOf(String.valueOf(UNICODE_LINE_SEPARATOR) + UNICODE_PARAGRAPH_SEPARATOR))
            .precomputed();
    public static final CharMatcher JAVA_TRIM_CHARACTERS = CharMatcher.inRange('\0', ' ');
    public static final CharMatcher DOTS = CharMatcher.is('.');
    public static final CharMatcher SLASHES = CharMatcher.is('/');

    private TextCharacterMatchers() {}
}
