# 카카오톡 자동응답 구현 조사

2026-09-13 기준 공개 GitHub 저장소와 Android 공식 API 문서를 비교해 현재 앱의 알림 기반 설계를 점검했다. 조사 대상은 카카오톡 알림을 직접 다루는 Android 프로젝트와 카카오톡 봇의 운영 문서를 우선했다.

## 조사 결과

| 사례 | 관찰한 방식 | 현재 앱에 적용할 판단 |
| --- | --- | --- |
| [lee775/KakaoBot-Android](https://github.com/lee775/KakaoBot-Android) | `NotificationListenerService`에서 `com.kakao.talk` 알림을 파싱하고, `RemoteInput`과 `PendingIntent`로 응답한다. | 알림 수신, 방·보낸 사람·본문 추출, 알림 액션 세션 보존이라는 현재 경로와 일치한다. |
| [DarkTornado/KakaoTalkBot](https://github.com/DarkTornado/KakaoTalkBot/releases) | Android 11/KakaoTalk 9.7 이후 알림 구조 변경에 대응하도록 파싱 전략을 분기하고, 세션 저장·방 이름 표시 문제를 별도로 수정했다. | `EXTRA_TEXT` 하나에 의존하지 않고 `BIG_TEXT`·메시지 배열·제목 계층을 계속 방어적으로 읽어야 한다. |
| [tmdduq/notifyreply](https://github.com/tmdduq/notifyreply) | 카카오톡 알림의 `android.title`, `android.subText`, `android.text`를 방 정보·발신자·본문 후보로 사용하고, RemoteInput action을 찾아 전송한다. | 방 이름과 발신자 후보의 우선순위를 고정하고, action이 없는 알림은 전송하지 않는 현재 안전장치를 유지한다. |
| [bssm-oss/kakao-talk-auto-bot](https://github.com/bssm-oss/kakao-talk-auto-bot) | 로컬 Gemma 모델, 방별 설정, SQLite 대화 기록, 알림 기반 자동응답을 한 기기 안에서 운영한다. | 이 프로젝트의 제품 요구와 가장 가까운 비교 대상이다. 선택 방의 수집 상태와 응답 설정을 같은 흐름에서 생성해야 한다. |

카카오톡 플러스친구의 서버 API([plusfriend/auto_reply](https://github.com/plusfriend/auto_reply))는 소비자 카카오톡 알림을 읽는 앱과 다른 제품 경로다. 따라서 이 앱의 개인 계정 대화 자동응답을 서버 API나 카카오톡 내부 API로 우회하는 근거로 사용하지 않는다.

## 플랫폼 제약

Android는 `NotificationListenerService`가 시스템에서 게시·삭제되는 알림을 받는 서비스이며, 사용자가 알림 접근 권한을 허용해야 한다. 서비스는 `onListenerConnected()` 이후에만 작업해야 한다([공식 API](https://developer.android.com/reference/android/service/notification/NotificationListenerService)). 실제 답장은 알림 action의 `RemoteInput` 결과를 `PendingIntent`에 넣어 대상 앱으로 전달하는 방식이다([RemoteInput 공식 API](https://developer.android.com/reference/android/app/RemoteInput)). 따라서 알림을 꺼 두거나 카카오톡이 해당 메시지에 알림을 만들지 않으면, 이 권한 모델로는 새 메시지를 안정적으로 수집하거나 답장할 수 없다. 직접 카카오톡 창을 조작하는 접근성 경로는 별도 제품 모드가 되므로 이번 요구사항인 “알림을 받을 때만 답장” 범위에는 넣지 않는다.

## 반영한 수정

방 직접 선택은 로컬 `conversationId`를 만들고, 방별 JSON 응답 설정을 저장한다. 기존에는 이때 JSON의 `captureEnabled`는 `true`여도 SQLite `conversations.capture`가 기본값 `0`으로 남았다. 이후 알림 처리에서 `ConversationStore.isCaptureEnabled()`가 먼저 검사되므로 메시지가 도착해도 기록·스타일 학습이 실행되지 않는 상태였다.

`BotManager.addSelectedRoom()`에서 설정 저장 직후 SQLite 수집 플래그를 설정의 `captureEnabled` 값과 동기화했다. 이제 사용자가 응답 방으로 추가한 방은 즉시 학습 대상이 되며, 방 관리에서 수집을 끄면 이후 알림 기록은 계속 중단된다. 이 동작을 `screenTitleSelectionIsExplicitIdempotentAndEnablesCaptureWhenAddedAsReplyTarget` 계측 테스트로 고정했다.

## 검증과 남은 범위

- 정적·JVM·기기 테스트는 변경 후 다시 실행할 예정이다.
- 실제 카카오톡의 새 메시지 수신과 RemoteInput 전송은 상대 계정과 메시지를 보내는 실사용 검증이 필요하다. 이 조사만으로 전송 성공을 주장하지 않는다.
- 알림 접근 권한이 꺼진 경우에는 앱 화면에서 상태를 안내하고, 과거 기록은 카카오톡 내보내기 공유 경로로 가져올 수 있지만 실시간 자동응답은 동작하지 않는다.

