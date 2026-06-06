package ch.fmartin.symphony.trello.setup;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

final class Sha3 {
    private Sha3() {}

    static String sha3_256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA3-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA3-256 is required by the JDK", e);
        }
    }
}
