# 개인 배달 주문 에이전트 — 제품 행동·UX 스토리보드

## 문서 상태

- 상태: `WORKING_DRAFT_NOT_MISSION_LOCKED`
- 작성일: `2026-07-29 KST`
- 구현 상태: 시작하지 않음
- 목적: Mission 선언 전에 제품 깊이, E1–E4 사용자 경험, 기억 권한, 화면 역할을 검토하기 위한 명세
- 공식 사실의 기준: `COMPETITION_CONTEXT.md`와 `release_v3/`

이 문서는 승인 전 작업가설이다. Dacon이 생성하는 `MISSION_LOCK.json`을 대신하지 않으며, 앱 구현을
승인하는 문서도 아니다.

---

## 1. 현재 제품 가설

### 대상 사용자

비슷한 취향으로 배달을 반복 주문하지만, 매번 달라지는 예산·시간·기분과 복잡한 메뉴 옵션 때문에
현재 주문안을 다시 구성해야 하는 개인 사용자.

연령이나 특정 취약계층으로 제한하지 않는다. 스마트폰 사용이 익숙하지 않은 사용자도 이득을 받지만,
Mission의 대상은 행동 특성으로 정의한다.

### 핵심 문제

기존 주문기록과 단순 재주문은 과거 주문 전체를 복사할 뿐이다. 사용자의 안정적인 취향, 오늘만의 선택,
과거 만족도, 현재 가격·재고, 자동 적용 권한을 구분하지 못한다. 그 결과:

- 새로운 식당이나 메뉴에서는 개인화가 다시 처음부터 시작된다.
- 과거 주문을 복사하면 현재 상황에 맞지 않는 옵션이 남을 수 있다.
- 사용자는 비슷한 옵션과 요청사항을 반복 입력한다.
- 과거의 낮은 만족도가 다음 추천과 옵션에 제대로 반영되지 않는다.
- 정정·철회·삭제 뒤에도 오래된 주문기록에서 같은 정보가 다시 추론될 위험이 있다.

### 장기 목표 작업가설

여러 주문 session과 앱 재실행을 거친 뒤에도 사용자의 현재 지시와 허용범위를 우선하면서, 현재도 유효한
취향과 과거 결과만 선택적으로 활용해 주문 초안 완성에 필요한 질문·탐색·옵션 입력을 줄인다.

### Primary value 작업가설

> 현재 상황과 취향에 맞는 개인 배달 주문안을 완성하는 데 필요한 사용자 부담 감소

추천 정확도, 옵션 자동 적용, 응답 속도는 이 Primary value를 만드는 수단 또는 측정지표다. 별개의
Primary value로 선언하지 않는다.

### 제품 한 문장

> 사용자의 명시적 취향·과거 만족도·오늘 상황·자동 적용 권한을 구분해 기억하고, 현재도 유효한 정보만
> 사용하여 필요한 질문이 최소화된 합성 배달 주문 초안을 만드는 대화형 모바일 에이전트

---

## 2. 제품 깊이와 범위

### 이번 Mission에 포함

1. 합성 식당·메뉴·옵션·가격·재고·예상시간 탐색
2. 자연어 또는 빠른 선택을 통한 오늘 상황 입력
3. 명시적 취향, 추론 취향, one-off 조건, 지연 만족도의 구분
4. 사용자가 허용한 저위험 옵션의 선택적 자동 적용
5. 메뉴 후보 2–3개와 간결한 추천 근거
6. 구조화된 주문 초안 생성·수정·최종 확인
7. 정정·철회·삭제와 자동 적용 권한 관리
8. 품절·가격변경·network 변화에 대한 재판단
9. 영향을 받은 항목만 다시 계산하는 부분복구
10. 앱/process 종료·재실행 뒤 정확한 상태복구와 중복 가상 주문 방지
11. virtual time 뒤 도착한 만족도·결과가 다음 session의 판단을 변경
12. 전체 Reset과 기억 조회·수정·삭제

### 이번 Mission에서 제외

- 실제 배달앱 계정·API·식당·메뉴·주소·결제수단
- 실제 주문·결제·취소·환불·메시지·전화
- 다른 앱이나 Android system의 privileged 제어
- 단체주문과 타인의 취향 profile
- 금융·의료 판단
- 여러 생활 domain으로의 확장
- 상담만 하고 구조화된 주문 초안을 만들지 않는 일반 chatbot
- 추천 결과를 설명 없이 자동 확정하는 흐름

### 권장 깊이

`추천 → 옵션 적용 → 주문 초안 → 지연된 만족도 학습 → 예외 재판단 → 부분복구 → 재실행 연속성`까지
하나의 production core에서 연결한다.

---

## 3. 대화형 UI의 역할

대화는 빠른 입력 수단이고, 진실의 원본은 구조화된 state다.

```text
사용자 대화·빠른 선택
  → 구조화된 현재 조건과 변경분
  → 기억 권한·유효성·현재 catalog 검증
  → 추천 후보
  → 구조화된 주문 초안
  → 사용자 확인
  → app-local 가상 action·outcome ledger
```

채팅 문장만 저장해 다음 판단의 원본으로 사용하지 않는다. 모든 의미 있는 변화는 사용자가 확인 가능한
구조화된 항목으로 표시한다.

### generic core와 배달 표현 계층

```text
Generic production core
  fact ID · opaque role value · authority · scope · lifetime
  dependency · invalidation · decision · ledger · reconciliation
                    │
                    ▼
Delivery presentation layer
  순한맛 · 수저 제외 · 소스 별도 포장 · 메뉴 · 가격 · 재고
```

Probe의 합성 role 값은 공개 예시와 다른 불투명 token일 수 있다. generic core는 특정 메뉴명이나
`PUBLIC_*` 문자열을 해석해 정답을 고르지 않고 ID·authority·scope·dependency 관계로 처리한다. 배달
표현 계층만 이 관계를 사용자에게 이해 가능한 메뉴·옵션·근거로 보여준다.

### 지원할 사용자 의도

- 현재 주문의 예산·시간·음식 성격 설정
- 현재 주문 session에만 적용할 선호 설정
- 안정적인 취향 저장
- 저장 취향 정정
- 자동 적용 권한 철회
- 저장 기억 삭제
- 메뉴 추천 요청
- 추천 후보 선택
- 주문 초안의 메뉴·옵션·수량 수정
- 최종 가상 주문 확인
- 만족도·결과 기록 또는 정정

### 모호성 처리

다음 상황에서는 추측해 확정하지 않고 질문한다.

- “가볍게”, “빨리”, “저렴하게”처럼 기준이 여러 가지인 표현
- 현재 menu option과 저장 취향의 mapping이 불확실한 경우
- 오늘만의 선택인지 안정 취향 변경인지 불명확한 경우
- 현재 가격·재고·예산이 동시에 충족되지 않는 경우
- 삭제·철회된 기억과 유사한 과거 기록만 남아 있는 경우

---

## 4. 기억 계약

### 기억 종류

| 종류 | 의미 | 자동 적용 |
|---|---|---|
| `EXPLICIT_STABLE` | 사용자가 앞으로도 유지한다고 명시한 취향 | 허용 scope·현재 catalog가 유효할 때만 가능 |
| `INFERRED_SOFT` | 주문 반복이나 만족도에서 추론한 약한 취향 | 추천 순위에만 사용, 자동 확정 금지 |
| `ONE_OFF_CURRENT` | 현재 주문 session에만 적용되는 조건 | 현재 session에서 최우선 적용 |
| `EPHEMERAL_REQUEST` | 현재 주문 초안에 쓰이는 삭제 가능한 일회성 요청사항 | 현재 초안에만 적용 |
| `EPHEMERAL_CATALOG` | 현재 가격·재고·예상시간 | 해당 version·session에서만 사용 |
| `DELAYED_OUTCOME` | virtual time 뒤 도착한 만족도·품절·지연 결과 | 다음 판단을 바꾸되 source와 scope 유지 |
| `PERMISSION_SCOPE` | 어떤 기억을 추천·자동 적용에 쓸 수 있는지 | 현재 사용자의 허용범위가 최종 권위 |
| `RAW_HISTORY` | 과거 주문과 action 기록 | 단독으로 안정 취향 확정 금지 |
| `TOMBSTONE` | 삭제된 원문이 다시 등장하지 않게 하는 최소 표식 | 원문·복원 가능한 값 저장 금지 |

### 권위 우선순위

1. 현재 session에서 사용자가 명시한 지시
2. 현재 정정·철회·삭제
3. 범위가 유효한 명시적 안정 취향
4. 범위가 유효한 지연 만족도
5. 추론 취향
6. 원시 주문기록

현재 사용자가 새 값을 명시하면 삭제된 과거 원문을 복원하는 것이 아니라 새로운 current fact로 취급한다.

### one-off 만료 규칙

- `ONE_OFF_CURRENT`는 만들어진 `session_id`에 귀속된다.
- 다른 `session_id`로 `ADVANCE_SESSION`하면 만료된다.
- `ADVANCE_TIME`만으로는 만료되지 않는다.
- 실제 시각 만료가 필요한 별도 값에만 명시적 `expires_at`을 둔다.

사용자 문장 “오늘만”은 이번 Mission에서 “현재 주문 session에만”으로 구조화해 확인한다.

### delayed outcome 전달 계약

- delayed outcome은 notification·다른 선택 permission보다 먼저 app-local ledger에 기록한다.
- permission 상태는 outcome의 저장 여부나 다음 판단 반영 여부를 바꾸지 않는다.
- notification은 outcome 도착을 사람이 보기 쉽게 만드는 부수 surface일 뿐 정본이 아니다.
- notification을 거부하거나 사용할 수 없으면 in-app outcome inbox와 다음 실행 banner로 보여준다.
- network failure는 permission fallback의 대안이 아니다. `SET_NETWORK`의 online·delayed·offline·unknown
  상태를 별도 필수 축으로 처리하고, 최신 catalog가 없으면 `WAIT` 또는 `ABSTAIN`한다.
- network·permission 상태가 바뀌어도 같은 outcome ID를 중복 반영하지 않는다.

### scope와 dependency 요약

- fact와 permission에는 하나 이상의 `scope_id`가 붙는다.
- 주문 초안의 각 field는 자신이 의존한 fact·permission·catalog version ID를 기록한다.
- 정정·철회·삭제·catalog 변화는 dependency가 닿은 field만 무효화한다.
- 가격이 있는 line이 바뀌면 해당 line과 총액은 다시 계산하지만 독립적인 메뉴·option은 보존한다.
- permission만 철회하면 취향 원문은 보존하고, 자동 적용된 미확인 field만 `확인 필요`로 낮춘다.
- 사용자가 별도로 명시한 현재 선택은 과거 기억에서 파생된 값과 구분해 기록한다.

상세 전파 규칙은 `PERSONAL_DELIVERY_AGENT_EVALUATION_STRATEGY.md`를 따른다.

### 자동 적용 가능 조건

다음을 모두 만족해야 한다.

1. 사용자가 직접 저장한 취향이다.
2. 자동 적용을 허용한 scope 안에 있다.
3. 현재 주문의 명시적 지시와 충돌하지 않는다.
4. 현재 메뉴 option에 의미가 명확하게 대응된다.
5. 현재 가격·재고·예상시간 version이 유효하다.
6. 최종 주문 초안에서 사용자가 적용 사실을 확인할 수 있다.

### 자동 적용하지 않는 항목

- 추론만으로 얻은 취향
- 현재 주문의 최종 메뉴·수량
- 합성 주소별칭
- 총액과 최종 가상 주문
- 의미 mapping이 불확실한 옵션
- 삭제·철회된 scope

---

## 5. 화면 구조

### S1. 오늘의 주문

목적: 오늘 상황을 가장 짧게 입력한다.

- 자연어 입력
- 예산·희망시간·음식 성격 빠른 선택
- 최근 주문 전체 복사가 아닌 “현재 조건으로 새 초안 만들기”
- 현재 network 상태와 사용할 수 있는 fallback 표시

### S2. 상황 이해 확인

목적: 에이전트가 해석한 현재 조건을 구조화해 보여준다.

- 오늘 예산
- 희망 예상시간
- 음식 성격
- 오늘만 적용할 조건
- 적용 후보인 저장 취향
- 수정 또는 “오늘만/앞으로도” 범위 선택

### S3. 추천 후보

목적: 선택 부담을 줄이되 사용자가 판단권을 가진다.

- 2–3개 후보
- 가격·현재 재고·합성 예상시간
- 간결한 근거 badge:
  - `오늘 입력`
  - `직접 저장`
  - `과거 평가`
  - `현재 메뉴정보`
- 후보 제외 사유가 필요할 때 간단히 표시
- 내부 chain-of-thought는 표시하지 않음

### S4. 주문 초안

목적: 메뉴·수량·옵션·총액의 authoritative state를 제공한다.

- 메뉴·수량
- 옵션별 적용값
- 옵션별 source badge
- 자동 적용된 항목의 수정·자동 적용 해제
- 현재 총액·재고·예상시간 재검증
- 최종 확인 전 상태는 `초안` 또는 `확인 필요`로 표시

### S5. 가상 주문 상태와 복구

목적: 완료·대기·실패를 정직하게 구분하고 재실행 뒤 이어간다.

- 합성 상태:
  - 초안
  - 확인 대기
  - 가상 제출
  - 가상 접수
  - 지연
  - 부분복구 필요
  - 가상 완료
  - 실패
- 중복 가상 action 방지를 위한 action ID·receipt 연결
- 재실행 후 보존된 항목과 다시 확인할 항목 표시

### S6. 내 취향과 기억

목적: 사용자가 장기기억의 권위자임을 보장한다.

- 직접 저장한 취향
- 추론 취향
- 만족도·결과
- 자동 적용 허용범위
- source·scope·마지막 갱신시점
- 수정·철회·삭제
- 전체 Reset

### S7. 평가·내보내기 진입

제품 production core와 같은 state·decision·ledger를 사용하는 별도 표준 진입점이다.

- public Probe import/run/export control
- 제품 UI와 다른 판단 logic을 두지 않음
- 구체 control과 evidence 화면은 Probe mapping 단계에서 확정

---

## 6. 공통 UX 원칙

- 화면마다 가장 중요한 primary action 한 개
- 대화 결과는 항상 구조화된 chip·card·draft로 확인
- 저장 또는 자동 적용 전에 scope를 명확히 표시
- 추론값과 사용자 명시값을 시각적으로 구분
- 현재 지시가 과거보다 우선한다는 결과를 화면에 반영
- 모호할 때는 자동 확정 대신 짧은 질문
- 사용자 수정은 한 화면 안에서 가능
- 삭제·철회는 숨은 설정이 아니라 기억 항목에서 직접 가능
- 재실행 뒤 “완료하지 않은 것을 완료”로 표시하지 않음
- 큰 tap target, 짧은 문장, 한 화면의 선택 수 제한
- 색상만으로 상태를 구분하지 않고 text·icon을 함께 사용

---

## 7. 4개 에피소드 상세 스토리보드

모든 이름·식당·메뉴·가격·주소·시간은 합성이다. 실제 기다림 대신 virtual time을 사용한다.

### 공통 합성 catalog

- 식당 A: `다온국밥 실험점`
  - `맑은 닭곰탕`
  - `얼큰 닭곰탕`
  - option: 맵기, 밥 양, 일회용 수저, 반찬
- 식당 B: `온기한상 실험점`
  - `들깨 수제비`
  - `순한 순두부`
  - option: 간 세기, 면·밥 양, 일회용 수저, 사이드
- 식당 C: `불꽃분식 실험점`
  - `매운 떡볶이`
  - `순한 떡볶이`
  - option: 맵기, 양, 토핑, 사이드, 일회용 수저

가격·재고·예상시간은 episode별 synthetic event로 바뀐다.

### E1 — Learn: 첫 주문과 기억 허용범위

#### 시작 상태

- 장기 취향 없음
- 과거 주문 없음
- 자동 적용 permission 없음
- 식당 A online

#### 사용자 입력

> “2만원 이하로 따뜻한 국물이 먹고 싶어. 맵지 않게 해줘.”

#### 앱의 구조화

- 예산: 20,000원 — `ONE_OFF_CURRENT`
- 음식 성격: 따뜻한 국물 — `ONE_OFF_CURRENT`
- 현재 맵기 요구: 순한맛 — 아직 안정 취향으로 확정하지 않음

#### 필요한 질문

1. “순한맛은 오늘만 적용할까요, 다음 주문에도 기억할까요?”
2. “일회용 수저는 받을까요?”
3. “밥 양은 보통으로 할까요, 적게 할까요?”
4. 자동 적용을 허용할 항목 확인

#### 사용자 결정

- 순한맛 선호를 `EXPLICIT_STABLE`로 저장
- 순한맛 option의 자동 적용 허용
- 일회용 수저 제외를 `EXPLICIT_STABLE`로 저장
- 일회용 수저 option의 자동 적용 허용
- 밥 양 `보통`을 `EXPLICIT_STABLE`로 저장하고 해당 option의 자동 적용 허용
- 예산과 음식 성격은 현재 주문 session에서만 사용

#### 추천과 초안

- 추천: `맑은 닭곰탕`
- 자동 적용:
  - 맵기 `순한맛`
  - 일회용 수저 `제외`
- 직접 확인:
  - 메뉴
  - 수량
  - 밥 양
  - 총액

#### action

- 사용자가 구조화된 초안을 확인
- 앱은 `가상 제출`과 `가상 접수` receipt를 기록

#### 지연 outcome

virtual time 뒤 사용자가 다음을 기록한다.

> “국물은 괜찮았지만 간이 조금 셌어.”

- `DELAYED_OUTCOME`
- scope: 국물 메뉴의 간 세기
- “모든 음식은 싱겁게”라는 안정 취향으로 과도하게 일반화하지 않음

#### 다음 session에 남는 것

- 순한맛 명시 취향과 자동 적용 permission
- 일회용 수저 제외 명시 취향과 자동 적용 permission
- 국물 메뉴의 간 세기에 대한 낮은 만족도
- E1 주문·action·outcome ledger

### Session boundary 1

정상적으로 앱을 닫고 다음 주문 session을 시작한다.

---

### E2 — Reuse: 새로운 식당에서 선택적 재사용

#### 시작 상태

- E1 기억과 outcome 존재
- 더 최근이지만 다른 식당·다른 목표에 속한 유사 distractor 기록 존재
- 식당 B라는 새로운 surface
- 예산·가격·재고는 새 version

#### 사용자 입력

> “오늘도 따뜻한 거. 1만8천원 이하이고 30분 안에 오는 걸로 추천해줘.”

#### 앱의 구조화

- 예산: 18,000원 — `ONE_OFF_CURRENT`
- 합성 예상시간: 30분 이하 — `ONE_OFF_CURRENT`
- 음식 성격: 따뜻한 음식 — `ONE_OFF_CURRENT`

#### 선택적 기억 사용

- `순한맛`은 식당 B의 메뉴·option 의미가 명확할 때만 적용
- `일회용 수저 제외` 자동 적용
- E1의 “국물 간이 셌다” outcome은 간 세기 option과 추천 순위에만 사용
- E1의 식당 A entity와 현재 무관한 option 이름은 그대로 복사하지 않음
- 더 최근인 distractor도 현재 target·goal과 다르면 선택하지 않음

#### 추천 후보

1. `들깨 수제비` — 간 약하게 적용 가능
2. `순한 순두부` — 현재 순한맛 조건 충족

각 후보에 사용한 근거 badge를 표시한다.

#### 주문 초안

사용자가 `들깨 수제비`를 선택한다.

- 간 세기: 약하게 — 과거 outcome을 근거로 제안, 사용자가 확인
- 일회용 수저: 제외 — 명시 취향과 permission으로 자동 적용
- 수량·총액: 직접 확인

#### E2에서 줄어드는 부담

- 순한맛을 다시 설명하지 않음
- 일회용 수저를 다시 선택하지 않음
- 과거 outcome을 반영한 간 세기 후보를 먼저 제안
- 현재 예산·시간과 메뉴 선택만 집중 확인

#### action

- 가상 주문 초안 확인·가상 접수
- 화면·persisted state·receipt에 같은 옵션과 총액 기록

#### 다음 session에 미치는 변화

- E2의 명시적 선택과 결과는 식당 B history로 추가
- E1의 취향은 덮어쓰지 않고 유지
- 반복 주문 부담 감소 evidence 생성 가능

### Session boundary 2

정상적으로 앱을 닫고 다음 주문 session을 시작한다.

---

### E3 — Exception: 오늘의 예외와 권한 철회

#### 시작 상태

- 안정 취향: 순한맛
- 자동 적용 permission: 순한맛, 일회용 수저 제외
- E1–E2 history와 outcome 존재
- 식당 C의 현재 catalog 사용

#### 사용자 입력

> “이번 주문에만 아주 매운 떡볶이가 먹고 싶어.”

#### 충돌

- 현재 지시 `아주 매운맛`
- 저장된 안정 취향 `순한맛`

앱은 과거 취향을 적용하지 않고 현재 지시를 우선한다. 다만 다음 주문까지 영구 변경하지 않는다.

#### 필요한 확인

> “아주 매운맛은 이번 주문 session에만 적용하고, 저장된 순한맛 취향은 유지할게요.”

사용자는 구조화된 scope를 확인한다.

#### 권한 철회

사용자가 이어서 입력한다.

> “일회용 수저는 앞으로 자동으로 정하지 말아줘.”

앱의 처리:

- `일회용 수저 제외` 원문 취향 자체를 삭제하는 것이 아님
- `AUTO_APPLY_UTENSIL` permission scope를 철회
- 현재 주문 초안과 이후 주문에서 자동 적용 중단
- 현재 주문에서는 일회용 수저를 다시 질문

#### 저장 fact 정정

사용자가 E1의 future reuse용 밥 양 `보통` 취향을 `적게`로 더 최신 지시로 정정한다.

- 해당 fact의 `authority_version` 증가
- 오래된 fact와 그 fact에 의존하는 현재 미확정 field·provenance만 무효화
- 이미 확인 완료된 과거 주문 기록은 불변 ledger로 보존
- 순한맛·수저 취향과 다른 scope의 permission·현재 선택은 보존

#### 재계획

- 맵기: 현재 주문 session만 아주 매운맛
- 일회용 수저: 확인 필요
- 기존 안정 취향과 다른 무관한 기억은 유지
- 과거 순한맛 취향을 전체 Reset하지 않음

#### action

- 사용자가 현재 주문에서만 일회용 수저 `필요`를 선택
- 구조화된 가상 주문 초안 확인

#### 다음 session에 남는 것

- 순한맛 안정 취향은 여전히 존재
- “이번 주문에만 아주 매운맛”은 새 session으로 이동하면 재사용하지 않음
- 일회용 수저 자동 적용 permission은 철회된 상태
- 오래된 자동 적용 event가 재전달되어도 철회 상태를 뒤집지 않음

### Session boundary 3

정상적으로 앱을 닫고 다음 주문 session을 시작한다.

---

### E4 — Recover: 품절·process kill·부분복구

#### 시작 상태

- 순한맛 안정 취향 존재
- 정정된 밥 양 `적게`가 current authority
- 일회용 수저 자동 적용 permission 철회 상태
- E3 one-off 아주 매운맛은 만료
- 식당 B의 새로운 catalog version

#### 사용자 입력

> “지난번처럼 따뜻한 걸로 추천해줘. 사이드도 하나 넣고 싶어.”

#### 앱의 초기 초안

- 본 메뉴: `들깨 수제비`
- 밥 양: `적게` 자동 적용
- 간 세기: 약하게 제안
- 일회용 수저: permission이 철회되었으므로 확인 필요
- 사이드: `바삭 만두`
- 합성 일회성 요청 메모: `소스 별도 포장`

#### synthetic catalog event

초안 생성 뒤 더 높은 catalog version의 `바삭 만두 품절` event가 도착한다. 제품 화면에서는
virtual-time queue가 event를 전달할 수 있고, Probe에서는 `UPSERT_FACT`로 새 stock fact를 넣거나
`ADVANCE_TIME`으로 예약된 event를 도착시킬 수 있다. core는 특정 operation 위치가 아니라 event ID·
catalog version·dependency로 같은 의미를 처리한다.

#### 부분 무효화

무효화:

- `바삭 만두` line
- 그 line에 종속된 가격·재고·총액

보존:

- 본 메뉴
- 간 세기
- 수량
- 현재 사용자 조건
- 사용자가 이미 확인한 본 메뉴 선택

앱은 전체 주문을 버리지 않고 사이드 대체만 요청한다.

#### 합성 요청 메모 삭제

사용자가 현재 초안의 `소스 별도 포장` 메모를 삭제한다.

- 원문과 그 메모에 의존한 draft field·provenance 제거
- 복원 가능한 원문 없이 최소 tombstone만 보존
- 본 메뉴·option·side recovery state는 보존
- 이후 process relaunch와 out-of-order event 뒤에도 메모가 부활하거나 판단에 재사용되지 않음

#### 실제 process kill

사용자가 대체 사이드 선택 화면을 보는 중 Runner 또는 OS가 process를 종료한다.

checkpoint:

- 본 메뉴 확인 완료
- 사이드 품절 반영 완료
- 대체 사이드 미확정
- 가상 주문 action 미실행

#### relaunch

앱 재실행 뒤:

- 본 메뉴와 유효한 옵션 복구
- 품절 사이드는 부활하지 않음
- 대체 사이드만 다시 선택
- 미실행 action을 완료로 표시하지 않음
- 같은 action ID로 가상 주문을 중복 생성하지 않음

#### stale/out-of-order event

오래된 catalog version의 “바삭 만두 재고 있음” event가 뒤늦게 도착해도 현재 품절 version보다 우선하지
않는다.

#### 최종 action

- 사용자가 대체 사이드를 선택
- 현재 총액·재고 재검증
- 최종 초안 확인
- 가상 제출·접수 receipt를 한 번만 생성

#### E4 결과

- 영향받은 사이드 branch만 복구
- 이전에 유효하게 확인된 본 메뉴와 옵션 보존
- lifecycle 뒤 state·화면·receipt 일치

---

## 8. 핵심 인과변화

| 원인 | 뒤 episode의 실제 변화 |
|---|---|
| E1에서 순한맛을 안정 취향으로 저장하고 자동 적용 허용 | E2에서 순한 메뉴·option mapping이 적용되고 맵기 질문이 줄어듦 |
| E1에서 국물 간이 셌다는 지연 만족도 기록 | E2에서 간이 약한 후보가 상위에 오르고 간 세기 option이 달라짐 |
| E1에서 일회용 수저 제외 자동 적용 허용 | E2에서 해당 option을 질문하지 않고 초안에 적용 |
| E3에서 자동 적용 permission 철회 | E4에서 취향 원문은 남아 있어도 일회용 수저를 다시 질문 |
| E3에서 현재 session만 아주 매운맛을 지정 | E3 초안만 변경되고 E4에서는 순한맛 안정 취향으로 복귀 |

공식 최소 인과변화의 대표 두 지점은 `(1) E1 stable 취향·permission → E2 질문 감소`와
`(2) E3 permission 철회 → E4 재질문`으로 둔다. E1 delayed outcome은 세 번째 장기 변화 증거다.
E4 품절에 따른 side 부분복구는 필수 Recover 증거이지만, 앞 episode의 선택에서 생긴 변화 두 지점 중
하나로 계산하지 않는다.

---

## 9. 상태와 action 표현 원칙

사용자 화면과 evidence에서 다음을 혼동하지 않는다.

| 상태 | 사용자 의미 |
|---|---|
| `DRAFT` | 에이전트가 만든 수정 가능한 주문 초안 |
| `WAITING_FOR_USER` | 모호성·권한·현재 조건 확인 필요 |
| `PROPOSED` | 현재 catalog 검증을 통과한 제안 |
| `LOCALLY_CONFIRMED` | 사용자가 합성 초안을 확인 |
| `SIMULATED_SUBMITTED` | app-local 가상 제출 event 기록 |
| `SIMULATED_ACCEPTED` | 합성 식당의 가상 접수 outcome 도착 |
| `RECOVERY_REQUIRED` | 일부 항목만 다시 확인 필요 |
| `SIMULATED_FAILED` | 가상 action 또는 outcome 실패 |

명칭은 구현 전 schema 단계에서 공식 contract와 충돌하지 않도록 다시 확정한다.

---

## 10. 측정 후보

### Primary value 직접 측정

- 주문 초안 완성까지 필요한 사용자 질문 수
- 주문 초안 완성까지 필요한 tap·수정 수
- 주문 초안 완성시간

### 안전·정확성

- 현재 지시와 충돌하는 과거 취향 재사용 횟수
- 추론 취향을 사용자 허용 없이 자동 적용한 횟수
- 철회·삭제된 scope의 재사용 횟수
- 현재 catalog와 맞지 않는 option 적용 횟수

### 예외·복구

- 변화에 영향받지 않았지만 함께 무효화된 항목 수
- 품절 뒤 다시 확인해야 하는 항목 수
- relaunch 뒤 보존된 유효 항목 수
- 중복 가상 action 수

정확한 목표 수치와 full/claim-off paired metric은 Signature mechanism 단계에서 확정한다.

---

## 11. UI가 보여줘야 할 근거

사용자에게는 내부 사고과정이 아니라 결정에 필요한 provenance만 보여준다.

예:

- `순한맛 — 직접 저장, 자동 적용 허용`
- `간 약하게 — 지난 국물 평가 반영, 확인 필요`
- `18,000원 이하 — 오늘 입력`
- `28분 — 현재 합성 메뉴정보`
- `일회용 수저 — 자동 적용 권한 철회, 다시 확인`

모든 badge는 해당 기억을 수정·철회·삭제하거나 현재 주문에서만 override할 수 있는 화면으로 이어져야 한다.

---

## 12. 이 단계의 미확정 항목

다음은 아직 Mission으로 잠그지 않았다.

1. 대상 사용자 문장의 최종 범위
2. 핵심 문제·장기 목표·Primary value의 최종 문구
3. E1–E4에 사용할 메뉴·event의 최종 선택
4. mobile constraint와 PC counterfactual
5. Signature mechanism의 최종 명칭·claim
6. claim-off baseline
7. paired comparison metric과 목표값
8. 13 operation과 10 semantic role의 mapping
9. 자연어 처리에 model을 사용할지 deterministic parser를 사용할지
10. 저해상도 wireframe과 navigation

---

## 13. 다음 Gate

다음 단계에서 수행한다.

Mission·Signature·비교지표·Probe mapping의 검토안은
`PERSONAL_DELIVERY_AGENT_EVALUATION_STRATEGY.md`에 작성한다. 두 문서를 함께 검토해 다음을 승인한다.

1. 이 스토리보드의 제품 타당성과 과도한 범위
2. E1–E4 핵심 인과관계
3. Signature mechanism과 claim-off 경계
4. paired metric
5. mobile constraint
6. Probe operation·semantic role mapping

승인 뒤 1쪽 Mission 선언과 저해상도 화면 흐름을 작성한다.

이 Gate와 사용자 승인이 끝나기 전에는 Android 제품 구현을 시작하지 않는다.
