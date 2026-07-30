# 공개 rehearsal과 비공식 예상점수

## 1. 목적

이 문서는 참가자가 공식 hidden 답을 맞히는 대신, 같은 construct에서 자신의 APK와 evidence가 어느
수준인지 점검하도록 돕습니다. `PUBLIC_REHEARSAL_CASES.json`의 값·순서·문장은 official hidden에서
재사용되지 않습니다.

## 2. 실행방법

1. `PUBLIC_REHEARSAL_CASES.json`을 public `PROBE_INPUT`으로 mapping합니다. 시작 예시는
   `templates/PUBLIC_PROBE_INPUT_EXAMPLE.json`입니다.
2. 모든 참가자가 같은 APK를 공개 연습도구(`public_harness`) 또는 앱 public UI에서
   import→run→export하고 result schema를 확인합니다.
   result 예시는 `templates/PUBLIC_PROBE_RESULT_EXAMPLE.json`입니다.
3. 공개 expected relation과 result의 state·action·evidence를 비교합니다.
4. CORE별 `observed`, `expected_property`, `difference`, evidence ID를 적습니다.
5. 아래 CORE 확인 anchor로 0–4 정수 하나를 선택하고 `SELF_SCORE.py`로 Q/80을 계산합니다.
6. `PRACTICE-B`의 표면·entity·순서에서도 relation과 anchor가 유지되는지 확인합니다.

이 파일은 개발 중 쓰는 비공식 점검표이므로 candidate ID·release ID·SHA-256을 적지 않습니다.
자가점수 계산은 **Python 3.10 이상만 필요하며 Android SDK나 JDK는 필요하지 않습니다.** 참가자 ZIP을
압축 해제한 최상위 폴더에서 다음처럼 예시를 작업파일로 복사하고, 내용을 자신의 관찰과 evidence
ID로 바꾼 뒤 실행합니다.

```bash
cp candidate_kit/templates/SELF_SCORE_EXAMPLE.json my_self_score.json
python3 candidate_kit/SELF_SCORE.py my_self_score.json
```

`SELF_SCORE.py`는 같은 폴더의 `PUBLIC_SCORING_LOOKUP.json`을 자동으로 읽습니다. 참가자가 lookup
경로, release ID, SHA-256이나 digest를 명령행·JSON에 입력하지 않습니다.

## 3. CORE 확인 0–4 anchor

| Anchor | 이 점수를 받는 행동(공개 기준) |
|---:|---|
| 0 | 요구와 반대로 하거나 치명적으로 실패, 또는 증거가 없거나 관찰 불가 |
| 1 | 미리 준비한 정상 경로(happy path)만 되고, 새 값·변화·재시작에서 무너짐 |
| 2 | 정상 상황은 해내지만, 장기 흐름의 연결·범위·회복이 일부 미흡 |
| 3 | 기본 상황과 공개된 변형 모두에서 현재 상태·행동·증거가 일관됨 |
| 4 | 처음 보는 표면 변형과 뒤늦게 오는 결과에서도 3의 성질이 유지되고, 언제 못 하는지(실패 경계)가 분명함 |

anchor 4를 받는 데 comparison(비교모드)이나 현장 소유권 확인까지는 필요 없습니다. 그 두 가지는 deep
단계(CII)에서 따로 봅니다.

## 4. CORE별 확인

| CORE | anchor 3 이상을 받으려면 보여야 하는 것 |
|---|---|
| CORE-1 | 목표·대상이 다른 무관한 정보(distractor)는 빼고, 지금 필요한 최소 맥락만 사용 |
| CORE-2 | 정정·철회·삭제가 그로부터 파생된 하위 항목(descendant)까지 반영되고, 재시작 뒤 옛 값(stale)을 쓰지 않음 |
| CORE-3 | 값·표현이 새로 바뀐 상황에서도 E2(Reuse)의 반복 부담이 줄고, 필요한 안전확인은 유지 |
| CORE-4 | 충돌 상황에서 질문(ASK)·대기(WAIT)·보류(ABSTAIN)·재계획(replan)하고, 영향받은 부분만 고쳐 E4(Recover) 회복 |
| CORE-5 | 강제종료·중복·순서뒤바뀜 뒤에도 각 action이 정확히 한 번만 반영되고(exactly-once) 상태가 이어짐(local continuity) |
| CORE-6 | 늦게 온 결과가 다음 판단을 바꾸고, event→action→outcome 기록장(ledger)이 서로 일치 |

## 5. Q 구성

| Profile | 최대 | 공개 의미 |
|---|---:|---|
| Q1 | 24 | 장기 상태·계획 연속성 |
| Q2 | 14 | 적응적 안전 자율성 |
| Q3 | 12 | 선택적 맥락·기억 가치 |
| Q4 | 14 | 현재성·lifecycle·사용자 통제 |
| Q5 | 8 | 범위 경계·유효한 전이 |
| Q6 | 8 | 행동·자원·증거 무결성 |

정확한 CORE→profile lookup은 `PUBLIC_SCORING_LOOKUP.json`에 있습니다. 동일한 integer anchor를 넣으면
참가자 calculator와 Dacon 자동 평가 batch 도구가 같은 Q lookup을 사용하며 별도 반올림은 없습니다.

## 6. 이 자기평가를 어떻게 활용할까

### 배경 — 예선 2차가 확인하려는 것

예선 2차는 미리 외운 성공 장면을 재생하는 시험이 아닙니다. 여러 session에 걸쳐 **앞선 선택이 뒤의 판단을
바꾸는 능력** — 필요한 것만 선택적으로 기억하고, 상황이 바뀌면 멈춰 다시 판단하며, 앱을 껐다 켜도
안전하게 이어지는 것 — 을 확인하려는 시험입니다.

채점 방식도 이 성질을 겨냥합니다. 실제 채점(official)은 앱이 스스로 매긴 점수를 읽지 않고, 공개 연습과
**다른 값·순서·표면**으로 앱을 돌린 뒤 **관찰된 사실만 정답과 대조**해 anchor와 Q를 만듭니다
(Dacon 자동 평가 batch 도구). 합격·선정 기준은 결과를 열기 전에 동결되므로, 이 self-score의
준비도 구간(`readiness_band`)이
공식 결과와 같다는 보장은 없습니다.

### 게이밍이 통하지 않는 이유와 준비 방향

이 구조에서는 self-score 숫자만 높이거나 공개 rehearsal 문장을 그대로 하드코딩해도 소용이 없습니다.
official은 다른 값을 쓰고, 앱이 스스로 매긴 점수를 읽지 않기 때문입니다. 따라서 점수를 겨냥한 요령보다
과제가 요구하는 실제 역량에 집중하는 것이 맞습니다.

- 새 값·변형·재시작에서도 무너지지 않는 **진짜 E1–E4 동작** (위 anchor 3·4의 성질).
- 화면·state·receipt가 서로 어긋나지 않는 **정직한 증거**.
- comparison에서 mechanism이 실제로 사용자 부담을 줄인다는 **관찰 가능한 차이**.
- self-score는 점수 예측이 아니라 **약한 CORE를 찾아 개발 중 보완하는 점검 도구**로 사용.

### 자기평가 시 주의

높은 self-score만 노리고 anchor를 부풀리지 마십시오. anchor 3·4에는 시각(timestamp)이 찍힌
화면·state·receipt와 독립 변형에서의 결과가 필요합니다. `SELF_SCORE.py`는 증거의 내용을 이해하지 않고
형식과 lookup만 확인하므로, 높은 숫자가 나와도 그 자체로 실력을 보장하지 않습니다.
