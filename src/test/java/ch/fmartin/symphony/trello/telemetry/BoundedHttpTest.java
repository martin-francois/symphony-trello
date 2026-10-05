package ch.fmartin.symphony.trello.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import ch.fmartin.symphony.trello.telemetry.BoundedHttp.BodyRead;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import org.junit.jupiter.api.Test;

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

    private static final class UncheckedFailureStream extends InputStream {
        @Override
        public int read() {
            throw new IllegalStateException("stream broke");
        }
    }
}
