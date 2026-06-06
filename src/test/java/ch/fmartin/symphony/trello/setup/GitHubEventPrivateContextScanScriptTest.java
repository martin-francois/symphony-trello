package ch.fmartin.symphony.trello.setup;

import static ch.fmartin.symphony.trello.setup.InstallerScriptFixture.commandExists;
import static ch.fmartin.symphony.trello.setup.InstallerScriptFixture.run;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ch.fmartin.symphony.trello.setup.InstallerScriptFixture.ProcessResult;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class GitHubEventPrivateContextScanScriptTest {
    private static final String PRIVATE_TRELLO_URL = "https://trello.com/b/" + "AbCd" + "1234/private-board";
    private static final String CLEAN_TEXT = "Clean public issue text.";

    @TempDir
    Path tempDir;

    @Test
    void scansIssueBodyWithoutPrintingMatchedValue() throws Exception {
        // given
        assumeToolsAvailable();
        Path event =
                event("""
                {"issue":{"title":"Clean title","body":"Details from %s"}}
                """
                        .formatted(PRIVATE_TRELLO_URL));

        // when
        ProcessResult result = runEventScan("issues", event);

        // then
        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.output())
                .contains("github-event:issue.body", "trello-url-private-context")
                .doesNotContain(PRIVATE_TRELLO_URL, "AbCd1234");
    }

    @Test
    void scansIssueCommentBody() throws Exception {
        // given
        assumeToolsAvailable();
        Path event = event("""
                {"comment":{"body":"%s"}}
                """.formatted(CLEAN_TEXT));

        // when
        ProcessResult result = runEventScan("issue_comment", event);

        // then
        result.assertSuccess();
    }

    @Test
    void scansPullRequestBodyWithoutPrintingMatchedValue() throws Exception {
        // given
        assumeToolsAvailable();
        Path event = event(
                """
                {"pull_request":{"title":"fix: clean title","body":"Details from %s"}}
                """
                        .formatted(PRIVATE_TRELLO_URL));

        // when
        ProcessResult result = runEventScan("pull_request", event);

        // then
        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.output())
                .contains("github-event:pull_request.body", "trello-url-private-context")
                .doesNotContain(PRIVATE_TRELLO_URL, "AbCd1234");
    }

    @Test
    void scansReviewCommentBodyWithoutPrintingMatchedValue() throws Exception {
        // given
        assumeToolsAvailable();
        Path event = event("""
                {"comment":{"body":"Details from %s"}}
                """
                .formatted(PRIVATE_TRELLO_URL));

        // when
        ProcessResult result = runEventScan("pull_request_review_comment", event);

        // then
        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.output())
                .contains("github-event:pull_request_review_comment.body", "trello-url-private-context")
                .doesNotContain(PRIVATE_TRELLO_URL, "AbCd1234");
    }

    private ProcessResult runEventScan(String eventName, Path event) throws Exception {
        return run(
                Map.of("GITHUB_EVENT_NAME", eventName, "GITHUB_EVENT_PATH", event.toString()),
                scanner().toString());
    }

    private Path event(String json) throws Exception {
        Path event = tempDir.resolve("event.json");
        Files.writeString(event, json, StandardCharsets.UTF_8);
        return event;
    }

    private static void assumeToolsAvailable() {
        assumeTrue(commandExists("bash"));
        assumeTrue(commandExists("jq"));
    }

    private static Path scanner() {
        return Path.of("scripts", "check-github-event-private-context")
                .toAbsolutePath()
                .normalize();
    }
}
