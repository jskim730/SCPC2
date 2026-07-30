# SCPC 2026 예선 2차 참가자 Release v3

이 폴더에는 응시자 문서·schema, 서명된 공식 Runner, starter AAR, public 13-step
rehearsal·harness와 sample app이 들어 있습니다. 참가자는 Dacon 대회 페이지의 공식 다운로드 링크에서
이 Kit를 받습니다. Dacon은 게시 전에 공식 배포본을 확인합니다. 참가자는 ZIP이나 APK의 SHA-256,
서명 인증서 지문, release 식별값을 계산하거나 입력하지 않습니다. 필요한 기술 식별값은 제공 도구와
starter AAR가 자동으로 처리합니다.

release ID는 `SCPC2026-R2-CANDIDATE-RELEASE-V3`이고 상태는 `FROZEN_PARTICIPANT_RELEASE`입니다. 참가자 의무와 7일 개발기간은 Dacon
페이지가 게시한 과제 공개시각 `T`에 시작합니다. Runner는 이미 서명되어 Kit에 포함되므로 참가자가
별도 Runner나 서명환경을 만들 필요가 없습니다.

## 읽고 실행하는 순서

1. `candidate_kit/README_FIRST.md`부터 응시 규칙을 읽습니다.
2. `probe/STARTER_AAR_INTEGRATION.md`를 따라 AAR와 production adapter를 연결합니다.
3. `sample_app/README.md`의 clean sample source와 local debug-only sample APK를 참고합니다.
   sample은 정답 구현이 아닙니다.
4. `public_harness/make_local_integration_fixture.py`와 `runnerctl.py`로
   `probe/PUBLIC_PROBE_INPUT_13_STEP.json`을 실행합니다.
5. `candidate_kit/FINALIZE_SAMPLE_EXPORT.py`로 Runner 결과·증거에서 `SAMPLE_EXPORT/`를 자동
   완성합니다. SHA-256·release ID·runtime JSON을 손으로 작성하지 않습니다.
6. Dacon 페이지의 공식 다운로드 링크로 받은 Kit만 사용합니다.

## 공개 경계

- `probe/scpc-dacon-runner-3.0.0-draft.apk`는 서명된 공식 Runner입니다.
- `probe/scpc-probe-starter-3.0.0-draft.aar`는 공식 starter AAR입니다.
- 공개 input에는 oracle·expected relation·anchor·Q·cut·official token이 없습니다.
- 이 release에는 keystore·private key·password/env·official hidden input·operator package,
  Gradle/build cache와 Python bytecode가 없습니다.
- release attestation은 최종 APK에서 public harness가 자동 계산해 실행 입력과 assignment에 같은
  값으로 주입합니다. 참가자는 이 기술 식별값을 직접 만들거나 바꾸지 않습니다.
