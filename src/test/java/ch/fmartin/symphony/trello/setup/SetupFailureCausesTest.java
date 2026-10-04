package ch.fmartin.symphony.trello.setup;

import static ch.fmartin.symphony.trello.testsupport.TestFileContents.INVALID_UTF_8_READ_FAILURE;
import static ch.fmartin.symphony.trello.testsupport.TestFileContents.INVALID_YAML_QUOTING_PRIVATE_VALUE;
import static ch.fmartin.symphony.trello.testsupport.TestFileContents.PRIVATE_YAML_VALUE;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.net.ConnectException;
import java.nio.channels.UnresolvedAddressException;
import java.nio.charset.MalformedInputException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.NoSuchFileException;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

final class SetupFailureCausesTest {
    private static final String PRIVATE_PATH = "/home/private-user/.config/symphony-trello/connected-boards.json";

    @MethodSource("causes")
    @ParameterizedTest(name = "{0}")
    void summaryNamesTheCauseTypeAndItsPathFreeDetail(String scenario, Throwable cause, String expected) {
        // given

        // when
        String summary = SetupFailureCauses.summary(cause);

        // then
        assertThat(summary).as(scenario).isEqualTo(expected).doesNotContain(PRIVATE_PATH);
    }

    private static Stream<Arguments> causes() {
        return Stream.of(
                Arguments.of(
                        "hand-written IOException message",
                        new IOException("Selected dotenv parent is not a directory."),
                        "IOException: Selected dotenv parent is not a directory."),
                Arguments.of("IOException without message", new IOException(), "IOException"),
                Arguments.of("IOException with blank message", new IOException(" "), "IOException"),
                Arguments.of("JDK detail without a path", new MalformedInputException(1), INVALID_UTF_8_READ_FAILURE),
                Arguments.of(
                        "file-system failure with a reason",
                        new AccessDeniedException(PRIVATE_PATH, null, "Permission denied"),
                        "AccessDeniedException: Permission denied"),
                Arguments.of(
                        "file-system failure without a reason",
                        new NoSuchFileException(PRIVATE_PATH),
                        "NoSuchFileException"),
                Arguments.of(
                        "file-system failure naming two paths",
                        new FileAlreadyExistsException(PRIVATE_PATH, PRIVATE_PATH + ".tmp", null),
                        "FileAlreadyExistsException"),
                Arguments.of(
                        "network failure whose detail is only in the root cause",
                        new ConnectException()
                                .initCause(new ConnectException().initCause(new UnresolvedAddressException())),
                        "ConnectException, caused by UnresolvedAddressException"),
                Arguments.of(
                        "root cause on a file-system failure",
                        new IOException("Could not rotate worker log", new AccessDeniedException(PRIVATE_PATH)),
                        "IOException: Could not rotate worker log, caused by AccessDeniedException"));
    }

    @Test
    void summaryOmitsParserMessagesBecauseTheyQuoteTheParsedContent() {
        // given
        IOException parserFailure = yamlParserFailure(INVALID_YAML_QUOTING_PRIVATE_VALUE);

        // when
        String summary = SetupFailureCauses.summary(parserFailure);

        // then
        assertThat(parserFailure).hasMessageContaining(PRIVATE_YAML_VALUE);
        assertThat(summary).isEqualTo(parserFailure.getClass().getSimpleName()).doesNotContain(PRIVATE_YAML_VALUE);
    }

    @Test
    void summaryStopsAtAWrappedParserFailureBecauseItsCausesQuoteTheSameContent() {
        // given
        IOException parserFailure = yamlParserFailure(INVALID_YAML_QUOTING_PRIVATE_VALUE);
        var wrappedFailure = new IOException("Could not load workflow front matter", parserFailure);

        // when
        String summary = SetupFailureCauses.summary(wrappedFailure);

        // then
        assertThat(summary)
                .isEqualTo(
                        "IOException: Could not load workflow front matter, caused by %s",
                        parserFailure.getClass().getSimpleName())
                .doesNotContain(PRIVATE_YAML_VALUE);
    }

    @Test
    void withCauseAppendsTheSummaryInParentheses() {
        // given
        String message = "Could not update the connected-board manifest. Check the config directory permissions.";

        // when
        String rendered = SetupFailureCauses.withCause(message, new AccessDeniedException(PRIVATE_PATH));

        // then
        assertThat(rendered)
                .isEqualTo("Could not update the connected-board manifest. Check the config directory permissions."
                        + " (AccessDeniedException)");
    }

    @Test
    void setupFailureChainsTheCauseItNames() {
        // given
        var cause = new AccessDeniedException(PRIVATE_PATH);

        // when
        TrelloBoardSetupException failure =
                SetupFailureCauses.setupFailure("setup_manifest_write_failed", "Could not update the manifest.", cause);

        // then
        assertThat(failure.code()).isEqualTo("setup_manifest_write_failed");
        assertThat(failure)
                .hasMessage("Could not update the manifest. (AccessDeniedException)")
                .hasCause(cause);
    }

    @MethodSource("commandBoundaryFailures")
    @ParameterizedTest(name = "{0}")
    void commandBoundaryMessageSummarizesOnlyUnwrappedIoFailures(String scenario, Exception failure, String expected) {
        // given

        // when
        String message = SetupFailureCauses.commandBoundaryMessage(failure);

        // then
        assertThat(message).as(scenario).isEqualTo(expected);
    }

    private static Stream<Arguments> commandBoundaryFailures() {
        return Stream.of(
                Arguments.of(
                        "unwrapped I/O failure",
                        new AccessDeniedException(PRIVATE_PATH, null, "Permission denied"),
                        "AccessDeniedException: Permission denied"),
                Arguments.of(
                        "setup failure keeps its actionable message",
                        new TrelloBoardSetupException("setup_invalid_path", "Invalid Trello API path"),
                        "Invalid Trello API path"),
                Arguments.of(
                        "input validation keeps its actionable message",
                        new IllegalArgumentException("--board must not be empty."),
                        "--board must not be empty."));
    }

    private static IOException yamlParserFailure(String yaml) {
        try {
            new ObjectMapper(new YAMLFactory()).readValue(yaml, Map.class);
        } catch (IOException e) {
            return e;
        }
        throw new AssertionError("The YAML fixture must fail to parse.");
    }
}
