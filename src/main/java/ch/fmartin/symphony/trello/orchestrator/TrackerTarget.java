package ch.fmartin.symphony.trello.orchestrator;

import ch.fmartin.symphony.trello.config.EffectiveConfig;
import ch.fmartin.symphony.trello.tracker.TrackerEndpoint;
import java.util.Locale;
import java.util.Objects;

/// Stable, non-secret identity for one tracker board namespace.
record TrackerTarget(String kind, String endpointFingerprint, String resolvedBoardId) {
    static TrackerTarget from(EffectiveConfig config) {
        EffectiveConfig.TrackerConfig tracker = config.tracker();
        return from(tracker.kind(), tracker.endpoint(), tracker.resolvedBoardId());
    }

    static TrackerTarget from(String kind, String endpoint, String resolvedBoardId) {
        return new TrackerTarget(
                kind.toLowerCase(Locale.ROOT),
                TrackerEndpoint.fingerprint(endpoint),
                Objects.requireNonNull(resolvedBoardId, "resolvedBoardId"));
    }
}
