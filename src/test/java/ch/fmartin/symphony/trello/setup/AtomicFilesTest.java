package ch.fmartin.symphony.trello.setup;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class AtomicFilesTest {
    @TempDir
    Path directory;

    @Test
    void temporaryFileSharesTheTargetFolderAndReplacementPreservesItsBytes() throws IOException {
        // given
        Path target = Files.writeString(directory.resolve("workflow.md"), "original");
        Path temporary = AtomicFiles.siblingTemporaryFile(target);
        Files.writeString(temporary, "replacement");

        // when
        AtomicFiles.replace(temporary, target);

        // then
        assertThat(temporary).hasParentRaw(target.getParent());
        assertThat(target).hasContent("replacement");
        assertThat(temporary).doesNotExist();
    }

    @Test
    void cleanupRemovesOnlyTheTemporaryFileAndAcceptsAnAbsentFile() throws IOException {
        // given
        Path target = Files.writeString(directory.resolve("workflow.md"), "original");
        Path temporary = AtomicFiles.siblingTemporaryFile(target);

        // when
        boolean deleted = AtomicFiles.deleteQuietly(temporary);
        boolean alreadyAbsent = AtomicFiles.deleteQuietly(temporary);

        // then
        assertThat(deleted).as("the temporary file existed and was deleted").isTrue();
        assertThat(alreadyAbsent).as("the second cleanup has no file to delete").isFalse();
        assertThat(AtomicFiles.deleteQuietly(null))
                .as("no temporary file was allocated")
                .isFalse();
        assertThat(target).hasContent("original");
    }

    @Test
    void cleanupFailureLeavesTheOriginalOperationResultAvailable() throws IOException {
        // given
        Path nonemptyDirectory = Files.createDirectory(directory.resolve("nonempty"));
        Path child = Files.writeString(nonemptyDirectory.resolve("keep.txt"), "keep");

        // when
        boolean deleted = AtomicFiles.deleteQuietly(nonemptyDirectory);

        // then
        assertThat(deleted)
                .as("a nonempty directory cannot be deleted as a temporary file")
                .isFalse();
        assertThat(child).hasContent("keep");
    }
}
