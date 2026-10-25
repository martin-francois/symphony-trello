package ch.fmartin.symphony.trello.setup;

import static ch.fmartin.symphony.trello.setup.WorkflowBodyClassification.ALREADY_CURRENT;
import static ch.fmartin.symphony.trello.setup.WorkflowBodyClassification.DUPLICATE_GENERATED_BODY;
import static ch.fmartin.symphony.trello.setup.WorkflowBodyClassification.INTERNALLY_CUSTOMIZED;
import static ch.fmartin.symphony.trello.setup.WorkflowBodyClassification.NO_GENERATED_BODY_CHANGE;
import static ch.fmartin.symphony.trello.setup.WorkflowBodyClassification.UNCHANGED_GENERATED_BODY;
import static ch.fmartin.symphony.trello.setup.WorkflowBodyClassification.UNKNOWN_SOURCE;
import static ch.fmartin.symphony.trello.setup.WorkflowBodyClassification.USER_PREFIX_SUFFIX;
import static org.assertj.core.api.Assertions.assertThat;

import ch.fmartin.symphony.trello.setup.WorkflowBodyClassifier.WorkflowBodyAssessment;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

final class WorkflowBodyClassifierTest {
    private static final String SOURCE = "# Trello Card\n\nOld step one.\nOld step two.\nOld step three.\n";
    private static final String TARGET = "# Trello Card\n\nNew step one.\nOld step two.\nOld step three.\n";
    private static final String PREFIX = "Team rule: answer in German.\n\n";
    private static final String SUFFIX = "\nAlways run the smoke tests.\n";
    private static final String APPENDED_SECTION = "\n## Newer Section\n\nOnly the newer version has this.\n";

    @MethodSource("classificationCases")
    @ParameterizedTest(name = "{0}")
    void classifiesWorkflowBodiesByExactGeneratedText(
            String scenario, String body, Optional<String> source, WorkflowBodyAssessment expected) {
        // given
        String target = TARGET;

        // when
        WorkflowBodyAssessment assessment = WorkflowBodyClassifier.classify(body, source, target);

        // then
        assertThat(assessment).as(scenario).isEqualTo(expected);
    }

    @MethodSource("nestedGeneratedBodyCases")
    @ParameterizedTest(name = "{0}")
    void countsAGeneratedBodyInsideTheOtherAsPartOfThatBlock(
            String scenario, String body, String source, String target, WorkflowBodyAssessment expected) {
        // given
        Optional<String> recordedSource = Optional.of(source);

        // when
        WorkflowBodyAssessment assessment = WorkflowBodyClassifier.classify(body, recordedSource, target);

        // then
        assertThat(assessment).as(scenario).isEqualTo(expected);
    }

    static Stream<Arguments> nestedGeneratedBodyCases() {
        String longer = TARGET + APPENDED_SECTION;
        return Stream.of(
                Arguments.of(
                        "downgrade from a body that only appended a section",
                        longer,
                        longer,
                        TARGET,
                        new WorkflowBodyAssessment(UNCHANGED_GENERATED_BODY, 0, longer.length())),
                Arguments.of(
                        "downgrade with user text around the longer body",
                        PREFIX + longer + SUFFIX,
                        longer,
                        TARGET,
                        new WorkflowBodyAssessment(USER_PREFIX_SUFFIX, PREFIX.length(), (PREFIX + longer).length())),
                Arguments.of(
                        "update that only appended a section",
                        PREFIX + longer,
                        TARGET,
                        longer,
                        WorkflowBodyAssessment.of(ALREADY_CURRENT)),
                Arguments.of(
                        "old and new generated blocks both present",
                        SOURCE + TARGET,
                        SOURCE,
                        TARGET,
                        WorkflowBodyAssessment.of(DUPLICATE_GENERATED_BODY)));
    }

    static Stream<Arguments> classificationCases() {
        return Stream.of(
                Arguments.of(
                        "no generated body change, even with edits",
                        "edited " + SOURCE,
                        Optional.of(TARGET),
                        WorkflowBodyAssessment.of(NO_GENERATED_BODY_CHANGE)),
                Arguments.of(
                        "already current", TARGET, Optional.of(SOURCE), WorkflowBodyAssessment.of(ALREADY_CURRENT)),
                Arguments.of(
                        "already current with user prefix and suffix",
                        PREFIX + TARGET + SUFFIX,
                        Optional.of(SOURCE),
                        WorkflowBodyAssessment.of(ALREADY_CURRENT)),
                Arguments.of(
                        "unversioned body that is already current",
                        TARGET,
                        Optional.empty(),
                        WorkflowBodyAssessment.of(ALREADY_CURRENT)),
                Arguments.of(
                        "exact source body",
                        SOURCE,
                        Optional.of(SOURCE),
                        new WorkflowBodyAssessment(UNCHANGED_GENERATED_BODY, 0, SOURCE.length())),
                Arguments.of(
                        "prefix only",
                        PREFIX + SOURCE,
                        Optional.of(SOURCE),
                        new WorkflowBodyAssessment(USER_PREFIX_SUFFIX, PREFIX.length(), (PREFIX + SOURCE).length())),
                Arguments.of(
                        "suffix only",
                        SOURCE + SUFFIX,
                        Optional.of(SOURCE),
                        new WorkflowBodyAssessment(USER_PREFIX_SUFFIX, 0, SOURCE.length())),
                Arguments.of(
                        "prefix and suffix",
                        PREFIX + SOURCE + SUFFIX,
                        Optional.of(SOURCE),
                        new WorkflowBodyAssessment(USER_PREFIX_SUFFIX, PREFIX.length(), (PREFIX + SOURCE).length())),
                Arguments.of(
                        "internal edit",
                        SOURCE.replace("Old step two.", "My own step two."),
                        Optional.of(SOURCE),
                        WorkflowBodyAssessment.of(INTERNALLY_CUSTOMIZED)),
                Arguments.of(
                        "reordered text",
                        "# Trello Card\n\nOld step two.\nOld step one.\nOld step three.\n",
                        Optional.of(SOURCE),
                        WorkflowBodyAssessment.of(INTERNALLY_CUSTOMIZED)),
                Arguments.of(
                        "deleted text",
                        SOURCE.replace("Old step three.\n", ""),
                        Optional.of(SOURCE),
                        WorkflowBodyAssessment.of(INTERNALLY_CUSTOMIZED)),
                Arguments.of(
                        "duplicate generated blocks",
                        SOURCE + SUFFIX + SOURCE,
                        Optional.of(SOURCE),
                        WorkflowBodyAssessment.of(DUPLICATE_GENERATED_BODY)),
                Arguments.of("unknown source", SOURCE, Optional.empty(), WorkflowBodyAssessment.of(UNKNOWN_SOURCE)),
                Arguments.of(
                        "blank recorded source", SOURCE, Optional.of(""), WorkflowBodyAssessment.of(UNKNOWN_SOURCE)));
    }
}
