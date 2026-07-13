package ch.fmartin.symphony.trello.repository;

import static ch.fmartin.symphony.trello.TextCharacterMatchers.SLASHES;

import ch.fmartin.symphony.trello.config.EffectiveConfig;
import ch.fmartin.symphony.trello.domain.Card;
import com.google.common.base.CharMatcher;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public final class RepositorySourceResolver {
    private static final CharMatcher LEADING_TOKEN_PUNCTUATION = CharMatcher.anyOf("<([{");
    private static final CharMatcher TRAILING_TOKEN_PUNCTUATION = CharMatcher.anyOf(".,;:)>]}");
    private static final int URI_PORT_ABSENT = -1;
    private static final int MIN_URI_PORT = 1;
    private static final int MAX_URI_PORT = 65_535;
    private static final Pattern LABELED_SOURCE = Pattern.compile(
            "(?i)^[\\t ]*(repository[\\t ]+(?:url|path)|repo[\\t ]+(?:url|path)|local[\\t ]+(?:checkout|path)|checkout|repository|repo)[\\t ]*:[\\t ]*(.*)$");
    private static final Pattern LINE_BREAK = Pattern.compile("\\R");
    private static final Pattern URI_SCHEME = Pattern.compile("^([A-Za-z][A-Za-z0-9+.-]*):");
    private static final Pattern WINDOWS_DRIVE_PATH = Pattern.compile("^[A-Za-z]:[\\\\/].*");
    private static final String REPOSITORY_SOURCE_CONFLICT_CODE = "repository_source_conflict";
    private static final String REPOSITORY_SOURCE_CONFLICT_GUIDANCE =
            "Multiple explicit repository source declarations conflict. Keep one source declaration or make every declaration identical.";

    public RepositorySourceSelection select(Card card, EffectiveConfig.RepositoryConfig repository) {
        RepositorySourceSelection explicit = explicitSource(card);
        if (explicit.status() != RepositorySourceSelection.Status.NONE) {
            return explicit;
        }
        return workflowDefault(repository);
    }

    /**
     * Validates a repository URL intended for {@code repository.default_url} without resolving or
     * contacting the repository.
     */
    public RepositorySourceSelection selectWorkflowDefaultUrl(String value) {
        if (!RepositorySourceText.safePromptLine(value)) {
            return invalid(
                    "repository_remote_malformed",
                    "The selected repository URL contains unsupported control characters.");
        }
        String candidate = value.strip();
        if (candidate.contains("?") || candidate.contains("#")) {
            return invalid(
                    "repository_remote_malformed", "Repository URLs must not include query strings or fragments.");
        }
        if (!candidate.equals(stripTokenPunctuation(candidate))) {
            return invalid(
                    "repository_remote_malformed",
                    "The selected repository URL contains unsupported token punctuation.");
        }
        return parse(candidate, RepositorySource.Origin.WORKFLOW_DEFAULT_URL, SourceMode.REMOTE_OR_FILE);
    }

    private RepositorySourceSelection explicitSource(Card card) {
        List<Declaration> declarations = declarations(card);

        return switch (declarations.size()) {
            case 0 -> RepositorySourceSelection.none();
            case 1 -> parseExplicitSource(declarations.getFirst());
            default -> explicitSourceFromMultipleDeclarations(declarations);
        };
    }

    private static RepositorySourceSelection explicitSourceFromMultipleDeclarations(List<Declaration> declarations) {
        RepositorySourceSelection first = parseExplicitSource(declarations.getFirst());

        boolean allEquivalent = first.status() == RepositorySourceSelection.Status.SELECTED
                && declarations.stream()
                        .skip(1)
                        .map(RepositorySourceResolver::parseExplicitSource)
                        .allMatch(selection -> equivalent(first, selection));

        return allEquivalent ? first : repositorySourceConflict();
    }

    private static RepositorySourceSelection parseExplicitSource(Declaration declaration) {
        return parse(declaration.value(), RepositorySource.Origin.CARD, declaration.mode());
    }

    private static RepositorySourceSelection repositorySourceConflict() {
        return invalid(REPOSITORY_SOURCE_CONFLICT_CODE, REPOSITORY_SOURCE_CONFLICT_GUIDANCE);
    }

    private RepositorySourceSelection workflowDefault(EffectiveConfig.RepositoryConfig repository) {
        if (repository.defaultUrl() != null) {
            RepositorySourceSelection selection = parse(
                    repository.defaultUrl(), RepositorySource.Origin.WORKFLOW_DEFAULT_URL, SourceMode.REMOTE_OR_FILE);
            if (selection.status() == RepositorySourceSelection.Status.INVALID_SELECTED) {
                return RepositorySourceSelection.invalidWorkflowFallback(selection.problem());
            }
            return selection;
        }
        if (repository.defaultPath() != null) {
            return RepositorySourceSelection.selected(new RepositorySource(
                    RepositorySource.Kind.LOCAL_PATH,
                    RepositorySource.Origin.WORKFLOW_DEFAULT_PATH,
                    repository.defaultPath().toString(),
                    null,
                    repository.defaultPath().toAbsolutePath().normalize()));
        }
        return RepositorySourceSelection.none();
    }

    private static RepositorySourceSelection parse(String rawValue, RepositorySource.Origin origin, SourceMode mode) {
        String value = mode == SourceMode.LOCAL_PATH ? rawValue.strip() : stripTokenPunctuation(rawValue);
        if (value.isBlank()) {
            return invalid("repository_source_missing", "The selected repository source is blank.");
        }
        if (!RepositorySourceText.safePromptLine(value)) {
            return malformed(mode);
        }
        if (mode == SourceMode.LOCAL_PATH) {
            return localPath(value, origin);
        }
        if (mode.allowsLocalPath() && windowsDrivePath(value)) {
            return localPath(value, origin);
        }
        if (fileUri(value)) {
            return fileUriPath(value, origin);
        }
        RepositorySourceSelection remote = remote(value, origin);
        if (remote.status() != RepositorySourceSelection.Status.NONE) {
            return remote;
        }
        if (mode == SourceMode.REMOTE_ONLY || mode == SourceMode.REMOTE_OR_FILE || explicitUriScheme(value)) {
            return invalid(
                    "repository_remote_unsupported",
                    "The selected repository remote uses an unsupported transport. Use HTTPS, SSH, SCP-style SSH, or a local checkout path.");
        }
        return localPath(rawValue.strip(), origin);
    }

    private static RepositorySourceSelection remote(String value, RepositorySource.Origin origin) {
        if (!explicitUriScheme(value)) {
            RepositorySourceSelection scp = scpRemote(value, origin);
            if (scp.status() != RepositorySourceSelection.Status.NONE) {
                return scp;
            }
        }
        try {
            URI uri = new URI(value);
            String scheme = lower(uri.getScheme());
            if ("http".equals(scheme) || "https".equals(scheme)) {
                return httpRemote(uri, origin);
            }
            if ("ssh".equals(scheme)) {
                return sshRemote(uri, origin);
            }
            return RepositorySourceSelection.none();
        } catch (URISyntaxException | IllegalArgumentException e) {
            return RepositorySourceSelection.none();
        }
    }

    private static RepositorySourceSelection httpRemote(URI uri, RepositorySource.Origin origin) {
        if (notBlank(uri.getRawUserInfo()) || hasQueryOrFragment(uri)) {
            return invalid(
                    "repository_remote_credentials_unsupported",
                    "HTTP(S) repository URLs must not include embedded credentials, query strings, or fragments. Use a credential helper or SSH.");
        }
        if (unsafeUriComponent(uri.getRawAuthority(), uri.getAuthority())
                || unsafeUriComponent(uri.getRawPath(), uri.getPath())) {
            return invalid(
                    "repository_remote_malformed",
                    "The selected repository URL contains unsupported control characters.");
        }
        return uriRemote(uri, origin, false);
    }

    private static RepositorySourceSelection sshRemote(URI uri, RepositorySource.Origin origin) {
        if (hasQueryOrFragment(uri)) {
            return invalid(
                    "repository_remote_malformed", "SSH repository URLs must not include query strings or fragments.");
        }
        String userInfo = uri.getUserInfo();
        if (notBlank(userInfo) && userInfo.contains(":")) {
            return invalid(
                    "repository_remote_credentials_unsupported",
                    "SSH repository URLs must not include password-like user information. Use a username-only SSH URL.");
        }
        if (unsafeUriComponent(uri.getRawAuthority(), uri.getAuthority())
                || unsafeUriComponent(uri.getRawUserInfo(), uri.getUserInfo())
                || unsafeUriComponent(uri.getRawPath(), uri.getPath())) {
            return invalid(
                    "repository_remote_malformed",
                    "The selected repository URL contains unsupported control characters.");
        }
        return uriRemote(uri, origin, true);
    }

    private static RepositorySourceSelection uriRemote(URI uri, RepositorySource.Origin origin, boolean includeUser) {
        String rawPath = uri.getRawPath();
        if (invalidExplicitPort(uri)) {
            return invalid(
                    "repository_remote_malformed", "The selected repository URL must use a port from 1 through 65535.");
        }
        if (blank(uri.getHost()) || blank(rawPath) || "/".equals(rawPath)) {
            return invalid(
                    "repository_remote_malformed",
                    "The selected repository URL must include a host and repository path.");
        }
        String repositoryPath = stripSlashes(rawPath);
        try {
            RepositoryIdentity identity = new RepositoryIdentity(authorityHost(uri), repositoryPath);
            return RepositorySourceSelection.selected(new RepositorySource(
                    RepositorySource.Kind.REMOTE,
                    origin,
                    normalizedUri(uri, repositoryPath, includeUser),
                    identity,
                    null));
        } catch (IllegalArgumentException e) {
            return invalid(
                    "repository_remote_malformed", "The selected repository URL must include a valid repository path.");
        }
    }

    private static boolean invalidExplicitPort(URI uri) {
        int port = uri.getPort();
        return port == URI_PORT_ABSENT ? hasExplicitAuthorityPort(uri) : port < MIN_URI_PORT || port > MAX_URI_PORT;
    }

    private static boolean hasExplicitAuthorityPort(URI uri) {
        String authority = uri.getRawAuthority();
        if (authority == null) {
            return false;
        }
        String hostAndPort = authority.substring(authority.lastIndexOf('@') + 1);
        if (hostAndPort.startsWith("[")) {
            int closingBracket = hostAndPort.indexOf(']');
            return closingBracket >= 0
                    && closingBracket + 1 < hostAndPort.length()
                    && hostAndPort.charAt(closingBracket + 1) == ':';
        }
        return hostAndPort.indexOf(':') >= 0;
    }

    private static RepositorySourceSelection scpRemote(String value, RepositorySource.Origin origin) {
        if (value.contains("://") || explicitUriScheme(value)) {
            return RepositorySourceSelection.none();
        }
        int colon = value.indexOf(':');
        if (colon <= 0 || colon == value.length() - 1) {
            return RepositorySourceSelection.none();
        }
        String hostPart = value.substring(0, colon);
        String rawPath = value.substring(colon + 1);
        if (!hostPart.contains("@")) {
            return RepositorySourceSelection.none();
        }
        if (containsWhitespace(hostPart)
                || containsWhitespace(rawPath)
                || unsafeRawComponent(hostPart)
                || unsafeRawComponent(rawPath)
                || rawPath.isBlank()) {
            return RepositorySourceSelection.none();
        }
        int at = hostPart.lastIndexOf('@');
        String user = at >= 0 ? hostPart.substring(0, at) : null;
        String host = at >= 0 ? hostPart.substring(at + 1) : hostPart;
        if ((user != null && !simpleName(user)) || !simpleHost(host)) {
            return RepositorySourceSelection.none();
        }
        String path = stripSlashes(rawPath);
        try {
            String prefix = user == null ? "" : user + "@";
            RepositoryIdentity identity = new RepositoryIdentity(host, path);
            return RepositorySourceSelection.selected(new RepositorySource(
                    RepositorySource.Kind.REMOTE, origin, prefix + identity.host() + ":" + path, identity, null));
        } catch (IllegalArgumentException e) {
            return invalid(
                    "repository_remote_malformed",
                    "The selected SCP-style repository remote must include a valid host and repository path.");
        }
    }

    private static RepositorySourceSelection fileUriPath(String value, RepositorySource.Origin origin) {
        try {
            URI uri = new URI(value);
            if (!"file".equals(lower(uri.getScheme()))
                    || hasQueryOrFragment(uri)
                    || unsafeUriComponent(uri.getRawPath(), uri.getPath())) {
                return invalid(
                        "repository_path_malformed",
                        "The selected file repository URL is malformed. Use a valid file URL or local checkout path.");
            }
            Path path = Path.of(uri).toAbsolutePath().normalize();
            return RepositorySourceSelection.selected(
                    new RepositorySource(RepositorySource.Kind.LOCAL_PATH, origin, path.toString(), null, path));
        } catch (IllegalArgumentException | URISyntaxException e) {
            return invalid(
                    "repository_path_malformed",
                    "The selected file repository URL is malformed. Use a valid file URL or local checkout path.");
        }
    }

    private static RepositorySourceSelection localPath(String value, RepositorySource.Origin origin) {
        try {
            if (!RepositorySourceText.safePromptLine(stripAngleBrackets(value))) {
                return invalid("repository_path_malformed", "The selected local repository path is malformed.");
            }
            Path path = Path.of(stripAngleBrackets(value)).toAbsolutePath().normalize();
            return RepositorySourceSelection.selected(
                    new RepositorySource(RepositorySource.Kind.LOCAL_PATH, origin, path.toString(), null, path));
        } catch (InvalidPathException e) {
            return invalid("repository_path_malformed", "The selected local repository path is malformed.");
        }
    }

    private static RepositorySourceSelection invalid(String code, String guidance) {
        return RepositorySourceSelection.invalid(new RepositorySourceProblem(code, guidance));
    }

    private static RepositorySourceSelection malformed(SourceMode mode) {
        return switch (mode) {
            case LOCAL_PATH -> invalid("repository_path_malformed", "The selected local repository path is malformed.");
            case REMOTE_ONLY, REMOTE_OR_FILE, REMOTE_FILE_OR_LOCAL ->
                invalid(
                        "repository_remote_malformed",
                        "The selected repository URL must include a valid host and repository path.");
        };
    }

    private static List<Declaration> declarations(Card card) {
        return Stream.concat(
                        Stream.of(card.title(), card.description()),
                        card.comments().stream().map(Card.Comment::text))
                .flatMap(RepositorySourceResolver::declarations)
                .toList();
    }

    private static Stream<Declaration> declarations(String text) {
        if (text == null || text.isBlank()) {
            return Stream.empty();
        }
        return LINE_BREAK
                .splitAsStream(text)
                .map(LABELED_SOURCE::matcher)
                .filter(Matcher::matches)
                .map(labeled -> new Declaration(labeled.group(2), labelMode(labeled.group(1))));
    }

    private static boolean equivalent(RepositorySourceSelection expected, RepositorySourceSelection actual) {
        if (actual.status() != RepositorySourceSelection.Status.SELECTED) {
            return false;
        }
        RepositorySource left = expected.source();
        RepositorySource right = actual.source();
        return left.kind() == right.kind()
                && left.value().equals(right.value())
                && (left.identity() == null
                        ? right.identity() == null
                        : left.identity().equals(right.identity()))
                && (left.path() == null ? right.path() == null : left.path().equals(right.path()));
    }

    private static SourceMode labelMode(String label) {
        String normalized =
                label.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
        if (normalized.endsWith(" url")) {
            return SourceMode.REMOTE_ONLY;
        }
        if (normalized.endsWith(" path") || normalized.contains("checkout")) {
            return SourceMode.LOCAL_PATH;
        }
        return SourceMode.REMOTE_FILE_OR_LOCAL;
    }

    private static String normalizedUri(URI uri, String repositoryPath, boolean includeUser) {
        String user = includeUser && notBlank(uri.getRawUserInfo()) ? uri.getRawUserInfo() + "@" : "";
        return lower(uri.getScheme()) + "://" + user + authorityHost(uri) + "/" + repositoryPath;
    }

    private static String authorityHost(URI uri) {
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        return uri.getPort() < 0 ? host : host + ":" + uri.getPort();
    }

    private static boolean explicitUriScheme(String value) {
        Matcher matcher = URI_SCHEME.matcher(value);
        return matcher.find() && matcher.group(1).length() > 1;
    }

    private static boolean windowsDrivePath(String value) {
        return WINDOWS_DRIVE_PATH.matcher(value).matches();
    }

    private static boolean fileUri(String value) {
        return value.regionMatches(true, 0, "file://", 0, "file://".length());
    }

    private static String stripTokenPunctuation(String value) {
        return TRAILING_TOKEN_PUNCTUATION.trimTrailingFrom(LEADING_TOKEN_PUNCTUATION.trimLeadingFrom(value.strip()));
    }

    private static String stripAngleBrackets(String value) {
        String stripped = value.strip();
        if (stripped.startsWith("<") && stripped.endsWith(">") && stripped.length() > 2) {
            return stripped.substring(1, stripped.length() - 1);
        }
        return stripped;
    }

    private static String stripSlashes(String value) {
        return SLASHES.trimFrom(value.strip());
    }

    private static String lower(String value) {
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }

    private static boolean simpleName(String value) {
        return !value.isBlank()
                && value.chars()
                        .allMatch(character -> Character.isLetterOrDigit(character)
                                || character == '.'
                                || character == '_'
                                || character == '-');
    }

    private static boolean simpleHost(String value) {
        return !value.isBlank()
                && Character.isLetterOrDigit(value.charAt(0))
                && value.chars()
                        .allMatch(character -> Character.isLetterOrDigit(character)
                                || character == '.'
                                || character == '_'
                                || character == '-');
    }

    private static boolean containsWhitespace(String value) {
        return value.chars().anyMatch(Character::isWhitespace);
    }

    private static boolean unsafeUriComponent(String rawValue, String decodedValue) {
        return unsafeRawComponent(rawValue) || unsafeDecodedComponent(decodedValue);
    }

    private static boolean unsafeRawComponent(String value) {
        return RepositorySourceText.unsafePromptLine(value);
    }

    private static boolean unsafeDecodedComponent(String value) {
        return RepositorySourceText.unsafePromptLine(value);
    }

    private static boolean hasQueryOrFragment(URI uri) {
        return uri.getRawQuery() != null || uri.getRawFragment() != null;
    }

    private static boolean notBlank(String value) {
        return !blank(value);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private enum SourceMode {
        REMOTE_ONLY,
        REMOTE_OR_FILE,
        REMOTE_FILE_OR_LOCAL,
        LOCAL_PATH;

        private boolean allowsLocalPath() {
            return this == REMOTE_FILE_OR_LOCAL || this == LOCAL_PATH;
        }
    }

    private record Declaration(String value, SourceMode mode) {}
}
