package ch.fmartin.symphony.trello.boardsession;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import jakarta.ws.rs.core.Response.Status;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/// Loopback MCP server that exposes [BoardSessionTools] to one interactive Codex process over the
/// MCP streamable HTTP transport.
///
/// It runs inside the `symphony-trello` process, so the Trello API key and token stay in this JVM
/// and never reach Codex. Codex authenticates with a random bearer token that exists only for this
/// session. The server answers every request with one JSON response and offers no event stream,
/// which the transport allows for servers without server-initiated messages.
@NullMarked
public final class BoardSessionMcpServer implements AutoCloseable {
    public static final String PATH = "/mcp";
    static final String LATEST_PROTOCOL_VERSION = "2025-11-25";
    /// Protocol revisions whose initialize, ping, tools/list, and tools/call messages this server
    /// implements unchanged.
    static final List<String> SUPPORTED_PROTOCOL_VERSIONS =
            List.of(LATEST_PROTOCOL_VERSION, "2025-06-18", "2025-03-26", "2024-11-05");
    static final int MAX_REQUEST_BYTES = 1024 * 1024;
    /// Both the bound socket and the advertised URL use this literal address, so they agree even when
    /// the JVM prefers IPv6 for the generic loopback lookup.
    private static final String LOOPBACK_HOST = "127.0.0.1";
    private static final SecureRandom TOKEN_RANDOM = new SecureRandom();
    private static final int TOKEN_BYTES = 32;
    private static final String JSON_RPC_VERSION = "2.0";
    private static final int PARSE_ERROR = -32_700;
    private static final int INVALID_REQUEST = -32_600;
    private static final int METHOD_NOT_FOUND = -32_601;
    private static final int INVALID_PARAMS = -32_602;

    private final ObjectMapper json;
    private final BoardSessionTools tools;
    private final String serverInstructions;
    private final String serverVersion;
    private final String bearerToken;
    private final HttpServer server;
    private final ExecutorService executor;

    private BoardSessionMcpServer(
            ObjectMapper json,
            BoardSessionTools tools,
            String serverInstructions,
            String serverVersion,
            HttpServer server) {
        this.json = json;
        this.tools = tools;
        this.serverInstructions = serverInstructions;
        this.serverVersion = serverVersion;
        this.bearerToken = newBearerToken();
        this.server = server;
        // Owned by this server and closed in close().
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        server.createContext("/", this::handle);
        server.setExecutor(executor);
    }

    /// Binds a new server to an ephemeral loopback port and starts serving.
    public static BoardSessionMcpServer start(
            ObjectMapper json, BoardSessionTools tools, String serverInstructions, String serverVersion)
            throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.ofLiteral(LOOPBACK_HOST), 0), 0);
        var mcp = new BoardSessionMcpServer(json, tools, serverInstructions, serverVersion, server);
        server.start();
        return mcp;
    }

    public URI endpoint() {
        return URI.create("http://" + LOOPBACK_HOST + ":" + server.getAddress().getPort() + PATH);
    }

    /// Secret that Codex must send as `Authorization: Bearer ...`. Pass it only through the Codex
    /// child environment, never on a command line.
    public String bearerToken() {
        return bearerToken;
    }

    /// Stops accepting requests and waits for running tool calls to finish.
    @Override
    public void close() {
        server.stop(0);
        executor.close();
    }

    private static String newBearerToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        TOKEN_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!PATH.equals(exchange.getRequestURI().getPath())) {
                respondEmpty(exchange, Status.NOT_FOUND);
                return;
            }
            if (!loopbackHost(exchange) || exchange.getRequestHeaders().containsKey("Origin")) {
                // Codex does not send Origin. Refusing it and foreign Host values keeps browser
                // pages, including DNS-rebinding attempts, away from the board tools.
                respondEmpty(exchange, Status.FORBIDDEN);
                return;
            }
            if (!authorized(exchange)) {
                exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer");
                respondEmpty(exchange, Status.UNAUTHORIZED);
                return;
            }
            if (!"POST".equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Allow", "POST");
                respondEmpty(exchange, Status.METHOD_NOT_ALLOWED);
                return;
            }
            Optional<byte[]> body = readBody(exchange.getRequestBody());
            if (body.isEmpty()) {
                respondEmpty(exchange, Status.REQUEST_ENTITY_TOO_LARGE);
                return;
            }
            handleMessage(exchange, body.orElseThrow());
        }
    }

    private void handleMessage(HttpExchange exchange, byte[] body) throws IOException {
        JsonNode message;
        try {
            message = json.readTree(body);
        } catch (JsonProcessingException e) {
            respondJson(exchange, Status.BAD_REQUEST, error(null, PARSE_ERROR, "Request body is not valid JSON."));
            return;
        }
        if (message == null || !(message.isObject() || message.isArray())) {
            respondJson(exchange, Status.BAD_REQUEST, error(null, INVALID_REQUEST, "Expected a JSON-RPC message."));
            return;
        }
        if (message.isObject()) {
            Optional<ObjectNode> response = dispatch(message);
            if (response.isPresent()) {
                respondJson(exchange, Status.OK, response.orElseThrow());
            } else {
                respondEmpty(exchange, Status.ACCEPTED);
            }
            return;
        }
        ArrayNode responses = json.createArrayNode();
        message.forEach(item -> dispatch(item).ifPresent(responses::add));
        if (responses.isEmpty()) {
            respondEmpty(exchange, Status.ACCEPTED);
        } else {
            respondJson(exchange, Status.OK, responses);
        }
    }

    /// Returns the JSON-RPC response for a request, or empty for notifications and client responses,
    /// which need no reply.
    private Optional<ObjectNode> dispatch(JsonNode message) {
        JsonNode id = message.get("id");
        JsonNode method = message.get("method");
        if (method == null || !method.isTextual()) {
            return id == null || message.has("result") || message.has("error")
                    ? Optional.empty()
                    : Optional.of(error(id, INVALID_REQUEST, "Missing JSON-RPC method."));
        }
        if (id == null) {
            return Optional.empty();
        }
        JsonNode params = message.path("params");
        return Optional.of(
                switch (method.textValue()) {
                    case "initialize" -> result(id, initializeResult(params));
                    case "ping" -> result(id, json.createObjectNode());
                    case "tools/list" -> result(id, toolsListResult());
                    case "tools/call" -> toolsCall(id, params);
                    default -> error(id, METHOD_NOT_FOUND, "Unsupported MCP method: " + method.textValue());
                });
    }

    private ObjectNode initializeResult(JsonNode params) {
        String requested = params.path("protocolVersion").asText("");
        ObjectNode result = json.createObjectNode();
        result.put(
                "protocolVersion",
                SUPPORTED_PROTOCOL_VERSIONS.contains(requested) ? requested : LATEST_PROTOCOL_VERSION);
        result.putObject("capabilities").putObject("tools").put("listChanged", false);
        result.putObject("serverInfo").put("name", "symphony-trello").put("version", serverVersion);
        result.put("instructions", serverInstructions);
        return result;
    }

    private ObjectNode toolsListResult() {
        ObjectNode result = json.createObjectNode();
        ArrayNode toolNodes = result.putArray("tools");
        for (BoardSessionTools.ToolDefinition tool : tools.definitions()) {
            ObjectNode node = toolNodes.addObject();
            node.put("name", tool.name());
            node.put("description", tool.description());
            node.set("inputSchema", tool.inputSchema());
            ObjectNode annotations = node.putObject("annotations");
            annotations.put("readOnlyHint", tool.readOnly());
            annotations.put("destructiveHint", tool.destructive());
            annotations.put("openWorldHint", true);
        }
        return result;
    }

    private ObjectNode toolsCall(JsonNode id, JsonNode params) {
        JsonNode name = params.path("name");
        if (!name.isTextual()) {
            return error(id, INVALID_PARAMS, "tools/call requires a tool name.");
        }
        BoardSessionTools.ToolResult toolResult = tools.call(name.textValue(), params.path("arguments"));
        ObjectNode result = json.createObjectNode();
        result.putArray("content")
                .addObject()
                .put("type", "text")
                .put("text", toolResult.payload().toString());
        result.put("isError", toolResult.error());
        return result(id, result);
    }

    private ObjectNode result(JsonNode id, ObjectNode result) {
        ObjectNode response = json.createObjectNode();
        response.put("jsonrpc", JSON_RPC_VERSION);
        response.set("id", id);
        response.set("result", result);
        return response;
    }

    private ObjectNode error(@Nullable JsonNode id, int code, String message) {
        ObjectNode response = json.createObjectNode();
        response.put("jsonrpc", JSON_RPC_VERSION);
        if (id == null) {
            response.putNull("id");
        } else {
            response.set("id", id);
        }
        response.putObject("error").put("code", code).put("message", message);
        return response;
    }

    private boolean authorized(HttpExchange exchange) {
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        String expected = "Bearer " + bearerToken;
        return header != null
                && MessageDigest.isEqual(
                        header.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8));
    }

    private boolean loopbackHost(HttpExchange exchange) {
        String host = exchange.getRequestHeaders().getFirst("Host");
        int port = server.getAddress().getPort();
        return host == null
                || host.equals(LOOPBACK_HOST + ":" + port)
                || host.equals("localhost:" + port)
                || host.equals("[::1]:" + port);
    }

    private static Optional<byte[]> readBody(InputStream input) throws IOException {
        byte[] body = input.readNBytes(MAX_REQUEST_BYTES + 1);
        return body.length > MAX_REQUEST_BYTES ? Optional.empty() : Optional.of(body);
    }

    private void respondJson(HttpExchange exchange, Status status, JsonNode body) throws IOException {
        byte[] bytes = json.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status.getStatusCode(), bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private static void respondEmpty(HttpExchange exchange, Status status) throws IOException {
        exchange.sendResponseHeaders(status.getStatusCode(), -1);
    }
}
