"""Writes news-real.rss and slr-real.ws here: the real answers of Jagex's news feed and world list (fetched once each,
11/09/2026, by the session that checked the relays' parsers against them), cut down: the feed's head and its first four
items as they came, and the world list's first world of each kind of flags (with its length and count made right, and
a little of the zero padding the real one ends with: it's always 30,000 bytes, the list's length saying how much is
the list).

    python3 tests/fixtures/almanac/trim_real.py <the whole feed> <the whole slr.ws>
"""
import os
import re
import struct
import sys

HERE = os.path.dirname(os.path.abspath(__file__))


def trim_news(raw):
    items = list(re.finditer(rb"<item>[\s\S]*?</item>\s*", raw))
    return raw[:items[0].start()] + b"".join(m.group(0) for m in items[:4]) + b"</channel>\n</rss>\n"


def trim_worlds(raw):
    length, = struct.unpack(">i", raw[:4])
    count, = struct.unpack(">H", raw[4:6])
    at, records, kinds = 6, [], set()
    for _ in range(count):
        start = at
        mask, = struct.unpack(">I", raw[at + 2:at + 6])
        at = raw.index(b"\0", at + 6) + 1
        at = raw.index(b"\0", at) + 1
        loc = raw[at]
        at += 3
        if (mask, loc) not in kinds and (mask not in {k[0] for k in kinds} or len(records) < 4):
            kinds.add((mask, loc))
            records.append(raw[start:at])
    assert at <= 4 + length
    body = struct.pack(">H", len(records)) + b"".join(records)
    return struct.pack(">i", len(body)) + body + b"\0" * 32


if __name__ == "__main__":
    with open(sys.argv[1], "rb") as f, open(os.path.join(HERE, "news-real.rss"), "wb") as o:
        o.write(trim_news(f.read()))
    with open(sys.argv[2], "rb") as f, open(os.path.join(HERE, "slr-real.ws"), "wb") as o:
        o.write(trim_worlds(f.read()))
