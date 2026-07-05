package ch.fmartin.symphony.trello.workflow;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@ApplicationScoped
@NullMarked
public class WorkflowLoader {
    private static final TypeReference<LinkedHashMap<String, Object>> YAML_MAP_TYPE = new TypeReference<>() {};

    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

    public WorkflowDefinition load(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        List<String> lines;
        try {
            lines = Files.readAllLines(absolute);
        } catch (IOException e) {
            throw new WorkflowException("missing_workflow_file", "Workflow file cannot be read: " + absolute, e);
        }

        ParsedMarkdown parsed = splitFrontMatter(lines);
        Map<String, Object> config = parseYamlMap(parsed.frontMatter());
        return new WorkflowDefinition(absolute, config, parsed.body().trim());
    }

    private Map<String, Object> parseYamlMap(@Nullable String frontMatter) {
        if (frontMatter == null) {
            return Map.of();
        }
        try {
            LinkedHashMap<String, Object> parsed = yaml.readValue(frontMatter, YAML_MAP_TYPE);
            if (parsed == null) {
                return Map.of();
            }
            rejectNullTopLevelEntries(parsed);
            return parsed;
        } catch (JsonProcessingException e) {
            if (e.getMessage() != null && e.getMessage().contains("Cannot deserialize")) {
                throw new WorkflowException(
                        "workflow_front_matter_not_a_map", "Workflow front matter must be a YAML map", e);
            }
            throw new WorkflowException("workflow_parse_error", "Workflow front matter is invalid YAML", e);
        }
    }

    private static void rejectNullTopLevelEntries(LinkedHashMap<String, Object> parsed) {
        if (parsed.entrySet().stream().anyMatch(WorkflowLoader::hasNullKeyOrValue)) {
            throw new WorkflowException(
                    "workflow_parse_error", "Workflow front matter top-level keys and values must not be null");
        }
    }

    private static boolean hasNullKeyOrValue(Map.Entry<String, Object> entry) {
        return entry.getKey() == null || entry.getValue() == null;
    }

    private static ParsedMarkdown splitFrontMatter(List<String> lines) {
        if (lines.isEmpty() || !"---".equals(lines.getFirst().trim())) {
            return new ParsedMarkdown(null, String.join(System.lineSeparator(), lines));
        }

        int closing = -1;
        for (int i = 1; i < lines.size(); i++) {
            if ("---".equals(lines.get(i).trim())) {
                closing = i;
                break;
            }
        }
        if (closing < 0) {
            throw new WorkflowException("workflow_parse_error", "Workflow YAML front matter is missing closing ---");
        }

        String frontMatter = String.join(System.lineSeparator(), lines.subList(1, closing));
        String body = String.join(System.lineSeparator(), lines.subList(closing + 1, lines.size()));
        return new ParsedMarkdown(frontMatter, body);
    }

    private record ParsedMarkdown(@Nullable String frontMatter, String body) {}
}
