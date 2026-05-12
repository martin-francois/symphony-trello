package ch.fmartin.symphony.trello.setup;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

record CliRunResult(int exitCode, String stdout, String stderr) {
    CliRunResult assertSuccess() {
        assertThat(exitCode).as("stdout:%n%s%nstderr:%n%s", stdout, stderr).isZero();
        assertThat(stderr).isEmpty();
        return this;
    }

    CliRunResult assertFailure(int expectedExit) {
        assertThat(exitCode).as("stdout:%n%s%nstderr:%n%s", stdout, stderr).isEqualTo(expectedExit);
        return this;
    }

    CliRunResult stdoutContains(String... expected) {
        assertThat(stdout).contains(expected);
        return this;
    }

    CliRunResult stdoutDoesNotContain(String... forbidden) {
        assertThat(stdout).doesNotContain(forbidden);
        return this;
    }

    CliRunResult stderrContains(String... expected) {
        assertThat(stderr).contains(expected);
        return this;
    }

    CliRunResult stderrDoesNotContain(String... forbidden) {
        assertThat(stderr).doesNotContain(forbidden);
        return this;
    }

    CliRunResult stderrEmpty() {
        assertThat(stderr).isEmpty();
        return this;
    }

    List<String> stdoutLines() {
        return stdout.lines().toList();
    }

    CliRunResult stdoutContainsSubsequence(String... expectedLinesOrFragments) {
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
