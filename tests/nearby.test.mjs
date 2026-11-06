import test from 'node:test';
import assert from 'node:assert/strict';
import { searchNearby } from '../server/nearby.mjs';

const env = { KAKAO_REST_API_KEY: 'test-private-token' };
const input = { kind: 'restaurant', lat: 35.1585, lon: 129.1598 };
const place = (overrides = {}) => ({ id: '123', place_name: '해변 식당', address_name: '부산 해운대구 우동', road_address_name: '부산 해운대구 해변로', x: '129.16', y: '35.158', distance: '150', category_group_code: 'FD6', phone: '051-123-4567', ...overrides });

test('nearby category search keeps coordinates/category/distance and never invents parking availability', async () => {
  let request;
  const result = await searchNearby(input, { env, fetchImpl: async (url, options) => {
    request = new URL(url); assert.equal(options.headers.Authorization, 'KakaoAK test-private-token');
    assert.equal(options.redirect, 'error'); assert.ok(options.signal instanceof AbortSignal);
    return Response.json({ documents: [place({ distance: '250' }), place({ id: '124', distance: '50', place_url: 'https://malicious.invalid/' })] });
  } });
  assert.equal(request.origin, 'https://dapi.kakao.com'); assert.equal(request.pathname, '/v2/local/search/category.json');
  assert.equal(request.searchParams.get('category_group_code'), 'FD6'); assert.equal(request.searchParams.get('x'), '129.1598');
  assert.equal(request.searchParams.get('y'), '35.1585'); assert.equal(request.searchParams.get('radius'), '3000');
  assert.equal(request.searchParams.get('sort'), 'distance'); assert.equal(request.searchParams.get('size'), '10');
  assert.deepEqual(result.places.map(p => p.id), ['124', '123']);
  assert.equal(result.places[0].placeUrl, 'https://place.map.kakao.com/124');
  assert.equal(result.places[0].parkingStatus, 'unknown'); assert.equal(result.source, 'Kakao Local');
  assert.ok(!JSON.stringify(result).includes('test-private-token'));
});

test('parking lookup uses the exact selected restaurant coordinates', async () => {
  const restaurant = { kind: 'parking', lat: 35.1621, lon: 129.1659 };
  const result = await searchNearby(restaurant, { env, fetchImpl: async url => {
    assert.equal(url.searchParams.get('category_group_code'), 'PK6');
    assert.equal(url.searchParams.get('x'), String(restaurant.lon)); assert.equal(url.searchParams.get('y'), String(restaurant.lat));
    return Response.json({ documents: [place({ category_group_code: 'PK6' })] });
  } });
  assert.equal(result.kind, 'parking'); assert.equal(result.places.length, 1);
});

test('bad coordinates/categories/extra input are rejected before fetch', async () => {
  const invalid = [null, [], { ...input, kind: '__proto__' }, { ...input, lat: '' }, { ...input, lon: Infinity }, { ...input, lat: 0 }, { ...input, lon: '129' }, { ...input, radius: 20000 }];
  for (const value of invalid) await assert.rejects(searchNearby(value, { env, fetchImpl: () => { throw new Error('unexpected fetch'); } }), error => error.status === 400);
});

test('untrusted records are filtered and output capped', async () => {
  const invalid = [place({ id: '../bad' }), place({ id: 123 }), place({ place_name: '\u0000secret' }), place({ x: '' }), place({ x: '139.2' }), place({ distance: '' }), place({ distance: '-1' }), place({ distance: '3001' }), place({ category_group_code: 'PK6' })];
  const docs = [...invalid, ...Array.from({ length: 14 }, (_, i) => place({ id: String(100 + i) })), place({ id: '100' })];
  const result = await searchNearby(input, { env, fetchImpl: async () => Response.json({ documents: docs }) });
  assert.equal(result.places.length, 10); assert.equal(new Set(result.places.map(p => p.id)).size, 10);
});

test('missing key and provider/malformed responses are safe failures', async () => {
  await assert.rejects(searchNearby(input, { env: {} }), error => error.status === 503);
  for (const fetchImpl of [async () => { throw new Error('test-private-token'); }, async () => new Response('test-private-token', { status: 403 }), async () => Response.json({ error: 'test-private-token' })]) {
    await assert.rejects(searchNearby(input, { env, fetchImpl }), error => error.status === 502 && !error.message.includes('test-private-token'));
  }
});
