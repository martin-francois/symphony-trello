package ch.fmartin.symphony.trello.agent;

import static ch.fmartin.symphony.trello.agent.TrelloHandoffToolFuzzInvariants.MIDDLE_DOT;
import static ch.fmartin.symphony.trello.agent.TrelloHandoffToolFuzzInvariants.assertCallStaysInWriteScope;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import ch.fmartin.symphony.trello.agent.TrelloHandoffToolFuzzInvariants.Execution;
import ch.fmartin.symphony.trello.agent.TrelloHandoffToolFuzzInvariants.Permissions;
import ch.fmartin.symphony.trello.tracker.InMemoryTrelloBoard.Write;
import ch.fmartin.symphony.trello.tracker.InMemoryTrelloBoard.WriteKind;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class TrelloHandoffToolFuzzOracleTest {
    private static final String FOOTER = "_Written by Symphony_";
    private static final String CARD_ID = "card-current";

    @ParameterizedTest
    @ValueSource(
            strings = {
                FOOTER,
                "_Managed by Symphony_",
                "_Managed by Symphony_\n\n" + FOOTER,
                " \n" + FOOTER + "\n ",
                FOOTER + "\n\n" + FOOTER,
                FOOTER + " \n \n_Written by Symphony " + MIDDLE_DOT + " detail_",
                "_Written by Symphony " + MIDDLE_DOT + " [Source](https://example.test/card)_"
            })
    void acceptsFooterOnlyRefusalsWithoutWrites(String text) {
        // given
        List<Execution> executions = footerRefusals(text);

        // when
        List<Throwable> violations = violations(executions);

        // then
        assertThat(violations).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "Body",
                "Body\n\n" + FOOTER,
                FOOTER + "\n" + FOOTER,
                FOOTER + "\n\n " + FOOTER,
                "_Written by Symphony " + MIDDLE_DOT + " _detail_",
                "_Written by Symphony " + MIDDLE_DOT + " detail _",
                "_Written by Symphony " + MIDDLE_DOT + " detail\rinside_"
            })
    void rejectsFooterOnlyCodesForContentOrNoncanonicalFooters(String text) {
        // given
        List<Execution> executions = footerRefusals(text);

        // when
        List<Throwable> violations = violations(executions);

        // then
        assertThat(violations).hasSameSizeAs(executions).hasOnlyElementsOfType(AssertionError.class);
    }

    @Test
    void rejectsFooterOnlyCodesFromTheWrongTool() {
        // given
        List<Execution> executions = List.of(
                refusal("trello_upsert_workpad", "invalid_comment_text", FOOTER, List.of()),
                refusal("trello_add_comment", "invalid_workpad_text", FOOTER, List.of()));

        // when
        List<Throwable> violations = violations(executions);

        // then
        assertThat(violations).hasSameSizeAs(executions).hasOnlyElementsOfType(AssertionError.class);
    }

    @Test
    void rejectsFooterOnlyRefusalsAfterAWrite() {
        // given
        var write = new Write(WriteKind.COMMENT_CREATE, CARD_ID, CARD_ID, "Body", null, true);
        Execution execution = refusal("trello_add_comment", "invalid_comment_text", FOOTER, List.of(write));

        // when
        Throwable violation = catchThrowable(() -> assertCallStaysInWriteScope(execution));

        // then
        assertThat(violation).isInstanceOf(AssertionError.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Written", "Managed"})
    void acceptsUpdatesToRecheckCommentsWithEitherAttribution(String attribution) {
        // given
        Execution execution = recheckUpdate(recheckText(attribution, "abc123"));

        // when
        Throwable violation = catchThrowable(() -> assertCallStaysInWriteScope(execution));

        // then
        assertThat(violation).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Written", "Managed"})
    void rejectsRecheckUpdatesLinkedToAnotherCard(String attribution) {
        // given
        Execution execution = recheckUpdate(recheckText(attribution, "other123"));

        // when
        Throwable violation = catchThrowable(() -> assertCallStaysInWriteScope(execution));

        // then
        assertThat(violation).isInstanceOf(AssertionError.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Previously written", "Previously managed"})
    void rejectsRecheckUpdatesWithLookalikeAttribution(String attribution) {
        // given
        Execution execution = recheckUpdate(recheckText(attribution, "abc123"));

        // when
        Throwable violation = catchThrowable(() -> assertCallStaysInWriteScope(execution));

        // then
        assertThat(violation).isInstanceOf(AssertionError.class);
    }

    private static Execution recheckUpdate(String previousText) {
        var nodes = JsonNodeFactory.instance;
        var result = nodes.objectNode().put("success", true);
        result.putArray("contentItems")
                .addObject()
                .put("type", "inputText")
                .put("text", nodes.objectNode().put("card_id", CARD_ID).toString());
        var write = new Write(
                WriteKind.COMMENT_UPDATE,
                CARD_ID,
                "recheck-action",
                recheckText("Written", "abc123"),
                previousText,
                true);
        return new Execution(
                new Permissions(true, true, true, true, true, false, List.of(), List.of()),
                List.of(),
                CARD_ID,
                "abc123",
                "trello_update_blocker_recheck_status",
                nodes.objectNode().put("status", "checking"),
                List.of(previousText),
                result,
                List.of(write),
                false);
    }

    private static String recheckText(String attribution, String shortLink) {
        return "Checking whether this card is still blocked...\n\n_" + attribution + " by Symphony " + MIDDLE_DOT
                + " [View the comment explaining why this card was previously blocked](https://trello.com/c/"
                + shortLink + "#comment-blocker1)_";
    }

    private static List<Throwable> violations(List<Execution> executions) {
        List<Throwable> violations = new ArrayList<>();
        for (Execution execution : executions) {
            Throwable violation = catchThrowable(() -> assertCallStaysInWriteScope(execution));
            if (violation != null) {
                violations.add(violation);
            }
        }
        return List.copyOf(violations);
    }

    private static List<Execution> footerRefusals(String text) {
        return List.of(
                refusal("trello_add_comment", "invalid_comment_text", text, List.of()),
                refusal("trello_upsert_workpad", "invalid_workpad_text", text, List.of()));
    }

    private static Execution refusal(String tool, String code, String text, List<Write> writes) {
        var nodes = JsonNodeFactory.instance;
        var result = nodes.objectNode().put("success", false);
        result.putArray("contentItems")
                .addObject()
                .put("type", "inputText")
                .put("text", nodes.objectNode().put("error", code).toString());
        return new Execution(
                new Permissions(true, true, true, true, true, false, List.of(), List.of()),
                List.of(),
                CARD_ID,
                "abc123",
                tool,
                nodes.objectNode().put("text", text),
                List.of(),
                result,
                writes,
                false);
    }
}
