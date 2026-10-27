package ch.fmartin.symphony.trello.setup;

import static com.google.common.base.Preconditions.checkArgument;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import ch.fmartin.symphony.trello.testsupport.CapturedStreams;
import com.google.common.base.Splitter;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.SimpleFileServer;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import picocli.CommandLine;

final class InstallerScriptFixture {
    static final String FAKE_APP_VERSION = "1.0.0";
    static final String TRELLO_KEY_PROMPT = "Trello API key: ";
    static final String TRELLO_TOKEN_PROMPT = "Trello API token: ";
    static final String TRELLO_BOARD_PROMPT = "Trello board name: ";
    private static final int PROCESS_DESCENDANT_DISCOVERY_MILLIS = 1_000;
    private static final int PROCESS_TERMINATION_SECONDS = 5;
    private static final int PROCESS_STOP_SECONDS = 5;
    private static final int PROCESS_STOP_POLL_MILLIS = 10;
    private static final Splitter PS_LINES =
            Splitter.onPattern("\\R").trimResults().omitEmptyStrings();
    private static final int PROCESS_TIMEOUT_SECONDS = 60;
    private static final int DIALOG_COLUMNS = 120;
    private static final int DIALOG_ROWS = 24;
    private static final int TEMP_FILE_DELETION_SECONDS = 5;
    static final String FAKE_SLOW_TERM_DELAYED = "true";
    static final String FAKE_SLOW_TERM_UNTIL_KILLED = "until-killed";
    // The real start returns once the worker is healthy; the fake start waits this long for jar-start.
    static final int FAKE_WORKER_READY_SECONDS = 30;
    private static final String FAKE_WORKER_TERM_HANDLER = singleQuoteFree(
            """
            fake_worker_on_term() {
              echo "jar-term-received pid=$$" >> "${SYMPHONY_FAKE_LOG:?}"
              if [[ "${SYMPHONY_FAKE_SLOW_TERM:-}" == "%s" ]]; then sleep 2; fi
              if [[ -d "$app_home" ]]; then
                echo "app-present-before-exit" >> "${SYMPHONY_FAKE_LOG:?}"
              else
                echo "app-missing-before-exit" >> "${SYMPHONY_FAKE_LOG:?}"
              fi
              if [[ "${SYMPHONY_FAKE_SLOW_TERM:-}" == "%s" ]]; then
                while true; do sleep 1; done
              fi
              echo "jar-stopped" >> "${SYMPHONY_FAKE_LOG:?}"
              exit 0
            }
            trap fake_worker_on_term TERM
            """
                    .formatted(FAKE_SLOW_TERM_DELAYED, FAKE_SLOW_TERM_UNTIL_KILLED));
    private static final String REPOSITORY_ENVIRONMENT_PREFIX = "SYMPHONY_";
    /// The installers decide whether to color their output from these variables. Tests start without
    /// the developer's or CI runner's values so a transcript does not depend on the host terminal.
    static final List<String> OUTPUT_COLOR_CONTROLS = List.of("NO_COLOR", "CLICOLOR", "CLICOLOR_FORCE", "TERM");
    private static final Pattern POSIX_INSTALLER_DEFAULT_VERSION =
            Pattern.compile("(?m)^DEFAULT_VERSION=\"([^\"]+)\" # x-release-please-version$");

    private InstallerScriptFixture() {}

    static void writeExecutable(Path path, String content) throws IOException {
        Files.writeString(path, content);
        path.toFile().setExecutable(true);
    }

    static void writeCommandProxy(Path directory, String name, String target) throws IOException {
        writeExecutable(
                directory.resolve(name),
                """
                #!/bin/sh
                exec %s "$@"
                """.formatted(target));
    }

    /// Gives a pipe-backed script session dimensions before JLine reads its first prompt.
    static String withTerminalSize(String command) {
        return "stty cols " + DIALOG_COLUMNS + " rows " + DIALOG_ROWS + " && " + command;
    }

    static PseudoTerminalResult runWithPseudoTerminal(Map<String, String> environment, String input, String command)
            throws IOException, InterruptedException {
        ProcessResult session = runWithTestEnvironment(environment, pseudoTerminalProcess(command), input);
        return PseudoTerminalResult.from(session);
    }

    /// Runs `command` in a pseudo-terminal and types each response only after its prompt appeared,
    /// the way a person answers prompts. Unlike [#runWithPseudoTerminal], which writes all input
    /// before the command starts, the terminal echo of each answer then follows its prompt, so the
    /// transcript order does not depend on scheduling.
    static PseudoTerminalResult runPseudoTerminalDialog(
            HostEnvironment hostEnvironment,
            Map<String, String> environment,
            Path workingDirectory,
            String command,
            List<TerminalAnswer> answers)
            throws IOException, InterruptedException {
        ProcessBuilder processBuilder = pseudoTerminalProcess(command);
        processBuilder.directory(workingDirectory.toFile());
        hostEnvironment.applyTo(processBuilder);
        try (AppliedTestEnvironment ignored = applyTestEnvironment(processBuilder, environment)) {
            return PseudoTerminalResult.from(runDialog(processBuilder, answers, PROCESS_TIMEOUT_SECONDS));
        }
    }

    private static ProcessBuilder pseudoTerminalProcess(String command) {
        return new ProcessBuilder("script", "-q", "-e", "-c", command, "/dev/null");
    }

    static ProcessResult run(Map<String, String> environment, String... command)
            throws IOException, InterruptedException {
        return run(environment, new ProcessBuilder(command));
    }

    static ProcessResult run(Map<String, String> environment, Path workingDirectory, String... command)
            throws IOException, InterruptedException {
        var processBuilder = new ProcessBuilder(command);
        processBuilder.directory(workingDirectory.toFile());
        return run(environment, processBuilder);
    }

    static ProcessResult run(Map<String, String> environment, ProcessBuilder processBuilder)
            throws IOException, InterruptedException {
        return runWithTestEnvironment(environment, processBuilder, "");
    }

    static ProcessResult run(
            HostEnvironment hostEnvironment, Map<String, String> environment, Path workingDirectory, String... command)
            throws IOException, InterruptedException {
        var processBuilder = new ProcessBuilder(command);
        processBuilder.directory(workingDirectory.toFile());
        hostEnvironment.applyTo(processBuilder);
        return run(environment, processBuilder);
    }

    private static ProcessResult runWithTestEnvironment(
            Map<String, String> environment, ProcessBuilder processBuilder, String input)
            throws IOException, InterruptedException {
        try (AppliedTestEnvironment ignored = applyTestEnvironment(processBuilder, environment)) {
            return run(processBuilder, input, PROCESS_TIMEOUT_SECONDS);
        }
    }

    private static AppliedTestEnvironment applyTestEnvironment(
            ProcessBuilder processBuilder, Map<String, String> environment) throws IOException {
        Map<String, String> processEnvironment = processBuilder.environment();
        processEnvironment.keySet().removeIf(InstallerScriptFixture::isRepositoryEnvironmentControl);
        processEnvironment.keySet().removeIf(InstallerScriptFixture::isOutputColorControl);
        if (!environment.containsKey("PATH")) {
            String inheritedPath = processEnvironment.getOrDefault("PATH", "");
            String javaBin = Path.of(System.getProperty("java.home"), "bin").toString();
            processEnvironment.put(
                    "PATH", inheritedPath.isEmpty() ? javaBin : javaBin + File.pathSeparator + inheritedPath);
        }
        for (String xdgHome : List.of("XDG_DATA_HOME", "XDG_CONFIG_HOME", "XDG_STATE_HOME", "XDG_CACHE_HOME")) {
            if (!environment.containsKey(xdgHome)) {
                processEnvironment.remove(xdgHome);
            }
        }
        processEnvironment.putAll(environment);
        if (!environment.containsKey("TMPDIR") && !isWindows()) {
            // The JVM ignores TMPDIR on Linux, and scripts/pwsh-docker.sh mounts TMPDIR into the container.
            processEnvironment.put("TMPDIR", System.getProperty("java.io.tmpdir"));
        }
        if (!environment.containsKey("SYMPHONY_TRELLO_TEST_OS") && isLinux()) {
            processEnvironment.put("SYMPHONY_TRELLO_TEST_OS", "Linux");
        }
        applyDeterministicLinuxDistro(processEnvironment);
        applyDeterministicLinuxFilesystem(processEnvironment);
        List<Path> cleanupRoots = List.of();
        if (!environment.containsKey("HOME") && !isWindows()) {
            String symphonyHome = environment.get("SYMPHONY_HOME");
            Path home;
            Path symphonyHomePath = symphonyHome == null || symphonyHome.isBlank() ? null : Path.of(symphonyHome);
            if (symphonyHomePath != null
                    && symphonyHomePath.isAbsolute()
                    && symphonyHomePath.getParent() != null
                    && symphonyHomePath.getFileName() != null) {
                Files.createDirectories(symphonyHomePath.getParent());
                home = symphonyHomePath.resolveSibling(symphonyHomePath.getFileName() + "-user-home");
            } else {
                home = Files.createTempDirectory("symphony-trello-installer-home-");
                cleanupRoots = List.of(home);
            }
            Files.createDirectories(home);
            processEnvironment.put("HOME", home.toString());
        }
        return new AppliedTestEnvironment(cleanupRoots);
    }

    static boolean isRepositoryEnvironmentControl(String name) {
        return name.regionMatches(true, 0, REPOSITORY_ENVIRONMENT_PREFIX, 0, REPOSITORY_ENVIRONMENT_PREFIX.length());
    }

    private static boolean isOutputColorControl(String name) {
        for (String control : OUTPUT_COLOR_CONTROLS) {
            if (control.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    /// Returns `base` with `overrides` applied, for a run that differs from a shared environment only
    /// in a few variables.
    static Map<String, String> withEnvironment(Map<String, String> base, Map<String, String> overrides) {
        Map<String, String> environment = new LinkedHashMap<>(base);
        environment.putAll(overrides);
        return environment;
    }

    private static void applyDeterministicLinuxDistro(Map<String, String> processEnvironment) {
        if (!"Linux".equals(processEnvironment.get("SYMPHONY_TRELLO_TEST_OS"))) {
            return;
        }
        processEnvironment.putIfAbsent("SYMPHONY_TRELLO_TEST_OS_ID", "debian");
        processEnvironment.putIfAbsent("SYMPHONY_TRELLO_TEST_OS_ID_LIKE", "debian");
        processEnvironment.putIfAbsent("SYMPHONY_TRELLO_TEST_OS_PRETTY_NAME", "Debian GNU/Linux");
    }

    private static void applyDeterministicLinuxFilesystem(Map<String, String> processEnvironment) {
        if (!"Linux".equals(processEnvironment.get("SYMPHONY_TRELLO_TEST_OS"))) {
            return;
        }
        processEnvironment.putIfAbsent("SYMPHONY_TRELLO_TEST_HOME_FS_SOURCE", "fixture-root");
        processEnvironment.putIfAbsent("SYMPHONY_TRELLO_TEST_ROOT_FS_SOURCE", "fixture-root");
        processEnvironment.putIfAbsent("SYMPHONY_TRELLO_TEST_VAR_FS_SOURCE", "fixture-root");
        processEnvironment.putIfAbsent("SYMPHONY_TRELLO_TEST_HOME_SIZE_KB", "1048576");
        processEnvironment.putIfAbsent("SYMPHONY_TRELLO_TEST_VAR_SIZE_KB", "1048576");
    }

    private static boolean isLinux() {
        return System.getProperty("os.name", "").equalsIgnoreCase("Linux");
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException {
                if (failure != null) {
                    throw failure;
                }
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    static ProcessResult run(ProcessBuilder processBuilder, String input, int timeoutSeconds)
            throws IOException, InterruptedException {
        Path stdin = Files.createTempFile("symphony-trello-installer-stdin-", ".log");
        try {
            Files.writeString(stdin, input);
            processBuilder.redirectInput(stdin.toFile());
            return runCaptured(processBuilder, timeoutSeconds, (process, stdout, deadline) -> {});
        } finally {
            deleteTempFiles(stdin);
        }
    }

    private static ProcessResult runDialog(
            ProcessBuilder processBuilder, List<TerminalAnswer> answers, int timeoutSeconds)
            throws IOException, InterruptedException {
        processBuilder.redirectInput(ProcessBuilder.Redirect.PIPE);
        return runCaptured(processBuilder, timeoutSeconds, (process, stdout, deadline) -> {
            int answeredUpTo = 0;
            try (var terminalInput = process.getOutputStream()) {
                for (TerminalAnswer answer : answers) {
                    answeredUpTo = awaitPrompt(process, stdout, answer.prompt(), answeredUpTo, deadline);
                    terminalInput.write(
                            (answer.response() + (answer.pressEnter() ? "\n" : "")).getBytes(StandardCharsets.UTF_8));
                    terminalInput.flush();
                }
                // Keep the terminal attached while a raw control key is handled. Closing script's
                // input here would inject EOF immediately after Ctrl+C or Ctrl+D.
                if (!answers.isEmpty() && !answers.getLast().pressEnter()) {
                    assertThat(process.waitFor(Duration.ofNanos(Math.max(0, deadline - System.nanoTime()))))
                            .as("the child exits after the raw control key while terminal input remains attached")
                            .isTrue();
                }
            }
        });
    }

    /// Starts the process with captured stdout and stderr, lets `input` interact with it until the
    /// deadline, and waits for it to exit. Standard input must already be configured.
    private static ProcessResult runCaptured(ProcessBuilder processBuilder, int timeoutSeconds, ProcessInput input)
            throws IOException, InterruptedException {
        Path stdout = Files.createTempFile("symphony-trello-installer-stdout-", ".log");
        Path stderr = null;
        Process process = null;
        try {
            stderr = Files.createTempFile("symphony-trello-installer-stderr-", ".log");
            processBuilder.redirectOutput(stdout.toFile());
            processBuilder.redirectError(stderr.toFile());
            process = processBuilder.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
            input.interact(process, stdout, deadline);
            boolean completed = process.waitFor(Duration.ofNanos(Math.max(0, deadline - System.nanoTime())));
            if (!completed) {
                terminateProcessTree(process);
            }
            String capturedStdout = decodeCapturedOutput(stdout);
            String capturedStderr = decodeCapturedOutput(stderr);
            assertThat(completed)
                    .as("process timed out: %s output:%n%s%s", processBuilder.command(), capturedStdout, capturedStderr)
                    .isTrue();
            return new ProcessResult(process.exitValue(), capturedStdout, capturedStderr);
        } finally {
            if (process != null && process.isAlive()) {
                terminateProcessTreeImmediately(process);
            }
            deleteTempFiles(stdout, stderr);
        }
    }

    @FunctionalInterface
    private interface ProcessInput {
        void interact(Process process, Path stdout, long deadlineNanos) throws IOException, InterruptedException;
    }

    private static int awaitPrompt(Process process, Path transcript, String prompt, int searchFrom, long deadline)
            throws IOException, InterruptedException {
        while (true) {
            String output = decodeCapturedOutput(transcript);
            int promptStart = output.indexOf(prompt, searchFrom);
            if (promptStart >= 0) {
                return promptStart + prompt.length();
            }
            if (!process.isAlive() || System.nanoTime() >= deadline) {
                throw new AssertionError("terminal never showed the prompt %s; transcript so far:%n%s"
                        .formatted(shellQuote(prompt), output));
            }
            pollDelayForBoundedProcessWait();
        }
    }

    private static void deleteTempFiles(Path... files) throws IOException, InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TEMP_FILE_DELETION_SECONDS);
        Map<Path, IOException> failures = new LinkedHashMap<>();
        do {
            failures.clear();
            for (Path file : files) {
                if (file == null) {
                    continue;
                }
                try {
                    Files.deleteIfExists(file);
                } catch (IOException exception) {
                    failures.put(file, exception);
                }
            }
            if (failures.isEmpty()) {
                return;
            }
            pollDelayForBoundedProcessWait();
        } while (System.nanoTime() < deadline);

        var failure = new IOException("Temporary process files remain locked: " + failures.keySet());
        failures.values().forEach(failure::addSuppressed);
        throw failure;
    }

    private static String decodeCapturedOutput(Path output) throws IOException {
        return StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE)
                .decode(ByteBuffer.wrap(Files.readAllBytes(output)))
                .toString();
    }

    private static void terminateProcessTree(Process process) throws IOException, InterruptedException {
        Map<Long, ProcessHandle> observedDescendants = new LinkedHashMap<>();
        if (isWindows()) {
            terminateDescendantsUntilNoneAppear(process, observedDescendants);
        } else {
            stopProcessTree(process);
        }
        terminateCurrentDescendants(process, observedDescendants);
        process.destroyForcibly();
        for (ProcessHandle descendant : observedDescendants.values()) {
            descendant.destroyForcibly();
        }
        long terminationDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(PROCESS_TERMINATION_SECONDS);
        while ((process.isAlive() || observedDescendants.values().stream().anyMatch(ProcessHandle::isAlive))
                && System.nanoTime() < terminationDeadline) {
            pollDelayForBoundedProcessWait();
        }
        assertThat(process.isAlive() || observedDescendants.values().stream().anyMatch(ProcessHandle::isAlive))
                .as("timed-out process tree terminates: root pid %s", process.pid())
                .isFalse();
    }

    /// Sends SIGSTOP to the root and to every descendant before any of them is terminated, on Linux and
    /// macOS. A running process can start a child between a descendant scan and its own termination,
    /// for example from a `CHLD` trap that replaces a terminated child, and once that process dies the
    /// kernel moves the new child out of the tree where no later scan finds it. A stopped process starts
    /// no child and runs no trap. The fixture waits until `ps` reports each signalled process as stopped
    /// before it scans again, because a process can still finish a fork after `kill` returns. The scan
    /// after the last stop therefore sees every process the tree contains. See ADR 0129.
    static void stopProcessTree(Process process) throws IOException, InterruptedException {
        Set<Long> stoppedPids = new HashSet<>();
        List<ProcessHandle> unstopped = List.of(process.toHandle());
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(PROCESS_STOP_SECONDS);
        while (!unstopped.isEmpty() && System.nanoTime() < deadline) {
            runProcessHelper(command(List.of("kill", "-s", "STOP"), pidArguments(unstopped)));
            awaitStopped(unstopped, deadline);
            for (ProcessHandle stopped : unstopped) {
                stoppedPids.add(stopped.pid());
            }
            unstopped = new ArrayList<>();
            for (ProcessHandle descendant : process.descendants().toList()) {
                if (!stoppedPids.contains(descendant.pid())) {
                    unstopped.add(descendant);
                }
            }
        }
    }

    private static void awaitStopped(List<ProcessHandle> processes, long deadline)
            throws IOException, InterruptedException {
        String pids = String.join(",", pidArguments(processes));
        while (!allListedProcessesStopped(runProcessHelper(List.of("ps", "-o", "stat=", "-p", pids)))
                && System.nanoTime() < deadline) {
            Thread.sleep(PROCESS_STOP_POLL_MILLIS);
        }
    }

    /// `ps` prints no line for a process that exited and was reaped. `T` is a stopped process, `Z` one
    /// that exited while its stopped parent cannot reap it; neither can start a child.
    private static boolean allListedProcessesStopped(String psStates) {
        for (String state : PS_LINES.split(psStates)) {
            if (!state.startsWith("T") && !state.startsWith("Z")) {
                return false;
            }
        }
        return true;
    }

    private static String[] pidArguments(List<ProcessHandle> processes) {
        String[] pids = new String[processes.size()];
        for (int index = 0; index < pids.length; index++) {
            pids[index] = Long.toString(processes.get(index).pid());
        }
        return pids;
    }

    /// Runs `kill` or `ps` and returns its standard output. Both exit non-zero when a listed process
    /// already exited, and still handle the others, so the exit code is ignored.
    private static String runProcessHelper(List<String> command) throws IOException, InterruptedException {
        Path output = Files.createTempFile("symphony-trello-process-helper-", ".log");
        try {
            Process helper = new ProcessBuilder(command)
                    .redirectOutput(output.toFile())
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!helper.waitFor(Duration.ofSeconds(PROCESS_STOP_SECONDS))) {
                helper.destroyForcibly();
            }
            return Files.readString(output);
        } finally {
            Files.deleteIfExists(output);
        }
    }

    /// Windows offers no stop signal, so this best-effort loop terminates descendants until a scan finds
    /// none or the discovery window closes. A process that starts a child after the last scan can still
    /// leave that child running.
    private static void terminateDescendantsUntilNoneAppear(
            Process process, Map<Long, ProcessHandle> observedDescendants) throws InterruptedException {
        long discoveryDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(PROCESS_DESCENDANT_DISCOVERY_MILLIS);
        List<ProcessHandle> currentDescendants;
        do {
            currentDescendants = terminateCurrentDescendants(process, observedDescendants);
            if (currentDescendants.isEmpty()) {
                break;
            }
            pollDelayForBoundedProcessWait();
        } while (process.isAlive() && System.nanoTime() < discoveryDeadline);
    }

    private static List<ProcessHandle> terminateCurrentDescendants(
            Process process, Map<Long, ProcessHandle> observedDescendants) {
        List<ProcessHandle> currentDescendants = process.descendants().toList();
        for (ProcessHandle descendant : currentDescendants.reversed()) {
            observedDescendants.put(descendant.pid(), descendant);
            descendant.destroyForcibly();
        }
        return currentDescendants;
    }

    static void terminateProcessTreeImmediately(Process process) {
        List<ProcessHandle> descendants = process.descendants().toList();
        descendants.reversed().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    static Path singleFile(Path directory, String suffix) throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(path -> path.getFileName().toString().endsWith(suffix))
                    .reduce((first, second) -> {
                        throw new IllegalStateException("Expected one " + suffix + " file, found at least two");
                    })
                    .orElseThrow(() -> new IllegalStateException("Expected one " + suffix + " file in " + directory));
        }
    }

    static List<Path> regularFilesUnder(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(directory)) {
            return files.filter(Files::isRegularFile).toList();
        }
    }

    static boolean processIsRunning(long pid) throws IOException, InterruptedException {
        return run(Map.of(), "bash", "-c", "kill -0 " + pid).exitCode() == 0;
    }

    static boolean processStopsWithin(long pid, int timeoutSeconds) throws IOException, InterruptedException {
        return holdsWithin(timeoutSeconds, () -> !processIsRunning(pid));
    }

    static boolean fileContainsWithin(Path file, String text, int timeoutSeconds)
            throws IOException, InterruptedException {
        return holdsWithin(
                timeoutSeconds,
                () -> Files.exists(file) && Files.readString(file).contains(text));
    }

    private static boolean holdsWithin(int timeoutSeconds, PolledCondition condition)
            throws IOException, InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        while (System.nanoTime() < deadline) {
            if (condition.holds()) {
                return true;
            }
            pollDelayForBoundedProcessWait();
        }
        return condition.holds();
    }

    @FunctionalInterface
    private interface PolledCondition {
        boolean holds() throws IOException, InterruptedException;
    }

    /// The uninstaller's stop contract: it signals the worker, the worker is gone by the time the
    /// service is disabled, and the worker never sees its app directory removed while stopping. The
    /// uninstaller disables the service before it removes any installer-managed file, and it prints
    /// its own STOP line before it sends TERM. The worker's trap only runs once its current sleep
    /// ends, so a trap log line would not prove the signal on a stalled host. The contract holds
    /// whether the worker exits on TERM or the uninstaller has to KILL it after its grace.
    static void assertUninstallStoppedWorkerBeforeDisablingService(
            ProcessResult uninstall, String uninstallLog, String workerLabel, long workerPid) {
        assertThat(uninstall.output())
                .containsSubsequence(
                        "STOP  " + workerLabel + " pid=" + workerPid + "\n",
                        "STOP  user systemd service: symphony-trello.service\n");
        assertThat(uninstallLog)
                .as("fake tool log during uninstall; uninstall output:%n%s", uninstall.output())
                .containsSubsequence(
                        "systemctl --user disable --now symphony-trello.service\n",
                        "systemctl-disable worker pid=" + workerPid + " stopped\n")
                .doesNotContain("systemctl-disable worker pid=" + workerPid + " alive", "app-missing-before-exit");
    }

    // The managed start path embeds the fake worker's TERM handler in a bash -c '...' script.
    private static String singleQuoteFree(String script) {
        checkArgument(!script.contains("'"), "script must not contain a single quote: %s", script);
        return script;
    }

    private static String withPlaceholders(String script, Map<String, String> values) {
        String filled = script;
        for (Map.Entry<String, String> value : values.entrySet()) {
            checkArgument(filled.contains(value.getKey()), "script has no placeholder %s", value.getKey());
            filled = filled.replace(value.getKey(), value.getValue());
        }
        return filled;
    }

    private static void pollDelayForBoundedProcessWait() throws InterruptedException {
        Thread.sleep(100);
    }

    static String shellQuote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    /// Creates a directory that exposes only the named host commands, for a PATH whose output must
    /// not change with optional tools a host or CI runner happens to have, such as `curl`, `node`,
    /// or `npm`. A missing command fails the test instead of skipping it, so a runner without it
    /// cannot silently drop required coverage.
    static Path hostCommandDirectory(Path directory, List<String> commands) throws IOException {
        Files.createDirectories(directory);
        for (String command : commands) {
            writeCommandProxy(
                    directory, command, shellQuote(hostExecutable(command).toString()));
        }
        return directory;
    }

    private static Path hostExecutable(String command) {
        for (String directory : searchPathEntries(System.getenv().getOrDefault("PATH", ""))) {
            Path candidate = Path.of(directory, command);
            if (Files.isExecutable(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException(command + " is required on PATH for this test");
    }

    /// Splits a PATH-style search path, ignoring empty entries.
    static Iterable<String> searchPathEntries(String searchPath) {
        return Splitter.on(File.pathSeparatorChar).omitEmptyStrings().split(searchPath);
    }

    static boolean commandExists(String name) {
        try {
            Process process = new ProcessBuilder(name, "--version").start();
            return process.waitFor(Duration.ofSeconds(10)) && process.exitValue() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    static boolean dockerDaemonIsUsable() {
        try {
            Process process = new ProcessBuilder("docker", "info", "--format", "{{.ServerVersion}}")
                    .redirectErrorStream(true)
                    .start();
            boolean completed = process.waitFor(Duration.ofSeconds(10));
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            return completed
                    && process.exitValue() == 0
                    && !output.isBlank()
                    && !output.toLowerCase(Locale.ROOT).contains("permission denied");
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    static List<String> powershellCommand() {
        String configured = System.getenv("SYMPHONY_TRELLO_TEST_PWSH");
        if (configured != null && !configured.isBlank()) {
            Path command = Path.of(configured);
            if (Files.isExecutable(command)) {
                return List.of(command.toString());
            }
        }
        if (!isWindows()) {
            return List.of();
        }
        if (commandExists("pwsh")) {
            return List.of("pwsh");
        }
        return List.of();
    }

    static List<String> command(List<String> executable, String... arguments) {
        return Stream.concat(executable.stream(), Stream.of(arguments)).toList();
    }

    static Map<String, String> nonWindowsPowerShellEnvironment() {
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            return Map.of();
        }
        return Map.of("SYMPHONY_TRELLO_ALLOW_NON_WINDOWS_PWSH_FOR_TEST", "1");
    }

    static String powerShellLiteral(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    static String installerDefaultRef() throws IOException {
        return "v" + installerDefaultVersion();
    }

    /// The release version `install.sh` installs by default; Release Please bumps it every release.
    static String installerDefaultVersion() throws IOException {
        String installScript = Files.readString(Path.of("install.sh"));
        return POSIX_INSTALLER_DEFAULT_VERSION
                .matcher(installScript)
                .results()
                .findAny()
                .map(match -> match.group(1))
                .orElseThrow(() -> new IllegalStateException("install.sh is missing the Release Please default ref"));
    }

    static Path createSourceRepository(Path temporaryDirectory) throws Exception {
        Path repository = temporaryDirectory.resolve("source");
        Files.createDirectories(repository);
        Files.writeString(repository.resolve("pom.xml"), "<project><version>1.0.0</version></project>\n");
        Files.writeString(repository.resolve("WORKFLOW.example.md"), "# Example workflow\n");
        Files.writeString(
                repository.resolve("mvnw"),
                """
                #!/usr/bin/env bash
                set -euo pipefail
                echo "mvnw $*" >> "${SYMPHONY_FAKE_LOG:?}"
                echo "build-cwd=$PWD" >> "${SYMPHONY_FAKE_LOG:?}"
                app_home="$(cd "$(dirname "$0")" && pwd -P)"
                mkdir -p "$app_home/target/quarkus-app/app" "$app_home/target/quarkus-app/lib/main" "$app_home/target/quarkus-app/quarkus"
                : > "$app_home/target/quarkus-app/quarkus-run.jar"
                """);
        repository.resolve("mvnw").toFile().setExecutable(true);
        initializeSourceRepository(repository);
        return repository;
    }

    static Path createWindowsSourceRepository(Path temporaryDirectory) throws Exception {
        Path repository = temporaryDirectory.resolve("windows source");
        Files.createDirectories(repository);
        Files.writeString(repository.resolve("pom.xml"), "<project><version>1.0.0</version></project>\n");
        Files.writeString(repository.resolve("WORKFLOW.example.md"), "# Example workflow\n");
        Files.writeString(
                repository.resolve("mvnw.cmd"),
                """
                @echo off
                setlocal DisableDelayedExpansion
                echo mvnw.cmd %* >> "%SYMPHONY_FAKE_LOG%"
                rem SET prints the path without parsing its metacharacters as commands.
                set "build-cwd=%CD%"
                set build-cwd>> "%SYMPHONY_FAKE_LOG%"
                mkdir target\\quarkus-app\\app 2>nul
                mkdir target\\quarkus-app\\lib\\main 2>nul
                mkdir target\\quarkus-app\\quarkus 2>nul
                type nul > target\\quarkus-app\\quarkus-run.jar
                """);
        initializeSourceRepository(repository);
        return repository;
    }

    private static void initializeSourceRepository(Path repository) throws Exception {
        run(Map.of(), "git", "-C", repository.toString(), "init", "-b", "main").assertSuccess();
        run(Map.of(), "git", "-C", repository.toString(), "config", "user.name", "Test User")
                .assertSuccess();
        run(Map.of(), "git", "-C", repository.toString(), "config", "user.email", "test@example.invalid")
                .assertSuccess();
        run(Map.of(), "git", "-C", repository.toString(), "add", ".").assertSuccess();
        run(Map.of(), "git", "-C", repository.toString(), "commit", "-m", "Initial test source")
                .assertSuccess();
        run(Map.of(), "git", "-C", repository.toString(), "tag", installerDefaultRef())
                .assertSuccess();
    }

    static void addSourceRepositoryCommit(Path repository, String relativePath, String content) throws Exception {
        Files.writeString(repository.resolve(relativePath), content);
        run(Map.of(), "git", "-C", repository.toString(), "add", relativePath).assertSuccess();
        run(Map.of(), "git", "-C", repository.toString(), "commit", "-m", "Update test source")
                .assertSuccess();
    }

    static String pinnedRef(Path repository, String refType) throws Exception {
        return switch (refType) {
            case "tag" -> {
                run(Map.of(), "git", "-C", repository.toString(), "tag", "v-test-install")
                        .assertSuccess();
                yield "v-test-install";
            }
            case "sha" -> {
                ProcessResult result = run(Map.of(), "git", "-C", repository.toString(), "rev-parse", "HEAD");
                result.assertSuccess();
                yield result.output().trim();
            }
            default -> throw new IllegalArgumentException("Unknown ref type: " + refType);
        };
    }

    static Path createFakeToolchain(Path temporaryDirectory) throws IOException {
        Path fakeBin = temporaryDirectory.resolve("fake-bin");
        Files.createDirectories(fakeBin);
        writeExecutable(
                fakeBin.resolve("codex"),
                """
                #!/usr/bin/env bash
                set -euo pipefail
                echo "codex $*" >> "${SYMPHONY_FAKE_LOG:?}"
                if [[ "${1:-}" == "login" && "${2:-}" == "status" ]]; then
                  [[ -f "$(dirname "$SYMPHONY_FAKE_LOG")/codex-authenticated" ]]
                  exit $?
                fi
                if [[ "${1:-}" == "login" ]]; then
                  : > "$(dirname "$SYMPHONY_FAKE_LOG")/codex-authenticated"
                  exit 0
                fi
                exit 0
                """);
        writeExecutable(
                fakeBin.resolve("systemctl"),
                """
                #!/usr/bin/env bash
                set -euo pipefail
                echo "systemctl $*" >> "${SYMPHONY_FAKE_LOG:?}"
                if [[ -n "${SYMPHONY_FAKE_SYSTEMD_UNAVAILABLE:-}" && "$*" == "--user show-environment" ]]; then
                  exit 1
                fi
                if [[ -n "${SYMPHONY_FAKE_SYSTEMD_ENABLE_FAILURE:-}" && "$*" == "--user enable symphony-trello.service" ]]; then
                  exit 1
                fi
                config_home="${XDG_CONFIG_HOME:-$HOME/.config}"
                service_file="$config_home/systemd/user/symphony-trello.service"
                command_path=""
                if [[ -f "$service_file" ]]; then
                  command_path="$(sed -n 's/^ExecStart="\\([^"]*symphony-trello\\)" .*/\\1/p' "$service_file" | head -1)"
                fi
                if [[ "$*" == "--user restart symphony-trello.service" && -n "$command_path" ]]; then
                  "$command_path" start --all
                elif [[ "$*" == "--user disable --now symphony-trello.service" ]]; then
                  while read -r worker_pid; do
                    if kill -0 "$worker_pid" >/dev/null 2>&1; then
                      echo "systemctl-disable worker pid=$worker_pid alive" >> "${SYMPHONY_FAKE_LOG:?}"
                    else
                      echo "systemctl-disable worker pid=$worker_pid stopped" >> "${SYMPHONY_FAKE_LOG:?}"
                    fi
                  done < <(sed -n 's/^jar-start pid=\\([0-9][0-9]*\\) .*/\\1/p' "${SYMPHONY_FAKE_LOG:?}")
                  if [[ -n "$command_path" ]]; then
                    "$command_path" stop
                  fi
                fi
                exit 0
                """);
        writeExecutable(
                fakeBin.resolve("loginctl"),
                """
                #!/usr/bin/env bash
                set -euo pipefail
                echo "loginctl $*" >> "${SYMPHONY_FAKE_LOG:?}"
                if [[ -n "${SYMPHONY_FAKE_LINGER_FAILURE:-}" ]]; then
                  exit 1
                fi
                exit 0
                """);
        writeExecutable(
                fakeBin.resolve("java"),
                """
                #!/usr/bin/env bash
                set -euo pipefail
                if [[ "${1:-}" == "-version" ]]; then
                  echo 'openjdk version "25.0.1" 2026-04-21' >&2
                  exit 0
                fi
                if [[ "$*" == *"ch.fmartin.symphony.trello.setup.TrelloBoardSetupMain"* ]]; then
                  java_args=()
                  raw_cli_args=()
                  found_main=false
                  for value in "$@"; do
                    if [[ "$found_main" == true ]]; then
                      raw_cli_args+=("$value")
                    elif [[ "$value" == "ch.fmartin.symphony.trello.setup.TrelloBoardSetupMain" ]]; then
                      found_main=true
                    else
                      java_args+=("$value")
                    fi
                  done
                  installed_property() {
                    local name="$1"
                    for value in "${java_args[@]}"; do
                      if [[ "$value" == "-D${name}="* ]]; then
                        printf '%s' "${value#-D${name}=}"
                        return 0
                      fi
                    done
                    return 1
                  }
                  same_path() {
                    [[ "$(/usr/bin/readlink -m "$1")" == "$(/usr/bin/readlink -m "$2")" ]]
                  }
                  has_option() {
                    local option="$1"
                    shift
                    for value in "$@"; do
                      if [[ "$value" == "$option" || "$value" == "$option="* ]]; then
                        return 0
                      fi
                    done
                    return 1
                  }
                  option_value() {
                    local option="$1"
                    shift
                    local values=("$@")
                    for ((index = 0; index < ${#values[@]}; index++)); do
                      local value="${values[$index]}"
                      if [[ "$value" == "$option" && $((index + 1)) -lt ${#values[@]} ]]; then
                        printf '%s' "${values[$((index + 1))]}"
                        return 0
                      fi
                      if [[ "$value" == "$option="* ]]; then
                        printf '%s' "${value#${option}=}"
                        return 0
                      fi
                    done
                    return 1
                  }
                  setup_local_value_option() {
                    case "$1" in
                      --key|--token|--board-name|--board|--workspace-id|--active|--terminal|--in-progress|--blocked|--workflow|--workspace-root|--config-dir|--manifest|--server-port|--max-agents|--codex-model|--codex-reasoning-effort|--env|--add-path|--endpoint)
                        return 0
                        ;;
                      *)
                        return 1
                        ;;
                    esac
                  }
                  setup_local_lifecycle() {
                    local values=("${raw_cli_args[@]:1}")
                    for ((index = 0; index < ${#values[@]}; index++)); do
                      local value="${values[$index]}"
                      case "$value" in
                        check|repair-port|configure-github)
                          return 0
                          ;;
                      esac
                      if setup_local_value_option "$value"; then
                        index=$((index + 1))
                      elif [[ "$value" != --* ]]; then
                        return 1
                      fi
                    done
                    return 1
                  }
                  config_dir="${SYMPHONY_TRELLO_CONFIG_DIR:-$(installed_property symphony.trello.installed.config.dir || true)}"
                  workspace_root="${SYMPHONY_TRELLO_WORKSPACE_ROOT:-}"
                  state_home="${SYMPHONY_TRELLO_STATE_HOME:-}"
                  app_home="${SYMPHONY_TRELLO_APP_HOME:-$(installed_property symphony.trello.installed.app.home || true)}"
                  installed_workspace_root="$(installed_property symphony.trello.installed.workspace.root || true)"
                  installed_state_home="$(installed_property symphony.trello.installed.state.home || true)"
                  workspace_from_user_environment=false
                  state_from_user_environment=false
                  if [[ -n "$workspace_root" && -n "$installed_workspace_root" ]] && ! same_path "$workspace_root" "$installed_workspace_root"; then
                    workspace_from_user_environment=true
                  fi
                  if [[ -n "$state_home" && -n "$installed_state_home" ]] && ! same_path "$state_home" "$installed_state_home"; then
                    state_from_user_environment=true
                  fi
                  defaults=()
                  command="${raw_cli_args[0]:-}"
                  explicit_config="$(option_value --config-dir "${raw_cli_args[@]}" || true)"
                  case "$command" in
                    setup-local)
                      if ! has_option --config-dir "${raw_cli_args[@]}" && [[ -n "$config_dir" ]]; then
                        defaults+=("--config-dir" "$config_dir")
                      fi
                      if ! setup_local_lifecycle; then
                        if [[ -z "$explicit_config" ]]; then
                          if [[ -n "$workspace_root" ]] && ! has_option --workspace-root "${raw_cli_args[@]}"; then
                            defaults+=("--workspace-root" "$workspace_root")
                          fi
                        else
                          if [[ "$workspace_from_user_environment" == true ]]; then
                            if ! has_option --workspace-root "${raw_cli_args[@]}"; then
                              defaults+=("--workspace-root" "$workspace_root")
                            fi
                          else
                            if ! has_option --workspace-root "${raw_cli_args[@]}"; then
                              defaults+=("--workspace-root" "$(/usr/bin/readlink -m "$explicit_config")/workspaces")
                            fi
                          fi
                        fi
                      fi
                      ;;
                    new-board|import-board)
                      if [[ -n "$workspace_root" ]] && ! has_option --workspace-root "${raw_cli_args[@]}"; then
                        defaults+=("--workspace-root" "$workspace_root")
                      fi
                      ;;
                    start|stop|status|logs|diagnostics)
                      if [[ -z "$explicit_config" ]]; then
                        [[ -n "$config_dir" ]] && defaults+=("--config-dir" "$config_dir")
                        if [[ -n "$workspace_root" ]] && ! has_option --workspace-root "${raw_cli_args[@]}"; then
                          defaults+=("--workspace-root" "$workspace_root")
                        fi
                        if [[ -n "$state_home" ]] && ! has_option --state-home "${raw_cli_args[@]}"; then
                          defaults+=("--state-home" "$state_home")
                        fi
                      else
                        normalized_config="$(/usr/bin/readlink -m "$explicit_config")"
                        if [[ "$workspace_from_user_environment" == true ]]; then
                          if ! has_option --workspace-root "${raw_cli_args[@]}"; then
                            defaults+=("--workspace-root" "$workspace_root")
                          fi
                        else
                          if ! has_option --workspace-root "${raw_cli_args[@]}"; then
                            defaults+=("--workspace-root" "$normalized_config/workspaces")
                          fi
                        fi
                        if [[ "$state_from_user_environment" == true ]]; then
                          if ! has_option --state-home "${raw_cli_args[@]}"; then
                            defaults+=("--state-home" "$state_home")
                          fi
                        else
                          if ! has_option --state-home "${raw_cli_args[@]}"; then
                            defaults+=("--state-home" "$(dirname "$normalized_config")/state")
                          fi
                        fi
                      fi
                      if [[ -n "$app_home" ]] && ! has_option --app-home "${raw_cli_args[@]}"; then
                        defaults+=("--app-home" "$app_home")
                      fi
                      ;;
                  esac
                  effective_cli_args=("${raw_cli_args[@]}")
                  if [[ ${#defaults[@]} -gt 0 ]]; then
                    effective_cli_args=("${raw_cli_args[0]}" "${defaults[@]}" "${raw_cli_args[@]:1}")
                  fi
                  echo "setup-cli cwd=$PWD ${java_args[*]} ch.fmartin.symphony.trello.setup.TrelloBoardSetupMain ${effective_cli_args[*]} dotenv=${SYMPHONY_TRELLO_DOTENV:-} workspace_env=${SYMPHONY_TRELLO_WORKSPACE_ROOT:-} state_env=${SYMPHONY_TRELLO_STATE_HOME:-} completion_mode=${SYMPHONY_TRELLO_INSTALLER_COMPLETION:-}" >> "${SYMPHONY_FAKE_LOG:?}"
                  if [[ "$*" == *"definitely-not-a-command"* ]]; then
                    echo "setup_failed code=setup_invalid_arguments message=Unmatched argument: 'definitely-not-a-command'" >&2
                    exit 2
                  fi
                  if [[ "$*" == *"--help"* || "$*" == *" -h"* ]]; then
                    if [[ "${raw_cli_args[0]:-}" == "migrate-workflows" ]]; then
                      if [[ -n "${SYMPHONY_FAKE_MIGRATION_HELP_FAILURE:-}" ]]; then
                        echo "simulated migration help failure" >&2
                        exit 23
                      fi
                      if [[ -n "${SYMPHONY_FAKE_MIGRATION_HELP:-}" ]]; then
                        printf '%s\\n' "$SYMPHONY_FAKE_MIGRATION_HELP"
                      elif [[ -n "${SYMPHONY_FAKE_WITHOUT_WORKFLOW_MIGRATION:-}" ]]; then
                        echo "Usage: symphony-trello"
                      else
                        echo "Usage: symphony-trello migrate-workflows"
                      fi
                    else
                      echo "Usage: symphony-trello"
                    fi
                    exit 0
                  fi
                  if [[ -n "${SYMPHONY_FAKE_WITHOUT_WORKFLOW_MIGRATION:-}" && "${raw_cli_args[0]:-}" == "migrate-workflows" ]]; then
                    echo "setup_failed code=setup_invalid_arguments message=Unmatched argument: 'migrate-workflows'" >&2
                    exit 2
                  fi
                  if [[ -n "${SYMPHONY_FAKE_WORKFLOW_MIGRATION_FAILURE:-}" && "${raw_cli_args[0]:-}" == "migrate-workflows" ]]; then
                    echo "  REJECTED  simulated workflow migration rejection"
                    exit 2
                  fi
                  if [[ "$*" == *"--version"* ]]; then
                    case "${SYMPHONY_FAKE_VERSION_MODE:-ok}" in
                      fail)
                        echo "version failed" >&2
                        exit 23
                        ;;
                      malformed)
                        echo "not a symphony version"
                        exit 0
                        ;;
                      multiline)
                        echo "diagnostic preface"
                        echo "symphony-trello @FAKE_APP_VERSION@"
                        exit 0
                        ;;
                      *)
                        echo "symphony-trello ${SYMPHONY_FAKE_VERSION:-@FAKE_APP_VERSION@}"
                        exit 0
                        ;;
                    esac
                  fi
	                if [[ -n "${SYMPHONY_FAKE_START_ALL_FAILURE:-}" && "${effective_cli_args[0]:-}" == "start" && " ${effective_cli_args[*]} " == *" --all "* ]]; then
	                  echo "managed start --all failed" >&2
	                  exit 29
	                fi
	                fi
	                if [[ "${effective_cli_args[0]:-}" == "start" || "${effective_cli_args[0]:-}" == "status" || "${effective_cli_args[0]:-}" == "stop" || "${effective_cli_args[0]:-}" == "logs" || "${effective_cli_args[0]:-}" == "diagnostics" ]]; then
	                  cli_args=("${effective_cli_args[@]}")
	                  command="${cli_args[0]:-}"
		                  config_dir="."
		                  state_home="."
		                  app_home="$PWD"
		                  env_file=""
		                  workflow=""
	                  for ((i = 1; i < ${#cli_args[@]}; i++)); do
	                    case "${cli_args[$i]}" in
		                      --config-dir) i=$((i + 1)); config_dir="${cli_args[$i]}" ;;
		                      --state-home) i=$((i + 1)); state_home="${cli_args[$i]}" ;;
		                      --app-home) i=$((i + 1)); app_home="${cli_args[$i]}" ;;
		                      --env) i=$((i + 1)); env_file="${cli_args[$i]}" ;;
	                      --workflow) i=$((i + 1)); workflow="${cli_args[$i]}" ;;
	                    esac
	                  done
		                  if [[ -z "$workflow" && -f "$config_dir/connected-boards.json" ]]; then
		                    workflow="$(sed -n 's/.*"workflowPath"[[:blank:]]*:[[:blank:]]*"\\([^"]*\\)".*/\\1/p' "$config_dir/connected-boards.json" | head -1)"
		                  fi
		                  if [[ -z "$env_file" && -f "$config_dir/connected-boards.json" ]]; then
		                    env_file="$(sed -n 's/.*"envPath"[[:blank:]]*:[[:blank:]]*"\\([^"]*\\)".*/\\1/p' "$config_dir/connected-boards.json" | head -1)"
		                  fi
		                  mkdir -p "$state_home"
	                  display_name="$(basename "${workflow:-WORKFLOW.md}")"
                  state_name="$display_name.abcdef123456"
	                  pid_file="$state_home/$state_name.pid"
	                  log_file="$state_home/$state_name.log"
	                  case "$command" in
	                    start)
		                      if [[ -f "$pid_file" ]]; then
		                        echo "Symphony for Trello is already running for $workflow pid=$(cat "$pid_file")"
		                        exit 0
		                      fi
		                      bash -c '
                        app_home="$1"
                        workflow="$2"
                        env_file="$3"
                        @FAKE_WORKER_TERM_HANDLER@
                        echo "jar-start pid=$$ cwd=$PWD args=-jar quarkus-run.jar $workflow dotenv=$env_file" >> "${SYMPHONY_FAKE_LOG:?}"
                        while true; do sleep 1; done
                      ' symphony-worker "$app_home" "$workflow" "$env_file" "-Dsymphony.trello.managed.app_home=$app_home" "$app_home/target/quarkus-app/quarkus-run.jar" >"$log_file" 2>"$log_file.err" &
	                      worker_pid="$!"
	                      # The real start returns only after the worker passes its health check. Waiting for
	                      # jar-start means a stop that follows can never reach the worker before its TERM trap.
	                      ready_deadline=$((SECONDS + @FAKE_WORKER_READY_SECONDS@))
	                      until grep -q "^jar-start pid=$worker_pid " "${SYMPHONY_FAKE_LOG:?}"; do
	                        if ! kill -0 "$worker_pid" >/dev/null 2>&1 || ((SECONDS >= ready_deadline)); then
	                          echo "Fake worker pid=$worker_pid did not report jar-start" >&2
	                          exit 1
	                        fi
	                        sleep 0.1
	                      done
	                      echo "$worker_pid" > "$pid_file"
	                      echo "Started Symphony for Trello: $workflow"
	                      echo "Log: $log_file"
	                      exit 0
	                      ;;
	                    status)
		                      if [[ -f "$pid_file" ]]; then
		                        echo "running $display_name pid=$(cat "$pid_file")"
	                      elif [[ -n "$workflow" ]]; then
	                        echo "stopped $display_name"
	                      else
	                        echo "No managed Symphony process found"
	                      fi
	                      exit 0
	                      ;;
	                    stop)
	                      if [[ -f "$pid_file" ]]; then
	                        worker_pid="$(cat "$pid_file")"
	                        # Same TERM grace and KILL fallback as LocalWorkerManager, so "Stopped" always
	                        # means the worker is gone and no late TERM-handler output can land after it.
	                        worker_exits_within() {
	                          local deadline=$((SECONDS + $1))
	                          while kill -0 "$worker_pid" >/dev/null 2>&1 && ((SECONDS < deadline)); do
	                            sleep 0.1
	                          done
	                          ! kill -0 "$worker_pid" >/dev/null 2>&1
	                        }
	                        kill "$worker_pid" >/dev/null 2>&1 || true
	                        if ! worker_exits_within @WORKER_STOP_GRACE_SECONDS@; then
	                          kill -KILL "$worker_pid" >/dev/null 2>&1 || true
	                          if ! worker_exits_within @WORKER_KILL_WAIT_SECONDS@; then
	                            echo "Fake worker pid=$worker_pid did not stop" >&2
	                            exit 1
	                          fi
	                        fi
	                        rm -f "$pid_file"
	                      fi
	                      echo "Stopped $display_name"
	                      exit 0
	                      ;;
	                    logs)
	                      if [[ " ${cli_args[*]} " == *" --follow "* ]]; then
	                        tail -n 100 -f "$log_file"
	                      else
	                        tail -n 100 "$log_file"
	                      fi
	                      exit 0
	                      ;;
	                  esac
	                fi
                if [[ "${effective_cli_args[0]:-}" == "tutorial" && " ${effective_cli_args[*]} " == *" --offer "* ]]; then
                  if [[ -t 0 && -t 1 ]]; then
                    printf '\\n@TUTORIAL_OFFER_PROMPT@'
                    IFS= read -r answer || true
                    echo "You can start it later with:"
                    echo "  symphony-trello tutorial"
                  fi
                  exit 0
                fi
	                if [[ "${effective_cli_args[0]:-}" == "setup-local" ]]; then
                  config_dir="."
                  cli_args=("${effective_cli_args[@]}")
                  for ((index = 1; index < ${#cli_args[@]}; index++)); do
                    if [[ "${cli_args[$index]}" == "--config-dir" && $((index + 1)) -lt ${#cli_args[@]} ]]; then
                      config_dir="${cli_args[$((index + 1))]}"
                      index=$((index + 1))
                    elif [[ "${cli_args[$index]}" == "--config-dir="* ]]; then
                      config_dir="${cli_args[$index]#--config-dir=}"
                    else
                      :
                    fi
                  done
                  if [[ "${SYMPHONY_TRELLO_INSTALLER_COMPLETION:-}" == "print" ]]; then
                    board="$(sed -n 's/.*"boardName"[[:blank:]]*:[[:blank:]]*"\\([^"]*\\)".*/\\1/p' "$config_dir/connected-boards.json" | head -1)"
                    workflow="$(sed -n 's/.*"workflowPath"[[:blank:]]*:[[:blank:]]*"\\([^"]*\\)".*/\\1/p' "$config_dir/connected-boards.json" | head -1)"
                    echo "You're good to go - your Trello board is now a queue for Codex work."
                    echo "Connected board: \"$board\""
                    echo "Workflow: $workflow"
                    echo
                    echo "Create a Trello card with a clear task and move it to this workflow's configured queue list."
                    echo
                    echo "Useful commands:"
                    echo "  symphony-trello status"
                    printf "  symphony-trello logs --workflow '%s'\\n" "$workflow"
                    exit 0
                  fi
                  mkdir -p "$config_dir"
                  if [[ -t 0 ]] && command -v stty >/dev/null 2>&1; then
                    # Like Java's Console.readPassword, hide the echo before showing a secret prompt.
                    # set -e exits on a failed read; the trap still restores the echo.
                    trap 'stty echo' EXIT
                    stty -echo
                    printf '@TRELLO_KEY_PROMPT@'
                    IFS= read -r key
                    printf '\\n'
                    printf '@TRELLO_TOKEN_PROMPT@'
                    IFS= read -r token
                    stty echo
                    trap - EXIT
                    printf '\\n'
                    printf '@TRELLO_BOARD_PROMPT@'
                    IFS= read -r board
                  else
                    read -r key
                    read -r token
                    read -r board
                  fi
                  slug="$(printf '%s' "$board" | tr '[:upper:]' '[:lower:]' | sed 's/[^a-z0-9][^a-z0-9]*/-/g; s/^-//; s/-$//')"
                  workflow="$config_dir/WORKFLOW.${slug:-trello-board}.md"
                  printf 'TRELLO_API_KEY=%s\\nTRELLO_API_TOKEN=%s\\n' "$key" "$token" > "$config_dir/.env"
                  printf -- '---\\ntracker:\\n  kind: trello\\n---\\n# %s\\n' "$board" > "$workflow"
                  printf '{"boards":[{"boardId":"board-1","boardName":"%s","workflowPath":"%s","envPath":"%s/.env"}]}\\n' "$board" "$workflow" "$config_dir" > "$config_dir/connected-boards.json"
	                  echo "setup-local key=$key token=$token board=$board" >> "${SYMPHONY_FAKE_LOG:?}"
	                  env -u SYMPHONY_TRELLO_INSTALLER_COMPLETION "${SYMPHONY_TRELLO_COMMAND:?}" start --env "$config_dir/.env" --workflow "$workflow"
                  if [[ "${SYMPHONY_TRELLO_INSTALLER_COMPLETION:-}" != "defer" ]]; then
                    echo "You're good to go - your Trello board is now a queue for Codex work."
                  fi
                  exit 0
                fi
                if [[ "$*" == *"-jar "* ]]; then
                  app_home="$PWD"
                  @FAKE_WORKER_TERM_HANDLER@
                  echo "jar-start pid=$$ cwd=$PWD args=$* dotenv=${SYMPHONY_TRELLO_DOTENV:-}" >> "${SYMPHONY_FAKE_LOG:?}"
                  while true; do sleep 1; done
                fi
                echo "java $*" >> "${SYMPHONY_FAKE_LOG:?}"
                exit 0
                """
                        .transform(script -> withPlaceholders(
                                script,
                                Map.of(
                                        "@FAKE_APP_VERSION@",
                                        FAKE_APP_VERSION,
                                        "@TRELLO_KEY_PROMPT@",
                                        TRELLO_KEY_PROMPT,
                                        "@TRELLO_TOKEN_PROMPT@",
                                        TRELLO_TOKEN_PROMPT,
                                        "@TRELLO_BOARD_PROMPT@",
                                        TRELLO_BOARD_PROMPT,
                                        "@TUTORIAL_OFFER_PROMPT@",
                                        GuidedTutorial.OFFER_PROMPT,
                                        "@FAKE_WORKER_TERM_HANDLER@",
                                        FAKE_WORKER_TERM_HANDLER,
                                        "@FAKE_WORKER_READY_SECONDS@",
                                        Integer.toString(FAKE_WORKER_READY_SECONDS),
                                        "@WORKER_STOP_GRACE_SECONDS@",
                                        Long.toString(LocalWorkerManager.WORKER_STOP_GRACE.toSeconds()),
                                        "@WORKER_KILL_WAIT_SECONDS@",
                                        Long.toString(LocalWorkerManager.WORKER_KILL_WAIT.toSeconds())))));
        writeExecutable(
                fakeBin.resolve("javac"),
                """
                #!/usr/bin/env bash
                set -euo pipefail
                echo 'javac 25.0.1'
                """);
        return fakeBin;
    }

    static Path createPowerShellSourceRepository(Path temporaryDirectory) throws Exception {
        if (isWindows()) {
            return createWindowsSourceRepository(temporaryDirectory);
        }
        Path repository = createSourceRepository(temporaryDirectory);
        writeExecutable(
                repository.resolve("mvnw.cmd"),
                """
                #!/usr/bin/env bash
                set -euo pipefail
                echo "mvnw.cmd $*" >> "${SYMPHONY_FAKE_LOG:?}"
                echo "build-cwd=$PWD" >> "${SYMPHONY_FAKE_LOG:?}"
                app_home="$(cd "$(dirname "$0")" && pwd -P)"
                mkdir -p "$app_home/target/quarkus-app/app" "$app_home/target/quarkus-app/lib/main" "$app_home/target/quarkus-app/quarkus"
                : > "$app_home/target/quarkus-app/quarkus-run.jar"
                """);
        run(Map.of(), "git", "-C", repository.toString(), "add", "mvnw.cmd").assertSuccess();
        run(Map.of(), "git", "-C", repository.toString(), "commit", "-m", "Add PowerShell test wrapper")
                .assertSuccess();
        return repository;
    }

    static Path createPowerShellFakeToolchain(Path temporaryDirectory) throws IOException {
        if (isWindows()) {
            return createFakeWindowsToolchain(temporaryDirectory);
        }
        return createFakeToolchain(temporaryDirectory);
    }

    /// Writes the release assets this platform's installer downloads: the archive with a placeholder
    /// app, SHA3-256 `checksums.txt`, and optionally a placeholder signature bundle for the fake
    /// GitHub CLI. PowerShell installs the zip and the POSIX installer the tar.gz archive.
    static Path createReleaseAssets(Path temporaryDirectory, String version, boolean withSignatureBundle)
            throws Exception {
        String rootName = "symphony-trello-" + version;
        Path staging = temporaryDirectory.resolve("release-staging-" + version);
        Path assets = temporaryDirectory.resolve("release-assets-" + version);
        Path quarkusApp = staging.resolve(rootName).resolve("target/quarkus-app");
        Files.createDirectories(quarkusApp);
        Files.createDirectories(assets);
        Files.writeString(quarkusApp.resolve("quarkus-run.jar"), "placeholder app\n");
        Path archive;
        if (isWindows()) {
            archive = assets.resolve(rootName + ".zip");
            try (var zip = new ZipOutputStream(Files.newOutputStream(archive))) {
                zip.putNextEntry(new ZipEntry(rootName + "/target/quarkus-app/quarkus-run.jar"));
                zip.write(Files.readAllBytes(quarkusApp.resolve("quarkus-run.jar")));
                zip.closeEntry();
            }
        } else {
            archive = assets.resolve(rootName + ".tar.gz");
            run(Map.of(), "tar", "-czf", archive.toString(), "-C", staging.toString(), rootName)
                    .assertSuccess();
        }
        Files.writeString(assets.resolve("checksums.txt"), sha3Hex(archive) + "  " + archive.getFileName() + "\n");
        if (withSignatureBundle) {
            Files.writeString(assets.resolve(rootName + ".intoto.jsonl"), "{\"placeholder\":\"bundle\"}\n");
        }
        return assets;
    }

    private static String sha3Hex(Path file) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA3-256").digest(Files.readAllBytes(file)));
    }

    /// Serves release assets over HTTP because PowerShell 7 cannot download `file:` URLs.
    static HttpServer serveReleaseAssets(Path assets) throws IOException {
        HttpServer server = SimpleFileServer.createFileServer(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
                assets.toAbsolutePath(),
                SimpleFileServer.OutputLevel.NONE);
        server.start();
        return server;
    }

    static String releaseAssetsUrl(HttpServer server) throws URISyntaxException {
        InetSocketAddress address = server.getAddress();
        return new URI("http", null, address.getAddress().getHostAddress(), address.getPort(), null, null, null)
                .toString();
    }

    /// Writes a GitHub CLI double for release signature checks. `SYMPHONY_FAKE_GH_ATTESTATION`
    /// selects `verified`, `rejected`, `outdated`, or `unsupported`. The outdated CLI
    /// has the command but lacks current trust support. Calls go to `SYMPHONY_FAKE_LOG` with the `GH_HOST` they
    // saw.
    static Path createFakeGitHubCli(Path temporaryDirectory) throws IOException {
        Path fakeBin = temporaryDirectory.resolve("fake-gh-bin");
        Files.createDirectories(fakeBin);
        if (isWindows()) {
            writeFakeWindowsGitHubCli(fakeBin);
            return fakeBin;
        }
        writeExecutable(
                fakeBin.resolve("gh"),
                """
                #!/usr/bin/env bash
                set -euo pipefail
                echo "gh GH_HOST=${GH_HOST:-} $*" >> "${SYMPHONY_FAKE_LOG:?}"
                mode="${SYMPHONY_FAKE_GH_ATTESTATION:-verified}"
                if [[ "${1:-}" == "--version" ]]; then
                  if [[ "$mode" == "outdated" ]]; then echo 'gh version 2.49.0 (2024-05-08)';
                  elif [[ "$mode" == "packaged" ]]; then echo 'gh version 2.65.0+dfsg1 (2025-01-06)';
                  else echo 'gh version 2.65.0 (2025-01-06)'; fi
                  exit 0
                fi
                if [[ "${1:-} ${2:-}" != "attestation verify" ]]; then
                  exit 0
                fi
                if [[ "${3:-}" == "--help" ]]; then
                  [[ "$mode" != "unsupported" ]]
                  exit $?
                fi
                if [[ ( "$mode" == "verified" || "$mode" == "packaged" ) && -f "${5:-}" ]]; then
                  exit 0
                fi
                echo
                echo 'Error: verifying with issuer "sigstore.dev"' >&2
                exit 1
                """);
        return fakeBin;
    }

    private static void writeFakeWindowsGitHubCli(Path fakeBin) throws IOException {
        Files.writeString(
                fakeBin.resolve("gh.cmd"),
                """
                @echo off
                pwsh -NoProfile -File "%~dp0fake-gh.ps1" %*
                exit /b %ERRORLEVEL%
                """);
        Files.writeString(
                fakeBin.resolve("fake-gh.ps1"),
                """
                Add-Content -Path $env:SYMPHONY_FAKE_LOG -Value ("gh GH_HOST=$env:GH_HOST " + ($args -join " "))
                $mode = if ($env:SYMPHONY_FAKE_GH_ATTESTATION) { $env:SYMPHONY_FAKE_GH_ATTESTATION } else { "verified" }
                if ($args.Count -gt 0 -and $args[0] -eq "--version") {
                  if ($mode -eq "outdated") { Write-Output 'gh version 2.49.0 (2024-05-08)' }
                  elseif ($mode -eq "packaged") { Write-Output 'gh version 2.65.0+dfsg1 (2025-01-06)' }
                  else { Write-Output 'gh version 2.65.0 (2025-01-06)' }
                  exit 0
                }
                if ($args.Count -lt 2 -or $args[0] -ne "attestation" -or $args[1] -ne "verify") {
                  exit 0
                }
                if ($args.Count -ge 3 -and $args[2] -eq "--help") {
                  exit $(if ($mode -eq "unsupported") { 1 } else { 0 })
                }
                if (($mode -eq "verified" -or $mode -eq "packaged") -and $args.Count -ge 5 -and (Test-Path -LiteralPath $args[4] -PathType Leaf)) {
                  exit 0
                }
                [Console]::Error.WriteLine('Error: verifying with issuer "sigstore.dev"')
                exit 1
                """);
    }

    static Path createFakeWindowsToolchain(Path temporaryDirectory) throws IOException {
        Path fakeBin = temporaryDirectory.resolve("fake-windows-bin");
        Files.createDirectories(fakeBin);
        Files.writeString(
                fakeBin.resolve("java.cmd"),
                """
                @echo off
                pwsh -NoProfile -File "%SYMPHONY_FAKE_JAVA%" %*
                """);
        Files.writeString(
                fakeBin.resolve("javac.cmd"),
                """
                @echo off
                echo javac 25.0.1
                """);
        Files.writeString(
                fakeBin.resolve("codex.cmd"),
                """
                @echo off
                if "%1"=="login" if "%2"=="status" exit /b 0
                echo codex %* >> "%SYMPHONY_FAKE_LOG%"
                exit /b 0
                """);
        Files.writeString(
                fakeBin.resolve("fake-java.ps1"),
                """
                if ($args.Count -gt 0 -and $args[0] -eq "-version") {
                  [Console]::Error.WriteLine('openjdk version "25.0.1" 2026-04-21')
                  exit 0
                }
                if (($args -join " ") -like "* -jar *") {
                  [ordered]@{
                    event = "jar-start"
                    args = $args
                    dotenv = $env:SYMPHONY_TRELLO_DOTENV
                  } | ConvertTo-Json -Compress -Depth 4 |
                    Add-Content -Path $env:SYMPHONY_FAKE_LOG
                  Write-Output "fake wrapper log"
                  while ($true) { Start-Sleep -Seconds 1 }
                }
                if (($args -join " ") -like "*ch.fmartin.symphony.trello.setup.TrelloBoardSetupMain*") {
                  [ordered]@{
                    event = "setup-cli"
                    cwd = (Get-Location).Path
                    args = $args
                    dotenv = $env:SYMPHONY_TRELLO_DOTENV
                  } | ConvertTo-Json -Compress -Depth 4 |
                    Add-Content -Path $env:SYMPHONY_FAKE_LOG
                  if (($args -join " ") -like "*definitely-not-a-command*") {
                    [Console]::Error.WriteLine("setup_failed code=setup_invalid_arguments message=Unmatched argument: 'definitely-not-a-command'")
                    exit 2
                  }
                  if (($args -join " ") -like "*--help*" -or ($args -join " ") -like "* -h*") {
                    if ($args -contains "migrate-workflows") {
                      if ($env:SYMPHONY_FAKE_MIGRATION_HELP_FAILURE) {
                        [Console]::Error.WriteLine("simulated migration help failure")
                        exit 23
                      }
                      if ($env:SYMPHONY_FAKE_MIGRATION_HELP) { Write-Output $env:SYMPHONY_FAKE_MIGRATION_HELP }
                      elseif ($env:SYMPHONY_FAKE_WITHOUT_WORKFLOW_MIGRATION) { Write-Output "Usage: symphony-trello" }
                      else { Write-Output "Usage: symphony-trello migrate-workflows" }
                    } else { Write-Output "Usage: symphony-trello" }
                    exit 0
                  }
                  if ($env:SYMPHONY_FAKE_WITHOUT_WORKFLOW_MIGRATION -and $args -contains "migrate-workflows") {
                    [Console]::Error.WriteLine("setup_failed code=setup_invalid_arguments message=Unmatched argument: 'migrate-workflows'")
                    exit 2
                  }
                  if ($env:SYMPHONY_FAKE_WORKFLOW_MIGRATION_FAILURE -and $args -contains "migrate-workflows") {
                    Write-Output "  REJECTED  simulated workflow migration rejection"
                    exit 2
                  }
                  if (($args -join " ") -like "*--version*") {
                    if ($env:SYMPHONY_FAKE_VERSION_MODE -eq "fail") {
                      [Console]::Error.WriteLine("version failed")
                      exit 23
                    }
                    if ($env:SYMPHONY_FAKE_VERSION_MODE -eq "malformed") {
                      Write-Output "not a symphony version"
                      exit 0
                    }
                    if ($env:SYMPHONY_FAKE_VERSION_MODE -eq "multiline") {
                      Write-Output "diagnostic preface"
                      Write-Output "symphony-trello @FAKE_APP_VERSION@"
                      exit 0
                    }
                    Write-Output "symphony-trello $(if ($env:SYMPHONY_FAKE_VERSION) { $env:SYMPHONY_FAKE_VERSION } else { '@FAKE_APP_VERSION@' })"
                    exit 0
                  }
	                  if (($args -join " ") -like "*TrelloBoardSetupMain start*" -or
	                      ($args -join " ") -like "*TrelloBoardSetupMain status*" -or
	                      ($args -join " ") -like "*TrelloBoardSetupMain stop*" -or
	                      ($args -join " ") -like "*TrelloBoardSetupMain logs*" -or
	                      ($args -join " ") -like "*TrelloBoardSetupMain diagnostics*") {
	                    $mainIndex = [Array]::IndexOf($args, "ch.fmartin.symphony.trello.setup.TrelloBoardSetupMain")
	                    $cliArgs = $args[($mainIndex + 1)..($args.Count - 1)]
	                    $command = $cliArgs[0]
	                    $stateHome = "."
	                    $workflow = "WORKFLOW.md"
	                    for ($i = 1; $i -lt $cliArgs.Count; $i++) {
	                      switch ($cliArgs[$i]) {
	                        "--state-home" { $i++; $stateHome = $cliArgs[$i] }
	                        "--workflow" { $i++; $workflow = $cliArgs[$i] }
	                      }
	                    }
	                    New-Item -ItemType Directory -Force -Path $stateHome | Out-Null
	                    $displayName = Split-Path -Leaf $workflow
	                    $stateName = "$displayName.abcdef123456"
	                    $pidFile = Join-Path $stateHome "$stateName.pid"
	                    $logFile = Join-Path $stateHome "$stateName.log"
	                    if ($command -eq "start") {
	                      Set-Content -Encoding ASCII -Path $pidFile -Value $PID
	                      Set-Content -Encoding UTF8 -Path $logFile -Value "fake wrapper log"
	                      Write-Output "Started Symphony for Trello: $workflow"
	                      exit 0
	                    }
	                    if ($command -eq "status") {
	                      Write-Output "running $displayName pid=$PID"
	                      exit 0
	                    }
	                    if ($command -eq "stop") {
	                      Remove-Item -Force -ErrorAction SilentlyContinue $pidFile
	                      Write-Output "Stopped $displayName"
	                      exit 0
	                    }
	                    if ($command -eq "logs") {
	                      Get-Content -Path $logFile
	                      exit 0
	                    }
	                  }
	                }
                "java $($args -join ' ')" | Add-Content -Path $env:SYMPHONY_FAKE_LOG
                exit 0
                """
                        .replace("@FAKE_APP_VERSION@", FAKE_APP_VERSION));
        return fakeBin;
    }

    static String migrationHelp(boolean supported) throws IOException {
        var output = new StringWriter();
        try (var input = new BufferedReader(new StringReader(""))) {
            var main = new TrelloBoardSetupMain(
                    mock(TrelloBoardSetupService.class), mock(LocalWorkerManager.class), input, System.out, System.err);
            var command = new CommandLine(main).setOut(new PrintWriter(output)).setErr(new PrintWriter(output));
            if (!supported) {
                command.getCommandSpec().removeSubcommand("migrate-workflows");
            }
            assertThat(command.execute("migrate-workflows", "--help")).isZero();
        }
        return output.toString();
    }

    static List<String[]> installedPicocliHelpCommands() {
        return List.of(
                new String[] {"--help"},
                new String[] {"--version"},
                new String[] {"setup-local", "--help"},
                new String[] {"setup-local", "check", "--help"},
                new String[] {"setup-local", "repair-port", "--help"},
                new String[] {"setup-local", "configure-github", "--help"},
                new String[] {"new-board", "--help"},
                new String[] {"import-board", "--help"},
                new String[] {"list-workspaces", "--help"},
                new String[] {"start", "--help"},
                new String[] {"stop", "--help"},
                new String[] {"status", "--help"},
                new String[] {"logs", "--help"},
                new String[] {"diagnostics", "--help"},
                new String[] {"migrate-workflows", "--help"});
    }

    static String[] commandWithPrefix(String executable, String[] arguments) {
        String[] command = new String[arguments.length + 1];
        command[0] = executable;
        System.arraycopy(arguments, 0, command, 1, arguments.length);
        return command;
    }

    static String[] powerShellFileCommand(String script, String[] arguments) {
        String[] command = new String[arguments.length + 4];
        command[0] = "pwsh";
        command[1] = "-NoProfile";
        command[2] = "-File";
        command[3] = script;
        System.arraycopy(arguments, 0, command, 4, arguments.length);
        return command;
    }

    static String[] commandPromptCommand(String executable, String[] arguments) {
        String commandLine = Stream.concat(Stream.of(executable), Stream.of(arguments))
                .map(InstallerScriptFixture::commandPromptLiteral)
                .collect(Collectors.joining(" "));
        return new String[] {"cmd.exe", "/d", "/s", "/c", "\"" + commandLine + "\""};
    }

    private static String commandPromptLiteral(String value) {
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    /// A child process whose standard output and standard error were captured separately.
    record ProcessResult(int exitCode, String stdout, String stderr) implements CapturedStreams {
        /// Standard output followed by standard error, for fragment assertions that do not depend
        /// on stream placement. Use [#stdout()] and [#stderr()] when the stream matters.
        String output() {
            return stdout + stderr;
        }

        void assertSuccess() {
            assertThat(exitCode).as(output()).isZero();
        }
    }

    /// A pseudo-terminal session. The terminal merges standard output, standard error, and the
    /// echo of typed input into one transcript, so this result has no stream split.
    record PseudoTerminalResult(int exitCode, String transcript) {
        private static PseudoTerminalResult from(ProcessResult session) {
            // `script` writes the terminal session to its own stdout; its stderr only carries
            // diagnostics from `script` itself, kept so a harness failure stays visible.
            return new PseudoTerminalResult(session.exitCode(), session.stdout() + session.stderr());
        }

        void assertSuccess() {
            assertThat(exitCode).as(transcript).isZero();
        }
    }

    /// One answer typed into a pseudo-terminal after `prompt` appeared.
    record TerminalAnswer(String prompt, String response, boolean pressEnter) {
        TerminalAnswer(String prompt, String response) {
            this(prompt, response, true);
        }
    }

    /// Whether a child process starts from the test JVM's environment or from an empty one. Use
    /// [#CLEARED] when output must not depend on the developer's or runner's variables, such as
    /// locale, terminal type, color settings, shell, or credentials.
    enum HostEnvironment {
        INHERITED,
        CLEARED;

        void applyTo(ProcessBuilder processBuilder) {
            if (this == CLEARED) {
                processBuilder.environment().clear();
            }
        }
    }

    private record AppliedTestEnvironment(List<Path> cleanupRoots) implements AutoCloseable {
        @Override
        public void close() throws IOException {
            for (Path cleanupRoot : cleanupRoots) {
                deleteRecursively(cleanupRoot);
            }
        }
    }
}
