package ch.fmartin.symphony.trello.config;

import static org.assertj.core.api.Assertions.assertThat;

import ch.fmartin.symphony.trello.workflow.WorkflowDefinition;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class CredentialSourcesTest {
    @TempDir
    Path temporary;

    @Test
    void retainsCustomVariableFileSecretAndShadowedDotenvSources() throws Exception {
        // given
        Path source = Files.createDirectory(temporary.resolve("source"));
        Path key = Files.writeString(source.resolve("key"), "synthetic-key");
        Path dotenv = Files.writeString(temporary.resolve("custom.env"), "CUSTOM_TOKEN=shadowed-token\n");
        Path workflow = temporary.resolve("managed.md");
        var resolver = new ConfigResolver(LocalEnvironment.sources(Map.of("CUSTOM_TOKEN", "synthetic-token"), dotenv));
        var definition = new WorkflowDefinition(
                workflow, Map.of("tracker", Map.of("api_key", "file:key", "api_token", "$CUSTOM_TOKEN")), "");

        // when
        EffectiveConfig config = resolver.resolve(definition, source);

        // then
        assertThat(config.credentialSources().files()).containsExactlyInAnyOrder(workflow, key, dotenv);
        assertThat(config.credentialSources().environmentVariables())
                .containsExactlyInAnyOrder("TRELLO_API_KEY", "TRELLO_API_TOKEN", "CUSTOM_TOKEN");
        assertThat(config.withResolvedBoardId("resolved").credentialSources()).isEqualTo(config.credentialSources());
        assertThat(config.credentialSources().toString())
                .doesNotContain("synthetic-key", "synthetic-token", "shadowed-token");
    }
}
