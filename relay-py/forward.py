"""Dink's posts on to Discord, by rules: the friends' server keeps getting what it got.

Dink can hold only one set of settings, so it's set generously for the
portal and posts only to the relay; the relay forwards to Discord the posts
that pass the rules here, Dink's own post untouched (the embed, the
screenshot), so the friends' channel looks as it did. The rules came from
Jace's ::dinkexport of 11/04/2026 (what Discord got then).

The rules live outside the repo, with the webhook URL (anyone with it can
post to the server): ~/Documents/ghillie portal/forward.json, the shape of
tools/portal/forward.example.json:

    {"discord": ["https://discord.com/api/webhooks/..."],
     "forward": {"LEVEL": true,                                  every one
                 "LOOT": {"minValue": 1000000, "allow": [...]},  a stack worth this, or an allowed item (* wildcards)
                 "KILL_COUNT": {"every": 25, "lapsEvery": 1000, "first": true, "personalBest": true}}}

A kind not listed (or false) isn't forwarded.
"""

import asyncio
import fnmatch
import json
import os
import time
import urllib.error
import urllib.request

DEFAULT = os.path.join(os.path.expanduser("~"), "Documents", "ghillie portal", "forward.json")
USER_AGENT = "DiscordBot (https://ghillie.wiki, 1)"


class Forward:
    def __init__(self, rules, log=print):
        self.urls = [u for u in rules.get("discord", []) if isinstance(u, str) and u.startswith(("https://", "http://"))]
        self.rules = rules.get("forward", {})
        self.log = log

    @classmethod
    def load(cls, path, log=print):
        with open(path, encoding="utf-8") as f:
            return cls(json.load(f), log)

    def summary(self):
        kinds = sorted(k for k, v in self.rules.items() if v)
        return "%d webhook%s, %d kinds (%s)" % (len(self.urls), "" if len(self.urls) == 1 else "s", len(kinds), ", ".join(kinds))

    def wants(self, p):
        """Would Discord have got this post under the rules?"""
        if not isinstance(p, dict):
            return False
        kind = p.get("type")
        rule = self.rules.get(kind)
        if not rule:
            return False
        if rule is True:
            return True
        extra = p.get("extra") if isinstance(p.get("extra"), dict) else {}
        if kind == "LOOT":
            return self._loot(rule, extra)
        if kind == "KILL_COUNT":
            return self._kill_count(rule, p, extra)
        return bool(rule)

    @staticmethod
    def _loot(rule, extra):
        items = [i for i in extra.get("items") or [] if isinstance(i, dict)]
        least = rule.get("minValue", 0)
        # Dink's "min loot value" is a stack's worth: quantity times price
        for i in items:
            try:
                if int(i.get("quantity", 0)) * int(i.get("priceEach", 0)) >= least:
                    return True
            except (TypeError, ValueError):
                pass
        allow = [str(a).strip().lower() for a in rule.get("allow", []) if str(a).strip()]
        return any(fnmatch.fnmatchcase(str(i.get("name", "")).lower(), a) for i in items for a in allow)

    @staticmethod
    def _kill_count(rule, p, extra):
        try:
            count = int(extra.get("count") or 0)
        except (TypeError, ValueError):
            count = 0
        if rule.get("personalBest") and extra.get("isPersonalBest"):
            return True
        if rule.get("first") and count == 1:
            return True
        text = (str(p.get("content") or "") + " " + str(extra.get("gameMessage") or "")).lower()
        every = rule.get("lapsEvery" if " lap" in text else "every", 1)
        return bool(every) and count > 0 and count % int(every) == 0

    async def send(self, body, content_type):
        for url in self.urls:
            why = await asyncio.to_thread(_post, url, body, content_type)
            if why:
                # never the URL itself in the log: it's the key to the server
                self.log("forward to Discord failed: %s" % why)


def _post(url, body, content_type, attempts=3):
    last = None
    for _ in range(attempts):
        req = urllib.request.Request(url, data=body, method="POST",
                                     headers={"Content-Type": content_type, "User-Agent": USER_AGENT})
        try:
            with urllib.request.urlopen(req, timeout=15):
                return None
        except urllib.error.HTTPError as e:
            if e.code == 429:
                try:
                    wait = float(e.headers.get("Retry-After") or 1)
                except ValueError:
                    wait = 1.0
                time.sleep(min(wait, 10))
                last = "rate limited"
                continue
            return "HTTP %d" % e.code
        except (urllib.error.URLError, OSError) as e:
            last = str(getattr(e, "reason", e))
            time.sleep(2)
    return last
