package ch.fmartin.symphony.trello.setup;

import java.nio.file.Path;

final class PrivateContextTokens {
    private PrivateContextTokens() {}

    static String pathToken(Path configDir, Path path) {
        return pathToken(configDir, path.toString());
    }

    static String pathToken(Path configDir, String path) {
        return "<path:" + tokenHasher(configDir).token(path) + ">";
    }

    static String lookupHint(String token) {
        return " Resolve with " + lookupCommand(token) + ".";
    }

    static String lookupCommand(String token) {
        return "symphony-trello diagnostics --show-private-context --lookup " + shellQuote(token);
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private static DiagnosticsTokenHasher tokenHasher(Path configDir) {
        return DiagnosticsTokenHasher.load(configDir);
    }
}
