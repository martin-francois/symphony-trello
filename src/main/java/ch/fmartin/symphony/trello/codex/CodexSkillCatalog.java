package ch.fmartin.symphony.trello.codex;

import java.util.List;

public final class CodexSkillCatalog {
    public static final String INSTALLED_SKILL_PREFIX = "symphony-trello-";
    public static final List<String> SKILL_NAMES = List.of(
            "commit", "debug", "land", "push-pr", "repo-sync", "review-sweep", "trello-handoff", "trello-workpad");

    private CodexSkillCatalog() {}

    public static String installedSkillPath(String skillName) {
        return ".codex/skills/%s%s/SKILL.md".formatted(INSTALLED_SKILL_PREFIX, skillName);
    }
}
