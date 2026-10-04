package ch.fmartin.symphony.trello.setup;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

final class SystemConsoleTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final String UNSET_PROVIDER = "unset";

    @CsvSource({
        "Linux, unset, jdk.internal.le",
        "Windows 11, unset, unset",
        "Mac OS X, unset, unset",
        "FreeBSD, unset, unset",
        "Linux, java.base, java.base",
        "Windows 11, jdk.internal.le, jdk.internal.le"
    })
    @ParameterizedTest
    void enablesTheEditorOnlyOnLinuxUntilIssue817VerifiesOtherPlatforms(
            String operatingSystem, String chosenProvider, String expectedProvider) throws Exception {
        // given
        List<String> command = new ArrayList<>(
                List.of(ProcessHandle.current().info().command().orElseThrow(), "-Dos.name=" + operatingSystem));
        if (!chosenProvider.equals(UNSET_PROVIDER)) {
            command.add("-D" + SystemConsole.PROVIDER_PROPERTY + "=" + chosenProvider);
        }
        command.addAll(List.of("-cp", System.getProperty("java.class.path"), ProviderProbe.class.getName()));

        // when
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        try {
            boolean exited = process.waitFor(TIMEOUT);

            // then
            assertThat(exited).as("provider probe must finish").isTrue();
            var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(process.exitValue())
                    .as("provider probe output: %s", output)
                    .isZero();
            assertThat(output.strip()).isEqualTo(expectedProvider);
        } finally {
            process.destroyForcibly();
        }
    }

    static final class ProviderProbe {
        public static void main(String... args) {
            SystemConsole.enableLineEditing();
            System.out.println(System.getProperty(SystemConsole.PROVIDER_PROPERTY, UNSET_PROVIDER));
        }
    }
}
