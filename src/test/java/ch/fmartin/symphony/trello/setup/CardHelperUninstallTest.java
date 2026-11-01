package ch.fmartin.symphony.trello.setup;

import static ch.fmartin.symphony.trello.setup.InstallerScriptFixture.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

final class CardHelperUninstallTest {
    @TempDir
    Path temporary;

    @CsvSource({
        "false,--remove-state,true,state", "false,--remove-state,false,state",
        "false,--remove-all-local-data,true,state", "false,--remove-all-local-data,false,state",
        "true,--remove-state,true,state", "true,--remove-state,false,state",
        "true,--remove-all-local-data,true,state", "true,--remove-all-local-data,false,state",
        "false,--remove-state,true,state/.", "false,--remove-state,false,state/.",
        "true,--remove-state,true,state/.", "true,--remove-state,false,state/."
    })
    @ParameterizedTest
    void localDataCleanupRemovesQueuedAndStagedRequests(
            boolean powershell, String scope, boolean stateExists, String stateSpelling) throws Exception {
        // given
        Fixture fixture = fixture(powershell, stateExists, stateSpelling);
        Map<String, String> environment = fixture.environment();
        List<String> arguments = new ArrayList<>(fixture.arguments());
        run(environment, arguments.toArray(String[]::new)).assertSuccess();
        assertThat(fixture.storage()).isDirectory();
        arguments.addAll(List.of("--yes-local-data", scope));

        // when
        var result = run(environment, arguments.toArray(String[]::new));

        // then
        result.assertSuccess();
        assertThat(fixture.state()).doesNotExist();
        assertThat(fixture.storage()).doesNotExist();
    }

    @CsvSource({
        "false,CONFIG_DIR,equal",
        "false,CONFIG_DIR,nested",
        "false,CONFIG_DIR,ancestor",
        "false,WORKSPACE_ROOT,equal",
        "false,WORKSPACE_ROOT,nested",
        "false,WORKSPACE_ROOT,ancestor",
        "true,CONFIG_DIR,equal",
        "true,CONFIG_DIR,nested",
        "true,CONFIG_DIR,ancestor",
        "true,WORKSPACE_ROOT,equal",
        "true,WORKSPACE_ROOT,nested",
        "true,WORKSPACE_ROOT,ancestor",
        "false,CONFIG_DIR,alias",
        "false,WORKSPACE_ROOT,alias",
        "true,CONFIG_DIR,alias",
        "true,WORKSPACE_ROOT,alias"
    })
    @ParameterizedTest
    void stateCleanupPreservesOtherDataScopes(boolean powershell, String variable, String overlap) throws Exception {
        // given
        Fixture fixture = fixture(powershell, true, "state");
        Path preserved =
                switch (overlap) {
                    case "nested" -> fixture.storage().resolve("nested");
                    case "ancestor" -> fixture.home();
                    case "alias" -> linkedStorage(fixture.storage());
                    default -> fixture.storage();
                };
        Files.createDirectories(preserved);
        Path sentinel = Files.writeString(preserved.resolve("keep.txt"), "preserved data");
        var environment = new LinkedHashMap<>(fixture.environment());
        environment.put("SYMPHONY_TRELLO_" + variable, preserved.toString());
        List<String> arguments = new ArrayList<>(fixture.arguments());
        arguments.addAll(List.of("--yes-local-data", "--remove-state"));

        // when
        var result = run(environment, arguments.toArray(String[]::new));

        // then
        assertThat(result.exitCode()).as(result.output()).isEqualTo(powershell ? 1 : 2);
        assertThat(result.output()).containsAnyOf("State cleanup overlaps preserved local data", "must not overlap");
        assertThat(sentinel).hasContent("preserved data");
        assertThat(fixture.state()).isDirectory();
    }

    @CsvSource({
        "false,CONFIG_DIR,--remove-config", "false,WORKSPACE_ROOT,--remove-workspaces",
        "true,CONFIG_DIR,--remove-config", "true,WORKSPACE_ROOT,--remove-workspaces"
    })
    @ParameterizedTest
    void stateCleanupPreservesDataWhoseRemovalWasDeclined(boolean powershell, String variable, String scope)
            throws Exception {
        // given
        Fixture fixture = fixture(powershell, true, "state");
        Path preserved = Files.createDirectory(fixture.storage().resolve("preserved"));
        Path sentinel = Files.writeString(preserved.resolve("keep.txt"), "preserved data");
        var environment = new LinkedHashMap<>(fixture.environment());
        environment.put("SYMPHONY_TRELLO_" + variable, preserved.toString());
        List<String> arguments = new ArrayList<>(fixture.arguments());
        arguments.addAll(List.of(scope, "--remove-state"));

        // when
        ProcessResult result = answerCleanupPrompts(powershell, environment, arguments);

        // then
        assertThat(result.exitCode()).as(result.output()).isEqualTo(powershell ? 1 : 2);
        assertThat(result.output()).contains("State cleanup overlaps preserved local data");
        assertThat(sentinel).hasContent("preserved data");
        assertThat(fixture.state()).isDirectory();
    }

    private static ProcessResult answerCleanupPrompts(
            boolean powershell, Map<String, String> environment, List<String> arguments) throws Exception {
        if (powershell) {
            return runWithInput(environment, "n\ny\n", arguments.toArray(String[]::new));
        }
        assumeTrue(commandExists("script"));
        var command = new StringJoiner(" ");
        for (String argument : arguments) {
            command.add(shellQuote(argument));
        }
        var result = runWithPseudoTerminal(environment, "n\ny\n", command.toString());
        return new ProcessResult(result.exitCode(), result.transcript(), "");
    }

    private Path linkedStorage(Path storage) throws Exception {
        Path alias = temporary.resolve("storage-alias");
        if (isWindows()) {
            run(Map.of(), "cmd.exe", "/d", "/c", "mklink", "/J", alias.toString(), storage.toString())
                    .assertSuccess();
            return alias;
        }
        return Files.createSymbolicLink(alias, storage);
    }

    private Fixture fixture(boolean powershell, boolean stateExists, String stateSpelling) throws Exception {
        List<String> shell = powershell ? powershellCommand() : isWindows() ? List.of() : List.of("bash");
        assumeFalse(shell.isEmpty());
        Path home = Files.createDirectory(temporary.resolve("installation"));
        Path state = home.resolve("state");
        if (stateExists) {
            Files.createDirectory(state);
        }
        Path storage = Files.createDirectory(home.resolve("state-card-requests"));
        Path queued = Files.createDirectory(storage.resolve("installation"));
        Path staged = Files.createDirectory(storage.resolve("installation-submissions"));
        Files.writeString(queued.resolve("queued.request"), "private card text");
        Files.writeString(staged.resolve("staged.request"), "private card text");
        var environment = new LinkedHashMap<>(nonWindowsPowerShellEnvironment());
        environment.put("SYMPHONY_HOME", home.toString());
        Path operatorHome = Files.createDirectory(temporary.resolve("operator-home"));
        environment.put("HOME", operatorHome.toString());
        environment.put("USERPROFILE", operatorHome.toString());
        environment.put("XDG_CONFIG_HOME", operatorHome.resolve("config").toString());
        environment.put("APPDATA", operatorHome.resolve("roaming").toString());
        environment.put("LOCALAPPDATA", operatorHome.resolve("local").toString());
        environment.put(
                "SYMPHONY_TRELLO_STATE_HOME", home.resolve(stateSpelling).toString());
        List<String> arguments = new ArrayList<>(shell);
        if (powershell) {
            arguments.addAll(List.of("-NoProfile", "-File"));
        }
        arguments.addAll(List.of(
                powershell ? "./uninstall.ps1" : "uninstall.sh",
                "--yes",
                "--bin-dir",
                temporary.resolve("bin").toString(),
                "--prefix",
                temporary.resolve("app").toString()));
        return new Fixture(home, state, storage, Map.copyOf(environment), List.copyOf(arguments));
    }

    private record Fixture(
            Path home, Path state, Path storage, Map<String, String> environment, List<String> arguments) {}
}
