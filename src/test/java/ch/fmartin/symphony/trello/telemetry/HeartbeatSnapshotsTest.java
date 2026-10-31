package ch.fmartin.symphony.trello.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class HeartbeatSnapshotsTest {
    @Test
    void observationKeepsTheOverrideNamesSeenAtCaptureTime() {
        // given
        List<String> names = new ArrayList<>(List.of("--prefix"));
        var observation = new HeartbeatSnapshots.Observation(TelemetryFixture.LINUX_PLATFORM, "1.2.0", names, 3);

        // when
        names.add("--bin-dir");

        // then
        assertThat(observation.installOverrides()).containsExactly("--prefix");
        assertThatExceptionOfType(UnsupportedOperationException.class)
                .isThrownBy(() -> observation.installOverrides().add("--bin-dir"));
    }
}
