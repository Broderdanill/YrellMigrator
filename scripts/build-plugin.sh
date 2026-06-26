#!/usr/bin/env bash
set -euo pipefail

VERSION="0.86.0"
PLUGIN_ID="se.yrell.migrator"
PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEVSTUDIO_HOME="${DEVSTUDIO_HOME:-}"

if [[ -z "$DEVSTUDIO_HOME" ]]; then
  echo "Set DEVSTUDIO_HOME to your Developer Studio x64 directory, for example:" >&2
  echo "  DEVSTUDIO_HOME=/opt/BMC/DeveloperStudio/x64 $0" >&2
  exit 2
fi

if [[ -d "$DEVSTUDIO_HOME/x64/plugins" ]]; then
  DEVSTUDIO_HOME="$DEVSTUDIO_HOME/x64"
fi

PLUGINS_DIR="$DEVSTUDIO_HOME/plugins"
if [[ ! -d "$PLUGINS_DIR" ]]; then
  echo "Developer Studio plugins directory not found: $PLUGINS_DIR" >&2
  exit 2
fi

BUILD_DIR="$PROJECT_DIR/build/classes"
INSTALL_DIR="$PROJECT_DIR/install"
JAR_FILE="$INSTALL_DIR/${PLUGIN_ID}_${VERSION}.jar"
CP_FILE="$PROJECT_DIR/build/classpath.txt"

rm -rf "$PROJECT_DIR/build"
mkdir -p "$BUILD_DIR" "$INSTALL_DIR"

python3 - "$PLUGINS_DIR" > "$CP_FILE" <<'PY'
import os
import sys
root = os.path.abspath(sys.argv[1])
paths = []
for dirpath, dirnames, filenames in os.walk(root):
    if any(name.endswith('.class') for name in filenames):
        paths.append(dirpath)
    for name in filenames:
        if name.endswith('.jar'):
            paths.append(os.path.join(dirpath, name))
print(os.pathsep.join(paths))
PY

javac --release 17 -proc:none -encoding UTF-8 -cp "$(cat "$CP_FILE")" -d "$BUILD_DIR" @"$PROJECT_DIR/sources.txt"

(
  cd "$PROJECT_DIR"
  jar cfm "$JAR_FILE" META-INF/MANIFEST.MF \
    -C "$BUILD_DIR" . \
    plugin.xml \
    config \
    README.md
)

python3 "$PROJECT_DIR/scripts/verify-java-release.py" "$JAR_FILE" --max-major 61

echo "Built $JAR_FILE"
