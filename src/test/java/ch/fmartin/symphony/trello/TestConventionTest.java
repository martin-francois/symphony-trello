package ch.fmartin.symphony.trello;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

final class TestConventionTest {
    private static final Pattern SIMPLE_MANUAL_MOCK = Pattern.compile(
            "\\b(new\\s+AgentRunner\\s*\\(\\)|extends\\s+(SymphonyOrchestrator|CodexAppServerClient)|implements\\s+AgentRunner)");
    private static final Pattern TEST_ANNOTATION = Pattern.compile("^\\s*@(Test|ParameterizedTest)\\b");

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
        int index = 0;
        while (index < lines.size()) {
            if (!TEST_ANNOTATION.matcher(lines.get(index)).find()) {
                index++;
                continue;
            }
            int methodStart = findMethodStart(lines, index + 1);
            int methodEnd = findMethodEnd(lines, methodStart);
            if (methodStart < 0 || methodEnd < 0) {
                violations.add("%s:%d: could not parse test method".formatted(file, index + 1));
                index++;
                continue;
            }
            List<String> body = methodEnd > methodStart ? lines.subList(methodStart + 1, methodEnd) : List.of();
            assertTestSections(file, methodStart, body, violations);
            index = methodEnd;
            index++;
        }
    }

    private static int findMethodStart(List<String> lines, int start) {
        for (int index = start; index < lines.size(); index++) {
            String line = lines.get(index);
            if (line.stripLeading().startsWith("@")) {
                continue;
            }
            if (line.contains("{")) {
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
