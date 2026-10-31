package ch.fmartin.symphony.trello.config;

import static ch.fmartin.symphony.trello.TextCharacterMatchers.DOTS;
import static ch.fmartin.symphony.trello.TextCharacterMatchers.SLASHES;

import ch.fmartin.symphony.trello.TrelloEnvironment;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.SequencedMap;
import java.util.stream.Collectors;

/// Binds a Trello key and token to one endpoint, the way SSH ties a key to a host.
///
/// Credentials read from an [EnvironmentSource] belong to the endpoint that the same source names
/// in `TRELLO_API_ENDPOINT`, or to the Trello API when it names none. Before a command sends them
/// anywhere, it compares that endpoint with the one it would send them to and refuses a mismatch.
/// A workflow file alone therefore cannot redirect credentials it did not supply, whether or not
/// the workflow is connected. Every command uses this one check; see
/// docs/adr/0151-bind-trello-credentials-to-an-endpoint.md.
public final class TrelloCredentialEndpoint {
    public static final String TRELLO_API = "https://api.trello.com/1";
    /// Code of the [ConfigException] for credentials whose source binds them elsewhere.
    public static final String MISMATCH_CODE = "credentials_endpoint_mismatch";
    /// The path of the Trello REST API; an endpoint without a path means this one.
    public static final String REST_API_PATH = "/1";
    private static final int NO_PORT = -1;

    private TrelloCredentialEndpoint() {}

    /// Whether two endpoints name the same place, compared in their [#canonical] form. A value
    /// that is not a URL compares as the text it is.
    public static boolean sameEndpoint(String left, String right) {
        return canonical(left)
                .map(leftUri -> canonical(right).map(leftUri::equals).orElse(false))
                .orElseGet(() -> left.strip().equals(right.strip()));
    }

    /// The one spelling of an endpoint that every comparison uses: scheme and host in lower case,
    /// no trailing dot in the host, no explicit default port, no trailing slashes, and the Trello
    /// REST API path for an endpoint that names none, so `https://api.trello.com` and
    /// `https://api.trello.com/1` are the same endpoint. Empty when the value is not an absolute
    /// URL with a host.
    public static Optional<URI> canonical(String endpoint) {
        URI uri;
        try {
            uri = new URI(endpoint.strip());
        } catch (URISyntaxException notAUrl) {
            return Optional.empty();
        }
        if (uri.getScheme() == null || uri.getHost() == null) {
            return Optional.empty();
        }
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        String host = DOTS.trimTrailingFrom(uri.getHost().toLowerCase(Locale.ROOT));
        int port = uri.getPort() == defaultPort(scheme) ? NO_PORT : uri.getPort();
        String path = SLASHES.trimTrailingFrom(Objects.requireNonNullElse(uri.getRawPath(), ""));
        try {
            var authority = new URI(scheme, uri.getUserInfo(), host, port, null, null, null);
            // Component constructors quote percent signs; the raw path must keep its escaped separators.
            StringBuilder normalized =
                    new StringBuilder(authority.toString()).append(path.isEmpty() ? REST_API_PATH : path);
            if (uri.getRawQuery() != null) {
                normalized.append('?').append(uri.getRawQuery());
            }
            if (uri.getRawFragment() != null) {
                normalized.append('#').append(uri.getRawFragment());
            }
            return Optional.of(new URI(normalized.toString()));
        } catch (URISyntaxException unexpected) {
            return Optional.empty();
        }
    }

    private static int defaultPort(String scheme) {
        return switch (scheme) {
            case "http" -> 80;
            case "https" -> 443;
            default -> NO_PORT;
        };
    }

    /// Refuses `endpoint` for every credential whose source binds it elsewhere. `target` names the
    /// setting that chose `endpoint`, such as "the workflow's tracker.endpoint". The message names
    /// every source whose credentials do not belong to `endpoint`, each with the line that binds
    /// it, so one run is enough to fix all of them.
    public static void requireBoundTo(List<EnvironmentValue> credentials, String endpoint, String target) {
        mismatch(credentials, endpoint, target).ifPresent(TrelloCredentialEndpoint::refuseMismatch);
    }

    private static void refuseMismatch(String message) {
        throw new ConfigException(MISMATCH_CODE, message);
    }

    /// Keeps credential-bearing or malformed endpoint values out of errors and terminal output.
    public static String displaySafe(String endpoint) {
        return canonical(endpoint)
                .filter(TrelloCredentialEndpoint::safeToDisplay)
                .map(_ -> endpoint.strip())
                .orElse("<redacted endpoint>");
    }

    private static boolean safeToDisplay(URI endpoint) {
        return ("http".equals(endpoint.getScheme()) || "https".equals(endpoint.getScheme()))
                && endpoint.getRawUserInfo() == null
                && endpoint.getRawQuery() == null
                && endpoint.getRawFragment() == null;
    }

    /// The refusal that [#requireBoundTo] raises, or empty when every credential may go there.
    public static Optional<String> mismatch(List<EnvironmentValue> credentials, String endpoint, String target) {
        // Credentials group by their source in encounter order, so the key's source comes first.
        SequencedMap<EnvironmentSource, List<String>> namesBySource = credentials.stream()
                .filter(credential -> !sameEndpoint(credential.source().trelloEndpoint(), endpoint))
                .collect(Collectors.groupingBy(
                        EnvironmentValue::source,
                        LinkedHashMap::new,
                        Collectors.mapping(EnvironmentValue::name, Collectors.toUnmodifiableList())));
        if (namesBySource.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(mismatchMessage(namesBySource, endpoint, target));
    }

    private static String mismatchMessage(
            SequencedMap<EnvironmentSource, List<String>> namesBySource, String endpoint, String target) {
        String displayedEndpoint = displaySafe(endpoint);
        String ownership = namesBySource.entrySet().stream()
                .map(entry -> ownership(entry.getKey(), entry.getValue()))
                .collect(Collectors.joining(", and "));
        String instructions = namesBySource.keySet().stream()
                .map(source -> assignmentInstruction(source, displayedEndpoint))
                .collect(Collectors.joining("\nand "));
        return ownership + ", but " + target + " is " + displayedEndpoint
                + ". Symphony sends a Trello key and token only to the endpoint they belong to."
                + "\nTo send them to " + displayedEndpoint + " instead, " + instructions;
    }

    private static String assignmentInstruction(EnvironmentSource source, String endpoint) {
        return source.assignmentInstruction() + "\n  " + TrelloEnvironment.API_ENDPOINT + "=" + endpoint;
    }

    /// "TRELLO_API_KEY and TRELLO_API_TOKEN from the env file X belong to Y" for one source.
    private static String ownership(EnvironmentSource source, List<String> names) {
        String verb = names.size() == 1 ? "belongs" : "belong";
        String bound = source.value(TrelloEnvironment.API_ENDPOINT)
                .map(TrelloCredentialEndpoint::displaySafe)
                .map(configured ->
                        configured + ", the " + TrelloEnvironment.API_ENDPOINT + " of " + source.description())
                .orElseGet(() -> TRELLO_API + ", the default while " + source.description() + " does not set "
                        + TrelloEnvironment.API_ENDPOINT);
        return String.join(" and ", names) + " from " + source.description() + " " + verb + " to " + bound;
    }
}
