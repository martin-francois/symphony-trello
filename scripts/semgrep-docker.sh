#!/usr/bin/env bash
set -euo pipefail

image="docker.io/semgrep/semgrep:1.164.0"
repo_root="$(git rev-parse --show-toplevel)"
git_dir="$(git -C "$repo_root" rev-parse --absolute-git-dir)"
git_common_dir="$(git -C "$repo_root" rev-parse --path-format=absolute --git-common-dir)"
container_runtime="${SYMPHONY_TRELLO_CONTAINER_RUNTIME:-docker}"

case "$container_runtime" in
docker | podman) ;;
*)
  printf 'SYMPHONY_TRELLO_CONTAINER_RUNTIME must be docker or podman\n' >&2
  exit 2
  ;;
esac
if ! command -v "$container_runtime" >/dev/null 2>&1; then
  printf '%s is required to run Semgrep in a container\n' "$container_runtime" >&2
  exit 127
fi

docker_args=(
  run
  --rm
  --security-opt
  label=disable
  --workdir /src
  -e HOME=/tmp
  -v "$repo_root:/src:ro"
)

if [ "$container_runtime" = "podman" ]; then
  docker_args+=(--userns=keep-id)
fi

# Worktrees can point .git at a directory outside the checkout. Mount those git
# directories so Semgrep can still honor tracked-file scanning inside Docker.
if [[ "$git_dir" != "$repo_root/.git" ]]; then
  docker_args+=(-v "$git_dir:$git_dir:ro")
fi

if [[ "$git_common_dir" != "$repo_root/.git" && "$git_common_dir" != "$git_dir" ]]; then
  docker_args+=(-v "$git_common_dir:$git_common_dir:ro")
fi

# The registry rule requires minimumReleaseAge in every package rule and does not model the
# inherited repository-level policy. The Renovate workflow test enforces one strict global value.
exec "$container_runtime" "${docker_args[@]}" "$image" \
  semgrep scan \
  --config p/owasp-top-ten \
  --config p/security-audit \
  --config p/ci \
  --config p/github-actions \
  --config p/secrets \
  --config p/gitleaks \
  --config p/supply-chain \
  --config config/semgrep \
  --exclude-rule java.lang.security.audit.crypto.unencrypted-socket.unencrypted-socket \
  --exclude-rule package_managers.renovate.renovate-missing-minimum-release-age.renovate-missing-minimum-release-age \
  --disable-version-check \
  --error \
  --metrics=off \
  "$@"
