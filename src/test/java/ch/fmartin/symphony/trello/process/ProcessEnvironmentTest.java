package ch.fmartin.symphony.trello.process;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class ProcessEnvironmentTest {

    @Test
    void removesDefaultTrelloSecretsWithoutChangingOtherEnvironment() {
        // given
        Map<String, String> environment = new HashMap<>();
        environment.put("PATH", "/usr/bin");
        environment.put("TRELLO_API_KEY", "key");
        environment.put("TRELLO_API_TOKEN", "token");

        // when
        ProcessEnvironment.removeDefaultSecrets(environment);

        // then
        assertThat(environment)
                .containsEntry("PATH", "/usr/bin")
                .doesNotContainKeys("TRELLO_API_KEY", "TRELLO_API_TOKEN");
    }
}
