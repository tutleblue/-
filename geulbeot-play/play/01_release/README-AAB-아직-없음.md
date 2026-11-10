# AAB는 아직 여기 없습니다

2026-09-11 기준, 서명된 AAB를 만들 수 없는 이유는 하나입니다: **업로드 키스토어가 아직 없습니다.**

Play는 서명 없는 AAB를 받지 않습니다. 그래서 여기에 파일을 올리는 대신 무엇이 필요한지 적어 둡니다.

## 지금 상태

- 앱 코드: 컴파일됩니다 (GitHub Actions로 확인)
- Play용 사본: 자체 업데이트 제거 완료, targetSdk 36 완료
- 서명: **없음**
- AAB: **없음**
- mapping.txt: AAB가 나오면 같은 폴더에 함께 보관해야 합니다. 이게 없으면 난독화된 크래시 로그를
  되읽을 수 없습니다.

## AAB를 받으려면

1. 키스토어를 만듭니다 (비밀번호를 물어보므로 **직접** 실행):

   ```bash
   keytool -genkeypair -v -keystore upload-keystore.jks \
     -keyalg RSA -keysize 2048 -validity 10000 -alias upload
   ```

2. GitHub 저장소 시크릿에 네 개를 넣습니다 (Settings → Secrets and variables → Actions):

   | 시크릿 | 값 |
   |---|---|
   | `PLAY_UPLOAD_KEYSTORE_BASE64` | `base64 -w0 upload-keystore.jks` 출력 전체 |
   | `PLAY_UPLOAD_STORE_PASSWORD` | 키스토어 비밀번호 |
   | `PLAY_UPLOAD_KEY_ALIAS` | `upload` |
   | `PLAY_UPLOAD_KEY_PASSWORD` | 키 비밀번호 |

3. `글벗 Play AAB` 워크플로를 돌리면 서명된 AAB와 `mapping.txt`가 나옵니다.

비밀번호는 GitHub 시크릿과 비밀번호 관리자에만 둡니다. 이 폴더에는 올리지 않습니다 —
키스토어와 비밀번호가 한곳에 있으면 백업이 아니라 유출 경로가 됩니다.

## 키스토어 백업

키를 잃으면 같은 패키지명(`kr.geulbeot.app`)으로 **영영 업데이트를 낼 수 없습니다.**
만드신 뒤 `upload-keystore.jks`를 이 폴더에 올려 두시면 백업이 됩니다.
단, **이 폴더가 링크 공개나 외부 공유로 설정돼 있지 않은지 먼저 확인**하세요.
