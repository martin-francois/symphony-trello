package ch.fmartin.symphony.trello.telemetry;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.jspecify.annotations.Nullable;

/// An installation's ownership credential. Only the issuer sends the secret over the network;
/// subsequent requests carry a signature. Diagnostic rendering deliberately omits the secret.
public record TelemetryCredential(
        @JsonProperty("installation_id") UUID installationId,
        @JsonProperty("key_version") String keyVersion,
        @JsonProperty("secret") String secret) {
    private static final Pattern KEY_VERSION = Pattern.compile("k[1-9][0-9]{0,3}");
    private static final Pattern SECRET = Pattern.compile("[0-9a-f]{64}");
    // PostHog Hog offers only SHA-256 HMAC (`sha256HmacChainHex`), so the protocol cannot use SHA3.
    private static final String ALGORITHM = "HmacSHA256";
    private static final int RANDOM_UUID_VERSION = 4;
    private static final int IETF_UUID_VARIANT = 2;

    public TelemetryCredential {
        if (!isRandom(installationId)) {
            throw new IllegalArgumentException("ownership credential requires a random installation UUID");
        }
        if (keyVersion == null || !KEY_VERSION.matcher(keyVersion).matches()) {
            throw new IllegalArgumentException("ownership credential has an unsupported key version");
        }
        if (secret == null || !SECRET.matcher(secret).matches()) {
            throw new IllegalArgumentException("ownership credential has an invalid secret");
        }
    }

    static boolean isRandom(@Nullable UUID value) {
        return value != null && value.version() == RANDOM_UUID_VERSION && value.variant() == IETF_UUID_VARIANT;
    }

    public String subject() {
        return "h1-" + keyVersion + "-" + installationId;
    }

    /// PostHog Hog and the reference protocol use the UTF-8 hexadecimal text as the HMAC key.
    String sign(String unsigned) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(unsigned.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", exception);
        }
    }

    @Override
    public String toString() {
        return "TelemetryCredential[installationId=" + installationId + ", keyVersion=" + keyVersion
                + ", secret=redacted]";
    }
}
