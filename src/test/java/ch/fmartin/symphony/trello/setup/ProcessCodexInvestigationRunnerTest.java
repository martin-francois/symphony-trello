package ch.fmartin.symphony.trello.setup;

import static org.assertj.core.api.Assertions.assertThat;

import ch.fmartin.symphony.trello.setup.CodexInvestigationFinding.Classification;
import ch.fmartin.symphony.trello.setup.CodexInvestigationFinding.ValidationRun;
import ch.fmartin.symphony.trello.setup.CodexInvestigationRunner.Outcome;
import ch.fmartin.symphony.trello.setup.CodexInvestigationRunner.Request;
import ch.fmartin.symphony.trello.testsupport.BoundedWait;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/// Runs the real process runner against a fake `codex` shell script, so the command line, the
/// stdin prompt, the structured answer file, and the failure handling are checked at the process
/// boundary without calling the real Codex service.
@EnabledOnOs({OS.LINUX, OS.MAC})
final class ProcessCodexInvestigationRunnerTest {
    private static final String PROMPT = "Investigate the sanitized failure.\nSecond line.";
    private static final Duration GENEROUS_TIMEOUT = Duration.ofMinutes(1);
    private static final long TIMEOUT_MILLIS = 300;
    private static final long CHILD_EXIT_WAIT_SECONDS = 10;
    private static final Duration SHUTDOWN_CHILD_LIFETIME = Duration.ofMinutes(5);
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(10);
    private static final int SUCCESS_EXIT_CODE = 0;
    private static final String VALID_ANSWER =
            """
            {"classification":"symphony_bug","diagnosis":"Missing parent directory.","fix_applied":true,\
            "fix_summary":"Create the parent directory.","changed_files":["WORKFLOW.md"],\
            "validation":[{"command":"symphony-trello setup-local check","passed":true}],"next_step":""}""";

    @TempDir
    Path tempDir;

    @Test
    void runsCodexExecWithTheSandboxedCommandLineAndReadsTheStructuredAnswer() throws IOException {
        // given
        Path workingRoot = Files.createDirectories(tempDir.resolve("config"));
        Path sourceCheckout = Files.createDirectories(tempDir.resolve("app"));
        Path codex = fakeCodex(
                """
                printf '%%s\\n' "$@" > "%1$s/args.txt"
                cat > "%1$s/prompt.txt"
                printf '%%s' "${SYMPHONY_TRELLO_CODEX_INVESTIGATION:-}" > "%1$s/marker.txt"
                cp "$(argument --output-schema "$@")" "%1$s/schema.json"
                printf '%%s' '%2$s' > "$(argument --output-last-message "$@")"
                """
                        .formatted(tempDir, VALID_ANSWER));

        // when
        Outcome outcome = new ProcessCodexInvestigationRunner()
                .investigate(request(codex, workingRoot, List.of(sourceCheckout), GENEROUS_TIMEOUT));

        // then
        assertThat(outcome)
                .isEqualTo(new Outcome.Completed(new CodexInvestigationFinding(
                        Classification.SYMPHONY_BUG,
                        "Missing parent directory.",
                        true,
                        "Create the parent directory.",
                        List.of("WORKFLOW.md"),
                        List.of(new ValidationRun("symphony-trello setup-local check", true)),
                        "")));
        assertThat(Files.readAllLines(tempDir.resolve("args.txt")))
                .startsWith(
                        "exec",
                        "--sandbox",
                        "workspace-write",
                        "--skip-git-repo-check",
                        "--ephemeral",
                        "--color",
                        "never",
                        "--output-schema")
                .containsSubsequence("--cd", workingRoot.toString(), "--add-dir", sourceCheckout.toString(), "-")
                .endsWith("-");
        assertThat(tempDir.resolve("prompt.txt")).hasContent(PROMPT);
        assertThat(tempDir.resolve("marker.txt"))
                .as("commands started by Codex must see the marker that blocks a second investigation")
                .hasContent("1");
        JsonNode schema =
                new ObjectMapper().readTree(tempDir.resolve("schema.json").toFile());
        assertThat(schema.path("required"))
                .extracting(JsonNode::asText)
                .containsExactlyInAnyOrderElementsOf(propertyNames(schema));
    }

    @CsvSource(
            delimiter = '|',
            quoteCharacter = '~',
            textBlock =
                    """
                    exits non-zero                 | exit 3                                                             | Codex exited with code 3
                    writes no answer               | exit 0                                                             | Codex finished without an answer
                    writes malformed JSON          | printf 'not json' > "$(argument --output-last-message "$@")"       | Codex returned an answer in an unexpected format
                    uses an unknown classification | printf '{"classification":"other"}' > "$(argument --output-last-message "$@")" | Codex returned an answer in an unexpected format
                    """)
    @ParameterizedTest(name = "{0}")
    void reportsUnusableCodexRunsAsFailures(String scenario, String script, String reason) throws IOException {
        // given
        Path codex = fakeCodex(script);

        // when
        Outcome outcome =
                new ProcessCodexInvestigationRunner().investigate(request(codex, tempDir, List.of(), GENEROUS_TIMEOUT));

        // then
        assertThat(outcome).as(scenario).isEqualTo(new Outcome.Failed(reason));
    }

    @Test
    void stopsCodexAndItsChildProcessWhenTheInvestigationTimesOut() throws IOException {
        // given
        Path childPid = tempDir.resolve("child.pid");
        Path codex = fakeCodex("sleep 30 &\nprintf '%%s' \"$!\" > \"%s\"\nwait".formatted(childPid));
        long startNanos = System.nanoTime();

        // when
        Outcome outcome = new ProcessCodexInvestigationRunner()
                .investigate(request(codex, tempDir, List.of(), Duration.ofMillis(TIMEOUT_MILLIS)));

        // then
        assertThat(outcome).isEqualTo(new Outcome.Failed("Codex did not finish within " + TIMEOUT_MILLIS + " ms"));
        assertThat(Duration.ofNanos(System.nanoTime() - startNanos))
                .as("the runner must stop Codex instead of waiting for it to finish")
                .isLessThan(Duration.ofSeconds(20));
        assertThat(childPid)
                .as("the fake codex must have started its child process")
                .exists();
        Optional<ProcessHandle> child = ProcessHandle.of(Long.parseLong(Files.readString(childPid)));
        child.ifPresent(handle -> handle.onExit()
                .completeOnTimeout(handle, CHILD_EXIT_WAIT_SECONDS, TimeUnit.SECONDS)
                .join());
        assertThat(child.filter(ProcessHandle::isAlive))
                .as("the child process that Codex started must be stopped with it")
                .isEmpty();
    }

    @Test
    void jvmShutdownStopsCodexAndItsChildAfterTheHookIsRegistered() throws Exception {
        // given
        Path codexPid = tempDir.resolve("codex.pid");
        Path childPid = tempDir.resolve("shutdown-child.pid");
        Path ready = tempDir.resolve("shutdown-ready");
        Path codex = fakeCodex(
                """
                printf '%%s' "$$" > "%s"
                sleep %d &
                printf '%%s' "$!" > "%s"
                wait
                """
                        .formatted(codexPid, SHUTDOWN_CHILD_LIFETIME.toSeconds(), childPid));
        Process helper = new ProcessBuilder(
                        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-Djava.io.tmpdir=" + tempDir,
                        "-cp",
                        System.getProperty("java.class.path"),
                        ShutdownProbe.class.getName(),
                        codex.toString(),
                        tempDir.toString(),
                        codexPid.toString(),
                        childPid.toString(),
                        ready.toString())
                .redirectErrorStream(true)
                .redirectOutput(tempDir.resolve("shutdown-probe.log").toFile())
                .start();
        List<ProcessHandle> cleanup = new ArrayList<>();
        try {
            BoundedWait.until(
                    PROBE_TIMEOUT, () -> Files.exists(ready), "the investigation reaches its registered-hook wait");
            Optional<ProcessHandle> codexProcess = processFromPidFile(codexPid);
            Optional<ProcessHandle> child = processFromPidFile(childPid);
            child.ifPresent(cleanup::add);
            codexProcess.ifPresent(cleanup::add);
            assertThat(codexProcess.filter(ProcessHandle::isAlive))
                    .as("the fake Codex process is alive before shutdown")
                    .isPresent();
            assertThat(child.filter(ProcessHandle::isAlive))
                    .as("the fake Codex child is alive before shutdown")
                    .isPresent();

            // when
            helper.getOutputStream().write(1);
            helper.getOutputStream().flush();

            // then
            assertThat(helper.waitFor(PROBE_TIMEOUT))
                    .as("the helper JVM exits after running its shutdown hooks")
                    .isTrue();
            assertThat(helper.exitValue()).isEqualTo(SUCCESS_EXIT_CODE);
            BoundedWait.until(
                    PROBE_TIMEOUT,
                    () -> codexProcess.filter(ProcessHandle::isAlive).isEmpty()
                            && child.filter(ProcessHandle::isAlive).isEmpty(),
                    "the registered hook stops the fake Codex process and its child");
        } finally {
            helper.descendants().forEach(ProcessHandle::destroyForcibly);
            cleanup.forEach(ProcessHandle::destroyForcibly);
            helper.destroyForcibly();
            helper.waitFor(PROBE_TIMEOUT);
        }
    }

    private static boolean waitsForProcess(Thread investigation) {
        for (StackTraceElement frame : investigation.getStackTrace()) {
            if (frame.getClassName().startsWith("java.lang.Process")
                    && frame.getMethodName().equals("waitFor")) {
                return true;
            }
        }
        return false;
    }

    private static Optional<ProcessHandle> processFromPidFile(Path path) throws IOException {
        return Files.exists(path) ? ProcessHandle.of(Long.parseLong(Files.readString(path))) : Optional.empty();
    }

    public static final class ShutdownProbe {
        private ShutdownProbe() {}

        public static void main(String[] args) throws Exception {
            Path codex = Path.of(args[0]);
            Path root = Path.of(args[1]);
            Path codexPid = Path.of(args[2]);
            Path childPid = Path.of(args[3]);
            Path ready = Path.of(args[4]);
            Thread investigation = Thread.ofPlatform()
                    .daemon()
                    .name("shutdown-investigation")
                    .start(() -> new ProcessCodexInvestigationRunner()
                            .investigate(request(codex, root, List.of(), GENEROUS_TIMEOUT)));
            // run() registers its shutdown hook before entering Process.waitFor.
            BoundedWait.until(
                    PROBE_TIMEOUT,
                    () -> codexPid.toFile().length() > 0
                            && childPid.toFile().length() > 0
                            && waitsForProcess(investigation),
                    "the investigation waits for its child after hook registration");
            Files.writeString(ready, "ready");
            System.in.read();
            System.exit(SUCCESS_EXIT_CODE);
        }
    }

    @Test
    void reportsACodexExecutableThatCannotStart() {
        // given
        Path missing = tempDir.resolve("missing-codex");

        // when
        Outcome outcome = new ProcessCodexInvestigationRunner()
                .investigate(request(missing, tempDir, List.of(), GENEROUS_TIMEOUT));

        // then
        assertThat(outcome).isEqualTo(new Outcome.Failed("Codex could not be started (IOException)"));
    }

    private static List<String> propertyNames(JsonNode schema) {
        List<String> names = new ArrayList<>();
        schema.path("properties").fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static Request request(Path codex, Path workingRoot, List<Path> additionalRoots, Duration timeout) {
        return new Request(
                arguments -> {
                    String[] command = new String[arguments.length + 1];
                    command[0] = codex.toString();
                    System.arraycopy(arguments, 0, command, 1, arguments.length);
                    return command;
                },
                PROMPT,
                workingRoot,
                additionalRoots,
                timeout);
    }

    /// Writes a fake `codex` script. `argument NAME "$@"` prints the value after option `NAME`.
    private Path fakeCodex(String body) throws IOException {
        Path script = Files.createTempFile(tempDir, "codex-", ".sh");
        Files.writeString(
                script,
                """
                #!/usr/bin/env bash
                set -eu
                argument() {
                  local name="$1"
                  shift
                  while [ "$#" -gt 0 ]; do
                    if [ "$1" = "$name" ]; then
                      printf '%%s' "$2"
                      return
                    fi
                    shift
                  done
                }
                %s
                """
                        .formatted(body));
        assertThat(script.toFile().setExecutable(true))
                .as("the fake codex script must be executable")
                .isTrue();
        return script;
    }
}
