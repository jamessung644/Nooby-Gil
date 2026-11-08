// Public Seoul Open Data sheets, KOGL Type 1 (attribution required).
// This is the published dataset's JSON sheet, not an embedded third-party API key.
// Live occupancy: https://data.seoul.go.kr/dataList/OA-21709/A/1/datasetView.do
// Position/code join: https://data.seoul.go.kr/dataList/OA-13122/S/1/datasetView.do
import { enrichEasternParkingPlaces } from './parking-regions-east.mjs';
import { enrichJejuParkingPlaces } from './parking-regions-jeju.mjs';
import { enrichNationalParkingPlaces } from './parking-regions-national.mjs';
import { enrichKotsaParkingPlaces } from './parking-kotsa.mjs';
export const PARKING_SOURCE = '서울열린데이터광장 · 시영주차장';
export const PARKING_SOURCE_URL = 'https://data.seoul.go.kr/dataList/OA-21709/A/1/datasetView.do';
export const PARKING_FRESH_MS = 10 * 60 * 1000;
const LIVE_TTL_MS = 30 * 1000, POSITION_TTL_MS = 24 * 60 * 60 * 1000;
const caches = new WeakMap();
const inSeoul = place => place.lat >= 37.40 && place.lat <= 37.72 && place.lon >= 126.75 && place.lon <= 127.20
  && (!place.address || /^서울(?:특별시)?\s/.test(place.address));
const coordinate = (lat, lon) => Number.isFinite(lat) && Number.isFinite(lon) && lat >= 37.40 && lat <= 37.72 && lon >= 126.75 && lon <= 127.20;
const integer = value => (typeof value === 'number' || typeof value === 'string' && /^\d+$/.test(value))
  && Number.isSafeInteger(Number(value)) && Number(value) >= 0 && Number(value) <= 100000 ? Number(value) : null;
const code = value => typeof value === 'string' && /^\d{1,20}$/.test(value) ? value : null;
const nameKey = value => typeof value === 'string' ? value.normalize('NFKC').replace(/\((?:시|구|민영)\)/g, '')
  .replace(/서울특별시|서울시|공영|시영|구영|공공|주차장/g, '').replace(/[^가-힣a-zA-Z0-9]/g, '').toLowerCase() : '';
const toIso = value => new Date(value).toISOString();
function observedTime(value) {
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$/.test(value)) return null;
  const parsed = Date.parse(value.replace(' ', 'T') + '+09:00');
  if (!Number.isFinite(parsed) || new Date(parsed + 9 * 3600000).toISOString().slice(0, 19).replace('T', ' ') !== value) return null;
  return parsed;
}
function meters(a, b) {
  const radians = Math.PI / 180, deltaLat = (b.lat - a.lat) * radians, deltaLon = (b.lon - a.lon) * radians;
  const h = Math.sin(deltaLat / 2) ** 2 + Math.cos(a.lat * radians) * Math.cos(b.lat * radians) * Math.sin(deltaLon / 2) ** 2;
  return 6371000 * 2 * Math.atan2(Math.sqrt(h), Math.sqrt(Math.max(0, 1 - h)));
}
function unknown(reason, now, supported = true, observedAt = null, providerId = null) {
  return { status: 'unknown', availableSpaces: null, totalSpaces: null, observedAt,
    fetchedAt: toIso(now), source: supported ? PARKING_SOURCE : '', sourceUrl: supported ? PARKING_SOURCE_URL : '', reason, providerId };
}
export function parseParkingSheet(text) {
  // Seoul's sheet emits object keys without quotes. Quote identifiers only outside
  // JSON string tokens, then use JSON.parse; never evaluate the provider's script.
  const quoted = text.replace(/"(?:\\.|[^"\\])*"|([A-Za-z_][A-Za-z0-9_]*)(\s*:)/g,
    (token, key, colon) => key ? '"' + key + '"' + colon : token);
  return JSON.parse(quoted.replace(/"(?:\\.|[^"\\])*"|,(?=\s*[\]}])/g, token => token === ',' ? '' : token));
}
async function sheet(dataset, page, fetchImpl, signal) {
  const url = new URL('https://data.seoul.go.kr/dataList/dataView.do');
  url.search = new URLSearchParams({ onepagerow: '1000', infId: dataset, serviceKind: '1', srvType: 'S', pageNo: String(page) });
  const response = await fetchImpl(url, { method: 'POST', headers: { Accept: 'application/json', 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ pageNo: String(page), pageSize: '1000' }).toString(), redirect: 'error', signal });
  if (!response.ok) throw new Error('parking sheet unavailable');
  if (Number(response.headers?.get?.('content-length')) > 3000000) throw new Error('parking sheet too large');
  const reader = response.body?.getReader?.();
  let data;
  if (reader) {
    const chunks = []; let size = 0;
    for (;;) { const part = await reader.read(); if (part.done) break; size += part.value.byteLength; if (size > 3000000) { await reader.cancel(); throw new Error('parking sheet too large'); } chunks.push(part.value); }
    data = parseParkingSheet(Buffer.concat(chunks).toString('utf8'));
  } else data = await response.json();
  if (data?.result !== 'ok' || !Array.isArray(data.list) || data.list.length > 1000 || !Number.isSafeInteger(Number(data.page?.totalCount))
    || Number(data.page.totalCount) < 0 || Number(data.page.totalCount) > 100000) throw new Error('invalid parking sheet');
  return { rows: data.list, total: Number(data.page.totalCount) };
}
async function fetchPositions(fetchImpl, signal) {
  const first = await sheet('OA-13122', 1, fetchImpl, signal);
  // The public sheet uses 100-row page offsets even when onepagerow=1000.
  // Bound the join to 5000 rows; unmatched/missing coordinates remain unknown.
  const count = Math.min(Math.ceil(first.total / 1000), 5);
  const more = await Promise.all(Array.from({ length: Math.max(0, count - 1) }, (_, index) => sheet('OA-13122', 11 + index * 10, fetchImpl, signal)));
  const positions = new Map();
  for (const row of [...first.rows, ...more.flatMap(page => page.rows)]) {
    if (!row || !code(row.PKLT_CD) || typeof row.LAT !== 'string' || typeof row.LOT !== 'string') continue;
    const lat = Number(row.LAT), lon = Number(row.LOT), key = nameKey(row.PKLT_NM);
    if (!coordinate(lat, lon) || key.length < 2) continue;
    const values = positions.get(row.PKLT_CD) || [];
    if (values.length < 100) values.push({ lat, lon, name: key });
    positions.set(row.PKLT_CD, values);
  }
  return positions;
}
function cacheFor(fetchImpl) {
  if (!caches.has(fetchImpl)) caches.set(fetchImpl, {});
  return caches.get(fetchImpl);
}
async function inventory(fetchImpl, clock) {
  const cache = cacheFor(fetchImpl), time = clock();
  if (cache.live && time >= cache.live.fetchedAt && time - cache.live.fetchedAt < LIVE_TTL_MS && cache.positions && time - cache.positionTime < POSITION_TTL_MS) return cache;
  if (cache.pending) return cache.pending;
  cache.pending = (async () => {
    const controller = new AbortController(); let timer;
    try {
      return await Promise.race([
        (async () => {
          const [live, positions] = await Promise.all([
            sheet('OA-21709', 1, fetchImpl, controller.signal),
            cache.positions && time >= cache.positionTime && time - cache.positionTime < POSITION_TTL_MS ? cache.positions : fetchPositions(fetchImpl, controller.signal),
          ]);
          if (controller.signal.aborted) throw new Error('parking request cancelled');
          cache.live = { rows: live.rows, fetchedAt: clock() };
          if (positions !== cache.positions) { cache.positions = positions; cache.positionTime = clock(); }
          return cache;
        })(),
        new Promise((_resolve, reject) => { timer = setTimeout(() => { controller.abort(); reject(new Error('parking request timeout')); }, 8000); }),
      ]);
    } finally { clearTimeout(timer); controller.abort(); cache.pending = null; }
  })();
  return cache.pending;
}
export function matchParkingInventory(place, rows, positions, { now, fetchedAt = now } = {}) {
  const key = nameKey(place.name), matches = [];
  if (key.length < 2) return unknown('unmatched', fetchedAt);
  for (const row of rows) {
    const id = code(row?.PKLT_CD);
    if (!id || nameKey(row.PKLT_NM) !== key) continue;
    const candidates = positions.get(id) || [];
    if (candidates.some(position => position.name === key && meters(place, position) <= 150)) matches.push(row);
  }
  // Never borrow a neighboring/similarly named lot's occupancy, or resolve ambiguity by distance alone.
  if (matches.length !== 1) return unknown('unmatched', fetchedAt);
  const row = matches[0], observed = observedTime(row.NOW_PRK_VHCL_UPDT_TM), providerId = 'seoul:' + row.PKLT_CD;
  // The live city sheet also includes coach-only lots. Their counts must not
  // trigger automatic rerouting of a passenger car into an incompatible facility.
  if (/관광버스|버스\s*전용|이륜차|거주자\s*우선|직원\s*전용/.test(row.PKLT_NM + ' ' + place.name))
    return unknown('unsupported', fetchedAt, true, observed === null ? null : toIso(observed), providerId);
  if (observed === null || observed > now || String(row.PRK_STTS_YN) !== '1') return unknown('unavailable', fetchedAt, true, observed === null ? null : toIso(observed), providerId);
  if (now - observed > PARKING_FRESH_MS) return unknown('stale', fetchedAt, true, toIso(observed), providerId);
  const total = integer(row.TPKCT), occupied = integer(row.NOW_PRK_VHCL_CNT);
  if (total === null || occupied === null || total < 1 || occupied > total) return unknown('unavailable', fetchedAt, true, toIso(observed), providerId);
  const free = total - occupied;
  return { status: free > 0 ? 'available' : 'full', availableSpaces: free, totalSpaces: total,
    observedAt: toIso(observed), fetchedAt: toIso(fetchedAt), source: PARKING_SOURCE, sourceUrl: PARKING_SOURCE_URL, reason: 'live', providerId };
}
async function enrichSeoulParkingPlaces(places, { fetchImpl = fetch, now = Date.now } = {}) {
  const supported = places.some(inSeoul);
  let data = null;
  if (supported) { try { data = await inventory(fetchImpl, now); } catch { /* preserve useful places, never stale occupancy on provider failure */ } }
  const time = now();
  return places.map(place => {
    const parking = !inSeoul(place) ? unknown('unsupported', time, false) : !data ? unknown('unavailable', time)
      // Response assessment time lets clients add monotonic elapsed time even if
      // this response used the 30-second upstream cache or the vehicle clock is wrong.
      : matchParkingInventory(place, data.live.rows, data.positions, { now: time, fetchedAt: time });
    return { ...place, parkingStatus: parking.status, parking };
  });
}

/** Municipal queries run independently; one unavailable city never masks another. */
export function assessParkingFreshness(parking, now) {
  const assessed = { ...parking, fetchedAt: toIso(now) };
  if (parking.reason !== 'live') return assessed;
  const stamp = Date.parse(parking.timeBasis === 'retrieved' ? parking.retrievedAt : parking.observedAt);
  const maxAge = parking.timeBasis === 'retrieved' ? 90000 : PARKING_FRESH_MS;
  if (!Number.isFinite(stamp) || stamp > now || now - stamp > maxAge) {
    return { ...assessed, status: 'unknown', availableSpaces: null, totalSpaces: null,
      reason: !Number.isFinite(stamp) || stamp > now ? 'unavailable' : 'stale' };
  }
  return assessed;
}
export async function enrichParkingPlaces(places, { fetchImpl = fetch, now = Date.now, env = {} } = {}) {
  const options = { fetchImpl, now };
  const [kotsa, ...results] = await Promise.all([
    // KOTSA (needs DATA_GO_KR_KEY) is the lowest-priority source: it only fills places no municipal source confirmed live.
    enrichKotsaParkingPlaces(places, { env, fetchImpl, now }),
    enrichSeoulParkingPlaces(places, options), enrichEasternParkingPlaces(places, options),
    enrichJejuParkingPlaces(places, options), enrichNationalParkingPlaces(places, options),
  ]);
  const evaluated = now();
  const quality = row => row?.parking?.reason === 'live' ? 3 : row?.parking?.providerId ? 2 : row?.parking?.source ? 1 : 0;
  return places.map((place, index) => {
    let selected = results[0][index];
    for (const result of results.slice(1)) {
      const candidate = result[index];
      if (candidate !== place && quality(candidate) >= quality(selected)) selected = candidate;
    }
    const fallback = kotsa[index];
    if (fallback !== place && quality(selected) < 3 && quality(fallback) === 3) selected = fallback;
    const parking = assessParkingFreshness(selected.parking, evaluated);
    return { ...selected, parkingStatus: parking.status, parking };
  });
}
