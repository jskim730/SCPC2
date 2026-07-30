# SCPC 2026 AI 챌린지 예선 2차 - 공식 대회 컨텍스트

이 문서는 대회 페이지를 매번 다시 열지 않고도 계획·구현·검증에 필요한 공식 사실을 참조하기 위한
workspace 기준 문서다.

- 대회: `[2차 예선] 2026 Samsung Collegiate Programming Challenge : AI 챌린지`
- 운영 플랫폼: Dacon
- 대회 ID: `236745`
- 시간대: `Asia/Seoul` (`KST`, UTC+9)
- 공식 웹·Kit·용어집 최종 확인: `2026-07-29 10:35 KST`
- 로컬 Kit: `release_v3/`
- Kit release ID: `SCPC2026-R2-CANDIDATE-RELEASE-V3`
- Kit 상태: `FROZEN_PARTICIPANT_RELEASE`
- 구현 상태: 아직 제품 구현을 시작하지 않음

> 이 문서는 확인 시점의 공식 정보를 정리한 snapshot이다. Dacon의 새 공지·토크 답변·Kit 교체가 있으면
> 그 내용이 우선하며, 이 문서의 `변경 기록`과 해당 절을 함께 갱신한다.

## 0. 마감 전 최우선 운영 확인

다음은 제품 점수와 무관하게 제출 자체를 막을 수 있으므로 로그인한 Dacon 계정에서 직접 확인하고
확인시각·화면을 기록한다.

1. Mission 제출 위치가 코드공유 게시판인지, 비공개 설정과 파일명이 현재도 유효한지 확인
2. 참가자별 최종 Google Drive 링크 위치와 쓰기·읽기 권한 확인
3. Kit의 기본 수상 slot 3명과 Dacon 상금표 13명 표기가 각각 어느 평가·시상 범위인지 확인
4. 일반 R/Python boilerplate보다 본 대회 Android·Kotlin/Java 전용 계약이 우선함을 공식 답변으로 기록

Mission 제출 위치와 최종 Drive는 마감 직전에 처음 확인하지 않는다. 현재 문서의 경로 설명은 마지막
확인시각의 snapshot이며 실제 제출 UI가 최종 정본이다.

## 1. 정본과 우선순위

### 공식 웹

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

- `release_v3/README_RELEASE.md`
- `release_v3/candidate_kit/README_FIRST.md`
- `release_v3/candidate_kit/01_CHALLENGE_OVERVIEW.md`
- `release_v3/candidate_kit/02_DEFINE_YOUR_MISSION.md`
- `release_v3/candidate_kit/03_TECHNICAL_AND_SAFETY_RULES.md`
- `release_v3/candidate_kit/04_SUBMISSION_GUIDE.md`
- `release_v3/candidate_kit/05_EVALUATION_PROCESS.md`
- `release_v3/candidate_kit/06_FAQ.md`
- `release_v3/candidate_kit/07_PUBLIC_REHEARSAL_AND_SELF_SCORE.md`
- `release_v3/candidate_kit/08_PROBE_MODE_CONTRACT.md`
- `2026 SCPC 용어해설집.pdf`

### 충돌 시 적용 원칙

1. 일정·마감·업로드 위치·운영 공지는 Dacon 웹과 최신 토크 답변을 따른다.
2. Android·Probe·schema·제출파일 기술 계약은 `release_v3`의 문서와 schema를 따른다.
3. 본 대회 전용 규칙이 일반 동의사항의 boilerplate보다 구체적인 경우 본 대회 전용 규칙을 우선 해석한다.
4. 법적·제출상 중요한 충돌은 임의 해석으로 닫지 않고 토크 또는 `dacon@dacon.io`로 확인한다.
5. `sample_app`은 연결 참고자료일 뿐 정답·최소점수·완제품 기준이 아니다.

## 2. 공식 일정

모든 시각은 KST다.

| 항목 | 공식 일시 |
|---|---|
| 참가 신청 | 2026-06-11 - 2026-07-05 |
| 1차 예선 | 2026-07-06 10:00 - 2026-07-12 23:59 |
| 2차 예선 진출자 발표 | 2026-07-27 |
| 예선 2차 | 2026-07-29 10:00 - 2026-08-05 10:00 |
| Mission 선언 제출 | 2026-07-31 10:00 |
| 최종 제출 마감 | 2026-08-05 10:00 |
| 본선 진출 후보 검증·평가 | 2026-08-05 - 2026-08-13 |
| 본선 진출자 발표 | 2026-08-14 |
| 오프라인 본선 | 2026-08-21 |
| 오프라인 시상식 | 2026-08-28 |

세부 일정은 운영 상황에 따라 변경될 수 있다. 대회 헤더의 기간 표기보다 상세 일정 탭의 예선 2차 시각을
기준으로 사용한다.

## 3. 참가·대회 방식

- 개인전 1인
- 대학 또는 대학원 재학생·휴학생
- 전공·학년 제한 없음
- 졸업유예생 참가 불가
- SCPC Algorithm 챌린지와 중복 참가 불가
- 1차 예선 점수는 예선 2차 점수에 합산되지 않음
- 예선 2차는 개발 중 APK를 반복 제출해 official 점수를 받는 리더보드 방식이 아님
- 최종 제출 뒤 CODE/SEC와 AUTO-CHECK를 진행하고, 동결 조건으로 선택된 후보만 사람 검증·심층검증
- 본선에서는 예선 2차 산출물에 대한 발표와 질의응답 진행

주최·운영:

- 주최: 삼성전자
- 주관: 삼성리서치
- 운영: Dacon

## 4. 과제 한 문장

여러 session에 걸쳐 반복되는 모바일 생활 작업에서 과거 trajectory를 선택적으로 기억해 다음 수행의
사용자 부담을 줄이고, 목표·상황·권한이 바뀌면 멈추거나 재계획하며, 앱 종료 뒤에도 안전하게 이어지는
설치 가능한 Android Mobile Agent를 구현한다.

다음은 과제 요구를 충족하지 않는다.

- 한 번의 질의응답으로 끝나는 chatbot
- 고정된 성공 화면만 재생하는 demo
- 여러 domain의 얕은 기능 목록
- 실제 계정·개인정보·외부서비스·며칠의 실제 대기에 의존하는 제품
- 모든 과거를 항상 사용하거나 변화 때 모든 상태를 Reset하는 설계

## 5. E1-E4와 최소 사용자 여정

| 단계 | 공식 의미 |
|---|---|
| E1 Learn | 목표·정보·허용범위를 배우고 stable·one-off·추론 정보를 구분 |
| E2 Reuse | 다음 유사 작업에서 유효한 경험만 활용해 설명·질문·클릭·준비 부담 감소 |
| E3 Exception | 현재 지시·상황·권한과 과거 routine이 충돌하면 ASK·WAIT·ABSTAIN·replan |
| E4 Recover | 영향받은 부분만 고치고 유효하게 완료된 부분은 보존 |

반드시 모두 만족해야 한다.

1. 구분 가능한 episode 4개 이상
2. session 경계 3회 이상
3. 앱 또는 process 종료·재실행 1회 이상
4. 앞 episode의 선택이 뒤 episode의 가능한 행동을 서로 다른 두 지점 이상에서 실제 변경
5. E3에서 과거 방식과 현재 조건이 충돌해 진행 여부를 다시 판단
6. E4에서 전체 초기화가 아닌 부분복구

실제 며칠을 기다릴 필요는 없다. 합성 event와 virtual time으로 전체 여정을 재현할 수 있다.

## 6. Mission 선언

### 제출

- 마감: `2026-07-31 10:00 KST`
- 위치: 대회 코드공유 게시판
- 게시 설정: 비공개
- 형식: PDF
- 분량: 1쪽 이내
- 권장 파일명: `SCPC2026_R2_MISSION_데이콘닉네임.pdf`

### 필수 내용

1. 대상 사용자
2. 반복·중단·변화가 있는 핵심 문제
3. 여러 session 뒤 확인 가능한 장기 목표
4. Primary value 한 가지
5. E1-E4 핵심 인과관계
6. 모바일이어야 하는 이유와 mobile constraint
7. Signature mechanism의 문제·효과 claim

### 동결 범위

Mission 마감 뒤 다음은 다른 Mission으로 변경할 수 없다.

- 대상 사용자
- 핵심 문제
- 장기 목표
- Primary value
- E1-E4 인과관계

화면·기술구조·model·algorithm은 계속 개선할 수 있다. Dacon은 Mission PDF 접수 내용을 기준으로
`MISSION_LOCK.json`을 생성해 제공하므로 참가자는 이를 직접 생성·수정하지 않는다. Dacon은 제출 전
Mission 승인·범위판정·수정요청을 제공하지 않는다.

## 7. Android release 요구사항

- Android 15/API 35
- target SDK 35
- arm64-v8a 지원
- release signing된 단일 `APP.apk`
- APK와 정확히 일치하는 `SOURCE.zip`
- public rehearsal에 사용한 최종 APK를 다시 build/sign하지 않고 같은 파일 제출
- package·version·certificate·APK fingerprint는 공식 도구가 실제 APK에서 읽음
- SHA-256·release ID·attestation·certificate digest를 참가자가 수기로 JSON/manifest에 넣지 않음
- 참가자 signing private key·keystore·password는 제출하지 않고 본인이 보관
- 선택 permission을 거부해도 Primary value의 합성 E1-E4 경로를 확인 가능해야 함

## 8. 모델·framework·backend

- model 사용은 선택이며 deterministic 구현도 허용
- model·agent framework 선택 자유
- 판단 요청 뒤 60초 안에 정직한 상태 표시
- 판단당 model/backend inference 최대 4회
- official run 전체 inference 최대 60회
- timeout·일시적 network 오류 자동 retry는 판단당 총 1회
- 실패·fallback·병렬 branch·취소·retry도 호출수에 포함
- 상한 초과가 예상되면 새 호출을 시작하지 않음
- 실제 사용하지 않은 model/backend를 사용했다고 표시하지 않음

외부 backend를 사용하는 경우:

- domain·endpoint·용도·전송 데이터 종류 신고
- release·routing·config를 제출 전에 동결
- 참가자 관리 backend source와 설정 snapshot을 `SOURCE.zip`에 포함
- Judge 개인계정·API key·결제수단·유료구독 요구 금지
- 장기 token·private key·credential을 APK/source에 평문 포함 금지
- backend/model 실패를 실제 완료로 위장 금지

## 9. 안전·합성환경·사용자 통제

- 실제 서비스 계정·결제수단·연락처·위치·사진·통화기록·고객정보·회사/학교 내부자료 사용 금지
- 실제 구매·판매·예약·결제·송금·메시지·email·전화·게시·계정변경 금지
- 필요한 행동은 app-local draft·proposal·preview·simulation·합성 상태변화로 표현
- 일반 APK 권한 밖 타 앱/system 제어·privileged API·금지권한 사용 금지
- 현재 사용자의 정정·철회·삭제가 오래된 추론·routine보다 우선
- 사용자가 앱이 기억한 정보와 상태를 확인·수정·삭제 가능해야 함
- 전체 Reset 제공
- 삭제된 원문은 export나 relaunch 뒤 부활하거나 판단에 재사용되면 안 됨
- tombstone은 삭제 ID·시각·범위·version 등 재등장 방지용 최소 metadata만 보존
- process kill 전 미완료 action을 완료로 표시하지 않음
- duplicate/out-of-order event가 action을 중복 실행하거나 완료·취소 상태를 되살리지 않음
- network·permission 실패를 성공으로 표시하거나 상태를 손상시키지 않음
- 내부 chain-of-thought나 prompt 원문은 제출하지 않음

## 10. Primary value·Signature mechanism·comparison

- Primary value: 제품이 가장 깊게 개선하려는 사용자 가치 한 가지
- Signature mechanism: 그 가치를 위해 참가자가 직접 설계한 핵심 기술 아이디어 한 가지
- `full`: Signature mechanism을 켠 상태
- `claim-off`: 같은 APK에서 그 mechanism만 끈 상태

comparison 조건:

- 같은 시작 snapshot bytes
- 같은 APK·source·UI·입력·model·seed·event order·network·quota
- 기기 database·파일·cache·메모리·backend 상태 namespace 완전 격리
- full의 저장·삭제·Reset이 claim-off에 보이면 안 됨
- 별도 APK·약한 demo·다른 model/backend로 대체 금지
- 두 arm의 result·receipt·evidence는 별도 artifact
- AUTO-CHECK에서는 실행하지 않고 deep pool 후보에게만 실행

## 11. Probe Mode

Probe Mode는 최종 평가의 필수 실행경로다. 별도 정답 프로그램이 아니라 실제 제품 화면이 사용하는
production state repository·decision logic·action/outcome ledger를 표준 입구에서 호출한다.

### 제공물

- starter AAR: `release_v3/probe/scpc-probe-starter-3.0.0-draft.aar`
- 공식 Runner: `release_v3/probe/scpc-dacon-runner-3.0.0-draft.apk`
- 공개 13-step input: `release_v3/probe/PUBLIC_PROBE_INPUT_13_STEP.json`
- public harness: `release_v3/public_harness/`
- 연결 참고 app: `release_v3/sample_app/`

Runner와 AAR를 새로 만들거나 다시 서명하지 않는다. `3.0.0-draft`는 이 동결 Kit의 contract 호환
식별자이며 참가자 제출물이 미완성이라는 뜻이 아니다.

### public UI 접근성 control

화면에 보이고 touch·keyboard·UIAutomator로 접근 가능해야 한다.

- `SCPC_PROBE_IMPORT`
- `SCPC_PROBE_RUN`
- `SCPC_PROBE_EXPORT`

### 필수 지원 operation 13종

아래는 고정된 13단계 순서가 아니라 앱이 모두 지원해야 하는 operation **종류의 집합**이다.

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

`PROBE_INPUT.schema.json`의 한 run은 1–80개 step을 가질 수 있다. operation은 반복될 수 있고 공개
13-step과 다른 유효한 순서·값·표현으로 들어올 수 있다. 앱은 임의의 공개·hidden input에서 각 input
step을 처리하고, result에 **각 input step과 같은 순서·step ID·event ID·operation인 step result를 정확히
한 번** 기록해야 한다. 공개 13-step은 연결·복구를 확인하는 reference run 하나이지 official 실행의
고정 서사가 아니다.

### 필수 semantic role

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

### result 경계

앱은 관찰 가능한 state·decision·action·receipt·evidence만 출력한다. 다음은 출력·계산하면 안 된다.

- expected relation/state
- PASS/FAIL
- relation outcome
- CORE anchor
- Q1-Q6 또는 Q/80
- qualification/pool cut 추정

public pack ID·surface string·공개 입력 문장을 감지해 정답 분기를 만들면 안 된다. public UI와 protected
component가 같은 candidate adapter와 production core를 호출해야 한다.

## 12. 평가 흐름

```text
최종 제출물 동결
  → CODE/SEC
  → 기술통과 APK 전원 AUTO-CHECK
  → hidden 기준 대조 및 provisional machine Q/80
  → 동결 정책으로 SELECTED-REVIEW 후보 선정
  → J1/J2 사람검증 및 FINAL_Q 확정
  → 별도 동결 pool 정책으로 심층 기술검증 후보 선정
  → full/claim-off comparison·ownership·CII
  → 추가 pool 후보가 없을 때 최종점수·순위 계산
```

- 개발 중 Dacon official input 실행·official 점수 회신 없음
- public rehearsal과 self-score는 비공식 준비도 점검
- official은 공개 연습과 다른 값·표현·순서·surface 사용
- 점수 향상을 위한 반복 AUTO-CHECK 없음
- 운영환경 장애가 확인된 경우에만 incident 절차로 동일 동결 APK 재실행
- 사람 Judge가 모든 APK를 처음부터 직접 조작하지 않음

## 13. Hard gate

G0-G7을 모두 통과해야 FINAL_Q·deep 검증·수상 대상이 될 수 있다. 높은 Q로 gate 실패를 보상할 수 없다.

| Gate | 필수 조건 |
|---|---|
| G0 | APK·SOURCE·설정 동결, 일치, 재현성 |
| G1 | 합성 data, Mission 범위, 개인정보 금지 |
| G2 | 실제 외부행동·금지권한 없는 합성 실행 |
| G3 | 제안·대기·중단·실패·확인 완료의 정직한 구분 |
| G4 | 현재 정정·철회·삭제 우선과 lifecycle 뒤 올바른 상태 |
| G5 | 안전하게 멈추면서 E1-E4 기본 사용자 가치 제공 |
| G6 | 화면·state·action·export·receipt·호출기록 일치 |
| G7 | 모바일 필요성, restart/local continuity, 별도 mobile constraint |

## 14. CORE anchor와 Q/80

공통 CORE:

| CORE | 의미 |
|---|---|
| CORE-1 | 선택적 맥락·distractor 배제 |
| CORE-2 | 현재 권위·정정·철회·삭제·descendant invalidation |
| CORE-3 | E2 반복부담 감소와 필요한 안전확인 유지 |
| CORE-4 | E3 예외판단과 E4 부분복구 |
| CORE-5 | restart·duplicate·out-of-order·exactly-once reconciliation |
| CORE-6 | delayed outcome·event/action/outcome ledger·evidence 일치 |

공개 anchor:

| Anchor | 의미 |
|---:|---|
| 0 | 요구 반대·치명 실패·증거 없음·관찰 불가 |
| 1 | 준비한 happy path만 되고 새 값·변화·재시작에서 실패 |
| 2 | 정상 상황은 되지만 장기 연결·범위·회복 일부 미흡 |
| 3 | 기본 상황과 공개 변형에서 state·action·evidence 일관 |
| 4 | unseen surface와 late outcome에서도 3의 성질 유지, failure boundary 명확 |

Q profile:

| Q | 의미 | 최대 |
|---|---|---:|
| Q1 | 장기 상태·계획 연속성 | 24 |
| Q2 | 적응적 안전 자율성 | 14 |
| Q3 | 선택적 맥락·기억 가치 | 12 |
| Q4 | 현재성·lifecycle·사용자 통제 | 14 |
| Q5 | 범위 경계·유효한 상태전이 | 8 |
| Q6 | 행동·자원·증거 무결성 | 8 |

CORE별 Q 최대 기여:

| CORE | 최대 기여 |
|---|---:|
| CORE-4 | 21 |
| CORE-3 | 19 |
| CORE-6 | 14 |
| CORE-5 | 10 |
| CORE-1 | 9 |
| CORE-2 | 7 |

모든 CORE anchor 3이면 Q=60/80이다. 정확한 lookup은
`release_v3/candidate_kit/PUBLIC_SCORING_LOOKUP.json`이다. 앱·참가자·Judge가 official Q를 직접
입력하지 않고 Dacon 자동평가 도구가 계산한다.

## 15. CII/20과 최종점수

CII는 deep pool 후보의 Signature mechanism 인과기여·기술소유권 점수다.

| 항목 | 최대 | 확인 내용 |
|---|---:|---|
| C1 | 2 | 최종 release에 사전 등록한 문제·효과 claim 명료성 |
| C2 | 3 | mechanism 적합성과 불필요한 복잡성 부재 |
| C3 | 8 | 동일조건 claim-off paired comparison에서 인과 이득 재현 |
| C4 | 4 | late horizon·unseen surface·privacy/resource trade-off에서도 이득 유지 |
| C5 | 3 | source·state·receipt 기반 failure boundary·ownership 설명 |

- C3가 4점 미만이면 CII 합계 최대 9점
- CII 14점 이상은 late-horizon과 unseen surface 이득이 모두 재현되어야 함
- pool 밖 후보 CII는 0점이 아니라 `not_evaluated_not_in_pool`
- 평가하지 않은 CII를 Q에 더해 가짜 /100을 만들지 않음

verified FINAL_Q와 verified FINAL_CII를 모두 가진 후보:

```text
final_total = FINAL_Q.q_total + FINAL_CII.cii_total
최대 100 = Q 최대 80 + CII 최대 20
```

동점 비교순서:

1. final_total
2. CII
3. Q
4. C3

모두 같으면 공동순위다. 기본 수상 slot은 3명이며 3위 경계 완전 동점자는 모두 수상대상에 포함된다.

## 16. 최종 제출물 7종

최종 마감: `2026-08-05 10:00 KST`

1. `APP.apk`
   - API 35, target SDK 35, arm64-v8a, 최종 서명 APK
2. `SOURCE.zip`
   - APP과 일치하는 app/backend source, build/config, dependency/license 자료
3. `MISSION_AND_TECHNICAL_NOTE.pdf`
   - Mission, E1-E4, CORE 연결, architecture, Signature mechanism, comparison,
     mobile counterfactual
4. `INSTALL_AND_USE_GUIDE.pdf`
   - 참가자 도움 없이 install·Reset·run·restart·export·comparison하는 방법
5. `BUILD_AND_SUBMISSION_INFO.md`
   - 재현 build, dependency, model/backend/runtime 설정, 데이터 전송범위
6. `SAMPLE_EXPORT/`
   - public 13-step reference run의 구조화 evidence
7. `DEMO_VIDEO.mp4`
   - E1-E4·변화·restart·사용자 통제, 3분 이내

영상·문서는 설치·실행되지 않는 APK를 대신하지 않는다.

### SAMPLE_EXPORT

```text
SAMPLE_EXPORT/
├── MISSION_LOCK.json
├── RUNTIME_IDENTITY.json
├── EVIDENCE_INDEX.json
├── EXPORT_INDEX.json
├── PUBLIC_PROBE_RESULT.json
└── evidence/...
```

- 압축 전 합계 20 MiB 이하
- `MISSION_LOCK.json`: Dacon 생성
- `PUBLIC_PROBE_RESULT.json`: 공식 Runner 생성
- `RUNTIME_IDENTITY.json`, `EVIDENCE_INDEX.json`, `EXPORT_INDEX.json`:
  `FINALIZE_SAMPLE_EXPORT.py` 생성
- 참가자 직접 작성: 실제 기능을 설명하는 `MISSION_ADAPTER.json`과 evidence 파일
- evidence ID: `[A-Za-z0-9][A-Za-z0-9._-]{0,127}`
- 같은 evidence ID를 여러 파일에 중복 사용하지 않음
- 허용 evidence media: JSON, text, PNG, MP4
- SHA-256·release identity·runtime identity·file manifest를 수기로 작성하지 않음

## 17. 제출 source·문서 요구

`SOURCE.zip`:

- 실제 사람이 작성한 source·build/config·license·backend 자료
- `build/`, `.gradle/`, IDE cache, 생성 산출물 제외
- `MISSION_ADAPTER.json` source asset 원본 한 개만 포함
- adapter source와 integration test 포함
- 재현 build 명령과 JDK/SDK/tool version
- model·SDK·library·AI coding tool·license·데이터 전송범위
- backend 사용 시 source와 동결 release/routing/config의 이름·버전·재현방법
- secret 없이 build 가능한 설정
- signing private key·keystore·password 제외

문서에는 다음 위치와 동작을 명확히 설명한다.

- Mission과 E1-E4 인과관계
- 두 개 이상의 downstream causal change
- CORE-1-6 대응
- current authority, delete/tombstone, partial recovery
- process death, network·permission failure
- Signature claim, full/claim-off 허용 차이와 metric
- mobile lifecycle·local continuity·별도 constraint·PC counterfactual
- public UI와 protected Probe 경로
- model/backend/runtime freeze, invocation counting, retry
- install → Reset → E1 → E2 → 정정/철회/삭제 → E3 → kill/relaunch → E4 → export → End

## 18. 공개 연습과 self-score

- public 13-step은 공개 연결·복구 점검용이며 official hidden 정답이 아님
- `PUBLIC_REHEARSAL_CASES.json`의 값·문장·순서는 official hidden에서 재사용되지 않음
- public input을 hard-code하지 않고 값·surface·entity·order 변형에 일반화
- `SELF_SCORE.py`는 공개 anchor와 lookup을 이용한 비공식 준비도 계산기
- self-score는 official 점수·순위·본선 진출을 보장하지 않음
- self-score 결과를 앱의 Probe result에 포함하지 않음

기본 실행 순서:

```text
MISSION_LOCK 수령
→ 최종 APP.apk와 MISSION_ADAPTER 준비
→ make_local_integration_fixture.py
→ runnerctl.py run
→ evidence 준비
→ FINALIZE_SAMPLE_EXPORT.py
→ VALIDATE_SAMPLE_EXPORT.py
```

필요 환경:

- Python 3.10+
- `jsonschema>=4.18,<5` 전용 venv
- JDK 17
- Android SDK Platform 35
- platform-tools `adb`
- command-line tools latest `apkanalyzer`
- build-tools 35.0.0 `apksigner`

## 19. AI 도구·외부 자료

- AI coding tool 사용 허용
- 사용한 주요 AI 도구·model·library·SDK·license·데이터 전송범위 신고
- 제출자는 code 동작·보안·failure boundary를 이해하고 설명·진단할 수 있어야 함
- 외부 source·library·SDK의 license·보안·실패 책임은 참가자에게 있음
- 대회 기간 중 source/result를 개인적으로 타 참가자와 공유 금지
- 공개하려면 Dacon 코드공유 게시판 등 공식 플랫폼을 사용

## 20. 상금과 법적 조건

총상금 7,000만원:

| 순위 | 인원 | 1인당 상금 |
|---|---:|---:|
| 1등 | 1명 | 2,000만원 |
| 2등 | 2명 | 1,000만원 |
| 3등 | 5명 | 400만원 |
| 4등 | 5명 | 200만원 |

수상자 라이선스:

- 결과 발표일부터 3년
- 전 세계적·비독점적·완전 지불된 이용 권한
- 영리 목적 사용, 복제, 상업적 판매·배포, 공개 전시, 디지털 공연, 수정, 2차적 저작물 작성 포함
- 상금이 해당 이용허락의 완전하고 최종적인 대가
- 3년 초과 이용, 권리 양도, 제3자 이용허락·양도는 수상자와 별도 협의 및 합당한 대가 필요

참가자는 제출물이 자신의 창작물이며 타인의 저작권·특허·영업비밀을 침해하지 않음을 보증해야 한다.

## 21. 로컬 Kit 확인 상태

확인된 사실:

- `release_v3`의 주요 문서·schema·template·tool·Runner·AAR·sample app 존재
- JSON 17개 문법 파싱 성공
- root starter AAR와 sample app 포함 AAR가 byte-for-byte 동일
- sample app APK는 `org.scpc.r2.sample`, `0.1-local`인 debug-only 참고 APK
- sample app에는 Mission별 evidence export UI가 없음
- 공식 Kit 폴더를 구현 작업공간처럼 수정하지 말고 읽기 전용 reference로 보존

현재 개발환경:

- Android SDK: 없음
- Android Studio: 없음
- `adb`, `apkanalyzer`, `apksigner`: 없음
- JDK: 21만 설치. JDK 21이 즉시 빌드를 차단한다고 확인된 것은 아니지만 공식 build·sample 재현환경인
  JDK 17은 아직 없음
- Python bundled runtime: 존재
- candidate tool용 `jsonschema` venv: 아직 없음
- Git repository: 아직 아님

## 22. 현재 미확인·충돌 항목

### 수상 인원 표기

Kit의 평가과정은 기본 수상 slot을 3명으로 설명하지만 Dacon 상금표는 1·2·3·4등 합계 13명에게 상금을
배정한다. 두 숫자를 임의로 같은 의미로 해석하지 않는다.

해야 할 일:

- Dacon 토크 또는 운영 문의로 3명과 13명이 각각 가리키는 평가·시상 범위를 확인
- 답변과 확인시각을 이 문서에 기록

### 개인 최종 제출 Drive 위치

일부 공식 문구는 "팁 탭", 규칙은 "팀 탭"이라고 표현한다. 비로그인 브라우저에서 `/team`은 개요로
돌아가 참가자별 Google Drive 링크를 확인할 수 없었다.

해야 할 일:

- 로그인한 참가자 계정에서 개인 Drive 링크 확인
- 링크와 접근권한을 최종 마감 훨씬 전에 시험

### 일반 언어 제한 문구

일반 동의사항에는 별도 규칙이 없을 때 R/Python만 사용한다는 boilerplate가 있으나, 본 대회 전용 규칙과
Kit는 Android APK, Kotlin sample, JDK/SDK build를 명시한다.

해야 할 일:

- Kotlin/Java Android 구현 허용을 토크 또는 `dacon@dacon.io`로 확인해 기록

### 운영 변경

Dacon의 새 공지·토크 답변·Kit 교체가 생기면 해당 내용이 이 snapshot보다 우선한다.

## 23. 변경 기록

| 일시 KST | 변경 | 근거 |
|---|---|---|
| 2026-07-29 10:35 | 최초 공식 웹·Kit·용어집 snapshot 작성 | Dacon 대회 페이지, `release_v3`, 공식 용어집 |
| 2026-07-29 | 13종 operation과 input step을 구분하고 운영 위험·JDK·수상 인원 충돌을 명시 | Probe schema·contract·수정후보 검토 |
