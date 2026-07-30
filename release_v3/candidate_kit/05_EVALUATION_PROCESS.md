# 제출 APK의 평가과정

## 1. 전체 흐름

```text
전 후보 release·security·schema preflight
                ↓ 기술통과
Dacon OPS: 적격 APK 전원 protected Probe AUTO-CHECK 실행·원본기록
                ↓
Dacon 자동평가 도구: 숨은 판정기준 대조 → CORE 0–4 anchor
                    → 공개 lookup → provisional machine Q/80
                ↓
동결 조건으로 SELECTED-REVIEW 후보 자동선정
                ↓
Judge J1/J2: 선택 후보의 화면·state·action·evidence·source parity 독립확인
                ↓
Dacon 자동평가 도구: 사람검증 반영 FINAL_Q 확정·audit 확대
                    → 별도 pool 후보 반복선정
                ↓
Dacon OPS: pool 후보의 동일조건 full/claim-off 실행·원본봉인
응시자: 실제 artifact에 근거한 기술설명·진단
Judge J1/J2: C1–C5 독립채점
                ↓
Dacon 자동평가 도구: FINAL_CII 확정·추가 pool 후보 재선정
                    → 추가 후보가 없으면 최종점수·순위 계산
```

숨은 판정기준은 결과를 보기 전에 고정해 Dacon의 접근제한 평가환경에 둡니다. Dacon 자동평가기는
제출물이 낸 Probe result를 그 기준과 대조해 outcome·anchor를 만들며, 참가자와 Judge에게 실제 입력값이나
기대상태를 보여 주지 않습니다.

전 후보에게 사람 Judge의 첫 실행을 붙이지 않습니다. 기술검사를 통과한 모든 APK는 AUTO-CHECK을
받고, 그 결과에서 정해진 조건에 해당하는 후보와 사전동결 표본만 SELECTED-REVIEW를 받습니다.
표본에서 기계 결과와 사람 확인이 다르면 결과 전에 정한 규칙으로 같은 점수구간의 검증을 전원까지
넓힐 수 있으며, 인원 cap을 두지 않습니다.

SELECTED-REVIEW가 끝난 뒤에는 별도의 pool 규칙으로 심층 기술검증 후보를 정합니다. 심층 결과에 따라
수상 가능성이 남은 후보가 있으면 pool을 다시 계산해 후보를 추가하고, 더 추가할 후보가 없을 때까지
반복합니다. 쉽게 말하면 **사람검증 대상과 심층검증 대상을 서로 다른 동결 규칙으로 고르고, 결과가
나온 뒤 임의로 인원수를 줄이거나 늘리지 않는다**는 뜻입니다.

실제 hidden 값·표현·순서·expected relation instance·공식 qualification와 pool cut은 공개하지 않습니다.
행동 construct, operation 종류, Probe 입출력 계약, 0–4 anchor와 Q lookup은 모두에게 동일하게 공개합니다.

## 2. AUTO-CHECK — 참가자가 준비할 것

AUTO-CHECK의 공식 실행 주체는 Dacon이며, 실행 시점은 최종 제출 마감과 CODE/SEC 적격성 검사
뒤입니다. 응시자는 개발 중 공개 입력을 로컬에서 반복 실행하지만 official input을 받거나 official
AUTO-CHECK를 요청하지 않습니다.

| 시점 | 응시자가 하는 일 | Dacon이 하는 일 |
|---|---|---|
| 7일 개발 중 | 공개 연습도구 또는 앱 public UI로 공개 입력을 반복 실행하고 self-score로 약한 부분을 보완 | official input 실행이나 official 점수 회신을 하지 않음 |
| 최종 제출 | Dacon 페이지 규칙에 따라 최종 APP.apk와 제출물 7종을 제출 | 마감 receipt가 가리키는 최종 접수본을 고정 |
| 마감 뒤 | 별도 APK 개선·교체 없음 | CODE/SEC를 통과한 최종 APK 전원에 AUTO-CHECK를 실행하고 기계적으로 Q 계산 |
| 사람검증 선별 뒤 | SELECTED-REVIEW에 뽑힌 경우 안내된 검증에 참여 | 선택 후보만 J1/J2 검증, 필요하면 같은 점수구간으로 확대 |
| 심층검증 pool 선정 뒤 | pool에 든 경우 실제 artifact를 설명·진단 | Dacon OPS가 comparison을 실행하고 Judge가 CII를 독립검증 |

마감 전 재업로드가 허용되더라도 Dacon이 각 업로드를 official 채점해 점수를 돌려주는 것이 아닙니다.
점수 향상을 위한 반복 AUTO-CHECK는 제공하지 않습니다. Dacon 운영환경 장애가 확인된 재실행만
incident 절차에 따라 같은 동결 APK·동등한 입력조건으로 수행합니다.

| 참가자 확인사항 | 안내 |
|---|---|
| 구현 | 공식 starter AAR를 제출 APK의 실제 기능에 연결하고, 안내된 형식으로 실행결과와 evidence(실행 증거)를 내보냅니다. |
| 연습 | 공개 13단계 input으로 실행·재시작·결과 내보내기가 정상인지 제출 전에 확인합니다. |
| 제공 도구 | 공식 Kit에 포함된 starter AAR와 Runner를 그대로 사용합니다. 별도 평가용 Runner나 보안 protocol을 만들 필요가 없습니다. |
| 실제 평가 | 공개 연습과는 다른 비공개 input을 사용하지만, 앱이 지켜야 할 입출력 형식과 실행규칙은 공개 연습과 같습니다. |
| 앱이 출력하지 않는 값 | 앱은 `PASS/FAIL`, relation outcome, anchor, Q 점수 또는 합격선을 계산하거나 출력하지 않습니다. |

평가 도구와 환경을 준비하고 점수를 산정하는 절차는 운영진의 내부 업무입니다. 참가자는 그 내부
절차를 구현하거나 관련 파일을 제출하지 않습니다. 참가자가 할 일은
공개된 계약에 맞춰 앱을 구현하고, 공개 연습으로 정상 동작을 확인해 최종 APK와 제출물을 내는 것입니다.

## 3. AUTO-CHECK이 확인하는 것

official input은 공개 rehearsal과 다른 합성 값·surface·순서를 사용해 다음 관계를 확인합니다.

- E1(Learn) incomplete information과 stable/one-off 구분
- E2(Reuse) new surface, wrong-goal/entity distractor와 반복부담 감소
- 현재의 정정·부분철회·삭제와 descendant invalidation
- network 지연·차단에서 정직한 degradation
- proposal/commit 경계의 실제 process kill·restart
- duplicate·out-of-order event와 exactly-once reconciliation(중복·역순 event가 와도 상태를 어긋남 없이 맞춰 각 action이 정확히 한 번만 반영)
- delayed outcome과 corrected authority
- export→delete→relaunch 뒤 no resurrection(삭제한 값이 export·재시작 뒤 되살아나지 않음)

comparison mode는 AUTO-CHECK에서 실행하지 않습니다.

## 4. Hard gate — 점수와 별개인 필수 통과조건

Gate는 높은 점수로 대신할 수 없는 필수 조건입니다. **G0–G7을 모두 통과해야 FINAL_Q가 확정되고
심층 기술검증·수상 대상이 될 수 있습니다.** `FLAG`·`NOT_OBSERVED`는 사람 확인으로 보내며,
`NOT_EVALUABLE`·`CONFLICT`는 0점으로 처리하지 않고 원인을 바로잡아 재실행합니다. 확정된 기계
`FAIL`을 Judge가 임의로 `PASS`로 바꿀 수 없습니다.

| Gate | 참가자가 충족해야 하는 것 |
|---|---|
| G0 Release 동결·재현성 | 제출한 APK·SOURCE·설정이 서로 맞고 같은 release를 재현·검증할 수 있음 |
| G1 개인정보·범위 | 합성데이터만 사용하고 선언한 Mission 범위와 개인정보 금지규칙을 지킴 |
| G2 실행 경계 | 실제 결제·발송 등 외부행동이나 금지권한 없이 안전한 합성경로로 동작 |
| G3 상태 정직성 | 제안·대기·중단·실패·확인된 완료를 거짓 없이 구분 |
| G4 현재성·lifecycle | 현재 정정·철회·삭제를 우선하고 앱 종료·재실행 뒤에도 올바른 상태를 유지 |
| G5 안전한 자율성·기본 유용성 | 멈출 곳에서는 멈추면서도 E1–E4의 기본 사용자 가치를 제공 |
| G6 증거 무결성 | 화면·state·action·export·receipt·호출기록이 실제 같은 실행과 일치 |
| G7 모바일 필연성·engineering | 모바일 경험의 필요성, restart·local continuity, 별도 mobile constraint 한 가지 이상을 모두 보여 줌 |

## 5. SELECTED-REVIEW — 선별 수동 검증

SELECTED-REVIEW 대상은 결과를 보기 전에 동결한 정책으로 고릅니다.

- Q total과 모든 qualification profile buffer를 함께 만족하는 후보 (`Q total`은 Q1–Q6 합계 /80이며, Q1–Q6과 profile 정의는 `07_PUBLIC_REHEARSAL_AND_SELF_SCORE.md`의 5절 「Q 구성」, CORE→profile lookup은 `PUBLIC_SCORING_LOOKUP.json`을 따릅니다)
- machine gate가 `FLAG`·`NOT_OBSERVED`이거나 parity·integrity 위험이 있는 후보
- 후보 ID·점수와 무관하게 결정한 audit 표본

사람 Judge는 machine Q나 순위를 보지 않은 채 검증합니다. 미리 점수·순위를 보면 “이 후보는 고득점이니
맞겠지”처럼 그 숫자에 이끌려 판정이 휘둘릴 수 있기 때문입니다. 그래서 machine 결과와 독립적으로 사실을
확인하도록, Judge가 기록하는 것은 `observed fact`, prefilled relation에 대한 outcome, evidence ID와 gate
사실뿐이며, Q 숫자를 직접 입력하거나 의미에 따라 lookup을 바꾸지 않습니다. 이렇게 해야 machine 채점과
사람 검증이 서로 맞는지(교차검증) 확인할 수 있고, 위조된 고득점도 이 단계에서 걸러집니다.

SELECTED-REVIEW는 다음 **두 방식 중 결과를 열기 전에 미리 정해 둔 하나**로 진행합니다. 어느
방식에서도 Judge는 실행용 token을 만들거나 Q 숫자를 입력하지 않습니다.

- **방식 A — 같은 증거를 다시 검증(blind review):** AUTO-CHECK이 보존한 그 evidence를 사람 Judge가
  다시 확인합니다. 같은 실행을 다시 보는 것이므로, run ID·instance ID·run-token record·Probe
  result·evidence bundle 다섯 식별자가 AUTO-CHECK 기록과 **각각 같아야** 합니다.
- **방식 B — 새 live 실행:** Dacon OPS가 표면·값·순서는 다르지만 동등성이 미리 동결된 새 실행을
  공식 Runner로 시작합니다. 별개의 새 실행이므로 위 다섯 식별자는 AUTO-CHECK과 **각각 모두
  달라야** 합니다. 새 실행이라 AUTO-CHECK 기록과 이어지는 자동 연결고리가 없으므로, 그 신뢰를
  다음 두 가지로 대신 확보합니다.
  - **두 Judge가 같은 실행을 함께 봄:** J1·J2 두 사람이 **하나의 같은 새 실행**을 보고 각자 독립적으로
    사실을 기록해 서로 대조합니다(사람 간 교차확인 — 한 사람의 실수·편향을 걸러냄).
  - **네 가지로 결박:** 그 실행을 `assignment`(결과 전 미리 정한 검증 배정)·`equivalence manifest`(이 새
    실행이 원래 확인과 동등함을 미리 동결한 증명)·`release`(같은 동결 APK)·`mapping digest`(같은
    MISSION_ADAPTER 연결)에 묶습니다. 이렇게 해야 더 쉬운 다른 시나리오나 다른 빌드로 바꿔치기할 수
    없습니다.

즉 방식 A는 “다섯 식별자가 AUTO-CHECK과 같음”으로, 방식 B는 “다섯 식별자가 모두 다르되 J1·J2가 같은
새 실행을 공유함”으로 정합성을 확인합니다.

**판정이 갈릴 때:** 두 Judge가 relation·gate에서 다르게 판정하거나, 후보가 합격선 근처(qualification
경계)에 있으면, 결과를 열기 전에 정해 둔 조정 절차(`ADJ`, adjudication)로 해결합니다. 조정자도
anchor·Q 숫자를 직접 넣지 않고 relation별 최종 판정과 근거만 기록하며, 그 기록을 `finalizer`(최종
환산부)가 J1·J2 원본과 함께 다시 점수로 환산합니다. 단, 코드·보안 검토(`CODE/SEC`)나 기계 채점부가 내린
확정 `FAIL`은 사람 Judge가 `PASS`로 뒤집을 수 없습니다.

**구조 자체가 어긋날 때:** 위 결박(run tuple·release·mapping·assignment·equivalence)이 서로 맞지 않거나
production parity(제출 APK의 실제 제품 코드로 실행됐는지)가 통과하지 못하면, 이건 점수로 다툴 사안이
아니라 검증의 전제 자체가 깨진 것입니다. 이때는 점수(`FINAL_Q`)를 만들지 않고 incident/HOLD로 표시한
뒤, 새로 미리 정한 배정과 일회용 token으로 다시 실행합니다. 기계가 `NOT_EVALUABLE`(평가 불가)·`CONFLICT`(충돌)로
표시한 경우도, 그 원인이 바로잡히기 전에는 사람 검증으로 억지로 넘기지 않습니다.

**선택되지 않은 후보:** SELECTED-REVIEW에 뽑히지 않은 후보에게는 따로 사람 검증을 요구하지 않습니다. 그
후보의 AUTO-CHECK 기록은 기계 기본검증 결과로 그대로 보존하되, 평가하지 않은 deep 비교점수나 CII를 임의로
붙이지 않습니다.

## 6. 심층 기술검증 — pool 후보의 comparison·기술소유권·CII

audit가 닫힌 뒤 확정된 `FINAL_Q`와 **동결된 pool 정책**으로 심층 기술검증 후보를 정합니다.
정확한 cut과 계산값은 공개하지 않지만, 결과를 본 뒤 바꾸거나 임의 인원 cap을 두지 않습니다. 한
차례의 CII가 끝난 뒤에도 아직 수상 가능성이 남은 미평가 후보를 자동으로 다시 선정하며, 추가 후보가
없을 때까지 반복합니다.

- 같은 snapshot·입력·model·network·quota의 full/claim-off comparison
- 공개 rehearsal에 없던 surface transform과 late-horizon outcome
- source·APK·Probe result·화면 evidence 일치
- 선택한 trace의 설계·failure boundary와 현장진단
- participant ownership와 paired artifact 결박

역할은 다음처럼 분리합니다.

| 담당 | 하는 일 |
|---|---|
| Dacon OPS | 같은 APK·시작 snapshot·조건으로 full/claim-off를 실행하고 서로 다른 result·receipt 원본을 봉인 |
| 응시자 | 등록한 mechanism, 선택 trace, source·state·receipt와 실패경계를 실제 artifact에 근거해 설명·진단 |
| Judge J1/J2 | 같은 원본을 각각 독립적으로 검토해 C1–C5와 재현 여부를 기록 |
| Dacon 자동평가 도구 | pair·ownership·Judge 원본·FINAL_Q 결박과 점수조건을 검사해 FINAL_CII를 확정하고 다음 pool 후보를 계산 |

`CII`는 핵심 기술인 Signature mechanism의 인과 기여를 확인하는 /20 점수입니다.

| 항목 | 최대 | 확인하는 것 |
|---|---:|---|
| C1 | 2 | 결과를 보기 전 최종 release에 등록한 문제·효과 claim이 분명한가 |
| C2 | 3 | mechanism이 문제에 맞고 불필요하게 복잡하지 않은가 |
| C3 | 8 | 같은 조건의 claim-off 짝비교에서 mechanism의 인과 이득이 재현되는가 |
| C4 | 4 | 장기 후반 전이와 privacy·resource trade-off에서도 이득이 유지되는가 |
| C5 | 3 | 응시자가 실제 source·state·receipt로 실패경계와 기술소유권을 설명·진단하는가 |

C3가 4점 미만이면 CII 합계는 최대 9점이며, CII 14점 이상은 late-horizon과 unseen surface
이득이 모두 재현돼야 합니다. 위반값은 자동 보정하지 않고 공식 CII record 생성을 거부합니다.

예선 2차에서는 mechanism 효과 크기를 재는 공통 `MME` 지표나 `mme_met` 입력을 두지 않습니다(모든 Mission에
공통되는 방향·단위·기준이 없기 때문). mechanism이 최소한의 인과 효과를 냈는지는 위의 **C3 causal
floor**(C3가 4점 미만이면 총점 9점 상한)로만 판정합니다.

`운영 finalizer`는 사람이 아니라 **Dacon이 실행하는 자동 평가 batch 도구의 최종산출 단계**입니다. 이것이
pair-integrity·ownership·서로 다른 두 CII Judge 원본과 FINAL_Q를 함께 검증합니다. 양 arm은 FINAL_Q와
같은 APK·같은 시작 snapshot을 쓰며 result·receipt는 각각 별도 artifact여야 합니다.

위 “현장진단”에서 참가자가 얼마나 말을 매끄럽게 하느냐(설명 유창함)는 점수 기준이 아닙니다. 실제
artifact와 재현된 결과만 봅니다. pool 밖 후보의 CII는 0점이 아니라 `not_evaluated_not_in_pool`입니다.
Q/80과 평가하지 않은 CII를 더해 가짜 /100을 만들지 않습니다.

## 7. 최종점수·순위·동점·수상경계

Pool 반복이 끝난 뒤, `HUMAN_VERIFIED FINAL_Q`와
`PAIR_AND_OWNERSHIP_VERIFIED FINAL_CII`를 모두 가진 후보만 다음 총점을 만듭니다.

```text
final_total = FINAL_Q.q_total + FINAL_CII.cii_total
최대 100점 = Q 최대 80점 + CII 최대 20점
```

순위는 다음 값을 차례로 비교하며 모두 큰 값이 우선입니다.

1. `final_total`
2. `FINAL_CII.cii_total`
3. `FINAL_Q.q_total`
4. `FINAL_CII.components.C3`

네 값이 모두 같으면 공동순위입니다. 기본 수상 slot은 **3명**이며, 3위 수상경계에서 네 값이 모두
같은 후보가 여러 명이면 무작위로 탈락시키지 않고 해당 동점자를 모두 수상대상에 포함합니다.

Pool 밖 후보는 `Q/80`과 `not_evaluated_not_in_pool` 상태만 표시합니다. 평가하지 않은 CII를 0점으로
간주해 `/100` 총점을 만들거나, CII 검증 후보와 섞어 전체 ordinal 순위를 만들지 않습니다.
잠정결과 공개시각·이의제기 기간과 경로·최종결과 공개시각은 Dacon 대회 페이지가 정본입니다.

## 8. 환경과 incident

Dacon Runner와 Judge는 조직 평가전용 단말, 초기화 가능한 임시단말 또는 검증된 격리 profile만
사용합니다. 공통 network control path도 실패하면 환경사고로 분리합니다. 주 단말 실패가 환경 탓으로
의심되면 Reference Android에서 재현할 때만 앱 실패로 확정합니다. 의도적인 offline·delay·kill은
환경사고가 아니라 시험입력입니다.
