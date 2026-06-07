package ch.fmartin.symphony.trello.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class TrelloListRoleValidator {
    private TrelloListRoleValidator() {}

    public static Optional<Overlap> firstOverlap(
            List<String> activeStates, List<String> terminalStates, String inProgressState, String blockedState) {
        List<RoleValue> values = new ArrayList<>();
        activeStates.forEach(state -> add(values, "active", state));
        terminalStates.forEach(state -> add(values, "terminal", state));
        add(values, "in-progress", inProgressState);
        add(values, "blocked", blockedState);

        for (int left = 0; left < values.size(); left++) {
            for (int right = left + 1; right < values.size(); right++) {
                RoleValue first = values.get(left);
                RoleValue second = values.get(right);
                if (first.role().equals(second.role())) {
                    continue;
                }
                if (isAllowedOverlap(first, second)) {
                    continue;
                }
                if (first.normalizedValue().equals(second.normalizedValue())) {
                    return Optional.of(new Overlap(first.role(), second.role(), first.value()));
                }
            }
        }
        return Optional.empty();
    }

    private static void add(List<RoleValue> values, String role, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        values.add(new RoleValue(role, value, StateNames.normalize(value)));
    }

    private static boolean isAllowedOverlap(RoleValue first, RoleValue second) {
        return "active".equals(first.role()) && "in-progress".equals(second.role());
    }

    public record Overlap(String firstRole, String secondRole, String value) {
        public String description() {
            return firstRole + " and " + secondRole + " both use " + value;
        }
    }

    private record RoleValue(String role, String value, String normalizedValue) {}
}
