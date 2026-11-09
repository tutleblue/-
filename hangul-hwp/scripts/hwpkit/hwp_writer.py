"""Builds a new .hwp (HWP 5.0 binary) from the document model.

Every record layout here is copied from a file Hangul itself saved, not reconstructed from the
specification alone:

  - DocInfo (fonts, shapes, styles) and the section-definition paragraph come from
    ``assets/hwp-template.bin``: the DocInfo and BodyText records of a blank Hangul 2014
    document (5.0.3.4), with nothing personal in them.
  - Tables use the exact CTRL_HEADER / TABLE / LIST_HEADER layouts of a table Hangul inserted
    with its default settings, with sizes and cell addresses filled in.

New paragraphs get no line-segment record: that record is a layout cache, and Hangul builds it
when it opens the document.
"""

import os
import random
import struct

from . import cfb
from .hwp5 import (CTRL_HEADER, LIST_HEADER, PARA_CHAR_SHAPE, PARA_HEADER, PARA_TEXT, TABLE,
                   Record, deflate, parse_records, serialize_records, str_to_units, walk)
from .model import Note, Paragraph, Table

ASSET = os.path.join(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))),
                     "assets", "hwp-template.bin")

ID_MAPPINGS = 0x11
FACE_NAME = 0x13
BORDER_FILL = 0x14
CHAR_SHAPE = 0x15
PARA_SHAPE = 0x19
PAGE_DEF = 0x49

VERSION = 0x05000304
BASE_PARA = 3          # 바탕글: justified, 160%
BASE_CHAR = 0          # 10pt 함초롬바탕
HEADING_SIZE = {1: 16, 2: 14, 3: 12, 4: 11, 5: 10, 6: 10}
INDENT_STEP = 2000     # HWPUNIT per list/quote level: 20pt, about 7mm
ALIGN_BITS = {"justify": 0, "left": 1, "right": 2, "center": 3, "distribute": 4, "divide": 5}
HWPUNIT_PER_MM = 7200 / 25.4

# ID_MAPPINGS slots (INT32 each)
IDM_FONTS = range(1, 8)   # hangul, latin, hanja, japanese, other, symbol, user
IDM_BORDER_FILL = 8
IDM_CHAR_SHAPE = 9
IDM_PARA_SHAPE = 13

# A solid 0.12 mm border on all four sides, byte for byte as Hangul wrote it for a default table:
# attributes, then (line type, thickness, COLORREF) for left/right/top/bottom and the diagonal,
# then the fill (type 0: none).
SOLID_BORDER = bytes.fromhex("00000101000000000101000000000101000000000101000000000100000000000000000000000000")

# The common object part of a default table control (from a table inserted by Hangul).
TBL_CTRL = bytes.fromhex("206c627410222a080000000000000000e2a3000002050000010000001b011b011b011b01"
                         "e240b85b000000000000")
# A default cell's list header; fields at fixed offsets are filled in per cell.
CELL_HEADER = bytes.fromhex("01000000200000000000000001000100f15100001a010000fe01fe018d008d000300"
                            "f1510000000000000000000000")


class HwpWriterError(Exception):
    pass


def _colorref(hexcolor):
    h = (hexcolor or "#000000").lstrip("#")
    if len(h) != 6:
        return 0
    r, g, b = int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16)
    return r | (g << 8) | (b << 16)


def _ext_ctrl(code, ctrl_id):
    v = struct.unpack(">I", ctrl_id.encode("latin-1"))[0]
    return [code, v & 0xFFFF, (v >> 16) & 0xFFFF, 0, 0, 0, 0, code]


class _DocInfo:
    """DocInfo records. Fonts, border fills, character and paragraph shapes are children of
    ID_MAPPINGS (level 1), in that order, each kind's records stored one after another; the
    counts in ID_MAPPINGS must match what is there."""

    def __init__(self, records):
        self.records = records
        self.idm = next(r for r in records if r.tag == ID_MAPPINGS)
        self.items = self.idm.children
        self.counts = list(struct.unpack("<%di" % (len(self.idm.payload) // 4), self.idm.payload))
        self.chars = [r for r in self.items if r.tag == CHAR_SHAPE]
        self.paras = [r for r in self.items if r.tag == PARA_SHAPE]
        self.fills = [r for r in self.items if r.tag == BORDER_FILL]
        if not (len(self.chars) == self.counts[IDM_CHAR_SHAPE] and len(self.paras) == self.counts[IDM_PARA_SHAPE]
                and len(self.fills) == self.counts[IDM_BORDER_FILL]):
            raise HwpWriterError("템플릿 DocInfo의 개수 정보가 실제 레코드와 다릅니다.")
        self.char_ids, self.para_ids = {}, {}
        self.font_ids = {}
        faces = self._faces()
        for i, r in enumerate(faces[0]):
            n = struct.unpack_from("<H", r.payload, 1)[0]
            self.font_ids[r.payload[3:3 + 2 * n].decode("utf-16-le")] = [i] * 7
        self.solid_id = None
        self.shade_id = None

    def _faces(self):
        """FACE_NAME records split into the seven language lists."""
        all_faces = [r for r in self.items if r.tag == FACE_NAME]
        groups, k = [], 0
        for slot in IDM_FONTS:
            groups.append(all_faces[k:k + self.counts[slot]])
            k += self.counts[slot]
        return groups

    def _insert_after(self, last, rec):
        self.items.insert(self.items.index(last) + 1, rec)

    def font(self, face):
        """Font ids (one per language list) for ``face``, adding it to every list if needed."""
        if face in self.font_ids:
            return self.font_ids[face]
        groups = self._faces()
        proto = groups[0][-1]
        n = struct.unpack_from("<H", proto.payload, 1)[0]
        # props(1) + name length(2) + name, then whatever the prototype carries after its name.
        payload = proto.payload[:1] + struct.pack("<H", len(face)) + face.encode("utf-16-le") + proto.payload[3 + 2 * n:]
        ids = []
        for slot, group in zip(IDM_FONTS, groups):
            ids.append(self.counts[slot])
            self._insert_after(group[-1], Record(FACE_NAME, group[-1].level, payload))
            self.counts[slot] += 1
        self.font_ids[face] = ids
        return ids

    def char(self, style, default_font=None, default_size=None):
        style = style or {}
        size = float(style.get("size") or default_size or 10)
        font = style.get("font") or default_font
        key = (bool(style.get("bold")), bool(style.get("italic")), bool(style.get("underline")),
               bool(style.get("strike")), size, (style.get("color") or "#000000").upper(), font,
               bool(style.get("superscript")), bool(style.get("subscript")))
        if key == (False, False, False, False, 10.0, "#000000", None, False, False):
            return BASE_CHAR
        if key in self.char_ids:
            return self.char_ids[key]
        bold, italic, underline, strike, size, color, font, sup, sub = key
        p = bytearray(self.chars[BASE_CHAR].payload)
        if font:
            struct.pack_into("<7H", p, 0, *self.font(font))
        struct.pack_into("<i", p, 42, int(round(size * 100)))
        props = struct.unpack_from("<I", p, 46)[0]
        props &= ~(0x3 | (0x3 << 2) | (0xF << 4) | (1 << 15) | (1 << 16) | (1 << 18) | (0xF << 26))
        if italic:
            props |= 1
        if bold:
            props |= 2
        if underline:
            props |= 1 << 2                      # below the text, solid
        if strike:
            # Hangul records a strikeout both in its own bits and as the old centre underline.
            props |= 1 << 18
            if not underline:
                props |= 2 << 2
        if sup:
            props |= 1 << 15
        if sub:
            props |= 1 << 16
        struct.pack_into("<I", p, 46, props)
        struct.pack_into("<I", p, 52, _colorref(color))
        rec = Record(CHAR_SHAPE, self.chars[-1].level, bytes(p))
        self._insert_after(self.chars[-1], rec)
        self.chars.append(rec)
        cid = self.counts[IDM_CHAR_SHAPE]
        self.counts[IDM_CHAR_SHAPE] += 1
        self.char_ids[key] = cid
        return cid

    def para(self, align=None, indent=0, space_before=0, space_after=0, keep_next=False):
        key = (align, indent, space_before, space_after, keep_next)
        if key == (None, 0, 0, 0, False):
            return BASE_PARA
        if key in self.para_ids:
            return self.para_ids[key]
        p = bytearray(self.paras[BASE_PARA].payload)
        props = struct.unpack_from("<I", p, 0)[0]
        if align:
            props = (props & ~(0x7 << 2)) | (ALIGN_BITS.get(align, 0) << 2)
        if keep_next:
            props |= 1 << 17
        struct.pack_into("<I", p, 0, props)
        # Paragraph lengths are stored doubled, bit 0 being the unit flag (0 = HWPUNIT), so
        # 2000 here is 1000 HWPUNIT (10pt). Same lengths as the .hwpx writer, in this encoding.
        struct.pack_into("<i", p, 4, 2 * indent * INDENT_STEP)
        struct.pack_into("<i", p, 16, 2 * space_before)
        struct.pack_into("<i", p, 20, 2 * space_after)
        rec = Record(PARA_SHAPE, self.paras[-1].level, bytes(p))
        self._insert_after(self.paras[-1], rec)
        self.paras.append(rec)
        pid = self.counts[IDM_PARA_SHAPE]
        self.counts[IDM_PARA_SHAPE] += 1
        self.para_ids[key] = pid
        return pid

    def _fill(self, payload):
        rec = Record(BORDER_FILL, self.fills[-1].level, payload)
        self._insert_after(self.fills[-1], rec)
        self.fills.append(rec)
        self.counts[IDM_BORDER_FILL] += 1
        return self.counts[IDM_BORDER_FILL]       # border fill ids start at 1

    def solid(self):
        if self.solid_id is None:
            self.solid_id = self._fill(SOLID_BORDER)
        return self.solid_id

    def shaded(self):
        if self.shade_id is None:
            # Colour fill laid out as in Hangul's own: type 1, background, pattern colour,
            # pattern type -1 (none), no extra fill data, one trailing byte.
            fill = (struct.pack("<I", 1) + struct.pack("<I", _colorref("#E5E5E5"))
                    + struct.pack("<I", 0xFF000000) + struct.pack("<i", -1) + struct.pack("<I", 0) + b"\x00")
            self.shade_id = self._fill(SOLID_BORDER[:32] + fill)
        return self.shade_id

    def finish(self):
        self.idm.payload = struct.pack("<%di" % len(self.counts), *self.counts)
        return self.records


class HwpWriter:
    def __init__(self, doc):
        self.doc = doc
        with open(ASSET, "rb") as f:
            blob = f.read()
        di_len = struct.unpack_from("<I", blob, 0)[0]
        self.docinfo = _DocInfo(parse_records(blob[4:4 + di_len]))
        self.section = parse_records(blob[4 + di_len:])
        meta = doc.meta or {}
        self.font = meta.get("font")
        self.size = meta.get("size")
        self.page = meta.get("page") or {}
        self.rng = random.Random(0x5EED)

    # ---- paragraphs --------------------------------------------------------------------

    def _runs(self, para):
        runs = para.runs if para.runs is not None else [(para.text, {})]
        base = {"bold": True, "size": HEADING_SIZE.get(para.heading, 10)} if para.heading else {}
        out = []
        for text, style in runs:
            text = _clean(text)
            if text:
                out.append((text, self.docinfo.char(dict(base, **(style or {})), self.font, self.size)))
        return out

    def _para_shape(self, para):
        if para.heading:
            return self.docinfo.para(para.align, para.indent, 600 if para.heading <= 2 else 300, 300, True)
        return self.docinfo.para(para.align, para.indent)

    def _paragraph(self, level, para, last, prefix_units=(), prefix_controls=(), break_bits=0, mask=0):
        units = list(prefix_units)
        shapes = []
        for text, cid in self._runs(para):
            pos = len(units)
            if not shapes or shapes[-1][1] != cid:
                shapes.append((pos, cid))
            units.extend(ord(c) for c in str_to_units(text))
        if any(u == 10 for u in units):
            mask |= 1 << 10
        if not shapes:
            shapes.append((0, self.docinfo.char({}, self.font, self.size)))
        if shapes[0][0] != 0:
            shapes.insert(0, (0, shapes[0][1]))
        units.append(13)
        if para.page_break:
            break_bits |= 0x04
        header = struct.pack("<IIHBBHHHIH", len(units) | (0x80000000 if last else 0), mask,
                             self._para_shape(para), 0, break_bits, len(shapes), 0, 0, 0, 0)
        rec = Record(PARA_HEADER, level, header)
        if len(units) > 1:
            rec.children.append(Record(PARA_TEXT, level + 1, struct.pack("<%dH" % len(units), *units)))
        rec.children.append(Record(PARA_CHAR_SHAPE, level + 1,
                                   b"".join(struct.pack("<II", p, s) for p, s in shapes)))
        rec.children.extend(prefix_controls)
        return rec

    # ---- tables ------------------------------------------------------------------------

    def _table_paragraph(self, level, table, width, last):
        ctrl = self._table(level + 1, table, width)
        units = _ext_ctrl(11, "tbl ") + [13]
        header = struct.pack("<IIHBBHHHIH", len(units) | (0x80000000 if last else 0), 1 << 11,
                             BASE_PARA, 0, 0, 1, 0, 0, 0, 0)
        rec = Record(PARA_HEADER, level, header)
        rec.children.append(Record(PARA_TEXT, level + 1, struct.pack("<%dH" % len(units), *units)))
        rec.children.append(Record(PARA_CHAR_SHAPE, level + 1, struct.pack("<II", 0, BASE_CHAR)))
        rec.children.append(ctrl)
        return rec

    def _table(self, level, table, width):
        rows, cols = table.rows, table.cols
        weights = table.col_widths or _auto_weights(table)
        weights = [float(w) for w in (list(weights) + [1] * cols)[:cols]]
        total = sum(weights) or cols
        col_w = [max(1500, int(width * w / total)) for w in weights]
        col_w[-1] += width - sum(col_w)
        row_h = 1282

        p = bytearray(TBL_CTRL)
        struct.pack_into("<I", p, 16, width)
        struct.pack_into("<I", p, 20, row_h * rows)
        struct.pack_into("<I", p, 36, self.rng.randint(10 ** 9, 2 * 10 ** 9))
        ctrl = Record(CTRL_HEADER, level, bytes(p))

        by_row = {}
        for c in table.cells:
            by_row.setdefault(c.row, []).append(c)
        solid = self.docinfo.solid()
        tbl = struct.pack("<IHHH4H", 6, rows, cols, 0, 510, 510, 141, 141)
        tbl += b"".join(struct.pack("<H", len(by_row.get(r, []))) for r in range(rows))
        tbl += struct.pack("<HH", solid, 0)
        ctrl.children.append(Record(TABLE, level + 1, tbl))

        for r in range(rows):
            for c in sorted(by_row.get(r, []), key=lambda c: c.col):
                cw = sum(col_w[c.col:c.col + c.colspan])
                blocks = [b for b in (c.blocks or [Paragraph("")])]
                paras = []
                for b in blocks:
                    if isinstance(b, Paragraph):
                        paras.append(("p", b))
                    elif isinstance(b, Table):
                        paras.append(("t", b))
                if not paras:
                    paras = [("p", Paragraph(""))]
                h = bytearray(CELL_HEADER)
                struct.pack_into("<H", h, 0, len(paras))
                struct.pack_into("<HHHH", h, 8, c.col, c.row, c.colspan, c.rowspan)
                struct.pack_into("<I", h, 16, cw)
                struct.pack_into("<H", h, 32, self.docinfo.shaded() if c.header else solid)
                struct.pack_into("<I", h, 34, max(0, cw - 1020))
                ctrl.children.append(Record(LIST_HEADER, level + 1, bytes(h)))
                for i, (kind, b) in enumerate(paras):
                    last = i == len(paras) - 1
                    if kind == "p":
                        ctrl.children.append(self._paragraph(level + 1, b, last))
                    else:
                        ctrl.children.append(self._table_paragraph(level + 1, b, max(3000, cw - 1020 - 566), last))
        return ctrl

    # ---- document ----------------------------------------------------------------------

    def _apply_page(self):
        for r in walk(self.section):
            if r.tag != PAGE_DEF:
                continue
            p = bytearray(r.payload)
            margins = self.page.get("margins_mm") or {}
            for i, key in ((2, "left"), (3, "right"), (4, "top"), (5, "bottom"), (6, "header"), (7, "footer")):
                if key in margins:
                    struct.pack_into("<i", p, i * 4, int(round(float(margins[key]) * HWPUNIT_PER_MM)))
            if str(self.page.get("orientation", "")).lower() == "landscape":
                struct.pack_into("<I", p, 36, struct.unpack_from("<I", p, 36)[0] | 1)
            r.payload = bytes(p)
            vals = struct.unpack_from("<10i", r.payload)
            width = vals[1] if vals[9] & 1 else vals[0]
            return width - vals[2] - vals[3]
        return 42520

    def build(self):
        text_width = self._apply_page() - 566
        first_rec = self.section[0]
        first_text = next(c for c in first_rec.children if c.tag == PARA_TEXT)
        units = list(struct.unpack("<%dH" % (len(first_text.payload) // 2), first_text.payload))
        prefix = units[:-1]                       # the section/column definitions, without the end
        prefix_ctrls = [c for c in first_rec.children if c.tag == CTRL_HEADER]
        old_hdr = first_rec.payload
        mask = struct.unpack_from("<I", old_hdr, 4)[0]
        break_bits = old_hdr[11]

        blocks = [b for b in self.doc.blocks if isinstance(b, (Paragraph, Table, Note))]
        flat = []
        for b in blocks:
            if isinstance(b, Note):
                flat.extend(x for x in b.blocks if isinstance(x, Paragraph))
            else:
                flat.append(b)
        if not flat or not isinstance(flat[0], Paragraph):
            flat.insert(0, Paragraph(""))

        out = []
        for i, b in enumerate(flat):
            last = i == len(flat) - 1
            if i == 0:
                out.append(self._paragraph(0, b, last, prefix, prefix_ctrls, break_bits, mask))
            elif isinstance(b, Paragraph):
                out.append(self._paragraph(0, b, last))
            else:
                out.append(self._table_paragraph(0, b, text_width, last))

        docinfo = serialize_records(self.docinfo.finish())
        body = serialize_records(out)
        header = bytearray(256)
        header[:17] = b"HWP Document File"
        struct.pack_into("<II", header, 32, VERSION, 1)       # compressed
        from .hwpx import preview_text
        prv = preview_text(self.doc).encode("utf-16-le") + b"\x00\x00"
        streams = [
            ("FileHeader", bytes(header)),
            ("DocInfo", deflate(docinfo)),
            ("BodyText/Section0", deflate(body)),
            ("PrvText", prv),
            ("DocOptions/_LinkDoc", bytes(524)),
            ("\x05HwpSummaryInformation", _summary()),
        ]
        return cfb.build(streams)


def _auto_weights(table):
    longest = [1.0] * table.cols
    for c in table.cells:
        if c.colspan == 1 and c.col < table.cols:
            n = max([len(b.text) for b in c.blocks if isinstance(b, Paragraph)] or [0])
            longest[c.col] = max(longest[c.col], min(float(n), 40.0))
    return [max(w, 4.0) for w in longest]


def _clean(s):
    s = s.replace("\r\n", "\n").replace("\r", "\n").replace("\t", "    ")
    return "".join(ch for ch in s if ch == "\n" or ord(ch) >= 32)


HWP_SUMMARY_FMTID = bytes.fromhex("60b6a29f6110d411b4c6006097c09d8c")


def _summary():
    """A property set with only the application name: no author, no times.

    Hangul's own summary carries the author's name and the save time; a document made here has
    neither to record.
    """
    def lpwstr(s):
        data = (s + "\x00").encode("utf-16-le")
        blob = struct.pack("<HHI", 0x1F, 0, len(s) + 1) + data
        return blob + b"\x00" * ((-len(blob)) % 4)

    props = [(9, lpwstr("hangul-hwp"))]
    offset = 8 + 8 * len(props)
    table, values = b"", b""
    for pid, blob in props:
        table += struct.pack("<II", pid, offset + len(values))
        values += blob
    section = struct.pack("<II", 8 + len(table) + len(values), len(props)) + table + values
    header = struct.pack("<HHI", 0xFFFE, 0, 0x00020006) + bytes(16) + struct.pack("<I", 1)
    header += HWP_SUMMARY_FMTID + struct.pack("<I", 48)
    return header + section
