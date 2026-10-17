package ch.fmartin.symphony.trello.tracker;

import static ch.fmartin.symphony.trello.tracker.FakeTrelloCommentBoard.DONE_LIST;
import static ch.fmartin.symphony.trello.tracker.FakeTrelloCommentBoard.PROGRESS_LIST;
import static ch.fmartin.symphony.trello.tracker.FakeTrelloCommentBoard.TODO_LIST;
import static ch.fmartin.symphony.trello.tracker.FakeTrelloCommentBoard.cardUrl;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.STRING;

import ch.fmartin.symphony.trello.config.ConfigResolver;
import ch.fmartin.symphony.trello.config.EffectiveConfig;
import ch.fmartin.symphony.trello.tracker.FakeTrelloCommentBoard.FakeCard;
import ch.fmartin.symphony.trello.workflow.WorkflowDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// Covers how often the candidate poll reads comment actions to keep the managed prerequisite
/// status comment truthful, across several poll ticks against a stateful fake board.
final class TrelloClientPrerequisiteStatusLookupTest {
    private static final String STALE_WAITING_STATUS = TrelloClient.WAITING_COMMENT_MARKER + "\n\nOld waiting text";

    private final TrelloClient client = new TrelloClient(new ObjectMapper());
    private FakeTrelloCommentBoard board;
    private EffectiveConfig config;

    @TempDir
    Path tempDir;

    @BeforeEach
    void startBoard() throws Exception {
        board = new FakeTrelloCommentBoard().start();
        Map<String, Object> tracker = new LinkedHashMap<>();
        tracker.put("kind", "trello");
        tracker.put("endpoint", board.endpoint());
        tracker.put("api_key", "key");
        tracker.put("api_token", "token");
        tracker.put("board_id", FakeTrelloCommentBoard.BOARD_INPUT);
        tracker.put("active_states", List.of("Todo"));
        tracker.put("terminal_states", List.of("Done"));
        tracker.put("blocker_enforced_states", List.of("Todo"));
        config = new ConfigResolver()
                .resolve(new WorkflowDefinition(tempDir.resolve("WORKFLOW.md"), Map.of("tracker", tracker), ""))
                .withResolvedBoardId(FakeTrelloCommentBoard.BOARD_ID);
    }

    @AfterEach
    void stopBoard() {
        board.close();
    }

    @Test
    void waitingStatusIsWrittenOnceAndClearedAfterPrerequisitesAreRemovedWithoutRepeatedLookups() {
        // given
        FakeCard prerequisite = board.addCard("prerequisite", TODO_LIST);
        FakeCard card = board.addCard("waiting", TODO_LIST).withComments(2).withChecklistItem(cardUrl(prerequisite));

        // when
        List<List<String>> waitingTicks = List.of(pollTick(), pollTick(), pollTick());
        card.withoutChecklistItems();
        List<List<String>> clearedTicks = List.of(pollTick(), pollTick(), pollTick());

        // then
        assertThat(waitingTicks)
                .as("while the card waits: look up and write, verify the write, then reuse the answer")
                .satisfiesExactly(
                        tick -> assertThat(tick)
                                .filteredOn(statusLookupOf(card))
                                .hasSize(1),
                        tick -> assertThat(tick)
                                .filteredOn(statusLookupOf(card))
                                .hasSize(1),
                        tick -> assertThat(tick)
                                .filteredOn(statusLookupOf(card))
                                .isEmpty());
        assertThat(waitingTicks.getFirst())
                .filteredOn(TrelloClientPrerequisiteStatusLookupTest::isWrite)
                .singleElement(STRING)
                .startsWith("POST /1/cards/waiting/actions/comments?");
        assertThat(clearedTicks)
                .as("after the prerequisite items were removed: update the remembered comment, verify, reuse")
                .satisfiesExactly(
                        tick -> assertThat(tick)
                                .filteredOn(statusLookupOf(card))
                                .isEmpty(),
                        tick -> assertThat(tick)
                                .filteredOn(statusLookupOf(card))
                                .hasSize(1),
                        tick -> assertThat(tick)
                                .filteredOn(statusLookupOf(card))
                                .isEmpty());
        assertThat(clearedTicks.getFirst())
                .filteredOn(TrelloClientPrerequisiteStatusLookupTest::isWrite)
                .singleElement(STRING)
                .startsWith("PUT /1/actions/");
        assertThat(card.commentTexts())
                .filteredOn(text -> text.startsWith(TrelloClient.PREREQUISITE_STATUS_COMMENT_MARKER))
                .singleElement(STRING)
                .contains("Status: prerequisites resolved.")
                .doesNotContain("waiting for prerequisites");
    }

    @Test
    void staleWaitingMarkerAddedAfterAnEarlierLookupIsClearedOnTheNextTick() {
        // given
        FakeCard card = board.addCard("ready", TODO_LIST).withComments(3);

        // when
        List<String> firstTick = pollTick();
        List<String> unchangedTick = pollTick();
        // Adding a comment changes the comment badge, for example a status written by an older run.
        card.withComment(STALE_WAITING_STATUS);
        List<String> tickAfterNewComment = pollTick();

        // then
        assertThat(firstTick).filteredOn(statusLookupOf(card)).hasSize(1);
        assertThat(unchangedTick).filteredOn(statusLookupOf(card)).isEmpty();
        assertThat(tickAfterNewComment).filteredOn(statusLookupOf(card)).hasSize(1);
        assertThat(card.commentTexts())
                .first(STRING)
                .startsWith(TrelloClient.PREREQUISITE_STATUS_COMMENT_MARKER)
                .contains("Status: prerequisites resolved.");
    }

    @Test
    void rateLimitedStatusWriteIsRetriedWithAFreshLookupOnTheNextTick() {
        // given
        FakeCard card = board.addCard("ready", TODO_LIST).withComments(2).withComment(STALE_WAITING_STATUS);

        // when
        board.rejectCommentWrites(true);
        List<String> rateLimitedTick = pollTick();
        board.rejectCommentWrites(false);
        List<String> nextTick = pollTick();

        // then
        assertThat(rateLimitedTick)
                .as("the poll survives the rejected write")
                .filteredOn(TrelloClientPrerequisiteStatusLookupTest::isWrite)
                .singleElement(STRING)
                .startsWith("PUT /1/actions/");
        assertThat(nextTick)
                .as("the rejected write does not leave a remembered answer behind")
                .filteredOn(statusLookupOf(card))
                .hasSize(1);
        assertThat(nextTick)
                .filteredOn(TrelloClientPrerequisiteStatusLookupTest::isWrite)
                .singleElement(STRING)
                .startsWith("PUT /1/actions/");
        assertThat(card.commentTexts()).first(STRING).contains("Status: prerequisites resolved.");
    }

    @Test
    void statusCommentsOfInactiveTerminalOrCommentlessCardsAreNeverLookedUp() {
        // given
        FakeCard inactive = board.addCard("inactive", PROGRESS_LIST).withComment(STALE_WAITING_STATUS);
        FakeCard terminal = board.addCard("terminal", DONE_LIST).withComment(STALE_WAITING_STATUS);
        FakeCard withoutComments =
                board.addCard("ordinary-checklist", TODO_LIST).withChecklistItem("Write docs");

        // when
        List<String> requests = pollTick();

        // then
        assertThat(requests)
                .noneMatch(statusLookupOf(inactive))
                .noneMatch(statusLookupOf(terminal))
                .noneMatch(statusLookupOf(withoutComments));
        assertThat(inactive.commentTexts()).containsExactly(STALE_WAITING_STATUS);
        assertThat(terminal.commentTexts()).containsExactly(STALE_WAITING_STATUS);
        assertThat(requests).noneMatch(TrelloClientPrerequisiteStatusLookupTest::isWrite);
    }

    private List<String> pollTick() {
        board.clearRequests();
        client.fetchCandidateCards(config);
        return board.requests();
    }

    /// Matches the deep comment read that looks for the card's managed status comment.
    private static Predicate<String> statusLookupOf(FakeCard card) {
        return request -> request.startsWith("GET /1/cards/" + card.id() + "?")
                && request.contains("actions_limit=" + TrelloClient.WORKPAD_COMMENT_ACTION_LIMIT);
    }

    private static boolean isWrite(String request) {
        return !request.startsWith("GET ");
    }
}
