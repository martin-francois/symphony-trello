#!/usr/bin/env bash
set -euo pipefail

image="semgrep/semgrep:1.164.0"
repo_root="$(git rev-parse --show-toplevel)"
git_dir="$(git -C "$repo_root" rev-parse --absolute-git-dir)"
git_common_dir="$(git -C "$repo_root" rev-parse --path-format=absolute --git-common-dir)"

docker_args=(
  run
  --rm
  --workdir /src
  -v "$repo_root:/src:ro"
)

# Worktrees can point .git at a directory outside the checkout. Mount those git
# directories so Semgrep can still honor tracked-file scanning inside Docker.
if [[ "$git_dir" != "$repo_root/.git" ]]; then
  docker_args+=(-v "$git_dir:$git_dir:ro")
fi

if [[ "$git_common_dir" != "$repo_root/.git" && "$git_common_dir" != "$git_dir" ]]; then
  docker_args+=(-v "$git_common_dir:$git_common_dir:ro")
fi

exec docker "${docker_args[@]}" "$image" \
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
  --error \
  --metrics=off \
  "$@"
