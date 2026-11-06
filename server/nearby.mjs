import { ApiError } from './providers.mjs';
import { enrichParkingPlaces } from './parking-live.mjs';

// Kakao Local category contract: https://developers.kakao.com/docs/ko/local/dev-guide#search-by-category
const categories = { restaurant: 'FD6', parking: 'PK6' };
// A name label is evidence for ordering, never proof of access or availability.
const publicParkingLabel = name => /공영|공공|시영|구영|군영/.test(name);
const clean = (value, max, optional = false) => typeof value === 'string'
  && value.length <= max && (optional || value.trim().length > 0)
  && !/[\u0000-\u001f\u007f-\u009f]/.test(value);
const coordinate = (lat, lon) => typeof lat === 'number' && typeof lon === 'number'
  && Number.isFinite(lat) && Number.isFinite(lon) && lat >= 33 && lat <= 38.65 && lon >= 124.5 && lon <= 131.95;

export async function searchNearby(input, { env = process.env, fetchImpl = fetch, now = Date.now } = {}) {
  if (!input || Array.isArray(input) || typeof input !== 'object'
    || Object.keys(input).some(key => !['kind', 'lat', 'lon', 'query', 'focusId'].includes(key))
    || !Object.hasOwn(categories, input.kind) || !coordinate(input.lat, input.lon)
    || (input.query !== undefined && (!clean(input.query, 40) || input.kind !== 'restaurant'))
    || (input.focusId !== undefined && (input.kind !== 'parking' || typeof input.focusId !== 'string' || !/^[0-9]{1,100}$/.test(input.focusId)))) {
    throw new ApiError('국내 기준 위치와 음식점 또는 주차장을 선택해 주세요.');
  }
  const key = typeof env.KAKAO_REST_API_KEY === 'string' ? env.KAKAO_REST_API_KEY.trim() : '';
  if (!key) throw new ApiError('주변 장소 검색을 위한 Kakao REST API 키가 필요합니다.', 503);
  const query = input.query?.trim() || '';
  const url = new URL(`https://dapi.kakao.com/v2/local/search/${query ? 'keyword' : 'category'}.json`);
  url.search = new URLSearchParams({ x: String(input.lon), y: String(input.lat), radius: '3000', sort: query ? 'accuracy' : 'distance', size: query || input.kind === 'parking' ? '15' : '10' });
  // Keyword search must include cafés such as a region's coffee/bakery venues.
  // Kakao accepts one category filter; preserve one relevance ordering by
  // searching the keyword then accepting only its explicit FD6/CE7 records.
  if (query) url.searchParams.set('query', query);
  else url.searchParams.set('category_group_code', categories[input.kind]);
  const controller = new AbortController();
  let timer;
  try {
    // One deadline covers all Kakao pages. Dense districts can have fifteen
    // private lots before the first public facility with official inventory.
    const deadline = new Promise((_resolve, reject) => { timer = setTimeout(() => { controller.abort(); reject(new ApiError('주변 장소 조회 시간이 초과됐어요. 다시 시도해 주세요.', 504)); }, 12000); });
    const page = async number => {
      const requestUrl = new URL(url);
      if (input.kind === 'parking') requestUrl.searchParams.set('page', String(number));
      const response = await fetchImpl(requestUrl, { headers: { Authorization: `KakaoAK ${key}` }, redirect: 'error', signal: controller.signal });
      if (!response.ok) throw new ApiError(response.status === 401 || response.status === 403
        ? '카카오 API 인증 또는 서비스 권한을 확인해 주세요.' : '주변 장소를 불러오지 못했어요. 다시 시도해 주세요.', 502);
      const data = await response.json();
      if (!Array.isArray(data?.documents)) throw new ApiError('주변 장소 검색 응답을 확인하지 못했어요.', 502);
      return data;
    };
    let data = await Promise.race([page(1), deadline]);
    const documents = input.kind === 'parking' ? data.documents.slice(0, 15) : data.documents;
    if (input.kind === 'parking') {
      for (let number = 2; number <= 3 && data.meta?.is_end === false && !controller.signal.aborted; number++) {
        try {
          data = await Promise.race([page(number), deadline]);
          documents.push(...data.documents.slice(0, 15));
        } catch { break; /* A failed optional page must not discard earlier valid results. */ }
      }
    }
    clearTimeout(timer); controller.abort();
    let places = []; const ids = new Set();
    for (const item of documents) {
      if (!item || typeof item !== 'object' || typeof item.id !== 'string' || !/^[0-9]{1,100}$/.test(item.id)
        || ids.has(item.id) || !clean(item.place_name, 120)
        || typeof item.x !== 'string' || !item.x.trim() || typeof item.y !== 'string' || !item.y.trim()
        || !coordinate(Number(item.y), Number(item.x))
        || typeof item.distance !== 'string' || !/^\d+(?:\.\d+)?$/.test(item.distance)) continue;
      const distanceMeters = Math.round(Number(item.distance));
      if (!Number.isFinite(distanceMeters) || distanceMeters > 3000) continue;
      if (query ? !['FD6', 'CE7'].includes(item.category_group_code)
        : item.category_group_code && item.category_group_code !== categories[input.kind]) continue;
      const address = [item.road_address_name, item.address_name].find(value => clean(value, 300)) || '';
      ids.add(item.id);
      places.push({ id: item.id, name: item.place_name.trim(), address, lat: Number(item.y), lon: Number(item.x), distanceMeters,
        category: clean(item.category_name, 180, true) ? item.category_name.trim() : '',
        phone: clean(item.phone, 40, true) && /^[0-9+() -]*$/.test(item.phone) ? item.phone : '',
        placeUrl: `https://place.map.kakao.com/${item.id}`, parkingStatus: 'unknown' });
      if (places.length === 10 && input.kind !== 'parking') break;
    }
    if (input.kind === 'parking') places = await enrichParkingPlaces(places, { fetchImpl, now });
    const availabilityOrder = { available: 0, unknown: 1, full: 2 };
    // Keyword relevance is Kakao's returned order, not an invented quality score.
    if (!query) places.sort((a, b) => (input.kind === 'parking' ? availabilityOrder[a.parkingStatus] - availabilityOrder[b.parkingStatus]
      || Number(publicParkingLabel(b.name)) - Number(publicParkingLabel(a.name)) : 0)
      || a.distanceMeters - b.distanceMeters || a.id.localeCompare(b.id));
    return { source: 'Kakao Local', kind: input.kind, query, ranking: query ? 'relevance' : input.kind === 'parking' ? 'availability_then_public_label_then_distance' : 'distance',
      places: places.slice(0, 10), ...(input.kind === 'parking' ? { focus: places.find(place => place.id === input.focusId) || null } : {}) };
  } catch (error) {
    if (error instanceof ApiError) throw error;
    throw new ApiError('주변 장소 서비스에 연결하지 못했어요. 다시 시도해 주세요.', 502);
  } finally { clearTimeout(timer); }
}
