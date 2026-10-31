package ch.fmartin.symphony.trello.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

final class InstallContextTest {
    @TempDir
    Path tempDir;

    @Test
    void contextKeepsItsOwnSnapshotOfOverrideNames() {
        // given
        List<String> names = new ArrayList<>(List.of("--prefix"));
        var context = new InstallContext(Optional.of("1.2.0"), Optional.of(names));

        // when
        names.add("--bin-dir");

        // then
        assertThat(context.installOverrides()).hasValue(List.of("--prefix"));
        assertThatExceptionOfType(UnsupportedOperationException.class)
                .isThrownBy(() -> context.installOverrides().orElseThrow().add("--bin-dir"));
    }

    @Test
    void readsTheInstallerContextFromTheStateDirectory() throws IOException {
        // given
        Path stateDir = TelemetryFixture.installedStateDir(tempDir, "1.3.4");
        Files.writeString(
                InstalledVersion.installContextPath(stateDir),
                "install_overrides=--prefix,SYMPHONY_TRELLO_CONFIG_DIR\n",
                StandardOpenOption.APPEND);

        // when
        InstallContext context = InstallContext.read(stateDir);

        // then
        assertThat(context.appVersion()).hasValue("1.3.4");
        assertThat(context.installOverrides())
                .as("names come back in wire order, never in the installer's order")
                .hasValue(List.of("SYMPHONY_TRELLO_CONFIG_DIR", "--prefix"));
    }

    @Test
    void windowsPathsDoNotHideTheVersionOrOverrideNames() throws IOException {
        // given
        Path stateDir = TelemetryFixture.installedStateDir(tempDir, "1.3.4");
        Files.writeString(
                InstalledVersion.installContextPath(stateDir),
                "prefix=C:\\Users\\user\\symphony\ninstall_overrides=--prefix\n",
                StandardOpenOption.APPEND);

        // when
        InstallContext context = InstallContext.read(stateDir);

        // then
        assertThat(context.appVersion()).hasValue("1.3.4");
        assertThat(context.installOverrides()).hasValue(List.of("--prefix"));
    }

    @Test
    void missingContextMeansEverythingIsUnknown() throws IOException {
        // given
        Path stateDir = Files.createDirectories(tempDir.resolve("empty"));

        // when
        InstallContext context = InstallContext.read(stateDir);

        // then
        assertThat(context).isEqualTo(InstallContext.UNKNOWN);
    }

    @Test
    void aContextWithoutTheOverrideRecordReportsUnknownOverrides() throws IOException {
        // given
        Path stateDir = TelemetryFixture.installedStateDir(tempDir);

        // when
        InstallContext context = InstallContext.read(stateDir);

        // then
        assertThat(context.installOverrides()).isEmpty();
    }

    @CsvSource({
        "'', ''",
        "' ', ''",
        "SYMPHONY_HOME, SYMPHONY_HOME",
        "'microos-var,SYMPHONY_HOME', 'SYMPHONY_HOME;microos-var'",
        "'--bin-dir, --prefix ,--bin-dir', '--prefix;--bin-dir'",
        "'SYMPHONY_HOME=/home/alice,/var/lib/symphony', ''",
        "'SYMPHONY_TRELLO_STATE_HOME,unknown-name', SYMPHONY_TRELLO_STATE_HOME"
    })
    @ParameterizedTest(name = "record [{0}] parses to [{1}]")
    void recordedNamesAreFilteredToTheListDeduplicatedAndOrdered(String recorded, String expected) {
        // given
        String record = recorded;

        // when
        Optional<List<String>> names = InstallOverride.parse(record);

        // then
        assertThat(names).hasValue(expected.isEmpty() ? List.of() : List.of(expected.split(";")));
    }

    @Test
    void everyOverrideNameIsPlainAsciiWithoutSeparatorsOrPaths() {
        // given
        List<String> names = new ArrayList<>();
        for (InstallOverride override : InstallOverride.values()) {
            names.add(override.wireName());
        }

        // when
        List<String> parsed = InstallOverride.parse(String.join(InstallOverride.RECORD_SEPARATOR, names))
                .orElseThrow();

        // then
        assertThat(parsed).containsExactlyElementsOf(names);
        assertThat(names).allMatch(name -> name.matches("[A-Za-z_-]+"), "a name is never a path or a value");
    }
}
