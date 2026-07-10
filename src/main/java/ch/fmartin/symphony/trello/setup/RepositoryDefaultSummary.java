package ch.fmartin.symphony.trello.setup;

import java.io.PrintStream;
import java.nio.file.Path;

final class RepositoryDefaultSummary {
    private RepositoryDefaultSummary() {}

    static void printGuided(
            PrintStream out, TrelloBoardSetup.RepositoryDefaults repositoryDefaults, Path workflowPath) {
        print(out, repositoryDefaults, workflowPath, "  OK  ", "      ");
    }

    static void printDirect(
            PrintStream out, TrelloBoardSetup.RepositoryDefaults repositoryDefaults, Path workflowPath) {
        print(out, repositoryDefaults, workflowPath, "", "");
    }

    private static void print(
            PrintStream out,
            TrelloBoardSetup.RepositoryDefaults repositoryDefaults,
            Path workflowPath,
            String statusPrefix,
            String detailPrefix) {
        out.println(statusPrefix + summary(repositoryDefaults));
        out.println(detailPrefix + "To add or change it later, edit repository.default_url in:");
        out.println(detailPrefix + "  " + workflowPath.toAbsolutePath().normalize());
    }

    private static String summary(TrelloBoardSetup.RepositoryDefaults repositoryDefaults) {
        if (repositoryDefaults.defaultUrl() != null) {
            return "Repository clone URL saved in repository.default_url";
        }
        if (repositoryDefaults.defaultPath() != null) {
            return "Repository clone URL not set; repository.default_path remains configured.";
        }
        return "Repository clone URL not set; this workflow remains repository-general.";
    }
}
