import test from 'node:test';
import assert from 'node:assert/strict';
import { Readable } from 'node:stream';
import { createServer } from '../server/index.mjs';
import { playlists } from '../server/music-catalog.mjs';

let requestId = 0;
function request(server, url, { method = 'GET', chunks = [], contentType = 'application/json' } = {}) {
  return new Promise((resolve) => {
    const req = Readable.from(chunks);
    Object.assign(req, { url, method, headers: { host: '127.0.0.1:4173', 'content-type': contentType }, socket: { remoteAddress: `input-${requestId++}` } });
    server.emit('request', req, { setHeader() {}, writeHead(status) { this.status = status; }, end(body) { resolve({ status: this.status, body: JSON.parse(String(body)) }); } });
  });
}
const post = (server, url, value) => request(server, url, { method: 'POST', chunks: [Buffer.from(JSON.stringify(value))] });

test('Korean JSON text survives UTF-8 characters split across request chunks', async () => {
  const context = { title: '춘천 호수 드라이브', theme: 'lake', durationMinutes: 90 };
  let received;
  const server = createServer({ env: { OPENAI_API_KEY: 'test-key' }, fetchImpl: async (_url, options) => {
    received = JSON.parse(JSON.parse(options.body).input[0].content).context;
    return Response.json({ status: 'completed', output: [{ type: 'message', role: 'assistant', content: [{ type: 'output_text', text: JSON.stringify({ playlistId: playlists[0].id, reason: '호수와 어울립니다.' }) }] }] });
  } });
  const bytes = Buffer.from(JSON.stringify({ context }));
  const split = bytes.indexOf(Buffer.from('춘')) + 1;
  const result = await request(server, '/api/android/music', { method: 'POST', chunks: [bytes.subarray(0, split), bytes.subarray(split)] });
  assert.equal(result.status, 200);
  assert.deepEqual(received, context);
});

test('route inputs with missing objects return client errors before provider access', async () => {
  let calls = 0;
  const server = createServer({ env: { KAKAO_REST_API_KEY: 'test-key' }, fetchImpl: async () => { calls++; throw new Error('must not call'); } });
  for (const value of [null, { stops: [null, { lat: 37, lon: 127 }] }, { stops: [{ lat: 37, lon: 127 }, null] }]) {
    assert.equal((await post(server, '/api/route', value)).status, 400);
  }
  assert.equal(calls, 0);
});

test('blank coordinate or radius query values are rejected before tourism access', async () => {
  let calls = 0;
  const server = createServer({ env: { TOUR_API_KEY: 'test-key' }, fetchImpl: async () => {
    calls++;
    return Response.json({ response: { header: { resultCode: '0000' }, body: { items: '' } } });
  } });
  for (const url of ['/api/places?lat=&lon=127', '/api/places?lat=37&lon=%20', '/api/places?lat=37&lon=127&radiusMeters=']) {
    assert.equal((await request(server, url)).status, 400);
  }
  assert.equal(calls, 0);
});

test('malformed Kakao route containers return upstream errors', async () => {
  for (const sections of [{}, [null], [{ roads: {} }], [{ roads: [null] }]]) {
    const server = createServer({ env: { KAKAO_REST_API_KEY: 'test-key' }, fetchImpl: async () => Response.json({ routes: [{ result_code: 0, summary: { distance: 1000, duration: 100 }, sections }] }) });
    assert.equal((await post(server, '/api/route', { stops: [{ lat: 37, lon: 127 }, { lat: 38, lon: 128 }] })).status, 502);
  }
});

test('malformed TourAPI success envelopes do not masquerade as empty results', async () => {
  for (const body of [undefined, null, [], { items: { item: 'invalid' } }]) {
    const server = createServer({ env: { TOUR_API_KEY: 'test-key' }, fetchImpl: async () => Response.json({ response: { header: { resultCode: '0000' }, body } }) });
    assert.equal((await request(server, '/api/places?lat=37&lon=127')).status, 502);
  }
});

test('the JSON request limit counts incoming bytes across chunks', async () => {
  let calls = 0;
  const server = createServer({ env: { KAKAO_REST_API_KEY: 'test-key' }, fetchImpl: async () => { calls++; throw new Error('must not call'); } });
  const result = await request(server, '/api/route', { method: 'POST', chunks: [Buffer.alloc(8192, ' '), Buffer.alloc(8193, ' ')] });
  assert.equal(result.status, 413);
  assert.equal(calls, 0);
});
