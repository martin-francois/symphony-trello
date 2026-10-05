package ch.fmartin.symphony.trello.telemetry;

import ch.fmartin.symphony.trello.telemetry.HeartbeatField.Names;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/// The allowlisted `properties` object of one heartbeat. Only these fields exist; nothing else is
/// ever serialized. `null` means unknown and is distinct from `0`.
///
/// A stored pending report carries both protocol flags. Reading skips them because the sent body
/// always sets them, whatever the file says.
@JsonIgnoreProperties(
        value = {Names.GEOIP_DISABLE, Names.PROCESS_PERSON_PROFILE},
        allowGetters = true)
@JsonPropertyOrder({
    Names.TELEMETRY_SCHEMA_VERSION,
    Names.REGISTERED_ON,
    Names.APP_VERSION,
    Names.OS_FAMILY,
    Names.OS_RELEASE,
    Names.LINUX_DISTRIBUTION,
    Names.RUNTIME_ARCH,
    Names.CONNECTED_BOARD_COUNT,
    Names.BOARD_IMPORTS_TOTAL,
    Names.BOARD_CREATIONS_TOTAL,
    Names.GEOIP_DISABLE,
    Names.PROCESS_PERSON_PROFILE
})
public record HeartbeatProperties(
        @JsonProperty(Names.TELEMETRY_SCHEMA_VERSION) int telemetrySchemaVersion,
        @JsonProperty(Names.REGISTERED_ON) @Nullable String registeredOn,
        @JsonProperty(Names.APP_VERSION) @Nullable String appVersion,
        @JsonProperty(Names.OS_FAMILY) String osFamily,
        @JsonProperty(Names.OS_RELEASE) String osRelease,
        @JsonProperty(Names.LINUX_DISTRIBUTION) @Nullable String linuxDistribution,
        @JsonProperty(Names.RUNTIME_ARCH) String runtimeArch,
        @JsonProperty(Names.CONNECTED_BOARD_COUNT) @Nullable Integer connectedBoardCount,
        @JsonProperty(Names.BOARD_IMPORTS_TOTAL) long boardImportsTotal,
        @JsonProperty(Names.BOARD_CREATIONS_TOTAL) long boardCreationsTotal) {

    public static final int SCHEMA_VERSION = 1;
    private static final Set<String> RUNTIME_ARCHITECTURES = WireVocabulary.wireNames(RuntimeArch.class);

    /// Always true so PostHog adds no location data derived from the connection.
    @JsonProperty(Names.GEOIP_DISABLE)
    public boolean geoipDisable() {
        return true;
    }

    /// Always true so PostHog keeps a minimal profile through which the maintainer can erase reports.
    @JsonProperty(Names.PROCESS_PERSON_PROFILE)
    public boolean processPersonProfile() {
        return true;
    }

    /// A stored snapshot is input, not fresh normalizer output; before it is sent again it must
    /// still match the contract. Unknown values stay allowed, free text does not.
    public Optional<String> invariantProblem() {
        if (telemetrySchemaVersion != SCHEMA_VERSION) {
            return Optional.of("pending report has schema version " + telemetrySchemaVersion);
        }
        Optional<OsFamily> family = OsFamily.fromWireName(osFamily);
        if (family.isEmpty() || runtimeArch == null || !RUNTIME_ARCHITECTURES.contains(runtimeArch)) {
            return Optional.of("pending report has an unknown platform value");
        }
        if (osRelease == null || !PlatformNormalizer.isNormalized(family.get(), osRelease, linuxDistribution)) {
            return Optional.of("pending report has a platform release the normalizer never produces");
        }
        if (appVersion != null && InstalledVersion.normalize(appVersion).isEmpty()) {
            return Optional.of("pending report has an unusable app version");
        }
        if (connectedBoardCount != null && connectedBoardCount < 0
                || boardImportsTotal < 0
                || boardCreationsTotal < 0) {
            return Optional.of("pending report has a negative count");
        }
        if (registeredOn != null) {
            try {
                LocalDate.parse(registeredOn);
            } catch (DateTimeParseException exception) {
                return Optional.of("pending report has a malformed registration date");
            }
        }
        return Optional.empty();
    }
}
