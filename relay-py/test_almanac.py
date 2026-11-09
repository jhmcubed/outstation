"""The almanac's parsing and schedule (almanac.py), against the fixtures the Worker's relay/test/almanac_test.mts reads
too (tests/fixtures/almanac; expected.json, what both relays make of them), and its two routes on relay.py.

    py relay-py/test_almanac.py

Nothing here asks a real host: the schedule runs on a clock of its own against the fixtures, and the relay's routes
fetch from a server on this computer.
"""

import asyncio
import http.server
import json
import os
import socket
import sys
import threading
import unittest
import urllib.error
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
FIX = os.path.join(os.path.dirname(HERE), "tests", "fixtures", "almanac")
sys.path.insert(0, HERE)
import almanac as A  # noqa: E402
import relay  # noqa: E402

T = 1794009600


def fixture(name):
    with open(os.path.join(FIX, name), "rb") as f:
        return f.read()


EXPECTED = json.loads(fixture("expected.json"))


def plain(v):
    return json.loads(json.dumps(v))


def utc(y, mo, d, h=0, mi=0):
    import calendar
    return calendar.timegm((y, mo, d, h, mi, 0)) * 1000


class Parsing(unittest.TestCase):
    def test_news(self):
        self.assertEqual(plain(A.parse_news(fixture("news.rss"))), EXPECTED["news"])

    def test_not_a_feed(self):
        with self.assertRaises(A.SourceError):
            A.parse_news('<!DOCTYPE rss [<!ENTITY a "b">]><rss><channel></channel></rss>')
        with self.assertRaises(A.SourceError):
            A.parse_news("<html>not a feed</html>")
        self.assertEqual(A.parse_news("<rss><channel></channel></rss>"), [])

    def test_dates(self):
        self.assertEqual(A.pub_date("Wed, 04 Nov 2026 11:30:00 GMT"), utc(2026, 11, 4, 11, 30))
        self.assertEqual(A.pub_date("Mon, 02 Nov 2026 15:00:00 +0100"), utc(2026, 11, 2, 14))
        self.assertEqual(A.pub_date("2 Nov 2026 15:00 -0230"), utc(2026, 11, 2, 17, 30))
        self.assertIsNone(A.pub_date("Mon, 02 Nov 2026"))
        self.assertIsNone(A.pub_date("Mon, 02 Foo 2026 15:00:00 GMT"))

    def test_worlds(self):
        self.assertEqual(plain(A.parse_worlds(fixture("slr.ws"), EXPECTED["worlds_at"])), EXPECTED["worlds"])

    def test_worlds_cut_short_or_wrong(self):
        b = fixture("slr.ws")
        for n in (0, 5, 40, len(b) - 10):
            with self.assertRaises(A.SourceError):
                A.parse_worlds(b[:n], 0)
        with self.assertRaises(A.SourceError):
            A.parse_worlds(bytes([0, 0, 0, 2, 0, 0]), 0)
        with self.assertRaises(A.SourceError):
            A.parse_worlds(b[:3] + b"\xff" + b[4:], 0)

    def test_world_flags_are_runelites(self):
        # (runelite-api's WorldType, checked 11/09/2026: the masks the seasons are read from)
        flags = dict(A.WORLD_TYPES)
        self.assertEqual((flags["SEASONAL"], flags["DEADMAN"], flags["BETA_WORLD"], flags["MEMBERS"]),
                         (1 << 30, 1 << 29, 1 << 16, 1))

    def test_names(self):
        self.assertEqual(A.parse_mapping(fixture("mapping.json")), EXPECTED["names"])

    def test_market(self):
        names = A.parse_mapping(fixture("mapping.json"))
        latest, before, nxt = (A.parse_day(fixture(n)) for n in ("24h-latest.json", "24h-before.json", "24h-next.json"))
        self.assertEqual(plain(A.market(latest, before, names)), EXPECTED["market"])
        self.assertEqual(plain(A.market(nxt, latest, names)), EXPECTED["market_next"])
        for bad in ("[]", '{"data": {}}', "not json"):
            with self.assertRaises(A.SourceError):
                A.parse_day(bad)
        with self.assertRaises(A.SourceError):
            A.parse_mapping("[]")

    def test_user_agent(self):
        self.assertIn("https://github.com/jhmcubed/outstation", A.USER_AGENT)
        self.assertNotIn("@", A.USER_AGENT)

    def test_urls_for_tests(self):
        self.assertEqual(A.urls("http://127.0.0.1:9/"), {
            "news": "http://127.0.0.1:9/m=news/latest_news.rss?oldschool=true",
            "worlds": "http://127.0.0.1:9/slr.ws?order=LPWM", "day": "http://127.0.0.1:9/api/v2/osrs/24h",
            "mapping": "http://127.0.0.1:9/api/v2/osrs/mapping"})


class Fake:
    """The sources on a clock of their own: the fixtures, the next day's /24h once it's 'published'."""

    def __init__(self, start):
        self.now, self.asked, self.published, self.failing = start, [], float("inf"), set()
        self.a = A.Almanac(self.serve, "http://up", clock=lambda: self.now)

    async def serve(self, url):
        self.asked.append(url)
        path = url[len("http://up"):]
        if any(path.startswith(f) for f in self.failing):
            raise A.SourceError("HTTP 500")
        if path.startswith("/m=news/"):
            return fixture("news.rss")
        if path.startswith("/slr.ws"):
            return fixture("slr.ws")
        if path == "/api/v2/osrs/mapping":
            return fixture("mapping.json")
        if path == "/api/v2/osrs/24h":
            return fixture("24h-next.json" if self.now >= self.published else "24h-latest.json")
        if path == "/api/v2/osrs/24h?timestamp=%d" % (T - 86400):
            return fixture("24h-before.json")
        raise A.SourceError("HTTP 404")

    def count(self, prefix):
        return sum(1 for u in self.asked if u.startswith("http://up" + prefix))


class Schedule(unittest.IsolatedAsyncioTestCase):
    async def test_a_days_requests(self):
        start = utc(2026, 11, 9, 5)
        f = Fake(start)
        f.published = utc(2026, 11, 10, 0, 5)
        for t in range(start, start + A.DAY, A.MINUTE):
            f.now = t
            await f.a.refresh()
        self.assertEqual(f.count("/slr.ws"), 288)
        self.assertEqual(f.count("/m=news/"), 72)
        self.assertEqual(f.count("/api/v2/osrs/mapping"), 1)
        self.assertEqual(f.count("/api/v2/osrs/24h?timestamp="), 1)
        self.assertEqual(f.count("/api/v2/osrs/24h"), 3)    # (the day, the day before, the next day at 00:15)
        self.assertEqual(f.a.day, (T + 86400) * 1000)
        self.assertEqual(plain(f.a.data["market"]), EXPECTED["market_next"])

    async def test_failing_keeps_last_good_and_backs_off(self):
        start = utc(2026, 11, 9, 5)
        f = Fake(start)
        await f.a.refresh()
        had = plain(f.a.read()["updates"])
        f.failing.add("/m=news/")
        waits = []
        for _ in range(5):
            f.now = f.a.state["updates"]["next"]
            await f.a.refresh()
            waits.append((f.a.state["updates"]["next"] - f.now) // A.MINUTE)
        self.assertEqual(waits, [20, 40, 80, 120, 120])
        r = f.a.read()
        self.assertEqual(plain(r["updates"]), had)
        self.assertIs(r["sources"]["updates"]["ok"], False)
        self.assertEqual(r["sources"]["updates"]["at"], start)
        self.assertGreater(r["sources"]["updates"]["age_s"], 0)
        f.failing.clear()
        f.now = f.a.state["updates"]["next"]
        await f.a.refresh()
        self.assertEqual(f.a.state["updates"]["fails"], 0)
        self.assertIs(f.a.read()["sources"]["updates"]["ok"], True)

    async def test_cold_reads_share_one_refresh(self):
        f = Fake(utc(2026, 11, 9, 5))
        self.assertTrue(f.a.cold())
        await asyncio.gather(f.a.refresh(), f.a.refresh(), f.a.refresh())
        self.assertEqual(f.count("/slr.ws"), 1)
        self.assertFalse(f.a.cold())

    async def test_names_that_come_late_reach_the_ticker(self):
        f = Fake(utc(2026, 11, 9, 5))
        f.failing.add("/api/v2/osrs/mapping")
        await f.a.refresh()
        self.assertEqual(f.a.data["market"][0]["item"], "item 561")
        f.failing.clear()
        await f.a.refresh(force=True)
        self.assertEqual(plain(f.a.data["market"]), EXPECTED["market"])

    async def test_forced(self):
        f = Fake(utc(2026, 11, 9, 5))
        await f.a.refresh()
        n = len(f.asked)
        await f.a.refresh()
        self.assertEqual(len(f.asked), n)
        await f.a.refresh(force=True)
        self.assertEqual([u[len("http://up"):] for u in f.asked[n:]],
                         ["/slr.ws?order=LPWM", "/m=news/latest_news.rss?oldschool=true", "/api/v2/osrs/mapping",
                          "/api/v2/osrs/24h"])


class Upstream(http.server.ThreadingHTTPServer):
    """The sources, served from the fixtures on this computer, with the User-Agent each request came with."""
    daemon_threads = True

    def __init__(self):
        self.seen = []
        super().__init__(("127.0.0.1", 0), Handler)
        threading.Thread(target=self.serve_forever, daemon=True).start()

    @property
    def base(self):
        return "http://127.0.0.1:%d" % self.server_address[1]


class Handler(http.server.BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def do_GET(self):
        self.server.seen.append((self.path, self.headers.get("User-Agent")))
        files = {"/m=news/latest_news.rss?oldschool=true": "news.rss", "/slr.ws?order=LPWM": "slr.ws",
                 "/api/v2/osrs/mapping": "mapping.json", "/api/v2/osrs/24h": "24h-latest.json",
                 "/api/v2/osrs/24h?timestamp=%d" % (T - 86400): "24h-before.json"}
        name = files.get(self.path)
        if not name:
            self.send_response(404)
            self.end_headers()
            return
        body = fixture(name)
        self.send_response(200)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


def free_port():
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    port = s.getsockname()[1]
    s.close()
    return port


class Routes(unittest.IsolatedAsyncioTestCase):
    """GET /almanac and the keeper's routes on relay.py, its sources a server on this computer."""
    almanac = "upstream"

    async def asyncSetUp(self):
        self.up = Upstream()
        self.port, self.public_port, started = free_port(), free_port(), asyncio.Event()

        def ready(server, r):
            self.relay = r
            started.set()
        self.task = asyncio.ensure_future(relay.serve(
            "127.0.0.1", self.port, 0.0, ready, log=lambda *_: None, public_port=self.public_port,
            admin_token="keeper-" + "x" * 16, almanac=self.up.base if self.almanac == "upstream" else self.almanac))
        await asyncio.wait_for(started.wait(), 5)

    async def asyncTearDown(self):
        self.task.cancel()
        try:
            await self.task
        except BaseException:
            pass
        self.up.shutdown()
        self.up.server_close()

    async def get(self, path, port=None, method="GET", token=None):
        def go():
            req = urllib.request.Request("http://127.0.0.1:%d%s" % (port or self.port, path), method=method,
                                         data=b"" if method == "POST" else None)
            if token:
                req.add_header("Authorization", "Bearer " + token)
            try:
                with urllib.request.urlopen(req, timeout=10) as r:
                    return r.status, dict(r.headers), r.read()
            except urllib.error.HTTPError as e:
                return e.code, dict(e.headers), e.read()
        return await asyncio.to_thread(go)

    async def test_read(self):
        status, headers, body = await self.get("/almanac")
        self.assertEqual(status, 200)
        self.assertEqual(headers.get("Access-Control-Allow-Origin"), "*")
        self.assertEqual(headers.get("Cache-Control"), "public, max-age=60")
        got = json.loads(body)
        self.assertEqual(got["updates"], EXPECTED["news"])
        self.assertEqual(got["market"], EXPECTED["market"])
        self.assertEqual({k: v for k, v in got["worlds"].items() if k != "at"},
                         {k: v for k, v in EXPECTED["worlds"].items() if k != "at"})
        # (every request named the project, and none was made for a read after the first)
        self.assertTrue(self.up.seen and all(ua == A.USER_AGENT for _, ua in self.up.seen))
        n = len(self.up.seen)
        for _ in range(5):
            await self.get("/almanac")
        self.assertEqual(len(self.up.seen), n)

    async def test_public_side(self):
        status, _, _ = await self.get("/almanac", self.public_port)
        self.assertEqual(status, 200)
        status, _, _ = await self.get("/admin/almanac", self.public_port, "POST", "keeper-" + "x" * 16)
        self.assertEqual(status, 403)

    async def test_keeper(self):
        self.assertEqual((await self.get("/admin/almanac", method="POST"))[0], 403)
        status, _, body = await self.get("/admin/almanac", method="POST", token="keeper-" + "x" * 16)
        self.assertEqual(status, 200)
        self.assertEqual(set(json.loads(body)["sources"]), {"worlds", "updates", "names", "market"})
        self.assertEqual((await self.get("/almanac", method="POST"))[0], 405)


class RoutesOff(Routes):
    almanac = "off"

    async def test_read(self):
        status, _, body = await self.get("/almanac")
        got = json.loads(body)
        self.assertEqual((status, got["updates"], got["worlds"], got["market"]), (200, None, None, None))
        self.assertEqual(self.up.seen, [])

    async def test_keeper(self):
        status, _, _ = await self.get("/admin/almanac", method="POST", token="keeper-" + "x" * 16)
        self.assertEqual(status, 200)
        self.assertEqual(self.up.seen, [])


if __name__ == "__main__":
    unittest.main()
