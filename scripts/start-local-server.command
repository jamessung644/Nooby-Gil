#!/bin/sh
# Run from the user's Terminal to keep the demonstration server visible.
# Restart only a listener whose working directory and command match this project.
set -eu
scenic_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$scenic_root"
scenic_listeners=$(lsof -tiTCP:4173 -sTCP:LISTEN 2>/dev/null || true)
for scenic_pid in $scenic_listeners; do
  scenic_cwd=$(lsof -a -p "$scenic_pid" -d cwd -Fn 2>/dev/null | sed -n 's/^n//p')
  scenic_command=$(ps -p "$scenic_pid" -o command= 2>/dev/null || true)
  case "$scenic_command" in
    *server/index.mjs*) ;;
    *) echo '4173 포트를 다른 프로그램이 사용 중입니다. 해당 프로그램은 종료하지 않았습니다.'; exit 1 ;;
  esac
  if [ "$scenic_cwd" != "$scenic_root" ]; then
    echo '다른 프로젝트가 4173 포트를 사용 중입니다. 해당 서버는 종료하지 않았습니다.'
    exit 1
  fi
done
for scenic_pid in $scenic_listeners; do
  kill -TERM "$scenic_pid"
done
scenic_attempt=0
while lsof -tiTCP:4173 -sTCP:LISTEN >/dev/null 2>&1; do
  scenic_attempt=$((scenic_attempt + 1))
  if [ "$scenic_attempt" -ge 20 ]; then
    echo '이전 서버가 아직 종료 중입니다. 잠시 후 다시 실행해 주세요.'
    exit 1
  fi
  sleep 0.1
done
echo 'SCENIC 서버를 실행합니다. 시연하는 동안 이 창을 열어 두세요. 종료: Ctrl+C'
exec npm start
