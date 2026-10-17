package ch.fmartin.symphony.trello.setup;

import ch.fmartin.symphony.trello.workflow.WorkflowException;
import ch.fmartin.symphony.trello.workflow.WorkflowLoader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/// Replaces one workflow file with a migrated body. It keeps a discoverable backup next to the
/// workflow, writes a same-directory temporary copy that keeps the original file attributes, checks
/// that copy before an atomic move, checks the result again, and restores the backup when the final
/// check fails.
@NullMarked
final class WorkflowBodyWriter {
    static final String BACKUP_INFIX = ".backup-";

    private static final DateTimeFormatter BACKUP_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private final Clock clock;
    private final FileReplacement replacement;
    private final WorkflowCheck workflowCheck;

    WorkflowBodyWriter(Clock clock) {
        this(clock, AtomicFiles::replace, WorkflowBodyWriter::loadWithRuntimeLoader);
    }

    WorkflowBodyWriter(Clock clock, FileReplacement replacement, WorkflowCheck workflowCheck) {
        this.clock = clock;
        this.replacement = replacement;
        this.workflowCheck = workflowCheck;
    }

    WriteResult write(Path workflowPath, WorkflowBodyReplacement migration) {
        Path target = workflowPath.toAbsolutePath().normalize();
        String expectedContent = migration.original().content();
        String newContent = migration.proposedContent();
        Path backup;
        try {
            requireUnchanged(target, expectedContent);
            backup = createBackup(target);
        } catch (IOException e) {
            return WriteResult.failed("could not create a backup (" + reason(e) + ")", Optional.empty(), false);
        }
        @Nullable Path temporary = null;
        try {
            temporary = AtomicFiles.siblingTemporaryFile(target);
            // Copying first keeps the workflow's permissions and other copyable attributes.
            Files.copy(target, temporary, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            Files.writeString(temporary, newContent);
            check(temporary, newContent, migration.original().metadata());
            requireUnchanged(target, expectedContent);
            replacement.replace(temporary, target);
        } catch (IOException | RuntimeException e) {
            AtomicFiles.deleteQuietly(temporary);
            return WriteResult.failed("the workflow was not changed (" + reason(e) + ")", Optional.of(backup), false);
        }
        try {
            check(target, newContent, migration.original().metadata());
            return WriteResult.written(backup);
        } catch (IOException | RuntimeException e) {
            boolean restored = restore(backup, target);
            return WriteResult.failed(
                    "the migrated workflow did not pass validation (" + reason(e) + ")", Optional.of(backup), restored);
        }
    }

    private void check(Path workflow, String expectedContent, String expectedMetadata) throws IOException {
        String written = Files.readString(workflow);
        if (!written.equals(expectedContent)) {
            throw new IOException("written text differs from the planned text");
        }
        boolean metadataKept = WorkflowFileText.parse(written)
                .map(text -> text.metadata().equals(expectedMetadata))
                .orElse(false);
        if (!metadataKept) {
            throw new IOException("workflow metadata changed");
        }
        workflowCheck.check(workflow);
    }

    private Path createBackup(Path target) throws IOException {
        String baseName = PathNames.fileName(target) + BACKUP_INFIX + BACKUP_TIMESTAMP.format(clock.instant());
        Path backup = target.resolveSibling(baseName);
        // Several migrations in the same second keep every backup with a numeric suffix.
        for (int attempt = 2; Files.exists(backup, LinkOption.NOFOLLOW_LINKS); attempt++) {
            backup = target.resolveSibling(baseName + "-" + attempt);
        }
        Files.copy(target, backup, StandardCopyOption.COPY_ATTRIBUTES, LinkOption.NOFOLLOW_LINKS);
        return backup;
    }

    private boolean restore(Path backup, Path target) {
        @Nullable Path temporary = null;
        try {
            temporary = AtomicFiles.siblingTemporaryFile(target);
            Files.copy(backup, temporary, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            AtomicFiles.replace(temporary, target);
            return Files.readString(target).equals(Files.readString(backup));
        } catch (IOException | RuntimeException e) {
            AtomicFiles.deleteQuietly(temporary);
            return false;
        }
    }

    private static void requireUnchanged(Path target, String expectedContent) throws IOException {
        if (Files.isSymbolicLink(target) || !Files.readString(target).equals(expectedContent)) {
            throw new IOException("the workflow changed after it was checked");
        }
    }

    private static void loadWithRuntimeLoader(Path workflow) throws IOException {
        try {
            new WorkflowLoader().load(workflow);
        } catch (WorkflowException e) {
            throw new IOException("the runtime cannot load it: " + e.getMessage(), e);
        }
    }

    private static String reason(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    @FunctionalInterface
    interface FileReplacement {
        void replace(Path source, Path target) throws IOException;
    }

    @FunctionalInterface
    interface WorkflowCheck {
        void check(Path workflow) throws IOException;
    }

    record WriteResult(boolean written, String failure, Optional<Path> backup, boolean restored) {
        static WriteResult written(Path backup) {
            return new WriteResult(true, "", Optional.of(backup), false);
        }

        static WriteResult failed(String failure, Optional<Path> backup, boolean restored) {
            return new WriteResult(false, failure, backup, restored);
        }
    }
}
