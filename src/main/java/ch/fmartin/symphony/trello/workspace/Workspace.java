package ch.fmartin.symphony.trello.workspace;

import java.nio.file.Path;

public record Workspace(Path path, String workspaceKey, boolean createdNow) {}
