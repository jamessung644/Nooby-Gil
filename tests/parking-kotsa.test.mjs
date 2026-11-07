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