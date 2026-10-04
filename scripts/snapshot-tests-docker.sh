#!/usr/bin/env bash
set -euo pipefail

# Runs the terminal-output snapshot tests in a Linux Java 25 container, so contributors on other
# platforms can verify the install.sh and pseudo-terminal baselines. Extra arguments go to Maven,
# for example -Dsymphony.snapshots.update=true to rewrite baselines after reviewing a change.
image="docker.io/library/maven:3.9.11-eclipse-temurin-25@sha256:407c4423cec0cf2981055bc2c6c0dc211d9605b6669279b95997f2d1c7e91e2c"
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
  printf '%s is required to run the snapshot tests in a container\n' "$container_runtime" >&2
  exit 127
fi

# Keep downloaded Maven and dependencies between runs, outside the checkout.
cache_dir="${XDG_CACHE_HOME:-$HOME/.cache}/symphony-trello/snapshot-tests"
mkdir -p "$cache_dir/home" "$cache_dir/m2"

docker_args=(
  run
  --rm
  --security-opt
  label=disable
  --user "$(id -u):$(id -g)"
  -e HOME=/cache/home
  -e MAVEN_CONFIG=/cache/home/.m2
  -e "MAVEN_OPTS=-Duser.home=/cache/home -Dmaven.repo.local=/cache/m2"
  -v "$cache_dir:/cache"
  -v "$repo_root:$repo_root"
  -w "$repo_root"
)

if [ "$container_runtime" = "podman" ]; then
  docker_args+=(--userns=keep-id)
fi

exec "$container_runtime" "${docker_args[@]}" "$image" \
  ./mvnw -q -Dtest='*SnapshotTest' -Dsurefire.failIfNoSpecifiedTests=false "$@" test
