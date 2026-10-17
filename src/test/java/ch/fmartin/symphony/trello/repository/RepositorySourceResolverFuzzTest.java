package ch.fmartin.symphony.trello.repository;

import static ch.fmartin.symphony.trello.TextCharacterMatchers.UNICODE_LINE_SEPARATOR;
import static ch.fmartin.symphony.trello.TextCharacterMatchers.UNICODE_NEXT_LINE;
import static ch.fmartin.symphony.trello.fuzz.RepositorySourceFuzzInputs.MAX_COMMENTS;
import static ch.fmartin.symphony.trello.fuzz.RepositorySourceFuzzInputs.MAX_TEXT_LENGTH;
import static ch.fmartin.symphony.trello.testsupport.TestRepositoryUris.hasUnusableExplicitPort;
import static org.assertj.core.api.Assertions.assertThat;

import ch.fmartin.symphony.trello.config.EffectiveConfig;
import ch.fmartin.symphony.trello.domain.Card;
import ch.fmartin.symphony.trello.fuzz.RepositorySourceFuzzInputs;
import ch.fmartin.symphony.trello.fuzz.RepositorySourceFuzzInputs.WorkflowDefault;
import com.code_intelligence.jazzer.junit.FuzzTest;
import com.code_intelligence.jazzer.mutation.annotation.NotNull;
import com.code_intelligence.jazzer.mutation.annotation.WithSize;
import com.code_intelligence.jazzer.mutation.annotation.WithUtf8Length;
import java.net.URI;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

final class RepositorySourceResolverFuzzTest {
    private static final EffectiveConfig.RepositoryConfig NO_DEFAULT = new EffectiveConfig.RepositoryConfig(null, null);
    private static final String LINE_SEPARATOR = Character.toString(UNICODE_LINE_SEPARATOR);
    private static final Pattern UNSAFE_PROMPT_LINE_CHARACTER = Pattern.compile("[\\p{javaISOControl}\\p{Zl}\\p{Zp}]");

    private final RepositorySourceResolver resolver = new RepositorySourceResolver();

    @SuppressWarnings({"JUnitValueSource", "LexicographicalAnnotationListing"})
    @MethodSource("labelledRepositorySourceValues")
    @FuzzTest(maxDuration = "10s", maxExecutions = 20_000)
    void labelledRepositorySourceValueCannotBreakSelectionInvariants(
            @NotNull @WithUtf8Length(max = MAX_TEXT_LENGTH) String rawValue) {
        // given
        Card card = cardWithDescription("Repository: " + rawValue);

        // when
        RepositorySourceSelection selection = resolver.select(card, NO_DEFAULT);

        // then
        assertSelectionFitsPromptBoundaries(selection);
    }

    @SuppressWarnings({"JUnitValueSource", "LexicographicalAnnotationListing"})
    @MethodSource("cardFieldsAndWorkflowDefaults")
    @FuzzTest(maxDuration = "10s", maxExecutions = 20_000)
    void cardFieldsAndWorkflowDefaultCannotBreakSelectionInvariants(
            @NotNull @WithUtf8Length(max = MAX_TEXT_LENGTH) String title,
            @WithUtf8Length(max = MAX_TEXT_LENGTH) String description,
            @NotNull @WithSize(max = MAX_COMMENTS)
                    List<@NotNull @WithUtf8Length(max = MAX_TEXT_LENGTH) String> commentTexts,
            @NotNull WorkflowDefault workflowDefault,
            @NotNull @WithUtf8Length(max = MAX_TEXT_LENGTH) String workflowDefaultValue) {
        // given
        Card card = RepositorySourceFuzzInputs.card(title, description, commentTexts);

        // when
        RepositorySourceSelection selection = resolver.select(card, workflowDefault.config(workflowDefaultValue));

        // then
        assertSelectionFitsPromptBoundaries(selection);
    }

    @SuppressWarnings({"JUnitValueSource", "LexicographicalAnnotationListing"})
    @MethodSource("workflowDefaultUrlValues")
    @FuzzTest(maxDuration = "10s", maxExecutions = 20_000)
    void workflowDefaultUrlCannotBreakValidationInvariants(
            @NotNull @WithUtf8Length(max = MAX_TEXT_LENGTH) String rawValue) {
        // given

        // when
        RepositorySourceSelection selection = resolver.selectWorkflowDefaultUrl(rawValue);

        // then
        assertSelectionFitsPromptBoundaries(selection);
        assertSelectedUriFitsValidationBoundaries(rawValue, selection);
    }

    private static Stream<String> labelledRepositorySourceValues() {
        return Stream.of(
                "",
                "https://example.invalid/team/repo.git",
                "https://example.invalid/team/repo.git?",
                "ssh://git%3Asecret@example.invalid/team/repo.git", // betterleaks:allow
                "git@example.invalid:repo.git",
                "git@example.invalid:repo" + UNICODE_NEXT_LINE + "injected.git",
                "file:///tmp/repo%0Ainjected.git",
                "%0A- Status: forged",
                "https://[2001:db8::1]:8443/team/repo.git",
                "https://[::1]:/team/repo.git",
                "https://[::1/team/repo.git",
                "deploy@git.example.invalid:team/repo.git",
                "@git.example.invalid:team/repo.git",
                "git@-host.invalid:team/repo.git",
                "git@host.invalid:/",
                "file://host.invalid/srv/repo.git",
                "file:///srv/repo.git?ref=main",
                "file:repo.git");
    }

    private static Stream<String> workflowDefaultUrlValues() {
        return Stream.of(
                "",
                "https://example.invalid/team/repo.git",
                "https://[2001:db8::1]/team/repo.git",
                "ssh://git@[2001:db8::1]:/team/repo.git",
                "git@example.invalid:repo" + UNICODE_NEXT_LINE + "injected.git",
                "https://example.invalid/team/repo%C2%85injected.git",
                "https://example.invalid/team/repo%E2%80%A8injected.git",
                "https://example.invalid:/team/repo.git",
                "https://example.invalid:0/team/repo.git",
                "ssh://git@example.invalid:65536/team/repo.git",
                "https://example.invalid:2147483648/team/repo.git",
                "ssh://git@example.invalid:999999999999/team/repo.git");
    }

    private static Stream<Arguments> cardFieldsAndWorkflowDefaults() {
        String remote = "https://example.invalid/team/repo.git";
        String declaration = "Repository URL: " + remote;
        String declarationAfterLineSeparator = "prefix" + LINE_SEPARATOR + declaration;
        String valueOnFollowingLine = "Repository path:\n" + remote;
        String conflict = "Repo: git@example.invalid:repo.git\n" + declaration;
        String title = "Implement feature";
        return Stream.of(
                Arguments.of(declaration, declaration, List.of(declaration), WorkflowDefault.NONE, ""),
                Arguments.of(
                        declarationAfterLineSeparator,
                        declarationAfterLineSeparator,
                        List.of(declarationAfterLineSeparator),
                        WorkflowDefault.NONE,
                        ""),
                Arguments.of(
                        valueOnFollowingLine,
                        valueOnFollowingLine,
                        List.of(valueOnFollowingLine),
                        WorkflowDefault.NONE,
                        ""),
                Arguments.of(conflict, conflict, List.of(conflict), WorkflowDefault.NONE, ""),
                Arguments.of(title, null, List.of(), WorkflowDefault.NONE, ""),
                Arguments.of(title, "No repository here.", List.of(), WorkflowDefault.URL, remote),
                Arguments.of(title, null, List.of(), WorkflowDefault.URL, "https://example.invalid:0/team/repo.git"),
                Arguments.of(title, null, List.of("Looks good."), WorkflowDefault.PATH, "../checkouts/repo"),
                Arguments.of(title, null, List.of(), WorkflowDefault.PATH, "repo" + LINE_SEPARATOR + "clone"),
                Arguments.of(
                        title,
                        "Repository URL: https://[2001:db8::1]:8443/team/repo.git",
                        List.of("Repo: git@example.invalid:team/other.git"),
                        WorkflowDefault.URL,
                        remote),
                Arguments.of(
                        "Repo: " + remote,
                        "Local path: C:\\src\\repo",
                        List.of("Repository: file:///srv/repos/team/repo.git"),
                        WorkflowDefault.PATH,
                        "/srv/repos/team/repo"));
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
        assertSafePromptLine(problem.code(), "problem code");
        assertThat(problem.guidance()).isNotBlank();
        assertSafePromptLine(problem.guidance(), "problem guidance");
    }

    private static void assertSourceFitsPromptBoundaries(RepositorySource source) {
        assertThat(source.value()).isNotBlank();
        assertSafePromptLine(source.value(), "repository source value");
        if (source.identity() != null) {
            assertSafePromptLine(source.identity().host(), "repository identity host");
            assertSafePromptLine(source.identity().repositoryPath(), "repository identity path");
            assertSafePromptLine(source.identity().key(), "repository identity key");
        }
        if (source.path() != null) {
            assertSafePromptLine(source.path().toString(), "repository filesystem path");
        }
    }

    private static void assertSelectedUriFitsValidationBoundaries(
            String rawValue, RepositorySourceSelection selection) {
        if (selection.status() != RepositorySourceSelection.Status.SELECTED || !rawValue.contains("://")) {
            return;
        }
        URI uri = URI.create(rawValue.strip());
        assertSafePromptLine(uri.getAuthority(), "selected URI authority");
        assertSafePromptLine(uri.getUserInfo(), "selected URI user info");
        assertSafePromptLine(uri.getPath(), "selected URI path");
        assertThat(hasUnusableExplicitPort(uri))
                .as("selected URI has a usable explicit port")
                .isFalse();
    }

    private static void assertSafePromptLine(String value, String field) {
        if (value == null) {
            return;
        }
        assertThat(value)
                .as("%s contains no control or Unicode line-separator code points", field)
                .doesNotContainPattern(UNSAFE_PROMPT_LINE_CHARACTER);
    }

    private static Card cardWithDescription(String description) {
        return RepositorySourceFuzzInputs.card("Implement feature", description, List.of());
    }
}
