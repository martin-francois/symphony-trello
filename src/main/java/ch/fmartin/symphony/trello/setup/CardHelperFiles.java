package ch.fmartin.symphony.trello.setup;

import ch.fmartin.symphony.trello.Sha3;
import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/// The client writes requests only. Responses and helper state are separate so a client cannot
/// forge a response or replace the running helper's identity through its write grant.
record CardHelperFiles(Path root) {
    private static final String REQUEST_SUFFIX = ".request";
    private static final String RESPONSE_SUFFIX = ".response";
    private static final Set<PosixFilePermission> OWNER_DIRECTORY = PosixFilePermissions.fromString("rwx------");

    static CardHelperFiles from(LocalWorkerPaths paths) {
        String identity = paths.configDir().toString();
        if (!paths.manifestPath().equals(paths.configDir().resolve(ConnectedBoardManifest.FILE_NAME))) {
            identity += "\n" + paths.manifestPath();
        }
        String installation = Sha3.sha3_256(identity);
        return new CardHelperFiles(paths.stateHome().resolve("card-helper").resolve(installation));
    }

    Path requests() {
        return root.resolve("requests");
    }

    Path responses() {
        return root.resolve("responses");
    }

    Path privateState() {
        return root.resolve("private");
    }

    Path ready() {
        return root.resolve("ready.json");
    }

    Path processLock() {
        return privateState().resolve("helper.lock");
    }

    void removeStoppedReadiness(CardHelperProtocol.Ready stopped) throws IOException {
        // The helper holds this lock before publishing readiness. A restart must retain its record.
        try (var channel = FileChannel.open(processLock(), StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                var lock = channel.tryLock()) {
            if (lock != null
                    && !Files.notExists(ready(), LinkOption.NOFOLLOW_LINKS)
                    && stopped.equals(CardHelperProtocol.json()
                            .readValue(
                                    read(ready(), CardHelperProtocol.MAX_REQUEST_BYTES),
                                    CardHelperProtocol.Ready.class))) {
                Files.delete(ready());
            }
        } catch (NoSuchFileException | OverlappingFileLockException ignored) {
            // Graceful shutdown already removed readiness, or another helper owns the lock.
            return;
        }
    }

    Path request(UUID id) {
        return requests().resolve(id + REQUEST_SUFFIX);
    }

    Path response(UUID id) {
        return responses().resolve(id + RESPONSE_SUFFIX);
    }

    Path claimed(UUID id) {
        return privateState().resolve(id + REQUEST_SUFFIX);
    }

    static UUID requestId(Path file) {
        String name = PathNames.fileName(file);
        if (!name.endsWith(REQUEST_SUFFIX)) {
            throw new IllegalArgumentException("cannot read helper request name: expected a UUID request filename");
        }
        String value = name.substring(0, name.length() - REQUEST_SUFFIX.length());
        UUID id = UUID.fromString(value);
        if (!id.toString().equals(value)) {
            throw new IllegalArgumentException("cannot read helper request name: expected a canonical UUID");
        }
        return id;
    }

    void initialize(Path trustedConfig) throws IOException {
        UserPrincipal owner = Files.getOwner(trustedConfig);
        Path helperHome = PathNames.requireParent(root);
        Path stateHome = PathNames.requireParent(helperHome);
        Files.createDirectories(stateHome);
        if (!Files.isDirectory(stateHome, LinkOption.NOFOLLOW_LINKS)
                || !Files.getOwner(stateHome, LinkOption.NOFOLLOW_LINKS).equals(owner)) {
            throw new IOException(
                    "cannot use card helper state: expected an unlinked directory owned by the config owner");
        }
        if (Files.getFileStore(stateHome).supportsFileAttributeView("posix")) {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(stateHome, LinkOption.NOFOLLOW_LINKS);
            if (permissions.contains(PosixFilePermission.GROUP_WRITE)
                    || permissions.contains(PosixFilePermission.OTHERS_WRITE)) {
                throw new IOException("cannot use card helper state: remove write permissions for other users");
            }
        }
        List<Path> directories = List.of(helperHome, root, requests(), responses(), privateState());
        if (Files.getFileStore(stateHome).supportsFileAttributeView("posix")) {
            for (Path directory : directories) {
                createPrivatePosixDirectory(directory, owner);
            }
        } else if (Files.getFileStore(stateHome).supportsFileAttributeView("acl")) {
            List<Path> missing = new ArrayList<>();
            for (Path directory : directories) {
                if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
                    requirePrivateDirectory(directory, owner);
                } else {
                    missing.add(directory);
                }
            }
            WindowsPrivateDirectories.create(missing, owner);
            for (Path directory : directories) {
                requirePrivateDirectory(directory, owner);
            }
        } else {
            throw new IOException(
                    "cannot create card helper directory: owner-only filesystem permissions are required");
        }
    }

    private static void createPrivatePosixDirectory(Path path, UserPrincipal owner) throws IOException {
        try {
            Files.createDirectory(path, PosixFilePermissions.asFileAttribute(OWNER_DIRECTORY));
        } catch (FileAlreadyExistsException ignored) {
            // Existing state must meet the same ownership and permission requirements.
        }
        requirePrivateDirectory(path, owner);
    }

    private static void requirePrivateDirectory(Path path, UserPrincipal owner) throws IOException {
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                || !Files.getOwner(path, LinkOption.NOFOLLOW_LINKS).equals(owner)) {
            throw new IOException(
                    "cannot use card helper directory: expected an unlinked directory owned by the config owner");
        }
        if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
            if (!OWNER_DIRECTORY.containsAll(Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS))) {
                throw new IOException("cannot use card helper directory: remove permissions for other users");
            }
            return;
        }
        AclFileAttributeView view =
                Files.getFileAttributeView(path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (view == null) {
            throw new IOException("cannot use card helper directory: owner-only filesystem permissions are required");
        }
        for (AclEntry entry : view.getAcl()) {
            if (entry.type() == AclEntryType.ALLOW && !entry.principal().equals(owner)) {
                throw new IOException("cannot use card helper directory: remove access grants for other users");
            }
        }
    }

    static byte[] read(Path path, int limit) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("cannot read card helper message: expected a regular file without links");
        }
        try (var channel = Files.newByteChannel(path, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
                var input = Channels.newInputStream(channel)) {
            byte[] bytes = input.readNBytes(limit + 1);
            if (bytes.length > limit) {
                throw new IOException("cannot read card helper message: message exceeds the size limit");
            }
            return bytes;
        }
    }

    static void publish(Path stagingDirectory, Path destination, byte[] bytes, int limit) throws IOException {
        if (bytes.length > limit) {
            throw new IOException("cannot publish card helper message: message exceeds the size limit");
        }
        Path temporary = Files.createTempFile(stagingDirectory, ".message-", ".tmp");
        try {
            Files.write(temporary, bytes, StandardOpenOption.TRUNCATE_EXISTING);
            AtomicFiles.replace(temporary, destination);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
