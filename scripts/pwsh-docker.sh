#!/usr/bin/env bash
set -euo pipefail

# Microsoft documents the .NET SDK images as the current PowerShell-in-Docker path.
# Keep this wrapper small so CI and local checks exercise the same PowerShell runtime.
IMAGE="${SYMPHONY_TRELLO_PWSH_DOCKER_IMAGE:-mcr.microsoft.com/dotnet/sdk:8.0@sha256:3c0edbfe1549dd93fb789dc96299a40df865ad7bffefcaf38e8c05940686d641}"
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
repo_root="$(cd -- "$script_dir/.." && pwd -P)"
container_runtime="${SYMPHONY_TRELLO_CONTAINER_RUNTIME:-docker}"

case "$container_runtime" in
docker | podman) ;;
*)
  printf 'SYMPHONY_TRELLO_CONTAINER_RUNTIME must be docker or podman\n' >&2
  exit 2
  ;;
esac
if ! command -v "$container_runtime" >/dev/null 2>&1; then
  printf '%s is required to run PowerShell through %s\n' "$container_runtime" "$IMAGE" >&2
  exit 127
fi

docker_environment=(
  -e HOME=/tmp
  -e PATH
  -e POWERSHELL_TELEMETRY_OPTOUT=1
)
allow_non_windows_test_runtime="${SYMPHONY_TRELLO_PWSH_ALLOW_NON_WINDOWS_TEST_RUNTIME:-1}"
if [[ "$allow_non_windows_test_runtime" == "1" ]]; then
  docker_environment+=(-e SYMPHONY_TRELLO_ALLOW_NON_WINDOWS_PWSH_FOR_TEST=1)
fi
while IFS='=' read -r name _; do
  case "$name" in
  SYMPHONY_TRELLO_ALLOW_NON_WINDOWS_PWSH_FOR_TEST)
    if [[ "$allow_non_windows_test_runtime" == "1" ]]; then
      docker_environment+=(-e "$name")
    fi
    ;;
  SYMPHONY_*) docker_environment+=(-e "$name") ;;
  esac
done < <(env)

docker_mounts=(
  -v "$repo_root:$repo_root"
  -v /tmp:/tmp
)
mount_unless_covered() {
  case "$1" in
  "$repo_root" | "$repo_root"/* | /tmp | /tmp/*) ;;
  *) docker_mounts+=(-v "$1:$1") ;;
  esac
}
mount_unless_covered "$PWD"

# Installer tests keep their files in the JVM temp dir, which the test fixture exports as TMPDIR.
temp_dir="${TMPDIR:-}"
temp_dir="${temp_dir%/}"
if [[ "$temp_dir" == /* && -d "$temp_dir" && "$temp_dir" != "$PWD" ]]; then
  mount_unless_covered "$temp_dir"
fi

# The podman-docker shim installs Podman as `docker`. Without keep-id, rootless Podman maps the
# host user to a subordinate UID that cannot read the caller's 0700 directories. The probe drops
# HOME because installer tests may point it at a missing directory, which Podman refuses.
runtime_is_podman() {
  local version
  [ "$container_runtime" = "podman" ] && return 0
  version="$(env -u HOME "$container_runtime" --version 2>/dev/null)" || return 1
  [[ "$version" == "podman version "* ]]
}

container_user_namespace=()
if runtime_is_podman; then
  container_user_namespace+=(--userns=keep-id)
fi

exec "$container_runtime" run --rm --security-opt label=disable \
  "${container_user_namespace[@]}" \
  --user "$(id -u):$(id -g)" \
  "${docker_environment[@]}" \
  "${docker_mounts[@]}" \
  -w "$PWD" \
  "$IMAGE" \
  pwsh "$@"
