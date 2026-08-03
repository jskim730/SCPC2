# BUILD_AND_SUBMISSION_INFO — 초안

개인 배달 주문 에이전트 · 참가자: First_penguin · 초안 갱신: 2026-08-03 KST

> **초안 상태.** 8/4에 release keystore를 만들고 최종 APK를 빌드한 뒤, 이 문서의 "동결된 산출물"
> 절만 실제 값으로 채운다. SHA-256·인증서 지문·release ID는 **참가자가 손으로 적지 않는다** —
> 제출 완성 도구가 실제 파일에서 계산한다.

---

## 1. 한 줄 요약

순수 Kotlin + Android SDK로만 빌드된다. **model도, 원격 backend도, 네트워크 호출도 없다.**
심사관의 개인 계정·API key·유료 구독이 필요 없고, secret 없이 그대로 빌드된다.

## 2. 재현 빌드

### 필요한 것

| 항목 | 버전 |
|---|---|
| JDK | 17 이상 (Android Studio 번들 JBR 권장) |
| Gradle | 8.9 (wrapper가 자동으로 받는다) |
| Android Gradle Plugin | 8.7.3 |
| Kotlin | 2.0.21 |
| Android SDK Platform | 35 |
| Android SDK Build-Tools | 35.0.0 |

`ANDROID_SDK_ROOT` 또는 `ANDROID_HOME`이 SDK를 가리켜야 하고, `JAVA_HOME`이 JDK 17을 가리켜야 한다.

### 명령

JVM 단위 테스트 (에뮬레이터 불필요):

```bash
cd android && ./gradlew :app:testDebugUnitTest
```

디버그 APK:

```bash
cd android && ./gradlew :app:assembleDebug
```

서명된 release APK (아래 3장의 `keystore.properties`가 있어야 한다):

```bash
cd android && ./gradlew :app:assembleRelease
```

Windows에서는 `./gradlew` 대신 `.\gradlew.bat`을 쓴다.

### 빌드 설정

| 항목 | 값 |
|---|---|
| `applicationId` | `com.scpc.deliveryagent` |
| `compileSdk` / `targetSdk` | 35 / 35 |
| `minSdk` | 28 (Android 9.0) |
| ABI | `arm64-v8a`, `x86_64` |
| `isMinifyEnabled` | **false** — 제출 APK가 함께 내는 source와 한 줄씩 대응하도록 난독화·축소를 끈다 |
| Java / Kotlin target | 17 |

## 3. 서명

signing 설정은 `android/keystore.properties`에서 읽는다. **이 파일과 keystore(`.jks`)는 저장소에
들어 있지 않다** — 공식 규칙이 참가자가 signing private key·keystore·password를 직접 보관하도록
하고 APK·source에 credential을 넣지 못하게 하기 때문이다.

`android/keystore.properties.example`을 복사해 네 값을 채운다:

```text
storeFile=...
storePassword=...
keyAlias=...
keyPassword=...
```

키를 새로 만들 때:

```bash
keytool -genkeypair -v -keystore scpc2-release.jks -keyalg RSA -keysize 4096 -validity 10000 -alias scpc2
```

설정이 없으면 `packageRelease`가 **명시적으로 실패한다.** 서명되지 않은 APK가 조용히 만들어져
리허설을 낭비하는 일이 없도록 의도적으로 막아 두었다. 디버그 빌드와 JVM 테스트는 이 파일 없이도
그대로 동작한다.

## 4. 외부 dependency와 라이선스

### 앱에 들어가는 것

| 항목 | 출처 | 라이선스 | 용도 |
|---|---|---|---|
| `scpc-probe-starter-3.0.0-draft.aar` | 대회 공식 Kit (`release_v3/`) | 대회 제공물 | protected component·adapter 계약. 운영 제공물이며 참가자 산출물이 아니다 |
| Kotlin stdlib | JetBrains | Apache-2.0 | 언어 런타임 |
| Android SDK (`android.*`) | Google | Android SDK 라이선스 | 플랫폼 API |

**이것이 전부다.** AndroidX·Compose·네트워크·이미지·직렬화 라이브러리를 쓰지 않는다. 화면은
`android.widget`으로 코드에서 직접 구성하고, JSON은 플랫폼의 `org.json`을 쓴다.

### 테스트에만 들어가는 것 (APK 미포함)

| 항목 | 라이선스 |
|---|---|
| `junit:junit:4.13.2` | EPL-1.0 |
| `org.json:json:20240303` | Public Domain (JSON.org) |
| `androidx.test.ext:junit:1.2.1`, `androidx.test:runner:1.6.2`, `androidx.test:rules:1.6.1` | Apache-2.0 |

## 5. model·backend — 사용하지 않음

| 항목 | 상태 |
|---|---|
| 온디바이스 model | **없음** |
| 원격 backend·endpoint | **없음** |
| 판단당 inference | **0회** |
| run 누적 inference | **0회** |
| 전송하는 데이터 | **없음** (앱이 네트워크로 아무것도 보내지 않는다) |

자연어 해석은 `PreferenceIntake`의 결정적 구현(`RuleBasedIntake`)이 담당하며, 어휘는 전부
`app/src/main/assets/synthetic/catalog.json`의 데이터다. `usesModel=false`, `invocations=0`으로
고정되어 있고 runtime identity에도 그대로 보고된다. 공식 규칙 §8이 deterministic 구현을 명문으로
허용한다.

따라서 판단당 4회·run당 60회 inference 상한과 retry 정책은 **계수 대상이 0**이다. 평가 기간에
가용성을 유지해야 하는 참가자 관리 endpoint도 존재하지 않는다.

앱이 선언하는 권한은 `POST_NOTIFICATIONS` 하나이며 선택이다. 거부해도 주문·기억·복구가 모두
그대로 동작한다.

## 6. 합성 데이터

식당·메뉴·가격·재고·예상시간·리뷰 어휘는 사람이 작성한 단일 asset
`app/src/main/assets/synthetic/catalog.json` 하나에 있다. 런타임 무작위 생성이나 외부 조회를 쓰지
않으므로, `full`과 `claim-off` 두 arm이 **같은 byte에서 출발한다.** 이 파일의 digest는 비교 증거에
기록된다.

`SyntheticCatalog.parse`가 제품 코드에서 불변조건을 강제한다 — token 유일성, 음수 가격 금지,
존재하지 않는 slot·값 참조 금지, 식당명의 `실험` 표식, label에 `@`·전화번호 패턴 금지,
**한 표현이 두 값을 가리키면 거부**, 평점 범위.

## 7. `SOURCE.zip`에 담기는 것

- `android/` 전체의 사람이 작성한 source와 build/config
- `app/src/main/assets/MISSION_ADAPTER.json` (실제 source asset 한 개. build 중간 산출물 복사본은 넣지 않는다)
- `app/src/main/assets/synthetic/catalog.json`
- 루트의 설계·평가 문서

**넣지 않는 것**: `build/`, `.gradle/`, `.kotlin/`, IDE 캐시, keystore와 `keystore.properties`,
`work/APP.apk`, `work/PUBLIC_RUN/`, `SAMPLE_EXPORT/`, Python 가상환경. `.gitignore`가 같은 목록을
막고 있다.

## 8. 동결된 산출물 (8/4에 채운다)

| 항목 | 값 |
|---|---|
| 최종 APK 빌드 일시 | *(8/4 기입)* |
| 빌드 기계 | *(8/4 기입)* |
| `versionCode` / `versionName` | 1 / 1.0.0 |
| APK SHA-256 | *제출 완성 도구가 계산* |
| 서명 인증서 SHA-256 | *제출 완성 도구가 계산* |
| catalog snapshot digest | *앱 화면의 "검증 정보"와 evidence에 기록* |

**최종 APK를 정한 뒤에는 다시 build·sign 하지 않는다.** 공개 리허설에 쓴 APK와 제출 APK가 같은
바이너리여야 하므로, 최종 빌드는 리허설을 수행한 기계에서 한 번만 만든다.
