#!/usr/bin/env bash
set -euo pipefail

image="ghcr.io/betterleaks/betterleaks:v1.4.1"
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
project_root="$(cd "$script_dir/.." && pwd -P)"
scan_root="$(git rev-parse --show-toplevel 2>/dev/null || pwd -P)"
work_dir="$(pwd -P)"
container_runtime="${SYMPHONY_TRELLO_CONTAINER_RUNTIME:-docker}"

case "$container_runtime" in
docker | podman) ;;
*)
  printf 'SYMPHONY_TRELLO_CONTAINER_RUNTIME must be docker or podman\n' >&2
  exit 2
  ;;
esac
if ! command -v "$container_runtime" >/dev/null 2>&1; then
  printf '%s is required to run BetterLeaks in a container\n' "$container_runtime" >&2
  exit 127
fi

docker_args=(
  run
  -i
  --rm
  --security-opt
  label=disable
  --workdir "$work_dir"
  -e GIT_CONFIG_COUNT=1
  -e GIT_CONFIG_KEY_0=safe.directory
  -e "GIT_CONFIG_VALUE_0=$scan_root"
  -v "$project_root:$project_root:ro"
)

if [ "$container_runtime" = "podman" ]; then
  docker_args+=(--userns=keep-id)
fi

if [[ "$scan_root" != "$project_root" ]]; then
  docker_args+=(-v "$scan_root:$scan_root:ro")
fi

exec "$container_runtime" "${docker_args[@]}" "$image" "$@"
