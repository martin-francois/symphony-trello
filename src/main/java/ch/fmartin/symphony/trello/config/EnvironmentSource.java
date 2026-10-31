package ch.fmartin.symphony.trello.config;

import ch.fmartin.symphony.trello.TrelloEnvironment;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/// One place the local environment reads values from: the shell environment or one env file.
///
/// A source binds its own Trello key and token to one endpoint: the one it names in
/// `TRELLO_API_ENDPOINT`, or the Trello API when it names none. Only the source itself can set
/// that binding. A workflow file, another env file, or a command-line option never binds
/// credentials it did not supply; see docs/adr/0151-bind-trello-credentials-to-an-endpoint.md.
public sealed interface EnvironmentSource {
    /// The non-blank value of `name`, or empty when the source does not set it or sets it blank.
    Optional<String> value(String name);

    /// Names the source in messages, for example "the shell environment".
    String description();

    /// The step that adds a `NAME=value` line to this source, for messages that then print the line.
    String assignmentInstruction();

    /// The endpoint that this source's Trello key and token belong to.
    default String trelloEndpoint() {
        return value(TrelloEnvironment.API_ENDPOINT).orElse(TrelloCredentialEndpoint.TRELLO_API);
    }

    default Optional<EnvironmentValue> lookup(String name) {
        return value(name).map(value -> new EnvironmentValue(name, value, this));
    }

    static EnvironmentSource shellEnvironment(Map<String, String> environment) {
        return new ShellEnvironment(name -> Optional.ofNullable(environment.get(name)));
    }

    /// The process environment, or in tests a function that stands in for it.
    record ShellEnvironment(Function<String, Optional<String>> values) implements EnvironmentSource {
        @Override
        public Optional<String> value(String name) {
            return values.apply(name).filter(EnvironmentSource::hasText);
        }

        @Override
        public String description() {
            return "the shell environment";
        }

        @Override
        public String assignmentInstruction() {
            return "set this variable in the shell environment:";
        }
    }

    /// One dotenv file, read again on every lookup so an edit is seen without a restart, the way
    /// [LocalEnvironment#get(String)] has always read it.
    record EnvFile(Path path) implements EnvironmentSource {
        @Override
        public Optional<String> value(String name) {
            return Optional.ofNullable(LocalEnvironment.load(path).get(name)).filter(EnvironmentSource::hasText);
        }

        @Override
        public String description() {
            return "the env file " + path;
        }

        @Override
        public String assignmentInstruction() {
            return "add or replace this line in " + path + ":";
        }
    }

    private static boolean hasText(String value) {
        return !value.isBlank();
    }
}
