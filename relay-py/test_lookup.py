"""The lookups' shapes, keeping and asking (lookup.py), on a clock and a network of the test's own; the Worker's
relay/test/lookup_test.mts, the same. Nothing here asks a real host.

    py relay-py/test_lookup.py
"""

import asyncio
import json
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import lookup as L  # noqa: E402

WOM, RP = L.HOSTS["wom"], L.HOSTS["rp"]
PNG = b"\x89PNG\r\n\x1a\n\x01\x02"


def W(p):
    return L.want([x for x in p.split("/") if x])


def ok_json(v):
    return 200, json.dumps(v).encode(), None


class Rig:
    def __init__(self, answers=None):
        self.t, self.answers, self.asked = 1e9, dict(answers or {}), []
        self.shelf = L.Shelf(self.fetch, lambda: self.t)

    async def fetch(self, url, source):
        self.asked.append(url)
        a = self.answers.get(url)
        if a is None:
            return 404, None, None
        return (await a()) if callable(a) else a

    def read(self, p):
        return asyncio.run(self.shelf.read(W(p)))


class Lookups(unittest.TestCase):
    def test_shapes(self):
        self.assertEqual((W("lookup/wom/Sburb")["key"], W("lookup/wom/Sburb")["url"]), ("wom:sburb", WOM + "sburb"))
        self.assertEqual(W("lookup/wom/Iron_Man-2")["url"], WOM + "iron%20man%202")
        self.assertEqual(W("lookup/wom/a b/gained")["url"], WOM + "a%20b/gained?period=week")
        self.assertEqual(W("lookup/rp/x/activities")["url"], RP + "x/activities?limit=20")
        self.assertEqual(W("icon/item/04151.png")["key"], "item:4151.png")
        self.assertEqual(L.want(["lookup", "wom", "x"], "http://127.0.0.1:9/")["url"], "http://127.0.0.1:9/v2/players/x")
        for bad in ["lookup/wom", "lookup/wom/thirteenchars", "lookup/wom/a.b", "lookup/wom/x/gained/more",
                    "lookup/wom/x/snapshots", "lookup/rp/x/groups", "lookup/hiscores/x", "lookup/wom/___",
                    "icon/item/x.png", "icon/item/1234567.png", "icon/item/1.gif", "icon/metric/Zulrah.png",
                    "icon/other/1.png", "icon/item"]:
            self.assertIsNone(L.want([x for x in bad.split("/") if x]), bad)
        self.assertIsNone(L.want(["lookup", "wom", "x", ""]))

    def test_kept_while_fresh(self):
        r = Rig({WOM + "sburb": ok_json({"username": "sburb"})})
        status, h, body = r.read("lookup/wom/sburb")
        self.assertEqual(status, 200)
        self.assertEqual(json.loads(body), {"username": "sburb"})
        self.assertEqual(h["Cache-Control"], "public, max-age=300")
        self.assertEqual(h["x-lookup-at"], str(int(r.t * 1000)))
        r.t += 59 * 60
        r.read("lookup/wom/SBURB")
        self.assertEqual(len(r.asked), 1)
        r.t += 120
        r.read("lookup/wom/sburb")
        self.assertEqual(len(r.asked), 2)

    def test_many_at_once_one_request(self):
        async def slow():
            await asyncio.sleep(0.05)
            return ok_json({})
        r = Rig({RP + "x": slow})

        async def go():
            return await asyncio.gather(*[r.shelf.read(W("lookup/rp/x")) for _ in range(10)])
        self.assertEqual({a[0] for a in asyncio.run(go())}, {200})
        self.assertEqual(len(r.asked), 1)

    def test_not_there(self):
        r = Rig({RP + "nobody": (400, None, None)})
        self.assertEqual(r.read("lookup/rp/nobody")[0], 404)
        self.assertEqual(r.read("lookup/wom/nobody")[0], 404)
        r.t += L.MISS_FRESH - 1
        r.read("lookup/rp/nobody")
        self.assertEqual(len(r.asked), 2)
        r.t += 2
        r.read("lookup/rp/nobody")
        self.assertEqual(len(r.asked), 3)

    def test_failing_stale_and_waiting(self):
        r = Rig({WOM + "x": ok_json({"v": 1})})
        r.read("lookup/wom/x")
        r.t += 2 * 3600
        r.answers[WOM + "x"] = (500, None, None)
        status, h, body = r.read("lookup/wom/x")
        self.assertEqual((status, h.get("x-lookup-stale"), json.loads(body)), (200, "1", {"v": 1}))
        r.read("lookup/wom/x")
        self.assertEqual(len(r.asked), 2)
        r.t += 60
        r.read("lookup/wom/x")
        self.assertEqual(len(r.asked), 3)
        r.t += 60
        r.read("lookup/wom/x")
        self.assertEqual(len(r.asked), 3)

    def test_nothing_kept_503(self):
        r = Rig({WOM + "x": (502, None, None)})
        status, h, _ = r.read("lookup/wom/x")
        self.assertEqual((status, h["Retry-After"], h["Cache-Control"]), (503, "60", "no-store"))

    def test_only_json_or_png(self):
        r = Rig({WOM + "x": (200, b"<html>", None), L.HOSTS["item"] + "1.png": (200, b"{}", None),
                 L.HOSTS["item"] + "2.png": (200, PNG, None)})
        self.assertEqual(r.read("lookup/wom/x")[0], 503)
        self.assertEqual(r.read("icon/item/1.png")[0], 503)
        status, h, _ = r.read("icon/item/2.png")
        self.assertEqual((status, h["Content-Type"], h["Cache-Control"]), (200, "image/png", "public, max-age=604800"))

    def test_the_minute(self):
        r = Rig({WOM + "p%d" % i: ok_json({"i": i}) for i in range(40)})
        got = [r.read("lookup/wom/p%d" % i)[0] for i in range(40)]
        self.assertEqual(len(r.asked), L.PER_MINUTE["wom"])
        self.assertEqual(got.count(200), L.PER_MINUTE["wom"])
        self.assertEqual(r.read("lookup/wom/p39")[0], 503)
        r.t += 60
        self.assertEqual(r.read("lookup/wom/p39")[0], 200)

    def test_429_pauses(self):
        r = Rig({RP + "a": (429, None, 120), RP + "b": ok_json({})})
        status, h, _ = r.read("lookup/rp/a")
        self.assertEqual((status, h["Retry-After"]), (503, "120"))
        self.assertEqual(r.read("lookup/rp/b")[0], 503)
        self.assertEqual(len(r.asked), 1)
        r.t += 120
        self.assertEqual(r.read("lookup/rp/b")[0], 200)

    def test_prune(self):
        now, day = 100 * L.DAY, L.DAY
        self.assertEqual(sorted(L.pruned([("wom:a", now - 15 * day), ("wom:b", now - 13 * day),
                                          ("item:1.png", now - 60 * day), ("metric:x.png", now - 91 * day)], now)),
                         ["metric:x.png", "wom:a"])
        many = [("rp:p%d" % i, now - i) for i in range(5003)]
        self.assertEqual(sorted(L.pruned(many, now)), ["rp:p5000", "rp:p5001", "rp:p5002"])


if __name__ == "__main__":
    unittest.main()
