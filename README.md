# 0.5초 인식 버전 (v1.1.0)

- 카메라 영상에서 **500ms마다 최대 1회** 7세그먼트 판독을 시도합니다. 실제 처리 지연으로 더 길어질 수 있으며 일정한 시각에 정확히 한 번씩 실행되는 타이머는 아닙니다.
- 화면에 매 인식 결과를 표시합니다. 읽지 못하면 `— (인식 실패)`로 표시됩니다.
- 기본 변경 확정 프레임을 3에서 **1**로 바꾸었습니다. (2로 설정하면 두 번 연속 같은 값, 최소 약 1초 확인 후 기록합니다.)
- 이전과 같이 **숫자가 바뀌었을 때만** CSV에 기록합니다. 0.5초마다 같은 값을 반복 기록하는 방식은 아닙니다.
- GitHub Actions SDK 설치 시 기본 패키지 대신 `platform-tools`를 명시했습니다.

## 기존 GitHub 저장소 업데이트

압축을 풀어 `SevenSegmentLogger` 폴더 안의 파일들을 기존 저장소의 같은 경로에 덮어씌우세요. 특히 다음 파일을 업데이트해야 합니다:

- `app/src/main/java/com/example/sevensegmentlogger/MainActivity.java`
- `app/build.gradle`
- `.github/workflows/build-apk.yml`

업로드 후 Actions > Build Galaxy APK의 성공한 실행 > Artifacts에서 APK를 내려받으세요. 기존 앱과 동일한 applicationId이므로 같은 서명으로 만든 디버그 APK라면 업데이트 설치 가능하지만, 서명이 다르면 기존 앱을 삭제해야 할 수 있습니다. **앱을 삭제하면 내부 저장 기록도 삭제될 수 있으니 먼저 CSV를 내보내세요.**

# 7세그먼트 실시간 기록기 (Android)

갤럭시 후면 카메라로 7세그먼트 LCD/LED 숫자를 읽고, 값 변경 시 자동으로 시간/값을 CSV에 저장하는 **안드로이드 Java 프로젝트**입니다.

## 빌드/설치

1. 최신 Android Studio에서 이 프로젝트 폴더(`SevenSegmentLogger`)를 엽니다.
2. Gradle Sync를 완료합니다. Android SDK 36 및 JDK 17 이상이 필요합니다.
3. 갤럭시를 USB 디버깅으로 연결하고 **Run ▶** 을 누르거나 **Build > Build APK(s)** 를 실행합니다.
4. 출력 APK 경로: `app/build/outputs/apk/debug/app-debug.apk`.

> 이 제공물은 **소스 프로젝트**입니다. 현재 생성 환경에는 Android SDK/Gradle이 없어 APK를 직접 빌드하거나 실기기 테스트하지 못했습니다.

## 사용법

1. 카메라 권한을 허용하고 갤럭시를 고정합니다. (카메라 영상은 저해상도 흑백으로 표시됩니다.)
2. 화면 하단 **X/Y/너비/높이** 슬라이더로 청록색 인식 상자를 숫자만 꽉 들어오도록 조정합니다.
3. **자릿수**를 디스플레이 실제 숫자 칸 수로 설정합니다. 필요하면 **소수점 판독**을 켜세요.
4. 숫자 LED가 밝으면 **밝은 세그먼트**를 켜고, 어두운 LCD 숫자라면 끄세요.
5. **밝기 임계값**을 움직여 표시되는 '인식 값'이 맞게 나오는지 확인합니다.
6. **측정 시작**: 값이 연속 N프레임 일치할 때 값이 달라진 경우에만 자동 기록합니다. 시작 시 현재 인식값도 1회 기록합니다.
7. **측정 중지** 후 (혹은 측정 도중) **CSV 저장**을 눌러 다운로드 폴더 등 원하는 위치로 내보냅니다.

CSV 헤더는 `timestamp_utc,elapsed_seconds,value`이며 UTC ISO 8601 시각과 측정 시작 후 경과 초를 함께 기록합니다. 기록이 채택될 때마다 앱 내부 저장소 CSV로 `flush()`됩니다.

## 판독 제한과 튜닝

- 이 버전은 **고정 카메라/정면 7세그먼트 숫자**에 최적화된 규칙 기반 판독기입니다. 숫자의 위치가 일정하고, 한 칸의 너비가 비슷해야 합니다.
- 숫자 내부에서 7개의 샘플 선을 검사합니다. 직사광선, 반사, 플리커, 기울기, 특이한 폰트/특수문자 및 큰 숫자 간격에서는 오인식하거나 읽지 못할 수 있습니다.
- 소수점 위치는 각 숫자 오른쪽 아래라고 가정합니다. 마지막 숫자 뒤 소수점은 판독하지 않습니다.
- **인식 값이 잘못 표시되면 기록하지 마세요.** 먼저 ROI/자릿수/밝기 임계값/밝은 세그먼트 옵션을 조절하고, 표준값 몇 개로 대조 검증하세요.
- 초당 최대 약 10회 분석하며, '프레임 N회 확인'은 시간 기준으로 약 N×0.1초 이상입니다. 변경이 너무 빠르면 건너뛸 수 있습니다.
- 측정 중 화면 켜짐 유지/백그라운드 서비스 기능은 아직 없습니다. 화면을 켜 둔 상태로 사용하세요.
- 데이터는 세션별 내부 파일로 남습니다. 앱을 제거하면 내부 파일도 삭제되므로 **CSV 저장**으로 내보내세요.
- 기본 설정은 예시로, 실제 장비 영상 없이 모든 7세그먼트 기기에서의 정확도를 보증할 수 없습니다.

## 구조

- `app/src/main/java/com/example/sevensegmentlogger/MainActivity.java`: 카메라 취득, 기록, 설정, CSV 내보내기
- `app/src/main/java/com/example/sevensegmentlogger/SevenSegmentDecoder.java`: 7세그먼트 숫자/소수점 판독
- `app/src/main/java/com/example/sevensegmentlogger/CameraOverlayView.java`: 영상 및 ROI 표시

카메라 취득은 CameraX 1.6.2 기반이며, 숫자 판독은 별도 OCR 라이브러리나 인터넷 연결 없이 휴대폰에서 실행됩니다.

## Android Studio 없이 갤럭시 APK 받기 (GitHub Actions)

이 프로젝트의 `.github/workflows/build-apk.yml`을 이용하면 **GitHub 서버가 APK를 컴파일**합니다. 컴퓨터에 Android Studio 또는 Android SDK 설치가 필요하지 않습니다.

1. [github.com/new](https://github.com/new)에서 빈 저장소를 생성합니다(가능하면 **Private**). 기본 브랜치는 `main`을 사용합니다.
2. 다운로드한 ZIP 파일을 풀어 `SevenSegmentLogger` 폴더 **안의 전체 내용**을 저장소 루트에 업로드합니다. 숨김 폴더 `.github`도 반드시 포함합니다. GitHub의 `Add file → Upload files`에서 폴더 내부 파일을 끌어놓고 커밋합니다. 프로젝트는 `app/`, `settings.gradle`, `build.gradle`, `.github/workflows/build-apk.yml`이 **저장소 루트**에 있어야 합니다.
3. 저장소의 **Actions** 탭에서 **Build Galaxy APK**를 선택합니다. Push 후 자동 빌드가 시작될 수 있습니다. 시작되지 않으면 **Run workflow → Run workflow**를 누릅니다.
4. 빌드 결과가 초록색 ✅ 이면 해당 실행 페이지 아래 **Artifacts**의 `SevenSegmentLogger-Galaxy-APK`를 클릭하여 ZIP을 다운로드합니다.
5. 내려받은 ZIP 파일의 압축을 풀어 `app-debug.apk`를 갤럭시로 옮겨 실행합니다. 필요 시 Android가 묻는 **이 출처의 앱 설치 허용**을 허용합니다.
6. 카메라 권한을 허용하고 디스플레이 영역 및 인식 옵션을 조정한 후 측정합니다.

**참고:** GitHub Actions는 사용자 저장소에서 빌드되므로 이 안내의 파일은 미리 컴파일된 APK가 아닙니다. `app-debug.apk`는 개발용(debug) 서명 APK로 직접 설치할 수 있지만 Play 스토어 배포용은 아닙니다. GitHub 계정 및 인터넷은 APK **빌드/다운로드 시에만** 필요하고, 앱이 실행되는 동안 인식/기록은 오프라인으로 가능합니다. GitHub Artifact는 ZIP으로 제공되며 30일 후 만료될 수 있습니다. GitHub에서 외부 코드 실행을 허용하는 Actions 설정이 필요할 수 있습니다.
