package ch.fmartin.symphony.trello.telemetry;

import java.util.Arrays;
import java.util.List;

/// The complete list of fields in an `installation_heartbeat` request body. Every JSON property
/// name derives from this catalog, and a test checks that the privacy document and the
/// specification name every field, so a new field cannot reach the wire undocumented.
public enum HeartbeatField {
    API_KEY(Names.API_KEY, Envelope.ENVELOPE),
    EVENT(Names.EVENT, Envelope.ENVELOPE),
    DISTINCT_ID(Names.DISTINCT_ID, Envelope.ENVELOPE),
    UUID(Names.UUID, Envelope.ENVELOPE),
    TIMESTAMP(Names.TIMESTAMP, Envelope.ENVELOPE),
    TELEMETRY_SCHEMA_VERSION(Names.TELEMETRY_SCHEMA_VERSION, Envelope.PROPERTY),
    REGISTERED_ON(Names.REGISTERED_ON, Envelope.PROPERTY),
    APP_VERSION(Names.APP_VERSION, Envelope.PROPERTY),
    OS_FAMILY(Names.OS_FAMILY, Envelope.PROPERTY),
    OS_RELEASE(Names.OS_RELEASE, Envelope.PROPERTY),
    LINUX_DISTRIBUTION(Names.LINUX_DISTRIBUTION, Envelope.PROPERTY),
    RUNTIME_ARCH(Names.RUNTIME_ARCH, Envelope.PROPERTY),
    CONNECTED_BOARD_COUNT(Names.CONNECTED_BOARD_COUNT, Envelope.PROPERTY),
    BOARD_IMPORTS_TOTAL(Names.BOARD_IMPORTS_TOTAL, Envelope.PROPERTY),
    BOARD_CREATIONS_TOTAL(Names.BOARD_CREATIONS_TOTAL, Envelope.PROPERTY),
    GEOIP_DISABLE(Names.GEOIP_DISABLE, Envelope.PROPERTY),
    PROCESS_PERSON_PROFILE(Names.PROCESS_PERSON_PROFILE, Envelope.PROPERTY);

    private final String jsonName;
    private final Envelope envelope;

    HeartbeatField(String jsonName, Envelope envelope) {
        this.jsonName = jsonName;
        this.envelope = envelope;
    }

    public String jsonName() {
        return jsonName;
    }

    public boolean isProperty() {
        return envelope == Envelope.PROPERTY;
    }

    public static List<HeartbeatField> properties() {
        return Arrays.stream(values()).filter(HeartbeatField::isProperty).toList();
    }

    private enum Envelope {
        ENVELOPE,
        PROPERTY
    }

    /// Compile-time constants for Jackson annotations.
    public static final class Names {
        public static final String API_KEY = "api_key";
        public static final String EVENT = "event";
        public static final String DISTINCT_ID = "distinct_id";
        public static final String UUID = "uuid";
        public static final String TIMESTAMP = "timestamp";
        public static final String PROPERTIES = "properties";
        public static final String TELEMETRY_SCHEMA_VERSION = "telemetry_schema_version";
        public static final String REGISTERED_ON = "registered_on";
        public static final String APP_VERSION = "app_version";
        public static final String OS_FAMILY = "os_family";
        public static final String OS_RELEASE = "os_release";
        public static final String LINUX_DISTRIBUTION = "linux_distribution";
        public static final String RUNTIME_ARCH = "runtime_arch";
        public static final String CONNECTED_BOARD_COUNT = "connected_board_count";
        public static final String BOARD_IMPORTS_TOTAL = "board_imports_total";
        public static final String BOARD_CREATIONS_TOTAL = "board_creations_total";
        public static final String GEOIP_DISABLE = "$geoip_disable";
        public static final String PROCESS_PERSON_PROFILE = "$process_person_profile";

        private Names() {}
    }
}
