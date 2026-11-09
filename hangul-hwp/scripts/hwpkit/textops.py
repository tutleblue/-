"""Text matching shared by the .hwp and .hwpx editors."""


def find_matches(text, pairs, counts):
    """Non-overlapping matches in ``text``, scanning left to right and preferring the longest key
    at each position. Returns [(start, end, new)] and adds to ``counts[old]``."""
    keys = sorted((p for p in pairs if p[0]), key=lambda p: -len(p[0]))
    found, i, n = [], 0, len(text)
    while i < n:
        for old, new in keys:
            if text.startswith(old, i):
                found.append((i, i + len(old), new))
                counts[old] += 1
                i += len(old)
                break
        else:
            i += 1
    return found


def squash(s):
    """Text with all whitespace removed, for matching labels such as "성 명" against "성명"."""
    return "".join(s.split())
