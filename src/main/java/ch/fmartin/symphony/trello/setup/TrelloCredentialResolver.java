package ch.fmartin.symphony.trello.setup;

import ch.fmartin.symphony.trello.TrelloEnvironment;
import ch.fmartin.symphony.trello.config.EnvironmentReferences;
import ch.fmartin.symphony.trello.config.LocalEnvironment;
import ch.fmartin.symphony.trello.setup.TrelloBoardSetup.TrelloCredentials;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/// The one implementation of the Trello credential precedence that setup, the CLI commands, and
/// worker start share: a value passed directly with `--key`/`--token` wins, then a non-blank shell
/// environment variable, then a non-blank value from the credential file. This stays
/// repository-owned code instead of a SmallRye Config source chain, because keeping this contract
/// on SmallRye Config takes more glue than the lookup it would replace. See
/// docs/adr/0101-share-one-trello-credential-resolver.md.
@NullMarked
final class TrelloCredentialResolver {
    private final Map<String, String> environment;
    private final Map<String, String> credentialFile;

    TrelloCredentialResolver(Map<String, String> environment, Path credentialFile) {
        this.environment = environment;
        this.credentialFile = LocalEnvironment.load(credentialFile);
    }

    /// Resolves both default Trello variables and rejects a reference-looking value that the
    /// credential file contributes, key first.
    CredentialSelection resolveTrelloCredentials(Optional<String> directApiKey, Optional<String> directApiToken) {
        return new CredentialSelection(
                resolve(TrelloEnvironment.API_KEY, directApiKey).requireLiteralCredentialFileValue(),
                resolve(TrelloEnvironment.API_TOKEN, directApiToken).requireLiteralCredentialFileValue());
    }

    /// Resolves one variable without the credential-file check, for callers such as worker start
    /// that must report missing credentials before a reference-looking file value. A present
    /// direct value wins even when blank, so `--key ""` never falls back to another source.
    CredentialValue resolve(String name, Optional<String> directValue) {
        return directValue
                .map(value -> CredentialValue.directInput(name, value))
                .or(() -> nonBlank(environment.get(name))
                        .map(value -> new CredentialValue(name, value, TrelloCredentialSource.SHELL_ENVIRONMENT)))
                .or(() -> nonBlank(credentialFile.get(name))
                        .map(value -> new CredentialValue(name, value, TrelloCredentialSource.DOTENV_FILE)))
                .orElseGet(() -> CredentialValue.missing(name));
    }

    private static Optional<String> nonBlank(@Nullable String value) {
        return Optional.ofNullable(value).filter(text -> !text.isBlank());
    }

    /// One resolved Trello credential. `name` is the environment variable it was looked up as. The
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
            return value == null || value.isBlank();
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

    record CredentialSelection(CredentialValue apiKeyValue, CredentialValue apiTokenValue) {
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
            if (apiKeyValue.source() == TrelloCredentialSource.SHELL_ENVIRONMENT
                    && apiTokenValue.source() == TrelloCredentialSource.SHELL_ENVIRONMENT) {
                return "environment variables";
            }
            if (apiKeyValue.source() == TrelloCredentialSource.DOTENV_FILE
                    && apiTokenValue.source() == TrelloCredentialSource.DOTENV_FILE) {
                return envPath.toString();
            }
            return "environment variables and " + envPath;
        }
    }
}
