import { readFileSync } from 'node:fs';

// Drive courses introduced by 한국관광공사 TourAPI (여행코스). A person or an agency picked the
// stops and the order, so a road that follows them is a recommended road, not a computed one.
// catalog/drive-courses.json is produced by scripts/build-drive-courses.mjs.
const COAST = /해안|바다|해변|해금강|동해|서해|남해|일주|7번\s*국도|낙조|문탠/;
const WATER = /호수|저수지|강|계곡|물줄기/;

/** 'coast', 'water' or 'other', judged from the course title. */
export function courseTerrain(title) {
  return COAST.test(title) ? 'coast' : WATER.test(title) ? 'water' : 'other';
}

let cached;
/** Returns the shipped courses, or an empty list when the file is missing or malformed. Never throws. */
export function loadDriveCourses(file = new URL('../catalog/drive-courses.json', import.meta.url)) {
  if (cached && cached.href === file.href) return cached.courses;
  let courses = [];
  try {
    const data = JSON.parse(readFileSync(file, 'utf8'));
    // Courses kept only because their stops look drive-scale (basis "shape") include food and
    // experience itineraries, so only courses titled as drive courses are offered. The shape courses
    // stay in the file for validation work.
    courses = (Array.isArray(data.courses) ? data.courses : []).filter(course => course?.basis !== 'shape' && /^[A-Za-z0-9_-]{1,80}$/.test(course?.id)
      && typeof course.title === 'string' && Array.isArray(course.stops) && course.stops.length >= 3
      && course.stops.every(stop => typeof stop?.name === 'string' && stop.name && Number.isFinite(stop.lat) && Number.isFinite(stop.lon)))
      .map(course => ({ ...course, terrain: courseTerrain(course.title) }));
  } catch { courses = []; }
  cached = { href: file.href, courses };
  return courses;
}
