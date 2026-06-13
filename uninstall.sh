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
PREFIX_CONFIGURED=false
CODEX_NPM_PREFIX="$SYMPHONY_HOME/npm"
DRY_RUN=false
YES=false
YES_LOCAL_DATA=false
REMOVE_CONFIG=false
REMOVE_WORKSPACES=false
REMOVE_STATE=false
PATH_BLOCK_START="# >>> Symphony for Trello PATH >>>"
PATH_BLOCK_END="# <<< Symphony for Trello PATH <<<"

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
    PREFIX_CONFIGURED=true
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
  if [[ "$parent" == "/" ]]; then
    [[ "$child" == /* ]]
    return
  fi
  [[ "$child" == "$parent" || "$child" == "$parent"/* ]]
}

path_parent() {
  local path="$1"
  path="${path%/}"
  if [[ "$path" != */* ]]; then
    printf '.\n'
  elif [[ "$path" == /* && "${path%/*}" == "" ]]; then
    printf '/\n'
  else
    printf '%s\n' "${path%/*}"
  fi
}

script_checkout_dir() {
  local source="${BASH_SOURCE[0]:-}"
  local script_dir script_path
  case "$source" in
  "" | bash | -*) return 1 ;;
  esac
  script_path="$(absolutize_path "$source")"
  script_dir="$(path_parent "$script_path")"
  script_dir="$(normalize_path "$script_dir")"
  if [[ -d "$script_dir/.git" || -f "$script_dir/pom.xml" ]]; then
    printf '%s\n' "$script_dir"
  fi
}

path_has_symlink_component() {
  local path="$1"
  local current="/"
  local part
  local -a parts=()
  path="$(absolutize_path "$path")"
  path="${path#/}"
  IFS=/ read -r -a parts <<<"$path"
  for part in "${parts[@]}"; do
    case "$part" in
    "" | .) continue ;;
    ..)
      current="${current%/*}"
      [[ -n "$current" ]] || current="/"
      continue
      ;;
    esac
    current="${current%/}/$part"
    if [[ -L "$current" ]]; then
      return 0
    fi
    if [[ ! -e "$current" ]]; then
      return 1
    fi
  done
  return 1
}

normalize_path() {
  local path="$1"
  local -a parts=()
  local -a normalized=()
  local part
  path="$(absolutize_path "$path")"
  path="${path#/}"
  IFS=/ read -r -a parts <<<"$path"
  for part in "${parts[@]}"; do
    case "$part" in
    "" | .) ;;
    ..)
      if ((${#normalized[@]} > 0)); then
        unset "normalized[$((${#normalized[@]} - 1))]"
      fi
      ;;
    *) normalized+=("$part") ;;
    esac
  done
  if ((${#normalized[@]} == 0)); then
    printf '/\n'
  else
    local IFS=/
    printf '/%s\n' "${normalized[*]}"
  fi
}

is_blank() {
  local value="$1"
  value="${value// /}"
  value="${value//$'\t'/}"
  value="${value//$'\n'/}"
  value="${value//$'\r'/}"
  value="${value//$'\v'/}"
  value="${value//$'\f'/}"
  [[ -z "$value" ]]
}

has_control() {
  local value="$1"
  [[ "$value" != "${value//[$'\001'-$'\037'$'\177']/}" ]]
}

validate_bin_dir_option_value() {
  if is_blank "$BIN_DIR"; then
    echo "--bin-dir must not be blank." >&2
    exit 2
  fi
  if has_control "$BIN_DIR"; then
    echo "--bin-dir must not contain control characters." >&2
    exit 2
  fi
  case "$BIN_DIR" in
  /*) ;;
  *)
    echo "--bin-dir must be an absolute path." >&2
    exit 2
    ;;
  esac
}

validate_app_path_option_values() {
  if is_blank "$APP_DIR"; then
    echo "--prefix must not be blank." >&2
    exit 2
  fi
  if has_control "$SYMPHONY_HOME" || has_control "$APP_DIR" || has_control "$CONFIG_DIR" || has_control "$WORKSPACE_ROOT" || has_control "$STATE_HOME"; then
    echo "Installer app, config, workspace, and state paths must not contain control characters." >&2
    exit 2
  fi
  if [[ "$PREFIX_CONFIGURED" == true ]]; then
    case "$APP_DIR" in
    /*) ;;
    *)
      echo "--prefix must be an absolute path." >&2
      exit 2
      ;;
    esac
  fi
}

is_broad_system_path() {
  case "$1" in
  / | /etc | /home | /opt | /root | /tmp | /usr | /var) return 0 ;;
  *) return 1 ;;
  esac
}

validate_app_paths() {
  local raw_app_dir app_dir home_dir symphony_home checkout_dir root
  raw_app_dir="$APP_DIR"
  app_dir="$(normalize_path "$APP_DIR")"
  home_dir="$(normalize_path "$HOME")"
  symphony_home="$(normalize_path "$SYMPHONY_HOME")"
  checkout_dir="$(script_checkout_dir || true)"
  if is_broad_system_path "$app_dir" ||
    is_broad_system_path "$symphony_home" ||
    [[ "$app_dir" == "$home_dir" || "$symphony_home" == "$home_dir" ||
      (-n "$checkout_dir" && "$app_dir" == "$checkout_dir") ]]; then
    echo "--prefix must point to a dedicated app checkout directory." >&2
    exit 2
  fi
  if path_has_symlink_component "$raw_app_dir" || path_has_symlink_component "$app_dir"; then
    echo "--prefix must not be a symlink." >&2
    exit 2
  fi
  if [[ -e "$app_dir" && ! -d "$app_dir" ]]; then
    echo "--prefix must be a directory." >&2
    exit 2
  fi
  for root in "$CONFIG_DIR" "$WORKSPACE_ROOT" "$STATE_HOME"; do
    root="$(normalize_path "$root")"
    if [[ ! -e "$root" && ! -L "$root" ]] && (same_or_inside_path "$app_dir" "$root" || same_or_inside_path "$root" "$app_dir"); then
      echo "--prefix must not overlap Symphony config, workspace, or state directories." >&2
      exit 2
    fi
  done
}

validate_command_directory() {
  local bin_dir home_dir checkout_dir root
  bin_dir="$(normalize_path "$BIN_DIR")"
  home_dir="$(normalize_path "$HOME")"
  checkout_dir="$(script_checkout_dir || true)"
  if [[ "$bin_dir" == "/" || "$bin_dir" == "$home_dir" || (-n "$checkout_dir" && "$bin_dir" == "$checkout_dir") ]]; then
    echo "--bin-dir must point to a dedicated command directory." >&2
    exit 2
  fi
  if [[ -e "$bin_dir" && ! -d "$bin_dir" ]]; then
    echo "--bin-dir must be a directory." >&2
    exit 2
  fi
  for root in "$APP_DIR" "$CONFIG_DIR" "$WORKSPACE_ROOT" "$STATE_HOME"; do
    root="$(normalize_path "$root")"
    if same_or_inside_path "$bin_dir" "$root" || same_or_inside_path "$root" "$bin_dir"; then
      echo "--bin-dir must not overlap Symphony app, config, workspace, or state directories." >&2
      exit 2
    fi
  done
}

shell_literal() {
  local value="$1"
  value="${value//\'/\'\\\'\'}"
  printf "'%s'" "$value"
}

path_setup_line() {
  local current_path
  current_path="\"\$PATH\""
  printf 'export PATH=%s:%s\n' "$(shell_literal "$BIN_DIR")" "$current_path"
}

profile_candidates() {
  printf '%s/.bashrc\n' "$HOME"
  printf '%s/.bash_profile\n' "$HOME"
  printf '%s/.bash_login\n' "$HOME"
  printf '%s/.profile\n' "$HOME"
  printf '%s/.zshrc\n' "$HOME"
}

validate_bin_dir_option_value
validate_app_path_option_values
SYMPHONY_HOME="$(absolutize_path "$SYMPHONY_HOME")"
APP_DIR="$(absolutize_path "$APP_DIR")"
CONFIG_DIR="$(absolutize_path "$CONFIG_DIR")"
WORKSPACE_ROOT="$(absolutize_path "$WORKSPACE_ROOT")"
STATE_HOME="$(absolutize_path "$STATE_HOME")"
BIN_DIR="$(absolutize_path "$BIN_DIR")"
CODEX_NPM_PREFIX="$(absolutize_path "$SYMPHONY_HOME/npm")"
validate_app_paths

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

validate_requested_removal_paths() {
  if [[ "$REMOVE_CONFIG" == true ]]; then
    safe_removal_path "$CONFIG_DIR" >/dev/null
  fi
  if [[ "$REMOVE_WORKSPACES" == true ]]; then
    safe_removal_path "$WORKSPACE_ROOT" >/dev/null
  fi
  if [[ "$REMOVE_STATE" == true ]]; then
    safe_removal_path "$STATE_HOME" >/dev/null
  fi
}

validate_requested_removal_paths
validate_command_directory

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

remove_path_setup_from_profile() {
  local profile="$1"
  local line current tmp block changed=false in_block=false block_matches=false
  [[ -f "$profile" ]] || return 0
  line="$(path_setup_line)"
  tmp="$(mktemp "${TMPDIR:-/tmp}/symphony-trello-profile.XXXXXX")"
  if ! exec 3<"$profile"; then
    echo "  NOTE  Could not remove managed PATH setup from $profile"
    rm -f "$tmp"
    return
  fi
  while IFS= read -r current || [[ -n "$current" ]]; do
    if [[ "$in_block" == true ]]; then
      block+="$current"$'\n'
      if [[ "$current" == "$line" ]]; then
        block_matches=true
      fi
      if [[ "$current" == "$PATH_BLOCK_END" ]]; then
        if [[ "$block_matches" == true ]]; then
          changed=true
        else
          printf '%s' "$block" >>"$tmp"
        fi
        in_block=false
        block_matches=false
        block=""
      fi
      continue
    fi
    if [[ "$current" == "$PATH_BLOCK_START" ]]; then
      in_block=true
      block_matches=false
      block="$current"$'\n'
      continue
    fi
    printf '%s\n' "$current" >>"$tmp"
  done <&3
  exec 3<&-
  if [[ "$in_block" == true ]]; then
    printf '%s' "$block" >>"$tmp"
    echo "  SKIP  managed PATH setup in $profile is missing its end marker"
  fi
  if [[ "$changed" == false ]]; then
    rm -f "$tmp"
    return
  fi
  if [[ "$DRY_RUN" == true ]]; then
    echo "  WOULD remove managed PATH setup from $profile"
    rm -f "$tmp"
    return
  fi
  if ! cat "$tmp" >"$profile"; then
    echo "  NOTE  Could not remove managed PATH setup from $profile"
    rm -f "$tmp"
    return
  fi
  rm -f "$tmp"
  echo "  OK  Removed managed PATH setup from $profile"
}

remove_path_setup_from_profiles() {
  local profile
  while IFS= read -r profile; do
    remove_path_setup_from_profile "$profile"
  done < <(profile_candidates)
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

# Lifecycle output shows the workflow file name; the trailing 12-hex segment of a pid file name
# is internal state naming, not user-facing context.
worker_label() {
  local name
  name="$(basename "$1" .pid)"
  if [[ "$name" =~ \.[0-9a-f]{12}$ ]]; then
    printf '%s\n' "${name%.*}"
  else
    printf '%s\n' "$name"
  fi
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
      if [[ "$DRY_RUN" == true ]]; then
        echo "  WOULD STOP  $(worker_label "$pid_file") pid=$pid"
      else
        echo "  STOP  $(worker_label "$pid_file") pid=$pid"
        kill "$pid" >/dev/null 2>&1 || true
        wait_for_exit "$pid"
        rm -f "$pid_file"
      fi
    elif kill -0 "$pid" >/dev/null 2>&1; then
      echo "  SKIP  stale pid does not belong to this install: $(worker_label "$pid_file") pid=$pid"
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
echo "  WORKERS         Managed Symphony workers are stopped before removal"
if [[ "$REMOVE_CONFIG" == true ]]; then
  echo "  CONFIG          $CONFIG_DIR"
fi
if [[ "$REMOVE_WORKSPACES" == true ]]; then
  echo "  WORKSPACES      $WORKSPACE_ROOT"
fi
if [[ "$REMOVE_STATE" == true ]]; then
  echo "  STATE/LOGS      $STATE_HOME"
fi
echo
echo "Will preserve:"
if [[ "$REMOVE_CONFIG" == false ]]; then
  echo "  CONFIG          $CONFIG_DIR"
fi
if [[ "$REMOVE_WORKSPACES" == false ]]; then
  echo "  WORKSPACES      $WORKSPACE_ROOT"
fi
if [[ "$REMOVE_STATE" == false ]]; then
  echo "  STATE/LOGS      $STATE_HOME"
fi
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
  remove_path_setup_from_profiles
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
