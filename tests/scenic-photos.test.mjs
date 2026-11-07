import test from 'node:test';
import assert from 'node:assert/strict';
import { Readable } from 'node:stream';
import { readFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { createServer } from '../server/index.mjs';
import { createScenicSelectionSigner, findScenicPhotos as findPhotos, safeTourPhotoUrl } from '../server/scenic-photos.mjs';
import { planDestinationRoutes } from '../server/destination-routes.mjs';

const destination = { id: 'haeundae', name: '해운대해수욕장', address: '부산 해운대구', lat: 35.1587, lon: 129.1604 };
const origin = { lat: 35.1152, lon: 129.0422 };
const place = { id: 'tour-123', name: '광안리해수욕장', address: '부산 수영구', lat: 35.153, lon: 129.118 };
const input = { destination, radiusMeters: 10000 };
const findScenicPhotos = (input, options) => findPhotos(input, { catalog: { places: [] }, ...options });
const env = { TOUR_API_KEY: 'test-private-tour', OPENAI_API_KEY: 'test-private-openai', KAKAO_REST_API_KEY: 'test-private-kakao' };
const image = id => `https://tong.visitkorea.or.kr/cms/resource/10/${id}_image2_1.jpg`;
const item = (id = '123', extra = {}) => ({ contentid: id, contenttypeid: '12', title: '공개 관광 사진', addr1: '부산', mapx: '129.118', mapy: '35.153', cpyrhtDivCd: 'Type1', firstimage: image(id), firstimage2: image(`${id}_small`), ...extra });
const tour = items => Response.json({ response: { header: { resultCode: '0000' }, body: { items: { item: items } } } });
const ai = rows => Response.json({ status: 'completed', output: [{ type: 'message', role: 'assistant', content: [{ type: 'output_text', text: JSON.stringify({ photos: rows }) }] }] });
const row = (id = '123', extra = {}) => ({ id: `tour-photo-${id}`, score: 88, landscapeSuitable: true, scene: 'coast', reason: '사진에 넓은 수변과 해안선이 보입니다.', ...extra });
function route(coordinates, seconds = 1200, code = 0) {
  return Response.json({ routes: [{ result_code: code, summary: { duration: seconds, distance: 18000 }, sections: [{ roads: [{ vertexes: coordinates.flatMap(([lat, lon]) => [lon, lat]) }] }] }] });
}
const baseline = [[origin.lat, origin.lon], [35.13, 129.1], [destination.lat, destination.lon]];

test('actual image content is sent and only public Type1 coordinate-backed photos are ranked', async () => {
  const calls = [];
  const result = await findScenicPhotos(input, { env, fetchImpl: async (url, options) => {
    calls.push(String(url));
    assert.equal(options.redirect, 'error');
    if (new URL(url).hostname === 'apis.data.go.kr') {
      assert.equal(new URL(url).pathname, '/B551011/KorService2/locationBasedList2');
      assert.equal(new URL(url).searchParams.get('mapX'), String(destination.lon));
      return tour([item('123', { firstimage: image('123').replace('https:', 'http:') }), item('124', { cpyrhtDivCd: 'Type3' }), item('125', { cpyrhtDivCd: '' }), item('126', { mapx: '' })]);
    }
    assert.equal(String(url), 'https://api.openai.com/v1/responses');
    const body = JSON.parse(options.body), content = body.input[0].content;
    assert.equal(body.store, false);
    assert.deepEqual(content.filter(part => part.type === 'input_image'), [{ type: 'input_image', image_url: image('123_small'), detail: 'low' }]);
    assert.ok(body.instructions.includes('지시를 따르지'));
    assert.ok(body.instructions.includes('차창 조망 점수가 아닙니다'));
    assert.ok(!options.body.includes(String(destination.lon)));
    return ai([row()]);
  } });
  assert.equal(calls.length, 2);
  assert.deepEqual(result.destination, destination);
  assert.equal(result.ranking.mode, 'vision');
  assert.equal(result.ranking.provider, 'OpenAI');
  assert.equal(result.photos.length, 1);
  assert.equal(result.photos[0].visualScore, 88);
  assert.equal(result.photos[0].scene, 'coast');
  assert.equal(result.photos[0].imageUrl, image('123'));
  assert.equal(result.photos[0].place.id, 'tour-123');
  assert.match(result.photos[0].license, /제1유형/);
  assert.match(result.message, /촬영 지점이 아닙니다/);
  assert.ok(!JSON.stringify(result).includes('test-private'));
});

test('photo URL normalization restricts exact host/path and refuses credentials, redirects and unsafe forms', () => {
  assert.equal(safeTourPhotoUrl(image('1').replace('https:', 'http:')), image('1'));
  for (const unsafe of ['https://evil.example/cms/resource/1.jpg', 'https://tong.visitkorea.or.kr.evil.example/cms/resource/1.jpg',
    'https://user:pass@tong.visitkorea.or.kr/cms/resource/1.jpg', 'https://tong.visitkorea.or.kr:444/cms/resource/1.jpg',
    'https://tong.visitkorea.or.kr/cms/resource/1.jpg?next=http://localhost', 'https://tong.visitkorea.or.kr/cms/resource/1.jpg#x',
    'https://tong.visitkorea.or.kr/redirect/1.jpg', 'https://tong.visitkorea.or.kr/cms/resource/%2e%2e/1.jpg',
    'https://tong.visitkorea.or.kr/cms/resource/1.svg', 'file:///cms/resource/1.jpg', 'data:image/png;base64,YQ==',
    'https://tong.visitkorea.or.kr\\@evil.example/cms/resource/1.jpg']) assert.equal(safeTourPhotoUrl(unsafe), null, unsafe);
});

test('maximum twelve photos are unique and within destination radius, fallback stays distance-only', async () => {
  const items = Array.from({ length: 16 }, (_, i) => item(String(100 + i), { mapx: String(129.16 - i * .001) }));
  items.push(items[0], item('999', { firstimage: items[0].firstimage }), item('998', { mapy: '37.5' }), item('997', { firstimage: 'https://evil.example/a.jpg' }));
  const result = await findScenicPhotos(input, { env: { TOUR_API_KEY: 'x' }, fetchImpl: async () => tour(items) });
  assert.equal(result.photos.length, 12);
  assert.equal(new Set(result.photos.map(photo => photo.place.id)).size, 12);
  assert.equal(result.ranking.mode, 'distance');
  assert.equal(result.ranking.provider, null);
  assert.ok(result.photos.every(photo => photo.visualScore === null && photo.scene === 'all' && photo.distanceMeters <= 10000));
  assert.deepEqual(result.photos.map(photo => photo.distanceMeters), result.photos.map(photo => photo.distanceMeters).sort((a, b) => a - b));
});

test('invalid request shape, foreign destination and unsafe values fail before providers', async () => {
  for (const bad of [null, {}, { ...input, extra: true }, { ...input, radiusMeters: 999 }, { ...input, radiusMeters: 20001 },
    { ...input, radiusMeters: '10000' }, { ...input, destination: { ...destination, lat: 50 } },
    { ...input, destination: { ...destination, name: 'bad\nname' } }, { ...input, destination: { ...destination, imageUrl: image('1') } }]) {
    await assert.rejects(findScenicPhotos(bad, { env, fetchImpl: async () => assert.fail('No provider expected') }), error => error.status === 400);
  }
});

test('missing configuration, provider errors and no licensed photos remain explicit without invented photos', async () => {
  const missing = await findScenicPhotos(input, { env: {}, fetchImpl: async () => assert.fail('No provider expected') });
  assert.equal(missing.photos.length, 0);
  assert.match(missing.ranking.message, /키가 없어/);
  for (const mock of [async () => { throw new Error('test-private-tour'); }, async () => new Response('test-private-tour', { status: 403 }), async () => tour([item('1', { cpyrhtDivCd: 'Type3' })]), async () => Response.json({ unexpected: true })]) {
    const result = await findScenicPhotos(input, { env, fetchImpl: mock });
    assert.equal(result.photos.length, 0);
    assert.equal(result.ranking.mode, 'distance');
    assert.ok(!JSON.stringify(result).includes('test-private'));
  }
});

test('AI unavailable, malformed, hallucinated, duplicated or incomplete results fall back honestly', async () => {
  const badResponses = [() => { throw new Error('test-private-openai'); }, () => new Response('secret', { status: 429 }),
    () => ai([row('unknown')]), () => ai([row('123', { score: 101 })]), () => ai([row('123', { reason: 'x'.repeat(161) })]),
    () => ai([row('123', { scene: 'invented' })]), () => ai([row(), row()]),
    () => Response.json({ status: 'incomplete', output: [] }), () => ai([row('123', { lat: 35.1 })])];
  for (const failure of badResponses) {
    let calls = 0;
    const result = await findScenicPhotos(input, { env, fetchImpl: async () => ++calls === 1 ? tour([item()]) : failure() });
    assert.equal(result.photos.length, 1);
    assert.equal(result.ranking.mode, 'distance');
    assert.equal(result.ranking.provider, null);
    assert.equal(result.photos[0].visualScore, null);
    assert.equal(result.photos[0].scene, 'all');
    assert.match(result.ranking.message, /완료하지 못해/);
    assert.ok(!JSON.stringify(result).includes('test-private'));
  }
});

test('vision scores change ordering using allowlisted image IDs without substituting place metadata', async () => {
  let calls = 0;
  const result = await findScenicPhotos(input, { env, fetchImpl: async () => ++calls === 1 ? tour([item('123'), item('124', { mapx: '129.17' })]) : ai([row('123', { score: 99 }), row('124', { score: 60 })]) });
  assert.deepEqual(result.photos.map(photo => photo.id), ['tour-photo-123', 'tour-photo-124']);
  assert.equal(result.photos[0].place.lon, 129.118);
});

test('selection HMAC expires at thirty minutes and is bound to destination and server lifetime', () => {
  let now = 1000000;
  const signer = createScenicSelectionSigner({ now: () => now });
  const token = signer.issue(destination, place, 'coast');
  assert.equal(signer.verify(token, destination).id, place.id);
  const [payload, signature] = token.split('.');
  const edited = JSON.parse(Buffer.from(payload, 'base64url').toString('utf8')); edited.place.lat = 35.2;
  const forged = `${Buffer.from(JSON.stringify(edited)).toString('base64url')}.${signature}`;
  for (const bad of [forged, '', 'abc.def', null, token + 'x', 'x'.repeat(4097)]) assert.throws(() => signer.verify(bad, destination), error => error.status === 409);
  for (const change of [{ id: 'different' }, { name: 'different' }, { lon: destination.lon + .0001 }]) assert.throws(() => signer.verify(token, { ...destination, ...change }), error => error.status === 409);
  assert.throws(() => createScenicSelectionSigner({ now: () => now }).verify(token, destination), error => error.status === 409);
  now += 30 * 60 * 1000;
  assert.throws(() => signer.verify(token, destination), error => error.status === 409);
});

test('chosen photo is the exact waypoint, original destination is retained and prepared candidates are not used', async () => {
  const signer = createScenicSelectionSigner(), token = signer.issue(destination, place, 'coast'), calls = [];
  const result = await planDestinationRoutes({ origin, destination, extraMinutes: 30, theme: 'forest', scenicSelection: token }, { env, selectionSigner: signer,
    catalog: { get places() { assert.fail('Must not select a substitute'); } }, fetchImpl: async url => {
      const target = new URL(url); calls.push(target);
      assert.equal(target.searchParams.get('destination'), `${destination.lon},${destination.lat}`);
      return target.searchParams.has('waypoints') ? route([[origin.lat, origin.lon], [place.lat, place.lon], [destination.lat, destination.lon]], 1700) : route(baseline);
    } });
  assert.equal(calls.length, 2);
  assert.equal(calls[1].searchParams.get('waypoints'), `${place.lon},${place.lat}`);
  assert.deepEqual(result.destination, destination);
  assert.equal(result.scenicStatus, 'ok');
  assert.deepEqual(result.routes[1].waypoints, [{ id: place.id, name: place.name, lat: place.lat, lon: place.lon, source: '한국관광공사 TourAPI' }]);
  assert.match(result.routes[1].reason, /차창 조망은 미확인/);
});

test('selected photo time overflow, unreachable road and 150m proximity failure return fast only without replacement', async () => {
  const signer = createScenicSelectionSigner(), token = signer.issue(destination, place);
  const failures = [
    () => route([[origin.lat, origin.lon], [place.lat, place.lon], [destination.lat, destination.lon]], 3001),
    () => route(baseline, 1500, 104),
    () => route([[origin.lat, origin.lon], [place.lat - .003, place.lon], [destination.lat, destination.lon]], 1500),
  ];
  for (let i = 0; i < failures.length; i++) {
    let calls = 0;
    const result = await planDestinationRoutes({ origin, destination, extraMinutes: 30, scenicSelection: token }, { env, selectionSigner: signer, fetchImpl: async () => ++calls === 1 ? route(baseline) : failures[i]() });
    assert.equal(calls, 2);
    assert.equal(result.scenicStatus, 'no_feasible_route');
    assert.deepEqual(result.routes.map(value => value.id), ['fast']);
    assert.deepEqual(result.destination, destination);
    assert.match(result.message, i === 0 ? /추가 시간을 넘어/ : /150m 이내/);
  }
});

test('extra zero stays disabled and stale selection fails before road provider calls', async () => {
  const signer = createScenicSelectionSigner(), token = signer.issue(destination, place);
  let calls = 0;
  const result = await planDestinationRoutes({ origin, destination, extraMinutes: 0, scenicSelection: token }, { env, selectionSigner: signer, fetchImpl: async () => { calls++; return route(baseline); } });
  assert.equal(result.scenicStatus, 'disabled'); assert.equal(calls, 1);
  await assert.rejects(planDestinationRoutes({ origin, destination, scenicSelection: 'bad' }, { env, fetchImpl: async () => assert.fail('No provider expected') }), error => error.status === 409);
});

let requestIndex = 0;
function request(server, path, body) {
  return new Promise(resolve => {
    const req = Readable.from([Buffer.from(JSON.stringify(body))]);
    Object.assign(req, { url: path, method: 'POST', headers: { host: '127.0.0.1:4173', 'content-type': 'application/json' }, socket: { remoteAddress: `photo-test-${requestIndex++}` } });
    server.emit('request', req, { setHeader() {}, writeHead(status) { this.status = status; }, end(value) { resolve({ status: this.status, body: JSON.parse(String(value)) }); } });
  });
}
test('HTTP photo and routing endpoints share signer, while another server rejects the token', async () => {
  const server = createServer({ env: { ...env, OPENAI_API_KEY: '' }, fetchImpl: async url => {
    const target = new URL(url);
    if (target.hostname === 'apis.data.go.kr') return tour([item('123', { title: place.name })]);
    return target.searchParams.has('waypoints') ? route([[origin.lat, origin.lon], [place.lat, place.lon], [destination.lat, destination.lon]], 1700) : route(baseline);
  } });
  const photos = await request(server, '/api/scenic-photos', input);
  assert.equal(photos.status, 200);
  const payload = { origin, destination, extraMinutes: 30, scenicSelection: photos.body.photos[0].selectionToken };
  const planned = await request(server, '/api/destination-routes', payload);
  assert.equal(planned.status, 200); assert.equal(planned.body.scenicStatus, 'ok');
  const restarted = await request(createServer({ env, fetchImpl: async () => assert.fail('No provider expected') }), '/api/destination-routes', payload);
  assert.equal(restarted.status, 409);
  assert.match(restarted.body.error, /再|다시/);
});

test('indoor attractions, monuments, stations and walking-only destinations are excluded before image analysis', async () => {
  const excluded = ['해안 박물관', '숲 미술관', '강릉역', '속초터미널', '충무공 동상', '기념비', '남산순환나들길',
    '해안산책로', '해파랑길', '전망 스카이워크', '백두산 정상', '경포 탐방로', '남해 바래길'];
  const items = excluded.map((title, i) => item(String(100 + i), { title }));
  items.push(item('250', { title: '등대 전망 장소', lclsSystm3: 'VE040300' }));
  let calls = 0;
  const result = await findScenicPhotos(input, { env, fetchImpl: async () => { calls++; return tour(items); } });
  assert.equal(calls, 1); assert.equal(result.photos.length, 0);
  assert.equal(result.ranking.mode, 'distance');
  assert.match(result.ranking.message, /찾지 못했습니다/);
});

test('natural viewpoints and coast candidates reach the bounded image request ahead of generic nearby attractions', async () => {
  const items = Array.from({ length: 14 }, (_, i) => item(String(100 + i), { title: '일반 관광 장소', mapx: String(129.16 + i * .0001) }));
  items.push(item('999', { title: '해안 전망대', mapx: '129.12' }));
  const result = await findScenicPhotos(input, { env: { TOUR_API_KEY: 'x' }, fetchImpl: async () => tour(items) });
  assert.equal(result.photos.length, 12);
  assert.ok(result.photos.some(photo => photo.id === 'tour-photo-999'));
  assert.deepEqual(result.photos.map(photo => photo.distanceMeters), result.photos.map(photo => photo.distanceMeters).sort((a, b) => a - b));
  assert.ok(result.photos.every(photo => photo.visualScore === null));
});

test('actual image suitability overrides attractive place names and can return an honest empty vision result', async () => {
  let calls = 0;
  const result = await findScenicPhotos(input, { env, fetchImpl: async () => ++calls === 1
    ? tour([item('123', { title: '해안 전망대' }), item('124', { title: '푸른 바다 해변' }), item('125', { title: '도시 야경' })])
    : ai([row('123', { landscapeSuitable: false, score: 90, reason: '사진은 안내판 중심입니다.' }), row('124', { score: 54 }), row('125', { scene: 'all' })]) });
  assert.equal(result.ranking.mode, 'vision'); assert.equal(result.ranking.provider, 'OpenAI');
  assert.deepEqual(result.photos, []);
  assert.match(result.ranking.message, /실제 이미지를 평가했지만/);
});

test('image suitability must be a real boolean and qualifying pictures retain source coordinates without extra public fields', async () => {
  for (const suitable of [undefined, 'true', 1]) {
    let calls = 0;
    const result = await findScenicPhotos(input, { env, fetchImpl: async () => ++calls === 1 ? tour([item()]) : ai([row('123', { landscapeSuitable: suitable })]) });
    assert.equal(result.ranking.mode, 'distance'); assert.equal(result.photos[0].visualScore, null);
  }
  let calls = 0;
  const good = await findScenicPhotos(input, { env, fetchImpl: async () => ++calls === 1 ? tour([item()]) : ai([row('123', { score: 55 })]) });
  assert.equal(good.ranking.mode, 'vision'); assert.equal(good.photos.length, 1);
  assert.ok(!Object.hasOwn(good.photos[0], 'landscapeSuitable'));
  assert.equal(good.photos[0].place.lat, 35.153);
});

test('photo-destination routes directly to selected photo POI, never to or back to the region anchor', async () => {
  const signer = createScenicSelectionSigner(), token = signer.issue(destination, place);
  const calls = [];
  const result = await planDestinationRoutes({ origin, destination, mode: 'photo-destination', scenicSelection: token, extraMinutes: 0 }, {
    env, selectionSigner: signer, catalog: { get places() { assert.fail('No substitutions'); } }, fetchImpl: async url => {
      const parsed = new URL(url); calls.push(parsed);
      assert.equal(parsed.searchParams.get('destination'), `${place.lon},${place.lat}`);
      assert.equal(parsed.searchParams.has('waypoints'), false);
      return route([[origin.lat, origin.lon], [place.lat, place.lon]], 1800);
    },
  });
  assert.equal(calls.length, 1);
  assert.equal(result.mode, 'photo-destination'); assert.equal(result.scenicStatus, 'ok');
  assert.deepEqual(result.requestedDestination, destination); assert.deepEqual(result.destination, place);
  assert.deepEqual(result.routes.map(value => value.id), ['fast']);
  assert.deepEqual(result.routes[0].waypoints, []);
  assert.deepEqual(result.routes[0].coordinates.at(-1), [place.lat, place.lon]);
  assert.match(result.message, /촬영 지점이나 주차장 입구가 아닙니다/);
});

test('photo-destination requires selected token and rejects unknown modes and client target overrides', async () => {
  for (const bad of [{ origin, destination, mode: 'photo-destination' }, { origin, destination, mode: 'unknown' },
    { origin, destination, mode: 'photo-destination', scenicSelection: 'bad', photoPlace: place }]) {
    await assert.rejects(planDestinationRoutes(bad, { env, fetchImpl: async () => assert.fail('No provider expected') }), error => error.status === 400);
  }
  const signer = createScenicSelectionSigner(), token = signer.issue(destination, place);
  await assert.rejects(planDestinationRoutes({ origin, destination: place, mode: 'photo-destination', scenicSelection: token }, { env, selectionSigner: signer, fetchImpl: async () => assert.fail('No provider expected') }), error => error.status === 409);
});

test('photo-destination requires arrival within 150m, not a nearby pass followed by a different endpoint', async () => {
  const signer = createScenicSelectionSigner(), token = signer.issue(destination, place);
  for (const coordinates of [
    [[origin.lat, origin.lon], [place.lat, place.lon], [destination.lat, destination.lon]],
    [[origin.lat, origin.lon], [place.lat + .002, place.lon]],
  ]) {
    let calls = 0;
    await assert.rejects(planDestinationRoutes({ origin, destination, mode: 'photo-destination', scenicSelection: token }, { env, selectionSigner: signer,
      fetchImpl: async () => { calls++; return route(coordinates); } }), error => error.status === 422);
    assert.equal(calls, 1);
  }
  const accepted = await planDestinationRoutes({ origin, destination, mode: 'photo-destination', scenicSelection: token }, { env, selectionSigner: signer,
    fetchImpl: async () => route([[origin.lat, origin.lon], [place.lat + .001, place.lon]]) });
  assert.equal(accepted.destination.id, place.id);
});

test('photo-destination unreachable, provider failure and already-near-photo never manufacture region fallback', async () => {
  const signer = createScenicSelectionSigner(), token = signer.issue(destination, place);
  for (const [mock, status] of [[async () => route(baseline, 1200, 104), 422], [async () => { throw new Error('test-private-kakao'); }, 502]]) {
    let calls = 0;
    await assert.rejects(planDestinationRoutes({ origin, destination, mode: 'photo-destination', scenicSelection: token }, { env, selectionSigner: signer,
      fetchImpl: async (...args) => { calls++; return mock(...args); } }), error => error.status === status && !error.message.includes('test-private'));
    assert.equal(calls, 1);
  }
  await assert.rejects(planDestinationRoutes({ origin: { lat: place.lat, lon: place.lon }, destination, mode: 'photo-destination', scenicSelection: token }, { env, selectionSigner: signer, fetchImpl: async () => assert.fail('No provider expected') }), error => error.status === 422);
  const fromRegion = await planDestinationRoutes({ origin: { lat: destination.lat, lon: destination.lon }, destination, mode: 'photo-destination', scenicSelection: token }, { env, selectionSigner: signer,
    fetchImpl: async () => route([[destination.lat, destination.lon], [place.lat, place.lon]]) });
  assert.equal(fromRegion.destination.id, place.id);
});

test('sparse location index is supplemented with at most eight fresh official details and three concurrent requests', async () => {
  const catalog = { places: Array.from({ length: 12 }, (_, i) => ({ id: String(1000 + i), title: '해안 해변', lat: 35.153, lon: 129.118 + i * .001, contentTypeId: '12' })) };
  let active = 0, maximum = 0; const ids = [];
  const result = await findScenicPhotos(input, { catalog, env: { TOUR_API_KEY: 'x' }, fetchImpl: async (url, options) => {
    const target = new URL(url);
    assert.equal(options.redirect, 'error'); assert.ok(options.signal instanceof AbortSignal);
    if (target.pathname.endsWith('/locationBasedList2')) return tour([]);
    assert.ok(target.pathname.endsWith('/detailCommon2'));
    assert.ok(!target.searchParams.has('mapX') && !target.searchParams.has('radius') && !target.searchParams.has('contentTypeId'));
    const id = target.searchParams.get('contentId'); ids.push(id); active++; maximum = Math.max(maximum, active);
    await new Promise(resolve => setTimeout(resolve, 2)); active--;
    return tour([item(id, { title: '현재 확인된 해변', mapx: '129.12' })]);
  } });
  assert.equal(ids.length, 8); assert.equal(new Set(ids).size, 8); assert.equal(maximum, 3);
  assert.equal(result.photos.length, 8);
  assert.ok(result.photos.every(photo => photo.place.name === '현재 확인된 해변' && photo.place.lon === 129.12));
  assert.equal(result.ranking.mode, 'distance');
});

test('supplement cannot use saved image metadata when current detail is missing, mismatched, unlicensed or outside radius', async () => {
  const catalog = { places: Array.from({ length: 6 }, (_, i) => ({ id: String(1000 + i), title: '저장된 해변', lat: 35.153, lon: 129.118,
    firstimage: image('saved'), cpyrhtDivCd: 'Type1' })) };
  const result = await findScenicPhotos(input, { catalog, env: { TOUR_API_KEY: 'x' }, fetchImpl: async url => {
    const target = new URL(url); if (target.pathname.endsWith('/locationBasedList2')) return tour([]);
    const id = target.searchParams.get('contentId');
    if (id === '1000') return tour([item('999999')]);
    if (id === '1001') return tour([item(id, { cpyrhtDivCd: '' })]);
    if (id === '1002') return tour([item(id, { cpyrhtDivCd: 'Type3' })]);
    if (id === '1003') return tour([item(id, { mapy: '37.5' })]);
    if (id === '1004') return tour([item(id, { firstimage: '' })]);
    return tour([]);
  } });
  assert.deepEqual(result.photos, []);
  assert.match(result.ranking.message, /일부 장소의 최신 사진은 확인하지 못했습니다/);
  assert.ok(!JSON.stringify(result).includes(image('saved')));
});

test('supplement excludes stale foreign, walking-only, duplicate and unsafe IDs while retaining good partial details', async () => {
  const good = { id: '1234', title: '해변', lat: 35.153, lon: 129.118 };
  const catalog = { places: [good, good, { ...good, id: '12?secret' }, { ...good, id: '2', lat: 50 },
    { ...good, id: '3', title: '해안 산책로' }, { ...good, id: '4', contentTypeId: '39' }, { ...good, id: '5' }] };
  const ids = [];
  const result = await findScenicPhotos(input, { catalog, env: { TOUR_API_KEY: 'x' }, fetchImpl: async url => {
    const target = new URL(url); if (target.pathname.endsWith('/locationBasedList2')) return tour([]);
    const id = target.searchParams.get('contentId'); ids.push(id);
    if (id === '5') throw new Error('private-provider-value');
    return tour([item(id, { title: '새로 확인된 바다' })]);
  } });
  assert.deepEqual(ids.sort(), ['1234', '5']);
  assert.equal(result.photos.length, 1); assert.equal(result.photos[0].place.id, 'tour-1234');
  assert.match(result.ranking.message, /일부 장소/);
  assert.ok(!JSON.stringify(result).includes('private-provider-value'));
});

test('overall deadline prevents late detail and AI calls instead of extending the UI wait', async () => {
  const originalNow = Date.now; let now = originalNow(), calls = 0;
  Date.now = () => now;
  try {
    const result = await findScenicPhotos(input, { env, catalog: { places: [{ id: '1234', title: '해변', lat: 35.153, lon: 129.118 }] },
      fetchImpl: async () => { calls++; now += 70001; return tour([]); } });
    assert.equal(calls, 1); assert.deepEqual(result.photos, []);
    assert.match(result.ranking.message, /일부 장소/);
  } finally { Date.now = originalNow; }
});

test('new official resource_photo images keep the same strict host and path policy', () => {
  const official = 'https://tong.visitkorea.or.kr/cms/resource_photo/50/4118550_image2_1.jpg';
  assert.equal(safeTourPhotoUrl(official.replace('https:', 'http:')), official);
  for (const bad of [official + '?token=x', official.replace('resource_photo', 'resource_photos'), official.replace('/50/', '/%2e%2e/'), official.replace('tong.visitkorea.or.kr', 'elsewhere.example')]) assert.equal(safeTourPhotoUrl(bad), null);
});

const curatedCatalog = preferred => ({ places: [{ id: '123', title: '광안리해수욕장', lat: 35.153, lon: 129.118,
  curated: true, preferredImage: { serial: 'chosen-1', imageUrl: image('old-saved'), ...preferred } }] });
const galleryImage = extra => ({ contentid: '123', serialnum: 'chosen-1', cpyrhtDivCd: 'Type1',
  originimgurl: image('gallery-current'), smallimageurl: image('gallery-small'), ...extra });

test('selected gallery image needs current per-image license and is the actual image sent to AI', async () => {
  const result = await findScenicPhotos(input, { env, catalog: curatedCatalog(), fetchImpl: async (url, options) => {
    const target = new URL(url);
    if (target.pathname.endsWith('/locationBasedList2')) return tour([item()]);
    if (target.pathname.endsWith('/detailImage2')) {
      assert.equal(target.searchParams.get('contentId'), '123'); assert.equal(target.searchParams.get('numOfRows'), '30');
      assert.ok(!target.searchParams.has('subImageYN')); return tour([galleryImage()]);
    }
    const body = JSON.parse(options.body);
    assert.equal(body.input[0].content[1].image_url, image('gallery-small'));
    return ai([row()]);
  } });
  assert.equal(result.photos[0].imageUrl, image('gallery-current'));
  assert.equal(result.photos[0].place.id, 'tour-123');
  assert.ok(!JSON.stringify(result).includes('old-saved'));
});

test('gallery identity, changed license, unsafe URL and missing selection never publish a saved image', async () => {
  for (const rows of [[galleryImage({ cpyrhtDivCd: 'Type3' })], [galleryImage({ contentid: '999' })],
    [galleryImage({ serialnum: 'other' })], [galleryImage({ originimgurl: 'https://evil.example/a.jpg' })], [], [galleryImage(), galleryImage()]]) {
    const result = await findScenicPhotos(input, { env: { TOUR_API_KEY: 'x' }, catalog: curatedCatalog(), fetchImpl: async url =>
      new URL(url).pathname.endsWith('/locationBasedList2') ? tour([item()]) : tour(rows) });
    assert.equal(result.photos[0].imageUrl, image('123'));
    assert.match(result.ranking.message, /선별 사진은 갱신하지 못해/);
    assert.ok(!JSON.stringify(result).includes('old-saved'));
  }
});

test('curated missing place is refreshed even when location search has six ordinary photos', async () => {
  const catalog = { places: [{ id: '2000', title: '경포호', lat: 35.153, lon: 129.118, curated: true }] }, calls = [];
  const result = await findScenicPhotos(input, { env: { TOUR_API_KEY: 'x' }, catalog, fetchImpl: async url => {
    const target = new URL(url); calls.push(target.pathname);
    return target.pathname.endsWith('/locationBasedList2') ? tour(Array.from({ length: 6 }, (_, i) => item(String(i + 1)))) : tour([item('2000', { title: '최신 호수 사진' })]);
  } });
  assert.ok(calls.some(path => path.endsWith('/detailCommon2')));
  assert.ok(result.photos.some(photo => photo.place.id === 'tour-2000' && photo.place.name === '최신 호수 사진'));
});

test('gallery enrichment stays bounded to four places and two concurrent calls', async () => {
  const catalog = { places: Array.from({ length: 8 }, (_, i) => ({ id: String(i + 1), title: '해변', lat: 35.153, lon: 129.118,
    curated: true, preferredImage: { serial: 'chosen-1' } })) };
  let calls = 0, active = 0, maximum = 0;
  const result = await findScenicPhotos(input, { env: { TOUR_API_KEY: 'x' }, catalog, fetchImpl: async url => {
    const target = new URL(url);
    if (target.pathname.endsWith('/locationBasedList2')) return tour(catalog.places.map(p => item(p.id)));
    assert.ok(target.pathname.endsWith('/detailImage2')); calls++; active++; maximum = Math.max(maximum, active);
    await new Promise(resolve => setTimeout(resolve, 2)); active--;
    return tour([galleryImage({ contentid: target.searchParams.get('contentId') })]);
  } });
  assert.equal(calls, 4); assert.equal(maximum, 2); assert.equal(result.photos.length, 8);
});

test('eight bundled regional photos are licensed, attributed, diverse and match their manifest hashes', async () => {
  const base = new URL('../android/app/src/main/assets/scenic-regions/', import.meta.url);
  const manifest = JSON.parse(await readFile(new URL('manifest.json', base), 'utf8'));
  assert.equal(manifest.photos.length, 8);
  assert.equal(new Set(manifest.photos.map(photo => photo.regionId)).size, 8);
  assert.equal(new Set(manifest.photos.map(photo => photo.sha256)).size, 8);
  for (const photo of manifest.photos) {
    assert.match(photo.license, /제1유형/); assert.equal(photo.sourceName, '한국관광공사 TourAPI');
    assert.equal(photo.file, photo.regionId + '.jpg'); assert.ok(safeTourPhotoUrl(photo.originalUrl));
    assert.ok(photo.width >= 500 && photo.width <= 960 && photo.height <= 720);
    const bytes = await readFile(new URL(photo.file, base)); assert.ok(bytes.length < 300000);
    assert.equal(createHash('sha256').update(bytes).digest('hex'), photo.sha256);
    assert.equal(bytes.readUInt16BE(0), 0xffd8);
  }
});
