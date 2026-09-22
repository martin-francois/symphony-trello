package ch.fmartin.symphony.trello.telemetry;

import ch.fmartin.symphony.trello.TestHttpExchange;
import ch.fmartin.symphony.trello.time.ApplicationClock;
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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/// An in-memory stand-in for the PostHog capture and management endpoints the erasure experiment
/// uses. It models only what the safety tests need: profiles per distinct ID, events, queued
/// deletions with dated status rows, the reuse of a deleted ID with a profile that PostHog does or
/// does not rebuild, and the two query shapes the harness sends. Switches make it misbehave in the
/// ways the harness must refuse, wait on, or repair.
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
    private static final Duration STALE_ROW_AGE = Duration.ofDays(2);
    private static final Pattern IN_LIST = Pattern.compile("IN \\(([^)]*)\\)");
    private static final ObjectMapper JSON = new ObjectMapper();

    /// How `delete_verified_at` is rendered on a completed status row.
    enum VerifiedAtMode {
        VALID,
        ABSENT,
        NULL,
        BLANK,
        GARBAGE
    }

    private final HttpServer server;
    private final Map<String, Person> persons = new LinkedHashMap<>();
    private final List<Event> events = new ArrayList<>();
    private final Map<String, DeletionJob> deletions = new LinkedHashMap<>();
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final List<String> resetRequests = new CopyOnWriteArrayList<>();
    private long nextPersonId = 1;
    private int bulkDeleteCalls;
    volatile Clock clock = ApplicationClock.systemUtc();
    volatile boolean completeDeletionsImmediately = true;
    volatile boolean completeCleanupDeletions = true;
    volatile boolean emptyDeletionStatus;
    volatile boolean deletionErrors;
    volatile boolean personsForbidden;
    volatile boolean staleQueries;
    volatile boolean staleCompletedRows;
    volatile boolean wrongPersonRows;
    volatile VerifiedAtMode verifiedAtMode = VerifiedAtMode.VALID;
    volatile @Nullable String deletionStatusBodyOverride;
    volatile @Nullable String queryBodyOverride;
    volatile String projectName = PROJECT_NAME;
    volatile String mergedDistinctId = "";
    /// Reused IDs whose new profile PostHog exposes neither in the person API nor in the persons
    /// table until a reset arrives after a new event.
    final Set<String> brokenReuseDistinctIds = ConcurrentHashMap.newKeySet();
    /// Reused IDs whose profile the person API shows while analytics still lack the mapping.
    final Set<String> analyticsOnlyBrokenDistinctIds = ConcurrentHashMap.newKeySet();
    /// When positive, a broken mapping heals by itself after this many person API reads.
    volatile int resolveBrokenAfterPersonsReads;
    /// When set, a reset marks the profile to heal on the next captured event instead of at once.
    volatile boolean resetHealsOnNextEvent;

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

    /// The distinct IDs reset requests named, in order.
    List<String> resetRequests() {
        return resetRequests;
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
                .filter(person -> person.deleted)
                .map(person -> person.uuid)
                .toList();
        events.removeIf(event -> deletedPersons.contains(event.personUuid()) && event.capturedBeforeDeletion());
        Instant now = clock.instant();
        deletions.replaceAll((uuid, job) -> job.completed(now));
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
            if (person == null || person.deleted) {
                boolean reused = person != null;
                person = new Person(UUID.randomUUID().toString(), nextPersonId, distinctId);
                nextPersonId++;
                if (reused && brokenReuseDistinctIds.contains(distinctId)) {
                    person.hiddenInApi = true;
                    person.hiddenInTable = true;
                }
                if (reused && analyticsOnlyBrokenDistinctIds.contains(distinctId)) {
                    person.hiddenInTable = true;
                }
                persons.put(distinctId, person);
            } else if (person.healOnNextEvent) {
                person.heal();
            }
            events.add(new Event(
                    body.path("uuid").asText(),
                    distinctId,
                    person.uuid,
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
                case "persons/reset_person_distinct_id/" -> reset(exchange, body);
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
        if (person != null && !person.deleted && person.broken() && resolveBrokenAfterPersonsReads > 0) {
            resolveBrokenAfterPersonsReads--;
            if (resolveBrokenAfterPersonsReads == 0) {
                person.heal();
            }
        }
        if (person != null && !person.deleted && !person.hiddenInApi) {
            results.add(personNode(person));
        }
        TestHttpExchange.respond(exchange, HTTP_OK, paginated(results));
    }

    private ObjectNode personNode(Person person) {
        ObjectNode node = JSON.createObjectNode();
        node.put("id", person.id).put("uuid", person.uuid).put("name", person.distinctId);
        ArrayNode ids = node.putArray("distinct_ids");
        ids.add(person.distinctId);
        if (person.distinctId.equals(mergedDistinctId)) {
            ids.add("merged-" + person.distinctId);
        }
        return node;
    }

    /// Mirrors PostHog: a reset finds the ID's current profile and repairs it; with no current
    /// profile (nothing captured since the deletion) it answers 202 and changes nothing.
    private void reset(HttpExchange exchange, JsonNode body) throws IOException {
        String distinctId = body.path("distinct_id").asText();
        resetRequests.add(distinctId);
        Person person = persons.get(distinctId);
        if (person != null && !person.deleted && person.broken()) {
            if (resetHealsOnNextEvent) {
                person.healOnNextEvent = true;
            } else {
                person.heal();
            }
        }
        TestHttpExchange.respond(exchange, HTTP_ACCEPTED, "");
    }

    private void bulkDelete(HttpExchange exchange, JsonNode body) throws IOException {
        bulkDeleteCalls++;
        List<Person> found = new ArrayList<>();
        for (JsonNode id : body.path("distinct_ids")) {
            Person person = persons.get(id.asText());
            if (person != null && !person.deleted && !person.hiddenInApi) {
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
                    .put("person_id", found.isEmpty() ? "" : found.getFirst().uuid)
                    .put("step", "delete_clickhouse");
            TestHttpExchange.respond(exchange, HTTP_ACCEPTED, response.toString());
            return;
        }
        Instant now = clock.instant();
        for (Person person : found) {
            person.deleted = true;
            deletions.put(person.uuid, new DeletionJob(now, null));
            events.replaceAll(event -> event.personUuid().equals(person.uuid)
                    ? new Event(event.uuid(), event.distinctId(), event.personUuid(), event.timestamp(), true)
                    : event);
        }
        response.put("persons_deleted", 0)
                .put("persons_queued_for_deletion", found.size())
                .put("events_queued_for_deletion", body.path("delete_events").asBoolean(false));
        if (completeDeletionsImmediately && (bulkDeleteCalls == 1 || completeCleanupDeletions)) {
            completeDeletions();
        }
        TestHttpExchange.respond(exchange, HTTP_ACCEPTED, response.toString());
    }

    private void deletionStatus(HttpExchange exchange) throws IOException {
        String override = deletionStatusBodyOverride;
        if (override != null) {
            TestHttpExchange.respond(exchange, HTTP_OK, override);
            return;
        }
        String personUuid = TestHttpExchange.query(exchange).getOrDefault("person_uuid", "");
        ArrayNode results = JSON.createArrayNode();
        DeletionJob job = deletions.get(personUuid);
        if (job != null && !emptyDeletionStatus) {
            if (staleCompletedRows) {
                ObjectNode stale = results.addObject();
                stale.put("person_uuid", personUuid)
                        .put("status", "completed")
                        .put("created_at", job.createdAt().minus(STALE_ROW_AGE).toString())
                        .put(
                                "delete_verified_at",
                                job.createdAt()
                                        .minus(STALE_ROW_AGE.dividedBy(2))
                                        .toString());
            }
            ObjectNode row = results.addObject();
            row.put("person_uuid", wrongPersonRows ? UUID.randomUUID().toString() : personUuid)
                    .put("status", job.verifiedAt() == null ? "pending" : "completed")
                    .put("created_at", job.createdAt().toString());
            renderVerifiedAt(row, job.verifiedAt());
        }
        TestHttpExchange.respond(exchange, HTTP_OK, paginated(results));
    }

    private void renderVerifiedAt(ObjectNode row, @Nullable Instant verifiedAt) {
        if (verifiedAt == null) {
            row.putNull("delete_verified_at");
            return;
        }
        switch (verifiedAtMode) {
            case VALID -> row.put("delete_verified_at", verifiedAt.toString());
            case ABSENT -> {
                // The field is left out entirely.
            }
            case NULL -> row.putNull("delete_verified_at");
            case BLANK -> row.put("delete_verified_at", "");
            case GARBAGE -> row.put("delete_verified_at", "soon");
        }
    }

    private void query(HttpExchange exchange, JsonNode body) throws IOException {
        String override = queryBodyOverride;
        if (override != null) {
            TestHttpExchange.respond(exchange, HTTP_OK, override);
            return;
        }
        String hogql = body.path("query").path("query").asText();
        ObjectNode response = JSON.createObjectNode();
        response.put("is_cached", staleQueries);
        ArrayNode results = response.putArray("results");
        List<String> ids = inList(hogql);
        if (hogql.contains("FROM persons")) {
            persons.values().stream()
                    .filter(person -> !person.deleted && !person.hiddenInTable && ids.contains(person.uuid))
                    .forEach(person -> results.addArray().add(person.uuid));
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

    /// A profile as the fake tracks it; mutable because PostHog mutates profiles in place.
    private static final class Person {
        final String uuid;
        final long id;
        final String distinctId;
        boolean deleted;
        boolean hiddenInApi;
        boolean hiddenInTable;
        boolean healOnNextEvent;

        Person(String uuid, long id, String distinctId) {
            this.uuid = uuid;
            this.id = id;
            this.distinctId = distinctId;
        }

        boolean broken() {
            return hiddenInApi || hiddenInTable;
        }

        void heal() {
            hiddenInApi = false;
            hiddenInTable = false;
            healOnNextEvent = false;
        }
    }

    private record Event(
            String uuid, String distinctId, String personUuid, String timestamp, boolean capturedBeforeDeletion) {}

    private record DeletionJob(Instant createdAt, @Nullable Instant verifiedAt) {
        DeletionJob completed(Instant at) {
            return verifiedAt == null ? new DeletionJob(createdAt, at) : this;
        }
    }
}
