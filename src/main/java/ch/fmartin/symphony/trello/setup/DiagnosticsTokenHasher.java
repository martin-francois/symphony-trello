package ch.fmartin.symphony.trello.setup;

import static java.util.Objects.requireNonNullElseGet;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

final class DiagnosticsTokenHasher {
    static final String KEY_FILE_NAME = ".symphony-trello-diagnostics-key";

    private static final String ALGORITHM = "HmacSHA3-256";
    private static final int KEY_BYTES = 32;
    private static final int DISPLAY_BYTES = 6;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec key;
    private final boolean persisted;

    private DiagnosticsTokenHasher(byte[] key, boolean persisted) {
        this.key = new SecretKeySpec(key.clone(), ALGORITHM);
        this.persisted = persisted;
    }

    static DiagnosticsTokenHasher load(Path configDir) {
        Path keyPath = configDir.resolve(KEY_FILE_NAME);
        return readKey(keyPath).orElseGet(() -> createKey(keyPath).orElseGet(DiagnosticsTokenHasher::ephemeral));
    }

    static DiagnosticsTokenHasher ephemeral() {
        return temporary(randomKey());
    }

    boolean persisted() {
        return persisted;
    }

    String token(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            byte[] bytes = mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes, 0, DISPLAY_BYTES);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(ALGORITHM + " is unavailable", e);
        } catch (InvalidKeyException e) {
            throw new IllegalStateException("Diagnostics token key is invalid", e);
        }
    }

    private static Optional<DiagnosticsTokenHasher> readKey(Path keyPath) {
        if (!Files.isRegularFile(keyPath)) {
            return Optional.empty();
        }
        try {
            return parseKey(Files.readString(keyPath, StandardCharsets.UTF_8)).map(DiagnosticsTokenHasher::persisted);
        } catch (IOException ignored) {
            return Optional.empty();
        }
    }

    private static Optional<DiagnosticsTokenHasher> createKey(Path keyPath) {
        byte[] key = randomKey();
        try {
            Path parent = keyPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            createKeyFile(keyPath, key);
            return Optional.of(persisted(key));
        } catch (FileAlreadyExistsException ignored) {
            return readKey(keyPath);
        } catch (IOException ignored) {
            return Optional.of(temporary(key));
        }
    }

    private static void createKeyFile(Path keyPath, byte[] key) throws IOException {
        if (supportsPosixFilePermissions(keyPath)) {
            Files.createFile(keyPath, PosixFilePermissions.asFileAttribute(ownerOnlyKeyPermissions()));
            Files.writeString(keyPath, HexFormat.of().formatHex(key) + "\n", StandardCharsets.UTF_8);
        } else {
            Files.writeString(
                    keyPath,
                    HexFormat.of().formatHex(key) + "\n",
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE);
        }
    }

    private static boolean supportsPosixFilePermissions(Path path) throws IOException {
        Path existing = requireNonNullElseGet(
                path.getParent(), () -> path.toAbsolutePath().normalize());
        return Files.getFileStore(existing).supportsFileAttributeView("posix");
    }

    private static Set<PosixFilePermission> ownerOnlyKeyPermissions() {
        return Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
    }

    private static Optional<byte[]> parseKey(String value) {
        String hex = value.strip();
        if (hex.length() != KEY_BYTES * 2) {
            return Optional.empty();
        }
        try {
            return Optional.of(HexFormat.of().parseHex(hex));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    private static byte[] randomKey() {
        byte[] key = new byte[KEY_BYTES];
        RANDOM.nextBytes(key);
        return key;
    }

    private static DiagnosticsTokenHasher persisted(byte[] key) {
        return new DiagnosticsTokenHasher(key, true);
    }

    private static DiagnosticsTokenHasher temporary(byte[] key) {
        return new DiagnosticsTokenHasher(key, false);
    }
}
