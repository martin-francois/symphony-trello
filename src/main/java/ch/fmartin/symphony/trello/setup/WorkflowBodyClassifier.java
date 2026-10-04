package ch.fmartin.symphony.trello.setup;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.NullMarked;

/// Classifies a workflow body against the source and target generated bodies (ADR 0095). All texts
/// must use LF line breaks. The classifier only matches exact text: it never guesses which parts of an
/// edited body are generated, so anything that is not an exact, unique match needs a manual migration.
///
/// One generated body can contain the other, for example when a version only appends a section. An
/// occurrence of one body inside an occurrence of the other belongs to that larger block and is not
/// counted as a separate generated block.
@NullMarked
final class WorkflowBodyClassifier {
    private WorkflowBodyClassifier() {}

    static WorkflowBodyAssessment classify(String body, Optional<String> sourceBody, String targetBody) {
        Optional<String> source = sourceBody.filter(text -> !text.isBlank());
        if (source.map(targetBody::equals).orElse(false)) {
            return WorkflowBodyAssessment.of(WorkflowBodyClassification.NO_GENERATED_BODY_CHANGE);
        }
        Occurrences target = Occurrences.of(body, targetBody);
        Occurrences sourceBlocks =
                source.map(text -> Occurrences.of(body, text)).orElseGet(Occurrences::none);
        if (target.starts().size() == 1 && sourceBlocks.allInside(target)) {
            return WorkflowBodyAssessment.of(WorkflowBodyClassification.ALREADY_CURRENT);
        }
        if (!target.allInside(sourceBlocks) || sourceBlocks.starts().size() > 1) {
            return WorkflowBodyAssessment.of(WorkflowBodyClassification.DUPLICATE_GENERATED_BODY);
        }
        if (source.isEmpty()) {
            return WorkflowBodyAssessment.of(WorkflowBodyClassification.UNKNOWN_SOURCE);
        }
        if (sourceBlocks.starts().isEmpty()) {
            return WorkflowBodyAssessment.of(WorkflowBodyClassification.INTERNALLY_CUSTOMIZED);
        }
        int start = sourceBlocks.starts().getFirst();
        WorkflowBodyClassification classification = body.length() == sourceBlocks.length()
                ? WorkflowBodyClassification.UNCHANGED_GENERATED_BODY
                : WorkflowBodyClassification.USER_PREFIX_SUFFIX;
        return new WorkflowBodyAssessment(classification, start, start + sourceBlocks.length());
    }

    /// Every start offset of one text in the body, including overlapping ones.
    private record Occurrences(List<Integer> starts, int length) {
        static Occurrences none() {
            return new Occurrences(List.of(), 0);
        }

        static Occurrences of(String body, String text) {
            List<Integer> starts = new ArrayList<>();
            for (int start = body.indexOf(text); !text.isEmpty() && start >= 0; start = body.indexOf(text, start + 1)) {
                starts.add(start);
            }
            return new Occurrences(List.copyOf(starts), text.length());
        }

        /// Whether every occurrence lies inside some occurrence of `outer`.
        boolean allInside(Occurrences outer) {
            return starts.stream().allMatch(inner -> outer.starts().stream()
                    .anyMatch(start -> start <= inner && inner + length <= start + outer.length()));
        }
    }

    /// The classification plus, for an automatic migration, the LF offsets of the one source body
    /// block that a migration replaces. Offsets are zero when there is no such block.
    record WorkflowBodyAssessment(WorkflowBodyClassification classification, int sourceStart, int sourceEnd) {
        static WorkflowBodyAssessment of(WorkflowBodyClassification classification) {
            return new WorkflowBodyAssessment(classification, 0, 0);
        }
    }
}
