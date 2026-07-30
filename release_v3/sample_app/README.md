# Probe sample candidate

이 프로젝트는 공식 Kit의 production-state/adapter 연결 예시를 clean standalone app으로
조립한 것입니다. build output, `.gradle`, `local.properties`, debug
keystore는 포함하지 않습니다. `app/libs/`의 AAR는 release root의 starter AAR와 byte-for-byte 같습니다.

이 코드는 정답·최소 점수 보장이 아닙니다. release attestation을 manifest나 adapter에 직접 적지
마십시오. public harness가 최종 APK의 SHA-256·package·version·signing certificate에서 자동
계산해 Probe input과 assignment에 같은 값을 주입하고, starter AAR가 실행 중 APK의 공개정보를 다시
읽습니다. `app/src/main/assets/MISSION_ADAPTER.json`의 raw bytes SHA-256도 starter AAR가
자동 계산합니다. 공식 Runner의 package와 인증서 정보는 starter AAR에 포함되어 있으므로 app
manifest나 Gradle 설정에 복사하지 않습니다.

`scpc-probe-sample-0.1-local.apk`는 package `org.scpc.r2.sample`, version
`0.1-local`인 **local debug-only** 참고 APK입니다. 공개 rehearsal
reference로만 사용하고 공식 제출물로 내지 마십시오. debug keystore 자체는 이 release에 포함되지
않습니다.

이 sample은 starter AAR 연결, 영속 상태, 13단계 결과와 evidence ID 반환까지 보여 줍니다.
Mission마다 화면·state·receipt 형식이 다르므로 실제 evidence 파일을 내보내는 UI·기능은 포함하지
않습니다. 참가자는 자신의 제품에서 evidence ID에 대응하는 `.json`, `.txt`, `.png` 또는 `.mp4`
파일을 내보내도록 구현해야 합니다. `04_SUBMISSION_GUIDE.md`의 `SAMPLE_EXPORT` 절을 따르십시오.

Android SDK 35와 JDK 17에서:

```bash
export ANDROID_SDK_ROOT="<Android SDK>"
export ANDROID_HOME="$ANDROID_SDK_ROOT"
./gradlew --no-daemon :app:testDebugUnitTest :app:assembleDebug
```

생성된 debug APK는 local rehearsal 전용입니다. 참가자의 최종 제출 APK는 참가자 자신의 release key로
서명합니다. 공개 연습에 사용한 최종 APK를 그대로 제출하면 제출 완성 도구가
package·version·certificate·APK 지문을 자동 기록하므로, 이 값을 문서나 JSON에 직접 옮겨 적지
않습니다.
