// Factual occupancy from the public, unauthenticated Seogwipo real-time map API.
// https://parking.seogwipo.go.kr/parking/realtime
// The map labels parkCount as remaining spaces, NOT parked vehicles.
// The response dateTime is request time, not a sensor observation timestamp.
// Its setParkingStatus marks invalid occupancy ratios as notLinked. The public
// UI does not filter directYn/baseYn/publicOrganCode; those management fields
// are not documented connectivity flags. Do not infer connectivity from them.
// No dataset-specific KOGL grant is published; do not describe this as KOGL data.
// General policy: https://www.seogwipo.go.kr/help/copyright.htm
// Jeju ITS is deliberately not used: its eight public records do not establish
// current sensor connectivity, and its UPDT_DT may be a facility metadata date.
export const JEJU_PARKING_SOURCE = '서귀포시 주차포털';
export const JEJU_PARKING_SOURCE_URL = 'https://parking.seogwipo.go.kr/parking/realtime';
export const JEJU_PARKING_SNAPSHOT_FRESH_MS = 90 * 1000;
const API_URL = 'https://parking.seogwipo.go.kr/api/parking/realtime/list';
const CACHE_MS = 30 * 1000, MAX_BYTES = 1000000;
const caches = new WeakMap();
const iso = value => new Date(value).toISOString();
const numeric = value => (typeof value === 'number' || typeof value === 'string' && /^\d+(?:\.\d+)?$/.test(value))
  && Number.isFinite(Number(value)) ? Number(value) : null;
const integer = value => (typeof value === 'number' || typeof value === 'string' && /^\d+$/.test(value))
  && Number.isSafeInteger(Number(value)) && Number(value) >= 0 && Number(value) <= 100000 ? Number(value) : null;
const identifier = value => (typeof value === 'number' && Number.isSafeInteger(value) || typeof value === 'string' && /^\d{1,12}$/.test(value))
  && Number(value) > 0 ? String(value) : null;
const coordinates = (lat, lon) => Number.isFinite(lat) && Number.isFinite(lon)
  && lat >= 33.10 && lat <= 33.60 && lon >= 126.10 && lon <= 126.98;
const nameKey = value => typeof value === 'string' ? value.normalize('NFKC')
  .replace(/^(?:제주특별자치도)?\s*서귀포시\s*/, '').replace(/공영|시영|공공|주차장/g, '')
  .replace(/[^가-힣a-zA-Z0-9]/g, '').toLowerCase() : '';

// Broad request eligibility only; a unique name AND coordinates within 150 m
// are still mandatory. Explicit Jeju City addresses never claim this coverage.
export function isSeogwipoParkingPlace(place) {
  if (!place || !coordinates(place.lat, place.lon)) return false;
  const address = [place.address, place.roadAddress].filter(value => typeof value === 'string').join(' ');
  if (/제주시/.test(address)) return false;
  if (/서귀포시/.test(address)) return true;
  return place.lat <= 33.34 || place.lon >= 126.72 && place.lat <= 33.49;
}
function distance(a, b) {
  const r = Math.PI / 180, dy = (b.lat - a.lat) * r, dx = (b.lon - a.lon) * r;
  const h = Math.sin(dy / 2) ** 2 + Math.cos(a.lat * r) * Math.cos(b.lat * r) * Math.sin(dx / 2) ** 2;
  return 6371000 * 2 * Math.atan2(Math.sqrt(h), Math.sqrt(Math.max(0, 1 - h)));
}
function unknown(reason, now, retrievedAt = null, providerId = null) {
  return { status: 'unknown', availableSpaces: null, totalSpaces: null, observedAt: null,
    fetchedAt: iso(now), retrievedAt: retrievedAt === null ? null : iso(retrievedAt),
    ...(retrievedAt === null ? {} : { timeBasis: 'retrieved' }),
    source: JEJU_PARKING_SOURCE, sourceUrl: JEJU_PARKING_SOURCE_URL, reason, providerId };
}

export function matchSeogwipoParkingInventory(place, rows, { now, retrievedAt = null } = {}) {
  const key = nameKey(place.name);
  if (key.length < 2 || !coordinates(place.lat, place.lon)) return unknown('unmatched', now, retrievedAt);
  const matches = rows.filter(row => {
    const lat = numeric(row?.lat), lon = numeric(row?.lng);
    return identifier(row?.parkingId) && identifier(row?.sectnId) && coordinates(lat, lon)
      && key === nameKey(row.parkingName) && distance(place, { lat, lon }) <= 150;
  });
  // Distinct sections and duplicate inventory rows are ambiguous; never pick one.
  if (matches.length !== 1) return unknown('unmatched', now, retrievedAt);
  const row = matches[0], providerId = `seogwipo:${row.parkingId}:${row.sectnId}`;
  if (/관광버스|버스\s*전용|이륜차|거주자\s*우선|직원\s*전용/.test(row.parkingName + ' ' + place.name))
    return unknown('unsupported', now, retrievedAt, providerId);
  if (!Number.isFinite(retrievedAt) || retrievedAt > now) return unknown('unavailable', now, retrievedAt, providerId);
  if (now - retrievedAt > JEJU_PARKING_SNAPSHOT_FRESH_MS) return unknown('stale', now, retrievedAt, providerId);
  const total = integer(row.cellCount), available = integer(row.parkCount);
  if (total === null || total < 1 || available === null || available > total)
    return unknown('unavailable', now, retrievedAt, providerId);
  if (integer(row.busCellCount) === total) return unknown('unsupported', now, retrievedAt, providerId);
  return { status: available > 0 ? 'available' : 'full', availableSpaces: available, totalSpaces: total,
    observedAt: null, fetchedAt: iso(now), retrievedAt: iso(retrievedAt), timeBasis: 'retrieved',
    source: JEJU_PARKING_SOURCE, sourceUrl: JEJU_PARKING_SOURCE_URL, reason: 'live', providerId };
}

async function readInventory(fetchImpl, signal) {
  const response = await fetchImpl(new URL(API_URL), { headers: { Accept: 'application/json' }, redirect: 'error', signal });
  if (!response.ok || Number(response.headers?.get?.('content-length')) > MAX_BYTES) throw new Error('parking provider unavailable');
  const reader = response.body?.getReader?.();
  let data;
  if (reader) {
    const chunks = []; let size = 0;
    for (;;) {
      const part = await reader.read(); if (part.done) break;
      size += part.value.byteLength;
      if (size > MAX_BYTES) { await reader.cancel(); throw new Error('parking inventory too large'); }
      chunks.push(part.value);
    }
    data = JSON.parse(Buffer.concat(chunks).toString('utf8'));
  } else data = await response.json();
  if (data?.result !== true || data.httpStatusCode !== 200 || !Array.isArray(data.data) || data.data.length > 500)
    throw new Error('invalid parking inventory');
  return data.data;
}
async function inventory(fetchImpl, clock) {
  if (!caches.has(fetchImpl)) caches.set(fetchImpl, {});
  const cache = caches.get(fetchImpl), time = clock();
  if (cache.value && time >= cache.value.retrievedAt && time - cache.value.retrievedAt < CACHE_MS) return cache.value;
  if (cache.pending) return cache.pending;
  cache.pending = (async () => {
    const controller = new AbortController(); let timer;
    try {
      return await Promise.race([
        (async () => {
          const rows = await readInventory(fetchImpl, controller.signal);
          if (controller.signal.aborted) throw new Error('parking request cancelled');
          const value = { rows, retrievedAt: clock() };
          cache.value = value;
          return value;
        })(),
        new Promise((_resolve, reject) => { timer = setTimeout(() => { controller.abort(); reject(new Error('parking request timeout')); }, 8000); }),
      ]);
    } catch (error) {
      cache.value = null;
      throw error;
    } finally { clearTimeout(timer); controller.abort(); cache.pending = null; }
  })();
  return cache.pending;
}

export async function enrichJejuParkingPlaces(places, { fetchImpl = fetch, now = Date.now } = {}) {
  if (!places.some(isSeogwipoParkingPlace)) return places;
  let data = null;
  try { data = await inventory(fetchImpl, now); } catch { /* Never retain old availability when a refresh fails. */ }
  const time = now();
  return places.map(place => {
    if (!isSeogwipoParkingPlace(place)) return place;
    const parking = data ? matchSeogwipoParkingInventory(place, data.rows, { now: time, retrievedAt: data.retrievedAt })
      : unknown('unavailable', time);
    return { ...place, parkingStatus: parking.status, parking };
  });
}
