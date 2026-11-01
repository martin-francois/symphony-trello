package ch.fmartin.symphony.trello.setup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

final class CardHelperProtocolTest {
    @MethodSource("operations")
    @ParameterizedTest
    void roundTripsOnlyTheTypedOperationAndBoardSelector(CardCommandRequest.Operation operation) throws Exception {
        // given
        Path trustedConfig = Path.of("trusted-config");
        var request = new CardCommandRequest(
                Optional.of("board-1"),
                Optional.empty(),
                Optional.of(Path.of("untrusted-config")),
                Optional.of(Path.of("untrusted-state")),
                true,
                operation);
        var json = CardHelperProtocol.json();

        // when
        byte[] bytes = json.writeValueAsBytes(CardHelperProtocol.from(request));
        CardCommandRequest received =
                json.readValue(bytes, CardHelperProtocol.Request.class).command(trustedConfig);

        // then
        assertThat(received)
                .isEqualTo(new CardCommandRequest(
                        request.board(),
                        request.workflow(),
                        Optional.of(trustedConfig),
                        Optional.empty(),
                        true,
                        operation));
        assertThat(json.readTree(bytes).has("configDir"))
                .as("the wire request cannot override the helper's credential installation")
                .isFalse();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{\"version\":1,\"operation\":\"list\",\"arguments\":{},\"configDir\":\"elsewhere\"}",
                "{\"version\":1,\"operation\":\"list\",\"operation\":\"archive\",\"arguments\":{}}",
                "{\"version\":1,\"operation\":\"list\",\"arguments\":{}} {}",
                "{\"version\":2,\"operation\":\"list\",\"arguments\":{}}",
                "{\"version\":1,\"board\":\"board\",\"workflow\":\"workflow\",\"operation\":\"list\",\"arguments\":{}}"
            })
    void rejectsAmbiguousOrExtendedWireRequests(String body) {
        // given
        var json = CardHelperProtocol.json();

        // when
        Throwable thrown = catchThrowable(() -> json.readValue(body, CardHelperProtocol.Request.class));

        // then
        assertThat(thrown).isInstanceOf(Exception.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"start", "stop", "setup-local", "disconnect", "GET"})
    void rejectsOperationsOutsideTheCardApi(String operation) {
        // given
        var request = new CardHelperProtocol.Request(1, null, null, true, operation, Map.of());

        // when
        Throwable thrown = catchThrowable(() -> request.command(Path.of("config")));

        // then
        assertThat(thrown)
                .isInstanceOf(TrelloBoardSetupException.class)
                .hasMessageContaining("supported card operation");
    }

    @Test
    void rejectsCredentialAndEndpointOverridesInOperationArguments() {
        // given
        var request = new CardHelperProtocol.Request(1, null, null, true, "list", Map.of("api_key", "secret"));

        // when
        Throwable thrown = catchThrowable(() -> request.command(Path.of("config")));

        // then
        assertThat(thrown).isInstanceOf(TrelloBoardSetupException.class).hasMessageNotContaining("secret");
    }

    private static List<CardCommandRequest.Operation> operations() {
        return List.of(
                new CardCommandRequest.ListCards(Optional.empty()),
                new CardCommandRequest.ListCards(Optional.of("Inbox")),
                new CardCommandRequest.ShowCard("card-1"),
                new CardCommandRequest.CreateCard("Title", "Description", "Inbox"),
                new CardCommandRequest.MoveCard("card-1", "Done"),
                new CardCommandRequest.CommentOnCard("card-1", "Comment"),
                new CardCommandRequest.ArchiveCard("card-1", "Title"));
    }
}
