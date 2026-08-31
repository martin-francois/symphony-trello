package ch.fmartin.symphony.trello;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

final class ContinuousFuzzingWorkflowTest {
    private static final Path WORKFLOW = Path.of(".github/workflows/continuous-fuzzing.yml");
    private static final String CLUSTERFUZZLITE_COMMIT = "884713a6c30a92e5e8544c39945cd7cb630abcd1";
    private static final Pattern UNPINNED_ACTION = Pattern.compile("uses: [^\\s]+@(?![0-9a-f]{40}(?:\\s|$))");

    @Test
    void batchFuzzingRunsOnlyOnMainWithPersistentCorporaAndNativeReporting() throws IOException {
        // given
        String source = workflowSource();

        // when
        String batchJob = source.substring(source.indexOf("  batch:"), source.indexOf("  prune:"));

        // then
        assertThat(batchJob)
                .contains(
                        "github.ref == 'refs/heads/main'",
                        "runs-on: ubuntu-latest",
                        "timeout-minutes: 350",
                        "google/clusterfuzzlite/actions/build_fuzzers@" + CLUSTERFUZZLITE_COMMIT,
                        "google/clusterfuzzlite/actions/run_fuzzers@" + CLUSTERFUZZLITE_COMMIT,
                        "github.event.schedule == '17 0 * * *' && 18000 || 19800",
                        "language: jvm",
                        "minimize-crashes: true",
                        "mode: batch",
                        "output-sarif: true",
                        "github/codeql-action/upload-sarif@",
                        "sarif_file: cifuzz-sarif/results.sarif",
                        "scripts/report-clusterfuzzlite-failure",
                        "!cancelled()")
                .doesNotContain("blacksmith-", "parallel-fuzzing: true");
    }

    @Test
    void corpusPruningRunsDailyAndCanBeDispatchedManually() throws IOException {
        // given
        String source = workflowSource();

        // when
        String pruneJob = source.substring(source.indexOf("  prune:"));

        // then
        assertThat(source)
                .contains(
                        "cron: \"17 0 * * *\"",
                        "cron: \"17 6,12,18 * * *\"",
                        "workflow_dispatch:",
                        "operation:",
                        "fuzz_seconds:");
        assertThat(pruneJob)
                .contains(
                        "github.ref == 'refs/heads/main'",
                        "inputs.operation == 'prune'",
                        "github.event.schedule == '17 0 * * *'",
                        "always()",
                        "needs: batch",
                        "fuzz-seconds: 600",
                        "mode: prune")
                .doesNotContain("blacksmith-");
    }

    @Test
    void usefulClusterFuzzLiteModesRunWithNativeArtifactStorage() throws IOException {
        // given
        String source = workflowSource();

        // when
        String codeChangeJob =
                source.substring(source.indexOf("  code-change:"), source.indexOf("  continuous-build:"));
        String continuousBuildJob = source.substring(source.indexOf("  continuous-build:"), source.indexOf("  batch:"));
        String coverageJob = source.substring(source.indexOf("  coverage:"));

        // then
        assertThat(codeChangeJob)
                .contains(
                        "github.event_name == 'pull_request'",
                        "fuzz-seconds: 300",
                        "mode: code-change",
                        "minimize-crashes: true",
                        "output-sarif: true",
                        "storage-repo: https://github.com/martin-francois/symphony-trello-fuzzing-storage.git",
                        "github.event.pull_request.head.repo.full_name == github.repository")
                .doesNotContain("CLUSTERFUZZLITE_STORAGE_TOKEN", "parallel-fuzzing: true");
        assertThat(continuousBuildJob).contains("github.event_name == 'push'", "upload-build: true");
        assertThat(continuousBuildJob).doesNotContain("CLUSTERFUZZLITE_STORAGE_TOKEN", "storage-repo:");
        assertThat(coverageJob)
                .contains(
                        "github.event.schedule == '17 0 * * *'",
                        "fuzz-seconds: 600",
                        "mode: coverage",
                        "sanitizer: coverage");
        assertThat(source)
                .contains("storage-repo:", "secrets.CLUSTERFUZZLITE_STORAGE_TOKEN")
                .doesNotContain("blacksmith-", "PERSONAL_ACCESS_TOKEN");
    }

    @Test
    void longRunningOperationsHaveIndependentConcurrencyGroups() throws IOException {
        // given
        List<String> expectedGroups = List.of(
                "group: continuous-fuzzing-batch-main",
                "group: continuous-fuzzing-prune-main",
                "group: continuous-fuzzing-coverage-main",
                "group: continuous-fuzzing-build-main",
                "group: continuous-fuzzing-code-change-${{ github.event.pull_request.number }}");

        // when
        String source = workflowSource();

        // then
        assertThat(expectedGroups).allSatisfy(group -> assertThat(source).containsOnlyOnce(group));
    }

    @Test
    void workflowActionsArePinnedAndBuildIntegrationReusesOssFuzzPackaging() throws IOException {
        // given
        String workflow = workflowSource();
        String dockerfile = Files.readString(Path.of(".clusterfuzzlite/Dockerfile"));
        String buildScript = Files.readString(Path.of(".clusterfuzzlite/build.sh"));
        String project = Files.readString(Path.of(".clusterfuzzlite/project.yaml"));

        // when
        var unpinnedAction = UNPINNED_ACTION.matcher(workflow);

        // then
        assertThat(unpinnedAction.find()).as("workflow has an unpinned action").isFalse();
        assertThat(dockerfile)
                .contains(
                        "FROM gcr.io/oss-fuzz-base/base-builder-jvm@sha256:",
                        "COPY . /src/symphony-trello",
                        "COPY .clusterfuzzlite/build.sh /src/build.sh")
                .doesNotContain("git clone");
        assertThat(buildScript).contains("exec bash \"$SRC/symphony-trello/oss-fuzz/build.sh\"");
        assertThat(project).contains("language: jvm");
    }

    @Test
    void everyStandaloneFuzzerHasASeedCorpus() throws IOException {
        // given
        Path corpora = Path.of("oss-fuzz/corpora");
        Path fuzzers = Path.of("src/test/java/ch/fmartin/symphony/trello/fuzz");

        // when
        Set<String> fuzzerNames = new TreeSet<>();
        try (var sources = Files.newDirectoryStream(fuzzers, "*Fuzzer.java")) {
            for (Path source : sources) {
                fuzzerNames.add(source.getFileName().toString().replace(".java", ""));
            }
        }
        Map<String, Integer> seedCounts = new TreeMap<>();
        try (var directories = Files.newDirectoryStream(corpora)) {
            for (Path directory : directories) {
                if (!Files.isDirectory(directory)) {
                    continue;
                }
                int count = 0;
                try (var seeds = Files.newDirectoryStream(directory)) {
                    for (Path ignored : seeds) {
                        count++;
                    }
                }
                seedCounts.put(directory.getFileName().toString(), count);
            }
        }

        // then
        assertThat(seedCounts).hasSameSizeAs(fuzzerNames).allSatisfy((name, count) -> assertThat(count)
                .as("seed corpus %s contains an input", name)
                .isGreaterThan(0));
        assertThat(seedCounts.keySet())
                .as("every standalone fuzzer has exactly one corpus directory")
                .containsExactlyElementsOf(fuzzerNames);
    }

    private static String workflowSource() throws IOException {
        return Files.readString(WORKFLOW);
    }
}
