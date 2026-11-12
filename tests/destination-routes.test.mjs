import test from 'node:test';
import assert from 'node:assert/strict';
import { Readable } from 'node:stream';
import { createServer } from '../server/index.mjs';
import { planDestinationRoutes, selectScenicCandidates } from '../server/destination-routes.mjs';
import { createScenicSelectionSigner } from '../server/scenic-photos.mjs';

const env = { KAKAO_REST_API_KEY: 'private-test-key' };
const origin = { lat: 35.1152, lon: 129.0422 };
const destination = { id: 'public-haeundae', name: '해운대해수욕장', lat: 35.1587, lon: 129.1604 };
const input = { origin, destination, extraMinutes: 30, theme: 'coast' };
const baseline = [[35.1152, 129.0422], [35.135, 129.075], [35.142, 129.125], [35.1587, 129.1604]];
let requestId = 0;
function request(pathname, payload, { fetchImpl = async () => { throw new Error('Unexpected fetch'); }, ...options } = {}) {
  const server = createServer({ env, fetchImpl, scenery: null, courses: [], ...options });
  return new Promise((resolve) => {
    const req = Readable.from(payload === undefined ? [] : [Buffer.from(JSON.stringify(payload))]);
    Object.assign(req, { url: pathname, method: payload === undefined ? 'GET' : 'POST', headers: { host: '127.0.0.1:4173', 'content-type': 'application/json' }, socket: { remoteAddress: `destination-${requestId++}` } });
    const res = { setHeader() {}, writeHead(status) { this.status = status; }, end(body) { resolve({ status: this.status, body: JSON.parse(String(body)) }); } };
    server.emit('request', req, res);
  });
}
function road(coordinates, seconds = 1200, distance = 14500, code = 0) {
  return Response.json({ routes: [{ result_code: code, summary: { distance, duration: seconds }, sections: [{ roads: [{ name: '검증 도로', distance, duration: seconds, traffic_speed: 30, traffic_state: 2, vertexes: coordinates.flatMap(([lat, lon]) => [lon, lat]) }] }] }] });
}
function routeFetch({ scenicSeconds = 1800, scenicCode = 0, failScenic = false, fastCoordinates = baseline, scenicCoordinates, fastSeconds = 1200 } = {}) {
  const calls = [];
  return { calls, fetchImpl: async (url, options) => {
    const target = new URL(url); calls.push(target);
    assert.equal(target.hostname, 'apis-navi.kakaomobility.com');
    assert.equal(options.headers.Authorization, 'KakaoAK private-test-key');
    assert.equal(options.redirect, 'error');
    assert.ok(options.signal instanceof AbortSignal);
    if (!target.searchParams.has('waypoints')) return road(fastCoordinates, fastSeconds);
    if (failScenic) throw new Error('private-test-key');
    const [lon, lat] = target.searchParams.get('waypoints').split(',').map(Number);
    return road(scenicCoordinates ?? [baseline[0], [35.14, 129.085], [lat, lon], baseline.at(-1)], scenicSeconds, 18000, scenicCode);
  } };
}

test('Gyeongpo Lake photo rejects a road ending 344m away even when Kakao summary repeats the requested coordinate', async () => {
  const start = { lat: 35.1151, lon: 129.0414 };
  const region = { id: 'region-gangneung', name: '강릉', lat: 37.7519, lon: 128.8761 };
  const photo = { id: 'tour-127975', name: '경포호(철새도래지)', lat: 37.7967, lon: 128.9021 };
  const selectionSigner = createScenicSelectionSigner();
  let calls = 0;
  // Sanitized metadata from a successful Kakao response on 2026-11-09.
  // summary.destination is the requested POI, not the final drivable vertex.
  const data = await road([[start.lat, start.lon], [37.79914818296551, 128.89970305024505]], 17416, 378255).json();
  data.routes[0].summary.destination = { name: '', x: 128.9020918800868, y: 37.796697175463294 };
  await assert.rejects(planDestinationRoutes({ origin: start, destination: region, mode: 'photo-destination',
    scenicSelection: selectionSigner.issue(region, photo), extraMinutes: 30 }, {
    env, selectionSigner, catalog: { places: [] }, courses: [],
    fetchImpl: async () => { calls++; return Response.json(data); },
  }), error => error.status === 422 && /150m/.test(error.message));
  assert.equal(calls, 1, 'An unreachable photo must not trigger scenic calls or a replacement destination');
});

test('photo course roads require reaching their waypoints in order, allowing an earlier pass of a later stop', async () => {
  const start = { lat: 35.1, lon: 129 };
  const photo = { id: 'photo-coast', name: '선택한 해안', lat: 35.1, lon: 129.3 };
  const first = { name: '첫 해안', lat: 35.12, lon: 129.1 };
  const second = { name: '둘째 해안', lat: 35.08, lon: 129.2 };
  const selectionSigner = createScenicSelectionSigner();
  const scenicSelection = selectionSigner.issue(destination, photo);
  const coordinates = place => [place.lat, place.lon];
  for (const [stops, expectedIds] of [
    [[second, first], ['fast']],
    [[first, second], ['fast', 'curated']],
    [[second, first, second], ['fast', 'curated']],
  ]) {
    const result = await planDestinationRoutes({ origin: start, destination, mode: 'photo-destination', scenicSelection, extraMinutes: 30, theme: 'coast' }, {
      env, selectionSigner, catalog: { places: [] },
      courses: [{ id: 'coast-course', title: '해안 드라이브', terrain: 'coast', stops: [first, second, photo] }],
      fetchImpl: async url => {
        const query = new URL(url).searchParams;
        assert.equal(query.get('destination'), '129.3,35.1');
        return query.has('waypoints')
          ? road([start, ...stops, photo].map(coordinates), 1800, 50000)
          : road([start, photo].map(coordinates), 1200, 30000);
      },
    });
    assert.deepEqual(result.routes.map(route => route.id), expectedIds);
    for (const route of result.routes) assert.deepEqual(route.coordinates.at(-1), [35.1, 129.3]);
  }
});

test('photo scenic pairs can pass the second stop early and return to it after the first', async () => {
  const start = { lat: 35.1, lon: 129 };
  const photo = { id: 'photo-coast', name: '선택한 해안', lat: 35.1, lon: 129.3 };
  const selectionSigner = createScenicSelectionSigner();
  const result = await planDestinationRoutes({ origin: start, destination, mode: 'photo-destination', scenicSelection: selectionSigner.issue(destination, photo), extraMinutes: 30, theme: 'coast' }, {
    env, selectionSigner,
    catalog: { places: [{ id: 'first', title: '첫 해안', lat: 35.12, lon: 129.1 }, { id: 'second', title: '둘째 해안', lat: 35.08, lon: 129.2 }] },
    fetchImpl: async url => {
      const query = new URL(url).searchParams;
      assert.equal(query.get('destination'), '129.3,35.1');
      const stops = (query.get('waypoints') || '').split('|').filter(Boolean).map(pair => pair.split(',').map(Number).reverse());
      if (stops.length === 2) stops.unshift(stops[1]);
      return road([[35.1, 129], ...stops, [35.1, 129.3]], stops.length ? 1800 : 1200, stops.length > 1 ? 50000 : 30000);
    },
  });
  assert.deepEqual(result.routes.map(route => route.id), ['fast', 'scenic']);
  assert.deepEqual(result.routes[1].waypoints.map(stop => stop.id), ['first', 'second']);
  assert.deepEqual(result.routes[1].coordinates.at(-1), [35.1, 129.3]);
});

test('destination search normalizes Kakao Local results and omits invalid, duplicate and private fields', async () => {
  let called;
  const result = await request('/api/destinations?q=%20해운대%20', undefined, { fetchImpl: async (url, options) => {
    called = new URL(url);
    assert.equal(options.headers.Authorization, 'KakaoAK private-test-key');
    return Response.json({ documents: [
      { id: '20323456', place_name: '해운대해수욕장', address_name: '부산 해운대구 우동', road_address_name: '부산 해운대구 해운대해변로 264', x: '129.1604', y: '35.1587', phone: 'private-field' },
      { id: '20323456', place_name: 'duplicate', x: '129.1604', y: '35.1587' },
      { id: '2', place_name: 'Invalid', x: '', y: '35.1' },
      { id: '3', place_name: 'Foreign', x: '139.69', y: '35.68' },
    ] });
  } });
  assert.equal(result.status, 200);
  assert.equal(called.hostname, 'dapi.kakao.com');
  assert.equal(called.pathname, '/v2/local/search/keyword.json');
  assert.equal(called.searchParams.get('query'), '해운대');
  assert.deepEqual(result.body, { source: 'Kakao Local', places: [{ id: '20323456', name: '해운대해수욕장', address: '부산 해운대구 해운대해변로 264', lat: 35.1587, lon: 129.1604 }] });
});

test('empty and oversized destination queries fail before external calls', async () => {
  for (const query of ['', '%20%20', '가'.repeat(81)]) {
    assert.equal((await request(`/api/destinations?q=${query}`)).status, 400);
  }
});

test('search provider failure and malformed success stay explicit and never leak credentials', async () => {
  for (const fetchImpl of [async () => { throw new Error('private-test-key'); }, async () => Response.json({ unexpected: 'private-test-key' }), async () => new Response('private-test-key', { status: 401 })]) {
    const result = await request('/api/destinations?q=해운대', undefined, { fetchImpl });
    assert.equal(result.status, 502);
    assert.ok(!JSON.stringify(result.body).includes('private-test-key'));
    assert.ok(result.body.error);
  }
});

test('fast and scenic routes retain one selected destination and use verified road geometry and time', async () => {
  const mock = routeFetch();
  const result = await request('/api/destination-routes', input, mock);
  assert.equal(result.status, 200);
  assert.equal(result.body.scenicStatus, 'ok');
  assert.deepEqual(result.body.destination, destination);
  assert.deepEqual(result.body.origin, origin);
  assert.deepEqual(result.body.routes.map(route => route.id), ['fast', 'scenic']);
  const [fast, scenic] = result.body.routes;
  assert.equal(fast.durationMin, 20);
  assert.equal(fast.distanceKm, 14.5);
  assert.deepEqual(fast.coordinates, baseline);
  assert.deepEqual(fast.waypoints, []);
  assert.equal(scenic.durationMin, 30);
  assert.equal(scenic.distanceKm, 18);
  assert.equal(scenic.source, 'Kakao Mobility');
  assert.equal(scenic.waypoints.length, 1);
  assert.equal(scenic.waypoints[0].source, '한국관광공사 TourAPI');
  assert.ok(scenic.waypoints[0].lon > origin.lon && scenic.waypoints[0].lon < destination.lon);
  assert.ok(scenic.scenicLabels.length > 0);
  assert.notDeepEqual(scenic.coordinates, baseline);
  for (const call of mock.calls) {
    assert.equal(call.searchParams.get('origin'), '129.0422,35.1152');
    assert.equal(call.searchParams.get('destination'), '129.1604,35.1587');
    assert.equal(call.searchParams.get('priority'), 'TIME');
  }
  assert.equal(mock.calls[0].searchParams.get('avoid'), 'ferries');
  assert.equal(mock.calls[1].searchParams.get('avoid'), 'motorway|ferries');
  assert.equal(mock.calls[1].searchParams.get('waypoints').split('|').length, 1);
  assert.ok(!/최고의|반드시.*보|완전.*제외/.test(scenic.reason));
});

test('scenic extra time is compared before rounding and over-budget results return fast only', async () => {
  const mock = routeFetch({ scenicSeconds: 3001 });
  const result = await request('/api/destination-routes', input, mock);
  assert.equal(result.status, 200);
  assert.equal(result.body.scenicStatus, 'no_feasible_route');
  assert.deepEqual(result.body.routes.map(route => route.id), ['fast']);
  // One baseline plus up to three candidates, each retried once when motorway avoidance runs over budget.
  assert.ok(mock.calls.length > 1 && mock.calls.length <= 7);
  assert.ok(result.body.message);
});

test('a scenic duration exactly on the time budget remains eligible', async () => {
  const result = await request('/api/destination-routes', input, routeFetch({ scenicSeconds: 3000 }));
  assert.equal(result.status, 200);
  assert.equal(result.body.scenicStatus, 'ok');
  assert.equal(result.body.routes[1].durationMin, 50);
});

test('zero extra minutes returns only fast with an explicit disabled reason', async () => {
  const mock = routeFetch();
  const result = await request('/api/destination-routes', { ...input, extraMinutes: 0 }, mock);
  assert.equal(result.status, 200);
  assert.equal(result.body.scenicStatus, 'disabled');
  assert.equal(mock.calls.length, 1);
  assert.deepEqual(result.body.routes.map(route => route.id), ['fast']);
});

test('scenic provider failure preserves fast only and hides upstream errors', async () => {
  const result = await request('/api/destination-routes', input, routeFetch({ failScenic: true }));
  assert.equal(result.status, 200);
  assert.equal(result.body.scenicStatus, 'provider_unavailable');
  assert.deepEqual(result.body.routes.map(route => route.id), ['fast']);
  assert.ok(!JSON.stringify(result.body).includes('private-test-key'));
});

test('an infeasible scenic road is not returned as a successful scenery route', async () => {
  const result = await request('/api/destination-routes', input, routeFetch({ scenicCode: 104 }));
  assert.equal(result.status, 200);
  assert.equal(result.body.scenicStatus, 'no_feasible_route');
  assert.deepEqual(result.body.routes.map(route => route.id), ['fast']);
});

test('baseline failure never manufactures a straight-line or catalog route', async () => {
  for (const fetchImpl of [async () => road(baseline, 0, 0, 104), async () => Response.json({ routes: [] }), async () => { throw new Error('private-test-key'); }]) {
    const result = await request('/api/destination-routes', input, { fetchImpl });
    assert.equal(result.status, 502);
    assert.ok(!Object.hasOwn(result.body, 'routes'));
    assert.ok(!JSON.stringify(result.body).includes('private-test-key'));
  }
});

test('road geometry must actually approach the fixed origin and destination', async () => {
  const result = await request('/api/destination-routes', input, routeFetch({ fastCoordinates: [[37.5, 127], [37.6, 127.1]] }));
  assert.equal(result.status, 502);
  assert.ok(!Object.hasOwn(result.body, 'routes'));
});

test('unchanged baseline geometry cannot masquerade as a scenic detour', async () => {
  const result = await request('/api/destination-routes', input, routeFetch({ scenicCoordinates: baseline }));
  assert.equal(result.status, 200);
  assert.deepEqual(result.body.routes.map(route => route.id), ['fast']);
});

test('invalid route inputs reject injected stops, foreign coordinates and budgets before fetch', async () => {
  for (const payload of [null, [], { ...input, stops: [] }, { ...input, extraMinutes: -1 }, { ...input, extraMinutes: 121 }, { ...input, extraMinutes: 2.5 }, ...['desert', 'lake', 'night', '__proto__', 'constructor', '', null, 0].map(theme => ({ ...input, theme })), { ...input, origin: { ...origin, secret: true } }, { ...input, origin: { lat: 35.68, lon: 139.69 } }, { ...input, destination: { ...destination, id: '../oops' } }, { ...input, destination: { ...destination, name: '' } }, { ...input, destination: { ...destination, lat: '35.1587' } }]) {
    const result = await request('/api/destination-routes', payload);
    assert.equal(result.status, 400);
  }
});

test('missing routing key stays an explicit configuration error', async () => {
  const result = await request('/api/destination-routes', input, { env: {} });
  assert.equal(result.status, 503);
  assert.ok(result.body.error);
});

test('fractional-minute display values cannot reject an exactly affordable duration in raw seconds', async () => {
  const result = await request('/api/destination-routes', input, routeFetch({ fastSeconds: 244, scenicSeconds: 2044 }));
  assert.equal(result.status, 200);
  assert.equal(result.body.scenicStatus, 'ok');
  assert.equal(result.body.routes[1].durationMin, 2044 / 60);
});

test('candidate filter excludes backtracking, beyond destination, hiking, existing path and out-of-corridor places', async () => {
  const { selectScenicCandidates } = await import('../server/destination-routes.mjs');
  const place = (id, lat, lon, title = '해안 풍경') => ({ id, title, lat, lon });
  const result = selectScenicCandidates({ origin: { lat: 35.1, lon: 129 }, destination: { lat: 35.1, lon: 129.2 }, theme: 'coast', fast: { distanceKm: 20, coordinates: [[35.1, 129], [35.1, 129.1], [35.1, 129.2]] } }, { places: [
    place('good', 35.11, 129.1), place('behind', 35.101, 128.99), place('beyond', 35.101, 129.21),
    place('far', 35.16, 129.1), place('on-road', 35.1, 129.08), place('destination', 35.1, 129.199),
    place('hiking', 35.11, 129.08, '해안 등산 탐방로'), place('good', 35.11, 129.1),
    place('forest', 35.11, 129.13, '푸른숲 정원'),
  ] });
  assert.deepEqual(result.map(place => place.id), ['good']);
});

test('amusement, commercial and bare garden places are never offered as a scenic stop', async () => {
  const { selectScenicCandidates } = await import('../server/destination-routes.mjs');
  const trip = { origin: { lat: 35.1, lon: 129 }, destination: { lat: 35.1, lon: 129.2 }, theme: 'all', fast: { distanceKm: 20, coordinates: [[35.1, 129], [35.1, 129.1], [35.1, 129.2]] } };
  const at = (id, title) => ({ id, title, lat: 35.11, lon: 129.1 });
  for (const title of ['마이돈촌테마공원', '과일나라 테마파크', '푸른숲 유원지', '숲속 리조트', '바다 카페', '마이정원', '쌍류포도정원협동조합']) {
    assert.deepEqual(selectScenicCandidates(trip, { places: [at('x', title)] }).map(place => place.id), [], title);
  }
  for (const title of ['푸른 해안도로', '순천만국가정원', '호반 전망대', '메타세쿼이아 가로수길']) {
    assert.equal(selectScenicCandidates(trip, { places: [at('ok', title)] }).length, 1, title);
  }
});

test('an offline OSM grid must also find the title scene beside the place', async () => {
  const { selectScenicCandidates } = await import('../server/destination-routes.mjs');
  const grid = { LON0: 129, LAT0: 35, dLon: 0.003, dLat: 0.00225, W: 200, H: 120, cells: new Uint8Array(200 * 120) };
  const trip = { origin: { lat: 35.1, lon: 129 }, destination: { lat: 35.1, lon: 129.2 }, theme: 'all', fast: { distanceKm: 20, coordinates: [[35.1, 129], [35.1, 129.1], [35.1, 129.2]] } };
  const place = { id: 'sea', title: '푸른 바다', lat: 35.11, lon: 129.1 };
  assert.equal(selectScenicCandidates(trip, { places: [place] }, null).length, 1, 'no grid: the title decides');
  assert.equal(selectScenicCandidates(trip, { places: [place] }, grid).length, 0, 'a coast name with no coast around it is dropped');
  const cx = Math.floor((place.lon - grid.LON0) / grid.dLon), cy = Math.floor((place.lat - grid.LAT0) / grid.dLat);
  grid.cells[cy * grid.W + cx] = 1;
  const [kept] = selectScenicCandidates(trip, { places: [place] }, grid);
  assert.equal(kept.id, 'sea');
  const plain = selectScenicCandidates(trip, { places: [place] }, null)[0];
  assert.ok(kept.value > plain.value, 'a scene found in the grid scores higher than a title alone');
  // Scenes the grid cannot judge keep the title hint.
  assert.equal(selectScenicCandidates({ ...trip, theme: 'countryside' }, { places: [{ id: 'f', title: '청보리밭', lat: 35.11, lon: 129.1 }] }, grid).length, 1);
});
test('route corridor follows real bends instead of accepting everything near the origin-destination line', async () => {
  const { selectScenicCandidates } = await import('../server/destination-routes.mjs');
  const result = selectScenicCandidates({ origin: { lat: 35.1, lon: 129 }, destination: { lat: 35.1, lon: 129.2 }, theme: 'forest', fast: { distanceKm: 35, coordinates: [[35.1, 129], [35.17, 129.1], [35.1, 129.2]] } }, { places: [
    { id: 'wrong-corridor', title: '푸른숲', lat: 35.09, lon: 129.1 },
    { id: 'right-corridor', title: '초록숲길', lat: 35.16, lon: 129.1 },
  ] });
  assert.deepEqual(result.map(place => place.id), ['right-corridor']);
});

test('resampled baseline geometry cannot count as a new scenery route merely by adding vertices', async () => {
  const { planDestinationRoutes } = await import('../server/destination-routes.mjs');
  const route = [[35.1, 129], [35.1, 129.1], [35.1, 129.2]];
  const result = await planDestinationRoutes({ origin: { lat: 35.1, lon: 129 }, destination: { id: 'fixed', name: '목적지', lat: 35.1, lon: 129.2 }, extraMinutes: 30, theme: 'coast' }, {
    env, catalog: { places: [{ id: 'nearby-coast', title: '해안 풍경', lat: 35.102, lon: 129.1 }] },
    fetchImpl: async url => road(new URL(url).searchParams.has('waypoints') ? [route[0], [35.1, 129.05], ...route.slice(1)] : route),
  });
  assert.equal(result.scenicStatus, 'no_feasible_route');
  assert.deepEqual(result.routes.map(route => route.id), ['fast']);
});

test('control characters in destination text are rejected before the native result can become unreadable', async () => {
  const result = await request('/api/destination-routes', { ...input, destination: { ...destination, name: '해운대\u0000해수욕장' } });
  assert.equal(result.status, 400);
});

test('a valid pair of endpoints cannot hide foreign or impossible intermediate road geometry', async () => {
  for (const coordinates of [[baseline[0], [0, 0], baseline.at(-1)], [baseline[0], [35.7, 129.1], baseline.at(-1)]]) {
    const result = await request('/api/destination-routes', { ...input, extraMinutes: 0 }, routeFetch({ fastCoordinates: coordinates }));
    assert.equal(result.status, 502);
    assert.ok(!Object.hasOwn(result.body, 'routes'));
  }
});

const scenicFixtures = [
  { theme: 'coast', label: '바다·해안', title: '해안 풍경' },
  { theme: 'forest', label: '숲·나무길', title: '담양 메타세쿼이아길' },
  { theme: 'mountain', label: '산·전망', title: '말티재 전망대(말티고개)' },
  { theme: 'water', label: '강·호수', title: '대아저수지(대아호)' },
  { theme: 'countryside', label: '들판·농촌', title: '대관령 하늘목장' },
  { theme: 'city', label: '도시 풍경', title: '봉산문화거리' },
];
const scenePoint = (title, id = 'scene') => ({ id, title, lat: 35.11, lon: 129.1 });
const sceneTrip = {
  origin: { lat: 35.1, lon: 129 },
  destination: { id: 'fixed', name: '선택한 목적지', lat: 35.1, lon: 129.2 },
  extraMinutes: 30,
};
const sceneBaseline = [[35.1, 129], [35.1, 129.1], [35.1, 129.2]];
function sceneCandidates(theme, titles) {
  return selectScenicCandidates({ ...sceneTrip, theme, fast: { distanceKm: 20, coordinates: sceneBaseline } }, {
    places: titles.map((title, i) => scenePoint(title, `scene-${i}`)),
  });
}
function sceneRouting() {
  const calls = [];
  return { calls, fetchImpl: async url => {
    const target = new URL(url); calls.push(target);
    if (!target.searchParams.has('waypoints')) return road(sceneBaseline, 1200, 20000);
    const [lon, lat] = target.searchParams.get('waypoints').split(',').map(Number);
    return road([sceneBaseline[0], [lat, lon], sceneBaseline.at(-1)], 1800, 24000);
  } };
}

for (const { theme, label, title } of scenicFixtures) {
  test(`${theme} returns its selected scenery label on one verified route to the same destination`, async () => {
    const mock = sceneRouting();
    const result = await planDestinationRoutes({ ...sceneTrip, theme }, { ...mock, env, catalog: { places: [scenePoint(title)] } });
    assert.equal(result.scenicStatus, 'ok');
    assert.deepEqual(result.destination, sceneTrip.destination);
    assert.deepEqual(result.routes.map(route => route.id), ['fast', 'scenic']);
    const scenic = result.routes[1];
    assert.ok(scenic.scenicLabels.includes(label));
    assert.ok(scenic.scenicLabels.includes(title));
    assert.equal(scenic.waypoints.length, 1);
    assert.equal(scenic.waypoints[0].name, title);
    assert.deepEqual(scenic.coordinates.at(-1), sceneBaseline.at(-1));
    assert.ok(scenic.durationMin <= result.routes[0].durationMin + sceneTrip.extraMinutes);
    assert.equal(mock.calls.length, 2);
    assert.equal(mock.calls[1].searchParams.get('destination'), '129.2,35.1');
    assert.equal(mock.calls[1].searchParams.get('avoid'), 'motorway|ferries');
  });

  test(`${theme} does not substitute another scenery when its candidates are absent`, async () => {
    const mock = sceneRouting();
    const other = scenicFixtures.find(fixture => fixture.theme !== theme);
    const result = await planDestinationRoutes({ ...sceneTrip, theme }, { ...mock, env, catalog: { places: [scenePoint(other.title)] } });
    assert.equal(result.scenicStatus, 'no_candidates');
    assert.deepEqual(result.routes.map(route => route.id), ['fast']);
    assert.ok(result.message.includes(label));
    assert.equal(mock.calls.length, 1);
  });
}

test('all admits the natural and rural categories but not city views, and stops at the same place never become two waypoints', async () => {
  for (const { title } of scenicFixtures) assert.equal(sceneCandidates('all', [title]).length, sceneCandidates('city', [title]).length ? 0 : 1, title);
  assert.ok(scenicFixtures.some(({ title }) => sceneCandidates('city', [title]).length === 1));
  const candidates = sceneCandidates('all', scenicFixtures.map(fixture => fixture.title));
  assert.equal(candidates.length, 3);
  const result = await planDestinationRoutes({ ...sceneTrip, theme: 'all' }, {
    ...sceneRouting(), env, catalog: { places: scenicFixtures.map((fixture, i) => scenePoint(fixture.title, `theme-${i}`)) },
  });
  assert.equal(result.scenicStatus, 'ok');
  assert.deepEqual(result.routes.map(route => route.id), ['fast', 'scenic']);
  assert.equal(result.routes[1].waypoints.length, 1);
});

test('river parks support both water and city while preserving the user-selected label', async () => {
  for (const [theme, label] of [['water', '강·호수'], ['city', '도시 풍경']]) {
    const result = await planDestinationRoutes({ ...sceneTrip, theme }, {
      ...sceneRouting(), env, catalog: { places: [scenePoint('반포한강공원')] },
    });
    assert.equal(result.scenicStatus, 'ok');
    assert.equal(result.routes[1].scenicLabels[0], label);
  }
});

test('title classification uses explicit landscape words instead of syllables in city names', () => {
  for (const title of ['부산역', '울산광역시청', '강남역', '강릉시청', '논현포대', '도곡재', '삼산건강공원', '새밭계곡']) {
    assert.deepEqual(sceneCandidates('all', [title]), [], title);
  }
  assert.equal(sceneCandidates('forest', ['한밭수목원']).length, 1);
  assert.deepEqual(sceneCandidates('countryside', ['한밭수목원']), []);
  assert.equal(sceneCandidates('mountain', ['단양 보발재']).length, 1);
  assert.equal(sceneCandidates('countryside', ['서광차밭']).length, 1);
});

test('named rivers, lakes and city landmarks do not classify similarly named buildings or valleys', () => {
  for (const title of ['금강굴', '금강암(부산)', '금강사(강진)', '금강대도', '금강정사', '금강수목원', '금강소나무생태경영림']) {
    assert.deepEqual(sceneCandidates('water', [title]), [], title);
  }
  for (const title of ['금강', '금강하구둑(군산)', '대청호', '광주호', '간월호 쉼터공원', '태화강 국가정원']) {
    assert.equal(sceneCandidates('water', [title]).length, 1, title);
  }
  assert.equal(sceneCandidates('city', ['광안대교']).length, 1);
  assert.equal(sceneCandidates('city', ['우암동 도시숲']).length, 1);
  assert.equal(sceneCandidates('countryside', ['경산 하양경관농업단지']).length, 1);
  assert.deepEqual(sceneCandidates('city', ['대교천 현무암 협곡']), []);
  assert.deepEqual(sceneCandidates('countryside', ['말목장터와 감나무', '신풍 신천 바다목장']), []);
});

test('explicit coastal and urban observation points retain their scenery instead of implying mountains', () => {
  for (const [title, theme] of [['해안 전망대', 'coast'], ['대포항 전망대', 'coast'], ['울산대교 전망대', 'city'], ['에코델타시티 전망대', 'city']]) {
    assert.equal(sceneCandidates(theme, [title]).length, 1, title);
    assert.deepEqual(sceneCandidates('mountain', [title]), [], title);
  }
  assert.equal(sceneCandidates('mountain', ['군위 화산산성 전망대']).length, 1);
});

test('slow-city names alone cannot supply an urban scene while explicit city scenery remains eligible', () => {
  for (const title of ['대흥슬로시티', '경북 상주 함창·이안·공검면 [슬로시티]',
    '경북 청송 주왕산면·파천면 [슬로시티]', '강원 영월 김삿갓면 [슬로시티]']) {
    assert.deepEqual(sceneCandidates('city', [title]), [], title);
  }
  assert.equal(sceneCandidates('city', ['에코델타시티 전망대']).length, 1);
  assert.equal(sceneCandidates('city', ['슬로시티 문화거리']).length, 1);
});

test('the catalog harbor plaza supports coast and city without treating 포항 as a harbor keyword', () => {
  for (const theme of ['coast', 'city']) {
    assert.equal(sceneCandidates(theme, ['궁평항광장']).length, 1, theme);
  }
  assert.deepEqual(sceneCandidates('coast', ['포항광장']), []);
  assert.equal(sceneCandidates('city', ['포항광장']).length, 1);
});

test('Oncheoncheon river park is water and city scenery without admitting hot-spring facilities', () => {
  for (const theme of ['water', 'city']) {
    assert.equal(sceneCandidates(theme, ['온천천시민공원']).length, 1, theme);
  }
  for (const title of ['도심 온천', '온천천 온천시설', '온천천 스파']) {
    assert.deepEqual(sceneCandidates('all', [title]), [], title);
  }
});

const twoScenes = [
  { id: 'a-first', title: '해안 풍경 하나', lat: 35.11, lon: 129.05 },
  { id: 'b-second', title: '해안 풍경 둘', lat: 35.11, lon: 129.15 },
];
// One stop is a short detour for 129.15 and a long one for 129.05; a pair passes both.
function twoStopRouting({ pairSeconds = 2400, pairCode = 0, failPair = false } = {}) {
  const calls = [];
  const singleSeconds = { '129.05': 2400, '129.15': 1700 };
  return { calls, fetchImpl: async url => {
    const target = new URL(url); calls.push(target);
    const waypoints = target.searchParams.get('waypoints');
    if (!waypoints) return road(sceneBaseline, 1200, 20000);
    const stops = waypoints.split('|').map(stop => stop.split(',').map(Number));
    const via = stops.map(([lon, lat]) => [lat, lon]);
    if (stops.length === 2) {
      if (failPair) throw new Error('private-test-key');
      return road([sceneBaseline[0], ...via, sceneBaseline.at(-1)], pairSeconds, 26000, pairCode);
    }
    return road([sceneBaseline[0], ...via, sceneBaseline.at(-1)], singleSeconds[String(stops[0][0])], 24000);
  } };
}

test('two well-separated scenes become one ordered two-stop route within the extra time', async () => {
  const mock = twoStopRouting();
  const result = await planDestinationRoutes({ ...sceneTrip, theme: 'coast' }, { ...mock, env, catalog: { places: twoScenes } });
  assert.equal(result.scenicStatus, 'ok');
  assert.deepEqual(result.routes.map(route => route.id), ['fast', 'scenic']);
  const scenic = result.routes[1];
  assert.deepEqual(scenic.waypoints.map(stop => stop.id), ['a-first', 'b-second']);
  assert.equal(scenic.title, '풍경 경로 · 2곳 경유');
  assert.ok(scenic.durationMin * 60 <= 1200 + sceneTrip.extraMinutes * 60);
  assert.equal(mock.calls.length, 4);
  assert.equal(mock.calls.at(-1).searchParams.get('waypoints').split('|').length, 2);
  assert.equal(mock.calls.at(-1).searchParams.get('avoid'), 'motorway|ferries');
  assert.ok(scenic.scenicLabels.includes('해안 풍경 하나') && scenic.scenicLabels.includes('해안 풍경 둘'));
  assert.equal(new Set(scenic.scenicLabels).size, scenic.scenicLabels.length);
  assert.ok(scenic.scenicLabels.length <= 8);
  assert.deepEqual(scenic.coordinates.at(-1), sceneBaseline.at(-1));
});

test('a pair that is over budget, unroutable or fails keeps the single stop with the best value for its extra time', async () => {
  for (const options of [{ pairSeconds: 3300 }, { pairCode: 104 }, { failPair: true }]) {
    const mock = twoStopRouting(options);
    const result = await planDestinationRoutes({ ...sceneTrip, theme: 'coast' }, { ...mock, env, catalog: { places: twoScenes } });
    assert.equal(result.scenicStatus, 'ok', JSON.stringify(options));
    const scenic = result.routes[1];
    // 129.15 costs 500 extra seconds against 1200 for 129.05, so it wins although it is not listed first.
    assert.deepEqual(scenic.waypoints.map(stop => stop.id), ['b-second'], JSON.stringify(options));
    assert.ok(!JSON.stringify(result).includes('private-test-key'));
  }
});

test('a motorway-free road over the budget is retried once with motorways allowed and says so', async () => {
  const calls = [];
  const fetchImpl = async url => {
    const target = new URL(url); calls.push(target);
    if (!target.searchParams.has('waypoints')) return road(sceneBaseline, 1200, 20000);
    const [lon, lat] = target.searchParams.get('waypoints').split(',').map(Number);
    const avoidsMotorway = target.searchParams.get('avoid').includes('motorway');
    return road([sceneBaseline[0], [lat, lon], sceneBaseline.at(-1)], avoidsMotorway ? 3500 : 1900, 24000);
  };
  const result = await planDestinationRoutes({ ...sceneTrip, theme: 'coast' }, { fetchImpl, env, catalog: { places: [scenePoint('해안 풍경')] } });
  assert.equal(result.scenicStatus, 'ok');
  assert.equal(calls.length, 3);
  assert.equal(calls[1].searchParams.get('avoid'), 'motorway|ferries');
  assert.equal(calls[2].searchParams.get('avoid'), 'ferries');
  const scenic = result.routes[1];
  assert.equal(scenic.durationMin * 60, 1900);
  assert.ok(scenic.reason.includes('자동차 전용도로를 포함'));
  assert.ok(!Object.hasOwn(scenic, 'motorway') && !Object.hasOwn(scenic, 'durationSeconds'));
});

test('a road within the budget is never retried and does not mention motorways', async () => {
  const mock = twoStopRouting({ pairSeconds: 2400 });
  const result = await planDestinationRoutes({ ...sceneTrip, theme: 'coast' }, { ...mock, env, catalog: { places: [twoScenes[1]] } });
  assert.equal(mock.calls.length, 2);
  assert.ok(!result.routes[1].reason.includes('자동차 전용도로'), 'only a road that needed motorways says so');
});

test('nearby scenes are never paired into two stops', async () => {
  const mock = twoStopRouting();
  const nearby = [twoScenes[0], { ...twoScenes[1], lon: 129.06 }];
  const result = await planDestinationRoutes({ ...sceneTrip, theme: 'coast' }, { ...mock, env, catalog: { places: nearby } });
  assert.equal(result.scenicStatus, 'ok');
  assert.equal(result.routes[1].waypoints.length, 1);
  assert.equal(mock.calls.length, 3);
});

test('a place with several scenery tags outranks a single-tag place at the same position for all', () => {
  const [first] = selectScenicCandidates({ ...sceneTrip, theme: 'all', fast: { distanceKm: 20, coordinates: sceneBaseline } }, {
    places: [scenePoint('해안 풍경', 'a-plain'), scenePoint('해안 숲길', 'z-rich')],
  });
  assert.equal(first.id, 'z-rich');
});

test('scenery keywords cannot admit indoor, hiking-only or offshore attractions', () => {
  for (const title of ['해안 등산 탐방로', '숲 둘레길', '산 정상 전망대', '해안 스카이워크', '호수 출렁다리',
    '강변스파랜드', '한강 박물관', '산악전시관', '바다 아쿠아리움', '도시 홍보관', '숲 캠핑장',
    '해상 전망대', '무인도 해안', '가파도 소망전망대', '우도 해안도로', '해안 도서관', '호수 천문대', '아라타워 전망대', '숲 키즈카페']) {
    assert.deepEqual(sceneCandidates('all', [title]), [], title);
  }
});
