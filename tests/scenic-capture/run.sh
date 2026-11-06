#!/bin/sh
# Offline route association, preview visibility, persistence and safety tests.
set -eu
PROJECT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
JAVA_DIR=${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}
OUT_DIR=$(mktemp -d "${TMPDIR:-/tmp}/scenic-capture-tests.XXXXXX")
trap 'rm -rf "$OUT_DIR"' EXIT HUP INT TERM
"$JAVA_DIR/bin/javac" --release 8 -encoding UTF-8 -d "$OUT_DIR" \
  "$PROJECT_DIR/android/app/src/main/java/com/scenic/pleos/ScenicCaptureStore.java" \
  "$PROJECT_DIR/android/tests/ScenicCaptureStoreTest.java"
"$JAVA_DIR/bin/java" -cp "$OUT_DIR" com.scenic.pleos.ScenicCaptureStoreTest
