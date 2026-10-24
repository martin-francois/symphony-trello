package ch.fmartin.symphony.trello.setup;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

final class WorkflowMigrationReporterTest {
    @CsvSource({"1, 1 confirmed workflow body.", "2, 2 confirmed workflow bodies."})
    @ParameterizedTest(name = "{0} failed write(s)")
    void namesTheNumberOfFailedWritesWithTheMatchingNoun(int failedWrites, String expectedCount) {
        // given
        int count = failedWrites;

        // when
        String message = WorkflowMigrationReporter.failedWritesMessage(count);

        // then
        assertThat(message).startsWith("Could not migrate " + expectedCount);
    }
}
