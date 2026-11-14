#!/bin/bash
# Run from Finder or the user's Terminal. Never wipe the AVD's application data.
set -euo pipefail

scenic_root=$(cd -- "$(dirname -- "$0")/.." && pwd)
scenic_sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-"$HOME/Library/Android/sdk"}}
scenic_adb="$scenic_sdk/platform-tools/adb"
scenic_emulator="$scenic_sdk/emulator/emulator"
scenic_avd=Pleos_Connect_v2.1.0
scenic_apk="$scenic_root/android/app/build/outputs/apk/debug/app-debug.apk"

fail() { printf '%s\n' "$*" >&2; exit 1; }
[[ -x "$scenic_adb" && -x "$scenic_emulator" ]] || fail 'Android SDK의 adb와 emulator를 찾지 못했습니다.'
[[ -f "$scenic_apk" ]] || fail '설치할 APK가 없습니다. Android Studio에서 먼저 assembleDebug를 실행해 주세요.'

if [[ ${1:-} == --check ]]; then
  printf 'SDK: %s\nAVD: %s\nAPK: %s\n' "$scenic_sdk" "$scenic_avd" "$scenic_apk"
  "$scenic_emulator" -list-avds
  exit 0
fi
[[ $# == 0 ]] || fail '사용법: start-pleos.command [--check]'

# The demo server is managed separately; do not stop an existing server.
curl --fail --silent --max-time 3 http://127.0.0.1:4173/api/status >/dev/null ||
  fail '서버가 꺼져 있습니다. 먼저 scripts/start-local-server.command를 실행해 주세요.'

scenic_serial=''
find_pleos() {
  local device state rest name
  scenic_serial=''
  while read -r device state rest; do
    [[ "$device" == emulator-* ]] || continue
    # An offline emulator may already own this AVD. Do not start a duplicate.
    [[ "$state" == device ]] || fail '연결 중인 에뮬레이터가 있습니다. 부팅을 기다리거나 해당 창을 닫은 뒤 다시 실행해 주세요.'
    name=$("$scenic_adb" -s "$device" emu avd name 2>/dev/null | tr -d '\r' | head -n 1)
    if [[ "$name" == "$scenic_avd" ]]; then
      [[ -z "$scenic_serial" ]] || fail 'Pleos가 여러 개 실행 중입니다. 하나만 남겨 주세요.'
      scenic_serial=$device
    fi
  done < <("$scenic_adb" devices)
}
find_pleos

scenic_pid=''
if [[ -z "$scenic_serial" ]]; then
  # An isolated, initially absent cache image avoids all existing disk-cache data.
  scenic_run=$(mktemp -d "${TMPDIR:-/tmp}/scenic-pleos.XXXXXX")
  scenic_log="$scenic_run/emulator.log"
  printf '새 캐시로 Pleos를 시작합니다. SwiftShader 사용, Vulkan·스냅샷 비활성화.\n로그: %s\n' "$scenic_log"
  "$scenic_emulator" -avd "$scenic_avd" \
    -cache "$scenic_run/cache.img" -no-snapshot \
    -gpu swiftshader -feature -Vulkan -dns-server 8.8.8.8 \
    >"$scenic_log" 2>&1 &
  scenic_pid=$!
fi

scenic_ready=0
scenic_deadline=$((SECONDS + 180))
while (( SECONDS < scenic_deadline )); do
  if [[ -n "$scenic_pid" ]] && ! kill -0 "$scenic_pid" 2>/dev/null; then
    wait "$scenic_pid" || true
    tail -n 25 "$scenic_log"
    fail '에뮬레이터가 부팅 전에 종료됐습니다. 위 로그를 확인해 주세요.'
  fi
  # During startup, offline devices are expected. Query online emulators only.
  while read -r scenic_device scenic_state scenic_rest; do
    [[ "$scenic_device" == emulator-* && "$scenic_state" == device ]] || continue
    scenic_name=$("$scenic_adb" -s "$scenic_device" emu avd name 2>/dev/null | tr -d '\r' | head -n 1) || continue
    [[ "$scenic_name" == "$scenic_avd" ]] || continue
    scenic_serial=$scenic_device
    scenic_boot=$("$scenic_adb" -s "$scenic_serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r') || continue
    [[ "$scenic_boot" == 1 ]] && scenic_ready=1
  done < <("$scenic_adb" devices)
  [[ "$scenic_ready" == 1 ]] && break
  sleep 2
done
[[ "$scenic_ready" == 1 ]] || fail '3분 안에 부팅이 완료되지 않았습니다. 열려 있는 에뮬레이터와 로그를 확인해 주세요.'

scenic_user=$("$scenic_adb" -s "$scenic_serial" shell am get-current-user | tr -d '\r')
[[ "$scenic_user" =~ ^[0-9]+$ ]] || fail '현재 Pleos 사용자를 확인하지 못했습니다.'
printf '수정한 SCENIC 앱을 설치합니다. 기존 앱 데이터는 유지됩니다.\n'
"$scenic_adb" -s "$scenic_serial" install -r --user "$scenic_user" "$scenic_apk"
scenic_launch=$("$scenic_adb" -s "$scenic_serial" shell am start --user "$scenic_user" -W -n com.scenic.pleos/.MainActivity)
printf '%s\n' "$scenic_launch"
[[ "$scenic_launch" == *'Status: ok'* ]] || fail '앱 실행 완료를 확인하지 못했습니다.'
printf 'SCENIC 실행 완료. 서버 주소는 앱 설정에서 http://10.0.2.2:4173을 사용하세요.\n'
if [[ -n "$scenic_pid" ]]; then
  printf '시연하는 동안 이 터미널을 열어 두세요.\n'
  wait "$scenic_pid"
fi
