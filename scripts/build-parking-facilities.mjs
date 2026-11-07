// Builds catalog/parking-facilities.json: facility info (name, position, capacity) for every lot that
// the KOTSA real-time API reports. Real-time rows carry only an id, so this catalog gives them a place.
// Usage: DATA_GO_KR_KEY=... node scripts/build-parking-facilities.mjs   (reads .env if the key is unset)
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.join(path.dirname(fileURLToPath(import.meta.url)), '..');
const BASE = 'https://apis.data.go.kr/B553881/Parking_v1';
function key() {
  if (process.env.DATA_GO_KR_KEY) return process.env.DATA_GO_KR_KEY.trim();
  const env = fs.existsSync(path.join(root, '.env')) ? fs.readFileSync(path.join(root, '.env'), 'utf8') : '';
  const line = env.split(/\r?\n/).find(l => l.startsWith('DATA_GO_KR_KEY='));
  if (!line) throw new Error('DATA_GO_KR_KEY가 필요합니다 (.env 또는 환경변수).');
  return line.slice('DATA_GO_KR_KEY='.length).trim();
}
async function page(op, n, rows, serviceKey, sido) {
  const url = `${BASE}/${op}_v1?serviceKey=${encodeURIComponent(serviceKey)}&pageNo=${n}&numOfRows=${rows}&format=2&sidoCd=${sido}`;
  for (let attempt = 0; attempt < 3; attempt++) {
    try {
      const res = await fetch(url, { signal: AbortSignal.timeout(60000) });
      const data = await res.json();
      if (data.resultCode === '0') return data;
    } catch { /* retry */ }
  }
  throw new Error(`${op} page ${n} failed`);
}

const serviceKey = key();
// The facility list is ~1.7M rows nationwide, so only the listed 시도 are fetched (default 부산 26, 서울 11).
const SIDO = (process.env.PARKING_SIDO_CODES || '26,11').split(',').map(s => s.trim()).filter(Boolean);
const realtimeIds = new Set();
for (const sido of SIDO) for (let n = 1; ; n++) {
  const data = await page('PrkRealtimeInfo', n, 1000, serviceKey, sido);
  for (const row of data.PrkRealtimeInfo || []) realtimeIds.add(row.prk_center_id);
  if (n * 1000 >= Number(data.totalCount)) break;
}
console.log(`실시간 대상 ${realtimeIds.size}곳`);

const found = {};
for (const sido of SIDO) for (let n = 1; ; n++) {
  const data = await page('PrkSttusInfo', n, 1000, serviceKey, sido);
  for (const row of data.PrkSttusInfo || []) {
    if (!realtimeIds.has(row.prk_center_id)) continue;
    const lat = Number(row.prk_plce_entrc_la), lon = Number(row.prk_plce_entrc_lo);
    if (!(lat >= 33 && lat <= 38.7 && lon >= 124.5 && lon <= 132)) continue;
    found[row.prk_center_id] = { name: String(row.prk_plce_nm || '').trim(), lat, lon, total: Number(row.prk_cmprt_co) || 0,
      kind: row.prk_manage_agent_nm || '', fee: row.parkng_chrge_se_nm || '', address: row.prk_plce_adres || '' };
  }
  process.stdout.write(`시설 ${n * 1000}/${data.totalCount}\r`);
  if (n * 1000 >= Number(data.totalCount)) break;
}
fs.writeFileSync(path.join(root, 'catalog', 'parking-facilities.json'), JSON.stringify({
  source: '한국교통안전공단 주차정보 제공 API (data.go.kr 15099883)', builtAt: new Date().toISOString(), lots: found,
}));
console.log(`\n저장: ${Object.keys(found).length}곳 (실시간 ${realtimeIds.size}곳 중 위치 확인)`);

