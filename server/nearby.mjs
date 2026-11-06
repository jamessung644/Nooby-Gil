import { ApiError } from './providers.mjs';

// Kakao Local category contract: https://developers.kakao.com/docs/ko/local/dev-guide#search-by-category
const categories = { restaurant: 'FD6', parking: 'PK6' };
const clean = (value, max, optional = false) => typeof value === 'string'
  && value.length <= max && (optional || value.trim().length > 0)
  && !/[\u0000-\u001f\u007f-\u009f]/.test(value);
const coordinate = (lat, lon) => typeof lat === 'number' && typeof lon === 'number'
  && Number.isFinite(lat) && Number.isFinite(lon) && lat >= 33 && lat <= 38.65 && lon >= 124.5 && lon <= 131.95;

export async function searchNearby(input, { env = process.env, fetchImpl = fetch } = {}) {
  if (!input || Array.isArray(input) || typeof input !== 'object'
    || Object.keys(input).some(key => !['kind', 'lat', 'lon'].includes(key))
    || !Object.hasOwn(categories, input.kind) || !coordinate(input.lat, input.lon)) {
    throw new ApiError('국내 기준 위치와 음식점 또는 주차장을 선택해 주세요.');
  }
  const key = typeof env.KAKAO_REST_API_KEY === 'string' ? env.KAKAO_REST_API_KEY.trim() : '';
  if (!key) throw new ApiError('주변 장소 검색을 위한 Kakao REST API 키가 필요합니다.', 503);
  const url = new URL('https://dapi.kakao.com/v2/local/search/category.json');
  url.search = new URLSearchParams({ category_group_code: categories[input.kind], x: String(input.lon), y: String(input.lat), radius: '3000', sort: 'distance', size: '10' });
  const controller = new AbortController();
  let timer;
  try {
    const data = await Promise.race([
      (async () => {
        const response = await fetchImpl(url, { headers: { Authorization: `KakaoAK ${key}` }, redirect: 'error', signal: controller.signal });
        if (!response.ok) throw new ApiError(response.status === 401 || response.status === 403
          ? '카카오 API 인증 또는 서비스 권한을 확인해 주세요.' : '주변 장소를 불러오지 못했어요. 다시 시도해 주세요.', 502);
        return response.json();
      })(),
      new Promise((_resolve, reject) => { timer = setTimeout(() => { controller.abort(); reject(new ApiError('주변 장소 조회 시간이 초과됐어요. 다시 시도해 주세요.', 504)); }, 12000); }),
    ]);
    if (!Array.isArray(data?.documents)) throw new ApiError('주변 장소 검색 응답을 확인하지 못했어요.', 502);
    const places = [], ids = new Set();
    for (const item of data.documents) {
      if (!item || typeof item !== 'object' || typeof item.id !== 'string' || !/^[0-9]{1,100}$/.test(item.id)
        || ids.has(item.id) || !clean(item.place_name, 120)
        || typeof item.x !== 'string' || !item.x.trim() || typeof item.y !== 'string' || !item.y.trim()
        || !coordinate(Number(item.y), Number(item.x))
        || typeof item.distance !== 'string' || !/^\d+(?:\.\d+)?$/.test(item.distance)) continue;
      const distanceMeters = Math.round(Number(item.distance));
      if (!Number.isFinite(distanceMeters) || distanceMeters > 3000) continue;
      if (item.category_group_code && item.category_group_code !== categories[input.kind]) continue;
      const address = [item.road_address_name, item.address_name].find(value => clean(value, 300)) || '';
      ids.add(item.id);
      places.push({ id: item.id, name: item.place_name.trim(), address, lat: Number(item.y), lon: Number(item.x), distanceMeters,
        phone: clean(item.phone, 40, true) && /^[0-9+() -]*$/.test(item.phone) ? item.phone : '',
        placeUrl: `https://place.map.kakao.com/${item.id}`, parkingStatus: 'unknown' });
      if (places.length === 10) break;
    }
    places.sort((a, b) => a.distanceMeters - b.distanceMeters);
    return { source: 'Kakao Local', kind: input.kind, places };
  } catch (error) {
    if (error instanceof ApiError) throw error;
    throw new ApiError('주변 장소 서비스에 연결하지 못했어요. 다시 시도해 주세요.', 502);
  } finally { clearTimeout(timer); }
}
