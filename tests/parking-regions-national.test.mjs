import test from 'node:test';
import assert from 'node:assert/strict';
import { enrichNationalParkingPlaces, parseNationalParkingRows, matchNationalParkingInventory } from '../server/parking-regions-national.mjs';

const time = Date.parse('2026-11-06T15:01:00Z');
const place = (provider = 'incheon', changes = {}) => ({
  id: '123', name: provider === 'incheon' ? '부개역 공영주차장' : provider === 'yeosu' ? '돌산공원 공영주차장' : '국채보상운동기념공원 공영주차장',
  lat: provider === 'incheon' ? 37.4893855 : provider === 'yeosu' ? 34.730935 : 35.868824,
  lon: provider === 'incheon' ? 126.7396225 : provider === 'yeosu' ? 127.741110 : 128.600292,
  parkingStatus: 'unknown', ...changes,
});
const portalRow = (provider = 'incheon', changes = {}) => ({
  parkingId: provider === 'incheon' ? 10 : 2, sectnId: provider === 'incheon' ? 10 : 2,
  parkingName: provider === 'incheon' ? '부개역' : '돌산공원',
  lat: String(place(provider).lat), lng: String(place(provider).lon),
  cellCount: 193, parkCount: 68, linkYn: 'Y', parkingDivisionCode: 'PLT-001', publicOrganCode: 'PUO-001', baseYn: 1,
  ...changes,
});
const portal = (rows, extra = {}) => ({ result: true, dateTime: '2026-11-07 00:01:00', data: rows, ...extra });
const daeguBase = (changes = {}) => ({ pklt_id: '150-2-000002', pklt_nm: place('daegu').name,
  lat: place('daegu').lat, lot: place('daegu').lon, pklt_se_cd: 'PRK001001', pklt_type_cd: 'PRK002002',
  tot_prk_nocmprt: 999, tot_rmnd_prk_nocmprt: 0, ...changes });
const daeguDetail = (changes = {}) => ({ rltmInfo: { type: 'clct', tot_prk_nocmprt: 456,
  tot_rmnd_prk_nocmprt: 384, data_clct_dt: '2026-11-07 00:00:43', ...changes } });
const match = (rows, overrides = {}) => matchNationalParkingInventory(place(), parseNationalParkingRows('incheon', portal(rows)),
  { provider: 'incheon', now: time, retrievedAt: time - 1000, ...overrides });

test('Incheon official parkCount means free spaces, and zero means full', () => {
  const available = match([portalRow()]);
  assert.equal(available.status, 'available'); assert.equal(available.availableSpaces, 68); assert.equal(available.totalSpaces, 193);
  assert.equal(available.providerId, 'incheon:10-10'); assert.equal(available.timeBasis, 'retrieved'); assert.equal(available.observedAt, null);
  assert.equal(available.retrievedAt, new Date(time - 1000).toISOString()); assert.equal(available.fetchedAt, new Date(time).toISOString());
  assert.equal(match([portalRow('incheon', { parkCount: 0 })]).status, 'full');
});

test('portal response dateTime is never represented as a sensor observation', () => {
  const rows = parseNationalParkingRows('yeosu', portal([portalRow('yeosu')], { dateTime: '2099-01-01 12:00:00' }));
  const result = matchNationalParkingInventory(place('yeosu'), rows, { provider: 'yeosu', now: time, retrievedAt: time });
  assert.equal(result.status, 'available'); assert.equal(result.observedAt, null); assert.equal(result.timeBasis, 'retrieved');
});

test('snapshot expiry uses fixed upstream retrieval, including future-clock rejection', () => {
  assert.equal(match([portalRow()], { now: time + 89000 }).status, 'available');
  const stale = match([portalRow()], { now: time + 89001 });
  assert.equal(stale.reason, 'stale'); assert.equal(stale.availableSpaces, null);
  assert.equal(match([portalRow()], { retrievedAt: time + 1 }).reason, 'unavailable');
});

test('invalid counts never silently become full, available, or negative spaces', () => {
  for (const changes of [{ parkCount: null }, { parkCount: '' }, { parkCount: true }, { parkCount: -1 }, { parkCount: 194 },
    { parkCount: '1.5' }, { cellCount: 0 }, { cellCount: null }, { cellCount: Infinity }]) {
    const result = match([portalRow('incheon', changes)]);
    assert.equal(result.status, 'unknown'); assert.equal(result.reason, 'unavailable'); assert.equal(result.availableSpaces, null);
  }
});

test('Incheon excludes unlinked, monthly-only, non-public and non-published lots', () => {
  for (const changes of [{ linkYn: 'N' }, { linkYn: undefined }, { parkingId: 999 }, { sectnId: 57 },
    { parkingDivisionCode: 'PLT-005' }]) assert.equal(match([portalRow('incheon', changes)]).reason, 'unsupported');
});

test('Yeosu accepts official live public lots but not extra or restricted inventory', () => {
  for (const changes of [{ publicOrganCode: 'PRIVATE' }, { baseYn: 0 }, { baseYn: true }, { parkingDivisionCode: 'EXTRA' }]) {
    const rows = parseNationalParkingRows('yeosu', portal([portalRow('yeosu', changes)]));
    assert.equal(matchNationalParkingInventory(place('yeosu'), rows, { provider: 'yeosu', now: time, retrievedAt: time }).reason, 'unsupported');
  }
  const p = place('incheon', { name: '부개역 월정기 주차장' });
  const rows = parseNationalParkingRows('incheon', portal([portalRow('incheon', { parkingName: '부개역 월정기' })]));
  assert.equal(matchNationalParkingInventory(p, rows, { provider: 'incheon', now: time, retrievedAt: time }).reason, 'unsupported');
});

test('names, numbered sections and 150-meter coordinates must match unambiguously', () => {
  for (const rows of [[portalRow('incheon', { parkingName: '부개역2' })], [portalRow('incheon', { lat: '37.50' })],
    [portalRow(), portalRow()], [portalRow('incheon', { lat: '' })], [portalRow('incheon', { lng: null })],
    [portalRow('incheon', { parkingId: '../secret' })]]) assert.equal(match(rows).reason, 'unmatched');
});

test('verified Incheon aliases require exact provider and Kakao IDs, source name, matching address and location', () => {
  const target = place('incheon', { id: '1303680165', name: '부개역환승공영주차장', address: '인천 부평구 부개동 206-1' });
  const source = portalRow('incheon', { addr: '부개동 206-1', roadName: '수변로', buildingMainNo: '22' });
  const matchAlias = (p, row) => matchNationalParkingInventory(p, parseNationalParkingRows('incheon', portal([row])),
    { provider: 'incheon', now: time, retrievedAt: time });
  assert.equal(matchAlias(target, source).status, 'available');
  for (const [p, r] of [[{ ...target, id: 'another' }, source], [{ ...target, address: '인천 부평구 부개동 207' }, source],
    [target, { ...source, addr: '', roadName: '', buildingMainNo: '' }], [target, { ...source, sectnId: 11 }],
    [target, { ...source, parkingName: '부개역2' }], [{ ...target, lat: 37.50 }, source]]) assert.equal(matchAlias(p, r).reason, 'unmatched');
  const gyeyang = place('incheon', { id: '20563039', name: '계산택지 제1공영주차장', address: '인천 계양구 계양문화로 111',
    lat: 37.5406722584394, lon: 126.736747597226 });
  const gyeRow = portalRow('incheon', { parkingId: 5, sectnId: 5, parkingName: '계산1', roadName: '계양문화로', buildingMainNo: '111',
    lat: '37.5406681', lng: '126.7367455' });
  assert.equal(matchAlias(gyeyang, gyeRow).status, 'available');
  assert.equal(matchAlias({ ...gyeyang, name: '계산택지 제2공영주차장' }, gyeRow).reason, 'unmatched');
});

test('Dolsan aliases only address-verified lot 2, never closer lot 1', () => {
  const source = portalRow('yeosu', { roadName: '우두리', detailBuildingName: '799-76' });
  const rows = parseNationalParkingRows('yeosu', portal([source]));
  const target = place('yeosu', { id: '2051129262', name: '돌산공원 제2주차장', address: '전남광주통합특별시 여수시 돌산읍 우두리 799-76',
    lat: 34.7311716303144, lon: 127.740848337273 });
  const options = { provider: 'yeosu', now: time, retrievedAt: time };
  assert.equal(matchNationalParkingInventory(target, rows, options).status, 'available');
  const first = place('yeosu', { id: '2051147113', name: '돌산공원 제1주차장', address: '전남광주통합특별시 여수시 돌산읍 우두리 794-88' });
  assert.equal(matchNationalParkingInventory(first, rows, options).reason, 'unmatched');
  assert.equal(matchNationalParkingInventory({ ...target, address: first.address }, rows, options).reason, 'unmatched');
});

test('Yeosu city matching survives province renaming without accepting neighboring cities', async () => {
  let requests = 0;
  const fetchImpl = async () => { requests++; return Response.json(portal([portalRow('yeosu')])); };
  const target = place('yeosu', { address: '전남광주통합특별시 여수시 돌산읍' });
  const outside = place('yeosu', { address: '전남광주통합특별시 광양시' });
  const result = await enrichNationalParkingPlaces([target, outside], { fetchImpl, now: () => time });
  assert.equal(result[0].parkingStatus, 'available'); assert.equal(result[1], outside); assert.equal(requests, 1);
});

test('malformed portals and excessive result lists fail closed', () => {
  for (const data of [null, {}, portal([], { result: false }), portal('invalid'), portal(Array(2001).fill({}))])
    assert.throws(() => parseNationalParkingRows('incheon', data));
  assert.throws(() => parseNationalParkingRows('other', portal([])));
});

test('unsupported cities and other providers remain untouched and make no requests', async () => {
  const targets = [place('incheon', { address: '서울 강서구', lat: 37.57, lon: 126.78 }),
    place('daegu', { lat: 35.85, lon: 128.60, address: '경북 경산시' }),
    place('incheon', { lat: 37.28, lon: 127.01, name: '수원 주차장' }),
    place('incheon', { lat: 37.795, lon: 128.914, name: '강릉 주차장' })];
  const result = await enrichNationalParkingPlaces(targets, { fetchImpl: () => { throw new Error('unexpected request'); }, now: () => time });
  for (let i = 0; i < result.length; i++) assert.equal(result[i], targets[i]);
});

test('all three cities request only their public endpoints without credentials', async () => {
  const urls = [];
  const fetchImpl = async (url, options) => {
    urls.push(url.href); assert.equal(options.redirect, 'error'); assert.ok(options.signal instanceof AbortSignal);
    assert.equal(options.headers.Authorization, undefined); assert.equal(options.headers.Cookie, undefined);
    if (url.hostname === 'pis.daegu.go.kr') {
      assert.equal(options.method, 'POST');
      if (url.pathname.endsWith('getPkltList')) {
        const body = JSON.parse(options.body); assert.equal(body.rltmYn, true); assert.equal(body.radius, 5000);
        return Response.json({ result: [daeguBase()] });
      }
      assert.equal(new URLSearchParams(options.body).get('pklt_id'), daeguBase().pklt_id);
      return Response.json(daeguDetail());
    }
    assert.equal(options.method, 'GET'); assert.equal(url.pathname, '/api/parking/realtime/list');
    return Response.json(portal([portalRow(url.hostname.startsWith('parking.') ? 'yeosu' : 'incheon')]));
  };
  const result = await enrichNationalParkingPlaces([place('daegu', { address: '대구 중구' }), place('incheon', { address: '인천 부평구' }),
    place('yeosu', { address: '전남 여수시' })], { fetchImpl, now: () => time });
  assert.equal(urls.length, 4); assert.deepEqual(result.map(r => r.parkingStatus), ['available', 'available', 'available']);
  assert.deepEqual(result.map(r => r.parking.availableSpaces), [384, 68, 68]);
  assert.equal(result[0].parking.timeBasis, 'observed'); assert.equal(result[0].parking.observedAt, '2026-11-06T15:00:43.000Z');
});

test('Daegu list-level zeros are never mistaken for live occupancy', async () => {
  const fetchImpl = async url => Response.json(url.pathname.endsWith('getPkltList') ? { result: [daeguBase()] } : daeguDetail());
  const [result] = await enrichNationalParkingPlaces([place('daegu')], { fetchImpl, now: () => time });
  assert.equal(result.parking.availableSpaces, 384); assert.equal(result.parking.totalSpaces, 456);
});

test('Daegu general-vehicle DVR spaces exclude disabled-only vacancies', async () => {
  const fetchImpl = async url => Response.json(url.pathname.endsWith('getPkltList') ? { result: [daeguBase()] }
    : { rltmInfo: { type: 'dvr', general_rmnd: 0, handicap_rmnd: 8, data_clct_dt: '2026-11-07 00:00:43' }, oprkarInfo: { general_tot: 50, handicap_tot: 8 } });
  const [result] = await enrichNationalParkingPlaces([place('daegu')], { fetchImpl, now: () => time });
  assert.equal(result.parkingStatus, 'full'); assert.equal(result.parking.totalSpaces, 50);
});

test('Daegu stale, malformed and future observations never fall back to snapshot availability', async () => {
  for (const timestamp of ['2026-11-06 23:00:00', '2026-02-30 00:00:00', '2026-11-07 00:01:01']) {
    const fetchImpl = async url => Response.json(url.pathname.endsWith('getPkltList') ? { result: [daeguBase()] }
      : daeguDetail({ data_clct_dt: timestamp }));
    const [result] = await enrichNationalParkingPlaces([place('daegu')], { fetchImpl, now: () => time });
    assert.equal(result.parkingStatus, 'unknown'); assert.equal(result.parking.availableSpaces, null);
    assert.equal(result.parking.reason, timestamp.startsWith('2026-11-06') ? 'stale' : 'unavailable');
  }
});

test('missing Daegu observations explicitly use short-lived retrieved snapshots', async () => {
  const fetchImpl = async url => Response.json(url.pathname.endsWith('getPkltList') ? { result: [daeguBase()] }
    : daeguDetail({ data_clct_dt: null }));
  const [result] = await enrichNationalParkingPlaces([place('daegu')], { fetchImpl, now: () => time });
  assert.equal(result.parkingStatus, 'available'); assert.equal(result.parking.observedAt, null); assert.equal(result.parking.timeBasis, 'retrieved');
});

test('Daegu fetches details for matching lots only and rejects ambiguous matches', async () => {
  let detailCalls = 0;
  const fetchImpl = async url => {
    if (url.pathname.endsWith('getPkltList')) return Response.json({ result: [daeguBase(), daeguBase({ pklt_id: '999', pklt_nm: '다른 주차장' })] });
    detailCalls++; return Response.json(daeguDetail());
  };
  await enrichNationalParkingPlaces([place('daegu')], { fetchImpl, now: () => time }); assert.equal(detailCalls, 1);
  const duplicateFetch = async url => Response.json(url.pathname.endsWith('getPkltList') ? { result: [daeguBase(), daeguBase()] } : daeguDetail());
  const [result] = await enrichNationalParkingPlaces([place('daegu')], { fetchImpl: duplicateFetch, now: () => time });
  assert.equal(result.parking.reason, 'unmatched');
});

test('30-second caching shares concurrent requests and never refreshes retrieval age', async () => {
  let requests = 0, current = time;
  const fetchImpl = async () => { requests++; return Response.json(portal([portalRow()])); };
  const options = { fetchImpl, now: () => current };
  const [first, concurrent] = await Promise.all([enrichNationalParkingPlaces([place()], options), enrichNationalParkingPlaces([place()], options)]);
  assert.equal(requests, 1); assert.equal(first[0].parking.retrievedAt, concurrent[0].parking.retrievedAt);
  current += 29000;
  const [cached] = await enrichNationalParkingPlaces([place()], options);
  assert.equal(requests, 1); assert.equal(cached.parking.retrievedAt, first[0].parking.retrievedAt);
  assert.equal(cached.parking.fetchedAt, iso(current));
  current += 1000; await enrichNationalParkingPlaces([place()], options); assert.equal(requests, 2);
});
const iso = value => new Date(value).toISOString();

test('expired portal cache is never served when upstream fails, and can recover', async () => {
  let current = time, failed = false;
  const fetchImpl = async () => { if (failed) throw new Error('private upstream secret'); return Response.json(portal([portalRow()])); };
  const options = { fetchImpl, now: () => current };
  assert.equal((await enrichNationalParkingPlaces([place()], options))[0].parkingStatus, 'available');
  current += 30000; failed = true;
  const [result] = await enrichNationalParkingPlaces([place()], options);
  assert.equal(result.parkingStatus, 'unknown'); assert.equal(result.parking.reason, 'unavailable'); assert.equal(result.parking.availableSpaces, null);
  assert.equal(JSON.stringify(result).includes('secret'), false);
  failed = false; assert.equal((await enrichNationalParkingPlaces([place()], options))[0].parkingStatus, 'available');
});

test('Daegu detail failure cannot reuse list counts or prior live details', async () => {
  let current = time, failed = false;
  const fetchImpl = async url => {
    if (url.pathname.endsWith('getPkltList')) return Response.json({ result: [daeguBase()] });
    if (failed) throw new Error('unavailable'); return Response.json(daeguDetail());
  };
  const options = { fetchImpl, now: () => current };
  assert.equal((await enrichNationalParkingPlaces([place('daegu')], options))[0].parkingStatus, 'available');
  current += 30000; failed = true;
  const [result] = await enrichNationalParkingPlaces([place('daegu')], options);
  assert.equal(result.parkingStatus, 'unknown'); assert.equal(result.parking.reason, 'unavailable');
});

test('provider failure is isolated, preserving independently verified cities', async () => {
  const fetchImpl = async url => { if (url.hostname.includes('insiseol')) throw new Error('offline'); return Response.json(portal([portalRow('yeosu')])); };
  const result = await enrichNationalParkingPlaces([place(), place('yeosu')], { fetchImpl, now: () => time });
  assert.equal(result[0].parking.reason, 'unavailable'); assert.equal(result[1].parkingStatus, 'available');
});

test('HTTP errors, HTML bodies and excessive responses are bounded and fail closed', async () => {
  for (const response of [new Response('denied', { status: 403 }), new Response('<html>error</html>'),
    new Response('{}', { headers: { 'content-length': '3000001' } }), new Response(' '.repeat(3000001))]) {
    const [result] = await enrichNationalParkingPlaces([place()], { fetchImpl: async () => response, now: () => time });
    assert.equal(result.parkingStatus, 'unknown'); assert.equal(result.parking.reason, 'unavailable');
  }
});
