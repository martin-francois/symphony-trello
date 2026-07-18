package ch.fmartin.symphony.trello.testsupport;

import static ch.fmartin.symphony.trello.testsupport.TerminalTranscriptAssertions.assertThatTranscript;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.function.Supplier;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

final class TestSupportAssertionsTest {
    private static final String ALPHA = "alpha";
    private static final String BETA = "beta";

    @MethodSource("acceptedRunResultCases")
    @ParameterizedTest(name = "{0}")
    void runResultHelpersAcceptOrderedNonOverlappingAndEmptyFragments(
            String scenario, Object expectedResult, Supplier<Object> action) {
        // given

        // when
        Object returnedResult = action.get();

        // then
        assertThat(returnedResult).as(scenario).isSameAs(expectedResult);
    }

    @MethodSource("rejectedRunResultCases")
    @ParameterizedTest(name = "{0}")
    void runResultHelpersRejectInvalidFragmentContracts(
            String scenario, ThrowingCallable action, Class<? extends Throwable> exceptionType) {
        // given

        // when

        // then
        assertThatThrownBy(action).as(scenario).isInstanceOf(exceptionType);
    }

    @Test
    void transcriptHelperAcceptsOrderedNonOverlappingSections() {
        // given
        TerminalTranscriptAssertions assertions = assertThatTranscript("alpha---beta");

        // when
        TerminalTranscriptAssertions returnedAssertions = assertions.containsSectionsInOrder(ALPHA, BETA);

        // then
        assertThat(returnedAssertions).isSameAs(assertions);
        assertThatThrownBy(() -> assertions.containsSectionsInOrder(BETA, ALPHA))
                .isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> assertions.containsSectionsInOrder("alpha---", "---beta"))
                .isInstanceOf(AssertionError.class);
    }

    @Test
    void transcriptHelperRetainsEmptyAndNullBoundaryBehavior() {
        // given
        TerminalTranscriptAssertions assertions = assertThatTranscript(null);

        // when
        TerminalTranscriptAssertions returnedAssertions = assertions.containsSectionsInOrder();

        // then
        assertThat(returnedAssertions).isSameAs(assertions);
        assertThatNullPointerException().isThrownBy(() -> assertions.containsSectionsInOrder("section"));
        assertThatNullPointerException().isThrownBy(() -> assertions.containsSectionsInOrder((String[]) null));
    }

    @Test
    void recordingTerminalQueuesConstructorInputInEncounterOrder() {
        // given
        var terminal = new RecordingTerminal("first", "second");

        // when
        String first = terminal.readLine("one: ");
        String second = terminal.readLine("two: ");
        String exhausted = terminal.readLine("three: ");

        // then
        assertThat(new String[] {first, second, exhausted})
                .as("terminal reads")
                .containsExactly("first", "second", null);
        assertThat(terminal.stdout()).isEqualTo("one: two: three: ");
    }

    @Test
    void recordingTerminalRetainsNullInputFailure() {
        // given
        String[] input = {"first", null};

        // when
        ThrowingCallable action = () -> new RecordingTerminal(input);

        // then
        assertThatNullPointerException().isThrownBy(action);
    }

    private static List<Arguments> acceptedRunResultCases() {
        var cliOrdered = new CliRunResult(0, "alpha---beta", "");
        var setupOrdered = new SetupRunResult(0, "alpha---beta", "");
        var cliEmpty = new CliRunResult(0, null, "");
        var setupEmpty = new SetupRunResult(0, null, "");
        Supplier<Object> cliOrderedAssertion = () -> cliOrdered.stdoutContainsSubsequence(ALPHA, BETA);
        Supplier<Object> setupOrderedAssertion = () -> setupOrdered.stdoutContainsSubsequence(ALPHA, BETA);
        Supplier<Object> cliEmptyAssertion = cliEmpty::stdoutContainsSubsequence;
        Supplier<Object> setupEmptyAssertion = setupEmpty::stdoutContainsSubsequence;
        return List.of(
                Arguments.of("CLI: ordered non-overlapping fragments", cliOrdered, cliOrderedAssertion),
                Arguments.of("setup: ordered non-overlapping fragments", setupOrdered, setupOrderedAssertion),
                Arguments.of("CLI: empty expectations with null stdout", cliEmpty, cliEmptyAssertion),
                Arguments.of("setup: empty expectations with null stdout", setupEmpty, setupEmptyAssertion));
    }

    private static List<Arguments> rejectedRunResultCases() {
        var cli = new CliRunResult(0, "alpha---beta", "");
        var setup = new SetupRunResult(0, "alpha---beta", "");
        var cliNullStdout = new CliRunResult(0, null, "");
        var setupNullStdout = new SetupRunResult(0, null, "");
        return List.of(
                Arguments.of(
                        "CLI: reversed fragments",
                        (ThrowingCallable) () -> cli.stdoutContainsSubsequence(BETA, ALPHA),
                        AssertionError.class),
                Arguments.of(
                        "setup: reversed fragments",
                        (ThrowingCallable) () -> setup.stdoutContainsSubsequence(BETA, ALPHA),
                        AssertionError.class),
                Arguments.of(
                        "CLI: overlapping fragments",
                        (ThrowingCallable) () -> cli.stdoutContainsSubsequence("alpha---", "---beta"),
                        AssertionError.class),
                Arguments.of(
                        "setup: overlapping fragments",
                        (ThrowingCallable) () -> setup.stdoutContainsSubsequence("alpha---", "---beta"),
                        AssertionError.class),
                Arguments.of(
                        "CLI: expected fragment with null stdout",
                        (ThrowingCallable) () -> cliNullStdout.stdoutContainsSubsequence("fragment"),
                        NullPointerException.class),
                Arguments.of(
                        "setup: expected fragment with null stdout",
                        (ThrowingCallable) () -> setupNullStdout.stdoutContainsSubsequence("fragment"),
                        NullPointerException.class),
                Arguments.of(
                        "CLI: null fragment array",
                        (ThrowingCallable) () -> cli.stdoutContainsSubsequence((String[]) null),
                        NullPointerException.class),
                Arguments.of(
                        "setup: null fragment array",
                        (ThrowingCallable) () -> setup.stdoutContainsSubsequence((String[]) null),
                        NullPointerException.class));
    }
}
