package ch.fmartin.symphony.trello;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

final class ReleasePackagingScriptTest {
    private static final String VERSION = "1.2.3";

    @TempDir
    Path tempDir;

    @Test
    void writesExpectedAssetsToDefaultDestination() throws Exception {
        // given
        TestProject project = createProject();

        // when
        ProcessResult result = project.run(VERSION);

        // then
        assertThat(result.exitCode()).as(result.output()).isZero();
        assertExpectedAssets(project.root().resolve("dist/release-assets"));
        assertThat(project.mvnwLog()).contains("-q -DskipTests clean package");
    }

    @Test
    void stampsRepositoryInstallerTemplates() throws Exception {
        // given
        TestProject project = createProjectWithRepositoryInstallerTemplates();

        // when
        ProcessResult result = project.run(VERSION);

        // then
        assertThat(result.exitCode()).as(result.output()).isZero();
        assertExpectedAssets(project.root().resolve("dist/release-assets"));
    }

    @Test
    void stampsRepositoryInstallerTemplatesForCurrentDefaultVersion() throws Exception {
        // given
        TestProject project = createProjectWithRepositoryInstallerTemplates();

        // when
        ProcessResult result = project.run("0.2.0");

        // then
        assertThat(result.exitCode()).as(result.output()).isZero();
        assertExpectedAssets(project.root().resolve("dist/release-assets"), "0.2.0");
    }

    @Test
    void writesExpectedAssetsToNewRelativeDestination() throws Exception {
        // given
        TestProject project = createProject();

        // when
        ProcessResult result = project.run(VERSION, "dist/custom-assets");

        // then
        assertThat(result.exitCode()).as(result.output()).isZero();
        assertExpectedAssets(project.root().resolve("dist/custom-assets"));
    }

    @Test
    void writesExpectedAssetsToNewAbsoluteDestinationInsideCheckout() throws Exception {
        // given
        TestProject project = createProject();
        Path destination = project.root().resolve("release output/with spaces");

        // when
        ProcessResult result = project.run(VERSION, destination.toString());

        // then
        assertThat(result.exitCode()).as(result.output()).isZero();
        assertExpectedAssets(destination);
    }

    @Test
    void repeatedRunReplacesOnlyGeneratedAssets() throws Exception {
        // given
        TestProject project = createProject();
        Path destination = project.root().resolve("dist/release-assets");
        ProcessResult first = project.run(VERSION);
        assertThat(first.exitCode()).as(first.output()).isZero();
        Files.writeString(destination.resolve("install.sh"), "stale");

        // when
        ProcessResult second = project.run(VERSION);

        // then
        assertThat(second.exitCode()).as(second.output()).isZero();
        assertExpectedAssets(destination);
        assertThat(destination.resolve("install.sh"))
                .content(StandardCharsets.UTF_8)
                .contains("1.2.3");
    }

    @Test
    void repeatedRunReplacesOwnedAssetsFromPriorVersion() throws Exception {
        // given
        TestProject project = createProject();
        Path destination = project.root().resolve("dist/release-assets");
        ProcessResult first = project.run("1.2.2");
        assertThat(first.exitCode()).as(first.output()).isZero();

        // when
        ProcessResult second = project.run(VERSION);

        // then
        assertThat(second.exitCode()).as(second.output()).isZero();
        assertExpectedAssets(destination);
        assertThat(destination.resolve("symphony-trello-1.2.2.tar.gz")).doesNotExist();
        assertThat(destination.resolve("symphony-trello-1.2.2.zip")).doesNotExist();
    }

    @Test
    void defaultDestinationRejectsUnownedGeneratedAssetsWithoutOwnershipMarker() throws Exception {
        // given
        TestProject project = createProject();
        Path destination = project.root().resolve("dist/release-assets");
        Files.createDirectories(destination);
        for (String asset : List.of(
                "install.sh",
                "install.ps1",
                "uninstall.sh",
                "uninstall.ps1",
                "checksums.txt",
                "symphony-trello-1.2.2.tar.gz",
                "symphony-trello-1.2.2.zip")) {
            Files.writeString(destination.resolve(asset), "unowned");
        }

        // when
        ProcessResult result = project.run(VERSION);

        // then
        assertThat(result.exitCode()).as(result.output()).isEqualTo(2);
        assertThat(result.output()).contains("Release asset destination contains files not managed");
        assertThat(project.mvnwLogPath()).doesNotExist();
        assertThat(destination.resolve("install.sh"))
                .content(StandardCharsets.UTF_8)
                .isEqualTo("unowned");
        assertThat(destination.resolve("symphony-trello-1.2.2.tar.gz"))
                .content(StandardCharsets.UTF_8)
                .isEqualTo("unowned");
    }

    @Test
    void failedBuildDoesNotPublishPartialDestination() throws Exception {
        // given
        TestProject project = createProject();
        project.writeFailingMavenWrapper();
        Path destination = project.root().resolve("dist/release-assets");

        // when
        ProcessResult result = project.run(VERSION);

        // then
        assertThat(result.exitCode()).as(result.output()).isNotZero();
        assertThat(destination).doesNotExist();
    }

    @MethodSource("invalidInstallerMarkerCases")
    @ParameterizedTest(name = "{0}")
    void rejectsInvalidInstallerMarkersBeforeBuild(InvalidInstallerMarkerCase invalidCase) throws Exception {
        // given
        TestProject project = createProject();
        invalidCase.apply(project.root());
        Path destination = project.root().resolve("dist/release-assets");

        // when
        ProcessResult result = project.run(VERSION);

        // then
        assertThat(result.exitCode()).as(result.output()).isEqualTo(2);
        assertThat(result.output()).contains(invalidCase.expectedMessage());
        assertThat(project.mvnwLogPath()).doesNotExist();
        assertThat(destination).doesNotExist();
    }

    @Test
    void failsClosedWhenAValidatedMarkerIsNotSubstituted() throws Exception {
        // given
        TestProject project = createProject();
        rewrite(
                project.root().resolve("install.sh"),
                content -> content.replace(
                        "DEFAULT_VERSION=\"0.0.0\" # x-release-please-version",
                        "  DEFAULT_VERSION=\"0.0.0\" # x-release-please-version"));
        Path destination = project.root().resolve("dist/release-assets");

        // when
        ProcessResult result = project.run(VERSION);

        // then
        assertThat(result.exitCode()).as(result.output()).isEqualTo(2);
        assertThat(result.output()).contains("install.sh DEFAULT_VERSION was not stamped");
        assertThat(project.mvnwLogPath()).exists();
        assertThat(destination).doesNotExist();
    }

    @Test
    void packagingScriptAvoidsGnuOnlyFilesystemOptions() throws Exception {
        // given
        Path scriptPath = Path.of("scripts/package-release-assets.sh");

        // when
        String script = Files.readString(scriptPath);

        // then
        assertThat(script).doesNotContain("realpath -m", "-printf");
    }

    @MethodSource("invalidDestinationCases")
    @ParameterizedTest(name = "{0}")
    void rejectsUnsafeDestinationsBeforeBuild(InvalidDestinationCase invalidCase) throws Exception {
        // given
        TestProject project = createProject();
        PreparedDestination destination = invalidCase.prepare(tempDir, project.root());
        destination.assertPreconditions();

        // when
        ProcessResult result = project.run(VERSION, destination.path().toString());

        // then
        assertThat(result.exitCode()).as(result.output()).isEqualTo(2);
        assertThat(result.output()).contains(invalidCase.expectedMessage());
        assertThat(project.mvnwLogPath()).doesNotExist();
        assertNoPublicationAttempt(project.root());
        assertNoPublicationAttempt(tempDir);
        destination.assertSentinelSurvived();
    }

    static Stream<InvalidDestinationCase> invalidDestinationCases() {
        return Stream.of(
                new InvalidDestinationCase(
                        "parent alias escapes checkout", "Release asset destination must be inside", (temp, root) -> {
                            Path victim = temp.resolve("victim");
                            Files.createDirectories(victim);
                            return PreparedDestination.withSentinel(root.resolve("../victim"), victim);
                        }),
                new InvalidDestinationCase(
                        "absolute unrelated directory", "Release asset destination must be inside", (temp, root) -> {
                            Path destination = temp.resolve("outside");
                            Files.createDirectories(destination);
                            return PreparedDestination.withSentinel(destination, destination);
                        }),
                new InvalidDestinationCase(
                        "repository root",
                        "must not be the source checkout",
                        (temp, root) -> PreparedDestination.withSentinel(root, root)),
                new InvalidDestinationCase(
                        "repository parent",
                        "Release asset destination must be inside",
                        (temp, root) -> PreparedDestination.withSentinel(root.getParent(), root.getParent())),
                new InvalidDestinationCase(
                        "filesystem root",
                        "must not be the filesystem root",
                        (temp, root) -> PreparedDestination.withoutSentinel(Path.of("/"))),
                new InvalidDestinationCase(
                        "inside Maven target", "must not be inside Maven build output", (temp, root) -> {
                            Path destination = root.resolve("target/release-assets");
                            Files.createDirectories(destination);
                            return PreparedDestination.withSentinel(destination, destination);
                        }),
                new InvalidDestinationCase(
                        "destination contains Maven target",
                        "must not be the source checkout",
                        (temp, root) -> PreparedDestination.withSentinel(root.resolve("target/.."), root)),
                new InvalidDestinationCase("destination is a file", "must be a directory", (temp, root) -> {
                    Path destination = root.resolve("dist-file");
                    Files.writeString(destination, "sentinel");
                    return PreparedDestination.withoutSentinel(destination);
                }),
                new InvalidDestinationCase(
                        "existing non-empty unowned destination", "contains files not managed", (temp, root) -> {
                            Path destination = root.resolve("dist/unowned");
                            Files.createDirectories(destination);
                            Files.writeString(destination.resolve("keep.txt"), "sentinel");
                            return PreparedDestination.withSentinel(destination, destination);
                        }),
                new InvalidDestinationCase(
                        "existing custom destination with asset-like file",
                        "contains files not managed",
                        (temp, root) -> {
                            Path destination = root.resolve("custom");
                            Files.createDirectories(destination);
                            Files.writeString(destination.resolve("install.sh"), "sentinel");
                            return PreparedDestination.withSentinel(destination, destination);
                        }),
                new InvalidDestinationCase(
                        "invalid ownership marker directory", "ownership marker is invalid", (temp, root) -> {
                            Path destination = root.resolve("dist/invalid-marker");
                            Files.createDirectories(destination.resolve(".symphony-trello-release-assets"));
                            return PreparedDestination.withSentinel(destination, destination);
                        }),
                new InvalidDestinationCase(
                        "destination symlink", "Release asset destination must not be a symlink", (temp, root) -> {
                            assumeSymlinks();
                            Path outside = temp.resolve("outside-link-target");
                            Files.createDirectories(outside);
                            Path link = root.resolve("dist-link");
                            Files.createSymbolicLink(link, outside);
                            return PreparedDestination.withSymlinkSentinel(link, link, outside);
                        }),
                new InvalidDestinationCase(
                        "destination symlink with trailing slash",
                        "Release asset destination must not be a symlink",
                        (temp, root) -> {
                            assumeSymlinks();
                            Path outside = temp.resolve("outside-link-target-slash");
                            Files.createDirectories(outside);
                            Path link = root.resolve("dist-link-slash");
                            Files.createSymbolicLink(link, outside);
                            return PreparedDestination.withSymlinkSentinel(Path.of(link + "/"), link, outside);
                        }),
                new InvalidDestinationCase(
                        "inside-checkout destination symlink",
                        "Release asset destination must not be a symlink",
                        (temp, root) -> {
                            assumeSymlinks();
                            Path target = root.resolve("dist/inside-link-target");
                            Files.createDirectories(target);
                            Path link = root.resolve("inside-link");
                            Files.createSymbolicLink(link, target);
                            return PreparedDestination.withSymlinkSentinel(link, link, target);
                        }),
                new InvalidDestinationCase(
                        "inside-checkout destination symlink with trailing slash",
                        "Release asset destination must not be a symlink",
                        (temp, root) -> {
                            assumeSymlinks();
                            Path target = root.resolve("dist/inside-link-target-slash");
                            Files.createDirectories(target);
                            Path link = root.resolve("inside-link-slash");
                            Files.createSymbolicLink(link, target);
                            return PreparedDestination.withSymlinkSentinel(Path.of(link + "/"), link, target);
                        }),
                new InvalidDestinationCase(
                        "inside-checkout destination symlink with dot alias",
                        "Release asset destination must not be a symlink",
                        (temp, root) -> {
                            assumeSymlinks();
                            Path target = root.resolve("dist/inside-link-target-dot");
                            Files.createDirectories(target);
                            Path link = root.resolve("inside-link-dot");
                            Files.createSymbolicLink(link, target);
                            return PreparedDestination.withSymlinkSentinel(Path.of(link + "/."), link, target);
                        }),
                new InvalidDestinationCase(
                        "relative destination symlink",
                        "Release asset destination must not be a symlink",
                        (temp, root) -> {
                            assumeSymlinks();
                            Path target = root.resolve("dist/relative-link-target");
                            Files.createDirectories(target);
                            Path link = root.resolve("relative-link");
                            Files.createSymbolicLink(link, target);
                            return PreparedDestination.withSymlinkSentinel(root.relativize(link), link, target);
                        }),
                new InvalidDestinationCase(
                        "absolute destination symlink",
                        "Release asset destination must not be a symlink",
                        (temp, root) -> {
                            assumeSymlinks();
                            Path target = root.resolve("dist/absolute-link-target");
                            Files.createDirectories(target);
                            Path link = root.resolve("absolute-link");
                            Files.createSymbolicLink(link, target);
                            return PreparedDestination.withSymlinkSentinel(link.toAbsolutePath(), link, target);
                        }),
                new InvalidDestinationCase(
                        "empty target through destination symlink",
                        "Release asset destination must not be a symlink",
                        (temp, root) -> {
                            assumeSymlinks();
                            Path target = root.resolve("dist/empty-link-target");
                            Files.createDirectories(target);
                            Path link = root.resolve("empty-link");
                            Files.createSymbolicLink(link, target);
                            return PreparedDestination.withSymlink(link, link, target);
                        }),
                new InvalidDestinationCase(
                        "owned-looking target through destination symlink",
                        "Release asset destination must not be a symlink",
                        (temp, root) -> {
                            assumeSymlinks();
                            Path target = root.resolve("dist/owned-looking-link-target");
                            Files.createDirectories(target);
                            writeOwnershipMarker(target);
                            Path link = root.resolve("owned-looking-link");
                            Files.createSymbolicLink(link, target);
                            return PreparedDestination.withSymlinkSentinel(link, link, target);
                        }),
                new InvalidDestinationCase(
                        "symlinked parent escapes checkout",
                        "Release asset destination must be inside",
                        (temp, root) -> {
                            assumeSymlinks();
                            Path outside = temp.resolve("outside-parent-target");
                            Files.createDirectories(outside);
                            Path link = root.resolve("linked-parent");
                            Files.createSymbolicLink(link, outside);
                            Path requested = link.resolve("release-assets");
                            Path resolved = outside.resolve("release-assets");
                            Files.createDirectories(resolved);
                            return PreparedDestination.withSentinel(requested, resolved);
                        }));
    }

    static Stream<InvalidInstallerMarkerCase> invalidInstallerMarkerCases() {
        return Stream.of(
                new InvalidInstallerMarkerCase(
                        "missing POSIX DEFAULT_VERSION marker",
                        "install.sh",
                        content -> content.replace(
                                "DEFAULT_VERSION=\"0.0.0\" # x-release-please-version", "DEFAULT_VERSION=\"0.0.0\""),
                        "install.sh DEFAULT_VERSION must contain exactly one supported release marker"),
                new InvalidInstallerMarkerCase(
                        "duplicate POSIX DEFAULT_VERSION marker",
                        "install.sh",
                        content -> content + "\nDEFAULT_VERSION=\"0.0.0\" # x-release-please-version\n",
                        "install.sh DEFAULT_VERSION must contain exactly one supported release marker"),
                new InvalidInstallerMarkerCase(
                        "missing PowerShell Version marker",
                        "install.ps1",
                        content -> content.replace(
                                "[string]$Version = $(if ($env:SYMPHONY_TRELLO_VERSION) { $env:SYMPHONY_TRELLO_VERSION } else { \"0.0.0\" }), # x-release-please-version",
                                "[string]$Version = $(if ($env:SYMPHONY_TRELLO_VERSION) { $env:SYMPHONY_TRELLO_VERSION } else { \"0.0.0\" }),"),
                        "install.ps1 Version parameter must contain exactly one supported release marker"),
                new InvalidInstallerMarkerCase(
                        "duplicate PowerShell Version marker",
                        "install.ps1",
                        content -> content
                                + "\n[string]$Version = $(if ($env:SYMPHONY_TRELLO_VERSION) { $env:SYMPHONY_TRELLO_VERSION } else { \"0.0.0\" }), # x-release-please-version\n",
                        "install.ps1 Version parameter must contain exactly one supported release marker"),
                new InvalidInstallerMarkerCase(
                        "missing PowerShell Ref marker",
                        "install.ps1",
                        content -> content.replace(
                                "[string]$Ref = $(if ($env:SYMPHONY_TRELLO_REF) { $env:SYMPHONY_TRELLO_REF } else { \"v0.0.0\" }), # x-release-please-version",
                                "[string]$Ref = $(if ($env:SYMPHONY_TRELLO_REF) { $env:SYMPHONY_TRELLO_REF } else { \"v0.0.0\" }),"),
                        "install.ps1 Ref parameter must contain exactly one supported release marker"),
                new InvalidInstallerMarkerCase(
                        "duplicate PowerShell Ref marker",
                        "install.ps1",
                        content -> content
                                + "\n[string]$Ref = $(if ($env:SYMPHONY_TRELLO_REF) { $env:SYMPHONY_TRELLO_REF } else { \"v0.0.0\" }), # x-release-please-version\n",
                        "install.ps1 Ref parameter must contain exactly one supported release marker"),
                new InvalidInstallerMarkerCase(
                        "missing PowerShell DefaultVersion marker",
                        "install.ps1",
                        content -> content.replace(
                                "$DefaultVersion = \"0.0.0\" # x-release-please-version",
                                "$DefaultVersion = \"0.0.0\""),
                        "install.ps1 DefaultVersion must contain exactly one supported release marker"),
                new InvalidInstallerMarkerCase(
                        "duplicate PowerShell DefaultVersion marker",
                        "install.ps1",
                        content -> content + "\n$DefaultVersion = \"0.0.0\" # x-release-please-version\n",
                        "install.ps1 DefaultVersion must contain exactly one supported release marker"),
                new InvalidInstallerMarkerCase(
                        "malformed POSIX marker",
                        "install.sh",
                        content -> content.replace(
                                "DEFAULT_VERSION=\"0.0.0\" # x-release-please-version",
                                "DEFAULT_VERSION=0.0.0 # x-release-please-version"),
                        "install.sh DEFAULT_VERSION must contain exactly one supported release marker"));
    }

    private static void assumeSymlinks() {
        assumeFalse(System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win"));
    }

    private TestProject createProject() throws IOException {
        Path root = tempDir.resolve("project");
        Files.createDirectories(root.resolve("scripts"));
        Files.copy(Path.of("scripts/package-release-assets.sh"), root.resolve("scripts/package-release-assets.sh"));
        Files.writeString(
                root.resolve("install.sh"),
                """
                #!/usr/bin/env bash
                DEFAULT_VERSION="0.0.0" # x-release-please-version
                echo install
                """);
        Files.writeString(
                root.resolve("install.ps1"),
                """
                param(
                  [string]$Version = $(if ($env:SYMPHONY_TRELLO_VERSION) { $env:SYMPHONY_TRELLO_VERSION } else { "0.0.0" }), # x-release-please-version
                  [string]$Ref = $(if ($env:SYMPHONY_TRELLO_REF) { $env:SYMPHONY_TRELLO_REF } else { "v0.0.0" }), # x-release-please-version
                  [switch]$Help
                )
                $DefaultVersion = "0.0.0" # x-release-please-version
                """);
        Files.writeString(root.resolve("uninstall.sh"), "#!/usr/bin/env bash\necho uninstall\n");
        Files.writeString(root.resolve("uninstall.ps1"), "Write-Output uninstall\n");
        Files.writeString(root.resolve("README.md"), "readme\n");
        var project = new TestProject(root);
        project.writeSuccessfulMavenWrapper();
        return project;
    }

    private TestProject createProjectWithRepositoryInstallerTemplates() throws IOException {
        TestProject project = createProject();
        Files.copy(Path.of("install.sh"), project.root().resolve("install.sh"), StandardCopyOption.REPLACE_EXISTING);
        Files.copy(Path.of("install.ps1"), project.root().resolve("install.ps1"), StandardCopyOption.REPLACE_EXISTING);
        return project;
    }

    private static void rewrite(Path file, UnaryOperator<String> replacement) throws IOException {
        Files.writeString(file, replacement.apply(Files.readString(file)));
    }

    private static void assertExpectedAssets(Path destination) throws IOException {
        assertExpectedAssets(destination, VERSION);
    }

    private static void assertExpectedAssets(Path destination, String version) throws IOException {
        try (Stream<Path> files = Files.list(destination)) {
            assertThat(files.map(path -> path.getFileName().toString())
                            .filter(name -> !name.equals(".symphony-trello-release-assets"))
                            .sorted()
                            .toList())
                    .containsExactlyElementsOf(expectedAssets(version));
        }
        assertThat(destination.resolve(".symphony-trello-release-assets")).exists();
        assertThat(destination.resolve("checksums.txt"))
                .content(StandardCharsets.UTF_8)
                .contains(
                        "  install.sh",
                        "  install.ps1",
                        "  uninstall.sh",
                        "  uninstall.ps1",
                        "  symphony-trello-" + version + ".tar.gz",
                        "  symphony-trello-" + version + ".zip")
                .doesNotContain("checksums.txt", ".symphony-trello-release-assets", "\r");
        assertThat(destination.resolve("install.sh"))
                .content(StandardCharsets.UTF_8)
                .contains("DEFAULT_VERSION=\"" + version + "\"")
                .doesNotContain("DEFAULT_VERSION=\"0.0.0\"", "v" + version);
        assertThat(destination.resolve("install.ps1"))
                .content(StandardCharsets.UTF_8)
                .contains(
                        "else { \"" + version + "\" }",
                        "else { \"v" + version + "\" }",
                        "$DefaultVersion = \"" + version + "\"")
                .doesNotContain("else { \"0.0.0\" }", "else { \"v0.0.0\" }", "$DefaultVersion = \"0.0.0\"");
    }

    private static void assertNoPublicationAttempt(Path root) throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            assertThat(paths.map(path -> path.getFileName().toString())
                            .filter(name -> name.startsWith(".release-assets"))
                            .toList())
                    .isEmpty();
        }
    }

    private static void writeOwnershipMarker(Path destination) throws IOException {
        Files.writeString(
                destination.resolve(".symphony-trello-release-assets"),
                String.join("\n", expectedAssets(VERSION)) + "\n");
    }

    private static List<String> expectedAssets(String version) {
        return List.of(
                "checksums.txt",
                "install.ps1",
                "install.sh",
                "symphony-trello-" + version + ".tar.gz",
                "symphony-trello-" + version + ".zip",
                "uninstall.ps1",
                "uninstall.sh");
    }

    private record TestProject(Path root) {
        void writeSuccessfulMavenWrapper() throws IOException {
            writeMavenWrapper(
                    """
                    #!/usr/bin/env bash
                    set -euo pipefail
                    echo "$*" >> mvnw.log
                    rm -rf target
                    mkdir -p target/quarkus-app
                    printf 'app' > target/quarkus-app/application.txt
                    """);
        }

        void writeFailingMavenWrapper() throws IOException {
            writeMavenWrapper(
                    """
                    #!/usr/bin/env bash
                    set -euo pipefail
                    echo "$*" >> mvnw.log
                    exit 42
                    """);
        }

        void writeMavenWrapper(String content) throws IOException {
            Path mvnw = root.resolve("mvnw");
            Files.writeString(mvnw, content);
            mvnw.toFile().setExecutable(true);
        }

        ProcessResult run(String... arguments) throws IOException, InterruptedException {
            String[] command = new String[arguments.length + 2];
            command[0] = "bash";
            command[1] = root.resolve("scripts/package-release-assets.sh").toString();
            System.arraycopy(arguments, 0, command, 2, arguments.length);
            Process process = new ProcessBuilder(command)
                    .directory(root.toFile())
                    .redirectErrorStream(true)
                    .start();
            var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return new ProcessResult(process.waitFor(), output);
        }

        Path mvnwLogPath() {
            return root.resolve("mvnw.log");
        }

        String mvnwLog() throws IOException {
            return Files.readString(mvnwLogPath());
        }
    }

    private record ProcessResult(int exitCode, String output) {}

    private record InvalidDestinationCase(String name, String expectedMessage, DestinationFactory destinationFactory) {
        PreparedDestination prepare(Path tempDir, Path root) throws IOException {
            return destinationFactory.prepare(tempDir, root);
        }

        @Override
        public String toString() {
            return name;
        }
    }

    @FunctionalInterface
    private interface DestinationFactory {
        PreparedDestination prepare(Path tempDir, Path root) throws IOException;
    }

    private record InvalidInstallerMarkerCase(
            String name, String fileName, UnaryOperator<String> replacement, String expectedMessage) {
        void apply(Path root) throws IOException {
            rewrite(root.resolve(fileName), replacement);
        }

        @Override
        public String toString() {
            return name;
        }
    }

    private record PreparedDestination(Path path, Path sentinel, Path symlink, Path symlinkTarget) {
        static PreparedDestination withSentinel(Path requestedPath, Path actualDirectory) throws IOException {
            Files.createDirectories(actualDirectory);
            Path sentinel = actualDirectory.resolve("sentinel.txt");
            Files.writeString(sentinel, "sentinel");
            return new PreparedDestination(requestedPath, sentinel, null, null);
        }

        static PreparedDestination withoutSentinel(Path requestedPath) {
            return new PreparedDestination(requestedPath, null, null, null);
        }

        static PreparedDestination withSymlink(Path requestedPath, Path symlink, Path target) {
            return new PreparedDestination(requestedPath, null, symlink, target);
        }

        static PreparedDestination withSymlinkSentinel(Path requestedPath, Path symlink, Path target)
                throws IOException {
            Files.createDirectories(target);
            Path sentinel = target.resolve("sentinel.txt");
            Files.writeString(sentinel, "sentinel");
            return new PreparedDestination(requestedPath, sentinel, symlink, target);
        }

        void assertPreconditions() {
            if (symlink != null) {
                assertThat(symlink).isSymbolicLink();
                assertThat(symlinkTarget).isDirectory();
            }
        }

        void assertSentinelSurvived() {
            if (sentinel != null) {
                assertThat(sentinel).exists();
            }
            if (symlink != null) {
                assertThat(symlink).isSymbolicLink();
                assertThat(symlinkTarget).isDirectory();
            }
        }
    }
}
