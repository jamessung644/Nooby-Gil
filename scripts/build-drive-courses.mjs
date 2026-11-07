// Builds catalog/drive-courses.json from the TourAPI 여행코스 (contentTypeId 25) list.
// Usage: node --env-file=.env scripts/build-drive-courses.mjs
//
// A course is kept when it has at least three stops with coordinates and either
//   basis "title": its title says it is a drive / coastal-road / national-road course, or
//   basis "shape": its title names scenery (sea, lake, river, pass...) AND the stops are drive-scale
//                  (consecutive stops 2.5 km or more apart on average, laid out roughly along a line,
//                  at most 400 km in total), which separates a road from a walking or museum itinerary.
// The server reads the file offline.
import { existsSync, readFileSync, writeFileSync } from 'node:fs';

const raw = (process.env.TOUR_API_KEY || '').trim();
const key = /%[\da-f]{2}/i.test(raw) ? decodeURIComponent(raw) : raw;
if (!key) { console.error('TOUR_API_KEY is required'); process.exit(1); }
const arr = value => [].concat(value || []);
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
const DRIVE = /드라이브|해안도로|일주도로|국도|도로|로드|고갯길|해안길|해안\s*드라이브/;
const SCENERY = /해안|바다|호수|저수지|순환|일주|벚꽃길|단풍길|산길|고개|비경|절경|환상|누리길|둘레|전망|바닷길|달리|강을\s*따라|강\s*따라|물길|계곡/;
const WALKING = /걷|도보|트레킹|올레|둘레길|산책|등산|캠핑|체험|박물관|투어|기행/;

async function tour(endpoint, params) {
  const url = new URL(`https://apis.data.go.kr/B551011/KorService2/${endpoint}`);
  url.search = new URLSearchParams({ serviceKey: key, MobileOS: 'ETC', MobileApp: 'ScenicPleos', _type: 'json', pageNo: '1', numOfRows: '1000', ...params });
  for (let attempt = 0; attempt < 3; attempt++) {
    try { return await (await fetch(url, { signal: AbortSignal.timeout(30000) })).json(); } catch { await sleep(1000); }
  }
  return null;
}
const rad = d => d * Math.PI / 180;
const meters = (a, b) => {
  const x = rad(b.lat - a.lat), y = rad(b.lon - a.lon);
  const h = Math.sin(x / 2) ** 2 + Math.cos(rad(a.lat)) * Math.cos(rad(b.lat)) * Math.sin(y / 2) ** 2;
  return 12742000 * Math.asin(Math.sqrt(h));
};

// The list is paged; every page must be read or the tail of the catalogue is silently lost.
const list = [];
for (let page = 1; page <= 10; page++) {
  const body = (await tour('areaBasedList2', { contentTypeId: '25', pageNo: String(page) }))?.response?.body;
  list.push(...arr(body?.items?.item));
  if (list.length >= Number(body?.totalCount || 0)) break;
}
console.log('courses listed:', list.length);

const courses = [];
for (const course of list) {
  const byTitle = DRIVE.test(course.title);
  if (!byTitle && !(SCENERY.test(course.title) && !WALKING.test(course.title))) continue;
  const subs = arr((await tour('detailInfo2', { contentId: course.contentid, contentTypeId: '25' }))?.response?.body?.items?.item);
  const stops = [];
  for (const sub of subs) {
    const detail = arr((await tour('detailCommon2', { contentId: sub.subcontentid }))?.response?.body?.items?.item)[0];
    const lat = Number(detail?.mapy), lon = Number(detail?.mapx);
    if (lat > 33 && lat < 38.65 && lon > 124.5 && lon < 131.95) stops.push({ name: String(sub.subname || detail.title || '').slice(0, 100), lat, lon });
  }
  if (stops.length < 3) { console.log('skip   ', course.title, stops.length); continue; }
  let path = 0;
  for (let i = 1; i < stops.length; i++) path += meters(stops[i - 1], stops[i]);
  const extent = meters(stops[0], stops.at(-1)), average = path / (stops.length - 1);
  const driveScale = average >= 2500 && path <= 400000 && extent / path >= 0.55;
  if (!byTitle && !driveScale) { console.log('shape- ', course.title, `avg ${Math.round(average)}m line ${(extent / path).toFixed(2)}`); continue; }
  courses.push({ id: `tour-${course.contentid}`, title: String(course.title).slice(0, 120), basis: byTitle ? 'title' : 'shape', stops });
  console.log(byTitle ? 'title  ' : 'shape+ ', course.title, stops.length);
}
// The provider sometimes drops a detail call; a course found by an earlier run is kept rather than lost.
const target = new URL('../catalog/drive-courses.json', import.meta.url);
if (existsSync(target)) {
  const known = new Set(courses.map(course => course.id));
  for (const old of JSON.parse(readFileSync(target, 'utf8')).courses || []) if (!known.has(old.id)) courses.push({ basis: 'title', ...old });
}
writeFileSync(target, JSON.stringify({
  version: 2, fetchedAt: new Date().toISOString(), source: '한국관광공사 TourAPI 여행코스', courses,
}, null, 1));
console.log('courses written:', courses.length, '| title', courses.filter(c => c.basis === 'title').length, '| shape', courses.filter(c => c.basis === 'shape').length);

