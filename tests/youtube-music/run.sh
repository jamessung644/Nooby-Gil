#!/bin/sh
# Offline URL and fallback tests; the SDK supplies types, no emulator/network is used.
set -eu
PROJECT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
JAVA_DIR=${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}
SDK_DIR=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}
ANDROID_JAR=${SCENIC_TEST_ANDROID_JAR:-$SDK_DIR/platforms/android-37.0/android.jar}
for required in "$JAVA_DIR/bin/javac" "$JAVA_DIR/bin/java" "$ANDROID_JAR"; do
  if [ ! -f "$required" ]; then
    echo 'Required JDK/Android SDK missing. Set JAVA_HOME or SCENIC_TEST_ANDROID_JAR.' >&2
    exit 1
  fi
done
OUT_DIR=$(mktemp -d "${TMPDIR:-/tmp}/scenic-youtube-tests.XXXXXX")
trap 'rm -rf "$OUT_DIR"' EXIT HUP INT TERM
"$JAVA_DIR/bin/javac" --release 8 -Xlint:-options -encoding UTF-8 -cp "$ANDROID_JAR" -d "$OUT_DIR" \
  "$PROJECT_DIR/tests/youtube-music/stubs/android/content/ActivityNotFoundException.java" \
  "$PROJECT_DIR/android/app/src/main/java/com/scenic/pleos/YouTubeMusicLauncher.java" \
  "$PROJECT_DIR/tests/youtube-music/YouTubeMusicLauncherTest.java"
"$JAVA_DIR/bin/java" -cp "$OUT_DIR:$ANDROID_JAR" com.scenic.pleos.YouTubeMusicLauncherTest
