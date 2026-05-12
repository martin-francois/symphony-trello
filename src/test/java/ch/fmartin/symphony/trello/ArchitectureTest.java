package ch.fmartin.symphony.trello;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
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

    @ArchTest
    static final ArchRule setupServicesDoNotDependOnPicocli = noClasses()
            .that(setupServiceClasses())
            .should()
            .dependOnClassesThat()
            .resideInAPackage("picocli..")
            .because("picocli must stay at the command boundary while setup and lifecycle services own behavior");

    @ArchTest
    static final ArchRule extractedSetupFlowsUseTerminalInsteadOfRawInputReaders = noClasses()
            .that(extractedSetupFlowClasses())
            .should()
            .dependOnClassesThat()
            .haveFullyQualifiedName("java.io.BufferedReader")
            .because("interactive setup prompting should be centralized through the Terminal abstraction");

    @ArchTest
    static final ArchRule commandBoundaryDoesNotCallTrelloHttpLogicDirectly = noClasses()
            .that(commandBoundaryClasses())
            .should()
            .dependOnClassesThat()
            .haveFullyQualifiedName("ch.fmartin.symphony.trello.tracker.TrelloClient")
            .because("picocli commands should parse and delegate, not perform Trello transport work");

    @ArchTest
    static final ArchRule commandBoundaryDoesNotPersistConnectedBoardManifestsDirectly = noClasses()
            .that(commandBoundaryClasses())
            .should()
            .dependOnClassesThat()
            .haveFullyQualifiedName("ch.fmartin.symphony.trello.setup.ConnectedBoardRepository")
            .because("manifest persistence should stay centralized behind setup services");

    @ArchTest
    static final ArchRule commandBoundaryDoesNotEditWorkflowYamlDirectly = noClasses()
            .that(commandBoundaryClasses())
            .should()
            .dependOnClassesThat()
            .haveFullyQualifiedName("ch.fmartin.symphony.trello.setup.WorkflowConfigEditor")
            .because("workflow front matter updates should stay centralized in WorkflowConfigEditor");

    @ArchTest
    static final ArchRule workflowYamlParsingIsCentralized = noClasses()
            .that(setupClassesOutsideWorkflowConfigAndInitialGenerator())
            .should()
            .dependOnClassesThat()
            .haveFullyQualifiedName("com.fasterxml.jackson.dataformat.yaml.YAMLFactory")
            .because("workflow YAML parsing and front matter editing should stay in WorkflowConfigEditor");

    @ArchTest
    static final ArchRule commandBoundaryDoesNotOwnManagedProcessLifecycle = noClasses()
            .that(commandBoundaryClasses())
            .should()
            .dependOnClassesThat(lifecycleInfrastructureClasses())
            .because("picocli commands should delegate lifecycle commands through LocalWorkerManager");

    @ArchTest
    static final ArchRule extractedSetupFlowsDoNotWriteToRawPrintStreams = noClasses()
            .that(extractedSetupFlowClasses())
            .should()
            .dependOnClassesThat()
            .haveFullyQualifiedName("java.io.PrintStream")
            .because("setup prompts and user-visible setup output should go through Terminal");

    @ArchTest
    static final ArchRule extractedSetupFlowsDoNotWriteToRawPrintWriters = noClasses()
            .that(extractedSetupFlowClasses())
            .should()
            .dependOnClassesThat()
            .haveFullyQualifiedName("java.io.PrintWriter")
            .because("setup prompts and user-visible setup output should go through Terminal");

    private static DescribedPredicate<JavaClass> setupServiceClasses() {
        return new DescribedPredicate<>("setup service classes outside the command boundary") {
            @Override
            public boolean test(JavaClass input) {
                return input.getPackageName().contains(".setup")
                        && !input.getName().contains("TrelloBoardSetupMain")
                        && !input.getName().contains("SetupLocalCommandFactory")
                        && !input.getName().contains("GitHubMode");
            }
        };
    }

    private static DescribedPredicate<JavaClass> extractedSetupFlowClasses() {
        return new DescribedPredicate<>("extracted setup flow/service classes") {
            @Override
            public boolean test(JavaClass input) {
                String name = input.getSimpleName();
                return input.getPackageName().contains(".setup")
                        && (name.endsWith("Checker")
                                || name.endsWith("Flow")
                                || name.endsWith("Store")
                                || name.endsWith("Configurator")
                                || name.endsWith("Connector"));
            }
        };
    }

    private static DescribedPredicate<JavaClass> commandBoundaryClasses() {
        return new DescribedPredicate<>("picocli command boundary classes") {
            @Override
            public boolean test(JavaClass input) {
                return input.getName().contains("TrelloBoardSetupMain")
                        || input.getName().contains("SetupLocalCommandFactory");
            }
        };
    }

    private static DescribedPredicate<JavaClass> setupClassesOutsideWorkflowConfigAndInitialGenerator() {
        return new DescribedPredicate<>("setup classes outside workflow config editor and initial generator") {
            @Override
            public boolean test(JavaClass input) {
                String name = input.getSimpleName();
                return input.getPackageName().contains(".setup")
                        && !name.equals("WorkflowConfigEditor")
                        && !name.equals("TrelloBoardSetup");
            }
        };
    }

    private static DescribedPredicate<JavaClass> lifecycleInfrastructureClasses() {
        return new DescribedPredicate<>("managed process lifecycle infrastructure classes") {
            @Override
            public boolean test(JavaClass input) {
                String name = input.getSimpleName();
                return name.equals("ManagedProcessStore")
                        || name.equals("ManagedProcessPlatform")
                        || name.equals("ProcessHandleManagedProcessPlatform")
                        || name.equals("WindowsManagedProcessPlatform");
            }
        };
    }
}
