# BUILD_AND_SUBMISSION_INFO

개인 배달 주문 에이전트 · 참가자: First_penguin · 2026-08-05 KST

이 문서는 제출 APK와 SOURCE.zip의 재현 빌드, dependency, runtime 및 데이터 경계를 설명한다.
파일 지문·인증서 지문·release identity는 공식 도구가 실제 제출 파일에서 계산하며 이 문서에 수기로
기입하지 않는다.

## 1. 구현 요약

- Kotlin 2.0.21, Android SDK 35, JDK 17
- 결정적 production core와 app-local 영속 상태
- model, 원격 backend, analytics, 광고 SDK 없음
- 인터넷 통신 및 외부 서비스 계정 없음
- 제품 UI, 공개 Probe UI, protected Probe가 같은 production core와 repository를 사용

## 2. 재현 환경

| 항목 | 버전 |
|---|---|
| JDK | 17 이상 |
| Gradle | 8.9 wrapper |
| Android Gradle Plugin | 8.7.3 |
| Kotlin | 2.0.21 |
| Android SDK Platform | 35 |
| Android SDK Build-Tools | 35.0.0 |

`ANDROID_SDK_ROOT` 또는 `ANDROID_HOME`이 Android SDK를 가리켜야 한다. Gradle dependency를 처음
받는 빌드에서는 일반적인 dependency 저장소 접근이 필요하지만, 앱 runtime은 네트워크를 사용하지 않는다.

### 테스트와 Debug 빌드 - 서명정보 불필요

```bash
cd android
./gradlew :app:testDebugUnitTest :app:testReleaseUnitTest
./gradlew :app:assembleDebug
```

Windows에서는 `./gradlew` 대신 `.\gradlew.bat`을 사용한다. 공개 13-step fixture가 SOURCE.zip 안에
포함되어 있으므로 위 JVM 테스트는 별도 `release_v3` 디렉터리를 요구하지 않는다.

### 서명된 Release 빌드 - 참가자 보관 keystore 필요

공식 규칙에 따라 signing private key, keystore와 password는 SOURCE.zip에 포함하지 않는다.
`android/keystore.properties.example`을 `android/keystore.properties`로 복사한 뒤 다음 값을 로컬에서
채운다.

```text
storeFile=...
storePassword=...
keyAlias=...
keyPassword=...
```

```bash
cd android
./gradlew :app:assembleRelease
```

서명 설정이 없으면 Debug 빌드와 JVM 테스트는 동작하고, Release packaging만 명시적으로 중단된다.

## 3. Android 설정

| 항목 | 값 |
|---|---|
| applicationId | `com.scpc.deliveryagent` |
| versionCode / versionName | 1 / 1.0.0 |
| compileSdk / targetSdk | 35 / 35 |
| minSdk | 28 |
| ABI | arm64-v8a, x86_64 |
| Java / Kotlin target | 17 |
| 코드 축소·난독화 | 사용하지 않음 |

선언 권한은 다음 두 개다.

| 권한 | 성격과 용도 |
|---|---|
| `POST_NOTIFICATIONS` | Android 13+ 선택 권한. 지연된 평가 요청을 보조 알림으로 표시하며 거부해도 제품 기능은 유지된다 |
| `ACCESS_NETWORK_STATE` | 설치 시 허용되는 normal 권한. 기기의 현재 연결 상태를 화면에 표시할 뿐 판단에는 사용하지 않는다 |

`INTERNET` 권한은 선언하지 않는다. 주문, 기억, Probe, evidence export는 모두 app-local로 동작한다.

## 4. Dependency와 라이선스

### APK에 포함되는 항목

| 구성요소 | 버전·출처 | 용도 |
|---|---|---|
| Kotlin standard library | Kotlin 2.0.21, JetBrains | 언어 runtime |
| Android SDK API | Google Android SDK | 플랫폼 API |
| `scpc-probe-starter-3.0.0-draft.aar` | SCPC 2026 공식 Release v3 Kit | protected Probe component와 adapter contract |

화면은 `android.widget`, JSON은 플랫폼 `org.json`을 사용한다. AndroidX, Compose, 네트워크, 이미지,
database 및 model runtime library는 APK에 포함하지 않는다.

### 테스트 전용 dependency - APK 미포함

| 구성요소 | 버전 |
|---|---|
| JUnit | 4.13.2 |
| JSON-java | 20240303 |
| AndroidX Test Ext JUnit | 1.2.1 |
| AndroidX Test Runner | 1.6.2 |
| AndroidX Test Rules | 1.6.1 |

전체 라이선스·고지 정보는 `THIRD_PARTY_NOTICES.md`에 정리했다.

## 5. Model, backend와 데이터 전송

| 항목 | 상태 |
|---|---|
| 온디바이스 model | 없음 |
| 원격 backend·endpoint | 없음 |
| 판단당 / run 누적 inference | 0 / 0 |
| retry | 없음 |
| 사용자·평가 데이터 전송 | 없음 |

자연어 입력은 `RuleBasedIntake`가 합성 catalog에 선언된 표현을 결정적으로 해석한다. 알 수 없는 표현은
추측하지 않고 화면에서 확인을 요청한다. 식당·메뉴·가격·재고·평가 어휘는
`app/src/main/assets/synthetic/catalog.json`에 있으며 실제 개인정보나 실제 주문정보를 포함하지 않는다.

개발 보조로 OpenAI Codex coding agent를 사용했다. 저장소 source·문서와 로컬 명령 출력 일부가 개발 중
OpenAI 서비스에서 처리될 수 있었으나, Codex SDK·API·model·prompt·credential은 제품 runtime에 포함되지
않으며 앱 사용자 데이터가 OpenAI로 전송되지 않는다.

## 6. SOURCE.zip 구성

SOURCE.zip은 빌드와 기술 검증에 필요한 다음 항목만 포함한다.

- Android Gradle 설정, wrapper와 signing 설정 예시
- `app/src/main`: 실제 제품·Probe source, manifest, resource와 합성 asset
- `app/src/test`, `app/src/androidTest`: core 계약, lifecycle, 비교 및 UI parity 테스트
- 공식 starter AAR 한 개와 SOURCE 내부 실행용 공개 13-step fixture
- 이 빌드 문서와 `THIRD_PARTY_NOTICES.md`

다음은 포함하지 않는다.

- `build/`, `.gradle/`, `.kotlin/`, IDE 설정과 local SDK 경로
- keystore, password, signing private key, token과 기타 secret
- APK, SAMPLE_EXPORT, Runner 실행결과와 evidence 복사본
- 내부 구현 진행상태, UX 초안, 평가전략, 개인 경로가 있는 runbook
- PDF 원고·생성기, 로컬 Windows shim, 변형 fixture 생성 도구

`MISSION_ADAPTER.json`은 `app/src/main/assets`의 production source 원본 한 개만 포함하며 build 중간
복사본은 포함하지 않는다.

## 7. 검증 범위

JVM 테스트는 다음을 포함한다.

- 공개 Probe operation replay와 step 순서·식별자 보존
- 현재 정정·철회·삭제와 no-resurrection
- process 재시작, duplicate·out-of-order event, exactly-once commit
- network 지연·차단과 미확인 완료 방지
- full / claim-off 시작상태 격리와 interaction-load 비교
- 모든 합성 식당·메뉴 조합의 완결성과 미제공 옵션 부분복구

기기 계측 테스트는 제품 UI와 Probe adapter가 같은 repository·decision logic·ledger를 사용하는지 확인한다.
제출 APK의 실제 public Runner 결과와 evidence는 별도 SAMPLE_EXPORT에 보관한다.

## 8. 동결 원칙

제출 APK, SOURCE.zip과 SAMPLE_EXPORT의 실제 지문 및 release binding은 공식 finalizer로 생성한다. 어느
제출 파일이든 변경되면 SOURCE.zip을 다시 만들고 official finalizer와 validator를 다시 실행한다.
