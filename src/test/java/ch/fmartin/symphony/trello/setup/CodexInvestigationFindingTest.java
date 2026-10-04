package ch.fmartin.symphony.trello.setup;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class CodexInvestigationFindingTest {
    @ParameterizedTest(name = "blank command reports passed={0}")
    @ValueSource(booleans = {false, true})
    void blankValidationCommandsNeverVerifyAFix(boolean reportedPassed) throws IOException {
        // given
        var answer = new ObjectMapper()
                .readTree(
                        """
                {
                  "classification":"symphony_bug",
                  "fix_applied":true,
                  "changed_files":["WORKFLOW.md"],
                  "validation":[
                    {"command":"verify","passed":true},
                    {"command":"  ","passed":%s}
                  ]
                }
                """
                                .formatted(reportedPassed));

        // when
        var finding = CodexInvestigationFinding.parse(answer);

        // then
        assertThat(finding).hasValueSatisfying(value -> assertThat(value.verifiedFix())
                .as("every reported validation must have a command and pass before a fix is verified")
                .isFalse());
    }
}
