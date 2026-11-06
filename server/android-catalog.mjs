import { readFileSync } from 'node:fs';
import { ApiError } from './providers.mjs';

// Compatibility entries for already installed native clients. New nationwide
// courses come exclusively from the shared, prepared source catalog below.
const legacyAndroidRoutes = [
  {
    id: 'chuncheon', title: '춘천, 물빛을 따라서', region: '춘천', theme: 'lake',
    durationMin: 85, distanceKm: 42,
    description: '호수와 숲 사이로 이어지는 느린 오후',
    tags: ['호수', '숲', '의암호', '의암호 스카이워크', '공지천', '소양강 스카이워크'],
  },
  {
    id: 'gangneung', title: '강릉, 파도 옆으로', region: '강릉', theme: 'coast',
    durationMin: 110, distanceKm: 36,
    description: '바닷바람이 흐르는 동해안의 장면들',
    tags: ['바다', '해안', '안목해변', '경포해변', '사천해변', '영진해변'],
  },
  {
    id: 'seoul', title: '서울, 빛이 흐르는 밤', region: '서울', theme: 'city',
    durationMin: 60, distanceKm: 28,
    description: '한강과 도시의 불빛이 만드는 리듬',
    tags: ['도시', '야경', '반포한강공원', '잠원한강공원', '서울숲', '뚝섬한강공원'],
  },
];

const CATALOG_URL = new URL('../catalog/nationwide.json', import.meta.url);
const THEMES = { lake: 'lake', sea: 'coast', night: 'city', forest: 'forest' };
const isObject = (value) => value !== null && typeof value === 'object' && !Array.isArray(value);
const isText = (value, max = 200) => typeof value === 'string' && value.trim().length > 0 && value.length <= max;
const isId = (value) => isText(value, 64) && /^[a-zA-Z0-9_-]+$/.test(value);
const coordinate = (value, limit) => Number.isFinite(value) && Math.abs(value) <= limit;
const invalidCatalog = () => new ApiError('준비된 코스 데이터를 불러올 수 없습니다. 잠시 후 다시 시도해 주세요.', 503);
const requireValue = (condition) => { if (!condition) throw invalidCatalog(); };
function freeze(value) {
  if (value && typeof value === 'object') {
    for (const child of Object.values(value)) freeze(child);
    Object.freeze(value);
  }
  return value;
}

// Validation and an explicit projection prevent unexpected source fields from
// entering either the public API or the AI's trusted metadata. Counts are derived
// from validated records, never copied from unverified manifest statistics.
export function parsePreparedCatalog(raw) {
  requireValue(isObject(raw) && (isText(raw.version, 64) || Number.isSafeInteger(raw.version))
    && isText(raw.generatedAt, 64) && Number.isFinite(Date.parse(raw.generatedAt))
    && Array.isArray(raw.regions) && raw.regions.length > 0 && raw.regions.length <= 100
    && Array.isArray(raw.places) && raw.places.length > 0 && raw.places.length <= 100000
    && Array.isArray(raw.courses) && raw.courses.length > 0 && raw.courses.length <= 10000
    && isObject(raw.source));
  const regionIds = new Set();
  const regions = raw.regions.map((region) => {
    requireValue(isObject(region) && isId(region.id) && isText(region.name, 20) && !regionIds.has(region.id));
    regionIds.add(region.id);
    return { id: region.id, name: region.name };
  });
  const regionNames = new Map(regions.map(({ id, name }) => [id, name]));
  const placeIds = new Set();
  const places = raw.places.map((place) => {
    requireValue(isObject(place) && isId(place.id) && !placeIds.has(place.id)
      && isText(place.title) && typeof place.address === 'string' && place.address.length <= 300
      && coordinate(place.lat, 90) && coordinate(place.lon, 180) && regionIds.has(place.regionId)
      && (place.districtId === undefined || typeof place.districtId === 'string')
      && (place.contentTypeId === undefined || typeof place.contentTypeId === 'string'));
    placeIds.add(place.id);
    return {
      id: place.id, title: place.title, address: place.address, lat: place.lat, lon: place.lon,
      regionId: place.regionId, districtId: place.districtId ?? '', contentTypeId: place.contentTypeId ?? '',
    };
  });
  const courseIds = new Set();
  const courses = raw.courses.map((course) => {
    requireValue(isObject(course) && isId(course.id) && !courseIds.has(course.id)
      && isText(course.title) && typeof course.subtitle === 'string' && course.subtitle.length <= 200
      && regionIds.has(course.regionId) && course.regionName === regionNames.get(course.regionId)
      && typeof course.theme === 'string' && Object.hasOwn(THEMES, course.theme)
      && Number.isInteger(course.minutes) && course.minutes >= 1 && course.minutes <= 720
      && Number.isFinite(course.distanceKm) && course.distanceKm >= 0 && course.distanceKm <= 2000
      && Array.isArray(course.spotIds) && course.spotIds.length >= 2 && course.spotIds.length <= 12
      && new Set(course.spotIds).size === course.spotIds.length && course.spotIds.every((id) => placeIds.has(id))
      && coordinate(course.centerLat, 90) && coordinate(course.centerLon, 180));
    courseIds.add(course.id);
    return {
      id: course.id, title: course.title, subtitle: course.subtitle, regionId: course.regionId,
      regionName: course.regionName, theme: course.theme, minutes: course.minutes, distanceKm: course.distanceKm,
      spotIds: [...course.spotIds], centerLat: course.centerLat, centerLon: course.centerLon,
    };
  });
  const { provider, attribution, metricMethod } = raw.source;
  requireValue(isText(provider, 200) && isText(attribution, 1000) && isText(metricMethod, 1000));
  return freeze({
    version: raw.version, generatedAt: raw.generatedAt,
    source: { provider, attribution, metricMethod }, regions, places, courses,
    stats: { courseCount: courses.length, placeCount: places.length, regionCount: regions.length },
  });
}

let preparedCatalog;
let androidRoutes;
export function getPreparedCatalog() {
  if (!preparedCatalog) {
    try { preparedCatalog = parsePreparedCatalog(JSON.parse(readFileSync(CATALOG_URL, 'utf8'))); }
    catch { throw invalidCatalog(); }
  }
  return preparedCatalog;
}

export function getAndroidRoutes() {
  if (!androidRoutes) {
    const catalog = getPreparedCatalog();
    const placesById = new Map(catalog.places.map((place) => [place.id, place]));
    const preparedRoutes = catalog.courses.map((course) => ({
      id: course.id, title: course.title, region: course.regionName, theme: THEMES[course.theme],
      durationMin: course.minutes, distanceKm: course.distanceKm, description: course.subtitle,
      tags: [course.regionName, ...course.spotIds.map((id) => placesById.get(id).title)],
    }));
    const combined = [...legacyAndroidRoutes, ...preparedRoutes];
    requireValue(new Set(combined.map((route) => route.id)).size === combined.length);
    androidRoutes = freeze(combined);
  }
  return androidRoutes;
}
