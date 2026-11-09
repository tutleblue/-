"""Compound File Binary (OLE2) container: the outer shell of every .hwp file.

Reading flattens the container into ``{"BodyText/Section0": bytes, ...}``.

Writing never invents a directory. It takes the directory of the file that was read and only
re-lays out the sectors around new stream contents. The directory tree (names, red-black colours,
sibling links, CLSIDs, timestamps) is the part other readers are strictest about, and a tree that
Hangul itself wrote is known to be one Hangul accepts. Editing therefore never adds or removes a
stream; it only changes what is inside one.
"""

import struct

SIGNATURE = b"\xD0\xCF\x11\xE0\xA1\xB1\x1A\xE1"
SECTOR = 512
MINI_SECTOR = 64
MINI_CUTOFF = 4096
DIR_ENTRY = 128

FREESECT = 0xFFFFFFFF
ENDOFCHAIN = 0xFFFFFFFE
FATSECT = 0xFFFFFFFD
DIFSECT = 0xFFFFFFFC
NOSTREAM = 0xFFFFFFFF

TYPE_EMPTY, TYPE_STORAGE, TYPE_STREAM, TYPE_ROOT = 0, 1, 2, 5

# Generous upper bounds so a hostile file cannot make us allocate without limit.
MAX_STREAM = 512 * 1024 * 1024


class CfbError(Exception):
    pass


class Entry:
    __slots__ = ("index", "raw", "name", "type", "left", "right", "child", "start", "size", "path")

    def __init__(self, index, raw):
        self.index = index
        self.raw = bytearray(raw)
        name_len = struct.unpack_from("<H", raw, 64)[0]
        chars = max(0, min(32, name_len // 2 - 1))
        self.name = raw[: chars * 2].decode("utf-16-le", "replace").split("\x00")[0]
        self.type = raw[66]
        self.left, self.right, self.child = struct.unpack_from("<III", raw, 68)
        self.start = struct.unpack_from("<I", raw, 116)[0]
        self.size = struct.unpack_from("<Q", raw, 120)[0]
        self.path = None


class CompoundFile:
    """A parsed container. ``streams`` maps slash paths to bytes; edit it, then ``to_bytes()``."""

    def __init__(self, data):
        if len(data) < SECTOR or data[:8] != SIGNATURE:
            raise CfbError("복합 문서(OLE2) 형식이 아닙니다.")
        self._data = data
        self._parse()

    # ---- reading ------------------------------------------------------------------------

    def _parse(self):
        d = self._data
        (minor, major, order, sshift, mshift) = struct.unpack_from("<HHHHH", d, 24)
        if order != 0xFFFE:
            raise CfbError("지원하지 않는 바이트 순서입니다.")
        self.sector_size = 1 << sshift
        if self.sector_size not in (512, 4096):
            raise CfbError("손상된 복합 문서 헤더입니다(섹터 크기 %d)." % self.sector_size)
        self.major = major
        (n_fat, first_dir, _txn, cutoff, first_minifat, n_minifat, first_difat, n_difat) = struct.unpack_from(
            "<IIIIIIII", d, 44)
        self.mini_cutoff = cutoff or MINI_CUTOFF

        fat_sectors = []
        for i in range(109):
            s = struct.unpack_from("<I", d, 76 + i * 4)[0]
            if s < 0xFFFFFFFA:
                fat_sectors.append(s)
        nxt, guard = first_difat, 0
        per = self.sector_size // 4
        while nxt < 0xFFFFFFFA and guard <= n_difat + 1:
            sec = self._sector(nxt)
            vals = struct.unpack("<%dI" % per, sec)
            fat_sectors.extend(v for v in vals[:-1] if v < 0xFFFFFFFA)
            nxt = vals[-1]
            guard += 1
        fat = []
        for s in fat_sectors:
            fat.extend(struct.unpack("<%dI" % per, self._sector(s)))
        self.fat = fat

        minifat_raw = self._chain(first_minifat) if n_minifat and first_minifat < 0xFFFFFFFA else b""
        self.minifat = list(struct.unpack("<%dI" % (len(minifat_raw) // 4), minifat_raw[: len(minifat_raw) // 4 * 4]))

        dir_raw = self._chain(first_dir)
        self.entries = [Entry(i, dir_raw[i * DIR_ENTRY:(i + 1) * DIR_ENTRY])
                        for i in range(len(dir_raw) // DIR_ENTRY)]
        if not self.entries or self.entries[0].type != TYPE_ROOT:
            raise CfbError("복합 문서의 루트 항목이 없습니다.")
        root = self.entries[0]
        self._ministream = self._chain(root.start) if root.start < 0xFFFFFFFA else b""

        self.streams = {}
        self.order = []  # stream paths in directory-walk order
        seen = set()

        def walk(idx, prefix):
            stack = [(idx, prefix)]
            # Iterative in-order walk; the sibling tree can be deep in large files.
            while stack:
                i, pre = stack.pop()
                if i == NOSTREAM or i >= len(self.entries) or i in seen:
                    continue
                seen.add(i)
                e = self.entries[i]
                stack.append((e.right, pre))
                path = e.name if not pre else pre + "/" + e.name
                e.path = path
                if e.type == TYPE_STORAGE:
                    stack.append((e.child, path))
                elif e.type == TYPE_STREAM:
                    self.streams[path] = self._read_stream(e)
                    self.order.append(path)
                stack.append((e.left, pre))

        walk(root.child, "")

    def _sector(self, n):
        off = (n + 1) * self.sector_size
        if off + self.sector_size > len(self._data):
            # Some writers truncate the final sector; pad rather than reject.
            chunk = self._data[off:off + self.sector_size]
            if not chunk:
                raise CfbError("섹터 %d가 파일 범위를 벗어납니다. 손상된 문서입니다." % n)
            return chunk + b"\x00" * (self.sector_size - len(chunk))
        return self._data[off:off + self.sector_size]

    def _chain(self, start, limit=None):
        out, n, seen = [], start, set()
        while n < 0xFFFFFFFA:
            if n in seen:
                raise CfbError("섹터 체인이 순환합니다. 손상된 문서입니다.")
            seen.add(n)
            out.append(self._sector(n))
            if limit is not None and len(out) * self.sector_size >= limit:
                break
            n = self.fat[n] if n < len(self.fat) else ENDOFCHAIN
        return b"".join(out)

    def _read_stream(self, e):
        size = e.size if self.major >= 4 else e.size & 0xFFFFFFFF
        if size > MAX_STREAM:
            raise CfbError("스트림이 너무 큽니다(%d바이트)." % size)
        if size == 0:
            return b""
        if size < self.mini_cutoff:
            out, n, seen = bytearray(), e.start, set()
            while n < 0xFFFFFFFA and len(out) < size:
                if n in seen:
                    raise CfbError("미니 섹터 체인이 순환합니다.")
                seen.add(n)
                out += self._ministream[n * MINI_SECTOR:(n + 1) * MINI_SECTOR]
                n = self.minifat[n] if n < len(self.minifat) else ENDOFCHAIN
            return bytes(out[:size])
        return self._chain(e.start, limit=size)[:size]

    # ---- writing ------------------------------------------------------------------------

    def to_bytes(self):
        """Re-lays out every sector around the current stream contents, keeping the directory.

        Paths in ``streams`` must be exactly the ones that were read: adding or removing a stream
        would need a new directory tree, which is deliberately not done here.
        """
        by_path = {e.path: e for e in self.entries if e.type == TYPE_STREAM and e.path}
        if set(by_path) != set(self.streams):
            raise CfbError("스트림 목록이 바뀌었습니다. 이 도구는 기존 스트림의 내용만 바꿉니다.")

        S = SECTOR
        chains = []          # list of (first_sector, count)
        next_sector = [0]

        def alloc(nbytes):
            count = (nbytes + S - 1) // S
            if count == 0:
                return ENDOFCHAIN
            first = next_sector[0]
            next_sector[0] += count
            chains.append((first, count))
            return first

        mini = bytearray()
        big = []
        for path in self.order:
            e, data = by_path[path], self.streams[path]
            if len(data) == 0:
                start = ENDOFCHAIN
            elif len(data) < MINI_CUTOFF:
                start = len(mini) // MINI_SECTOR
                mini += data
                mini += b"\x00" * ((-len(data)) % MINI_SECTOR)
            else:
                start = ENDOFCHAIN  # filled in once regular sectors are allocated below
                big.append(path)
            struct.pack_into("<IQ", e.raw, 116, start, len(data))

        big_start = {}
        for path in big:
            big_start[path] = alloc(len(self.streams[path]))
            struct.pack_into("<I", by_path[path].raw, 116, big_start[path])

        mini_start = alloc(len(mini))
        root = self.entries[0]
        struct.pack_into("<IQ", root.raw, 116, mini_start if mini else ENDOFCHAIN, len(mini))

        n_mini = len(mini) // MINI_SECTOR
        minifat = [ENDOFCHAIN] * n_mini
        for path in self.order:
            data = self.streams[path]
            if 0 < len(data) < MINI_CUTOFF:
                first = struct.unpack_from("<I", by_path[path].raw, 116)[0]
                count = (len(data) + MINI_SECTOR - 1) // MINI_SECTOR
                for k in range(count):
                    minifat[first + k] = ENDOFCHAIN if k == count - 1 else first + k + 1
        minifat_bytes = struct.pack("<%dI" % n_mini, *minifat)
        minifat_start = alloc(len(minifat_bytes))
        minifat_count = (len(minifat_bytes) + S - 1) // S

        # The output is always version 3 (512-byte sectors), where the high half of every size
        # field must be zero; a version-4 source may have left something there for storages.
        dir_bytes = bytearray()
        for e in self.entries:
            struct.pack_into("<I", e.raw, 124, 0)
            dir_bytes += e.raw
        while len(dir_bytes) % S:
            dir_bytes += _empty_entry()
        dir_start = alloc(len(dir_bytes))

        data_sectors = next_sector[0]
        n_fat, n_difat = 1, 0
        while True:
            n_difat = 0 if n_fat <= 109 else (n_fat - 109 + 126) // 127
            if n_fat * (S // 4) >= data_sectors + n_fat + n_difat:
                break
            n_fat += 1
        fat_start = data_sectors
        difat_start = fat_start + n_fat

        fat = [FREESECT] * (n_fat * (S // 4))
        for first, count in chains:
            for k in range(count):
                fat[first + k] = ENDOFCHAIN if k == count - 1 else first + k + 1
        for k in range(n_fat):
            fat[fat_start + k] = FATSECT
        for k in range(n_difat):
            fat[difat_start + k] = DIFSECT

        header = bytearray(512)
        header[0:8] = SIGNATURE
        struct.pack_into("<HHHHH", header, 24, 0x3E, 3, 0xFFFE, 9, 6)
        struct.pack_into("<IIIIIIII", header, 44, n_fat, dir_start, 0, MINI_CUTOFF,
                         minifat_start if n_mini else ENDOFCHAIN, minifat_count if n_mini else 0,
                         difat_start if n_difat else ENDOFCHAIN, n_difat)
        for i in range(109):
            struct.pack_into("<I", header, 76 + i * 4, fat_start + i if i < n_fat else FREESECT)

        out = bytearray(header)

        def put(blob):
            out.extend(blob)
            pad = (-len(blob)) % S
            if pad:
                out.extend(b"\x00" * pad)

        for path in big:
            put(self.streams[path])
        put(bytes(mini))
        if n_mini:
            blob = bytearray(minifat_bytes)
            while len(blob) % S:
                blob += struct.pack("<I", FREESECT)
            put(bytes(blob))
        put(bytes(dir_bytes))
        put(struct.pack("<%dI" % len(fat), *fat))
        if n_difat:
            idx = 109
            for s in range(n_difat):
                vals = []
                for _ in range(127):
                    vals.append(fat_start + idx if idx < n_fat else FREESECT)
                    idx += 1
                vals.append(ENDOFCHAIN if s == n_difat - 1 else difat_start + s + 1)
                put(struct.pack("<128I", *vals))
        return bytes(out)


def _empty_entry():
    e = bytearray(DIR_ENTRY)
    struct.pack_into("<III", e, 68, NOSTREAM, NOSTREAM, NOSTREAM)
    return bytes(e)


def is_cfb(data):
    return data[:8] == SIGNATURE


# ---- building a new container -----------------------------------------------------------

def _name_key(name):
    # [MS-CFB] 2.6.4: shorter names first, then by upper-cased UTF-16 code units.
    units = name.encode("utf-16-le")
    return (len(units), name.upper())


def build(streams):
    """A new version-3 container from ``[(path, bytes)]``, for documents made from scratch.

    Each storage's children form a red-black tree as [MS-CFB] requires: a balanced binary tree
    whose deepest level is coloured red when that level is not full, which gives every path the
    same number of black nodes. Timestamps and CLSIDs are left zero: nothing in a .hwp needs them,
    and they are where a container would otherwise record when and on what it was made.
    """

    class N:
        def __init__(self, name, is_stream, data=b""):
            self.name, self.is_stream, self.data = name, is_stream, data
            self.kids = {}
            self.sid = None
            self.left = self.right = self.child = NOSTREAM
            self.red = False

    root = N("Root Entry", False)
    for path, data in streams:
        parts = [p for p in path.split("/") if p]
        cur = root
        for p in parts[:-1]:
            cur = cur.kids.setdefault(p, N(p, False))
        cur.kids[parts[-1]] = N(parts[-1], True, data)

    order = [root]
    root.sid = 0

    def number(n):
        for k in sorted(n.kids.values(), key=lambda x: _name_key(x.name)):
            k.sid = len(order)
            order.append(k)
            number(k)

    number(root)

    def link(n):
        kids = sorted(n.kids.values(), key=lambda x: _name_key(x.name))
        depth = {}

        def tree(lo, hi, d):
            if lo > hi:
                return NOSTREAM
            mid = (lo + hi) // 2
            node = kids[mid]
            depth[id(node)] = d
            node.left = tree(lo, mid - 1, d + 1)
            node.right = tree(mid + 1, hi, d + 1)
            return node.sid

        n.child = tree(0, len(kids) - 1, 0)
        if kids:
            deepest = max(depth.values())
            perfect = len(kids) == (1 << (deepest + 1)) - 1
            for k in kids:
                k.red = (not perfect) and depth[id(k)] == deepest
        for k in kids:
            link(k)

    link(root)

    # Reuse the sector layout code of to_bytes() by presenting the same shape it expects.
    cf = CompoundFile.__new__(CompoundFile)
    cf.entries = []
    cf.order = []
    cf.streams = {}
    for n in order:
        raw = bytearray(DIR_ENTRY)
        name = n.name.encode("utf-16-le")[:62]
        raw[:len(name)] = name
        struct.pack_into("<H", raw, 64, len(name) + 2)
        raw[66] = TYPE_ROOT if n is root else (TYPE_STREAM if n.is_stream else TYPE_STORAGE)
        raw[67] = 0 if n.red else 1
        struct.pack_into("<III", raw, 68, n.left, n.right, n.child)
        e = Entry(n.sid, bytes(raw))
        cf.entries.append(e)
    paths = {}

    def collect(n, prefix):
        for k in sorted(n.kids.values(), key=lambda x: _name_key(x.name)):
            p = k.name if not prefix else prefix + "/" + k.name
            if k.is_stream:
                paths[k.sid] = p
                cf.streams[p] = k.data
                cf.order.append(p)
            collect(k, p)

    collect(root, "")
    for e in cf.entries:
        e.path = paths.get(e.index)
    return cf.to_bytes()
