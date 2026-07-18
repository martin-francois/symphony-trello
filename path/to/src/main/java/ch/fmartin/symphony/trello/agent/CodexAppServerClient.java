# Complete code

import com.networknt.json.schema.JsonSchema;
import com.networknt.json.schema.JsonSchemaFactory;

public class CodexAppServerClient {

    private static final JsonSchemaFactory jsonSchemaFactory = JsonSchemaFactory.getInstance();

    public static JsonSchema getSchema(String schemaId) {
        // Return the compiled and cached schema object
        return TrelloHandoffToolHandler.cachedSchemas.get(schemaId);
    }
}