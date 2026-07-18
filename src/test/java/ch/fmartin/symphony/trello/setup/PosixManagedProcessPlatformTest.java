package ch.fmartin.symphony.trello.setup;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

final class PosixManagedProcessPlatformTest {
    private static final Path USR_BIN_SETSID = Path.of("/usr/bin/setsid");

    @Test
    void launchCommandUsesNewSessionAndNohupForTerminalIndependentWorker() {
        // given
        var platform = new PosixManagedProcessPlatform();

        // when
        List<String> command = platform.launchCommand(List.of("java", "-jar", "app.jar"));

        // then
        if (Files.isExecutable(USR_BIN_SETSID)) {
            assertThat(command).hasSize(5);
            assertThat(command.getFirst()).endsWith("setsid");
            assertThat(command.get(1)).endsWith("nohup");
        } else {
            assertThat(command).hasSize(4).first().asString().endsWith("nohup");
        }
        assertThat(command).containsSubsequence("java", "-jar", "app.jar");
    }
}
