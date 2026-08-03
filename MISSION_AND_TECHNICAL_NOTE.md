# MISSION_AND_TECHNICAL_NOTE — 초안

제품: 개인 배달 주문 에이전트 · 참가자: First_penguin · 초안 갱신: 2026-08-03 KST

> **초안 상태.** 8/4 기기 검증 뒤 PDF로 변환해 제출한다. 이 문서는 제출된 Mission 선언
> (`SCPC2026_R2_MISSION_First_penguin.pdf`, T+48 동결)과 일치하며, 공식 결과경계를 지킨다 —
> expected relation·PASS/FAIL·공식 점수 추정을 어디에도 적지 않는다.

---

## 1. Mission — 제출 선언과 동일

> 비슷한 취향과 조건으로 배달 주문을 반복하는 개인 사용자가, **현재도 유효하고 자동 적용이
> 허용된 취향만** 재사용해 앱 중단과 조건 변화 뒤에도 한 식당의 주문 초안을 **최소한의
> 확인만으로** 완성하도록 돕는다.

**대상 사용자와 핵심 문제.** 배달앱의 식당·메뉴·옵션 구조에 익숙하지 않거나 복잡한 선택 과정에
부담을 느끼면서도 배달 주문을 반복하는 사람. 기존 재주문 기능은 이전 주문을 그대로 불러오지만
**평소 취향과 그때만의 선택을 구분하지 못하므로**, 예산이나 조건이 달라지면 사용자가 다시 조정해야
하고, 탐색 중 앱이 종료되면 비교하던 후보와 판단 과정을 다시 확인해야 한다.

**Primary value.** 현재 상황과 취향에 맞는 **유효한** 개인 주문 초안을 완성하기까지 필요한
탐색·선택의 반복 부담을 줄이는 것. 단, 사용자의 현재 요청과 변경·삭제 의사를 반영하고, 현재 주문
가능한 메뉴와 옵션을 지킨다.

## 2. 제품 흐름 — 채팅방형 주문

앱은 메신저 형태다. 입력창이 화면 하단에 고정되고, 대화 스레드가 위로 쌓인다.

1. 사용자가 자연어로 조건을 말한다 — "1만5천원 이하로 따뜻한 국물, 앞으로도 맵지 않게 해줘".
2. 에이전트가 조건·저장 취향·내 평점을 근거로 **식당·메뉴 후보**를 근거 배지와 함께 제시한다.
3. 사용자가 후보를 고르면 **항목별 주문 초안서**가 만들어진다. 저장된 취향 중 이번에도 유효하고
   자동 적용이 허용된 값만 각 항목에 채워지고, 값의 출처(오늘 입력·직접 저장·리뷰 승인)가 행마다
   표시된다.
4. **빈 칸과 불확실한 값만 질문이 된다.** 재사용 범위를 말하지 않은 값은 "다음에도 쓸까요?"로
   묻고, 모호한 표현·이 식당이 제공하지 않는 옵션은 추측 없이 되묻는다.
5. 확인이 끝나면 상단 배너의 확정 버튼으로 **가상 주문을 한 번만 기록**한다 (app-local, 실제
   주문·결제 없음).
6. 주문 뒤 **평가 요청이 도착**하고, 사용자가 평점·한 줄 리뷰를 남긴다. 리뷰에서 추출한 취향
   후보는 값·대상 식당·메뉴·적용 범위를 명시해 물은 뒤 **동의한 범위에만** 저장한다.

화면 상단의 "지금 단계" 배너(식당 → 메뉴 → 옵션 확인 → 확정 → 평가)가 이 흐름의 현재 위치와
다음 할 일을 항상 보여준다.

## 3. E1–E4 장기 인과관계와 session 경계

### Episode 정의 (Mission 선언 §3과 동일)

- **E1 학습.** 전역 기본 취향 / 같은 메뉴 유형 취향 / 특정 식당·메뉴 예외와 자동 적용 허용
  범위를 **현재 주문만의 조건과 구분해** 저장. 평점은 해당 식당·메뉴의 추천 근거로, 리뷰 추출
  후보는 범위를 물어 동의한 범위에만.
- **E2 재사용.** 현재 지시 > 식당·메뉴 예외 > 같은 유형 취향 > 전역 기본 순으로 **유효하고
  허용된 값만** 항목에 채움. 다른 대상·목표의 기록과 미승인 리뷰 후보는 제외.
- **E3 예외.** 일회성 지시가 저장 취향과 충돌하거나 자동 적용 허용이 철회되면 현재 지시를
  우선해 **해당 항목만** 다시 판단. 안정 취향과 다른 항목의 허용 범위는 보존.
- **E4 복구.** 품절·process 종료 뒤에도 **영향받은 항목만** 다시 선택. 미실행 주문을 완료로
  오인하거나 중복 실행하지 않고, 삭제한 값·오래된 기록을 부활시키지 않음.

### 대본 (앱의 E1–E4 버튼과 데모 영상이 이 순서를 따른다)

| Ep | 무대 | 보이는 것 |
|---|---|---|
| E1 | 마라향 실험점 | 조건 입력 → 추천 → 고수 빼기 학습 → 리뷰에서 "고수 향이 세서 힘들었어" → **"같은 메뉴 유형이면 어디서든"** 범위 승인 |
| E2 | 금손분식 실험점 | **다른 식당**, 같은 마라 유형 두 메뉴에 고수 빼기 자동 적용, 유료 치즈 추가는 이번 주문만 |
| E3 | 금손분식 실험점 | "이번 주문만 아주 맵게"(일회성 예외) · 수저 자동 적용 철회 · 저장 맵기를 중간맛으로 정정 |
| E4 | 마라향 실험점 | 고수는 유지 적용, **이 집엔 중간맛이 없어 맵기만 재질문**(자동 대체 없음) · 사이드 품절 → 그 항목·총액만 재계산 · process 종료 후 재실행 연속성 |

### session 경계

step의 `session_id`가 달라지거나 `ADVANCE_SESSION`이 오면 새 주문 session이 열린다. 이번 주문만의
조건·일회성 지시(one-off)는 session과 함께 만료되고, 안정 취향(stable)과 permission·평점·리뷰는
남는다. 예산·음식 성격 같은 **조건은 문장에 "앞으로도"가 있어도 절대 stable로 저장하지 않는다** —
조건은 현재 주문 session의 것이다.

### Downstream causal change (두 개 이상 요구 — 네 개 제시)

1. E1의 전역·메뉴 유형 취향이 **E2의 새 식당** 초안 값과 질문 목록을 바꾼다.
2. E2 리뷰에서 승인한 식당·메뉴 예외가 **E4의 동일 식당·메뉴 조합** 초안 값을 바꾼다.
3. E3의 수저 자동 적용 철회 뒤 **E4에서는 수저만** 다시 묻는다.
4. E4 사이드 품절 시 **사이드·가격·총액만** 다시 계산되고 본 메뉴와 독립 옵션은 보존된다.

## 4. Architecture — 판단은 generic core, 의미는 데이터

```
ui/         화면(채팅 스레드·초안서·기억 관리). 판단하지 않는다.
delivery/   배달 표현 계층. 여기만 한국어·메뉴·금액을 안다.
              SyntheticCatalog(불변조건 강제) · NaturalLanguage(문장→구조화)
              Recommender(결정적 순위) · ProductSurface(제품 조작→13종 operation)
core/       generic production core. 값 철자를 해석하지 않는다.
              AsprEngine(선택→투영→부분 무효화) · ProductionCore(13종 op dispatcher,
              ledger, evidence) · ProductionState(영속 state 문서 하나)
probe/      ProductionProbeAdapter · PublicProbeRunner (public UI와 protected
            component가 같은 adapter·core를 호출)
platform/   Android 저장소·evidence 파일·release identity
```

- `core/`에는 **한국어 문자열·메뉴 지식이 0줄**이다. 모든 어휘·메뉴·금액·이벤트는 사람이 작성한
  단일 asset `assets/synthetic/catalog.json`(식당 6·메뉴 19·option slot 14·menu type 5)에 있고,
  `SyntheticCatalog.parse`가 token 유일성·가격 양수·모호 표현 거부·개인정보 유사 label 금지 등
  불변조건을 제품 코드에서 강제한다.
- 이 경계가 **production parity의 근거**다: 화면이 쓰는 조립 함수와 probe가 치는 경로가 같은
  core를 지나고, core는 domain을 모르므로 공개/official 값 차이로 분기할 수 없다.
- 런타임 무작위 생성·외부 조회를 쓰지 않는 이유: paired comparison이 "같은 시작 snapshot bytes·
  같은 seed·같은 event order"를 요구하고, 한 파일이 APK와 SOURCE.zip에 byte-for-byte 같이 들어가
  검토 가능해야 하기 때문이다.

## 5. 대표 기제 — 권위·범위 기반 취향 조정 및 부분복구기 (ASPR)

**Claim (Mission 선언 §5와 동일).** ASPR은 최근 주문을 그대로 불러오는 대신, 저장된 취향마다
① 적용할 식당·메뉴(범위), ② 어느 지시가 더 최신인지(권위), ③ 자동 적용이 허용됐는지(허용)를
구분한다. 이번 주문에도 유효하고 허용된 취향만 각 항목 초안에 반영하고, 불확실한 값은 묻는다.
정정·철회·삭제·품절이 발생하면 **영향받은 메뉴·옵션만** 다시 확인하고 나머지는 보존한다.

### 동작 원리

- **선택(Select).** slot 식별은 addressing role에서만 파생한다
  (`PRESERVED_SCOPE → TARGET_ENTITY → PRIMARY_GOAL → 현재 target → 현재 goal`).
  authority는 불투명 token을 처음 본 순서로 monotonic 번호에 사상한다 — `PUBLIC_AUTHORITY_V1`의
  "V1"을 파싱하지 않는다.
- **투영(Project).** 제품 화면이 draft 구조(`DraftLineDecl`: 항목 ID·메뉴·menu type·slot binding)를
  선언하면 항목별 field가 만들어지고, scope가 맞는 취향이 specificity 순
  (`현재 주문 직접 지시 > 현재 정정·철회·삭제 > 식당·메뉴 override > MENU_TYPE > 전역 > ASK`)으로
  채워진다. 같은 범위 안에서는 더 높은 authority가 이긴다.
- **부분 무효화(Partial invalidation).** 품절 event·항목 삭제·철회는 의존하는 field만 다시 열고,
  독립 field·다른 항목·저장 기억은 보존한다.

### 3-scope 취향 저장

```text
PreferenceKey(slotId, scopeLevel ∈ {GLOBAL_DEFAULT, MENU_TYPE, RESTAURANT_MENU_OVERRIDE},
              menuTypeId?, restaurantId?, menuId?)
```

- `MENU_TYPE` 취향은 catalog author가 부여한 `menu_type` token이 같고, 그 유형의 stable slot이며,
  현재 식당이 그 slot을 제공할 때만 식당을 건넌다. 표시 이름의 유사성으로는 절대 건너지 않는다.
- 식당마다 옵션 가짓수가 다르다(`offered_values`). 저장 값이 현재 식당에 없으면 **자동 대체하지
  않고 그 field만 다시 연다** (E4의 "중간맛 없음 → 맵기만 재질문"이 이 규칙의 화면이다).

### full / claim-off의 허용된 차이

claim-off는 **같은 APK, 같은 코드 경로**에서 `asprEnabled=false`로 실행되는 비교 기준이다.

| | full (ASPR) | claim-off |
|---|---|---|
| lifetime·scope·permission typing, dependency edge | 있음 | **없음** |
| 현재 session에서 말하지 않은 값 | 유효·허용이면 자동 적용 | 자동 적용하지 않고 다시 물음 |
| 삭제·tombstone·idempotency·재시작·receipt·추천 순위·평점 | **양 arm 동일 (공통 infrastructure)** | 동일 |

두 arm은 state namespace가 완전히 분리되어 상호 오염이 없고, 같은 catalog snapshot digest에서
출발한다.

### Metric — VIL

**사용자 해결 부담(VIL) = 유효한 초안 완성까지 사용자가 추가로 해결한 입력 항목 수 + 최종 확인
횟수의 합.** 측정은 **초안이 사용자에게 물은 항목 수** 기준이다 — 항목을 더 담아 생긴 확인을
기제의 이득으로 세지 않는다 (예: 메뉴 1개에 1건 질문 vs 메뉴 2개에 0건 질문). ASPR의 목표는
사용자의 현재 요청·삭제 의사를 어기거나 주문을 중복 처리하지 않으면서 E2 재사용과 E4 부분복구에서
VIL을 claim-off보다 낮추는 것이다. guardrail 위반 0이 전제이며, 위반을 동반한 이득은 이득으로
주장하지 않는다.

## 6. 기억 계약 — 권위·삭제·부활 차단

- **userConfirmed 구분.** 사용자가 질문에 답해 정한 값과 그 외를 구분한다. 철회·다른 fact의
  삭제는 전자를 보존하고, 같은 slot의 더 높은 authority는 언제나 값을 갱신한다.
- **삭제와 tombstone.** 삭제된 값은 tombstone만 남기고, export·재실행·replay 뒤에도 부활하지
  않는다. 리뷰를 지우면 그 리뷰가 만든 후보·scoped 취향(`derivedFactIds`로 연결)도 **정확히 그
  descendant만** 함께 제거된다. 사용자가 별도로 재확인한 독립 취향은 남는다.
- **commit identity.** 확정 draft 값들의 digest가 action의 identity다. 같은 draft에 반복 판단
  요청이 와도 같은 action을 돌려주고 추가 commit이 없다 — 재시작·중복·순서변형에서 exactly-once.
- **재고 event의 권위.** 품절은 취향이 아니라 이번 주문 선택과 같은 precedence의 더 높은
  authority로 들어간다. 지나간 이벤트가 저장 취향을 오염시키지 않는다.
- **평가 요청은 지연 outcome.** 주문 확정이 app-local 평가 요청을 예약하고, 도착은 ledger에
  정확히 한 번 기록되며, TTL이 지나면 스스로 만료되어 이후 초안을 막지 않는다.

## 7. 실패·중단 시 예상 state

| 상황 | 예상 state |
|---|---|
| process 종료 → 재실행 | 디스크의 영속 state 문서에서 재조정. 대화·초안·확정 항목이 이어지고, 이미 commit된 draft는 다시 묻거나 중복 commit하지 않는다. 앱 안 "이 process 종료" 버튼이 실제 process kill로 이를 시연한다 |
| network `DELAYED`/`UNKNOWN` | 열린 확인이 있으면 질문은 network 없이 가능하므로 `ASK` 우선. 확인이 없으면 `WAIT` — 초안은 그대로 |
| network `OFFLINE` | 유효한 cache가 있으면 `WAIT`, 없으면 `ABSTAIN`. 미확인 commit 없음 |
| 예산·희망시간 위반 | `ABSTAIN` — 조건을 만족하는 안이 없음을 정직하게 표시하고 멈춘다 |
| 알림 권한 거부 | 평가 요청의 정본은 ledger, 표시는 in-app 배너. 알림은 그 정본을 비추는 부수 surface이므로 거부돼도 주문 기록·평가 요청·복구가 그대로 유지되고 알림만 뜨지 않는다 (§9 참조) |
| 품절 event | 영향 항목만 `NEEDS_CONFIRMATION`으로 재개방, 총액 재계산, 다른 항목·기억 보존 |

## 8. CORE-1…6이 이 Mission에서 나타나는 위치

| CORE | 이 제품에서 | 검증 위치 (JVM) |
|---|---|---|
| CORE-1 선택적 맥락·distractor 배제 | 다른 식당·다른 메뉴 유형의 기록, 미승인 리뷰 후보, 만료된 조건은 초안에 서지 않는다 | `ScopedPreferenceTest`, `ChatIntakeTest`, `MetamorphicProbeTest` V2 |
| CORE-2 현재 권위·정정·철회·삭제·descendant 무효화 | E3 정정·철회, 리뷰 삭제 시 파생 취향 동반 제거, 부활 차단 | `ReviewMemoryTest`, `ProbeOperationContractTest` |
| CORE-3 반복부담 감소 + 필요한 안전확인 유지 | E2 자동 적용은 유효·허용 값만, 불확실은 질문 유지 | `ClaimOffComparisonTest`(VIL paired), `OptionAvailabilityTest` |
| CORE-4 예외판단·부분복구 | E3 일회성 예외 항목만 재판단, E4 품절·미제공 옵션 부분 재개방 | `ProductFlowProbeTest`, `MultiLineDraftTest`, `MetamorphicProbeTest` line 단위 |
| CORE-5 restart·duplicate·out-of-order·exactly-once | commit identity, 재시작 연속성, replay 무해 | `MetamorphicProbeTest` V3·V4, `MultiLineDraftTest` 재시작, `ProbeOperationContractTest` idempotency |
| CORE-6 delayed outcome·ledger·evidence 일치 | 평가 요청 1회 도착·만료·응답 해소, 화면=state=receipt | `ReviewMemoryTest`, `ProbeOperationContractTest` digest chaining, `DemoScriptTranscriptTest` |

## 9. 모바일 필요성과 PC counterfactual

배달 주문은 이동 중·짧은 시간에 휴대폰으로 이루어지고 전화·화면 잠금·앱 전환으로 쉽게 중단된다.
이 제품의 mobile constraint는: **background에서 OS가 process를 종료하거나 알림 권한이 거부된
경우에도, 중단 전 선택과 이후 변화를 올바르게 조정해 미완료 주문을 완료로 오인하거나 중복
처리하지 않는 것.**

- **process death 대응**은 §7의 재조정으로 성립하고, 실제 kill 버튼으로 시연 가능하다.
- **알림 권한 조건**은 surface 분리로 성립한다: 평가 요청의 **정본은 ledger**이고 화면 표시는
  in-app 배너다. `EvaluationNotification`은 그 정본을 **비추기만** 하며 요청을 만들거나 진행시키지
  않는다(`sync`는 멱등). 따라서 `POST_NOTIFICATIONS`가 거부돼도 주문 기록·평가 요청·복구는 그대로
  동작하고, 달라지는 것은 알림이 뜨느냐뿐이다. 화면이 허용/거부 상태를 그대로 표시하므로 심사관이
  권한을 거부한 채 같은 흐름을 완주하는 대비 시연이 가능하다.
- **PC counterfactual.** 일반 PC 대화 환경에는 Android lifecycle(임의 시점 process 종료)과 알림
  권한의 경계가 없으므로 같은 복구 가치를 제공하거나 검증하기 어렵다. 이 제품의 가치 축인
  "중단 뒤 부분 복구"는 모바일에서만 실재하는 제약 위에 있다.

## 10. 검증 경로 — Judge·OPS가 확인하는 곳

- **공개 연습 (앱 안).** 메인 화면 하단 "평가·내보내기" → `ProbeConsoleActivity`. 공식 계약이
  요구하는 세 컨트롤이 그 accessibility content description 그대로 붙어 있고 터치·키보드·
  UIAutomator로 도달 가능하다: **공개 입력 불러오기**(시스템 파일 선택기) → **불러온 step 실행**
  → **결과 내보내기**(`getExternalFilesDir`에 `PROBE_RESULT_<arm>.json`, 저장 위치를 화면에 표시).
  import 실패는 실패로 표시되며 성공한 실행으로 꾸미지 않는다.
- **production parity.** public UI와 protected component가 **같은 candidate adapter와 production
  core**를 호출한다. 앱은 score·expected relation·anchor를 계산하지 않는다. 기기에서
  `ProbeParityTest`(androidTest)가 이를 확인한다.
- **비교 실행.** 메인 화면 "비교 실행 (full / claim-off)" → 같은 입력을 양 arm에 돌린 결과와
  guardrail 위반 여부를 나란히 표시. arm 선택과 namespace 격리는 화면에서 확인 가능하다.
- **evidence.** 모든 판단은 receipt·ledger로 남고 probe 화면의 "증거 파일" 목록과 export로
  내보낼 수 있다. 화면이 보여주는 것과 state·receipt가 일치한다 (`DemoScriptTranscriptTest`가
  화면 조립 함수 기준으로 이를 고정).
- **기억의 사용자 통제.** "내 취향과 기억" 화면에서 저장 항목 전부를 출처·범위·자동 적용 여부와
  함께 열람하고 범위별 철회·삭제할 수 있다 — 숨은 설정이 없다.

## 11. model·backend·runtime freeze

- **결정적 intake는 의도된 설계다** (공식 규칙 §8 "deterministic 구현도 허용" 명문). 자연어
  해석은 catalog가 authoring한 어휘(값 표현 80·리뷰 표현 15·모호 표현 6)에 대한 어절 단위 gapped
  매칭이며, 해석 실패는 항상 `ASK`로 수렴해 설계된 안전 동작이 된다.
- 선택 이유: ① 채점 경로(Probe)는 불투명 token만 보내므로 model이 기여할 자리가 없고, ② paired
  comparison은 양 arm 동일 구성을 요구하며, ③ 재현성(같은 입력 → 모든 기기에서 같은 출력)·
  오프라인 완주·APK 크기·라이선스 신고가 전부 단순해진다. 무거운 backend는 동결·가용성 부담만
  늘린다.
- **model 호출 0회.** `PreferenceIntake`가 model 구현 교체 지점으로 유지되지만
  `modelConfigured=false`로 고정되어 inference가 없다. 따라서 판단당 4회·run당 60회 호출 상한과
  retry 정책은 **계수 대상 자체가 0**이다. 원격 backend 없음 — 평가 기간에 가용성이 요구되는
  참가자 관리 endpoint가 존재하지 않는다.
- **runtime freeze.** 최종 서명 APK와 `SOURCE.zip`이 같은 release로 묶이며, 합성 catalog
  snapshot digest가 비교 evidence에 기록되어 두 arm이 같은 데이터에서 출발했음을 보인다.

## 12. Claim → 검증 근거 매핑 (요약)

JVM 144개(에뮬레이터 불필요) + 기기 검증. 대표 매핑:

| 주장 | 근거 |
|---|---|
| core는 token 철자를 읽지 않는다 | `MetamorphicProbeTest` V1 전면치환 불변 |
| 다른 entity·goal의 기록은 서지 않는다 | V2 entity/goal 교환 |
| 순서변형·반복·중간 Reset에도 결과 불변 | V3 (18-step) |
| 26-step 장기 연결 | V4 |
| 전 식당 × 전 메뉴가 확정까지 완주한다 | `AllRestaurantCompletionTest` (6식당 × 재고 있는 모든 main 메뉴 + 식당별 전체 메뉴 장바구니) |
| VIL paired 비교·guardrail 0 위반·arm 격리 | `ClaimOffComparisonTest` |
| 리뷰 승인 전 무변경·범위별 저장·정확한 파생 삭제 | `ReviewMemoryTest` |
| 항목 단위 품절 부분복구·단일 commit | `MultiLineDraftTest`, `ProductFlowProbeTest` |
| 미제공 옵션 자동 대체 금지 | `OptionAvailabilityTest` |
| 화면 문장에 내부 상수·원시 token 없음 | `DemoScriptTranscriptTest` |
| 기기 parity·13-step 완주·V1–V4 변형 | 8/4 기기 검증 (`ProbeParityTest`, 공식 Runner) |

---

*이 문서는 결과경계를 준수한다: 공식 hidden 값·expected relation·PASS/FAIL·점수 추정을 포함하지
않으며, 여기 적힌 모든 검증은 공개 연습 범위의 자가 확인이다.*
