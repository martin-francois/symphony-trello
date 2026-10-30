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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.NullMarked;

/// Temporary front matter migration that raises `codex.turn_timeout_ms` in workflows written before
/// Symphony 2.0.0 from the old generated default to the current default, so a full Codex review fits
/// inside a turn (ADR 0136). It adds no review-loop settings, so a migrated workflow keeps following
/// the review-loop defaults of the running version.
///
/// The change is a text edit of the one affected line, so comments, quoting, and every other line
/// stay as they are. A planned edit counts only when the edited front matter parses to the original
/// settings with exactly that value changed.
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
    private static final Change TURN_TIMEOUT_CHANGE = new Change(
            EffectiveConfig.CodexConfig.SECTION_KEY,
            EffectiveConfig.CodexConfig.TURN_TIMEOUT_MS_KEY,
            RELEASED_GENERATED_TURN_TIMEOUT_MS,
            TARGET_TURN_TIMEOUT_MS);

    private CodexTimeoutSettingsMigration() {}

    /// Plans the update for one workflow file. Returns empty when the workflow needs no change or
    /// its front matter cannot be read.
    static Optional<Plan> plan(Path workflowPath, String content) {
        return WorkflowFileText.parse(content).flatMap(text -> plan(workflowPath, text));
    }

    private static Optional<Plan> plan(Path workflowPath, WorkflowFileText text) {
        boolean needsUpdate = settings(workflowPath, text.content())
                .filter(CodexTimeoutSettingsMigration::hasReleasedGeneratedTurnTimeout)
                .isPresent();
        return needsUpdate
                ? Optional.of(new Plan(text, TURN_TIMEOUT_CHANGE, verifiedEdit(workflowPath, text)))
                : Optional.empty();
    }

    private static boolean hasReleasedGeneratedTurnTimeout(Map<String, Object> settings) {
        return section(settings, TURN_TIMEOUT_CHANGE.section())
                .map(codex -> codex.get(TURN_TIMEOUT_CHANGE.key()))
                .filter(Integer.valueOf(RELEASED_GENERATED_TURN_TIMEOUT_MS)::equals)
                .isPresent();
    }

    private static Optional<String> verifiedEdit(Path workflowPath, WorkflowFileText text) {
        return edit(text).filter(candidate -> parsesTo(workflowPath, candidate, text.content()));
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

    /// Replaces the old value on its own line, or returns empty when there is no such line, for
    /// example in a flow-style or quoted value.
    private static Optional<String> edit(WorkflowFileText text) {
        List<String> lines = splitKeepingLineBreaks(text.metadata());
        return Block.find(lines, TURN_TIMEOUT_CHANGE.section())
                .flatMap(block -> replaceValue(lines, block, TURN_TIMEOUT_CHANGE))
                .map(metadata -> metadata + text.body());
    }

    /// The metadata with the value of `change` replaced, or empty unless exactly one line of the
    /// block holds the old value.
    private static Optional<String> replaceValue(List<String> lines, Block block, Change change) {
        Pattern settingLine = Pattern.compile(Pattern.quote(block.childIndent() + change.key() + ":") + "([ \\t]+)"
                + change.from() + "([ \\t]*(?:#.*)?)");
        List<LineMatch> matches = new ArrayList<>();
        for (int index = block.firstChild(); index < block.end(); index++) {
            Matcher matcher = settingLine.matcher(withoutLineBreak(lines.get(index)));
            if (matcher.matches()) {
                matches.add(new LineMatch(index, matcher));
            }
        }
        if (matches.size() != 1) {
            return Optional.empty();
        }
        LineMatch match = matches.getFirst();
        List<String> edited = new ArrayList<>(lines);
        edited.set(
                match.index(),
                block.childIndent() + change.key() + ":" + match.matcher().group(1) + change.to()
                        + match.matcher().group(2) + lineBreak(lines.get(match.index())));
        return Optional.of(String.join("", edited));
    }

    private record LineMatch(int index, Matcher matcher) {}

    private static boolean parsesTo(Path workflowPath, String updated, String original) {
        return settings(workflowPath, updated)
                .flatMap(actual -> settings(workflowPath, original)
                        .map(CodexTimeoutSettingsMigration::withChange)
                        .map(actual::equals))
                .orElse(false);
    }

    /// The parsed settings with the planned change applied, as the edited file should parse.
    private static Map<String, Object> withChange(Map<String, Object> settings) {
        section(settings, TURN_TIMEOUT_CHANGE.section())
                .ifPresent(section -> section.put(TURN_TIMEOUT_CHANGE.key(), TURN_TIMEOUT_CHANGE.to()));
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

    /// One planned setting change from the value `from` to the value `to`.
    record Change(String section, String key, int from, int to) {
        Change {
            Objects.requireNonNull(section, "section");
            Objects.requireNonNull(key, "key");
        }

        String path() {
            return section + "." + key;
        }
    }

    /// The planned update for one workflow. `updatedContent` is empty when Symphony cannot make the
    /// change safely, so the operator has to make it by hand.
    record Plan(WorkflowFileText original, Change change, Optional<String> updatedContent) {}

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
    }
}
