// 한국교통안전공단 주차정보 API (data.go.kr 15099883) -> `parking` objects used by server/parking-live.mjs.
// PrkRealtimeInfo_v1 returns only {id, free, total}; positions come from catalog/parking-facilities.json
// (built by scripts/build-parking-facilities.mjs). The API gives no observation time, so the result is a
// retrieved-time snapshot (`timeBasis: 'retrieved'`) and is never used for automatic destination changes.
// Needs DATA_GO_KR_KEY; without it every place is returned unchanged.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const BASE = 'https://apis.data.go.kr/B553881/Parking_v1/PrkRealtimeInfo_v1';
const SOURCE_URL = 'https://www.data.go.kr/data/15099883/openapi.do';
export const KOTSA_SOURCE = '한국교통안전공단 · 공영주차장 (제공처 갱신 시각 미제공)';
export const KOTSA_MATCH_METERS = 150;
const CACHE_MS = 30000, MAX_PAGES = 10;
const defaultFile = path.join(path.dirname(fileURLToPath(import.meta.url)), '..', 'catalog', 'parking-facilities.json');
const generic = /주차장|공영|공용|노외|노상|민영|시영|구영|군영|\(.*?\)|\s+|[-_·.]/g;
const nameKey = value => String(value || '').normalize('NFKC').replace(generic, '').toLowerCase();
const similar = (a, b) => { const x = nameKey(a), y = nameKey(b); return x.length >= 2 && y.length >= 2 && (x === y || x.includes(y) || y.includes(x)); };
function meters(a, b) {
  const r = Math.PI / 180, h = Math.sin((b.lat - a.lat) * r / 2) ** 2
    + Math.cos(a.lat * r) * Math.cos(b.lat * r) * Math.sin((b.lon - a.lon) * r / 2) ** 2;
  return 12742000 * Math.atan2(Math.sqrt(h), Math.sqrt(Math.max(0, 1 - h)));
}
export function loadFacilities(file = defaultFile) {
  try {
    const lots = JSON.parse(fs.readFileSync(file, 'utf8'))?.lots;
    return lots && typeof lots === 'object' ? lots : {};
  } catch { return {}; }
}
const caches = new Map();
async function realtime(key, fetchImpl, now) {
  const cached = caches.get(key);
  if (cached?.rows && now - cached.at < CACHE_MS) return cached;
  if (cached?.pending) return cached.pending;
  const entry = cached || { at: 0, rows: null, pending: null };
  entry.pending = (async () => {
    const rows = [];
    for (let page = 1; page <= MAX_PAGES; page++) {
      const response = await fetchImpl(`${BASE}?serviceKey=${encodeURIComponent(key)}&pageNo=${page}&numOfRows=1000&format=2`, { redirect: 'error' });
      if (!response.ok) throw new Error('kotsa http');
      const data = await response.json();
      if (data?.resultCode !== '0' || !Array.isArray(data.PrkRealtimeInfo)) throw new Error('kotsa response');
      rows.push(...data.PrkRealtimeInfo);
      if (page * 1000 >= Number(data.totalCount)) break;
    }
    entry.rows = rows; entry.at = now; return entry;
  })().finally(() => { entry.pending = null; });
  caches.set(key, entry);
  return entry.pending;
}

/** Pure matching: one KOTSA facility per Kakao place and one place per facility; name AND position must agree. */
export function matchKotsaParking(places, rows, facilities, { now, retrievedAt }) {
  const candidates = [];
  places.forEach((place, index) => {
    if (!Number.isFinite(place.lat) || !Number.isFinite(place.lon)) return;
    for (const row of rows) {
      const info = facilities[row.prk_center_id];
      if (!info || !Number.isFinite(info.lat) || !Number.isFinite(info.lon) || !similar(place.name, info.name)) continue;
      const distance = meters(place, info);
      if (distance > KOTSA_MATCH_METERS) continue;
      const free = Number(row.pkfc_Available_ParkingLots_total), total = Number(row.pkfc_ParkingLots_total);
      if (!Number.isInteger(free) || !Number.isInteger(total) || total <= 0 || total > 100000 || free < 0 || free > total) continue;
      candidates.push({ index, id: String(row.prk_center_id), free, total, distance });
    }
  });
  candidates.sort((a, b) => a.distance - b.distance);
  const usedPlaces = new Set(), usedLots = new Set(), result = new Map();
  for (const c of candidates) {
    if (usedPlaces.has(c.index) || usedLots.has(c.id)) continue;
    usedPlaces.add(c.index); usedLots.add(c.id);
    result.set(c.index, { status: c.free > 0 ? 'available' : 'full', availableSpaces: c.free, totalSpaces: c.total, observedAt: null,
      retrievedAt: new Date(retrievedAt).toISOString(), timeBasis: 'retrieved', fetchedAt: new Date(now).toISOString(),
      source: KOTSA_SOURCE, sourceUrl: SOURCE_URL, reason: 'live', providerId: `kotsa:${c.id}` });
  }
  return result;
}

/** Same length as `places`; unmatched places, a missing key or any provider failure return the place itself. */
export async function enrichKotsaParkingPlaces(places, { env = process.env, fetchImpl = fetch, now = Date.now, facilities = loadFacilities() } = {}) {
  const key = typeof env?.DATA_GO_KR_KEY === 'string' ? env.DATA_GO_KR_KEY.trim() : '';
  if (!key || !Object.keys(facilities).length || !places.length) return places;
  try {
    const stamp = now();
    const { at, rows } = await realtime(key, fetchImpl, stamp);
    const matches = matchKotsaParking(places, rows, facilities, { now: stamp, retrievedAt: at });
    return places.map((place, index) => matches.has(index) ? { ...place, parkingStatus: matches.get(index).status, parking: matches.get(index) } : place);
  } catch { return places; }
}
