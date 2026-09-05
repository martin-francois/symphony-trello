package ch.fmartin.symphony.trello.setup;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class RepositoryDefaultSummaryTest {
    @Test
    void reportsPreservedPathDefaultWithoutCallingWorkflowRepositoryGeneral() {
        // given
        var output = new ByteArrayOutputStream();
        TrelloBoardSetup.RepositoryDefaults defaults =
                TrelloBoardSetup.RepositoryDefaults.preserved(null, "$SYNTHETIC_REPOSITORY_PATH");

        // when
        RepositoryDefaultSummary.printDirect(
                new PrintStream(output, true, StandardCharsets.UTF_8), defaults, Path.of("WORKFLOW.md"));

        // then
        assertThat(output.toString(StandardCharsets.UTF_8))
                .contains("Repository clone URL not set; repository.default_path remains configured.")
                .doesNotContain("repository-general");
    }
}
