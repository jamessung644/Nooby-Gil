import http from 'node:http';
import {readFile,stat,realpath} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
import path from 'node:path';
import {ApiError,providerStatus,fetchRoadRoute,fetchPlaces} from './providers.mjs';
import {createScenicSelectionSigner} from './scenic-photos.mjs';
const root=path.resolve(fileURLToPath(new URL('../web/',import.meta.url)));
const insideRoot=file=>file===root||file.startsWith(root+path.sep);
const mime={'.html':'text/html; charset=utf-8','.js':'text/javascript; charset=utf-8','.css':'text/css; charset=utf-8','.json':'application/json','.webmanifest':'application/manifest+json','.svg':'image/svg+xml','.png':'image/png','.webp':'image/webp','.jpg':'image/jpeg','.mp3':'audio/mpeg','.wav':'audio/wav','.woff2':'font/woff2','.ttf':'font/ttf'};
const json=(res,status,data)=>{res.writeHead(status,{'Content-Type':'application/json; charset=utf-8','Cache-Control':'no-store'});res.end(JSON.stringify(data));};
const visits=new Map();
async function readJson(req){
  if(!req.headers['content-type']?.startsWith('application/json'))throw new ApiError('JSON 요청만 지원합니다.',415);
  const chunks=[];let bytes=0;
  for await(const chunk of req){bytes+=chunk.length;if(bytes>16384)throw new ApiError('요청이 너무 큽니다.',413);chunks.push(chunk);}
  // Decode once: a network chunk can end in the middle of a Korean UTF-8 character.
  try{return JSON.parse(Buffer.concat(chunks,bytes).toString('utf8'));}catch{throw new ApiError('JSON 형식이 올바르지 않습니다.');}
}
export function createServer({env=process.env,fetchImpl=fetch}={}){
  const selectionSigner=createScenicSelectionSigner();
  return http.createServer(async(req,res)=>{
    res.setHeader('X-Content-Type-Options','nosniff');res.setHeader('Referrer-Policy','strict-origin-when-cross-origin');
    try{
      // Loopback hosts plus Android emulator's host-loopback alias. The listener
      // stays bound to 127.0.0.1; browsers must still send a matching Origin.
      const host=req.headers.host||'';
      if(!/^(localhost|127\.0\.0\.1|10\.0\.2\.2|\[::1\])(?::\d+)?$/.test(host))throw new ApiError('로컬 호스트에서만 사용할 수 있습니다.',403);
      if(req.headers.origin&&req.headers.origin!==`http://${host}`)throw new ApiError('다른 사이트에서의 요청은 허용하지 않습니다.',403);
      const url=new URL(req.url,`http://${host}`);
      if(url.pathname.startsWith('/api/')){
        const minute=Math.floor(Date.now()/60000);const ip=req.socket.remoteAddress;
        const old=visits.get(ip);const count=old?.minute===minute?old.count+1:1;
        visits.set(ip,{minute,count});if(count>60)throw new ApiError('요청이 많습니다. 잠시 후 다시 시도해 주세요.',429);
        if(url.pathname==='/api/status'&&req.method==='GET')return json(res,200,providerStatus(env));
        if(url.pathname==='/api/nearby'&&req.method==='GET'){
          if(!url.searchParams.has('lat')||!url.searchParams.has('lon'))throw new ApiError('검색할 위치가 필요합니다.');
          const {searchNearby}=await import('./nearby.mjs');
          return json(res,200,await searchNearby({kind:url.searchParams.get('kind'),lat:Number(url.searchParams.get('lat')),lon:Number(url.searchParams.get('lon')),...(url.searchParams.has('query')?{query:url.searchParams.get('query')}:{}),...(url.searchParams.has('focusId')?{focusId:url.searchParams.get('focusId')}:{})},{env,fetchImpl}));
        }
        if(url.pathname==='/api/catalog'&&req.method==='GET'){
          const {getPreparedCatalog}=await import('./android-catalog.mjs');
          return json(res,200,getPreparedCatalog());
        }
        if(url.pathname==='/api/map-config'&&req.method==='GET'){
          // Kakao JavaScript keys are public map-client configuration. Never
          // include private REST, OpenAI, TourAPI, or other environment values.
          const javascriptKey=typeof env.KAKAO_MAP_JS_KEY==='string'?env.KAKAO_MAP_JS_KEY.trim():'';
          return json(res,200,{configured:javascriptKey.length>0,javascriptKey});
        }
        if(url.pathname==='/api/destinations'&&req.method==='GET'){
          const {searchDestinations}=await import('./destination-routes.mjs');
          return json(res,200,await searchDestinations(url.searchParams.get('q'),{env,fetchImpl}));
        }
        if(url.pathname==='/api/destination-routes'&&req.method==='POST'){
          const {planDestinationRoutes}=await import('./destination-routes.mjs');
          return json(res,200,await planDestinationRoutes(await readJson(req),{env,fetchImpl,selectionSigner}));
        }
        if(url.pathname==='/api/scenic-photos'&&req.method==='POST'){
          const {findScenicPhotos}=await import('./scenic-photos.mjs');
          return json(res,200,await findScenicPhotos(await readJson(req),{env,fetchImpl,selectionSigner}));
        }
        if(url.pathname==='/api/route'&&req.method==='POST')return json(res,200,await fetchRoadRoute(await readJson(req),{env,fetchImpl}));
        if(url.pathname==='/api/recommend'&&req.method==='POST'){
          const {recommendWithAI}=await import('./ai.mjs');
          return json(res,200,await recommendWithAI(await readJson(req),{env,fetchImpl}));
        }
        if(url.pathname==='/api/android/recommend'&&req.method==='POST'){
          const {recommendAndroidWithAI}=await import('./ai.mjs');
          return json(res,200,await recommendAndroidWithAI(await readJson(req),{env,fetchImpl}));
        }
        if(url.pathname==='/api/android/plan'&&req.method==='POST'){
          const {planNearbyDrive}=await import('./live-planner.mjs');
          return json(res,200,await planNearbyDrive(await readJson(req),{env,fetchImpl}));
        }
        if(url.pathname==='/api/android/music'&&req.method==='POST'){
          const {recommendMusic}=await import('./music.mjs');
          return json(res,200,await recommendMusic(await readJson(req),{env,fetchImpl}));
        }
        if(url.pathname==='/api/places'&&req.method==='GET'){
          const lat=url.searchParams.get('lat'),lon=url.searchParams.get('lon'),radius=url.searchParams.get('radiusMeters');
          if(!lat?.trim()||!lon?.trim())throw new ApiError('위치가 필요합니다.');
          if(radius!==null&&!radius.trim())throw new ApiError('관광지 탐색 반경은 1~20,000m입니다.');
          return json(res,200,await fetchPlaces({lat:Number(lat),lon:Number(lon),radiusMeters:radius===null?10000:Number(radius)},{env,fetchImpl}));
        }
        throw new ApiError('지원하지 않는 API 요청입니다.',404);
      }
      if(req.method!=='GET'&&req.method!=='HEAD')throw new ApiError('허용되지 않은 메서드입니다.',405);
      let pathname;try{pathname=decodeURIComponent(url.pathname);}catch{throw new ApiError('잘못된 경로입니다.');}
      if(pathname.includes('\0')||pathname.split('/').some(p=>p.startsWith('.')))throw new ApiError('파일을 찾을 수 없습니다.',404);
      let file=path.resolve(root,'.'+pathname);if(!insideRoot(file))throw new ApiError('파일을 찾을 수 없습니다.',404);
      try{if((await stat(file)).isDirectory())file=path.join(file,'index.html');file=await realpath(file);if(!insideRoot(file))throw new Error();}catch{throw new ApiError('파일을 찾을 수 없습니다.',404);}
      const data=await readFile(file);res.writeHead(200,{'Content-Type':mime[path.extname(file)]||'application/octet-stream','Cache-Control':'no-cache'});res.end(req.method==='HEAD'?undefined:data);
    }catch(e){json(res,e instanceof ApiError?e.status:500,{error:e instanceof ApiError?e.message:'요청 처리 중 오류가 발생했습니다.'});}
  });
}
if(process.argv[1]&&path.resolve(process.argv[1])===fileURLToPath(import.meta.url)){
  const port=Number(process.env.PORT||4173);if(!Number.isInteger(port)||port<1||port>65535)throw new Error('PORT must be 1–65535');
  const server=createServer();server.on('error',e=>{console.error(`서버를 시작할 수 없습니다 (${e.code}). 포트 사용 여부 또는 실행 환경의 네트워크 권한을 확인해 주세요.`);process.exitCode=1;});server.listen(port,'127.0.0.1',()=>console.log(`SCENIC Local: http://127.0.0.1:${port}`));
  for(const signal of ['SIGINT','SIGTERM'])process.on(signal,()=>server.close(()=>process.exit(0)));
}
