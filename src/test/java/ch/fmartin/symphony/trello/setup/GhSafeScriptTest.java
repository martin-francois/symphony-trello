package ch.fmartin.symphony.trello.setup;

import static ch.fmartin.symphony.trello.setup.InstallerScriptFixture.commandExists;
import static ch.fmartin.symphony.trello.setup.InstallerScriptFixture.run;
import static ch.fmartin.symphony.trello.setup.InstallerScriptFixture.writeExecutable;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ch.fmartin.symphony.trello.setup.InstallerScriptFixture.ProcessResult;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class GhSafeScriptTest {
    private static final String PRIVATE_TRELLO_URL = "https://trello.com/b/" + "AbCd" + "1234/private-board";
    private static final String CLEAN_BODY = "Implementation notes without private context.";

    @TempDir
    Path tempDir;

    @Test
    void scansHeredocIssueBodyFromStdinBeforeCallingGitHubCli() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        Path log = installFakeGh();

        // when
        ProcessResult result =
                runGhSafe(CLEAN_BODY, "issue", "create", "--title", "fix: clean issue", "--body-file", "-");

        // then
        result.assertSuccess();
        assertThat(log).content(StandardCharsets.UTF_8).contains("issue create --title fix: clean issue --body-file");
    }

    @Test
    void rejectsInlinePullRequestBodyWithoutCallingGitHubCli() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        Path log = installFakeGh();

        // when
        ProcessResult result = runGhSafe(
                "", "pr", "create", "--title", "fix: clean pr", "--body", "Details from " + PRIVATE_TRELLO_URL);

        // then
        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.output())
                .contains("trello-url-private-context")
                .doesNotContain(PRIVATE_TRELLO_URL, "AbCd1234");
        assertThat(log).doesNotExist();
    }

    @Test
    void rejectsApiBodyFieldWithoutCallingGitHubCli() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        Path log = installFakeGh();

        // when
        ProcessResult result = runGhSafe(
                "",
                "api",
                "repos/example/project/pulls/comments/1/replies",
                "-f",
                "body=Details from " + PRIVATE_TRELLO_URL);

        // then
        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.output())
                .contains("trello-url-private-context")
                .doesNotContain(PRIVATE_TRELLO_URL, "AbCd1234");
        assertThat(log).doesNotExist();
    }

    @Test
    void scansApiFieldFileBeforeCallingGitHubCli() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        Path log = installFakeGh();
        Path body = tempDir.resolve("body.md");
        Files.writeString(body, CLEAN_BODY, StandardCharsets.UTF_8);

        // when
        ProcessResult result =
                runGhSafe("", "api", "repos/example/project/pulls/comments/1/replies", "-F", "body=@" + body);

        // then
        result.assertSuccess();
        assertThat(log)
                .content(StandardCharsets.UTF_8)
                .contains("api repos/example/project/pulls/comments/1/replies -F body=@" + body);
    }

    @Test
    void failsClosedForEditorMode() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        Path log = installFakeGh();

        // when
        ProcessResult result = runGhSafe("", "issue", "create", "--editor");

        // then
        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.output()).contains("Editor and web modes cannot be scanned");
        assertThat(log).doesNotExist();
    }

    private ProcessResult runGhSafe(String input, String... arguments) throws Exception {
        ProcessBuilder processBuilder = new ProcessBuilder(command(arguments));
        processBuilder
                .environment()
                .put("GH_SAFE_GH_COMMAND", tempDir.resolve("gh").toString());
        return run(processBuilder, input, 30);
    }

    private Path installFakeGh() throws Exception {
        Path log = tempDir.resolve("gh.log");
        writeExecutable(
                tempDir.resolve("gh"),
                """
                #!/usr/bin/env bash
                printf '%%s\\n' "$*" > '%s'
                """
                        .formatted(log));
        return log;
    }

    private static String[] command(String... arguments) {
        String[] command = new String[arguments.length + 1];
        command[0] = Path.of("scripts", "gh-safe").toAbsolutePath().normalize().toString();
        System.arraycopy(arguments, 0, command, 1, arguments.length);
        return command;
    }
}
