package ch.fmartin.symphony.trello;

import ch.fmartin.symphony.trello.config.LocalEnvironment;
import ch.fmartin.symphony.trello.orchestrator.SymphonyOrchestrator;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@QuarkusMain
public class SymphonyMain {
    private static final TypeReference<Map<String, Object>> YAML_MAP_TYPE = new TypeReference<>() {};
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    public static void main(String... args) {
        CliOptions options = CliOptions.parse(args);
        String workflowPath = options.workflowPath().orElseGet(SymphonyMain::configuredWorkflowPath);
        configuredPort(options, Path.of(workflowPath)).ifPresent(port -> {
            System.setProperty("quarkus.http.port", port);
            System.setProperty("symphony.http.port.source", options.port().isPresent() ? "cli" : "workflow");
        });
        if (options.workflowPath().isPresent()) {
            System.setProperty("symphony.workflow.path", options.workflowPath().get());
        }
        Quarkus.run(Application.class, args);
    }

    static String configuredWorkflowPath() {
        String systemProperty = System.getProperty("symphony.workflow.path");
        if (systemProperty != null && !systemProperty.isBlank()) {
            return systemProperty;
        }
        Optional<String> env = LocalEnvironment.get("SYMPHONY_WORKFLOW_PATH");
        if (env.isPresent()) {
            return env.get();
        }
        return "WORKFLOW.md";
    }

    static Optional<String> configuredPort(CliOptions options, Path workflowPath) {
        if (options.port().isPresent()) {
            return options.port();
        }
        if (hasExternalPortOverride()) {
            return Optional.empty();
        }
        return configuredServerPort(workflowPath);
    }

    private static boolean hasExternalPortOverride() {
        if (hasText(System.getProperty("quarkus.http.port"))) {
            return true;
        }
        return LocalEnvironment.get("SYMPHONY_HTTP_PORT").isPresent()
                || LocalEnvironment.get("QUARKUS_HTTP_PORT").isPresent();
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
            if (options.workflowPath().isPresent()) {
                Path workflow = Path.of(options.workflowPath().get());
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
            }
            orchestrator.start();
            Quarkus.waitForExit();
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
            if (frontMatter.isEmpty()) {
                return Optional.empty();
            }
            Map<String, Object> yaml = YAML.readValue(frontMatter.get(), YAML_MAP_TYPE);
            if (yaml != null && yaml.get("server") instanceof Map<?, ?> server) {
                Object port = server.get("port");
                if (port instanceof Number number) {
                    return Optional.of(number.toString());
                }
                if (port instanceof String text && !text.isBlank()) {
                    return Optional.of(text);
                }
            }
        } catch (IOException ignored) {
            return Optional.empty();
        }
        return Optional.empty();
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
            String workflowPath = null;
            String port = null;
            List<String> arguments = new ArrayList<>(List.of(args));
            for (int i = 0; i < arguments.size(); i++) {
                String argument = arguments.get(i);
                if ("--port".equals(argument) && i + 1 < arguments.size()) {
                    port = arguments.get(++i);
                } else if (argument.startsWith("--port=")) {
                    port = argument.substring("--port=".length());
                } else if (!argument.startsWith("-") && workflowPath == null) {
                    workflowPath = argument;
                }
            }
            return new CliOptions(Optional.ofNullable(workflowPath), Optional.ofNullable(port));
        }
    }
}
