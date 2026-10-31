package ch.fmartin.symphony.trello.fuzz;

import static java.nio.charset.StandardCharsets.UTF_8;

import ch.fmartin.symphony.trello.workflow.WorkflowDefinition;
import ch.fmartin.symphony.trello.workflow.WorkflowException;
import ch.fmartin.symphony.trello.workflow.WorkflowFrontMatterYaml;
import ch.fmartin.symphony.trello.workflow.WorkflowLoader;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.google.common.base.Splitter;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;
import org.yaml.snakeyaml.nodes.Tag;

/// Domain properties of [WorkflowLoader] shared by the standalone `WorkflowLoaderFuzzer` and the
/// JUnit `WorkflowLoaderFuzzTest`. It throws [AssertionError] instead of using AssertJ because the
/// OSS-Fuzz runtime classpath has no test libraries.
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
    private static final Pattern ANY_UNICODE_LINE_BREAK = Pattern.compile("\\R");
    // The line ends that separate front matter lines; other Unicode breaks stay inside a line.
    private static final Pattern WORKFLOW_LINE_END = Pattern.compile("\r\n|\r|\n");
    private static final Splitter WORKFLOW_LINES = Splitter.on(WORKFLOW_LINE_END);
    // An explicit empty key, "?," in a flow sequence, becomes {"": null}: three nodes for two bytes. The
    // factor leaves headroom for denser forms. Aliases may add at most the alias limit on top, so
    // nested aliases that grow exponentially still fail the bound.
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
        List<String> lines = WORKFLOW_LINES.splitToList(text);
        if (!isFrontMatterDelimiter(lines.getFirst())) {
            WorkflowDefinition definition = result.definition();
            if (definition == null) {
                throw violation("markdown without front matter is accepted");
            }
            check(definition.config().isEmpty(), "markdown without front matter has an empty config");
            check(
                    definition
                            .promptTemplate()
                            .equals(WORKFLOW_LINE_END
                                    .matcher(text)
                                    .replaceAll(System.lineSeparator())
                                    .trim()),
                    "markdown without front matter becomes the whole trimmed prompt");
        } else {
            int closing = closingDelimiter(lines);
            if (closing < 0) {
                check(
                        PARSE_ERROR_CODE.equals(result.failureCode()),
                        "front matter without a closing delimiter is a parse error");
            } else {
                String frontMatter = String.join("\n", lines.subList(1, closing));
                assertYamlRules(frontMatter, result);
                WorkflowConfigInvariants.assertStartupReadAgrees(frontMatter, result);
            }
        }
    }

    private static int closingDelimiter(List<String> lines) {
        for (int i = 1; i < lines.size(); i++) {
            if (isFrontMatterDelimiter(lines.get(i))) {
                return i;
            }
        }
        return -1;
    }

    /// Checks the YAML rules of `SPEC.md` section 5.2 against SnakeYAML's composer, a YAML reader
    /// independent of the loader. It resolves aliases into shared nodes, keeps merge keys as keys
    /// tagged `!!merge`, and keeps repeated keys, so each rule is visible in its node graph.
    private static void assertYamlRules(String frontMatter, LoadResult result) {
        Node root;
        try {
            root = new Yaml().compose(new StringReader(frontMatter));
        } catch (RuntimeException notComposable) {
            // Invalid YAML, several documents, or one of SnakeYAML's own limits: no oracle.
            return;
        }
        WorkflowDefinition definition = result.definition();
        if (root == null) {
            check(
                    definition != null && definition.config().isEmpty(),
                    "front matter without content is an empty config");
            return;
        }
        if (hasMergeOrRepeatedKey(root)) {
            check(definition == null, "front matter with a merge key or a repeated key is rejected");
            return;
        }
        if (definition != null && root instanceof MappingNode) {
            check(
                    hasShape(root, definition.config()),
                    "an accepted config has the structure of the YAML with every alias resolved");
        }
    }

    /// Whether a node that SnakeYAML's composer built holds a merge key or a key repeated in one map,
    /// the two keys that `SPEC.md` section 5.2 rejects.
    public static boolean hasMergeOrRepeatedKey(Node root) {
        return hasMergeOrRepeatedKey(root, Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    // Aliases make the graph share nodes and may make it cyclic, so each node is visited once.
    private static boolean hasMergeOrRepeatedKey(Node node, Set<Node> visited) {
        if (!visited.add(node)) {
            return false;
        }
        if (node instanceof SequenceNode sequence) {
            for (Node element : sequence.getValue()) {
                if (hasMergeOrRepeatedKey(element, visited)) {
                    return true;
                }
            }
        } else if (node instanceof MappingNode mapping) {
            Set<String> keys = new HashSet<>();
            for (NodeTuple entry : mapping.getValue()) {
                if (entry.getKeyNode() instanceof ScalarNode key
                        && (Tag.MERGE.equals(key.getTag()) || !keys.add(key.getValue()))) {
                    return true;
                }
                if (hasMergeOrRepeatedKey(entry.getKeyNode(), visited)
                        || hasMergeOrRepeatedKey(entry.getValueNode(), visited)) {
                    return true;
                }
            }
        }
        return false;
    }

    // Compares maps, lists and their keys; scalar values may differ in type between the readers.
    private static boolean hasShape(Node node, @Nullable Object value) {
        return switch (node) {
            case MappingNode mapping -> value instanceof Map<?, ?> map && hasMappingShape(mapping, map);
            case SequenceNode sequence -> value instanceof List<?> list && hasSequenceShape(sequence, list);
            default -> !(value instanceof Map<?, ?>) && !(value instanceof List<?>);
        };
    }

    private static boolean hasMappingShape(MappingNode mapping, Map<?, ?> map) {
        if (mapping.getValue().size() != map.size()) {
            return false;
        }
        for (NodeTuple entry : mapping.getValue()) {
            if (!(entry.getKeyNode() instanceof ScalarNode key)
                    || !map.containsKey(key.getValue())
                    || !hasShape(entry.getValueNode(), map.get(key.getValue()))) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasSequenceShape(SequenceNode sequence, List<?> list) {
        List<Node> elements = sequence.getValue();
        if (elements.size() != list.size()) {
            return false;
        }
        for (int i = 0; i < elements.size(); i++) {
            if (!hasShape(elements.get(i), list.get(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isFrontMatterDelimiter(String line) {
        return FRONT_MATTER_DELIMITER.equals(line.trim());
    }

    private static void assertExplicitRejection(WorkflowException failure) {
        check(FAILURE_CODES.contains(failure.code()), "failure code is one of the specified workflow errors");
        check(
                failure.getMessage() != null
                        && !ANY_UNICODE_LINE_BREAK.matcher(failure.getMessage()).find(),
                "failure message is one line");
    }

    private static void assertAcceptedDefinition(
            WorkflowLoader loader, byte[] markdown, WorkflowDefinition definition) {
        check(WORKFLOW_PATH.equals(definition.path()), "definition keeps the normalized workflow path");
        String prompt = definition.promptTemplate();
        check(prompt.equals(prompt.trim()), "prompt template is trimmed");
        // Aliases copy anchored nodes, but never more than the alias limit, so a few kilobytes of
        // nested aliases cannot turn into an exponential config.
        long nodesBelowRoot = nodeCount(definition.config()) - 1;
        check(
                nodesBelowRoot
                        <= (long) MAX_NODES_PER_INPUT_BYTE * markdown.length
                                + WorkflowFrontMatterYaml.MAX_ALIAS_EXPANDED_NODES,
                "config size stays linear in the input size plus the alias limit");

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
        long count = 1;
        switch (value) {
            case Map<?, ?> map -> {
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    count += nodeCount(entry.getKey()) + nodeCount(entry.getValue());
                }
            }
            case List<?> list -> {
                for (Object element : list) {
                    count += nodeCount(element);
                }
            }
            case null, default -> {}
        }
        return count;
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

        /// Describes the result by value. Config values can hold `byte[]` from YAML `!!binary`, so
        /// [Map#equals] would compare arrays by identity; key-sorted JSON compares their content.
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
