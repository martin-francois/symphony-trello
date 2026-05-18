package ch.fmartin.symphony.trello.setup;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;

final class InstallerScriptFixture {
    private static final Pattern POSIX_INSTALLER_DEFAULT_REF =
            Pattern.compile("(?m)^DEFAULT_REF=\"([^\"]+)\" # x-release-please-version$");

    private InstallerScriptFixture() {}

    static String output(Process process) throws IOException {
        return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8)
                + new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    static void writeExecutable(Path path, String content) throws IOException {
        Files.writeString(path, content, StandardCharsets.UTF_8);
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

    static ProcessResult runWithPseudoTerminal(Map<String, String> environment, String input, String command)
            throws IOException, InterruptedException {
        ProcessBuilder processBuilder = new ProcessBuilder("script", "-q", "-e", "-c", command, "/dev/null");
        processBuilder.environment().putAll(environment);
        return run(processBuilder, input, 60);
    }

    static ProcessResult run(Map<String, String> environment, String... command)
            throws IOException, InterruptedException {
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.environment().putAll(environment);
        return run(processBuilder, "", 60);
    }

    static ProcessResult run(Map<String, String> environment, Path workingDirectory, String... command)
            throws IOException, InterruptedException {
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.directory(workingDirectory.toFile());
        processBuilder.environment().putAll(environment);
        return run(processBuilder, "", 60);
    }

    static ProcessResult run(ProcessBuilder processBuilder, String input, int timeoutSeconds)
            throws IOException, InterruptedException {
        Process process = processBuilder.start();
        process.getOutputStream().write(input.getBytes(StandardCharsets.UTF_8));
        process.getOutputStream().close();
        boolean completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        String output = output(process);
        assertThat(completed)
                .as("process timed out: %s output:%n%s", processBuilder.command(), output)
                .isTrue();
        return new ProcessResult(process.exitValue(), output);
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
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        while (System.nanoTime() < deadline) {
            if (!processIsRunning(pid)) {
                return true;
            }
            Thread.sleep(100);
        }
        return !processIsRunning(pid);
    }

    static String shellQuote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    static boolean commandExists(String name) {
        try {
            Process process = new ProcessBuilder(name, "--version").start();
            return process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0;
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
            boolean completed = process.waitFor(10, TimeUnit.SECONDS);
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
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    static List<String> powershellCommand() {
        String configured = System.getenv("SYMPHONY_TRELLO_TEST_PWSH");
        if (configured != null && !configured.isBlank()) {
            Path command = Path.of(configured);
            if (Files.isExecutable(command)) {
                return List.of(command.toString());
            }
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
        if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
            return Map.of();
        }
        return Map.of("SYMPHONY_TRELLO_ALLOW_NON_WINDOWS_PWSH_FOR_TEST", "1");
    }

    static String powerShellLiteral(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    static String installerDefaultRef() throws IOException {
        String installScript = Files.readString(Path.of("install.sh"), StandardCharsets.UTF_8);
        return POSIX_INSTALLER_DEFAULT_REF
                .matcher(installScript)
                .results()
                .findAny()
                .map(match -> match.group(1))
                .orElseThrow(() -> new IllegalStateException("install.sh is missing the Release Please default ref"));
    }

    static Path createSourceRepository(Path temporaryDirectory) throws Exception {
        Path repository = temporaryDirectory.resolve("source");
        Files.createDirectories(repository);
        Files.writeString(repository.resolve("pom.xml"), "<project />\n", StandardCharsets.UTF_8);
        Files.writeString(repository.resolve("WORKFLOW.example.md"), "# Example workflow\n", StandardCharsets.UTF_8);
        Files.writeString(
                repository.resolve("mvnw"),
                """
                #!/usr/bin/env bash
                set -euo pipefail
                echo "mvnw $*" >> "${SYMPHONY_FAKE_LOG:?}"
                app_home="$(cd "$(dirname "$0")" && pwd -P)"
                mkdir -p "$app_home/target/quarkus-app/app" "$app_home/target/quarkus-app/lib/main" "$app_home/target/quarkus-app/quarkus"
                : > "$app_home/target/quarkus-app/quarkus-run.jar"
                """,
                StandardCharsets.UTF_8);
        repository.resolve("mvnw").toFile().setExecutable(true);
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
        return repository;
    }

    static Path createWindowsSourceRepository(Path temporaryDirectory) throws Exception {
        Path repository = temporaryDirectory.resolve("windows source");
        Files.createDirectories(repository);
        Files.writeString(repository.resolve("pom.xml"), "<project />\n", StandardCharsets.UTF_8);
        Files.writeString(repository.resolve("WORKFLOW.example.md"), "# Example workflow\n", StandardCharsets.UTF_8);
        Files.writeString(
                repository.resolve("mvnw.cmd"),
                """
                @echo off
                echo mvnw.cmd %* >> "%SYMPHONY_FAKE_LOG%"
                mkdir target\\quarkus-app\\app 2>nul
                mkdir target\\quarkus-app\\lib\\main 2>nul
                mkdir target\\quarkus-app\\quarkus 2>nul
                type nul > target\\quarkus-app\\quarkus-run.jar
                """,
                StandardCharsets.UTF_8);
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
        return repository;
    }

    static void addSourceRepositoryCommit(Path repository, String relativePath, String content) throws Exception {
        Files.writeString(repository.resolve(relativePath), content, StandardCharsets.UTF_8);
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
                fakeBin.resolve("java"),
                """
                #!/usr/bin/env bash
                set -euo pipefail
                if [[ "${1:-}" == "-version" ]]; then
                  echo 'openjdk version "25.0.1" 2026-04-21' >&2
                  exit 0
                fi
                if [[ "$*" == *"ch.fmartin.symphony.trello.setup.TrelloBoardSetupMain"* ]]; then
                  echo "setup-cli cwd=$PWD $* dotenv=${SYMPHONY_TRELLO_DOTENV:-}" >> "${SYMPHONY_FAKE_LOG:?}"
                  if [[ "$*" == *"definitely-not-a-command"* ]]; then
                    echo "setup_failed code=setup_invalid_arguments message=Unmatched argument at index 0: 'definitely-not-a-command'" >&2
                    exit 2
                  fi
                  if [[ "$*" == *"--help"* || "$*" == *" -h"* ]]; then
                    echo "Usage: symphony-trello"
                    exit 0
                  fi
	                  if [[ "$*" == *"--version"* ]]; then
	                    echo "symphony-trello test"
	                    exit 0
	                  fi
	                fi
	                if [[ "$*" == *"ch.fmartin.symphony.trello.setup.TrelloBoardSetupMain start"* || "$*" == *"ch.fmartin.symphony.trello.setup.TrelloBoardSetupMain status"* || "$*" == *"ch.fmartin.symphony.trello.setup.TrelloBoardSetupMain stop"* || "$*" == *"ch.fmartin.symphony.trello.setup.TrelloBoardSetupMain logs"* || "$*" == *"ch.fmartin.symphony.trello.setup.TrelloBoardSetupMain diagnostics"* ]]; then
	                  cli_args=()
	                  found_main=false
	                  for value in "$@"; do
	                    if [[ "$found_main" == true ]]; then
	                      cli_args+=("$value")
	                    elif [[ "$value" == "ch.fmartin.symphony.trello.setup.TrelloBoardSetupMain" ]]; then
	                      found_main=true
	                    fi
	                  done
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
	                  state_name="$(basename "${workflow:-WORKFLOW.md}").fake"
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
                        echo "jar-start cwd=$PWD args=-jar quarkus-run.jar $workflow dotenv=$env_file" >> "${SYMPHONY_FAKE_LOG:?}"
                        trap '"'"'if [[ -n "${SYMPHONY_FAKE_SLOW_TERM:-}" ]]; then sleep 2; fi; if [[ -d "$app_home" ]]; then echo "app-present-before-exit" >> "${SYMPHONY_FAKE_LOG:?}"; else echo "app-missing-before-exit" >> "${SYMPHONY_FAKE_LOG:?}"; fi; echo "jar-stopped" >> "${SYMPHONY_FAKE_LOG:?}"; exit 0'"'"' TERM
                        while true; do sleep 1; done
                      ' symphony-worker "$app_home" "$workflow" "$env_file" "-Dsymphony.trello.managed.app_home=$app_home" "$app_home/target/quarkus-app/quarkus-run.jar" >"$log_file" 2>"$log_file.err" &
	                      echo "$!" > "$pid_file"
	                      echo "Started Symphony for Trello: $workflow"
	                      echo "Log: $log_file"
	                      exit 0
	                      ;;
	                    status)
		                      if [[ -f "$pid_file" ]]; then
		                        echo "running $state_name pid=$(cat "$pid_file")"
	                      elif [[ -n "$workflow" ]]; then
	                        echo "stopped $state_name"
	                      else
	                        echo "No managed Symphony process found"
	                      fi
	                      exit 0
	                      ;;
	                    stop)
	                      if [[ -f "$pid_file" ]]; then
	                        kill "$(cat "$pid_file")" >/dev/null 2>&1 || true
	                        rm -f "$pid_file"
	                      fi
	                      echo "Stopped $state_name"
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
	                if [[ "$*" == *"ch.fmartin.symphony.trello.setup.TrelloBoardSetupMain setup-local"* ]]; then
                  config_dir="."
                  while [[ $# -gt 0 ]]; do
                    if [[ "${1:-}" == "--config-dir" ]]; then
                      config_dir="$2"
                      shift 2
                    else
                      shift
                    fi
                  done
                  mkdir -p "$config_dir"
                  read -r key
                  read -r token
                  read -r board
                  slug="$(printf '%s' "$board" | tr '[:upper:]' '[:lower:]' | sed 's/[^a-z0-9][^a-z0-9]*/-/g; s/^-//; s/-$//')"
                  workflow="$config_dir/WORKFLOW.${slug:-trello-board}.md"
                  printf 'TRELLO_API_KEY=%s\\nTRELLO_API_TOKEN=%s\\n' "$key" "$token" > "$config_dir/.env"
                  printf -- '---\\ntracker:\\n  kind: trello\\n---\\n# %s\\n' "$board" > "$workflow"
                  printf '{"boards":[{"boardId":"board-1","boardName":"%s","workflowPath":"%s","envPath":"%s/.env"}]}\\n' "$board" "$workflow" "$config_dir" > "$config_dir/connected-boards.json"
                  echo "setup-local key=$key token=$token board=$board" >> "${SYMPHONY_FAKE_LOG:?}"
	                  "${SYMPHONY_TRELLO_COMMAND:?}" start --env "$config_dir/.env" --workflow "$workflow"
                  exit 0
                fi
                if [[ "$*" == *"-jar "* ]]; then
                  app_home="$PWD"
                  echo "jar-start cwd=$PWD args=$* dotenv=${SYMPHONY_TRELLO_DOTENV:-}" >> "${SYMPHONY_FAKE_LOG:?}"
                  trap 'if [[ -n "${SYMPHONY_FAKE_SLOW_TERM:-}" ]]; then sleep 2; fi; if [[ -d "$app_home" ]]; then echo "app-present-before-exit" >> "${SYMPHONY_FAKE_LOG:?}"; else echo "app-missing-before-exit" >> "${SYMPHONY_FAKE_LOG:?}"; fi; echo "jar-stopped" >> "${SYMPHONY_FAKE_LOG:?}"; exit 0' TERM
                  while true; do sleep 1; done
                fi
                echo "java $*" >> "${SYMPHONY_FAKE_LOG:?}"
                exit 0
                """);
        writeExecutable(
                fakeBin.resolve("javac"),
                """
                #!/usr/bin/env bash
                set -euo pipefail
                echo 'javac 25.0.1'
                """);
        return fakeBin;
    }

    static Path createFakeWindowsToolchain(Path temporaryDirectory) throws IOException {
        Path fakeBin = temporaryDirectory.resolve("fake-windows-bin");
        Files.createDirectories(fakeBin);
        Files.writeString(
                fakeBin.resolve("java.cmd"),
                """
                @echo off
                pwsh -NoProfile -File "%SYMPHONY_FAKE_JAVA%" %*
                """,
                StandardCharsets.UTF_8);
        Files.writeString(
                fakeBin.resolve("javac.cmd"),
                """
                @echo off
                echo javac 25.0.1
                """,
                StandardCharsets.UTF_8);
        Files.writeString(
                fakeBin.resolve("codex.cmd"),
                """
                @echo off
                if "%1"=="login" if "%2"=="status" exit /b 0
                echo codex %* >> "%SYMPHONY_FAKE_LOG%"
                exit /b 0
                """,
                StandardCharsets.UTF_8);
        Files.writeString(
                fakeBin.resolve("fake-java.ps1"),
                """
                if ($args.Count -gt 0 -and $args[0] -eq "-version") {
                  [Console]::Error.WriteLine('openjdk version "25.0.1" 2026-04-21')
                  exit 0
                }
                if (($args -join " ") -like "* -jar *") {
                  "jar-start args=$($args -join ' ') dotenv=$env:SYMPHONY_TRELLO_DOTENV" |
                    Add-Content -Path $env:SYMPHONY_FAKE_LOG
                  Write-Output "fake wrapper log"
                  while ($true) { Start-Sleep -Seconds 1 }
                }
                if (($args -join " ") -like "*ch.fmartin.symphony.trello.setup.TrelloBoardSetupMain*") {
                  "setup-cli cwd=$((Get-Location).Path) $($args -join ' ') dotenv=$env:SYMPHONY_TRELLO_DOTENV" |
                    Add-Content -Path $env:SYMPHONY_FAKE_LOG
                  if (($args -join " ") -like "*definitely-not-a-command*") {
                    [Console]::Error.WriteLine("setup_failed code=setup_invalid_arguments message=Unmatched argument at index 0: 'definitely-not-a-command'")
                    exit 2
                  }
                  if (($args -join " ") -like "*--help*" -or ($args -join " ") -like "* -h*") {
                    Write-Output "Usage: symphony-trello"
                    exit 0
                  }
	                  if (($args -join " ") -like "*--version*") {
	                    Write-Output "symphony-trello test"
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
	                    $stateName = "$(Split-Path -Leaf $workflow).fake"
	                    $pidFile = Join-Path $stateHome "$stateName.pid"
	                    $logFile = Join-Path $stateHome "$stateName.log"
	                    if ($command -eq "start") {
	                      Set-Content -Encoding ASCII -Path $pidFile -Value $PID
	                      Set-Content -Encoding UTF8 -Path $logFile -Value "fake wrapper log"
	                      Write-Output "Started Symphony for Trello: $workflow"
	                      exit 0
	                    }
	                    if ($command -eq "status") {
	                      Write-Output "running $stateName pid=$PID"
	                      exit 0
	                    }
	                    if ($command -eq "stop") {
	                      Remove-Item -Force -ErrorAction SilentlyContinue $pidFile
	                      Write-Output "Stopped $stateName"
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
                """,
                StandardCharsets.UTF_8);
        return fakeBin;
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
                new String[] {"diagnostics", "--help"});
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

    record ProcessResult(int exitCode, String output) {
        void assertSuccess() {
            assertThat(exitCode).as(output).isZero();
        }
    }
}
