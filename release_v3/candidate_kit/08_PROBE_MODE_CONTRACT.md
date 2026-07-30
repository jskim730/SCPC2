# 표준 Probe Mode 계약

> **필수 계약:** Probe Mode는 최종 평가에 사용하는 필수 실행경로입니다. 참가자는 공식 Kit에
> 포함된 starter AAR·Runner·공개 계약을 기준으로 구현합니다.

## 1. 왜 필요한가

Open Mission의 화면과 용어가 모두 다르면 운영자가 40개 APK의 hidden 행동을 사람 손으로 전부 실행해야
합니다. 예선 2차는 모든 기술통과 제출물을 먼저 AUTO-CHECK(전원 자동 기본검증)하고, 점수·profile
buffer·gate-risk와 동결표본에 해당하는 후보만 사람이 정밀검증하기 위해 같은 APK 안에 작은
**Probe Mode**를 요구합니다.

Probe Mode는 별도 정답 프로그램이 아닙니다. production 화면이 사용하는 다음 logic과 state를 그대로
호출하는 합성 시험입구입니다.

- context selection
- current authority·correction·revoke·delete
- plan·decision·partial recovery
- persistence·restart reconciliation·idempotency
- action/outcome ledger와 receipt

별도 hard-coded state machine이나 사전계산 결과를 반환하면 release·evidence parity gate가 실패합니다.

## 2. 공식 Kit 제공물

공식 참가자 ZIP에는 다음 제공물이 함께 들어 있습니다.

아래 경로는 모두 **참가자 ZIP을 압축 해제한 최상위 폴더**를 기준으로 합니다.

| 제공물 | ZIP 루트 기준 상대경로 | 무엇에 쓰는가 |
|---|---|---|
| starter contract AAR | `probe/scpc-probe-starter-3.0.0-draft.aar` | 참가자 앱과 공식 Runner가 같은 형식으로 요청과 결과를 주고받게 해 주는 **연결용 부품**입니다. 앱 프로젝트에 넣고 참가자의 실제 기능을 이 부품에 연결합니다. |
| 서명된 공식 Runner APK | `probe/scpc-dacon-runner-3.0.0-draft.apk` | 공개 연습이나 공식 점검 때 시험 입력을 참가자 앱에 전달하고, 앱이 내보낸 실행 결과를 회수하는 **시험 실행 앱**입니다. 참가자가 새로 만들거나 다시 서명하지 않습니다. |
| 공개 연습도구(public harness) | 실행: `public_harness/runnerctl.py`<br>입력 준비: `public_harness/make_local_integration_fixture.py`<br>13-step 입력: `probe/PUBLIC_PROBE_INPUT_13_STEP.json` | 참가자가 제출 전에 자신의 PC와 Android 기기에서 공식 Runner APK를 공개 입력으로 반복 실행하고 결과 파일을 모으는 **연습용 실행 도구**입니다. 별도의 공개 연습 전용 Runner APK가 아닙니다. |
| sample app·adapter | sample 전체: `sample_app/`<br>adapter 예시: `sample_app/app/src/main/java/org/scpc/r2/sample/SampleProbeAdapter.kt` | 자신의 앱 기능·저장 데이터·판단 결과를 Probe Mode에 어떻게 연결하는지 보여 주는 **참고 예제**입니다. 그대로 제출하는 정답 앱은 아닙니다. |
| 연결 가이드·검증 예제 | 연결 가이드: `probe/STARTER_AAR_INTEGRATION.md`<br>sample test: `sample_app/app/src/test/java/org/scpc/r2/sample/SampleCoreTest.kt`<br>전체 실행 도구: `public_harness/runnerctl.py` | AAR 연결부터 입력 실행, 앱 재시작, 결과 내보내기까지 제대로 이어지는지 확인하는 **설명서와 점검 예제**입니다. 오류가 나거나 공개 실행과 실제 앱의 동작이 다를 때 원인을 찾는 데도 사용합니다. |
| 제출 기록 자동 완성·검사 | 완성: `candidate_kit/FINALIZE_SAMPLE_EXPORT.py`<br>재검사: `candidate_kit/VALIDATE_SAMPLE_EXPORT.py` | APK·SOURCE·Runner 결과에서 필요한 지문과 목록을 자동으로 만들고 검사합니다. 참가자가 SHA-256·release ID·runtime identity를 손으로 적지 않게 해 주는 도구입니다. |

표의 파일은 모두 Dacon 페이지의 공식 다운로드 링크로 제공되는 하나의 최종 Kit에서 사용합니다.
Dacon이 게시 전에 공식 링크와 최종 배포본을 확인하며 참가자는 SHA-256이나 release ID를 확인·대조하지
않습니다. 다른 pilot/RC의 파일을 섞어 쓰지 마십시오. Runner 또는 contract AAR 교체가 필요하면
수정된 전체 Kit가 다시 제공되며, 그 교체기간은 참가자의 7일 개발기간에 포함하지 않습니다.

Runner package·공개 인증서 확인값과 contract version은 starter AAR 안에 포함되어 있으며 AAR와
공식 도구가 자동으로 확인합니다. 참가자는 별도 certificate/contract manifest를 찾거나 인증서
SHA-256을 manifest에 복사하지 않습니다.

schema의 `probe_contract_profile` 값은 public과 official 실행이 공유하는 **interface 규격 이름**입니다.
호환성 때문에 남겨 둔 `DACON_SIGNED_RUNNER_PROTECTED_COMPONENT_V3_DRAFT`라는 legacy 규격명은
현재 서명 주체나 참가자의 추가 의무를 뜻하지 않습니다. public result에 이 값이 있다고 해서 official
실행했다는 뜻도 아닙니다. official 여부는 caller certificate·assignment·일회용 token·nonce와 실행기록으로
따로 증명합니다.

참가자는 다음을 구현·제출합니다.

1. 같은 `APP.apk` 안의 protected Probe component와 public 진입 UI
2. `SOURCE.zip` 안의 candidate adapter source와 integration test
3. 실제 기능 연결을 설명하는 `MISSION_ADAPTER.json`
4. public probe input을 실행한 뒤 공식 Runner가 내보낸 `PUBLIC_PROBE_RESULT.json`
5. 설치안내의 public import·run·export와 parity 확인방법

새로운 여덟 번째 제출묶음을 요구하지 않습니다. adapter는 SOURCE에, public result는 SAMPLE_EXPORT에
포함합니다.

## 3. 두 실행경로

### public rehearsal

여기서 **공개 점검 화면**은 평소 제품 화면과 별개로, 합성 input을 불러와 실행하고 결과를 내보내는
세 control을 모아 둔 참가자용 진입 화면입니다.
앱의 public UI와 protected component는 서로 다른 진입경로이지만 같은 candidate adapter와
production core에 연결해야 합니다. 참가자는 공개 연습에서 public UI 또는 공개 연습도구를 쓰고,
공식 Runner는 AUTO-CHECK에서 protected component를 사용합니다.

모든 참가자는 공개 연습도구(`public_harness`) 또는 앱 안의 public UI로 공개 input을 실행합니다.
공개 연습도구는 별도 Runner APK가 아니라 Kit에 포함된 공식 Runner APK를 공개 입력으로 구동합니다.
이 공개 점검 화면 안에
다음 세 control을 정확한 accessibility content description으로 제공합니다.

| content description | 동작 |
|---|---|
| `SCPC_PROBE_IMPORT` | public `PROBE_INPUT.json` 선택·schema 검증 |
| `SCPC_PROBE_RUN` | import한 public step을 production core에 실행 |
| `SCPC_PROBE_EXPORT` | 점수 없는 `PROBE_RESULT.json` 저장 |

각 control은 화면에 보이고 키보드·touch·UIAutomator로 접근 가능해야 합니다. import 오류, 실행 중,
성공·실패와 export 위치를 화면에 정직하게 표시합니다.

### official AUTO-CHECK

최종 제출 마감과 CODE/SEC 적격성 검사 뒤 Dacon이 공식 Runner로 AUTO-CHECK를 실행합니다.
응시자가 개발 중 업로드할 때마다 Dacon이 official 점수를 돌려주는 방식이 아닙니다. 공식 Runner가
candidate APK의 protected component에 **explicit intent**를 보냅니다. Package·인증서·contract
확인값과 실제 Android 호출값은 starter AAR가 자동 처리하며, logical action은 다음 세 가지입니다.

```text
SCPC_PROBE_IMPORT_OFFICIAL
SCPC_PROBE_RUN_OFFICIAL
SCPC_PROBE_EXPORT_OFFICIAL
```

contract AAR는 최소한 다음을 강제해야 합니다.

- component `exported=true`이되 공식 signature-level permission과 caller certificate allowlist를 모두 확인
- explicit component, contract version, one-time request nonce와 official run token 사용
- Runner가 부여한 read-once input URI와 write-once result URI 외의 storage 접근 금지
- candidate가 임의로 official content class·token·nonce를 발급하거나 public UI에서 재사용할 수 없음
- package/version/signing 정보는 AAR가 APK에서 읽고, release attestation은 Dacon assignment와
  Runner가 자동 생성·주입하여 input/result를 동일 run에 결박
- callback은 `MISSION_ADAPTER`에 등록된 production core만 호출

앱은 수행에 필요한 합성 role value와 operation은 받지만 restricted pack 파일을 따로 보존할 수 없습니다.
무엇보다 input에는 oracle, expected relation, PASS/FAIL, anchor, Q, cut이 들어 있지 않습니다.
채점기준은 Runner나 앱이 아니라 접근이 제한된 자동 채점환경에만 존재합니다.

actual Android intent extra, URI grant, timeout, callback와 certificate 값은 보안검토된 starter AAR가
캡슐화해야 합니다. 참가자가 이 문서만 보고 독자적인 보안 protocol을 재구현하게 해서는 안 됩니다.

Runner/AAR는 schema만 통과시키지 않고 input의 `step_id`·`event_id` 유일성, virtual-time ordering과
operation quota를 확인합니다. result에는 input 각 step이 같은 순서·ID·operation으로 정확히 한 번 있어야
하며 input·adapter·제출 APK가 서로 맞아야 합니다. 이 교차검사는 참가자가 손으로 적은 지문이 아니라
Runner와 제출 완성 도구가 실제 파일에서 계산한 값을 기준으로 합니다.

## 4. 입력·결과 파일 형식

Probe Mode는 시험 입력, 앱의 실행 결과, 참가자 Mission의 연결정보를 JSON 파일로 주고받습니다.
다음 schema(각 파일에 들어갈 항목과 작성 형식을 정한 규격 파일)와 공개 예시를 사용합니다.

- 시험 입력 형식: `PROBE_INPUT.schema.json`
- 실행 결과 형식: `PROBE_RESULT.schema.json`
- Mission 연결정보 형식: `MISSION_ADAPTER.schema.json`
- 공개 형식 예시: `templates/PUBLIC_PROBE_INPUT_EXAMPLE.json`,
  `templates/PUBLIC_PROBE_RESULT_EXAMPLE.json`

두 public 예시는 파일 모양을 읽어 보기 위한 최소 예시입니다. 제출용 13단계 input과 result는 공개
Runner가 만들며, 참가자가 예시의 ID·digest·지문을 교체해 제출하지 않습니다. 참가자가 직접 작성하는
파일은 자신의 실제 기능을 연결하는 `MISSION_ADAPTER.json`입니다.

official input에는 실제 외부행동·개인정보·secret이 없고 합성 role value만 있습니다. result는 내부
사고과정이나 prompt 원문이 아니라 관찰 가능한 구조화 사실만 담습니다.

- step별 decision state
- selected·invalidated·preserved state ID
- before/after state digest
- action idempotency·commit·outcome
- delete tombstone
- runtime identity와 invocation count
- 화면/state/export evidence ID

evidence/receipt ID는 제출 증거 파일명과 연결되므로 영문자나 숫자로 시작하고, 이후에는
영문자·숫자·마침표·밑줄·하이픈만 사용합니다(최대 128자, 확장자 제외). 한글 설명은 증거 파일
내용에 자유롭게 쓸 수 있습니다.

앱은 expected relation, `PASS/FAIL`, relation outcome, CORE anchor, Q1–Q6, Q/80과 cut 추정값을 result에
출력하지 않습니다. Dacon 자동평가기가 사전에 동결된 숨은 판정기준과 결과를 비교해 CORE anchor를
만들고 공개 lookup으로 Q를 계산합니다.

## 5. 필수 operation

| operation | 의미 |
|---|---|
| `RESET_AND_START` | clean state·새 run·counter 0 |
| `UPSERT_FACT` | source·authority·scope·lifetime을 가진 합성 fact |
| `ADVANCE_SESSION` | 새 session·surface로 이동 |
| `REQUEST_DECISION` | 현재 state로 ACT/ASK/WAIT/ABSTAIN 등 판단 |
| `CORRECT_FACT` | 더 현재의 authority로 fact 정정 |
| `REVOKE_SCOPE` | 지정 scope만 철회 |
| `DELETE_FACT` | 원문 삭제와 비가역 tombstone |
| `SET_NETWORK` | online/offline/delayed 합성 network 상태 |
| `PROCESS_KILL_RELAUNCH` | 실제 Android process 종료·재실행 checkpoint |
| `REPLAY_EVENT` | duplicate event |
| `DELIVER_OUT_OF_ORDER` | 역순 event 도착 |
| `ADVANCE_TIME` | delayed outcome·virtual time 진행 |
| `EXPORT_AND_END` | result·receipt export와 run 종료 |

실제 process kill은 Dacon harness가 ADB 또는 보안검토된 device-control path로 수행하고 앱은 relaunch 뒤
production persistence를 사용해야 합니다. 앱이 “kill된 척” 화면만 바꾸는 방식은 인정되지 않습니다.

## 6. Mission role mapping

모든 Mission은 다음 semantic role을 자신의 합성 domain에 mapping합니다.

```text
PRIMARY_GOAL
TARGET_ENTITY
DISTRACTOR_ENTITY
STABLE_VALUE
ONE_OFF_VALUE
CURRENT_AUTHORITY
REVOKED_SCOPE
PRESERVED_SCOPE
DELAYED_OUTCOME
EPHEMERAL_VALUE
```

mapping에는 화면 label만이 아니라 production state field·source path·observable evidence를 적습니다.
13개 operation도 각각 실제 production callback과 evidence에 mapping합니다. 예시는
`templates/MISSION_ADAPTER_EXAMPLE.json`이며 official hidden은 이 role에 공개 rehearsal과 다른
값·표현·순서를 넣습니다.

## 7. Probe Mode와 실제 제출 앱은 같은 기능을 사용해야 합니다

Probe Mode는 심사를 위해 따로 만든 작은 앱이나 시험 전용 기능이 아닙니다. 비유하면 **제출 앱의 같은
엔진을 다른 검사구로 작동시켜 보는 방식**입니다. 화면에서 실행하든 Probe Mode로 실행하든 실제 상태를
읽고 판단하는 핵심 기능은 같아야 합니다.

### 참가자가 지켜야 할 사항

- **같은 제출 앱을 사용합니다.** Probe Mode가 실행되는 앱은 제출한 `APP.apk`와 package name(앱
  식별자), version(버전), signing identity(앱 서명정보), runtime identity(실행 중 같은 앱인지 확인하는
  식별정보)가 모두 같아야 합니다. 시험용으로 다시 만든 별도 APK는 사용할 수 없습니다.
- **같은 실행조건을 사용합니다.** network(네트워크 상태), model(사용 모델), backend(연결 서버),
  quota(호출 한도)는 일반 심사 화면과 같은 사전 확정값을 사용합니다. Probe Mode일 때만 더 좋은 모델이나
  별도 서버를 사용하면 안 됩니다.
- **시험 전용 지름길을 만들면 안 됩니다.** Probe Mode만을 위한 가짜 저장소(mock repository), 별도
  점수용 코드경로(scoring branch), 숨은 정답을 알아보는 특정 단어나 표식(keyword)을 둘 수 없습니다.
- **시험 종류를 감지해 답을 바꾸면 안 됩니다.** 공개 연습인지 비공개 시험인지 나타내는 pack ID나 화면
  문구(surface string)를 보고 미리 준비한 정답 행동을 선택하면 안 됩니다.
- **같은 기록 연결방식을 사용합니다.** Probe 결과의 state ID와 action ID는 `SAMPLE_EXPORT`에서 사용한
  것과 같은 형식과 연결 규칙을 따라야 하며, 각 ID는 해당 Probe 실행의 근거자료까지 추적할 수 있어야
  합니다. 공개 연습과 공식 Probe는 서로 다른 실행이므로 ID 값 자체가 같아야 한다는 뜻은 아닙니다.
- **같은 연결코드를 사용했음을 보여야 합니다.** 제출한 소스코드(source)에 포함된 테스트(test)는
  참가자가 보는 일반 앱 화면(public UI)과 보호된 Probe 실행경로가 모두 같은 candidate adapter(Probe
  요청을 실제 앱 기능에 연결하는 공통 연결부)를 호출한다는 사실을 보여야 합니다.

### 사람이 다시 확인하는 경우

자동점검 결과가 사람 확인 대상에 들면 Judge는 같은 평가상황을 실제 화면에서 다시 실행하거나, 같은
내용을 확인한다고 미리 인정된 SELECTED-REVIEW(선별 수동 검증) 시험으로 확인합니다. 예를 들어 자동점검이
“삭제한 정보가 앱 재실행 뒤에도 되살아나지 않았다”고 기록했다면 Judge도 같은 상황을 다시 만들어
확인합니다. 이때 source, Probe 결과와 근거자료가 모두 같은 실제 기능에서 나온 것인지 함께 대조합니다.
시험 전용 기능으로 높은 점수를 만든 사실이 확인되면 해당 gate(통과조건)를 `FAIL`로 처리합니다.

낮은 점수 구간에서도 결과를 보기 전에 정해 둔 표본을 사람이 확인합니다. 자동점검과 사람 확인 결과가
다르면 사전에 정한 규칙에 따라 같은 점수 구간의 확인 대상을 전원까지 넓힐 수 있으며 인원 cap을
두지 않습니다. 이 검증이 끝난 뒤에만 별도의 pool 규칙으로 심층 기술검증 후보를 선정합니다.

### 기록 연결이 맞아야 다음 단계로 갈 수 있습니다

사람검증이 끝나면 Dacon 자동 평가 batch 도구가 원본기록과 사전동결 규칙으로 `FINAL_Q`와 pool
대상을 계산합니다. Pool은 CII 결과에 따라 수상 가능성이 남은 후보를 추가하고, 더 추가할 후보가
없을 때까지 반복하며 인원 cap을 두지 않습니다.

실행기록, 결과파일과 근거자료가 같은 실행에서 나왔다는 연결(binding)을 확인할 수 없거나, Probe Mode와
실제 앱이 같은 기능을 사용했다는 사실(production parity)을 확인할 수 없으면 Judge가 추정으로 점수를
고치지 않습니다. 운영사고로 기록하고 판정을 보류(`incident/HOLD`)한 뒤 Dacon이 새 배정으로
재실행합니다.

## 8. 채택 결정과 7일 보호

Probe Mode는 최종 운영방식으로 채택됐으며, 서명된 공식 Runner·starter AAR·public
harness·sample·13-step public input은 하나의 공식 Kit로 제공합니다. Runner 확인정보와 contract
설정은 starter AAR에 포함되어 자동 처리됩니다. 참가자는 Dacon 페이지의 공식 다운로드 링크로 받은
Kit를 사용하고, 공지된 과제 공개시각 `T`부터 7일을 계산합니다. Dacon이 게시 전에 공식 링크와
배포본을 확인하므로 참가자가 SHA-256이나 release ID를 대조하지 않습니다.

공식 제공물의 재현 가능한 결함은 참가자 실패로 채점하지 않고 incident/HOLD 후 수정·재실행합니다.
제공물 수정이 불가피하면 개발시계를 중지하고 수정된 전체 Kit 기준으로 7일을 다시 보장합니다. 반면 같은
공식 Kit와 Reference Android에서 재현되는 candidate adapter·production parity 결함은 참가자
책임입니다.
