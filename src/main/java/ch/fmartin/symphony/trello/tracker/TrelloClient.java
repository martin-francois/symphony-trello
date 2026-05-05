package ch.fmartin.symphony.trello.tracker;

import ch.fmartin.symphony.trello.config.EffectiveConfig;
import ch.fmartin.symphony.trello.config.StateNames;
import ch.fmartin.symphony.trello.domain.BlockerRef;
import ch.fmartin.symphony.trello.domain.Card;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import org.jboss.logging.Logger;

@ApplicationScoped
public class TrelloClient implements TrackerClient {
    private static final Logger LOG = Logger.getLogger(TrelloClient.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final TypeReference<List<Map<String, Object>>> LIST_MAP_TYPE = new TypeReference<>() {};
    private static final String CARD_FIELDS =
            "id,name,desc,idList,idBoard,closed,idShort,shortLink,shortUrl,url,labels,dateLastActivity,due,dueComplete,pos";
    private static final String COMMENT_ACTION_FIELDS = "data,date,memberCreator";
    private static final String COMMENT_ACTION_LIMIT = "20";

    private final ObjectMapper json;
    private final HttpClient httpClient;

    public TrelloClient(ObjectMapper json) {
        this.json = json;
        this.httpClient =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    @Override
    public String resolveBoardId(EffectiveConfig config) {
        Map<String, Object> board = getMap(
                config, "boards/" + encodeSegment(config.tracker().boardId()), Map.of("fields", "id,name,closed"));
        if (bool(board.get("closed"))) {
            throw new TrelloException("trello_board_closed", "Configured Trello board is closed");
        }
        return requiredString(board, "id", "trello_unknown_payload");
    }

    @Override
    public List<Card> fetchCandidateCards(EffectiveConfig config) {
        BoardContext context = boardContext(config);
        List<Map<String, Object>> payload = getList(
                config,
                "boards/" + encodeSegment(context.boardId()) + "/cards/open",
                Map.of("fields", CARD_FIELDS, "filter", "open"));
        return payload.stream()
                .map(card -> normalize(card, context, config))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .filter(card -> isActive(card, config) && !isTerminal(card, config))
                .toList();
    }

    @Override
    public List<Card> fetchTerminalCards(EffectiveConfig config) {
        BoardContext context = boardContext(config);
        List<Map<String, Object>> boardCards = getList(
                config,
                "boards/" + encodeSegment(context.boardId()) + "/cards/all",
                Map.of("fields", CARD_FIELDS, "filter", "all"));
        List<Card> normalized = boardCards.stream()
                .map(card -> normalize(card, context, config))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .filter(card -> isTerminal(card, config))
                .collect(Collectors.toCollection(ArrayList::new));

        Set<String> archivedListIds = context.lists().values().stream()
                .filter(BoardList::closed)
                .map(BoardList::id)
                .collect(Collectors.toSet());
        for (String listId : archivedListIds) {
            List<Map<String, Object>> listCards = getList(
                    config,
                    "lists/" + encodeSegment(listId) + "/cards",
                    Map.of("fields", CARD_FIELDS, "filter", "all"));
            listCards.stream()
                    .map(card -> normalize(card, context, config))
                    .filter(Optional::isPresent)
                    .map(Optional::get)
                    .filter(card -> isTerminal(card, config))
                    .forEach(normalized::add);
        }

        return normalized.stream()
                .collect(Collectors.toMap(Card::id, card -> card, (left, right) -> left, LinkedHashMap::new))
                .values()
                .stream()
                .toList();
    }

    @Override
    public Map<String, CardLookupResult> fetchCardStatesByIds(EffectiveConfig config, List<String> cardIds) {
        BoardContext context = boardContext(config);
        Map<String, CardLookupResult> results = new LinkedHashMap<>();
        for (String cardId : cardIds) {
            try {
                Map<String, Object> payload = cardWithComments(config, cardId);
                Optional<Card> card = normalize(payload, context, config);
                results.put(
                        cardId,
                        card.<CardLookupResult>map(CardLookupResult.Found::new)
                                .orElseGet(() -> new CardLookupResult.Failed(
                                        cardId, "trello_unknown_payload", "Card payload could not be normalized")));
            } catch (TrelloException e) {
                if (e.statusCode() == 404) {
                    results.put(cardId, new CardLookupResult.Missing(cardId));
                } else {
                    results.put(cardId, new CardLookupResult.Failed(cardId, e.code(), e.getMessage()));
                }
            }
        }
        return results;
    }

    private Map<String, Object> cardWithComments(EffectiveConfig config, String cardId) {
        return getMap(
                config,
                "cards/" + encodeSegment(cardId),
                Map.of(
                        "fields",
                        CARD_FIELDS,
                        "attachments",
                        "false",
                        "actions",
                        "commentCard",
                        "actions_limit",
                        COMMENT_ACTION_LIMIT,
                        "action_fields",
                        COMMENT_ACTION_FIELDS));
    }

    public static boolean isActive(Card card, EffectiveConfig config) {
        if (card.closed() || Boolean.TRUE.equals(card.listClosed()) || Boolean.TRUE.equals(card.boardClosed())) {
            return false;
        }
        if (card.boardId() != null
                && !Objects.equals(card.boardId(), config.tracker().resolvedBoardId())) {
            return false;
        }
        if (card.listId() != null && !config.tracker().activeListIds().isEmpty()) {
            return config.tracker().activeListIds().contains(card.listId());
        }
        return config.tracker().activeStates().stream()
                .map(StateNames::normalize)
                .anyMatch(state -> state.equals(StateNames.normalize(card.state())));
    }

    public static boolean isTerminal(Card card, EffectiveConfig config) {
        if (card.listId() != null && !config.tracker().terminalListIds().isEmpty()) {
            return config.tracker().terminalListIds().contains(card.listId());
        }
        return config.tracker().terminalStates().contains(StateNames.normalize(card.state()));
    }

    public static Comparator<Card> dispatchComparator(EffectiveConfig config) {
        return Comparator.comparing((Card card) -> card.priority() == null ? Integer.MAX_VALUE : card.priority())
                .thenComparingInt(card -> activeOrder(card, config))
                .thenComparing(card -> card.position() == null ? BigDecimal.valueOf(Long.MAX_VALUE) : card.position())
                .thenComparing(card -> card.createdAt() == null ? Instant.MAX : card.createdAt())
                .thenComparing(Card::identifier);
    }

    private static int activeOrder(Card card, EffectiveConfig config) {
        if (card.listId() != null && !config.tracker().activeListIds().isEmpty()) {
            int index = config.tracker().activeListIds().indexOf(card.listId());
            return index < 0 ? Integer.MAX_VALUE : index;
        }
        List<String> active = config.tracker().activeStates().stream()
                .map(StateNames::normalize)
                .toList();
        int index = active.indexOf(StateNames.normalize(card.state()));
        return index < 0 ? Integer.MAX_VALUE : index;
    }

    private BoardContext boardContext(EffectiveConfig config) {
        Map<String, Object> board = getMap(
                config, "boards/" + encodeSegment(config.tracker().boardId()), Map.of("fields", "id,name,closed"));
        String boardId = requiredString(board, "id", "trello_unknown_payload");
        boolean boardClosed = bool(board.get("closed"));
        Map<String, BoardList> listMap = fetchBoardLists(config.withResolvedBoardId(boardId)).stream()
                .collect(Collectors.toMap(BoardList::id, list -> list, (left, right) -> left, LinkedHashMap::new));
        return new BoardContext(boardId, boardClosed, listMap);
    }

    public List<BoardList> fetchBoardLists(EffectiveConfig config) {
        String boardId = config.tracker().resolvedBoardId();
        return getList(
                        config,
                        "boards/" + encodeSegment(boardId) + "/lists",
                        Map.of("filter", "all", "fields", "id,name,closed,pos"))
                .stream()
                .map(this::toList)
                .toList();
    }

    public Map<String, Object> addComment(EffectiveConfig config, String cardId, String text) {
        return postMap(config, "cards/" + encodeSegment(cardId) + "/actions/comments", Map.of("text", text));
    }

    public Map<String, Object> moveCardToList(EffectiveConfig config, String cardId, String listId) {
        return putMap(config, "cards/" + encodeSegment(cardId) + "/idList", Map.of("value", listId));
    }

    private Optional<Card> normalize(Map<String, Object> payload, BoardContext context, EffectiveConfig config) {
        String id = string(payload.get("id"));
        String name = string(payload.get("name"));
        String boardId = string(payload.get("idBoard"));
        String listId = string(payload.get("idList"));
        if (blank(id) || blank(name) || blank(boardId)) {
            LOG.warnf("trello_card outcome=skipped reason=missing_required_fields card_id=%s", id);
            return Optional.empty();
        }
        BoardList list = listId == null ? null : context.lists().get(listId);
        boolean cardClosed = bool(payload.get("closed"));
        String state;
        String source;
        if (context.boardClosed()) {
            state = "ArchivedBoard";
            source = "board_closed";
        } else if (cardClosed) {
            state = "Archived";
            source = "card_closed";
        } else if (list != null && list.closed()) {
            state = "ArchivedList";
            source = "list_closed";
        } else if (list != null) {
            state = list.name();
            source = "list";
        } else {
            state = "Unknown";
            source = "unknown";
        }

        List<String> labels = labels(payload);
        Integer priority = labels.stream()
                .map(label -> config.tracker().priorityLabels().get(StateNames.normalize(label)))
                .filter(Objects::nonNull)
                .min(Integer::compareTo)
                .orElse(null);

        return Optional.of(new Card(
                id,
                identifier(config, payload),
                name,
                string(payload.get("desc")),
                priority,
                state,
                source,
                listId,
                list == null ? null : list.name(),
                list == null ? null : list.closed(),
                boardId,
                context.boardClosed(),
                cardClosed,
                integer(payload.get("idShort")),
                string(payload.get("shortLink")),
                string(payload.get("shortUrl")),
                null,
                string(payload.get("url")),
                labels,
                labelIds(payload),
                List.of(),
                List.<BlockerRef>of(),
                comments(payload),
                createdAtFromObjectId(id),
                instant(payload.get("dateLastActivity")),
                instant(payload.get("due")),
                nullableBool(payload.get("dueComplete")),
                decimal(payload.get("pos"))));
    }

    private static List<Card.Comment> comments(Map<String, Object> payload) {
        Object actions = payload.get("actions");
        if (!(actions instanceof List<?> actionList)) {
            return List.of();
        }
        return actionList.stream()
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .map(TrelloClient::comment)
                .flatMap(Optional::stream)
                .toList();
    }

    private static Optional<Card.Comment> comment(Map<?, ?> action) {
        String text = null;
        Object data = action.get("data");
        if (data instanceof Map<?, ?> dataMap) {
            text = string(dataMap.get("text"));
        }
        if (blank(text)) {
            return Optional.empty();
        }
        return Optional.of(new Card.Comment(
                string(action.get("id")),
                text,
                commentAuthor(action.get("memberCreator")),
                instant(action.get("date"))));
    }

    private static String commentAuthor(Object memberCreator) {
        if (!(memberCreator instanceof Map<?, ?> member)) {
            return null;
        }
        String fullName = string(member.get("fullName"));
        return blank(fullName) ? string(member.get("username")) : fullName;
    }

    private Map<String, Object> getMap(EffectiveConfig config, String path, Map<String, String> query) {
        return request("GET", config, path, query, MAP_TYPE, true);
    }

    private List<Map<String, Object>> getList(EffectiveConfig config, String path, Map<String, String> query) {
        return request("GET", config, path, query, LIST_MAP_TYPE, true);
    }

    private Map<String, Object> postMap(EffectiveConfig config, String path, Map<String, String> query) {
        // Do not automatically retry writes; a network failure after Trello applied a comment could duplicate it.
        return request("POST", config, path, query, MAP_TYPE, false);
    }

    private Map<String, Object> putMap(EffectiveConfig config, String path, Map<String, String> query) {
        return request("PUT", config, path, query, MAP_TYPE, false);
    }

    private <T> T request(
            String method,
            EffectiveConfig config,
            String path,
            Map<String, String> query,
            TypeReference<T> type,
            boolean retry) {
        URI uri = uri(config, path, query);
        int maxAttempts = retry ? Math.max(1, config.tracker().maxApiRetries() + 1) : 1;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                        .timeout(config.tracker().requestTimeout())
                        .header("Accept", "application/json")
                        .header("Authorization", authorization(config));
                HttpRequest request =
                        switch (method) {
                            case "GET" -> builder.GET().build();
                            case "POST" ->
                                builder.POST(HttpRequest.BodyPublishers.noBody())
                                        .build();
                            case "PUT" ->
                                builder.PUT(HttpRequest.BodyPublishers.noBody()).build();
                            default -> throw new IllegalArgumentException("Unsupported Trello method: " + method);
                        };
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    return json.readValue(response.body(), type);
                }
                if (response.statusCode() == 429 && attempt < maxAttempts) {
                    sleep(backoff(config, attempt, response));
                    continue;
                }
                throw statusException(response.statusCode());
            } catch (IOException e) {
                if (attempt == maxAttempts) {
                    throw new TrelloException("trello_api_request", "Trello request failed", e);
                }
                sleep(backoff(config, attempt, null));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new TrelloException("trello_api_request", "Trello request interrupted", e);
            }
        }
        throw new TrelloException("trello_api_request", "Trello request failed after retries");
    }

    private static TrelloException statusException(int statusCode) {
        return switch (statusCode) {
            case 401 -> new TrelloException("trello_auth_failed", "Trello authentication failed", statusCode);
            case 403 -> new TrelloException("trello_permission_denied", "Trello permission denied", statusCode);
            case 404 -> new TrelloException("trello_card_not_found", "Trello resource not found", statusCode);
            case 429 -> new TrelloException("trello_api_rate_limited", "Trello rate limit exceeded", statusCode);
            default -> new TrelloException("trello_api_status", "Trello returned HTTP " + statusCode, statusCode);
        };
    }

    private static Duration backoff(EffectiveConfig config, int attempt, HttpResponse<?> response) {
        Optional<Duration> retryAfter = response == null
                ? Optional.empty()
                : response.headers().firstValue("Retry-After").flatMap(TrelloClient::parseRetryAfter);
        if (retryAfter.isPresent()) {
            return retryAfter.get();
        }
        long base = config.tracker().apiRetryBaseDelay().toMillis();
        long jitter = ThreadLocalRandom.current().nextLong(Math.max(1L, base));
        return Duration.ofMillis((base * (1L << Math.min(attempt - 1, 8))) + jitter);
    }

    private static Optional<Duration> parseRetryAfter(String value) {
        try {
            return Optional.of(Duration.ofSeconds(Long.parseLong(value)));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TrelloException("trello_api_request", "Trello retry sleep interrupted", e);
        }
    }

    private static URI uri(EffectiveConfig config, String path, Map<String, String> query) {
        String normalizedPath = path.startsWith("/") ? path.substring(1) : path;
        if (normalizedPath.contains("..") || normalizedPath.contains("?") || normalizedPath.contains("#")) {
            throw new TrelloException("trello_unsupported_operation", "Invalid Trello API path");
        }
        String queryString = query.entrySet().stream()
                .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                .collect(Collectors.joining("&"));
        String endpoint = config.tracker().endpoint().replaceAll("/+$", "");
        return URI.create(endpoint + "/" + normalizedPath + (queryString.isBlank() ? "" : "?" + queryString));
    }

    private static String authorization(EffectiveConfig config) {
        return authorization(config.tracker().apiKey(), config.tracker().apiToken());
    }

    public static String authorization(String apiKey, String apiToken) {
        return "OAuth oauth_consumer_key=\"%s\", oauth_token=\"%s\"".formatted(apiKey, apiToken);
    }

    private BoardList toList(Map<String, Object> payload) {
        return new BoardList(
                requiredString(payload, "id", "trello_unknown_payload"),
                requiredString(payload, "name", "trello_unknown_payload"),
                bool(payload.get("closed")));
    }

    private static String identifier(EffectiveConfig config, Map<String, Object> payload) {
        String shortLink = string(payload.get("shortLink"));
        String id = string(payload.get("id"));
        if (!blank(shortLink)) {
            return config.tracker().cardIdentifierPrefix() + "-" + shortLink;
        }
        return blank(id) ? null : config.tracker().cardIdentifierPrefix() + "-" + id;
    }

    @SuppressWarnings("unchecked")
    private static List<String> labels(Map<String, Object> payload) {
        Object labels = payload.get("labels");
        if (!(labels instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .filter(Map.class::isInstance)
                .map(label -> string(((Map<String, Object>) label).get("name")))
                .filter(label -> !blank(label))
                .map(StateNames::normalize)
                .toList();
    }

    @SuppressWarnings("unchecked")
    private static List<String> labelIds(Map<String, Object> payload) {
        Object labels = payload.get("labels");
        if (!(labels instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .filter(Map.class::isInstance)
                .map(label -> string(((Map<String, Object>) label).get("id")))
                .filter(label -> !blank(label))
                .toList();
    }

    private static Instant createdAtFromObjectId(String id) {
        if (id == null || id.length() < 8) {
            return null;
        }
        try {
            long epochSeconds = Long.parseLong(id.substring(0, 8), 16);
            return Instant.ofEpochSecond(epochSeconds);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Instant instant(Object value) {
        if (value == null || value.toString().isBlank()) {
            return null;
        }
        return Instant.parse(value.toString());
    }

    private static BigDecimal decimal(Object value) {
        if (value == null) {
            return null;
        }
        return new BigDecimal(value.toString());
    }

    private static Integer integer(Object value) {
        if (value == null) {
            return null;
        }
        return value instanceof Number number ? number.intValue() : Integer.parseInt(value.toString());
    }

    private static Boolean nullableBool(Object value) {
        return value == null ? null : bool(value);
    }

    private static boolean bool(Object value) {
        return value != null && Boolean.parseBoolean(value.toString());
    }

    private static String requiredString(Map<String, Object> payload, String key, String errorCode) {
        String value = string(payload.get(key));
        if (blank(value)) {
            throw new TrelloException(errorCode, "Trello payload is missing " + key);
        }
        return value;
    }

    private static String string(Object value) {
        return value == null ? null : value.toString();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String encodeSegment(String value) {
        return encode(value).replace("+", "%20");
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private record BoardContext(String boardId, boolean boardClosed, Map<String, BoardList> lists) {}

    public record BoardList(String id, String name, boolean closed) {}
}
