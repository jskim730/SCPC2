# 나만의 Open Mission 정의하기

## 1. T+48시간 Mission 선언

이 과제는 정답 화면이 정해져 있지 않고, 참가자가 스스로 고른 장기 목표를 얼마나 깊이 이뤘는지를
봅니다. Mission 선언은 그 목표를 개발 초반에 좁게 확정해 두는 절차입니다. 목표를 미리 고정하면 남은
기간을 한 가치의 깊이에 집중해 쓸 수 있고, 나중에 목표를 바꿔 평가에 끼워 맞추는 일 없이 모든 제출물을
“자신이 선언한 목표를 달성했는가”라는 같은 기준으로 볼 수 있습니다.

과제 공개시각 `T` 뒤 48시간 안에 Dacon 안내경로로 1쪽 이내 Mission 선언을 제출합니다. 선언에는 다음을
포함합니다.

1. 대상 사용자와 반복·중단·변화의 문제
2. 여러 session 뒤 확인 가능한 장기 목표
3. Primary value 한 가지
4. E1(Learn)·E2(Reuse)·E3(Exception)·E4(Recover)의 핵심 인과관계 (자세한 정의는 `01_CHALLENGE_OVERVIEW.md` 2절)
5. 모바일이어야 하는 이유와 mobile constraint
6. Signature mechanism의 문제·효과 claim

Dacon은 접수사실만 확인하며 사전 승인·범위판정·수정요청을 제공하지 않습니다. 대상 사용자, 핵심문제,
장기 목표, Primary value와 E1–E4 인과관계는 T+48부터 동결됩니다. 화면·기술구조·model과 구현은 계속
개선할 수 있지만 다른 Mission으로 바꿀 수 없습니다.

## 2. 7일 완주 가능한 범위

좋은 Mission은 좁고 깊습니다.

- 반복되는 사용자 결과가 하나이고 성공상태를 관찰할 수 있습니다.
- 합성 event와 virtual time으로 30–40분 안에 전체 여정을 재현할 수 있습니다.
- 실제 외부서비스 없이 app-local preview·draft·state change로 가치를 보여 줍니다.
- E3(Exception) 변화가 한 부분을 무효화하지만 E4(Recover)에서 나머지는 보존할 수 있습니다.
- 새 도메인 기능보다 restart·delete·evidence까지 완성할 수 있습니다.

다음은 피하십시오.

- 한 번 입력하면 답하고 끝나는 chatbot
- 다음 버튼으로 고정 성공장면만 재생하는 demo
- 여러 domain을 얕게 모은 기능목록
- 실제 며칠 대기, 실제 계정·구매·메시지에 의존
- 모든 과거를 항상 prompt에 넣거나 변화 때 전체 data를 삭제하는 방식

## 3. 내 Mission을 공통 확인 항목(CORE)에 연결하기

Open Mission의 화면·용어는 자유지만, 모든 제출물은 도메인과 무관하게 아래 여섯 가지 공통 확인
항목(CORE-1…6)으로 평가됩니다. 각 항목에 대해 자신의 Mission에서 무엇을 정할지 답할 수 있어야 합니다.

| CORE | Mission에서 정할 것 |
|---|---|
| CORE-1 선택적 맥락 | 지금 목표에 필요한 current context와, goal·user·entity·time이 달라 헷갈리게 하는 distractor(무관한 과거 정보)의 구분 |
| CORE-2 현재 권위 | 정정·철회·삭제가 그 정보에서 파생된 하위 항목(descendant)을 어디까지 무효화하는지 |
| CORE-3 반복부담 | E2(Reuse)에서 줄어드는 입력·질문·클릭과, 그래도 유지해야 하는 안전확인 |
| CORE-4 예외·회복 | 상황이 바뀌는 지점(change point)에서 질문(ASK)·대기(WAIT)·보류(ABSTAIN)·재계획(replan) 중 무엇을 할지와 부분복구 |
| CORE-5 restart reconciliation | 앱을 껐다 켠 뒤 상태를 어긋남 없이 다시 맞추는 것(reconciliation) — commit 경계, 중복(duplicate)·순서뒤바뀜(out-of-order) event 처리와 local continuity |
| CORE-6 delayed outcome·evidence | 늦게 온 결과가 다음 판단을 바꾸고, 행동·결과를 순서대로 남긴 기록장(ledger)과 일치하는지 |

Probe Mode(자동 점검 입구, `08_PROBE_MODE_CONTRACT.md`)가 최종 운영방식으로 채택되었으므로,
최종 제출에는 `MISSION_ADAPTER.json`이라는
연결표와 설치안내에 위 CORE 역할과 13개 시험 operation이 **내 앱의 어느 부분에 대응하는지** — 어떤 state
저장소·callback·화면·evidence인지 — 를 적습니다. 이렇게 적어 두면 서로 다른 Mission이라도 Dacon 점검
도구가 각 앱에서 무엇을 확인할지 위치를 찾을 수 있습니다. 작성 예시는
`templates/MISSION_ADAPTER_EXAMPLE.json`이며, official hidden이 실제로 쓰는 값·순서·정답관계(expected
relation)는 공개되지 않습니다.

## 4. 비교 가능한 Signature mechanism

최종 release에 problem statement, 예상효과, 성공·실패 metric과 trade-off를 등록합니다.

full과 claim-off는 동일한 source snapshot(공통 시작 상태)에서 시작하고 state namespace(상태공간)를
완전히 분리하며, mechanism 이외의 UI, model, seed, event order, quota와 network 조건을 바꾸지 않습니다.

이 비교모드와 상태 격리는 **참가자가 자신의 APK에 직접 구현합니다.** Dacon이 comparison용 framework나
라이브러리를 따로 제공하지 않으므로, “같은 시작 상태를 서로 분리된 두 저장공간에 복제하고, 한쪽의
저장·삭제·Reset이 다른 쪽에 보이지 않게 한다”는 요건만 지키면 구현 방법은 자유입니다. (Dacon이 제공할
starter AAR·서명 Runner는 Probe Mode 자동점검용이며 comparison과는 무관합니다.) 저장공간을 나누는
구체적 방법과 예시는 `03_TECHNICAL_AND_SAFETY_RULES.md`의 7절을 따릅니다.
