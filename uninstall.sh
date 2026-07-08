#!/usr/bin/env bash
set -euo pipefail

HOME="${HOME:-}"
if [[ -z "$HOME" ]]; then
  echo "HOME must be set to a user home directory before running the uninstaller." >&2
  exit 2
fi
SYMPHONY_HOME_CONFIGURED=false
CONFIG_DIR_CONFIGURED=false
WORKSPACE_ROOT_CONFIGURED=false
STATE_HOME_CONFIGURED=false
BIN_DIR_CONFIGURED=false
APP_DIR_FROM_CONTEXT=false
CONFIG_DIR_FROM_CONTEXT=false
WORKSPACE_ROOT_FROM_CONTEXT=false
STATE_HOME_FROM_CONTEXT=false
BIN_DIR_FROM_CONTEXT=false
SYSTEMD_USER_DIR_FROM_CONTEXT=false
SYSTEMD_SERVICE_PATH_FROM_CONTEXT=false
AUTOSTART_ENV_PATH_FROM_CONTEXT=false
[[ -n "${SYMPHONY_HOME:-}" ]] && SYMPHONY_HOME_CONFIGURED=true
[[ -n "${SYMPHONY_TRELLO_CONFIG_DIR:-}" ]] && CONFIG_DIR_CONFIGURED=true
[[ -n "${SYMPHONY_TRELLO_WORKSPACE_ROOT:-}" ]] && WORKSPACE_ROOT_CONFIGURED=true
[[ -n "${SYMPHONY_TRELLO_STATE_HOME:-}" ]] && STATE_HOME_CONFIGURED=true
SYMPHONY_HOME="${SYMPHONY_HOME:-$HOME/.local/share/symphony-trello}"
APP_DIR="$SYMPHONY_HOME/app"
CONFIG_DIR="${SYMPHONY_TRELLO_CONFIG_DIR:-$SYMPHONY_HOME/config}"
WORKSPACE_ROOT="${SYMPHONY_TRELLO_WORKSPACE_ROOT:-$SYMPHONY_HOME/workspaces}"
STATE_HOME="${SYMPHONY_TRELLO_STATE_HOME:-$SYMPHONY_HOME/state}"
BIN_DIR="$HOME/.local/bin"
PREFIX_CONFIGURED=false
LAYOUT_MODE="legacy-xdg"
HOME_REAL="$HOME"
CACHE_DIR="$SYMPHONY_HOME/cache"
MICROOS_VAR_ROOT=""
MICROOS_VAR_ROOT_CREATED=false
CODEX_NPM_PREFIX="$SYMPHONY_HOME/npm"
DRY_RUN=false
YES=false
YES_LOCAL_DATA=false
REMOVE_CONFIG=false
REMOVE_WORKSPACES=false
REMOVE_STATE=false
PATH_BLOCK_START="# >>> Symphony for Trello PATH >>>"
PATH_BLOCK_END="# <<< Symphony for Trello PATH <<<"
SYSTEMD_USER_DIR="$HOME/.config/systemd/user"
SYSTEMD_SERVICE_NAME="symphony-trello.service"
SYSTEMD_SERVICE_PATH="$SYSTEMD_USER_DIR/$SYSTEMD_SERVICE_NAME"
AUTOSTART_ENV_PATH="$HOME/.config/symphony-trello/autostart.env"
LAUNCH_AGENT_DIR="$HOME/Library/LaunchAgents"
LAUNCH_AGENT_LABEL="ch.fmartin.symphony-trello"
LAUNCH_AGENT_PATH="$LAUNCH_AGENT_DIR/$LAUNCH_AGENT_LABEL.plist"

usage() {
  cat <<'USAGE'
Usage:
  curl -fsSL https://symphony-trello.fmartin.ch/uninstall.sh | bash
  curl -fsSL https://symphony-trello.fmartin.ch/uninstall.sh | bash -s -- --dry-run

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

Environment:
  SYMPHONY_HOME             Override the local app/workspace data home.
  SYMPHONY_TRELLO_CONFIG_DIR
                            Override local config and .env directory.
  SYMPHONY_TRELLO_WORKSPACE_ROOT
                            Override per-card workspace directory.
  SYMPHONY_TRELLO_STATE_HOME
                            Override local state and logs directory.
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
    BIN_DIR_CONFIGURED=true
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

same_or_inside_resolved_path() {
  local child parent
  child="$(canonicalize_existing_or_future_path "$1")"
  parent="$(canonicalize_existing_or_future_path "$2")"
  same_or_inside_path "$child" "$parent"
}

same_or_inside_lexical_or_resolved_path() {
  same_or_inside_path "$(normalize_path "$1")" "$(normalize_path "$2")" ||
    same_or_inside_resolved_path "$1" "$2"
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

command_exists() {
  command -v "$1" >/dev/null 2>&1
}

canonicalize_existing_or_future_path() {
  local path directory basename probe suffix depth
  path="$(absolutize_path "$1")"
  depth="${2:-0}"
  if ((depth > 40)); then
    normalize_path "$path"
    return
  fi
  if [[ -e "$path" && ! -L "$path" ]]; then
    if [[ -d "$path" ]]; then
      normalize_path "$(cd -P "$path" && pwd -P)"
    else
      directory="$(path_parent "$path")"
      basename="${path##*/}"
      if [[ -d "$directory" ]]; then
        normalize_path "$(cd -P "$directory" && pwd -P)/$basename"
      else
        normalize_path "$path"
      fi
    fi
    return
  fi
  if [[ -L "$path" ]] && command_exists readlink; then
    local target
    target="$(readlink "$path" || true)"
    if [[ -n "$target" ]]; then
      case "$target" in
      /*) canonicalize_existing_or_future_path "$target" "$((depth + 1))" ;;
      *) canonicalize_existing_or_future_path "$(normalize_path "$(path_parent "$path")/$target")" "$((depth + 1))" ;;
      esac
      return
    fi
  fi
  probe="$path"
  suffix=""
  while [[ ! -e "$probe" && "$probe" != "/" ]]; do
    basename="${probe##*/}"
    suffix="/$basename$suffix"
    probe="$(path_parent "$probe")"
  done
  if [[ -d "$probe" ]]; then
    normalize_path "$(cd -P "$probe" && pwd -P)$suffix"
  elif [[ -e "$probe" ]]; then
    directory="$(path_parent "$probe")"
    basename="${probe##*/}"
    normalize_path "$(cd -P "$directory" && pwd -P)/$basename$suffix"
  else
    normalize_path "$path"
  fi
}

install_context_value() {
  local file="$1" key="$2"
  [[ -f "$file" ]] || return 1
  local line value=""
  while IFS= read -r line || [[ -n "$line" ]]; do
    case "$line" in
    "$key="*) value="${line#"$key="}" ;;
    esac
  done <"$file"
  [[ -n "$value" ]] || return 1
  printf '%s\n' "$value"
}

current_username() {
  local username
  if [[ -n "${SYMPHONY_TRELLO_TEST_USER:-}" ]]; then
    username="$SYMPHONY_TRELLO_TEST_USER"
  else
    username="$(id -un)"
  fi
  if [[ -z "$username" || "$username" == "." || "$username" == ".." || "$username" == */* ||
    "$username" != "${username//[$'\001'-$'\037'$'\177']/}" ]]; then
    echo "Could not determine a safe local username for the MicroOS data root." >&2
    exit 2
  fi
  printf '%s\n' "$username"
}

microos_var_path() {
  printf '%s\n' "${SYMPHONY_TRELLO_TEST_VAR_PATH:-/var}"
}

microos_var_users_root() {
  printf '%s\n' "${SYMPHONY_TRELLO_TEST_VAR_USERS_ROOT:-$(microos_var_path)/lib/symphony-trello/users}"
}

path_is_under_microos_var() {
  local path var_path
  path="$(canonicalize_existing_or_future_path "$1")"
  var_path="$(canonicalize_existing_or_future_path "$(microos_var_path)")"
  same_or_inside_path "$path" "$var_path"
}

os_name() {
  printf '%s\n' "${SYMPHONY_TRELLO_TEST_OS:-$(uname -s)}"
}

os_release_id() {
  if [[ -n "${SYMPHONY_TRELLO_TEST_OS_ID:-}" ]]; then
    printf '%s\n' "$SYMPHONY_TRELLO_TEST_OS_ID"
    return
  fi
  if [[ -r /etc/os-release ]]; then
    local ID=""
    # shellcheck disable=SC1091
    . /etc/os-release
    printf '%s\n' "$ID"
  fi
}

os_release_id_like() {
  if [[ -n "${SYMPHONY_TRELLO_TEST_OS_ID_LIKE:-}" ]]; then
    printf '%s\n' "$SYMPHONY_TRELLO_TEST_OS_ID_LIKE"
    return
  fi
  if [[ -r /etc/os-release ]]; then
    local ID_LIKE=""
    # shellcheck disable=SC1091
    . /etc/os-release
    printf '%s\n' "$ID_LIKE"
  fi
}

os_release_pretty_name() {
  if [[ -n "${SYMPHONY_TRELLO_TEST_OS_PRETTY_NAME:-}" ]]; then
    printf '%s\n' "$SYMPHONY_TRELLO_TEST_OS_PRETTY_NAME"
    return
  fi
  if [[ -r /etc/os-release ]]; then
    local NAME="" PRETTY_NAME=""
    # shellcheck disable=SC1091
    . /etc/os-release
    printf '%s\n' "${PRETTY_NAME:-${NAME:-}}"
  fi
}

filesystem_probe_path() {
  local path="$1"
  while [[ ! -e "$path" && "$path" != "/" ]]; do
    path="$(path_parent "$path")"
  done
  printf '%s\n' "$path"
}

filesystem_source_for_path() {
  local name="$1" path="$2"
  local override="SYMPHONY_TRELLO_TEST_${name}_FS_SOURCE"
  if [[ -n "${!override:-}" ]]; then
    printf '%s\n' "${!override}"
    return
  fi
  df -P "$(filesystem_probe_path "$path")" 2>/dev/null | awk 'NR == 2 { print $1 }'
}

filesystem_size_kb_for_path() {
  local name="$1" path="$2"
  local override="SYMPHONY_TRELLO_TEST_${name}_SIZE_KB"
  if [[ -n "${!override:-}" ]]; then
    printf '%s\n' "${!override}"
    return
  fi
  df -Pk "$(filesystem_probe_path "$path")" 2>/dev/null | awk 'NR == 2 { print $2 }'
}

microos_storage_like() {
  [[ "$(os_name)" == "Linux" ]] || return 1
  [[ "${SYMPHONY_TRELLO_TEST_VAR_WRITABLE:-true}" == true ]] || return 1
  local home_source root_source var_source home_size var_size
  home_source="$(filesystem_source_for_path HOME "$HOME")"
  root_source="$(filesystem_source_for_path ROOT /)"
  var_source="$(filesystem_source_for_path VAR "$(microos_var_path)")"
  home_size="$(filesystem_size_kb_for_path HOME "$HOME")"
  var_size="$(filesystem_size_kb_for_path VAR "$(microos_var_path)")"
  [[ -n "$home_source" && -n "$root_source" && -n "$var_source" ]] || return 1
  [[ "$home_source" == "$root_source" && "$var_source" != "$root_source" ]] || return 1
  [[ "$home_size" =~ ^[0-9]+$ && "$var_size" =~ ^[0-9]+$ ]] || return 1
  ((var_size > home_size))
}

microos_distribution_like() {
  [[ "$(os_name)" == "Linux" ]] || return 1
  local id id_like pretty
  id="$(os_release_id)"
  id_like="$(os_release_id_like)"
  pretty="$(os_release_pretty_name)"
  case " $id $id_like $pretty " in
  *[Mm]icro[Oo][Ss]* | *[Ll]eap-[Mm]icro* | *[Ll]eap\ [Mm]icro* | *[Ss][Ll]-[Mm]icro* | *[Aa]eon* | *[Kk]alpa* | *"SLE Micro"* | *"SUSE Linux Enterprise Micro"*) return 0 ;;
  *) return 1 ;;
  esac
}

microos_like_layout() {
  microos_distribution_like
}

configured_install_context_candidates() {
  if [[ "$SYMPHONY_HOME_CONFIGURED" == true ]]; then
    printf '%s/state/install-context.properties\n' "$SYMPHONY_HOME"
    printf '%s/config/install-context.properties\n' "$SYMPHONY_HOME"
  fi
  if [[ "$STATE_HOME_CONFIGURED" == true ]]; then
    printf '%s/install-context.properties\n' "$STATE_HOME"
  fi
  if [[ "$CONFIG_DIR_CONFIGURED" == true ]]; then
    printf '%s/install-context.properties\n' "$CONFIG_DIR"
  fi
}

default_state_install_context_file() {
  printf '%s/.local/state/symphony-trello/install-context.properties\n' "$HOME"
}

default_config_install_context_file() {
  printf '%s/.config/symphony-trello/install-context.properties\n' "$HOME"
}

default_systemd_user_dir() {
  local config_home
  config_home="$(absolutize_path "${XDG_CONFIG_HOME:-$HOME/.config}")"
  printf '%s/systemd/user\n' "$config_home"
}

legacy_state_install_context_file() {
  printf '%s/.local/share/symphony-trello/state/install-context.properties\n' "$HOME"
}

default_install_context_candidates() {
  configured_install_context_candidates
  default_state_install_context_file
  default_config_install_context_file
  legacy_state_install_context_file
  local username var_users_root
  if username="$(current_username 2>/dev/null)"; then
    var_users_root="$(microos_var_users_root)"
    printf '%s/%s/state/install-context.properties\n' "$var_users_root" "$username"
    printf '%s/%s/config/install-context.properties\n' "$var_users_root" "$username"
  fi
}

find_existing_install_context() {
  local candidate
  while IFS= read -r candidate; do
    [[ -n "$candidate" && -f "$candidate" ]] || continue
    install_context_candidate_safe "$candidate" || continue
    install_context_replayable "$candidate" || continue
    printf '%s\n' "$candidate"
    return 0
  done < <(default_install_context_candidates)
  return 1
}

install_context_candidate_safe() {
  local candidate="$1"
  case "$candidate" in
  "$(default_state_install_context_file)" | "$(default_config_install_context_file)" | "$(legacy_state_install_context_file)")
    stable_install_context_file_safe "$candidate"
    ;;
  *) return 0 ;;
  esac
}

install_context_replayable() {
  local context_file="$1" format_version key
  format_version="$(install_context_value "$context_file" install_format_version || true)"
  for key in app_dir config_dir workspace_root state_home bin_dir codex_npm_prefix; do
    install_context_value "$context_file" "$key" >/dev/null 2>&1 || return 1
  done
  [[ "$format_version" != "2" ]] || install_context_value "$context_file" cache_dir >/dev/null 2>&1
}

stable_install_context_file_safe() {
  local context_file="$1" context_dir context_dir_resolved
  context_dir="$(path_parent "$context_file")"
  context_dir_resolved="$(canonicalize_existing_or_future_path "$context_dir")"
  is_broad_system_path "$context_dir_resolved" && return 1
  if path_has_symlink_component "$context_file" && ! trusted_path_root "$context_file"; then
    return 1
  fi
  return 0
}

apply_install_context() {
  local context_file="$1" format_version value
  format_version="$(install_context_value "$context_file" install_format_version || true)"
  value="$(install_context_value "$context_file" layout_mode || true)"
  [[ -n "$value" ]] && LAYOUT_MODE="$value"
  value="$(install_context_value "$context_file" home_real || true)"
  [[ -n "$value" ]] && HOME_REAL="$value"
  if [[ "$PREFIX_CONFIGURED" != true ]]; then
    value="$(install_context_value "$context_file" app_dir || true)"
    if [[ -n "$value" ]]; then
      APP_DIR="$value"
      APP_DIR_FROM_CONTEXT=true
    fi
  fi
  if [[ "$CONFIG_DIR_CONFIGURED" != true ]]; then
    value="$(install_context_value "$context_file" config_dir || true)"
    if [[ -n "$value" ]]; then
      CONFIG_DIR="$value"
      CONFIG_DIR_FROM_CONTEXT=true
    fi
  fi
  if [[ "$WORKSPACE_ROOT_CONFIGURED" != true ]]; then
    value="$(install_context_value "$context_file" workspace_root || true)"
    if [[ -n "$value" ]]; then
      WORKSPACE_ROOT="$value"
      WORKSPACE_ROOT_FROM_CONTEXT=true
    fi
  fi
  if [[ "$STATE_HOME_CONFIGURED" != true ]]; then
    value="$(install_context_value "$context_file" state_home || true)"
    if [[ -n "$value" ]]; then
      STATE_HOME="$value"
      STATE_HOME_FROM_CONTEXT=true
    fi
  fi
  if [[ "$BIN_DIR_CONFIGURED" != true ]]; then
    value="$(install_context_value "$context_file" bin_dir || true)"
    if [[ -n "$value" ]]; then
      BIN_DIR="$value"
      BIN_DIR_FROM_CONTEXT=true
    fi
  fi
  local cache_value=""
  cache_value="$(install_context_value "$context_file" cache_dir || true)"
  [[ -n "$cache_value" ]] && CACHE_DIR="$cache_value"
  value="$(install_context_value "$context_file" codex_npm_prefix || true)"
  if [[ -n "$value" ]]; then
    CODEX_NPM_PREFIX="$value"
    [[ -n "$cache_value" ]] || CACHE_DIR="$value"
  fi
  value="$(install_context_value "$context_file" microos_var_root || true)"
  [[ -n "$value" ]] && MICROOS_VAR_ROOT="$value"
  value="$(install_context_value "$context_file" created_microos_var_root || true)"
  [[ -n "$value" ]] && MICROOS_VAR_ROOT_CREATED="$value"
  value="$(install_context_value "$context_file" systemd_user_dir || true)"
  if [[ -n "$value" ]]; then
    SYSTEMD_USER_DIR="$value"
    SYSTEMD_USER_DIR_FROM_CONTEXT=true
  fi
  value="$(install_context_value "$context_file" systemd_service_path || true)"
  if [[ -n "$value" ]]; then
    SYSTEMD_SERVICE_PATH="$value"
    SYSTEMD_SERVICE_PATH_FROM_CONTEXT=true
  fi
  value="$(install_context_value "$context_file" autostart_env_path || true)"
  if [[ -n "$value" ]]; then
    AUTOSTART_ENV_PATH="$value"
    AUTOSTART_ENV_PATH_FROM_CONTEXT=true
  elif [[ "$CONFIG_DIR_CONFIGURED" != true ]]; then
    AUTOSTART_ENV_PATH="$HOME/.config/symphony-trello/autostart.env"
    AUTOSTART_ENV_PATH_FROM_CONTEXT=true
  fi
  if [[ "$format_version" != "2" ]]; then
    if [[ "$SYSTEMD_USER_DIR_FROM_CONTEXT" != true ]]; then
      SYSTEMD_USER_DIR="$HOME/.config/systemd/user"
      SYSTEMD_USER_DIR_FROM_CONTEXT=true
    fi
    if [[ "$SYSTEMD_SERVICE_PATH_FROM_CONTEXT" != true ]]; then
      SYSTEMD_SERVICE_PATH="$SYSTEMD_USER_DIR/$SYSTEMD_SERVICE_NAME"
      SYSTEMD_SERVICE_PATH_FROM_CONTEXT=true
    fi
    if [[ "$AUTOSTART_ENV_PATH_FROM_CONTEXT" != true ]]; then
      AUTOSTART_ENV_PATH="$HOME/.config/symphony-trello/autostart.env"
      AUTOSTART_ENV_PATH_FROM_CONTEXT=true
    fi
  fi
  if [[ "$LAYOUT_MODE" == "microos-var" && -z "$MICROOS_VAR_ROOT" ]]; then
    if [[ -n "$CACHE_DIR" ]]; then
      MICROOS_VAR_ROOT="$(path_parent "$CACHE_DIR")"
    elif [[ -n "$WORKSPACE_ROOT" ]]; then
      MICROOS_VAR_ROOT="$(path_parent "$WORKSPACE_ROOT")"
    elif [[ -n "$STATE_HOME" ]]; then
      MICROOS_VAR_ROOT="$(path_parent "$STATE_HOME")"
    else
      MICROOS_VAR_ROOT="$(path_parent "$CONFIG_DIR")"
    fi
  fi
}

refresh_service_manager_paths() {
  if [[ "$SYSTEMD_USER_DIR_FROM_CONTEXT" != true ]]; then
    SYSTEMD_USER_DIR="$(default_systemd_user_dir)"
  fi
  if [[ "$SYSTEMD_SERVICE_PATH_FROM_CONTEXT" != true ]]; then
    SYSTEMD_SERVICE_PATH="$SYSTEMD_USER_DIR/$SYSTEMD_SERVICE_NAME"
  fi
  if [[ "$AUTOSTART_ENV_PATH_FROM_CONTEXT" != true ]]; then
    AUTOSTART_ENV_PATH="$CONFIG_DIR/autostart.env"
  fi
  SYSTEMD_USER_DIR="$(absolutize_path "$SYSTEMD_USER_DIR")"
  SYSTEMD_SERVICE_PATH="$(absolutize_path "$SYSTEMD_SERVICE_PATH")"
  AUTOSTART_ENV_PATH="$(absolutize_path "$AUTOSTART_ENV_PATH")"
}

install_context_app_matches_selected_app() {
  local context_file="$1" context_app
  context_app="$(install_context_value "$context_file" app_dir || true)"
  [[ -n "$context_app" ]] || return 1
  [[ "$(canonicalize_existing_or_future_path "$context_app")" == "$(canonicalize_existing_or_future_path "$APP_DIR")" ]]
}

apply_generic_xdg_layout() {
  local data_home config_home state_home_base cache_home
  data_home="${XDG_DATA_HOME:-$HOME/.local/share}"
  config_home="${XDG_CONFIG_HOME:-$HOME/.config}"
  state_home_base="${XDG_STATE_HOME:-$HOME/.local/state}"
  cache_home="${XDG_CACHE_HOME:-$HOME/.cache}"
  if [[ "$SYMPHONY_HOME_CONFIGURED" != true ]]; then
    SYMPHONY_HOME="$data_home/symphony-trello"
  fi
  [[ "$PREFIX_CONFIGURED" == true ]] || APP_DIR="$SYMPHONY_HOME/app"
  if [[ "$SYMPHONY_HOME_CONFIGURED" == true ]]; then
    [[ "$CONFIG_DIR_CONFIGURED" == true ]] || CONFIG_DIR="$SYMPHONY_HOME/config"
    [[ "$WORKSPACE_ROOT_CONFIGURED" == true ]] || WORKSPACE_ROOT="$SYMPHONY_HOME/workspaces"
    [[ "$STATE_HOME_CONFIGURED" == true ]] || STATE_HOME="$SYMPHONY_HOME/state"
    CACHE_DIR="$SYMPHONY_HOME/cache"
  else
    [[ "$CONFIG_DIR_CONFIGURED" == true ]] || CONFIG_DIR="$config_home/symphony-trello"
    [[ "$WORKSPACE_ROOT_CONFIGURED" == true ]] || WORKSPACE_ROOT="$SYMPHONY_HOME/workspaces"
    [[ "$STATE_HOME_CONFIGURED" == true ]] || STATE_HOME="$state_home_base/symphony-trello"
    CACHE_DIR="$cache_home/symphony-trello"
  fi
  [[ "$BIN_DIR_CONFIGURED" == true ]] || BIN_DIR="$HOME/.local/bin"
  if [[ "$SYMPHONY_HOME_CONFIGURED" == true ]]; then
    CODEX_NPM_PREFIX="$SYMPHONY_HOME/npm"
  else
    CODEX_NPM_PREFIX="$CACHE_DIR/npm"
  fi
}

apply_microos_var_layout() {
  local username root
  username="$(current_username)"
  root="$(microos_var_users_root)/$username"
  MICROOS_VAR_ROOT="$root"
  LAYOUT_MODE="microos-var"
  SYMPHONY_HOME="$root/data"
  [[ "$PREFIX_CONFIGURED" == true ]] || APP_DIR="$SYMPHONY_HOME/app"
  [[ "$CONFIG_DIR_CONFIGURED" == true ]] || CONFIG_DIR="$root/config"
  [[ "$WORKSPACE_ROOT_CONFIGURED" == true ]] || WORKSPACE_ROOT="$root/workspaces"
  [[ "$STATE_HOME_CONFIGURED" == true ]] || STATE_HOME="$root/state"
  CACHE_DIR="$root/cache"
  [[ "$BIN_DIR_CONFIGURED" == true ]] || BIN_DIR="$HOME/.local/bin"
  CODEX_NPM_PREFIX="$CACHE_DIR/npm"
}

choose_default_layout() {
  local existing_context microos_storage=false microos_home_on_var=false
  HOME_REAL="$(canonicalize_existing_or_future_path "$HOME")"
  existing_context="$(find_existing_install_context || true)"
  if [[ -n "$existing_context" ]]; then
    if [[ "$SYMPHONY_HOME_CONFIGURED" != true && "$PREFIX_CONFIGURED" != true ]] ||
      install_context_app_matches_selected_app "$existing_context"; then
      apply_install_context "$existing_context"
      return
    fi
  fi
  if [[ "$SYMPHONY_HOME_CONFIGURED" == true ]]; then
    apply_generic_xdg_layout
    return
  fi
  if microos_storage_like; then
    microos_storage=true
  fi
  if path_is_under_microos_var "$HOME_REAL"; then
    microos_home_on_var=true
  fi
  if [[ "$microos_storage" == true && "$microos_home_on_var" != true ]]; then
    apply_microos_var_layout
    return
  fi
  if [[ "$CONFIG_DIR_CONFIGURED" == true ||
    "$WORKSPACE_ROOT_CONFIGURED" == true ||
    "$STATE_HOME_CONFIGURED" == true ||
    "$PREFIX_CONFIGURED" == true ]]; then
    apply_generic_xdg_layout
    return
  fi
  if [[ "$microos_home_on_var" == true ]] && microos_like_layout; then
    LAYOUT_MODE="microos-home-var"
  fi
  apply_generic_xdg_layout
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
  / | /bin | /boot | /dev | /etc | /home | /lib | /lib64 | /opt | /proc | /root | /run | /sbin | /sys | /tmp | /usr | /usr/bin | /usr/lib | /usr/lib64 | /usr/sbin | /var) return 0 ;;
  *) return 1 ;;
  esac
}

trusted_path_root() {
  local path="$1" resolved_path home_real microos_root_real
  resolved_path="$(canonicalize_existing_or_future_path "$path")"
  home_real="$(canonicalize_existing_or_future_path "$HOME")"
  if same_or_inside_path "$resolved_path" "$home_real"; then
    return 0
  fi
  if [[ -n "$MICROOS_VAR_ROOT" ]]; then
    microos_root_real="$(canonicalize_existing_or_future_path "$MICROOS_VAR_ROOT")"
    same_or_inside_path "$resolved_path" "$microos_root_real" && return 0
  fi
  return 1
}

validate_symlinked_path_target() {
  local raw_path="$1" message="$2" explicit="${3:-false}"
  if path_has_symlink_component "$raw_path"; then
    local resolved_path
    resolved_path="$(canonicalize_existing_or_future_path "$raw_path")"
    if is_broad_system_path "$resolved_path"; then
      echo "$message" >&2
      exit 2
    fi
    [[ "$explicit" == true ]] && return
    if ! trusted_path_root "$raw_path"; then
      echo "$message" >&2
      exit 2
    fi
  fi
}

validate_app_paths() {
  local raw_app_dir app_dir app_dir_resolved app_dir_explicit home_dir home_dir_resolved symphony_home symphony_home_resolved
  local checkout_dir checkout_dir_resolved root root_resolved root_explicit
  local cache_dir cache_dir_resolved codex_npm_prefix codex_npm_prefix_resolved cache_explicit
  raw_app_dir="$APP_DIR"
  app_dir="$(normalize_path "$APP_DIR")"
  app_dir_resolved="$(canonicalize_existing_or_future_path "$raw_app_dir")"
  app_dir_explicit=false
  [[ "$SYMPHONY_HOME_CONFIGURED" == true || "$PREFIX_CONFIGURED" == true || "$APP_DIR_FROM_CONTEXT" == true ]] &&
    app_dir_explicit=true
  home_dir="$(normalize_path "$HOME")"
  home_dir_resolved="$(canonicalize_existing_or_future_path "$HOME")"
  symphony_home="$(normalize_path "$SYMPHONY_HOME")"
  symphony_home_resolved="$(canonicalize_existing_or_future_path "$SYMPHONY_HOME")"
  checkout_dir="$(script_checkout_dir || true)"
  checkout_dir_resolved=""
  [[ -n "$checkout_dir" ]] && checkout_dir_resolved="$(canonicalize_existing_or_future_path "$checkout_dir")"
  if is_broad_system_path "$app_dir" ||
    is_broad_system_path "$app_dir_resolved" ||
    is_broad_system_path "$symphony_home" ||
    is_broad_system_path "$symphony_home_resolved" ||
    [[ "$app_dir" == "$home_dir" || "$symphony_home" == "$home_dir" ||
      "$app_dir_resolved" == "$home_dir_resolved" || "$symphony_home_resolved" == "$home_dir_resolved" ||
      (-n "$checkout_dir" && "$app_dir" == "$checkout_dir") ||
      (-n "$checkout_dir_resolved" && "$app_dir_resolved" == "$checkout_dir_resolved") ]]; then
    echo "--prefix must point to a dedicated app checkout directory." >&2
    exit 2
  fi
  if [[ -L "$raw_app_dir" || -L "$app_dir" ]]; then
    echo "--prefix must not be a symlink." >&2
    exit 2
  fi
  validate_symlinked_path_target "$raw_app_dir" "--prefix must resolve inside the user home or Symphony MicroOS data root." "$app_dir_explicit"
  validate_symlinked_path_target "$app_dir" "--prefix must resolve inside the user home or Symphony MicroOS data root." "$app_dir_explicit"
  if [[ -e "$app_dir" && ! -d "$app_dir" ]]; then
    echo "--prefix must be a directory." >&2
    exit 2
  fi
  for root in "$CONFIG_DIR" "$WORKSPACE_ROOT" "$STATE_HOME"; do
    root_resolved="$(canonicalize_existing_or_future_path "$root")"
    root="$(normalize_path "$root")"
    if is_broad_system_path "$root_resolved"; then
      echo "Symphony config, workspace, and state paths must point to dedicated directories." >&2
      exit 2
    fi
    root_explicit=false
    case "$root" in
    "$(normalize_path "$CONFIG_DIR")")
      [[ "$SYMPHONY_HOME_CONFIGURED" == true || "$CONFIG_DIR_CONFIGURED" == true || "$CONFIG_DIR_FROM_CONTEXT" == true ]] &&
        root_explicit=true
      ;;
    "$(normalize_path "$WORKSPACE_ROOT")")
      [[ "$SYMPHONY_HOME_CONFIGURED" == true || "$WORKSPACE_ROOT_CONFIGURED" == true || "$WORKSPACE_ROOT_FROM_CONTEXT" == true ]] &&
        root_explicit=true
      ;;
    "$(normalize_path "$STATE_HOME")")
      [[ "$SYMPHONY_HOME_CONFIGURED" == true || "$STATE_HOME_CONFIGURED" == true || "$STATE_HOME_FROM_CONTEXT" == true ]] &&
        root_explicit=true
      ;;
    esac
    validate_symlinked_path_target "$root" "Symphony config, workspace, and state paths must resolve inside the user home or Symphony MicroOS data root." "$root_explicit"
    if [[ ! -e "$root" && ! -L "$root" ]] &&
      (same_or_inside_path "$app_dir_resolved" "$root_resolved" || same_or_inside_path "$root_resolved" "$app_dir_resolved"); then
      echo "--prefix must not overlap Symphony config, workspace, or state directories." >&2
      exit 2
    fi
  done
  cache_dir="$(normalize_path "$CACHE_DIR")"
  cache_dir_resolved="$(canonicalize_existing_or_future_path "$CACHE_DIR")"
  codex_npm_prefix="$(normalize_path "$CODEX_NPM_PREFIX")"
  codex_npm_prefix_resolved="$(canonicalize_existing_or_future_path "$CODEX_NPM_PREFIX")"
  if is_broad_system_path "$cache_dir" ||
    is_broad_system_path "$cache_dir_resolved" ||
    is_broad_system_path "$codex_npm_prefix" ||
    is_broad_system_path "$codex_npm_prefix_resolved" ||
    [[ "$cache_dir" == "$home_dir" || "$codex_npm_prefix" == "$home_dir" ||
      "$cache_dir_resolved" == "$home_dir_resolved" || "$codex_npm_prefix_resolved" == "$home_dir_resolved" ]]; then
    echo "Symphony cache and managed npm paths must point to dedicated directories." >&2
    exit 2
  fi
  cache_explicit=false
  [[ "$SYMPHONY_HOME_CONFIGURED" == true ]] && cache_explicit=true
  validate_symlinked_path_target "$CACHE_DIR" "Symphony cache and managed npm paths must resolve inside the user home or Symphony MicroOS data root." "$cache_explicit"
  validate_symlinked_path_target "$CODEX_NPM_PREFIX" "Symphony cache and managed npm paths must resolve inside the user home or Symphony MicroOS data root." "$cache_explicit"
  for root in "$APP_DIR" "$CONFIG_DIR" "$WORKSPACE_ROOT" "$STATE_HOME"; do
    root_resolved="$(canonicalize_existing_or_future_path "$root")"
    if [[ "$root_resolved" == "$app_dir_resolved" &&
      "$SYMPHONY_HOME_CONFIGURED" == true &&
      "$app_dir_resolved" == "$symphony_home_resolved" ]]; then
      continue
    fi
    if same_or_inside_path "$cache_dir_resolved" "$root_resolved" ||
      same_or_inside_path "$root_resolved" "$cache_dir_resolved" ||
      same_or_inside_path "$codex_npm_prefix_resolved" "$root_resolved" ||
      same_or_inside_path "$root_resolved" "$codex_npm_prefix_resolved"; then
      echo "Symphony cache and managed npm paths must not overlap Symphony app, config, workspace, or state directories." >&2
      exit 2
    fi
  done
}

validate_command_directory() {
  local bin_dir bin_dir_resolved bin_dir_explicit home_dir home_dir_resolved checkout_dir checkout_dir_resolved root root_resolved
  bin_dir="$(normalize_path "$BIN_DIR")"
  bin_dir_resolved="$(canonicalize_existing_or_future_path "$BIN_DIR")"
  home_dir="$(normalize_path "$HOME")"
  home_dir_resolved="$(canonicalize_existing_or_future_path "$HOME")"
  checkout_dir="$(script_checkout_dir || true)"
  checkout_dir_resolved=""
  [[ -n "$checkout_dir" ]] && checkout_dir_resolved="$(canonicalize_existing_or_future_path "$checkout_dir")"
  if [[ "$bin_dir" == "/" || "$bin_dir" == "$home_dir" || "$bin_dir_resolved" == "$home_dir_resolved" ||
    (-n "$checkout_dir" && "$bin_dir" == "$checkout_dir") ||
    (-n "$checkout_dir_resolved" && "$bin_dir_resolved" == "$checkout_dir_resolved") ]]; then
    echo "--bin-dir must point to a dedicated command directory." >&2
    exit 2
  fi
  if is_broad_system_path "$bin_dir_resolved" || is_broad_system_path "$bin_dir"; then
    echo "--bin-dir must point to a dedicated command directory." >&2
    exit 2
  fi
  bin_dir_explicit=false
  [[ "$BIN_DIR_CONFIGURED" == true || "$BIN_DIR_FROM_CONTEXT" == true ]] && bin_dir_explicit=true
  validate_symlinked_path_target "$BIN_DIR" "--bin-dir must resolve inside the user home or Symphony MicroOS data root." "$bin_dir_explicit"
  validate_symlinked_path_target "$bin_dir" "--bin-dir must resolve inside the user home or Symphony MicroOS data root." "$bin_dir_explicit"
  if [[ -e "$bin_dir" && ! -d "$bin_dir" ]]; then
    echo "--bin-dir must be a directory." >&2
    exit 2
  fi
  for root in "$APP_DIR" "$CONFIG_DIR" "$WORKSPACE_ROOT" "$STATE_HOME"; do
    root_resolved="$(canonicalize_existing_or_future_path "$root")"
    if same_or_inside_path "$bin_dir_resolved" "$root_resolved" || same_or_inside_path "$root_resolved" "$bin_dir_resolved"; then
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

choose_default_layout
validate_bin_dir_option_value
validate_app_path_option_values
SYMPHONY_HOME="$(absolutize_path "$SYMPHONY_HOME")"
APP_DIR="$(absolutize_path "$APP_DIR")"
CONFIG_DIR="$(absolutize_path "$CONFIG_DIR")"
WORKSPACE_ROOT="$(absolutize_path "$WORKSPACE_ROOT")"
STATE_HOME="$(absolutize_path "$STATE_HOME")"
BIN_DIR="$(absolutize_path "$BIN_DIR")"
CACHE_DIR="$(absolutize_path "$CACHE_DIR")"
CODEX_NPM_PREFIX="$(absolutize_path "$CODEX_NPM_PREFIX")"
refresh_service_manager_paths
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
  if is_broad_system_path "$normalized"; then
    echo "Refusing dangerous removal path: $raw_path" >&2
    exit 2
  fi
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

validate_custom_prefix_local_data_cleanup() {
  local default_app_dir
  default_app_dir="$(normalize_path "$SYMPHONY_HOME/app")"
  if [[ "$PREFIX_CONFIGURED" != true || "$(normalize_path "$APP_DIR")" == "$default_app_dir" ]]; then
    return
  fi

  local missing=()
  if [[ "$REMOVE_CONFIG" == true &&
    "$SYMPHONY_HOME_CONFIGURED" != true &&
    "$CONFIG_DIR_CONFIGURED" != true &&
    "$CONFIG_DIR_FROM_CONTEXT" != true ]]; then
    missing+=("CONFIG: set SYMPHONY_HOME or SYMPHONY_TRELLO_CONFIG_DIR")
  fi
  if [[ "$REMOVE_WORKSPACES" == true &&
    "$SYMPHONY_HOME_CONFIGURED" != true &&
    "$WORKSPACE_ROOT_CONFIGURED" != true &&
    "$WORKSPACE_ROOT_FROM_CONTEXT" != true ]]; then
    missing+=("WORKSPACES: set SYMPHONY_HOME or SYMPHONY_TRELLO_WORKSPACE_ROOT")
  fi
  if [[ "$REMOVE_STATE" == true &&
    "$SYMPHONY_HOME_CONFIGURED" != true &&
    "$STATE_HOME_CONFIGURED" != true &&
    "$STATE_HOME_FROM_CONTEXT" != true ]]; then
    missing+=("STATE/LOGS: set SYMPHONY_HOME or SYMPHONY_TRELLO_STATE_HOME")
  fi
  if ((${#missing[@]} == 0)); then
    return
  fi

  echo "Refusing to remove default local data while uninstalling a custom --prefix." >&2
  printf '  %s\n' "${missing[@]}" >&2
  echo "Set SYMPHONY_HOME to the install home or set the listed local-data environment variables, then rerun." >&2
  exit 2
}

validate_requested_removal_paths
validate_command_directory
validate_custom_prefix_local_data_cleanup

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
      delete_tree_safely "$path"
    fi
  fi
}

directory_is_empty() {
  local path="$1"
  [[ -d "$path" ]] || return 1
  [[ -z "$(find "$path" -mindepth 1 -maxdepth 1 -print -quit)" ]]
}

planned_microos_removal_target() {
  local path="$1" path_real target target_real
  path_real="$(canonicalize_existing_or_future_path "$path")"
  for target in "$APP_DIR" "$CONFIG_DIR" "$WORKSPACE_ROOT" "$STATE_HOME" "$CODEX_NPM_PREFIX"; do
    [[ -n "$target" ]] || continue
    target_real="$(canonicalize_existing_or_future_path "$target")"
    if same_or_inside_path "$path_real" "$target_real"; then
      return 0
    fi
  done
  return 1
}

planned_microos_empty_child_dir() {
  local path="$1" path_real child parent parent_real
  path_real="$(canonicalize_existing_or_future_path "$path")"
  for parent in "$(path_parent "$APP_DIR")" "$SYMPHONY_HOME" "$CACHE_DIR"; do
    [[ -n "$parent" ]] || continue
    parent_real="$(canonicalize_existing_or_future_path "$parent")"
    if [[ "$path_real" == "$parent_real" ]]; then
      while IFS= read -r child; do
        planned_microos_cleanup_removes_path "$child" || return 1
      done < <(find "$path" -mindepth 1 -maxdepth 1 -print)
      return 0
    fi
  done
  return 1
}

planned_microos_cleanup_removes_path() {
  local path="$1"
  planned_microos_removal_target "$path" && return 0
  [[ -d "$path" ]] || return 1
  planned_microos_empty_child_dir "$path"
}

microos_var_root_empty_after_planned_cleanup() {
  local child
  [[ -d "$MICROOS_VAR_ROOT" ]] || return 1
  while IFS= read -r child; do
    planned_microos_cleanup_removes_path "$child" || return 1
  done < <(find "$MICROOS_VAR_ROOT" -mindepth 1 -maxdepth 1 -print)
  return 0
}

remove_empty_microos_child_dir() {
  local path="$1" path_real root_real
  [[ "$DRY_RUN" == false && -n "$path" && -d "$path" ]] || return 0
  path_real="$(canonicalize_existing_or_future_path "$path")"
  root_real="$(canonicalize_existing_or_future_path "$MICROOS_VAR_ROOT")"
  [[ "$path_real" != "$root_real" ]] || return 0
  same_or_inside_path "$path_real" "$root_real" || return 0
  rmdir "$path" 2>/dev/null || true
}

cleanup_microos_var_root_if_empty() {
  [[ "$LAYOUT_MODE" == "microos-var" ]] || return 0
  [[ "$MICROOS_VAR_ROOT_CREATED" == true ]] || return 0
  [[ "$REMOVE_CONFIG" == true && "$REMOVE_WORKSPACES" == true && "$REMOVE_STATE" == true ]] || return 0
  [[ -n "$MICROOS_VAR_ROOT" && -d "$MICROOS_VAR_ROOT" ]] || return 0
  if [[ "$DRY_RUN" == true ]]; then
    if microos_var_root_empty_after_planned_cleanup; then
      echo
      remove_path "$MICROOS_VAR_ROOT"
    else
      echo "Kept MicroOS data root because it is not empty: $MICROOS_VAR_ROOT"
    fi
    return 0
  fi
  remove_empty_microos_child_dir "$(path_parent "$APP_DIR")"
  remove_empty_microos_child_dir "$SYMPHONY_HOME"
  remove_empty_microos_child_dir "$CACHE_DIR"
  if ! directory_is_empty "$MICROOS_VAR_ROOT"; then
    echo "Kept MicroOS data root because it is not empty: $MICROOS_VAR_ROOT"
    return 0
  fi
  echo
  if confirm "Remove empty MicroOS data root created by the installer?" "$YES_LOCAL_DATA"; then
    remove_path "$MICROOS_VAR_ROOT"
  else
    echo "Kept MicroOS data root: $MICROOS_VAR_ROOT"
  fi
}

remove_default_install_context_files() {
  local app_removal_confirmed="${1:-false}"
  [[ "$app_removal_confirmed" == true ]] || return 0
  [[ "$REMOVE_CONFIG" == true && "$REMOVE_WORKSPACES" == true && "$REMOVE_STATE" == true ]] || return 0

  local context_files=()
  local context_file has_context=false
  while IFS= read -r context_file; do
    [[ -n "$context_file" ]] || continue
    stable_install_context_file_safe "$context_file" || continue
    context_files+=("$context_file")
    if [[ -e "$context_file" || -L "$context_file" ]]; then
      has_context=true
    fi
  done < <(
    {
      default_state_install_context_file
      default_config_install_context_file
    } | sort -u
  )

  [[ "$has_context" == true ]] || return 0
  echo
  if confirm "Remove install context discovery files for future no-flag installs and uninstalls?" "$YES_LOCAL_DATA"; then
    for context_file in "${context_files[@]}"; do
      remove_path "$context_file"
    done
  else
    echo "Kept install context discovery files."
  fi
}

path_is_btrfs_subvolume() {
  command_exists btrfs && btrfs subvolume show "$1" >/dev/null 2>&1
}

delete_nested_btrfs_subvolumes() {
  local path="$1"
  local require_mount_root="${2:-true}"
  local mount_root subvolumes
  command_exists btrfs || return 0
  mount_root="$(btrfs_mount_root "$path")"
  if [[ -z "$mount_root" ]]; then
    if [[ "$require_mount_root" != true ]]; then
      return 0
    fi
    echo "Could not determine btrfs mount root for: $path" >&2
    exit 2
  fi
  if ! subvolumes="$(btrfs subvolume list -o "$path" 2>/dev/null)"; then
    if [[ "$require_mount_root" != true ]]; then
      return 0
    fi
    echo "Could not list nested btrfs subvolumes under: $path" >&2
    exit 2
  fi
  printf '%s\n' "$subvolumes" |
    awk '{
      for (field_index = 1; field_index <= NF; field_index++) {
        if ($field_index == "path") {
          path = $(field_index + 1)
          for (field = field_index + 2; field <= NF; field++) {
            path = path " " $field
          }
          print path
          next
        }
      }
    }' |
    awk '{ path = $0; slash_count = gsub("/", "/", path); printf "%08d\t%08d\t%s\n", slash_count, length($0), $0 }' |
    sort -r |
    cut -f3- |
    while IFS= read -r subvolume; do
      [[ -n "$subvolume" ]] || continue
      case "$subvolume" in
      /*) btrfs subvolume delete "$subvolume" ;;
      *) btrfs subvolume delete "$(btrfs_visible_subvolume_path "$path" "$mount_root" "$subvolume")" ;;
      esac
    done
}

btrfs_visible_subvolume_path() {
  local target="$1" mount_root="$2" subvolume="$3"
  local target_name target_real mount_root_real target_relative suffix parent
  target_real="$(canonicalize_existing_or_future_path "$target")"
  mount_root_real="$(canonicalize_existing_or_future_path "$mount_root")"
  if [[ "$target_real" == "$mount_root_real" ]]; then
    target_name="${target_real##*/}"
    case "$subvolume" in
    "$target_name"/*) printf '%s/%s\n' "$target_real" "${subvolume#"$target_name"/}" ;;
    *) printf '%s/%s\n' "$target_real" "$subvolume" ;;
    esac
    return
  fi
  if same_or_inside_path "$target_real" "$mount_root_real"; then
    target_relative="${target_real#"$mount_root_real"}"
    target_relative="${target_relative#/}"
    if [[ -n "$target_relative" ]]; then
      case "$subvolume" in
      "$target_relative" | "$target_relative"/* | */"$target_relative" | */"$target_relative"/*)
        suffix="${subvolume#*"$target_relative"}"
        printf '%s/%s%s\n' "$mount_root_real" "$target_relative" "$suffix"
        return
        ;;
      esac
    fi
  fi
  parent="$(path_parent "$mount_root_real")"
  if [[ "$subvolume" == */* ]]; then
    printf '%s/%s\n' "$parent" "${subvolume#*/}"
  else
    printf '%s/%s\n' "$mount_root_real" "$subvolume"
  fi
}

btrfs_mount_root() {
  local path="$1" mount_root
  if command_exists findmnt; then
    mount_root="$(findmnt -T "$path" -n -o TARGET 2>/dev/null | head -1 || true)"
    if [[ -n "$mount_root" ]]; then
      printf '%s\n' "$mount_root"
      return
    fi
  fi
  df -P "$path" 2>/dev/null | awk 'NR == 2 { print $6 }'
}

rm_supports_one_file_system() {
  rm --help 2>/dev/null | grep -q -- '--one-file-system'
}

delete_tree_safely() {
  local path="$1"
  if [[ -L "$path" ]]; then
    rm -f "$path"
    return
  fi
  if path_is_btrfs_subvolume "$path"; then
    delete_nested_btrfs_subvolumes "$path"
    btrfs subvolume delete "$path"
    return
  fi
  if command_exists btrfs; then
    delete_nested_btrfs_subvolumes "$path" false
  fi
  if rm_supports_one_file_system; then
    rm -rf --one-file-system "$path"
  else
    rm -rf "$path"
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
    if path_exists "$CONFIG_DIR" && same_or_inside_lexical_or_resolved_path "$CONFIG_DIR" "$APP_DIR"; then conflicts+=("CONFIG     $CONFIG_DIR"); fi
  elif [[ "$YES_LOCAL_DATA" == false ]] && path_exists "$CONFIG_DIR" && same_or_inside_lexical_or_resolved_path "$CONFIG_DIR" "$APP_DIR"; then
    cleanup_paths+=("CONFIG     $CONFIG_DIR")
  fi
  if [[ "$REMOVE_WORKSPACES" == false ]]; then
    if path_exists "$WORKSPACE_ROOT" && same_or_inside_lexical_or_resolved_path "$WORKSPACE_ROOT" "$APP_DIR"; then conflicts+=("WORKSPACES $WORKSPACE_ROOT"); fi
  elif [[ "$YES_LOCAL_DATA" == false ]] && path_exists "$WORKSPACE_ROOT" && same_or_inside_lexical_or_resolved_path "$WORKSPACE_ROOT" "$APP_DIR"; then
    cleanup_paths+=("WORKSPACES $WORKSPACE_ROOT")
  fi
  if [[ "$REMOVE_STATE" == false ]]; then
    if path_exists "$STATE_HOME" && same_or_inside_lexical_or_resolved_path "$STATE_HOME" "$APP_DIR"; then conflicts+=("STATE/LOGS $STATE_HOME"); fi
  elif [[ "$YES_LOCAL_DATA" == false ]] && path_exists "$STATE_HOME" && same_or_inside_lexical_or_resolved_path "$STATE_HOME" "$APP_DIR"; then
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

# A bounded kill -0 poll is the standard mechanism here: POSIX shell `wait` only covers child
# processes, and managed workers are not children of this script. The PowerShell uninstaller can
# use Wait-Process instead because it waits on process handles directly.
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

remove_user_systemd_service() {
  if [[ ! -e "$SYSTEMD_SERVICE_PATH" && ! -L "$SYSTEMD_SERVICE_PATH" ]]; then
    return
  fi
  if [[ "$DRY_RUN" == true ]]; then
    echo "  WOULD disable user systemd service: $SYSTEMD_SERVICE_NAME"
    echo "  WOULD remove user systemd service: $SYSTEMD_SERVICE_PATH"
    return
  fi
  if command -v systemctl >/dev/null 2>&1; then
    echo "  STOP  user systemd service: $SYSTEMD_SERVICE_NAME"
    systemctl --user disable --now "$SYSTEMD_SERVICE_NAME" >/dev/null 2>&1 || true
    systemctl --user daemon-reload >/dev/null 2>&1 || true
  fi
  remove_path "$SYSTEMD_SERVICE_PATH"
}

remove_launch_agent() {
  if [[ ! -e "$LAUNCH_AGENT_PATH" && ! -L "$LAUNCH_AGENT_PATH" ]]; then
    return
  fi
  if [[ "$DRY_RUN" == true ]]; then
    echo "  WOULD disable macOS LaunchAgent: $LAUNCH_AGENT_LABEL"
    echo "  WOULD remove macOS LaunchAgent: $LAUNCH_AGENT_PATH"
    return
  fi
  if command -v launchctl >/dev/null 2>&1; then
    echo "  STOP  macOS LaunchAgent: $LAUNCH_AGENT_LABEL"
    launchctl bootout "gui/$(id -u)" "$LAUNCH_AGENT_PATH" >/dev/null 2>&1 || true
  fi
  remove_path "$LAUNCH_AGENT_PATH"
}

remove_autostart_environment_file() {
  if [[ ! -e "$AUTOSTART_ENV_PATH" && ! -L "$AUTOSTART_ENV_PATH" ]]; then
    return
  fi
  if [[ "$DRY_RUN" == true ]]; then
    echo "  WOULD remove autostart environment snapshot: $AUTOSTART_ENV_PATH"
    return
  fi
  remove_path "$AUTOSTART_ENV_PATH"
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

print_user_systemd_removal_plan() {
  if [[ -e "$SYSTEMD_SERVICE_PATH" || -L "$SYSTEMD_SERVICE_PATH" ]]; then
    echo "  USER SERVICE    $SYSTEMD_SERVICE_PATH"
  fi
}

print_launch_agent_removal_plan() {
  if [[ -e "$LAUNCH_AGENT_PATH" || -L "$LAUNCH_AGENT_PATH" ]]; then
    echo "  LAUNCH AGENT    $LAUNCH_AGENT_PATH"
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
print_user_systemd_removal_plan
print_launch_agent_removal_plan
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

APP_REMOVAL_CONFIRMED=false
if confirm "Remove installer-managed app files and CLI executable?" "$YES"; then
  APP_REMOVAL_CONFIRMED=true
  if [[ -e "$APP_DIR" && ! -f "$APP_DIR/.symphony-trello-install" ]]; then
    echo "Refusing to remove app directory without Symphony installer marker: $APP_DIR" >&2
    exit 2
  fi
  assert_app_removal_preserves_current_data
  stop_managed_processes
  remove_user_systemd_service
  remove_launch_agent
  remove_autostart_environment_file
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

remove_default_install_context_files "$APP_REMOVAL_CONFIRMED"
cleanup_microos_var_root_if_empty

echo
echo "Trello boards were not deleted or archived."
