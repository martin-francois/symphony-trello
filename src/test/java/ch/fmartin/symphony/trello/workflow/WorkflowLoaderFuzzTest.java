package ch.fmartin.symphony.trello.workflow;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

final class WorkflowLoaderFuzzTest {
    private static final int MAX_MARKDOWN_BYTES = 8 * 1024;

    @TempDir
    Path tempDir;

    private final WorkflowLoader loader = new WorkflowLoader();

    @FuzzTest(maxDuration = "10s", maxExecutions = 20_000)
    void workflowLoaderHandlesArbitraryWorkflowBytes(FuzzedDataProvider data) throws IOException {
        // given
        byte[] candidate = data.consumeBytes(MAX_MARKDOWN_BYTES);

        // when
        WorkflowLoadBoundaryResult result = loadBoundary(candidate);

        // then
        assertWorkflowLoadBoundary(candidate, result);
    }

    @MethodSource("workflowBytes")
    @ParameterizedTest
    @SuppressWarnings("JUnitValueSource")
    void workflowLoaderSeedsHandleKnownBoundaries(byte[] markdown) throws IOException {
        // given
        byte[] candidate = markdown;

        // when
        WorkflowLoadBoundaryResult result = loadBoundary(candidate);

        // then
        assertWorkflowLoadBoundary(candidate, result);
    }

    private WorkflowLoadBoundaryResult loadBoundary(byte[] markdown) throws IOException {
        Path workflow = tempDir.resolve("WORKFLOW.md");
        Files.write(workflow, markdown);

        try {
            return new WorkflowLoadBoundaryResult(loader.load(workflow), null, Files.readAllBytes(workflow));
        } catch (WorkflowException expected) {
            return new WorkflowLoadBoundaryResult(null, expected, Files.readAllBytes(workflow));
        }
    }

    private static void assertWorkflowLoadBoundary(byte[] markdown, WorkflowLoadBoundaryResult result) {
        if (result.definition() != null) {
            assertThat(result.definition().path()).isAbsolute();
            assertThat(result.definition().config()).isNotNull();
            assertThat(result.definition().promptTemplate()).isNotNull();
        }
        if (result.failure() != null) {
            assertThat(result.failure().code())
                    .isIn("missing_workflow_file", "workflow_front_matter_not_a_map", "workflow_parse_error");
            assertThat(result.failure()).hasMessageNotContaining("\n");
        }
        assertThat(result.persistedBytes()).isEqualTo(markdown);
    }

    private static Stream<byte[]> workflowBytes() {
        return Stream.of(
                bytes(""),
                bytes(
                        """
                        ---
                        tracker:
                          kind: trello
                          board_id: board-1
                        ---
                        ## Work
                        """),
                bytes(
                        """
                        ---
                        []
                        ---
                        Body
                        """),
                bytes(
                        """
                        ---
                        :
                        ---
                        Body
                        """),
                new byte[] {(byte) 0xC3, 0x28});
    }

    private static byte[] bytes(String value) {
        return value.getBytes(UTF_8);
    }

    private static final class WorkflowLoadBoundaryResult {
        private final WorkflowDefinition definition;
        private final WorkflowException failure;
        private final byte[] persistedBytes;

        private WorkflowLoadBoundaryResult(
                WorkflowDefinition definition, WorkflowException failure, byte[] persistedBytes) {
            this.definition = definition;
            this.failure = failure;
            this.persistedBytes = persistedBytes;
        }

        private WorkflowDefinition definition() {
            return definition;
        }

        private WorkflowException failure() {
            return failure;
        }

        private byte[] persistedBytes() {
            return persistedBytes;
        }
    }
}
