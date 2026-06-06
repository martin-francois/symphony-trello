package ch.fmartin.symphony.trello.setup;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
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
        Files.writeString(workflow, "---\n---\n", StandardCharsets.UTF_8);
        String expectedHash = sha3_256(workflow.toRealPath().toString()).substring(0, 12);

        // when
        ManagedProcessStore.ManagedProcessFiles files = new ManagedProcessStore(stateHome).files(workflow);

        // then
        assertThat(files.pidFile()).hasFileName("WORKFLOW.md." + expectedHash + ".pid");
        assertThat(files.stdoutLog()).hasFileName("WORKFLOW.md." + expectedHash + ".log");
        assertThat(files.stderrLog()).hasFileName("WORKFLOW.md." + expectedHash + ".err");
        assertThat(files.processLockFile()).hasFileName("WORKFLOW.md." + expectedHash + ".lock");
    }

    private static String sha3_256(String value) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA3-256");
        return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
