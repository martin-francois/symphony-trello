package ch.fmartin.symphony.trello.setup;

import static ch.fmartin.symphony.trello.setup.InstallerScriptFixture.commandExists;
import static ch.fmartin.symphony.trello.setup.InstallerScriptFixture.run;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ch.fmartin.symphony.trello.setup.InstallerScriptFixture.ProcessResult;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class PrivateContextScannerScriptTest {
    private static final String PRIVATE_TRELLO_URL = "https://trello.com/b/" + "AbCd" + "1234/private-board";
    private static final String SYNTHETIC_TRELLO_URL = "https://trello.com/b/SYNTH001/synthetic-board";
    private static final String PRIVATE_TRELLO_ID = "6a1eb7c4873" + "fd71be041d1cf";
    private static final String SYNTHETIC_TRELLO_ID = "000000000000000000000001";

    @TempDir
    Path tempDir;

    @Test
    void rejectsPrivateTrelloUrlFromStdinWithoutPrintingMatchedValue() throws Exception {
        // given
        assumeToolsAvailable();

        // when
        ProcessResult result = runScanner("Found " + PRIVATE_TRELLO_URL + "\n", "--stdin");

        // then
        assertThat(result.exitCode()).isOne();
        assertThat(result.output())
                .contains("trello-url-private-context")
                .doesNotContain(PRIVATE_TRELLO_URL, "AbCd1234");
    }

    @Test
    void rejectsPrivateTrelloUrlFromStdinThroughDockerFallbackWithoutPrintingMatchedValue() throws Exception {
        // given
        assumeToolsAvailable();

        // when
        ProcessResult result =
                runScannerWithEnvironment(dockerFallbackEnvironment(), "Found " + PRIVATE_TRELLO_URL + "\n", "--stdin");

        // then
        assertThat(result.exitCode()).isOne();
        assertThat(result.output())
                .contains("trello-url-private-context")
                .doesNotContain(PRIVATE_TRELLO_URL, "AbCd1234");
    }

    @Test
    void allowsClearlySyntheticTrelloFixtureShapes() throws Exception {
        // given
        assumeToolsAvailable();

        // when
        ProcessResult result = runScanner(
                """
                board url: %s
                board id: %s
                """
                        .formatted(SYNTHETIC_TRELLO_URL, SYNTHETIC_TRELLO_ID),
                "--stdin");

        // then
        result.assertSuccess();
    }

    @Test
    void rejectsPrivateTrelloIdFromFileWithoutPrintingMatchedValue() throws Exception {
        // given
        assumeToolsAvailable();
        Path body = tempDir.resolve("body.md");
        Files.writeString(body, "board id " + PRIVATE_TRELLO_ID + "\n");

        // when
        ProcessResult result = runScanner("", "--file", body.toString());

        // then
        assertThat(result.exitCode()).isOne();
        assertThat(result.output()).contains("trello-id-private-context").doesNotContain(PRIVATE_TRELLO_ID);
    }

    @Test
    void rejectsUninspectableExplicitFileWithoutPrintingContents() throws Exception {
        // given
        assumeToolsAvailable();
        Path body = tempDir.resolve("body.bin");
        byte[] privateUrl = ("board " + PRIVATE_TRELLO_URL).getBytes(StandardCharsets.UTF_8);
        byte[] bytes = Arrays.copyOf(privateUrl, privateUrl.length + 1);
        Files.write(body, bytes);

        // when
        ProcessResult result = runScanner("", "--file", body.toString());

        // then
        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.output())
                .contains("File cannot be inspected as text")
                .doesNotContain(PRIVATE_TRELLO_URL, "AbCd1234");
    }

    @Test
    void allowsNewlineOnlyExplicitFile() throws Exception {
        // given
        assumeToolsAvailable();
        Path body = tempDir.resolve("blank.md");
        Files.writeString(body, "\n\n");

        // when
        ProcessResult result = runScanner("", "--file", body.toString());

        // then
        result.assertSuccess();
    }

    @Test
    void allowsWhitespaceOnlyExplicitFile() throws Exception {
        // given
        assumeToolsAvailable();
        Path body = tempDir.resolve("whitespace.md");
        Files.writeString(body, "   \n\t \n");

        // when
        ProcessResult result = runScanner("", "--file", body.toString());

        // then
        result.assertSuccess();
    }

    @Test
    void scansCommitMessagesInGitRange() throws Exception {
        // given
        assumeToolsAvailable();
        assumeTrue(commandExists("git"));
        initializeGitRepositoryWithCleanNote();
        Files.writeString(tempDir.resolve("other.md"), "still clean\n");
        run(Map.of(), tempDir, "git", "add", "other.md").assertSuccess();
        run(Map.of(), tempDir, "git", "commit", "-m", "test: mention " + PRIVATE_TRELLO_URL)
                .assertSuccess();

        // when
        ProcessResult result = run(scannerEnvironment(), tempDir, scanner().toString(), "--git-range", "HEAD~1..HEAD");

        // then
        assertThat(result.exitCode()).isOne();
        assertThat(result.output())
                .contains("git-range:HEAD~1..HEAD", "trello-url-private-context")
                .doesNotContain(PRIVATE_TRELLO_URL, "AbCd1234");
    }

    @Test
    void doesNotPrintPrivateCommitMessageAsGitPatchMetadata() throws Exception {
        // given
        assumeToolsAvailable();
        assumeTrue(commandExists("git"));
        initializeGitRepositoryWithCleanNote();
        Files.writeString(tempDir.resolve("note.md"), "board " + PRIVATE_TRELLO_URL + "\n");
        run(Map.of(), tempDir, "git", "add", "note.md").assertSuccess();
        run(Map.of(), tempDir, "git", "commit", "-m", "test: mention " + PRIVATE_TRELLO_ID)
                .assertSuccess();

        // when
        ProcessResult result = run(scannerEnvironment(), tempDir, scanner().toString(), "--git-range", "HEAD~1..HEAD");

        // then
        assertThat(result.exitCode()).isOne();
        assertThat(result.output())
                .contains("git-range:HEAD~1..HEAD", "git-patch:HEAD~1..HEAD", "trello-url-private-context")
                .doesNotContain(PRIVATE_TRELLO_ID, PRIVATE_TRELLO_URL, "AbCd1234");
    }

    @Test
    void scansAddedLinesInGitRange() throws Exception {
        // given
        assumeToolsAvailable();
        assumeTrue(commandExists("git"));
        initializeGitRepositoryWithCleanNote();
        Files.writeString(tempDir.resolve("note.md"), "board " + PRIVATE_TRELLO_URL + "\n");
        run(Map.of(), tempDir, "git", "add", "note.md").assertSuccess();
        run(Map.of(), tempDir, "git", "commit", "-m", "test: update note").assertSuccess();

        // when
        ProcessResult result = run(scannerEnvironment(), tempDir, scanner().toString(), "--git-range", "HEAD~1..HEAD");

        // then
        assertThat(result.exitCode()).isOne();
        assertThat(result.output())
                .contains("trello-url-private-context")
                .doesNotContain(PRIVATE_TRELLO_URL, "AbCd1234");
    }

    @Test
    void scansAddedLinesThatStartWithLiteralPlusInGitRange() throws Exception {
        // given
        assumeToolsAvailable();
        assumeTrue(commandExists("git"));
        initializeGitRepositoryWithCleanNote();
        Files.writeString(tempDir.resolve("note.md"), "+" + PRIVATE_TRELLO_URL + "\n");
        run(Map.of(), tempDir, "git", "add", "note.md").assertSuccess();
        run(Map.of(), tempDir, "git", "commit", "-m", "test: add plus-prefixed note")
                .assertSuccess();

        // when
        ProcessResult result = run(scannerEnvironment(), tempDir, scanner().toString(), "--git-range", "HEAD~1..HEAD");

        // then
        assertThat(result.exitCode()).isOne();
        assertThat(result.output())
                .contains("git-patch:HEAD~1..HEAD", "trello-url-private-context")
                .doesNotContain(PRIVATE_TRELLO_URL, "AbCd1234");
    }

    @Test
    void scansPrivateValuesAddedAndRemovedInsideGitRange() throws Exception {
        // given
        assumeToolsAvailable();
        assumeTrue(commandExists("git"));
        initializeGitRepositoryWithCleanNote();
        Files.writeString(tempDir.resolve("note.md"), "board " + PRIVATE_TRELLO_URL + "\n");
        run(Map.of(), tempDir, "git", "add", "note.md").assertSuccess();
        run(Map.of(), tempDir, "git", "commit", "-m", "test: add temporary private fixture")
                .assertSuccess();
        Files.writeString(tempDir.resolve("note.md"), "board clean\n");
        run(Map.of(), tempDir, "git", "add", "note.md").assertSuccess();
        run(Map.of(), tempDir, "git", "commit", "-m", "test: remove temporary private fixture")
                .assertSuccess();

        // when
        ProcessResult result = run(scannerEnvironment(), tempDir, scanner().toString(), "--git-range", "HEAD~2..HEAD");

        // then
        assertThat(result.exitCode()).isOne();
        assertThat(result.output())
                .contains("git-patch:HEAD~2..HEAD", "trello-url-private-context")
                .doesNotContain(PRIVATE_TRELLO_URL, "AbCd1234");
    }

    @Test
    void ignoresRemovedLinesInGitRange() throws Exception {
        // given
        assumeToolsAvailable();
        assumeTrue(commandExists("git"));
        initializeGitRepository();
        Files.writeString(tempDir.resolve("note.md"), "board " + PRIVATE_TRELLO_URL + "\n");
        run(Map.of(), tempDir, "git", "add", "note.md").assertSuccess();
        run(Map.of(), tempDir, "git", "commit", "-m", "test: add private fixture")
                .assertSuccess();
        Files.writeString(tempDir.resolve("note.md"), "board " + SYNTHETIC_TRELLO_URL + "\n");
        run(Map.of(), tempDir, "git", "add", "note.md").assertSuccess();
        run(Map.of(), tempDir, "git", "commit", "-m", "test: replace private fixture")
                .assertSuccess();

        // when
        ProcessResult result = run(scannerEnvironment(), tempDir, scanner().toString(), "--git-range", "HEAD~1..HEAD");

        // then
        result.assertSuccess();
    }

    @Test
    void scansTrackedAndUntrackedWorktreeFiles() throws Exception {
        // given
        assumeToolsAvailable();
        assumeTrue(commandExists("git"));
        initializeGitRepository();
        Files.writeString(tempDir.resolve("tracked.md"), "tracked\n");
        run(Map.of(), tempDir, "git", "add", "tracked.md").assertSuccess();
        run(Map.of(), tempDir, "git", "commit", "-m", "test: add tracked note").assertSuccess();
        Files.writeString(tempDir.resolve("untracked.md"), "board " + PRIVATE_TRELLO_URL + "\n");

        // when
        ProcessResult result = run(scannerEnvironment(), tempDir, scanner().toString(), "--worktree");

        // then
        assertThat(result.exitCode()).isOne();
        assertThat(result.output())
                .contains("trello-url-private-context")
                .doesNotContain(PRIVATE_TRELLO_URL, "AbCd1234");
    }

    @Test
    void scansTheWholeWorktreeWhenInvokedFromASubdirectory() throws Exception {
        // given
        assumeToolsAvailable();
        assumeTrue(commandExists("git"));
        initializeGitRepository();
        Path subdirectory = Files.createDirectories(tempDir.resolve("nested"));
        Files.writeString(tempDir.resolve("untracked.md"), "board " + PRIVATE_TRELLO_URL + "\n");

        // when
        ProcessResult result = run(scannerEnvironment(), subdirectory, scanner().toString(), "--worktree");

        // then
        assertThat(result.exitCode()).isOne();
        assertThat(result.output())
                .contains("trello-url-private-context")
                .doesNotContain(PRIVATE_TRELLO_URL, "AbCd1234");
    }

    @Test
    void ignoresGitIgnoredWorktreeFiles() throws Exception {
        // given
        assumeToolsAvailable();
        assumeTrue(commandExists("git"));
        initializeGitRepository();
        Files.writeString(tempDir.resolve(".gitignore"), "node_modules/\n");
        Files.writeString(tempDir.resolve("tracked.md"), "tracked\n");
        run(Map.of(), tempDir, "git", "add", ".gitignore", "tracked.md").assertSuccess();
        run(Map.of(), tempDir, "git", "commit", "-m", "test: ignore dependencies")
                .assertSuccess();
        Path ignoredDirectory = Files.createDirectories(tempDir.resolve("node_modules"));
        Files.writeString(ignoredDirectory.resolve("dependency.md"), "ignored board " + PRIVATE_TRELLO_URL + "\n");

        // when
        ProcessResult result = run(scannerEnvironment(), tempDir, scanner().toString(), "--worktree");

        // then
        result.assertSuccess();
    }

    @Test
    void scansTrackedFilesEvenWhenTheyMatchAnIgnoreRule() throws Exception {
        // given
        assumeToolsAvailable();
        assumeTrue(commandExists("git"));
        initializeGitRepository();
        Files.writeString(tempDir.resolve("tracked.md"), "tracked\n");
        run(Map.of(), tempDir, "git", "add", "tracked.md").assertSuccess();
        run(Map.of(), tempDir, "git", "commit", "-m", "test: add tracked note").assertSuccess();
        Files.writeString(tempDir.resolve(".gitignore"), "tracked.md\n");
        Files.writeString(tempDir.resolve("tracked.md"), "board " + PRIVATE_TRELLO_URL + "\n");

        // when
        ProcessResult result = run(scannerEnvironment(), tempDir, scanner().toString(), "--worktree");

        // then
        assertThat(result.exitCode()).isOne();
        assertThat(result.output())
                .contains("trello-url-private-context")
                .doesNotContain(PRIVATE_TRELLO_URL, "AbCd1234");
    }

    @Test
    void scansNonIgnoredWorktreeFilesWhoseNamesStartWithDash() throws Exception {
        // given
        assumeToolsAvailable();
        assumeTrue(commandExists("git"));
        initializeGitRepository();
        Files.writeString(tempDir.resolve("-note.md"), "board " + PRIVATE_TRELLO_URL + "\n");

        // when
        ProcessResult result = run(scannerEnvironment(), tempDir, scanner().toString(), "--worktree");

        // then
        assertThat(result.exitCode()).isOne();
        assertThat(result.output())
                .contains("trello-url-private-context")
                .doesNotContain(PRIVATE_TRELLO_URL, "AbCd1234");
    }

    @Test
    void failsClosedWhenGitCannotEnumerateWorktreeFiles() throws Exception {
        // given
        assumeToolsAvailable();
        assumeTrue(commandExists("git"));
        initializeGitRepository();
        Files.writeString(tempDir.resolve(".git/index"), "invalid index");

        // when
        ProcessResult result = run(scannerEnvironment(), tempDir, scanner().toString(), "--worktree");

        // then
        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.output()).contains("Could not enumerate Git worktree files");
    }

    @Test
    void doesNotDescendIntoAnOpaqueNestedRepository() throws Exception {
        // given
        assumeToolsAvailable();
        assumeTrue(commandExists("git"));
        initializeGitRepository();
        Path nestedRepository = Files.createDirectories(tempDir.resolve("nested"));
        run(Map.of(), nestedRepository, "git", "init", "-b", "main").assertSuccess();
        Files.writeString(nestedRepository.resolve("note.md"), "board " + PRIVATE_TRELLO_URL + "\n");

        // when
        ProcessResult result = run(scannerEnvironment(), tempDir, scanner().toString(), "--worktree");

        // then
        result.assertSuccess();
    }

    @Test
    void excludesThePrivateContextRuleFileFromScanningItself() throws Exception {
        // given
        assumeToolsAvailable();
        assumeTrue(commandExists("git"));
        initializeGitRepository();
        Path configDirectory = Files.createDirectories(tempDir.resolve("config/betterleaks"));
        Files.writeString(
                configDirectory.resolve("private-context.toml"), "example = \"" + PRIVATE_TRELLO_URL + "\"\n");
        run(Map.of(), tempDir, "git", "add", "config/betterleaks/private-context.toml")
                .assertSuccess();

        // when
        ProcessResult result = run(scannerEnvironment(), tempDir, scanner().toString(), "--worktree");

        // then
        result.assertSuccess();
    }

    @Test
    void doesNotFollowASymlinkedParentOutsideTheWorktree() throws Exception {
        // given
        assumeToolsAvailable();
        assumeTrue(commandExists("git"));
        initializeGitRepository();
        Path trackedDirectory = Files.createDirectories(tempDir.resolve("tracked"));
        Files.writeString(trackedDirectory.resolve("note.md"), "clean\n");
        Files.writeString(tempDir.resolve(".gitignore"), ".ignored-outside/\n");
        run(Map.of(), tempDir, "git", "add", ".gitignore", "tracked/note.md").assertSuccess();
        run(Map.of(), tempDir, "git", "commit", "-m", "test: add tracked note").assertSuccess();
        Path outsideDirectory = Files.createDirectories(tempDir.resolve(".ignored-outside"));
        Files.writeString(outsideDirectory.resolve("note.md"), "board " + PRIVATE_TRELLO_URL + "\n");
        Files.delete(trackedDirectory.resolve("note.md"));
        Files.delete(trackedDirectory);
        Files.createSymbolicLink(tempDir.resolve("tracked"), outsideDirectory);

        // when
        ProcessResult result = run(scannerEnvironment(), tempDir, scanner().toString(), "--worktree");

        // then
        result.assertSuccess();
    }

    private ProcessResult runScanner(String input, String... arguments) throws Exception {
        return runScannerWithEnvironment(scannerEnvironment(), input, arguments);
    }

    private ProcessResult runScannerWithEnvironment(Map<String, String> environment, String input, String... arguments)
            throws Exception {
        var processBuilder = new ProcessBuilder(scannerCommand(arguments));
        processBuilder.directory(Path.of(".").toAbsolutePath().normalize().toFile());
        processBuilder.environment().remove("BETTERLEAKS_COMMAND");
        processBuilder.environment().putAll(environment);
        return run(processBuilder, input, 30);
    }

    private void initializeGitRepository() throws Exception {
        run(Map.of(), tempDir, "git", "init", "-b", "main").assertSuccess();
        run(Map.of(), tempDir, "git", "config", "user.name", "Test User").assertSuccess();
        run(Map.of(), tempDir, "git", "config", "user.email", "test@example.invalid")
                .assertSuccess();
    }

    private void initializeGitRepositoryWithCleanNote() throws Exception {
        initializeGitRepository();
        Files.writeString(tempDir.resolve("note.md"), "clean\n");
        run(Map.of(), tempDir, "git", "add", "note.md").assertSuccess();
        run(Map.of(), tempDir, "git", "commit", "-m", "test: add clean note").assertSuccess();
    }

    private void assumeToolsAvailable() {
        assumeTrue(commandExists("bash"));
    }

    private Map<String, String> scannerEnvironment() throws Exception {
        return Map.of(
                "BETTERLEAKS_COMMAND",
                PrivateContextScriptFixture.installFakeBetterLeaks(tempDir).toString());
    }

    private Map<String, String> dockerFallbackEnvironment() throws Exception {
        PrivateContextScriptFixture.installFakeDockerBetterLeaks(tempDir);
        return Map.of("PATH", tempDir + ":/usr/bin:/bin");
    }

    private static String[] scannerCommand(String... arguments) {
        String[] command = new String[arguments.length + 1];
        command[0] = scanner().toString();
        System.arraycopy(arguments, 0, command, 1, arguments.length);
        return command;
    }

    private static Path scanner() {
        return Path.of("scripts", "check-private-context").toAbsolutePath().normalize();
    }
}
