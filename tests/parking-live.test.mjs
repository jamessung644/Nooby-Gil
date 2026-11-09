import test from 'node:test';
import assert from 'node:assert/strict';
import { assessParkingFreshness, enrichParkingPlaces, matchParkingInventory, parseParkingSheet, PARKING_FRESH_MS } from '../server/parking-live.mjs';
import { searchNearby } from '../server/nearby.mjs';

const time = Date.parse('2026-11-06T14:16:43.000Z');
const place = (overrides = {}) => ({ id: '2029879158', name: '방화역 동 공영주차장', lat: 37.5773, lon: 126.8133, distanceMeters: 58, ...overrides });
const row = (overrides = {}) => ({ PKLT_CD: '172971', PKLT_NM: '방화역(동) 공영주차장(시)', PRK_STTS_YN: '1', TPKCT: '67', NOW_PRK_VHCL_CNT: '67', NOW_PRK_VHCL_UPDT_TM: '2026-11-06 23:16:43', ...overrides });
const positionRow = (overrides = {}) => ({ PKLT_CD: '172971', PKLT_NM: '방화역(동) 공영주차장(시)', LAT: '37.5773', LOT: '126.8133', ...overrides });
const positions = () => new Map([['172971', [{ lat: 37.5773, lon: 126.8133, name: '방화역동' }]]]);
const sheet = (rows, total = rows.length) => Response.json({ result: 'ok', page: { totalCount: total }, list: rows });
const mockInventory = (rows = [row()], positionRows = [positionRow()]) => async (url, options) => {
  assert.equal(url.origin, 'https://data.seoul.go.kr'); assert.equal(url.pathname, '/dataList/dataView.do');
  assert.equal(options.redirect, 'error'); assert.equal(options.method, 'POST'); assert.ok(options.signal instanceof AbortSignal);
  return sheet(url.searchParams.get('infId') === 'OA-21709' ? rows : positionRows);
};

test('final response reassesses original timestamps after other city requests finish', () => {
  const original = matchParkingInventory(place(), [row({ NOW_PRK_VHCL_CNT: '25' })], positions(), { now: time });
  const expired = assessParkingFreshness(original, time + PARKING_FRESH_MS + 1);
  assert.equal(expired.status, 'unknown'); assert.equal(expired.reason, 'stale'); assert.equal(expired.availableSpaces, null);
  assert.equal(expired.observedAt, original.observedAt); assert.equal(expired.fetchedAt, new Date(time + PARKING_FRESH_MS + 1).toISOString());
  const snapshot = { ...original, observedAt: null, retrievedAt: new Date(time).toISOString(), timeBasis: 'retrieved' };
  assert.equal(assessParkingFreshness(snapshot, time + 90000).status, 'available');
  const agedSnapshot = assessParkingFreshness(snapshot, time + 90001);
  assert.equal(agedSnapshot.reason, 'stale'); assert.equal(agedSnapshot.totalSpaces, null); assert.equal(agedSnapshot.retrievedAt, snapshot.retrievedAt);
  assert.equal(assessParkingFreshness(snapshot, time - 1).status, 'unknown');
});

test('public sheet grammar is parsed without executing script or altering quoted data', () => {
  const parsed = parseParkingSheet('{result:"ok",page:{totalCount:1,},list:[{PKLT_NM:"이름 key: 값 ,]",LAT:"37.5",},]}');
  assert.equal(parsed.list[0].PKLT_NM, '이름 key: 값 ,]');
  for (const text of ['{result:(()=>{throw 1})()}', '{result:undefined}', '{result:"ok",list:[/*comment*/]}']) assert.throws(() => parseParkingSheet(text));
});

test('verified coordinates, name and fresh KST time distinguish free spaces from full', () => {
  const full = matchParkingInventory(place(), [row()], positions(), { now: time + 23000 });
  assert.equal(full.status, 'full'); assert.equal(full.availableSpaces, 0); assert.equal(full.totalSpaces, 67);
  assert.equal(full.observedAt, '2026-11-06T14:16:43.000Z'); assert.equal(full.reason, 'live'); assert.equal(full.providerId, 'seoul:172971');
  const available = matchParkingInventory(place(), [row({ NOW_PRK_VHCL_CNT: '25' })], positions(), { now: time });
  assert.equal(available.status, 'available'); assert.equal(available.availableSpaces, 42);
});

test('expired, invalid and future timestamps never turn into available counts', () => {
  const exactly = matchParkingInventory(place(), [row()], positions(), { now: time + PARKING_FRESH_MS });
  assert.equal(exactly.status, 'full');
  const stale = matchParkingInventory(place(), [row()], positions(), { now: time + PARKING_FRESH_MS + 1 });
  assert.equal(stale.reason, 'stale'); assert.equal(stale.status, 'unknown'); assert.equal(stale.availableSpaces, null);
  for (const timestamp of ['', '2026-02-30 23:16:43', '2026-11-06T23:16:43', '2026-11-06 23:16:44']) {
    const result = matchParkingInventory(place(), [row({ NOW_PRK_VHCL_UPDT_TM: timestamp })], positions(), { now: time });
    assert.equal(result.status, 'unknown'); assert.equal(result.reason, 'unavailable'); assert.equal(result.availableSpaces, null);
  }
});

test('missing live flag and inconsistent occupancy are unknown, not zero or negative vacancies', () => {
  for (const change of [{ PRK_STTS_YN: '2' }, { PRK_STTS_YN: '0' }, { TPKCT: '0' }, { TPKCT: '' }, { TPKCT: null }, { TPKCT: true }, { NOW_PRK_VHCL_CNT: '' }, { NOW_PRK_VHCL_CNT: '-1' }, { NOW_PRK_VHCL_CNT: '68' }, { NOW_PRK_VHCL_CNT: '1.5' }]) {
    const result = matchParkingInventory(place(), [row(change)], positions(), { now: time });
    assert.equal(result.status, 'unknown'); assert.equal(result.availableSpaces, null); assert.equal(result.totalSpaces, null);
  }
});

test('different east/west lots, distant coordinates, missing positions and ambiguous records never cross-match', () => {
  for (const [target, rows, coords] of [
    [place({ name: '방화역 서 공영주차장' }), [row()], positions()],
    [place({ lat: 37.58 }), [row()], positions()],
    [place(), [row()], new Map()],
    [place(), [row(), row()], positions()],
    [place({ name: '공영주차장' }), [row({ PKLT_NM: '공영주차장' })], positions()],
  ]) assert.equal(matchParkingInventory(target, rows, coords, { now: time }).reason, 'unmatched');
});

test('tour coach and other explicitly restricted lots never advertise passenger-car vacancy', () => {
  const target = place({ name: '남산 관광버스전용 주차장' });
  const source = row({ PKLT_NM: '남산 관광버스전용 주차장(시)', NOW_PRK_VHCL_CNT: '1' });
  const coords = new Map([['172971', [{ lat: target.lat, lon: target.lon, name: '남산관광버스전용' }]]]);
  const result = matchParkingInventory(target, [source], coords, { now: time });
  assert.equal(result.status, 'unknown'); assert.equal(result.reason, 'unsupported'); assert.equal(result.availableSpaces, null);
});

test('outside enabled city coverage makes no public-provider requests and marks unsupported', async () => {
  let requests = 0;
  const result = await enrichParkingPlaces([place({ lat: 36.56, lon: 128.73 })], { fetchImpl: () => { requests++; throw new Error('must not fetch'); }, now: () => time });
  assert.equal(requests, 0);
  assert.equal(result[0].parkingStatus, 'unknown'); assert.equal(result[0].parking.reason, 'unsupported'); assert.equal(result[0].parking.source, '');
});

test('public inventory cache coalesces calls, renews assessment time and ages original observations', async () => {
  let current = time, requests = 0;
  const provider = mockInventory();
  const fetchImpl = async (...args) => { requests++; return provider(...args); };
  const [first, concurrent] = await Promise.all([enrichParkingPlaces([place()], { fetchImpl, now: () => current }), enrichParkingPlaces([place()], { fetchImpl, now: () => current })]);
  assert.equal(first[0].parkingStatus, 'full'); assert.equal(concurrent[0].parkingStatus, 'full'); assert.equal(requests, 2);
  current += 20000;
  const cached = await enrichParkingPlaces([place()], { fetchImpl, now: () => current });
  assert.equal(requests, 2); assert.equal(cached[0].parking.fetchedAt, new Date(current).toISOString());
  assert.equal(cached[0].parking.observedAt, new Date(time).toISOString());
  current += PARKING_FRESH_MS;
  const aged = await enrichParkingPlaces([place()], { fetchImpl, now: () => current });
  assert.equal(requests, 3); assert.equal(aged[0].parking.reason, 'stale'); assert.equal(aged[0].parking.availableSpaces, null);
});

test('a stalled KOTSA fallback cannot suppress a successful municipal inventory', async t => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const seoul = mockInventory([row({ NOW_PRK_VHCL_CNT: '25' })]);
  let result, kotsaCalls = 0;
  const pending = enrichParkingPlaces([place()], { env: { DATA_GO_KR_KEY: 'test-stalled-fallback' }, now: () => time,
    fetchImpl: async (url, options) => {
      if (new URL(url).hostname === 'apis.data.go.kr') { kotsaCalls++; return new Promise(() => {}); }
      return seoul(url, options);
    } }).then(value => { result = value; });
  await new Promise(setImmediate);
  assert.equal(kotsaCalls, 1);
  t.mock.timers.tick(8000);
  await new Promise(setImmediate);
  assert.ok(result, 'nearby results must finish when the KOTSA deadline expires');
  await pending;
  assert.equal(result[0].parkingStatus, 'available');
  assert.equal(result[0].parking.availableSpaces, 42);
  assert.equal(result[0].parking.providerId, 'seoul:172971');
});

test('provider failure never reuses an earlier available count, and a later recovery works', async () => {
  let current = time, broken = false;
  const provider = mockInventory([row({ NOW_PRK_VHCL_CNT: '1' })]);
  const fetchImpl = async (...args) => { if (broken) throw new Error('upstream failed'); return provider(...args); };
  assert.equal((await enrichParkingPlaces([place()], { fetchImpl, now: () => current }))[0].parkingStatus, 'available');
  current += 31000; broken = true;
  const failed = (await enrichParkingPlaces([place()], { fetchImpl, now: () => current }))[0];
  assert.equal(failed.parking.reason, 'unavailable'); assert.equal(failed.parking.availableSpaces, null);
  broken = false;
  assert.equal((await enrichParkingPlaces([place()], { fetchImpl, now: () => current }))[0].parkingStatus, 'available');
});

test('bounded position pages join by official code and never confuse duplicate curb positions with separate lots', async () => {
  const pages = [];
  const fetchImpl = async url => {
    if (url.searchParams.get('infId') === 'OA-21709') return sheet([row()]);
    const page = Number(url.searchParams.get('pageNo')); pages.push(page);
    return sheet(page === 11 ? [positionRow(), positionRow({ LAT: '37.5774' })] : [], 1001);
  };
  const result = await enrichParkingPlaces([place()], { fetchImpl, now: () => time });
  assert.deepEqual(pages, [1, 11]); assert.equal(result[0].parkingStatus, 'full');
});

test('invalid sheet schema and oversized data become unknown without leaking provider errors', async () => {
  for (const fetchImpl of [async () => new Response('private-upstream-data', { status: 500 }), async () => sheet([row()], -1), async () => new Response('x', { headers: { 'content-length': '3000001' } })]) {
    const result = await enrichParkingPlaces([place()], { fetchImpl, now: () => time });
    assert.equal(result[0].parking.reason, 'unavailable'); assert.ok(!JSON.stringify(result).includes('private-upstream-data'));
  }
});

test('availability outranks unknown and full, preserving a full focus even below the first ten', async () => {
  const documents = Array.from({ length: 15 }, (_, index) => ({ id: String(1000 + index), place_name: index === 0 ? '방화역 동 공영주차장' : index === 14 ? '방화역 서 공영주차장' : `건물 ${index} 주차장`,
    x: '126.8133', y: '37.5773', distance: String(10 + index * 10), category_group_code: 'PK6', address_name: '서울 강서구 방화동', phone: '' }));
  const rows = [row(), row({ PKLT_CD: '172970', PKLT_NM: '방화역(서) 공영주차장(시)', NOW_PRK_VHCL_CNT: '26', TPKCT: '69' })];
  const coords = [positionRow(), positionRow({ PKLT_CD: '172970', PKLT_NM: '방화역(서) 공영주차장(시)' })];
  const publicProvider = mockInventory(rows, coords);
  const fetchImpl = async (url, options) => url.hostname === 'dapi.kakao.com' ? Response.json({ documents }) : publicProvider(url, options);
  const result = await searchNearby({ kind: 'parking', lat: 37.5774, lon: 126.8128, focusId: '1000' }, { env: { KAKAO_REST_API_KEY: 'test-key' }, fetchImpl, now: () => time });
  assert.equal(result.places.length, 10); assert.equal(result.places[0].id, '1014'); assert.equal(result.places[0].parkingStatus, 'available');
  assert.equal(result.focus.id, '1000'); assert.equal(result.focus.parkingStatus, 'full'); assert.equal(result.focus.parking.availableSpaces, 0);
  assert.ok(!result.places.some(p => p.id === '1000'));
});

test('focus identifiers are parking-only and validated before provider calls', async () => {
  for (const extra of [{ focusId: '' }, { focusId: 'https://bad.invalid' }, { focusId: 123 }, { kind: 'restaurant', focusId: '123' }])
    await assert.rejects(searchNearby({ kind: 'parking', lat: 37.5774, lon: 126.8128, ...extra }, { fetchImpl: () => { throw new Error('must not fetch'); } }), error => error.status === 400);
});
