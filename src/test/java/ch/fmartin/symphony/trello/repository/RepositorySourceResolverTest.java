package ch.fmartin.symphony.trello.repository;

import static org.assertj.core.api.Assertions.assertThat;

import ch.fmartin.symphony.trello.TestCards;
import ch.fmartin.symphony.trello.config.EffectiveConfig;
import ch.fmartin.symphony.trello.domain.Card;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

final class RepositorySourceResolverTest {
    private final RepositorySourceResolver resolver = new RepositorySourceResolver();

    @TempDir
    Path tempDir;

    @CsvSource({
        "Repository URL: https://github.example/team/project.git, https://github.example/team/project.git, github.example/team/project",
        "Repository URL: ssh://git@git.example/team/project.git, ssh://git@git.example/team/project.git, git.example/team/project",
        "Repository: deploy@git.example:team/project.git, deploy@git.example:team/project.git, git.example/team/project",
        "Repository URL: deploy@git.example:repo.git, deploy@git.example:repo.git, git.example/repo"
    })
    @ParameterizedTest(name = "{0}")
    void selectsExplicitCredentialFreeRemote(String cardText, String remote, String identity) {
        // given
        Card card = cardWithDescription(cardText);

        // when
        RepositorySourceSelection selection = resolver.select(card, noDefault());

        // then
        assertThat(selection.status()).isEqualTo(RepositorySourceSelection.Status.SELECTED);
        assertThat(selection.source().kind()).isEqualTo(RepositorySource.Kind.REMOTE);
        assertThat(selection.source().origin()).isEqualTo(RepositorySource.Origin.CARD);
        assertThat(selection.source().value()).isEqualTo(remote);
        assertThat(selection.source().identity().key()).isEqualTo(identity);
    }

    @Test
    void preservesSafeUriPercentEncodingWithoutCreatingDelimitersOrSpaces() {
        // given
        String repository = "https://github.example/team/%3Fquery%23fragment%20space%25percent%2Fslash.git";
        Card card = cardWithDescription("Repository URL: " + repository);

        // when
        RepositorySourceSelection selection = resolver.select(card, noDefault());

        // then
        assertThat(selection.status()).isEqualTo(RepositorySourceSelection.Status.SELECTED);
        assertThat(selection.source().value()).isEqualTo(repository);
        assertThat(selection.source().identity().repositoryPath())
                .isEqualTo("team/%3Fquery%23fragment%20space%25percent%2Fslash");
        assertThat(selection.source().value())
                .doesNotContain(" ")
                .doesNotContain("?")
                .doesNotContain("#");
    }

    @Test
    void normalizesFileUriIntoLocalSource() {
        // given
        Path repository = tempDir.resolve("repo").toAbsolutePath().normalize();
        Card card = cardWithDescription("Repository: " + repository.toUri());

        // when
        RepositorySourceSelection selection = resolver.select(card, noDefault());

        // then
        assertThat(selection.status()).isEqualTo(RepositorySourceSelection.Status.SELECTED);
        assertThat(selection.source().kind()).isEqualTo(RepositorySource.Kind.LOCAL_PATH);
        assertThat(selection.source().path()).isEqualTo(repository);
    }

    @Test
    void rejectsFileUriWhoseDecodedPathContainsControlCharacters() {
        // given
        Card card = cardWithDescription("Repository: file:///tmp/repo%0Ainjected.git");

        // when
        RepositorySourceSelection selection = resolver.select(card, noDefault());

        // then
        assertThat(selection.status()).isEqualTo(RepositorySourceSelection.Status.INVALID_SELECTED);
        assertThat(selection.problem().code()).isEqualTo("repository_path_malformed");
    }

    @CsvSource({"file:///tmp/repo.git?", "file:///tmp/repo.git#"})
    @ParameterizedTest(name = "{0}")
    void rejectsFileUriWithEmptyQueryOrFragmentMarker(String repositoryUrl) {
        // given
        Card card = cardWithDescription("Repository: " + repositoryUrl);

        // when
        RepositorySourceSelection selection = resolver.select(card, noDefault());

        // then
        assertThat(selection.status()).isEqualTo(RepositorySourceSelection.Status.INVALID_SELECTED);
        assertThat(selection.problem().code()).isEqualTo("repository_path_malformed");
    }

    @Test
    void selectsExplicitLocalPathFromComment() {
        // given
        Path repository = tempDir.resolve("source checkout").toAbsolutePath().normalize();
        Card card = TestCards.cardWithText(
                "card-1",
                "TRELLO-source",
                "Ready for Codex",
                "Implement feature",
                "No repository here",
                List.of(comment("Repository path: <" + repository + ">")));

        // when
        RepositorySourceSelection selection = resolver.select(card, noDefault());

        // then
        assertThat(selection.status()).isEqualTo(RepositorySourceSelection.Status.SELECTED);
        assertThat(selection.source().kind()).isEqualTo(RepositorySource.Kind.LOCAL_PATH);
        assertThat(selection.source().path()).isEqualTo(repository);
    }

    @Test
    void selectsGenericWindowsDrivePathAsLocalSourceWithoutRealDrive() {
        // given
        Card card = cardWithDescription("Repository: C:/work/repo.git");

        // when
        RepositorySourceSelection selection = resolver.select(card, noDefault());

        // then
        assertThat(selection.status()).isEqualTo(RepositorySourceSelection.Status.SELECTED);
        assertThat(selection.source().kind()).isEqualTo(RepositorySource.Kind.LOCAL_PATH);
        assertThat(selection.source().identity()).isNull();
    }

    @Test
    void selectsNativeWindowsPathAsLocalSourceWithoutRealDrive() {
        // given
        Card card = cardWithDescription("Repository: C:\\work\\repo.git");

        // when
        RepositorySourceSelection selection = resolver.select(card, noDefault());

        // then
        assertThat(selection.status()).isEqualTo(RepositorySourceSelection.Status.SELECTED);
        assertThat(selection.source().kind()).isEqualTo(RepositorySource.Kind.LOCAL_PATH);
        assertThat(selection.source().identity()).isNull();
    }

    @Test
    void rejectsWindowsDrivePathWhenSourceIsUrlLabelled() {
        // given
        Card card = cardWithDescription("Repository URL: C:/work/repo.git");

        // when
        RepositorySourceSelection selection = resolver.select(card, noDefault());

        // then
        assertThat(selection.status()).isEqualTo(RepositorySourceSelection.Status.INVALID_SELECTED);
        assertThat(selection.problem().code()).isEqualTo("repository_remote_unsupported");
    }

    @Test
    void ignoresUnlabelledOrdinaryWebLinks() {
        // given
        Card card = cardWithDescription("See https://github.example/team/project.git for background.");

        // when
        RepositorySourceSelection selection = resolver.select(card, noDefault());

        // then
        assertThat(selection.status()).isEqualTo(RepositorySourceSelection.Status.NONE);
    }

    @Test
    void blankUrlLabelInTitleSuppressesWorkflowDefault() {
        // given
        Card card = cardWithText("Repository URL:", "No source in description", List.of());
        EffectiveConfig.RepositoryConfig repository =
                new EffectiveConfig.RepositoryConfig("https://github.example/team/default.git", null);

        // when
        RepositorySourceSelection selection = resolver.select(card, repository);

        // then
        assertThat(selection.status()).isEqualTo(RepositorySourceSelection.Status.INVALID_SELECTED);
        assertThat(selection.problem().code()).isEqualTo("repository_source_missing");
    }

    @Test
    void blankPathLabelInDescriptionSuppressesWorkflowDefault() {
        // given
        Card card = cardWithDescription("Repository path:");
        EffectiveConfig.RepositoryConfig repository =
                new EffectiveConfig.RepositoryConfig("https://github.example/team/default.git", null);

        // when
        RepositorySourceSelection selection = resolver.select(card, repository);

        // then
        assertThat(selection.status()).isEqualTo(RepositorySourceSelection.Status.INVALID_SELECTED);
        assertThat(selection.problem().code()).isEqualTo("repository_source_missing");
    }

    @Test
    void blankLabelInCommentSuppressesWorkflowDefault() {
        // given
        Card card = cardWithText("Implement feature", "No source in description", List.of(comment("Repo:")));
        EffectiveConfig.RepositoryConfig repository =
                new EffectiveConfig.RepositoryConfig("https://github.example/team/default.git", null);

        // when
        RepositorySourceSelection selection = resolver.select(card, repository);

        // then
        assertThat(selection.status()).isEqualTo(RepositorySourceSelection.Status.INVALID_SELECTED);
        assertThat(selection.problem().code()).isEqualTo("repository_source_missing");
    }

    @CsvSource({"Repository URL:, plain prose", "Repository URL:, https://github.example/team/project.git"})
    @ParameterizedTest(name = "{0} then {1}")
    void blankLabelDoesNotConsumeFollowingLine(String labelLine, String nextLine) {
        // given
        Card card = cardWithDescription(labelLine + "\n" + nextLine);

        // when
        RepositorySourceSelection selection = resolver.select(card, noDefault());

        // then
        assertThat(selection.status()).isEqualTo(RepositorySourceSelection.Status.INVALID_SELECTED);
        assertThat(selection.problem().code()).isEqualTo("repository_source_missing");
    }

    @MethodSource("regexLineBreaks")
    @ParameterizedTest(name = "{0}")
    void recognizesRepositoryDeclarationsAfterRegexLineBreaks(String scenario, String lineBreak) {
        // given
        String expectedSource = "https://github.example/team/project.git";
        String description = "prefix" + lineBreak + "Repository URL: " + expectedSource;
        Card card = cardWithDescription(description);

        // when
        RepositorySourceSelection selection = resolver.select(card, noDefault());

        // then
        assertThat(selection.status()).isEqualTo(RepositorySourceSelection.Status.SELECTED);
        assertThat(selection.source().value()).isEqualTo(expectedSource);
    }

    private static Stream<Arguments> regexLineBreaks() {
        return Stream.of(
                Arguments.of("next line", "\n"),
                Arguments.of("CRLF", "\r\n"),
                Arguments.of("NEL", "\u0085"),
                Arguments.of("line separator", "\u2028"),
                Arguments.of("paragraph separator", "\u2029"));
    }

    @Test
    void labelAtEndOfTitleDoesNotConsumeFirstDescriptionLine() {
        // given
        Card card = cardWithText("Repository URL:", "https://github.example/team/project.git", List.of());

        // when
        RepositorySourceSelection selection = resolver.select(card, noDefault());

        // then
        assertThat(selection.status()).isEqualTo(RepositorySourceSelection.Status.INVALID_SELECTED);
        assertThat(selection.problem().code()).isEqualTo("repository_source_missing");
    }

    @Test
    void labelAtEndOfDescriptionDoesNotConsumeFirstCommentLine() {
        // given
        Card card = cardWithText(
                "Implement feature", "Repository URL:", List.of(comment("https://github.example/team/project.git")));

        // when
        RepositorySourceSelection selection = resolver.select(card, noDefault());

        // then
        assertThat(selection.status()).isEqualTo(RepositorySourceSelection.Status.INVALID_SELECTED);
        assertThat(selection.problem().code()).isEqualTo("repository_source_missing");
    }

    @Test
    void acceptsEquivalentDeclarationsFromDifferentFields() {
        // given
        Card card = cardWithText(
                "Repository URL: https://github.example/team/project.git",
                "Repository: https://github.example/team/project.git",
                List.of(comment("Repo URL: https://github.example/team/project.git")));

        // when
        RepositorySourceSelection selection = resolver.select(card, noDefault());

        // then
        assertThat(selection.status()).isEqualTo(RepositorySourceSelection.Status.SELECTED);
        assertThat(selection.source().value()).isEqualTo("https://github.example/team/project.git");
    }

    @Test
    void conflictingDeclarationsFailClosed() {
        // given
        Card card = cardWithText(
                "Repository URL: https://github.example/team/project.git",
                "Repository URL: https://github.example/team/other.git",
                List.of());

        // when
        RepositorySourceSelection selection = resolver.select(card, noDefault());

        // then
        assertThat(selection.status()).isEqualTo(RepositorySourceSelection.Status.INVALID_SELECTED);
        assertThat(selection.problem().code()).isEqualTo("repository_source_conflict");
        assertThat(selection.problem().guidance()).doesNotContain("github.example");
    }

    @Test
    void validPlusMalformedDeclarationsFailClosed() {
        // given
        Card card = cardWithText(
                "Repository URL: https://github.example/team/project.git",
                "Repository URL: ftp://github.example/team/project.git",
                List.of());

        // when
        RepositorySourceSelection selection = resolver.select(card, noDefault());

        // then
        assertThat(selection.status()).isEqualTo(RepositorySourceSelection.Status.INVALID_SELECTED);
        assertThat(selection.problem().code()).isEqualTo("repository_source_conflict");
    }

    @Test
    void explicitSourceSuppressesInvalidWorkflowDefault() {
        // given
        Card card = cardWithDescription("Repository URL: https://github.example/team/card.git");
        EffectiveConfig.RepositoryConfig repository = new EffectiveConfig.RepositoryConfig(
                "https://token@example.invalid/team/default.git?access_token=secret", null);

        // when
        RepositorySourceSelection selection = resolver.select(card, repository);

        // then
        assertThat(selection.status()).isEqualTo(RepositorySourceSelection.Status.SELECTED);
        assertThat(selection.source().value()).isEqualTo("https://github.example/team/card.git");
    }

    @Test
    void invalidExplicitSourceSuppressesEveryWorkflowDefault() {
        // given
        Card card = cardWithDescription("Repository URL: ftp://example.invalid/team/project.git");
        EffectiveConfig.RepositoryConfig repository = new EffectiveConfig.RepositoryConfig(
                "https://github.example/team/default.git", tempDir.resolve("repo"));

        // when
        RepositorySourceSelection selection = resolver.select(card, repository);

        // then
        assertThat(selection.status()).isEqualTo(RepositorySourceSelection.Status.INVALID_SELECTED);
        assertThat(selection.problem().code()).isEqualTo("repository_remote_unsupported");
    }

    @Test
    void invalidExplicitSourceNeverFallsBackToWorkflowDefault() {
        // given
        Card card = cardWithDescription("Repository URL: ftp://example.invalid/team/project.git");
        EffectiveConfig.RepositoryConfig repository =
                new EffectiveConfig.RepositoryConfig("https://github.example/team/default.git", null);

        // when
        RepositorySourceSelection selection = resolver.select(card, repository);

        // then
        assertThat(selection.status()).isEqualTo(RepositorySourceSelection.Status.INVALID_SELECTED);
        assertThat(selection.problem().code()).isEqualTo("repository_remote_unsupported");
    }

    @CsvSource({
        "Repository URL: https://user:secret@example.invalid/team/project.git",
        "Repository URL: https://token@example.invalid/team/project.git",
        "Repository URL: https://example.invalid/team/project.git?access_token=secret",
        "Repository URL: https://example.invalid/team/project.git#secret",
        "Repository URL: ssh://git%3Asecret@example.invalid/team/project.git",
        "Repository URL: ssh://git:secret@example.invalid/team/project.git"
    })
    @ParameterizedTest(name = "{0}")
    void rejectsCredentialBearingRemoteWithoutEchoingSecret(String cardText) {
        // given
        Card card = cardWithDescription(cardText);

        // when
        RepositorySourceSelection selection = resolver.select(card, noDefault());

        // then
        assertThat(selection.status()).isEqualTo(RepositorySourceSelection.Status.INVALID_SELECTED);
        assertThat(selection.problem().code()).isEqualTo("repository_remote_credentials_unsupported");
        assertThat(selection.problem().guidance()).doesNotContain("secret").doesNotContain("access_token");
    }

    @CsvSource({
        "Repository URL: https://example.invalid/team/project.git?, repository_remote_credentials_unsupported",
        "Repository URL: https://example.invalid/team/project.git#, repository_remote_credentials_unsupported",
        "Repository URL: ssh://git@example.invalid/team/project.git?, repository_remote_malformed",
        "Repository URL: ssh://git@example.invalid/team/project.git#, repository_remote_malformed"
    })
    @ParameterizedTest(name = "{0}")
    void rejectsRemoteUriWithEmptyQueryOrFragmentMarker(String cardText, String code) {
        // given
        Card card = cardWithDescription(cardText);

        // when
        RepositorySourceSelection selection = resolver.select(card, noDefault());

        // then
        assertThat(selection.status()).isEqualTo(RepositorySourceSelection.Status.INVALID_SELECTED);
        assertThat(selection.problem().code()).isEqualTo(code);
    }

    @CsvSource({
        "Repository URL: https://example.invalid/team/repo%0D.git",
        "Repository URL: https://example.invalid/team/repo%0A.git",
        "Repository URL: https://example.invalid/team/repo%00.git",
        "Repository URL: https://example.invalid/team/repo%1F.git",
        "Repository URL: https://example.invalid/team/repo%7F.git"
    })
    @ParameterizedTest(name = "{0}")
    void rejectsEncodedUriControls(String cardText) {
        // given
        Card card = cardWithDescription(cardText);

        // when
        RepositorySourceSelection selection = resolver.select(card, noDefault());

        // then
        assertThat(selection.status()).isEqualTo(RepositorySourceSelection.Status.INVALID_SELECTED);
        assertThat(selection.problem().code()).isEqualTo("repository_remote_malformed");
    }

    @Test
    void rejectsLiteralControlCharactersInLocalPath() {
        // given
        Card card = cardWithDescription("Repository path: /tmp/repo\u0000injected");

        // when
        RepositorySourceSelection selection = resolver.select(card, noDefault());

        // then
        assertThat(selection.status()).isEqualTo(RepositorySourceSelection.Status.INVALID_SELECTED);
        assertThat(selection.problem().code()).isEqualTo("repository_path_malformed");
    }

    @MethodSource("unsafePromptLineCharacters")
    @ParameterizedTest(name = "{0}")
    void rejectsEveryIsoControlAndUnicodeLineSeparatorFromPromptLines(String scenario, int codePoint) {
        // given
        String value = "before" + Character.toString(codePoint) + "after";

        // when
        boolean safe = RepositorySourceText.safePromptLine(value);

        // then
        assertThat(safe).as(scenario).isFalse();
    }

    private static Stream<Arguments> unsafePromptLineCharacters() {
        return IntStream.concat(
                        IntStream.rangeClosed(0, 0x9F).filter(Character::isISOControl), IntStream.of(0x2028, 0x2029))
                .mapToObj(codePoint -> Arguments.of("U+%04X".formatted(codePoint), codePoint));
    }

    @MethodSource("invalidSetupRepositoryUrls")
    @ParameterizedTest(name = "{0}")
    void rejectsMalformedSetupRepositoryUrls(String scenario, String repositoryUrl) {
        // given

        // when
        RepositorySourceSelection selection = resolver.selectWorkflowDefaultUrl(repositoryUrl);

        // then
        assertThat(selection.status()).as(scenario).isEqualTo(RepositorySourceSelection.Status.INVALID_SELECTED);
        assertThat(selection.problem().code()).isEqualTo("repository_remote_malformed");
    }

    private static Stream<Arguments> invalidSetupRepositoryUrls() {
        return Stream.of(
                Arguments.of("literal NEL in SCP path", "git@example.invalid:team/repo\u0085injected.git"),
                Arguments.of(
                        "percent-decoded NEL in HTTPS path", "https://example.invalid/team/repo%C2%85injected.git"),
                Arguments.of(
                        "percent-decoded line separator in HTTP path",
                        "http://example.invalid/team/repo%E2%80%A8injected.git"),
                Arguments.of(
                        "percent-decoded paragraph separator in SSH path",
                        "ssh://git@example.invalid/team/repo%E2%80%A9injected.git"),
                Arguments.of("HTTP port zero", "http://example.invalid:0/team/project.git"),
                Arguments.of("HTTPS port above range", "https://example.invalid:65536/team/project.git"),
                Arguments.of("SSH port above range", "ssh://git@example.invalid:99999/team/project.git"));
    }

    @Test
    void selectsWorkflowDefaultUrlWhenCardHasNoExplicitSource() {
        // given
        EffectiveConfig.RepositoryConfig repository =
                new EffectiveConfig.RepositoryConfig("https://github.example/team/default.git", null);

        // when
        RepositorySourceSelection selection = resolver.select(cardWithDescription("No source"), repository);

        // then
        assertThat(selection.status()).isEqualTo(RepositorySourceSelection.Status.SELECTED);
        assertThat(selection.source().origin()).isEqualTo(RepositorySource.Origin.WORKFLOW_DEFAULT_URL);
        assertThat(selection.source().value()).isEqualTo("https://github.example/team/default.git");
    }

    @Test
    void preservesTokenPunctuationCompatibilityForExistingWorkflowDefaults() {
        // given
        EffectiveConfig.RepositoryConfig repository =
                new EffectiveConfig.RepositoryConfig("<https://github.example/team/default.git>.", null);

        // when
        RepositorySourceSelection selection = resolver.select(cardWithDescription("No source"), repository);

        // then
        assertThat(selection.status()).isEqualTo(RepositorySourceSelection.Status.SELECTED);
        assertThat(selection.source().value()).isEqualTo("https://github.example/team/default.git");
    }

    @Test
    void rejectsBarePathWorkflowDefaultUrlInsteadOfConvertingItToLocalPath() {
        // given
        EffectiveConfig.RepositoryConfig repository = new EffectiveConfig.RepositoryConfig("relative/repo.git", null);

        // when
        RepositorySourceSelection selection = resolver.select(cardWithDescription("No source"), repository);

        // then
        assertThat(selection.status()).isEqualTo(RepositorySourceSelection.Status.INVALID_SELECTED);
        assertThat(selection.problem().code()).isEqualTo("repository_remote_unsupported");
    }

    @CsvSource({"https://example.invalid/team/project.git?", "https://example.invalid/team/project.git#"})
    @ParameterizedTest(name = "{0}")
    void rejectsWorkflowDefaultUrlWithEmptyQueryOrFragmentMarkerAndSuppressesPath(String defaultUrl) {
        // given
        Path fallbackPath = tempDir.resolve("fallback").toAbsolutePath().normalize();
        EffectiveConfig.RepositoryConfig repository = new EffectiveConfig.RepositoryConfig(defaultUrl, fallbackPath);

        // when
        RepositorySourceSelection selection = resolver.select(cardWithDescription("No source"), repository);

        // then
        assertThat(selection.status()).isEqualTo(RepositorySourceSelection.Status.INVALID_SELECTED);
        assertThat(selection.problem().code()).isEqualTo("repository_remote_credentials_unsupported");
        assertThat(selection.source()).isNull();
    }

    @CsvSource({
        "Repository URL: https:example.invalid/team/repo.git, repository_remote_malformed",
        "Repository URL: ftp:example.invalid/team/repo.git, repository_remote_unsupported",
        "Repository URL: ftp://example.invalid/team/repo.git, repository_remote_unsupported",
        "Repository URL: https://example.invalid, repository_remote_malformed",
        "Repository URL: https://example.invalid/, repository_remote_malformed"
    })
    @ParameterizedTest(name = "{0}")
    void rejectsMalformedAndUnsupportedUriLikeSources(String cardText, String code) {
        // given
        Card card = cardWithDescription(cardText);

        // when
        RepositorySourceSelection selection = resolver.select(card, noDefault());

        // then
        assertThat(selection.status()).isEqualTo(RepositorySourceSelection.Status.INVALID_SELECTED);
        assertThat(selection.problem().code()).isEqualTo(code);
    }

    @Test
    void selectsWorkflowDefaultPathWhenUrlIsAbsent() {
        // given
        Path repositoryPath = tempDir.resolve("repo").toAbsolutePath().normalize();
        EffectiveConfig.RepositoryConfig repository = new EffectiveConfig.RepositoryConfig(null, repositoryPath);

        // when
        RepositorySourceSelection selection = resolver.select(cardWithDescription("No source"), repository);

        // then
        assertThat(selection.status()).isEqualTo(RepositorySourceSelection.Status.SELECTED);
        assertThat(selection.source().origin()).isEqualTo(RepositorySource.Origin.WORKFLOW_DEFAULT_PATH);
        assertThat(selection.source().path()).isEqualTo(repositoryPath);
    }

    private static Card cardWithText(String title, String description, List<Card.Comment> comments) {
        return TestCards.cardWithText("card-1", "TRELLO-source", "Ready for Codex", title, description, comments);
    }

    private static EffectiveConfig.RepositoryConfig noDefault() {
        return new EffectiveConfig.RepositoryConfig(null, null);
    }

    private static Card cardWithDescription(String description) {
        return TestCards.cardWithText(
                "card-1", "TRELLO-source", "Ready for Codex", "Implement feature", description, List.of());
    }

    private static Card.Comment comment(String text) {
        return new Card.Comment("comment-1", text, "maintainer", Instant.parse("2026-01-01T00:00:00Z"));
    }
}
