package ch.fmartin.symphony.trello.api;

import ch.fmartin.symphony.trello.orchestrator.SymphonyOrchestrator;
import ch.fmartin.symphony.trello.time.ApplicationClock;
import io.vertx.ext.web.RoutingContext;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.OPTIONS;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;

@Path("/")
public class StatusResource {
    // Without explicit OPTIONS methods, RESTEasy Reactive advertises the union of every method on
    // the resource class in Allow, which wrongly offers POST on the read-only routes.
    private static final String READ_ONLY_ALLOW = "GET, HEAD, OPTIONS";
    private static final String REFRESH_ALLOW = "OPTIONS, POST";

    private final SymphonyOrchestrator orchestrator;
    private final BooleanSupplier loopbackClient;
    private final Clock clock;

    @Context
    RoutingContext routingContext;

    public StatusResource(SymphonyOrchestrator orchestrator) {
        this(orchestrator, null, ApplicationClock.systemUtc());
    }

    @Inject
    public StatusResource(SymphonyOrchestrator orchestrator, Clock clock) {
        this(orchestrator, null, clock);
    }

    StatusResource(SymphonyOrchestrator orchestrator, BooleanSupplier loopbackClient) {
        this(orchestrator, loopbackClient, ApplicationClock.systemUtc());
    }

    StatusResource(SymphonyOrchestrator orchestrator, BooleanSupplier loopbackClient, Clock clock) {
        this.orchestrator = orchestrator;
        this.loopbackClient = loopbackClient;
        this.clock = clock;
    }

    @GET
    @Produces(MediaType.TEXT_HTML)
    public String index() {
        var snapshot = orchestrator.snapshot();
        return """
                <!doctype html>
                <html lang="en">
                <head>
                  <meta charset="utf-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1">
                  <title>Symphony for Trello</title>
                  <style>
                    body { font: 14px system-ui, sans-serif; margin: 2rem; color: #1f2937; }
                    table { border-collapse: collapse; width: 100%%; margin-top: 1rem; }
                    th, td { border-bottom: 1px solid #d1d5db; padding: .5rem; text-align: left; }
                    code { background: #f3f4f6; padding: .1rem .25rem; border-radius: 3px; }
                  </style>
                </head>
                <body>
                  <h1>Symphony for Trello</h1>
                  <p><code>%s</code> running, <code>%s</code> retrying.</p>
                  <table>
                    <thead><tr><th>Card</th><th>State</th><th>Session</th><th>Last event</th><th>Turns</th></tr></thead>
                    <tbody>%s</tbody>
                  </table>
                </body>
                </html>
                """
                .formatted(
                        snapshot.counts().running(),
                        snapshot.counts().retrying(),
                        snapshot.running().stream()
                                .map(row -> "<tr><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>"
                                        .formatted(
                                                escape(row.cardIdentifier()),
                                                escape(row.state()),
                                                escape(row.sessionId()),
                                                escape(row.lastEvent()),
                                                row.turnCount()))
                                .reduce("", String::concat));
    }

    @GET
    @Path("/api/v1/state")
    @Produces(MediaType.APPLICATION_JSON)
    public Object state() {
        return StateSnapshotResponse.from(orchestrator.snapshot());
    }

    @GET
    @Path("/api/v1/local-status")
    @Produces(MediaType.APPLICATION_JSON)
    public Object localStatus() {
        if (!isLoopbackClient()) {
            throw new NotFoundException();
        }
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("boardId", orchestrator.selectedBoardId());
        status.put("configuredBoardId", orchestrator.selectedConfiguredBoardId());
        status.put("workflowPath", orchestrator.selectedWorkflowPath().toString());
        // Lifecycle commands use this pid to repair missing managed pid metadata for healthy
        // untracked workers.
        status.put("pid", ProcessHandle.current().pid());
        return status;
    }

    @GET
    @Path("/api/v1/{cardIdentifier}")
    @Produces(MediaType.APPLICATION_JSON)
    public Object card(@PathParam("cardIdentifier") String cardIdentifier) {
        // The template also catches arbitrary unknown /api/v1 segments, and only an intentional
        // card-details lookup may report card_not_found: the segment must look like one of this
        // worker's card identifiers (configured prefix plus short link or id, or the raw
        // 24-character Trello card id fallback) before a miss counts as a missing card.
        if (!looksLikeCardIdentifier(cardIdentifier)) {
            throw new NotFoundException("Unknown local API route.");
        }
        return orchestrator
                .cardDetails(cardIdentifier)
                .map(CardDebugDetailsResponse::from)
                .orElseThrow(() -> new CardNotFoundException("Unknown card: " + cardIdentifier));
    }

    private boolean looksLikeCardIdentifier(String segment) {
        String prefix = orchestrator.cardIdentifierPrefix() + "-";
        if (segment.startsWith(prefix)) {
            String suffix = segment.substring(prefix.length());
            return !suffix.isEmpty() && suffix.chars().allMatch(Character::isLetterOrDigit);
        }
        return segment.matches("[0-9a-fA-F]{24}");
    }

    @OPTIONS
    public Response indexOptions() {
        return allowOnly(READ_ONLY_ALLOW);
    }

    @OPTIONS
    @Path("/api/v1/state")
    public Response stateOptions() {
        return allowOnly(READ_ONLY_ALLOW);
    }

    @OPTIONS
    @Path("/api/v1/local-status")
    public Response localStatusOptions() {
        // The local-status payload is hidden from non-loopback clients, so its OPTIONS answer
        // must not reveal the endpoint either.
        if (!isLoopbackClient()) {
            throw new NotFoundException();
        }
        return allowOnly(READ_ONLY_ALLOW);
    }

    @OPTIONS
    @Path("/api/v1/refresh")
    public Response refreshOptions() {
        return allowOnly(REFRESH_ALLOW);
    }

    private static Response allowOnly(String allow) {
        return Response.ok().header(HttpHeaders.ALLOW, allow).build();
    }

    @POST
    @Path("/api/v1/refresh")
    @Produces(MediaType.APPLICATION_JSON)
    public Response refresh() {
        orchestrator.requestRefresh();
        return Response.accepted(Map.of(
                        "queued",
                        true,
                        "coalesced",
                        false,
                        "requested_at",
                        clock.instant(),
                        "operations",
                        new String[] {"poll", "reconcile"}))
                .build();
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private boolean isLoopbackClient() {
        if (loopbackClient != null) {
            return loopbackClient.getAsBoolean();
        }
        if (routingContext == null
                || routingContext.request() == null
                || routingContext.request().remoteAddress() == null) {
            return false;
        }
        String hostAddress = routingContext.request().remoteAddress().hostAddress();
        if (hostAddress == null || hostAddress.isBlank()) {
            return false;
        }
        try {
            return InetAddress.getByName(hostAddress).isLoopbackAddress();
        } catch (UnknownHostException ignored) {
            return false;
        }
    }
}
