import test from 'node:test';
import assert from 'node:assert/strict';
import { recommendWithAI, recommendAndroidWithAI } from '../server/ai.mjs';
import { getPreparedCatalog, getAndroidRoutes } from '../server/android-catalog.mjs';
import { ApiError } from '../server/providers.mjs';

const env = { OPENAI_API_KEY: 'test-secret-not-for-the-browser' };
const input = { routeIds: ['chuncheon-lake', 'chuncheon-soyang'], preferences: { region: '춘천', theme: 'lake', maxMinutes: 90, query: '' } };
const recommendation = { routeId: 'chuncheon-lake', reason: '호수 풍경을 선호하는 취향과 90분 이내 조건에 어울리는 시연 코스입니다.' };
const response = (recommendations = [recommendation]) => new Response(JSON.stringify({
  id: 'resp_test', object: 'response', status: 'completed', model: 'gpt-6-luna',
  output: [{ id: 'msg_test', type: 'message', role: 'assistant', status: 'completed', content: [{ type: 'output_text', text: JSON.stringify({ recommendations }), annotations: [] }] }],
}), { status: 200, headers: { 'content-type': 'application/json' } });
const rejectStatus = (status) => (error) => error instanceof ApiError && error.status === status;
const noFetch = () => { throw new Error('Unexpected external call'); };

test('missing OpenAI key reports configuration error without contacting upstream', async () => {
  await assert.rejects(recommendWithAI(input, { env: {}, fetchImpl: noFetch }), (error) => rejectStatus(503)(error) && /OPENAI_API_KEY/.test(error.message));
});

test('ranking uses authenticated structured REST with only public fixture metadata and selected preferences', async () => {
  let request;
  const result = await recommendWithAI({ ...input, privateImage: 'private-image', gps: [37, 127] }, { env, fetchImpl: async (url, options) => {
    request = { url, options, body: JSON.parse(options.body) };
    return response();
  } });
  assert.equal(request.url, 'https://api.openai.com/v1/responses');
  assert.equal(request.options.method, 'POST');
  assert.equal(request.options.headers.Authorization, `Bearer ${env.OPENAI_API_KEY}`);
  assert.equal(request.options.headers['Content-Type'], 'application/json');
  assert.equal(request.options.redirect, 'error');
  assert.ok(request.options.signal instanceof AbortSignal);
  assert.deepEqual(Object.keys(request.body).sort(), ['input', 'instructions', 'max_output_tokens', 'model', 'reasoning', 'store', 'text']);
  assert.equal(request.body.store, false);
  assert.equal(request.body.model, 'gpt-6-luna');
  assert.equal(request.body.reasoning.effort, 'low');
  assert.equal(request.body.text.format.type, 'json_schema');
  assert.equal(request.body.text.format.strict, true);
  assert.equal(request.body.text.format.schema.additionalProperties, false);
  assert.deepEqual(request.body.text.format.schema.properties.recommendations.items.properties.routeId.enum, ['chuncheon-lake', 'chuncheon-soyang']);
  const data = JSON.parse(request.body.input[0].content);
  assert.deepEqual(data.preferences, input.preferences);
  assert.equal(data.candidates[0].routeId, 'chuncheon-lake');
  assert.deepEqual(Object.keys(data.candidates[0]).sort(), ['description', 'distanceKm', 'durationMin', 'region', 'routeId', 'tags', 'theme', 'title']);
  assert.ok(!request.options.body.includes('private-image'));
  assert.ok(!request.options.body.includes('coordinates'));
  assert.ok(!request.options.body.includes(env.OPENAI_API_KEY));
  assert.deepEqual(result.recommendations, [recommendation]);
  assert.equal(result.source, 'OpenAI');
  assert.equal(result.model, 'gpt-6-luna');
  assert.ok(Number.isFinite(Date.parse(result.generatedAt)));
});

test('server rejects unknown, duplicate, empty, or excessive candidate IDs', async () => {
  for (const routeIds of [['unverified-route'], ['chuncheon-lake', 'chuncheon-lake'], [], Array(9).fill('chuncheon-lake'), [3], 'chuncheon-lake']) {
    await assert.rejects(recommendWithAI({ ...input, routeIds }, { env, fetchImpl: noFetch }), rejectStatus(400));
  }
});

test('invalid preference types, bounds, enum values, or extra private fields are rejected', async () => {
  for (const preferences of [null, [], { maxMinutes: '90' }, { maxMinutes: Infinity }, { maxMinutes: 0 }, { maxMinutes: 721 }, { maxMinutes: 1.5 }, { region: 'unlisted' }, { theme: 'mountain' }, { query: [] }, { query: 'a'.repeat(201) }, { gps: [37, 127] }]) {
    await assert.rejects(recommendWithAI({ ...input, preferences }, { env, fetchImpl: noFetch }), rejectStatus(400));
  }
  await assert.rejects(recommendWithAI(null, { env, fetchImpl: noFetch }), rejectStatus(400));
});

test('server reapplies budget, region, and normalized search before sending candidates', async () => {
  let sent;
  await recommendWithAI({ routeIds: ['chuncheon-lake', 'chuncheon-soyang', 'seoul-forest'], preferences: { region: '춘천', maxMinutes: 55, query: ' 의 암 호 ' } }, { env, fetchImpl: async (_url, options) => {
    sent = JSON.parse(JSON.parse(options.body).input[0].content);
    return response();
  } });
  assert.deepEqual(sent.candidates.map((candidate) => candidate.routeId), ['chuncheon-lake']);
  for (const preferences of [{ region: '서울' }, { maxMinutes: 34 }, { query: '없는검색어' }]) {
    await assert.rejects(recommendWithAI({ ...input, preferences }, { env, fetchImpl: noFetch }), rejectStatus(400));
  }
});

test('a nonmatching landscape theme remains a preference rather than silently deleting eligible alternatives', async () => {
  const result = await recommendWithAI({ ...input, preferences: { theme: 'forest', maxMinutes: 55 } }, { env, fetchImpl: async () => response() });
  assert.deepEqual(result.recommendations, [recommendation]);
});

test('invalid JSON, incomplete responses, refusal, and absent output_text produce controlled errors', async () => {
  const payloads = [
    '{', '{}',
    JSON.stringify({ status: 'incomplete', output: [{ type: 'message', content: [{ type: 'output_text', text: JSON.stringify({ recommendations: [recommendation] }) }] }] }),
    JSON.stringify({ status: 'completed', output: [{ type: 'message', role: 'assistant', content: [{ type: 'output_text', text: 'not json' }] }] }),
    JSON.stringify({ status: 'completed', output: [{ type: 'message', role: 'assistant', content: [{ type: 'refusal', refusal: 'Cannot comply' }] }] }),
    JSON.stringify({ status: 'completed', output_text: JSON.stringify({ recommendations: [recommendation] }), output: [] }),
  ];
  for (const payload of payloads) {
    await assert.rejects(recommendWithAI(input, { env, fetchImpl: async () => new Response(payload) }), rejectStatus(502));
  }
});

test('model outputs cannot introduce IDs, duplicate candidates, empty rankings, fabricated scores, or unbounded reasons', async () => {
  for (const recommendations of [[{ ...recommendation, routeId: 'invented-road' }], [{ ...recommendation, routeId: 'seoul-forest' }], [recommendation, recommendation], [], [{ ...recommendation, score: 99 }], [{ ...recommendation, reason: '' }], [{ ...recommendation, reason: 'a'.repeat(281) }], [{ ...recommendation, reason: 99 }]]) {
    await assert.rejects(recommendWithAI(input, { env, fetchImpl: async () => response(recommendations) }), rejectStatus(502));
  }
});

test('a returned known route removed by the time budget is still rejected', async () => {
  await assert.rejects(recommendWithAI({ ...input, preferences: { maxMinutes: 55 } }, { env, fetchImpl: async () => response([{ ...recommendation, routeId: 'chuncheon-soyang' }]) }), rejectStatus(502));
});

test('upstream status, network errors, and JSON errors never expose secrets', async () => {
  const fetches = [
    async () => new Response(JSON.stringify({ error: { message: env.OPENAI_API_KEY } }), { status: 403 }),
    async () => { throw new Error(`Network failure: ${env.OPENAI_API_KEY}`); },
    async () => ({ ok: true, json: async () => { throw new Error(env.OPENAI_API_KEY); } }),
  ];
  for (const fetchImpl of fetches) {
    await assert.rejects(recommendWithAI(input, { env, fetchImpl }), (error) => rejectStatus(502)(error) && !error.message.includes(env.OPENAI_API_KEY) && !String(error.stack).includes(env.OPENAI_API_KEY));
  }
});

test('model override is path-safe and returned as provenance', async () => {
  let endpoint;
  const result = await recommendWithAI(input, { env: { ...env, OPENAI_MODEL: 'gpt-6-sol' }, fetchImpl: async (url) => { endpoint = url; return response(); } });
  assert.equal(result.model, 'gpt-6-sol');
  assert.equal(endpoint, 'https://api.openai.com/v1/responses');
  for (const model of ['../../other', 'gpt-x?key=secret', 'https://other.example', 'gpt-']) {
    await assert.rejects(recommendWithAI(input, { env: { ...env, OPENAI_MODEL: model }, fetchImpl: noFetch }), rejectStatus(503));
  }
});

test('REST response parsing tolerates reasoning before a completed assistant message', async () => {
  const payload = await response().json();
  payload.output.unshift({ id: 'rs_test', type: 'reasoning', summary: [] });
  const result = await recommendWithAI(input, { env, fetchImpl: async () => Response.json(payload) });
  assert.deepEqual(result.recommendations, [recommendation]);
});

test('nationwide AI ranks only twelve locally selected canonical IDs without forwarding catalog coordinates', async () => {
  const catalog = getPreparedCatalog();
  const selected = catalog.courses.slice(0, 12);
  assert.equal(selected.length, 12);
  assert.equal(getAndroidRoutes().length, catalog.courses.length + 3);
  let sent;
  const chosen = { routeId: selected[0].id, reason: '선택한 시간 안에서 준비된 관광지 코스를 살펴볼 수 있습니다.' };
  const result = await recommendAndroidWithAI({ routeIds: selected.map((course) => course.id), preferences: { maxMinutes: 720 } }, {
    env,
    fetchImpl: async (_url, options) => {
      const body = JSON.parse(options.body);
      sent = JSON.parse(body.input[0].content);
      assert.deepEqual(body.text.format.schema.properties.recommendations.items.properties.routeId.enum, selected.map((course) => course.id));
      assert.ok(!options.body.includes(env.OPENAI_API_KEY));
      return response([chosen]);
    },
  });
  assert.equal(sent.candidates.length, 12);
  assert.deepEqual(sent.candidates.map((candidate) => candidate.routeId), selected.map((course) => course.id));
  assert.deepEqual(result.recommendations, [chosen]);
  for (const candidate of sent.candidates) {
    assert.deepEqual(Object.keys(candidate).sort(), ['description', 'distanceKm', 'durationMin', 'region', 'routeId', 'tags', 'theme', 'title']);
    assert.ok(!['lat', 'lon', 'centerLat', 'centerLon', 'spots', 'places'].some((field) => Object.hasOwn(candidate, field)));
  }
  assert.equal(sent.candidates[0].durationMin, selected[0].minutes);
  assert.equal(sent.candidates[0].theme, { lake: 'lake', sea: 'coast', night: 'city', forest: 'forest' }[selected[0].theme]);
});

test('nationwide AI rejects thirteen or six hundred candidates, stale IDs, overrides, and raw location before upstream', async () => {
  const courses = getPreparedCatalog().courses;
  const valid = { routeIds: [courses[0].id], preferences: { maxMinutes: 720 } };
  for (const invalid of [
    { ...valid, routeIds: courses.slice(0, 13).map((course) => course.id) },
    { ...valid, routeIds: Array.from({ length: 600 }, (_, index) => courses[index % courses.length].id) },
    { ...valid, routeIds: [courses[0].id, `${courses[0].id}-stale`] },
    { ...valid, routeIds: ['chuncheon-lake'] },
    { ...valid, routeIds: [courses[0].id, courses[0].id] },
    { ...valid, gps: { lat: 37.5665, lon: 126.978 } },
    { ...valid, catalog: [{ id: courses[0].id, minutes: 1 }] },
    { ...valid, candidates: [{ id: courses[0].id, durationMin: 1 }] },
    { ...valid, preferences: { maxMinutes: 720, latitude: 37.5665 } },
  ]) {
    await assert.rejects(recommendAndroidWithAI(invalid, { env, fetchImpl: noFetch }), rejectStatus(400));
  }
});

test('canonical duration budget is enforced before ranking and a filtered or unselected AI ID is rejected', async () => {
  const courses = [...getPreparedCatalog().courses].sort((a, b) => a.minutes - b.minutes);
  const short = courses[0];
  const long = courses.find((course) => course.minutes > short.minutes);
  assert.ok(long, 'Prepared courses must offer more than one duration');
  const scoped = { routeIds: [short.id, long.id], preferences: { maxMinutes: short.minutes } };
  let sent;
  const result = await recommendAndroidWithAI(scoped, { env, fetchImpl: async (_url, options) => {
    sent = JSON.parse(JSON.parse(options.body).input[0].content);
    return response([{ routeId: short.id, reason: '시간 조건에 맞는 준비된 코스입니다.' }]);
  } });
  assert.deepEqual(sent.candidates.map((candidate) => candidate.routeId), [short.id]);
  assert.equal(result.recommendations[0].routeId, short.id);
  for (const routeId of [long.id, courses.find((course) => course.id !== short.id && course.id !== long.id).id]) {
    await assert.rejects(recommendAndroidWithAI(scoped, { env, fetchImpl: async () => response([{ routeId, reason: '예시 이유' }]) }), rejectStatus(502));
  }
  await assert.rejects(recommendAndroidWithAI({ ...scoped, preferences: { maxMinutes: short.minutes - 1 } }, { env, fetchImpl: noFetch }), rejectStatus(400));
});
