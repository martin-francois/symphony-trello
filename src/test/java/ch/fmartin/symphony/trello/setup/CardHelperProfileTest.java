package ch.fmartin.symphony.trello.setup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class CardHelperProfileTest {
    @TempDir
    Path temporary;

    @Test
    void rejectsRequestStorageInsideReadOnlyLaunchPaths() throws Exception {
        // given
        Path config = Files.createDirectory(temporary.resolve("config"));
        var paths = new LocalWorkerPaths(
                temporary.resolve("app"), config, temporary.resolve("workspaces"), temporary.resolve("app/state"));

        // when
        Throwable failure = catchThrowable(() -> CardHelperProfile.render(paths, paths.workspaceRoot(), Map.of()));

        // then
        assertThat(failure)
                .isInstanceOf(IOException.class)
                .hasMessageContaining("outside protected configuration, credential and launch paths");
    }

    @ParameterizedTest
    @ValueSource(strings = {".", "state-card-requests", "state-card-requests/agent"})
    void rejectsWorkspaceThatCanWriteAnotherManifestsQueue(String relativeWorkspace) throws Exception {
        // given
        Path config = Files.createDirectory(temporary.resolve("config"));
        var paths = new LocalWorkerPaths(
                temporary.resolve("app"), config, temporary.resolve("workspaces"), temporary.resolve("state"));
        Path workspace = temporary.resolve(relativeWorkspace);

        // when
        Throwable failure = catchThrowable(() -> CardHelperProfile.render(paths, workspace, Map.of()));

        // then
        assertThat(failure)
                .isInstanceOf(IOException.class)
                .hasMessageContaining("outside shared card request storage and its ancestors");
    }

    @EnabledOnOs({OS.LINUX, OS.MAC})
    @Test
    void rejectsFutureRequestStorageBelowAnAliasedWorkspace() throws Exception {
        // given
        Path config = Files.createDirectory(temporary.resolve("config"));
        Path workspace = Files.createDirectory(temporary.resolve("workspace"));
        Path alias = Files.createSymbolicLink(temporary.resolve("alias"), workspace);
        var paths =
                new LocalWorkerPaths(temporary.resolve("app"), config, workspace, alias.resolve("future/nested/state"));

        // when
        Throwable failure = catchThrowable(() -> CardHelperProfile.render(paths, workspace, Map.of()));

        // then
        assertThat(failure)
                .isInstanceOf(IOException.class)
                .hasMessageContaining("outside shared card request storage and its ancestors");
    }

    @Test
    void protectsEveryConnectedCredentialSourceAndGrantsOnlyTheRequestDirectoryWrites() throws Exception {
        // given
        Path config = Files.createDirectory(temporary.resolve("config"));
        Path source = Files.writeString(temporary.resolve("original.md"), "synthetic-token");
        Path secret = Files.writeString(temporary.resolve("key"), "synthetic-key");
        Path workflow = Files.writeString(
                config.resolve("WORKFLOW.md"),
                """
                ---
                tracker:
                  kind: trello
                  board_id: board-1
                  api_key: file:%s
                  api_token: synthetic-token
                ---
                Queue
                """
                        .formatted(secret));
        Path env = temporary.resolve("custom.env");
        var paths = new LocalWorkerPaths(
                temporary.resolve("app"), config, temporary.resolve("workspaces"), temporary.resolve("state"));
        var board = new ConnectedBoard(
                "board-1",
                "board-1",
                "Queue",
                "https://trello.com/b/board-1",
                workflow,
                env,
                paths.workspaceRoot(),
                8080,
                false,
                List.of(),
                false,
                source);
        new ConnectedBoardRepository(paths.manifestPath()).save(new ConnectedBoardManifest(List.of(board)));
        var json = CardHelperProtocol.json();

        // when
        String profile = CardHelperProfile.render(paths, paths.workspaceRoot(), Map.of());

        // then
        assertThat(profile)
                .contains(
                        "default_permissions = ",
                        "extends = \":read-only\"",
                        json.writeValueAsString(paths.workspaceRoot().toString()) + " = \"write\"",
                        json.writeValueAsString(config.toString()) + " = \"deny\"",
                        json.writeValueAsString(secret.toString()) + " = \"deny\"",
                        json.writeValueAsString(env.toString()) + " = \"deny\"",
                        json.writeValueAsString(source.toString()) + " = \"deny\"",
                        json.writeValueAsString(
                                        CardHelperFiles.from(paths).requests().toString()) + " = \"write\"")
                .doesNotContain("synthetic-key", "synthetic-token", json.writeValueAsString(workflow.toString()));

        assertThat(profile)
                .contains(json.writeValueAsString(
                                paths.workspaceRoot().resolve(".codex").toString()) + " = \"read\"")
                .doesNotContain("\":workspace_roots\"", "\":tmpdir\"", "\":slash_tmp\"");
        CardHelperFiles files = CardHelperFiles.from(paths);
        assertThat(files.requests().startsWith(paths.stateHome()))
                .as("request writes do not reopen a descendant of protected state")
                .isFalse();
        assertThat(profile)
                .contains(
                        json.writeValueAsString(files.root().toString()) + " = \"read\"",
                        json.writeValueAsString(files.responses().toString()) + " = \"read\"");
    }
}
