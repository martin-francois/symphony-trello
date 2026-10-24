package ch.fmartin.symphony.trello.setup;

import static ch.fmartin.symphony.trello.TextCharacterMatchers.UNICODE_LINE_SEPARATOR;
import static ch.fmartin.symphony.trello.TextCharacterMatchers.UNICODE_PARAGRAPH_SEPARATOR;
import static ch.fmartin.symphony.trello.setup.TerminalInputFixture.ESCAPE;
import static ch.fmartin.symphony.trello.testsupport.WindowsShimFixtures.NPM_PATHEXT;
import static ch.fmartin.symphony.trello.testsupport.WindowsShimFixtures.WINDOWS_OS_NAME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import ch.fmartin.symphony.trello.process.PlatformEnvironment;
import ch.fmartin.symphony.trello.setup.CodexModelSelectionDefaults.CatalogModel;
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
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

final class CodexModelDefaultsResolverTest {
    private static final String LINE_FEED = "\n";
    private static final String SAFE_MODEL = "gpt-safe";
    private static final String SAFE_REASONING_EFFORT = "low";

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
        assertThat(defaults.reasoningEffortForModel("gpt-5.4")).hasValue("medium");
        assertThat(defaults.reasoningEffortForModel("gpt-5.6-sol")).hasValue("low");
        assertThat(defaults.reasoningEffortChoicesForModel("gpt-5.4"))
                .hasValue(List.of("low", "medium", "high", "xhigh"));
        assertThat(defaults.reasoningEffortChoicesForModel("gpt-5.6-sol"))
                .hasValue(List.of("low", "medium", "high", "xhigh", "max", "ultra"));
        assertThat(defaults.reasoningEffortDescriptionForModel("gpt-5.6-sol", "max"))
                .hasValue("Maximum reasoning depth for the hardest problems");
        assertThat(defaults.reasoningEffortDescriptionForModel("gpt-5.6-sol", "ultra"))
                .hasValue("Maximum reasoning with automatic task delegation");
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
        assertThat(defaults.reasoningEffortChoicesForModel("gpt-5.6-sol")).hasValue(List.of("low", "medium", "high"));
        assertThat(defaults.reasoningEffortDescriptionForModel("gpt-5.6-sol", "low"))
                .hasValue("First");
    }

    @MethodSource("unsafeCatalogEntryScenarios")
    @ParameterizedTest
    void skipsAnUnsafeCatalogEntryAndKeepsTheRestOfTheCatalog(UnsafeCatalogEntryScenario scenario) throws Exception {
        // given
        var catalog = json.createArrayNode();
        catalog.addObject()
                .put("model", scenario.model())
                .put("defaultReasoningEffort", scenario.reasoningEffort())
                .put("hidden", scenario.hidden())
                .put("isDefault", true)
                .putArray("supportedReasoningEfforts")
                .addObject()
                .put("reasoningEffort", "high")
                .put("description", scenario.reasoningEffortDescription());
        catalog.addObject().put("model", SAFE_MODEL).put("defaultReasoningEffort", SAFE_REASONING_EFFORT);
        Path appServer = CodexModelAppServerFixture.createPaginated(tempDir, json.writeValueAsString(catalog), "[]");

        // when
        CodexModelSelectionDefaults defaults =
                new CodexModelDefaultsResolver(json, List.of(appServer.toString())).resolveSelectionDefaults();

        // then
        assertThat(defaults.defaults()).isEqualTo(new CodexModelDefaults(SAFE_MODEL, SAFE_REASONING_EFFORT));
        assertThat(defaults.visibleModels()).containsExactly(new CatalogModel(SAFE_MODEL, null, true));
        assertThat(defaults.reasoningEffortChoicesForModel(SAFE_MODEL)).isEmpty();
    }

    @MethodSource("unsafeSupportedEffortMetadata")
    @ParameterizedTest
    void skipsAnUnsafeFirstDuplicateBeforeNormalizingSupportedEffortMetadata(String field, String value)
            throws Exception {
        // given
        var catalog = json.createArrayNode();
        catalog.addObject()
                .put("model", SAFE_MODEL)
                .put("defaultReasoningEffort", "medium")
                .putArray("supportedReasoningEfforts")
                .addObject()
                .put("reasoningEffort", "high")
                .put("description", "Deep")
                .put(field, value);
        catalog.addObject().put("model", SAFE_MODEL).put("defaultReasoningEffort", SAFE_REASONING_EFFORT);
        Path appServer = CodexModelAppServerFixture.createPaginated(tempDir, json.writeValueAsString(catalog), "[]");

        // when
        CodexModelSelectionDefaults defaults =
                new CodexModelDefaultsResolver(json, List.of(appServer.toString())).resolveSelectionDefaults();

        // then
        assertThat(defaults.defaults()).isEqualTo(new CodexModelDefaults(SAFE_MODEL, SAFE_REASONING_EFFORT));
        assertThat(defaults.reasoningEffortChoicesForModel(SAFE_MODEL)).isEmpty();
    }

    private static Stream<Arguments> unsafeSupportedEffortMetadata() {
        return Stream.of(
                Arguments.of("reasoningEffort", "high\t"),
                Arguments.of("reasoningEffort", "\thigh"),
                Arguments.of("reasoningEffort", "\t"),
                Arguments.of("description", "Deep" + LINE_FEED),
                Arguments.of("description", LINE_FEED));
    }

    @Test
    void fallsBackToTheCompatibilityModelWhenEveryCatalogEntryHasControlCharacters() throws Exception {
        // given
        var catalog = json.createArrayNode();
        catalog.addObject().put("model", "gpt" + LINE_FEED + "forged").put("isDefault", true);
        Path appServer = CodexModelAppServerFixture.createPaginated(tempDir, json.writeValueAsString(catalog), "[]");

        // when
        CodexModelSelectionDefaults defaults =
                new CodexModelDefaultsResolver(json, List.of(appServer.toString())).resolveSelectionDefaults();

        // then
        assertThat(defaults.defaults()).isEqualTo(CodexModelDefaults.fallback());
        assertThat(defaults.visibleModels()).isEmpty();
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
                      printf '%s\n' '{"jsonrpc":"2.0","id":2,"result":{"data":[{"model":"gpt-5.6-terra","hidden":true,"defaultReasoningEffort":"medium","isDefault":true,"supportedReasoningEfforts":[{"reasoningEffort":"low","description":"Fast Terra"},{"reasoningEffort":"medium","description":"Balanced Terra"},{"reasoningEffort":"high","description":"Deep Terra"}]},{"model":"gpt-visible","hidden":false,"defaultReasoningEffort":"high","isDefault":false,"supportedReasoningEfforts":[{"reasoningEffort":"high","description":"Visible default"}]}]}}'
                      ;;
                  esac
                done
                """);

        // when
        CodexModelSelectionDefaults defaults =
                new CodexModelDefaultsResolver(json, List.of(appServer.toString())).resolveSelectionDefaults();

        // then
        assertThat(defaults.defaults()).isEqualTo(new CodexModelDefaults("gpt-visible", "high"));
        assertThat(defaults.reasoningEffortChoicesForModel("gpt-5.6-terra")).hasValue(List.of("low", "medium", "high"));
        assertThat(defaults.reasoningEffortDescriptionForModel("gpt-5.6-terra", "high"))
                .hasValue("Deep Terra");
    }

    @Test
    void usesFirstVisibleUsableModelWithoutSpecialSolFallback() throws Exception {
        // given
        Path appServer = appServerScript(
                """
                while IFS= read -r line; do
                  case "$line" in
                    *'"method":"initialize"'*)
                      printf '%s\\n' '{"jsonrpc":"2.0","id":1,"result":{}}'
                      ;;
                    *'"method":"model/list"'*)
                      printf '%s\\n' '{"jsonrpc":"2.0","id":2,"result":{"data":[{"model":"gpt-visible-first","defaultReasoningEffort":"high"},{"model":"gpt-5.6-sol","defaultReasoningEffort":"low"}]}}'
                      ;;
                  esac
                done
                """);

        // when
        CodexModelDefaults defaults = new CodexModelDefaultsResolver(json, List.of(appServer.toString())).resolve();

        // then
        assertThat(defaults).isEqualTo(new CodexModelDefaults("gpt-visible-first", "high"));
    }

    @Test
    void listsVisibleCatalogModelsForThePickerFromTheFirstUsableEntryOfEachModel() throws Exception {
        // given
        Path appServer = CodexModelAppServerFixture.createPaginated(
                tempDir,
                """
                [{"model":"gpt-6.1-sol","displayName":"GPT-6.1-Sol","isDefault":true,"defaultReasoningEffort":"low"},{"model":"gpt-reserve","displayName":"GPT-Reserve","hidden":true},{"model":" "},{"displayName":"No model id"},"not-an-object",{"model":"gpt-5.5","displayName":" "}]
                """,
                """
                [{"model":"gpt-5.6-terra","displayName":"GPT-5.6-Terra","defaultReasoningEffort":"medium"},{"model":"gpt-6.1-sol","displayName":"Duplicate Sol","defaultReasoningEffort":"ultra","supportedReasoningEfforts":[{"reasoningEffort":"ultra"}]}]
                """);

        // when
        CodexModelSelectionDefaults defaults =
                new CodexModelDefaultsResolver(json, List.of(appServer.toString())).resolveSelectionDefaults();

        // then
        assertThat(defaults.visibleModels())
                .containsExactly(
                        new CatalogModel("gpt-6.1-sol", "GPT-6.1-Sol", false),
                        new CatalogModel("gpt-5.5", null, false),
                        new CatalogModel("gpt-5.6-terra", "GPT-5.6-Terra", true));
        assertThat(defaults.defaults()).isEqualTo(new CodexModelDefaults("gpt-5.6-terra", "medium"));
        assertThat(defaults.reasoningEffortForModel("gpt-6.1-sol")).hasValue("low");
        assertThat(defaults.reasoningEffortChoicesForModel("gpt-6.1-sol")).isEmpty();
    }

    @MethodSource("unsafeDisplayNames")
    @ParameterizedTest
    void dropsAnUnsafeDisplayNameWithoutRejectingTheCatalog(String displayName) throws Exception {
        // given
        Path appServer = CodexModelAppServerFixture.createPaginated(
                tempDir,
                json.writeValueAsString(List.of(
                        Map.of("model", SAFE_MODEL, "displayName", displayName, "defaultReasoningEffort", "medium"))),
                "[]");

        // when
        CodexModelSelectionDefaults defaults =
                new CodexModelDefaultsResolver(json, List.of(appServer.toString())).resolveSelectionDefaults();

        // then
        assertThat(defaults.defaults()).isEqualTo(new CodexModelDefaults("gpt-safe", "medium"));
        assertThat(defaults.visibleModels()).containsExactly(new CatalogModel("gpt-safe", null, true));
    }

    @Test
    void recommendsTheFirstVisibleCatalogDefaultWhenSeveralEntriesAreMarkedDefault() throws Exception {
        // given
        Path appServer = CodexModelAppServerFixture.createPaginated(
                tempDir,
                """
                [{"model":"gpt-first-visible"},{"model":"gpt-first-default","isDefault":true},{"model":"gpt-later-default","isDefault":true}]
                """,
                "[]");

        // when
        CodexModelSelectionDefaults defaults =
                new CodexModelDefaultsResolver(json, List.of(appServer.toString())).resolveSelectionDefaults();

        // then
        assertThat(defaults.defaults().model()).isEqualTo("gpt-first-default");
        assertThat(defaults.visibleModels())
                .extracting(CatalogModel::model, CatalogModel::recommended)
                .containsExactly(
                        tuple("gpt-first-visible", false),
                        tuple("gpt-first-default", true),
                        tuple("gpt-later-default", false));
    }

    @Test
    void prefersTerraRecommendationFromLaterModelListPageOverCatalogDefault() throws Exception {
        // given
        Path appServer = CodexModelAppServerFixture.createPaginated(
                tempDir,
                """
                [{"model":"gpt-5.5","defaultReasoningEffort":"medium","isDefault":true}]
                """,
                """
                [{"model":"gpt-5.6-terra","defaultReasoningEffort":"high","isDefault":false,"supportedReasoningEfforts":[{"reasoningEffort":"high","description":"Terra recommendation from Codex"},{"reasoningEffort":"max","description":"Deeper Terra reasoning"}]}]
                """);

        // when
        CodexModelSelectionDefaults defaults =
                new CodexModelDefaultsResolver(json, List.of(appServer.toString())).resolveSelectionDefaults();

        // then
        assertThat(defaults.defaults()).isEqualTo(new CodexModelDefaults("gpt-5.6-terra", "high"));
        assertThat(defaults.reasoningEffortForModel("gpt-5.6-terra")).hasValue("high");
        assertThat(defaults.reasoningEffortChoicesForModel("gpt-5.6-terra")).hasValue(List.of("high", "max"));
        assertThat(defaults.reasoningEffortDescriptionForModel("gpt-5.6-terra", "high"))
                .hasValue("Terra recommendation from Codex");
    }

    @Test
    void prefersVisibleTerraWithoutInventingReasoningRecommendation() throws Exception {
        // given
        Path appServer = appServerScript(
                """
                while IFS= read -r line; do
                  case "$line" in
                    *'"method":"initialize"'*)
                      printf '%s\\n' '{"jsonrpc":"2.0","id":1,"result":{}}'
                      ;;
                    *'"method":"model/list"'*)
                      printf '%s\\n' '{"jsonrpc":"2.0","id":2,"result":{"data":[{"model":"gpt-5.5","defaultReasoningEffort":"medium","isDefault":true},{"model":"gpt-5.6-terra","isDefault":false,"supportedReasoningEfforts":[{"reasoningEffort":"low"},{"reasoningEffort":"high"}]}]}}'
                      ;;
                  esac
                done
                """);

        // when
        CodexModelSelectionDefaults defaults =
                new CodexModelDefaultsResolver(json, List.of(appServer.toString())).resolveSelectionDefaults();

        // then
        assertThat(defaults.defaults()).isEqualTo(CodexModelDefaults.partial("gpt-5.6-terra", null));
        assertThat(defaults.reasoningEffortForModel("gpt-5.6-terra")).isEmpty();
        assertThat(defaults.reasoningEffortChoicesForModel("gpt-5.6-terra")).hasValue(List.of("low", "high"));
    }

    @Test
    void selectsCatalogDefaultModelWithoutInventingReasoningRecommendation() throws Exception {
        // given
        Path appServer = appServerScript(
                """
                while IFS= read -r line; do
                  case "$line" in
                    *'"method":"initialize"'*)
                      printf '%s\\n' '{"jsonrpc":"2.0","id":1,"result":{}}'
                      ;;
                    *'"method":"model/list"'*)
                      printf '%s\\n' '{"jsonrpc":"2.0","id":2,"result":{"data":[{"model":"gpt-no-default","isDefault":true,"supportedReasoningEfforts":[{"reasoningEffort":"low"},{"reasoningEffort":"high"}]},{"model":"gpt-other","defaultReasoningEffort":"medium","isDefault":false}]}}'
                      ;;
                  esac
                done
                """);

        // when
        CodexModelSelectionDefaults defaults =
                new CodexModelDefaultsResolver(json, List.of(appServer.toString())).resolveSelectionDefaults();

        // then
        assertThat(defaults.defaults()).isEqualTo(CodexModelDefaults.partial("gpt-no-default", null));
        assertThat(defaults.reasoningEffortForModel("gpt-no-default")).isEmpty();
        assertThat(defaults.reasoningEffortChoicesForModel("gpt-no-default")).hasValue(List.of("low", "high"));
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
    void startsNpmCodexShimThroughTheCommandInterpreterOnWindows() throws Exception {
        // given
        Path npmPrefix = Files.createDirectories(tempDir.resolve("npm"));
        Path codexShim = Files.writeString(npmPrefix.resolve("codex.CMD"), "");
        Path interpreterArguments = tempDir.resolve("interpreter-arguments.txt");
        Path commandInterpreter = tempDir.resolve("cmd.exe");
        Files.writeString(
                commandInterpreter,
                """
                #!/bin/bash
                printf '%s\n' "$@" > "$CAPTURE"
                while IFS= read -r line; do
                  case "$line" in
                    *'"method":"initialize"'*)
                      printf '%s\n' '{"jsonrpc":"2.0","id":1,"result":{}}'
                      ;;
                    *'"method":"model/list"'*)
                      printf '%s\n' '{"jsonrpc":"2.0","id":2,"result":{"data":[{"model":"gpt-5.5","defaultReasoningEffort":"medium","isDefault":true}]}}'
                      ;;
                  esac
                done
                """);
        commandInterpreter.toFile().setExecutable(true);

        // when
        CodexModelDefaults defaults = new CodexModelDefaultsResolver(
                        json,
                        List.of("codex", "app-server"),
                        PlatformEnvironment.of(
                                Map.of(
                                        "PATH",
                                        npmPrefix.toString(),
                                        "PATHEXT",
                                        NPM_PATHEXT,
                                        "ComSpec",
                                        commandInterpreter.toString(),
                                        "CAPTURE",
                                        interpreterArguments.toString()),
                                WINDOWS_OS_NAME),
                        Duration.ofSeconds(5))
                .resolve();

        // then
        assertThat(defaults).isEqualTo(new CodexModelDefaults("gpt-5.5", "medium"));
        assertThat(Files.readAllLines(interpreterArguments))
                .containsExactly("/d", "/s", "/c", "\"\"" + codexShim + "\" \"app-server\"\"");
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

    private static List<String> unsafeDisplayNames() {
        return List.of(
                "GPT" + ESCAPE + "[2J Safe",
                "GPT" + UNICODE_LINE_SEPARATOR + "Safe",
                "GPT" + UNICODE_PARAGRAPH_SEPARATOR + "Safe");
    }

    private static Stream<UnsafeCatalogEntryScenario> unsafeCatalogEntryScenarios() {
        String otherModel = "gpt-other";
        String effort = "medium";
        String description = "Deep";
        String unsafeEffort = "med" + ESCAPE + "ium";
        return Stream.of(
                new UnsafeCatalogEntryScenario("model", "gpt" + LINE_FEED + "forged", effort, description, false),
                new UnsafeCatalogEntryScenario(
                        "model with a trailing line break", otherModel + LINE_FEED, effort, description, false),
                new UnsafeCatalogEntryScenario(
                        "model with line separator",
                        "gpt" + UNICODE_LINE_SEPARATOR + "forged",
                        effort,
                        description,
                        false),
                new UnsafeCatalogEntryScenario(
                        "model with paragraph separator",
                        "gpt" + UNICODE_PARAGRAPH_SEPARATOR + "forged",
                        effort,
                        description,
                        false),
                new UnsafeCatalogEntryScenario(
                        "model with trailing line separator",
                        otherModel + UNICODE_LINE_SEPARATOR,
                        effort,
                        description,
                        false),
                new UnsafeCatalogEntryScenario(
                        "model with trailing paragraph separator",
                        otherModel + UNICODE_PARAGRAPH_SEPARATOR,
                        effort,
                        description,
                        false),
                new UnsafeCatalogEntryScenario("default effort", otherModel, unsafeEffort, description, false),
                new UnsafeCatalogEntryScenario(
                        "reasoning effort description", otherModel, effort, description + ESCAPE + "[2J", false),
                new UnsafeCatalogEntryScenario("hidden entry", otherModel, unsafeEffort, description, true),
                new UnsafeCatalogEntryScenario(
                        "first entry of a repeated model", SAFE_MODEL, unsafeEffort, description, false));
    }

    private Path appServerScript(String body) throws Exception {
        return CodexModelAppServerFixture.create(tempDir, body);
    }

    private record UnsafeCatalogEntryScenario(
            String name, String model, String reasoningEffort, String reasoningEffortDescription, boolean hidden) {
        @Override
        public String toString() {
            return name;
        }
    }
}
