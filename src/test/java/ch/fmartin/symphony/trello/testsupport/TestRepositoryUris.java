package ch.fmartin.symphony.trello.testsupport;

import java.net.URI;
import java.util.regex.Pattern;

public final class TestRepositoryUris {
    private static final int URI_PORT_ABSENT = -1;
    private static final int MIN_URI_PORT = 1;
    private static final int MAX_URI_PORT = 65_535;
    private static final Pattern EXPLICIT_AUTHORITY_PORT = Pattern.compile("^(?:\\[[^]]+]|[^:]+):.*$");

    private TestRepositoryUris() {}

    public static boolean hasUnusableExplicitPort(URI uri) {
        int port = uri.getPort();
        return port == URI_PORT_ABSENT ? hasExplicitAuthorityPort(uri) : port < MIN_URI_PORT || port > MAX_URI_PORT;
    }

    private static boolean hasExplicitAuthorityPort(URI uri) {
        String authority = uri.getRawAuthority();
        if (authority == null) {
            return false;
        }
        String hostAndPort = authority.substring(authority.lastIndexOf('@') + 1);
        return EXPLICIT_AUTHORITY_PORT.matcher(hostAndPort).matches();
    }
}
