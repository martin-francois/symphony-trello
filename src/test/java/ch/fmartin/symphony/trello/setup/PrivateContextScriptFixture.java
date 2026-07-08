package ch.fmartin.symphony.trello.setup;

import static ch.fmartin.symphony.trello.setup.InstallerScriptFixture.writeExecutable;

import java.nio.file.Path;

final class PrivateContextScriptFixture {
    private PrivateContextScriptFixture() {}

    static Path installFakeBetterLeaks(Path directory) throws Exception {
        Path command = directory.resolve("betterleaks");
        writeExecutable(
                command,
                """
                #!/usr/bin/env bash
                set -euo pipefail

                private_config=false
                mode=""
                log_opts=""
                paths=()

                while [ "$#" -gt 0 ]; do
                  case "$1" in
                    -c)
                      case "${2:-}" in
                        *private-context.toml) private_config=true ;;
                      esac
                      shift 2
                      ;;
                    --log-opts=*)
                      log_opts="${1#*=}"
                      shift
                      ;;
                    stdin|dir|git)
                      mode="$1"
                      shift
                      ;;
                    -*)
                      shift
                      ;;
                    *)
                      paths+=("$1")
                      shift
                      ;;
                  esac
                done

                if [ "$private_config" = false ]; then
                  exit 0
                fi

                scan_file="$(mktemp)"
                trap 'rm -f "$scan_file"' EXIT

                case "$mode" in
                  stdin)
                    cat > "$scan_file"
                    ;;
                  dir)
                    find "${paths[@]}" -type f -print0 | while IFS= read -r -d '' file; do
                      grep -Ih . "$file" || true
                    done > "$scan_file"
                    ;;
                  git)
                    git diff --unified=0 "$log_opts" -- "${paths[@]}" |
                      awk '
                        /^@@ / { in_hunk = 1; next }
                        /^diff --git / { in_hunk = 0; next }
                        in_hunk && /^\\+/ { sub(/^\\+/, ""); print }
                      ' > "$scan_file"
                    ;;
                  *)
                    exit 0
                    ;;
                esac

                status=0
                if grep -Eq 'trello\\.com/[bc]/AbCd1234' "$scan_file"; then
                  printf 'trello-url-private-context: [REDACTED]\\n'
                  status=1
                fi
                if grep -Eq '6a1eb7c4873''fd71be041d1cf' "$scan_file"; then
                  printf 'trello-id-private-context: [REDACTED]\\n'
                  status=1
                fi
                exit "$status"
                """);
        return command;
    }

    static Path installFakeDockerBetterLeaks(Path directory) throws Exception {
        Path command = directory.resolve("docker");
        writeExecutable(
                command,
                """
                #!/usr/bin/env bash
                set -euo pipefail

                interactive=false
                disables_label=false
                private_config=false
                mode=""

                while [ "$#" -gt 0 ]; do
                  case "$1" in
                    -i|--interactive)
                      interactive=true
                      shift
                      ;;
                    --security-opt)
                      [ "${2:-}" = "label=disable" ] && disables_label=true
                      shift 2
                      ;;
                    -c)
                      case "${2:-}" in
                        *private-context.toml) private_config=true ;;
                      esac
                      shift 2
                      ;;
                    stdin|dir|git)
                      mode="$1"
                      shift
                      ;;
                    *)
                      shift
                      ;;
                  esac
                done

                if [ "$interactive" = false ]; then
                  printf 'fake docker: missing interactive stdin\\n' >&2
                  exit 42
                fi
                if [ "$disables_label" = false ]; then
                  printf 'fake docker: missing disabled label security option\\n' >&2
                  exit 43
                fi

                scan_file="$(mktemp)"
                trap 'rm -f "$scan_file"' EXIT
                cat > "$scan_file"

                if [ "$mode" != stdin ] || [ "$private_config" = false ]; then
                  exit 0
                fi

                if grep -Eq 'trello\\.com/[bc]/AbCd1234' "$scan_file"; then
                  printf 'trello-url-private-context: [REDACTED]\\n'
                  exit 1
                fi
                exit 0
                """);
        return command;
    }
}
