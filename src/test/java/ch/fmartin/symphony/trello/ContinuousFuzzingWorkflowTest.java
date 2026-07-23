package ch.fmartin.symphony.trello;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class ContinuousFuzzingWorkflowTest {
    @Test
    void continuousFuzzingRunsOnlyOnMainWithGithubHostedRunnerAndIssueReporting() throws IOException {
        // given
        Path workflow = Path.of(".github/workflows/continuous-fuzzing.yml");

        // when
        String source = Files.readString(workflow);

        // then
        assertThat(source)
                .contains(
                        "name: Continuous Fuzzing",
                        "schedule:",
                        "cron: \"17 */6 * * *\"",
                        "workflow_dispatch:",
                        "duration_minutes:",
                        "permissions:",
                        "contents: read",
                        "issues: write",
                        "concurrency:",
                        "cancel-in-progress: false",
                        "if: github.ref == 'refs/heads/main'",
                        "runs-on: ubuntu-latest",
                        "timeout-minutes: 350",
                        "DEFAULT_FUZZ_MINUTES: \"330\"",
                        "MAX_FUZZ_MINUTES: \"330\"",
                        "actions/checkout@de0fac2e4500dabe0009e67214ff5f5447ce83dd # v6",
                        "actions/setup-java@be666c2fcd27ec809703dec50e508c2fdc7f6654 # v5",
                        "actions/upload-artifact@ea165f8d65b6e75b540449e92b4886f43607fa02 # v4",
                        "GH_TOKEN: ${{ github.token }}",
                        "gh label create bug",
                        "gh label create fuzzed",
                        "gh issue create",
                        "--label bug --label fuzzed",
                        "gh issue comment",
                        "openssl dgst -sha3-256",
                        "Fuzz fingerprint:",
                        "continuous-fuzzing-failure-${{ github.run_id }}")
                .doesNotContain("blacksmith-");
    }

    @Test
    void continuousFuzzingRunsEveryPublicFuzzTargetWithActiveJazzerSettings() throws IOException {
        // given
        Path workflow = Path.of(".github/workflows/continuous-fuzzing.yml");

        // when
        String source = Files.readString(workflow);

        // then
        assertThat(source)
                .contains(
                        "RepositorySourceResolverFuzzTest#labelledRepositorySourceValueCannotBreakSelectionInvariants",
                        "RepositorySourceResolverFuzzTest#cardTextDeclarationScanCannotBreakSelectionInvariants",
                        "TrelloCardReferenceParserFuzzTest#trelloReferenceParsingKeepsLookupIdsAndUrlsStable",
                        "TrelloCardReferenceParserFuzzTest#checklistClassificationNeverEmitsPrerequisitesWithProblems",
                        "WorkflowLoaderFuzzTest#workflowLoaderHandlesArbitraryWorkflowBytes",
                        "JAZZER_FUZZ=1",
                        "-Pfuzzing",
                        "-Djacoco.skip=true",
                        "\"-Djazzer.max_duration=${per_target_minutes}m\"",
                        "-Djazzer.max_executions=0",
                        "\"-Dtest=$target\"",
                        "exit \"$status\"")
                .doesNotContain("-Djazzer.max_duration=6h");
    }
}
