package ch.fmartin.symphony.trello;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CodexSkillStructureTest {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final Path SKILLS_ROOT = Path.of(".codex", "skills");

    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

    @Test
    void repositoryLocalSkillsHaveValidFrontMatterAndMatchingNames() throws IOException {
        // given
        List<Path> skillFiles;
        try (var paths = Files.list(SKILLS_ROOT)) {
            skillFiles = paths.filter(Files::isDirectory)
                    .map(path -> path.resolve("SKILL.md"))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        }

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
                .contains("Preserve completed work")
                .contains("Do not close the")
                .contains("existing PR")
                .contains("delete the workpad")
                .contains("create a new branch")
                .contains("Do not create duplicate progress summary comments");
    }

    @Test
    void landSkillFailsClosedAndAvoidsAutoMergeByDefault() throws IOException {
        // given
        Path skill = SKILLS_ROOT.resolve("land").resolve("SKILL.md");

        // when
        String body = readSkillMetadata(skill).body();

        // then
        assertThat(body)
                .contains("Land only after a human moves the Trello card to `Merging`")
                .contains("Do not enable auto-merge unless the repository policy explicitly requires it")
                .contains("mergeability")
                .contains("review feedback")
                .contains("required checks are green")
                .contains("move the card to the configured landing completion list")
                .contains("move the card to `Blocked`")
                .contains("The card is in `Human Review` rather than `Merging`");
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

    private static String string(Object value) {
        return value == null ? "" : value.toString();
    }

    private record SkillMetadata(Path file, String directoryName, String name, String description, String body) {}
}
