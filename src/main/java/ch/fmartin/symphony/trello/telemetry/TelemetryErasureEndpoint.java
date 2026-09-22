package ch.fmartin.symphony.trello.telemetry;

import java.net.URI;

/// Public PostHog source URL and project-bound proof audience; neither value is a credential.
public record TelemetryErasureEndpoint(URI uri, String audience) {
    public TelemetryErasureEndpoint {
        TelemetryDistribution.requireHttpsOrLoopback(uri);
        if (uri.getUserInfo() != null
                || uri.getFragment() != null
                || uri.getQuery() != null
                || audience == null
                || !audience.matches("[a-z0-9:-]{1,80}")) {
            throw new IllegalArgumentException("invalid erasure service configuration");
        }
    }
}
