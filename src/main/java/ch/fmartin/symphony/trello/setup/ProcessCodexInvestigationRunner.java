package ch.fmartin.symphony.trello.setup;

import ch.fmartin.symphony.trello.setup.CodexInvestigationRunner.Outcome.Completed;
import ch.fmartin.symphony.trello.setup.CodexInvestigationRunner.Outcome.Failed;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/// Runs `codex exec` as a child process for a setup-failure investigation.
///
/// Codex gets the prompt on standard input, writes its final answer to a JSON file that matches
/// [CodexInvestigationFinding#jsonSchema(ObjectMapper)], and runs with the `workspace-write`
/// sandbox so it can change only its working root, the extra writable roots, and temporary
/// directories. `--ephemeral` keeps Codex from persisting a session log of the investigation. The
/// Codex progress output is captured in a scratch file and deleted, because it can echo local file
/// contents that the user did not ask to see.
final class ProcessCodexInvestigationRunner implements CodexInvestigationRunner {
    private static final String STANDARD_INPUT_PROMPT = "-";
    private static final Duration TERMINATION_GRACE = Duration.ofSeconds(5);
    private static final String MARKER_VALUE = "1";
    private static final String UNEXPECTED_ANSWER = "Codex returned an answer in an unexpected format";

    private final ObjectMapper json = new ObjectMapper();

    @Override
    public Outcome investigate(Request request) {
        Path scratch = null;
        try {
            scratch = Files.createTempDirectory("symphony-trello-codex-");
            ScratchFiles files = ScratchFiles.in(scratch);
            Files.writeString(files.prompt(), request.prompt());
            Files.writeString(files.schema(), CodexInvestigationFinding.jsonSchema(json));
            return run(request, files);
        } catch (IOException e) {
            return new Failed("the temporary files for Codex could not be written ("
                    + e.getClass().getSimpleName() + ")");
        } finally {
            deleteScratchBestEffort(scratch);
        }
    }

    private Outcome run(Request request, ScratchFiles files) throws IOException {
        var builder = new ProcessBuilder(command(request, files))
                .redirectInput(files.prompt().toFile())
                .redirectErrorStream(true)
                .redirectOutput(files.output().toFile());
        builder.environment().put(INVESTIGATION_MARKER_ENV, MARKER_VALUE);
        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            return new Failed("Codex could not be started (" + e.getClass().getSimpleName() + ")");
        }
        var stopOnExit = new Thread(() -> stop(process), "codex-investigation-stop");
        Runtime.getRuntime().addShutdownHook(stopOnExit);
        try {
            if (!process.waitFor(request.timeout())) {
                stop(process);
                return new Failed(
                        "Codex did not finish within " + CodexInvestigationRunner.describe(request.timeout()));
            }
            int exitCode = process.exitValue();
            if (exitCode != 0) {
                return new Failed("Codex exited with code " + exitCode);
            }
            return finding(files.result());
        } catch (InterruptedException e) {
            stop(process);
            Thread.currentThread().interrupt();
            return new Failed("Codex investigation was interrupted");
        } finally {
            removeShutdownHookUnlessExiting(stopOnExit);
        }
    }

    private static List<String> command(Request request, ScratchFiles files) {
        List<String> arguments = new ArrayList<>(List.of(
                "exec",
                "--sandbox",
                "workspace-write",
                "--skip-git-repo-check",
                "--ephemeral",
                "--color",
                "never",
                "--output-schema",
                files.schema().toString(),
                "--output-last-message",
                files.result().toString(),
                "--cd",
                request.workingRoot().toString()));
        for (Path root : request.additionalWritableRoots()) {
            arguments.add("--add-dir");
            arguments.add(root.toString());
        }
        arguments.add(STANDARD_INPUT_PROMPT);
        return List.of(request.codex().command(arguments.toArray(String[]::new)));
    }

    private Outcome finding(Path result) {
        if (!Files.isRegularFile(result)) {
            return new Failed("Codex finished without an answer");
        }
        try {
            return CodexInvestigationFinding.parse(json.readTree(result.toFile()))
                    .<Outcome>map(Completed::new)
                    .orElseGet(() -> new Failed(UNEXPECTED_ANSWER));
        } catch (IOException e) {
            return new Failed(UNEXPECTED_ANSWER);
        }
    }

    /// Codex starts shell commands of its own, so the whole process tree is stopped, not only the
    /// direct child.
    private static void stop(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        try {
            process.waitFor(TERMINATION_GRACE);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void removeShutdownHookUnlessExiting(Thread hook) {
        try {
            Runtime.getRuntime().removeShutdownHook(hook);
        } catch (IllegalStateException ignored) {
            // The JVM is already exiting, so the registered hook is about to stop Codex anyway.
        }
    }

    private static void deleteScratchBestEffort(Path scratch) {
        if (scratch == null) {
            return;
        }
        try (var entries = Files.list(scratch)) {
            for (Path entry : entries.toList()) {
                Files.deleteIfExists(entry);
            }
            Files.deleteIfExists(scratch);
        } catch (IOException ignored) {
            // Best effort: the scratch directory holds only the sanitized prompt, the schema, and
            // Codex output, and the OS temp cleaner removes leftovers.
        }
    }

    private record ScratchFiles(Path prompt, Path schema, Path result, Path output) {
        static ScratchFiles in(Path scratch) {
            return new ScratchFiles(
                    scratch.resolve("prompt.md"),
                    scratch.resolve("schema.json"),
                    scratch.resolve("result.json"),
                    scratch.resolve("output.log"));
        }
    }
}
