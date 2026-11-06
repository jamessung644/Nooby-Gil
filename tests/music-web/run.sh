#!/bin/sh
# Pure URL/window bounds checks; compile the Android UI against installed SDK types.
set -eu
PROJECT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
JAVA_DIR=${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}
SDK_DIR=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}
ANDROID_JAR=${SCENIC_TEST_ANDROID_JAR:-$SDK_DIR/platforms/android-37.0/android.jar}
OUT_DIR=$(mktemp -d "${TMPDIR:-/tmp}/scenic-music-web-tests.XXXXXX")
trap 'rm -rf "$OUT_DIR"' EXIT HUP INT TERM
"$JAVA_DIR/bin/javac" --release 8 -Xlint:-options -encoding UTF-8 -cp "$ANDROID_JAR" -d "$OUT_DIR" \
  "$PROJECT_DIR/android/app/src/main/java/com/scenic/pleos/MusicWebPolicy.java" \
  "$PROJECT_DIR/android/app/src/main/java/com/scenic/pleos/MusicWindowLayout.java" \
  "$PROJECT_DIR/tests/music-web/MusicWebPolicyTest.java" \
  "$PROJECT_DIR/tests/music-web/MusicWindowLayoutTest.java"
"$JAVA_DIR/bin/java" -cp "$OUT_DIR" com.scenic.pleos.MusicWebPolicyTest
"$JAVA_DIR/bin/java" -cp "$OUT_DIR" com.scenic.pleos.MusicWindowLayoutTest
"$JAVA_DIR/bin/javac" --release 8 -Xlint:-options -encoding UTF-8 -cp "$ANDROID_JAR:$OUT_DIR" -d "$OUT_DIR" \
  "$PROJECT_DIR/android/app/src/main/java/com/scenic/pleos/YouTubeMusicLauncher.java" \
  "$PROJECT_DIR/android/app/src/main/java/com/scenic/pleos/ScenicTypography.java" \
  "$PROJECT_DIR/android/app/src/main/java/com/scenic/pleos/MusicWebDialog.java"
