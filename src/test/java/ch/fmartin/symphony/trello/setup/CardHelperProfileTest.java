package ch.fmartin.symphony.trello.setup;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class CardHelperProfileTest {
    @TempDir
    Path temporary;

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
        String profile = CardHelperProfile.render(paths, Map.of());

        // then
        assertThat(profile)
                .contains(
                        "default_permissions = ",
                        "extends = \":workspace\"",
                        json.writeValueAsString(config.toString()) + " = \"deny\"",
                        json.writeValueAsString(secret.toString()) + " = \"deny\"",
                        json.writeValueAsString(env.toString()) + " = \"deny\"",
                        json.writeValueAsString(source.toString()) + " = \"deny\"",
                        json.writeValueAsString(
                                        CardHelperFiles.from(paths).requests().toString()) + " = \"write\"")
                .doesNotContain("synthetic-key", "synthetic-token", json.writeValueAsString(workflow.toString()));
        assertThat(profile).containsOnlyOnce(" = \"write\"");
    }
}
