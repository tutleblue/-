# 이 폴더는 Play 제출용 사본입니다

- **만든 날짜**: 2026-09-11
- **원본**: `../geulbeot` — GitHub 릴리즈로 자체 업데이트하는 사이드로드 배포본. 앞으로의 개발은 **원본에서만** 합니다.
- **이 사본**: Google Play Console에 올릴 AAB를 뽑기 위한 것. 기능 개발을 여기서 하지 않습니다.

두 폴더를 헷갈리면 사고가 납니다. 편집기에서 경로에 `-play`가 있는지 먼저 보세요.

## 원본과 다른 점

### 1. 자체 업데이트 기능을 전부 걷어냈습니다

Play 정책(Device and Network Abuse)은 앱이 Play 밖에서 실행 코드를 받아 스스로를 설치·갱신하는 것을
금지합니다. GitHub 릴리즈에서 APK를 받아 설치하는 코드가 남아 있으면 심사에서 거절되거나, 통과하더라도
나중에 앱이 내려갑니다. 기능이 나빠서가 아니라 유통 경로가 달라서 빼는 것이고, 원본에서는 그대로
유효합니다.

| 지운 것 | 자리 |
|---|---|
| `update/UpdateChecker.kt` | 파일 삭제 (`UpdateChecker`, `UpdateInstaller`, `UpdateResult` 전부) |
| `UpdateDialog` 컴포저블 | `ui/settings/SettingsScreen.kt` |
| 설정의 "업데이트" 항목 (켤 때 확인 스위치 + 지금 확인 버튼) | `ui/settings/SettingsScreen.kt` |
| 앱 시작 시 하루 한 번 새 버전 확인 | `ui/GeulbeotRoot.kt` |
| `checkUpdatesOnStart` / `skippedUpdateVersion` / `lastUpdateCheckMillis` 와 저장 키 3개 | `data/Settings.kt` |
| `REQUEST_INSTALL_PACKAGES` 권한 | `AndroidManifest.xml` |
| `INTERNET`, `ACCESS_NETWORK_STATE` 권한 | `AndroidManifest.xml` — 아래 참고 |
| FileProvider의 `updates/` 캐시 경로 | `res/xml/file_paths.xml` |
| `version.json` | 자체 업데이트 매니페스트라 이 사본에는 쓸 데가 없음 |

**권한이 하나도 남지 않았습니다.** 이 앱에서 네트워크를 쓰던 코드는 업데이트 확인 한 곳뿐이었고,
그게 사라졌으니 `INTERNET`도 뺐습니다. 파일은 전부 저장소 접근 프레임워크(SAF)로만 다루므로 저장소
권한도 없습니다. Play Console의 **데이터 안전 섹션에서 "데이터를 수집하지 않음"으로 답할 수 있는
상태**이고, 그게 사실입니다.

유지한 것: FileProvider(내보낸 PDF·텍스트를 다른 앱에 건네는 용도), 문서함·자동 저장·바로가기 등
나머지 기능 전부.

> 인앱에서 업데이트 알림을 띄우고 싶으면 Play In-App Update API(`app-update-ktx`)로 대체할 수
> 있습니다. 요청이 없어서 넣지 않았습니다.

### 2. targetSdk 36 (Android 16)

Play는 2026년 8월 31일부터 신규 앱과 업데이트에 API 36 이상을 요구합니다. `compileSdk`/`targetSdk`를
36으로 올렸고, 그에 맞춰 AGP를 8.13.0으로, activity-compose를 1.10.1로 올렸습니다.

API 36을 target하면 켜지는 동작 변경 두 가지와, 이 앱에서의 처리:

- **가장자리까지 그리기(edge-to-edge) 옵트아웃이 사라집니다.** 원본은
  `WindowCompat.setDecorFitsSystemWindows(window, true)`로 옵트아웃하고 있었는데, 36에서는 이게
  무시됩니다. `MainActivity`에서 `enableEdgeToEdge()`를 호출하도록 바꿨습니다. 두 화면 모두
  `Scaffold`라 시스템 바 인셋은 Scaffold가 넣어 줍니다. `Theme.kt`의 `statusBarColor` /
  `navigationBarColor` 대입은 API 35부터 무시되는 값이라 지웠고, 아이콘 명암을 정하는
  `isAppearanceLight*Bars`만 남겼습니다.
- **600dp 이상 화면에서 방향·화면비 고정이 무시됩니다.** 이 앱은 원래 방향을 고정하지 않았고
  (`android:screenOrientation` 없음), 창 너비에 따라 한 화면/두 화면을 고르는 구조라 영향이 없습니다.

**남은 확인**: 이건 코드로 끝나지 않습니다. 릴리스 빌드를 실기기에 올려 **화면을 돌려 보고, 하단
내비게이션 바에 내용이 가리지 않는지 눈으로 확인**해야 합니다. 내부 테스트 트랙이 그 자리입니다.

기한 안에 대응이 어려우면 Play Console에서 11월 1일까지 연장을 신청할 수 있습니다.

### 3. 그 밖에

- `build.gradle.kts`의 배포 압축 버전은 `version.json` 대신 앱 모듈의 `versionName`에서 읽습니다.
- `minSdk`는 26 그대로입니다. 올릴 근거가 없습니다.

## 빌드

서명 키는 저장소에 없습니다. `keystore.properties`를 이 폴더 루트에 직접 만들고(비밀번호는 사용자만
입력합니다) 빌드하세요.

```properties
storeFile=../upload-keystore.jks
storePassword=<직접 입력>
keyAlias=upload
keyPassword=<직접 입력>
```

```bash
./gradlew bundleRelease
```

- 산출물: `app/build/outputs/bundle/release/app-release.aab`
- 매핑 파일: `app/build/outputs/mapping/release/mapping.txt` — R8을 켜 두었으므로, 난독화된 크래시
  로그를 되읽으려면 릴리스마다 버전별로 반드시 보관하세요.

`keystore.properties`가 없으면 릴리스 빌드는 서명 없이 나옵니다. Play는 서명 없는 AAB를 거부합니다.

### 이 컨테이너에서는 빌드가 안 됩니다

개발 컨테이너의 egress 정책이 `dl.google.com`을 막아 안드로이드 SDK를 받을 수 없습니다. 그래서
AAB는 GitHub Actions에서 만듭니다 — `.github/workflows/play.yml`. 손으로 돌리거나
`geulbeot-play/` 아래가 바뀌면 자동으로 돕니다.

서명하려면 저장소 시크릿 네 개가 필요합니다. 비밀번호는 사용자가 GitHub 시크릿에 직접 넣고,
Claude는 그 값을 보지 않습니다.

| 시크릿 | 값 |
|---|---|
| `PLAY_UPLOAD_KEYSTORE_BASE64` | `base64 -w0 upload-keystore.jks` 출력 |
| `PLAY_UPLOAD_STORE_PASSWORD` | 키스토어 비밀번호 |
| `PLAY_UPLOAD_KEY_ALIAS` | `upload` |
| `PLAY_UPLOAD_KEY_PASSWORD` | 키 비밀번호 |

시크릿이 없으면 서명 없이 빌드해 **컴파일만 확인**합니다. 그 결과물은 Play에 올릴 수 없습니다.
