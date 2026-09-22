package ch.fmartin.symphony.trello.telemetry;

import java.util.OptionalInt;

/// Supplies the number of distinct connected Trello boards. Absent means the local manifest is
/// unreadable or invalid, which the heartbeat reports as `null`, never as `0`.
@FunctionalInterface
public interface ConnectedBoardInventory {
    OptionalInt distinctBoardCount();

    static ConnectedBoardInventory unavailable() {
        return OptionalInt::empty;
    }
}
