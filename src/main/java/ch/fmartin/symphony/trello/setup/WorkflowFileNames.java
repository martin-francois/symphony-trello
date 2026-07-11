package ch.fmartin.symphony.trello.setup;

import ch.fmartin.symphony.trello.Sha3;
import java.util.Locale;

final class WorkflowFileNames {
    private static final String WORKFLOW_FILE_PREFIX = "WORKFLOW.";
    private static final String WORKFLOW_FILE_EXTENSION = ".md";
    private static final int MAX_GENERATED_WORKFLOW_FILE_NAME_LENGTH = 127;
    private static final int MAX_GENERATED_WORKFLOW_SLUG_LENGTH = 100;
    private static final int GENERATED_WORKFLOW_HASH_LENGTH = 12;

    private WorkflowFileNames() {}

    static String generatedFileName(String value, String blankFallback, int suffix) {
        String suffixSegment = suffixSegment(suffix);
        String slug = generatedSlug(value, blankFallback, suffixSegment.length());
        return WORKFLOW_FILE_PREFIX + slug + suffixSegment + WORKFLOW_FILE_EXTENSION;
    }

    static String workflowFileName(String slug, int suffix) {
        String suffixSegment = suffixSegment(suffix);
        return WORKFLOW_FILE_PREFIX + slug + suffixSegment + WORKFLOW_FILE_EXTENSION;
    }

    static boolean isGeneratedFileName(String fileName) {
        return fileName != null
                && fileName.startsWith(WORKFLOW_FILE_PREFIX)
                && fileName.endsWith(WORKFLOW_FILE_EXTENSION);
    }

    static String slug(String value, String blankFallback) {
        String slug =
                value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
        return slug.isBlank() ? blankFallback : slug;
    }

    private static String generatedSlug(String value, String blankFallback, int suffixLength) {
        String slug = slug(value, blankFallback);
        int readableSlugLength = Math.min(MAX_GENERATED_WORKFLOW_SLUG_LENGTH, maxReadableSlugLength(suffixLength));
        if (slug.length() <= readableSlugLength) {
            return slug;
        }
        String hash = Sha3.sha3_256(value).substring(0, GENERATED_WORKFLOW_HASH_LENGTH);
        return slug.substring(0, readableSlugLength) + "-" + hash;
    }

    private static int maxReadableSlugLength(int suffixLength) {
        return MAX_GENERATED_WORKFLOW_FILE_NAME_LENGTH
                - WORKFLOW_FILE_PREFIX.length()
                - WORKFLOW_FILE_EXTENSION.length()
                - suffixLength
                - "-".length()
                - GENERATED_WORKFLOW_HASH_LENGTH;
    }

    private static String suffixSegment(int suffix) {
        return suffix <= 1 ? "" : "-" + suffix;
    }
}
