package ch.fmartin.symphony.trello.setup;

import ch.fmartin.symphony.trello.TrelloEnvironment;
import ch.fmartin.symphony.trello.config.ConfigException;
import ch.fmartin.symphony.trello.config.EnvironmentReferences;
import ch.fmartin.symphony.trello.config.EnvironmentSource;
import ch.fmartin.symphony.trello.config.EnvironmentValue;
import ch.fmartin.symphony.trello.config.TrelloCredentialEndpoint;
import ch.fmartin.symphony.trello.setup.TrelloBoardSetup.TrelloCredentials;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/// The one implementation of the Trello credential precedence that setup, the CLI commands, and
/// worker start share: a value passed directly with `--key`/`--token` wins, then a non-blank shell
/// environment variable, then a non-blank value from the credential file. This stays
/// repository-owned code instead of a SmallRye Config source chain, because keeping this contract
/// on SmallRye Config takes more glue than the lookup it would replace. See
/// docs/adr/0101-share-one-trello-credential-resolver.md.
///
/// A key and token from the shell environment or the credential file belong to the endpoint that
/// the same source names in `TRELLO_API_ENDPOINT`, so a command states the endpoint it will send
/// them to and is refused when they belong elsewhere; see
/// docs/adr/0151-bind-trello-credentials-to-an-endpoint.md.
@NullMarked
final class TrelloCredentialResolver {
    /// Code of the setup failure for credentials whose source binds them to another endpoint.
    static final String ENDPOINT_MISMATCH_CODE = "setup_credentials_endpoint_mismatch";
    private static final String ENDPOINT_OPTION = "--endpoint";

    private final EnvironmentSource shellEnvironment;
    private final EnvironmentSource credentialFile;

    TrelloCredentialResolver(Map<String, String> environment, Path credentialFile) {
        this.shellEnvironment = EnvironmentSource.shellEnvironment(environment);
        this.credentialFile = new EnvironmentSource.EnvFile(credentialFile);
    }

    /// Resolves both default Trello variables for a command that sends them to `endpoint`. Rejects
    /// a reference-looking value that the credential file contributes, key first, then a source
    /// whose credentials belong to another endpoint.
    CredentialSelection resolveTrelloCredentials(
            Optional<String> directApiKey, Optional<String> directApiToken, URI endpoint) {
        CredentialValue apiKey =
                resolve(TrelloEnvironment.API_KEY, directApiKey).requireLiteralCredentialFileValue();
        CredentialValue apiToken =
                resolve(TrelloEnvironment.API_TOKEN, directApiToken).requireLiteralCredentialFileValue();
        requireBoundTo(List.of(apiKey, apiToken), endpoint.toString());
        return new CredentialSelection(apiKey, apiToken, endpoint);
    }

    /// Resolves one variable without the credential-file check, for callers such as worker start
    /// that must report missing credentials before a reference-looking file value. A present
    /// direct value wins even when blank, so `--key ""` never falls back to another source. Setup
    /// then prompts for the value, and the CLI commands reject it as missing.
    CredentialValue resolve(String name, Optional<String> directValue) {
        return directValue
                .map(value -> CredentialValue.directInput(name, value))
                .or(() -> credentialValue(shellEnvironment, name, TrelloCredentialSource.SHELL_ENVIRONMENT))
                .or(() -> credentialValue(credentialFile, name, TrelloCredentialSource.DOTENV_FILE))
                .orElseGet(() -> CredentialValue.missing(name));
    }

    private static Optional<CredentialValue> credentialValue(
            EnvironmentSource environmentSource, String name, TrelloCredentialSource source) {
        return environmentSource.value(name).map(value -> new CredentialValue(name, value, source));
    }

    /// Fails for a credential that an environment source supplied for another endpoint than the
    /// `--endpoint` the command sends to. A direct `--key` or `--token` was entered for this very
    /// command, so it goes where the command goes.
    private void requireBoundTo(List<CredentialValue> credentials, String endpoint) {
        List<EnvironmentValue> environmentCredentials = credentials.stream()
                .flatMap(credential -> environmentValue(credential).stream())
                .toList();
        try {
            TrelloCredentialEndpoint.requireBoundTo(environmentCredentials, endpoint, ENDPOINT_OPTION);
        } catch (ConfigException mismatch) {
            throw endpointMismatch(mismatch);
        }
    }

    /// The value as its environment source supplied it, for a shell or credential-file value.
    private Optional<EnvironmentValue> environmentValue(CredentialValue credential) {
        return switch (credential.source()) {
            case SHELL_ENVIRONMENT -> Optional.of(environmentValue(credential, shellEnvironment));
            case DOTENV_FILE -> Optional.of(environmentValue(credential, credentialFile));
            case DIRECT_INPUT, WORKFLOW_CONFIG, MISSING -> Optional.empty();
        };
    }

    private static EnvironmentValue environmentValue(CredentialValue credential, EnvironmentSource source) {
        return new EnvironmentValue(credential.name(), Objects.requireNonNull(credential.value()), source);
    }

    /// The setup failure for the refusal that [TrelloCredentialEndpoint#requireBoundTo] raised.
    static TrelloBoardSetupException endpointMismatch(ConfigException cause) {
        return new TrelloBoardSetupException(ENDPOINT_MISMATCH_CODE, cause.getMessage(), cause);
    }

    /// One resolved Trello credential. `name` is the environment variable that error messages and
    /// remediation hints name: the variable it was looked up as, or for
    /// [TrelloCredentialSource#WORKFLOW_CONFIG] the default variable, which was never read. The
    /// value is `null` for [TrelloCredentialSource#MISSING] and
    /// [TrelloCredentialSource#WORKFLOW_CONFIG].
    record CredentialValue(String name, @Nullable String value, TrelloCredentialSource source) {
        static CredentialValue directInput(String name, @Nullable String value) {
            return new CredentialValue(name, value, TrelloCredentialSource.DIRECT_INPUT);
        }

        static CredentialValue missing(String name) {
            return new CredentialValue(name, null, TrelloCredentialSource.MISSING);
        }

        static CredentialValue workflowConfig(String name) {
            return new CredentialValue(name, null, TrelloCredentialSource.WORKFLOW_CONFIG);
        }

        /// Only directly entered values are saved. Environment and credential-file values already
        /// live somewhere the user controls and must not be copied into another file.
        boolean persist() {
            return source == TrelloCredentialSource.DIRECT_INPUT;
        }

        boolean blank() {
            return CliValueNormalizer.blank(value);
        }

        /// Credential file values are used literally and never expanded. A value that looks like an
        /// environment reference is a local configuration mistake that would otherwise reach Trello
        /// as a literal credential and fail as a misleading authentication error. The check applies
        /// only when the credential file supplied the value, so a shell variable that wins keeps
        /// the file line unchecked.
        CredentialValue requireLiteralCredentialFileValue() {
            if (source == TrelloCredentialSource.DOTENV_FILE && value != null && looksLikeEnvironmentReference(value)) {
                throw new TrelloBoardSetupException(
                        "setup_credentials_environment_reference",
                        name + " in the credential file looks like the environment reference " + value.trim()
                                + ", but credential file values are used literally. Put the actual value in the"
                                + " credential file, or remove the line and export " + name
                                + " in the shell environment.");
            }
            return this;
        }

        private static boolean looksLikeEnvironmentReference(String value) {
            return EnvironmentReferences.referenceName(value).isPresent()
                    || value.trim().startsWith("${");
        }
    }

    /// The key and token a command resolved, with the `endpoint` it checked them against and will
    /// send them to.
    record CredentialSelection(CredentialValue apiKeyValue, CredentialValue apiTokenValue, URI endpoint) {
        TrelloCredentials credentials() {
            return new TrelloCredentials(apiKeyValue.value(), apiTokenValue.value());
        }

        @Nullable
        String apiKey() {
            return apiKeyValue.value();
        }

        @Nullable
        String apiToken() {
            return apiTokenValue.value();
        }

        boolean persist() {
            return persistApiKey() || persistApiToken();
        }

        boolean persistApiKey() {
            return apiKeyValue.persist();
        }

        boolean persistApiToken() {
            return apiTokenValue.persist();
        }

        String sourceDescription(Path envPath) {
            return switch (TrelloCredentialSource.Pair.of(apiKeyValue.source(), apiTokenValue.source())) {
                case SHELL_ENVIRONMENT -> "environment variables";
                case DOTENV_FILE -> envPath.toString();
                case MIXED -> "environment variables and " + envPath;
            };
        }
    }
}
