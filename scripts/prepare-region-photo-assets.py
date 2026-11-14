"""Developer asset tool: Python 3 + Pillow; no runtime Python dependency.

First run collect-region-photos.mjs with an approved TourAPI key. This step only
resizes verified images, preserving their aspect ratio and attribution metadata.
"""
import hashlib
import json
from pathlib import Path
from PIL import Image, ImageOps

root = Path(__file__).resolve().parent.parent
staging = root / '.cache' / 'region-photo-import'
target = root / 'android' / 'app' / 'src' / 'main' / 'assets' / 'scenic-regions'
manifest = json.loads((staging / 'manifest.json').read_text(encoding='utf-8'))
if len(manifest['photos']) != 8:
    raise ValueError('Expected eight licensed photos')
target.mkdir(parents=True, exist_ok=True)
prepared = []
for photo in manifest['photos']:
    if photo['file'] != photo['regionId'] + '.jpg' or '/' in photo['file'] or '\\' in photo['file']:
        raise ValueError('Invalid asset path')
    image = ImageOps.exif_transpose(Image.open(staging / photo['file'])).convert('RGB')
    if image.width < 500 or image.height < 250:
        raise ValueError('Source too small for a regional card')
    image.thumbnail((960, 720), Image.Resampling.LANCZOS)
    destination = target / photo['file']
    image.save(destination, 'JPEG', quality=86, optimize=True)
    photo.update(width=image.width, height=image.height, sha256=hashlib.sha256(destination.read_bytes()).hexdigest(),
                 transformation='가로세로 비율 유지 축소·JPEG 압축. 카드에서는 중앙 맞춤 표시.')
    prepared.append(photo)
(target / 'manifest.json').write_text(json.dumps({'version': 1, 'photos': prepared}, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
(target / 'ATTRIBUTION.txt').write_text('누비길 지역 카드 사진\n\n' + '\n\n'.join(
    f"{p['file']} — {p['placeName']}\n제공: {p['sourceName']}\n{p['license']}\n촬영자: {p['photographer']}\n원본: {p['originalUrl']}\n출처: {p['sourceUrl']}\n이용조건: {p['licenseUrl']}\n확인: {p['fetchedAt']}\n{p['transformation']}"
    for p in prepared) + '\n', encoding='utf-8')
print(f'Prepared {len(prepared)} attributed regional photos ({sum((target / p["file"]).stat().st_size for p in prepared):,} bytes).')
