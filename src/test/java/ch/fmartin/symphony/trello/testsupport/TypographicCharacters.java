package ch.fmartin.symphony.trello.testsupport;

/// Characters that user-facing text must not contain, named so an assertion says which one it
/// found instead of showing a glyph that is easy to misread in a diff.
public final class TypographicCharacters {
    public static final String EM_DASH = "\u2014";
    public static final String LEFT_DOUBLE_QUOTATION_MARK = "\u201C";
    public static final String RIGHT_SINGLE_QUOTATION_MARK = "\u2019";

    private TypographicCharacters() {}
}
