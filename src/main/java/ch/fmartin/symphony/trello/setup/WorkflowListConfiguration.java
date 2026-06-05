package ch.fmartin.symphony.trello.setup;

import java.util.List;
import java.util.Optional;

record WorkflowListConfiguration(
        List<String> activeStates,
        List<String> terminalStates,
        Optional<String> inProgressState,
        Optional<String> blockedState,
        boolean activeStatesInvalid,
        boolean terminalStatesInvalid) {
    static WorkflowListConfiguration empty() {
        return new WorkflowListConfiguration(List.of(), List.of(), Optional.empty(), Optional.empty(), false, false);
    }

    WorkflowListConfiguration onlyOpenLists(List<String> openLists) {
        return new WorkflowListConfiguration(
                activeStates.stream()
                        .filter(state -> containsIgnoreCase(openLists, state))
                        .toList(),
                terminalStates.stream()
                        .filter(state -> containsIgnoreCase(openLists, state))
                        .toList(),
                inProgressState.filter(state -> containsIgnoreCase(openLists, state)),
                blockedState.filter(state -> containsIgnoreCase(openLists, state)),
                activeStatesInvalid,
                terminalStatesInvalid);
    }

    String activeStatesDiagnosticsCell() {
        return diagnosticsCountCell(activeStates.size(), activeStatesInvalid);
    }

    String terminalStatesDiagnosticsCell() {
        return diagnosticsCountCell(terminalStates.size(), terminalStatesInvalid);
    }

    private static String diagnosticsCountCell(int count, boolean invalid) {
        return invalid ? "invalid" : String.valueOf(count);
    }

    private static boolean containsIgnoreCase(List<String> values, String expected) {
        return values.stream().anyMatch(value -> value.equalsIgnoreCase(expected));
    }
}
