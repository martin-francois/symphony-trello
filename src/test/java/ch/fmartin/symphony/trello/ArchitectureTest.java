package ch.fmartin.symphony.trello;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.importer.ImportOption.DoNotIncludeTests;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "ch.fmartin.symphony.trello", importOptions = DoNotIncludeTests.class)
class ArchitectureTest {
    @ArchTest
    static final ArchRule topLevelProductionPackagesDoNotHaveCircularDependencies = slices().matching(
                    "ch.fmartin.symphony.trello.(*)..")
            .should()
            .beFreeOfCycles()
            .because("top-level packages are the service's maintainable module boundaries");

    @ArchTest
    static final ArchRule apiOnlyDependsOnOrchestratorAsApplicationBoundary = noClasses()
            .that()
            .resideInAPackage("..api..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("..agent..", "..config..", "..prompt..", "..setup..", "..tracker..", "..workspace..");

    @ArchTest
    static final ArchRule setupDoesNotDependOnRuntimeOrchestration = noClasses()
            .that()
            .resideInAPackage("..setup..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("..agent..", "..api..", "..orchestrator..", "..prompt..", "..workspace..");
}
