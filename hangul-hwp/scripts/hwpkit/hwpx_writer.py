"""Builds a new .hwpx from the document model.

Everything structural comes from ``assets/blank.hwpx``: a document saved by Hangul itself, cut
down to its header (fonts, styles, shapes) and the section-definition paragraph. This writer only
appends what the new content needs - character shapes, paragraph shapes, one shaded border fill,
fonts - and writes the paragraphs and tables, copying Hangul's own markup for each.

Line-layout caches (hp:linesegarray) are deliberately not written: they are Hangul's record of
where it broke lines last time, and Hangul computes them when it opens the file.
"""

import io
import os
import random
import re
import zipfile

from . import xmlsplice as xs
from .model import Note, Paragraph, Table

ASSET = os.path.join(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))),
                     "assets", "blank.hwpx")

HWPUNIT_PER_MM = 7200 / 25.4
PAGE_W, PAGE_H = 59528, 84188          # A4 portrait, as in the template
BASE_PARA = "3"                        # 바탕글: justified, 160% line spacing, keep words together
BASE_CHAR = "0"                        # 10pt 함초롬바탕
SOLID_BORDER = "3"                     # 0.12mm solid lines on all four sides
HEADING_SIZE = {1: 16, 2: 14, 3: 12, 4: 11, 5: 10, 6: 10}
INDENT_STEP = 2000                     # HWPUNIT per list/quote level: 20pt, about 7mm
ALIGN = {"left": "LEFT", "right": "RIGHT", "center": "CENTER", "justify": "JUSTIFY",
         "distribute": "DISTRIBUTE", "divide": "DISTRIBUTE_SPACE"}


class WriterError(Exception):
    pass


def _set_attr(tag_text, name, value):
    pat = re.compile(r'(\s%s=")[^"]*(")' % re.escape(name))
    if pat.search(tag_text):
        return pat.sub(lambda m: m.group(1) + value + m.group(2), tag_text, count=1)
    return tag_text.replace(">", ' %s="%s">' % (name, value), 1) if not tag_text.endswith("/>") \
        else tag_text[:-2] + ' %s="%s"/>' % (name, value)


def _mm(v):
    return int(round(float(v) * HWPUNIT_PER_MM))


class _Header:
    """header.xml plus the shapes this document adds to it."""

    def __init__(self, xml):
        self.xml = xml
        root = xs.parse(xml)
        self.lists = {n.local: n for n in root.iter() if n.local in (
            "fontfaces", "charProperties", "paraProperties", "borderFills")}
        cp = self.lists["charProperties"]
        self.char_base = next(xml[c.start:c.end] for c in cp.elements("charPr") if c.attrs.get("id") == BASE_CHAR)
        pp = self.lists["paraProperties"]
        self.para_base = next(xml[c.start:c.end] for c in pp.elements("paraPr") if c.attrs.get("id") == BASE_PARA)
        bf = self.lists["borderFills"]
        self.border_base = next(xml[c.start:c.end] for c in bf.elements("borderFill") if c.attrs.get("id") == SOLID_BORDER)
        self.n_char = len(cp.elements("charPr"))
        self.n_para = len(pp.elements("paraPr"))
        self.n_border = len(bf.elements("borderFill"))
        # Fonts per language list, in order; the same face gets the same id in every list.
        self.fontfaces = []
        self.fonts = {}
        for ff in self.lists["fontfaces"].elements("fontface"):
            faces = [f.attrs.get("face") for f in ff.elements("font")]
            self.fontfaces.append((ff, faces))
        first = self.fontfaces[0][1]
        for i, face in enumerate(first):
            self.fonts[face] = i
        self.font_proto = next(xml[f.start:f.end] for f in self.fontfaces[0][0].elements("font"))
        self.new_fonts, self.new_chars, self.new_paras, self.new_borders = [], [], [], []
        self.char_ids, self.para_ids = {}, {}
        self.shade_id = None

    def font_id(self, face):
        if face in self.fonts:
            return self.fonts[face]
        fid = len(self.fonts)
        self.fonts[face] = fid
        self.new_fonts.append(face)
        return fid

    def char(self, style, default_font=None, default_size=None):
        style = style or {}
        size = float(style.get("size") or default_size or 10)
        key = (bool(style.get("bold")), bool(style.get("italic")), bool(style.get("underline")),
               bool(style.get("strike")), size, (style.get("color") or "#000000").upper(),
               style.get("font") or default_font, bool(style.get("superscript")), bool(style.get("subscript")))
        if key == (False, False, False, False, 10.0, "#000000", None, False, False):
            return BASE_CHAR
        if key in self.char_ids:
            return self.char_ids[key]
        bold, italic, underline, strike, size, color, font, sup, sub = key
        cid = str(self.n_char + len(self.new_chars))
        x = self.char_base
        x = re.sub(r'<hh:charPr\b[^>]*>', lambda m: _set_attr(_set_attr(_set_attr(
            m.group(0), "id", cid), "height", str(int(round(size * 100)))), "textColor", color), x, count=1)
        if font:
            fid = str(self.font_id(font))
            x = re.sub(r'<hh:fontRef\b[^>]*/>', '<hh:fontRef hangul="{0}" latin="{0}" hanja="{0}" japanese="{0}" '
                       'other="{0}" symbol="{0}" user="{0}"/>'.format(fid), x, count=1)
        extra = ("<hh:italic/>" if italic else "") + ("<hh:bold/>" if bold else "")
        x = x.replace("</hh:offset>", "</hh:offset>" + extra, 1) if "</hh:offset>" in x else \
            re.sub(r'(<hh:offset\b[^>]*/>)', lambda m: m.group(1) + extra, x, count=1)
        if underline:
            x = re.sub(r'<hh:underline\b[^>]*/>', '<hh:underline type="BOTTOM" shape="SOLID" color="#000000"/>', x, count=1)
        if strike:
            x = re.sub(r'<hh:strikeout\b[^>]*/>', '<hh:strikeout shape="SOLID" color="#000000"/>', x, count=1)
        if sup or sub:
            x = x.replace("</hh:charPr>", ("<hh:supscript/>" if sup else "<hh:subscript/>") + "</hh:charPr>", 1)
        self.new_chars.append(x)
        self.char_ids[key] = cid
        return cid

    def para(self, align=None, indent=0, space_before=0, space_after=0, keep_next=False):
        key = (align, indent, space_before, space_after, keep_next)
        if key == (None, 0, 0, 0, False):
            return BASE_PARA
        if key in self.para_ids:
            return self.para_ids[key]
        pid = str(self.n_para + len(self.new_paras))
        x = self.para_base
        x = re.sub(r'<hh:paraPr\b[^>]*>', lambda m: _set_attr(m.group(0), "id", pid), x, count=1)
        if align:
            x = re.sub(r'(<hh:align\b[^>]*\shorizontal=")[^"]*', lambda m: m.group(1) + ALIGN.get(align, "JUSTIFY"), x, count=1)
        if keep_next:
            x = re.sub(r'(keepWithNext=")\d', r'\g<1>1', x, count=1)
        # Hangul writes each length twice: in HWPUNIT inside <hp:case> (read by every Hangul
        # since 2016) and doubled inside <hp:default> (the older encoding). Both must agree.
        values = {"left": indent * INDENT_STEP, "prev": space_before, "next": space_after}
        cut = x.find("<hp:default>")
        case, default = (x[:cut], x[cut:]) if cut >= 0 else (x, "")
        for name, v in values.items():
            case = re.sub(r'(<hc:%s value=")[^"]*' % name, lambda m, v=v: m.group(1) + str(v), case)
            default = re.sub(r'(<hc:%s value=")[^"]*' % name, lambda m, v=v: m.group(1) + str(2 * v), default)
        x = case + default
        self.new_paras.append(x)
        self.para_ids[key] = pid
        return pid

    def shaded_border(self):
        if self.shade_id is None:
            bid = str(self.n_border + 1 + len(self.new_borders))  # borderFill ids start at 1
            x = re.sub(r'<hh:borderFill\b[^>]*>', lambda m: _set_attr(m.group(0), "id", bid), self.border_base, count=1)
            fill = '<hc:fillBrush><hc:winBrush faceColor="#E5E5E5" hatchColor="#999999" alpha="0"/></hc:fillBrush>'
            x = x.replace("</hh:borderFill>", fill + "</hh:borderFill>", 1)
            self.new_borders.append(x)
            self.shade_id = bid
        return self.shade_id

    def render(self):
        xml = self.xml
        splices = []

        def grow(node, child_local, new_items, count_attr="itemCnt"):
            if not new_items:
                return
            open_tag = xml[node.start:node.open_end]
            total = len(node.elements(child_local)) + len(new_items)
            splices.append((node.start, node.open_end, _set_attr(open_tag, count_attr, str(total))))
            splices.append((node.close_start, node.close_start, "".join(new_items)))

        if self.new_fonts:
            for ff, faces in self.fontfaces:
                items = []
                for k, face in enumerate(self.new_fonts):
                    fid = len(faces) + k
                    f = re.sub(r'\sid="\d+"', ' id="%d"' % fid, self.font_proto, count=1)
                    f = re.sub(r'\sface="[^"]*"', ' face="%s"' % xs.escape(face).replace('"', "&quot;"), f, count=1)
                    items.append(f)
                grow(ff, "font", items, "fontCnt")
        grow(self.lists["borderFills"], "borderFill", self.new_borders)
        grow(self.lists["charProperties"], "charPr", self.new_chars)
        grow(self.lists["paraProperties"], "paraPr", self.new_paras)
        return xs.apply(xml, splices)


class HwpxWriter:
    def __init__(self, doc):
        self.doc = doc
        with open(ASSET, "rb") as f:
            self.template = f.read()
        zf = zipfile.ZipFile(io.BytesIO(self.template))
        self.infos = zf.infolist()
        self.parts = {i.filename: zf.read(i.filename) for i in self.infos}
        self.header = _Header(self.parts["Contents/header.xml"].decode("utf-8"))
        meta = doc.meta or {}
        self.font = meta.get("font")
        self.size = meta.get("size")
        self.page = meta.get("page") or {}
        self.rng = random.Random(0x5EED)
        self.warnings = []

    # ---- page --------------------------------------------------------------------------

    def _text_width(self):
        landscape = str(self.page.get("orientation", "")).lower() == "landscape"
        width = PAGE_H if landscape else PAGE_W
        m = self.page.get("margins_mm") or {}
        left = _mm(m["left"]) if "left" in m else 8504
        right = _mm(m["right"]) if "right" in m else 8504
        return width - left - right

    def _section_prefix(self, sec):
        """The template's section-definition run, with page settings applied."""
        m = re.search(r'(<hp:p [^>]*>)(<hp:run [^>]*><hp:secPr .*?</hp:ctrl>)', sec, re.S)
        if not m:
            raise WriterError("템플릿에서 구역 정의를 찾지 못했습니다.")
        p_open, run = m.group(1), m.group(2)
        if str(self.page.get("orientation", "")).lower() == "landscape":
            # Hangul keeps width/height as the portrait sheet and flags the orientation.
            run = re.sub(r'(<hp:pagePr\b[^>]*\slandscape=")[^"]*', r'\1NARROWLY', run, count=1)
        margins = self.page.get("margins_mm") or {}
        for key in ("left", "right", "top", "bottom", "header", "footer"):
            if key in margins:
                run = re.sub(r'(<hp:margin\b[^>]*\s%s=")[^"]*' % key,
                             lambda mm: mm.group(1) + str(_mm(margins[key])), run, count=1)
        return sec[:m.start()], p_open, run

    # ---- content -----------------------------------------------------------------------

    def _runs_xml(self, para):
        runs = para.runs if para.runs is not None else [(para.text, {})]
        base = {}
        if para.heading:
            base = {"bold": True, "size": HEADING_SIZE.get(para.heading, 10)}
        out = []
        for text, style in runs:
            if not text:
                continue
            st = dict(base, **(style or {}))
            cid = self.header.char(st, self.font, self.size)
            body = xs.escape(_clean(text)).replace("\n", "<hp:lineBreak/>")
            out.append('<hp:run charPrIDRef="%s"><hp:t>%s</hp:t></hp:run>' % (cid, body))
        if not out:
            out.append('<hp:run charPrIDRef="%s"/>' % self.header.char({}, self.font, self.size))
        return "".join(out)

    def _para_attrs(self, para):
        if para.heading:
            pid = self.header.para(para.align, para.indent, space_before=600 if para.heading <= 2 else 300,
                                   space_after=300, keep_next=True)
        else:
            pid = self.header.para(para.align, para.indent)
        return pid

    def _paragraph(self, para, pid_override=None, first=False):
        pid = pid_override or self._para_attrs(para)
        pb = "1" if para.page_break and not first else "0"
        return '<hp:p id="0" paraPrIDRef="%s" styleIDRef="0" pageBreak="%s" columnBreak="0" merged="0">%s</hp:p>' % (
            pid, pb, self._runs_xml(para))

    def _table(self, table, width):
        rows, cols = table.rows, table.cols
        if not rows or not cols:
            return ""
        weights = table.col_widths or self._auto_weights(table)
        weights = [float(w) for w in (list(weights) + [1] * cols)[:cols]]
        total_w = sum(weights) or cols
        col_w = [max(1500, int(width * w / total_w)) for w in weights]
        col_w[-1] += width - sum(col_w)
        row_h = 1282
        tid = str(self.rng.randint(10 ** 9, 2 * 10 ** 9))
        out = ['<hp:tbl id="%s" zOrder="0" numberingType="TABLE" textWrap="TOP_AND_BOTTOM" textFlow="BOTH_SIDES" '
               'lock="0" dropcapstyle="None" pageBreak="CELL" repeatHeader="1" rowCnt="%d" colCnt="%d" cellSpacing="0" '
               'borderFillIDRef="%s" noAdjust="0">' % (tid, rows, cols, SOLID_BORDER),
               '<hp:sz width="%d" widthRelTo="ABSOLUTE" height="%d" heightRelTo="ABSOLUTE" protect="0"/>'
               % (width, row_h * rows),
               '<hp:pos treatAsChar="0" affectLSpacing="0" flowWithText="1" allowOverlap="0" holdAnchorAndSO="0" '
               'vertRelTo="PARA" horzRelTo="COLUMN" vertAlign="TOP" horzAlign="LEFT" vertOffset="0" horzOffset="0"/>',
               '<hp:outMargin left="283" right="283" top="283" bottom="283"/>',
               '<hp:inMargin left="510" right="510" top="141" bottom="141"/>']
        by_row = {}
        for c in table.cells:
            by_row.setdefault(c.row, []).append(c)
        shade = None
        for r in range(rows):
            out.append("<hp:tr>")
            for c in sorted(by_row.get(r, []), key=lambda c: c.col):
                cw = sum(col_w[c.col:c.col + c.colspan])
                border = SOLID_BORDER
                if c.header:
                    shade = shade or self.header.shaded_border()
                    border = shade
                paras = c.blocks or [Paragraph("")]
                inner = []
                for b in paras:
                    if isinstance(b, Paragraph):
                        inner.append(self._paragraph(b))
                    elif isinstance(b, Table):
                        inner.append('<hp:p id="0" paraPrIDRef="%s" styleIDRef="0" pageBreak="0" columnBreak="0" '
                                     'merged="0"><hp:run charPrIDRef="0">%s<hp:t/></hp:run></hp:p>'
                                     % (BASE_PARA, self._table(b, max(3000, cw - 1020 - 566))))
                out.append('<hp:tc name="" header="%d" hasMargin="0" protect="0" editable="0" dirty="0" '
                           'borderFillIDRef="%s"><hp:subList id="" textDirection="HORIZONTAL" lineWrap="BREAK" '
                           'vertAlign="CENTER" linkListIDRef="0" linkListNextIDRef="0" textWidth="0" textHeight="0" '
                           'hasTextRef="0" hasNumRef="0">%s</hp:subList><hp:cellAddr colAddr="%d" rowAddr="%d"/>'
                           '<hp:cellSpan colSpan="%d" rowSpan="%d"/><hp:cellSz width="%d" height="%d"/>'
                           '<hp:cellMargin left="510" right="510" top="141" bottom="141"/></hp:tc>'
                           % (1 if c.header else 0, border, "".join(inner), c.col, c.row, c.colspan, c.rowspan,
                              cw, row_h * c.rowspan))
            out.append("</hp:tr>")
        out.append("</hp:tbl>")
        return "".join(out)

    @staticmethod
    def _auto_weights(table):
        """Column widths from content length, so a "번호" column does not get as much room as a
        "내용" column. Clamped so no column is starved."""
        longest = [1.0] * table.cols
        for c in table.cells:
            if c.colspan == 1:
                n = max([len(b.text) for b in c.blocks if isinstance(b, Paragraph)] or [0])
                longest[c.col] = max(longest[c.col], min(float(n), 40.0))
        return [max(w, 4.0) for w in longest]

    def build(self, title=""):
        sec = self.parts["Contents/section0.xml"].decode("utf-8")
        head, p_open, sec_run = self._section_prefix(sec)
        width = self._text_width() - 566  # the table's outer margins
        blocks = [b for b in self.doc.blocks]
        body = []

        # The first paragraph carries the section definition; the first block goes in it so the
        # document does not start with an empty line.
        first = blocks[0] if blocks else Paragraph("")
        if isinstance(first, Paragraph):
            pid = self._para_attrs(first)
            p_open = _set_attr(_set_attr(p_open, "paraPrIDRef", pid), "pageBreak", "0")
            runs = self._runs_xml(first)
            body.append(p_open + sec_run + "</hp:run>" + runs + "</hp:p>")
            rest = blocks[1:]
        else:
            body.append(p_open + sec_run + "</hp:run></hp:p>")
            rest = blocks

        for b in rest:
            if isinstance(b, Paragraph):
                body.append(self._paragraph(b))
            elif isinstance(b, Table):
                body.append('<hp:p id="0" paraPrIDRef="%s" styleIDRef="0" pageBreak="0" columnBreak="0" merged="0">'
                            '<hp:run charPrIDRef="0">%s<hp:t/></hp:run></hp:p>' % (BASE_PARA, self._table(b, width)))
            elif isinstance(b, Note):
                for x in b.blocks:
                    if isinstance(x, Paragraph):
                        body.append(self._paragraph(x))
        section_xml = head + "".join(body) + "</hs:sec>"

        self.parts["Contents/section0.xml"] = section_xml.encode("utf-8")
        self.parts["Contents/header.xml"] = self.header.render().encode("utf-8")
        hpf = self.parts["Contents/content.hpf"].decode("utf-8")
        if title:
            hpf = hpf.replace("<opf:title/>", "<opf:title>%s</opf:title>" % xs.escape(title), 1)
        self.parts["Contents/content.hpf"] = hpf.encode("utf-8")
        from .hwpx import preview_text  # local import: hwpx imports nothing from here
        self.parts["Preview/PrvText.txt"] = preview_text(self.doc).encode("utf-8")

        out = io.BytesIO()
        with zipfile.ZipFile(out, "w") as zf:
            for info in self.infos:
                zi = zipfile.ZipInfo(info.filename, date_time=(1980, 1, 1, 0, 0, 0))
                zi.compress_type = info.compress_type
                zi.external_attr = info.external_attr
                zf.writestr(zi, self.parts[info.filename])
        return out.getvalue()


def _clean(s):
    s = s.replace("\r\n", "\n").replace("\r", "\n").replace("\t", "    ")
    return "".join(ch for ch in s if ch == "\n" or ord(ch) >= 32)
