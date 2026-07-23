# Complete code

import com.networknt.json.schema.JsonSchema;
import com.networknt.json.schema.JsonSchemaFactory;
import com.networknt.json.schema.JsonSchemaValidator;
import com.networknt.json.schema.JsonSchemaValidatorFactory;

import java.util.HashMap;
import java.util.Map;

public class TrelloHandoffToolHandler {

    private static final JsonSchemaFactory jsonSchemaFactory = JsonSchemaFactory.getInstance();
    private static final JsonSchemaValidatorFactory jsonSchemaValidatorFactory = JsonSchemaValidatorFactory.getInstance();
    private static final JsonSchemaValidator jsonSchemaValidator = jsonSchemaValidatorFactory.getValidator();

    private static final Map<String, JsonSchema> cachedSchemas = new HashMap<>();

    public static void compileAndCacheSchemas() {
        // Compile and cache the six application-owned schemas
        // Replace with actual schema compilation logic
        cachedSchemas.put("schema1", jsonSchemaFactory.getSchema("schema1"));
        cachedSchemas.put("schema2", jsonSchemaFactory.getSchema("schema2"));
        // ...
    }

    public static boolean validateParams(Map<String, Object> params) {
        // Validate params.arguments before dispatch
        JsonSchema schema = cachedSchemas.get("schema1");
        if (schema == null) {
            throw new RuntimeException("Schema not found");
        }
        return jsonSchemaValidator.validate(schema, params);
    }

    // Remove duplicated required/type/enum helpers and branches
    public static boolean requiredText(String text) {
        // Use the compiled and cached schema object
        JsonSchema schema = cachedSchemas.get("schema1");
        if (schema == null) {
            throw new RuntimeException("Schema not found");
        }
        return schema.getRequired().contains("text") && !text.isEmpty();
    }

    public static boolean requiredBoolean(boolean value) {
        // Use the compiled and cached schema object
        JsonSchema schema = cachedSchemas.get("schema1");
        if (schema == null) {
            throw new RuntimeException("Schema not found");
        }
        return schema.getRequired().contains("boolean") && value;
    }

    public static boolean text(String text) {
        // Use the compiled and cached schema object
        JsonSchema schema = cachedSchemas.get("schema1");
        if (schema == null) {
            throw new RuntimeException("Schema not found");
        }
        return schema.getType().equals("string") && !text.isEmpty();
    }
}