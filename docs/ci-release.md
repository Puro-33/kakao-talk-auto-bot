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

## 대화 수집 검증 범위와 상태

- `.github/workflows/conversation-storage.yml`은 SQLite 통합 검증, `maestro.yml`은 사용자 화면 검증을 담당한다.
- Maestro는 `conversations.yaml`, `home.yaml`, `settings.yaml`, `room-management.yaml`만 별도 실행 폴더에 모아 실행한다. 각 flow는 앱 데이터를 초기화하며 개인 대화 파일을 사용하지 않는다.
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
- 기본 `java` 가 JDK 25 이상이면 Gradle Kotlin DSL 초기화 단계에서 실패할 수 있습니다.
- `gradlew` bootstrap 단계에서 호환 가능한 JDK 21을 먼저 찾고, 없으면 JDK 17, JDK 11 순으로 fallback 합니다.
- JDK 기준을 바꾸면 `gradlew` 의 bootstrap 탐색 순서와 README 안내를 함께 갱신해야 합니다.
- CI와 로컬 개발 모두 가능하면 `JAVA_HOME` 을 JDK 21로 고정하는 것을 권장합니다.
