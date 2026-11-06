import test from 'node:test';
import assert from 'node:assert/strict';
import { Readable } from 'node:stream';
import { createServer } from '../server/index.mjs';
import { parsePreparedCatalog } from '../server/android-catalog.mjs';
import { ApiError } from '../server/providers.mjs';

function request(server, url, { method = 'GET', headers = {} } = {}) {
  return new Promise((resolve) => {
    const req = Readable.from([]);
    Object.assign(req, { url, method, headers: { host: '127.0.0.1:4173', ...headers }, socket: { remoteAddress: 'catalog-test' } });
    const res = {
      headers: {}, setHeader(key, value) { this.headers[key] = value; },
      writeHead(status, values) { this.status = status; Object.assign(this.headers, values); },
      end(data) { resolve({ status: this.status, headers: this.headers, body: String(data ?? '') }); },
    };
    server.emit('request', req, res);
  });
}

test('prepared nationwide catalog is available without any provider key or external call', async () => {
  const result = await request(createServer({ env: {}, fetchImpl: () => { throw new Error('Catalog must be local'); } }), '/api/catalog');
  assert.equal(result.status, 200);
  const catalog = JSON.parse(result.body);
  assert.ok(catalog.courses.length >= 500);
  assert.equal(catalog.regions.length, 17);
  assert.deepEqual(catalog.stats, { courseCount: catalog.courses.length, placeCount: catalog.places.length, regionCount: 17 });
  assert.equal(new Set(catalog.courses.map((course) => course.id)).size, catalog.courses.length);
  const places = new Set(catalog.places.map((place) => place.id));
  const regions = new Set(catalog.regions.map((region) => region.id));
  for (const course of catalog.courses) {
    assert.ok(regions.has(course.regionId));
    assert.ok(Number.isInteger(course.minutes) && course.minutes > 0 && course.minutes <= 720);
    assert.ok(course.spotIds.length >= 2 && course.spotIds.every((id) => places.has(id)));
  }
  assert.equal(new Set(catalog.courses.map((course) => course.regionId)).size, 17);
});

test('public prepared catalog exposes no configuration or private environment values', async () => {
  const secrets = { OPENAI_API_KEY: 'catalog-openai-secret', KAKAO_REST_API_KEY: 'catalog-kakao-secret', TOUR_API_KEY: 'catalog-tour-secret', PRIVATE_SETTING: 'catalog-private-secret' };
  const result = await request(createServer({ env: secrets }), '/api/catalog');
  assert.equal(result.status, 200);
  for (const secret of Object.values(secrets)) assert.ok(!result.body.includes(secret));
  const catalog = JSON.parse(result.body);
  assert.deepEqual(Object.keys(catalog).sort(), ['courses', 'generatedAt', 'places', 'regions', 'source', 'stats', 'version']);
  assert.deepEqual(Object.keys(catalog.source).sort(), ['attribution', 'metricMethod', 'provider']);
  assert.ok(!result.body.includes('serviceKey'));
  assert.ok(!result.body.includes('OPENAI_API_KEY'));
  assert.equal((await request(createServer({ env: {} }), '/api/catalog', { method: 'POST' })).status, 404);
});

const fixture = () => ({
  version: 'test-v1', generatedAt: '2026-11-04T00:00:00Z',
  source: { provider: '한국관광공사 TourAPI', attribution: '한국관광공사', metricMethod: '직선거리 기반 추정', endpoint: 'https://example.invalid/?serviceKey=hidden-secret' },
  regions: [{ id: '1', name: '서울' }],
  places: [
    { id: 'p1', title: '출발지', address: '서울', lat: 37.56, lon: 126.97, regionId: '1', districtId: '1', contentTypeId: '12', privateSecret: 'hidden-secret' },
    { id: 'p2', title: '도착지', address: '서울', lat: 37.57, lon: 127.01, regionId: '1', districtId: '1', contentTypeId: '12' },
  ],
  courses: [{ id: 'route-1', title: '준비된 코스', subtitle: '관광지 사이의 풍경', regionId: '1', regionName: '서울', theme: 'night', minutes: 60, distanceKm: 4.2, spotIds: ['p1', 'p2'], centerLat: 37.565, centerLon: 126.99 }],
  stats: { courseCount: 600, placeCount: 1000, regionCount: 17 },
  secret: 'hidden-secret',
});

test('public catalog derives truthful counts and excludes unrecognized source fields', () => {
  const catalog = parsePreparedCatalog(fixture());
  assert.deepEqual(catalog.stats, { courseCount: 1, placeCount: 2, regionCount: 1 });
  assert.ok(!JSON.stringify(catalog).includes('hidden-secret'));
  assert.ok(Object.isFrozen(catalog.courses[0]));
  assert.throws(() => { catalog.courses[0].minutes = 1; }, TypeError);
});

test('invalid prepared records fail closed instead of becoming trusted AI candidates', () => {
  const mutations = [
    (data) => { data.courses.push({ ...data.courses[0] }); },
    (data) => { data.places.push({ ...data.places[0] }); },
    (data) => { data.courses[0].minutes = 0; },
    (data) => { data.courses[0].minutes = 721; },
    (data) => { data.courses[0].minutes = '60'; },
    (data) => { data.courses[0].theme = '__proto__'; },
    (data) => { data.courses[0].theme = ['lake']; },
    (data) => { data.courses[0].theme = null; },
    (data) => { data.courses[0].theme = {}; },
    (data) => { data.courses[0].spotIds = ['p1', 'stale']; },
    (data) => { data.courses[0].spotIds = ['p1', 'p1']; },
    (data) => { data.courses[0].regionId = 'stale'; },
    (data) => { data.courses[0].regionName = '다른 지역'; },
    (data) => { data.places[0].lat = NaN; },
    (data) => { data.places[0].lon = 181; },
    (data) => { data.generatedAt = 'invalid date'; },
  ];
  for (const mutate of mutations) {
    const data = fixture(); mutate(data);
    assert.throws(() => parsePreparedCatalog(data), (error) => error instanceof ApiError && error.status === 503 && !error.message.includes('hidden-secret'));
  }
});
