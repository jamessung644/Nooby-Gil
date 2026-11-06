const fallbackStatus={
  weather:{configured:true,label:'Open-Meteo · 키 없이 사용'},
  routing:{configured:false,label:'Kakao Mobility REST 키 필요'},
  tourism:{configured:false,label:'한국관광공사 TourAPI 인증키 필요'},
  ai:{configured:false,label:'OpenAI API 키 연결 필요 · 기본 추천 사용 가능'},
  vehicle:{configured:false,label:'실차 미연결 · Android 별도 연동'},
  camera:{configured:false,label:'차량 원본 영상 미연결 · 사용자 영상 사용'}
};
async function json(url,options={},timeoutMs=12000){
  const res=await fetch(url,{...options,signal:AbortSignal.timeout(timeoutMs)});
  const type=res.headers.get('content-type')||'';
  if(!type.includes('application/json'))throw new Error('API 서버가 연결되지 않았습니다. 소스 폴더에서 npm start로 실행해 주세요.');
  const body=await res.json();
  if(!res.ok)throw new Error(body.error||'서비스 연결에 실패했습니다.');
  return body;
}
export async function getProviderStatus(){
  try{return await json('/api/status');}catch{return structuredClone(fallbackStatus);}
}
export async function getWeather(lat,lon){
  if(!Number.isFinite(lat)||!Number.isFinite(lon)||Math.abs(lat)>90||Math.abs(lon)>180)throw new Error('날씨 위치가 유효하지 않습니다.');
  const u=new URL('https://api.open-meteo.com/v1/forecast');
  // Only selected public route coordinates are sent unless the user explicitly requests their location's weather.
  u.search=new URLSearchParams({latitude:String(lat),longitude:String(lon),current:'temperature_2m,weather_code,wind_speed_10m,is_day',timezone:'Asia/Seoul'});
  const d=await json(u); const c=d.current;
  if(!c||!Number.isFinite(c.temperature_2m))throw new Error('현재 날씨 데이터가 없습니다.');
  return {temperature:c.temperature_2m,weatherCode:c.weather_code,windSpeed:c.wind_speed_10m,isDay:c.is_day===1,observedAt:c.time,source:'Open-Meteo'};
}
export async function getRoadRoute(route){
  return json('/api/route',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({stops:route.stops.map(({lat,lon})=>({lat,lon}))})});
}
export async function searchPlaces({lat,lon,radiusMeters=10000}){
  return json(`/api/places?${new URLSearchParams({lat,lon,radiusMeters})}`);
}
export async function recommendWithAI(routeIds,preferences){
  return json('/api/recommend',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({routeIds,preferences})},20000);
}
