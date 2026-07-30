# 구현 상태와 다음 단계

작성: 2026-07-30 KST · 프로젝트 root: `C:\Users\Infocar\SCPC2\android`

## 이 문서의 목적

이 노트북(RAM 5.9 GB)에서는 **로직 구현과 JVM 검증까지** 진행했다. emulator 실행·13-step
Runner 완주·데모 영상은 RAM 16 GB 새 노트북에서 한다. 그 노트북에서 이어서 할 일을 아래
"다음 단계"에 적었다.

## 빌드 환경 (확인됨)

| 항목 | 값 |
|---|---|
| Gradle | 8.9 (wrapper) |
| AGP | 8.7.3 |
| Kotlin | 2.0.21 |
| JDK | Android Studio 번들 JBR (`$env:ProgramFiles\Android\Android Studio\jbr`) |
| compileSdk / targetSdk | 35 |
| minSdk | 28 |
| ABI | arm64-v8a, x86_64 |
| applicationId | `com.scpc.deliveryagent` |

빌드·테스트 명령:

```bash
cd C:/Users/Infocar/SCPC2/android && ./gradlew.bat :app:testDebugUnitTest
```

```bash
cd C:/Users/Infocar/SCPC2/android && ./gradlew.bat :app:assembleDebug
```

`JAVA_HOME`이 없으면 먼저 설정한다:

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
```

## 완료된 것

### 1. generic production core (`app/src/main/java/com/scpc/deliveryagent/core/`)

| 파일 | 역할 |
|---|---|
| `Canonical.kt` | 결정적 JSON 인코딩과 SHA-256 (state digest) |
| `Ids.kt` | state·evidence·receipt ID 정규화 (`^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$`) |
| `Model.kt` | typed fact, dependency edge, draft field, ledger record |
| `ProductionState.kt` | 영속 state 문서 하나 + 직렬화 |
| `Roles.kt` | 10개 semantic role의 generic 읽기 (값 철자 해석 없음) |
| `AsprEngine.kt` | Signature mechanism: 선택 → 투영 → 부분 무효화 |
| `ProductionCore.kt` | 13종 operation dispatcher, ledger, evidence |
| `ProbeResultShape.kt` | `PROBE_RESULT.schema.json` step result 형태 검사 |

핵심 설계 결정(문서에 남길 것):

- **slot 식별**: `PRESERVED_SCOPE → TARGET_ENTITY → PRIMARY_GOAL → 현재 target → 현재 goal`
  순으로 addressing role에서만 파생한다. 값 철자는 보지 않는다.
- **authority version**: 불투명 token을 처음 본 순서로 monotonic version에 사상한다
  (`PUBLIC_AUTHORITY_V1`의 "V1"을 파싱하지 않는다).
- **재사용 허용 신호**: `PRESERVED_SCOPE`가 명시되면 그 fact는 entity에 묶이지 않고
  자동 적용이 허용된다. 없으면 현재 entity 전용이고 적용 전 확인한다.
- **catalog line**: `TARGET_ENTITY`가 명시되면 그 값은 현재 catalog에서 가격이 붙은 line으로
  보고 `field.total` roll-up에 들어간다. `entityId`(배타적 귀속)와 `catalogEntityId`(가격 기준)를
  분리했다.
- **userConfirmed**: 사용자가 질문에 답해서 정해진 값과, commit에 딸려간 값을 구분한다.
  정정·철회·삭제는 전자를 보존하고 후자만 다시 연다.
- **commit identity**: action idempotency key = 확정 draft 값들의 digest. 같은 draft에 대한
  반복 REQUEST_DECISION은 같은 action을 돌려주고 추가 commit이 없다.
- **session 경계**: step의 `session_id`가 달라지거나 `ADVANCE_SESSION`이면 새 session을 열고
  내부 key(`label#seq`)를 올린다. one-off만 만료되고 stable은 남는다.
- **network**: 질문은 network 없이도 가능하므로 열린 확인이 있으면 `ASK`가 우선하고,
  확인이 없을 때 `DELAYED/UNKNOWN → WAIT`, `OFFLINE →` cache 있으면 `WAIT` 없으면 `ABSTAIN`.
- **claim-off**: 같은 코드 경로에서 `asprEnabled=false`. lifetime·scope·permission typing과
  dependency edge가 없고, 현재 session에서 말하지 않은 값은 자동 적용하지 않고 다시 묻는다.
  삭제·tombstone·idempotency·restart·receipt는 두 arm 공통 infrastructure다.

### 2. Probe 연결 (`app/src/main/java/com/scpc/deliveryagent/probe/`)

- `ProductionProbeAdapter.kt` — `ProbeAdapter` 구현. contract type을 core step으로 옮기는
  번역만 하고 production core를 호출한다. `releaseBinding`/`runtimeIdentity`/
  `missionAdapterSha256`은 구현하지 않는다(starter AAR가 APK에서 읽음).
- `PublicProbeRunner.kt` — 공개 연습 실행. adapter는 manifest metadata
  (`org.scpc.r2.probe.ADAPTER_CLASS`)에서 해석하므로 public UI와 protected 경로가 같은
  adapter를 쓴다. 입력 parsing은 AAR의 `ProbeInputParser.parsePublic`.
- `platform/ReleaseIdentity.kt` — package·version·서명 인증서를 `PackageManager`에서,
  mission adapter digest를 실제 asset byte에서 계산. 수기 입력값 없음.

> **주의:** AAR의 `ProbeAdapterLoader`·`ProbeResultValidator`·`ProbeTechnicalIdentity`는
> Kotlin `internal`이라 앱에서 호출할 수 없다(bytecode는 public처럼 보이지만 컴파일이 거부됨).
> 그래서 adapter 해석·결과 형태 검사·technical identity를 참가자 코드로 구현했다.

### 3. 합성 데이터 (`app/src/main/assets/synthetic/catalog.json`)

식당·메뉴·option·가격·재고·예상시간·예산·요청메모를 **사람이 작성한 JSON asset 하나**로 둔다.
런타임 무작위 생성이나 외부 조회를 쓰지 않는 이유:

- paired comparison이 "같은 시작 snapshot bytes·같은 seed·같은 event order"를 요구한다(C3).
  값이 실행마다 달라지면 인과 이득 재현이 불가능하다.
- 데이터를 asset으로 분리하면 판단 경로 밖에 물리적으로 있게 되어, generic core가 값 철자로
  분기하지 않음을 보이기 쉽다.
- 한 파일이 APK와 `SOURCE.zip`에 byte-for-byte 같이 들어가고 검토 가능하다.

품절·가격변경도 코드 분기가 아니라 데이터의 `catalog_events`다. `ProductSurface.applyCatalogEvent`가
그 slot 하나에만 더 높은 authority upsert로 전달하므로, 본 메뉴와 독립 option은 그대로 보존된다.

`SyntheticCatalog.parse`가 제품 코드에서 불변조건을 강제한다: token 유일성, 음수 가격 금지,
menu 항목의 양수 예상시간, 존재하지 않는 slot 참조 금지, 식당명에 `실험` 표식 필수,
label에 `@`나 전화번호 패턴 금지. `ProductSurface.remember`는 현재 catalog가 제공하지 않는 option을
아예 거부한다(유효성 guardrail "현재 catalog에 없는 option 적용 = 0").

금액은 표현 계층(`DraftPricing`)에서 계산한다. core는 구조적 roll-up digest만 유지하므로
한 줄이 바뀌면 probe result에서 관찰되고, 화면에는 실제 합성 금액이 나온다.

### 4. 자연어 입력과 추천

| 파일 | 역할 |
|---|---|
| `delivery/NaturalLanguage.kt` | `PreferenceIntake` 인터페이스와 결정적 구현 `RuleBasedIntake` |
| `delivery/Recommender.kt` | 조건·취향·지난 평가로 후보 점수와 근거 badge 산출 |

어휘(표현 80개, scope 표현, 의도 표현, **모호 표현과 그 이유**)는 전부 catalog 데이터다.
판단 코드가 문장을 해석하지 않고, 파서는 데이터에 있는 말만 알아본다. 한 표현이 두 뜻을 가지면
`SyntheticCatalog.parse`가 거부한다.

핵심 규칙 두 가지:

1. 재사용 범위를 말하지 않으면 값을 저장하지 않고 "이번 주문만인가요, 기억할까요?"를 묻는다.
2. 모호 표현·현재 식당에 없는 option·이해 못 한 표현은 모두 질문이 된다. 조용히 버리지 않는다.

추천은 조건(5점)·저장 취향(3점)·지난 평가(2점)로 점수를 매기고 동점은 가격→시간→token으로 깬다.
순수 함수이므로 두 arm이 같은 순서로 정렬되고 C3 재현성이 유지된다. 예산·시간·품절로 걸린 후보는
**왜 걸렸는지 붙여** 함께 반환한다.

**model 사용 여부는 미결이다.** 현재 `modelConfigured=false`, inference 0회. `PreferenceIntake`가
교체 지점이다. 판단 근거는 아래 "열린 항목" 참조.

### 5. 제품 화면 (`app/src/main/java/com/scpc/deliveryagent/`)

| 화면 | 내용 |
|---|---|
| `ui/MainActivity.kt` | 오늘의 주문. 주문 초안 표와 provenance, E1–E4 실제 조작 버튼, network 전환, 실제 process kill |
| `ui/MemoryActivity.kt` | 저장 항목·허용범위·도착한 평가·삭제 표식·제외 이유, 개별 철회·삭제, 전체 Reset |
| `ui/ProbeConsoleActivity.kt` | `SCPC_PROBE_IMPORT` / `SCPC_PROBE_RUN` / `SCPC_PROBE_EXPORT` (정확한 content description) |
| `ui/ComparisonActivity.kt` | full / claim-off arm을 사람이 명시적으로 선택 |
| `delivery/DeliveryDomain.kt` | 합성 catalog와 배달 표현 계층, 제품 조작 → 13종 operation |

### 6. JVM 검증 (emulator 불필요) — 75 tests, 전부 통과

| 파일 | 내용 |
|---|---|
| `ProbeRunHarness.kt` | adapter와 같은 방식으로 core를 구동. `relaunch()`로 실제 process 교체 재현 |
| `ReferenceRuns.kt` | 13-op reference run, V3 18-step, V4 26-step |
| `ProbeOperationContractTest.kt` | step 1:1 대응, digest chaining, epoch, 만료, 철회, 정정, 삭제, 부활 차단, idempotency, replay, network, 복구 필요, verdict 어휘 부재 |
| `MetamorphicProbeTest.kt` | V1 token 전면치환 불변, V2 entity/goal 교환, V3 순서변형, V4 장기 연결, line 단위 부분 무효화 |
| `ClaimOffComparisonTest.kt` | VIL paired 비교, 안전 guardrail 0 위반, arm state 완전 격리 |
| `SyntheticCatalogTest.kt` | 실제 shipped asset byte로 불변조건 검사, snapshot digest 결정성, 개인정보 유사 label 부재, 잘못된 catalog 거부 |
| `ProductFlowProbeTest.kt` | 제품 화면 기준 E1–E4 완주, 가격·예상시간, 품절 부분복구, 예산 초과 ABSTAIN, 재시작 연속성 |
| `ChatIntakeTest.kt` | 문장 읽기(금액 4형태·기간·부정 표현), 모호 표현 질문화, 미제공 option 질문화, 철회 의도 분리, 추천 순위와 근거, 순위 결정성 |

`app/src/androidTest/.../ProbeParityTest.kt`는 컴파일까지 확인했고 **실행은 기기에서** 한다.

## 다음 단계 (RAM 16 GB 노트북)

### A. 환경

1. Android Studio + SDK Platform 35 + Build-Tools 35.0.0 + platform-tools + cmdline-tools latest
2. `py -3 -m venv` 로 `.venv` 만들고 `release_v3/candidate_kit/requirements.txt` 설치
3. AVD 생성. RAM이 넉넉하니 `-memory 2048` 이상으로 여유 있게.
4. **network 설정 사전 시드**(안 하면 13-step이 시작조차 안 됨):

```bash
adb shell settings put global wifi_on 1
```

```bash
adb shell settings put global mobile_data 1
```

### B. 기기 검증

1. `./gradlew.bat :app:connectedDebugAndroidTest` — parity test 실행
2. `./gradlew.bat :app:installDebug` 후 화면에서 E1→E4 버튼을 순서대로 눌러 초안·provenance·
   철회·품절·부분복구가 화면에 보이는지 확인
3. 앱 안 `평가·내보내기`에서 `release_v3/probe/PUBLIC_PROBE_INPUT_13_STEP.json`을 import →
   run → export 해보기

### C. release 서명과 공식 Runner 완주

1. release keystore 생성 (private key·password는 제출하지 않고 본인 보관)
2. `app/build.gradle.kts`에 `signingConfigs.release` 추가 후 `assembleRelease`
3. 산출물을 `work/APP.apk`로 복사
4. `make_local_integration_fixture.py` → `runnerctl.py run` → `PROBE_RESULT.json` 확인
   (`SETUP_AND_REHEARSAL_RUNBOOK.md` Phase 1 절차 그대로)
5. V1–V4 변형 입력으로 반복 (`PROBE_METAMORPHIC_TEST_PLAN.md`)
6. **최종 APK를 정한 뒤에는 다시 build/sign 하지 않는다.**

### D. 남은 산출물

- `MISSION_AND_TECHNICAL_NOTE.pdf` — 위 "핵심 설계 결정"을 근거로 작성
- `INSTALL_AND_USE_GUIDE.pdf` — install → Reset → E1 → E2 → 정정/철회/삭제 → E3 →
  kill/relaunch → E4 → export → End, 그리고 full/claim-off 두 arm 실행 절차
- `BUILD_AND_SUBMISSION_INFO.md` — 위 빌드 환경 표 + dependency + AI 도구 신고
- `SAMPLE_EXPORT/` — `FINALIZE_SAMPLE_EXPORT.py` → `VALIDATE_SAMPLE_EXPORT.py`
- `DEMO_VIDEO.mp4` — 3분 이내
- `SOURCE.zip` — `android/` 전체에서 `build/`·`.gradle/` 제외, `MISSION_ADAPTER.json` 원본 1개 포함

## 열린 항목

### model 사용 결정 (미결)

Probe 경로에는 자연어가 들어오지 않는다. official 채점은 불투명 role token만 보내므로 model은
Q 점수 경로에 관여하지 않고, 사람 Judge가 보는 화면과 데모 영상에서만 작동한다. 반면 비용은
자격에 직접 걸린다.

| 방식 | 얻는 것 | 대가 |
|---|---|---|
| 결정적 파서 (현재) | 자격·재현성 리스크 0, 오프라인, inference 0회 | 카탈로그에 없는 표현은 되묻는다 |
| 온디바이스 소형 LLM | key·backend 불필요, 오프라인 유지 | APP.apk 300–800 MB(번들 필수, 첫 실행 다운로드는 OFFLINE 요구와 충돌), 모델 라이선스 신고, 기기 간 출력 동일성 미보장 |
| 우리 backend + 클라우드 | 표현 커버리지 최대 | endpoint 상시가동 의무(08-05~심층검증), domain·전송데이터 신고, 판단당 4회·전체 60회 계수 구현, backend 장애 시 G3/G5 위험 |

주의: CII **C2(3점)가 "불필요한 복잡성 부재"** 이고, 우리 설계 문서는 "채팅 UI와 자연어
parser/model"을 Signature 공로에서 명시적으로 제외했다. 채점 경로가 건드리지 않는 모델을 싣는 것이
C2에 불리하게 읽힐 수 있다.

- Mission 선언 제출 마감이 **2026-07-31 10:00 KST**다. `SCPC2026_R2_MISSION_rev4.docx`가
  최신이며 PDF 변환·비공개 게시가 남았는지 확인이 필요하다.
- `COMPETITION_CONTEXT.md` 22절의 미확인 항목(수상 인원 표기, 개인 Drive 위치, 언어 제한 문구)은
  그대로 남아 있다.
- `MISSION_ADAPTER.json`의 `source_paths`는 `SOURCE.zip` root 기준이다. 현재는 `android/`를
  root로 압축하는 전제로 `app/src/...`로 적혀 있다. 압축 방식을 바꾸면 함께 고쳐야 한다.
