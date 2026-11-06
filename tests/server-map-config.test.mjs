import test from 'node:test';
import assert from 'node:assert/strict';
import { Readable } from 'node:stream';
import { createServer } from '../server/index.mjs';

function request(server, { method = 'GET', headers = {}, remoteAddress = '127.0.0.2' } = {}) {
  return new Promise((resolve) => {
    const req = Readable.from([]);
    Object.assign(req, {
      url: '/api/map-config', method,
      headers: { host: '10.0.2.2:4173', ...headers }, socket: { remoteAddress },
    });
    const res = {
      headers: {},
      setHeader(name, value) { this.headers[name] = value; },
      writeHead(status, headers) { this.status = status; Object.assign(this.headers, headers); },
      end(body) { resolve({ status: this.status, headers: this.headers, data: JSON.parse(body) }); },
    };
    server.emit('request', req, res);
  });
}

const noFetch = async () => { throw new Error('Map configuration must not contact an upstream service'); };

test('map configuration exposes only the explicitly public JavaScript key', async () => {
  const server = createServer({ env: {
    KAKAO_MAP_JS_KEY: '  public-map-key  ',
    KAKAO_REST_API_KEY: 'private-rest-secret',
    OPENAI_API_KEY: 'private-openai-secret',
    TOUR_API_KEY: 'private-tour-secret',
    UNRELATED_SECRET: 'private-other-secret',
  }, fetchImpl: noFetch });
  const response = await request(server);
  assert.equal(response.status, 200);
  assert.deepEqual(response.data, { configured: true, javascriptKey: 'public-map-key' });
  assert.equal(response.headers['Cache-Control'], 'no-store');
  assert.equal(response.headers['Content-Type'], 'application/json; charset=utf-8');
});

test('an absent, blank, or non-string JavaScript key reports map setup as unavailable', async () => {
  for (const value of [undefined, '', '  \n\t ', null, 123]) {
    const response = await request(createServer({ env: { KAKAO_MAP_JS_KEY: value }, fetchImpl: noFetch }));
    assert.equal(response.status, 200);
    assert.deepEqual(response.data, { configured: false, javascriptKey: '' });
  }
});

test('map configuration keeps the local host, matching origin, and GET-only policies', async () => {
  const server = createServer({ env: { KAKAO_MAP_JS_KEY: 'public-map-key' }, fetchImpl: noFetch });
  for (const headers of [
    { host: 'evil.invalid' },
    { host: '10.0.2.2.evil.invalid:4173' },
    { origin: 'http://127.0.0.1:4173' },
    { origin: 'https://evil.invalid' },
  ]) assert.equal((await request(server, { headers })).status, 403);
  assert.equal((await request(server, { headers: { origin: 'http://10.0.2.2:4173' } })).status, 200);
  assert.equal((await request(server, { method: 'POST' })).status, 404);
});

test('map configuration consumes the existing per-address API rate limit', async () => {
  const server = createServer({ env: {}, fetchImpl: noFetch });
  for (let index = 0; index < 60; index++) {
    assert.equal((await request(server, { remoteAddress: '127.0.0.3' })).status, 200);
  }
  assert.equal((await request(server, { remoteAddress: '127.0.0.3' })).status, 429);
});
