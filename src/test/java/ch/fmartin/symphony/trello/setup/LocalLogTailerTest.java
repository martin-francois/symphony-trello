package ch.fmartin.symphony.trello.setup;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class LocalLogTailerTest {
    @TempDir
    Path tempDir;

    @Test
    void printRecentPrintsOnlyRequestedTailLines() throws Exception {
        // given
        Path logFile = tempDir.resolve("worker.log");
        Files.writeString(
                logFile,
                String.join(
                        "\n",
                        IntStream.rangeClosed(1, 150)
                                .mapToObj(line -> "line-" + line)
                                .toList()),
                StandardCharsets.UTF_8);
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();

        // when
        new LocalLogTailer().printRecent(List.of(logFile), 5, new PrintStream(stdout, true, StandardCharsets.UTF_8));

        // then
        assertThat(stdout.toString(StandardCharsets.UTF_8))
                .contains("line-146", "line-147", "line-148", "line-149", "line-150")
                .doesNotContain("line-145");
    }
}
