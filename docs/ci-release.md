# CI / 릴리즈 정책

## CI

- `Android CI`
  - `testDebugUnitTest`
  - `lintDebug`
  - `assembleDebug`
  - `assembleRelease`
  - 테스트/린트/APK 아티팩트 업로드
- `Maestro UI Test`
  - 대화 화면·홈 레이아웃·Maestro flow/워크플로 변경 push와 `workflow_dispatch` 수동 실행을 지원하며 릴리즈 필수 게이트에는 포함하지 않음
  - Ubuntu + KVM, API 30 x86_64 에뮬레이터에서 실행하도록 구성
  - 에뮬레이터 시작 전에 debug APK를 빌드하고 애니메이션을 비활성화
  - 대화 수집 화면, 파일 선택 취소, 홈, 응답 설정, 기존 학습 삭제 버튼의 대화 선택 화면 이동을 검사
  - JUnit 결과, debug output, 테스트 아티팩트, logcat 업로드
- `Conversation storage tests`
  - push / pull request / 수동 실행에서 Ubuntu + KVM, API 29 x86_64 사용
  - `ConversationStoreInstrumentedTest`로 실제 SQLite 저장·가져오기·중복·삭제·방 식별 등의 동작을 검사
  - Android instrumentation 결과와 HTML 보고서 업로드
  - 성공 시 같은 작업에서 빌드한 debug 앱·instrumentation APK를 `device-test-apks`로 업로드해 실기기에서 재검증 가능

## 대화 수집 검증 범위와 상태

- `.github/workflows/conversation-storage.yml`은 SQLite 통합 검증, `maestro.yml`은 사용자 화면 검증을 담당한다.
- Maestro는 `conversations.yaml`, `home.yaml`, `settings.yaml`, `room-management.yaml`만 별도 실행 폴더에 모아 실행한다. 각 flow는 앱 데이터를 초기화하며 개인 대화 파일을 사용하지 않는다.
- 설정의 모델 안내는 `text_provider_summary` ID로 스크롤·표시를 검사한다. 방 관리의 `debug_room_scroll` 확인 직후에는 `hideKeyboard`를 호출하지 않는다. 키보드가 없는 상태에서 뒤로가기로 처리되어 메인 화면으로 이탈하는 회귀를 방지한다.
- 설정에서 `응답 엔진` 제목만 보이고 아래 라벨은 화면 밖인 경우를 위해 `로컬 응답 엔진`에도 별도 스크롤을 수행한다.
- 답장 근거 안내는 런타임 문구와 XML 기본 문구가 다를 수 있어 `text_grounding_summary` ID로 검사한다. 저장 후 메인 화면 복귀는 `AI 자동 답장`으로 위로 스크롤해 확인한다.
- CLI 설치와 출력 옵션은 [공식 설치 안내](https://github.com/mobile-dev-inc/Maestro#installing-the-cli)와 [테스트 아티팩트 안내](https://docs.maestro.dev/cli/test-output-directory)를 따른다. 실제 실행한 CLI 버전은 워크플로 로그에 남긴다.
- 이 구성의 추가 자체는 테스트 통과를 의미하지 않는다. 최종 커밋의 Actions 실행 결과와 업로드된 보고서를 확인해야 한다.
- UI smoke는 파일 선택 창 진입·취소까지 검사한다. 실제 대화 파일 선택 후 가져오기 전체 과정, 카카오톡 알림 식별 안정성, ARM64 기기의 모델 추론·실제 답장 도착은 별도 실기기 검증이 필요하다.

## 릴리즈 게이트

- 태그 릴리즈 전에는 JVM 테스트, lint, debug/release APK 빌드가 모두 통과해야 한다.
- 릴리즈 워크플로는 `validate` 잡이 성공한 뒤에만 GitHub Release를 생성한다.
- Maestro는 UI 변경 push 또는 로컬/수동 워크플로에서 검증한다.
- `validate` 단계의 `assembleRelease` 는 서명 시크릿이 없어도 계속 실행 가능해야 하며, 이 경우 unsigned 결과물은 검증용으로만 취급한다.

## 릴리즈

- `v*` 태그 푸시 시 GitHub Release 생성
- GitHub Release에는 최종 사용자용 signed release APK 1개만 첨부
- 릴리즈 노트는 GitHub 자동 생성 노트를 기본으로 사용
- 릴리즈 자산 이름은 `kakao-auto-reply-vX.Y.Z.apk` 형식으로 재패키징한다
- debug APK와 unsigned release APK는 CI 검증 산출물일 수 있지만 GitHub Release의 일반 설치 자산으로 게시하지 않는다

## 릴리즈 서명 시크릿

- 태그 릴리즈 게시에는 아래 GitHub Secrets가 모두 필요하다.
  - `ANDROID_RELEASE_KEYSTORE_BASE64`
  - `ANDROID_RELEASE_STORE_PASSWORD`
  - `ANDROID_RELEASE_KEY_ALIAS`
  - `ANDROID_RELEASE_KEY_PASSWORD`
- 워크플로는 위 시크릿 중 하나라도 비어 있으면 릴리즈 잡에서 즉시 실패해야 한다.
- CI는 `ANDROID_RELEASE_KEYSTORE_BASE64` 를 임시 keystore 파일로 복호화한 뒤 환경 변수 `ANDROID_RELEASE_STORE_FILE` 로 Gradle에 전달한다.
- 로컬 개발과 일반 CI 검증에서는 위 환경 변수가 없어도 `assembleRelease` 자체는 유지되며, 이 경우 결과물은 서명되지 않은 검증용 APK다.

## JDK

- 이 저장소의 Gradle/Kotlin 조합은 **JDK 21 기준**으로 검증합니다.
- 기본 `java`가 프로젝트의 재현 가능한 bootstrap 범위(17–23)를 벗어나면 검증 기준 JDK로 전환합니다.
- `gradlew` bootstrap은 JDK 21을 먼저 찾고 없으면 JDK 17로 fallback 합니다. Gradle 9에는 JDK 17 이상이 필요합니다.
- JDK 기준을 바꾸면 `gradlew` 의 bootstrap 탐색 순서와 README 안내를 함께 갱신해야 합니다.
- CI와 로컬 개발 모두 가능하면 `JAVA_HOME` 을 JDK 21로 고정하는 것을 권장합니다.

## 빌드 도구와 품질 게이트

AGP 9.4.0, Gradle 9.6.0, AGP 내장 Kotlin, compile/target SDK 37을 사용합니다. CI JDK는 21, 앱 바이트코드 대상은 11입니다. 의존성은 gradle/libs.versions.toml에 고정하며 LiteRT-LM은 0.17.0입니다. org.json은 JVM testImplementation에만 포함합니다.

Lint 경고는 오류로 처리합니다. CI는 APK 가짜 토큰 포함 검사와 XML/HTML Lint 보고서를 제공합니다. Maestro는 앱 소스·리소스·빌드 의존성 변경에도 실행합니다.

공식 근거: [AGP 호환성](https://developer.android.com/build/releases/agp-9-4-0-release-notes), [내장 Kotlin](https://developer.android.com/build/migrate-to-built-in-kotlin), [Android 17](https://developer.android.com/about/versions/17/setup-sdk), [LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM/releases/tag/v0.17.0).
