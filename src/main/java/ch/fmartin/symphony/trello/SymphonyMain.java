package ch.fmartin.symphony.trello;

import ch.fmartin.symphony.trello.config.LocalEnvironment;
import ch.fmartin.symphony.trello.config.WorkflowConfigFinding;
import ch.fmartin.symphony.trello.config.WorkflowConfigIngestion;
import ch.fmartin.symphony.trello.config.WorkflowIntegerSetting;
import ch.fmartin.symphony.trello.orchestrator.SymphonyOrchestrator;
import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import picocli.CommandLine;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@QuarkusMain
public class SymphonyMain {
    public static void main(String... args) {
        CliOptions options = CliOptions.parse(args);
        String workflowPath = options.workflowPath().orElseGet(SymphonyMain::configuredWorkflowPath);
        configuredPort(options, Path.of(workflowPath)).ifPresent(port -> {
            System.setProperty("quarkus.http.port", port);
            System.setProperty("symphony.http.port.source", options.port().isPresent() ? "cli" : "workflow");
        });
        options.workflowPath().ifPresent(path -> System.setProperty("symphony.workflow.path", path));
        Quarkus.run(Application.class, args);
    }

    static String configuredWorkflowPath() {
        return configuredWorkflowPath(
                System.getProperty("symphony.workflow.path"), LocalEnvironment.get("SYMPHONY_WORKFLOW_PATH"));
    }

    static String configuredWorkflowPath(String systemProperty, Optional<String> environmentValue) {
        if (hasText(systemProperty)) {
            return systemProperty;
        }
        return environmentValue.orElse("WORKFLOW.md");
    }

    static Optional<String> configuredPort(CliOptions options, Path workflowPath) {
        return configuredPort(
                options, workflowPath, System.getProperty("quarkus.http.port"), externalHttpPortOverride());
    }

    static Optional<String> configuredPort(
            CliOptions options, Path workflowPath, String quarkusHttpPort, Optional<String> externalHttpPort) {
        if (options.port().isPresent()) {
            return options.port();
        }
        if (hasText(quarkusHttpPort)) {
            return Optional.empty();
        }
        if (externalHttpPort.isPresent()) {
            return externalHttpPort;
        }
        return configuredServerPort(workflowPath);
    }

    private static Optional<String> externalHttpPortOverride() {
        return LocalEnvironment.firstPresent("SYMPHONY_HTTP_PORT", "QUARKUS_HTTP_PORT");
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    public static class Application implements QuarkusApplication {
        @Inject
        SymphonyOrchestrator orchestrator;

        @Override
        public int run(String... args) {
            CliOptions options = CliOptions.parse(args);
            int workflowExitCode = options.workflowPath()
                    .map(Path::of)
                    .map(this::selectWorkflow)
                    .orElse(0);
            if (workflowExitCode != 0) {
                return workflowExitCode;
            }
            orchestrator.start();
            Quarkus.waitForExit();
            return 0;
        }

        private int selectWorkflow(Path workflow) {
            if (!Files.isRegularFile(workflow)) {
                System.err.println("Workflow file does not exist: " + workflow);
                return 2;
            }
            Path requestedWorkflow = workflow.toAbsolutePath().normalize();
            if (orchestrator.isStarted()) {
                if (!orchestrator.selectedWorkflowPath().equals(requestedWorkflow)) {
                    System.err.println("Workflow file was provided after startup already selected: "
                            + orchestrator.selectedWorkflowPath());
                    return 2;
                }
            } else {
                orchestrator.setWorkflowPath(requestedWorkflow);
            }
            return 0;
        }
    }

    static Optional<String> configuredServerPort(Path workflowPath) {
        if (!Files.isRegularFile(workflowPath)) {
            return Optional.empty();
        }
        try {
            List<String> lines = Files.readAllLines(workflowPath);
            Optional<String> frontMatter = frontMatter(lines);
            return frontMatter.flatMap(value -> configuredServerPort(value, LocalEnvironment::get));
        } catch (IOException ignored) {
            return Optional.empty();
        }
    }

    static Optional<String> configuredServerPort(
            String frontMatter, Function<String, Optional<String>> environmentResolver) {
        return WorkflowConfigIngestion.collectFrontMatter(
                        frontMatter,
                        environmentResolver,
                        WorkflowConfigIngestion.UnresolvedEnvironmentPolicy.THROW_SERVER_PORT)
                .flatMap(typed -> configuredServerPort(typed.serverPort()));
    }

    private static Optional<String> configuredServerPort(WorkflowIntegerSetting setting) {
        setting.finding().ifPresent(finding -> {
            throw new IllegalArgumentException(serverPortError(finding));
        });
        return setting.value().map(String::valueOf);
    }

    private static String serverPortError(WorkflowConfigFinding finding) {
        return switch (finding.kind()) {
            case OUT_OF_INT_RANGE -> "Workflow server.port is out of integer range: " + finding.input();
            case FRACTIONAL, NOT_A_NUMBER -> "Workflow server.port must be a whole number: " + finding.input();
            case NON_POSITIVE, NEGATIVE, IGNORED_MAP_VALUE -> "Workflow " + finding.message();
        };
    }

    private static Optional<String> frontMatter(List<String> lines) {
        if (lines.isEmpty() || !"---".equals(lines.getFirst().trim())) {
            return Optional.empty();
        }
        for (int i = 1; i < lines.size(); i++) {
            if ("---".equals(lines.get(i).trim())) {
                return Optional.of(String.join(System.lineSeparator(), lines.subList(1, i)));
            }
        }
        return Optional.empty();
    }

    record CliOptions(Optional<String> workflowPath, Optional<String> port) {
        static CliOptions parse(String... args) {
            RuntimeArgs parsed = CommandLine.populateCommand(new RuntimeArgs(), args);
            return new CliOptions(Optional.ofNullable(parsed.workflowPath), Optional.ofNullable(parsed.port));
        }
    }

    private static final class RuntimeArgs {
        @Parameters(index = "0", arity = "0..1", description = "Workflow file to run.")
        String workflowPath;

        @Option(names = "--port", description = "HTTP port for this Symphony worker.")
        String port;
    }
}
