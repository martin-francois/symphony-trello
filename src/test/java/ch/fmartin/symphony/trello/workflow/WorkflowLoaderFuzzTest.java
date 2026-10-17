package ch.fmartin.symphony.trello.workflow;

import static ch.fmartin.symphony.trello.fuzz.WorkflowLoaderInvariants.MAX_MARKDOWN_BYTES;
import static ch.fmartin.symphony.trello.fuzz.WorkflowLoaderInvariants.assertWorkflowLoaderProperties;
import static ch.fmartin.symphony.trello.fuzz.WorkflowLoaderInvariants.parse;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Named.named;

import ch.fmartin.symphony.trello.fuzz.WorkflowLoaderInvariants.LoadResult;
import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

final class WorkflowLoaderFuzzTest {
    private static final int DEEP_NESTING_LEVELS = 4_000;
    // Enough "?," pairs that the config has more nodes than the input has bytes.
    private static final int EXPLICIT_EMPTY_KEYS = 40;

    @TempDir
    Path tempDir;

    private final WorkflowLoader loader = new WorkflowLoader();

    @FuzzTest(maxDuration = "10s", maxExecutions = 20_000)
    void workflowLoaderHandlesArbitraryWorkflowBytes(FuzzedDataProvider data) {
        // given
        byte[] candidate = data.consumeBytes(MAX_MARKDOWN_BYTES);

        // when
        LoadResult result = parse(loader, candidate);

        // then
        assertWorkflowLoaderProperties(loader, candidate, result);
    }

    @MethodSource("workflowBytes")
    @ParameterizedTest
    @SuppressWarnings("JUnitValueSource")
    void workflowLoaderSeedsHandleKnownBoundaries(byte[] markdown) {
        // given
        byte[] candidate = markdown;

        // when
        LoadResult result = parse(loader, candidate);

        // then
        assertWorkflowLoaderProperties(loader, candidate, result);
    }

    @MethodSource("workflowBytes")
    @ParameterizedTest
    @SuppressWarnings("JUnitValueSource")
    void fileLoadMatchesInMemoryParse(byte[] markdown) throws IOException {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");
        Files.write(workflow, markdown);

        // when
        String fromFile = LoadResult.of(() -> loader.load(workflow)).canonical();
        String fromMemory =
                LoadResult.of(() -> loader.parse(workflow, markdown)).canonical();

        // then
        assertThat(fromFile).isEqualTo(fromMemory);
        assertThat(workflow).hasBinaryContent(markdown);
    }

    @Test
    void nestingBeyondTheParserDepthLimitIsRejectedAsParseError() {
        // given
        byte[] candidate = deeplyNestedFrontMatter();

        // when
        LoadResult result = parse(loader, candidate);

        // then
        assertThat(result.failure())
                .as("deep nesting fails as a workflow error instead of overflowing the stack")
                .extracting(WorkflowException::code)
                .isEqualTo("workflow_parse_error");
    }

    private static Stream<Named<byte[]>> workflowBytes() {
        return Stream.of(
                named("empty file", bytes("")),
                named(
                        "valid front matter",
                        bytes(
                                """
                                ---
                                tracker:
                                  kind: trello
                                  board_id: board-1
                                ---
                                ## Work
                                """)),
                named("list front matter", bytes("---\n[]\n---\nBody\n")),
                named("empty key", bytes("---\n:\n---\nBody\n")),
                named(
                        "explicit empty keys in a flow sequence",
                        bytes("---\nx: [" + "?,".repeat(EXPLICIT_EMPTY_KEYS) + "?]\n---\n")),
                named("empty front matter", bytes("---\n---\nBody\n")),
                named("unclosed front matter", bytes("---\ntracker:\n  kind: trello\nBody\n")),
                named("CRLF and lone CR line ends", bytes("---\r\ntracker: {}\r---\r\n\r\nBody\rline\r\n")),
                named("binary scalar", bytes("---\nseed: !!binary aGVsbG8=\n---\nBody\n")),
                named("alias chain", bytes("---\na: &a [x, x, x]\nb: &b [*a, *a, *a]\nc: [*b, *b, *b]\n---\nBody\n")),
                named("nesting beyond the parser depth limit", deeplyNestedFrontMatter()),
                named("invalid UTF-8", new byte[] {(byte) 0xC3, 0x28}));
    }

    private static byte[] deeplyNestedFrontMatter() {
        return bytes("---\nx: " + "[".repeat(DEEP_NESTING_LEVELS) + "]".repeat(DEEP_NESTING_LEVELS) + "\n---\nBody\n");
    }

    private static byte[] bytes(String value) {
        return value.getBytes(UTF_8);
    }
}
