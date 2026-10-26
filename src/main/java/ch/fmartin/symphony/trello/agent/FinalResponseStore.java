package ch.fmartin.symphony.trello.agent;

import ch.fmartin.symphony.trello.Sha3;
import ch.fmartin.symphony.trello.config.EffectiveConfig;
import ch.fmartin.symphony.trello.config.PullRequestMode;
import ch.fmartin.symphony.trello.prompt.PullRequestHandoff;
import ch.fmartin.symphony.trello.time.ApplicationClock;
import ch.fmartin.symphony.trello.tracker.TrackerEndpoint;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.jboss.logging.Logger;
import org.jspecify.annotations.NullMarked;

/// Keeps the current private final response outside task workspaces, which terminal cleanup removes.
/// The existing workflow runtime lock owns the namespace across processes; synchronized operations
/// serialize its cards within this runner and prevent an older attempt from replacing a newer one.
@NullMarked
final class FinalResponseStore {
    // WorkspaceManager.sanitize always replaces +, so no task key can own or clean this directory.
    static final String DIRECTORY = ".symphony+final-responses";
    static final int MAX_RESPONSE_BYTES = 1024 * 1024;
    static final int MAX_FINISHED_RECORDS = 128;
    private static final int MAX_METADATA_BYTES = 64 * 1024;
    static final int MAX_RECORD_BYTES = MAX_RESPONSE_BYTES * 6 + MAX_METADATA_BYTES;
    private static final int MAX_ID_BYTES = 1024;
    private static final int STICKY_BIT = 512;
    private static final Set<String> STATUSES = Set.of("running", "completed", "failed");
    private static final Logger LOG = Logger.getLogger(FinalResponseStore.class);
    private final ObjectMapper json = new ObjectMapper();
    private final Clock clock;
    private final Map<Path, String> active = new HashMap<>();

    FinalResponseStore() {
        this(ApplicationClock.systemUtc());
    }

    FinalResponseStore(Clock clock) {
        this.clock = clock;
    }

    synchronized Optional<FinalResponseRecording> begin(AgentRunner.AgentRunRequest request) {
        Path path = path(request);
        Path namespace = requiredParent(path);
        try {
            if (PullRequestHandoff.resolve(request.config().github(), request.card())
                                    .mode()
                            != PullRequestMode.BRANCH_ONLY
                    || TrelloHandoffToolHandler.evidenceWritesEnabled(request.config())) {
                if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                    prepareDirectories(request.config().workspace().root(), namespace);
                    readRecord(path);
                    Files.delete(path);
                }
                active.remove(path);
                return Optional.empty();
            }
            prepareDirectories(request.config().workspace().root(), namespace);
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                readRecord(path);
            }
            ObjectNode record = metadata(request);
            trimFinished(namespace, MAX_FINISHED_RECORDS);
            replace(path, record);
            active.put(path, request.workerIdentity());
            return Optional.of(new FinalResponseRecording(this, path, request, record));
        } catch (IOException e) {
            throw failure("prepare", request.workerIdentity(), e);
        }
    }

    private Path path(AgentRunner.AgentRunRequest request) {
        EffectiveConfig config = request.config();
        String namespace = String.join(
                "\n",
                config.workflowPath().toAbsolutePath().normalize().toString(),
                TrackerEndpoint.fingerprint(config.tracker().endpoint()),
                config.tracker().resolvedBoardId());
        return config.workspace()
                .root()
                .toAbsolutePath()
                .normalize()
                .resolve(DIRECTORY)
                .resolve(Sha3.sha3_256(namespace))
                .resolve(Sha3.sha3_256(request.card().id()) + ".json");
    }

    private ObjectNode metadata(AgentRunner.AgentRunRequest request) throws IOException {
        ObjectNode record = json.createObjectNode();
        record.put("status", "running");
        record.put("card_id", checkedIdentity(request.card().id()));
        record.put("card_identifier", checkedIdentity(request.card().identifier()));
        record.put("worker_identity", checkedIdentity(request.workerIdentity()));
        record.put("updated_at", clock.instant().toString());
        return record;
    }

    synchronized void complete(
            Path path, String worker, ObjectNode record, String thread, String turn, String response) {
        if (!worker.equals(active.get(path))) {
            throw failure(
                    "save", worker, new IOException("cannot save local final response: the current attempt changed"));
        }
        try {
            record.put("thread_id", checkedIdentity(thread));
            record.put("turn_id", checkedIdentity(turn));
            if (response.getBytes(StandardCharsets.UTF_8).length > MAX_RESPONSE_BYTES) {
                throw new IOException(
                        "cannot save local final response: the completed response exceeds the size limit");
            }
            record.put("response", response);
            record.put("status", "completed");
            record.put("updated_at", clock.instant().toString());
            trimFinished(requiredParent(path), MAX_FINISHED_RECORDS - 1);
            replace(path, record);
            active.remove(path, worker);
            // This is a private local path, like the worker's existing workflow path log. Never log
            // the response or protocol payload; operators read the private JSON file directly.
            LOG.infof("final_response outcome=completed path=%s", path);
        } catch (IOException e) {
            throw failure("save", worker, e);
        }
    }

    synchronized void abandon(Path path, String worker, ObjectNode record) {
        if (!worker.equals(active.get(path))) {
            return;
        }
        try {
            record.remove("response");
            record.put("status", "failed");
            record.put("updated_at", clock.instant().toString());
            trimFinished(requiredParent(path), MAX_FINISHED_RECORDS - 1);
            replace(path, record);
        } catch (IOException e) {
            // The accepted start already removed the old response. Keep that incomplete record and
            // the original run failure when marking it failed also encounters a storage error.
            LOG.warn("cannot mark local final response failed: check private output storage permissions and capacity");
        } finally {
            active.remove(path, worker);
        }
    }

    private void trimFinished(Path namespace, int retain) throws IOException {
        var candidates = new ArrayList<StoredRecord>();
        try (var files = Files.newDirectoryStream(namespace, "*.json")) {
            for (Path file : files) {
                JsonNode record = readRecord(file);
                if (!active.containsKey(file)) {
                    candidates.add(new StoredRecord(
                            file, Instant.parse(record.path("updated_at").textValue())));
                }
            }
        }
        candidates.sort(Comparator.comparing(StoredRecord::updatedAt)
                .thenComparing(record -> record.path().toString()));
        int remove = Math.max(0, candidates.size() - retain);
        for (int index = 0; index < remove; index++) {
            Files.delete(candidates.get(index).path());
        }
    }

    private JsonNode readRecord(Path path) throws IOException {
        requireRegularFile(path);
        if (Files.size(path) > MAX_RECORD_BYTES) {
            throw new IOException("cannot read local final response: an existing record exceeds the size limit");
        }
        byte[] bytes;
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            bytes = input.readNBytes(MAX_RECORD_BYTES + 1);
        }
        if (bytes.length > MAX_RECORD_BYTES) {
            throw new IOException("cannot read local final response: an existing record exceeds the size limit");
        }
        JsonNode record = json.readTree(bytes);
        if (record == null
                || !record.isObject()
                || !STATUSES.contains(record.path("status").asText())) {
            throw new IOException("cannot read local final response: expected valid status metadata");
        }
        for (String field : Set.of("card_id", "card_identifier", "worker_identity", "updated_at")) {
            if (!record.path(field).isTextual()) {
                throw new IOException("cannot read local final response: expected valid identity metadata");
            }
            checkedIdentity(record.path(field).textValue());
        }
        // A verified regular record is not a filesystem root and therefore has a filename.
        if (!Objects.requireNonNull(path.getFileName(), "cannot read local final response: expected a record filename")
                .toString()
                .equals(Sha3.sha3_256(record.path("card_id").textValue()) + ".json")) {
            throw new IOException(
                    "cannot read local final response: the record filename does not match its card identity");
        }
        try {
            Instant.parse(record.path("updated_at").textValue());
        } catch (DateTimeParseException e) {
            throw new IOException("cannot read local final response: expected a valid timestamp", e);
        }
        if ("completed".equals(record.path("status").textValue())) {
            for (String field : Set.of("thread_id", "turn_id")) {
                if (!record.path(field).isTextual()) {
                    throw new IOException(
                            "cannot read local final response: expected completed thread and turn identities");
                }
                checkedIdentity(record.path(field).textValue());
            }
            if (!record.path("response").isTextual()
                    || record.path("response").textValue().isBlank()
                    || record.path("response").textValue().getBytes(StandardCharsets.UTF_8).length
                            > MAX_RESPONSE_BYTES) {
                throw new IOException("cannot read local final response: expected response text within the size limit");
            }
        } else if (record.has("response")) {
            throw new IOException(
                    "cannot read local final response: an incomplete record must not contain response text");
        }
        return record;
    }

    private static String checkedIdentity(String value) throws IOException {
        if (value.isBlank() || value.getBytes(StandardCharsets.UTF_8).length > MAX_ID_BYTES) {
            throw new IOException(
                    "cannot save local final response: expected nonempty identity metadata within the size limit");
        }
        return value;
    }

    private static void prepareDirectories(Path workspaceRoot, Path namespace) throws IOException {
        Path root = workspaceRoot.toAbsolutePath().normalize();
        Path current = Objects.requireNonNull(
                root.getRoot(), "cannot prepare local final response: expected an absolute workspace root");
        validateDirectory(current);
        for (Path component : namespace) {
            Path parent = current;
            current = current.resolve(component);
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.getFileStore(parent).supportsFileAttributeView("posix")) {
                    Files.createDirectory(
                            current,
                            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
                } else {
                    Files.createDirectory(current);
                }
            }
            validateDirectory(current);
            if (current.startsWith(root.resolve(DIRECTORY))
                    && Files.getFileStore(current).supportsFileAttributeView("posix")) {
                Files.setPosixFilePermissions(current, PosixFilePermissions.fromString("rwx------"));
            }
        }
    }

    private static void validateDirectory(Path directory) throws IOException {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(directory)) {
            throw new IOException("cannot prepare local final response: expected directories without symbolic links");
        }
        if (!Files.getFileStore(directory).supportsFileAttributeView("posix")) {
            return;
        }
        var attributes = Files.readAttributes(directory, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        String processUser = ProcessHandle.current()
                .info()
                .user()
                .orElseThrow(() -> new IOException(
                        "cannot prepare local final response: expected the process owner for POSIX ancestry checks"));
        var processOwner =
                directory.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName(processUser);
        boolean processOwned = attributes.owner().equals(processOwner);
        boolean sharedWritable = attributes.permissions().contains(PosixFilePermission.GROUP_WRITE)
                || attributes.permissions().contains(PosixFilePermission.OTHERS_WRITE);
        if (processOwned && !sharedWritable) {
            return;
        }
        // The public named attribute API exposes UID and sticky mode on Unix providers. Without
        // these attributes, accept neither an unknown owner nor an unverifiable shared directory.
        Map<String, Object> unix =
                directory.getFileSystem().supportedFileAttributeViews().contains("unix")
                        ? unixAttributes(directory)
                        : Map.of();
        if (!processOwned && !Integer.valueOf(0).equals(unix.get("uid"))) {
            throw new IOException(
                    "cannot prepare local final response: POSIX ancestors must be owned by the process user or system root");
        }
        // A trusted sticky parent, such as /tmp, prevents other users from replacing our children.
        // Every child is checked in turn, so an existing child owned by another user is rejected.
        if (sharedWritable && (!(unix.get("mode") instanceof Integer mode) || (mode & STICKY_BIT) == 0)) {
            throw new IOException(
                    "cannot prepare local final response: writable POSIX ancestors require sticky protection");
        }
    }

    private static Map<String, Object> unixAttributes(Path directory) throws IOException {
        try {
            return Files.readAttributes(directory, "unix:uid,mode", LinkOption.NOFOLLOW_LINKS);
        } catch (UnsupportedOperationException | IllegalArgumentException e) {
            throw new IOException(
                    "cannot prepare local final response: expected Unix ownership and mode metadata for POSIX ancestry checks",
                    e);
        }
    }

    private static void requireRegularFile(Path path) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
            throw new IOException("cannot access local final response: expected a regular file without symbolic links");
        }
    }

    private void replace(Path target, ObjectNode record) throws IOException {
        Path namespace = requiredParent(target);
        prepareDirectories(requiredParent(requiredParent(namespace)), namespace);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            requireRegularFile(target);
        }
        Path temporary = Files.getFileStore(namespace).supportsFileAttributeView("posix")
                ? Files.createTempFile(
                        namespace,
                        ".response-",
                        ".tmp",
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
                : Files.createTempFile(namespace, ".response-", ".tmp");
        try {
            Files.write(temporary, json.writerWithDefaultPrettyPrinter().writeValueAsBytes(record));
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static Path requiredParent(Path path) {
        // Records have three components below the absolute workspace root: store, namespace,
        // and filename. Their ancestors through the workspace root therefore always exist as paths.
        return Objects.requireNonNull(
                path.getParent(), "cannot access local final response: expected the internal record path parent");
    }

    static void reportFailure(String worker, String message) {
        // The worker may already be inactive, so its exit callback does not necessarily log failures.
        // Correlate the attempt without exposing response text, raw identities or exception details.
        LOG.error(message + " attempt=" + Sha3.sha3_256(worker));
    }

    private static IllegalStateException failure(String operation, String worker, IOException cause) {
        String message =
                "cannot " + operation + " local final response: check private output storage permissions and capacity";
        reportFailure(worker, message);
        return new IllegalStateException(message, cause);
    }

    private record StoredRecord(Path path, Instant updatedAt) {}
}
