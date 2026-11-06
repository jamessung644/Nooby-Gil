# Pleos 순정 내비 연결

SCENIC은 공식 `ai.pleos.playground:NaviHelper:3.0.5`를 사용해 선택한 목적지와 경유지를 Pleos 순정 내비로 요청합니다. 사용자가 정차 중 **순정 내비로 안내**를 누를 때만 연결합니다.

## 전달 범위

- 목적지의 위도·경도·이름·주소와 순서가 있는 경유지를 전달합니다.
- 출발지는 순정 내비가 확인한 **차량 현재 위치**입니다. SCENIC의 공개 시연 출발지를 차량 위치로 변경하지 않습니다.
- Kakao의 도로선이나 예상 시간을 전달하는 기능은 아닙니다. 순정 내비가 도로와 시간을 다시 계산합니다.
- 공식 SDK가 지원하는 경유지는 최대 **2곳**입니다. 이를 초과하면 경유지를 조용히 생략하지 않고 요청 전에 안내합니다.
- Kakao/TourAPI의 장소 ID를 순정 내비의 POI ID로 오인하지 않도록 좌표와 이름으로 요청합니다.

## 결과 확인

`initialize()` 연결 결과와 `NaviHelperEventListener` 이벤트를 처리합니다. 경로 요청을 보낸 사실과 경로 설정 완료를 구분합니다. 경로 이벤트 후 목적지·경유지 정보를 다시 조회하고, 요청한 좌표·순서·개수가 일치할 때만 설정을 확인했다고 표시합니다. 제한 시간 안에 확인하지 못하면 내비 화면에서 직접 확인하도록 안내합니다.

일부 Navigation 버전은 경유지 이름과 순서는 반환하지만 좌표를 `0,0`, 표시 좌표를 `null`로 반환합니다. 최종 목적지 좌표가 일치하고, 경유지 개수·이름·순서가 모두 일치하며, 모든 경유지 좌표가 `0` 또는 `null`일 때만 **이름·순서 부분 확인**으로 표시합니다. 경유지 위치 확인 완료로 표시하지 않으며 순정 내비 화면에서 위치를 확인하도록 안내합니다. 일부 좌표만 누락되거나 이름·개수·순서가 다르면 이 부분 확인을 적용하지 않습니다.

앱이 종료되면 리스너·SDK 연결을 해제합니다. 연결 중 화면을 나가면 아직 보내지 않은 요청을 중단하지만, 이미 순정 내비에서 설정된 경로를 임의로 취소하지 않습니다.

필요한 권한은 `NAVI_ROUTE`(결과 조회), `NAVI_ROUTE_SEARCH`(목적지·경유지 설정), `NAVI_CUSTOM_MAP`(내비 화면 요청)입니다. 지원 서비스를 찾지 못하거나 권한이 없으면 실패 원인을 보여줍니다.

## 검증 범위

- 공식 Maven의 실제 SDK 아티팩트를 사용해 브리지 Java 컴파일을 확인했습니다.
- 목적지·경유지 수·순서·콜백 좌표 일치와 부분 확인의 엄격한 조건 등 `NavigationHandoffPlanTest` 31개 검사를 통과했습니다.
- 개발 에뮬레이터에서 Navi Service 3.0.4와 순정 Navigation 앱이 설치되어 있고, 필요한 권한이 선언되어 있음을 확인했습니다.
- 2026-11-06 Pleos Connect v2.1.0 / API 34에서 실제 왕복 경로(경유지 2곳 + 출발지 복귀)를 요청해 순정 내비 화면과 경로 탐색 이벤트를 확인했습니다. 최종 목적지 콜백의 좌표가 요청과 일치했고 경유지 2곳의 이름·순서도 일치했습니다. Navigation 3.3.2-rc5-hotfix.3은 이 요청의 경유지 좌표를 모두 `0,0`으로, 표시 좌표를 `null`로 반환해 **경유지 좌표 검증까지 완료한 것은 아닙니다**. 구체적인 장소나 위치를 앱 진단 로그에 남기지 않습니다.
- 실제 주행이나 실차 안전성 검증을 의미하지 않습니다. 순정 내비의 네트워크·위치·서비스 상태에 따라 경로 요청이 실패할 수 있습니다.

## 공식 문서

- [SDK 가져오기](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/importing-sdk)
- [권한 선언](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/declaring-permissions)
- [목적지 설정과 결과 확인](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/destinations)
- [경유지 개수와 순서](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/waypoint-settings)
- [경유지를 포함한 경로 요청](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/route-with-waypoints)

문서 확인일: 2026-11-06. 일부 Java 예제는 이전 생성자 형식을 사용하므로 실제 3.0.5 배포 아티팩트의 타입·인자 순서를 기준으로 구현했습니다.
