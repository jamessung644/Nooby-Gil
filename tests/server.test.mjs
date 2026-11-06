import test from 'node:test';
import assert from 'node:assert/strict';
import {Readable} from 'node:stream';
import {createServer} from '../server/index.mjs';
import {routes} from '../web/data.js';
function request(server,url,{method='GET',body='',headers={}}={}){
  return new Promise(resolve=>{
    const req=Readable.from(body?[Buffer.from(body)]:[]);
    Object.assign(req,{url,method,headers:{host:'127.0.0.1:4173',...headers},socket:{remoteAddress:'127.0.0.1'}});
    const res={headers:{},setHeader(k,v){this.headers[k]=v;},writeHead(status,h){this.status=status;Object.assign(this.headers,h);},end(data){resolve({status:this.status,headers:this.headers,body:String(data??'')});}};
    server.emit('request',req,res);
  });
}
test('static home serves index and dotfiles never expose keys',async()=>{
  const server=createServer({env:{}});
  const home=await request(server,'/');
  assert.equal(home.status,200);
  assert.match(home.headers['Content-Type'],/text\/html/);
  for(const u of ['/.env','/%2eenv','/../.env','/%2e%2e/package.json'])assert.equal((await request(server,u)).status,404);
});
test('host and origin validation block cross-site key quota use',async()=>{
  const server=createServer({env:{}});
  assert.equal((await request(server,'/api/status',{headers:{host:'evil.invalid'}})).status,403);
  assert.equal((await request(server,'/api/status',{headers:{origin:'https://evil.invalid'}})).status,403);
  assert.equal((await request(server,'/api/status')).status,200);
});
test('API missing configuration and malformed input fail explicitly',async()=>{
  const server=createServer({env:{}});
  assert.equal((await request(server,'/api/route',{method:'POST',body:'{}',headers:{'content-type':'application/json'}})).status,503);
  assert.equal((await request(server,'/api/route',{method:'POST',body:'not json',headers:{'content-type':'application/json'}})).status,400);
  assert.equal((await request(server,'/api/places?lon=127')).status,400);
  assert.equal((await request(server,'/api/missing')).status,404);
});
test('unexpected upstream errors redact secret-bearing messages',async()=>{
  const server=createServer({env:{KAKAO_REST_API_KEY:'secret-token'},fetchImpl:async()=>{throw new Error('bad secret-token');}});
  const r=await request(server,'/api/route',{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify({stops:[{lat:37,lon:127},{lat:38,lon:128}]})});
  assert.equal(r.status,502);assert.ok(!r.body.includes('secret-token'));
});
test('AI endpoint connects server configuration and returns explicit missing-key errors',async()=>{
  const body=JSON.stringify({routeIds:[routes[0].id],preferences:{maxMinutes:720}});
  const options={method:'POST',headers:{'content-type':'application/json'},body};
  assert.equal((await request(createServer({env:{}}),'/api/recommend',options)).status,503);
  let calls=0;
  const server=createServer({env:{OPENAI_API_KEY:'mock-test-token'},fetchImpl:async(url,init)=>{
    calls++;
    assert.equal(url,'https://api.openai.com/v1/responses');
    assert.equal(init.headers.Authorization,'Bearer mock-test-token');
    assert.equal(JSON.parse(init.body).store,false);
    return {ok:true,json:async()=>({status:'completed',output:[{type:'message',role:'assistant',content:[{type:'output_text',text:JSON.stringify({recommendations:[{routeId:routes[0].id,reason:'선택한 시간 안에서 경관을 즐길 수 있는 예시 코스입니다.'}]})}]}]})};
  }});
  const result=await request(server,'/api/recommend',options);
  assert.equal(result.status,200);
  assert.equal(JSON.parse(result.body).source,'OpenAI');
  assert.equal(JSON.parse(result.body).recommendations[0].routeId,routes[0].id);
  assert.equal(calls,1);
  assert.ok(!result.body.includes('mock-test-token'));
});

test('Android emulator host is allowed exactly and browser origins must still match',async()=>{
  const server=createServer({env:{}});
  for(const host of ['10.0.2.2','10.0.2.2:4173']) {
    assert.equal((await request(server,'/api/status',{headers:{host}})).status,200);
    assert.equal((await request(server,'/api/status',{headers:{host,origin:`http://${host}`}})).status,200);
  }
  for(const headers of [
    {host:'10.0.2.3:4173'}, {host:'10.0.2.2.evil.invalid:4173'},
    {host:'10.0.2.2:4173',origin:'http://127.0.0.1:4173'},
    {host:'10.0.2.2:4173',origin:'https://10.0.2.2:4173'},
  ]) assert.equal((await request(server,'/api/status',{headers})).status,403);
});

const androidInput={routeIds:['chuncheon','gangneung','seoul'],preferences:{region:'all',theme:'lake',maxMinutes:120,query:''}};
const androidOptions=(input=androidInput)=>({method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify(input)});
const androidResponse=(recommendations)=>Response.json({
  id:'resp_android',object:'response',status:'completed',
  output:[{id:'msg_android',type:'message',role:'assistant',status:'completed',content:[{type:'output_text',text:JSON.stringify({recommendations}),annotations:[]}]}],
});

test('Android recommendation uses trusted Android metadata through the existing structured AI contract',async()=>{
  let sent;
  const server=createServer({env:{OPENAI_API_KEY:'mock-android-token'},fetchImpl:async(url,init)=>{
    assert.equal(url,'https://api.openai.com/v1/responses');
    assert.equal(init.headers.Authorization,'Bearer mock-android-token');
    assert.equal(init.redirect,'error');
    const body=JSON.parse(init.body);
    assert.equal(body.store,false);
    assert.equal(body.text.format.strict,true);
    assert.deepEqual(body.text.format.schema.properties.recommendations.items.properties.routeId.enum,['chuncheon','gangneung','seoul']);
    sent=JSON.parse(body.input[0].content);
    return androidResponse([{routeId:'chuncheon',reason:'호수와 숲을 따라가는 85분 시연 코스입니다.'}]);
  }});
  const r=await request(server,'/api/android/recommend',androidOptions());
  assert.equal(r.status,200);
  assert.deepEqual(sent.preferences,androidInput.preferences);
  assert.deepEqual(sent.candidates.map(({routeId,durationMin,distanceKm,theme})=>({routeId,durationMin,distanceKm,theme})),[
    {routeId:'chuncheon',durationMin:85,distanceKm:42,theme:'lake'},
    {routeId:'gangneung',durationMin:110,distanceKm:36,theme:'coast'},
    {routeId:'seoul',durationMin:60,distanceKm:28,theme:'city'},
  ]);
  assert.equal(sent.candidates[0].title,'춘천, 물빛을 따라서');
  const data=JSON.parse(r.body);
  assert.equal(data.source,'OpenAI');
  assert.equal(data.model,'gpt-6-luna');
  assert.ok(Number.isFinite(Date.parse(data.generatedAt)));
  assert.deepEqual(data.recommendations,[{routeId:'chuncheon',reason:'호수와 숲을 따라가는 85분 시연 코스입니다.'}]);
  assert.ok(!r.body.includes('mock-android-token'));
});

test('Android candidates obey their own time budgets and reject web IDs or client catalogue overrides',async()=>{
  let sent;
  const server=createServer({env:{OPENAI_API_KEY:'mock-android-token'},fetchImpl:async(_url,init)=>{
    sent=JSON.parse(JSON.parse(init.body).input[0].content);
    return androidResponse([{routeId:'seoul',reason:'60분 조건에 맞는 도시 야경 시연 코스입니다.'}]);
  }});
  const r=await request(server,'/api/android/recommend',androidOptions({...androidInput,preferences:{theme:'coast',maxMinutes:60}}));
  assert.equal(r.status,200);
  assert.deepEqual(sent.candidates.map(c=>c.routeId),['seoul']);
  const noFetch=createServer({env:{OPENAI_API_KEY:'mock-android-token'},fetchImpl:async()=>{throw new Error('Unexpected external call');}});
  for(const input of [
    {...androidInput,routeIds:['chuncheon-lake']},
    {...androidInput,routeIds:['chuncheon','chuncheon']},
    {...androidInput,routeIds:[]},
    {...androidInput,preferences:{maxMinutes:59}},
    {...androidInput,preferences:{region:'부산'}},
    {...androidInput,catalogue:[{id:'chuncheon',durationMin:1}]},
    {...androidInput,candidates:[{id:'invented',durationMin:1}]},
  ]) assert.equal((await request(noFetch,'/api/android/recommend',androidOptions(input))).status,400);
  assert.equal((await request(noFetch,'/api/recommend',androidOptions())).status,400);
});

test('Android ranking rejects an AI route excluded by the budget',async()=>{
  const server=createServer({env:{OPENAI_API_KEY:'mock-android-token'},fetchImpl:async()=>androidResponse([{routeId:'chuncheon',reason:'예시 추천'}])});
  const r=await request(server,'/api/android/recommend',androidOptions({...androidInput,preferences:{maxMinutes:60}}));
  assert.equal(r.status,502);
});

test('Android endpoint reports missing configuration and malformed input without exposing secrets',async()=>{
  assert.equal((await request(createServer({env:{}}),'/api/android/recommend',androidOptions())).status,503);
  const server=createServer({env:{OPENAI_API_KEY:'mock-android-token'},fetchImpl:async()=>{throw new Error('mock-android-token');}});
  assert.equal((await request(server,'/api/android/recommend',{...androidOptions(),body:'not json'})).status,400);
  const r=await request(server,'/api/android/recommend',androidOptions());
  assert.equal(r.status,502);
  assert.ok(!r.body.includes('mock-android-token'));
});
