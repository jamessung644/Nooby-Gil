#!/bin/sh
# Offline tests use the installed Android Studio JSON implementation, with no sockets.
set -eu
PROJECT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
JAVA_DIR=${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}
JSON_JAR=${SCENIC_TEST_JSON_JAR:-/Applications/Android Studio.app/Contents/plugins/design-tools/lib/layoutlib.jar}
if [ ! -f "$JSON_JAR" ]; then
  echo 'Set SCENIC_TEST_JSON_JAR to a local jar containing a working org.json implementation.' >&2
  exit 1
fi
OUT_DIR=$(mktemp -d "${TMPDIR:-/tmp}/scenic-api-tests.XXXXXX")
trap 'rm -rf "$OUT_DIR"' EXIT HUP INT TERM
"$JAVA_DIR/bin/javac" --release 8 -Xlint:-options -encoding UTF-8 -cp "$JSON_JAR" -d "$OUT_DIR" \
  "$PROJECT_DIR/android/app/src/main/java/com/scenic/pleos/CourseEngine.java" \
  "$PROJECT_DIR/android/app/src/main/java/com/scenic/pleos/NationwideCatalog.java" \
  "$PROJECT_DIR/android/app/src/main/java/com/scenic/pleos/LocationFixPolicy.java" \
  "$PROJECT_DIR/android/app/src/main/java/com/scenic/pleos/EndpointPolicy.java" \
  "$PROJECT_DIR/android/app/src/main/java/com/scenic/pleos/ScenicApiClient.java" \
  "$PROJECT_DIR/android/app/src/main/java/com/scenic/pleos/DestinationPlace.java" \
  "$PROJECT_DIR/android/app/src/main/java/com/scenic/pleos/DestinationRoutePlan.java" \
  "$PROJECT_DIR/android/app/src/main/java/com/scenic/pleos/LivePlan.java" \
  "$PROJECT_DIR/android/app/src/main/java/com/scenic/pleos/LivePlanOrigin.java" \
  "$PROJECT_DIR/android/app/src/main/java/com/scenic/pleos/StrictJson.java" \
  "$PROJECT_DIR/android/app/src/main/java/com/scenic/pleos/MusicContext.java" \
  "$PROJECT_DIR/android/app/src/main/java/com/scenic/pleos/MusicPlaylist.java" \
  "$PROJECT_DIR/android/app/src/main/java/com/scenic/pleos/MusicRecommendationState.java" \
  "$PROJECT_DIR/tests/api-client/EndpointPolicyTest.java" \
  "$PROJECT_DIR/tests/api-client/ScenicApiClientTest.java" \
  "$PROJECT_DIR/tests/api-client/DestinationRoutesTest.java" \
  "$PROJECT_DIR/tests/api-client/LivePlanTest.java" \
  "$PROJECT_DIR/tests/api-client/MusicPlaylistTest.java" \
  "$PROJECT_DIR/tests/api-client/MusicRecommendationStateTest.java"
"$JAVA_DIR/bin/java" -cp "$OUT_DIR:$JSON_JAR" com.scenic.pleos.EndpointPolicyTest
"$JAVA_DIR/bin/java" -cp "$OUT_DIR:$JSON_JAR" com.scenic.pleos.ScenicApiClientTest
"$JAVA_DIR/bin/java" -cp "$OUT_DIR:$JSON_JAR" com.scenic.pleos.DestinationRoutesTest

"$JAVA_DIR/bin/java" -cp "$OUT_DIR:$JSON_JAR" com.scenic.pleos.LivePlanTest
"$JAVA_DIR/bin/java" -cp "$OUT_DIR:$JSON_JAR" com.scenic.pleos.MusicPlaylistTest
"$JAVA_DIR/bin/java" -cp "$OUT_DIR:$JSON_JAR" com.scenic.pleos.MusicRecommendationStateTest
