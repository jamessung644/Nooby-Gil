#!/bin/sh
# Offline native map boundary checks using installed JDK, Android SDK and JSON classes.
set -eu
PROJECT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
JAVA_DIR=${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}
JSON_JAR=${SCENIC_TEST_JSON_JAR:-/Applications/Android Studio.app/Contents/plugins/design-tools/lib/layoutlib.jar}
SDK_DIR=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}
ANDROID_JAR=${SCENIC_TEST_ANDROID_JAR:-$SDK_DIR/platforms/android-37.0/android.jar}
for required in "$JAVA_DIR/bin/javac" "$JAVA_DIR/bin/java" "$JSON_JAR" "$ANDROID_JAR"; do
  if [ ! -f "$required" ]; then
    echo 'Required local JDK/JSON/Android SDK dependency is missing. Set JAVA_HOME, SCENIC_TEST_JSON_JAR or SCENIC_TEST_ANDROID_JAR.' >&2
    exit 1
  fi
done
OUT_DIR=$(mktemp -d "${TMPDIR:-/tmp}/scenic-map-tests.XXXXXX")
trap 'rm -rf "$OUT_DIR"' EXIT HUP INT TERM
"$JAVA_DIR/bin/javac" --release 8 -Xlint:-options -encoding UTF-8 -cp "$ANDROID_JAR:$JSON_JAR" -d "$OUT_DIR" \
  "$PROJECT_DIR/android/app/src/main/java/com/scenic/pleos/CourseEngine.java" \
  "$PROJECT_DIR/android/app/src/main/java/com/scenic/pleos/NationwideCatalog.java" \
  "$PROJECT_DIR/android/app/src/main/java/com/scenic/pleos/LocationFixPolicy.java" \
  "$PROJECT_DIR/android/app/src/main/java/com/scenic/pleos/EndpointPolicy.java" \
  "$PROJECT_DIR/android/app/src/main/java/com/scenic/pleos/KakaoMapView.java" \
  "$PROJECT_DIR/tests/kakao-map-native/KakaoMapPayloadTest.java" \
  "$PROJECT_DIR/tests/kakao-map-native/KakaoMapTransportTest.java"
"$JAVA_DIR/bin/java" -cp "$OUT_DIR:$JSON_JAR" com.scenic.pleos.KakaoMapPayloadTest
"$JAVA_DIR/bin/java" -cp "$OUT_DIR:$JSON_JAR" com.scenic.pleos.KakaoMapTransportTest
