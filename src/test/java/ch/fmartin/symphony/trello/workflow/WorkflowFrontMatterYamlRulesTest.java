package ch.fmartin.symphony.trello.workflow;

import static ch.fmartin.symphony.trello.workflow.FrontMatterWorkflows.WORKFLOW;
import static ch.fmartin.symphony.trello.workflow.FrontMatterWorkflows.workflow;
import static ch.fmartin.symphony.trello.workflow.WorkflowFrontMatterYaml.MAX_ALIAS_COPIED_CHARACTERS;
import static ch.fmartin.symphony.trello.workflow.WorkflowFrontMatterYaml.MAX_ALIAS_EXPANDED_NODES;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.assertj.core.api.InstanceOfAssertFactories.LIST;
import static org.junit.jupiter.api.Named.named;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/// The YAML rules of `SPEC.md` section 5.2 for workflow front matter: aliases resolve, merge keys and
/// repeated keys fail with a `workflow_parse_error` that names the key, and front matter without
/// content is an empty config.
final class WorkflowFrontMatterYamlRulesTest {
    private static final String PARSE_ERROR = "workflow_parse_error";
    // The message shows a next-line character in a key as its escape, so the message stays one line.
    private static final String ESCAPED_NEXT_LINE = "\\u0085";
    // Long enough that the character limit, not the node limit, stops the copies.
    private static final String LONG_SCALAR = "x".repeat(1_000);

    private final WorkflowLoader loader = new WorkflowLoader();

    @MethodSource("aliasCases")
    @ParameterizedTest
    void aliasesResolveToTheAnchoredValue(String frontMatter, Map<String, Object> expectedConfig) {
        // given
        byte[] workflow = workflow(frontMatter);

        // when
        WorkflowDefinition loaded = loader.parse(WORKFLOW, workflow);

        // then
        assertThat(loaded.config()).containsExactlyInAnyOrderEntriesOf(expectedConfig);
    }

    private static Stream<Arguments> aliasCases() {
        return Stream.of(
                arguments(named("scalar", "a: &x 5\nb: *x\n"), Map.of("a", 5, "b", 5)),
                arguments(
                        named("block sequence", "a: &x\n  - 1\n  - two\nb: *x\n"),
                        Map.of("a", List.of(1, "two"), "b", List.of(1, "two"))),
                arguments(
                        named("flow sequence", "a: &x [1, 2]\nb: *x\n"),
                        Map.of("a", List.of(1, 2), "b", List.of(1, 2))),
                arguments(
                        named("block mapping", "base: &d\n  t: 5\n  on: yes\nc: *d\n"),
                        Map.of("base", Map.of("t", 5, "on", true), "c", Map.of("t", 5, "on", true))),
                arguments(
                        named("flow mapping", "base: &d {t: 5}\nc: *d\n"),
                        Map.of("base", Map.of("t", 5), "c", Map.of("t", 5))),
                arguments(
                        named("nested mapping value", "base: &d {t: 5}\ncodex:\n  hooks:\n    copy: *d\n"),
                        Map.of("base", Map.of("t", 5), "codex", Map.of("hooks", Map.of("copy", Map.of("t", 5))))),
                arguments(
                        named("block sequence items", "a: &x {t: 1}\nl:\n  - *x\n  - *x\n"),
                        Map.of("a", Map.of("t", 1), "l", List.of(Map.of("t", 1), Map.of("t", 1)))),
                arguments(
                        named("flow sequence items", "a: &x 1\nl: [*x, 2, *x]\n"),
                        Map.of("a", 1, "l", List.of(1, 2, 1))),
                arguments(
                        named("flow mapping value", "a: &x [1]\nc: {k: *x}\n"),
                        Map.of("a", List.of(1), "c", Map.of("k", List.of(1)))),
                arguments(
                        named("alias inside an anchored sequence", "a: &x 1\nb: &y [*x]\nc: *y\n"),
                        Map.of("a", 1, "b", List.of(1), "c", List.of(1))),
                arguments(
                        named("alias inside an anchored mapping", "a: &x 1\nb: &y {k: *x}\nc: *y\n"),
                        Map.of("a", 1, "b", Map.of("k", 1), "c", Map.of("k", 1))),
                arguments(
                        named("anchor redefined later", "a: &x 1\nb: *x\nc: &x 2\nd: *x\n"),
                        Map.of("a", 1, "b", 1, "c", 2, "d", 2)),
                arguments(
                        named(
                                "copy keeps the value an inner alias had where the anchor was defined",
                                "a: &x 1\nb: &y [*x]\nc: &x 2\nd: *y\ne: *x\n"),
                        Map.of("a", 1, "b", List.of(1), "c", 2, "d", List.of(1), "e", 2)),
                arguments(
                        named("anchor nested inside an anchor", "a: &outer {in: &inner [1]}\nb: *inner\nc: *outer\n"),
                        Map.of("a", Map.of("in", List.of(1)), "b", List.of(1), "c", Map.of("in", List.of(1)))),
                arguments(
                        named("anchor redefined inside its own node", "a: &x [&x 1, *x]\nb: *x\n"),
                        Map.of("a", List.of(1, 1), "b", 1)),
                arguments(
                        named(
                                "anchor redefined inside its own node stays redefined after it",
                                "a: &x {k: &x [2]}\nb: *x\n"),
                        Map.of("a", Map.of("k", List.of(2)), "b", List.of(2))),
                arguments(named("alias as a key", "a: &k name\n*k : v\n"), Map.of("a", "name", "name", "v")),
                arguments(named("alias to a quoted scalar", "a: &x '5'\nb: *x\n"), Map.of("a", "5", "b", "5")),
                arguments(named("alias to null", "c:\n  a: &x\n  b: *x\n"), Map.of("c", nullValues("a", "b"))));
    }

    @MethodSource("ruleViolations")
    @ParameterizedTest
    void ruleViolationsFailWithAMessageThatNamesTheKeyAndTheFix(String frontMatter, String expectedMessage) {
        // given
        byte[] workflow = workflow(frontMatter);

        // when
        WorkflowException failure =
                catchThrowableOfType(WorkflowException.class, () -> loader.parse(WORKFLOW, workflow));

        // then
        assertThat(failure)
                .hasMessage(expectedMessage)
                .extracting(WorkflowException::code)
                .isEqualTo(PARSE_ERROR);
    }

    private static Stream<Arguments> ruleViolations() {
        return Stream.of(
                arguments(named("merge key at the top level", "base: &d {t: 5}\n<<: *d\n"), mergeKey("<<", 2)),
                arguments(
                        named("merge key in a block mapping", "base: &d {t: 5}\nc:\n  <<: *d\n"), mergeKey("c.<<", 3)),
                arguments(named("merge key in a flow mapping", "base: &d {t: 5}\nc: {<<: *d}\n"), mergeKey("c.<<", 2)),
                arguments(
                        named("merge key nested two levels", "base: &d {t: 5}\ncodex:\n  hooks:\n    <<: *d\n"),
                        mergeKey("codex.hooks.<<", 4)),
                arguments(
                        named("merge key in a sequence item", "base: &d {t: 5}\nl:\n  - x: 1\n  - <<: *d\n"),
                        mergeKey("l[1].<<", 4)),
                arguments(named("merge key with an inline mapping", "c:\n  <<: {t: 5}\n"), mergeKey("c.<<", 2)),
                arguments(
                        named("merge key with a list of mappings", "a: &a {x: 1}\nb: &b {y: 2}\nc:\n  <<: [*a, *b]\n"),
                        mergeKey("c.<<", 4)),
                arguments(named("merge key after other keys", "c:\n  t: 1\n  <<: {t: 5}\n"), mergeKey("c.<<", 3)),
                arguments(named("merge key with an explicit tag", "c:\n  !!merge <<: {t: 5}\n"), mergeKey("c.<<", 2)),
                arguments(
                        named("merge key inside an anchored mapping", "base: &d {<<: {t: 5}}\nc: *d\n"),
                        mergeKey("base.<<", 1)),
                arguments(
                        named("merge key introduced through an alias", "k: &k <<\nc:\n  *k : {t: 5}\n"),
                        mergeKey("c.*k", 3)),
                arguments(
                        named("merge key under a key that an alias supplies", "a: &k v\n*k : {<<: {t: 5}}\n"),
                        mergeKey("*k.<<", 2)),
                arguments(named("duplicate at the top level", "a: 1\nb: 2\na: 3\n"), duplicateKey("a", 3)),
                arguments(
                        named("duplicate in a block mapping", "codex:\n  x: 1\n  x: 2\n"), duplicateKey("codex.x", 3)),
                arguments(named("duplicate in a flow mapping", "c: {x: 1, x: 2}\n"), duplicateKey("c.x", 1)),
                arguments(
                        named("duplicate nested two levels", "codex:\n  hooks:\n    x: 1\n    x: 2\n"),
                        duplicateKey("codex.hooks.x", 4)),
                arguments(
                        named("duplicate in a sequence item", "l:\n  - {x: 1}\n  - {x: 1, x: 2}\n"),
                        duplicateKey("l[1].x", 3)),
                arguments(
                        named("duplicate that differs only in double quotes", "a: 1\n\"a\": 2\n"),
                        duplicateKey("a", 2)),
                arguments(
                        named("duplicate that differs only in single quotes", "'a': 1\na: 2\n"), duplicateKey("a", 2)),
                arguments(named("number key and quoted number key", "1: a\n\"1\": b\n"), duplicateKey("1", 2)),
                arguments(
                        named("duplicate introduced through an alias", "k: &k a\na: 1\n*k : 2\n"),
                        duplicateKey("*k", 3)),
                arguments(
                        named("duplicate of an anchored key through its alias", "&k x: 1\n*k : 2\n"),
                        duplicateKey("*k", 2)),
                arguments(
                        named("duplicate under a key that an alias supplies", "a: &k v\n*k : {y: 1, y: 2}\n"),
                        duplicateKey("*k.y", 2)),
                arguments(named("duplicate with different value types", "a: {x: 1}\na: [1]\n"), duplicateKey("a", 2)),
                arguments(named("undefined alias", "a: *missing\n"), undefinedAlias("missing", 1)),
                arguments(named("alias before its anchor", "a: *x\nb: &x 1\n"), undefinedAlias("x", 1)),
                arguments(
                        named("alias inside the node its anchor marks", "a: &x\n  b: *x\n"),
                        aliasInsideItsAnchor("x", 2)),
                arguments(
                        named("alias inside a node that redefines an earlier anchor", "a: &x 1\nb: &x [*x]\n"),
                        aliasInsideItsAnchor("x", 2)),
                arguments(
                        named("alias inside the flow sequence its anchor marks", "a: &x [1, *x]\n"),
                        aliasInsideItsAnchor("x", 1)),
                arguments(
                        named("repeated key with a line break", "\"a\\nb\": 1\n\"a\\nb\": 2\n"),
                        duplicateKey("a\\nb", 2)),
                arguments(
                        named("repeated nested key with a next-line character", "c:\n  \"a\\Nb\": 1\n  \"a\\Nb\": 2\n"),
                        duplicateKey("c.a" + ESCAPED_NEXT_LINE + "b", 3)),
                arguments(
                        named("merge key under a key with a line break", "\"a\\nb\":\n  <<: {t: 5}\n"),
                        mergeKey("a\\nb.<<", 2)));
    }

    @Test
    void twoQuotedMergeKeysInOneMappingAreDuplicates() {
        // given
        byte[] workflow = workflow("a:\n  \"<<\": 1\n  '<<': 2\nb: \"<<\"\nc: !!str <<\n");

        // when
        WorkflowException failure =
                catchThrowableOfType(WorkflowException.class, () -> loader.parse(WORKFLOW, workflow));

        // then
        assertThat(failure)
                .as("two quoted \"<<\" keys in one mapping are the same string key")
                .hasMessage(duplicateKey("a.<<", 3));
    }

    @Test
    void quotedMergeKeyAndPlainMergeScalarValuesLoadAsStrings() {
        // given
        byte[] workflow = workflow("a:\n  \"<<\": 1\nb: \"<<\"\nc: <<\nd: !!str <<\n");

        // when
        WorkflowDefinition loaded = loader.parse(WORKFLOW, workflow);

        // then
        assertThat(loaded.config())
                .containsExactlyInAnyOrderEntriesOf(Map.of("a", Map.of("<<", 1), "b", "<<", "c", "<<", "d", "<<"));
    }

    @Test
    void aMergeKeyWithTheNonSpecificTagLoadsAsAStringKey() {
        // given
        byte[] workflow = workflow("c:\n  ! <<: {a: 1}\n");

        // when
        WorkflowDefinition loaded = loader.parse(WORKFLOW, workflow);

        // then
        assertThat(loaded.config())
                .as("YAML reads a scalar tagged ! as a string, so ! << is the key \"<<\"")
                .containsExactlyInAnyOrderEntriesOf(Map.of("c", Map.of("<<", Map.of("a", 1))));
    }

    @Test
    void sameKeyInDifferentMappingsIsNotADuplicate() {
        // given
        byte[] workflow = workflow("x: {x: 1}\ny: {x: 2}\nl: [{x: 1}, {x: 2}]\nd: &d {x: 3}\ncopies: [*d, *d]\n");

        // when
        WorkflowDefinition loaded = loader.parse(WORKFLOW, workflow);

        // then
        assertThat(loaded.config())
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        "x", Map.of("x", 1),
                        "y", Map.of("x", 2),
                        "l", List.of(Map.of("x", 1), Map.of("x", 2)),
                        "d", Map.of("x", 3),
                        "copies", List.of(Map.of("x", 3), Map.of("x", 3))));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "---\n---\nBody\n",
                "---\n# only a comment\n---\nBody\n",
                "---\n  # indented comment\n\n# another\n---\nBody\n",
                "---\n   \n \n\n---\nBody\n",
                "---\n\n---\nBody\n",
                "---\nnull\n---\nBody\n",
                "---\n~\n---\nBody\n",
                "---\r\n# comment with CRLF\r\n---\r\nBody\r\n"
            })
    void frontMatterWithoutContentIsAnEmptyConfig(String markdown) {
        // given
        byte[] workflow = markdown.getBytes(UTF_8);

        // when
        WorkflowDefinition loaded = loader.parse(WORKFLOW, workflow);

        // then
        assertThat(loaded.config()).isEmpty();
        assertThat(loaded.promptTemplate()).isEqualTo("Body");
    }

    @Test
    void aliasesMayExpandToExactlyTheNodeLimit() {
        // given
        byte[] workflow = workflow(scalarAliases(MAX_ALIAS_EXPANDED_NODES));

        // when
        WorkflowDefinition loaded = loader.parse(WORKFLOW, workflow);

        // then
        assertThat(loaded.config().get("copies"))
                .asInstanceOf(LIST)
                .hasSize(MAX_ALIAS_EXPANDED_NODES)
                .containsOnly("x");
    }

    @Test
    void aliasesThatExpandBeyondTheNodeLimitFail() {
        // given
        int copies = MAX_ALIAS_EXPANDED_NODES + 1;
        byte[] workflow = workflow(scalarAliases(copies));

        // when
        WorkflowException failure =
                catchThrowableOfType(WorkflowException.class, () -> loader.parse(WORKFLOW, workflow));

        // then
        assertThat(failure)
                .hasMessage(aliasLimit("s", 2))
                .extracting(WorkflowException::code)
                .isEqualTo(PARSE_ERROR);
    }

    @MethodSource("collectionAliasesWithinLimit")
    @ParameterizedTest
    void collectionAliasesLoadUpToTheNodeLimit(String anchored, int copiesWithinLimit) {
        // given
        byte[] workflow = workflow(listCopies(anchored, copiesWithinLimit));

        // when
        WorkflowDefinition loaded = loader.parse(WORKFLOW, workflow);

        // then
        assertThat(loaded.config().get("copies")).asInstanceOf(LIST).hasSize(copiesWithinLimit);
    }

    @MethodSource("collectionAliasCases")
    @ParameterizedTest
    void collectionAliasesFailOneCopyPastTheNodeLimit(String anchored, int copiesWithinLimit, int aliasLine) {
        // given
        byte[] workflow = workflow(listCopies(anchored, copiesWithinLimit + 1));

        // when
        WorkflowException failure =
                catchThrowableOfType(WorkflowException.class, () -> loader.parse(WORKFLOW, workflow));

        // then
        assertThat(failure).hasMessage(aliasLimit("l", aliasLine));
    }

    // The same lists as collectionAliasCases, without the alias line that only a failure reports.
    private static Stream<Arguments> collectionAliasesWithinLimit() {
        return collectionAliasCases().map(WorkflowFrontMatterYamlRulesTest::withoutAliasLine);
    }

    private static Arguments withoutAliasLine(Arguments listAndCopies) {
        Object[] values = listAndCopies.get();
        return arguments(values[0], values[1]);
    }

    private static Stream<Arguments> collectionAliasCases() {
        // A copy of the plain list is 1 sequence node plus 9 scalars. A copy of the list of aliases is
        // 1 sequence node plus the 4 scalars its aliases copied where it was defined, and that
        // definition already used 4 nodes of the limit.
        return Stream.of(
                arguments(
                        named("a list of scalars", "list: &l [1, 2, 3, 4, 5, 6, 7, 8, 9]\n"),
                        MAX_ALIAS_EXPANDED_NODES / 10,
                        2),
                arguments(
                        named("a list of aliases", "s: &s x\nlist: &l [*s, *s, *s, *s]\n"),
                        (MAX_ALIAS_EXPANDED_NODES - 4) / 5,
                        3));
    }

    @Test
    void aScalarThatAnAliasCopiesIntoAnAnchoredNodeCountsInEveryCopyOfIt() {
        // given
        // The definition of the list copies the scalar once, so the last copy of the list passes the limit.
        String anchored = "s: &s " + LONG_SCALAR + "\nlist: &l [*s]\n";
        int copies = MAX_ALIAS_COPIED_CHARACTERS / LONG_SCALAR.length();
        byte[] workflow = workflow(listCopies(anchored, copies));

        // when
        WorkflowException failure =
                catchThrowableOfType(WorkflowException.class, () -> loader.parse(WORKFLOW, workflow));

        // then
        assertThat(failure).hasMessage(characterLimit("l", 3));
    }

    @Test
    void aliasesMayCopyExactlyTheCharacterLimit() {
        // given
        int copies = MAX_ALIAS_COPIED_CHARACTERS / LONG_SCALAR.length();
        byte[] workflow = workflow(longScalarAliases(copies));

        // when
        WorkflowDefinition loaded = loader.parse(WORKFLOW, workflow);

        // then
        assertThat(loaded.config().get("copies"))
                .asInstanceOf(LIST)
                .hasSize(copies)
                .containsOnly(LONG_SCALAR);
    }

    @Test
    void aliasesThatCopyBeyondTheCharacterLimitFail() {
        // given
        int copies = MAX_ALIAS_COPIED_CHARACTERS / LONG_SCALAR.length() + 1;
        byte[] workflow = workflow(longScalarAliases(copies));

        // when
        WorkflowException failure =
                catchThrowableOfType(WorkflowException.class, () -> loader.parse(WORKFLOW, workflow));

        // then
        assertThat(failure)
                .hasMessage(characterLimit("s", 2))
                .extracting(WorkflowException::code)
                .isEqualTo(PARSE_ERROR);
    }

    @ParameterizedTest
    @ValueSource(strings = {"---\n\t\n---\nBody\n", "---\n\t# comment\n---\nBody\n", "---\na: 1\n\t\n---\nBody\n"})
    void aTabThatStartsALineIsAParseError(String markdown) {
        // given
        byte[] workflow = markdown.getBytes(UTF_8);

        // when
        WorkflowException failure =
                catchThrowableOfType(WorkflowException.class, () -> loader.parse(WORKFLOW, workflow));

        // then
        assertThat(failure)
                .as("a tab that starts a line is a YAML syntax error")
                .extracting(WorkflowException::code)
                .isEqualTo(PARSE_ERROR);
    }

    private static String listCopies(String anchored, int copies) {
        return anchored + "copies: [" + "*l, ".repeat(copies) + "]\n";
    }

    private static String longScalarAliases(int copies) {
        return "s: &s " + LONG_SCALAR + "\ncopies: [" + "*s,".repeat(copies - 1) + "*s]\n";
    }

    private static String scalarAliases(int copies) {
        return "s: &s x\ncopies: [" + "*s,".repeat(copies - 1) + "*s]\n";
    }

    private static Map<String, Object> nullValues(String... keys) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (String key : keys) {
            values.put(key, null);
        }
        return values;
    }

    private static String mergeKey(String path, int line) {
        return "Workflow front matter uses a YAML merge key at " + path + " (front matter line " + line
                + "), which is not supported; repeat the keys of the merged mapping there instead";
    }

    private static String undefinedAlias(String alias, int line) {
        return "Workflow front matter uses the alias *" + alias + " (front matter line " + line + ") before an anchor &"
                + alias + " defines it; define the anchor earlier or write the value instead";
    }

    private static String aliasInsideItsAnchor(String alias, int line) {
        return "Workflow front matter uses the alias *" + alias + " (front matter line " + line
                + ") inside the node its anchor &" + alias + " marks; replace the alias with a value";
    }

    private static String duplicateKey(String path, int line) {
        return "Workflow front matter has the key " + path + " more than once (front matter line " + line
                + "); keep one entry and remove the others";
    }

    private static String aliasLimit(String alias, int line) {
        return "Workflow front matter aliases expand to more than " + MAX_ALIAS_EXPANDED_NODES + " YAML nodes (alias *"
                + alias + ", front matter line " + line + "); use fewer aliases or smaller anchored values";
    }

    private static String characterLimit(String alias, int line) {
        return "Workflow front matter aliases copy more than " + MAX_ALIAS_COPIED_CHARACTERS
                + " characters of scalars (alias *" + alias + ", front matter line " + line
                + "); use fewer aliases or shorter anchored values";
    }
}
