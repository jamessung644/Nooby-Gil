import { readFileSync } from 'node:fs';
import { gunzipSync } from 'node:zlib';

// Road-side scenery measured on an offline 250 m grid built from OpenStreetMap.
// It describes what lies beside the road. It does not prove that the scenery is
// visible from the car, and it never changes which route is chosen.
export const GRID_BITS = { coast: 1, water: 2, forest: 4, viewpoint: 8, tunnel: 16, mountain: 32 };
const SOURCE = 'OpenStreetMap contributors';
const STEP_METERS = 500;
const MIN_RUN_KM = 1.5;
const radians = value => value * Math.PI / 180;
const distance = (a, b) => {
  const dlat = radians(b.lat - a.lat), dlon = radians(b.lon - a.lon);
  const h = Math.sin(dlat / 2) ** 2 + Math.cos(radians(a.lat)) * Math.cos(radians(b.lat)) * Math.sin(dlon / 2) ** 2;
  return 12742000 * Math.atan2(Math.sqrt(h), Math.sqrt(Math.max(0, 1 - h)));
};

let cached;
/** Returns the shipped grid, or null when it is missing or malformed. Never throws. */
export function loadSceneryGrid(directory = new URL('../catalog/', import.meta.url)) {
  if (cached !== undefined && directory.href === cached.href) return cached.grid;
  let grid = null;
  try {
    const meta = JSON.parse(readFileSync(new URL('scenery-grid.json', directory), 'utf8'));
    const cells = gunzipSync(readFileSync(new URL('scenery-grid.bin.gz', directory)));
    if (meta.W > 0 && meta.H > 0 && cells.length === meta.W * meta.H) grid = { ...meta, cells };
  } catch { grid = null; }
  cached = { href: directory.href, grid };
  return grid;
}

function cellFlags(grid, p, spread) {
  const cx = Math.floor((p.lon - grid.LON0) / grid.dLon), cy = Math.floor((p.lat - grid.LAT0) / grid.dLat);
  let flags = 0;
  for (let dy = -spread; dy <= spread; dy++) for (let dx = -spread; dx <= spread; dx++) {
    const x = cx + dx, y = cy + dy;
    if (x >= 0 && x < grid.W && y >= 0 && y < grid.H) flags |= grid.cells[y * grid.W + x];
  }
  return flags;
}
/** Scenery bits of the grid cells within `spread` cells of a place (0 = its own cell). Returns 0 without a grid. */
export function flagsAround(place, grid = loadSceneryGrid(), spread = 2) {
  if (!grid || !place || !Number.isFinite(place.lat) || !Number.isFinite(place.lon)) return 0;
  return cellFlags(grid, place, spread);
}// [start, end) sample index pairs of runs (one-sample gaps bridged) of at least minKm.
function runRanges(samples, minKm) {
  const flags = samples.slice();
  for (let i = 1; i < flags.length - 1; i++) if (!flags[i] && flags[i - 1] && flags[i + 1]) flags[i] = true;
  const ranges = [];
  let start = -1;
  for (let i = 0; i <= flags.length; i++) {
    if (flags[i]) { if (start < 0) start = i; continue; }
    if (start >= 0 && (i - start) * STEP_METERS / 1000 >= minKm) ranges.push([start, i]);
    start = -1;
  }
  return ranges;
}
// Kilometres inside runs of consecutive samples (one-sample gaps bridged) of at least minKm.
const runKm = (samples, minKm) => runRanges(samples, minKm).reduce((sum, [a, b]) => sum + (b - a) * STEP_METERS / 1000, 0);

// Points every STEP_METERS along the road. Walk every segment so sparse vertices cannot skip a stretch.
function sampleRoad(coordinates) {
  const samples = [{ lat: coordinates[0][0], lon: coordinates[0][1] }];
  let sinceSample = 0;
  for (let i = 1; i < coordinates.length; i++) {
    const a = { lat: coordinates[i - 1][0], lon: coordinates[i - 1][1] }, b = { lat: coordinates[i][0], lon: coordinates[i][1] };
    const length = distance(a, b);
    if (!(length > 0)) continue;
    let along = STEP_METERS - sinceSample;
    for (; along <= length; along += STEP_METERS) {
      const t = along / length;
      samples.push({ lat: a.lat + (b.lat - a.lat) * t, lon: a.lon + (b.lon - a.lon) * t });
    }
    sinceSample = length - (along - STEP_METERS);
  }
  return samples;
}

/**
 * Measures a road polyline ([lat, lon] pairs). Coast, water and mountain use a 3 x 3 cell
 * neighbourhood (about 375 m); tunnels use only the cell under the road.
 */
export function analyzeRoute(coordinates, grid = loadSceneryGrid()) {
  if (!grid || !Array.isArray(coordinates) || coordinates.length < 2) return null;
  const samples = sampleRoad(coordinates);
  const near = samples.map(p => cellFlags(grid, p, 1)), under = samples.map(p => cellFlags(grid, p, 0));
  const km = bit => runKm(near.map(flags => !!(flags & bit)), MIN_RUN_KM);
  return {
    source: SOURCE, stepMeters: STEP_METERS,
    coastKm: Math.round(km(GRID_BITS.coast)), waterKm: Math.round(km(GRID_BITS.water)), mountainKm: Math.round(km(GRID_BITS.mountain)),
    tunnelKm: Math.round(under.filter(flags => flags & GRID_BITS.tunnel).length * STEP_METERS / 1000),
    viewpoints: near.filter(flags => flags & GRID_BITS.viewpoint).length,
  };
}

const CHECKPOINT_KINDS = [
  { kind: 'coast', bit: GRID_BITS.coast, label: '바다·해안 구간' },
  { kind: 'water', bit: GRID_BITS.water, label: '강·호수 구간' },
  { kind: 'mountain', bit: GRID_BITS.mountain, label: '산길 구간' },
];
/**
 * Stretches of the road where a clip is worth recording: the longest runs of coast, lake or river
 * and mountain scenery, in driving order. The id comes from the 1 km block where the stretch starts,
 * so another road through the same stretch gets the same id. Returns [] without a grid.
 */
export function findCheckpoints(coordinates, grid = loadSceneryGrid(), { minKm = 3, limit = 4 } = {}) {
  if (!grid || !Array.isArray(coordinates) || coordinates.length < 2) return [];
  const samples = sampleRoad(coordinates), near = samples.map(p => cellFlags(grid, p, 1));
  const found = [];
  for (const { kind, bit, label } of CHECKPOINT_KINDS) {
    for (const [from, to] of runRanges(near.map(flags => !!(flags & bit)), minKm)) {
      const start = samples[from], end = samples[to - 1], km = Math.round((to - from) * STEP_METERS / 100) / 10;
      const bx = Math.floor((start.lon - grid.LON0) / grid.dLon / 4), by = Math.floor((start.lat - grid.LAT0) / grid.dLat / 4);
      found.push({ id: `${kind}-${bx}-${by}`, kind, name: `${label} ${Math.round(km)}km`, lat: start.lat, lon: start.lon, endLat: end.lat, endLon: end.lon, km, from });
    }
  }
  // Longest first, then back to driving order. One checkpoint id never appears twice.
  const seen = new Set();
  return found.sort((a, b) => b.km - a.km).filter(item => !seen.has(item.id) && seen.add(item.id)).slice(0, limit)
    .sort((a, b) => a.from - b.from).map(({ from, ...item }) => item);
}

/**
 * Short Korean evidence lines for a scenic road, compared with the fast road.
 * Only facts that are large enough to matter are reported.
 */
export function describeEvidence(scenic, fast) {
  if (!scenic) return { labels: [], sentence: '' };
  const labels = [];
  if (scenic.coastKm >= 2) labels.push(`해안 ${scenic.coastKm}km`);
  if (scenic.waterKm >= 2) labels.push(`호수·강변 ${scenic.waterKm}km`);
  if (scenic.mountainKm >= 5) labels.push(`산지 ${scenic.mountainKm}km`);
  if (fast && fast.tunnelKm - scenic.tunnelKm >= 5) labels.push(`터널 ${fast.tunnelKm - scenic.tunnelKm}km 적음`);
  // The data source is credited in the evidence field and in the app footer, not in every sentence.
  const sentence = labels.length ? '지도 분석 기준이며 실제 조망은 보장하지 않습니다.' : '';
  return { labels, sentence };
}
