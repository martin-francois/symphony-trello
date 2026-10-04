package ch.fmartin.symphony.trello.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.google.common.base.CaseFormat;
import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

final class CardNullnessTest {
    private static final String CARD_SECTION_START = "#### 4.1.1 Card";
    private static final String CARD_SECTION_END = "#### 4.1.2 ";
    private static final String TOP_LEVEL_INDENT = "";
    private static final String NESTED_INDENT = "    ";
    private static final Pattern FIELD = Pattern.compile("^(\\s*)- `([a-z_]+)` \\(([^)]+)\\)$");
    private static final String NULLABLE_FIELD_TYPE_SUFFIX = " or null";

    static List<Arguments> specifiedRecords() {
        return List.of(
                arguments(Card.class, CARD_SECTION_START, TOP_LEVEL_INDENT),
                arguments(Card.Checklist.class, "  - Each checklist record contains:", NESTED_INDENT),
                arguments(Card.ChecklistItem.class, "  - Each checklist item record contains:", NESTED_INDENT),
                arguments(Card.Attachment.class, "  - Each attachment record contains:", NESTED_INDENT),
                arguments(Card.TrelloReference.class, "  - Each reference record contains:", NESTED_INDENT),
                arguments(Card.PrerequisiteProblem.class, "  - Each problem record contains:", NESTED_INDENT),
                arguments(BlockerRef.class, "  - Each blocker ref contains:", NESTED_INDENT),
                arguments(Card.Comment.class, "  - Each comment record contains:", NESTED_INDENT));
    }

    @MethodSource("specifiedRecords")
    @ParameterizedTest(name = "{0}")
    void recordComponentsAreNullableExactlyWhereTheSpecificationAllowsNull(
            Class<? extends Record> record, String specificationIntro, String fieldIndent) throws IOException {
        // given
        List<String> cardSection = cardSection(Files.readAllLines(Path.of("SPEC.md")));

        // when
        SpecifiedFields specified = specifiedFields(cardSection, specificationIntro, fieldIndent);

        // then
        assertThat(record.getRecordComponents())
                .as("%s components should match the fields SPEC.md lists after \"%s\"", record, specificationIntro)
                .extracting(RecordComponent::getName)
                .containsExactlyInAnyOrderElementsOf(specified.names());
        assertThat(record.getRecordComponents())
                .as("%s components annotated @Nullable should match the SPEC.md fields typed \"or null\"", record)
                .filteredOn(CardNullnessTest::nullable)
                .extracting(RecordComponent::getName)
                .containsExactlyInAnyOrderElementsOf(specified.nullableNames());
    }

    private static List<String> cardSection(List<String> specification) {
        int start = specification.indexOf(CARD_SECTION_START);
        List<String> section = new ArrayList<>();
        if (start < 0) {
            return section;
        }
        for (String line : specification.subList(start, specification.size())) {
            if (line.startsWith(CARD_SECTION_END)) {
                break;
            }
            section.add(line);
        }
        return section;
    }

    private static SpecifiedFields specifiedFields(
            List<String> cardSection, String specificationIntro, String fieldIndent) {
        var specified = new SpecifiedFields(new ArrayList<>(), new ArrayList<>());
        int intro = cardSection.indexOf(specificationIntro);
        if (intro < 0) {
            return specified;
        }
        for (String line : cardSection.subList(intro + 1, cardSection.size())) {
            if (!fieldIndent.isEmpty() && !line.startsWith(fieldIndent)) {
                break;
            }
            Matcher field = FIELD.matcher(line);
            if (field.matches() && field.group(1).equals(fieldIndent)) {
                String name = CaseFormat.LOWER_UNDERSCORE.to(CaseFormat.LOWER_CAMEL, field.group(2));
                specified.names().add(name);
                if (field.group(3).endsWith(NULLABLE_FIELD_TYPE_SUFFIX)) {
                    specified.nullableNames().add(name);
                }
            }
        }
        return specified;
    }

    private static boolean nullable(RecordComponent component) {
        return component.getAnnotatedType().isAnnotationPresent(Nullable.class);
    }

    private record SpecifiedFields(List<String> names, List<String> nullableNames) {}
}
