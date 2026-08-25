# 환경 구축 → 공개 13-step 완주 Runbook

작성: 2026-07-29 KST · 대상: Windows 11 / `C:\Users\Infocar\SCPC2`

## 목표

**빌드 없이** 공식 Runner로 공개 13-step을 완주시키는 것이 첫 관문이다. 이게 되면 나머지는
"내 코드를 그 자리에 끼워 넣는 문제"로 바뀐다. 안 되면 Mission 품질과 무관하게 제출물이 나오지 않는다.

Kit에 이미 서명된 sample APK(`release_v3/sample_app/scpc-probe-sample-0.1-local.apk`)가 들어 있으므로
**Gradle 빌드도 JDK도 없이** Phase 1까지 갈 수 있다.

## 확인된 현재 상태

| 항목 | 상태 |
|---|---|
| winget | 있음 |
| 디스크 여유 (C:) | 57 GB |
| CPU 가상화 | 활성 (emulator 가능) |
| JDK | 21만 있음 |
| Python | 3.13 (`py -3`) |
| Android SDK / adb / apkanalyzer / apksigner | **없음** |
| Python venv | **없음** |

## 작업 폴더 규칙

`release_v3/`는 읽기 전용 참조본이다. 생성물은 전부 `work/`에 둔다.
Kit 도구의 기본 경로는 ZIP 루트 기준이므로 **`work/`에서 실행할 때는 경로를 명시**한다.

```text
C:\Users\Infocar\SCPC2\
├── release_v3\          (읽기 전용)
├── .venv\               (Phase 0에서 생성)
└── work\                (여기서 작업)
    ├── APP.apk
    └── PUBLIC_RUN\      (도구가 생성)
```

---

# Phase 0 — 설치

## 0-1. Python venv

```powershell
py -3 -m venv C:\Users\Infocar\SCPC2\.venv
C:\Users\Infocar\SCPC2\.venv\Scripts\python.exe -m pip install -r C:\Users\Infocar\SCPC2\release_v3\candidate_kit\requirements.txt
```

확인:

```powershell
C:\Users\Infocar\SCPC2\.venv\Scripts\python.exe -c "import jsonschema; print('jsonschema', jsonschema.__version__)"
```

## 0-2. Android Studio

SDK Manager·emulator·번들 JDK가 함께 들어와서 가장 빠르다. 다운로드가 크므로 먼저 시작해 두고
0-1을 병행한다.

```powershell
winget install --id Google.AndroidStudio -e --source winget
```

설치 후 **한 번 실행**해 첫 실행 마법사가 SDK를 내려받게 한다. 기본 위치는
`%LOCALAPPDATA%\Android\Sdk`.

## 0-3. SDK 구성요소

Studio의 `Settings > Languages & Frameworks > Android SDK`에서 다음을 체크한다.
`SDK Tools` 탭은 **Show Package Details**를 켜야 정확한 버전이 보인다.

| 탭 | 항목 |
|---|---|
| SDK Platforms | **Android 15 (API 35)** |
| SDK Tools | **Android SDK Build-Tools 35.0.0** |
| SDK Tools | **Android SDK Platform-Tools** |
| SDK Tools | **Android SDK Command-line Tools (latest)** |
| SDK Tools | Android Emulator (실기기 쓰면 생략) |

CLI를 선호하면:

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\cmdline-tools\latest\bin\sdkmanager.bat" "platforms;android-35" "build-tools;35.0.0" "platform-tools" "cmdline-tools;latest"
```

## 0-4. 환경변수

```powershell
[Environment]::SetEnvironmentVariable("ANDROID_HOME", "$env:LOCALAPPDATA\Android\Sdk", "User")
$p = [Environment]::GetEnvironmentVariable("Path","User")
$add = "$env:LOCALAPPDATA\Android\Sdk\platform-tools;$env:LOCALAPPDATA\Android\Sdk\build-tools\35.0.0;$env:LOCALAPPDATA\Android\Sdk\cmdline-tools\latest\bin"
[Environment]::SetEnvironmentVariable("Path", "$p;$add", "User")
```

**새 PowerShell 창을 연 뒤** 확인:

```powershell
adb version; apkanalyzer -h 2>&1 | Select-Object -First 2; apksigner version
```

세 개가 모두 응답해야 Phase 1로 간다.

## 0-5. Emulator 준비 (실기기 없음)

### 확인된 전제

| 항목 | 상태 | 의미 |
|---|---|---|
| 하이퍼바이저 | **이미 실행 중** (`HvHost`·`vmcompute` Running) | WHPX 활성화 작업 **불필요** |
| CPU | AMD Ryzen 7 3700U (4C/8T) | AMD는 HAXM 불가·WHPX 경로 — 위에서 충족 |
| RAM | **5.9 GB** | **가장 큰 제약. 아래 절약 규칙 필수** |
| 디스크 | 57 GB 여유 | 충분 |

### RAM 절약 규칙 (5.9 GB 환경)

1. **emulator를 돌릴 때 Android Studio를 닫는다.** 빌드는 `gradlew.bat` CLI로 한다.
2. AVD 메모리는 **1536 MB**로 고정한다. 기본값 2048은 과하다.
3. system image는 **`default`(AOSP)** 를 쓴다. `google_apis`·`google_apis_playstore`는 무겁고
   Play Store 이미지는 `adb root`도 막힌다.
4. Gradle daemon이 남아 메모리를 먹으면 `.\gradlew.bat --stop`.

### AVD 생성

```powershell
sdkmanager "system-images;android-35;default;x86_64"
avdmanager create avd -n scpc35 -k "system-images;android-35;default;x86_64" -d pixel_6
```

### 실행

```powershell
emulator -avd scpc35 -memory 1536 -no-snapshot-save -no-boot-anim -gpu host
```

- `-gpu host`가 실패하면 `-gpu swiftshader_indirect` (느리지만 호환성 높음)
- 화면이 필요 없는 harness 실행만 할 때는 `-no-window`를 추가해 메모리를 더 아낄 수 있다.
  단 데모 영상 촬영과 수동 확인 때는 창이 필요하다.

### 확인

```powershell
adb devices
```

`emulator-5554  device` 형태로 보여야 한다. `offline`이면 부팅이 끝날 때까지 기다린다.

```powershell
adb wait-for-device shell getprop sys.boot_completed
```

`1`이 나오면 준비 완료.

### network 설정 사전 시드 — **반드시 먼저 실행**

`runnerctl.py`는 실행 전에 `settings get global wifi_on`과 `mobile_data`를 읽어 **`0`이나 `1`이
아니면 run 자체를 거부**한다(기기 network 상태를 망가뜨리지 않으려는 안전장치). emulator는 이 값이
미설정(`null`)인 경우가 있으므로 미리 넣어 둔다.

```powershell
adb shell settings put global wifi_on 1
adb shell settings put global mobile_data 1
adb shell settings get global wifi_on
adb shell settings get global mobile_data
```

둘 다 `1`이 출력되어야 한다. 이걸 건너뛰면
`cannot determine Android network setting ...` 오류로 13-step이 시작조차 안 된다.

---

# Phase 1 — 빌드 없이 13-step 완주 (go/no-go)

## 1-1. 작업 폴더 구성

```powershell
New-Item -ItemType Directory -Force C:\Users\Infocar\SCPC2\work | Out-Null
Copy-Item C:\Users\Infocar\SCPC2\release_v3\sample_app\scpc-probe-sample-0.1-local.apk C:\Users\Infocar\SCPC2\work\APP.apk
```

## 1-2. 공개 입력·배정 파일 생성

`MISSION_LOCK.json`은 **없어도 된다.** 도구가 파일이 있을 때만 읽고, 없으면 공개 입력에 든
`PUBLIC_REHEARSAL_AUTOMATIC` 값을 쓴다. 즉 Dacon 회신을 기다릴 필요가 없다.

> **Windows에서는 공식 스크립트가 그대로 돌지 않는다 (2026-08-04 확인).**
> `release_v3/public_harness/apk_release_info.py`가 SDK 도구를 **확장자 없는 이름**으로 찍는다
> (`cmdline-tools/latest/bin/apkanalyzer`, `build-tools/35.0.0/apksigner`). 그 이름은 macOS·Linux에만
> 있고 Windows SDK는 `.bat` 래퍼만 배포하며, CreateProcess는 확장자 없는 배치 파일을 거부한다
> (`WinError 193`). 그래서 `Android SDK tools are missing`으로 멈춘다 — **SDK는 멀쩡히 설치돼 있는데도.**
>
> 위 0-4의 확인(`apkanalyzer -h`)은 shell이 PATHEXT로 `.bat`을 찾아주므로 **통과한다.** 초록불이
> 이 문제를 가린다는 점에 주의한다.
>
> 우회는 `tools/win_fixture_shim.py`다. 공식 코드를 **수정하지 않고** import해서 실행하며, 확장자 없는
> 실행 파일 경로만 `.bat` 형제로 바꾼다. 하네스 로직은 손대지 않고 `release_v3/`에도 쓰지 않는다.
> 로컬 리허설에만 영향이 있고, 채점은 Dacon 하네스가 자기 기계에서 돌리므로 무관하다.

```powershell
Set-Location C:\Users\Infocar\SCPC2\work
..\.venv\Scripts\python.exe ..\tools\win_fixture_shim.py --public-input ..\release_v3\probe\PUBLIC_PROBE_INPUT_13_STEP.json --candidate-apk APP.apk --output-dir PUBLIC_RUN
```

인자는 공식 스크립트와 동일하다. macOS·Linux에서는 shim 없이 원본을 그대로 부르면 된다:

```powershell
..\.venv\Scripts\python.exe ..\release_v3\public_harness\make_local_integration_fixture.py --public-input ..\release_v3\probe\PUBLIC_PROBE_INPUT_13_STEP.json --candidate-apk APP.apk --output-dir PUBLIC_RUN
```

성공하면 `work\PUBLIC_RUN\`에 `PROBE_INPUT.json`과 `ASSIGNMENT.json`이 생기고 run id가 출력된다.
`runnerctl.py`는 이 도구들을 쓰지 않으므로 shim이 필요 없다.

```powershell
Get-ChildItem PUBLIC_RUN
```

## 1-3. 공식 Runner 실행

```powershell
..\.venv\Scripts\python.exe ..\release_v3\public_harness\runnerctl.py run --assignment PUBLIC_RUN\ASSIGNMENT.json --input PUBLIC_RUN\PROBE_INPUT.json --output-dir PUBLIC_RUN --runner-apk ..\release_v3\probe\scpc-dacon-runner-3.0.0-draft.apk --candidate-apk APP.apk
```

이 명령이 하는 일: Runner APK와 `APP.apk` 설치 → 이전 연습상태 삭제 → 13-step 전달
(중간에 network 변경과 force-stop·relaunch 포함) → 결과 회수 → network 원상복구.

## 1-4. 결과 확인 — 여기까지 되면 첫 관문 통과

```powershell
..\.venv\Scripts\python.exe -c "import json;d=json.load(open(r'PUBLIC_RUN\PROBE_RESULT.json',encoding='utf-8'));s=d['step_results'];print('steps:',len(s));print('ops:',[x['operation'] for x in s]);print('decisions:',[x['decision_state'] for x in s]);print('epochs:',[x['process_epoch'] for x in s])"
```

기대값:

- `steps: 13`
- `ops`가 입력과 **같은 순서**
- `PROCESS_KILL_RELAUNCH` 이후 `process_epoch`가 증가

schema 검증:

```powershell
..\.venv\Scripts\python.exe -c "import json,jsonschema;s=json.load(open(r'..\release_v3\candidate_kit\PROBE_RESULT.schema.json',encoding='utf-8'));d=json.load(open(r'PUBLIC_RUN\PROBE_RESULT.json',encoding='utf-8'));jsonschema.validate(d,s);print('PROBE_RESULT schema OK')"
```

---

# Phase 2 — sample_app 직접 빌드

Phase 1이 통과한 뒤에만 한다. 목적은 **내 소스에서 나온 APK로도 같은 흐름이 도는지** 확인하는 것.

`release_v3/sample_app`은 읽기 전용이므로 복사해서 쓴다.

```powershell
Copy-Item -Recurse C:\Users\Infocar\SCPC2\release_v3\sample_app C:\Users\Infocar\SCPC2\work\sample_build
Set-Location C:\Users\Infocar\SCPC2\work\sample_build
.\gradlew.bat assembleDebug
```

- Gradle 8.9 + AGP 8.7.3이라 JDK 21로 빌드될 가능성이 높다.
- 실패하면 Studio 번들 JDK를 쓴다:
  `$env:JAVA_HOME = "$env:ProgramFiles\Android\Android Studio\jbr"`

산출물을 `work\APP.apk`로 덮고 Phase 1의 1-2~1-4를 그대로 반복한다. 결과가 같으면
빌드 → 서명 → 연동 경로 전체가 확보된 것이다.

---

# Phase 3 — 내 앱으로 교체

이때부터가 실제 구현이다. 순서만 적어 둔다.

1. 새 Android 프로젝트 (Kotlin, minSdk 28, **compileSdk/targetSdk 35**, namespace 확정)
2. `app/libs/`에 starter AAR 복사 후 `implementation(files(...))`
3. manifest에 `org.scpc.r2.probe.ADAPTER_CLASS` meta-data 등록
4. `assets/MISSION_ADAPTER.json` 작성 (role 10개 × 3필드, operation 13개 × 2필드, `source_paths` 2개 이상)
5. production core 구현 → adapter가 그 core만 호출
6. **release 서명 keystore 생성** (private key·password는 제출하지 않고 본인 보관)
7. `assembleRelease` → `work\APP.apk`
8. Phase 1 절차 반복 → 값·순서를 바꾼 변형 입력으로도 반복

---

# 진단

| 증상 | 확인 |
|---|---|
| `adb devices`에 아무것도 없음 | USB 케이블(데이터 전송 지원), 개발자 옵션·USB 디버깅, 드라이버 |
| `unauthorized` | 휴대폰 화면의 디버깅 허용 팝업 승인 |
| `apkanalyzer`/`apksigner` 못 찾음 | cmdline-tools latest·build-tools 35.0.0 설치 여부, PATH 반영 위해 새 창 |
| fixture가 APK 못 읽음 | `ANDROID_HOME` 설정 여부, `--sdk-root`로 직접 지정 |
| Runner가 candidate를 못 부름 | manifest의 `ADAPTER_CLASS` 값과 실제 class 이름 일치 여부 |
| 재실행 시 Runner가 거부 | anti-replay 기록. `--clear-runner-data`는 **복구용 최후수단**으로만 |
| step 수가 13이 아님 | adapter가 일부 operation을 처리하지 않음 |

## 원칙

- **공개 입력의 값·문장을 hard-code하지 않는다.** official은 다른 값·표현·순서를 쓴다.
- 최종 APK를 정한 뒤에는 **다시 build/sign하지 않는다.** 공개 연습에 쓴 그 파일을 그대로 제출한다.
- `release_v3/`에 생성물을 남기지 않는다.
