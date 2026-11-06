import { ApiError, buildKakaoUrl, normalizeKakao } from './providers.mjs';
import { getPreparedCatalog } from './android-catalog.mjs';
import { scenicSelectionSigner } from './scenic-photos.mjs';

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
  if (!keysOnly(input, ['origin', 'destination', 'extraMinutes', 'theme', 'scenicSelection'])
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
  if (distanceMeters(input.origin, input.destination) < 50) throw new ApiError('출발지와 목적지가 너무 가깝습니다. 다른 목적지를 선택해 주세요.');
  const { id, name, address, lat, lon } = input.destination;
  return { origin: { ...input.origin }, destination: { id, name, ...(address === undefined ? {} : { address }), lat, lon }, extraMinutes, theme, scenicSelection: input.scenicSelection };
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
function scenery(title) {
  // The prepared catalog exposes titles, not source classification. These are
  // conservative scene hints; road reachability and detour budgets are checked later.
  // Hiking-only, offshore and indoor locations cannot establish a drive-through scene.
  if (/정상|등산|탐방|둘레길|산책|스카이워크|출렁다리|등대|터널|야영|캠핑|기찻길/.test(title)
    || /박물관|미술관|전시관|홍보관|체험관|문화관|기념관|과학관|도서관|천문대|타워|수족관|아쿠아리움|스파|온천(?!천)|사우나|실내|스튜디오|키즈카페|쇼핑|백화점|공항/.test(title)
    || /유람선|여객선|선착장|무인도|해상/.test(title)
    || /(?:^|[\s(])(?:가파도|마라도|우도|울릉도|독도|홍도|흑산도|백령도|추자도|소매물도)(?:[\s)]|$)/.test(title)) return [];
  const themes = [];
  const coastal = /해변|해수욕장|해안|바다|포구|방파제|항구|대포항|궁평항광장/.test(title);
  // The slow-city tourism designation alone does not establish an urban scene.
  const urbanTitle = title.replace(/슬로\s*시티/g, '');
  const urban = /도심|도시공원|도시숲|도시자연공원|시티|야경|문화\s*(?:의\s*)?거리|예술\s*(?:의\s*)?거리|한강(?:시민)?공원|중앙공원|시민공원|광장|대교(?=$|[\s(])/.test(urbanTitle);
  if (coastal) themes.push('coast');
  if (/숲|수목원|휴양림|삼림|산림|정원|가로수길|메타세쿼이아|메타쉐콰이어|은행나무길|벚꽃길/.test(title)) themes.push('forest');
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

export function selectScenicCandidates({ origin, destination, theme, fast }, catalog = getPreparedCatalog()) {
  const direct = distanceMeters(origin, destination);
  const target = project(destination, origin), denominator = target.x ** 2 + target.y ** 2;
  if (direct < 500 || !denominator) return [];
  const corridor = Math.min(4000, Math.max(750, fast.distanceKm * 150));
  const seen = new Set(), candidates = [];
  for (const place of catalog.places) {
    if (!object(place) || !safeId(place.id) || seen.has(place.id) || !domesticCoordinate(place) || !text(place.title, 200)) continue;
    const themes = scenery(place.title);
    if (!themes.length || (theme !== 'all' && !themes.includes(theme))) continue;
    // For places with several scenes, keep the requested theme first in labels.
    const selectedTheme = theme === 'all' ? themes[0] : theme;
    const fromStart = distanceMeters(origin, place), toEnd = distanceMeters(place, destination);
    if (fromStart < 500 || toEnd < 500 || fromStart + toEnd - direct > Math.min(10000, Math.max(1500, direct * 0.4))) continue;
    const xy = project(place, origin), forward = (xy.x * target.x + xy.y * target.y) / denominator;
    // Explicitly exclude places behind the start or past the chosen destination.
    if (forward < 0.1 || forward > 0.9) continue;
    const near = nearPolyline(place, fast.coordinates, origin);
    if (near.distance > corridor || near.distance < 150 || near.progress < 0.08 || near.progress > 0.92) continue;
    seen.add(place.id);
    candidates.push({ id: place.id, name: place.title, lat: place.lat, lon: place.lon, theme: selectedTheme,
      themes: [selectedTheme, ...themes.filter(value => value !== selectedTheme)], source: PLACE_SOURCE,
      baselineDistance: near.distance, score: near.distance + (fromStart + toEnd - direct) * 0.5 });
  }
  return candidates.sort((a, b) => a.score - b.score || a.id.localeCompare(b.id)).slice(0, 3);
}

async function roadRoute(origin, destination, waypoint, options) {
  const url = buildKakaoUrl({ stops: waypoint ? [origin, waypoint, destination] : [origin, destination] });
  // Official https://developers.kakaomobility.com/guide/navi-api/directions
  // 'motorway' means 자동차 전용 도로. This requests avoidance, not a guarantee
  // that every motorway is absent; the public geometry carries no road class.
  url.searchParams.set('priority', 'TIME');
  url.searchParams.set('avoid', waypoint ? 'motorway|ferries' : 'ferries');
  const data = await requestJson(url, options);
  if (waypoint && NO_ROUTE_CODES.has(data?.routes?.[0]?.result_code)) return null;
  const route = normalizeKakao(data);
  const geometryLength = route.coordinates.reduce((sum, coordinate, index, coordinates) => index ? sum + distanceMeters(point(coordinates[index - 1]), point(coordinate)) : sum, 0);
  if (route.coordinates.some(coordinate => !domesticCoordinate(point(coordinate)))
    || geometryLength > route.distanceKm * 1250 + 1000
    || route.coordinates.length > 50000 || route.distanceKm <= 0 || route.distanceKm >= 1500
    || route.durationMin <= 0 || route.durationMin > 720
    || distanceMeters(origin, point(route.coordinates[0])) > 1000
    || distanceMeters(destination, point(route.coordinates.at(-1))) > 1000) {
    throw new ApiError('선택한 출발지·목적지에 맞는 도로 경로를 확인하지 못했습니다.', 502);
  }
  if (waypoint && nearPolyline(waypoint, route.coordinates, origin).distance > (options.exactSelection ? 150 : 700)) return null;
  return { ...route, durationSeconds: data.routes[0].summary.duration };
}

export async function planDestinationRoutes(input, { env = process.env, fetchImpl = fetch, catalog, selectionSigner = scenicSelectionSigner } = {}) {
  const { origin, destination, extraMinutes, theme, scenicSelection } = validateInput(input);
  // Verify before making any provider request. Expired/tampered tokens never
  // fall back to a different scenic place or to a client-supplied coordinate.
  const selected = scenicSelection === undefined ? null : selectionSigner.verify(scenicSelection, destination);
  const options = { key: keyFrom(env), fetchImpl, deadline: Date.now() + 50000 };
  const { durationSeconds: baselineSeconds, ...baseline } = await roadRoute(origin, destination, null, options);
  const fast = { ...baseline, id: 'fast', title: '빠른 경로', scenicLabels: [], waypoints: [], reason: '카카오의 시간 우선 옵션으로 조회한 목적지까지의 도로 경로입니다.' };
  const response = (scenicStatus, message, scenic) => ({ origin, destination, routes: scenic ? [fast, scenic] : [fast], scenicStatus, message });
  if (extraMinutes === 0) return response('disabled', '추가 시간이 0분이어서 빠른 경로만 표시합니다.');
  if (selected) {
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
  let candidates;
  try { candidates = selectScenicCandidates({ origin, destination, theme, fast }, catalog); }
  catch { return response('provider_unavailable', '풍경 기초정보를 확인하지 못해 빠른 경로만 표시합니다.'); }
  if (!candidates.length) return response('no_candidates', `목적지로 가는 길 주변에서 ${theme === 'all' ? '조건에 맞는' : `선택한 ‘${SCENIC_THEMES[theme]}’`} 풍경 통과 지점을 찾지 못해 빠른 경로만 표시합니다.`);
  let providerFailed = false;
  for (const candidate of candidates) {
    let route;
    try { route = await roadRoute(origin, destination, candidate, options); }
    catch { providerFailed = true; break; }
    if (!route || route.durationSeconds > baselineSeconds + extraMinutes * 60 || route.distanceKm > fast.distanceKm * 1.6 + 3) continue;
    // Require a real approach toward the scenery, independent of polyline sampling.
    if (nearPolyline(candidate, route.coordinates, origin).distance > candidate.baselineDistance - 100) continue;
    if (route.coordinates.length === fast.coordinates.length && route.coordinates.every((p, i) => p[0] === fast.coordinates[i][0] && p[1] === fast.coordinates[i][1])) continue;
    const { id, name, lat, lon, source } = candidate;
    const { durationSeconds, ...publicRoute } = route;
    return response('ok', '두 경로는 같은 목적지로 갑니다. 풍경 경로는 인근 도로 통과 기준이며 조망·현장 통행은 보장하지 않습니다.', {
      ...publicRoute, id: 'scenic', title: '풍경 경로', scenicLabels: [...candidate.themes.map(value => SCENIC_THEMES[value]), name],
      waypoints: [{ id, name, lat, lon, source }],
      reason: `${name} 인근을 지나 같은 목적지로 갑니다. 자동차 전용도로 회피 옵션을 적용했습니다.`,
    });
  }
  return response(providerFailed ? 'provider_unavailable' : 'no_feasible_route', providerFailed
    ? '풍경 경로의 도로 정보를 확인하지 못해 빠른 경로만 표시합니다. 잠시 후 다시 시도해 주세요.'
    : '추가 시간 안에 이용할 수 있는 다른 풍경 경로를 확인하지 못해 빠른 경로만 표시합니다.');
}
