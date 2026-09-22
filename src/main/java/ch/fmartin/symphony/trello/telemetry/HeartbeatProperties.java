package ch.fmartin.symphony.trello.telemetry;

import ch.fmartin.symphony.trello.telemetry.HeartbeatField.Names;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/// The allowlisted `properties` object of one heartbeat. Only these fields exist; nothing else is
/// ever serialized. `null` means unknown and is distinct from `0`.
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
        @JsonProperty(Names.BOARD_CREATIONS_TOTAL) long boardCreationsTotal,
        @JsonProperty(Names.GEOIP_DISABLE) boolean geoipDisable,
        @JsonProperty(Names.PROCESS_PERSON_PROFILE) boolean processPersonProfile) {

    public static final int SCHEMA_VERSION = 1;
    private static final Set<String> OS_FAMILIES =
            Set.of("windows", "macos", "linux", Platform.OTHER, Platform.UNKNOWN);
    private static final Set<String> RUNTIME_ARCHITECTURES =
            Set.of("x64", "arm64", "x86", Platform.OTHER, Platform.UNKNOWN);
    private static final Pattern RELEASE_TOKEN = Pattern.compile("^[a-z0-9_.-]{1,32}$");

    /// A stored snapshot is input, not fresh normalizer output; before it is sent again it must
    /// still match the contract. Unknown values stay allowed, free text and changed flags do not.
    public Optional<String> invariantProblem() {
        if (telemetrySchemaVersion != SCHEMA_VERSION) {
            return Optional.of("pending report has schema version " + telemetrySchemaVersion);
        }
        if (!geoipDisable || !processPersonProfile) {
            return Optional.of("pending report changed a fixed protocol flag");
        }
        if (osFamily == null
                || !OS_FAMILIES.contains(osFamily)
                || runtimeArch == null
                || !RUNTIME_ARCHITECTURES.contains(runtimeArch)) {
            return Optional.of("pending report has an unknown platform value");
        }
        if (osRelease == null
                || !RELEASE_TOKEN.matcher(osRelease).matches()
                || linuxDistribution != null
                        && !RELEASE_TOKEN.matcher(linuxDistribution).matches()) {
            return Optional.of("pending report has a free-text platform release");
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
