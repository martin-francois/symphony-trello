package ch.fmartin.symphony.trello;

import static java.nio.file.Path.of;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
    private static final Path WORKFLOW = of(".github/workflows/continuous-fuzzing.yml");
    private static final String CLUSTERFUZZLITE_COMMIT = "884713a6c30a92e5e8544c39945cd7cb630abcd1";
    private static final Pattern UNPINNED_ACTION = Pattern.compile("uses: [^\\s]+@(?![0-9a-f]{40}(?:\\s|$))");
    private static final Pattern PINNED_TEMURIN_IMAGE =
            Pattern.compile("FROM eclipse-temurin:25\\.\\d+\\.\\d+_\\d+-jdk@sha256:[0-9a-f]{64} AS jdk");

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
                        "timeout-minutes: 100",
                        "fail-fast: false",
                        "max-parallel: 1",
                        "group: continuous-fuzzing-batch-main-${{ matrix.target }}",
                        "RepositorySourceFuzzer",
                        "TrelloCardReferenceParserFuzzer",
                        "TrelloChecklistClassifierFuzzer",
                        "WorkflowLoaderFuzzer",
                        "scripts/select-clusterfuzzlite-target \"${{ matrix.target }}\"",
                        "google/clusterfuzzlite/actions/build_fuzzers@" + CLUSTERFUZZLITE_COMMIT,
                        "google/clusterfuzzlite/actions/run_fuzzers@" + CLUSTERFUZZLITE_COMMIT,
                        "github.event.schedule == '17 0 * * *' && 4500 || 4950",
                        "language: jvm",
                        "minimize-crashes: true",
                        "mode: batch",
                        "output-sarif: true",
                        "storage-repo: https://${{ secrets.CLUSTERFUZZLITE_STORAGE_TOKEN }}@github.com/"
                                + "martin-francois/symphony-trello-fuzzing-storage.git",
                        "scripts/verify-clusterfuzzlite-storage corpus \"${{ matrix.target }}\"",
                        "github/codeql-action/upload-sarif@",
                        "category: clusterfuzzlite-${{ matrix.target }}",
                        "sarif_file: cifuzz-sarif/results.sarif",
                        "CFL_CRASH_ARTIFACT: crashes-${{ matrix.target }}",
                        "CFL_TARGET: ${{ matrix.target }}",
                        "scripts/report-clusterfuzzlite-failure",
                        "!cancelled()")
                .doesNotContain("blacksmith-", "parallel-fuzzing: true");
        String issueStep = batchJob.substring(batchJob.indexOf("      - name: Create or update fuzz failure issue"));
        assertThat(issueStep.substring(0, issueStep.indexOf("      - name: Fail after publishing")))
                .doesNotContain("hashFiles('cifuzz-sarif/results.sarif')");
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
                        "mode: prune",
                        "scripts/verify-clusterfuzzlite-storage corpus")
                .doesNotContain("blacksmith-");
    }

    @Test
    void usefulClusterFuzzLiteModesUseDedicatedVerifiedStorage() throws IOException {
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
                        "storage-repo-branch: main",
                        "storage-repo-branch-coverage: gh-pages",
                        "github.event.pull_request.head.repo.full_name == github.repository")
                .doesNotContain("CLUSTERFUZZLITE_STORAGE_TOKEN", "parallel-fuzzing: true");
        assertThat(continuousBuildJob).contains("github.event_name == 'push'", "upload-build: true");
        assertThat(continuousBuildJob).doesNotContain("CLUSTERFUZZLITE_STORAGE_TOKEN", "storage-repo:");
        assertThat(coverageJob)
                .contains(
                        "github.event.schedule == '17 0 * * *'",
                        "fuzz-seconds: 600",
                        "mode: coverage",
                        "sanitizer: coverage",
                        "CLUSTERFUZZLITE_STORAGE_TOKEN",
                        "scripts/verify-clusterfuzzlite-storage coverage");
        assertThat(source)
                .contains(
                        "storage-repo-branch: main",
                        "storage-repo-branch-coverage: gh-pages",
                        "CLUSTERFUZZLITE_STORAGE_TOKEN")
                .doesNotContain("blacksmith-", "PERSONAL_ACCESS_TOKEN", "contents: write");
    }

    @Test
    void longRunningOperationsHaveIndependentConcurrencyGroups() throws IOException {
        // given
        List<String> expectedGroups = List.of(
                "group: continuous-fuzzing-batch-main-${{ matrix.target }}",
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
        String dockerfile = Files.readString(of(".clusterfuzzlite/Dockerfile"));
        String ossFuzzDockerfile = Files.readString(of("oss-fuzz/Dockerfile"));
        String buildScript = Files.readString(of(".clusterfuzzlite/build.sh"));
        String project = Files.readString(of(".clusterfuzzlite/project.yaml"));

        // when
        var unpinnedAction = UNPINNED_ACTION.matcher(workflow);

        // then
        assertThat(unpinnedAction.find()).as("workflow has an unpinned action").isFalse();
        assertThat(dockerfile)
                .contains(
                        "COPY --from=jdk /opt/java/openjdk/ \"$JAVA_HOME/\"",
                        "FROM gcr.io/oss-fuzz-base/base-builder-jvm@sha256:",
                        "COPY . /src/symphony-trello",
                        "COPY .clusterfuzzlite/build.sh /src/build.sh")
                .doesNotContain("git clone", "api.adoptium.net/v3/binary/latest");
        assertThat(dockerfile).containsPattern(PINNED_TEMURIN_IMAGE);
        assertThat(ossFuzzDockerfile)
                .containsPattern(PINNED_TEMURIN_IMAGE)
                .contains("COPY --from=jdk /opt/java/openjdk/ \"$JAVA_HOME/\"")
                .doesNotContain("api.adoptium.net/v3/binary/latest");
        assertThat(buildScript).contains("exec bash \"$SRC/symphony-trello/oss-fuzz/build.sh\"");
        assertThat(of("oss-fuzz/build.sh"))
                .content(StandardCharsets.UTF_8)
                .contains("TestRepositoryUris*.class", "ch/fmartin/symphony/trello/testsupport");
        assertThat(project).contains("language: jvm");
    }

    @Test
    void everyStandaloneFuzzerHasASeedCorpus() throws IOException {
        // given
        Path corpora = of("oss-fuzz/corpora");
        Path fuzzers = of("src/test/java/ch/fmartin/symphony/trello/fuzz");

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
                int count;
                try (var seeds = Files.list(directory)) {
                    count = Math.toIntExact(seeds.count());
                }
                seedCounts.put(directory.getFileName().toString(), count);
            }
        }

        // then
        assertThat(seedCounts).hasSameSizeAs(fuzzerNames).doesNotContainValue(0);
        assertThat(seedCounts.keySet())
                .as("every standalone fuzzer has exactly one corpus directory")
                .containsExactlyElementsOf(fuzzerNames);
    }

    private static String workflowSource() throws IOException {
        return Files.readString(WORKFLOW);
    }
}
