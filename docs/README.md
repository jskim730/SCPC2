# 문서 색인

예선 2차 7일 동안 만든 설계·검증 문서입니다. 작성 순서대로 읽으면 판단의 흐름이 그대로 보입니다.

## competition/ — 공식 기준

| 파일 | 내용 |
|---|---|
| [`COMPETITION_CONTEXT.md`](competition/COMPETITION_CONTEXT.md) | 공식 규칙 snapshot(§0–§22). 채점 축, hard gate, 제출 규격, 자격 조건. 기술 판단의 근거는 여기서 인용했습니다 |

## mission/ — 동결된 범위

| 파일 | 내용 |
|---|---|
| [`SCPC2026_R2_MISSION_First_penguin.pdf`](mission/SCPC2026_R2_MISSION_First_penguin.pdf) | 2026-07-31 제출한 1쪽 Mission 선언 |
| `SCPC2026_R2_MISSION_First_penguin.docx` | 위 PDF의 편집 원본 |

Dacon이 발급한 동결 정본은 저장소 루트의 [`MISSION_LOCK.json`](../MISSION_LOCK.json)입니다. 두 문서가
다르면 `MISSION_LOCK.json`이 정본입니다.

## design/ — 무엇을 왜 그렇게 만들었나

작성 순서: 사전조사 → UX 명세 → 평가 대응 전략 → 어댑터 설계.

| 파일 | 내용 |
|---|---|
| [`SCPC2026_R2_RESEARCH_AND_STRATEGY.md`](design/SCPC2026_R2_RESEARCH_AND_STRATEGY.md) | 과제 공개 직후 사전조사. 어떤 제품을 만들지 고르던 단계의 기록 |
| [`PERSONAL_DELIVERY_AGENT_UX_SPEC.md`](design/PERSONAL_DELIVERY_AGENT_UX_SPEC.md) | 화면·대화 흐름·상태 전이 명세. "이번 Mission에서 제외" 목록 포함 |
| [`PERSONAL_DELIVERY_AGENT_EVALUATION_STRATEGY.md`](design/PERSONAL_DELIVERY_AGENT_EVALUATION_STRATEGY.md) | 채점 항목별로 무엇이 그것을 담당하고 무엇으로 증명하는지 |
| [`MISSION_ADAPTER_DESIGN_DRAFT.json`](design/MISSION_ADAPTER_DESIGN_DRAFT.json) | Probe 어댑터 연결 설계 초안. APK·`MISSION_LOCK`·실제 소스가 생기기 전에 쓴 문서 |
| `mockups/` | 취향 적용 범위 선택 화면 mockup |

## verification/ — 어떻게 증명했나

| 파일 | 내용 |
|---|---|
| [`PROBE_METAMORPHIC_TEST_PLAN.md`](verification/PROBE_METAMORPHIC_TEST_PLAN.md) | 변형 Probe V1–V4 설계. "공개 입력에 하드코딩된 게 아님"을 보이는 방법 |
| [`DEVICE_TEST_SCRIPTS.md`](verification/DEVICE_TEST_SCRIPTS.md) | 기기 검증 대본 10종. 각 대본이 어느 채점 축을 겨냥하는지 명시 |
| [`SETUP_AND_REHEARSAL_RUNBOOK.md`](verification/SETUP_AND_REHEARSAL_RUNBOOK.md) | emulator 구성부터 공식 Runner 완주까지의 실행 절차 |
| [`SELF_SCORE_2026-08-04.json`](verification/SELF_SCORE_2026-08-04.json) | 공개 anchor 기준 자체 점검 결과(비공식) |

## 루트에 남아 있는 문서

`tools/`의 빌드 스크립트가 루트 경로로 참조하므로 옮기지 않았습니다.

| 파일 | 왜 루트인가 |
|---|---|
| [`../BUILD_AND_SUBMISSION_INFO.md`](../BUILD_AND_SUBMISSION_INFO.md) | `SOURCE.zip` 구성 allowlist에 포함 |
| [`../THIRD_PARTY_NOTICES.md`](../THIRD_PARTY_NOTICES.md) | `SOURCE.zip` 구성 allowlist에 포함 |
| [`../MISSION_AND_TECHNICAL_NOTE.md`](../MISSION_AND_TECHNICAL_NOTE.md) | 제출 PDF의 원본 (`tools/build_submission_pdfs.py`) |
| [`../INSTALL_AND_USE_GUIDE.md`](../INSTALL_AND_USE_GUIDE.md) | 제출 PDF의 원본 (`tools/build_submission_pdfs.py`) |
| [`../android/IMPLEMENTATION_STATUS.md`](../android/IMPLEMENTATION_STATUS.md) | 소스 옆에 두는 구현 현황 문서 |
