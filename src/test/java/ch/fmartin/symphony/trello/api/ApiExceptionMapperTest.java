package ch.fmartin.symphony.trello.api;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.ws.rs.NotAllowedException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Response;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class ApiExceptionMapperTest {
    private final ApiExceptionMapper mapper = new ApiExceptionMapper();

    @Test
    void mapsUnsupportedMethodsToMethodNotAllowed() {
        // given
        var methodNotAllowed = new NotAllowedException("HTTP 405 Method Not Allowed", "GET", new String[0]);

        // when
        try (Response response = mapper.toResponse(methodNotAllowed)) {

            // then
            assertThat(response.getStatus()).isEqualTo(Response.Status.METHOD_NOT_ALLOWED.getStatusCode());
            assertThat(errorCode(response)).isEqualTo("method_not_allowed");
        }
    }

    @Test
    void mapsUnknownRoutesToNeutralNotFound() {
        // given
        var routeNotFound = new NotFoundException("Unable to find matching target resource method");

        // when
        try (Response response = mapper.toResponse(routeNotFound)) {

            // then
            assertThat(response.getStatus()).isEqualTo(Response.Status.NOT_FOUND.getStatusCode());
            assertThat(errorCode(response)).isEqualTo("not_found");
        }
    }

    @Test
    void keepsNotFoundAsCardNotFound() {
        // given
        var notFound = new CardNotFoundException("Unknown card: card-1");

        // when
        try (Response response = mapper.toResponse(notFound)) {

            // then
            assertThat(response.getStatus()).isEqualTo(Response.Status.NOT_FOUND.getStatusCode());
            assertThat(errorCode(response)).isEqualTo("card_not_found");
        }
    }

    @Test
    void wrapsUnexpectedFailuresAsInternalErrors() {
        // given
        var unexpected = new IllegalStateException("boom");

        // when
        try (Response response = mapper.toResponse(unexpected)) {

            // then
            assertThat(response.getStatus()).isEqualTo(Response.Status.INTERNAL_SERVER_ERROR.getStatusCode());
            assertThat(errorCode(response)).isEqualTo("internal_error");
        }
    }

    private static String errorCode(Response response) {
        Map<?, ?> body = (Map<?, ?>) response.getEntity();
        Map<?, ?> error = (Map<?, ?>) body.get("error");
        return String.valueOf(error.get("code"));
    }
}
