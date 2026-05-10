#!/usr/bin/env bash
set -euo pipefail

# Microsoft documents the .NET SDK images as the current PowerShell-in-Docker path.
# Keep this wrapper small so CI and local checks exercise the same PowerShell runtime.
IMAGE="${SYMPHONY_TRELLO_PWSH_DOCKER_IMAGE:-mcr.microsoft.com/dotnet/sdk:8.0}"

if ! command -v docker >/dev/null 2>&1; then
  echo "Docker is required to run PowerShell through $IMAGE." >&2
  exit 127
fi

docker_environment=(
  -e HOME=/tmp
  -e PATH
  -e POWERSHELL_TELEMETRY_OPTOUT=1
  -e SYMPHONY_TRELLO_ALLOW_NON_WINDOWS_PWSH_FOR_TEST=1
)
while IFS='=' read -r name _; do
  case "$name" in
  SYMPHONY_*) docker_environment+=(-e "$name") ;;
  esac
done < <(env)

exec docker run --rm \
  --user "$(id -u):$(id -g)" \
  "${docker_environment[@]}" \
  -v "$PWD:$PWD" \
  -v /tmp:/tmp \
  -w "$PWD" \
  "$IMAGE" \
  pwsh "$@"
