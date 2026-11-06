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

const parkingInput = { kind: 'parking', lat: 36.97, lon: 127.94 };
const parkingDocument = (overrides = {}) => place({ category_group_code: 'PK6', place_name: '상가 주차장', address_name: '충북 충주시',
  road_address_name: '', x: '127.94', y: '36.97', ...overrides });

test('parking pages two and three reach official availability ranking before the ten-result cutoff', async () => {
  const requests = [], signals = [], documents = Array.from({ length: 15 }, (_, index) => parkingDocument({
    id: String(100 + index), place_name: `상가 ${index} 주차장`, x: '129.16', y: '35.158', distance: String(10 + index),
  }));
  const target = (id, name, distance) => parkingDocument({ id, place_name: name, x: '129.16', y: '35.158', distance });
  const result = await searchNearby({ ...input, kind: 'parking', focusId: '302' }, { env, fetchImpl: async (url, options) => {
    if (url.hostname !== 'dapi.kakao.com') {
      assert.equal(url.href, 'https://bsparking.bisco.or.kr/api/parking/realtime/list');
      assert.equal(options.headers.Authorization, undefined);
      return Response.json({ result: true, data: [
        { parkingId: 1, sectnId: 1, parkingName: '공원 서측 공영주차장', lat: '35.158', lng: '129.16', cellCount: 100, parkCount: 7 },
        { parkingId: 2, sectnId: 2, parkingName: '공원 동측 공영주차장', lat: '35.158', lng: '129.16', cellCount: 100, parkCount: 12 },
      ] });
    }
    const page = Number(url.searchParams.get('page')); requests.push(page); signals.push(options.signal);
    assert.equal(url.searchParams.get('size'), '15'); assert.equal(url.searchParams.get('radius'), '3000');
    assert.equal(options.headers.Authorization, 'KakaoAK test-private-token');
    return Response.json({ meta: { is_end: page === 3 }, documents: page === 1 ? documents
      : [target(page === 2 ? '201' : '302', page === 2 ? '공원 서측 공영주차장' : '공원 동측 공영주차장', page === 2 ? '700' : '800')] });
  } });
  assert.deepEqual(requests, [1, 2, 3]); assert.ok(signals.every(signal => signal === signals[0]));
  assert.equal(result.places.length, 10); assert.deepEqual(result.places.slice(0, 2).map(value => value.id), ['201', '302']);
  assert.deepEqual(result.places.slice(0, 2).map(value => value.parking.availableSpaces), [7, 12]);
  assert.equal(result.focus.id, '302'); assert.equal(result.focus.parkingStatus, 'available');
});

test('parking deduplicates pages while retaining normal name, location and distance validation', async () => {
  const pages = [
    [parkingDocument({ id: '100', place_name: '원래 공영주차장', distance: '300' }), parkingDocument({ id: '101' })],
    [parkingDocument({ id: '100', place_name: '중복 변경 이름', distance: '0' }), parkingDocument({ id: '102' }), parkingDocument({ id: '../bad' })],
    [parkingDocument({ id: '103', x: '' }), parkingDocument({ id: '104', distance: '3001' }), parkingDocument({ id: '105', category_group_code: 'FD6' }),
      parkingDocument({ id: '106', place_name: '깨진\u0000이름' }), parkingDocument({ id: '107', place_name: '새 공영주차장' })],
  ];
  const result = await searchNearby(parkingInput, { env, fetchImpl: async url => {
    const number = Number(url.searchParams.get('page'));
    return Response.json({ documents: pages[number - 1], meta: { is_end: number === 3 } });
  } });
  assert.deepEqual(new Set(result.places.map(value => value.id)), new Set(['100', '101', '102', '107']));
  assert.equal(result.places.find(value => value.id === '100').name, '원래 공영주차장');
  assert.equal(result.places.find(value => value.id === '100').distanceMeters, 300);
});

test('parking caps pages at three and raw candidates at fifteen per page', async () => {
  const requests = [];
  const result = await searchNearby({ ...parkingInput, focusId: '315' }, { env, fetchImpl: async url => {
    const number = Number(url.searchParams.get('page')); requests.push(number);
    assert.ok(number >= 1 && number <= 3);
    return Response.json({ meta: { is_end: false }, documents: Array.from({ length: 20 }, (_, index) => parkingDocument({
      id: String(number * 100 + index), place_name: index < 15 ? `상가 ${number} ${index} 주차장` : `넘친 공영주차장 ${number} ${index}`, distance: String(100 + index),
    })) });
  } });
  assert.deepEqual(requests, [1, 2, 3]); assert.equal(result.places.length, 10); assert.equal(result.focus, null);
  assert.ok(result.places.every(value => !value.name.includes('넘친')));
});

test('only an explicit false is_end requests another parking page; restaurants never page', async () => {
  for (const meta of [undefined, {}, { is_end: true }, { is_end: 'false' }, { is_end: 0 }]) {
    let calls = 0;
    await searchNearby(parkingInput, { env, fetchImpl: async () => { calls++; return Response.json({ documents: [parkingDocument()], meta }); } });
    assert.equal(calls, 1);
  }
  for (const request of [input, { ...input, query: '커피' }]) {
    let calls = 0;
    const result = await searchNearby(request, { env, fetchImpl: async url => {
      calls++; assert.equal(url.searchParams.has('page'), false);
      return Response.json({ documents: [place()], meta: { is_end: false } });
    } });
    assert.equal(calls, 1); assert.equal(result.places.length, 1);
  }
});

test('optional parking page failure preserves earlier valid pages while first-page failure remains an error', async () => {
  const failures = [async () => { throw new Error('test-private-token'); }, async () => new Response('', { status: 403 }),
    async () => new Response('', { status: 500 }), async () => Response.json({ documents: null }), async () => new Response('invalid JSON')];
  for (const fail of failures) {
    for (const failedPage of [2, 3]) {
      const calls = [];
      const result = await searchNearby(parkingInput, { env, fetchImpl: async url => {
        const number = Number(url.searchParams.get('page')); calls.push(number);
        if (number === failedPage) return fail();
        return Response.json({ documents: [parkingDocument({ id: String(number) })], meta: { is_end: false } });
      } });
      assert.deepEqual(calls, failedPage === 2 ? [1, 2] : [1, 2, 3]);
      assert.equal(result.places.length, failedPage - 1); assert.ok(!JSON.stringify(result).includes('test-private-token'));
    }
    await assert.rejects(searchNearby(parkingInput, { env, fetchImpl: fail }), error => error.status === 502 && !error.message.includes('test-private-token'));
  }
});

test('parking page deadline is shared and later-page timeout returns the already received results', async t => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  let firstFinished, laterStarted;
  const firstPageDelay = new Promise(resolve => { firstFinished = resolve; });
  const waitingForLater = new Promise(resolve => { laterStarted = resolve; });
  let signal;
  const pending = searchNearby(parkingInput, { env, fetchImpl: async (url, options) => {
    signal = options.signal;
    if (url.searchParams.get('page') === '1') {
      await firstPageDelay;
      return Response.json({ documents: [parkingDocument()], meta: { is_end: false } });
    }
    laterStarted(); return new Promise(() => {});
  } });
  t.mock.timers.tick(6000); firstFinished();
  await waitingForLater;
  t.mock.timers.tick(5999); assert.equal(signal.aborted, false);
  t.mock.timers.tick(1);
  const result = await pending;
  assert.equal(signal.aborted, true); assert.equal(result.places.length, 1);
  const firstTimeout = searchNearby(parkingInput, { env, fetchImpl: async () => new Promise(() => {}) });
  t.mock.timers.tick(12000);
  await assert.rejects(firstTimeout, error => error.status === 504);
});
