package ch.fmartin.symphony.trello.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.fmartin.symphony.trello.telemetry.BoundedHttp.BodyRead;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentMatchers;

final class BoundedHttpTest {
    // Long enough that waiting it out would show as a timeout instead of the read failure.
    private static final Duration DEADLINE = Duration.ofSeconds(30);

    @Test
    void anUncheckedFailureOnTheReaderThreadEndsTheReadBeforeTheDeadline() throws IOException {
        // given
        long deadline = System.nanoTime() + DEADLINE.toNanos();

        // when
        BodyRead read;
        try (InputStream failing = new UncheckedFailureStream()) {
            read = BoundedHttp.readBounded(failing, deadline);
        }

        // then
        assertThat(read).isEqualTo(new BodyRead.Failed("response body unreadable"));
        assertThat(System.nanoTime())
                .as("the read must end as soon as the reader thread fails, not at the deadline")
                .isLessThan(deadline);
    }

    @ParameterizedTest
    @ValueSource(ints = {200, 401, 429})
    void responseHeadersSurviveABodyFailureBeforeTheResponseFutureCompletes(int status) {
        // given
        // The JDK calls the body handler after headers, but can fail its response future
        // when EOF arrives before it publishes the InputStream response.
        try (HttpClient http = mock()) {
            HttpResponse.ResponseInfo info = mock();
            HttpHeaders headers = HttpHeaders.of(Map.of("Retry-After", List.of("17")), (name, value) -> true);
            when(info.statusCode()).thenReturn(status);
            when(info.headers()).thenReturn(headers);
            when(http.sendAsync(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<InputStream>>any()))
                    .thenAnswer(invocation -> failAfterHeaders(invocation.getArgument(1), info));

            // when
            BoundedHttp.Exchange result =
                    BoundedHttp.post(http, URI.create("http://localhost/capture"), DEADLINE, "{}");

            // then
            // Status and retry metadata remain authoritative even when no body is readable.
            assertThat(result)
                    .isEqualTo(new BoundedHttp.Exchange.Answered(
                            status, headers, new BodyRead.Failed("response body unreadable")));
        }
    }

    private static CompletableFuture<HttpResponse<InputStream>> failAfterHeaders(
            HttpResponse.BodyHandler<InputStream> handler, HttpResponse.ResponseInfo info) {
        var response = new CompletableFuture<HttpResponse<InputStream>>();
        Thread.ofPlatform().start(() -> deliverHeadersThenFail(handler, info, response));
        return response;
    }

    private static void deliverHeadersThenFail(
            HttpResponse.BodyHandler<InputStream> handler,
            HttpResponse.ResponseInfo info,
            CompletableFuture<HttpResponse<InputStream>> response) {
        handler.apply(info);
        response.completeExceptionally(new IOException("cannot read response body: expected 50 bytes, received 0"));
    }

    private static final class UncheckedFailureStream extends InputStream {
        @Override
        public int read() {
            throw new IllegalStateException("stream broke");
        }
    }
}
