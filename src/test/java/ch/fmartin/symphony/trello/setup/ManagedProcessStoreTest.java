package ch.fmartin.symphony.trello.setup;

import static org.assertj.core.api.Assertions.assertThat;

import ch.fmartin.symphony.trello.Sha3;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ManagedProcessStoreTest {
    @TempDir
    Path tempDir;

    @Test
    void namesManagedProcessFilesWithSha3WorkflowPathHash() throws Exception {
        // given
        Path stateHome = tempDir.resolve("state");
        Path workflow = tempDir.resolve("WORKFLOW.md");
        Files.writeString(workflow, "---\n---\n");
        String expectedHash = Sha3.sha3_256(workflow.toRealPath().toString()).substring(0, 12);

        // when
        ManagedProcessStore.ManagedProcessFiles files = new ManagedProcessStore(stateHome).files(workflow);

        // then
        assertThat(files.pidFile()).hasFileName("WORKFLOW.md." + expectedHash + ".pid");
        assertThat(files.stdoutLog()).hasFileName("WORKFLOW.md." + expectedHash + ".log");
        assertThat(files.stderrLog()).hasFileName("WORKFLOW.md." + expectedHash + ".err");
        assertThat(files.processLockFile()).hasFileName("WORKFLOW.md." + expectedHash + ".lock");
    }
}
