package ch.fmartin.symphony.trello.telemetry;

import ch.fmartin.symphony.trello.telemetry.HeartbeatField.Names;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;
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
    Names.INSTALL_OVERRIDES,
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
        @JsonProperty(Names.INSTALL_OVERRIDES) @Nullable List<String> installOverrides,
        @JsonProperty(Names.CONNECTED_BOARD_COUNT) @Nullable Integer connectedBoardCount,
        @JsonProperty(Names.BOARD_IMPORTS_TOTAL) long boardImportsTotal,
        @JsonProperty(Names.BOARD_CREATIONS_TOTAL) long boardCreationsTotal) {

    public static final int SCHEMA_VERSION = 1;
    private static final String UNKNOWN_PLATFORM_PROBLEM = "pending report has an unknown platform value";

    public HeartbeatProperties {
        installOverrides = installOverrides == null ? null : List.copyOf(installOverrides);
    }

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
        return schemaProblem()
                .or(this::platformProblem)
                .or(this::overridesProblem)
                .or(this::versionProblem)
                .or(this::countProblem)
                .or(this::registrationProblem);
    }

    private Optional<String> schemaProblem() {
        return telemetrySchemaVersion == SCHEMA_VERSION
                ? Optional.empty()
                : Optional.of("pending report has schema version " + telemetrySchemaVersion);
    }

    /// Both platform values must be vocabulary words, and the release must be one the normalizer
    /// produces for that family.
    private Optional<String> platformProblem() {
        if (WireVocabulary.fromWireName(RuntimeArch.class, runtimeArch).isEmpty()) {
            return Optional.of(UNKNOWN_PLATFORM_PROBLEM);
        }
        return WireVocabulary.fromWireName(OsFamily.class, osFamily)
                .map(this::releaseProblem)
                .orElseGet(() -> Optional.of(UNKNOWN_PLATFORM_PROBLEM));
    }

    private Optional<String> releaseProblem(OsFamily family) {
        boolean normalized = osRelease != null && PlatformNormalizer.isNormalized(family, osRelease, linuxDistribution);
        return normalized
                ? Optional.empty()
                : Optional.of("pending report has a platform release the normalizer never produces");
    }

    /// Every recorded override is a name from the fixed list, and none repeats.
    private Optional<String> overridesProblem() {
        if (installOverrides == null) {
            return Optional.empty();
        }
        boolean known = installOverrides.stream()
                .allMatch(name ->
                        WireVocabulary.fromWireName(InstallOverride.class, name).isPresent());
        boolean distinct = installOverrides.stream().distinct().count() == installOverrides.size();
        return known && distinct ? Optional.empty() : Optional.of("pending report has an unknown install override");
    }

    private Optional<String> versionProblem() {
        return appVersion != null && InstalledVersion.normalize(appVersion).isEmpty()
                ? Optional.of("pending report has an unusable app version")
                : Optional.empty();
    }

    private Optional<String> countProblem() {
        return connectedBoardCount != null && connectedBoardCount < 0
                        || boardImportsTotal < 0
                        || boardCreationsTotal < 0
                ? Optional.of("pending report has a negative count")
                : Optional.empty();
    }

    private Optional<String> registrationProblem() {
        if (registeredOn == null) {
            return Optional.empty();
        }
        try {
            LocalDate.parse(registeredOn);
            return Optional.empty();
        } catch (DateTimeParseException exception) {
            return Optional.of("pending report has a malformed registration date");
        }
    }
}
