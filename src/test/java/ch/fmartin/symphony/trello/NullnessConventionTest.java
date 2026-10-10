package ch.fmartin.symphony.trello;

import static org.assertj.core.api.Assertions.assertThat;

import ch.fmartin.symphony.trello.codex.CodexSkillCatalog;
import ch.fmartin.symphony.trello.domain.Card;
import ch.fmartin.symphony.trello.process.ProcessEnvironment;
import ch.fmartin.symphony.trello.prompt.PromptRenderer;
import ch.fmartin.symphony.trello.repository.RepositorySource;
import ch.fmartin.symphony.trello.time.ApplicationClock;
import ch.fmartin.symphony.trello.workflow.WorkflowDefinition;
import ch.fmartin.symphony.trello.workspace.Workspace;
import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class NullnessConventionTest {
    /// One class per production package whose every type passed the nullness audit, so the
    /// package-level `@NullMarked` default stays in place.
    @ParameterizedTest
    @ValueSource(
            classes = {
                ApplicationClock.class,
                Card.class,
                CodexSkillCatalog.class,
                ProcessEnvironment.class,
                PromptRenderer.class,
                RepositorySource.class,
                SymphonyMain.class,
                WorkflowDefinition.class,
                Workspace.class
            })
    void auditedPackagesDeclareNullMarkedDefault(Class<?> packageMember) {
        // given
        Package auditedPackage = packageMember.getPackage();

        // when
        var annotations = auditedPackage.getDeclaredAnnotations();

        // then
        assertThat(annotations)
                .as("%s should keep @NullMarked in its package-info.java", auditedPackage.getName())
                .hasAtLeastOneElementOfType(NullMarked.class);
    }
}
