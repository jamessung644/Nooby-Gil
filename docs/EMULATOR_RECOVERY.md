# Pleos 에뮬레이터 그래픽 복구 기록

기준: 2026-11-05 UTC, Mac ARM64, Android Emulator 37.2.12.

## 관찰된 증상

- 기존 Hardware 실행에서는 에뮬레이터 자체의 SIGSEGV 종료가 관찰됐다.
- Software + Cold Boot에서는 Pleos 홈, SCENIC 첫 화면·설정, `http://10.0.2.2:4173` API 연결, 전국 코스 목록까지 확인했다.
- 지도에서는 Kakao SDK의 줌 버튼·로고가 표시됐지만 타일은 회색이었고, 코스 좌표를 기다린다는 문구가 유지됐다. 이후 SCENIC 화면이 닫히고 Pleos 차량 화면도 검게 표시됐다. 지도 최종 표시 성공은 확인하지 못했다.
- Android Studio Logcat에서 `ai.umos.drivingview`의 `GameThread`가 `libUnreal.so`에서 SIGSEGV를 반복했고, `eglCreateContext: EGL_BAD_CONFIG: no ES 3.2 support`가 출력됐다. 이 로그만으로 SCENIC Java 예외나 화면 종료의 직접 원인을 확정하지 않는다.

## 복구 순서

1. Android Studio **Tools → SDK Manager → SDK Tools → Android Emulator**에서 **37.2.13** 업데이트를 적용한다. 공식 패치 설명은 앱·프로세스 종료 후 GLES context, surface, framebuffer가 해제되지 않던 **호스트 메모리 누수 수정**이다. 현재 Unreal 충돌의 해결을 보장하는 패치는 아니다.
2. 설치 완료 뒤 기존 AVD를 정상 종료한다. **Device Manager → 해당 Pleos 기기 편집 → Graphics acceleration: Hardware**, **Default boot: Cold**, **RAM: 4 GB**를 확인하고 한 번만 시작한다. Pleos 공식 기준 이미지는 **Pleos Connect 2.1.0 / API 34 / Mac ARM64**다. Cold Boot는 VHAL 사용에도 필요하다.
3. Pleos 차량 화면이 유지되는지 먼저 확인한 뒤 SCENIC의 API 연결·전국 코스·지도 제목·마커·타일을 확인한다. 재종료 시 Logcat의 프로세스명과 최초 오류를 기록한다. 업데이트 완료와 지도·앱 안정성 검증 완료를 구분한다.

Hardware에서도 충돌한다면 Android 공식 문서가 제시하는 다음 비교 후보는 GLES용 **Vulkan 비활성화(`-feature -Vulkan`)** 또는 명시적 **SwiftShader(`-gpu swiftshader`)**다. 이는 Apple Silicon의 MoltenVK 호환성 문제에 대한 대안이며, ES 3.2 지원이나 Pleos DrivingView의 정상 동작을 보장하지 않는다. 이미 실패한 Software 설정의 반복 실행을 복구 성공으로 취급하지 않는다.

## 설치 후 결과

- Android Emulator 설치 버전: SDK Manager에서 37.2.13 Installed 확인. 기존 AVD를 종료한 뒤 업데이트했다.
- 실제 적용한 그래픽·부팅 설정: Hardware(host), Cold Boot, RAM 4 GB. 설정 파일에서도 적용값을 확인했다.
- Pleos 차량 화면 / SCENIC 유지 여부: 패치와 Hardware 재부팅 후 차량 3D 화면 및 SCENIC 첫 화면·전국 코스 화면이 함께 표시됨.
- 지도 제목·마커·타일 표시: 선택한 문경 코스의 제목, 명소 마커 4개, Kakao 실제 타일 표시 확인. 장시간 안정성은 미검증.

## 공식 근거

- [Android Emulator 릴리스 노트](https://developer.android.com/studio/releases/emulator): 37.2.13, 2026-10-28, GLES 호스트 메모리 누수 수정.
- [Android Emulator 문제 해결](https://developer.android.com/studio/run/emulator-troubleshooting): Apple Silicon의 MoltenVK 제약과 Software·SwiftShader·Vulkan 비활성화 대안.
- [Pleos 설치·실행 가이드](https://document.pleos.ai/en/docs/connect/guide/getting-started/application-development/setup-connect-sdk-emulator): Mac ARM64 이미지, API 34, Pleos 2.1.0, RAM 4 GB, Cold Boot와 기존 Studio의 Hardware 설정.
- [Pleos 에뮬레이터 설정](https://document.pleos.ai/en/docs/connect/guide/getting-started/application-development/configure-pleos-connect-emulator): Cold Boot 요구와 GPU 사양. Apple GPU 대응 사양이나 Mac의 ES 3.2·Unreal 안정성을 보장하는 설정은 명시하지 않는다.

최종 추가 확인: 지도 타일·명소 마커뿐 아니라 Kakao 도로 조회 성공 후 도로선이 표시됐다. AI가 선택한 Lost in the Woods 플레이리스트도 YouTube Music 공식 웹 화면에서 제목·표지·곡 목록까지 확인했다. 실제 음원 재생 검증과는 구분한다.
