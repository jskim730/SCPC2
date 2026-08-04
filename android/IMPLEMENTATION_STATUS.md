# 구현 상태와 남은 작업

최종 갱신: 2026-08-03 KST · 프로젝트 root: `android/` · 저장소: `github.com/jskim730/SCPC2`

이 문서는 **협업 인수인계 문서**다. 코드를 바꿀 때 이 문서의 "핵심 설계 결정"과 "남은 작업"을
같은 커밋에서 갱신한다. 공식 사실의 기준은 항상 루트의 `COMPETITION_CONTEXT.md`와 `release_v3/`다.

## 한눈에

| 항목 | 상태 |
|---|---|
| JVM 단위 테스트 | **159개 전부 통과** (debug·release 각각, emulator 불필요) |
| `assembleDebug` / `assembleDebugAndroidTest` | 통과 |
| 제출 Mission 범위 구현 (다중 항목·3-scope 취향·리뷰 승인·평점 랭킹) | **완료.** JVM으로 검증, 기기 육안 확인만 남음 |
| 기기 실행 검증 | **API 35 x86_64 에뮬레이터 기본 흐름 확인.** debug APK 설치·첫 식당 선택·즉시 메뉴 추천 카드 표시 확인. 전체 E1–E4 육안 완주는 남음 |
| 미구현·보류 항목 | 진짜 multi-select — 아래 전용 절 참조 (실제 network는 8/4 표시 전용으로 반영) |
| 공식 Runner 13-step 완주 | **미실행.** release 서명 뒤 수행 |
| release 서명 설정 | **배선 완료·검증됨.** keystore 생성만 남음 (8/4 노트북에서 1회) |
| 제출물 7종 | `APP.apk` 빌드 경로만 확보. 문서 4종·`SAMPLE_EXPORT`·영상 미착수 |
| Mission 선언 제출 | **제출·Dacon 동결 완료.** 공식 정본은 루트 `MISSION_LOCK.json` (`candidate_025`, `mission_025`, 수정 금지) |

소스 규모: main 22파일 7,811줄 / JVM 테스트 16파일 4,604줄 / 기기 테스트 1파일 237줄.

## 빌드·테스트

```bash
cd android && ./gradlew.bat :app:testDebugUnitTest
```

```bash
cd android && ./gradlew.bat :app:assembleDebug
```

`JAVA_HOME`이 없으면 Android Studio 번들 JBR을 쓴다.

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
```

| 항목 | 값 |
|---|---|
| Gradle / AGP / Kotlin | 8.9 / 8.7.3 / 2.0.21 |
| compileSdk · targetSdk / minSdk | 35 / 28 |
| ABI | arm64-v8a, x86_64 |
| applicationId | `com.scpc.deliveryagent` |

## 아키텍처와 레이어 경계

이 경계가 채점의 핵심이므로 코드를 옮길 때 반드시 지킨다.

```
ui/                     화면. 판단하지 않는다.
  ↓
delivery/               배달 표현 계층. 여기만 한국어·메뉴·금액을 안다.
  SyntheticCatalog        authored 데이터 로더 + 불변조건 강제
  NaturalLanguage         문장 → 구조화 후보 (PreferenceIntake)
  Recommender             후보 순위와 근거
  ProductSurface          제품 조작 → 13종 operation
  ↓
core/                   generic production core. 값 철자를 해석하지 않는다.
  AsprEngine              Signature mechanism: 선택 → 투영 → 부분 무효화
  ProductionCore          13종 operation dispatcher, ledger, evidence
  ProductionState         영속 state 문서 하나
  ↑
probe/                  ProductionProbeAdapter, PublicProbeRunner
platform/               Android 저장소·evidence 파일·release identity
```

`core/`는 android import가 없어 JVM에서 그대로 테스트된다. 새 판단 로직은 `core/`에, 새 어휘·메뉴·
금액은 `assets/synthetic/catalog.json`에 넣는다. **한국어 문자열을 `core/`에 넣지 않는다.**

## 핵심 설계 결정

문서(기술노트)에 그대로 옮길 내용이다. 실수로 되돌리기 쉬운 것에는 이유를 붙였다.

### 일반화

- **slot 식별**은 addressing role에서만 파생한다:
  `PRESERVED_SCOPE → TARGET_ENTITY → PRIMARY_GOAL → 현재 target → 현재 goal`.
- **authority version**은 불투명 token을 처음 본 순서로 monotonic 번호에 사상한다.
  `PUBLIC_AUTHORITY_V1`의 "V1"을 파싱하지 않는다.
- **slot 주소 지정과 자동 적용 허용은 별개다.** 초기 구현이 `PRESERVED_SCOPE` 하나에 두 역할을
  겸하게 해서 메뉴가 `slot.option.main`이 아니라 `slot.<식당>`으로 저장되는 버그가 있었다.
  지금은 값 저장 시 항상 scope로 slot을 지정하고, "매 주문 사용자가 골라야 하는 slot"은
  `declareOptionSchema(neverAutoApplySlotIds=…)`로 따로 선언한다. 되돌리면 주문이 완료되지 않는다.
- **entity 귀속과 가격 기준을 분리했다.** `Fact.entityId`는 배타적 귀속(다른 식당에서 제외됨),
  `Fact.catalogEntityId`는 가격이 붙은 line 여부다. 하나로 합치면 사이드가 총액에 안 들어간다.
- **userConfirmed**는 사용자가 질문에 답해 정한 값과 그 외를 구분한다. 철회·다른 fact의 삭제는
  전자를 보존하고, 같은 slot의 더 높은 authority는 언제나 값을 갱신한다.
- **commit identity** = 확정 draft 값들의 digest. 같은 draft에 반복 판단 요청이 와도 같은 action을
  돌려주고 추가 commit이 없다. status는 identity에 넣지 않는다(넣으면 commit 뒤 재확정된다).
- **session 경계**: step의 `session_id`가 달라지거나 `ADVANCE_SESSION`이면 새 session을 열고 내부
  key(`label#seq`)를 올린다. one-off만 만료되고 stable은 남는다.
- **network**: 질문은 network 없이도 가능하므로 열린 확인이 있으면 `ASK`가 우선한다. 확인이 없을 때
  `DELAYED/UNKNOWN → WAIT`, `OFFLINE →` cache 있으면 `WAIT` 없으면 `ABSTAIN`.
- **재고 event는 취향이 아니다.** 품절은 사용자의 이번 주문 선택과 같은 precedence로 들어가 더 높은
  authority로 이긴다. STABLE로 넣으면 ONE_OFF 선택에 밀려 품절이 반영되지 않는다.
- **catalog가 채울 수 없는 값**(`unusableValues`)을 가진 field는 확정 상태가 될 수 없다.
- **예산·시간 위반**은 `declareDraftConstraint`로 core에 전달되고 판단은 `ABSTAIN`이 된다. 금액 계산
  자체는 표현 계층 몫이다.
- **claim-off**는 같은 코드 경로에서 `asprEnabled=false`. lifetime·scope·permission typing과
  dependency edge가 없고, 현재 session에서 말하지 않은 값은 자동 적용하지 않고 다시 묻는다.
  삭제·tombstone·idempotency·restart·receipt·추천 순위·평점은 두 arm 공통 infrastructure다.

### 자연어

- 어휘는 전부 catalog 데이터다. 값 표현 80개, 리뷰 표현 15개, 모호 표현 6개, scope·의도 표현.
  한 표현이 두 뜻을 가지면 `SyntheticCatalog.parse`가 거부한다.
- **재사용 범위를 말하지 않으면 저장하지 않고 묻는다.** 추측이 이 제품이 없애려는 실패다.
- 모호 표현·현재 식당에 없는 option·이해 못 한 표현은 전부 질문이 된다. 조용히 버리지 않는다.
- **어절 단위 gapped 매칭**: 카탈로그가 `"간이 셌"`이라 써도 사용자는 `"간이 좀 셌어"`라 쓴다.
  어절을 순서대로 매칭하며 사이 간격을 허용한다(지시문 2자, 리뷰 4자). 조사 변형을 나열하지 않아도 된다.
- `PreferenceIntake`가 model 구현체 교체 지점이다. 현재 `modelConfigured=false`, inference 0회.

### 주문 초안 범위 — 개인 사용자·한 식당·여러 주문 항목

> **2026-07-31 제품 결정:** Mission의 사용자는 한 명으로 고정하되, 한 주문에서 같은 식당의 메뉴를
> 하나 이상 담을 수 있게 한다. 단체주문·타인 취향 profile·다중 식당 장바구니·묶음 주문·분할 결제는
> 제외한다.

> **2026-07-31 구현 범위 결정:** UI는 대본 중심 최소 범위다. 항목 추가는 추천 후보·사이드 탭을
> 경유하고, 항목별 옵션·수량·제거 control을 제공하며, 임의 메뉴 검색·브라우징 화면은 이번 제출
> 범위에서 제외한다. core와 JVM 테스트는 UI 범위와 무관하게 다중 항목을 일반적으로 지원한다.

**구현 완료 (2026-07-31).** 제품 화면이 `declareDraftStructure`로 현재 draft의 line 목록
(`DraftLineDecl`: lineId·메뉴 token·menu type·slot binding)을 선언하면, projection이 line별 field
(`slot.line.<id>.option.*`)를 만들고 scope가 맞는 취향으로 채운다. 사이드는 별도 option slot이 아니라
`course=side`인 메뉴 항목이다. Probe 입력은 line을 선언하지 않으므로 기존 one-field-per-slot 형태
그대로다 — probe 계약·metamorphic 테스트는 변경 없이 통과한다.

- 한 주문은 정확히 한 `restaurant_id`를 가진다.
- `OrderLine`은 `line_item_id`, `menu_id`, `menu_type_id`, `quantity`, option field를 가진다.
- 같은 메뉴를 옵션만 다르게 두 번 담으면 서로 다른 `line_item_id`를 사용한다.
- `line_item_id`는 현재 초안의 dependency·가격·재고·리뷰 원문 귀속용이며 장기 취향 key가 아니다.
- 전역·메뉴 유형·식당/메뉴 취향은 동일 사용자의 각 주문 항목에 독립적으로 적용한다.
- 현재 지시가 특정 항목인지 전체 호환 항목인지 불명확하면 자동 전파하지 않고 대상 메뉴를 묻는다.
- 한 항목의 품절·삭제·옵션 변경은 그 항목과 총액만 다시 열고 다른 항목은 보존한다.
- 최종 가상 주문 action은 전체 `order_id`와 정렬된 항목 snapshot에 대해 한 번만 commit한다.

### 리뷰와 평점

리뷰 텍스트와 별점을 **다르게** 취급한다. 이 구분이 설계의 기억 계약(`EXPLICIT_STABLE` /
`DELAYED_OUTCOME` / `RAW_HISTORY`)에 그대로 대응한다.

> **구현 완료 (2026-07-31).** 명시적 안정 취향은 `GLOBAL_DEFAULT`, `MENU_TYPE`,
> `RESTAURANT_MENU_OVERRIDE` 세 범위로 저장되고 `식당·메뉴 > 메뉴 유형 > 전역` 순으로 주문 초안에
> 적용된다. 리뷰 후보는 `acceptFromReview`가 동의한 범위의 scoped stable fact로 승격하고, 내 평점은
> 동일 `(식당, 메뉴)` 추천의 동순위 tie-break 근거다.

| | 리뷰 텍스트의 option 언급 | 별점 |
|---|---|---|
| 무엇에 대한 말 | 사용자가 지정한 주문 항목의 option 의미 (간 세기) | 사용자가 지정한 식당·메뉴 |
| 저장 | 승인 전 `RAW_HISTORY`와 후보, 승인 후 선택한 범위의 `EXPLICIT_STABLE` + permission (`derivedFactIds`로 리뷰에 연결) | `state.reviews`에서 `(식당, 메뉴)`별 파생 |
| entity 경계 | `GLOBAL_DEFAULT`, `MENU_TYPE`, 정확한 `(식당, 메뉴, option slot)` 중 사용자 승인 범위 | 정확한 `(식당, 메뉴)` |
| 영향 | 범위가 일치하는 주문 초안에서 specificity 순으로 적용 | 동순위 후보의 tie-break와 "내 평점" 근거 badge |

- 리뷰 문구만으로는 아무것도 자동 적용하지 않는다. 앱은 “이 식당의 이 메뉴는 다음부터 `덜 맵게`를
  초안에 적용할까요?”처럼 값·식당·메뉴·자동 적용 범위를 명시해 묻는다.
- 주문 항목이 하나면 평가 대상을 그 메뉴로 미리 연결한다. 여러 메뉴면 사용자가 메뉴를 먼저 선택하거나
  리뷰에서 대상을 명확히 지정해야 한다. 대상이 모호하면 평점을 추천에 반영하거나 리뷰 후보를 취향으로
  저장하지 않고 메뉴부터 확인한다.
- 리뷰 승인 시 `이 식당·메뉴만`, `모든 식당의 같은 메뉴 유형`, `모든 메뉴`, `저장하지 않음` 중
  적용 범위를 명시한다. 동의한 범위의 stable fact만 만들고 다른 범위는 바꾸지 않는다.
- 예: 전역 `매운맛`, 메뉴 유형 `떡볶이 → 보통맛`, 특정 `다온분식 + 불떡볶이 → 덜 맵게`.
- 동일 slot의 초안 적용 우선순위는
  `현재 주문 직접 지시 > 현재 정정·철회·삭제 > 식당·메뉴 override > MENU_TYPE > 전역 기본 취향 >
  ASK`다. 같은 범위 안에서는 더 높은 authority가 이긴다.
- scoped override는 정확히 같은 식당·메뉴에서만 사용한다. 리뷰 하나를 해당 식당의 모든 메뉴나 다른
  식당으로 확대하지 않는다. 범위를 넓히려면 별도의 명시적 승인을 받는다.
- `MENU_TYPE`은 메뉴 표시 이름이 아니라 catalog가 각 메뉴에 부여한 안정적인 `menu_type_id`로
  연결한다. A식당과 C식당의 메뉴가 같은 `menu_type_id`이고 option semantic이 호환될 때만 식당을
  넘어 재사용한다.
- 현재 catalog에 저장된 option이 없거나 의미 mapping이 불확실하면 자동 대체하지 않고 그 field만
  `ASK`로 연다.
- 리뷰를 지우면 연결된 후보와 review-derived scoped override도 제거하고 tombstone만 남긴다. 사용자가
  별도로 같은 값을 독립 취향으로 재확인한 경우에만 리뷰와 분리된 fact로 유지할 수 있다.
- 평점은 사용자의 자기 기록만 사용하며 해당 `(식당, 메뉴)` 후보의 추천 근거로 반영한다. 표본 수가
  적을 때 한 번의 평점이 순위를 과도하게 움직이지 않도록 count-aware smoothing을 사용하고, 평점은
  예산·시간·품절 같은 hard constraint나 자동 옵션 적용 근거로 사용하지 않는다.
- 내 평점 랭킹은 `full`/`claim-off` 양 arm에서 동일하게 작동한다. Signature 공로로 주장하지 않으며,
  기존 "두 arm 동일" 계약을 새 랭킹 테스트로 승계한다.
- smoothing 계약: `adjust = (내 평점 − 3.0) × n/(n+1)` (n = 해당 쌍의 내 평점 수)를 조건 충족·현재
  지시 일치보다 항상 후순위인 순위 점수에만 더한다. n=0이면 0이고, 조건 불충족 후보를 평점으로
  끌어올리지 않는다.
- 라벨은 **"내 평점"** 이다. 공개 평점으로 오해되지 않게 한다.

### 승인된 주문·학습 흐름 — 구현 완료 기준

1. 사용자의 자연어 질의를 구조화된 현재 조건으로 해석한다.
2. 전체 합성 catalog에서 조건·전역 취향·메뉴 유형 취향·식당/메뉴 취향·내 평점을 근거로 후보를 만든다.
3. 첫 후보 선택으로 식당을 확정하고, 사용자가 같은 식당의 메뉴를 하나 이상 주문 항목으로 추가하면
   항목별 메뉴·수량·옵션을 포함한 주문 초안을 만든다.
4. 각 주문 항목의 옵션값은
   `이번 주문 직접 지시 > 식당·메뉴 override > MENU_TYPE > 전역 기본 취향` 순서로 해석한다.
5. 저장값이 없거나 무효·미허용·catalog 불일치이면 해당 항목만 수동 선택으로 남긴다.
6. 사용자는 채워진 값도 이번 주문에 한해 수정할 수 있으며, 이 값은 stable fact를 덮어쓰지 않는다.
7. 모든 주문 항목의 필수 field와 주문 전체의 최종 확인이 끝난 경우에만 app-local 가상 주문 action을
   한 번 기록한다.
8. 주문 뒤 평가 요청을 표시하고 사용자가 평점과 리뷰를 입력한다.
9. 주문 항목이 하나면 해당 메뉴를 평가 대상으로 연결하고, 여러 항목이면 사용자가 평가할 메뉴를
   선택하게 한다. 평점은 동일 `(식당, 메뉴)`의 이후 추천에 사용한다.
10. 대상 메뉴가 확정된 리뷰에서 option 취향 후보를 찾으면 값과 세 범위를 보여주고 저장·자동 적용
    여부를 묻는다.
11. 동의한 후보만 선택한 범위의 stable fact로 저장해 이후 일치하는 초안에 적용한다.

2026-07-31 필수 변경 목록은 **전부 구현되었다.** 구현이 목록과 다른 지점 두 가지만 기록한다:

- probe adapter·`ReferenceRuns`·계약 테스트는 "재작성"이 아니라 **무변경으로 남겼다**. line 기제는
  제품이 draft 구조를 선언할 때만 켜지고, probe 입력은 선언하지 않으므로 기존 계약이 그대로
  성립한다 — 이 격리 자체가 회귀 방지 장치다. `MISSION_ADAPTER.json`은 `STABLE_VALUE`(3-scope 구조
  필드)와 `DELAYED_OUTCOME`(만료 가능한 평가 요청) 의미만 보강했다.
- "식당·메뉴 쌍 후보" 추천은 현재 식당 안의 메뉴 순위 + 식당 선택 chip으로 구현했다(대본 중심 최소
  UI 결정). 전 식당 통합 후보 목록은 범위 밖.

#### 세 범위의 저장 key 계약

```text
PreferenceKey(
  slotId,
  scopeLevel,       // GLOBAL_DEFAULT | MENU_TYPE | RESTAURANT_MENU_OVERRIDE
  menuTypeId?,      // MENU_TYPE에서만 필수
  restaurantId?,    // RESTAURANT_MENU_OVERRIDE에서만 필수
  menuId?,          // RESTAURANT_MENU_OVERRIDE에서만 필수
)
```

`factId`와 tombstone ID는 이 key 전체를 포함해야 같은 slot에 세 범위의 값을 동시에 보존할 수 있다.
`ReviewRecord`는 파생된 value token만이 아니라 생성한 scoped fact ID를 연결해야 리뷰 삭제 시 정확한
descendant만 제거할 수 있다. 범위별 permission도 같은 key를 사용하며, 한 범위의 철회가 다른 범위의
취향이나 permission을 지우면 안 된다.

#### 주문 항목 key 계약

```text
OrderDraft(
  orderId,
  restaurantId,
  items: List<OrderLine>,
  orderLevelFields,
  total,
  status,
)

OrderLine(
  lineItemId,       // 현재 초안 안에서 불변인 고유 ID
  menuId,
  menuTypeId,
  quantity,
  optionFields,     // field key에 lineItemId + slotId 포함
  subtotal,
  catalogVersion,
)
```

- `lineItemId`는 배열 위치나 `menuId`로 만들지 않는다. 항목 순서 변경과 같은 메뉴 중복에도 안정적이어야
  한다.
- 저장 취향의 `PreferenceKey`에는 `lineItemId`를 넣지 않는다. 취향은 다음 주문의 새 항목에도
  재사용되어야 하기 때문이다.
- 현재 주문 one-off 지시는 대상 `lineItemId` 집합을 가질 수 있다. 전체 적용은 사용자가 명시하고 option
  의미가 호환되는 항목에만 허용한다.
- 항목 삭제 tombstone은 현재 `orderId + lineItemId`에만 적용하며 메뉴에 대한 장기 취향을 삭제하지 않는다.
- `ReviewRecord`는 `orderId`, `actionId`, `lineItemId`, `restaurantId`, `menuId`를 함께 보존한다. 여러
  항목 중 대상이 확정되지 않은 리뷰는 후보 상태로만 두고 평점 ranking이나 stable fact를 만들지 않는다.
- action idempotency key에는 정렬된 `lineItemId`와 각 항목의 현재 version/digest를 포함하거나 그 전체
  snapshot의 digest를 사용한다. 배열 순서만 달라져 다른 주문으로 취급되면 안 된다.

## 합성 데이터 (`app/src/main/assets/synthetic/catalog.json`, schema 3)

schema 3 (2026-07-31): `menu_types[]`(token·label·`course`·`stable_option_slots`), 메뉴별
`menu_type`·`line_option_slots`, 식당별 `order_level_slots`·`offered_values`를 추가했다.
`MENU_TYPE` 취향은 catalog-authored type token이 같고 그 type의 stable slot이며 현재 식당이 그
slot을 제공할 때만 식당을 넘는다.

**규모 (2026-08-03 확충):** 식당 6 · 메뉴 19 · option slot 21 · menu type 5.
메인 메뉴 하나가 4–5개 옵션을 지고, 사이드도 처음으로 옵션을 하나씩 가진다.

| menu type | 메뉴 | 식당 | 무엇을 시연하나 |
|---|---:|---:|---|
| `soup_meal` | 6 | 3 | 밥 양·간 세기 취향이 세 식당을 건넌다 |
| `tteokbokki` | 3 | 2 | 치즈(유료) 취향이 식당을 건넌다 |
| `mala` | 3 | 2 | 고수 빼기 커스터마이징이 식당을 건넌다 |
| `side_fried` / `side_rice` | 3 / 3 | 2 / 3 | 사이드가 별도 slot이 아니라 주문 항목이다 |

확충의 목적은 개수가 아니라 **이전 카탈로그로는 시연할 수 없던 두 가지**다.

1. **식당마다 옵션 가짓수가 다르다.** 마라향은 맵기를 `순한맛/아주 매운맛` 두 단계만 내고 금손분식은
   세 단계를 낸다(`offered_values`). 그래서 금손에서 저장한 `중간맛`이 마라향에서는 적용되지 않고
   그 field만 다시 열린다 — "저장된 option이 현재 catalog에 없으면 자동 대체하지 않는다"는 선언을
   이제 화면에서 보일 수 있다. 이전에는 세 식당이 옵션 어휘를 100% 공유해 시연 자체가 불가능했다.
2. **재료 빼기와 유료 추가.** 고수·계란은 빼기/그대로, 두부(+1,500)·치즈(+1,000)는 추가/안 넣기의
   이진 옵션이다. 한 slot에 여러 값을 동시에 담는 진짜 multi-select는 집합 값 field가 필요해
   probe 계약까지 파급되므로 이번 범위에서 제외했고, 이진 옵션으로 같은 제품 경험을 표현한다.

사람이 작성한 JSON asset 하나다. 런타임 무작위 생성·외부 조회를 쓰지 않는 이유:

- paired comparison이 "같은 시작 snapshot bytes·같은 seed·같은 event order"를 요구한다(C3).
- 데이터가 판단 경로 밖에 물리적으로 있어야 core가 값 철자로 분기하지 않음을 보이기 쉽다.
- 한 파일이 APK와 `SOURCE.zip`에 byte-for-byte 같이 들어가고 검토 가능하다.

현재: 식당 6, option slot 21, 리뷰 표현 15, 모호 표현 6, catalog event 2.

`SyntheticCatalog.parse`가 제품 코드에서 강제하는 불변조건 — 어휘를 추가할 때 여기에 걸린다:

- token 유일성, 음수 가격 금지, 메뉴 항목의 양수 가격·예상시간
- 존재하지 않는 slot·값·trait 참조 금지
- 식당명에 `실험` 표식 필수
- label에 `@`나 전화번호 패턴 금지
- **한 표현이 두 값을 가리키면 거부**
- 평점 score가 선언된 1..`rating_scale_max` 범위 안

품절·가격변경은 코드 분기가 아니라 `catalog_events`다. `ProductSurface.applyCatalogEvent`가 그 slot
하나에만 더 높은 authority로 전달하므로 본 메뉴와 독립 option은 보존된다.

## 검증 — JVM 159개, emulator 불필요

| 파일 | 개수 | 내용 |
|---|---:|---|
| `core/ProbeOperationContractTest.kt` | 29 | step 1:1 대응, digest chaining, epoch, 만료, 철회, 정정, 삭제, 부활 차단, idempotency, replay, network, 복구 필요, verdict 어휘 부재, reset 직후 goal·target 확립 |
| `core/MetamorphicProbeTest.kt` | 10 | V1 token 전면치환 불변, V2 entity/goal 교환, V3 순서변형·반복·중간 Reset, V4 26-step 장기 연결, line 단위 부분 무효화 |
| `core/ClaimOffComparisonTest.kt` | 6 | VIL paired 비교, guardrail 0 위반, arm state 완전 격리 |
| `delivery/SyntheticCatalogTest.kt` | 10 | shipped asset byte로 불변조건 검사, menu type·line slot authoring, digest 결정성, 개인정보 유사 label 부재, 잘못된 catalog 거부 |
| `delivery/ProductFlowProbeTest.kt` | 8 | 제품 기준 E1–E4 완주, 가격·예상시간, 항목 단위 품절 부분복구, 예산 초과 `ABSTAIN`, 재시작 연속성, 첫 식당 선택 즉시 추천 후보 |
| `delivery/ChatIntakeTest.kt` | 19 | 금액 4형태(프리셋 밖 금액 포함)·기간·부정 표현, 모호 표현 질문화, 미제공 option 질문화, 철회 의도 분리, 추천 순위·근거, 순위 결정성, **조건 발화 자체가 후보 요청** |
| `delivery/MultiLineDraftTest.kt` | 10 | 두 메뉴 두 line, 같은 메뉴 옵션 분리, line 지시 우선, 수량·총액, 항목 제거·부활 차단, line ID 불재사용, 단일 commit, 항목 단위 품절, product step digest 연속성 |
| `delivery/ScopedPreferenceTest.kt` | 9 | override > 유형 > 전역 우선순위, authored type만 식당 간 전달, stable slot 제한, 범위별 철회·삭제·정정 독립, 범위 표시 |
| `delivery/ReviewMemoryTest.kt` | 20 | 리뷰 읽기, 동의 전 무변경, 범위 선택지 제한, scoped 승격·같은 쌍만 적용·유형 전달, review version 소유권, 최신 직접 지시·정정 보존, 독립 outcome 격리, 평가 요청 원자적 예약·도착·만료·응답 해소, 다중 항목 대상 확정 |
| `delivery/RatingDisplayTest.kt` | 12 | 평균·척도·건수, 식당 경계, 조건·취향 우선 유지, 동순위 tie-break 양 arm 동일, "내 평점" 근거 badge, count 감쇠, 차단 없음, 삭제 반영 |
| `delivery/DemoScriptTranscriptTest.kt` | 1 | **화면 검토용.** E1–E4 대본을 실제 화면 조립 함수로 걸어 각 단계 화면을 출력하고, 답할 수 없는 질문·원시 token·내부 상수 노출·중복 행이 없음을 확인 |
| `delivery/GuideScriptSentencesTest.kt` | 2 | **설치가이드 §3이 심사관에게 그대로 입력하라고 지시하는 문장**을 타이핑만으로 검증. 메뉴 이름으로 주문, 한 문장이 담은 항목 추가와 요청 메모를 함께 적용. 대본 테스트가 같은 자리에서 `addLine(token)`으로 파서를 우회하던 사각을 덮는다 |
| `delivery/OptionAvailabilityTest.kt` | 10 | 식당별 옵션 가짓수, 미제공 값 ASK·임의 대체 금지·취향 보존, 유료 추가의 항목·수량·예산 반영, 마라/국물/떡볶이 유형의 식당 간 전달 |
| `ui/DeviceLinkTest.kt` | 5 | 기기 실제 연결의 **표시 전용** 매핑 — 활성 network 없음→끊김, 인터넷 capability 없음→연결됐지만 인터넷 없음, 연결됨 판정, 라벨이 내부 상수를 노출하지 않음. Android 타입을 쓰지 않는 순수 함수로 분리해 emulator 없이 고정 |
| `delivery/ParticlesTest.kt` | 6 | 조사 선택 규칙(을/를·으로/로·이/가), ㄹ받침 예외, 숫자·영문 끝 라벨 |
| `delivery/AllRestaurantCompletionTest.kt` | 2 | 6개 식당의 재고 있는 모든 main menu를 새 run에서 선택하고, 제공 가능한 값으로 필수 질문을 해소해 정확히 한 번 `ACT` 되는지 전수검사. 식당별 **전체 메뉴(사이드 포함)를 한 주문에** 담은 장바구니 경로도 같은 방식으로 완주 검사 |

`DemoScriptTranscriptTest`는 기기 수동검증 전에 화면 문장을 먼저 전수검사하는 수단이다.
`MainActivity`와 **같은 조립 함수**(`slotLabel`·`displayValue`·`statusLine`·`DraftPricing`·`Recommender`·
`reviewScopeChoices`)로 문장을 만들므로 출력이 곧 사람이 읽는 화면이다. 레이아웃·터치·스크롤·
lifecycle은 여전히 기기 검증 몫이다. 화면 원문을 보려면:

```bash
cd android && ./gradlew.bat :app:testDebugUnitTest --tests "*DemoScriptTranscriptTest"
```

출력은 `app/build/test-results/testDebugUnitTest/TEST-*DemoScriptTranscriptTest.xml`의
`system-out`에 UTF-8로 들어 있다.

보조: `core/ProbeRunHarness.kt`(adapter와 같은 방식으로 core 구동, `relaunch()`로 process 교체 재현),
`core/ReferenceRuns.kt`(13-op reference run, V3 18-step, V4 26-step).

`androidTest/probe/ProbeParityTest.kt`는 컴파일까지 확인했고 **실행은 기기에서** 한다.

## 일정 절단선 (2026-07-31 확정)

| 시점 KST | 내용 |
|---|---|
| ~8/3 밤 | 이 저장소에서 코드+JVM 테스트 완료 (**코드 freeze**) |
| 8/4 | RAM 16 GB 노트북: 기기 검증·release 서명·13-step 완주·V1–V4·SAMPLE_EXPORT·영상·문서 4종 |
| 8/5 10:00 | 최종 제출 마감 |

8/4 하루에 기기 검증과 산출물 제작이 몰려 있어 기기에서 문제가 발견되면 되돌릴 버퍼가 없다.
freeze 전에 JVM에서 재현 가능한 것은 전부 JVM 테스트로 내려서 검증한다.

## 남은 작업

### A. 지금 이 저장소에서 (기기 불필요)

0. **freeze 전 최우선 — 전부 완료 (2026-08-03).** 전 식당 완주 전수검사(장바구니 경로 추가),
   자유 탐색 UI 크래시 경로 전수 확인·차단.
1. **제출 문서 초안 3종 — 전부 완료 (2026-08-03).** 루트의 `MISSION_AND_TECHNICAL_NOTE.md`·
   `INSTALL_AND_USE_GUIDE.md`·`BUILD_AND_SUBMISSION_INFO.md`. 설치가이드에 E1–E4 재현 문장이
   들어 있어 앱에서 대본을 뺄 수 있었다. 빌드 문서의 "동결된 산출물" 절만 8/4에 실제 값으로 채운다
   (SHA-256·인증서 지문은 제출 완성 도구가 계산하므로 수기 기입 금지).
2. 파서 어휘 확장 — 데모에서 쓸 문장을 먼저 확정하고 그 표현을 확실히 커버한다.
   `INSTALL_AND_USE_GUIDE`의 E1–E4 문장이 현재 어휘로 전부 커버됨을 JVM 테스트가 아니라
   기기에서 직접 타이핑해서도 한 번 확인한다.

(완료: MemoryActivity 리뷰·평점·범위별 취향 목록과 범위별 철회·삭제, release 서명 배선 — 2026-07-31)

### B. RAM 16 GB 노트북에서 (기기 필요)

1. 환경: Android Studio + SDK Platform 35 + Build-Tools 35.0.0 + platform-tools + cmdline-tools latest,
   `.venv`에 `release_v3/candidate_kit/requirements.txt`
2. **release keystore 생성 — 이 노트북에서 한 번, 여기에만 둔다.** 리허설에 쓴 APK를 그대로 제출해야
   하므로(공식 규칙 §7) 최종 APK는 이 기계에서 빌드한다. 배선(`app/build.gradle.kts`)은 이미 되어
   있고 `keystore.properties`만 채우면 된다:

```bash
keytool -genkeypair -v -keystore scpc2-release.jks -keyalg RSA -keysize 4096 -validity 10000 -alias scpc2
```

   그다음 `android/keystore.properties.example`을 `android/keystore.properties`로 복사해 네 값을
   채운다. `.jks`와 `keystore.properties` 모두 `.gitignore`가 막고 있으니 커밋되지 않는다. **`.jks`는
   저장소 밖에 백업해 둔다** — 잃어버리면 같은 인증서의 APK를 다시 만들 수 없다.
   `./gradlew.bat :app:assembleRelease`가 서명된 `app-release.apk`를 만들면 성공이다. 설정이 없으면
   packaging 단계에서 명시적으로 거부하므로 서명 안 된 APK가 나올 일은 없다.
3. AVD 생성 후 **network 설정 사전 시드** — 빼먹으면 13-step이 시작조차 안 된다:

```bash
adb shell settings put global wifi_on 1
```

```bash
adb shell settings put global mobile_data 1
```

4. `./gradlew.bat :app:connectedDebugAndroidTest` — parity test 실행
5. `installDebug` 후 `INSTALL_AND_USE_GUIDE` 3장의 문장을 그대로 입력해 E1→E4 완주.
   **이어서 §4의 기기 network 표시 확인** — 비행기 모드를 켜고 `메뉴 → network 상태`의 `기기` 값과
   상단 표시가 `끊김`으로 바뀌는지, 그동안 **합성 상태는 그대로**인지. 순수 매핑은 JVM으로 고정했지만
   `ConnectivityManager`를 실제로 읽는 부분은 기기에서만 확인된다
6. 앱 안 `평가·내보내기`에서 `release_v3/probe/PUBLIC_PROBE_INPUT_13_STEP.json` import → run → export
7. release 서명 APK를 `work/APP.apk`로 두고 `make_local_integration_fixture.py` →
   `runnerctl.py run` → `PROBE_RESULT.json` 확인 (`SETUP_AND_REHEARSAL_RUNBOOK.md` Phase 1)
8. V1–V4 변형 입력으로 반복 (`PROBE_METAMORPHIC_TEST_PLAN.md`)
9. `FINALIZE_SAMPLE_EXPORT.py` → `VALIDATE_SAMPLE_EXPORT.py`
10. `DEMO_VIDEO.mp4` 3분 이내
11. **최종 APK를 정한 뒤에는 다시 build/sign 하지 않는다.**

## 열린 결정

### model 사용 — 결정적 파서로 확정 (2026-07-31)

Probe 경로에는 자연어가 들어오지 않는다. official 채점은 불투명 role token만 보내므로 model은 Q/80에
기여하지 못하고, comparison은 양 arm 동일 model을 요구하므로(공식 규칙 §10) VIL 차이도 만들 수 없다.
사람 Judge는 모든 APK를 처음부터 직접 조작하지 않고(§12), 데모 영상 대본은 파서 어휘로 커버한다.

기각한 대안과 이유:

| 방식 | 기각 이유 |
|---|---|
| 온디바이스 소형 LLM | APP.apk 300–800 MB(번들 필수. 첫 실행 다운로드는 OFFLINE 요구와 충돌), 모델 라이선스 신고, 기기 간 출력 동일성 미보장. sub-1B 한국어 구어체에서 one-off/stable·부정·범위 구분이 불안정한데 그 오추출은 기억 계약 위반처럼 보인다. 채점 경로가 건드리지 않는 대형 컴포넌트는 C2(불필요한 복잡성 부재)·C4(resource trade-off) 감점 소재 |
| 우리 backend + 클라우드 | endpoint 상시가동 의무(08-05~심층검증), domain·전송데이터 신고, 판단당 4회·전체 60회 계수 구현, 장애 시 G3/G5 위험 |

파서 실패는 `ASK`로 수렴해 설계된 안전 동작을 시연한다. `PreferenceIntake`는 model 교체 지점으로
유지하고(`modelConfigured=false`, inference 0회) 본선 진출 뒤 재검토할 수 있다. 기술노트에는
"결정적 intake는 재현성·오프라인·자원 절약을 위한 의도된 설계"로 서술한다(규정 §8 "deterministic
구현도 허용" 명문).

### 다른 사용자 평점 — 하지 않기로 결정 (2026-07-30)

1. §9가 타인의 고객정보 사용을 금지하고, §4가 "실제 계정·개인정보·외부서비스에 의존하는 제품"을 과제
   미충족으로 명시한다.
2. `PERSONAL_DELIVERY_AGENT_UX_SPEC.md`의 "이번 Mission에서 제외" 목록에 **"단체주문과 타인의 취향
   profile"** 이 이미 들어 있다.
3. 합성 인기도라도 "새 식당에서 유효한 개인 취향 재사용으로 확인이 줄어든다"는 인과 서사와 경쟁한다.
   인기 있지만 현재 조건에 안 맞는 메뉴가 올라오면 우리가 고쳤다고 주장하는 실패 모드를 시연하게 된다.

타인의 평점은 계속 제외한다. **"내 평점"** 은 현재 코드에서는 표시 전용이지만, 승인된 제품 목표에서는
동일 `(식당, 메뉴)` 후보의 추천 근거로만 사용한다. 자동 옵션 적용이나 hard constraint에는 사용하지 않는다.

## 미구현 사항과 추후 구현 방향

2026-07-31 기준으로 **의도적으로 넣지 않았거나 아직 확인하지 못한 것**을 근거와 함께 남긴다.
"안 했다"와 "안 하기로 했다"를 구분하는 것이 목적이다.

### 1. 실제 기기 network — 표시 전용으로 반영 (2026-08-04)

판단은 그대로 합성 상태만 읽는다. 기기의 실제 연결은 `ui/DeviceLink.kt`가 render 시점에 읽어
**표시 전용**으로만 보여준다: `메뉴 → network 상태`에서 합성 값과 나란히, 그리고 실제 연결이 끊긴
동안에는 상단 appBar에도 `기기 …`가 함께 뜬다.

- **판단 경로 무변경:** `ProductionState.network`는 여전히 `SET_NETWORK` operation과 제품 화면 버튼으로만
  바뀐다. `core/`는 `ConnectivityManager`를 모른다. probe 계약과 claim-off 짝 비교가 영향을 받지 않고,
  기기 없이 재현되는 성질도 그대로다. 공식 채점은 `SET_NETWORK`로 상태를 주입하므로 Q/80 경로도 동일하다.
- **해결한 위험:** 심사관이 비행기 모드를 켜도 앱이 `ONLINE`만 말하던 화면이 사라졌다. 두 값이 다르면
  다르다고 말한다. 어느 쪽이 판단을 움직이는지도 화면에 적혀 있다.
- **구현 선택 — `NetworkCallback` 대신 render 시점 동기 읽기.** 콜백은 lifecycle 해제 실수가 그대로
  크래시 경로가 되는데(§3에서 전수 차단한 것과 같은 종류), freeze 다음날 그 위험을 지는 대신 화면을
  열면 최신 값이 보이는 수준으로 충분하다. 읽기 실패·service 부재·권한 부재는 전부 `확인 불가`로
  수렴하고 화면은 그대로 산다.
- **권한:** `ACCESS_NETWORK_STATE`는 install-time 권한이라 런타임 거부 대상이 아니다. manifest의
  "선택 권한을 전부 거부해도 완주한다"는 성질은 그대로 성립한다.
- **검증:** `ui/DeviceLinkTest.kt` 5개. Android 타입을 건드리지 않는 순수 매핑 함수로 분리해 emulator
  없이 고정했다. `ConnectivityManager`를 실제로 읽는 부분만 기기 몫으로 남는다.

### 2. 진짜 multi-select 옵션 — 이번 범위에서 제외

토핑 여러 개 동시 선택처럼 한 slot이 값 집합을 갖는 형태는 넣지 않았다. 재료 빼기·토핑 추가는
이진 옵션 slot으로 표현했다(고수·계란·두부·치즈).

- **제외 이유:** 집합 값 field는 core projection·부분 무효화·dependency·probe 결과 형태까지 파급되어
  다중 항목 작업과 맞먹는 규모다. freeze 이틀 전에는 회귀 위험이 이득보다 크다.
- **추후 방향:** 본선 진출 후. `DraftField.value: String?`을 값 목록으로 확장하기보다, slot 하나에
  값 하나라는 계약을 유지한 채 토핑마다 slot을 두는 현재 방식을 유지하는 편이 core를 단순하게 둔다.
  UI에서만 여러 토핑 chip을 한 줄로 묶어 보여주는 것으로 체감을 개선할 수 있다.

### 3. 심사관 자유 탐색 시 크래시 경로 — 전수 확인·차단 완료 (2026-08-03)

`ui/`의 `surface.` 호출 전수를 분류했다. 읽기 호출을 제외하고 `act`/`actQuiet`/try-catch 밖에서
상태를 변경하던 호출 9곳을 전부 감쌌다: MainActivity 4곳(`answerScope`의 `remember` 루프,
리뷰 대상 선택 `setReviewTarget`, 리뷰 삭제, `dismissFromReview`)과 MemoryActivity 5곳(범위별
철회·삭제·전역 철회·메모 삭제 — 공통 `guarded` 헬퍼 경유). 낡은 항목·중복 탭은 크래시 대신
Toast가 되고, `answerScope`는 **실제로 저장된 값만** 대화에 말한다(G3 정직성).

낡은 화면 상태 경로도 닫았다: `clearTransientScreenState()`가 후보·재사용 범위 질문·리뷰 진행
상태 7필드를 비우며, 식당 선택 chip과 `다음 주문을 시작할까요?` chip 모두 session 전환 시
호출한다. 이전 식당의 추천을 새 식당에서 누르거나 반쯤 답한 리뷰 범위가 다음 session의
render에 살아남는 경로가 사라졌다. 저장된 기억 자체는 core에 있으므로 이 초기화의 영향을 받지
않는다.

### 4. 기기에서만 가능한 검증 (위 "남은 작업 B" 전체)

`connectedDebugAndroidTest`(ProbeParityTest), 공식 Runner 13-step 완주, V1–V4 변형, `SAMPLE_EXPORT`,
데모 영상, release 서명 keystore 생성이 모두 미실행이다.

개발 노트북(RAM 5.9 GB)에서 2026-08-04에 직접 측정한 결과 **에뮬레이터는 뜬다.** `scpc36`
(android-36 google_apis x86_64, `hw.ramSize=1536`)이 140초에 부팅했고, debug APK 설치(11초)와 앱
실행이 모두 성공했으며 식당 칩·채팅 입력창까지 의도대로 렌더링됐다. 앱 자체 크래시는 없었다.

쓸 수 없는 이유는 따로 있다. 부팅 시점 호스트 여유 RAM이 0.27 GB뿐이라 **게스트의 시스템 앱이
버티지 못한다.** `MainActivity` 첫 표시에 16.8초가 걸렸고(`Displayed ... +16s813ms`), Digital
Wellbeing과 System UI가 연달아 ANR을 냈으며 그 대화상자가 포커스를 가져가 탭 입력이 먹지 않았다.
13-step은 force-stop·relaunch·network 변경을 포함하므로 이 상태로는 완주할 수 없고, 데모 영상도
이 화면으로는 찍을 수 없다.

원인이 둘 겹쳤다. ① 호스트 여유 RAM, ② **런북이 지정한 이미지가 아니다.**
`SETUP_AND_REHEARSAL_RUNBOOK`은 `system-images;android-35;default;x86_64`(AVD `scpc35`)를 쓰라고
하는데 설치된 것은 android-36 `google_apis` 하나뿐이다. 가장 먼저 ANR을 낸 Digital Wellbeing은
`default` 이미지에는 아예 없는 앱이다. 어느 기계에서 검증하든 런북 이미지를 먼저 맞춘다.

이에 따라 기기 검증·release 빌드·데모 영상은 RAM 16 GB 노트북에서 수행한다(2026-08-04 결정).
레이아웃·터치 타겟·스크롤·Android lifecycle은 JVM으로 확인할 수 없으므로 그대로 남는다.

### 5. 제출물

문서 3종(`MISSION_AND_TECHNICAL_NOTE`·`INSTALL_AND_USE_GUIDE`·`BUILD_AND_SUBMISSION_INFO`)은
2026-08-03에 작성됐다. `BUILD_AND_SUBMISSION_INFO` §8 "동결된 산출물"만 최종 빌드 뒤 실제 값으로
채우면 된다. `DEMO_VIDEO`는 미착수이고 기기가 필요하다. 기술노트의 뼈대는 이 문서의 "핵심 설계
결정"과 "열린 결정"이다.

## 협업 규칙

- **`release_v3/`를 수정하지 않는다.** 공식 Kit의 읽기 전용 참조본이고, 생성물·캐시를 남기지 않는다.
- **커밋하지 않는 것**: keystore·비밀번호·API key·실행 token, `work/APP.apk`, `work/PUBLIC_RUN/`,
  `SAMPLE_EXPORT/`, Gradle/Python 캐시. `.gitignore`가 막고 있으니 `-f`로 우회하지 않는다.
- 판단 로직을 바꾸면 그 커밋에서 JVM 테스트를 통과시키고, 이 문서의 해당 절을 함께 고친다.
- `core/`에 한국어 문자열이나 메뉴 지식을 넣지 않는다. 그 경계가 production parity 설명의 근거다.
- Dacon 공지·토크 답변이 `COMPETITION_CONTEXT.md`와 다르면 공지가 우선한다. 문서와 변경 기록을 같이
  갱신한다.

## 확인이 필요한 자격 항목

기술과 무관하게 제출 자체를 막을 수 있으므로 먼저 확인한다.

1. **개인전 1인**이다(`COMPETITION_CONTEXT.md` §3). §19는 "대회 기간 중 source/result를 개인적으로
   타 참가자와 공유 금지"이고, 공개하려면 Dacon 코드공유 게시판 등 공식 플랫폼을 쓰라고 한다.
   협업자의 역할·참가 여부와 저장소 공개 범위를 확인해 기록한다.
2. 이 저장소가 **public이면** 대회 기간 중 소스가 공식 경로 밖에서 공개되고, `release_v3/`(공식 Kit)와
   공식 용어해설집 PDF까지 재배포된다. private 여부를 확인한다.
3. §20의 제출물 창작·비침해 보증을 누가 하는지와 실제 작성 주체가 일치해야 한다.
4. Mission 제출 위치·비공개 설정·파일명, 개인 최종 Drive 링크 권한 (`COMPETITION_CONTEXT.md` §0, §22).

## 변경 기록

| 일시 KST | 변경 |
|---|---|
| 2026-07-30 | generic core·13종 operation·Probe adapter·제품 화면·합성 catalog 구현. JVM 51개 통과 |
| 2026-07-30 | 제품 흐름 버그 4건 수정(slot 주소 지정, 총액, 품절 반영, 예산 정직성). JVM 58개 |
| 2026-07-30 | 자연어 입력·결정적 추천 추가. JVM 75개 |
| 2026-07-30 | 채팅 UI 배선. 질문에 탭으로 답하는 흐름 |
| 2026-07-30 | 평가·리뷰와 "기억할까요" 흐름, 어절 gapped 매칭. JVM 87개 |
| 2026-07-30 | 내 평점 표시(당시 순위 무개입) 구현. 이후 제품 결정으로 동일 식당·메뉴 추천 근거 반영은 구현 대기. JVM 97개 |
| 2026-07-31 | 개인 사용자·한 식당 범위를 유지하면서 단일 대표 메뉴를 `OrderDraft.items[]`로 확장하기로 결정. 단체주문·타인 profile·다중 식당은 제외하고 line별 취향·부분복구·리뷰 귀속·idempotency 구현 계약 추가 |
| 2026-07-31 | 구현 확정 3건: 결정적 파서(모델 미탑재), 대본 중심 최소 UI, 8/3 밤 코드 freeze. 내 평점 랭킹 양 arm 공통·smoothing 계약 명시, probe adapter·`ReferenceRuns`·catalog schema bump를 필수 변경 목록에 추가 |
| 2026-07-31 | catalog schema 3: `menu_types`(course·stable slots)·메뉴별 `menu_type`/`line_option_slots`·수량 slot·다온 간세기 추가, 사이드를 메뉴 항목으로 전환. core에 draft 구조 선언(`DraftLineDecl`)과 line별 projection·specificity 우선순위 추가 — probe 경로는 line 미선언 시 기존과 동일. `ProductSurface`가 `addLine`/`chooseLineMenu`/`removeLine`/`setLineOption`/`setQuantity` 제공. `MultiLineDraftTest` 9개 신설, JVM 109개 통과 |
| 2026-07-31 | 3-scope 취향 저장: `PreferenceScopes` 토큰 체계(전역=기존 base·`.type.<menu_type>`·`.at.<식당>.<메뉴>`)와 `rememberForMenuType`/`rememberForMenu`/`revokeAutoApplyScope`/`correctPreference`/`deletePreference`/`storedPreferences`. scoped fact는 base field를 만들지 않고 scope가 맞는 line만 채운다. authored stable slot 밖의 MENU_TYPE 저장은 거부. `ScopedPreferenceTest` 9개, JVM 118개 통과 |
| 2026-07-31 | 리뷰 승인 파이프라인 교체: `scheduleOutcome` 지연 outcome 대신 `submitReview`(다중 항목이면 대상 메뉴 확정 전 후보·평점 귀속 보류) → `reviewScopeChoices`(catalog가 허용하는 범위만 제시) → `acceptFromReview`(동의한 범위의 scoped `EXPLICIT_STABLE`+permission, `derivedFactIds`로 리뷰에 연결) → `deleteReview`는 그 fact만 정확히 제거. 주문 확정 시 app-local 평가 요청을 지연 outcome으로 예약(도착 1회 기록, TTL 만료·리뷰 응답 시 해소). `ReviewMemoryTest` 15개로 재작성, JVM 122개 통과 |
| 2026-07-31 | 내 평점을 동일 `(식당, 메뉴)` 추천 근거로 반영: `ratingAdjust = (평균 − 3.0) × n/(n+1)`을 조건·취향 점수가 남긴 동순위에서만 tie-break로 사용, "내 평점" 근거 badge 표시. 차단·자동 옵션 적용에는 불사용, 양 arm 동일 랭킹 계약을 `RatingDisplayTest`로 고정. JVM 124개 통과 |
| 2026-07-31 | 대본 중심 UI: 초안을 항목 카드(항목별 옵션·수량 1–3·항목 빼기)로 렌더, 사이드 추가 chip, 리뷰 4지선다(이 식당·메뉴만/같은 메뉴 유형/모든 메뉴/저장하지 않음)와 다중 항목 평가 대상 선택, 평가 요청 도착 배너, `daon` 고정 제거(식당 선택 chip), MemoryActivity에 범위별 취향·리뷰 관리 추가. E1–E4 대본 버튼을 새 스토리보드(간 세기 질문, E2 ★4 평가, E4 수제비+만두)로 갱신, 평점 ★4 token(`rating.nice`) authoring |
| 2026-07-31 | probe 경로 무변경 검증: `ProductionProbeAdapter`·`PublicProbeRunner`·`ReferenceRuns`·계약/metamorphic 테스트는 수정 없이 그린 — line 기제는 draft 구조 선언 시에만 활성화된다. `MISSION_ADAPTER.json`의 `STABLE_VALUE`·`DELAYED_OUTCOME` 의미만 3-scope·만료 알림에 맞춰 보강. `assembleDebug`·`assembleDebugAndroidTest` 통과 |
| 2026-07-31 | release 서명 배선: `keystore.properties`(비커밋)에서 읽는 `signingConfigs.release`, 설정이 없으면 `packageRelease`가 명시적으로 거부해 서명 안 된 APK를 만들지 않는다. `keystore.properties.example` 템플릿과 `.gitignore` 항목 추가. 일회용 keystore로 실제 서명·`apksigner verify`(v2, 인증서 SHA-256 노출)까지 확인한 뒤 흔적 삭제 — keystore 자체는 8/4 노트북에서 생성 |
| 2026-07-31 | catalog 확충(식당 3→6, 메뉴 9→19, 유형 4→5)과 E1–E4 대본 이전. 기존 3식당·메뉴는 그대로 두고 새 무대를 더한 뒤 대본만 옮겨, fixture로 쓰는 테스트는 무변경. 새 대본: E1 마라향(고수 커스터마이징 학습→**메뉴 유형** 범위 승인) → E2 금손분식(다른 식당·같은 마라 유형에 고수 자동 적용, 두 메뉴·유료 치즈 추가) → E3(일회성 예외·수저 권한 철회·맵기를 중간맛으로 정정) → E4 마라향(**이 집엔 중간맛이 없어 맵기만 재질문**, 고수는 유지, 사이드 품절 부분복구). 부담 비교를 누적 resolution 총합에서 **초안이 물은 항목 수**로 교정(메뉴 1개 1건 → 메뉴 2개 0건) — 항목을 더 담은 것을 기제의 이득으로 세지 않기 위함 |
| 2026-08-01 | Dacon 발급 `MISSION_LOCK.json` 수령. 저장소 루트에 원본 그대로 보관하고 공식 v3 schema 검증 통과 (`candidate_025`, `mission_025`, receipt `DACON-SCPC2026-025`) |
| 2026-08-01 | pre-emulator 정합성 보강: 제품의 line schema·사용자 확인·constraint·평가요청 예약을 해당 `ProductionCore.execute` step 안에서 원자적으로 저장해 before/after digest와 persisted state를 일치시켰다. 리뷰 승인 fact에 `originReviewId`를 기록하고 최신 직접 지시·정정·다른 outcome을 이전 리뷰 삭제로부터 격리했다. 6개 식당의 재고 있는 모든 main menu 완주 전수검사를 추가했다. debug·release JVM 각 148개, `assembleDebug`·`assembleDebugAndroidTest` 통과 |
| 2026-08-01 | Mission의 알림 권한 거부 제약을 실제로 시연할 수 있도록 평가 요청 알림을 선택적 보조 surface로 구현했다. 평가 요청은 먼저 app-local 정본에 commit되고, 알림은 그 `pending` 상태만 idempotent하게 반영한다. 권한 요청은 사용자의 명시적 버튼으로만 시작하며 거부 시 인앱 배너·주문 상태·중복 방지가 그대로 유지된다. launcher·notification icon도 추가했다 |
| 2026-07-31 | 확충 과정에서 찾은 결함 4건 수정: (1) 옵션 `price_delta`가 총액에 전혀 반영되지 않던 버그 — `rice.large`의 500원이 죽은 데이터였다. 항목별 `(메뉴+옵션합)×수량`으로 계산하고 `extrasAmount` 노출. (2) 카탈로그 프리셋 밖 금액을 말하면 `say()`가 예외를 던지던 버그 — 파서는 임의 금액을 해석하는데 저장 단계 검사가 프리셋 목록만 봤다. `accepts()`로 일원화. (3) 주문한 메뉴에 없는 옵션을 말하면 조용히 주문 전체 항목으로 들어가 가격까지 누락되던 버그 — 이제 "그 선택이 있는 메뉴가 없다"고 되묻는다. (4) `CATALOG_CANNOT_FULFIL_ASK_AGAIN` 같은 내부 상수가 화면에 그대로 노출되던 것 — 문장으로 교체하고 SCREAMING_SNAKE 노출 검사 추가 |
| 2026-07-31 | 화면 검토(`DemoScriptTranscriptTest`)로 찾은 결함 5건 수정: (1) **조건이 안정 취향으로 저장되던 버그** — "앞으로도"가 문장 전체에 걸려 예산·음식 성격까지 STABLE이 되어 며칠 뒤 주문에 말한 적 없는 예산이 자동 적용됐다. `USER_CONDITION` slot은 문장 scope와 무관하게 절대 stable로 저장하지 않는다(UX_SPEC E1의 "예산과 음식 성격은 현재 주문 session에서만 사용" 계약과 코드가 어긋나 있었다). (2) 총액 행이 내부 digest(`total.<hash>`)를 그대로 보여주던 것을 금액 표시로 교체(`displayValue`). (3) 확인 완료 행이 "확인 완료 · 확인 완료"로 중복 출력되던 것 정리(`statusLine`). (4) 같은 trait가 "오늘 입력"과 "직접 저장"으로 두 번 계산·표시되던 추천 근거 dedupe. (5) 조사 오류("맵기을 순한맛로") — 라벨에서 받침을 보고 을/를·으로/로를 고르는 `Particles` 도입. `setLineOption`이 해당 항목에 없는 slot을 받아 중복 행을 만들 수 있던 구멍도 함께 막음. JVM 131개 통과 |
| 2026-08-03 | 전 식당 완주 검증: 화면과 같은 답변 루프로 6식당 × main 메뉴 단독과 식당별 전체 메뉴를 완주, 전부 `ACT` 도달·결함 0건. 8/1의 `AllRestaurantCompletionTest`와 병합하며 **전체 메뉴 장바구니 경로**만 그 파일에 남기고 중복 파일은 삭제 |
| 2026-08-03 | 기기 육안 테스트 피드백 반영 — 표시 전용, 판단 로직·probe 경로 무변경: ① "현재 상황"의 내부 정보(주문 session·process epoch·비교 arm·마지막 판단·catalog digest)를 하단 접이식 "검증 정보 (심사용)"로 이동 ② 내부 line ID 노출 제거(`항목 l1` → `항목 1`, `slotLabel` 포함) ③ 상단 "지금 단계" 배너(식당→메뉴→옵션 확인→확정→평가)와 "다음 할 일" 한 줄 추가, 주문 확정 버튼을 초안 하단에서 배너로 이동 ④ 대화형 액션(문장 전송·질문 답·재사용 범위 답) 후 대화 위치로 자동 스크롤 ⑤ 여러 버튼 핸들러가 `act()` 재렌더 **뒤에** chat을 추가해 방금 행동의 피드백이 다음 상호작용까지 화면에 안 보이던 표시 버그 수정. JVM 144개 통과 |
| 2026-08-03 | 알림: 8/3 작업이 낡은 7/31 기반 위에서 진행되어 "구현이 없다"는 전제로 `POST_NOTIFICATIONS` 선언을 지웠으나, 8/1 커밋에 이미 `EvaluationNotification` 구현이 있었다. 병합 시 **선언을 되살려** (a)안(ledger 정본 + 배너, 알림은 부수 표시)으로 통일 |
| 2026-08-03 | 추천 후보를 **파서 성공 여부와 분리**했다. 앞선 수정(조건 발화 → 후보)은 파서가 값을 뽑아냈을 때만 동작해서, 카탈로그 어휘 밖의 문장을 쓰면 여전히 아무 제안도 못 받는 막다른 길이 남아 있었다. 이제 **초안에 메뉴가 없는 동안에는 화면이 항상 후보를 제시한다**(`renderCandidates`가 chat turn의 결과가 비면 현재 state로 직접 순위를 만든다). 식당만 고르고 아무 말도 안 해도 메뉴 후보가 보이고, 말을 하면 순위가 나아질 뿐이다. 후보 카드도 6.7% 회색 배경에서 주문서 카드와 같은 흰 배경·테두리·둥근 모서리로 바꿔 "에이전트가 건네는 카드"로 읽히게 했다 |
| 2026-08-03 | 기기 확인에서 나온 결함 1건 수정 — **조건만 말하면 추천 후보가 뜨지 않았다.** `say()`가 `Intent.RECOMMEND`(문장에 "추천"·"골라줘"·"뭐 먹을까")가 있을 때만 후보를 돌려줘서, 입력창 예시이자 설치가이드 첫 문장인 `2만원 이하로 따뜻한 국물, 앞으로도 맵지 않게 해줘`로는 후보가 0개였다. 제출 Mission은 "대화로 원하는 음식과 현재 조건을 입력하면 앱은 적합한 식당·메뉴 후보를 추천한다"이므로 선언과 구현이 어긋나 있었다. **초안에 메뉴가 없고 이번 발화에서 이해한 값이 있으면** 후보를 제시하도록 고쳤다(메뉴를 담은 뒤에는 그 주문에 대한 대화이므로 이름으로 요청할 때만). `ChatIntakeTest`에 회귀 테스트 추가. JVM 150개 통과 |
| 2026-08-03 | **화면을 직접 보고** 고친 레이아웃 결함 5건: `Ui.kt`의 실제 dp·색·순서를 그대로 옮긴 360dp 복제본을 브라우저로 렌더해 스크린샷으로 검토했다(기기·에뮬레이터 없이 레이아웃을 확인하는 수단, `app/build/uireview/`는 비커밋). 찾은 것 — ① 주문서 행 라벨이 `항목 1 · 떡 종/류`로 깨짐(항목 헤더가 이미 있는데 접두사를 반복) → 행에서는 base slot 라벨만 표시 ② 값이 `11,000/원`으로 잘림 → 값 칸 weight 4→5, 출처 문구를 12sp 아랫줄로 분리해 한 줄에 들어오게 함 ③ `이 항목 빼기`가 4등분 칩에서 `이 항목 빼/기`로 깨짐 → 수량 칩과 행 분리 ④ 단계 배너가 mono 12sp라 본문에 묻힘 → 연초록 배경 블록(`Ui.banner`)으로 분리 ⑤ 같은 화자 연속 말풍선마다 이름 반복 → 메신저처럼 한 번만(접근성은 `contentDescription`이 매 말풍선 유지). 상단 면책 문구도 2줄로 축약. JVM 149개 통과 |
| 2026-08-03 | 앱에서 E1–E4 대본 제거(약 110줄)와 조작 재배치. 각본은 "준비된 happy path만 되는 앱"으로 읽힐 소지가 있고 심사관은 설치가이드를 보고 직접 입력하면 된다. 다만 조사 결과 `advanceTime`·`applyCatalogEvent`·`nextOrderSession`·`correctPreference`가 **대본 블록에서만** 호출되고 있어(식당 chip은 주문이 열려 있으면 숨고 `resetEverything`은 UI 미연결) 통째로 지우면 E2–E4와 지연 outcome 시연이 기기에서 불가능해진다. 그래서 각본 문장 버튼만 지우고 조작은 성격대로 나눴다: **새 주문 시작**은 주문 확정 후 뜨는 제품 UI(`renderNextOrder`), **취향 값 정정**은 `내 취향과 기억`의 철회·삭제 옆으로, **시간 경과·재고 변화**는 network·process 종료와 함께 **"합성 환경 조작 (시연·검증용)"** 패널로 통합. 품절 버튼은 하드코딩 대신 `catalog.eventsFor(현재 식당)`로 authored event를 나열한다. E1–E4 문장은 `INSTALL_AND_USE_GUIDE.md`로 옮겼다. JVM 149개 통과 |
| 2026-08-03 | 주문서를 대화 속 카드로: `Ui.card`·`Ui.editableRow` 추가, 초안의 모든 행에 **변경** 버튼을 붙여 그 자리에서 이 식당이 제공하는 값 chip을 열고 고르게 했다(빈칸 채우기와 값 수정이 같은 동작). "이대로 주문하기" 버튼을 카드 안으로 옮겨 상단 배너와 중복 제거 |
| 2026-08-03 | 옵션 확충(option slot 14→21): `국물 양`·`파`·`떡 종류`·`어묵 추가`·`사리`·`튀김 정도`·`단무지` 신설. 메인 메뉴당 옵션 1–3개 → 4–5개, 사이드도 옵션 1개씩 보유. **`파`는 세 main 유형 전부의 stable slot**이라 한 번 저장하면 모든 식당을 건넌다. `소스 따로`는 이미 요청 메모 값이라 사이드는 `튀김 정도`로 대체(스크립트의 문구 충돌 감지가 잡아냄). 대본 E1에 "앞으로도 파는 빼줘"와 사리 취향 학습을 추가해 부담 비교가 **E1 2건 → E2 1건**으로 벌어졌다. 완주 테스트가 새 옵션까지 자동 커버, 주문 완주형 테스트 10개는 화면 chip과 같은 방식으로 남은 질문을 답하는 공용 `answerRemaining` 헬퍼로 전환해 이후 카탈로그 확장에 견디게 했다. JVM 149개 통과 |
| 2026-08-03 | 대화창 말풍선(표시 전용): `Ui.chatLine`을 한 줄 TextView에서 말풍선으로 교체 — 사용자는 우측 브랜드색, 에이전트는 좌측 회색에 이름 라벨, 화면폭 78% 제한, 화자 쪽 모서리만 각지게. **색에 의존하지 않는 화자 식별**은 좌우 위치·이름 라벨·`contentDescription`("화자 + 문장") 세 겹으로 유지. 에이전트의 열린 질문·재사용 범위 질문·추천 도입·식당 선택 안내를 섹션 제목 대신 말풍선으로 바꿔 chip이 quick reply로 읽히게 하고, 스레드 보존 12줄 → 40줄. JVM 149개 통과 |
| 2026-08-03 | UI 크래시 경로 전수 차단(freeze 전 최우선 3/3): `act`/try-catch 밖 변경 호출 9곳(Main 4·Memory 5, 공통 `guarded` 헬퍼) 감싸기 — 낡은 항목·중복 탭이 크래시 대신 Toast. `answerScope`는 실제 저장된 값만 대화에 말함. `clearTransientScreenState()`로 session 전환 5곳(식당 chip·대본 버튼)에서 후보·범위 질문·리뷰 진행 상태 초기화. JVM 144개 통과 |
| 2026-08-03 | 채팅방형 화면 구성(표시 전용, 제품 결정): 입력창을 메신저처럼 화면 하단에 고정하고 스레드는 위에서 스크롤. 스레드 순서를 대화 흐름대로 재배치(대화 → 질문 → 추천 후보 → 주문 초안서 → 주문 완료 후 평가 → 상황, 대본·network·검증 도구는 아래로), 빈 초안 placeholder 제거. 흐름 자체(자연어 → 후보 → 선택 → 항목별 초안 → 빈 칸 질문 → 확정 → 평가 요청 → 리뷰 범위 승인)는 기존 판단 경로 그대로다. JVM 144개 통과 |
| 2026-08-03 | 기술노트 초안: 루트 `MISSION_AND_TECHNICAL_NOTE.md` — 제출 Mission 선언 전문과 일치 확인 후 §2 필수 10항목(E1–E4·session 경계·인과변화 4건, CORE 1–6 위치, 권위·tombstone·부분복구, 실패 시 예상 state, ASPR claim·claim-off 허용 차이·VIL, mobile counterfactual, 검증 경로, probe 3버튼·parity, model 0회·runtime freeze)을 구성하고 claim→테스트 근거 매핑 표로 마감. 결과경계 준수(점수·PASS/FAIL 미기재) |
| 2026-08-03 | 에뮬레이터에서 첫 식당 선택 뒤 추천 카드가 나오지 않던 결함 수정. `RESET_AND_START`가 이전 run을 비운 뒤 새 step의 목표·대상 context를 적용하도록 순서를 고치고, `startNewOrder`가 `TARGET_ENTITY`를 명시한다. reset 직후 context와 채팅 입력 전 추천 후보를 고정하는 회귀 테스트 2개 추가. debug·release JVM 각 152개 및 `assembleDebug` 통과 |
| 2026-08-04 | **메뉴 이름을 타이핑하면 주문 항목이 담기지 않던 결함 수정.** `option.main`의 kind가 `menu_option`이라, 재사용 범위를 말하지 않은 문장(`마라탕으로 할게`)에서 파서가 메뉴에도 "이번 주문만 적용할까요, 기억할까요?" 질문을 붙였고 `say`가 그 값을 미해결로 보고 버려 `addLine` 분기에 도달하지 못했다. 메뉴 slot을 범위 질문 대상에서 제외(`NaturalLanguage.read`). `INSTALL_AND_USE_GUIDE` §3이 심사관에게 그대로 입력하라고 지시하는 E4 문장 2개가 이 경로였고, 기존 대본 테스트는 그 자리에서 `addLine(token)`을 직접 불러 파서를 우회했기 때문에 잡히지 않았다. 타이핑만으로 검증하는 `GuideScriptSentencesTest` 신설. debug·release JVM 각 154개 통과 |
| 2026-08-04 | **기기의 실제 network 연결을 표시 전용으로 반영.** `ui/DeviceLink.kt`가 render 시점에 `ConnectivityManager`를 읽어 `메뉴 → network 상태`에서 합성 값과 나란히 보여주고, 연결이 끊긴 동안에는 appBar에도 `기기 …`를 덧붙인다. 판단은 그대로 합성 상태만 읽으므로 `core/`·probe 계약·claim-off 비교가 무변경이다. `NetworkCallback` 대신 동기 읽기를 택해 lifecycle 해제 실수로 인한 크래시 경로를 만들지 않았고, 읽기 실패는 전부 `확인 불가`로 수렴한다. `ACCESS_NETWORK_STATE`(install-time) 추가. 순수 매핑 테스트 5개, `INSTALL_AND_USE_GUIDE` §4 갱신. debug·release JVM 각 159개 및 `assembleDebug` 통과 |
| 2026-08-04 | 개발 노트북에서 에뮬레이터를 실제로 띄워 §4의 "뜨지 않는다" 서술을 측정값으로 교체. 부팅은 되지만 호스트 여유 RAM 0.27 GB에서 게스트 시스템 앱이 ANR을 내 사용 불가이고, 설치된 이미지가 런북 지정(`android-35;default`)과 다른 `android-36;google_apis`임을 확인. 기기 검증·release 빌드·데모 영상은 16 GB 노트북에서 수행하기로 결정. §5의 "제출물 4종 미착수"도 실제 상태(문서 3종 작성 완료)로 정정 |
