package ch.fmartin.symphony.trello.telemetry;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicReference;

/// Shared telemetry test scaffolding: an installed state directory, a mutable UTC clock, a fixed
/// platform, and a loopback distribution that can never reach production.
final class TelemetryFixture {
    static final Instant NOON = Instant.parse("2026-09-22T12:00:00Z");
    static final String INSTALLED_VERSION = "1.2.0";
    static final String TEST_TOKEN = "phc_" + "testtoken0".repeat(4) + "0123";
    static final URI LOOPBACK_ENDPOINT = URI.create("http://127.0.0.1:9/i/v0/e/");
    static final Platform LINUX_PLATFORM = new Platform("linux", "24.04", "ubuntu", "x64");

    private TelemetryFixture() {}

    static Path installedStateDir(Path tempDir) throws IOException {
        return installedStateDir(tempDir, INSTALLED_VERSION);
    }

    static Path installedStateDir(Path tempDir, String appVersion) throws IOException {
        Path stateDir = Files.createDirectories(tempDir.resolve("state"));
        Files.writeString(
                InstalledVersion.installContextPath(stateDir),
                "installer=install.sh\ninstall_format_version=2\napp_version=" + appVersion + "\n");
        return stateDir;
    }

    static TelemetryInstallation installation(Path stateDir) {
        return installation(stateDir, TelemetryEnvironment.none(), distribution(LOOPBACK_ENDPOINT));
    }

    static TelemetryInstallation installation(Path stateDir, TelemetryEnvironment environment) {
        return installation(stateDir, environment, distribution(LOOPBACK_ENDPOINT));
    }

    static TelemetryInstallation installation(
            Path stateDir, TelemetryEnvironment environment, TelemetryDistribution distribution) {
        return TelemetryInstallation.of(stateDir, () -> OptionalInt.of(3), environment, distribution);
    }

    static TelemetryDistribution distribution(URI endpoint) {
        return new TelemetryDistribution(endpoint, Optional.of(TEST_TOKEN));
    }

    static TelemetryDistribution unconfiguredDistribution() {
        return new TelemetryDistribution(LOOPBACK_ENDPOINT, Optional.empty());
    }

    static PlatformDetector fixedPlatform() {
        return new PlatformDetector(
                () -> new PlatformFacts("Linux", "amd64", null, Map.of("ID", "ubuntu", "VERSION_ID", "24.04")));
    }

    static HeartbeatSnapshots snapshots(TelemetryInstallation installation) {
        return new HeartbeatSnapshots(installation, fixedPlatform());
    }

    /// A clock tests move forward explicitly; no test waits for real time.
    static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now;

        MutableClock(Instant start) {
            this.now = new AtomicReference<>(start);
        }

        void advance(Duration duration) {
            now.updateAndGet(current -> current.plus(duration));
        }

        void set(Instant instant) {
            now.set(instant);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }
}
