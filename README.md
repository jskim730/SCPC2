# SCPC 2026 AI 챌린지 — 개인 배달 주문 에이전트

SCPC 2026 AI 챌린지 예선 2차 과제로 7일 동안 만든 **Android Long-Horizon 에이전트**입니다.
반복되는 배달 주문에서 이전 session의 취향 중 **지금 유효한 것만** 골라 재사용하고, 조건이 바뀌거나
앱이 강제 종료되면 **영향받은 주문 항목만** 다시 판단합니다.

이 저장소는 예선 2차 제출물과 본선 발표 자료를 **제출 당시 그대로 보존**한 기록입니다.

| | |
|---|---|
| 대회 | [2026 Samsung Collegiate Programming Challenge : AI 챌린지](https://dacon.io/competitions/official/236745/overview/description) 예선 2차 → 본선 |
| 참가 형태 | 개인전 1인 (`candidate_025` / `mission_025`) |
| 개발 기간 | 2026-07-29 10:00 – 2026-08-05 10:00 KST (7일) |
| Mission 동결 | 2026-07-31 02:55 KST (`MISSION_LOCK.json`, Dacon 발급·수정 불가) |
| 본선 발표 | 2026-08-21, 삼성전자 우면 R&D캠퍼스 |
| 구현 규모 | Kotlin 24파일 11,680줄 · JVM 테스트 197개 · 기기 테스트 10개 |

## 무엇을 만들었나

**대상** — 배달앱의 식당·메뉴·옵션 구조에 익숙하지 않거나 선택 과정에 부담을 느끼면서도 배달 주문을
반복하는 사용자.

**문제** — 기존 재주문 기능은 이전 주문을 통째로 불러온다. 그래서 **평소 취향**(항상 순한맛)과
**그때만의 선택**(오늘은 예산 2만원)을 구분하지 못하고, 조건이 달라지면 사용자가 처음부터 다시 조정해야 한다.

**동결한 인과 사슬 (E1–E4)** — Mission 선언에서 고정한 네 가지 장기 행동이 그대로 구현 범위입니다.

| | 행동 | 구현이 보장하는 것 |
|---|---|---|
| **E1** | 학습 | 전역 기본 취향 / 메뉴 유형별 취향 / 특정 식당·메뉴 예외를 **이번 주문만의 조건과 구분해** 저장 |
| **E2** | 재사용 | 현재 지시 → 식당·메뉴 예외 → 메뉴 유형 → 전역 순으로, **자동 적용이 허용된 값만** 초안에 채움 |
| **E3** | 예외 | 일회성 지시가 저장된 취향과 충돌하면 **해당 항목만** 다시 판단하고 나머지 허용 범위는 보존 |
| **E4** | 복구 | 품절·process 재시작 뒤에도 **영향받은 항목만** 재선택. 미실행 주문을 완료로 표시하거나 중복 실행하지 않음 |

전문은 [`MISSION_LOCK.json`](MISSION_LOCK.json)의 `locked_content`에 있습니다.

## 설계에서 가장 중요했던 세 가지

**1. 판단과 언어를 레이어로 분리했다.**
`core/`는 한국어 문자열도 메뉴 지식도 갖지 않고 **불투명 token 관계로만** 판단합니다. 식당·메뉴·금액·조사는
전부 `delivery/`와 `assets/synthetic/catalog.json`에 있습니다. 이 경계가 "이 에이전트는 배달 도메인에
하드코딩된 게 아니다"라는 주장의 **증명 가능한 근거**입니다 — 변형 Probe에서 role token을 전부 무작위
ID로 치환해도 13단계 판단이 동일했습니다.

**2. 모델을 넣지 않고 결정적 파서를 택했다.**
7/31에 언어모델 탑재를 검토한 뒤 기각했습니다. 60초 실행 제한, 호출 quota, secret·backend 동결 위험을
7일 안에 통제할 수 없다고 판단했습니다. 대신 규칙 기반 파서를 쓰고 `PreferenceIntake`를 **교체 지점으로
남겨** 두었습니다. 그 결과 **모델 호출 0회**로 전 구간이 재현 가능합니다.

**3. 증거를 UI가 아니라 파일로 남겼다.**
매 step의 결정 상태·행동·결과를 `evidence/`에 JSON으로 기록하고, 공식 finalizer가 이것을 `SAMPLE_EXPORT/`로
봉인합니다. 채점자가 화면 녹화를 신뢰할 필요 없이 파일만 보면 됩니다.

자세한 설계 결정과 그 이유는 [`android/IMPLEMENTATION_STATUS.md`](android/IMPLEMENTATION_STATUS.md),
7일간의 판단 기록은 [`RETROSPECTIVE.md`](RETROSPECTIVE.md)에 있습니다.

## 검증

| 방법 | 결과 |
|---|---|
| JVM 단위 테스트 197개 | debug·release 모두 통과, **emulator 불필요** |
| 기기 instrumentation 10개 | API 35 x86_64 emulator 통과 |
| 공식 Runner 13-step | 13/13 완주, ID 참조·실파일·내부 ID **35/35**, epoch 0→1, 모델 호출 0회 |
| 변형 Probe V1–V4 | role token 치환·entity 교환·순서 변형·26-step 장기 반복 — 넷 다 공식 Runner 완주 |

변형 Probe는 "공개 13-step에 맞춘 하드코딩이 아님"을 보이려고 직접 만든 metamorphic 테스트입니다.
설계는 [`docs/verification/PROBE_METAMORPHIC_TEST_PLAN.md`](docs/verification/PROBE_METAMORPHIC_TEST_PLAN.md).

## 저장소 구조

```
android/                    제품 소스 — core(판단) / delivery(언어·메뉴) / probe / ui / platform
  IMPLEMENTATION_STATUS.md  구현 현황, 설계 결정과 되돌리면 안 되는 이유
docs/
  competition/              공식 규칙 snapshot
  mission/                  제출한 Mission 선언(PDF·DOCX)
  design/                   UX 명세, 평가 대응 전략, 사전조사, 어댑터 설계 초안
  verification/             변형 Probe 계획, 기기 검증 대본, 연습 runbook, self-score
output/
  submission/               제출물 7종 — 동결된 그대로 (APK · SOURCE.zip · SAMPLE_EXPORT · PDF · 영상)
  pdf/                      제출 PDF 2종의 빌드 산출물
finals_presentation/        본선 발표 자료 — 제출 deck, 대본, 예상 질의응답
tools/                      SOURCE.zip·PDF 빌드, 변형 Probe 생성, Windows 실행 shim
test-fixtures/probe/        변형 Probe 입력 V1–V4
work/harness/               공식 harness의 Windows 대응본
MISSION_LOCK.json           Dacon 발급 Mission 동결 정본 (수정 금지)
```

대회 공식 Kit(`release_v3/`)은 **포함하지 않습니다.** Dacon이 배포·검증하는 자료이고 재배포를 허용하는
조항이 없습니다. 무엇이 들어 있었고 어떤 스크립트가 그것을 필요로 하는지는
[`docs/competition/OFFICIAL_KIT.md`](docs/competition/OFFICIAL_KIT.md)에 기록했습니다.
JVM 테스트 197개는 Kit 없이 통과합니다.

**루트에 남긴 4개 문서**(`BUILD_AND_SUBMISSION_INFO.md`, `THIRD_PARTY_NOTICES.md`,
`MISSION_AND_TECHNICAL_NOTE.md`, `INSTALL_AND_USE_GUIDE.md`)는 `tools/`의 빌드 스크립트가 루트 경로로
참조합니다. 앞의 두 개는 `SOURCE.zip` 구성 allowlist에, 뒤의 두 개는 제출 PDF 2종의 원본입니다.
옮기면 제출물이 재현되지 않으므로 그대로 둡니다.

## 빌드

```powershell
cd android
.\gradlew.bat :app:testDebugUnitTest
```

```powershell
cd android
.\gradlew.bat :app:assembleDebug
```

JDK 17 이상(Android Studio 번들 JBR 권장), Android SDK Platform 35, Build-Tools 35.0.0이 필요합니다.
Gradle 8.9 / AGP 8.7.3 / Kotlin 2.0.21, `minSdk 28` · `targetSdk 35`.

## 제출물 동결

`output/submission/`은 2026-08-05 마감에 제출한 **바이트 그대로**입니다. `SAMPLE_EXPORT/EXPORT_INDEX.json`이
APK와 `SOURCE.zip`의 SHA-256을 고정하고 있어, 안의 문서를 고치면 결속이 깨집니다. 그래서 이 저장소의
정리 과정에서도 제출물은 **한 바이트도 건드리지 않았습니다.**

현재 트리에서 `tools/build_source_zip.py`로 zip을 다시 만들면 해시가 다릅니다. 확인해 보니 **내용이 다른
파일은 0개**이고, 차이는 전부 개행문자입니다 — 자세한 내용은 [`RETROSPECTIVE.md`](RETROSPECTIVE.md)의
"재현성에서 배운 것"에 적었습니다.

## 데이터와 안전 경계

- 실제 계정·개인정보·결제·외부 서비스 호출이 **없습니다.** 식당과 메뉴는 전부 합성 catalog입니다.
- 원격 서비스 SDK, 분석·광고 SDK, 생성모델 runtime을 **포함하지 않습니다** ([`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md)).
- signing key·keystore·password·실행 token은 저장소에 없습니다.
