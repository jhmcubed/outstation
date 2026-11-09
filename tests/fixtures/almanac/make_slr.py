"""Writes slr.ws here: a world list in the game's own binary format, as RuneLite's WorldsService read it (a 4-byte
length, the worlds' count in 2 bytes, then each world's number (2), type mask (4), address and activity (0-ended),
location (1) and players (2, signed; -1 offline), big-endian), with made-up worlds that cover what the relays read:
seasonal and deadman worlds, a beta, an offline world, every region RuneLite knows and one it doesn't, a Latin-1
activity, and a mask with a bit RuneLite has no name for. A few bytes past the length are left over (they're not read).

    python3 tests/fixtures/almanac/make_slr.py
"""
import os
import struct

M, PVP, SKILL_TOTAL, HIGH_RISK, BETA, FRESH, DEADMAN, SEASONAL = 1, 1 << 2, 1 << 7, 1 << 10, 1 << 16, 1 << 27, 1 << 29, 1 << 30
WORLDS = [
    # number, mask, address, activity, location, players
    (302, M, "oldschool2.runescape.com", "Trade - Members", 1, 1500),
    (301, 0, "oldschool1.runescape.com", "Trade - Free", 0, 1200),
    (303, M | SKILL_TOTAL, "oldschool3.runescape.com", "1500 skill total", 7, 400),
    (304, M | PVP, "oldschool4.runescape.com", "PvP World", 3, 300),
    (305, M | SEASONAL, "oldschool5.runescape.com", "Leagues VI", 0, 2000),
    (306, M | SEASONAL, "oldschool6.runescape.com", "Leagues VI", 1, 1800),
    (307, M | SEASONAL, "oldschool7.runescape.com", "Leagues VI - Trade", 3, 700),
    (308, M | DEADMAN | SEASONAL, "oldschool8.runescape.com", "Deadman: Annihilation", 8, 900),
    (309, M | SEASONAL | BETA, "oldschool9.runescape.com", "Beta", 0, 50),
    (310, M, "oldschool10.runescape.com", "-", 12, -1),
    (311, M | HIGH_RISK, "oldschool11.runescape.com", "  Caf\xe9\tworld  ", 5, 77),
    (312, M | FRESH, "oldschool12.runescape.com", "Fresh Start", 9, 600),
    (313, M | (1 << 31), "oldschool13.runescape.com", "", 10, 10),
]


def build():
    body = struct.pack(">H", len(WORLDS))
    for n, mask, addr, act, loc, players in WORLDS:
        body += struct.pack(">HI", n, mask) + addr.encode("latin-1") + b"\0" + act.encode("latin-1") + b"\0"
        body += struct.pack(">Bh", loc, players)
    return struct.pack(">i", len(body)) + body + b"\xff\xfe\xfd"


if __name__ == "__main__":
    with open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "slr.ws"), "wb") as f:
        f.write(build())
