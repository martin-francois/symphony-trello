package ch.fmartin.symphony.trello.telemetry;

import ch.fmartin.symphony.trello.telemetry.TelemetryStateStore.StateRead;
import ch.fmartin.symphony.trello.telemetry.TelemetryStateStore.Update;
import java.io.IOException;
import java.io.PrintStream;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/// Behavior behind the `symphony-trello telemetry` commands and the setup-time hooks. Commands
/// print through the given streams; the interactive disable review reads one line at a time from
/// the caller's reader and never holds the state lock while waiting.
public final class TelemetryService {
    public static final int EXIT_OK = 0;
    public static final int EXIT_FAILURE = 1;
    private static final String NOT_INSTALLED_LINE =
            "This run is not inside an installed Symphony for Trello context, so reports are never sent.";
    private static final String NOT_CONFIGURED_LINE =
            "No project token is configured in this build, so reports are never sent.";
    private static final String PREVIEW_NOT_SENT_LINE = "This preview is not sent.";
    private static final String IDENTITY_PLACEHOLDER_LINE =
            "distinct_id and registered_on are null until the first eligible worker registers this installation.";
    private static final String REMAINS_ENABLED_LINE = "Telemetry remains enabled.";
    private static final String THANKS_LINE = "Thanks for helping improve symphony-trello!";
    private static final String DISABLED_LINE = "Telemetry disabled. The orchestra will have to play this one by ear.";
    private static final String FEATURES_LINE = "All features remain available.";
    private static final String PROMPT = "Disable telemetry? [yes/No/privacy] ";
    private static final String ENTER_HINT = "Enter keeps telemetry enabled.";

    private final TelemetryInstallation installation;
    private final HeartbeatSnapshots snapshots;
    private final Clock clock;

    public TelemetryService(TelemetryInstallation installation, HeartbeatSnapshots snapshots, Clock clock) {
        this.installation = installation;
        this.snapshots = snapshots;
        this.clock = clock;
    }

    public TelemetryInstallation installation() {
        return installation;
    }

    public int status(PrintStream out) {
        Optional<TelemetryStateStore> store = installation.store();
        StateRead read = readState();
        TelemetryState state = read.stateOrInitial();
        EffectiveTelemetry effective = installation.effective(state.mode());
        Instant now = clock.instant();
        out.println("Telemetry status");
        out.println("  Stored mode: " + state.mode().displayName()
                + (read.status() == StateRead.Status.ABSENT ? " (default, no preference stored yet)" : ""));
        out.println("  Effective mode: " + effective.effective().displayName()
                + effective.overrideReason().map(reason -> " (" + reason + ")").orElse(""));
        if (installation.environment().log()) {
            out.println("  Request logging: on (" + TelemetryEnvironment.LOG_VARIABLE + "=1, output only)");
        }
        out.println(
                "  Installation ID: " + state.installation().map(UUID::toString).orElse("not registered yet"));
        out.println("  Analytics ID: " + state.analyticsId().orElse("not registered yet"));
        TelemetryOwnership.Erasure erasure = state.erasure();
        if (erasure != null) {
            out.println("  Erasure: " + erasure.phase());
        }
        out.println("  Registered on: "
                + state.registration().map(LocalDate::toString).orElse("-"));
        out.println("  Installed context: " + (installation.installed() ? "yes" : "no (development or test run)"));
        out.println("  Destination: " + installation.distribution().endpoint() + " ("
                + installation
                        .distribution()
                        .problem()
                        .orElse(
                                installation.distribution().ready()
                                        ? "project token configured"
                                        : "no project token, never sends")
                + ")");
        out.println("  First report allowed: "
                + state.firstWorkerDeadlineAt()
                        .map(deadline -> deadline.isAfter(now) ? "after " + deadline : "yes, since " + deadline)
                        .orElse(TelemetryNotice.FIRST_REPORT_GRACE.toMinutes()
                                + " minutes after the first worker starts"));
        out.println("  Last accepted report: "
                + state.lastReported().map(LocalDate::toString).orElse("none"));
        out.println("  Pending report: "
                + state.pending()
                        .map(pending -> "yes, built " + pending.timestamp()
                                + state.retrySchedule()
                                        .map(retry -> ", retry " + retry.attempts() + " not before " + retry.notBefore()
                                                + " (" + retry.reason() + ")")
                                        .orElse(""))
                        .orElse("none"));
        if (state.pending().isEmpty()) {
            state.retrySchedule()
                    .ifPresent(retry -> out.println("  Next attempt not before: " + retry.notBefore()
                            + " (last attempt: " + retry.reason() + ")"));
        }
        out.println("  Counters: imports " + state.boardImportsTotal() + ", creations " + state.boardCreationsTotal());
        out.println("  State file: "
                + store.map(TelemetryStateStore::stateFile)
                        .map(Object::toString)
                        .orElse("-")
                + read.problem()
                        .map(problem -> " (unreadable: " + problem + ")")
                        .orElse(""));
        if (!installation.installed()) {
            out.println(NOT_INSTALLED_LINE);
        } else if (!installation.distribution().ready()) {
            out.println(NOT_CONFIGURED_LINE);
        }
        return EXIT_OK;
    }

    public int preview(PrintStream out) {
        StateRead read = readState();
        TelemetryState state = read.stateOrInitial();
        printPreview(out, state, read);
        return EXIT_OK;
    }

    public int privacy(PrintStream out) {
        out.print(TelemetryPrivacyText.load());
        out.flush();
        return EXIT_OK;
    }

    public int enable(PrintStream out, PrintStream err) {
        return installation
                .installedStore()
                .map(store -> enable(store, out, err))
                .orElseGet(() -> nothingToChange(out));
    }

    private int enable(TelemetryStateStore store, PrintStream out, PrintStream err) {
        if (installation.environment().disabled()) {
            err.println("Telemetry cannot be enabled while " + TelemetryEnvironment.DISABLED_VARIABLE
                    + " is set for this process. Unset it and run the command again.");
            return EXIT_FAILURE;
        }
        try {
            boolean changed = store.update(state -> {
                if (state.mode() == TelemetryMode.ENABLED) {
                    return Update.unchanged(false);
                }
                Instant now = clock.instant();
                TelemetryState enabled = state.withMode(TelemetryMode.ENABLED)
                        .withNotice(TelemetryNotice.REVISION, now)
                        // Explicit enabling is an affirmative action; no further grace period applies.
                        .withFirstWorkerDeadline(now);
                TelemetryOwnership ownership = state.ownership();
                if (ownership != null) {
                    TelemetryOwnership.Erasure erasure = ownership.erasure();
                    if (erasure != null && erasure.phase() != TelemetryOwnership.Phase.COMPLETE) {
                        throw new TelemetryStateException(
                                "erasure is still pending or refused; check `symphony-trello telemetry erase-status`");
                    }
                    enabled = enabled.withOwnership(ownership.resume());
                }
                // Register only where a report can ever leave; an unconfigured build keeps no identity.
                return Update.write(installation.networkEligible() ? registered(enabled) : enabled, true);
            });
            if (!changed) {
                out.println("Telemetry is already enabled.");
            } else {
                TelemetryNotice.lines(readState().stateOrInitial().firstWorkerDeadlineAt(), clock.instant())
                        .forEach(out::println);
                out.println();
                out.println(
                        "Telemetry enabled. The next report is sent by a running worker; this command sends nothing.");
            }
            printEligibilityNotes(out);
            return EXIT_OK;
        } catch (TelemetryStateException exception) {
            err.println("Telemetry could not be enabled: " + exception.getMessage());
            return EXIT_FAILURE;
        }
    }

    public int disable(DisableRequest request, PrintStream out, PrintStream err) {
        return installation
                .installedStore()
                .map(store -> disable(store, request, out, err))
                .orElseGet(() -> nothingToChange(out));
    }

    private int disable(TelemetryStateStore store, DisableRequest request, PrintStream out, PrintStream err) {
        StateRead read = store.read();
        TelemetryState state = read.stateOrInitial();
        if (read.unreadable()) {
            err.println("Telemetry state is unreadable: " + read.problem().orElse("") + ". Reporting is already off.");
            return EXIT_FAILURE;
        }
        if (state.mode() == TelemetryMode.DISABLED) {
            out.println("Telemetry is already disabled.");
            printEligibilityNotes(out);
            return EXIT_OK;
        }
        if (!request.yes() && request.interactive()) {
            Review answer = review(request, out, state, read);
            if (answer != Review.DISABLE) {
                // The stored preference may have changed while the question was open; report what
                // is true now instead of the value the dialog started with.
                TelemetryState current = store.read().stateOrInitial();
                EffectiveTelemetry effective = installation.effective(current.mode());
                out.println(
                        answer == Review.CANCELLED ? "Cancelled. " + outcomeLine(effective) : outcomeLine(effective));
                if (answer == Review.KEEP && effective.sendsReports()) {
                    out.println(THANKS_LINE);
                }
                return EXIT_OK;
            }
        }
        return persistDisabled(store, out, err);
    }

    public int erase(PrintStream out, PrintStream err) {
        return erasureCommand(true, out, err);
    }

    public int erasureStatus(PrintStream out, PrintStream err) {
        return erasureCommand(false, out, err);
    }

    private int erasureCommand(boolean request, PrintStream out, PrintStream err) {
        try {
            if (!installation.networkEligible()
                    || installation.distribution().erasure().isEmpty()) {
                if (request) {
                    installation
                            .installedStore()
                            .ifPresent(store -> store.update(state -> state.mode() == TelemetryMode.DISABLED
                                    ? Update.unchanged(null)
                                    : Update.write(state.withMode(TelemetryMode.DISABLED), null)));
                }
                err.println(
                        "Automatic erasure is not configured for this installation; use maintainer-assisted erasure.");
                return EXIT_FAILURE;
            }
            TelemetryStateStore store = installation.installedStore().orElseThrow();
            TelemetryErasure service = new TelemetryErasure(installation, clock);
            if (request) {
                if (service.request(store) == TelemetryErasure.Request.NOTHING_SENT) {
                    out.println(
                            "Reporting is off. This installation never sent a report, so there is nothing to erase.");
                    return EXIT_OK;
                }
            } else {
                service.refresh(store);
            }
            TelemetryOwnership.Erasure erasure =
                    TelemetryErasure.readable(store).erasure();
            if (erasure == null) {
                out.println("No erasure has been requested.");
                return EXIT_OK;
            }
            String message =
                    switch (erasure.phase()) {
                        case REQUESTED ->
                            "Erasure is not yet confirmed as accepted. Reporting is off. Retry with `symphony-trello telemetry erase-status`; running workers also retry.";
                        case ACCEPTED ->
                            "Erasure accepted. PostHog continues deletion after this command exits. Physical deletion is still pending.";
                        case COMPLETE ->
                            "Erasure complete. Reporting remains off until you run `symphony-trello telemetry enable`.";
                        case REFUSED ->
                            "Automatic erasure refused because the provider identity is inconsistent. Reporting is off; contact the maintainer.";
                    };
            out.println(message);
            return erasure.phase() == TelemetryOwnership.Phase.REFUSED ? EXIT_FAILURE : EXIT_OK;
        } catch (TelemetryStateException exception) {
            err.println("Erasure could not be confirmed: " + exception.getMessage());
            return EXIT_FAILURE;
        }
    }

    public int debug(PrintStream out, PrintStream err) {
        return installation
                .installedStore()
                .map(store -> debug(store, out, err))
                .orElseGet(() -> nothingToChange(out));
    }

    private int debug(TelemetryStateStore store, PrintStream out, PrintStream err) {
        try {
            // Debug never overrides a stored disable: a disabled installation stays disabled until an
            // explicit enable, so a preference stored before an erasure cannot be undone by accident.
            boolean disabled = store.update(current -> switch (current.mode()) {
                case DISABLED -> Update.unchanged(true);
                case DEBUG -> Update.unchanged(false);
                case ENABLED -> Update.write(current.withMode(TelemetryMode.DEBUG), false);
            });
            if (disabled) {
                err.println("Telemetry is disabled, and debug mode does not override that. Run"
                        + " `symphony-trello telemetry enable` first if you want local-only debug output.");
                return EXIT_FAILURE;
            }
            StateRead read = readState();
            out.println("Telemetry switched to local-only debug mode for this installation.");
            out.println("Workers print each report they would send and send nothing. Re-enable with"
                    + " `symphony-trello telemetry enable`.");
            out.println();
            printPreview(out, read.stateOrInitial(), read);
            if (installation.environment().disabled()) {
                out.println(TelemetryEnvironment.DISABLED_VARIABLE
                        + " is set for this process, so it also collects nothing until unset.");
            }
            return EXIT_OK;
        } catch (TelemetryStateException exception) {
            err.println("Telemetry debug mode could not be stored: " + exception.getMessage());
            return EXIT_FAILURE;
        }
    }

    /// Setup-time hook: before a board operation, create the identity and print the notice once.
    /// Nothing happens outside an installed, token-configured, enabled context.
    public void prepareForSetup(PrintStream out) {
        if (!installation.networkEligible()) {
            return;
        }
        installation.installedStore().ifPresent(store -> prepareForSetup(store, out));
    }

    private void prepareForSetup(TelemetryStateStore store, PrintStream out) {
        try {
            Optional<List<String>> notice = store.update(state -> {
                if (!installation.effective(state.mode()).sendsReports()) {
                    return Update.unchanged(Optional.empty());
                }
                TelemetryState next = registered(state);
                if (next.noticeRevision() >= TelemetryNotice.REVISION) {
                    return Update.write(next, Optional.empty());
                }
                Instant now = clock.instant();
                next = next.withNotice(TelemetryNotice.REVISION, now);
                return Update.write(next, Optional.of(TelemetryNotice.lines(next.firstWorkerDeadlineAt(), now)));
            });
            notice.ifPresent(lines -> {
                out.println();
                lines.forEach(out::println);
            });
        } catch (TelemetryStateException exception) {
            // A broken telemetry file must not block setup; say so and let the operation continue.
            out.println("Usage reporting is unavailable: " + exception.getMessage());
        }
    }

    /// Records one successful, newly registered board operation. Failures are swallowed: telemetry
    /// bookkeeping must never fail the setup that succeeded.
    public boolean recordSuccessfulOperation(BoardOperation operation) {
        return installation
                .installedStore()
                .map(store -> recordSuccessfulOperation(store, operation))
                .orElse(false);
    }

    private boolean recordSuccessfulOperation(TelemetryStateStore store, BoardOperation operation) {
        try {
            return store.update(state -> {
                if (!installation.effective(state.mode()).collectsCounters()) {
                    return Update.unchanged(false);
                }
                TelemetryState counted = operation == BoardOperation.IMPORT
                        ? state.withCounters(state.boardImportsTotal() + 1, state.boardCreationsTotal())
                        : state.withCounters(state.boardImportsTotal(), state.boardCreationsTotal() + 1);
                return Update.write(counted, true);
            });
        } catch (TelemetryStateException exception) {
            return false;
        }
    }

    private static int nothingToChange(PrintStream out) {
        out.println(NOT_INSTALLED_LINE + " Nothing to change.");
        return EXIT_OK;
    }

    private TelemetryState registered(TelemetryState state) {
        if (state.hasIdentity() || installation.distribution().erasure().isPresent()) {
            return state;
        }
        return state.withIdentity(UUID.randomUUID(), LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC));
    }

    private Review review(DisableRequest request, PrintStream out, TelemetryState state, StateRead read) {
        out.println("Telemetry helps " + TelemetryNotice.MAINTAINER + " improve symphony-trello.");
        out.println("Reports are sent through PostHog EU, normally once per day while running.");
        out.println();
        out.println("This is the complete JSON body of a report generated now.");
        out.println(PREVIEW_NOT_SENT_LINE);
        out.println();
        printPreviewJson(out, state, read);
        out.println();
        out.println(TelemetryNotice.MAINTAINER + " uses these reports only to improve symphony-trello.");
        out.println("Privacy covers provider processing, network metadata, retention and deletion.");
        out.println();
        while (true) {
            out.print(PROMPT);
            out.println();
            out.println(ENTER_HINT);
            out.flush();
            String line;
            try {
                line = request.input().get();
            } catch (RuntimeException exception) {
                return Review.CANCELLED;
            }
            if (line == null) {
                return Review.CANCELLED;
            }
            switch (line.strip().toLowerCase(Locale.ROOT)) {
                case "", "n", "no" -> {
                    return Review.KEEP;
                }
                case "y", "yes" -> {
                    return Review.DISABLE;
                }
                case "p", "privacy" -> {
                    // Privacy is navigation inside the review: show the details, then ask again.
                    out.println();
                    privacy(out);
                    out.println();
                }
                default -> out.println("Please answer yes, no, or privacy.");
            }
        }
    }

    private int persistDisabled(TelemetryStateStore store, PrintStream out, PrintStream err) {
        try {
            TelemetryMode previous = store.update(state -> state.mode() == TelemetryMode.DISABLED
                    ? Update.unchanged(state.mode())
                    : Update.write(state.withMode(TelemetryMode.DISABLED), state.mode()));
            if (previous == TelemetryMode.DISABLED) {
                out.println("Telemetry is already disabled.");
                return EXIT_OK;
            }
            out.println(DISABLED_LINE);
            out.println(FEATURES_LINE);
            if (installation.environment().disabled()) {
                out.println(TelemetryEnvironment.DISABLED_VARIABLE
                        + " already covered processes that inherit it; the stored preference now covers every worker.");
            }
            return EXIT_OK;
        } catch (TelemetryStateException exception) {
            err.println("Telemetry could not be disabled: " + exception.getMessage());
            err.println("The previous preference is unchanged. Set " + TelemetryEnvironment.DISABLED_VARIABLE
                    + "=1 for workers you start until the state directory is writable again.");
            return EXIT_FAILURE;
        }
    }

    private void printPreview(PrintStream out, TelemetryState state, StateRead read) {
        printPreviewJson(out, state, read);
        out.println();
        out.println(PREVIEW_NOT_SENT_LINE);
        if (!state.hasIdentity()) {
            out.println(IDENTITY_PLACEHOLDER_LINE);
        }
        printEligibilityNotes(out);
    }

    private void printPreviewJson(PrintStream out, TelemetryState state, StateRead read) {
        read.problem()
                .ifPresent(problem -> out.println(
                        "Note: stored telemetry state is unreadable (" + problem + "); this preview uses defaults."));
        out.println(HeartbeatJson.serialize(snapshots.preview(state, clock.instant())));
    }

    private void printEligibilityNotes(PrintStream out) {
        if (!installation.installed()) {
            out.println(NOT_INSTALLED_LINE);
        } else if (!installation.distribution().ready()) {
            out.println(NOT_CONFIGURED_LINE);
        }
    }

    private StateRead readState() {
        return installation.store().map(TelemetryStateStore::read).orElseGet(StateRead::absent);
    }

    /// The truthful state after a review that changed nothing, including overrides and concurrent
    /// changes: what the user keeps is what applies now, not what the dialog started with.
    private static String outcomeLine(EffectiveTelemetry effective) {
        return switch (effective.effective()) {
            case ENABLED -> REMAINS_ENABLED_LINE;
            case DEBUG -> "Telemetry remains in local-only debug mode.";
            case DISABLED ->
                effective
                        .overrideReason()
                        .map(reason -> "Telemetry is disabled for this process by " + reason + ".")
                        .orElse("Telemetry is disabled.");
        };
    }

    private enum Review {
        KEEP,
        CANCELLED,
        DISABLE
    }

    /// `yes` skips the review; `interactive` says a human terminal is attached; `input` reads one
    /// answer line and returns `null` at end of input.
    public record DisableRequest(boolean yes, boolean interactive, Supplier<@Nullable String> input) {
        public static DisableRequest nonInteractive() {
            return new DisableRequest(false, false, () -> null);
        }
    }

    /// Adapts a checked line reader to the review's answer supplier.
    @FunctionalInterface
    public interface LineSource {
        @Nullable
        String readLine() throws IOException;
    }

    public static Supplier<@Nullable String> lines(LineSource source) {
        return () -> {
            try {
                return source.readLine();
            } catch (IOException exception) {
                return null;
            }
        };
    }
}
