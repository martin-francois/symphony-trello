package ch.fmartin.symphony.trello.api;

import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import java.util.Locale;
import java.util.Map;

@Provider
public class ApiExceptionMapper implements ExceptionMapper<Throwable> {
    @Override
    public Response toResponse(Throwable exception) {
        if (exception instanceof NotFoundException) {
            // Only card lookups report card_not_found; unknown local routes must not imply a
            // Trello card lookup failed.
            String code = exception instanceof CardNotFoundException ? "card_not_found" : "not_found";
            return Response.status(Response.Status.NOT_FOUND)
                    .type(MediaType.APPLICATION_JSON)
                    .entity(error(code, exception.getMessage()))
                    .build();
        }
        if (exception instanceof WebApplicationException webApplication) {
            // Framework errors such as 405 Method Not Allowed must keep their HTTP status instead
            // of being wrapped as internal server errors.
            int status = webApplication.getResponse().getStatus();
            return Response.status(status)
                    .type(MediaType.APPLICATION_JSON)
                    .entity(error(webApplicationErrorCode(status), exception.getMessage()))
                    .build();
        }
        return Response.serverError()
                .type(MediaType.APPLICATION_JSON)
                .entity(error("internal_error", exception.getMessage()))
                .build();
    }

    private static String webApplicationErrorCode(int status) {
        Response.Status known = Response.Status.fromStatusCode(status);
        if (known == Response.Status.METHOD_NOT_ALLOWED) {
            return "method_not_allowed";
        }
        return known == null ? "http_" + status : known.name().toLowerCase(Locale.ROOT);
    }

    private static Map<String, Object> error(String code, String message) {
        return Map.of("error", Map.of("code", code, "message", message == null ? "" : message));
    }
}
