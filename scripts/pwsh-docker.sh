#!/usr/bin/env bash
set -euo pipefail

# Microsoft documents the .NET SDK images as the current PowerShell-in-Docker path.
# Keep this wrapper small so CI and local checks exercise the same PowerShell runtime.
IMAGE="${SYMPHONY_TRELLO_PWSH_DOCKER_IMAGE:-mcr.microsoft.com/dotnet/sdk:8.0}"
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
case "$PWD" in
"$repo_root" | "$repo_root"/* | /tmp | /tmp/*) ;;
*) docker_mounts+=(-v "$PWD:$PWD") ;;
esac

container_user_namespace=()
if [ "$container_runtime" = "podman" ]; then
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
