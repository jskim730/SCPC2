# 제출 안내

## 1. 필수 제출물 7종

| 파일 | 내용 |
|---|---|
| `APP.apk` | 지원 Android에 설치되는 최종 서명 APK |
| `SOURCE.zip` | APP과 일치하는 source·build/config·license·backend 자료 |
| `MISSION_AND_TECHNICAL_NOTE.pdf` | Mission, E1–E4, architecture, Signature mechanism, mobile counterfactual |
| `INSTALL_AND_USE_GUIDE.pdf` | 처음 보는 Dacon OPS와 Judge가 설치·Reset·run·restart·export·comparison 절차와 확인 위치를 찾는 방법 |
| `BUILD_AND_SUBMISSION_INFO.md` | 재현 build 방법과 외부 dependency·backend/model 설정 설명 |
| `SAMPLE_EXPORT/` | 한 reference run의 index·state·event·receipt evidence |
| `DEMO_VIDEO.mp4` | E1–E4·변화·restart·통제를 보여 주는 **3분 이내** 참고영상 |

영상은 실제 APK 확인을 대신하지 않습니다.

이 제출은 개발 중 점수를 요청하는 중간채점 제출이 아닙니다. Dacon은 마감 receipt가 가리키는 최종
접수본 한 벌을 마감 뒤 CODE/SEC 검사하고, 적격 APK에 AUTO-CHECK를 실행합니다. 마감 전 재업로드
허용여부와 최종본 확정방법은 Dacon 페이지 규칙을 따릅니다.

Probe Mode가 최종 운영방식으로 채택되었으므로 `SOURCE.zip`에는 `MISSION_ADAPTER.json`, protected component/candidate
adapter source와 integration test를 포함하고, `SAMPLE_EXPORT/`에는 public pack의
`PUBLIC_PROBE_RESULT.json`을 포함합니다. 별도 여덟 번째 제출묶음은 추가하지 않습니다.
서명된 공식 Runner APK와 starter AAR는 Kit에 포함되는 운영 제공물이며, 참가자가 제출하는 binary가
아닙니다.

## 1.1 SAMPLE_EXPORT의 공통 JSON

```text
SAMPLE_EXPORT/
├── MISSION_LOCK.json
├── RUNTIME_IDENTITY.json
├── EVIDENCE_INDEX.json
├── EXPORT_INDEX.json
├── PUBLIC_PROBE_RESULT.json
└── evidence/...
```

이 다섯 파일을 모두 직접 작성하는 것은 아닙니다.

| 파일 | 누가 만드는가 | 참가자가 할 일 |
|---|---|---|
| `MISSION_LOCK.json` | Dacon | T+48 Mission 제출 뒤 받은 파일을 수정하지 않고 보관 |
| `PUBLIC_PROBE_RESULT.json` | 공식 Runner | 공개 13단계 실행을 끝내고 내보낸 파일을 보관 |
| `RUNTIME_IDENTITY.json` | 제출 완성 도구 | 직접 작성하지 않음 |
| `EVIDENCE_INDEX.json` | 제출 완성 도구 | Runner 결과에 나온 evidence ID와 같은 이름으로 증거 파일 준비 |
| `EXPORT_INDEX.json` | 제출 완성 도구 | 직접 작성하거나 SHA-256을 계산하지 않음 |

응시자가 직접 작성하는 연결정보는 `SOURCE.zip` 안의 `MISSION_ADAPTER.json`입니다.
`templates/MISSION_ADAPTER_EXAMPLE.json`을 참고해 자신의 실제 기능·callback·근거 위치를 적습니다.
`templates/MISSION_RECEIPT_EXAMPLE.json`과 `templates/PUBLIC_PROBE_*_EXAMPLE.json`은 형식을 설명하는
예시일 뿐 제출용 빈 양식이 아닙니다.

## 2. 문서에 반드시 포함할 내용

- T+48 선언과 일치하는 대상 사용자·장기 목표·Primary value
- E1–E4의 episode, session 경계와 두 개 이상의 downstream causal change
- CORE-1…6이 Mission에서 나타나는 위치
- current authority, delete·tombstone, partial recovery 규칙
- process death, network·permission failure의 예상 state
- Signature mechanism claim, full/claim-off allowed difference와 metric
- mobile lifecycle·local continuity·별도 constraint와 PC counterfactual
- 공개 연습도구·앱 public UI와 Dacon OPS가 live 검증 입력을 실행하고 Judge가 evidence를 찾는 정확한 path
- Probe Mode의 세 accessibility control과 production core parity
- model/backend/runtime freeze, invocation counting과 retry

## 3. 설치안내의 first-reader 기준

참가자의 도움 없이 다음을 재현할 수 있어야 합니다.

```text
install → synthetic starting state → Reset & Start official run
→ E1 → next session → E2 → correction/revoke/delete → E3
→ process kill/relaunch → E4 → export → End
```

버튼 이름·화면 위치·예상 화면상태·evidence 파일을 적되 official hidden 값을 예상해 적지 않습니다.

## 4. sample export

**왜 내나요?** sample export는 앱이 한 번의 실행에서 만든 evidence(상태·event·receipt·runtime identity)를
담은 **대표 실행 기록**입니다. 이것으로 Dacon은 앱을 직접 돌리지 않고도 (1) 앱의 evidence·receipt 형식이
규격에 맞고 내부적으로 일관되는지, (2) 그 기록들이 제출한 하나의 release에 실제로 묶여 있는지를 기계로
확인할 수 있습니다. `EXPORT_INDEX.json`이 나머지 파일을 SHA-256으로 묶어 두므로, 파일 하나만
바꿔치기해도 검사에서 드러납니다.

`SAMPLE_EXPORT/`는 압축 전 합계 **20 MiB(20,971,520 bytes) 이하**의 공개 13단계
실행기록입니다. 아래처럼 파일을 한 작업폴더에 모읍니다.

```text
APP.apk
SOURCE.zip
MISSION_LOCK.json
PUBLIC_RUN/
├── PROBE_INPUT.json
├── PROBE_RESULT.json
└── evidence/...
```

제출 완성·검사 도구에는 **Python 3.10 이상**, `requirements.txt`의 Python package,
Android SDK의 **command-line tools latest**(`apkanalyzer`)와 **build-tools 35.0.0**
(`apksigner`)가 필요합니다. Sample app을 직접 build할 때는 Android SDK Platform 35와 JDK 17도
필요합니다. 자가점수 계산(`SELF_SCORE.py`)만 할 때는 Android SDK와 JDK가 필요하지 않습니다.

참가자 ZIP을 압축 해제한 최상위 폴더에서 Python 가상환경을 만들고 공개 검사 package를 처음 한
번만 설치합니다. 가상환경을 쓰면 학교·개인 PC의 기존 Python package와 충돌하지 않고, 최신
Ubuntu/Debian의 전역 package 설치 제한에도 걸리지 않습니다.

macOS·Linux:

```bash
python3 -m venv .venv
.venv/bin/python -m pip install -r candidate_kit/requirements.txt
```

Windows PowerShell:

```powershell
py -3 -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r candidate_kit\requirements.txt
```

그 뒤 `ANDROID_SDK_ROOT` 또는 `ANDROID_HOME`이 Android SDK 35를 가리키는 상태에서 작업폴더
최상위에서 운영체제에 맞는 가상환경 Python으로 한 명령만 실행합니다. 출력할 `SAMPLE_EXPORT`
폴더는 도구가 새로 만드므로 미리 만들지 않습니다.

```bash
.venv/bin/python candidate_kit/FINALIZE_SAMPLE_EXPORT.py SAMPLE_EXPORT
```

```powershell
.\.venv\Scripts\python.exe candidate_kit\FINALIZE_SAMPLE_EXPORT.py SAMPLE_EXPORT
```

`PROBE_INPUT.json`은 `public_harness/make_local_integration_fixture.py`가 공개 입력에서 준비하고,
`PROBE_RESULT.json`은 같은 Kit의 공식 Runner가 공개 연습 실행 뒤 내보냅니다. `evidence/`에는 그 결과가
참조한 화면·상태·판단 영수증을 참가자 앱의 export 기능으로 내보내 넣습니다. 다른 폴더 이름을
썼거나 Android SDK 환경변수를 설정하지 않았다면
`FINALIZE_SAMPLE_EXPORT.py --help`에 나온 선택옵션으로 경로만 지정할 수
있습니다. `evidence/` 안의 각 파일명에서 확장자를 뺀 부분은 Runner 결과의 evidence ID와 같아야
합니다. evidence ID는 영문자 또는 숫자로 시작하고, 이후에는 영문자·숫자·마침표·밑줄·하이픈만
사용합니다(최대 128자). 예를 들어 결과에 `decision_03`이 있으면 `decision_03.json` 또는
`decision_03.png` 중 실제 증거에 맞는 파일 **하나**를 준비합니다. 같은 ID를 여러 파일에 중복
사용하지 않습니다. JSON·텍스트는 비어 있으면 안 되며, JSON은 올바른 UTF-8 JSON이어야 합니다.

도구는 다음 작업을 자동으로 합니다.

- APK에서 package·version·서명 인증서 정보를 읽음
- APP·SOURCE·Mission adapter·증거 파일의 SHA-256과 크기를 계산
- Runner가 기록한 model 사용 여부·호출수를 옮김
- 세 index JSON을 생성하고 모든 파일이 같은 실행·release를 가리키는지 검사
- 검사에 모두 통과한 경우에만 `SAMPLE_EXPORT/`를 완성

따라서 SHA-256, 인증서 지문, release attestation, release ID, runtime identity와 file manifest를
응시자가 계산·복사·입력하지 않습니다. 완성 뒤 다시 확인하고 싶을 때만 아래 읽기 전용 검사를
실행합니다.

```bash
.venv/bin/python candidate_kit/VALIDATE_SAMPLE_EXPORT.py SAMPLE_EXPORT
```

```powershell
.\.venv\Scripts\python.exe candidate_kit\VALIDATE_SAMPLE_EXPORT.py SAMPLE_EXPORT
```

이 검사는 공개 계약의 일관성만 확인하며 production parity의 의미판정이나 공식점수를 대신하지 않습니다.

## 5. source와 외부 dependency

- `SOURCE.zip`에는 사람이 작성한 source·build/config·license·backend 자료만 넣고, `build/`,
  `.gradle/`, IDE cache와 그 밖의 생성 산출물은 제외
- `MISSION_ADAPTER.json`은 실제 source asset 한 개만 포함. 예를 들어
  `app/src/main/assets/MISSION_ADAPTER.json`을 넣고
  `app/build/intermediates/.../MISSION_ADAPTER.json` 같은 build 복사본은 넣지 않음
- 재현 build 명령, 필요한 JDK/SDK/tool version
- 참가자 관리 backend source와 제출 때 사용한 배포·routing·config의 이름·버전·재현방법
- model·SDK·library·AI coding tool·license와 data 전송범위
- secret이 없어도 build 가능한 설정과, 참가자가 관리하는 backend를 평가기간에 같은 조건으로
  재현·사용할 수 있는 방법. Judge의 개인계정·API key·유료구독을 요구하지 않음
- 참가자 APK의 signing private key·keystore·password는 참가자가 소유·보관하며 Dacon에 제출하지 않음

**backend는 어디까지 쓰나:** 참가자가 직접 관리하는 원격 backend를 써도 되고, model 없이 deterministic
경로만 써도 됩니다. 다만 backend를 쓴다면 ① 그 release·routing·config를 제출 전에 동결하고 source와 함께
재현 가능해야 하며, ② 판단당 4회·run당 60회 호출 상한과 60초 판단기한 안에 들어와야 하고, ③ 실제
외부행동·개인정보·특권 제어 없이 합성으로만 동작해야 하며 Judge의 개인계정·API key·유료구독을 요구할 수
없습니다.

**화려한 backend가 점수를 더 주지는 않습니다.** 이 과제의 변별력은 backend·model의 규모가 아니라 E1–E4
장기 흐름·선택적 기억·예외 회복이 실제로 이어지는지에서 나옵니다. 오히려 무거운 backend는
동결·가용성·지연 부담만 늘리고 호출 상한에 먼저 막힐 수 있으니, 선택한 Primary value에 꼭 필요한
만큼만 쓰는 편이 안전합니다.

여기서 `immutable deployment`(변경 불가 배포)는 제출 뒤 바뀌지 않도록 고정한 backend 배포본을 뜻합니다.
응시자는 사람이 알아볼 수 있는 배포 이름·버전과 재현방법을 적고 관련 설정파일을 `SOURCE.zip`에
포함합니다. 파일 지문이 필요하면 제출 완성 도구와 Dacon 검증도구가 실제 파일에서 계산합니다.

## 6. 제출 전 self-check

- 공개 rehearsal 두 흐름을 새 값으로 한 번 더 변형
- self-score의 모든 anchor에 timestamped evidence 연결
- uninstall/reinstall, process kill, offline, permission denied 확인
- deleted value가 export와 relaunch 뒤 부활하지 않는지 확인
- full/off state namespace 상호오염 확인
- public Probe Runner/UI의 input→result schema와 app 화면·state·receipt parity 확인
- official protected component가 Dacon caller만 허용하는 contract test 확인
- `FINALIZE_SAMPLE_EXPORT.py`가 APP·SOURCE·Runner 결과를 같은 release로 확인하고 폴더를 완성했는지 확인
- `VALIDATE_SAMPLE_EXPORT.py` 최종 실행 PASS
- SHA-256·인증서 지문·release attestation·release ID를 문서나 JSON에 수기로 넣지 않았는지 확인

self-score 파일은 개발도구이며 제출 필수점수나 공식점수가 아닙니다.
