import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const ROOT = fileURLToPath(new URL('../', import.meta.url));
export const REGIONS = [
  ['11','서울','1'],['26','부산','6'],['27','대구','4'],['28','인천','2'],['29','광주','5'],
  ['30','대전','3'],['31','울산','7'],['36','세종','8'],['41','경기','31'],['43','충북','33'],
  ['44','충남','34'],['46','전남','38'],['47','경북','35'],['48','경남','36'],['50','제주','39'],
  ['51','강원','32'],['52','전북','37']
].map(([id,name,legacyAreaCode]) => ({id,name,legacyAreaCode}));
const REGION_MAP = new Map(REGIONS.map(r => [r.id,r]));
const GWANGJU_DISTRICTS = new Set(['210','240','270','300','330']);
export const COURSE_QUOTA = 40;
export const MAX_LEG_KM = 15;
export const MAX_DIAMETER_KM = 20;
const SOURCE = {
  provider:'한국관광공사 TourAPI',
  endpoint:'https://apis.data.go.kr/B551011/KorService2/areaBasedList2',
  documentationUrl:'https://www.data.go.kr/data/15101578/openapi.do',
  attribution:'관광지 기초정보: 한국관광공사 TourAPI. 코스 조합: Scenic Pleos 자체 생성.',
  metricMethod:'명소 간 직선거리 합 × 1.35를 거리로, 그 거리를 시속 30km로 이동하고 명소마다 15분 체류하는 값을 시간으로 추정. 실제 도로 경로·교통·출발지 접근시간은 미포함.',
  courseStatus:'generated-combinations-not-official-tours',
  regionConvention:'areaCode2의 17개 관광권역을 유지한다. 현재 법정동코드 12의 동구·서구·남구·북구·광산구는 광주, 나머지는 전남 관광권역으로 분류한다. 세종 36110은 관광권역 36으로 매핑한다.',
  licensingNote:'명칭·주소·좌표·콘텐츠 ID·분류 등 기초정보만 저장한다. 이미지와 상세 소개문은 포함하지 않는다. 원자료 이용조건은 공공데이터포털 원문을 따른다.',
  destinationPolicy:'산·봉우리(NA010100), 폭포(NA010300), 트레킹 코스(VE040300) 및 정상·국기봉·등산로·둘레길·탐방로 명칭은 차량 목적지로 오해되지 않도록 제외한다. 남은 관광지도 주차 위치와 진입 가능 여부를 검증한 목적지는 아니다.',
  islandPolicy:'같은 시군구와 같은 육지/주요 섬 그룹 안에서만 조합한다. 섬 단독 분류, 알려진 도서지역 주소, 불확실한 도서 구간은 보수적으로 제외한다. 통행·교량·주차 가능 여부는 별도 길찾기로 확인해야 한다.'
};
const compareId = (a,b) => a.id.localeCompare(b.id, 'en');
const clean = value => String(value ?? '').replace(/<[^>]*>/g,'').replace(/\s+/g,' ').trim();
export function distanceKm(a,b) {
  const r=Math.PI/180, p=(b.lat-a.lat)*r, l=(b.lon-a.lon)*r;
  const h=Math.sin(p/2)**2+Math.cos(a.lat*r)*Math.cos(b.lat*r)*Math.sin(l/2)**2;
  return 6371*2*Math.atan2(Math.sqrt(h),Math.sqrt(Math.max(0,1-h)));
}
function tourRegion(item) {
  const source=clean(item.lDongRegnCd);
  if(source==='12')return GWANGJU_DISTRICTS.has(clean(item.lDongSignguCd))?'29':'46';
  if(source==='36110')return '36';
  return REGION_MAP.has(source)?source:null;
}
export function landGroup(p) {
  const text=`${p.address} ${p.title}`;
  // Remote islands remain legitimate TourAPI POIs, but are not combined as road tours.
  if(p.classification3==='NA020500' || /옹진군|신안군|완도군|울릉군|추자면|우도면|서도면|삼산면|조도면|흑산면|낙월면|위도면|옥도면|화정면|경호동|욕지면|한산면|사량면|봉산면?\s*추도|오천면.*(외연도|녹도|호도|원산도|삽시도|효자도|장고도|고대도)|근흥면.*가의도|서면.*마량리.*(동백정|해돋이)/.test(text))return null;
  if(/여수시 남면|통영시 산양읍|남해군 삼동면 물건리.*수우도|고흥군.*(시산|득량|연홍)도|제주시 한림읍.*(비양도|협재리.*섬)/.test(text))return null;
  if(p.regionId==='50')return 'jeju-main';
  if(/거제시/.test(p.address))return 'geoje-main';
  if(/남해군/.test(p.address))return /창선면/.test(p.address)?'changseon':'namhae-main';
  if(/진도군/.test(p.address))return 'jindo-main';
  if(/강화군/.test(p.address))return /교동면/.test(p.address)?'gyodong':'ganghwa-main';
  if(/중구/.test(p.address)&&p.regionId==='28') {
    return p.lon<126.60 ? (/무의|소무의/.test(text)?'muui':'yeongjong') : 'mainland';
  }
  if(/영도구/.test(p.address))return 'yeongdo';
  if(/가덕도|천성동|대항동|성북동/.test(p.address)&&p.regionId==='26')return 'gadeok';
  if(/고흥군/.test(p.address)&&/봉래면|동일면/.test(p.address))return /봉래면/.test(p.address)?'outer-naro':'inner-naro';
  return 'mainland';
}
export function normalizePlace(item, districtNames={}) {
  const regionId=tourRegion(item), lat=Number(item.mapy),lon=Number(item.mapx);
  if(!regionId||!item.contentid||!item.title||!item.addr1||!String(item.mapx??'').trim()||!String(item.mapy??'').trim()||!Number.isFinite(lat)||!Number.isFinite(lon)||lat<33||lat>39||lon<124||lon>132)return null;
  const sourceRegionId=clean(item.lDongRegnCd),districtId=clean(item.lDongSignguCd);
  if(!districtId)return null;
  const p={id:clean(item.contentid),title:clean(item.title),address:clean(item.addr1),lat,lon,regionId,districtId,
    districtName:districtNames[`${sourceRegionId}:${districtId}`]|| (regionId==='36'?'세종':clean(item.addr1).split(' ')[1]),
    sourceRegionId,contentTypeId:clean(item.contenttypeid),classification1:clean(item.lclsSystm1),classification2:clean(item.lclsSystm2),classification3:clean(item.lclsSystm3),modifiedAt:clean(item.modifiedtime),
    sourceUrl:SOURCE.documentationUrl};
  p.landGroup=landGroup(p);
  return p;
}
async function requestTour(endpoint,params,key) {
  const url=new URL(`https://apis.data.go.kr/B551011/KorService2/${endpoint}`);
  url.search=new URLSearchParams({serviceKey:key,MobileOS:'ETC',MobileApp:'ScenicPleos',_type:'json',pageNo:'1',numOfRows:'300',...params});
  for(let attempt=0;attempt<3;attempt++) {
    try {
      const res=await fetch(url,{signal:AbortSignal.timeout(30000),redirect:'error'});
      if(!res.ok)throw new Error('upstream-status');
      const json=await res.json();
      if(json.response?.header?.resultCode!=='0000')throw new Error('upstream-code');
      return json.response.body;
    } catch { if(attempt===2)throw new Error(`TourAPI ${endpoint} request failed; authentication, permission or connectivity must be checked (raw request suppressed).`); }
  }
}
export async function fetchSnapshot(rawKey) {
  if(!rawKey)throw new Error('TOUR_API_KEY is required for --fetch.');
  let key;try{key=/%[\da-f]{2}/i.test(rawKey)?decodeURIComponent(rawKey):rawKey;}catch{throw new Error('TOUR_API_KEY encoding is invalid.');}
  const array=b=>Array.isArray(b.items?.item)?b.items.item:b.items?.item?[b.items.item]:[];
  const sourceRegions=array(await requestTour('ldongCode2',{},key)).map(x=>({id:clean(x.code),name:clean(x.name)}));
  const legacyRegions=array(await requestTour('areaCode2',{},key)).map(x=>({id:clean(x.code),name:clean(x.name)}));
  const places=[],districtNames={},requests=[];
  for(const region of sourceRegions) {
    const districts=array(await requestTour('ldongCode2',{lDongRegnCd:region.id},key));
    for(const d of districts)districtNames[`${region.id}:${d.code}`]=clean(d.name);
    const records=[];
    for(let page=1; ; page++) {
      const body=await requestTour('areaBasedList2',{lDongRegnCd:region.id,contentTypeId:'12',arrange:'A',pageNo:String(page)},key);
      const rows=array(body);records.push(...rows);
      requests.push({endpoint:'areaBasedList2',sourceRegionId:region.id,pageNo:page,returned:rows.length,totalCount:Number(body.totalCount)});
      // This is a bounded prepared sample, not a claim to enumerate all tourism records.
      if(page>=1||records.length>=Number(body.totalCount)||rows.length===0)break;
    }
    places.push(...records.map(x=>normalizePlace(x,districtNames)).filter(Boolean));
    console.log(`Fetched ${region.name}: ${records.length} sightseeing records`);
  }
  const unique=[...new Map(places.map(p=>[p.id,p])).values()].sort(compareId);
  return {version:1,fetchedAt:new Date().toISOString(),source:SOURCE,sourceRegions,legacyRegions,requests,places:unique};
}
function classify(stops) {
  if(stops.some(p=>p.classification2==='NA02'||/해변|해수욕장|해안|바다|포구|방파제/.test(p.title)))return 'sea';
  if(stops.some(p=>p.classification1==='NA'||/공원|숲|수목원|호수|저수지|계곡|폭포|강변|수변/.test(p.title)))return 'lake';
  return 'night'; // Product's third scene means urban/culture, not verified night opening/lighting.
}
function shortestOrder(stops) {
  let best=null,bestDistance=Infinity;
  const permute=(left,prefix)=>{
    if(!left.length){const d=prefix.slice(1).reduce((sum,p,i)=>sum+distanceKm(prefix[i],p),0);const code=prefix.map(p=>p.id).join(',');if(d<bestDistance-1e-9||(Math.abs(d-bestDistance)<1e-9&&code<best.map(p=>p.id).join(','))){best=prefix;bestDistance=d;}return;}
    for(let i=0;i<left.length;i++)permute([...left.slice(0,i),...left.slice(i+1)],[...prefix,left[i]]);
  };
  permute(stops,[]);return {stops:best,straightDistanceKm:bestDistance};
}
export function isCourseEligible(p) {
  return !!p.landGroup&&p.contentTypeId==='12'
    &&!['NA010100','NA010300','VE040300'].includes(p.classification3)
    &&!/폐쇄|폐업|운영종료|운영 종료|휴장|정상|국기봉|등산로|등산길|등산코스|둘레길|탐방로|탐방길|트레킹|트래킹|광석대|입석대|서석대/.test(p.title);
}
export function makeCourses(places,quota=COURSE_QUOTA) {
  const eligible=places.filter(isCourseEligible).sort(compareId);
  const buckets=new Map();
  for(const p of eligible){const k=`${p.regionId}:${p.districtId}:${p.landGroup}`;if(!buckets.has(k))buckets.set(k,[]);buckets.get(k).push(p);}
  const all=[];
  for(const region of REGIONS){
    const groups=[];
    for(const [groupId,ps] of buckets){
      if(ps[0].regionId!==region.id||ps.length<2)continue;
      const seen=new Set(),candidates=[];
      for(const anchor of ps){
        const near=ps.filter(p=>p.id!==anchor.id).map(p=>({p,d:distanceKm(anchor,p)})).filter(x=>x.d>=0.25&&x.d<=MAX_LEG_KM).sort((a,b)=>a.d-b.d||compareId(a.p,b.p)).slice(0,7).map(x=>x.p);
        const shapes=[];
        for(let a=0;a<near.length;a++){
          shapes.push([anchor,near[a]]);
          for(let b=a+1;b<near.length;b++){
            shapes.push([anchor,near[a],near[b]]);
            if(b+1<near.length)shapes.push([anchor,near[a],near[b],near[b+1]]);
          }
        }
        for(const set of shapes){
          const key=set.map(p=>p.id).sort().join('-');if(seen.has(key))continue;seen.add(key);
          if(set.some((p,i)=>set.slice(i+1).some(q=>distanceKm(p,q)<0.25||distanceKm(p,q)>MAX_DIAMETER_KM)))continue;
          const ordered=shortestOrder(set);if(ordered.straightDistanceKm<1||ordered.straightDistanceKm>35||ordered.stops.slice(1).some((p,i)=>distanceKm(p,ordered.stops[i])>MAX_LEG_KM))continue;
          candidates.push({key,...ordered,groupId});
        }
      }
      if(candidates.length)groups.push({id:groupId,candidates,selected:0});
    }
    groups.sort((a,b)=>a.id.localeCompare(b.id,'en'));
    const selected=[],use=new Map();
    while(selected.length<quota){
      let winner=null,winnerGroup=null,winnerScore=Infinity;
      for(const group of groups){
        for(const c of group.candidates){
          if(c.used)continue;
          // Exclude set permutations and trivial one-stop extensions/subsets.
          if(selected.some(s=>{const overlap=c.stops.filter(p=>s.stops.some(q=>q.id===p.id)).length;return c.stops.length+s.stops.length-2*overlap<2;}))continue;
          if(c.stops.some(p=>(use.get(p.id)||0)>=6))continue;
          const repeats=c.stops.reduce((n,p)=>n+(use.get(p.id)||0),0);
          const desiredSize=2+selected.length%3;
          const score=group.selected*1000+repeats*40+Math.abs(c.stops.length-desiredSize)*12+Math.abs(c.straightDistanceKm-8)*0.3;
          if(score<winnerScore-1e-9||(Math.abs(score-winnerScore)<1e-9&&c.key<(winner?.key||''))){winner=c;winnerGroup=group;winnerScore=score;}
        }
      }
      if(!winner)break;
      winner.used=true;winnerGroup.selected++;selected.push(winner);
      for(const p of winner.stops)use.set(p.id,(use.get(p.id)||0)+1);
    }
    if(selected.length<quota)throw new Error(`${region.name}: only ${selected.length} sufficiently distinct local courses, requested ${quota}.`);
    for(const c of selected){
      const first=c.stops[0],last=c.stops.at(-1),km=Math.round(c.straightDistanceKm*1.35*10)/10;
      const minutes=Math.ceil((km/30*60+c.stops.length*15)/5)*5;
      const districtName=first.districtName||region.name;
      all.push({id:`kr-${region.id}-${createHash('sha256').update(c.key).digest('hex').slice(0,12)}`,
        title:`${first.title}에서 ${last.title}까지`,subtitle:`${region.name} ${districtName} · ${c.stops.length}개 명소 · 거리·시간 추정`,
        regionId:region.id,regionName:region.name,districtId:first.districtId,districtName,landGroup:first.landGroup,
        theme:classify(c.stops),minutes,distanceKm:km,straightDistanceKm:Math.round(c.straightDistanceKm*1000)/1000,
        centerLat:Number((c.stops.reduce((n,p)=>n+p.lat,0)/c.stops.length).toFixed(7)),centerLon:Number((c.stops.reduce((n,p)=>n+p.lon,0)/c.stops.length).toFixed(7)),
        spotIds:c.stops.map(p=>p.id),metricKind:'heuristic',routeVerified:false});
    }
  }
  return all.sort((a,b)=>a.regionId.localeCompare(b.regionId,'en')||a.id.localeCompare(b.id,'en'));
}
export function buildCatalog(snapshot) {
  const courses=makeCourses(snapshot.places),used=new Set(courses.flatMap(c=>c.spotIds));
  const places=snapshot.places.filter(p=>used.has(p.id)).sort(compareId);
  return {version:1,generatedAt:snapshot.fetchedAt,source:{...SOURCE,fetchedAt:snapshot.fetchedAt,sourceRegions:snapshot.sourceRegions,legacyRegions:snapshot.legacyRegions},regions:REGIONS,places,courses,
    stats:{regionCount:REGIONS.length,courseCount:courses.length,placeCount:places.length,sourcePlaceCount:snapshot.places.length,districtCount:new Set(courses.map(c=>`${c.regionId}:${c.districtId}`)).size,
      byRegion:REGIONS.map(r=>({id:r.id,name:r.name,courses:courses.filter(c=>c.regionId===r.id).length,places:places.filter(p=>p.regionId===r.id).length,districts:new Set(courses.filter(c=>c.regionId===r.id).map(c=>c.districtId)).size}))}};
}
const javaString=value=>JSON.stringify(String(value)).replace(/\u2028/g,'\\u2028').replace(/\u2029/g,'\\u2029');
export function generateJava(catalog) {
  const places=new Map(catalog.places.map(p=>[p.id,p]));
  const lines=['package com.scenic.pleos;','','/** Generated from catalog/nationwide.json by scripts/build-nationwide-catalog.mjs.',
    ' * Tourist POIs: Korea Tourism Organization TourAPI. Combinations and metrics are heuristic.',
    ' * No network is required. Do not edit this generated file. */','public final class NationwideCatalog {',
    `    public static final int COURSE_COUNT = ${catalog.courses.length};`,
    `    public static final int PLACE_COUNT = ${catalog.places.length};`,
    `    public static final String FETCHED_AT = ${javaString(catalog.source.fetchedAt)};`,
    '    public static CourseEngine.Course[] courses() {','        CourseEngine.Course[] result = new CourseEngine.Course[COURSE_COUNT];'];
  const size=12,chunks=Math.ceil(catalog.courses.length/size);
  for(let i=0;i<chunks;i++)lines.push(`        fill${i}(result);`);
  lines.push('        return result;','    }');
  for(let i=0;i<chunks;i++){
    lines.push(`    private static void fill${i}(CourseEngine.Course[] result) {`);
    for(let j=i*size;j<Math.min((i+1)*size,catalog.courses.length);j++){
      const c=catalog.courses[j],tag={lake:'자연 · 산책',sea:'바다 · 해안',night:'도시 · 문화'}[c.theme];
      lines.push(`        result[${j}] = new CourseEngine.Course(${[c.id,c.title,c.subtitle,c.theme,tag,`약 ${c.distanceKm} km (추정)`].map(javaString).join(', ')}, ${c.minutes}, ${javaString(c.regionId)}, ${javaString(c.regionName)}, ${c.centerLat}, ${c.centerLon},`);
      for(let k=0;k<c.spotIds.length;k++){
        const p=places.get(c.spotIds[k]);
        lines.push(`            new CourseEngine.Spot(${javaString(p.title)}, ${javaString(`${p.address} · 한국관광공사 TourAPI`)}, ${p.lat}, ${p.lon})${k===c.spotIds.length-1?');':','}`);
      }
    }
    lines.push('    }');
  }
  lines.push('    private NationwideCatalog() {}','}','');return lines.join('\n');
}
async function main(){
  const dir=path.join(ROOT,'catalog');await mkdir(dir,{recursive:true});
  const snapshotPath=path.join(dir,'tourapi-snapshot.json');
  let snapshot;
  if(process.argv.includes('--fetch')){
    snapshot=await fetchSnapshot(process.env.TOUR_API_KEY);
    await writeFile(snapshotPath,JSON.stringify(snapshot,null,2)+'\n');
  }else snapshot=JSON.parse(await readFile(snapshotPath,'utf8'));
  const catalog=buildCatalog(snapshot);
  await writeFile(path.join(dir,'nationwide.json'),JSON.stringify(catalog,null,2)+'\n');
  await writeFile(path.join(ROOT,'android/app/src/main/java/com/scenic/pleos/NationwideCatalog.java'),generateJava(catalog));
  console.log(JSON.stringify(catalog.stats,null,2));
}
if(process.argv[1]&&path.resolve(process.argv[1])===fileURLToPath(import.meta.url))main().catch(e=>{console.error(e.message);process.exitCode=1;});
