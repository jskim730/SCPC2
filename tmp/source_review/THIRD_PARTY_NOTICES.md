# Third-party notices

This file records the build and test dependencies used by the submitted Android source. The application contains no
remote service SDK, analytics SDK, advertising SDK, image library, database library, or generative-model runtime.

## Runtime/build dependencies

| Component | Version/source | License or governing terms | Use |
|---|---|---|---|
| Kotlin standard library and Gradle plugin | Kotlin 2.0.21, JetBrains | Apache License 2.0 | Language runtime and build plugin |
| Android SDK / Android Gradle Plugin | API 35 / AGP 8.7.3, Google | Android SDK terms and Apache License 2.0 components | Android platform and build tooling |
| `scpc-probe-starter-3.0.0-draft.aar` | SCPC 2026 official Release v3 Kit | Competition-supplied operating component; use is governed by the competition terms | Protected Probe component and adapter contract |

## Test-only dependencies (not packaged in `APP.apk`)

| Component | Version | License |
|---|---|---|
| JUnit | 4.13.2 | Eclipse Public License 1.0 |
| JSON-java (`org.json`) | 20240303 | Public Domain / JSON.org notice |
| AndroidX Test Ext JUnit | 1.2.1 | Apache License 2.0 |
| AndroidX Test Runner | 1.6.2 | Apache License 2.0 |
| AndroidX Test Rules | 1.6.1 | Apache License 2.0 |

The participant-authored application source is submitted under the rights and obligations of the SCPC 2026 AI
Challenge rules. Signing private keys, passwords, local SDK paths, generated build outputs, and rehearsal secrets are
not included in `SOURCE.zip`.
