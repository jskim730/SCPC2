# -*- coding: utf-8 -*-
"""SCPC 2026 본선 발표자료 v2 — 내용 충실도·흐름·근거(artifact) 중심 초안.

사용자가 디자인을 다시 입힐 예정이므로, 이 초안의 목표는:
  1) 메일 권장 목차 6개 항목 완전 커버
  2) CII C1~C5 축 태깅 (kicker에 표시)
  3) 슬라이드마다 '근거 artifact' 각주
  4) speaker note에 발표 시간 배분·말할 포인트
"""
from pptx import Presentation
from pptx.util import Inches, Pt, Emu
from pptx.dml.color import RGBColor
from pptx.enum.text import PP_ALIGN, MSO_ANCHOR
from pptx.enum.shapes import MSO_SHAPE
from pptx.oxml.ns import qn

# ---------- palette ----------
INK = "26303F"
DARK = "222B3C"
ACCENT = "E8543F"
ACCENT_DK = "B8402E"
TINT = "FBEFEB"
TINT2 = "F2F4F7"
GRAY = "66707E"
LINE = "D9DEE5"
WHITE = "FFFFFF"
ICE = "D8E1EE"

FONT = "Malgun Gothic"

prs = Presentation()
prs.slide_width = Inches(13.333)
prs.slide_height = Inches(7.5)
BLANK = prs.slide_layouts[6]


def rgb(hexs):
    return RGBColor.from_string(hexs)


def _set_run(run, text, size, color, bold, italic=False, font=FONT):
    run.text = text
    f = run.font
    f.size = Pt(size)
    f.bold = bold
    f.italic = italic
    f.color.rgb = rgb(color)
    f.name = font
    rPr = run._r.get_or_add_rPr()
    ea = rPr.find(qn('a:ea'))
    if ea is None:
        ea = rPr.makeelement(qn('a:ea'), {})
        latin = rPr.find(qn('a:latin'))
        if latin is not None:
            latin.addnext(ea)
        else:
            rPr.append(ea)
    ea.set('typeface', font)


def _bullet(para, color=ACCENT, char="\u2022", size_pct=90):
    pPr = para._p.get_or_add_pPr()
    for tag in ('a:buNone', 'a:buChar', 'a:buAutoNum'):
        el = pPr.find(qn(tag))
        if el is not None:
            pPr.remove(el)
    buClr = pPr.makeelement(qn('a:buClr'), {})
    srgb = pPr.makeelement(qn('a:srgbClr'), {'val': color})
    buClr.append(srgb)
    buSz = pPr.makeelement(qn('a:buSzPct'), {'val': str(size_pct * 1000)})
    buFont = pPr.makeelement(qn('a:buFont'), {'typeface': 'Arial'})
    buChar = pPr.makeelement(qn('a:buChar'), {'char': char})
    for el in (buClr, buSz, buFont, buChar):
        pPr.append(el)
    pPr.set('indent', str(Emu(Inches(-0.18))))
    pPr.set('marL', str(Emu(Inches(0.18))))


def _no_bullet(para):
    pPr = para._p.get_or_add_pPr()
    if pPr.find(qn('a:buNone')) is None:
        pPr.append(pPr.makeelement(qn('a:buNone'), {}))


def _fill_paras(tf, paras, default_align=PP_ALIGN.LEFT):
    first = True
    for p in paras:
        para = tf.paragraphs[0] if first else tf.add_paragraph()
        first = False
        para.alignment = p.get('align', default_align)
        if 'space_after' in p:
            para.space_after = Pt(p['space_after'])
        if 'space_before' in p:
            para.space_before = Pt(p['space_before'])
        if 'line' in p:
            para.line_spacing = p['line']
        if p.get('bullet'):
            _bullet(para, p.get('bullet_color', ACCENT))
        else:
            _no_bullet(para)
        for r in p['runs']:
            run = para.add_run()
            _set_run(run, r[0], r[1], r[2], r[3], r[4] if len(r) > 4 else False)


def tb(slide, x, y, w, h, paras, align=PP_ALIGN.LEFT, anchor=MSO_ANCHOR.TOP,
       wrap=True):
    box = slide.shapes.add_textbox(Inches(x), Inches(y), Inches(w), Inches(h))
    tf = box.text_frame
    tf.word_wrap = wrap
    tf.vertical_anchor = anchor
    tf.margin_left = 0
    tf.margin_right = 0
    tf.margin_top = 0
    tf.margin_bottom = 0
    _fill_paras(tf, paras, align)
    return box


def P(text, size, color, bold=False, **kw):
    d = {'runs': [(text, size, color, bold)]}
    d.update(kw)
    return d


def shape(slide, kind, x, y, w, h, fill=None, line_color=None, line_w=0.75,
          radius=None):
    sp = slide.shapes.add_shape(kind, Inches(x), Inches(y), Inches(w), Inches(h))
    sp.shadow.inherit = False
    if fill is None:
        sp.fill.background()
    else:
        sp.fill.solid()
        sp.fill.fore_color.rgb = rgb(fill)
    if line_color is None:
        sp.line.fill.background()
    else:
        sp.line.color.rgb = rgb(line_color)
        sp.line.width = Pt(line_w)
    if radius is not None and kind == MSO_SHAPE.ROUNDED_RECTANGLE:
        try:
            sp.adjustments[0] = radius
        except Exception:
            pass
    sp.text_frame.word_wrap = True
    return sp


def card_text(sp, paras, anchor=MSO_ANCHOR.TOP, ml=0.14, mr=0.14, mt=0.12,
              mb=0.10):
    tf = sp.text_frame
    tf.vertical_anchor = anchor
    tf.margin_left = Inches(ml)
    tf.margin_right = Inches(mr)
    tf.margin_top = Inches(mt)
    tf.margin_bottom = Inches(mb)
    _fill_paras(tf, paras)


def bg(slide, color):
    return shape(slide, MSO_SHAPE.RECTANGLE, 0, 0, 13.333, 7.5, fill=color)


def header(slide, kicker, title, num):
    tb(slide, 0.7, 0.36, 11.9, 0.3, [P(kicker, 12, ACCENT, True)])
    tb(slide, 0.7, 0.64, 11.9, 0.62, [P(title, 26, INK, True)])
    tb(slide, 12.35, 7.08, 0.7, 0.3,
       [P(str(num), 10, GRAY, False, align=PP_ALIGN.RIGHT)])


def footer_src(slide, text):
    tb(slide, 0.7, 7.02, 11.5, 0.34,
       [{'runs': [("근거  ", 9.5, ACCENT_DK, True), (text, 9.5, GRAY, False)]}])


def notes(slide, text):
    slide.notes_slide.notes_text_frame.text = text


def chip(slide, x, y, w, text, dark_bg=True):
    sp = shape(slide, MSO_SHAPE.ROUNDED_RECTANGLE, x, y, w, 0.44,
               fill=("2E3A50" if dark_bg else TINT2), radius=0.5)
    card_text(sp, [P(text, 12.5, (ICE if dark_bg else INK), True,
                     align=PP_ALIGN.CENTER)],
              anchor=MSO_ANCHOR.MIDDLE, mt=0.02, mb=0.02)
    return sp


def arrow_right(slide, x, y, w=0.32, h=0.26, color=ACCENT):
    shape(slide, MSO_SHAPE.RIGHT_ARROW, x, y, w, h, fill=color)


# ============================================================
# S1 — 표지
# ============================================================
s = prs.slides.add_slide(BLANK)
bg(s, DARK)
tb(s, 1.0, 1.5, 11.3, 0.4,
   [P("2026 SCPC : AI 챌린지  ·  본선 발표", 15, ACCENT, True)])
tb(s, 1.0, 2.0, 11.3, 1.0,
   [P("개인 배달 주문 에이전트", 44, WHITE, True)])
tb(s, 1.0, 3.2, 11.3, 1.2, [
    P("유효하고, 허용된 기억만 재사용한다", 20, ICE, True, space_after=4),
    P("반복 배달 주문을 최소한의 확인으로 완성하는 온디바이스 에이전트", 16, "9AA7BC"),
])
chip(s, 1.0, 5.3, 1.6, "ASPR")
chip(s, 2.75, 5.3, 2.3, "Production Parity")
chip(s, 5.20, 5.3, 2.2, "Evidence 무결성")
tb(s, 1.0, 6.6, 11.3, 0.35,
   [P("First_penguin   ·   발표 10분  +  질의응답 10분", 12, "8B97AB")])
notes(s, "[15초] 한 문장 소개: 반복 배달 주문에서 '기억 관리'가 왜 어려운 문제인지로 바로 진입.")

# ============================================================
# S2 — Mission 개요 (C1)
# ============================================================
s = prs.slides.add_slide(BLANK)
header(s, "01 · MISSION 개요  ·  C1 문제·효과 claim", "반복 주문은 편해야 하지만, 오래된 기억은 위험하다", 2)

tb(s, 0.7, 1.55, 6.1, 0.32, [P("문제 상황", 15, ACCENT, True)])
tb(s, 0.7, 1.9, 6.1, 1.05, [
    P("비슷한 취향과 조건으로 배달 주문을 반복하는 개인 사용자는 주문 때마다 "
      "맵기, 수저, 옵션 같은 질문에 처음부터 다시 답해야 한다.", 13, INK, line=1.25),
])
c = shape(s, MSO_SHAPE.ROUNDED_RECTANGLE, 0.7, 3.0, 6.1, 2.75, fill=TINT,
          radius=0.06)
card_text(c, [
    P("\u201c기억이 많을수록 편리하다\u201d 는 성립하지 않는다", 14, ACCENT_DK, True,
      space_after=7),
    P("주문마다 달라지는 조건 (예산, 희망시간)", 12, INK, bullet=True, space_after=4),
    P("식당마다 다른 메뉴와 옵션 구조", 12, INK, bullet=True, space_after=4),
    P("누적되는 사용자의 정정·철회·삭제", 12, INK, bullet=True, space_after=7),
    P("→ 오래된 기억은 오히려 잘못된 자동화를 만든다", 13, INK, True),
], mt=0.18)

tb(s, 7.2, 1.55, 5.4, 0.32, [P("대상 사용자와 핵심 문제", 15, ACCENT, True)])
c = shape(s, MSO_SHAPE.ROUNDED_RECTANGLE, 7.2, 1.9, 5.4, 1.55, fill=TINT2,
          radius=0.07)
card_text(c, [
    P("대상 — 같은 앱에서 주문을 반복하는 개인 사용자", 12, INK, bullet=True,
      space_after=5),
    P("핵심 문제 — 반복 입력 부담과 잘못된 자동 적용 사이의 긴장", 12, INK,
      bullet=True, space_after=5),
    P("전제 — 앱 중단, 조건 변화, 철회가 일상적으로 발생", 12, INK, bullet=True),
], anchor=MSO_ANCHOR.MIDDLE)
c = shape(s, MSO_SHAPE.ROUNDED_RECTANGLE, 7.2, 3.62, 5.4, 2.13, fill=DARK,
          radius=0.07)
card_text(c, [
    P("Primary Value (7/31 Mission 선언으로 동결된 claim)", 11.5, ACCENT, True,
      space_after=7),
    P("현재도 유효하고 자동 적용이 허용된 취향만 재사용한다.", 14.5, WHITE, True,
      line=1.28, space_after=3),
    P("반복 입력은 줄이고, 불확실하거나 권한이 없는 값은 다시 확인한다.", 14.5,
      WHITE, True, line=1.28),
], anchor=MSO_ANCHOR.MIDDLE, mt=0.16)
tb(s, 0.7, 6.05, 11.9, 0.6, [
    P("대상 사용자·핵심 문제·장기 목표·Primary value·E1-E4 인과는 Mission 선언 시점에 동결 — "
      "발표 내용은 제출물과 동일한 claim이다.  |  모든 식당·메뉴·주문·평가는 앱 안의 합성 데이터 "
      "(실제 주문·결제·외부 전송 없음)", 10.5, GRAY, line=1.3),
])
footer_src(s, "MISSION_LOCK.json (SAMPLE_EXPORT 동봉) · MISSION_AND_TECHNICAL_NOTE §1")
notes(s, "[45초] C1 대응: claim이 사전 등록·동결됐음을 명시. '기억이 많을수록 좋다'가 아니라는 문제 재정의가 이 Mission의 출발점.")

# ============================================================
# S3 — 설계 의도
# ============================================================
s = prs.slides.add_slide(BLANK)
header(s, "02 · AGENT / APP 설계 의도", "채팅과 주문서가 한 화면에서 이어진다", 3)

flow = ["자연어로\n조건 요청", "후보 식당·메뉴\n제시", "주문서 자동 채움\n(출처·범위 표시)", "최소 확인 후\n가상 주문 확정",
        "지연 도착하는\n평가 요청", "승인한 기억만\n다음 주문에 사용"]
fx = 0.7
fw = 1.86
for i, t in enumerate(flow):
    fill = DARK if i in (2, 5) else TINT2
    color = WHITE if i in (2, 5) else INK
    c = shape(s, MSO_SHAPE.ROUNDED_RECTANGLE, fx, 1.62, fw, 1.05, fill=fill,
              radius=0.10)
    card_text(c, [P(ln, 11.5, color, True, align=PP_ALIGN.CENTER, space_after=1)
                  for ln in t.split("\n")],
              anchor=MSO_ANCHOR.MIDDLE, ml=0.06, mr=0.06, mt=0.04, mb=0.04)
    if i < len(flow) - 1:
        arrow_right(s, fx + fw + 0.015, 2.04, w=0.16, h=0.20)
    fx += fw + 0.19

tb(s, 0.7, 3.0, 6.1, 0.32, [P("모바일 환경에서 동작해야 하는 이유", 15, ACCENT, True)])
c = shape(s, MSO_SHAPE.ROUNDED_RECTANGLE, 0.7, 3.36, 6.1, 2.55, fill=TINT,
          radius=0.06)
card_text(c, [
    P("핵심은 작은 화면이 아니라 짧은 상호작용 사이의 중단과 재개", 13, ACCENT_DK,
      True, space_after=7, line=1.2),
    P("주문을 구성하다 앱을 떠나고, process가 종료된다", 12, INK, bullet=True,
      space_after=4),
    P("평가 요청은 주문이 끝난 뒤 늦게 도착한다", 12, INK, bullet=True, space_after=4),
    P("다음 주문에서 과거의 선택·철회를 다시 만난다", 12, INK, bullet=True,
      space_after=7),
    P("→ 이 lifecycle을 예외처리가 아닌 제품 기능으로 흡수", 13, INK, True, line=1.2),
], mt=0.16)

tb(s, 7.2, 3.0, 5.4, 0.32, [P("사용자가 얻는 핵심 가치", 15, ACCENT, True)])
vals = [("반복 부담 감소", "유효·허용된 취향이 주문서에 먼저 채워진다"),
        ("현재가 과거를 이긴다", "오늘의 지시·정정·철회가 과거 기억보다 우선한다"),
        ("부분 복구", "품절·미제공·중단 뒤에도 영향받은 부분만 다시 연다")]
vy = 3.4
for i, (t1, t2) in enumerate(vals):
    circ = shape(s, MSO_SHAPE.OVAL, 7.2, vy + 0.08, 0.42, 0.42, fill=ACCENT)
    card_text(circ, [P(str(i + 1), 14, WHITE, True, align=PP_ALIGN.CENTER)],
              anchor=MSO_ANCHOR.MIDDLE, ml=0, mr=0, mt=0, mb=0)
    tb(s, 7.78, vy, 4.82, 0.78, [
        P(t1, 13.5, INK, True, space_after=2),
        P(t2, 11.5, GRAY),
    ])
    vy += 0.85
footer_src(s, "MISSION_AND_TECHNICAL_NOTE §2·§7 · INSTALL_AND_USE_GUIDE §2")
notes(s, "[45초] 흐름 6단계를 왼→오로 짚고, '왜 모바일인가'는 중단·재개 문장 하나로 요약. 가치 3개는 이후 검증 슬라이드와 연결됨을 예고.")

# ============================================================
# S4 — E1~E4 구조와 장기 인과
# ============================================================
s = prs.slides.add_slide(BLANK)
header(s, "03 · 핵심 기능 구조", "E1 → E4 : 앞의 episode가 뒤의 판단을 바꾼다", 4)

cards = [
    ("E1", "Learn", "마라향 실험점에서 맵기·수저·파를 정하고, 주문 뒤 고수 리뷰를 남긴다",
     "직접 말한 stable 취향과, 승인한 범위의 리뷰 기억 생성"),
    ("E2", "Reuse", "다른 식당(금손분식 실험점)에서 마라 메뉴와 일반 떡볶이를 함께 담는다",
     "전역 취향은 두 메뉴에, 고수 취향은 마라 유형에만 자동 적용 → 질문 감소"),
    ("E3", "Exception", "오늘만 아주 맵게 요청, 수저 자동 적용 철회, 저장된 맵기를 중간맛으로 정정",
     "일회성은 이번 주문만, 철회·정정은 해당 범위의 이후 판단만 변경"),
    ("E4", "Recover", "중간맛 미제공·사이드 품절을 겪은 뒤 process를 재시작한다",
     "제공 불가·품절 항목만 다시 열리고, 다른 항목과 확정 기록은 보존"),
]
cx = 0.7
cw = 2.95
for i, (tag, name, sc, effect) in enumerate(cards):
    c = shape(s, MSO_SHAPE.ROUNDED_RECTANGLE, cx, 1.58, cw, 3.15, fill=TINT2,
              radius=0.05)
    card_text(c, [
        {'runs': [(tag + "  ", 16, ACCENT, True), (name, 16, INK, True)],
         'space_after': 6},
        P(sc, 11, INK, line=1.22, space_after=7),
        P("다음 episode에 남기는 것", 10, ACCENT_DK, True, space_after=3),
        P(effect, 10.5, INK, True, line=1.2),
    ], mt=0.14)
    if i < 3:
        arrow_right(s, cx + cw - 0.015, 3.0, w=0.18, h=0.22)
    cx += cw + 0.14

c = shape(s, MSO_SHAPE.ROUNDED_RECTANGLE, 0.7, 4.92, 11.93, 1.85, fill=TINT,
          radius=0.06)
card_text(c, [
    P("episode 사이의 장기 인과 4지점 — session 경계는 주문 단위, process 종료는 경계가 아니다",
      12.5, ACCENT_DK, True, space_after=6),
    P("E1 고수 범위 승인 → E2 다른 식당의 마라 메뉴만 자동 채움 (일반 메뉴는 영향 없음)", 11,
      INK, bullet=True, space_after=3),
    P("E3 수저 권한 철회 → 이후 주문에서 수저만 다시 질문 (다른 취향 유지)", 11, INK,
      bullet=True, space_after=3),
    P("E3 중간맛 정정 → E4에 전달되지만 미제공 식당에서는 임의 대체하지 않고 맵기만 질문", 11,
      INK, bullet=True, space_after=3),
    P("E4 품절 event → 사이드와 총액만 무효화, 본 메뉴·다른 option·확정 action 보존", 11, INK,
      bullet=True),
], mt=0.14)
footer_src(s, "MISSION_AND_TECHNICAL_NOTE §3 · DEMO_VIDEO (E1→E4 실기기 재현) · INSTALL_AND_USE_GUIDE §4")
notes(s, "[60초] 이 슬라이드가 Mission의 뼈대. 4개 인과 지점을 정확히 말하기 — '기억이 이어진다'가 아니라 '앞의 결정이 뒤의 판단 범위를 바꾼다'로 표현.")

# ============================================================
# S5 — 주요 화면과 사용자 시나리오 (신규)
# ============================================================
s = prs.slides.add_slide(BLANK)
header(s, "03 · 핵심 기능 구조", "주요 화면 — 기억이 만들어지는 순간을 사용자가 본다", 5)

rows = [
    ("대화", "사용자 문장, 추천 후보, 질문·답변 칩이 시간순으로"),
    ("주문서 막대", "항목·option·출처·금액 표시, \u2018변경\u2019 / \u2018이대로 주문하기\u2019"),
    ("내 취향과 기억", "저장 값의 출처·범위·자동 적용 여부 확인, 정정·철회·삭제·전체 Reset"),
    ("평가·내보내기", "공개 Probe 입력 불러오기·실행·결과와 evidence 내보내기"),
    ("비교 실행", "full / claim-off paired 시작점 생성, 두 arm의 확인 수·VIL 비교"),
]
tb(s, 0.7, 1.55, 5.9, 0.32, [P("화면 구성", 14.5, ACCENT, True)])
ry = 1.92
for i, (k, v) in enumerate(rows):
    fill = TINT2 if i % 2 == 0 else WHITE
    c = shape(s, MSO_SHAPE.RECTANGLE, 0.7, ry, 5.9, 0.78, fill=fill,
              line_color=LINE, line_w=0.5)
    tb(s, 0.9, ry + 0.08, 1.75, 0.62, [P(k, 11.5, ACCENT_DK, True)],
       anchor=MSO_ANCHOR.MIDDLE)
    tb(s, 2.75, ry + 0.08, 3.7, 0.62, [P(v, 10.5, INK, line=1.15)],
       anchor=MSO_ANCHOR.MIDDLE)
    ry += 0.78

tb(s, 7.0, 1.55, 5.65, 0.32, [P("기억이 저장되는 3가지 관문", 14.5, ACCENT, True)])
gates = [
    ("값을 처음 정하면", "\u2018좋아요\u2019 / \u2018이번 주문만 할래요\u2019를 함께 제시 — 승격 여부를 사용자가 결정"),
    ("기존 기억과 다른 값을 말하면", "즉시 덮어쓰지 않고 적용 범위를 질문 (이번 주문 / 이 식당·메뉴 / 같은 유형 / 전역)"),
    ("리뷰에서 취향 후보를 찾으면", "자동 저장하지 않고 값·대상·범위를 제시, 승인한 내용만 다음 주문에 사용"),
]
gy = 1.92
for t1, t2 in gates:
    c = shape(s, MSO_SHAPE.ROUNDED_RECTANGLE, 7.0, gy, 5.65, 1.06, fill=TINT,
              radius=0.07)
    card_text(c, [
        P(t1, 11.5, ACCENT_DK, True, space_after=3),
        P(t2, 10.5, INK, line=1.2),
    ], mt=0.1, mb=0.08)
    gy += 1.18

c = shape(s, MSO_SHAPE.ROUNDED_RECTANGLE, 7.0, 5.5, 5.65, 1.2, fill=TINT2,
          radius=0.07)
card_text(c, [
    P("대표 표현", 11, ACCENT_DK, True, space_after=4),
    P("\u2018앞으로도 수저 빼고\u2019(stable) · \u2018이번 주문만 아주 맵게\u2019(one-off) · "
      "\u2018일회용 수저는 앞으로 자동으로 정하지 마\u2019(철회)", 10.5, INK, line=1.25),
], mt=0.1)
footer_src(s, "INSTALL_AND_USE_GUIDE §2·§3·§5 · DEMO_VIDEO")
notes(s, "[30초] 화면 나열이 아니라 '기억 저장의 3관문'이 포인트 — 모든 기억은 사용자 승인 게이트를 통과한다. 데모 영상 장면과 연결.")

# ============================================================
# S6 — ASPR typed fact (C2)
# ============================================================
s = prs.slides.add_slide(BLANK)
header(s, "04 · 대표 기제 ASPR  ·  C2 mechanism 적합성", "기억을 \u2018권위가 붙은 사실\u2019로 저장한다", 6)

tb(s, 0.7, 1.55, 7.2, 0.75, [
    P("Authority-Scope Projection & Repair — 기억은 문자열 목록이 아니라, 현재 주문서에 "
      "적용할 자격이 명시된 typed fact와 그 의존관계다.", 13, INK, line=1.3),
])
rows = [
    ("kind", "stable · one-off · 권한 · 철회 · 지연 outcome 등 수명과 역할"),
    ("scope", "이번 주문 / 이 식당-이 메뉴 / 같은 메뉴 유형 / 전역"),
    ("authority", "직접 지시, 현재 정정, 승인된 리뷰 중 무엇이 최신 근거인지"),
    ("permission", "자동 적용이 허용되었는지, 철회되었는지"),
    ("lineage", "이 fact가 만든 주문서 field·action·outcome과의 의존관계"),
]
ry = 2.42
for i, (k, v) in enumerate(rows):
    fill = TINT2 if i % 2 == 0 else WHITE
    shape(s, MSO_SHAPE.RECTANGLE, 0.7, ry, 7.2, 0.6, fill=fill,
          line_color=LINE, line_w=0.5)
    tb(s, 0.9, ry + 0.09, 1.5, 0.44, [P(k, 12, ACCENT_DK, True)],
       anchor=MSO_ANCHOR.MIDDLE)
    tb(s, 2.45, ry + 0.09, 5.35, 0.44, [P(v, 11, INK)],
       anchor=MSO_ANCHOR.MIDDLE)
    ry += 0.6
c = shape(s, MSO_SHAPE.ROUNDED_RECTANGLE, 0.7, 5.6, 7.2, 1.1, fill=TINT2,
          radius=0.07)
card_text(c, [
    P("왜 이 문제에 맞고, 왜 더 복잡하지 않은가", 11.5, ACCENT_DK, True, space_after=3),
    P("문제의 본질이 \u2018무엇을 기억하나\u2019가 아니라 \u2018지금 적용할 자격이 있나\u2019이므로 "
      "자격을 type으로 만들었다. 연산은 projection과 repair 둘뿐이다.", 11, INK, line=1.25),
], mt=0.1)

tb(s, 8.3, 1.55, 4.3, 0.32, [P("scope 구체성 사다리", 14, ACCENT, True)])
ladder = [("이번 주문", 4.3), ("이 식당 · 이 메뉴", 3.7), ("같은 메뉴 유형", 3.1),
          ("전역", 2.5)]
ly = 1.95
for i, (t, w) in enumerate(ladder):
    fill = ["B8402E", "E8543F", "EE7F6E", "F4AB9F"][i]
    c = shape(s, MSO_SHAPE.ROUNDED_RECTANGLE, 8.3, ly, w, 0.5, fill=fill,
              radius=0.25)
    card_text(c, [P(t, 12, WHITE, True)], anchor=MSO_ANCHOR.MIDDLE, ml=0.16)
    ly += 0.6
tb(s, 8.3, 4.5, 4.3, 2.2, [
    P("\u201c가장 최근 값\u201d을 고르지 않는다", 12.5, INK, True, space_after=4),
    P("현재 entity·goal에 관련되고, 아직 유효하며, 자동 적용 권한이 있는 후보 중에서 "
      "구체성과 권위를 비교해 선택한다.", 11.5, GRAY, line=1.3, space_after=4),
    P("예산·희망시간은 매번 달라지므로 stable로 승격하지 않는다.", 11, GRAY, line=1.25),
])
footer_src(s, "MISSION_AND_TECHNICAL_NOTE §4.1 · SOURCE.zip core 모듈 (fact type 정의)")
notes(s, "[45초] C2 대응: '자격의 타입화'가 설계의 전부이고 연산은 2개뿐 — 불필요한 복잡성이 없음을 명시적으로 주장.")

# ============================================================
# S7 — Projection & Repair
# ============================================================
s = prs.slides.add_slide(BLANK)
header(s, "04 · 대표 기제 ASPR", "Projection은 자격 검사, Repair는 선택적 무효화", 7)

tb(s, 0.7, 1.5, 8.0, 0.3, [P("Projection — 적용 가능한 fact만 주문서로 투영", 14, ACCENT, True)])
pflow = ["현재 state +\nmenu schema", "entity · goal · lifetime\nscope · permission 필터",
         "current authority\n우선순위 비교", "주문서 field +\nprovenance 생성",
         "불확실·미제공 값\nNEEDS_CONFIRMATION"]
fx = 0.7
fw = 2.28
for i, t in enumerate(pflow):
    fill = DARK if i == 4 else TINT2
    color = WHITE if i == 4 else INK
    c = shape(s, MSO_SHAPE.ROUNDED_RECTANGLE, fx, 1.88, fw, 0.95, fill=fill,
              radius=0.10)
    card_text(c, [P(ln, 10.5, color, True, align=PP_ALIGN.CENTER, space_after=1)
                  for ln in t.split("\n")],
              anchor=MSO_ANCHOR.MIDDLE, ml=0.05, mr=0.05, mt=0.03, mb=0.03)
    if i < 4:
        arrow_right(s, fx + fw + 0.005, 2.26, w=0.15, h=0.19)
    fx += fw + 0.155

tb(s, 0.7, 3.12, 8.0, 0.3, [P("Repair — dependency graph를 따라 파생 필드만 무효화", 14, ACCENT, True)])
reps = [("정정", "같은 scope의 current authority를 새 값으로 교체하고 영향 필드만 재투영"),
        ("철회", "값은 남기고 그 scope의 자동 적용 권한만 꺼서 다음 주문에 다시 질문"),
        ("삭제", "fact와 descendant 제거, tombstone만 남겨 export·재시작 후 부활 차단"),
        ("품절", "해당 line의 menu·option과 총액만 다시 열고 다른 line은 보존")]
rx = 0.7
rw = 2.92
for t1, t2 in reps:
    c = shape(s, MSO_SHAPE.ROUNDED_RECTANGLE, rx, 3.5, rw, 1.62, fill=TINT,
              radius=0.06)
    card_text(c, [
        P(t1, 12.5, ACCENT_DK, True, space_after=4),
        P(t2, 10.5, INK, line=1.22),
    ], mt=0.12)
    rx += rw + 0.083

c = shape(s, MSO_SHAPE.ROUNDED_RECTANGLE, 0.7, 5.4, 11.93, 1.3, fill=TINT2,
          radius=0.07)
card_text(c, [
    P("generic core는 언어·표현과 무관하다", 12, INK, True, space_after=3),
    P("core는 한국어 문장·메뉴명으로 분기하지 않는다. 자연어 계층이 catalog 표현을 token으로 "
      "바꾸고, core는 role · scope · authority · state 전이만 처리한다 → 식당·메뉴·표현·event "
      "순서가 바뀌어도 같은 규칙이 적용된다 (role-token 치환 변형에서 13단계 판단 동일로 확인).",
      11, GRAY, line=1.25),
], anchor=MSO_ANCHOR.MIDDLE, mt=0.1, mb=0.1)
footer_src(s, "MISSION_AND_TECHNICAL_NOTE §4.2·§4.3 · ScopedPreferenceTest · ReviewMemoryTest · RC1_RUN_V1")
notes(s, "[45초] repair 4종은 E3·E4와 1:1 대응. 마지막 상자는 C4(unseen surface)와 연결되는 복선 — V1 변형 결과를 미리 언급.")

# ============================================================
# S8 — 아키텍처·production parity·저장
# ============================================================
s = prs.slides.add_slide(BLANK)
header(s, "05 · 기술 구현", "Probe와 제품이 같은 core, 같은 저장소를 쓴다 — Production Parity", 8)

tops = [("제품 UI", "Main · Memory ·\nComparison Activity"),
        ("공개 Probe UI", "심사위원이 직접\n조작 가능"),
        ("Protected Probe\nadapter", "operation → core step\n얇은 변환 경계")]
txx = 0.7
tww = 2.36
for t1, t2 in tops:
    c = shape(s, MSO_SHAPE.ROUNDED_RECTANGLE, txx, 1.58, tww, 1.15, fill=TINT2,
              radius=0.08)
    paras = [P(ln, 11.5, INK, True, align=PP_ALIGN.CENTER, space_after=1)
             for ln in t1.split("\n")]
    paras += [P(ln, 9.5, GRAY, align=PP_ALIGN.CENTER)
              for ln in t2.split("\n")]
    card_text(c, paras, anchor=MSO_ANCHOR.MIDDLE, ml=0.06, mr=0.06, mt=0.05,
              mb=0.05)
    shape(s, MSO_SHAPE.DOWN_ARROW, txx + tww / 2 - 0.10, 2.76, 0.20, 0.30,
          fill=ACCENT)
    txx += tww + 0.21

c = shape(s, MSO_SHAPE.ROUNDED_RECTANGLE, 0.7, 3.1, 7.5, 0.75, fill=DARK,
          radius=0.09)
card_text(c, [P("ProductionCore  —  단일 판단 경로 (Probe 전용 판단기·mock state 없음)",
                12.5, WHITE, True, align=PP_ALIGN.CENTER)],
          anchor=MSO_ANCHOR.MIDDLE)
shape(s, MSO_SHAPE.DOWN_ARROW, 4.35, 3.88, 0.20, 0.28, fill=ACCENT)
c = shape(s, MSO_SHAPE.ROUNDED_RECTANGLE, 0.7, 4.19, 7.5, 0.95, fill="35415A",
          radius=0.09)
card_text(c, [
    P("ProductionState repository  +  EvidenceWriter", 12, WHITE, True,
      align=PP_ALIGN.CENTER, space_after=2),
    P("fact · field · session · permission · tombstone · action/outcome ledger · process epoch",
      10, ICE, align=PP_ALIGN.CENTER),
], anchor=MSO_ANCHOR.MIDDLE)
tb(s, 0.7, 5.35, 7.5, 1.4, [
    P("검증 가능성", 12, INK, True, space_after=3),
    P("EvidenceWriter가 모든 판단의 근거를 공식 contract 형태로 기록 — 화면에 보이는 상태와 "
      "export된 evidence가 같은 ledger를 가리킨다. Probe 결과의 evidence ID와 실제 파일이 "
      "일치하지 않으면 export를 성공으로 표시하지 않는다.", 11, GRAY, line=1.25),
])

facts = [
    ("저장 방식", "전체 상태를 하나의 versioned 문서로 — 임시 파일 후 atomic rename"),
    ("재시작 조정", "재시작 시 같은 문서를 읽어 미완료 상태 조정 — 화면·ledger·export가 같은 시점"),
    ("Exactly-once", "주문 내용 기반 idempotency identity — 재시작·중복 event에도 기존 action 재사용"),
    ("지연 outcome", "action에 연결된 별도 ledger 항목 — 도착 전에는 완료로 표시하지 않음"),
]
fy = 1.58
for t1, t2 in facts:
    tb(s, 8.55, fy, 4.1, 1.0, [
        P(t1, 12, ACCENT_DK, True, space_after=2),
        P(t2, 10.5, INK, line=1.2),
    ])
    fy += 1.02
footer_src(s, "MISSION_AND_TECHNICAL_NOTE §5 · ProbeParityTest · ProbeOperationContractTest · INSTALL_AND_USE_GUIDE §7")
notes(s, "[40초] 질의응답 방어의 핵심 슬라이드. 'Probe용 별도 로직이 없다'와 'export 무결성 검사'를 강조 — C5에서 어떤 파일을 열어도 같은 상태임을 예고.")

# ============================================================
# S9 — Mobile lifecycle과 사용자 통제
# ============================================================
s = prs.slides.add_slide(BLANK)
header(s, "05 · 기술 구현", "중단·재개를 state transition의 일부로", 9)

items = [
    ("즉시 지속화", "대화·주문서·memory를 입력 직후 app-local 저장 — 잃어버리는 입력이 없다"),
    ("Process kill 복원", "실제 process 종료 뒤에도 같은 주문 화면과 미완료 질문을 그대로 복원 (메뉴의 \u2018이 process 종료\u2019로 직접 재현 가능)"),
    ("Ledger가 정본", "평가 요청의 정본을 알림이 아니라 ledger에 둠 — 알림 권한 거부에도 상태 보존, 알림은 보조 표시"),
    ("즉시 통제", "\u2018내 취향과 기억\u2019 화면에서 출처·범위·자동 적용 확인, 정정·철회·삭제·전체 Reset 즉시 반영"),
]
iy = 1.62
for i, (t1, t2) in enumerate(items):
    circ = shape(s, MSO_SHAPE.OVAL, 0.7, iy + 0.05, 0.5, 0.5, fill=ACCENT)
    card_text(circ, [P(str(i + 1), 15, WHITE, True, align=PP_ALIGN.CENTER)],
              anchor=MSO_ANCHOR.MIDDLE, ml=0, mr=0, mt=0, mb=0)
    tb(s, 1.4, iy, 6.5, 1.1, [
        P(t1, 13.5, INK, True, space_after=3),
        P(t2, 11.5, GRAY, line=1.22),
    ])
    iy += 1.24

c = shape(s, MSO_SHAPE.ROUNDED_RECTANGLE, 8.35, 1.62, 4.28, 4.7, fill=TINT,
          radius=0.06)
card_text(c, [
    P("PC와 무엇이 다른가", 13, ACCENT_DK, True, space_after=7),
    P("PC의 \u2018연속된 한 session\u2019 가정에서는 process death, 권한 거부, 지연 outcome, "
      "재진입 사이의 정합성이 핵심 설계에서 빠진다.", 11.5, INK, line=1.3,
      space_after=7),
    P("이 앱은 해당 경계를 별도 예외처리가 아니라 state transition의 일부로 구현했다.",
      12, INK, True, line=1.3, space_after=7),
    P("session 경계 = 주문 단위. 새 주문은 새 대화·one-off 영역을 만들지만, 허용된 stable "
      "기억과 action ledger는 이어진다. process 경계 ≠ session 경계.", 11, GRAY,
      line=1.28),
], mt=0.18)
footer_src(s, "MISSION_AND_TECHNICAL_NOTE §7 · DEVICE_TEST_SCRIPTS (process kill 검증) · DEMO_VIDEO restart 구간")
notes(s, "[30초] E4·데모 영상의 restart 장면과 연결. '이 process 종료' 메뉴로 현장에서도 즉시 재현 가능함을 언급 (Q&A 대비).")

# ============================================================
# S10 — 안전·실패 경계 + 권한·온디바이스 범위
# ============================================================
s = prs.slides.add_slide(BLANK)
header(s, "05 · 기술 구현", "실패를 대체하지 않고, 경계에서 멈춘다", 10)

rows = [
    ("열린 확인이 있음", "network와 무관하게 값을 묻고, action을 시작하지 않는다"),
    ("network DELAYED · UNKNOWN", "최신 상태가 불명확하면 주문서를 보존한 채 기다린다 (WAIT)"),
    ("network OFFLINE", "유효한 cache가 없으면 중단 — 미확인 action을 완료로 만들지 않는다"),
    ("예산·시간 만족 후보 없음", "가능한 것처럼 대체하지 않고 \u2018조건을 만족할 수 없음\u2019 표시"),
    ("option 미제공 · 품절", "영향받은 field만 재확인 대상으로, 나머지는 보존"),
    ("알 수 없는 자연어 표현", "의미를 추측하거나 저장하지 않고 선택 가능한 항목을 제시"),
]
ry = 1.55
for i, (k, v) in enumerate(rows):
    fill = TINT2 if i % 2 == 0 else WHITE
    shape(s, MSO_SHAPE.RECTANGLE, 0.7, ry, 11.93, 0.6, fill=fill,
          line_color=LINE, line_w=0.5)
    tb(s, 0.92, ry + 0.09, 3.9, 0.44, [P(k, 11.5, ACCENT_DK, True)],
       anchor=MSO_ANCHOR.MIDDLE)
    tb(s, 5.0, ry + 0.09, 7.4, 0.44, [P(v, 11.5, INK)],
       anchor=MSO_ANCHOR.MIDDLE)
    ry += 0.6

c = shape(s, MSO_SHAPE.ROUNDED_RECTANGLE, 0.7, 5.35, 5.85, 1.5, fill=DARK,
          radius=0.08)
card_text(c, [
    P("네트워크 사용과 온디바이스 범위", 12, ACCENT, True, space_after=4),
    P("INTERNET 권한 없음 · 외부 endpoint 없음 · 온디바이스 model 없음 · inference 0회 "
      "· runtime 데이터 전송 없음 — 모든 판단·저장이 온디바이스에서 완결", 11, ICE,
      line=1.28),
], anchor=MSO_ANCHOR.MIDDLE, mt=0.1, mb=0.1)
c = shape(s, MSO_SHAPE.ROUNDED_RECTANGLE, 6.78, 5.35, 5.85, 1.5, fill=TINT2,
          radius=0.08)
card_text(c, [
    P("자연어 intake와 권한 설계", 12, ACCENT_DK, True, space_after=4),
    P("catalog authored phrase의 longest-match 결정적 parser (확장은 catalog data로) · "
      "POST_NOTIFICATIONS 선택 · ACCESS_NETWORK_STATE 표시 전용", 11, INK, line=1.28),
], anchor=MSO_ANCHOR.MIDDLE, mt=0.1, mb=0.1)
footer_src(s, "MISSION_AND_TECHNICAL_NOTE §6·§10 · AndroidManifest (권한 3종) · OptionAvailabilityTest · RUNTIME_IDENTITY.json (invocation 0)")
notes(s, "[30초] 표는 훑고, 아래 두 상자를 또박또박: '외부로 나가는 경로가 물리적으로 없다'가 결론. RUNTIME_IDENTITY의 cumulative_invocations=0이 증거.")

# ============================================================
# S11 — 검증 ① full/claim-off paired comparison (C3)
# ============================================================
s = prs.slides.add_slide(BLANK)
header(s, "06 · 검증  ·  C3 인과 이득", "같은 APK 안에서 ASPR만 껐다 켠 짝비교", 11)

c = shape(s, MSO_SHAPE.ROUNDED_RECTANGLE, 0.7, 1.55, 6.0, 2.5, fill=TINT2,
          radius=0.06)
card_text(c, [
    P("비교 설계 — 차이는 오직 mechanism", 12.5, ACCENT_DK, True, space_after=6),
    P("동일: APK · 화면 · parser · catalog · 시작 snapshot", 11.5, INK, bullet=True,
      space_after=4),
    P("분리: 두 arm의 storage·evidence namespace", 11.5, INK, bullet=True,
      space_after=4),
    P("claim-off가 끄는 것: ASPR의 lifetime·scope·permission projection만", 11.5, INK,
      bullet=True, space_after=4),
    P("그대로 유지: 삭제·tombstone·idempotency·restart·안전 경계·최종 확인", 11.5, INK,
      bullet=True),
], mt=0.14)

c = shape(s, MSO_SHAPE.ROUNDED_RECTANGLE, 6.93, 1.55, 5.7, 2.5, fill=TINT,
          radius=0.06)
card_text(c, [
    P("지표 VIL — Valid Interaction Load", 12.5, ACCENT_DK, True, space_after=6),
    P("같은 유효 주문서를 완성할 때까지 사용자가 직접 해결한 확인·입력 횟수", 11.5, INK,
      line=1.25, space_after=6),
    P("잘못된 entity 적용, 철회 무시, 미제공 option 자동 대체, 중복 action은 부담 감소로 "
      "인정하지 않는다 → 편의성 이득과 안전장치 약화를 분리해 mechanism의 인과 기여만 관찰",
      11.5, INK, True, line=1.25),
], mt=0.14)

c = shape(s, MSO_SHAPE.ROUNDED_RECTANGLE, 0.7, 4.25, 11.93, 1.35, fill=WHITE,
          line_color=LINE, line_w=0.75, radius=0.05)
card_text(c, [
    P("관찰되는 차이 (E2 재사용 시나리오)", 12, ACCENT_DK, True, space_after=4),
    P("claim-off — 맵기·수저·파·고수를 모두 다시 질문   |   full — 유효·허용된 값만 자동 적용, "
      "출처를 표시하고 새 option만 질문 (미제공 option은 두 arm 모두 묻지도 채우지도 않음)",
      11.5, INK, line=1.3),
], mt=0.12)

c = shape(s, MSO_SHAPE.ROUNDED_RECTANGLE, 0.7, 5.75, 11.93, 1.1, fill=TINT2,
          radius=0.06)
card_text(c, [
    {'runs': [("현재 상태  ", 11.5, ACCENT_DK, True),
              ("비교 구조·앱 내 \u2018비교 실행\u2019 화면·ClaimOffComparisonTest 완비. 기기 VIL 수치는 실측 전 — ",
               11.5, INK, False),
              ("[본선 전 실측해 full vs claim-off 수치로 교체]", 11.5, ACCENT, True)]},
], anchor=MSO_ANCHOR.MIDDLE, mt=0.1, mb=0.1)
footer_src(s, "MISSION_AND_TECHNICAL_NOTE §8 · ComparisonActivity (비교 실행 화면) · ClaimOffComparisonTest · ProductFlowProbeTest(E2)")
notes(s, "[90초] 배점이 가장 큰 축(C3). '무엇이 같고 무엇만 다른가'를 정확히 말하고, VIL의 불인정 규칙(안전 위반은 편의로 안 침)을 강조. 실측 수치를 확보하면 이 슬라이드에 숫자를 넣을 것.")

# ============================================================
# S12 — 검증 ② 공개 Probe·변형·테스트 (C4)
# ============================================================
s = prs.slides.add_slide(BLANK)
header(s, "06 · 검증  ·  C4 장기·변형 재현", "같은 core가 공개 입력·변형 입력·JVM 테스트를 통과", 12)

stats = [("13 / 13", "공개 Probe 13-step 완주\n(서명된 Runner 실행)"),
         ("195", "JVM 테스트\n(debug · release 모두)"),
         ("4", "서명된 독립 변형 run\n(치환·교환·반복·복합)"),
         ("35 / 35", "result 참조 evidence ID\n닫힘 검사 통과")]
sx = 0.7
sw = 2.92
for t1, t2 in stats:
    c = shape(s, MSO_SHAPE.ROUNDED_RECTANGLE, sx, 1.55, sw, 1.42, fill=TINT2,
              radius=0.07)
    card_text(c, [P(t1, 24, ACCENT_DK, True, align=PP_ALIGN.CENTER,
                    space_after=2)] +
              [P(ln, 10, GRAY, align=PP_ALIGN.CENTER)
               for ln in t2.split("\n")],
              anchor=MSO_ANCHOR.MIDDLE, mt=0.06, mb=0.06)
    sx += sw + 0.083

tb(s, 0.7, 3.22, 7.4, 0.3, [P("공개 13-step에서 관찰되는 판단", 13.5, ACCENT, True)])
obs = [
    ("OFFLINE 전환", "WAIT — 미확인 action을 완료로 만들지 않음"),
    ("kill 후 같은 event 재전달", "CONFIRMED_COMPLETE — 기존 action 재사용, 중복 없음"),
    ("순서가 뒤바뀐 event", "ABSTAIN — 추측으로 진행하지 않음"),
    ("삭제 후 재시작", "tombstone 유지 — 삭제된 기억이 부활하지 않음"),
]
oy = 3.58
for k, v in obs:
    shape(s, MSO_SHAPE.RECTANGLE, 0.7, oy, 7.4, 0.56, fill=WHITE,
          line_color=LINE, line_w=0.5)
    tb(s, 0.92, oy + 0.07, 2.75, 0.42, [P(k, 11, INK, True)],
       anchor=MSO_ANCHOR.MIDDLE)
    tb(s, 3.8, oy + 0.07, 4.15, 0.42, [P(v, 10.5, GRAY)],
       anchor=MSO_ANCHOR.MIDDLE)
    oy += 0.56

c = shape(s, MSO_SHAPE.ROUNDED_RECTANGLE, 8.3, 3.22, 4.33, 2.6, fill=TINT,
          radius=0.06)
card_text(c, [
    P("장기·변형에서도 같은 판단 (C4)", 12, ACCENT_DK, True, space_after=5),
    P("role-token 치환 변형(V1): 13단계 판단 동일", 10.5, INK, bullet=True,
      space_after=4),
    P("주문·반복·reset 장기 변형(V3): 18단계 완주", 10.5, INK, bullet=True,
      space_after=4),
    P("kill·중복·역순 복합(V4): 26단계, ASK 2회로 안전 유지", 10.5, INK, bullet=True,
      space_after=4),
    P("시간 경과 후 지연 평가 도착 → 승인 범위가 다음 주문의 다른 식당 초안을 변경", 10.5,
      INK, bullet=True),
], mt=0.12)

c = shape(s, MSO_SHAPE.ROUNDED_RECTANGLE, 0.7, 6.0, 11.93, 0.85, fill=DARK,
          radius=0.07)
card_text(c, [
    {'runs': [("제출물 연결  ", 11, ACCENT, True),
              ("SAMPLE_EXPORT = PROBE_RESULT·evidence·RUNTIME_IDENTITY (digest 연쇄·restart epoch·"
               "zero-invocation 통과)  ·  DEMO_VIDEO = E1→E4 실기기 시연", 11, ICE, False)]},
], anchor=MSO_ANCHOR.MIDDLE, mt=0.08, mb=0.08)
footer_src(s, "SAMPLE_EXPORT/PUBLIC_PROBE_RESULT.json · RC1_RUN_V1~V4 · RC4_PUBLIC_RUN · MetamorphicProbeTest")
notes(s, "[60초] 왼쪽 표의 4개 판단(WAIT/CONFIRMED_COMPLETE/ABSTAIN/tombstone)은 심사 중 재현 요청이 올 수 있는 부분 — step 번호(08·10·11·07+09)까지 기억해 둘 것.")

# ============================================================
# S13 — 실패 사례와 개선 + 남은 한계 (C5)
# ============================================================
s = prs.slides.add_slide(BLANK)
header(s, "06 · 검증  ·  C5 실패경계 설명", "실패에서 배운 것, 남겨둔 것", 13)

tb(s, 0.7, 1.52, 6.0, 0.32, [P("개발 중 실패 → 설계로 흡수", 14, ACCENT, True)])
fails = [
    ("오래된 기억의 잘못된 자동화", "저장 취향이 미제공 option까지 채우려 함 → 임의 대체 금지, 해당 field만 NEEDS_CONFIRMATION 전환"),
    ("재시작·중복 event의 이중 기록", "같은 주문이 두 번 기록될 수 있음 → 내용 기반 idempotency key로 기존 action 재사용"),
    ("삭제된 기억의 부활", "export·재시작 경로에서 삭제값 복원 → tombstone으로 부활을 구조적으로 차단"),
]
fy = 1.9
for t1, t2 in fails:
    c = shape(s, MSO_SHAPE.ROUNDED_RECTANGLE, 0.7, fy, 6.0, 1.38, fill=TINT2,
              radius=0.06)
    card_text(c, [
        P(t1, 12, INK, True, space_after=4),
        P(t2, 10.5, GRAY, line=1.22),
    ], mt=0.11)
    fy += 1.5

tb(s, 7.1, 1.52, 5.55, 0.32, [P("정직하게 남겨둔 검증 한계 (제출 시점)", 14, ACCENT, True)])
c = shape(s, MSO_SHAPE.ROUNDED_RECTANGLE, 7.1, 1.9, 5.55, 3.15, fill=TINT,
          radius=0.06)
card_text(c, [
    P("공식 PRACTICE-B 표면 미실행 — 공개 rehearsal과 자체 변형으로 대체 확인", 11, INK,
      bullet=True, space_after=5, line=1.22),
    P("full vs claim-off VIL 수치는 기기 실측 전 — 비교 구조와 테스트는 완비", 11, INK,
      bullet=True, space_after=5, line=1.22),
    P("delayed outcome은 평가 요청 1종만 검증", 11, INK, bullet=True, space_after=5,
      line=1.22),
    P("network 4상태 중 공개 입력이 밟는 경로만 Runner로, 나머지는 앱 화면·JVM 테스트로 확인",
      11, INK, bullet=True, line=1.22),
], mt=0.14)
tb(s, 7.1, 5.2, 5.55, 1.3, [
    P("한계를 숨기지 않고 anchor 3 이하로 자체 평가했다. 근거 없는 주장을 만들지 않는 것은 "
      "\u2018불확실하면 다시 확인한다\u2019는 이 제품의 설계 원칙과 같다.", 11, GRAY,
      line=1.3),
])
footer_src(s, "SELF_SCORE_2026-08-04.json · ProductFlowProbeTest · ChatIntakeTest")
notes(s, "[45초] C5 대응. 왼쪽 3건은 '실패를 어떻게 구조로 바꿨나'의 스토리로, 오른쪽 한계는 먼저 말해서 Q&A 주도권을 가져올 것.")

# ============================================================
# S14 — 차별점·한계·향후
# ============================================================
s = prs.slides.add_slide(BLANK)
header(s, "07 · 차별점과 한계", "편의성의 인과를 측정 가능하게 만든 것이 차별점", 14)

diffs = [
    ("측정 가능한 인과", "기억 기능을 \u2018있다\u2019고 주장하지 않고, 같은 APK 안 paired comparison과 VIL로 mechanism의 기여를 관찰 가능하게 설계 (검증 ① 참조)"),
    ("선택적 repair", "전체 초기화가 아니라 dependency graph 기반으로 파생 필드만 무효화 — 정정·철회·품절·재시작의 영향 범위가 언제나 최소"),
    ("검증 가능한 구조 자체", "Probe와 제품의 완전한 parity, 화면·export·ledger의 단일 시점, evidence 무결성 검사 — 주장과 산출물이 분리되지 않는다"),
]
dy = 1.58
for t1, t2 in diffs:
    c = shape(s, MSO_SHAPE.ROUNDED_RECTANGLE, 0.7, dy, 7.7, 1.5, fill=TINT2,
              radius=0.06)
    card_text(c, [
        P(t1, 12.5, ACCENT_DK, True, space_after=4),
        P(t2, 11, INK, line=1.24),
    ], mt=0.12)
    dy += 1.63

tb(s, 8.7, 1.58, 3.95, 0.32, [P("현재 한계", 14, ACCENT, True)])
tb(s, 8.7, 1.94, 3.95, 1.75, [
    P("결정적 parser의 표현력 제한 (등록 표현만 이해)", 11, INK, bullet=True,
      space_after=4, line=1.2),
    P("단일 배달 domain의 합성 catalog", 11, INK, bullet=True, space_after=4),
    P("delayed outcome 종류 제한", 11, INK, bullet=True),
])
tb(s, 8.7, 3.85, 3.95, 0.32, [P("향후 개선", 14, ACCENT, True)])
tb(s, 8.7, 4.21, 3.95, 2.3, [
    P("intake 계층에만 온디바이스 소형 LM 결합 — typed fact 계약과 검증 가능성은 유지", 11,
      INK, bullet=True, space_after=4, line=1.2),
    P("VIL 실측 자동화·시나리오 확대", 11, INK, bullet=True, space_after=4),
    P("catalog 일반화로 다중 domain(장보기·예약 등) 확장", 11, INK, bullet=True,
      line=1.2),
])
footer_src(s, "MISSION_AND_TECHNICAL_NOTE §8·§10")
notes(s, "[45초] 차별점 3개는 '주장'이 아니라 모두 앞 슬라이드의 검증과 연결됨을 언급. 향후 개선의 LM 결합은 'core 계약 유지'가 핵심 — 확률적 생성이 판단 경계를 침범하지 않는다.")

# ============================================================
# S15 — 마무리
# ============================================================
s = prs.slides.add_slide(BLANK)
bg(s, DARK)
tb(s, 1.0, 2.1, 11.3, 1.7, [
    P("많이 기억하는 에이전트가 아니라,", 26, ICE, space_after=6),
    P("지금 유효하고 허용된 기억만 쓰는 에이전트", 30, WHITE, True),
])
chip(s, 1.0, 4.35, 1.6, "ASPR")
chip(s, 2.75, 4.35, 2.3, "Production Parity")
chip(s, 5.20, 4.35, 2.2, "Evidence 무결성")
tb(s, 1.0, 5.35, 11.3, 0.9, [
    P("모든 주장은 제출물로 재현됩니다 — APP.apk · SOURCE.zip · SAMPLE_EXPORT · DEMO_VIDEO",
      13, "9AA7BC"),
])
tb(s, 1.0, 6.35, 11.3, 0.5, [P("감사합니다  ·  Q&A", 15, "8B97AB")])
notes(s, "[15초] 핵심 문장 하나로 마무리하고 Q&A로. 질문이 오면 해당 artifact(화면·export·테스트)를 특정해서 답할 것.")

prs.save(r"C:\Users\Infocar\SCPC2\finals_presentation\draft_deck\SCPC2026_본선발표자료_First_penguin_v2.pptx")
print("saved 15 slides")
