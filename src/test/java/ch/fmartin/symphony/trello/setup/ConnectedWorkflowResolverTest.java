package ch.fmartin.symphony.trello.setup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import ch.fmartin.symphony.trello.config.ConfigResolver;
import ch.fmartin.symphony.trello.workflow.WorkflowDefinition;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class ConnectedWorkflowResolverTest {
    @TempDir
    Path temporary;

    @ParameterizedTest
    @ValueSource(strings = {ConnectedBoardManifest.FILE_NAME, "custom-boards.json"})
    void runtimeRequiresRegistrationAndProtectsInstallationSources(String manifestName) throws Exception {
        // given
        Path configDir = Files.createDirectory(temporary.resolve("config"));
        Path source = temporary.resolve("source.md");
        Path managed = configDir.resolve("WORKFLOW.queue.md");
        Path dotenv = temporary.resolve("credentials.env");
        var board = new ConnectedBoard(
                "board-1",
                "board-1",
                "Queue",
                "https://trello.com/b/board-1",
                managed,
                dotenv,
                temporary.resolve("workspaces"),
                8080,
                false,
                List.of(),
                false,
                source);
        Path manifest = configDir.resolve(manifestName);
        var repository = new ConnectedBoardRepository(manifest);
        repository.save(new ConnectedBoardManifest(List.of(board)));
        var resolver = new ConnectedWorkflowResolver(Map.of(
                "SYMPHONY_TRELLO_CONFIG_DIR", configDir.toString(), "SYMPHONY_TRELLO_MANIFEST", manifest.toString()));
        var definition = new WorkflowDefinition(
                managed,
                Map.of(
                        "tracker",
                        Map.of(
                                "kind",
                                "trello",
                                "board_id",
                                "board-1",
                                "api_key",
                                "synthetic-key",
                                "api_token",
                                "synthetic-token")),
                "");
        var configResolver = new ConfigResolver(name -> Optional.empty());

        // when
        var config = resolver.resolveRuntime(definition, configResolver);
        repository.save(new ConnectedBoardManifest(List.of()));

        // then
        assertThat(config.credentialSources().files())
                .contains(configDir, managed, source, dotenv, temporary.resolve("state"));
        assertThatThrownBy(() -> resolver.resolveRuntime(definition, configResolver))
                .isInstanceOf(TrelloBoardSetupException.class)
                .hasMessageContaining("start --workflow PATH");
    }

    @Test
    void sourceAliasSelectsManagedMetadataEvenWhenNeitherFileExists() {
        // given
        Path managed = temporary.resolve("config/WORKFLOW.queue.md");
        Path source = temporary.resolve("source/WORKFLOW.md");
        var board = new ConnectedBoard(
                "board-1",
                "board-1",
                "Queue",
                "https://trello.com/b/board-1",
                managed,
                temporary.resolve("missing.env"),
                temporary.resolve("workspaces"),
                8080,
                false,
                List.of(),
                false,
                source);
        var manifest = new ConnectedBoardManifest(List.of(board));

        // when
        ConnectedBoard result =
                ConnectedWorkflowResolver.select(manifest, Optional.empty(), Optional.of(source), "stop");

        // then
        assertThat(result).isEqualTo(board);
        assertThat(result.workflowPath()).isEqualTo(managed).doesNotExist();
    }

    @Test
    void unregisteredSourceGetsAConnectionInstructionWithoutReadingItsContents() throws Exception {
        // given
        Path source = temporary.resolve("untrusted.md");
        Files.writeString(source, "private invalid YAML");

        // when
        Throwable thrown = catchThrowable(() -> ConnectedWorkflowResolver.select(
                new ConnectedBoardManifest(List.of()), Optional.empty(), Optional.of(source), "card list"));

        // then
        assertThat(thrown)
                .isInstanceOf(TrelloBoardSetupException.class)
                .hasMessageContaining("start --workflow PATH", "trusted terminal")
                .hasMessageNotContaining("private");
    }

    @Test
    void managedSnapshotResolvesRelativePathsAtItsSourceDirectory() throws Exception {
        // given
        Path source = Files.createDirectory(temporary.resolve("source"));
        Files.writeString(source.resolve("key"), "synthetic-key");
        Path managed = temporary.resolve("config/WORKFLOW.queue.md");
        var workflow = new WorkflowDefinition(
                managed,
                Map.of(
                        "tracker",
                        Map.of(
                                "kind",
                                "trello",
                                "board_id",
                                "board-1",
                                "api_key",
                                "file:key",
                                "api_token",
                                "synthetic-token"),
                        "workspace",
                        Map.of("root", "workspaces"),
                        "repository",
                        Map.of("default_path", "repository"),
                        "codex",
                        Map.of("additional_writable_roots", List.of("extra"))),
                "Queue");

        // when
        var config = new ConfigResolver(name -> Optional.empty()).resolve(workflow, source);

        // then
        assertThat(config.workflowPath()).isEqualTo(managed);
        assertThat(config.tracker().apiKey()).isEqualTo("synthetic-key");
        assertThat(config.workspace().root()).isEqualTo(source.resolve("workspaces"));
        assertThat(config.repository().defaultPath()).isEqualTo(source.resolve("repository"));
        assertThat(config.codex().additionalWritableRoots()).containsExactly(source.resolve("extra"));
    }
}
