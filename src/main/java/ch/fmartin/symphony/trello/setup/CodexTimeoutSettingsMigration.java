package ch.fmartin.symphony.trello.setup;

import static java.nio.charset.StandardCharsets.UTF_8;

import ch.fmartin.symphony.trello.config.ConfigDefaults;
import ch.fmartin.symphony.trello.config.EffectiveConfig;
import ch.fmartin.symphony.trello.workflow.WorkflowException;
import ch.fmartin.symphony.trello.workflow.WorkflowLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.NullMarked;

/// Temporary front matter migration that brings workflows written before Symphony 2.0.0 to the
/// Codex timeout settings that setup writes now (ADR 0136). It raises `codex.turn_timeout_ms` from
/// the old generated default to the current default, so one review run fits inside a turn, and
/// writes the review-loop settings that setup now writes when they are missing.
///
/// The change is a text edit of the affected lines, so comments, quoting, and the order of every
/// other line stay as they are. A planned edit counts only when the edited front matter parses to
/// the original settings plus exactly the planned changes.
///
/// Remove this class as tracked in GitHub issue #1150, together with the settings-update step in
/// [WorkflowBodyMigration], the settings-update output in [WorkflowMigrationReporter], and
/// [WorkflowBodyWriter#writeMetadata] if nothing else uses it.
@NullMarked
final class CodexTimeoutSettingsMigration {
    /// The `codex.turn_timeout_ms` value that setup wrote before Symphony 2.0.0. It is a
    /// historical value, not the current default, so it must not follow [ConfigDefaults].
    static final int RELEASED_GENERATED_TURN_TIMEOUT_MS = 3_600_000;

    static final int TARGET_TURN_TIMEOUT_MS = Math.toIntExact(ConfigDefaults.DEFAULT_CODEX_TURN_TIMEOUT_MS);

    private static final Pattern TOP_LEVEL_KEY = Pattern.compile("([A-Za-z_][A-Za-z0-9_-]*):[ \\t]*(?:#.*)?");
    private static final Pattern LEADING_SPACES = Pattern.compile("^( +)\\S");
    private static final String COMMENT_START = "#";

    private CodexTimeoutSettingsMigration() {}

    /// Plans the update for one workflow file. Returns empty when the workflow needs no change or
    /// its front matter cannot be read.
    static Optional<Plan> plan(Path workflowPath, String content) {
        return WorkflowFileText.parse(content).flatMap(text -> plan(workflowPath, text));
    }

    private static Optional<Plan> plan(Path workflowPath, WorkflowFileText text) {
        return settings(workflowPath, text.content())
                .map(CodexTimeoutSettingsMigration::changes)
                .filter(changes -> !changes.isEmpty())
                .map(changes -> new Plan(text, changes, verifiedEdit(workflowPath, text, changes)));
    }

    private static Optional<String> verifiedEdit(Path workflowPath, WorkflowFileText text, List<Change> changes) {
        return edit(text, changes).filter(candidate -> parsesTo(workflowPath, candidate, text.content(), changes));
    }

    private static List<Change> changes(Map<String, Object> settings) {
        List<Change> changes = new ArrayList<>();
        if (section(settings, EffectiveConfig.CodexConfig.SECTION_KEY)
                .map(codex -> codex.get(EffectiveConfig.CodexConfig.TURN_TIMEOUT_MS_KEY))
                .filter(Integer.valueOf(RELEASED_GENERATED_TURN_TIMEOUT_MS)::equals)
                .isPresent()) {
            changes.add(new Change(
                    EffectiveConfig.CodexConfig.SECTION_KEY,
                    EffectiveConfig.CodexConfig.TURN_TIMEOUT_MS_KEY,
                    OptionalInt.of(RELEASED_GENERATED_TURN_TIMEOUT_MS),
                    TARGET_TURN_TIMEOUT_MS));
        }
        section(settings, EffectiveConfig.RepositoryConfig.SECTION_KEY)
                .ifPresent(repository -> addMissingReviewSettings(changes, repository));
        return List.copyOf(changes);
    }

    private static void addMissingReviewSettings(List<Change> changes, Map<String, Object> repository) {
        addIfMissing(
                changes,
                repository,
                EffectiveConfig.CodexReviewConfig.MAX_ROUNDS_KEY,
                ConfigDefaults.DEFAULT_CODEX_REVIEW_MAX_ROUNDS);
        addIfMissing(
                changes,
                repository,
                EffectiveConfig.CodexReviewConfig.TIMEOUT_MS_KEY,
                Math.toIntExact(ConfigDefaults.DEFAULT_CODEX_REVIEW_TIMEOUT_MS));
    }

    private static void addIfMissing(List<Change> changes, Map<String, Object> repository, String key, int value) {
        if (!repository.containsKey(key)) {
            changes.add(new Change(EffectiveConfig.RepositoryConfig.SECTION_KEY, key, OptionalInt.empty(), value));
        }
    }

    // The runtime loader reads YAML maps with String keys, so a nested map has String keys too.
    @SuppressWarnings("unchecked")
    private static Optional<Map<String, Object>> section(Map<String, Object> settings, String key) {
        return settings.get(key) instanceof Map<?, ?> section
                ? Optional.of((Map<String, Object>) section)
                : Optional.empty();
    }

    private static Optional<Map<String, Object>> settings(Path workflowPath, String content) {
        try {
            return Optional.of(new WorkflowLoader()
                    .parse(workflowPath, content.getBytes(UTF_8))
                    .config());
        } catch (WorkflowException e) {
            // The body migration reports an unreadable front matter; this update leaves it alone.
            return Optional.empty();
        }
    }

    /// Applies every change as a line edit, or returns empty when a change has no line it can edit,
    /// for example in a flow-style or quoted value.
    private static Optional<String> edit(WorkflowFileText text, List<Change> changes) {
        List<String> lines = splitKeepingLineBreaks(text.metadata());
        for (Change change : changes) {
            if (!apply(lines, change)) {
                return Optional.empty();
            }
        }
        return Optional.of(String.join("", lines) + text.body());
    }

    private static boolean apply(List<String> lines, Change change) {
        return Block.find(lines, change.section())
                .map(block -> applyInBlock(lines, block, change))
                .orElse(false);
    }

    private static boolean applyInBlock(List<String> lines, Block block, Change change) {
        return change.from().isPresent() ? replaceValue(lines, block, change) : insertLine(lines, block, change);
    }

    private static boolean replaceValue(List<String> lines, Block block, Change change) {
        Pattern settingLine = Pattern.compile(Pattern.quote(block.childIndent() + change.key() + ":") + "([ \\t]+)"
                + change.from().orElseThrow() + "([ \\t]*(?:#.*)?)");
        List<LineMatch> matches = new ArrayList<>();
        for (int index = block.firstChild(); index < block.end(); index++) {
            Matcher matcher = settingLine.matcher(withoutLineBreak(lines.get(index)));
            if (matcher.matches()) {
                matches.add(new LineMatch(index, matcher));
            }
        }
        if (matches.size() != 1) {
            return false;
        }
        LineMatch match = matches.getFirst();
        lines.set(
                match.index(),
                block.childIndent() + change.key() + ":" + match.matcher().group(1) + change.to()
                        + match.matcher().group(2) + lineBreak(lines.get(match.index())));
        return true;
    }

    private record LineMatch(int index, Matcher matcher) {}

    /// Adds the setting after the block's last setting line, with the block's indentation and line break.
    private static boolean insertLine(List<String> lines, Block block, Change change) {
        int last = block.lastContentLine(lines);
        if (last < block.firstChild()) {
            return false;
        }
        lines.add(last + 1, block.childIndent() + change.key() + ": " + change.to() + lineBreak(lines.get(last)));
        return true;
    }

    private static boolean parsesTo(Path workflowPath, String updated, String original, List<Change> changes) {
        return settings(workflowPath, updated)
                .flatMap(actual -> settings(workflowPath, original)
                        .map(expected -> withChanges(expected, changes))
                        .map(actual::equals))
                .orElse(false);
    }

    /// The parsed settings with every planned change applied, as the edited file should parse.
    private static Map<String, Object> withChanges(Map<String, Object> settings, List<Change> changes) {
        for (Change change : changes) {
            section(settings, change.section()).ifPresent(section -> section.put(change.key(), change.to()));
        }
        return settings;
    }

    private static List<String> splitKeepingLineBreaks(String text) {
        List<String> lines = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int lineFeed = text.indexOf(WorkflowFileText.LF, start);
            int end = lineFeed < 0 ? text.length() : lineFeed + 1;
            lines.add(text.substring(start, end));
            start = end;
        }
        return lines;
    }

    private static String withoutLineBreak(String line) {
        return line.substring(0, line.length() - lineBreak(line).length());
    }

    private static String lineBreak(String line) {
        if (line.endsWith(WorkflowFileText.CRLF)) {
            return WorkflowFileText.CRLF;
        }
        return line.endsWith(WorkflowFileText.LF) ? WorkflowFileText.LF : "";
    }

    private static boolean contentLine(String line) {
        String stripped = withoutLineBreak(line).strip();
        return !stripped.isEmpty() && !stripped.startsWith(COMMENT_START);
    }

    /// One planned setting change. `from` is the value being replaced, or empty for a missing key.
    record Change(String section, String key, OptionalInt from, int to) {
        Change {
            Objects.requireNonNull(section, "section");
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(from, "from");
        }

        String path() {
            return section + "." + key;
        }
    }

    /// The planned update for one workflow. `updatedContent` is empty when Symphony cannot make the
    /// changes safely, so the operator has to make them by hand.
    record Plan(WorkflowFileText original, List<Change> changes, Optional<String> updatedContent) {
        Plan {
            changes = List.copyOf(changes);
        }
    }

    /// The lines of one top-level front matter key: the key line at `keyLine`, its child lines from
    /// `firstChild` up to `end`, and the indentation of its direct children.
    private record Block(int keyLine, int end, String childIndent) {
        static Optional<Block> find(List<String> lines, String key) {
            // Line 0 opens the front matter and the last line closes it.
            int closing = lines.size() - 1;
            List<Integer> keyLines = new ArrayList<>();
            for (int index = 1; index < closing; index++) {
                Matcher matcher = TOP_LEVEL_KEY.matcher(withoutLineBreak(lines.get(index)));
                if (matcher.matches() && matcher.group(1).equals(key)) {
                    keyLines.add(index);
                }
            }
            if (keyLines.size() != 1) {
                return Optional.empty();
            }
            int keyLine = keyLines.getFirst();
            int end = keyLine + 1;
            while (end < closing && !startsTopLevelLine(lines.get(end))) {
                end++;
            }
            for (int index = keyLine + 1; index < end; index++) {
                if (contentLine(lines.get(index))) {
                    Matcher indent = LEADING_SPACES.matcher(lines.get(index));
                    return indent.find() ? Optional.of(new Block(keyLine, end, indent.group(1))) : Optional.empty();
                }
            }
            return Optional.empty();
        }

        private static boolean startsTopLevelLine(String line) {
            return contentLine(line) && !Character.isWhitespace(line.charAt(0));
        }

        int firstChild() {
            return keyLine + 1;
        }

        int lastContentLine(List<String> lines) {
            for (int index = end - 1; index > keyLine; index--) {
                if (contentLine(lines.get(index))) {
                    return index;
                }
            }
            return keyLine;
        }
    }
}
