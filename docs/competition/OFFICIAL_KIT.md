# 공식 Kit — 이 저장소에 포함하지 않는 이유와 무엇이 들어 있었나

대회 공식 Kit(`release_v3/`)은 **이 저장소에 포함돼 있지 않습니다.** Dacon이 대회 페이지의 공식
다운로드 링크로 배포하고 게시 전에 배포본을 검증하는 자료이며, 재배포를 허용하는 조항이 규칙에 없습니다
(`COMPETITION_CONTEXT.md` §19–§20). 이 프로젝트의 창작물이 아니므로 공개 저장소에 다시 싣지 않습니다.

작업 당시에는 저장소 루트에 `release_v3/`로 두고 **읽기 전용 참조**로만 썼습니다. 커밋 이력에는
남아 있으므로 필요하면 되살릴 수 있습니다.

| | |
|---|---|
| release ID | `SCPC2026-R2-CANDIDATE-RELEASE-V3` |
| 상태 | `FROZEN_PARTICIPANT_RELEASE` |
| 배포 | Dacon 대회 페이지 공식 다운로드 링크 |
| 규모 | 55개 파일 / 약 5.3MB |

## 구성

| 경로 | 내용 |
|---|---|
| `candidate_kit/` | 응시 규칙 문서 8종, JSON schema 8종, template 5종, `SELF_SCORE.py`, `FINALIZE_SAMPLE_EXPORT.py`, `VALIDATE_SAMPLE_EXPORT.py` |
| `probe/` | Dacon 서명 공식 Runner APK, starter AAR, 공개 13-step 입력, starter 연동 문서 |
| `public_harness/` | `runnerctl.py`, `make_local_integration_fixture.py`, `apk_release_info.py` |
| `sample_app/` | 정답 구현이 아닌 참고용 sample Android 프로젝트와 debug-only APK |

## Kit이 있어야 돌아가는 것과 없어도 되는 것

**없어도 됩니다 — JVM 테스트 197개.** `OfficialPublicInputTest`는 공개 13-step 입력의 사본을
`android/test-fixtures/probe/PUBLIC_PROBE_INPUT_13_STEP.json`에서 읽습니다. 이 사본은 제출한
`SOURCE.zip` 안에도 들어 있어, Kit을 따로 내려받지 않아도 전체 suite가 통과합니다.

**필요합니다 — `tools/`의 스크립트 5개.** 아래는 Kit 도구를 감싸는 wrapper라서 Kit 없이는 실행되지
않습니다. 저장소 루트에 `release_v3/`가 있다고 가정하고 경로를 씁니다.

| 스크립트 | 필요한 Kit 경로 |
|---|---|
| `tools/make_probe_variants.py` | `release_v3/probe/PUBLIC_PROBE_INPUT_13_STEP.json`, `release_v3/candidate_kit/PROBE_INPUT.schema.json` |
| `tools/win_runner_shim.py` | `release_v3/public_harness/` |
| `tools/win_fixture_shim.py` | `release_v3/public_harness/` |
| `tools/win_finalize_shim.py` | `release_v3/candidate_kit/` |
| `tools/win_validate_shim.py` | `release_v3/candidate_kit/` |

이 wrapper들이 존재하는 이유는 공식 harness가 POSIX 경로와 확장자 없는 SDK 실행파일을 가정하는데
작업 환경이 Windows였기 때문입니다. Kit 자체는 한 바이트도 수정하지 않고 호출 측에서만 보정했습니다.

## starter AAR에 대하여

`android/app/libs/scpc-probe-starter-3.0.0-draft.aar`는 Kit이 제공한 것과 **동일한 바이너리**입니다
(SHA-256 `2930dac2176eb136…`). 앱이 빌드되려면 필요하고 제출한 `SOURCE.zip`에도 들어 있어, 이 두 벌은
그대로 둡니다. 사용 조건은 `THIRD_PARTY_NOTICES.md`에 명시했습니다 — competition-supplied operating
component이며 대회 약관을 따릅니다.
