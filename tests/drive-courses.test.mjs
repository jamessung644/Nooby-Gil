import test from 'node:test';
import assert from 'node:assert/strict';
import { courseTerrain, loadDriveCourses } from '../server/drive-courses.mjs';
import { planDestinationRoutes, selectCuratedCandidates } from '../server/destination-routes.mjs';

const env = { KAKAO_REST_API_KEY: 'private-test-key' };
const trip = { origin: { lat: 35.1, lon: 129 }, destination: { id: 'd', name: '목적지', lat: 35.1, lon: 129.4 }, extraMinutes: 60, theme: 'coast' };
const baseline = Array.from({ length: 41 }, (_, i) => [35.1, 129 + i * 0.01]);
const stop = (name, lon, lat = 35.12) => ({ name, lat, lon });
const course = (id, title, stops, terrain) => ({ id, title, stops, terrain: terrain || courseTerrain(title) });
const coastCourse = course('tour-1', '테스트 해안도로', [stop('가', 129.1), stop('나', 129.2), stop('다', 129.3)]);

function kakaoRoad(coordinates, seconds, distance) {
  return Response.json({ routes: [{ result_code: 0, summary: { distance, duration: seconds }, sections: [{ roads: [{ name: '도로', distance, duration: seconds, traffic_speed: 30, traffic_state: 2, vertexes: coordinates.flatMap(([lat, lon]) => [lon, lat]) }] }] }] });
}
const fetchImpl = async url => {
  const waypoints = new URL(url).searchParams.get('waypoints');
  if (!waypoints) return kakaoRoad(baseline, 1800, 36000);
  const via = waypoints.split('|').map(pair => pair.split(',').map(Number)).map(([lon, lat]) => [lat, lon]);
  return kakaoRoad([baseline[0], ...via, baseline.at(-1)], 2400, 40000);
};
const fastRoute = { coordinates: baseline, distanceKm: 36 };

test('a stretch of a published course along the way is offered in the course direction', () => {
  const [match] = selectCuratedCandidates({ ...trip, fast: fastRoute }, [coastCourse]);
  assert.deepEqual(match.stops.map(s => s.name), ['가', '다']);
  assert.ok(match.stops[0].progress < match.stops[1].progress);
  assert.ok(match.stops.every(s => /^tour-1-\d$/.test(s.id) && s.source === '한국관광공사 TourAPI'));
});

test('a course running against the direction of travel is used in reverse', () => {
  const reversed = course('tour-2', '반대 해안도로', [stop('다', 129.3), stop('나', 129.2), stop('가', 129.1)]);
  const [match] = selectCuratedCandidates({ ...trip, fast: fastRoute }, [reversed]);
  assert.ok(match.stops[0].lon < match.stops[1].lon);
});

test('courses far from the way, too short, or of another kind are not offered', () => {
  const far = course('tour-3', '먼 해안도로', [stop('가', 129.1, 35.6), stop('나', 129.2, 35.6), stop('다', 129.3, 35.6)]);
  const tiny = course('tour-4', '짧은 해안도로', [stop('가', 129.1), stop('나', 129.1005), stop('다', 129.101)]);
  const lake = course('tour-5', '호수 드라이브', [stop('가', 129.1), stop('나', 129.2), stop('다', 129.3)]);
  assert.deepEqual(selectCuratedCandidates({ ...trip, fast: fastRoute }, [far, tiny, lake]), []);
  assert.equal(selectCuratedCandidates({ ...trip, theme: 'water', fast: fastRoute }, [lake]).length, 1);
  assert.deepEqual(selectCuratedCandidates({ ...trip, fast: fastRoute }, 'not a list'), []);
});

test('the planner returns the recommended-course road before the computed scenic road', async () => {
  const result = await planDestinationRoutes(trip, { fetchImpl, env, catalog: { places: [] }, courses: [coastCourse] });
  assert.equal(result.scenicStatus, 'ok');
  assert.deepEqual(result.routes.map(r => r.id), ['fast', 'curated']);
  const curated = result.routes[1];
  assert.equal(curated.title, '추천 코스 길');
  assert.equal(curated.waypoints.length, 2);
  assert.ok(curated.scenicLabels.includes('추천 코스') && new Set(curated.scenicLabels).size === curated.scenicLabels.length);
  assert.ok(curated.reason.includes('테스트 해안도로') && curated.reason.length <= 500);
});

test('both a recommended-course road and a computed scenic road are offered together', async () => {
  const places = [{ id: 'a-first', title: '해안 풍경 하나', lat: 35.13, lon: 129.35 }];
  const result = await planDestinationRoutes(trip, { fetchImpl, env, catalog: { places }, courses: [coastCourse] });
  assert.deepEqual(result.routes.map(r => r.id), ['fast', 'curated', 'scenic']);
});

test('a course whose road exceeds the extra-time budget is dropped and the answer stays honest', async () => {
  const result = await planDestinationRoutes({ ...trip, extraMinutes: 5 }, { fetchImpl, env, catalog: { places: [] }, courses: [coastCourse] });
  assert.deepEqual(result.routes.map(r => r.id), ['fast']);
  assert.notEqual(result.scenicStatus, 'ok');
});

test('the shipped course file loads, has coordinates in Korea and a terrain for every course', () => {
  const courses = loadDriveCourses();
  assert.ok(courses.length >= 20, `courses ${courses.length}`);
  assert.ok(courses.every(c => c.stops.length >= 3 && ['coast', 'water', 'other'].includes(c.terrain)));
  assert.deepEqual(loadDriveCourses(new URL('file:///definitely-not-here.json')), []);
  assert.ok(courses.every(c => c.basis !== 'shape'), 'courses chosen only by shape are not offered');
});
