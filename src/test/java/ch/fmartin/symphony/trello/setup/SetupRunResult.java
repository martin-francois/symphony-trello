package ch.fmartin.symphony.trello.setup;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

record SetupRunResult(int exitCode, String stdout, String stderr) {
    SetupRunResult assertSuccess() {
        assertThat(exitCode).as("stdout:%n%s%nstderr:%n%s", stdout, stderr).isZero();
        assertThat(stderr).isEmpty();
        return this;
    }

    SetupRunResult assertFailure(int expectedExit) {
        assertThat(exitCode).as("stdout:%n%s%nstderr:%n%s", stdout, stderr).isEqualTo(expectedExit);
        return this;
    }

    SetupRunResult stdoutContains(String... expected) {
        assertThat(stdout).contains(expected);
        return this;
    }

    SetupRunResult stdoutDoesNotContain(String... forbidden) {
        assertThat(stdout).doesNotContain(forbidden);
        return this;
    }

    SetupRunResult stderrContains(String... expected) {
        assertThat(stderr).contains(expected);
        return this;
    }

    SetupRunResult stderrDoesNotContain(String... forbidden) {
        assertThat(stderr).doesNotContain(forbidden);
        return this;
    }

    SetupRunResult stderrEmpty() {
        assertThat(stderr).isEmpty();
        return this;
    }

    List<String> stdoutLines() {
        return stdout.lines().toList();
    }

    SetupRunResult stdoutContainsSubsequence(String... expectedLinesOrFragments) {
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
