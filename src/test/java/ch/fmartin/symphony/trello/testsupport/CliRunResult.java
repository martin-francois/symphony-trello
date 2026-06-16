package ch.fmartin.symphony.trello.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

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
        int cursor = 0;
        for (String expected : expectedLinesOrFragments) {
            int next = stdout.indexOf(expected, cursor);
            assertThat(next)
                    .as("stdout fragment %s appears after offset %s", expected, cursor)
                    .isGreaterThanOrEqualTo(cursor);
            cursor = next + expected.length();
        }
        return this;
    }
}
