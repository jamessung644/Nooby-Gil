import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { buildCatalog, generateJava, distanceKm, landGroup, normalizePlace, isCourseEligible, MAX_DIAMETER_KM, MAX_LEG_KM } from '../scripts/build-nationwide-catalog.mjs';

const catalog=JSON.parse(await readFile(new URL('../catalog/nationwide.json',import.meta.url),'utf8'));
const snapshot=JSON.parse(await readFile(new URL('../catalog/tourapi-snapshot.json',import.meta.url),'utf8'));
const places=new Map(catalog.places.map(p=>[p.id,p]));

test('prepared catalog covers all 17 tourism regions with hundreds of distinct combinations',()=>{
  assert.equal(catalog.regions.length,17);
  assert.ok(catalog.courses.length>=500);
  assert.equal(new Set(catalog.courses.map(c=>c.id)).size,catalog.courses.length);
  assert.equal(catalog.stats.courseCount,catalog.courses.length);
  assert.equal(catalog.stats.placeCount,catalog.places.length);
  for(const region of catalog.regions){
    const courses=catalog.courses.filter(c=>c.regionId===region.id);
    assert.ok(courses.length>=30,`${region.name} has useful coverage`);
    assert.ok(courses.every(c=>c.regionName===region.name));
    assert.ok(new Set(courses.flatMap(c=>c.spotIds)).size>=15,`${region.name} is not just repeated permutations of a handful of places`);
  }
});

test('every POI retains a real TourAPI content ID and matches the fetched snapshot exactly',()=>{
  const original=new Map(snapshot.places.map(p=>[p.id,p]));
  assert.equal(places.size,catalog.places.length);
  assert.equal(catalog.source.provider,'한국관광공사 TourAPI');
  assert.equal(catalog.source.courseStatus,'generated-combinations-not-official-tours');
  assert.ok(Number.isFinite(Date.parse(catalog.source.fetchedAt)));
  assert.ok(snapshot.requests.length>=16);
  for(const p of catalog.places){
    assert.match(p.id,/^\d+$/);assert.deepEqual(p,original.get(p.id));
    assert.equal(p.contentTypeId,'12');assert.ok(p.title&&p.address&&p.districtId&&p.sourceRegionId);
    assert.ok(p.lat>=33&&p.lat<=39&&p.lon>=124&&p.lon<=132);
    assert.ok(isCourseEligible(p));
    assert.ok(p.sourceUrl.startsWith('https://www.data.go.kr/'));
    assert.ok(!('image' in p)&&!('overview' in p));
  }
  assert.ok(!JSON.stringify(catalog).includes('serviceKey='));
  assert.ok(!JSON.stringify(snapshot).includes('serviceKey='));
});

test('courses stay within one district and land group and have close, distinct stops',()=>{
  const sets=new Set();
  for(const c of catalog.courses){
    assert.ok(c.spotIds.length>=2&&c.spotIds.length<=4);
    assert.equal(new Set(c.spotIds).size,c.spotIds.length);
    const stops=c.spotIds.map(id=>{assert.ok(places.has(id));return places.get(id);});
    const signature=[...c.spotIds].sort().join(',');
    assert.ok(!sets.has(signature),'reversing or reordering stops must not inflate course count');sets.add(signature);
    assert.ok(stops.every(p=>p.regionId===c.regionId&&p.districtId===c.districtId&&p.landGroup===c.landGroup&&landGroup(p)));
    for(let i=0;i<stops.length;i++)for(let j=i+1;j<stops.length;j++){
      const d=distanceKm(stops[i],stops[j]);assert.ok(d>=0.25&&d<=MAX_DIAMETER_KM);
    }
    const legs=stops.slice(1).map((p,i)=>distanceKm(stops[i],p));
    assert.ok(legs.every(d=>d<=MAX_LEG_KM));
    assert.ok(legs.reduce((n,d)=>n+d,0)<=35);
    assert.ok(c.id.length<=64&&c.title.length<=200&&c.subtitle.length<=200);
  }
});

test('estimated metrics are reproducible and explicitly do not claim routed ETAs',()=>{
  for(const c of catalog.courses){
    const stops=c.spotIds.map(id=>places.get(id));
    const direct=stops.slice(1).reduce((n,p,i)=>n+distanceKm(stops[i],p),0);
    assert.equal(c.distanceKm,Math.round(direct*1.35*10)/10);
    assert.equal(c.minutes,Math.ceil((c.distanceKm/30*60+stops.length*15)/5)*5);
    assert.equal(c.metricKind,'heuristic');assert.equal(c.routeVerified,false);
    assert.ok(c.minutes>0&&c.minutes<=720);
    assert.ok(Math.abs(c.centerLat-stops.reduce((n,p)=>n+p.lat,0)/stops.length)<1e-6);
    assert.ok(Math.abs(c.centerLon-stops.reduce((n,p)=>n+p.lon,0)/stops.length)<1e-6);
  }
});

test('minor stop additions and subsets do not inflate the prepared catalog',()=>{
  for(const region of catalog.regions){
    const courses=catalog.courses.filter(c=>c.regionId===region.id);
    for(let i=0;i<courses.length;i++)for(let j=i+1;j<courses.length;j++){
      const a=courses[i].spotIds,b=courses[j].spotIds,overlap=a.filter(id=>b.includes(id)).length;
      assert.ok(a.length+b.length-2*overlap>=2);
    }
    const usage=new Map();
    for(const c of courses)for(const id of c.spotIds)usage.set(id,(usage.get(id)||0)+1);
    assert.ok([...usage.values()].every(n=>n<=6));
  }
});

test('remote islands and invalid coordinates are excluded conservatively',()=>{
  for(const address of ['제주특별자치도 제주시 우도면','제주특별자치도 제주시 추자면','인천광역시 옹진군','전남광주통합특별시 신안군','경상북도 울릉군']){
    assert.equal(landGroup({address,title:'관광지',regionId:'50',lon:126.5}),null);
  }
  const fixture={contentid:'123',title:'공원',addr1:'서울특별시 종로구',lDongRegnCd:'11',lDongSignguCd:'110',contenttypeid:'12',mapx:'127',mapy:'37.5'};
  assert.equal(normalizePlace({...fixture,mapx:''}),null);
  assert.equal(normalizePlace({...fixture,mapy:'0'}),null);
  assert.equal(normalizePlace({...fixture,lDongRegnCd:'12',lDongSignguCd:'210'}).regionId,'29');
  assert.equal(normalizePlace({...fixture,lDongRegnCd:'12',lDongSignguCd:'110'}).regionId,'46');
  assert.equal(normalizePlace({...fixture,lDongRegnCd:'36110',lDongSignguCd:'36110'}).regionId,'36');
});

test('summits and hiking-only classes do not masquerade as drive destinations',()=>{
  const p={title:'공원',landGroup:'mainland',contentTypeId:'12',classification3:'VE030100'};
  assert.equal(isCourseEligible(p),true);
  for(const classification3 of ['NA010100','NA010300','VE040300'])assert.equal(isCourseEligible({...p,classification3}),false);
  for(const title of ['관음사국기봉전망대','설악산 정상','남한산성 탐방로','호수 둘레길','국민의 숲 트레킹 코스'])assert.equal(isCourseEligible({...p,title}),false);
});

test('checked-in JSON and Android offline data regenerate from the saved source without network',async()=>{
  assert.deepEqual(buildCatalog(snapshot),catalog);
  const java=await readFile(new URL('../android/app/src/main/java/com/scenic/pleos/NationwideCatalog.java',import.meta.url),'utf8');
  assert.equal(generateJava(catalog),java);
  assert.equal((java.match(/new CourseEngine\.Course\(/g)||[]).length,catalog.courses.length);
  assert.ok((java.match(/private static void fill/g)||[]).length>1,'split Java methods keep generated bytecode below the method size limit');
});
