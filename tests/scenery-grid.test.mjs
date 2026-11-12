import test from 'node:test';
import assert from 'node:assert/strict';
import { analyzeRoute, describeEvidence, findCheckpoints, loadSceneryGrid, GRID_BITS } from '../server/scenery-grid.mjs';
import { planDestinationRoutes, sceneryBonus } from '../server/destination-routes.mjs';

const CELL = 250;
const dLat = CELL / 111320, dLon = CELL / (111320 * Math.cos(36 * Math.PI / 180));
const LAT0 = 35.0, LON0 = 128.9, W = 200, H = 100;
function makeGrid(paint) {
  const cells = new Uint8Array(W * H);
  paint((x, y, bit) => { cells[y * W + x] |= bit; });
  return { W, H, LAT0, LON0, dLat, dLon, CELL, cells };
}
const cellOf = (lat, lon) => [Math.floor((lon - LON0) / dLon), Math.floor((lat - LAT0) / dLat)];
// A straight east-west road about 17 km long.
const road = Array.from({ length: 21 }, (_, i) => [35.1, 129.0 + i * 0.01]);
const [, roadRow] = cellOf(35.1, 129.0);
const [x0] = cellOf(35.1, 129.0);

test('a coastal stretch is measured in kilometres along the road', () => {
  const grid = makeGrid(set => { for (let x = x0 + 10; x < x0 + 50; x++) set(x, roadRow, GRID_BITS.coast); });
  const result = analyzeRoute(road, grid);
  assert.equal(result.source, 'OpenStreetMap contributors');
  assert.ok(result.coastKm >= 8 && result.coastKm <= 12, `coast ${result.coastKm}`);
  assert.equal(result.waterKm, 0);
  assert.equal(result.tunnelKm, 0);
});

test('scenery shorter than 1.5 km is ignored and one sample gap is bridged', () => {
  // Two cells (0.5 km) plus the 250 m neighbourhood on each side stays below the 1.5 km minimum.
  const short = makeGrid(set => { for (let x = x0 + 10; x < x0 + 12; x++) set(x, roadRow, GRID_BITS.water); });
  assert.equal(analyzeRoute(road, short).waterKm, 0);
  const gap = makeGrid(set => {
    for (let x = x0 + 10; x < x0 + 30; x++) set(x, roadRow, GRID_BITS.water);
    for (let x = x0 + 33; x < x0 + 50; x++) set(x, roadRow, GRID_BITS.water);
  });
  assert.ok(analyzeRoute(road, gap).waterKm >= 8);
});

test('tunnels count only the cell under the road, not a parallel tunnel next to it', () => {
  const under = makeGrid(set => { for (let x = x0 + 10; x < x0 + 30; x++) set(x, roadRow, GRID_BITS.tunnel); });
  assert.ok(analyzeRoute(road, under).tunnelKm >= 3);
  const parallel = makeGrid(set => { for (let x = x0 + 10; x < x0 + 30; x++) set(x, roadRow + 1, GRID_BITS.tunnel); });
  assert.equal(analyzeRoute(road, parallel).tunnelKm, 0);
});

test('a road outside the grid, a missing grid and a degenerate road return no measurement', () => {
  const grid = makeGrid(() => {});
  assert.equal(analyzeRoute([[36.9, 127.0], [36.91, 127.01]], grid).coastKm, 0);
  assert.equal(analyzeRoute(road, null), null);
  assert.equal(analyzeRoute([[35.1, 129]], grid), null);
  assert.equal(analyzeRoute('not a road', grid), null);
});

test('evidence names only facts large enough to matter and says visibility is not guaranteed', () => {
  const scenic = { coastKm: 13, waterKm: 1, tunnelKm: 0, source: 'OpenStreetMap contributors' };
  const fast = { coastKm: 0, waterKm: 0, tunnelKm: 31 };
  const { labels, sentence } = describeEvidence(scenic, fast);
  assert.deepEqual(labels, ['해안 13km', '터널 31km 적음']);
  assert.ok(sentence.includes('보장하지 않습니다') && sentence.length < 60);
  assert.deepEqual(describeEvidence({ coastKm: 1, waterKm: 1, tunnelKm: 3 }, { tunnelKm: 5 }), { labels: [], sentence: '' });
  assert.deepEqual(describeEvidence(null, fast), { labels: [], sentence: '' });
});

test('the shipped grid loads, has the expected size and recognises the coast and tunnels', () => {
  const grid = loadSceneryGrid();
  assert.ok(grid, 'catalog/scenery-grid.bin.gz must load');
  assert.equal(grid.cells.length, grid.W * grid.H);
  assert.match(grid.source, /OpenStreetMap/);
  const count = bit => grid.cells.reduce((sum, value) => sum + ((value & bit) ? 1 : 0), 0);
  assert.ok(count(GRID_BITS.coast) > 30000 && count(GRID_BITS.tunnel) > 10000);
  // Haeundae beach has coastline within ~1.5 km; the inland Daejeon basin has none.
  const coastNear = (lat, lon, radius = 6) => {
    const cx = Math.floor((lon - grid.LON0) / grid.dLon), cy = Math.floor((lat - grid.LAT0) / grid.dLat);
    for (let dy = -radius; dy <= radius; dy++) for (let dx = -radius; dx <= radius; dx++) {
      if (grid.cells[(cy + dy) * grid.W + cx + dx] & GRID_BITS.coast) return true;
    }
    return false;
  };
  assert.equal(coastNear(35.1587, 129.1604), true);
  assert.equal(coastNear(36.3323, 127.4343), false);
});

test('a missing grid directory loads as null instead of throwing', () => {
  assert.equal(loadSceneryGrid(new URL('file:///definitely-not-here/')), null);
});

// ---- integration with the route planner ----
const env = { KAKAO_REST_API_KEY: 'private-test-key' };
const sceneTrip = { origin: { lat: 35.1, lon: 129 }, destination: { id: 'd', name: '목적지', lat: 35.1, lon: 129.2 }, extraMinutes: 30, theme: 'coast' };
const sceneBaseline = [[35.1, 129], [35.1, 129.1], [35.1, 129.2]];
const places = [{ id: 'a-first', title: '해안 풍경 하나', lat: 35.11, lon: 129.1 }];
function kakaoRoad(coordinates, seconds, distance) {
  return Response.json({ routes: [{ result_code: 0, summary: { distance, duration: seconds }, sections: [{ roads: [{ name: '도로', distance, duration: seconds, traffic_speed: 30, traffic_state: 2, vertexes: coordinates.flatMap(([lat, lon]) => [lon, lat]) }] }] }] });
}
const fetchImpl = async url => {
  const target = new URL(url), waypoints = target.searchParams.get('waypoints');
  if (!waypoints) return kakaoRoad(sceneBaseline, 1200, 20000);
  const [lon, lat] = waypoints.split(',').map(Number);
  return kakaoRoad([sceneBaseline[0], [lat, lon], sceneBaseline.at(-1)], 1700, 24000);
};
const coastEverywhere = () => makeGrid(set => { for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) set(x, y, GRID_BITS.coast); });

test('the chosen scenic road carries measured evidence without changing which road is chosen', async () => {
  const withGrid = await planDestinationRoutes(sceneTrip, { fetchImpl, env, catalog: { places }, scenery: coastEverywhere() });
  const without = await planDestinationRoutes(sceneTrip, { fetchImpl, env, catalog: { places }, scenery: null });
  assert.equal(withGrid.scenicStatus, 'ok');
  const scenic = withGrid.routes[1], plain = without.routes[1];
  assert.deepEqual(scenic.waypoints, plain.waypoints);
  assert.deepEqual(scenic.coordinates, plain.coordinates);
  assert.ok(scenic.scenicLabels.some(label => /^해안 \d+km$/.test(label)), JSON.stringify(scenic.scenicLabels));
  assert.ok(scenic.reason.includes('지도 분석') && scenic.reason.length <= 200, scenic.reason);
  assert.equal(scenic.headline, '해안 풍경 하나 경유');
  assert.ok(scenic.scenicLabels.length <= 8 && new Set(scenic.scenicLabels).size === scenic.scenicLabels.length);
  assert.equal(scenic.evidence.source, 'OpenStreetMap contributors');
  assert.ok(scenic.evidence.scenic.coastKm >= 10 && scenic.evidence.fast.coastKm >= 10);
});

test('sceneryBonus rewards extra coast, penalises extra tunnel, and ignores coast for non-coast themes', () => {
  const fast = { coastKm: 0, tunnelKm: 10, viewpoints: 0 };
  assert.equal(sceneryBonus(null, fast, 'coast'), 0);
  assert.ok(sceneryBonus({ coastKm: 20, tunnelKm: 10, viewpoints: 0 }, fast, 'coast') > sceneryBonus({ coastKm: 5, tunnelKm: 10, viewpoints: 0 }, fast, 'coast'));
  assert.equal(sceneryBonus({ coastKm: 20, tunnelKm: 10, viewpoints: 0 }, fast, 'forest'), 0);
  assert.ok(sceneryBonus({ coastKm: 0, tunnelKm: 20, viewpoints: 0 }, fast, 'forest') < 0);
  assert.ok(sceneryBonus({ coastKm: 99, tunnelKm: 0, viewpoints: 99 }, fast, 'coast') <= 5);
});

test('mountain relief earns a bonus only for mountain and forest themes, and less than coast', () => {
  const fast = { coastKm: 0, mountainKm: 0, tunnelKm: 0, viewpoints: 0 };
  const hilly = { coastKm: 0, mountainKm: 30, tunnelKm: 0, viewpoints: 0 };
  assert.ok(sceneryBonus(hilly, fast, 'mountain') > 0 && sceneryBonus(hilly, fast, 'forest') > 0);
  assert.equal(sceneryBonus(hilly, fast, 'coast'), 0);
  assert.ok(sceneryBonus({ ...hilly, mountainKm: 999 }, fast, 'mountain') <= 2);
  assert.equal(sceneryBonus({ coastKm: 0, tunnelKm: 0, viewpoints: 0 }, { coastKm: 0, tunnelKm: 0, viewpoints: 0 }, 'mountain'), 0);
});

test('checkpoints are the longest stretches in driving order, with ids stable for the same stretch', () => {
  const grid = makeGrid(set => {
    for (let x = x0 + 2; x < x0 + 18; x++) set(x, roadRow, GRID_BITS.coast);
    for (let x = x0 + 24; x < x0 + 44; x++) set(x, roadRow, GRID_BITS.mountain);
    for (let x = x0 + 52; x < x0 + 68; x++) set(x, roadRow, GRID_BITS.water);
  });
  const points = findCheckpoints(road, grid);
  assert.deepEqual(points.map(p => p.kind), ['coast', 'mountain', 'water']);
  assert.ok(points.every(p => /^[a-z]+-\d+-\d+$/.test(p.id) && p.km >= 3 && p.name.length <= 80));
  assert.ok(points[0].lon < points[1].lon && points[1].lon < points[2].lon);
  assert.deepEqual(findCheckpoints(road, grid).map(p => p.id), points.map(p => p.id));
  assert.equal(findCheckpoints(road, grid, { limit: 1 }).length, 1);
  assert.deepEqual(findCheckpoints(road, null), []);
  assert.deepEqual(findCheckpoints(road, makeGrid(() => {})), []);
});

test('the planner attaches checkpoints to the scenic road when a grid is present and omits them otherwise', async () => {
  const withGrid = await planDestinationRoutes(sceneTrip, { fetchImpl, env, catalog: { places }, scenery: coastEverywhere() });
  const scenic = withGrid.routes[1];
  assert.ok(Array.isArray(scenic.checkpoints) && scenic.checkpoints.length >= 2 && scenic.checkpoints.length <= 6);
  assert.ok(scenic.checkpoints.some(c => c.kind === 'place' && c.name === '해안 풍경 하나'));
  assert.equal(new Set(scenic.checkpoints.map(c => c.id)).size, scenic.checkpoints.length);
  assert.equal('checkpoints' in withGrid.routes[0], false);
  const without = await planDestinationRoutes(sceneTrip, { fetchImpl, env, catalog: { places }, scenery: null });
  assert.equal('checkpoints' in without.routes[1], false);
});

test('with a coast theme the planner prefers the place whose road runs along more coast', async () => {
  const two = [{ id: 'a-inland', title: '내륙 풍경', lat: 35.11, lon: 129.05 }, { id: 'b-coastal', title: '해안 풍경', lat: 35.11, lon: 129.15 }];
  const [, row] = cellOf(35.1, 129.0);
  const [xa] = cellOf(35.1, 129.0);
  const grid = makeGrid(set => { for (let x = xa + 60; x < xa + 200 && x < W; x++) for (let y = row - 3; y <= row + 3; y++) set(x, y, GRID_BITS.coast); for (let x = xa + 51; x <= xa + 57; x++) for (let y = row + 3; y <= row + 6; y++) set(x, y, GRID_BITS.coast); });
  const result = await planDestinationRoutes(sceneTrip, { fetchImpl, env, catalog: { places: two }, scenery: grid });
  assert.equal(result.scenicStatus, 'ok');
  assert.ok(result.routes[1].waypoints.some(w => Math.abs(w.lon - 129.15) < 0.01), JSON.stringify(result.routes[1].waypoints));
});

test('without a grid the scenic road keeps the earlier labels and reason and has no evidence field', async () => {
  const result = await planDestinationRoutes(sceneTrip, { fetchImpl, env, catalog: { places }, scenery: null });
  const scenic = result.routes[1];
  assert.equal('evidence' in scenic, false);
  assert.ok(!scenic.reason.includes('지도 분석'));
  assert.ok(!scenic.scenicLabels.some(label => /\d+km/.test(label)));
});

