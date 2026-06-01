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
    static final ArchRule TOP_LEVEL_PRODUCTION_PACKAGES_DO_NOT_HAVE_CIRCULAR_DEPENDENCIES = slices().matching(
                    "ch.fmartin.symphony.trello.(*)..")
            .should()
            .beFreeOfCycles()
            .because("top-level packages are the service's maintainable module boundaries");

    @ArchTest
    static final ArchRule API_ONLY_DEPENDS_ON_ORCHESTRATOR_AS_APPLICATION_BOUNDARY = noClasses()
            .that()
            .resideInAPackage("..api..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("..agent..", "..config..", "..prompt..", "..setup..", "..tracker..", "..workspace..");

    @ArchTest
    static final ArchRule SETUP_DOES_NOT_DEPEND_ON_RUNTIME_ORCHESTRATION = noClasses()
            .that()
            .resideInAPackage("..setup..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("..agent..", "..api..", "..orchestrator..", "..prompt..", "..workspace..");

    @ArchTest
    static final ArchRule SETUP_SERVICES_DO_NOT_DEPEND_ON_PICOCLI = noClasses()
            .that(setupServiceClasses())
            .should()
            .dependOnClassesThat()
            .resideInAPackage("picocli..")
            .because("picocli must stay at the command boundary while setup and lifecycle services own behavior");

    @ArchTest
    static final ArchRule EXTRACTED_SETUP_FLOWS_USE_TERMINAL_INSTEAD_OF_RAW_INPUT_READERS = noClasses()
            .that(extractedSetupFlowClasses())
            .should()
            .dependOnClassesThat()
            .haveFullyQualifiedName("java.io.BufferedReader")
            .because("interactive setup prompting should be centralized through the Terminal abstraction");

    @ArchTest
    static final ArchRule COMMAND_BOUNDARY_DOES_NOT_CALL_TRELLO_HTTP_LOGIC_DIRECTLY = noClasses()
            .that(commandBoundaryClasses())
            .should()
            .dependOnClassesThat()
            .haveFullyQualifiedName("ch.fmartin.symphony.trello.tracker.TrelloClient")
            .because("picocli commands should parse and delegate, not perform Trello transport work");

    @ArchTest
    static final ArchRule COMMAND_BOUNDARY_DOES_NOT_PERSIST_CONNECTED_BOARD_MANIFESTS_DIRECTLY = noClasses()
            .that(commandBoundaryClasses())
            .should()
            .dependOnClassesThat()
            .haveFullyQualifiedName("ch.fmartin.symphony.trello.setup.ConnectedBoardRepository")
            .because("manifest persistence should stay centralized behind setup services");

    @ArchTest
    static final ArchRule COMMAND_BOUNDARY_DOES_NOT_EDIT_WORKFLOW_YAML_DIRECTLY = noClasses()
            .that(commandBoundaryClasses())
            .should()
            .dependOnClassesThat()
            .haveFullyQualifiedName("ch.fmartin.symphony.trello.setup.WorkflowConfigEditor")
            .because("workflow front matter updates should stay centralized in WorkflowConfigEditor");

    @ArchTest
    static final ArchRule WORKFLOW_YAML_PARSING_IS_CENTRALIZED = noClasses()
            .that(setupClassesOutsideWorkflowConfigAndInitialGenerator())
            .should()
            .dependOnClassesThat()
            .haveFullyQualifiedName("com.fasterxml.jackson.dataformat.yaml.YAMLFactory")
            .because("workflow YAML parsing and front matter editing should stay in WorkflowConfigEditor");

    @ArchTest
    static final ArchRule COMMAND_BOUNDARY_DOES_NOT_OWN_MANAGED_PROCESS_LIFECYCLE = noClasses()
            .that(commandBoundaryClasses())
            .should()
            .dependOnClassesThat(lifecycleInfrastructureClasses())
            .because("picocli commands should delegate lifecycle commands through LocalWorkerManager");

    @ArchTest
    static final ArchRule EXTRACTED_SETUP_FLOWS_DO_NOT_WRITE_TO_RAW_PRINT_STREAMS = noClasses()
            .that(extractedSetupFlowClasses())
            .should()
            .dependOnClassesThat()
            .haveFullyQualifiedName("java.io.PrintStream")
            .because("setup prompts and user-visible setup output should go through Terminal");

    @ArchTest
    static final ArchRule EXTRACTED_SETUP_FLOWS_DO_NOT_WRITE_TO_RAW_PRINT_WRITERS = noClasses()
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
