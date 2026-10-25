#!/usr/bin/env bash
set -euo pipefail

# Runs the terminal-output snapshot tests in a Linux Java 25 container, so contributors on other
# platforms can verify the install.sh and pseudo-terminal baselines. Extra arguments go to Maven,
# for example -Dsymphony.snapshots.update=true to rewrite baselines after reviewing a change.
image="docker.io/library/maven:3.10.0-eclipse-temurin-25@sha256:9b5f82b7166db6112eb123ba310a9e010ec3fe95022ac0728f491121563dfbab"
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

# An absent CI variable permits local baseline updates; preserve that distinction in the container.
if [ "${CI+x}" = x ]; then
  docker_args+=(-e "CI=$CI")
fi

if [ "$container_runtime" = "podman" ]; then
  docker_args+=(--userns=keep-id)
fi

exec "$container_runtime" "${docker_args[@]}" "$image" \
  ./mvnw -q -Dtest='*SnapshotTest' "$@" test
