package ch.fmartin.symphony.trello.setup;

import static ch.fmartin.symphony.trello.TextCharacterMatchers.UNICODE_BYTE_ORDER_MARK;
import static ch.fmartin.symphony.trello.testsupport.TestEndpointValues.FOREIGN_ENDPOINT_URI;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import ch.fmartin.symphony.trello.TrelloEnvironment;
import ch.fmartin.symphony.trello.config.LocalEnvironment;
import ch.fmartin.symphony.trello.setup.TrelloCredentialResolver.CredentialSelection;
import ch.fmartin.symphony.trello.setup.TrelloCredentialResolver.CredentialValue;
import ch.fmartin.symphony.trello.testsupport.RecordingTerminal;
import java.net.URI;
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
        CredentialSelection credentials = new TrelloCredentialStore(Map.of()).loadOrPrompt(options, env, terminal);
        new TrelloCredentialStore(Map.of()).write(credentials, env, terminal);

        // then
        assertThat(env)
                .content(StandardCharsets.UTF_8)
                .contains("TRELLO_API_KEY=direct-key", "TRELLO_API_TOKEN=direct-token");
        assertThat(terminal.stdout()).contains("dire******", "dire********").doesNotContain("direct-token");
    }

    @Test
    void savingCredentialsForAnotherEndpointBindsTheDotenvToIt() throws Exception {
        // given
        Path env = tempDir.resolve(".env");
        var terminal = new RecordingTerminal();

        // when
        new TrelloCredentialStore(Map.of()).write(directCredentials(FOREIGN_ENDPOINT_URI), env, terminal);

        // then
        assertThat(env)
                .content(StandardCharsets.UTF_8)
                .isEqualTo(
                        "TRELLO_API_KEY=direct-key\nTRELLO_API_TOKEN=direct-token\nTRELLO_API_ENDPOINT=%s\n",
                        FOREIGN_ENDPOINT_URI);
        assertThat(terminal.stdout()).contains("  OK  Trello API endpoint: " + FOREIGN_ENDPOINT_URI);
    }

    @Test
    void savingCredentialsForTheTrelloApiLeavesAnUnboundDotenvUnbound() throws Exception {
        // given
        Path env = tempDir.resolve(".env");
        var terminal = new RecordingTerminal();

        // when
        new TrelloCredentialStore(Map.of()).write(directCredentials(TrelloBoardSetup.DEFAULT_ENDPOINT), env, terminal);

        // then
        assertThat(env).content(StandardCharsets.UTF_8).doesNotContain(TrelloEnvironment.API_ENDPOINT);
        assertThat(terminal.stdout()).doesNotContain("Trello API endpoint");
    }

    @Test
    void savingCredentialsForTheTrelloApiRebindsADotenvBoundElsewhere() throws Exception {
        // given
        Path env = tempDir.resolve(".env");
        Files.writeString(env, "TRELLO_API_ENDPOINT=" + FOREIGN_ENDPOINT_URI + "\n# preserved comment\n");

        // when
        new TrelloCredentialStore(Map.of())
                .write(directCredentials(TrelloBoardSetup.DEFAULT_ENDPOINT), env, new RecordingTerminal());

        // then
        assertThat(env)
                .content(StandardCharsets.UTF_8)
                .isEqualTo(
                        """
                        TRELLO_API_ENDPOINT=https://api.trello.com/1
                        # preserved comment
                        TRELLO_API_KEY=direct-key
                        TRELLO_API_TOKEN=direct-token
                        """);
    }

    @Test
    void savingCredentialsIntoADotenvAlreadyBoundToTheEndpointLeavesTheBindingAlone() throws Exception {
        // given
        Path env = tempDir.resolve(".env");
        Files.writeString(env, "TRELLO_API_ENDPOINT=HTTPS://Trello.Example.Test:443/1/\n");
        var terminal = new RecordingTerminal();

        // when
        new TrelloCredentialStore(Map.of()).write(directCredentials(FOREIGN_ENDPOINT_URI), env, terminal);

        // then
        assertThat(env)
                .content(StandardCharsets.UTF_8)
                .isEqualTo(
                        """
                        TRELLO_API_ENDPOINT=HTTPS://Trello.Example.Test:443/1/
                        TRELLO_API_KEY=direct-key
                        TRELLO_API_TOKEN=direct-token
                        """);
        assertThat(terminal.stdout()).doesNotContain("Trello API endpoint");
    }

    @Test
    void savingOneCredentialIntoADotenvBoundElsewhereIsRefusedBeforeTheWrite() throws Exception {
        // given
        // the file's own token stays bound to the Trello API, so the file must not be rebound
        Path env = tempDir.resolve(".env");
        Files.writeString(env, "TRELLO_API_KEY=file-key\nTRELLO_API_TOKEN=file-token\n");
        var credentials = new CredentialSelection(
                CredentialValue.directInput(TrelloEnvironment.API_KEY, "direct-key"),
                new CredentialValue(
                        TrelloEnvironment.API_TOKEN, "shell-token", TrelloCredentialSource.SHELL_ENVIRONMENT),
                FOREIGN_ENDPOINT_URI);

        // when
        Throwable thrown = catchThrowable(
                () -> new TrelloCredentialStore(Map.of()).write(credentials, env, new RecordingTerminal()));

        // then
        assertThat(thrown).isInstanceOfSatisfying(TrelloBoardSetupException.class, failure -> {
            assertThat(failure.code()).isEqualTo("setup_credentials_endpoint_mismatch");
            assertThat(failure)
                    .hasMessageContainingAll(
                            "cannot change Trello credential binding in \"" + env + "\"",
                            "TRELLO_API_TOKEN remains unchecked and belongs to https://api.trello.com/1",
                            "this command uses " + FOREIGN_ENDPOINT_URI,
                            "use a separate env file",
                            "TRELLO_API_ENDPOINT yourself");
        });
        assertThat(env)
                .content(StandardCharsets.UTF_8)
                .isEqualTo("TRELLO_API_KEY=file-key\nTRELLO_API_TOKEN=file-token\n");
    }

    @Test
    void savingOneCredentialIntoADotenvWithoutCredentialsBindsTheDotenv() throws Exception {
        // given
        // nothing in the file is left unchecked, so the file may take the command's binding
        Path env = tempDir.resolve(".env");
        Files.writeString(env, "# preserved comment\n");
        var credentials = new CredentialSelection(
                new CredentialValue(TrelloEnvironment.API_KEY, "shell-key", TrelloCredentialSource.SHELL_ENVIRONMENT),
                CredentialValue.directInput(TrelloEnvironment.API_TOKEN, "direct-token"),
                FOREIGN_ENDPOINT_URI);

        // when
        new TrelloCredentialStore(Map.of()).write(credentials, env, new RecordingTerminal());

        // then
        assertThat(env)
                .content(StandardCharsets.UTF_8)
                .isEqualTo(
                        "# preserved comment\nTRELLO_API_TOKEN=direct-token\nTRELLO_API_ENDPOINT=%s\n",
                        FOREIGN_ENDPOINT_URI);
    }

    private static CredentialSelection directCredentials(URI endpoint) {
        return new CredentialSelection(
                CredentialValue.directInput(TrelloEnvironment.API_KEY, "direct-key"),
                CredentialValue.directInput(TrelloEnvironment.API_TOKEN, "direct-token"),
                endpoint);
    }

    @ParameterizedTest
    @ValueSource(strings = {"CUSTOM_TOKEN", "CUSTOM_KEY", "OTHER"})
    void savingDefaultCredentialsDoesNotRebindExistingCustomVariables(String variable) throws Exception {
        // given
        Path env = tempDir.resolve(".env");
        String original = variable + "=synthetic-custom-secret\n";
        Files.writeString(env, original);

        // when
        Throwable thrown = catchThrowable(() -> new TrelloCredentialStore(Map.of())
                .write(directCredentials(FOREIGN_ENDPOINT_URI), env, new RecordingTerminal()));

        // then
        assertThat(thrown)
                .isInstanceOf(TrelloBoardSetupException.class)
                .hasMessageContaining(variable)
                .hasMessageNotContaining("synthetic-custom-secret");
        assertThat(env).content(StandardCharsets.UTF_8).isEqualTo(original);
    }

    @Test
    void environmentCredentialsAreNotCopiedIntoDotenv() throws Exception {
        // given
        Path env = tempDir.resolve(".env");
        LocalSetup.Options options = SetupOptionFactory.options(tempDir);
        var store = new TrelloCredentialStore(Map.of(
                "TRELLO_API_KEY",
                "env-key",
                "TRELLO_API_TOKEN",
                "env-token",
                "TRELLO_API_ENDPOINT",
                options.endpoint().toString()));

        // when
        CredentialSelection credentials = store.loadOrPrompt(options, env, new RecordingTerminal());

        // then
        assertThat(credentials)
                .isEqualTo(new CredentialSelection(
                        new CredentialValue(
                                TrelloEnvironment.API_KEY, "env-key", TrelloCredentialSource.SHELL_ENVIRONMENT),
                        new CredentialValue(
                                TrelloEnvironment.API_TOKEN, "env-token", TrelloCredentialSource.SHELL_ENVIRONMENT),
                        options.endpoint()));
        assertThat(env).doesNotExist();
    }

    @Test
    void existingDotenvCredentialsAreNotCopiedIntoAnotherDotenv() throws Exception {
        // given
        Path env = tempDir.resolve(".env");
        LocalSetup.Options options = SetupOptionFactory.options(tempDir);
        Files.writeString(
                env,
                "TRELLO_API_KEY=dotenv-key\nTRELLO_API_TOKEN=dotenv-token\nTRELLO_API_ENDPOINT=" + options.endpoint()
                        + "\n");

        // when
        CredentialSelection credentials =
                new TrelloCredentialStore(Map.of()).loadOrPrompt(options, env, new RecordingTerminal());

        // then
        assertThat(credentials)
                .isEqualTo(new CredentialSelection(
                        new CredentialValue(
                                TrelloEnvironment.API_KEY, "dotenv-key", TrelloCredentialSource.DOTENV_FILE),
                        new CredentialValue(
                                TrelloEnvironment.API_TOKEN, "dotenv-token", TrelloCredentialSource.DOTENV_FILE),
                        options.endpoint()));
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
    void writeReplacesFirstKeyBehindByteOrderMarkAndPreservesOtherKeys() throws Exception {
        // given
        Path env = tempDir.resolve(".env");
        Files.writeString(
                env,
                UNICODE_BYTE_ORDER_MARK
                        + "TRELLO_API_KEY=old-example-key\nTRELLO_API_TOKEN=stored-example-token\nOTHER=keep-me\n");
        var credentials = new CredentialSelection(
                CredentialValue.directInput(TrelloEnvironment.API_KEY, "new-example-key"),
                new CredentialValue(
                        TrelloEnvironment.API_TOKEN, "stored-example-token", TrelloCredentialSource.DOTENV_FILE),
                TrelloBoardSetup.DEFAULT_ENDPOINT);

        // when
        new TrelloCredentialStore(Map.of()).write(credentials, env, new RecordingTerminal());

        // then
        assertThat(Files.readAllLines(env))
                .containsExactly(
                        "TRELLO_API_KEY=new-example-key", "TRELLO_API_TOKEN=stored-example-token", "OTHER=keep-me");
        assertThat(LocalEnvironment.load(env))
                .containsOnly(
                        Map.entry("TRELLO_API_KEY", "new-example-key"),
                        Map.entry("TRELLO_API_TOKEN", "stored-example-token"),
                        Map.entry("OTHER", "keep-me"));
    }

    @Test
    void upsertEnvUsesReaderKeyRecognitionForExportAndWhitespace() {
        // given
        List<String> lines = List.of(
                "  export TRELLO_API_KEY  = old-example-key # previous value",
                "TRELLO_API_TOKEN=stored-example-token",
                "OTHER=keep-me");

        // when
        List<String> updated = TrelloCredentialStore.upsertEnv(lines, "TRELLO_API_KEY", "new-example-key");

        // then
        assertThat(updated)
                .containsExactly(
                        "TRELLO_API_KEY=new-example-key", "TRELLO_API_TOKEN=stored-example-token", "OTHER=keep-me");
    }

    @Test
    void upsertEnvOnlyIgnoresByteOrderMarkOnTheFirstLine() {
        // given
        List<String> lines = List.of("OTHER=keep-me", UNICODE_BYTE_ORDER_MARK + "TRELLO_API_KEY=invalid-later-bom");

        // when
        List<String> updated = TrelloCredentialStore.upsertEnv(lines, "TRELLO_API_KEY", "new-example-key");

        // then
        assertThat(updated)
                .containsExactly(
                        "OTHER=keep-me",
                        UNICODE_BYTE_ORDER_MARK + "TRELLO_API_KEY=invalid-later-bom",
                        "TRELLO_API_KEY=new-example-key");
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
                options.codexReviewer(),
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
