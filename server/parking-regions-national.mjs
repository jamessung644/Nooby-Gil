// Public, unauthenticated requests used by the municipalities' parking maps.
// Never reuse map SDK keys, login sessions, or private integration credentials.
// Incheon/Yeosu parkCount is VACANCIES, as labelled by their official map UI.
export const NATIONAL_PARKING_PROVIDERS = Object.freeze({
  daegu: { source: '대구광역시 통합주차정보시스템', sourceUrl: 'https://pis.daegu.go.kr/',
    bounds: [35.60, 36.34, 128.34, 128.91], address: /^대구(?:광역시)?(?:\s|$)/ },
  incheon: { source: '인천시설공단 · 실시간 주차 현황', sourceUrl: 'https://smartparking.insiseol.or.kr/realtimeparking',
    bounds: [37.32, 37.75, 126.35, 126.79], address: /^인천(?:광역시)?(?:\s|$)/ },
  yeosu: { source: '여수시도시관리공단 · 실시간 주차 현황', sourceUrl: 'https://parking.yumcorp.or.kr/parking/realtime',
    bounds: [34.30, 34.95, 127.25, 128.00], address: /(?:^|\s)여수시(?:\s|$)/ },
});
const CACHE_MS = 30000, OBSERVED_FRESH_MS = 600000, RETRIEVED_FRESH_MS = 90000;
const MAX_BODY_BYTES = 3000000, MAX_ROWS = 2000;
const caches = new WeakMap();
// The official Incheon realtime page itself filters to these public lots, and
// excludes monthly-only sections 57/58. A returned row alone is not coverage.
const INCHEON_PUBLIC_IDS = new Set([13, 14, 25, 5, 3, 8, 11, 4, 10, 6, 12, 17, 33, 18, 19, 48, 22, 49, 50, 1, 47]);
const restricted = /관광버스|버스\s*전용|이륜차|거주자\s*우선|직원\s*전용|월정기|장애인\s*전용/;
const iso = value => new Date(value).toISOString();
const integer = value => (typeof value === 'number' || typeof value === 'string' && /^\d+$/.test(value))
  && Number.isSafeInteger(Number(value)) && Number(value) >= 0 && Number(value) <= 100000 ? Number(value) : null;
const number = value => (typeof value === 'number' || typeof value === 'string' && /^-?\d+(?:\.\d+)?$/.test(value))
  && Number.isFinite(Number(value)) ? Number(value) : null;
const id = value => (typeof value === 'string' || typeof value === 'number') && /^[\d-]{1,40}$/.test(String(value)) ? String(value) : null;
const key = value => typeof value === 'string' ? value.normalize('NFKC')
  .replace(/\(주차관제센터\)/g, '').replace(/대구광역시|인천광역시|여수시|공영|시영|구영|공공|주차장/g, '')
  .replace(/[^가-힣a-zA-Z0-9]/g, '').toLowerCase() : '';
// Verified 2026-11-07 against the provider's address and the Kakao facility ID.
// Do not globally remove '환승', '택지', or numbered-lot distinctions.
const VERIFIED_ALIASES = {
  'incheon:10-10': { original: '부개역', name: '부개역환승공영주차장', kakaoId: '1303680165', address: /(?:부개동\s*206-1|수변로\s*22)(?:\s|$|\))/ },
  'incheon:5-5': { original: '계산1', name: '계산택지 제1공영주차장', kakaoId: '20563039', address: /계양문화로\s*111(?:\s|$|\))/ },
  // Official address is 우두리 799-76, matching Kakao lot 2, NOT the closer
  // lot 1 at 794-88. Confirmed by the municipal tourism parking directory:
  // https://m.yeosu.go.kr/tour/information/trafficinfo/public_parking
  'yeosu:2-2': { original: '돌산공원', name: '돌산공원 제2주차장', kakaoId: '2051129262', address: /우두리\s*799-76(?:\s|$|\))/ },
};
function nameMatches(place, row, provider) {
  if (key(place.name) === key(row.name)) return true;
  const alias = VERIFIED_ALIASES[`${provider}:${row.id}`];
  return !!alias && row.name === alias.original && key(place.name) === key(alias.name) && place.id === alias.kakaoId
    && alias.address.test(row.addressText || '') && alias.address.test(place.address || '');
}
function inBounds(lat, lon, provider) {
  const [south, north, west, east] = NATIONAL_PARKING_PROVIDERS[provider].bounds;
  return Number.isFinite(lat) && Number.isFinite(lon) && lat >= south && lat <= north && lon >= west && lon <= east;
}
function providerFor(place) {
  for (const [provider, meta] of Object.entries(NATIONAL_PARKING_PROVIDERS)) {
    if (inBounds(place.lat, place.lon, provider) && (!place.address || meta.address.test(place.address))) return provider;
  }
  return null;
}
function meters(a, b) {
  const r = Math.PI / 180, h = Math.sin((b.lat - a.lat) * r / 2) ** 2
    + Math.cos(a.lat * r) * Math.cos(b.lat * r) * Math.sin((b.lon - a.lon) * r / 2) ** 2;
  return 12742000 * Math.atan2(Math.sqrt(h), Math.sqrt(Math.max(0, 1 - h)));
}
function observedTime(value) {
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$/.test(value)) return null;
  const parsed = Date.parse(value.replace(' ', 'T') + '+09:00');
  return Number.isFinite(parsed) && new Date(parsed + 32400000).toISOString().slice(0, 19).replace('T', ' ') === value ? parsed : null;
}
function unknown(provider, reason, now, row = null, retrievedAt = null) {
  const meta = NATIONAL_PARKING_PROVIDERS[provider];
  return { status: 'unknown', availableSpaces: null, totalSpaces: null,
    observedAt: row?.observedAt == null ? null : iso(row.observedAt), fetchedAt: iso(now),
    ...(retrievedAt == null ? {} : { retrievedAt: iso(retrievedAt), timeBasis: row?.observedAt == null ? 'retrieved' : 'observed' }),
    source: meta.source, sourceUrl: meta.sourceUrl, reason, providerId: row?.id ? `${provider}:${row.id}` : null };
}
function location(row, provider) {
  const lat = number(row.lat), lon = number(row.lot ?? row.lng), name = row.pklt_nm ?? row.parkingName;
  const providerId = id(row.pklt_id ?? row.parkingId), section = row.sectnId == null ? null : id(row.sectnId);
  if (!providerId || (row.sectnId != null && !section) || !inBounds(lat, lon, provider) || key(name).length < 2) return null;
  return { id: section ? `${providerId}-${section}` : providerId, name, lat, lon,
    addressText: [row.addr, row.roadName, row.buildingMainNo, row.buildingSubNo, row.detailBuildingName]
      .filter(value => typeof value === 'string').join(' ') };
}
// Exported for deterministic tests. The response timestamp on the two portals
// is request time, not a sensor observation, and must never become observedAt.
export function parseNationalParkingRows(provider, data) {
  if (!['incheon', 'yeosu'].includes(provider) || data?.result !== true || !Array.isArray(data.data)
    || data.data.length > MAX_ROWS) throw new Error('invalid regional parking inventory');
  return data.data.flatMap(row => {
    if (!row || typeof row !== 'object') return [];
    const place = location(row, provider);
    if (!place) return [];
    const supported = provider === 'incheon'
      ? INCHEON_PUBLIC_IDS.has(Number(row.parkingId)) && ![57, 58].includes(Number(row.sectnId)) && row.linkYn === 'Y'
        && row.parkingDivisionCode === 'PLT-001'
      : row.publicOrganCode === 'PUO-001' && integer(row.baseYn) === 1 && ['PLT-001', 'PLT-002'].includes(row.parkingDivisionCode);
    return [{ ...place, total: integer(row.cellCount), free: integer(row.parkCount), observedAt: null,
      validTime: true, supported: supported && !restricted.test(place.name) }];
  });
}
function daeguRow(base, detail) {
  const real = detail?.rltmInfo, place = location(base, 'daegu');
  if (!place) return null;
  const timestamp = real?.data_clct_dt;
  const observedAt = timestamp == null || timestamp === '' ? null : observedTime(timestamp);
  const counts = real?.type === 'clct' ? { total: real.tot_prk_nocmprt, free: real.tot_rmnd_prk_nocmprt }
    : real?.type === 'dvr' ? { total: detail.oprkarInfo?.general_tot, free: real.general_rmnd } : {};
  return { ...place, total: integer(counts.total), free: integer(counts.free), observedAt,
    validTime: !timestamp || observedAt !== null, supported: !!real && ['clct', 'dvr'].includes(real.type)
      && base.pklt_se_cd === 'PRK001001' && !restricted.test(place.name) };
}
export function matchNationalParkingInventory(place, rows, { provider, now, retrievedAt } = {}) {
  const normalized = key(place.name);
  const matches = normalized.length < 2 ? [] : rows.filter(row => nameMatches(place, row, provider) && meters(place, row) <= 150);
  if (matches.length !== 1) return unknown(provider, 'unmatched', now, null, retrievedAt);
  const row = matches[0];
  if (!row.supported || restricted.test(place.name)) return unknown(provider, 'unsupported', now, row, retrievedAt);
  if (!row.validTime || !Number.isFinite(retrievedAt) || retrievedAt > now || row.observedAt > now
    || row.total === null || row.free === null || row.total < 1 || row.free > row.total)
    return unknown(provider, 'unavailable', now, row, retrievedAt);
  const basis = row.observedAt === null ? 'retrieved' : 'observed';
  if (now - (row.observedAt ?? retrievedAt) > (basis === 'observed' ? OBSERVED_FRESH_MS : RETRIEVED_FRESH_MS))
    return unknown(provider, 'stale', now, row, retrievedAt);
  return { ...unknown(provider, 'live', now, row, retrievedAt), status: row.free > 0 ? 'available' : 'full',
    totalSpaces: row.total, availableSpaces: row.free, timeBasis: basis };
}
async function json(url, fetchImpl, signal, body) {
  const isForm = body instanceof URLSearchParams;
  const response = await fetchImpl(new URL(url), { method: body ? 'POST' : 'GET', redirect: 'error', signal,
    headers: { Accept: 'application/json', ...(body ? { 'Content-Type': isForm ? 'application/x-www-form-urlencoded' : 'application/json' } : {}) },
    ...(body ? { body: isForm ? body.toString() : JSON.stringify(body) } : {}) });
  if (!response.ok || Number(response.headers?.get?.('content-length')) > MAX_BODY_BYTES) throw new Error('regional parking unavailable');
  const reader = response.body?.getReader?.();
  if (!reader) return response.json();
  const chunks = []; let length = 0;
  for (;;) {
    const chunk = await reader.read(); if (chunk.done) break;
    length += chunk.value.byteLength;
    if (length > MAX_BODY_BYTES) { await reader.cancel(); throw new Error('regional parking response too large'); }
    chunks.push(chunk.value);
  }
  return JSON.parse(Buffer.concat(chunks).toString('utf8'));
}
function cacheFor(fetchImpl) {
  if (!caches.has(fetchImpl)) caches.set(fetchImpl, new Map());
  return caches.get(fetchImpl);
}
async function cached(key, fetchImpl, clock, signal, loader) {
  const cache = cacheFor(fetchImpl), current = clock(), existing = cache.get(key);
  if (existing?.data && current >= existing.data.retrievedAt && current - existing.data.retrievedAt < CACHE_MS) return existing.data;
  if (existing?.pending) return existing.pending;
  const state = {};
  const pending = (async () => {
    try {
      const rows = await loader();
      if (signal.aborted) throw new Error('regional parking request cancelled');
      state.data = { rows, retrievedAt: clock() }; return state.data;
    } catch (error) { cache.delete(key); throw error; }
    finally { state.pending = null; }
  })();
  state.pending = pending;
  if (cache.size >= 128) cache.delete(cache.keys().next().value);
  cache.set(key, state);
  return pending;
}
async function fetchDaegu(places, fetchImpl, clock, signal) {
  const center = { lat: places.reduce((sum, p) => sum + p.lat, 0) / places.length,
    lot: places.reduce((sum, p) => sum + p.lon, 0) / places.length };
  const list = await cached(`daegu:list:${center.lat.toFixed(3)}:${center.lot.toFixed(3)}`, fetchImpl, clock, signal, async () => {
    const data = await json('https://pis.daegu.go.kr/parking/ajax/getPkltList', fetchImpl, signal, {
      ...center, radius: 5000, rltmYn: true, theme_cd: '', pklt_se_type_list: ['PRK002001', 'PRK002002', 'PRK002003']
        .map(type => ({ pklt_se_cd: 'PRK001001', pklt_type_cd: type })) });
    if (!Array.isArray(data?.result) || data.result.length > MAX_ROWS) throw new Error('invalid Daegu parking list');
    return data.result;
  });
  const selected = list.rows.filter(row => {
    const position = row && location(row, 'daegu');
    return position && places.some(place => key(place.name) === key(position.name) && meters(place, position) <= 150);
  }).slice(0, 30);
  const rows = [], queue = [...selected];
  // Fetch live details only for matching lots; list-level zeros are not vacancy.
  await Promise.all(Array.from({ length: Math.min(4, queue.length) }, async () => {
    while (queue.length && !signal.aborted) {
      const base = queue.shift();
      try {
        const result = await cached(`daegu:detail:${base.pklt_id}`, fetchImpl, clock, signal,
          () => json('https://pis.daegu.go.kr/parking/ajax/getPrkltDtlInfo', fetchImpl, signal, new URLSearchParams({ pklt_id: base.pklt_id })));
        const row = daeguRow(base, result.rows);
        if (row) rows.push({ ...row, retrievedAt: result.retrievedAt });
      } catch { const row = daeguRow(base, null); if (row) rows.push({ ...row, supported: true, retrievedAt: clock() }); }
    }
  }));
  return { rows, retrievedAt: clock() };
}
async function inventory(provider, places, fetchImpl, clock) {
  const controller = new AbortController(); let timer;
  try {
    return await Promise.race([
      provider === 'daegu' ? fetchDaegu(places, fetchImpl, clock, controller.signal)
        : cached(provider, fetchImpl, clock, controller.signal, async () => parseNationalParkingRows(provider,
          await json(provider === 'incheon' ? 'https://smartparking.insiseol.or.kr/api/parking/realtime/list'
            : 'https://parking.yumcorp.or.kr/api/parking/realtime/list', fetchImpl, controller.signal))),
      new Promise((_resolve, reject) => { timer = setTimeout(() => { controller.abort(); reject(new Error('regional parking timeout')); }, 8000); }),
    ]);
  } finally { clearTimeout(timer); controller.abort(); }
}
export async function enrichNationalParkingPlaces(places, { fetchImpl = fetch, now = Date.now } = {}) {
  const grouped = new Map(), inventories = new Map();
  for (const place of places) { const provider = providerFor(place); if (provider) grouped.set(provider, [...(grouped.get(provider) || []), place]); }
  await Promise.all([...grouped].map(async ([provider, group]) => {
    try { inventories.set(provider, await inventory(provider, group, fetchImpl, now)); } catch { /* no stale fallback on failure */ }
  }));
  const time = now();
  return places.map(place => {
    const provider = providerFor(place);
    if (!provider) return place;
    const data = inventories.get(provider);
    const row = provider === 'daegu' && data?.rows.find(r => key(r.name) === key(place.name) && meters(place, r) <= 150);
    const parking = !data ? unknown(provider, 'unavailable', time)
      : matchNationalParkingInventory(place, data.rows, { provider, now: time, retrievedAt: row?.retrievedAt ?? data.retrievedAt });
    return { ...place, parkingStatus: parking.status, parking };
  });
}
