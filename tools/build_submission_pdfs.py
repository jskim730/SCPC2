"""Render the two final Korean submission documents from their Markdown sources.

The renderer is intentionally dependency-light: ReportLab plus the Windows
Malgun Gothic fonts. It preserves headings, paragraphs, block quotes, lists,
code blocks and Markdown tables, then adds document headers and page numbers.
"""

from __future__ import annotations

import argparse
import html
import re
from pathlib import Path

from reportlab.lib import colors
from reportlab.lib.enums import TA_CENTER, TA_LEFT
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle, getSampleStyleSheet
from reportlab.lib.units import mm
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.platypus import (
    HRFlowable,
    KeepTogether,
    LongTable,
    Paragraph,
    SimpleDocTemplate,
    Spacer,
    TableStyle,
    XPreformatted,
)


INK = colors.HexColor("#17242C")
MUTED = colors.HexColor("#5A6972")
TEAL = colors.HexColor("#006F74")
TEAL_PALE = colors.HexColor("#E8F4F3")
ORANGE = colors.HexColor("#E16B3A")
RULE = colors.HexColor("#D6E0E2")
TABLE_HEAD = colors.HexColor("#DDEEEE")
TABLE_ALT = colors.HexColor("#F7FAFA")
CODE_BG = colors.HexColor("#F2F5F5")


def register_fonts() -> None:
    fonts = Path(r"C:\Windows\Fonts")
    pdfmetrics.registerFont(TTFont("Malgun", fonts / "malgun.ttf"))
    pdfmetrics.registerFont(TTFont("Malgun-Bold", fonts / "malgunbd.ttf"))
    pdfmetrics.registerFontFamily(
        "Malgun",
        normal="Malgun",
        bold="Malgun-Bold",
        italic="Malgun",
        boldItalic="Malgun-Bold",
    )


def inline_markup(text: str) -> str:
    """Translate the small inline Markdown subset used by the source docs."""

    placeholders: list[str] = []

    def hold_code(match: re.Match[str]) -> str:
        value = html.escape(match.group(1), quote=False)
        placeholders.append(
            f'<font name="Malgun" color="#075E63" backColor="#EDF5F5">{value}</font>'
        )
        return f"\x00CODE{len(placeholders) - 1}\x00"

    value = re.sub(r"`([^`]+)`", hold_code, text)
    value = html.escape(value, quote=False)
    value = re.sub(r"\*\*(.+?)\*\*", r"<b>\1</b>", value)
    value = re.sub(r"(?<!\*)\*([^*]+)\*(?!\*)", r"<i>\1</i>", value)
    value = re.sub(r"\[([^\]]+)\]\(([^)]+)\)", r'<u color="#006F74">\1</u>', value)
    for index, replacement in enumerate(placeholders):
        value = value.replace(f"\x00CODE{index}\x00", replacement)
    return value


def make_styles() -> dict[str, ParagraphStyle]:
    base = getSampleStyleSheet()
    return {
        "title": ParagraphStyle(
            "TitleK",
            parent=base["Title"],
            fontName="Malgun-Bold",
            fontSize=22,
            leading=29,
            textColor=INK,
            alignment=TA_LEFT,
            spaceAfter=8 * mm,
            keepWithNext=True,
            wordWrap="CJK",
        ),
        "h2": ParagraphStyle(
            "H2K",
            parent=base["Heading2"],
            fontName="Malgun-Bold",
            fontSize=14.2,
            leading=20,
            textColor=TEAL,
            spaceBefore=7 * mm,
            spaceAfter=2.6 * mm,
            keepWithNext=True,
            wordWrap="CJK",
        ),
        "h3": ParagraphStyle(
            "H3K",
            parent=base["Heading3"],
            fontName="Malgun-Bold",
            fontSize=11.4,
            leading=16,
            textColor=INK,
            spaceBefore=4.5 * mm,
            spaceAfter=1.8 * mm,
            keepWithNext=True,
            wordWrap="CJK",
        ),
        "h4": ParagraphStyle(
            "H4K",
            parent=base["Heading4"],
            fontName="Malgun-Bold",
            fontSize=9.8,
            leading=14,
            textColor=ORANGE,
            spaceBefore=3.5 * mm,
            spaceAfter=1.4 * mm,
            keepWithNext=True,
            wordWrap="CJK",
        ),
        "body": ParagraphStyle(
            "BodyK",
            parent=base["BodyText"],
            fontName="Malgun",
            fontSize=8.8,
            leading=13.8,
            textColor=INK,
            spaceAfter=1.8 * mm,
            wordWrap="CJK",
            splitLongWords=True,
        ),
        "meta": ParagraphStyle(
            "MetaK",
            parent=base["BodyText"],
            fontName="Malgun",
            fontSize=9,
            leading=14,
            textColor=MUTED,
            spaceAfter=2.5 * mm,
            wordWrap="CJK",
        ),
        "quote": ParagraphStyle(
            "QuoteK",
            parent=base["BodyText"],
            fontName="Malgun",
            fontSize=8.5,
            leading=14,
            textColor=INK,
            leftIndent=5 * mm,
            rightIndent=3 * mm,
            borderColor=TEAL,
            borderWidth=0,
            borderLeftWidth=2.2,
            borderPadding=(3 * mm, 3 * mm, 3 * mm, 4 * mm),
            backColor=TEAL_PALE,
            spaceBefore=1.5 * mm,
            spaceAfter=2.5 * mm,
            wordWrap="CJK",
        ),
        "bullet": ParagraphStyle(
            "BulletK",
            parent=base["BodyText"],
            fontName="Malgun",
            fontSize=8.6,
            leading=13.8,
            textColor=INK,
            leftIndent=7 * mm,
            firstLineIndent=-3.5 * mm,
            bulletIndent=2 * mm,
            bulletFontName="Malgun",
            bulletFontSize=8,
            spaceAfter=1 * mm,
            wordWrap="CJK",
        ),
        "number": ParagraphStyle(
            "NumberK",
            parent=base["BodyText"],
            fontName="Malgun",
            fontSize=8.6,
            leading=13.8,
            textColor=INK,
            leftIndent=8 * mm,
            firstLineIndent=-5 * mm,
            bulletIndent=1.5 * mm,
            bulletFontName="Malgun",
            bulletFontSize=8,
            spaceAfter=1 * mm,
            wordWrap="CJK",
        ),
        "code": ParagraphStyle(
            "CodeK",
            parent=base["Code"],
            fontName="Malgun",
            fontSize=7.1,
            leading=10.5,
            textColor=colors.HexColor("#24343A"),
            leftIndent=3 * mm,
            rightIndent=3 * mm,
            borderColor=RULE,
            borderWidth=0.5,
            borderPadding=3 * mm,
            backColor=CODE_BG,
            spaceBefore=1.5 * mm,
            spaceAfter=3 * mm,
            wordWrap="CJK",
        ),
        "cell": ParagraphStyle(
            "CellK",
            parent=base["BodyText"],
            fontName="Malgun",
            fontSize=7.35,
            leading=10.8,
            textColor=INK,
            wordWrap="CJK",
        ),
        "cell_head": ParagraphStyle(
            "CellHeadK",
            parent=base["BodyText"],
            fontName="Malgun-Bold",
            fontSize=7.45,
            leading=10.8,
            textColor=INK,
            wordWrap="CJK",
        ),
    }


def split_table_row(line: str) -> list[str]:
    text = line.strip()
    if text.startswith("|"):
        text = text[1:]
    if text.endswith("|"):
        text = text[:-1]
    return [cell.strip() for cell in re.split(r"(?<!\\)\|", text)]


def is_table_separator(line: str) -> bool:
    cells = split_table_row(line)
    return bool(cells) and all(re.fullmatch(r":?-{3,}:?", cell) for cell in cells)


def table_widths(rows: list[list[str]], total: float) -> list[float]:
    columns = max(len(row) for row in rows)
    weights: list[float] = []
    for column in range(columns):
        values = [row[column] if column < len(row) else "" for row in rows]
        peak = max((sum(2 if ord(ch) > 127 else 1 for ch in value) for value in values), default=1)
        weights.append(max(5.0, min(float(peak), 34.0)))
    raw = [total * weight / sum(weights) for weight in weights]
    minimum = 20 * mm if columns <= 4 else 14 * mm
    adjusted = [max(minimum, width) for width in raw]
    return [width * total / sum(adjusted) for width in adjusted]


def make_table(rows: list[list[str]], styles: dict[str, ParagraphStyle], width: float):
    columns = max(len(row) for row in rows)
    normalized = [row + [""] * (columns - len(row)) for row in rows]
    data = []
    for row_index, row in enumerate(normalized):
        style = styles["cell_head"] if row_index == 0 else styles["cell"]
        data.append([Paragraph(inline_markup(cell), style) for cell in row])
    table = LongTable(
        data,
        colWidths=table_widths(normalized, width),
        repeatRows=1,
        hAlign="LEFT",
        splitByRow=1,
        spaceBefore=1.5 * mm,
        spaceAfter=2.5 * mm,
    )
    commands = [
        ("BACKGROUND", (0, 0), (-1, 0), TABLE_HEAD),
        ("TEXTCOLOR", (0, 0), (-1, 0), INK),
        ("GRID", (0, 0), (-1, -1), 0.35, RULE),
        ("VALIGN", (0, 0), (-1, -1), "TOP"),
        ("LEFTPADDING", (0, 0), (-1, -1), 4),
        ("RIGHTPADDING", (0, 0), (-1, -1), 4),
        ("TOPPADDING", (0, 0), (-1, -1), 4),
        ("BOTTOMPADDING", (0, 0), (-1, -1), 4),
    ]
    for row_index in range(2, len(data), 2):
        commands.append(("BACKGROUND", (0, row_index), (-1, row_index), TABLE_ALT))
    table.setStyle(TableStyle(commands))
    return table


def markdown_story(source: Path, styles: dict[str, ParagraphStyle], content_width: float):
    lines = source.read_text(encoding="utf-8-sig").splitlines()
    story = []
    index = 0
    first_heading = True

    while index < len(lines):
        line = lines[index]
        stripped = line.strip()
        if not stripped:
            index += 1
            continue

        if stripped.startswith("```"):
            index += 1
            block = []
            while index < len(lines) and not lines[index].strip().startswith("```"):
                block.append(lines[index].rstrip())
                index += 1
            index += 1
            story.append(XPreformatted(html.escape("\n".join(block)), styles["code"]))
            continue

        heading = re.match(r"^(#{1,4})\s+(.+)$", stripped)
        if heading:
            level = len(heading.group(1))
            key = "title" if level == 1 else f"h{level}"
            if first_heading:
                story.append(Spacer(1, 5 * mm))
                first_heading = False
            story.append(Paragraph(inline_markup(heading.group(2)), styles[key]))
            index += 1
            continue

        if stripped in {"---", "***", "___"}:
            story.append(HRFlowable(width="100%", thickness=0.65, color=RULE, spaceBefore=2 * mm, spaceAfter=3 * mm))
            index += 1
            continue

        if stripped.startswith("|") and index + 1 < len(lines) and is_table_separator(lines[index + 1]):
            rows = [split_table_row(stripped)]
            index += 2
            while index < len(lines) and lines[index].strip().startswith("|"):
                rows.append(split_table_row(lines[index]))
                index += 1
            story.append(make_table(rows, styles, content_width))
            continue

        if stripped.startswith(">"):
            quote = []
            while index < len(lines) and (lines[index].strip().startswith(">") or not lines[index].strip()):
                current = lines[index].strip()
                if current.startswith(">"):
                    quote.append(current[1:].strip())
                elif quote and quote[-1] != "":
                    quote.append("")
                index += 1
            text = "<br/>".join(inline_markup(part) for part in quote if part or len(quote) == 1)
            story.append(Paragraph(text, styles["quote"]))
            continue

        bullet = re.match(r"^\s*[-*+]\s+(.+)$", line)
        numbered = re.match(r"^\s*(\d+)\.\s+(.+)$", line)
        if bullet or numbered:
            if bullet:
                text = bullet.group(1)
                story.append(Paragraph(inline_markup(text), styles["bullet"], bulletText="•"))
            else:
                label = numbered.group(1) + "."
                story.append(Paragraph(inline_markup(numbered.group(2)), styles["number"], bulletText=label))
            index += 1
            continue

        paragraph = [stripped]
        index += 1
        while index < len(lines):
            candidate = lines[index].strip()
            if not candidate:
                break
            if (
                candidate.startswith(("#", ">", "```", "|"))
                or candidate in {"---", "***", "___"}
                or re.match(r"^\s*[-*+]\s+", lines[index])
                or re.match(r"^\s*\d+\.\s+", lines[index])
            ):
                break
            paragraph.append(candidate)
            index += 1
        style = styles["meta"] if len(story) == 1 else styles["body"]
        story.append(Paragraph(inline_markup(" ".join(paragraph)), style))

    return story


def build_pdf(source: Path, destination: Path, short_title: str) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    styles = make_styles()
    left = right = 17 * mm
    top = 16 * mm
    bottom = 13 * mm
    content_width = A4[0] - left - right

    def decorate(canvas, document):
        canvas.saveState()
        page = canvas.getPageNumber()
        canvas.setStrokeColor(RULE)
        canvas.setLineWidth(0.45)
        canvas.line(left, A4[1] - 12 * mm, A4[0] - right, A4[1] - 12 * mm)
        canvas.setFont("Malgun-Bold", 7.4)
        canvas.setFillColor(TEAL)
        canvas.drawString(left, A4[1] - 9.2 * mm, short_title)
        canvas.setFont("Malgun", 7.2)
        canvas.setFillColor(MUTED)
        canvas.drawRightString(A4[0] - right, 8.2 * mm, f"First_penguin  ·  {page}")
        canvas.restoreState()

    document = SimpleDocTemplate(
        str(destination),
        pagesize=A4,
        leftMargin=left,
        rightMargin=right,
        topMargin=top,
        bottomMargin=bottom,
        title=short_title,
        author="First_penguin",
        subject="SCPC 2026 AI Challenge submission",
        pageCompression=1,
    )
    story = markdown_story(source, styles, content_width)
    document.build(story, onFirstPage=decorate, onLaterPages=decorate)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parent.parent)
    parser.add_argument("--output-dir", type=Path)
    args = parser.parse_args()
    root = args.root.resolve()
    output = (args.output_dir or (root / "output" / "pdf")).resolve()
    register_fonts()
    jobs = (
        (root / "MISSION_AND_TECHNICAL_NOTE.md", output / "MISSION_AND_TECHNICAL_NOTE.pdf", "MISSION & TECHNICAL NOTE"),
        (root / "INSTALL_AND_USE_GUIDE.md", output / "INSTALL_AND_USE_GUIDE.pdf", "INSTALL & USE GUIDE"),
    )
    for source, destination, title in jobs:
        build_pdf(source, destination, title)
        print(f"built {destination}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
