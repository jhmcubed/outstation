"""The lookups (11/10/2026; docs/protocol.md "The lookups"): Wise Old Man's and RuneProfile's records of a player, and
the item and metric icons, fetched by the relay and kept, so a page never asks those hosts itself. The Worker's
relay/src/lookup.ts, the same, line for line where it can be (its header says the whole of it):

    GET /lookup/wom/<name>[/achievements|/gained|/groups]    GET /lookup/rp/<name>[/activities]
    GET /icon/item/<id>.png                                   GET /icon/metric/<metric>.png

200 the record (fresh for its time), 404 not there (kept six hours; a day for an icon), 503 not asked just now with
Retry-After; x-lookup-at when it was fetched, x-lookup-stale: 1 when the last good one stands for a failing source.
Kept in memory here (the Worker's, in its object's storage), so a restart asks again. Standard library only.
"""

import asyncio
import json
import re
import time
import urllib.error
import urllib.parse
import urllib.request

USER_AGENT = ("outstation-relay/1.0 (lookups for outside.wiki and ghillie.wiki's Outside page: each player "
              "asked once and shared by every visitor; https://github.com/jhmcubed/outstation)")

MINUTE, HOUR, DAY = 60.0, 3600.0, 86400.0
SOURCES = ("wom", "rp", "item", "metric")
HOSTS = {
    "wom": "https://api.wiseoldman.net/v2/players/",
    "rp": "https://api.runeprofile.com/v1/accounts/",
    "item": "https://static.runelite.net/cache/item/icon/",
    "metric": "https://cdn.jsdelivr.net/gh/wise-old-man/wise-old-man@master/app/public/img/metrics_small/",
}
PER_MINUTE = {"wom": 15, "rp": 20, "item": 60, "metric": 60}
FRESH_ICON, MISS_FRESH, MISS_FRESH_ICON = 30 * DAY, 6 * HOUR, DAY
KEEP_DAYS, KEEP_DAYS_ICON, KEPT_MOST = 14, 90, 5000
FAIL_BASE, FAIL_CAP, PAUSE_DEFAULT, PAUSE_MOST = MINUTE, HOUR, MINUTE, 10 * MINUTE
BODY_MOST = {"wom": 1024 * 1024, "rp": 1024 * 1024, "item": 64 * 1024, "metric": 64 * 1024}
FETCH_TIMEOUT_S = 15
NAME_OK = re.compile(r"^[A-Za-z0-9 _-]{1,12}$")
ITEM_ID = re.compile(r"^[0-9]{1,6}\.png$")
METRIC = re.compile(r"^[a-z0-9_]{1,40}\.png$")
PNG = b"\x89PNG\r\n\x1a\n"
PARTS = {
    "wom": {"": ("", HOUR), "achievements": ("/achievements", HOUR), "gained": ("/gained?period=week", HOUR),
            "groups": ("/groups", 6 * HOUR)},
    "rp": {"": ("", HOUR), "activities": ("/activities?limit=20", 30 * MINUTE)},
}
CORS = {"Access-Control-Allow-Origin": "*", "Access-Control-Expose-Headers": "x-lookup-at, x-lookup-stale"}


def plain_name(raw):
    """A name as Wise Old Man reads it (lower case, a run of spaces, _ and - as one space), or None."""
    if not NAME_OK.match(raw):
        return None
    n = re.sub(r"[ _-]+", " ", raw.lower()).strip()
    return n or None


def want(parts, base=None):
    """The address's parts as {source, key, url, fresh, miss_fresh, json}, or None if it isn't one of the shapes."""
    miss_fresh, is_json = MISS_FRESH, True
    if len(parts) in (3, 4) and parts[0] == "lookup" and parts[1] in ("wom", "rp"):
        name, part = plain_name(parts[2]), parts[3] if len(parts) == 4 else ""
        spec = PARTS[parts[1]].get(part)
        if not name or not spec or (len(parts) == 4 and not part):
            return None
        source = parts[1]
        key = source + ":" + name + ("/" + part if part else "")
        path = urllib.parse.quote(name, safe="") + spec[0]
        fresh = spec[1]
    elif len(parts) == 3 and parts[0] == "icon" and parts[1] in ("item", "metric"):
        f = parts[2]
        if not (ITEM_ID if parts[1] == "item" else METRIC).match(f):
            return None
        source = parts[1]
        name = "%d.png" % int(f[:-4]) if source == "item" else f
        key, path, fresh, miss_fresh, is_json = source + ":" + name, name, FRESH_ICON, MISS_FRESH_ICON, False
    else:
        return None
    host = HOSTS[source]
    if base:
        host = base.rstrip("/") + "/" + "/".join(host.split("://")[1].split("/")[1:])
    return {"source": source, "key": key, "url": host + path, "fresh": fresh, "miss_fresh": miss_fresh, "json": is_json}


def looks_right(w, body):
    if not w["json"]:
        return len(body) >= 8 and body[:8] == PNG
    try:
        json.loads(body.decode("utf-8"))
        return True
    except (ValueError, UnicodeDecodeError):
        return False


def answer_of(w, e, stale, retry):
    """(status, headers, body)."""
    if e is None:
        return 503, dict(CORS, **{"Content-Type": "application/json", "Cache-Control": "no-store",
                                  "Retry-After": str(max(1, int(-(-retry // 1))))}), \
            json.dumps({"error": "not asked just now: try again later"}).encode()
    h = dict(CORS, **{"Content-Type": e["type"], "x-lookup-at": str(int(e["at"] * 1000))})
    if stale:
        h["x-lookup-stale"] = "1"
    h["Cache-Control"] = ("public, max-age=60" if stale else "public, max-age=3600" if e["status"] == 404
                          else "public, max-age=300" if w["json"] else "public, max-age=604800")
    return e["status"], h, e["body"]


def http_lookup(url, source):
    """One GET with USER_AGENT and nothing else: (status, body or None, Retry-After seconds or None)."""
    req = urllib.request.Request(url, headers={
        "User-Agent": USER_AGENT, "Accept": "application/json" if source in ("wom", "rp") else "image/png"})
    try:
        with urllib.request.urlopen(req, timeout=FETCH_TIMEOUT_S) as r:
            body = r.read(BODY_MOST[source] + 1)
            if len(body) > BODY_MOST[source]:
                return 502, None, None
            return r.status, body, None
    except urllib.error.HTTPError as e:
        try:
            ra = int(e.headers.get("Retry-After") or "")
        except ValueError:
            ra = None
        return e.code, None, ra


async def async_lookup(url, source):
    return await asyncio.to_thread(http_lookup, url, source)


class Shelf:
    """The lookups' keeping and asking (the Worker's Shelf, the same); the fetcher and the clock may be the tests'."""

    def __init__(self, fetcher=async_lookup, now=time.time):
        self.fetcher, self.now = fetcher, now
        self.kept = {}
        self.starts = {s: [] for s in SOURCES}
        self.paused = {s: 0.0 for s in SOURCES}
        self.fails = {}
        self.asking = {}

    def wait(self, source):
        t, s = self.now(), self.starts[source]
        while s and t - s[0] >= MINUTE:
            s.pop(0)
        at = max(0.0, self.paused[source] - t)
        if len(s) >= PER_MINUTE[source]:
            at = max(at, s[0] + MINUTE - t)
        return at

    async def read(self, w):
        kept, t = self.kept.get(w["key"]), self.now()
        if kept and t - kept["at"] < (w["fresh"] if kept["status"] == 200 else w["miss_fresh"]):
            return answer_of(w, kept, False, 0)
        f = self.fails.get(w["key"])
        retry = max(self.wait(w["source"]), f["next"] - t if f else 0)
        if retry <= 0:
            task = self.asking.get(w["key"])
            if task is None:
                task = asyncio.ensure_future(self.ask(w))
                self.asking[w["key"]] = task
                task.add_done_callback(lambda _t, k=w["key"]: self.asking.pop(k, None))
            got = await asyncio.shield(task)
            if got:
                return answer_of(w, got, False, 0)
            f2 = self.fails.get(w["key"])
            retry = max(self.wait(w["source"]), f2["next"] - self.now() if f2 else FAIL_BASE)
        return answer_of(w, kept, True, retry) if kept else answer_of(w, None, False, retry)

    async def ask(self, w):
        self.starts[w["source"]].append(self.now())
        try:
            status, body, retry_after = await self.fetcher(w["url"], w["source"])
        except Exception:
            status, body, retry_after = 0, None, None
        t, e = self.now(), None
        if status == 200 and body is not None and len(body) <= BODY_MOST[w["source"]] and looks_right(w, body):
            e = {"at": t, "status": 200, "type": "application/json" if w["json"] else "image/png", "body": body}
        elif status == 404 or (w["source"] == "rp" and status == 400):
            e = {"at": t, "status": 404, "type": "application/json", "body": json.dumps({"error": "not found"}).encode()}
        elif status == 429:
            ra = retry_after if retry_after and retry_after > 0 else PAUSE_DEFAULT
            self.paused[w["source"]] = t + min(ra, PAUSE_MOST)
        if e:
            self.fails.pop(w["key"], None)
            self.kept[w["key"]] = e
            self.prune()
            return e
        n = (self.fails.get(w["key"]) or {}).get("n", 0) + 1
        self.fails[w["key"]] = {"n": n, "next": t + min(FAIL_BASE * 2 ** (n - 1), FAIL_CAP)}
        if len(self.fails) > KEPT_MOST:
            self.fails.pop(next(iter(self.fails)))
        return None

    def prune(self):
        for k in pruned([(k, e["at"]) for k, e in self.kept.items()], self.now()):
            self.kept.pop(k, None)


def pruned(entries, now):
    """Which kept keys go: fetched longer ago than KEEP_DAYS (an icon's, KEEP_DAYS_ICON), and past KEPT_MOST the oldest."""
    out, left = [], []
    for k, at in entries:
        icon = k.startswith("item:") or k.startswith("metric:")
        (out if now - at > (KEEP_DAYS_ICON if icon else KEEP_DAYS) * DAY else left).append((k, at))
    out = [k for k, _ in out]
    if len(left) > KEPT_MOST:
        left.sort(key=lambda x: x[1])
        out += [k for k, _ in left[:len(left) - KEPT_MOST]]
    return out
