#!/usr/bin/env python3
"""한글 문서(.hwp / .hwpx) 읽기·쓰기 도구. 파이썬 표준 라이브러리만 씁니다.

    python3 hwp.py read  문서.hwp                     # 마크다운으로 읽기 (표는 표로)
    python3 hwp.py read  문서.hwpx --format text      # 순수 텍스트 / --format json
    python3 hwp.py info  문서.hwp                     # 형식·버전·보호 여부·표 개수
    python3 hwp.py cells 문서.hwp                     # 표 셀 주소와 내용 (set-cell용)
    python3 hwp.py replace 원본.hwp -o 결과.hwp --pair "{{이름}}" "홍길동" --pair "{{날짜}}" "2026. 1. 5."
    python3 hwp.py replace 원본.hwpx -o 결과.hwpx --map 값.json        # {"{{이름}}": "홍길동", ...}
    python3 hwp.py set-cell 원본.hwp -o 결과.hwp --table 0 --row 1 --col 2 --text "홍길동"   # 번호는 0부터
    python3 hwp.py set-cell 원본.hwp -o 결과.hwp --map 셀.json         # [{"table":0,"row":1,"col":2,"text":"..."}]
    python3 hwp.py create 결과.hwpx --from 내용.md [--title 제목]       # 또는 --from 내용.json
    python3 hwp.py check 결과.hwpx                    # 다시 열어 구조 점검

모든 출력은 UTF-8입니다. 원본 파일은 절대 덮어쓰지 않습니다(-o가 원본과 같으면 거부).
"""

import argparse
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from hwpkit import cfb, hwp5, hwpx  # noqa: E402
from hwpkit.model import Paragraph, Table  # noqa: E402


class ToolError(Exception):
    pass


def _utf8_stdio():
    # A Windows console defaults to cp949/cp1252; Korean text must not depend on that.
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(encoding="utf-8", errors="replace")
        except (AttributeError, ValueError):
            pass


def sniff(data):
    if cfb.is_cfb(data):
        return "hwp"
    if data[:2] == b"PK":
        return "hwpx"
    if data[:17] == b"HWP Document File":
        return "hwp3"
    head = data[:512].lstrip(b"\xef\xbb\xbf").lstrip()
    if head.startswith(b"<?xml") and b"HWPML" in data[:2048]:
        return "hml"
    return None


def open_doc(path):
    if not os.path.isfile(path):
        raise ToolError("파일이 없습니다: %s" % path)
    with open(path, "rb") as f:
        data = f.read()
    kind = sniff(data)
    if kind == "hwp":
        return kind, hwp5.HwpFile(data)
    if kind == "hwpx":
        return kind, hwpx.HwpxFile(data)
    if kind == "hwp3":
        raise ToolError("한글 97 이전(HWP 3.x) 형식입니다. 한글에서 열어 .hwpx로 다시 저장한 뒤 사용해 주세요.")
    if kind == "hml":
        raise ToolError("HWPML(.hml) 형식입니다. 한글에서 열어 .hwpx로 다시 저장한 뒤 사용해 주세요.")
    raise ToolError("한글 문서(.hwp/.hwpx)가 아닙니다: %s" % path)


def write_output(src, out, data, force=False):
    if src and os.path.abspath(src) == os.path.abspath(out):
        raise ToolError("원본을 덮어쓰지 않습니다. -o로 다른 파일 이름을 주세요.")
    if os.path.exists(out) and not force:
        raise ToolError("출력 파일이 이미 있습니다: %s (덮어쓰려면 --force)" % out)
    tmp = out + ".tmp-hwp"
    with open(tmp, "wb") as f:
        f.write(data)
    os.replace(tmp, out)  # never leaves a half-written document behind


def expect_ext(out, kind):
    ext = os.path.splitext(out)[1].lower()
    if ext and ext != "." + kind:
        raise ToolError("출력 확장자(%s)가 원본 형식(.%s)과 다릅니다. 형식 변환은 `read` 후 `create`로 하세요."
                        % (ext, kind))


# ---- commands -----------------------------------------------------------------------------

def cmd_read(a):
    kind, f = open_doc(a.file)
    doc = f.to_document()
    fmt = a.format
    text = doc.to_markdown() if fmt == "markdown" else doc.to_text() if fmt == "text" else doc.to_json()
    if doc.warnings and fmt != "json":
        text = "".join("> ⚠ %s\n" % w for w in doc.warnings) + "\n" + text
    if a.output:
        write_output(None, a.output, text.encode("utf-8"), force=True)
        print("저장: %s (%d자)" % (a.output, len(text)))
    else:
        sys.stdout.write(text)


def _count_tables(blocks):
    n = 0
    for b in blocks:
        if isinstance(b, Table):
            n += 1
            for c in b.cells:
                n += _count_tables(c.blocks)
    return n


def _count_cells(blocks):
    n = 0
    for b in blocks:
        if isinstance(b, Table):
            n += len(b.cells)
            for c in b.cells:
                n += _count_cells(c.blocks)
    return n


def cmd_info(a):
    kind, f = open_doc(a.file)
    info = {"file": a.file, "format": kind}
    if kind == "hwp":
        info["version"] = f.version_text
        info["compressed"] = f.compressed
        info["protection"] = f.protection() or "none"
        info["sections"] = len(f.section_names)
        info["streams"] = len(f.cf.streams)
    else:
        info["parts"] = sorted(f.pkg.parts)
        info["sections"] = len(f.pkg.section_names())
    try:
        doc = f.to_document()
        info["body_paragraphs"] = sum(1 for b in doc.blocks if isinstance(b, Paragraph))  # 표 밖
        info["tables"] = _count_tables(doc.blocks)
        info["table_cells"] = _count_cells(doc.blocks)
        # Every character of text, table cells included; whitespace not counted.
        info["characters"] = len("".join(doc.to_text().split()))
        info["headers_footers"] = len(doc.headers)
        info["notes"] = len(doc.notes)
        if doc.warnings:
            info["warnings"] = doc.warnings
    except (hwp5.HwpError, hwpx.HwpxError) as e:
        info["error"] = str(e)
    print(json.dumps(info, ensure_ascii=False, indent=1))


def _editor(kind, f):
    return f if kind == "hwp" else hwpx.HwpxEditor(f)


def cmd_cells(a):
    kind, f = open_doc(a.file)
    cells = _editor(kind, f).list_cells()
    if a.json:
        print(json.dumps(cells, ensure_ascii=False, indent=1))
        return
    if not cells:
        print("표가 없습니다.")
        return
    current = None
    for c in cells:
        if c["table"] != current:
            current = c["table"]
            print("\n[표 %d]" % current)
        span = ""
        if c["rowspan"] > 1 or c["colspan"] > 1:
            span = " (병합 %dx%d)" % (c["rowspan"], c["colspan"])
        text = c["text"].replace("\n", " / ")
        print("  (행 %d, 열 %d)%s: %s" % (c["row"], c["col"], span, text if text.strip() else "〈빈 칸〉"))


def _load_pairs(a):
    pairs = list(a.pair or [])
    if a.map:
        with open(a.map, encoding="utf-8-sig") as f:
            m = json.load(f)
        if isinstance(m, dict):
            pairs.extend((str(k), "" if v is None else str(v)) for k, v in m.items())
        else:
            pairs.extend((str(x[0]), str(x[1])) for x in m)
    if not pairs:
        raise ToolError("바꿀 내용이 없습니다. --pair 이전 이후 또는 --map 파일을 주세요.")
    return pairs


def cmd_replace(a):
    kind, f = open_doc(a.file)
    expect_ext(a.output, kind)
    pairs = _load_pairs(a)
    ed = _editor(kind, f)
    counts = ed.replace_text(pairs)
    missing = [k for k, v in counts.items() if v == 0]
    if missing and a.strict:
        raise ToolError("찾지 못한 항목이 있어 저장하지 않았습니다(--strict): %s" % ", ".join(missing))
    data = ed.to_bytes()
    write_output(a.file, a.output, data, a.force)
    report = verify_edit(a.file, a.output, kind)
    for k, v in counts.items():
        print("  %s → %d곳" % (k, v))
    if missing:
        print("⚠ 문서에서 찾지 못함: %s" % ", ".join(missing))
        print("  (표시는 같아도 글자 사이에 숨은 서식·필드가 끼어 있으면 못 찾을 수 있습니다. "
              "`read`로 실제 글자를 확인하세요.)")
    print(report)


def cmd_set_cell(a):
    kind, f = open_doc(a.file)
    expect_ext(a.output, kind)
    ed = _editor(kind, f)
    items = []
    if a.map:
        with open(a.map, encoding="utf-8-sig") as fp:
            items = json.load(fp)
    if a.text is not None:
        if a.table is None or a.row is None or a.col is None:
            raise ToolError("--table, --row, --col을 모두 주세요.")
        items.append({"table": a.table, "row": a.row, "col": a.col, "text": a.text})
    if not items:
        raise ToolError("채울 셀이 없습니다.")
    for it in items:
        ed.set_cell(int(it["table"]), int(it["row"]), int(it["col"]), str(it.get("text", "")))
    data = ed.to_bytes()
    write_output(a.file, a.output, data, a.force)
    print("셀 %d개를 채웠습니다." % len(items))
    print(verify_edit(a.file, a.output, kind))


def cmd_create(a):
    from hwpkit import mdparse
    src = a.source
    with open(src, encoding="utf-8-sig") as f:
        text = f.read()
    doc = mdparse.parse_json(text) if src.lower().endswith(".json") else mdparse.parse_markdown(text)
    if a.font:
        doc.meta["font"] = a.font
    if a.size:
        doc.meta["size"] = a.size
    if a.landscape:
        doc.meta.setdefault("page", {})["orientation"] = "landscape"
    ext = os.path.splitext(a.output)[1].lower()
    if ext == ".hwpx":
        from hwpkit.hwpx_writer import HwpxWriter
        data = HwpxWriter(doc).build(title=a.title or "")
    elif ext == ".hwp":
        from hwpkit.hwp_writer import HwpWriter
        data = HwpWriter(doc).build()
    else:
        raise ToolError("출력 파일 확장자는 .hwpx 또는 .hwp여야 합니다.")
    write_output(None, a.output, data, a.force)
    print("만들었습니다: %s (%d바이트)" % (a.output, len(data)))
    print(check_file(a.output, expect=doc))


def cmd_check(a):
    print(check_file(a.file))


# ---- verification -------------------------------------------------------------------------

def check_file(path, expect=None):
    """Re-opens a written file the way a reader would and reports what it finds."""
    lines = []
    kind, f = open_doc(path)
    if kind == "hwpx":
        import zipfile
        import io
        with open(path, "rb") as fp:
            data = fp.read()
        z = zipfile.ZipFile(io.BytesIO(data))
        bad = z.testzip()
        if bad:
            raise ToolError("ZIP 손상: %s" % bad)
        first = z.infolist()[0]
        if first.filename != "mimetype" or first.compress_type != zipfile.ZIP_STORED:
            raise ToolError("mimetype이 첫 항목(무압축)이 아닙니다.")
        for name in z.namelist():
            if name.endswith(".xml") or name.endswith(".hpf"):
                hwpx.safe_xml(z.read(name))
        lines.append("점검: ZIP·mimetype·XML %d개 정상" % sum(
            1 for n in z.namelist() if n.endswith((".xml", ".hpf"))))
    else:
        lines.append("점검: 복합 문서 스트림 %d개, 본문 구역 %d개 정상" % (len(f.cf.streams), len(f.section_names)))
    doc = f.to_document()
    paras = sum(1 for b in doc.blocks if isinstance(b, Paragraph))
    lines.append("다시 읽기: 표 밖 문단 %d개(빈 문단 포함), 표 %d개" % (paras, _count_tables(doc.blocks)))
    if expect is not None:
        want = [b.text for b in expect.blocks if isinstance(b, Paragraph) and b.text.strip()]
        got = {b.text for b in doc.blocks if isinstance(b, Paragraph)}
        lost = [w for w in want if w not in got]
        if lost:
            raise ToolError("다시 읽은 문서에 빠진 문단이 있습니다: %r" % lost[:3])
        lines.append("내용 대조: 입력한 글 있는 문단 %d개가 모두 그대로 들어 있음" % len(want))
    return "\n".join(lines)


def verify_edit(src, out, kind):
    """After an edit: the result re-opens, and nothing outside the edited parts changed."""
    with open(src, "rb") as f:
        a = f.read()
    with open(out, "rb") as f:
        b = f.read()
    report = [check_file(out)]
    if kind == "hwp":
        ca, cb = cfb.CompoundFile(a), cfb.CompoundFile(b)
        changed = [p for p in ca.streams if ca.streams[p] != cb.streams.get(p)]
        allowed = {p for p in ca.streams if p.startswith("BodyText/") or p == "PrvText"}
        stray = [p for p in changed if p not in allowed]
        if stray or set(ca.streams) != set(cb.streams):
            raise ToolError("편집하지 않은 스트림이 바뀌었습니다(내부 오류): %s" % stray)
        report.append("보존: 본문 외 스트림 %d개는 원본과 바이트 단위로 동일" % (len(ca.streams) - len(changed)))
    else:
        pa, pb = hwpx.Package(a).parts, hwpx.Package(b).parts
        changed = [p for p in pa if pa[p] != pb.get(p)]
        stray = [p for p in changed if not (p.startswith("Contents/section") or p == "Preview/PrvText.txt")]
        if stray or set(pa) != set(pb):
            raise ToolError("편집하지 않은 파트가 바뀌었습니다(내부 오류): %s" % stray)
        report.append("보존: 본문 외 파트 %d개는 원본과 바이트 단위로 동일" % (len(pa) - len(changed)))
    return "\n".join(report)


# ---- main ---------------------------------------------------------------------------------

def main(argv=None):
    _utf8_stdio()
    p = argparse.ArgumentParser(prog="hwp.py", description="한글 문서(.hwp/.hwpx) 읽기·쓰기")
    sub = p.add_subparsers(dest="cmd", required=True)

    s = sub.add_parser("read", help="문서를 마크다운/텍스트/JSON으로 읽기")
    s.add_argument("file")
    s.add_argument("--format", choices=["markdown", "text", "json"], default="markdown")
    s.add_argument("-o", "--output")
    s.set_defaults(func=cmd_read)

    s = sub.add_parser("info", help="형식·버전·보호 여부·통계")
    s.add_argument("file")
    s.set_defaults(func=cmd_info)

    s = sub.add_parser("cells", help="표 셀 주소와 내용")
    s.add_argument("file")
    s.add_argument("--json", action="store_true")
    s.set_defaults(func=cmd_cells)

    s = sub.add_parser("replace", help="글자 바꾸기(서식·나머지 내용 보존)")
    s.add_argument("file")
    s.add_argument("-o", "--output", required=True)
    s.add_argument("--pair", nargs=2, action="append", metavar=("이전", "이후"))
    s.add_argument("--map", help='JSON 파일: {"이전": "이후", ...}')
    s.add_argument("--strict", action="store_true", help="하나라도 못 찾으면 저장하지 않음")
    s.add_argument("--force", action="store_true", help="출력 파일이 있으면 덮어쓰기")
    s.set_defaults(func=cmd_replace)

    s = sub.add_parser("set-cell", help="표 셀 내용 채우기")
    s.add_argument("file")
    s.add_argument("-o", "--output", required=True)
    s.add_argument("--table", type=int, help="표 번호 (0부터, `cells` 출력 그대로)")
    s.add_argument("--row", type=int, help="행 번호 (0부터)")
    s.add_argument("--col", type=int, help="열 번호 (0부터)")
    s.add_argument("--text")
    s.add_argument("--map", help='JSON 파일: [{"table":0,"row":1,"col":2,"text":"..."}]')
    s.add_argument("--force", action="store_true")
    s.set_defaults(func=cmd_set_cell)

    s = sub.add_parser("create", help="마크다운/JSON으로 새 문서 만들기")
    s.add_argument("output", help="결과 파일 (.hwpx 권장, .hwp 가능)")
    s.add_argument("--from", dest="source", required=True, help=".md 또는 .json")
    s.add_argument("--title", help="문서 정보의 제목")
    s.add_argument("--font", help='본문 글꼴 (예: "맑은 고딕"). 기본: 함초롬바탕')
    s.add_argument("--size", type=float, help="본문 글자 크기(pt). 기본 10")
    s.add_argument("--landscape", action="store_true", help="가로 방향")
    s.add_argument("--force", action="store_true")
    s.set_defaults(func=cmd_create)

    s = sub.add_parser("check", help="파일을 다시 열어 구조 점검")
    s.add_argument("file")
    s.set_defaults(func=cmd_check)

    a = p.parse_args(argv)
    try:
        a.func(a)
    except (ToolError, hwp5.HwpError, hwpx.HwpxError, cfb.CfbError) as e:
        print("오류: %s" % e, file=sys.stderr)
        return 1
    except Exception as e:  # noqa: BLE001 - last resort: say what failed, not a traceback wall
        from hwpkit import hwpx_writer
        if isinstance(e, getattr(hwpx_writer, "WriterError", ())):
            print("오류: %s" % e, file=sys.stderr)
            return 1
        raise
    return 0


if __name__ == "__main__":
    sys.exit(main())
