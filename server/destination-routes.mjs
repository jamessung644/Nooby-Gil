import { ApiError, buildKakaoUrl, normalizeKakao } from './providers.mjs';
import { getPreparedCatalog } from './android-catalog.mjs';
import { scenicSelectionSigner } from './scenic-photos.mjs';
import { GRID_BITS, analyzeRoute, describeEvidence, findCheckpoints, flagsAround } from './scenery-grid.mjs';
import { loadDriveCourses } from './drive-courses.mjs';

const SOURCE = 'Kakao Mobility';
const PLACE_SOURCE = '한국관광공사 TourAPI';
const SCENIC_THEMES = {
  all: '모두', coast: '바다·해안', forest: '숲·나무길', mountain: '산·전망',
  water: '강·호수', countryside: '들판·농촌', city: '도시 풍경',
};
const NO_ROUTE_CODES = new Set([1, 101, 102, 103, 104, 105, 106, 107]);
const object = value => value !== null && typeof value === 'object' && !Array.isArray(value);
const keysOnly = (value, keys) => object(value) && Object.keys(value).every(key => keys.includes(key));
const noControls = value => !/[\u0000-\u001f\u007f-\u009f]/.test(value);
const text = (value, max) => typeof value === 'string' && value.trim().length > 0 && value.length <= max && noControls(value);
const safeId = value => typeof value === 'string' && /^[A-Za-z0-9_-]{1,100}$/.test(value);
const radians = value => value * Math.PI / 180;
const point = ([lat, lon]) => ({ lat, lon });

function domesticCoordinate(value) {
  if (!object(value) || !Number.isFinite(value.lat) || !Number.isFinite(value.lon)) return false;
  const { lat, lon } = value;
  return (lat >= 33 && lat <= 38.65 && lon >= 124.5 && lon <= 129.8)
    || (lat >= 37.4 && lat <= 37.65 && lon >= 130.7 && lon <= 131.05)
    || (lat >= 37.15 && lat <= 37.35 && lon >= 131.75 && lon <= 131.95);
}
function distanceMeters(a, b) {
  const dlat = radians(b.lat - a.lat), dlon = radians(b.lon - a.lon);
  const h = Math.sin(dlat / 2) ** 2 + Math.cos(radians(a.lat)) * Math.cos(radians(b.lat)) * Math.sin(dlon / 2) ** 2;
  return 12742000 * Math.atan2(Math.sqrt(h), Math.sqrt(Math.max(0, 1 - h)));
}
function validateInput(input) {
  if (!keysOnly(input, ['origin', 'destination', 'extraMinutes', 'theme', 'scenicSelection', 'mode'])
    || !keysOnly(input.origin, ['lat', 'lon']) || !domesticCoordinate(input.origin)
    || !keysOnly(input.destination, ['id', 'name', 'address', 'lat', 'lon']) || !domesticCoordinate(input.destination)
    || !safeId(input.destination.id) || !text(input.destination.name, 120)
    || (input.destination.address !== undefined && (typeof input.destination.address !== 'string' || input.destination.address.length > 300 || !noControls(input.destination.address)))) {
    throw new ApiError('국내 출발 위치와 선택한 목적지 한 곳을 입력해 주세요.');
  }
  const { extraMinutes = 30, theme = 'all' } = input;
  if (!Number.isInteger(extraMinutes) || extraMinutes < 0 || extraMinutes > 120 || typeof theme !== 'string' || !Object.hasOwn(SCENIC_THEMES, theme)) {
    throw new ApiError(`추가 시간은 0~120분, 풍경은 ${Object.values(SCENIC_THEMES).join(', ')} 중 선택해 주세요.`);
  }
  if (input.mode !== undefined && input.mode !== 'photo-destination') throw new ApiError('지원하지 않는 경로 모드입니다.');
  if (input.mode === 'photo-destination' && input.scenicSelection === undefined) throw new ApiError('도착할 풍경 사진을 먼저 선택해 주세요.');
  if (input.mode !== 'photo-destination' && distanceMeters(input.origin, input.destination) < 50) throw new ApiError('출발지와 목적지가 너무 가깝습니다. 다른 목적지를 선택해 주세요.');
  const { id, name, address, lat, lon } = input.destination;
  return { origin: { ...input.origin }, destination: { id, name, ...(address === undefined ? {} : { address }), lat, lon }, extraMinutes, theme, scenicSelection: input.scenicSelection, mode: input.mode };
}
function keyFrom(env) {
  const key = typeof env.KAKAO_REST_API_KEY === 'string' ? env.KAKAO_REST_API_KEY.trim() : '';
  if (!key) throw new ApiError('Kakao REST API 키가 없어 목적지 검색·도로 조회를 할 수 없습니다.', 503);
  return key;
}
async function requestJson(url, { key, fetchImpl, deadline }) {
  const remaining = Math.min(12000, deadline - Date.now());
  if (remaining <= 0) throw new ApiError('도로 조회 시간이 초과되었습니다. 다시 시도해 주세요.', 504);
  const controller = new AbortController();
  let timer;
  try {
    return await Promise.race([
      (async () => {
        const response = await fetchImpl(url, { headers: { Authorization: `KakaoAK ${key}` }, signal: controller.signal, redirect: 'error' });
        if (!response.ok) throw new ApiError(response.status === 401 || response.status === 403
          ? '카카오 API 인증 또는 서비스 사용 권한을 확인해 주세요.' : '카카오 서비스가 요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.', 502);
        return await response.json();
      })(),
      new Promise((_resolve, reject) => { timer = setTimeout(() => { controller.abort(); reject(new ApiError('카카오 조회 시간이 초과되었습니다. 다시 시도해 주세요.', 504)); }, remaining); }),
    ]);
  } catch (error) {
    if (error instanceof ApiError) throw error;
    // Providers may include credentials in error messages. Never forward them.
    throw new ApiError('카카오 연결 또는 응답 형식을 확인하지 못했습니다. 다시 시도해 주세요.', 502);
  } finally { clearTimeout(timer); }
}

export async function searchDestinations(query, { env = process.env, fetchImpl = fetch } = {}) {
  if (!text(query, 80)) throw new ApiError('목적지 검색어를 1~80자로 입력해 주세요.');
  const key = keyFrom(env);
  const url = new URL('https://dapi.kakao.com/v2/local/search/keyword.json');
  url.search = new URLSearchParams({ query: query.trim(), size: '8', sort: 'accuracy' });
  const data = await requestJson(url, { key, fetchImpl, deadline: Date.now() + 12000 });
  if (!Array.isArray(data?.documents)) throw new ApiError('목적지 검색 응답 형식이 올바르지 않습니다.', 502);
  const ids = new Set(), places = [];
  for (const item of data.documents) {
    if (!object(item) || !safeId(item.id) || ids.has(item.id) || !text(item.place_name, 120)
      || !text(item.x, 40) || !text(item.y, 40)) continue;
    const lat = Number(item.y), lon = Number(item.x);
    if (!domesticCoordinate({ lat, lon })) continue;
    ids.add(item.id);
    const address = [item.road_address_name, item.address_name].find(value => typeof value === 'string' && value.trim() && noControls(value)) || '';
    places.push({ id: item.id, name: item.place_name, address: address.slice(0, 300), lat, lon });
    if (places.length === 8) break;
  }
  return { places, source: 'Kakao Local' };
}

// Local projection is used only for candidate filtering, never as a road route.
function project(value, reference) {
  return { x: radians(value.lon - reference.lon) * 6371000 * Math.cos(radians(reference.lat)), y: radians(value.lat - reference.lat) * 6371000 };
}
function nearPolyline(value, coordinates, reference) {
  const p = project(value, reference);
  let distance = Infinity, progress = 0, walked = 0, total = 0;
  for (let i = 1; i < coordinates.length; i++) {
    const a = project(point(coordinates[i - 1]), reference), b = project(point(coordinates[i]), reference);
    const x = b.x - a.x, y = b.y - a.y, length = Math.hypot(x, y);
    const t = length ? Math.max(0, Math.min(1, ((p.x - a.x) * x + (p.y - a.y) * y) / (length * length))) : 0;
    const d = Math.hypot(p.x - a.x - t * x, p.y - a.y - t * y);
    if (d < distance) { distance = d; progress = walked + t * length; }
    walked += length; total += length;
  }
  return { distance, progress: total ? progress / total : 0 };
}
// Match an ordered sequence of reaches, not each stop's nearest/first occurrence:
// the road may pass a later stop before returning to it after an earlier stop.
function reachesInOrder(stops, coordinates, reference, radius) {
  let segment = 1, progress = 0;
  for (const stop of stops) {
    const p = project(stop, reference);
    let reached = false;
    for (; segment < coordinates.length; segment++, progress = 0) {
      const a = project(point(coordinates[segment - 1]), reference), b = project(point(coordinates[segment]), reference);
      const ax = a.x - p.x, ay = a.y - p.y, dx = b.x - a.x, dy = b.y - a.y, lengthSquared = dx * dx + dy * dy;
      if (lengthSquared === 0) {
        if (ax * ax + ay * ay <= radius * radius) { reached = true; break; }
        continue;
      }
      const center = -(ax * dx + ay * dy) / lengthSquared;
      const closestX = ax + center * dx, closestY = ay + center * dy;
      const margin = radius * radius - closestX * closestX - closestY * closestY;
      if (margin < 0) continue;
      const reach = Math.sqrt(margin / lengthSquared), entry = Math.max(progress, center - reach);
      if (entry <= 1 && entry <= center + reach) { progress = entry; reached = true; break; }
    }
    if (!reached) return false;
  }
  return true;
}
function scenery(title) {
  // The prepared catalog exposes titles, not source classification. These are
  // conservative scene hints; road reachability and detour budgets are checked later.
  // Hiking-only, offshore and indoor locations cannot establish a drive-through scene.
  if (/정상|등산|탐방|둘레길|산책|스카이워크|출렁다리|등대|터널|야영|캠핑|기찻길/.test(title)
    || /박물관|미술관|전시관|홍보관|체험관|문화관|기념관|과학관|도서관|천문대|타워|수족관|아쿠아리움|스파|온천(?!천)|사우나|실내|스튜디오|키즈카페|쇼핑|백화점|공항/.test(title)
    || /유람선|여객선|선착장|무인도|해상/.test(title)
    // Amusement and commercial places are a destination in themselves, not a stretch of road to drive.
    || /테마\s*(?:파크|공원|랜드|월드|마을|타운|농원)|유원지|놀이공원|워터파크|동물원|리조트|펜션|골프|스키장|썰매|카페|맛집|식당/.test(title)
    || /(?:^|[\s(])(?:가파도|마라도|우도|울릉도|독도|홍도|흑산도|백령도|추자도|소매물도)(?:[\s)]|$)/.test(title)) return [];
  const themes = [];
  const coastal = /해변|해수욕장|해안|바다|포구|방파제|항구|대포항|궁평항광장/.test(title);
  // The slow-city tourism designation alone does not establish an urban scene.
  const urbanTitle = title.replace(/슬로\s*시티/g, '');
  const urban = /도심|도시공원|도시숲|도시자연공원|시티|야경|문화\s*(?:의\s*)?거리|예술\s*(?:의\s*)?거리|한강(?:시민)?공원|중앙공원|시민공원|광장|대교(?=$|[\s(])/.test(urbanTitle);
  if (coastal) themes.push('coast');
  if (/숲|수목원|휴양림|삼림|산림|국가정원|식물원|가로수길|메타세쿼이아|메타쉐콰이어|은행나무길|벚꽃길/.test(title)) themes.push('forest');
  // Never match individual 산/강/밭/재 syllables: 부산, 강남, 한밭 and
  // historic buildings such as 도곡재 are not evidence of those landscapes.
  // An explicit coast/city viewpoint belongs to that scene. A generic viewpoint
  // remains only a title-based hint, not proof of a mountain or a visible panorama.
  if (!coastal && !urban && /전망대|전망공원|전망쉼터|산자락|산악도로|고갯길|보발재|말티재|바람재쉼터|너릿재옛길|북악스카이/.test(title)) themes.push('mountain');
  if (/호수|저수지|호반|수변|강변|한강|낙동강|섬진강|태화강|영산강|소양강|온천천|대청댐|대청호|광주호|고천암호|간월호/.test(title)
    || /금강(?=$|[\s(]|하구|하굿둑|호(?:\s|$)|생태|습지|신관공원|로하스|3공구)/.test(title)) themes.push('water');
  if (/농촌|경관농업|들판|논밭|평야|초원|청보리|보리밭|메밀밭|차밭|꽃밭|수국밭|억새|목장(?!터)/.test(title)
    && !/바다목장/.test(title)) themes.push('countryside');
  if (urban) themes.push('city');
  return themes;
}

// A title is only a hint. When the offline OSM grid is available, a coast, lake/river, forest or
// mountain scene must also be found around the place itself; scenes the grid cannot judge
// (countryside, city) keep the title hint. Missing grid: the title decides, as before.
const OSM_SCENE_BITS = { coast: GRID_BITS.coast, water: GRID_BITS.water, forest: GRID_BITS.forest, mountain: GRID_BITS.mountain | GRID_BITS.viewpoint };
function confirmScenes(place, themes, grid) {
  if (!grid) return { themes, confirmed: 0 };
  const flags = flagsAround(place, grid, 2);
  const kept = themes.filter(theme => !(theme in OSM_SCENE_BITS) || flags & OSM_SCENE_BITS[theme]);
  return { themes: kept, confirmed: kept.filter(theme => theme in OSM_SCENE_BITS).length };
}
// A requested scene outranks an 'all' match, and extra scene tags add a small bonus.
// This ranks title-based hints only; it is not a measure of the road's actual view.
const sceneValue = (themes, theme) => (theme === 'all' ? 2 : 3) + Math.min(1, (themes.length - 1) * 0.5);

export function selectScenicCandidates({ origin, destination, theme, fast, extraMinutes = 30, limit = 3 }, catalog = getPreparedCatalog(), grid = null) {
  const direct = distanceMeters(origin, destination);
  const target = project(destination, origin), denominator = target.x ** 2 + target.y ** 2;
  if (direct < 500 || !denominator) return [];
  // The reach grows with the extra time the user allows; 30 minutes is the reference
  // and the reach never shrinks below it or grows past four times it.
  const reach = Math.min(4, Math.max(1, extraMinutes / 30));
  const corridor = Math.min(4000 * reach, Math.max(750, fast.distanceKm * 150));
  const seen = new Set(), candidates = [];
  for (const place of catalog.places) {
    if (!object(place) || !safeId(place.id) || seen.has(place.id) || !domesticCoordinate(place) || !text(place.title, 200)) continue;
    const { themes, confirmed } = confirmScenes(place, scenery(place.title), grid);
    if (!themes.length || (theme !== 'all' && !themes.includes(theme))) continue;
    // For places with several scenes, keep the requested theme first in labels.
    const selectedTheme = theme === 'all' ? themes[0] : theme;
    // Scenes the grid also found beside the place count a little more than a title alone.
    const value = sceneValue(themes, theme) + (confirmed ? 0.5 : 0);
    const fromStart = distanceMeters(origin, place), toEnd = distanceMeters(place, destination);
    if (fromStart < 500 || toEnd < 500 || fromStart + toEnd - direct > Math.min(10000 * reach, Math.max(1500, direct * 0.4))) continue;
    const xy = project(place, origin), forward = (xy.x * target.x + xy.y * target.y) / denominator;
    // Explicitly exclude places behind the start or past the chosen destination.
    if (forward < 0.1 || forward > 0.9) continue;
    const near = nearPolyline(place, fast.coordinates, origin);
    if (near.distance > corridor || near.distance < 150 || near.progress < 0.08 || near.progress > 0.92) continue;
    seen.add(place.id);
    candidates.push({ id: place.id, name: place.title, lat: place.lat, lon: place.lon, theme: selectedTheme,
      themes: [selectedTheme, ...themes.filter(value => value !== selectedTheme)], source: PLACE_SOURCE,
      baselineDistance: near.distance, progress: near.progress, value,
      score: near.distance + (fromStart + toEnd - direct) * 0.5 - value * 300 * reach });
  }
  return candidates.sort((a, b) => a.score - b.score || a.id.localeCompare(b.id)).slice(0, limit);
}

// Road-side scenery gained over the fast road, in the same units as a stop's scenery value (3-4).
// In the test against curated drive courses the coastal share separated them from ordinary roads in
// 88% of pairs, tunnels (fewer) and viewpoints (more) moderately; other terrains were not separated,
// so only the coast, tunnels and viewpoints count. Missing measurements add nothing.
const COAST_THEMES = new Set(['coast', 'all']);
const MOUNTAIN_THEMES = new Set(['mountain', 'forest']);
export function sceneryBonus(stats, fastStats, theme) {
  if (!stats || !fastStats) return 0;
  let bonus = 0;
  if (COAST_THEMES.has(theme)) bonus += Math.min(3, Math.max(0, stats.coastKm - fastStats.coastKm) * 0.1);
  // Mountain relief separated 18 non-coastal courses from ordinary roads in 65% of pairs (80% with the
  // first 8 courses, so the early figure was too optimistic). It counts for less than the coast (83%).
  if (MOUNTAIN_THEMES.has(theme)) bonus += Math.min(1.5, Math.max(0, (stats.mountainKm ?? 0) - (fastStats.mountainKm ?? 0)) * 0.04);
  bonus += Math.min(0.5, Math.max(0, fastStats.tunnelKm - stats.tunnelKm) * 0.02);
  bonus -= Math.min(1, Math.max(0, stats.tunnelKm - fastStats.tunnelKm) * 0.03);
  // Viewpoints separated courses from ordinary roads in 67-73% of pairs, so they count a little more than before.
  bonus += Math.min(0.8, Math.max(0, stats.viewpoints - fastStats.viewpoints) * 0.15);
  return bonus;
}
// Recommended-road candidates: a stretch of a published drive course that lies along the way.
// Two stops of one course (at most the two the native navigation accepts), in the course's own
// direction of travel, with the first reached before the second on the way to the destination.
const COURSE_THEMES = { all: null, coast: 'coast', water: 'water' };
export function selectCuratedCandidates({ origin, destination, theme, fast, extraMinutes = 30, limit = 3 }, courses = loadDriveCourses()) {
  const direct = distanceMeters(origin, destination);
  const target = project(destination, origin), denominator = target.x ** 2 + target.y ** 2;
  if (direct < 500 || !denominator || !Array.isArray(courses)) return [];
  const reach = Math.min(4, Math.max(1, extraMinutes / 30));
  const corridor = Math.min(4000 * reach, Math.max(750, fast.distanceKm * 150));
  const detourLimit = Math.min(10000 * reach, Math.max(1500, direct * 0.4));
  // Themes without a matching course kind (forest, mountain...) are served by the 'other' courses.
  const wanted = theme in COURSE_THEMES ? COURSE_THEMES[theme] : 'other';
  const best = [];
  for (const course of courses) {
    if (wanted && course.terrain !== wanted) continue;
    const annotated = course.stops.map((stop, index) => {
      if (!domesticCoordinate(stop)) return null;
      const xy = project(stop, origin), forward = (xy.x * target.x + xy.y * target.y) / denominator;
      const near = nearPolyline(stop, fast.coordinates, origin);
      if (forward < 0.05 || forward > 0.95 || near.distance > corridor || near.progress < 0.05 || near.progress > 0.95) return null;
      return { id: `${course.id}-${index + 1}`, name: stop.name, lat: stop.lat, lon: stop.lon, source: PLACE_SOURCE, progress: near.progress, baselineDistance: near.distance };
    });
    let pick = null;
    for (const direction of [1, -1]) {
      const sequence = direction === 1 ? annotated : annotated.slice().reverse();
      for (let i = 0; i < sequence.length; i++) for (let j = i + 1; j < sequence.length; j++) {
        const a = sequence[i], b = sequence[j];
        if (!a || !b || a.progress >= b.progress) continue;
        const span = distanceMeters(a, b);
        const detour = distanceMeters(origin, a) + span + distanceMeters(b, destination) - direct;
        if (span < 2000 || detour > detourLimit) continue;
        const score = Math.min(span, 30000) - detour - 0.5 * Math.max(a.baselineDistance, b.baselineDistance);
        if (!pick || score > pick.score) pick = { course: { id: course.id, title: course.title }, stops: [a, b], span, score };
      }
    }
    if (pick) best.push(pick);
  }
  return best.sort((a, b) => b.score - a.score || a.course.id.localeCompare(b.course.id)).slice(0, limit);
}

// Places where a clip is worth recording, in driving order: the chosen places and the longest
// stretches of coast, water and mountain. At most six, ids unique, every field short.
/** "동해안일주해안도로(삼척), 등뼈를 타고 달리다!" becomes "동해안일주해안도로(삼척)". */
function shortCourseTitle(title) {
  const head = title.split(/[,!?:·]/)[0].trim();
  const base = head.length >= 4 ? head : title;
  return base.length > 24 ? `${base.slice(0, 23)}…` : base;
}
const MAX_CHECKPOINTS = 6;
function routeCheckpoints(route, stops, grid) {
  try {
    const places = stops.map(stop => ({ id: stop.id, kind: 'place', name: String(stop.name).slice(0, 80), lat: stop.lat, lon: stop.lon, endLat: stop.lat, endLon: stop.lon, km: 0 }));
    const merged = [...places, ...findCheckpoints(route.coordinates, grid, { limit: MAX_CHECKPOINTS - places.length })];
    const order = item => nearPolyline(item, route.coordinates, route.coordinates[0] && point(route.coordinates[0])).progress;
    return merged.slice(0, MAX_CHECKPOINTS).sort((a, b) => order(a) - order(b));
  } catch { return []; }
}

const measure = (route, grid) => { try { return analyzeRoute(route.coordinates, grid); } catch { return null; } };

// `waypoint` is null, one stop, or an ordered array of stops.
async function roadRoute(origin, destination, waypoint, options) {
  const via = waypoint ? [].concat(waypoint) : [];
  const url = buildKakaoUrl({ stops: [origin, ...via, destination] });
  // Official https://developers.kakaomobility.com/guide/navi-api/directions
  // 'motorway' means 자동차 전용 도로. This requests avoidance, not a guarantee
  // that every motorway is absent; the public geometry carries no road class.
  url.searchParams.set('priority', 'TIME');
  url.searchParams.set('avoid', via.length && !options.allowMotorway ? 'motorway|ferries' : 'ferries');
  const data = await requestJson(url, options);
  if ((via.length || options.exactDestination) && NO_ROUTE_CODES.has(data?.routes?.[0]?.result_code)) return null;
  const route = normalizeKakao(data);
  const geometryLength = route.coordinates.reduce((sum, coordinate, index, coordinates) => index ? sum + distanceMeters(point(coordinates[index - 1]), point(coordinate)) : sum, 0);
  if (route.coordinates.some(coordinate => !domesticCoordinate(point(coordinate)))
    || geometryLength > route.distanceKm * 1250 + 1000
    || route.coordinates.length > 50000 || route.distanceKm <= 0 || route.distanceKm >= 1500
    || route.durationMin <= 0 || route.durationMin > 720
    || distanceMeters(origin, point(route.coordinates[0])) > 1000
    || (!options.exactDestination && distanceMeters(destination, point(route.coordinates.at(-1))) > 1000)) {
    throw new ApiError('선택한 출발지·목적지에 맞는 도로 경로를 확인하지 못했습니다.', 502);
  }
  if (!reachesInOrder(via, route.coordinates, origin, options.exactSelection ? 150 : 700)) return null;
  if (options.exactDestination && (distanceMeters(destination, point(route.coordinates.at(-1))) > 150
    || nearPolyline(destination, route.coordinates, origin).distance > 150)) return null;
  return { ...route, durationSeconds: data.routes[0].summary.duration };
}

// Road-side evidence from the offline scenery grid. It only describes the chosen road;
// it never alters which road is chosen, and a missing grid leaves the response unchanged.
function roadEvidence(scenic, fast, theme) {
  try {
    if (!scenic || !fast) return { labels: [], sentence: '', evidence: undefined };
    const { labels, sentence } = describeEvidence(scenic, fast);
    // Say so when a coastal request did not gain meaningfully more coast than the fast road.
    const thin = theme === 'coast' && scenic.coastKm - fast.coastKm < 3 ? ' 빠른 길보다 해안 구간이 크게 늘지는 않습니다.' : '';
    return { labels, sentence: sentence + thin, evidence: { source: scenic.source, scenic, fast } };
  } catch { return { labels: [], sentence: '', evidence: undefined }; }
}

// `scenery` is the offline scenery grid. Without it the road is chosen by place scenery alone.
export async function planDestinationRoutes(input, { env = process.env, fetchImpl = fetch, catalog, selectionSigner = scenicSelectionSigner, scenery = null, courses = [] } = {}) {
  const { origin, destination, extraMinutes, theme, scenicSelection, mode } = validateInput(input);
  // Verify before making any provider request. Expired/tampered tokens never
  // fall back to a different scenic place or to a client-supplied coordinate.
  const selected = scenicSelection === undefined ? null : selectionSigner.verify(scenicSelection, destination);
  const options = { key: keyFrom(env), fetchImpl, deadline: Date.now() + 50000 };
  // A chosen scenery photo can be the final destination (photo-destination). The road to it is
  // the fast road; a published drive course or a scenic road to that same place is offered next to
  // it only when the client explicitly sends extra minutes, and every road must still end within 150 m.
  const photo = mode === 'photo-destination';
  let goal = destination, photoName = '';
  if (photo) {
    const { id, name, address, lat, lon } = selected;
    goal = { id, name, ...(address === undefined ? {} : { address }), lat, lon };
    photoName = name;
    if (distanceMeters(origin, goal) < 50) throw new ApiError('이미 선택한 사진 장소 가까이에 있습니다. 다른 사진 장소를 선택해 주세요.', 422);
  }
  const opts = photo ? { ...options, exactDestination: true } : options;
  const extra = photo && input.extraMinutes === undefined ? 0 : extraMinutes;
  // The region is a search anchor only. Never route back to it after the photo.
  const baselineRoute = await roadRoute(origin, goal, null, opts);
  if (!baselineRoute) throw new ApiError('선택한 사진 장소 150m 이내에 도착하는 자동차 경로를 확인하지 못했습니다. 지역 중심이나 다른 장소로 바꾸지 않았어요.', 422);
  const { durationSeconds: baselineSeconds, ...baseline } = baselineRoute;
  const fast = photo
    ? { ...baseline, id: 'fast', title: '사진 장소로 가는 길', scenicLabels: [photoName], waypoints: [],
      reason: `선택한 ${photoName}을 최종 목적지로 안내합니다. 지역 탐색 기준점으로 돌아가지 않습니다. 사진 촬영 지점·차창 조망은 미확인입니다.` }
    : { ...baseline, id: 'fast', title: '빠른 경로', scenicLabels: [], waypoints: [], reason: '카카오의 시간 우선 옵션으로 조회한 목적지까지의 도로 경로입니다.' };
  const response = (scenicStatus, message, scenic) => ({ ...(photo ? { mode, requestedDestination: destination } : {}), origin, destination: goal,
    routes: scenic ? [fast, ...[].concat(scenic)] : [fast], scenicStatus, message });
  if (extra === 0) return photo
    ? response('ok', '선택한 사진의 관광 장소로 갑니다. 도로 끝점이 장소 좌표 150m 이내인지 확인했습니다. 좌표는 대표 장소 위치이며 정확한 촬영 지점이나 주차장 입구가 아닙니다.')
    : response('disabled', '추가 시간이 0분이어서 빠른 경로만 표시합니다.');
  if (selected && !photo) {
    let route;
    try { route = await roadRoute(origin, destination, selected, { ...options, exactSelection: true }); }
    catch { return response('provider_unavailable', '선택한 사진 장소의 도로 정보를 확인하지 못해 빠른 경로만 표시합니다. 다른 장소로 바꾸지 않았어요.'); }
    if (!route) return response('no_feasible_route', '선택한 사진 장소 150m 이내로 접근하는 도로를 확인하지 못해 빠른 경로만 표시합니다. 다른 장소로 바꾸지 않았어요.');
    if (route.durationSeconds > baselineSeconds + extraMinutes * 60) return response('no_feasible_route', '선택한 사진 장소를 경유하면 허용한 추가 시간을 넘어 빠른 경로만 표시합니다. 추가 시간을 늘려 다시 비교할 수 있어요.');
    const { durationSeconds, ...publicRoute } = route;
    const { id, name, lat, lon, source } = selected;
    return response('ok', '선택한 사진 장소를 경유한 뒤 원래 목적지로 갑니다. 장소 좌표와 150m 이내 도로 접근을 확인했으며 촬영 지점·차창 조망을 보장하지 않습니다.', {
      ...publicRoute, id: 'scenic', title: '선택한 사진 경유', scenicLabels: [name],
      waypoints: [{ id, name, lat, lon, source }],
      reason: `선택한 ${name} 인근 도로를 경유해 같은 목적지로 갑니다. 사진의 실제 촬영 위치와 차창 조망은 미확인입니다.`,
    });
  }
  const budgetSeconds = extra * 60;
  const withinBudget = route => route.durationSeconds <= baselineSeconds + budgetSeconds && route.distanceKm <= fast.distanceKm * 1.6 + 3;
  const distinctPath = route => !(route.coordinates.length === fast.coordinates.length && route.coordinates.every((p, i) => p[0] === fast.coordinates[i][0] && p[1] === fast.coordinates[i][1]));
  // Avoiding every 자동차 전용 도로 often exceeds the budget between cities, so one more
  // lookup may allow them. The same budget and approach checks still apply to that road.
  const scenicRoad = async stops => {
    const route = await roadRoute(origin, goal, stops, opts);
    if (!route || withinBudget(route)) return route;
    const mixed = await roadRoute(origin, goal, stops, { ...opts, allowMotorway: true });
    return mixed && { ...mixed, motorway: true };
  };
  const fastStats = scenery ? measure(fast, scenery) : null;

  // A published drive course is offered next to the computed road, never blended into its score.
  let curated = null;
  for (const match of selectCuratedCandidates({ origin, destination: goal, theme, fast, extraMinutes: extra }, courses)) {
    let route;
    try { route = await scenicRoad(match.stops); } catch { break; }
    if (!route || !withinBudget(route) || !distinctPath(route)) continue;
    const { durationSeconds, motorway, ...publicRoute } = route;
    const [from, to] = match.stops.map(stop => stop.name);
    const stats = fastStats ? measure(route, scenery) : null;
    const { labels: evidenceLabels, sentence } = roadEvidence(stats, fastStats, theme);
    const short = shortCourseTitle(match.course.title);
    const baseReason = `관광공사 소개 코스 ‘${short}’의 ${from} → ${to} 구간입니다. 코스 전체가 아니라 가는 길에 겹치는 일부입니다.`
      + (motorway ? ' 일부 자동차 전용도로를 포함합니다.' : '');
    curated = {
      ...publicRoute, id: 'curated', title: '추천 코스 길', headline: `${short} 일부 · ${from} → ${to}`.slice(0, 120),
      scenicLabels: [...new Set(['추천 코스', from, to, ...evidenceLabels])].slice(0, 8),
      waypoints: match.stops.map(({ id, name, lat, lon, source }) => ({ id, name, lat, lon, source })),
      ...(scenery ? { checkpoints: routeCheckpoints(route, match.stops, scenery) } : {}),
      reason: sentence && baseReason.length + 1 + sentence.length <= 500 ? `${baseReason} ${sentence}` : baseReason,
    };
    break;
  }
  // The computed road follows; a recommended road alone still makes the answer useful.
  const withCurated = (status, message, scenic) => curated
    ? response('ok', scenic ? '추천 코스 길과 풍경 길을 같은 목적지로 제안합니다. 추천 코스는 소개된 일부 구간이며 조망·현장 통행은 보장하지 않습니다.'
      : '추천 코스 길을 제안합니다. 소개된 코스의 일부 구간이며 조망·현장 통행은 보장하지 않습니다.', [curated, scenic].filter(Boolean))
    : response(status, message, scenic);

  let candidates;
  // With measurements, more places are tried as roads and ranked by the scenery along each road.
  try { candidates = selectScenicCandidates({ origin, destination: goal, theme, fast, extraMinutes: extra, limit: fastStats ? 5 : 3 }, catalog, scenery); }
  catch { return withCurated('provider_unavailable', '풍경 기초정보를 확인하지 못해 빠른 경로만 표시합니다.'); }
  if (!candidates.length) return withCurated('no_candidates', `목적지로 가는 길 주변에서 ${theme === 'all' ? '조건에 맞는' : `선택한 ‘${SCENIC_THEMES[theme]}’`} 풍경 통과 지점을 찾지 못해 빠른 경로만 표시합니다.`);
  // Require a real approach toward every scenery stop, independent of polyline sampling.
  const approaches = (stops, route) => stops.every(stop => nearPolyline(stop, route.coordinates, origin).distance <= stop.baselineDistance - 100);
  // Scenery value gained per extra drive time; a second stop counts less than the first.
  // A road that had to allow 자동차 전용 도로 to fit the time budget scores lower.
  const efficiency = ({ stops, route, stats }) => stops.reduce((sum, stop, i) => sum + stop.value * (i ? 0.6 : 1), 0)
    - 1.5 * Math.max(0, route.durationSeconds - baselineSeconds) / budgetSeconds - (route.motorway ? 0.5 : 0)
    + sceneryBonus(stats, fastStats, theme);
  let providerFailed = false;
  const found = [];
  for (const candidate of candidates) {
    let route;
    try { route = await scenicRoad(candidate); }
    catch { providerFailed = true; break; }
    if (!route || !withinBudget(route) || !approaches([candidate], route) || !distinctPath(route)) continue;
    found.push({ stops: [candidate], route, stats: fastStats ? measure(route, scenery) : null });
  }
  found.sort((a, b) => efficiency(b) - efficiency(a));
  let best = found[0];
  // Two separate scenes need real spacing along the way. The destination limit of two
  // stops matches what the native navigation can receive, so never exceed it.
  if (!providerFailed && found.length >= 2) {
    const pair = [found[0].stops[0], found[1].stops[0]].sort((a, b) => a.progress - b.progress);
    if (pair[1].progress - pair[0].progress >= 0.1 && distanceMeters(pair[0], pair[1]) >= 1000) {
      try {
        const route = await scenicRoad(pair);
        if (route && withinBudget(route) && approaches(pair, route) && distinctPath(route)) {
          const both = { stops: pair, route, stats: fastStats ? measure(route, scenery) : null };
          if (efficiency(both) > efficiency(best)) best = both;
        }
      } catch { /* A failed pair lookup keeps the already verified single-stop route. */ }
    }
  }
  if (best) {
    const { stops } = best;
    const { durationSeconds, motorway, ...publicRoute } = best.route;
    const names = [...new Set(stops.map(stop => stop.name))];
    const sceneLabels = [...new Set(stops.flatMap(stop => stop.themes.map(value => SCENIC_THEMES[value])))].slice(0, 8 - names.length);
    const { labels: evidenceLabels, sentence, evidence } = roadEvidence(best.stats, fastStats, theme);
    const baseReason = `${names.join(', ')} 인근을 ${stops.length > 1 ? '차례로 ' : ''}지나 같은 목적지로 갑니다.`
      + (motorway ? ' 일부 자동차 전용도로를 포함합니다.' : '');
    // Keep the contract limits: at most 8 labels and 500 characters of reason text.
    const labels = [...new Set([...sceneLabels, ...names, ...evidenceLabels])].slice(0, 8);
    const scenicMessage = curated
      ? '추천 코스 길과 풍경 길을 같은 목적지로 제안합니다. 추천 코스는 소개된 일부 구간, 풍경 길은 인근 도로 통과 기준이며 조망·현장 통행은 보장하지 않습니다.'
      : '두 경로는 같은 목적지로 갑니다. 풍경 경로는 인근 도로 통과 기준이며 조망·현장 통행은 보장하지 않습니다.';
    return response('ok', scenicMessage, [...(curated ? [curated] : []), {
      ...publicRoute, id: 'scenic', title: stops.length > 1 ? '풍경 경로 · 2곳 경유' : '풍경 경로',
      headline: `${names.join(' → ')} 경유`.slice(0, 120),
      scenicLabels: labels,
      waypoints: stops.map(({ id, name, lat, lon, source }) => ({ id, name, lat, lon, source })),
      ...(scenery ? { checkpoints: routeCheckpoints(publicRoute, stops, scenery) } : {}),
      reason: sentence && baseReason.length + 1 + sentence.length <= 500 ? `${baseReason} ${sentence}` : baseReason,
      ...(evidence ? { evidence } : {}),
    }]);
  }
  return withCurated(providerFailed ? 'provider_unavailable' : 'no_feasible_route', providerFailed
    ? '풍경 경로의 도로 정보를 확인하지 못해 빠른 경로만 표시합니다. 잠시 후 다시 시도해 주세요.'
    : '추가 시간 안에 이용할 수 있는 다른 풍경 경로를 확인하지 못해 빠른 경로만 표시합니다.');
}
