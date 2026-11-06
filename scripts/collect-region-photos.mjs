// Run: node --env-file=.env scripts/collect-region-photos.mjs
// Downloads only the eight checked editorial hero choices to an ignored staging directory.
// No key-bearing URL or raw provider exception is printed or stored.
import { readFile, writeFile, mkdir, rm } from 'node:fs/promises';
import { buildTourUrl } from '../server/providers.mjs';
import { safeTourPhotoUrl } from '../server/scenic-photos.mjs';

const root = new URL('../', import.meta.url), destination = new URL('.cache/region-photo-import/', root);
async function json(endpoint, id) {
  const url = buildTourUrl({ lat: 37, lon: 127 }, process.env.TOUR_API_KEY);
  url.pathname = url.pathname.replace('locationBasedList2', endpoint);
  for (const key of ['mapX', 'mapY', 'radius', 'contentTypeId', 'arrange']) url.searchParams.delete(key);
  url.searchParams.set('contentId', id); url.searchParams.set('numOfRows', '30');
  if (endpoint === 'detailImage2') url.searchParams.set('imageYN', 'Y');
  const response = await fetch(url, { redirect: 'error', signal: AbortSignal.timeout(12000) });
  if (!response.ok) throw new Error('provider');
  const data = await response.json();
  if (data.response?.header?.resultCode !== '0000') throw new Error('response');
  const raw = data.response.body?.items?.item;
  return Array.isArray(raw) ? raw : raw && typeof raw === 'object' ? [raw] : [];
}
let stage = 'catalog';
try {
  const catalog = JSON.parse(await readFile(new URL('catalog/scenic-photo-curation.json', root), 'utf8'));
  const heroes = catalog.places.filter(place => place.hero === true);
  if (heroes.length !== 8 || new Set(heroes.map(p => p.regionId)).size !== 8) throw new Error('catalog');
  await mkdir(destination, { recursive: true });
  // An interrupted import must not leave a manifest that could publish mixed old/new files.
  await rm(new URL('manifest.json', destination), { force: true });
  const records = [];
  for (const choice of heroes) {
    stage = choice.regionId + ':place';
    const current = await json('detailCommon2', choice.id);
    if (current.length !== 1 || String(current[0].contentid) !== choice.id || current[0].cpyrhtDivCd !== 'Type1'
      || current[0].title !== choice.title || Math.abs(Number(current[0].mapy) - choice.lat) > .003
      || Math.abs(Number(current[0].mapx) - choice.lon) > .003) throw new Error('identity');
    let imageUrl = safeTourPhotoUrl(current[0].firstimage);
    if (choice.preferredImage) {
      stage = choice.regionId + ':gallery';
      const images = (await json('detailImage2', choice.id)).filter(image => String(image.contentid) === choice.id && image.serialnum === choice.preferredImage.serial);
      if (images.length !== 1 || images[0].cpyrhtDivCd !== 'Type1') throw new Error('license');
      imageUrl = safeTourPhotoUrl(images[0].originimgurl);
    }
    if (!imageUrl) throw new Error('image');
    stage = choice.regionId + ':image';
    const response = await fetch(imageUrl, { redirect: 'error', signal: AbortSignal.timeout(12000) });
    if (!response.ok || !/^image\/(jpe?g|png|webp)(?:;|$)/i.test(response.headers.get('content-type') || '')) throw new Error('image');
    const reader = response.body.getReader(), chunks = []; let bytes = 0;
    try {
      for (;;) { const { done, value } = await reader.read(); if (done) break; bytes += value.byteLength;
        if (bytes > 12 * 1024 * 1024) { await reader.cancel(); throw new Error('size'); } chunks.push(Buffer.from(value)); }
    } finally { reader.releaseLock(); }
    const file = choice.regionId + '.jpg'; await writeFile(new URL(file, destination), Buffer.concat(chunks));
    records.push({ regionId: choice.regionId, contentId: choice.id, placeName: current[0].title,
      lat: Number(current[0].mapy), lon: Number(current[0].mapx), file, originalUrl: imageUrl,
      sourceName: choice.sourceName, sourceUrl: choice.sourceUrl, license: choice.license, licenseUrl: choice.licenseUrl,
      photographer: choice.photographer, fetchedAt: new Date().toISOString(),
      viewpointNote: '관광 장소의 대표 사진입니다. 정확한 촬영 지점·방향·시기는 확인되지 않았습니다.' });
    console.log(`${choice.regionId}: verified Type1 image downloaded`);
  }
  await writeFile(new URL('manifest.json', destination), JSON.stringify({ version: 1, photos: records }, null, 2) + '\n');
  console.log('Eight photos staged. Run scripts/prepare-region-photo-assets.py with Python + Pillow to publish thumbnails.');
} catch (error) { console.error(`Photo collection stopped (${stage}; ${['provider', 'response', 'catalog', 'identity', 'license', 'image', 'size'].includes(error.message) ? error.message : 'connection'}): current identity, license, image or provider could not be verified. Existing bundled assets were not changed.`); process.exitCode = 1; }
