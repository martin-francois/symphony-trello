package ch.fmartin.symphony.trello.telemetry;

import com.google.common.base.CharMatcher;
import com.google.common.net.InetAddresses;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.Optional;
import java.util.Properties;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/// Where heartbeats go and which public project token identifies the Symphony for Trello PostHog
/// project. The endpoint is fixed here; the token comes from a classpath resource that the release
/// workflow fills in. Tests and development runs may override both values through system
/// properties, and a missing or placeholder token suppresses delivery instead of failing.
public record TelemetryDistribution(URI endpoint, Optional<String> projectToken, Optional<String> problem) {
    public static final URI PRODUCTION_ENDPOINT = URI.create("https://eu.i.posthog.com/i/v0/e/");
    public static final String RESOURCE = "symphony-trello-telemetry.properties";
    public static final String ENDPOINT_PROPERTY = "symphony.trello.telemetry.endpoint";
    public static final String TOKEN_PROPERTY = "symphony.trello.telemetry.project-token";
    static final String TOKEN_KEY = "posthog.project-token";
    static final String TOKEN_PLACEHOLDER = "<unset>";
    private static final String PROJECT_TOKEN_PREFIX = "phc_";
    /// PostHog project tokens are `phc_` plus 43 letters and digits; the range leaves room for change
    /// and matches the check in `scripts/package-release-assets.sh`.
    private static final int MIN_TOKEN_BODY_LENGTH = 40;
    private static final int MAX_TOKEN_BODY_LENGTH = 64;
    private static final CharMatcher TOKEN_BODY_CHARACTERS =
            CharMatcher.inRange('a', 'z').or(CharMatcher.inRange('A', 'Z')).or(CharMatcher.inRange('0', '9'));

    public TelemetryDistribution(URI endpoint, Optional<String> projectToken) {
        this(endpoint, projectToken, Optional.empty());
    }

    public TelemetryDistribution {
        requireHttpsOrLoopback(endpoint);
    }

    /// A configuration that can never send, with the reason `telemetry status` shows. Preference
    /// and preview commands keep working; nothing falls back to the production endpoint.
    static TelemetryDistribution invalid(String problem) {
        return new TelemetryDistribution(PRODUCTION_ENDPOINT, Optional.empty(), Optional.of(problem));
    }

    public static TelemetryDistribution load() {
        return load(System::getProperty);
    }

    public static TelemetryDistribution load(Function<String, @Nullable String> systemProperties) {
        Properties bundled = new Properties();
        try (InputStream stream = TelemetryDistribution.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (stream != null) {
                bundled.load(stream);
            }
        } catch (IOException | IllegalArgumentException exception) {
            // A damaged bundled resource behaves like an unconfigured distribution: no token, no sends.
            bundled.clear();
        }
        String endpoint =
                firstNonBlank(systemProperties.apply(ENDPOINT_PROPERTY), null).orElseGet(PRODUCTION_ENDPOINT::toString);
        Optional<String> token = firstNonBlank(systemProperties.apply(TOKEN_PROPERTY), bundled.getProperty(TOKEN_KEY));
        try {
            return new TelemetryDistribution(
                    URI.create(endpoint.strip()), token.flatMap(TelemetryDistribution::validToken));
        } catch (IllegalArgumentException exception) {
            // An invalid explicit override must not break startup or setup, and must not silently
            // send to production instead; the value itself is not echoed.
            return invalid(ENDPOINT_PROPERTY + " is not a usable endpoint: " + exception.getMessage());
        }
    }

    public boolean ready() {
        return projectToken.isPresent() && problem.isEmpty();
    }

    static Optional<String> validToken(@Nullable String token) {
        if (token == null) {
            return Optional.empty();
        }
        String value = token.strip();
        if (!value.startsWith(PROJECT_TOKEN_PREFIX)) {
            return Optional.empty();
        }
        String body = value.substring(PROJECT_TOKEN_PREFIX.length());
        if (body.length() < MIN_TOKEN_BODY_LENGTH
                || body.length() > MAX_TOKEN_BODY_LENGTH
                || !TOKEN_BODY_CHARACTERS.matchesAllOf(body)) {
            return Optional.empty();
        }
        return Optional.of(value);
    }

    private static void requireHttpsOrLoopback(URI endpoint) {
        String scheme = endpoint.getScheme();
        String host = endpoint.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("telemetry endpoint must be an absolute URL with a host");
        }
        boolean loopback = "localhost".equals(host)
                || InetAddresses.isInetAddress(host)
                        && InetAddresses.forString(host).isLoopbackAddress();
        if (!"https".equals(scheme) && !(loopback && "http".equals(scheme))) {
            throw new IllegalArgumentException("telemetry endpoint must use https (http is allowed for loopback)");
        }
    }

    private static Optional<String> firstNonBlank(@Nullable String first, @Nullable String second) {
        if (first != null && !first.isBlank()) {
            return Optional.of(first);
        }
        if (second != null && !second.isBlank()) {
            return Optional.of(second);
        }
        return Optional.empty();
    }
}
