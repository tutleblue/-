"""Tests for the hangul-hwp tool. Standard library only:

    python3 -m unittest discover -s hangul-hwp/tests -v

Set HWP_SAMPLES to a folder of real .hwp/.hwpx files (for example clones of the Apache-2.0
neolord0/hwplib and neolord0/hwpxlib repositories) to also run the corpus checks: every file
must read, its text must agree with the preview text Hangul stored in it, and an edit must keep
every structural rule Hangul's own files obey.
"""

import io
import json
import os
import re
import struct
import subprocess
import sys
import tempfile
import unittest
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
SCRIPTS = os.path.join(os.path.dirname(HERE), "scripts")
sys.path.insert(0, SCRIPTS)

from hwpkit import cfb, hwp5, hwpx, mdparse  # noqa: E402
from hwpkit.hwp_writer import HwpWriter  # noqa: E402
from hwpkit.hwpx_writer import HwpxWriter  # noqa: E402
from hwpkit.model import Paragraph, Table  # noqa: E402

CLI = os.path.join(SCRIPTS, "hwp.py")

SAMPLE_MD = """# 보고서 제목

<right>작성일: 2026. 11. 9.</right>

이 문단에는 **굵은 글**과 *기울임*이 있습니다. 특수문자 & < > " ' 와 이모지 😀.

- 첫째 항목
  - 하위 항목
1. 번호 항목

| 구분 | 내용 | 비고 |
|---|---|---|
| {{이름}} | {{직위}} | 없음 |
| 둘째 | 셀 | 끝 |

<!-- pagebreak -->

<center>{{날짜}}에 작성</center>
"""


def paragraph_invariants(h):
    """Rules every paragraph in a Hangul-written .hwp obeys; returns the violations."""
    bad = []
    for sec in h.sections:
        for r in hwp5.walk(sec):
            if r.tag != hwp5.PARA_HEADER:
                continue
            n = struct.unpack_from("<I", r.payload, 0)[0] & 0x7FFFFFFF
            t = next((c for c in r.children if c.tag == hwp5.PARA_TEXT), None)
            units = hwp5.text_units(t.payload) if t else []
            if t and n != len(units):
                bad.append("글자 수 %d != 실제 %d" % (n, len(units)))
            if units and units[-1] != 13:
                bad.append("문단 끝 문자 없음")
            i = 0
            while i < len(units):
                u = units[i]
                if u < 32 and u not in hwp5.CHAR_CONTROLS:
                    if i + 7 >= len(units) or units[i + 7] != u:
                        bad.append("8칸 제어문자 깨짐")
                        break
                    i += 8
                else:
                    i += 1
            cs = next((c for c in r.children if c.tag == hwp5.PARA_CHAR_SHAPE), None)
            if cs:
                pos = [struct.unpack_from("<I", cs.payload, k)[0] for k in range(0, len(cs.payload), 8)]
                if struct.unpack_from("<H", r.payload, 12)[0] != len(pos):
                    bad.append("글자 모양 개수 불일치")
                if pos != sorted(set(pos)) or (pos and pos[0] != 0):
                    bad.append("글자 모양 위치 순서 %r" % pos[:5])
                if pos and units and pos[-1] >= len(units):
                    bad.append("글자 모양 위치가 글자 밖")
            segs = [c for c in r.children if c.tag == hwp5.PARA_LINE_SEG]
            want = len(segs[0].payload) // 36 if segs else 0
            if struct.unpack_from("<H", r.payload, 16)[0] != want:
                bad.append("줄 정보 개수 불일치")
    return bad


def build(fmt, text=SAMPLE_MD):
    doc = mdparse.parse_markdown(text)
    return HwpxWriter(doc).build("시험") if fmt == "hwpx" else HwpWriter(doc).build()


def open_any(data):
    return hwp5.HwpFile(data) if cfb.is_cfb(data) else hwpx.HwpxFile(data)


def editor(f):
    return f if isinstance(f, hwp5.HwpFile) else hwpx.HwpxEditor(f)


class CreateTests(unittest.TestCase):
    def check_roundtrip(self, fmt):
        data = build(fmt)
        doc = open_any(data).to_document()
        texts = [b.text for b in doc.blocks if isinstance(b, Paragraph)]
        self.assertIn("보고서 제목", texts)
        self.assertIn("이 문단에는 굵은 글과 기울임이 있습니다. 특수문자 & < > \" ' 와 이모지 😀.", texts)
        self.assertIn("• 첫째 항목", texts)
        self.assertIn("◦ 하위 항목", texts)
        tables = [b for b in doc.blocks if isinstance(b, Table)]
        self.assertEqual(len(tables), 1)
        self.assertEqual((tables[0].rows, tables[0].cols), (3, 3))
        cell_text = {(c.row, c.col): c.blocks[0].text for c in tables[0].cells}
        self.assertEqual(cell_text[(1, 0)], "{{이름}}")
        aligns = {b.text: b.align for b in doc.blocks if isinstance(b, Paragraph)}
        self.assertEqual(aligns["작성일: 2026. 11. 9."], "right")
        self.assertEqual(aligns["{{날짜}}에 작성"], "center")
        self.assertTrue(any(b.page_break for b in doc.blocks if isinstance(b, Paragraph)))
        return data

    def test_hwpx_roundtrip(self):
        data = self.check_roundtrip("hwpx")
        z = zipfile.ZipFile(io.BytesIO(data))
        first = z.infolist()[0]
        self.assertEqual((first.filename, first.compress_type), ("mimetype", zipfile.ZIP_STORED))
        self.assertEqual(z.read("mimetype"), b"application/hwp+zip")
        self.assertIsNone(z.testzip())

    def test_hwp_roundtrip(self):
        data = self.check_roundtrip("hwp")
        h = hwp5.HwpFile(data)
        self.assertEqual(paragraph_invariants(h), [])
        self.assertTrue(h.compressed)
        self.assertEqual(set(h.cf.streams), {"FileHeader", "DocInfo", "BodyText/Section0", "PrvText",
                                             "DocOptions/_LinkDoc", "\x05HwpSummaryInformation"})

    def test_docinfo_counts_match_records(self):
        doc = mdparse.parse_json(json.dumps({"font": "맑은 고딕", "blocks": [
            {"type": "paragraph", "runs": [{"text": "a", "bold": True}, {"text": "b", "color": "#FF0000"}]},
            {"type": "table", "rows": [["h"], ["v"]]}]}))
        h = hwp5.HwpFile(HwpWriter(doc).build())
        raw = h.cf.streams["DocInfo"]
        recs = hwp5.parse_records(hwp5.inflate(raw))
        idm = next(r for r in recs if r.tag == 0x11)
        counts = struct.unpack("<%di" % (len(idm.payload) // 4), idm.payload)
        kids = idm.children
        self.assertEqual(sum(1 for r in kids if r.tag == 0x13), sum(counts[1:8]))
        self.assertEqual(sum(1 for r in kids if r.tag == 0x14), counts[8])
        self.assertEqual(sum(1 for r in kids if r.tag == 0x15), counts[9])
        self.assertEqual(sum(1 for r in kids if r.tag == 0x19), counts[13])

    def test_template_has_no_personal_data(self):
        assets = os.path.join(os.path.dirname(HERE), "assets")
        for name in os.listdir(assets):
            with open(os.path.join(assets, name), "rb") as f:
                blob = f.read()
            if name.endswith(".hwpx"):
                parts = zipfile.ZipFile(io.BytesIO(blob))
                hpf = parts.read("Contents/content.hpf").decode("utf-8")
                for meta in ("creator", "lastsaveby", "CreatedDate", "ModifiedDate"):
                    m = re.search(r'name="%s" content="text"(?:/>|>([^<]*)<)' % meta, hpf)
                    self.assertFalse(m and m.group(1), meta)
                self.assertNotIn("Preview/PrvImage.png", parts.namelist())
            else:
                text = blob.decode("utf-16-le", "ignore") + blob[1:].decode("utf-16-le", "ignore")
                self.assertNotIn("Users", text)
                self.assertNotIn("박성균", text)  # the author name in the source file's summary


class EditTests(unittest.TestCase):
    def roundtrip_replace(self, fmt, pairs):
        f = open_any(build(fmt))
        ed = editor(f)
        counts = ed.replace_text(pairs)
        out = ed.to_bytes()
        return counts, open_any(out), out

    def test_replace_both_formats(self):
        for fmt in ("hwpx", "hwp"):
            with self.subTest(fmt=fmt):
                counts, g, out = self.roundtrip_replace(fmt, [("{{이름}}", "김진영"), ("{{직위}}", "팀장"),
                                                              ("{{날짜}}", "2026년 1월 5일"), ("없는말", "x")])
                self.assertEqual(counts, {"{{이름}}": 1, "{{직위}}": 1, "{{날짜}}": 1, "없는말": 0})
                text = g.to_document().to_text()
                self.assertIn("김진영\t팀장\t없음", text)
                self.assertIn("2026년 1월 5일에 작성", text)
                self.assertNotIn("{{", text)
                if fmt == "hwp":
                    self.assertEqual(paragraph_invariants(g), [])

    def test_replace_across_formatting_runs(self):
        # "굵은 글과" spans a bold run and a plain run.
        for fmt in ("hwpx", "hwp"):
            with self.subTest(fmt=fmt):
                counts, g, _ = self.roundtrip_replace(fmt, [("굵은 글과", "BOLD+plain")])
                self.assertEqual(counts["굵은 글과"], 1)
                self.assertIn("이 문단에는 BOLD+plain 기울임이", g.to_document().to_text())
                if fmt == "hwp":
                    self.assertEqual(paragraph_invariants(g), [])

    def test_replace_with_newline_emoji_and_empty(self):
        for fmt in ("hwpx", "hwp"):
            with self.subTest(fmt=fmt):
                counts, g, _ = self.roundtrip_replace(fmt, [("보고서", "첫 줄\n둘째 줄 🎉"), ("이모지 😀", "")])
                text = g.to_document().to_text()
                self.assertIn("첫 줄\n둘째 줄 🎉 제목", text)
                self.assertNotIn("😀", text)
                if fmt == "hwp":
                    self.assertEqual(paragraph_invariants(g), [])

    def test_untouched_parts_are_byte_identical(self):
        src = build("hwpx")
        ed = hwpx.HwpxEditor(hwpx.HwpxFile(src))
        ed.replace_text([("{{이름}}", "x")])
        a, b = hwpx.Package(src).parts, hwpx.Package(ed.to_bytes()).parts
        changed = {k for k in a if a[k] != b[k]}
        self.assertEqual(changed, {"Contents/section0.xml", "Preview/PrvText.txt"})

        src = build("hwp")
        h = hwp5.HwpFile(src)
        h.replace_text([("{{이름}}", "x")])
        ca, cb = cfb.CompoundFile(src), cfb.CompoundFile(h.to_bytes())
        changed = {k for k in ca.streams if ca.streams[k] != cb.streams[k]}
        self.assertEqual(changed, {"BodyText/Section0", "PrvText"})

    def test_set_cell(self):
        for fmt in ("hwpx", "hwp"):
            with self.subTest(fmt=fmt):
                ed = editor(open_any(build(fmt)))
                cells = ed.list_cells()
                self.assertEqual(len(cells), 9)
                ed.set_cell(0, 2, 2, "새 값\n두 줄")
                ed.set_cell(0, 1, 1, "")
                g = open_any(ed.to_bytes())
                t = [b for b in g.to_document().blocks if isinstance(b, Table)][0]
                got = {(c.row, c.col): "\n".join(p.text for p in c.blocks) for c in t.cells}
                self.assertEqual(got[(2, 2)], "새 값\n두 줄")
                self.assertEqual(got[(1, 1)], "")
                with self.assertRaises((hwp5.HwpError, hwpx.HwpxError)):
                    ed2 = editor(open_any(build(fmt)))
                    ed2.set_cell(0, 9, 9, "x")


class SafetyTests(unittest.TestCase):
    def test_entity_declaration_refused(self):
        buf = io.BytesIO()
        with zipfile.ZipFile(buf, "w") as z:
            z.writestr("mimetype", "application/hwp+zip")
            z.writestr("Contents/section0.xml", '<?xml version="1.0"?><!DOCTYPE x [<!ENTITY a "aaaa">]><x>&a;</x>')
        with self.assertRaises(hwpx.HwpxError):
            hwpx.HwpxFile(buf.getvalue()).to_document()

    def test_declared_size_limit(self):
        buf = io.BytesIO()
        with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as z:
            z.writestr("mimetype", "application/hwp+zip")
            z.writestr("Contents/section0.xml", b"\x00" * (300 * 1024 * 1024))
        with self.assertRaises(hwpx.HwpxError):
            hwpx.HwpxFile(buf.getvalue())

    def test_cyclic_sector_chain_is_an_error_not_a_hang(self):
        data = bytearray(build("hwp"))
        fat_sector = struct.unpack_from("<I", data, 76)[0]
        dir_start = struct.unpack_from("<I", data, 48)[0]
        off = (fat_sector + 1) * 512 + dir_start * 4
        struct.pack_into("<I", data, off, dir_start)  # the directory chain now points at itself
        with self.assertRaises(cfb.CfbError):
            cfb.CompoundFile(bytes(data))

    def test_cli_never_overwrites_source(self):
        with tempfile.TemporaryDirectory() as d:
            src = os.path.join(d, "a.hwpx")
            with open(src, "wb") as f:
                f.write(build("hwpx"))
            with open(src, "rb") as f:
                before = f.read()
            r = subprocess.run([sys.executable, CLI, "replace", src, "-o", src, "--pair", "보고서", "x"],
                               capture_output=True, text=True, encoding="utf-8")
            self.assertNotEqual(r.returncode, 0)
            self.assertIn("덮어쓰지 않습니다", r.stderr)
            with open(src, "rb") as f:
                self.assertEqual(f.read(), before)

    def test_cli_end_to_end(self):
        with tempfile.TemporaryDirectory() as d:
            md = os.path.join(d, "in.md")
            with open(md, "w", encoding="utf-8") as f:
                f.write(SAMPLE_MD)
            for ext in ("hwpx", "hwp"):
                out = os.path.join(d, "out." + ext)
                r = subprocess.run([sys.executable, CLI, "create", out, "--from", md],
                                   capture_output=True, text=True, encoding="utf-8")
                self.assertEqual(r.returncode, 0, r.stderr)
                done = os.path.join(d, "done." + ext)
                r = subprocess.run([sys.executable, CLI, "replace", out, "-o", done, "--pair", "{{이름}}", "홍길동"],
                                   capture_output=True, text=True, encoding="utf-8")
                self.assertEqual(r.returncode, 0, r.stderr)
                self.assertIn("바이트 단위로 동일", r.stdout)
                r = subprocess.run([sys.executable, CLI, "read", done], capture_output=True, text=True,
                                   encoding="utf-8")
                self.assertIn("| 홍길동 | {{직위}} | 없음 |", r.stdout)


@unittest.skipUnless(os.environ.get("HWP_SAMPLES"), "HWP_SAMPLES 폴더가 없으면 건너뜀")
class CorpusTests(unittest.TestCase):
    def files(self, ext):
        root = os.environ["HWP_SAMPLES"]
        for dirpath, _, names in os.walk(root):
            for n in names:
                if n.lower().endswith(ext):
                    yield os.path.join(dirpath, n)

    @staticmethod
    def norm(s):
        return re.sub(r"[\s<>]", "", s)

    def agreement(self, f):
        import difflib
        prv = self.norm(f.preview_text())
        if not prv:
            return 1.0
        ours = self.norm(f.to_document().to_text())[:len(prv) + 60]
        m = difflib.SequenceMatcher(None, prv, ours, autojunk=False)
        return sum(b.size for b in m.get_matching_blocks()) / len(prv)

    def test_text_agrees_with_hangul_preview(self):
        for path in list(self.files(".hwp")) + list(self.files(".hwpx")):
            with self.subTest(path=path):
                with open(path, "rb") as fp:
                    f = open_any(fp.read())
                # Pictures are shown as "[그림]", which Hangul's preview leaves out; allow for it.
                self.assertGreaterEqual(self.agreement(f), 0.94)

    def test_edits_keep_structure(self):
        for path in self.files(".hwp"):
            with self.subTest(path=path):
                with open(path, "rb") as fp:
                    h = hwp5.HwpFile(fp.read())
                if h.protection():
                    continue
                self.assertEqual(paragraph_invariants(h), [])
                words = re.findall(r"[가-힣]{2}", h.to_document().to_text())
                if not words:
                    continue
                h.replace_text([(words[0], "★")])
                self.assertEqual(paragraph_invariants(hwp5.HwpFile(h.to_bytes())), [])


if __name__ == "__main__":
    unittest.main()
