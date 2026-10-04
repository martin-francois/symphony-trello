package ch.fmartin.symphony.trello.setup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import ch.fmartin.symphony.trello.config.LocalEnvironment;
import ch.fmartin.symphony.trello.testsupport.RecordingTerminal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class TrelloCredentialStoreTest {
    @TempDir
    Path tempDir;

    @Test
    void directCredentialsArePersistedAndRedacted() throws Exception {
        // given
        Path env = tempDir.resolve(".env");
        LocalSetup.Options options = options(env, "direct-key", "direct-token");
        var terminal = new RecordingTerminal();

        // when
        TrelloCredentialStore.CredentialSelection credentials =
                new TrelloCredentialStore(Map.of()).loadOrPrompt(options, env, terminal);
        new TrelloCredentialStore(Map.of()).write(credentials, env, terminal);

        // then
        assertThat(env)
                .content(StandardCharsets.UTF_8)
                .contains("TRELLO_API_KEY=direct-key", "TRELLO_API_TOKEN=direct-token");
        assertThat(terminal.stdout()).contains("dire******", "dire********").doesNotContain("direct-token");
    }

    @Test
    void environmentCredentialsAreNotCopiedIntoDotenv() throws Exception {
        // given
        Path env = tempDir.resolve(".env");
        LocalSetup.Options options = SetupOptionFactory.options(tempDir);
        var store = new TrelloCredentialStore(Map.of("TRELLO_API_KEY", "env-key", "TRELLO_API_TOKEN", "env-token"));

        // when
        TrelloCredentialStore.CredentialSelection credentials =
                store.loadOrPrompt(options, env, new RecordingTerminal());

        // then
        assertThat(credentials)
                .isEqualTo(new TrelloCredentialStore.CredentialSelection(
                        TrelloCredentialStore.CredentialValue.environment("env-key"),
                        TrelloCredentialStore.CredentialValue.environment("env-token")));
        assertThat(env).doesNotExist();
    }

    @Test
    void existingDotenvCredentialsAreNotCopiedIntoAnotherDotenv() throws Exception {
        // given
        Path env = tempDir.resolve(".env");
        Files.writeString(env, "TRELLO_API_KEY=dotenv-key\nTRELLO_API_TOKEN=dotenv-token\n");
        LocalSetup.Options options = SetupOptionFactory.options(tempDir);

        // when
        TrelloCredentialStore.CredentialSelection credentials =
                new TrelloCredentialStore(Map.of()).loadOrPrompt(options, env, new RecordingTerminal());

        // then
        assertThat(credentials)
                .isEqualTo(new TrelloCredentialStore.CredentialSelection(
                        TrelloCredentialStore.CredentialValue.dotenv("dotenv-key"),
                        TrelloCredentialStore.CredentialValue.dotenv("dotenv-token")));
    }

    @Test
    void dotenvEscapingQuotesSpacesAndBackslashes() {
        // given
        List<String> lines = List.of();

        // when
        List<String> updated = TrelloCredentialStore.upsertEnv(lines, "TRELLO_API_TOKEN", "a b\"c\\d");

        // then
        assertThat(updated).containsExactly("TRELLO_API_TOKEN=\"a b\\\"c\\\\d\"");
    }

    /// The setup writer and the LocalEnvironment reader must agree on quoting and escaping, so a
    /// saved credential reads back unchanged. See docs/adr/0100-keep-the-hand-rolled-dotenv-parser.md.
    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(
            strings = {
                "plain-token_1.2:3@4%5+6=7,8/9",
                "",
                " leading and trailing spaces ",
                "double\"quote",
                "single'quote",
                "C:\\Users\\Jane Doe",
                "trailing backslash\\",
                "tab\tbackspace\bform feed\f",
                "hash # inside",
                "#leading-hash",
                "$TRELLO_API_KEY",
                "${TRELLO_API_KEY:-fallback}",
                "non-ASCII \u00e4\u00f6\u00fc \uD835\uDC00"
            })
    void writtenDotenvValuesReadBackUnchanged(String value) throws Exception {
        // given
        Path env = tempDir.resolve(".env");
        Files.write(env, TrelloCredentialStore.upsertEnv(List.of(), "TRELLO_API_TOKEN", value));

        // when
        Map<String, String> values = LocalEnvironment.load(env);

        // then
        assertThat(values).containsExactly(Map.entry("TRELLO_API_TOKEN", value));
    }

    @Test
    void multilineDotenvValuesAreRejected() {
        // given

        // when
        Throwable thrown = catchThrowable(() -> TrelloCredentialStore.dotenvValue("a\nb"));

        // then
        assertThat(thrown).isInstanceOf(TrelloBoardSetupException.class).hasMessageContaining("newlines");
    }

    @EnabledOnOs({OS.LINUX, OS.MAC})
    @Test
    void persistedDotenvUsesOwnerOnlyPosixPermissions() throws Exception {
        // given
        Path env = tempDir.resolve(".env");
        LocalSetup.Options options = options(env, "key", "token");
        var store = new TrelloCredentialStore(Map.of());

        // when
        store.write(store.loadOrPrompt(options, env, new RecordingTerminal()), env, new RecordingTerminal());

        // then
        assertThat(Files.getPosixFilePermissions(env))
                .containsExactlyInAnyOrder(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
    }

    private LocalSetup.Options options(Path env, String key, String token) {
        LocalSetup.Options options = SetupOptionFactory.options(tempDir);
        return new LocalSetup.Options(
                options.check(),
                options.dryRun(),
                options.repairPort(),
                options.nonInteractive(),
                options.force(),
                options.forceNewSetup(),
                options.configureGithub(),
                options.githubMode(),
                Optional.of(key),
                Optional.of(token),
                options.boardName(),
                options.existingBoardId(),
                options.workspaceId(),
                options.repositoryUrl(),
                options.activeStates(),
                options.terminalStates(),
                options.inProgressState(),
                options.detectInProgressState(),
                options.blockedState(),
                options.workflowPath(),
                options.workflowPathExplicit(),
                options.workspaceRoot(),
                options.workspaceRootExplicit(),
                options.configDir(),
                options.manifestPath(),
                options.serverPort(),
                options.maxAgents(),
                options.maxAgentsExplicit(),
                options.codexModel(),
                options.codexReasoningEffort(),
                options.codexModelCatalog(),
                options.codexModelDefaults(),
                env,
                options.additionalWritableRoots(),
                options.allowAllPaths(),
                options.dangerFullAccess(),
                options.noStart(),
                options.command(),
                options.endpoint(),
                options.callerDirectory());
    }
}
