#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEVSTUDIO_HOME="${DEVSTUDIO_HOME:-}"
if [[ -z "$DEVSTUDIO_HOME" ]]; then
  echo "Set DEVSTUDIO_HOME to your Developer Studio x64 directory before running smoke tests." >&2
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

BUILD_DIR="$PROJECT_DIR/build/test-classes"
CP_FILE="$PROJECT_DIR/build/test-classpath.txt"
rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR" "$PROJECT_DIR/build"

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

javac --release 17 -proc:none -encoding UTF-8 -cp "$(cat "$CP_FILE")" -d "$BUILD_DIR" @"$PROJECT_DIR/sources.txt" "$PROJECT_DIR/tests/se/yrell/migrator/core/MigrationPlannerSmokeTest.java"
java -cp "$BUILD_DIR:$(cat "$CP_FILE")" se.yrell.migrator.core.MigrationPlannerSmokeTest
