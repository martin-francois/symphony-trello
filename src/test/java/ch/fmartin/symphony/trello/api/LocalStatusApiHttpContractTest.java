package ch.fmartin.symphony.trello.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Drives the real HTTP routing stack instead of calling the exception mapper directly, so the
 * public local API contract for unsupported methods is proven end to end. The Quarkus test
 * extension boots one shared application and its scope manager does not support concurrent test
 * methods, so this class opts out of parallel execution.
 */
@Execution(ExecutionMode.SAME_THREAD)
@QuarkusTest
final class LocalStatusApiHttpContractTest {

    @MethodSource("unsupportedMethodRequests")
    @ParameterizedTest
    void unsupportedMethodsReturnMethodNotAllowedJson(String method, String path) {
        // given
        String unsupportedMethod = method;

        // when
        Response response = given().request(unsupportedMethod, path);

        // then
        response.then().statusCode(405).body("error.code", equalTo("method_not_allowed"));
    }

    private static Stream<Arguments> unsupportedMethodRequests() {
        return Stream.of(
                Arguments.of("POST", "/api/v1/state"),
                Arguments.of("PUT", "/api/v1/state"),
                Arguments.of("DELETE", "/api/v1/state"),
                Arguments.of("PATCH", "/api/v1/state"),
                Arguments.of("POST", "/api/v1/local-status"),
                Arguments.of("PUT", "/api/v1/local-status"),
                Arguments.of("DELETE", "/api/v1/local-status"),
                Arguments.of("PATCH", "/api/v1/local-status"),
                Arguments.of("PUT", "/api/v1/refresh"),
                Arguments.of("DELETE", "/api/v1/refresh"),
                Arguments.of("PATCH", "/api/v1/refresh"),
                Arguments.of("POST", "/"),
                Arguments.of("DELETE", "/"));
    }

    @ParameterizedTest
    @Timeout(10)
    @ValueSource(strings = {"/", "/api/v1/state", "/api/v1/local-status"})
    void headOnReadOnlyRoutesCompletesWithoutBody(String path) {
        // given
        String headPath = path;

        // when
        Response response = given().head(headPath);

        // then
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body().asByteArray()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/", "/api/v1/state", "/api/v1/local-status"})
    void optionsOnReadOnlyRoutesAnswersWithExactReadMethodAllowHeader(String path) {
        // given
        String optionsPath = path;

        // when
        Response response = given().options(optionsPath);

        // then
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.header("Allow"))
                .as("a read-only route must not advertise POST or other write methods")
                .isEqualTo("GET, HEAD, OPTIONS");
    }

    @Test
    void optionsOnRefreshAnswersWithItsOwnAllowHeader() {
        // given
        String path = "/api/v1/refresh";

        // when
        Response response = given().options(path);

        // then
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.header("Allow")).isEqualTo("OPTIONS, POST");
    }

    @Test
    void unknownCardLookupKeepsItsNotFoundMapping() {
        // given
        String path = "/api/v1/UNKNOWN-card";

        // when
        Response response = given().get(path);

        // then
        response.then().statusCode(404).body("error.code", equalTo("card_not_found"));
    }
}
