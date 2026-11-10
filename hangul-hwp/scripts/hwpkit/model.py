"""A small, format-neutral document model and its renderers (Markdown, plain text, JSON).

Both readers (.hwp and .hwpx) produce this model, so everything downstream - what Claude reads,
the tests, conversion to a new file - sees the two formats the same way.
"""

import html
import json
import re


class Paragraph:
    __slots__ = ("text", "runs", "align", "heading", "page_break", "indent")

    def __init__(self, text="", runs=None, align=None, heading=0, page_break=False, indent=0):
        self.text = text
        self.indent = indent  # left-margin level for list items and quotes (writers only)
        # runs: list of (text, style) where style is a dict such as {"bold": True}. Readers fill
        # only ``text``; the Markdown parser and the writers use ``runs``.
        self.runs = runs
        self.align = align
        self.heading = heading
        self.page_break = page_break

    def to_json(self):
        d = {"type": "paragraph", "text": self.text}
        if self.align:
            d["align"] = self.align
        if self.heading:
            d["heading"] = self.heading
        return d


class Cell:
    __slots__ = ("row", "col", "rowspan", "colspan", "blocks", "header")

    def __init__(self, row, col, rowspan=1, colspan=1, blocks=None, header=False):
        self.row, self.col = row, col
        self.rowspan, self.colspan = max(1, rowspan), max(1, colspan)
        self.blocks = blocks or []
        self.header = header

    def to_json(self):
        d = {"row": self.row, "col": self.col, "blocks": [b.to_json() for b in self.blocks]}
        if self.rowspan > 1:
            d["rowspan"] = self.rowspan
        if self.colspan > 1:
            d["colspan"] = self.colspan
        return d


class Table:
    __slots__ = ("cells", "rows", "cols", "col_widths")

    def __init__(self, cells, rows=None, cols=None, col_widths=None):
        self.cells = cells
        self.rows = rows if rows is not None else max([c.row + c.rowspan for c in cells] or [0])
        self.cols = cols if cols is not None else max([c.col + c.colspan for c in cells] or [0])
        self.col_widths = col_widths

    def has_merges(self):
        return any(c.rowspan > 1 or c.colspan > 1 for c in self.cells)

    def to_json(self):
        return {"type": "table", "rows": self.rows, "cols": self.cols,
                "cells": [c.to_json() for c in self.cells]}


class Note:
    """Header, footer, footnote, endnote or a text box: blocks that live outside the main flow."""
    __slots__ = ("kind", "blocks", "number")

    def __init__(self, kind, blocks, number=None):
        self.kind = kind
        self.blocks = blocks
        self.number = number

    def to_json(self):
        d = {"type": self.kind, "blocks": [b.to_json() for b in self.blocks]}
        if self.number is not None:
            d["number"] = self.number
        return d


class Document:
    def __init__(self, blocks=None, meta=None, notes=None):
        self.blocks = blocks or []
        self.meta = meta or {}
        self.notes = notes or []          # footnotes / endnotes, referenced as [^n] in the text
        self.headers = []                 # header / footer Note objects
        self.warnings = []

    # ---- renderers ----------------------------------------------------------------------

    def to_json(self):
        return json.dumps({
            "meta": self.meta,
            "warnings": self.warnings,
            "headers_footers": [n.to_json() for n in self.headers],
            "blocks": [b.to_json() for b in self.blocks],
            "notes": [n.to_json() for n in self.notes],
        }, ensure_ascii=False, indent=1)

    def to_text(self):
        lines = []
        for b in self.blocks:
            _text_block(b, lines)
        for n in self.notes:
            lines.append("[%s %s] %s" % (_NOTE_LABEL.get(n.kind, n.kind), n.number,
                                         " ".join(_flat(n.blocks))))
        # Runs of empty paragraphs (forms are full of them) collapse to one blank line.
        text = "\n".join(lines)
        text = re.sub(r"\n{3,}", "\n\n", text)
        return text.strip("\n") + "\n"

    def to_markdown(self):
        out = []
        for h in self.headers:
            body = " / ".join(_flat(h.blocks)).strip()
            if body:
                out.append("> **%s**: %s" % (_NOTE_LABEL.get(h.kind, h.kind), _md_escape(body)))
        if out:
            out.append("")
        for b in self.blocks:
            _md_block(b, out)
        if self.notes:
            out.append("")
            for n in self.notes:
                prefix = "^" if n.kind == "footnote" else "^e"
                out.append("[%s%s]: %s" % (prefix, n.number, _md_escape(" ".join(_flat(n.blocks)))))
        text = "\n".join(out)
        while "\n\n\n" in text:
            text = text.replace("\n\n\n", "\n\n")
        return text.strip() + "\n"


_NOTE_LABEL = {"header": "머리말", "footer": "꼬리말", "footnote": "각주", "endnote": "미주",
               "textbox": "글상자"}


def _flat(blocks):
    for b in blocks:
        if isinstance(b, Paragraph):
            if b.text.strip():
                yield b.text
        elif isinstance(b, Table):
            for c in b.cells:
                yield from _flat(c.blocks)
        elif isinstance(b, Note):
            yield from _flat(b.blocks)


def _text_block(b, lines):
    if isinstance(b, Paragraph):
        lines.append(b.text)
    elif isinstance(b, Table):
        grid = _grid(b)
        for row in grid:
            lines.append("\t".join(" ".join(_flat(c.blocks)).replace("\n", " ") if c else "" for c in row))
    elif isinstance(b, Note):
        for x in b.blocks:
            _text_block(x, lines)


def _md_escape(s):
    # Only what would otherwise change meaning at the start of a line or inside a table.
    return s.replace("|", "\\|")


def _md_line(text):
    if not text:
        return ""
    t = text
    if t[:1] in "#>" or t.startswith(("- ", "* ", "+ ")):
        t = "\\" + t
    return t.replace("\n", "  \n")


def _md_block(b, out):
    if isinstance(b, Paragraph):
        if b.page_break and out and out[-1] != "":
            out.append("")
        if b.heading:
            out.append("#" * min(6, b.heading) + " " + b.text.replace("\n", " "))
            out.append("")
            return
        line = _md_line(b.text)
        if b.align in ("center", "right") and line:
            line = "<p align=\"%s\">%s</p>" % (b.align, html.escape(b.text).replace("\n", "<br>"))
        out.append(line)
        out.append("")
    elif isinstance(b, Table):
        out.extend(_md_table(b))
        out.append("")
    elif isinstance(b, Note):
        label = _NOTE_LABEL.get(b.kind, b.kind)
        inner = []
        for x in b.blocks:
            _md_block(x, inner)
        body = [l for l in inner if l.strip()]
        if body:
            out.append("> **[%s]**" % label)
            for l in body:
                out.append("> " + l)
            out.append("")


def _grid(t):
    """Expands merged cells into a rows x cols grid of Cell (or None) references."""
    rows = max(t.rows, max([c.row + c.rowspan for c in t.cells] or [0]))
    cols = max(t.cols, max([c.col + c.colspan for c in t.cells] or [0]))
    grid = [[None] * cols for _ in range(rows)]
    for c in t.cells:
        if 0 <= c.row < rows and 0 <= c.col < cols:
            grid[c.row][c.col] = c
    return grid


def _trimmed(blocks):
    """Cell blocks without the empty paragraphs Hangul leaves at the top and bottom of cells."""
    blocks = list(blocks)
    while blocks and isinstance(blocks[0], Paragraph) and not blocks[0].text.strip():
        blocks.pop(0)
    while blocks and isinstance(blocks[-1], Paragraph) and not blocks[-1].text.strip():
        blocks.pop()
    return blocks


def _cell_md(c):
    parts = []
    for b in _trimmed(c.blocks):
        if isinstance(b, Paragraph):
            parts.append(_md_escape(b.text).replace("\n", "<br>"))
        elif isinstance(b, Table):
            parts.append(_html_table(b).replace("\n", ""))
        elif isinstance(b, Note):
            parts.extend(_md_escape(x) for x in _flat(b.blocks))
    return "<br>".join(p for p in parts if p is not None)


def _md_table(t):
    if not t.cells:
        return []
    nested = any(isinstance(b, Table) for c in t.cells for b in c.blocks)
    if t.has_merges() or nested:
        # Markdown cannot express merged cells; an HTML table can, and Markdown allows it inline.
        return [_html_table(t)]
    grid = _grid(t)
    lines = []
    for i, row in enumerate(grid):
        lines.append("| " + " | ".join(_cell_md(c) if c else "" for c in row) + " |")
        if i == 0:
            lines.append("|" + "|".join(["---"] * len(row)) + "|")
    return lines


def _html_table(t):
    by_row = {}
    for c in sorted(t.cells, key=lambda c: (c.row, c.col)):
        by_row.setdefault(c.row, []).append(c)
    out = ["<table>"]
    for r in sorted(by_row):
        tds = []
        for c in by_row[r]:
            attrs = ""
            if c.rowspan > 1:
                attrs += ' rowspan="%d"' % c.rowspan
            if c.colspan > 1:
                attrs += ' colspan="%d"' % c.colspan
            inner = []
            for b in _trimmed(c.blocks):
                if isinstance(b, Paragraph):
                    inner.append(html.escape(b.text).replace("\n", "<br>"))
                elif isinstance(b, Table):
                    inner.append(_html_table(b))
                elif isinstance(b, Note):
                    inner.extend(html.escape(x) for x in _flat(b.blocks))
            tds.append("<td%s>%s</td>" % (attrs, "<br>".join(inner)))
        out.append("<tr>" + "".join(tds) + "</tr>")
    out.append("</table>")
    return "\n".join(out)
