package ch.fmartin.symphony.trello.setup;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

final class WorkflowMigrationReporterTest {
    @CsvSource({
        "0, 1, Could not migrate 1 confirmed workflow body.",
        "0, 2, Could not migrate 2 confirmed workflow bodies.",
        "1, 0, Could not update the settings in 1 workflow.",
        "2, 0, Could not update the settings in 2 workflows.",
        "1, 2, Could not update the settings in 1 workflow and migrate 2 confirmed workflow bodies."
    })
    @ParameterizedTest(name = "{0} failed settings update(s), {1} failed body replacement(s)")
    void namesEachKindOfFailedWriteWithTheMatchingNoun(
            int failedSettingsUpdates, int failedBodyReplacements, String expectedStart) {
        // given
        int settings = failedSettingsUpdates;
        int bodies = failedBodyReplacements;

        // when
        String message = WorkflowMigrationReporter.failedWritesMessage(settings, bodies);

        // then
        assertThat(message)
                .startsWith(expectedStart)
                .endsWith(" The output above says where each backup is and whether the original is in place.");
    }
}
