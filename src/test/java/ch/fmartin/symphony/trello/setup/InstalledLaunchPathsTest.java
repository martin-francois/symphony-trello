package ch.fmartin.symphony.trello.setup;

import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import ch.fmartin.symphony.trello.config.ConfigResolver;
import ch.fmartin.symphony.trello.process.PlatformEnvironment;
import ch.fmartin.symphony.trello.workflow.WorkflowLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

final class InstalledLaunchPathsTest {
    @TempDir
    Path temporary;

    @EnumSource(WrapperSource.class)
    @ParameterizedTest
    void bothProfilesProtectTheInstalledWrapperDirectory(WrapperSource source) throws Exception {
        // given
        Path bin = Files.createDirectory(temporary.resolve("bin"));
        Fixture fixture = fixture(bin, source);

        // when
        ProfileProtection protection = resolveProfiles(fixture);

        // then
        assertBothProfilesProtect(protection, bin);
    }

    @Test
    void bothProfilesProtectDirectoryAndWrapperFileAliases() throws Exception {
        // given
        assumeFalse(PlatformEnvironment.current().windows());
        Path realBin = Files.createDirectory(temporary.resolve("real-bin"));
        Path bin = Files.createSymbolicLink(temporary.resolve("linked-bin"), realBin);
        Path wrapperTarget = Files.writeString(temporary.resolve("wrapper-target"), "synthetic wrapper");
        Files.createSymbolicLink(realBin.resolve("symphony-trello"), wrapperTarget);
        Fixture fixture = fixture(bin, WrapperSource.COMMAND);

        // when
        ProfileProtection protection = resolveProfiles(fixture);

        // then
        assertBothProfilesProtect(protection, bin, realBin, wrapperTarget);
    }

    private static ProfileProtection resolveProfiles(Fixture fixture) throws Exception {
        var runtime = new ConnectedWorkflowResolver(fixture.environment())
                .resolveRuntime(
                        new WorkflowLoader().load(fixture.workflow()), new ConfigResolver(ignored -> Optional.empty()));
        String profile = CardHelperProfile.render(fixture.paths(), fixture.environment());
        return new ProfileProtection(runtime.protectedLaunchPaths(), profile);
    }

    private static void assertBothProfilesProtect(ProfileProtection protection, Path... expected) throws Exception {
        var rules = new ArrayList<String>();
        for (Path path : expected) {
            rules.add(CardHelperProtocol.json().writeValueAsString(path.toString()) + " = \"read\"");
        }
        assertSoftly(softly -> {
            softly.assertThat(protection.paths()).contains(expected);
            softly.assertThat(protection.profile()).contains(rules.toArray(String[]::new));
        });
    }

    private Fixture fixture(Path bin, WrapperSource source) throws Exception {
        Path config = Files.createDirectory(temporary.resolve("config"));
        Path app = Files.createDirectory(temporary.resolve("app"));
        Path state = Files.createDirectory(temporary.resolve("state"));
        var paths = new LocalWorkerPaths(app, config, temporary.resolve("workspaces"), state);
        Path workflow = Files.writeString(
                config.resolve("WORKFLOW.md"),
                """
                ---
                tracker:
                  kind: trello
                  board_id: board-1
                  api_key: synthetic-key
                  api_token: synthetic-token
                ---
                Queue
                """);
        new ConnectedBoardRepository(paths.manifestPath())
                .save(new ConnectedBoardManifest(
                        List.of(ConnectedBoardBuilder.connectedBoard(workflow).build())));
        Map<String, String> environment = new HashMap<>(Map.of(
                "SYMPHONY_TRELLO_APP_HOME", app.toString(),
                "SYMPHONY_TRELLO_CONFIG_DIR", config.toString(),
                "SYMPHONY_TRELLO_STATE_HOME", state.toString()));
        switch (source) {
            case COMMAND ->
                environment.put(
                        "SYMPHONY_TRELLO_COMMAND",
                        bin.resolve("symphony-trello").toString());
            case WRAPPER ->
                environment.put(
                        "SYMPHONY_TRELLO_WRAPPER_COMMAND",
                        bin.resolve("symphony-trello.cmd").toString());
            case CONTEXT ->
                Files.writeString(
                        state.resolve("install-context.properties"), "app_dir=" + app + "\nbin_dir=" + bin + "\n");
        }
        return new Fixture(paths, workflow, Map.copyOf(environment));
    }

    private record ProfileProtection(Set<Path> paths, String profile) {}

    private record Fixture(LocalWorkerPaths paths, Path workflow, Map<String, String> environment) {}

    enum WrapperSource {
        COMMAND,
        WRAPPER,
        CONTEXT
    }
}
