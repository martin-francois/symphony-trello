package ch.fmartin.symphony.trello.setup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class CardHelperClientTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @TempDir
    Path temporary;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void receivesCardOutputWithoutReadingConfigOrRequiringHostPidVisibility(boolean hiddenHostPid) throws Exception {
        // given
        Path config = Files.createDirectory(temporary.resolve("trusted-config"));
        var files = new CardHelperFiles(temporary.resolve("state").resolve("helper"));
        var client = new CardHelperClient(Map.of(), TIMEOUT);
        try (var server = new CardHelperServer(config, files, (request, out) -> {
                    assertThat(request.configDir()).hasValue(config);
                    out.print("card result\n");
                    return 0;
                });
                var executor = Executors.newSingleThreadExecutor();
                var output = new ByteArrayOutputStream();
                var out = new PrintStream(output, false, StandardCharsets.UTF_8)) {
            if (hiddenHostPid) {
                var json = CardHelperProtocol.json();
                var ready = json.readValue(Files.readAllBytes(files.ready()), CardHelperProtocol.Ready.class);
                Files.write(
                        files.ready(),
                        json.writeValueAsBytes(
                                new CardHelperProtocol.Ready(ready.version(), ready.generation(), Long.MAX_VALUE)));
            }
            var serving = executor.submit(() -> {
                server.serve();
                return null;
            });

            // when
            int result;
            try (server) {
                result = client.run(request(), files, out);
            }
            serving.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);

            // then
            assertThat(result).isZero();
            assertThat(output.toString(StandardCharsets.UTF_8)).isEqualTo("card result\n");
        }
    }

    @Test
    void unavailableHelperDoesNotFallBackToDirectCredentialUse() throws Exception {
        // given
        var files = new CardHelperFiles(temporary.resolve("missing-helper"));
        var client = new CardHelperClient(Map.of(), TIMEOUT);
        try (var output = new ByteArrayOutputStream();
                var out = new PrintStream(output, false, StandardCharsets.UTF_8)) {

            // when
            Throwable thrown = catchThrowable(() -> client.run(request(), files, out));

            // then
            assertThat(thrown).isInstanceOf(TrelloBoardSetupException.class).hasMessageContaining("trusted terminal");
            assertThat(files.root()).doesNotExist();
            assertThat(output.toString(StandardCharsets.UTF_8)).isEmpty();
        }
    }

    private static CardCommandRequest request() {
        return new CardCommandRequest(
                Optional.of("board-1"),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                true,
                new CardCommandRequest.ListCards(Optional.empty()));
    }
}
