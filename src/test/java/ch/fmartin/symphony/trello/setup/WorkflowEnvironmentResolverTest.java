package ch.fmartin.symphony.trello.setup;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class WorkflowEnvironmentResolverTest {
    @TempDir
    Path tempDir;

    @Test
    void externalHttpPortOverrideSourceUsesConfiguredEnvironmentNameOrder() {
        // given
        Map<String, String> environment = Map.of("SYMPHONY_HTTP_PORT", "18080", "QUARKUS_HTTP_PORT", "19080");

        // when
        var source = WorkflowEnvironmentResolver.externalHttpPortOverrideSource(environment, tempDir.resolve(".env"));

        // then
        assertThat(source).contains("SYMPHONY_HTTP_PORT environment variable");
    }
}
