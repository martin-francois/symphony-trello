package ch.fmartin.symphony.trello.setup;

import static ch.fmartin.symphony.trello.setup.ConnectedBoardBuilder.connectedBoard;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

final class TelemetryInstallationsTest {
    @TempDir
    Path tempDir;

    @EnumSource(UnavailableManifest.class)
    @ParameterizedTest
    void unavailableManifestHasAnUnknownBoardCount(UnavailableManifest scenario) throws IOException {
        // given
        Path manifest = tempDir.resolve(ConnectedBoardManifest.FILE_NAME);
        if (scenario == UnavailableManifest.DIRECTORY) {
            Files.createDirectory(manifest);
        }

        // when
        OptionalInt count = TelemetryInstallations.inventory(manifest).distinctBoardCount();

        // then
        assertThat(count).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{invalid", "null", "{}", "{\"boards\":[{}]}"})
    void invalidManifestHasAnUnknownBoardCount(String content) throws IOException {
        // given
        Path manifest = writeManifest(content);

        // when
        OptionalInt count = TelemetryInstallations.inventory(manifest).distinctBoardCount();

        // then
        assertThat(count).isEmpty();
    }

    @Test
    void unreadableManifestHasAnUnknownBoardCount() throws IOException {
        // given
        assumeTrue(Files.getFileStore(tempDir).supportsFileAttributeView("posix"), "requires POSIX permissions");
        Path manifest = writeManifest("{\"boards\":[]}");
        Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(manifest);
        Files.setPosixFilePermissions(manifest, Set.of());
        try {
            assumeFalse(Files.isReadable(manifest), "the test user must not bypass the file's read permissions");

            // when
            OptionalInt count = TelemetryInstallations.inventory(manifest).distinctBoardCount();

            // then
            assertThat(count).isEmpty();
        } finally {
            Files.setPosixFilePermissions(manifest, permissions);
        }
    }

    @Test
    void readableEmptyManifestHasZeroBoards() throws IOException {
        // given
        Path manifest = writeManifest("{\"boards\":[]}");

        // when
        OptionalInt count = TelemetryInstallations.inventory(manifest).distinctBoardCount();

        // then
        assertThat(count).hasValue(0);
    }

    @Test
    void readableManifestCountsEachBoardOnce() throws IOException {
        // given
        Path manifest = tempDir.resolve(ConnectedBoardManifest.FILE_NAME);
        ConnectedBoard first = connectedBoard(tempDir.resolve("WORKFLOW.first.md"))
                .withBoardId("board-first")
                .build();
        ConnectedBoard second = connectedBoard(tempDir.resolve("WORKFLOW.second.md"))
                .withBoardId("board-second")
                .build();
        new ConnectedBoardRepository(manifest).save(new ConnectedBoardManifest(List.of(first, second, first)));

        // when
        OptionalInt count = TelemetryInstallations.inventory(manifest).distinctBoardCount();

        // then
        assertThat(count).hasValue(2);
    }

    private Path writeManifest(String content) throws IOException {
        return Files.writeString(tempDir.resolve(ConnectedBoardManifest.FILE_NAME), content);
    }

    private enum UnavailableManifest {
        MISSING,
        DIRECTORY
    }
}
