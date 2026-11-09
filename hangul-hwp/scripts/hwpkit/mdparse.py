"""Markdown (the subset people actually write for documents) and JSON -> the document model.

Markdown that is understood:

    # 제목 1 ... ###### 제목 6
    일반 문단 (한 줄이 한 문단. 빈 줄은 무시)
    **굵게**  *기울임*  ~~취소선~~  <u>밑줄</u>  `코드`
    - 글머리표 / * 글머리표 (들여쓰면 하위 수준)
    1. 번호 목록 (번호는 적힌 그대로 둔다)
    > 인용 (왼쪽 여백을 둔 문단)
    | 표 | 머리글 |      GFM 표. 첫 줄이 머리글(굵게, 음영)
    <center>가운데</center>  <right>오른쪽</right>  <p align="center">...</p>
    <!-- pagebreak -->  또는  \\newpage   쪽 나눔
    ---                                    빈 줄 하나

Lines are paragraphs: Korean documents are written a line per paragraph far more often than as
reflowed prose, so joining lines (as Markdown renderers do) would merge what the author separated.
"""

import json
import re

from .model import Cell, Document, Paragraph, Table

_INLINE = re.compile(
    r"(\*\*\*(?P<bi>.+?)\*\*\*)"
    r"|(\*\*(?P<b>.+?)\*\*)|(__(?P<b2>.+?)__)"
    r"|(~~(?P<s>.+?)~~)"
    r"|(<u>(?P<u>.+?)</u>)"
    r"|(\*(?P<i>[^*\s][^*]*?)\*)|((?<![\w])_(?P<i2>[^_\s][^_]*?)_(?![\w]))"
    r"|(`(?P<code>[^`]+)`)"
    r"|(\[(?P<lt>[^\]]+)\]\((?P<lu>[^)\s]+)\))"
    r"|(\\(?P<esc>[\\`*_{}\[\]()#+\-.!~<>|]))",
    re.S)


def inline_runs(text, base=None):
    """Splits inline Markdown into [(text, style)]."""
    base = dict(base or {})
    runs = []

    def add(t, st):
        if not t:
            return
        if runs and runs[-1][1] == st:
            runs[-1] = (runs[-1][0] + t, st)
        else:
            runs.append((t, st))

    pos = 0
    for m in _INLINE.finditer(text):
        add(text[pos:m.start()], base)
        g = m.groupdict()
        if g["bi"] is not None:
            for t, st in inline_runs(g["bi"], dict(base, bold=True, italic=True)):
                add(t, st)
        elif g["b"] is not None or g["b2"] is not None:
            for t, st in inline_runs(g["b"] if g["b"] is not None else g["b2"], dict(base, bold=True)):
                add(t, st)
        elif g["s"] is not None:
            for t, st in inline_runs(g["s"], dict(base, strike=True)):
                add(t, st)
        elif g["u"] is not None:
            for t, st in inline_runs(g["u"], dict(base, underline=True)):
                add(t, st)
        elif g["i"] is not None or g["i2"] is not None:
            for t, st in inline_runs(g["i"] if g["i"] is not None else g["i2"], dict(base, italic=True)):
                add(t, st)
        elif g["code"] is not None:
            add(g["code"], base)
        elif g["lt"] is not None:
            label, url = g["lt"], g["lu"]
            add(label if label == url else "%s(%s)" % (label, url), base)
        elif g["esc"] is not None:
            add(g["esc"], base)
        pos = m.end()
    add(text[pos:], base)
    return runs


def _plain(runs):
    return "".join(t for t, _ in runs)


def _para(text, base=None, **kw):
    text = text.replace("<br>", "\n").replace("<br/>", "\n").replace("<br />", "\n")
    runs = inline_runs(text, base)
    return Paragraph(_plain(runs), runs=runs, **kw)


_ALIGN_TAG = re.compile(r"^\s*<(center|right|left)>(.*)</\1>\s*$", re.S | re.I)
_ALIGN_P = re.compile(r"^\s*<p\s+align=[\"'](center|right|left|justify)[\"']\s*>(.*)</p>\s*$", re.S | re.I)
_TABLE_SEP = re.compile(r"^\s*\|?\s*:?-{2,}:?\s*(\|\s*:?-{2,}:?\s*)*\|?\s*$")
_LIST = re.compile(r"^(\s*)([-*+]|\d+[.)])\s+(.*)$")
_PAGEBREAK = re.compile(r"^\s*(<!--\s*page\s*-?break\s*-->|\\newpage|\\pagebreak)\s*$", re.I)


def _split_row(line):
    s = line.strip()
    if s.startswith("|"):
        s = s[1:]
    if s.endswith("|") and not s.endswith("\\|"):
        s = s[:-1]
    cells, buf, i = [], [], 0
    while i < len(s):
        if s[i] == "\\" and i + 1 < len(s) and s[i + 1] == "|":
            buf.append("|")
            i += 2
            continue
        if s[i] == "|":
            cells.append("".join(buf).strip())
            buf = []
        else:
            buf.append(s[i])
        i += 1
    cells.append("".join(buf).strip())
    return cells


def _aligns(sep_line):
    out = []
    for c in _split_row(sep_line):
        c = c.strip()
        if c.startswith(":") and c.endswith(":"):
            out.append("center")
        elif c.endswith(":"):
            out.append("right")
        else:
            out.append(None)
    return out


def parse_markdown(text):
    lines = text.replace("\r\n", "\n").replace("\r", "\n").split("\n")
    blocks = []
    pending_break = False
    i, n = 0, len(lines)

    def push(b):
        nonlocal pending_break
        if pending_break:
            if not isinstance(b, Paragraph):
                blocks.append(Paragraph("", runs=[], page_break=True))
            else:
                b.page_break = True
            pending_break = False
        blocks.append(b)

    while i < n:
        line = lines[i]
        stripped = line.strip()
        if not stripped:
            i += 1
            continue
        if _PAGEBREAK.match(line):
            pending_break = True
            i += 1
            continue
        if stripped.startswith("```"):
            i += 1
            while i < n and not lines[i].strip().startswith("```"):
                push(Paragraph(lines[i], runs=[(lines[i], {})]))
                i += 1
            i += 1
            continue
        if re.fullmatch(r"(-{3,}|\*{3,}|_{3,})", stripped):
            push(Paragraph("", runs=[]))
            i += 1
            continue
        m = re.match(r"^(#{1,6})\s+(.*?)\s*#*\s*$", stripped)
        if m:
            level = len(m.group(1))
            body, align = m.group(2), None
            am = _ALIGN_TAG.match(body) or _ALIGN_P.match(body)
            if am:  # "# <center>제목</center>": a centred heading
                body, align = am.group(2), am.group(1).lower()
            push(_para(body, heading=level, align=align))
            i += 1
            continue
        if "|" in stripped and i + 1 < n and _TABLE_SEP.match(lines[i + 1]):
            header = _split_row(line)
            aligns = _aligns(lines[i + 1])
            rows = [header]
            i += 2
            while i < n and lines[i].strip() and "|" in lines[i]:
                rows.append(_split_row(lines[i]))
                i += 1
            ncols = max(len(r) for r in rows)
            cells = []
            for r, row in enumerate(rows):
                for c in range(ncols):
                    txt = row[c] if c < len(row) else ""
                    align = aligns[c] if c < len(aligns) else None
                    if r == 0:
                        align = "center"
                    p = _para(txt, {"bold": True} if r == 0 else None, align=align)
                    cells.append(Cell(r, c, blocks=[p], header=(r == 0)))
            push(Table(cells, len(rows), ncols))
            continue
        m = _ALIGN_TAG.match(stripped) or _ALIGN_P.match(stripped)
        if m:
            push(_para(m.group(2), align=m.group(1).lower()))
            i += 1
            continue
        m = _LIST.match(line)
        if m:
            indent = len(m.group(1).replace("\t", "    "))
            level = min(indent // 2, 4)
            marker = m.group(2)
            body = m.group(3)
            if marker[0].isdigit():
                prefix = marker + " "
            else:
                prefix = ("• ", "◦ ", "▪ ", "- ", "· ")[level]
            push(_para(prefix + body, indent=level + 1))
            i += 1
            continue
        if stripped.startswith(">"):
            body = re.sub(r"^>\s?", "", stripped)
            push(_para(body, indent=1))
            i += 1
            continue
        push(_para(stripped))
        i += 1
    if pending_break:
        blocks.append(Paragraph("", runs=[], page_break=True))
    return Document(blocks)


# ---- JSON ---------------------------------------------------------------------------------

def parse_json(text):
    """The precise form, for when Markdown is not enough::

        {"page": {"orientation": "landscape", "margins_mm": {"left": 20, "right": 20, "top": 15, "bottom": 15}},
         "font": "맑은 고딕", "size": 11,
         "blocks": [
           {"type": "heading", "level": 1, "text": "제목"},
           {"type": "paragraph", "align": "center", "indent": 1,
            "runs": [{"text": "굵은 글", "bold": true, "size": 14, "color": "#C00000"}, {"text": " 보통 글"}]},
           {"type": "paragraph", "text": "**마크다운 강조**도 됩니다"},
           {"type": "table", "header": true, "col_widths": [1, 3],
            "rows": [["항목", "내용"], ["가", "나"]],
            "merges": [{"row": 0, "col": 0, "rowspan": 1, "colspan": 2}]},
           {"type": "page_break"}
         ]}
    """
    spec = json.loads(text)
    doc = Document([])
    doc.meta["page"] = spec.get("page") or {}
    doc.meta["font"] = spec.get("font")
    doc.meta["size"] = spec.get("size")
    pending_break = False
    for b in spec.get("blocks", []):
        kind = b.get("type", "paragraph")
        if kind == "page_break":
            pending_break = True
            continue
        if kind in ("paragraph", "heading"):
            if "runs" in b:
                runs = []
                for r in b["runs"]:
                    st = {k: v for k, v in r.items() if k != "text"}
                    runs.append((r.get("text", ""), st))
                p = Paragraph(_plain(runs), runs=runs)
            else:
                p = _para(b.get("text", ""))
            p.align = b.get("align")
            if kind == "heading":
                p.heading = int(b.get("level", 1))
            p.indent = int(b.get("indent") or 0)
            block = p
        elif kind == "table":
            rows = b.get("rows", [])
            header = b.get("header", True)
            ncols = max([len(r) for r in rows] or [0])
            merges = {(m["row"], m["col"]): m for m in b.get("merges", [])}
            covered = set()
            for (r0, c0), m in merges.items():
                for r in range(r0, r0 + m.get("rowspan", 1)):
                    for c in range(c0, c0 + m.get("colspan", 1)):
                        if (r, c) != (r0, c0):
                            covered.add((r, c))
            cells = []
            for r, row in enumerate(rows):
                for c in range(ncols):
                    if (r, c) in covered:
                        continue
                    val = row[c] if c < len(row) else ""
                    is_head = bool(header) and r == 0
                    if isinstance(val, dict):
                        txt = val.get("text", "")
                        st = {k: v for k, v in val.items() if k not in ("text", "align")}
                        align = val.get("align", "center" if is_head else None)
                    else:
                        txt, st, align = str(val), {}, ("center" if is_head else None)
                    if is_head:
                        st = dict(st, bold=True)
                    paras = [_para(line, st, align=align) for line in str(txt).split("\n")]
                    m = merges.get((r, c), {})
                    cells.append(Cell(r, c, m.get("rowspan", 1), m.get("colspan", 1), paras, header=is_head))
            block = Table(cells, len(rows), ncols, col_widths=b.get("col_widths"))
        else:
            raise ValueError("알 수 없는 블록 종류: %r" % kind)
        if pending_break:
            if isinstance(block, Paragraph):
                block.page_break = True
            else:
                doc.blocks.append(Paragraph("", runs=[], page_break=True))
            pending_break = False
        doc.blocks.append(block)
    return doc
