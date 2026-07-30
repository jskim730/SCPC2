# 개인 배달 주문 에이전트 — Mission·Signature·평가 설계안

## 문서 상태

- 상태: `MISSION_SUBMITTED_USER_FROZEN_PENDING_OFFICIAL_LOCK`
- 작성일: `2026-07-29 KST`
- 구현 상태: 시작하지 않음
- 선행 문서: `PERSONAL_DELIVERY_AGENT_UX_SPEC.md`
- 공식 기준: `COMPETITION_CONTEXT.md`, `release_v3/candidate_kit/`

Mission 선언은 `2026-07-31 KST`에 제출되었으며 사용자가 최종본으로 확정했다. 동결된 내용의 기준은
`SCPC2026_R2_MISSION_First_penguin.pdf`이고, 이 문서는 구현·평가 설계를 구체화하는
협업 문서다. Dacon이 제공하는 `MISSION_LOCK.json`을 수령하면 제출 PDF와 대조하고 공식 동결
정보에는 그 파일을 따른다.

---

## 1. 이번 단계의 결론

### 권장 제품 정의

> 비슷한 취향으로 배달을 반복 주문하지만 매번 달라지는 예산·시간·기분·메뉴 상태 때문에 주문안을 다시
> 구성해야 하는 개인 사용자를 위해, 현재도 유효하고 사용이 허용된 취향만 새 메뉴 옵션에 적용하여
> 최소한의 확인으로 합성 주문 초안을 완성하게 돕는 대화형 모바일 에이전트.

### 권장 Primary value

> **현재 상황과 취향에 맞는 유효한 개인 주문 초안을 완성하는 데 필요한 사용자 결정 부담 감소**

`유효한`을 반드시 포함한다. 질문 수만 줄이고 현재 지시·철회·재고를 어긴 초안을 만들면 Primary value를
달성한 것이 아니다.

### 권장 장기 목표

> 여러 주문 session·예외·지연 결과·앱 재실행을 거친 뒤에도 현재 지시와 사용자 허용범위를 우선하고,
> 현재도 유효한 취향과 결과만 선택적으로 재사용하여 주문 초안 완성에 필요한 질문·재입력·재확인을
> 지속적으로 줄인다.

### generic core와 배달 표현 계층의 경계

ASPR의 generic core는 `fact ID`, 불투명 `role value`, `authority version`, `scope ID`, `lifetime`,
`dependency`, `event version`만으로 판단한다. 공개 rehearsal의 문자열이나 특정 메뉴명을 보고 분기하지
않는다.

배달 표현 계층은 generic state를 순한맛·수저·메뉴·가격·재고·사용자 설명 badge로 바꾼다. 일반 제품
화면, public Probe UI, protected Probe callback은 서로 다른 진입점이지만 같은 generic repository·
decision engine·ledger를 호출한다.

---

## 2. Signature mechanism 권장안

### 명칭

**ASPR — Authority-Scoped Preference Reconciler**

한글 설명:

> **권위·범위 기반 취향 컴파일 및 부분조정기**

Mission PDF에서는 약어보다 한글 설명을 먼저 쓴다.

### 해결하려는 기술 문제

배달 기록 전체나 “최근 주문”을 복사하면 다음을 구분하기 어렵다.

- 앞으로도 유지할 안정 취향과 오늘만의 선택
- 사용자가 직접 말한 사실과 약하게 추론한 선호
- 추천에만 써도 되는 정보와 자동 적용까지 허용된 정보
- 다른 식당에서도 의미가 유지되는 취향과 특정 메뉴에만 유효한 옵션
- 현재 정정·철회·삭제로 무효화된 과거 정보
- 주문안 중 변경에 영향을 받은 항목과 그대로 보존할 항목

### mechanism의 입력

각 기억과 현재 event를 다음 속성이 있는 typed fact로 받는다.

- `source`: 사용자 직접 입력, 지연 만족도, catalog event, 과거 action
- `authority_version`: 현재성이 높은 지시가 오래된 지시를 이기게 하는 버전
- `scope_ids`: fact가 영향을 줄 수 있는 불투명 scope ID 집합
- `lifetime`: stable, current-session, catalog-version, deleted
- `permission`: rank-only, ask-before-apply, auto-apply
- `confidence`: explicit 또는 inferred

### mechanism의 처리

```text
현재 주문 목표·catalog
  + typed preference facts
  + 현재 authority·permission
          │
          ▼
1. 현재 목표와 무관한 기억 제외
2. current > correction/revoke/delete > 식당·메뉴별 stable > 메뉴 유형 stable > 전역 stable > outcome > inferred 순으로 활성 fact 선택
3. 활성 fact를 현재 식당의 option 의미에 대응
4. 확실한 값은 적용하고, 모호하거나 권한이 부족한 값은 ASK
5. 각 주문 필드에 사용된 fact·catalog version의 dependency 기록
6. 변화가 오면 dependency가 닿은 필드만 무효화·재계산하고 나머지는 보존
```

### mechanism의 출력

- 활성 취향과 제외된 취향 ID
- 현재 메뉴 option에 대한 적용·질문·보류 결정
- 주문 필드별 provenance와 dependency
- 변경 뒤 invalidated field와 preserved field
- 사용자에게 다시 물어야 할 최소 확인 목록

### scope의 데이터 단위

`scope_id`는 Probe에서 받은 불투명 token을 그대로 식별자로 사용할 수 있어야 한다. 제품 UI가 scope를
만들 때는 다음 의미 tuple을 별도 metadata로 보관한다.

```text
ScopeKey(
  subject,
  domain,        // preference | permission | request | catalog
  entity_class,  // food-category | option-semantic | restaurant | menu
  scope_level,   // GLOBAL_DEFAULT | MENU_TYPE | RESTAURANT_MENU_OVERRIDE
  menu_type_id,  // MENU_TYPE에서 쓰는 catalog-authored semantic ID
  entity_ids,    // RESTAURANT_MENU_OVERRIDE의 정확한 (restaurant_id, menu_id)
  slot,          // spiciness | utensil | saltiness | side | ...
  usage,         // rank | ask-before-apply | auto-apply
  lifetime       // stable | session | catalog-version | deleted
)
```

core는 문자열 `AUTO_APPLY_UTENSIL`처럼 공개 예시의 뜻을 추측하지 않는다. preceding fact가 어떤
`scope_id`에 속했는지와 revoke·preserve 입력이 가리키는 ID 관계만 처리한다.

한 주문은 한 명의 사용자와 한 식당에 귀속되며 하나 이상의 주문 항목을 가질 수 있다. 초안의 항목
주소는 다음처럼 취향 저장 key와 분리한다.

```text
OrderDraft(
  order_id,
  restaurant_id,
  items: [
    OrderLine(
      line_item_id,
      menu_id,
      menu_type_id,
      quantity,
      option_fields
    )
  ],
  order_level_fields,
  total
)
```

`line_item_id`는 같은 메뉴를 옵션만 다르게 여러 번 담아도 각 항목의 현재 선택과 dependency를 구분하는
초안 내부 ID다. 장기 취향은 `line_item_id`에 저장하지 않고, 아래 세 scope level의
`(slot_id, menu_type_id?, restaurant_id?, menu_id?)` key에 저장한다. 단체주문·타인 profile·다중 식당
장바구니는 이 Mission의 범위가 아니다.

명시적 안정 취향에는 `GLOBAL_DEFAULT`, `MENU_TYPE`, `RESTAURANT_MENU_OVERRIDE` 세 specificity
level을 둔다. 주문 초안은 현재 사용자의 직접 지시를 먼저 적용하고, 그다음 정확히 일치하는
`(restaurant_id, menu_id, slot_id)` override, catalog-authored `(menu_type_id, slot_id)` 취향, 같은
slot의 전역 기본 취향 순으로 적용한다. 메뉴 이름 문자열이나 fuzzy matching만으로 `MENU_TYPE`을
동일시하지 않는다. 리뷰에서 얻은 문구는 바로 stable fact가 되지 않으며, 사용자가 적용 범위와 자동
적용을 명시적으로 승인한 경우에만 선택한 scope level의 stable fact로 승격한다.

### draft dependency

각 주문 field는 `line_item_id`를 포함한 주소를 가지며 다음 dependency edge를 가진다. 예산·희망시간
같은 현재 조건과 최종 총액은 주문 전체 field로 둔다.

- `HARD_VALUE`: 값이 그 fact에 의존하며 fact 무효화 시 field도 무효화
- `RANKING`: 추천 순위 근거이며 무효화 시 점수·근거만 재계산
- `PERMISSION`: 자동 적용 권한이며 철회 시 미확인 field를 `ASK`로 낮춤
- `CATALOG`: 가격·재고·예상시간 version이며 stale 또는 변경 시 관련 line 재검증
- `TOTAL`: 가격이 있는 line의 descendant로 총액 재계산
- `CONFIRMATION`: 사용자가 현재 session에서 별도로 확인한 현재 권위

### descendant invalidation 규칙

| 변화 | 무효화 | 보존 |
|---|---|---|
| future reuse stable fact 정정 | 이전 fact와 그 fact에 의존하는 현재 미확정 `HARD_VALUE`·`RANKING` descendant | 이미 완료된 과거 주문 ledger, 다른 scope의 메뉴·option |
| permission revoke | 해당 `PERMISSION` edge와 미확인 자동 적용 상태 | 취향 fact 원문, 다른 permission, 별도 사용자 확인값 |
| ephemeral fact 삭제 | 원문과 그 fact에만 의존한 draft field·provenance | 독립적인 메뉴·option·총액 구성요소 |
| 한 주문 항목의 stock/version 변경 | 해당 `line_item_id`의 메뉴·가격·영향받은 option과 descendant인 총액 | 다른 주문 항목·현재 사용자 조건 |
| 한 주문 항목 삭제 | 해당 `line_item_id`의 field·가격·provenance와 descendant인 총액 | 다른 주문 항목·장기 취향 |
| catalog 전체 stale | catalog-dependent commit 가능 상태 | 입력·기억·이미 만든 draft와 provenance |
| current explicit correction | 충돌하는 과거 fact와 같은 scope의 draft descendant | 충돌하지 않는 stable fact와 다른 scope |

삭제 UI는 `저장 기억만 삭제`와 `현재 초안에도 반영`의 scope를 명시한다. Probe의 `DELETE_FACT`는 지정
ephemeral fact와 그 descendant를 제거하고, 복원 가능한 원문 없이 tombstone만 남긴다.

### one-off expiry

- `ONE_OFF_VALUE`는 생성된 `session_id`에 귀속된다.
- 새 `session_id`의 `ADVANCE_SESSION`에서 만료된다.
- `ADVANCE_TIME`만으로는 만료되지 않는다.
- 명시적 `expires_at`이 있는 별도 fact만 virtual time으로 만료한다.

### 이 mechanism에 포함하지 않는 것

다음은 `full`과 `claim-off`가 공통으로 사용한다. Signature의 공로로 주장하지 않는다.

- 채팅 UI와 자연어 parser/model
- 합성 식당·메뉴 catalog
- 기본 추천 점수와 현재 session 입력
- 최종 주문 확인과 app-local simulation
- 영속 저장, tombstone, process restart, idempotency ledger
- Probe adapter와 evidence export
- 실제 외부행동 금지, 안전 상태표시

이 경계를 지켜야 `claim-off`도 정상적인 제품이고, 관찰된 차이를 ASPR의 인과효과라고 설명할 수 있다.

### 예상 효과 claim

> 같은 현재 요청·catalog·과거 기록에서 ASPR을 켜면 최근 주문 복사 방식보다 현재 유효한 취향만 새
> 옵션에 재사용하고, 예외·품절 뒤 영향을 받은 필드만 다시 확인하므로 안전조건을 위반하지 않으면서
> 유효한 주문 초안까지 필요한 사용자 결정 수가 감소한다.

---

## 3. 현실적인 `claim-off` 비교군

### 비교군 이름

**Recent-order baseline — 최근 주문 스냅샷 재사용**

구조적 정의:

> fact store가 key별 최신값으로 축약되고 scope·lifetime·permission typing과 draft dependency edge가
> 없는 상태.

### 비교군이 할 수 있는 것

- 같은 채팅·화면·parser/model·catalog·추천 점수를 사용한다.
- 현재 session에서 사용자가 말한 조건은 정상 반영한다.
- 최근 주문을 사용자가 볼 수 있고 “다시 주문” 출발점으로 쓸 수 있다.
- 최종 확인, 삭제, 재시작, idempotency, 안전 상태표시는 그대로 동작한다.
- 명확하지 않은 값은 안전하게 질문한다.

### ASPR을 끄면 달라지는 것

- stable·one-off·outcome·permission을 조합해 새 option으로 컴파일하지 않는다.
- 다른 식당·새 option 표현에서는 과거 값을 자동 대응하지 않고 다시 질문한다.
- 최근 주문의 한 항목이 현재 지시와 충돌하면 해당 snapshot을 안전한 기본 초안으로 다시 구성한다.
- 품절처럼 의존관계를 정확히 알 수 없는 변화가 오면 전체 주문 초안을 다시 검토하게 한다.

### 공정한 비교를 위해 고정할 것

- 동일 APK와 source snapshot
- 동일한 합성 사용자 입력·catalog·event 순서·virtual time
- 동일한 model 또는 deterministic parser, seed, network, quota
- 동일한 UI와 최종 확인
- `full`과 `claim-off`의 완전히 분리된 state namespace
- 각 arm의 별도 result·receipt·evidence

`full`의 저장·삭제·Reset이 `claim-off`에 보이거나 그 반대가 일어나면 비교는 무효다.

### arm 선택

comparison은 AUTO-CHECK와 별도다. Dacon OPS·Judge가 볼 수 있는 comparison 시작 화면에서 `full` 또는
`claim-off`를 명시적으로 선택하고, 선택값·snapshot ID·namespace ID를 run metadata와 receipt에 남긴다.
두 arm은 같은 snapshot bytes를 각자의 저장공간에 복제한다.

`probe_pack_id`, `surface_nonce`, public/official 여부나 특정 입력 문자열을 보고 arm을 자동 선택하지 않는다.
정확한 화면 위치와 두 arm 실행 절차는 `INSTALL_AND_USE_GUIDE.pdf`에 기록한다.

---

## 4. 비교 metric

### Primary paired metric

**VIL — Valid Interaction Load**

> 최초 주문 요청 뒤 `LOCALLY_CONFIRMED`인 유효한 주문 초안이 될 때까지 사용자가 추가로 확인·입력해야
> 하는 field-resolution event의 누적 수.

다음 각각을 1 field-resolution event로 센다.

- clarification에 대한 사용자 응답
- 주문 항목별 메뉴·옵션·수량을 직접 선택하거나 다시 선택
- 잘못 적용된 값을 정정
- 변화 뒤 유효했지만 사라진 항목을 다시 입력
- 최종 주문 초안 확인

초기 자연어 요청은 양쪽에 동일하게 주어지므로 세지 않는다. 화면을 읽는 행위, 에이전트 내부 계산,
자동 적용 항목의 provenance 표시는 세지 않는다. 최종 확인은 양쪽 모두 반드시 1회 센다. 각 event는
`open_confirmation_ids`와 사용자 응답 fact를 연결한 decision ledger에서 파생한다.

여러 메뉴가 있는 비교에서는 `full`과 `claim-off`에 동일한 식당·동일한 주문 항목 목록·동일한
`line_item_id`·동일한 현재 지시를 제공한다. 항목 수가 늘어난 자체를 ASPR의 이득으로 세지 않고, 각
항목에서 기억 재사용 또는 부분복구로 줄어든 field-resolution event만 비교한다.

### 유효성 guardrail

다음 중 하나라도 발생한 run은 낮은 VIL을 성과로 주장하지 않는다.

- 현재 명시 지시 위반
- 철회·삭제된 scope 재사용
- 추론 취향의 무허가 자동 적용
- 현재 catalog에 없는 option 적용
- 사용자가 확인하지 않은 최종 가상 주문
- 같은 action의 중복 반영
- 재실행 뒤 삭제값 부활

### 권장 비교 목표

- E2 새 식당 재사용: `full`의 VIL이 `claim-off`보다 **35% 이상 낮음**
- E4 부분복구: `full`의 VIL이 `claim-off`보다 **35% 이상 낮음**
- 위 두 구간 모두 유효성 guardrail 위반 **0**

수치는 구현 전 공개 rehearsal의 난이도를 보며 조정할 수 있지만, Mission의 Primary value와 방향은
바꾸지 않는다.

### 보조 metric

| metric | 정의 | 기대 방향 |
|---|---|---|
| `SPR` | 변화와 무관해 그대로 유지된 확인완료 필드 / 유지 가능했던 확인완료 필드 | 높을수록 좋음 |
| `invalid_reuse_count` | 현재 지시·scope·catalog와 충돌하는 과거값 재사용 수 | 0 |
| `required_reentry_count` | 이미 유효했던 값을 사용자가 다시 입력한 수 | 낮을수록 좋음 |
| `duplicate_action_count` | 같은 가상 action이 두 번 commit된 수 | 0 |
| `resurrected_fact_count` | 삭제·철회 뒤 다시 사용된 과거 fact 수 | 0 |

### late-horizon·unseen surface 확인

- late-horizon: E1의 지연 만족도가 여러 session 뒤 새 식당 추천과 질문을 바꾸는지 확인
- unseen surface: 같은 “순한맛” 취향을 `맵기 0단계`, `안매움`, `mild`처럼 공개 시나리오와 다른 합성
  option 표현에 대응
- trade-off: 의미 대응이 불확실하면 VIL을 억지로 줄이지 않고 ASK로 전환

---

## 5. E1–E4 Mission 압축안

### E1 — Learn

사용자는 “2만원 이하, 따뜻한 국물, 순한맛”이라는 현재 목표로 첫 합성 주문 초안을 만든다. 맵기와
수저·밥 양처럼 반복 사용할 항목은 추측하지 않고 질문한다. 답을 확인한 뒤 순한맛·일회용 수저 제외는
전역 기본 취향으로, 밥 양 보통은 catalog의 국물 `menu_type_id` 취향으로 저장하되 각각의 재사용·자동
적용 범위를 확인한다. 주문 뒤 virtual
time에 평가 요청이 도착하고, “이 식당의 이 메뉴는 간이 너무 셌다”는 리뷰에서 `간 약하게` 후보를
찾는다. 사용자가 해당 식당·메뉴에 다음부터 자동 적용하도록 동의한 경우에만 scoped stable
override로 승격한다.

### E2 — Reuse

새 식당·새 option 표현과 “1만8천원 이하, 30분 이내”라는 현재 조건에서는 E1의 전역 기본 취향·수저
권한과, catalog의 `menu_type_id`가 같을 때 밥 양 보통 취향을 선택적으로 재사용한다. E1의 식당·메뉴별
`간 약하게` override는 대상 쌍이 다르므로 제외한다.
더 최근이더라도 다른 식당·목표의 distractor는 선택하지 않는다. 주문 뒤 지연 평가 요청에서 식당 B의
선택 메뉴에 대한 옵션 리뷰를 남기고, 사용자가 범위와 자동 적용을 승인하면 식당 B·선택 메뉴 scoped
override를 만든다. 이때 질문·재입력이 E1과 `claim-off`보다 줄어든다.

### E3 — Exception

사용자는 “이번 주문에만 아주 매운 떡볶이”를 지정한다. one-off 값이 stable 순한맛보다 현재 session에서
우선하지만 stable 사실 자체는 변하지 않는다. 동시에 일회용 수저 제외의 자동 적용 권한만 철회한다.
취향 원문과 다른 자동 적용 scope는 보존한다. E1의 future reuse용 밥 양 `보통` 취향을 `적게`로
정정하면 authority version을 올리고 그 fact에 의존하는 현재 미확정 field만 무효화하며, 이미 완료된
과거 주문 ledger와 순한맛·수저 취향은 보존한다.

### E4 — Recover

다음 session에서 E3의 one-off가 만료되어 stable 순한맛으로 돌아오고, 정정된 밥 양 `적게`가 current
authority로 적용된다. 본 메뉴와 사이드를 포함한 주문안에서 사이드만 품절되면 그 branch와 총액만
무효화하고 본 메뉴·유효한 option은 보존한다. 대체
사이드 선택 중 실제 process kill·relaunch가 발생해도 checkpoint로 돌아오며, duplicate action과 오래된
재고 event를 반영하지 않는다. 초안에 쓰인 합성 일회성 요청 메모를 삭제하면 원문·파생 field를 제거하고
tombstone만 남겨 재실행 뒤에도 부활하지 않게 한다. E3에서 자동 적용 권한을 철회했으므로 수저 여부는
다시 묻는다.

### 뒤 episode를 실제로 바꾸는 원인

1. E1의 전역 순한맛·메뉴 유형 밥 양과 permission → E2 새 식당의 option 대응과 질문 감소
2. E2 리뷰에서 승인한 식당·메뉴별 override → E4의 같은 쌍 초안에서 전역 기본값을 재정의
3. E3의 one-off → E3만 아주 매움, E4에서는 stable 순한맛 복귀
4. E3의 utensil permission revoke → E4에서 수저를 다시 질문

공식 최소 인과변화의 대표 두 지점은 1과 4로 둔다. 2는 장기 후반 변화의 추가 증거다. E4의 side stock
event로 side branch만 재계산하고 main branch를 보존하는 관계는 Recover의 핵심 증거이지만 앞 episode
선택의 downstream change 개수로 계산하지 않는다.

---

## 6. CORE-1…6 연결

| CORE | 제품에서 확인할 관계 |
|---|---|
| CORE-1 선택적 맥락 | 현재 식당·목표·음식군에 맞는 취향만 활성화하고, 과거 다른 식당 option·E3 one-off를 distractor로 제외 |
| CORE-2 현재 권위 | 현재 지시·정정·permission revoke·delete가 파생 option과 자동 적용 결정을 필요한 범위까지만 무효화 |
| CORE-3 반복부담 | E2의 VIL·질문·재입력이 줄되 최종 확인과 불확실 option 질문은 유지 |
| CORE-4 예외·회복 | option 의미가 모호하면 ASK, catalog가 지연되면 WAIT, 안전한 결정이 없으면 ABSTAIN, 품절이면 side만 replan |
| CORE-5 restart reconciliation | 제안과 commit 사이 실제 kill 뒤 pending 상태 복원, duplicate action 방지, stale stock event 무시 |
| CORE-6 delayed outcome·evidence | E2 지연 평가 요청과 승인된 scoped override가 E4 판단을 바꾸고 fact→decision→action→outcome ledger가 화면·export와 일치 |

---

## 7. 10개 semantic role mapping

`MISSION_ADAPTER.json`의 role binding은 정확히 아래 10개이며 각 항목에 세 필드를 모두 둔다.

| semantic role | `mission_meaning` | `production_state_field` | `evidence_path` |
|---|---|---|---|
| `PRIMARY_GOAL` | 현재 조건에 맞는 유효한 개인 주문 초안 완성 | `orderContext.primaryGoal` | `evidence/state/current_goal.json` |
| `TARGET_ENTITY` | 현재 합성 식당·메뉴·option schema | `orderContext.targetEntityId` | `evidence/state/context_selection.json` |
| `DISTRACTOR_ENTITY` | 다른 목표·식당의 과거 option 또는 만료된 주문 | `contextCandidates.entityId` | `evidence/state/context_selection.json` |
| `STABLE_VALUE` | 명시적으로 저장한 순한맛 취향 | `facts.stableValueToken` | `evidence/state/facts.json` |
| `ONE_OFF_VALUE` | 현재 주문 session만의 맵기·budget·time 조건 | `facts.oneOffValueToken` | `evidence/state/facts.json` |
| `CURRENT_AUTHORITY` | 가장 최근의 사용자 지시·정정 version | `facts.authorityVersion` | `evidence/state/authority.json` |
| `REVOKED_SCOPE` | 일회용 수저 제외의 자동 적용 permission scope | `permissions.revokedScopeIds` | `evidence/state/invalidation.json` |
| `PRESERVED_SCOPE` | 부분변경 뒤에도 유효한 본 메뉴·option scope | `draft.preservedFieldIds` | `evidence/state/recovery.json` |
| `DELAYED_OUTCOME` | 만족도 또는 예약된 합성 catalog outcome | `ledger.delayedOutcomes` | `evidence/ledger/outcomes.json` |
| `EPHEMERAL_VALUE` | 초안에 실제 사용되는 삭제 가능한 일회성 요청사항 | `facts.ephemeralRequestToken` | `evidence/state/deletion.json` |

`EPHEMERAL_VALUE`에는 실제 주소·전화번호·개인정보를 넣지 않는다. 제품 표현 예시는
`이번 주문: 소스 별도 포장`이며, 삭제하면 초안의 요청사항 field와 provenance만 무효화하고 메뉴·option은
보존한다.

---

## 8. 13종 operation mapping

아래 13종을 모두 구현하되 고정 순서·1회 실행을 가정하지 않는다. 한 run은 1–80 step이고 같은 operation이
반복될 수 있다. 각 input step마다 같은 순서·step ID·event ID·operation의 result를 정확히 한 번 만든다.

| operation | 순서에 독립적인 production 동작 | 관찰 evidence |
|---|---|---|
| `RESET_AND_START` | 선택된 namespace의 active product state를 clean run으로 시작하고 중단된 이전 run은 audit archive에 보존 | 새 run ID·counter 0·clean active state·이전 terminal snapshot |
| `UPSERT_FACT` | stable·one-off·permission·ephemeral request·catalog version fact를 authority·scope·lifetime과 함께 저장 | typed fact event와 before/after digest |
| `ADVANCE_SESSION` | 새 session·surface·target을 열고 이전 session의 one-off만 만료 | session 변경, expired one-off, preserved stable fact |
| `REQUEST_DECISION` | 현재 state의 순수한 판단으로 ACT/ASK/WAIT/ABSTAIN 등을 반환 | active/excluded fact, open confirmation, selected option과 provenance |
| `CORRECT_FACT` | 더 최신 authority로 지정 fact를 정정하고 dependency descendant를 선택 무효화 | authority version 증가, invalidated·preserved ID |
| `REVOKE_SCOPE` | 입력이 가리키는 불투명 scope의 permission descendant만 철회 | revoked scope, preserved fact와 다른 scope |
| `DELETE_FACT` | 초안에 사용된 ephemeral request 원문과 descendant를 제거하고 최소 tombstone 생성 | 원문 부재, invalidated request field, tombstone ID |
| `SET_NETWORK` | roles의 모든 값에서 synthetic network 상태를 읽어 catalog freshness·decision을 변경 | ONLINE/OFFLINE/DELAYED/UNKNOWN, WAIT·ABSTAIN과 보존 state |
| `PROCESS_KILL_RELAUNCH` | 어떤 pending proposal/commit 경계에서도 실제 process 종료 뒤 persistent checkpoint를 reconcile | 증가한 process epoch, pending action, commit 정직성 |
| `REPLAY_EVENT` | 이미 처리한 동일 event ID를 재전달하고 idempotency ledger로 중복 효과 차단 | duplicate 기록, 기존 action ID, 추가 commit 0 |
| `DELIVER_OUT_OF_ORDER` | 낮은 authority·catalog version의 event를 늦게 처리하고 current state 부활을 차단 | stale event ID·version, current state 보존 |
| `ADVANCE_TIME` | queue에 예약된 만족도·catalog outcome과 명시적 expiry를 virtual time 기준으로 materialize | virtual time, 도착 outcome ID, 다음 판단 변화 |
| `EXPORT_AND_END` | 현재 run의 state·ledger·receipt·step-bound evidence를 export하고 terminal 상태 기록 | result digest와 traceable ID, terminal snapshot |

`SET_NETWORK`는 `NETWORK_STATE`라는 특정 key 존재를 가정하지 않는다. 공개 sample처럼 role 값 전체에서
허용된 network token을 찾는다. offline에서는 cached draft·기억을 보존하지만 최신 catalog가 필요한
자동 적용·commit은 `WAIT`, 안전한 대안이 없으면 `ABSTAIN`으로 둔다.

`REQUEST_DECISION`은 새 fact·authority·session 변화가 없으면 같은 unresolved decision을 반환하며 호출
횟수만으로 다음 질문으로 이동하지 않는다. production evidence에는 deterministic priority의
`open_confirmation_ids`를 남기고, 사용자 응답에 해당하는 fact event 뒤에만 다음 unresolved 항목으로
진행한다. 이 목록은 Probe result schema에 임의 필드를 추가하지 않고 step에 연결된 evidence에 기록한다.

Probe callback은 화면과 별도 정답 logic을 사용하지 않고 같은 production repository·decision
engine·ledger를 호출한다.

---

## 9. 모바일 필연성과 constraint

### 모바일이어야 하는 이유

배달 주문의 현재 조건은 주문하려는 순간에 바뀐다. 사용자는 이동 중이거나 짧은 휴식시간에 휴대폰에서
예산·시간·기분을 말하고, 전화·앱 전환·화면 잠금으로 작업을 중단할 수 있다. 따라서 데스크톱에서 과거
취향을 분석하는 것만으로는 주문 순간의 입력 단축과 중단 뒤 연속성을 제공하지 못한다.

### 별도 mobile constraint

선언 문장:

> **선택 permission의 허용·거부 여부와 무관하게 delayed outcome을 app-local 정본에 정확히 한 번
> 기록하고, 앱 중단·재실행 뒤 다음 판단까지 연속시킨다.**

#### 제1축 — permission 독립적인 outcome 연속성

- delayed outcome은 어떤 permission-gated surface보다 먼저 app-local ledger에 commit
- permission 상태는 outcome 저장·정합성·다음 decision 입력을 바꾸지 않음
- outcome ID는 앱 중단·재실행 뒤에도 정확히 한 번만 판단에 반영
- 선택 permission을 모두 거부해도 E1–E4와 Primary value 경로 완료
- notification은 claim의 본체가 아니라 사람이 outcome 도착을 관찰하는 부수 surface
- notification이 허용되면 합성 outcome 도착을 알림으로도 표시
- notification이 거부되거나 사용할 수 없으면 in-app outcome inbox와 다음 실행 banner로 표시
- proposal과 commit 사이 process kill 뒤 유효한 draft를 복원하되, commit되지 않은 action을 완료로 표시하지 않음

#### 제2축 — 필수 병행 network degradation

`SET_NETWORK`는 별도 필수 operation이므로 permission fallback의 대안으로 취급하지 않는다.

- `ONLINE`: current catalog version을 검증하고 정상 판단
- `DELAYED`: cached draft를 보존하고 최신 catalog 의존 action은 `WAIT`
- `OFFLINE`: 기억·입력·draft를 손상시키지 않고 commit을 보류, 안전한 대안이 없으면 `ABSTAIN`
- `UNKNOWN`: 완료를 추정하지 않고 freshness 미확인 상태를 표시
- network 복구 뒤 current authority·catalog version으로 재판단
- network 상태가 달라도 delayed outcome ledger의 app-local 정본은 유실되지 않음

다음은 Mission constraint가 아니라 모바일 UI acceptance다.

- 최소 360dp 너비에서 한 화면의 primary action은 하나
- 자동 적용된 항목은 최종 확인 화면에서 출처와 함께 모두 확인 가능
- 삭제·철회·최종 가상 주문은 명시적 button confirmation 사용
- 복구 화면은 “보존됨 / 다시 확인 필요 / 변경됨”을 좁은 화면에서 구분

### PC counterfactual

같은 decision engine을 PC에서도 실행할 수는 있다. 그러나 휴대폰에서 실제로 발생하는 한 손 입력,
짧은 attention window, permission이 제한된 전달 surface, network 전환, 앱 전환·process death와
app-local outcome 연속성을 다루지 않으면 선언한 Primary value를 같은 방식으로 달성했다고 보기 어렵다.

---

## 10. 실패경계와 의도적 정지

| 상황 | 에이전트 행동 |
|---|---|
| option 의미가 확실하지 않음 | `ASK`; 자동 적용하지 않음 |
| 현재 catalog version이 만료됨 | `WAIT` 또는 현재값 미확인 표시 |
| 예산·시간·재고를 동시에 만족하는 후보 없음 | `ABSTAIN`; 조건 완화 선택지를 제시 |
| 현재 지시와 stable preference 충돌 | 현재 지시 적용, stable 원문은 명시적 변경 없이는 보존 |
| permission만 철회 | 자동 적용 descendant만 무효화, 취향 fact는 보존 |
| fact 삭제 | 원문과 복원 가능한 파생값 제거, tombstone만 보존 |
| side 품절 | side와 총액만 무효화, main과 독립 option 보존 |
| process kill 전 action 미commit | relaunch 뒤 pending 또는 cancelled로 표시, 완료로 표시하지 않음 |
| duplicate·out-of-order event | ledger와 version으로 무시 또는 reconcile, action 중복 금지 |

---

## 11. 구현 전 남은 승인 항목

다음 다섯 가지를 한 묶음으로 승인한 뒤 Mission 선언문을 작성한다.

1. 대상 사용자·핵심 문제·Primary value 최종 문구
2. E1–E4 압축안과 인과변화
3. ASPR Signature mechanism과 포함·제외 경계
4. recent-order `claim-off`와 VIL 비교 metric
5. 모바일 필연성·permission 독립적인 outcome 연속성·필수 network degradation

그다음에만 다음 문서를 만든다.

- 1쪽 Mission 선언 초안
- 저해상도 화면 흐름
- production state schema와 action ledger 설계
- Android 기술구조·검증 계획

schema-complete 연결 설계는 `MISSION_ADAPTER_DESIGN_DRAFT.json`, 변형 Probe 계획은
`PROBE_METAMORPHIC_TEST_PLAN.md`에 미리 작성했다. 두 파일은 APP·MISSION_LOCK·실제 source가 생기기 전의
설계 산출물이며 제출본이 아니다.

Android 구현은 위 산출물과 사용자의 명시적 승인이 모두 끝난 뒤 시작한다.
