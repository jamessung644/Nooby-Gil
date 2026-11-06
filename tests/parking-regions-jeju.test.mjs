import test from 'node:test';
import assert from 'node:assert/strict';
import { enrichJejuParkingPlaces, matchSeogwipoParkingInventory, JEJU_PARKING_SNAPSHOT_FRESH_MS } from '../server/parking-regions-jeju.mjs';

const time = Date.parse('2026-11-06T15:00:00Z');
const place = (extra = {}) => ({ id: 'test', name: '중앙공영주차빌딩', lat: 33.249849, lon: 126.562280, address: '제주특별자치도 서귀포시 서귀동', ...extra });
const row = (extra = {}) => ({ parkingId: 26, sectnId: 26, parkingName: '중앙 공영주차빌딩', lat: '33.249849', lng: '126.562280', cellCount: 304, parkCount: 301, busCellCount: 0, ...extra });
const response = rows => Response.json({ result: true, httpStatusCode: 200, dateTime: '2026-11-07 00:00:00', data: rows });
const match = (target = place(), rows = [row()], options = {}) => matchSeogwipoParkingInventory(target, rows, { now: time, retrievedAt: time, ...options });

test('Seogwipo parkCount is remaining spaces and no observation time is invented', () => {
  const available = match();
  assert.equal(available.availableSpaces, 301); assert.equal(available.totalSpaces, 304); assert.equal(available.status, 'available');
  assert.equal(available.observedAt, null); assert.equal(available.timeBasis, 'retrieved');
  assert.equal(available.retrievedAt, new Date(time).toISOString()); assert.equal(available.providerId, 'seogwipo:26:26');
  const full = match(place(), [row({ parkCount: 0 })]);
  assert.equal(full.status, 'full'); assert.equal(full.availableSpaces, 0);
});

test('snapshots expire after ninety seconds and future or absent retrieval times stay unknown', () => {
  assert.equal(match(place(), [row()], { now: time + JEJU_PARKING_SNAPSHOT_FRESH_MS }).status, 'available');
  assert.equal(match(place(), [row()], { now: time + JEJU_PARKING_SNAPSHOT_FRESH_MS + 1 }).reason, 'stale');
  for (const retrievedAt of [null, time + 1]) {
    const result = match(place(), [row()], { retrievedAt });
    assert.equal(result.status, 'unknown'); assert.equal(result.reason, 'unavailable'); assert.equal(result.availableSpaces, null);
  }
});

test('zero capacity, inconsistent or missing counts never imply empty spaces', () => {
  for (const change of [{ cellCount: 0 }, { cellCount: '0' }, { cellCount: true }, { cellCount: '' }, { parkCount: null }, { parkCount: '' }, { parkCount: true }, { parkCount: -1 }, { parkCount: 305 }, { parkCount: 1.5 }, { parkCount: 'NaN' }]) {
    const result = match(place(), [row(change)]);
    assert.equal(result.status, 'unknown'); assert.equal(result.availableSpaces, null); assert.equal(result.totalSpaces, null);
  }
  assert.equal(match(place(), [row({ parkCount: '15', cellCount: '304' })]).availableSpaces, 15);
});

test('names, building type, numeric sections, positions and ambiguity are checked together', () => {
  for (const [target, rows] of [
    [place({ name: '중앙로터리공영주차빌딩' }), [row()]],
    [place({ name: '중앙공영주차장' }), [row()]],
    [place({ lat: 33.254 }), [row()]],
    [place(), [row({ lat: '' })]],
    [place(), [row({ parkingId: null })]],
    [place(), [row({ sectnId: 'https://other.invalid' })]],
    [place(), [row(), row({ sectnId: 27 })]],
    [place(), [row(), row()]],
    [place({ name: '공영주차장' }), [row({ parkingName: '공영주차장' })]],
    [place({ name: '서홍 노외2 공영주차장' }), [row({ parkingName: '서홍 노외1 공영주차장' })]],
  ]) assert.equal(match(target, rows).reason, 'unmatched');
});

test('dedicated bus parking is never offered to a passenger car', () => {
  assert.equal(match(place(), [row({ busCellCount: 304 })]).reason, 'unsupported');
  const name = '중앙 관광버스 전용 주차장';
  assert.equal(match(place({ name }), [row({ parkingName: name })]).reason, 'unsupported');
});

test('outside the coverage returns original references without a provider request', async () => {
  const inputs = [place({ address: '제주시 일도동', lat: 33.51 }), place({ lat: 35.16, lon: 129.06 }), place({ address: '', lat: 33.48, lon: 126.45 })];
  const results = await enrichJejuParkingPlaces(inputs, { fetchImpl: () => { throw new Error('must not fetch'); }, now: () => time });
  assert.equal(results, inputs); results.forEach((result, index) => assert.equal(result, inputs[index]));
});

test('one bounded official public request is shared and cached retrieval time never advances', async () => {
  let current = time, requests = 0;
  const fetchImpl = async (url, options) => {
    requests++;
    assert.equal(url.href, 'https://parking.seogwipo.go.kr/api/parking/realtime/list');
    assert.equal(options.redirect, 'error'); assert.ok(options.signal instanceof AbortSignal);
    assert.equal(options.headers.Authorization, undefined);
    current += 500;
    return response([row()]);
  };
  const outside = place({ lat: 35.16, lon: 129.06 });
  const [first, concurrent] = await Promise.all([enrichJejuParkingPlaces([place(), outside], { fetchImpl, now: () => current }), enrichJejuParkingPlaces([place()], { fetchImpl, now: () => current })]);
  assert.equal(requests, 1); assert.equal(first[1], outside); assert.equal(concurrent[0].parkingStatus, 'available');
  assert.equal(first[0].parking.retrievedAt, new Date(time + 500).toISOString());
  current += 20000;
  const cached = (await enrichJejuParkingPlaces([place()], { fetchImpl, now: () => current }))[0].parking;
  assert.equal(requests, 1); assert.equal(cached.retrievedAt, first[0].parking.retrievedAt);
  assert.equal(cached.fetchedAt, new Date(current).toISOString()); assert.equal(cached.observedAt, null);
});

test('refresh failure clears availability, and recovery obtains a fresh snapshot', async () => {
  let current = time, broken = false;
  const fetchImpl = async () => { if (broken) throw new Error('private upstream failure'); return response([row()]); };
  assert.equal((await enrichJejuParkingPlaces([place()], { fetchImpl, now: () => current }))[0].parkingStatus, 'available');
  current += 30001; broken = true;
  const failed = (await enrichJejuParkingPlaces([place()], { fetchImpl, now: () => current }))[0].parking;
  assert.equal(failed.status, 'unknown'); assert.equal(failed.retrievedAt, null); assert.equal(failed.availableSpaces, null);
  assert.ok(!JSON.stringify(failed).includes('private upstream'));
  broken = false;
  assert.equal((await enrichJejuParkingPlaces([place()], { fetchImpl, now: () => current }))[0].parking.retrievedAt, new Date(current).toISOString());
});

test('invalid envelopes, oversized and non-JSON replies fail closed', async () => {
  for (const fetchImpl of [async () => new Response('x', { status: 500 }), async () => Response.json({ result: true, data: [row()] }),
    async () => Response.json({ result: true, httpStatusCode: 200, data: Array(501).fill(row()) }),
    async () => new Response('x', { headers: { 'content-length': '1000001' } }), async () => new Response('x'.repeat(1000001)),
    async () => new Response('<html>service unavailable</html>')]) {
    const result = (await enrichJejuParkingPlaces([place()], { fetchImpl, now: () => time }))[0].parking;
    assert.equal(result.status, 'unknown'); assert.equal(result.reason, 'unavailable'); assert.equal(result.availableSpaces, null);
  }
});
