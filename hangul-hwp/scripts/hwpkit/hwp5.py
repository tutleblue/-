"""HWP 5.0 binary (.hwp): reading text and tables, and editing text in place.

Layout of a .hwp, as far as this module needs it::

    FileHeader          256 bytes, never compressed: signature, version, flags
    DocInfo             records: fonts, character/paragraph shapes, styles ...
    BodyText/SectionN   records: the paragraphs of section N
    PrvText             UTF-16LE preview text Hangul shows in file dialogs

Every record is ``[tag:10 bits][level:10 bits][size:12 bits]`` (+ a UINT32 size when the 12 bits
read 0xFFF) followed by the payload. Records form a tree by level, but one detail matters a lot:
the paragraphs of a list (a table cell, a text box, a footnote...) are *siblings* of the
LIST_HEADER that introduces them, not its children.

Paragraph text (PARA_TEXT) is UTF-16LE in which control characters occupy 1 or 8 code units.
Formatting (PARA_CHAR_SHAPE) refers to positions in that array, so an edit that changes the text
length must shift every later position, or all formatting after the edit slides sideways.
"""

import re
import struct
import zlib

from . import cfb
from .model import Cell, Document, Note, Paragraph, Table
from .textops import find_matches

# ---- record tags -------------------------------------------------------------------------
PARA_HEADER = 0x42
PARA_TEXT = 0x43
PARA_CHAR_SHAPE = 0x44
PARA_LINE_SEG = 0x45
PARA_RANGE_TAG = 0x46
CTRL_HEADER = 0x47
LIST_HEADER = 0x48
TABLE = 0x4D
SHAPE_COMPONENT = 0x4C
PARA_SHAPE = 0x19  # DocInfo
EQEDIT = 0x58

# ---- control characters ------------------------------------------------------------------
# Characters 0-31 are controls. "char" controls take one code unit; "inline" and "extended"
# controls take eight: [code][6 info units][code]. An extended control's first two info units
# are its control id; its data lives in the next CTRL_HEADER child of the paragraph, in order.
CHAR_CONTROLS = {0, 10, 13, 24, 25, 26, 27, 28, 29, 30, 31}
INLINE_CONTROLS = {4, 5, 6, 7, 8, 9, 19, 20}
EXTENDED_CONTROLS = {1, 2, 3, 11, 12, 14, 15, 16, 17, 18, 21, 22, 23}

FLAG_COMPRESSED = 1 << 0
FLAG_PASSWORD = 1 << 1
FLAG_DISTRIBUTION = 1 << 2
FLAG_DRM = 1 << 4

MAX_DECOMPRESSED = 256 * 1024 * 1024


class HwpError(Exception):
    pass


# ---- records -----------------------------------------------------------------------------

class Record:
    __slots__ = ("tag", "level", "payload", "children", "ext")

    def __init__(self, tag, level, payload, ext=False):
        self.tag, self.level, self.payload = tag, level, payload
        self.children = []
        self.ext = ext  # the size was written in the 4-byte form although it fit in 12 bits


def parse_records(data):
    roots, stack, i, n = [], [], 0, len(data)
    while i + 4 <= n:
        h = struct.unpack_from("<I", data, i)[0]
        i += 4
        tag, level, size = h & 0x3FF, (h >> 10) & 0x3FF, h >> 20
        ext = False
        if size == 0xFFF:
            if i + 4 > n:
                break
            size = struct.unpack_from("<I", data, i)[0]
            i += 4
            ext = size < 0xFFF
        if i + size > n:
            raise HwpError("레코드가 스트림 끝을 넘어갑니다(tag=0x%X). 손상된 문서입니다." % tag)
        rec = Record(tag, level, data[i:i + size], ext)
        i += size
        while len(stack) > level:
            stack.pop()
        if level == 0 or not stack:
            roots.append(rec)
        else:
            stack[-1].children.append(rec)
        stack.append(rec)
    return roots


def serialize_records(roots):
    out = bytearray()

    def emit(r):
        size = len(r.payload)
        if size >= 0xFFF or r.ext:
            out.extend(struct.pack("<II", r.tag | (r.level << 10) | (0xFFF << 20), size))
        else:
            out.extend(struct.pack("<I", r.tag | (r.level << 10) | (size << 20)))
        out.extend(r.payload)
        for c in r.children:
            emit(c)

    for r in roots:
        emit(r)
    return bytes(out)


def walk(records):
    for r in records:
        yield r
        yield from walk(r.children)


def inflate(data):
    d = zlib.decompressobj(-15)
    out = d.decompress(data, MAX_DECOMPRESSED)
    if d.unconsumed_tail:
        raise HwpError("압축을 푼 크기가 상한(256MB)을 넘습니다. 비정상 문서로 보고 중단합니다.")
    return out


def deflate(data):
    c = zlib.compressobj(zlib.Z_DEFAULT_COMPRESSION, zlib.DEFLATED, -15)
    return c.compress(data) + c.flush()


def ctrl_id_of(payload):
    if len(payload) < 4:
        return ""
    v = struct.unpack_from("<I", payload, 0)[0]
    return "".join(chr((v >> s) & 0xFF) for s in (24, 16, 8, 0))


# ---- paragraph text ----------------------------------------------------------------------

def split_units(units):
    """Splits a paragraph's code units into ("text", str) and ("ctrl", code, raw) pieces.

    Text pieces hold one Python character per UTF-16 code unit (a character outside the BMP
    appears as its two surrogates), so string indices stay equal to code-unit positions.
    Use ``units_to_str`` to turn such a piece into ordinary text.
    """
    pieces, i, n, buf = [], 0, len(units), []
    while i < n:
        u = units[i]
        if u >= 32:
            buf.append(chr(u))
            i += 1
            continue
        if buf:
            pieces.append(("text", "".join(buf)))
            buf = []
        if u in CHAR_CONTROLS:
            pieces.append(("ctrl", u, units[i:i + 1]))
            i += 1
        else:
            pieces.append(("ctrl", u, units[i:i + 8]))
            i += 8
    if buf:
        pieces.append(("text", "".join(buf)))
    return pieces


def units_to_str(s):
    return s.encode("utf-16-le", "surrogatepass").decode("utf-16-le", "replace")


def str_to_units(s):
    """Ordinary text -> one character per UTF-16 code unit (see ``split_units``)."""
    raw = s.encode("utf-16-le")
    return "".join(chr(u) for u in struct.unpack("<%dH" % (len(raw) // 2), raw))


def clean_replacement(s):
    """Text that can go straight into PARA_TEXT.

    A line break (code 10) is a one-unit control and is kept. A tab is an eight-unit inline
    control whose info units this tool does not model, so it becomes a space rather than a
    malformed control. Every other control character is dropped.
    """
    s = s.replace("\r\n", "\n").replace("\r", "\n").replace("\t", " ")
    return "".join(ch for ch in s if ch == "\n" or ord(ch) >= 32)


def text_units(payload):
    return list(struct.unpack("<%dH" % (len(payload) // 2), payload[: len(payload) // 2 * 2]))


# ---- the file ----------------------------------------------------------------------------

class HwpFile:
    def __init__(self, data):
        if data[:17] == b"HWP Document File" and not cfb.is_cfb(data):
            raise HwpError("한글 97 이전 형식(HWP 3.x)입니다. 한글에서 열어 .hwp(5.0) 또는 .hwpx로 "
                           "다시 저장한 뒤 사용해 주세요.")
        if not cfb.is_cfb(data):
            raise HwpError("한글 .hwp 파일이 아닙니다(복합 문서 서명 없음).")
        try:
            self.cf = cfb.CompoundFile(data)
        except cfb.CfbError as e:
            raise HwpError(str(e))
        header = self.cf.streams.get("FileHeader")
        if not header or not header.startswith(b"HWP Document File"):
            raise HwpError("한글 문서가 아닌 복합 문서입니다(FileHeader 없음).")
        self.version, self.flags = struct.unpack_from("<II", header, 32)
        self.compressed = bool(self.flags & FLAG_COMPRESSED)
        self.section_names = sorted(
            (p for p in self.cf.streams if re.fullmatch(r"BodyText/Section\d+", p)),
            key=lambda p: int(p[len("BodyText/Section"):]))
        self._sections = None
        self._aligns = None

    @property
    def version_text(self):
        v = self.version
        return "%d.%d.%d.%d" % ((v >> 24) & 0xFF, (v >> 16) & 0xFF, (v >> 8) & 0xFF, v & 0xFF)

    def protection(self):
        if self.flags & (FLAG_PASSWORD | FLAG_DRM):
            return "password"
        if self.flags & FLAG_DISTRIBUTION or not self.section_names:
            return "distribution"
        return None

    def preview_text(self):
        raw = self.cf.streams.get("PrvText", b"")
        return raw.decode("utf-16-le", "replace").rstrip("\x00")

    @property
    def sections(self):
        if self._sections is None:
            if self.protection() == "password":
                raise HwpError("암호가 걸린 문서입니다. 한글에서 암호를 해제한 뒤 다시 시도해 주세요.")
            self._sections = []
            for name in self.section_names:
                raw = self.cf.streams[name]
                data = inflate(raw) if self.compressed else raw
                self._sections.append(parse_records(data))
        return self._sections

    # ---- reading ------------------------------------------------------------------------

    def para_aligns(self):
        """Horizontal alignment of every paragraph shape in DocInfo, by id."""
        if self._aligns is None:
            self._aligns = []
            raw = self.cf.streams.get("DocInfo", b"")
            try:
                recs = parse_records(inflate(raw) if self.compressed else raw)
            except (HwpError, zlib.error):
                recs = []
            names = ["justify", "left", "right", "center", "distribute", "divide"]
            for r in walk(recs):
                if r.tag == PARA_SHAPE and len(r.payload) >= 4:
                    a = (struct.unpack_from("<I", r.payload, 0)[0] >> 2) & 0x7
                    self._aligns.append(names[a] if a < len(names) else None)
        return self._aligns

    def to_document(self):
        doc = Document(meta={"format": "hwp", "version": self.version_text})
        prot = self.protection()
        if prot == "password":
            raise HwpError("암호가 걸린 문서입니다. 한글에서 암호를 해제한 뒤 다시 시도해 주세요.")
        if prot == "distribution":
            # A distribution document's body is encrypted by design. Hangul still writes the
            # preview text in the clear, so that much can be shown honestly as a preview.
            doc.warnings.append("배포용 문서라 본문이 암호화되어 있습니다. 한글이 저장해 둔 미리보기 "
                                "텍스트(문서 앞부분 일부)만 읽었습니다.")
            for line in self.preview_text().split("\r\n"):
                doc.blocks.append(Paragraph(line))
            return doc
        ctx = _ReadContext(doc, self.para_aligns())
        for sec in self.sections:
            doc.blocks.extend(ctx.read_list(sec, 0, len(sec)))
        doc.meta["sections"] = len(self.sections)
        return doc

    # ---- editing ------------------------------------------------------------------------

    def replace_text(self, pairs, max_count=None):
        """Replaces text in every paragraph (body, tables, text boxes, headers, notes).

        ``pairs`` is a list of (old, new). Matches never cross a control character such as a
        table anchor or a tab, which keeps every control and its data exactly where it was.
        Returns {old: count}.
        """
        unit_pairs = [(str_to_units(old), str_to_units(clean_replacement(new))) for old, new in pairs if old]
        unit_counts = {old: 0 for old, _ in unit_pairs}
        for sec in self.sections:
            for rec in walk(sec):
                if rec.tag == PARA_HEADER:
                    _replace_in_paragraph(rec, unit_pairs, unit_counts)
        return {units_to_str(k): v for k, v in unit_counts.items()}

    # ---- tables -------------------------------------------------------------------------

    def tables(self):
        """Every table control in document order (nested tables included, outer first)."""
        return [r for sec in self.sections for r in walk(sec)
                if r.tag == CTRL_HEADER and ctrl_id_of(r.payload) == "tbl "]

    @staticmethod
    def table_cells(ctrl):
        """[(row, col, rowspan, colspan, [PARA_HEADER records])] for one table control."""
        kids = ctrl.children
        table_at = next((i for i, r in enumerate(kids) if r.tag == TABLE), -1)
        cells, i = [], table_at + 1
        while 0 < i < len(kids):
            r = kids[i]
            if r.tag == LIST_HEADER:
                count = struct.unpack_from("<H", r.payload, 0)[0] if len(r.payload) >= 2 else 0
                paras, j = [], i + 1
                while j < len(kids) and kids[j].tag == PARA_HEADER and len(paras) < max(count, 1):
                    paras.append(kids[j])
                    j += 1
                if len(r.payload) >= 16:
                    col, row, cspan, rspan = struct.unpack_from("<HHHH", r.payload, 8)
                else:
                    col = row = 0
                    cspan = rspan = 1
                cells.append((row, col, rspan, cspan, paras))
                i = j
            else:
                i += 1
        return cells

    @staticmethod
    def para_text(rec):
        text_rec = next((c for c in rec.children if c.tag == PARA_TEXT), None)
        if text_rec is None:
            return ""
        out = []
        for piece in split_units(text_units(text_rec.payload)):
            if piece[0] == "text":
                out.append(units_to_str(piece[1]))
            elif piece[1] == 10:
                out.append("\n")
        return "".join(out)

    def list_cells(self):
        out = []
        for ti, ctrl in enumerate(self.tables()):
            for row, col, rs, cs, paras in self.table_cells(ctrl):
                out.append({"table": ti, "row": row, "col": col, "rowspan": rs, "colspan": cs,
                            "text": "\n".join(self.para_text(p) for p in paras)})
        return out

    def set_cell(self, table, row, col, value):
        tabs = self.tables()
        if not 0 <= table < len(tabs):
            raise HwpError("표 %d가 없습니다(표는 0번부터 %d개)." % (table, len(tabs)))
        for r, c, rs, cs, paras in self.table_cells(tabs[table]):
            if r == row and c == col:
                if not paras:
                    raise HwpError("셀(%d,%d)에 문단이 없습니다." % (row, col))
                lines = clean_replacement(value).split("\n")
                for i, p in enumerate(paras):
                    if i < len(lines):
                        text = lines[i] if i < len(paras) - 1 else "\n".join(lines[i:])
                    else:
                        text = ""
                    _set_paragraph_text(p, text)
                return
        raise HwpError("표 %d에 (행 %d, 열 %d) 셀이 없습니다. `cells` 명령으로 주소를 확인하세요."
                       % (table, row, col))

    def to_bytes(self):
        if self._sections is not None:
            for name, sec in zip(self.section_names, self._sections):
                data = serialize_records(sec)
                self.cf.streams[name] = deflate(data) if self.compressed else data
            self._update_preview()
        return self.cf.to_bytes()

    def _update_preview(self):
        if "PrvText" not in self.cf.streams:
            return
        lines = []
        for b in self.to_document().blocks:
            if isinstance(b, Paragraph):
                lines.append(b.text.replace("\n", " "))
            elif isinstance(b, Table):
                lines.append("".join("<%s>" % " ".join(
                    x.text for x in c.blocks if isinstance(x, Paragraph)) for c in b.cells))
        text = "\r\n".join(lines)[:1024]
        self.cf.streams["PrvText"] = text.encode("utf-16-le") + b"\x00\x00"


# ---- reading helpers ---------------------------------------------------------------------

class _ReadContext:
    def __init__(self, doc, aligns):
        self.doc = doc
        self.aligns = aligns
        self.footnote_no = 0
        self.endnote_no = 0

    def read_list(self, siblings, start, end):
        """Paragraphs among ``siblings[start:end]``, as blocks."""
        blocks = []
        for rec in siblings[start:end]:
            if rec.tag == PARA_HEADER:
                blocks.extend(self.read_paragraph(rec))
        return blocks

    def lists_in(self, records):
        """Every LIST_HEADER under ``records`` with the paragraphs that follow it."""
        found = []
        i = 0
        while i < len(records):
            r = records[i]
            if r.tag == LIST_HEADER:
                count = struct.unpack_from("<H", r.payload, 0)[0] if len(r.payload) >= 2 else 0
                j = i + 1
                while j < len(records) and records[j].tag == PARA_HEADER and (j - i - 1) < max(count, 1):
                    j += 1
                found.append((r, self.read_list(records, i + 1, j)))
                i = j
                continue
            if r.tag not in (PARA_HEADER,):
                found.extend(self.lists_in(r.children))
            i += 1
        return found

    def read_paragraph(self, rec):
        text_rec = next((c for c in rec.children if c.tag == PARA_TEXT), None)
        ctrls = [c for c in rec.children if c.tag == CTRL_HEADER]
        blocks, buf, ci = [], [], 0
        page_break = bool(len(rec.payload) > 11 and rec.payload[11] & 0x04)
        shape = struct.unpack_from("<H", rec.payload, 8)[0] if len(rec.payload) >= 10 else 0
        align = self.aligns[shape] if shape < len(self.aligns) else None

        def flush():
            if buf:
                blocks.append(Paragraph("".join(buf), align=align))
                buf.clear()

        units = text_units(text_rec.payload) if text_rec else []
        for piece in split_units(units):
            if piece[0] == "text":
                buf.append(units_to_str(piece[1]))
                continue
            code = piece[1]
            if code == 9:
                buf.append("\t")
            elif code == 10:
                buf.append("\n")
            elif code in (24,):
                buf.append("-")
            elif code in (30, 31):
                buf.append(" ")
            elif code in EXTENDED_CONTROLS:
                ctrl = ctrls[ci] if ci < len(ctrls) else None
                ci += 1
                if ctrl is None:
                    continue
                cid = ctrl_id_of(ctrl.payload)
                if cid == "tbl ":
                    flush()
                    blocks.extend(self.read_table(ctrl))
                elif cid in ("head", "foot"):
                    body = [b for _, bs in self.lists_in(ctrl.children) for b in bs]
                    kind = "header" if cid == "head" else "footer"
                    text = "\n".join(p.text for p in body if isinstance(p, Paragraph))
                    if text.strip() and not any(
                            h.kind == kind and "\n".join(x.text for x in h.blocks if isinstance(x, Paragraph)) == text
                            for h in self.doc.headers):
                        self.doc.headers.append(Note(kind, body))
                elif cid in ("fn  ", "en  "):
                    body = [b for _, bs in self.lists_in(ctrl.children) for b in bs]
                    if cid == "fn  ":
                        self.footnote_no += 1
                        no, kind, mark = self.footnote_no, "footnote", "[^%d]"
                    else:
                        self.endnote_no += 1
                        no, kind, mark = self.endnote_no, "endnote", "[^e%d]"
                    buf.append(mark % no)
                    self.doc.notes.append(Note(kind, body, no))
                elif cid == "gso ":
                    lists = self.lists_in(ctrl.children)
                    inner = [b for _, bs in lists for b in bs if not _is_empty(b)]
                    if inner:
                        flush()
                        blocks.append(Note("textbox", inner))
                    elif _has_picture(ctrl):
                        buf.append("[그림]")
                elif cid == "eqed":
                    script = _equation_script(ctrl)
                    if script:
                        buf.append("[수식: %s]" % script)
                # Section/column definitions, auto numbers, bookmarks, fields...: no text of their own.
        flush()
        if not blocks:
            blocks.append(Paragraph("", align=align))
        if page_break:
            # A table that starts the page carries the break on an empty paragraph in front.
            if not isinstance(blocks[0], Paragraph):
                blocks.insert(0, Paragraph(""))
            blocks[0].page_break = True
        return blocks

    def read_table(self, ctrl):
        table_rec = next((c for c in ctrl.children if c.tag == TABLE), None)
        rows = cols = 0
        if table_rec and len(table_rec.payload) >= 8:
            rows, cols = struct.unpack_from("<HH", table_rec.payload, 4)
        cells, caption = [], []
        seen_table = False
        for lh, blocks in self.lists_in(ctrl.children):
            idx = ctrl.children.index(lh) if lh in ctrl.children else -1
            if table_rec is not None and idx >= 0 and idx < ctrl.children.index(table_rec):
                caption.extend(blocks)
                continue
            seen_table = True
            p = lh.payload
            if len(p) >= 16:
                col, row, cspan, rspan = struct.unpack_from("<HHHH", p, 8)
            else:
                col = row = 0
                cspan = rspan = 1
            cells.append(Cell(row, col, rspan, cspan, blocks))
        out = []
        if cells or seen_table:
            out.append(Table(cells, rows or None, cols or None))
        # A caption that is only Hangul's automatic label ("표 1", whose number is generated at
        # layout time and not stored) says nothing; a caption with real words is kept.
        cap = [b for b in caption if isinstance(b, Paragraph)
               and b.text.strip() not in ("", "표", "그림", "수식", "Table", "Figure")]
        out.extend(cap)
        return out


def _is_empty(b):
    return isinstance(b, Paragraph) and not b.text.strip()


def _has_picture(ctrl):
    return any(r.tag == 0x55 for r in walk(ctrl.children))


def _equation_script(ctrl):
    for r in walk(ctrl.children):
        if r.tag == EQEDIT and len(r.payload) >= 6:
            n = struct.unpack_from("<H", r.payload, 4)[0]
            return r.payload[6:6 + n * 2].decode("utf-16-le", "replace").strip()
    return ""


# ---- editing helpers ---------------------------------------------------------------------

def _set_paragraph_text(rec, value):
    """Replaces all of a paragraph's text with ``value``, keeping its controls (and their data)
    in their original order at the start of the paragraph."""
    text_rec = next((c for c in rec.children if c.tag == PARA_TEXT), None)
    units = text_units(text_rec.payload) if text_rec else []
    if text_rec is not None:
        old = "".join(p[1] for p in split_units(units) if p[0] == "text")
        if old:
            # Same path as a replacement, so formatting runs are shifted the same careful way.
            _replace_in_paragraph(rec, [(old, str_to_units(value))], {old: 0}, whole=True)
            return
    if not value:
        return
    keep = [u for p in split_units(units) if p[0] == "ctrl" and p[1] != 13 for u in p[2]]
    new_units = keep + [ord(ch) for ch in str_to_units(value)] + [13]
    payload = struct.pack("<%dH" % len(new_units), *new_units)
    if text_rec is None:
        text_rec = Record(PARA_TEXT, rec.level + 1, payload)
        rec.children.insert(0, text_rec)
    else:
        text_rec.payload = payload
    hdr = bytearray(rec.payload)
    count = struct.unpack_from("<I", hdr, 0)[0]
    struct.pack_into("<I", hdr, 0, (count & 0x80000000) | len(new_units))
    if "\n" in value:
        struct.pack_into("<I", hdr, 4, struct.unpack_from("<I", hdr, 4)[0] | (1 << 10))
    rec.payload = bytes(hdr)
    _reset_line_segs(rec)


def _replace_in_paragraph(rec, pairs, counts, whole=False):
    """``pairs`` are in code-unit form (see ``split_units``). With ``whole``, the paragraph's
    text pieces are all replaced: the first one by the new text, the others by nothing."""
    text_rec = next((c for c in rec.children if c.tag == PARA_TEXT), None)
    if text_rec is None:
        return
    units = text_units(text_rec.payload)
    pieces = split_units(units)

    # Plan every replacement against the original positions first.
    edits = []  # (start, end, new_units)
    pos = 0
    first_text = True
    for piece in pieces:
        if piece[0] == "ctrl":
            pos += len(piece[2])
            continue
        seg = piece[1]
        if whole:
            new = pairs[0][1] if first_text else ""
            edits.append((pos, pos + len(seg), [ord(ch) for ch in new]))
            first_text = False
        else:
            for start, end, new in find_matches(seg, pairs, counts):
                edits.append((pos + start, pos + end, [ord(ch) for ch in new]))  # already code units
        pos += len(seg)
    if not edits:
        return

    new_units, cursor = [], 0
    for start, end, repl in edits:
        new_units.extend(units[cursor:start])
        new_units.extend(repl)
        cursor = end
    new_units.extend(units[cursor:])

    def shift(p):
        """Maps an old position to the new array. Positions inside a replaced span collapse to
        the start of the replacement, so the replacement takes the formatting of its first
        character."""
        delta = 0
        for start, end, repl in edits:
            n = len(repl)
            if p <= start:
                break
            if p < end:
                return start + delta
            delta += n - (end - start)
        return p + delta

    text_rec.payload = struct.pack("<%dH" % len(new_units), *new_units)

    # Paragraph header: the character count (low 31 bits; bit 31 marks "last paragraph in list").
    hdr = bytearray(rec.payload)
    old_count = struct.unpack_from("<I", hdr, 0)[0]
    struct.pack_into("<I", hdr, 0, (old_count & 0x80000000) | len(new_units))
    if any(u == 10 for _, _, repl in edits for u in repl):
        struct.pack_into("<I", hdr, 4, struct.unpack_from("<I", hdr, 4)[0] | (1 << 10))

    def run_position(p):
        """Where a formatting run starts after the edit, or None when it started strictly inside
        a replaced span (the replacement keeps the formatting that applied at the span's start)."""
        delta = 0
        for start, end, repl in edits:
            if p <= start:
                break
            if p < end:
                return None
            delta += len(repl) - (end - start)
        return p + delta

    # Character shape runs: [UINT32 position, UINT32 shape id] pairs.
    for c in rec.children:
        if c.tag == PARA_CHAR_SHAPE:
            runs = [struct.unpack_from("<II", c.payload, k) for k in range(0, len(c.payload) // 8 * 8, 8)]
            moved = []
            for p, sid in runs:
                np_ = 0 if p == 0 else run_position(p)
                if np_ is None:
                    continue
                if moved and np_ == moved[-1][0]:
                    # Only an emptied span can make two runs meet; the earlier one then covers no
                    # text and the later one is what applies from here on.
                    moved[-1] = (np_, sid)
                    continue
                moved.append((np_, sid))
            c.payload = b"".join(struct.pack("<II", p, s) for p, s in moved)
            if len(hdr) >= 14:
                struct.pack_into("<H", hdr, 12, len(moved))
        elif c.tag == PARA_RANGE_TAG:
            out = bytearray()
            for k in range(0, len(c.payload) // 12 * 12, 12):
                s, e, tag = struct.unpack_from("<III", c.payload, k)
                out += struct.pack("<III", shift(s), max(shift(s), shift(e)), tag)
            c.payload = bytes(out)
    rec.payload = bytes(hdr)
    _reset_line_segs(rec)


def _reset_line_segs(rec):
    """Line segments are a layout cache. After a text change the cached line breaks are wrong,
    and Hangul draws the new text into the old line slots (overlapping glyphs). Keep a single
    segment starting at 0: its vertical metrics stay valid and Hangul re-breaks the lines."""
    for c in rec.children:
        if c.tag == PARA_LINE_SEG and len(c.payload) >= 36:
            seg = bytearray(c.payload[:36])
            struct.pack_into("<I", seg, 0, 0)
            c.payload = bytes(seg)
            hdr = bytearray(rec.payload)
            if len(hdr) >= 18:
                struct.pack_into("<H", hdr, 16, 1)
            rec.payload = bytes(hdr)
