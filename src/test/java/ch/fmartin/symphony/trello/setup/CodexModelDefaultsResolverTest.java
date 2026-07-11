package ch.fmartin.symphony.trello.setup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import ch.fmartin.symphony.trello.setup.TrelloBoardSetup.CodexModelDefaults;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

final class CodexModelDefaultsResolverTest {
    private final ObjectMapper json = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void resolvesDefaultModelAndReasoningEffortFromCodexAppServer() throws Exception {
        // given
        Path appServer = appServerScript(
                """
                while IFS= read -r line; do
                  case "$line" in
                    *'"method":"initialize"'*)
                      printf '%s\\n' '{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":1}}'
                      ;;
                    *'"method":"model/list"'*)
                      printf '%s\\n' '{"jsonrpc":"2.0","method":"codex/event","params":{"msg":"ignored notification"}}'
                      printf '%s\\n' '{"jsonrpc":"2.0","id":2,"result":{"data":[{"model":"gpt-5.4","defaultReasoningEffort":"medium","isDefault":false,"supportedReasoningEfforts":[{"reasoningEffort":"low"},{"reasoningEffort":"medium"},{"reasoningEffort":"high"},{"reasoningEffort":"xhigh"}]},{"model":"gpt-6","defaultReasoningEffort":"high","isDefault":true,"supportedReasoningEfforts":[{"reasoningEffort":"high"},{"reasoningEffort":"max"},{"reasoningEffort":"ultra"}]}]}}'
                      ;;
                  esac
                done
                """);

        // when
        CodexModelDefaults defaults = new CodexModelDefaultsResolver(json, List.of(appServer.toString())).resolve();

        // then
        assertThat(defaults).isEqualTo(new CodexModelDefaults("gpt-6", "high"));
    }

    @Test
    void keepsModelSpecificReasoningEffortsFromCodexAppServer() throws Exception {
        // given
        Path appServer = appServerScript(
                """
                while IFS= read -r line; do
                  case "$line" in
                    *'"method":"initialize"'*)
                      printf '%s\\n' '{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":1}}'
                      ;;
                    *'"method":"model/list"'*)
                      printf '%s\\n' '{"jsonrpc":"2.0","id":2,"result":{"data":[{"model":"gpt-5.4","defaultReasoningEffort":"medium","isDefault":true,"supportedReasoningEfforts":[{"reasoningEffort":"low","description":"Fast responses"},{"reasoningEffort":"medium","description":"Balanced reasoning"},{"reasoningEffort":"high","description":"Greater reasoning depth"},{"reasoningEffort":"xhigh","description":"Extra high reasoning depth"}]},{"model":"gpt-5.6-sol","defaultReasoningEffort":"low","isDefault":false,"supportedReasoningEfforts":[{"reasoningEffort":"low","description":"Fast responses with lighter reasoning"},{"reasoningEffort":"medium","description":"Balances speed and reasoning depth for everyday tasks"},{"reasoningEffort":"high","description":"Greater reasoning depth for complex problems"},{"reasoningEffort":"xhigh","description":"Extra high reasoning depth for complex problems"},{"reasoningEffort":"max","description":"Maximum reasoning depth for the hardest problems"},{"reasoningEffort":"ultra","description":"Maximum reasoning with automatic task delegation"}]}]}}'
                      ;;
                  esac
                done
                """);

        // when
        CodexModelSelectionDefaults defaults =
                new CodexModelDefaultsResolver(json, List.of(appServer.toString())).resolveSelectionDefaults();

        // then
        assertThat(defaults.defaults()).isEqualTo(new CodexModelDefaults("gpt-5.4", "medium"));
        assertThat(defaults.reasoningEffortForModel("gpt-5.4")).contains("medium");
        assertThat(defaults.reasoningEffortForModel("gpt-5.6-sol")).contains("low");
        assertThat(defaults.reasoningEffortChoicesForModel("gpt-5.4"))
                .contains(List.of("low", "medium", "high", "xhigh"));
        assertThat(defaults.reasoningEffortChoicesForModel("gpt-5.6-sol"))
                .contains(List.of("low", "medium", "high", "xhigh", "max", "ultra"));
        assertThat(defaults.reasoningEffortDescriptionForModel("gpt-5.6-sol", "max"))
                .contains("Maximum reasoning depth for the hardest problems");
        assertThat(defaults.reasoningEffortDescriptionForModel("gpt-5.6-sol", "ultra"))
                .contains("Maximum reasoning with automatic task delegation");
    }

    @Test
    void normalizesAndDeduplicatesSupportedReasoningEffortsInCatalogOrder() throws Exception {
        // given
        Path appServer = appServerScript(
                """
                while IFS= read -r line; do
                  case "$line" in
                    *'"method":"initialize"'*)
                      printf '%s\\n' '{"jsonrpc":"2.0","id":1,"result":{}}'
                      ;;
                    *'"method":"model/list"'*)
                      printf '%s\\n' '{"jsonrpc":"2.0","id":2,"result":{"data":[{"model":"gpt-5.6-sol","defaultReasoningEffort":"medium","isDefault":true,"supportedReasoningEfforts":[{"reasoningEffort":" low ","description":"First"},{"reasoningEffort":" "},{"description":"Missing effort"},{"reasoningEffort":"medium","description":"Balanced"},{"reasoningEffort":"low","description":"Ignored duplicate"},{"reasoningEffort":"high","description":"Deep"}] }]}}'
                      ;;
                  esac
                done
                """);

        // when
        CodexModelSelectionDefaults defaults =
                new CodexModelDefaultsResolver(json, List.of(appServer.toString())).resolveSelectionDefaults();

        // then
        assertThat(defaults.reasoningEffortChoicesForModel("gpt-5.6-sol")).contains(List.of("low", "medium", "high"));
        assertThat(defaults.reasoningEffortDescriptionForModel("gpt-5.6-sol", "low"))
                .contains("First");
    }

    @MethodSource("catalogControlCharacterScenarios")
    @ParameterizedTest
    void rejectsControlCharactersInCatalogModelMetadata(CatalogControlCharacterScenario scenario) throws Exception {
        // given
        var model = json.createObjectNode();
        model.put("model", scenario.model());
        model.put("defaultReasoningEffort", scenario.reasoningEffort());
        model.put("isDefault", true);
        var response = json.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.put("id", 2);
        response.putObject("result").putArray("data").add(model);
        String responseJson = json.writeValueAsString(response);
        Path appServer = appServerScript(
                """
                while IFS= read -r line; do
                  case "$line" in
                    *'"method":"initialize"'*)
                      printf '%%s\\n' '{"jsonrpc":"2.0","id":1,"result":{}}'
                      ;;
                    *'"method":"model/list"'*)
                      printf '%%s\\n' '%s'
                      ;;
                  esac
                done
                """
                        .formatted(responseJson));

        // when
        CodexModelDefaults defaults = new CodexModelDefaultsResolver(json, List.of(appServer.toString())).resolve();

        // then
        assertThat(defaults).isEqualTo(CodexModelDefaults.unsupportedFirstClassFields());
    }

    @Test
    void keepsHiddenModelReasoningEffortsWithoutSelectingHiddenDefault() throws Exception {
        // given
        Path appServer = appServerScript(
                """
                while IFS= read -r line; do
                  case "$line" in
                    *'"method":"initialize"'*)
                      printf '%s\n' '{"jsonrpc":"2.0","id":1,"result":{}}'
                      ;;
                    *'"method":"model/list"'*)
                      printf '%s\n' '{"jsonrpc":"2.0","id":2,"result":{"data":[{"model":"codex-auto-review","hidden":true,"defaultReasoningEffort":"medium","isDefault":true,"supportedReasoningEfforts":[{"reasoningEffort":"low","description":"Fast review"},{"reasoningEffort":"medium","description":"Balanced review"},{"reasoningEffort":"high","description":"Deep review"},{"reasoningEffort":"xhigh","description":"Extra-deep review"}]},{"model":"gpt-visible","hidden":false,"defaultReasoningEffort":"high","isDefault":false,"supportedReasoningEfforts":[{"reasoningEffort":"high","description":"Visible default"}]}]}}'
                      ;;
                  esac
                done
                """);

        // when
        CodexModelSelectionDefaults defaults =
                new CodexModelDefaultsResolver(json, List.of(appServer.toString())).resolveSelectionDefaults();

        // then
        assertThat(defaults.defaults()).isEqualTo(new CodexModelDefaults("gpt-visible", "high"));
        assertThat(defaults.reasoningEffortChoicesForModel("codex-auto-review"))
                .contains(List.of("low", "medium", "high", "xhigh"));
        assertThat(defaults.reasoningEffortDescriptionForModel("codex-auto-review", "xhigh"))
                .contains("Extra-deep review");
    }

    @Test
    void usesFirstModelWhenCodexAppServerDoesNotFlagDefaultModel() throws Exception {
        // given
        Path appServer = appServerScript(
                """
                while IFS= read -r line; do
                  case "$line" in
                    *'"method":"initialize"'*)
                      printf '%s\\n' '{"jsonrpc":"2.0","id":1,"result":{}}'
                      ;;
                    *'"method":"model/list"'*)
                      printf '%s\\n' '{"jsonrpc":"2.0","id":2,"result":{"data":[{"model":"gpt-5.5","defaultReasoningEffort":"medium"},{"model":"gpt-6","defaultReasoningEffort":"high"}]}}'
                      ;;
                  esac
                done
                """);

        // when
        CodexModelDefaults defaults = new CodexModelDefaultsResolver(json, List.of(appServer.toString())).resolve();

        // then
        assertThat(defaults).isEqualTo(new CodexModelDefaults("gpt-5.5", "medium"));
    }

    @Test
    void searchesPaginatedModelListBeforeSelectingDefaultModel() throws Exception {
        // given
        Path appServer = appServerScript(
                """
                while IFS= read -r line; do
                  case "$line" in
                    *'"method":"initialize"'*)
                      printf '%s\\n' '{"jsonrpc":"2.0","id":1,"result":{}}'
                      ;;
                    *'"method":"model/list"'*'"cursor":"page-2"'*)
                      printf '%s\\n' '{"jsonrpc":"2.0","id":3,"result":{"data":[{"model":"gpt-6","defaultReasoningEffort":"high","isDefault":true}]}}'
                      ;;
                    *'"method":"model/list"'*)
                      printf '%s\\n' '{"jsonrpc":"2.0","id":2,"result":{"data":[{"model":"gpt-5.5","defaultReasoningEffort":"medium","isDefault":false}],"nextCursor":"page-2"}}'
                      ;;
                  esac
                done
                """);

        // when
        CodexModelDefaults defaults = new CodexModelDefaultsResolver(json, List.of(appServer.toString())).resolve();

        // then
        assertThat(defaults).isEqualTo(new CodexModelDefaults("gpt-6", "high"));
    }

    @Test
    void fallsBackWhenCodexAppServerCannotBeQueried() {
        // given
        Path missingCommand = tempDir.resolve("missing-codex");

        // when
        CodexModelDefaults defaults =
                new CodexModelDefaultsResolver(json, List.of(missingCommand.toString())).resolve();

        // then
        assertThat(defaults).isEqualTo(CodexModelDefaults.unsupportedFirstClassFields());
    }

    @Test
    void treatsModelListErrorsAsUnsupportedFirstClassFields() throws Exception {
        // given
        Path appServer = appServerScript(
                """
                while IFS= read -r line; do
                  case "$line" in
                    *'"method":"initialize"'*)
                      printf '%s\\n' '{"jsonrpc":"2.0","id":1,"result":{}}'
                      ;;
                    *'"method":"model/list"'*)
                      printf '%s\\n' '{"jsonrpc":"2.0","id":2,"error":{"code":-32601,"message":"method not found"}}'
                      ;;
                  esac
                done
                """);

        // when
        CodexModelDefaults defaults = new CodexModelDefaultsResolver(json, List.of(appServer.toString())).resolve();

        // then
        assertThat(defaults).isEqualTo(CodexModelDefaults.unsupportedFirstClassFields());
    }

    @Test
    void treatsNotificationOnlyModelListAsUnsupportedFirstClassFields() throws Exception {
        // given
        Path appServer = appServerScript(
                """
                while IFS= read -r line; do
                  case "$line" in
                    *'"method":"initialize"'*)
                      printf '%s\\n' '{"id":1,"result":{}}'
                      ;;
                    *'"method":"model/list"'*)
                      while true; do
                        printf '%s\\n' '{"method":"codex/event","params":{"msg":"still working"}}'
                        sleep 0.01
                      done
                      ;;
                  esac
                done
                """);

        // when
        CodexModelDefaults defaults = new CodexModelDefaultsResolver(
                        json, List.of(appServer.toString()), Map.of(), Duration.ofMillis(100))
                .resolve();

        // then
        assertThat(defaults).isEqualTo(CodexModelDefaults.unsupportedFirstClassFields());
    }

    @Test
    void fallsBackWhenCodexAppServerStallsWithOpenStdout() throws Exception {
        // given
        Path appServer = appServerScript(
                """
                while IFS= read -r line; do
                  case "$line" in
                    *'"method":"initialize"'*)
                      printf '%s\\n' '{"id":1,"result":{}}'
                      ;;
                    *'"method":"model/list"'*)
                      sleep 60
                      ;;
                  esac
                done
                """);

        // when
        CodexModelDefaults defaults =
                assertTimeoutPreemptively(Duration.ofSeconds(2), () -> new CodexModelDefaultsResolver(
                                json, List.of(appServer.toString()), Map.of(), Duration.ofMillis(100))
                        .resolve());

        // then
        assertThat(defaults).isEqualTo(CodexModelDefaults.unsupportedFirstClassFields());
    }

    @Test
    void doesNotExposeTrelloCredentialsToCodexAppServerProcess() throws Exception {
        // given
        Path environmentCapture = tempDir.resolve("codex-env.txt");
        Path appServer = appServerScript(
                """
                printf 'key=%s token=%s\\n' "${TRELLO_API_KEY-unset}" "${TRELLO_API_TOKEN-unset}" > "$CAPTURE"
                while IFS= read -r line; do
                  case "$line" in
                    *'"method":"initialize"'*)
                      printf '%s\\n' '{"jsonrpc":"2.0","id":1,"result":{}}'
                      ;;
                    *'"method":"model/list"'*)
                      printf '%s\\n' '{"jsonrpc":"2.0","id":2,"result":{"data":[{"model":"gpt-5.5","defaultReasoningEffort":"medium","isDefault":true}]}}'
                      ;;
                  esac
                done
                """);

        // when
        new CodexModelDefaultsResolver(
                        json,
                        List.of(appServer.toString()),
                        Map.of(
                                "CAPTURE",
                                environmentCapture.toString(),
                                "TRELLO_API_KEY",
                                "key",
                                "TRELLO_API_TOKEN",
                                "token"))
                .resolve();

        // then
        assertThat(environmentCapture).content(StandardCharsets.UTF_8).isEqualTo("key=unset token=unset\n");
    }

    @Test
    void usesCodexAppServerRequestEnvelopeWithoutJsonRpcVersionField() throws Exception {
        // given
        Path requestCapture = tempDir.resolve("codex-requests.txt");
        Path appServer = appServerScript(
                """
                while IFS= read -r line; do
                  printf '%s\\n' "$line" >> "$CAPTURE"
                  case "$line" in
                    *'"method":"initialize"'*)
                      printf '%s\\n' '{"id":1,"result":{}}'
                      ;;
                    *'"method":"model/list"'*)
                      printf '%s\\n' '{"id":2,"result":{"data":[{"model":"gpt-5.5","defaultReasoningEffort":"medium","isDefault":true}]}}'
                      ;;
                  esac
                done
                """);

        // when
        CodexModelDefaults defaults = new CodexModelDefaultsResolver(
                        json, List.of(appServer.toString()), Map.of("CAPTURE", requestCapture.toString()))
                .resolve();

        // then
        assertThat(defaults).isEqualTo(new CodexModelDefaults("gpt-5.5", "medium"));
        assertThat(requestCapture)
                .content(StandardCharsets.UTF_8)
                .contains(
                        "\"id\":1",
                        "\"method\":\"initialize\"",
                        "\"method\":\"initialized\"",
                        "\"clientInfo\":{\"name\":\"symphony-trello-setup\",\"version\":\"development\"}")
                .contains("\"includeHidden\":true")
                .doesNotContain("\"version\":\"0.1.0\"")
                .doesNotContain("\"jsonrpc\"");
    }

    private static Stream<CatalogControlCharacterScenario> catalogControlCharacterScenarios() {
        return Stream.of(
                new CatalogControlCharacterScenario("model", "gpt\nforged", "medium"),
                new CatalogControlCharacterScenario(
                        "default effort", "gpt-safe", "med" + Character.toString(0x1B) + "ium"));
    }

    private Path appServerScript(String body) throws Exception {
        Path appServer = tempDir.resolve("codex-app-server");
        Files.writeString(appServer, "#!/usr/bin/env bash\nset -euo pipefail\n" + body, StandardCharsets.UTF_8);
        appServer.toFile().setExecutable(true);
        return appServer;
    }

    private record CatalogControlCharacterScenario(String name, String model, String reasoningEffort) {
        @Override
        public String toString() {
            return name;
        }
    }
}
