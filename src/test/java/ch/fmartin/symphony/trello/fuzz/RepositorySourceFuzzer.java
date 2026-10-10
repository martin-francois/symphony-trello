package ch.fmartin.symphony.trello.fuzz;

import static ch.fmartin.symphony.trello.fuzz.RepositorySourceFuzzInputs.MAX_COMMENTS;
import static ch.fmartin.symphony.trello.fuzz.RepositorySourceFuzzInputs.MAX_TEXT_LENGTH;
import static ch.fmartin.symphony.trello.testsupport.TestRepositoryUris.hasUnusableExplicitPort;

import ch.fmartin.symphony.trello.fuzz.RepositorySourceFuzzInputs.WorkflowDefault;
import ch.fmartin.symphony.trello.repository.RepositoryIdentity;
import ch.fmartin.symphony.trello.repository.RepositorySource;
import ch.fmartin.symphony.trello.repository.RepositorySourceProblem;
import ch.fmartin.symphony.trello.repository.RepositorySourceResolver;
import ch.fmartin.symphony.trello.repository.RepositorySourceSelection;
import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/// Builds a card and a workflow repository default from one fuzz input.
///
/// The harness reads the repository value first. `FuzzedDataProvider` ends a text at a backslash
/// followed by any other character and takes choices from the end of the input, so the harness reads
/// the choices next and the other texts after them. An input without that separator, like most
/// inputs stored before the harness made choices, keeps its whole text as the value and gets the first
/// option of every choice: the value declared in the title and the description, no comments, and no
/// workflow default. A seed file is the texts in consumption order, each ended by a backslash and one
/// other character, followed by one byte per choice in reverse consumption order. Every card field
/// takes two choice bytes, its content and its label, even when the content uses no label, so the
/// layout stays fixed. A literal backslash is written as two backslashes.
public final class RepositorySourceFuzzer {
    private static final RepositorySourceResolver RESOLVER = new RepositorySourceResolver();
    private static final String DEFAULT_TITLE = "Implement feature";
    private static final String[] DECLARATION_LABELS = {
        "Repository",
        "Repository URL",
        "Repository path",
        "Repo",
        "Repo URL",
        "Repo path",
        "Local checkout",
        "Local path",
        "Checkout"
    };

    private RepositorySourceFuzzer() {}

    public static void fuzzerTestOneInput(FuzzedDataProvider data) {
        String value = data.consumeString(MAX_TEXT_LENGTH);
        FieldChoice titleChoice = FieldChoice.consume(data);
        FieldChoice descriptionChoice = FieldChoice.consume(data);
        int commentCount = data.consumeInt(0, MAX_COMMENTS);
        List<FieldChoice> commentChoices = new ArrayList<>(commentCount);
        for (int comment = 0; comment < commentCount; comment++) {
            commentChoices.add(FieldChoice.consume(data));
        }
        WorkflowDefault workflowDefault = data.pickValue(WorkflowDefault.values());

        RepositorySourceSelection defaultUrlSelection = RESOLVER.selectWorkflowDefaultUrl(value);
        assertSelectionFitsPromptBoundaries(defaultUrlSelection);
        assertSelectedUriFitsValidationBoundaries(value, defaultUrlSelection);

        String title = titleChoice.text(data, value);
        String description = descriptionChoice.text(data, value);
        List<String> commentTexts = new ArrayList<>(commentCount);
        for (FieldChoice commentChoice : commentChoices) {
            String text = commentChoice.text(data, value);
            if (text != null) {
                commentTexts.add(text);
            }
        }
        var card = RepositorySourceFuzzInputs.card(title == null ? DEFAULT_TITLE : title, description, commentTexts);
        String workflowDefaultValue =
                workflowDefault == WorkflowDefault.NONE ? "" : data.consumeString(MAX_TEXT_LENGTH);
        assertSelectionFitsPromptBoundaries(RESOLVER.select(card, workflowDefault.config(workflowDefaultValue)));
    }

    /// What one card text field carries. The first constant is the choice an input without choice
    /// bytes gets.
    private enum FieldContent {
        /// A declaration of the shared repository value, so several fields can agree.
        DECLARATION,
        /// A declaration of its own value, so several fields can conflict.
        OWN_DECLARATION,
        /// Free text that may hold declarations on any line.
        TEXT,
        /// No text: the default title, no description, or no comment.
        ABSENT
    }

    /// The content and declaration label chosen for one card text field.
    private record FieldChoice(FieldContent content, String label) {
        private static FieldChoice consume(FuzzedDataProvider data) {
            return new FieldChoice(data.pickValue(FieldContent.values()), data.pickValue(DECLARATION_LABELS));
        }

        private String text(FuzzedDataProvider data, String value) {
            return switch (content) {
                case ABSENT -> null;
                case DECLARATION -> label + ": " + value;
                case OWN_DECLARATION -> label + ": " + data.consumeString(MAX_TEXT_LENGTH);
                case TEXT -> data.consumeString(MAX_TEXT_LENGTH);
            };
        }
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
        if (hasUnusableExplicitPort(uri)) {
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
