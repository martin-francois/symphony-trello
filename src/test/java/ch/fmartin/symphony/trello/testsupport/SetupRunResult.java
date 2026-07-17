package ch.fmartin.symphony.trello.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Objects;

public record SetupRunResult(int exitCode, String stdout, String stderr) {
    public SetupRunResult assertSuccess() {
        assertThat(exitCode).as("stdout:%n%s%nstderr:%n%s", stdout, stderr).isZero();
        assertThat(stderr).isEmpty();
        return this;
    }

    public SetupRunResult assertFailure(int expectedExit) {
        assertThat(exitCode).as("stdout:%n%s%nstderr:%n%s", stdout, stderr).isEqualTo(expectedExit);
        return this;
    }

    public SetupRunResult stdoutContains(String... expected) {
        assertThat(stdout).contains(expected);
        return this;
    }

    public SetupRunResult stdoutDoesNotContain(String... forbidden) {
        assertThat(stdout).doesNotContain(forbidden);
        return this;
    }

    public SetupRunResult stderrContains(String... expected) {
        assertThat(stderr).contains(expected);
        return this;
    }

    public SetupRunResult stderrDoesNotContain(String... forbidden) {
        assertThat(stderr).doesNotContain(forbidden);
        return this;
    }

    public SetupRunResult stderrEmpty() {
        assertThat(stderr).isEmpty();
        return this;
    }

    public List<String> stdoutLines() {
        return stdout.lines().toList();
    }

    public SetupRunResult stdoutContainsSubsequence(String... expectedLinesOrFragments) {
        if (expectedLinesOrFragments.length > 0) {
            assertThat(Objects.requireNonNull(stdout)).containsSubsequence(expectedLinesOrFragments);
        }
        return this;
    }
}
