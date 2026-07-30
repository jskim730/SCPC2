# SCPC 2026 예선 2차 응시자 Kit

> 실제 일정·마감시각·업로드 용량·문의채널은 Dacon 대회 페이지가 정본입니다.

> **공식 Kit 사용:** Dacon 대회 페이지의 공식 다운로드 링크에서 받은 Kit를 사용하십시오. Dacon은
> 게시 전에 공식 링크와 최종 배포본을 확인합니다. 참가자는 SHA-256이나 release ID를 확인·대조하지
> 않습니다. 참가자 의무와 7일 개발기간은 페이지에 공지된 과제 공개시각 `T`부터 시작합니다.

## 과제 한 문장

> 여러 session에 걸쳐 반복되는 모바일 생활 작업에서 과거 trajectory를 선택적으로 기억해 다음 수행의
> 사용자 부담을 줄이고, 목표·상황·권한이 바뀌면 멈추거나 재계획하며, 앱 종료 뒤에도 안전하게 이어지는
> Android Mobile Agent를 구현하십시오.

쉽게 말하면, 반복할수록 이전 경험 중 필요한 부분만 활용해 사용자의 수고를 줄이고, 상황이 바뀌면 멈춰 다시
판단하며, 앱을 껐다 켜도 안전하게 이어지는 모바일 AI 에이전트 제품(APK)을 7일 안에 만들어 제출하는
시험입니다.

개발기간은 과제 공개시각 `T`부터 7일입니다. `T+48시간`까지 1쪽 Mission 선언을 제출하고 핵심 범위를
고정(동결)합니다.

## 누가 언제 실행하고 점수를 만드나요?

응시자가 개발 중 APK를 올릴 때마다 Dacon이 official input을 실행해 점수를 알려 주는 방식이 아닙니다.
개발 중 공개 연습과 self-score는 응시자가 자기 PC·Android 단말에서 원하는 만큼 반복합니다. 이 결과는
공식점수가 아니며 Dacon에 채점을 요청하는 제출도 아닙니다.

```text
7일 개발 중
  응시자: 공개 연습도구(public_harness) 또는 앱의 공개 점검 UI로 반복 점검
  Dacon: official 점수 산정·hidden 결과 회신 없음
                 ↓
최종 제출 마감
  응시자: Dacon 페이지 규칙에 따라 최종 APP.apk와 제출물 7종 제출
                 ↓
마감 뒤
  Dacon: 최종 접수본 CODE/SEC 검사 → 적격 APK 전원 AUTO-CHECK
         → 자동 Q 계산 → SELECTED-REVIEW 후보 선정
  Judge: 선정된 후보만 사람 검증
  Dacon 자동평가 도구: 사람 검증을 반영한 FINAL_Q 확정
         → 별도의 pool 선정규칙으로 심층 기술검증 후보 반복 선정
  Dacon·응시자·Judge: pool 후보만 comparison·기술설명·CII 검증
  Dacon 자동평가 도구: 추가 pool 후보가 없을 때 최종점수·순위 계산
                 ↓
공지된 결과 발표
```

마감 전 재업로드가 허용되는지는 Dacon 페이지 규칙을 따르며, 허용되더라도 채점대상은 마감 receipt가
가리키는 **최종 접수본 한 벌**입니다. Dacon은 점수를 높이기 위한 반복 제출·반복 AUTO-CHECK를
제공하지 않습니다. 운영환경 장애가 확인된 경우에만 정해진 incident 절차와 새 배정으로 재실행하며,
이는 응시자에게 추가 개선기회를 주는 재채점이 아닙니다.

## 읽는 순서

1. `01_CHALLENGE_OVERVIEW.md` — 과제와 E1–E4
2. `02_DEFINE_YOUR_MISSION.md` — 7일 안에 완결 가능한 Mission
3. `03_TECHNICAL_AND_SAFETY_RULES.md` — Android·model·evidence 규칙
4. `04_SUBMISSION_GUIDE.md` — 제출물과 작성방법
5. `05_EVALUATION_PROCESS.md` — AUTO-CHECK(전원 자동 기본검증),
   SELECTED-REVIEW(선별 수동 검증)와 deep 단계
6. `06_FAQ.md` — 자주 묻는 질문
7. `07_PUBLIC_REHEARSAL_AND_SELF_SCORE.md` — 공개 연습과 예상 Q 계산
8. `08_PROBE_MODE_CONTRACT.md` — 전원 자동 기본검증을 위한 표준 합성 시험입구

## 제출 기록은 도구가 자동으로 완성합니다

응시자가 SHA-256, 파일 크기, APK 서명 지문, release 식별자나 JSON 목록을 손으로 계산해 적을 필요는
없습니다. 역할은 다음처럼 나뉩니다.

1. Dacon이 T+48 Mission 제출을 접수하고 정확한 `MISSION_LOCK.json`을 돌려줍니다.
2. 응시자는 앱의 실제 기능을 설명하는 `MISSION_ADAPTER.json`과 증거 파일만 준비합니다.
3. 공식 Runner가 공개 13단계 실행 뒤 `PUBLIC_PROBE_RESULT.json`을 만듭니다.
4. 응시자는 `FINALIZE_SAMPLE_EXPORT.py`를 한 번 실행합니다. 이 도구가 APK·SOURCE·Runner 결과를 읽어
   `RUNTIME_IDENTITY.json`, `EVIDENCE_INDEX.json`, `EXPORT_INDEX.json`과 필요한 SHA-256을 자동으로
   만들고, 완성된 `SAMPLE_EXPORT/`를 검사합니다.
5. `VALIDATE_SAMPLE_EXPORT.py`는 이미 완성된 폴더를 다시 읽기 전용으로 점검할 때 사용합니다.

응시자가 직접 작성하는 JSON의 시작 예시는
`templates/MISSION_ADAPTER_EXAMPLE.json`입니다. `templates/MISSION_RECEIPT_EXAMPLE.json`과
`templates/PUBLIC_PROBE_*_EXAMPLE.json`은 Dacon·Runner가 주는 파일의 모양을 이해하기 위한 예시이며,
복사해서 제출하는 양식이 아닙니다. 자세한 실행 명령과 파일 준비방법은 `04_SUBMISSION_GUIDE.md`를
따르십시오.

`*.schema.json`은 각 JSON의 형식을 도구가 검사할 때 쓰는 규격입니다. 참가자가 schema 파일이나
digest 값을 수정할 필요는 없습니다.

## 예선 2차 평가 방식의 특징

Probe Mode·AUTO-CHECK은 최종 운영방식입니다. 참가자는 Dacon 페이지의 공식 다운로드 링크로 받은
Kit를 사용하고, 공지된 과제 공개시각 `T`를 개발기간의 시작으로 봅니다.

- 참가자는 공개된 채점 기준(CORE-1…6 anchor)과 계산표(lookup)로 예상 Q/80을 스스로 계산해 볼 수 있습니다.
- 기술검사를 통과한 제출물은 공식 Runner가 같은 APK의 표준 점검 입구(Probe Mode)를 자동 실행하는
  AUTO-CHECK(전원 자동 기본검증)을 받습니다.
- 사람이 직접 보는 검증은 상위권·gate 위험군·미리 뽑아 둔 표본에만 적용합니다.
- `gate`는 점수와 별개인 필수 통과조건입니다. G0–G7의 의미와 모두 통과해야 한다는 규칙은
  `05_EVALUATION_PROCESS.md` 4절을 확인하십시오.
- 앱은 점수나 anchor를 내지 않고 관찰 가능한 state·action·evidence만 내보냅니다. 점수(Q)는 동결된
  자동 채점기준과 공개 계산표가 만들며, 사람도 운영자도 Q 숫자를 직접 넣지 않습니다.
- comparison(비교모드)은 모든 참가자가 앱에 넣어 두지만 AUTO-CHECK에서는 실행하지 않고, deep 후보에게만
  실행합니다.
- 실제 시험에 쓰는 값·순서·정답·합격 커트라인은 공개하지 않습니다.

## 공개 self-score

자가평가에는 **Python 3.10 이상만 필요하며 Android SDK는 필요하지 않습니다.** 참가자 ZIP을 압축
해제한 최상위 폴더에서 예시를 작업파일로 복사해 각 CORE anchor와 근거를 고친 뒤 실행하십시오.

```bash
cp candidate_kit/templates/SELF_SCORE_EXAMPLE.json my_self_score.json
python3 candidate_kit/SELF_SCORE.py my_self_score.json
```

여기서 나오는 Q1–Q6·Q/80은 개발 준비도를 스스로 확인하는 **자가평가**일 뿐, 공식 점수나 합격을 보장하지
않습니다. 실제 채점은 Dacon이 공개 연습과 다른 hidden 값으로 따로 하기 때문입니다.

## 제출물

```text
APP.apk
SOURCE.zip
MISSION_AND_TECHNICAL_NOTE.pdf
INSTALL_AND_USE_GUIDE.pdf
BUILD_AND_SUBMISSION_INFO.md
SAMPLE_EXPORT/
DEMO_VIDEO.mp4
```

정확한 폴더구조와 내용은 `04_SUBMISSION_GUIDE.md`를 따릅니다. 참가자는 Mission adapter와 증거 파일을
준비하고 `FINALIZE_SAMPLE_EXPORT.py`로 제출기록을 완성합니다. 각 파일의 SHA-256을 직접 계산하거나
외부 목록과 하나씩 대조할 필요는 없습니다. `VALIDATE_SAMPLE_EXPORT.py`는 공개 계약과 제출물의
일치만 검사하며 점수·hidden oracle·합격 cut은 포함하지 않습니다.

## 공식 Kit에 들어 있는 도구와 예제

Probe Mode는 이번 예선 2차에서 실제로 사용하는 공식 평가 방식입니다. 공식 Kit에는 앱에 연결할
부품뿐 아니라, 공개 입력으로 직접 연습하고 연결방법을 참고할 수 있는 도구와 예제도 함께 들어 있습니다.

| 제공물 | Kit 안의 위치 | 무엇에 쓰나요? | 언제 쓰이나요? | 응시자가 할 일 |
|---|---|---|---|---|
| starter AAR | `probe/scpc-probe-starter-3.0.0-draft.aar` | 참가자 앱의 실제 production core를 표준 Probe 입구에 연결 | 개발 초기에 연결하고 최종 APK를 만들 때까지 유지 | 앱 프로젝트에 연결하고 실제 기능을 candidate adapter에 매핑 |
| 공식 Runner APK | `probe/scpc-dacon-runner-3.0.0-draft.apk` | 공개 연습에서는 공개 input을 앱에 전달하고 결과를 회수하며, 마감 뒤에는 Dacon이 official AUTO-CHECK에 사용 | 응시자는 7일 개발 중 공개 연습에 사용하고, Dacon은 최종 제출 마감 뒤 공식 평가에 사용 | Kit에 든 파일을 그대로 사용하고 자체 Runner를 만들지 않음 |
| 공개 연습도구 | `public_harness/make_local_integration_fixture.py`<br>`public_harness/runnerctl.py` | 공개 13단계 입력과 로컬 실행 배정파일을 준비하고, 공식 Runner를 참가자의 Android 단말에서 실행 | Probe 연결을 시작한 뒤부터 최종 제출 전까지 필요할 때마다 사용 | 원하는 만큼 실행해 `PUBLIC_RUN/` 결과를 확인 |
| 공개 13단계 입력 | `probe/PUBLIC_PROBE_INPUT_13_STEP.json` | 공개 연습에서 import·run·restart·export 연결을 확인 | 개발 중 공개 연습에서만 사용하며 마감 뒤 official 입력으로는 사용하지 않음 | hidden 정답으로 간주하지 말고 앱의 일반화·복구 결함을 찾는 데 사용 |
| sample app | `sample_app/` | starter AAR·adapter·상태저장·Probe 결과 연결을 보여 주는 참고 구현과 로컬 debug APK | 개발 초기에 연결방법을 익힐 때와 연동 오류를 진단할 때 참고 | 구조와 테스트를 참고하되 sample을 자신의 정답 앱처럼 제출하지 않음. Mission별 evidence 파일 내보내기는 자신의 앱에 별도 구현 |

`public_harness/`는 별도의 공개 연습 전용 Runner APK가 아니라 Kit의 공식 Runner를 공개 입력으로 실행하는
참가자용 연습도구입니다. `sample_app/`도 과제 정답이나 출발용 완성제품이 아니라 연결방법을 확인하는
참고자료입니다. 두 폴더 자체를 별도 제출물로 내지 않으며, 공개 연습 결과 중 필요한
`PUBLIC_PROBE_RESULT.json`과 evidence만 `SAMPLE_EXPORT/`에 포함합니다.

- 파일명의 `3.0.0-draft`는 이번 Kit가 사용하는 동결된 contract 호환 식별자입니다. 이 문자열 때문에
  참가자 release가 미완성이라는 뜻은 아닙니다.
- 흩어진 pilot/RC 파일을 섞지 말고 Dacon 페이지의 공식 다운로드 링크로 받은 Kit만 사용합니다.
  공식 링크와 배포본 확인은 Dacon이 담당하며 참가자는 SHA-256이나 release ID를 대조하지 않습니다.
- 참가자가 자체 Runner나 별도 보안 protocol을 만들 필요는 없습니다.
- 모든 참가자는 public 세트를 직접 실행하고, official 세트는 마감 뒤 Dacon이 공식 Runner와
  protected component로 실행합니다.
- 운영 제공물의 재현 가능한 결함으로 교체가 필요하면 참가자 실패로 처리하지 않고, 수정된 전체 Kit를
  기준으로 7일 개발기간을 다시 보장합니다.

## 가장 먼저 완성할 것

1. 안내서만 보고 E1→E2→E3→E4(처음 배우기→다음에 활용→예외에서 재판단→회복)를 APK에서 재현
2. 새 값·정정·삭제가 실제 계획·판단·상태를 바꾸는지
3. 앱(process) 종료·재실행 뒤 현재 상태를 복구하는지
4. network·permission 실패에서 거짓 완료 없이 안전하게 저하되는지
5. 내보낸 증거(evidence export)와 화면·state·receipt가 서로 일치하는지
6. 같은 APK 안에서 Signature mechanism을 켠(full)·끈(claim-off) 두 실행이 서로 격리되는지
7. public Probe 세트를 불러와 실행·내보내기(import→run→export)했을 때 실제 제품 코드와 같게 도는지(parity)

화면 수나 기능 수보다, 이 일곱 항목이 처음부터 끝까지(end-to-end) 동작하는 것을 먼저 완성하십시오.
APK·SOURCE·증거의 SHA-256, 인증서 지문, release attestation과 release ID는 공식 도구가 실제
파일에서 자동 생성·검사합니다. 참가자는 예시나 문서에서 이런 기술값을 복사해 JSON·manifest·설명서에
직접 입력하지 않습니다.
