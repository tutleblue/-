"""A lossless XML view: an element tree whose nodes remember where they sit in the original text.

Edits are recorded as (start, end, replacement) splices on the original string, so everything
the edit did not touch - namespace prefixes, attribute order, whitespace, self-closing style -
comes back exactly as Hangul wrote it.

Only the XML that OWPML uses is handled: elements, attributes, text, character references,
comments, CDATA and processing instructions. DTDs are refused by the caller before this runs.
"""

import re

_NAME = r"[^\s/>]+"
_TAG = re.compile(r"<(/?)(" + _NAME + r")((?:\s+[^\s=/>]+\s*=\s*(?:\"[^\"]*\"|'[^']*'))*)\s*(/?)>", re.S)
_ATTR = re.compile(r"([^\s=/>]+)\s*=\s*(\"[^\"]*\"|'[^']*')", re.S)
_ENT = re.compile(r"&(#x[0-9A-Fa-f]+|#[0-9]+|lt|gt|amp|quot|apos);")
_NAMED = {"lt": "<", "gt": ">", "amp": "&", "quot": '"', "apos": "'"}


class XmlError(Exception):
    pass


class Node:
    __slots__ = ("name", "attrs", "start", "open_end", "close_start", "end", "children", "parent")

    def __init__(self, name, attrs, start, open_end, parent):
        self.name = name                 # qualified name as written, e.g. "hp:p"
        self.attrs = attrs
        self.start = start               # offset of "<"
        self.open_end = open_end         # offset just after the start tag's ">"
        self.close_start = open_end      # offset of "</..." (== open_end when self-closing)
        self.end = open_end              # offset just after the element
        self.children = []               # Node or Text, in order
        self.parent = parent

    @property
    def local(self):
        return self.name.split(":", 1)[-1]

    @property
    def self_closing(self):
        return self.close_start == self.open_end == self.end

    def elements(self, local=None):
        return [c for c in self.children if isinstance(c, Node) and (local is None or c.local == local)]

    def iter(self):
        yield self
        for c in self.children:
            if isinstance(c, Node):
                yield from c.iter()


class Text:
    __slots__ = ("start", "end", "raw", "parent")

    def __init__(self, start, end, raw, parent):
        self.start, self.end, self.raw, self.parent = start, end, raw, parent

    @property
    def value(self):
        return unescape(self.raw)


def unescape(s):
    def sub(m):
        g = m.group(1)
        if g[0] == "#":
            return chr(int(g[2:], 16) if g[1] in "xX" else int(g[1:]))
        return _NAMED[g]
    return _ENT.sub(sub, s)


def escape(s):
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def parse(xml):
    """Returns the root element. Text outside the root (the declaration) is not modelled."""
    pos, n = 0, len(xml)
    root, stack = None, []
    while pos < n:
        lt = xml.find("<", pos)
        if lt < 0:
            lt = n
        if lt > pos and stack:
            stack[-1].children.append(Text(pos, lt, xml[pos:lt], stack[-1]))
        if lt >= n:
            break
        if xml.startswith("<?", lt):
            end = xml.find("?>", lt)
            pos = n if end < 0 else end + 2
            continue
        if xml.startswith("<!--", lt):
            end = xml.find("-->", lt)
            pos = n if end < 0 else end + 3
            continue
        if xml.startswith("<![CDATA[", lt):
            end = xml.find("]]>", lt)
            if end < 0:
                raise XmlError("닫히지 않은 CDATA")
            if stack:
                # Stored escaped so that Text.value round-trips the literal content.
                stack[-1].children.append(Text(lt, end + 3, escape(xml[lt + 9:end]), stack[-1]))
            pos = end + 3
            continue
        if xml.startswith("<!", lt):
            raise XmlError("DTD는 지원하지 않습니다.")
        m = _TAG.match(xml, lt)
        if not m:
            raise XmlError("XML 태그를 해석할 수 없습니다(위치 %d)." % lt)
        closing, name, attr_text, selfclose = m.group(1), m.group(2), m.group(3), m.group(4)
        if closing:
            if not stack or stack[-1].name != name:
                raise XmlError("태그 짝이 맞지 않습니다: </%s>" % name)
            node = stack.pop()
            node.close_start, node.end = lt, m.end()
        else:
            attrs = {k: unescape(v[1:-1]) for k, v in _ATTR.findall(attr_text)}
            node = Node(name, attrs, lt, m.end(), stack[-1] if stack else None)
            if stack:
                stack[-1].children.append(node)
            elif root is None:
                root = node
            if not selfclose:
                stack.append(node)
        pos = m.end()
    if stack:
        raise XmlError("닫히지 않은 요소가 있습니다: <%s>" % stack[-1].name)
    if root is None:
        raise XmlError("루트 요소가 없습니다.")
    return root


def apply(xml, splices):
    """Applies non-overlapping (start, end, text) splices."""
    out, cursor = [], 0
    # The same splice can be requested twice (e.g. dropping one paragraph's layout cache for two
    # edits in it); identical requests are one edit.
    for start, end, text in sorted(set(splices), key=lambda s: (s[0], s[1])):
        if start < cursor:
            raise XmlError("겹치는 편집이 있습니다(내부 오류).")
        out.append(xml[cursor:start])
        out.append(text)
        cursor = end
    out.append(xml[cursor:])
    return "".join(out)
