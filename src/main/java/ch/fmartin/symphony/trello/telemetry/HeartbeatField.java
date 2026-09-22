package ch.fmartin.symphony.trello.telemetry;

import java.util.Arrays;
import java.util.List;

/// The complete list of fields in an `installation_heartbeat` request body. Every JSON property
/// name derives from this catalog, and a test keeps the privacy document and the short notice
/// consistent with it so the wire format and the user-facing text cannot drift apart.
public enum HeartbeatField {
    API_KEY(Names.API_KEY, Envelope.ENVELOPE, "The public PostHog project token of the Symphony for Trello project."),
    EVENT(Names.EVENT, Envelope.ENVELOPE, "Always `installation_heartbeat`; there is no other event type."),
    DISTINCT_ID(
            Names.DISTINCT_ID,
            Envelope.ENVELOPE,
            "The random installation ID. It is created locally, shared by all workers of one installation, and"
                    + " is not derived from hardware, hostname, user name, or Trello identity."),
    UUID(Names.UUID, Envelope.ENVELOPE, "A random ID for this one report. Retries of the same report reuse it."),
    TIMESTAMP(Names.TIMESTAMP, Envelope.ENVELOPE, "The UTC time the report was built. Retries keep the original time."),
    TELEMETRY_SCHEMA_VERSION(
            Names.TELEMETRY_SCHEMA_VERSION,
            Envelope.PROPERTY,
            "The version of this field list, currently " + HeartbeatProperties.SCHEMA_VERSION + "."),
    REGISTERED_ON(
            Names.REGISTERED_ON,
            Envelope.PROPERTY,
            "The UTC date the installation ID was created. It is not the original installation date."),
    APP_VERSION(
            Names.APP_VERSION,
            Envelope.PROPERTY,
            "The installed Symphony for Trello release from the installer's metadata, or null when unknown."),
    OS_FAMILY(Names.OS_FAMILY, Envelope.PROPERTY, "One of `windows`, `macos`, `linux`, `other`, or `unknown`."),
    OS_RELEASE(
            Names.OS_RELEASE,
            Envelope.PROPERTY,
            "A coarse product release: Windows `10`, `11`, or `server_YYYY`; the macOS major release; a"
                    + " support-relevant Linux release such as Ubuntu `24.04` or Debian `13`; `rolling` for rolling"
                    + " distributions; otherwise `unknown`."),
    LINUX_DISTRIBUTION(
            Names.LINUX_DISTRIBUTION,
            Envelope.PROPERTY,
            "A normalized distribution ID from a fixed list, `other`, or `unknown` on Linux; null elsewhere."),
    RUNTIME_ARCH(
            Names.RUNTIME_ARCH,
            Envelope.PROPERTY,
            "The Java runtime architecture: `x64`, `arm64`, `x86`, `other`, or `unknown`."),
    CONNECTED_BOARD_COUNT(
            Names.CONNECTED_BOARD_COUNT,
            Envelope.PROPERTY,
            "How many distinct Trello boards are connected in the local manifest, or null when the manifest"
                    + " cannot be read."),
    BOARD_IMPORTS_TOTAL(
            Names.BOARD_IMPORTS_TOTAL,
            Envelope.PROPERTY,
            "How many successful board imports this installation completed while reporting was enabled."),
    BOARD_CREATIONS_TOTAL(
            Names.BOARD_CREATIONS_TOTAL,
            Envelope.PROPERTY,
            "How many successful board creations this installation completed while reporting was enabled."),
    GEOIP_DISABLE(
            Names.GEOIP_DISABLE,
            Envelope.PROPERTY,
            "Always true. It asks PostHog not to add location data derived from the connection."),
    PROCESS_PERSON_PROFILE(
            Names.PROCESS_PERSON_PROFILE,
            Envelope.PROPERTY,
            "Always true. It keeps a minimal PostHog profile per installation ID so the maintainer can find and"
                    + " erase an installation's reports.");

    private final String jsonName;
    private final Envelope envelope;
    private final String meaning;

    HeartbeatField(String jsonName, Envelope envelope, String meaning) {
        this.jsonName = jsonName;
        this.envelope = envelope;
        this.meaning = meaning;
    }

    public String jsonName() {
        return jsonName;
    }

    public String meaning() {
        return meaning;
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
