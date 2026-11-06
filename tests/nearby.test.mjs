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

test('parking prefers public-name evidence over closer shop lots, then distance, before taking ten', async () => {
  const documents = [
    place({ id: '801', place_name: '카페오션스강릉수제버거 경포대점 주차장', distance: '107', category_group_code: 'PK6' }),
    place({ id: '802', place_name: '강문해변 제2공영주차장', distance: '180', category_group_code: 'PK6' }),
    place({ id: '803', place_name: '건물 공용주차장', distance: '15', category_group_code: 'PK6' }),
    ...Array.from({ length: 7 }, (_, i) => place({ id: String(810 + i), place_name: `상가 ${i} 주차장`, distance: String(210 + i), category_group_code: 'PK6' })),
    place({ id: '820', place_name: '시영주차장', distance: '150', category_group_code: 'PK6' }),
    place({ id: '821', place_name: '공공주차장', distance: '300', category_group_code: 'PK6' }),
    place({ id: '822', place_name: '구영주차장', distance: '400', category_group_code: 'PK6' }),
    place({ id: '823', place_name: '군영주차장', distance: '500', category_group_code: 'PK6' }),
    place({ id: '824', place_name: '먼 공영주차장', distance: '600', category_group_code: 'PK6' }),
  ];
  const result = await searchNearby({ ...input, kind: 'parking' }, { env, fetchImpl: async url => {
    assert.equal(url.searchParams.get('size'), '15'); assert.equal(url.searchParams.get('sort'), 'distance');
    assert.equal(url.searchParams.get('category_group_code'), 'PK6'); assert.equal(url.searchParams.get('radius'), '3000');
    return Response.json({ documents });
  } });
  assert.equal(result.places.length, 10); assert.equal(result.ranking, 'availability_then_public_label_then_distance');
  assert.deepEqual(result.places.slice(0, 6).map(p => p.id), ['820', '802', '821', '822', '823', '824']);
  assert.deepEqual(result.places.slice(6, 8).map(p => p.id), ['803', '801']);
  assert.ok(result.places.every(p => p.parkingStatus === 'unknown' && !('free' in p) && !('open' in p) && !('remainingSpaces' in p)));
});

test('chosen food uses bounded restaurant keyword search and preserves provider relevance order', async () => {
  const result = await searchNearby({ ...input, query: '  순두부  ' }, { env, fetchImpl: async url => {
    assert.equal(url.pathname, '/v2/local/search/keyword.json');
    assert.equal(url.searchParams.get('query'), '순두부');
    assert.equal(url.searchParams.has('category_group_code'), false);
    assert.equal(url.searchParams.get('sort'), 'accuracy');
    assert.equal(url.searchParams.get('radius'), '3000');
    return Response.json({ documents: [place({ id: '11', distance: '800', category_name: '음식점 > 한식 > 두부전문점' }), place({ id: '12', distance: '80' })] });
  } });
  assert.deepEqual(result.places.map(p => p.id), ['11', '12']);
  assert.equal(result.ranking, 'relevance'); assert.equal(result.query, '순두부');
  assert.equal(result.places[0].category, '음식점 > 한식 > 두부전문점');
  assert.equal(result.places[0].phone, '051-123-4567');
  assert.ok(result.places.every(p => p.parkingStatus === 'unknown' && !('rating' in p) && !('remainingSpaces' in p)));
});

test('regional coffee and bakery keywords include real cafés but reject unknown and non-food categories', async () => {
  for (const query of ['커피', '황남빵']) {
    const result = await searchNearby({ ...input, query }, { env, fetchImpl: async url => {
      assert.equal(url.pathname, '/v2/local/search/keyword.json');
      assert.equal(url.searchParams.has('category_group_code'), false);
      assert.equal(url.searchParams.get('query'), query); assert.equal(url.searchParams.get('sort'), 'accuracy');
      assert.equal(url.searchParams.get('radius'), '3000'); assert.equal(url.searchParams.get('size'), '15');
      return Response.json({ documents: [
        place({ id: '21', distance: '900', category_group_code: 'CE7', category_name: '음식점 > 카페' }),
        place({ id: '22', distance: '30', category_group_code: 'AT4' }),
        place({ id: '23', category_group_code: 'MT1' }), place({ id: '24', category_group_code: '' }),
        place({ id: '25', category_group_code: undefined }), place({ id: '26', category_group_code: 'PK6' }),
        place({ id: '27', distance: '100', category_group_code: 'FD6' }),
        place({ id: '28', distance: '3001', category_group_code: 'CE7' }),
      ] });
    } });
    assert.deepEqual(result.places.map(p => p.id), ['21', '27']);
    assert.equal(result.places[0].category, '음식점 > 카페'); assert.equal(result.ranking, 'relevance');
    assert.ok(result.places.every(p => p.parkingStatus === 'unknown'));
  }
  const defaults = await searchNearby(input, { env, fetchImpl: async url => {
    assert.equal(url.searchParams.get('category_group_code'), 'FD6');
    return Response.json({ documents: [place({ id: '31', category_group_code: 'CE7' }), place({ id: '32', category_group_code: 'FD6' })] });
  } });
  assert.deepEqual(defaults.places.map(p => p.id), ['32']);
});

test('food keyword errors do not fall back to an unrelated restaurant or category', async () => {
  let calls = 0;
  await assert.rejects(searchNearby({ ...input, query: '장칼국수' }, { env, fetchImpl: async () => { calls++; return new Response('', { status: 500 }); } }), error => error.status === 502);
  assert.equal(calls, 1);
  const empty = await searchNearby({ ...input, query: '장칼국수' }, { env, fetchImpl: async () => Response.json({ documents: [] }) });
  assert.deepEqual(empty.places, []); assert.equal(empty.query, '장칼국수');
});

test('food preference is rejected for parking and malformed terms never reach the provider', async () => {
  for (const query of ['', '   ', null, 1, 'a'.repeat(41), '회\u0000']) {
    await assert.rejects(searchNearby({ ...input, query }, { env, fetchImpl: () => { throw new Error('unexpected'); } }), error => error.status === 400);
  }
  await assert.rejects(searchNearby({ ...input, kind: 'parking', query: '순두부' }, { env }), error => error.status === 400);
});

test('untrusted phone and category are omitted without creating opening or availability claims', async () => {
  const result = await searchNearby(input, { env, fetchImpl: async () => Response.json({ documents: [place({ phone: 'javascript:bad', category_name: '한식\u0000', opening_hours: '24h', rating: 5, remainingSpaces: 7 })] }) });
  assert.equal(result.places[0].phone, ''); assert.equal(result.places[0].category, '');
  assert.equal(result.places[0].parkingStatus, 'unknown');
  assert.ok(!('opening_hours' in result.places[0]) && !('rating' in result.places[0]));
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
