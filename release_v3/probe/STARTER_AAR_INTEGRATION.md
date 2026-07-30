# 참가자 앱의 starter AAR 연결

> 이 문서는 Dacon 대회 페이지의 공식 다운로드 링크로 받은 Kit를 앱에 연결하는 안내입니다.

## 1. 의존성과 manifest

배포받은 AAR를 app module의 `libs/`에 두고 연결합니다.

```kotlin
dependencies {
    implementation(files("libs/scpc-probe-starter-3.0.0-draft.aar"))
}
```

AAR의 manifest merger가 `ScpcProbeService`와 signature permission 요구조건을 넣습니다. 공식 Runner의
package·인증서 확인값과 contract version도 AAR 안에 들어 있으므로 참가자가 별도 파일에서 복사하거나
입력하지 않습니다. 참가자 app manifest의 `<application>`에는 자신의 adapter class만 등록합니다.

```xml
<meta-data
    android:name="org.scpc.r2.probe.ADAPTER_CLASS"
    android:value="com.example.app.OfficialProbeAdapter" />
```

`RELEASE_ATTESTATION_ID` metadata는 추가하지 않습니다. release attestation은 최종 APK에서 자동으로
계산되어 실행 입력에 결박되므로, manifest나 adapter에 값을 직접 적지 않습니다.

## 2. adapter와 자동 기술 ID

public no-arg constructor를 가진 class가 `ProbeAdapter`를 구현합니다. 새 adapter가 직접 제공하는
최소 기술 상태는 `runtimeState`입니다.

```kotlin
override fun runtimeState(context: Context): ProbeRuntimeState =
    ProbeRuntimeState(
        modelConfigured = false,
        cumulativeInvocations = productionCore(context).cumulativeInvocations(),
    )
```

- `modelConfigured`: 제출 release가 외부 model/backend를 실제로 사용하도록 구성되었으면 `true`,
  사용하지 않으면 `false`
- `cumulativeInvocations`: 이번 실행을 포함해 production core가 누적한 model/backend 호출 수
  (`0..60`)

다음 callback은 별도 mock이 아니라 일반 제품 화면이 사용하는 production
repository·decision engine·action/outcome ledger를 호출합니다.

- `onRunStarted`: run binding과 production state 시작
- `executeStep`: 13 operation을 production core에 적용하고 구조화 관찰 반환
- `onRunFinished`: result가 참조하는 evidence/receipt ID 반환
- `onRunAborted`: 점수 변경 없이 run cleanup

evidence/receipt ID는 제출 증거의 파일명이 되므로
`[A-Za-z0-9][A-Za-z0-9._-]{0,127}` 형식으로 만듭니다. 즉 영문자나 숫자로 시작하고, 이후에는
영문자·숫자·마침표·밑줄·하이픈만 사용할 수 있습니다. 확장자는 ID에 넣지 않습니다.

새 adapter는 `releaseBinding`, `runtimeIdentity`, `missionAdapterSha256`를 직접 구현할 필요가 없습니다.
starter AAR가 다음 값을 자동으로 만듭니다.

1. Android `PackageManager`에서 설치된 참가자 APK의 package, version, signing certificate를 읽습니다.
2. Runner가 실행 입력에 결박한 release attestation을 release binding과 runtime 시작·종료 기록에
   동일하게 넣습니다.
3. `modelConfigured=false`이면 model/backend ID를 `NOT_USED`로 기록합니다. `true`이면 release
   attestation에 결박된 `mbf-v1:` ID를 자동 생성합니다.
4. `assets/MISSION_ADAPTER.json` 원본 byte의 SHA-256을 mission adapter digest로 사용합니다.
   이 파일은 비어 있지 않아야 하며 최대 크기는 1 MiB입니다. 제출 `SOURCE.zip`에도 같은
   `MISSION_ADAPTER.json`을 byte-for-byte 그대로 포함합니다. 제출 완성 도구가 APK와 SOURCE의
   파일을 자동 대조하므로 참가자가 digest를 계산하거나 입력하지 않습니다.

이전 API로 작성한 adapter의 세 method도 호환을 위해 받아들입니다. 단, APK에서 읽은 package,
version, certificate와 이전 adapter 값이 충돌하면 실행을 거부합니다. 이전 adapter가 적은 release
attestation이나 model digest는 결과에 복사하지 않고 starter AAR가 Runner-bound 값으로
정규화합니다.

AAR는 unknown field, identifier 혼합, step skip/replay, digest·token·nonce 불일치와 중단된 write를
fail-closed로 거부합니다. adapter는 oracle, expected relation, PASS/FAIL, anchor, Q나 cut을 계산하지
않습니다.

## 3. release attestation은 도구가 만듭니다

release attestation은 “이 package·version·서명 인증서·APK byte가 한 묶음인지”를 나타내는 자동
기술 ID입니다. 참가자가 임의 값을 정하거나 직접 입력하지 않습니다. public harness는 최종 APK에서
APK 전체 byte의 SHA-256, package name, signing certificate SHA-256, version code와 version name을
읽어 `ra-v1:<64자리 lowercase SHA-256>` 값을 자동 계산합니다.

작업폴더에 `APP.apk`, Dacon이 발급한 `MISSION_LOCK.json`과 공식 Kit가 있으면 다음 한 명령으로
공개 연습 input과 assignment를 `PUBLIC_RUN/`에 만듭니다.

```bash
python3 public_harness/make_local_integration_fixture.py
```

도구는 `MISSION_LOCK.json`에서 candidate ID와 Mission ID를 읽고, 최종 APK에서 release
attestation을 계산해 `PROBE_INPUT.json`과 `ASSIGNMENT.json`에 같은 값으로 주입합니다.
참가자가 SHA-256·인증서 지문·release attestation을 계산하거나 명령행·JSON에 입력하지 않습니다.

이 연결과 공개 실행에는 Python 3.10 이상, Android SDK의 platform-tools(`adb`),
command-line tools latest(`apkanalyzer`), build-tools 35.0.0(`apksigner`)가 필요합니다.
앱을 직접 build할 때는 Android SDK Platform 35와 JDK 17도 필요합니다. Android SDK 위치는
`ANDROID_SDK_ROOT` 또는 `ANDROID_HOME`에서 읽습니다. 파일이나 폴더 이름을 다르게 쓴 경우에만
`--help`에 나온 경로 옵션을 사용합니다.

Android 기기 또는 emulator를 연결한 뒤 공개 13단계 실행도 기본 경로에서는 한 명령입니다.

```bash
python3 public_harness/runnerctl.py run
```

이 명령은 공식 Runner와 `APP.apk`를 설치하고 참가자 앱의 이전 연습상태를 지운 뒤,
`PUBLIC_RUN/ASSIGNMENT.json`과 `PROBE_INPUT.json`으로 실행해 결과를 같은 폴더에 모읍니다.
Runner의 anti-replay 기록은 기본적으로 보존하며, 실행 중 네트워크를 변경했다면 실행 전의
Wi-Fi·모바일 데이터 상태로 되돌립니다.
여러 기기를 연결했거나 파일경로를 바꾼 경우에만 `--help`의 선택옵션을 사용합니다.

## 4. restart

`PROCESS_KILL_RELAUNCH`에서는 Dacon host가 실제 process를 종료합니다. 다음 process에서:

- production state와 action idempotency ledger를 같은 저장소에서 복구
- AAR의 durable lease가 허용하는 정확한 다음 step만 실행
- 진행 중 write에서 process가 죽었다면 결과를 추정하지 않고 `RECOVERY_REQUIRED`
- 삭제된 원문은 복구하지 않고 tombstone만 유지

## 5. 제출 전 확인

1. public rehearsal과 protected service가 같은 adapter를 호출
2. public input의 13 operation을 새 값으로 반복 가능
3. process kill 뒤 epoch 증가와 state/action continuity 확인
4. result에 input step이 같은 순서로 정확히 한 번 존재
5. 공식 검사도구가 release/runtime/adapter/result 연결을 `PASS`로 판정
6. public UI나 일반 앱이 official service를 호출하지 못함
7. manifest·adapter source에 인증서 지문·release attestation 수기 입력값이 없음
8. source 안에 official token·certificate private key·hidden 값·oracle 없음
