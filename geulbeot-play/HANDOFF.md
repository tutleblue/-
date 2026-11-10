# HANDOFF.md — 글벗(Geulbeot) 안드로이드 한글문서 앱

> ⚠️ **이 파일은 Play 제출용 사본 안의 복사본입니다.** 아래 설명은 원본(`../geulbeot`) 기준이라,
> 자체 업데이트처럼 이 사본에는 없는 기능도 그대로 적혀 있습니다. 이 사본이 원본과 어디가 다른지는
> `PLAY-BUILD.md`를 보세요. **개발은 원본에서 합니다.**

> 이 문서는 **다른 코딩 에이전트(Codex 등)가 이 파일 하나만 읽고도 작업을 그대로 이어받을 수 있도록** 쓴 자기완결적 핸드오프 브리프입니다.
> 이전 대화 맥락을 전혀 모른다는 전제로 작성되었습니다. 코드가 바뀔 때마다 "9. 현재 상태" 절을 갱신합니다.

- 문서 버전: 2026-09-11 최초 작성
- 대상 앱 버전: `version.json` 참조
- 저장소: `jykim5215/-` (GitHub), 브랜치 `claude/korean-file-app-hngrn7`, 프로젝트 폴더 `geulbeot/`

---

## 1. 프로젝트 개요와 목표

**안드로이드 전용으로, 한컴오피스 한글 문서(`.hwp` / `.hwpx`)를 휴대폰과 태블릿에서 열고(읽기), 편집하고(쓰기), 저장·내보내기할 수 있는 개인용 문서 앱**을 만든다.
"한글 앱에서 쓸 수 있는 기능을 최대한 쓸 수 있게" 하는 것이 목표이며, 뷰어에 그치지 않고 **실제 편집과 저장이 되는 에디터**를 지향한다.

앱 이름은 **글벗(Geulbeot)** 이다. ("한글"은 한컴의 제품 상표이므로 앱 이름으로 쓰지 않고, 설명 문구에서만 "한글 문서(HWP)"처럼 파일 형식을 가리키는 용도로 쓴다.)

---

## 2. 사용자 요구사항 정리

사용자가 실제로 말한 요구사항(원문 의도를 그대로 보존):

1. **"한글 파일을 읽고 쓰고 저장 등 한글 앱에서 사용가능한 모든 기능을 사용할 수 있게"**
   - 읽기: `.hwp`(HWP 5.0 바이너리), `.hwpx`(OWPML, KS X 6101) 모두 열기
   - 쓰기: 문서 내용과 서식을 앱에서 편집
   - 저장: 한글에서 다시 열 수 있는 형식으로 저장
   - "한글 앱에서 사용가능한 모든 기능" → 서식(글꼴/크기/굵게/기울임/밑줄/취소선/글자색/음영), 문단(정렬/줄간격/들여쓰기/글머리표·번호), 표, 그림, 찾기·바꾸기, 실행취소, 쪽 설정, 인쇄, 문서 통계 등 워드프로세서의 일반 기능 전반
2. **"나만의 앱을 만들어줘"** — 개인용. 계정·로그인·서버가 필요 없어야 한다.
3. **"안드로이드 전용"** — iOS/웹 대응은 범위 밖. 네이티브 안드로이드 앱으로 만든다.
4. **"휴대폰, 테블릿등에서 쓸듯"** — 폰과 태블릿 두 폼팩터 모두 제대로 동작해야 한다. 태블릿에서는 넓은 화면을 활용한 레이아웃이 필요하다.

### 제작 프로세스 규칙(사용자가 상시 적용하도록 지정한 규칙)

사용자는 애플리케이션 개발 시 항상 아래를 동시에 지키도록 요구했다:

- **(A) 배포용 패키지와 보안 점검**: 배포용 압축 파일을 항상 함께 만들고, 개인정보를 제거하고 보안 취약점을 점검한다. 코드를 고치고 결과를 보고할 때마다 **어떤 취약점을 개선했는지(없으면 없다고)** 보고 끝에 적는다.
- **(B) UI/UX**: 사용자가 말한 대로만 해석하지 말고, 실제 사용자라면 어떻게 쓸지 스스로 시뮬레이션해서 개편한다. **같거나 비슷한 기능이 여러 섹션에 중복 등장하면 통합**한다. UI 스타일 후보를 **3개 이상 HTML 화면으로 보여주고 사용자가 반드시 고르게** 한다.
- **(C) 디바이스 내 구동**: 로컬 서버를 띄우는 방식이 아니라 **디바이스 안에서 단독 구동**되어야 하고, 언제 들어가도 정상 구동되어야 한다.
- **(D) 바로가기**: 원클릭으로 실행 가능한 바로가기를 만들고, 아이콘은 주제와 관련된 것으로 한다.
- **(E) GitHub 릴리즈 기반 자체 업데이트**: `version.json`으로 semantic versioning 관리, 코드 수정 시 버전도 함께 올림. GitHub Release에 `version.json`과 빌드 산출물을 첨부. 앱 안에서 GitHub releases API를 호출해 새 버전을 확인하고, changelog를 보여주고, 사용자 승인 후 내려받아 적용. 네트워크 실패 시에도 앱은 계속 쓸 수 있어야 함. 보안 점검 시 **토큰/자격증명 하드코딩 여부**도 함께 점검.
- **(F) 핸드오프 브리프**: 본격적인 코드 작성 **전에** 이 `HANDOFF.md`를 먼저 쓰고, 진행 상황이 바뀔 때마다 갱신한다.

---

## 3. 해석 및 판단(요구사항을 그대로 두되, 기술적으로 풀어낸 부분)

요구사항을 바꾼 것이 아니라, 실현 가능한 형태로 구체화한 판단들과 그 이유:

| 판단 | 이유 |
|---|---|
| **편집 문서의 기본 저장 형식은 `.hwpx`** | `.hwpx`는 OWPML(KS X 6101) 국가표준이며 ZIP+XML이라 사양이 공개되어 있고, 한컴오피스 한글 2010 SP 이상/2014 이후에서 그대로 열린다. 손실 없이 안정적으로 쓸 수 있는 형식이다. |
| **`.hwp`(5.0 바이너리)도 읽기 + 쓰기 둘 다 구현하되, 쓰기는 "호환 저장"으로 명시** | 사용자가 "읽고 쓰고 저장"을 요구했으므로 바이너리 저장도 구현한다. 다만 HWP 5.0은 문서화되지 않은 영역이 있어, 구현 범위(문단/문자모양/문단모양/표/그림)를 넘어서는 요소는 보존되지 않을 수 있다. 앱 UI에서 이 점을 사용자에게 고지한다. |
| **원본 보존(Preservation) 정책** | `.hwp`를 열어 편집 후 같은 형식으로 저장할 때, 우리가 해석하지 못한 레코드는 **원본 스트림에서 그대로 복사**해 최대한 보존한다. 완전 재작성(rewrite from scratch)은 정보 손실이 크다. |
| **암호 설정 문서 / 배포용 문서는 읽기 거부 + 안내** | HWP의 암호·배포용(distribution) 문서는 추가 암호화가 걸려 있다. 우회 시도는 하지 않고, 사용자에게 "한글 PC판에서 암호를 해제한 뒤 다시 열어 달라"고 안내한다. |
| **PDF 내보내기 포함** | "한글 앱에서 쓸 수 있는 기능"에 PDF 저장이 포함된다고 보는 것이 자연스럽다. Android `PdfDocument`로 자체 렌더링한다. |
| **HWP 엔진을 순수 Kotlin/JVM 모듈로 분리** | Android SDK 없이도 JVM 단위테스트로 파서·라이터를 검증할 수 있게 하기 위함. UI 없이 포맷 로직만 독립 검증 가능. |
| **자체 업데이트는 "APK 다운로드 + 사용자 승인 설치"** | 안드로이드는 앱이 자기 코드를 임의로 교체할 수 없다. 규칙 (E)의 취지(=GitHub 릴리즈 확인 후 사용자 승인 하에 갱신)를 안드로이드에서 실현하는 정식 방법은 `PackageInstaller` / `ACTION_VIEW` APK 설치 인텐트이다. 서버는 띄우지 않고 네트워크 요청만 쓴다. |

---

## 4. 기술 스택

- **언어**: Kotlin 2.0.21
- **UI**: Jetpack Compose (Material 3), Compose BOM
- **적응형 레이아웃**: `material3-window-size-class` — 폰(Compact)은 단일 창, 태블릿(Medium/Expanded)은 2-pane
- **최소/목표 SDK**: `minSdk 26` (Android 8.0), `targetSdk 35` (Android 15), `compileSdk 35`
- **빌드**: Gradle 8.x + AGP 8.7.x, Kotlin Compose Compiler plugin
- **비동기**: Kotlin Coroutines + Flow
- **저장소 접근**: Storage Access Framework(SAF) — `ACTION_OPEN_DOCUMENT` / `ACTION_CREATE_DOCUMENT`. 광범위 저장소 권한(`MANAGE_EXTERNAL_STORAGE`)은 **쓰지 않는다**.
- **로컬 DB**: Room (최근 문서, 자동저장 스냅샷)
- **설정**: DataStore(Preferences)
- **네트워크**: `java.net.HttpURLConnection` (업데이트 확인 전용, 서드파티 HTTP 라이브러리 불필요)
- **압축/암호**: JDK 기본 `java.util.zip.Inflater/Deflater` (HWP는 raw deflate, `nowrap=true`)
- **테스트**: JUnit5 + kotlin.test (core 모듈), Compose UI 테스트(선택)

**의도적으로 쓰지 않는 것**: Apache POI(안드로이드에서 무겁고 `javax.xml` 의존 문제), 한컴 상용 SDK(라이선스), 원격 변환 서버(요구사항 C 위배).

---

## 5. 모듈 / 폴더 구조

```
geulbeot/
├── HANDOFF.md                  ← 이 문서
├── version.json                ← 앱 버전 + changelog (규칙 E)
├── README.md
├── SECURITY.md                 ← 보안 점검 결과 누적 기록
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
├── gradle/libs.versions.toml   ← 버전 카탈로그
│
├── core-hwp/                   ← ★ 순수 Kotlin/JVM. Android 의존성 0. 여기서 단위테스트로 검증.
│   └── src/main/kotlin/kr/geulbeot/hwp/
│       ├── model/              문서 모델(Document/Section/Paragraph/TextRun/CharShape/ParaShape/Table/Image)
│       ├── cfb/                CFB(OLE2 복합문서) 읽기/쓰기 — .hwp 컨테이너
│       ├── hwp5/               HWP 5.0 레코드 파서/라이터 (DocInfo, BodyText, BinData, FileHeader)
│       ├── hwpx/               OWPML(ZIP+XML) 리더/라이터
│       ├── text/               PrvText 추출, 일반 텍스트 입출력
│       └── util/               리틀엔디안 IO, zlib, UTF-16 처리
│   └── src/test/kotlin/        라운드트립 테스트(쓰기→읽기 일치 검증)
│
└── app/                        ← Android 앱 (Compose UI)
    └── src/main/
        ├── java/kr/geulbeot/app/
        │   ├── ui/             화면(문서함/에디터/뷰어/설정), 테마, 컴포저블
        │   ├── editor/         편집 상태, 실행취소 스택, 서식 적용
        │   ├── render/         페이지 렌더러(화면 + PDF 공용)
        │   ├── io/             SAF 래퍼, 최근문서, 자동저장
        │   ├── update/         GitHub Release 확인 + APK 다운로드 (규칙 E)
        │   └── shortcut/       동적 바로가기 / 새 문서 바로가기 (규칙 D)
        ├── res/                아이콘(적응형), 문자열(ko/en), 테마
        └── AndroidManifest.xml
```

---

## 6. 핵심 포맷 사양 메모 (Codex가 코드를 이어 쓸 때 필요한 지식)

### 6-1. `.hwp` = HWP 5.0 = CFB(OLE2) 복합 문서
- 컨테이너: Microsoft Compound File Binary. 512바이트 헤더, FAT/미니FAT 체인, 디렉터리 엔트리.
- 주요 스트림:
  - `FileHeader` (256바이트, **비압축**): 시그니처 `"HWP Document File"` + 0x00 패딩(총 32바이트), 버전 4바이트(`MM.nn.PP.rr` 순서는 리틀엔디안 `rr PP nn MM`), 속성 플래그 4바이트.
    - 플래그 bit0 = 압축 여부, bit1 = 암호 설정, bit2 = 배포용 문서.
  - `DocInfo`: 문서 공통 정보. 압축 플래그가 켜져 있으면 **raw deflate(zlib nowrap)** 로 압축됨.
  - `BodyText/Section0`, `Section1`, ... : 본문. 동일하게 압축.
  - `BinData/BIN0001.jpg` 등: 삽입된 그림. 개별적으로 압축될 수 있음.
  - `PrvText`: UTF-16LE 평문 미리보기 (빠른 텍스트 추출 폴백으로 유용).
  - `PrvImage`: 미리보기 이미지.
  - `\x05HwpSummaryInformation`: 요약 정보(제목/작성자 등) 속성 집합 스트림.
- **레코드 구조** (DocInfo/BodyText 공통): 4바이트 헤더 = `tagID(10bit) | level(10bit) | size(12bit)`.
  `size == 0xFFF` 이면 그 다음 4바이트가 실제 크기. 레코드는 `level`로 트리를 이룬다.
- 태그 상수: `HWPTAG_BEGIN = 0x10`.
  - DocInfo: DOCUMENT_PROPERTIES(0x10), ID_MAPPINGS(0x11), BIN_DATA(0x12), FACE_NAME(0x13),
    BORDER_FILL(0x14), CHAR_SHAPE(0x15), TAB_DEF(0x16), NUMBERING(0x17), BULLET(0x18),
    PARA_SHAPE(0x19), STYLE(0x1A), DOC_DATA(0x1B), ...
  - BodyText: PARA_HEADER(0x42), PARA_TEXT(0x43), PARA_CHAR_SHAPE(0x44), PARA_LINE_SEG(0x45),
    PARA_RANGE_TAG(0x46), CTRL_HEADER(0x47), LIST_HEADER(0x48), PAGE_DEF(0x49),
    FOOTNOTE_SHAPE(0x4A), PAGE_BORDER_FILL(0x4B), SHAPE_COMPONENT(0x4C), TABLE(0x4D), ...
- **`PARA_TEXT` 해석 규칙** (가장 실수하기 쉬운 부분): UTF-16LE WCHAR 배열이며 제어문자가 섞여 있다.
  - **char control (1 WCHAR 차지)**: 0, 10, 13, 24, 25, 26, 27, 28, 29, 30, 31
    (10 = 한 줄 끝/line break, 13 = 문단 끝, 30 = 묶음 빈칸, 31 = 고정폭 빈칸)
  - **inline control (8 WCHAR 차지)**: 4, 5, 6, 7, 8, 9, 19, 20 (9 = 탭)
  - **extended control (8 WCHAR 차지)**: 1, 2, 3, 11, 12, 14, 15, 16, 17, 18, 21, 22, 23
    (11 = 그리기 개체/표, 개체의 상세는 뒤따르는 CTRL_HEADER 레코드에 있음)
  - 8 WCHAR 형태는 `[ctrlId(2 WCHAR)] [info 4 WCHAR] [ctrlId 반복(2 WCHAR)]` 구조.
- `PARA_CHAR_SHAPE`: `(글자위치 UINT32, charShapeId UINT32)` 쌍의 배열 → 문단 내 서식 구간.

### 6-2. `.hwpx` = OWPML (KS X 6101)
- ZIP 컨테이너. 주요 엔트리:
  - `mimetype` (**반드시 첫 엔트리, 무압축 STORED**), 내용: `application/hwp+zip`
  - `version.xml`
  - `META-INF/container.xml`, `META-INF/manifest.xml`
  - `Contents/content.hpf` (OPF 형태 매니페스트/스파인)
  - `Contents/header.xml` (글꼴, 문자모양, 문단모양, 스타일 정의)
  - `Contents/section0.xml`, `section1.xml`, ... (본문)
  - `Preview/PrvText.txt`, `Preview/PrvImage.png`
  - `BinData/…` (그림 등)
- 네임스페이스 접두사: `hh`(head), `hp`(paragraph/본문), `hs`(section), `hc`(core), `ha`(app), `hml`(root)
- 본문 골격: `<hs:sec>` → `<hp:p paraPrIDRef paraprIDRef charPrIDRef>` → `<hp:run charPrIDRef>` → `<hp:t>텍스트</hp:t>`
- 쓰기가 읽기보다 훨씬 쉬우므로, **편집 결과의 1차 저장 형식**으로 삼는다.

---

## 7. 구현 계획 (순서와 이유)

> 진행 순서는 "검증 가능한 것부터" 원칙을 따른다. 포맷 엔진은 UI 없이 단위테스트로 검증되므로 먼저 만든다.

- **P0 — 설계/핸드오프**: 이 문서 작성. *(규칙 F: 코드보다 먼저)*
- **P1 — UI 스타일 후보 3종**: HTML 목업 3개를 만들어 사용자가 고르게 함. *(규칙 B)*
- **P2 — `core-hwp` 유틸 + 문서 모델**: 리틀엔디안 IO, zlib(raw deflate), UTF-16 처리, Document/Paragraph/TextRun 모델.
- **P3 — CFB 리더/라이터**: `.hwp` 컨테이너 입출력. 라운드트립 테스트(스트림 쓰기→읽기 동일성).
- **P4 — HWP 5.0 리더**: FileHeader → DocInfo → BodyText 레코드 파싱 → 문서 모델. 암호/배포용 문서 감지.
- **P5 — HWPX 리더/라이터**: ZIP + XML. 문서 모델 ↔ OWPML 왕복 테스트.
- **P6 — HWP 5.0 라이터**: 문서 모델 → 레코드 → 압축 → CFB. 자체 리더로 되읽어 일치 검증.
- **P7 — 안드로이드 앱 셸**: Compose 네비게이션, 문서함, SAF 열기/저장, 최근 문서, 적응형 레이아웃.
- **P8 — 에디터**: 서식 툴바, 실행취소/다시실행, 찾기·바꾸기, 표/그림, 문서 통계.
- **P9 — 렌더러 + PDF/인쇄 내보내기**.
- **P10 — 자체 업데이트 + 바로가기 + 아이콘**. *(규칙 D, E)*
- **P11 — 배포 패키지 + 보안 점검 문서 + GitHub 릴리즈**. *(규칙 A, E)*

### 주요 설계 결정
1. **문서 모델은 포맷 중립**: `.hwp`와 `.hwpx` 양쪽이 같은 `HwpDocument` 모델로 들어오고 나간다. 이래야 "hwp로 열어 hwpx로 저장" 같은 변환이 공짜로 된다.
2. **미해석 레코드 보존**: 파서는 모르는 레코드를 버리지 않고 `RawRecord(tagId, level, bytes)`로 보관한다. 같은 형식으로 저장할 때 되돌려 쓴다.
3. **UI 기능 중복 제거** *(규칙 B)*: "서식"은 한 곳(하단 컨텍스트 툴바)에서만 다루고 메뉴에 중복 배치하지 않는다. "저장/내보내기/공유"는 별도 메뉴 3개가 아니라 **하나의 "내보내기" 시트**에서 형식만 고르게 한다. "열기"도 문서함 + 메뉴로 흩어놓지 않고 문서함 하나로 통합한다.
4. **편집기는 문단 단위 가상 리스트**: 대형 문서에서 한 개의 거대한 TextField는 성능이 무너진다. 문단별 컴포저블 + LazyColumn으로 간다.

---

## 8. 제약 조건

### 프로세스 제약 (사용자 규칙)
- 배포용 압축 + 개인정보 제거 + 보안 점검 보고를 매 수정마다 수행 *(A)*
- UI 스타일 후보 3개 제시 후 사용자 선택 필수, 기능 중복 통합 *(B)*
- 로컬 서버 없이 디바이스 내 구동 *(C)* → 네이티브 앱이므로 자연히 충족. **WebView로 로컬 서버 띄우는 방식 금지.**
- 원클릭 바로가기 + 주제 관련 아이콘 *(D)*
- `version.json` 기반 semantic versioning, GitHub Release 게시, 앱 내 업데이트 확인 *(E)*
- push/release 전 사용자 확인 필수 *(E)*
- 이 문서를 살아있는 문서로 유지 *(F)*

### 기술 제약
- **개발 컨테이너에서 Android SDK(`dl.google.com`)가 조직 egress 정책으로 차단됨** → APK를 이 환경에서 빌드할 수 없다. 그래서 `core-hwp`를 순수 JVM 모듈로 떼어내 **Maven Central만으로 컴파일·테스트**하고, `app` 모듈은 사용자의 Android Studio에서 빌드한다. Codex가 다른 환경에서 이어받을 경우 Android SDK가 있으면 `./gradlew :app:assembleDebug`가 바로 된다.
- 실제 `.hwp` 샘플 파일이 개발 환경에 없다 → 검증은 **자체 라이터로 만든 파일을 자체 리더로 되읽는 라운드트립**과 공개 사양 대조로 한다. 실기기에서 실제 한글 문서로 검증하는 것은 사용자 몫으로 남는다.
- HWP 5.0은 일부 비공개 영역이 있어 100% 완전 호환은 불가능하다. 구현 범위를 README에 명시한다.
- 암호 설정/배포용 문서는 열지 않는다(우회 시도 금지).

### 보안·프라이버시 제약
- 문서 내용을 외부로 전송하지 않는다. 네트워크는 **업데이트 확인 한 가지 목적**으로만 쓴다.
- GitHub 토큰/자격증명을 코드에 넣지 않는다. 공개 저장소의 공개 API만 쓴다.
- 업데이트 다운로드는 HTTPS만 허용, 리다이렉트도 HTTPS만 따라간다. 다운로드한 APK는 설치 전 검증하고, 설치는 항상 사용자 승인을 거친다.
- 광범위 저장소 권한 대신 SAF를 쓴다. 앱은 사용자가 고른 파일에만 접근한다.
- 분석/추적 SDK를 넣지 않는다.

---

## 9. 현재 상태

**2026-09-11 — 1.0.0 완성. P0~P11 전부 구현 완료.**

| 항목 | 상태 |
|---|---|
| HANDOFF.md (P0) | ✅ |
| UI 스타일 후보 3종 (P1) | ✅ 제시 완료. **사용자 선택 결과: 세 후보 모두 채택하지 않고 "한글 앱을 그대로 따라가는, 색이 튀지 않는 무난한 사무용 앱"으로 결정.** 리본 탭(서식·문단·입력·쪽·보기), 회색/흰색 기반 + 오피스 블루 #2F5597 한 가지. |
| core-hwp 엔진 (P2~P6) | ✅ CFB, HWP 5.0 읽기/쓰기, HWPX 읽기/쓰기, 보존 정책 |
| 편집 엔진 / 찾기·바꾸기 | ✅ `api/DocumentEditor`, `api/DocumentSearch` |
| 안드로이드 앱 (P7~P9) | ✅ 문서함, 편집기, 리본, 표 편집, 찾기 바, 대화상자 8종, 설정 |
| PDF 내보내기 / 인쇄 (P9) | ✅ `render/DocumentPainter`, `render/DocumentExport` |
| 자체 업데이트 / 바로가기 / 아이콘 (P10) | ✅ `update/UpdateChecker`, `shortcut/Shortcuts`, 적응형 아이콘 |
| 배포 패키지 / 보안 문서 (P11) | ✅ `./gradlew packageDistribution`, `SECURITY.md` |
| 단위 테스트 | ✅ **48건 전부 통과** (`./gradlew :core-hwp:test`) |
| GitHub에 마지막으로 올린 버전 | 1.0.0 |
| 앱 내 업데이트 확인 기능 | ✅ 앱 시작 시 하루 1회 자동 확인 + 설정에서 수동 확인 |

### ⚠️ 검증 상태 — 이어받는 에이전트가 가장 먼저 알아야 할 것

- **`core-hwp`는 JVM 단위 테스트 48건으로 검증됨.** 형식 왕복, 제어문자 처리, 서식 보존, 형식 변환,
  편집 연산, 안전 상한까지 포함.
- **`app` 모듈은 이 개발 컨테이너에서 한 번도 컴파일되지 않음.** `dl.google.com`이 조직 egress
  정책으로 차단되어 Android SDK와 AndroidX 의존성을 받을 수 없기 때문입니다. Android Studio에서
  첫 빌드를 하면 컴파일 오류가 나올 수 있습니다. 정적 점검(누락 import 검사)은 통과했지만,
  시그니처 불일치나 Compose API 오용은 잡히지 않습니다.
- 첫 빌드에서 오류가 나면 그것이 정상입니다. 고치고 나서 이 표를 갱신해 주세요.

### 엔진 구현 중 확인된 중요한 사실

1. **CFB 섹터 번호는 부호 없는 값이다.** `MAX_REG_SECT`(0xFFFFFFFA)를 부호 있는 Int로 비교하면
   모든 유효 섹터가 걸러진다. 유효 섹터는 항상 양수이므로 `sect >= 0` 만으로 판별한다.
2. **8-WCHAR 컨트롤의 구조는 `[컨트롤 문자][정보 6개][컨트롤 문자]` 이다.** 확장 컨트롤은 그 정보
   6개 중 앞 2개가 ctrl id(UINT32, 하위 16비트 먼저)이다. 이 구조를 잘못 잡으면
   `PARA_CHAR_SHAPE` 위치가 전부 어긋나 표 뒤의 모든 서식이 밀린다.
3. **소스 파일에 제어문자 리터럴을 절대 넣지 말 것.** NUL이나 U+0005를 직접 쓰면 파일이 바이너리로
   취급되어 패치와 검색이 어긋난다. `Char(0)`, `String(CharArray(n))`, `Char(5)` 형태로 쓴다.
4. **조각 내기(slice)는 반드시 복사본을 반환해야 한다.** `Paragraph.normalise()`가 인접 텍스트를
   제자리에서 합치므로, 같은 인스턴스가 앞뒤 조각에 함께 들어가면 텍스트가 중복된다.
5. **글자/문단 모양의 `reserved` 필드는 우리가 해석하지 않는 비트만 담아야 한다.** 전체 속성
   워드를 그대로 담으면 같은 모양이 서로 다른 것으로 판정되어 모양 표가 계속 늘어난다.
6. **`.hwp`로의 신규 저장은 구역 정의(`secd`)를 직접 만들어야 하는 유일한 지점**이며 확신도가 가장
   낮다(`Hwp5Template`). 그래서 새 문서의 기본 저장 형식은 `.hwpx`다.
7. **Compose 텍스트 필드를 모델 변경마다 다시 채우면 안 된다.** IME 조합 중인 글자가 끊긴다.
   모델과 필드의 텍스트가 실제로 다를 때만 동기화한다(`ParagraphEditor`).
8. **설정값(SharedPreferences)은 Compose 상태가 아니다.** 테마·확대율처럼 화면에 영향을 주는 값은
   ViewModel에 상태로 미러링해야 바뀐 것이 반영된다.

### 다음에 할 만한 일
1. Android Studio에서 첫 빌드 → 컴파일 오류 수정 → 실기기에서 실제 한글 문서로 확인.
2. 머리말·꼬리말, 각주·미주 편집 (현재는 읽어서 보존만 함).
3. 화면에서의 실제 쪽 나눔 표시 (현재는 PDF/인쇄에서만 적용).
4. 수식·글상자 등 개체 편집.
5. GitHub 릴리즈에 APK 올리기 → 앱 내 업데이트 기능 실동작 확인.

### 이어받는 에이전트를 위한 메모
- 코드를 건드렸으면 **`version.json`의 version과 changelog를 함께 올리고**, 이 표를 갱신할 것.
- 보고할 때 **보안 점검 결과 한 줄**(개선한 취약점 / 없으면 "없음")을 반드시 덧붙일 것.
- `core-hwp`는 Android API를 import하지 말 것. 이 모듈의 JVM 테스트 가능성이 이 프로젝트의
  유일한 자동 검증 수단이다.
- `settings.gradle.kts`가 Android SDK 유무를 보고 `:app`을 자동으로 건너뛴다. SDK 없는 환경에서도
  `./gradlew :core-hwp:test`는 항상 돌아간다.
