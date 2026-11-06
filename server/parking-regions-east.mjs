// Public real-time displays operated by Gangneung City and Busan Facilities Corporation.
// Both expose counts, but no per-lot observation timestamp. Never substitute the
// response envelope's dateTime for observedAt; retrieval-based snapshots are explicit.
export const EASTERN_PARKING_SOURCES = {
  gangneung: { source: '강릉시 도시정보센터', sourceUrl: 'https://its.gn.go.kr/parking/ParkingInfo' },
  busan: { source: '부산시설공단 통합주차포털', sourceUrl: 'https://bsparking.bisco.or.kr/realtimeparking' },
};
export const EASTERN_SNAPSHOT_FRESH_MS = 90000;
// The Gangneung map also carries communication status, so it shares the short TTL.
const CACHE_MS = 30000, MAX_BYTES = 3000000;
const caches = new WeakMap();
const inGangneung = p => p.lat >= 37.60 && p.lat <= 37.99 && p.lon >= 128.65 && p.lon <= 129.06;
const inBusan = p => p.lat >= 34.85 && p.lat <= 35.40 && p.lon >= 128.75 && p.lon <= 129.35;
const nameKey = value => typeof value === 'string' ? value.normalize('NFKC').replace(/\((?:시|구|공영|민영)\)/g, '')
  .replace(/강릉시|부산광역시|공영|시영|구영|공공|주차장/g, '').replace(/[^가-힣a-zA-Z0-9]/g, '').toLowerCase() : '';
const restricted = name => /관광버스|버스\s*전용|화물|차고지|이륜차|거주자\s*우선|직원\s*전용/.test(name);
const count = value => (typeof value === 'number' || typeof value === 'string' && /^\d+$/.test(value))
  && Number.isSafeInteger(Number(value)) && Number(value) >= 0 && Number(value) <= 100000 ? Number(value) : null;
const coord = value => (typeof value === 'number' || typeof value === 'string' && /^\d+(?:\.\d+)?$/.test(value)) && Number.isFinite(Number(value)) ? Number(value) : NaN;
const freshCache = (entry, now, ttl = CACHE_MS) => entry && now >= entry.at && now - entry.at < ttl;
function meters(a, b) {
  const k = Math.PI / 180, h = Math.sin((b.lat - a.lat) * k / 2) ** 2 + Math.cos(a.lat * k) * Math.cos(b.lat * k) * Math.sin((b.lon - a.lon) * k / 2) ** 2;
  return 12742000 * Math.atan2(Math.sqrt(h), Math.sqrt(Math.max(0, 1 - h)));
}
function unknown(region, reason, now, providerId = null, retrievedAt = null) {
  return { status: 'unknown', availableSpaces: null, totalSpaces: null, observedAt: null,
    retrievedAt: retrievedAt === null ? null : new Date(retrievedAt).toISOString(), timeBasis: 'retrieved', fetchedAt: new Date(now).toISOString(),
    ...EASTERN_PARKING_SOURCES[region], reason, providerId };
}
function snapshot(region, id, name, totalValue, occupiedValue, retrievedAt, now, explicitAvailable) {
  if (restricted(name)) return unknown(region, 'unsupported', now, id, retrievedAt);
  if (!Number.isFinite(retrievedAt) || retrievedAt > now || now - retrievedAt > EASTERN_SNAPSHOT_FRESH_MS) return unknown(region, 'stale', now, id, retrievedAt);
  const total = count(totalValue), occupied = count(occupiedValue);
  if (total === null || occupied === null || total === 0 || occupied > total
    || explicitAvailable !== undefined && (count(explicitAvailable) === null || count(explicitAvailable) !== total - occupied)) return unknown(region, 'unavailable', now, id, retrievedAt);
  const available = total - occupied;
  return { ...unknown(region, 'live', now, id, retrievedAt), status: available > 0 ? 'available' : 'full', availableSpaces: available, totalSpaces: total };
}
export function parseBusanParkingInventory(data) {
  if (data?.result !== true || !Array.isArray(data.data) || data.data.length > 300) throw new Error('Invalid Busan parking response');
  return data.data.filter(row => row && Number.isSafeInteger(row.parkingId) && row.parkingId > 0 && Number.isSafeInteger(row.sectnId) && row.sectnId > 0
    && typeof row.parkingName === 'string' && row.parkingName.length <= 120 && !/[\u0000-\u001f\u007f-\u009f]/.test(row.parkingName)
    && inBusan({ lat: coord(row.lat), lon: coord(row.lng) })).map(row => ({ id: `busan:${row.parkingId}:${row.sectnId}`, name: row.parkingName,
    // Busan's official popup labels parkCount as "이용 가능" (available).
    // Its marker colors use (cellCount - parkCount) / cellCount as occupancy.
    lat: coord(row.lat), lon: coord(row.lng), total: row.cellCount, available: row.parkCount }));
}
export function parseGangneungParkingMap(data) {
  if (!Array.isArray(data?.rows) || data.rows.length > 1000) throw new Error('Invalid Gangneung parking response');
  return data.rows.filter(row => row && typeof row.code === 'string' && /^PLOT\d{6}$/.test(row.code) && typeof row.name === 'string'
    && row.name.length <= 120 && !/[\u0000-\u001f\u007f-\u009f]/.test(row.name) && inGangneung({ lat: coord(row.wgs84Y), lon: coord(row.wgs84X) }))
    .map(row => ({ id: row.code, name: row.name, lat: coord(row.wgs84Y), lon: coord(row.wgs84X), live: row.commStatImgCd === 'l' }));
}
function matching(place, inventory) {
  const name = nameKey(place.name);
  if (name.length < 2) return [];
  return inventory.filter(row => nameKey(row.name) === name && meters(place, row) <= 150);
}
export function matchBusanParking(place, rows, { now, retrievedAt } = {}) {
  const candidates = matching(place, rows);
  if (candidates.length !== 1) return unknown('busan', 'unmatched', now, null, retrievedAt);
  const row = candidates[0];
  const total = count(row.total), available = count(row.available);
  if (total === null || available === null || available > total) return unknown('busan', 'unavailable', now, row.id, retrievedAt);
  return snapshot('busan', row.id, row.name + ' ' + place.name, total, total - available, retrievedAt, now);
}
export function matchGangneungParkingDetail(place, mapRow, detail, { now, retrievedAt } = {}) {
  if (matching(place, [mapRow]).length !== 1) return unknown('gangneung', 'unmatched', now, null, retrievedAt);
  const rows = detail?.rows;
  if (!Array.isArray(rows) || rows.length !== 1 || rows[0]?.plotId !== mapRow.id || nameKey(rows[0]?.facLocation) !== nameKey(mapRow.name))
    return unknown('gangneung', 'unmatched', now, 'gangneung:' + mapRow.id, retrievedAt);
  const row = rows[0], id = 'gangneung:' + mapRow.id;
  if (row.collectYn !== 'Y' || !mapRow.live) return unknown('gangneung', 'unsupported', now, id, retrievedAt);
  if (row.tot !== undefined && count(row.tot) !== count(row.totalSpace)) return unknown('gangneung', 'unavailable', now, id, retrievedAt);
  return snapshot('gangneung', id, row.facLocation + ' ' + place.name, row.totalSpace, row.occupancy, retrievedAt, now, row.availSpace);
}
async function json(url, fetchImpl, signal) {
  const response = await fetchImpl(new URL(url), { headers: { Accept: 'application/json' }, redirect: 'error', signal });
  if (!response.ok || response.status !== 200 || Number(response.headers?.get?.('content-length')) > MAX_BYTES) throw new Error('Public parking unavailable');
  if (!response.body?.getReader) return response.json();
  const reader = response.body.getReader(), chunks = []; let bytes = 0;
  for (;;) { const part = await reader.read(); if (part.done) break; bytes += part.value.byteLength;
    if (bytes > MAX_BYTES) { await reader.cancel(); throw new Error('Parking response too large'); } chunks.push(part.value); }
  return JSON.parse(Buffer.concat(chunks).toString('utf8'));
}
function cacheFor(fetchImpl) {
  if (!caches.has(fetchImpl)) caches.set(fetchImpl, { details: new Map(), detailPending: new Map() });
  return caches.get(fetchImpl);
}
async function bounded(action) {
  const controller = new AbortController(); let timer;
  try { return await Promise.race([action(controller.signal), new Promise((_resolve, reject) => { timer = setTimeout(() => { controller.abort(); reject(new Error('Parking timeout')); }, 8000); })]); }
  finally { clearTimeout(timer); controller.abort(); }
}
async function busanInventory(fetchImpl, now) {
  const cache = cacheFor(fetchImpl);
  if (freshCache(cache.busan, now())) return cache.busan;
  if (!cache.busanPending) cache.busanPending = bounded(async signal => {
    const rows = parseBusanParkingInventory(await json('https://bsparking.bisco.or.kr/api/parking/realtime/list', fetchImpl, signal));
    if (signal.aborted) throw new Error('Cancelled');
    return cache.busan = { rows, at: now() };
  }).finally(() => { cache.busanPending = null; });
  return cache.busanPending;
}
async function gangneungResults(places, fetchImpl, now) {
  const cache = cacheFor(fetchImpl);
  return bounded(async signal => {
    if (!freshCache(cache.gangneungMap, now())) {
      if (!cache.gangneungMapPending) cache.gangneungMapPending = (async () => {
        const rows = parseGangneungParkingMap(await json('https://its.gn.go.kr/main/selectParkList', fetchImpl, signal));
        if (signal.aborted) throw new Error('Cancelled');
        return cache.gangneungMap = { rows, at: now() };
      })().finally(() => { cache.gangneungMapPending = null; });
      await cache.gangneungMapPending;
    }
    const found = places.map(place => ({ place, candidates: matching(place, cache.gangneungMap.rows) }));
    const tasks = [...new Set(found.filter(item => item.candidates.length === 1 && item.candidates[0].live && !restricted(item.candidates[0].name)).map(item => item.candidates[0].id))].slice(0, 6);
    const loaded = new Map(); let next = 0;
    await Promise.all(Array.from({ length: Math.min(3, tasks.length) }, async () => {
      while (next < tasks.length && !signal.aborted) {
        const id = tasks[next++];
        try {
          let entry = cache.details.get(id);
          if (!freshCache(entry, now())) {
            if (!cache.detailPending.has(id)) cache.detailPending.set(id, (async () => {
              const data = await json('https://its.gn.go.kr/popup/getParkingInfo?plotId=' + id, fetchImpl, signal);
              if (signal.aborted) throw new Error('Cancelled');
              const value = { data, at: now() }; cache.details.set(id, value); return value;
            })().finally(() => { cache.detailPending.delete(id); }));
            entry = await cache.detailPending.get(id);
          }
          loaded.set(id, entry);
        } catch { /* one failed lot never borrows another lot's count */ }
      }
    }));
    if (signal.aborted) throw new Error('Cancelled');
    const evaluated = now();
    return found.map(({ place, candidates }) => {
      let parking;
      if (candidates.length !== 1) parking = unknown('gangneung', 'unmatched', evaluated);
      else {
        const row = candidates[0], detail = loaded.get(row.id);
        parking = !row.live || restricted(row.name) ? unknown('gangneung', 'unsupported', evaluated, 'gangneung:' + row.id)
          : !detail ? unknown('gangneung', 'unavailable', evaluated, 'gangneung:' + row.id)
            : matchGangneungParkingDetail(place, row, detail.data, { now: evaluated, retrievedAt: detail.at });
      }
      return { ...place, parkingStatus: parking.status, parking };
    });
  });
}
export async function enrichEasternParkingPlaces(places, { fetchImpl = fetch, now = Date.now } = {}) {
  const busan = places.filter(inBusan), gangneung = places.filter(inGangneung), replacements = new Map();
  await Promise.all([
    busan.length ? (async () => {
      let inventory = null; try { inventory = await busanInventory(fetchImpl, now); } catch { /* explicit unavailable */ }
      const evaluated = now();
      for (const place of busan) {
        const parking = inventory ? matchBusanParking(place, inventory.rows, { now: evaluated, retrievedAt: inventory.at }) : unknown('busan', 'unavailable', evaluated);
        replacements.set(place, { ...place, parkingStatus: parking.status, parking });
      }
    })() : null,
    gangneung.length ? (async () => {
      try { const result = await gangneungResults(gangneung, fetchImpl, now); result.forEach((place, i) => replacements.set(gangneung[i], place)); }
      catch { for (const place of gangneung) replacements.set(place, { ...place, parkingStatus: 'unknown', parking: unknown('gangneung', 'unavailable', now()) }); }
    })() : null,
  ]);
  return places.map(place => replacements.get(place) || place);
}
