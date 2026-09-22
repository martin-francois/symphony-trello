package ch.fmartin.symphony.trello.telemetry;

import ch.fmartin.symphony.trello.TestHttpExchange;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.base.Splitter;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// An in-memory stand-in for the PostHog capture and management endpoints the erasure experiment
/// uses. It models only what the safety tests need: profiles per distinct ID, events, queued
/// deletions with a status row, and the two query shapes the harness sends. Switches make it
/// misbehave in the ways the harness must refuse or wait on.
final class FakePostHog implements AutoCloseable {
    static final long PROJECT_ID = 424242;
    static final String PROJECT_NAME = "Symphony for Trello (fake)";
    static final String TOKEN = "phc_" + "faketoken0".repeat(4) + "0123";
    static final String KEY = "phx_fake_personal_key_0123456789";
    private static final int HTTP_OK = 200;
    private static final int HTTP_ACCEPTED = 202;
    private static final int HTTP_UNAUTHORIZED = 401;
    private static final int HTTP_FORBIDDEN = 403;
    private static final int HTTP_NOT_FOUND = 404;
    private static final Pattern IN_LIST = Pattern.compile("IN \\(([^)]*)\\)");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpServer server;
    private final Map<String, Person> persons = new LinkedHashMap<>();
    private final List<Event> events = new ArrayList<>();
    private final Map<String, Boolean> deletions = new LinkedHashMap<>();
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private long nextPersonId = 1;
    volatile boolean completeDeletionsImmediately = true;
    volatile boolean emptyDeletionStatus;
    volatile boolean deletionErrors;
    volatile boolean personsForbidden;
    volatile boolean staleQueries;
    volatile String projectName = PROJECT_NAME;
    volatile String mergedDistinctId = "";

    FakePostHog() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/i/v0/e/", this::capture);
        server.createContext("/api/projects/", this::management);
        server.start();
    }

    URI host() {
        return URI.create("http://" + server.getAddress().getHostString() + ":"
                + server.getAddress().getPort());
    }

    URI captureEndpoint() {
        return host().resolve("/i/v0/e/");
    }

    List<String> requests() {
        return requests;
    }

    long count(String prefix) {
        return requests.stream().filter(request -> request.startsWith(prefix)).count();
    }

    synchronized List<String> eventUuids(String distinctId) {
        return events.stream()
                .filter(event -> event.distinctId().equals(distinctId))
                .map(Event::uuid)
                .toList();
    }

    /// Finishes every queued deletion: events go away and the status rows turn completed.
    synchronized void completeDeletions() {
        List<String> deletedPersons = persons.values().stream()
                .filter(Person::deleted)
                .map(Person::uuid)
                .toList();
        events.removeIf(event -> deletedPersons.contains(event.personUuid()) && event.capturedBeforeDeletion());
        deletions.replaceAll((uuid, done) -> true);
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void capture(HttpExchange exchange) throws IOException {
        requests.add("capture");
        JsonNode body = JSON.readTree(exchange.getRequestBody().readAllBytes());
        if (!TOKEN.equals(body.path("api_key").asText())) {
            TestHttpExchange.respond(exchange, HTTP_UNAUTHORIZED, "{\"type\":\"authentication_error\"}");
            return;
        }
        synchronized (this) {
            String distinctId = body.path("distinct_id").asText();
            Person person = persons.get(distinctId);
            if (person == null || person.deleted()) {
                person = new Person(UUID.randomUUID().toString(), nextPersonId, distinctId, false);
                nextPersonId++;
                persons.put(distinctId, person);
            }
            events.add(new Event(
                    body.path("uuid").asText(),
                    distinctId,
                    person.uuid(),
                    body.path("timestamp").asText(),
                    false));
        }
        TestHttpExchange.respond(exchange, HTTP_OK, "{\"status\":\"Ok\"}");
    }

    private void management(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        requests.add(method + " " + path);
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        if (!("Bearer " + KEY).equals(authorization)) {
            TestHttpExchange.respond(exchange, HTTP_UNAUTHORIZED, "{\"detail\":\"Invalid personal API key.\"}");
            return;
        }
        String prefix = "/api/projects/" + PROJECT_ID + "/";
        if (!path.startsWith(prefix)) {
            TestHttpExchange.respond(exchange, HTTP_NOT_FOUND, "{\"detail\":\"Not found.\"}");
            return;
        }
        String rest = path.substring(prefix.length());
        JsonNode body =
                "POST".equals(method) ? JSON.readTree(exchange.getRequestBody().readAllBytes()) : JSON.nullNode();
        synchronized (this) {
            switch (rest) {
                case "" -> TestHttpExchange.respond(exchange, HTTP_OK, project());
                case "persons/" -> persons(exchange);
                case "persons/bulk_delete/" -> bulkDelete(exchange, body);
                case "persons/deletion_status/" -> deletionStatus(exchange);
                case "persons/reset_person_distinct_id/" -> TestHttpExchange.respond(exchange, HTTP_ACCEPTED, "");
                case "query/" -> query(exchange, body);
                default -> TestHttpExchange.respond(exchange, HTTP_NOT_FOUND, "{\"detail\":\"Not found.\"}");
            }
        }
    }

    private String project() {
        ObjectNode node = JSON.createObjectNode();
        node.put("id", PROJECT_ID).put("name", projectName).put("api_token", TOKEN);
        return node.toString();
    }

    private void persons(HttpExchange exchange) throws IOException {
        if (personsForbidden) {
            TestHttpExchange.respond(exchange, HTTP_FORBIDDEN, "{\"detail\":\"API key missing scope person:read\"}");
            return;
        }
        String distinctId = TestHttpExchange.query(exchange).getOrDefault("distinct_id", "");
        ArrayNode results = JSON.createArrayNode();
        Person person = persons.get(distinctId);
        if (person != null && !person.deleted()) {
            results.add(personNode(person));
        }
        TestHttpExchange.respond(exchange, HTTP_OK, paginated(results));
    }

    private ObjectNode personNode(Person person) {
        ObjectNode node = JSON.createObjectNode();
        node.put("id", person.id()).put("uuid", person.uuid()).put("name", person.distinctId());
        ArrayNode ids = node.putArray("distinct_ids");
        ids.add(person.distinctId());
        if (person.distinctId().equals(mergedDistinctId)) {
            ids.add("merged-" + person.distinctId());
        }
        return node;
    }

    private void bulkDelete(HttpExchange exchange, JsonNode body) throws IOException {
        List<Person> found = new ArrayList<>();
        for (JsonNode id : body.path("distinct_ids")) {
            Person person = persons.get(id.asText());
            if (person != null && !person.deleted()) {
                found.add(person);
            }
        }
        ObjectNode response = JSON.createObjectNode();
        response.put("persons_found", found.size());
        ArrayNode errors = response.putArray("deletion_errors");
        if (deletionErrors) {
            response.put("persons_deleted", 0)
                    .put("persons_queued_for_deletion", 0)
                    .put("events_queued_for_deletion", false);
            errors.addObject()
                    .put("person_id", found.isEmpty() ? "" : found.getFirst().uuid())
                    .put("step", "delete_clickhouse");
            TestHttpExchange.respond(exchange, HTTP_ACCEPTED, response.toString());
            return;
        }
        for (Person person : found) {
            persons.put(person.distinctId(), new Person(person.uuid(), person.id(), person.distinctId(), true));
            deletions.put(person.uuid(), false);
            events.replaceAll(event -> event.personUuid().equals(person.uuid())
                    ? new Event(event.uuid(), event.distinctId(), event.personUuid(), event.timestamp(), true)
                    : event);
        }
        response.put("persons_deleted", 0)
                .put("persons_queued_for_deletion", found.size())
                .put("events_queued_for_deletion", body.path("delete_events").asBoolean(false));
        if (completeDeletionsImmediately) {
            completeDeletions();
        }
        TestHttpExchange.respond(exchange, HTTP_ACCEPTED, response.toString());
    }

    private void deletionStatus(HttpExchange exchange) throws IOException {
        String personUuid = TestHttpExchange.query(exchange).getOrDefault("person_uuid", "");
        ArrayNode results = JSON.createArrayNode();
        Boolean done = deletions.get(personUuid);
        if (done != null && !emptyDeletionStatus) {
            results.addObject()
                    .put("person_uuid", personUuid)
                    .put("status", done ? "completed" : "pending")
                    .put("delete_verified_at", done ? "2026-09-27T05:00:00Z" : null);
        }
        TestHttpExchange.respond(exchange, HTTP_OK, paginated(results));
    }

    private void query(HttpExchange exchange, JsonNode body) throws IOException {
        String hogql = body.path("query").path("query").asText();
        ObjectNode response = JSON.createObjectNode();
        response.put("is_cached", staleQueries);
        ArrayNode results = response.putArray("results");
        List<String> ids = inList(hogql);
        if (hogql.contains("FROM persons")) {
            persons.values().stream()
                    .filter(person -> !person.deleted() && ids.contains(person.uuid()))
                    .forEach(person -> results.addArray().add(person.uuid()));
        } else {
            events.stream().filter(event -> ids.contains(event.distinctId())).forEach(event -> results.addArray()
                    .add(event.distinctId())
                    .add(event.uuid())
                    .add(event.personUuid())
                    .add(event.timestamp()));
        }
        TestHttpExchange.respond(exchange, HTTP_OK, response.toString());
    }

    private static List<String> inList(String hogql) {
        Matcher matcher = IN_LIST.matcher(hogql);
        List<String> values = new ArrayList<>();
        if (matcher.find()) {
            for (String value : Splitter.on(',').split(matcher.group(1))) {
                values.add(value.strip().replace("'", ""));
            }
        }
        return values;
    }

    private static String paginated(ArrayNode results) {
        ObjectNode node = JSON.createObjectNode();
        node.put("count", results.size()).putNull("next").putNull("previous").set("results", results);
        return node.toString();
    }

    private record Person(String uuid, long id, String distinctId, boolean deleted) {}

    private record Event(
            String uuid, String distinctId, String personUuid, String timestamp, boolean capturedBeforeDeletion) {}
}
