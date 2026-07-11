package ch.fmartin.symphony.trello.setup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import ch.fmartin.symphony.trello.testsupport.RecordingTerminal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

final class TrelloCredentialStoreTest {
    @TempDir
    Path tempDir;

    @Test
    void directCredentialsArePersistedAndRedacted() throws Exception {
        // given
        Path env = tempDir.resolve(".env");
        LocalSetup.Options options = options(env, "direct-key", "direct-token");
        RecordingTerminal terminal = new RecordingTerminal();

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
        TrelloCredentialStore store =
                new TrelloCredentialStore(Map.of("TRELLO_API_KEY", "env-key", "TRELLO_API_TOKEN", "env-token"));

        // when
        TrelloCredentialStore.CredentialSelection credentials =
                store.loadOrPrompt(options, env, new RecordingTerminal());

        // then
        assertThat(credentials.persist()).isFalse();
        assertThat(env).doesNotExist();
    }

    @Test
    void existingDotenvCredentialsAreNotCopiedIntoAnotherDotenv() throws Exception {
        // given
        Path env = tempDir.resolve(".env");
        Files.writeString(env, "TRELLO_API_KEY=dotenv-key\nTRELLO_API_TOKEN=dotenv-token\n", StandardCharsets.UTF_8);
        LocalSetup.Options options = SetupOptionFactory.options(tempDir);

        // when
        TrelloCredentialStore.CredentialSelection credentials =
                new TrelloCredentialStore(Map.of()).loadOrPrompt(options, env, new RecordingTerminal());

        // then
        assertThat(credentials.persist()).isFalse();
        assertThat(credentials.credentials())
                .extracting(TrelloBoardSetup.TrelloCredentials::apiKey, TrelloBoardSetup.TrelloCredentials::apiToken)
                .containsExactly("dotenv-key", "dotenv-token");
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
        TrelloCredentialStore store = new TrelloCredentialStore(Map.of());

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
                java.util.Optional.of(key),
                java.util.Optional.of(token),
                options.boardName(),
                options.existingBoardId(),
                options.workspaceId(),
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
