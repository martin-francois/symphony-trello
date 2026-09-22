package ch.fmartin.symphony.trello.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import ch.fmartin.symphony.trello.telemetry.TelemetryFixture.MutableClock;
import ch.fmartin.symphony.trello.telemetry.TelemetryService.DisableRequest;
import ch.fmartin.symphony.trello.telemetry.TelemetryStateStore.StateRead;
import ch.fmartin.symphony.trello.telemetry.TelemetryStateStore.Update;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class TelemetryServiceTest {
    @TempDir
    Path tempDir;

    private Path stateDir;
    private TelemetryStateStore store;
    private final MutableClock clock = new MutableClock(TelemetryFixture.NOON);
    private final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
    private final ByteArrayOutputStream stderr = new ByteArrayOutputStream();
    private final PrintStream out = new PrintStream(stdout, true, StandardCharsets.UTF_8);
    private final PrintStream err = new PrintStream(stderr, true, StandardCharsets.UTF_8);

    @BeforeEach
    void setUp() throws IOException {
        stateDir = TelemetryFixture.installedStateDir(tempDir);
        store = new TelemetryStateStore(stateDir);
    }

    @Test
    void previewPrintsTheFullJsonWithoutWritingAnythingBeforeRegistration() {
        // given
        TelemetryService service = service(TelemetryEnvironment.none());

        // when
        int exit = service.preview(out);

        // then
        assertThat(exit).isZero();
        assertThat(output())
                .contains("\"event\" : \"installation_heartbeat\"")
                .contains("\"distinct_id\" : null")
                .contains("\"registered_on\" : null")
                .contains("\"app_version\" : \"1.2.0\"")
                .contains("\"connected_board_count\" : 3")
                .contains("This preview is not sent.")
                .contains("distinct_id and registered_on are null until");
        assertThat(store.read().status()).isEqualTo(StateRead.Status.ABSENT);
    }

    @Test
    void previewAfterAReportShowsTheRealIdentityAndChangesNoSchedule() {
        // given
        UUID id = UUID.randomUUID();
        store.update(state -> Update.write(
                state.withIdentity(id, LocalDate.of(2026, 9, 20))
                        .withFirstWorkerDeadline(TelemetryFixture.NOON.minus(Duration.ofDays(1)))
                        .withReporting(LocalDate.of(2026, 9, 21), null, null, null),
                null));
        TelemetryState before = store.read().stateOrInitial();
        TelemetryService service = service(TelemetryEnvironment.none());

        // when
        service.preview(out);

        // then
        assertThat(output())
                .contains("\"distinct_id\" : \"" + id + "\"")
                .contains("\"registered_on\" : \"2026-09-20\"");
        assertThat(output()).doesNotContain("until the first eligible worker");
        assertThat(store.read().stateOrInitial()).isEqualTo(before);
    }

    @Test
    void statusReportsStoredAndEffectiveModeWithTheOverrideReason() {
        // given
        store.update(state -> Update.write(state.withMode(TelemetryMode.DEBUG), null));
        TelemetryService service = service(new TelemetryEnvironment(true, false, true));

        // when
        int exit = service.status(out);

        // then
        assertThat(exit).isZero();
        assertThat(output())
                .contains("Stored mode: debug")
                .contains("Effective mode: disabled (SYMPHONY_TRELLO_TELEMETRY_DISABLED=1)")
                .contains("Request logging: on")
                .contains("Installation ID: not registered yet")
                .contains("Installed context: yes")
                .contains("project token configured")
                .contains("Last accepted report: none")
                .contains("State file: " + store.stateFile());
    }

    @Test
    void statusExplainsWhyADevelopmentRunOrUnconfiguredBuildNeverSends() throws IOException {
        // given
        Path developmentDir = Files.createDirectories(tempDir.resolve("dev"));
        TelemetryService development = new TelemetryService(
                TelemetryFixture.installation(developmentDir),
                TelemetryFixture.snapshots(TelemetryFixture.installation(developmentDir)),
                clock);
        TelemetryInstallation unconfigured = TelemetryFixture.installation(
                stateDir, TelemetryEnvironment.none(), TelemetryFixture.unconfiguredDistribution());
        TelemetryService noToken = new TelemetryService(unconfigured, TelemetryFixture.snapshots(unconfigured), clock);

        // when
        development.status(out);
        String developmentOutput = output();
        stdout.reset();
        noToken.status(out);

        // then
        assertThat(developmentOutput).contains("Installed context: no").contains("reports are never sent");
        assertThat(output()).contains("no project token, never sends").contains("No project token is configured");
    }

    @Test
    void privacyPrintsTheBundledTextOffline() {
        // given
        TelemetryService service = service(TelemetryEnvironment.none());

        // when
        int exit = service.privacy(out);

        // then
        assertThat(exit).isZero();
        assertThat(output()).startsWith("# Usage reporting privacy").contains("installation_heartbeat");
    }

    @ParameterizedTest(name = "answer {0} keeps telemetry enabled")
    @ValueSource(strings = {"", "n", "N", "no", "No", "NO", "  no  "})
    void interactiveDisableDefaultsToNoAndWritesNothing(String answer) {
        // given
        TelemetryService service = service(TelemetryEnvironment.none());

        // when
        int exit = service.disable(interactive(answer), out, err);

        // then
        assertThat(exit).isZero();
        assertThat(output())
                .contains("Telemetry helps François Martin improve symphony-trello.")
                .contains("This is the complete JSON body of a report generated now.")
                .contains("\"event\" : \"installation_heartbeat\"")
                .contains("Disable telemetry? [yes/No/privacy]")
                .contains("Enter keeps telemetry enabled.")
                .endsWith("Telemetry remains enabled.\nThanks for helping improve symphony-trello!\n");
        assertThat(store.read().status()).isEqualTo(StateRead.Status.ABSENT);
    }

    @ParameterizedTest(name = "answer {0} disables")
    @ValueSource(strings = {"y", "Y", "yes", "YES", " Yes "})
    void interactiveDisablePersistsOnlyAfterAffirmativeConfirmation(String answer) {
        // given
        TelemetryService service = service(TelemetryEnvironment.none());

        // when
        int exit = service.disable(interactive(answer), out, err);

        // then
        assertThat(exit).isZero();
        assertThat(output())
                .endsWith("Telemetry disabled. The orchestra will have to play this one by ear.\n"
                        + "All features remain available.\n");
        assertThat(store.read().stateOrInitial().mode()).isEqualTo(TelemetryMode.DISABLED);
        assertThat(store.read().stateOrInitial().preferenceRevision()).isEqualTo(1);
    }

    @Test
    void invalidAnswersRepeatTheQuestionUntilAValidOne() {
        // given
        TelemetryService service = service(TelemetryEnvironment.none());

        // when
        service.disable(interactive("maybe", "sure", "no"), out, err);

        // then
        assertThat(output())
                .containsSubsequence(
                        "Disable telemetry? [yes/No/privacy]",
                        "Please answer yes, no, or privacy.",
                        "Disable telemetry? [yes/No/privacy]",
                        "Please answer yes, no, or privacy.",
                        "Disable telemetry? [yes/No/privacy]",
                        "Telemetry remains enabled.");
        assertThat(store.read().status()).isEqualTo(StateRead.Status.ABSENT);
    }

    @ParameterizedTest(name = "answer {0} shows privacy and asks again")
    @ValueSource(strings = {"p", "privacy", "P"})
    void privacyAnswerShowsTheTextAndReturnsToTheQuestion(String answer) {
        // given
        TelemetryService keeps = service(TelemetryEnvironment.none());
        TelemetryService disables = service(TelemetryEnvironment.none());

        // when
        int keepExit = keeps.disable(interactive(answer, "no"), out, err);
        String keepOutput = output();
        stdout.reset();
        int disableExit = disables.disable(interactive(answer, answer, "yes"), out, err);

        // then
        assertThat(keepExit).isZero();
        assertThat(keepOutput)
                .containsSubsequence(
                        "Disable telemetry? [yes/No/privacy]",
                        "# Usage reporting privacy",
                        "Disable telemetry? [yes/No/privacy]",
                        "Telemetry remains enabled.")
                .endsWith("Telemetry remains enabled.\nThanks for helping improve symphony-trello!\n");
        assertThat(disableExit).isZero();
        assertThat(output()).contains("Telemetry disabled.");
        assertThat(store.read().stateOrInitial().mode()).isEqualTo(TelemetryMode.DISABLED);
    }

    @Test
    void endOfInputCancelsWithoutAWrite() {
        // given
        TelemetryService service = service(TelemetryEnvironment.none());

        // when
        int exit = service.disable(new DisableRequest(false, true, () -> null), out, err);

        // then
        assertThat(exit).isZero();
        assertThat(output()).endsWith("Cancelled. Telemetry remains enabled.\n");
        assertThat(store.read().status()).isEqualTo(StateRead.Status.ABSENT);
    }

    @Test
    void reviewOutcomeReportsAConcurrentDisableTruthfully() {
        // given
        TelemetryService service = service(TelemetryEnvironment.none());
        DisableRequest request = new DisableRequest(false, true, () -> {
            store.update(state -> Update.write(state.withMode(TelemetryMode.DISABLED), null));
            return "";
        });

        // when
        int exit = service.disable(request, out, err);

        // then
        assertThat(exit).isZero();
        assertThat(output()).endsWith("Telemetry is disabled.\n").doesNotContain("Thanks for helping");
    }

    @Test
    void reviewOutcomeNamesAnEnvironmentDisableInsteadOfThankingForData() {
        // given
        TelemetryService service = service(new TelemetryEnvironment(true, false, false));
        store.update(state -> Update.write(state.withMode(TelemetryMode.ENABLED), null));

        // when
        service.disable(interactive("no"), out, err);

        // then
        assertThat(output())
                .endsWith("Telemetry is disabled for this process by SYMPHONY_TRELLO_TELEMETRY_DISABLED=1.\n")
                .doesNotContain("Thanks for helping");
    }

    @Test
    void cancellationDoesNotOverwriteAConcurrentChangeMadeDuringTheDialog() {
        // given
        TelemetryService service = service(TelemetryEnvironment.none());
        DisableRequest request = new DisableRequest(false, true, () -> {
            // Another command changes the preference while the question is open.
            store.update(
                    state -> Update.write(state.withMode(TelemetryMode.DEBUG).withCounters(7, 0), null));
            return "no";
        });

        // when
        service.disable(request, out, err);
        TelemetryState state = store.read().stateOrInitial();

        // then
        assertThat(state.mode()).isEqualTo(TelemetryMode.DEBUG);
        assertThat(state.boardImportsTotal()).isEqualTo(7);
    }

    @Test
    void confirmationRereadsTheLatestStateUnderLock() {
        // given
        TelemetryService service = service(TelemetryEnvironment.none());
        DisableRequest request = new DisableRequest(false, true, () -> {
            store.update(state -> Update.write(state.withCounters(5, 2), null));
            return "yes";
        });

        // when
        service.disable(request, out, err);
        TelemetryState state = store.read().stateOrInitial();

        // then
        assertThat(state.mode()).isEqualTo(TelemetryMode.DISABLED);
        assertThat(state.boardImportsTotal())
                .as("counters written during the dialog survive")
                .isEqualTo(5);
    }

    @Test
    void nonInteractiveAndYesDisableDirectlyWithoutTheReview() {
        // given
        TelemetryService piped = service(TelemetryEnvironment.none());
        TelemetryService withYes = service(TelemetryEnvironment.none());

        // when
        int pipedExit = piped.disable(DisableRequest.nonInteractive(), out, err);
        String pipedOutput = output();
        store.update(state -> Update.write(state.withMode(TelemetryMode.ENABLED), null));
        stdout.reset();
        int yesExit = withYes.disable(new DisableRequest(true, true, () -> "no"), out, err);

        // then
        assertThat(pipedExit).isZero();
        assertThat(pipedOutput).doesNotContain("Disable telemetry?").contains("Telemetry disabled.");
        assertThat(yesExit).isZero();
        assertThat(output()).doesNotContain("Disable telemetry?").contains("Telemetry disabled.");
        assertThat(store.read().stateOrInitial().mode()).isEqualTo(TelemetryMode.DISABLED);
    }

    @Test
    void alreadyDisabledSucceedsWithoutAnotherReview() {
        // given
        store.update(state -> Update.write(state.withMode(TelemetryMode.DISABLED), null));
        TelemetryService service = service(TelemetryEnvironment.none());

        // when
        int exit = service.disable(interactive("yes"), out, err);

        // then
        assertThat(exit).isZero();
        assertThat(output()).isEqualTo("Telemetry is already disabled.\n");
        assertThat(store.read().stateOrInitial().preferenceRevision()).isEqualTo(1);
    }

    @Test
    void disableDiscardsPendingWorkAndBumpsTheRevision() {
        // given
        UUID pendingUuid = UUID.randomUUID();
        store.update(state -> Update.write(state.withIdentity(UUID.randomUUID(), LocalDate.of(2026, 9, 22)), null));
        HeartbeatProperties properties = new HeartbeatProperties(
                1, "2026-09-22", "1.2.0", "linux", "24.04", "ubuntu", "x64", 3, 0, 0, true, true);
        store.update(state -> Update.write(
                state.withReporting(
                        null,
                        new TelemetryState.RetryState(1, TelemetryFixture.NOON, "HTTP 503"),
                        new TelemetryState.PendingHeartbeat(
                                pendingUuid, TelemetryFixture.NOON, LocalDate.of(2026, 9, 22), 0, properties),
                        new TelemetryState.ReportClaim(
                                "w", pendingUuid, UUID.randomUUID(), TelemetryFixture.NOON.plusSeconds(60))),
                null));
        TelemetryService service = service(TelemetryEnvironment.none());

        // when
        service.disable(DisableRequest.nonInteractive(), out, err);
        TelemetryState state = store.read().stateOrInitial();

        // then
        assertThat(state.pendingReport())
                .as("the report discarded by the disable is not replayed")
                .isNull();
        assertThat(state.claim()).isNull();
        assertThat(state.retry()).isNull();
        assertThat(state.preferenceRevision()).isEqualTo(1);
    }

    @Test
    void failedPreferenceWriteReportsAnErrorAndNeverPrintsSuccess() throws IOException {
        // given
        Files.writeString(store.stateFile(), "{corrupt");
        TelemetryService service = service(TelemetryEnvironment.none());

        // when
        int disableExit = service.disable(DisableRequest.nonInteractive(), out, err);
        int enableExit = service.enable(out, err);

        // then
        assertThat(disableExit).isEqualTo(TelemetryService.EXIT_FAILURE);
        assertThat(enableExit).isEqualTo(TelemetryService.EXIT_FAILURE);
        assertThat(output()).doesNotContain("Telemetry disabled").doesNotContain("Telemetry enabled");
        assertThat(errors()).contains("unreadable").contains("could not be enabled");
    }

    @Test
    void messagesStayTruthfulUnderAnEnvironmentDisableOverride() {
        // given
        TelemetryService service = service(new TelemetryEnvironment(true, false, false));

        // when
        int disableExit = service.disable(DisableRequest.nonInteractive(), out, err);
        int enableExit = service.enable(out, err);

        // then
        assertThat(disableExit).isZero();
        assertThat(output())
                .contains("Telemetry disabled.")
                .contains("SYMPHONY_TRELLO_TELEMETRY_DISABLED already covered");
        assertThat(enableExit).isEqualTo(TelemetryService.EXIT_FAILURE);
        assertThat(errors()).contains("cannot be enabled while SYMPHONY_TRELLO_TELEMETRY_DISABLED");
        assertThat(store.read().stateOrInitial().mode()).isEqualTo(TelemetryMode.DISABLED);
    }

    @Test
    void enableAfterDisableKeepsIdentityAndCountersAndRemovesTheGracePeriod() {
        // given
        UUID id = UUID.randomUUID();
        HeartbeatProperties properties = new HeartbeatProperties(
                1, "2026-09-21", "1.2.0", "linux", "24.04", "ubuntu", "x64", 3, 4, 2, true, true);
        store.update(state -> Update.write(
                state.withIdentity(id, LocalDate.of(2026, 9, 1))
                        .withCounters(4, 2)
                        .withReporting(
                                LocalDate.of(2026, 9, 20),
                                null,
                                new TelemetryState.PendingHeartbeat(
                                        UUID.randomUUID(),
                                        TelemetryFixture.NOON,
                                        LocalDate.of(2026, 9, 21),
                                        0,
                                        properties),
                                null)
                        .withMode(TelemetryMode.DISABLED),
                null));
        TelemetryService service = service(TelemetryEnvironment.none());

        // when
        int exit = service.enable(out, err);
        TelemetryState state = store.read().stateOrInitial();

        // then
        assertThat(exit).isZero();
        assertThat(output()).contains("Optional usage reporting is enabled.").contains("this command sends nothing");
        assertThat(state.mode()).isEqualTo(TelemetryMode.ENABLED);
        assertThat(state.installationId()).isEqualTo(id);
        assertThat(state.registeredOn()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(state.boardImportsTotal()).isEqualTo(4);
        assertThat(state.boardCreationsTotal()).isEqualTo(2);
        assertThat(state.lastReportedDate())
                .as("nothing is backfilled or replayed")
                .isEqualTo(LocalDate.of(2026, 9, 20));
        assertThat(state.pendingReport()).isNull();
        assertThat(state.firstWorkerDeadline()).isEqualTo(TelemetryFixture.NOON);
        assertThat(state.preferenceRevision()).isEqualTo(2);
    }

    @Test
    void enableWithoutAConfiguredTokenChangesTheModeButCreatesNoIdentity() {
        // given
        store.update(state -> Update.write(state.withMode(TelemetryMode.DISABLED), null));
        TelemetryInstallation unconfigured = TelemetryFixture.installation(
                stateDir, TelemetryEnvironment.none(), TelemetryFixture.unconfiguredDistribution());
        TelemetryService service = new TelemetryService(unconfigured, TelemetryFixture.snapshots(unconfigured), clock);

        // when
        int exit = service.enable(out, err);
        TelemetryState state = store.read().stateOrInitial();

        // then
        assertThat(exit).isZero();
        assertThat(state.mode()).isEqualTo(TelemetryMode.ENABLED);
        assertThat(state.hasIdentity())
                .as("no identity exists where no report can ever leave")
                .isFalse();
        assertThat(output()).contains("No project token is configured");
    }

    @Test
    void debugIsRefusedWhileTheStoredPreferenceIsDisabled() {
        // given
        store.update(state -> Update.write(state.withMode(TelemetryMode.DISABLED), null));
        TelemetryService service = service(TelemetryEnvironment.none());

        // when
        int exit = service.debug(out, err);
        TelemetryState state = store.read().stateOrInitial();

        // then
        assertThat(exit).isEqualTo(TelemetryService.EXIT_FAILURE);
        assertThat(errors()).contains("debug mode does not override that");
        assertThat(state.mode()).isEqualTo(TelemetryMode.DISABLED);
        assertThat(state.preferenceRevision()).isEqualTo(1);
    }

    @Test
    void preferenceCommandsOutsideAnInstallationChangeNothing() throws IOException {
        // given
        Path developmentDir = Files.createDirectories(tempDir.resolve("dev"));
        TelemetryInstallation development = TelemetryFixture.installation(developmentDir);
        TelemetryService service = new TelemetryService(development, TelemetryFixture.snapshots(development), clock);

        // when
        List<Integer> exits = List.of(
                service.enable(out, err),
                service.disable(DisableRequest.nonInteractive(), out, err),
                service.debug(out, err));

        // then
        assertThat(exits).containsOnly(TelemetryService.EXIT_OK);
        assertThat(output()).contains("Nothing to change.");
        assertThat(developmentDir.resolve(TelemetryStateStore.STATE_FILE)).doesNotExist();
    }

    @Test
    void debugStoresLocalOnlyModeAndPrintsAPreviewImmediately() {
        // given
        TelemetryService service = service(TelemetryEnvironment.none());

        // when
        int exit = service.debug(out, err);

        // then
        assertThat(exit).isZero();
        assertThat(output()).contains("local-only debug mode").contains("\"event\" : \"installation_heartbeat\"");
        assertThat(store.read().stateOrInitial().mode()).isEqualTo(TelemetryMode.DEBUG);
    }

    @Test
    void setupPreparationRegistersAndPrintsTheNoticeExactlyOnce() {
        // given
        TelemetryService service = service(TelemetryEnvironment.none());

        // when
        service.prepareForSetup(out);
        String first = output();
        stdout.reset();
        service.prepareForSetup(out);
        TelemetryState state = store.read().stateOrInitial();

        // then
        assertThat(first)
                .contains("Optional usage reporting is enabled. First report 5 minutes after the first worker starts")
                .contains(TelemetryNotice.FIELD_SUMMARY);
        assertThat(output()).isEmpty();
        assertThat(state.hasIdentity())
                .as("setup registers the installation before the first operation")
                .isTrue();
        assertThat(state.firstWorkerDeadline())
                .as("setup must not start the worker countdown")
                .isNull();
    }

    @Test
    void setupPreparationDoesNothingWhenDisabledOrNotEligible() throws IOException {
        // given
        Path developmentDir = Files.createDirectories(tempDir.resolve("dev"));
        TelemetryService disabled = service(new TelemetryEnvironment(true, false, false));
        TelemetryService development = new TelemetryService(
                TelemetryFixture.installation(developmentDir),
                TelemetryFixture.snapshots(TelemetryFixture.installation(developmentDir)),
                clock);

        // when
        disabled.prepareForSetup(out);
        development.prepareForSetup(out);

        // then
        assertThat(output()).isEmpty();
        assertThat(store.read().status()).isEqualTo(StateRead.Status.ABSENT);
        assertThat(Files.exists(developmentDir.resolve(TelemetryStateStore.STATE_FILE)))
                .as("no dev state")
                .isFalse();
    }

    @Test
    void countersAccrueWhenEnabledOrDebugAndFreezeWhenDisabled() {
        // given
        TelemetryService enabled = service(TelemetryEnvironment.none());
        TelemetryService debug = service(new TelemetryEnvironment(false, true, false));
        TelemetryService disabled = service(new TelemetryEnvironment(true, false, false));

        // when
        boolean importCounted = enabled.recordSuccessfulOperation(BoardOperation.IMPORT);
        boolean createCounted = debug.recordSuccessfulOperation(BoardOperation.CREATE);
        boolean frozen = disabled.recordSuccessfulOperation(BoardOperation.IMPORT);
        TelemetryState state = store.read().stateOrInitial();

        // then
        assertThat(importCounted).as("enabled counts").isTrue();
        assertThat(createCounted).as("debug counts locally").isTrue();
        assertThat(frozen).as("disabled freezes").isFalse();
        assertThat(state.boardImportsTotal()).isEqualTo(1);
        assertThat(state.boardCreationsTotal()).isEqualTo(1);
    }

    private TelemetryService service(TelemetryEnvironment environment) {
        TelemetryInstallation installation = TelemetryFixture.installation(stateDir, environment);
        return new TelemetryService(installation, TelemetryFixture.snapshots(installation), clock);
    }

    private static DisableRequest interactive(String... answers) {
        Deque<String> queue = new ArrayDeque<>(Arrays.asList(answers));
        return new DisableRequest(false, true, queue::poll);
    }

    private String output() {
        return stdout.toString(StandardCharsets.UTF_8);
    }

    private String errors() {
        return stderr.toString(StandardCharsets.UTF_8);
    }
}
