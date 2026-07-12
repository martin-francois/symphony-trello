package ch.fmartin.symphony.trello;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class CodexSkillStructureTest {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final Path SKILLS_ROOT = Path.of(".codex", "skills");

    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

    @Test
    void repositoryLocalSkillsHaveValidFrontMatterAndMatchingNames() throws IOException {
        // given
        List<Path> skillFiles = repositoryLocalSkillFiles();

        // when
        List<SkillMetadata> metadata =
                skillFiles.stream().map(this::readSkillMetadata).toList();

        // then
        assertThat(metadata)
                .extracting(SkillMetadata::name)
                .contains(
                        "commit",
                        "debug",
                        "land",
                        "push-pr",
                        "repo-sync",
                        "review-sweep",
                        "trello-handoff",
                        "trello-workpad");
        assertThat(metadata).allSatisfy(skill -> {
            assertThat(skill.file()).as("skill file").isRegularFile();
            assertThat(skill.name())
                    .as("front matter name for %s", skill.file())
                    .isEqualTo(skill.directoryName());
            assertThat(skill.description())
                    .as("description for %s", skill.name())
                    .isNotBlank();
            assertThat(skill.body()).as("body for %s", skill.name()).contains("# ");
        });
    }

    @Test
    void repositoryLocalSkillsArePackagedForWorkspaceSeeding() throws IOException {
        // given
        List<Path> skillFiles = repositoryLocalSkillFiles();

        // when
        List<String> packagedResources = skillFiles.stream()
                .map(path ->
                        "codex-skills/%s/SKILL.md".formatted(path.getParent().getFileName()))
                .toList();

        // then
        assertThat(packagedResources).allSatisfy(resource -> {
            try (var stream = getClass().getClassLoader().getResourceAsStream(resource)) {
                assertThat(stream).as("packaged skill resource %s", resource).isNotNull();
                assertThat(new String(stream.readAllBytes(), StandardCharsets.UTF_8))
                        .as("packaged skill body %s", resource)
                        .contains("# ");
            } catch (IOException e) {
                throw new AssertionError("Could not read packaged skill resource " + resource, e);
            }
        });
        assertThat(getClass().getClassLoader().getResource("codex-skills/tessl__recon/SKILL.md"))
                .as("ignored Tessl-generated skill symlinks are not packaged")
                .isNull();
    }

    @Test
    void reviewSweepSkillCoversPrFeedbackSourcesAndOutcomes() throws IOException {
        // given
        Path skill = SKILLS_ROOT.resolve("review-sweep").resolve("SKILL.md");

        // when
        String body = readSkillMetadata(skill).body();

        // then
        assertThat(body)
                .contains("PR URLs in the Trello card description")
                .contains("PR URLs in recent Trello comments")
                .contains("Branch names in the Trello card description or comments")
                .contains("gh pr view --json")
                .contains("gh api \"repos/{owner}/{repo}/issues/$pr_number/comments\"")
                .contains("gh api \"repos/{owner}/{repo}/pulls/$pr_number/comments\"")
                .contains("gh api \"repos/{owner}/{repo}/pulls/$pr_number/reviews\"")
                .contains("gh api graphql --paginate")
                .contains("reviewThreads(first: 100, after: $endCursor)")
                .contains("pageInfo { hasNextPage endCursor }")
                .contains("comments(first: 100)")
                .contains("comments(first: 100, after: $endCursor)")
                .doesNotContain("resolveReviewThread")
                .contains("Do not treat a possible\nincomplete comments page as a complete feedback sweep")
                .contains("leave the thread unresolved for the reviewer")
                .contains("short commit hash containing the change")
                .contains("fenced code blocks showing the final relevant code")
                .contains("A prose summary, file link, or commit link does not\nreplace the final snippet")
                .contains("When no file content changed, say that\nexplicitly")
                .contains("Codex review issue comments")
                .contains("correctness")
                .contains("design")
                .contains("style")
                .contains("clarification")
                .contains("scope")
                .contains("inline replies for inline review comments")
                .contains("PR title, body, branch, labels, or linked card references")
                .contains("If a failing check is related to the card's changes")
                .contains("If a related CI check fails")
                .contains("If checks are pending or stale")
                .contains("external quota or infrastructure limits")
                .contains("do not spend")
                .contains("time reproducing that unrelated failure locally")
                .contains("flaky-check caveat")
                .contains("Repeat the sweep until no actionable comments remain");
    }

    @Test
    void trelloHandoffSkillCoversReworkWithoutResetByDefault() throws IOException {
        // given
        Path skill = SKILLS_ROOT.resolve("trello-handoff").resolve("SKILL.md");

        // when
        String body = readSkillMetadata(skill).body();

        // then
        assertThat(body)
                .contains("## Rework")
                .contains("moves a card from `Human Review` back to an active list")
                .contains("Read the existing `## Codex Workpad` comment")
                .contains("Run `review-sweep` when a PR or branch exists")
                .contains("PR: <https://github.com/owner/repo/pull/123>")
                .contains("Preserve completed work")
                .contains("Do not close the")
                .contains("existing PR")
                .contains("delete the workpad")
                .contains("create a new branch")
                .contains("Do not create duplicate progress summary comments")
                .contains("trello_update_blocker_recheck_status")
                .containsIgnoringWhitespaces("newest ordinary comment")
                .containsIgnoringWhitespaces("exact `Managed by Symphony` footer")
                .containsIgnoringWhitespaces("a link to the qualifying blocker comment on the current card")
                .containsIgnoringWhitespaces(
                        "The newest ordinary `Blocked:` or `Blocked by ...` comment is the comment being rechecked; leave it unchanged")
                .containsIgnoringWhitespaces(
                        "Call `checking` to create or update a separate Symphony-managed status comment")
                .containsIgnoringWhitespaces(
                        "The rendered prompt contains only recent Trello comments, so do not use it to decide whether a stale blocker exists")
                .containsIgnoringWhitespaces(
                        "This section applies only when `trello_update_blocker_recheck_status` is advertised")
                .containsIgnoringWhitespaces(
                        "When it is unavailable, do not attempt a managed recheck write; follow the workflow's tool-disabled final-response or manual handoff path")
                .containsIgnoringWhitespaces(
                        "When the tool is advertised, always call it with status `checking` before changing code")
                .containsIgnoringWhitespaces(
                        "When the tool returns `blocker_recheck_not_needed`, continue without creating a managed status")
                .containsIgnoringWhitespaces(
                        "If the initial `checking` call returns a tool failure, including `trello_blocker_recheck_refresh_failed` or `trello_blocker_recheck_card_missing`, stop the current attempt")
                .containsIgnoringWhitespaces("Do not test the blocker, call `resumed`, or request another Trello write")
                .containsIgnoringWhitespaces("the next dispatched retry must begin with `checking` again")
                .containsIgnoringWhitespaces(
                        "Symphony's automatic pre-dispatch move may already have happened before Codex starts")
                .containsIgnoringWhitespaces(
                        "The absence of an existing managed recheck comment is not a reason to skip this call")
                .contains("`Blocked:`")
                .contains("`Blocked by ...`")
                .contains("matched without case sensitivity")
                .containsIgnoringWhitespaces("status `checking`")
                .contains("status `resumed`")
                .containsIgnoringWhitespaces(
                        "If the `resumed` call returns any tool failure, including `trello_blocker_recheck_stale`, `trello_blocker_recheck_not_started`, `trello_blocker_recheck_refresh_failed`, or `trello_blocker_recheck_card_missing`, stop the current attempt")
                .containsIgnoringWhitespaces(
                        "Do not claim that work resumed, use the ordinary blocked handoff, or request another Trello write")
                .containsIgnoringWhitespaces(
                        "A stale result means the newly qualifying blocker must enter its own `checking` episode before it can resume")
                .contains("scan past a newer ordinary human comment")
                .contains("claim that work resumed")
                .containsIgnoringWhitespaces(
                        "An already-resumed retry for the same blocker comment retains its last-confirmed resumed state. A new qualifying blocker comment starts a new action-bound recheck episode and must enter `checking` before it can resume.");
    }

    @Test
    void commitAndPushPrSkillsCoverGithubAuthorIdentityPolicy() throws IOException {
        // given
        Path commitSkill = SKILLS_ROOT.resolve("commit").resolve("SKILL.md");
        Path pushPrSkill = SKILLS_ROOT.resolve("push-pr").resolve("SKILL.md");

        // when
        String commitBody = readSkillMetadata(commitSkill).body();
        String pushPrBody = readSkillMetadata(pushPrSkill).body();

        // then
        assertThat(commitBody)
                .contains("Match the target repository's commit-message convention")
                .contains("CONTRIBUTING.md")
                .contains("last 20 to 50")
                .contains("only one commit")
                .contains("default to Conventional Commits")
                .contains("verified or configured for this\n  workflow")
                .contains("configure it from the authenticated GitHub login")
                .contains("git config --local --get user.name")
                .contains("git config --local --get user.email")
                .contains("symphony-trello.github-author-verified")
                .contains("avoids repeated GitHub API calls")
                .contains("unrelated local author")
                .contains("authenticated GitHub login")
                .contains("gh api user")
                .contains("gh api user/emails")
                .contains("users.noreply.github.com")
                .contains("could not resolve user login")
                .contains("could not resolve user email metadata")
                .contains("user:email scope")
                .contains("gh auth refresh -s user:email")
                .contains("configure a public email or accessible GitHub noreply email")
                .contains("generic fallback author")
                .contains("lookup before the first commit and cannot\n  resolve it")
                .contains("has not been pushed")
                .contains("do not rewrite history");
        assertThat(pushPrBody)
                .contains("Create a ready-for-review, non-draft PR")
                .contains("Use a draft PR only when the Trello card explicitly asks for a draft PR")
                .contains("verify that commits intended for the PR are authored")
                .contains("authenticated GitHub login")
                .contains("gh api user")
                .contains("gh api user/emails")
                .contains("users.noreply.github.com")
                .contains("cannot verify PR commit author login")
                .contains("user:email scope")
                .contains("could not resolve origin default branch")
                .contains("git fetch origin")
                .contains("git merge-base HEAD")
                .contains("wrong_authors=")
                .contains("%H%x09%an <%ae>")
                .contains("[ \"$author\" != \"$github_author\" ]")
                .contains("expected PR commits authored as")
                .contains("Git author verification will rewrite PR branch commits")
                .contains("git rebase --exec 'git commit --amend --no-edit --reset-author'")
                .contains("symphony_trello_author_rewrite=true")
                .contains("git push --force-with-lease -u origin HEAD")
                .contains("current non-default PR branch")
                .contains("generic\n   Codex identity")
                .contains("configure a public email or accessible GitHub noreply email")
                .contains("number,state,title,url,isDraft")
                .contains("Do not pass\n     `--draft` unless")
                .contains("mark it\n     ready for review")
                .contains("repository's default/base template source")
                .contains("do not rely on templates that only exist on the unmerged task")
                .contains("single-template locations under `.github/`, the")
                .contains("repository root, and `docs/`")
                .contains("case-insensitively with supported `.md` or `.txt` extensions")
                .contains(
                        "`PULL_REQUEST_TEMPLATE/`\n     directories under `.github/`, the repository root, and `docs/`")
                .contains("exactly one directory template candidate exists")
                .contains("multiple directory template candidates exist")
                .contains("unambiguously name\n     the intended template")
                .contains("surface a\n     blocker")
                .contains("Preserve required headings, checklists, and prompts")
                .contains("If no template exists")
                .contains("PR: <https://github.com/owner/repo/pull/123>")
                .contains("trailing\n    punctuation cannot be absorbed into the link")
                .contains("contains unrelated\n  human-owned work");
    }

    @Test
    void landSkillFailsClosedAndAvoidsAutoMergeByDefault() throws IOException {
        // given
        Path skill = SKILLS_ROOT.resolve("land").resolve("SKILL.md");

        // when
        String body = readSkillMetadata(skill).body();

        // then
        assertThat(body)
                .contains("Merge only after a human moves the Trello card to `Merging`")
                .contains("Do not enable auto-merge unless the repository policy explicitly requires it")
                .contains("mergeability")
                .contains("review feedback")
                .contains("required checks are green")
                .contains("leave thread resolution to the\n   reviewer")
                .contains("configured review handoff list to the\n  merge approval list")
                .contains("precise, unambiguous feedback")
                .contains("material fixups, broad\n  interpretation, or unverifiable changes")
                .contains("move the card to the configured merge completion list")
                .contains("configured blocked\n  destination")
                .contains("configured review handoff list rather than the merge\n  approval list");
    }

    private SkillMetadata readSkillMetadata(Path file) {
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            assertThat(content).as("front matter start for %s", file).startsWith("---\n");
            int end = content.indexOf("\n---\n", 4);
            assertThat(end).as("front matter end for %s", file).isGreaterThan(0);
            Map<String, Object> frontMatter = yaml.readValue(content.substring(4, end), MAP_TYPE);
            String body = content.substring(end + "\n---\n".length());
            return new SkillMetadata(
                    file,
                    file.getParent().getFileName().toString(),
                    string(frontMatter.get("name")),
                    string(frontMatter.get("description")),
                    body);
        } catch (IOException e) {
            throw new AssertionError("Could not read skill file " + file, e);
        }
    }

    private List<Path> repositoryLocalSkillFiles() throws IOException {
        try (var paths = Files.list(SKILLS_ROOT)) {
            return paths.filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                    .map(path -> path.resolve("SKILL.md"))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        }
    }

    private static String string(Object value) {
        return value == null ? "" : value.toString();
    }

    private record SkillMetadata(Path file, String directoryName, String name, String description, String body) {}
}
