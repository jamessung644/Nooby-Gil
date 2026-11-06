#!/bin/sh
# Build through Gradle so the Pleos SDK and its Android/Kotlin dependencies are packaged.
set -eu
PROJECT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$PROJECT_DIR"
exec sh ./gradlew assembleDebug --console=plain "$@"
