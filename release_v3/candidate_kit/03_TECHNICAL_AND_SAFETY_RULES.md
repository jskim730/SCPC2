# 기술·안전규칙

## 1. Android release

- Android 15/API 35, target SDK 35, `arm64-v8a` 지원
- 단일 서명 APK와 일치하는 `SOURCE.zip`
- 제출할 최종 APK를 정한 뒤 같은 파일을 공개 연습과 제출에 사용
- Judge의 개인계정·API key·유료구독을 요구하지 않음
- 선택 permission을 거부해도 Primary value의 합성 E1–E4 경로는 확인 가능

평가도구는 제출한 APK에서 앱 이름(package), 버전, 서명 인증서와 파일 지문을 자동으로 읽어 같은
APK인지 확인합니다. 응시자가 이 값을 계산하거나 JSON에 옮겨 적지 않습니다. 응시자의 할 일은 공개
13단계 연습을 끝낸 뒤 APK를 다시 빌드하거나 다시 서명하지 않고, 연습에 쓴 바로 그 `APP.apk`를
제출하는 것입니다.

참가자 APK의 서명 개인키·keystore·password는 참가자가 보관하며 Dacon에 제출하지 않습니다. Dacon은
APK에 공개되어 있는 인증서 정보만 읽어 동일한 파일인지 확인합니다.

raw 기기속도·battery·발열은 점수에 포함하지 않습니다. 환경 탓 실패가 의심되면 공지된 Reference
Android에서 재현할 때만 앱 실패로 확정합니다.

## 2. 판단시간과 model 호출

- 공식 Runner 또는 배정된 live 검증에서 판단을 요청한 뒤 60초 안에
  제안·질문·대기·중단·실패·확인된 완료 중 정직한 상태 표시
- model·backend inference는 판단당 최대 4회
- official run 하나당 model·backend inference 합계 최대 60회. official run의 경계는 Dacon의
  assignment와 일회용 token으로 정하며, 참가자가 comparison의 `full`·`claim-off` arm이나 중간
  재시작을 임의의 새 run으로 나눠 호출계수를 초기화할 수 없습니다.
- timeout·일시적 network 오류의 자동 retry는 판단당 총 1회
- 실패·fallback·병렬 branch·취소된 dispatch·retry도 모두 호출수에 포함

상한을 넘길 것을 알면 새 호출을 시작하지 말고 현재 가능한 상태를 표시합니다. 이미 초과했다면 더
호출하지 않고 모든 attempt와 `quota_exceeded=true`를 남깁니다.

## 3. official run의 시작과 종료

AUTO-CHECK(전원 자동 기본검증)은 공식 Runner가 앱의 **보호된 연결부**(starter AAR의
`protected component`)를 거쳐 이 실행에만 쓰는 일회용
`operator_run_token`을 앱에 전달합니다. 앱이 이 token을 직접 만들거나 아무 곳에서나 받는 것이 아니라,
정해진 이 연결부를 통해서만 받는다는 뜻입니다.

여기서 **보호된 연결부**(protected component)는 허가된 공식 Runner만 official input과 일회용 token을
넣을 수 있도록 잠가 둔 진입 지점입니다. Android는 signature-level permission을 강제하고, 이 연결부는
호출자의 certificate가 허용목록에 있는지와 token·assignment·release가 서로 맞는지를 확인합니다.
별도의 정답 프로그램이나 평가 logic이 아니라, Runner와 제출 APK를 안전하게 이어 주는 통로일 뿐입니다.
앱은 수행할 합성 input만 받고 oracle, expected state, anchor와 점수는 받지 않습니다.

SELECTED-REVIEW(선별 수동 검증)가 별도 live run으로 배정되면 **Dacon OPS가** 새 assignment와
일회용 token으로 공식 Runner 실행을 시작합니다. Judge는 화면·상태·증거를 독립적으로 확인할 뿐
token을 만들거나 이전 token을 재사용하지 않습니다.

시작 처리의 주체를 나누면 이렇습니다. **`operator_run_token`은 평가 실행 측에서** 옵니다.
AUTO-CHECK와 별도 live SELECTED-REVIEW 모두 Dacon OPS가 승인한 공식 Runner가 token과
`RESET_AND_START` 조작을 전달합니다. 그 지시를 받으면 **나머지는 앱(제출 APK)이 직접
수행**합니다. 즉 아래 네 항목 중 assignment·token 준비와 실행 시작은 Dacon이 하고, run_id
생성·계수 초기화·묶기·시작상태 준비는 앱이 합니다.

앱은 이 네 항목을 **한 번의 원자적 시작 처리로 함께** 확정하며, 하나라도 준비되지 않으면 official run이
시작된 것으로 보지 않습니다.

1. 초기화된 상태 또는 운영자가 승인한 시작 snapshot 준비 — 앱이 준비/복원
2. 이 실행에서만 사용하는 새 `run_id` 생성 — 앱이 생성
3. model·backend 호출계수를 0으로 초기화 — 앱이 자기 계수를 0으로
4. 평가 실행 측이 건넨 token과 앱이 만든 `run_id`를 실행 종료까지 1:1로 묶어 기록 — 앱이 결박

쉽게 말하면 official run마다 **시작상태·run ID·token·호출계수 한 세트**가 있어야 하며, 서로 다른
run의 token이나 결과를 섞어서는 안 됩니다.

추가 Reset이나 중단된 run도 기록에서 삭제하지 않고 마지막 상태와 evidence를 남깁니다. public
rehearsal token은 공개 연습에서만 사용할 수 있고 official token으로 바꿔 쓸 수 없습니다. 공식
Runner의 공개 연습 실행이 남긴 token은 제출 완성 도구가 결과에서 자동으로 읽으므로 응시자가
만들거나 바꾸지 않습니다.

## 4. 실행환경·판단 receipt·evidence

`runtime identity`(실행환경 기록)는 **한 run이 실제로 어떤 APK·SOURCE와 model 사용 여부로
돌았는지를 도구가 기록한 결과**입니다. 참가자가 SHA-256이나 별도 식별자를 만들지 않습니다.
Starter AAR와 제출 생성 도구가 APK·SOURCE에서 package·version·서명 인증서·파일 지문을 직접 읽고,
run 시작·끝의 model 사용 여부와 호출수를 함께 기록합니다.

**규칙:** 한 run의 시작과 끝은 같은 APK·SOURCE에 결박되어야 합니다. 판단 도중 앱이나 설정을 바꿔
서로 다른 환경의 결과를 한 run인 것처럼 섞을 수 없습니다.

backend·routing·feature flag를 사용한다면 그 이름·버전·재현방법은
`BUILD_AND_SUBMISSION_INFO.md`에 평문으로 설명하고 관련 설정파일을 `SOURCE.zip`에 포함합니다.
Dacon CODE/SEC가 제출받은 파일에서 필요한 지문을 다시 계산합니다. 값이 필요 없는 release에 가상의
SHA-256을 채우는 방식은 사용하지 않습니다.

`receipt`는 판단 하나가 언제 시작되어 어떤 시도를 거쳐 어떤 상태로 끝났는지 보여 주는 **판단
영수증**입니다. 판단마다 다음 내용을 남깁니다.

- 판단 요청 시각과 최종상태가 결정된 시각
- 각 attempt의 ID와 local·remote·backend 실행 종류
- 각 attempt의 시작·종료 시각, success·failure·timeout 상태와 retry 여부
- 해당 판단의 호출수, run 누적 호출수와 quota 상태
- app release와 run 식별값

예를 들어 첫 backend 호출이 timeout되고 허용된 retry 한 번이 성공한 판단은 아래처럼 기록할 수
있습니다. key 이름은 이해를 위한 예시이며, 최종 제출형식은 Dacon이 제공할 starter와 schema가
정본입니다.

```json
{
  "receipt_id": "receipt-RUN-17-D03",
  "run_id": "RUN-17",
  "decision_id": "D03",
  "requested_at": "2026-07-25T10:00:00Z",
  "final_state_at": "2026-07-25T10:00:18Z",
  "decision_state": "ACT",
  "action_commit_state": "PROPOSED",
  "attempts": [
    {
      "attempt_id": "ATT-D03-1",
      "execution_kind": "REMOTE_BACKEND",
      "started_at": "2026-07-25T10:00:01Z",
      "ended_at": "2026-07-25T10:00:11Z",
      "status": "TIMEOUT",
      "retry": false
    },
    {
      "attempt_id": "ATT-D03-2",
      "execution_kind": "REMOTE_BACKEND",
      "started_at": "2026-07-25T10:00:12Z",
      "ended_at": "2026-07-25T10:00:17Z",
      "status": "SUCCESS",
      "retry": true
    }
  ],
  "decision_invocations": 2,
  "cumulative_invocations": 7,
  "quota_exceeded": false
}
```

이 예시는 판단당 호출수가 2회이고 run 전체 누적 호출수가 7회임을 보여 줍니다. model을 사용하지 않은
release의 호출수는 0이어야 합니다. 제출용 runtime identity와 기술 지문은 참가자가 이 receipt에
복사하지 않고 제출 생성 도구가 만듭니다.

prompt 원문이나 내부 chain-of-thought는 제출하지 않습니다. 화면, persisted state, action/result와
receipt가 서로 모순되면 evidence integrity가 실패합니다.

## 5. 최신 지시·삭제표식·복구

- 현재 사용자가 명시한 지시·정정·철회·삭제는 오래된 추론이나 반복 routine보다 우선합니다.
- 삭제된 원문은 export나 앱 재시작 뒤 다시 나타나거나 판단에 사용되어서는 안 됩니다.
- 필요하면 삭제한 원문 대신 `tombstone`(삭제표식)을 남길 수 있습니다. tombstone은 “이 항목은 이미
  삭제되었다”는 사실만 표시합니다. 삭제 항목 ID·삭제시각·적용범위·version처럼 재등장을 막는 데 필요한
  최소 정보만 포함하며, 삭제한 원문이나 원문을 복원할 수 있는 값은 포함하지 않습니다.
- process kill 전에 끝나지 않은 action을 재실행 뒤 완료라고 표시하지 않습니다.
- duplicate·out-of-order event가 같은 action을 두 번 실행하거나 완료·취소 상태를 되살리지 않게 합니다.
- network·permission 실패를 성공으로 표시하거나 무한히 기다리게 하거나 저장상태를 손상시키지 않습니다.

예를 들어 `fact-17`을 삭제했다면 원문은 state와 export에서 제거합니다. 해당 Probe step의
`tombstone_ids`에는 `["ts-17"]`처럼 삭제표식 ID만 기록하고, 별도 tombstone evidence에는
`ts-17`이 `fact-17`의 삭제시각·범위·version을 표시한다는 최소 metadata만 둘 수 있습니다. 앱을
재시작한 뒤 오래된 event가 `fact-17`을 다시 전달하더라도 이 삭제표식을 확인해 원문을 되살리지 않아야
합니다.

## 6. 안전한 합성실행

실제 개인정보, 계정, 결제수단과 운영 중인 외부서비스를 사용하지 않습니다. 구매·결제·예약·메시지·게시·
계정변경은 app-local preview·draft·simulation까지만 수행합니다. 일반 APK 권한 밖의 타 앱·system 제어,
privileged API와 금지권한을 사용하지 않습니다.

## 7. comparison 실행의 격리

**왜 이렇게 격리하나:** comparison의 목적은 Signature mechanism 하나가 실제로 무엇을 바꾸는지를
인과적으로 보이는 것입니다(deep 확인의 C3 항목). 관찰된 차이를 “mechanism 덕분”이라고 말하려면 두
실행이 **오직 mechanism on/off만 다르고 나머지 조건은 모두 같아야** 합니다. 만약 두 실행이 같은
저장소·메모리를 공유하면 한쪽의 저장·삭제·Reset이 다른 쪽에 스며들어, 그 차이가 mechanism 때문인지
서로 오염된 탓인지 구분할 수 없게 됩니다. 그래서 두 실행의 상태를 아래처럼 완전히 분리합니다.

comparison은 같은 시작상태에서 Signature mechanism을 켠 `full`과 그것만 끈 `claim-off`를 비교하는
추가기술확인입니다. 두 실행은 같은 source snapshot bytes를 각각 별도의 저장공간에 복제해 시작합니다.
같은 파일을 함께 읽는 방식이 아니라, 내용이 같은 두 독립 사본을 만드는 방식입니다.

예를 들어 일정계획 앱의 동일한 snapshot `S-104`를 다음처럼 준비할 수 있습니다.

- `full` 격리 저장공간 `cmp-full-R17`: `S-104`를 복제하고 Signature mechanism을 켬
- `claim-off` 격리 저장공간 `cmp-off-R17`: 같은 `S-104`를 복제하고 Signature mechanism만 끔
- 두 실행에 같은 입력·model·backend·network·quota를 적용
- 두 실행의 화면·state·action·receipt를 서로 다른 evidence로 export

`full`에서 새 event를 저장하거나 Reset한 결과가 `claim-off`의 database·memory·cache·backend state에
보인다면 두 실행이 서로 오염된 것이므로 짝비교(paired comparison)로 인정하지 않습니다. 두 결과의
차이는 Signature mechanism의 on/off 외 다른 조건에서 생겨서는 안 됩니다.

comparison은 AUTO-CHECK에서 실행하지 않고, verified Q와 동결된 pool 정책으로 정한 deep 후보의
추가기술확인에서만 실행합니다.

## 8. Probe Mode — 표준 자동 점검 입구

Probe Mode는 서로 다른 Mission과 화면을 가진 APK를 Dacon Runner가 같은 방식으로 점검할 수 있게 하는
**표준 합성시험 입구**입니다. 공개 또는 official 합성 event를 앱에 전달하면, 앱은 실제 제품 화면이
사용하는 state 저장소·판단 logic·action 기록을 그대로 실행하고 관찰 가능한 결과를 구조화된
`PROBE_RESULT.json`으로 내보냅니다.

전체 흐름을 그림으로 보면 다음과 같습니다.

```text
입력 경로 (둘 중 하나, 같은 합성 input 계약)
├─ 공개 연습:      참가자가 public_harness·앱 public UI로 공개 input 실행
└─ 공식 AUTO-CHECK: 공식 Runner가 보호된 연결부로 official input 전달
                         │
                         ▼
   앱(제출 APK)이 실제 production core를 그대로 실행
   (state 저장소 · 판단 logic · action ledger — 별도 정답 프로그램 아님)
                         │
                         ▼
   PROBE_RESULT.json export — 관찰 가능한 사실만
   (state·action·receipt·evidence / 점수·anchor·PASS·FAIL·정답관계 없음)
── 앱의 역할은 여기서 끝 ───────────────────────────────
                         │  (공식 경로만)
                         ▼
   Dacon 자동 평가 batch 도구가 동결된 기준과 비교
   → CORE 0–4 anchor → 공개 lookup → Q   (앱은 관여하지 않음)
```

Probe Mode는 별도의 정답 프로그램, 숨은 정답을 보여 주는 화면, 점수계산기 또는 production logic을
우회하는 debug demo가 아닙니다. 공개 점검 화면과 live 검증에서도 같은 app release와 production core를 사용해야
합니다.

최종 공고에서는 다음 두 실행경로를 제공합니다.

- 공개 연습경로: 응시자가 `public_harness/runnerctl.py` 또는 앱의 public UI로 공개 input을
  자기 PC·Android 단말에서 반복 실행
- official AUTO-CHECK 경로: 최종 제출 마감 뒤 Dacon이 같은 공식 Runner APK와 승인된
  배정·일회용 token을 사용해 protected component로 official input을 전달

여기서 `public_harness`는 별도의 공개 연습 전용 Runner APK가 아닙니다. Kit에 포함된 같은 공식 Runner
APK를 공개 입력으로 구동하는 참가자용 연습도구입니다.

응시자가 개발 중 APK를 올릴 때마다 Dacon이 official input을 실행해 점수를 알려 주는 방식이
아닙니다. 개발 중 공개 연습과 self-score는 응시자가 자기 PC·Android 단말에서 원하는 만큼
반복합니다. 이 결과는 공식점수가 아니며 Dacon에 채점을 요청하는 제출도 아닙니다.

Dacon은 최종 제출 마감 뒤 마감 receipt가 가리키는 최종 접수본을 CODE/SEC 검사하고, 적격 APK에
official AUTO-CHECK를 실행합니다. 점수 향상을 위한 반복 AUTO-CHECK는 제공하지 않으며,
운영환경 장애가 확인된 재실행만 정해진 incident 절차로 처리합니다.

두 경로 모두 production state repository·decision logic·action ledger를 실행하고 점수 없는
`PROBE_RESULT.json`을 export합니다.

- app은 expected relation/state·PASS/FAIL·relation outcome·anchor·Q를 계산하거나 출력하지 않습니다.
- probe pack ID·surface string에 따른 정답분기를 만들지 않습니다.
- 실제 process kill·network·duplicate·order는 Dacon harness가 통제합니다.
- Probe Mode와 공개 점검 화면·live 검증의 release·runtime·state/evidence parity를 source와 human replay로
  확인할 수 있어야 합니다.

참가자는 공식 release에 포함된 starter AAR와 Runner를 그대로 사용하면 됩니다. Runner package,
인증서 확인값과 contract version은 starter AAR 안에 포함되어 공식 도구가 자동으로 확인합니다.
참가자는 자체 보안 protocol이나 가상의 Runner를 만들거나 인증서 지문을 복사할 필요가 없습니다.
참가자 의무와 7일 개발기간은 Dacon 페이지의 공식 다운로드 링크에서 최종 Kit가 제공되고 과제
공개시각 `T`가 공지될 때 시작합니다. Dacon이 게시 전에 공식 링크와 최종 배포본을 확인하며,
참가자는 SHA-256이나 release ID를 확인·대조하지 않습니다.

APK·SOURCE·증거의 SHA-256, 인증서 지문, release attestation과 release ID는 공식 Runner·공개
도구가 실제 파일에서 자동 계산합니다. 참가자는 문서나 예시에서 이런 기술값을 복사하거나 직접
계산해 manifest·JSON에 입력하지 않습니다.

공식 제공물의 재현 가능한 결함은 참가자 실패로 판정하지 않고 incident/HOLD 후 수정·재실행합니다.
제공물 수정이 필요하면 개발시계를 중지하고 수정된 전체 Kit 기준으로 7일을 다시 보장합니다.
