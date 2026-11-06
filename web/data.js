/**
 * CURATED DEMO DATA — not a navigable road network.
 * Place names and broad scenery are grounded in the official tourism references
 * below (checked 2026-11-04). Coordinates are hand-curated approximate scene
 * anchors, and connecting lines are illustrative, not verified drivable roads.
 * durationMin, distanceKm, sceneryScore, titles and route groupings are authored
 * demonstration values, not traffic estimates, measured quality, or official tours.
 * A stop denotes an area of interest, not permission to park or enter by car.
 * Park/forest interiors are walking destinations; this prototype does not route
 * vehicles through them. Production requires a licensed routing/traffic provider.
 */
export const dataNotice = '시연용 큐레이션 · 시간·거리·점수는 예시이며, 실제 주행 안내가 아닙니다.';

export const fixtureSources = [
  { title: '한국관광공사 · 의암호', url: 'https://korean.visitkorea.or.kr/detail/ms_detail.do?cotid=ba6ec2dc-92b7-4e6f-b16e-bc228d478611', routeIds: ['chuncheon-lake'] },
  { title: '춘천시 · 춘천여행ON', url: 'https://www.chuncheon.go.kr/tour/?Mcode=20511', routeIds: ['chuncheon-lake', 'chuncheon-soyang'] },
  { title: '한국관광공사 · 경포호와 주변 해변', url: 'https://korean.visitkorea.or.kr/detail/rem_detail.do?cotid=e373b9d9-247b-41fd-8c4b-79744f442596', routeIds: ['gangneung-coast', 'gangneung-lake'] },
  { title: '서울관광재단 · 북악산 야경', url: 'https://korean.visitseoul.net/nature/북악산-야경/KOP016324', routeIds: ['seoul-skyline'] },
  { title: '서울관광재단 · 서울숲', url: 'https://korean.visitseoul.net/area/서울숲/KOP001838', routeIds: ['seoul-forest'] },
  { title: '제주관광공사 · 애월해안도로', url: 'https://www.visitjeju.net/kr/detail/view?contentsid=CONT_000000000500425', routeIds: ['jeju-coast'] },
  { title: '제주관광공사 · 사려니숲길', url: 'https://visitjeju.net/kr/detail/view?contentsid=CONT_000000000500281&menuId=DOM_000001917002000000', routeIds: ['jeju-forest'] },
];

export const routes = [
  {
    id: 'chuncheon-lake', title: '의암호, 물빛을 따라', region: '춘천',
    description: '잔잔한 호수와 산 그림자가 번갈아 나타나는 길. 공지천에서 서면의 초록 풍경까지 이어지는 시연 코스입니다.',
    theme: 'lake', durationMin: 55, distanceKm: 23.4, sceneryScore: 96,
    tags: ['의암호', '물안개', '호반', '여유로운 오후'], image: '', source: 'curated-demo',
    coordinates: [[37.8723, 127.7134], [37.8615, 127.6991], [37.8451, 127.6823], [37.8377, 127.6749], [37.8541, 127.6689], [37.8752, 127.6711], [37.8932, 127.6758]],
    stops: [
      { id: 'gongjicheon', name: '공지천 수변', lat: 37.8723, lon: 127.7134, scene: 'lake', description: '호수의 넓은 수면을 닮은 느린 앰비언스로 출발합니다.' },
      { id: 'uiam-dam', name: '의암댐 주변', lat: 37.8377, lon: 127.6749, scene: 'lake', description: '산과 물이 만나는 풍경에 부드러운 음색을 더합니다.' },
      { id: 'seomyeon-green', name: '서면 호반길', lat: 37.8752, lon: 127.6711, scene: 'forest', description: '나무가 가까워지면 낮고 따뜻한 숲의 음색으로 바뀝니다.' },
      { id: 'animation-museum', name: '애니메이션박물관 주변', lat: 37.8932, lon: 127.6758, scene: 'lake', description: '다시 펼쳐지는 호수에서 차분하게 코스를 마무리합니다.' },
    ],
  },
  {
    id: 'chuncheon-soyang', title: '소양강의 느린 오후', region: '춘천',
    description: '소양강의 열린 물빛을 지나 소양댐 주변 산 풍경을 만나는 시연 코스입니다.',
    theme: 'lake', durationMin: 75, distanceKm: 31.2, sceneryScore: 92,
    tags: ['소양강', '소양댐', '호수', '산 풍경'], image: '', source: 'curated-demo',
    coordinates: [[37.8938, 127.7227], [37.9012, 127.7399], [37.9133, 127.7549], [37.9340, 127.7828], [37.9431, 127.8100]],
    stops: [
      { id: 'soyang-skywalk', name: '소양강스카이워크 주변', lat: 37.8938, lon: 127.7227, scene: 'lake', description: '강 위의 빛에서 영감을 받은 잔잔한 사운드입니다.' },
      { id: 'sinbuk', name: '신북의 들과 숲', lat: 37.9133, lon: 127.7549, scene: 'forest', description: '초록 풍경이 깊어지며 소리의 질감도 차분해집니다.' },
      { id: 'soyang-dam', name: '소양댐 주변', lat: 37.9431, lon: 127.8100, scene: 'lake', description: '넓어진 시야에 맞춰 맑고 길게 이어지는 음색을 재생합니다.' },
    ],
  },
  {
    id: 'gangneung-coast', title: '파도 옆, 강릉 바닷길', region: '강릉',
    description: '안목에서 강문과 사천 방향으로, 동해의 수평선과 해변의 소나무 풍경을 담은 시연 코스입니다.',
    theme: 'coast', durationMin: 65, distanceKm: 21.8, sceneryScore: 95,
    tags: ['동해', '안목', '강문', '바다', '해안'], image: '', source: 'curated-demo',
    coordinates: [[37.7729, 128.9475], [37.7877, 128.9277], [37.7966, 128.9174], [37.8177, 128.9016], [37.8402, 128.8772]],
    stops: [
      { id: 'anmok', name: '안목해변 주변', lat: 37.7729, lon: 128.9475, scene: 'coast', description: '열린 수평선에 가벼운 리듬이 어울립니다.' },
      { id: 'songjeong', name: '송정 해송길 주변', lat: 37.7877, lon: 128.9277, scene: 'forest', description: '해송을 닮은 따뜻한 음색이 잠시 흐릅니다.' },
      { id: 'sacheon', name: '사천해변 주변', lat: 37.8402, lon: 128.8772, scene: 'coast', description: '파도처럼 반복되는 멜로디로 바다 구간을 채웁니다.' },
    ],
  },
  {
    id: 'gangneung-lake', title: '경포호에 머무는 빛', region: '강릉',
    description: '경포대와 경포호 주변의 잔잔한 풍경에 집중하는 짧은 시연 코스입니다.',
    theme: 'lake', durationMin: 40, distanceKm: 12.6, sceneryScore: 89,
    tags: ['경포호', '경포대', '호수', '짧은 드라이브'], image: '', source: 'curated-demo',
    coordinates: [[37.7954, 128.8966], [37.8014, 128.8961], [37.8074, 128.9061], [37.7982, 128.9151]],
    stops: [
      { id: 'gyeongpo-wetland', name: '경포 가시연습지 주변', lat: 37.7954, lon: 128.8966, scene: 'forest', description: '습지의 초록을 따라 차분한 소리로 시작합니다.' },
      { id: 'gyeongpodae', name: '경포대 주변', lat: 37.8014, lon: 128.8961, scene: 'lake', description: '호수를 바라보는 구간에 느린 물빛 선율을 더합니다.' },
      { id: 'gyeongpo-east', name: '경포호 동쪽 주변', lat: 37.7982, lon: 128.9151, scene: 'lake', description: '일렁이는 수면을 닮은 소리와 함께 마무리합니다.' },
    ],
  },
  {
    id: 'seoul-skyline', title: '북악, 도시 위의 저녁', region: '서울',
    description: '북악산로 주변 숲과 서울의 전경을 번갈아 보는 시연 코스입니다. 실제 통행 상태는 반영하지 않습니다.',
    theme: 'city', durationMin: 45, distanceKm: 13.2, sceneryScore: 90,
    tags: ['북악', '서울 전경', '야경', '도시'], image: '', source: 'curated-demo',
    coordinates: [[37.5927, 126.9665], [37.5983, 126.9720], [37.6022, 126.9813], [37.6032, 126.9928], [37.5949, 126.9993]],
    stops: [
      { id: 'changuimun', name: '창의문 주변', lat: 37.5927, lon: 126.9665, scene: 'city', description: '도시의 리듬을 낮은 음량으로 부드럽게 시작합니다.' },
      { id: 'bugak-pavilion', name: '북악팔각정 주변', lat: 37.6022, lon: 126.9813, scene: 'city', description: '멀리 펼쳐지는 도시 풍경에 은은한 전자음을 더합니다.' },
      { id: 'seongbuk-green', name: '성북동 숲길 주변', lat: 37.5949, lon: 126.9993, scene: 'forest', description: '숲이 가까워지는 구간에서는 리듬을 덜어냅니다.' },
    ],
  },
  {
    id: 'seoul-forest', title: '서울숲에서 한강까지', region: '서울',
    description: '서울숲 외곽과 한강변 주변을 연결한 도시 속 초록 시연 코스입니다. 공원 내부 차량 진입을 뜻하지 않습니다.',
    theme: 'forest', durationMin: 35, distanceKm: 10.8, sceneryScore: 86,
    tags: ['서울숲', '한강', '초록', '도심 휴식'], image: '', source: 'curated-demo',
    coordinates: [[37.5434, 127.0445], [37.5398, 127.0354], [37.5330, 127.0468], [37.5310, 127.0674]],
    stops: [
      { id: 'seoul-forest-entrance', name: '서울숲 입구 주변', lat: 37.5434, lon: 127.0445, scene: 'forest', description: '나무 그늘을 닮은 앰비언스로 잠깐의 여유를 만듭니다.' },
      { id: 'seongsu-river', name: '성수대교 북단 주변', lat: 37.5398, lon: 127.0354, scene: 'city', description: '도시와 강이 만나는 장면에 부드러운 리듬이 흐릅니다.' },
      { id: 'ttukseom-river', name: '뚝섬한강공원 진입부', lat: 37.5310, lon: 127.0674, scene: 'lake', description: '넓은 강의 풍경에 차분한 수면의 음색을 재생합니다.' },
    ],
  },
  {
    id: 'jeju-coast', title: '애월, 수평선의 여유', region: '제주',
    description: '하귀에서 애월 방향으로 이어지는 해안 풍경에서 영감을 받은 시연 코스입니다.',
    theme: 'coast', durationMin: 60, distanceKm: 18.6, sceneryScore: 98,
    tags: ['애월', '하귀', '해안도로', '바다', '일몰'], image: '', source: 'curated-demo',
    coordinates: [[33.4851, 126.3976], [33.4750, 126.3762], [33.4721, 126.3532], [33.4661, 126.3377], [33.4654, 126.3195]],
    stops: [
      { id: 'hagwi', name: '하귀 해안 주변', lat: 33.4851, lon: 126.3976, scene: 'coast', description: '시야가 열리는 순간 산뜻한 해안 리듬을 재생합니다.' },
      { id: 'gueom', name: '구엄리 해안 주변', lat: 33.4721, lon: 126.3532, scene: 'coast', description: '파도와 검은 현무암의 대비를 가벼운 음색으로 표현합니다.' },
      { id: 'aewol', name: '애월항 주변', lat: 33.4654, lon: 126.3195, scene: 'coast', description: '수평선을 따라 이어진 음악이 여유롭게 마무리됩니다.' },
    ],
  },
  {
    id: 'jeju-forest', title: '삼나무 사이, 제주', region: '제주',
    description: '비자림로와 교래리 주변의 숲 풍경을 담은 시연 코스입니다. 사려니숲길 내부는 도보 구간이며 차량 경로가 아닙니다.',
    theme: 'forest', durationMin: 70, distanceKm: 26.5, sceneryScore: 94,
    tags: ['교래리', '비자림로', '삼나무', '사려니', '숲'], image: '', source: 'curated-demo',
    coordinates: [[33.4268, 126.6412], [33.4342, 126.6601], [33.4350, 126.6738], [33.4212, 126.6754], [33.4080, 126.6758]],
    stops: [
      { id: 'bijarim-road', name: '비자림로 삼나무길 주변', lat: 33.4268, lon: 126.6412, scene: 'forest', description: '길게 이어지는 나무의 선을 느린 음으로 표현합니다.' },
      { id: 'gyorae', name: '교래리 주변', lat: 33.4350, lon: 126.6738, scene: 'forest', description: '초록 풍경 속에서 낮고 따뜻한 화음이 이어집니다.' },
      { id: 'red-oreum-parking', name: '붉은오름 입구 주차장 주변', lat: 33.4080, lon: 126.6758, scene: 'forest', description: '숲길 산책은 주차 후 시작할 수 있습니다. 드라이브 사운드는 차분하게 마무리됩니다.' },
    ],
  },
];
