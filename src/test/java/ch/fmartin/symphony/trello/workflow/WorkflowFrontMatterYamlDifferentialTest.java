package ch.fmartin.symphony.trello.workflow;

import static ch.fmartin.symphony.trello.TextCharacterMatchers.UNICODE_BYTE_ORDER_MARK;
import static ch.fmartin.symphony.trello.TextCharacterMatchers.UNICODE_LINE_SEPARATOR;
import static ch.fmartin.symphony.trello.TextCharacterMatchers.UNICODE_PARAGRAPH_SEPARATOR;
import static org.assertj.core.api.Assertions.assertThat;

import ch.fmartin.symphony.trello.fuzz.WorkflowLoaderInvariants;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.charset.CharacterCodingException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.events.AliasEvent;
import org.yaml.snakeyaml.events.Event;
import org.yaml.snakeyaml.nodes.Node;

/// Loads a corpus of front matter through Jackson's plain YAML reader, which the workflow loader used
/// before the `SPEC.md` section 5.2 rules, and through [WorkflowFrontMatterYaml], and checks both give
/// the same values with the same Java types, or both fail. The corpus is every front matter in the
/// repository's workflow files, fuzz seeds and Java text blocks, plus hand-made YAML scalars and
/// structures. Inputs that the section 5.2 rules decide differently live in
/// [WorkflowFrontMatterYamlRulesTest] instead.
final class WorkflowFrontMatterYamlDifferentialTest {
    private static final ObjectMapper JACKSON_PLAIN = new ObjectMapper(new YAMLFactory());
    private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final Path REPOSITORY_ROOT = Path.of("");
    private static final List<Path> FRONT_MATTER_FILE_DIRECTORIES =
            List.of(Path.of("oss-fuzz/corpora"), Path.of("src/test/resources/ch/fmartin/symphony/trello/workflow"));
    private static final List<Path> JAVA_SOURCE_DIRECTORIES =
            List.of(Path.of("src/main/java"), Path.of("src/test/java"));
    private static final Pattern TEXT_BLOCK = Pattern.compile("\"\"\"[ \\t]*\\R(.*?)\"\"\"", Pattern.DOTALL);
    private static final String DELIMITER = "---";
    // A corpus this small would mean the harvest silently stopped finding the repository's workflows.
    private static final int MIN_REPOSITORY_FRONT_MATTERS = 100;
    // Keeps parameterized case names readable in test reports.
    private static final int MAX_CASE_NAME_LENGTH = 80;
    private static final String LATIN_SMALL_E_WITH_ACUTE = "\u00E9";

    private static final List<String> SCALARS = List.of(
            // YAML 1.1 and 1.2 booleans and near misses
            "true",
            "True",
            "TRUE",
            "false",
            "False",
            "FALSE",
            "tRUE",
            "yes",
            "Yes",
            "YES",
            "no",
            "No",
            "NO",
            "on",
            "On",
            "ON",
            "off",
            "Off",
            "OFF",
            "y",
            "Y",
            "n",
            "N",
            // nulls
            "null",
            "Null",
            "NULL",
            "nULL",
            "~",
            "",
            // integers in every base and form
            "0",
            "-0",
            "+1",
            "-17",
            "017",
            "08",
            "0o17",
            "0O17",
            "0x1F",
            "0X1f",
            "0xff_ff",
            "0b101",
            "0b1_0",
            "-0b101",
            "1_000",
            "1__0",
            "_1",
            "1_",
            "1,000",
            "190:20:30",
            "-190:20:30",
            "2147483647",
            "2147483648",
            "-2147483649",
            "9223372036854775807",
            "9223372036854775808",
            "12345678901234567890123",
            "0x8000000000000000",
            // floats
            "1.5",
            "-1.5",
            "+1.5",
            "1e3",
            "1E3",
            "1e+3",
            "3.0e+2",
            ".5",
            "-.5",
            "1.",
            "1_000.5",
            "6.8523015e+5",
            "685.230_15e+03",
            "190:20:30.15",
            "0.1e",
            "1e",
            "1.5.5",
            "0.0",
            "1.7976931348623157e309",
            // values Jackson cannot read as numbers, which both readers must reject the same way
            ".inf",
            "-.Inf",
            ".NaN",
            // timestamps stay strings
            "2001-12-14",
            "2001-12-14t21:59:43.10-05:00",
            "2001-12-14 21:59:43.10 -5",
            "12:30",
            // strings that look like other types or need quoting
            "'1'",
            "\"true\"",
            "'~'",
            "\"\"",
            "''",
            "'yes'",
            "\"0x1F\"",
            "a: b",
            "\"a: b\"",
            "a #not",
            "a#b",
            "\"tab\\there\"",
            "\"\\u00e9\"",
            "'it''s'",
            "\"line\\nbreak\"",
            "plain text",
            "  spaced  ",
            "$TRELLO_API_KEY",
            "file:~/secret",
            "~/cache",
            "@at",
            "`tick`",
            "%percent",
            "!bang",
            "&anchorless",
            "|",
            ">",
            "-",
            "- x",
            "? x",
            "[1, 2]",
            "{a: 1}",
            "\"[1, 2]\"",
            "<<",
            "\"<<\"",
            "'<<'",
            // explicit tags
            "!!str 5",
            "!!str true",
            "!!str",
            "!!int '7'",
            "!!int 0x1F",
            "!!float 1",
            "!!bool yes",
            "!!bool 'on'",
            "!!null ''",
            "!!null x",
            "!!binary aGVsbG8=",
            "!custom x",
            "!<tag:example.com,2026:x> y",
            "!!timestamp 2001-12-14",
            "! 5",
            "!!seq [1]",
            "!!map {a: 1}");

    private static final List<String> STRUCTURES = List.of(
            "a:\n  b:\n    c: 1\n",
            "a: {b: {c: [1, {d: 2}]}}\n",
            "a: []\nb: {}\nc: [[], {}]\n",
            "a:\n- 1\n- 2\n",
            "a:\n  - - x\n    - y\n  - - z\n",
            "a:\n  - k: 1\n    l: 2\n  - k: 3\n",
            "? a\n: 1\n",
            "? a\n",
            ": empty key\n",
            "a: 1 # comment\n# full line\nb: 2\n",
            "a: |\n  literal\n  text\n",
            "a: |-\n  stripped\n",
            "a: |+\n  kept\n\n",
            "a: >\n  folded\n  text\n",
            "a: >-\n  folded\n\n  paragraph\n",
            "a: \"double\n  quoted multi\"\n",
            "a: plain\n  multi line\n",
            "a: 'single\n  quoted'\n",
            "a: !!set {x, y}\n",
            "a: !!omap [x: 1, y: 2]\n",
            "a: !!pairs [x: 1, x: 2]\n",
            "a: [x: 1, y: 2]\n",
            "a: {x, y: 1}\n",
            "a: 1\n...\n",
            "a: 1\n--- # second\nb: 2\n",
            "%YAML 1.1\n--- \na: 1\n",
            "\"quoted key\": 1\n'single key': 2\n",
            "\"\": empty quoted key\n",
            "! <<: 1\n",
            "a:\n  ! <<: {b: 1}\n",
            "a b c: spaced key\n",
            "\"k\\u00e9y\": unicode escape\n",
            "k" + LATIN_SMALL_E_WITH_ACUTE + "y: unicode\n",
            "a: " + UNICODE_LINE_SEPARATOR + "\n",
            "a: x" + UNICODE_PARAGRAPH_SEPARATOR + "y\n",
            UNICODE_BYTE_ORDER_MARK + "a: bom\n",
            "a:    \n",
            "a: &anchor-only 1\n",
            "a: &x 1\n",
            "[1, 2]\n",
            "plain scalar\n",
            "42\n",
            "a: [\n",
            "a: b: c\n",
            "\ta: tab\n",
            "a:\n\t- tab\n",
            "a: 'unterminated\n",
            "- a\nb: 1\n",
            "a: 1\n  b: 2\n",
            "{a: 1, b: [x, y], c: {d: null}}\n",
            "a: [1, 2,]\n",
            "a: {,}\n",
            "a: [?, ?]\n",
            "1: a\n2.5: b\ntrue: c\n~: d\nnull: e\n0x10: f\n");

    @MethodSource({"repositoryFrontMatters", "handMadeFrontMatters"})
    @ParameterizedTest
    void frontMatterOutsideTheRulesLoadsAsBefore(String frontMatter) {
        // given
        String before = jacksonPlain(frontMatter);

        // when
        String after = frontMatterReader(frontMatter);

        // then
        assertThat(after).isEqualTo(before);
    }

    @Test
    void corpusCoversTheRepositoryWorkflowsAndFixtures() {
        // given
        List<String> harvested = repositoryFrontMatterTexts();

        // when
        List<String> compared = comparable(harvested);

        // then
        assertThat(harvested)
                .as("the harvest finds the example workflow, the fuzz seeds and the text blocks")
                .hasSizeGreaterThan(MIN_REPOSITORY_FRONT_MATTERS)
                .contains(frontMatter(read(Path.of("WORKFLOW.example.md"))).orElseThrow());
        assertThat(compared)
                .as("front matter that loads without a section 5.2 rule is compared")
                .filteredOn(yaml -> !jacksonPlain(yaml).startsWith("failure"))
                .hasSizeGreaterThan(MIN_REPOSITORY_FRONT_MATTERS);
    }

    private static List<Named<String>> repositoryFrontMatters() {
        return named(comparable(repositoryFrontMatterTexts()));
    }

    private static List<String> repositoryFrontMatterTexts() {
        List<String> found = new ArrayList<>();
        frontMatter(read(Path.of("WORKFLOW.example.md"))).ifPresent(found::add);
        for (Path directory : FRONT_MATTER_FILE_DIRECTORIES) {
            for (Path file : files(directory, "")) {
                readUtf8(file)
                        .flatMap(WorkflowFrontMatterYamlDifferentialTest::frontMatter)
                        .ifPresent(found::add);
            }
        }
        for (Path directory : JAVA_SOURCE_DIRECTORIES) {
            for (Path file : files(directory, ".java")) {
                Matcher block = TEXT_BLOCK.matcher(read(file));
                while (block.find()) {
                    textBlockValue(block.group(1))
                            .flatMap(WorkflowFrontMatterYamlDifferentialTest::frontMatter)
                            .ifPresent(found::add);
                }
            }
        }
        return found;
    }

    private static List<Named<String>> handMadeFrontMatters() {
        List<String> corpus = new ArrayList<>(STRUCTURES);
        for (String scalar : SCALARS) {
            corpus.add("v: " + scalar + "\n");
            corpus.add(scalar + ": v\n");
            corpus.add("l: [" + scalar + "]\n");
            corpus.add("l:\n  - " + scalar + "\n");
            corpus.add("m: {k: " + scalar + "}\n");
        }
        return named(comparable(corpus));
    }

    private static List<String> comparable(List<String> corpus) {
        // Keeps the first occurrence in corpus order, so parameterized case numbers stay stable.
        Set<String> unique = new LinkedHashSet<>(corpus);
        List<String> comparable = new ArrayList<>();
        for (String yaml : unique) {
            if (outsideTheDecidedRules(yaml)) {
                comparable.add(yaml);
            }
        }
        return comparable;
    }

    private static List<Named<String>> named(List<String> corpus) {
        List<Named<String>> cases = new ArrayList<>();
        for (String yaml : corpus) {
            cases.add(Named.of(abbreviate(yaml), yaml));
        }
        return cases;
    }

    // Inputs that use a section 5.2 rule (an alias, a merge key, a repeated key, or no content) load
    // differently on purpose; WorkflowFrontMatterYamlRulesTest owns them. SnakeYAML's own parser and
    // composer find them, independently of the code under test.
    private static boolean outsideTheDecidedRules(String yaml) {
        var snakeYaml = new Yaml();
        try {
            for (Event event : snakeYaml.parse(new StringReader(yaml))) {
                if (event instanceof AliasEvent) {
                    return false;
                }
            }
            Node root = WorkflowLoaderInvariants.compose(yaml);
            return root != null && !WorkflowLoaderInvariants.hasMergeOrRepeatedKey(root);
        } catch (YAMLException notOneValidDocument) {
            return true;
        }
    }

    private static String jacksonPlain(String yaml) {
        try {
            return typed(JACKSON_PLAIN.readValue(yaml, MAP_TYPE));
        } catch (IOException | RuntimeException e) {
            return failure(e);
        }
    }

    private static String frontMatterReader(String yaml) {
        try {
            return typed(WorkflowFrontMatterYaml.readMap(yaml));
        } catch (IOException | RuntimeException e) {
            return failure(e);
        }
    }

    // Both readers may word a failure differently; only the fact that YAML failed must match, except
    // for the rule violations, which the corpus filter keeps out of the comparison.
    private static String failure(Exception e) {
        if (e instanceof WorkflowFrontMatterYaml.RuleViolation) {
            return "failure " + e.getClass().getSimpleName() + " " + e.getMessage();
        }
        String message = String.valueOf(e.getMessage());
        return message.contains("No content to map due to end-of-input")
                ? "failure No content to map due to end-of-input"
                : "failure";
    }

    /// Spells out a value with the Java type of every node, so `1` and `"1"` or `1` and `1L` differ.
    private static String typed(@Nullable Object value) {
        return switch (value) {
            case null -> "null";
            case Map<?, ?> map -> {
                List<String> entries = new ArrayList<>();
                map.forEach((key, entry) -> entries.add(typed(key) + "=" + typed(entry)));
                yield "{" + String.join(", ", entries) + "}";
            }
            case List<?> list -> {
                List<String> elements = new ArrayList<>();
                list.forEach(element -> elements.add(typed(element)));
                yield "[" + String.join(", ", elements) + "]";
            }
            case byte[] bytes -> "byte[]" + Arrays.toString(bytes);
            default -> value.getClass().getSimpleName() + ":" + value;
        };
    }

    // Splits like WorkflowLoader: the first line is ---, and the front matter ends at the next ---.
    private static Optional<String> frontMatter(String markdown) {
        List<String> lines = markdown.lines().toList();
        if (lines.isEmpty() || !DELIMITER.equals(lines.getFirst().trim())) {
            return Optional.empty();
        }
        for (int i = 1; i < lines.size(); i++) {
            if (DELIMITER.equals(lines.get(i).trim())) {
                return Optional.of(String.join("\n", lines.subList(1, i)));
            }
        }
        return Optional.empty();
    }

    private static Optional<String> textBlockValue(String rawContent) {
        try {
            return Optional.of(rawContent.stripIndent().translateEscapes());
        } catch (IllegalArgumentException invalidEscape) {
            // A text block with a Java format escape the JDK cannot translate is no workflow fixture.
            return Optional.empty();
        }
    }

    private static List<Path> files(Path directory, String suffix) {
        List<Path> files = new ArrayList<>();
        try {
            Files.walkFileTree(REPOSITORY_ROOT.resolve(directory), new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    if (attributes.isRegularFile() && file.toString().endsWith(suffix)) {
                        files.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        files.sort(null);
        return files;
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Optional<String> readUtf8(Path file) {
        try {
            return Optional.of(Files.readString(file));
        } catch (CharacterCodingException binarySeed) {
            return Optional.empty();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String abbreviate(String yaml) {
        String oneLine = yaml.replace("\n", "\\n");
        return oneLine.length() <= MAX_CASE_NAME_LENGTH ? oneLine : oneLine.substring(0, MAX_CASE_NAME_LENGTH) + "...";
    }
}
