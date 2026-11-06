"""Package runnable deliverables and reproducible source, excluding keys/caches."""
from pathlib import Path
import hashlib
import json
import shutil
import zipfile

root = Path(__file__).resolve().parents[1]
out = root.parent / 'outputs'
out.mkdir(exist_ok=True)
html = root / 'dist-offline/SCENIC_Demo.html'
apk = root / 'android/app/build/outputs/apk/debug/scenic-debug.apk'
for source, name in [(html, 'SCENIC_Demo.html'), (apk, 'SCENIC-debug.apk')]:
    if not source.is_file():
        raise SystemExit(f'Missing deliverable: {source.name}')
    shutil.copy2(source, out / name)

catalog = json.loads((root / 'catalog/nationwide.json').read_text(encoding='utf-8'))
first = f'''# SCENIC · 같은 목적지까지 빠른 길과 풍경 길

이번 시연의 주 실행 대상은 Pleos용 Android 네이티브 앱입니다. 목적지 한 곳을 유지하면서 허용한 추가 시간 안의 풍경 길을 비교하고, 안내·빌트인 캠 촬영·공유 미리보기를 체험합니다.

## 시작하기

1. Android Studio에서 `pleos-drive/android`를 열고 `app` · `Pleos Connect v2.1.0`을 선택해 Run을 누르세요. 차량은 P·0km/h 상태로 둡니다.
2. 프로젝트 루트의 기존 `.env`를 유지하세요. 목적지 검색·빠른 길/풍경 길 도로 조회에는 `KAKAO_REST_API_KEY`, 배경 지도에는 `KAKAO_MAP_JS_KEY`와 Kakao Maps 도메인 등록이 필요합니다. `OPENAI_API_KEY`는 음악 추천에 사용합니다. 새 경로 비교에는 OpenAI·TourAPI 실시간 호출이 필요하지 않습니다.
3. Node.js 22 이상에서 프로젝트 루트의 `npm start`로 최신 서버를 실행하세요. 예전 서버가 실행 중이면 먼저 종료해야 새 `/api/destinations`와 `/api/destination-routes`가 적용됩니다. 앱의 `설정 → API 서버 연결` 기본 주소는 `http://10.0.2.2:4173`입니다.
4. Pleos의 `차량 설정 → 연결 → Wi-Fi → AndroidWifi` 연결을 확인하세요. 같은 AVD를 중복 실행하거나 데이터를 지울 필요는 없습니다.

## 경로와 풍경 기록 체험

1. `경로`의 목적지 이름을 눌러 검색 결과 한 곳을 선택합니다. 초기 화면은 부산역 출발·해운대해수욕장 도착의 공개 예시입니다.
2. 출발지는 `현재 위치` 또는 `부산역 · 시연 출발지`로 선택합니다. 현재 위치에는 Android 위치 권한과 최근 국내 GPS 수신이 필요합니다. 부산역 좌표는 실제 현재 위치가 아니며 GPS 실패 시 자동 대체하지 않습니다.
3. `더 돌아가도 괜찮은 시간`을 +15/+30/+60분 또는 직접 입력(0~120분)으로 정하고, 모든 풍경 또는 바다·해안 / 산·전망 / 숲·나무길 / 강·호수 / 들판·농촌 / 도시 풍경 중 선택한 뒤 `경로 비교`를 누릅니다. 추가 시간은 전체 여행 시간이 아니라 빠른 길 대비 허용 시간입니다.
4. 같은 도착지의 `빠른 길`과 `풍경 길`을 선택하면 지도·거리·시간이 바뀝니다. 풍경 길은 후보 한 곳 인근을 통과하며 별도 체류 시간을 더하지 않습니다. 조건에 맞는 풍경 길이 없거나 추가 시간이 0분이면 이유와 빠른 길만 표시합니다.
5. `안내 시연 시작`은 단계별 경로와 풍경 저장을 보여 줍니다. 실제 내비·음성 안내·NaviHelper 연동은 아닙니다. `빌트인 캠 촬영 → 경로 미리보기에 추가`로 데모 기록을 저장하고 `풍경 기록`에서 확인하세요.
6. `공유 미리보기에 표시`는 이 기기의 표시 설정입니다. 사진은 공개 라이선스의 실제 부산 풍경을 사용한 공유 예시이며 SCENIC·사용자 차량·다른 운전자가 촬영한 기록이 아닙니다. 실제 카메라 촬영, 영상 녹화, 서버 업로드나 다른 사용자에게 전송하는 기능은 없습니다. 사진 출처는 `pleos-drive/docs/SCENIC_DEMO_ASSETS.md`에 있습니다.
7. 정차 중 `음악`에서 AI가 선택한 경로 분위기에 맞는 공개 플레이리스트를 고릅니다. `노래 재생`을 누르면 공식 앱 → 외부 HTTPS → 내부 공식 웹 화면에서 같은 목록을 엽니다. 자동 재생·내부 계정 로그인은 제공하지 않습니다.

도로와 시간은 Kakao 조회 결과이며 실제 차창 조망·현장 통행·주차·날씨·모든 고속도로 배제를 보장하지 않습니다. 풍경 후보는 함께 배포한 TourAPI 출처 관광 데이터에서 찾습니다. 기존 전국 {len(catalog['courses'])}개 코스 조합({len(catalog['places']):,}개 관광지, {len(catalog['regions'])}개 관광권역)과 다중 명소 API는 호환·개발 자료로 남아 있으며 새 기본 화면의 방문 일정이 아닙니다.

`SCENIC-debug.apk`는 개발용 서명 APK로 Android Studio의 `app-debug.apk`와 구분합니다. `SCENIC_Demo.html`은 이전 8개 샘플 코스의 별도 보조 웹 데모이며 새 Android 목적지 흐름을 대신하지 않습니다. 안내 시연·사진 저장을 실제 차량 자동 촬영이나 공개 공유 완료로 해석하지 마세요.

자동 검사·Pleos 화면·외부 API 호출의 실제 확인 범위와 남은 검증은 `pleos-drive/docs/VALIDATION.md`를 참고하세요. 실차·스토어 검증을 마친 제품은 아닙니다.

인증키·`.env`·서명 개인키는 이 압축에 없습니다. 사용법은 `pleos-drive/README.md`, 설정은 `pleos-drive/docs/API_SETUP.md`, 데이터 출처는 `pleos-drive/catalog/README.md`에 있습니다.
'''

excluded_dirs = {'.git', '.idea', '.openai', 'node_modules', 'build', 'dist-offline', '.cache', 'outputs', 'work', '__pycache__'}
def include(p):
    rel = p.relative_to(root)
    if any(part in excluded_dirs or part.startswith('.gradle') for part in rel.parts):
        return False
    if p.name in {'.DS_Store', 'local.properties'} or p.suffix in {'.jks', '.keystore', '.apk', '.aab', '.zip', '.log'}:
        return False
    if p.name.startswith('.env') and p.name != '.env.example':
        return False
    return p.is_file() and not p.is_symlink()

archive = out / 'SCENIC_Pleos_Project.zip'
with zipfile.ZipFile(archive, 'w', zipfile.ZIP_DEFLATED) as z:
    z.writestr('README_FIRST.md', first)
    z.write(out / 'SCENIC_Demo.html', 'SCENIC_Demo.html')
    z.write(out / 'SCENIC-debug.apk', 'SCENIC-debug.apk')
    for p in sorted(root.rglob('*')):
        if include(p):
            z.write(p, 'pleos-drive/' + p.relative_to(root).as_posix())
with zipfile.ZipFile(archive) as z:
    bad = z.testzip()
    if bad:
        raise SystemExit(f'Corrupt archive member: {bad}')
    names = z.namelist()
    if any(n.endswith('.jks') or n.endswith('/.env') or '/.git/' in n for n in names):
        raise SystemExit('Unexpected private build material in archive')
print(json.dumps({'archive': str(archive), 'files': len(names), 'bytes': archive.stat().st_size,
                  'sha256': hashlib.sha256(archive.read_bytes()).hexdigest()}, ensure_ascii=False))
