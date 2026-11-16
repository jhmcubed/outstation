"""Proving a name from the game itself (11/15/2026; docs/protocol.md "Proving a name"; the Worker's witness.ts, the same).

A page claimed by the Outside plugin alone is unproven: anyone can send what the plugin sends. What can't be faked is
the future: the exact experience the plugin reports while they play, before the official hiscores have it, found on the
hiscores after they log out.
  1. While they're logged in the hiscores can't have this session's experience (Jagex writes it on logout): LOOK after
     a skill's experience last changed, they're read (a look), and every figure above what they show is hidden.
  2. As they log out (the game's "off", or the plugin going), the hidden figures are claimed.
  3. A while after (WAITS), once more: a claimed skill found there exactly proves the page; lower, asked again later.
The name on the hiscores is the one the plugin says they're logged in as, and it must be the page's own.
"""
import asyncio
import json
import re
import time
import urllib.parse
import urllib.request

WAITS = [60, 180, 600, 1800]       # seconds after a logout the hiscores are asked again (witness.ts's WITNESS_WAITS)
LOOK = 45                           # seconds after a skill last changed that the hiscores are looked at (WITNESS_LOOK)
LOOK_S = None                       # (tests) every look this long instead (relay.py's --witness-look)
HISCORES = "https://secure.runescape.com"
WOM = "https://api.wiseoldman.net"
FETCH_TIMEOUT_S = 10
UPSTREAM = None                     # (tests) both hosts' stand-in (relay.py's --lookup <address>)
WAIT_S = None                       # (tests) every wait this long instead (relay.py's --witness-wait)
AGENT = "outstation-relay/1.0 (proving a page's name for outside.wiki; https://github.com/jhmcubed/outstation)"


def channel_form(name):
    """A player's name as a channel has it: lower case, spaces, no-break spaces, - and _ out."""
    return re.sub(r"[\s _-]", "", name.lower())


def skill_key(name):
    k = name.lower()
    return "runecraft" if k == "runecrafting" else k


def from_hiscores(j):
    skills = j.get("skills") if isinstance(j, dict) else None
    if not isinstance(skills, list):
        return None
    out = {}
    for s in skills:
        n, x = (s.get("name"), s.get("xp")) if isinstance(s, dict) else (None, None)
        if isinstance(n, str) and n != "Overall" and isinstance(x, int) and not isinstance(x, bool) and x >= 0:
            out[skill_key(n)] = x
    return out


def from_wom(j):
    try:
        skills = j["latestSnapshot"]["data"]["skills"]
    except (KeyError, TypeError):
        return None
    if not isinstance(skills, dict):
        return None
    out = {}
    for n, v in skills.items():
        x = v.get("experience") if isinstance(v, dict) else None
        if n != "overall" and isinstance(x, int) and not isinstance(x, bool) and x >= 0:
            out[skill_key(n)] = x
    return out


def reported(skills):
    out = {}
    if isinstance(skills, dict):
        for n, v in skills.items():
            if isinstance(v, list) and len(v) == 3 and isinstance(v[2], int):
                out[skill_key(n)] = v[2]
    return out


def note_said(seen, said, now):
    """What the plugin says, against what was seen ({skill: {"x", "at", "hidden"?}}): a changed figure is new."""
    changed = False
    for k, x in said.items():
        if k in seen and seen[k]["x"] == x:
            continue
        seen[k] = {"x": x, "at": now}
        changed = True
    return changed


def note_look(seen, h, at):
    """A look: every figure said before it and above what the hiscores show is hidden; one they show isn't."""
    for k, v in seen.items():
        if v["at"] <= at and k in h:
            v["hidden"] = v["x"] > h[k]


def claims_from(seen):
    return {k: v["x"] for k, v in seen.items() if v.get("hidden")}


def look_s():
    return LOOK_S if LOOK_S else LOOK


def judge(claims, h1):
    wait = False
    for k, x in claims.items():
        if h1.get(k) == x:
            return "proven"
        if k in h1 and h1[k] < x:
            wait = True
    return "wait" if wait else "no"


def wait_s(i):
    return WAIT_S if WAIT_S else WAITS[min(i, len(WAITS) - 1)]


def _get(url, method="GET"):
    req = urllib.request.Request(url, method=method, data=b"" if method == "POST" else None,
                                 headers={"User-Agent": AGENT, "Accept": "application/json"})
    with urllib.request.urlopen(req, timeout=FETCH_TIMEOUT_S) as r:
        return json.loads(r.read())


def read_xp_now(name, fresh):
    n = urllib.parse.quote(name.replace(" ", " "), safe="")
    try:
        # (a cache in front of the hiscores can answer with an older page: each read asks for one of its own)
        bust = "" if UPSTREAM else "&t=%d" % int(time.time() * 1000)
        x = from_hiscores(_get((UPSTREAM or HISCORES) + "/m=hiscore_oldschool/index_lite.json?player=" + n + bust))
        if x is not None:
            return x
    except Exception:
        pass
    try:
        return from_wom(_get((UPSTREAM or WOM) + "/v2/players/" + n, "POST" if fresh else "GET"))
    except Exception:
        return None


async def read_xp(name, fresh):
    """A name's experience from the official hiscores, else Wise Old Man; None when neither answers."""
    return await asyncio.to_thread(read_xp_now, name, fresh)
