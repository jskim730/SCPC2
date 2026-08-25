# SCPC 2026 AI 챌린지 예선 2차 조사·전략 문서

조사 기준시각: 2026-07-29 10:35 KST  
현재 상태: 공식 페이지·공식 Kit·용어집 조사 완료, 제품 구현은 아직 시작하지 않음

## 1. 확인한 정본

### Dacon 공식 페이지

- [대회 개요](https://dacon.io/competitions/official/236745/overview/description)
- [평가](https://dacon.io/competitions/official/236745/overview/evaluation)
- [규칙](https://dacon.io/competitions/official/236745/overview/rules)
- [일정](https://dacon.io/competitions/official/236745/overview/schedule)
- [상금](https://dacon.io/competitions/official/236745/overview/prize)
- [동의사항](https://dacon.io/competitions/official/236745/overview/agreement)
- [데이터](https://dacon.io/competitions/official/236745/data)
- [코드 공유](https://dacon.io/competitions/official/236745/codeshare)
- [토크](https://dacon.io/competitions/official/236745/talkboard)
- [리더보드](https://dacon.io/competitions/official/236745/leaderboard)

### 로컬 공식 자료

- `release_v3/`: `SCPC2026-R2-CANDIDATE-RELEASE-V3`,
  `FROZEN_PARTICIPANT_RELEASE`
- `2026 SCPC 용어해설집.pdf`: 5쪽 공식 용어집

정본 우선순위는 다음처럼 사용한다.

1. 일정·제출 경로·운영 공지는 Dacon 대회 페이지와 토크의 최신 공지
2. Android·Probe·JSON·제출 구조는 `release_v3` 문서와 schema
3. 용어의 쉬운 해석은 공식 용어집
4. `sample_app`은 AAR 연결 참고자료일 뿐 정답이나 점수 보장 구현이 아님

## 2. 과제의 본질

한 번의 대화로 끝나는 챗봇이 아니라, 여러 session과 앱 재실행을 지나도 앞선 선택이 뒤의 가능한 행동을
실제로 바꾸는 설치 가능한 Android Mobile Agent 제품을 만든다.

필수 사용자 여정은 다음과 같다.

| 단계 | 요구 결과 |
|---|---|
| E1 Learn | 목표·정보·허용범위를 배우고 stable/one-off/추론 정보를 구분 |
| E2 Reuse | 다음 유사 작업에서 유효한 경험만 재사용해 입력·질문·클릭 부담 감소 |
| E3 Exception | 현재 지시·상황·권한이 과거 routine과 충돌하면 멈추고 ASK/WAIT/ABSTAIN/replan |
| E4 Recover | 전체 초기화 없이 영향받은 부분만 복구하고 유효한 완료 부분은 보존 |

구조적 최소조건은 다음 여섯 가지다.

1. 구분 가능한 episode 4개 이상
2. session 경계 3회 이상
3. 앱 또는 process 종료·재실행 1회 이상
4. 앞 episode의 선택이 뒤 episode의 가능한 행동을 서로 다른 두 지점 이상에서 변경
5. E3에서 과거 방식과 현재 조건이 충돌해 진행 여부를 다시 판단
6. E4에서 전체 Reset이 아닌 부분복구

실제 며칠을 기다리거나 실제 계정·개인정보·결제·예약·메시지·전화·게시를 사용하지 않는다. 합성 event와
virtual time, app-local draft/proposal/preview/simulation으로 전체 흐름을 30-40분 안에 재현해야 한다.

## 3. 일정과 내부 마감

공식 상세 일정 기준:

| 항목 | 공식 마감 |
|---|---|
| 예선 2차 | 2026-07-29 10:00 - 2026-08-05 10:00 KST |
| Mission 선언 | 2026-07-31 10:00 KST |
| 최종 제출물 7종 | 2026-08-05 10:00 KST |
| 검증·평가 | 2026-08-05 - 2026-08-13 |
| 본선 진출 발표 | 2026-08-14 |
| 오프라인 본선 | 2026-08-21 |
| 시상식 | 2026-08-28 |

조사 시각 기준 Mission 마감까지 약 47시간 25분이 남아 있다. 운영 리스크를 줄이기 위한 내부 마감은 다음과
같이 잡는다.

- Mission 내용 확정: 2026-07-30 18:00
- Mission PDF 완성: 2026-07-30 20:00
- Mission 비공개 제출: 2026-07-30 22:00
- 기능·schema code freeze: 2026-08-04 10:00
- 최종 APK와 public 13-step freeze: 2026-08-04 14:00
- 문서·영상·SAMPLE_EXPORT 완성: 2026-08-04 22:00
- Google Drive 최종 업로드: 2026-08-05 06:00

## 4. Mission 선언에서 동결되는 것

현재 공식 웹 snapshot 기준으로 1쪽 PDF를 코드공유 게시판의 비공개 글로 제출하며 권장 파일명은
`SCPC2026_R2_MISSION_데이콘닉네임.pdf`다. 로그인한 계정의 실제 제출 UI에서 위치·비공개 설정·파일명을
내부 마감 전에 다시 확인하고 화면 근거를 남긴다.

반드시 포함할 내용:

- 대상 사용자
- 핵심 반복 문제
- 여러 session 뒤 확인 가능한 장기 목표
- Primary value 한 가지
- E1-E4 인과관계
- 모바일이어야 하는 이유와 mobile constraint
- Signature mechanism의 문제·효과 claim

마감 뒤 동결되는 내용:

- 대상 사용자
- 핵심 문제
- 장기 목표
- Primary value
- E1-E4 인과관계

화면·기술구조·model·algorithm은 개선할 수 있지만 다른 Mission으로 바꿀 수 없다. Dacon이 PDF를 기준으로
`MISSION_LOCK.json`을 생성하므로 참가자가 직접 만들거나 수정하지 않는다.

## 5. 기술·안전 비협상 조건

### Android release

- Android 15/API 35
- target SDK 35
- arm64-v8a 지원
- 참가자 release key로 서명한 단일 `APP.apk`
- APK와 정확히 일치하는 `SOURCE.zip`
- public rehearsal에 사용한 최종 APK를 다시 build/sign하지 않고 같은 파일 제출
- private key·keystore·password는 제출하지 않음
- 선택 permission을 거부해도 Primary value의 합성 E1-E4 경로는 동작

### 모델·backend

- 모델 사용은 선택사항이며 deterministic 구현도 공식 허용
- 판단당 model/backend inference 최대 4회
- official run 전체 최대 60회
- timeout/network 자동 retry는 판단당 총 1회
- 실패·fallback·취소·retry도 호출수에 포함
- 판단 요청 후 60초 안에 ACT/ASK/WAIT/ABSTAIN/FAILED/CONFIRMED_COMPLETE 등 정직한 상태 표시
- backend를 쓰면 source, endpoint, 전송 데이터 범위, release/routing/config와 재현방법을 신고하고 동결
- Judge 개인계정·API key·유료구독 요구 금지
- token/private key/credential 평문 포함 금지

### 상태·안전

- 현재 사용자의 정정·철회·삭제가 오래된 추론·routine보다 우선
- 삭제 원문은 export와 relaunch 뒤 부활하거나 판단에 재사용되면 안 됨
- tombstone에는 원문을 복원할 수 없는 최소 metadata만 보존
- process kill 전 미완료 action을 완료로 표시하지 않음
- duplicate/out-of-order event가 action이나 완료·취소 상태를 되살리지 않음
- network/permission 실패를 성공으로 위장하지 않음
- 사용자가 기억·상태를 확인·수정·삭제하고 전체 Reset할 수 있어야 함

## 6. 평가 구조

### 6.1 Gate 우선

G0-G7을 모두 통과해야 FINAL_Q, 심층 기술검증, 수상 대상이 될 수 있다. 높은 Q로 gate 실패를 보상할 수
없다.

| Gate | 의미 |
|---|---|
| G0 | APK·SOURCE·설정 동결, 일치, 재현성 |
| G1 | 합성 데이터, Mission 범위, 개인정보 규칙 |
| G2 | 실제 외부행동·금지권한 없는 합성 실행 |
| G3 | 제안·대기·중단·실패·확인된 완료의 정직한 구분 |
| G4 | 현재 정정·철회·삭제 우선과 lifecycle 뒤 상태 유지 |
| G5 | 안전하게 멈추면서도 E1-E4 기본 가치 제공 |
| G6 | 화면·state·action·export·receipt·호출기록 일치 |
| G7 | 모바일 필연성, restart/local continuity, 별도 mobile constraint |

### 6.2 Q/80

공식 Q profile:

| Q | 의미 | 최대 |
|---|---|---:|
| Q1 | 장기 상태·계획 연속성 | 24 |
| Q2 | 적응적 안전 자율성 | 14 |
| Q3 | 선택적 맥락·기억 가치 | 12 |
| Q4 | 현재성·lifecycle·사용자 통제 | 14 |
| Q5 | 범위 경계·유효한 상태전이 | 8 |
| Q6 | 행동·자원·증거 무결성 | 8 |

공개 lookup을 CORE 기준으로 다시 합치면 최대 기여도는 다음과 같다.

| CORE | 핵심 | Q 최대 기여 |
|---|---|---:|
| CORE-4 | 예외 판단과 부분복구 | 21 |
| CORE-3 | 반복부담 감소와 안전확인 | 19 |
| CORE-6 | 지연결과·ledger·evidence | 14 |
| CORE-5 | restart·duplicate·out-of-order·exactly-once | 10 |
| CORE-1 | 선택적 맥락과 distractor 배제 | 9 |
| CORE-2 | 현재 권위·정정·철회·삭제·descendant invalidation | 7 |

모든 CORE anchor 3이면 Q=60/80이다. Anchor 4는 unseen surface와 late outcome에서도 anchor 3의 성질이
유지되고 failure boundary가 분명해야 한다. 전략은 CORE-4, CORE-3, CORE-6을 우선하되 모든 gate와 CORE를
끝까지 닫는 것이다.

### 6.3 CII/20

모든 참가자는 같은 APK에 `full`과 `claim-off` comparison을 구현한다. AUTO-CHECK에서는 실행하지 않고,
FINAL_Q와 동결 pool 규칙으로 선택된 deep 후보에게만 실행한다.

| 항목 | 최대 | 확인 내용 |
|---|---:|---|
| C1 | 2 | 사전 등록한 문제·효과 claim의 명료성 |
| C2 | 3 | mechanism 적합성과 불필요한 복잡성 부재 |
| C3 | 8 | 같은 조건의 paired claim-off 대비 인과 이득 재현 |
| C4 | 4 | late horizon·unseen surface·privacy/resource trade-off에서도 유지 |
| C5 | 3 | source·state·receipt 기반 실패경계·기술소유권 설명 |

C3가 4점 미만이면 CII 총점은 최대 9점이다. CII 14점 이상은 late-horizon과 unseen surface 이득이 모두
재현되어야 한다.

최종 순위 대상은 verified FINAL_Q와 verified FINAL_CII를 모두 가진 pool 후보이며,
`final_total = Q/80 + CII/20`이다. 동점은 final_total, CII, Q, C3 순으로 비교한다.

## 7. Probe Mode 계약

Probe Mode는 선택 기능이 아니라 최종 평가의 필수 표준 입구다. public UI와 protected component가 같은
candidate adapter와 실제 production core를 호출해야 한다.

public 점검 화면에 다음 content description을 정확히 제공한다.

- `SCPC_PROBE_IMPORT`
- `SCPC_PROBE_RUN`
- `SCPC_PROBE_EXPORT`

필수 지원 operation 13종:

- `RESET_AND_START`
- `UPSERT_FACT`
- `ADVANCE_SESSION`
- `REQUEST_DECISION`
- `CORRECT_FACT`
- `REVOKE_SCOPE`
- `DELETE_FACT`
- `SET_NETWORK`
- `PROCESS_KILL_RELAUNCH`
- `REPLAY_EVENT`
- `DELIVER_OUT_OF_ORDER`
- `ADVANCE_TIME`
- `EXPORT_AND_END`

이는 고정된 13단계 서사가 아니다. 한 input은 1–80 step이며 같은 operation이 반복되거나 공개
13-step과 다른 유효한 순서로 들어올 수 있다. result에는 operation별 한 번이 아니라 **각 input step이
같은 순서·ID·operation으로 정확히 한 번** 있어야 한다.

필수 semantic role:

- `PRIMARY_GOAL`
- `TARGET_ENTITY`
- `DISTRACTOR_ENTITY`
- `STABLE_VALUE`
- `ONE_OFF_VALUE`
- `CURRENT_AUTHORITY`
- `REVOKED_SCOPE`
- `PRESERVED_SCOPE`
- `DELAYED_OUTCOME`
- `EPHEMERAL_VALUE`

앱이 내보내는 것은 관찰 가능한 state·action·receipt·evidence뿐이다. PASS/FAIL, expected relation, anchor,
Q, cut을 계산하거나 출력하지 않는다. 공개 pack ID나 문자열을 보고 정답 분기를 만들지 않는다.

## 8. 제출물 7종

1. `APP.apk`
2. `SOURCE.zip`
3. `MISSION_AND_TECHNICAL_NOTE.pdf`
4. `INSTALL_AND_USE_GUIDE.pdf`
5. `BUILD_AND_SUBMISSION_INFO.md`
6. `SAMPLE_EXPORT/`
7. `DEMO_VIDEO.mp4` - 3분 이내

`SAMPLE_EXPORT/`의 기본 구조:

```text
SAMPLE_EXPORT/
├── MISSION_LOCK.json
├── RUNTIME_IDENTITY.json
├── EVIDENCE_INDEX.json
├── EXPORT_INDEX.json
├── PUBLIC_PROBE_RESULT.json
└── evidence/...
```

압축 전 합계는 20 MiB 이하다. 참가자는 실제 기능을 설명하는 `MISSION_ADAPTER.json`과 evidence 파일을
준비한다. `FINALIZE_SAMPLE_EXPORT.py`가 APK·SOURCE·Runner 결과에서 SHA-256, release identity,
runtime identity와 index를 자동 생성한다. 이 값들을 수기로 작성하거나 예시에서 복사하지 않는다.

## 9. `release_v3` 점검 결과

- 문서 9종, schema·template·lookup, finalizer/validator/self-score 도구 확인
- 서명된 공식 Runner APK와 starter AAR 확인
- public 13-step input, public harness, sample app 확인
- JSON 파일 17개 모두 문법 파싱 성공
- release root의 starter AAR와 sample app에 포함된 AAR가 byte-for-byte 동일
- `3.0.0-draft`는 이 동결 Kit의 contract 호환 식별자이며 미완성 제출물을 뜻하지 않음
- sample app은 AAR/adapter/persistence 연결만 보여 주며 public control UI와 Mission별 evidence export가
  없는 debug-only 참고 구현
- `SELF_SCORE.py` 예시는 정상 실행되며 공개 예시 anchor 3 기준 Q=60/80

Kit 도구 사용에 필요한 개발 환경:

- Python 3.10+
- `jsonschema>=4.18,<5` 전용 venv
- Android SDK Platform 35
- platform-tools의 `adb`
- command-line tools latest의 `apkanalyzer`
- build-tools 35.0.0의 `apksigner`
- JDK 17

현재 로컬 환경에는 Android SDK/adb/apkanalyzer/apksigner/Android Studio가 없고 JDK 21만 있다. JDK 21이
즉시 빌드를 막는다고 확인된 것은 아니지만 공식 build·sample 재현환경은 JDK 17이므로 구현 전 JDK 17
빌드도 검증해야 한다. 또한 현재 폴더는 Git repository가 아니다. 구현 전 환경 구축과 repository
초기화가 필요하다.

## 10. 권장 제품 방향 - 아직 Mission으로 동결하지 않은 작업가설

> **2026-07-29 전략 업데이트:** 아래의 캠퍼스 외출 준비 에이전트는 초기 비교용 작업가설로 보존한다.
> 현재 우선 검토 중인 작업가설은 `PERSONAL_DELIVERY_AGENT_UX_SPEC.md`의 개인 배달 주문 에이전트다.
> 새 방향도 아직 Mission으로 제출·동결되지 않았으며, E1–E4·Signature·comparison·Probe mapping 승인 전에는
> 구현하지 않는다.

### 작업가설: 반복되는 캠퍼스 외출 준비 에이전트

- 대상 사용자: 수업·동아리·개인 일정으로 반복 외출 준비를 하면서 조건 변경과 앱 중단을 자주 겪는 대학생
- 핵심 문제: stable preference와 one-off constraint가 섞여 매번 다시 입력하거나, 오래된 조건 때문에
  잘못된 준비계획을 유지하고, 변경 시 전체 계획을 버리는 문제
- 장기 목표: 여러 session 뒤에도 현재 목표·대상에 필요한 정보만 재사용해 준비 부담을 줄이고, 정정·철회·
  permission/network/lifecycle 변화에는 안전하게 멈춰 영향받은 부분만 복구
- Primary value 후보: "오래된 상태 위험 없이 반복 준비 부담을 줄이는 것"
- mobile constraint 기준: permission 허용·거부와 무관한 app-local outcome 연속성. notification은 관찰용
  부수 surface이며, `SET_NETWORK`의 합성 delayed/offline degradation은 대안이 아니라 별도 필수 축

E1-E4 예시:

- E1: 합성 일정 A의 stable preference, one-off constraint, 허용범위를 학습
- E2: 새 session/표면에서 일정 A를 다시 준비하고 무관한 일정 B distractor를 배제하며 질문·클릭 감소
- E3: 현재 사용자가 조건을 정정하고 한 scope를 철회하거나 삭제한 뒤, 과거 routine을 멈추고 ASK/WAIT/replan
- E4: 이미 완료·유효한 준비는 보존하고 영향받은 plan node만 다시 계산; process relaunch 뒤 이어서 완료

### Signature mechanism 후보

`Authority-Scoped Causal Memory Graph`

- fact에 goal/entity/scope/lifetime/authority/version을 부여
- plan node가 사용한 fact dependency를 기록
- correction/revoke/delete/tombstone이 영향받는 descendant만 무효화
- 현재 목표에 맞는 유효 fact만 선택
- completed/preserved node는 유지하고 invalidated node만 재계획
- event/action/outcome/receipt를 append-only ledger에 결박

`full`은 이 mechanism을 사용한다. `claim-off`는 같은 code·UI·입력·시작 snapshot을 사용하되 이 mechanism만
끄고 단순 last-write-wins 기억과 전체 재계획 baseline을 사용한다. Baseline을 의도적으로 망가뜨리지 않고도
다음 차이를 측정할 수 있다.

- 재입력·질문·클릭 수
- distractor 선택 수
- 변경 뒤 다시 계산한 plan node 수
- 보존한 완료 node 수
- stale/삭제값 재사용 수
- duplicate action 수
- recovery 완료시간과 receipt 일관성

이 작업가설은 외부 model/backend 없이 deterministic core로 구현하는 것을 기본으로 한다. 이는 규정상
허용되고 60초·호출 quota·secret·backend freeze 위험을 없앤다. AI 모델은 사용자 가치와 anchor에 명확한
추가 이득이 입증될 때만 도입한다.

## 11. 권장 아키텍처

```text
제품 UI / public Probe UI / protected Probe component
                        ↓
                  동일 Command Bus
                        ↓
            Production Command Processor
      ┌───────────────┼────────────────┐
      ↓               ↓                ↓
 Causal Memory    Decision/Plan     Action Ledger
      └───────────────┼────────────────┘
                      ↓
          Transactional State Repository
                      ↓
      Receipt + Canonical Evidence Export
```

구현 원칙:

- Kotlin Android native
- transaction을 지원하는 durable local database
- 모든 event에 unique `event_id`, authority version, virtual time
- 모든 action에 stable idempotency key와 PROPOSED/COMMITTED/CANCELLED/FAILED 경계
- tombstone은 원문 없는 최소 metadata
- process마다 epoch 증가, startup 시 pending action reconciliation
- 동일 command handler를 제품 UI와 Probe adapter가 사용
- state digest는 deterministic canonical JSON
- evidence는 실제 persisted state와 ledger에서 생성
- comparison은 `full`/`claim-off`별 DB, memory, cache, 설정 namespace 완전 분리
- deterministic release에서는 `modelConfigured=false`, invocation count 0
- release 후보가 정해지면 APK 재build·resign 금지

## 12. 테스트 전략

### Gate tests

- APK/SOURCE/MISSION_ADAPTER byte 일치
- 합성 data만 사용, 실제 외부 action 없음
- ACT/ASK/WAIT/ABSTAIN/PENDING/FAILED/CONFIRMED_COMPLETE 구분
- 최신 correction/revoke/delete 우선
- user memory view/edit/delete/reset
- 화면·DB·ledger·receipt·export 일치
- restart/local continuity와 별도 mobile constraint

### Metamorphic/hidden 대비 tests

- role 값·entity·surface string 전체 치환
- step 표현과 값 변경
- distractor 최신성·유사성 변경
- correction/revoke/delete 순서 변형
- duplicate와 out-of-order 조합
- process kill 위치를 proposal 전/후로 이동
- offline/delayed/online 전이
- delayed outcome 도착시각 변경
- export→delete→relaunch no-resurrection

### Comparison tests

- 같은 snapshot byte에서 두 namespace 생성
- full 변경·삭제·Reset이 claim-off에 보이지 않음
- 동일 input/model/network/quota
- 서로 다른 result/receipt/evidence artifact
- 사전 등록 metric에서 인과 차이 재현

### Probe tests

- 1–80개의 각 input step이 같은 순서·step ID·event ID·operation으로 정확히 한 번 result에 존재
- operation 반복·유효한 순서변형·role 값 전면치환에도 같은 production 의미관계 유지
- public UI 세 accessibility control
- public/protected 경로가 같은 adapter와 production core 호출
- AAR가 official caller 외 호출을 fail-closed
- result에 PASS/FAIL/anchor/Q/cut 없음
- public pack 문자열 하드코딩 없음

## 13. 구현 전 의사결정 Gate

아래가 모두 확정되기 전에는 앱 코드를 시작하지 않는다.

1. 로그인 계정에서 Mission 제출 위치·비공개 설정·개인 Drive 위치 확인
2. Android·Kotlin/Java 전용 계약과 일반 boilerplate의 우선순위 공식 확인
3. Mission 작업가설 승인 또는 대체 아이디어 선정
4. 대상 사용자·핵심 문제·장기 목표·Primary value 한 문장씩 확정
5. E1-E4 인과관계와 downstream causal change 두 지점 확정
6. mobile constraint와 mobile counterfactual 확정
7. Signature mechanism과 claim-off baseline·comparison metric 확정
8. 13종 operation과 10개 semantic role의 schema-complete Mission mapping 초안 확정
9. state·event·scope·dependency·action·outcome·tombstone·receipt schema 초안 확정
10. JDK 17, Android SDK 35, adb, apkanalyzer, apksigner, Python venv 준비

1–2는 운영 확인, 3–9는 Mission·설계, 10은 환경 구축으로 병렬 진행할 수 있다. 환경 구축 완료를 기다리며
Mission 설계를 멈추지는 않는다.

## 14. 다음 작업

다음 단계는 60-90분 Mission 설계 세션이다.

1. 사용자가 이미 가진 아이디어와 권장 작업가설을 같은 scorecard로 비교
2. 한 Mission을 선택
3. 1쪽 Mission 선언 초안 작성
4. E1-E4 state transition과 순서·횟수에 독립적인 13종 operation mapping 작성
5. Signature comparison metric 확정
6. 사용자 승인 뒤에만 Android 환경 구축과 구현 시작
