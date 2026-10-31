package ch.fmartin.symphony.trello.telemetry;

import java.util.Locale;
import org.jspecify.annotations.Nullable;

/// The coarse Java runtime architectures a heartbeat may name.
enum RuntimeArch implements WireVocabulary {
    X64("x64"),
    ARM64("arm64"),
    X86("x86"),
    OTHER(Platform.OTHER),
    UNKNOWN(Platform.UNKNOWN);

    private final String wireName;

    RuntimeArch(String wireName) {
        this.wireName = wireName;
    }

    @Override
    public String wireName() {
        return wireName;
    }

    static RuntimeArch fromOsArch(@Nullable String osArch) {
        if (osArch == null || osArch.isBlank()) {
            return UNKNOWN;
        }
        return switch (osArch.toLowerCase(Locale.ROOT)) {
            case "amd64", "x86_64", "x64" -> X64;
            case "aarch64", "arm64" -> ARM64;
            case "x86", "i386", "i486", "i586", "i686" -> X86;
            default -> OTHER;
        };
    }
}
