package ch.fmartin.symphony.trello.setup;

import static ch.fmartin.symphony.trello.setup.InstallerScriptFixture.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ch.fmartin.symphony.trello.setup.InstallerScriptFixture.ProcessResult;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

final class InstallerScriptTest {
    private static final String INSTALL_CONTEXT_PROPERTIES = "install-context.properties";

    @TempDir
    Path temporaryDirectory;

    @Test
    void fixtureCapturesOutputLargerThanProcessPipesWithoutDeadlocking() throws Exception {
        // given
        Path source = temporaryDirectory.resolve("LargeOutput.java");
        Files.writeString(
                source,
                """
                public class LargeOutput {
                    public static void main(String[] arguments) {
                        System.out.print("o".repeat(262_144));
                        System.err.print("e".repeat(262_144));
                    }
                }
                """);
        Path java = Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java");
        var processBuilder = new ProcessBuilder(java.toString(), source.toString());
        for (String launcherOption : List.of("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS")) {
            processBuilder.environment().remove(launcherOption);
        }

        // when
        ProcessResult result = run(processBuilder, "", 60);

        // then
        result.assertSuccess();
        assertThat(result.output())
                .as("captured stdout and stderr")
                .hasSize(524_288)
                .startsWith("o")
                .endsWith("e");
    }

    @Test
    void fixtureStartsTimeoutBeforeChildConsumesLargeInput() throws Exception {
        // given
        Path source = temporaryDirectory.resolve("DelayedInput.java");
        Files.writeString(
                source,
                """
                public class DelayedInput {
                    public static void main(String[] arguments) throws Exception {
                        Thread.sleep(3_000);
                    }
                }
                """);
        Path java = Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java");
        var processBuilder = new ProcessBuilder(java.toString(), source.toString());
        for (String launcherOption : List.of("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS")) {
            processBuilder.environment().remove(launcherOption);
        }

        // when / then
        assertThatThrownBy(() -> run(processBuilder, "i".repeat(1_048_576), 1))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("process timed out");
    }

    @Test
    void fixtureReplacesMalformedUtf8InCapturedOutput() throws Exception {
        // given
        Path source = temporaryDirectory.resolve("MalformedOutput.java");
        Files.writeString(
                source,
                """
                public class MalformedOutput {
                    public static void main(String[] arguments) throws Exception {
                        System.out.write(new byte[] {'o', 'u', 't', (byte) 0xff});
                        System.err.write(new byte[] {'e', 'r', 'r', (byte) 0xff});
                    }
                }
                """);
        Path java = Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java");
        var processBuilder = new ProcessBuilder(java.toString(), source.toString());
        for (String launcherOption : List.of("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS")) {
            processBuilder.environment().remove(launcherOption);
        }

        // when
        ProcessResult result = run(processBuilder, "", 60);

        // then
        result.assertSuccess();
        assertThat(result.output()).isEqualTo("out\ufffderr\ufffd");
    }

    @Test
    void fixtureTerminatesDescendantsWhenProcessTimesOut() throws Exception {
        // given
        assumeFalse(isWindows());
        assumeTrue(commandExists("bash"));
        Path descendantPid = temporaryDirectory.resolve("descendant.pid");
        var processBuilder = new ProcessBuilder(
                "bash",
                "-c",
                "sleep 60 & child=$!; printf '%s' \"$child\" > "
                        + shellQuote(descendantPid.toString())
                        + "; wait \"$child\"");

        // when
        assertThatThrownBy(() -> run(processBuilder, "", 1))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("process timed out");

        // then
        assertThat(descendantPid).exists();
        long pid = Long.parseLong(Files.readString(descendantPid));
        assertThat(ProcessHandle.of(pid).filter(ProcessHandle::isAlive))
                .as("timed-out descendant process")
                .isEmpty();
    }

    @Test
    void fixtureTerminatesReplacementDescendantsSpawnedDuringTimeoutCleanup() throws Exception {
        // given
        assumeFalse(isWindows());
        assumeTrue(commandExists("bash"));
        Path originalPid = temporaryDirectory.resolve("original.pid");
        Path replacementPid = temporaryDirectory.resolve("replacement.pid");
        var processBuilder = new ProcessBuilder(
                "bash",
                "-c",
                "trap 'trap - CHLD; sleep 60 & printf '%s' \"$!\" > \"$2\"' CHLD; "
                        + "sleep 60 & child=$!; printf '%s' \"$child\" > \"$1\"; wait \"$child\"; wait",
                "fixture-timeout",
                originalPid.toString(),
                replacementPid.toString());

        // when
        assertThatThrownBy(() -> run(processBuilder, "", 1))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("process timed out");

        // then
        assertThat(originalPid).exists();
        assertThat(replacementPid).exists();
        assertThat(ProcessHandle.of(Long.parseLong(Files.readString(originalPid)))
                        .filter(ProcessHandle::isAlive))
                .as("original timed-out descendant process")
                .isEmpty();
        assertThat(ProcessHandle.of(Long.parseLong(Files.readString(replacementPid)))
                        .filter(ProcessHandle::isAlive))
                .as("replacement timed-out descendant process")
                .isEmpty();
    }

    @ParameterizedTest(name = "{0} syntax")
    @ValueSource(strings = {"install.sh", "uninstall.sh"})
    void posixInstallAndUninstallScriptsHaveValidBashSyntax(String script) throws Exception {
        // given
        assumeTrue(commandExists("bash"));

        // when
        ProcessResult result = run(Map.of(), "bash", "-n", script);

        // then
        result.assertSuccess();
    }

    @MethodSource("posixPublicDryRunScenarios")
    @ParameterizedTest(name = "{0}")
    void posixPublicDryRunCommandsStopBeforeChangingFiles(String name, String[] command, String[] expectedOutput)
            throws Exception {
        // given
        assumeTrue(commandExists("bash"));

        // when
        ProcessResult result = run(Map.of(), command);

        // then
        result.assertSuccess();
        assertThat(result.output()).contains(expectedOutput);
    }

    private static Stream<Arguments> posixPublicDryRunScenarios() {
        return Stream.of(
                Arguments.of(
                        "install dry-run",
                        new String[] {"bash", "install.sh", "--dry-run", "--no-onboard"},
                        new String[] {
                            "Symphony for Trello installer",
                            "Dry run: no files changed.",
                            "Symphony would install the command here:",
                            "Command PATH setup"
                        }),
                Arguments.of("uninstall dry-run", new String[] {"bash", "uninstall.sh", "--dry-run"}, new String[] {
                    "Symphony for Trello uninstall", "Trello boards were not deleted or archived."
                }));
    }

    @Test
    void posixFixtureSuppressesInheritedRepositoryEnvironmentControls() throws Exception {
        // given
        assumeFalse(isWindows());
        assumeTrue(commandExists("bash"));
        Map<String, String> inheritedControls = Map.ofEntries(
                Map.entry("SYMPHONY_HOME", "inherited-home-sentinel"),
                Map.entry("SYMPHONY_TRELLO_CONFIG_DIR", "inherited-config-sentinel"),
                Map.entry("SYMPHONY_TRELLO_WORKSPACE_ROOT", "inherited-workspace-sentinel"),
                Map.entry("SYMPHONY_TRELLO_STATE_HOME", "inherited-state-sentinel"),
                Map.entry("SYMPHONY_TRELLO_VERSION", "inherited-version-sentinel"),
                Map.entry("SYMPHONY_TRELLO_RELEASE_TAG", "inherited-tag-sentinel"),
                Map.entry("SYMPHONY_TRELLO_RELEASE_BASE_URL", "https://example.invalid/inherited-release"),
                Map.entry("SYMPHONY_TRELLO_REPO_URL", "https://example.invalid/inherited-repository.git"),
                Map.entry("SYMPHONY_TRELLO_REF", "inherited-ref-sentinel"),
                Map.entry("SYMPHONY_TRELLO_INSTALL_SOURCE", "inherited-source-sentinel"),
                Map.entry("SYMPHONY_TRELLO_TEST_INHERITED", "inherited-test-sentinel"),
                Map.entry("sYmPhOnY_FUTURE_CONTROL", "inherited-mixed-case-sentinel"));
        String inheritedNames = String.join(" ", inheritedControls.keySet());
        var processBuilder = new ProcessBuilder(
                "bash",
                "-c",
                "for name in " + inheritedNames + "; do printf '%s\\n' \"${!name}\"; done; "
                        + "printf 'fixture-os=%s\\nunrelated=%s\\n' "
                        + "\"${SYMPHONY_TRELLO_TEST_OS:-}\" \"${UNRELATED_HOST_CONTROL:-}\"");
        processBuilder.environment().putAll(inheritedControls);
        processBuilder.environment().put("UNRELATED_HOST_CONTROL", "unrelated-host-sentinel");

        // when
        ProcessResult result = run(Map.of(), processBuilder);

        // then
        result.assertSuccess();
        assertThat(result.output()).doesNotContain(inheritedControls.values().toArray(String[]::new));
        assertThat(result.output()).contains("unrelated=unrelated-host-sentinel");
        if (System.getProperty("os.name", "").equalsIgnoreCase("Linux")) {
            assertThat(result.output()).contains("fixture-os=Linux");
        }
    }

    @Test
    void posixFixtureKeepsExplicitRepositoryEnvironmentControlsAuthoritative() throws Exception {
        // given
        assumeFalse(isWindows());
        assumeTrue(commandExists("bash"));
        Path explicitSymphonyHome = temporaryDirectory.resolve("explicit-symphony-home");
        Path explicitConfigDirectory = temporaryDirectory.resolve("explicit-config");
        Map<String, String> explicitEnvironment = Map.of(
                "SYMPHONY_HOME", explicitSymphonyHome.toString(),
                "SYMPHONY_TRELLO_CONFIG_DIR", explicitConfigDirectory.toString(),
                "SYMPHONY_TRELLO_INSTALL_SOURCE", "release-archive");
        var processBuilder = new ProcessBuilder(
                "bash",
                "-c",
                "printf '%s\\n%s\\n%s' \"$SYMPHONY_HOME\" \"$SYMPHONY_TRELLO_CONFIG_DIR\" "
                        + "\"$SYMPHONY_TRELLO_INSTALL_SOURCE\"");
        processBuilder.environment().put("SYMPHONY_HOME", "inherited-home-sentinel");
        processBuilder.environment().put("SYMPHONY_TRELLO_CONFIG_DIR", "inherited-config-sentinel");
        processBuilder.environment().put("SYMPHONY_TRELLO_INSTALL_SOURCE", "inherited-source-sentinel");

        // when
        ProcessResult result = run(explicitEnvironment, processBuilder);

        // then
        result.assertSuccess();
        assertThat(result.output())
                .isEqualTo(String.join(
                        "\n", explicitSymphonyHome.toString(), explicitConfigDirectory.toString(), "release-archive"))
                .doesNotContain("inherited-home-sentinel", "inherited-config-sentinel", "inherited-source-sentinel");
    }

    @Test
    void posixPublicInstallerDryRunUsesDefaultsInsteadOfInheritedRepositoryControls() throws Exception {
        // given
        assumeFalse(isWindows());
        assumeTrue(commandExists("bash"));
        Path inheritedHome = temporaryDirectory.resolve("inherited-home-sentinel");
        var processBuilder = new ProcessBuilder("bash", "install.sh", "--dry-run", "--no-onboard");
        processBuilder.environment().put("SYMPHONY_HOME", inheritedHome.toString());
        processBuilder.environment().put("SYMPHONY_TRELLO_INSTALL_SOURCE", "inherited-source-sentinel");
        processBuilder
                .environment()
                .put("SYMPHONY_TRELLO_REPO_URL", "https://example.invalid/inherited-repository.git");
        processBuilder.environment().put("SYMPHONY_TRELLO_REF", "inherited-ref-sentinel");

        // when
        ProcessResult result = run(Map.of(), processBuilder);

        // then
        result.assertSuccess();
        assertThat(result.output())
                .contains(
                        "Symphony for Trello installer",
                        "Install source: release-archive",
                        "Dry run: no files changed.",
                        "WOULD download release archive:")
                .doesNotContain(
                        inheritedHome.toString(),
                        "inherited-source-sentinel",
                        "https://example.invalid/inherited-repository.git",
                        "inherited-ref-sentinel");
        Path generatedHome = installerHomeFromDryRunOutput(result.output());
        assertThat(generatedHome).doesNotExist();
        assertThat(generatedHome.getFileName().toString()).startsWith("symphony-trello-installer-home-");
        assertThat(inheritedHome).doesNotExist();
        assertThat(regularFilesUnder(temporaryDirectory)).isEmpty();
    }

    @Test
    void posixExplicitInstallSourceSuppressesInheritedRepositoryFallbacks() throws Exception {
        // given
        assumeFalse(isWindows());
        assumeTrue(commandExists("bash"));
        var processBuilder = new ProcessBuilder(
                "bash",
                "-c",
                "if [[ -n ${SYMPHONY_TRELLO_REPO_URL:-} || -n ${SYMPHONY_TRELLO_REF:-} ]]; then "
                        + "printf 'inherited fallback reached child'; exit 91; fi; "
                        + "exec bash install.sh --dry-run --no-onboard");
        processBuilder.environment().put("SYMPHONY_TRELLO_INSTALL_SOURCE", "inherited-source-sentinel");
        processBuilder.environment().put("SYMPHONY_TRELLO_REPO_URL", "not-a-valid-repository");
        processBuilder.environment().put("SYMPHONY_TRELLO_REF", "refs/heads/not-a-valid-ref");

        // when
        ProcessResult result = run(Map.of("SYMPHONY_TRELLO_INSTALL_SOURCE", "release-archive"), processBuilder);

        // then
        result.assertSuccess();
        assertThat(result.output())
                .contains("Install source: release-archive", "Dry run: no files changed.")
                .doesNotContain(
                        "inherited fallback reached child",
                        "inherited-source-sentinel",
                        "not-a-valid-repository",
                        "refs/heads/not-a-valid-ref");
    }

    @CsvSource(
            delimiter = '|',
            value = {
                "SYMPHONY_HOME|true",
                "sYmPhOnY_future_control|true",
                "SYMPHONY|false",
                "NOT_SYMPHONY_CONTROL|false"
            })
    @ParameterizedTest(name = "[{index}] {0} is a repository environment control: {1}")
    void repositoryEnvironmentControlPrefixMatchesCaseInsensitively(String variable, boolean expected) {
        // given

        // when
        boolean matches = isRepositoryEnvironmentControl(variable);

        // then
        assertThat(matches)
                .as("repository environment-control classification for <%s>", variable)
                .isEqualTo(expected);
    }

    private static Path installerHomeFromDryRunOutput(String output) {
        String installPrefix = "Install: ";
        Path appDirectory = output.lines()
                .filter(line -> line.startsWith(installPrefix))
                .map(line -> Path.of(line.substring(installPrefix.length())))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Installer output did not contain an install path"));
        return appDirectory.getParent().getParent().getParent().getParent();
    }

    @Test
    void posixFixtureIsolatesTheDefaultHomeDistroAndJavaFromTheHost() throws Exception {
        // given
        assumeFalse(isWindows());
        assumeTrue(commandExists("bash"));
        Path capturedEnvironment = temporaryDirectory.resolve("captured-environment");

        // when
        ProcessResult result = run(
                Map.of("SYMPHONY_FAKE_LOG", capturedEnvironment.toString()),
                "bash",
                "-c",
                "printf '%s\\n%s\\n%s\\n%s\\n%s\\n%s\\n%s\\n%s\\n%s\\n' "
                        + "\"$HOME\" \"${SYMPHONY_TRELLO_TEST_OS:-}\" \"${SYMPHONY_TRELLO_TEST_OS_ID:-}\" "
                        + "\"$(command -v java)\" \"${SYMPHONY_TRELLO_TEST_HOME_FS_SOURCE:-}\" "
                        + "\"${SYMPHONY_TRELLO_TEST_ROOT_FS_SOURCE:-}\" "
                        + "\"${SYMPHONY_TRELLO_TEST_VAR_FS_SOURCE:-}\" "
                        + "\"${SYMPHONY_TRELLO_TEST_HOME_SIZE_KB:-}\" "
                        + "\"${SYMPHONY_TRELLO_TEST_VAR_SIZE_KB:-}\" > \"$SYMPHONY_FAKE_LOG\"");

        // then
        result.assertSuccess();
        List<String> environment = Files.readAllLines(capturedEnvironment);
        Path isolatedHome = Path.of(environment.getFirst());
        Path repositoryRoot = Path.of("").toAbsolutePath();
        assertThat(isolatedHome)
                .isAbsolute()
                .doesNotExist()
                .doesNotMatch(
                        path -> path.startsWith(repositoryRoot),
                        "path under repository working tree <%s>".formatted(repositoryRoot));
        if (System.getProperty("os.name", "").equalsIgnoreCase("Linux")) {
            assertThat(environment.get(1)).isEqualTo("Linux");
            assertThat(environment.get(2)).isEqualTo("debian");
        }
        assertThat(Path.of(environment.get(3)).toRealPath())
                .isEqualTo(
                        Path.of(System.getProperty("java.home"), "bin", "java").toRealPath());
        if (System.getProperty("os.name", "").equalsIgnoreCase("Linux")) {
            assertThat(environment.subList(4, 7)).containsExactly("fixture-root", "fixture-root", "fixture-root");
            assertThat(environment.subList(7, 9)).containsExactly("1048576", "1048576");
        }
    }

    @Test
    void posixFixtureUsesDistinctStableHomesForAbsoluteSymphonyHomes() throws Exception {
        // given
        assumeFalse(isWindows());
        assumeTrue(commandExists("bash"));
        Path firstSymphonyHome = temporaryDirectory.resolve("first-symphony-home");
        Path secondSymphonyHome = temporaryDirectory.resolve("second-symphony-home");

        // when
        ProcessResult first =
                run(Map.of("SYMPHONY_HOME", firstSymphonyHome.toString()), "bash", "-c", "printf '%s' \"$HOME\"");
        ProcessResult second =
                run(Map.of("SYMPHONY_HOME", secondSymphonyHome.toString()), "bash", "-c", "printf '%s' \"$HOME\"");
        ProcessResult repeatedFirst =
                run(Map.of("SYMPHONY_HOME", firstSymphonyHome.toString()), "bash", "-c", "printf '%s' \"$HOME\"");

        // then
        first.assertSuccess();
        second.assertSuccess();
        repeatedFirst.assertSuccess();
        Path firstHome = Path.of(first.output());
        Path secondHome = Path.of(second.output());
        assertThat(firstHome).hasParentRaw(temporaryDirectory);
        assertThat(firstHome).isDirectory().hasFileName("first-symphony-home-user-home");
        assertThat(secondHome).hasParentRaw(temporaryDirectory);
        assertThat(secondHome).isDirectory().isNotEqualTo(firstHome).hasFileName("second-symphony-home-user-home");
        assertThat(Path.of(repeatedFirst.output())).isEqualTo(firstHome);
    }

    @Test
    void posixFixtureUsesTemporaryHomeWhenSymphonyHomeHasNoParent() throws Exception {
        // given
        assumeFalse(isWindows());
        assumeTrue(commandExists("bash"));

        // when
        ProcessResult result = run(Map.of("SYMPHONY_HOME", "/"), "bash", "-c", "printf '%s' \"$HOME\"");

        // then
        result.assertSuccess();
        Path isolatedHome = Path.of(result.output());
        Path repositoryRoot = Path.of("").toAbsolutePath();
        assertThat(isolatedHome)
                .isAbsolute()
                .doesNotExist()
                .doesNotMatch(
                        path -> path.startsWith(repositoryRoot),
                        "path under repository working tree <%s>".formatted(repositoryRoot));
    }

    @Test
    void posixInstallerUsesXdgSplitLayoutByDefault() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        Path home = temporaryDirectory.resolve("xdg-home");
        Files.createDirectories(home);

        // when
        ProcessResult result = run(Map.of("HOME", home.toString()), "bash", "install.sh", "--dry-run", "--no-onboard");

        // then
        result.assertSuccess();
        assertThat(result.output())
                .contains(
                        "Install: " + home.resolve(".local/share/symphony-trello/app"),
                        "Config: " + home.resolve(".config/symphony-trello"),
                        "Workspaces: " + home.resolve(".local/share/symphony-trello/workspaces"),
                        "State/logs: " + home.resolve(".local/state/symphony-trello"),
                        "Command: " + home.resolve(".local/bin/symphony-trello"));
    }

    @Test
    void posixInstallerUsesMicroOsVarLayoutWhenHomeIsRootBacked() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        Path home = temporaryDirectory.resolve("microos-root-home");
        Path varPath = temporaryDirectory.resolve("microos-var");
        Path usersRoot = temporaryDirectory.resolve("microos-users");
        Files.createDirectories(home);
        Files.createDirectories(varPath);

        // when
        ProcessResult result = run(
                microOsLayoutEnvironment(home, varPath, usersRoot), "bash", "install.sh", "--dry-run", "--no-onboard");

        // then
        result.assertSuccess();
        Path userRoot = usersRoot.resolve("micro-user");
        assertThat(result.output())
                .contains(
                        "Detected openSUSE MicroOS amd64",
                        "Install: " + userRoot.resolve("data/app"),
                        "Config: " + userRoot.resolve("config"),
                        "Workspaces: " + userRoot.resolve("workspaces"),
                        "State/logs: " + userRoot.resolve("state"),
                        "Command: " + home.resolve(".local/bin/symphony-trello"),
                        "Detected MicroOS-like storage layout.",
                        "WOULD create MicroOS data root with:",
                        "install -d -m 0750 -o 'micro-user' -g 'micro-group' '" + userRoot + "'");
    }

    @Test
    void posixScriptsUseMicroOsVarLayoutWhenVarIsLargerThanRootBackedHome() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        Path home = temporaryDirectory.resolve("microos-larger-var-home");
        Path varPath = temporaryDirectory.resolve("microos-larger-var");
        Path usersRoot = temporaryDirectory.resolve("microos-larger-var-users");
        Path userRoot = usersRoot.resolve("micro-user");
        Files.createDirectories(home);
        Files.createDirectories(varPath);
        Map<String, String> environment = new LinkedHashMap<>(microOsLayoutEnvironment(home, varPath, usersRoot));
        environment.put("SYMPHONY_TRELLO_TEST_HOME_SIZE_KB", "20971520");
        environment.put("SYMPHONY_TRELLO_TEST_VAR_SIZE_KB", "41943040");

        // when
        ProcessResult install = run(environment, "bash", "install.sh", "--dry-run", "--no-onboard");
        ProcessResult uninstall = run(environment, "bash", "uninstall.sh", "--dry-run");

        // then
        install.assertSuccess();
        uninstall.assertSuccess();
        assertThat(install.output())
                .contains(
                        "Install: " + userRoot.resolve("data/app"),
                        "Config: " + userRoot.resolve("config"),
                        "Workspaces: " + userRoot.resolve("workspaces"),
                        "State/logs: " + userRoot.resolve("state"));
        assertThat(uninstall.output())
                .contains(
                        "APP FILES       " + userRoot.resolve("data/app"),
                        "CONFIG          " + userRoot.resolve("config"),
                        "WORKSPACES      " + userRoot.resolve("workspaces"),
                        "STATE/LOGS      " + userRoot.resolve("state"));
    }

    @Test
    void posixInstallerRejectsSymlinkedMicroOsVarRoot() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        Path home = temporaryDirectory.resolve("microos-symlink-root-home");
        Path varPath = temporaryDirectory.resolve("microos-symlink-root-var");
        Path usersRoot = temporaryDirectory.resolve("microos-symlink-root-users");
        Path outside = temporaryDirectory.resolve("microos-symlink-root-outside");
        Path userRoot = usersRoot.resolve("micro-user");
        Files.createDirectories(home);
        Files.createDirectories(varPath);
        Files.createDirectories(usersRoot);
        Files.createDirectories(outside);
        Files.createSymbolicLink(userRoot, outside);

        // when
        ProcessResult result = runUnchecked(
                microOsLayoutEnvironment(home, varPath, usersRoot), "bash", "install.sh", "--dry-run", "--no-onboard");

        // then
        assertThat(result.exitCode()).as(result.output()).isEqualTo(2);
        assertThat(result.output())
                .contains("Recommended MicroOS data root must not contain symlinked path components: " + userRoot)
                .doesNotContain("Install: " + outside.resolve("data/app"));
    }

    @Test
    void posixInstallerRejectsSymlinkedMicroOsVarRootParent() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        Path home = temporaryDirectory.resolve("microos-symlink-parent-home");
        Path varPath = temporaryDirectory.resolve("microos-symlink-parent-var");
        Path realUsersParent = temporaryDirectory.resolve("microos-symlink-parent-real");
        Path symlinkUsersParent = temporaryDirectory.resolve("microos-symlink-parent-link");
        Path usersRoot = symlinkUsersParent.resolve("users");
        Path userRoot = usersRoot.resolve("micro-user");
        Files.createDirectories(home);
        Files.createDirectories(varPath);
        Files.createDirectories(realUsersParent);
        Files.createSymbolicLink(symlinkUsersParent, realUsersParent);

        // when
        ProcessResult result = runUnchecked(
                microOsLayoutEnvironment(home, varPath, usersRoot), "bash", "install.sh", "--dry-run", "--no-onboard");

        // then
        assertThat(result.exitCode()).as(result.output()).isEqualTo(2);
        assertThat(result.output())
                .contains("Recommended MicroOS data root must not contain symlinked path components: " + userRoot)
                .doesNotContain("WOULD create MicroOS data root");
        assertThat(realUsersParent.resolve("users")).doesNotExist();
    }

    @Test
    void posixInstallerRejectsUnsafeMicroOsUsernamePathSegment() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        Path home = temporaryDirectory.resolve("microos-unsafe-user-home");
        Path varPath = temporaryDirectory.resolve("microos-unsafe-user-var");
        Path usersRoot = temporaryDirectory.resolve("microos-unsafe-user-root");
        Files.createDirectories(home);
        Files.createDirectories(varPath);
        Map<String, String> environment = new LinkedHashMap<>(microOsLayoutEnvironment(home, varPath, usersRoot));
        environment.put("SYMPHONY_TRELLO_TEST_USER", "../../../../etc/pwn");

        // when
        ProcessResult result = runUnchecked(environment, "bash", "install.sh", "--dry-run", "--no-onboard");

        // then
        assertThat(result.exitCode()).as(result.output()).isEqualTo(2);
        assertThat(result.output())
                .contains("Could not determine a safe local username for the MicroOS data root.")
                .doesNotContain("../../../../etc/pwn", "Dry run: no files changed.");
    }

    @Test
    void posixInstallerKeepsMicroOsVarLayoutWhenOnlyCommandDirectoryIsOverridden() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        Path home = temporaryDirectory.resolve("microos-bin-home");
        Path varPath = temporaryDirectory.resolve("microos-bin-var");
        Path usersRoot = temporaryDirectory.resolve("microos-bin-users");
        Path binDirectory = temporaryDirectory.resolve("microos-bin");
        Files.createDirectories(home);
        Files.createDirectories(varPath);

        // when
        ProcessResult result = run(
                microOsLayoutEnvironment(home, varPath, usersRoot),
                "bash",
                "install.sh",
                "--dry-run",
                "--no-onboard",
                "--bin-dir",
                binDirectory.toString());

        // then
        result.assertSuccess();
        assertThat(result.output())
                .contains(
                        "Install: " + usersRoot.resolve("micro-user/data/app"),
                        "Workspaces: " + usersRoot.resolve("micro-user/workspaces"),
                        "Command: " + binDirectory.resolve("symphony-trello"))
                .doesNotContain("Install: " + home.resolve(".local/share/symphony-trello/app"));
    }

    @Test
    void posixInstallerKeepsMicroOsVarLayoutWhenOnlyPrefixIsOverridden() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        Path home = temporaryDirectory.resolve("microos-prefix-home");
        Path varPath = temporaryDirectory.resolve("microos-prefix-var");
        Path usersRoot = temporaryDirectory.resolve("microos-prefix-users");
        Path app = temporaryDirectory.resolve("microos-prefix-app");
        Files.createDirectories(home);
        Files.createDirectories(varPath);

        // when
        ProcessResult result = run(
                microOsLayoutEnvironment(home, varPath, usersRoot),
                "bash",
                "install.sh",
                "--dry-run",
                "--no-onboard",
                "--prefix",
                app.toString());

        // then
        result.assertSuccess();
        assertThat(result.output())
                .contains(
                        "Install: " + app,
                        "Config: " + usersRoot.resolve("micro-user/config"),
                        "Workspaces: " + usersRoot.resolve("micro-user/workspaces"),
                        "State/logs: " + usersRoot.resolve("micro-user/state"),
                        "Detected MicroOS-like storage layout.")
                .doesNotContain(
                        "Config: " + home.resolve(".config/symphony-trello"),
                        "Workspaces: " + home.resolve(".local/share/symphony-trello/workspaces"),
                        "State/logs: " + home.resolve(".local/state/symphony-trello"));
    }

    @Test
    void posixInstallerKeepsMicroOsVarLayoutWhenOnlyConfigDirectoryIsOverridden() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        Path home = temporaryDirectory.resolve("microos-config-home");
        Path varPath = temporaryDirectory.resolve("microos-config-var");
        Path usersRoot = temporaryDirectory.resolve("microos-config-users");
        Path configDirectory = temporaryDirectory.resolve("microos-config");
        Files.createDirectories(home);
        Files.createDirectories(varPath);
        Map<String, String> environment = new LinkedHashMap<>(microOsLayoutEnvironment(home, varPath, usersRoot));
        environment.put("SYMPHONY_TRELLO_CONFIG_DIR", configDirectory.toString());

        // when
        ProcessResult result = run(environment, "bash", "install.sh", "--dry-run", "--no-onboard");

        // then
        result.assertSuccess();
        Path userRoot = usersRoot.resolve("micro-user");
        assertThat(result.output())
                .contains(
                        "Install: " + userRoot.resolve("data/app"),
                        "Config: " + configDirectory,
                        "Workspaces: " + userRoot.resolve("workspaces"),
                        "State/logs: " + userRoot.resolve("state"))
                .doesNotContain(
                        "Install: " + home.resolve(".local/share/symphony-trello/app"),
                        "Workspaces: " + home.resolve(".local/share/symphony-trello/workspaces"),
                        "State/logs: " + home.resolve(".local/state/symphony-trello"));
    }

    @Test
    void posixInstallerKeepsXdgLayoutOnMicroOsWhenHomeIsNotRootBacked() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        Path home = temporaryDirectory.resolve("microos-normal-home");
        Path varPath = temporaryDirectory.resolve("microos-normal-var");
        Path usersRoot = temporaryDirectory.resolve("microos-normal-users");
        Files.createDirectories(home);
        Files.createDirectories(varPath);
        Map<String, String> environment = new LinkedHashMap<>(microOsLayoutEnvironment(home, varPath, usersRoot));
        environment.put("SYMPHONY_TRELLO_TEST_HOME_FS_SOURCE", "homefs");
        environment.put("SYMPHONY_TRELLO_TEST_ROOT_FS_SOURCE", "rootfs");
        environment.put("SYMPHONY_TRELLO_TEST_VAR_FS_SOURCE", "varfs");

        // when
        ProcessResult result = run(environment, "bash", "install.sh", "--dry-run", "--no-onboard");

        // then
        result.assertSuccess();
        assertThat(result.output())
                .contains(
                        "Detected openSUSE MicroOS amd64",
                        "Install: " + home.resolve(".local/share/symphony-trello/app"),
                        "Config: " + home.resolve(".config/symphony-trello"),
                        "State/logs: " + home.resolve(".local/state/symphony-trello"))
                .doesNotContain("Detected MicroOS-like storage layout.", usersRoot.toString());
    }

    @Test
    void posixScriptsUseMicroOsVarLayoutForStorageHeuristicWithoutMicroOsRelease() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        Path home = temporaryDirectory.resolve("storage-root-home");
        Path varPath = temporaryDirectory.resolve("storage-var");
        Path usersRoot = temporaryDirectory.resolve("storage-users");
        Path userRoot = usersRoot.resolve("micro-user");
        Files.createDirectories(home);
        Files.createDirectories(varPath);
        Map<String, String> environment = new LinkedHashMap<>(microOsLayoutEnvironment(home, varPath, usersRoot));
        environment.put("SYMPHONY_TRELLO_TEST_OS_ID", "debian");
        environment.put("SYMPHONY_TRELLO_TEST_OS_PRETTY_NAME", "Debian GNU/Linux");
        environment.put("SYMPHONY_TRELLO_TEST_HOME_FS_SOURCE", "rootfs");
        environment.put("SYMPHONY_TRELLO_TEST_ROOT_FS_SOURCE", "rootfs");
        environment.put("SYMPHONY_TRELLO_TEST_VAR_FS_SOURCE", "varfs");
        environment.put("SYMPHONY_TRELLO_TEST_HOME_SIZE_KB", "1024");
        environment.put("SYMPHONY_TRELLO_TEST_VAR_SIZE_KB", "8192");

        // when
        ProcessResult install = run(environment, "bash", "install.sh", "--dry-run", "--no-onboard");
        ProcessResult uninstall = run(environment, "bash", "uninstall.sh", "--dry-run");

        // then
        install.assertSuccess();
        uninstall.assertSuccess();
        assertThat(install.output())
                .contains(
                        "Detected MicroOS-like storage layout.",
                        "Install: " + userRoot.resolve("data/app"),
                        "Workspaces: " + userRoot.resolve("workspaces"),
                        "State/logs: " + userRoot.resolve("state"))
                .doesNotContain("Install: " + home.resolve(".local/share/symphony-trello/app"));
        assertThat(uninstall.output())
                .contains(
                        "APP FILES       " + userRoot.resolve("data/app"),
                        "WORKSPACES      " + userRoot.resolve("workspaces"),
                        "STATE/LOGS      " + userRoot.resolve("state"))
                .doesNotContain("APP FILES       " + home.resolve(".local/share/symphony-trello/app"));
    }

    @Test
    void posixScriptsSkipIncompleteInstallContextDuringDefaultLayoutSelection() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        Path home = temporaryDirectory.resolve("partial-context-home");
        Path varPath = temporaryDirectory.resolve("partial-context-var");
        Path usersRoot = temporaryDirectory.resolve("partial-context-users");
        Path userRoot = usersRoot.resolve("micro-user");
        Path partialApp = temporaryDirectory.resolve("partial-context-app");
        Path partialContext = installContext(home.resolve(".local/state/symphony-trello"));
        Files.createDirectories(home);
        Files.createDirectories(varPath);
        Files.createDirectories(partialApp);
        Files.createDirectories(partialContext.getParent());
        Files.writeString(
                partialContext,
                """
                installer=install.sh
                install_format_version=2
                app_dir=%s
                config_dir=/tmp/ignored-config
                """
                        .formatted(partialApp));
        Map<String, String> environment = new LinkedHashMap<>(microOsLayoutEnvironment(home, varPath, usersRoot));
        environment.put("SYMPHONY_TRELLO_TEST_OS_ID", "debian");
        environment.put("SYMPHONY_TRELLO_TEST_OS_PRETTY_NAME", "Debian GNU/Linux");

        // when
        ProcessResult install = run(environment, "bash", "install.sh", "--dry-run", "--no-onboard");
        ProcessResult uninstall = run(environment, "bash", "uninstall.sh", "--dry-run");

        // then
        install.assertSuccess();
        uninstall.assertSuccess();
        assertThat(install.output())
                .contains(
                        "Detected MicroOS-like storage layout.",
                        "Install: " + userRoot.resolve("data/app"),
                        "Config: " + userRoot.resolve("config"))
                .doesNotContain(partialApp.toString(), "/tmp/ignored-config");
        assertThat(uninstall.output())
                .contains(
                        "APP FILES       " + userRoot.resolve("data/app"),
                        "CONFIG          " + userRoot.resolve("config"))
                .doesNotContain(partialApp.toString(), "/tmp/ignored-config");
    }

    @Test
    void posixScriptsSkipUnsafeLegacyContextPathThroughUntrustedSymlink() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        Path home = temporaryDirectory.resolve("unsafe-legacy-context-home");
        Path outside = temporaryDirectory.resolve("unsafe-legacy-context-outside");
        Path varPath = temporaryDirectory.resolve("unsafe-legacy-context-var");
        Path usersRoot = temporaryDirectory.resolve("unsafe-legacy-context-users");
        Path userRoot = usersRoot.resolve("micro-user");
        Path binDirectory = home.resolve("bin");
        Path unsafeContext = installContext(outside.resolve("share/symphony-trello/state"));
        Path replayedApp = temporaryDirectory.resolve("unsafe-legacy-context-replayed-app");
        Files.createDirectories(home);
        Files.createDirectories(binDirectory);
        Files.createDirectories(outside);
        Files.createDirectories(varPath);
        Files.createDirectories(unsafeContext.getParent());
        Files.createSymbolicLink(home.resolve(".local"), outside);
        Files.writeString(
                unsafeContext,
                """
                installer=install.sh
                install_format_version=2
                layout_mode=custom-flags
                app_dir=%s
                config_dir=%s
                workspace_root=%s
                state_home=%s
                bin_dir=%s
                created_microos_var_root=false
                """
                        .formatted(
                                replayedApp,
                                temporaryDirectory.resolve("unsafe-legacy-context-replayed-config"),
                                temporaryDirectory.resolve("unsafe-legacy-context-replayed-workspaces"),
                                temporaryDirectory.resolve("unsafe-legacy-context-replayed-state"),
                                temporaryDirectory.resolve("unsafe-legacy-context-replayed-bin")));
        Map<String, String> environment = new LinkedHashMap<>(microOsLayoutEnvironment(home, varPath, usersRoot));
        environment.put("SYMPHONY_TRELLO_TEST_OS_ID", "debian");
        environment.put("SYMPHONY_TRELLO_TEST_OS_PRETTY_NAME", "Debian GNU/Linux");

        // when
        ProcessResult install = run(
                environment, "bash", "install.sh", "--dry-run", "--no-onboard", "--bin-dir", binDirectory.toString());
        ProcessResult uninstall =
                run(environment, "bash", "uninstall.sh", "--dry-run", "--bin-dir", binDirectory.toString());

        // then
        install.assertSuccess();
        uninstall.assertSuccess();
        assertThat(install.output())
                .contains("Install: " + userRoot.resolve("data/app"))
                .doesNotContain("Install: " + replayedApp);
        assertThat(uninstall.output())
                .contains("APP FILES       " + userRoot.resolve("data/app"))
                .doesNotContain("APP FILES       " + replayedApp);
        assertThat(unsafeContext).exists();
    }

    @Test
    void posixScriptsDoNotRequireMicroOsUsernameForGenericContextDiscovery() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        Path home = temporaryDirectory.resolve("generic-context-no-user-home");
        Path fakeBin = temporaryDirectory.resolve("generic-context-no-user-bin");
        Files.createDirectories(home);
        Files.createDirectories(fakeBin);
        writeExecutable(
                fakeBin.resolve("id"),
                """
                #!/usr/bin/env bash
                exit 1
                """);
        Map<String, String> environment =
                Map.of("PATH", fakeBin + File.pathSeparator + System.getenv("PATH"), "HOME", home.toString());

        // when
        ProcessResult install = run(environment, "bash", "install.sh", "--dry-run", "--no-onboard");
        ProcessResult uninstall = run(environment, "bash", "uninstall.sh", "--dry-run");

        // then
        install.assertSuccess();
        uninstall.assertSuccess();
        assertThat(install.output())
                .contains("Install: " + home.resolve(".local/share/symphony-trello/app"))
                .doesNotContain("Could not determine a safe local username for the MicroOS data root.");
        assertThat(uninstall.output())
                .contains("APP FILES       " + home.resolve(".local/share/symphony-trello/app"))
                .doesNotContain("Could not determine a safe local username for the MicroOS data root.");
    }

    @Test
    void posixScriptsKeepXdgLayoutForNonMicroOsWhenStorageHeuristicDoesNotMatch() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        Path home = temporaryDirectory.resolve("storage-same-source-home");
        Path varPath = temporaryDirectory.resolve("storage-same-source-var");
        Path usersRoot = temporaryDirectory.resolve("storage-same-source-users");
        Files.createDirectories(home);
        Files.createDirectories(varPath);
        Map<String, String> environment = new LinkedHashMap<>(microOsLayoutEnvironment(home, varPath, usersRoot));
        environment.put("SYMPHONY_TRELLO_TEST_OS_ID", "debian");
        environment.put("SYMPHONY_TRELLO_TEST_OS_PRETTY_NAME", "Debian GNU/Linux");
        environment.put("SYMPHONY_TRELLO_TEST_HOME_FS_SOURCE", "rootfs");
        environment.put("SYMPHONY_TRELLO_TEST_ROOT_FS_SOURCE", "rootfs");
        environment.put("SYMPHONY_TRELLO_TEST_VAR_FS_SOURCE", "rootfs");
        environment.put("SYMPHONY_TRELLO_TEST_HOME_SIZE_KB", "1024");
        environment.put("SYMPHONY_TRELLO_TEST_VAR_SIZE_KB", "8192");

        // when
        ProcessResult install = run(environment, "bash", "install.sh", "--dry-run", "--no-onboard");
        ProcessResult uninstall = run(environment, "bash", "uninstall.sh", "--dry-run");

        // then
        install.assertSuccess();
        uninstall.assertSuccess();
        assertThat(install.output())
                .contains(
                        "Install: " + home.resolve(".local/share/symphony-trello/app"),
                        "Config: " + home.resolve(".config/symphony-trello"),
                        "State/logs: " + home.resolve(".local/state/symphony-trello"))
                .doesNotContain("Detected MicroOS-like storage layout.", usersRoot.toString());
        assertThat(uninstall.output())
                .contains(
                        "APP FILES       " + home.resolve(".local/share/symphony-trello/app"),
                        "CONFIG          " + home.resolve(".config/symphony-trello"),
                        "STATE/LOGS      " + home.resolve(".local/state/symphony-trello"))
                .doesNotContain("APP FILES       " + usersRoot.resolve("micro-user/data/app"));
    }

    @Test
    void posixInstallerFailsNonInteractiveMicroOsVarRootCreationWithActionableCommand() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        Path home = temporaryDirectory.resolve("microos-noninteractive-home");
        Path varPath = temporaryDirectory.resolve("microos-noninteractive-var");
        Path usersRoot = temporaryDirectory.resolve("microos-noninteractive-users");
        Path fakeBin = temporaryDirectory.resolve("microos-noninteractive-bin");
        Files.createDirectories(home);
        Files.createDirectories(varPath);
        Files.createDirectories(fakeBin);
        writeExecutable(
                fakeBin.resolve("java"),
                """
                #!/bin/sh
                echo 'openjdk version "25.0.1"' >&2
                """);
        writeExecutable(
                fakeBin.resolve("javac"),
                """
                #!/bin/sh
                echo 'javac 25.0.1'
                """);
        Map<String, String> environment = new LinkedHashMap<>(microOsLayoutEnvironment(home, varPath, usersRoot));
        environment.put("PATH", fakeBin + File.pathSeparator + "/bin");
        environment.put("SYMPHONY_TRELLO_TEST_NO_PRIVILEGE_HELPERS", "true");

        // when
        ProcessResult result = runUnchecked(environment, "/bin/bash", "install.sh", "--no-onboard");

        // then
        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.output())
                .contains(
                        "Detected MicroOS-like storage layout.",
                        "Create the recommended MicroOS data root, then rerun this installer:",
                        "install -d -m 0750 -o 'micro-user' -g 'micro-group' '" + usersRoot.resolve("micro-user") + "'")
                .doesNotContain("Installing Symphony...", "sudo install");
    }

    @Test
    void posixInstallerRejectsExistingMicroOsVarRootWritableByOtherUsers() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        Path home = temporaryDirectory.resolve("microos-world-writable-home");
        Path varPath = temporaryDirectory.resolve("microos-world-writable-var");
        Path usersRoot = temporaryDirectory.resolve("microos-world-writable-users");
        Path userRoot = usersRoot.resolve("micro-user");
        Files.createDirectories(home);
        Files.createDirectories(varPath);
        Files.createDirectories(userRoot);
        Files.setPosixFilePermissions(userRoot, PosixFilePermissions.fromString("rwxrwx---"));
        Map<String, String> environment = microOsLayoutEnvironment(home, varPath, usersRoot);

        // when
        ProcessResult result = runUnchecked(environment, "bash", "install.sh", "--dry-run", "--no-onboard");

        // then
        assertThat(result.exitCode()).as(result.output()).isEqualTo(2);
        assertThat(result.output())
                .contains(
                        "Recommended MicroOS data root must not be writable by group or other: " + userRoot,
                        "install -d -m 0750 -o 'micro-user' -g 'micro-group' '" + userRoot + "'")
                .doesNotContain("WOULD clone or update:", "Installing Symphony...");
    }

    @Test
    void posixInstallerCreatesMicroOsVarRootWhenDefaultPromptIsAccepted() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        assumeTrue(commandExists("git"));
        assumeTrue(commandExists("script"));
        Path installScript = Path.of("install.sh").toAbsolutePath();
        Path sourceRepository = createSourceRepository(temporaryDirectory);
        Path fakeBin = createFakeToolchain(temporaryDirectory);
        Path home = temporaryDirectory.resolve("microos-interactive-home");
        Path varPath = temporaryDirectory.resolve("microos-interactive-var");
        Path usersRoot = temporaryDirectory.resolve("microos-interactive-users");
        Path userRoot = usersRoot.resolve("micro-user");
        Path fakeLog = temporaryDirectory.resolve("microos-interactive.log");
        Files.createDirectories(home);
        Files.createDirectories(varPath);
        writeExecutable(
                fakeBin.resolve("sudo"),
                """
                #!/usr/bin/env bash
                set -euo pipefail
                echo "sudo $*" >> "${SYMPHONY_FAKE_LOG:?}"
                "$@"
                """);
        writeExecutable(
                fakeBin.resolve("install"),
                """
                #!/usr/bin/env bash
                set -euo pipefail
                directory="${@: -1}"
                echo "install $*" >> "${SYMPHONY_FAKE_LOG:?}"
                mkdir -p "$directory"
                """);
        Map<String, String> environment = new LinkedHashMap<>(microOsLayoutEnvironment(home, varPath, usersRoot));
        environment.put("PATH", fakeBin + File.pathSeparator + System.getenv("PATH"));
        environment.put("SYMPHONY_TRELLO_REPO_URL", sourceRepository.toUri().toString());
        environment.put("SYMPHONY_TRELLO_REF", "main");
        environment.put("SYMPHONY_FAKE_LOG", fakeLog.toString());
        environment.put("SYMPHONY_TRELLO_TEST_EUID", "1000");

        // when
        ProcessResult result = runWithPseudoTerminal(
                environment, "\n", "bash " + shellQuote(installScript.toString()) + " --no-onboard");

        // then
        result.assertSuccess();
        assertThat(result.output())
                .contains(
                        "Create command:",
                        "install -d -m 0750 -o 'micro-user' -g 'micro-group' '" + userRoot + "'",
                        "Create this directory now? [Y/n]");
        assertThat(result.output().indexOf("install -d -m 0750"))
                .isLessThan(result.output().indexOf("Create this directory now? [Y/n]"));
        assertThat(userRoot).isDirectory();
        assertThat(fakeLog)
                .content(StandardCharsets.UTF_8)
                .contains(
                        "sudo install -d -m 0750 -o micro-user -g micro-group " + userRoot,
                        "install -d -m 0750 -o micro-user -g micro-group " + userRoot);
        assertThat(installContext(userRoot.resolve("config")))
                .content(StandardCharsets.UTF_8)
                .contains("layout_mode=microos-var", "microos_var_root=" + userRoot, "created_microos_var_root=true");
    }

    @Test
    void posixInstallWithCustomMicroOsConfigWritesRootContextAndUninstallCleansRoot() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        assumeTrue(commandExists("git"));
        assumeTrue(commandExists("script"));
        Path installScript = Path.of("install.sh").toAbsolutePath();
        Path sourceRepository = createSourceRepository(temporaryDirectory);
        Path fakeBin = createFakeToolchain(temporaryDirectory);
        Path home = temporaryDirectory.resolve("microos-custom-config-home");
        Path varPath = temporaryDirectory.resolve("microos-custom-config-var");
        Path usersRoot = temporaryDirectory.resolve("microos-custom-config-users");
        Path userRoot = usersRoot.resolve("micro-user");
        Path configDirectory = temporaryDirectory.resolve("microos-custom-config");
        Path fakeLog = temporaryDirectory.resolve("microos-custom-config.log");
        Files.createDirectories(home);
        Files.createDirectories(varPath);
        writeExecutable(
                fakeBin.resolve("sudo"),
                """
                #!/usr/bin/env bash
                set -euo pipefail
                echo "sudo $*" >> "${SYMPHONY_FAKE_LOG:?}"
                "$@"
                """);
        writeExecutable(
                fakeBin.resolve("install"),
                """
                #!/usr/bin/env bash
                set -euo pipefail
                directory="${@: -1}"
                echo "install $*" >> "${SYMPHONY_FAKE_LOG:?}"
                mkdir -p "$directory"
                """);
        Map<String, String> environment = new LinkedHashMap<>(microOsLayoutEnvironment(home, varPath, usersRoot));
        environment.put("PATH", fakeBin + File.pathSeparator + System.getenv("PATH"));
        environment.put("SYMPHONY_TRELLO_CONFIG_DIR", configDirectory.toString());
        environment.put("SYMPHONY_TRELLO_REPO_URL", sourceRepository.toUri().toString());
        environment.put("SYMPHONY_TRELLO_REF", "main");
        environment.put("SYMPHONY_FAKE_LOG", fakeLog.toString());
        environment.put("SYMPHONY_TRELLO_TEST_EUID", "1000");

        // when
        ProcessResult install = runWithPseudoTerminal(
                environment, "\n", "bash " + shellQuote(installScript.toString()) + " --no-onboard");

        // then
        install.assertSuccess();
        assertThat(configDirectory.resolve("install-context.properties"))
                .content(StandardCharsets.UTF_8)
                .contains(
                        "layout_mode=microos-var",
                        "config_dir=" + configDirectory,
                        "microos_var_root=" + userRoot,
                        "created_microos_var_root=true");

        // when
        ProcessResult uninstall =
                run(environment, "bash", "uninstall.sh", "--yes", "--yes-local-data", "--remove-all-local-data");

        // then
        uninstall.assertSuccess();
        assertThat(uninstall.output()).contains("REMOVE  " + userRoot);
        assertThat(userRoot).doesNotExist();
        assertThat(configDirectory).doesNotExist();
    }

    @Test
    void posixInstallerAllowsMicroOsHomeSymlinkedIntoVar() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        Path varPath = temporaryDirectory.resolve("symlink-var");
        Path realHome = varPath.resolve("userhomes/live/micro-user");
        Path symlinkedHome = temporaryDirectory.resolve("symlink-home");
        Path usersRoot = varPath.resolve("lib/symphony-trello/users");
        Files.createDirectories(realHome);
        Files.createDirectories(usersRoot);
        Files.createSymbolicLink(symlinkedHome, realHome);

        // when
        ProcessResult result = run(
                microOsLayoutEnvironment(symlinkedHome, varPath, usersRoot),
                "bash",
                "install.sh",
                "--dry-run",
                "--no-onboard");

        // then
        result.assertSuccess();
        assertThat(result.output())
                .contains(
                        "Install: " + symlinkedHome.resolve(".local/share/symphony-trello/app"),
                        "Config: " + symlinkedHome.resolve(".config/symphony-trello"),
                        "State/logs: " + symlinkedHome.resolve(".local/state/symphony-trello"))
                .doesNotContain("must resolve inside the user home", "WOULD create MicroOS data root");
    }

    @Test
    void posixUninstallerUsesMicroOsVarLayoutFallbackWithoutInstallContext() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        Path home = temporaryDirectory.resolve("microos-uninstall-home");
        Path varPath = temporaryDirectory.resolve("microos-uninstall-var");
        Path usersRoot = temporaryDirectory.resolve("microos-uninstall-users");
        Files.createDirectories(home);
        Files.createDirectories(varPath);
        Path userRoot = usersRoot.resolve("micro-user");

        // when
        ProcessResult result =
                run(microOsLayoutEnvironment(home, varPath, usersRoot), "bash", "uninstall.sh", "--dry-run");

        // then
        result.assertSuccess();
        assertThat(result.output())
                .contains(
                        "APP FILES       " + userRoot.resolve("data/app"),
                        "CONFIG          " + userRoot.resolve("config"),
                        "WORKSPACES      " + userRoot.resolve("workspaces"),
                        "STATE/LOGS      " + userRoot.resolve("state"));
    }

    @Test
    void posixInstallWritesContextAndUninstallUsesItWithoutCustomPaths() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        assumeTrue(commandExists("git"));
        Path sourceRepository = createSourceRepository(temporaryDirectory);
        Path fakeBin = createFakeToolchain(temporaryDirectory);
        Path home = temporaryDirectory.resolve("context-home");
        Path varPath = temporaryDirectory.resolve("context-var");
        Path usersRoot = temporaryDirectory.resolve("context-users");
        Path userRoot = usersRoot.resolve("micro-user");
        Path binDirectory = home.resolve(".local/bin");
        Path fakeLog = temporaryDirectory.resolve("context.log");
        Files.createDirectories(home);
        Files.createDirectories(varPath);
        Files.createDirectories(userRoot);
        Map<String, String> environment = new LinkedHashMap<>(microOsLayoutEnvironment(home, varPath, usersRoot));
        environment.put("PATH", fakeBin + File.pathSeparator + System.getenv("PATH"));
        environment.put("SYMPHONY_TRELLO_REPO_URL", sourceRepository.toUri().toString());
        environment.put("SYMPHONY_TRELLO_REF", "main");
        environment.put("SYMPHONY_FAKE_LOG", fakeLog.toString());

        // when
        ProcessResult install = run(environment, "bash", "install.sh", "--no-onboard");
        ProcessResult uninstall = run(environment, "bash", "uninstall.sh", "--yes");

        // then
        install.assertSuccess();
        uninstall.assertSuccess();
        assertThat(userRoot.resolve("config/install-context.properties"))
                .content(StandardCharsets.UTF_8)
                .contains(
                        "install_format_version=2",
                        "layout_mode=microos-var",
                        "app_dir=" + userRoot.resolve("data/app"),
                        "config_dir=" + userRoot.resolve("config"),
                        "workspace_root=" + userRoot.resolve("workspaces"),
                        "state_home=" + userRoot.resolve("state"),
                        "cache_dir=" + userRoot.resolve("cache"),
                        "bin_dir=" + binDirectory,
                        "microos_var_root=" + userRoot,
                        "codex_npm_prefix=" + userRoot.resolve("cache/npm"));
        assertThat(userRoot.resolve("state/install-context.properties"))
                .content(StandardCharsets.UTF_8)
                .contains("layout_mode=microos-var", "created_microos_var_root=false");
        assertThat(userRoot.resolve("data/app")).doesNotExist();
        assertThat(binDirectory.resolve("symphony-trello")).doesNotExist();
        assertThat(userRoot.resolve("config")).isDirectory();
        assertThat(userRoot.resolve("workspaces")).isDirectory();
        assertThat(userRoot.resolve("state")).isDirectory();
    }

    @Test
    void posixInstallWritesDiscoverableContextForCustomHome() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        assumeTrue(commandExists("git"));
        Path sourceRepository = createSourceRepository(temporaryDirectory);
        Path fakeBin = createFakeToolchain(temporaryDirectory);
        Path home = temporaryDirectory.resolve("custom-context-home");
        Path symphonyHome = temporaryDirectory.resolve("custom-context-symphony-home");
        Path fakeLog = temporaryDirectory.resolve("custom-context.log");
        Files.createDirectories(home);
        Map<String, String> installEnvironment =
                installEnvironmentForCustomHome(sourceRepository, fakeBin, home, symphonyHome, fakeLog);

        // when
        ProcessResult install = run(installEnvironment, "bash", "install.sh", "--no-onboard");
        ProcessResult uninstall = run(Map.of("HOME", home.toString()), "bash", "uninstall.sh", "--dry-run", "--yes");

        // then
        install.assertSuccess();
        uninstall.assertSuccess();
        assertThat(home.resolve(".local/state/symphony-trello/install-context.properties"))
                .content(StandardCharsets.UTF_8)
                .contains(
                        "layout_mode=custom-env",
                        "app_dir=" + symphonyHome.resolve("app"),
                        "config_dir=" + symphonyHome.resolve("config"),
                        "workspace_root=" + symphonyHome.resolve("workspaces"),
                        "state_home=" + symphonyHome.resolve("state"));
        assertThat(symphonyHome.resolve("state/install-context.properties"))
                .content(StandardCharsets.UTF_8)
                .contains("layout_mode=custom-env", "app_dir=" + symphonyHome.resolve("app"));
        assertThat(uninstall.output())
                .contains(
                        "App checkout: " + symphonyHome.resolve("app"),
                        "CONFIG          " + symphonyHome.resolve("config"),
                        "WORKSPACES      " + symphonyHome.resolve("workspaces"),
                        "STATE/LOGS      " + symphonyHome.resolve("state"))
                .doesNotContain("App checkout: " + home.resolve(".local/share/symphony-trello/app"));
    }

    @Test
    void posixScriptsUseSymphonyHomeContextAndLegacyAutostartSnapshot() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        Path home = temporaryDirectory.resolve("legacy-context-home");
        Path symphonyHome = temporaryDirectory.resolve("legacy-context-symphony-home");
        Path app = symphonyHome.resolve("app");
        Path config = symphonyHome.resolve("config");
        Path workspaces = symphonyHome.resolve("workspaces");
        Path state = symphonyHome.resolve("state");
        Path cache = symphonyHome.resolve("cache");
        Path binDirectory = temporaryDirectory.resolve("legacy-context-bin");
        Path command = binDirectory.resolve("symphony-trello");
        Path xdgConfigHome = temporaryDirectory.resolve("legacy-context-xdg-config");
        Path fakeTools = temporaryDirectory.resolve("legacy-context-tools");
        Path fakeLog = temporaryDirectory.resolve("legacy-context-tools.log");
        Path legacyService = home.resolve(".config/systemd/user/symphony-trello.service");
        Path legacyAutostartEnv = home.resolve(".config/symphony-trello/autostart.env");
        Files.createDirectories(app);
        Files.createDirectories(config);
        Files.createDirectories(workspaces);
        Files.createDirectories(state);
        Files.createDirectories(cache);
        Files.createDirectories(binDirectory);
        Files.createDirectories(fakeTools);
        Files.createDirectories(legacyService.getParent());
        Files.createDirectories(legacyAutostartEnv.getParent());
        Files.writeString(app.resolve(".symphony-trello-install"), "marker");
        Files.writeString(command, "launcher\n");
        Files.writeString(legacyService, "legacy service\n");
        Files.writeString(legacyAutostartEnv, "TRELLO_API_KEY=\"legacy\"\n");
        writeExecutable(
                fakeTools.resolve("systemctl"),
                """
                #!/usr/bin/env bash
                set -euo pipefail
                echo "systemctl $*" >> "${SYMPHONY_FAKE_LOG:?}"
                """);
        Files.writeString(
                state.resolve("install-context.properties"),
                """
                installer=install.sh
                install_format_version=1
                app_dir=%s
                config_dir=%s
                workspace_root=%s
                state_home=%s
                cache_dir=%s
                bin_dir=%s
                codex_npm_prefix=%s
                """
                        .formatted(app, config, workspaces, state, cache, binDirectory, cache.resolve("npm")));
        Map<String, String> environment = Map.of(
                "HOME",
                home.toString(),
                "SYMPHONY_HOME",
                symphonyHome.toString(),
                "SYMPHONY_TRELLO_CONFIG_DIR",
                config.toString(),
                "XDG_CONFIG_HOME",
                xdgConfigHome.toString(),
                "PATH",
                fakeTools + File.pathSeparator + System.getenv("PATH"),
                "SYMPHONY_FAKE_LOG",
                fakeLog.toString());

        // when
        ProcessResult installDryRun = run(environment, "bash", "install.sh", "--dry-run", "--no-onboard");
        ProcessResult uninstall = run(environment, "bash", "uninstall.sh", "--yes");

        // then
        installDryRun.assertSuccess();
        uninstall.assertSuccess();
        assertThat(installDryRun.output()).contains("Command: " + command);
        assertThat(uninstall.output())
                .contains(
                        "Installed CLI: " + command,
                        "USER SERVICE    " + legacyService,
                        "STOP  user systemd service: symphony-trello.service",
                        "REMOVE  " + legacyService,
                        "REMOVE  " + command,
                        "REMOVE  " + legacyAutostartEnv);
        assertThat(command).doesNotExist();
        assertThat(legacyService).doesNotExist();
        assertThat(legacyAutostartEnv).doesNotExist();
        assertThat(fakeLog)
                .content(StandardCharsets.UTF_8)
                .contains("systemctl --user disable --now symphony-trello.service", "systemctl --user daemon-reload");
    }

    @Test
    void posixUninstallerRemovesRecordedAutostartSnapshotWhenConfigIsOverridden() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        Path home = temporaryDirectory.resolve("recorded-autostart-home");
        Path app = temporaryDirectory.resolve("recorded-autostart-app");
        Path oldConfig = temporaryDirectory.resolve("recorded-autostart-old-config");
        Path newConfig = temporaryDirectory.resolve("recorded-autostart-new-config");
        Path workspaces = temporaryDirectory.resolve("recorded-autostart-workspaces");
        Path state = home.resolve(".local/state/symphony-trello");
        Path cache = temporaryDirectory.resolve("recorded-autostart-cache");
        Path binDirectory = temporaryDirectory.resolve("recorded-autostart-bin");
        Path recordedAutostartEnv = oldConfig.resolve("autostart.env");
        Files.createDirectories(home.resolve(".config/symphony-trello"));
        Files.createDirectories(app);
        Files.createDirectories(oldConfig);
        Files.createDirectories(newConfig);
        Files.createDirectories(workspaces);
        Files.createDirectories(state);
        Files.createDirectories(cache);
        Files.createDirectories(binDirectory);
        Files.writeString(app.resolve(".symphony-trello-install"), "marker");
        Files.writeString(recordedAutostartEnv, "TRELLO_API_KEY=\"recorded\"\n");
        Files.writeString(
                state.resolve("install-context.properties"),
                """
                installer=install.sh
                install_format_version=2
                layout_mode=custom-env
                app_dir=%s
                config_dir=%s
                workspace_root=%s
                state_home=%s
                cache_dir=%s
                bin_dir=%s
                autostart_env_path=%s
                codex_npm_prefix=%s
                created_microos_var_root=false
                """
                        .formatted(
                                app,
                                oldConfig,
                                workspaces,
                                state,
                                cache,
                                binDirectory,
                                recordedAutostartEnv,
                                cache.resolve("npm")));
        Map<String, String> environment =
                Map.of("HOME", home.toString(), "SYMPHONY_TRELLO_CONFIG_DIR", newConfig.toString());

        // when
        ProcessResult result = run(environment, "bash", "uninstall.sh", "--yes");

        // then
        result.assertSuccess();
        assertThat(result.output()).contains("REMOVE  " + recordedAutostartEnv);
        assertThat(recordedAutostartEnv).doesNotExist();
        assertThat(newConfig.resolve("autostart.env")).doesNotExist();
    }

    @Test
    void posixInstallerPreservesRecordedAutostartSnapshotWhenConfigIsOverridden() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        assumeTrue(commandExists("git"));
        Path installScript = Path.of("install.sh").toAbsolutePath();
        Path sourceRepository = createSourceRepository(temporaryDirectory);
        Path fakeBin = createFakeToolchain(temporaryDirectory);
        Path home = temporaryDirectory.resolve("install-recorded-autostart-home");
        Path app = temporaryDirectory.resolve("install-recorded-autostart-app");
        Path oldConfig = temporaryDirectory.resolve("install-recorded-autostart-old-config");
        Path newConfig = temporaryDirectory.resolve("install-recorded-autostart-new-config");
        Path workspaces = temporaryDirectory.resolve("install-recorded-autostart-workspaces");
        Path state = home.resolve(".local/state/symphony-trello");
        Path cache = temporaryDirectory.resolve("install-recorded-autostart-cache");
        Path binDirectory = temporaryDirectory.resolve("install-recorded-autostart-bin");
        Path fakeLog = temporaryDirectory.resolve("install-recorded-autostart.log");
        Path recordedAutostartEnv = oldConfig.resolve("autostart.env");
        Files.createDirectories(home);
        Files.createDirectories(oldConfig);
        Files.createDirectories(newConfig);
        Files.createDirectories(state);
        Files.createDirectories(cache);
        Files.createDirectories(binDirectory);
        Files.writeString(recordedAutostartEnv, "TRELLO_API_KEY=\"recorded\"\n");
        Files.writeString(
                state.resolve(INSTALL_CONTEXT_PROPERTIES),
                """
                installer=install.sh
                install_format_version=2
                layout_mode=custom-flags
                app_dir=%s
                config_dir=%s
                workspace_root=%s
                state_home=%s
                cache_dir=%s
                bin_dir=%s
                autostart_env_path=%s
                codex_npm_prefix=%s
                created_microos_var_root=false
                """
                        .formatted(
                                app,
                                oldConfig,
                                workspaces,
                                state,
                                cache,
                                binDirectory,
                                recordedAutostartEnv,
                                cache.resolve("npm")));
        Map<String, String> environment = Map.of(
                "PATH",
                fakeBin + File.pathSeparator + System.getenv("PATH"),
                "HOME",
                home.toString(),
                "SYMPHONY_TRELLO_CONFIG_DIR",
                newConfig.toString(),
                "SYMPHONY_TRELLO_REPO_URL",
                sourceRepository.toUri().toString(),
                "SYMPHONY_TRELLO_REF",
                "main",
                "SYMPHONY_FAKE_LOG",
                fakeLog.toString());

        // when
        ProcessResult result = run(
                environment,
                "bash",
                installScript.toString(),
                "--no-onboard",
                "--no-update-path",
                "--bin-dir",
                binDirectory.toString());

        // then
        result.assertSuccess();
        assertThat(recordedAutostartEnv).exists();
        assertThat(newConfig.resolve("autostart.env")).doesNotExist();
        assertThat(state.resolve(INSTALL_CONTEXT_PROPERTIES))
                .content(StandardCharsets.UTF_8)
                .contains("config_dir=" + newConfig, "autostart_env_path=" + recordedAutostartEnv)
                .doesNotContain("autostart_env_path=" + newConfig.resolve("autostart.env"));
    }

    @Test
    void posixInstallSkipsUnsafeStableContextPathThroughUntrustedSymlink() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        assumeTrue(commandExists("git"));
        Path sourceRepository = createSourceRepository(temporaryDirectory);
        Path fakeBin = createFakeToolchain(temporaryDirectory);
        Path home = temporaryDirectory.resolve("unsafe-stable-context-home");
        Path outside = temporaryDirectory.resolve("unsafe-stable-context-outside");
        Path symphonyHome = temporaryDirectory.resolve("unsafe-stable-context-symphony-home");
        Path configDirectory = temporaryDirectory.resolve("unsafe-stable-context-config");
        Path fakeLog = temporaryDirectory.resolve("unsafe-stable-context.log");
        Files.createDirectories(home);
        Files.createDirectories(outside);
        Files.createDirectories(configDirectory);
        Files.createSymbolicLink(home.resolve(".config"), outside);
        Map<String, String> environment = new LinkedHashMap<>(
                installEnvironmentForCustomHome(sourceRepository, fakeBin, home, symphonyHome, fakeLog));
        environment.put("SYMPHONY_TRELLO_CONFIG_DIR", configDirectory.toString());

        // when
        ProcessResult install = run(environment, "bash", "install.sh", "--no-onboard");

        // then
        install.assertSuccess();
        assertThat(configDirectory.resolve("install-context.properties"))
                .content(StandardCharsets.UTF_8)
                .contains("config_dir=" + configDirectory);
        assertThat(home.resolve(".local/state/symphony-trello/install-context.properties"))
                .exists();
        assertThat(outside.resolve("symphony-trello/install-context.properties"))
                .doesNotExist();
    }

    @Test
    void posixInstallContextReplayAllowsDedicatedAppPathDirectlyUnderHome() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        Path home = temporaryDirectory.resolve("context-home-app-home");
        Path app = home.resolve("symphony-trello");
        Path dataHome = home.resolve(".local/share/symphony-trello");
        Path stateHome = home.resolve(".local/state/symphony-trello");
        Files.createDirectories(stateHome);
        Files.writeString(
                stateHome.resolve("install-context.properties"),
                """
                installer=install.sh
                install_format_version=1
                install_source=release-archive
                app_version=1.1.0
                app_dir=%s
                config_dir=%s
                workspace_root=%s
                state_home=%s
                bin_dir=%s
                codex_npm_prefix=%s
                """
                        .formatted(
                                app,
                                dataHome.resolve("config"),
                                dataHome.resolve("workspaces"),
                                dataHome.resolve("state"),
                                home.resolve(".local/bin"),
                                dataHome.resolve("npm")));

        // when
        ProcessResult install = run(Map.of("HOME", home.toString()), "bash", "install.sh", "--dry-run", "--no-onboard");
        ProcessResult uninstall = run(Map.of("HOME", home.toString()), "bash", "uninstall.sh", "--dry-run", "--yes");

        // then
        install.assertSuccess();
        uninstall.assertSuccess();
        assertThat(install.output())
                .contains("Install: " + app)
                .doesNotContain("--prefix must point to a dedicated app checkout directory");
        assertThat(uninstall.output())
                .contains("App checkout: " + app)
                .doesNotContain("--prefix must point to a dedicated app checkout directory");
    }

    @Test
    void posixInstallContextReplayAllowsSymlinkedExplicitAppParent() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        Path home = temporaryDirectory.resolve("context-symlink-app-home");
        Path outside = temporaryDirectory.resolve("context-symlink-app-outside");
        Path symlink = temporaryDirectory.resolve("context-symlink-app-link");
        Path app = symlink.resolve("app");
        Path commandDirectory = symlink.resolve("bin");
        Path config = temporaryDirectory.resolve("context-symlink-app-config");
        Path workspaces = temporaryDirectory.resolve("context-symlink-app-workspaces");
        Path state = home.resolve(".local/state/symphony-trello");
        Path cache = temporaryDirectory.resolve("context-symlink-app-cache");
        Files.createDirectories(state);
        Files.createDirectories(outside.resolve("app"));
        Files.createDirectories(outside.resolve("bin"));
        Files.createDirectories(config);
        Files.createDirectories(workspaces);
        Files.createDirectories(cache);
        Files.writeString(outside.resolve("app/.symphony-trello-install"), "marker");
        Files.createSymbolicLink(symlink, outside);
        Files.writeString(
                state.resolve("install-context.properties"),
                """
                installer=install.sh
                install_format_version=2
                layout_mode=custom-flags
                app_dir=%s
                config_dir=%s
                workspace_root=%s
                state_home=%s
                cache_dir=%s
                bin_dir=%s
                codex_npm_prefix=%s
                created_microos_var_root=false
                """
                        .formatted(app, config, workspaces, state, cache, commandDirectory, cache.resolve("npm")));

        // when
        ProcessResult install = run(Map.of("HOME", home.toString()), "bash", "install.sh", "--dry-run", "--no-onboard");
        ProcessResult uninstall = run(Map.of("HOME", home.toString()), "bash", "uninstall.sh", "--dry-run", "--yes");

        // then
        install.assertSuccess();
        uninstall.assertSuccess();
        assertThat(install.output())
                .contains(
                        "Install: " + app,
                        "Command: " + commandDirectory.resolve("symphony-trello"),
                        "Config: " + config);
        assertThat(uninstall.output())
                .contains(
                        "App checkout: " + app,
                        "Installed CLI: " + commandDirectory.resolve("symphony-trello"),
                        "CONFIG          " + config);
    }

    @Test
    void posixScriptsAllowSymlinkedSymphonyHomeOverride() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        Path home = temporaryDirectory.resolve("symlink-symphony-home-user");
        Path outside = temporaryDirectory.resolve("symlink-symphony-home-target");
        Path symlink = temporaryDirectory.resolve("symlink-symphony-home-link");
        Path symphonyHome = symlink.resolve("symphony");
        Files.createDirectories(home);
        Files.createDirectories(outside);
        Files.createSymbolicLink(symlink, outside);
        Map<String, String> environment = Map.of("HOME", home.toString(), "SYMPHONY_HOME", symphonyHome.toString());

        // when
        ProcessResult install = run(environment, "bash", "install.sh", "--dry-run", "--no-onboard");
        ProcessResult uninstall = run(environment, "bash", "uninstall.sh", "--dry-run", "--yes");

        // then
        install.assertSuccess();
        uninstall.assertSuccess();
        assertThat(install.output())
                .contains(
                        "Install: " + symphonyHome.resolve("app"),
                        "Config: " + symphonyHome.resolve("config"),
                        "Workspaces: " + symphonyHome.resolve("workspaces"),
                        "State/logs: " + symphonyHome.resolve("state"))
                .doesNotContain("must resolve inside the user home or Symphony MicroOS data root");
        assertThat(uninstall.output())
                .contains(
                        "App checkout: " + symphonyHome.resolve("app"),
                        "CONFIG          " + symphonyHome.resolve("config"),
                        "WORKSPACES      " + symphonyHome.resolve("workspaces"),
                        "STATE/LOGS      " + symphonyHome.resolve("state"))
                .doesNotContain("must resolve inside the user home or Symphony MicroOS data root");
    }

    @Test
    void posixInstallContextReplayKeepsDataPathsWhenPrefixIsExplicitlySupplied() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        Path home = temporaryDirectory.resolve("context-explicit-prefix-home");
        Path app = temporaryDirectory.resolve("context-explicit-prefix-app");
        Path dataHome = home.resolve(".local/share/symphony-trello");
        Path stateHome = dataHome.resolve("state");
        Path binDirectory = home.resolve(".local/bin");
        Files.createDirectories(stateHome);
        Files.writeString(
                stateHome.resolve("install-context.properties"),
                """
                installer=install.sh
                install_format_version=1
                install_source=release-archive
                app_version=1.1.0
                app_dir=%s
                config_dir=%s
                workspace_root=%s
                state_home=%s
                bin_dir=%s
                codex_npm_prefix=%s
                """
                        .formatted(
                                app,
                                dataHome.resolve("config"),
                                dataHome.resolve("workspaces"),
                                dataHome.resolve("state"),
                                binDirectory,
                                dataHome.resolve("npm")));

        // when
        ProcessResult result = run(
                Map.of("HOME", home.toString()),
                "bash",
                "install.sh",
                "--dry-run",
                "--no-onboard",
                "--prefix",
                app.toString());

        // then
        result.assertSuccess();
        assertThat(result.output())
                .contains(
                        "Install: " + app,
                        "Config: " + dataHome.resolve("config"),
                        "Workspaces: " + dataHome.resolve("workspaces"),
                        "State/logs: " + dataHome.resolve("state"),
                        "Command: " + binDirectory.resolve("symphony-trello"))
                .doesNotContain(
                        "Config: " + home.resolve(".config/symphony-trello"),
                        "State/logs: " + home.resolve(".local/state/symphony-trello"));
    }

    @Test
    void posixScriptsReplayMatchingInstallContextWhenSymphonyHomeIsExplicitlySupplied() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        Path home = temporaryDirectory.resolve("context-explicit-home-env-home");
        Path symphonyHome = temporaryDirectory.resolve("context-explicit-home-env-symphony");
        Path app = symphonyHome.resolve("app");
        Path config = temporaryDirectory.resolve("context-explicit-home-env-config");
        Path workspaces = temporaryDirectory.resolve("context-explicit-home-env-workspaces");
        Path state = temporaryDirectory.resolve("context-explicit-home-env-state");
        Path cache = temporaryDirectory.resolve("context-explicit-home-env-cache");
        Path bin = temporaryDirectory.resolve("context-explicit-home-env-bin");
        Files.createDirectories(home.resolve(".config/symphony-trello"));
        Files.createDirectories(app);
        Files.createDirectories(config);
        Files.createDirectories(workspaces);
        Files.createDirectories(state);
        Files.createDirectories(cache.resolve("npm"));
        Files.createDirectories(bin);
        Files.writeString(app.resolve(".symphony-trello-install"), "installer-managed\n");
        Files.writeString(
                home.resolve(".config/symphony-trello/install-context.properties"),
                """
                installer=install.sh
                install_format_version=2
                layout_mode=custom-env
                app_dir=%s
                config_dir=%s
                workspace_root=%s
                state_home=%s
                cache_dir=%s
                bin_dir=%s
                codex_npm_prefix=%s
                created_microos_var_root=false
                """
                        .formatted(app, config, workspaces, state, cache, bin, cache.resolve("npm")));
        Map<String, String> environment = Map.of("HOME", home.toString(), "SYMPHONY_HOME", symphonyHome.toString());

        // when
        ProcessResult install = run(environment, "bash", "install.sh", "--dry-run", "--no-onboard");
        ProcessResult uninstall = run(environment, "bash", "uninstall.sh", "--dry-run", "--yes");

        // then
        install.assertSuccess();
        uninstall.assertSuccess();
        assertThat(install.output())
                .contains(
                        "Install: " + app,
                        "Config: " + config,
                        "Workspaces: " + workspaces,
                        "State/logs: " + state,
                        "Command: " + bin.resolve("symphony-trello"))
                .doesNotContain("Config: " + symphonyHome.resolve("config"));
        assertThat(uninstall.output())
                .contains(
                        "App checkout: " + app,
                        "Installed CLI: " + bin.resolve("symphony-trello"),
                        "CONFIG          " + config,
                        "WORKSPACES      " + workspaces,
                        "STATE/LOGS      " + state);
    }

    @Test
    void posixUninstallerTreatsContextDataPathsAsKnownWhenPrefixIsExplicitlySupplied() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        Path home = temporaryDirectory.resolve("uninstall-context-explicit-prefix-home");
        Path app = temporaryDirectory.resolve("uninstall-context-explicit-prefix-app");
        Path dataHome = home.resolve(".local/share/symphony-trello");
        Path config = dataHome.resolve("config");
        Path workspaces = dataHome.resolve("workspaces");
        Path state = dataHome.resolve("state");
        Path cache = dataHome.resolve("cache");
        Files.createDirectories(app);
        Files.createDirectories(config);
        Files.createDirectories(workspaces);
        Files.createDirectories(state);
        Files.createDirectories(cache.resolve("npm"));
        Files.writeString(app.resolve(".symphony-trello-install"), "installer-managed\n");
        Files.writeString(
                state.resolve("install-context.properties"),
                """
                installer=install.sh
                install_format_version=2
                layout_mode=custom-env
                app_dir=%s
                config_dir=%s
                workspace_root=%s
                state_home=%s
                cache_dir=%s
                bin_dir=%s
                codex_npm_prefix=%s
                created_microos_var_root=false
                """
                        .formatted(
                                app,
                                config,
                                workspaces,
                                state,
                                cache,
                                home.resolve(".local/bin"),
                                cache.resolve("npm")));

        // when
        ProcessResult result = run(
                Map.of("HOME", home.toString()),
                "bash",
                "uninstall.sh",
                "--dry-run",
                "--yes",
                "--yes-local-data",
                "--remove-all-local-data",
                "--prefix",
                app.toString());

        // then
        result.assertSuccess();
        assertThat(result.output())
                .contains("CONFIG          " + config, "WORKSPACES      " + workspaces, "STATE/LOGS      " + state)
                .doesNotContain(
                        "Refusing to remove default local data while uninstalling a custom --prefix.",
                        "CONFIG: set SYMPHONY_HOME or SYMPHONY_TRELLO_CONFIG_DIR",
                        "WORKSPACES: set SYMPHONY_HOME or SYMPHONY_TRELLO_WORKSPACE_ROOT",
                        "STATE/LOGS: set SYMPHONY_HOME or SYMPHONY_TRELLO_STATE_HOME");
    }

    @Test
    void posixUninstallerIgnoresInstallContextForDifferentExplicitPrefix() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        Path home = temporaryDirectory.resolve("uninstall-stale-context-home");
        Path contextApp = temporaryDirectory.resolve("uninstall-stale-context-app");
        Path explicitApp = temporaryDirectory.resolve("uninstall-stale-context-explicit-app");
        Path dataHome = home.resolve(".local/share/symphony-trello");
        Path config = dataHome.resolve("config");
        Path workspaces = dataHome.resolve("workspaces");
        Path state = dataHome.resolve("state");
        Path cache = dataHome.resolve("cache");
        Path bin = temporaryDirectory.resolve("uninstall-stale-context-bin");
        Files.createDirectories(contextApp);
        Files.createDirectories(explicitApp);
        Files.createDirectories(config);
        Files.createDirectories(workspaces);
        Files.createDirectories(state);
        Files.createDirectories(cache.resolve("npm"));
        Files.createDirectories(bin);
        Files.writeString(explicitApp.resolve(".symphony-trello-install"), "installer-managed\n");
        Files.writeString(config.resolve(".env"), "TRELLO_API_KEY=secret\n");
        Files.writeString(workspaces.resolve("card.txt"), "work\n");
        Files.writeString(state.resolve("worker.pid"), "123\n");
        Files.writeString(
                state.resolve("install-context.properties"),
                """
                installer=install.sh
                install_format_version=2
                layout_mode=custom-env
                app_dir=%s
                config_dir=%s
                workspace_root=%s
                state_home=%s
                cache_dir=%s
                bin_dir=%s
                codex_npm_prefix=%s
                created_microos_var_root=false
                """
                        .formatted(
                                contextApp,
                                config,
                                workspaces,
                                state,
                                cache,
                                home.resolve(".local/bin"),
                                cache.resolve("npm")));

        // when
        ProcessResult result = run(
                Map.of("HOME", home.toString()),
                "bash",
                "uninstall.sh",
                "--yes",
                "--yes-local-data",
                "--remove-all-local-data",
                "--prefix",
                explicitApp.toString(),
                "--bin-dir",
                bin.toString());

        // then
        assertThat(result.exitCode()).as(result.output()).isEqualTo(2);
        assertThat(result.output())
                .contains("Refusing to remove default local data while uninstalling a custom --prefix.")
                .doesNotContain(
                        "CONFIG          " + config, "WORKSPACES      " + workspaces, "STATE/LOGS      " + state);
        assertThat(config.resolve(".env")).exists();
        assertThat(workspaces.resolve("card.txt")).exists();
        assertThat(state.resolve("worker.pid")).exists();
        assertThat(explicitApp.resolve(".symphony-trello-install")).exists();
    }

    @MethodSource("posixBroadLocalDataRootOverrideScenarios")
    @ParameterizedTest(name = "{0}")
    void posixScriptsRejectBroadLocalDataRootOverrides(BroadLocalDataRootOverrideScenario scenario) throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        Path home = temporaryDirectory.resolve(scenario.slug() + "-home");
        Files.createDirectories(home);
        Map<String, String> environment = Map.of("HOME", home.toString(), scenario.environmentVariable(), "/etc");

        // when
        ProcessResult install = run(environment, "bash", "install.sh", "--dry-run", "--no-onboard");
        ProcessResult uninstall = run(
                environment, "bash", "uninstall.sh", "--dry-run", "--yes", "--yes-local-data", scenario.removeFlag());

        // then
        assertThat(install.exitCode()).as(install.output()).isEqualTo(2);
        assertThat(uninstall.exitCode()).as(uninstall.output()).isEqualTo(2);
        assertThat(install.output())
                .contains("Symphony config, workspace, and state paths must point to dedicated directories.")
                .doesNotContain("Install:");
        assertThat(uninstall.output())
                .contains("Symphony config, workspace, and state paths must point to dedicated directories.")
                .doesNotContain("Will remove if present:", "WOULD REMOVE  /etc");
    }

    private static Stream<BroadLocalDataRootOverrideScenario> posixBroadLocalDataRootOverrideScenarios() {
        return Stream.of(
                new BroadLocalDataRootOverrideScenario(
                        "config override", "broad-config", "SYMPHONY_TRELLO_CONFIG_DIR", "--remove-config"),
                new BroadLocalDataRootOverrideScenario(
                        "workspace override",
                        "broad-workspace",
                        "SYMPHONY_TRELLO_WORKSPACE_ROOT",
                        "--remove-workspaces"),
                new BroadLocalDataRootOverrideScenario(
                        "state override", "broad-state", "SYMPHONY_TRELLO_STATE_HOME", "--remove-state"));
    }

    private record BroadLocalDataRootOverrideScenario(
            String name, String slug, String environmentVariable, String removeFlag) {
        @Override
        public String toString() {
            return name;
        }
    }

    @Test
    void posixScriptsRejectCommandDirectoryLocalDataRootOverrides() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        Path home = temporaryDirectory.resolve("system-command-root-home");
        Path app = temporaryDirectory.resolve("system-command-root-app");
        Path bin = temporaryDirectory.resolve("system-command-root-bin");
        Files.createDirectories(home);
        Files.createDirectories(app);
        Files.createDirectories(bin);
        Files.writeString(app.resolve(".symphony-trello-install"), "marker");
        Map<String, String> environment = Map.of("HOME", home.toString(), "SYMPHONY_TRELLO_CONFIG_DIR", "/bin");

        // when
        ProcessResult install = runUnchecked(
                environment,
                "bash",
                "install.sh",
                "--dry-run",
                "--no-onboard",
                "--prefix",
                app.toString(),
                "--bin-dir",
                bin.toString());
        ProcessResult uninstall = runUnchecked(
                environment,
                "bash",
                "uninstall.sh",
                "--dry-run",
                "--yes",
                "--yes-local-data",
                "--remove-config",
                "--prefix",
                app.toString(),
                "--bin-dir",
                bin.toString());

        // then
        assertThat(install.exitCode()).as(install.output()).isEqualTo(2);
        assertThat(uninstall.exitCode()).as(uninstall.output()).isEqualTo(2);
        assertThat(install.output())
                .contains("Symphony config, workspace, and state paths must point to dedicated directories.")
                .doesNotContain("Dry run: no files changed.");
        assertThat(uninstall.output())
                .contains("Symphony config, workspace, and state paths must point to dedicated directories.")
                .doesNotContain("WOULD REMOVE  //bin", "WOULD REMOVE  /bin", "WOULD REMOVE  /usr/bin");
    }

    @Test
    void posixScriptsRejectDefaultLocalDataPathThroughUntrustedSymlink() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        Path home = temporaryDirectory.resolve("symlink-config-home");
        Path outside = temporaryDirectory.resolve("symlink-config-outside");
        Path app = home.resolve(".local/share/symphony-trello/app");
        Files.createDirectories(home.resolve(".local/share/symphony-trello"));
        Files.createDirectories(home.resolve(".local/state/symphony-trello"));
        Files.createDirectories(outside.resolve("symphony-trello"));
        Files.createDirectories(app);
        Files.writeString(app.resolve(".symphony-trello-install"), "marker");
        Files.writeString(outside.resolve("symphony-trello/.env"), "TRELLO_TOKEN=test\n");
        Files.createSymbolicLink(home.resolve(".config"), outside);
        Map<String, String> environment = Map.of("HOME", home.toString());

        // when
        ProcessResult install = runUnchecked(environment, "bash", "install.sh", "--dry-run", "--no-onboard");
        ProcessResult uninstall =
                runUnchecked(environment, "bash", "uninstall.sh", "--yes", "--yes-local-data", "--remove-config");

        // then
        assertThat(install.exitCode()).as(install.output()).isEqualTo(2);
        assertThat(uninstall.exitCode()).as(uninstall.output()).isEqualTo(2);
        assertThat(install.output())
                .contains(
                        "Symphony config, workspace, and state paths must resolve inside the user home or Symphony MicroOS data root.")
                .doesNotContain("Dry run: no files changed.");
        assertThat(uninstall.output())
                .contains(
                        "Symphony config, workspace, and state paths must resolve inside the user home or Symphony MicroOS data root.")
                .doesNotContain("REMOVE  " + outside.resolve("symphony-trello"));
        assertThat(outside.resolve("symphony-trello/.env")).exists();
    }

    @Test
    void posixScriptsRejectDefaultCachePathThroughUntrustedSymlink() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        Path home = temporaryDirectory.resolve("symlink-cache-home");
        Path outside = temporaryDirectory.resolve("symlink-cache-outside");
        Path app = home.resolve(".local/share/symphony-trello/app");
        Path outsideNpmPrefix = outside.resolve("symphony-trello/npm");
        Files.createDirectories(home.resolve(".local/share/symphony-trello"));
        Files.createDirectories(home.resolve(".config/symphony-trello"));
        Files.createDirectories(home.resolve(".local/state/symphony-trello"));
        Files.createDirectories(home.resolve(".local/bin"));
        Files.createDirectories(app);
        Files.createDirectories(outsideNpmPrefix.resolve("bin"));
        Files.writeString(app.resolve(".symphony-trello-install"), "marker");
        Files.writeString(outsideNpmPrefix.resolve("package.json"), "{\"private\":true}\n");
        Files.createSymbolicLink(home.resolve(".cache"), outside);
        Files.createSymbolicLink(
                home.resolve(".local/bin/codex"), home.resolve(".cache/symphony-trello/npm/bin/codex"));
        Map<String, String> environment = Map.of("HOME", home.toString());

        // when
        ProcessResult install = runUnchecked(environment, "bash", "install.sh", "--dry-run", "--no-onboard");
        ProcessResult uninstall = runUnchecked(environment, "bash", "uninstall.sh", "--yes");

        // then
        assertThat(install.exitCode()).as(install.output()).isEqualTo(2);
        assertThat(uninstall.exitCode()).as(uninstall.output()).isEqualTo(2);
        assertThat(install.output())
                .contains(
                        "Symphony cache and managed npm paths must resolve inside the user home or Symphony MicroOS data root.")
                .doesNotContain("Dry run: no files changed.");
        assertThat(uninstall.output())
                .contains(
                        "Symphony cache and managed npm paths must resolve inside the user home or Symphony MicroOS data root.")
                .doesNotContain("REMOVE  " + outsideNpmPrefix);
        assertThat(outsideNpmPrefix.resolve("package.json")).exists();
    }

    @Test
    void posixScriptsRejectDefaultCachePathInsidePreservedDataRoot() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        Path home = temporaryDirectory.resolve("cache-overlap-home");
        Path app = home.resolve(".local/share/symphony-trello/app");
        Path workspaceRoot = home.resolve(".local/share/symphony-trello/workspaces");
        Path managedNpm = workspaceRoot.resolve("symphony-trello/npm");
        Files.createDirectories(app);
        Files.createDirectories(home.resolve(".config/symphony-trello"));
        Files.createDirectories(home.resolve(".local/state/symphony-trello"));
        Files.createDirectories(managedNpm);
        Files.writeString(app.resolve(".symphony-trello-install"), "marker");
        Files.writeString(managedNpm.resolve("package.json"), "{\"private\":true}\n");
        Map<String, String> environment = Map.of("HOME", home.toString(), "XDG_CACHE_HOME", workspaceRoot.toString());

        // when
        ProcessResult install = runUnchecked(environment, "bash", "install.sh", "--dry-run", "--no-onboard");
        ProcessResult uninstall = runUnchecked(environment, "bash", "uninstall.sh", "--yes");

        // then
        assertDefaultCacheOverlapRejected(install, uninstall, managedNpm);
    }

    @Test
    void posixScriptsRejectDefaultCachePathInsideAppDirectory() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        Path home = temporaryDirectory.resolve("cache-app-overlap-home");
        Path app = home.resolve(".local/share/symphony-trello/app");
        Path managedNpm = app.resolve("symphony-trello/npm");
        Files.createDirectories(app);
        Files.createDirectories(home.resolve(".config/symphony-trello"));
        Files.createDirectories(home.resolve(".local/state/symphony-trello"));
        Files.createDirectories(managedNpm);
        Files.writeString(app.resolve(".symphony-trello-install"), "marker");
        Files.writeString(managedNpm.resolve("package.json"), "{\"private\":true}\n");
        Map<String, String> environment = Map.of("HOME", home.toString(), "XDG_CACHE_HOME", app.toString());

        // when
        ProcessResult install = runUnchecked(environment, "bash", "install.sh", "--dry-run", "--no-onboard");
        ProcessResult uninstall = runUnchecked(environment, "bash", "uninstall.sh", "--yes");

        // then
        assertDefaultCacheOverlapRejected(install, uninstall, managedNpm);
    }

    private static void assertDefaultCacheOverlapRejected(
            ProcessResult install, ProcessResult uninstall, Path managedNpm) {
        assertThat(install.exitCode()).as(install.output()).isEqualTo(2);
        assertThat(uninstall.exitCode()).as(uninstall.output()).isEqualTo(2);
        assertThat(install.output())
                .contains(
                        "Symphony cache and managed npm paths must not overlap Symphony app, config, workspace, or state directories.")
                .doesNotContain("Install:");
        assertThat(uninstall.output())
                .contains(
                        "Symphony cache and managed npm paths must not overlap Symphony app, config, workspace, or state directories.")
                .doesNotContain("REMOVE  " + managedNpm);
        assertThat(managedNpm.resolve("package.json")).exists();
    }

    @Test
    void posixUninstallerRefusesToRemoveAppContainingPreservedSymlinkedConfigPath() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        Path home = temporaryDirectory.resolve("app-symlink-config-home");
        Path symphonyHome = temporaryDirectory.resolve("app-symlink-config-symphony");
        Path app = symphonyHome.resolve("app");
        Path outsideConfig = temporaryDirectory.resolve("app-symlink-config-target");
        Path configSymlink = app.resolve("config");
        Path workspaceRoot = temporaryDirectory.resolve("app-symlink-config-workspaces");
        Path stateHome = temporaryDirectory.resolve("app-symlink-config-state");
        Path binDirectory = temporaryDirectory.resolve("app-symlink-config-bin");
        Files.createDirectories(home);
        Files.createDirectories(app);
        Files.createDirectories(outsideConfig);
        Files.createDirectories(workspaceRoot);
        Files.createDirectories(stateHome);
        Files.createDirectories(binDirectory);
        Files.writeString(app.resolve(".symphony-trello-install"), "marker");
        Files.writeString(outsideConfig.resolve(".env"), "TRELLO_TOKEN=test\n");
        Files.createSymbolicLink(configSymlink, outsideConfig);
        Map<String, String> environment = Map.of(
                "HOME",
                home.toString(),
                "SYMPHONY_HOME",
                symphonyHome.toString(),
                "SYMPHONY_TRELLO_CONFIG_DIR",
                configSymlink.toString(),
                "SYMPHONY_TRELLO_WORKSPACE_ROOT",
                workspaceRoot.toString(),
                "SYMPHONY_TRELLO_STATE_HOME",
                stateHome.toString());

        // when
        ProcessResult result = run(environment, "bash", "uninstall.sh", "--yes", "--bin-dir", binDirectory.toString());

        // then
        assertThat(result.exitCode()).as(result.output()).isEqualTo(2);
        assertThat(result.output())
                .contains(
                        "Refusing to remove app directory because it contains current local data that is preserved by default:",
                        "CONFIG     " + configSymlink);
        assertThat(app).isDirectory();
        assertThat(configSymlink).isSymbolicLink();
        assertThat(outsideConfig.resolve(".env")).exists();
    }

    @Test
    void posixUninstallerSkipsUnsafeStableContextCleanupThroughUntrustedSymlink() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        Path home = temporaryDirectory.resolve("unsafe-context-cleanup-home");
        Path outside = temporaryDirectory.resolve("unsafe-context-cleanup-outside");
        Path app = temporaryDirectory.resolve("unsafe-context-cleanup-app");
        Path config = temporaryDirectory.resolve("unsafe-context-cleanup-config");
        Path workspaces = temporaryDirectory.resolve("unsafe-context-cleanup-workspaces");
        Path state = temporaryDirectory.resolve("unsafe-context-cleanup-state");
        Path binDirectory = temporaryDirectory.resolve("unsafe-context-cleanup-bin");
        Path outsideContext = outside.resolve("symphony-trello/install-context.properties");
        Files.createDirectories(home);
        Files.createDirectories(outsideContext.getParent());
        Files.createDirectories(app);
        Files.createDirectories(config);
        Files.createDirectories(workspaces);
        Files.createDirectories(state);
        Files.createDirectories(binDirectory);
        Files.writeString(app.resolve(".symphony-trello-install"), "marker");
        Files.writeString(outsideContext, "installer=install.sh\n");
        Files.createSymbolicLink(home.resolve(".config"), outside);
        Map<String, String> environment = Map.of(
                "HOME",
                home.toString(),
                "SYMPHONY_TRELLO_CONFIG_DIR",
                config.toString(),
                "SYMPHONY_TRELLO_WORKSPACE_ROOT",
                workspaces.toString(),
                "SYMPHONY_TRELLO_STATE_HOME",
                state.toString());

        // when
        ProcessResult result = run(
                environment,
                "bash",
                "uninstall.sh",
                "--yes",
                "--yes-local-data",
                "--remove-all-local-data",
                "--prefix",
                app.toString(),
                "--bin-dir",
                binDirectory.toString());

        // then
        result.assertSuccess();
        assertThat(outsideContext).exists();
        assertThat(result.output())
                .doesNotContain(
                        "Remove install context discovery files for future no-flag installs and uninstalls?",
                        "REMOVE  " + outsideContext);
    }

    @Test
    void posixFullUninstallRemovesStableContextForCustomHome() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        assumeTrue(commandExists("git"));
        Path sourceRepository = createSourceRepository(temporaryDirectory);
        Path fakeBin = createFakeToolchain(temporaryDirectory);
        Path home = temporaryDirectory.resolve("custom-context-clean-home");
        Path symphonyHome = temporaryDirectory.resolve("custom-context-clean-symphony-home");
        Path fakeLog = temporaryDirectory.resolve("custom-context-clean.log");
        Path stableStateContext = home.resolve(".local/state/symphony-trello/install-context.properties");
        Path stableConfigContext = home.resolve(".config/symphony-trello/install-context.properties");
        Files.createDirectories(home);
        Map<String, String> installEnvironment =
                installEnvironmentForCustomHome(sourceRepository, fakeBin, home, symphonyHome, fakeLog);

        // when
        ProcessResult install = run(installEnvironment, "bash", "install.sh", "--no-onboard");
        ProcessResult uninstall = run(
                Map.of("HOME", home.toString()),
                "bash",
                "uninstall.sh",
                "--yes",
                "--yes-local-data",
                "--remove-all-local-data");
        ProcessResult nextInstall =
                run(Map.of("HOME", home.toString()), "bash", "install.sh", "--dry-run", "--no-onboard");

        // then
        install.assertSuccess();
        uninstall.assertSuccess();
        assertThat(uninstall.output()).contains("REMOVE  " + stableStateContext, "REMOVE  " + stableConfigContext);
        assertThat(stableStateContext).doesNotExist();
        assertThat(stableConfigContext).doesNotExist();
        assertThat(nextInstall.output())
                .contains("Install: " + home.resolve(".local/share/symphony-trello/app"))
                .doesNotContain("Install: " + symphonyHome.resolve("app"));
    }

    @Test
    void posixUninstallerUsesInstallContextWhenOnlyCommandDirectoryIsOverridden() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        Path home = temporaryDirectory.resolve("context-bin-home");
        Path app = temporaryDirectory.resolve("context-bin-app");
        Path config = temporaryDirectory.resolve("context-bin-config");
        Path workspaces = temporaryDirectory.resolve("context-bin-workspaces");
        Path state = temporaryDirectory.resolve("context-bin-state");
        Path cache = temporaryDirectory.resolve("context-bin-cache");
        Path contextBin = temporaryDirectory.resolve("context-bin-original");
        Path overrideBin = temporaryDirectory.resolve("context-bin-override");
        Files.createDirectories(home.resolve(".config/symphony-trello"));
        Files.createDirectories(app);
        Files.createDirectories(config);
        Files.createDirectories(workspaces);
        Files.createDirectories(state);
        Files.createDirectories(cache);
        Files.createDirectories(contextBin);
        Files.createDirectories(overrideBin);
        Files.writeString(app.resolve(".symphony-trello-install"), "marker");
        Files.writeString(
                home.resolve(".config/symphony-trello/install-context.properties"),
                """
                installer=install.sh
                install_format_version=2
                layout_mode=custom-env
                app_dir=%s
                config_dir=%s
                workspace_root=%s
                state_home=%s
                cache_dir=%s
                bin_dir=%s
                codex_npm_prefix=%s
                created_microos_var_root=false
                """
                        .formatted(app, config, workspaces, state, cache, contextBin, cache.resolve("npm")));

        // when
        ProcessResult result = run(
                Map.of("HOME", home.toString()),
                "bash",
                "uninstall.sh",
                "--dry-run",
                "--yes",
                "--bin-dir",
                overrideBin.toString());

        // then
        result.assertSuccess();
        assertThat(result.output())
                .contains(
                        "App checkout: " + app,
                        "Installed CLI: " + overrideBin.resolve("symphony-trello"),
                        "CONFIG          " + config,
                        "WORKSPACES      " + workspaces,
                        "STATE/LOGS      " + state)
                .doesNotContain(
                        "App checkout: " + home.resolve(".local/share/symphony-trello/app"),
                        "Installed CLI: " + contextBin.resolve("symphony-trello"));
    }

    @Test
    void posixUninstallerRemovesEmptyInstallerCreatedMicroOsRootWhenAllLocalDataIsRemoved() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        Path home = temporaryDirectory.resolve("microos-cleanup-home");
        Path varPath = temporaryDirectory.resolve("microos-cleanup-var");
        Path usersRoot = temporaryDirectory.resolve("microos-cleanup-users");
        Path userRoot = usersRoot.resolve("micro-user");
        Path app = userRoot.resolve("data/app");
        Path config = userRoot.resolve("config");
        Path workspaces = userRoot.resolve("workspaces");
        Path state = userRoot.resolve("state");
        Path cache = userRoot.resolve("cache");
        Files.createDirectories(home);
        Files.createDirectories(varPath);
        Files.createDirectories(app);
        Files.createDirectories(config);
        Files.createDirectories(workspaces);
        Files.createDirectories(state);
        Files.createDirectories(cache.resolve("npm"));
        Files.writeString(app.resolve(".symphony-trello-install"), "installer-managed\n");
        Files.writeString(
                state.resolve("install-context.properties"),
                """
                installer=install.sh
                install_format_version=2
                layout_mode=microos-var
                app_dir=%s
                config_dir=%s
                workspace_root=%s
                state_home=%s
                cache_dir=%s
                bin_dir=%s
                codex_npm_prefix=%s
                created_microos_var_root=true
                """
                        .formatted(
                                app,
                                config,
                                workspaces,
                                state,
                                cache,
                                home.resolve(".local/bin"),
                                cache.resolve("npm")));

        // when
        ProcessResult result = run(
                microOsLayoutEnvironment(home, varPath, usersRoot),
                "bash",
                "uninstall.sh",
                "--yes",
                "--yes-local-data",
                "--remove-all-local-data");

        // then
        result.assertSuccess();
        assertThat(result.output()).contains("REMOVE  " + userRoot);
        assertThat(userRoot).doesNotExist();
    }

    @Test
    void posixUninstallerDryRunDoesNotPruneMicroOsDataOrCacheDirectories() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        Path home = temporaryDirectory.resolve("microos-dry-cleanup-home");
        Path varPath = temporaryDirectory.resolve("microos-dry-cleanup-var");
        Path usersRoot = temporaryDirectory.resolve("microos-dry-cleanup-users");
        Path userRoot = usersRoot.resolve("micro-user");
        Path data = userRoot.resolve("data");
        Path config = userRoot.resolve("config");
        Path workspaces = userRoot.resolve("workspaces");
        Path state = userRoot.resolve("state");
        Path cache = userRoot.resolve("cache");
        Files.createDirectories(home);
        Files.createDirectories(varPath);
        Files.createDirectories(data);
        Files.createDirectories(config);
        Files.createDirectories(workspaces);
        Files.createDirectories(state);
        Files.createDirectories(cache);
        Files.writeString(
                state.resolve("install-context.properties"),
                """
                installer=install.sh
                install_format_version=2
                layout_mode=microos-var
                app_dir=%s
                config_dir=%s
                workspace_root=%s
                state_home=%s
                cache_dir=%s
                bin_dir=%s
                codex_npm_prefix=%s
                created_microos_var_root=true
                """
                        .formatted(
                                data.resolve("app"),
                                config,
                                workspaces,
                                state,
                                cache,
                                home.resolve(".local/bin"),
                                cache.resolve("npm")));

        // when
        ProcessResult result = run(
                microOsLayoutEnvironment(home, varPath, usersRoot),
                "bash",
                "uninstall.sh",
                "--dry-run",
                "--yes",
                "--yes-local-data",
                "--remove-all-local-data");

        // then
        result.assertSuccess();
        assertThat(result.output())
                .contains("Dry run: no files changed.", "WOULD REMOVE  " + userRoot)
                .doesNotContain("Kept MicroOS data root because it is not empty");
        assertThat(data).isDirectory();
        assertThat(cache).isDirectory();
        assertThat(userRoot).isDirectory();
    }

    @Test
    void posixUninstallerDeletesBtrfsSubvolumeTargetsWithBtrfsCommand() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        Path fakeBin = temporaryDirectory.resolve("btrfs-bin");
        Path mountRoot = temporaryDirectory.resolve("btrfs-mount");
        Path symphonyHome = mountRoot.resolve("symphony");
        Path app = symphonyHome.resolve("app");
        Path btrfsLog = temporaryDirectory.resolve("btrfs.log");
        Files.createDirectories(fakeBin);
        Files.createDirectories(app);
        Files.writeString(app.resolve(".symphony-trello-install"), "installer-managed\n");
        writeExecutable(
                fakeBin.resolve("btrfs"),
                """
                #!/usr/bin/env bash
                set -euo pipefail
                case "$1 $2" in
                  "subvolume show")
                    exit 0
                    ;;
                  "subvolume list")
                    echo "ID 256 gen 1 top level 5 path symphony/app/nested"
                    echo "ID 257 gen 1 top level 256 path symphony/app/nested/deeper"
                    ;;
                  "subvolume delete")
                    echo "delete $3" >> "${SYMPHONY_FAKE_LOG:?}"
                    /bin/rm -rf "$3"
                    ;;
                esac
                """);

        // when
        ProcessResult result = runBtrfsUninstall(fakeBin, mountRoot, symphonyHome, btrfsLog);

        // then
        result.assertSuccess();
        assertBtrfsSubvolumeDeletes(btrfsLog, app);
    }

    @Test
    void posixUninstallerKeepsNestedBtrfsDeletesUnderTargetMountRoot() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        Path fakeBin = temporaryDirectory.resolve("btrfs-target-root-bin");
        Path symphonyHome = temporaryDirectory.resolve("btrfs-target-root-home");
        Path app = symphonyHome.resolve("app");
        Path btrfsLog = temporaryDirectory.resolve("btrfs-target-root.log");
        Files.createDirectories(fakeBin);
        Files.createDirectories(app.resolve("nested/deeper"));
        Files.writeString(app.resolve(".symphony-trello-install"), "installer-managed\n");
        writeExecutable(
                fakeBin.resolve("findmnt"),
                """
                #!/usr/bin/env bash
                set -euo pipefail
                echo "${SYMPHONY_FAKE_BTRFS_MOUNT:?}"
                """);
        writeExecutable(
                fakeBin.resolve("btrfs"),
                """
                #!/usr/bin/env bash
                set -euo pipefail
                case "$1 $2" in
                  "subvolume show")
                    exit 0
                    ;;
                  "subvolume list")
                    echo "ID 256 gen 1 top level 5 path app/nested"
                    echo "ID 257 gen 1 top level 256 path nested/deeper"
                    ;;
                  "subvolume delete")
                    echo "delete $3" >> "${SYMPHONY_FAKE_LOG:?}"
                    case "$3" in
                      "${SYMPHONY_FAKE_APP:?}"*)
                        /bin/rm -rf "$3"
                        ;;
                      *)
                        echo "unexpected delete path: $3" >&2
                        exit 3
                        ;;
                    esac
                    ;;
                esac
                """);
        Map<String, String> environment = Map.of(
                "PATH",
                fakeBin + File.pathSeparator + System.getenv("PATH"),
                "SYMPHONY_HOME",
                symphonyHome.toString(),
                "SYMPHONY_FAKE_BTRFS_MOUNT",
                app.toString(),
                "SYMPHONY_FAKE_APP",
                app.toString(),
                "SYMPHONY_FAKE_LOG",
                btrfsLog.toString());

        // when
        ProcessResult result = run(environment, "bash", "uninstall.sh", "--yes");

        // then
        result.assertSuccess();
        assertBtrfsSubvolumeDeletes(btrfsLog, app);
    }

    @Test
    void posixUninstallerDeletesNestedBtrfsSubvolumesUnderOrdinaryDirectoryTargets() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        Path fakeBin = temporaryDirectory.resolve("btrfs-ordinary-bin");
        Path mountRoot = temporaryDirectory.resolve("btrfs-ordinary-mount");
        Path symphonyHome = mountRoot.resolve("symphony");
        Path app = symphonyHome.resolve("app");
        Path btrfsLog = temporaryDirectory.resolve("btrfs-ordinary.log");
        Files.createDirectories(fakeBin);
        Files.createDirectories(app.resolve("nested/deeper"));
        Files.writeString(app.resolve(".symphony-trello-install"), "installer-managed\n");
        writeExecutable(
                fakeBin.resolve("btrfs"),
                """
                #!/usr/bin/env bash
                set -euo pipefail
                case "$1 $2" in
                  "subvolume show")
                    exit 1
                    ;;
                  "subvolume list")
                    echo "ID 256 gen 1 top level 5 path symphony/app/nested"
                    echo "ID 257 gen 1 top level 256 path symphony/app/nested/deeper"
                    ;;
                  "subvolume delete")
                    echo "delete $3" >> "${SYMPHONY_FAKE_LOG:?}"
                    /bin/rm -rf "$3"
                    ;;
                esac
                """);
        writeExecutable(
                fakeBin.resolve("rm"),
                """
                #!/usr/bin/env bash
                set -euo pipefail
                if [[ "${1:-}" == "--help" ]]; then
                  echo "GNU rm supports --one-file-system"
                  exit 0
                fi
                echo "rm $*" >> "${SYMPHONY_FAKE_LOG:?}"
                /bin/rm "$@"
                """);

        // when
        ProcessResult result = runBtrfsUninstall(fakeBin, mountRoot, symphonyHome, btrfsLog);

        // then
        result.assertSuccess();
        assertThat(btrfsLog)
                .content(StandardCharsets.UTF_8)
                .containsSubsequence(
                        "delete " + app.resolve("nested/deeper"),
                        "delete " + app.resolve("nested"),
                        "rm -rf --one-file-system " + app);
        assertThat(app).doesNotExist();
    }

    @Test
    void posixUninstallerFallsBackToRmForOrdinaryDirectoryWhenBtrfsListFails() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        Path fakeBin = temporaryDirectory.resolve("btrfs-list-fails-bin");
        Path mountRoot = temporaryDirectory.resolve("btrfs-list-fails-mount");
        Path symphonyHome = mountRoot.resolve("symphony");
        Path app = symphonyHome.resolve("app");
        Path btrfsLog = temporaryDirectory.resolve("btrfs-list-fails.log");
        Files.createDirectories(fakeBin);
        Files.createDirectories(app);
        Files.writeString(app.resolve(".symphony-trello-install"), "installer-managed\n");
        writeExecutable(
                fakeBin.resolve("btrfs"),
                """
                #!/usr/bin/env bash
                set -euo pipefail
                case "$1 $2" in
                  "subvolume show")
                    exit 1
                    ;;
                  "subvolume list")
                    exit 42
                    ;;
                  "subvolume delete")
                    echo "delete $3" >> "${SYMPHONY_FAKE_LOG:?}"
                    ;;
                esac
                """);
        writeExecutable(
                fakeBin.resolve("rm"),
                """
                #!/usr/bin/env bash
                set -euo pipefail
                if [[ "${1:-}" == "--help" ]]; then
                  echo "GNU rm supports --one-file-system"
                  exit 0
                fi
                echo "rm $*" >> "${SYMPHONY_FAKE_LOG:?}"
                /bin/rm "$@"
                """);

        // when
        ProcessResult result = runBtrfsUninstall(fakeBin, mountRoot, symphonyHome, btrfsLog);

        // then
        result.assertSuccess();
        assertThat(btrfsLog)
                .content(StandardCharsets.UTF_8)
                .contains("rm -rf --one-file-system " + app)
                .doesNotContain("delete ");
        assertThat(app).doesNotExist();
    }

    @Test
    void posixUninstallerUnlinksSymlinkedCleanupTargetsBeforeBtrfsProbe() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        Path fakeBin = temporaryDirectory.resolve("btrfs-symlink-bin");
        Path home = temporaryDirectory.resolve("btrfs-symlink-home");
        Path symphonyHome = temporaryDirectory.resolve("btrfs-symlink-symphony-home");
        Path targetConfig = temporaryDirectory.resolve("btrfs-symlink-target-config");
        Path configSymlink = home.resolve("config-link");
        Path commandLog = temporaryDirectory.resolve("btrfs-symlink.log");
        Files.createDirectories(fakeBin);
        Files.createDirectories(home);
        Files.createDirectories(symphonyHome);
        Files.createDirectories(targetConfig);
        Files.createSymbolicLink(configSymlink, targetConfig);
        writeExecutable(
                fakeBin.resolve("btrfs"),
                """
                #!/usr/bin/env bash
                set -euo pipefail
                echo "btrfs $*" >> "${SYMPHONY_FAKE_LOG:?}"
                exit 3
                """);
        writeExecutable(
                fakeBin.resolve("rm"),
                """
                #!/usr/bin/env bash
                set -euo pipefail
                if [[ "${1:-}" == "--help" ]]; then
                  echo "GNU rm supports --one-file-system"
                  exit 0
                fi
                echo "rm $*" >> "${SYMPHONY_FAKE_LOG:?}"
                /bin/rm "$@"
                """);
        Map<String, String> environment = Map.of(
                "PATH",
                fakeBin + File.pathSeparator + System.getenv("PATH"),
                "HOME",
                home.toString(),
                "SYMPHONY_HOME",
                symphonyHome.toString(),
                "SYMPHONY_TRELLO_CONFIG_DIR",
                configSymlink.toString(),
                "SYMPHONY_FAKE_LOG",
                commandLog.toString());

        // when
        ProcessResult result = run(environment, "bash", "uninstall.sh", "--yes", "--yes-local-data", "--remove-config");

        // then
        result.assertSuccess();
        assertThat(configSymlink).doesNotExist();
        assertThat(targetConfig).isDirectory();
        assertThat(commandLog)
                .content(StandardCharsets.UTF_8)
                .contains("rm -f " + configSymlink)
                .doesNotContain("btrfs ");
    }

    @Test
    void posixUninstallerDeletesBtrfsSubvolumesReturnedRelativeToFilesystemRoot() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        Path fakeBin = temporaryDirectory.resolve("btrfs-top-level-bin");
        Path mountRoot = temporaryDirectory.resolve("btrfs-top-level-mount").resolve("var");
        Path symphonyHome = mountRoot.resolve("lib/symphony-trello/users/codex");
        Path app = symphonyHome.resolve("app");
        Path btrfsLog = temporaryDirectory.resolve("btrfs-top-level.log");
        Files.createDirectories(fakeBin);
        Files.createDirectories(app.resolve("nested/deeper"));
        Files.writeString(app.resolve(".symphony-trello-install"), "installer-managed\n");
        writeExecutable(
                fakeBin.resolve("btrfs"),
                """
                #!/usr/bin/env bash
                set -euo pipefail
                case "$1 $2" in
                  "subvolume show")
                    exit 0
                    ;;
                  "subvolume list")
                    echo "ID 256 gen 1 top level 5 path @/var/lib/symphony-trello/users/codex/app/nested"
                    echo "ID 257 gen 1 top level 256 path @/var/lib/symphony-trello/users/codex/app/nested/deeper"
                    ;;
                  "subvolume delete")
                    echo "delete $3" >> "${SYMPHONY_FAKE_LOG:?}"
                    case "$3" in
                      */@/*)
                        echo "unexpected unmounted btrfs path: $3" >&2
                        exit 3
                        ;;
                      */var/lib/symphony-trello/users/codex/app*)
                        /bin/rm -rf "$3"
                        ;;
                      *)
                        echo "unexpected delete path: $3" >&2
                        exit 3
                        ;;
                    esac
                    ;;
                esac
                """);

        // when
        ProcessResult result = runBtrfsUninstall(fakeBin, mountRoot, symphonyHome, btrfsLog);

        // then
        result.assertSuccess();
        assertThat(assertBtrfsSubvolumeDeletes(btrfsLog, app)).doesNotContain("/@/");
    }

    @Test
    void posixUninstallerParsesBtrfsSubvolumePathsContainingPathToken() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        Path fakeBin = temporaryDirectory.resolve("btrfs-path-token-bin");
        Path mountRoot = temporaryDirectory.resolve("btrfs-path-token-mount");
        Path symphonyHome = mountRoot.resolve("foo path bar");
        Path app = symphonyHome.resolve("app");
        Path btrfsLog = temporaryDirectory.resolve("btrfs-path-token.log");
        Files.createDirectories(fakeBin);
        Files.createDirectories(app.resolve("nested/deeper"));
        Files.writeString(app.resolve(".symphony-trello-install"), "installer-managed\n");
        writeExecutable(
                fakeBin.resolve("btrfs"),
                """
                #!/usr/bin/env bash
                set -euo pipefail
                case "$1 $2" in
                  "subvolume show")
                    exit 0
                    ;;
                  "subvolume list")
                    echo "ID 256 gen 1 top level 5 path foo path bar/app/nested"
                    echo "ID 257 gen 1 top level 256 path foo path bar/app/nested/deeper"
                    ;;
                  "subvolume delete")
                    echo "delete $3" >> "${SYMPHONY_FAKE_LOG:?}"
                    case "$3" in
                      "${SYMPHONY_FAKE_APP:?}"*)
                        /bin/rm -rf "$3"
                        ;;
                      *)
                        echo "unexpected delete path: $3" >&2
                        exit 3
                        ;;
                    esac
                    ;;
                esac
                """);

        // when
        ProcessResult result = runBtrfsUninstall(fakeBin, mountRoot, symphonyHome, btrfsLog);

        // then
        result.assertSuccess();
        assertBtrfsSubvolumeDeletes(btrfsLog, app);
    }

    @Test
    void posixUninstallerUsesPortableRmWhenOneFileSystemIsUnsupported() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        Path fakeBin = temporaryDirectory.resolve("bsd-rm-bin");
        Path symphonyHome = temporaryDirectory.resolve("bsd-rm-home");
        Path app = symphonyHome.resolve("app");
        Path rmLog = temporaryDirectory.resolve("bsd-rm.log");
        Files.createDirectories(fakeBin);
        Files.createDirectories(app);
        Files.writeString(app.resolve(".symphony-trello-install"), "installer-managed\n");
        writeExecutable(
                fakeBin.resolve("rm"),
                """
                #!/usr/bin/env bash
                set -euo pipefail
                if [[ "${1:-}" == "--help" ]]; then
                  echo "BSD rm"
                  exit 1
                fi
                echo "rm $*" >> "${SYMPHONY_FAKE_LOG:?}"
                /bin/rm "$@"
                """);
        Map<String, String> environment = Map.of(
                "PATH", fakeBin + File.pathSeparator + System.getenv("PATH"),
                "SYMPHONY_HOME", symphonyHome.toString(),
                "SYMPHONY_FAKE_LOG", rmLog.toString());

        // when
        ProcessResult result = run(environment, "bash", "uninstall.sh", "--yes");

        // then
        result.assertSuccess();
        assertThat(rmLog)
                .content(StandardCharsets.UTF_8)
                .contains("rm -rf " + app)
                .doesNotContain("--one-file-system");
    }

    private static Map<String, String> microOsLayoutEnvironment(Path home, Path varPath, Path usersRoot) {
        Map<String, String> environment = new LinkedHashMap<>();
        environment.put("HOME", home.toString());
        environment.put("USER", "micro-user");
        environment.put("SYMPHONY_TRELLO_TEST_USER", "micro-user");
        environment.put("SYMPHONY_TRELLO_TEST_GROUP", "micro-group");
        environment.put("SYMPHONY_TRELLO_TEST_OS", "Linux");
        environment.put("SYMPHONY_TRELLO_TEST_ARCH", "x86_64");
        environment.put("SYMPHONY_TRELLO_TEST_OS_ID", "opensuse-microos");
        environment.put("SYMPHONY_TRELLO_TEST_OS_PRETTY_NAME", "openSUSE MicroOS");
        environment.put("SYMPHONY_TRELLO_TEST_VAR_PATH", varPath.toString());
        environment.put("SYMPHONY_TRELLO_TEST_VAR_USERS_ROOT", usersRoot.toString());
        environment.put("SYMPHONY_TRELLO_TEST_HOME_FS_SOURCE", "rootfs");
        environment.put("SYMPHONY_TRELLO_TEST_ROOT_FS_SOURCE", "rootfs");
        environment.put("SYMPHONY_TRELLO_TEST_VAR_FS_SOURCE", "varfs");
        environment.put("SYMPHONY_TRELLO_TEST_HOME_SIZE_KB", "1024");
        environment.put("SYMPHONY_TRELLO_TEST_VAR_SIZE_KB", "8192");
        return Map.copyOf(environment);
    }

    private static Path installContext(Path directory) {
        return directory.resolve(INSTALL_CONTEXT_PROPERTIES);
    }

    @Test
    void posixUninstallHelpUsesStablePublicUrls() throws Exception {
        // given
        assumeTrue(commandExists("bash"));

        // when
        ProcessResult result = run(Map.of(), "bash", "uninstall.sh", "--help");

        // then
        result.assertSuccess();
        assertThat(result.output())
                .contains(
                        "curl -fsSL https://symphony-trello.fmartin.ch/uninstall.sh | bash",
                        "curl -fsSL https://symphony-trello.fmartin.ch/uninstall.sh | bash -s -- --dry-run")
                .doesNotContain("raw.githubusercontent.com");
    }

    @Test
    void powershellUninstallHelpUsesStablePublicUrls() throws Exception {
        // given
        List<String> pwsh = powershellCommand();
        assumeFalse(pwsh.isEmpty());

        // when
        ProcessResult result = run(
                Map.of(),
                command(pwsh, "-NoProfile", "-File", "./uninstall.ps1", "--help")
                        .toArray(String[]::new));

        // then
        result.assertSuccess();
        assertThat(result.output())
                .contains("powershell -c \"irm https://symphony-trello.fmartin.ch/uninstall.ps1 | iex\"")
                .doesNotContain("raw.githubusercontent.com");
    }

    @Test
    void posixInstallerRejectsNormalizedCommandDirectoryInsideAppBeforeDryRunPlan() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        Path home = temporaryDirectory.resolve("normalized-bin-home");
        Files.createDirectories(home);
        Path commandDirectory = home.resolve(".local/share/symphony-trello/missing/../app/bin");

        // when
        ProcessResult result = runUnchecked(
                Map.of("HOME", home.toString()),
                "bash",
                "install.sh",
                "--dry-run",
                "--no-onboard",
                "--bin-dir",
                commandDirectory.toString());

        // then
        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.output())
                .contains("--bin-dir must not overlap Symphony app, config, workspace, or state directories.")
                .doesNotContain("Dry run: no files changed.");
    }

    @Test
    void posixInstallerRejectsNormalizedAppPathInsideConfigBeforeDryRunPlan() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        Path home = temporaryDirectory.resolve("normalized-app-home");
        Path safeBin = temporaryDirectory.resolve("normalized-app-bin");
        Files.createDirectories(home);
        Path app = home.resolve(".local/share/symphony-trello/missing/../../../../.config/symphony-trello/app");

        // when
        ProcessResult result = runUnchecked(
                Map.of("HOME", home.toString()),
                "bash",
                "install.sh",
                "--dry-run",
                "--no-onboard",
                "--prefix",
                app.toString(),
                "--bin-dir",
                safeBin.toString());

        // then
        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.output())
                .contains("--prefix must not overlap Symphony config, workspace, or state directories.")
                .doesNotContain("Dry run: no files changed.");
    }

    @Test
    void posixScriptsRejectSymlinkResolvedAppPathAtCheckoutDirectory() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        Path home = temporaryDirectory.resolve("symlink-checkout-app-home");
        Path safeBin = temporaryDirectory.resolve("symlink-checkout-app-bin");
        Path checkout = Path.of("").toAbsolutePath().normalize();
        Path checkoutParentLink = temporaryDirectory.resolve("symlink-checkout-parent-link");
        Path app = checkoutParentLink.resolve(checkout.getFileName());
        Files.createDirectories(home);
        Files.createDirectories(safeBin);
        Files.createSymbolicLink(checkoutParentLink, checkout.getParent());
        Map<String, String> environment = Map.of("HOME", home.toString());

        // when
        PosixDryRunPair results = runPosixInstallAndUninstallDryRunWithPrefix(environment, app, safeBin);

        // then
        assertThat(results.install().exitCode()).as(results.install().output()).isEqualTo(2);
        assertThat(results.uninstall().exitCode())
                .as(results.uninstall().output())
                .isEqualTo(2);
        assertThat(results.install().output())
                .contains("--prefix must point to a dedicated app checkout directory.")
                .doesNotContain("Dry run: no files changed.");
        assertThat(results.uninstall().output())
                .contains("--prefix must point to a dedicated app checkout directory.")
                .doesNotContain("Will remove if present:");
    }

    @Test
    void posixScriptsRejectSymlinkResolvedCommandDirectoryAtHome() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        Path home = temporaryDirectory.resolve("symlink-home-bin-home");
        Path symphonyHome = temporaryDirectory.resolve("symlink-home-bin-symphony");
        Path homeLink = temporaryDirectory.resolve("symlink-home-bin-link");
        Files.createDirectories(home);
        Files.createDirectories(symphonyHome);
        Files.createSymbolicLink(homeLink, home);
        Map<String, String> environment = Map.of("HOME", home.toString(), "SYMPHONY_HOME", symphonyHome.toString());

        // when
        ProcessResult install = runUnchecked(
                environment, "bash", "install.sh", "--dry-run", "--no-onboard", "--bin-dir", homeLink.toString());
        ProcessResult uninstall = runUnchecked(
                environment, "bash", "uninstall.sh", "--dry-run", "--yes", "--bin-dir", homeLink.toString());

        // then
        assertThat(install.exitCode()).as(install.output()).isEqualTo(2);
        assertThat(uninstall.exitCode()).as(uninstall.output()).isEqualTo(2);
        assertThat(install.output())
                .contains("--bin-dir must point to a dedicated command directory.")
                .doesNotContain("Dry run: no files changed.");
        assertThat(uninstall.output())
                .contains("--bin-dir must point to a dedicated command directory.")
                .doesNotContain("Will remove if present:");
    }

    @Test
    void posixScriptsRejectSymlinkParentTraversalAppPathInsideConfigBeforeUnsafeRemoval() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        Path home = temporaryDirectory.resolve("symlink-parent-app-home");
        Path symphonyHome = temporaryDirectory.resolve("symlink-parent-app-symphony");
        Path configSubdirectory = symphonyHome.resolve("config/sub");
        Path symlink = temporaryDirectory.resolve("symlink-parent-app-link");
        Path app = symlink.resolve("../app");
        Path safeBin = temporaryDirectory.resolve("symlink-parent-app-bin");
        Files.createDirectories(home);
        Files.createDirectories(configSubdirectory);
        Files.createSymbolicLink(symlink, configSubdirectory);
        Map<String, String> environment = Map.of("HOME", home.toString(), "SYMPHONY_HOME", symphonyHome.toString());

        // when
        PosixDryRunPair results = runPosixInstallAndUninstallDryRunWithPrefix(environment, app, safeBin);

        // then
        assertThat(results.install().exitCode()).as(results.install().output()).isEqualTo(2);
        assertThat(results.uninstall().exitCode())
                .as(results.uninstall().output())
                .isEqualTo(2);
        assertThat(results.install().output())
                .contains("--prefix must not overlap Symphony config, workspace, or state directories.")
                .doesNotContain("Dry run: no files changed.");
        assertThat(results.uninstall().output())
                .contains("Refusing dangerous removal path: " + app)
                .doesNotContain("REMOVE  " + app);
        assertThat(configSubdirectory).isDirectory();
    }

    @Test
    void posixScriptsRejectSymlinkCycleAppPathWithoutHanging() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        assumeTrue(commandExists("timeout"));
        Path home = temporaryDirectory.resolve("symlink-cycle-app-home");
        Path app = temporaryDirectory.resolve("symlink-cycle-app-loop");
        Path safeBin = temporaryDirectory.resolve("symlink-cycle-app-bin");
        Files.createDirectories(home);
        Files.createSymbolicLink(app, app);
        Map<String, String> environment = Map.of("HOME", home.toString());

        // when
        ProcessResult install = runUnchecked(
                environment,
                "timeout",
                "5s",
                "bash",
                "install.sh",
                "--dry-run",
                "--no-onboard",
                "--prefix",
                app.toString(),
                "--bin-dir",
                safeBin.toString());
        ProcessResult uninstall = runUnchecked(
                environment,
                "timeout",
                "5s",
                "bash",
                "uninstall.sh",
                "--dry-run",
                "--yes",
                "--prefix",
                app.toString(),
                "--bin-dir",
                safeBin.toString());

        // then
        assertThat(install.exitCode()).as(install.output()).isEqualTo(2);
        assertThat(uninstall.exitCode()).as(uninstall.output()).isEqualTo(2);
        assertThat(install.output()).contains("--prefix must not be a symlink.");
        assertThat(uninstall.output()).contains("--prefix must not be a symlink.");
    }

    @Test
    void posixScriptsRejectSymlinkParentTraversalCommandDirectoryInsideStateBeforePlan() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        Path home = temporaryDirectory.resolve("symlink-parent-bin-home");
        Path symphonyHome = temporaryDirectory.resolve("symlink-parent-bin-symphony");
        Path stateSubdirectory = symphonyHome.resolve("state/sub");
        Path symlink = temporaryDirectory.resolve("symlink-parent-bin-link");
        Path commandDirectory = symlink.resolve("../bin");
        Files.createDirectories(home);
        Files.createDirectories(stateSubdirectory);
        Files.createSymbolicLink(symlink, stateSubdirectory);
        Map<String, String> environment = Map.of("HOME", home.toString(), "SYMPHONY_HOME", symphonyHome.toString());

        // when
        ProcessResult install = runUnchecked(
                environment,
                "bash",
                "install.sh",
                "--dry-run",
                "--no-onboard",
                "--prefix",
                symphonyHome.resolve("app").toString(),
                "--bin-dir",
                commandDirectory.toString());
        ProcessResult uninstall = runUnchecked(
                environment,
                "bash",
                "uninstall.sh",
                "--dry-run",
                "--yes",
                "--prefix",
                symphonyHome.resolve("app").toString(),
                "--bin-dir",
                commandDirectory.toString());

        // then
        assertThat(install.exitCode()).as(install.output()).isEqualTo(2);
        assertThat(uninstall.exitCode()).as(uninstall.output()).isEqualTo(2);
        assertThat(install.output())
                .contains("--bin-dir must not overlap Symphony app, config, workspace, or state directories.")
                .doesNotContain("Dry run: no files changed.");
        assertThat(uninstall.output())
                .contains("--bin-dir must not overlap Symphony app, config, workspace, or state directories.")
                .doesNotContain("Will remove if present:");
    }

    @MethodSource("posixUnsafeCommandDirectoryScenarios")
    @ParameterizedTest(name = "{0}")
    void posixScriptsRejectUnsafeCommandDirectoriesBeforeDryRunPlan(PosixUnsafeCommandDirectoryScenario scenario)
            throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        Path home = temporaryDirectory.resolve(scenario.slug() + "-home");
        Path symphonyHome = temporaryDirectory.resolve(scenario.slug() + "-symphony-home");
        Path app = temporaryDirectory.resolve(scenario.slug() + "-app");
        Path file = temporaryDirectory.resolve(scenario.slug() + "-file");
        Path symlink = temporaryDirectory.resolve(scenario.symlinkName());
        Files.createDirectories(home);
        Files.createDirectories(symphonyHome.resolve(scenario.overlapDirectory()));
        Files.writeString(file, "not a directory");
        Files.createSymbolicLink(symlink, symphonyHome.resolve(scenario.overlapDirectory()));
        Map<String, String> environment = Map.of("HOME", home.toString(), "SYMPHONY_HOME", symphonyHome.toString());

        List<UnsafeCommandDirectory> cases = posixUnsafeCommandDirectories(
                home,
                file,
                symlink,
                scenario.symlinkName(),
                new UnsafeCommandDirectory(
                        scenario.overlapCaseName(),
                        symphonyHome.resolve(scenario.overlapDirectory()).toString(),
                        "--bin-dir must not overlap Symphony app, config, workspace, or state directories."));
        List<UnsafeCommandDirectory> rejectedCases =
                cases.stream().filter(scenario::rejectsCommandDirectory).toList();

        // when
        List<ProcessResult> results = rejectedCases.stream()
                .map(commandDirectory -> runUnchecked(
                        environment,
                        "bash",
                        scenario.script(),
                        scenario.firstFlag(),
                        scenario.secondFlag(),
                        "--prefix",
                        app.toString(),
                        "--bin-dir",
                        commandDirectory.value()))
                .toList();

        // then
        for (int index = 0; index < rejectedCases.size(); index++) {
            UnsafeCommandDirectory commandDirectory = rejectedCases.get(index);
            ProcessResult result = results.get(index);
            assertThat(result.exitCode()).as(commandDirectory.name()).isEqualTo(2);
            assertThat(result.output())
                    .as(commandDirectory.name())
                    .contains(commandDirectory.expectedMessage())
                    .doesNotContain(scenario.absentOutputArray());
        }
    }

    private static Stream<PosixUnsafeCommandDirectoryScenario> posixUnsafeCommandDirectoryScenarios() {
        return Stream.of(
                new PosixUnsafeCommandDirectoryScenario(
                        "installer",
                        "install-bin",
                        "install.sh",
                        "--dry-run",
                        "--no-onboard",
                        "config",
                        "config-overlap",
                        "install-bin-config-link",
                        false,
                        List.of("WOULD install command", "Command PATH setup")),
                new PosixUnsafeCommandDirectoryScenario(
                        "uninstaller",
                        "uninstall-bin",
                        "uninstall.sh",
                        "--dry-run",
                        "--yes",
                        "state",
                        "state-overlap",
                        "uninstall-bin-state-link",
                        true,
                        List.of("Installed CLI:", "Will remove if present:")));
    }

    @Test
    void posixUninstallDryRunPrintsPlannedWorkerStopsWithoutStopping() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        Path home = temporaryDirectory.resolve("would-stop-home");
        Path symphonyHome = temporaryDirectory.resolve("would-stop-symphony-home");
        Path app = symphonyHome.resolve("app");
        Path stateHome = symphonyHome.resolve("state");
        Files.createDirectories(home);
        Files.createDirectories(stateHome);
        Files.createDirectories(app.resolve("target").resolve("quarkus-app"));
        Files.writeString(app.resolve(".symphony-trello-install"), "marker");
        String markerArgument =
                "-Dsymphony.trello.managed.app_home=" + app.toAbsolutePath().normalize();
        String jarArgument = app.toAbsolutePath()
                .normalize()
                .resolve("target")
                .resolve("quarkus-app")
                .resolve("quarkus-run.jar")
                .toString();
        Process worker = new ProcessBuilder(
                        "bash", "-c", "while :; do sleep 1; done", "--", markerArgument, jarArgument)
                .start();
        try {
            Files.writeString(
                    stateHome.resolve("WORKFLOW.would-stop.md.abcdef123456.pid"), Long.toString(worker.pid()));
            Map<String, String> environment = Map.of("HOME", home.toString(), "SYMPHONY_HOME", symphonyHome.toString());

            // when
            ProcessResult result =
                    runUnchecked(environment, "bash", "uninstall.sh", "--dry-run", "--yes", "--prefix", app.toString());

            // then
            assertThat(result.exitCode()).as(result.output()).isZero();
            assertThat(result.output())
                    .contains("WOULD STOP  WORKFLOW.would-stop.md pid=" + worker.pid())
                    .doesNotContain("WOULD STOP  WORKFLOW.would-stop.md.")
                    .doesNotContain("\n  STOP  ");
            assertThat(worker.isAlive()).as("dry run must not stop the worker").isTrue();
        } finally {
            worker.destroyForcibly();
        }
    }

    @Test
    void posixUninstallSkipsStaleUnmanagedPidWithHashFreeLabelWithoutStoppingIt() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        Path home = temporaryDirectory.resolve("stale-skip-home");
        Path symphonyHome = temporaryDirectory.resolve("stale-skip-symphony-home");
        Path app = symphonyHome.resolve("app");
        Path stateHome = symphonyHome.resolve("state");
        Files.createDirectories(home);
        Files.createDirectories(stateHome);
        Files.createDirectories(app.resolve("target").resolve("quarkus-app"));
        Files.writeString(app.resolve(".symphony-trello-install"), "marker");
        Process unmanaged = new ProcessBuilder("bash", "-c", "while :; do sleep 1; done").start();
        try {
            Files.writeString(
                    stateHome.resolve("WORKFLOW.stale-skip.md.abcdef123456.pid"), Long.toString(unmanaged.pid()));
            Map<String, String> environment = Map.of("HOME", home.toString(), "SYMPHONY_HOME", symphonyHome.toString());

            // when
            ProcessResult result =
                    runUnchecked(environment, "bash", "uninstall.sh", "--yes", "--prefix", app.toString());

            // then
            assertThat(result.exitCode()).as(result.output()).isZero();
            assertThat(result.output())
                    .contains("SKIP  stale pid does not belong to this install: WORKFLOW.stale-skip.md pid="
                            + unmanaged.pid())
                    .doesNotContain("WORKFLOW.stale-skip.md.abcdef123456");
            assertThat(unmanaged.isAlive())
                    .as("an unmanaged process must never be stopped")
                    .isTrue();
        } finally {
            unmanaged.destroyForcibly();
        }
    }

    @Test
    void posixUninstallerAllowsSymlinkedCommandDirectory() throws Exception {
        // given
        // Install rejects a symlinked command directory, but a user may replace the directory
        // with a symlink afterward; uninstall must still clean up instead of stranding files.
        assumeTrue(commandExists("bash"));
        Path home = temporaryDirectory.resolve("uninstall-symlink-bin-home");
        Path app = temporaryDirectory.resolve("uninstall-symlink-bin-app");
        Path realBin = temporaryDirectory.resolve("uninstall-symlink-bin-target");
        Path symlinkBin = temporaryDirectory.resolve("uninstall-symlink-bin-link");
        Files.createDirectories(home);
        Files.createDirectories(realBin);
        Files.createSymbolicLink(symlinkBin, realBin);
        Map<String, String> environment = Map.of("HOME", home.toString());

        // when
        ProcessResult result = run(
                environment,
                "bash",
                "uninstall.sh",
                "--dry-run",
                "--yes",
                "--prefix",
                app.toString(),
                "--bin-dir",
                symlinkBin.toString());

        // then
        assertThat(result.exitCode()).as(result.output()).isZero();
        assertThat(result.output()).contains("Installed CLI:", "Will remove if present:");
    }

    @Test
    void posixUninstallerRejectsFinalSymlinkedAppDirectory() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        Path home = temporaryDirectory.resolve("uninstall-symlink-app-home");
        Path targetApp = temporaryDirectory.resolve("uninstall-symlink-app-target");
        Path symlinkApp = temporaryDirectory.resolve("uninstall-symlink-app-link");
        Path bin = temporaryDirectory.resolve("uninstall-symlink-app-bin");
        Files.createDirectories(home);
        Files.createDirectories(targetApp);
        Files.createDirectories(bin);
        Files.writeString(targetApp.resolve(".symphony-trello-install"), "marker");
        Files.createSymbolicLink(symlinkApp, targetApp);

        // when
        ProcessResult result = run(
                Map.of("HOME", home.toString()),
                "bash",
                "uninstall.sh",
                "--yes",
                "--prefix",
                symlinkApp.toString(),
                "--bin-dir",
                bin.toString());

        // then
        assertThat(result.exitCode()).as(result.output()).isEqualTo(2);
        assertThat(result.output()).contains("--prefix must not be a symlink.").doesNotContain("REMOVE  " + symlinkApp);
        assertThat(symlinkApp).isSymbolicLink();
        assertThat(targetApp.resolve(".symphony-trello-install")).exists();
    }

    @Test
    void posixUninstallerPreservesDataInsideCanonicalAppDirectoryThroughSymlinkedParent() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        Path home = temporaryDirectory.resolve("uninstall-symlink-parent-home");
        Path realRoot = temporaryDirectory.resolve("uninstall-symlink-parent-real");
        Path symlinkRoot = temporaryDirectory.resolve("uninstall-symlink-parent-link");
        Path app = symlinkRoot.resolve("app");
        Path config = realRoot.resolve("app/config");
        Path bin = temporaryDirectory.resolve("uninstall-symlink-parent-bin");
        Files.createDirectories(home);
        Files.createDirectories(config);
        Files.createDirectories(bin);
        Files.writeString(realRoot.resolve("app/.symphony-trello-install"), "marker");
        Files.writeString(config.resolve(".env"), "TRELLO_TOKEN=test\n");
        Files.createSymbolicLink(symlinkRoot, realRoot);

        // when
        ProcessResult result = run(
                Map.of("HOME", home.toString(), "SYMPHONY_TRELLO_CONFIG_DIR", config.toString()),
                "bash",
                "uninstall.sh",
                "--yes",
                "--prefix",
                app.toString(),
                "--bin-dir",
                bin.toString());

        // then
        assertThat(result.exitCode()).as(result.output()).isEqualTo(2);
        assertThat(result.output())
                .contains("Refusing to remove app directory because it contains current local data")
                .doesNotContain("REMOVE  " + app);
        assertThat(config.resolve(".env")).exists();
        assertThat(realRoot.resolve("app/.symphony-trello-install")).exists();
    }

    @MethodSource("posixUnsafeAppPathScenarios")
    @ParameterizedTest(name = "{0}")
    void posixScriptsRejectUnsafeAppPathsBeforeDryRunPlan(PosixUnsafeAppPathScenario scenario) throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        Path home = temporaryDirectory.resolve(scenario.slug() + "-home");
        Path symphonyHome = temporaryDirectory.resolve(scenario.slug() + "-symphony-home");
        Path file = temporaryDirectory.resolve(scenario.slug() + "-file");
        Path symlink = temporaryDirectory.resolve(scenario.symlinkName());
        Path safeBin = temporaryDirectory.resolve(scenario.slug() + "-bin");
        Files.createDirectories(home);
        Files.writeString(file, "not a directory");
        Files.createSymbolicLink(symlink, temporaryDirectory.resolve(scenario.slug() + "-target"));
        Map<String, String> environment = Map.of("HOME", home.toString(), "SYMPHONY_HOME", symphonyHome.toString());

        List<UnsafeInstallerPath> cases = scenario.usesLocalDataOverlap()
                ? posixUnsafeAppPaths(
                        home,
                        file,
                        symlink,
                        scenario.symlinkName(),
                        new UnsafeInstallerPath(
                                "local-data-overlap",
                                symphonyHome.toString(),
                                "--prefix must not overlap Symphony config, workspace, or state directories."))
                : posixUnsafeAppPaths(home, file, symlink, scenario.symlinkName());

        // when
        List<ProcessResult> results =
                runPosixAppPathCases(environment, scenario.script(), scenario.fixedArgs(), cases, safeBin);

        // then
        assertUnsafeAppPathFailures(cases, results, OutputStyle.RAW, scenario.absentOutputArray());
    }

    private static Stream<PosixUnsafeAppPathScenario> posixUnsafeAppPathScenarios() {
        return Stream.of(
                new PosixUnsafeAppPathScenario(
                        "installer",
                        "install-app",
                        "install.sh",
                        List.of("--dry-run", "--no-onboard"),
                        "install-app-link",
                        true,
                        List.of("Install:", "WOULD clone or update:", "Command PATH setup")),
                new PosixUnsafeAppPathScenario(
                        "uninstaller",
                        "uninstall-app",
                        "uninstall.sh",
                        List.of("--dry-run", "--yes"),
                        "uninstall-app-link",
                        false,
                        List.of("App checkout:", "Will remove if present:", "Installed CLI:")));
    }

    @Test
    void posixInstallerAcceptsDedicatedAppPathDirectlyUnderHome() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        Path home = temporaryDirectory.resolve("install-home-child-home");
        Path app = home.resolve("symphony-trello");
        Path safeBin = temporaryDirectory.resolve("install-home-child-bin");
        Path fakeBin = createFakeToolchain(temporaryDirectory);
        Files.createDirectories(home);
        Map<String, String> environment = Map.of(
                "HOME",
                home.toString(),
                "PATH",
                fakeBin + File.pathSeparator + System.getenv("PATH"),
                "SYMPHONY_TRELLO_TEST_OS",
                "Linux",
                "SYMPHONY_TRELLO_TEST_ARCH",
                "x86_64");

        // when
        ProcessResult result = run(
                environment,
                "bash",
                "install.sh",
                "--dry-run",
                "--no-onboard",
                "--prefix",
                app.toString(),
                "--bin-dir",
                safeBin.toString());

        // then
        result.assertSuccess();
        assertThat(result.output()).contains("Install: " + app, "WOULD unpack release archive into: " + app);
    }

    @Test
    void posixInstallerRejectsUnsafeSymphonyHomeBeforeDryRunPlan() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        Path home = temporaryDirectory.resolve("install-home-base-home");
        Path safeBin = temporaryDirectory.resolve("install-home-base-bin");
        Files.createDirectories(home);

        List<UnsafeInstallerPath> cases = List.of(
                new UnsafeInstallerPath("root", "/", "--prefix must point to a dedicated app checkout directory."),
                new UnsafeInstallerPath(
                        "root-home", "/root", "--prefix must point to a dedicated app checkout directory."),
                new UnsafeInstallerPath(
                        "home", home.toString(), "--prefix must point to a dedicated app checkout directory."),
                new UnsafeInstallerPath(
                        "control", temporaryDirectory.resolve("home\nline").toString(), "control characters"));

        // when
        List<ProcessResult> results = cases.stream()
                .map(appPath -> {
                    Map<String, String> environment = Map.of("HOME", home.toString(), "SYMPHONY_HOME", appPath.value());
                    return runUnchecked(
                            environment,
                            "bash",
                            "install.sh",
                            "--dry-run",
                            "--no-onboard",
                            "--bin-dir",
                            safeBin.toString());
                })
                .toList();

        // then
        for (int index = 0; index < cases.size(); index++) {
            UnsafeInstallerPath appPath = cases.get(index);
            ProcessResult result = results.get(index);
            assertThat(result.exitCode()).as(appPath.name()).isEqualTo(2);
            assertThat(result.output())
                    .as(appPath.name())
                    .contains(appPath.expectedMessage())
                    .doesNotContain("Install:", "WOULD clone or update:", "Command PATH setup");
        }
    }

    @Test
    void posixUninstallerAcceptsDedicatedAppPathDirectlyUnderHome() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        Path home = temporaryDirectory.resolve("uninstall-home-child-home");
        Path app = home.resolve("symphony-trello");
        Path safeBin = temporaryDirectory.resolve("uninstall-home-child-bin");
        Files.createDirectories(home);
        Map<String, String> environment = Map.of("HOME", home.toString());

        // when
        ProcessResult result = run(
                environment,
                "bash",
                "uninstall.sh",
                "--dry-run",
                "--yes",
                "--prefix",
                app.toString(),
                "--bin-dir",
                safeBin.toString());

        // then
        result.assertSuccess();
        assertThat(result.output()).contains("App checkout: " + app, "Will remove if present:");
    }

    @MethodSource("posixDefaultCommandDirectoryScenarios")
    @ParameterizedTest(name = "{0}")
    void posixScriptsAllowDefaultCommandDirectoryWhenRunFromThatDirectory(PosixDefaultCommandDirectoryScenario scenario)
            throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        Path script = Path.of(scenario.script()).toAbsolutePath();
        Path home = temporaryDirectory.resolve(scenario.slug() + "-home");
        Path binDirectory = home.resolve(".local/bin");
        Files.createDirectories(binDirectory);
        Map<String, String> environment = Map.of("HOME", home.toString());

        // when
        ProcessResult result =
                run(environment, binDirectory, "bash", script.toString(), "--dry-run", scenario.secondFlag());

        // then
        result.assertSuccess();
        assertThat(result.output()).contains(scenario.expectedOutput());
    }

    private static Stream<PosixDefaultCommandDirectoryScenario> posixDefaultCommandDirectoryScenarios() {
        return Stream.of(
                new PosixDefaultCommandDirectoryScenario(
                        "installer",
                        "install-run-from-bin",
                        "install.sh",
                        "--no-onboard",
                        "Dry run: no files changed."),
                new PosixDefaultCommandDirectoryScenario(
                        "uninstaller",
                        "uninstall-run-from-bin",
                        "uninstall.sh",
                        "--yes",
                        "Trello boards were not deleted or archived."));
    }

    @MethodSource("posixRootScopedConfigDirectoryScenarios")
    @ParameterizedTest(name = "{0}")
    void posixScriptsRejectAppPathInsideRootScopedConfigDirectory(PosixRootScopedConfigDirectoryScenario scenario)
            throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        Path home = temporaryDirectory.resolve(scenario.slug() + "-home");
        Path app = home.resolve(".local/share/symphony-trello/app");
        Path safeBin = temporaryDirectory.resolve(scenario.slug() + "-bin");
        Map<String, String> environment = Map.of("HOME", home.toString(), "SYMPHONY_TRELLO_CONFIG_DIR", "/");

        // when
        ProcessResult result = run(
                environment,
                "bash",
                scenario.script(),
                "--dry-run",
                scenario.secondFlag(),
                "--prefix",
                app.toString(),
                "--bin-dir",
                safeBin.toString());

        // then
        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.output()).contains(scenario.expectedMessage()).doesNotContain(scenario.absentOutputArray());
    }

    private static Stream<PosixRootScopedConfigDirectoryScenario> posixRootScopedConfigDirectoryScenarios() {
        return Stream.of(
                new PosixRootScopedConfigDirectoryScenario(
                        "installer",
                        "install-root-config",
                        "install.sh",
                        "--no-onboard",
                        "Symphony config, workspace, and state paths must point to dedicated directories.",
                        List.of("WOULD clone or update:", "Dry run: no files changed.")),
                new PosixRootScopedConfigDirectoryScenario(
                        "uninstaller",
                        "uninstall-root-config",
                        "uninstall.sh",
                        "--yes",
                        "Symphony config, workspace, and state paths must point to dedicated directories.",
                        List.of("Will remove if present:", "Trello boards were not deleted or archived.")));
    }

    @MethodSource("posixHomeValidationScenarios")
    @ParameterizedTest(name = "{0}")
    void posixScriptsRejectMissingOrEmptyHomeBeforeResolvingDefaultPaths(PosixHomeValidationScenario scenario)
            throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        String[] command = {
            "bash",
            scenario.script(),
            "--dry-run",
            scenario.secondFlag(),
            "--prefix",
            temporaryDirectory.resolve("app").toString(),
            "--bin-dir",
            temporaryDirectory.resolve("bin").toString()
        };

        // when
        ProcessResult result = scenario.missingHome() ? runWithoutHome(command) : run(Map.of("HOME", ""), command);

        // then
        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.output())
                .contains(
                        "HOME must be set to a user home directory before running the " + scenario.commandName() + ".")
                .doesNotContain(scenario.absentOutputArray());
    }

    private static Stream<PosixHomeValidationScenario> posixHomeValidationScenarios() {
        return Stream.of(
                new PosixHomeValidationScenario(
                        "installer missing HOME",
                        "install.sh",
                        "--no-onboard",
                        "installer",
                        true,
                        List.of("unbound variable", "/.local/share/symphony-trello")),
                new PosixHomeValidationScenario(
                        "installer empty HOME",
                        "install.sh",
                        "--no-onboard",
                        "installer",
                        false,
                        List.of("/.local/share/symphony-trello")),
                new PosixHomeValidationScenario(
                        "uninstaller missing HOME",
                        "uninstall.sh",
                        "--yes",
                        "uninstaller",
                        true,
                        List.of("unbound variable", "/.local/share/symphony-trello")),
                new PosixHomeValidationScenario(
                        "uninstaller empty HOME",
                        "uninstall.sh",
                        "--yes",
                        "uninstaller",
                        false,
                        List.of("/.local/share/symphony-trello")));
    }

    @MethodSource("invalidPosixInstallerSourceInputs")
    @ParameterizedTest(name = "{0}")
    void posixInstallerRejectsInvalidSourceInputsBeforeDryRunPlan(
            String name, String[] sourceArgs, String expectedMessage) throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        Path prefix = temporaryDirectory
                .resolve(name.replaceAll("[^A-Za-z0-9]+", "-"))
                .resolve("app");
        String[] command = Stream.concat(
                        Stream.of("bash", "install.sh", "--dry-run", "--no-onboard", "--prefix", prefix.toString()),
                        Stream.of(sourceArgs))
                .toArray(String[]::new);

        // when
        ProcessResult result = run(Map.of(), command);

        // then
        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.output()).contains(expectedMessage).doesNotContain("WOULD clone or update:");
        assertThat(prefix).doesNotExist();
    }

    private static Stream<Arguments> invalidPosixInstallerSourceInputs() {
        return Stream.of(
                Arguments.of("blank repo", new String[] {"--repo", ""}, "--repo must not be blank."),
                Arguments.of("option-looking repo", new String[] {"--repo", "--prefix"}, "Missing value for --repo"),
                Arguments.of(
                        "option-looking repo with following token",
                        new String[] {"--repo", "--prefix", "/tmp/ignored"},
                        "Missing value for --repo"),
                Arguments.of(
                        "malformed repo",
                        new String[] {"--repo", "not-a-url"},
                        "--repo must be a URL or existing local Git repository path."),
                Arguments.of("blank ref", new String[] {"--ref", ""}, "--ref must not be blank."),
                Arguments.of(
                        "option-looking ref with following token",
                        new String[] {"--ref", "--prefix", "/tmp/ignored"},
                        "Missing value for --ref"),
                Arguments.of(
                        "path traversal ref",
                        new String[] {"--ref", "../main"},
                        "--ref must be a branch, tag, or commit without Git namespace prefixes or path traversal."),
                Arguments.of(
                        "remote namespace ref",
                        new String[] {"--ref", "origin/main"},
                        "--ref must be a branch, tag, or commit without Git namespace prefixes or path traversal."),
                Arguments.of(
                        "full namespace ref",
                        new String[] {"--ref", "refs/heads/main"},
                        "--ref must be a branch, tag, or commit without Git namespace prefixes or path traversal."),
                Arguments.of(
                        "lock component ref",
                        new String[] {"--ref", "foo.lock/bar"},
                        "--ref must be a branch, tag, or commit without Git namespace prefixes or path traversal."),
                Arguments.of(
                        "trailing dot ref",
                        new String[] {"--ref", "foo."},
                        "--ref must be a branch, tag, or commit without Git namespace prefixes or path traversal."),
                Arguments.of(
                        "unsupported ref characters",
                        new String[] {"--ref", "main;echo-x"},
                        "--ref contains unsupported characters."),
                Arguments.of(
                        "control character ref",
                        new String[] {"--ref", "main\nother"},
                        "--ref must not contain whitespace or control characters."));
    }

    @Test
    void posixInstallerAcceptsScpStyleRepoAndRedactsCredentialUrlBeforeDryRunPlan() throws Exception {
        // given
        assumeTrue(commandExists("bash"));

        // when
        ProcessResult scpRepo = run(
                Map.of(),
                "bash",
                "install.sh",
                "--dry-run",
                "--no-onboard",
                "--repo",
                "deploy@example.com:org/repo.git");
        ProcessResult credentialRepo = run(
                Map.of(),
                "bash",
                "install.sh",
                "--dry-run",
                "--no-onboard",
                "--repo",
                "https://user:secret-token@example.com/org/repo.git");
        ProcessResult buildMetadataRef =
                run(Map.of(), "bash", "install.sh", "--dry-run", "--no-onboard", "--ref", "v1.2.3+hotfix.1");

        // then
        scpRepo.assertSuccess();
        assertThat(scpRepo.output()).contains("Repository: deploy@example.com:org/repo.git");
        credentialRepo.assertSuccess();
        assertThat(credentialRepo.output())
                .contains("Repository: https://<redacted>@example.com/org/repo.git")
                .doesNotContain("user:secret-token");
        buildMetadataRef.assertSuccess();
        assertThat(buildMetadataRef.output()).contains("Ref: v1.2.3+hotfix.1");
    }

    @Test
    void posixInstallerRejectsExistingNonGitRepoPathBeforeDryRunPlan() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        Path notGitRepository = temporaryDirectory.resolve("not-git-repository");
        Path prefix = temporaryDirectory.resolve("non-git-repo-prefix").resolve("app");
        Files.createDirectories(notGitRepository);

        // when
        ProcessResult result = run(
                Map.of(),
                "bash",
                "install.sh",
                "--dry-run",
                "--no-onboard",
                "--prefix",
                prefix.toString(),
                "--repo",
                notGitRepository.toString());

        // then
        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.output())
                .contains("--repo must be a URL or existing local Git repository path.")
                .doesNotContain("WOULD clone or update:");
        assertThat(prefix).doesNotExist();
    }

    @Test
    void posixInstallerAcceptsLocalGitRepoPathWithSpacesBeforeDryRunPlan() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        Path localRepository = temporaryDirectory.resolve("local repo with spaces");
        Path prefix = temporaryDirectory.resolve("local-repo-space-prefix").resolve("app");
        Files.createDirectories(localRepository.resolve(".git"));

        // when
        ProcessResult result = run(
                Map.of(),
                "bash",
                "install.sh",
                "--dry-run",
                "--no-onboard",
                "--prefix",
                prefix.toString(),
                "--repo",
                localRepository.toString());

        // then
        result.assertSuccess();
        assertThat(result.output()).contains("Repository: " + localRepository, "WOULD clone or update:");
        assertThat(prefix).doesNotExist();
    }

    @Test
    void posixInstallerRedactsCredentialRepoUrlInRealGitStepLabels() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        Path fakeBin = createFakeToolchain(temporaryDirectory);
        Path prefix = temporaryDirectory.resolve("credential-repo-label-prefix").resolve("app");
        Path binDirectory = temporaryDirectory.resolve("credential-repo-label-bin");
        Path fakeLog = temporaryDirectory.resolve("credential-repo-label.log");
        writeExecutable(
                fakeBin.resolve("git"),
                """
                #!/usr/bin/env bash
                set -euo pipefail
                case "${1:-}" in
                  ls-remote) exit 1 ;;
                  clone)
                    app="${@: -1}"
                    mkdir -p "$app/.git" "$app/target/quarkus-app"
                    printf '#!/usr/bin/env bash\\nexit 0\\n' > "$app/mvnw"
                    chmod +x "$app/mvnw"
                    exit 0
                    ;;
                  -C) exit 0 ;;
                  *) exit 0 ;;
                esac
                """);
        Map<String, String> environment = Map.of(
                "PATH",
                fakeBin + File.pathSeparator + System.getenv("PATH"),
                "SYMPHONY_TRELLO_TEST_OS",
                "Linux",
                "SYMPHONY_TRELLO_TEST_ARCH",
                "x86_64",
                "SYMPHONY_FAKE_LOG",
                fakeLog.toString());

        // when
        ProcessResult result = run(
                environment,
                "bash",
                "install.sh",
                "--no-onboard",
                "--prefix",
                prefix.toString(),
                "--bin-dir",
                binDirectory.toString(),
                "--repo",
                "https://user:secret-token@example.com/org/repo.git");

        // then
        result.assertSuccess();
        assertThat(result.output())
                .contains("Repository: https://<redacted>@example.com/org/repo.git")
                .doesNotContain("user:secret-token");
    }

    @Test
    void posixSourceCheckoutInstallContextUsesBuiltVersionAndSourceCommit() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        assumeTrue(commandExists("git"));
        Path sourceRepository = createSourceRepository(temporaryDirectory);
        String expectedCommit = run(Map.of(), "git", "-C", sourceRepository.toString(), "rev-parse", "HEAD")
                .output()
                .trim();
        Path fakeBin = createFakeToolchain(temporaryDirectory);
        Path symphonyHome = temporaryDirectory.resolve("source-context-home");
        Path binDirectory = temporaryDirectory.resolve("source-context-bin");
        Path fakeLog = temporaryDirectory.resolve("source-context.log");
        Map<String, String> environment = Map.of(
                "PATH", fakeBin + File.pathSeparator + System.getenv("PATH"),
                "SYMPHONY_TRELLO_REPO_URL", sourceRepository.toUri().toString(),
                "SYMPHONY_HOME", symphonyHome.toString(),
                "SYMPHONY_FAKE_LOG", fakeLog.toString());

        // when
        ProcessResult result =
                run(environment, "bash", "install.sh", "--no-onboard", "--bin-dir", binDirectory.toString());

        // then
        result.assertSuccess();
        assertThat(symphonyHome.resolve("state/install-context.properties"))
                .content(StandardCharsets.UTF_8)
                .contains("install_source=source-checkout", "app_version=test", "source_commit=" + expectedCommit)
                .doesNotContain(
                        "app_version=" + installerDefaultRef().substring(1), "release_tag=", "release_base_url=");
    }

    @ParameterizedTest(name = "POSIX source checkout app_version fallback for {0} version output")
    @ValueSource(strings = {"fail", "malformed", "multiline"})
    void posixSourceCheckoutInstallContextUsesUnknownVersionWhenBuiltVersionIsNotProven(String versionMode)
            throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        assumeTrue(commandExists("git"));
        Path sourceRepository = createSourceRepository(temporaryDirectory);
        String expectedCommit = run(Map.of(), "git", "-C", sourceRepository.toString(), "rev-parse", "HEAD")
                .output()
                .trim();
        Path fakeBin = createFakeToolchain(temporaryDirectory);
        Path symphonyHome = temporaryDirectory.resolve("source-context-" + versionMode + "-home");
        Path binDirectory = temporaryDirectory.resolve("source-context-" + versionMode + "-bin");
        Path fakeLog = temporaryDirectory.resolve("source-context-" + versionMode + ".log");
        Map<String, String> environment = Map.of(
                "PATH", fakeBin + File.pathSeparator + System.getenv("PATH"),
                "SYMPHONY_TRELLO_REPO_URL", sourceRepository.toUri().toString(),
                "SYMPHONY_HOME", symphonyHome.toString(),
                "SYMPHONY_FAKE_LOG", fakeLog.toString(),
                "SYMPHONY_FAKE_VERSION_MODE", versionMode);

        // when
        ProcessResult result =
                run(environment, "bash", "install.sh", "--no-onboard", "--bin-dir", binDirectory.toString());

        // then
        result.assertSuccess();
        assertThat(symphonyHome.resolve("state/install-context.properties"))
                .content(StandardCharsets.UTF_8)
                .contains("install_source=source-checkout", "app_version=unknown", "source_commit=" + expectedCommit)
                .doesNotContain(
                        "app_version=" + installerDefaultRef().substring(1), "release_tag=", "release_base_url=");
    }

    @Test
    void posixInstallerDryRunReportsConcreteMissingPrerequisiteActions() throws Exception {
        // given
        assumeTrue(Files.exists(Path.of("/bin/bash")));
        Map<String, String> environment = Map.of(
                "PATH", temporaryDirectory.resolve("empty-bin").toString(),
                "SYMPHONY_TRELLO_TEST_OS", "Linux",
                "SYMPHONY_TRELLO_TEST_ARCH", "x86_64");

        // when
        ProcessResult result = run(environment, "/bin/bash", "install.sh", "--dry-run");

        // then
        assertThat(result.exitCode()).isZero();
        assertThat(result.output())
                .contains(
                        "Install source: release-archive",
                        "Version: " + installerDefaultRef().substring(1),
                        "WOULD download release archive:",
                        "WOULD verify SHA3-256 checksum from:",
                        "WOULD offer to install Java 25+ JDK",
                        "WOULD offer to install Codex CLI with Symphony-managed npm:",
                        "Node.js/npm installed: no");
    }

    @Test
    void posixInstallerRejectsLeadingVVersionEnvironmentValue() throws Exception {
        // given
        assumeTrue(Files.exists(Path.of("/bin/bash")));
        String version = installerDefaultRef().substring(1);
        Map<String, String> environment = Map.of(
                "PATH",
                temporaryDirectory.resolve("empty-bin").toString(),
                "SYMPHONY_TRELLO_TEST_OS",
                "Linux",
                "SYMPHONY_TRELLO_TEST_ARCH",
                "x86_64",
                "SYMPHONY_TRELLO_VERSION",
                "v" + version);

        // when
        ProcessResult result = run(environment, "/bin/bash", "install.sh", "--dry-run", "--no-onboard");

        // then
        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.output())
                .contains("--version must be a semantic version without the leading v.")
                .doesNotContain("Dry run: no files changed.", "Version: " + version, "vv" + version);
    }

    @Test
    void posixInstallerRejectsLeadingVVersionArgument() throws Exception {
        // given
        assumeTrue(Files.exists(Path.of("/bin/bash")));
        String version = installerDefaultRef().substring(1);

        // when
        ProcessResult result =
                run(Map.of(), "/bin/bash", "install.sh", "--dry-run", "--no-onboard", "--version", "v" + version);

        // then
        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.output())
                .contains("--version must be a semantic version without the leading v.")
                .doesNotContain("Dry run: no files changed.", "Version: " + version, "vv" + version);
    }

    @Test
    void posixInstallerRejectsRemovedUpdatePathOption() throws Exception {
        // given
        assumeTrue(Files.exists(Path.of("/bin/bash")));

        // when
        ProcessResult result = run(Map.of(), "/bin/bash", "install.sh", "--dry-run", "--update-path", "--no-onboard");

        // then
        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.output()).contains("Unknown option: --update-path");
    }

    @Test
    void posixInstallerNoOnboardFailsCleanlyWithoutTerminalWhenPrerequisitePromptWouldRun() throws Exception {
        // given
        assumeTrue(Files.exists(Path.of("/bin/bash")));
        Path fakeBin = temporaryDirectory.resolve("noninteractive-bin");
        Files.createDirectories(fakeBin);
        writeExecutable(fakeBin.resolve("git"), "#!/usr/bin/env bash\nexit 0\n");
        writeExecutable(fakeBin.resolve("apt-get"), "#!/usr/bin/env bash\nexit 0\n");
        writeCommandProxy(fakeBin, "sed", "/bin/sed");
        writeCommandProxy(fakeBin, "head", "/usr/bin/head");
        Map<String, String> environment = Map.of(
                "PATH", fakeBin.toString(),
                "SYMPHONY_TRELLO_TEST_EUID", "0",
                "SYMPHONY_TRELLO_TEST_OS", "Linux",
                "SYMPHONY_TRELLO_TEST_ARCH", "x86_64");

        // when
        ProcessResult result = run(environment, "/bin/bash", "install.sh", "--no-onboard");

        // then
        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.output())
                .contains(
                        "Java 25+ JDK is missing.",
                        "This step needs an interactive terminal.",
                        "install the missing prerequisite manually first")
                .doesNotContain("/dev/tty: No such device or address", "pass --no-onboard");
    }

    @Test
    void posixInstallerDoesNotOfferPathSetupWhenBinDirIsAlreadyOnPath() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        assumeTrue(commandExists("git"));
        Path sourceRepository = createSourceRepository(temporaryDirectory);
        Path fakeBin = createFakeToolchain(temporaryDirectory);
        Path symphonyHome = temporaryDirectory.resolve("path-present-home");
        Path binDirectory = temporaryDirectory.resolve("path-present-bin");
        Path fakeLog = temporaryDirectory.resolve("path-present.log");
        Map<String, String> environment = Map.of(
                "PATH", fakeBin + File.pathSeparator + binDirectory + File.pathSeparator + System.getenv("PATH"),
                "SYMPHONY_TRELLO_REPO_URL", sourceRepository.toUri().toString(),
                "SYMPHONY_HOME", symphonyHome.toString(),
                "SYMPHONY_FAKE_LOG", fakeLog.toString());

        // when
        ProcessResult result =
                run(environment, "bash", "install.sh", "--no-onboard", "--bin-dir", binDirectory.toString());

        // then
        result.assertSuccess();
        assertThat(result.output()).doesNotContain("Command PATH setup", "not on PATH");
    }

    @Test
    void posixInstallerAddsPathSetupByDefaultWithoutDuplicates() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        assumeTrue(commandExists("git"));
        assumeTrue(commandExists("script"));
        Path installScript = Path.of("install.sh").toAbsolutePath();
        Path sourceRepository = createSourceRepository(temporaryDirectory);
        Path fakeBin = createFakeToolchain(temporaryDirectory);
        Path home = temporaryDirectory.resolve("path-accepted-home");
        Path symphonyHome = temporaryDirectory.resolve("path-accepted-symphony-home");
        Path binDirectory = temporaryDirectory.resolve("path-accepted-bin");
        Path fakeLog = temporaryDirectory.resolve("path-accepted.log");
        Files.createDirectories(home);
        Map<String, String> environment = Map.of(
                "PATH", fakeBin + File.pathSeparator + System.getenv("PATH"),
                "HOME", home.toString(),
                "SHELL", "/bin/bash",
                "SYMPHONY_TRELLO_REPO_URL", sourceRepository.toUri().toString(),
                "SYMPHONY_HOME", symphonyHome.toString(),
                "SYMPHONY_FAKE_LOG", fakeLog.toString());

        // when
        ProcessResult firstInstall = runWithPseudoTerminal(
                environment,
                "",
                "bash " + shellQuote(installScript.toString()) + " --no-onboard --bin-dir "
                        + shellQuote(binDirectory.toString()));
        ProcessResult secondInstall = runWithPseudoTerminal(
                environment,
                "",
                "bash " + shellQuote(installScript.toString()) + " --no-onboard --bin-dir "
                        + shellQuote(binDirectory.toString()));

        // then
        firstInstall.assertSuccess();
        secondInstall.assertSuccess();
        Path profile = home.resolve(".bashrc");
        Path loginProfile = home.resolve(".profile");
        String expectedLine = "export PATH='" + binDirectory + "':\"$PATH\"";
        assertThat(firstInstall.output())
                .contains(
                        "Command PATH setup",
                        "Added " + binDirectory + " to PATH in " + profile,
                        "Added " + binDirectory + " to PATH in " + loginProfile);
        assertThat(secondInstall.output()).contains("PATH setup already exists in " + profile);
        assertThat(profile)
                .content(StandardCharsets.UTF_8)
                .contains("# >>> Symphony for Trello PATH >>>", expectedLine, "# <<< Symphony for Trello PATH <<<")
                .containsOnlyOnce(expectedLine);
        assertThat(loginProfile)
                .content(StandardCharsets.UTF_8)
                .contains("# >>> Symphony for Trello PATH >>>", expectedLine, "# <<< Symphony for Trello PATH <<<")
                .containsOnlyOnce(expectedLine);
    }

    @Test
    void posixPipedInstallerAddsPathSetupByDefault() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        assumeTrue(commandExists("git"));
        assumeTrue(commandExists("script"));
        Path installScript = Path.of("install.sh").toAbsolutePath();
        Path sourceRepository = createSourceRepository(temporaryDirectory);
        Path fakeBin = createFakeToolchain(temporaryDirectory);
        Path home = temporaryDirectory.resolve("path-piped-home");
        Path symphonyHome = temporaryDirectory.resolve("path-piped-symphony-home");
        Path binDirectory = temporaryDirectory.resolve("path-piped-bin");
        Path fakeLog = temporaryDirectory.resolve("path-piped.log");
        Files.createDirectories(home);
        Map<String, String> environment = Map.of(
                "PATH", fakeBin + File.pathSeparator + System.getenv("PATH"),
                "HOME", home.toString(),
                "SHELL", "/bin/bash",
                "SYMPHONY_TRELLO_REPO_URL", sourceRepository.toUri().toString(),
                "SYMPHONY_HOME", symphonyHome.toString(),
                "SYMPHONY_FAKE_LOG", fakeLog.toString());

        // when
        ProcessResult result = runWithPseudoTerminal(
                environment,
                "",
                "/bin/cat " + shellQuote(installScript.toString()) + " | bash -s -- --no-onboard --bin-dir "
                        + shellQuote(binDirectory.toString()));

        // then
        result.assertSuccess();
        assertThat(result.output()).contains("Command PATH setup", "Added " + binDirectory + " to PATH");
        assertThat(home.resolve(".bashrc"))
                .content(StandardCharsets.UTF_8)
                .contains("export PATH='" + binDirectory + "':\"$PATH\"");
        assertThat(home.resolve(".profile"))
                .content(StandardCharsets.UTF_8)
                .contains("export PATH='" + binDirectory + "':\"$PATH\"");
    }

    @Test
    void posixInstallerAddsPathSetupBeforeGuidedSetupFailure() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        assumeTrue(commandExists("git"));
        assumeTrue(commandExists("script"));
        Path installScript = Path.of("install.sh").toAbsolutePath();
        Path sourceRepository = createSourceRepository(temporaryDirectory);
        Path fakeBin = createFakeToolchain(temporaryDirectory);
        writeExecutable(
                fakeBin.resolve("java"),
                """
                #!/usr/bin/env bash
                set -euo pipefail
                if [[ "${1:-}" == "-version" ]]; then
                  echo 'openjdk version "25.0.1" 2026-04-21' >&2
                  exit 0
                fi
                if [[ "$*" == *"ch.fmartin.symphony.trello.setup.TrelloBoardSetupMain setup-local"* ]]; then
                  echo "setup-local failed before PATH test" >&2
                  exit 7
                fi
                echo "java $*" >> "${SYMPHONY_FAKE_LOG:?}"
                exit 0
                """);
        Path home = temporaryDirectory.resolve("path-before-setup-failure-home");
        Path symphonyHome = temporaryDirectory.resolve("path-before-setup-failure-symphony-home");
        Path binDirectory = temporaryDirectory.resolve("path-before-setup-failure-bin");
        Path fakeLog = temporaryDirectory.resolve("path-before-setup-failure.log");
        Files.createFile(temporaryDirectory.resolve("codex-authenticated"));
        Files.createDirectories(home);
        Map<String, String> environment = Map.of(
                "PATH", fakeBin + File.pathSeparator + System.getenv("PATH"),
                "HOME", home.toString(),
                "SHELL", "/bin/bash",
                "SYMPHONY_TRELLO_REPO_URL", sourceRepository.toUri().toString(),
                "SYMPHONY_HOME", symphonyHome.toString(),
                "SYMPHONY_FAKE_LOG", fakeLog.toString());

        // when
        ProcessResult result = runWithPseudoTerminal(
                environment,
                "",
                "bash " + shellQuote(installScript.toString()) + " --bin-dir " + shellQuote(binDirectory.toString()));

        // then
        assertThat(result.exitCode()).as(result.output()).isEqualTo(7);
        assertThat(result.output())
                .containsSubsequence(
                        "OK  Command installed: " + binDirectory.resolve("symphony-trello"),
                        "Added " + binDirectory + " to PATH in " + home.resolve(".bashrc"),
                        "Added " + binDirectory + " to PATH in " + home.resolve(".profile"),
                        "Starting setup...",
                        "setup-local failed before PATH test");
        assertThat(home.resolve(".bashrc"))
                .content(StandardCharsets.UTF_8)
                .contains("export PATH='" + binDirectory + "':\"$PATH\"");
        assertThat(home.resolve(".profile"))
                .content(StandardCharsets.UTF_8)
                .contains("export PATH='" + binDirectory + "':\"$PATH\"");
    }

    @Test
    void posixInstallerGuidedSetupWithoutTerminalSuggestsNoOnboardWhenPrerequisitesExist() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        assumeTrue(commandExists("git"));
        Path sourceRepository = createSourceRepository(temporaryDirectory);
        Path fakeBin = createFakeToolchain(temporaryDirectory);
        Path home = temporaryDirectory.resolve("guided-no-tty-home");
        Path symphonyHome = temporaryDirectory.resolve("guided-no-tty-symphony-home");
        Path binDirectory = temporaryDirectory.resolve("guided-no-tty-bin");
        Path fakeLog = temporaryDirectory.resolve("guided-no-tty.log");
        Files.createFile(temporaryDirectory.resolve("codex-authenticated"));
        Files.createDirectories(home);
        Map<String, String> environment = Map.of(
                "PATH", fakeBin + File.pathSeparator + System.getenv("PATH"),
                "HOME", home.toString(),
                "SHELL", "/bin/bash",
                "SYMPHONY_TRELLO_REPO_URL", sourceRepository.toUri().toString(),
                "SYMPHONY_HOME", symphonyHome.toString(),
                "SYMPHONY_FAKE_LOG", fakeLog.toString());

        // when
        ProcessResult result = run(environment, "bash", "install.sh", "--bin-dir", binDirectory.toString());

        // then
        assertThat(result.exitCode()).as(result.output()).isEqualTo(2);
        assertThat(result.output())
                .contains("Starting setup...", "This step needs an interactive terminal.", "pass --no-onboard")
                .doesNotContain("install the missing prerequisite manually first");
    }

    @Test
    void posixInstallerLeavesProfileUnchangedWhenPathSetupIsDisabled() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        assumeTrue(commandExists("git"));
        assumeTrue(commandExists("script"));
        Path installScript = Path.of("install.sh").toAbsolutePath();
        Path sourceRepository = createSourceRepository(temporaryDirectory);
        Path fakeBin = createFakeToolchain(temporaryDirectory);
        Path home = temporaryDirectory.resolve("path-declined-home");
        Path symphonyHome = temporaryDirectory.resolve("path-declined-symphony-home");
        Path binDirectory = temporaryDirectory.resolve("path-declined-bin");
        Path fakeLog = temporaryDirectory.resolve("path-declined.log");
        Files.createDirectories(home);
        Map<String, String> environment = Map.of(
                "PATH", fakeBin + File.pathSeparator + System.getenv("PATH"),
                "HOME", home.toString(),
                "SHELL", "/bin/bash",
                "SYMPHONY_TRELLO_REPO_URL", sourceRepository.toUri().toString(),
                "SYMPHONY_HOME", symphonyHome.toString(),
                "SYMPHONY_FAKE_LOG", fakeLog.toString());

        // when
        ProcessResult result = runWithPseudoTerminal(
                environment,
                "",
                "bash " + shellQuote(installScript.toString()) + " --no-onboard --no-update-path --bin-dir "
                        + shellQuote(binDirectory.toString()));
        ProcessResult status =
                run(environment, binDirectory.resolve("symphony-trello").toString(), "status");

        // then
        result.assertSuccess();
        status.assertSuccess();
        assertThat(result.output())
                .contains(
                        "NOTE  " + binDirectory + " is not on PATH for this shell.",
                        "Suggested profile files:",
                        home.resolve(".bashrc").toString());
        assertThat(home.resolve(".bashrc")).doesNotExist();
        assertThat(home.resolve(".profile")).doesNotExist();
    }

    @Test
    void posixUninstallDryRunPlanSeparatesRemovalsFromPreservedData() throws Exception {
        // given
        assumeTrue(commandExists("bash"));

        // when
        String output = posixUninstallDryRunPlanOutput("uninstall-plan", "--remove-all-local-data");

        // then
        int removeSection = output.indexOf("Will remove if present:");
        int preserveSection = output.indexOf("Will preserve:");
        assertThat(removeSection).as(output).isNotNegative();
        assertThat(preserveSection).as(output).isGreaterThan(removeSection);
        String removePlan = output.substring(removeSection, preserveSection);
        String preservePlan = output.substring(preserveSection);
        assertThat(removePlan)
                .contains("CONFIG", "WORKSPACES", "STATE/LOGS", "WORKERS         Managed Symphony workers");
        assertThat(preservePlan)
                .contains("AUTH", "TRELLO")
                .doesNotContain("CONFIG          ", "WORKSPACES      ", "STATE/LOGS      ");
    }

    @Test
    void posixUninstallDryRunPreservesLocalDataByDefault() throws Exception {
        // given
        assumeTrue(commandExists("bash"));

        // when
        String output = posixUninstallDryRunPlanOutput("uninstall-default");

        // then
        int preserveSection = output.indexOf("Will preserve:");
        assertThat(preserveSection).as(output).isNotNegative();
        assertThat(output.substring(preserveSection)).contains("CONFIG", "WORKSPACES", "STATE/LOGS", "AUTH", "TRELLO");
        assertThat(output.substring(0, preserveSection)).doesNotContain("CONFIG          ");
    }

    private String posixUninstallDryRunPlanOutput(String prefix, String... extraFlags) throws Exception {
        Path home = temporaryDirectory.resolve(prefix + "-home");
        Path symphonyHome = temporaryDirectory.resolve(prefix + "-symphony-home");
        Path binDirectory = temporaryDirectory.resolve(prefix + "-bin");
        Files.createDirectories(home);
        Files.createDirectories(symphonyHome.resolve("state"));
        Map<String, String> environment = Map.of(
                "PATH", System.getenv("PATH"),
                "HOME", home.toString(),
                "SYMPHONY_HOME", symphonyHome.toString());
        List<String> command = new ArrayList<>(List.of("bash", "uninstall.sh", "--dry-run", "--yes"));
        command.addAll(List.of(extraFlags));
        command.addAll(List.of("--bin-dir", binDirectory.toString()));
        ProcessResult result = run(environment, command.toArray(String[]::new));
        result.assertSuccess();
        return result.output();
    }

    @Test
    void posixWrapperReportsActionableErrorWhenJavaIsMissing() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        assumeTrue(commandExists("git"));
        Path sourceRepository = createSourceRepository(temporaryDirectory);
        Path fakeBin = createFakeToolchain(temporaryDirectory);
        Path home = temporaryDirectory.resolve("missing-java-home");
        Path symphonyHome = temporaryDirectory.resolve("missing-java-symphony-home");
        Path binDirectory = temporaryDirectory.resolve("missing-java-bin");
        Path fakeLog = temporaryDirectory.resolve("missing-java.log");
        Files.createDirectories(home);
        Map<String, String> environment = Map.of(
                "PATH", fakeBin + File.pathSeparator + System.getenv("PATH"),
                "HOME", home.toString(),
                "SHELL", "/bin/bash",
                "SYMPHONY_TRELLO_REPO_URL", sourceRepository.toUri().toString(),
                "SYMPHONY_HOME", symphonyHome.toString(),
                "SYMPHONY_FAKE_LOG", fakeLog.toString());
        ProcessResult install = run(
                environment,
                "bash",
                "install.sh",
                "--no-onboard",
                "--no-update-path",
                "--bin-dir",
                binDirectory.toString());
        Path runtimeBin = createWrapperRuntimePathWithoutJava(temporaryDirectory);
        try {
            Map<String, String> environmentWithoutJava = Map.of(
                    "PATH",
                    runtimeBin.toString(),
                    "HOME",
                    home.toString(),
                    "SHELL",
                    "/bin/bash",
                    "SYMPHONY_HOME",
                    symphonyHome.toString());
            ProcessResult javaLookup =
                    run(environmentWithoutJava, runtimeBin.resolve("bash").toString(), "-c", "command -v java");

            // when
            ProcessResult status = run(
                    environmentWithoutJava,
                    binDirectory.resolve("symphony-trello").toString(),
                    "status");

            // then
            install.assertSuccess();
            assertThat(javaLookup.exitCode())
                    .as("the controlled runtime PATH must not resolve java, but found: %s", javaLookup.output())
                    .isNotZero();
            assertThat(status.exitCode()).as(status.output()).isEqualTo(2);
            assertThat(status.output())
                    .contains("Symphony for Trello needs Java 25+ on PATH", "rerun the installer")
                    .doesNotContain("exec: java: not found");
        } finally {
            // Remove the host-tool links before @TempDir cleanup: JUnit warns in the build log
            // whenever it deletes a symlink that resolves to a location outside the temp dir.
            deleteWrapperRuntimePathWithoutJava(runtimeBin);
        }
    }

    @Test
    void powerShellWrapperSourceGuardsMissingJavaBeforeLaunch() throws Exception {
        // given
        // Runtime coverage for the missing-java branch is POSIX-only: the PowerShell wrapper only
        // exists after a full install.ps1 run, and pwsh is not available in every verification
        // environment. This pins the generated wrapper text so the guard cannot silently drop.
        String installer = Files.readString(Path.of("install.ps1"));

        // when
        int javaGuard = installer.indexOf("if (-not (Get-Command java -ErrorAction SilentlyContinue))");
        int javaLaunch = installer.indexOf("& java \"-Dsymphony.trello.app.home=", javaGuard);

        // then
        assertThat(javaGuard)
                .as("generated PowerShell wrapper must guard the java lookup")
                .isNotNegative();
        assertThat(javaLaunch)
                .as("generated PowerShell wrapper must launch java")
                .isNotNegative();
        assertThat(javaGuard)
                .as("the java guard must run before the java launch")
                .isLessThan(javaLaunch);
        assertThat(installer)
                .contains(
                        "Symphony for Trello needs Java 25+ on PATH, but no java command was found.",
                        "Install a Java 25+ JDK or rerun the installer, then try again:");
    }

    /// A runtime PATH for negative wrapper tests holding only the commands the installed POSIX
    /// wrapper needs before its java lookup. Hosts commonly have /usr/bin/java or /bin/java, so a
    /// host-directory PATH cannot prove the missing-java branch runs.
    private static Path createWrapperRuntimePathWithoutJava(Path temporaryDirectory) throws IOException {
        Path runtimeBin = temporaryDirectory.resolve("runtime-bin-without-java");
        Files.createDirectories(runtimeBin);
        for (String tool : List.of("bash", "mkdir")) {
            Files.createSymbolicLink(runtimeBin.resolve(tool), hostTool(tool));
        }
        return runtimeBin;
    }

    private static void deleteWrapperRuntimePathWithoutJava(Path runtimeBin) throws IOException {
        try (Stream<Path> links = Files.list(runtimeBin)) {
            for (Path link : links.toList()) {
                Files.delete(link);
            }
        }
    }

    private static Path hostTool(String tool) {
        return Stream.of(System.getenv("PATH").split(File.pathSeparator))
                .filter(directory -> !directory.isBlank())
                .map(directory -> Path.of(directory).resolve(tool))
                .filter(Files::isExecutable)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Required tool is missing on the host PATH: " + tool));
    }

    @Test
    void posixNonInteractiveInstallerUpdatesProfileByDefault() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        assumeTrue(commandExists("git"));
        Path sourceRepository = createSourceRepository(temporaryDirectory);
        Path fakeBin = createFakeToolchain(temporaryDirectory);
        Path home = temporaryDirectory.resolve("path-noninteractive-home");
        Path symphonyHome = temporaryDirectory.resolve("path-noninteractive-symphony-home");
        Path binDirectory = temporaryDirectory.resolve("path-noninteractive-bin");
        Path fakeLog = temporaryDirectory.resolve("path-noninteractive.log");
        Files.createDirectories(home);
        Map<String, String> environment = Map.of(
                "PATH", fakeBin + File.pathSeparator + System.getenv("PATH"),
                "HOME", home.toString(),
                "SHELL", "/bin/bash",
                "SYMPHONY_TRELLO_REPO_URL", sourceRepository.toUri().toString(),
                "SYMPHONY_HOME", symphonyHome.toString(),
                "SYMPHONY_FAKE_LOG", fakeLog.toString());

        // when
        ProcessResult result =
                run(environment, "bash", "install.sh", "--no-onboard", "--bin-dir", binDirectory.toString());

        // then
        result.assertSuccess();
        assertThat(result.output())
                .contains(
                        "Added " + binDirectory + " to PATH in " + home.resolve(".bashrc"),
                        "Added " + binDirectory + " to PATH in " + home.resolve(".profile"));
        assertThat(home.resolve(".bashrc"))
                .content(StandardCharsets.UTF_8)
                .contains("export PATH='" + binDirectory + "':\"$PATH\"");
        assertThat(home.resolve(".profile"))
                .content(StandardCharsets.UTF_8)
                .contains("export PATH='" + binDirectory + "':\"$PATH\"");
    }

    @Test
    void posixUninstallerDryRunReportsManagedPathProfileCleanup() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        Path home = temporaryDirectory.resolve("path-cleanup-dry-run-home");
        Path symphonyHome = temporaryDirectory.resolve("path-cleanup-dry-run-symphony-home");
        Path binDirectory = temporaryDirectory.resolve("path-cleanup-dry-run-bin");
        Files.createDirectories(home);
        String pathLine = "export PATH='" + binDirectory + "':\"$PATH\"";
        String managedBlock =
                """
                before
                # >>> Symphony for Trello PATH >>>
                %s
                # <<< Symphony for Trello PATH <<<
                after
                """
                        .formatted(pathLine);
        Files.writeString(home.resolve(".bashrc"), managedBlock);
        Files.writeString(home.resolve(".profile"), managedBlock);
        Map<String, String> environment = Map.of(
                "HOME", home.toString(),
                "SHELL", "/bin/bash",
                "SYMPHONY_HOME", symphonyHome.toString());

        // when
        ProcessResult result =
                run(environment, "bash", "uninstall.sh", "--dry-run", "--yes", "--bin-dir", binDirectory.toString());

        // then
        result.assertSuccess();
        assertThat(result.output())
                .contains(
                        "WOULD remove managed PATH setup from " + home.resolve(".bashrc"),
                        "WOULD remove managed PATH setup from " + home.resolve(".profile"));
        assertThat(home.resolve(".bashrc")).content(StandardCharsets.UTF_8).isEqualTo(managedBlock);
        assertThat(home.resolve(".profile")).content(StandardCharsets.UTF_8).isEqualTo(managedBlock);
    }

    @Test
    void posixUninstallerKeepsProfileWithUnterminatedManagedPathBlock() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        Path home = temporaryDirectory.resolve("path-cleanup-corrupt-home");
        Path symphonyHome = temporaryDirectory.resolve("path-cleanup-corrupt-symphony-home");
        Path binDirectory = temporaryDirectory.resolve("path-cleanup-corrupt-bin");
        Files.createDirectories(home);
        String profile =
                """
                before
                # >>> Symphony for Trello PATH >>>
                export PATH='%s':"$PATH"
                alias kept='still here'
                """
                        .formatted(binDirectory);
        Files.writeString(home.resolve(".bashrc"), profile);
        Map<String, String> environment = Map.of(
                "HOME", home.toString(),
                "SHELL", "/bin/bash",
                "SYMPHONY_HOME", symphonyHome.toString());

        // when
        ProcessResult result = run(environment, "bash", "uninstall.sh", "--yes", "--bin-dir", binDirectory.toString());

        // then
        result.assertSuccess();
        assertThat(result.output())
                .contains("SKIP  managed PATH setup in " + home.resolve(".bashrc") + " is missing its end marker");
        assertThat(home.resolve(".bashrc")).content(StandardCharsets.UTF_8).isEqualTo(profile);
    }

    @Test
    void posixUninstallerKeepsManagedPathBlocksForDifferentBinDirectories() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        Path home = temporaryDirectory.resolve("path-cleanup-other-bin-home");
        Path symphonyHome = temporaryDirectory.resolve("path-cleanup-other-bin-symphony-home");
        Path binDirectory = temporaryDirectory.resolve("path-cleanup-other-bin-current");
        Path otherBinDirectory = temporaryDirectory.resolve("path-cleanup-other-bin-other");
        Files.createDirectories(home);
        String currentPathLine = "export PATH='" + binDirectory + "':\"$PATH\"";
        String otherPathLine = "export PATH='" + otherBinDirectory + "':\"$PATH\"";
        String profile =
                """
                before
                # >>> Symphony for Trello PATH >>>
                %s
                # <<< Symphony for Trello PATH <<<
                middle
                # >>> Symphony for Trello PATH >>>
                %s
                # <<< Symphony for Trello PATH <<<
                after
                """
                        .formatted(otherPathLine, currentPathLine);
        Files.writeString(home.resolve(".bashrc"), profile);

        // when
        ProcessResult result = runPosixUninstall(home, symphonyHome, binDirectory);

        // then
        result.assertSuccess();
        assertRemovedPathSetup(result, home);
        assertThat(home.resolve(".bashrc"))
                .content(StandardCharsets.UTF_8)
                .isEqualTo(
                        """
                        before
                        # >>> Symphony for Trello PATH >>>
                        %s
                        # <<< Symphony for Trello PATH <<<
                        middle
                        after
                        """
                                .formatted(otherPathLine));
    }

    @Test
    void posixUninstallerContinuesWhenManagedPathProfileCannotBeWritten() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        Path home = temporaryDirectory.resolve("path-cleanup-read-only-home");
        Path symphonyHome = temporaryDirectory.resolve("path-cleanup-read-only-symphony-home");
        Path binDirectory = temporaryDirectory.resolve("path-cleanup-read-only-bin");
        Files.createDirectories(home);
        String pathLine = "export PATH='" + binDirectory + "':\"$PATH\"";
        String profile =
                """
                before
                # >>> Symphony for Trello PATH >>>
                %s
                # <<< Symphony for Trello PATH <<<
                after
                """
                        .formatted(pathLine);
        Path shellProfile = home.resolve(".bashrc");
        Files.writeString(shellProfile, profile);
        assertThat(shellProfile.toFile().setWritable(false, false))
                .as("the profile is made non-writable for the cleanup-failure scenario")
                .isTrue();
        assumeFalse(Files.isWritable(shellProfile));
        Map<String, String> environment = Map.of(
                "HOME", home.toString(),
                "SHELL", "/bin/bash",
                "SYMPHONY_HOME", symphonyHome.toString());

        // when
        ProcessResult result = run(environment, "bash", "uninstall.sh", "--yes", "--bin-dir", binDirectory.toString());

        // then
        result.assertSuccess();
        assertThat(result.output())
                .contains("NOTE  Could not remove managed PATH setup from " + home.resolve(".bashrc"));
        assertThat(shellProfile).content(StandardCharsets.UTF_8).isEqualTo(profile);
    }

    @Test
    void posixUninstallerContinuesWhenManagedPathProfileCannotBeRead() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        assumeFalse(isWindows());
        assumeTrue(commandExists("runuser"));
        assumeTrue(canRunAsNobody());
        Path home = temporaryDirectory.resolve("path-cleanup-unreadable-home");
        Path symphonyHome = temporaryDirectory.resolve("path-cleanup-unreadable-symphony-home");
        Path binDirectory = temporaryDirectory.resolve("path-cleanup-unreadable-bin");
        temporaryDirectory.toFile().setReadable(true, false);
        temporaryDirectory.toFile().setExecutable(true, false);
        Files.createDirectories(home);
        home.toFile().setReadable(true, false);
        home.toFile().setExecutable(true, false);
        String pathLine = "export PATH='" + binDirectory + "':\"$PATH\"";
        String profile =
                """
                before
                # >>> Symphony for Trello PATH >>>
                %s
                # <<< Symphony for Trello PATH <<<
                after
                """
                        .formatted(pathLine);
        Path shellProfile = home.resolve(".bashrc");
        Files.writeString(shellProfile, profile);
        assertThat(shellProfile.toFile().setReadable(false, false))
                .as("the profile is made unreadable for the cleanup-failure scenario")
                .isTrue();

        // when
        ProcessResult result = run(
                Map.of(),
                "runuser",
                "-u",
                "nobody",
                "--",
                "env",
                "HOME=" + home,
                "SHELL=/bin/bash",
                "SYMPHONY_HOME=" + symphonyHome,
                "bash",
                "uninstall.sh",
                "--yes",
                "--bin-dir",
                binDirectory.toString());

        // then
        result.assertSuccess();
        assertThat(result.output())
                .contains("NOTE  Could not remove managed PATH setup from " + home.resolve(".bashrc"));
        assertThat(shellProfile).content(StandardCharsets.UTF_8).isEqualTo(profile);
    }

    private static ProcessResult runPosixUninstall(Path home, Path symphonyHome, Path binDirectory) throws Exception {
        return run(
                uninstallEnvironment(home, symphonyHome),
                "bash",
                "uninstall.sh",
                "--yes",
                "--bin-dir",
                binDirectory.toString());
    }

    private static Map<String, String> uninstallEnvironment(Path home, Path symphonyHome) {
        return Map.of("HOME", home.toString(), "SHELL", "/bin/bash", "SYMPHONY_HOME", symphonyHome.toString());
    }

    private static void assertRemovedPathSetup(ProcessResult result, Path home) {
        assertThat(result.output()).contains("OK  Removed managed PATH setup from " + home.resolve(".bashrc"));
    }

    private static boolean canRunAsNobody() {
        try {
            return run(Map.of(), "runuser", "-u", "nobody", "--", "true").exitCode() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    void posixInstallerContinuesWhenAutomaticPathSetupCannotWriteProfile() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        assumeTrue(commandExists("git"));
        Path sourceRepository = createSourceRepository(temporaryDirectory);
        Path fakeBin = createFakeToolchain(temporaryDirectory);
        Path homeFile = temporaryDirectory.resolve("not-a-directory-home");
        Path symphonyHome = temporaryDirectory.resolve("path-unwritable-symphony-home");
        Path binDirectory = temporaryDirectory.resolve("path-unwritable-bin");
        Path fakeLog = temporaryDirectory.resolve("path-unwritable.log");
        Files.writeString(homeFile, "not a directory\n");
        Map<String, String> environment = Map.of(
                "PATH", fakeBin + File.pathSeparator + System.getenv("PATH"),
                "HOME", homeFile.toString(),
                "SHELL", "/bin/bash",
                "SYMPHONY_TRELLO_REPO_URL", sourceRepository.toUri().toString(),
                "SYMPHONY_HOME", symphonyHome.toString(),
                "SYMPHONY_FAKE_LOG", fakeLog.toString());

        // when
        ProcessResult result =
                run(environment, "bash", "install.sh", "--no-onboard", "--bin-dir", binDirectory.toString());

        // then
        result.assertSuccess();
        assertThat(result.output())
                .contains(
                        "NOTE  Could not update PATH in " + homeFile.resolve(".bashrc"),
                        "Add this line to a shell profile file so future shells can run symphony-trello:",
                        "export PATH='" + binDirectory + "':\"$PATH\"");
    }

    @Test
    void posixInstallerDryRunOffersJava25WhenJava24IsOnPath() throws Exception {
        // given
        assumeTrue(Files.exists(Path.of("/bin/bash")));
        Path fakeBin = temporaryDirectory.resolve("java-24-bin");
        Files.createDirectories(fakeBin);
        String success = """
                #!/bin/sh
                exit 0
                """;
        writeExecutable(fakeBin.resolve("git"), success);
        writeExecutable(fakeBin.resolve("codex"), success);
        writeExecutable(fakeBin.resolve("apt-get"), success);
        writeCommandProxy(fakeBin, "sed", "/bin/sed");
        writeCommandProxy(fakeBin, "head", "/usr/bin/head");
        writeExecutable(
                fakeBin.resolve("java"),
                """
                #!/bin/sh
                echo 'openjdk version "24.0.2"' >&2
                """);
        writeExecutable(
                fakeBin.resolve("javac"),
                """
                #!/bin/sh
                echo 'javac 24.0.2'
                """);
        Map<String, String> environment = Map.of(
                "PATH", fakeBin.toString(),
                "SYMPHONY_TRELLO_TEST_EUID", "0",
                "SYMPHONY_TRELLO_TEST_OS", "Linux",
                "SYMPHONY_TRELLO_TEST_ARCH", "x86_64");

        // when
        ProcessResult result = run(environment, "/bin/bash", "install.sh", "--dry-run", "--no-onboard");

        // then
        result.assertSuccess();
        assertThat(result.output())
                .contains(
                        "NEEDED  Java 25+ JDK",
                        "OK      Codex CLI available",
                        "WOULD offer to install Java 25+ JDK with: apt-get update && apt-get install -y openjdk-25-jdk")
                .doesNotContain("WOULD offer to install Git");
    }

    @Test
    void posixInstallerProposesPackageManagerCommandWithoutSudoWhenRoot() throws Exception {
        // given
        assumeTrue(Files.exists(Path.of("/bin/bash")));
        assumeTrue(commandExists("script"));
        Path installScript = Path.of("install.sh").toAbsolutePath();
        Path fakeBin = temporaryDirectory.resolve("package-manager-bin");
        Files.createDirectories(fakeBin);
        writeExecutable(
                fakeBin.resolve("apt-get"),
                """
                #!/usr/bin/env bash
                exit 0
                """);
        Map<String, String> environment = Map.of(
                "PATH", fakeBin.toString(),
                "SYMPHONY_TRELLO_TEST_EUID", "0",
                "SYMPHONY_TRELLO_TEST_OS", "Linux",
                "SYMPHONY_TRELLO_TEST_ARCH", "x86_64");

        // when
        ProcessResult result = runWithPseudoTerminal(
                environment,
                "n\n",
                "/bin/bash " + shellQuote(installScript.toString()) + " --no-onboard --from-source");

        // then
        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.output())
                .contains("Git is missing.", "Proposed install command:")
                .contains("  apt-get update && apt-get install -y git")
                .doesNotContain("sudo apt-get");
    }

    @Test
    void posixInstallerExplainsManualRootCommandWhenNonRootWithoutPrivilegeHelper() throws Exception {
        // given
        assumeTrue(Files.exists(Path.of("/bin/bash")));
        Path fakeBin = temporaryDirectory.resolve("package-manager-without-sudo");
        Files.createDirectories(fakeBin);
        writeExecutable(
                fakeBin.resolve("apt-get"),
                """
                #!/usr/bin/env bash
                exit 0
                """);
        Map<String, String> environment = Map.of(
                "PATH", fakeBin.toString(),
                "SYMPHONY_TRELLO_TEST_EUID", "1000",
                "SYMPHONY_TRELLO_TEST_OS", "Linux",
                "SYMPHONY_TRELLO_TEST_ARCH", "x86_64");

        // when
        ProcessResult result = run(environment, "/bin/bash", "install.sh", "--no-onboard", "--from-source");

        // then
        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.output())
                .contains(
                        "Git is missing.",
                        "Automatic install requires root, sudo, or doas.",
                        "Run this command as root:",
                        "  apt-get update && apt-get install -y git")
                .doesNotContain("Proposed install command:", "sudo apt-get");
    }

    @Test
    void posixInstallerDryRunShowsManualPlanWhenNonRootWithoutPrivilegeHelper() throws Exception {
        // given
        assumeTrue(Files.exists(Path.of("/bin/bash")));
        Path fakeBin = temporaryDirectory.resolve("dry-run-package-manager-without-sudo");
        Files.createDirectories(fakeBin);
        writeExecutable(
                fakeBin.resolve("apt-get"),
                """
                #!/usr/bin/env bash
                exit 0
                """);
        Map<String, String> environment = Map.of(
                "PATH", fakeBin.toString(),
                "SYMPHONY_TRELLO_TEST_EUID", "1000",
                "SYMPHONY_TRELLO_TEST_OS", "Linux",
                "SYMPHONY_TRELLO_TEST_ARCH", "x86_64");

        // when
        ProcessResult result =
                run(environment, "/bin/bash", "install.sh", "--dry-run", "--no-onboard", "--from-source");

        // then
        assertThat(result.exitCode()).isZero();
        assertThat(result.output())
                .contains(
                        "NEEDED  Git",
                        "WOULD offer to install Git",
                        "NEEDED  Java 25+ JDK",
                        "WOULD offer to install Java 25+ JDK",
                        "Dry run: no files changed.")
                .doesNotContain("sudo apt-get", "doas apt-get");
    }

    @MethodSource("codexInstallDryRunCases")
    @ParameterizedTest(name = "{0}")
    void posixInstallerDryRunReportsSelectedCodexInstallPath(
            String name, Map<String, String> commandStubs, Map<String, String> extraEnvironment, String expected)
            throws Exception {
        // given
        assumeTrue(Files.exists(Path.of("/bin/bash")));
        Path fakeBin = temporaryDirectory.resolve(name.replaceAll("[^a-zA-Z0-9]+", "-"));
        Files.createDirectories(fakeBin);
        for (Map.Entry<String, String> stub : commandStubs.entrySet()) {
            writeExecutable(fakeBin.resolve(stub.getKey()), stub.getValue());
        }
        Map<String, String> environment = new LinkedHashMap<>();
        environment.put("PATH", fakeBin.toString());
        environment.put("SYMPHONY_TRELLO_TEST_OS", extraEnvironment.getOrDefault("SYMPHONY_TRELLO_TEST_OS", "Linux"));
        environment.put("SYMPHONY_TRELLO_TEST_ARCH", "x86_64");
        environment.putAll(extraEnvironment);

        // when
        ProcessResult result = run(environment, "/bin/bash", "install.sh", "--dry-run");

        // then
        assertThat(result.exitCode()).isZero();
        assertThat(result.output()).contains(expected);
    }

    private static Stream<Arguments> codexInstallDryRunCases() {
        String success = """
                #!/usr/bin/env bash
                exit 0
                """;
        return Stream.of(
                Arguments.of("existing codex", Map.of("codex", success), Map.of(), "OK      Codex CLI available"),
                Arguments.of("npm with node present", Map.of("npm", success), Map.of(), "Node.js/npm installed: yes"),
                Arguments.of("npm needs node", Map.of(), Map.of(), "Node.js/npm installed: no"));
    }

    @Test
    void posixInstallerFindsManagedCodexFromInterruptedPreviousRun() throws Exception {
        // given
        assumeTrue(Files.exists(Path.of("/bin/bash")));
        Path fakeBin = temporaryDirectory.resolve("managed-codex-rerun-tools");
        Files.createDirectories(fakeBin);
        writeExecutable(
                fakeBin.resolve("git"),
                """
                #!/usr/bin/env bash
                exit 0
                """);
        writeExecutable(
                fakeBin.resolve("java"),
                """
                #!/usr/bin/env bash
                echo 'openjdk version "25.0.1"' >&2
                """);
        writeExecutable(
                fakeBin.resolve("javac"),
                """
                #!/usr/bin/env bash
                echo 'javac 25.0.1'
                """);
        writeCommandProxy(fakeBin, "sed", "/bin/sed");
        writeCommandProxy(fakeBin, "head", "/usr/bin/head");
        Path symphonyHome = temporaryDirectory.resolve("managed-codex-rerun-home");
        Path managedCodex = symphonyHome.resolve("npm/bin/codex");
        Files.createDirectories(managedCodex.getParent());
        writeExecutable(
                managedCodex, """
                #!/usr/bin/env bash
                exit 0
                """);
        Map<String, String> environment = Map.of(
                "PATH",
                fakeBin.toString(),
                "SYMPHONY_HOME",
                symphonyHome.toString(),
                "SYMPHONY_TRELLO_TEST_OS",
                "Linux",
                "SYMPHONY_TRELLO_TEST_ARCH",
                "x86_64");

        // when
        ProcessResult result = run(environment, "/bin/bash", "install.sh", "--dry-run");

        // then
        assertThat(result.exitCode()).as(result.output()).isZero();
        assertThat(result.output())
                .contains("OK      Codex CLI available")
                .doesNotContain("WOULD offer to install Codex CLI");
    }

    @Test
    void posixInstallerRunsAptUpdateOnlyForFirstAcceptedPackageInstall() throws Exception {
        // given
        assumeTrue(Files.exists(Path.of("/bin/bash")));
        assumeTrue(commandExists("script"));
        Path installScript = Path.of("install.sh").toAbsolutePath();
        Path fakeBin = temporaryDirectory.resolve("apt-update-once-bin");
        Files.createDirectories(fakeBin);
        writeCommandProxy(fakeBin, "bash", "/bin/bash");
        writeCommandProxy(fakeBin, "uname", "/bin/uname");
        writeCommandProxy(fakeBin, "sed", "/bin/sed");
        writeCommandProxy(fakeBin, "head", "/usr/bin/head");
        Path aptLog = temporaryDirectory.resolve("apt-update-once.log");
        writeExecutable(
                fakeBin.resolve("apt-get"),
                """
                #!/bin/bash
                set -euo pipefail
                echo "$*" >> "${SYMPHONY_FAKE_LOG:?}"
                if [[ "${1:-}" == "install" && "$*" == *" git"* ]]; then
                  /bin/cat > "${SYMPHONY_FAKE_BIN:?}/git" <<'GIT'
                #!/bin/bash
                exit 0
                GIT
                  /bin/chmod +x "${SYMPHONY_FAKE_BIN}/git"
                fi
                """);
        Map<String, String> environment = Map.of(
                "PATH",
                fakeBin.toString(),
                "SYMPHONY_FAKE_BIN",
                fakeBin.toString(),
                "SYMPHONY_FAKE_LOG",
                aptLog.toString(),
                "SYMPHONY_TRELLO_TEST_EUID",
                "0",
                "SYMPHONY_TRELLO_TEST_OS",
                "Linux",
                "SYMPHONY_TRELLO_TEST_ARCH",
                "x86_64");

        // when
        ProcessResult result = runWithPseudoTerminal(
                environment,
                "y\nn\n",
                "/bin/bash " + shellQuote(installScript.toString()) + " --no-onboard --from-source");

        // then
        assertThat(result.exitCode()).as(result.output()).isEqualTo(2);
        assertThat(result.output())
                .contains("  apt-get update && apt-get install -y git", "  apt-get install -y openjdk-25-jdk")
                .doesNotContain("  apt-get update && apt-get install -y openjdk-25-jdk");
        assertThat(aptLog)
                .content(StandardCharsets.UTF_8)
                .contains("update", "install -y git")
                .doesNotContain("openjdk-25-jdk");
    }

    @Test
    void posixInstallerGroupsMissingMicroOsPackagesBeforeOneReboot() throws Exception {
        // given
        assumeTrue(Files.exists(Path.of("/bin/bash")));
        assumeTrue(commandExists("script"));
        Path installScript = Path.of("install.sh").toAbsolutePath();
        Path fakeBin = temporaryDirectory.resolve("microos-transactional-bin");
        Path home = temporaryDirectory.resolve("microos-transactional-home");
        Path varPath = temporaryDirectory.resolve("microos-transactional-var");
        Path usersRoot = temporaryDirectory.resolve("microos-transactional-users");
        Path userRoot = usersRoot.resolve("micro-user");
        Files.createDirectories(fakeBin);
        Files.createDirectories(home);
        Files.createDirectories(varPath);
        writeCommandProxy(fakeBin, "bash", "/bin/bash");
        Path commandLog = temporaryDirectory.resolve("microos-transactional.log");
        writeExecutable(
                fakeBin.resolve("sudo"),
                """
                #!/bin/bash
                set -euo pipefail
                echo "sudo $*" >> "${SYMPHONY_FAKE_LOG:?}"
                "$@"
                """);
        writeExecutable(
                fakeBin.resolve("transactional-update"),
                """
                #!/bin/bash
                set -euo pipefail
                echo "transactional-update $*" >> "${SYMPHONY_FAKE_LOG:?}"
                """);
        writeExecutable(
                fakeBin.resolve("install"),
                """
                #!/bin/bash
                set -euo pipefail
                directory="${@: -1}"
                echo "install $*" >> "${SYMPHONY_FAKE_LOG:?}"
                mkdir -p "$directory"
                """);
        Map<String, String> environment = new LinkedHashMap<>(microOsLayoutEnvironment(home, varPath, usersRoot));
        environment.put("PATH", fakeBin.toString());
        environment.put("SYMPHONY_FAKE_LOG", commandLog.toString());
        environment.put("SYMPHONY_TRELLO_TEST_EUID", "1000");

        // when
        ProcessResult result = runWithPseudoTerminal(
                environment, "y\n", "/bin/bash " + shellQuote(installScript.toString()) + " --from-source");

        // then
        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.output())
                .contains(
                        "Detected openSUSE MicroOS amd64",
                        "Missing prerequisites need OS package installation:",
                        "  - Git",
                        "  - Java 25+ JDK",
                        "  - Node.js/npm for Codex CLI installation",
                        "Proposed install command:",
                        "  sudo transactional-update --non-interactive pkg install git java-25-openjdk-devel nodejs npm",
                        "Package installation was scheduled with transactional-update.",
                        "Reboot this machine so the new system snapshot becomes active, then rerun this installer.")
                .doesNotContain(
                        "zypper install",
                        "Detected MicroOS-like storage layout.",
                        "Create command:",
                        "Codex CLI install:",
                        "Open a new terminal with Java 25+ on PATH",
                        "Open a new terminal with npm on PATH");
        assertThat(userRoot).doesNotExist();
        assertThat(commandLog)
                .content(StandardCharsets.UTF_8)
                .isEqualTo(
                        """
                        sudo transactional-update --non-interactive pkg install git java-25-openjdk-devel nodejs npm
                        transactional-update --non-interactive pkg install git java-25-openjdk-devel nodejs npm
                        """);
    }

    @Test
    void posixInstallerDryRunGroupsMissingMicroOsPackages() throws Exception {
        // given
        assumeTrue(Files.exists(Path.of("/bin/bash")));
        Path fakeBin = temporaryDirectory.resolve("microos-dry-run-bin");
        Files.createDirectories(fakeBin);
        String success = """
                #!/bin/bash
                exit 0
                """;
        writeExecutable(fakeBin.resolve("sudo"), success);
        writeExecutable(fakeBin.resolve("transactional-update"), success);
        Map<String, String> environment = Map.of(
                "PATH", fakeBin.toString(),
                "SYMPHONY_TRELLO_TEST_EUID", "1000",
                "SYMPHONY_TRELLO_TEST_OS", "Linux",
                "SYMPHONY_TRELLO_TEST_OS_PRETTY_NAME", "openSUSE MicroOS",
                "SYMPHONY_TRELLO_TEST_OS_ID", "opensuse-microos",
                "SYMPHONY_TRELLO_TEST_ARCH", "x86_64");

        // when
        ProcessResult result = run(environment, "/bin/bash", "install.sh", "--dry-run", "--from-source");

        // then
        assertThat(result.exitCode()).isZero();
        assertThat(result.output())
                .contains(
                        "WOULD offer to install missing OS packages:",
                        "          - Git",
                        "          - Java 25+ JDK",
                        "          - Node.js/npm for Codex CLI installation",
                        "          Command: sudo transactional-update --non-interactive pkg install git java-25-openjdk-devel nodejs npm",
                        "WOULD stop after transactional-update and ask you to reboot, then rerun this installer.")
                .doesNotContain(
                        "WOULD offer to install Git",
                        "WOULD offer to install Java 25+ JDK",
                        "WOULD offer to install Codex CLI with Symphony-managed npm:",
                        "WOULD clone or update:",
                        "WOULD build packaged Quarkus app with Maven wrapper",
                        "WOULD install command:",
                        "WOULD run guided setup and start Symphony automatically.");
    }

    @Test
    void posixInstallerUsesTransactionalUpdateForOpenSuseAeonSystems() throws Exception {
        // given
        assumeTrue(Files.exists(Path.of("/bin/bash")));
        Path fakeBin = temporaryDirectory.resolve("aeon-transactional-bin");
        Files.createDirectories(fakeBin);
        String success = """
                #!/bin/bash
                exit 0
                """;
        writeExecutable(fakeBin.resolve("sudo"), success);
        writeExecutable(fakeBin.resolve("transactional-update"), success);
        writeExecutable(fakeBin.resolve("zypper"), success);
        Map<String, String> environment = Map.of(
                "PATH", fakeBin.toString(),
                "SYMPHONY_TRELLO_TEST_EUID", "1000",
                "SYMPHONY_TRELLO_TEST_OS", "Linux",
                "SYMPHONY_TRELLO_TEST_OS_PRETTY_NAME", "openSUSE Aeon",
                "SYMPHONY_TRELLO_TEST_OS_ID", "opensuse-aeon",
                "SYMPHONY_TRELLO_TEST_ARCH", "x86_64");

        // when
        ProcessResult result = run(environment, "/bin/bash", "install.sh", "--dry-run", "--from-source");

        // then
        assertThat(result.exitCode()).isZero();
        assertThat(result.output())
                .contains(
                        "          Command: sudo transactional-update --non-interactive pkg install git java-25-openjdk-devel nodejs npm",
                        "WOULD stop after transactional-update and ask you to reboot, then rerun this installer.")
                .doesNotContain(
                        "zypper install",
                        "WOULD clone or update:",
                        "WOULD build packaged Quarkus app with Maven wrapper",
                        "WOULD install command:",
                        "WOULD run guided setup and start Symphony automatically.");
    }

    @Test
    void posixInstallerUsesOutOfPathTransactionalUpdateForLeapMicroSystems() throws Exception {
        // given
        assumeTrue(Files.exists(Path.of("/bin/bash")));
        Path fakeBin = temporaryDirectory.resolve("leap-micro-bin");
        Files.createDirectories(fakeBin);
        Path sbin = temporaryDirectory.resolve("leap-micro-sbin");
        Files.createDirectories(sbin);
        Path transactionalUpdate = sbin.resolve("transactional-update");
        String success = """
                #!/bin/bash
                exit 0
                """;
        writeExecutable(fakeBin.resolve("sudo"), success);
        writeExecutable(fakeBin.resolve("zypper"), success);
        writeExecutable(transactionalUpdate, success);
        Map<String, String> environment = Map.of(
                "PATH", fakeBin.toString(),
                "SYMPHONY_TRELLO_TEST_EUID", "1000",
                "SYMPHONY_TRELLO_TEST_OS", "Linux",
                "SYMPHONY_TRELLO_TEST_OS_PRETTY_NAME", "openSUSE Leap Micro 6.0",
                "SYMPHONY_TRELLO_TEST_OS_ID", "opensuse-leap-micro",
                "SYMPHONY_TRELLO_TEST_TRANSACTIONAL_UPDATE", transactionalUpdate.toString(),
                "SYMPHONY_TRELLO_TEST_ARCH", "x86_64");

        // when
        ProcessResult result = run(environment, "/bin/bash", "install.sh", "--dry-run", "--from-source");

        // then
        assertThat(result.exitCode()).isZero();
        assertThat(result.output())
                .contains(
                        "          Command: sudo "
                                + transactionalUpdate
                                + " --non-interactive pkg install git java-25-openjdk-devel nodejs npm",
                        "WOULD stop after transactional-update and ask you to reboot, then rerun this installer.")
                .doesNotContain(
                        "zypper install",
                        "WOULD clone or update:",
                        "WOULD build packaged Quarkus app with Maven wrapper",
                        "WOULD install command:",
                        "WOULD run guided setup and start Symphony automatically.");
    }

    @Test
    void posixInstallerKeepsZypperForMutableOpenSuseSystems() throws Exception {
        // given
        assumeTrue(Files.exists(Path.of("/bin/bash")));
        Path fakeBin = temporaryDirectory.resolve("opensuse-zypper-bin");
        Files.createDirectories(fakeBin);
        String success = """
                #!/bin/bash
                exit 0
                """;
        writeExecutable(fakeBin.resolve("sudo"), success);
        writeExecutable(fakeBin.resolve("transactional-update"), success);
        writeExecutable(fakeBin.resolve("zypper"), success);
        Map<String, String> environment = Map.of(
                "PATH", fakeBin.toString(),
                "SYMPHONY_TRELLO_TEST_EUID", "1000",
                "SYMPHONY_TRELLO_TEST_OS", "Linux",
                "SYMPHONY_TRELLO_TEST_OS_PRETTY_NAME", "openSUSE Leap 15.6",
                "SYMPHONY_TRELLO_TEST_OS_ID", "opensuse-leap",
                "SYMPHONY_TRELLO_TEST_ARCH", "x86_64");

        // when
        ProcessResult result = run(environment, "/bin/bash", "install.sh", "--dry-run", "--no-onboard");

        // then
        assertThat(result.exitCode()).isZero();
        assertThat(result.output())
                .contains("WOULD offer to install Java 25+ JDK with: sudo zypper install -y java-25-openjdk-devel")
                .doesNotContain("transactional-update --non-interactive pkg install");
    }

    @Test
    void posixInstallerOffersSingleCodexCommandWhenNodeIsMissing() throws Exception {
        // given
        assumeTrue(Files.exists(Path.of("/bin/bash")));
        assumeTrue(commandExists("script"));
        Path installScript = Path.of("install.sh").toAbsolutePath();
        Path fakeBin = temporaryDirectory.resolve("codex-node-missing-bin");
        Files.createDirectories(fakeBin);
        writeCommandProxy(fakeBin, "bash", "/bin/bash");
        writeCommandProxy(fakeBin, "uname", "/bin/uname");
        writeCommandProxy(fakeBin, "sed", "/bin/sed");
        writeCommandProxy(fakeBin, "head", "/usr/bin/head");
        writeExecutable(
                fakeBin.resolve("git"), """
                #!/bin/bash
                exit 0
                """);
        writeExecutable(
                fakeBin.resolve("java"),
                """
                #!/bin/bash
                echo 'openjdk version "25.0.1"' >&2
                """);
        writeExecutable(
                fakeBin.resolve("javac"),
                """
                #!/bin/bash
                echo 'javac 25.0.1'
                """);
        writeExecutable(
                fakeBin.resolve("apt-get"), """
                #!/bin/bash
                exit 0
                """);
        Path symphonyHome = temporaryDirectory.resolve("codex-node-missing-home");
        Path binDirectory = temporaryDirectory.resolve("codex-node-missing-bin-dir");
        Map<String, String> environment = Map.of(
                "PATH", fakeBin.toString(),
                "SYMPHONY_HOME", symphonyHome.toString(),
                "SYMPHONY_TRELLO_TEST_EUID", "0",
                "SYMPHONY_TRELLO_TEST_OS", "Linux",
                "SYMPHONY_TRELLO_TEST_ARCH", "x86_64");

        // when
        ProcessResult result = runWithPseudoTerminal(
                environment,
                "n\n",
                "/bin/bash " + shellQuote(installScript.toString()) + " --bin-dir "
                        + shellQuote(binDirectory.toString()));

        // then
        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.output())
                .contains(
                        "Codex CLI is missing and needs Node.js with npm.",
                        "Install Codex CLI with Symphony-managed npm.",
                        "Install location: " + symphonyHome.resolve("npm"),
                        "Command link: " + binDirectory.resolve("codex"),
                        "This keeps system-wide npm packages unchanged.",
                        "Node.js/npm install:",
                        "apt-get update && apt-get install -y nodejs npm",
                        "Codex CLI install:",
                        "npm install --global --prefix",
                        "Run now? [y/N]")
                .doesNotContain(
                        "Node.js/npm is missing.",
                        "Selected install path:",
                        "Install method:",
                        "Install Codex CLI this way now? [y/N]",
                        "apt-get update && apt-get install -y nodejs npm && mkdir -p");
    }

    @Test
    void posixInstallerDryRunUsesAbsoluteManagedCodexPathsWhenHomeIsRelative() throws Exception {
        // given
        assumeTrue(Files.exists(Path.of("/bin/bash")));
        Path installScript = Path.of("install.sh").toAbsolutePath();
        Path fakeBin = temporaryDirectory.resolve("relative-home-tools");
        Files.createDirectories(fakeBin);
        String success = """
                #!/usr/bin/env bash
                exit 0
                """;
        writeExecutable(fakeBin.resolve("git"), success);
        writeExecutable(fakeBin.resolve("java"), success);
        writeExecutable(fakeBin.resolve("javac"), success);
        writeExecutable(fakeBin.resolve("npm"), success);
        Path workingDirectory = temporaryDirectory.resolve("relative-home-cwd");
        Files.createDirectories(workingDirectory);
        Map<String, String> environment = Map.of(
                "PATH", fakeBin.toString(),
                "SYMPHONY_HOME", "relative-home",
                "SYMPHONY_TRELLO_TEST_OS", "Linux",
                "SYMPHONY_TRELLO_TEST_ARCH", "x86_64");

        // when
        ProcessResult result = run(environment, workingDirectory, "/bin/bash", installScript.toString(), "--dry-run");

        // then
        assertThat(result.exitCode()).isZero();
        assertThat(result.output()).contains("Install location: " + workingDirectory.resolve("relative-home/npm"));
    }

    @Test
    void posixInstallerRejectsUnsupportedPlatforms() throws Exception {
        // given
        assumeTrue(Files.exists(Path.of("/bin/bash")));
        Map<String, String> environment = Map.of(
                "SYMPHONY_TRELLO_TEST_OS", "FreeBSD",
                "SYMPHONY_TRELLO_TEST_ARCH", "riscv64");

        // when
        ProcessResult result = run(environment, "/bin/bash", "install.sh", "--dry-run", "--no-onboard");

        // then
        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.output())
                .contains(
                        "Unsupported platform: FreeBSD riscv64",
                        "Supported platforms: macOS arm64/amd64, Linux arm64/amd64, and WSL2 through the Linux path.");
    }

    @Test
    void powershellInstallerAcceptsPublicDryRunFlagsWhenAvailable() throws Exception {
        // given
        List<String> pwsh = powershellCommand();
        assumeFalse(pwsh.isEmpty());

        // when
        ProcessResult result = run(
                nonWindowsPowerShellEnvironment(),
                command(pwsh, "-NoProfile", "-File", "./install.ps1", "--dry-run", "--no-onboard")
                        .toArray(String[]::new));

        // then
        result.assertSuccess();
        assertThat(result.output()).contains("Symphony for Trello installer", "Dry run: no files changed.");
    }

    @Test
    void powershellInstallerDryRunPreviewsSetupBeforeManagedWorkerStartupWhenAvailable() throws Exception {
        // given
        List<String> pwsh = powershellCommand();
        assumeFalse(pwsh.isEmpty());

        // when
        ProcessResult result = run(
                nonWindowsPowerShellEnvironment(),
                command(pwsh, "-NoProfile", "-File", "./install.ps1", "--dry-run")
                        .toArray(String[]::new));

        // then
        result.assertSuccess();
        assertThat(result.output())
                .containsSubsequence(
                        "Dry run: no files changed.",
                        "Starting setup...",
                        "WOULD run:",
                        "symphony-trello.ps1 setup-local",
                        "Starting managed workers...",
                        "WOULD write autostart environment snapshot:",
                        "WOULD write Windows autostart launcher:",
                        "WOULD create Windows Scheduled Task:",
                        "WOULD start managed workers through Windows Scheduled Task")
                .doesNotContain("You're good to go");
    }

    @Test
    void powershellInstallerRejectsLeadingVVersionEnvironmentValueWhenAvailable() throws Exception {
        // given
        List<String> pwsh = powershellCommand();
        assumeFalse(pwsh.isEmpty());
        String version = installerDefaultRef().substring(1);
        Map<String, String> environment = new LinkedHashMap<>(nonWindowsPowerShellEnvironment());
        environment.put("SYMPHONY_TRELLO_VERSION", "v" + version);

        // when
        ProcessResult result = run(
                environment,
                command(pwsh, "-NoProfile", "-File", "./install.ps1", "--dry-run", "--no-onboard")
                        .toArray(String[]::new));

        // then
        assertThat(result.exitCode()).isNotZero();
        assertThat(result.output())
                .contains("--version must be a semantic version without the leading v.")
                .doesNotContain("Dry run: no files changed.", "Version: " + version, "vv" + version);
    }

    @Test
    void powershellInstallerRejectsLeadingVVersionArgumentWhenAvailable() throws Exception {
        // given
        List<String> pwsh = powershellCommand();
        assumeFalse(pwsh.isEmpty());
        String version = installerDefaultRef().substring(1);

        // when
        ProcessResult result = run(
                nonWindowsPowerShellEnvironment(),
                command(
                                pwsh,
                                "-NoProfile",
                                "-File",
                                "./install.ps1",
                                "--dry-run",
                                "--no-onboard",
                                "--version",
                                "v" + version)
                        .toArray(String[]::new));

        // then
        assertThat(result.exitCode()).isNotZero();
        assertThat(result.output())
                .contains("--version must be a semantic version without the leading v.")
                .doesNotContain("Dry run: no files changed.", "Version: " + version, "vv" + version);
    }

    @Test
    void powershellInstallerRejectsRemovedUpdatePathOptionWhenAvailable() throws Exception {
        // given
        List<String> pwsh = powershellCommand();
        assumeFalse(pwsh.isEmpty());

        // when
        ProcessResult result = run(
                nonWindowsPowerShellEnvironment(),
                command(pwsh, "-NoProfile", "-File", "./install.ps1", "--dry-run", "--update-path", "--no-onboard")
                        .toArray(String[]::new));

        // then
        assertThat(result.exitCode()).isNotZero();
        assertThat(result.output()).contains("Unknown option: --update-path");
    }

    @Test
    void powershellInstallerValidationErrorsArePlainWhenAvailable() throws Exception {
        // given
        List<String> pwsh = powershellCommand();
        assumeFalse(pwsh.isEmpty());

        // when
        ProcessResult result = run(
                nonWindowsPowerShellEnvironment(),
                command(
                                pwsh,
                                "-NoProfile",
                                "-File",
                                "./install.ps1",
                                "--dry-run",
                                "--no-onboard",
                                "--version",
                                "latest")
                        .toArray(String[]::new));

        // then
        assertThat(result.exitCode()).isNotZero();
        assertThat(result.output())
                .contains("--version must be a semantic version without the leading v.")
                .doesNotContain("Exception:", "Line |", "install.ps1:", "throw \"--version");
    }

    @Test
    void powershellInstallerAcceptsPublicFlagsThroughScriptblockWhenAvailable() throws Exception {
        // given
        List<String> pwsh = powershellCommand();
        assumeFalse(pwsh.isEmpty());

        // when
        ProcessResult result = run(
                nonWindowsPowerShellEnvironment(),
                command(
                                pwsh,
                                "-NoProfile",
                                "-Command",
                                "& ([scriptblock]::Create((Get-Content -Raw './install.ps1'))) --dry-run --no-onboard")
                        .toArray(String[]::new));

        // then
        result.assertSuccess();
        assertThat(result.output()).contains("Symphony for Trello installer", "Dry run: no files changed.");
    }

    @Test
    void powershellInstallerRejectsLeadingVVersionThroughScriptblockWhenAvailable() throws Exception {
        // given
        List<String> pwsh = powershellCommand();
        assumeFalse(pwsh.isEmpty());
        String version = installerDefaultRef().substring(1);

        // when
        ProcessResult result = run(
                nonWindowsPowerShellEnvironment(),
                command(
                                pwsh,
                                "-NoProfile",
                                "-Command",
                                "& ([scriptblock]::Create((Get-Content -Raw './install.ps1'))) "
                                        + "--dry-run --no-onboard --version v" + version)
                        .toArray(String[]::new));

        // then
        assertThat(result.exitCode()).isNotZero();
        assertThat(result.output())
                .contains("--version must be a semantic version without the leading v.")
                .doesNotContain("Dry run: no files changed.", "Version: " + version, "vv" + version);
    }

    @Test
    void powershellInstallerRejectsBlankPathValuesThroughScriptblockWhenAvailable() throws Exception {
        // given
        List<String> pwsh = powershellCommand();
        assumeFalse(pwsh.isEmpty());

        // when
        ProcessResult result = run(
                nonWindowsPowerShellEnvironment(),
                command(
                                pwsh,
                                "-NoProfile",
                                "-Command",
                                "& ([scriptblock]::Create((Get-Content -Raw './install.ps1'))) "
                                        + "--dry-run --no-onboard --prefix '' --bin-dir ''")
                        .toArray(String[]::new));

        // then
        assertThat(result.exitCode()).isNotZero();
        assertThat(result.output())
                .contains("Missing value for --prefix")
                .doesNotContain("Install:", "WOULD clone or update:", "/--bin-dir");
    }

    @Test
    void powershellInstallerRejectsUnsafeAppPathsWhenAvailable() throws Exception {
        // given
        List<String> pwsh = powershellCommand();
        assumeFalse(pwsh.isEmpty());
        Path home = temporaryDirectory.resolve("ps-install-app-home");
        Path symphonyHome = temporaryDirectory.resolve("ps-install-app-symphony-home");
        Path file = temporaryDirectory.resolve("ps-install-app-file");
        Path symlink = temporaryDirectory.resolve("ps-install-app-link");
        Path safeBin = temporaryDirectory.resolve("ps-install-app-bin");
        Files.createDirectories(home);
        Files.writeString(file, "not a directory");
        Files.createSymbolicLink(symlink, temporaryDirectory.resolve("ps-install-app-target"));
        Map<String, String> environment = new LinkedHashMap<>(nonWindowsPowerShellEnvironment());
        environment.put("USERPROFILE", home.toString());
        environment.put("SYMPHONY_HOME", symphonyHome.toString());
        List<UnsafeInstallerPath> cases = powershellUnsafeAppPaths(
                file,
                symlink,
                "ps-install-app-link",
                new UnsafeInstallerPath(
                        "local-data-overlap",
                        symphonyHome.toString(),
                        "--prefix must not overlap Symphony config, workspace"));

        // when
        List<ProcessResult> results = runPowerShellAppPathCases(
                environment, pwsh, "./install.ps1", List.of("--dry-run", "--no-onboard"), cases, safeBin);

        // then
        assertUnsafeAppPathFailures(
                cases, results, OutputStyle.NORMALIZED, "Install:", "WOULD clone or update:", "Command PATH setup");
    }

    @Test
    void powershellInstallerAcceptsDedicatedAppPathDirectlyUnderHomeWhenAvailable() throws Exception {
        // given
        List<String> pwsh = powershellCommand();
        assumeFalse(pwsh.isEmpty());
        Path home = temporaryDirectory.resolve("ps-install-home-child-home");
        Path app = home.resolve("symphony-trello");
        Path safeBin = temporaryDirectory.resolve("ps-install-home-child-bin");
        Files.createDirectories(home);
        Map<String, String> environment = new LinkedHashMap<>(nonWindowsPowerShellEnvironment());
        environment.put("USERPROFILE", home.toString());

        // when
        ProcessResult result = run(
                environment,
                command(
                                pwsh,
                                "-NoProfile",
                                "-File",
                                "./install.ps1",
                                "--dry-run",
                                "--no-onboard",
                                "--prefix",
                                app.toString(),
                                "--bin-dir",
                                safeBin.toString())
                        .toArray(String[]::new));

        // then
        result.assertSuccess();
        assertThat(result.output())
                .contains(
                        "Install:",
                        app.getFileName().toString(),
                        "WOULD unpack release archive to:",
                        app.getFileName().toString());
    }

    @Test
    void powershellInstallerRejectsUnsafeSymphonyHomeWhenAvailable() throws Exception {
        // given
        List<String> pwsh = powershellCommand();
        assumeFalse(pwsh.isEmpty());
        Path home = temporaryDirectory.resolve("ps-install-home-base-home");
        Path safeBin = temporaryDirectory.resolve("ps-install-home-base-bin");
        Files.createDirectories(home);

        List<UnsafeInstallerPath> cases = List.of(
                new UnsafeInstallerPath(
                        "root", platformRootPath(), "--prefix must point to a dedicated app checkout directory."),
                new UnsafeInstallerPath(
                        "control", temporaryDirectory.resolve("home") + "\nline", "control characters"));

        // when
        List<ProcessResult> results = cases.stream()
                .map(appPath -> {
                    Map<String, String> environment = new LinkedHashMap<>(nonWindowsPowerShellEnvironment());
                    environment.put("HOME", home.toString());
                    environment.put("USERPROFILE", home.toString());
                    environment.put("SYMPHONY_HOME", appPath.value());
                    return runUnchecked(
                            environment,
                            command(
                                            pwsh,
                                            "-NoProfile",
                                            "-File",
                                            "./install.ps1",
                                            "--dry-run",
                                            "--no-onboard",
                                            "--bin-dir",
                                            safeBin.toString())
                                    .toArray(String[]::new));
                })
                .toList();

        // then
        for (int index = 0; index < cases.size(); index++) {
            UnsafeInstallerPath appPath = cases.get(index);
            ProcessResult result = results.get(index);
            assertThat(result.exitCode()).as(appPath.name()).isNotZero();
            assertThat(normalizedWhitespace(result.output()))
                    .as(appPath.name())
                    .contains(appPath.expectedMessage())
                    .doesNotContain("Install:", "WOULD clone or update:", "Command PATH setup");
        }
    }

    @Test
    void powershellInstallerRejectsUnsafeCommandDirectoriesWhenAvailable() throws Exception {
        // given
        List<String> pwsh = powershellCommand();
        assumeFalse(pwsh.isEmpty());
        Path home = temporaryDirectory.resolve("ps-install-bin-home");
        Path symphonyHome = temporaryDirectory.resolve("ps-install-bin-symphony-home");
        Path app = temporaryDirectory.resolve("ps-install-bin-app");
        Path file = temporaryDirectory.resolve("ps-install-bin-file");
        Path symlink = temporaryDirectory.resolve("ps-install-bin-workspaces-link");
        Files.createDirectories(home);
        Files.createDirectories(symphonyHome.resolve("workspaces"));
        Files.writeString(file, "not a directory");
        Files.createSymbolicLink(symlink, symphonyHome.resolve("workspaces"));
        Map<String, String> environment = new LinkedHashMap<>(nonWindowsPowerShellEnvironment());
        environment.put("USERPROFILE", home.toString());
        environment.put("SYMPHONY_HOME", symphonyHome.toString());
        List<UnsafeCommandDirectory> cases = powershellUnsafeCommandDirectories(
                file,
                symlink,
                "ps-install-bin-workspaces-link",
                new UnsafeCommandDirectory(
                        "workspace-overlap",
                        symphonyHome.resolve("workspaces").toString(),
                        "--bin-dir must not overlap Symphony app, config, workspace"));

        // when
        List<ProcessResult> results = cases.stream()
                .map(commandDirectory -> runUnchecked(
                        environment,
                        command(
                                        pwsh,
                                        "-NoProfile",
                                        "-File",
                                        "./install.ps1",
                                        "--dry-run",
                                        "--no-onboard",
                                        "--prefix",
                                        app.toString(),
                                        "--bin-dir",
                                        commandDirectory.value())
                                .toArray(String[]::new)))
                .toList();

        // then
        for (int index = 0; index < cases.size(); index++) {
            UnsafeCommandDirectory commandDirectory = cases.get(index);
            ProcessResult result = results.get(index);
            assertThat(result.exitCode()).as(commandDirectory.name()).isNotZero();
            assertThat(normalizedWhitespace(result.output()))
                    .as(commandDirectory.name())
                    .contains(commandDirectory.expectedMessage())
                    .doesNotContain("WOULD install CLI executable", "Command PATH setup");
        }
    }

    @Test
    void powershellInstallerAllowsDefaultCommandDirectoryWhenRunFromThatDirectoryWhenAvailable() throws Exception {
        // given
        List<String> pwsh = powershellCommandForDifferentWorkingDirectory();
        assumeFalse(pwsh.isEmpty());
        Path installScript = Path.of("install.ps1").toAbsolutePath();
        Path home = temporaryDirectory.resolve("ps-install-run-from-bin-home");
        Path binDirectory = home.resolve(".local/bin");
        Files.createDirectories(binDirectory);
        Map<String, String> environment = new LinkedHashMap<>(nonWindowsPowerShellEnvironment());
        environment.put("HOME", home.toString());
        environment.put("USERPROFILE", home.toString());

        // when
        ProcessResult result = run(
                environment,
                binDirectory,
                command(pwsh, "-NoProfile", "-File", installScript.toString(), "--dry-run", "--no-onboard")
                        .toArray(String[]::new));

        // then
        result.assertSuccess();
        assertThat(result.output()).contains("Dry run: no files changed.");
    }

    @MethodSource("invalidPowerShellInstallerSourceInputs")
    @ParameterizedTest(name = "{0}")
    void powershellInstallerRejectsInvalidSourceInputsBeforeDryRunPlanWhenAvailable(
            String name, String[] sourceArgs, String expectedMessage) throws Exception {
        // given
        List<String> pwsh = powershellCommand();
        assumeFalse(pwsh.isEmpty());
        Path prefix = temporaryDirectory
                .resolve(name.replaceAll("[^A-Za-z0-9]+", "-"))
                .resolve("app");
        String[] command = Stream.concat(
                        command(
                                pwsh,
                                "-NoProfile",
                                "-File",
                                "./install.ps1",
                                "--dry-run",
                                "--no-onboard",
                                "--prefix",
                                prefix.toString())
                                .stream(),
                        Stream.of(sourceArgs))
                .toArray(String[]::new);

        // when
        ProcessResult result = run(nonWindowsPowerShellEnvironment(), command);

        // then
        assertThat(result.exitCode()).isNotZero();
        assertThat(result.output()).contains(expectedMessage).doesNotContain("WOULD clone or update:");
        assertThat(prefix).doesNotExist();
    }

    private static Stream<Arguments> invalidPowerShellInstallerSourceInputs() {
        return Stream.of(
                Arguments.of("blank repo", new String[] {"--repo", ""}, "--repo must not be blank."),
                Arguments.of(
                        "malformed repo",
                        new String[] {"--repo", "not-a-url"},
                        "--repo must be a URL or existing local Git repository path."),
                Arguments.of("blank ref", new String[] {"--ref", ""}, "--ref must not be blank."),
                Arguments.of("path traversal ref", new String[] {"--ref", "../main"}, "Git namespace prefixes"),
                Arguments.of("remote namespace ref", new String[] {"--ref", "origin/main"}, "Git namespace prefixes"),
                Arguments.of("lock component ref", new String[] {"--ref", "foo.lock/bar"}, "Git namespace prefixes"),
                Arguments.of("trailing dot ref", new String[] {"--ref", "foo."}, "Git namespace prefixes"),
                Arguments.of(
                        "unsupported ref characters",
                        new String[] {"--ref", "main;echo-x"},
                        "--ref contains unsupported characters."));
    }

    @Test
    void powershellInstallerAcceptsScpStyleRepoAndRedactsCredentialUrlBeforeDryRunPlanWhenAvailable() throws Exception {
        // given
        List<String> pwsh = powershellCommand();
        assumeFalse(pwsh.isEmpty());

        // when
        ProcessResult scpRepo = run(
                nonWindowsPowerShellEnvironment(),
                command(
                                pwsh,
                                "-NoProfile",
                                "-File",
                                "./install.ps1",
                                "--dry-run",
                                "--no-onboard",
                                "--repo",
                                "deploy@example.com:org/repo.git")
                        .toArray(String[]::new));
        ProcessResult credentialRepo = run(
                nonWindowsPowerShellEnvironment(),
                command(
                                pwsh,
                                "-NoProfile",
                                "-File",
                                "./install.ps1",
                                "--dry-run",
                                "--no-onboard",
                                "--repo",
                                "https://user:secret-token@example.com/org/repo.git")
                        .toArray(String[]::new));
        ProcessResult buildMetadataRef = run(
                nonWindowsPowerShellEnvironment(),
                command(
                                pwsh,
                                "-NoProfile",
                                "-File",
                                "./install.ps1",
                                "--dry-run",
                                "--no-onboard",
                                "--ref",
                                "v1.2.3+hotfix.1")
                        .toArray(String[]::new));

        // then
        scpRepo.assertSuccess();
        assertThat(scpRepo.output()).contains("Repository: deploy@example.com:org/repo.git");
        credentialRepo.assertSuccess();
        assertThat(credentialRepo.output())
                .contains("Repository: https://<redacted>@example.com/org/repo.git")
                .doesNotContain("user:secret-token");
        buildMetadataRef.assertSuccess();
        assertThat(buildMetadataRef.output()).contains("Ref: v1.2.3+hotfix.1");
    }

    @Test
    void powershellInstallerRejectsExistingNonGitRepoPathBeforeDryRunPlanWhenAvailable() throws Exception {
        // given
        List<String> pwsh = powershellCommand();
        assumeFalse(pwsh.isEmpty());
        Path notGitRepository = temporaryDirectory.resolve("ps-not-git-repository");
        Path prefix = temporaryDirectory.resolve("ps-non-git-repo-prefix").resolve("app");
        Files.createDirectories(notGitRepository);

        // when
        ProcessResult result = run(
                nonWindowsPowerShellEnvironment(),
                command(
                                pwsh,
                                "-NoProfile",
                                "-File",
                                "./install.ps1",
                                "--dry-run",
                                "--no-onboard",
                                "--prefix",
                                prefix.toString(),
                                "--repo",
                                notGitRepository.toString())
                        .toArray(String[]::new));

        // then
        assertThat(result.exitCode()).isNotZero();
        assertThat(result.output())
                .contains("--repo must be a URL or existing local Git repository path.")
                .doesNotContain("WOULD clone or update:");
        assertThat(prefix).doesNotExist();
    }

    @Test
    void powershellInstallerAcceptsLocalGitRepoPathWithSpacesBeforeDryRunPlanWhenAvailable() throws Exception {
        // given
        List<String> pwsh = powershellCommand();
        assumeFalse(pwsh.isEmpty());
        Path localRepository = temporaryDirectory.resolve("ps local repo with spaces");
        Path prefix = temporaryDirectory.resolve("ps-local-repo-space-prefix").resolve("app");
        Files.createDirectories(localRepository.resolve(".git"));

        // when
        ProcessResult result = run(
                nonWindowsPowerShellEnvironment(),
                command(
                                pwsh,
                                "-NoProfile",
                                "-File",
                                "./install.ps1",
                                "--dry-run",
                                "--no-onboard",
                                "--prefix",
                                prefix.toString(),
                                "--repo",
                                localRepository.toString())
                        .toArray(String[]::new));

        // then
        result.assertSuccess();
        assertThat(result.output())
                .contains("Repository:", localRepository.getFileName().toString(), "WOULD clone or update:");
        assertThat(prefix).doesNotExist();
    }

    @Test
    void powershellInstallerRejectsBlankSourceValueThroughScriptblockWhenAvailable() throws Exception {
        // given
        List<String> pwsh = powershellCommand();
        assumeFalse(pwsh.isEmpty());
        Path prefix = temporaryDirectory.resolve("ps-scriptblock-source").resolve("app");

        // when
        ProcessResult result = run(
                nonWindowsPowerShellEnvironment(),
                command(
                                pwsh,
                                "-NoProfile",
                                "-Command",
                                "& ([scriptblock]::Create((Get-Content -Raw './install.ps1'))) --dry-run --no-onboard"
                                        + " --repo '' --prefix "
                                        + powerShellLiteral(prefix.toString()))
                        .toArray(String[]::new));

        // then
        assertThat(result.exitCode()).isNotZero();
        assertThat(result.output()).contains("Missing value for --repo").doesNotContain("WOULD clone or update:");
        assertThat(prefix).doesNotExist();
    }

    @Test
    void powershellInstallerAcceptsDashPrefixedRelativePathValuesThroughScriptblockWhenAvailable() throws Exception {
        // given
        List<String> pwsh = powershellCommand();
        assumeFalse(pwsh.isEmpty());
        Path binDirectory = temporaryDirectory.resolve("-dash-bin");
        Path prefix = temporaryDirectory.resolve("-dash-prefix-app");

        // when
        ProcessResult result = run(
                nonWindowsPowerShellEnvironment(),
                command(
                                pwsh,
                                "-NoProfile",
                                "-Command",
                                "& ([scriptblock]::Create((Get-Content -Raw './install.ps1'))) --dry-run --no-onboard"
                                        + " --prefix "
                                        + powerShellLiteral(prefix.toString())
                                        + " --bin-dir "
                                        + powerShellLiteral(binDirectory.toString()))
                        .toArray(String[]::new));

        // then
        result.assertSuccess();
        assertThat(result.output())
                .contains(
                        prefix.getFileName().toString(),
                        binDirectory.getFileName().toString(),
                        "Dry run: no files changed.");
    }

    @Test
    void powershellInstallerAcceptsPublicValueFlagsWhenAvailable() throws Exception {
        // given
        List<String> pwsh = powershellCommand();
        assumeFalse(pwsh.isEmpty());
        Path home = temporaryDirectory.resolve("ps home $value (quoted)");
        Path bin = temporaryDirectory.resolve("ps bin & tools");

        // when
        ProcessResult result = run(
                nonWindowsPowerShellEnvironment(),
                command(
                                pwsh,
                                "-NoProfile",
                                "-File",
                                "./install.ps1",
                                "--dry-run",
                                "--no-onboard",
                                "--prefix",
                                home.resolve("app").toString(),
                                "--bin-dir",
                                bin.toString(),
                                "--repo",
                                "https://example.invalid/symphony-trello.git",
                                "--ref",
                                "feature/test-ref")
                        .toArray(String[]::new));

        // then
        result.assertSuccess();
        assertThat(result.output())
                .contains(
                        "Install:",
                        home.resolve("app").getFileName().toString(),
                        "Repository: https://example.invalid/symphony-trello.git",
                        "Ref: feature/test-ref",
                        "Command:",
                        bin.getFileName() + "\\symphony-trello.ps1",
                        "WOULD clone or update:",
                        "WOULD build packaged Quarkus app with Maven wrapper",
                        "WOULD install CLI executable:",
                        bin.getFileName() + "\\symphony-trello.ps1",
                        "Dry run: no files changed.",
                        "Symphony would install the command here:")
                .doesNotContain("Unknown option");
    }

    @Test
    void powershellInstallerDryRunReportsDefaultUserPathSetupWhenAvailable() throws Exception {
        // given
        List<String> pwsh = powershellCommand();
        assumeFalse(pwsh.isEmpty());
        Path bin = temporaryDirectory.resolve("ps default path bin");

        // when
        ProcessResult result = run(
                nonWindowsPowerShellEnvironment(),
                command(
                                pwsh,
                                "-NoProfile",
                                "-File",
                                "./install.ps1",
                                "--dry-run",
                                "--no-onboard",
                                "--bin-dir",
                                bin.toString())
                        .toArray(String[]::new));

        // then
        result.assertSuccess();
        assertThat(result.output()).contains("WOULD add", bin.getFileName().toString(), "to the current user PATH.");
    }

    @Test
    void powershellInstallerDryRunSkipsPathSetupWhenBinDirIsAlreadyOnPathWhenAvailable() throws Exception {
        // given
        List<String> pwsh = powershellCommand();
        assumeFalse(pwsh.isEmpty());
        Path bin = temporaryDirectory.resolve("ps path present bin");
        Map<String, String> environment = new LinkedHashMap<>(nonWindowsPowerShellEnvironment());
        environment.put("PATH", bin + File.pathSeparator + System.getenv("PATH"));

        // when
        ProcessResult result = run(
                environment,
                command(
                                pwsh,
                                "-NoProfile",
                                "-File",
                                "./install.ps1",
                                "--dry-run",
                                "--no-onboard",
                                "--bin-dir",
                                bin.toString())
                        .toArray(String[]::new));

        // then
        result.assertSuccess();
        assertThat(result.output()).doesNotContain("WOULD offer to add", "not on PATH");
    }

    @Test
    void powershellInstallerRejectsNonWindowsWithoutTestOverrideWhenAvailable() throws Exception {
        // given
        assumeFalse(isWindows());
        List<String> pwsh = powershellCommand();
        assumeFalse(pwsh.isEmpty());
        Map<String, String> environment = Map.of("SYMPHONY_TRELLO_PWSH_ALLOW_NON_WINDOWS_TEST_RUNTIME", "0");

        // when
        ProcessResult result = run(
                environment,
                command(pwsh, "-NoProfile", "-File", "./install.ps1", "--dry-run", "--no-onboard")
                        .toArray(String[]::new));

        // then
        assertThat(result.exitCode()).as(result.output()).isOne();
        assertThat(result.output()).contains("install.ps1 supports Windows PowerShell setup only");
    }

    @Test
    void powershellInstallerAcceptsPublicValueFlagsThroughScriptblockWhenAvailable() throws Exception {
        // given
        List<String> pwsh = powershellCommand();
        assumeFalse(pwsh.isEmpty());
        Path home = temporaryDirectory.resolve("scriptblock home");
        Path bin = temporaryDirectory.resolve("scriptblock bin");

        // when
        ProcessResult result = run(
                nonWindowsPowerShellEnvironment(),
                command(
                                pwsh,
                                "-NoProfile",
                                "-Command",
                                "& ([scriptblock]::Create((Get-Content -Raw './install.ps1'))) --dry-run --no-onboard"
                                        + " --prefix "
                                        + powerShellLiteral(home.resolve("app").toString())
                                        + " --bin-dir "
                                        + powerShellLiteral(bin.toString())
                                        + " --repo https://example.invalid/symphony-trello.git --ref main")
                        .toArray(String[]::new));

        // then
        result.assertSuccess();
        assertThat(result.output())
                .contains(
                        "Install:",
                        home.resolve("app").getFileName().toString(),
                        "Command:",
                        bin.getFileName() + "\\symphony-trello.ps1");
    }

    @Test
    void powershellUninstallerAcceptsPublicDryRunFlagsWhenAvailable() throws Exception {
        // given
        List<String> pwsh = powershellCommand();
        assumeFalse(pwsh.isEmpty());
        Path home = temporaryDirectory.resolve("uninstall ps home");
        Path bin = temporaryDirectory.resolve("uninstall ps bin");

        // when
        ProcessResult result = run(
                nonWindowsPowerShellEnvironment(),
                command(
                                pwsh,
                                "-NoProfile",
                                "-File",
                                "./uninstall.ps1",
                                "--dry-run",
                                "--prefix",
                                home.resolve("app").toString(),
                                "--bin-dir",
                                bin.toString())
                        .toArray(String[]::new));

        // then
        result.assertSuccess();
        assertThat(result.output())
                .contains(
                        "Symphony for Trello uninstall",
                        "App checkout:",
                        home.resolve("app").getFileName().toString(),
                        "Installed CLI:",
                        bin.resolve("symphony-trello.ps1").getFileName().toString(),
                        "Trello boards were not deleted or archived.");
    }

    @Test
    void powershellUninstallerAcceptsMultiplePublicFlagsThroughScriptblockWhenAvailable() throws Exception {
        // given
        List<String> pwsh = powershellCommand();
        assumeFalse(pwsh.isEmpty());

        // when
        ProcessResult result = run(
                nonWindowsPowerShellEnvironment(),
                command(
                                pwsh,
                                "-NoProfile",
                                "-Command",
                                "& ([scriptblock]::Create((Get-Content -Raw './uninstall.ps1'))) "
                                        + "--dry-run --yes --yes-local-data --remove-config --remove-workspaces --remove-state")
                        .toArray(String[]::new));

        // then
        result.assertSuccess();
        assertThat(result.output())
                .contains("Symphony for Trello uninstall", "Trello boards were not deleted or archived.");
    }

    @Test
    void powershellUninstallerRejectsDefaultLocalDataCleanupWithCustomPrefixWhenAvailable() throws Exception {
        // given
        List<String> pwsh = powershellCommand();
        assumeFalse(pwsh.isEmpty());
        Path localAppData = temporaryDirectory.resolve("ps-custom-prefix-local-app-data");
        Path defaultSymphonyHome = Path.of(localAppData + "\\SymphonyTrello");
        CustomPrefixUninstallFixture fixture =
                customPrefixUninstallFixture("ps-custom-prefix", defaultSymphonyHome, "symphony-trello.ps1");
        Map<String, String> environment = new LinkedHashMap<>(nonWindowsPowerShellEnvironment());
        environment.put("LOCALAPPDATA", localAppData.toString());
        environment.put(
                "HOME", temporaryDirectory.resolve("ps-custom-prefix-home").toString());
        environment.put(
                "USERPROFILE",
                temporaryDirectory.resolve("ps-custom-prefix-profile").toString());

        // when
        ProcessResult result = run(
                environment,
                command(
                                pwsh,
                                "-NoProfile",
                                "-File",
                                "./uninstall.ps1",
                                "--yes",
                                "--yes-local-data",
                                "--remove-all-local-data",
                                "--prefix",
                                fixture.app().toString(),
                                "--bin-dir",
                                fixture.bin().toString())
                        .toArray(String[]::new));

        // then
        assertThat(result.exitCode()).as(result.output()).isEqualTo(2);
        assertThat(result.output())
                .contains(
                        "Refusing to remove default local data while uninstalling a custom --prefix.",
                        "CONFIG: set SYMPHONY_HOME or SYMPHONY_TRELLO_CONFIG_DIR",
                        "WORKSPACES: set SYMPHONY_HOME or SYMPHONY_TRELLO_WORKSPACE_ROOT",
                        "STATE/LOGS: set SYMPHONY_HOME or SYMPHONY_TRELLO_STATE_HOME");
        assertCustomPrefixUninstallFixturePreserved(fixture, "symphony-trello.ps1");
    }

    @Test
    void powershellUninstallerRejectsDefaultLocalDataCleanupWithCustomPrefixThroughScriptblockWhenAvailable()
            throws Exception {
        // given
        List<String> pwsh = powershellCommand();
        assumeFalse(pwsh.isEmpty());
        Path localAppData = temporaryDirectory.resolve("ps-scriptblock-custom-prefix-local-app-data");
        Path defaultSymphonyHome = Path.of(localAppData + "\\SymphonyTrello");
        CustomPrefixUninstallFixture fixture = customPrefixUninstallFixture(
                "ps-scriptblock-custom-prefix", defaultSymphonyHome, "symphony-trello.ps1");
        Map<String, String> environment = new LinkedHashMap<>(nonWindowsPowerShellEnvironment());
        environment.put("LOCALAPPDATA", localAppData.toString());
        environment.put(
                "HOME",
                temporaryDirectory.resolve("ps-scriptblock-custom-prefix-home").toString());
        environment.put(
                "USERPROFILE",
                temporaryDirectory
                        .resolve("ps-scriptblock-custom-prefix-profile")
                        .toString());

        // when
        ProcessResult result = run(
                environment,
                command(
                                pwsh,
                                "-NoProfile",
                                "-Command",
                                "& ([scriptblock]::Create((Get-Content -Raw './uninstall.ps1'))) "
                                        + "--yes --yes-local-data --remove-all-local-data --prefix "
                                        + powerShellLiteral(fixture.app().toString())
                                        + " --bin-dir "
                                        + powerShellLiteral(fixture.bin().toString()))
                        .toArray(String[]::new));

        // then
        assertThat(result.exitCode()).as(result.output()).isEqualTo(2);
        assertThat(result.output())
                .contains(
                        "Refusing to remove default local data while uninstalling a custom --prefix.",
                        "CONFIG: set SYMPHONY_HOME or SYMPHONY_TRELLO_CONFIG_DIR",
                        "WORKSPACES: set SYMPHONY_HOME or SYMPHONY_TRELLO_WORKSPACE_ROOT",
                        "STATE/LOGS: set SYMPHONY_HOME or SYMPHONY_TRELLO_STATE_HOME");
        assertCustomPrefixUninstallFixturePreserved(fixture, "symphony-trello.ps1");
    }

    @Test
    void powershellUninstallerRejectsBlankPathValuesThroughScriptblockWhenAvailable() throws Exception {
        // given
        List<String> pwsh = powershellCommand();
        assumeFalse(pwsh.isEmpty());

        // when
        ProcessResult result = run(
                nonWindowsPowerShellEnvironment(),
                command(
                                pwsh,
                                "-NoProfile",
                                "-Command",
                                "& ([scriptblock]::Create((Get-Content -Raw './uninstall.ps1'))) "
                                        + "--dry-run --yes --prefix '' --bin-dir ''")
                        .toArray(String[]::new));

        // then
        assertThat(result.exitCode()).isNotZero();
        assertThat(result.output())
                .contains("Missing value for --prefix")
                .doesNotContain("App checkout:", "Will remove if present:", "/--bin-dir");
    }

    @Test
    void powershellUninstallerValidationErrorsArePlainWhenAvailable() throws Exception {
        // given
        List<String> pwsh = powershellCommand();
        assumeFalse(pwsh.isEmpty());

        // when
        ProcessResult result = run(
                nonWindowsPowerShellEnvironment(),
                command(pwsh, "-NoProfile", "-File", "./uninstall.ps1", "--dry-run", "--prefix", "relative/path")
                        .toArray(String[]::new));

        // then
        assertThat(result.exitCode()).isNotZero();
        assertThat(result.output())
                .contains("--prefix must be an absolute path.")
                .doesNotContain("Exception:", "Line |", "uninstall.ps1:", "throw \"--prefix");
    }

    @Test
    void powershellUninstallerRejectsUnsafeAppPathsWhenAvailable() throws Exception {
        // given
        List<String> pwsh = powershellCommand();
        assumeFalse(pwsh.isEmpty());
        Path home = temporaryDirectory.resolve("ps-uninstall-app-home");
        Path symphonyHome = temporaryDirectory.resolve("ps-uninstall-app-symphony-home");
        Path file = temporaryDirectory.resolve("ps-uninstall-app-file");
        Path symlink = temporaryDirectory.resolve("ps-uninstall-app-link");
        Path safeBin = temporaryDirectory.resolve("ps-uninstall-app-bin");
        Files.createDirectories(home);
        Files.writeString(file, "not a directory");
        Files.createSymbolicLink(symlink, temporaryDirectory.resolve("ps-uninstall-app-target"));
        Map<String, String> environment = new LinkedHashMap<>(nonWindowsPowerShellEnvironment());
        environment.put("USERPROFILE", home.toString());
        environment.put("SYMPHONY_HOME", symphonyHome.toString());
        List<UnsafeInstallerPath> cases = powershellUnsafeAppPaths(file, symlink, "ps-uninstall-app-link");

        // when
        List<ProcessResult> results = runPowerShellAppPathCases(
                environment, pwsh, "./uninstall.ps1", List.of("--dry-run", "--yes"), cases, safeBin);

        // then
        assertUnsafeAppPathFailures(
                cases, results, OutputStyle.NORMALIZED, "App checkout:", "Will remove if present:", "Installed CLI:");
    }

    @Test
    void powershellUninstallerAcceptsDedicatedAppPathDirectlyUnderHomeWhenAvailable() throws Exception {
        // given
        List<String> pwsh = powershellCommand();
        assumeFalse(pwsh.isEmpty());
        Path home = temporaryDirectory.resolve("ps-uninstall-home-child-home");
        Path app = home.resolve("symphony-trello");
        Path safeBin = temporaryDirectory.resolve("ps-uninstall-home-child-bin");
        Files.createDirectories(home);
        Map<String, String> environment = new LinkedHashMap<>(nonWindowsPowerShellEnvironment());
        environment.put("USERPROFILE", home.toString());

        // when
        ProcessResult result = run(
                environment,
                command(
                                pwsh,
                                "-NoProfile",
                                "-File",
                                "./uninstall.ps1",
                                "--dry-run",
                                "--yes",
                                "--prefix",
                                app.toString(),
                                "--bin-dir",
                                safeBin.toString())
                        .toArray(String[]::new));

        // then
        result.assertSuccess();
        assertThat(result.output()).contains("App checkout:", app.getFileName().toString(), "Will remove if present:");
    }

    @Test
    void powershellUninstallerRejectsUnsafeCommandDirectoriesWhenAvailable() throws Exception {
        // given
        List<String> pwsh = powershellCommand();
        assumeFalse(pwsh.isEmpty());
        Path home = temporaryDirectory.resolve("ps-uninstall-bin-home");
        Path symphonyHome = temporaryDirectory.resolve("ps-uninstall-bin-symphony-home");
        Path app = temporaryDirectory.resolve("ps-uninstall-bin-app");
        Path file = temporaryDirectory.resolve("ps-uninstall-bin-file");
        Path symlink = temporaryDirectory.resolve("ps-uninstall-bin-state-link");
        Files.createDirectories(home);
        Files.createDirectories(symphonyHome.resolve("state"));
        Files.writeString(file, "not a directory");
        Files.createSymbolicLink(symlink, symphonyHome.resolve("state"));
        Map<String, String> environment = new LinkedHashMap<>(nonWindowsPowerShellEnvironment());
        environment.put("USERPROFILE", home.toString());
        environment.put("SYMPHONY_HOME", symphonyHome.toString());
        List<UnsafeCommandDirectory> cases = powershellUnsafeCommandDirectories(
                file,
                symlink,
                "ps-uninstall-bin-state-link",
                new UnsafeCommandDirectory(
                        "state-overlap",
                        symphonyHome.resolve("state").toString(),
                        "--bin-dir must not overlap Symphony app, config, workspace"));
        List<UnsafeCommandDirectory> rejectedCases = cases.stream()
                .filter(InstallerScriptTest::unsafeUninstallCommandDirectoryCase)
                .toList();

        // when
        List<ProcessResult> results = rejectedCases.stream()
                .map(commandDirectory -> runUnchecked(
                        environment,
                        command(
                                        pwsh,
                                        "-NoProfile",
                                        "-File",
                                        "./uninstall.ps1",
                                        "--dry-run",
                                        "--yes",
                                        "--prefix",
                                        app.toString(),
                                        "--bin-dir",
                                        commandDirectory.value())
                                .toArray(String[]::new)))
                .toList();

        // then
        for (int index = 0; index < rejectedCases.size(); index++) {
            UnsafeCommandDirectory commandDirectory = rejectedCases.get(index);
            ProcessResult result = results.get(index);
            assertThat(result.exitCode()).as(commandDirectory.name()).isNotZero();
            assertThat(normalizedWhitespace(result.output()))
                    .as(commandDirectory.name())
                    .contains(commandDirectory.expectedMessage())
                    .doesNotContain("Installed CLI:", "Will remove if present:");
        }
    }

    @Test
    void powershellUninstallerAllowsSymlinkedCommandDirectoryWhenAvailable() throws Exception {
        // given
        // Install rejects a symlinked command directory, but a user may replace the directory
        // with a symlink afterward; uninstall must still clean up instead of stranding files.
        List<String> pwsh = powershellCommand();
        assumeFalse(pwsh.isEmpty());
        Path home = temporaryDirectory.resolve("ps-uninstall-symlink-bin-home");
        Path app = temporaryDirectory.resolve("ps-uninstall-symlink-bin-app");
        Path realBin = temporaryDirectory.resolve("ps-uninstall-symlink-bin-target");
        Path symlinkBin = temporaryDirectory.resolve("ps-uninstall-symlink-bin-link");
        Files.createDirectories(home);
        Files.createDirectories(realBin);
        Files.createSymbolicLink(symlinkBin, realBin);
        Map<String, String> environment = new LinkedHashMap<>(nonWindowsPowerShellEnvironment());
        environment.put("USERPROFILE", home.toString());

        // when
        ProcessResult result = run(
                environment,
                command(
                                pwsh,
                                "-NoProfile",
                                "-File",
                                "./uninstall.ps1",
                                "--dry-run",
                                "--yes",
                                "--prefix",
                                app.toString(),
                                "--bin-dir",
                                symlinkBin.toString())
                        .toArray(String[]::new));

        // then
        assertThat(result.exitCode()).as(result.output()).isZero();
        assertThat(normalizedWhitespace(result.output())).contains("Installed CLI:", "Will remove if present:");
    }

    @Test
    void powershellUninstallerAllowsDefaultCommandDirectoryWhenRunFromThatDirectoryWhenAvailable() throws Exception {
        // given
        List<String> pwsh = powershellCommandForDifferentWorkingDirectory();
        assumeFalse(pwsh.isEmpty());
        Path uninstallScript = Path.of("uninstall.ps1").toAbsolutePath();
        Path home = temporaryDirectory.resolve("ps-uninstall-run-from-bin-home");
        Path binDirectory = home.resolve(".local/bin");
        Files.createDirectories(binDirectory);
        Map<String, String> environment = new LinkedHashMap<>(nonWindowsPowerShellEnvironment());
        environment.put("HOME", home.toString());
        environment.put("USERPROFILE", home.toString());

        // when
        ProcessResult result = run(
                environment,
                binDirectory,
                command(pwsh, "-NoProfile", "-File", uninstallScript.toString(), "--dry-run", "--yes")
                        .toArray(String[]::new));

        // then
        result.assertSuccess();
        assertThat(result.output()).contains("Trello boards were not deleted or archived.");
    }

    @Test
    void powershellUninstallerAcceptsDashPrefixedRelativePathValuesThroughScriptblockWhenAvailable() throws Exception {
        // given
        List<String> pwsh = powershellCommand();
        assumeFalse(pwsh.isEmpty());
        Path binDirectory = temporaryDirectory.resolve("-dash-bin");
        Path prefix = temporaryDirectory.resolve("-dash-prefix-app");

        // when
        ProcessResult result = run(
                nonWindowsPowerShellEnvironment(),
                command(
                                pwsh,
                                "-NoProfile",
                                "-Command",
                                "& ([scriptblock]::Create((Get-Content -Raw './uninstall.ps1'))) --dry-run --yes"
                                        + " --prefix "
                                        + powerShellLiteral(prefix.toString())
                                        + " --bin-dir "
                                        + powerShellLiteral(binDirectory.toString()))
                        .toArray(String[]::new));

        // then
        result.assertSuccess();
        assertThat(result.output())
                .contains(
                        prefix.getFileName().toString(),
                        binDirectory.getFileName().toString(),
                        "Trello boards were not deleted or archived.");
    }

    @Test
    void powershellUninstallerSkipsInvalidStalePidFilesWhenAvailable() throws Exception {
        // given
        List<String> pwsh = powershellCommand();
        assumeFalse(pwsh.isEmpty());
        Path home = temporaryDirectory.resolve("invalid pid ps home");
        Path prefix = home.resolve("app");
        Path stateHome = home.resolve("state");
        Files.createDirectories(prefix);
        Files.createDirectories(stateHome);
        Files.createFile(prefix.resolve(".symphony-trello-install"));
        Path pidFile = stateHome.resolve("broken.pid");
        Files.writeString(pidFile, "not-a-pid\n");

        // when
        ProcessResult result = run(
                nonWindowsPowerShellEnvironment(),
                command(
                                pwsh,
                                "-NoProfile",
                                "-File",
                                "./uninstall.ps1",
                                "--yes",
                                "--symphony-home",
                                home.toString(),
                                "--prefix",
                                prefix.toString(),
                                "--bin-dir",
                                temporaryDirectory.resolve("invalid pid ps bin").toString())
                        .toArray(String[]::new));

        // then
        result.assertSuccess();
        assertThat(result.output())
                .contains(
                        "SKIP  invalid stale pid",
                        "REMOVE",
                        prefix.getFileName().toString());
        assertThat(pidFile).doesNotExist();
        assertThat(prefix).doesNotExist();
    }

    @Test
    void posixUninstallerRequiresSeparateConfirmationForLocalUserData() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        Path symphonyHome = temporaryDirectory.resolve("protected-user-data-home");
        Files.createDirectories(symphonyHome.resolve("config"));
        Files.writeString(symphonyHome.resolve("config/.env"), "TRELLO_API_KEY=secret\n");
        Map<String, String> environment = Map.of("SYMPHONY_HOME", symphonyHome.toString());

        // when
        ProcessResult result = run(environment, "bash", "uninstall.sh", "--yes", "--remove-config");

        // then
        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.output()).contains("matching confirmation flag");
        assertThat(symphonyHome.resolve("config/.env")).exists();
    }

    @Test
    void posixUninstallerRejectsDefaultLocalDataCleanupWithCustomPrefix() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        Path home = temporaryDirectory.resolve("custom-prefix-home");
        Path defaultSymphonyHome = home.resolve(".local/share/symphony-trello");
        CustomPrefixUninstallFixture fixture =
                customPrefixUninstallFixture("custom-prefix", defaultSymphonyHome, "symphony-trello");
        Map<String, String> environment = Map.of("HOME", home.toString());

        // when
        ProcessResult result = run(
                environment,
                "bash",
                "uninstall.sh",
                "--yes",
                "--yes-local-data",
                "--remove-all-local-data",
                "--prefix",
                fixture.app().toString(),
                "--bin-dir",
                fixture.bin().toString());

        // then
        assertThat(result.exitCode()).as(result.output()).isEqualTo(2);
        assertThat(result.output())
                .contains(
                        "Refusing to remove default local data while uninstalling a custom --prefix.",
                        "CONFIG: set SYMPHONY_HOME or SYMPHONY_TRELLO_CONFIG_DIR",
                        "WORKSPACES: set SYMPHONY_HOME or SYMPHONY_TRELLO_WORKSPACE_ROOT",
                        "STATE/LOGS: set SYMPHONY_HOME or SYMPHONY_TRELLO_STATE_HOME");
        assertCustomPrefixUninstallFixturePreserved(fixture, "symphony-trello");
    }

    @Test
    void posixUninstallerRefusesToRemoveAppDirectoryContainingPreservedCurrentData() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        Path symphonyHome = temporaryDirectory.resolve("embedded-current-layout-home");
        Files.createDirectories(symphonyHome.resolve("config"));
        Files.createDirectories(symphonyHome.resolve("workspaces"));
        Files.createDirectories(symphonyHome.resolve("state"));
        Files.createFile(symphonyHome.resolve(".symphony-trello-install"));
        Files.writeString(symphonyHome.resolve("config/.env"), "TRELLO_API_KEY=secret\n");
        Map<String, String> environment = Map.of("SYMPHONY_HOME", symphonyHome.toString());

        // when
        ProcessResult result = run(
                environment,
                "bash",
                "uninstall.sh",
                "--yes",
                "--prefix",
                symphonyHome.toString(),
                "--bin-dir",
                temporaryDirectory.resolve("bin").toString());

        // then
        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.output())
                .contains(
                        "Refusing to remove app directory because it contains current local data",
                        "CONFIG     " + symphonyHome.resolve("config"),
                        "WORKSPACES " + symphonyHome.resolve("workspaces"),
                        "STATE/LOGS " + symphonyHome.resolve("state"));
        assertThat(symphonyHome.resolve("config/.env")).exists();
    }

    @Test
    void posixUninstallerRejectsAppDirectoryThatWouldContainCurrentDataPaths() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        Path symphonyHome = temporaryDirectory.resolve("embedded-empty-home");
        Files.createDirectories(symphonyHome);
        Files.createFile(symphonyHome.resolve(".symphony-trello-install"));
        Files.writeString(symphonyHome.resolve("app.txt"), "managed app file\n");
        Map<String, String> environment = Map.of("SYMPHONY_HOME", symphonyHome.toString());

        // when
        ProcessResult result = run(
                environment,
                "bash",
                "uninstall.sh",
                "--yes",
                "--prefix",
                symphonyHome.toString(),
                "--bin-dir",
                temporaryDirectory.resolve("bin").toString());

        // then
        assertThat(result.exitCode()).as(result.output()).isEqualTo(2);
        assertThat(result.output())
                .contains("--prefix must not overlap Symphony config, workspace, or state directories.")
                .doesNotContain("REMOVE  " + symphonyHome);
        assertThat(symphonyHome).exists();
    }

    @Test
    void posixUninstallerCleansSelectedLocalDataWhenUnmarkedAppRemovalIsDeclined() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        assumeTrue(commandExists("script"));
        Path uninstallScript = Path.of("uninstall.sh").toAbsolutePath();
        Path symphonyHome = temporaryDirectory.resolve("unmarked-declined-home");
        Path appHome = symphonyHome.resolve("app");
        Path configDirectory = symphonyHome.resolve("config");
        Files.createDirectories(appHome);
        Files.createDirectories(configDirectory);
        Files.writeString(configDirectory.resolve(".env"), "TRELLO_API_KEY=secret\n");
        Map<String, String> environment = Map.of("SYMPHONY_HOME", symphonyHome.toString());

        // when
        ProcessResult result = runWithPseudoTerminal(
                environment,
                "n\n",
                "bash " + shellQuote(uninstallScript.toString()) + " --yes-local-data --remove-config --prefix "
                        + shellQuote(appHome.toString()) + " --bin-dir "
                        + shellQuote(temporaryDirectory.resolve("bin").toString()));

        // then
        assertThat(result.exitCode()).as(result.output()).isZero();
        assertThat(result.output()).contains("Skipped installer-managed files.", "REMOVE  " + configDirectory);
        assertThat(appHome).exists();
        assertThat(configDirectory).doesNotExist();
    }

    @Test
    void posixUninstallerKeepsDiscoveryContextWhenAppRemovalIsDeclined() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        assumeTrue(commandExists("script"));
        Path uninstallScript = Path.of("uninstall.sh").toAbsolutePath();
        Path home = temporaryDirectory.resolve("declined-context-home");
        Path symphonyHome = temporaryDirectory.resolve("declined-context-symphony-home");
        Path appHome = symphonyHome.resolve("app");
        Path configDirectory = symphonyHome.resolve("config");
        Path workspaceRoot = symphonyHome.resolve("workspaces");
        Path stateHome = symphonyHome.resolve("state");
        Path binDirectory = symphonyHome.resolve("bin");
        Path defaultStateContext = home.resolve(".local/state/symphony-trello").resolve(INSTALL_CONTEXT_PROPERTIES);
        Path defaultConfigContext = home.resolve(".config/symphony-trello").resolve(INSTALL_CONTEXT_PROPERTIES);
        Files.createDirectories(appHome);
        Files.createDirectories(configDirectory);
        Files.createDirectories(workspaceRoot);
        Files.createDirectories(stateHome);
        Files.createDirectories(binDirectory);
        Files.createDirectories(defaultStateContext.getParent());
        Files.createDirectories(defaultConfigContext.getParent());
        String context =
                """
                installer=install.sh
                install_format_version=2
                layout_mode=custom-flags
                app_dir=%s
                config_dir=%s
                workspace_root=%s
                state_home=%s
                bin_dir=%s
                created_microos_var_root=false
                """
                        .formatted(appHome, configDirectory, workspaceRoot, stateHome, binDirectory);
        Files.writeString(defaultStateContext, context);
        Files.writeString(defaultConfigContext, context);
        Map<String, String> environment = Map.of("HOME", home.toString(), "SYMPHONY_HOME", symphonyHome.toString());

        // when
        ProcessResult result = runWithPseudoTerminal(
                environment,
                "n\n",
                "bash " + shellQuote(uninstallScript.toString()) + " --yes-local-data --remove-all-local-data --prefix "
                        + shellQuote(appHome.toString()) + " --bin-dir "
                        + shellQuote(binDirectory.toString()));

        // then
        assertThat(result.exitCode()).as(result.output()).isZero();
        assertThat(result.output())
                .contains("Skipped installer-managed files.")
                .doesNotContain("REMOVE  " + defaultStateContext);
        assertThat(appHome).exists();
        assertThat(defaultStateContext).exists();
        assertThat(defaultConfigContext).exists();
        assertThat(configDirectory).doesNotExist();
        assertThat(workspaceRoot).doesNotExist();
        assertThat(stateHome).doesNotExist();
    }

    @Test
    void posixUninstallerAllowsRemovingAppDirectoryContainingCurrentDataWithExplicitCleanupScopes() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        Path symphonyHome = temporaryDirectory.resolve("embedded-cleanup-home");
        Files.createDirectories(symphonyHome.resolve("config"));
        Files.createDirectories(symphonyHome.resolve("workspaces"));
        Files.createDirectories(symphonyHome.resolve("state"));
        Files.createFile(symphonyHome.resolve(".symphony-trello-install"));
        Files.writeString(symphonyHome.resolve("config/.env"), "TRELLO_API_KEY=secret\n");
        Map<String, String> environment = Map.of("SYMPHONY_HOME", symphonyHome.toString());

        // when
        ProcessResult result = run(
                environment,
                "bash",
                "uninstall.sh",
                "--yes",
                "--yes-local-data",
                "--remove-config",
                "--remove-workspaces",
                "--remove-state",
                "--prefix",
                symphonyHome.toString(),
                "--bin-dir",
                temporaryDirectory.resolve("bin").toString());

        // then
        assertThat(result.exitCode()).as(result.output()).isZero();
        assertThat(result.output()).contains("REMOVE  " + symphonyHome);
        assertThat(symphonyHome).doesNotExist();
    }

    @Test
    void posixUninstallerPromptsBeforeRemovingAppDirectoryContainingCleanupScopedCurrentData() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        assumeTrue(commandExists("script"));
        Path uninstallScript = Path.of("uninstall.sh").toAbsolutePath();
        Path symphonyHome = temporaryDirectory.resolve("embedded-prompted-cleanup-home");
        Files.createDirectories(symphonyHome.resolve("config"));
        Files.createDirectories(symphonyHome.resolve("workspaces"));
        Files.createDirectories(symphonyHome.resolve("state"));
        Files.createFile(symphonyHome.resolve(".symphony-trello-install"));
        Files.writeString(symphonyHome.resolve("config/.env"), "TRELLO_API_KEY=secret\n");
        Map<String, String> environment = Map.of("SYMPHONY_HOME", symphonyHome.toString());

        // when
        ProcessResult result = runWithPseudoTerminal(
                environment,
                "y\ny\n",
                "bash " + shellQuote(uninstallScript.toString()) + " --remove-config --remove-workspaces"
                        + " --remove-state --prefix " + shellQuote(symphonyHome.toString())
                        + " --bin-dir "
                        + shellQuote(temporaryDirectory.resolve("bin").toString()));

        // then
        assertThat(result.exitCode()).as(result.output()).isZero();
        assertThat(result.output()).contains("local data selected for cleanup", "REMOVE  " + symphonyHome);
        assertThat(symphonyHome).doesNotExist();
    }

    @Test
    void posixUninstallerRefusesCanonicalEquivalentHomeDirectoryCleanup() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        Path symphonyHome = temporaryDirectory.resolve("home-equivalent");
        Files.createDirectories(symphonyHome);
        Map<String, String> environment = Map.of(
                "HOME", symphonyHome.toString(),
                "SYMPHONY_HOME", temporaryDirectory.resolve("safe-home").toString(),
                "SYMPHONY_TRELLO_CONFIG_DIR", symphonyHome + "/");

        // when
        ProcessResult result = run(
                environment,
                "bash",
                "uninstall.sh",
                "--dry-run",
                "--yes",
                "--yes-local-data",
                "--remove-config",
                "--bin-dir",
                temporaryDirectory.resolve("safe-home-equivalent-bin").toString());

        // then
        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.output()).contains("Refusing dangerous removal path");
        assertThat(symphonyHome).exists();
    }

    @Test
    void installersDeclareExpectedManagedRuntimeCapabilities() throws Exception {
        // given
        String posixInstaller = Files.readString(Path.of("install.sh"));
        String powershellInstaller = Files.readString(Path.of("install.ps1"));
        String posixUninstaller = Files.readString(Path.of("uninstall.sh"));
        String powershellUninstaller = Files.readString(Path.of("uninstall.ps1"));

        // when
        var commandNames = List.of("setup-local", "start", "stop", "status", "logs");
        String powershellAppRemovalBlock = powershellUninstaller.substring(
                powershellUninstaller.indexOf("\n  Assert-AppRemovalPreservesCurrentData\n"));

        // then
        assertThat(commandNames)
                .allSatisfy(command -> assertThat(posixInstaller).contains(command))
                .allSatisfy(command -> assertThat(powershellInstaller).contains(command));
        assertThat(posixInstaller)
                .contains(
                        "SYMPHONY_HOME",
                        "CONFIG_DIR",
                        "WORKSPACE_ROOT",
                        "STATE_HOME",
                        ".symphony-trello-install",
                        "Java 25+ JDK",
                        "javac",
                        "SYMPHONY_TRELLO_DOTENV",
                        "codex login --device-auth",
                        "Run \\`$login_command\\`, then rerun this installer.",
                        "npm install --global --prefix",
                        "CODEX_NPM_BIN_DIR",
                        "SYMPHONY_TRELLO_APP_HOME",
                        "INSTALLED_CONFIG_DIR",
                        "INSTALLED_WORKSPACE_ROOT",
                        "INSTALLED_STATE_HOME",
                        "-Dsymphony.trello.installed.config.dir",
                        "-Dsymphony.trello.installed.workspace.root",
                        "-Dsymphony.trello.installed.state.home",
                        "absolutize_path",
                        "append_path_setup_to_profile",
                        "TrelloBoardSetupMain",
                        "-DskipTests clean package",
                        "Stopping managed workers before update",
                        "Restarting managed workers after update",
                        "start --all",
                        "systemctl --user show-environment",
                        "symphony-trello.service",
                        "autostart.env",
                        "EnvironmentFile=-",
                        "loginctl enable-linger",
                        "Library/LaunchAgents",
                        "ch.fmartin.symphony-trello",
                        "launchctl bootstrap",
                        "EnvironmentVariables",
                        "autostart_environment_name",
                        "SYMPHONY_TRELLO_INSTALLER_COMPLETION",
                        "run_setup_local_with_deferred_completion",
                        "start_managed_workers_without_installer_completion",
                        "print_installer_completion",
                        "pid_command_line",
                        "is_live_managed_pid",
                        "-Dsymphony.trello.managed.app_home",
                        "exec_setup_cli \"\\$@\"")
                .doesNotContain(
                        ",,}",
                        "printf -v quoted '%q'",
                        "workflow_state_name",
                        "default_workflow",
                        "wait_for_exit",
                        "has_cli_option",
                        "cli_option_value",
                        "setup_local_lifecycle_subcommand",
                        "is_managed_pid",
                        "mapfile",
                        "nohup");
        assertThat(posixInstaller).doesNotContain("[[:space:]");
        assertThat(powershellInstaller)
                .contains(
                        "[Alias(\"-dry-run\", \"dry-run\", \"--dry-run\")]",
                        "[Alias(\"-no-onboard\", \"no-onboard\", \"--no-onboard\")]",
                        "LASTEXITCODE",
                        "SYMPHONY_TRELLO_DOTENV",
                        "SYMPHONY_TRELLO_APP_HOME",
                        "Install-CodexOrExit",
                        "if (-not $NoOnboard) {",
                        "Java 25+ JDK",
                        "javac",
                        "SymphonyHome",
                        "$ConfigDir",
                        "$WorkspaceRoot",
                        "$StateHome",
                        "symphony-trello.cmd",
                        ".symphony-trello-install",
                        "TrelloBoardSetupMain",
                        "-DskipTests clean package",
                        "Stopping managed workers before update",
                        "Restarting managed workers after update",
                        "start --all",
                        "Scheduled Task",
                        "Register-ScheduledTask",
                        "New-ScheduledTaskAction",
                        "Start-ScheduledTask",
                        "Protect-PrivateFile",
                        "SetAccessRuleProtection($true, $false)",
                        "symphony-trello-autostart.ps1",
                        "autostart-env.ps1",
                        "Test-AutostartEnvironmentName",
                        "$InstallerCompletionEnvironmentName = \"SYMPHONY_TRELLO_INSTALLER_COMPLETION\"",
                        "$InstallerCompletionWasSet = Test-Path",
                        "$InstallerCompletionPreviousValue = [Environment]::GetEnvironmentVariable",
                        "Set-InstallerCompletionMode \"defer\"",
                        "Clear-InstallerCompletionMode",
                        "Start-ManagedWorkersWithoutInstallerCompletion",
                        "Set-InstallerCompletionMode \"print\"",
                        "Restore-InstallerCompletionEnvironment",
                        "Scheduled Task was installed but could not be started immediately",
                        "if (Install-StartupFolderCommand) {",
                        "Microsoft\\Windows\\Start Menu\\Programs\\Startup",
                        "Invoke-Step \"$BinDir\\symphony-trello.ps1 setup-local\"",
                        "Invoke-Step \"$BinDir\\symphony-trello.ps1 start --all\"",
                        "`$InstalledConfigDir",
                        "`$InstalledWorkspaceRoot",
                        "`$InstalledStateHome",
                        "-Dsymphony.trello.installed.config.dir",
                        "-Dsymphony.trello.installed.workspace.root",
                        "-Dsymphony.trello.installed.state.home",
                        "Add-BinDirToUserPath",
                        "Join-Path `$ConfigDir \".env\"",
                        "Assert-AvailableAfterInstall",
                        "Open a new PowerShell window with the Symphony bin directory on PATH.",
                        "Run '$loginCommand', then rerun this installer.",
                        "codex.cmd",
                        "codex.ps1",
                        "$CodexCommand = Join-Path `$CodexNpmPrefix \"codex.ps1\"")
                .contains("ConvertTo-PowerShellLiteral")
                .contains("$PrefixLiteral = ConvertTo-PowerShellLiteral $Prefix")
                .contains("`$AppHome = $PrefixLiteral")
                .contains("`$env:SYMPHONY_TRELLO_COMMAND = $CommandLiteral")
                .contains("`$env:SYMPHONY_TRELLO_COMMAND = `$commandPath")
                .doesNotContain(
                        "SHA256]::HashData",
                        "Test-CliOption",
                        "Get-CliOptionValue",
                        "Test-SetupLocalLifecycleSubcommand",
                        "Get-WorkflowStateName",
                        "Get-DefaultWorkflow",
                        "Get-DefaultEnv",
                        "Stop-AndWaitManagedProcess",
                        "Start-Process -FilePath \"java\"",
                        "-Dsymphony.trello.managed.app_home",
                        "Test-ManagedPid");
        assertThat(posixUninstaller)
                .contains(
                        "/dev/tty",
                        "WOULD STOP",
                        "STOP",
                        "kill",
                        "wait_for_exit",
                        "absolutize_path",
                        "SKIP  stale pid does not belong to this install: $(worker_label \"$pid_file\")",
                        "symphony-trello.service",
                        "autostart.env",
                        "launchctl bootout",
                        "ch.fmartin.symphony-trello",
                        "--remove-config",
                        "--remove-workspaces",
                        "--remove-state",
                        "--yes-local-data",
                        ".symphony-trello-install",
                        "Trello boards were not deleted or archived.");
        assertThat(posixUninstaller).doesNotContain(",,}");
        assertThat(powershellUninstaller)
                .contains(
                        "WOULD STOP",
                        "[Alias(\"-dry-run\", \"dry-run\", \"--dry-run\")]",
                        "[Alias(\"-yes\", \"--yes\")]",
                        "[Alias(\"-yes-local-data\", \"yes-local-data\", \"--yes-local-data\")]",
                        "[Alias(\"-remove-config\", \"remove-config\", \"--remove-config\")]",
                        "symphony-trello.cmd",
                        "SymphonyHome",
                        "RemoveConfig",
                        "RemoveWorkspaces",
                        "RemoveState",
                        "RemoveAllLocalData",
                        "YesLocalData",
                        ".symphony-trello-install",
                        "SKIP  stale pid does not belong to this install: $(Get-WorkerLabel $pidFile.BaseName)",
                        "Remove-WindowsAutostart",
                        "schtasks.exe /Delete",
                        "Microsoft\\Windows\\Start Menu\\Programs\\Startup",
                        "symphony-trello-autostart.ps1",
                        "autostart-env.ps1",
                        "Stop-AndWaitManagedProcess",
                        "Stop-Process",
                        "Wait-Process",
                        "Test-ManagedPid",
                        "Remove-ManagedCodexArtifacts",
                        "Test-ManagedCodexWrapper",
                        "[int]::TryParse",
                        "SKIP  invalid stale pid",
                        "Test-Path -LiteralPath $safePath",
                        "Remove-Item -Recurse -Force -LiteralPath $safePath",
                        "Assert-AppRemovalPreservesCurrentData",
                        "Test-SameOrInsidePath",
                        "Test-SameFileContent",
                        "[System.IO.Path]::GetFullPath",
                        "(Get-Location).Path")
                .doesNotContain(
                        "Remove-ManagedPath \"$BinDir\\codex.exe\"",
                        "Remove-ManagedPath \"$BinDir\\codex.cmd\"",
                        "Remove-ManagedPath \"$BinDir\\codex.ps1\"");
        assertThat(powershellAppRemovalBlock).contains("Stop-ManagedProcesses", "Remove-WindowsAutostart");
        assertThat(powershellAppRemovalBlock.indexOf("Stop-ManagedProcesses"))
                .isLessThan(powershellAppRemovalBlock.indexOf("Remove-WindowsAutostart"));
    }

    @Test
    void installersOrderFinalHandoffAfterManagedWorkerSetupInSource() throws Exception {
        // given
        String posixInstaller = Files.readString(Path.of("install.sh"));
        String powershellInstaller = Files.readString(Path.of("install.ps1"));
        String posixOnboarding =
                posixInstaller.substring(posixInstaller.lastIndexOf("if [[ \"$NO_ONBOARD\" == false ]]"));
        String powershellOnboarding =
                powershellInstaller.substring(powershellInstaller.lastIndexOf("if (-not $NoOnboard)"));
        String posixAutostartFilter = posixInstaller.substring(
                posixInstaller.indexOf("autostart_environment_name()"),
                posixInstaller.indexOf("has_control_character()"));
        String powershellAutostartFilter = powershellInstaller.substring(
                powershellInstaller.indexOf("function Test-AutostartEnvironmentName"),
                powershellInstaller.indexOf("function Write-AutostartEnvironmentFile"));
        String powershellManagedWorkerBoundary = powershellInstaller.substring(
                powershellInstaller.indexOf("function Start-ManagedWorkersWithoutInstallerCompletion"),
                powershellInstaller.indexOf("function Get-InstalledAppVersionFallback"));

        // when
        int posixDeferredSetup = posixOnboarding.indexOf("run_setup_local_with_deferred_completion");
        int posixManagedWorkers =
                posixOnboarding.indexOf("start_managed_workers_without_installer_completion", posixDeferredSetup);
        int posixFinalHandoff = posixOnboarding.indexOf("print_installer_completion");
        int powershellDeferredSetup = powershellOnboarding.indexOf("Set-InstallerCompletionMode \"defer\"");
        int powershellManagedWorkers =
                powershellOnboarding.indexOf("Start-ManagedWorkersWithoutInstallerCompletion", powershellDeferredSetup);
        int powershellFinalHandoff = powershellOnboarding.indexOf("Set-InstallerCompletionMode \"print\"");

        // then
        assertThat(posixDeferredSetup).isNotNegative().isLessThan(posixManagedWorkers);
        assertThat(posixManagedWorkers).isLessThan(posixFinalHandoff);
        assertThat(powershellDeferredSetup).isNotNegative().isLessThan(powershellManagedWorkers);
        assertThat(powershellManagedWorkers).isLessThan(powershellFinalHandoff);
        assertThat(powershellOnboarding.indexOf("Clear-InstallerCompletionMode"))
                .isBetween(powershellDeferredSetup, powershellManagedWorkers);
        assertThat(powershellOnboarding.indexOf("Restore-InstallerCompletionEnvironment"))
                .isGreaterThan(powershellFinalHandoff);
        assertThat(powershellOnboarding).doesNotContain("if ($DryRun)");
        assertThat(posixInstaller)
                .contains(
                        "local -x \"$INSTALLER_COMPLETION_ENV=defer\"",
                        "local -x \"$INSTALLER_COMPLETION_ENV=print\"",
                        "unset \"$INSTALLER_COMPLETION_ENV\"")
                .doesNotContain(
                        "local -x SYMPHONY_TRELLO_INSTALLER_COMPLETION=", "unset SYMPHONY_TRELLO_INSTALLER_COMPLETION");
        assertThat(posixAutostartFilter).contains("[[ \"$name\" != \"$INSTALLER_COMPLETION_ENV\" ]] || return 1");
        assertThat(powershellAutostartFilter)
                .contains("if ($Name -eq $InstallerCompletionEnvironmentName)", "return $false");
        assertThat(powershellManagedWorkerBoundary)
                .containsSubsequence(
                        "try {",
                        "Clear-InstallerCompletionMode",
                        "Start-ManagedWorkers",
                        "} finally {",
                        "Restore-InstallerCompletionEnvironment");
        assertThat(powershellOnboarding.substring(powershellOnboarding.indexOf("elseif ($RestartManagedWorkers)")))
                .contains("Start-ManagedWorkersWithoutInstallerCompletion");
    }

    @Test
    void installersOfferPathSetupBeforeGuidedSetupCanAbort() throws Exception {
        // given
        String posixInstaller = Files.readString(Path.of("install.sh"));
        String powershellInstaller = Files.readString(Path.of("install.ps1"));

        // when
        int posixPathSetup = posixInstaller.indexOf("\noffer_path_setup\n\nif [[ \"$NO_ONBOARD\" == false ]]");
        int powershellPathSetup = powershellInstaller.indexOf("\nOffer-PathSetup\n\nif (-not $NoOnboard)");

        // then
        assertThat(posixPathSetup)
                .as("POSIX installer must offer PATH setup before setup-local or restart can abort")
                .isNotNegative();
        assertThat(powershellPathSetup)
                .as("PowerShell installer must offer PATH setup before setup-local or restart can abort")
                .isNotNegative();
    }

    @Test
    void posixInstallerAddsPathSetupAfterInstallingCodexWithUserLocalNpm() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        assumeTrue(commandExists("git"));
        assumeTrue(commandExists("script"));
        Path installScript = Path.of("install.sh").toAbsolutePath();
        Path sourceRepository = createSourceRepository(temporaryDirectory);
        Path fakeBin = createFakeToolchain(temporaryDirectory);
        Files.delete(fakeBin.resolve("codex"));
        writeCommandProxy(fakeBin, "bash", "/bin/bash");
        writeExecutable(
                fakeBin.resolve("git"),
                """
                #!/usr/bin/env bash
                exec /usr/bin/git "$@"
                """);
        writeCommandProxy(fakeBin, "mkdir", "/bin/mkdir");
        writeCommandProxy(fakeBin, "uname", "/bin/uname");
        writeCommandProxy(fakeBin, "chmod", "/bin/chmod");
        writeCommandProxy(fakeBin, "dirname", "/usr/bin/dirname");
        writeCommandProxy(fakeBin, "sed", "/bin/sed");
        writeCommandProxy(fakeBin, "head", "/usr/bin/head");
        writeCommandProxy(fakeBin, "cat", "/bin/cat");
        writeCommandProxy(fakeBin, "ln", "/bin/ln");
        writeCommandProxy(fakeBin, "tr", "/usr/bin/tr");
        writeCommandProxy(fakeBin, "sleep", "/bin/sleep");
        writeCommandProxy(fakeBin, "nohup", "/usr/bin/nohup");
        writeCommandProxy(fakeBin, "env", "/usr/bin/env");
        writeCommandProxy(fakeBin, "rm", "/bin/rm");
        writeCommandProxy(fakeBin, "basename", "/usr/bin/basename");
        writeCommandProxy(fakeBin, "sha256sum", "/usr/bin/sha256sum");
        writeCommandProxy(fakeBin, "awk", "/usr/bin/awk");
        writeCommandProxy(fakeBin, "grep", "/bin/grep");
        writeCommandProxy(fakeBin, "mktemp", "/usr/bin/mktemp");
        writeCommandProxy(fakeBin, "mv", "/bin/mv");
        writeExecutable(
                fakeBin.resolve("npm"),
                """
                #!/usr/bin/env bash
                set -euo pipefail
                prefix=""
                while [[ $# -gt 0 ]]; do
                  case "${1:-}" in
                    --prefix) prefix="${2:?missing prefix}"; shift 2 ;;
                    *) shift ;;
                  esac
                done
                mkdir -p "$prefix/bin"
                printf '#!/usr/bin/env bash\\nexit 0\\n' > "$prefix/bin/codex"
                chmod +x "$prefix/bin/codex"
                echo "npm $*" >> "${SYMPHONY_FAKE_LOG:?}"
                """);
        Path symphonyHome = temporaryDirectory.resolve("npm-codex-home");
        Path binDirectory = temporaryDirectory.resolve("npm-codex-bin");
        Path fakeLog = temporaryDirectory.resolve("npm-codex-fake-tools.log");
        Path uninstallScript = Path.of("uninstall.sh").toAbsolutePath();
        Path home = temporaryDirectory.resolve("npm-codex-user-home");
        Map<String, String> environment = Map.of(
                "PATH", fakeBin.toString(),
                "HOME", home.toString(),
                "SYMPHONY_TRELLO_REPO_URL", sourceRepository.toUri().toString(),
                "SYMPHONY_HOME", symphonyHome.toString(),
                "SYMPHONY_FAKE_LOG", fakeLog.toString(),
                "SHELL", "/bin/bash");

        // when
        ProcessResult install = runWithPseudoTerminal(
                environment,
                "y\napi-key\napi-token\nNpm Codex Board\n\n\n",
                "bash " + shellQuote(installScript.toString()) + " --bin-dir " + shellQuote(binDirectory.toString()));
        Map<String, String> uninstallEnvironment = new LinkedHashMap<>(environment);
        uninstallEnvironment.put("PATH", fakeBin + ":/bin:/usr/bin");
        ProcessResult uninstall = run(
                uninstallEnvironment,
                "bash",
                uninstallScript.toString(),
                "--yes",
                "--bin-dir",
                binDirectory.toString());

        // then
        assertThat(install.exitCode()).as(install.output()).isZero();
        assertThat(uninstall.exitCode()).as(uninstall.output()).isZero();
        assertThat(install.output())
                .contains(
                        "Install Codex CLI with Symphony-managed npm.",
                        "Install location: " + symphonyHome.resolve("npm"),
                        "This keeps system-wide npm packages unchanged.",
                        "Added " + binDirectory + " to PATH in " + home.resolve(".bashrc"),
                        "Added " + binDirectory + " to PATH in " + home.resolve(".profile"));
        assertThat(binDirectory.resolve("codex")).doesNotExist();
        assertThat(symphonyHome.resolve("npm")).doesNotExist();
    }

    @Test
    void posixInstallerUsesSystemdUserDirectoryAndXdgConfigAutostartEnvironment() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        assumeTrue(commandExists("git"));
        assumeTrue(commandExists("script"));
        Path installScript = Path.of("install.sh").toAbsolutePath();
        Path uninstallScript = Path.of("uninstall.sh").toAbsolutePath();
        Path sourceRepository = createSourceRepository(temporaryDirectory);
        Path fakeBin = createFakeToolchain(temporaryDirectory);
        Path home = temporaryDirectory.resolve("xdg-systemd-home");
        Path xdgDataHome = temporaryDirectory.resolve("xdg-systemd-data");
        Path xdgConfigHome = temporaryDirectory.resolve("xdg systemd config");
        Path xdgStateHome = temporaryDirectory.resolve("xdg-systemd-state");
        Path xdgCacheHome = temporaryDirectory.resolve("xdg-systemd-cache");
        Path binDirectory = temporaryDirectory.resolve("xdg-systemd-bin");
        Path fakeLog = temporaryDirectory.resolve("xdg-systemd.log");
        Path servicePath = xdgConfigHome.resolve("systemd/user/symphony-trello.service");
        Path homeServicePath = home.resolve(".config/systemd/user/symphony-trello.service");
        Path autostartEnvPath = xdgConfigHome.resolve("symphony-trello/autostart.env");
        Files.createDirectories(home);
        Files.createFile(temporaryDirectory.resolve("codex-authenticated"));
        Map<String, String> installEnvironment = Map.of(
                "PATH",
                fakeBin + File.pathSeparator + System.getenv("PATH"),
                "HOME",
                home.toString(),
                "XDG_DATA_HOME",
                xdgDataHome.toString(),
                "XDG_CONFIG_HOME",
                xdgConfigHome.toString(),
                "XDG_STATE_HOME",
                xdgStateHome.toString(),
                "XDG_CACHE_HOME",
                xdgCacheHome.toString(),
                "SYMPHONY_TRELLO_REPO_URL",
                sourceRepository.toUri().toString(),
                "SYMPHONY_TRELLO_REF",
                "main",
                "SYMPHONY_FAKE_LOG",
                fakeLog.toString());

        // when
        ProcessResult install = runWithPseudoTerminal(
                installEnvironment,
                "api-key\napi-token\nXDG Systemd Board\n\n\n",
                "bash " + shellQuote(installScript.toString()) + " --no-update-path --bin-dir "
                        + shellQuote(binDirectory.toString()));
        install.assertSuccess();
        String serviceContent = Files.readString(servicePath);
        String installContext = Files.readString(xdgStateHome.resolve("symphony-trello/install-context.properties"));
        Map<String, String> uninstallEnvironment = Map.of(
                "PATH",
                fakeBin + File.pathSeparator + System.getenv("PATH"),
                "HOME",
                home.toString(),
                "SYMPHONY_FAKE_LOG",
                fakeLog.toString());
        ProcessResult uninstall = run(
                uninstallEnvironment,
                "bash",
                uninstallScript.toString(),
                "--yes",
                "--bin-dir",
                binDirectory.toString());

        // then
        uninstall.assertSuccess();
        assertThat(servicePath).doesNotExist();
        assertThat(homeServicePath).doesNotExist();
        assertThat(autostartEnvPath).doesNotExist();
        assertThat(serviceContent)
                .contains("EnvironmentFile=-" + autostartEnvPath.toString().replace(" ", "\\x20"))
                .doesNotContain("%h/.config/symphony-trello/autostart.env");
        assertThat(installContext)
                .contains(
                        "systemd_user_dir=" + xdgConfigHome.resolve("systemd/user"),
                        "systemd_service_path=" + servicePath,
                        "autostart_env_path=" + autostartEnvPath);
        assertThat(fakeLog)
                .content(StandardCharsets.UTF_8)
                .contains(
                        "systemctl --user enable symphony-trello.service",
                        "systemctl --user restart symphony-trello.service",
                        "systemctl --user disable --now symphony-trello.service");
    }

    @MethodSource("posixInstallerHandoffScenarios")
    @ParameterizedTest(name = "{0}")
    void posixInstallerPrintsHandoffOnlyAfterManagedWorkerOutcome(InstallerHandoffScenario scenario) throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        assumeTrue(commandExists("git"));
        assumeTrue(commandExists("script"));
        Path installScript = Path.of("install.sh").toAbsolutePath();
        Path sourceRepository = createSourceRepository(temporaryDirectory);
        Path fakeBin = createFakeToolchain(temporaryDirectory);
        Path home = temporaryDirectory.resolve("handoff-" + scenario.slug() + "-home");
        Path symphonyHome = temporaryDirectory.resolve("handoff-" + scenario.slug() + "-symphony-home");
        Path binDirectory = temporaryDirectory.resolve("handoff-" + scenario.slug() + "-bin");
        Path fakeLog = temporaryDirectory.resolve("handoff-" + scenario.slug() + ".log");
        Files.createDirectories(home);
        Files.createFile(temporaryDirectory.resolve("codex-authenticated"));
        Map<String, String> environment = new LinkedHashMap<>(Map.of(
                "PATH",
                fakeBin + File.pathSeparator + System.getenv("PATH"),
                "HOME",
                home.toString(),
                "USER",
                "symphony-test",
                "SYMPHONY_TRELLO_REPO_URL",
                sourceRepository.toUri().toString(),
                "SYMPHONY_TRELLO_REF",
                "main",
                "SYMPHONY_HOME",
                symphonyHome.toString(),
                "SYMPHONY_FAKE_LOG",
                fakeLog.toString()));
        environment.putAll(scenario.environment());

        try {

            // when
            ProcessResult result = runWithPseudoTerminal(
                    environment,
                    "api-key\napi-token\nHandoff Queue\n",
                    "bash " + shellQuote(installScript.toString()) + " --no-update-path --bin-dir "
                            + shellQuote(binDirectory.toString()));

            // then
            assertThat(result.output())
                    .containsSubsequence(scenario.expectedOutput().toArray(String[]::new));
            if (scenario.success()) {
                result.assertSuccess();
                assertThat(result.output())
                        .containsOnlyOnce("You're good to go - your Trello board is now a queue for Codex work.");
                assertThat(result.output().stripTrailing())
                        .endsWith("symphony-trello logs --workflow '"
                                + symphonyHome.resolve("config/WORKFLOW.handoff-queue.md") + "'");
            } else {
                assertThat(result.exitCode()).as(result.output()).isNotZero();
                assertThat(result.output()).doesNotContain("You're good to go", "Useful commands:");
            }
        } finally {
            Path installedCommand = binDirectory.resolve("symphony-trello");
            if (Files.isExecutable(installedCommand)) {
                run(environment, installedCommand.toString(), "stop").assertSuccess();
            }
        }
    }

    private static Stream<InstallerHandoffScenario> posixInstallerHandoffScenarios() {
        return Stream.of(
                new InstallerHandoffScenario(
                        "lingering failure precedes handoff",
                        Map.of("SYMPHONY_FAKE_LINGER_FAILURE", "1"),
                        true,
                        List.of(
                                "Starting managed workers...",
                                "User systemd service enabled: symphony-trello.service",
                                "Could not enable user lingering. The service will start when the user session starts.",
                                "You're good to go - your Trello board is now a queue for Codex work.")),
                new InstallerHandoffScenario(
                        "unavailable service manager falls back before handoff",
                        Map.of("SYMPHONY_FAKE_SYSTEMD_UNAVAILABLE", "1"),
                        true,
                        List.of(
                                "Starting managed workers...",
                                "User systemd is unavailable in this session.",
                                "Autostart service was not configured.",
                                "start --all",
                                "You're good to go - your Trello board is now a queue for Codex work.")),
                new InstallerHandoffScenario(
                        "failed service setup falls back before handoff",
                        Map.of("SYMPHONY_FAKE_SYSTEMD_ENABLE_FAILURE", "1"),
                        true,
                        List.of(
                                "Starting managed workers...",
                                "Could not enable the user systemd service. Falling back to direct start.",
                                "Autostart service was not configured.",
                                "start --all",
                                "You're good to go - your Trello board is now a queue for Codex work.")),
                new InstallerHandoffScenario(
                        "failed fallback start suppresses handoff",
                        Map.of("SYMPHONY_FAKE_START_ALL_FAILURE", "1"),
                        false,
                        List.of(
                                "Starting managed workers...",
                                "Could not enable the user systemd service. Falling back to direct start.",
                                "Autostart service was not configured.",
                                "start --all",
                                "managed start --all failed")));
    }

    private record InstallerHandoffScenario(
            String name, Map<String, String> environment, boolean success, List<String> expectedOutput) {
        String slug() {
            return name.replace(' ', '-');
        }

        @Override
        public String toString() {
            return name;
        }
    }

    @ParameterizedTest(name = "pinned ref type: {0}")
    @ValueSource(strings = {"tag", "sha"})
    void posixInstallerRerunsPinnedNonBranchRefsWithoutPulling(String refType) throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        assumeTrue(commandExists("git"));
        Path installScript = Path.of("install.sh").toAbsolutePath();
        Path sourceRepository = createSourceRepository(temporaryDirectory);
        String ref = pinnedRef(sourceRepository, refType);
        Path fakeBin = createFakeToolchain(temporaryDirectory);
        Path symphonyHome = temporaryDirectory.resolve("pinned-" + refType + "-home");
        Path installPrefix = symphonyHome.resolve("app");
        Path binDirectory = temporaryDirectory.resolve("pinned-" + refType + "-bin");
        Path fakeLog = temporaryDirectory.resolve("pinned-" + refType + "-fake-tools.log");
        Map<String, String> environment = Map.of(
                "PATH", fakeBin + System.getProperty("path.separator") + System.getenv("PATH"),
                "SYMPHONY_TRELLO_REPO_URL", sourceRepository.toUri().toString(),
                "SYMPHONY_TRELLO_REF", ref,
                "SYMPHONY_HOME", symphonyHome.toString(),
                "SYMPHONY_FAKE_LOG", fakeLog.toString());

        // when
        ProcessResult install = run(
                environment, "bash", installScript.toString(), "--no-onboard", "--bin-dir", binDirectory.toString());
        addSourceRepositoryCommit(sourceRepository, "AFTER_PINNED_REF", "newer\n");
        ProcessResult rerun = run(
                environment, "bash", installScript.toString(), "--no-onboard", "--bin-dir", binDirectory.toString());

        // then
        assertThat(install.exitCode()).as(install.output()).isZero();
        assertThat(rerun.exitCode()).as(rerun.output()).isZero();
        assertThat(installPrefix.resolve("AFTER_PINNED_REF")).doesNotExist();
    }

    @Test
    void posixInstallerUpdateRunsCleanBuildAndRestartsManagedWorkers() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        assumeTrue(commandExists("git"));
        assumeTrue(commandExists("script"));
        Path installScript = Path.of("install.sh").toAbsolutePath();
        Path sourceRepository = createSourceRepository(temporaryDirectory);
        Path fakeBin = createFakeToolchain(temporaryDirectory);
        Path symphonyHome = temporaryDirectory.resolve("update-restart-home");
        Path binDirectory = temporaryDirectory.resolve("update-restart-bin");
        Path fakeLog = temporaryDirectory.resolve("update-restart-fake-tools.log");
        Files.createFile(temporaryDirectory.resolve("codex-authenticated"));
        Map<String, String> environment = Map.of(
                "PATH", fakeBin + System.getProperty("path.separator") + System.getenv("PATH"),
                "SYMPHONY_TRELLO_REPO_URL", sourceRepository.toUri().toString(),
                "SYMPHONY_HOME", symphonyHome.toString(),
                "SYMPHONY_FAKE_LOG", fakeLog.toString());

        try {

            // when
            ProcessResult install = runWithPseudoTerminal(
                    environment,
                    "api-key\napi-token\nDocs Queue\n\n\n",
                    "bash " + shellQuote(installScript.toString()) + " --bin-dir "
                            + shellQuote(binDirectory.toString()));
            addSourceRepositoryCommit(sourceRepository, "UPDATED", "updated\n");
            ProcessResult update = runWithPseudoTerminal(
                    environment,
                    "api-key\napi-token\nDocs Queue\n\n\n",
                    "bash " + shellQuote(installScript.toString()) + " --bin-dir "
                            + shellQuote(binDirectory.toString()));

            // then
            assertThat(install.exitCode()).as(install.output()).isZero();
            assertThat(update.exitCode()).as(update.output()).isZero();
            assertThat(update.output())
                    .contains(
                            "Stopping managed workers before update...",
                            "Restarting managed workers after update...",
                            "Stopped WORKFLOW.docs-queue.md",
                            "Starting setup...");
            assertThat(fakeLog)
                    .content(StandardCharsets.UTF_8)
                    .contains("mvnw -q -f " + symphonyHome.resolve("app/pom.xml") + " -DskipTests clean package")
                    .containsSubsequence(
                            "TrelloBoardSetupMain stop",
                            "mvnw -q -f " + symphonyHome.resolve("app/pom.xml") + " -DskipTests clean package",
                            "TrelloBoardSetupMain setup-local",
                            "TrelloBoardSetupMain start",
                            "--all");
        } finally {
            if (Files.exists(binDirectory.resolve("symphony-trello"))) {
                run(environment, binDirectory.resolve("symphony-trello").toString(), "stop");
            }
        }
    }

    @Test
    void posixNoOnboardUpdateRestartsStoppedWorkersWithStartAll() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        assumeTrue(commandExists("git"));
        assumeTrue(commandExists("script"));
        Path installScript = Path.of("install.sh").toAbsolutePath();
        Path sourceRepository = createSourceRepository(temporaryDirectory);
        Path fakeBin = createFakeToolchain(temporaryDirectory);
        Path symphonyHome = temporaryDirectory.resolve("no-onboard-update-home");
        Path binDirectory = temporaryDirectory.resolve("no-onboard-update-bin");
        Path fakeLog = temporaryDirectory.resolve("no-onboard-update-fake-tools.log");
        Files.createFile(temporaryDirectory.resolve("codex-authenticated"));
        Map<String, String> environment = Map.of(
                "PATH", fakeBin + System.getProperty("path.separator") + System.getenv("PATH"),
                "SYMPHONY_TRELLO_REPO_URL", sourceRepository.toUri().toString(),
                "SYMPHONY_HOME", symphonyHome.toString(),
                "SYMPHONY_FAKE_LOG", fakeLog.toString());

        try {
            ProcessResult install = runWithPseudoTerminal(
                    environment,
                    "n\napi-key\napi-token\nDocs Queue\n",
                    "bash " + shellQuote(installScript.toString()) + " --bin-dir "
                            + shellQuote(binDirectory.toString()));
            addSourceRepositoryCommit(sourceRepository, "UPDATED", "updated\n");

            // when
            ProcessResult update = run(
                    environment,
                    "bash",
                    installScript.toString(),
                    "--no-onboard",
                    "--bin-dir",
                    binDirectory.toString());

            // then
            assertThat(install.exitCode()).as(install.output()).isZero();
            assertThat(update.exitCode()).as(update.output()).isZero();
            assertThat(update.output())
                    .contains(
                            "Stopping managed workers before update...", "Restarting managed workers after update...");
            assertThat(fakeLog)
                    .content(StandardCharsets.UTF_8)
                    .containsSubsequence("TrelloBoardSetupMain stop", "TrelloBoardSetupMain start", "--all");
        } finally {
            if (Files.exists(binDirectory.resolve("symphony-trello"))) {
                run(environment, binDirectory.resolve("symphony-trello").toString(), "stop");
            }
        }
    }

    @Test
    void posixUpdateRemovesStalePidFilesWhenInstalledCommandIsMissing() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        assumeTrue(commandExists("git"));
        assumeTrue(commandExists("sleep"));
        Path installScript = Path.of("install.sh").toAbsolutePath();
        Path sourceRepository = createSourceRepository(temporaryDirectory);
        Path fakeBin = createFakeToolchain(temporaryDirectory);
        Path symphonyHome = temporaryDirectory.resolve("stale-pid-missing-command-home");
        Path binDirectory = temporaryDirectory.resolve("stale-pid-missing-command-bin");
        Path fakeLog = temporaryDirectory.resolve("stale-pid-missing-command-fake-tools.log");
        Files.createFile(temporaryDirectory.resolve("codex-authenticated"));
        Map<String, String> environment = Map.of(
                "PATH", fakeBin + System.getProperty("path.separator") + System.getenv("PATH"),
                "SYMPHONY_TRELLO_REPO_URL", sourceRepository.toUri().toString(),
                "SYMPHONY_HOME", symphonyHome.toString(),
                "SYMPHONY_FAKE_LOG", fakeLog.toString());

        ProcessResult install = run(
                environment, "bash", installScript.toString(), "--no-onboard", "--bin-dir", binDirectory.toString());
        Path command = binDirectory.resolve("symphony-trello");
        Path stalePid = symphonyHome.resolve("state/WORKFLOW.stale.pid");
        Path zeroPid = symphonyHome.resolve("state/WORKFLOW.zero.pid");
        Path reusedPid = symphonyHome.resolve("state/WORKFLOW.reused.pid");
        Files.writeString(stalePid, "999999\n");
        Files.writeString(zeroPid, "0\n");
        Files.delete(command);
        addSourceRepositoryCommit(sourceRepository, "UPDATED", "updated\n");
        Process unrelated = new ProcessBuilder("sleep", "60").start();

        try {
            Files.writeString(reusedPid, unrelated.pid() + "\n");

            // when
            ProcessResult update = run(
                    environment,
                    "bash",
                    installScript.toString(),
                    "--no-onboard",
                    "--bin-dir",
                    binDirectory.toString());

            // then
            assertThat(install.exitCode()).as(install.output()).isZero();
            assertThat(update.exitCode()).as(update.output()).isZero();
            assertThat(update.output())
                    .contains("Removing stale managed worker pid files before update...")
                    .doesNotContain("Stopping managed workers before update...")
                    .doesNotContain("Stop the running Symphony worker processes manually");
            assertThat(command).isExecutable();
            assertThat(stalePid).doesNotExist();
            assertThat(zeroPid).doesNotExist();
            assertThat(reusedPid).doesNotExist();
            assertThat(fakeLog).content(StandardCharsets.UTF_8).doesNotContain("TrelloBoardSetupMain stop");
        } finally {
            unrelated.destroyForcibly();
        }
    }

    @Test
    void posixInstallerGeneratedCommandHandlesPathsWithShellMetacharacters() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        assumeTrue(commandExists("git"));
        Path installScript = Path.of("install.sh").toAbsolutePath();
        Path sourceRepository = createSourceRepository(temporaryDirectory);
        Path fakeBin = createFakeToolchain(temporaryDirectory);
        Path symphonyHome = temporaryDirectory.resolve("home $value 'quoted'");
        Path binDirectory = temporaryDirectory.resolve("bin $value 'quoted'");
        Path fakeLog = temporaryDirectory.resolve("metachar-fake-tools.log");
        Map<String, String> environment = Map.of(
                "PATH", fakeBin + System.getProperty("path.separator") + System.getenv("PATH"),
                "SYMPHONY_TRELLO_REPO_URL", sourceRepository.toUri().toString(),
                "SYMPHONY_TRELLO_REF", "main",
                "SYMPHONY_HOME", symphonyHome.toString(),
                "SYMPHONY_FAKE_LOG", fakeLog.toString());

        // when
        ProcessResult install = run(
                environment, "bash", installScript.toString(), "--no-onboard", "--bin-dir", binDirectory.toString());
        assertThat(install.exitCode()).as(install.output()).isZero();
        ProcessResult syntax = run(
                environment,
                "bash",
                "-n",
                binDirectory.resolve("symphony-trello").toString());
        ProcessResult status =
                run(environment, binDirectory.resolve("symphony-trello").toString(), "status");

        // then
        assertThat(syntax.exitCode()).isZero();
        assertThat(status.exitCode()).isZero();
        assertThat(status.output()).contains("No managed Symphony process found");
        assertThat(binDirectory.resolve("symphony-trello"))
                .content(StandardCharsets.UTF_8)
                .contains("APP_HOME='"
                        + symphonyHome
                                .resolve("app")
                                .toAbsolutePath()
                                .normalize()
                                .toString()
                                .replace("'", "'\\''")
                        + "'");
        assertThat(binDirectory.resolve("symphony-trello"))
                .content(StandardCharsets.UTF_8)
                .contains("export SYMPHONY_TRELLO_COMMAND='"
                        + binDirectory
                                .resolve("symphony-trello")
                                .toAbsolutePath()
                                .normalize()
                                .toString()
                                .replace("'", "'\\''")
                        + "'");
    }

    @Test
    void posixInstallerPersistsHomebrewOpenJdkPathInWrapper() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        assumeTrue(commandExists("git"));
        Path sourceRepository = createSourceRepository(temporaryDirectory);
        Path fakeBin = temporaryDirectory.resolve("fake-brew-bin");
        Path brewOpenJdkBin = temporaryDirectory
                .resolve("homebrew")
                .resolve("opt")
                .resolve("openjdk@25")
                .resolve("bin");
        Path symphonyHome = temporaryDirectory.resolve("homebrew-home");
        Path binDirectory = temporaryDirectory.resolve("homebrew-bin");
        Path fakeLog = temporaryDirectory.resolve("homebrew-installer.log");
        Files.createDirectories(fakeBin);
        Files.createDirectories(brewOpenJdkBin);
        writeExecutable(
                fakeBin.resolve("brew"),
                """
                #!/usr/bin/env bash
                set -euo pipefail
                if [[ "${1:-}" == "--prefix" && "${2:-}" == "openjdk@25" ]]; then
                  printf '%s\\n' "$SYMPHONY_FAKE_BREW_OPENJDK_PREFIX"
                  exit 0
                fi
                echo "brew $*" >> "${SYMPHONY_FAKE_LOG:?}"
                exit 0
                """);
        writeExecutable(
                brewOpenJdkBin.resolve("java"),
                """
                #!/usr/bin/env bash
                set -euo pipefail
                echo 'openjdk version "25.0.1" 2026-04-21' >&2
                """);
        writeExecutable(
                brewOpenJdkBin.resolve("javac"),
                """
                #!/usr/bin/env bash
                set -euo pipefail
                echo 'javac 25.0.1'
                """);
        Map<String, String> environment = Map.of(
                "PATH", fakeBin + ":/usr/bin:/bin",
                "SYMPHONY_TRELLO_TEST_OS", "Darwin",
                "SYMPHONY_TRELLO_TEST_ARCH", "arm64",
                "SYMPHONY_TRELLO_REPO_URL", sourceRepository.toUri().toString(),
                "SYMPHONY_HOME", symphonyHome.toString(),
                "SYMPHONY_FAKE_LOG", fakeLog.toString(),
                "SYMPHONY_FAKE_BREW_OPENJDK_PREFIX", brewOpenJdkBin.getParent().toString());

        // when
        ProcessResult install =
                run(environment, "bash", "install.sh", "--no-onboard", "--bin-dir", binDirectory.toString());

        // then
        assertThat(install.exitCode()).as(install.output()).isZero();
        assertThat(binDirectory.resolve("symphony-trello"))
                .content(StandardCharsets.UTF_8)
                .contains(
                        "BREW_OPENJDK_BIN='" + brewOpenJdkBin.toAbsolutePath().normalize() + "'",
                        "export PATH=\"$BREW_OPENJDK_BIN:$PATH\"");
    }

    @Test
    void posixInstallerRefusesUnmarkedUnrelatedExistingCheckout() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        assumeTrue(commandExists("git"));
        Path sourceRepository = createSourceRepository(temporaryDirectory);
        Path existingCheckout = createUnmarkedCheckout("unrelated-posix-checkout");
        Path fakeBin = createFakeToolchain(temporaryDirectory);
        Path fakeLog = temporaryDirectory.resolve("unrelated-posix.log");
        Map<String, String> environment = Map.of(
                "PATH", fakeBin + File.pathSeparator + System.getenv("PATH"),
                "SYMPHONY_TRELLO_REPO_URL", sourceRepository.toUri().toString(),
                "SYMPHONY_FAKE_LOG", fakeLog.toString());

        // when
        ProcessResult result = run(
                environment,
                "bash",
                "install.sh",
                "--no-onboard",
                "--prefix",
                existingCheckout.toString(),
                "--bin-dir",
                temporaryDirectory.resolve("unrelated-posix-bin").toString());

        // then
        assertThat(result.exitCode()).as(result.output()).isEqualTo(2);
        assertThat(result.output())
                .contains("Refusing to update existing Git checkout without Symphony installer marker");
        assertThat(existingCheckout.resolve(".symphony-trello-install")).doesNotExist();
    }

    @Test
    void posixInstallerUpdatesMarkedCheckoutOriginBeforeFetching() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        assumeTrue(commandExists("git"));
        Path oldRepository = createSourceRepository(temporaryDirectory.resolve("old-origin"));
        Path sourceRepository = createSourceRepository(temporaryDirectory.resolve("new-origin"));
        Path fakeBin = createFakeToolchain(temporaryDirectory);
        Path symphonyHome = temporaryDirectory.resolve("remote-update-home");
        Path appHome = symphonyHome.resolve("app");
        Path binDirectory = temporaryDirectory.resolve("remote-update-bin");
        Path fakeLog = temporaryDirectory.resolve("remote-update.log");
        Files.createDirectories(appHome);
        run(Map.of(), "git", "-C", appHome.toString(), "init", "-b", "main").assertSuccess();
        run(
                        Map.of(),
                        "git",
                        "-C",
                        appHome.toString(),
                        "remote",
                        "add",
                        "origin",
                        oldRepository.toUri().toString())
                .assertSuccess();
        Files.createFile(appHome.resolve(".symphony-trello-install"));
        Map<String, String> environment = Map.of(
                "PATH", fakeBin + File.pathSeparator + System.getenv("PATH"),
                "SYMPHONY_TRELLO_REPO_URL", sourceRepository.toUri().toString(),
                "SYMPHONY_HOME", symphonyHome.toString(),
                "SYMPHONY_FAKE_LOG", fakeLog.toString());

        // when
        ProcessResult result =
                run(environment, "bash", "install.sh", "--no-onboard", "--bin-dir", binDirectory.toString());

        // then
        result.assertSuccess();
        ProcessResult origin = run(Map.of(), "git", "-C", appHome.toString(), "remote", "get-url", "origin");
        origin.assertSuccess();
        assertThat(origin.output().trim()).isEqualTo(sourceRepository.toUri().toString());
        assertThat(result.output()).contains("remote set-url origin " + sourceRepository.toUri());
    }

    @Test
    void posixInstallerReplacesMarkedArchiveInstallWhenSwitchingToSourceCheckout() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        assumeTrue(commandExists("git"));
        Path sourceRepository = createSourceRepository(temporaryDirectory);
        Path fakeBin = createFakeToolchain(temporaryDirectory);
        Path symphonyHome = temporaryDirectory.resolve("archive-to-source-home");
        Path appHome = symphonyHome.resolve("app");
        Path binDirectory = temporaryDirectory.resolve("archive-to-source-bin");
        Path fakeLog = temporaryDirectory.resolve("archive-to-source.log");
        Files.createDirectories(appHome);
        Files.writeString(appHome.resolve(".symphony-trello-install"), "installer-managed archive\n");
        Files.writeString(appHome.resolve("old-archive-file.txt"), "old archive content\n");
        Map<String, String> environment = Map.of(
                "PATH", fakeBin + File.pathSeparator + System.getenv("PATH"),
                "SYMPHONY_HOME", symphonyHome.toString(),
                "SYMPHONY_FAKE_LOG", fakeLog.toString());

        // when
        ProcessResult result = run(
                environment,
                "bash",
                "install.sh",
                "--no-onboard",
                "--from-source",
                "--repo",
                sourceRepository.toUri().toString(),
                "--ref",
                "main",
                "--bin-dir",
                binDirectory.toString());

        // then
        result.assertSuccess();
        assertThat(appHome.resolve(".git")).isDirectory();
        assertThat(appHome.resolve("old-archive-file.txt")).doesNotExist();
        assertThat(result.output()).contains("rm -rf " + appHome, "git clone");
    }

    @Test
    void powershellInstallerRefusesUnmarkedUnrelatedExistingCheckoutWhenAvailable() throws Exception {
        // given
        List<String> pwsh = powershellCommand();
        assumeFalse(pwsh.isEmpty());
        assumeTrue(commandExists("git"));
        Path sourceRepository = createPowerShellSourceRepository(temporaryDirectory);
        Path existingCheckout = createUnmarkedCheckout("unrelated-powershell-checkout");
        Path fakeBin = createPowerShellFakeToolchain(temporaryDirectory);
        Path fakeLog = temporaryDirectory.resolve("unrelated-powershell.log");
        Map<String, String> environment = new LinkedHashMap<>(nonWindowsPowerShellEnvironment());
        environment.put("PATH", fakeBin + File.pathSeparator + System.getenv("PATH"));
        environment.put("SYMPHONY_FAKE_JAVA", fakeBin.resolve("fake-java.ps1").toString());
        environment.put("SYMPHONY_FAKE_LOG", fakeLog.toString());

        // when
        ProcessResult result = run(
                environment,
                command(
                                pwsh,
                                "-NoProfile",
                                "-File",
                                "./install.ps1",
                                "--no-onboard",
                                "--prefix",
                                existingCheckout.toString(),
                                "--bin-dir",
                                temporaryDirectory
                                        .resolve("unrelated-powershell-bin")
                                        .toString(),
                                "--repo",
                                sourceRepository.toUri().toString())
                        .toArray(String[]::new));

        // then
        assertThat(result.exitCode()).as(result.output()).isOne();
        assertThat(normalizedWhitespace(result.output()))
                .contains(
                        "Refusing to update existing Git checkout without Symphony installer",
                        "marker:",
                        "Use",
                        "dedicated",
                        "app checkout path");
        assertThat(existingCheckout.resolve(".symphony-trello-install")).doesNotExist();
    }

    @Test
    void powershellInstallerReplacesMarkedArchiveInstallWhenSwitchingToSourceCheckoutWhenAvailable() throws Exception {
        // given
        List<String> pwsh = powershellCommand();
        assumeFalse(pwsh.isEmpty());
        assumeTrue(commandExists("git"));
        Path sourceRepository = createPowerShellSourceRepository(temporaryDirectory);
        Path fakeBin = createPowerShellFakeToolchain(temporaryDirectory);
        Path symphonyHome = temporaryDirectory.resolve("ps-archive-to-source-home");
        Path appHome = symphonyHome.resolve("app");
        Path binDirectory = temporaryDirectory.resolve("ps-archive-to-source-bin");
        Path fakeLog = temporaryDirectory.resolve("ps-archive-to-source.log");
        Files.createDirectories(appHome);
        Files.writeString(appHome.resolve(".symphony-trello-install"), "installer-managed archive\n");
        Files.writeString(appHome.resolve("old-archive-file.txt"), "old archive content\n");

        // when
        ProcessResult result = runPowerShellSourceCheckoutInstall(
                pwsh, sourceRepository, fakeBin, symphonyHome, binDirectory, fakeLog);

        // then
        result.assertSuccess();
        assertThat(appHome.resolve(".git")).isDirectory();
        assertThat(appHome.resolve("old-archive-file.txt")).doesNotExist();
        assertThat(result.output())
                .contains(
                        "remove existing release archive app",
                        appHome.getFileName().toString(),
                        "git clone");
    }

    @Test
    void powershellSourceCheckoutInstallContextUsesBuiltVersionAndSourceCommitWhenAvailable() throws Exception {
        // given
        List<String> pwsh = powershellCommand();
        assumeFalse(pwsh.isEmpty());
        assumeTrue(commandExists("git"));
        Path sourceRepository = createPowerShellSourceRepository(temporaryDirectory);
        String expectedCommit = run(Map.of(), "git", "-C", sourceRepository.toString(), "rev-parse", "HEAD")
                .output()
                .trim();
        Path fakeBin = createPowerShellFakeToolchain(temporaryDirectory);
        Path symphonyHome = temporaryDirectory.resolve("ps-source-context-home");
        Path binDirectory = temporaryDirectory.resolve("ps-source-context-bin");
        Path fakeLog = temporaryDirectory.resolve("ps-source-context.log");

        // when
        ProcessResult result = runPowerShellSourceCheckoutInstall(
                pwsh, sourceRepository, fakeBin, symphonyHome, binDirectory, fakeLog);

        // then
        result.assertSuccess();
        assertThat(symphonyHome.resolve("state/install-context.properties"))
                .content(StandardCharsets.UTF_8)
                .contains("install_source=source-checkout", "app_version=test", "source_commit=" + expectedCommit)
                .doesNotContain(
                        "app_version=" + installerDefaultRef().substring(1), "release_tag=", "release_base_url=");
    }

    @ParameterizedTest(name = "PowerShell source checkout app_version fallback for {0} version output")
    @ValueSource(strings = {"fail", "malformed", "multiline"})
    void powershellSourceCheckoutInstallContextUsesUnknownVersionWhenBuiltVersionIsNotProven(String versionMode)
            throws Exception {
        // given
        List<String> pwsh = powershellCommand();
        assumeFalse(pwsh.isEmpty());
        assumeTrue(commandExists("git"));
        Path sourceRepository = createPowerShellSourceRepository(temporaryDirectory);
        String expectedCommit = run(Map.of(), "git", "-C", sourceRepository.toString(), "rev-parse", "HEAD")
                .output()
                .trim();
        Path fakeBin = createPowerShellFakeToolchain(temporaryDirectory);
        Path symphonyHome = temporaryDirectory.resolve("ps-source-context-" + versionMode + "-home");
        Path binDirectory = temporaryDirectory.resolve("ps-source-context-" + versionMode + "-bin");
        Path fakeLog = temporaryDirectory.resolve("ps-source-context-" + versionMode + ".log");

        // when
        ProcessResult result = runPowerShellSourceCheckoutInstall(
                pwsh,
                sourceRepository,
                fakeBin,
                symphonyHome,
                binDirectory,
                fakeLog,
                Map.of("SYMPHONY_FAKE_VERSION_MODE", versionMode));

        // then
        result.assertSuccess();
        assertThat(symphonyHome.resolve("state/install-context.properties"))
                .content(StandardCharsets.UTF_8)
                .contains("install_source=source-checkout", "app_version=unknown", "source_commit=" + expectedCommit)
                .doesNotContain(
                        "app_version=" + installerDefaultRef().substring(1), "release_tag=", "release_base_url=");
    }

    private static ProcessResult runPowerShellSourceCheckoutInstall(
            List<String> pwsh, Path sourceRepository, Path fakeBin, Path symphonyHome, Path binDirectory, Path fakeLog)
            throws Exception {
        return runPowerShellSourceCheckoutInstall(
                pwsh, sourceRepository, fakeBin, symphonyHome, binDirectory, fakeLog, Map.of());
    }

    private static Map<String, String> installEnvironmentForCustomHome(
            Path sourceRepository, Path fakeBin, Path home, Path symphonyHome, Path fakeLog) {
        Map<String, String> environment = new LinkedHashMap<>();
        environment.put("HOME", home.toString());
        environment.put("SYMPHONY_HOME", symphonyHome.toString());
        environment.put("PATH", fakeBin + File.pathSeparator + System.getenv("PATH"));
        environment.put("SYMPHONY_TRELLO_REPO_URL", sourceRepository.toUri().toString());
        environment.put("SYMPHONY_TRELLO_REF", "main");
        environment.put("SYMPHONY_FAKE_LOG", fakeLog.toString());
        return environment;
    }

    private static ProcessResult runPowerShellSourceCheckoutInstall(
            List<String> pwsh,
            Path sourceRepository,
            Path fakeBin,
            Path symphonyHome,
            Path binDirectory,
            Path fakeLog,
            Map<String, String> extraEnvironment)
            throws Exception {
        Map<String, String> environment = new LinkedHashMap<>(nonWindowsPowerShellEnvironment());
        environment.put("PATH", fakeBin + File.pathSeparator + System.getenv("PATH"));
        environment.put("SYMPHONY_HOME", symphonyHome.toString());
        environment.put("SYMPHONY_FAKE_JAVA", fakeBin.resolve("fake-java.ps1").toString());
        environment.put("SYMPHONY_FAKE_LOG", fakeLog.toString());
        environment.putAll(extraEnvironment);
        return run(
                environment,
                command(
                                pwsh,
                                "-NoProfile",
                                "-File",
                                "./install.ps1",
                                "--no-onboard",
                                "--from-source",
                                "--repo",
                                sourceRepository.toUri().toString(),
                                "--ref",
                                "main",
                                "--bin-dir",
                                binDirectory.toString())
                        .toArray(String[]::new));
    }

    private static Path createPowerShellSourceRepository(Path temporaryDirectory) throws Exception {
        if (isWindows()) {
            return createWindowsSourceRepository(temporaryDirectory);
        }
        Path repository = createSourceRepository(temporaryDirectory);
        writeExecutable(
                repository.resolve("mvnw.cmd"),
                """
                #!/usr/bin/env bash
                set -euo pipefail
                echo "mvnw.cmd $*" >> "${SYMPHONY_FAKE_LOG:?}"
                app_home="$(cd "$(dirname "$0")" && pwd -P)"
                mkdir -p "$app_home/target/quarkus-app/app" "$app_home/target/quarkus-app/lib/main" "$app_home/target/quarkus-app/quarkus"
                : > "$app_home/target/quarkus-app/quarkus-run.jar"
                """);
        run(Map.of(), "git", "-C", repository.toString(), "add", "mvnw.cmd").assertSuccess();
        run(Map.of(), "git", "-C", repository.toString(), "commit", "-m", "Add PowerShell test wrapper")
                .assertSuccess();
        return repository;
    }

    private static Path createPowerShellFakeToolchain(Path temporaryDirectory) throws IOException {
        if (isWindows()) {
            return createFakeWindowsToolchain(temporaryDirectory);
        }
        return createFakeToolchain(temporaryDirectory);
    }

    @Test
    void posixStartResolvesRelativeEnvAndWorkflowFromCallerDirectory() throws Exception {
        // given
        assumeTrue(commandExists("bash"));
        assumeTrue(commandExists("git"));
        assumeTrue(commandExists("timeout"));
        Path installScript = Path.of("install.sh").toAbsolutePath();
        Path uninstallScript = Path.of("uninstall.sh").toAbsolutePath();
        Path sourceRepository = createSourceRepository(temporaryDirectory);
        Path fakeBin = createFakeToolchain(temporaryDirectory);
        Path symphonyHome = temporaryDirectory.resolve("relative-home");
        Path binDirectory = temporaryDirectory.resolve("relative-bin");
        Path callerDirectory = temporaryDirectory.resolve("caller");
        Path fakeLog = temporaryDirectory.resolve("relative-fake-tools.log");
        Files.createDirectories(callerDirectory);
        Files.writeString(callerDirectory.resolve(".env.relative"), "TRELLO_API_KEY=key\n");
        Files.writeString(callerDirectory.resolve("WORKFLOW.relative.md"), "# Relative\n");
        Map<String, String> environment = Map.of(
                "PATH", fakeBin + System.getProperty("path.separator") + System.getenv("PATH"),
                "SYMPHONY_TRELLO_REPO_URL", sourceRepository.toUri().toString(),
                "SYMPHONY_TRELLO_REF", "main",
                "SYMPHONY_HOME", symphonyHome.toString(),
                "SYMPHONY_FAKE_LOG", fakeLog.toString());

        // when
        ProcessResult install = run(
                environment, "bash", installScript.toString(), "--no-onboard", "--bin-dir", binDirectory.toString());
        Files.writeString(
                symphonyHome.resolve("config").resolve(ConnectedBoardManifest.FILE_NAME),
                """
                {"boards":[{"boardId":"board-1","boardName":"Relative","workflowPath":"%s","envPath":"%s"}]}
                """
                        .formatted(
                                callerDirectory.resolve("WORKFLOW.relative.md"),
                                callerDirectory.resolve(".env.relative")));
        ProcessResult start = run(
                environment,
                callerDirectory,
                binDirectory.resolve("symphony-trello").toString(),
                "start",
                "--workflow",
                callerDirectory.resolve("WORKFLOW.relative.md").toString());
        ProcessResult status = run(
                environment,
                callerDirectory,
                binDirectory.resolve("symphony-trello").toString(),
                "status",
                "--workflow",
                callerDirectory.resolve("WORKFLOW.relative.md").toString());
        ProcessResult logs = run(
                environment,
                callerDirectory,
                "timeout",
                "2",
                binDirectory.resolve("symphony-trello").toString(),
                "logs",
                "--workflow",
                callerDirectory.resolve("WORKFLOW.relative.md").toString(),
                "--follow");
        Path pidFile = singleFile(symphonyHome.resolve("state"), ".pid");
        long managedPid = Long.parseLong(Files.readString(pidFile).trim());
        ProcessResult stop = run(
                environment,
                callerDirectory,
                binDirectory.resolve("symphony-trello").toString(),
                "stop",
                "--workflow",
                callerDirectory.resolve("WORKFLOW.relative.md").toString());
        ProcessResult uninstall =
                run(environment, "bash", uninstallScript.toString(), "--yes", "--bin-dir", binDirectory.toString());

        // then
        assertThat(install.exitCode()).isZero();
        assertThat(start.exitCode()).isZero();
        assertThat(status.output()).contains("running WORKFLOW.relative.md");
        assertThat(logs.exitCode()).as(logs.output()).isEqualTo(124);
        assertThat(stop.output()).contains("Stopped WORKFLOW.relative.md");
        assertThat(uninstall.exitCode()).isZero();
        assertThat(processStopsWithin(managedPid, 5))
                .as("the relative-workflow managed process stops within 5 seconds")
                .isTrue();
        assertThat(fakeLog)
                .content()
                .contains(
                        "setup-cli cwd=" + callerDirectory,
                        callerDirectory.resolve("WORKFLOW.relative.md").toString(),
                        "dotenv=" + callerDirectory.resolve(".env.relative"));
    }

    private Path createUnmarkedCheckout(String name) throws Exception {
        Path checkout = temporaryDirectory.resolve(name);
        Files.createDirectories(checkout);
        Files.writeString(checkout.resolve("README.md"), "unrelated\n");
        run(Map.of(), "git", "-C", checkout.toString(), "init", "-b", "main").assertSuccess();
        run(Map.of(), "git", "-C", checkout.toString(), "config", "user.name", "Test User")
                .assertSuccess();
        run(Map.of(), "git", "-C", checkout.toString(), "config", "user.email", "test@example.invalid")
                .assertSuccess();
        run(Map.of(), "git", "-C", checkout.toString(), "add", ".").assertSuccess();
        run(Map.of(), "git", "-C", checkout.toString(), "commit", "-m", "Initial unrelated checkout")
                .assertSuccess();
        run(
                        Map.of(),
                        "git",
                        "-C",
                        checkout.toString(),
                        "remote",
                        "add",
                        "origin",
                        "https://example.invalid/unrelated.git")
                .assertSuccess();
        return checkout;
    }

    private static ProcessResult runWithoutHome(String... command) throws Exception {
        var processBuilder = new ProcessBuilder(command);
        processBuilder.environment().remove("HOME");
        return run(processBuilder, "", 60);
    }

    private static ProcessResult runUnchecked(Map<String, String> environment, String... command) {
        try {
            return run(environment, command);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static ProcessResult runBtrfsUninstall(Path fakeBin, Path mountRoot, Path symphonyHome, Path btrfsLog)
            throws Exception {
        writeExecutable(
                fakeBin.resolve("findmnt"),
                """
                #!/usr/bin/env bash
                set -euo pipefail
                echo "${SYMPHONY_FAKE_BTRFS_MOUNT:?}"
                """);
        Map<String, String> environment = Map.of(
                "PATH", fakeBin + File.pathSeparator + System.getenv("PATH"),
                "SYMPHONY_HOME", symphonyHome.toString(),
                "SYMPHONY_FAKE_APP", symphonyHome.resolve("app").toString(),
                "SYMPHONY_FAKE_BTRFS_MOUNT", mountRoot.toString(),
                "SYMPHONY_FAKE_LOG", btrfsLog.toString());
        return run(environment, "bash", "uninstall.sh", "--yes");
    }

    private static String assertBtrfsSubvolumeDeletes(Path btrfsLog, Path app) throws IOException {
        String log = Files.readString(btrfsLog);
        return assertThat(log)
                .containsSubsequence(
                        "delete " + app.resolve("nested/deeper"), "delete " + app.resolve("nested"), "delete " + app)
                .actual();
    }

    private static String normalizedWhitespace(String text) {
        return text.replace("|", "").replaceAll("\\s+", " ");
    }

    private CustomPrefixUninstallFixture customPrefixUninstallFixture(
            String prefix, Path defaultSymphonyHome, String commandName) throws IOException {
        Path app = temporaryDirectory.resolve(prefix + "-app");
        Path bin = temporaryDirectory.resolve(prefix + "-bin");
        Files.createDirectories(defaultSymphonyHome.resolve("config"));
        Files.createDirectories(defaultSymphonyHome.resolve("workspaces"));
        Files.createDirectories(defaultSymphonyHome.resolve("state"));
        Files.createDirectories(app);
        Files.createDirectories(bin);
        Files.createFile(app.resolve(".symphony-trello-install"));
        Files.writeString(defaultSymphonyHome.resolve("config/.env"), "TRELLO_API_KEY=secret\n");
        Files.writeString(defaultSymphonyHome.resolve("workspaces/card.txt"), "work\n");
        Files.writeString(defaultSymphonyHome.resolve("state/worker.pid"), "123\n");
        Files.writeString(bin.resolve(commandName), "launcher\n");
        return new CustomPrefixUninstallFixture(defaultSymphonyHome, app, bin);
    }

    private static void assertCustomPrefixUninstallFixturePreserved(
            CustomPrefixUninstallFixture fixture, String commandName) {
        assertThat(fixture.app().resolve(".symphony-trello-install")).exists();
        assertThat(fixture.bin().resolve(commandName)).exists();
        assertThat(fixture.defaultSymphonyHome().resolve("config/.env")).exists();
        assertThat(fixture.defaultSymphonyHome().resolve("workspaces/card.txt")).exists();
        assertThat(fixture.defaultSymphonyHome().resolve("state/worker.pid")).exists();
    }

    private static List<ProcessResult> runPosixAppPathCases(
            Map<String, String> environment,
            String script,
            List<String> fixedArgs,
            List<UnsafeInstallerPath> cases,
            Path safeBin) {
        return cases.stream()
                .map(appPath -> {
                    List<String> command = new ArrayList<>(List.of("bash", script));
                    command.addAll(fixedArgs);
                    command.add("--prefix");
                    command.add(appPath.value());
                    command.add("--bin-dir");
                    command.add(safeBin.toString());
                    return runUnchecked(environment, command.toArray(String[]::new));
                })
                .toList();
    }

    private static List<ProcessResult> runPowerShellAppPathCases(
            Map<String, String> environment,
            List<String> pwsh,
            String script,
            List<String> fixedArgs,
            List<UnsafeInstallerPath> cases,
            Path safeBin) {
        return cases.stream()
                .map(appPath -> {
                    List<String> command = new ArrayList<>(command(pwsh, "-NoProfile", "-File", script));
                    command.addAll(fixedArgs);
                    command.add("--prefix");
                    command.add(appPath.value());
                    command.add("--bin-dir");
                    command.add(safeBin.toString());
                    return runUnchecked(environment, command.toArray(String[]::new));
                })
                .toList();
    }

    private static void assertUnsafeAppPathFailures(
            List<UnsafeInstallerPath> cases, List<ProcessResult> results, OutputStyle outputStyle, String... absent) {
        for (int index = 0; index < cases.size(); index++) {
            UnsafeInstallerPath appPath = cases.get(index);
            ProcessResult result = results.get(index);
            assertThat(result.exitCode()).as(appPath.name()).isNotZero();
            assertThat(outputStyle.apply(result.output()))
                    .as(appPath.name())
                    .containsAnyOf(appPath.expectedMessages().toArray(String[]::new))
                    .doesNotContain(absent);
        }
    }

    private List<UnsafeCommandDirectory> posixUnsafeCommandDirectories(
            Path home, Path file, Path symlink, String symlinkName, UnsafeCommandDirectory extraCase) {
        List<UnsafeCommandDirectory> cases = new ArrayList<>(List.of(
                new UnsafeCommandDirectory("root", "/", "--bin-dir must point to a dedicated command directory."),
                new UnsafeCommandDirectory(
                        "home", home.toString(), "--bin-dir must point to a dedicated command directory."),
                new UnsafeCommandDirectory(
                        "checkout",
                        Path.of("").toAbsolutePath().normalize().toString(),
                        "--bin-dir must point to a dedicated command directory."),
                new UnsafeCommandDirectory(
                        "usr-bin", "/usr/bin", "--bin-dir must point to a dedicated command directory."),
                new UnsafeCommandDirectory("blank", "", "--bin-dir must not be blank."),
                new UnsafeCommandDirectory("whitespace", "   ", "--bin-dir must not be blank."),
                new UnsafeCommandDirectory("relative", "relative-bin", "--bin-dir must be an absolute path."),
                new UnsafeCommandDirectory(
                        "control",
                        temporaryDirectory.resolve("bin\nline").toString(),
                        "--bin-dir must not contain control characters."),
                new UnsafeCommandDirectory("file", file.toString(), "--bin-dir must be a directory.")));
        cases.addAll(unsafeCommandDirectorySymlinkCases(
                symlink,
                symlinkName,
                "--bin-dir must not overlap Symphony app, config, workspace, or state directories.",
                false));
        cases.add(extraCase);
        return List.copyOf(cases);
    }

    private List<UnsafeCommandDirectory> powershellUnsafeCommandDirectories(
            Path file, Path symlink, String symlinkName, UnsafeCommandDirectory extraCase) {
        List<UnsafeCommandDirectory> cases = new ArrayList<>(List.of(
                new UnsafeCommandDirectory(
                        "root", platformRootPath(), "--bin-dir must point to a dedicated command directory."),
                new UnsafeCommandDirectory("relative", "relative-bin", "--bin-dir must be an absolute path."),
                new UnsafeCommandDirectory("drive-relative", "C:relative-bin", "--bin-dir must be an absolute path."),
                new UnsafeCommandDirectory("root-relative", "\\relative-bin", "--bin-dir must be an absolute path."),
                new UnsafeCommandDirectory(
                        "control",
                        temporaryDirectory.resolve("bin") + "\nline",
                        "--bin-dir must not contain control characters."),
                new UnsafeCommandDirectory("file", file.toString(), "--bin-dir must be a directory.")));
        cases.addAll(unsafeCommandDirectorySymlinkCases(symlink, symlinkName));
        cases.add(extraCase);
        return List.copyOf(cases);
    }

    private List<UnsafeCommandDirectory> unsafeCommandDirectorySymlinkCases(Path symlink, String symlinkName) {
        return unsafeCommandDirectorySymlinkCases(symlink, symlinkName, "--bin-dir must not be a symlink.", true);
    }

    private List<UnsafeCommandDirectory> unsafeCommandDirectorySymlinkCases(
            Path symlink, String symlinkName, String expectedMessage, boolean includeTraversal) {
        List<UnsafeCommandDirectory> cases = new ArrayList<>(List.of(
                new UnsafeCommandDirectory("symlink", symlink.toString(), expectedMessage),
                new UnsafeCommandDirectory(
                        "symlink-parent", symlink.resolve("bin").toString(), expectedMessage)));
        if (includeTraversal) {
            cases.add(new UnsafeCommandDirectory(
                    "symlink-traversal", symlink.resolve("../bin").toString(), expectedMessage));
            cases.add(new UnsafeCommandDirectory(
                    "symlink-after-traversal",
                    temporaryDirectory
                            .resolve("missing")
                            .resolve("../" + symlinkName)
                            .resolve("bin")
                            .toString(),
                    expectedMessage));
        }
        return List.copyOf(cases);
    }

    private List<UnsafeInstallerPath> posixUnsafeAppPaths(
            Path home, Path file, Path symlink, String symlinkName, UnsafeInstallerPath extraCase) {
        List<UnsafeInstallerPath> cases = new ArrayList<>(posixUnsafeAppPaths(home, file, symlink, symlinkName));
        cases.add(extraCase);
        return List.copyOf(cases);
    }

    private List<UnsafeInstallerPath> posixUnsafeAppPaths(Path home, Path file, Path symlink, String symlinkName) {
        List<UnsafeInstallerPath> cases = new ArrayList<>(List.of(
                new UnsafeInstallerPath("root", "/", "--prefix must point to a dedicated app checkout directory."),
                new UnsafeInstallerPath(
                        "root-home", "/root", "--prefix must point to a dedicated app checkout directory."),
                new UnsafeInstallerPath(
                        "home", home.toString(), "--prefix must point to a dedicated app checkout directory."),
                new UnsafeInstallerPath(
                        "checkout",
                        Path.of("").toAbsolutePath().normalize().toString(),
                        "--prefix must point to a dedicated app checkout directory."),
                new UnsafeInstallerPath("blank", "", "--prefix must not be blank."),
                new UnsafeInstallerPath("whitespace", "   ", "--prefix must not be blank."),
                new UnsafeInstallerPath("relative", "relative-app", "--prefix must be an absolute path."),
                new UnsafeInstallerPath(
                        "control", temporaryDirectory.resolve("app\nline").toString(), "control characters"),
                new UnsafeInstallerPath("file", file.toString(), "--prefix must be a directory.")));
        return List.copyOf(cases);
    }

    private List<UnsafeInstallerPath> unsafeAppSymlinkCases(Path symlink, String symlinkName) {
        return List.of(
                new UnsafeInstallerPath("symlink", symlink.toString(), "--prefix must not be a symlink."),
                new UnsafeInstallerPath(
                        "symlink-parent", symlink.resolve("app").toString(), "--prefix must not be a symlink."),
                new UnsafeInstallerPath(
                        "symlink-traversal", symlink.resolve("../app").toString(), "--prefix must not be a symlink."),
                new UnsafeInstallerPath(
                        "symlink-after-traversal",
                        temporaryDirectory
                                .resolve("missing")
                                .resolve("../" + symlinkName)
                                .toString(),
                        "--prefix must not be a symlink."));
    }

    private List<UnsafeInstallerPath> powershellUnsafeAppPaths(
            Path file, Path symlink, String symlinkName, UnsafeInstallerPath extraCase) {
        List<UnsafeInstallerPath> cases = new ArrayList<>(powershellUnsafeAppPaths(file, symlink, symlinkName));
        cases.add(extraCase);
        return List.copyOf(cases);
    }

    private List<UnsafeInstallerPath> powershellUnsafeAppPaths(Path file, Path symlink, String symlinkName) {
        List<UnsafeInstallerPath> cases = new ArrayList<>(List.of(
                new UnsafeInstallerPath(
                        "root",
                        platformRootPath(),
                        List.of(
                                "--prefix must point to a dedicated app checkout directory.",
                                "--prefix must not overlap Symphony config, workspace, or state directories.")),
                new UnsafeInstallerPath(
                        "checkout",
                        Path.of("").toAbsolutePath().normalize().toString(),
                        "--prefix must point to a dedicated app checkout directory."),
                new UnsafeInstallerPath("relative", "relative-app", "--prefix must be an absolute path."),
                new UnsafeInstallerPath("drive-relative", "C:relative-app", "--prefix must be an absolute path."),
                new UnsafeInstallerPath("root-relative", "\\relative-app", "--prefix must be an absolute path."),
                new UnsafeInstallerPath("control", temporaryDirectory.resolve("app") + "\nline", "control characters"),
                new UnsafeInstallerPath("file", file.toString(), "--prefix must be a directory.")));
        cases.addAll(unsafeAppSymlinkCases(symlink, symlinkName));
        return List.copyOf(cases);
    }

    private static String platformRootPath() {
        Path root = Path.of("").toAbsolutePath().getRoot();
        return root == null ? "/" : root.toString();
    }

    private enum OutputStyle {
        RAW {
            @Override
            String apply(String output) {
                return output;
            }
        },
        NORMALIZED {
            @Override
            String apply(String output) {
                return normalizedWhitespace(output);
            }
        };

        abstract String apply(String output);
    }

    private static boolean unsafeUninstallCommandDirectoryCase(UnsafeCommandDirectory commandDirectory) {
        return !commandDirectory.name().startsWith("symlink");
    }

    private record PosixUnsafeCommandDirectoryScenario(
            String name,
            String slug,
            String script,
            String firstFlag,
            String secondFlag,
            String overlapDirectory,
            String overlapCaseName,
            String symlinkName,
            boolean allowsSymlinkedCommandDirectory,
            List<String> absentOutput) {
        private boolean rejectsCommandDirectory(UnsafeCommandDirectory commandDirectory) {
            return !allowsSymlinkedCommandDirectory || unsafeUninstallCommandDirectoryCase(commandDirectory);
        }

        private String[] absentOutputArray() {
            return absentOutput.toArray(String[]::new);
        }

        @Override
        public String toString() {
            return name;
        }
    }

    private record PosixUnsafeAppPathScenario(
            String name,
            String slug,
            String script,
            List<String> fixedArgs,
            String symlinkName,
            boolean usesLocalDataOverlap,
            List<String> absentOutput) {
        private String[] absentOutputArray() {
            return absentOutput.toArray(String[]::new);
        }

        @Override
        public String toString() {
            return name;
        }
    }

    private record PosixDefaultCommandDirectoryScenario(
            String name, String slug, String script, String secondFlag, String expectedOutput) {
        @Override
        public String toString() {
            return name;
        }
    }

    private record PosixRootScopedConfigDirectoryScenario(
            String name,
            String slug,
            String script,
            String secondFlag,
            String expectedMessage,
            List<String> absentOutput) {
        private String[] absentOutputArray() {
            return absentOutput.toArray(String[]::new);
        }

        @Override
        public String toString() {
            return name;
        }
    }

    private record PosixHomeValidationScenario(
            String name,
            String script,
            String secondFlag,
            String commandName,
            boolean missingHome,
            List<String> absentOutput) {
        private String[] absentOutputArray() {
            return absentOutput.toArray(String[]::new);
        }

        @Override
        public String toString() {
            return name;
        }
    }

    private record CustomPrefixUninstallFixture(Path defaultSymphonyHome, Path app, Path bin) {}

    private static List<String> powershellCommandForDifferentWorkingDirectory() {
        return powershellCommand().stream()
                .map(command ->
                        command.contains(File.separator) && !Path.of(command).isAbsolute()
                                ? Path.of(command).toAbsolutePath().toString()
                                : command)
                .toList();
    }

    private PosixDryRunPair runPosixInstallAndUninstallDryRunWithPrefix(
            Map<String, String> environment, Path app, Path bin) throws Exception {
        return new PosixDryRunPair(
                runUnchecked(
                        environment,
                        "bash",
                        "install.sh",
                        "--dry-run",
                        "--no-onboard",
                        "--prefix",
                        app.toString(),
                        "--bin-dir",
                        bin.toString()),
                runUnchecked(
                        environment,
                        "bash",
                        "uninstall.sh",
                        "--dry-run",
                        "--yes",
                        "--prefix",
                        app.toString(),
                        "--bin-dir",
                        bin.toString()));
    }

    private record PosixDryRunPair(ProcessResult install, ProcessResult uninstall) {}

    private record UnsafeCommandDirectory(String name, String value, String expectedMessage) {}

    private record UnsafeInstallerPath(String name, String value, List<String> expectedMessages) {
        private UnsafeInstallerPath(String name, String value, String expectedMessage) {
            this(name, value, List.of(expectedMessage));
        }

        private String expectedMessage() {
            return expectedMessages.getFirst();
        }
    }
}
