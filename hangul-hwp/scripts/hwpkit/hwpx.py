"""HWPX / OWPML (.hwpx, KS X 6101): reading, text replacement, and the shared package helpers.

A .hwpx is a ZIP: ``mimetype`` (first, stored), ``Contents/header.xml`` (fonts, character and
paragraph shapes), ``Contents/section0.xml``... (the body), ``Contents/content.hpf`` (manifest
and reading order), and ``Preview/PrvText.txt``.

Reading uses ElementTree. Editing does not: it splices the original XML text, because Hangul is
known to be particular about the exact markup it gets back (namespace prefixes, declarations it
did not need) and the safest XML to hand it is its own, changed only where the text changed.
"""

import io
import re
import zipfile
import xml.etree.ElementTree as ET

from .model import Cell, Document, Note, Paragraph, Table

MAX_ENTRY = 256 * 1024 * 1024
MAX_TOTAL = 512 * 1024 * 1024

ALIGN = {"JUSTIFY": "justify", "LEFT": "left", "RIGHT": "right", "CENTER": "center",
         "DISTRIBUTE": "distribute", "DISTRIBUTE_SPACE": "divide"}


class HwpxError(Exception):
    pass


def local(tag):
    return tag.rsplit("}", 1)[-1] if isinstance(tag, str) else ""


def safe_xml(data):
    """Parses XML that came from a document, refusing DTDs and entity declarations outright.

    OWPML never uses them, and an entity declaration is the one thing that can turn a small
    file into an enormous one in memory ("billion laughs").
    """
    head = data[:4096] if isinstance(data, bytes) else data[:4096].encode("utf-8", "replace")
    if b"<!DOCTYPE" in head or b"<!ENTITY" in data:
        raise HwpxError("문서 XML에 DTD/엔티티 선언이 있습니다. 한글 문서에는 없는 구조라 열지 않습니다.")
    return ET.fromstring(data)


class Package:
    """The ZIP, read fully into memory with size limits. ``parts`` keeps the original order."""

    def __init__(self, data):
        try:
            zf = zipfile.ZipFile(io.BytesIO(data))
        except zipfile.BadZipFile:
            raise HwpxError(".hwpx(ZIP) 형식이 아닙니다.")
        self.infos = zf.infolist()
        total = 0
        self.parts = {}
        for info in self.infos:
            if info.file_size > MAX_ENTRY:
                raise HwpxError("'%s'의 압축 해제 크기가 상한(256MB)을 넘습니다." % info.filename)
            total += info.file_size
            if total > MAX_TOTAL:
                raise HwpxError("압축 해제 총량이 상한(512MB)을 넘습니다.")
            with zf.open(info) as f:
                blob = f.read(MAX_ENTRY + 1)
            if len(blob) > MAX_ENTRY:
                raise HwpxError("'%s'의 실제 크기가 선언과 다릅니다." % info.filename)
            self.parts[info.filename] = blob
        mt = self.parts.get("mimetype", b"").strip()
        if mt and mt != b"application/hwp+zip":
            raise HwpxError("mimetype이 한글 문서가 아닙니다: %r" % mt[:40])
        if not any(n.startswith("Contents/") for n in self.parts):
            raise HwpxError("Contents/ 폴더가 없습니다. 한글 .hwpx 문서가 아닙니다.")

    def section_names(self):
        hpf = self.parts.get("Contents/content.hpf")
        names = []
        if hpf:
            try:
                root = safe_xml(hpf)
                hrefs = {}
                for el in root.iter():
                    if local(el.tag) == "item" and el.get("href"):
                        hrefs[el.get("id")] = el.get("href")
                for el in root.iter():
                    if local(el.tag) == "itemref":
                        href = hrefs.get(el.get("idref"), "")
                        if re.search(r"section\d+\.xml$", href):
                            names.append(href if href in self.parts else "Contents/" + href.split("/")[-1])
            except (ET.ParseError, HwpxError):
                names = []
        if not names:
            names = sorted((n for n in self.parts if re.fullmatch(r"Contents/section\d+\.xml", n)),
                           key=lambda n: int(re.search(r"(\d+)\.xml$", n).group(1)))
        return [n for n in names if n in self.parts]

    def to_bytes(self):
        """Rewrites the ZIP: ``mimetype`` first and stored, the rest in their original order."""
        out = io.BytesIO()
        with zipfile.ZipFile(out, "w") as zf:
            order = [i.filename for i in self.infos]
            if "mimetype" in order:
                order.remove("mimetype")
                order.insert(0, "mimetype")
            for name in order:
                orig = next((i for i in self.infos if i.filename == name), None)
                zi = zipfile.ZipInfo(name, date_time=orig.date_time if orig else (1980, 1, 1, 0, 0, 0))
                # Keep each part's original compression (Hangul stores mimetype, version.xml and
                # the preview image uncompressed); mimetype must be stored whatever the source did.
                if name == "mimetype":
                    zi.compress_type = zipfile.ZIP_STORED
                else:
                    zi.compress_type = orig.compress_type if orig else zipfile.ZIP_DEFLATED
                zi.external_attr = orig.external_attr if orig else 0
                zf.writestr(zi, self.parts[name])
        return out.getvalue()


# ---- reading ------------------------------------------------------------------------------

class HwpxFile:
    def __init__(self, data):
        self.pkg = Package(data)
        self._aligns = None

    def preview_text(self):
        return self.pkg.parts.get("Preview/PrvText.txt", b"").decode("utf-8", "replace")

    def para_aligns(self):
        if self._aligns is None:
            self._aligns = {}
            hdr = self.pkg.parts.get("Contents/header.xml")
            if hdr:
                for el in safe_xml(hdr).iter():
                    if local(el.tag) == "paraPr":
                        for c in el.iter():
                            if local(c.tag) == "align":
                                self._aligns[el.get("id")] = ALIGN.get(c.get("horizontal"))
                                break
        return self._aligns

    def to_document(self):
        version = self.pkg.parts.get("version.xml", b"")
        m = re.search(rb'appVersion="([^"]*)"', version)
        doc = Document(meta={"format": "hwpx"})
        if m:
            doc.meta["app_version"] = m.group(1).decode("utf-8", "replace")
        if any(n.startswith("META-INF/") and "encrypt" in n.lower() for n in self.pkg.parts):
            raise HwpxError("암호가 걸린 문서입니다. 한글에서 암호를 해제한 뒤 다시 시도해 주세요.")
        reader = _Reader(doc, self.para_aligns())
        names = self.pkg.section_names()
        for name in names:
            root = safe_xml(self.pkg.parts[name])
            for el in root:
                if local(el.tag) == "p":
                    doc.blocks.extend(reader.paragraph(el))
        doc.meta["sections"] = len(names)
        return doc


class _Reader:
    def __init__(self, doc, aligns):
        self.doc = doc
        self.aligns = aligns
        self.footnote_no = 0
        self.endnote_no = 0

    def sublist(self, el):
        blocks = []
        for c in el:
            n = local(c.tag)
            if n == "p":
                blocks.extend(self.paragraph(c))
            elif n == "subList":
                blocks.extend(self.sublist(c))
        return blocks

    def paragraph(self, p):
        align = self.aligns.get(p.get("paraPrIDRef"))
        blocks, buf = [], []

        def flush():
            if buf:
                blocks.append(Paragraph("".join(buf), align=align))
                buf.clear()

        def inline(el):
            n = local(el.tag)
            if n == "t":
                if el.text:
                    buf.append(el.text)
                for c in el:
                    cn = local(c.tag)
                    if cn == "tab":
                        buf.append("\t")
                    elif cn == "lineBreak":
                        buf.append("\n")
                    elif cn == "hyphen":
                        buf.append("-")
                    elif cn in ("nbSpace", "fwSpace"):
                        buf.append(" ")
                    if c.tail:
                        buf.append(c.tail)
            elif n == "tbl":
                flush()
                blocks.extend(self.table(el))
            elif n == "ctrl":
                for c in el:
                    cn = local(c.tag)
                    if cn in ("header", "footer"):
                        body = self.sublist(c)
                        text = "\n".join(b.text for b in body if isinstance(b, Paragraph))
                        if text.strip() and not any(
                                h.kind == cn and "\n".join(x.text for x in h.blocks if isinstance(x, Paragraph)) == text
                                for h in self.doc.headers):
                            self.doc.headers.append(Note(cn, body))
                    elif cn in ("footNote", "endNote"):
                        body = self.sublist(c)
                        if cn == "footNote":
                            self.footnote_no += 1
                            no, kind, mark = self.footnote_no, "footnote", "[^%d]"
                        else:
                            self.endnote_no += 1
                            no, kind, mark = self.endnote_no, "endnote", "[^e%d]"
                        buf.append(mark % no)
                        self.doc.notes.append(Note(kind, body, no))
            elif n == "equation":
                script = next((c.text for c in el if local(c.tag) == "script"), "") or ""
                if script.strip():
                    buf.append("[수식: %s]" % script.strip())
            elif n == "pic":
                buf.append("[그림]")
            elif n == "switch":
                branch = next((c for c in el if local(c.tag) == "case"), None) or \
                    next((c for c in el if local(c.tag) == "default"), None)
                if branch is not None:
                    for c in branch:
                        inline(c)
            elif n in ("secPr", "linesegarray"):
                pass
            else:
                # Drawing objects (rect, ellipse, polygon, container, textart...) can carry text
                # in a drawText/subList anywhere below them.
                inner = []
                for sub in el.iter():
                    if local(sub.tag) == "subList":
                        inner.extend(self.sublist(sub))
                        break
                inner = [b for b in inner if not (isinstance(b, Paragraph) and not b.text.strip())]
                if inner:
                    flush()
                    blocks.append(Note("textbox", inner))

        for run in p:
            rn = local(run.tag)
            if rn == "run":
                for el in run:
                    inline(el)
            elif rn != "linesegarray":
                inline(run)
        flush()
        if not blocks:
            blocks.append(Paragraph("", align=align))
        if p.get("pageBreak") == "1":
            # A table that starts the page carries the break on an empty paragraph in front.
            if not isinstance(blocks[0], Paragraph):
                blocks.insert(0, Paragraph(""))
            blocks[0].page_break = True
        return blocks

    def table(self, tbl):
        cells = []
        rows = int(tbl.get("rowCnt") or 0) or None
        cols = int(tbl.get("colCnt") or 0) or None
        caption = []
        for c in tbl:
            n = local(c.tag)
            if n == "tr":
                for tc in c:
                    if local(tc.tag) != "tc":
                        continue
                    row = col = 0
                    rs = cs = 1
                    blocks = []
                    for x in tc:
                        xn = local(x.tag)
                        if xn == "subList":
                            blocks = self.sublist(x)
                        elif xn == "cellAddr":
                            col, row = int(x.get("colAddr", 0)), int(x.get("rowAddr", 0))
                        elif xn == "cellSpan":
                            cs, rs = int(x.get("colSpan", 1)), int(x.get("rowSpan", 1))
                    cells.append(Cell(row, col, rs, cs, blocks, header=tc.get("header") == "1"))
            elif n == "caption":
                caption = [b for b in self.sublist(c) if isinstance(b, Paragraph)
                           and b.text.strip() not in ("", "표", "그림", "수식", "Table", "Figure")]
        return [Table(cells, rows, cols)] + caption


# ---- editing ------------------------------------------------------------------------------

from . import xmlsplice as xs  # noqa: E402  (kept next to the code that uses it)
from .textops import find_matches  # noqa: E402


class HwpxEditor:
    """Text-level edits on a .hwpx that leave every untouched byte of the XML as it was."""

    def __init__(self, hwpx_file):
        self.file = hwpx_file
        self.pkg = hwpx_file.pkg
        self.sections = []  # [name, xml text, root Node, splices]
        for name in self.pkg.section_names():
            safe_xml(self.pkg.parts[name])  # same DTD/entity refusal as reading
            text = self.pkg.parts[name].decode("utf-8")
            self.sections.append([name, text, xs.parse(text), []])
        self.changed = False

    # -- paragraph text model --

    @staticmethod
    def _groups(p):
        """The paragraph's own text nodes (not those of tables or boxes inside it), in runs of
        text that are not interrupted by anything but run/formatting boundaries."""
        groups = [[]]
        for child in p.children:
            if not isinstance(child, xs.Node):
                continue
            if child.local == "run":
                for el in child.children:
                    if not isinstance(el, xs.Node):
                        continue
                    if el.local == "t":
                        for x in el.children:
                            if isinstance(x, xs.Text):
                                groups[-1].append(x)
                            elif groups[-1]:
                                groups.append([])
                    elif groups[-1]:
                        groups.append([])
            elif child.local != "linesegarray" and groups[-1]:
                groups.append([])
        return [g for g in groups if g]

    @staticmethod
    def _escape_with_breaks(value, t_node_name):
        prefix = t_node_name.split(":", 1)[0] + ":" if ":" in t_node_name else ""
        return xs.escape(value).replace("\n", "<%slineBreak/>" % prefix)

    def _touch(self, sec, p):
        """Drops the paragraph's line-layout cache so Hangul re-breaks the changed text."""
        for c in p.children:
            if isinstance(c, xs.Node) and c.local == "linesegarray":
                sec[3].append((c.start, c.end, ""))
        self.changed = True

    # -- operations --

    def replace_text(self, pairs):
        pairs = [(old, _clean(new)) for old, new in pairs if old]
        counts = {old: 0 for old, _ in pairs}
        for sec in self.sections:
            for p in sec[2].iter():
                if p.local != "p":
                    continue
                touched = False
                for group in self._groups(p):
                    values = [t.value for t in group]
                    joined = "".join(values)
                    matches = find_matches(joined, pairs, counts)
                    if not matches:
                        continue
                    touched = True
                    pos = 0
                    for t, val in zip(group, values):
                        ts, te = pos, pos + len(val)
                        pos = te
                        out, i = [], ts
                        for s, e, new in matches:
                            if e <= ts or s >= te:
                                continue
                            out.append(joined[i:max(s, ts)])
                            if ts <= s < te:
                                out.append(new)
                            i = min(e, te)
                        if i == ts and not out:
                            continue  # this text node does not overlap any match
                        out.append(joined[i:te])
                        sec[3].append((t.start, t.end, self._escape_with_breaks("".join(out), t.parent.name)))
                if touched:
                    self._touch(sec, p)
        return counts

    def tables(self):
        """Every table in document order (nested tables included, outer first)."""
        found = []
        for sec in self.sections:
            for tbl in sec[2].iter():
                if tbl.local == "tbl":
                    found.append((sec, tbl))
        return found

    @staticmethod
    def cell_info(tc):
        row = col = 0
        rs = cs = 1
        paras = []
        for x in tc.elements():
            if x.local == "cellAddr":
                col, row = int(x.attrs.get("colAddr", 0)), int(x.attrs.get("rowAddr", 0))
            elif x.local == "cellSpan":
                cs, rs = int(x.attrs.get("colSpan", 1)), int(x.attrs.get("rowSpan", 1))
            elif x.local == "subList":
                paras = x.elements("p")
        return row, col, rs, cs, paras

    def para_text(self, p):
        return "".join(t.value for g in self._groups(p) for t in g)

    def list_cells(self):
        out = []
        for ti, (sec, tbl) in enumerate(self.tables()):
            for tr in tbl.elements("tr"):
                for tc in tr.elements("tc"):
                    row, col, rs, cs, paras = self.cell_info(tc)
                    out.append({"table": ti, "row": row, "col": col, "rowspan": rs, "colspan": cs,
                                "text": "\n".join(self.para_text(p) for p in paras)})
        return out

    def set_cell(self, table, row, col, value):
        tabs = self.tables()
        if not 0 <= table < len(tabs):
            raise HwpxError("표 %d가 없습니다(표는 0번부터 %d개)." % (table, len(tabs)))
        sec, tbl = tabs[table]
        for tr in tbl.elements("tr"):
            for tc in tr.elements("tc"):
                r, c, rs, cs, paras = self.cell_info(tc)
                if r == row and c == col:
                    if not paras:
                        raise HwpxError("셀(%d,%d)에 문단이 없습니다." % (row, col))
                    lines = _clean(value).split("\n")
                    # One cell paragraph per line where the cell has them; the rest go as line
                    # breaks in the last paragraph. Paragraphs left over are emptied.
                    for i, p in enumerate(paras):
                        if i < len(lines):
                            text = lines[i] if i < len(paras) - 1 else "\n".join(lines[i:])
                        else:
                            text = ""
                        self._set_para_text(sec, p, text)
                    return
        raise HwpxError("표 %d에 (행 %d, 열 %d) 셀이 없습니다. `cells` 명령으로 주소를 확인하세요."
                        % (table, row, col))

    def _set_para_text(self, sec, p, value):
        groups = self._groups(p)
        texts = [t for g in groups for t in g]
        if not texts and not value:
            return
        if texts:
            first = texts[0]
            sec[3].append((first.start, first.end, self._escape_with_breaks(value, first.parent.name)))
            for t in texts[1:]:
                sec[3].append((t.start, t.end, ""))
        else:
            run = next((c for c in p.elements("run")), None)
            if run is None:
                raise HwpxError("문단에 run 요소가 없어 글자를 넣을 수 없습니다.")
            prefix = run.name.split(":", 1)[0] + ":" if ":" in run.name else ""
            t_name = prefix + "t"
            empty_t = next((el for el in run.elements("t")), None)
            body = "<%s>%s</%s>" % (t_name, self._escape_with_breaks(value, t_name), t_name)
            if empty_t is not None:
                sec[3].append((empty_t.start, empty_t.end, body))
            elif run.self_closing:
                open_tag = sec[1][run.start:run.end]
                sec[3].append((run.start, run.end, open_tag[:-2].rstrip() + ">" + body + "</%s>" % run.name))
            else:
                sec[3].append((run.open_end, run.open_end, body))
        self._touch(sec, p)

    def to_bytes(self):
        for name, text, root, splices in self.sections:
            if splices:
                self.pkg.parts[name] = xs.apply(text, splices).encode("utf-8")
        if self.changed and "Preview/PrvText.txt" in self.pkg.parts:
            self.pkg.parts["Preview/PrvText.txt"] = preview_text(self.file.to_document()).encode("utf-8")
        return self.pkg.to_bytes()


def _clean(s):
    s = s.replace("\r\n", "\n").replace("\r", "\n").replace("\t", " ")
    return "".join(ch for ch in s if ch == "\n" or ord(ch) >= 32)


def preview_text(doc, limit=1024):
    """The plain-text preview Hangul keeps in the file: paragraphs by line, cells as <..>."""
    lines = []
    for b in doc.blocks:
        if isinstance(b, Paragraph):
            lines.append(b.text.replace("\n", " "))
        elif isinstance(b, Table):
            lines.append("".join("<%s>" % " ".join(x.text for x in c.blocks if isinstance(x, Paragraph))
                                 for c in b.cells))
    return "\r\n".join(lines)[:limit]
