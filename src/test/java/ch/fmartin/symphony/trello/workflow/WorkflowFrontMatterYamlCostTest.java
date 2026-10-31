package ch.fmartin.symphony.trello.workflow;

import static ch.fmartin.symphony.trello.workflow.WorkflowFrontMatterYaml.MAX_ALIAS_COPIED_CHARACTERS;
import static ch.fmartin.symphony.trello.workflow.WorkflowFrontMatterYaml.MAX_ALIAS_EXPANDED_NODES;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Named.named;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import ch.fmartin.symphony.trello.config.TypedWorkflowConfig;
import ch.fmartin.symphony.trello.config.WorkflowConfigIngestion;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.ThrowingSupplier;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/// Front matter costs about what Jackson's own YAML reader costs, however its anchors nest, and
/// hostile front matter fails as a normal parse error in every caller instead of exhausting memory.
final class WorkflowFrontMatterYamlCostTest {
    private static final Path WORKFLOW = Path.of("WORKFLOW.md");
    private static final ObjectMapper JACKSON_YAML = new ObjectMapper(new YAMLFactory());
    private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final ThreadMXBean THREADS = ManagementFactory.getPlatformMXBean(ThreadMXBean.class);
    // Keeping a copy of each event for every anchored collection around it allocated five times what
    // Jackson allocates for these anchors; one shared record of the events stays close to Jackson.
    private static final int NESTED_ANCHORS = 200;
    private static final int SCALARS = 100_000;
    private static final double ALLOCATION_RATIO_LIMIT = 2.0;
    private static final Duration FAIL_FAST_LIMIT = Duration.ofSeconds(10);
    private static final int LAUGH_LEVELS = 9;
    private static final int LAUGHS_PER_LEVEL = 9;

    private final WorkflowLoader loader = new WorkflowLoader();

    @Test
    void deeplyNestedAnchorsAllocateAboutWhatJacksonAllocates() throws Throwable {
        // given
        String frontMatter = nestedAnchors(NESTED_ANCHORS, SCALARS);

        // when
        long jackson = allocatedBytes(() -> JACKSON_YAML.readValue(frontMatter, MAP_TYPE));
        long frontMatterReader = allocatedBytes(() -> WorkflowFrontMatterYaml.readMap(frontMatter));

        // then
        assertThat((double) frontMatterReader).isLessThanOrEqualTo(jackson * ALLOCATION_RATIO_LIMIT);
    }

    @Test
    void anchorsNestedAroundManyScalarsLoadInEveryCaller() {
        // given
        String frontMatter = nestedAnchors(600, 400_000);

        // when
        Throwable loaderFailure = assertTimeoutPreemptively(
                FAIL_FAST_LIMIT, () -> catchThrowable(() -> loader.parse(WORKFLOW, workflow(frontMatter))));
        Optional<TypedWorkflowConfig> startupRead =
                assertTimeoutPreemptively(FAIL_FAST_LIMIT, () -> startupRead(frontMatter));

        // then
        assertThat(loaderFailure).isNull();
        assertThat(startupRead).isPresent();
    }

    @MethodSource("hostileFrontMatter")
    @ParameterizedTest
    void hostileFrontMatterIsAParseErrorInEveryCaller(String frontMatter, String expectedMessageStart) {
        // given
        byte[] workflow = workflow(frontMatter);

        // when
        Throwable loaderFailure = assertTimeoutPreemptively(
                FAIL_FAST_LIMIT, () -> catchThrowable(() -> loader.parse(WORKFLOW, workflow)));
        Optional<TypedWorkflowConfig> startupRead =
                assertTimeoutPreemptively(FAIL_FAST_LIMIT, () -> startupRead(frontMatter));

        // then
        assertThat(loaderFailure)
                .isInstanceOf(WorkflowException.class)
                .hasMessageStartingWith(expectedMessageStart)
                .extracting(failure -> ((WorkflowException) failure).code())
                .isEqualTo("workflow_parse_error");
        assertThat(startupRead).isEmpty();
    }

    private static Stream<Arguments> hostileFrontMatter() {
        String longScalar = "x".repeat(200_000);
        return Stream.of(
                arguments(
                        named("nine levels of nine aliases", billionLaughs()),
                        "Workflow front matter aliases expand to more than " + MAX_ALIAS_EXPANDED_NODES
                                + " YAML nodes"),
                arguments(
                        named(
                                "one long scalar copied by many aliases",
                                "a: &x " + longScalar + "\nb: [" + "*x,".repeat(MAX_ALIAS_EXPANDED_NODES - 1) + "]\n"),
                        "Workflow front matter aliases copy more than " + MAX_ALIAS_COPIED_CHARACTERS + " characters"),
                arguments(
                        named("sequences nested 20,000 deep", "a: " + "[".repeat(20_000) + "]".repeat(20_000) + "\n"),
                        "Workflow front matter is invalid YAML"));
    }

    private static Optional<TypedWorkflowConfig> startupRead(String frontMatter) {
        return WorkflowConfigIngestion.collectFrontMatter(
                frontMatter, ignored -> Optional.empty(), WorkflowConfigIngestion.UnresolvedEnvironmentPolicy.OMIT);
    }

    private static long allocatedBytes(ThrowingSupplier<?> read) throws Throwable {
        long before = THREADS.getCurrentThreadAllocatedBytes();
        read.get();
        return THREADS.getCurrentThreadAllocatedBytes() - before;
    }

    private static String nestedAnchors(int depth, int scalars) {
        StringBuilder yaml = new StringBuilder("a: ");
        for (int level = 0; level < depth; level++) {
            yaml.append("&a").append(level).append(" [");
        }
        yaml.repeat("x,", scalars);
        yaml.repeat("]", depth);
        return yaml.append('\n').toString();
    }

    // Nine levels of nine aliases would expand to 9^9 scalars.
    private static String billionLaughs() {
        StringBuilder yaml = new StringBuilder("lol0: &lol0 lol\n");
        for (int level = 1; level <= LAUGH_LEVELS; level++) {
            yaml.append("lol").append(level).append(": &lol").append(level).append(" [");
            yaml.repeat("*lol" + (level - 1) + ", ", LAUGHS_PER_LEVEL);
            yaml.append("]\n");
        }
        return yaml.toString();
    }

    private static byte[] workflow(String frontMatter) {
        return ("---\n" + frontMatter + "---\nBody\n").getBytes(UTF_8);
    }
}
