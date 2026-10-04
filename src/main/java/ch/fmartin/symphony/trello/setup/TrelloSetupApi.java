package ch.fmartin.symphony.trello.setup;

import static ch.fmartin.symphony.trello.TextCharacterMatchers.SLASHES;

import ch.fmartin.symphony.trello.setup.TrelloBoardSetup.TrelloCredentials;
import ch.fmartin.symphony.trello.tracker.TrelloClient;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.core.Response.Status;
import jakarta.ws.rs.core.Response.Status.Family;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/// Sends the Trello REST requests that setup commands make and maps every failure to a setup error
/// code, so board setup and other setup commands report Trello problems the same way.
final class TrelloSetupApi {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final TypeReference<List<Map<String, Object>>> LIST_MAP_TYPE = new TypeReference<>() {};

    private final ObjectMapper json;
    private final HttpClient httpClient;

    TrelloSetupApi(ObjectMapper json) {
        this.json = json;
        this.httpClient =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    Map<String, Object> getMap(URI endpoint, String path, Map<String, String> query, TrelloCredentials credentials) {
        return request(TrelloRequestKind.READ, endpoint, path, query, credentials, MAP_TYPE);
    }

    List<Map<String, Object>> getList(
            URI endpoint, String path, Map<String, String> query, TrelloCredentials credentials) {
        return request(TrelloRequestKind.READ, endpoint, path, query, credentials, LIST_MAP_TYPE);
    }

    Map<String, Object> postMap(
            URI endpoint,
            String path,
            Map<String, String> query,
            TrelloCredentials credentials,
            String... requiredKeys) {
        Map<String, Object> payload = request(TrelloRequestKind.WRITE, endpoint, path, query, credentials, MAP_TYPE);
        if (payload == null) {
            throw unknownTrelloWriteOutcome(
                    new TrelloBoardSetupException("trello_unknown_payload", "Trello payload is empty"));
        }
        for (String requiredKey : requiredKeys) {
            Object value = payload.get(requiredKey);
            if (value == null || value.toString().isBlank()) {
                throw unknownTrelloWriteOutcome(new TrelloBoardSetupException(
                        "trello_unknown_payload", "Trello payload is missing " + requiredKey));
            }
        }
        return payload;
    }

    static String encodeSegment(String value) {
        return encode(value).replace("+", "%20");
    }

    private <T> T request(
            TrelloRequestKind requestKind,
            URI endpoint,
            String path,
            Map<String, String> query,
            TrelloCredentials credentials,
            TypeReference<T> type) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri(endpoint, path, query))
                    .timeout(Duration.ofSeconds(30))
                    .header("Accept", "application/json")
                    .header("Authorization", TrelloClient.authorization(credentials.apiKey(), credentials.apiToken()));
            HttpRequest request =
                    switch (requestKind) {
                        case READ -> builder.GET().build();
                        case WRITE ->
                            builder.POST(HttpRequest.BodyPublishers.noBody()).build();
                    };
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (isSuccessfulStatus(response.statusCode())) {
                try {
                    return json.readValue(response.body(), type);
                } catch (JsonProcessingException e) {
                    throw trelloPayloadException(requestKind, e);
                }
            }
            throw statusException(requestKind, response.statusCode(), response.body());
        } catch (IOException e) {
            throw trelloTransportException(requestKind, "Trello request failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw trelloTransportException(requestKind, "Trello request interrupted", e);
        }
    }

    private static TrelloBoardSetupException trelloPayloadException(
            TrelloRequestKind requestKind, JsonProcessingException cause) {
        return switch (requestKind) {
            case READ ->
                new TrelloBoardSetupException(
                        "trello_unknown_payload", "Trello response payload could not be parsed", cause);
            case WRITE -> unknownTrelloWriteOutcome(cause);
        };
    }

    private static TrelloBoardSetupException trelloTransportException(
            TrelloRequestKind requestKind, String message, Exception cause) {
        return switch (requestKind) {
            case READ -> new TrelloBoardSetupException("trello_api_request", message, cause);
            case WRITE -> unknownTrelloWriteOutcome(cause);
        };
    }

    private static TrelloBoardSetupException unknownTrelloWriteOutcome(Exception cause) {
        return new TrelloBoardSetupException(
                "trello_write_outcome_unknown",
                "Trello write outcome is unknown. Inspect Trello before retrying setup.",
                cause);
    }

    private static URI uri(URI endpoint, String path, Map<String, String> query) {
        String normalizedPath = path.startsWith("/") ? path.substring(1) : path;
        if (normalizedPath.contains("..") || normalizedPath.contains("?") || normalizedPath.contains("#")) {
            throw new TrelloBoardSetupException("setup_invalid_path", "Invalid Trello API path");
        }
        String queryString = query.entrySet().stream()
                .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                .collect(Collectors.joining("&"));
        String base = SLASHES.trimTrailingFrom(endpoint.toString());
        return URI.create(base + "/" + normalizedPath + (queryString.isBlank() ? "" : "?" + queryString));
    }

    private static TrelloBoardSetupException statusException(
            TrelloRequestKind requestKind, int statusCode, String responseBody) {
        String detail = responseBody == null || responseBody.isBlank()
                ? ""
                : ": " + responseBody.strip().lines().findFirst().orElse("");
        Status status = Status.fromStatusCode(statusCode);
        if (requestKind == TrelloRequestKind.WRITE && Family.SERVER_ERROR == Family.familyOf(statusCode)) {
            return unknownTrelloWriteOutcome(new TrelloBoardSetupException(
                    "trello_api_status", "Trello returned HTTP " + statusCode + detail, statusCode));
        }
        if (status == null) {
            return new TrelloBoardSetupException(
                    "trello_api_status", "Trello returned HTTP " + statusCode + detail, statusCode);
        }
        return switch (status) {
            case BAD_REQUEST ->
                new TrelloBoardSetupException(
                        "trello_invalid_request", "Trello rejected the setup request" + detail, statusCode);
            case UNAUTHORIZED ->
                new TrelloBoardSetupException(
                        "trello_auth_failed", "Trello authentication failed" + detail, statusCode);
            case FORBIDDEN ->
                new TrelloBoardSetupException(
                        "trello_permission_denied", "Trello permission denied" + detail, statusCode);
            case NOT_FOUND ->
                new TrelloBoardSetupException(
                        "trello_resource_not_found", "Trello resource not found" + detail, statusCode);
            default ->
                new TrelloBoardSetupException(
                        "trello_api_status", "Trello returned HTTP " + statusCode + detail, statusCode);
        };
    }

    private static boolean isSuccessfulStatus(int statusCode) {
        return Family.SUCCESSFUL == Family.familyOf(statusCode);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private enum TrelloRequestKind {
        READ,
        WRITE
    }
}
