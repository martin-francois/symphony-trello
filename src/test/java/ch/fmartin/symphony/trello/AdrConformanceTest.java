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

class AdrConformanceTest {
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
}
