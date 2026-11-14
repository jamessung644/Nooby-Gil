import test from 'node:test';
import assert from 'node:assert/strict';
import { enrichKotsaParkingPlaces, matchKotsaParking, KOTSA_SOURCE } from '../server/parking-kotsa.mjs';
import { enrichParkingPlaces } from '../server/parking-live.mjs';

const facilities = {
  a: { name: '동백사거리 공영주차장', lat: 35.1700, lon: 129.1450, total: 80 },
  b: { name: '동백공영주차장', lat: 35.1710, lon: 129.1460, total: 60 },
  far: { name: '동백사거리 공영주차장', lat: 35.2500, lon: 129.2500, total: 40 },
};
const row = (id, free, total) => ({ prk_center_id: id, pkfc_Available_ParkingLots_total: String(free), pkfc_ParkingLots_total: String(total) });
const body = rows => ({ resultCode: '0', totalCount: String(rows.length), PrkRealtimeInfo: rows });
const place = (id, name, lat, lon) => ({ id, name, address: '', lat, lon, parkingStatus: 'unknown' });
let seed = 0;
const env = () => ({ DATA_GO_KR_KEY: `key-${++seed}` });

test('matches by name and 150 m, one facility per place, with a retrieved-time snapshot', () => {
  const places = [place('1', '동백사거리 공영주차장', 35.1701, 129.1451), place('2', '동백사거리 공영주차장', 35.1702, 129.1452)];
  const out = matchKotsaParking(places, [row('a', 31, 80), row('far', 5, 40)], facilities, { now: 5000, retrievedAt: 4000 });
  assert.equal(out.size, 1);
  const parking = out.get(0);
  assert.deepEqual([parking.status, parking.availableSpaces, parking.totalSpaces, parking.timeBasis, parking.reason, parking.providerId], ['available', 31, 80, 'retrieved', 'live', 'kotsa:a']);
  assert.equal(parking.observedAt, null);
  assert.equal(parking.source, KOTSA_SOURCE);
});

test('zero free spaces is full; impossible counts and a different name are rejected', () => {
  const near = place('1', '동백사거리 공영주차장', 35.1700, 129.1450);
  assert.equal(matchKotsaParking([near], [row('a', 0, 80)], facilities, { now: 1, retrievedAt: 1 }).get(0).status, 'full');
  assert.equal(matchKotsaParking([near], [row('a', 99, 80)], facilities, { now: 1, retrievedAt: 1 }).size, 0);
  assert.equal(matchKotsaParking([place('1', '완전히 다른 카페', 35.1700, 129.1450)], [row('a', 5, 80)], facilities, { now: 1, retrievedAt: 1 }).size, 0);
});

test('without a key or on provider failure the places come back untouched and nothing is fetched or guessed', async () => {
  const places = [place('1', '동백사거리 공영주차장', 35.1700, 129.1450)];
  let calls = 0;
  const fetchImpl = async () => { calls++; throw new Error('down'); };
  assert.equal(await enrichKotsaParkingPlaces(places, { env: {}, fetchImpl, facilities }), places);
  assert.equal(calls, 0);
  const failed = await enrichKotsaParkingPlaces(places, { env: env(), fetchImpl, facilities });
  assert.equal(failed[0], places[0]);
});

test('enriched place carries the live parking object and caches the API for 30 s', async () => {
  const places = [place('1', '동백사거리 공영주차장', 35.1700, 129.1450)];
  let calls = 0, time = 1_000_000;
  const e = env();
  const fetchImpl = async () => { calls++; return { ok: true, json: async () => body([row('a', 31, 80)]) }; };
  const first = await enrichKotsaParkingPlaces(places, { env: e, fetchImpl, facilities, now: () => time });
  assert.equal(first[0].parkingStatus, 'available');
  await enrichKotsaParkingPlaces(places, { env: e, fetchImpl, facilities, now: () => time + 10_000 });
  assert.equal(calls, 1);
  await enrichKotsaParkingPlaces(places, { env: e, fetchImpl, facilities, now: () => time + 40_000 });
  assert.equal(calls, 2);
});

test('enrichParkingPlaces without a key never calls the KOTSA API', async () => {
  const urls = [];
  const fetchImpl = async url => { urls.push(String(url)); throw new Error('offline'); };
  const out = await enrichParkingPlaces([place('1', '동백사거리 공영주차장', 35.1700, 129.1450)], { fetchImpl, now: () => 1 });
  assert.ok(!urls.some(url => url.includes('B553881')));
  assert.equal(out[0].parkingStatus, 'unknown');
});

test('a stalled KOTSA fetch releases concurrent callers at the provider deadline and permits recovery', async t => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const places = [place('1', '동백사거리 공영주차장', 35.1700, 129.1450)];
  const e = env();
  let calls = 0, signal, finishLate, recovered = false, settled = false;
  const fetchImpl = async (_url, options) => {
    calls++; signal = options.signal;
    if (!recovered) return new Promise(resolve => { finishLate = resolve; });
    return Response.json(body([row('a', 20, 80)]));
  };
  const options = { env: e, fetchImpl, facilities, now: () => 1_000_000 };
  const pending = Promise.all([enrichKotsaParkingPlaces(places, options), enrichKotsaParkingPlaces(places, options)])
    .then(value => { settled = true; return value; });
  assert.equal(calls, 1);
  t.mock.timers.tick(7999);
  await new Promise(setImmediate);
  assert.equal(settled, false);
  t.mock.timers.tick(1);
  await new Promise(setImmediate);
  assert.equal(settled, true, 'a provider that ignores abort must still release callers');
  assert.equal(signal.aborted, true);
  for (const result of await pending) assert.equal(result, places);
  recovered = true;
  assert.equal((await enrichKotsaParkingPlaces(places, options))[0].parking.availableSpaces, 20);
  finishLate(Response.json(body([row('a', 0, 80)])));
  await new Promise(setImmediate);
  assert.equal((await enrichKotsaParkingPlaces(places, options))[0].parking.availableSpaces, 20, 'late abandoned responses must not overwrite the recovered cache');
  assert.equal(calls, 2);
});

test('the KOTSA deadline includes later pages and stalled response bodies', async t => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const places = [place('1', '동백사거리 공영주차장', 35.1700, 129.1450)];
  let finishFirst, pageTwo, settled = false;
  const first = new Promise(resolve => { finishFirst = resolve; });
  const secondStarted = new Promise(resolve => { pageTwo = resolve; });
  const signals = [];
  const fetchImpl = async (url, options) => {
    signals.push(options.signal);
    if (new URL(url).searchParams.get('pageNo') === '1') {
      await first;
      return Response.json({ ...body([row('a', 31, 80)]), totalCount: '1001' });
    }
    return { ok: true, json: () => { pageTwo(); return new Promise(() => {}); } };
  };
  const pending = enrichKotsaParkingPlaces(places, { env: env(), fetchImpl, facilities, now: () => 1_000_000 })
    .then(value => { settled = true; return value; });
  t.mock.timers.tick(6000); finishFirst();
  await secondStarted;
  t.mock.timers.tick(2000);
  await new Promise(setImmediate);
  assert.equal(settled, true, 'pagination and body reads must share one overall deadline');
  assert.equal(await pending, places, 'partial inventories are never advertised as complete');
  assert.equal(signals.length, 2);
  assert.ok(signals.every(signal => signal instanceof AbortSignal && signal.aborted));
});

test('malformed vacancy values and malformed rows cannot produce full or available parking', () => {
  const places = [place('1', '동백사거리 공영주차장', 35.1700, 129.1450)];
  for (const value of [null, undefined, '', ' ', true, false, [], [0], {}, '0x10', '1e1']) {
    for (const field of ['pkfc_Available_ParkingLots_total', 'pkfc_ParkingLots_total']) {
      const invalid = { ...row('a', 0, 80), [field]: value };
      assert.equal(matchKotsaParking(places, [invalid], facilities, { now: 1, retrievedAt: 1 }).size, 0, `${field}: ${JSON.stringify(value)}`);
    }
  }
  const result = matchKotsaParking(places, [null, undefined, {}, row('a', 3, 80)], facilities, { now: 1, retrievedAt: 1 });
  assert.equal(result.get(0).availableSpaces, 3);
});

test('KOTSA snapshots are stamped on retrieval and assessed after the response arrives', async () => {
  const places = [place('1', '동백사거리 공영주차장', 35.1700, 129.1450)];
  let current = 1_000_000;
  const result = await enrichKotsaParkingPlaces(places, { env: env(), facilities, now: () => current,
    fetchImpl: async () => { current += 2000; return Response.json(body([row('a', 31, 80)])); } });
  assert.equal(result[0].parking.retrievedAt, new Date(1_002_000).toISOString());
  assert.equal(result[0].parking.fetchedAt, new Date(1_002_000).toISOString());
});

test('KOTSA cache cannot reuse future snapshots after a clock rollback', async () => {
  const places = [place('1', '동백사거리 공영주차장', 35.1700, 129.1450)];
  const e = env();
  let current = 1_000_000, free = 31;
  const fetchImpl = async () => Response.json(body([row('a', free, 80)]));
  const options = { env: e, facilities, fetchImpl, now: () => current };
  await enrichKotsaParkingPlaces(places, options);
  current -= 1000; free = 0;
  const result = await enrichKotsaParkingPlaces(places, options);
  assert.equal(result[0].parkingStatus, 'full');
  assert.equal(result[0].parking.retrievedAt, new Date(current).toISOString());
});

test('KOTSA caches are isolated by transport as well as API key', async () => {
  const places = [place('1', '동백사거리 공영주차장', 35.1700, 129.1450)];
  const options = { env: env(), facilities, now: () => 1_000_000 };
  const first = await enrichKotsaParkingPlaces(places, { ...options, fetchImpl: async () => Response.json(body([row('a', 31, 80)])) });
  const second = await enrichKotsaParkingPlaces(places, { ...options, fetchImpl: async () => Response.json(body([row('a', 0, 80)])) });
  assert.equal(first[0].parkingStatus, 'available');
  assert.equal(second[0].parkingStatus, 'full');
});
