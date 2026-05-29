package ch.fmartin.symphony.trello.setup;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DiagnosticsTokenHasherTest {
    @TempDir
    Path tempDir;

    @Test
    void createsAndReusesLocalDiagnosticsKey() throws Exception {
        // given
        Path configDir = tempDir.resolve("config");

        // when
        DiagnosticsTokenHasher first = DiagnosticsTokenHasher.load(configDir);
        String firstToken = first.token("private-board-id");
        DiagnosticsTokenHasher second = DiagnosticsTokenHasher.load(configDir);
        String secondToken = second.token("private-board-id");

        // then
        assertThat(first.persisted()).isTrue();
        assertThat(second.persisted()).isTrue();
        assertThat(firstToken).isEqualTo(secondToken).hasSize(12);
        assertThat(configDir.resolve(DiagnosticsTokenHasher.KEY_FILE_NAME))
                .exists()
                .content(StandardCharsets.UTF_8)
                .matches("[0-9a-f]{64}\\R?");
    }

    @Test
    void differentLocalKeysProduceDifferentTokens() throws Exception {
        // given
        Path firstConfig = tempDir.resolve("first-config");
        Path secondConfig = tempDir.resolve("second-config");

        // when
        String firstToken = DiagnosticsTokenHasher.load(firstConfig).token("private-board-id");
        String secondToken = DiagnosticsTokenHasher.load(secondConfig).token("private-board-id");

        // then
        assertThat(firstToken).isNotEqualTo(secondToken);
    }

    @Test
    void createsLocalDiagnosticsKeyWithOwnerOnlyPermissionsWhenSupported() throws Exception {
        // given
        Path configDir = tempDir.resolve("config");

        // when
        DiagnosticsTokenHasher.load(configDir);

        // then
        Path key = configDir.resolve(DiagnosticsTokenHasher.KEY_FILE_NAME);
        if (key.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            assertThat(Files.getPosixFilePermissions(key))
                    .containsExactlyInAnyOrder(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
        }
    }

    @Test
    void usesEphemeralKeyWhenLocalKeyIsInvalid() throws Exception {
        // given
        Path configDir = tempDir.resolve("config");
        Files.createDirectories(configDir);
        Path key = configDir.resolve(DiagnosticsTokenHasher.KEY_FILE_NAME);
        Files.writeString(key, "not-a-valid-key", StandardCharsets.UTF_8);

        // when
        DiagnosticsTokenHasher hasher = DiagnosticsTokenHasher.load(configDir);
        String token = hasher.token("private-board-id");

        // then
        assertThat(hasher.persisted()).isFalse();
        assertThat(token).hasSize(12);
        assertThat(key).content(StandardCharsets.UTF_8).isEqualTo("not-a-valid-key");
    }
}
