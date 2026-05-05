package ch.fmartin.symphony.trello.workspace;

import ch.fmartin.symphony.trello.config.EffectiveConfig;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.regex.Pattern;

@ApplicationScoped
public class WorkspaceManager {
    private static final Pattern UNSAFE = Pattern.compile("[^A-Za-z0-9._-]");

    private final HookRunner hooks;

    public WorkspaceManager(HookRunner hooks) {
        this.hooks = hooks;
    }

    public Workspace createForCard(String cardIdentifier, EffectiveConfig config) {
        String key = sanitize(cardIdentifier);
        Path root = config.workspace().root().toAbsolutePath().normalize();
        Path workspacePath = root.resolve(key).toAbsolutePath().normalize();
        requireInsideRoot(root, workspacePath);

        try {
            Files.createDirectories(root);
            boolean created = Files.notExists(workspacePath);
            if (created) {
                Files.createDirectory(workspacePath);
            } else if (!Files.isDirectory(workspacePath)) {
                throw new WorkspaceException(
                        "workspace_not_directory", workspacePath + " exists but is not a directory");
            }
            Workspace workspace = new Workspace(workspacePath, key, created);
            if (created) {
                hooks.runRequired("after_create", config.hooks().afterCreate(), workspacePath, config.hooks());
            }
            return workspace;
        } catch (IOException e) {
            throw new WorkspaceException("workspace_create_failed", "Workspace creation failed: " + workspacePath, e);
        }
    }

    public void removeForIdentifierIfPresent(String cardIdentifier, EffectiveConfig config) {
        if (cardIdentifier == null || cardIdentifier.isBlank()) {
            return;
        }
        Path root = config.workspace().root().toAbsolutePath().normalize();
        Path workspacePath =
                root.resolve(sanitize(cardIdentifier)).toAbsolutePath().normalize();
        requireInsideRoot(root, workspacePath);
        if (!Files.exists(workspacePath)) {
            return;
        }
        hooks.runBestEffort("before_remove", config.hooks().beforeRemove(), workspacePath, config.hooks());
        try (var paths = Files.walk(workspacePath)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    throw new WorkspaceException("workspace_remove_failed", "Failed to delete " + path, e);
                }
            });
        } catch (IOException e) {
            throw new WorkspaceException("workspace_remove_failed", "Failed to remove workspace " + workspacePath, e);
        }
    }

    public static void requireInsideRoot(Path root, Path workspacePath) {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path normalizedWorkspace = workspacePath.toAbsolutePath().normalize();
        if (!normalizedWorkspace.startsWith(normalizedRoot) || normalizedWorkspace.equals(normalizedRoot)) {
            throw new WorkspaceException(
                    "workspace_outside_root",
                    "Workspace path must be a child of workspace root: " + normalizedWorkspace);
        }
    }

    public static String sanitize(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            throw new WorkspaceException("workspace_identifier_missing", "Card identifier is required");
        }
        return UNSAFE.matcher(identifier).replaceAll("_");
    }
}
