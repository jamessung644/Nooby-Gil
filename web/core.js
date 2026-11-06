/** Pure, dependency-free demo logic. No remote AI, live traffic, or road routing. */
const EARTH_RADIUS_KM = 6371.0088;
const THEMES = ['lake', 'coast', 'forest', 'city'];
const THEME_NAMES = { lake: '호수', coast: '바다', forest: '숲', city: '도시' };
const clamp = (value, min, max) => Math.max(min, Math.min(max, value));
const normalize = (value) => String(value ?? '').normalize('NFKC').replace(/\s+/gu, '').toLocaleLowerCase('ko-KR');
const isCoordinate = (point) => Array.isArray(point) && point.length === 2
  && point.every(Number.isFinite) && Math.abs(point[0]) <= 90 && Math.abs(point[1]) <= 180;
const nonnegative = (value, fallback = 0) => Number.isFinite(value) && value >= 0 ? value : fallback;
const validScene = (scene) => THEMES.includes(scene) ? scene : 'lake';

/**
 * Strict filters: region, duration <= maxMinutes, normalized substring query.
 * Theme is a preference, not a filter: alternatives remain visible.
 * Score (0–100) = curated scenery × 0.6 + theme (30) + time headroom (0–10).
 * No selected theme receives the neutral 30 points; no budget receives 10.
 * Scores are deterministic demo relevance scores, never AI confidence.
 */
export function rankRoutes(routes = [], options = {}) {
  if (!Array.isArray(routes)) return [];
  const { region = 'all', theme = 'all', maxMinutes = Infinity, query = '' } = options ?? {};
  if (typeof maxMinutes !== 'number' || Number.isNaN(maxMinutes) || maxMinutes < 0) return [];
  const selectedRegion = normalize(region);
  const search = normalize(query);
  return routes
    .filter((route) => route && Number.isFinite(route.durationMin) && route.durationMin >= 0
      && route.durationMin <= maxMinutes
      && (region === 'all' || normalize(route.region) === selectedRegion)
      && (!search || [route.title, route.region, route.description, ...(route.tags ?? [])]
        .some((value) => normalize(value).includes(search))))
    .map((route) => {
      const scenery = clamp(nonnegative(route.sceneryScore), 0, 100);
      const themeMatch = theme === 'all' || route.theme === theme;
      const headroom = maxMinutes === Infinity ? 1 : maxMinutes === 0 ? 0 : 1 - route.durationMin / maxMinutes;
      const matchScore = Math.round(scenery * 0.6 + (themeMatch ? 30 : 0) + headroom * 10);
      const reasons = [`큐레이션 경관 ${scenery}점`];
      if (theme !== 'all') reasons.unshift(route.theme === theme ? `${THEME_NAMES[theme] ?? theme} 취향 일치` : '다른 풍경의 대안');
      if (maxMinutes !== Infinity) reasons.push(`${maxMinutes - route.durationMin}분 여유 · 시간 조건 충족`);
      if (search) reasons.push('검색어 일치');
      return { ...route, matchScore, reasons };
    })
    .sort((a, b) => b.matchScore - a.matchScore || a.durationMin - b.durationMin
      || String(a.id).localeCompare(String(b.id), 'en'));
}

/** @param {[number, number]} a [latitude, longitude], decimal degrees.
 * @param {[number, number]} b [latitude, longitude], decimal degrees.
 * @returns {number} Great-circle distance in kilometers (not road distance).
 */
export function haversineKm(a, b) {
  if (!isCoordinate(a) || !isCoordinate(b)) {
    throw new RangeError('Coordinates must be finite [latitude, longitude], latitude ±90 and longitude ±180.');
  }
  const radians = Math.PI / 180;
  const latitudeDelta = (b[0] - a[0]) * radians;
  const longitudeDelta = (b[1] - a[1]) * radians;
  const arc = Math.sin(latitudeDelta / 2) ** 2
    + Math.cos(a[0] * radians) * Math.cos(b[0] * radians) * Math.sin(longitudeDelta / 2) ** 2;
  return 2 * EARTH_RADIUS_KM * Math.asin(Math.sqrt(clamp(arc, 0, 1)));
}

/**
 * Projects a simulation fraction along geographic segment lengths.
 * Positions are illustrative, not snapped to roads. The nearest valid stop is
 * a sound-scene anchor; stopIndex is its index in the original stops array.
 * distanceKm is elapsed fixture trip distance; remainingMin is fixture time.
 * Missing/invalid paths return null coordinates rather than a fabricated fix.
 */
export function projectProgress(route, progress0to1 = 0) {
  const fallback = { lat: null, lon: null, stopIndex: -1, scene: validScene(route?.theme), distanceKm: 0, remainingMin: 0 };
  const points = route?.coordinates;
  if (!Array.isArray(points) || !points.length || !points.every(isCoordinate)) return fallback;
  const progress = typeof progress0to1 === 'number' && !Number.isNaN(progress0to1) ? clamp(progress0to1, 0, 1) : 0;
  const segments = points.slice(1).map((point, index) => haversineKm(points[index], point));
  const totalKm = segments.reduce((sum, length) => sum + length, 0);
  let position = points[0];
  let remainingKm = progress * totalKm;
  if (progress === 1) position = points[points.length - 1];
  else if (totalKm > 0) {
    for (let index = 0; index < segments.length; index += 1) {
      const length = segments[index];
      if (length === 0) continue;
      if (remainingKm <= length) {
        const fraction = remainingKm / length;
        const start = points[index];
        const end = points[index + 1];
        position = [start[0] + (end[0] - start[0]) * fraction, start[1] + (end[1] - start[1]) * fraction];
        break;
      }
      remainingKm -= length;
    }
  }
  let stopIndex = -1;
  let nearestKm = Infinity;
  const stops = Array.isArray(route.stops) ? route.stops : [];
  stops.forEach((stop, index) => {
    const point = [stop?.lat, stop?.lon];
    if (!isCoordinate(point)) return;
    const km = haversineKm(position, point);
    if (km < nearestKm) { nearestKm = km; stopIndex = index; }
  });
  return {
    lat: position[0], lon: position[1], stopIndex,
    scene: validScene(stopIndex >= 0 ? stops[stopIndex].scene : route.theme),
    distanceKm: totalKm === 0 ? 0 : Math.round(nonnegative(route.distanceKm, totalKm) * progress * 10) / 10,
    remainingMin: Math.ceil(nonnegative(route.durationMin) * (1 - progress)),
  };
}

// Original synthesized ambience descriptions; no commercial recording or artist likeness.
const SOUNDS = {
  lake: { id: 'still-water', title: '물결 사이', artist: 'Pleos Drive · 오리지널 앰비언스', bpm: 72, mood: '고요한 물빛', scene: 'lake' },
  coast: { id: 'open-horizon', title: '수평선의 리듬', artist: 'Pleos Drive · 오리지널 앰비언스', bpm: 92, mood: '가벼운 바닷바람', scene: 'coast' },
  forest: { id: 'green-canopy', title: '초록의 숨', artist: 'Pleos Drive · 오리지널 앰비언스', bpm: 64, mood: '차분한 숲 그늘', scene: 'forest' },
  city: { id: 'evening-lights', title: '도시의 저녁', artist: 'Pleos Drive · 오리지널 앰비언스', bpm: 84, mood: '부드러운 도시의 빛', scene: 'city' },
};

export function selectSound(scene) {
  return { ...SOUNDS[validScene(scene)] };
}

/** Demo UI policy; vehicle integration must supply trustworthy restriction state.
 * Unknown input fails closed. Playback means play/pause/volume only.
 */
export function canInteract(state, action) {
  if (!state || typeof state.moving !== 'boolean'
    || (state.uxRestricted !== undefined && typeof state.uxRestricted !== 'boolean')
    || !['video', 'edit', 'browse', 'playback'].includes(action)) return false;
  return action === 'playback' || (!state.moving && !state.uxRestricted);
}
