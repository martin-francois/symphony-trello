package ch.fmartin.symphony.trello.setup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import ch.fmartin.symphony.trello.telemetry.InstalledVersion;
import ch.fmartin.symphony.trello.telemetry.TelemetryEnvironment;
import ch.fmartin.symphony.trello.telemetry.TelemetryStateStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

final class ManagedWorkflowImportTest {
    private static final TrelloBoardSetup.BoardInfo BOARD =
            new TrelloBoardSetup.BoardInfo("board-1", "SYNTH001", "Queue", "https://trello.com/b/SYNTH001");
    private static final String WORKFLOW =
            """
            ---
            tracker:
              kind: trello
              board_id: board-1
              api_key: synthetic-key
              api_token: synthetic-token
            workspace:
              root: workspaces
            ---
            Initial instructions
            """;

    @TempDir
    Path temporary;

    @Test
    void firstImportCountsTheNewlyRegisteredBoard() throws Exception {
        // given
        Fixture fixture = installedFixture(Map.of());

        // when
        fixture.importer().connect(fixture.paths(), fixture.source(), Optional.empty(), ignored -> {});

        // then
        assertThat(new TelemetryStateStore(fixture.paths().stateHome()).read())
                .isInstanceOfSatisfying(
                        TelemetryStateStore.StateRead.Valid.class,
                        state -> assertThat(state.state().boardImportsTotal()).isOne());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void reimportCountsOnlyANewBoardIdentity(boolean retarget) throws Exception {
        // given
        Fixture fixture = installedFixture(Map.of());
        fixture.importer().connect(fixture.paths(), fixture.source(), Optional.empty(), ignored -> {});
        if (retarget) {
            Files.writeString(fixture.source(), WORKFLOW.replace("board-1", "board-2"));
        }

        // when
        ConnectedBoard connected =
                fixture.importer().connect(fixture.paths(), fixture.source(), Optional.empty(), ignored -> {});
        fixture.importer().connect(fixture.paths(), connected.workflowPath(), Optional.empty(), ignored -> {});

        // then
        assertThat(new TelemetryStateStore(fixture.paths().stateHome()).read())
                .isInstanceOfSatisfying(
                        TelemetryStateStore.StateRead.Valid.class,
                        state -> assertThat(state.state().boardImportsTotal()).isEqualTo(retarget ? 2 : 1));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void disabledReportingDoesNotCountImports(boolean explicitDotenv) throws Exception {
        // given
        Fixture fixture =
                installedFixture(explicitDotenv ? Map.of() : Map.of(TelemetryEnvironment.DISABLED_VARIABLE, "1"));
        Path dotenv = temporary.resolve("selected.env");
        Files.writeString(dotenv, TelemetryEnvironment.DISABLED_VARIABLE + "=1\n");

        // when
        fixture.importer()
                .connect(
                        fixture.paths(),
                        fixture.source(),
                        explicitDotenv ? Optional.of(dotenv) : Optional.empty(),
                        ignored -> {});

        // then
        assertThat(fixture.repository().loadForLifecycle().boards()).hasSize(1);
        assertThat(new TelemetryStateStore(fixture.paths().stateHome()).read())
                .isInstanceOf(TelemetryStateStore.StateRead.Absent.class);
    }

    @Test
    void failedPublicationDoesNotCountAnImport() throws Exception {
        // given
        Fixture fixture = installedFixture(Map.of());
        Files.createDirectories(fixture.paths().configDir());
        Files.writeString(
                GeneratedWorkflowStore.besideManifest(fixture.paths().manifestPath())
                        .storePath(),
                "invalid JSON");

        // when
        Throwable failure = catchThrowable(
                () -> fixture.importer().connect(fixture.paths(), fixture.source(), Optional.empty(), ignored -> {}));

        // then
        assertThat(failure).isInstanceOf(IOException.class);
        assertThat(fixture.repository().loadForLifecycle().boards()).isEmpty();
        assertThat(new TelemetryStateStore(fixture.paths().stateHome()).read())
                .isInstanceOf(TelemetryStateStore.StateRead.Absent.class);
    }

    @Test
    void unstableManagedPortLeavesThePreviousConnectionUntouched() throws Exception {
        // given
        Fixture fixture = fixture();
        ConnectedBoard first =
                fixture.importer().connect(fixture.paths(), fixture.source(), Optional.empty(), ignored -> {});
        Files.writeString(fixture.source(), WORKFLOW.replace("---\nInitial", "server:\n  port: 0\n---\nInitial"));
        List<ConnectedBoard> stopped = new ArrayList<>();

        // when
        Throwable failure = catchThrowable(
                () -> fixture.importer().connect(fixture.paths(), fixture.source(), Optional.empty(), stopped::add));

        // then
        assertThat(failure).isInstanceOf(TrelloBoardSetupException.class).hasMessageContaining("stable HTTP port");
        assertThat(stopped).isEmpty();
        assertThat(first.workflowPath()).hasContent(WORKFLOW);
        assertThat(fixture.repository().loadForLifecycle().boards()).containsExactly(first);
    }

    @CsvSource({
        "SYMPHONY_HTTP_PORT, 0",
        "QUARKUS_HTTP_PORT, 0",
        "SYMPHONY_HTTP_PORT, $MISSING_PORT",
        "QUARKUS_HTTP_PORT, $MISSING_PORT"
    })
    @ParameterizedTest
    void importedConnectionUsesTheEffectiveStablePortOverride(String variable, String workflowPort) throws Exception {
        // given
        Fixture fixture = fixture(Map.of(variable, "19090"));
        Files.writeString(
                fixture.source(),
                WORKFLOW.replace("---\nInitial", "server:\n  port: " + workflowPort + "\n---\nInitial"));

        // when
        ConnectedBoard connected =
                fixture.importer().connect(fixture.paths(), fixture.source(), Optional.empty(), ignored -> {});

        // then
        assertThat(connected.serverPort()).isEqualTo(19090);
        assertThat(fixture.repository().loadForLifecycle().boards()).containsExactly(connected);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void reimportKeepsLogsOnlyForTheSameBoardIdentity(boolean retarget) throws Exception {
        // given
        Fixture fixture = fixture();
        ConnectedBoard first =
                fixture.importer().connect(fixture.paths(), fixture.source(), Optional.empty(), ignored -> {});
        var files = new ManagedProcessStore(fixture.paths().stateHome()).files(first.workflowPath());
        Files.createDirectories(fixture.paths().stateHome());
        Files.writeString(files.stdoutLog(), "previous board output");
        Files.writeString(files.stderrLog(), "previous board errors");
        if (retarget) {
            Files.writeString(fixture.source(), WORKFLOW.replace("board-1", "board-2"));
        }
        List<ConnectedBoard> stopped = new ArrayList<>();

        // when
        ConnectedBoard connected =
                fixture.importer().connect(fixture.paths(), fixture.source(), Optional.empty(), stopped::add);

        // then
        assertThat(stopped).containsExactly(first);
        assertThat(connected.workflowPath()).isEqualTo(first.workflowPath());
        if (retarget) {
            assertThat(files.stdoutLog()).doesNotExist();
            assertThat(files.stderrLog()).doesNotExist();
            assertThat(files.stdoutLog().resolveSibling(files.stdoutLog().getFileName() + ".previous"))
                    .hasContent("previous board output");
            assertThat(files.stderrLog().resolveSibling(files.stderrLog().getFileName() + ".previous"))
                    .hasContent("previous board errors");
        } else {
            assertThat(files.stdoutLog()).hasContent("previous board output");
            assertThat(files.stderrLog()).hasContent("previous board errors");
        }
        assertThat(fixture.repository().loadForLifecycle().boards()).containsExactly(connected);
    }

    @Test
    void retargetingAnExternalRegisteredSourceReplacesItsOldConnection() throws Exception {
        // given
        Fixture fixture = fixture();
        ConnectedBoard previous = ConnectedBoardBuilder.connectedBoard(fixture.source())
                .withBoardId("old-board")
                .build();
        fixture.repository().save(new ConnectedBoardManifest(List.of(previous)));
        List<ConnectedBoard> stopped = new ArrayList<>();

        // when
        ConnectedBoard connected =
                fixture.importer().connect(fixture.paths(), fixture.source(), Optional.empty(), stopped::add);

        // then
        assertThat(stopped).containsExactly(previous);
        assertThat(fixture.repository().load().boards()).containsExactly(connected);
        assertThat(fixture.repository().load().selectWorkflow(fixture.source())).hasValue(connected);
    }

    @Test
    void firstImportCreatesConfigStorageWithAnExternalManifest() throws Exception {
        // given
        Fixture fixture = fixture();
        var paths = fixture.paths().withManifestPath(temporary.resolve("registry/boards.json"));

        // when
        ConnectedBoard connected = fixture.importer().connect(paths, fixture.source(), Optional.empty(), ignored -> {});

        // then
        assertThat(connected.workflowPath()).hasContent(WORKFLOW);
        assertThat(new ConnectedBoardRepository(paths.manifestPath()).load().boards())
                .containsExactly(connected);
    }

    @Test
    void boardNameMatchingAnotherBoardIdDoesNotReplaceThatConnection() throws Exception {
        // given
        Fixture fixture = fixture();
        Path original = Files.createDirectories(fixture.paths().configDir()).resolve("original.md");
        Files.writeString(original, "Original board workflow");
        ConnectedBoard other = ConnectedBoardBuilder.connectedBoard(original)
                .withBoardId("other-board")
                .withBoardName(BOARD.boardId())
                .build();
        fixture.repository().save(new ConnectedBoardManifest(List.of(other)));
        List<ConnectedBoard> stopped = new ArrayList<>();

        // when
        ConnectedBoard connected =
                fixture.importer().connect(fixture.paths(), fixture.source(), Optional.empty(), stopped::add);

        // then
        assertThat(stopped).isEmpty();
        assertThat(original).hasContent("Original board workflow");
        assertThat(fixture.repository().load().boards()).containsExactly(other, connected);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void registrationPreservesImportedCodexAccessForLaterSetupEdits(boolean unrestricted) throws Exception {
        // given
        Fixture fixture = fixture();
        String access =
                unrestricted ? "  thread_sandbox: danger-full-access\n" : "  additional_writable_roots: [../extra]\n";
        Files.writeString(
                fixture.source(),
                WORKFLOW.replace("---\nInitial instructions", "codex:\n" + access + "---\nInitial instructions"));

        // when
        ConnectedBoard connected =
                fixture.importer().connect(fixture.paths(), fixture.source(), Optional.empty(), ignored -> {});

        // then
        assertThat(connected.dangerFullAccess())
                .as("connection metadata preserves explicit unrestricted mode")
                .isEqualTo(unrestricted);
        assertThat(connected.additionalWritableRoots())
                .containsExactlyElementsOf(unrestricted ? List.of() : List.of(temporary.resolve("extra")));
    }

    @Test
    void conflictingSourceRetargetLeavesBothConnectionsAndWorkersUntouched() throws Exception {
        // given
        Fixture fixture = fixture();
        var importer = new ManagedWorkflowImport(new WorkflowConfigEditor(), Map.of(), config -> {
            String id = config.tracker().boardId();
            return new TrelloBoardSetup.BoardInfo(id, id, id, "https://trello.com/b/" + id);
        });
        importer.connect(fixture.paths(), fixture.source(), Optional.empty(), ignored -> {});
        Path other = fixture.source().resolveSibling("other.md");
        Files.writeString(other, WORKFLOW.replace("board-1", "board-2"));
        importer.connect(fixture.paths(), other, Optional.empty(), ignored -> {});
        ConnectedBoardManifest before = fixture.repository().load();
        Files.writeString(fixture.source(), WORKFLOW.replace("board-1", "board-2"));
        List<ConnectedBoard> stopped = new ArrayList<>();

        // when
        Throwable failure = catchThrowable(
                () -> importer.connect(fixture.paths(), fixture.source(), Optional.empty(), stopped::add));

        // then
        assertThat(failure).isInstanceOf(TrelloBoardSetupException.class).hasMessageContaining("disconnect", "before");
        assertThat(stopped).isEmpty();
        assertThat(fixture.repository().load()).isEqualTo(before);
    }

    @Test
    void managedCopyKeepsGeneratedBodyProvenanceForLaterMigration() throws Exception {
        // given
        Fixture fixture = fixture();
        var inputs =
                new GeneratedWorkflowBodyInputs(List.of("Ready"), List.of("Done"), null, "Review", null, null, false);
        var store = GeneratedWorkflowStore.besideManifest(fixture.paths().manifestPath());
        var original = new GeneratedWorkflowRecord(fixture.source(), "1.0.0", inputs, "Initial instructions");
        store.put(original);

        // when
        ConnectedBoard connected =
                fixture.importer().connect(fixture.paths(), fixture.source(), Optional.empty(), ignored -> {});

        // then
        assertThat(GeneratedWorkflowStore.find(store.records(), connected.workflowPath()))
                .hasValue(
                        new GeneratedWorkflowRecord(connected.workflowPath(), "1.0.0", inputs, "Initial instructions"));
        assertThat(GeneratedWorkflowStore.find(store.records(), fixture.source()))
                .hasValue(original);
    }

    @Test
    void stopsTrackedUnregisteredSourceBeforePublishingItsManagedCopy() throws Exception {
        // given
        Fixture fixture = fixture();
        Files.createDirectories(fixture.paths().stateHome());
        Path pid = new ManagedProcessStore(fixture.paths().stateHome())
                .files(fixture.source())
                .pidFile();
        Files.writeString(pid, "12345");
        List<ConnectedBoard> stopped = new ArrayList<>();

        // when
        ConnectedBoard connected = fixture.importer()
                .connect(fixture.paths(), fixture.source(), Optional.empty(), board -> {
                    assertThat(fixture.paths().manifestPath()).doesNotExist();
                    stopped.add(board);
                });

        // then
        assertThat(stopped)
                .singleElement()
                .extracting(ConnectedBoard::workflowPath)
                .isEqualTo(fixture.source());
        assertThat(connected.workflowPath()).isNotEqualTo(fixture.source()).hasContent(WORKFLOW);
    }

    @Test
    void connectsOneManagedSnapshotAndImportsSourceEditsOnlyWhenRequested() throws Exception {
        // given
        Fixture fixture = fixture();
        List<ConnectedBoard> stopped = new ArrayList<>();
        ConnectedBoard first =
                fixture.importer().connect(fixture.paths(), fixture.source(), Optional.empty(), stopped::add);
        String edited = WORKFLOW.replace("Initial instructions", "Updated instructions");
        Files.writeString(fixture.source(), edited);
        assertThat(first.workflowPath()).hasContent(WORKFLOW);
        assertThat(fixture.repository().loadForLifecycle().selectWorkflow(fixture.source()))
                .hasValue(first);

        // when
        ConnectedBoard second =
                fixture.importer().connect(fixture.paths(), fixture.source(), Optional.empty(), stopped::add);

        // then
        assertThat(second.workflowPath()).isEqualTo(first.workflowPath()).hasContent(edited);
        assertThat(second.sourceWorkflowPath()).isEqualTo(fixture.source());
        assertThat(second.workspaceRoot())
                .isEqualTo(fixture.source().getParent().resolve("workspaces"));
        assertThat(fixture.repository().loadForLifecycle().boards()).containsExactly(second);
        assertThat(stopped).containsExactly(first);
    }

    @Test
    void failedStopPreservesThePreviousSnapshotAndRegistration() throws Exception {
        // given
        Fixture fixture = fixture();
        List<ConnectedBoard> stopped = new ArrayList<>();
        ConnectedBoard first =
                fixture.importer().connect(fixture.paths(), fixture.source(), Optional.empty(), stopped::add);
        Files.writeString(fixture.source(), WORKFLOW.replace("Initial instructions", "Updated instructions"));

        // when
        Throwable thrown = catchThrowable(
                () -> fixture.importer().connect(fixture.paths(), fixture.source(), Optional.empty(), board -> {
                    throw new IOException("synthetic stop failure");
                }));

        // then
        assertThat(thrown).isInstanceOf(IOException.class).hasMessage("synthetic stop failure");
        assertThat(stopped).isEmpty();
        assertThat(first.workflowPath()).hasContent(WORKFLOW);
        assertThat(fixture.repository().loadForLifecycle().boards()).containsExactly(first);
    }

    @Test
    void startingTheManagedPathDoesNotReimportTheSource() throws Exception {
        // given
        Fixture fixture = fixture();
        List<ConnectedBoard> stopped = new ArrayList<>();
        ConnectedBoard first =
                fixture.importer().connect(fixture.paths(), fixture.source(), Optional.empty(), stopped::add);
        Files.delete(fixture.source());

        // when
        ConnectedBoard selected =
                fixture.importer().connect(fixture.paths(), first.workflowPath(), Optional.empty(), stopped::add);

        // then
        assertThat(selected).isEqualTo(first);
        assertThat(selected.workflowPath()).hasContent(WORKFLOW);
        assertThat(stopped).isEmpty();
    }

    private Fixture installedFixture(Map<String, String> environment) throws IOException {
        Fixture fixture = fixture(environment);
        Files.createDirectories(fixture.paths().stateHome());
        Files.writeString(
                InstalledVersion.installContextPath(fixture.paths().stateHome()),
                "installer=install.sh\napp_version=2.0.0\n");
        return fixture;
    }

    private Fixture fixture() throws IOException {
        return fixture(Map.of());
    }

    private Fixture fixture(Map<String, String> environment) throws IOException {
        Path source = Files.createDirectory(temporary.resolve("source")).resolve("WORKFLOW.md");
        Files.writeString(source, WORKFLOW);
        var paths = new LocalWorkerPaths(
                temporary, temporary.resolve("config"), temporary.resolve("workspaces"), temporary.resolve("state"));
        return new Fixture(
                source,
                paths,
                new ManagedWorkflowImport(
                        new WorkflowConfigEditor(),
                        environment,
                        config -> boardInfo(config.tracker().boardId())),
                new ConnectedBoardRepository(paths.manifestPath()));
    }

    private static TrelloBoardSetup.BoardInfo boardInfo(String boardId) {
        return new TrelloBoardSetup.BoardInfo(boardId, BOARD.boardKey(), BOARD.boardName(), BOARD.boardUrl());
    }

    private record Fixture(
            Path source, LocalWorkerPaths paths, ManagedWorkflowImport importer, ConnectedBoardRepository repository) {}
}
