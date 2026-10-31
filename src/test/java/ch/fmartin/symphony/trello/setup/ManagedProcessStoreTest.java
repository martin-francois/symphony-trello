package ch.fmartin.symphony.trello.setup;

import static org.assertj.core.api.Assertions.assertThat;

import ch.fmartin.symphony.trello.Sha3;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
        assertThat(files.workflowPathFile()).hasFileName("WORKFLOW.md." + expectedHash + ".workflow");
    }

    @ParameterizedTest
    @ValueSource(strings = {"WORKFLOW.md", "WORKFLOW.md "})
    void recordsTheWorkflowPathOfAStartAttemptAsGiven(String filename) throws Exception {
        // given
        Path stateHome = tempDir.resolve("state");
        Files.createDirectories(stateHome);
        Path realDir = tempDir.resolve("real");
        Files.createDirectories(realDir);
        Files.createSymbolicLink(tempDir.resolve("link"), realDir);
        Path workflow = tempDir.resolve("link").resolve(filename);
        Files.writeString(workflow, "---\n---\n");
        var store = new ManagedProcessStore(stateHome);

        // when
        store.recordWorkflowPath(workflow);

        // then
        assertThat(store.recordedWorkflowPaths())
                .as("the record keeps the symbolic link the start was given, like the tokens it printed")
                .containsExactly(workflow.toAbsolutePath().normalize());
        assertThat(store.files(workflow).workflowPathFile()).isRegularFile();
    }

    @Test
    void deletesTheRecordOfAStartAttempt() throws Exception {
        // given
        Path stateHome = tempDir.resolve("state");
        Files.createDirectories(stateHome);
        Path workflow = tempDir.resolve("WORKFLOW.md");
        Files.writeString(workflow, "---\n---\n");
        var store = new ManagedProcessStore(stateHome);
        store.recordWorkflowPath(workflow);

        // when
        boolean deleted = store.deleteWorkflowPathRecord(store.files(workflow));

        // then
        assertThat(deleted).as("the record existed and is gone").isTrue();
        assertThat(store.recordedWorkflowPaths()).isEmpty();
    }

    @Test
    void listsNoRecordedWorkflowsWithoutAStateHome() throws Exception {
        // given
        var store = new ManagedProcessStore(tempDir.resolve("missing-state"));

        // when
        var recorded = store.recordedWorkflowPaths();

        // then
        assertThat(recorded).isEmpty();
    }

    @Test
    void skipsAnEmptyRecord() throws Exception {
        // given
        Path stateHome = tempDir.resolve("state");
        Files.createDirectories(stateHome);
        Files.writeString(stateHome.resolve("WORKFLOW.md.000000000000.workflow"), " \n");

        // when
        var recorded = new ManagedProcessStore(stateHome).recordedWorkflowPaths();

        // then
        assertThat(recorded).isEmpty();
    }
}
