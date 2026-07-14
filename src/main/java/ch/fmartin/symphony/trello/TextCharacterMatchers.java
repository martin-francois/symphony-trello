package ch.fmartin.symphony.trello;

import com.google.common.base.CharMatcher;

/** Shared character classes whose semantics must stay aligned across package boundaries. */
public final class TextCharacterMatchers {
    public static final String UNICODE_BYTE_ORDER_MARK = "\uFEFF";
    public static final String UNICODE_NEXT_LINE = "\u0085";
    public static final char UNICODE_LINE_SEPARATOR = '\u2028';
    public static final char UNICODE_PARAGRAPH_SEPARATOR = '\u2029';

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
