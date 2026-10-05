package ch.fmartin.symphony.trello.testsupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.approvaltests.SafetyCheckBeforeVerify;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

final class TerminalSnapshotsTest {
    private static final String SCENARIO = "suite/scenario";
    private static final char BELL = '\u0007';
    private static final char ESCAPE = TerminalTranscript.ESCAPE;
    private static final String BASELINE_STDOUT =
            """
            Checking prerequisites...
              OK      Git
              OK      Java 25+ JDK
            Done.
            """;

    @TempDir
    Path tempDir;

    @MethodSource("unapprovedChanges")
    @ParameterizedTest(name = "{0}")
    void unapprovedChangeFailsWithLineDiffAndLeavesBaselineUntouched(
            String change, String changedStdout, String expectedDiff) throws IOException {
        // given
        Path baseline = writeBaseline(TerminalTranscript.ofStreams(0, BASELINE_STDOUT, ""));
        TerminalSnapshots snapshots = verifyingSnapshots();

        // when
        Throwable failure = catchThrowable(
                () -> snapshots.verifyStreams(SCENARIO, 0, changedStdout, "", TranscriptNormalizer.none()));

        // then
        assertThat(failure)
                .as(change)
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("Snapshot suite/scenario differs from %s", baseline)
                .hasMessageContaining(expectedDiff)
                .hasMessageContaining(TerminalSnapshots.UPDATE_COMMAND);
        assertThat(baseline)
                .content(StandardCharsets.UTF_8)
                .isEqualTo(TerminalTranscript.ofStreams(0, BASELINE_STDOUT, ""));
        assertThat(candidate())
                .content(StandardCharsets.UTF_8)
                .isEqualTo(TerminalTranscript.ofStreams(0, changedStdout, ""));
    }

    static Stream<Arguments> unapprovedChanges() {
        return Stream.of(
                Arguments.of(
                        "unexpected line",
                        BASELINE_STDOUT.replace("Done.", "  WARN    Unexpected line\nDone."),
                        """
                        Extra content at line 6:
                          ["  WARN    Unexpected line"]"""),
                Arguments.of(
                        "ordering change",
                        BASELINE_STDOUT.replace(
                                "  OK      Git\n  OK      Java 25+ JDK\n", "  OK      Java 25+ JDK\n  OK      Git\n"),
                        """
                        Missing content at line 4:
                          ["  OK      Git"]

                        Extra content at line 6:
                          ["  OK      Git"]"""),
                Arguments.of(
                        "meaningful whitespace change",
                        BASELINE_STDOUT.replace("  OK      Git", "  OK     Git"),
                        """
                        Changed content at line 4:
                        expecting:
                          ["  OK      Git"]
                        but was:
                          ["  OK     Git"]"""));
    }

    @Test
    void streamPlacementChangeFails() throws IOException {
        // given
        writeBaseline(TerminalTranscript.ofStreams(0, BASELINE_STDOUT, ""));
        TerminalSnapshots snapshots = verifyingSnapshots();

        // when
        Throwable failure = catchThrowable(
                () -> snapshots.verifyStreams(SCENARIO, 0, "", BASELINE_STDOUT, TranscriptNormalizer.none()));

        // then
        assertThat(failure).isInstanceOf(AssertionError.class).hasMessageContaining("--- stderr ---");
    }

    @Test
    void missingBaselineFailsWithoutCreatingIt() {
        // given
        TerminalSnapshots snapshots = verifyingSnapshots();

        // when
        Throwable failure = catchThrowable(
                () -> snapshots.verifyStreams(SCENARIO, 0, BASELINE_STDOUT, "", TranscriptNormalizer.none()));

        // then
        assertThat(failure)
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("Snapshot suite/scenario has no committed baseline at %s", baselinePath());
        assertThat(baselinePath()).doesNotExist();
        assertThat(candidate()).exists();
    }

    @Test
    void matchingTranscriptPassesAndRemovesTheCandidate() throws IOException {
        // given
        writeBaseline(TerminalTranscript.ofStreams(0, BASELINE_STDOUT, ""));

        // when
        verifyingSnapshots().verifyStreams(SCENARIO, 0, BASELINE_STDOUT, "", TranscriptNormalizer.none());

        // then
        assertThat(candidate()).doesNotExist();
    }

    @Test
    void explicitUpdateModeWritesTheBaseline() {
        // given
        var snapshots = new TerminalSnapshots(baselineRoot(), candidateRoot(), true);

        // when
        snapshots.verifyStreams(SCENARIO, 3, "", "failed\n", TranscriptNormalizer.none());

        // then
        assertThat(baselinePath())
                .content(StandardCharsets.UTF_8)
                .isEqualTo("exitCode: 3\n--- stdout ---\n--- stderr ---\nfailed\n");
    }

    @Test
    void ciRefusesSnapshotUpdates() {
        // given
        String updateProperty = "true";

        // when
        Throwable failure = catchThrowable(() -> TerminalSnapshots.updateRequested(updateProperty, "true"));

        // then
        assertThat(failure).isInstanceOf(IllegalStateException.class).hasMessageContaining("must not be updated in CI");
    }

    @Test
    void updatesRequireTheExplicitProperty() {
        // given
        String unsetProperty = null;

        // when
        boolean localDefault = TerminalSnapshots.updateRequested(unsetProperty, null);
        boolean ciDefault = TerminalSnapshots.updateRequested(unsetProperty, "true");
        boolean explicitLocalUpdate = TerminalSnapshots.updateRequested("true", null);

        // then
        assertThat(localDefault)
                .as("local runs verify unless the update property is set")
                .isFalse();
        assertThat(ciDefault).as("CI runs verify").isFalse();
        assertThat(explicitLocalUpdate)
                .as("a developer can request an update locally")
                .isTrue();
    }

    @Test
    void credentialSentinelFailsBeforeComparison() {
        // given
        TerminalSnapshots snapshots = verifyingSnapshots();
        String leakingStderr = "token=" + CredentialSentinels.TRELLO_API_TOKEN + "\n";

        // when
        Throwable failure = catchThrowable(
                () -> snapshots.verifyStreams(SCENARIO, 0, "", leakingStderr, TranscriptNormalizer.none()));

        // then
        assertThat(failure)
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("must not print a credential sentinel on stderr");
        assertThat(candidate()).doesNotExist();
    }

    @Test
    void normalizationReplacesOnlyRegisteredValues() {
        // given
        Path root = tempDir.resolve("junit-random-name");
        TranscriptNormalizer normalizer = TranscriptNormalizer.builder()
                .temporaryRoot(root, "<TEMP>")
                .literal("127.0.0.1:41234", "127.0.0.1:<PORT>")
                .build();

        // when
        String normalized = normalizer.normalize("Wrote " + root + "/WORKFLOW.md on 127.0.0.1:41234 at 41234\n");

        // then
        assertThat(normalized).isEqualTo("Wrote <TEMP>/WORKFLOW.md on 127.0.0.1:<PORT> at 41234\n");
    }

    @Test
    void wrappedTemporaryPathFailsInsteadOfBeingPartlyNormalized() {
        // given
        Path root = tempDir.resolve("junit-random-name");
        TranscriptNormalizer normalizer =
                TranscriptNormalizer.builder().temporaryRoot(root, "<TEMP>").build();
        String wrappedPath = "Wrote " + root.getParent() + "/junit-random-\nname/WORKFLOW.md\n";

        // when
        Throwable failure = catchThrowable(
                () -> normalizer.assertNoUnnormalizedValues(SCENARIO, normalizer.normalize(wrappedPath)));

        // then
        assertThat(failure)
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("still contains part of a temporary path");
    }

    @Test
    void transcriptsShowControlCharactersAndMissingFinalNewlines() {
        // given
        String stdout = ESCAPE + "[1mbold" + ESCAPE + "[0m\r\nprogress 50%\rprogress 100%\n";
        String stderr = "bell" + BELL;

        // when
        String rendered = TerminalTranscript.ofStreams(1, stdout, stderr);
        String terminal = TerminalTranscript.ofTerminal(0, "prompt: answer\r\n");

        // then
        assertThat(rendered)
                .isEqualTo(
                        """
                        exitCode: 1
                        --- stdout ---
                        <ESC>[1mbold<ESC>[0m
                        progress 50%<CR>progress 100%
                        --- stderr ---
                        bell<U+0007><no newline at end of stream>
                        """);
        assertThat(terminal).isEqualTo("exitCode: 0\n--- terminal ---\nprompt: answer<CR>\n");
    }

    @Test
    void comparisonsRunOneAtATimeBecauseApprovalTestsTracksBaselinesUnsynchronized() throws Exception {
        // given
        var snapshots = new TerminalSnapshots(baselineRoot(), candidateRoot(), true);
        var firstEntered = new CountDownLatch(1);
        var releaseFirst = new CountDownLatch(1);
        var secondEntered = new CountDownLatch(1);
        // Closing waits for both comparisons; the first one stops waiting after the latch timeout.
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> first = executor.submit(() -> verifyHoldingComparison(snapshots, "concurrent/first", () -> {
                firstEntered.countDown();
                awaitLatch(releaseFirst);
            }));
            awaitLatch(firstEntered);

            // when
            Future<?> second = executor.submit(
                    () -> verifyHoldingComparison(snapshots, "concurrent/second", secondEntered::countDown));
            boolean secondOverlappedFirst = secondEntered.await(200, TimeUnit.MILLISECONDS);
            releaseFirst.countDown();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);

            // then
            assertThat(secondOverlappedFirst)
                    .as("a second comparison must wait while another thread is inside ApprovalTests")
                    .isFalse();
            assertThat(secondEntered.getCount())
                    .as("the waiting comparison runs once the first one finishes")
                    .isZero();
        }
    }

    /// Runs `insideComparison` on the calling thread from within ApprovalTests' verification, where
    /// its unsynchronized baseline tracker is updated.
    private static void verifyHoldingComparison(
            TerminalSnapshots snapshots, String scenario, Runnable insideComparison) {
        SafetyCheckBeforeVerify.add((approver, options) -> insideComparison.run());
        snapshots.verifyStreams(scenario, 0, "", "", TranscriptNormalizer.none());
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("timed out waiting for the other comparison");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private TerminalSnapshots verifyingSnapshots() {
        return new TerminalSnapshots(baselineRoot(), candidateRoot(), false);
    }

    private Path writeBaseline(String content) throws IOException {
        Path baseline = baselinePath();
        Files.createDirectories(baseline.getParent());
        Files.writeString(baseline, content);
        return baseline;
    }

    private Path baselineRoot() {
        return tempDir.resolve("baselines");
    }

    private Path candidateRoot() {
        return tempDir.resolve("candidates");
    }

    private Path baselinePath() {
        return baselineRoot().resolve(SCENARIO + ".approved.txt");
    }

    private Path candidate() {
        return candidateRoot().resolve(SCENARIO + ".received.txt");
    }
}
