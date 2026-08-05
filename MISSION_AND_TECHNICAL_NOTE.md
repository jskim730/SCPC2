# 개인 배달 주문 에이전트

Mission & Technical Note · 참가자: First_penguin · 2026-08-05 KST

## 1. Mission과 Primary value

> 비슷한 취향과 조건으로 배달 주문을 반복하는 개인 사용자가, 현재도 유효하고 자동 적용이 허용된 취향만 재사용해 앱 중단과 조건 변화 뒤에도 한 식당의 주문 초안을 최소한의 확인만으로 완성하도록 돕는다.

핵심 문제는 "기억이 많을수록 편리하다"가 아니다. 주문마다 달라지는 조건, 식당마다 다른 옵션,
사용자의 정정과 철회가 누적되면 오래된 기억은 오히려 잘못된 자동화를 만든다. 이 제품의 Primary
value는 **반복 입력을 줄이면서도, 불확실하거나 권한이 없는 값은 다시 확인하는 것**이다.

주문을 여러 번 반복해도 아래 세 가지가 동시에 지켜지는 것이 장기 목표다.

- 유효하고 허용된 취향은 주문서에 먼저 채워져 반복 부담이 줄어든다.
- 오늘의 지시, 정정, 철회와 삭제는 과거 기억보다 우선한다.
- 품절, 미제공 옵션, network 지연과 process 종료 뒤에도 영향받은 부분만 복구한다.

모든 식당, 메뉴, 가격, 재고, 주문과 평가는 앱 안의 합성 데이터다. 실제 주문, 결제, 계정 또는
외부 서비스 변경은 발생하지 않는다.

## 2. 한 화면에서 이어지는 제품 경험

앱은 채팅과 구조화된 주문서를 결합한다. 사용자가 자연어로 조건을 말하면 후보를 보여주고, 선택한
메뉴의 주문서에서 확실한 값은 채우고 나머지만 질문한다. 각 행은 값뿐 아니라 **현재 적용 범위와
출처**를 함께 보여준다. 사용자는 같은 화면에서 값을 바꾸고, 자동 적용 권한을 철회하고, 가상 주문을
확정할 수 있다.

주문 뒤에는 app-local 지연 outcome으로 평가 요청이 도착한다. 리뷰 문장에서 취향 후보를 찾더라도
자동 저장하지 않는다. 값, 대상, 적용 범위를 화면에 제시하고 사용자가 승인한 내용만 다음 주문에
사용한다.

`내 취향과 기억` 화면은 저장된 사실, 적용 범위, 출처와 자동 적용 여부를 한곳에 모은다. 정정, 철회,
삭제 및 전체 Reset은 모두 production state에 즉시 반영된다.

## 3. E1-E4 장기 인과관계

| 단계 | 사용자 여정 | 뒤 episode를 바꾸는 원인 |
|---|---|---|
| E1 Learn | 마라향 실험점에서 맵기, 수저, 파를 정하고 주문 뒤 고수 리뷰를 남긴다 | 직접 말한 stable 취향과 승인한 메뉴 유형 범위의 리뷰 기억이 생성된다 |
| E2 Reuse | 다른 식당인 금손분식 실험점에서 마라 메뉴와 일반 떡볶이를 함께 담는다 | 전역 취향은 두 메뉴에, 고수 취향은 마라 유형에만 자동 적용되어 질문 수가 줄어든다 |
| E3 Exception | 오늘만 아주 맵게 요청하고, 수저 자동 적용을 철회하며, 저장된 맵기를 중간맛으로 정정한다 | 일회성 지시는 현재 주문만 이기고, 철회와 정정은 해당 범위의 이후 판단만 바꾼다 |
| E4 Recover | 마라향 실험점에서 중간맛 미제공과 사이드 품절을 겪은 뒤 process를 재시작한다 | 제공할 수 없는 맵기와 품절 항목만 다시 열리고, 다른 항목과 확정 기록은 보존된다 |

session 경계는 주문 단위다. 새 주문은 새 대화와 one-off 영역을 만들지만, 사용자가 허용한 stable
기억과 action ledger는 이어진다. process 경계는 session 경계가 아니다. 앱을 종료해도 현재 주문서,
대화, memory, action과 outcome이 디스크에서 복원된다.

앞 episode가 뒤 episode를 바꾸는 지점은 구체적으로 네 곳이다.

- E1의 고수 범위 승인이 E2의 다른 식당 마라 메뉴를 자동으로 채우되 일반 메뉴에는 영향을 주지 않는다.
- E3의 수저 권한 철회가 이후 주문에서 수저를 다시 묻게 하지만 다른 취향은 그대로 유지한다.
- E3의 중간맛 정정이 E4에 전달되지만, 해당 식당이 제공하지 않으므로 임의 대체하지 않고 맵기만 묻는다.
- E4의 품절 event가 사이드와 총액만 무효화하고 본 메뉴, 다른 옵션과 이미 기록된 action을 보존한다.

## 4. Signature mechanism - ASPR

대표 기제는 **Authority-Scope Projection and Repair**, ASPR이다. 기억을 문자열 목록으로 저장하는 대신
각 값을 현재 주문서에 적용할 수 있는 **권위가 붙은 사실**로 표현하고, 그 사실이 만든 필드와 action의
의존관계를 함께 유지한다.

### 4.1 Typed fact

각 fact가 지니는 정보는 다섯 가지다.

| 차원 | 의미 |
|---|---|
| kind | stable, one-off, current authority, revoked scope, delayed outcome 등 수명과 역할 |
| scope | 이번 주문, 이 식당-이 메뉴, 같은 메뉴 유형, 전역 중 어디에 적용되는지 |
| authority | 사용자 직접 지시, 현재 정정, 승인된 리뷰 등 어떤 근거가 최신인지 |
| permission | 자동 적용이 허용됐는지, 철회됐는지 |
| lineage | 이 fact가 만든 주문서 필드, action, outcome과의 의존관계 |

범위는 `이번 주문 > 이 식당-이 메뉴 > 같은 메뉴 유형 > 전역` 순으로 구체적이다. 단순히 가장 최근인
값을 고르지 않고 **현재 entity와 goal에 관련되고, 아직 유효하며, 자동 적용 권한이 있는 후보** 중에서
구체성과 권위를 비교한다. 예산과 희망시간은 매 주문 달라지는 사용자 조건이므로 stable memory로
승격하지 않는다.

### 4.2 Projection

ASPR은 현재 주문의 식당, 메뉴, session과 제공 가능한 option schema를 입력으로 받아 적용 가능한
fact만 주문서 필드로 투영한다.

```text
현재 state + 현재 menu schema
  -> entity / goal / lifetime / scope / permission 필터
  -> current authority 우선순위
  -> 주문서 field와 provenance 생성
  -> 불확실·미제공 값은 NEEDS_CONFIRMATION
```

core는 한국어 문장이나 메뉴명을 기준으로 분기하지 않는다. 자연어 계층은 catalog의 표현을 token으로
변환하고, generic core는 role, scope, authority, state transition만 처리한다. 따라서 식당명, 메뉴명,
표현 또는 event 순서가 바뀌어도 같은 규칙이 적용된다.

### 4.3 Repair

정정, 철회, 삭제 또는 catalog event가 들어오면 ASPR은 해당 사실에서 시작하는 dependency graph를
따라 파생 필드만 무효화한다. 유효한 sibling field, 다른 scope의 기억과 이미 확인된 action은 유지한다.

- 정정: 같은 scope의 current authority를 새 값으로 교체하고 영향 필드를 다시 투영한다.
- 철회: 값을 지우는 대신 그 scope의 자동 적용 권한을 끄고 다음 주문에서 다시 묻게 한다.
- 삭제: 원문 fact와 descendant를 제거하고 최소 tombstone만 남겨 export 또는 재시작 뒤 부활을 막는다.
- 품절: 해당 line의 menu 또는 option과 총액만 다시 열고 다른 line은 보존한다.

## 5. Architecture와 production parity

```text
MainActivity / MemoryActivity / ComparisonActivity
                         |
Public Probe UI ---------+--> ProductSurface / ProductionCore
Protected Probe adapter -+                  |
                                     ProductionState repository
                                     + action / outcome ledger
                                     + EvidenceWriter
```

제품 UI, 공개 Probe UI와 protected Probe adapter는 모두 `ProductionCore`와 같은 영속 repository를
호출한다. Probe 전용 판단기나 별도 mock state는 없다. adapter는 operation을 core step으로 변환하고
결과와 evidence를 공식 contract 형태로 전달하는 얇은 경계다.

`ProductionState`는 fact, field, session, permission, tombstone, action, outcome과 process epoch를 하나의
versioned 문서로 저장한다. 쓰기는 임시 파일 뒤 atomic rename으로 완료한다. process 재시작 시 같은
문서를 읽고 미완료 상태를 조정하므로 화면, action ledger와 export가 서로 다른 시점을 가리키지 않는다.

각 주문 확정은 주문서 내용에서 만든 idempotency identity로 action ledger에 기록된다. 같은 요청의 반복,
process 재시작 또는 duplicate event가 와도 이미 존재하는 action을 재사용하고 새로운 가상 주문을 만들지
않는다. delayed outcome은 action에 연결된 별도 ledger 항목이며 도착 전에는 완료로 표시되지 않는다.

## 6. 안전, 현재성 및 실패 경계

| 상황 | 제품 동작 |
|---|---|
| 열린 확인이 있음 | network와 무관하게 사용자에게 필요한 값을 묻고 action을 시작하지 않는다 |
| 합성 network가 DELAYED 또는 UNKNOWN | 확인이 끝났어도 최신 상태가 불명확하면 주문서를 보존한 채 기다린다 |
| 합성 network가 OFFLINE | 유효한 cache가 없으면 중단하고, 미확인 action을 완료로 만들지 않는다 |
| 예산·시간 조건을 만족할 후보가 없음 | 가능한 것처럼 대체하지 않고 조건을 만족할 수 없다고 표시한다 |
| option 미제공·품절 | 영향을 받은 field만 다시 확인 대상으로 만들고 나머지는 보존한다 |
| 알림 권한 거부 | 평가 요청은 in-app ledger와 배너에 남고 보조 알림만 생략된다 |
| 실제 기기 network 단절 | 실제 연결 상태만 화면에 표시한다. 재현 가능한 합성 판단 state와 섞지 않는다 |

`POST_NOTIFICATIONS`는 선택 권한이고 `ACCESS_NETWORK_STATE`는 표시 전용 normal 권한이다. `INTERNET`
권한은 없으며 외부 endpoint도 없다. 따라서 실제 결제, 메시지, 계정 변경 또는 데이터 전송 경로가 없다.

## 7. Mobile lifecycle과 사용자 통제

이 문제에서 모바일의 핵심은 작은 화면 자체가 아니라 **짧은 상호작용 사이의 중단과 재개**다. 사용자는
주문을 구성하다가 앱을 떠나고, 평가 요청은 나중에 도착하며, 다음 주문에서 과거의 선택을 다시 만난다.
앱이 이 lifecycle을 제품 기능으로 흡수하는 방식은 네 가지다.

- 대화, 주문서와 memory를 입력 직후 app-local로 지속화한다.
- 실제 process kill 뒤 같은 주문 화면과 미완료 질문을 복원한다.
- 평가 요청의 정본을 알림이 아니라 ledger에 두어 권한 거부에도 상태를 보존한다.
- 모바일 화면에서 기억의 출처, 범위, 자동 적용 여부를 확인하고 즉시 정정·철회·삭제할 수 있다.

PC의 연속된 한 session만 가정하면 process death, 권한 거부, 지연 outcome과 재진입 사이의 정합성이
핵심 설계에서 빠진다. 이 앱은 해당 경계를 별도 예외처리가 아니라 state transition의 일부로 구현한다.

## 8. Full / claim-off comparison

비교는 같은 APK, 같은 UI, 같은 parser, 같은 catalog와 같은 시작 snapshot을 사용한다. 두 arm의 storage,
evidence와 실행 namespace는 완전히 분리된다.

| 항목 | full | claim-off |
|---|---|---|
| ASPR lifetime, scope, permission typing | 사용 | 사용하지 않음 |
| dependency 기반 선택적 projection과 repair | 사용 | 사용하지 않음 |
| 현재 session에서 말하지 않은 과거 값 | 유효하고 허용된 경우에만 자동 적용 | 자동 적용하지 않고 다시 확인 |
| 삭제, tombstone, idempotency, restart, receipt | 동일 | 동일 |
| 자연어 parser, 추천, 화면, catalog | 동일 | 동일 |

비교 지표는 **VIL, Valid Interaction Load**다. 동일한 유효 주문서를 만들기 전까지 사용자가 직접 해결한
확인·입력 횟수를 센다. 두 arm 모두 안전하게 완료할 수 있어야 하며, 잘못된 entity 적용, 철회 무시,
미제공 option 자동 대체 또는 중복 action은 부담 감소로 인정하지 않는다. 이 설계는 편의성의 차이를
안전장치 약화와 분리해 ASPR의 인과 기여만 관찰하게 한다.

## 9. CORE와 구현 근거

| CORE | 제품에서 관찰되는 성질 | 검증 테스트 |
|---|---|---|
| CORE-1 선택적 맥락 | 다른 entity·goal, 만료 조건과 미승인 리뷰 후보를 배제 | `ScopedPreferenceTest`, `MetamorphicProbeTest` |
| CORE-2 현재 권위 | 정정·철회·삭제 우선, descendant 제거와 no-resurrection | `ReviewMemoryTest`, `ProbeOperationContractTest` |
| CORE-3 반복부담 감소 | 허용된 값만 자동 적용하고 불확실한 값의 확인은 유지 | `ClaimOffComparisonTest`, `OptionAvailabilityTest` |
| CORE-4 예외와 부분복구 | one-off 예외, 미제공 option과 품절의 field 단위 재개방 | `ProductFlowProbeTest`, `MultiLineDraftTest` |
| CORE-5 lifecycle | restart, duplicate, out-of-order와 exactly-once action | `MetamorphicProbeTest`, `ProbeOperationContractTest` |
| CORE-6 evidence 무결성 | delayed outcome, ledger, receipt와 화면·export의 동일 state | `ReviewMemoryTest`, `ProbeParityTest` |

공개 13-step fixture와 role-token 치환, entity-goal 교환, operation 순서 변형, 장기 반복 fixture는 동일
production core를 실행한다. 전 식당과 재고가 있는 모든 메뉴 조합도 동일한 주문서 불변조건을 통과한다.

## 10. Model과 runtime 경계

자연어 intake는 합성 catalog의 authored phrase를 longest-match 방식으로 해석하는 결정적 구현이다. 알 수
없는 표현은 의미를 추측하거나 값을 저장하지 않고 사용자가 선택할 수 있는 항목을 보여준다.

- 온디바이스 model: 없음
- 원격 backend와 endpoint: 없음
- inference와 retry: 0회
- runtime 데이터 전송: 없음

결정적 구현은 이 Mission의 좁고 합성된 주문 domain에서 판단 경계, 재현성, offline 동작과 paired
comparison을 명확하게 만든다. 새로운 식당·메뉴·표현은 core 분기 추가가 아니라 catalog data로 확장된다.
