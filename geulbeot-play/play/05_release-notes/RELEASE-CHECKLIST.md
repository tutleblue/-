# 글벗 출시 체크리스트

작성일: 2026-09-11
버전: versionName 1.0.0 / versionCode 1
패키지명: `kr.geulbeot.app` — **출시하면 영영 못 바꿉니다**
원본 프로젝트 경로: `<저장소>/geulbeot` (GitHub 릴리즈 자체 업데이트 버전 — **손대지 않았습니다**)
이 제출용 사본: `<저장소>/geulbeot-play`

## 빌드 (Claude 완료분)

- [x] 사본 생성, 원본 무수정 확인
- [x] 자체 업데이트 기능 제거 (아래 목록)
- [x] targetSdk 36 설정 + edge-to-edge 대응
- [x] versionCode 1 (최초 출시)
- [x] `isMinifyEnabled` / `isShrinkResources` = true (R8)
- [ ] **릴리스 빌드 서명 확인 (디버그 키 아님)** — 키스토어를 만들어야 진행됩니다
- [ ] **AAB 생성**
- [ ] **mapping.txt 보관**

### 제거한 항목

- `update/UpdateChecker.kt` (파일 삭제 — `UpdateChecker`, `UpdateInstaller`, `UpdateResult`)
- `ui/settings/SettingsScreen.kt`: `UpdateDialog` 컴포저블, 설정의 "업데이트" 항목 전체
- `ui/GeulbeotRoot.kt`: 앱 시작 시 새 버전 확인
- `data/Settings.kt`: `checkUpdatesOnStart`, `skippedUpdateVersion`, `lastUpdateCheckMillis`와 키 3개
- `AndroidManifest.xml`: `REQUEST_INSTALL_PACKAGES`, `INTERNET`, `ACCESS_NETWORK_STATE`
- `res/xml/file_paths.xml`: `updates/` 캐시 경로
- `version.json` (자체 업데이트 매니페스트)

### 유지한 것

- FileProvider — 내보낸 PDF·텍스트를 다른 앱에 건네는 용도
- 문서함, 자동 저장, 런처 바로가기 등 나머지 기능 전부

**선언한 권한이 0개입니다.** 데이터 안전 섹션에서 "데이터를 수집하지 않음"으로 답할 수 있고,
그게 사실입니다.

## 스토어 자료

- [x] 앱 이름 (30자 이내) — `02_store-listing/store-listing.md`
- [x] 짧은 설명 (80자 이내)
- [x] 자세한 설명 (4,000자 이내) — **초안입니다. 읽어 보고 고치세요**
- [x] 아이콘 512×512 — `03_graphics/icon-512.png`
- [x] 그래픽 이미지 1024×500 — `03_graphics/feature-1024x500.png`
- [ ] **스크린샷 2장 이상 — 없습니다. 이게 없으면 출시가 불가능합니다**
- [x] 변경 사항 (whatsnew) — `05_release-notes/whatsnew-ko-KR.txt`

## 사용자가 직접 할 일

### 지금

- [ ] **업로드 키스토어 만들기** (`04_signing/SIGNING-INFO.md`의 `keytool` 명령 — 비밀번호를 물어보므로 직접)
- [ ] `geulbeot-play/keystore.properties` 만들어 비밀번호 채우기
- [ ] 채웠다고 알려 주면 Claude가 `bundleRelease`를 돌려 서명된 AAB를 뽑습니다

### 실기기에서

- [ ] 릴리스 빌드 설치 후 **화면 회전** 확인 (targetSdk 36에서 방향 고정이 무시됩니다)
- [ ] **하단 내비게이션 바에 내용이 가리지 않는지** 확인 (edge-to-edge 강제)
- [ ] R8이 켜져 있으므로 전체 기능을 한 번씩 눌러 볼 것 — 조용히 깨지는 건 여기서 잡힙니다
- [ ] 이때 **스크린샷 2장 이상** 촬영 (편집 화면 + 문서함 화면)

### Play Console에서

- [ ] 앱 생성 (앱 이름·기본 언어 한국어·무료)
- [ ] 개인정보처리방침 URL 준비 및 등록 (`02_store-listing/privacy-policy-draft.md` 초안 사용)
- [ ] 데이터 안전 섹션 작성 — **수집·공유하는 데이터 없음**
- [ ] 콘텐츠 등급 설문 완료 (법적 진술이라 직접 답해야 합니다)
- [ ] 대상 연령층 / 광고 포함 여부 신고 (광고 없음)
- [ ] 내부 테스트 트랙에 AAB 업로드 후 실기기 설치 확인
- [ ] (개인 개발자 계정 최초 출시) **비공개 테스트 요건** — 테스터 12명이 14일 연속 참여해야
      프로덕션 신청이 열립니다. 계정 유형에 따라 다르니 Console 안내를 확인하세요
- [ ] 프로덕션 승격 및 검토 제출

## 메모

- targetSdk 36은 2026-08-31부터 필수입니다. 기한 대응이 어려우면 Play Console에서 11월 1일까지
  연장을 신청할 수 있습니다.
- 앱 이름에 "한글"이 들어갑니다. 한컴의 상표와 혼동될 소지가 있다면 심사에서 지적될 수 있으니,
  스토어 설명에 넣어 둔 "한컴의 한글(HWP)과 무관한 앱"이라는 문장은 지우지 마세요.
