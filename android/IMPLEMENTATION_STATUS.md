# 구현 상태와 남은 작업

최종 갱신: 2026-07-30 KST · 프로젝트 root: `android/` · 저장소: `github.com/jskim730/SCPC2`

이 문서는 **협업 인수인계 문서**다. 코드를 바꿀 때 이 문서의 "핵심 설계 결정"과 "남은 작업"을
같은 커밋에서 갱신한다. 공식 사실의 기준은 항상 루트의 `COMPETITION_CONTEXT.md`와 `release_v3/`다.

## 한눈에

| 항목 | 상태 |
|---|---|
| JVM 단위 테스트 | **97개 전부 통과** (emulator 불필요) |
| `assembleDebug` / `assembleDebugAndroidTest` | 통과 (debug APK 3.2 MB) |
| 기기 실행 검증 | **미실행.** RAM 16 GB 노트북에서 수행 |
| 공식 Runner 13-step 완주 | **미실행.** release 서명 뒤 수행 |
| release 서명 설정 | **없음.** 최종 APK 전 필수 |
| 제출물 7종 | `APP.apk` 빌드 경로만 확보. 문서 4종·`SAMPLE_EXPORT`·영상 미착수 |
| Mission 선언 제출 | 마감 `2026-07-31 10:00 KST`. `SCPC2026_R2_MISSION_rev4.docx` 최신, PDF 변환·비공개 게시 확인 필요 |

소스 규모: main 21파일 6,825줄 / JVM 테스트 10파일 3,267줄 / 기기 테스트 1파일 257줄.

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

### 리뷰와 평점

리뷰 텍스트와 별점을 **다르게** 취급한다. 이 구분이 설계의 기억 계약(`EXPLICIT_STABLE` /
`DELAYED_OUTCOME` / `RAW_HISTORY`)에 그대로 대응한다.

| | 리뷰 텍스트의 option 언급 | 별점 |
|---|---|---|
| 무엇에 대한 말 | option 의미 (간 세기) | 그 식당의 그 메뉴 |
| 저장 | 물어본 뒤 지연 outcome | 저장 안 함. `state.reviews`에서 파생 |
| entity 경계 | entity 무관 (새 식당에 전달) | (식당, 메뉴) 고정 |
| 영향 | option 값 제안, `ASK_BEFORE_APPLY` | **표시만. 순위 무개입** |
| 두 arm | ASPR | 공통 |

- 리뷰만으로는 아무것도 저장되지 않는다. 사용자가 `참고할게요`를 눌러야 지연 outcome이 예약되고,
  도착해도 자동 적용되지 않는다. "한 그릇 감상 → 모든 미래 주문"은 설계가 금지한 과도한 일반화다.
- 리뷰를 지우면 원문·예약된/도착한 outcome·그에 의존한 field까지 사라지고 tombstone만 남는다.
  재시작하고 시간이 또 흘러도 부활하지 않는다.
- 평점은 **표시 전용**이다. 순위에 넣으면 (a) VIL 이득이 mechanism 아닌 것에서 나와 C3 귀속이 깨지고,
  (b) 원시 기록의 무허가 적용이 된다. 나쁜 평점도 후보를 차단하지 않는다 — 차단은 예산·시간·품절 같은
  확정 사실에만 쓴다.
- 라벨은 **"내 평점"** 이다. 공개 평점으로 오해되지 않게 한다.

## 합성 데이터 (`app/src/main/assets/synthetic/catalog.json`, schema 2)

사람이 작성한 JSON asset 하나다. 런타임 무작위 생성·외부 조회를 쓰지 않는 이유:

- paired comparison이 "같은 시작 snapshot bytes·같은 seed·같은 event order"를 요구한다(C3).
- 데이터가 판단 경로 밖에 물리적으로 있어야 core가 값 철자로 분기하지 않음을 보이기 쉽다.
- 한 파일이 APK와 `SOURCE.zip`에 byte-for-byte 같이 들어가고 검토 가능하다.

현재: 식당 3, option slot 10, 값 표현 80, 리뷰 표현 15, 모호 표현 6, catalog event 2.

`SyntheticCatalog.parse`가 제품 코드에서 강제하는 불변조건 — 어휘를 추가할 때 여기에 걸린다:

- token 유일성, 음수 가격 금지, 메뉴 항목의 양수 가격·예상시간
- 존재하지 않는 slot·값·trait 참조 금지
- 식당명에 `실험` 표식 필수
- label에 `@`나 전화번호 패턴 금지
- **한 표현이 두 값을 가리키면 거부**
- 평점 score가 선언된 1..`rating_scale_max` 범위 안

품절·가격변경은 코드 분기가 아니라 `catalog_events`다. `ProductSurface.applyCatalogEvent`가 그 slot
하나에만 더 높은 authority로 전달하므로 본 메뉴와 독립 option은 보존된다.

## 검증 — JVM 97개, emulator 불필요

| 파일 | 개수 | 내용 |
|---|---:|---|
| `core/ProbeOperationContractTest.kt` | 28 | step 1:1 대응, digest chaining, epoch, 만료, 철회, 정정, 삭제, 부활 차단, idempotency, replay, network, 복구 필요, verdict 어휘 부재 |
| `core/MetamorphicProbeTest.kt` | 10 | V1 token 전면치환 불변, V2 entity/goal 교환, V3 순서변형·반복·중간 Reset, V4 26-step 장기 연결, line 단위 부분 무효화 |
| `core/ClaimOffComparisonTest.kt` | 6 | VIL paired 비교, guardrail 0 위반, arm state 완전 격리 |
| `delivery/SyntheticCatalogTest.kt` | 7 | shipped asset byte로 불변조건 검사, snapshot digest 결정성, 개인정보 유사 label 부재, 잘못된 catalog 거부 |
| `delivery/ProductFlowProbeTest.kt` | 7 | 제품 기준 E1–E4 완주, 가격·예상시간, 품절 부분복구, 예산 초과 `ABSTAIN`, 재시작 연속성 |
| `delivery/ChatIntakeTest.kt` | 17 | 금액 4형태·기간·부정 표현, 모호 표현 질문화, 미제공 option 질문화, 철회 의도 분리, 추천 순위·근거, 순위 결정성 |
| `delivery/ReviewMemoryTest.kt` | 12 | 리뷰 읽기, 동의 전 무변경, 수락 시 지연 outcome, 나중 제안만, 정확히 한 번, 삭제 시 파생 제거·부활 차단 |
| `delivery/RatingDisplayTest.kt` | 10 | 평균·척도·건수, 식당 경계, **평점이 순위를 바꾸지 않음**, badge 미포함, 차단 없음, 두 arm 동일, 삭제 반영 |

보조: `core/ProbeRunHarness.kt`(adapter와 같은 방식으로 core 구동, `relaunch()`로 process 교체 재현),
`core/ReferenceRuns.kt`(13-op reference run, V3 18-step, V4 26-step).

`androidTest/probe/ProbeParityTest.kt`는 컴파일까지 확인했고 **실행은 기기에서** 한다.

## 남은 작업

### A. 지금 이 저장소에서 (기기 불필요)

1. **MemoryActivity에 리뷰·평점 목록** — 현재 MainActivity에만 있다. §9 "사용자가 앱이 기억한 정보와
   상태를 확인·수정·삭제 가능"을 기억 화면에서도 만족시켜야 한다.
2. **release 서명 설정** — keystore 생성 + `signingConfigs.release`. private key·password는 커밋하지
   않고 본인 보관. `.gitignore`가 `*.jks`·`*.keystore`를 막고 있다.
3. **제출 문서 초안 3종** — `MISSION_AND_TECHNICAL_NOTE`, `INSTALL_AND_USE_GUIDE`,
   `BUILD_AND_SUBMISSION_INFO`. 위 "핵심 설계 결정"이 기술노트의 뼈대다.
4. 파서 어휘 확장 — 데모에서 쓸 문장을 먼저 확정하고 그 표현을 확실히 커버한다.
5. notification surface (선택) — mobile constraint는 알림 없이도 성립하지만 데모에 좋다.

### B. RAM 16 GB 노트북에서 (기기 필요)

1. 환경: Android Studio + SDK Platform 35 + Build-Tools 35.0.0 + platform-tools + cmdline-tools latest,
   `.venv`에 `release_v3/candidate_kit/requirements.txt`
2. AVD 생성 후 **network 설정 사전 시드** — 빼먹으면 13-step이 시작조차 안 된다:

```bash
adb shell settings put global wifi_on 1
```

```bash
adb shell settings put global mobile_data 1
```

3. `./gradlew.bat :app:connectedDebugAndroidTest` — parity test 실행
4. `installDebug` 후 채팅으로 E1→E4 완주. 대본 버튼으로도 재현 가능
5. 앱 안 `평가·내보내기`에서 `release_v3/probe/PUBLIC_PROBE_INPUT_13_STEP.json` import → run → export
6. release 서명 APK를 `work/APP.apk`로 두고 `make_local_integration_fixture.py` →
   `runnerctl.py run` → `PROBE_RESULT.json` 확인 (`SETUP_AND_REHEARSAL_RUNBOOK.md` Phase 1)
7. V1–V4 변형 입력으로 반복 (`PROBE_METAMORPHIC_TEST_PLAN.md`)
8. `FINALIZE_SAMPLE_EXPORT.py` → `VALIDATE_SAMPLE_EXPORT.py`
9. `DEMO_VIDEO.mp4` 3분 이내
10. **최종 APK를 정한 뒤에는 다시 build/sign 하지 않는다.**

## 열린 결정

### model 사용 — 미결

Probe 경로에는 자연어가 들어오지 않는다. official 채점은 불투명 role token만 보내므로 model은 Q/80에
기여하지 못하고, 사람 Judge가 보는 화면과 데모 영상에서만 작동한다.

| 방식 | 얻는 것 | 대가 |
|---|---|---|
| 결정적 파서 (현재) | 자격·재현성 리스크 0, 오프라인, inference 0회 | 카탈로그에 없는 표현은 되묻는다 |
| 온디바이스 소형 LLM | key·backend 불필요, 오프라인 유지 | APP.apk 300–800 MB(번들 필수. 첫 실행 다운로드는 OFFLINE 요구와 충돌), 모델 라이선스 신고, 기기 간 출력 동일성 미보장 |
| 우리 backend + 클라우드 | 표현 커버리지 최대 | endpoint 상시가동 의무(08-05~심층검증), domain·전송데이터 신고, 판단당 4회·전체 60회 계수 구현, 장애 시 G3/G5 위험 |

CII **C2(3점)가 "불필요한 복잡성 부재"** 이고, `PERSONAL_DELIVERY_AGENT_EVALUATION_STRATEGY.md`가
"채팅 UI와 자연어 parser/model"을 Signature 공로에서 명시적으로 제외했다. 채점 경로가 건드리지 않는
모델을 싣는 것이 C2에 불리하게 읽힐 수 있다.

### 다른 사용자 평점 — 하지 않기로 결정 (2026-07-30)

1. §9가 타인의 고객정보 사용을 금지하고, §4가 "실제 계정·개인정보·외부서비스에 의존하는 제품"을 과제
   미충족으로 명시한다.
2. `PERSONAL_DELIVERY_AGENT_UX_SPEC.md`의 "이번 Mission에서 제외" 목록에 **"단체주문과 타인의 취향
   profile"** 이 이미 들어 있다.
3. 합성 인기도라도 "새 식당에서 유효한 개인 취향 재사용으로 확인이 줄어든다"는 인과 서사와 경쟁한다.
   인기 있지만 현재 조건에 안 맞는 메뉴가 올라오면 우리가 고쳤다고 주장하는 실패 모드를 시연하게 된다.

대신 **"내 평점"** 을 표시 전용으로 넣었다.

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
| 2026-07-30 | 내 평점 표시(순위 무개입). 다른 사용자 평점은 하지 않기로 결정. JVM 97개 |
