# SCENIC 데모 사진 출처

이 사진들은 부산의 실제 장소를 촬영한 공개 라이선스 자료다. SCENIC 빌트인 캠, 사용자의 차량 또는 다른 SCENIC 운전자가 촬영한 사진이 아니다. 앱에 포함한 오프라인 샘플로만 사용한다. 사진의 촬영일·구도는 현재 도로 상태, 날씨, 주행 시야를 보장하지 않는다.

UI에는 `샘플 사진 · 실제 차량 촬영 아님` 안내와 각 사진의 저작자·라이선스를 표시한다. 사진 상세 화면에서는 원본 출처와 라이선스 링크에 접근할 수 있어야 한다. 이 자료를 저장하는 데모 동작과 실제 카메라 촬영을 구분한다. 특히 `coast.jpg`는 청사포 **항구와 해안** 사진이며 해안도로 차량 주행 사진이 아니다.

확인·다운로드: 2026-11-05. 앱에서 네트워크 없이 읽을 수 있는 위치는 `android/app/src/main/assets/scenic-demo/`다.

| 파일 | 실제 장소 / 원본 제목 | 저작자 | 라이선스 | 번들 이미지 |
| --- | --- | --- | --- | --- |
| `haeundae.jpg` | 해운대해수욕장 / Busan City Haeundae Beach.jpg | 부산광역시 | 공공누리 제1유형 (출처표시) | 1180 × 680, 원본 JPEG 그대로 |
| `gwangalli.jpg` | 광안리해수욕장과 광안대교 / Gwangalli Beach and Gwangan Bridge Busan.jpg | Masterhatch | CC BY-SA 4.0 | 1280 × 721, Wikimedia가 제공한 축소 JPEG |
| `coast.jpg` | 청사포항 / Cheongsapo.jpg | Andrewssi2 | CC BY-SA 3.0 | 1280 × 721, Wikimedia가 제공한 축소 JPEG |

## 해운대해수욕장

- 표시 권장: `사진: 부산광역시 · 공공누리 제1유형`
- 파일 정보: https://commons.wikimedia.org/wiki/File:Busan_City_Haeundae_Beach.jpg
- 원본 기관 출처: https://www.kogl.or.kr/recommend/recommendDivView.do?recommendIdx=17856&division=img
- 라이선스: https://www.kogl.or.kr/info/licenseType1.do
- 내려받은 파일: https://upload.wikimedia.org/wikipedia/commons/5/5a/Busan_City_Haeundae_Beach.jpg
- Wikimedia에 기재된 날짜: 2024-09-15. 실제 촬영일로 단정하지 않는다.
- 변경: 파일 변경 없음. UI에서 레이아웃에 맞게 화면상 잘라 보여줄 수 있다.
- SHA-256: `b6d43608f93647db4b045341e76c56ae61818d5b6fa3fbb9ac419fef6b62277c`

## 광안리해수욕장과 광안대교

- 표시 권장: `사진: Masterhatch · CC BY-SA 4.0`
- 파일 정보: https://commons.wikimedia.org/wiki/File:Gwangalli_Beach_and_Gwangan_Bridge_Busan.jpg
- 저작자: https://commons.wikimedia.org/wiki/User:Masterhatch
- 라이선스: https://creativecommons.org/licenses/by-sa/4.0/
- 내려받은 파일: https://thumb.wikimedia.org/wikipedia/commons/thumb/d/d4/Gwangalli_Beach_and_Gwangan_Bridge_Busan.jpg/1280px-Gwangalli_Beach_and_Gwangan_Bridge_Busan.jpg
- Wikimedia에 기재된 촬영일: 2022-07-04.
- 변경: 원본 4624 × 2604에서 Wikimedia가 제공한 1280 × 721 축소본. 별도 보정 없음. UI에서 레이아웃에 맞게 화면상 잘라 보여줄 수 있다. 해당 이미지와 이미지에 대한 변경물은 CC BY-SA 4.0으로 제공한다.
- SHA-256: `3fdaee095b603d59a8a7ff8d14084ecc0b3f4a08bdf2d71dab12dbf801bb8519`

## 청사포항

- 표시 권장: `사진: Andrewssi2 · CC BY-SA 3.0`
- 파일 정보: https://commons.wikimedia.org/wiki/File:Cheongsapo.jpg
- 저작자: https://commons.wikimedia.org/wiki/User:Andrewssi2
- 라이선스: https://creativecommons.org/licenses/by-sa/3.0/
- 내려받은 파일: https://thumb.wikimedia.org/wikipedia/commons/thumb/6/68/Cheongsapo.jpg/1280px-Cheongsapo.jpg
- Wikimedia에 기재된 촬영일: 2014-02-05.
- 변경: 원본 3552 × 2000에서 Wikimedia가 제공한 1280 × 721 축소본. 별도 보정 없음. UI에서 레이아웃에 맞게 화면상 잘라 보여줄 수 있다. 해당 이미지와 이미지에 대한 변경물은 CC BY-SA 3.0으로 제공한다.
- SHA-256: `7bc9cacfbc22d639c2e1f2d428963f76ba3f5682f07019da25b1223bb3508a02`

## 검증

세 파일 모두 JPEG 디코딩과 실제 내용을 시각적으로 확인했다. 원본·라이선스·저작자 정보를 위 Wikimedia 파일 정보 페이지에서 확인했다. Android 패키지에도 출처와 라이선스가 함께 포함되도록 `scenic-demo/ATTRIBUTION.txt`를 배포한다. 저작자의 앱 후원·보증을 의미하지 않는다.
