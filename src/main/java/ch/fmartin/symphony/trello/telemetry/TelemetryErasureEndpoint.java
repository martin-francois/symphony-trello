package ch.fmartin.symphony.trello.telemetry;

import java.net.URI;
import java.util.regex.Pattern;

/// Public PostHog source URL and project-bound proof audience; neither value is a credential.
public record TelemetryErasureEndpoint(URI uri, String audience) {
    // Same rule as the deployment scope check in scripts/erasure-service.ts.
    static final Pattern AUDIENCE = Pattern.compile("[a-z0-9:-]{1,80}");

    public TelemetryErasureEndpoint {
        TelemetryDistribution.requireHttpsOrLoopback(uri);
        if (uri.getUserInfo() != null
                || uri.getFragment() != null
                || uri.getQuery() != null
                || audience == null
                || !AUDIENCE.matcher(audience).matches()) {
            throw new IllegalArgumentException("invalid erasure service configuration");
        }
    }
}
