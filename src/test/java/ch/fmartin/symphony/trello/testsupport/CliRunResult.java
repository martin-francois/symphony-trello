package ch.fmartin.symphony.trello.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Objects;

public record CliRunResult(int exitCode, String stdout, String stderr) {
    public CliRunResult assertSuccess() {
        assertThat(exitCode).as("stdout:%n%s%nstderr:%n%s", stdout, stderr).isZero();
        assertThat(stderr).isEmpty();
        return this;
    }

    public CliRunResult assertFailure(int expectedExit) {
        assertThat(exitCode).as("stdout:%n%s%nstderr:%n%s", stdout, stderr).isEqualTo(expectedExit);
        return this;
    }

    public CliRunResult stdoutContains(String... expected) {
        assertThat(stdout).contains(expected);
        return this;
    }

    public CliRunResult stdoutDoesNotContain(String... forbidden) {
        assertThat(stdout).doesNotContain(forbidden);
        return this;
    }

    public CliRunResult stderrContains(String... expected) {
        assertThat(stderr).contains(expected);
        return this;
    }

    public CliRunResult stderrDoesNotContain(String... forbidden) {
        assertThat(stderr).doesNotContain(forbidden);
        return this;
    }

    public CliRunResult stderrEmpty() {
        assertThat(stderr).isEmpty();
        return this;
    }

    public List<String> stdoutLines() {
        return stdout.lines().toList();
    }

    public CliRunResult stdoutContainsSubsequence(String... expectedLinesOrFragments) {
        if (expectedLinesOrFragments.length > 0) {
            assertThat(Objects.requireNonNull(stdout)).containsSubsequence(expectedLinesOrFragments);
        }
        return this;
    }
}
