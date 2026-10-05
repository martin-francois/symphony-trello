package ch.fmartin.symphony.trello.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.approvaltests.Approvals;
import org.approvaltests.core.ApprovalFailureReporter;
import org.approvaltests.core.Options;
import org.approvaltests.namer.ApprovalNamer;
import org.approvaltests.reporters.AutoApproveReporter;
import org.jspecify.annotations.Nullable;

/// Verifies terminal transcripts against reviewed baselines under `src/test/snapshots`.
///
/// ApprovalTests stores, compares, and approves the files; ADR 0092 records why.
/// Each scenario has its own baseline file, so reviewing or updating one command never rewrites
/// another command's baseline. A normal run only reads baselines. A missing or different baseline
/// fails with a line-level diff, and the candidate transcript is written under
/// `target/snapshot-candidates`. Baselines change only when a developer passes
/// `-Dsymphony.snapshots.update=true`, which CI refuses.
public final class TerminalSnapshots {
    public static final String UPDATE_PROPERTY = "symphony.snapshots.update";
    public static final String UPDATE_COMMAND = "./mvnw test -Dtest='*SnapshotTest' -D" + UPDATE_PROPERTY + "=true";
    private static final Path BASELINE_ROOT = Path.of("src", "test", "snapshots");
    private static final Path CANDIDATE_ROOT = Path.of("target", "snapshot-candidates");
    private static final Pattern SCENARIO_NAME =
            Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*(?:/[a-z0-9]+(?:-[a-z0-9]+)*)+");
    // ApprovalTests records every verified baseline path in a static, unsynchronized set to reject
    // duplicate verifications. Snapshot tests run concurrently, so each comparison holds this lock;
    // command execution and output capture stay parallel.
    private static final Object APPROVAL_TRACKER_LOCK = new Object();

    private final Path baselineRoot;
    private final Path candidateRoot;
    private final boolean updateBaselines;

    TerminalSnapshots(Path baselineRoot, Path candidateRoot, boolean updateBaselines) {
        this.baselineRoot = baselineRoot.toAbsolutePath().normalize();
        this.candidateRoot = candidateRoot.toAbsolutePath().normalize();
        this.updateBaselines = updateBaselines;
    }

    /// Returns the repository's committed baselines in the mode selected for this test run.
    public static TerminalSnapshots repository() {
        return new TerminalSnapshots(
                BASELINE_ROOT,
                CANDIDATE_ROOT,
                updateRequested(System.getProperty(UPDATE_PROPERTY), System.getenv("CI")));
    }

    static boolean updateRequested(@Nullable String updateProperty, @Nullable String continuousIntegration) {
        boolean requested = Boolean.parseBoolean(updateProperty);
        if (requested && continuousIntegration != null && !"false".equalsIgnoreCase(continuousIntegration)) {
            throw new IllegalStateException("Snapshot baselines must not be updated in CI. Remove -D" + UPDATE_PROPERTY
                    + "=true and review the candidate under " + CANDIDATE_ROOT + " instead.");
        }
        return requested;
    }

    /// Verifies a command whose stdout and stderr were captured separately.
    public void verifyStreams(
            String scenario, int exitCode, String stdout, String stderr, TranscriptNormalizer normalizer) {
        CredentialSentinels.assertAbsent(scenario, "stdout", stdout);
        CredentialSentinels.assertAbsent(scenario, "stderr", stderr);
        verifyRendered(scenario, TerminalTranscript.ofStreams(exitCode, stdout, stderr), normalizer);
    }

    public void verify(String scenario, CliRunResult result, TranscriptNormalizer normalizer) {
        verifyStreams(scenario, result.exitCode(), result.stdout(), result.stderr(), normalizer);
    }

    /// Verifies a pseudo-terminal session, whose transcript intentionally merges both streams.
    public void verifyTerminal(String scenario, int exitCode, String transcript, TranscriptNormalizer normalizer) {
        CredentialSentinels.assertAbsent(scenario, "the terminal", transcript);
        verifyRendered(scenario, TerminalTranscript.ofTerminal(exitCode, transcript), normalizer);
    }

    private void verifyRendered(String scenario, String rendered, TranscriptNormalizer normalizer) {
        if (!SCENARIO_NAME.matcher(scenario).matches()) {
            throw new IllegalArgumentException(
                    "Snapshot scenario names are lower-case kebab-case segments below a suite directory,"
                            + " such as cli/root-help: " + scenario);
        }
        String normalized = normalizer.normalize(rendered);
        normalizer.assertNoUnnormalizedValues(scenario, normalized);
        Options options = new Options()
                .withReporter(updateBaselines ? new AutoApproveReporter() : new VerificationReporter(scenario))
                .forFile()
                .withNamer(new ScenarioNamer(scenario, baselineRoot, candidateRoot));
        synchronized (APPROVAL_TRACKER_LOCK) {
            Approvals.verify(normalized, options);
        }
    }

    private record VerificationReporter(String scenario) implements ApprovalFailureReporter {
        @Override
        public boolean report(String received, String approved) {
            Path candidate = Path.of(received);
            Path baseline = Path.of(approved);
            String review = "Review %s and, if the change is intended, run: %s".formatted(candidate, UPDATE_COMMAND);
            if (Files.notExists(baseline)) {
                throw new AssertionError(
                        "Snapshot %s has no committed baseline at %s. %s".formatted(scenario, baseline, review));
            }
            String description = "Snapshot %s differs from %s. %s".formatted(scenario, baseline, review);
            assertThat(candidate)
                    .as(description)
                    .usingCharset(StandardCharsets.UTF_8)
                    .hasSameTextualContentAs(baseline, StandardCharsets.UTF_8);
            // The line comparison above ignores a missing final newline; the exact comparison does not.
            assertThat(read(candidate)).as(description).isEqualTo(read(baseline));
            return true;
        }

        private static String read(Path file) {
            try {
                return Files.readString(file);
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }
    }

    private record ScenarioNamer(String scenario, Path baselineRoot, Path candidateRoot) implements ApprovalNamer {
        @Override
        public File getApprovedFile(String extensionWithDot) {
            return baselineRoot
                    .resolve(scenario + ".approved" + extensionWithDot)
                    .toFile();
        }

        @Override
        public File getReceivedFile(String extensionWithDot) {
            return candidateRoot
                    .resolve(scenario + ".received" + extensionWithDot)
                    .toFile();
        }

        @Override
        public String getApprovalName() {
            return scenario;
        }

        @Override
        public String getSourceFilePath() {
            return baselineRoot + File.separator;
        }

        @Override
        public ApprovalNamer addAdditionalInformation(String info) {
            throw new UnsupportedOperationException("Snapshot scenarios carry their full name: " + scenario);
        }

        @Override
        public String getAdditionalInformation() {
            return "";
        }
    }
}
