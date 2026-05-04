package com.openai.symphony;

import com.openai.symphony.orchestrator.SymphonyOrchestrator;
import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import jakarta.inject.Inject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@QuarkusMain
public class SymphonyMain {
    public static void main(String... args) {
        CliOptions options = CliOptions.parse(args);
        if (options.port() != null) {
            System.setProperty("quarkus.http.port", options.port());
        }
        if (options.workflowPath() != null) {
            System.setProperty("symphony.workflow.path", options.workflowPath());
        }
        Quarkus.run(Application.class, args);
    }

    public static class Application implements QuarkusApplication {
        @Inject
        SymphonyOrchestrator orchestrator;

        @Override
        public int run(String... args) {
            CliOptions options = CliOptions.parse(args);
            if (options.workflowPath() != null) {
                Path workflow = Path.of(options.workflowPath());
                if (!Files.isRegularFile(workflow)) {
                    System.err.println("Workflow file does not exist: " + workflow);
                    return 2;
                }
                orchestrator.setWorkflowPath(workflow);
            }
            orchestrator.start();
            Quarkus.waitForExit();
            return 0;
        }
    }

    private record CliOptions(String workflowPath, String port) {
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
            return new CliOptions(workflowPath, port);
        }
    }
}
