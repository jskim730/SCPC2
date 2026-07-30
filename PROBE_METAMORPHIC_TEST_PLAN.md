# 개인 배달 주문 에이전트 — 변형 Probe 테스트 설계

## 문서 상태

- 상태: `DESIGN_ONLY_NO_APP_NO_MISSION_LOCK`
- 작성일: `2026-07-29 KST`
- 제품 구현: 시작하지 않음
- 공식 정본: `release_v3/candidate_kit/PROBE_INPUT.schema.json`,
  `release_v3/candidate_kit/08_PROBE_MODE_CONTRACT.md`

실제 Runner 입력에는 APP에서 계산한 release attestation, Dacon Mission receipt와 run token이 필요하다.
따라서 지금 가짜 제출형 `PROBE_INPUT.json`을 만들지 않는다. APP와 `MISSION_LOCK.json`이 생기면 공식
`make_local_integration_fixture.py`가 만든 작업 사본에 아래 변형을 적용한다. `release_v3` 원본은 수정하지
않는다.

---

## 1. 공통 불변조건

모든 변형은 다음을 지킨다.

- 1–80개 step
- `step_id`와 `event_id`는 run 안에서 유일
- virtual time은 Runner 계약에 맞는 순서
- operation은 13종 중 하나
- 동일 equality relation을 보존해야 하는 role token만 같은 값 사용
- public 문자열·메뉴명을 정답 신호로 사용하지 않음
- 각 input step에 같은 순서·step ID·event ID·operation의 result가 정확히 한 개
- evidence 파일은 하나 이상의 실제 `step_ids`에 연결
- expected relation·PASS/FAIL·anchor·Q는 input과 result에 넣지 않고 개발용 외부 assertion으로만 유지
- 실제 외부계정·결제·개인정보 없이 합성 state만 사용

---

## 2. V1 — role token 전면치환

### 목적

공개 `PUBLIC_*` 값이나 배달 표현을 하드코딩하지 않고 불투명 token 관계로 동작하는지 확인한다.

### 변환

- `PRIMARY_GOAL`, entity, stable, one-off, authority, scope, outcome token을 무작위 합성 ID로 교체
- 같은 대상을 가리키는 token equality만 보존
- `TARGET_ENTITY`와 `DISTRACTOR_ENTITY`는 서로 다른 새 ID
- `REVOKED_SCOPE`와 `PRESERVED_SCOPE`도 새 opaque ID
- `SET_NETWORK`의 key 이름은 바꿀 수 있지만 값은 contract enum
  `ONLINE/OFFLINE/DELAYED/UNKNOWN` 중 하나로 유지

### 외부 assertion

- current target 선택은 token 철자와 무관
- distractor는 더 최근이어도 선택되지 않음
- 정정 authority와 revoke scope 관계 유지
- 공개 메뉴명·`PUBLIC_*` 문자열이 state·source 분기에 없음

---

## 3. V2 — entity·goal 교환

### 목적

특정 식당 A를 항상 target으로 고르는 분기 없이 current goal·entity 관계를 선택하는지 확인한다.

### 변환

- 기존 target token을 새 distractor로 이동
- 기존 distractor와 다른 새 token을 current target으로 지정
- current goal도 새 token으로 교체
- stable fact 중 target과 공유 가능한 option-semantic scope만 유지
- restaurant/menu-specific fact는 distractor entity에 남김
- distractor event의 authority version을 target보다 더 최근으로 만들어 recency shortcut을 방지

### 외부 assertion

- 새 target의 current goal에 필요한 stable scope만 선택
- 더 최근인 wrong-entity fact는 제외
- 식당 고유 option 이름은 새 target에 그대로 복사되지 않음
- selected·excluded context evidence가 같은 production state를 가리킴

---

## 4. V3 — 유효한 순서변형·operation 반복

### 목적

13종을 고정 13단계 서사로 처리하지 않고 반복·중간 Reset·network 전환·연속 판단을 처리하는지 확인한다.

### 권장 18-step 관계

1. `RESET_AND_START`
2. `UPSERT_FACT`
3. `REQUEST_DECISION`
4. `REQUEST_DECISION` — state 변화 없음
5. `SET_NETWORK` — offline
6. `REQUEST_DECISION`
7. `SET_NETWORK` — online
8. `REQUEST_DECISION`
9. `ADVANCE_SESSION`
10. `CORRECT_FACT`
11. `REQUEST_DECISION`
12. `REVOKE_SCOPE`
13. `REQUEST_DECISION`
14. `RESET_AND_START` — active state clean, 이전 run audit 보존
15. `UPSERT_FACT` — 다른 token·entity
16. `REQUEST_DECISION`
17. `PROCESS_KILL_RELAUNCH`
18. `EXPORT_AND_END`

### 외부 assertion

- 3과 4는 새 fact가 없으므로 같은 unresolved relation을 반환하고 action을 중복 commit하지 않음
- offline 판단은 완료를 위장하지 않고 cached state를 손상시키지 않음
- online 복구 뒤 같은 state에서 재판단 가능
- correction·revoke descendant 경계가 operation 위치와 무관
- 두 번째 Reset 뒤 active state는 clean이지만 첫 run terminal snapshot·evidence는 audit에 남음

---

## 5. V4 — 26-step 장기 확장

### 목적

반복 판단, 지연 outcome, catalog 변화, revoke, delete, kill, duplicate와 out-of-order를 한 장기 run에서
연결한다.

### 권장 26-step 관계

1. `RESET_AND_START`
2. `UPSERT_FACT` — stable·one-off·ephemeral request·delayed outcome 예약
3. `REQUEST_DECISION`
4. `REQUEST_DECISION` — idempotent repeat
5. `ADVANCE_SESSION`
6. `REQUEST_DECISION`
7. `CORRECT_FACT`
8. `REQUEST_DECISION`
9. `REVOKE_SCOPE`
10. `REQUEST_DECISION`
11. `SET_NETWORK` — delayed
12. `REQUEST_DECISION`
13. `SET_NETWORK` — offline
14. `REQUEST_DECISION`
15. `SET_NETWORK` — online
16. `REQUEST_DECISION`
17. `UPSERT_FACT` — 높은 version의 side stock 변경
18. `REQUEST_DECISION`
19. `ADVANCE_TIME` — 예약된 outcome 도착
20. `REQUEST_DECISION`
21. `PROCESS_KILL_RELAUNCH`
22. `REQUEST_DECISION`
23. `REPLAY_EVENT`
24. `DELIVER_OUT_OF_ORDER` — 낮은 authority 또는 catalog version
25. `DELETE_FACT` — draft에 쓰인 ephemeral request
26. `EXPORT_AND_END`

### 외부 assertion

- E2에서 stable reuse로 반복 부담이 감소
- permission revoke 뒤 해당 자동 적용만 ASK로 전환
- delayed outcome 전후 selected context 또는 ranking evidence가 달라짐
- permission 상태와 무관하게 같은 delayed outcome ID가 app-local ledger와 다음 판단에 정확히 한 번 반영
- network delayed·offline은 permission fallback과 별개로 WAIT·ABSTAIN과 state 보존을 관찰
- side stock 변경은 side·총액만 무효화하고 main을 보존
- kill 뒤 미commit action을 완료로 표시하지 않음
- replay가 action을 추가 commit하지 않음
- 오래된 event가 완료·삭제·current authority를 부활시키지 않음
- ephemeral 원문과 descendant는 export에서 제거되고 tombstone만 남음

---

## 6. 실제 fixture 생성 시점과 위치

실제 생성 조건:

1. 사용자가 Mission을 승인
2. Dacon `MISSION_LOCK.json` 수령
3. 동일 release의 `APP.apk` 생성
4. 공식 harness로 base `PUBLIC_RUN/PROBE_INPUT.json` 생성

프로젝트 내부 예정 위치:

```text
test-fixtures/probe/
  v1-role-token-permutation/
  v2-entity-goal-swap/
  v3-order-repeat-reset/
  v4-extended-26-step/
```

각 fixture는 schema validation, Runner 실행, step-result parity, screen/state/receipt evidence 확인을 함께
통과해야 한다. fixture 생성기나 assertion code는 Android 구현 승인 뒤 작성한다.
