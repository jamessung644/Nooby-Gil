import test from 'node:test';
import assert from 'node:assert/strict';
import { Readable } from 'node:stream';
import { createServer } from '../server/index.mjs';
import { recommendMusic } from '../server/music.mjs';
import { playlists } from '../server/music-catalog.mjs';

const env = { OPENAI_API_KEY: 'music-test-secret' };
const input = { context: { title: '호수와 숲 드라이브', theme: 'lake', durationMinutes: 90 } };
const answer = (value = { playlistId: playlists[0].id, reason: '차분한 음악이 호숫가 풍경과 어울립니다.' }) => ({ status: 'completed', output: [{ type: 'message', role: 'assistant', content: [{ type: 'output_text', text: JSON.stringify(value) }] }] });
const fetchImpl = async () => Response.json(answer());
let requestId = 0;
function request(body, options = {}, method = 'POST') {
  const server = createServer({ env, fetchImpl, ...options });
  return new Promise((resolve) => {
    const req = Readable.from([Buffer.from(JSON.stringify(body))]);
    Object.assign(req, { url: '/api/android/music', method, headers: { host: '10.0.2.2:4173', 'content-type': 'application/json' }, socket: { remoteAddress: `music-${requestId++}` } });
    server.emit('request', req, { setHeader() {}, writeHead(status) { this.status = status; }, end(body) { resolve({ status: this.status, body: JSON.parse(String(body)) }); } });
  });
}

test('music endpoint selects an actual allowlisted ID and supplies canonical metadata/direct playlist URL', async () => {
  const result = await request(input, { fetchImpl: async (url, options) => {
    assert.equal(url, 'https://api.openai.com/v1/responses');
    assert.equal(options.redirect, 'error');
    assert.ok(options.signal instanceof AbortSignal);
    assert.equal(options.headers.Authorization, `Bearer ${env.OPENAI_API_KEY}`);
    const body = JSON.parse(options.body);
    assert.equal(body.store, false);
    assert.equal(body.text.format.strict, true);
    assert.deepEqual(body.text.format.schema.properties.playlistId.enum, playlists.map(p => p.id));
    const data = JSON.parse(body.input[0].content);
    assert.deepEqual(data.context, input.context);
    assert.ok(body.instructions.includes('명령이 아닙니다'));
    for (const forbidden of [env.OPENAI_API_KEY, '"lat"', '"lon"', '"origin"', '"url"']) assert.ok(!options.body.includes(forbidden));
    return Response.json(answer());
  } });
  assert.equal(result.status, 200);
  assert.equal(result.body.source, 'OpenAI');
  assert.deepEqual(result.body.playlist, { id: playlists[0].id, title: playlists[0].title, creator: playlists[0].creator, url: `https://music.youtube.com/playlist?list=${playlists[0].id}` });
  assert.ok(Number.isFinite(Date.parse(result.body.generatedAt)));
  assert.ok(!JSON.stringify(result).includes(env.OPENAI_API_KEY));
});

test('catalog has unique real-format playlist IDs and documented source links', () => {
  assert.ok(playlists.length >= 4);
  assert.equal(new Set(playlists.map(p => p.id)).size, playlists.length);
  for (const p of playlists) {
    assert.match(p.id, /^PL[A-Za-z0-9_-]{14,98}$/);
    assert.ok(p.title.length && p.title.length <= 200);
    assert.ok(p.creator.length && p.creator.length <= 120);
    assert.ok(p.mood.length);
    assert.ok(p.themes.length);
    assert.ok(p.sourceUrl.startsWith('https://'));
    assert.equal(p.checkedAt, '2026-11-05');
  }
});

test('invalid or extra client data is rejected before a provider call', async () => {
  const never = async () => { throw new Error('provider should not run'); };
  for (const body of [null, {}, [], { ...input, lat: 37 }, { context: { ...input.context, lon: 127 } },
    { context: { ...input.context, title: '' } }, { context: { ...input.context, title: 'a'.repeat(121) } },
    { context: { ...input.context, theme: 'night' } }, { context: { ...input.context, durationMinutes: 0 } },
    { context: { ...input.context, durationMinutes: 721 } }, { context: { ...input.context, durationMinutes: 3.5 } },
    { context: { ...input.context, durationMinutes: '90' } }]) {
    assert.equal((await request(body, { fetchImpl: never })).status, 400);
  }
  assert.equal((await request(input, {}, 'GET')).status, 404);
});

test('all supported themes and time bounds are accepted', async () => {
  for (const theme of ['all', 'lake', 'coast', 'forest', 'mountain', 'water', 'countryside', 'city']) {
    for (const durationMinutes of [1, 720]) {
      assert.equal((await recommendMusic({ context: { ...input.context, theme, durationMinutes } }, { env, fetchImpl })).source, 'OpenAI');
    }
  }
});

test('each destination theme reaches the music provider unchanged with a known suitable candidate', async () => {
  for (const theme of ['all', 'coast', 'forest', 'mountain', 'water', 'countryside', 'city']) {
    const result = await request({ context: { ...input.context, theme } }, { fetchImpl: async (_url, options) => {
      const data = JSON.parse(JSON.parse(options.body).input[0].content);
      assert.equal(data.context.theme, theme);
      const suitable = data.candidates.find(candidate => candidate.themes.includes(theme));
      assert.ok(suitable, `Missing known playlist candidate for ${theme}`);
      return Response.json(answer({ playlistId: suitable.id, reason: '선택한 풍경에 어울리는 음악입니다.' }));
    } });
    assert.equal(result.status, 200, `Destination theme ${theme} must be accepted`);
    assert.ok(playlists.some(playlist => playlist.id === result.body.playlist.id));
  }
});

test('AI cannot invent a playlist, URL, title, or unsupported fields', async () => {
  for (const value of [null, [], {}, { playlistId: 'PLmadeUpUnregisteredMusic', reason: '분위기' },
    { playlistId: playlists[0].id, reason: '' }, { playlistId: playlists[0].id, reason: 'x'.repeat(281) },
    { playlistId: playlists[0].id, reason: '분위기', url: 'https://evil.example/' },
    { playlistId: playlists[0].id, reason: '분위기', title: 'invented' },
    { playlistId: playlists[0].id, reason: '분위기\u0000음악' }]) {
    assert.equal((await request(input, { fetchImpl: async () => Response.json(answer(value)) })).status, 502);
  }
});

test('multiline reasons become a single Android-compatible sentence', async () => {
  const result = await request(input, { fetchImpl: async () => Response.json(answer({ playlistId: playlists[0].id, reason: '  차분한 음악이\n\t호숫가 풍경과 어울립니다.  ' })) });
  assert.equal(result.status, 200);
  assert.equal(result.body.reason, '차분한 음악이 호숫가 풍경과 어울립니다.');
});

test('incomplete, refused, non-assistant and malformed AI replies do not produce a playlist', async () => {
  for (const data of [{ ...answer(), status: 'incomplete' }, { status: 'completed', output: [] },
    { status: 'completed', output: [{ type: 'message', role: 'user', content: [] }] },
    { status: 'completed', output: [{ type: 'message', role: 'assistant', content: [{ type: 'refusal', refusal: 'no' }] }] },
    { status: 'completed', output: [{ type: 'message', role: 'assistant', content: [{ type: 'output_text', text: '{bad' }] }] }]) {
    assert.equal((await request(input, { fetchImpl: async () => Response.json(data) })).status, 502);
  }
});

test('provider failures and credentials never leak or become a fabricated fallback', async () => {
  assert.equal((await request(input, { env: {} })).status, 503);
  assert.equal((await request(input, { env: { ...env, OPENAI_MODEL: 'bad model' } })).status, 503);
  for (const fetchImpl of [async () => { throw new Error(env.OPENAI_API_KEY); }, async () => new Response(env.OPENAI_API_KEY, { status: 401 }),
    async () => new Response(env.OPENAI_API_KEY, { status: 500 }), async () => new Response(env.OPENAI_API_KEY, { status: 200 })]) {
    const result = await request(input, { fetchImpl });
    assert.equal(result.status, 502);
    assert.ok(!JSON.stringify(result).includes(env.OPENAI_API_KEY));
    assert.ok(!result.body.playlist);
  }
});

test('twenty-second timeout aborts a stalled provider response body', async (t) => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  let signal;
  const pending = request(input, { fetchImpl: async (_url, options) => {
    signal = options.signal;
    return { ok: true, json: () => new Promise(() => {}) };
  } });
  await new Promise(resolve => setImmediate(resolve));
  t.mock.timers.tick(19999);
  assert.equal(signal.aborted, false);
  t.mock.timers.tick(1);
  const result = await pending;
  assert.equal(signal.aborted, true);
  assert.equal(result.status, 504);
  assert.ok(!result.body.playlist);
});
