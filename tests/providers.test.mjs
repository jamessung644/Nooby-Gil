import test from 'node:test';
import assert from 'node:assert/strict';
import { buildKakaoUrl, normalizeKakao, buildTourUrl, normalizeTour, providerStatus, fetchRoadRoute } from '../server/providers.mjs';

test('Kakao coordinates are explicitly longitude,latitude and include interior stops', () => {
  const u=buildKakaoUrl({stops:[{lat:37.8,lon:127.7},{lat:37.9,lon:127.8},{lat:38,lon:127.9}]});
  assert.equal(u.searchParams.get('origin'),'127.7,37.8');
  assert.equal(u.searchParams.get('destination'),'127.9,38');
  assert.equal(u.searchParams.get('waypoints'),'127.8,37.9');
});
test('invalid or excessive route requests are rejected instead of silently truncating', () => {
  assert.throws(()=>buildKakaoUrl({stops:[{lat:127,lon:37},{lat:38,lon:128}]}));
  assert.throws(()=>buildKakaoUrl({stops:Array.from({length:8},()=>({lat:37,lon:127}))}));
});
test('route geometry and units are converted without fabricating missing geometry', () => {
  const r=normalizeKakao({routes:[{result_code:0,summary:{distance:4200,duration:600},sections:[{roads:[{vertexes:[127,37,128,38]}]}]}]});
  assert.deepEqual(r.coordinates,[[37,127],[38,128]]);
  assert.equal(r.distanceKm,4.2); assert.equal(r.durationMin,10);
  assert.throws(()=>normalizeKakao({routes:[{result_code:104,result_msg:'no route'}]}));
  assert.throws(()=>normalizeKakao({routes:[{result_code:0,summary:{distance:0,duration:0},sections:[]}]}));
});
test('provider status reveals only configuration booleans, never keys', () => {
  const status=providerStatus({KAKAO_REST_API_KEY:'sensitive-value'});
  assert.equal(status.routing.configured,true);
  assert.equal(status.tourism.configured,false);
  assert.ok(!JSON.stringify(status).includes('sensitive-value'));
});
test('missing key is a clear configuration error and makes no upstream call', async () => {
  await assert.rejects(fetchRoadRoute({stops:[]},{env:{},fetchImpl:()=>{throw new Error('must not call');}}),e=>e.status===503);
});
test('routing deadline is distinct from provider rejection and does not retry or expose details', async (t) => {
  const controller=new AbortController();
  t.mock.method(AbortSignal,'timeout',milliseconds=>{
    assert.equal(milliseconds,10000);
    return controller.signal;
  });
  let calls=0;
  const pending=fetchRoadRoute({stops:[{lat:37,lon:127},{lat:38,lon:128}]},{
    env:{KAKAO_REST_API_KEY:'test-private-key'},
    fetchImpl:async(_url,{signal})=>{
      calls++;
      return {ok:true,json:()=>new Promise((_resolve,reject)=>signal.addEventListener('abort',()=>reject(signal.reason),{once:true}))};
    }
  });
  const rejected=assert.rejects(pending,error=>error.status===504&&/시간/.test(error.message)&&!error.message.includes('test-private-key'));
  await new Promise(resolve=>setImmediate(resolve));
  controller.abort(new DOMException('test-private-key','TimeoutError'));
  await rejected;
  assert.equal(calls,1);
});
test('TourAPI URL validates bounds and encodes a raw decoded key exactly once', () => {
  const u=buildTourUrl({lat:37.5,lon:127,radiusMeters:1000},'abc+def/=');
  assert.equal(u.searchParams.get('serviceKey'),'abc+def/=');
  assert.equal(u.searchParams.get('mapX'),'127');
  assert.throws(()=>buildTourUrl({lat:37,lon:127,radiusMeters:999999},'x'));
});
test('TourAPI accepts the portal encoded key without double encoding or changing raw plus signs', () => {
  for(const key of ['abc+def/=', 'abc%2Bdef%2F%3D']) {
    const u=buildTourUrl({lat:37.5,lon:127},key);
    assert.equal(u.protocol,'https:');
    assert.equal(u.searchParams.get('serviceKey'),'abc+def/=');
    assert.ok(!u.search.includes('%252B'));
  }
  assert.throws(()=>buildTourUrl({lat:37.5,lon:127},'broken%2'),e=>e.status===503&&!e.message.includes('broken'));
});
test('TourAPI singleton item normalizes and excludes invalid coordinates', () => {
  const result=normalizeTour({response:{header:{resultCode:'0000'},body:{items:{item:{contentid:'42',title:'명소',mapx:'127',mapy:'37',addr1:'주소'}}}}});
  assert.equal(result.length,1); assert.equal(result[0].lat,37);
  assert.throws(()=>normalizeTour({response:{header:{resultCode:'30',resultMsg:'DENIED'}}}));
  assert.deepEqual(normalizeTour({response:{header:{resultCode:'0000'},body:{items:{item:[{mapx:null,mapy:null},{mapx:' ',mapy:'37'}]}}}}),[]);
});
