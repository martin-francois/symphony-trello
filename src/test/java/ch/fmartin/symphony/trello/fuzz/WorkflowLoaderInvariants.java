package ch.fmartin.symphony.trello.fuzz;

import static java.nio.charset.StandardCharsets.UTF_8;

import ch.fmartin.symphony.trello.workflow.WorkflowDefinition;
import ch.fmartin.symphony.trello.workflow.WorkflowException;
import ch.fmartin.symphony.trello.workflow.WorkflowLoader;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Domain properties of {@link WorkflowLoader} shared by the standalone {@code WorkflowLoaderFuzzer} and the
 * JUnit {@code WorkflowLoaderFuzzTest}. It throws {@link AssertionError} instead of using AssertJ because the
 * OSS-Fuzz runtime classpath has no test libraries.
 */
public final class WorkflowLoaderInvariants {
    public static final int MAX_MARKDOWN_BYTES = 8 * 1024;

    // Resolves against user.dir without touching the filesystem.
    private static final Path WORKFLOW_PATH =
            Path.of("WORKFLOW.md").toAbsolutePath().normalize();
    private static final String UNREADABLE_CODE = "missing_workflow_file";
    private static final String PARSE_ERROR_CODE = "workflow_parse_error";
    private static final Set<String> FAILURE_CODES =
            Set.of(UNREADABLE_CODE, PARSE_ERROR_CODE, "workflow_front_matter_not_a_map");
    private static final String FRONT_MATTER_DELIMITER = "---";
    private static final String EMPTY_FRONT_MATTER = "---\n{}\n---\n";
    private static final Pattern LINE_TERMINATOR = Pattern.compile("\\R");
    private static final Pattern FILE_LINE_TERMINATOR = Pattern.compile("\r\n|\r|\n");
    // The densest valid YAML found so far is "?," in a flow sequence, which becomes {"": null}: three
    // nodes for two bytes. The factor leaves headroom for denser forms while still failing on alias
    // expansion, which grows exponentially.
    private static final int MAX_NODES_PER_INPUT_BYTE = 4;
    private static final ObjectMapper CANONICAL_JSON =
            new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    private WorkflowLoaderInvariants() {}

    public static LoadResult parse(WorkflowLoader loader, byte[] markdown) {
        return LoadResult.of(() -> loader.parse(WORKFLOW_PATH, markdown));
    }

    public static void assertWorkflowLoaderProperties(WorkflowLoader loader, byte[] markdown, LoadResult result) {
        check(
                result.canonical().equals(parse(loader, markdown).canonical()),
                "parsing the same bytes twice gave a different result");
        Optional<String> text = strictUtf8(markdown);
        check(
                text.isEmpty() == UNREADABLE_CODE.equals(result.failureCode()),
                "invalid UTF-8 and only invalid UTF-8 is rejected as unreadable");
        text.ifPresent(decoded -> assertFrontMatterRules(decoded, result));
        if (result.failure() != null) {
            assertExplicitRejection(result.failure());
        }
        if (result.definition() != null) {
            assertAcceptedDefinition(loader, markdown, result.definition());
        }
    }

    private static void assertFrontMatterRules(String text, LoadResult result) {
        List<String> lines = text.lines().toList();
        if (lines.isEmpty() || !FRONT_MATTER_DELIMITER.equals(lines.getFirst().trim())) {
            WorkflowDefinition definition = result.definition();
            if (definition == null) {
                throw violation("markdown without front matter is accepted");
            }
            check(definition.config().isEmpty(), "markdown without front matter has an empty config");
            check(
                    definition
                            .promptTemplate()
                            .equals(FILE_LINE_TERMINATOR
                                    .matcher(text)
                                    .replaceAll(System.lineSeparator())
                                    .trim()),
                    "markdown without front matter becomes the whole trimmed prompt");
        } else if (lines.stream().skip(1).noneMatch(line -> FRONT_MATTER_DELIMITER.equals(line.trim()))) {
            check(
                    PARSE_ERROR_CODE.equals(result.failureCode()),
                    "front matter without a closing delimiter is a parse error");
        }
    }

    private static void assertExplicitRejection(WorkflowException failure) {
        check(FAILURE_CODES.contains(failure.code()), "failure code is one of the specified workflow errors");
        check(
                failure.getMessage() != null
                        && !LINE_TERMINATOR.matcher(failure.getMessage()).find(),
                "failure message is one line");
    }

    private static void assertAcceptedDefinition(
            WorkflowLoader loader, byte[] markdown, WorkflowDefinition definition) {
        check(WORKFLOW_PATH.equals(definition.path()), "definition keeps the normalized workflow path");
        String prompt = definition.promptTemplate();
        check(prompt.equals(prompt.trim()), "prompt template is trimmed");
        // Jackson turns a YAML alias into its anchor name instead of expanding it. A parser change that
        // starts expanding aliases could turn a few kilobytes into an exponential config, which this catches.
        long nodesBelowRoot = nodeCount(definition.config()) - 1;
        check(
                nodesBelowRoot <= (long) MAX_NODES_PER_INPUT_BYTE * markdown.length,
                "config size stays linear in the input size");

        WorkflowDefinition reparsed = loader.parse(WORKFLOW_PATH, (EMPTY_FRONT_MATTER + prompt).getBytes(UTF_8));
        check(reparsed.config().isEmpty(), "re-wrapped prompt adds no config");
        check(reparsed.promptTemplate().equals(prompt), "prompt survives a parse after re-wrapping");
    }

    private static Optional<String> strictUtf8(byte[] markdown) {
        try {
            return Optional.of(
                    UTF_8.newDecoder().decode(ByteBuffer.wrap(markdown)).toString());
        } catch (CharacterCodingException invalid) {
            return Optional.empty();
        }
    }

    private static long nodeCount(@Nullable Object value) {
        return switch (value) {
            case Map<?, ?> map ->
                1
                        + map.entrySet().stream()
                                .mapToLong(entry -> nodeCount(entry.getKey()) + nodeCount(entry.getValue()))
                                .sum();
            case List<?> list ->
                1 + list.stream().mapToLong(WorkflowLoaderInvariants::nodeCount).sum();
            case null, default -> 1;
        };
    }

    private static void check(boolean property, String description) {
        if (!property) {
            throw violation(description);
        }
    }

    private static AssertionError violation(String description) {
        return new AssertionError("workflow loader property violated: " + description);
    }

    public record LoadResult(@Nullable WorkflowDefinition definition, @Nullable WorkflowException failure) {
        public static LoadResult of(Supplier<WorkflowDefinition> load) {
            try {
                return new LoadResult(load.get(), null);
            } catch (WorkflowException failure) {
                return new LoadResult(null, failure);
            }
        }

        @Nullable
        String failureCode() {
            return failure == null ? null : failure.code();
        }

        /**
         * Describes the result by value. Config values can hold {@code byte[]} from YAML {@code !!binary}, so
         * {@link Map#equals} would compare arrays by identity; key-sorted JSON compares their content.
         */
        public String canonical() {
            if (definition == null) {
                return failure == null ? "no result" : "failure " + failure.code() + ": " + failure.getMessage();
            }
            try {
                return "definition " + definition.path() + " " + CANONICAL_JSON.writeValueAsString(definition.config())
                        + " " + definition.promptTemplate();
            } catch (JsonProcessingException e) {
                throw new AssertionError("workflow config cannot be compared", e);
            }
        }
    }
}
