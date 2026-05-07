package ch.fmartin.symphony.trello.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CodexSkillInstallerTest {
    private final CodexSkillInstaller installer = new CodexSkillInstaller();

    @TempDir
    Path tempDir;

    @Test
    void installsBundledCodexSkillsIntoNamespacedWorkspaceDirectories() {
        // given
        Path workspace = tempDir.resolve("workspace");

        // when
        installer.installInto(workspace);

        // then
        assertThat(workspace.resolve(CodexSkillInstaller.installedSkillPath("commit")))
                .content()
                .contains("name: symphony-trello-commit")
                .contains("configure it from the authenticated GitHub login");
        assertThat(workspace.resolve(CodexSkillInstaller.installedSkillPath("push-pr")))
                .content()
                .contains("name: symphony-trello-push-pr")
                .contains("verify that commits intended for the PR are authored");
        assertThat(workspace.resolve(CodexSkillInstaller.installedSkillPath("land")))
                .content()
                .contains("`symphony-trello-review-sweep`")
                .contains("`symphony-trello-repo-sync`")
                .contains("`symphony-trello-push-pr`")
                .doesNotContain("`review-sweep`")
                .doesNotContain("`repo-sync`")
                .doesNotContain("`push-pr`");
    }

    @Test
    void refreshesBundledCodexSkillsInExistingWorkspaces() throws Exception {
        // given
        Path workspace = tempDir.resolve("workspace");
        installer.installInto(workspace);
        Path commitSkill = workspace.resolve(CodexSkillInstaller.installedSkillPath("commit"));
        Files.writeString(commitSkill, "stale");

        // when
        installer.installInto(workspace);

        // then
        assertThat(commitSkill).content().contains("configure it from the authenticated GitHub login");
    }

    @Test
    void excludesInstalledSkillsFromGitStatusWhenWorkspaceIsCheckoutRoot() throws Exception {
        // given
        Path workspace = tempDir.resolve("workspace");
        Path exclude = workspace.resolve(".git/info/exclude");
        Files.createDirectories(exclude.getParent());
        Files.writeString(exclude, "*.log\n");

        // when
        installer.installInto(workspace);
        installer.installInto(workspace);

        // then
        assertThat(exclude)
                .content()
                .contains("*.log\n/.codex/skills/symphony-trello-*/\n")
                .containsOnlyOnce("/.codex/skills/symphony-trello-*/");
    }

    @Test
    void doesNotFollowCheckoutRootCommonDirOutsideWorkspace() throws Exception {
        // given
        Path workspace = tempDir.resolve("workspace");
        Path outsideCommonDir = tempDir.resolve("outside-git");
        Path localExclude = workspace.resolve(".git/info/exclude");
        Files.createDirectories(localExclude.getParent());
        Files.writeString(workspace.resolve(".git/commondir"), outsideCommonDir.toString());

        // when
        installer.installInto(workspace);

        // then
        assertThat(localExclude).content().contains("/.codex/skills/symphony-trello-*/");
        assertThat(outsideCommonDir.resolve("info/exclude")).doesNotExist();
    }

    @Test
    void refusesGitdirFilesWithoutSafeWorkspaceBackReference() throws Exception {
        // given
        Path workspace = tempDir.resolve("workspace");
        Path outsideGitDir = tempDir.resolve("outside/.git");
        Files.createDirectories(outsideGitDir.resolve("info"));
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve(".git"), "gitdir: " + outsideGitDir + "\n");

        // when
        Throwable thrown = catchThrowable(() -> installer.installInto(workspace));

        // then
        assertThat(thrown).isInstanceOfSatisfying(WorkspaceException.class, error -> assertThat(error.code())
                .isEqualTo("workspace_skill_gitdir_file_failed"));
        assertThat(outsideGitDir.resolve("info/exclude")).doesNotExist();
    }

    @Test
    void excludesInstalledSkillsFromGitStatusWhenWorkspaceUsesGitdirFile() throws Exception {
        // given
        Path workspace = tempDir.resolve("workspace");
        Path gitDir = tempDir.resolve("repository/.git/worktrees/workspace");
        Path commonGitDir = tempDir.resolve("repository/.git");
        Path exclude = commonGitDir.resolve("info/exclude");
        Files.createDirectories(gitDir);
        Files.createDirectories(commonGitDir);
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve(".git"), "gitdir: " + gitDir + "\n");
        Files.writeString(gitDir.resolve("gitdir"), workspace.resolve(".git").toString());
        Files.writeString(gitDir.resolve("commondir"), "../..");

        // when
        installer.installInto(workspace);

        // then
        assertThat(exclude).content().contains("/.codex/skills/symphony-trello-*/");
        assertThat(workspace.resolve(CodexSkillInstaller.installedSkillPath("commit")))
                .isRegularFile();
    }

    @Test
    void refusesToOverwriteWorkspaceControlledSkillSymlinks() throws Exception {
        // given
        Path workspace = tempDir.resolve("workspace");
        Path outside = tempDir.resolve("outside.md");
        Path target = workspace.resolve(CodexSkillInstaller.installedSkillPath("commit"));
        Files.createDirectories(target.getParent());
        Files.writeString(outside, "do not overwrite");
        Files.createSymbolicLink(target, outside);

        // when
        Throwable thrown = catchThrowable(() -> installer.installInto(workspace));

        // then
        assertThat(thrown).isInstanceOfSatisfying(WorkspaceException.class, error -> assertThat(error.code())
                .isEqualTo("workspace_skill_install_failed"));
        assertThat(outside).content().isEqualTo("do not overwrite");
    }

    @Test
    void refusesToFollowWorkspaceControlledGitExcludeSymlinks() throws Exception {
        // given
        Path workspace = tempDir.resolve("workspace");
        Path outside = tempDir.resolve("outside-exclude");
        Path exclude = workspace.resolve(".git/info/exclude");
        Files.createDirectories(exclude.getParent());
        Files.writeString(outside, "do not mutate");
        Files.createSymbolicLink(exclude, outside);

        // when
        Throwable thrown = catchThrowable(() -> installer.installInto(workspace));

        // then
        assertThat(thrown).isInstanceOfSatisfying(WorkspaceException.class, error -> assertThat(error.code())
                .isEqualTo("workspace_skill_git_exclude_failed"));
        assertThat(outside).content().isEqualTo("do not mutate");
    }
}
