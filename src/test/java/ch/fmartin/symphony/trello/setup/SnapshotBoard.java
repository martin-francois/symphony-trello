package ch.fmartin.symphony.trello.setup;

/// The Trello board that every terminal-output snapshot scenario connects, so the baselines of
/// both snapshot suites name the same board.
final class SnapshotBoard {
    static final String NAME = "Snapshot Queue";
    static final String SLUG = "snapshot-queue";
    static final String URL = "https://trello.com/b/abc123/" + SLUG;
    static final String WORKFLOW_FILE_NAME = "WORKFLOW." + SLUG + ".md";

    private SnapshotBoard() {}
}
