package ch.fmartin.symphony.trello.testsupport;

public final class TestFileContents {
    /// The cause summary of reading [#invalidUtf8()] as UTF-8.
    public static final String INVALID_UTF_8_READ_FAILURE = "MalformedInputException: Input length = 1";

    /// A credential-shaped value that parser messages must never carry into failure output.
    public static final String PRIVATE_YAML_VALUE = "private-token-value";

    /// YAML front matter that fails to parse on the line holding [#PRIVATE_YAML_VALUE], so the
    /// parser message quotes that value.
    public static final String INVALID_YAML_QUOTING_PRIVATE_VALUE =
            "tracker:\n  api_token: " + PRIVATE_YAML_VALUE + ": [\n";

    private TestFileContents() {}

    /// A truncated two-byte UTF-8 sequence, so UTF-8 readers fail with a one-byte malformed input.
    public static byte[] invalidUtf8() {
        return new byte[] {(byte) 0xc3, (byte) 0x28};
    }
}
