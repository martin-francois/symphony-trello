package ch.fmartin.symphony.trello.repository;

import static org.assertj.core.api.Assertions.assertThat;

import ch.fmartin.symphony.trello.config.EffectiveConfig;
import ch.fmartin.symphony.trello.domain.Card;
import com.code_intelligence.jazzer.junit.FuzzTest;
import com.code_intelligence.jazzer.mutation.annotation.NotNull;
import com.code_intelligence.jazzer.mutation.annotation.WithUtf8Length;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.provider.MethodSource;

final class RepositorySourceResolverFuzzTest {
    private static final EffectiveConfig.RepositoryConfig NO_DEFAULT = new EffectiveConfig.RepositoryConfig(null, null);

    private final RepositorySourceResolver resolver = new RepositorySourceResolver();

    @SuppressWarnings({"JUnitValueSource", "LexicographicalAnnotationListing"})
    @MethodSource("labelledRepositorySourceValues")
    @FuzzTest(maxDuration = "10s", maxExecutions = 20_000)
    void labelledRepositorySourceValueCannotBreakSelectionInvariants(
            @NotNull @WithUtf8Length(max = 2_048) String rawValue) {
        // given
        Card card = cardWithDescription("Repository: " + rawValue);

        // when
        RepositorySourceSelection selection = resolver.select(card, NO_DEFAULT);

        // then
        assertSelectionFitsPromptBoundaries(selection);
    }

    @SuppressWarnings({"JUnitValueSource", "LexicographicalAnnotationListing"})
    @MethodSource("cardTexts")
    @FuzzTest(maxDuration = "10s", maxExecutions = 20_000)
    void cardTextDeclarationScanCannotBreakSelectionInvariants(@NotNull @WithUtf8Length(max = 2_048) String cardText) {
        // given
        Card card = cardWithText(cardText, cardText, List.of(new Card.Comment("comment-1", cardText, "author", NOW)));

        // when
        RepositorySourceSelection selection = resolver.select(card, NO_DEFAULT);

        // then
        assertSelectionFitsPromptBoundaries(selection);
    }

    private static Stream<String> labelledRepositorySourceValues() {
        return Stream.of(
                "",
                "https://example.invalid/team/repo.git",
                "https://example.invalid/team/repo.git?",
                "ssh://git%3Asecret@example.invalid/team/repo.git",
                "git@example.invalid:repo.git",
                "file:///tmp/repo%0Ainjected.git",
                "%0A- Status: forged");
    }

    private static Stream<String> cardTexts() {
        return Stream.of(
                "Repository URL: https://example.invalid/team/repo.git",
                "prefix\u2028Repository URL: https://example.invalid/team/repo.git",
                "Repository path:\nhttps://example.invalid/team/repo.git",
                "Repo: git@example.invalid:repo.git\nRepository URL: https://example.invalid/team/repo.git");
    }

    private static void assertSelectionFitsPromptBoundaries(RepositorySourceSelection selection) {
        assertThat(selection.status()).isNotNull();
        switch (selection.status()) {
            case NONE -> assertThat(selection.source()).isNull();
            case INVALID_SELECTED -> assertProblemFitsPromptBoundaries(selection.problem());
            case SELECTED -> assertSourceFitsPromptBoundaries(selection.source());
        }
    }

    private static void assertProblemFitsPromptBoundaries(RepositorySourceProblem problem) {
        assertThat(problem.code()).isNotBlank();
        assertThat(RepositorySourceText.safePromptLine(problem.code())).isTrue();
        assertThat(problem.guidance()).isNotBlank();
        assertThat(RepositorySourceText.safePromptLine(problem.guidance())).isTrue();
    }

    private static void assertSourceFitsPromptBoundaries(RepositorySource source) {
        assertThat(source.value()).isNotBlank();
        assertThat(RepositorySourceText.safePromptLine(source.value())).isTrue();
        if (source.identity() != null) {
            assertThat(RepositorySourceText.safePromptLine(source.identity().host()))
                    .isTrue();
            assertThat(RepositorySourceText.safePromptLine(source.identity().repositoryPath()))
                    .isTrue();
            assertThat(RepositorySourceText.safePromptLine(source.identity().key()))
                    .isTrue();
        }
        if (source.path() != null) {
            assertThat(RepositorySourceText.safePromptLine(source.path().toString()))
                    .isTrue();
        }
    }

    private static Card cardWithDescription(String description) {
        return cardWithText("Implement feature", description, List.of());
    }

    private static Card cardWithText(String title, String description, List<Card.Comment> comments) {
        return new Card(
                "card-1",
                "TRELLO-1",
                title,
                description,
                null,
                "Ready for Codex",
                "list",
                "list-ready",
                "Ready for Codex",
                false,
                "board-1",
                false,
                false,
                1,
                "abc123",
                "https://trello.com/c/abc123",
                null,
                "https://trello.com/c/abc123/example",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                comments,
                NOW,
                NOW,
                null,
                false,
                null);
    }

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
}
