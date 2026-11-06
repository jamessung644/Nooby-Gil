import test from 'node:test';
import assert from 'node:assert/strict';
import { enrichEasternParkingPlaces, parseBusanParkingInventory, parseGangneungParkingMap, matchBusanParking, matchGangneungParkingDetail, EASTERN_SNAPSHOT_FRESH_MS } from '../server/parking-regions-east.mjs';

const timestamp = Date.parse('2026-11-06T15:01:24Z');
const busanPlace = (extra = {}) => ({ id: '123', name: '삼락재첩거리 공영주차장', lat: 35.192803, lon: 128.98537, ...extra });
const busanRow = (extra = {}) => ({ parkingId: 2, sectnId: 2, parkingName: '삼락재첩거리', lat: '35.192803', lng: '128.98537', cellCount: 126, parkCount: 52, ...extra });
const gangPlace = (extra = {}) => ({ id: '456', name: '강문해변 제2공영주차장', lat: 37.795748, lon: 128.915345, ...extra });
const gangRow = (extra = {}) => ({ code: 'PLOT000328', name: '강문해변 제2공영주차장', wgs84Y: 37.795748, wgs84X: 128.915345, commStatImgCd: 'l', ...extra });
const gangDetail = (extra = {}) => ({ rows: [{ plotId: 'PLOT000328', facLocation: '강문해변 제2공영주차장', totalSpace: '84', occupancy: '10', collectYn: 'Y', tot: 84, availSpace: 74, ...extra }] });
const busanData = (rows = [busanRow()]) => ({ result: true, dateTime: '2030-01-01 12:00:00', data: rows });
const clock = () => timestamp;

test('Busan treats parkCount as vacancies per its official popup and never borrows the generic response dateTime', async () => {
  const result = await enrichEasternParkingPlaces([busanPlace()], { now: clock, fetchImpl: async (url, options) => {
    assert.equal(url.href, 'https://bsparking.bisco.or.kr/api/parking/realtime/list');
    assert.equal(options.redirect, 'error'); assert.equal(options.headers.Authorization, undefined);
    return Response.json(busanData());
  } });
  assert.equal(result[0].parkingStatus, 'available'); assert.equal(result[0].parking.availableSpaces, 52);
  assert.equal(result[0].parking.totalSpaces, 126); assert.equal(result[0].parking.observedAt, null);
  assert.equal(result[0].parking.timeBasis, 'retrieved'); assert.equal(result[0].parking.retrievedAt, new Date(timestamp).toISOString());
  assert.ok(!JSON.stringify(result).includes('2030')); assert.equal(result[0].parking.providerId, 'busan:2:2');
});

test('Gangneung fetches only the uniquely matched public detail and preserves exact destination', async () => {
  const requests = [];
  const fetchImpl = async (url, options) => {
    requests.push(url.href); assert.equal(options.headers.Authorization, undefined); assert.equal(options.redirect, 'error');
    if (url.pathname === '/main/selectParkList') return Response.json({ rows: [gangRow()] });
    assert.equal(url.searchParams.get('plotId'), 'PLOT000328'); return Response.json(gangDetail());
  };
  const target = gangPlace(); const result = await enrichEasternParkingPlaces([target], { now: clock, fetchImpl });
  assert.equal(requests.length, 2); assert.equal(result[0].id, target.id); assert.equal(result[0].lat, target.lat);
  assert.equal(result[0].parkingStatus, 'available'); assert.equal(result[0].parking.availableSpaces, 74);
  assert.equal(result[0].parking.observedAt, null); assert.equal(result[0].parking.timeBasis, 'retrieved');
});

test('unsupported regions preserve input objects without making provider calls', async () => {
  const target = { id: 'seoul', lat: 37.57, lon: 126.98, parkingStatus: 'available', parking: { source: 'Seoul' } };
  const result = await enrichEasternParkingPlaces([target], { fetchImpl: () => { throw new Error('unexpected'); } });
  assert.equal(result[0], target);
});

test('snapshots expire after 90 seconds and do not renew their retrieval time on cache hits', async () => {
  let now = timestamp, calls = 0;
  const fetchImpl = async () => { calls++; return Response.json(busanData()); };
  await enrichEasternParkingPlaces([busanPlace()], { fetchImpl, now: () => now });
  now += 20000;
  const result = await enrichEasternParkingPlaces([busanPlace()], { fetchImpl, now: () => now });
  assert.equal(calls, 1); assert.equal(result[0].parking.retrievedAt, new Date(timestamp).toISOString());
  assert.equal(result[0].parking.fetchedAt, new Date(now).toISOString());
  const inventory = parseBusanParkingInventory(busanData());
  assert.equal(matchBusanParking(busanPlace(), inventory, { now: timestamp + EASTERN_SNAPSHOT_FRESH_MS, retrievedAt: timestamp }).status, 'available');
  const stale = matchBusanParking(busanPlace(), inventory, { now: timestamp + EASTERN_SNAPSHOT_FRESH_MS + 1, retrievedAt: timestamp });
  assert.equal(stale.status, 'unknown'); assert.equal(stale.reason, 'stale'); assert.equal(stale.availableSpaces, null);
});

test('provider failure after cache expiry never reuses a previous vacancy', async () => {
  let now = timestamp, broken = false;
  const fetchImpl = async () => { if (broken) throw new Error('provider detail'); return Response.json(busanData()); };
  assert.equal((await enrichEasternParkingPlaces([busanPlace()], { fetchImpl, now: () => now }))[0].parkingStatus, 'available');
  now += 31000; broken = true;
  const result = await enrichEasternParkingPlaces([busanPlace()], { fetchImpl, now: () => now });
  assert.equal(result[0].parkingStatus, 'unknown'); assert.equal(result[0].parking.reason, 'unavailable');
});

test('Busan requires valid counters, coordinates and an unambiguous matching name', () => {
  for (const row of [busanRow({ cellCount: 0 }), busanRow({ parkCount: 127 }), busanRow({ parkCount: -1 }), busanRow({ cellCount: null }), busanRow({ parkCount: '' }), busanRow({ cellCount: true })]) {
    const value = matchBusanParking(busanPlace(), parseBusanParkingInventory(busanData([row])), { now: timestamp, retrievedAt: timestamp });
    assert.equal(value.status, 'unknown'); assert.equal(value.availableSpaces, null);
  }
  const rows = parseBusanParkingInventory(busanData());
  assert.equal(matchBusanParking(busanPlace({ lat: 35.2 }), rows, { now: timestamp, retrievedAt: timestamp }).reason, 'unmatched');
  assert.equal(matchBusanParking(busanPlace({ name: '다른 주차장' }), rows, { now: timestamp, retrievedAt: timestamp }).reason, 'unmatched');
  assert.equal(matchBusanParking(busanPlace(), [...rows, ...rows], { now: timestamp, retrievedAt: timestamp }).reason, 'unmatched');
  assert.equal(matchBusanParking(busanPlace(), parseBusanParkingInventory(busanData([busanRow({ parkCount: 0 })])), { now: timestamp, retrievedAt: timestamp }).status, 'full');
  assert.equal(matchBusanParking(busanPlace(), parseBusanParkingInventory(busanData([busanRow({ parkCount: 126 })])), { now: timestamp, retrievedAt: timestamp }).availableSpaces, 126);
});

test('Gangneung rejects inconsistent primary/secondary counters and detached detail IDs', () => {
  const mapRow = parseGangneungParkingMap({ rows: [gangRow()] })[0];
  for (const change of [{ tot: 85 }, { availSpace: 75 }, { totalSpace: '0' }, { occupancy: '85' }, { occupancy: null }]) {
    const value = matchGangneungParkingDetail(gangPlace(), mapRow, gangDetail(change), { now: timestamp, retrievedAt: timestamp });
    assert.equal(value.status, 'unknown'); assert.equal(value.reason, 'unavailable');
  }
  assert.equal(matchGangneungParkingDetail(gangPlace(), mapRow, gangDetail({ plotId: 'PLOT000327' }), { now: timestamp, retrievedAt: timestamp }).reason, 'unmatched');
  assert.equal(matchGangneungParkingDetail(gangPlace(), mapRow, gangDetail({ facLocation: '강문해변 제1공영주차장' }), { now: timestamp, retrievedAt: timestamp }).reason, 'unmatched');
  assert.equal(matchGangneungParkingDetail(gangPlace({ lat: 37.79 }), mapRow, gangDetail(), { now: timestamp, retrievedAt: timestamp }).reason, 'unmatched');
  assert.equal(matchGangneungParkingDetail(gangPlace(), mapRow, gangDetail({ collectYn: 'N' }), { now: timestamp, retrievedAt: timestamp }).reason, 'unsupported');
});

test('freight or other restricted facilities cannot become passenger-car vacancy recommendations', () => {
  const rows = parseBusanParkingInventory(busanData([busanRow({ parkingName: '노포 화물차고지' })]));
  const result = matchBusanParking(busanPlace({ name: '노포 화물차고지' }), rows, { now: timestamp, retrievedAt: timestamp });
  assert.equal(result.status, 'unknown'); assert.equal(result.reason, 'unsupported');
});

test('malformed data and hidden redirects fail closed rather than inventing counts', async () => {
  for (const response of [Response.json({ result: false, data: [] }), Response.json({ result: true, data: {} }), new Response('', { status: 302 }), new Response('x', { headers: { 'content-length': '3000001' } })]) {
    const result = await enrichEasternParkingPlaces([busanPlace()], { fetchImpl: async () => response, now: clock });
    assert.equal(result[0].parking.reason, 'unavailable');
  }
  assert.equal(parseBusanParkingInventory(busanData([busanRow({ lat: '' }), busanRow({ parkingName: '삼락\u0000' }), busanRow({ parkingId: '2' })])).length, 0);
  assert.equal(parseGangneungParkingMap({ rows: [gangRow({ code: '../../bad' }), gangRow({ wgs84X: null })] }).length, 0);
});

test('Gangneung duplicated names stay unmatched and do not request arbitrary details', async () => {
  let calls = 0;
  const fetchImpl = async () => { calls++; return Response.json({ rows: [gangRow(), gangRow({ code: 'PLOT000329' })] }); };
  const result = await enrichEasternParkingPlaces([gangPlace()], { fetchImpl, now: clock });
  assert.equal(calls, 1); assert.equal(result[0].parking.reason, 'unmatched');
});

test('concurrent Gangneung lookups share map/detail fetches and cached retrieval timestamps', async () => {
  let now = timestamp, calls = 0;
  const fetchImpl = async url => { calls++; return Response.json(url.pathname === '/main/selectParkList' ? { rows: [gangRow()] } : gangDetail()); };
  const both = await Promise.all([enrichEasternParkingPlaces([gangPlace()], { fetchImpl, now: () => now }), enrichEasternParkingPlaces([gangPlace()], { fetchImpl, now: () => now })]);
  assert.equal(calls, 2); assert.equal(both[0][0].parkingStatus, 'available'); assert.equal(both[1][0].parkingStatus, 'available');
  now += 20000;
  const result = await enrichEasternParkingPlaces([gangPlace()], { fetchImpl, now: () => now });
  assert.equal(calls, 2); assert.equal(result[0].parking.retrievedAt, new Date(timestamp).toISOString());
  assert.equal(result[0].parking.fetchedAt, new Date(now).toISOString());
});

test('Gangneung detail work is bounded to six matching facilities and concurrency three', async () => {
  const mapRows = Array.from({ length: 9 }, (_, i) => gangRow({ code: 'PLOT' + String(i + 1).padStart(6, '0'), name: '테스트' + i + '공영주차장' }));
  const places = mapRows.map(row => gangPlace({ name: row.name }));
  let detailCount = 0, inflight = 0, maximum = 0;
  const fetchImpl = async url => {
    if (url.pathname === '/main/selectParkList') return Response.json({ rows: mapRows });
    detailCount++; inflight++; maximum = Math.max(maximum, inflight); await new Promise(resolve => setTimeout(resolve, 2)); inflight--;
    const row = mapRows.find(row => row.code === url.searchParams.get('plotId'));
    return Response.json(gangDetail({ plotId: row.code, facLocation: row.name }));
  };
  const result = await enrichEasternParkingPlaces(places, { fetchImpl, now: clock });
  assert.equal(detailCount, 6); assert.equal(maximum, 3); assert.equal(result.filter(row => row.parkingStatus === 'available').length, 6);
  assert.equal(result.filter(row => row.parking.reason === 'unavailable').length, 3);
});

test('Gangneung rechecks map communication status after 30 seconds and disables disconnected lots', async () => {
  let now = timestamp, calls = 0, live = true;
  const fetchImpl = async url => {
    calls++;
    return Response.json(url.pathname === '/main/selectParkList' ? { rows: [gangRow({ commStatImgCd: live ? 'l' : 'd' })] } : gangDetail());
  };
  assert.equal((await enrichEasternParkingPlaces([gangPlace()], { fetchImpl, now: () => now }))[0].parkingStatus, 'available');
  now += 31000; live = false;
  const result = await enrichEasternParkingPlaces([gangPlace()], { fetchImpl, now: () => now });
  assert.equal(calls, 3); assert.equal(result[0].parkingStatus, 'unknown'); assert.equal(result[0].parking.reason, 'unsupported');
});
