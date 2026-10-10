package ch.fmartin.symphony.trello.tracker;

import static ch.fmartin.symphony.trello.testsupport.FakeTrelloServer.boardJson;
import static ch.fmartin.symphony.trello.testsupport.FakeTrelloServer.respond;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import ch.fmartin.symphony.trello.config.EffectiveConfig;
import ch.fmartin.symphony.trello.testsupport.FakeTrelloServer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// Every Trello `429` response, retried or not, must reach the poll scheduler with the
/// `Retry-After` deadline the client parsed.
final class TrelloClientRateLimitPressureTest {
    private static final Instant NOW = Instant.parse("2026-10-04T10:00:00Z");
    private static final String BOARD_ROUTE = "/1/boards/input";
    private static final Duration WRITE_RETRY_AFTER = Duration.ofSeconds(12);
    private static final Duration RETRY_AFTER_ABOVE_MAX_WAIT = RateLimitPressure.MAX_WAIT.multipliedBy(4);

    private final TrelloClient client = new TrelloClient(new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC));
    private FakeTrelloServer trello;

    @TempDir
    Path tempDir;

    @BeforeEach
    void startServer() throws Exception {
        trello = new FakeTrelloServer();
        trello.startEmpty();
    }

    @AfterEach
    void stopServer() {
        trello.stop();
    }

    @Test
    void readThatSucceedsAfterARetryStillReportsTheRateLimit() {
        // given
        var requests = new AtomicInteger();
        trello.on(BOARD_ROUTE, exchange -> {
            if (requests.incrementAndGet() == 1) {
                rateLimited(exchange, Duration.ZERO);
                return;
            }
            respond(exchange, boardJson("board-1", "Board", false));
        });

        // when
        String boardId = client.resolveBoardId(config(1));

        // then
        assertThat(boardId).isEqualTo("board-1");
        assertThat(client.drainRateLimitPressure())
                .isEqualTo(new RateLimitPressure(1, Optional.of(NOW), Optional.of(NOW)));
        assertThat(client.drainRateLimitPressure()).isEqualTo(RateLimitPressure.NONE);
    }

    @Test
    void rateLimitedWriteReportsItsRetryAfterDeadline() {
        // given
        trello.on("/1/cards/write-card/attachments", exchange -> rateLimited(exchange, WRITE_RETRY_AFTER));

        // when
        TrelloException failure = catchThrowableOfType(
                () -> client.addUrlAttachment(config(0), "write-card", "https://example.invalid/pr/1", "PR"),
                TrelloException.class);

        // then
        assertThat(failure.code()).isEqualTo("trello_api_rate_limited");
        assertThat(client.drainRateLimitPressure())
                .isEqualTo(new RateLimitPressure(1, Optional.of(NOW), Optional.of(NOW.plus(WRITE_RETRY_AFTER))));
    }

    @Test
    void retryAfterLongerThanTheMaximumWaitIsBounded() {
        // given
        trello.on(BOARD_ROUTE, exchange -> rateLimited(exchange, RETRY_AFTER_ABOVE_MAX_WAIT));

        // when
        TrelloException failure = catchThrowableOfType(() -> client.resolveBoardId(config(0)), TrelloException.class);

        // then
        assertThat(failure.code()).isEqualTo("trello_api_rate_limited");
        assertThat(client.drainRateLimitPressure().retryNotBefore()).hasValue(NOW.plus(RateLimitPressure.MAX_WAIT));
    }

    private static void rateLimited(HttpExchange exchange, Duration retryAfter) throws IOException {
        exchange.getResponseHeaders().add("Retry-After", String.valueOf(retryAfter.toSeconds()));
        respond(exchange, 429, "{}");
    }

    private EffectiveConfig config(int maxApiRetries) {
        return TrelloTestConfigs.trackerConfig(
                        tempDir.resolve("WORKFLOW.md"),
                        trello.endpoint(),
                        "input",
                        Map.of("active_states", List.of("Todo"), "max_api_retries", maxApiRetries))
                .withResolvedBoardId("board-1");
    }
}
