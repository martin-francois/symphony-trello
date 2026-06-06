#!/usr/bin/env bash
set -euo pipefail

ORIGINAL_PATH="$PATH"
REPO_URL="${SYMPHONY_TRELLO_REPO_URL:-https://github.com/martin-francois/symphony-trello.git}"
DEFAULT_REF="v0.2.0" # x-release-please-version
REF="${SYMPHONY_TRELLO_REF:-$DEFAULT_REF}"
SYMPHONY_HOME="${SYMPHONY_HOME:-$HOME/.local/share/symphony-trello}"
APP_DIR="$SYMPHONY_HOME/app"
CONFIG_DIR="${SYMPHONY_TRELLO_CONFIG_DIR:-$SYMPHONY_HOME/config}"
WORKSPACE_ROOT="${SYMPHONY_TRELLO_WORKSPACE_ROOT:-$SYMPHONY_HOME/workspaces}"
STATE_HOME="${SYMPHONY_TRELLO_STATE_HOME:-$SYMPHONY_HOME/state}"
BIN_DIR="$HOME/.local/bin"
DRY_RUN=false
NO_ONBOARD=false
SKIP_PATH_SETUP=false
OS_NAME=""
OS_ARCH=""
BREW_OPENJDK_BIN=""
CODEX_NPM_PREFIX="$SYMPHONY_HOME/npm"
APT_UPDATED=false

usage() {
  cat <<USAGE
Usage:
  export SYMPHONY_TRELLO_REF=${DEFAULT_REF}
  curl -fsSL "https://raw.githubusercontent.com/martin-francois/symphony-trello/\${SYMPHONY_TRELLO_REF}/install.sh" | bash
  curl -fsSL "https://raw.githubusercontent.com/martin-francois/symphony-trello/\${SYMPHONY_TRELLO_REF}/install.sh" | bash -s -- --dry-run

Options:
  --dry-run          Print planned actions without changing files.
  --no-onboard      Install or update the command without running setup-local.
  --no-update-path   Do not edit shell profile files.
  --prefix PATH     App checkout path. Default: \$SYMPHONY_HOME/app
  --bin-dir PATH    Command directory. Default: ~/.local/bin
  --repo URL        Git repository URL.
  --ref REF         Git ref to check out. Default: ${DEFAULT_REF}
  --help            Show this help.

Environment:
  SYMPHONY_HOME      Base directory for local app/config/workspace/state.
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
  --dry-run) DRY_RUN=true ;;
  --no-onboard) NO_ONBOARD=true ;;
  --no-update-path) SKIP_PATH_SETUP=true ;;
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
  --repo)
    if [[ $# -lt 2 ]]; then
      echo "Missing value for --repo" >&2
      exit 2
    fi
    if [[ "$2" == -* ]]; then
      echo "Missing value for --repo" >&2
      exit 2
    fi
    REPO_URL="$2"
    shift
    ;;
  --ref)
    if [[ $# -lt 2 ]]; then
      echo "Missing value for --ref" >&2
      exit 2
    fi
    if [[ "$2" == -* ]]; then
      echo "Missing value for --ref" >&2
      exit 2
    fi
    REF="$2"
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

run() {
  printf '  RUN  %s\n' "$*"
  if [[ "$DRY_RUN" == false ]]; then
    "$@"
  fi
}

run_with_label() {
  local label="$1"
  shift
  printf '  RUN  %s\n' "$label"
  if [[ "$DRY_RUN" == false ]]; then
    "$@"
  fi
}

terminal_available() {
  [[ -e /dev/tty ]] && { : </dev/tty; } 2>/dev/null
}

run_interactive() {
  if ! terminal_available; then
    echo "This step needs an interactive terminal. Rerun the installer from a terminal or pass --no-onboard." >&2
    exit 2
  fi
  printf '  RUN  %s\n' "$*"
  if [[ "$DRY_RUN" == false ]]; then
    "$@" </dev/tty
  fi
}

write_install_context() {
  if [[ "$DRY_RUN" == true ]]; then
    return
  fi
  mkdir -p "$STATE_HOME" 2>/dev/null || return
  {
    printf 'installer=install.sh\n'
    printf 'platform=%s\n' "$(platform_name)"
    printf 'os_name=%s\n' "$OS_NAME"
    printf 'os_arch=%s\n' "$OS_ARCH"
    printf 'repo_url=%s\n' "$REPO_URL"
    printf 'ref=%s\n' "$REF"
    printf 'app_dir=%s\n' "$APP_DIR"
    printf 'config_dir=%s\n' "$CONFIG_DIR"
    printf 'workspace_root=%s\n' "$WORKSPACE_ROOT"
    printf 'state_home=%s\n' "$STATE_HOME"
    printf 'bin_dir=%s\n' "$BIN_DIR"
    printf 'dry_run=%s\n' "$DRY_RUN"
    printf 'no_onboard=%s\n' "$NO_ONBOARD"
    printf 'apt_updated=%s\n' "$APT_UPDATED"
    printf 'codex_npm_prefix=%s\n' "$CODEX_NPM_PREFIX"
    printf 'git_available=%s\n' "$(if need git; then echo yes; else echo no; fi)"
    printf 'java_25_available=%s\n' "$(if jdk_compatible; then echo yes; else echo no; fi)"
    printf 'npm_available=%s\n' "$(if need npm; then echo yes; else echo no; fi)"
    printf 'codex_available=%s\n' "$(if need codex; then echo yes; else echo no; fi)"
    printf 'gh_available=%s\n' "$(if need gh; then echo yes; else echo no; fi)"
    printf 'package_manager=%s\n' "$(detected_package_manager)"
  } >"$INSTALL_CONTEXT_FILE" || true
}

prompt_from_terminal() {
  local variable_name="$1"
  local prompt="$2"
  local unavailable_message="${3:-This step needs an interactive terminal. Rerun the installer from a terminal or pass --no-onboard.}"
  local answer
  if ! terminal_available; then
    echo "$unavailable_message" >&2
    exit 2
  fi
  read -r -p "$prompt" answer </dev/tty
  printf -v "$variable_name" '%s' "$answer"
}

need() {
  command -v "$1" >/dev/null 2>&1
}

absolutize_path() {
  case "$1" in
  /*) printf '%s\n' "$1" ;;
  *) printf '%s/%s\n' "$PWD" "$1" ;;
  esac
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

has_space_or_control() {
  local value="$1"
  case "$value" in
  *" "* | *$'\t'* | *$'\n'* | *$'\r'* | *$'\v'* | *$'\f'*) return 0 ;;
  esac
  [[ "$value" != "${value//[$'\001'-$'\037'$'\177']/}" ]]
}

has_control() {
  local value="$1"
  [[ "$value" != "${value//[$'\001'-$'\037'$'\177']/}" ]]
}

has_space() {
  [[ "$1" == *" "* ]]
}

is_local_git_repo() {
  local value="$1"
  [[ -d "$value" && (-e "$value/.git" || (-f "$value/HEAD" && -d "$value/objects")) ]]
}

validate_repo_source() {
  local value="$1"
  if is_blank "$value"; then
    echo "--repo must not be blank." >&2
    exit 2
  fi
  if [[ "$value" == -* ]]; then
    echo "Missing value for --repo" >&2
    exit 2
  fi
  if has_control "$value"; then
    echo "--repo must be a URL or local Git path without whitespace or control characters." >&2
    exit 2
  fi
  if is_local_git_repo "$value"; then
    return
  fi
  if has_space "$value"; then
    echo "--repo must be a URL or local Git path without whitespace or control characters." >&2
    exit 2
  fi
  case "$value" in
  *://* | *@*:*) return ;;
  esac
  echo "--repo must be a URL or existing local Git repository path." >&2
  exit 2
}

validate_ref_source() {
  local value="$1"
  if is_blank "$value"; then
    echo "--ref must not be blank." >&2
    exit 2
  fi
  if [[ "$value" == -* ]]; then
    echo "Missing value for --ref" >&2
    exit 2
  fi
  if has_space_or_control "$value"; then
    echo "--ref must not contain whitespace or control characters." >&2
    exit 2
  fi
  if [[ "$value" == refs/* || "$value" == origin/* || "$value" == /* || "$value" == */ || "$value" == *..* || "$value" == *//* || "$value" == *./* || "$value" == *. || "$value" == .* || "$value" == */.* || "$value" == *.lock || "$value" == *.lock/* || "$value" == *"@{"* ]]; then
    echo "--ref must be a branch, tag, or commit without Git namespace prefixes or path traversal." >&2
    exit 2
  fi
  if [[ ! "$value" =~ ^[A-Za-z0-9][A-Za-z0-9._/+,%=@-]*$ ]]; then
    echo "--ref contains unsupported characters." >&2
    exit 2
  fi
}

validate_source_inputs() {
  validate_repo_source "$REPO_URL"
  validate_ref_source "$REF"
}

display_repo_source() {
  local value="$1"
  local scheme rest userinfo
  if [[ "$value" == *://* ]]; then
    scheme="${value%%://*}"
    rest="${value#*://}"
    userinfo="${rest%%@*}"
    if [[ "$rest" == *@* && "$userinfo" != */* ]]; then
      printf '%s://<redacted>@%s\n' "$scheme" "${rest#*@}"
      return
    fi
  fi
  printf '%s\n' "$value"
}

remote_has_branch() {
  git ls-remote --exit-code --heads "$1" "$2" >/dev/null 2>&1
}

checkout_ref() {
  local app_dir="$1"
  local ref="$2"
  if [[ "$DRY_RUN" == true ]]; then
    run git -C "$app_dir" checkout "$ref"
    return
  fi
  if git -C "$app_dir" show-ref --verify --quiet "refs/remotes/origin/$ref"; then
    run git -C "$app_dir" checkout -B "$ref" "origin/$ref"
    run git -C "$app_dir" pull --ff-only origin "$ref"
  else
    run git -C "$app_dir" checkout --detach "$ref"
  fi
}

assert_existing_checkout_safe() {
  if [[ -f "$APP_DIR/.symphony-trello-install" ]]; then
    return
  fi
  local origin_url display_repo
  origin_url="$(git -C "$APP_DIR" remote get-url origin 2>/dev/null || true)"
  if [[ "$origin_url" == "$REPO_URL" ]]; then
    return
  fi
  display_repo="$(display_repo_source "$REPO_URL")"
  echo "Refusing to update existing Git checkout without Symphony installer marker: $APP_DIR" >&2
  echo "Use an empty --prefix path, or pass a checkout whose origin remote is $display_repo." >&2
  exit 2
}

ensure_checkout_origin() {
  local origin_url display_repo
  origin_url="$(git -C "$APP_DIR" remote get-url origin 2>/dev/null || true)"
  if [[ "$origin_url" == "$REPO_URL" ]]; then
    return
  fi
  display_repo="$(display_repo_source "$REPO_URL")"
  if [[ -z "$origin_url" ]]; then
    run_with_label "git -C $APP_DIR remote add origin $display_repo" git -C "$APP_DIR" remote add origin "$REPO_URL"
  else
    run_with_label "git -C $APP_DIR remote set-url origin $display_repo" git -C "$APP_DIR" remote set-url origin "$REPO_URL"
  fi
}

install_or_update_checkout() {
  if [[ ! -d "$APP_DIR/.git" ]]; then
    run mkdir -p "$(dirname "$APP_DIR")"
    if [[ "$DRY_RUN" == false ]] && remote_has_branch "$REPO_URL" "$REF"; then
      run_with_label "git clone --branch $REF $(display_repo_source "$REPO_URL") $APP_DIR" git clone --branch "$REF" "$REPO_URL" "$APP_DIR"
    else
      run_with_label "git clone $(display_repo_source "$REPO_URL") $APP_DIR" git clone "$REPO_URL" "$APP_DIR"
      run git -C "$APP_DIR" fetch --tags --prune origin
      checkout_ref "$APP_DIR" "$REF"
    fi
  else
    assert_existing_checkout_safe
    ensure_checkout_origin
    run git -C "$APP_DIR" fetch --tags --prune origin
    checkout_ref "$APP_DIR" "$REF"
  fi
}

shell_literal() {
  local value="$1"
  value="${value//\'/\'\\\'\'}"
  printf "'%s'" "$value"
}

path_contains() {
  local search_path="${2:-$PATH}"
  case ":$search_path:" in
  *":$1:"*) return 0 ;;
  *) return 1 ;;
  esac
}

path_setup_line() {
  local current_path
  current_path="\"\$PATH\""
  printf 'export PATH=%s:%s\n' "$(shell_literal "$BIN_DIR")" "$current_path"
}

likely_shell_profiles() {
  local shell_name
  shell_name="${SHELL##*/}"
  case "$shell_name" in
  bash)
    local login_profile candidate
    printf '%s/.bashrc\n' "$HOME"
    for candidate in "$HOME/.bash_profile" "$HOME/.bash_login" "$HOME/.profile"; do
      if [[ -e "$candidate" ]]; then
        login_profile="$candidate"
        break
      fi
    done
    login_profile="${login_profile:-$HOME/.profile}"
    if [[ "$login_profile" != "$HOME/.bashrc" ]]; then
      printf '%s\n' "$login_profile"
    fi
    ;;
  zsh) printf '%s/.zshrc\n' "$HOME" ;;
  sh | dash | ksh) printf '%s/.profile\n' "$HOME" ;;
  *) return 1 ;;
  esac
}

print_path_setup_instructions() {
  local profile profiles=()
  echo "  NOTE  $BIN_DIR is not on PATH for this shell."
  echo "        Add this line to a shell profile file so future shells can run symphony-trello:"
  echo "        $(path_setup_line)"
  if [[ -n "${HOME:-}" ]]; then
    while IFS= read -r profile; do
      profiles+=("$profile")
    done < <(likely_shell_profiles 2>/dev/null || true)
  fi
  if [[ "${#profiles[@]}" -gt 0 ]]; then
    echo "        Suggested profile files:"
    printf '        %s\n' "${profiles[@]}"
  elif [[ -n "${HOME:-}" ]]; then
    echo "        Profile file candidates:"
    echo "        $HOME/.bashrc"
    echo "        $HOME/.zshrc"
    echo "        $HOME/.profile"
  else
    echo "        The installer could not determine HOME, so it did not choose a profile file."
  fi
}

append_path_setup_to_profile() {
  local line profile profiles=()
  if [[ -n "${HOME:-}" ]]; then
    while IFS= read -r profile; do
      profiles+=("$profile")
    done < <(likely_shell_profiles 2>/dev/null || true)
  fi
  if [[ -z "${HOME:-}" ]] || [[ "${#profiles[@]}" -eq 0 ]]; then
    echo "  NOTE  Could not safely choose a shell profile file."
    print_path_setup_instructions
    return
  fi
  line="$(path_setup_line)"
  for profile in "${profiles[@]}"; do
    if [[ -f "$profile" ]] && grep -Fqx "$line" "$profile"; then
      echo "  OK  PATH setup already exists in $profile"
      continue
    fi
    if [[ "$DRY_RUN" == true ]]; then
      echo "  WOULD add $BIN_DIR to PATH in $profile"
      echo "        Line: $line"
      continue
    fi
    if ! mkdir -p "$(dirname "$profile")"; then
      echo "  NOTE  Could not update PATH in $profile."
      print_path_setup_instructions
      continue
    fi
    if {
      printf '\n'
      printf '# Symphony for Trello\n'
      printf '%s\n' "$line"
    } >>"$profile"; then
      echo "  OK  Added $BIN_DIR to PATH in $profile"
    else
      echo "  NOTE  Could not update PATH in $profile."
      print_path_setup_instructions
    fi
  done
}

offer_path_setup() {
  if path_contains "$BIN_DIR" "$ORIGINAL_PATH"; then
    return
  fi
  if [[ "$SKIP_PATH_SETUP" == true ]]; then
    print_path_setup_instructions
    return
  fi
  echo
  echo "Command PATH setup"
  if [[ "$DRY_RUN" == true ]]; then
    echo "Symphony would install the command here:"
  else
    echo "Symphony installed the command here:"
  fi
  echo "  $BIN_DIR/symphony-trello"
  append_path_setup_to_profile
}

activate_managed_codex_path() {
  local codex_npm_bin="$CODEX_NPM_PREFIX/bin"
  if [[ ! -x "$BIN_DIR/codex" && ! -x "$codex_npm_bin/codex" ]]; then
    return
  fi
  path_contains "$codex_npm_bin" || export PATH="$codex_npm_bin:$PATH"
  path_contains "$BIN_DIR" || export PATH="$BIN_DIR:$PATH"
}

has_managed_pid_files() {
  [[ -d "$STATE_HOME" ]] && find "$STATE_HOME" -maxdepth 1 -type f -name '*.pid' -print -quit | grep -q .
}

pid_command_line() {
  local pid="$1"
  if [[ -r "/proc/$pid/cmdline" ]]; then
    tr '\0' ' ' <"/proc/$pid/cmdline"
  else
    ps -ww -p "$pid" -o command= 2>/dev/null || true
  fi
}

is_live_managed_pid() {
  local pid="$1"
  local command_line marker jar normalized_app_dir
  if [[ ! "$pid" =~ ^[0-9]+$ || "$pid" =~ ^0+$ ]] || ! kill -0 "$pid" >/dev/null 2>&1; then
    return 1
  fi
  normalized_app_dir="$(normalize_path "$APP_DIR")"
  marker="-Dsymphony.trello.managed.app_home=$normalized_app_dir"
  jar="$normalized_app_dir/target/quarkus-app/quarkus-run.jar"
  command_line="$(pid_command_line "$pid")"
  [[ "$command_line" == *"$marker"* && "$command_line" == *"$jar"* ]]
}

has_live_managed_pid_files() {
  local pid_file pid
  if [[ ! -d "$STATE_HOME" ]]; then
    return 1
  fi
  while IFS= read -r -d '' pid_file; do
    pid="$(tr -d '[:space:]' <"$pid_file" 2>/dev/null || true)"
    if is_live_managed_pid "$pid"; then
      return 0
    fi
  done < <(find "$STATE_HOME" -maxdepth 1 -type f -name '*.pid' -print0)
  return 1
}

remove_stale_managed_pid_files() {
  local pid_file pid
  if [[ ! -d "$STATE_HOME" ]]; then
    return
  fi
  while IFS= read -r -d '' pid_file; do
    pid="$(tr -d '[:space:]' <"$pid_file" 2>/dev/null || true)"
    if ! is_live_managed_pid "$pid"; then
      rm -f "$pid_file"
    fi
  done < <(find "$STATE_HOME" -maxdepth 1 -type f -name '*.pid' -print0)
}

platform_name() {
  local os arch distro
  os="${SYMPHONY_TRELLO_TEST_OS:-$(uname -s)}"
  arch="${SYMPHONY_TRELLO_TEST_ARCH:-$(uname -m)}"
  case "$arch" in
  x86_64 | amd64) arch="amd64" ;;
  arm64 | aarch64) arch="arm64" ;;
  esac
  case "$os" in
  Darwin) printf 'macOS %s\n' "$arch" ;;
  Linux)
    distro="Linux"
    if [[ -r /etc/os-release ]]; then
      # shellcheck disable=SC1091
      . /etc/os-release
      distro="${PRETTY_NAME:-${NAME:-Linux}}"
    fi
    printf '%s %s\n' "$distro" "$arch"
    ;;
  *) printf '%s %s\n' "$os" "$arch" ;;
  esac
}

detect_supported_platform() {
  OS_NAME="${SYMPHONY_TRELLO_TEST_OS:-$(uname -s)}"
  OS_ARCH="${SYMPHONY_TRELLO_TEST_ARCH:-$(uname -m)}"
  case "$OS_ARCH" in
  x86_64 | amd64) OS_ARCH="amd64" ;;
  arm64 | aarch64) OS_ARCH="arm64" ;;
  esac
  case "$OS_NAME:$OS_ARCH" in
  Darwin:amd64 | Darwin:arm64 | Linux:amd64 | Linux:arm64) return 0 ;;
  *)
    echo "Unsupported platform: $OS_NAME $OS_ARCH" >&2
    echo "Supported platforms: macOS arm64/amd64, Linux arm64/amd64, and WSL2 through the Linux path." >&2
    exit 2
    ;;
  esac
}

prompt_yes_no() {
  local variable_name="$1"
  local prompt="$2"
  local unavailable_message="${3:-}"
  local prompt_answer
  if [[ -n "$unavailable_message" ]]; then
    prompt_from_terminal prompt_answer "$prompt" "$unavailable_message"
  else
    prompt_from_terminal prompt_answer "$prompt"
  fi
  case "$prompt_answer" in
  [yY]*) printf -v "$variable_name" true ;;
  *) printf -v "$variable_name" false ;;
  esac
}

detected_package_manager() {
  if [[ "$OS_NAME" == "Darwin" ]] && need brew; then
    echo brew
  elif need apt-get; then
    echo apt-get
  elif need dnf; then
    echo dnf
  elif need yum; then
    echo yum
  elif need pacman; then
    echo pacman
  elif need zypper; then
    echo zypper
  else
    echo none
  fi
}

effective_uid() {
  printf '%s\n' "${SYMPHONY_TRELLO_TEST_EUID:-$EUID}"
}

privileged_command_prefix() {
  if [[ "$(effective_uid)" == "0" ]]; then
    printf ''
    return 0
  fi
  if need sudo; then
    printf 'sudo '
    return 0
  fi
  if need doas; then
    printf 'doas '
    return 0
  fi
  return 1
}

package_install_root_command() {
  local package="$1"
  if [[ "$OS_NAME" == "Darwin" ]]; then
    if need brew; then
      case "$package" in
      git) printf 'brew install git\n' ;;
      java) printf 'brew install openjdk@25\n' ;;
      node) printf 'brew install node\n' ;;
      gh) printf 'brew install gh\n' ;;
      esac
    fi
    return
  fi
  if need apt-get; then
    local apt_prefix=""
    if [[ "$APT_UPDATED" == false ]]; then
      apt_prefix="apt-get update && "
    fi
    case "$package" in
    git) printf '%sapt-get install -y git\n' "$apt_prefix" ;;
    java) printf '%sapt-get install -y openjdk-25-jdk\n' "$apt_prefix" ;;
    node) printf '%sapt-get install -y nodejs npm\n' "$apt_prefix" ;;
    gh) printf '%sapt-get install -y gh\n' "$apt_prefix" ;;
    esac
  elif need dnf; then
    case "$package" in
    git) printf 'dnf install -y git\n' ;;
    java) printf 'dnf install -y java-25-openjdk-devel\n' ;;
    node) printf 'dnf install -y nodejs npm\n' ;;
    gh) printf 'dnf install -y gh\n' ;;
    esac
  elif need yum; then
    case "$package" in
    git) printf 'yum install -y git\n' ;;
    java) printf 'yum install -y java-25-openjdk-devel\n' ;;
    node) printf 'yum install -y nodejs npm\n' ;;
    gh) printf 'yum install -y gh\n' ;;
    esac
  elif need pacman; then
    case "$package" in
    git) printf 'pacman -S --needed git\n' ;;
    java) printf 'pacman -S --needed jdk-openjdk\n' ;;
    node) printf 'pacman -S --needed nodejs npm\n' ;;
    gh) printf 'pacman -S --needed github-cli\n' ;;
    esac
  elif need zypper; then
    case "$package" in
    git) printf 'zypper install -y git\n' ;;
    java) printf 'zypper install -y java-25-openjdk-devel\n' ;;
    node) printf 'zypper install -y nodejs npm\n' ;;
    gh) printf 'zypper install -y gh\n' ;;
    esac
  fi
}

package_install_command() {
  local package="$1"
  local command root_prefix
  command="$(package_install_root_command "$package")"
  [[ -z "$command" ]] && return
  if [[ "$OS_NAME" == "Darwin" ]]; then
    printf '%s\n' "$command"
    return
  fi
  root_prefix="$(privileged_command_prefix)" || return
  if [[ -z "$root_prefix" ]]; then
    printf '%s\n' "$command"
    return
  fi
  local prefixed=""
  while [[ "$command" == *" && "* ]]; do
    prefixed+="${root_prefix}${command%% && *} && "
    command="${command#* && }"
  done
  prefixed+="${root_prefix}${command}"
  printf '%s\n' "$prefixed"
}

activate_brew_openjdk() {
  if [[ "$OS_NAME" != "Darwin" ]] || ! need brew; then
    return
  fi
  local prefix
  prefix="$(brew --prefix openjdk@25 2>/dev/null || true)"
  if [[ -n "$prefix" && -x "$prefix/bin/java" ]]; then
    BREW_OPENJDK_BIN="$prefix/bin"
    case ":$PATH:" in
    *":$BREW_OPENJDK_BIN:"*) ;;
    *) export PATH="$BREW_OPENJDK_BIN:$PATH" ;;
    esac
  fi
}

run_shell_command() {
  local command="$1"
  printf '  RUN  %s\n' "$command"
  if [[ "$DRY_RUN" == false ]]; then
    bash -c "$command"
  fi
}

mark_package_command_completed() {
  local command="$1"
  if [[ "$command" == *"apt-get update"* ]]; then
    APT_UPDATED=true
  fi
}

install_package_or_exit() {
  local label="$1"
  local package="$2"
  local fallback="$3"
  local command root_command answer
  command="$(package_install_command "$package" || true)"
  if [[ -z "$command" ]]; then
    root_command="$(package_install_root_command "$package")"
    if [[ -n "$root_command" && "$(effective_uid)" != "0" ]]; then
      echo
      echo "$label is missing."
      echo "Automatic install requires root, sudo, or doas."
      echo "Run this command as root:"
      echo "  $root_command"
      echo "Then rerun this installer."
      exit 2
    fi
    echo "$label is required, but this installer does not know a package-manager command for this platform."
    echo "$fallback"
    echo "Then rerun this installer."
    exit 2
  fi
  echo
  echo "$label is missing."
  echo "Proposed install command:"
  echo "  $command"
  prompt_yes_no \
    answer \
    "Run this command now? [y/N] " \
    "This step needs an interactive terminal. Rerun the installer from a terminal or install the missing prerequisite manually first."
  if [[ "$answer" != true ]]; then
    echo "$fallback"
    echo "Then rerun this installer."
    exit 2
  fi
  run_shell_command "$command"
  mark_package_command_completed "$command"
}

codex_authenticated() {
  need codex && codex login status >/dev/null 2>&1
}

install_codex_with_user_local_npm() {
  if ! need npm; then
    local node_command
    node_command="$(package_install_command node || true)"
    [[ -z "$node_command" ]] && explain_manual_node_install_and_exit
    run_shell_command "$node_command"
    mark_package_command_completed "$node_command"
    if ! need npm; then
      echo "Node.js/npm was installed, but npm is not available in this shell yet." >&2
      echo "Open a new terminal with npm on PATH, then rerun this installer." >&2
      exit 2
    fi
  fi
  run mkdir -p "$CODEX_NPM_PREFIX" "$BIN_DIR"
  run_shell_command "$(codex_npm_install_command)"
  run ln -sf "$CODEX_NPM_PREFIX/bin/codex" "$BIN_DIR/codex"
  export PATH="$BIN_DIR:$CODEX_NPM_PREFIX/bin:$PATH"
}

codex_install_plan() {
  if need npm; then
    printf 'Symphony-managed npm at %s (Node.js/npm installed: yes)\n' "$CODEX_NPM_PREFIX"
  else
    printf 'Symphony-managed npm at %s (Node.js/npm installed: no)\n' "$CODEX_NPM_PREFIX"
  fi
}

codex_npm_install_command() {
  printf 'npm install --global --prefix %s @openai/codex\n' "$(shell_literal "$CODEX_NPM_PREFIX")"
}

print_codex_npm_install_plan() {
  echo "Install Codex CLI with Symphony-managed npm."
  echo "  Install location: $CODEX_NPM_PREFIX"
  echo "  Command link: $BIN_DIR/codex"
  echo "This keeps system-wide npm packages unchanged."
  if need npm; then
    :
  else
    local node_command
    node_command="$(package_install_command node || true)"
    [[ -z "$node_command" ]] && explain_manual_node_install_and_exit
    echo "  Node.js/npm install: $node_command"
  fi
  echo "  Codex CLI install: $(codex_npm_install_command)"
}

explain_manual_node_install_and_exit() {
  local root_command
  root_command="$(package_install_root_command node)"
  echo "Automatic Node.js/npm install requires root, sudo, or doas."
  if [[ -n "$root_command" && "$(effective_uid)" != "0" ]]; then
    echo "Run this command as root:"
    echo "  $root_command"
  else
    echo "Install Node.js with npm from https://nodejs.org/ or your OS package manager."
  fi
  echo "Then rerun this installer."
  exit 2
}

install_codex_or_exit() {
  local answer
  if codex_authenticated; then
    return
  fi
  if need codex; then
    return
  fi
  echo
  if need npm; then
    echo "Codex CLI is missing."
  else
    echo "Codex CLI is missing and needs Node.js with npm."
  fi
  print_codex_npm_install_plan
  prompt_yes_no answer "Run now? [y/N] "
  if [[ "$answer" != true ]]; then
    echo "Install Codex CLI, then rerun this installer."
    echo "The installer will help you log in after it finds Codex CLI."
    exit 2
  fi
  install_codex_with_user_local_npm
  if ! need codex; then
    echo "Codex CLI was installed, but codex is not available in this shell yet." >&2
    echo "Add $BIN_DIR or $CODEX_NPM_PREFIX/bin to PATH, then rerun this installer." >&2
    exit 2
  fi
}

ensure_prerequisites() {
  if ! need git; then
    install_package_or_exit "Git" "git" "Install Git from https://git-scm.com/downloads or your OS package manager."
  fi
  if ! jdk_compatible; then
    install_package_or_exit "Java 25+ JDK" "java" "Install a Java 25 or newer JDK with javac, then make java and javac available on PATH."
    activate_brew_openjdk
    if ! jdk_compatible; then
      echo "Java was installed, but java and javac are not available in this shell yet." >&2
      if [[ -n "$BREW_OPENJDK_BIN" ]]; then
        echo "Add $BREW_OPENJDK_BIN to PATH, then rerun this installer." >&2
      else
        echo "Open a new terminal with Java 25+ on PATH, then rerun this installer." >&2
      fi
      exit 2
    fi
  fi
  if [[ "$NO_ONBOARD" == false ]]; then
    install_codex_or_exit
  fi
}

print_dry_run_package_offer() {
  local label="$1"
  local package="$2"
  local command
  command="$(package_install_command "$package" || true)"
  echo "  WOULD offer to install $label${command:+ with: $command}"
  mark_package_command_completed "$command"
}

print_dry_run_codex_plan() {
  local npm_status
  if need npm; then
    npm_status="yes"
  else
    npm_status="no"
  fi
  echo "  WOULD offer to install Codex CLI with Symphony-managed npm:"
  echo "          Install location: $CODEX_NPM_PREFIX"
  echo "          Command link: $BIN_DIR/codex"
  echo "          Node.js/npm installed: $npm_status"
}

print_dry_run_prerequisite_plan() {
  if ! need git; then
    print_dry_run_package_offer "Git" git
  fi
  if ! jdk_compatible; then
    print_dry_run_package_offer "Java 25+ JDK" java
  fi
  if [[ "$NO_ONBOARD" == false ]] && ! need codex; then
    print_dry_run_codex_plan
  elif [[ "$NO_ONBOARD" == true ]] && ! need codex; then
    echo "  NOTE   Codex CLI setup is skipped because --no-onboard was passed."
  fi
}

jdk_compatible() {
  if ! need java || ! need javac; then
    return 1
  fi
  local java_version javac_version
  java_version="$(java -version 2>&1 | sed -n 's/.*version "\([0-9][0-9]*\).*/\1/p' | head -1)"
  javac_version="$(javac -version 2>&1 | sed -n 's/javac \([0-9][0-9]*\).*/\1/p' | head -1)"
  [[ -n "$java_version" && -n "$javac_version" && "$java_version" -ge 25 && "$javac_version" -ge 25 ]]
}

validate_source_inputs
SYMPHONY_HOME="$(absolutize_path "$SYMPHONY_HOME")"
APP_DIR="$(absolutize_path "$APP_DIR")"
CONFIG_DIR="$(absolutize_path "$CONFIG_DIR")"
WORKSPACE_ROOT="$(absolutize_path "$WORKSPACE_ROOT")"
STATE_HOME="$(absolutize_path "$STATE_HOME")"
BIN_DIR="$(absolutize_path "$BIN_DIR")"
CODEX_NPM_PREFIX="$(absolutize_path "$SYMPHONY_HOME/npm")"
INSTALL_CONTEXT_FILE="$STATE_HOME/install-context.properties"
activate_managed_codex_path
detect_supported_platform
activate_brew_openjdk

echo "Symphony for Trello installer"
echo
echo "Detected $(platform_name)"
echo "Install: $APP_DIR"
echo "Config: $CONFIG_DIR"
echo "Workspaces: $WORKSPACE_ROOT"
echo "State/logs: $STATE_HOME"
echo "Command: $BIN_DIR/symphony-trello"
echo "Repository: $(display_repo_source "$REPO_URL")"
echo "Ref: $REF"
echo
echo "Checking prerequisites..."
if need git; then echo "  OK      Git available"; else echo "  NEEDED  Git"; fi
if jdk_compatible; then echo "  OK      Java 25+ JDK available"; else echo "  NEEDED  Java 25+ JDK"; fi
if need codex; then
  echo "  OK      Codex CLI available"
elif [[ "$NO_ONBOARD" == true ]]; then
  echo "  NEEDED  Codex CLI (only needed for guided setup; skipped by --no-onboard)"
else
  echo "  NEEDED  Codex CLI"
fi

if [[ "$DRY_RUN" == true ]]; then
  echo
  echo "Dry run: no files changed."
  print_dry_run_prerequisite_plan
  echo "  WOULD clone or update: $APP_DIR"
  echo "  WOULD build packaged Quarkus app with Maven wrapper"
  echo "  WOULD install command: $BIN_DIR/symphony-trello"
  offer_path_setup
  if [[ "$NO_ONBOARD" == false ]]; then
    echo "  WOULD run guided setup and start Symphony automatically."
  fi
  exit 0
fi

ensure_prerequisites

if [[ "$NO_ONBOARD" == false ]] && ! codex login status >/dev/null 2>&1; then
  browser=""
  login_command=""
  echo
  echo "Codex CLI is installed but not logged in."
  prompt_from_terminal browser "Can this machine open a browser for Codex login? [Y/n] "
  case "$browser" in
  [nN]*)
    login_command="codex login --device-auth"
    if ! run_interactive codex login --device-auth; then
      echo "Codex login did not complete successfully." >&2
      echo "Run \`$login_command\`, then rerun this installer." >&2
      exit 2
    fi
    ;;
  *)
    login_command="codex login"
    if ! run_interactive codex login; then
      echo "Codex login did not complete successfully." >&2
      echo "Run \`$login_command\`, then rerun this installer." >&2
      exit 2
    fi
    ;;
  esac
  if ! codex login status >/dev/null 2>&1; then
    echo "Codex login did not complete successfully." >&2
    echo "Run \`$login_command\`, then rerun this installer." >&2
    exit 2
  fi
fi

echo
echo "Installing Symphony..."
UPDATING_EXISTING_CHECKOUT=false
if [[ -d "$APP_DIR/.git" ]]; then
  UPDATING_EXISTING_CHECKOUT=true
fi
RESTART_MANAGED_WORKERS=false
if [[ "$UPDATING_EXISTING_CHECKOUT" == true ]] && has_managed_pid_files; then
  if [[ -x "$BIN_DIR/symphony-trello" ]]; then
    RESTART_MANAGED_WORKERS=true
    echo "Stopping managed workers before update..."
    run "$BIN_DIR/symphony-trello" stop
  elif has_live_managed_pid_files; then
    echo "Cannot stop managed workers because the installed command is missing: $BIN_DIR/symphony-trello" >&2
    echo "Stop the running Symphony worker processes manually, then rerun the installer." >&2
    exit 2
  else
    echo "Removing stale managed worker pid files before update..."
    if [[ "$DRY_RUN" == false ]]; then
      remove_stale_managed_pid_files
    fi
  fi
fi
install_or_update_checkout
run "$APP_DIR/mvnw" -q -f "$APP_DIR/pom.xml" -DskipTests clean package
run mkdir -p "$BIN_DIR" "$CONFIG_DIR" "$WORKSPACE_ROOT" "$STATE_HOME"
if [[ "$DRY_RUN" == false ]]; then
  APP_DIR_LITERAL="$(shell_literal "$APP_DIR")"
  CONFIG_DIR_LITERAL="$(shell_literal "$CONFIG_DIR")"
  WORKSPACE_ROOT_LITERAL="$(shell_literal "$WORKSPACE_ROOT")"
  STATE_HOME_LITERAL="$(shell_literal "$STATE_HOME")"
  COMMAND_LITERAL="$(shell_literal "$BIN_DIR/symphony-trello")"
  BREW_OPENJDK_BIN_LITERAL="$(shell_literal "$BREW_OPENJDK_BIN")"
  CODEX_NPM_BIN_DIR_LITERAL="$(shell_literal "$CODEX_NPM_PREFIX/bin")"
  BIN_DIR_LITERAL="$(shell_literal "$BIN_DIR")"
  printf 'symphony-trello installer-managed app directory\n' >"$APP_DIR/.symphony-trello-install"
  cat >"$BIN_DIR/symphony-trello" <<EOF
#!/usr/bin/env bash
set -euo pipefail
APP_HOME=$APP_DIR_LITERAL
BIN_DIR=$BIN_DIR_LITERAL
CONFIG_DIR="\${SYMPHONY_TRELLO_CONFIG_DIR:-}"
WORKSPACE_ROOT="\${SYMPHONY_TRELLO_WORKSPACE_ROOT:-}"
STATE_HOME="\${SYMPHONY_TRELLO_STATE_HOME:-}"
WORKSPACE_ROOT_FROM_ENV=false
STATE_HOME_FROM_ENV=false
if [[ -n "\$WORKSPACE_ROOT" ]]; then WORKSPACE_ROOT_FROM_ENV=true; fi
if [[ -n "\$STATE_HOME" ]]; then STATE_HOME_FROM_ENV=true; fi
if [[ -z "\$CONFIG_DIR" ]]; then CONFIG_DIR=$CONFIG_DIR_LITERAL; fi
if [[ -z "\$WORKSPACE_ROOT" ]]; then WORKSPACE_ROOT=$WORKSPACE_ROOT_LITERAL; fi
if [[ -z "\$STATE_HOME" ]]; then STATE_HOME=$STATE_HOME_LITERAL; fi
BREW_OPENJDK_BIN=$BREW_OPENJDK_BIN_LITERAL
CODEX_NPM_BIN_DIR=$CODEX_NPM_BIN_DIR_LITERAL
if [[ -n "\$BREW_OPENJDK_BIN" && -d "\$BREW_OPENJDK_BIN" ]]; then export PATH="\$BREW_OPENJDK_BIN:\$PATH"; fi
if [[ -d "\$CODEX_NPM_BIN_DIR" ]]; then export PATH="\$CODEX_NPM_BIN_DIR:\$PATH"; fi
export PATH="\$BIN_DIR:\$PATH"
mkdir -p "\$STATE_HOME"
absolutize_path() {
  case "\$1" in
    /*) printf '%s\n' "\$1" ;;
    *) printf '%s/%s\n' "\$PWD" "\$1" ;;
  esac
}
normalize_path() {
  local path="\$1"
  local -a parts=()
  local -a normalized=()
  local part
  path="\$(absolutize_path "\$path")"
  path="\${path#/}"
  IFS=/ read -r -a parts <<<"\$path"
  for part in "\${parts[@]}"; do
    case "\$part" in
    "" | .) ;;
    ..)
      if (("\${#normalized[@]}" > 0)); then
        unset "normalized[\$(("\${#normalized[@]}" - 1))]"
      fi
      ;;
    *) normalized+=("\$part") ;;
    esac
  done
  if (("\${#normalized[@]}" == 0)); then
    printf '/\n'
  else
    local IFS=/
    printf '/%s\n' "\${normalized[*]}"
  fi
}
has_cli_option() {
  local option="\$1"
  shift
  local value
  for value in "\$@"; do
    [[ "\$value" == "\$option" || "\$value" == "\$option="* ]] && return 0
  done
  return 1
}
cli_option_value() {
  local option="\$1"
  shift
  local value
  while [[ "\$#" -gt 0 ]]; do
    value="\$1"
    if [[ "\$value" == "\$option" ]]; then
      if [[ "\$#" -gt 1 ]]; then
        printf '%s\n' "\$2"
        return 0
      fi
      return 1
    fi
    if [[ "\$value" == "\$option="* ]]; then
      printf '%s\n' "\${value#"\$option="}"
      return 0
    fi
    shift
  done
  return 1
}
setup_local_lifecycle_subcommand() {
  local value
  while [[ "\$#" -gt 0 ]]; do
    value="\$1"
    shift
    case "\$value" in
    check | repair-port | configure-github) return 0 ;;
    --key | --token | --board-name | --board | --workspace-id | --active | --terminal | --in-progress | --blocked | --workflow | --workspace-root | --config-dir | --manifest | --server-port | --max-agents | --codex-model | --codex-reasoning-effort | --env | --add-path | --endpoint)
      [[ "\$#" -gt 0 ]] && shift
      ;;
    --key=* | --token=* | --board-name=* | --board=* | --workspace-id=* | --active=* | --terminal=* | --in-progress=* | --blocked=* | --workflow=* | --workspace-root=* | --config-dir=* | --manifest=* | --server-port=* | --max-agents=* | --codex-model=* | --codex-reasoning-effort=* | --env=* | --add-path=* | --endpoint=*) ;;
    --*) ;;
    *) return 1 ;;
    esac
  done
  return 1
}
exec_setup_cli() {
  local command="\${1:-}"
  local -a args=("\$@")
  local -a defaults=()
  local classpath="\$APP_HOME/target/quarkus-app/quarkus-run.jar:\$APP_HOME/target/quarkus-app/app/*:\$APP_HOME/target/quarkus-app/lib/main/*:\$APP_HOME/target/quarkus-app/quarkus/*"
  local caller_dir="\$PWD"
  export SYMPHONY_TRELLO_APP_HOME="\$APP_HOME"
  export SYMPHONY_TRELLO_COMMAND=$COMMAND_LITERAL
  export SYMPHONY_TRELLO_CONFIG_DIR="\$CONFIG_DIR"
  export SYMPHONY_TRELLO_WORKSPACE_ROOT="\$WORKSPACE_ROOT"
  export SYMPHONY_TRELLO_STATE_HOME="\$STATE_HOME"
  export SYMPHONY_TRELLO_CALLER_DIR="\$caller_dir"
  export SYMPHONY_TRELLO_DOTENV="\${SYMPHONY_TRELLO_DOTENV:-\$CONFIG_DIR/.env}"
  if [[ "\$command" == "setup-local" ]]; then
    local explicit_config_dir=false
    local explicit_config_dir_value=""
    local setup_local_lifecycle=false
    setup_local_lifecycle_subcommand "\${args[@]:1}" && setup_local_lifecycle=true
    if has_cli_option "--config-dir" "\${args[@]}"; then
      explicit_config_dir=true
      explicit_config_dir_value="\$(cli_option_value "--config-dir" "\${args[@]}" || true)"
    else
      defaults+=(--config-dir "\$CONFIG_DIR")
    fi
    if [[ "\$setup_local_lifecycle" == false && "\$explicit_config_dir" == false ]]; then
      has_cli_option "--workspace-root" "\${args[@]}" || defaults+=(--workspace-root "\$WORKSPACE_ROOT")
    elif [[ -n "\$explicit_config_dir_value" ]]; then
      local isolated_config_dir
      isolated_config_dir="\$(normalize_path "\$explicit_config_dir_value")"
      if [[ "\$setup_local_lifecycle" == false ]] && ! has_cli_option "--workspace-root" "\${args[@]}"; then
        if [[ "\$WORKSPACE_ROOT_FROM_ENV" == true ]]; then
          defaults+=(--workspace-root "\$WORKSPACE_ROOT")
        else
          defaults+=(--workspace-root "\$isolated_config_dir/workspaces")
        fi
      fi
      if [[ "\$STATE_HOME_FROM_ENV" == false ]]; then
        export SYMPHONY_TRELLO_STATE_HOME="\$(dirname "\$isolated_config_dir")/state"
      fi
    fi
  elif [[ "\$command" == "new-board" || "\$command" == "import-board" ]]; then
    has_cli_option "--workspace-root" "\${args[@]}" || defaults+=(--workspace-root "\$WORKSPACE_ROOT")
  elif [[ "\$command" == "start" || "\$command" == "stop" || "\$command" == "status" || "\$command" == "logs" || "\$command" == "diagnostics" ]]; then
    local explicit_config_dir=false
    local explicit_config_dir_value=""
    if has_cli_option "--config-dir" "\${args[@]}"; then
      explicit_config_dir=true
      explicit_config_dir_value="\$(cli_option_value "--config-dir" "\${args[@]}" || true)"
    else
      defaults+=(--config-dir "\$CONFIG_DIR")
    fi
    if [[ "\$explicit_config_dir" == false ]]; then
      has_cli_option "--workspace-root" "\${args[@]}" || defaults+=(--workspace-root "\$WORKSPACE_ROOT")
      has_cli_option "--state-home" "\${args[@]}" || defaults+=(--state-home "\$STATE_HOME")
    elif [[ -n "\$explicit_config_dir_value" ]]; then
      local isolated_config_dir
      isolated_config_dir="\$(normalize_path "\$explicit_config_dir_value")"
      if ! has_cli_option "--workspace-root" "\${args[@]}"; then
        if [[ "\$WORKSPACE_ROOT_FROM_ENV" == true ]]; then
          defaults+=(--workspace-root "\$WORKSPACE_ROOT")
        else
          defaults+=(--workspace-root "\$isolated_config_dir/workspaces")
        fi
      fi
      if ! has_cli_option "--state-home" "\${args[@]}"; then
        if [[ "\$STATE_HOME_FROM_ENV" == true ]]; then
          defaults+=(--state-home "\$STATE_HOME")
        else
          defaults+=(--state-home "\$(dirname "\$isolated_config_dir")/state")
        fi
      fi
    fi
    has_cli_option "--app-home" "\${args[@]}" || defaults+=(--app-home "\$APP_HOME")
  fi
  if [[ "\${#defaults[@]}" -gt 0 ]]; then
    exec java -Dsymphony.trello.app.home="\$APP_HOME" -Dsymphony.trello.config.dir="\$CONFIG_DIR" -Dsymphony.trello.shell=posix -Dsymphony.trello.command="\$SYMPHONY_TRELLO_COMMAND" -cp "\$classpath" ch.fmartin.symphony.trello.setup.TrelloBoardSetupMain "\$command" "\${defaults[@]}" "\${args[@]:1}"
  fi
  exec java -Dsymphony.trello.app.home="\$APP_HOME" -Dsymphony.trello.config.dir="\$CONFIG_DIR" -Dsymphony.trello.shell=posix -Dsymphony.trello.command="\$SYMPHONY_TRELLO_COMMAND" -cp "\$classpath" ch.fmartin.symphony.trello.setup.TrelloBoardSetupMain "\${args[@]}"
}
exec_setup_cli "\$@"
EOF
  chmod +x "$BIN_DIR/symphony-trello"
fi
echo "  OK  Command installed: $BIN_DIR/symphony-trello"

offer_path_setup

if [[ "$NO_ONBOARD" == false ]]; then
  echo
  if [[ "$RESTART_MANAGED_WORKERS" == true ]]; then
    echo "Restarting managed workers after update..."
  fi
  echo "Starting setup..."
  write_install_context
  run_interactive "$BIN_DIR/symphony-trello" setup-local
  if [[ "$RESTART_MANAGED_WORKERS" == true ]]; then
    run "$BIN_DIR/symphony-trello" start --all
  fi
elif [[ "$RESTART_MANAGED_WORKERS" == true ]]; then
  echo
  echo "Restarting managed workers after update..."
  write_install_context
  run "$BIN_DIR/symphony-trello" start --all
fi
