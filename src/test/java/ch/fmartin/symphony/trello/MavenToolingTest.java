package ch.fmartin.symphony.trello;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class MavenToolingTest {

    @Test
    void repositoryDoesNotUseGlobalMavenJvmConfig() {
        // given
        Path globalJvmConfig = Path.of(".mvn/jvm.config");

        // when
        boolean exists = globalJvmConfig.toFile().exists();

        // then
        assertThat(globalJvmConfig)
                .as("GitHub generated dependency submission may run Maven under a different Java version")
                .doesNotExist();
        assertThat(exists).isFalse();
    }
}
