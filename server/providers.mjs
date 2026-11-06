// Upstream contracts checked against official documentation on 2026-11-04.
// No secret is returned to the browser; only configuration status is exposed.
export class ApiError extends Error {
  constructor(message,status=400){super(message);this.status=status;}
}
const isObject=value=>value!==null&&typeof value==='object'&&!Array.isArray(value);
export function validateCoordinate(lat,lon){
  if(typeof lat!=='number'||typeof lon!=='number'||!Number.isFinite(lat)||!Number.isFinite(lon)||Math.abs(lat)>90||Math.abs(lon)>180) throw new ApiError('유효한 위도·경도를 입력해 주세요.');
}
export function providerStatus(env=process.env){
  return {
    weather:{configured:true,label:'Open-Meteo · 키 없이 사용'},
    routing:{configured:!!env.KAKAO_REST_API_KEY,label:env.KAKAO_REST_API_KEY?'Kakao Mobility · 서버 연결 준비':'Kakao Mobility REST 키 필요'},
    tourism:{configured:!!env.TOUR_API_KEY,label:env.TOUR_API_KEY?'한국관광공사 TourAPI · 서버 연결 준비':'TourAPI 인증키 필요'},
    ai:{configured:!!env.OPENAI_API_KEY,label:env.OPENAI_API_KEY?'OpenAI · 서버 연결 준비':'OpenAI API 키 연결 필요 · 기본 추천 사용 가능'},
    vehicle:{configured:false,label:'웹은 실차 미연결 · Android 별도 연동'},
    camera:{configured:false,label:'차량 영상 API 미확인 · 사용자 영상 사용'}
  };
}
export function buildKakaoUrl(input={}){
  const stops=isObject(input)?input.stops:undefined;
  if(!Array.isArray(stops)||stops.length<2||stops.length>7) throw new ApiError('출발·도착과 최대 5개 경유지를 선택해 주세요.');
  for(const s of stops) validateCoordinate(s?.lat,s?.lon);
  const coord=s=>`${s.lon},${s.lat}`; // Kakao X,Y; application data is lat,lon.
  const u=new URL('https://apis-navi.kakaomobility.com/v1/directions');
  u.search=new URLSearchParams({origin:coord(stops[0]),destination:coord(stops.at(-1)),priority:'RECOMMEND',summary:'false',alternatives:'false',roadevent:'0'});
  if(stops.length>2)u.searchParams.set('waypoints',stops.slice(1,-1).map(coord).join('|'));
  return u;
}
export function normalizeKakao(data){
  const route=data?.routes?.[0];
  if(!route||route.result_code!==0)throw new ApiError('도로 경로를 찾지 못했습니다. 출발지와 경유지를 확인해 주세요.',502);
  const coordinates=[];
  if(!Array.isArray(route.sections))throw new ApiError('길찾기 응답 경로 형식이 올바르지 않습니다.',502);
  for(const section of route.sections){
    if(!Array.isArray(section?.roads))throw new ApiError('길찾기 응답 경로 형식이 올바르지 않습니다.',502);
    for(const road of section.roads){
      const v=road?.vertexes;
      if(!Array.isArray(v)||v.length%2)throw new ApiError('길찾기 응답 좌표 형식이 올바르지 않습니다.',502);
      for(let i=0;i<v.length;i+=2){
        try{validateCoordinate(v[i+1],v[i]);}catch{throw new ApiError('길찾기 응답 좌표가 유효하지 않습니다.',502);}
        const last=coordinates.at(-1);
        if(!last||last[0]!==v[i+1]||last[1]!==v[i])coordinates.push([v[i+1],v[i]]);
      }
    }
  }
  const distance=route.summary?.distance,duration=route.summary?.duration;
  if(coordinates.length<2||!Number.isFinite(distance)||distance<0||!Number.isFinite(duration)||duration<0)throw new ApiError('길찾기 응답에 경로 정보가 부족합니다.',502);
  return {coordinates,distanceKm:distance/1000,durationMin:duration/60,source:'Kakao Mobility',fetchedAt:new Date().toISOString()};
}
async function upstreamJson(url,options,fetchImpl){
  const signal=AbortSignal.timeout(10000);
  try{
    const r=await fetchImpl(url,{...options,signal,redirect:'error'});
    if(!r.ok)throw new ApiError(r.status===401||r.status===403?'API 인증 또는 서비스 사용 권한을 확인해 주세요.':'외부 서비스 응답에 실패했습니다. 잠시 후 다시 시도해 주세요.',502);
    return await r.json();
  }catch(e){
    if(e instanceof ApiError)throw e;
    if(signal.aborted)throw new ApiError('외부 API 조회 시간이 초과되었습니다. 잠시 후 다시 시도해 주세요.',504);
    // Never return raw network errors: providers may embed key-bearing URLs.
    throw new ApiError('외부 API 연결이 지연되거나 응답 형식이 올바르지 않습니다.',502);
  }
}
export async function fetchRoadRoute(input,{env=process.env,fetchImpl=fetch}={}){
  if(!env.KAKAO_REST_API_KEY)throw new ApiError('KAKAO_REST_API_KEY가 설정되지 않았습니다. 현재는 샘플 코스로 이용할 수 있습니다.',503);
  const url=buildKakaoUrl(input);
  return normalizeKakao(await upstreamJson(url,{headers:{Authorization:`KakaoAK ${env.KAKAO_REST_API_KEY}`}},fetchImpl));
}
export function buildTourUrl({lat,lon,radiusMeters=10000}={},key){
  validateCoordinate(lat,lon);
  if(!Number.isInteger(radiusMeters)||radiusMeters<1||radiusMeters>20000)throw new ApiError('관광지 탐색 반경은 1~20,000m입니다.');
  // data.go.kr supplies both encoded and decoded keys. URLSearchParams performs
  // the sole transport encoding; preserve literal '+' in a decoded key.
  let serviceKey=typeof key==='string'?key.trim():'';
  try{if(serviceKey.includes('%'))serviceKey=decodeURIComponent(serviceKey);}catch{throw new ApiError('TourAPI 인증키 형식을 확인해 주세요.',503);}
  if(!serviceKey)throw new ApiError('TourAPI 인증키를 설정해 주세요.',503);
  const u=new URL('https://apis.data.go.kr/B551011/KorService2/locationBasedList2');
  u.search=new URLSearchParams({serviceKey,MobileOS:'ETC',MobileApp:'ScenicPleos',_type:'json',numOfRows:'30',pageNo:'1',arrange:'E',mapX:String(lon),mapY:String(lat),radius:String(radiusMeters),contentTypeId:'12'});
  return u;
}
export function normalizeTour(data){
  const response=data?.response;
  if(response?.header?.resultCode!=='0000')throw new ApiError('관광정보를 가져오지 못했습니다. 인증키와 서비스 승인을 확인해 주세요.',502);
  if(!isObject(response.body))throw new ApiError('관광정보 응답 형식이 올바르지 않습니다.',502);
  const raw=response?.body?.items?.item;
  if(raw!=null&&raw!==''&&!Array.isArray(raw)&&!isObject(raw))throw new ApiError('관광정보 응답 형식이 올바르지 않습니다.',502);
  const items=Array.isArray(raw)?raw:raw?[raw]:[];
  return items.filter(x=>x&&x.mapx!=null&&x.mapy!=null&&String(x.mapx).trim()!==''&&String(x.mapy).trim()!=='').flatMap(x=>{
    const lat=Number(x.mapy),lon=Number(x.mapx);
    try{validateCoordinate(lat,lon);}catch{return [];}
    return [{id:String(x.contentid),title:String(x.title||'관광지'),address:String(x.addr1||''),lat,lon,source:'한국관광공사 TourAPI'}];
  });
}
export async function fetchPlaces(input,{env=process.env,fetchImpl=fetch}={}){
  if(!env.TOUR_API_KEY)throw new ApiError('TOUR_API_KEY가 설정되지 않았습니다. 한국관광공사 API 활용 신청이 필요합니다.',503);
  return normalizeTour(await upstreamJson(buildTourUrl(input,env.TOUR_API_KEY),{},fetchImpl));
}
