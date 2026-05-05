package com.openai.symphony;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class JavaStyleTest {
    private static final Pattern INLINE_FULLY_QUALIFIED_TYPE = Pattern.compile(
            "\\b(?:java|javax|jakarta|com|org|io)\\.(?:[a-z_][A-Za-z0-9_]*\\.)+[A-Z][A-Za-z0-9_]*(?:\\.[A-Z][A-Za-z0-9_]*)*");

    @Test
    void inlineFullyQualifiedTypeReferencesAreNotUsed() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path sourceRoot : List.of(Path.of("src/main/java"), Path.of("src/test/java"))) {
            try (Stream<Path> files = Files.walk(sourceRoot)) {
                for (Path file :
                        files.filter(path -> path.toString().endsWith(".java")).toList()) {
                    collectViolations(file, violations);
                }
            }
        }

        assertThat(violations)
                .as(
                        "Use imports instead of inline fully qualified type references:%n%s",
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
}
