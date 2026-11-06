import test from 'node:test';
import assert from 'node:assert/strict';
import { rankRoutes, haversineKm, projectProgress, selectSound, canInteract } from '../web/core.js';
import { routes, dataNotice, fixtureSources } from '../web/data.js';

const candidates = [
  { id: 'lake', title: '의암호 물빛', region: '춘천', description: '호수를 따라 쉬어 가는 길', theme: 'lake', durationMin: 60, distanceKm: 20, sceneryScore: 80, tags: ['물안개'], coordinates: [[37, 127], [37, 127.1]], stops: [] },
  { id: 'forest', title: '숲의 길', region: '춘천', description: '나무 그늘', theme: 'forest', durationMin: 61, distanceKm: 22, sceneryScore: 100, tags: ['초록'], coordinates: [[37, 127], [37.1, 127]], stops: [] },
  { id: 'sea', title: '바다의 길', region: '강릉', description: '바닷바람', theme: 'coast', durationMin: 40, distanceKm: 15, sceneryScore: 85, tags: ['일몰'], coordinates: [[37, 129], [37.1, 129]], stops: [] },
];

// Catches a strict budget changed to a ranking penalty or a wrong < boundary.
test('budget excludes over-budget routes but includes the exact boundary', () => {
  const result = rankRoutes(candidates, { region: 'all', theme: 'all', maxMinutes: 60 });
  assert.deepEqual(new Set(result.map((route) => route.id)), new Set(['lake', 'sea']));
  assert.deepEqual(rankRoutes(candidates, { maxMinutes: 39 }), []);
});

test('region is a strict filter while theme ranks matching routes first', () => {
  const result = rankRoutes(candidates, { region: '춘천', theme: 'lake', maxMinutes: 90 });
  assert.deepEqual(result.map((route) => route.id), ['lake', 'forest']);
  assert.ok(result.every((route) => Number.isFinite(route.matchScore) && route.reasons.length > 0));
  assert.ok(result.every((route) => route.matchScore >= 0 && route.matchScore <= 100));
});

test('Korean search ignores whitespace and checks tags without broadening region', () => {
  assert.deepEqual(rankRoutes(candidates, { query: ' 의 암 호 ' }).map((route) => route.id), ['lake']);
  assert.deepEqual(rankRoutes(candidates, { query: ' 물 안 개 ' }).map((route) => route.id), ['lake']);
  assert.deepEqual(rankRoutes(candidates, { region: '서울', query: '의암호' }), []);
  assert.deepEqual(rankRoutes(candidates, { query: '없는 장소' }), []);
});

test('ranking handles absent input and invalid budgets without claiming feasible routes', () => {
  assert.deepEqual(rankRoutes(), []);
  assert.equal(rankRoutes(candidates).length, 3);
  for (const maxMinutes of [-1, NaN, '60', null]) {
    assert.deepEqual(rankRoutes(candidates, { maxMinutes }), []);
  }
});

test('ranking is deterministic and leaves source data intact', () => {
  const original = structuredClone(candidates);
  const first = rankRoutes(candidates, { theme: 'lake', maxMinutes: 90 });
  assert.deepEqual(first, rankRoutes(candidates, { theme: 'lake', maxMinutes: 90 }));
  assert.deepEqual(candidates, original);
  assert.notStrictEqual(first[0], candidates[0]);
});

// A [lon,lat] implementation yields about 111 km instead of 85 km here.
test('haversine uses [latitude, longitude] order and geographic longitude scaling', () => {
  assert.ok(Math.abs(haversineKm([40, 0], [40, 1]) - 85.18) < 0.1);
  assert.ok(Math.abs(haversineKm([0, 0], [1, 0]) - 111.195) < 0.1);
  assert.equal(haversineKm([37.5, 127], [37.5, 127]), 0);
});

test('haversine rejects malformed or reversed Korean coordinates', () => {
  for (const point of [[127, 37], [0, 181], [NaN, 0], [1], null, ['37', 127]]) {
    assert.throws(() => haversineKm(point, [0, 0]), /latitude|coordinates/i);
  }
});

const unevenRoute = {
  theme: 'lake', durationMin: 80, distanceKm: 44,
  coordinates: [[0, 0], [0, 1], [0, 4]],
  stops: [
    { id: 'start', lat: 0, lon: 0, scene: 'forest' },
    { id: 'end', lat: 0, lon: 4, scene: 'coast' },
  ],
};

test('progress follows distance across uneven polyline segments, not vertex count', () => {
  const half = projectProgress(unevenRoute, 0.5);
  assert.equal(half.lat, 0);
  assert.ok(Math.abs(half.lon - 2) < 1e-9);
  assert.equal(half.distanceKm, 22);
  assert.equal(half.remainingMin, 40);
});

test('progress clamps endpoints and uses the nearest sound scene anchor', () => {
  const start = projectProgress(unevenRoute, -2);
  assert.deepEqual([start.lat, start.lon, start.stopIndex, start.scene, start.distanceKm, start.remainingMin], [0, 0, 0, 'forest', 0, 80]);
  const end = projectProgress(unevenRoute, 8);
  assert.deepEqual([end.lat, end.lon, end.stopIndex, end.scene, end.distanceKm, end.remainingMin], [0, 4, 1, 'coast', 44, 0]);
  assert.equal(projectProgress(unevenRoute, 0.8).stopIndex, 1);
});

test('nonfinite and absent progress produces bounded locations', () => {
  assert.equal(projectProgress(unevenRoute, NaN).lon, 0);
  assert.equal(projectProgress(unevenRoute).lon, 0);
  assert.equal(projectProgress(unevenRoute, Infinity).lon, 4);
  assert.equal(projectProgress(unevenRoute, -Infinity).lon, 0);
});

test('empty and degenerate paths are finite and never invent a zero-coordinate trip', () => {
  const empty = projectProgress({}, 0.5);
  assert.deepEqual(empty, { lat: null, lon: null, stopIndex: -1, scene: 'lake', distanceKm: 0, remainingMin: 0 });
  const single = projectProgress({ ...unevenRoute, coordinates: [[37, 127], [37, 127]], stops: [] }, 0.5);
  assert.deepEqual([single.lat, single.lon, single.distanceKm, single.stopIndex], [37, 127, 0, -1]);
  assert.ok(Number.isFinite(single.remainingMin));
  assert.equal(projectProgress({ coordinates: [[127, 37]] }, 0.2).lat, null);
});

test('scene selection provides original ambience metadata and safe fallback', () => {
  const lake = selectSound('lake');
  const forest = selectSound('forest');
  assert.notEqual(lake.id, forest.id);
  for (const scene of ['lake', 'coast', 'forest', 'city']) {
    const track = selectSound(scene);
    assert.equal(track.scene, scene);
    assert.ok(track.title && track.artist && track.mood && track.bpm > 0);
  }
  assert.equal(selectSound('unknown').scene, 'lake');
  lake.title = 'mutated caller value';
  assert.notEqual(selectSound('lake').title, lake.title);
});

test('moving or UX-restricted vehicles only expose minimal playback interaction', () => {
  for (const state of [{ moving: true }, { moving: false, uxRestricted: true }]) {
    for (const action of ['video', 'edit', 'browse']) assert.equal(canInteract(state, action), false);
    assert.equal(canInteract(state, 'playback'), true);
  }
  for (const action of ['video', 'edit', 'browse', 'playback']) {
    assert.equal(canInteract({ moving: false, uxRestricted: false }, action), true);
  }
});

test('unknown movement, restriction types, and unknown actions fail closed', () => {
  for (const state of [undefined, {}, { moving: 'false' }, { moving: null }, { moving: false, uxRestricted: 'false' }]) {
    assert.equal(canInteract(state, 'video'), false);
    assert.equal(canInteract(state, 'playback'), false);
  }
  assert.equal(canInteract({ moving: false }, 'delete'), false);
});

test('curated fixtures support all four regions and round-trip through ranking and simulation', () => {
  assert.ok(routes.length >= 8);
  assert.deepEqual(new Set(routes.map((route) => route.region)), new Set(['춘천', '강릉', '서울', '제주']));
  assert.equal(new Set(routes.map((route) => route.id)).size, routes.length);
  assert.ok(dataNotice && fixtureSources.length >= 4);
  for (const route of routes) {
    assert.equal(route.source, 'curated-demo');
    assert.ok(route.stops.length >= 2 && route.coordinates.length >= 2);
    const result = rankRoutes([route], { region: route.region, theme: route.theme, maxMinutes: route.durationMin });
    assert.equal(result.length, 1);
    const start = projectProgress(route, 0);
    const end = projectProgress(route, 1);
    assert.deepEqual([start.lat, start.lon], route.coordinates[0]);
    assert.deepEqual([end.lat, end.lon], route.coordinates.at(-1));
    assert.equal(end.distanceKm, route.distanceKm);
    assert.equal(end.remainingMin, 0);
    for (const stop of route.stops) {
      assert.ok(Number.isFinite(haversineKm([stop.lat, stop.lon], route.coordinates[0])));
      assert.equal(selectSound(stop.scene).scene, stop.scene);
    }
  }
});
