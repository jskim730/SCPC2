# 예선 2차 과제 개요

## 1. 해결할 문제

실제 모바일 생활작업은 한 번의 대화로 끝나지 않습니다. 사용자는 앱을 닫았다가 돌아오고, 비슷한 일을
반복하며, 중간에 목표·상황·권한을 바꿉니다. 필요한 과거는 이어져야 하지만 오래되거나 삭제된 정보는
현재 판단을 방해해서는 안 됩니다.

이 과제의 `long-horizon task`는 단순히 화면이나 단계가 많은 작업이 아닙니다. 앞선 선택·상태·결과가
뒤 session의 판단과 가능한 행동을 실제로 바꾸는 장기 연속 과제입니다.

## 2. 하나로 이어지는 사용자 경험 (E1–E4)

| 단계 | 사용자가 경험해야 하는 결과 |
|---|---|
| E1 Learn | 필요한 목표·정보·허용범위를 배우고 사실·추론·일회성 정보를 구분 |
| E2 Reuse | 다음 유사작업에서 유효한 경험만 활용해 설명·클릭·준비 부담 감소 |
| E3 Exception | 현재 지시·상황·권한이 바뀌면 과거 routine을 멈추고 질문·대기·재계획 |
| E4 Recover | 영향받은 부분만 고치고 유효하게 끝난 부분을 보존해 다시 유용하게 진행 |

최소 네 episode, 세 session 경계, 한 번 이상의 앱 종료·재실행을 포함합니다. 앞 episode의 선택이 뒤의
가능한 행동을 서로 다른 두 지점 이상에서 바꾸어야 합니다.

## 3. 대표가치와 기술기여

- **Primary value:** 제품이 가장 깊게 개선하려는 사용자 가치 한 가지
- **Signature mechanism:** 그 가치를 위해 직접 설계한 핵심 기술 아이디어 한 가지
- **comparison mode:** 같은 APK에서 Signature mechanism만 끈 `claim-off` 상태

comparison은 동일 snapshot을 격리된 `full`/`claim-off` **상태공간(namespace)** 사본 **두 개**에 복제해,
같은 입력·model·network·quota로 비교합니다.

여기서 상태공간은 APK 실행파일이나 source code 자체가 아니라, **그 코드가 읽고 쓰는 상태가 담기는 곳**을
뜻합니다. 구체적으로는 기기 안 앱 저장소(database·파일·cache), 실행 중 메모리 상태, 그리고 참가자가
운영하는 backend 상태입니다.

두 사본은 서로 겹치지 않아야 하며, 한쪽의 저장·삭제·Reset이 다른 쪽에 보여서는 안 됩니다. 이때 별도의
약한 demo나 다른 APK로 대체할 수 없습니다. 저장공간을 분리하는 구체적 방법과 예시는
`03_TECHNICAL_AND_SAFETY_RULES.md`의 7절을 따릅니다.

## 4. 왜 모바일인가

다음을 모두 구현하고 문서화하십시오.

1. 앱 종료·process 재시작 뒤의 상태복구와 local continuity
2. 제품문제와 연결된 별도의 mobile constraint 한 가지 이상
   — permission 거부, network 실패, lifecycle 전환, local-data 통제 등
3. PC의 일반 대화창으로 옮기면 사라지거나 달라지는 state·decision·evidence를 설명하는
   mobile counterfactual

실제 다른 앱·결제·메시지·예약을 실행할 필요가 없습니다. 합성 data와 앱 내부의 안전한 동작으로
재현합니다.

## 5. 변별되는 지점

이 시험이 변별력을 갖는 핵심은, 채점에 쓰는 값이 공개 demo와 다르다는 점입니다. 공개 연습과 다른
값·표현·순서에 정정·삭제, restart, delayed outcome, network 변화를 더해, 미리 외운 성공장면만으로는
통과할 수 없게 합니다.

Probe Mode(서로 다른 Mission의 APK를 Dacon Runner가 같은 방식으로 자동 점검하도록 앱에 두는 표준
합성시험 입구)는 최종 운영방식으로 채택되었습니다. 최종 마감 receipt가 가리키는 제출물 중
기술검사를 통과한 모든 APK는 마감 뒤 공식 Runner가
자동 실행하는 AUTO-CHECK(전원 자동 기본검증)을 받고, 사람 Judge의 SELECTED-REVIEW(선택 사람
정밀검증)는 동결된 점수·profile·gate·audit 조건에 선택된 후보만 봅니다. 세부는
`README_FIRST.md`의 Probe 제공물 안내와 `08_PROBE_MODE_CONTRACT.md`를 따릅니다.
상위 구현은 다음을 함께 보여 줍니다.

- goal·user·entity가 다른 과거를 섞지 않는 선택적 맥락
- 현재의 정정·철회·삭제가 과거보다 우선
- 반복할수록 부담은 줄지만 안전한 확인은 유지
- 변화 뒤 전체 reset이 아닌 부분복구
- process kill·duplicate·out-of-order event 뒤 정확한 reconciliation
- 화면·state·action·outcome·receipt의 일치

공개 rehearsal 문장을 그대로 하드코딩해도 이 변형을 통과할 수 없습니다.
