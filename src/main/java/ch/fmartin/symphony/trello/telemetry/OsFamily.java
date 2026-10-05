package ch.fmartin.symphony.trello.telemetry;

import java.util.Locale;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/// The coarse operating-system buckets a heartbeat may name.
enum OsFamily implements WireVocabulary {
    WINDOWS("windows"),
    MACOS("macos"),
    LINUX("linux"),
    OTHER(Platform.OTHER),
    UNKNOWN(Platform.UNKNOWN);

    private final String wireName;

    OsFamily(String wireName) {
        this.wireName = wireName;
    }

    @Override
    public String wireName() {
        return wireName;
    }

    static Optional<OsFamily> fromWireName(@Nullable String wireName) {
        for (OsFamily family : values()) {
            if (family.wireName.equals(wireName)) {
                return Optional.of(family);
            }
        }
        return Optional.empty();
    }

    static OsFamily fromOsName(@Nullable String osName) {
        if (osName == null || osName.isBlank()) {
            return UNKNOWN;
        }
        String name = osName.toLowerCase(Locale.ROOT);
        if (name.contains("windows")) {
            return WINDOWS;
        }
        if (name.contains("mac") || name.contains("darwin")) {
            return MACOS;
        }
        if (name.contains("linux")) {
            return LINUX;
        }
        return OTHER;
    }
}
