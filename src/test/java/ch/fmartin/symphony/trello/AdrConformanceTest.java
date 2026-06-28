package ch.fmartin.symphony.trello;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

final class AdrConformanceTest {
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final TypeReference<LinkedHashMap<String, Object>> YAML_METADATA = new TypeReference<>() {};
    private static final Pattern ADR_DATE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");
    private static final Pattern ADR_FILE = Pattern.compile("^(\\d{4})-.+\\.md$");
    private static final Pattern SUPERSEDED_STATUS =
            Pattern.compile("^superseded by \\[ADR \\d{4}]\\(([^)]+\\.md)\\)$");
    private static final List<String> ADR_METADATA =
            List.of("status", "date", "decision-makers", "consulted", "informed");
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
            List<Path> adrFiles = files.filter(path -> path.toString().endsWith(".md"))
                    .sorted()
                    .toList();
            assertUniqueAdrNumbers(adrFiles, violations);
            for (Path file : adrFiles) {
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

    private static void assertUniqueAdrNumbers(List<Path> files, List<String> violations) {
        Map<String, List<Path>> filesByNumber = new LinkedHashMap<>();
        for (Path file : files) {
            String filename = file.getFileName().toString();
            Matcher matcher = ADR_FILE.matcher(filename);
            if (matcher.matches()) {
                filesByNumber
                        .computeIfAbsent(matcher.group(1), ignored -> new ArrayList<>())
                        .add(file);
            }
        }
        for (Map.Entry<String, List<Path>> entry : filesByNumber.entrySet()) {
            if (entry.getValue().size() > 1) {
                violations.add(
                        "ADR number %s is used by multiple files: %s".formatted(entry.getKey(), entry.getValue()));
            }
        }
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
        Map<String, Object> metadata;
        try {
            metadata = YAML.readValue(String.join("\n", lines.subList(1, end)), YAML_METADATA);
        } catch (IOException e) {
            violations.add("%s: invalid ADR YAML metadata: %s".formatted(file, e.getMessage()));
            return;
        }
        for (String key : ADR_METADATA) {
            Object value = metadata.get(key);
            if (metadataValueBlank(value)) {
                violations.add("%s: expected filled ADR metadata field %s".formatted(file, key));
            }
        }
        Object status = metadata.get("status");
        if (status != null) {
            assertAdrStatus(file, String.valueOf(status), violations);
        }
        Object date = metadata.get("date");
        if (date != null && !ADR_DATE.matcher(String.valueOf(date)).matches()) {
            violations.add("%s: expected ADR date as YYYY-MM-DD, found %s".formatted(file, date));
        }
    }

    private static void assertAdrStatus(Path file, String status, List<String> violations) {
        if ("accepted".equals(status)) {
            return;
        }
        Matcher superseded = SUPERSEDED_STATUS.matcher(status);
        if (!superseded.matches()) {
            violations.add(
                    "%s: expected ADR status accepted or MADR superseded-by link, found %s".formatted(file, status));
            return;
        }
        Path supersedingAdr = file.getParent().resolve(superseded.group(1)).normalize();
        if (!Files.isRegularFile(supersedingAdr)) {
            violations.add("%s: superseding ADR does not exist: %s".formatted(file, supersedingAdr));
        }
    }

    private static boolean metadataValueBlank(Object value) {
        return switch (value) {
            case null -> true;
            case String string -> string.isBlank();
            case Collection<?> collection -> collection.isEmpty();
            case Map<?, ?> map -> map.isEmpty();
            default -> false;
        };
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
