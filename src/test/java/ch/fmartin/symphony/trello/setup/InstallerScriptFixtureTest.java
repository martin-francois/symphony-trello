package ch.fmartin.symphony.trello.setup;

import static ch.fmartin.symphony.trello.setup.InstallerScriptFixture.powershellCommand;
import static ch.fmartin.symphony.trello.setup.InstallerScriptFixture.run;
import static ch.fmartin.symphony.trello.setup.InstallerScriptFixture.writeExecutable;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@EnabledOnOs({OS.LINUX, OS.MAC})
final class InstallerScriptFixtureTest {
    @TempDir
    Path temporaryDirectory;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void configuredPowerShellWrapperRunsFromAnotherDirectory(boolean absolute) throws Exception {
        // given
        Path wrapper = temporaryDirectory.resolve("PowerShell wrapper.sh");
        writeExecutable(wrapper, "#!/bin/sh\nprintf '%s' \"$1\"\n");
        Path configured = absolute ? wrapper : Path.of("").toAbsolutePath().relativize(wrapper);
        Path childDirectory = Files.createDirectory(temporaryDirectory.resolve("child directory"));

        // when
        var command = powershellCommand(configured.toString());
        var result = run(Map.of(), childDirectory, command.getFirst(), "argument with spaces");

        // then
        assertThat(Path.of(command.getFirst())).isAbsolute();
        result.assertSuccess();
        assertThat(result.stdout()).isEqualTo("argument with spaces");
    }
}
