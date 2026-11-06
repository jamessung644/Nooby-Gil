import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';

const scriptUrl = new URL('../web/kakao-map.js', import.meta.url);
// The real page logic runs against DOM and SDK transport boundaries, with no
// sockets, provider key, map tiles, GPS access, or browser visual claims.
function harness({ config = { configured: true, javascriptKey: 'public-js-key' }, status = 200, pendingConfig = false, beforeDom = false } = {}) {
  const elements = new Map(), scripts = [], requests = [], timers = new Map(), maps = [], markers = [], lines = [], circles = [], overlays = [], warnings = [];
  let nextTimer = 0, now = 0, resize, domReady;
  class Element {
    constructor(tag = 'div') { this.tagName = tag; this.hidden = false; this.textContent = ''; this.children = []; this.listeners = {}; this.style = {}; }
    addEventListener(name, fn) { this.listeners[name] = fn; }
    setAttribute(name, value) { this[name] = value; }
    appendChild(child) { this.children.push(child); if (child.tagName === 'script') scripts.push(child); return child; }
    replaceChildren() { this.children = []; }
    remove() { this.removed = true; }
  }
  const element = id => { if (!elements.has(id)) elements.set(id, new Element()); return elements.get(id); };
  const document = {
    readyState: beforeDom ? 'loading' : 'complete', head: new Element('head'),
    getElementById: element, createElement: tag => new Element(tag),
    addEventListener(name, fn) { if (name === 'DOMContentLoaded') domReady = fn; },
  };
  class LatLng { constructor(lat, lon) { this.lat = lat; this.lon = lon; } }
  class LatLngBounds { constructor() { this.points = []; } extend(point) { this.points.push(point); } }
  class KakaoMap {
    constructor(container, options) { this.options = options; this.centers = []; this.bounds = []; this.relayouts = 0; this.controls = []; maps.push(this); }
    addControl(control, position) { this.controls.push({ control, position }); }
    setBounds(bounds) { this.bounds.push(bounds); }
    setCenter(point) { this.centers.push(point); }
    setLevel(level) { this.level = level; }
    relayout() { this.relayouts++; }
  }
  const shape = records => class { constructor(options) { this.options = options; this.map = options.map; records.push(this); } setMap(map) { this.map = map; } };
  const sdk = { Map: KakaoMap, LatLng, LatLngBounds, Marker: shape(markers), Polyline: shape(lines), Circle: shape(circles), CustomOverlay: shape(overlays), ZoomControl: class {}, ControlPosition: { RIGHT: 'RIGHT' }, load(fn) { fn(); } };
  const window = { addEventListener() {}, location: { reload() { window.reloaded = true; } } };
  const context = {
    window, document, URL, AbortController, console: { warn(...args) { warnings.push(args); }, error() { throw new Error('Do not log upstream errors'); } },
    navigator: { get geolocation() { throw new Error('Browser GPS must never be accessed'); } },
    fetch: async (url, options) => { requests.push({ url, options }); if (pendingConfig) return new Promise(() => {}); return { ok: status >= 200 && status < 300, json: async () => config }; },
    setTimeout(fn, delay) { const id = ++nextTimer; timers.set(id, { fn, delay, at: now + delay }); return id; }, clearTimeout(id) { timers.delete(id); },
    requestAnimationFrame(fn) { fn(); return 1; }, cancelAnimationFrame() {},
    ResizeObserver: class { constructor(fn) { resize = fn; } observe() {} disconnect() {} },
  };
  vm.runInNewContext(fs.readFileSync(scriptUrl, 'utf8'), context);
  return { window, element, scripts, requests, timers, maps, markers, lines, circles, overlays, sdk, warnings,
    domReady() { domReady(); }, resize() { resize(); },
    finishSdk() { window.kakao = { maps: sdk }; scripts.at(-1).onload(); },
    async flush() { for (let i = 0; i < 12; i++) await Promise.resolve(); },
    expire(delay) { const timer = [...timers.values()].find(t => t.delay === delay); assert.ok(timer, `Missing ${delay}ms timeout`); timer.fn(); },
    advance(milliseconds) {
      const target = now + milliseconds;
      for (;;) {
        const next = [...timers.entries()].filter(([, timer]) => timer.at <= target).sort((a, b) => a[1].at - b[1].at)[0];
        if (!next) break;
        now = next[1].at; timers.delete(next[0]); next[1].fn();
      }
      now = target;
    },
    diagnostics() { return [...elements.values()].map(value => value.textContent).join('\n') + '\n' + JSON.stringify(warnings); },
  };
}

const scene = overrides => ({ courseId: 'seoul', title: '서울, 빛이 흐르는 밤', stops: [
  { title: '반포한강공원', lat: 37.51, lon: 126.9959 }, { title: '뚝섬한강공원', lat: 37.5293, lon: 127.0699 },
], roadCoordinates: [], location: null, focus: 'route', active: 0, ...overrides });

test('native bridge is installed before DOM and keeps only the latest scene until SDK ready', async () => {
  const h = harness({ beforeDom: true });
  assert.equal(typeof h.window.scenicMap?.setScene, 'function');
  assert.equal(h.window.scenicMap.setScene(scene()), true);
  assert.equal(h.window.scenicMap.setScene(scene({ title: '최신 코스' })), true);
  assert.equal(h.requests.length, 0);
  h.domReady(); await h.flush();
  assert.equal(h.requests[0].url, '/api/map-config');
  assert.equal(h.requests[0].options.redirect, 'error');
  const sdkUrl = new URL(h.scripts[0].src);
  assert.equal(sdkUrl.origin, 'https://dapi.kakao.com');
  assert.equal(sdkUrl.pathname, '/v2/maps/sdk.js');
  assert.equal(sdkUrl.searchParams.get('appkey'), 'public-js-key');
  assert.equal(sdkUrl.searchParams.get('autoload'), 'false');
  h.finishSdk(); await h.flush();
  assert.equal(h.element('map-title').textContent, '최신 코스');
  assert.equal(h.markers.length, 2);
  assert.equal(h.lines.length, 0, 'Stops without road data must not be connected');
  assert.equal(h.maps[0].controls.length, 1);
});

test('road geometry preserves latitude/longitude order and old route layers are removed', async () => {
  const h = harness(); await h.flush(); h.finishSdk(); await h.flush();
  h.window.scenicMap.setScene(scene({ roadCoordinates: [[37.51, 126.9959], [37.52, 127.01], [37.5293, 127.0699]] }));
  assert.equal(h.lines.length, 1);
  assert.deepEqual(Array.from(h.lines[0].options.path, p => [p.lat, p.lon]), [[37.51, 126.9959], [37.52, 127.01], [37.5293, 127.0699]]);
  assert.match(h.element('map-detail').textContent, /도로/);
  h.window.scenicMap.setScene(scene({ courseId: 'chuncheon', title: '다른 코스', roadCoordinates: null }));
  assert.equal(h.lines[0].map, null);
  assert.equal(h.lines.length, 1);
  assert.equal(h.markers[0].map, null);
  assert.match(h.element('map-detail').textContent, /도로.*없|명소/);
});

test('Android location creates a fixed dot and metre accuracy circle and clears when absent', async () => {
  const h = harness(); await h.flush(); h.finishSdk(); await h.flush();
  h.window.scenicMap.setScene(scene({ location: { lat: 37.6, lon: 127.2, accuracy: 24 }, focus: 'location', focusRevision: 1 }));
  assert.equal(h.circles.length, 1);
  assert.equal(h.circles[0].options.radius, 24);
  assert.equal(h.circles[0].options.center.lat, 37.6);
  assert.ok(h.overlays.some(x => x.options.content.className === 'current-location'));
  assert.equal(h.maps[0].centers.at(-1).lon, 127.2);
  h.window.scenicMap.setScene(scene({ location: { lat: 37.6, lon: 127.2, accuracy: null }, focusRevision: 1 }));
  assert.equal(h.circles.length, 1, 'Unknown accuracy must not become an invented radius');
  assert.match(h.element('map-location').textContent, /정확도 미수신/);
  h.window.scenicMap.setScene(scene({ location: null, focusRevision: 2 }));
  assert.equal(h.circles[0].map, null);
  assert.match(h.element('map-location').textContent, /현재 위치.*없|현재 위치.*미수신/);
});

test('GPS updates preserve user pan until native focus revision changes; resize relayout preserves focus', async () => {
  const h = harness(); await h.flush(); h.finishSdk(); await h.flush();
  h.window.scenicMap.setScene(scene({ location: { lat: 37.6, lon: 127.2, accuracy: 24 }, focus: 'location', focusRevision: 1 }));
  const moves = h.maps[0].centers.length;
  h.window.scenicMap.setScene(scene({ location: { lat: 37.61, lon: 127.21, accuracy: 20 }, focus: 'location', focusRevision: 1 }));
  assert.equal(h.maps[0].centers.length, moves);
  h.window.scenicMap.setScene(scene({ location: { lat: 37.61, lon: 127.21, accuracy: 20 }, focus: 'location', focusRevision: 2 }));
  assert.equal(h.maps[0].centers.length, moves + 1);
  h.resize();
  assert.equal(h.maps[0].relayouts, 1);
});

test('invalid native scenes are rejected atomically without drawing invented or unbounded coordinates', async () => {
  const h = harness(); await h.flush(); h.finishSdk(); await h.flush();
  h.window.scenicMap.setScene(scene());
  const count = h.markers.length;
  for (const invalid of [null, scene({ stops: [{ title: 'bad', lat: 200, lon: 127 }] }),
    scene({ roadCoordinates: [[37, 127]] }), scene({ roadCoordinates: [[37, 127], [NaN, 128]] }),
    scene({ location: { lat: 37, lon: 127, accuracy: -1 } }), scene({ focus: 'arbitrary' }),
    scene({ active: 4 }), scene({ focusRevision: -1 }), scene({ roadCoordinates: Array(50001).fill([37, 127]) }),
  ]) assert.equal(h.window.scenicMap.setScene(invalid), false);
  assert.equal(h.markers.length, count);
  assert.equal(h.element('map-title').textContent, '서울, 빛이 흐르는 밤');
});

test('missing map key shows an explicit unconnected state and no SDK request', async () => {
  const h = harness({ config: { configured: false, javascriptKey: '' } }); await h.flush();
  assert.equal(h.scripts.length, 0);
  assert.equal(h.element('map-message').hidden, false);
  assert.match(h.element('map-message-title').textContent, /실제 지도.*연결/);
  assert.match(h.element('map-message-detail').textContent, /KAKAO_MAP_JS_KEY/);
  assert.equal(h.element('map-retry').disabled, false);
  h.element('map-retry').listeners.click(); await h.flush();
  assert.equal(h.requests.length, 2);
});

test('404, server failure and config timeout show retry without private provider errors or setup code', async () => {
  for (const status of [404, 500]) {
    const h = harness({ status, config: { error: 'secret-REST-token' } }); await h.flush();
    assert.equal(h.scripts.length, 0);
    assert.equal(h.element('map-message').hidden, false);
    assert.doesNotMatch(h.element('map-message-detail').textContent, /secret|KAKAO_MAP_JS_KEY/);
    assert.equal(h.element('map-retry').disabled, false);
  }
  const h = harness({ pendingConfig: true }); await h.flush(); h.expire(8000); await h.flush();
  assert.equal(h.element('map-message').hidden, false);
  assert.match(h.element('map-message-title').textContent, /실제 지도.*연결/);
  assert.equal(h.element('map-retry').disabled, false);
});

test('SDK stays loading for 30 seconds then shows timeout guidance without leaking its key', async () => {
  const key = 'test-sdk-key-not-for-diagnostics';
  const h = harness({ config: { configured: true, javascriptKey: key } }); await h.flush();
  h.advance(29999); await h.flush();
  assert.equal(h.element('map-retry').disabled, true);
  assert.match(h.element('map-message-title').textContent, /준비/);
  assert.equal(h.warnings.length, 0);
  h.advance(1); await h.flush();
  assert.equal(h.element('map-message').hidden, false);
  assert.equal(h.element('map-canvas').hidden, true);
  assert.equal(h.element('map-retry').disabled, false);
  assert.match(h.element('map-message-detail').textContent, /응답 시간이 초과/);
  assert.match(h.element('map-message-detail').textContent, /새로고침/);
  assert.doesNotMatch(h.element('map-message-detail').textContent, /스크립트를 불러오지/);
  assert.equal(h.maps.length, 0);
  assert.equal(h.scripts[0].onload, null);
  assert.equal(h.scripts[0].removed, true);
  assert.deepEqual(h.warnings, [['SCENIC_MAP', 'map-sdk:timeout', 'Error']]);
  assert.ok(!h.diagnostics().includes(key));
  h.element('map-retry').listeners.click(); await h.flush();
  assert.equal(h.requests.length, 2);
  assert.equal(h.scripts.length, 2);
  h.finishSdk(); await h.flush();
  assert.equal(h.element('map-message').hidden, true);
  assert.equal(h.maps.length, 1, 'Retry can recover after a timeout');
});

test('first SDK script network failure shows script guidance and ignores key-bearing event details', async () => {
  const key = 'test-network-key-not-for-diagnostics';
  const h = harness({ config: { configured: true, javascriptKey: key } }); await h.flush();
  h.scripts[0].onerror({ type: 'error', target: h.scripts[0], message: `Failed to load ${h.scripts[0].src}`, error: new Error(key) });
  await h.flush();
  assert.equal(h.element('map-message').hidden, false);
  assert.equal(h.element('map-canvas').hidden, true);
  assert.equal(h.element('map-retry').disabled, false);
  assert.match(h.element('map-message-detail').textContent, /스크립트를 불러오지/);
  assert.doesNotMatch(h.element('map-message-detail').textContent, /시간이 초과|KAKAO_MAP_JS_KEY/);
  assert.deepEqual(h.warnings, [['SCENIC_MAP', 'map-sdk:network', 'Error']]);
  assert.ok(!h.diagnostics().includes(key));
  assert.ok(!h.diagnostics().includes(h.scripts[0].src));
  h.advance(30000); await h.flush();
  assert.equal(h.warnings.length, 1, 'Cleared timeout cannot replace the network diagnosis');
  assert.equal(h.maps.length, 0);
});

test('missing SDK API and initialization exceptions stay controlled and redact upstream error text', async () => {
  const key = 'test-initialization-key-not-for-diagnostics';
  for (const failure of ['missing-api', 'initialization']) {
    const h = harness({ config: { configured: true, javascriptKey: key } }); await h.flush();
    if (failure === 'initialization') h.sdk.load = () => { throw new Error(`SDK rejected appkey=${key}`); };
    if (failure === 'missing-api') h.scripts[0].onload(); else h.finishSdk();
    await h.flush();
    assert.equal(h.element('map-message').hidden, false);
    assert.equal(h.element('map-retry').disabled, false);
    assert.match(h.element('map-message-detail').textContent, /스크립트를 불러오지/);
    assert.deepEqual(h.warnings, [['SCENIC_MAP', `map-sdk:${failure}`, 'Error']]);
    assert.ok(!h.diagnostics().includes(key));
    assert.equal(h.maps.length, 0);
  }
});

test('map construction errors use display guidance without forwarding key-bearing SDK messages', async () => {
  const key = 'test-map-display-key-not-for-diagnostics';
  const h = harness({ config: { configured: true, javascriptKey: key } }); await h.flush();
  h.sdk.Map = class { constructor() { throw new Error(`Map initialization failed: appkey=${key}`); } };
  h.finishSdk(); await h.flush();
  assert.equal(h.element('map-message').hidden, false);
  assert.equal(h.element('map-canvas').hidden, true);
  assert.equal(h.element('map-retry').disabled, false);
  assert.match(h.element('map-message-detail').textContent, /화면에 표시하는 중 오류/);
  assert.doesNotMatch(h.element('map-message-detail').textContent, /시간이 초과|스크립트를 불러오지/);
  assert.deepEqual(h.warnings, [['SCENIC_MAP', 'map-display', 'Error']]);
  assert.ok(!h.diagnostics().includes(key));
});

test('default view is explicitly not a current location and unsafe titles stay plain text', async () => {
  const h = harness(); await h.flush(); h.finishSdk(); await h.flush();
  assert.match(h.element('map-location').textContent, /현재 위치.*아님/);
  const title = '<img src=x onerror=alert(1)>';
  h.window.scenicMap.setScene(scene({ title, stops: [{ title, lat: 37, lon: 127 }] }));
  assert.equal(h.element('map-title').textContent, title);
  assert.ok(h.overlays.some(x => x.options.content.textContent.includes(title)));
  assert.ok(h.overlays.every(x => typeof x.options.content !== 'string'));
});
