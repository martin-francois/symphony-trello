package ch.fmartin.symphony.trello.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class JavaLauncherEnvironmentTest {

    @Test
    void removesLauncherOptionsLeavingOtherVariablesIntact() {
        // given
        Map<String, String> environment = new HashMap<>(Map.of(
                "JAVA_TOOL_OPTIONS", "-Dfile.encoding=UTF-8",
                "_JAVA_OPTIONS", "-Xmx512m",
                "JDK_JAVA_OPTIONS", "--enable-preview",
                "PATH", "/usr/bin",
                "CUSTOM_VAR", "value"));

        // when
        JavaLauncherEnvironment.removeLauncherOptions(environment);

        // then
        assertThat(environment)
                .doesNotContainKeys("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS")
                .containsEntry("PATH", "/usr/bin")
                .containsEntry("CUSTOM_VAR", "value");
    }

    @Test
    void handlesNullOrEmptyEnvironmentWithoutError() {
        // given
        Map<String, String> empty = new HashMap<>();

        // when
        JavaLauncherEnvironment.removeLauncherOptions(null);
        JavaLauncherEnvironment.removeLauncherOptions(empty);

        // then
        assertThat(empty).isEmpty();
    }
}
