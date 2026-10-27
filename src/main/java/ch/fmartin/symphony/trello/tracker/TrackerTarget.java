package ch.fmartin.symphony.trello.tracker;

import ch.fmartin.symphony.trello.config.EffectiveConfig;
import java.util.Locale;
import java.util.Objects;

/// Stable, non-secret identity for one tracker board namespace.
///
/// @param kind normalized tracker kind
/// @param endpointFingerprint hash of the canonical endpoint, without exposing its credentials
/// @param resolvedBoardId resolved board identifier within that endpoint
public record TrackerTarget(String kind, String endpointFingerprint, String resolvedBoardId) {
    /// Identifies the configured tracker namespace after its board ID has been resolved.
    ///
    /// @param config effective configuration with the resolved tracker board
    /// @return the canonical tracker namespace identity
    public static TrackerTarget from(EffectiveConfig config) {
        EffectiveConfig.TrackerConfig tracker = config.tracker();
        return from(tracker.kind(), tracker.endpoint(), tracker.resolvedBoardId());
    }

    /// Canonicalizes the endpoint and hashes it so the identity carries no endpoint credentials.
    ///
    /// @param kind tracker kind
    /// @param endpoint absolute tracker API endpoint
    /// @param resolvedBoardId resolved board identifier within the endpoint
    /// @return the canonical tracker namespace identity
    public static TrackerTarget from(String kind, String endpoint, String resolvedBoardId) {
        return new TrackerTarget(
                kind.toLowerCase(Locale.ROOT),
                TrackerEndpoint.fingerprint(endpoint),
                Objects.requireNonNull(resolvedBoardId, "resolvedBoardId"));
    }
}
