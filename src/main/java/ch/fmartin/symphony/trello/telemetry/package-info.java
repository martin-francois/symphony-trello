/// Optional installation telemetry: one daily `installation_heartbeat` event per installation,
/// delivered directly to a dedicated PostHog EU project. The package owns the allowlisted event
/// contract, local state and coordination, platform normalization, transport, scheduling, and the
/// text shown by the `telemetry` CLI commands. It must not depend on the setup or orchestrator
/// packages; those wire it in.
@NullMarked
package ch.fmartin.symphony.trello.telemetry;

import org.jspecify.annotations.NullMarked;
