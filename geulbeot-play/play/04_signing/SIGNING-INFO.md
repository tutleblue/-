# 글벗 서명 정보

> 이 문서에는 비밀번호를 적지 않습니다.
> 비밀번호는 비밀번호 관리자에 보관하고, 여기에는 "어디에 있는지"만 남깁니다.

## 기본 정보

- 패키지명: `kr.geulbeot.app` — **출시 후에는 영영 바꿀 수 없습니다.**
- 키 별칭(alias): `upload`
- 키 알고리즘: RSA 2048 / 유효기간 10000일
- 생성일: (키를 만든 날짜를 적으세요)

## 업로드 키스토어 위치

- 파일명: `upload-keystore.jks`
- 보관 위치: (예: 로컬 `~/keystores/` + 이 드라이브 폴더의 백업)
- 비밀번호 보관처: (예: 비밀번호 관리자의 "글벗 업로드키" 항목)

## 아직 만들지 않았다면

키스토어를 만드는 명령은 비밀번호를 대화형으로 물어봅니다. **직접 실행하세요.**
Claude는 이 비밀번호를 보지 않으며, 어떤 파일에도 적지 않습니다.

```bash
keytool -genkeypair -v -keystore upload-keystore.jks \
  -keyalg RSA -keysize 2048 -validity 10000 -alias upload
```

만든 뒤 `geulbeot-play/keystore.properties`를 직접 만들어 채우세요 (이 파일은 `.gitignore`에
들어 있습니다):

```properties
storeFile=../upload-keystore.jks
storePassword=(직접 입력)
keyAlias=upload
keyPassword=(직접 입력)
```

## 지문 (fingerprint)

```
SHA-1:   (아래 명령 출력에서 복사)
SHA-256: (아래 명령 출력에서 복사)
```

확인 명령 (비밀번호를 물어보므로 직접 실행):

```bash
keytool -list -v -keystore upload-keystore.jks -alias upload
```

## Play App Signing

- 사용 여부: 사용 (Play Console에서 앱을 만들 때 기본으로 켜집니다)
- 켜면 Google이 실제 앱 서명 키를 보관하고, 손에 있는 것은 **업로드 키**입니다
- 업로드 키를 잃어도 Play Console에서 재설정을 요청할 수 있지만 며칠 걸립니다

## 주의

- 이 키를 잃으면 같은 패키지명으로 업데이트를 낼 수 없습니다
- 키스토어 파일을 클라우드에 올릴 때 **비밀번호와 분리해서** 보관하세요.
  한곳에 같이 두면 백업이 아니라 유출 경로가 됩니다
- 저장소(git)에 커밋하지 마세요 — `.gitignore`에 `*.jks`, `keystore.properties`가 들어 있는지
  확인했습니다
