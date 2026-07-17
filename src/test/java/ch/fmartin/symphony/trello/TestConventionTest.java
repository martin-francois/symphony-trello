package ch.fmartin.symphony.trello;

import static com.google.common.base.Preconditions.checkState;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class TestConventionTest {
    private static final TypeReference<LinkedHashMap<String, Object>> JSON_OBJECT = new TypeReference<>() {};
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern SIMPLE_MANUAL_MOCK = Pattern.compile(
            "\\b(new\\s+AgentRunner\\s*\\(\\)|extends\\s+(SymphonyOrchestrator|CodexAppServerClient)|implements\\s+AgentRunner)");
    private static final Set<String> TEST_ANNOTATIONS = Set.of("FuzzTest", "ParameterizedTest", "Test");

    @Test
    void methodsUseGivenWhenThenSections() throws IOException {
        // given
        List<String> violations = new ArrayList<>();

        // when
        try (Stream<Path> files = Files.walk(Path.of("src/test/java"))) {
            for (Path file :
                    files.filter(path -> path.toString().endsWith(".java")).toList()) {
                collectTestSectionViolations(file, violations);
            }
        }

        // then
        assertThat(violations)
                .as(
                        "JUnit test methods should use // given, // when, and // then sections with blank lines between sections:%n%s",
                        String.join(System.lineSeparator(), violations))
                .isEmpty();
    }

    @Test
    void methodsUseGivenWhenThenSectionsForWrappedFuzzSignatures(@TempDir Path tempDir) throws IOException {
        // given
        Path source = tempDir.resolve("WrappedFuzzSignatureTest.java");
        List<String> fixtureLines = List.of(
                "class WrappedFuzzSignatureTest {",
                "    @FuzzTest",
                "    @SuppressWarnings(",
                "            value = \"fixture }\")",
                "    void fuzz(",
                "            @NotNull String input) {",
                "        // " + "when",
                "        input.length();",
                "",
                "        // " + "then",
                "        input.isBlank();",
                "    }",
                "}");
        Files.writeString(source, String.join(System.lineSeparator(), fixtureLines));

        // when
        List<String> violations = testSectionViolations(source);

        // then
        assertThat(violations).containsExactly(source + ":6: expected // given, // when, and // then in order");
    }

    @Test
    void compilerPositionsIgnoreJavaLexicalBraces(@TempDir Path tempDir) throws IOException {
        // given
        Path source = tempDir.resolve("LexicalBracesTest.java");
        Files.writeString(
                source,
                """
                final class LexicalBracesTest {
                    @Test
                    void conventionalTest() {
                        // %s
                        String open = "{not valid json";
                        String close = "early closer }";
                        String escaped = "a\\\"b{";
                        String text = \"""
                                {"boards":[{}]}
                                extra } closer
                                \""";
                        char brace = '{';
                        // dangling }
                        /* { block comment } */

                        // %s
                        Runnable nested = () -> { open.length(); };

                        // %s
                        assertThat(text + close + escaped + brace + nested).isNotEmpty();
                    }

                    @Test
                    void missingSections() {
                        assertThat(1).isEqualTo(1);
                    }
                }
                """
                        .formatted("given", "when", "then"));

        // when
        List<String> violations = testSectionViolations(source);

        // then
        assertThat(violations).containsExactly(source + ":24: expected // given, // when, and // then in order");
    }

    @Test
    void sectionsIgnoreMarkersInsideTextBlocksAndBlockComments(@TempDir Path tempDir) throws IOException {
        // given
        Path source = tempDir.resolve("FakeSectionMarkersTest.java");
        Files.writeString(
                source,
                """
                final class FakeSectionMarkersTest {
                    @Test
                    void fakeSections() {
                        String text = \"""
                                // %s
                                // %s
                                // %s
                                \""";
                        /*
                         // %s
                         // %s
                         // %s
                         */
                        assertThat(text).isNotBlank();
                    }
                }
                """
                        .formatted("given", "when", "then", "given", "when", "then"));

        // when
        List<String> violations = testSectionViolations(source);

        // then
        assertThat(violations).containsExactly(source + ":3: expected // given, // when, and // then in order");
    }

    @Test
    void compilerPositionsPreserveBlankLineViolationNumbers(@TempDir Path tempDir) throws IOException {
        // given
        Path source = tempDir.resolve("BlankLineTest.java");
        Files.writeString(
                source,
                """
                final class BlankLineTest {
                    @Test
                    void missingBlankLine() {
                        // %s
                        int value = 1;
                        // %s
                        value++;

                        // %s
                        assertThat(value).isEqualTo(2);
                    }
                }
                """
                        .formatted("given", "when", "then"));

        // when
        List<String> violations = testSectionViolations(source);

        // then
        assertThat(violations).containsExactly(source + ":6: expected a blank line before // when");
    }

    @Test
    void compilerParseErrorsIncludeTheFixtureAndDiagnosticLine(@TempDir Path tempDir) throws IOException {
        // given
        Path source = tempDir.resolve("BrokenTest.java");
        Files.writeString(
                source,
                """
                final class BrokenTest {
                    @Test
                    void broken() {
                        String value = ;
                    }
                }
                """);

        // when
        Throwable thrown = catchThrowable(() -> testSectionViolations(source));

        // then
        assertThat(thrown)
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Could not parse %s", source)
                .hasMessageContaining("line 4:");
    }

    @Test
    void simpleMocksUseMockitoInsteadOfManualTestDoubles() throws IOException {
        // given
        List<String> violations = new ArrayList<>();

        // when
        try (Stream<Path> files = Files.walk(Path.of("src/test/java"))) {
            for (Path file :
                    files.filter(path -> path.toString().endsWith(".java")).toList()) {
                collectSimpleManualMockViolations(file, violations);
            }
        }

        // then
        assertThat(violations)
                .as(
                        "Use Mockito for simple mocks. Purpose-built fakes for stateful external boundaries are still allowed:%n%s",
                        String.join(System.lineSeparator(), violations))
                .isEmpty();
    }

    @Test
    void tesslJsonPinsJavaStyleSkills() throws IOException {
        // given
        Map<String, Object> tesslJson = JSON.readValue(Path.of("tessl.json").toFile(), JSON_OBJECT);

        // when
        Map<String, Object> dependencies = objectMap(tesslJson, "dependencies");

        // then
        assertThat(version(dependencies, "martinfrancois/java-optionals")).isEqualTo("1.0.0");
        assertThat(version(dependencies, "martinfrancois/java-streams")).isEqualTo("1.2.0");
    }

    @Test
    void pomProvidesJSpecifyAnnotationsToProductionSources() throws IOException {
        // given
        String pom = Files.readString(Path.of("pom.xml"));

        // when
        String dependency =
                """
                <dependency>
                      <groupId>org.jspecify</groupId>
                      <artifactId>jspecify</artifactId>
                      <version>${jspecify.version}</version>
                    </dependency>
                """
                        .stripIndent()
                        .trim();

        // then
        assertThat(pom).contains("<jspecify.version>1.0.0</jspecify.version>");
        assertThat(pom).contains(dependency);
        assertThat(pom).doesNotContain("<artifactId>jspecify</artifactId>%n      <scope>test</scope>".formatted());
    }

    @Test
    void mavenTestForksAuthorizeJazzerInstrumentationOnJava25() throws IOException {
        // given
        String pom = Files.readString(Path.of("pom.xml"));
        String permissions =
                "<test.jvm.instrumentation.args>-XX:+EnableDynamicAgentLoading --enable-native-access=ALL-UNNAMED</test.jvm.instrumentation.args>";
        String testForkArguments =
                "<argLine>@{argLine} ${test.jvm.instrumentation.args} -Xshare:off -javaagent:${org.mockito:mockito-core:jar}</argLine>";

        // when
        String surefirePlugin = mavenPluginBlock(pom, "maven-surefire-plugin");
        String failsafePlugin = mavenPluginBlock(pom, "maven-failsafe-plugin");

        // then
        assertThat(pom).containsOnlyOnce(permissions);
        assertThat(surefirePlugin)
                .as("Surefire should use the explicit Java 25 instrumentation permissions")
                .containsOnlyOnce(testForkArguments);
        assertThat(failsafePlugin)
                .as("Failsafe should use the explicit Java 25 instrumentation permissions")
                .containsOnlyOnce(testForkArguments);
    }

    private static String mavenPluginBlock(String pom, String artifactId) {
        String artifact = "<artifactId>" + artifactId + "</artifactId>";
        int artifactStart = pom.indexOf(artifact);
        assertThat(artifactStart)
                .as("Maven plugin %s should be configured", artifactId)
                .isNotNegative();
        int pluginStart = pom.lastIndexOf("<plugin>", artifactStart);
        int pluginEnd = pom.indexOf("</plugin>", artifactStart);
        assertThat(pluginStart)
                .as("Maven plugin %s should have an opening element", artifactId)
                .isNotNegative();
        assertThat(pluginEnd)
                .as("Maven plugin %s should have a closing element", artifactId)
                .isNotNegative();
        return pom.substring(pluginStart, pluginEnd + "</plugin>".length());
    }

    @Test
    void representativeJavaBoundariesUseJSpecifyAnnotations() throws IOException {
        // given
        List<Path> nullMarkedBoundaries = List.of(
                Path.of("src/main/java/ch/fmartin/symphony/trello/repository/RepositorySource.java"),
                Path.of("src/main/java/ch/fmartin/symphony/trello/repository/RepositorySourceSelection.java"),
                Path.of("src/main/java/ch/fmartin/symphony/trello/config/EffectiveConfig.java"),
                Path.of("src/main/java/ch/fmartin/symphony/trello/workflow/WorkflowDefinition.java"),
                Path.of("src/main/java/ch/fmartin/symphony/trello/tracker/CardLookupResult.java"),
                Path.of("src/main/java/ch/fmartin/symphony/trello/domain/BlockerRef.java"));

        // when
        Map<Path, String> sources = sourcesByPath(nullMarkedBoundaries);

        // then
        assertThat(sources).allSatisfy((boundary, source) -> assertThat(source)
                .as("%s should document reviewed nullness defaults".formatted(boundary))
                .contains("org.jspecify.annotations.NullMarked", "@NullMarked"));
        assertThat(sources.get(Path.of("src/main/java/ch/fmartin/symphony/trello/repository/RepositorySource.java")))
                .contains("@Nullable RepositoryIdentity identity", "@Nullable Path path");
        assertThat(sources.get(Path.of("src/main/java/ch/fmartin/symphony/trello/config/EffectiveConfig.java")))
                .contains("record RepositoryConfig(@Nullable String defaultUrl, @Nullable Path defaultPath)");
    }

    private static Map<Path, String> sourcesByPath(List<Path> paths) throws IOException {
        Map<Path, String> sources = new HashMap<>();
        for (Path path : paths) {
            sources.put(path, Files.readString(path));
        }
        return sources;
    }

    private static void collectSimpleManualMockViolations(Path file, List<String> violations) throws IOException {
        List<String> lines = Files.readAllLines(file);
        for (int index = 0; index < lines.size(); index++) {
            var matcher = SIMPLE_MANUAL_MOCK.matcher(lines.get(index));
            if (matcher.find()) {
                violations.add("%s:%d: %s".formatted(file, index + 1, matcher.group()));
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> objectMap(Map<String, Object> root, String key) {
        Object value = root.get(key);
        assertThat(value).as(key).isInstanceOf(Map.class);
        return (Map<String, Object>) value;
    }

    private static String version(Map<String, Object> dependencies, String dependency) {
        return String.valueOf(objectMap(dependencies, dependency).get("version"));
    }

    private static void collectTestSectionViolations(Path file, List<String> violations) throws IOException {
        violations.addAll(testSectionViolations(file));
    }

    static List<String> testSectionViolations(Path file) throws IOException {
        List<String> violations = new ArrayList<>();
        String source = Files.readString(file);
        String normalizedSource = source.replace("\r\n", "\n").replace('\r', '\n');
        List<String> lines = Files.readAllLines(file);
        Map<Integer, String> lineComments = standaloneLineComments(normalizedSource);
        for (TestMethod testMethod : testMethods(file, source)) {
            assertTestSections(file, testMethod, lines, lineComments, violations);
        }
        return violations;
    }

    private static Map<Integer, String> standaloneLineComments(String source) {
        Map<Integer, String> comments = new HashMap<>();
        JavaLexicalState state = JavaLexicalState.CODE;
        int line = 0;
        int lineStart = 0;
        int index = 0;
        while (index < source.length()) {
            char current = source.charAt(index);
            if (current == '\n') {
                line++;
                lineStart = index + 1;
                index++;
                continue;
            }
            switch (state) {
                case CODE -> {
                    if (source.startsWith("//", index)) {
                        int lineEnd = source.indexOf('\n', index + 2);
                        if (lineEnd < 0) {
                            lineEnd = source.length();
                        }
                        if (source.substring(lineStart, index).isBlank()) {
                            comments.put(
                                    line, source.substring(index + 2, lineEnd).trim());
                        }
                        index = lineEnd;
                    } else if (source.startsWith("/*", index)) {
                        state = JavaLexicalState.BLOCK_COMMENT;
                        index += 2;
                    } else if (source.startsWith("\"\"\"", index)) {
                        state = JavaLexicalState.TEXT_BLOCK;
                        index += 3;
                    } else if (current == '"') {
                        state = JavaLexicalState.STRING;
                        index++;
                    } else if (current == '\'') {
                        state = JavaLexicalState.CHARACTER;
                        index++;
                    } else {
                        index++;
                    }
                }
                case BLOCK_COMMENT -> {
                    if (source.startsWith("*/", index)) {
                        state = JavaLexicalState.CODE;
                        index += 2;
                    } else {
                        index++;
                    }
                }
                case STRING -> {
                    if (current == '\\') {
                        index = Math.min(source.length(), index + 2);
                    } else {
                        if (current == '"') {
                            state = JavaLexicalState.CODE;
                        }
                        index++;
                    }
                }
                case CHARACTER -> {
                    if (current == '\\') {
                        index = Math.min(source.length(), index + 2);
                    } else {
                        if (current == '\'') {
                            state = JavaLexicalState.CODE;
                        }
                        index++;
                    }
                }
                case TEXT_BLOCK -> {
                    if (source.startsWith("\"\"\"", index) && !isEscaped(source, index)) {
                        state = JavaLexicalState.CODE;
                        index += 3;
                    } else {
                        index++;
                    }
                }
            }
        }
        return Map.copyOf(comments);
    }

    private static boolean isEscaped(String source, int index) {
        int backslashes = 0;
        for (int current = index - 1; current >= 0 && source.charAt(current) == '\\'; current--) {
            backslashes++;
        }
        return backslashes % 2 != 0;
    }

    private static List<TestMethod> testMethods(Path file, String source) throws IOException {
        JavaCompiler compiler =
                Objects.requireNonNull(ToolProvider.getSystemJavaCompiler(), "Test convention checks require a JDK");
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager fileManager =
                compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            JavaFileObject sourceFile = sourceFile(file, source);
            JavacTask task = (JavacTask)
                    compiler.getTask(null, fileManager, diagnostics, List.of("-proc:none"), null, List.of(sourceFile));
            List<TestMethod> methods = new ArrayList<>();
            for (CompilationUnitTree unit : task.parse()) {
                collectTestMethods(unit, Trees.instance(task), methods);
            }
            rejectParseErrors(file, diagnostics);
            return List.copyOf(methods);
        }
    }

    private static JavaFileObject sourceFile(Path file, String source) {
        return new SimpleJavaFileObject(file.toUri(), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return source;
            }
        };
    }

    private static void collectTestMethods(CompilationUnitTree unit, Trees trees, List<TestMethod> methods) {
        var positions = trees.getSourcePositions();
        new TreePathScanner<Void, Void>() {
            @Override
            public Void visitMethod(MethodTree method, Void unused) {
                if (isTestMethod(method) && method.getBody() != null) {
                    long start = positions.getStartPosition(unit, method.getBody());
                    long end = positions.getEndPosition(unit, method.getBody());
                    checkState(
                            start != Diagnostic.NOPOS && end != Diagnostic.NOPOS,
                            "Compiler did not provide source positions for %s",
                            method.getName());
                    methods.add(new TestMethod(lineIndex(unit, start), lineIndex(unit, end - 1)));
                }
                return super.visitMethod(method, null);
            }
        }.scan(unit, null);
    }

    private static boolean isTestMethod(MethodTree method) {
        for (AnnotationTree annotation : method.getModifiers().getAnnotations()) {
            if (TEST_ANNOTATIONS.contains(simpleAnnotationName(annotation))) {
                return true;
            }
        }
        return false;
    }

    private static String simpleAnnotationName(AnnotationTree annotation) {
        String name = annotation.getAnnotationType().toString();
        int separator = name.lastIndexOf('.');
        return separator < 0 ? name : name.substring(separator + 1);
    }

    private static int lineIndex(CompilationUnitTree unit, long position) {
        return Math.toIntExact(unit.getLineMap().getLineNumber(position) - 1);
    }

    private static void rejectParseErrors(Path file, DiagnosticCollector<JavaFileObject> diagnostics)
            throws IOException {
        List<String> errors = new ArrayList<>();
        for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
            if (diagnostic.getKind() == Diagnostic.Kind.ERROR) {
                errors.add("line " + diagnostic.getLineNumber() + ": " + diagnostic.getMessage(Locale.ROOT));
            }
        }
        if (!errors.isEmpty()) {
            throw new IOException("Could not parse " + file + ": " + String.join("; ", errors));
        }
    }

    private static void assertTestSections(
            Path file,
            TestMethod method,
            List<String> lines,
            Map<Integer, String> lineComments,
            List<String> violations) {
        int firstBodyLine = method.startLine() + 1;
        int given = lineOfMarker(lineComments, firstBodyLine, method.endLine(), "given");
        int when = lineOfMarker(lineComments, firstBodyLine, method.endLine(), "when");
        int then = lineOfMarker(lineComments, firstBodyLine, method.endLine(), "then");
        if (given < 0 || when < 0 || then < 0 || !(given < when && when < then)) {
            violations.add(
                    "%s:%d: expected // given, // when, and // then in order".formatted(file, method.startLine() + 1));
            return;
        }
        if (!lines.get(when - 1).isBlank()) {
            violations.add("%s:%d: expected a blank line before // when".formatted(file, when + 1));
        }
        if (!lines.get(then - 1).isBlank()) {
            violations.add("%s:%d: expected a blank line before // then".formatted(file, then + 1));
        }
    }

    private static int lineOfMarker(Map<Integer, String> lineComments, int firstBodyLine, int endLine, String marker) {
        for (int line = firstBodyLine; line < endLine; line++) {
            if (marker.equals(lineComments.get(line))) {
                return line;
            }
        }
        return -1;
    }

    private enum JavaLexicalState {
        CODE,
        BLOCK_COMMENT,
        STRING,
        CHARACTER,
        TEXT_BLOCK
    }

    private record TestMethod(int startLine, int endLine) {}
}
