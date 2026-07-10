package ch.fmartin.symphony.trello.fuzz;

import ch.fmartin.symphony.trello.config.EffectiveConfig;
import ch.fmartin.symphony.trello.domain.Card;
import ch.fmartin.symphony.trello.repository.RepositoryIdentity;
import ch.fmartin.symphony.trello.repository.RepositorySource;
import ch.fmartin.symphony.trello.repository.RepositorySourceProblem;
import ch.fmartin.symphony.trello.repository.RepositorySourceResolver;
import ch.fmartin.symphony.trello.repository.RepositorySourceSelection;
import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import java.net.URI;
import java.time.Instant;
import java.util.List;

public final class RepositorySourceFuzzer {
    private static final RepositorySourceResolver RESOLVER = new RepositorySourceResolver();
    private static final EffectiveConfig.RepositoryConfig NO_DEFAULT = new EffectiveConfig.RepositoryConfig(null, null);
    private static final int MAX_TEXT_LENGTH = 2_048;
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private RepositorySourceFuzzer() {}

    public static void fuzzerTestOneInput(FuzzedDataProvider data) {
        String text = data.consumeString(MAX_TEXT_LENGTH);
        assertSelectionFitsPromptBoundaries(RESOLVER.select(card(text), NO_DEFAULT));
        RepositorySourceSelection workflowDefault = RESOLVER.selectWorkflowDefaultUrl(text);
        assertSelectionFitsPromptBoundaries(workflowDefault);
        assertSelectedUriFitsValidationBoundaries(text, workflowDefault);
    }

    private static void assertSelectionFitsPromptBoundaries(RepositorySourceSelection selection) {
        switch (selection.status()) {
            case NONE -> {
                if (selection.source() != null) {
                    throw new AssertionError("none selection has source");
                }
            }
            case INVALID_SELECTED -> assertProblemFitsPromptBoundaries(selection.problem());
            case SELECTED -> assertSourceFitsPromptBoundaries(selection.source());
        }
    }

    private static void assertProblemFitsPromptBoundaries(RepositorySourceProblem problem) {
        if (blank(problem.code()) || unsafePromptLine(problem.code())) {
            throw new AssertionError("invalid problem code");
        }
        if (blank(problem.guidance()) || unsafePromptLine(problem.guidance())) {
            throw new AssertionError("invalid problem guidance");
        }
    }

    private static void assertSourceFitsPromptBoundaries(RepositorySource source) {
        if (blank(source.value()) || unsafePromptLine(source.value())) {
            throw new AssertionError("invalid source value");
        }
        RepositoryIdentity identity = source.identity();
        if (identity != null
                && (unsafePromptLine(identity.host())
                        || unsafePromptLine(identity.repositoryPath())
                        || unsafePromptLine(identity.key()))) {
            throw new AssertionError("invalid repository identity");
        }
        if (source.path() != null && unsafePromptLine(source.path().toString())) {
            throw new AssertionError("invalid repository path");
        }
    }

    private static Card card(String text) {
        return new Card(
                "card-1",
                "TRELLO-1",
                text,
                "Repository: " + text,
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
                List.of(new Card.Comment("comment-1", text, "author", NOW)),
                NOW,
                NOW,
                null,
                false,
                null);
    }

    private static void assertSelectedUriFitsValidationBoundaries(
            String rawValue, RepositorySourceSelection selection) {
        if (selection.status() != RepositorySourceSelection.Status.SELECTED || !rawValue.contains("://")) {
            return;
        }
        URI uri = URI.create(rawValue.strip());
        if (unsafePromptLine(uri.getAuthority())
                || unsafePromptLine(uri.getUserInfo())
                || unsafePromptLine(uri.getPath())) {
            throw new AssertionError("selected URI contains unsafe decoded text");
        }
        if (uri.getPort() == 0 || uri.getPort() > 65_535) {
            throw new AssertionError("selected URI contains an unusable explicit port");
        }
    }

    private static boolean unsafePromptLine(String value) {
        return value != null && value.codePoints().anyMatch(RepositorySourceFuzzer::unsafePromptLineCharacter);
    }

    private static boolean unsafePromptLineCharacter(int codePoint) {
        int type = Character.getType(codePoint);
        return Character.isISOControl(codePoint)
                || type == Character.LINE_SEPARATOR
                || type == Character.PARAGRAPH_SEPARATOR;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
