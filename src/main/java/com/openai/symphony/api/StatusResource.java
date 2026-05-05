package com.openai.symphony.api;

import com.openai.symphony.orchestrator.SymphonyOrchestrator;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.Instant;
import java.util.Map;

@Path("/")
public class StatusResource {
    private final SymphonyOrchestrator orchestrator;

    public StatusResource(SymphonyOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
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
        return orchestrator.snapshot();
    }

    @GET
    @Path("/api/v1/{cardIdentifier}")
    @Produces(MediaType.APPLICATION_JSON)
    public Object card(@PathParam("cardIdentifier") String cardIdentifier) {
        return orchestrator
                .cardDetails(cardIdentifier)
                .orElseThrow(() -> new NotFoundException("Unknown card: " + cardIdentifier));
    }

    @POST
    @Path("/api/v1/refresh")
    @Produces(MediaType.APPLICATION_JSON)
    public Response refresh() {
        orchestrator.requestRefresh();
        return Response.accepted(Map.of(
                        "queued", true, "coalesced", false, "requested_at", Instant.now(), "operations", new String[] {
                            "poll", "reconcile"
                        }))
                .build();
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
