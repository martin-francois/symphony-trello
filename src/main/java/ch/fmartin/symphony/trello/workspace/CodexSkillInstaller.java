package ch.fmartin.symphony.trello.workspace;

import static ch.fmartin.symphony.trello.codex.CodexSkillCatalog.INSTALLED_SKILL_PREFIX;
import static ch.fmartin.symphony.trello.codex.CodexSkillCatalog.SKILL_NAMES;

import ch.fmartin.symphony.trello.codex.CodexSkillCatalog;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Set;

@ApplicationScoped
public class CodexSkillInstaller {
    private static final String GIT_EXCLUDE_PATTERN = "/.codex/skills/symphony-trello-*/";

    public static String installedSkillPath(String skillName) {
        return CodexSkillCatalog.installedSkillPath(skillName);
    }

    public void installInto(Path workspacePath) {
        excludeInstalledSkillsFromGitStatus(workspacePath);
        Path skillsRoot = workspacePath.resolve(".codex").resolve("skills");
        for (String skillName : SKILL_NAMES) {
            installSkill(skillsRoot, skillName);
        }
    }

    private void installSkill(Path skillsRoot, String skillName) {
        String resourcePath = "codex-skills/%s/SKILL.md".formatted(skillName);
        Path target = skillsRoot.resolve(INSTALLED_SKILL_PREFIX + skillName).resolve("SKILL.md");
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (input == null) {
                throw new WorkspaceException(
                        "workspace_skill_missing", "Bundled Codex skill is missing: " + resourcePath);
            }
            createSafeDirectory(skillsRoot);
            createSafeParentDirectory(target);
            writeSafeSkill(target, namespacedSkillBody(skillName, input.readAllBytes()));
        } catch (IOException e) {
            throw new WorkspaceException(
                    "workspace_skill_install_failed", "Could not install bundled Codex skill " + skillName, e);
        }
    }

    private void excludeInstalledSkillsFromGitStatus(Path workspacePath) {
        try {
            Path gitDir = gitDir(workspacePath);
            if (gitDir == null) {
                return;
            }
            Path exclude = gitDir.resolve("info").resolve("exclude");
            createSafeParentDirectory(exclude);
            String existing = readSafeFile(exclude);
            if (existing.lines().noneMatch(GIT_EXCLUDE_PATTERN::equals)) {
                writeSafeFile(exclude, existing + linePrefix(existing) + GIT_EXCLUDE_PATTERN + "\n");
            }
        } catch (IOException e) {
            throw new WorkspaceException(
                    "workspace_skill_git_exclude_failed",
                    "Could not exclude bundled Codex skills from Git status in " + workspacePath,
                    e);
        }
    }

    private static String linePrefix(String existing) {
        if (existing.isEmpty() || existing.endsWith("\n")) {
            return "";
        }
        return "\n";
    }

    private static void createSafeParentDirectory(Path path) throws IOException {
        Path parent = path.getParent();
        if (parent != null) {
            createSafeDirectory(parent);
        }
    }

    private static Path gitDir(Path workspacePath) throws IOException {
        Path dotGit = workspacePath.resolve(".git");
        if (Files.isDirectory(dotGit, LinkOption.NOFOLLOW_LINKS)) {
            return dotGit;
        }
        if (Files.isRegularFile(dotGit, LinkOption.NOFOLLOW_LINKS)) {
            return gitDirFromFile(workspacePath, dotGit);
        }
        return null;
    }

    private static Path gitDirFromFile(Path workspacePath, Path dotGit) {
        try {
            String content = readSafeFile(dotGit).strip();
            if (!content.startsWith("gitdir:")) {
                throw new IOException(".git file does not declare a gitdir");
            }
            String rawGitDir = content.substring("gitdir:".length()).trim();
            if (rawGitDir.isBlank()) {
                throw new IOException(".git file declares an empty gitdir");
            }
            Path declaredGitDir = Path.of(rawGitDir);
            Path resolvedGitDir = (declaredGitDir.isAbsolute() ? declaredGitDir : workspacePath.resolve(declaredGitDir))
                    .toAbsolutePath()
                    .normalize();
            if (!Files.isDirectory(resolvedGitDir, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException(".git file points to a non-directory gitdir: " + resolvedGitDir);
            }
            requireGitDirBackReference(workspacePath, dotGit, resolvedGitDir);
            return commonGitDir(resolvedGitDir);
        } catch (IOException e) {
            throw new WorkspaceException(
                    "workspace_skill_gitdir_file_failed", "Could not resolve workspace Git metadata from " + dotGit, e);
        }
    }

    private static Path commonGitDir(Path gitDir) throws IOException {
        Path commondir = gitDir.resolve("commondir");
        if (!Files.isRegularFile(commondir, LinkOption.NOFOLLOW_LINKS)) {
            return gitDir;
        }
        Path commonGitDir = resolveFrom(gitDir, readSafeFile(commondir).strip());
        if (!Files.isDirectory(commonGitDir, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Git commondir points to a non-directory path: " + commonGitDir);
        }
        return commonGitDir;
    }

    private static void requireGitDirBackReference(Path workspacePath, Path dotGit, Path resolvedGitDir)
            throws IOException {
        Path gitdirBackReference = resolvedGitDir.resolve("gitdir");
        if (Files.isRegularFile(gitdirBackReference, LinkOption.NOFOLLOW_LINKS)) {
            Path declaredDotGit = resolveFrom(
                    resolvedGitDir, readSafeFile(gitdirBackReference).strip());
            if (declaredDotGit.equals(dotGit.toAbsolutePath().normalize())) {
                return;
            }
        }

        Path config = resolvedGitDir.resolve("config");
        if (Files.isRegularFile(config, LinkOption.NOFOLLOW_LINKS)) {
            for (String line : readSafeFile(config).lines().toList()) {
                String stripped = line.strip();
                if (stripped.startsWith("worktree")) {
                    int separator = stripped.indexOf('=');
                    if (separator > 0) {
                        Path declaredWorktree = resolveFrom(
                                resolvedGitDir,
                                stripped.substring(separator + 1).trim());
                        if (declaredWorktree.equals(
                                workspacePath.toAbsolutePath().normalize())) {
                            return;
                        }
                    }
                }
            }
        }

        throw new IOException(".git file points to a gitdir without a safe back-reference to the workspace");
    }

    private static Path resolveFrom(Path base, String value) throws IOException {
        if (value.isBlank()) {
            throw new IOException("Git metadata path is blank");
        }
        Path path = Path.of(value);
        return (path.isAbsolute() ? path : base.resolve(path)).toAbsolutePath().normalize();
    }

    private static String namespacedSkillBody(String skillName, byte[] rawBody) {
        String body = new String(rawBody, StandardCharsets.UTF_8);
        String namespaced =
                body.replaceFirst("(?m)^name: %s$".formatted(skillName), "name: " + INSTALLED_SKILL_PREFIX + skillName);
        for (String referencedSkill : SKILL_NAMES) {
            namespaced = namespaced.replace(
                    "`" + referencedSkill + "`", "`" + INSTALLED_SKILL_PREFIX + referencedSkill + "`");
        }
        return namespaced;
    }

    private static void createSafeDirectory(Path directory) throws IOException {
        Path parent = directory.getParent();
        if (parent != null && !Files.exists(parent, LinkOption.NOFOLLOW_LINKS)) {
            createSafeDirectory(parent);
        }
        if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Refusing to use non-directory path: " + directory);
            }
            return;
        }
        Files.createDirectory(directory);
    }

    private static String readSafeFile(Path target) throws IOException {
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            return "";
        }
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Refusing to read non-regular file: " + target);
        }
        try (SeekableByteChannel channel =
                Files.newByteChannel(target, Set.<OpenOption>of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
            ByteBuffer buffer = ByteBuffer.allocate(Math.toIntExact(channel.size()));
            while (buffer.hasRemaining() && channel.read(buffer) != -1) {
                // Read until the file is fully buffered.
            }
            buffer.flip();
            return StandardCharsets.UTF_8.decode(buffer).toString();
        }
    }

    private static void writeSafeFile(Path target, String body) throws IOException {
        writeSafeFile(target, body, "Refusing to overwrite non-regular file: " + target);
    }

    private static void writeSafeFile(Path target, String body, String nonRegularPathMessage) throws IOException {
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)
                && !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(nonRegularPathMessage);
        }
        Files.writeString(
                target,
                body,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS);
    }

    private static void writeSafeSkill(Path target, String body) throws IOException {
        writeSafeFile(target, body, "Refusing to overwrite non-regular skill file: " + target);
    }
}
