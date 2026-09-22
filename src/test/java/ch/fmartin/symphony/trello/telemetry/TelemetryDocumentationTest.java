package ch.fmartin.symphony.trello.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/// Keeps the short notice, the bundled privacy document, the specification, the README, and the
/// dashboard document consistent with the field catalog and the command names.
final class TelemetryDocumentationTest {
    private static final Path PRIVACY = Path.of("docs/telemetry-privacy.md");
    private static final Path DASHBOARD = Path.of("docs/telemetry-dashboard.md");
    private static final Path RUNBOOK = Path.of("docs/telemetry-maintainer-runbook.md");
    private static final Path README = Path.of("README.md");
    private static final Path SPEC = Path.of("SPEC.md");
    private static final Path ADR = Path.of("docs/adr/0079-installation-telemetry.md");
    private static final List<String> COMMANDS = List.of(
            "symphony-trello telemetry status",
            "symphony-trello telemetry preview",
            "symphony-trello telemetry privacy",
            "symphony-trello telemetry enable",
            "symphony-trello telemetry disable",
            "symphony-trello telemetry debug");
    private static final List<String> VARIABLES = List.of(
            TelemetryEnvironment.DISABLED_VARIABLE,
            TelemetryEnvironment.DEBUG_VARIABLE,
            TelemetryEnvironment.LOG_VARIABLE);

    @Test
    void bundledPrivacyTextIsTheRepositoryDocumentAndNamesEveryField() throws IOException {
        // given
        String bundled = TelemetryPrivacyText.load();

        // when
        String published = Files.readString(PRIVACY);

        // then
        assertThat(bundled).isEqualTo(published);
        assertThat(HeartbeatField.values())
                .allSatisfy(field -> assertThat(published).contains("`" + field.jsonName() + "`"));
        assertThat(published).contains(TelemetryDistribution.PRODUCTION_ENDPOINT.toString());
        assertThat(published).contains(COMMANDS.getFirst()).contains(VARIABLES.getFirst());
    }

    @Test
    void noticeAndPurposeSentenceStayConsistentWithThePrivacyDocument() throws IOException {
        // given
        String published = Files.readString(PRIVACY);

        // when
        List<String> notice = TelemetryNotice.lines(Optional.empty(), TelemetryFixture.NOON);

        // then
        assertThat(notice).contains(TelemetryNotice.FIELD_SUMMARY, TelemetryNotice.PURPOSE_SENTENCE);
        assertThat(notice).anySatisfy(line -> assertThat(line).endsWith(TelemetryNotice.DISABLE_COMMAND));
        assertThat(published).contains("uses these reports only to improve symphony-trello");
        assertThat(String.join("\n", notice)).doesNotContain("—").doesNotContain("“");
    }

    @Test
    void specificationReadmeAndDashboardNameTheCommandsVariablesAndFields() throws IOException {
        // given
        String spec = Files.readString(SPEC);
        String readme = Files.readString(README);
        String dashboard = Files.readString(DASHBOARD);
        String runbook = Files.readString(RUNBOOK);

        // when
        String userFacing = readme + spec;

        // then
        assertThat(spec)
                .contains("### 19.6 Installation Telemetry Profile (OPTIONAL)")
                .contains("telemetry.json");
        assertThat(HeartbeatField.values())
                .allSatisfy(field -> assertThat(spec).contains("`" + field.jsonName() + "`"));
        assertThat(VARIABLES).allSatisfy(variable -> {
            assertThat(spec).contains(variable);
            assertThat(readme).contains(variable);
        });
        assertThat(readme).contains(TelemetryNotice.DISABLE_COMMAND).contains("docs/telemetry-privacy.md");
        assertThat(COMMANDS)
                .allSatisfy(command -> assertThat(userFacing).contains(command.substring("symphony-trello ".length())));
        assertThat(HeartbeatField.properties())
                .allSatisfy(field -> assertThat(dashboard).contains(field.jsonName()));
        assertThat(runbook)
                .contains(TelemetryDistribution.TOKEN_KEY)
                .contains("persons/bulk_delete/")
                .contains("\"delete_events\": true")
                .contains("persons/deletion_status/")
                .contains("docs/telemetry-erasure-verification.md");
    }

    @Test
    void releasePackagingScriptUsesTheSameTokenKeyPlaceholderAndShape() throws IOException {
        // given
        String script = Files.readString(Path.of("scripts/package-release-assets.sh"));

        // when
        String tokenKey = "TELEMETRY_TOKEN_KEY=\"" + TelemetryDistribution.TOKEN_KEY + "\"";
        String placeholder = "TELEMETRY_TOKEN_PLACEHOLDER=\"" + TelemetryDistribution.TOKEN_PLACEHOLDER + "\"";

        // then
        assertThat(script).contains(tokenKey).contains(placeholder).contains("^phc_[A-Za-z0-9]{40,64}$");
        assertThat(TelemetryDistribution.validToken("phc_" + "x".repeat(40))).isPresent();
        assertThat(TelemetryDistribution.validToken("phc_" + "x".repeat(65))).isEmpty();
    }

    @Test
    void adrCoversEveryAlternativeIdFromTheBrief() throws IOException {
        // given
        String adr = Files.readString(ADR);

        // when
        List<String> ids = new ArrayList<>();
        for (int id = 1; id <= 32; id++) {
            ids.add(String.format("A%02d", id));
        }

        // then
        assertThat(ids).allSatisfy(id -> assertThat(adr).contains(id));
        assertThat(adr).contains("superseded by [ADR 0082]").contains("## Pros and Cons of the Options");
    }
}
