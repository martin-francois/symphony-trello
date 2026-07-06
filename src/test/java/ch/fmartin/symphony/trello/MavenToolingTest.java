package ch.fmartin.symphony.trello;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class MavenToolingTest {

    @Test
    void repositoryUsesGlobalMavenJvmConfigForJava25() {
        // given
        Path globalJvmConfig = Path.of(".mvn/jvm.config");

        // when

        // then
        assertThat(globalJvmConfig)
                .as(
                        "Repository must configure Maven's Java 25 runtime flag globally because generated Automatic Dependency Submission is disabled")
                .exists()
                .content()
                .isEqualTo("--sun-misc-unsafe-memory-access=allow\n");
    }
}
