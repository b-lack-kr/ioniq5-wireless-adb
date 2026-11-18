# 무선 ADB

NEMESIS용 무선 ADB 제어 앱의 개발 소스입니다. 앱 아이콘으로 실행하며 시작·정지, Wi-Fi 정보와 QR 코드, 연결 상태 및 송수신 통계를 표시합니다. 인증된 일회성 셸 명령을 화면에 표시하며 대화형 셸의 후속 입력은 표시하지 않습니다.

기존 CarPlay 핫스팟을 사용하고, CarPlay가 없으면 별도 USB Wi-Fi AP를 시작하도록 구현했습니다. **자체 AP는 실차 검증 전이며 배포용 설치 파일은 아직 제공하지 않습니다.** 대상은 한국형 아이오닉 5 NEEV.KOR.0000.V039.001.260902, tcc803x, Android 4.4입니다.

## 인증 및 빌드

개인정보 보호를 위해 Mac의 ADB 공개키, APK 서명키, Wi-Fi 정보, 설치 식별자와 개인용 빌드 결과물은 이 저장소에 포함하지 않습니다. 네이티브 ADB는 단일 공개키를 고정하는 구조이므로 사용자 자신의 공개키로 설치 패키지를 구성해야 합니다. 현재 패키징 절차는 개발 환경에 의존하며 범용 설치 도구는 준비되지 않았습니다.

별도 AP에는 HKG USB Wi-Fi 드라이버와 도구가 필요하지만 이 저장소에는 해당 바이너리를 포함하지 않습니다. `package/runtime/ap/service.sh`에 검증 해시와 필요한 파일명이 지정되어 있습니다. 빌드에는 Android 4.4 대상 도구, NDK, NEMESIS 패키저 및 HMG-API ADB 소스가 필요합니다.

## 라이선스

AP 및 차량 연동에 사용된 코드는 AGPL-3.0-or-later이며 [LICENSE](LICENSE)를 확인해 주세요. 추가 구성 요소의 출처와 라이선스는 `package/runtime/ADBD-NOTICES.txt`, `package/runtime/QR-NOTICES.txt`, `ui/vendor/UPSTREAM.txt`에 있습니다.
