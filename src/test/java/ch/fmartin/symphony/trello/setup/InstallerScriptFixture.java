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
    private static final Pattern POSIX_INSTALLER_DEFAULT_VERSION =
            Pattern.compile("(?m)^DEFAULT_VERSION=\"([^\"]+)\" # x-release-please-version$");

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
        applyTestEnvironment(processBuilder, environment);
        return run(processBuilder, input, 60);
    }

    static ProcessResult run(Map<String, String> environment, String... command)
            throws IOException, InterruptedException {
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        applyTestEnvironment(processBuilder, environment);
        return run(processBuilder, "", 60);
    }

    static ProcessResult run(Map<String, String> environment, Path workingDirectory, String... command)
            throws IOException, InterruptedException {
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.directory(workingDirectory.toFile());
        applyTestEnvironment(processBuilder, environment);
        return run(processBuilder, "", 60);
    }

    private static void applyTestEnvironment(ProcessBuilder processBuilder, Map<String, String> environment)
            throws IOException {
        Map<String, String> processEnvironment = processBuilder.environment();
        for (String xdgHome : List.of("XDG_DATA_HOME", "XDG_CONFIG_HOME", "XDG_STATE_HOME", "XDG_CACHE_HOME")) {
            if (!environment.containsKey(xdgHome)) {
                processEnvironment.remove(xdgHome);
            }
        }
        processEnvironment.putAll(environment);
        applyDeterministicLinuxDistro(processEnvironment, environment);
        if (!environment.containsKey("HOME") && !isWindows()) {
            Path home = defaultPosixHome(environment);
            Files.createDirectories(home);
            processEnvironment.put("HOME", home.toString());
        }
    }

    private static void applyDeterministicLinuxDistro(
            Map<String, String> processEnvironment, Map<String, String> environment) {
        if (!"Linux".equals(environment.get("SYMPHONY_TRELLO_TEST_OS"))) {
            return;
        }
        processEnvironment.putIfAbsent("SYMPHONY_TRELLO_TEST_OS_ID", "debian");
        processEnvironment.putIfAbsent("SYMPHONY_TRELLO_TEST_OS_ID_LIKE", "debian");
        processEnvironment.putIfAbsent("SYMPHONY_TRELLO_TEST_OS_PRETTY_NAME", "Debian GNU/Linux");
    }

    private static Path defaultPosixHome(Map<String, String> environment) {
        String symphonyHome = environment.get("SYMPHONY_HOME");
        if (symphonyHome != null && !symphonyHome.isBlank()) {
            Path symphonyHomePath = Path.of(symphonyHome);
            if (symphonyHomePath.isAbsolute()) {
                return symphonyHomePath.resolveSibling("user-home");
            }
        }
        return Path.of("target", "installer-script-test-home").toAbsolutePath();
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
            pollDelayForBoundedProcessWait();
        }
        return !processIsRunning(pid);
    }

    private static void pollDelayForBoundedProcessWait() throws InterruptedException {
        Thread.sleep(100);
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
        String installScript = Files.readString(Path.of("install.sh"), StandardCharsets.UTF_8);
        return POSIX_INSTALLER_DEFAULT_VERSION
                .matcher(installScript)
                .results()
                .findAny()
                .map(match -> "v" + match.group(1))
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
        initializeSourceRepository(repository);
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
                elif [[ "$*" == "--user disable --now symphony-trello.service" && -n "$command_path" ]]; then
                  "$command_path" stop
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
                    echo "Usage: symphony-trello"
                    exit 0
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
                        echo "symphony-trello test"
                        exit 0
                        ;;
                      *)
                        echo "symphony-trello test"
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
	                        kill "$worker_pid" >/dev/null 2>&1 || true
	                        for _ in {1..50}; do
	                          kill -0 "$worker_pid" >/dev/null 2>&1 || break
	                          sleep 0.1
	                        done
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
                  read -r key
                  read -r token
                  read -r board
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
                    Write-Output "Usage: symphony-trello"
                    exit 0
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
                      Write-Output "symphony-trello test"
                      exit 0
                    }
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

    static String[] commandPromptCommand(String executable, String[] arguments) {
        String commandLine = Stream.concat(Stream.of(executable), Stream.of(arguments))
                .map(InstallerScriptFixture::commandPromptLiteral)
                .collect(java.util.stream.Collectors.joining(" "));
        return new String[] {"cmd.exe", "/d", "/s", "/c", "\"" + commandLine + "\""};
    }

    private static String commandPromptLiteral(String value) {
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    record ProcessResult(int exitCode, String output) {
        void assertSuccess() {
            assertThat(exitCode).as(output).isZero();
        }
    }
}
