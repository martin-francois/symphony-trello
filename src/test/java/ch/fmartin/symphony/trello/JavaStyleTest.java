package ch.fmartin.symphony.trello;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class JavaStyleTest {
    private static final Pattern INLINE_FULLY_QUALIFIED_TYPE = Pattern.compile(
            "\\b(?:java|javax|jakarta|com|org|io)\\.(?:[a-z_][A-Za-z0-9_]*\\.)+[A-Z][A-Za-z0-9_]*(?:\\.[A-Z][A-Za-z0-9_]*)*");
    private static final Pattern SIMPLE_MANUAL_MOCK = Pattern.compile(
            "\\b(new\\s+AgentRunner\\s*\\(\\)|extends\\s+(SymphonyOrchestrator|CodexAppServerClient)|implements\\s+AgentRunner)");
    private static final Pattern TEST_ANNOTATION = Pattern.compile("^\\s*@(Test|ParameterizedTest)\\b");
    private static final Pattern ADR_DATE = Pattern.compile("^date: \\d{4}-\\d{2}-\\d{2}$");
    private static final List<String> ADR_METADATA =
            List.of("status:", "date:", "decision-makers:", "consulted:", "informed:");
    private static final List<String> ADR_HEADINGS = List.of(
            "## Context and Problem Statement",
            "## Decision Drivers",
            "## Considered Options",
            "## Decision Outcome",
            "### Consequences",
            "### Confirmation",
            "## Pros and Cons of the Options",
            "## More Information");
    private static final List<String> ADR_TEMPLATE_PLACEHOLDERS = List.of(
            "{short title",
            "{Describe",
            "{decision driver",
            "{title of option",
            "{justification",
            "{positive consequence",
            "{negative consequence",
            "{Describe how",
            "{argument",
            "{You might want",
            "… <!--");

    @Test
    void inlineFullyQualifiedTypeReferencesAreNotUsed() throws IOException {
        // given
        List<String> violations = new ArrayList<>();

        // when
        for (Path sourceRoot : List.of(Path.of("src/main/java"), Path.of("src/test/java"))) {
            try (Stream<Path> files = Files.walk(sourceRoot)) {
                for (Path file :
                        files.filter(path -> path.toString().endsWith(".java")).toList()) {
                    collectViolations(file, violations);
                }
            }
        }

        // then
        assertThat(violations)
                .as(
                        "Use imports instead of inline fully qualified type references:%n%s",
                        String.join(System.lineSeparator(), violations))
                .isEmpty();
    }

    @Test
    void testMethodsUseGivenWhenThenSections() throws IOException {
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
    void architecturalDecisionRecordsUseMadrTemplateShape() throws IOException {
        // given
        List<String> violations = new ArrayList<>();

        // when
        try (Stream<Path> files = Files.list(Path.of("docs/adr"))) {
            for (Path file : files.filter(path -> path.toString().endsWith(".md"))
                    .sorted()
                    .toList()) {
                collectAdrShapeViolations(file, violations);
            }
        }

        // then
        assertThat(violations)
                .as(
                        "ADRs must follow the official MADR template shape with filled project-specific sections:%n%s",
                        String.join(System.lineSeparator(), violations))
                .isEmpty();
    }

    private static void collectViolations(Path file, List<String> violations) throws IOException {
        List<String> lines = Files.readAllLines(file);
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            String trimmed = line.stripLeading();
            if (trimmed.startsWith("package ")
                    || trimmed.startsWith("import ")
                    || trimmed.startsWith("//")
                    || trimmed.startsWith("*")
                    || trimmed.startsWith("/*")) {
                continue;
            }
            var matcher = INLINE_FULLY_QUALIFIED_TYPE.matcher(line);
            while (matcher.find()) {
                violations.add("%s:%d: %s".formatted(file, index + 1, matcher.group()));
            }
        }
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

    private static void collectTestSectionViolations(Path file, List<String> violations) throws IOException {
        List<String> lines = Files.readAllLines(file);
        for (int index = 0; index < lines.size(); index++) {
            if (!TEST_ANNOTATION.matcher(lines.get(index)).find()) {
                continue;
            }
            int methodStart = findMethodStart(lines, index + 1);
            int methodEnd = findMethodEnd(lines, methodStart);
            if (methodStart < 0 || methodEnd < 0) {
                violations.add("%s:%d: could not parse test method".formatted(file, index + 1));
                continue;
            }
            assertTestSections(file, methodStart, lines.subList(methodStart + 1, methodEnd), violations);
            index = methodEnd;
        }
    }

    private static void collectAdrShapeViolations(Path file, List<String> violations) throws IOException {
        List<String> lines = Files.readAllLines(file);
        if (lines.size() < 20) {
            violations.add("%s: ADR is too short to contain the MADR template sections".formatted(file));
            return;
        }
        assertAdrMetadata(file, lines, violations);
        assertAdrHeadings(file, lines, violations);
        assertNoAdrTemplatePlaceholders(file, lines, violations);
    }

    private static void assertAdrMetadata(Path file, List<String> lines, List<String> violations) {
        if (!lines.getFirst().equals("---")) {
            violations.add("%s:1: expected YAML front matter start".formatted(file));
            return;
        }
        int end = lines.subList(1, lines.size()).indexOf("---") + 1;
        if (end <= 0) {
            violations.add("%s: expected YAML front matter end".formatted(file));
            return;
        }
        Map<String, String> metadata = new LinkedHashMap<>();
        for (String line : lines.subList(1, end)) {
            int separator = line.indexOf(':');
            if (separator > 0) {
                metadata.put(line.substring(0, separator + 1), line);
            }
        }
        for (String key : ADR_METADATA) {
            String value = metadata.get(key);
            if (value == null || value.equals(key)) {
                violations.add("%s: expected filled ADR metadata field %s".formatted(file, key));
            }
        }
        String status = metadata.get("status:");
        if (status != null && !status.equals("status: accepted")) {
            violations.add("%s: expected accepted ADR status, found %s".formatted(file, status));
        }
        String date = metadata.get("date:");
        if (date != null && !ADR_DATE.matcher(date).matches()) {
            violations.add("%s: expected ADR date as YYYY-MM-DD, found %s".formatted(file, date));
        }
    }

    private static void assertAdrHeadings(Path file, List<String> lines, List<String> violations) {
        List<String> headings =
                lines.stream().filter(line -> line.startsWith("#")).toList();
        long titles = headings.stream()
                .filter(line -> line.startsWith("# ") && !line.startsWith("##"))
                .count();
        if (titles != 1) {
            violations.add("%s: expected exactly one ADR title heading".formatted(file));
        }
        int previous = -1;
        for (String heading : ADR_HEADINGS) {
            int index = lines.indexOf(heading);
            if (index < 0) {
                violations.add("%s: expected ADR heading %s".formatted(file, heading));
                continue;
            }
            if (index <= previous) {
                violations.add("%s:%d: ADR heading is out of order: %s".formatted(file, index + 1, heading));
            }
            previous = index;
        }
    }

    private static void assertNoAdrTemplatePlaceholders(Path file, List<String> lines, List<String> violations) {
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            for (String placeholder : ADR_TEMPLATE_PLACEHOLDERS) {
                if (line.contains(placeholder)) {
                    violations.add(
                            "%s:%d: remove MADR template placeholder %s".formatted(file, index + 1, placeholder));
                }
            }
        }
    }

    private static int findMethodStart(List<String> lines, int start) {
        for (int index = start; index < lines.size(); index++) {
            if (lines.get(index).contains("{")) {
                return index;
            }
        }
        return -1;
    }

    private static int findMethodEnd(List<String> lines, int methodStart) {
        if (methodStart < 0) {
            return -1;
        }
        int depth = 0;
        for (int index = methodStart; index < lines.size(); index++) {
            String line = lines.get(index);
            depth += count(line, '{');
            depth -= count(line, '}');
            if (depth == 0) {
                return index;
            }
        }
        return -1;
    }

    private static int count(String line, char expected) {
        int count = 0;
        for (int index = 0; index < line.length(); index++) {
            if (line.charAt(index) == expected) {
                count++;
            }
        }
        return count;
    }

    private static void assertTestSections(Path file, int methodStart, List<String> body, List<String> violations) {
        int given = indexOfMarker(body, "// given");
        int when = indexOfMarker(body, "// when");
        int then = indexOfMarker(body, "// then");
        if (given < 0 || when < 0 || then < 0 || !(given < when && when < then)) {
            violations.add("%s:%d: expected // given, // when, and // then in order".formatted(file, methodStart + 1));
            return;
        }
        if (when == 0 || !body.get(when - 1).isBlank()) {
            violations.add("%s:%d: expected a blank line before // when".formatted(file, methodStart + when + 2));
        }
        if (then == 0 || !body.get(then - 1).isBlank()) {
            violations.add("%s:%d: expected a blank line before // then".formatted(file, methodStart + then + 2));
        }
    }

    private static int indexOfMarker(List<String> body, String marker) {
        for (int index = 0; index < body.size(); index++) {
            if (body.get(index).trim().equals(marker)) {
                return index;
            }
        }
        return -1;
    }
}
