# AI 음악 추천의 실제 후보

AI는 아래 6개의 실제 공개 플레이리스트에서 하나를 선택하고 추천 이유를 작성한다. 코스 이름·풍경·시간을 참고하며 개인 청취 기록·날씨·현재 시각을 추정하지 않는다. 음악 파일이나 곡 목록을 복제·생성하지 않는다. 전체 YouTube를 검색하는 기능은 아니다.

2026-11-05에 제작자가 공개한 링크를 확인했다. Indie Folk Central은 각 공식 페이지의 `Other platforms`에서 연결한 Feature.fm의 YouTube Music 목적지를 확인했고, OVERWERK는 공식 사이트의 `YOUTUBE MUSIC` 링크를 확인했다. 링크 확인은 모든 국가·계정에서의 현재 재생을 보장하지 않는다. 플레이리스트 내용은 제작자가 변경할 수 있다.

| 플레이리스트 | 제작자 | 추천용 분위기 | 공식 출처 |
|---|---|---|---|
| Lost in the Woods | Indie Folk Central | 숲·호수, 차분한 포크 | https://indiefolkcentral.com/playlists/lost-in-the-woods/ |
| Acoustic Indie Folk | Indie Folk Central | 기타·목소리 중심의 담백함 | https://indiefolkcentral.com/playlists/acoustic-indie-folk/ |
| Cozy Folk | Indie Folk Central | 따뜻하고 부드러운 포크 | https://indiefolkcentral.com/playlists/cozy-folk/ |
| Indie Roadtrip & Adventure | Indie Folk Central | 밝고 경쾌한 로드트립 | https://indiefolkcentral.com/playlists/feel-good-folk-pop/ |
| NIGHT DRIVE 006 | OVERWERK | 도시 드라이브 | https://www.overwerk.com/music |
| DAY DRIVE 001 | OVERWERK | 일반·해안 드라이브 | https://www.overwerk.com/music |

`Indie Roadtrip & Adventure`는 위 제작자 소개 페이지에서 연결되는 YouTube Music 목록의 현재 표시 제목이다. 2026-11-05 Pleos 내부 음악 화면에서 목적지 제목을 직접 확인해 카탈로그를 갱신했다. 나머지 다섯 목록의 목적지 제목은 이번 Pleos 실행에서 각각 확인하지 않았다.

분위기와 풍경 태그는 공식 설명·제목에 기반한 앱의 추천용 해석이며 사용자의 개인 취향을 검증한 값이 아니다. 시간 조건은 추천의 참고 정보다. 곡 수·전체 재생 길이는 조회하지 않으므로 여정과 정확히 같은 길이라고 표시하지 않는다.

실제 ID는 `server/music-catalog.mjs`에 있다. 변경 시 제작자의 링크와 공식 Music 화면을 확인한 다음 ID·제목·출처·확인 날짜를 함께 갱신한다. AI가 출력한 임의 ID를 카탈로그에 등록하면 안 된다. 서버는 카탈로그 ID를 구조화 출력의 enum으로 제한하고 다시 검증하며, URL은 `https://music.youtube.com/playlist?list=`와 검증된 ID로 직접 조립한다.

`노래 재생`은 선택한 플레이리스트 페이지를 여는 버튼이다. 공식 앱·외부 HTTPS·내부 WebView에 같은 URL을 전달하며 자동 재생·로그인·구독을 진행하지 않는다. 내부 화면에서 Google 계정 로그인은 지원하지 않는다.

검사: `node --test tests/music.test.mjs`, `sh tests/youtube-music/run.sh`, `sh tests/music-web/run.sh`. Android 추천 모델·요청 상태 검사와 실제 Pleos 확인 결과는 `VALIDATION.md`에 기록한다.
