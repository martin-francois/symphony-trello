package ch.fmartin.symphony.trello.setup;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

final class PosixManagedProcessPlatformTest {
    @Test
    void launchCommandUsesNohupForTerminalIndependentWorker() {
        // given
        PosixManagedProcessPlatform platform = new PosixManagedProcessPlatform();

        // when
        List<String> command = platform.launchCommand(List.of("java", "-jar", "app.jar"));

        // then
        assertThat(command).hasSize(4).first().asString().endsWith("nohup");
        assertThat(command).containsSubsequence("java", "-jar", "app.jar");
    }
}
