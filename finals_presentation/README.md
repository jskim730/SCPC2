# 본선 발표 자료 (2026-08-21)

예선 2차 산출물에 대한 발표와 질의응답용 자료입니다.

**공식 배점** — APK 구현 50(예선 2차 결과로 확정) · 기술성 20(Long-Horizon 메모리 관리 기술 및 최적화)
· 창의성 20(구현한 APK 아이디어) · 발표역량 10(전달력·커뮤니케이션).

| 파일 | 내용 |
|---|---|
| [`SCPC2026_본선발표자료_First_penguin.pdf`](SCPC2026_본선발표자료_First_penguin.pdf) | **2026-08-18 제출한 최종 deck** (14p, 16:9) |
| [`PRESENTATION_SCRIPT.md`](PRESENTATION_SCRIPT.md) | 발표 대본 v3. 페이지별 대사, 시간 눈금, 줄일 후보와 자르면 안 되는 것 |
| [`QNA_PREP.md`](QNA_PREP.md) | 예상 질의응답. 질문 유형 색인 → 절 라우팅, 채점 기준 항목별 구현 대응표 |
| [`DEVICE_EVIDENCE_PLAN.md`](DEVICE_EVIDENCE_PLAN.md) | 기기 시연 계획 |
| `draft_deck/` | 대체된 v2 초안(15p)과 이를 만든 `build_deck.py`, 슬라이드 렌더 이미지 |

## deck 버전에 대하여

제출한 것은 루트의 **14p 디자인판**입니다. 원본은 20.1MB였고 여기 있는 파일은 중복 삽입된 장식 PNG만
dedup한 4.1MB 압축본으로, 14/14 페이지가 픽셀 단위로 동일함을 확인했습니다(구글폼 10MB 제한 대응).

`draft_deck/`의 15p PDF·PPTX는 `build_deck.py`로 생성한 이전 버전이며 **제출본이 아닙니다.**

## 발표 자료의 알려진 오류

최종 deck 제출 뒤 소스와 1:1 대조하면서 찾은 문제입니다. PDF는 이미 제출돼 수정할 수 없어 발표와
질의응답에서 구두로 보완하기로 했습니다. 어떤 문장이 왜 위험했는지는
[`../RETROSPECTIVE.md`](../RETROSPECTIVE.md)의 "아쉬웠던 것"에 정리했습니다.

- 11p "VIL 8 → 4, 50% 감소" — 실측이 아니라 **가정 산정**입니다
- 7p "구현 초기에 시도하였으나" — 언어모델은 분석 후 기각이었고 시도한 적이 없습니다
- 8p Permission `ASK` — 실제 enum은 `ASK_BEFORE_APPLY` (`core/Model.kt`)
- 5p E4 예시가 `MISSION_LOCK.json`의 E4(품절 + process 재시작 부분복구)와 다릅니다
- 13p 한계 목록에서 미해결 항목("PRACTICE-B 미실행", "VIL 실측 전")이 빠졌습니다
