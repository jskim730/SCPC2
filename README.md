# SCPC2

SCPC 2026 AI Challenge 예선 2차용 Android 개인 배달 주문 에이전트 작업 저장소입니다.

대회 공식 페이지 : https://dacon.io/competitions/official/236745/overview/description

## 저장소 구성

- `android/`: 제품 UI, production core, Probe adapter, 합성 catalog와 테스트
- `release_v3/`: 대회 공식 Kit의 읽기 전용 참조 사본
- `work/harness/`: Windows 로컬 연습을 위해 조정한 공동 디버깅 도구
- `work/`: APK와 Probe 실행결과를 생성하는 로컬 작업공간
- 루트의 Mission·전략·UX·검증 문서: 설계와 평가 대응 근거

`work/APP.apk`, `work/PUBLIC_RUN/`, Gradle/Python 캐시, signing key와 실행 token은
재생성 가능한 로컬 산출물이므로 Git에서 제외합니다. 합성 catalog와 재현 가능한 테스트 입력은
저장소에 포함합니다.

## Android 환경

- Android Studio
- JDK 17 이상(Android Studio 번들 JBR 권장)
- Android SDK Platform 35
- Android SDK Build-Tools 35.0.0

```powershell
cd android
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleDebug
```

공개 Runner 연습과 emulator 설정은 `SETUP_AND_REHEARSAL_RUNBOOK.md`, 현재 구현 상태와 남은
작업은 `android/IMPLEMENTATION_STATUS.md`를 참고합니다.

## 협업자가 먼저 읽을 것

1. `android/IMPLEMENTATION_STATUS.md` — 어디까지 됐는지, 무엇이 남았는지, 되돌리면 안 되는 설계 결정
2. `COMPETITION_CONTEXT.md` — 공식 규칙 snapshot. 기술 판단의 근거는 여기서 인용합니다
3. `AGENTS.md` — 작업공간 규칙

레이어 경계를 지킵니다. 판단 로직은 `android/app/src/main/java/com/scpc/deliveryagent/core/`,
한국어·메뉴·금액은 `android/app/src/main/assets/synthetic/catalog.json`에 둡니다. `core/`에 한국어
문자열이나 메뉴 지식을 넣지 않습니다 — 그 경계가 production parity 설명의 근거입니다.

판단 로직을 바꾸는 커밋은 `:app:testDebugUnitTest`(97개, emulator 불필요)를 통과시키고
`android/IMPLEMENTATION_STATUS.md`의 해당 절을 함께 고칩니다.

## 참가 자격 관련 확인 사항

본 대회는 **개인전 1인**이며(`COMPETITION_CONTEXT.md` §3), §19는 대회 기간 중 source와 result를
개인적으로 타 참가자와 공유하지 말고 공개는 Dacon 공식 플랫폼을 쓰라고 규정합니다. 이 저장소를
공유하기 전에 협업자의 역할·참가 여부, 저장소 공개 범위(public/private), §20 제출물 창작·비침해
보증의 주체를 확인해 기록하십시오. 저장소가 public이면 `release_v3/`(공식 Kit)와 공식 용어해설집
PDF까지 재배포됩니다.

## 보안·제출 원칙

- 실제 개인정보·결제·외부 행동을 사용하지 않습니다.
- release keystore, 비밀번호, API key와 실행 token을 커밋하지 않습니다.
- `release_v3/`에는 생성물이나 캐시를 남기지 않습니다.
- 최종 APK를 동결한 뒤에는 다시 build/sign하지 않습니다.
- `MISSION_LOCK.json`과 release identity/digest는 참가자가 수기로 만들지 않습니다.
