package ch.fmartin.symphony.trello.setup;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/// Structured answer of a local Codex investigation into an unexpected setup failure.
///
/// [#jsonSchema()] is passed to `codex exec --output-schema`, and [#parse(JsonNode)] reads the
/// answer back. Both use the same field-name constants so the schema and the parser cannot drift.
record CodexInvestigationFinding(
        Classification classification,
        String diagnosis,
        boolean fixApplied,
        String fixSummary,
        List<String> changedFiles,
        List<ValidationRun> validation,
        String nextStep) {
    private static final String CLASSIFICATION = "classification";
    private static final String DIAGNOSIS = "diagnosis";
    private static final String FIX_APPLIED = "fix_applied";
    private static final String FIX_SUMMARY = "fix_summary";
    private static final String CHANGED_FILES = "changed_files";
    private static final String VALIDATION = "validation";
    private static final String VALIDATION_COMMAND = "command";
    private static final String VALIDATION_PASSED = "passed";
    private static final String NEXT_STEP = "next_step";
    private static final String TYPE = "type";
    private static final String STRING = "string";
    private static final String BOOLEAN = "boolean";

    CodexInvestigationFinding {
        changedFiles = List.copyOf(changedFiles);
        validation = List.copyOf(validation);
    }

    /// True only when Codex changed files and every validation command it reported passed. A fix
    /// without changed files or without validation is not treated as verified.
    boolean verifiedFix() {
        return fixApplied
                && !changedFiles.isEmpty()
                && !validation.isEmpty()
                && validation.stream().allMatch(ValidationRun::passed);
    }

    static String jsonSchema(ObjectMapper json) {
        ObjectNode validationRun = strictObject(json);
        ObjectNode validationRunProperties = validationRun.putObject("properties");
        validationRunProperties.putObject(VALIDATION_COMMAND).put(TYPE, STRING);
        validationRunProperties.putObject(VALIDATION_PASSED).put(TYPE, BOOLEAN);
        requireAll(validationRun, VALIDATION_COMMAND, VALIDATION_PASSED);

        ObjectNode schema = strictObject(json);
        ObjectNode properties = schema.putObject("properties");
        ArrayNode classifications =
                properties.putObject(CLASSIFICATION).put(TYPE, STRING).putArray("enum");
        Arrays.stream(Classification.values()).map(Classification::jsonValue).forEach(classifications::add);
        properties.putObject(DIAGNOSIS).put(TYPE, STRING);
        properties.putObject(FIX_APPLIED).put(TYPE, BOOLEAN);
        properties.putObject(FIX_SUMMARY).put(TYPE, STRING);
        properties
                .putObject(CHANGED_FILES)
                .put(TYPE, "array")
                .putObject("items")
                .put(TYPE, STRING);
        properties.putObject(VALIDATION).put(TYPE, "array").set("items", validationRun);
        properties.putObject(NEXT_STEP).put(TYPE, STRING);
        requireAll(schema, CLASSIFICATION, DIAGNOSIS, FIX_APPLIED, FIX_SUMMARY, CHANGED_FILES, VALIDATION, NEXT_STEP);
        return schema.toString();
    }

    /// Codex structured output requires every property to be listed as required and rejects
    /// additional properties, so every object in the schema uses this shape.
    private static ObjectNode strictObject(ObjectMapper json) {
        ObjectNode object = json.createObjectNode();
        object.put(TYPE, "object");
        object.put("additionalProperties", false);
        return object;
    }

    private static void requireAll(ObjectNode object, String... names) {
        ArrayNode required = object.putArray("required");
        Arrays.stream(names).forEach(required::add);
    }

    static Optional<CodexInvestigationFinding> parse(JsonNode root) {
        if (root == null || !root.isObject()) {
            return Optional.empty();
        }
        return Classification.fromJson(root.path(CLASSIFICATION).asText())
                .map(classification -> new CodexInvestigationFinding(
                        classification,
                        root.path(DIAGNOSIS).asText(""),
                        root.path(FIX_APPLIED).asBoolean(false),
                        root.path(FIX_SUMMARY).asText(""),
                        textValues(root.path(CHANGED_FILES)),
                        validationRuns(root.path(VALIDATION)),
                        root.path(NEXT_STEP).asText("")));
    }

    private static List<String> textValues(JsonNode array) {
        List<String> values = new ArrayList<>();
        for (JsonNode value : array) {
            if (value.isTextual() && !value.asText().isBlank()) {
                values.add(value.asText());
            }
        }
        return values;
    }

    private static List<ValidationRun> validationRuns(JsonNode array) {
        List<ValidationRun> runs = new ArrayList<>();
        for (JsonNode run : array) {
            String command = run.path(VALIDATION_COMMAND).asText("");
            if (!command.isBlank()) {
                runs.add(new ValidationRun(command, run.path(VALIDATION_PASSED).asBoolean(false)));
            }
        }
        return runs;
    }

    record ValidationRun(String command, boolean passed) {}

    enum Classification {
        SYMPHONY_BUG("symphony_bug", "Symphony for Trello bug"),
        LOCAL_CONFIGURATION("local_configuration", "local configuration problem"),
        ENVIRONMENT("environment", "problem in this machine's environment"),
        EXTERNAL_TOOL_OR_SERVICE("external_tool_or_service", "external tool or service problem"),
        UNKNOWN("unknown", "cause not determined");

        private final String jsonValue;
        private final String label;

        Classification(String jsonValue, String label) {
            this.jsonValue = jsonValue;
            this.label = label;
        }

        String jsonValue() {
            return jsonValue;
        }

        String label() {
            return label;
        }

        static Optional<Classification> fromJson(String value) {
            return Arrays.stream(values())
                    .filter(classification -> classification.jsonValue.equals(value))
                    .findAny();
        }
    }
}
