package ch.fmartin.symphony.trello.config;

import static ch.fmartin.symphony.trello.testsupport.TestEndpointValues.FOREIGN_ENDPOINT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import ch.fmartin.symphony.trello.TrelloEnvironment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

final class TrelloCredentialEndpointTest {
    private static final String TARGET = "the workflow's tracker.endpoint";

    @TempDir
    Path tempDir;

    @MethodSource("equivalentEndpoints")
    @ParameterizedTest(name = "{0}")
    void endpointsThatDifferOnlyInCaseDefaultPortOrTrailingSlashAreTheSame(String scenario, String left, String right) {
        // given
        // The scenario supplies both endpoint spellings.

        // when
        boolean same = TrelloCredentialEndpoint.sameEndpoint(left, right);

        // then
        assertThat(same).as("%s: %s must equal %s", scenario, left, right).isTrue();
    }

    private static Stream<Arguments> equivalentEndpoints() {
        return Stream.of(
                Arguments.of("identical", TrelloCredentialEndpoint.TRELLO_API, "https://api.trello.com/1"),
                Arguments.of("no path means the REST API path", "https://api.trello.com", "https://api.trello.com/1"),
                Arguments.of("root path means the REST API path", "http://127.0.0.1:8080/", "http://127.0.0.1:8080/1"),
                Arguments.of("scheme case", "HTTPS://api.trello.com/1", "https://api.trello.com/1"),
                Arguments.of("host case", "https://API.Trello.COM/1", "https://api.trello.com/1"),
                Arguments.of(
                        "percent-escape case",
                        "https://proxy.example.test/tenant%2Fteam/1",
                        "https://proxy.example.test/tenant%2fteam/1"),
                Arguments.of("trailing dot in host", "https://api.trello.com./1", "https://api.trello.com/1"),
                Arguments.of("default https port", "https://api.trello.com:443/1", "https://api.trello.com/1"),
                Arguments.of("default http port", "http://127.0.0.1:80/1", "http://127.0.0.1/1"),
                Arguments.of("trailing slash", "https://api.trello.com/1/", "https://api.trello.com/1"),
                Arguments.of("several trailing slashes", "https://api.trello.com/1///", "https://api.trello.com/1"),
                Arguments.of("surrounding whitespace", " https://api.trello.com/1 ", "https://api.trello.com/1"),
                Arguments.of("not a URL", "not a url", "not a url"));
    }

    @MethodSource("differentEndpoints")
    @ParameterizedTest(name = "{0}")
    void endpointsThatNameAnotherPlaceDiffer(String scenario, String left, String right) {
        // given
        // The scenario supplies both endpoint spellings.

        // when
        boolean same = TrelloCredentialEndpoint.sameEndpoint(left, right);

        // then
        assertThat(same).as("%s: %s must differ from %s", scenario, left, right).isFalse();
    }

    private static Stream<Arguments> differentEndpoints() {
        return Stream.of(
                Arguments.of("other host", "https://trello.example.test/1", "https://api.trello.com/1"),
                Arguments.of("other scheme", "http://api.trello.com/1", "https://api.trello.com/1"),
                Arguments.of("other port", "http://127.0.0.1:18081/1", "http://127.0.0.1:18080/1"),
                Arguments.of("non-default port", "https://api.trello.com:8443/1", "https://api.trello.com/1"),
                Arguments.of("other path", "https://api.trello.com/2", "https://api.trello.com/1"),
                Arguments.of("inner slash", "https://api.trello.com//1", "https://api.trello.com/1"),
                Arguments.of(
                        "encoded separator",
                        "https://proxy.example.test/tenant%2F1",
                        "https://proxy.example.test/tenant/1"),
                Arguments.of(
                        "encoded trailing slash", "https://proxy.example.test/1%2F", "https://proxy.example.test/1"),
                Arguments.of("query", "https://api.trello.com/1?x=y", "https://api.trello.com/1"));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "https://user:password@example.invalid/1",
                "https://api.trello.com/1?token=synthetic-token",
                "https://api.trello.com/1#synthetic-token",
                "not a url synthetic-password"
            })
    void mismatchedEndpointMessagesDoNotExposeUnsafeEndpointValues(String unsafeEndpoint) {
        // given
        EnvironmentSource unsafeSource =
                EnvironmentSource.shellEnvironment(Map.of(TrelloEnvironment.API_ENDPOINT, unsafeEndpoint));
        EnvironmentSource defaultSource = EnvironmentSource.shellEnvironment(Map.of());

        // when
        Optional<String> sourceRefusal = TrelloCredentialEndpoint.mismatch(
                credentials(unsafeSource), TrelloCredentialEndpoint.TRELLO_API, TARGET);
        Optional<String> targetRefusal =
                TrelloCredentialEndpoint.mismatch(credentials(defaultSource), unsafeEndpoint, TARGET);

        // then
        assertThat(sourceRefusal).hasValueSatisfying(message -> assertThat(message)
                .contains(TrelloEnvironment.API_ENDPOINT)
                .doesNotContain(unsafeEndpoint, "password", "synthetic-token"));
        assertThat(targetRefusal).hasValueSatisfying(message -> assertThat(message)
                .contains(TARGET)
                .doesNotContain(unsafeEndpoint, "password", "synthetic-token"));
    }

    @Test
    void credentialsFromAnEnvFileWithoutABindingBelongToTheTrelloApi() throws Exception {
        // given
        Path envFile = envFile("TRELLO_API_KEY=key\nTRELLO_API_TOKEN=token\n");
        EnvironmentSource source = new EnvironmentSource.EnvFile(envFile);

        // when
        Optional<String> refusal = TrelloCredentialEndpoint.mismatch(credentials(source), FOREIGN_ENDPOINT, TARGET);

        // then
        assertThat(refusal)
                .hasValue("TRELLO_API_KEY and TRELLO_API_TOKEN from the env file " + envFile + " belong to "
                        + "https://api.trello.com/1, the default while the env file " + envFile
                        + " does not set TRELLO_API_ENDPOINT, but the workflow's tracker.endpoint is "
                        + FOREIGN_ENDPOINT + ". Symphony sends a Trello key and token only to the endpoint they "
                        + "belong to.\nTo send them to " + FOREIGN_ENDPOINT
                        + " instead, add or replace this line in " + envFile + ":\n  TRELLO_API_ENDPOINT="
                        + FOREIGN_ENDPOINT);
    }

    @Test
    void credentialsBoundToAnotherEndpointNameThatBinding() throws Exception {
        // given
        Path envFile =
                envFile("TRELLO_API_KEY=key\nTRELLO_API_TOKEN=token\nTRELLO_API_ENDPOINT=" + FOREIGN_ENDPOINT + "\n");
        EnvironmentSource source = new EnvironmentSource.EnvFile(envFile);

        // when
        Optional<String> refusal =
                TrelloCredentialEndpoint.mismatch(credentials(source), TrelloCredentialEndpoint.TRELLO_API, TARGET);

        // then
        assertThat(refusal).hasValueSatisfying(message -> assertThat(message)
                .startsWith("TRELLO_API_KEY and TRELLO_API_TOKEN from the env file " + envFile + " belong to "
                        + FOREIGN_ENDPOINT + ", the TRELLO_API_ENDPOINT of the env file " + envFile
                        + ", but the workflow's tracker.endpoint is https://api.trello.com/1.")
                .endsWith("TRELLO_API_ENDPOINT=https://api.trello.com/1"));
    }

    @Test
    void shellEnvironmentCredentialsAreBoundThroughTheShellVariable() {
        // given
        EnvironmentSource shell = EnvironmentSource.shellEnvironment(Map.of("TRELLO_API_KEY", "key"));
        List<EnvironmentValue> credentials = List.of(new EnvironmentValue("TRELLO_API_KEY", "key", shell));

        // when
        Optional<String> refusal = TrelloCredentialEndpoint.mismatch(credentials, FOREIGN_ENDPOINT, "--endpoint");

        // then
        assertThat(refusal)
                .hasValue("TRELLO_API_KEY from the shell environment belongs to https://api.trello.com/1, the "
                        + "default while the shell environment does not set TRELLO_API_ENDPOINT, but --endpoint "
                        + "is " + FOREIGN_ENDPOINT + ". Symphony sends a Trello key and token only to the endpoint "
                        + "they belong to.\nTo send them to " + FOREIGN_ENDPOINT
                        + " instead, set this variable in the shell environment:\n  TRELLO_API_ENDPOINT="
                        + FOREIGN_ENDPOINT);
    }

    @Test
    void credentialsBoundToTheEndpointInAnotherSpellingAreAccepted() {
        // given
        EnvironmentSource shell = EnvironmentSource.shellEnvironment(
                Map.of("TRELLO_API_KEY", "key", TrelloEnvironment.API_ENDPOINT, "HTTPS://Trello.Example.Test:443/1/"));

        // when
        Optional<String> refusal = TrelloCredentialEndpoint.mismatch(
                List.of(new EnvironmentValue("TRELLO_API_KEY", "key", shell)), FOREIGN_ENDPOINT, TARGET);

        // then
        assertThat(refusal).isEmpty();
    }

    @Test
    void credentialsFromTwoUnboundSourcesAreNamedWithBothLinesToAdd() throws Exception {
        // given
        EnvironmentSource shell = EnvironmentSource.shellEnvironment(Map.of("TRELLO_API_KEY", "key"));
        Path envFile = envFile("TRELLO_API_TOKEN=token\n");
        EnvironmentSource file = new EnvironmentSource.EnvFile(envFile);
        List<EnvironmentValue> credentials = List.of(
                new EnvironmentValue("TRELLO_API_KEY", "key", shell),
                new EnvironmentValue("TRELLO_API_TOKEN", "token", file));

        // when
        Optional<String> refusal = TrelloCredentialEndpoint.mismatch(credentials, FOREIGN_ENDPOINT, TARGET);

        // then
        assertThat(refusal)
                .hasValue("TRELLO_API_KEY from the shell environment belongs to https://api.trello.com/1, the "
                        + "default while the shell environment does not set TRELLO_API_ENDPOINT, and "
                        + "TRELLO_API_TOKEN from the env file " + envFile + " belongs to https://api.trello.com/1, "
                        + "the default while the env file " + envFile + " does not set TRELLO_API_ENDPOINT, but "
                        + TARGET + " is " + FOREIGN_ENDPOINT + ". Symphony sends a Trello key and token only to "
                        + "the endpoint they belong to.\nTo send them to " + FOREIGN_ENDPOINT
                        + " instead, set this variable in the shell environment:\n  TRELLO_API_ENDPOINT="
                        + FOREIGN_ENDPOINT + "\nand add or replace this line in " + envFile
                        + ":\n  TRELLO_API_ENDPOINT=" + FOREIGN_ENDPOINT);
    }

    @Test
    void requireBoundToRaisesTheMismatchAsAConfigError() {
        // given
        EnvironmentSource shell = EnvironmentSource.shellEnvironment(Map.of("TRELLO_API_KEY", "key"));
        List<EnvironmentValue> credentials = List.of(new EnvironmentValue("TRELLO_API_KEY", "key", shell));

        // when
        ConfigException thrown = catchThrowableOfType(
                ConfigException.class,
                () -> TrelloCredentialEndpoint.requireBoundTo(credentials, FOREIGN_ENDPOINT, TARGET));

        // then
        assertThat(thrown)
                .isNotNull()
                .hasMessageContaining("TRELLO_API_KEY from the shell environment belongs to")
                .extracting(ConfigException::code)
                .isEqualTo(TrelloCredentialEndpoint.MISMATCH_CODE);
    }

    @Test
    void onlyTheCredentialsOfTheMismatchedSourceAreNamed() throws Exception {
        // given
        EnvironmentSource shell = EnvironmentSource.shellEnvironment(
                Map.of("TRELLO_API_KEY", "key", TrelloEnvironment.API_ENDPOINT, FOREIGN_ENDPOINT));
        EnvironmentSource file = new EnvironmentSource.EnvFile(envFile("TRELLO_API_TOKEN=token\n"));
        List<EnvironmentValue> credentials = List.of(
                new EnvironmentValue("TRELLO_API_KEY", "key", shell),
                new EnvironmentValue("TRELLO_API_TOKEN", "token", file));

        // when
        Optional<String> refusal = TrelloCredentialEndpoint.mismatch(credentials, FOREIGN_ENDPOINT, TARGET);

        // then
        assertThat(refusal).hasValueSatisfying(message -> assertThat(message)
                .startsWith("TRELLO_API_TOKEN from the env file ")
                .doesNotContain("TRELLO_API_KEY"));
    }

    private static List<EnvironmentValue> credentials(EnvironmentSource source) {
        return List.of(
                new EnvironmentValue("TRELLO_API_KEY", "key", source),
                new EnvironmentValue("TRELLO_API_TOKEN", "token", source));
    }

    private Path envFile(String content) throws Exception {
        Path envFile = tempDir.resolve(".env");
        Files.writeString(envFile, content);
        return envFile;
    }
}
