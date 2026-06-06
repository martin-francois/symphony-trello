#!/usr/bin/env bash
set -euo pipefail

HOME="${HOME:-}"
if [[ -z "$HOME" ]]; then
  echo "HOME must be set to a user home directory before running the uninstaller." >&2
  exit 2
fi
SYMPHONY_HOME="${SYMPHONY_HOME:-$HOME/.local/share/symphony-trello}"
APP_DIR="$SYMPHONY_HOME/app"
CONFIG_DIR="${SYMPHONY_TRELLO_CONFIG_DIR:-$SYMPHONY_HOME/config}"
WORKSPACE_ROOT="${SYMPHONY_TRELLO_WORKSPACE_ROOT:-$SYMPHONY_HOME/workspaces}"
STATE_HOME="${SYMPHONY_TRELLO_STATE_HOME:-$SYMPHONY_HOME/state}"
BIN_DIR="$HOME/.local/bin"
CODEX_NPM_PREFIX="$SYMPHONY_HOME/npm"
DRY_RUN=false
YES=false
YES_LOCAL_DATA=false
REMOVE_CONFIG=false
REMOVE_WORKSPACES=false
REMOVE_STATE=false

usage() {
  cat <<'USAGE'
Usage:
  curl -fsSL https://raw.githubusercontent.com/martin-francois/symphony-trello/main/uninstall.sh | bash
  curl -fsSL https://raw.githubusercontent.com/martin-francois/symphony-trello/main/uninstall.sh | bash -s -- --dry-run

Options:
  --dry-run                 Print planned actions without deleting files.
  --yes                     Do not prompt for installer-managed app files.
  --yes-local-data          Do not prompt for cleanup scopes that delete local user data.
  --remove-config           Also remove local .env, workflows, and connected-board manifest.
  --remove-workspaces       Also remove per-card workspaces.
  --remove-state            Also remove local state and logs.
  --remove-logs             Alias for --remove-state.
  --remove-all-local-data   Remove config, workspaces, state, and logs after explicit confirmation.
  --prefix PATH             App checkout path. Default: $SYMPHONY_HOME/app
  --bin-dir PATH            Command directory. Default: ~/.local/bin
  --help                    Show this help.
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
  --dry-run) DRY_RUN=true ;;
  --yes | -y) YES=true ;;
  --yes-local-data) YES_LOCAL_DATA=true ;;
  --remove-config) REMOVE_CONFIG=true ;;
  --remove-workspaces) REMOVE_WORKSPACES=true ;;
  --remove-state | --remove-logs) REMOVE_STATE=true ;;
  --remove-all-local-data)
    REMOVE_CONFIG=true
    REMOVE_WORKSPACES=true
    REMOVE_STATE=true
    ;;
  --prefix)
    if [[ $# -lt 2 ]]; then
      echo "Missing value for --prefix" >&2
      exit 2
    fi
    APP_DIR="$2"
    shift
    ;;
  --bin-dir)
    if [[ $# -lt 2 ]]; then
      echo "Missing value for --bin-dir" >&2
      exit 2
    fi
    BIN_DIR="$2"
    shift
    ;;
  --help | -h)
    usage
    exit 0
    ;;
  *)
    echo "Unknown option: $1" >&2
    exit 2
    ;;
  esac
  shift
done

absolutize_path() {
  case "$1" in
  /*) printf '%s\n' "$1" ;;
  *) printf '%s/%s\n' "$PWD" "$1" ;;
  esac
}

same_or_inside_path() {
  local child parent
  child="$1"
  parent="$2"
  [[ "$child" == "$parent" || "$child" == "$parent"/* ]]
}

SYMPHONY_HOME="$(absolutize_path "$SYMPHONY_HOME")"
APP_DIR="$(absolutize_path "$APP_DIR")"
CONFIG_DIR="$(absolutize_path "$CONFIG_DIR")"
WORKSPACE_ROOT="$(absolutize_path "$WORKSPACE_ROOT")"
STATE_HOME="$(absolutize_path "$STATE_HOME")"
BIN_DIR="$(absolutize_path "$BIN_DIR")"
CODEX_NPM_PREFIX="$(absolutize_path "$SYMPHONY_HOME/npm")"

confirm() {
  local prompt="$1"
  local assume_yes="${2:-false}"
  if [[ "$DRY_RUN" == true ]]; then
    return 0
  fi
  if [[ "$assume_yes" == true ]]; then
    return 0
  fi
  if [[ ! -r /dev/tty ]]; then
    echo "This step needs an interactive terminal. Rerun from a terminal or pass the matching confirmation flag." >&2
    exit 2
  fi
  if ! read -r -p "$prompt [y/N] " answer </dev/tty; then
    echo "This step needs an interactive terminal. Rerun from a terminal or pass the matching confirmation flag." >&2
    exit 2
  fi
  case "$answer" in
  [yY]*) return 0 ;;
  *) return 1 ;;
  esac
}

safe_removal_path() {
  local raw_path="$1"
  local path="$raw_path"
  while [[ "$path" != "/" && "$path" == */ ]]; do
    path="${path%/}"
  done
  case "$path" in
  /*) ;;
  *) path="$PWD/$path" ;;
  esac
  case "$path" in
  "" | "/" | . | "..")
    echo "Refusing dangerous removal path: $raw_path" >&2
    exit 2
    ;;
  "../"* | */.. | */../*)
    echo "Refusing dangerous removal path: $raw_path" >&2
    exit 2
    ;;
  esac
  local normalized
  if [[ -d "$path" && ! -L "$path" ]]; then
    normalized="$(cd "$path" && pwd -P)"
  else
    local directory basename
    directory="$(dirname "$path")"
    basename="$(basename "$path")"
    if [[ -d "$directory" ]]; then
      normalized="$(cd "$directory" && pwd -P)/$basename"
    else
      normalized="$path"
    fi
  fi
  local home_directory working_directory
  home_directory="$(cd "$HOME" && pwd -P)"
  working_directory="$(pwd -P)"
  case "$normalized" in
  "" | "/" | "$home_directory" | "$working_directory" | "$home_directory/." | "$working_directory/.")
    echo "Refusing dangerous removal path: $raw_path" >&2
    exit 2
    ;;
  esac
  printf '%s\n' "$normalized"
}

remove_path() {
  local path
  path="$(safe_removal_path "$1")"
  if [[ -e "$path" || -L "$path" ]]; then
    if [[ "$DRY_RUN" == true ]]; then
      echo "  WOULD REMOVE  $path"
    else
      echo "  REMOVE  $path"
    fi
    if [[ "$DRY_RUN" == false ]]; then
      rm -rf "$path"
    fi
  fi
}

path_exists() {
  [[ -e "$1" || -L "$1" ]]
}

assert_app_removal_preserves_current_data() {
  local conflicts=()
  local cleanup_paths=()
  if [[ "$REMOVE_CONFIG" == false ]]; then
    if path_exists "$CONFIG_DIR" && same_or_inside_path "$CONFIG_DIR" "$APP_DIR"; then conflicts+=("CONFIG     $CONFIG_DIR"); fi
  elif [[ "$YES_LOCAL_DATA" == false ]] && path_exists "$CONFIG_DIR" && same_or_inside_path "$CONFIG_DIR" "$APP_DIR"; then
    cleanup_paths+=("CONFIG     $CONFIG_DIR")
  fi
  if [[ "$REMOVE_WORKSPACES" == false ]]; then
    if path_exists "$WORKSPACE_ROOT" && same_or_inside_path "$WORKSPACE_ROOT" "$APP_DIR"; then conflicts+=("WORKSPACES $WORKSPACE_ROOT"); fi
  elif [[ "$YES_LOCAL_DATA" == false ]] && path_exists "$WORKSPACE_ROOT" && same_or_inside_path "$WORKSPACE_ROOT" "$APP_DIR"; then
    cleanup_paths+=("WORKSPACES $WORKSPACE_ROOT")
  fi
  if [[ "$REMOVE_STATE" == false ]]; then
    if path_exists "$STATE_HOME" && same_or_inside_path "$STATE_HOME" "$APP_DIR"; then conflicts+=("STATE/LOGS $STATE_HOME"); fi
  elif [[ "$YES_LOCAL_DATA" == false ]] && path_exists "$STATE_HOME" && same_or_inside_path "$STATE_HOME" "$APP_DIR"; then
    cleanup_paths+=("STATE/LOGS $STATE_HOME")
  fi
  if ((${#conflicts[@]} > 0)); then
    echo "Refusing to remove app directory because it contains current local data that is preserved by default:" >&2
    printf '  %s\n' "${conflicts[@]}" >&2
    echo "Use a dedicated app checkout path, or pass the matching cleanup scope with --yes-local-data." >&2
    exit 2
  fi
  if ((${#cleanup_paths[@]} > 0)); then
    echo "The app directory contains local data selected for cleanup:"
    printf '  %s\n' "${cleanup_paths[@]}"
    if ! confirm "Remove those local data paths with the app directory?" "$YES_LOCAL_DATA"; then
      echo "Skipped cleanup-scoped local data inside the app directory."
      exit 2
    fi
  fi
}

is_managed_pid() {
  local pid="$1"
  local marker="-Dsymphony.trello.managed.app_home=$APP_DIR"
  local jar="$APP_DIR/target/quarkus-app/quarkus-run.jar"
  local has_marker=false
  local has_jar=false
  local arg
  if [[ -r "/proc/$pid/cmdline" ]]; then
    while IFS= read -r -d '' arg; do
      [[ "$arg" == "$marker" ]] && has_marker=true
      [[ "$arg" == "$jar" ]] && has_jar=true
    done <"/proc/$pid/cmdline"
    [[ "$has_marker" == true && "$has_jar" == true ]]
    return
  fi
  local command_line
  command_line="$(ps -p "$pid" -o command= 2>/dev/null || true)"
  command_line_has_arg "$command_line" "$marker" && command_line_has_arg "$command_line" "$jar"
}

command_line_has_arg() {
  local command_line="$1"
  local expected="$2"
  [[ " $command_line " == *" $expected "* ||
    " $command_line " == *" \"$expected\" "* ||
    " $command_line " == *" '$expected' "* ]]
}

wait_for_exit() {
  local pid="$1"
  local deadline=$((SECONDS + 15))
  while kill -0 "$pid" >/dev/null 2>&1 && [[ "$SECONDS" -lt "$deadline" ]]; do
    sleep 0.2
  done
  if kill -0 "$pid" >/dev/null 2>&1; then
    echo "  KILL  pid=$pid did not stop after SIGTERM"
    kill -KILL "$pid" >/dev/null 2>&1 || true
    deadline=$((SECONDS + 5))
    while kill -0 "$pid" >/dev/null 2>&1 && [[ "$SECONDS" -lt "$deadline" ]]; do
      sleep 0.2
    done
    if kill -0 "$pid" >/dev/null 2>&1; then
      echo "Managed process did not stop: pid=$pid" >&2
      exit 2
    fi
  fi
}

stop_managed_processes() {
  for pid_file in "$STATE_HOME"/*.pid; do
    [[ -e "$pid_file" ]] || continue
    local pid
    pid="$(cat "$pid_file")"
    if kill -0 "$pid" >/dev/null 2>&1 && is_managed_pid "$pid"; then
      echo "  STOP  $(basename "$pid_file" .pid) pid=$pid"
      if [[ "$DRY_RUN" == false ]]; then
        kill "$pid" >/dev/null 2>&1 || true
        wait_for_exit "$pid"
        rm -f "$pid_file"
      fi
    elif kill -0 "$pid" >/dev/null 2>&1; then
      echo "  SKIP  stale pid does not belong to this install: $(basename "$pid_file" .pid) pid=$pid"
    elif [[ "$DRY_RUN" == false ]]; then
      rm -f "$pid_file"
    fi
  done
}

remove_managed_codex_artifacts() {
  local codex_command="$BIN_DIR/codex"
  if [[ -L "$codex_command" ]]; then
    local target
    target="$(readlink "$codex_command" || true)"
    case "$target" in
    "$CODEX_NPM_PREFIX"/bin/codex) remove_path "$codex_command" ;;
    esac
  fi
  remove_path "$CODEX_NPM_PREFIX"
}

print_managed_codex_removal_plan() {
  local codex_command="$BIN_DIR/codex"
  local printed=false
  if [[ -L "$codex_command" ]]; then
    local target
    target="$(readlink "$codex_command" || true)"
    if [[ "$target" == "$CODEX_NPM_PREFIX/bin/codex" ]]; then
      echo "  CODEX CLI       $codex_command"
      printed=true
    fi
  fi
  if [[ -e "$CODEX_NPM_PREFIX" || -L "$CODEX_NPM_PREFIX" ]]; then
    if [[ "$printed" == true ]]; then
      echo "                  $CODEX_NPM_PREFIX"
    else
      echo "  CODEX CLI       $CODEX_NPM_PREFIX"
    fi
  fi
}

echo "Symphony for Trello uninstall"
echo
echo "App checkout: $APP_DIR"
echo "Installed CLI: $BIN_DIR/symphony-trello"
echo
if [[ "$DRY_RUN" == true ]]; then
  echo "Dry run: no files changed."
  echo
fi
echo "Will remove if present:"
echo "  APP FILES       $APP_DIR"
echo "  CLI EXECUTABLE  $BIN_DIR/symphony-trello"
print_managed_codex_removal_plan
echo
echo "Will preserve by default:"
echo "  CONFIG          $CONFIG_DIR"
echo "  WORKSPACES      $WORKSPACE_ROOT"
echo "  STATE/LOGS      $STATE_HOME"
echo "  AUTH            Codex login/auth files and GitHub auth"
echo "  TRELLO          Trello boards are not deleted or archived"
echo

if confirm "Remove installer-managed app files and CLI executable?" "$YES"; then
  if [[ -e "$APP_DIR" && ! -f "$APP_DIR/.symphony-trello-install" ]]; then
    echo "Refusing to remove app directory without Symphony installer marker: $APP_DIR" >&2
    exit 2
  fi
  assert_app_removal_preserves_current_data
  stop_managed_processes
  remove_path "$BIN_DIR/symphony-trello"
  remove_managed_codex_artifacts
  remove_path "$APP_DIR"
else
  echo "Skipped installer-managed files."
fi

if [[ "$REMOVE_CONFIG" == true && (-e "$CONFIG_DIR" || -L "$CONFIG_DIR") ]]; then
  echo
  if confirm "Remove local config, .env files, workflows, and connected-board manifest?" "$YES_LOCAL_DATA"; then
    remove_path "$CONFIG_DIR"
  else
    echo "Kept local config: $CONFIG_DIR"
  fi
fi

if [[ "$REMOVE_WORKSPACES" == true && (-e "$WORKSPACE_ROOT" || -L "$WORKSPACE_ROOT") ]]; then
  echo
  if confirm "Remove per-card workspaces?" "$YES_LOCAL_DATA"; then
    remove_path "$WORKSPACE_ROOT"
  else
    echo "Kept workspaces: $WORKSPACE_ROOT"
  fi
fi

if [[ "$REMOVE_STATE" == true && (-e "$STATE_HOME" || -L "$STATE_HOME") ]]; then
  echo
  if confirm "Remove local state and logs?" "$YES_LOCAL_DATA"; then
    remove_path "$STATE_HOME"
  else
    echo "Kept local state and logs: $STATE_HOME"
  fi
fi

echo
echo "Trello boards were not deleted or archived."
