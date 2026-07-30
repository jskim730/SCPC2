# SCPC 2026 AI 챌린지 예선 2차 — Mission 선언

**제품:** 개인 배달 주문 에이전트 · **참가자:** [데이콘 닉네임]

## 1. 대상 사용자와 반복·중단·변화의 문제

대상 사용자는 비슷한 취향으로 배달을 반복 주문하지만, 예산·시간·기분과 메뉴의 가격·재고·옵션이 매번
달라 주문안을 다시 구성해야 하는 사람이다. 최근 주문을 통째로 복사하면 안정 취향, 이번 주문만의 선택,
직접 말한 것과 추론한 것, 과거 만족도, 현재 catalog, 자동 적용 허용범위가 구분되지 않는다. 그래서 과거
기록이 있어도 먹을 메뉴와 가게를 매번 처음부터 다시 고르고 개인화도 새로 시작하며, 같은 정보를 반복
입력하고(반복), 앱 전환·process 종료로 주문이 끊기면 어디까지 유효하게 결정됐는지 알기
어려우며(중단), 지시·품절·권한이 바뀌면 이미 유효한 부분까지 다시 확인한다(변화).

## 2. 확인 가능한 장기 목표와 Primary value

장기 목표는, 네 번 이상의 주문 session과 그 사이의 예외·지연 outcome·앱 재실행을 거친 뒤 유효한 안정
취향, 자동 적용 허용·철회 scope, 삭제 상태, 보존 field와 확인할 field를 앱과 ledger에서 구분해 관찰할
수 있고, 새 식당에서도 최종 확인을 뺀 추가 확인·입력이 미학습·무효·미허용·미확정 항목에만 남는
상태다.

**Primary value는 현재 상황과 취향에 맞는 유효한 개인 주문 초안을 완성하는 데 필요한 사용자 확인·입력
부담의 감소다.** 질문을 줄였더라도 현재 지시·철회·삭제·catalog를 어긴 초안은 달성으로 보지 않는다.
모든 메뉴·가격·재고·action은 합성이며 실제 주문·결제·외부계정을 쓰지 않는다.

## 3. E1–E4 장기 인과관계

E1–E4는 각각 새 session에서 시작해 세 번의 session 경계를 만들고, E4에는 실제 process 종료·재실행이
포함된다.

**E1 Learn.** “2만원 이하, 따뜻한 국물, 순한맛” 요청에서 지시에 없는 수저·밥 양과 순한맛의 유지범위를
추측하지 않고 물어 확인한 뒤, 순한맛·수저 제외·밥 양 보통을 안정 취향으로 저장하고 자동 적용 scope를
허용한다. 이후 “국물 간이 셌다”는 지연 만족도가 그 scope의 outcome으로 기록된다.

**E2 Reuse.** 새 식당에서 현재도 유효하고 허용된 fact만 메뉴 후보 산출과 option 적용에 재사용하고, 더
최근이어도 다른 식당·목표의 기록은 후보에서 제외하며, 지연 만족도는 그 scope의 추천 순위와 option
제안에만 반영한다. 최종 확인과 불확실한 항목의 질문은 유지한 채 반복 확인·입력을 줄이고 초안을
확정한다.

**E3 Exception.** “이번 주문만 아주 매운맛”은 저장된 순한맛보다 우선하되 안정 취향을 바꾸지 않고, 수저
자동 적용 permission만 철회하면 수저 취향과 다른 scope는 보존한 채 그 field를 다시 묻는다. E1에서
저장한 밥 양 보통을 적게로 정정하면 authority version이 올라가 그 fact에 의존하는 미확정 field만
무효화되고, 확정된 과거 주문과 순한맛·수저 취향은 보존된다.

**E4 Recover.** 새 session에서 one-off는 만료되고 정정된 밥 양 적게가 현재 authority로 적용되며 철회된
수저 field는 다시 묻고, 사이드가 품절되면 사이드·가격·총액만 무효화하되 본 메뉴와 유효한 option은
보존하며, 일회성 요청 메모를 삭제하면 원문·파생 field를 지우고 tombstone만 남긴다. 그 사이 실제
process kill·relaunch가 일어나도 미실행 action을 완료·중복 실행하지 않고, 삭제값이나 낮은 version의
재고 event를 부활시키지 않는다.

대표 인과변화는 두 지점이다. E1의 안정 취향·자동 적용 허용이 E2의 자동 적용과 확인 항목을 바꾸고, E3의
permission 철회가 E4의 자동 적용을 없애 재확인을 만든다. 밥 양 정정과 지연 outcome은 추가 장기 변화,
품절은 부분복구의 증거다.

## 4. 모바일 필요성과 mobile constraint

주문 조건은 주문하려는 순간 휴대폰에서 만들어지고 전화·화면 잠금·앱 전환·network 변화로 자주 끊긴다.
PC 대화창에는 proposal과 commit 사이의 process 종료가 없어 PENDING·복구 state와 그 receipt가 생기지
않고, 앱이 닫힌 동안 도착한 outcome을 다음 실행에서 ledger와 재조정한 evidence도 남지 않는다.
restart·local continuity와 별개로 선언하는 mobile constraint는, 선택 permission의 허용·거부와 무관하게
delayed outcome을 app-local 정본에 정확히 한 번 기록하고 앱 중단·재실행 뒤 다음 판단까지 잇는 것이다.
notification은 관찰용 부수 surface다. network가 지연·차단되면 상태를 보존한 채 catalog 의존 action을
WAIT, 안전한 대안이 없으면 ABSTAIN하고, 복구 뒤 현재 authority·catalog로 재판단한다.

## 5. Signature mechanism의 문제·효과 claim

ASPR(Authority-Scoped Preference Reconciler)이 푸는 문제는, 최근 주문 snapshot이 위 구분을 한 덩어리로
다뤄 새 대상에서의 선택적 재사용도 변화 뒤 부분 무효화도 불가능해지는 것이다. ASPR은 fact마다
source·authority·scope·lifetime·permission을 구분해 현재 목표·대상에 모두 유효한 fact만 주문 field에
적용하고, 하나라도 확정되지 않으면 ASK한다. field의 fact·permission·catalog dependency를 기록해
정정·철회·삭제·품절 시 미확정 descendant만 무효화하고 확정된 부분과 독립 field는 보존한다.

full과 claim-off는 mechanism 외 모든 조건이 동일하고, claim-off는 fact store가 key별 최신값으로 축약되며
위 구분과 dependency edge가 없다. 비교지표 VIL(Valid Interaction Load)은 유효한 초안까지 사용자가
추가로 확인·입력해야 하는 field-resolution event 수이며 ledger에서 계산한다. ASPR은 안전 위반 없이 E2
재사용과 E4 부분복구의 VIL과 유효값 재입력을 줄이는 것을 claim한다.
