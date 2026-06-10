package ch.fmartin.symphony.trello.setup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class LocalSetupRequestTest {
    @Test
    void rejectsOutOfBoundsMaxAgentsAtConstruction() {
        // given
        int unboundedMaxAgents = 999999;

        // when
        Throwable thrown = catchThrowable(() -> request(unboundedMaxAgents));

        // then
        assertThat(thrown).isInstanceOfSatisfying(TrelloBoardSetupException.class, failure -> {
            assertThat(failure.code()).isEqualTo("setup_invalid_max_agents");
            assertThat(failure.getMessage())
                    .contains("--max-agents must be between 1 and " + TrelloBoardSetup.MAX_SETUP_CONCURRENT_AGENTS);
        });
    }

    @Test
    void rejectsZeroMaxAgentsAtConstruction() {
        // given
        int zeroMaxAgents = 0;

        // when
        Throwable thrown = catchThrowable(() -> request(zeroMaxAgents));

        // then
        assertThat(thrown).isInstanceOfSatisfying(TrelloBoardSetupException.class, failure -> assertThat(failure.code())
                .isEqualTo("setup_invalid_max_agents"));
    }

    private static LocalSetupRequest request(int maxAgents) {
        return new LocalSetupRequest(
                LocalSetupRequest.Action.SETUP,
                false,
                true,
                false,
                false,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                List.of(),
                List.of(),
                null,
                true,
                null,
                Optional.empty(),
                Optional.empty(),
                Optional.of(Path.of("config")),
                Optional.empty(),
                Optional.empty(),
                maxAgents,
                true,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                List.of(),
                false,
                false,
                true,
                URI.create("http://127.0.0.1:1/"));
    }
}
