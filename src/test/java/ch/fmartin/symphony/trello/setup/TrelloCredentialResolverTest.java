package ch.fmartin.symphony.trello.setup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import ch.fmartin.symphony.trello.TrelloEnvironment;
import ch.fmartin.symphony.trello.setup.TrelloCredentialResolver.CredentialSelection;
import ch.fmartin.symphony.trello.setup.TrelloCredentialResolver.CredentialValue;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

final class TrelloCredentialResolverTest {
    private static final String NAME = TrelloEnvironment.API_KEY;
    private static final String DIRECT = "direct-value";
    private static final String SHELL = "shell-value";
    private static final String FILE = "file-value";
    private static final String BLANK = "  ";
    private static final String FILE_REFERENCE = "${" + NAME + "}";
    private static final Path DESCRIBED_PATH = Path.of(".env.board");

    @TempDir
    Path tempDir;

    @MethodSource("precedenceScenarios")
    @ParameterizedTest(name = "{0}")
    void resolvesDirectValueThenShellEnvironmentThenCredentialFile(PrecedenceScenario scenario) throws Exception {
        // given
        TrelloCredentialResolver resolver = resolver(scenario.shellValue(), scenario.fileValue());

        // when
        CredentialValue resolved = resolver.resolve(NAME, scenario.directValue());

        // then
        assertThat(resolved)
                .as(scenario.name())
                .isEqualTo(new CredentialValue(NAME, scenario.expectedValue(), scenario.expectedSource()));
    }

    private static Stream<PrecedenceScenario> precedenceScenarios() {
        return Stream.of(
                new PrecedenceScenario(
                        "direct value wins over shell and file",
                        Optional.of(DIRECT),
                        SHELL,
                        FILE,
                        DIRECT,
                        TrelloCredentialSource.DIRECT_INPUT),
                new PrecedenceScenario(
                        "blank direct value still wins",
                        Optional.of(""),
                        SHELL,
                        FILE,
                        "",
                        TrelloCredentialSource.DIRECT_INPUT),
                new PrecedenceScenario(
                        "shell wins over file",
                        Optional.empty(),
                        SHELL,
                        FILE,
                        SHELL,
                        TrelloCredentialSource.SHELL_ENVIRONMENT),
                new PrecedenceScenario(
                        "blank shell falls back to file",
                        Optional.empty(),
                        BLANK,
                        FILE,
                        FILE,
                        TrelloCredentialSource.DOTENV_FILE),
                new PrecedenceScenario(
                        "file alone", Optional.empty(), null, FILE, FILE, TrelloCredentialSource.DOTENV_FILE),
                new PrecedenceScenario(
                        "blank shell and empty file are missing",
                        Optional.empty(),
                        BLANK,
                        "",
                        null,
                        TrelloCredentialSource.MISSING),
                new PrecedenceScenario(
                        "nothing set is missing", Optional.empty(), null, null, null, TrelloCredentialSource.MISSING));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"$TRELLO_API_KEY", "${TRELLO_API_KEY}", " ${TRELLO_API_KEY:-fallback} ", "${"})
    void rejectsReferenceLookingValueFromCredentialFile(String fileValue) throws Exception {
        // given
        TrelloCredentialResolver resolver = resolver(null, fileValue);

        // when
        TrelloBoardSetupException thrown =
                catchThrowableOfType(TrelloBoardSetupException.class, () -> resolver.resolve(NAME, Optional.empty())
                        .requireLiteralCredentialFileValue());

        // then
        assertThat(thrown)
                .as("credential file value %s must be rejected", fileValue)
                .isNotNull()
                .hasMessageContaining(NAME + " in the credential file looks like the environment reference")
                .extracting(TrelloBoardSetupException::code)
                .isEqualTo("setup_credentials_environment_reference");
    }

    @MethodSource("winningSourcesThatSkipTheCredentialFileCheck")
    @ParameterizedTest(name = "{0}")
    void credentialFileCheckIgnoresValueThatAnotherSourceOverrides(
            String scenario,
            Optional<String> directValue,
            @Nullable String shellValue,
            TrelloCredentialSource expectedSource)
            throws Exception {
        // given
        TrelloCredentialResolver resolver = resolver(shellValue, FILE_REFERENCE);

        // when
        CredentialValue resolved = resolver.resolve(NAME, directValue).requireLiteralCredentialFileValue();

        // then
        assertThat(resolved.source()).as(scenario).isEqualTo(expectedSource);
    }

    private static Stream<Arguments> winningSourcesThatSkipTheCredentialFileCheck() {
        return Stream.of(
                Arguments.of(
                        "direct reference-looking value is used as entered",
                        Optional.of(FILE_REFERENCE),
                        null,
                        TrelloCredentialSource.DIRECT_INPUT),
                Arguments.of("shell value wins", Optional.empty(), SHELL, TrelloCredentialSource.SHELL_ENVIRONMENT),
                Arguments.of(
                        "reference-looking shell value is used as exported",
                        Optional.empty(),
                        FILE_REFERENCE,
                        TrelloCredentialSource.SHELL_ENVIRONMENT));
    }

    @Test
    void resolvesBothCredentialsAndRejectsTheApiKeyFirst() throws Exception {
        // given
        var resolver = new TrelloCredentialResolver(
                Map.of(),
                credentialFile(
                        Map.of(TrelloEnvironment.API_KEY, "$OTHER_KEY", TrelloEnvironment.API_TOKEN, "$OTHER_TOKEN")));

        // when
        TrelloBoardSetupException thrown = catchThrowableOfType(
                TrelloBoardSetupException.class,
                () -> resolver.resolveTrelloCredentials(Optional.empty(), Optional.empty()));

        // then
        assertThat(thrown).hasMessageStartingWith(TrelloEnvironment.API_KEY + " in the credential file");
    }

    @Test
    void persistsOnlyDirectlyEnteredValues() throws Exception {
        // given
        var resolver =
                new TrelloCredentialResolver(Map.of(), credentialFile(Map.of(TrelloEnvironment.API_TOKEN, FILE)));

        // when
        CredentialSelection selection = resolver.resolveTrelloCredentials(Optional.of(DIRECT), Optional.empty());

        // then
        assertThat(selection)
                .returns(true, CredentialSelection::persistApiKey)
                .returns(false, CredentialSelection::persistApiToken)
                .returns(true, CredentialSelection::persist);
    }

    @MethodSource("sourceDescriptionScenarios")
    @ParameterizedTest(name = "{0}")
    void describesWhereLoadedCredentialsCameFrom(
            String scenario, Map<String, String> environment, Map<String, String> fileValues, String expected)
            throws Exception {
        // given
        var resolver = new TrelloCredentialResolver(environment, credentialFile(fileValues));

        // when
        String description = resolver.resolveTrelloCredentials(Optional.empty(), Optional.empty())
                .sourceDescription(DESCRIBED_PATH);

        // then
        assertThat(description).as(scenario).isEqualTo(expected);
    }

    private static Stream<Arguments> sourceDescriptionScenarios() {
        Map<String, String> bothCredentials =
                Map.of(TrelloEnvironment.API_KEY, SHELL, TrelloEnvironment.API_TOKEN, SHELL);
        return Stream.of(
                Arguments.of("both from the shell", bothCredentials, Map.of(), "environment variables"),
                Arguments.of("both from the file", Map.of(), bothCredentials, DESCRIBED_PATH.toString()),
                Arguments.of(
                        "key from the shell, token from the file",
                        Map.of(TrelloEnvironment.API_KEY, SHELL),
                        Map.of(TrelloEnvironment.API_TOKEN, FILE),
                        "environment variables and " + DESCRIBED_PATH));
    }

    private TrelloCredentialResolver resolver(@Nullable String shellValue, @Nullable String fileValue)
            throws Exception {
        Map<String, String> environment = shellValue == null ? Map.of() : Map.of(NAME, shellValue);
        return new TrelloCredentialResolver(
                environment, credentialFile(fileValue == null ? Map.of() : Map.of(NAME, fileValue)));
    }

    private Path credentialFile(Map<String, String> values) throws Exception {
        Path credentialFile = tempDir.resolve(".env");
        var content = new StringBuilder();
        values.forEach(
                (name, value) -> content.append(name).append('=').append(value).append('\n'));
        Files.writeString(credentialFile, content);
        return credentialFile;
    }

    record PrecedenceScenario(
            String name,
            Optional<String> directValue,
            @Nullable String shellValue,
            @Nullable String fileValue,
            @Nullable String expectedValue,
            TrelloCredentialSource expectedSource) {
        @Override
        public String toString() {
            return name;
        }
    }
}
