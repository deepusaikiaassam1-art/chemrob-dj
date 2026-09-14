"""
Minimal OOXML writer - enough to produce a clean report .docx with no
third-party dependencies.

A .docx is a ZIP of XML parts, so this builds the four parts Word needs
(content types, package relationships, styles, document body) and zips them.
Written because neither python-docx, docx-js, pandoc nor LibreOffice is present
on this machine, and adding a dependency to emit one report is a poor trade.

Supports headings, paragraphs with inline bold/italic/mono runs, bordered
tables with a shaded header row, bullet lists, page breaks and a footer note.
Deliberately does not attempt fields, images or a table of contents.
"""
from __future__ import annotations

import zipfile
from pathlib import Path
from xml.sax.saxutils import escape

W = 'xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"'

# US Letter portrait, 1 inch margins. DXA: 1440 per inch.
PAGE_W, PAGE_H, MARGIN = 12240, 15840, 1440
CONTENT_W = PAGE_W - 2 * MARGIN          # 9360

INK = "14171D"
ACCENT = "1F4FA8"
MUTED = "5B6572"
RULE = "DDE2E8"
HEADER_BG = "F1F4F8"
WARN = "B4361F"
GOOD = "1E6F4C"


def _esc(t: str) -> str:
    return escape(str(t))


def run(text: str, *, bold=False, italic=False, mono=False, size=20,
        color=INK, space_preserve=True) -> str:
    props = []
    if mono:
        props.append('<w:rFonts w:ascii="Consolas" w:hAnsi="Consolas"/>')
    if bold:
        props.append("<w:b/>")
    if italic:
        props.append("<w:i/>")
    props.append(f'<w:sz w:val="{size}"/><w:szCs w:val="{size}"/>')
    props.append(f'<w:color w:val="{color}"/>')
    sp = ' xml:space="preserve"' if space_preserve else ""
    return (f"<w:r><w:rPr>{''.join(props)}</w:rPr>"
            f"<w:t{sp}>{_esc(text)}</w:t></w:r>")


def para(runs: str | list[str], *, style: str | None = None, before=0, after=120,
         align: str | None = None, border_bottom=False, indent=0) -> str:
    if isinstance(runs, str):
        runs = [run(runs)]
    pr = []
    if style:
        pr.append(f'<w:pStyle w:val="{style}"/>')
    if align:
        pr.append(f'<w:jc w:val="{align}"/>')
    if indent:
        pr.append(f'<w:ind w:left="{indent}"/>')
    if border_bottom:
        pr.append(f'<w:pBdr><w:bottom w:val="single" w:sz="6" w:space="4" '
                  f'w:color="{RULE}"/></w:pBdr>')
    pr.append(f'<w:spacing w:before="{before}" w:after="{after}"/>')
    return f"<w:p><w:pPr>{''.join(pr)}</w:pPr>{''.join(runs)}</w:p>"


def heading(text: str, level: int = 1) -> str:
    sizes = {1: 32, 2: 26, 3: 22}
    colors = {1: INK, 2: ACCENT, 3: INK}
    return para(
        [run(text, bold=True, size=sizes.get(level, 22), color=colors.get(level, INK))],
        style=f"Heading{level}",
        before=280 if level > 1 else 0, after=120,
        border_bottom=(level == 1),
    )


def bullet(text: str, *, bold_prefix: str | None = None) -> str:
    runs = [run("•   ", color=ACCENT, bold=True)]
    if bold_prefix:
        runs.append(run(bold_prefix, bold=True))
    runs.append(run(text))
    return para(runs, indent=200, after=80)


def table(headers: list[str], rows: list[list], widths: list[int],
          *, aligns: list[str] | None = None, size=18,
          highlight: dict[int, str] | None = None) -> str:
    """`highlight` maps a row index to a hex colour for its whole row."""
    assert sum(widths) <= CONTENT_W + 5, f"columns sum to {sum(widths)}"
    aligns = aligns or ["left"] * len(headers)
    highlight = highlight or {}

    grid = "".join(f'<w:gridCol w:w="{w}"/>' for w in widths)
    borders = "".join(
        f'<w:{e} w:val="single" w:sz="4" w:space="0" w:color="{RULE}"/>'
        for e in ("top", "left", "bottom", "right", "insideH", "insideV")
    )
    tbl = [
        f'<w:tbl><w:tblPr><w:tblW w:w="{sum(widths)}" w:type="dxa"/>'
        f"<w:tblBorders>{borders}</w:tblBorders>"
        f'<w:tblLayout w:type="fixed"/></w:tblPr>'
        f"<w:tblGrid>{grid}</w:tblGrid>"
    ]

    cells = []
    for h, w, a in zip(headers, widths, aligns):
        cells.append(
            f'<w:tc><w:tcPr><w:tcW w:w="{w}" w:type="dxa"/>'
            f'<w:shd w:val="clear" w:color="auto" w:fill="{HEADER_BG}"/>'
            f"</w:tcPr>"
            f"{para([run(h, bold=True, size=size, color=MUTED)], align=a, after=40)}"
            f"</w:tc>"
        )
    tbl.append(f"<w:tr><w:trPr><w:tblHeader/></w:trPr>{''.join(cells)}</w:tr>")

    for i, row in enumerate(rows):
        colour = highlight.get(i, INK)
        bold_row = i in highlight
        cells = []
        for val, w, a in zip(row, widths, aligns):
            mono = a == "right"
            cells.append(
                f'<w:tc><w:tcPr><w:tcW w:w="{w}" w:type="dxa"/></w:tcPr>'
                f"{para([run(val, size=size, color=colour, bold=bold_row, mono=mono)], align=a, after=40)}"
                f"</w:tc>"
            )
        tbl.append(f"<w:tr>{''.join(cells)}</w:tr>")

    tbl.append("</w:tbl>")
    # Word merges consecutive tables that are not separated by a paragraph.
    tbl.append(para([run("")], after=140))
    return "".join(tbl)


def callout(label: str, text: str, *, colour: str = WARN) -> str:
    return (
        f'<w:tbl><w:tblPr><w:tblW w:w="{CONTENT_W}" w:type="dxa"/>'
        f'<w:tblBorders><w:left w:val="single" w:sz="18" w:space="0" w:color="{colour}"/>'
        f'<w:top w:val="single" w:sz="4" w:space="0" w:color="{RULE}"/>'
        f'<w:bottom w:val="single" w:sz="4" w:space="0" w:color="{RULE}"/>'
        f'<w:right w:val="single" w:sz="4" w:space="0" w:color="{RULE}"/>'
        f"</w:tblBorders><w:tblLayout w:type=\"fixed\"/></w:tblPr>"
        f'<w:tblGrid><w:gridCol w:w="{CONTENT_W}"/></w:tblGrid>'
        f'<w:tr><w:tc><w:tcPr><w:tcW w:w="{CONTENT_W}" w:type="dxa"/></w:tcPr>'
        f"{para([run(label.upper(), bold=True, size=16, color=colour)], after=40)}"
        f"{para([run(text)], after=40)}"
        f"</w:tc></w:tr></w:tbl>"
        + para([run("")], after=140)
    )


def page_break() -> str:
    return '<w:p><w:r><w:br w:type="page"/></w:r></w:p>'


_STYLES = f"""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:styles {W}>
<w:docDefaults><w:rPrDefault><w:rPr>
<w:rFonts w:ascii="Calibri" w:hAnsi="Calibri" w:cs="Calibri"/>
<w:sz w:val="20"/><w:szCs w:val="20"/></w:rPr></w:rPrDefault></w:docDefaults>
<w:style w:type="paragraph" w:default="1" w:styleId="Normal">
<w:name w:val="Normal"/><w:qFormat/></w:style>
{''.join(
    f'<w:style w:type="paragraph" w:styleId="Heading{i}">'
    f'<w:name w:val="heading {i}"/><w:basedOn w:val="Normal"/>'
    f'<w:next w:val="Normal"/><w:qFormat/>'
    f'<w:pPr><w:outlineLvl w:val="{i - 1}"/></w:pPr>'
    f'<w:rPr><w:b/></w:rPr></w:style>'
    for i in (1, 2, 3)
)}
</w:styles>"""

_CONTENT_TYPES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
<Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>
</Types>"""

_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
</Relationships>"""

_DOC_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
</Relationships>"""


def write_docx(path: str | Path, body: list[str]) -> Path:
    sect = (
        f'<w:sectPr><w:pgSz w:w="{PAGE_W}" w:h="{PAGE_H}"/>'
        f'<w:pgMar w:top="{MARGIN}" w:right="{MARGIN}" w:bottom="{MARGIN}" '
        f'w:left="{MARGIN}" w:header="720" w:footer="720" w:gutter="0"/></w:sectPr>'
    )
    document = (
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
        f"<w:document {W}><w:body>{''.join(body)}{sect}</w:body></w:document>"
    )
    path = Path(path)
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("[Content_Types].xml", _CONTENT_TYPES)
        z.writestr("_rels/.rels", _RELS)
        z.writestr("word/document.xml", document)
        z.writestr("word/styles.xml", _STYLES)
        z.writestr("word/_rels/document.xml.rels", _DOC_RELS)
    return path
