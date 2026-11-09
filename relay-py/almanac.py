"""The almanac (11/09/2026; docs/protocol.md "The almanac"): what the relay fetches on a schedule for outside.wiki's
globe and shares with every visitor, so no page ever asks Jagex or the Wiki itself. The Worker's relay/src/almanac.ts
is the same, line for line where it can be; tests/fixtures/almanac/expected.json holds what both make of the fixtures.

    GET <relay>/almanac   {"at", "updates", "worlds", "market", "sources"}

The sources (each fetched by the relay alone, on its own schedule, whatever the number of visitors):
- Jagex's news (the Old School RSS feed): the newest UPDATES_KEPT items, every NEWS_EVERY_S.
- The world list (slr.ws, the game's own binary list, read as RuneLite read it: its http-service's WorldsService, and
  its WorldType masks and WorldRegion locations): every world's players, region, activity and types, the total, and the
  seasonal and deadman worlds grouped ("seasons"), every WORLDS_EVERY_S.
- The OSRS Wiki's real-time prices (prices.runescape.wiki, its /24h against the day before's, and /mapping for the
  names): the day's biggest movers and most traded. /24h's day changes once a day (its Expires header: the next UTC
  midnight), so it's asked once after midnight and then hourly only until the new day's there; the day before is the
  kept day when it's the one before, else asked once. /mapping once a day.
A source that fails keeps its last good data (with its age in "sources") and is asked again later, each failure in a
row doubling the wait (to a cap). No visitor's request is ever passed on: the only thing that leaves is the relay's
own request, with USER_AGENT.
"""

import asyncio
import json
import math
import re
import time
import zlib

NEWS_URL = "https://secure.runescape.com/m=news/latest_news.rss?oldschool=true"
WORLDS_URL = "https://oldschool.runescape.com/slr.ws?order=LPWM"
PRICES_URL = "https://prices.runescape.wiki/api/v2/osrs"
# (the Wiki's one ask, its "Acceptable use policy": a User-Agent that says what it's for, with a way to reach us)
USER_AGENT = ("outstation-relay/1.0 (the almanac for outside.wiki's globe: fetched on a schedule and shared by every "
              "visitor; https://github.com/jhmcubed/outstation)")

MINUTE, HOUR, DAY = 60_000, 3_600_000, 86_400_000
NEWS_EVERY = 20 * MINUTE          # Jagex's news
WORLDS_EVERY = 5 * MINUTE         # the world list
PRICES_EVERY = HOUR               # /24h at most this often (and only till the new day's there)
PRICES_AFTER_MIDNIGHT = 15 * MINUTE   # a new day's /24h is asked for this long after UTC midnight
MAPPING_EVERY = DAY               # the items' names
# a source is due this long before its time, so a schedule's tick that comes a moment early (the Worker's cron, every
# 5 minutes, after a fetch that took a moment) still asks it, and the world list stays every 5 minutes, not 10
DUE_SLACK = 30_000
# after n failures in a row, the next try waits min(base * 2^(n-1), cap)
BACKOFF = {"updates": (NEWS_EVERY, 2 * HOUR), "worlds": (WORLDS_EVERY, HOUR), "market": (HOUR, 6 * HOUR),
           "names": (HOUR, DAY)}
SOURCES = ("worlds", "updates", "names", "market")   # (the order they're asked in: the names before the market)

FETCH_TIMEOUT_S = 15
FETCH_MOST = 8 * 1024 * 1024      # an answer bigger than this is refused (the mapping is about 0.9 MB)
UPDATES_KEPT = 8
NEWS_ITEMS_READ = 50
ID_MAX = 200
TITLE_MAX, SUMMARY_MAX, CATEGORY_MAX, ACTIVITY_MAX, NAME_MAX = 140, 200, 40, 60, 60
WORLDS_MOST = 1000
MARKET_POOL = 200                 # the day's most traded (by gp) that movers are picked from
MARKET_MOVERS = 6
MARKET_TRADED = 4

# RuneLite's WorldType masks (runelite-api's net.runelite.api.WorldType, the same in its http-api 1.2.26), in its order
WORLD_TYPES = (("MEMBERS", 1), ("PVP", 1 << 2), ("BOUNTY", 1 << 5), ("PVP_ARENA", 1 << 6), ("SKILL_TOTAL", 1 << 7),
               ("QUEST_SPEEDRUNNING", 1 << 8), ("HIGH_RISK", 1 << 10), ("HARDCORE_MAIN", 1 << 11),
               ("LAST_MAN_STANDING", 1 << 14), ("BETA_WORLD", 1 << 16), ("LEGACY_ONLY", 1 << 22),
               ("EOC_ONLY", 1 << 23), ("NOSAVE_MODE", 1 << 25), ("TOURNAMENT", 1 << 26),
               ("FRESH_START_WORLD", 1 << 27), ("DEADMAN", 1 << 29), ("SEASONAL", 1 << 30))
# RuneLite's WorldRegion (http-api 1.2.26's valueOf(int)): the list's location byte, as ISO 3166 alpha-2
WORLD_REGIONS = {0: "US", 1: "GB", 3: "AU", 7: "DE", 8: "BR", 9: "JP", 10: "SG", 12: "ZA"}
# worlds that are never a season's island, whatever else they're flagged: betas, no-save worlds and tournaments
NOT_SEASONS = ("BETA_WORLD", "NOSAVE_MODE", "TOURNAMENT")

CONTROL = re.compile(r"[\x00-\x08\x0b\x0c\x0e-\x1f\x7f-\x9f\ufeff]")
SPACES = re.compile(r"\s+")
ENTITY = re.compile(r"&(#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|[a-zA-Z]{2,6});")
NAMED = {"amp": "&", "lt": "<", "gt": ">", "quot": '"', "apos": "'", "nbsp": " "}
CDATA = re.compile(r"<!\[CDATA\[([\s\S]*?)\]\]>")
ITEM = re.compile(r"<item\b[^>]*>([\s\S]*?)</item>", re.I)
TAG = re.compile(r"<[^>]*>")
NEWS_LINK = re.compile(r"https?://([a-z0-9-]+\.)*runescape\.com(/[^\s\"'<>]*)?", re.I)
PUB_DATE = re.compile(r"(?:[A-Za-z]{3},\s*)?([0-9]{1,2})\s+([A-Za-z]{3})\s+([0-9]{4})\s+([0-9]{2}):([0-9]{2})"
                      r"(?::([0-9]{2}))?\s*(GMT|UT|UTC|Z|[+-][0-9]{4})?")
MONTHS = ("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
ITEM_ID = re.compile(r"[0-9]{1,9}")


class SourceError(Exception):
    """A source's answer that isn't its shape (or no answer): its last good data stays."""


def num(x):
    """A number as JSON writes it the same from both relays: a whole float as an int."""
    if isinstance(x, float) and x.is_integer():
        return int(x)
    return x


def round_half_up(x):
    """JavaScript's Math.round."""
    return math.floor(x + 0.5)


def cut(text, most):
    """At most `most` characters (code points), the last an ellipsis when it's cut."""
    return text if len(text) <= most else text[:most - 1] + "…"


def one_line(text):
    return SPACES.sub(" ", CONTROL.sub("", text)).strip()


# ---- Jagex's news: the RSS feed ----

def decode_entities(text):
    """&amp; &lt; &gt; &quot; &apos; &nbsp; and numeric ones, in one pass; anything else stays as it is."""
    def one(m):
        e = m.group(1)
        if e[0] == "#":
            cp = int(e[2:], 16) if e[1] in "xX" else int(e[1:])
            if 0 < cp <= 0x10FFFF and not 0xD800 <= cp <= 0xDFFF:
                return chr(cp)
            return m.group(0)
        return NAMED.get(e, m.group(0))
    return ENTITY.sub(one, text)


def xml_text(raw):
    """An element's text: its CDATA as it is, the rest with its entities decoded."""
    out, at = [], 0
    for m in CDATA.finditer(raw):
        out.append(decode_entities(raw[at:m.start()]))
        out.append(m.group(1))
        at = m.end()
    out.append(decode_entities(raw[at:]))
    return "".join(out)


def tag_text(body, name):
    m = re.search(r"<%s\b[^>]*>([\s\S]*?)</%s\s*>" % (name, name), body, re.I)
    return xml_text(m.group(1)) if m else None


def pub_date(text):
    """An RSS date (RFC 822) as ms, or None."""
    m = PUB_DATE.fullmatch(text.strip()) if text else None
    if not m or m.group(2).lower() not in MONTHS:
        return None
    d, mo, y, hh, mm, ss = int(m.group(1)), MONTHS.index(m.group(2).lower()), int(m.group(3)), int(m.group(4)), \
        int(m.group(5)), int(m.group(6) or 0)
    if not (1 <= d <= 31 and hh <= 23 and mm <= 59 and ss <= 60):
        return None
    days = days_from_civil(y, mo + 1, d)
    ms = ((days * 24 + hh) * 60 + mm) * 60_000 + ss * 1000
    z = m.group(7) or "GMT"
    if z[0] in "+-":
        off = (int(z[1:3]) * 60 + int(z[3:5])) * 60_000
        ms -= off if z[0] == "+" else -off
    return ms


def days_from_civil(y, m, d):
    """Days since 1970-01-01 (Howard Hinnant's), so a day past a month's end rolls over as Date.UTC does."""
    y -= m <= 2
    era = (y if y >= 0 else y - 399) // 400
    yoe = y - era * 400
    doy = (153 * (m + (-3 if m > 2 else 9)) + 2) // 5 + d - 1
    doe = yoe * 365 + yoe // 4 - yoe // 100 + doy
    return era * 146097 + doe - 719468


# (the feed says its encoding in its XML declaration: Jagex's is ISO-8859-1, 11/09/2026. Those labels are read as
# windows-1252, as a browser and the Worker's TextDecoder read them; anything else as UTF-8)
SINGLE_BYTE = ("iso-8859-1", "iso8859-1", "latin-1", "latin1", "l1", "windows-1252", "cp1252", "us-ascii", "ascii")
CP1252 = {}
for _b in range(0x80, 0xA0):
    try:
        CP1252[_b] = bytes([_b]).decode("cp1252")
    except UnicodeDecodeError:
        pass                                  # (the five it leaves undefined stay as they are, as WHATWG's does)


def feed_text(raw):
    """The feed's bytes as text, in the encoding its XML declaration names."""
    if isinstance(raw, str):
        return raw
    raw = bytes(raw)
    m = re.match(rb"\s*<\?xml[^>]*?encoding\s*=\s*[\"']([A-Za-z0-9._-]+)[\"']", raw)
    if m and m.group(1).decode("ascii").lower() in SINGLE_BYTE:
        return raw.decode("latin-1").translate(CP1252)
    return raw.decode("utf-8", "replace")


def parse_news(raw):
    """The feed (bytes or text) as [{"title", "url", "at", "category", "summary"}], newest first, UPDATES_KEPT at most.
    A DOCTYPE or an ENTITY declaration is refused (nothing in the feed needs one)."""
    text = feed_text(raw)
    if re.search(r"<!DOCTYPE|<!ENTITY", text, re.I):
        raise SourceError("the feed declares a DOCTYPE or an ENTITY")
    if not re.search(r"<rss\b|<channel\b", text, re.I):
        raise SourceError("not an RSS feed")
    items = []
    for i, m in enumerate(ITEM.finditer(text)):
        if i >= NEWS_ITEMS_READ:
            break
        body = m.group(1)
        title = cut(one_line(TAG.sub(" ", tag_text(body, "title") or "")), TITLE_MAX)
        if not title:
            continue
        link = one_line(tag_text(body, "link") or "")
        url = link if NEWS_LINK.fullmatch(link) else None
        category = cut(one_line(TAG.sub(" ", tag_text(body, "category") or "")), CATEGORY_MAX) or None
        desc = tag_text(body, "description") or ""
        summary = cut(one_line(decode_entities(TAG.sub(" ", desc))), SUMMARY_MAX) or None
        at = pub_date(tag_text(body, "pubDate") or "")
        # (its id: the feed's guid, else its link (when it's on runescape.com), else its title and date: the same item keeps it, refetch after refetch)
        uid = cut(one_line(tag_text(body, "guid") or ""), ID_MAX) or (url or "")[:ID_MAX] or "%s@%s" % (title, "null" if at is None else at)
        items.append({"id": uid, "title": title, "url": url, "at": at, "category": category, "summary": summary})
    # newest first (one without a date last), the feed's own order between equals
    order = sorted(range(len(items)), key=lambda k: (items[k]["at"] is None, -(items[k]["at"] or 0), k))
    return [items[k] for k in order][:UPDATES_KEPT]


# ---- the world list: slr.ws ----

def world_types(mask):
    return [name for name, bit in WORLD_TYPES if mask & bit]


def parse_worlds(raw, now):
    """slr.ws, as RuneLite's WorldsService read it: a 4-byte length, then the worlds' count (2 bytes), then each world's
    number (2), type mask (4), address and activity (each a 0-ended string), location (1) and players (2, signed:
    -1 offline). Big-endian. The answer: {"players", "worlds", "at", "seasons", "list"}."""
    b = bytes(raw)
    if len(b) < 6:
        raise SourceError("the world list is too short")
    length = int.from_bytes(b[0:4], "big", signed=True)
    if length < 2 or 4 + length > len(b):
        raise SourceError("the world list's length isn't its size")
    end, at = 4 + length, 6
    count = int.from_bytes(b[4:6], "big")
    if count == 0 or count > WORLDS_MOST:
        raise SourceError("the world list has %d worlds" % count)

    def need(n):
        if at + n > end:
            raise SourceError("the world list ends inside a world")

    def cstring():
        nonlocal at
        z = b.find(b"\x00", at, end)
        if z < 0:
            raise SourceError("the world list ends inside a string")
        s = b[at:z].decode("latin-1")
        at = z + 1
        return s

    worlds = []
    for _ in range(count):
        need(6)
        number = int.from_bytes(b[at:at + 2], "big")
        mask = int.from_bytes(b[at + 2:at + 6], "big")   # (unsigned: the top bit's a flag too)
        at += 6
        cstring()                                        # (the address: not passed on)
        activity = cut(one_line(cstring()), ACTIVITY_MAX)
        need(3)
        location = b[at]
        players = int.from_bytes(b[at + 1:at + 3], "big", signed=True)
        at += 3
        worlds.append({"world": number, "players": players if players >= 0 else None,
                       "region": WORLD_REGIONS.get(location), "location": location, "activity": activity,
                       "types": world_types(mask)})
    worlds.sort(key=lambda w: w["world"])
    online = [w for w in worlds if w["players"] is not None]
    seasons = []
    for kind in ("leagues", "deadman"):
        # (both are SEASONAL, as RuneLite's WorldType says: "for leagues and seasonal deadman". The permanent deadman
        # world, flagged DEADMAN alone ("Deadman - Permanent" on the real list, 11/09/2026), is never a season)
        mine = [w for w in online if "SEASONAL" in w["types"] and not any(t in NOT_SEASONS for t in w["types"])
                and ("DEADMAN" in w["types"]) == (kind == "deadman")]
        if not mine:
            continue
        counts = {}
        for w in mine:
            if w["activity"]:
                counts[w["activity"]] = counts.get(w["activity"], 0) + 1
        best = None
        for w in mine:                                   # (the commonest; between equals, the lowest world's)
            a = w["activity"]
            if a and (best is None or counts[a] > counts[best]):
                best = a
        seasons.append({"kind": kind, "name": kind, "players": sum(w["players"] for w in mine), "worlds": len(mine),
                        "activity": best})
    return {"players": sum(w["players"] for w in online), "worlds": len(online), "at": now, "seasons": seasons,
            "list": worlds}


# ---- the market: the Wiki's /24h and /mapping ----

def whole(v):
    """A JSON number that's whole (1e9 as JavaScript's Number.isInteger takes it), as an int; else None."""
    if isinstance(v, bool) or not isinstance(v, (int, float)) or not math.isfinite(v) or v != int(v):
        return None
    return int(v)


def is_number(v):
    return isinstance(v, (int, float)) and not isinstance(v, bool) and math.isfinite(v) and v >= 0


def parse_day(raw):
    """/24h as {"ts": its day's start in s, "items": {id: [price, volume]}}: price the day's average, the high and low
    averages weighted by their volumes; volume both together."""
    try:
        d = json.loads(raw)
    except ValueError:
        raise SourceError("/24h isn't JSON")
    ts = whole(d.get("timestamp")) if isinstance(d, dict) else None
    if not isinstance(d, dict) or not isinstance(d.get("data"), dict) or ts is None or ts <= 0:
        raise SourceError("/24h isn't its shape")
    items = {}
    for k, v in d["data"].items():
        if not ITEM_ID.fullmatch(k) or not isinstance(v, dict):
            continue
        h, l = v.get("avgHighPrice"), v.get("avgLowPrice")
        hv, lv = v.get("highPriceVolume"), v.get("lowPriceVolume")
        h = h if is_number(h) else None
        l = l if is_number(l) else None
        hv = hv if is_number(hv) else 0
        lv = lv if is_number(lv) else 0
        if h is not None and l is not None:
            p = (h * hv + l * lv) / (hv + lv) if hv + lv > 0 else (h + l) / 2
        elif h is not None:
            p = h
        elif l is not None:
            p = l
        else:
            continue
        items[k] = [p, hv + lv]
    return {"ts": ts, "items": items}


def parse_mapping(raw):
    """/mapping as {id: name}."""
    try:
        d = json.loads(raw)
    except ValueError:
        raise SourceError("/mapping isn't JSON")
    if not isinstance(d, list):
        raise SourceError("/mapping isn't a list")
    names = {}
    for x in d:
        i = whole(x.get("id")) if isinstance(x, dict) else None
        if i is not None and 0 <= i < 10 ** 9 and isinstance(x.get("name"), str):
            name = cut(one_line(x["name"]), NAME_MAX)
            if name:
                names[str(i)] = name
    if not names:
        raise SourceError("/mapping has no items")
    return names


def market(latest, before, names):
    """The ticker (each line's "at": the Wiki's day its numbers are from, in ms): the MARKET_MOVERS biggest moves on the day before among the MARKET_POOL most traded by gp, then the
    MARKET_TRADED most traded of the rest. [{"item", "id", "price", "change", "volume", "value", "why"}]."""
    pool = [(k, p, v, p * v) for k, (p, v) in latest["items"].items() if v > 0]
    pool.sort(key=lambda x: (-x[3], int(x[0])))
    pool = pool[:MARKET_POOL]
    prev = before["items"] if before else {}

    def entry(k, p, v, value, change, why):
        return {"item": names.get(k) or "item " + k, "at": latest["ts"] * 1000, "id": int(k), "price": round_half_up(p),
                "change": None if change is None else num(round_half_up(change * 10) / 10), "volume": num(v),
                "value": round_half_up(value), "why": why}

    moves = []
    for k, p, v, value in pool:
        b = prev.get(k)
        if b and b[0] > 0 and b[1] > 0:
            moves.append((k, p, v, value, (p - b[0]) / b[0] * 100))
    moves.sort(key=lambda x: (-abs(x[4]), int(x[0])))
    out = [entry(k, p, v, value, c, "mover") for k, p, v, value, c in moves[:MARKET_MOVERS]]
    picked = {e["id"] for e in out}
    for k, p, v, value in pool:
        if len(out) >= MARKET_MOVERS + MARKET_TRADED:
            break
        if int(k) in picked:
            continue
        b = prev.get(k)
        change = (p - b[0]) / b[0] * 100 if b and b[0] > 0 and b[1] > 0 else None
        out.append(entry(k, p, v, value, change, "traded"))
    return out


def next_midnight(now):
    return (now // DAY + 1) * DAY


# ---- the schedule and what's kept ----

def fresh_state():
    return {"at": None, "tried": None, "ok": None, "fails": 0, "next": 0, "error": None}


class Almanac:
    """What's kept, and when each source is next asked. fetch(url) -> bytes (async), raising on any failure; urls
    come from base (None: the real hosts; a URL: everything under it instead, for tests). Never asked by a visitor's
    read, except once when nothing's ever been tried (a cold start), shared by every read waiting for it."""

    def __init__(self, fetch, base=None, clock=None, log=None):
        self.fetch = fetch
        self.clock = clock or (lambda: int(time.time() * 1000))
        self.log = log or (lambda *_: None)
        self.urls = urls(base)
        self.state = {s: fresh_state() for s in SOURCES}
        self.data = {"updates": None, "worlds": None, "market": None}
        self.names = {}
        self.latest = None       # the newest /24h day kept ({"ts", "items"})
        self.before = None       # the day before it
        self.day = None          # the market's day (its /24h's ts), in ms
        self.refreshing = None   # the refresh going on (a future), shared
        self.warming = False     # whether it's trying a source for the first time

    def due(self, now):
        return [s for s in SOURCES if now + DUE_SLACK >= self.state[s]["next"]]

    async def refresh(self, force=False):
        """Asks each source that's due (every one, with force) and keeps what comes. One refresh at a time: asked for
        while one runs, it waits for that one (and with force, then runs its own)."""
        while self.refreshing is not None:
            await asyncio.shield(self.refreshing)
            if not force:
                return
        self.refreshing = asyncio.ensure_future(self._refresh(force))
        await asyncio.shield(self.refreshing)

    async def _refresh(self, force):
        self.warming = any(self.state[s]["tried"] is None for s in SOURCES)
        try:
            now = self.clock()
            for s in (SOURCES if force else self.due(now)):
                await self.ask(s)
        finally:
            self.refreshing, self.warming = None, False   # (cleared before it's done: no one waiting finds them set)

    async def ask(self, source):
        st, now = self.state[source], self.clock()
        st["tried"] = now
        try:
            nxt = await getattr(self, "ask_" + source)(now)
        except Exception as e:  # noqa: BLE001 (any failure: the last good data stays)
            st["ok"], st["fails"] = False, st["fails"] + 1
            st["error"] = cut(one_line(str(e) or e.__class__.__name__), 120)
            base, cap = BACKOFF[source]
            st["next"] = now + min(base * 2 ** (st["fails"] - 1), cap)
            self.log("the almanac's %s: %s (next in %d min)" % (source, st["error"], (st["next"] - now) // MINUTE))
            return
        st.update(ok=True, fails=0, error=None, at=now, next=nxt)

    async def ask_updates(self, now):
        self.data["updates"] = parse_news(await self.fetch(self.urls["news"]))
        return now + NEWS_EVERY

    async def ask_worlds(self, now):
        self.data["worlds"] = parse_worlds(await self.fetch(self.urls["worlds"]), now)
        return now + WORLDS_EVERY

    async def ask_names(self, now):
        self.names = parse_mapping(await self.fetch(self.urls["mapping"]))
        return now + MAPPING_EVERY

    async def ask_market(self, now):
        latest = parse_day(await self.fetch(self.urls["day"]))
        if self.latest is not None and latest["ts"] == self.latest["ts"] and self.data["market"] is not None:
            # (the same day as kept: the new one isn't out yet; asked again in an hour. The ticker's made again, in
            # case the names came since)
            self.data["market"] = market(self.latest, self.before, self.names)
            return now + PRICES_EVERY
        want = latest["ts"] - 86400
        if self.latest is not None and self.latest["ts"] == want:
            before = self.latest
        elif self.before is not None and self.before["ts"] == want:
            before = self.before
        else:
            before = parse_day(await self.fetch(self.urls["day"] + "?timestamp=%d" % want))
            if before["ts"] != want:
                raise SourceError("/24h?timestamp= answered another day")
        self.latest, self.before = latest, before
        self.data["market"] = market(latest, before, self.names)
        self.day = latest["ts"] * 1000
        return max(now + PRICES_EVERY, next_midnight(now) + PRICES_AFTER_MIDNIGHT)

    def cold(self):
        """Whether a read should wait for a refresh: a source has never been tried, or the refresh running is trying
        one for the first time (a cold start). Once each has been tried, a read never waits, whatever came of it."""
        return self.warming or any(self.state[s]["tried"] is None for s in SOURCES)

    def read(self, now=None):
        """GET /almanac's answer."""
        now = self.clock() if now is None else now

        def about(s, extra=None):
            st = self.state[s]
            out = {"at": st["at"], "ok": st["ok"],
                   "age_s": None if st["at"] is None else max(0, (now - st["at"]) // 1000)}
            if extra:
                out.update(extra)
            return out
        return {"at": now, "updates": self.data["updates"], "worlds": self.data["worlds"],
                "market": self.data["market"],
                "sources": {"updates": about("updates"), "worlds": about("worlds"),
                            "market": about("market", {"day": self.day}), "names": about("names")}}

    def status(self, now=None):
        """The keeper's (POST /admin/almanac): each source's schedule and last error."""
        now = self.clock() if now is None else now
        return {"sources": {s: {"at": st["at"], "tried": st["tried"], "ok": st["ok"], "fails": st["fails"],
                                "next_in_s": max(0, (st["next"] - now) // 1000), "error": st["error"]}
                            for s, st in self.state.items()}}


def urls(base=None):
    """Each source's address: the real hosts, or (tests) everything under base, at the real one's path and query."""
    real = {"news": NEWS_URL, "worlds": WORLDS_URL, "day": PRICES_URL + "/24h", "mapping": PRICES_URL + "/mapping"}
    if not base:
        return real
    base = base.rstrip("/")
    return {k: base + "/" + v.split("://", 1)[1].split("/", 1)[1] for k, v in real.items()}


def http_fetch(url):
    """One GET with USER_AGENT (gzip asked for), the body as bytes; raises on anything but a 200 or past FETCH_MOST."""
    import urllib.request
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT, "Accept-Encoding": "gzip"})
    with urllib.request.urlopen(req, timeout=FETCH_TIMEOUT_S) as r:
        if r.status != 200:
            raise SourceError("HTTP %d" % r.status)
        body = r.read(FETCH_MOST + 1)
        if len(body) > FETCH_MOST:
            raise SourceError("the answer is bigger than %d bytes" % FETCH_MOST)
        if (r.headers.get("Content-Encoding") or "").lower() == "gzip":
            z = zlib.decompressobj(16 + zlib.MAX_WBITS)
            body = z.decompress(body, FETCH_MOST + 1)
            if len(body) > FETCH_MOST or z.unconsumed_tail:
                raise SourceError("the answer is bigger than %d bytes" % FETCH_MOST)
        return body


async def async_fetch(url):
    return await asyncio.to_thread(http_fetch, url)
