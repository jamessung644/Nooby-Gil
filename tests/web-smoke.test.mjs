import fs from 'node:fs';
import test from 'node:test';
import assert from 'node:assert/strict';
import {routes,dataNotice,fixtureSources} from '../web/data.js';
import {rankRoutes,projectProgress,selectSound,canInteract,haversineKm} from '../web/core.js';
// This test exercises rendering/action contracts with mocked DOM/media/APIs. It is not browser visual, codec, GPS, or camera verification.
test('web UI contracts with mocked browser APIs', async (t) => {
const handlers={document:{},window:{}};
const elements=new Map();
function element(id){if(!elements.has(id))elements.set(id,{innerHTML:'',textContent:'',value:'',open:false,style:{},classList:{toggle(){}},setAttribute(){},removeAttribute(){},pause(){},load(){},play(){return Promise.resolve();},showModal(){this.open=true;},close(){this.open=false;},addEventListener(event,fn){this[event]=fn;}});return elements.get(id);}
const fixed=new Set(['#main-content','#clock','#location-label','#mode-label','#drive-mode-toggle','#main-nav','#weather-label','#toast-region','#clip-player','#video-dialog','#video-dialog-title','#close-video','#location-icon','#audio-file-input','#video-file-input']);
globalThis.document={querySelector(selector){if(fixed.has(selector))return element(selector);if(selector.startsWith('#')&&element('#main-content').innerHTML.includes(`id="${selector.slice(1)}"`))return element(selector);return null;},addEventListener(event,fn){(handlers.document[event]??=[]).push(fn);},createElement(tag){return {...element(tag),click(){}};},getElementById(id){return this.querySelector('#'+id);}};
globalThis.window={addEventListener(event,fn){(handlers.window[event]??=[]).push(fn);},isSecureContext:true,scrollTo(){}};
globalThis.location={hash:'#explore',protocol:'file:'};
globalThis.localStorage={getItem(){return null;},setItem(){}};
Object.defineProperty(globalThis,'navigator',{value:{geolocation:{watchPosition(){return 12;},clearWatch(){}}},configurable:true});
const a={source:'ambient',scene:'lake',volume:.35,playing:false,unlocked:false};
const audioEngine={setVolume(v){a.volume=v;},setScene(s){a.scene=s;},getState(){return {...a};},async start(s){a.scene=s;a.playing=true;},async play(){a.playing=true;},pause(){a.playing=false;},cleanup(){a.playing=false;a.source='ambient';},async loadFile(){a.source='file';}};
let stored=[];
let roadResolve,aiResolve;const aiCalls=[];
const deps={routes,dataNotice,fixtureSources,rankRoutes,projectProgress,selectSound,canInteract,haversineKm,createAudioEngine:()=>audioEngine,createCameraRecorder:()=>({getState:()=>({}),cleanup(){}}),loadPreferences:()=>({selectedId:"jeju-coast",region:"춘천"}),savePreferences:()=>true,listClips:async()=>stored,saveClip:async x=>stored.push(x),deleteClip:async()=>{},getWeather:async()=>({temperature:20,weatherCode:0,source:'mock',observedAt:'test'}),getProviderStatus:async()=>({routing:{configured:false},tourism:{configured:false}}),getRoadRoute:()=>new Promise(resolve=>{roadResolve=resolve;}),searchPlaces:async()=>[],recommendWithAI:(ids,preferences)=>{aiCalls.push({ids,preferences});return new Promise(resolve=>{aiResolve=resolve;});},setTimeout:()=>0,clearTimeout:()=>{},setInterval:()=>1,clearInterval:()=>{}};
const src=fs.readFileSync(new URL('../web/app.js',import.meta.url),'utf8').replace(/^import .*;\n/gm,'');
const app=Function(...Object.keys(deps),src+'\nreturn {state,render,navigate,startGPS,startSimulation,setMoving,stopTracking,selectRoute,renderSettings,getRanked,placesSection,applyFilters};')(...Object.values(deps));
const checks=[];
function check(label,fn){fn();checks.push(label);}
check('initial explore render',()=>{assert.equal(app.state.selected.region,'춘천');assert.match(element('#main-content').innerHTML,/코스 탐색/);assert.match(element('#main-content').innerHTML,/의암호, 물빛을 따라/);});
for(const view of ['explore','journey','drive','memories','settings'])check('render '+view,()=>{app.state.view=view;app.render();assert(element('#main-content').innerHTML.length>1500);assert(!element('#main-content').innerHTML.includes('[object Promise]'));});
check('region filter uses shared rank',()=>{app.state.filters.region='제주';assert(app.getRanked().every(r=>r.region==='제주'));});
check('driving locks memories and settings',()=>{app.state.moving=true;app.state.view='memories';app.render();assert.match(element('#main-content').innerHTML,/영상은 정차한 뒤에/);assert(!element('#main-content').innerHTML.includes('data-action="upload-video"'));app.state.view='settings';app.render();assert(!element('#main-content').innerHTML.includes('id="auto-sound-setting"'));});
check('camera permission pending blocks driving',()=>{app.state.moving=false;app.state.cameraPending=true;app.setMoving(true);assert.equal(app.state.moving,false);});
check('camera permission pending blocks navigation',()=>{app.state.view='memories';app.navigate('journey');assert.equal(app.state.view,'memories');app.state.cameraPending=false;});
check('GPS locks before location reply',()=>{app.state.view='drive';app.startGPS();assert.equal(app.state.moving,true);assert.equal(app.state.gpsWatch,12);app.setMoving(false);assert.equal(app.state.gpsWatch,null);});
check('route selection resets route-specific async state',()=>{app.state.roadLoading=true;app.state.placesLoading=true;app.state.roadRoute={coordinates:[]};app.selectRoute('jeju-coast',false);assert.equal(app.state.selected.id,'jeju-coast');assert.equal(app.state.roadLoading,false);assert.equal(app.state.placesLoading,false);assert.equal(app.state.roadRoute,null);});
check('all view HTML has provenance',()=>{app.state.view='journey';app.render();assert.match(element('#main-content').innerHTML,/실제 도로 안내/);assert.match(element('#main-content').innerHTML,/관광정보 API로 조회/);});
check('zero filter results never show previous hero recommendation',()=>{app.state.view='explore';app.state.filters={region:'춘천',theme:'all',maxMinutes:45,query:''};app.render();assert(!element('#main-content').innerHTML.includes('class="hero"'));assert.match(element('#main-content').innerHTML,/조건에 맞는 길을 아직 찾지/);});
check('simulation clears actual road geometry',()=>{app.state.roadRoute={coordinates:[[1,2],[3,4]]};app.startSimulation();assert.equal(app.state.roadRoute,null);assert.equal(app.state.mapMode,'diagram');app.stopTracking();app.setMoving(false);});
app.state.view='journey';app.selectRoute('chuncheon-lake',false);
const pendingRoad=handlers.document.click[0]({target:{closest(){return {disabled:false,dataset:{action:'road-route'}};}}});
app.selectRoute('jeju-coast',false);
roadResolve({coordinates:[[1,2],[3,4]],source:'mock old route'});
await pendingRoad;
check('late road response cannot overwrite changed selection',()=>{assert.equal(app.state.selected.id,'jeju-coast');assert.equal(app.state.roadRoute,null);assert.equal(app.state.roadLoading,false);});

check('BFCache restoration rerenders stopped playback and parked state',()=>{app.state.moving=true;a.playing=true;handlers.window.pagehide[0]();assert.equal(a.playing,false);handlers.window.pageshow[0]({persisted:true});assert.equal(app.state.moving,false);assert.match(element('#mode-label').textContent,/정차/);});
app.state.view='explore';app.state.filters={region:'all',theme:'all',maxMinutes:180,query:''};app.render();
const click=action=>handlers.document.click[0]({target:{closest(){return {disabled:false,dataset:{action}};}}});
const firstAI=click('ai-recommend');
check('AI request sends only candidate IDs and preferences',()=>{assert(aiCalls.length===1);assert(aiCalls[0].ids.length>0);assert(!('gps' in aiCalls[0].preferences));assert(!('video' in aiCalls[0].preferences));});
aiResolve({source:'OpenAI',model:'mock-model',generatedAt:'2026-11-04T00:00:00Z',recommendations:[{routeId:'jeju-coast',reason:'<script>not executable</script> 열린 바다 풍경을 추천합니다.'}]});
await firstAI;
check('successful AI displays real-source fields and escaped reason',()=>{assert.equal(app.state.selected.id,'jeju-coast');assert.equal(app.state.aiResult.source,'OpenAI');assert.match(element('#main-content').innerHTML,/mock-model/);assert.match(element('#main-content').innerHTML,/&lt;script&gt;/);});
const secondAI=click('ai-recommend');
app.state.filters.region='춘천';app.applyFilters();
aiResolve({source:'OpenAI',model:'mock-model',recommendations:[{routeId:'jeju-coast',reason:'late old response'}]});
await secondAI;
check('filter changes invalidate in-flight AI response',()=>{assert.equal(app.state.aiResult,null);assert.equal(app.state.selected.region,'춘천');});
const thirdAI=click('ai-recommend');
app.setMoving(true);
aiResolve({source:'OpenAI',model:'mock-model',recommendations:[{routeId:'chuncheon-soyang',reason:'late driving response'}]});
await thirdAI;
check('AI completion cannot unlock driving or replace active route',()=>{assert.equal(app.state.moving,true);assert.equal(app.state.aiResult,null);assert.notEqual(app.state.selected.id,'chuncheon-soyang');});
app.setMoving(false);
const fourthAI=click('ai-recommend');
await click('ai-cancel');
aiResolve({source:'OpenAI',model:'mock-model',recommendations:[{routeId:'chuncheon-lake',reason:'cancelled response'}]});
await fourthAI;
check('cancelled AI result is ignored',()=>{assert.equal(app.state.aiLoading,false);assert.equal(app.state.aiResult,null);});
for(const minutes of ['75','1','720']) {
  await handlers.document.change[0]({target:{id:'time-filter',value:minutes}});
  assert.equal(app.state.filters.maxMinutes,Number(minutes));
}
for(const invalid of ['', '0', '-1', '721', '75.5', 'NaN']) {
  const target={id:'time-filter',value:invalid};
  await handlers.document.change[0]({target});
  assert.equal(app.state.filters.maxMinutes,720);
  assert.equal(target.value,'720');
}
check('custom time budget input renders bounds and current entered value',()=>{assert.match(element('#main-content').innerHTML,/id="time-filter"[^>]*type="number"[^>]*min="1"[^>]*max="720"[^>]*value="720"/);});
check('ranked route card numbers resolve by stable route IDs',()=>{assert.doesNotMatch(element('#main-content').innerHTML,/class="route-card-number">00</);assert.match(element('#main-content').innerHTML,/class="route-card-number">0[1-8]</);});
app.state.filters={region:'all',theme:'all',maxMinutes:720,query:''};
app.state.bookmarks=['jeju-coast'];
app.selectRoute('chuncheon-lake',false);
await handlers.document.change[0]({target:{id:'sort-select',value:'saved'}});
check('saved filter selects an eligible bookmarked route for the hero',()=>{
  assert.equal(app.state.selected.id,'jeju-coast');
  assert.equal(app.getRanked().length,1);
  assert.equal(app.getRanked()[0].id,app.state.selected.id);
});
app.state.view='journey';app.state.mapMode='online';app.render();
check('offline map fallback updates the map toggle to offer a real map retry',()=>{
  assert.equal(app.state.mapMode,'diagram');
  assert.match(element('#main-content').innerHTML,/data-action="toggle-map"[^>]*>.*?실제 지도 보기/s);
  assert.doesNotMatch(element('#main-content').innerHTML,/코스 도식 보기/);
});
app.state.view='drive';app.state.gps={lat:37.5,lon:127,accuracy:20};app.state.gpsWatch=12;
app.setMoving(false);
check('stopping GPS removes the live reception label',()=>{
  assert.equal(app.state.gpsWatch,null);
  assert.doesNotMatch(element('#main-content').innerHTML,/GPS 수신/);
});
app.startGPS();app.render();
check('restarting GPS does not report the previous session position as a fresh fix',()=>{
  assert.equal(app.state.gps,null);
  assert.equal(app.state.gpsWatch,12);
  assert.doesNotMatch(element('#main-content').innerHTML,/GPS 수신/);
});
app.setMoving(false);
for (const label of checks) t.diagnostic(`PASS: ${label}`);
app.setMoving(false);app.state.filters={region:'all',theme:'all',maxMinutes:180,query:''};
const beforeBackground=app.state.selected.id;
const backgroundAI=click('ai-recommend');
handlers.window.pagehide[0]();
aiResolve({source:'OpenAI',recommendations:[{routeId:'jeju-coast',reason:'late hidden response'}]});
await backgroundAI;
check('pagehide invalidates AI selection and pending status',()=>{
  assert.equal(app.state.selected.id,beforeBackground);
  assert.equal(app.state.aiResult,null);
  assert.equal(app.state.aiLoading,false);
});
app.state.view='journey';app.selectRoute('chuncheon-lake',false);
const backgroundRoad=click('road-route');
handlers.window.pagehide[0]();
roadResolve({coordinates:[[37,127],[38,128]],source:'late route'});
await backgroundRoad;
check('pagehide invalidates pending route response',()=>{
  assert.equal(app.state.roadRoute,null);
  assert.equal(app.state.roadLoading,false);
});
assert.equal(checks.length,30);
});
