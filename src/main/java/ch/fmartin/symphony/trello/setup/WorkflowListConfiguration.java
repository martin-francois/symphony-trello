package ch.fmartin.symphony.trello.setup;

import java.util.List;
import java.util.Optional;

record WorkflowListConfiguration(
        List<String> activeStates,
        List<String> terminalStates,
        Optional<String> inProgressState,
        Optional<String> blockedState) {
    static WorkflowListConfiguration empty() {
        return new WorkflowListConfiguration(List.of(), List.of(), Optional.empty(), Optional.empty());
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
                blockedState.filter(state -> containsIgnoreCase(openLists, state)));
    }

    private static boolean containsIgnoreCase(List<String> values, String expected) {
        return values.stream().anyMatch(value -> value.equalsIgnoreCase(expected));
    }
}
