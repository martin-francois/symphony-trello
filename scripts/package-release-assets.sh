#!/usr/bin/env bash
set -euo pipefail

VERSION="${1:-}"
if [[ -z "$VERSION" ]]; then
  echo "Usage: scripts/package-release-assets.sh VERSION [DIST]" >&2
  exit 2
fi
if [[ "$VERSION" == v* ]]; then
  VERSION="${VERSION#v}"
fi
if [[ ! "$VERSION" =~ ^[0-9]+[.][0-9]+[.][0-9]+([-+][A-Za-z0-9._-]+)?$ ]]; then
  echo "VERSION must be a semantic version without the leading v." >&2
  exit 2
fi

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
DEFAULT_DIST="$ROOT/dist/release-assets"
DIST="${2:-$DEFAULT_DIST}"
if [[ "$DIST" != /* ]]; then
  DIST="$ROOT/$DIST"
fi
ARCHIVE_BASE="symphony-trello-$VERSION"
OWNERSHIP_MARKER=".symphony-trello-release-assets"
EXPECTED_ASSETS=(
  "install.sh"
  "install.ps1"
  "uninstall.sh"
  "uninstall.ps1"
  "checksums.txt"
  "$ARCHIVE_BASE.tar.gz"
  "$ARCHIVE_BASE.zip"
)

fail() {
  echo "$1" >&2
  exit 2
}

canonical_path() {
  local path="$1"
  if [[ "$path" != /* ]]; then
    path="$PWD/$path"
  fi

  local current="/"
  local -a path_parts
  local part candidate
  IFS='/' read -r -a path_parts <<<"${path#/}"
  for part in "${path_parts[@]}"; do
    if [[ -z "$part" || "$part" == "." ]]; then
      continue
    fi
    if [[ "$part" == ".." ]]; then
      current="$(dirname "$current")"
      continue
    fi
    if [[ "$current" == "/" ]]; then
      candidate="/$part"
    else
      candidate="$current/$part"
    fi
    if [[ -d "$candidate" ]]; then
      current="$(cd -P "$candidate" && pwd -P)"
    elif [[ -e "$candidate" ]]; then
      current="$(cd -P "$(dirname "$candidate")" && pwd -P)/$(basename "$candidate")"
    else
      current="$candidate"
    fi
  done
  printf '%s\n' "$current"
}

destination_symlink_probe_path() {
  local path="$1"
  if [[ "$path" != /* ]]; then
    path="$PWD/$path"
  fi

  while [[ "$path" != "/" && "$path" == */ ]]; do
    path="${path%/}"
  done
  while [[ "$path" == */. ]]; do
    path="${path%/.}"
    while [[ "$path" != "/" && "$path" == */ ]]; do
      path="${path%/}"
    done
  done

  printf '%s\n' "${path:-.}"
}

reject_final_destination_symlink() {
  local probe_path
  probe_path="$(destination_symlink_probe_path "$1")"
  [[ ! -L "$probe_path" ]] || fail "Release asset destination must not be a symlink."
}

is_root_or_inside() {
  local child="$1"
  local parent="$2"
  [[ "$child" == "$parent" || "$child" == "$parent"/* ]]
}

is_expected_asset() {
  local name="$1"
  local asset
  for asset in "${EXPECTED_ASSETS[@]}"; do
    if [[ "$name" == "$asset" ]]; then
      return 0
    fi
  done
  return 1
}

is_marker_managed_asset() {
  local marker="$1"
  local name="$2"
  grep -Fx -- "$name" "$marker" >/dev/null 2>&1
}

validate_destination() {
  local root_real="$1"
  local dist_real="$2"
  local target_real="$3"

  [[ "$dist_real" != "/" ]] || fail "Release asset destination must not be the filesystem root."
  is_root_or_inside "$dist_real" "$root_real" ||
    fail "Release asset destination must be inside the source checkout."
  [[ "$dist_real" != "$root_real" ]] || fail "Release asset destination must not be the source checkout."
  is_root_or_inside "$dist_real" "$target_real" &&
    fail "Release asset destination must not be inside Maven build output."
  is_root_or_inside "$target_real" "$dist_real" &&
    fail "Release asset destination must not contain Maven build output."
  if [[ -e "$dist_real" && ! -d "$dist_real" ]]; then
    fail "Release asset destination must be a directory."
  fi
  if [[ -d "$dist_real" ]]; then
    local entry name marker
    marker="$dist_real/$OWNERSHIP_MARKER"
    if [[ -e "$marker" && (! -f "$marker" || -L "$marker") ]]; then
      fail "Release asset destination ownership marker is invalid."
    fi
    shopt -s nullglob dotglob
    for entry in "$dist_real"/*; do
      name="$(basename "$entry")"
      if [[ "$name" == "$OWNERSHIP_MARKER" ]]; then
        continue
      fi
      [[ -f "$entry" && ! -L "$entry" ]] ||
        fail "Release asset destination contains files not managed by this packaging script."
      if [[ -f "$marker" ]]; then
        is_marker_managed_asset "$marker" "$name" ||
          fail "Release asset destination contains files not managed by this packaging script."
      else
        fail "Release asset destination contains files not managed by this packaging script."
      fi
    done
    shopt -u nullglob dotglob
  fi
}

write_ownership_marker() {
  local directory="$1"
  local marker="$directory/$OWNERSHIP_MARKER"
  : >"$marker"
  local asset
  for asset in "${EXPECTED_ASSETS[@]}"; do
    printf '%s\n' "$asset" >>"$marker"
  done
}

verify_assets() {
  local directory="$1"
  local asset
  for asset in "${EXPECTED_ASSETS[@]}"; do
    [[ -f "$directory/$asset" ]] || fail "release asset was not built: $asset"
  done
  local -a unexpected=()
  local entry name
  shopt -s nullglob dotglob
  for entry in "$directory"/*; do
    if [[ -f "$entry" ]]; then
      name="$(basename "$entry")"
      [[ "$name" == "$OWNERSHIP_MARKER" ]] ||
        is_expected_asset "$name" ||
        unexpected+=("$name")
    fi
  done
  shopt -u nullglob dotglob
  [[ "${#unexpected[@]}" -eq 0 ]] ||
    fail "release asset directory contains unexpected files: ${unexpected[*]}"
  grep -Eq "^[0-9a-f]{64}  install[.]sh$" "$directory/checksums.txt" ||
    fail "checksums.txt is not parseable."
}

count_marker_lines() {
  local file="$2"
  local prefix="$1"
  local suffix="$3"
  awk -v prefix="$prefix" -v suffix="$suffix" '
    {
      line = $0
      sub(/^[[:space:]]+/, "", line)
      if (index(line, prefix) == 1 && substr(line, length(line) - length(suffix) + 1) == suffix) {
        count++
      }
    }
    END { print count + 0 }
  ' "$file"
}

count_marker_lines_any_suffix() {
  local file="$2"
  local prefix="$1"
  local first_suffix="$3"
  local second_suffix="$4"
  awk -v prefix="$prefix" -v first_suffix="$first_suffix" -v second_suffix="$second_suffix" '
    {
      line = $0
      sub(/^[[:space:]]+/, "", line)
      first_matches = index(line, prefix) == 1 && substr(line, length(line) - length(first_suffix) + 1) == first_suffix
      second_matches = second_suffix != "" && index(line, prefix) == 1 && substr(line, length(line) - length(second_suffix) + 1) == second_suffix
      if (first_matches || second_matches) {
        count++
      }
    }
    END { print count + 0 }
  ' "$file"
}

require_one_marker() {
  local label="$1"
  local prefix="$2"
  local file="$3"
  local suffix="$4"
  local count
  count="$(count_marker_lines "$prefix" "$file" "$suffix")"
  [[ "$count" == "1" ]] ||
    fail "$label must contain exactly one supported release marker."
}

require_one_marker_any_suffix() {
  local label="$1"
  local prefix="$2"
  local file="$3"
  local first_suffix="$4"
  local second_suffix="$5"
  local count
  count="$(count_marker_lines_any_suffix "$prefix" "$file" "$first_suffix" "$second_suffix")"
  [[ "$count" == "1" ]] ||
    fail "$label must contain exactly one supported release marker."
}

# shellcheck disable=SC2016
validate_installer_templates() {
  require_one_marker \
    "install.sh DEFAULT_VERSION" \
    'DEFAULT_VERSION="' \
    "$ROOT/install.sh" \
    '" # x-release-please-version'
  require_one_marker \
    "install.ps1 Version parameter" \
    '[string]$Version = $(if ($env:SYMPHONY_TRELLO_VERSION) { $env:SYMPHONY_TRELLO_VERSION } else { "' \
    "$ROOT/install.ps1" \
    '" }), # x-release-please-version'
  require_one_marker_any_suffix \
    "install.ps1 Ref parameter" \
    '[string]$Ref = $(if ($env:SYMPHONY_TRELLO_REF) { $env:SYMPHONY_TRELLO_REF } else { "v' \
    "$ROOT/install.ps1" \
    '" }), # x-release-please-version' \
    '" }) # x-release-please-version'
  require_one_marker \
    "install.ps1 DefaultVersion" \
    '$DefaultVersion = "' \
    "$ROOT/install.ps1" \
    '" # x-release-please-version'
}

require_stamped_marker() {
  local label="$1"
  local prefix="$2"
  local file="$3"
  local suffix="$4"
  local count
  count="$(count_marker_lines "$prefix" "$file" "$suffix")"
  [[ "$count" == "1" ]] ||
    fail "$label was not stamped with the requested release version."
}

require_stamped_marker_any_suffix() {
  local label="$1"
  local prefix="$2"
  local file="$3"
  local first_suffix="$4"
  local second_suffix="$5"
  local count
  count="$(count_marker_lines_any_suffix "$prefix" "$file" "$first_suffix" "$second_suffix")"
  [[ "$count" == "1" ]] ||
    fail "$label was not stamped with the requested release version."
}

verify_stamped_installers() {
  local output_dir="$1"
  require_stamped_marker \
    "install.sh DEFAULT_VERSION" \
    "DEFAULT_VERSION=\"$VERSION" \
    "$output_dir/install.sh" \
    '" # x-release-please-version'
  require_stamped_marker \
    "install.ps1 Version parameter" \
    "[string]\$Version = \$(if (\$env:SYMPHONY_TRELLO_VERSION) { \$env:SYMPHONY_TRELLO_VERSION } else { \"$VERSION" \
    "$output_dir/install.ps1" \
    '" }), # x-release-please-version'
  require_stamped_marker_any_suffix \
    "install.ps1 Ref parameter" \
    "[string]\$Ref = \$(if (\$env:SYMPHONY_TRELLO_REF) { \$env:SYMPHONY_TRELLO_REF } else { \"v$VERSION" \
    "$output_dir/install.ps1" \
    '" }), # x-release-please-version' \
    '" }) # x-release-please-version'
  require_stamped_marker \
    "install.ps1 DefaultVersion" \
    "\$DefaultVersion = \"$VERSION" \
    "$output_dir/install.ps1" \
    '" # x-release-please-version'
}

stamp_posix_installer() {
  local output_dir="$1"
  awk -v version="$VERSION" '
    /^DEFAULT_VERSION=.*# x-release-please-version/ {
      sub(/"[^"]+"/, "\"" version "\"")
    }
    { print }
  ' "$ROOT/install.sh" >"$output_dir/install.sh"
  chmod +x "$output_dir/install.sh"
}

stamp_powershell_installer() {
  local output_dir="$1"
  awk -v version="$VERSION" '
    /^[[:space:]]*\[string\]\$Version = .*# x-release-please-version/ {
      sub(/else \{ "[^"]+" \}/, "else { \"" version "\" }")
    }
    /^[[:space:]]*\[string\]\$Ref = .*# x-release-please-version/ {
      sub(/else \{ "[^"]+" \}/, "else { \"v" version "\" }")
    }
    /^\$DefaultVersion = .*# x-release-please-version/ {
      sub(/"[^"]+"/, "\"" version "\"")
    }
    { print }
  ' "$ROOT/install.ps1" >"$output_dir/install.ps1"
}

publish_assets() {
  local source_dir="$1"
  local dist_real="$2"
  local parent
  parent="$(dirname "$dist_real")"
  mkdir -p "$parent"
  local publish_tmp backup_dir backup_path
  publish_tmp="$(mktemp -d "$parent/.release-assets.XXXXXX")"
  backup_dir="$(mktemp -d "$parent/.release-assets-backup.XXXXXX")"
  cp -R "$source_dir/." "$publish_tmp/"
  verify_assets "$publish_tmp"
  write_ownership_marker "$publish_tmp"
  if [[ -d "$dist_real" ]]; then
    backup_path="$backup_dir/old"
    mv "$dist_real" "$backup_path"
    if ! mv "$publish_tmp" "$dist_real"; then
      mv "$backup_path" "$dist_real"
      rm -rf "$backup_dir" "$publish_tmp"
      fail "Could not publish release assets."
    fi
    rm -rf "$backup_dir"
  else
    if ! mv "$publish_tmp" "$dist_real"; then
      rm -rf "$backup_dir" "$publish_tmp"
      fail "Could not publish release assets."
    fi
    rm -rf "$backup_dir"
  fi
}

cd "$ROOT"

ROOT_REAL="$(canonical_path "$ROOT")"
reject_final_destination_symlink "$DIST"
DIST_REAL="$(canonical_path "$DIST")"
TARGET_REAL="$(canonical_path "$ROOT/target")"
validate_destination "$ROOT_REAL" "$DIST_REAL" "$TARGET_REAL"
validate_installer_templates

./mvnw -q -DskipTests clean package

WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/symphony-trello-release.XXXXXX")"
trap 'rm -rf "$WORK_DIR"' EXIT
ASSET_DIR="$WORK_DIR/assets"
STAGING_PARENT="$WORK_DIR/staging"
STAGING="$STAGING_PARENT/$ARCHIVE_BASE"
mkdir -p "$ASSET_DIR" "$STAGING/target"
cp -R "$ROOT/target/quarkus-app" "$STAGING/target/quarkus-app"
stamp_posix_installer "$ASSET_DIR"
stamp_powershell_installer "$ASSET_DIR"
verify_stamped_installers "$ASSET_DIR"
cp "$ROOT/uninstall.sh" "$ROOT/uninstall.ps1" "$ASSET_DIR/"
cp "$ROOT/README.md" "$STAGING/README.md"
printf '%s\n' "$VERSION" >"$STAGING/VERSION"
printf 'symphony-trello installer-managed app directory\n' >"$STAGING/.symphony-trello-install"

tar -C "$STAGING_PARENT" -czf "$ASSET_DIR/$ARCHIVE_BASE.tar.gz" "$ARCHIVE_BASE"

if command -v zip >/dev/null 2>&1; then
  (cd "$STAGING_PARENT" && zip -qr "$ASSET_DIR/$ARCHIVE_BASE.zip" "$ARCHIVE_BASE")
else
  echo "zip is required to build the Windows release archive." >&2
  exit 2
fi

CHECKSUM_SOURCE="$WORK_DIR/Sha3Checksums.java"
cat >"$CHECKSUM_SOURCE" <<'JAVA'
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.stream.Stream;

class Sha3Checksums {
    public static void main(String[] args) throws IOException, NoSuchAlgorithmException {
        Path directory = Path.of(args[0]);
        MessageDigest digest = MessageDigest.getInstance("SHA3-256");
        try (Stream<Path> files = Files.list(directory)) {
            files.filter(Files::isRegularFile)
                    .filter(path -> !path.getFileName().toString().equals("checksums.txt"))
                    .sorted()
                    .forEach(path -> printChecksum(digest, path));
        }
    }

    private static void printChecksum(MessageDigest digest, Path path) {
        try {
            digest.reset();
            byte[] checksum = digest.digest(Files.readAllBytes(path));
            System.out.printf("%s  %s%n", HexFormat.of().formatHex(checksum), path.getFileName());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
JAVA

java "$CHECKSUM_SOURCE" "$ASSET_DIR" >"$ASSET_DIR/checksums.txt"
verify_assets "$ASSET_DIR"
publish_assets "$ASSET_DIR" "$DIST_REAL"

echo "Release assets written to $DIST_REAL"
