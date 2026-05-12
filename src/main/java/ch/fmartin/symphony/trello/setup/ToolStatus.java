package ch.fmartin.symphony.trello.setup;

record ToolStatus(boolean available) {
    static ToolStatus found() {
        return new ToolStatus(true);
    }

    static ToolStatus unavailable() {
        return new ToolStatus(false);
    }
}
