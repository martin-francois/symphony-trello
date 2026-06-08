#!/usr/bin/env bash
set -euo pipefail

VERSION="${1:-}"
if [[ -z "$VERSION" ]]; then
  echo "Usage: scripts/package-release-assets.sh VERSION" >&2
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
DIST="$ROOT/dist/release-assets"
STAGING="$ROOT/target/release-assets/symphony-trello-$VERSION"
ARCHIVE_BASE="symphony-trello-$VERSION"

cd "$ROOT"

rm -rf "$DIST" "$STAGING"

./mvnw -q -DskipTests clean package

mkdir -p "$DIST" "$STAGING/target"
cp -R target/quarkus-app "$STAGING/target/quarkus-app"
cp install.sh install.ps1 uninstall.sh uninstall.ps1 "$DIST/"
cp README.md "$STAGING/README.md"
printf '%s\n' "$VERSION" >"$STAGING/VERSION"
printf 'symphony-trello installer-managed app directory\n' >"$STAGING/.symphony-trello-install"

tar -C "$ROOT/target/release-assets" -czf "$DIST/$ARCHIVE_BASE.tar.gz" "$ARCHIVE_BASE"

if command -v zip >/dev/null 2>&1; then
  (cd "$ROOT/target/release-assets" && zip -qr "$DIST/$ARCHIVE_BASE.zip" "$ARCHIVE_BASE")
else
  echo "zip is required to build the Windows release archive." >&2
  exit 2
fi

CHECKSUM_SOURCE="$ROOT/target/release-assets/Sha3Checksums.java"
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

java "$CHECKSUM_SOURCE" "$DIST" >"$DIST/checksums.txt"

echo "Release assets written to $DIST"
