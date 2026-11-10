"""The portal's protocol, tested from outside against either relay, so the two never drift apart.

    py tests/protocol_test.py                  # relay-py/relay.py, started on a spare port
    PORTAL_RELAY=worker py tests/protocol_test.py   # the Cloudflare Worker, under `wrangler dev` on a spare port
    PORTAL_RELAY=ws://host:port ADMIN_TOKEN=... py tests/protocol_test.py   # a relay already running

Only what a client can see is tested here. relay-py/test_relay.py keeps the Python relay's own tests
(its page, its public side, the delay, forwarding to Discord). Each test has a channel of its own.
Standard library only (relay-py/wsmini.py plays every client).
"""

import asyncio
import base64
import hashlib
import hmac
import http.server
import json
import os
import secrets
import shutil
import signal
import socket
import struct
import subprocess
import sys
import tempfile
import threading
import time
import unittest
import urllib.error
import urllib.parse
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, os.path.join(ROOT, "relay-py"))
from wsmini import Client  # noqa: E402

MODE = os.environ.get("PORTAL_RELAY", "python")
WORKER = MODE == "worker" or (MODE.startswith("ws") and bool(os.environ.get("ADMIN_TOKEN")))
ADMIN = os.environ.get("ADMIN_TOKEN") or "test-admin-" + secrets.token_hex(12)
# the oldest client the relays under test let in (a relay already running: whatever it was started with, given here)
ASKS_AN_HOUR = 12    # (the Worker's own is 3: the tests all ask from one address)
REPORTS_AN_HOUR = 8  # (the Worker's own is 5)
MIN_CLIENT = os.environ.get("MIN_CLIENT", "" if MODE.startswith("ws") else "0.5.0")
# the aether's keeper (11/07/2026): the Worker's ADMIN_TOKEN, and relay-py's --admin-token (given it here)
AETHER_KEEPER = WORKER or MODE == "python"
SAY_BURST = 20
SOUND_HOLD = 0.5
# how long a player's square (following, 11/08/2026) shows on GET /channels after it came: 240 s as the relays are run,
# shortened here so it can be seen to go stale (a relay already running: whatever it was started with, given here)
WHERE_FRESH_S = float(os.environ.get("WHERE_FRESH_SECONDS") or (240 if MODE.startswith("ws") else 4))
# how old a live row on the Worker's open list (GET /channels) may be before its Directory asks the channel again: 300 s
# as it's run, shortened here so the check can be seen to catch a telling that was lost (relay.py asks nothing)
LISTING_CHECK_S = 5
RELAY = None          # ws://127.0.0.1:port
PROC = None
STATE = None
UPSTREAM = None       # the almanac's sources, served on this computer (Upstream, below): never the real hosts


def free_port():
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    port = s.getsockname()[1]
    s.close()
    return port


def http_url():
    return "http" + RELAY[2:]


def wait_up(timeout):
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        try:
            urllib.request.urlopen(http_url() + "/", timeout=2).read()
            return True
        except urllib.error.HTTPError:
            return True
        except OSError:
            time.sleep(0.25)
    return False


FIXTURES = os.path.join(HERE, "fixtures", "almanac")
DAY_S = 1794009600   # the fixtures' /24h day (11/07/2026, UTC)


def fixture(name):
    with open(os.path.join(FIXTURES, name), "rb") as f:
        return f.read()


class Upstream(http.server.ThreadingHTTPServer):
    """The almanac's sources (11/09/2026), on this computer, from tests/fixtures/almanac: Jagex's news and world list
    and the Wiki's /24h (today's, or the next day's once `day` says so; the day before with ?timestamp=) and /mapping,
    at the real ones' paths. `broken` maps a path to a status (or b"garbage"); `seen` is every request, with its
    User-Agent. The relays fetch everything under it (relay.py's --almanac, the Worker's ALMANAC_UPSTREAM)."""
    daemon_threads = True

    def __init__(self):
        self.seen, self.broken, self.day = [], {}, "24h-latest.json"
        self.lock = threading.Lock()
        super().__init__(("127.0.0.1", 0), UpstreamHandler)
        threading.Thread(target=self.serve_forever, daemon=True).start()

    @property
    def base(self):
        return "http://127.0.0.1:%d" % self.server_address[1]

    def paths(self):
        with self.lock:
            return [p for p, _ in self.seen]


class UpstreamHandler(http.server.BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def do_GET(self):
        up = self.server
        with up.lock:
            up.seen.append((self.path, self.headers.get("User-Agent")))
            broken = up.broken.get(self.path.split("?")[0])
            files = {"/m=news/latest_news.rss?oldschool=true": "news.rss", "/slr.ws?order=LPWM": "slr.ws",
                     "/api/v2/osrs/mapping": "mapping.json", "/api/v2/osrs/24h": up.day,
                     "/api/v2/osrs/24h?timestamp=%d" % (DAY_S - 86400): "24h-before.json",
                     "/api/v2/osrs/24h?timestamp=%d" % DAY_S: "24h-latest.json"}
        name = files.get(self.path)
        if isinstance(broken, int) or not name:
            self.send_response(broken if isinstance(broken, int) else 404)
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        body = broken if isinstance(broken, bytes) else fixture(name)
        self.send_response(200)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


def setUpModule():
    global RELAY, PROC, STATE, UPSTREAM
    if MODE.startswith("ws"):
        RELAY = MODE.rstrip("/")
        return
    UPSTREAM = Upstream()
    port = free_port()
    RELAY = "ws://127.0.0.1:%d" % port
    if MODE == "worker":
        STATE = tempfile.mkdtemp(prefix="portal-wrangler-")
        npx = shutil.which("npx") or "npx"
        PROC = subprocess.Popen(
            [npx, "wrangler", "dev", "--port", str(port), "--ip", "127.0.0.1", "--persist-to", STATE,
             "--show-interactive-dev-session=false", "--var", "ADMIN_TOKEN:" + ADMIN,
             "--var", "POLL_MIN_SECONDS:1", "--var", "POLL_KEPT_SECONDS:1", "--var", "MIN_CLIENT:" + MIN_CLIENT, "--var", "ASKS_AN_HOUR:" + str(ASKS_AN_HOUR), "--var", "REPORTS_AN_HOUR:" + str(REPORTS_AN_HOUR),
             "--var", "WHERE_FRESH_SECONDS:%g" % WHERE_FRESH_S,
             "--var", "ALMANAC_UPSTREAM:" + UPSTREAM.base, "--var", "TEST_FORGET:1", "--test-scheduled",
             "--var", "LISTING_CHECK_SECONDS:%g" % LISTING_CHECK_S, "--var", "RECORD_CHUNKS:%d" % RECORD_CHUNKS],
            cwd=os.path.join(ROOT, "relay"), stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
            shell=sys.platform == "win32", start_new_session=sys.platform != "win32")
        up = wait_up(90)
    else:
        STATE = tempfile.mkdtemp(prefix="portal-clips-")
        PROC = subprocess.Popen([sys.executable, os.path.join(ROOT, "relay-py", "relay.py"), "--port", str(port),
                                 "--no-forward", "--min-client", MIN_CLIENT, "--clips", STATE, "--admin-token", ADMIN,
                                 "--where-fresh-seconds", "%g" % WHERE_FRESH_S,
                                 "--almanac", UPSTREAM.base, "--test-forget",
                                 "--records", os.path.join(STATE, "records"), "--record-chunks", str(RECORD_CHUNKS)],
                                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                                start_new_session=sys.platform != "win32")
        up = wait_up(15)
    if not up:
        tearDownModule()
        raise RuntimeError("the relay didn't start (%s)" % MODE)


def tearDownModule():
    if UPSTREAM is not None:
        UPSTREAM.shutdown()
        UPSTREAM.server_close()
    if PROC is not None:
        if sys.platform == "win32":
            subprocess.run(["taskkill", "/F", "/T", "/PID", str(PROC.pid)], capture_output=True)
        else:
            # (the whole group: npx, wrangler and its two workerd, which outlived a kill of npx alone and kept watching
            # relay/src, 11/07/2026)
            try:
                os.killpg(PROC.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
        PROC.wait()
    if STATE:
        shutil.rmtree(STATE, ignore_errors=True)


def admin(method, path):
    """(status, JSON or None) from the relay's admin route."""
    req = urllib.request.Request(http_url() + path, method=method, headers={"Authorization": "Bearer " + ADMIN})
    try:
        with urllib.request.urlopen(req, data=b"" if method == "POST" else None, timeout=10) as r:
            body = r.read()
            return r.status, json.loads(body) if body else None
    except urllib.error.HTTPError as e:
        return e.code, None


def admin_json(method, path, body):
    """(status, JSON or None) from the admin route, with a JSON body."""
    req = urllib.request.Request(http_url() + path, method=method, data=json.dumps(body).encode(),
                                 headers={"Authorization": "Bearer " + ADMIN, "Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=10) as r:
            raw = r.read()
            return r.status, json.loads(raw) if raw else None
    except urllib.error.HTTPError as e:
        return e.code, None


def http(method, path, body=None):
    """(status, JSON or None) from a public route, as a friend's client calls it."""
    data = json.dumps(body).encode() if body is not None else (b"" if method == "POST" else None)
    req = urllib.request.Request(http_url() + path, method=method, data=data,
                                 headers={"Content-Type": "application/json"} if body is not None else {})
    try:
        with urllib.request.urlopen(req, timeout=10) as r:
            raw = r.read()
            return r.status, json.loads(raw) if raw else None
    except urllib.error.HTTPError as e:
        raw = e.read()
        try:
            return e.code, json.loads(raw) if raw else None
        except ValueError:
            return e.code, None


CRLF = "\r\n"


async def post(path, body, content_type):
    """An HTTP POST as Dink makes one; the status code back."""
    host, port = RELAY.split("//")[1].split(":")
    r, w = await asyncio.open_connection(host, int(port))
    head = CRLF.join(["POST %s HTTP/1.1" % path, "Host: %s:%s" % (host, port), "Content-Type: %s" % content_type,
                      "Content-Length: %d" % len(body), "Connection: close", "", ""])
    w.write(head.encode() + body)
    await w.drain()
    status = int((await r.readuntil(CRLF.encode())).split(b" ")[1])
    w.close()
    return status


def multipart(payload, screenshot=b""):
    b = "dinkboundary"
    parts = [CRLF.join(["--" + b, 'Content-Disposition: form-data; name="payload_json"',
                        "Content-Type: application/json", "", json.dumps(payload), ""]).encode()]
    if screenshot:
        parts.append(CRLF.join(["--" + b, 'Content-Disposition: form-data; name="file"; filename="image.png"',
                                "Content-Type: image/png", "", ""]).encode() + screenshot + CRLF.encode())
    parts.append(("--%s--" % b + CRLF).encode())
    return b"".join(parts), "multipart/form-data; boundary=" + b


async def next_of(client, kind, timeout=2.0):
    """The next line of this kind ("chat", "poll", "relay"...), skipping the rest."""
    while True:
        k, payload = await client.recv(timeout)
        if k == "close":
            raise AssertionError("closed (%s) while waiting for %s" % (payload, kind))
        if k == "text":
            m = json.loads(payload)
            if kind in m:
                return m[kind]


async def last_status(c, settle=0.3):
    """The newest status line a client has been sent, after a moment for the rest to arrive."""
    st = None
    end = time.monotonic() + settle
    while True:
        try:
            kind, line = await c.recv(timeout=max(0.01, end - time.monotonic()))
        except asyncio.TimeoutError:
            return st
        if kind == "text" and line.startswith('{"relay"'):
            st = json.loads(line)["relay"]


async def nothing_binary(c, wait=0.4):
    """True if no binary message comes in `wait` seconds."""
    try:
        while True:
            k, _ = await c.recv(timeout=wait)
            if k == "binary":
                return False
    except asyncio.TimeoutError:
        return True


class Base(unittest.IsolatedAsyncioTestCase):
    settle = 0.15 if WORKER else 0.05

    async def asyncSetUp(self):
        self.clients = []
        self.ch = "t" + secrets.token_hex(5)
        if WORKER:
            status, got = await asyncio.to_thread(admin, "POST", "/admin/channels/" + self.ch)
            self.assertEqual(status, 200)
            self.key = got["key"]
        else:
            self.key = "secret-key-" + secrets.token_hex(4)   # (relay.py: the first transmitter claims it)

    async def asyncTearDown(self):
        for c in self.clients:
            try:
                c.close()
            except Exception:
                pass

    async def connect(self, role, query=""):
        c = await Client().open("%s/%s/%s%s" % (RELAY, role, self.ch, ("?" + query) if query else ""))
        self.clients.append(c)
        return c

    async def tx(self, code="", key=None):
        return await self.connect("transmit", "key=%s&code=%s" % (key or self.key, code))

    async def watch(self, code=""):
        return await self.connect("watch", "code=" + code)

    async def desk(self, name=""):
        return await self.connect("desk", "key=%s&name=%s" % (self.key, name or self.ch))

    async def pause(self, n=1):
        await asyncio.sleep(self.settle * n)


class Fanout(Base):
    async def test_a_frame_and_data_reach_every_viewer(self):
        tx = await self.tx("moss")
        a, b = await self.watch("moss"), await self.watch("moss")
        await self.pause()
        frame = struct.pack(">I", 1234) + b"\xff\xd8 fake jpeg"
        await tx.send('{"tick": 1234}')
        await tx.send(frame)
        for v in (a, b):
            self.assertEqual(await v.recv_data(), ("text", '{"tick": 1234}'))
            self.assertEqual(await v.recv_data(), ("binary", frame))

    async def test_a_late_viewer_gets_the_last_data_and_frame_at_once(self):
        tx = await self.tx()
        await tx.send('{"tick": 1}')
        await tx.send(b"\x00\x00\x00\x01frame one")
        await tx.send('{"tick": 2}')
        await self.pause(2)
        late = await self.watch()
        self.assertEqual(await late.recv_data(), ("text", '{"tick": 2}'))
        self.assertEqual(await late.recv_data(), ("binary", b"\x00\x00\x00\x01frame one"))

    async def test_what_a_viewer_sends_goes_nowhere(self):
        await self.tx()
        v1, v2 = await self.watch(), await self.watch()
        await self.pause()
        await v1.send('{"tick": 999, "spoof": true}')
        await v1.send(b"\x00\x00\x03\xe7spoofed frame")
        with self.assertRaises(asyncio.TimeoutError):
            await v2.recv_data(timeout=0.4)
        late = await self.watch()
        with self.assertRaises(asyncio.TimeoutError):
            await late.recv_data(timeout=0.4)

    async def test_viewers_hear_when_the_channel_goes_live_and_off_air(self):
        v = await self.watch()
        self.assertFalse((await next_of(v, "relay"))["live"])
        tx = await self.tx()
        while not (await next_of(v, "relay"))["live"]:
            pass
        tx.close()
        while (await next_of(v, "relay"))["live"]:
            pass

    async def test_off_air_a_newcomer_gets_no_old_frame(self):
        tx = await self.tx()
        await self.pause()
        await tx.send(struct.pack(">I", 1234) + b"\xff\xd8 fake jpeg")
        await self.pause(2)
        tx.close()
        await self.pause(2)
        v = await self.watch()
        self.assertTrue(await nothing_binary(v))

    async def test_the_status_says_whether_the_channel_is_set_up(self):
        nobody = await Client().open("%s/watch/%s" % (RELAY, "none" + secrets.token_hex(4)))
        self.clients.append(nobody)
        self.assertFalse((await next_of(nobody, "relay"))["set"])
        await self.tx()
        await self.pause()
        v = await self.watch()
        self.assertTrue((await next_of(v, "relay"))["set"])

    async def test_the_desks_title_reaches_every_viewer_and_newcomers(self):
        # the broadcast's title (11/06/2026): one line, at most 100 characters, in every status line
        await self.tx()
        desk = await self.desk()
        v = await self.watch()
        self.assertEqual((await next_of(v, "relay"))["title"], "")
        await desk.send(json.dumps({"title": "  zulrah\nuntil the pet  "}))
        st = await next_of(v, "relay")
        while st["title"] == "":
            st = await next_of(v, "relay")
        self.assertEqual(st["title"], "zulrah until the pet")
        late = await self.watch()
        self.assertEqual((await next_of(late, "relay"))["title"], "zulrah until the pet")
        await desk.send(json.dumps({"title": "x" * 300}))
        while len(st["title"]) < 100:
            st = await next_of(v, "relay")
        self.assertEqual(st["title"], "x" * 100)
        await desk.send(json.dumps({"title": 5}))      # (not words: thrown away)
        await desk.send(json.dumps({"title": ""}))
        while st["title"]:
            st = await next_of(v, "relay")
        self.assertEqual(st["title"], "")

    async def test_the_transmitter_hears_how_many_watch(self):
        tx = await self.tx()
        self.assertEqual((await next_of(tx, "relay"))["viewers"], 0)
        await self.watch()
        while (await next_of(tx, "relay"))["viewers"] != 1:
            pass


class Listed(Base):
    async def test_the_open_portals_are_listed_and_a_coded_one_never(self):
        tx = await self.tx("")
        await self.pause()
        def listed():
            with urllib.request.urlopen(http_url() + "/channels", timeout=10) as r:
                self.assertEqual(r.headers.get("Access-Control-Allow-Origin"), "*")
                return {c["channel"]: c for c in json.load(r)["channels"]}
        got = await asyncio.to_thread(listed)
        self.assertIn(self.ch, got)
        self.assertTrue(got[self.ch]["live"])
        self.assertEqual(got[self.ch]["title"], "")
        desk = await self.desk()
        await desk.send(json.dumps({"title": "barrows"}))
        await self.pause(2)
        self.assertEqual((await asyncio.to_thread(listed))[self.ch]["title"], "barrows")
        tx.close()
        await self.tx("a-code")
        await self.pause(2)
        self.assertNotIn(self.ch, await asyncio.to_thread(listed))

    async def test_the_list_follows_viewers_the_picture_and_going_off_air(self):
        # (11/07/2026: on the Worker the Directory keeps the list, each channel telling it as its row changes, where each
        # read had asked every channel)
        def row():
            with urllib.request.urlopen(http_url() + "/channels", timeout=10) as r:
                return {c["channel"]: c for c in json.load(r)["channels"]}[self.ch]
        tx = await self.tx("")
        await self.pause()
        got = await asyncio.to_thread(row)
        self.assertEqual((got["live"], got["open"], got["viewers"]), (True, True, 0))
        await self.watch()
        await self.watch()
        await self.pause(2)
        self.assertEqual((await asyncio.to_thread(row))["viewers"], 2)
        await tx.send('{"state": "static"}')
        await self.pause(2)
        got = await asyncio.to_thread(row)
        self.assertEqual((got["open"], got["static"]), (False, True))
        tx.close()
        await self.pause(2)
        got = await asyncio.to_thread(row)
        self.assertEqual((got["live"], got["static"], got["doing"]), (False, False, None))


class Keys(Base):
    async def test_the_wrong_key_cant_take_a_channel(self):
        await self.tx()
        thief = await self.tx(key="another-key-entirely")
        self.assertEqual(await thief.recv_close(), ("close", 4003))

    async def test_a_short_key_is_refused(self):
        c = await self.tx(key="short")
        self.assertEqual(await c.recv_close(), ("close", 4001))

    async def test_the_wrong_watch_code_is_refused(self):
        await self.tx("moss")
        await self.pause()
        v = await self.watch("lichen")
        self.assertEqual(await v.recv_close(), ("close", 4003))

    async def test_a_viewer_waiting_early_is_let_go_if_the_code_differs(self):
        v = await self.watch()
        await v.recv()
        await self.tx("moss")
        self.assertEqual(await v.recv_close(), ("close", 4003))

    async def test_the_same_key_replaces_the_old_transmitter(self):
        old = await self.tx()
        await self.pause()
        await self.tx()
        self.assertEqual(await old.recv_close(), ("close", 4000))

    async def test_a_bad_path_is_not_upgraded(self):
        c = await Client().open(RELAY + "/somewhere/else")
        self.clients.append(c)
        self.assertEqual(c.status, 400)

    async def test_the_desk_and_the_sound_need_the_key(self):
        await self.tx()
        for role in ("desk", "sound"):
            bad = await self.connect(role, "key=wrong-key-xx")
            self.assertEqual(await bad.recv_close(), ("close", 4003), role)

    async def test_anyone_may_watch_when_the_channel_has_no_code(self):
        await self.tx("")
        await self.pause()
        for v in (await self.watch("8XKJX173"), await self.watch()):
            self.assertTrue((await next_of(v, "relay"))["live"])


@unittest.skipUnless(MIN_CLIENT, "the relay under test lets every version in (MIN_CLIENT not given)")
class Versions(Base):
    """The client says its version (v=) on its transmit, desk and sound sockets; one older than the relay's
    MIN_CLIENT is turned away with 4026 and the version needed, so it can say where to get a newer one."""

    def older(self):
        major, minor, patch = (int(x) for x in MIN_CLIENT.split("."))
        return "%d.%d.%d" % ((major, minor - 1, 9) if minor else (major - 1, 99, 0)) if (major or minor) else "0.0.0"

    async def test_an_older_client_is_turned_away_on_every_socket_it_opens(self):
        for role, query in (("transmit", "key=%s&code=" % self.key), ("desk", "key=%s&name=x" % self.key),
                            ("sound", "key=%s" % self.key)):
            c = await self.connect(role, query + "&v=" + self.older())
            self.assertEqual(await c.recv_close(), ("close", 4026), role)
            self.assertIn(MIN_CLIENT, c.reason)

    async def test_the_oldest_allowed_and_newer_ones_are_let_in_by_number_not_by_letter(self):
        major, minor, _ = (int(x) for x in MIN_CLIENT.split("."))
        for v in (MIN_CLIENT, "%d.%d.10" % (major, minor), "%d.%d.0" % (major, minor + 10), MIN_CLIENT + "-rc.1"):
            tx = await self.connect("transmit", "key=%s&code=&v=%s" % (self.key, v))
            self.assertTrue((await next_of(tx, "relay"))["live"], v)
            tx.close()
            await self.pause(2)

    async def test_a_client_that_says_no_version_is_let_in(self):
        tx = await self.tx()          # (ghillie's Python portal says none)
        self.assertTrue((await next_of(tx, "relay"))["live"])

    async def test_viewers_need_no_version(self):
        await self.tx()
        v = await self.connect("watch", "code=&v=" + self.older())
        self.assertIn("live", await next_of(v, "relay"))

    @unittest.skipUnless(WORKER, "the wrong-key limit: the Worker only")
    async def test_turned_away_for_its_version_isnt_a_wrong_key(self):
        for _ in range(12):
            c = await self.connect("transmit", "key=%s&code=&v=%s" % (self.key, self.older()))
            self.assertEqual(await c.recv_close(), ("close", 4026))
        tx = await self.connect("transmit", "key=%s&code=&v=%s" % (self.key, MIN_CLIENT))
        self.assertTrue((await next_of(tx, "relay"))["live"])

    @unittest.skipUnless(WORKER, "the keeper's list: the Worker only")
    async def test_the_keepers_list_counts_hours_on_air_this_month(self):
        def seconds():
            return {c["channel"]: c for c in admin("GET", "/admin/channels")[1]}[self.ch]["live_s"]
        self.assertEqual(seconds(), 0)
        tx = await self.tx()
        await next_of(tx, "relay")
        await asyncio.sleep(1.6)
        tx.close()
        await asyncio.sleep(0.6)
        after_one = seconds()
        self.assertGreaterEqual(after_one, 1)
        await asyncio.sleep(1.2)
        self.assertEqual(seconds(), after_one)                    # off air: nothing more
        again = await self.tx()                                   # a second stretch adds to the first, while it goes on
        await next_of(again, "relay")
        await asyncio.sleep(1.6)
        self.assertGreaterEqual(seconds(), after_one + 1)

    @unittest.skipUnless(WORKER, "the keeper's list: the Worker only")
    async def test_the_keepers_list_says_which_version_last_transmitted(self):
        tx = await self.connect("transmit", "key=%s&code=&v=%s" % (self.key, MIN_CLIENT))
        await next_of(tx, "relay")
        rows = {c["channel"]: c for c in admin("GET", "/admin/channels")[1]}
        self.assertEqual(rows[self.ch]["client"], MIN_CLIENT)
        self.assertTrue(rows[self.ch]["live"])


class Dink(Base):
    async def test_a_level_up_reaches_viewers_as_a_report_and_a_late_one_gets_the_last(self):
        await self.tx()
        v = await self.watch()
        await self.pause()
        body, ct = multipart({"type": "LEVEL", "playerName": "x", "content": "",
                              "embeds": [{"description": "**x** has levelled **Attack** to **70** [wiki](https://x)"}]},
                             screenshot=b"\x89PNG fake")
        self.assertEqual(await post("/dink/%s?key=%s" % (self.ch, self.key), body, ct), 204)
        r = (await next_of(v, "report"))
        self.assertEqual((r["type"], r["text"]), ("LEVEL", "x has levelled Attack to 70 wiki"))
        for i in range(2):
            await post("/dink/%s?key=%s" % (self.ch, self.key),
                       json.dumps({"type": "LOOT", "content": "loot %d" % i}).encode(), "application/json")
        await self.pause()
        late = await self.watch()
        got = [(await next_of(late, "report"))["text"] for _ in range(3)]
        self.assertEqual(got, ["x has levelled Attack to 70 wiki", "loot 0", "loot 1"])

    async def test_the_wrong_key_is_refused_and_other_players_never_pass(self):
        await self.tx()
        v = await self.watch()
        await self.pause()
        body, ct = multipart({"type": "LEVEL", "content": "x"})
        self.assertEqual(await post("/dink/%s?key=another-key-xx" % self.ch, body, ct), 403)
        for kind in ("PLAYER_KILL", "TRADE", "CHAT", "GROUP_STORAGE"):
            body, ct = multipart({"type": kind, "content": "someone else's name"})
            self.assertEqual(await post("/dink/%s?key=%s" % (self.ch, self.key), body, ct), 204)
        body, ct = multipart({"type": "DEATH", "content": "killed by someone", "extra": {"isPvp": True}})
        await post("/dink/%s?key=%s" % (self.ch, self.key), body, ct)
        r = (await next_of(v, "report"))
        self.assertEqual((r["type"], r["text"]), ("DEATH", ""))
        with self.assertRaises(asyncio.TimeoutError):
            await v.recv_data(timeout=0.4)

    async def test_the_same_post_twice_is_one_event(self):
        # (11/05/2026: a Dink with this address listed twice posted every event twice)
        await self.tx()
        v = await self.watch()
        await self.pause()
        body = json.dumps({"type": "LEVEL", "content": "", "extra": {"levelledSkills": {"Cooking": 71}}}).encode()
        for _ in range(2):
            self.assertEqual(await post("/dink/%s?key=%s" % (self.ch, self.key), body, "application/json"), 204)
        self.assertEqual((await next_of(v, "report"))["type"], "LEVEL")
        with self.assertRaises(asyncio.TimeoutError):
            await v.recv_data(timeout=0.5)

    async def test_a_report_sent_by_the_transmitter_reaches_viewers_and_late_ones(self):
        # (the client takes Dink's posts itself and sends reports this way)
        tx = await self.tx()
        v = await self.watch()
        await self.pause()
        line = json.dumps({"report": {"type": "PET", "text": "a pet", "at": 1}})
        await tx.send(line)
        self.assertEqual(await v.recv_data(), ("text", line))
        await self.pause()
        late = await self.watch()
        self.assertEqual(await late.recv_data(), ("text", line))


class Full(Base):
    async def test_past_the_cap_a_viewer_waits_in_line_with_no_frames(self):
        tx = await self.tx()
        d = await self.desk()
        await d.send(json.dumps({"cap": 2}))
        await self.pause()
        a, b = await self.watch(), await self.watch()
        await self.pause()
        third = await self.watch()
        st = await last_status(third)
        self.assertEqual((st["seat"], st["place"], st["viewers"], st["max"], st["live"]), (False, 1, 2, 2, True))
        sa = await last_status(a)
        self.assertNotIn("seat", sa)
        self.assertEqual((sa["viewers"], sa["max"], sa["waiting"]), (2, 2, 1))
        await tx.send(b"\x00\x00\x00\x01frame")
        self.assertEqual(await a.recv_data(), ("binary", b"\x00\x00\x00\x01frame"))
        self.assertTrue(await nothing_binary(third))

    async def test_a_seat_freed_goes_to_the_first_in_line_with_the_last_frame(self):
        tx = await self.tx()
        d = await self.desk()
        await d.send(json.dumps({"cap": 1}))
        await self.pause()
        a = await self.watch()
        await self.pause()
        second = await self.watch()
        await self.pause()
        third = await self.watch()
        self.assertEqual((await last_status(third))["place"], 2)
        await tx.send(b"\x00\x00\x00\x02frame two")
        await self.pause()
        a.close()
        self.assertEqual(await second.recv_data(), ("binary", b"\x00\x00\x00\x02frame two"))
        self.assertEqual((await last_status(third))["place"], 1)

    async def test_the_line_moves_up_as_people_in_it_and_ahead_of_it_go(self):
        # (11/07/2026: the Worker keeps who's here between messages; the places must still follow each coming and going)
        await self.tx()
        d = await self.desk()
        await d.send(json.dumps({"cap": 1}))
        await self.pause()
        seated = await self.watch()
        await self.pause()
        line = []
        for _ in range(4):
            line.append(await self.watch())
            await self.pause()
        self.assertEqual([(await last_status(c))["place"] for c in line], [1, 2, 3, 4])
        line[1].close()
        await self.pause()
        self.assertEqual([(await last_status(c))["place"] for c in (line[0], line[2], line[3])], [1, 2, 3])
        seated.close()
        await self.pause()
        st = await last_status(line[0])
        self.assertNotIn("seat", st)
        self.assertEqual((st["viewers"], st["waiting"]), (1, 2))
        self.assertEqual([(await last_status(c))["place"] for c in (line[2], line[3])], [1, 2])

    async def test_a_raised_cap_seats_those_waiting(self):
        await self.tx()
        d = await self.desk()
        await d.send(json.dumps({"cap": 1}))
        await self.pause()
        await self.watch()
        await self.pause()
        waiting = await self.watch()
        self.assertFalse((await last_status(waiting))["seat"])
        await d.send(json.dumps({"cap": 3}))
        st = await last_status(waiting)
        self.assertNotIn("seat", st)
        self.assertEqual((st["viewers"], st["max"]), (2, 3))


class Talk(Base):
    async def setup_room(self):
        tx = await self.tx()
        desk = await self.desk()
        a, b = await self.watch(), await self.watch()
        await a.send(json.dumps({"hello": {"name": "moss", "device": "device-aaaa"}}))
        await b.send(json.dumps({"hello": {"name": "fern", "device": "device-bbbb"}}))
        await self.pause()
        return tx, desk, a, b

    async def test_a_viewers_line_reaches_the_desk_and_every_viewer(self):
        tx, desk, a, b = await self.setup_room()
        await a.send(json.dumps({"say": "  hello\n there  "}))
        for c in (desk, a, b):
            line = await next_of(c, "chat")
            self.assertEqual((line["name"], line["text"], line["host"], line["queued"]),
                             ("moss", "hello there", False, False))

    async def test_his_lines_are_marked_as_his_and_nobody_can_take_his_name(self):
        tx, desk, a, b = await self.setup_room()
        await desk.send(json.dumps({"say": "try the north path"}))
        line = await next_of(b, "chat")
        self.assertEqual((line["name"], line["host"]), (self.ch, True))
        await a.send(json.dumps({"hello": {"name": self.ch.upper()}}))
        await a.send(json.dumps({"say": "it's me"}))
        line = await next_of(b, "chat")
        self.assertEqual((line["name"], line["host"]), ("visitor", False))

    async def test_a_late_viewer_gets_the_chat_so_far(self):
        tx, desk, a, b = await self.setup_room()
        await a.send(json.dumps({"say": "one"}))
        await next_of(b, "chat")
        await desk.send(json.dumps({"say": "two"}))
        await next_of(b, "chat")
        late = await self.watch()
        self.assertEqual([(await next_of(late, "chat"))["text"] for _ in range(2)], ["one", "two"])

    async def test_a_flood_is_cut_off_and_nothing_long_gets_through(self):
        tx, desk, a, b = await self.setup_room()
        for i in range(SAY_BURST + 4):
            await a.send(json.dumps({"say": "line %d" % i}))
        await a.send(json.dumps({"say": "x" * 5000}))
        got = []
        try:
            while True:
                got.append((await next_of(desk, "chat", timeout=0.6))["text"])
        except asyncio.TimeoutError:
            pass
        self.assertEqual(got, ["line %d" % i for i in range(SAY_BURST)])

    async def test_hide_takes_a_line_back_and_block_silences_a_viewer(self):
        tx, desk, a, b = await self.setup_room()
        await a.send(json.dumps({"say": "rude"}))
        line = await next_of(desk, "chat")
        await desk.send(json.dumps({"hide": line["id"]}))
        self.assertEqual(await next_of(b, "unsay"), line["id"])
        await desk.send(json.dumps({"block": line["who"]}))
        await self.pause()
        await a.send(json.dumps({"say": "again"}))
        await a.send(json.dumps({"ping": {"x": 0.5, "y": 0.5}}))
        await b.send(json.dumps({"say": "fine"}))
        self.assertEqual((await next_of(desk, "chat"))["text"], "fine")
        late = await self.watch()
        texts = []
        try:
            while True:
                texts.append((await next_of(late, "chat", timeout=0.4))["text"])
        except asyncio.TimeoutError:
            pass
        self.assertEqual(texts, ["fine"])

    async def test_a_poll_counts_one_vote_a_device_and_closes(self):
        tx, desk, a, b = await self.setup_room()
        await desk.send(json.dumps({"poll": {"question": "next?", "options": ["vorkath", "zulrah", ""], "seconds": 60}}))
        p = await next_of(a, "poll")
        self.assertEqual((p["options"], p["counts"], p["open"]), (["vorkath", "zulrah"], [0, 0], True))
        await a.send(json.dumps({"vote": {"poll": p["id"], "pick": 0}}))
        await a.send(json.dumps({"vote": {"poll": p["id"], "pick": 1}}))
        await b.send(json.dumps({"vote": {"poll": p["id"], "pick": 1}}))
        await b.send(json.dumps({"vote": {"poll": p["id"], "pick": 7}}))
        counts = None
        while counts != [0, 2]:
            counts = (await next_of(desk, "poll"))["counts"]
        await desk.send(json.dumps({"poll_close": True}))
        while (await next_of(b, "poll"))["open"]:
            pass
        await a.send(json.dumps({"vote": {"poll": p["id"], "pick": 0}}))
        await self.pause()
        late = await self.watch()
        p = await next_of(late, "poll")
        self.assertEqual((p["counts"], p["open"]), ([0, 2], False))

    @unittest.skipUnless(WORKER, "relay.py's poll takes 10 s at least: its own tests cover it")
    async def test_a_poll_ends_by_itself_and_goes_after_a_while(self):
        # (the Worker under test is started with POLL_MIN_SECONDS and POLL_KEPT_SECONDS at 1)
        tx, desk, a, b = await self.setup_room()
        await desk.send(json.dumps({"poll": {"question": "", "options": ["a", "b"], "seconds": 1}}))
        pid = (await next_of(a, "poll"))["id"]
        while (await next_of(a, "poll", timeout=4))["open"]:
            pass
        self.assertEqual(await next_of(b, "poll_gone", timeout=4), pid)
        late = await self.watch()
        with self.assertRaises(asyncio.TimeoutError):
            await next_of(late, "poll", timeout=0.4)

    async def test_a_ping_reaches_everyone_but_not_too_often_or_off_the_frame(self):
        tx, desk, a, b = await self.setup_room()
        await a.send(json.dumps({"ping": {"x": 0.25, "y": 0.75}}))
        await a.send(json.dumps({"ping": {"x": 0.3, "y": 0.3}}))
        await b.send(json.dumps({"ping": {"x": 1.5, "y": 0.3}}))
        p = await next_of(desk, "ping")
        self.assertEqual((p["x"], p["y"], p["name"]), (0.25, 0.75, "moss"))
        self.assertEqual((await next_of(b, "ping"))["x"], 0.25)
        with self.assertRaises(asyncio.TimeoutError):
            await next_of(desk, "ping", timeout=0.3)

    async def test_someone_in_line_for_a_spot_can_still_talk(self):
        tx = await self.tx()
        desk = await self.desk()
        await desk.send(json.dumps({"cap": 1}))
        await self.pause()
        seated = await self.watch()
        await desk.send(json.dumps({"say": "before"}))
        await next_of(seated, "chat")
        waiting = await self.watch()
        self.assertEqual((await next_of(waiting, "chat"))["text"], "before")
        st = await last_status(waiting)
        self.assertEqual(st["seat"], False)
        self.assertTrue(st["you"])
        await waiting.send(json.dumps({"hello": {"name": "fern", "device": "device-wait-1"}}))
        await waiting.send(json.dumps({"say": "let me in"}))
        line = await next_of(desk, "chat")
        while line["text"] != "let me in":
            line = await next_of(desk, "chat")
        self.assertEqual((line["name"], line["who"], line["queued"]), ("fern", st["you"], True))
        self.assertEqual((await next_of(seated, "chat"))["text"], "let me in")
        await tx.send(b"\x00\x00\x00\x09frame")
        self.assertTrue(await nothing_binary(waiting))

    async def test_the_desk_hears_what_the_portal_sends_each_second(self):
        tx, desk, a, b = await self.setup_room()
        frame = struct.pack(">I", 5) + b"\xff\xd8" + b"x" * 10000
        for _ in range(4):
            await tx.send(frame)
        up = await next_of(desk, "upload", timeout=3)
        while up["bps"] == 0:
            up = await next_of(desk, "upload", timeout=3)
        self.assertGreater(up["bps"], 200000)
        self.assertEqual(up["viewers"], 2)

    async def test_the_desk_hears_what_viewers_say_they_have(self):
        tx, desk, a, b = await self.setup_room()
        frame = struct.pack(">I", 5) + b"\xff\xd8" + b"x" * 10000
        for _ in range(4):
            await tx.send(frame)
        await a.send(json.dumps({"got": 4 * len(frame)}))
        up = await next_of(desk, "upload", timeout=3)
        while not up.get("acked"):
            up = await next_of(desk, "upload", timeout=3)
        self.assertGreater(up["acked"], 100000)

    async def test_the_desk_hears_when_the_picture_is_paused_and_back(self):
        tx, desk, a, b = await self.setup_room()
        await tx.send('{"state": "shut"}')
        r = await next_of(desk, "relay")
        while r["open"]:
            r = await next_of(desk, "relay")
        self.assertTrue(r["live"])
        await tx.send('{"state": "open"}')
        while not (await next_of(desk, "relay"))["open"]:
            pass


class Moderation(Base):
    """A ban, the banned list, and pings switched off (11/05/2026, Jace: "allow for banning of viewers, hiding of
    messages, and the ability to toggle viewer pings in the client")."""

    async def room(self):
        tx = await self.tx()
        desk = await self.desk()
        a, b = await self.watch(), await self.watch()
        await a.send(json.dumps({"hello": {"name": "moss", "device": "device-mod-aaaa"}}))
        await b.send(json.dumps({"hello": {"name": "fern", "device": "device-mod-bbbb"}}))
        await self.pause()
        return tx, desk, a, b

    async def test_a_ban_takes_back_every_line_and_the_desk_hears_the_list(self):
        tx, desk, a, b = await self.room()
        self.assertEqual(await next_of(desk, "banned"), [])
        await a.send(json.dumps({"say": "one"}))
        await a.send(json.dumps({"say": "two"}))
        await b.send(json.dumps({"say": "fine"}))
        lines = [await next_of(desk, "chat") for _ in range(3)]
        mine = [l for l in lines if l["name"] == "moss"]          # (two viewers' lines may arrive in either order)
        who = mine[0]["who"]
        await desk.send(json.dumps({"ban": who}))
        gone = {await next_of(b, "unsay"), await next_of(b, "unsay")}
        self.assertEqual(gone, {l["id"] for l in mine})
        self.assertEqual(await next_of(desk, "banned"), [{"who": who, "name": "moss"}])
        await a.send(json.dumps({"say": "three"}))
        await b.send(json.dumps({"say": "still fine"}))
        self.assertEqual((await next_of(desk, "chat"))["text"], "still fine")
        late = await self.watch()
        texts = []
        try:
            while True:
                texts.append((await next_of(late, "chat", timeout=0.4))["text"])
        except asyncio.TimeoutError:
            pass
        self.assertEqual(texts, ["fine", "still fine"])
        await desk.send(json.dumps({"unban": who}))
        self.assertEqual(await next_of(desk, "banned"), [])
        await a.send(json.dumps({"say": "back"}))
        self.assertEqual((await next_of(desk, "chat"))["text"], "back")

    async def test_pings_switched_off_go_nowhere_and_viewers_hear_it(self):
        tx, desk, a, b = await self.room()
        await desk.send(json.dumps({"pings": False}))
        st = await next_of(a, "relay")
        while st["pings"]:
            st = await next_of(a, "relay")
        await a.send(json.dumps({"ping": {"x": 0.5, "y": 0.5}}))
        with self.assertRaises(asyncio.TimeoutError):
            await next_of(desk, "ping", timeout=0.5)
        await desk.send(json.dumps({"pings": True}))
        while not (await next_of(a, "relay"))["pings"]:
            pass
        await a.send(json.dumps({"ping": {"x": 0.5, "y": 0.5}}))
        self.assertEqual((await next_of(desk, "ping"))["name"], "moss")


    async def test_the_chat_switched_off_takes_no_viewer_lines_but_the_hosts(self):
        tx, desk, a, b = await self.room()
        await desk.send(json.dumps({"chat_on": False}))
        st = await next_of(a, "relay")
        while st["chat"]:
            st = await next_of(a, "relay")
        await a.send(json.dumps({"say": "anyone there"}))
        with self.assertRaises(asyncio.TimeoutError):
            await next_of(desk, "chat", timeout=0.5)
        await desk.send(json.dumps({"say": "the chat's off for a bit"}))
        self.assertTrue((await next_of(a, "chat"))["host"])
        await desk.send(json.dumps({"chat_on": True}))
        while not (await next_of(a, "relay"))["chat"]:
            pass
        await a.send(json.dumps({"say": "back again"}))
        line = await next_of(desk, "chat")
        while line.get("host"):
            line = await next_of(desk, "chat")
        self.assertEqual(line["text"], "back again")

    async def test_the_desk_hears_who_is_watching_by_name(self):
        tx, desk, a, b = await self.room()
        names = []
        for _ in range(20):
            v = await next_of(desk, "viewers")
            names = sorted(x["name"] for x in v)
            if names == ["fern", "moss"]:
                break
        self.assertEqual(names, ["fern", "moss"])
        self.assertTrue(all(set(x) == {"who", "name", "waiting"} for x in v))   # (never a device)
        b.close()
        for _ in range(20):
            v = await next_of(desk, "viewers")
            if [x["name"] for x in v] == ["moss"]:
                break
        self.assertEqual([x["name"] for x in v], ["moss"])


class RoundTwo(Base):
    """main's round two (11/05/2026): lookers, the signal button, drop guesses, the static key."""

    async def room(self):
        tx = await self.tx()
        desk = await self.desk()
        a, b = await self.watch(), await self.watch()
        await a.send(json.dumps({"hello": {"name": "moss", "device": "device-two-aaaa"}}))
        await b.send(json.dumps({"hello": {"name": "fern", "device": "device-two-bbbb"}}))
        await self.pause()
        return tx, desk, a, b

    async def test_a_looker_takes_no_seat_and_gets_few_frames_only_when_it_asks(self):
        tx = await self.tx()
        d = await self.desk()
        await d.send(json.dumps({"cap": 1}))
        await self.pause()
        look = await self.connect("watch", "look=1")
        seated = await self.watch()
        st = await last_status(seated)
        self.assertNotIn("seat", st)
        self.assertEqual(st["viewers"], 1)
        frame = struct.pack(">I", 1) + b"\xff\xd8 frame"
        await tx.send(frame)
        with self.assertRaises(asyncio.TimeoutError):
            await look.recv_data(timeout=0.3)
        await look.send(json.dumps({"look": {"frames": True}}))
        self.assertEqual(await look.recv_data(), ("binary", frame))
        for _ in range(6):
            await tx.send(frame)
            await asyncio.sleep(0.05)
        got = 0
        try:
            while True:
                k, _ = await look.recv_data(timeout=0.3)
                got += k == "binary"
        except asyncio.TimeoutError:
            pass
        self.assertLessEqual(got, 1)

    async def test_small_frames_go_to_lookers_only_and_take_the_full_ones_place(self):
        tx = await self.tx()
        look = await self.connect("watch", "look=1")
        v = await self.watch()
        await self.pause()
        await look.send(json.dumps({"look": {"frames": True}}))
        await self.pause()
        thumb = struct.pack(">I", 7) + b"TH" + b"\xff\xd8 small"
        full = struct.pack(">I", 7) + b"\xff\xd8 full"
        await tx.send(thumb)
        await tx.send(full)
        self.assertEqual(await v.recv_data(), ("binary", full))       # a viewer: the full one only
        self.assertEqual(await look.recv_data(), ("binary", thumb))   # a looker: the small one only
        await asyncio.sleep(0.6)
        await tx.send(full)                                           # (small ones recent: no full ones to lookers)
        with self.assertRaises(asyncio.TimeoutError):
            await look.recv_data(timeout=0.4)
        late = await self.watch()
        self.assertEqual(await late.recv_data(), ("binary", full))    # a newcomer's last frame is never a small one

    async def test_a_looker_keeps_getting_small_frames_past_its_first_4_mb(self):
        # (11/07/2026: a looker never says {"got"}, and on the Worker its frames stopped for good after the first 4 MB:
        # the outstations page's tiles, the listening post and the client's live view froze after a quarter of an hour)
        tx = await self.tx()
        look = await self.connect("watch", "look=1")
        await self.pause()
        await look.send(json.dumps({"look": {"frames": True}}))
        await self.pause()
        thumb = struct.pack(">I", 9) + b"TH\xff\xd8" + b"s" * (300 * 1024)
        got = 0
        for _ in range(16):                          # 4.8 MB, each one a looker may have (LOOK_EVERY apart)
            await tx.send(thumb)
            end = time.monotonic() + 0.6
            while True:
                try:
                    k, _ = await look.recv_data(timeout=max(0.01, end - time.monotonic()))
                except asyncio.TimeoutError:
                    break
                got += k == "binary"
        self.assertEqual(got, 16)

    async def test_a_signal_reaches_everyone_as_often_as_a_hand_presses_but_no_flood(self):
        tx, desk, a, b = await self.room()
        await a.send(json.dumps({"signal": True}))
        await a.send(json.dumps({"signal": True}))
        self.assertEqual((await next_of(b, "signal"))["name"], "moss")
        self.assertEqual((await next_of(desk, "signal"))["name"], "moss")
        with self.assertRaises(asyncio.TimeoutError):
            await next_of(desk, "signal", timeout=0.4)

    async def test_a_drop_guess_round_kept_by_dink_and_decided_by_a_drop(self):
        tx, desk, a, b = await self.room()

        async def dink(payload):
            self.assertEqual(await post("/dink/%s?key=%s" % (self.ch, self.key), json.dumps(payload).encode(),
                                        "application/json"), 204)
        await dink({"type": "KILL_COUNT", "content": "", "extra": {"boss": "Vorkath", "count": 40}})
        await desk.send(json.dumps({"guess_open": {"boss": "vorkath", "what": "a unique"}}))
        g = await next_of(a, "guess")
        self.assertEqual((g["boss"], g["kc"], g["open"], g["count"]), ("vorkath", 40, True, 0))
        await a.send(json.dumps({"guess": {"id": g["id"], "kc": 45}}))
        await b.send(json.dumps({"guess": {"id": g["id"], "kc": 52}}))
        await b.send(json.dumps({"guess": {"id": g["id"], "kc": 39}}))
        while (await next_of(desk, "guess"))["count"] != 2:
            pass
        await dink({"type": "KILL_COUNT", "content": "", "extra": {"boss": "Vorkath", "count": 47}})
        await dink({"type": "LOOT", "content": "", "extra": {"source": "Vorkath", "items": [
            {"name": "Vorki", "quantity": 1, "priceEach": 0}, {"name": "Draconic visage", "quantity": 1, "priceEach": 2500000}]}})
        while True:
            g = await next_of(b, "guess")
            if g["result"]:
                break
        self.assertEqual((g["result"]["kc"], g["result"]["item"], g["result"]["winners"], g["result"]["off"]),
                         (47, "Draconic visage", ["moss"], 2))
        self.assertIn("moss guessed closest", (await next_of(b, "chat"))["text"])

    async def test_static_keeps_the_sound_going_and_says_so(self):
        tx = await self.tx()
        snd = await self.connect("sound", "key=" + self.key)
        desk = await self.desk()
        v = await self.watch()
        await tx.send('{"state": "static"}')
        st = await next_of(desk, "relay")
        while not st.get("static"):
            st = await next_of(desk, "relay")
        self.assertFalse(st["open"])
        packet = struct.pack(">I", 99) + b"AU" + bytes([1, 1]) + struct.pack(">H", 3) + b"opu"
        await snd.send(packet)
        while True:
            k, payload = await v.recv(timeout=SOUND_HOLD + 1)
            if k == "binary":
                break
        self.assertEqual(payload, packet)


class ReportData(Base):
    """The numbers a report carries, for the portal's readings, and nothing naming another player."""

    async def dink(self, payload):
        self.assertEqual(await post("/dink/%s?key=%s" % (self.ch, self.key), json.dumps(payload).encode(),
                                    "application/json"), 204)

    async def test_a_kill_count_carries_the_boss_and_count_and_never_the_party(self):
        await self.tx()
        v = await self.watch()
        await self.pause()
        await self.dink({"type": "KILL_COUNT", "content": "kc", "extra": {"boss": "Vorkath", "count": 1240,
                         "isPersonalBest": False, "party": ["someone else"]}})
        r = (await next_of(v, "report"))
        self.assertEqual(r["data"], {"boss": "Vorkath", "count": 1240, "pb": False})

    async def test_levels_the_log_and_loot(self):
        await self.tx()
        v = await self.watch()
        await self.pause()
        await self.dink({"type": "LEVEL", "content": "", "extra": {"levelledSkills": {"Cooking": 70},
                         "allSkills": {"Cooking": 70, "Attack": 99}, "combatLevel": {"value": 90, "increased": False}}})
        await self.dink({"type": "COLLECTION", "content": "", "extra": {"itemName": "Draconic visage", "itemId": 11286,
                         "completedEntries": 812, "totalEntries": 1568, "dropperName": "someone"}})
        await self.dink({"type": "LOOT", "content": "loot", "extra": {"source": "Vorkath", "category": "NPC",
                         "items": [{"id": 536, "name": "Dragon bones", "quantity": 2, "priceEach": 3000}]}})
        got = [(await next_of(v, "report"))["data"] for _ in range(3)]
        self.assertEqual(got[0], {"skills": {"Cooking": 70}, "total": 169, "combat": 90})
        self.assertEqual(got[1], {"item": "Draconic visage", "id": 11286, "done": 812, "total": 1568})
        self.assertEqual(got[2], {"items": [{"name": "Dragon bones", "quantity": 2, "price": 3000, "id": 536}], "value": 6000,
                                  "source": "Vorkath"})

    async def test_a_player_kills_loot_names_nobody(self):
        await self.tx()
        v = await self.watch()
        await self.pause()
        await self.dink({"type": "LOOT", "content": "x has looted from SomePlayer", "extra": {"source": "SomePlayer",
                         "category": "PLAYER", "items": [{"name": "Coins", "quantity": 5000, "priceEach": 1}]}})
        r = (await next_of(v, "report"))
        self.assertEqual(r["text"], "")
        self.assertNotIn("source", r["data"])
        self.assertEqual(r["data"]["value"], 5000)


class Summary(ReportData):
    """One summary per stream (11/06/2026): what the stream's done, from Dink's numbers, the same for every viewer
    and late joiner; kept off air as the recap (ended), carried on by a quick return; the open portals say what
    each live one is at (doing)."""

    async def last_summary(self, v, wait=1.5):
        got, end = None, time.monotonic() + wait
        while time.monotonic() < end:
            try:
                k, line = await v.recv(timeout=max(0.05, end - time.monotonic()))
            except asyncio.TimeoutError:
                break
            if k == "text" and line.startswith('{"summary"'):
                got = json.loads(line)["summary"]
        return got

    async def test_a_stream_sums_up_its_kills_loot_levels_and_log_for_everyone(self):
        tx = await self.tx()
        v = await self.watch()
        await self.pause()
        await self.dink({"type": "KILL_COUNT", "content": "", "extra": {"boss": "Vorkath", "count": 412}})
        await self.dink({"type": "KILL_COUNT", "content": "", "extra": {"boss": "Vorkath", "count": 414}})
        await self.dink({"type": "LOOT", "content": "", "extra": {"source": "Vorkath", "category": "NPC",
                         "items": [{"id": 536, "name": "Dragon bones", "quantity": 2, "priceEach": 3000},
                                   {"id": 21907, "name": "Vorkath's head", "quantity": 1, "priceEach": 90000}]}})
        await self.dink({"type": "LEVEL", "content": "", "extra": {"levelledSkills": {"Ranged": 92}}})
        await self.dink({"type": "PET", "content": "", "extra": {"petName": "Vorki"}})
        sm = await self.last_summary(v)
        self.assertEqual(sm["kc"]["vorkath"], {"name": "Vorkath", "first": 411, "last": 414})
        self.assertEqual((sm["boss"], sm["loot"], sm["levels"], sm["pets"]), ("Vorkath", 96000, {"Ranged": 92}, ["Vorki"]))
        self.assertEqual(sm["best"], {"name": "Vorkath's head", "value": 90000, "id": 21907})
        self.assertNotIn("ended", sm)
        late = await self.watch()
        self.assertEqual((await self.last_summary(late))["loot"], 96000)     # (a late joiner: the same numbers)
        rows = {c["channel"]: c for c in json.loads(urllib.request.urlopen(http_url() + "/channels", timeout=10).read())["channels"]}
        if self.ch in rows:
            self.assertEqual(rows[self.ch]["doing"], {"boss": "Vorkath", "kc": 414})

    async def test_off_air_it_stays_as_the_recap_and_a_quick_return_carries_on(self):
        tx = await self.tx()
        v = await self.watch()
        await self.pause()
        await self.dink({"type": "KILL_COUNT", "content": "", "extra": {"boss": "Zulrah", "count": 90}})
        since = (await self.last_summary(v))["since"]
        tx.close()
        ended = await self.last_summary(v)
        self.assertEqual((ended["since"], ended["kc"]["zulrah"]["last"]), (since, 90))
        self.assertIn("ended", ended)
        again = await self.tx()
        back = await self.last_summary(v)
        self.assertEqual(back["since"], since)                              # (back within 15 minutes: the same stream)
        self.assertNotIn("ended", back)


def array_read(query=""):
    """(status, headers, JSON or None) of GET /array/events, as outside.wiki's front page reads it."""
    try:
        with urllib.request.urlopen(http_url() + "/array/events" + query, timeout=10) as r:
            return r.status, r.headers, json.loads(r.read())
    except urllib.error.HTTPError as e:
        raw = e.read()
        try:
            return e.code, e.headers, json.loads(raw) if raw else None
        except ValueError:
            return e.code, e.headers, None


def array_since(since):
    """Every event after the cursor, page by page."""
    out = []
    while True:
        status, _, body = array_read("?since=%d" % since)
        if status != 200:
            raise AssertionError("GET /array/events: %s" % status)
        if not body["events"]:
            return out
        out += body["events"]
        since = body["next"]


SKILLS = ["Attack", "Strength", "Defence", "Ranged", "Prayer", "Magic", "Runecraft", "Hitpoints", "Crafting", "Mining",
          "Smithing", "Fishing"]


class ArrayEvents(Base):
    """The Array's events (11/08/2026): what's happening across the open outstations, for outside.wiki's front page (an
    outstation's icon pops "+1"). Only a live outstation on GET /channels (no watch code) makes them; one channel adds
    10 a minute at most."""

    async def asyncSetUp(self):
        await super().asyncSetUp()
        status, _, body = await asyncio.to_thread(array_read)
        self.assertEqual(status, 200)
        self.since = body["next"]                                           # (what came before this test: left out)

    async def dink(self, payload, ch=None, key=None):
        self.assertEqual(await post("/dink/%s?key=%s" % (ch or self.ch, key or self.key), json.dumps(payload).encode(),
                                    "application/json"), 204)

    async def mine(self, n=0, wait=3.0, ch=None):
        """This channel's events since the test began, oldest first: as soon as n have come, or after `wait` seconds."""
        end = time.monotonic() + wait
        while True:
            got = [e for e in await asyncio.to_thread(array_since, self.since) if e["channel"] == (ch or self.ch)]
            if (n and len(got) >= n) or time.monotonic() >= end:
                return got
            await asyncio.sleep(0.05)

    async def none_of_mine(self):
        self.assertEqual(await self.mine(wait=self.settle * 5), [])

    @staticmethod
    def what(events):
        return [{k: v for k, v in e.items() if k not in ("id", "at", "channel")} for e in events]

    async def other(self):
        """Another channel of this test's, and its key (handed out on the Worker; claimed by its transmitter here)."""
        ch = "t" + secrets.token_hex(5)
        if WORKER:
            status, got = await asyncio.to_thread(admin, "POST", "/admin/channels/" + ch)
            self.assertEqual(status, 200)
            return ch, got["key"]
        return ch, "secret-key-" + secrets.token_hex(4)

    async def test_a_live_open_outstations_reports_become_events(self):
        await self.tx()
        await self.pause()
        began = time.time() * 1000
        await self.dink({"type": "KILL_COUNT", "content": "", "extra": {"boss": "Vorkath", "count": 412,
                         "isPersonalBest": True, "party": ["someone else"]}})
        await self.dink({"type": "LEVEL", "content": "", "extra": {"levelledSkills": {"Ranged": 92, "Hitpoints": 90},
                         "allSkills": {"Ranged": 92, "Hitpoints": 90}}})
        await self.dink({"type": "PET", "content": "", "extra": {"petName": "Vorki"}})
        await self.dink({"type": "LOOT", "content": "", "extra": {"source": "Vorkath", "category": "NPC", "items": [
            {"id": 536, "name": "Dragon bones", "quantity": 2, "priceEach": 3000},
            {"id": 22006, "name": "Skeletal visage", "quantity": 1, "priceEach": 1200000}]}})         # (1,206,000: in)
        await self.dink({"type": "LOOT", "content": "", "extra": {"source": "Vorkath", "category": "NPC", "items": [
            {"id": 536, "name": "Dragon bones", "quantity": 300, "priceEach": 3333}]}})              # (999,900: under)
        await self.dink({"type": "COLLECTION", "content": "", "extra": {"itemName": "Draconic visage", "itemId": 11286,
                         "completedEntries": 812, "totalEntries": 1568, "dropperName": "someone"}})
        await self.dink({"type": "CLUE", "content": "", "extra": {"clueType": "Hard", "numberCompleted": 150}})
        await self.dink({"type": "PET", "content": "", "extra": {}})                                  # (no name given)
        await self.dink({"type": "QUEST", "content": "", "extra": {"questName": "Dragon Slayer II"}})   # (not one of them)
        got = await self.mine(8)
        self.assertEqual(self.what(got), [
            {"kind": "kill", "boss": "Vorkath", "kc": 412},
            {"kind": "level", "skill": "Ranged", "level": 92},
            {"kind": "level", "skill": "Hitpoints", "level": 90},
            {"kind": "pet", "name": "Vorki"},
            {"kind": "loot", "name": "Skeletal visage", "value": 1206000},
            {"kind": "clog", "name": "Draconic visage"},
            {"kind": "clue", "tier": "Hard", "count": 150},
            {"kind": "pet"},
        ])
        self.assertEqual(list(got[0]), ["id", "at", "channel", "kind", "boss", "kc"])
        ids = [e["id"] for e in got]
        self.assertEqual(ids, sorted(set(ids)))                                # (each its own, going up)
        for e in got:
            self.assertEqual(e["channel"], self.ch)
            self.assertTrue(began - 5000 <= e["at"] <= time.time() * 1000 + 5000, e["at"])
        self.assertEqual(await self.mine(wait=self.settle * 4), got)            # (and nothing more: the quest, the small drop)

    async def test_a_report_from_the_transmitter_counts_too_its_numbers_and_names_checked(self):
        tx = await self.tx()
        await self.pause()
        for data in ({"pet": "Vorki"}, {"boss": "Zulrah " + "z" * 80, "count": 5.0},
                     {"skills": {"Attack": "99", "Magic": -1, "Prayer": True, "  ": 5, "9": 9, "Strength": 99}},
                     {"value": 2 ** 53, "items": []}, {"value": 1000000, "items": [{"name": "Coins", "quantity": 1000000, "price": 1}]}):
            kind = {"pet": "PET", "boss": "KILL_COUNT", "skills": "LEVEL", "value": "LOOT"}[next(iter(data))]
            await tx.send(json.dumps({"report": {"type": kind, "text": "", "at": 1, "data": data}}))
        await tx.send(json.dumps({"report": "not one"}))
        got = await self.mine(4)
        self.assertEqual(self.what(got), [
            {"kind": "pet", "name": "Vorki"},
            {"kind": "kill", "boss": ("Zulrah " + "z" * 80)[:60], "kc": 5},     # (a name cut to 60; 5.0 is 5)
            {"kind": "level", "skill": "Strength", "level": 99},              # (only a skill's name, and a whole number)
            {"kind": "loot", "name": "Coins", "value": 1000000},             # (1,000,000 is enough; 2^53 isn't a number)
        ])

    async def test_the_minigames_the_hiscores_count_as_bosses_are_kills_too(self):
        # (11/10/2026, Jace: Wintertodt, Tempoross and Zalcano pop on the map as any boss's kill does; Dink names them
        # from the game's messages, the Gauntlets by their Hunllefs)
        await self.tx()
        await self.pause()
        bosses = ["Wintertodt", "Tempoross", "Zalcano", "Crystalline Hunllef", "Corrupted Hunllef"]
        for i, boss in enumerate(bosses):
            await self.dink({"type": "KILL_COUNT", "content": "", "extra": {"boss": boss, "count": 100 + i}})
        got = await self.mine(len(bosses))
        self.assertEqual(self.what(got), [{"kind": "kill", "boss": b, "kc": 100 + i} for i, b in enumerate(bosses)])

    async def test_since_moves_on_and_a_read_without_it_has_the_last_fifty(self):
        await self.tx()
        await self.pause()
        await self.dink({"type": "KILL_COUNT", "content": "", "extra": {"boss": "Zulrah", "count": 1}})
        await self.dink({"type": "KILL_COUNT", "content": "", "extra": {"boss": "Zulrah", "count": 2}})
        first, second = await self.mine(2)
        status, _, body = await asyncio.to_thread(array_read, "?since=%d" % first["id"])
        self.assertEqual((status, body), (200, {"events": [second], "next": second["id"]}))
        status, _, body = await asyncio.to_thread(array_read, "?since=%d" % second["id"])
        self.assertEqual((status, body), (200, {"events": [], "next": second["id"]}))
        # (a cursor past the newest, from a relay that's started again with its clock behind: next is the newest)
        self.assertEqual((await asyncio.to_thread(array_read, "?since=9007199254740991"))[2],
                         {"events": [], "next": second["id"]})
        status, _, body = await asyncio.to_thread(array_read)
        self.assertEqual((status, body["events"][-1], body["next"]), (200, second, second["id"]))
        self.assertLessEqual(len(body["events"]), 50)
        self.assertEqual([e["id"] for e in body["events"]], sorted(e["id"] for e in body["events"]))
        for bad in ("abc", "-1", "1.5", "99999999999999999", "9007199254740992"):
            status, _, body = await asyncio.to_thread(array_read, "?since=" + bad)
            self.assertEqual((status, sorted(body)), (400, ["error"]), bad)

    async def test_a_page_is_a_hundred_at_most_and_a_report_adds_ten_at_most(self):
        # eleven outstations, each levelling twelve skills at once: ten each, and the first hundred in one read
        chans = [(self.ch, self.key)] + [await self.other() for _ in range(10)]
        for ch, key in chans:
            self.clients.append(await Client().open("%s/transmit/%s?key=%s" % (RELAY, ch, key)))
        await self.pause(2)
        for ch, key in chans:
            await self.dink({"type": "LEVEL", "content": "", "extra": {"levelledSkills": {s: 50 for s in SKILLS}}}, ch, key)
        end = time.monotonic() + 5
        while len([e for e in await asyncio.to_thread(array_since, self.since) if e["channel"] in dict(chans)]) < 110:
            self.assertLess(time.monotonic(), end, "the 110 events didn't all come")
            await asyncio.sleep(0.1)
        status, _, page = await asyncio.to_thread(array_read, "?since=%d" % self.since)
        self.assertEqual(len(page["events"]), 100)
        self.assertEqual(page["next"], page["events"][-1]["id"])
        rest = await asyncio.to_thread(array_since, page["next"])
        ours = [e for e in page["events"] + rest if e["channel"] in dict(chans)]
        self.assertEqual(len(ours), 110)
        for ch, _ in chans:
            self.assertEqual([e["skill"] for e in ours if e["channel"] == ch], SKILLS[:10])
        self.assertEqual(len((await asyncio.to_thread(array_read))[2]["events"]), 50)

    async def test_one_outstation_adds_ten_a_minute_at_most(self):
        await self.tx()
        await self.pause()
        await self.dink({"type": "LEVEL", "content": "", "extra": {"levelledSkills": {s: 60 for s in SKILLS[:8]}}})
        await self.dink({"type": "KILL_COUNT", "content": "", "extra": {"boss": "Vorkath", "count": 7}})
        await self.dink({"type": "LEVEL", "content": "", "extra": {"levelledSkills": {s: 61 for s in SKILLS[8:11]}}})
        await self.dink({"type": "PET", "content": "", "extra": {"petName": "Vorki"}})
        await self.mine(10)
        got = await self.mine(wait=self.settle * 5)
        self.assertEqual(len(got), 10)
        self.assertEqual(self.what(got[-2:]), [{"kind": "kill", "boss": "Vorkath", "kc": 7},
                                               {"kind": "level", "skill": SKILLS[8], "level": 61}])

    async def test_a_dark_outstations_reports_never_show(self):
        await self.dink({"type": "KILL_COUNT", "content": "", "extra": {"boss": "Vorkath", "count": 9}})   # (never on air)
        tx = await self.tx()
        await self.pause(2)
        tx.close()
        await self.pause(4)
        await self.dink({"type": "PET", "content": "", "extra": {"petName": "Vorki"}})                     # (gone dark)
        await self.dink({"type": "LEVEL", "content": "", "extra": {"levelledSkills": {"Magic": 99}}})
        await self.none_of_mine()

    async def test_a_watch_coded_outstations_reports_never_show(self):
        await self.tx("a-code")
        await self.pause(2)
        await self.dink({"type": "KILL_COUNT", "content": "", "extra": {"boss": "Vorkath", "count": 9}})
        await self.dink({"type": "PET", "content": "", "extra": {"petName": "Vorki"}})
        await self.dink({"type": "LOOT", "content": "", "extra": {"source": "Vorkath", "category": "NPC", "items": [
            {"name": "Skeletal visage", "quantity": 1, "priceEach": 1200000}]}})
        await self.none_of_mine()

    async def test_any_page_may_read_it_and_only_read_it(self):
        status, headers, body = await asyncio.to_thread(array_read)
        self.assertEqual(status, 200)
        self.assertEqual(headers.get("Access-Control-Allow-Origin"), "*")
        self.assertEqual(headers.get("Cache-Control"), "no-cache")
        self.assertIn("application/json", headers.get("Content-Type"))
        self.assertEqual(sorted(body), ["events", "next"])
        self.assertIsInstance(body["next"], int)
        for method in ("POST", "PUT", "DELETE"):
            self.assertEqual(http(method, "/array/events")[0], 405, method)


MP4 = b"\x00\x00\x00\x18ftypmp42" + b"\x00" * 64       # (only its first bytes are looked at)
JPEG = b"\xff\xd8\xff\xe0" + b"\x00" * 64


def clip_upload(meta, clip=MP4, strip=JPEG):
    b = "clipboundary"
    out = b""
    for name, kind, data in (("meta", "application/json", json.dumps(meta).encode()), ("strip", "image/jpeg", strip),
                             ("clip", "video/mp4", clip)):
        fn = '' if name == "meta" else '; filename="%s"' % name
        out += CRLF.join(["--" + b, 'Content-Disposition: form-data; name="%s"%s' % (name, fn), "Content-Type: " + kind, "", ""]).encode()
        out += data + CRLF.encode()
    return out + ("--%s--" % b + CRLF).encode(), "multipart/form-data; boundary=" + b


def fetch(method, path, headers=None):
    """(status, headers, body) from any route."""
    req = urllib.request.Request(http_url() + path, method=method, headers=headers or {})
    try:
        with urllib.request.urlopen(req, timeout=10) as r:
            return r.status, r.headers, r.read()
    except urllib.error.HTTPError as e:
        return e.code, e.headers, e.read()


class Clips(Base):
    """Clips on a player's Outside page (docs/protocol.md, "Clips"): uploaded with the channel's key, listed for
    anyone (with the watch code, if it has one), the files served whole or in ranges, told to viewers as they come,
    at most 20 a channel, and taken down by the channel's own portal."""

    def meta(self, what="pet vorki", at=None):
        return {"what": what, "kind": "PET", "at": at or int(time.time()), "dur": 17, "w": 768, "h": 432, "still": "AAAA"}

    async def up(self, meta=None, key=None, clip=MP4, strip=JPEG):
        body, ct = clip_upload(meta or self.meta(), clip, strip)
        return await post("/clips/%s?key=%s" % (self.ch, key or self.key), body, ct)

    async def test_an_upload_is_listed_told_and_served_in_ranges(self):
        await self.tx()
        v = await self.watch()
        await self.pause()
        self.assertEqual(await self.up(), 201)
        told = await next_of(v, "clip")
        self.assertEqual((told["what"], told["kind"], told["dur"], told["still"]), ("pet vorki", "PET", 17, "AAAA"))
        status, got = await asyncio.to_thread(http, "GET", "/clips/" + self.ch)
        self.assertEqual(status, 200)
        self.assertEqual([c["id"] for c in got["clips"]], [told["id"]])
        st, h, body = await asyncio.to_thread(fetch, "GET", "/clips/%s/%s.mp4" % (self.ch, told["id"]))
        self.assertEqual((st, body, h.get("content-type")), (200, MP4, "video/mp4"))
        st, h, body = await asyncio.to_thread(fetch, "GET", "/clips/%s/%s.mp4" % (self.ch, told["id"]), {"Range": "bytes=4-11"})
        self.assertEqual((st, body, h.get("content-range")), (206, b"ftypmp42", "bytes 4-11/%d" % len(MP4)))
        st, h, body = await asyncio.to_thread(fetch, "GET", "/clips/%s/%s.jpg" % (self.ch, told["id"]))
        self.assertEqual((st, body), (200, JPEG))
        self.assertEqual(h.get("access-control-allow-origin"), "*")

    async def test_the_wrong_key_and_what_isnt_a_clip_are_refused(self):
        await self.tx()
        self.assertEqual(await self.up(key="wrong-key-123456"), 403)
        self.assertEqual(await self.up(clip=b"not a video at all, no"), 415)
        self.assertEqual(await self.up(strip=b"\x89PNG not a jpeg"), 415)
        self.assertEqual(await self.up(clip=b"\x00\x00\x00\x18ftyp" + b"\x00" * (8 * 1024 * 1024)), 413)
        self.assertEqual(await self.up(meta=dict(self.meta(), still="x" * 20000)), 413)
        status, got = await asyncio.to_thread(http, "GET", "/clips/" + self.ch)
        self.assertEqual(got["clips"], [])

    async def test_twenty_at_most_the_oldest_going_and_its_own_portal_takes_one_down(self):
        await self.tx()
        now = int(time.time())
        for i in range(21):
            self.assertEqual(await self.up(meta=self.meta("drop %d" % i, at=now - 600 + i * 10)), 201)
        status, got = await asyncio.to_thread(http, "GET", "/clips/" + self.ch)
        whats = [c["what"] for c in got["clips"]]
        self.assertEqual(len(whats), 20)
        self.assertEqual((whats[0], whats[-1]), ("drop 20", "drop 1"))   # (newest first; "drop 0" gone)
        one = got["clips"][0]["id"]
        st, _, _ = await asyncio.to_thread(fetch, "DELETE", "/clips/%s/%s?key=%s" % (self.ch, one, "wrong-key-123456"))
        self.assertEqual(st, 403)
        v = await self.watch()
        await self.pause()
        st, _, _ = await asyncio.to_thread(fetch, "DELETE", "/clips/%s/%s?key=%s" % (self.ch, one, self.key))
        self.assertEqual(st, 204)
        self.assertEqual(await next_of(v, "clip_gone"), one)
        st, _, _ = await asyncio.to_thread(fetch, "GET", "/clips/%s/%s.mp4" % (self.ch, one))
        self.assertEqual(st, 404)
        status, got = await asyncio.to_thread(http, "GET", "/clips/" + self.ch)
        self.assertEqual(len(got["clips"]), 19)

    async def test_three_kept_for_good_outlast_the_twenty_and_a_fourth_is_refused(self):
        await self.tx()
        now = int(time.time())
        ids = []
        for i in range(4):
            self.assertEqual(await self.up(meta=self.meta("pet %d" % i, at=now - 900 + i)), 201)
        status, got = await asyncio.to_thread(http, "GET", "/clips/" + self.ch)
        ids = [c["id"] for c in got["clips"]]                   # (newest first: pet 3, 2, 1, 0)
        v = await self.watch()
        await self.pause()
        keep = lambda i, k="1", key=None: asyncio.to_thread(fetch, "POST", "/clips/%s/%s?key=%s&keep=%s" % (self.ch, i, key or self.key, k))
        self.assertEqual((await keep(ids[3], key="wrong-key-123456"))[0], 403)
        for i in ids[1:]:
            self.assertEqual((await keep(i))[0], 200)
        self.assertEqual(await next_of(v, "clip_kept"), {"id": ids[1], "kept": True})
        self.assertEqual((await keep(ids[0]))[0], 409)          # (a fourth)
        for i in range(21):
            self.assertEqual(await self.up(meta=self.meta("drop %d" % i, at=now - 600 + i * 10)), 201)
        status, got = await asyncio.to_thread(http, "GET", "/clips/" + self.ch)
        whats = [c["what"] for c in got["clips"]]
        self.assertEqual(len(whats), 23)                         # (the three kept, and the newest 20)
        self.assertEqual(sorted(w for w, c in zip(whats, got["clips"]) if c.get("kept")), ["pet 0", "pet 1", "pet 2"])
        self.assertNotIn("pet 3", whats)
        self.assertEqual((await keep(ids[1], "0"))[0], 200)      # (let go: now the oldest of the rest, so it goes)
        status, got = await asyncio.to_thread(http, "GET", "/clips/" + self.ch)
        self.assertEqual(len(got["clips"]), 22)
        self.assertNotIn(ids[1], [c["id"] for c in got["clips"]])

    @unittest.skipUnless(WORKER, "the keeper's routes are the Worker's")
    async def test_the_keeper_sees_and_takes_down_any_channels_clips(self):
        await self.tx(code="moss")
        await self.pause()
        self.assertEqual(await self.up(), 201)
        status, got = await asyncio.to_thread(admin, "GET", "/admin/clips/" + self.ch)    # (whatever its code)
        self.assertEqual((status, len(got["clips"])), (200, 1))
        one = got["clips"][0]["id"]
        status, _ = await asyncio.to_thread(admin, "DELETE", "/admin/clips/%s/%s" % (self.ch, one))
        self.assertEqual(status, 204)
        status, got = await asyncio.to_thread(admin, "GET", "/admin/clips/" + self.ch)
        self.assertEqual(got["clips"], [])
        req = urllib.request.Request(http_url() + "/admin/clips/" + self.ch, headers={"Authorization": "Bearer not-the-token-at-all"})
        with self.assertRaises(urllib.error.HTTPError) as e:
            await asyncio.to_thread(urllib.request.urlopen, req)
        self.assertEqual(e.exception.code, 403)

    async def test_a_friends_only_portals_clips_need_its_code(self):
        await self.tx(code="moss")
        await self.pause()
        self.assertEqual(await self.up(), 201)
        status, _ = await asyncio.to_thread(http, "GET", "/clips/" + self.ch)
        self.assertEqual(status, 403)
        status, got = await asyncio.to_thread(http, "GET", "/clips/%s?code=moss" % self.ch)
        self.assertEqual((status, len(got["clips"])), (200, 1))


class Messages(Base):
    """Messages between portals (docs/protocol.md, "Messages between portals"): one portal's desk sends {"dm"} to
    another channel; that channel's desk gets {"dm"} at once, or as it next connects; the sender is told
    {"dm_sent"} or {"dm_failed"}."""

    async def other(self):
        """A second channel, handed out (the Worker) or claimed (relay-py), and its key."""
        ch = "t" + secrets.token_hex(5)
        if WORKER:
            status, got = await asyncio.to_thread(admin, "POST", "/admin/channels/" + ch)
            return ch, got["key"]
        key = "other-key-" + secrets.token_hex(4)
        c = await Client().open("%s/transmit/%s?key=%s&code=" % (RELAY, ch, key))
        self.clients.append(c)
        return ch, key

    async def desk_of(self, ch, key, name):
        c = await Client().open("%s/desk/%s?key=%s&name=%s" % (RELAY, ch, key, name))
        self.clients.append(c)
        return c

    async def test_a_message_reaches_the_other_portal_and_its_sender_hears_it_went(self):
        await self.tx()
        mine = await self.desk("Moss%20Fern")
        ch, key = await self.other()
        theirs = await self.desk_of(ch, key, "Birch")
        await self.pause()
        await mine.send(json.dumps({"dm": {"to": ch, "text": "nice drop!"}}))
        got = await next_of(theirs, "dm")
        self.assertEqual((got["from"], got["name"], got["text"]), (self.ch, "Moss Fern", "nice drop!"))
        sent = await next_of(mine, "dm_sent")
        self.assertEqual((sent["to"], sent["text"], sent["held"]), (ch, "nice drop!", False))
        await theirs.send(json.dumps({"dm": {"to": self.ch, "text": "ty"}}))
        self.assertEqual((await next_of(mine, "dm"))["text"], "ty")

    async def test_held_till_the_portal_opens_and_refused_for_no_such_portal(self):
        await self.tx()
        mine = await self.desk("Moss%20Fern")
        ch, key = await self.other()
        await self.pause()
        await mine.send(json.dumps({"dm": {"to": ch, "text": "for later"}}))
        self.assertTrue((await next_of(mine, "dm_sent"))["held"])
        theirs = await self.desk_of(ch, key, "Birch")
        self.assertEqual((await next_of(theirs, "dm"))["text"], "for later")
        await mine.send(json.dumps({"dm": {"to": "nobody" + secrets.token_hex(3), "text": "hello?"}}))
        self.assertIn("no outstation", (await next_of(mine, "dm_failed"))["why"])
        await mine.send(json.dumps({"dm": {"to": self.ch, "text": "me"}}))
        self.assertIn("your own", (await next_of(mine, "dm_failed"))["why"])
        # (a viewer can't send one: only a portal's desk)
        v = await self.watch()
        await v.send(json.dumps({"dm": {"to": ch, "text": "from a page"}}))
        with self.assertRaises(asyncio.TimeoutError):
            await next_of(theirs, "dm", timeout=0.5)


class Sound(Base):
    def packet(self, bus=1):
        return struct.pack(">I", 99) + b"AU" + bytes([bus, 1]) + struct.pack(">H", 3) + b"opu"

    async def test_sound_reaches_viewers_after_the_hold_while_open(self):
        tx = await self.tx()
        snd = await self.connect("sound", "key=" + self.key)
        v = await self.watch()
        await tx.send('{"state": "open"}')
        await snd.send('{"sound": {"mode": "all", "buses": [1, 2]}}')
        self.assertEqual((await next_of(v, "sound"))["buses"], [1, 2])
        await snd.send(self.packet())
        t0 = time.monotonic()
        while True:
            k, payload = await v.recv()
            if k == "binary":
                break
        self.assertEqual(payload, self.packet())
        self.assertGreaterEqual(time.monotonic() - t0, SOUND_HOLD - 0.1)

    async def test_paused_nothing_is_heard_and_sound_is_never_the_last_frame(self):
        tx = await self.tx()
        snd = await self.connect("sound", "key=" + self.key)
        v = await self.watch()
        await tx.send('{"state": "open"}')
        await self.pause()
        await snd.send(self.packet())
        await asyncio.sleep(0.1)
        await tx.send('{"state": "shut"}')
        self.assertTrue(await nothing_binary(v, SOUND_HOLD + 0.4))
        late = await self.watch()
        self.assertTrue(await nothing_binary(late))

    async def test_when_the_sound_stops_viewers_hear_it_is_off(self):
        await self.tx()
        snd = await self.connect("sound", "key=" + self.key)
        v = await self.watch()
        await snd.send('{"sound": {"mode": "game", "buses": [1]}}')
        await next_of(v, "sound")
        snd.close()
        self.assertEqual((await next_of(v, "sound"))["mode"], "off")


@unittest.skipUnless(WORKER, "flow control by acknowledgement: the Worker only (relay.py reads its own send buffer)")
class Flow(Base):
    async def test_a_viewer_that_doesnt_acknowledge_stops_getting_frames_until_it_does(self):
        tx = await self.tx()
        v = await self.watch()
        await self.pause()
        frame = struct.pack(">I", 1) + b"x" * (64 * 1024)
        for _ in range(80):                      # 5 MB: past the 4 MB window
            await tx.send(frame)
        got = 0
        try:
            while True:
                k, payload = await v.recv(timeout=0.6)
                if k == "binary":
                    got += 1
        except asyncio.TimeoutError:
            pass
        self.assertLess(got, 80)
        self.assertGreater(got, 55)
        await v.send(json.dumps({"got": got * len(frame)}))
        await self.pause()
        await tx.send(frame)
        self.assertEqual((await v.recv_data())[0], "binary")


class Game(Base):
    """The game (11/07/2026): the Outside plugin, a RuneLite plugin of our own, sends the player's live game state on a
    socket of its own. Each field replaces the one kept but "items", which is merged; what changed reaches seated
    viewers, lookers and desks, and someone just come gets the whole of it; GET /game reads it while the outstation's
    dark. (The checking of each field, the dictionary's most and the kept state's most: relay-py/test_relay.py's.)"""

    STATE = {"name": "Sburb", "world": 302, "type": "NORMAL", "off": False, "paused": False,
             "vitals": {"hp": [80, 99], "prayer": [43, 70], "run": 100, "spec": 50},
             "skills": {"Attack": [99, 112, 13034431], "Hitpoints": [99, 99, 13034431]},
             "worn": [[1163, 1]] + [None] * 13, "inv": [[385, 12]] + [None] * 27, "bag": None,
             "pouch": [[560, 1000], [565, 2000], [9075, 400]], "quiver": [11212, 120], "prayers": ["PIETY"],
             "items": {"385": ["Shark", 870], "1163": ["Rune full helm", 21000]}}

    async def game(self, key=None):
        """A game socket, saying a version older than any MIN_CLIENT: the plugin's versions are its own."""
        return await self.connect("game", "key=%s&v=0.0.1" % (key or self.key))

    def read(self, code="", channel=None):
        return http("GET", "/game/%s?code=%s" % (channel or self.ch, code))

    async def test_the_right_key_connects_whatever_its_version_and_a_wrong_or_short_one_is_refused(self):
        v = await self.watch()
        self.assertFalse((await next_of(v, "relay"))["game"])
        await self.game()
        while not (await next_of(v, "relay"))["game"]:
            pass
        wrong = await self.game(key="wrong-key-" + secrets.token_hex(3))
        self.assertEqual(await wrong.recv_close(), ("close", 4003))
        short = await self.game(key="short")
        self.assertEqual(await short.recv_close(), ("close", 4001))

    async def test_what_changed_reaches_viewers_lookers_and_desks_and_someone_just_come_gets_it_all(self):
        g = await self.game()
        v, look, d = await self.watch(), await self.connect("watch", "look=1"), await self.desk()
        await self.pause()
        t0 = time.time() * 1000
        await g.send(json.dumps({"game": self.STATE}))
        for c in (v, look, d):
            got = await next_of(c, "game")
            self.assertLess(abs(got.pop("at") - t0), 60000)
            self.assertEqual(got, self.STATE)
        await g.send(json.dumps({"game": {"vitals": {"hp": [70, 99], "prayer": [40, 70], "run": 98, "spec": 50}}}))
        got = await next_of(v, "game")
        self.assertEqual(sorted(got), ["at", "vitals"])                   # (only what was sent)
        for late in (await self.watch(), await self.connect("watch", "look=1"), await self.desk(name="late")):
            whole = await next_of(late, "game")
            self.assertEqual((whole["vitals"]["hp"], whole["inv"], whole["at"]), ([70, 99], self.STATE["inv"], got["at"]))

    async def test_items_merge_while_every_other_field_is_replaced_whole(self):
        g = await self.game()
        v = await self.watch()
        await self.pause()
        await g.send(json.dumps({"game": {"inv": [[385, 1]] + [None] * 27, "items": {"385": ["Shark", 870]}, "off": True,
                                          "paused": "  wilderness\n"}}))
        await g.send(json.dumps({"game": {"inv": [None] * 27 + [[2434, 4]],
                                          "items": {"2434": ["Prayer potion(4)", 9000], "385": ["Shark", 900]}}}))
        await next_of(v, "game")
        self.assertEqual((await next_of(v, "game"))["items"], {"385": ["Shark", 900], "2434": ["Prayer potion(4)", 9000]})
        whole = await next_of(await self.watch(), "game")
        self.assertEqual(whole["inv"], [None] * 27 + [[2434, 4]])
        self.assertEqual(whole["items"], {"385": ["Shark", 900], "2434": ["Prayer potion(4)", 9000]})
        self.assertEqual((whole["off"], whole["paused"]), (True, "wilderness"))   # (a message without them: as they were)
        await g.send(json.dumps({"game": {"off": False, "paused": False}}))
        got = await next_of(v, "game")
        self.assertEqual((got["off"], got["paused"]), (False, False))
        self.assertEqual(self.read()[1]["game"]["paused"], False)
        await g.send(json.dumps({"game": {"off": False, "paused": True}}))   # (nothing changed, true isn't its shape)
        with self.assertRaises(asyncio.TimeoutError):
            await next_of(v, "game", timeout=0.5)

    async def test_a_second_game_socket_replaces_the_first(self):
        old = await self.game()
        await self.pause()
        new = await self.game()
        self.assertEqual(await old.recv_close(), ("close", 4000))
        v = await self.watch()
        self.assertTrue((await next_of(v, "relay"))["game"])
        await new.send(json.dumps({"game": {"world": 420}}))
        self.assertEqual((await next_of(v, "game"))["world"], 420)

    async def test_the_read_gives_the_last_of_it_and_whether_the_plugin_is_connected(self):
        status, got = self.read()
        self.assertEqual((status, got["error"]), (404, "no game state for that outstation"))
        g = await self.game()
        v = await self.watch()
        await self.pause()
        await g.send(json.dumps({"game": {"name": "Sburb", "world": 302}}))
        at = (await next_of(v, "game"))["at"]
        status, got = self.read()
        self.assertEqual((status, got), (200, {"game": {"name": "Sburb", "world": 302}, "at": at, "connected": True}))
        g.close()
        while (await next_of(v, "relay"))["game"]:
            pass
        status, got = self.read()
        self.assertEqual((status, got["game"]["world"], got["connected"]), (200, 302, False))   # (while the player's away)
        self.assertEqual(self.read(channel="none" + secrets.token_hex(4))[0], 404)

    async def test_a_coded_outstations_game_needs_its_code(self):
        await self.tx("moss")
        g = await self.game()
        v = await self.watch("moss")
        await self.pause()
        await g.send(json.dumps({"game": {"world": 302}}))
        await next_of(v, "game")
        self.assertEqual([self.read(code)[0] for code in ("lichen", "")], [403, 403])
        self.assertEqual(self.read("moss")[1]["game"], {"world": 302})

    async def test_the_status_line_and_the_open_outstations_say_whether_its_connected(self):
        def row():
            return {c["channel"]: c for c in http("GET", "/channels")[1]["channels"]}[self.ch]
        v = await self.watch()
        self.assertFalse((await next_of(v, "relay"))["game"])
        g = await self.game()
        while not (await next_of(v, "relay"))["game"]:
            pass
        await self.pause(2)
        self.assertTrue(row()["game"])
        g.close()
        while (await next_of(v, "relay"))["game"]:
            pass
        await self.pause(2)
        self.assertFalse(row()["game"])

    async def test_too_big_unknown_and_wrong_shaped_fields_are_ignored(self):
        g = await self.game()
        v = await self.watch()
        await self.pause()
        big = json.dumps({"game": {"name": "Big", "prayers": ["x" * 40] * 2000}})
        self.assertGreater(len(big), 64 * 1024)
        await g.send(big)
        await g.send(json.dumps({"game": {"name": "  Sburb\n ", "world": "302", "inv": "full", "worn": [[1, 1]] * 15,
                                          "secret": "kept?", "vitals": {"hp": [10, 99], "mood": "fine", "run": 140}}}))
        got = await next_of(v, "game")                       # (the first: the big one never came)
        got.pop("at")
        self.assertEqual(got, {"name": "Sburb", "vitals": {"hp": [10, 99]}})
        self.assertEqual(self.read()[1]["game"], {"name": "Sburb", "vitals": {"hp": [10, 99]}})
        await g.send(json.dumps({"something": {"world": 302}}))     # (not a game message at all)
        await g.send(json.dumps({"game": {"secret": 1}}))           # (nothing known in it)
        with self.assertRaises(asyncio.TimeoutError):
            await next_of(v, "game", timeout=0.5)

    async def test_more_than_five_a_second_are_dropped(self):
        g = await self.game()
        v = await self.watch()
        await self.pause()
        for i in range(8):
            await g.send(json.dumps({"game": {"world": 300 + i}}))
        worlds = []
        try:
            while True:
                worlds.append((await next_of(v, "game", timeout=0.6))["world"])
        except asyncio.TimeoutError:
            pass
        self.assertEqual(worlds, [300, 301, 302, 303, 304])
        await asyncio.sleep(1.0)
        await g.send(json.dumps({"game": {"world": 399}}))
        self.assertEqual((await next_of(v, "game"))["world"], 399)

    @unittest.skipUnless(WORKER, "a new key from the keeper: the Worker only")
    async def test_a_new_key_cuts_the_game_off_and_forgets_it(self):
        g = await self.game()
        v = await self.watch()
        await self.pause()
        await g.send(json.dumps({"game": {"world": 302}}))
        await next_of(v, "game")
        self.assertEqual(admin("POST", "/admin/channels/" + self.ch)[0], 200)
        self.assertEqual(await g.recv_close(), ("close", 4003))
        self.assertEqual(self.read()[0], 404)
        self.assertIsNone(await nothing_game(await self.watch()))


class Following(Base):
    """Following (11/08/2026): the Outside plugin may send "where", the middle of the 128-tile square of the main map the
    player was in two minutes before, or null. The relay holds it (one every 50 s at most; null whenever it comes), drops
    it when the plugin goes, logs out or holds for the player's safety, and shows it only on GET /channels, while the
    channel's live and listed and it came within WHERE_FRESH_S. Never to viewers or lookers, in GET /game or in
    the Array's events. (The throttle's end and --delay: relay-py/test_relay.py's.)"""

    LUMBRIDGE = {"x": 3264, "y": 3264}

    async def game(self, key=None, channel=None):
        return await Client().open("%s/game/%s?key=%s&v=0.0.1" % (RELAY, channel or self.ch, key or self.key))

    async def say(self, g, fields):
        await g.send(json.dumps({"game": fields}))

    async def where(self, channel=None):
        """This channel's row's "where" on GET /channels, after a moment for the Worker's channel to tell the Directory;
        (False if it has no row)."""
        await self.pause(2)
        rows = {c["channel"]: c for c in http("GET", "/channels")[1]["channels"]}
        row = rows.get(channel or self.ch)
        return False if row is None else row["where"]

    async def live_and_following(self, where=None, code=""):
        """A transmitter (live) and the plugin, which has said where it is: (the transmitter, the plugin)."""
        tx = await self.tx(code)
        await next_of(tx, "relay")
        g = await self.game()
        self.clients.append(g)
        await self.say(g, {"where": where or self.LUMBRIDGE})
        return tx, g

    async def test_a_square_shows_on_the_list_only_while_live(self):
        g = await self.game()
        self.clients.append(g)
        await self.say(g, {"where": self.LUMBRIDGE})
        self.assertIsNone(await self.where())                      # (held, but not on air: not shown)
        tx = await self.tx()
        await next_of(tx, "relay")
        self.assertEqual(await self.where(), self.LUMBRIDGE)
        tx.close()
        await self.pause(2)
        self.assertIsNone(await self.where())
        tx = await self.tx()                                       # (on air again within the time: shown again)
        await next_of(tx, "relay")
        self.assertEqual(await self.where(), self.LUMBRIDGE)

    async def test_it_goes_stale(self):
        await self.live_and_following()
        self.assertEqual(await self.where(), self.LUMBRIDGE)
        await asyncio.sleep(WHERE_FRESH_S + 0.5)
        self.assertIsNone(await self.where())

    async def test_a_coded_outstation_never_shows_it(self):
        await self.live_and_following({"x": 1088, "y": 2496}, code="moss")
        await self.pause(2)
        got = http("GET", "/channels")[1]["channels"]
        self.assertNotIn(self.ch, [c["channel"] for c in got])
        self.assertNotIn({"x": 1088, "y": 2496}, [c["where"] for c in got])

    async def test_an_invite_is_taken_up_by_the_plugin_and_shows_it_only_once_live(self):
        if not WORKER:
            await self.tx()                    # (relay.py: the inviter's handed out once its first transmitter claims it)
            await self.pause(2)
        a = "i" + secrets.token_hex(5)
        status, got = http("POST", "/invites/" + self.ch, {"key": self.key, "player": a})
        self.assertEqual(status, 200)
        self.assertIs(await self.where(a), False)                   # (pending: no row at all)
        g = await self.game(key=got["key"], channel=a)              # (its key used: taken up)
        self.clients.append(g)
        await self.say(g, {"where": self.LUMBRIDGE})
        self.assertIsNone(await self.where(a))                      # (listed now, but not live)
        tx = await Client().open("%s/transmit/%s?key=%s&code=" % (RELAY, a, got["key"]))
        self.clients.append(tx)
        await next_of(tx, "relay")
        self.assertEqual(await self.where(a), self.LUMBRIDGE)

    async def test_a_bad_square_is_ignored_and_the_rest_of_its_message_still_lands(self):
        tx = await self.tx()
        await next_of(tx, "relay")
        g = await self.game()
        self.clients.append(g)
        v = await self.watch()
        await self.pause()
        bad = [{"x": 3265, "y": 3264}, {"x": 4160, "y": 3264}, {"x": 3264, "y": 4288}, {"x": 832, "y": 3264},
               {"x": 3264, "y": 3264, "plane": 0}, {"x": 3264}, {"x": "3264", "y": 3264}, {"x": 3264.5, "y": 3264},
               {"x": True, "y": 3264}, [3264, 3264], "lumbridge", 3264, True,
               {"x": 2368, "y": 4544}, {"x": 2496, "y": 4416}, {"x": 2368, "y": 4288}]   # (Zanaris's neighbours: off the map)
        for i, w in enumerate(bad):
            if i and i % 4 == 0:
                await asyncio.sleep(1.0)                           # (5 a second at most)
            await self.say(g, {"world": 300 + i, "where": w})
            got = await next_of(v, "game")
            self.assertEqual((sorted(got), got["world"]), (["at", "world"], 300 + i), w)
        self.assertIsNone(await self.where())
        await asyncio.sleep(1.0)
        await self.say(g, {"where": {"x": 3264.0, "y": 2496}})   # (a bad one never counted: this one's taken)
        self.assertEqual(await self.where(), {"x": 3264, "y": 2496})

    async def test_zanaris_is_one_place_and_shows(self):
        # (11/08/2026, Jace: "if someone goes to zanaris through a fairy ring on Gielinor make sure it shows their
        # location as being on Zanaris": the plugin sends any tile there as this, which the globe draws on its moon)
        await self.live_and_following({"x": 2368, "y": 4416})
        self.assertEqual(await self.where(), {"x": 2368, "y": 4416})

    async def test_the_seas_squares_show(self):
        # (11/10/2026, Sailing: the band of sea round the old map, x 960-1023 and 3968-4031, y 2048-2495 and 4160-4223,
        # is the main map's too: its south-west corner's square, the furthest from the old map's)
        await self.live_and_following({"x": 960, "y": 2112})
        self.assertEqual(await self.where(), {"x": 960, "y": 2112})

    async def test_the_great_conchs_square_shows(self):
        # (11/10/2026: the Great Conch, Sailing's island south of the old map, its square y 2432-2559)
        await self.live_and_following({"x": 3264, "y": 2496})
        self.assertEqual(await self.where(), {"x": 3264, "y": 2496})

    async def test_one_every_50_seconds_and_null_clears_whenever(self):
        tx, g = await self.live_and_following()
        self.assertEqual(await self.where(), self.LUMBRIDGE)
        await self.say(g, {"where": {"x": 3136, "y": 3264}})       # (too soon: ignored)
        self.assertEqual(await self.where(), self.LUMBRIDGE)
        await self.say(g, {"where": None})
        self.assertIsNone(await self.where())
        await self.say(g, {"where": {"x": 3136, "y": 3264}})       # (still too soon after the last one taken)
        self.assertIsNone(await self.where())

    async def test_logging_out_drops_it(self):
        tx, g = await self.live_and_following()
        self.assertEqual(await self.where(), self.LUMBRIDGE)
        await self.say(g, {"off": True})
        self.assertIsNone(await self.where())

    async def test_holding_for_the_players_safety_drops_it(self):
        tx, g = await self.live_and_following()
        self.assertEqual(await self.where(), self.LUMBRIDGE)
        await self.say(g, {"paused": "wilderness", "worn": [], "inv": [], "bag": [], "pouch": [], "quiver": None,
                           "vitals": {}, "prayers": []})              # (the plugin's Kit.held)
        self.assertIsNone(await self.where())

    async def test_the_plugin_going_drops_it(self):
        tx, g = await self.live_and_following()
        self.assertEqual(await self.where(), self.LUMBRIDGE)
        g.close()
        v = await self.watch()
        while (await next_of(v, "relay"))["game"]:
            pass
        self.assertIsNone(await self.where())

    @unittest.skipUnless(WORKER, "a new key from the keeper: the Worker only")
    async def test_a_new_key_drops_it(self):
        await self.live_and_following()
        self.assertEqual(await self.where(), self.LUMBRIDGE)
        status, got = admin("POST", "/admin/channels/" + self.ch)
        self.assertEqual(status, 200)
        tx = await self.tx(key=got["key"])
        await next_of(tx, "relay")
        self.assertIsNone(await self.where())

    async def test_never_to_viewers_lookers_desks_the_read_or_the_arrays_events(self):
        v, look, d = await self.watch(), await self.connect("watch", "look=1"), await self.desk()
        tx, g = await self.live_and_following()
        await self.say(g, {"world": 302, "where": {"x": 3136, "y": 3264}})
        self.assertEqual(await self.where(), self.LUMBRIDGE)          # (the list has it)
        late = [await self.watch(), await self.connect("watch", "look=1"), await self.desk(name="late")]
        for c in [v, look, d] + late:
            heard = []
            try:
                while True:
                    kind, line = await c.recv(timeout=0.5)
                    if kind == "text":
                        heard.append(line)
            except asyncio.TimeoutError:
                pass
            self.assertTrue(any('"game"' in line for line in heard))  # (the game reached it, the world)
            for line in heard:
                if c in (d, late[2]) and line.startswith('{"mine": {"where"'):
                    continue                                         # (your year, 11/10/2026: the owner's desk only, as {"mine"})
                self.assertNotIn("where", line)
            if c in (d, late[2]):
                self.assertTrue(any(line.startswith('{"mine": {"where"') for line in heard))
        self.assertNotIn("where", json.dumps(self.read_game()))
        with urllib.request.urlopen(http_url() + "/array/events", timeout=10) as r:
            self.assertNotIn("where", r.read().decode())

    def read_game(self):
        status, got = http("GET", "/game/%s?code=" % self.ch)
        self.assertEqual((status, got["game"]["world"]), (200, 302))
        return got



class FollowingWorld(Base):
    """Which world (11/08/2026): with the plugin's "and my world" on, "where" carries the world with the square, {"x",
    "y", "world"}, a whole number from 1 to 999; shown, taken and dropped as the square is (Following's tests)."""

    game, say, where, live_and_following = Following.game, Following.say, Following.where, Following.live_and_following

    async def test_the_world_shows_with_the_square(self):
        await self.live_and_following({"x": 3264, "y": 3264, "world": 302})
        self.assertEqual(await self.where(), {"x": 3264, "y": 3264, "world": 302})

    async def test_zanaris_with_its_world(self):
        await self.live_and_following({"y": 4416, "world": 330, "x": 2368})
        self.assertEqual(await self.where(), {"x": 2368, "y": 4416, "world": 330})

    async def test_a_bad_world_is_ignored_with_its_square_and_the_rest_still_lands(self):
        tx = await self.tx()
        await next_of(tx, "relay")
        g = await self.game()
        self.clients.append(g)
        v = await self.watch()
        await self.pause()
        bad = [{"x": 3264, "y": 3264, "world": 0}, {"x": 3264, "y": 3264, "world": 1000},
               {"x": 3264, "y": 3264, "world": -302}, {"x": 3264, "y": 3264, "world": 302.5},
               {"x": 3264, "y": 3264, "world": "302"}, {"x": 3264, "y": 3264, "world": True},
               {"x": 3264, "y": 3264, "world": None}, {"x": 3264, "y": 3264, "w": 302},
               {"x": 3264, "world": 302}, {"x": 3264, "y": 3264, "world": 302, "plane": 0}]
        for i, w in enumerate(bad):
            if i and i % 4 == 0:
                await asyncio.sleep(1.0)                           # (5 a second at most)
            await self.say(g, {"world": 300 + i, "where": w})
            got = await next_of(v, "game")
            self.assertEqual((sorted(got), got["world"]), (["at", "world"], 300 + i), w)
        self.assertIsNone(await self.where())
        await asyncio.sleep(1.0)
        await self.say(g, {"where": {"x": 3264, "y": 3264, "world": 999.0}})   # (as JavaScript reads it)
        self.assertEqual(await self.where(), {"x": 3264, "y": 3264, "world": 999})

    async def test_never_to_viewers_or_the_read(self):
        v = await self.watch()
        await self.live_and_following({"x": 3264, "y": 3264, "world": 417})
        await self.say((self.clients[-1]), {"name": "Sburb"})
        self.assertEqual(await self.where(), {"x": 3264, "y": 3264, "world": 417})
        heard = []
        try:
            while True:
                kind, line = await v.recv(timeout=0.5)
                if kind == "text":
                    heard.append(line)
        except asyncio.TimeoutError:
            pass
        self.assertTrue(any('"game"' in line for line in heard))
        for line in heard:
            self.assertNotIn('"world"', line)
            self.assertNotIn("where", line)
        self.assertNotIn('"world"', json.dumps(http("GET", "/game/%s?code=" % self.ch)[1]))


class Activity(Base):
    """What they're doing (11/08/2026): the Outside plugin may send "activity", one coarse label from closed lists
    ({"kind": "skilling", "skill"}, {"kind": "boss", "boss"}, {"kind": "fighting"}, {"kind": "idle"}), or null. Held,
    taken (one every 50 s), dropped and shown on GET /channels exactly as following's "where", and without it. Never to
    viewers or lookers, in GET /game or in the Array's events; the owner's desk only as {"mine"} (YourYear). (The throttle's end: relay-py/test_relay.py's.)"""

    MINING = {"kind": "skilling", "skill": "Mining"}

    async def game(self, key=None, channel=None):
        return await Client().open("%s/game/%s?key=%s&v=0.0.1" % (RELAY, channel or self.ch, key or self.key))

    async def say(self, g, fields):
        await g.send(json.dumps({"game": fields}))

    async def row(self, channel=None):
        """This channel's row on GET /channels, after a moment for the Worker's channel to tell the Directory (None if
        it has none)."""
        await self.pause(2)
        rows = {c["channel"]: c for c in http("GET", "/channels")[1]["channels"]}
        return rows.get(channel or self.ch)

    async def activity(self):
        r = await self.row()
        return False if r is None else r["activity"]

    async def live_and_doing(self, activity=None):
        tx = await self.tx()
        await next_of(tx, "relay")
        g = await self.game()
        self.clients.append(g)
        await self.say(g, {"activity": activity or self.MINING})
        return tx, g

    async def test_it_shows_only_while_live_without_following(self):
        g = await self.game()
        self.clients.append(g)
        await self.say(g, {"activity": self.MINING})
        self.assertIsNone(await self.activity())                   # (held, but not on air)
        tx = await self.tx()
        await next_of(tx, "relay")
        r = await self.row()
        self.assertEqual((r["activity"], r["where"]), (self.MINING, None))
        tx.close()
        await self.pause(2)
        self.assertIsNone(await self.activity())

    async def test_each_shape_it_takes(self):
        for a in ({"kind": "boss", "boss": "Vorkath"}, {"kind": "fighting"}, {"kind": "idle"},
                  {"skill": "Sailing", "kind": "skilling"}, {"kind": "boss", "boss": "Barrows"},
                  {"kind": "raid", "raid": "Tombs of Amascut"}, {"kind": "minigame", "minigame": "the Colosseum"},
                  {"kind": "boss", "boss": "Callisto"}, {"kind": "boss", "boss": "the Gauntlet"},
                  {"kind": "minigame", "minigame": "Last Man Standing"}, {"kind": "minigame", "minigame": "Zalcano"}):
            self.ch = "t" + secrets.token_hex(5)
            if WORKER:
                status, got = admin("POST", "/admin/channels/" + self.ch)
                self.key = got["key"]
            await self.live_and_doing(a)
            self.assertEqual(await self.activity(), dict(a), a)

    async def test_it_goes_stale(self):
        await self.live_and_doing()
        self.assertEqual(await self.activity(), self.MINING)
        await asyncio.sleep(WHERE_FRESH_S + 0.5)
        self.assertIsNone(await self.activity())

    async def test_a_bad_one_is_ignored_and_the_rest_of_its_message_still_lands(self):
        tx = await self.tx()
        await next_of(tx, "relay")
        g = await self.game()
        self.clients.append(g)
        v = await self.watch()
        await self.pause()
        bad = [{"kind": "skilling", "skill": "Overall"}, {"kind": "skilling", "skill": "mining"},
               {"kind": "skilling"}, {"kind": "boss", "boss": "Zalcano"}, {"kind": "boss", "boss": "Jace"},
               {"kind": "fighting", "with": "Goblin"}, {"kind": "idle", "since": 5}, {"kind": "afk"}, {"kind": 1},
               {}, "idle", ["idle"], 5, True, {"kind": "skilling", "skill": ["Mining"]},
               {"kind": "boss", "boss": "Vorkath", "kc": 50}, {"kind": "raid", "raid": "ToB"},
               {"kind": "minigame", "minigame": "the Gauntlet"}, {"kind": "minigame"},
               {"kind": "raid", "raid": "Theatre of Blood", "room": "Verzik"}]
        for i, a in enumerate(bad):
            if i and i % 4 == 0:
                await asyncio.sleep(1.0)
            await self.say(g, {"world": 300 + i, "activity": a})
            got = await next_of(v, "game")
            self.assertEqual((sorted(got), got["world"]), (["at", "world"], 300 + i), a)
        self.assertIsNone(await self.activity())
        await asyncio.sleep(1.0)
        await self.say(g, {"activity": {"kind": "idle"}})          # (a bad one never counted: this one's taken)
        self.assertEqual(await self.activity(), {"kind": "idle"})

    async def test_one_every_50_seconds_and_null_clears_whenever(self):
        tx, g = await self.live_and_doing()
        self.assertEqual(await self.activity(), self.MINING)
        await self.say(g, {"activity": {"kind": "fighting"}})      # (too soon: ignored)
        self.assertEqual(await self.activity(), self.MINING)
        await self.say(g, {"activity": None})
        self.assertIsNone(await self.activity())

    async def test_logging_out_holding_or_the_plugin_going_drops_it(self):
        tx, g = await self.live_and_doing()
        self.assertEqual(await self.activity(), self.MINING)
        await self.say(g, {"off": True})
        self.assertIsNone(await self.activity())
        self.ch = "t" + secrets.token_hex(5)
        if WORKER:
            self.key = admin("POST", "/admin/channels/" + self.ch)[1]["key"]
        tx, g = await self.live_and_doing()
        self.assertEqual(await self.activity(), self.MINING)
        await self.say(g, {"paused": "wilderness"})
        self.assertIsNone(await self.activity())
        self.ch = "t" + secrets.token_hex(5)
        if WORKER:
            self.key = admin("POST", "/admin/channels/" + self.ch)[1]["key"]
        tx, g = await self.live_and_doing()
        self.assertEqual(await self.activity(), self.MINING)
        g.close()
        v = await self.watch()
        while (await next_of(v, "relay"))["game"]:
            pass
        self.assertIsNone(await self.activity())

    async def test_a_coded_outstation_never_shows_it(self):
        tx = await self.tx(code="moss")
        await next_of(tx, "relay")
        g = await self.game()
        self.clients.append(g)
        await self.say(g, {"activity": {"kind": "minigame", "minigame": "Zalcano"}})
        self.assertIsNone(await self.row())

    @unittest.skipUnless(WORKER, "a new key from the keeper: the Worker only")
    async def test_a_new_key_drops_it(self):
        await self.live_and_doing()
        self.assertEqual(await self.activity(), self.MINING)
        status, got = admin("POST", "/admin/channels/" + self.ch)
        tx = await self.tx(key=got["key"])
        await next_of(tx, "relay")
        self.assertIsNone(await self.activity())

    async def test_never_to_viewers_lookers_desks_the_read_or_the_arrays_events(self):
        v, look, d = await self.watch(), await self.connect("watch", "look=1"), await self.desk()
        tx, g = await self.live_and_doing({"kind": "boss", "boss": "Kraken"})
        await self.say(g, {"world": 302})
        self.assertEqual(await self.activity(), {"kind": "boss", "boss": "Kraken"})
        for c in (v, look, d):
            heard = []
            try:
                while True:
                    kind, line = await c.recv(timeout=0.5)
                    if kind == "text":
                        heard.append(line)
            except asyncio.TimeoutError:
                pass
            self.assertTrue(any('"game"' in line for line in heard))
            for line in heard:
                if c is d and line.startswith('{"mine": {"activity"'):
                    continue                                         # (your year, 11/10/2026: the owner's desk only, as {"mine"})
                self.assertNotIn("Kraken", line)
                self.assertNotIn("activity", line)
            if c is d:
                self.assertTrue(any(line.startswith('{"mine": {"activity"') for line in heard))
        self.assertNotIn("Kraken", json.dumps(http("GET", "/game/%s?code=" % self.ch)[1]))
        with urllib.request.urlopen(http_url() + "/array/events", timeout=10) as r:
            self.assertNotIn("Kraken", r.read().decode())


class Stars(Base):
    """Shooting stars (11/08/2026): the Outside plugin may send "star", a crashed star it saw two minutes before
    ({"world", "x", "y", "tier"}, or "gone": true; with "site", its landing site, 11/09/2026). The relay keeps one for
    each world and square, however many told of it, and GET /stars shows them with the first listed outstation to tell
    of each ("by", 11/09/2026; null for a hidden one) and how many did: a lower tier replaces, a higher one's old news,
    gone takes it off (and a late report doesn't bring it back). One outstation tells of 30 an hour at most. (Expiry, at
    two hours: relay-py's own tests.) Each test has a world and a square of its own: the list is the whole relay's."""

    async def asyncSetUp(self):
        await super().asyncSetUp()
        self.world = 1 + secrets.randbelow(999)
        self.x = 1088 + 128 * secrets.randbelow(24)
        self.y = 2496 + 128 * secrets.randbelow(14)

    async def game(self, key=None, channel=None):
        g = await Client().open("%s/game/%s?key=%s&v=0.0.1" % (RELAY, channel or self.ch, key or self.key))
        self.clients.append(g)
        return g

    async def say(self, g, star, **more):
        await g.send(json.dumps({"game": dict({"star": star}, **more)}))

    def star(self, **more):
        """This test's star, asking for the finder's credit (11/09/2026: opt-in) unless credit=None."""
        more.setdefault("credit", True)
        if more["credit"] is None:
            del more["credit"]
        return dict({"world": self.world, "x": self.x, "y": self.y}, **more)

    async def mine(self):
        """This test's star on GET /stars (None if it isn't there), after a moment for the Worker's Directory."""
        await self.pause(2)
        status, got = http("GET", "/stars")
        self.assertEqual(status, 200)
        found = [s for s in got["stars"] if (s["world"], s["x"], s["y"]) == (self.world, self.x, self.y)]
        self.assertLessEqual(len(found), 1)
        return found[0] if found else None

    async def test_a_star_shows_with_its_finder_dark_or_not(self):
        g = await self.game()
        await self.say(g, self.star(tier=7))
        s = await self.mine()
        self.assertIsNotNone(s)
        self.assertEqual(sorted(s), ["by", "found", "reports", "seen", "site", "tier", "world", "x", "y"])
        self.assertEqual((s["world"], s["x"], s["y"], s["tier"], s["site"], s["by"], s["reports"]),
                         (self.world, self.x, self.y, 7, None, self.ch, 1))
        self.assertEqual(s["found"], s["seen"])
        with urllib.request.urlopen(http_url() + "/stars", timeout=10) as r:
            self.assertEqual(r.headers.get("Access-Control-Allow-Origin"), "*")

    async def test_without_the_credit_the_finder_isnt_named_but_counted(self):
        # (11/09/2026, Jace: "Name me as a star's finder" is opt-in; a report without "credit" names nobody)
        g = await self.game()
        await self.say(g, self.star(tier=4, credit=None))
        s = await self.mine()
        self.assertEqual((s["by"], s["reports"]), (None, 1))
        for bad in (False, 1, "yes"):
            await self.say(g, self.star(tier=3, credit=bad))          # (not its shape: ignored)
        self.assertEqual((await self.mine())["tier"], 4)
        await asyncio.sleep(1.0)
        await self.say(g, self.star(tier=3))                       # (now asked for: named)
        s = await self.mine()
        self.assertEqual((s["tier"], s["by"], s["reports"]), (3, self.ch, 1))
        with urllib.request.urlopen(http_url() + "/stars", timeout=10) as r:
            self.assertNotIn("credit", r.read().decode())

    async def test_a_landing_site_and_a_hidden_finder(self):
        # (11/09/2026: Shooting Star Info's sites, each in its own square; a watch-coded outstation is never named)
        self.x, self.y = 3008, 3264                                   # (Rimmington mine's square)
        tx = await self.tx(code="moss")
        await next_of(tx, "relay")
        g = await self.game()
        await self.say(g, self.star(tier=3, site="Varrock east bank"))  # (another square's site: ignored)
        self.assertIsNone(await self.mine())
        await self.say(g, self.star(tier=3, site="Rimmington mine"))
        s = await self.mine()
        self.assertEqual((s["site"], s["by"], s["reports"]), ("Rimmington mine", None, 1))
        with urllib.request.urlopen(http_url() + "/stars", timeout=10) as r:
            self.assertNotIn(self.ch, r.read().decode())

    async def test_several_outstations_one_star_tiers_only_fall_and_gone_takes_it_off(self):
        g = await self.game()
        other = "t" + secrets.token_hex(5)
        if WORKER:
            key = admin("POST", "/admin/channels/" + other)[1]["key"]
        else:
            key = "secret-key-" + secrets.token_hex(4)
        h = await self.game(key=key, channel=other)
        await self.say(g, self.star(tier=6))
        await self.pause(2)                                        # (this channel's first, on the Worker too)
        await self.say(h, self.star(tier=6))
        first = await self.mine()
        self.assertEqual(first["by"], self.ch)
        await self.say(h, self.star(tier=5))
        s = await self.mine()
        self.assertEqual((s["tier"], s["found"], s["by"], s["reports"]), (5, first["found"], self.ch, 2))
        self.assertGreaterEqual(s["seen"], first["seen"])
        await self.say(g, self.star(tier=6))                       # (older news: ignored)
        self.assertEqual((await self.mine())["tier"], 5)
        await self.say(g, self.star(gone=True))
        self.assertIsNone(await self.mine())
        await self.say(h, self.star(tier=1))                       # (a late report of it: it stays gone)
        self.assertIsNone(await self.mine())

    async def test_a_bad_one_is_ignored_and_the_rest_of_its_message_still_lands(self):
        tx = await self.tx()
        await next_of(tx, "relay")
        g = await self.game()
        v = await self.watch()
        await self.pause()
        bad = [self.star(tier=0), self.star(tier=10), self.star(tier=6.5), self.star(tier="6"), self.star(tier=True),
               self.star(gone=False), self.star(gone=1), self.star(tier=6, gone=True), self.star(),
               self.star(tier=6, plane=0), dict(self.star(tier=6), x=self.x + 1), dict(self.star(tier=6), world=0),
               dict(self.star(tier=6), world=1000), {"x": 2368, "y": 4416, "world": self.world, "tier": 6},
               {"x": self.x, "y": self.y, "tier": 6}, "star", [self.world, self.x, self.y, 6]]
        for i, st in enumerate(bad):
            if i and i % 4 == 0:
                await asyncio.sleep(1.0)
            await self.say(g, st, world=300 + i)
            got = await next_of(v, "game")
            self.assertEqual((sorted(got), got["world"]), (["at", "world"], 300 + i), st)
        self.assertIsNone(await self.mine())

    async def test_not_from_a_message_that_logs_out_or_holds_and_never_to_viewers(self):
        v = await self.watch()
        tx = await self.tx()
        await next_of(tx, "relay")
        g = await self.game()
        await self.say(g, self.star(tier=8), off=True)
        await self.say(g, self.star(tier=8), paused="wilderness")
        self.assertIsNone(await self.mine())
        await self.say(g, self.star(tier=8), off=False, paused=False)
        self.assertEqual((await self.mine())["tier"], 8)
        heard = []
        try:
            while True:
                kind, line = await v.recv(timeout=0.5)
                if kind == "text":
                    heard.append(line)
        except asyncio.TimeoutError:
            pass
        for line in heard:
            self.assertNotIn("star", line)
        self.assertNotIn("star", json.dumps(http("GET", "/game/%s?code=" % self.ch)[1]))

    async def test_thirty_an_hour_from_one_outstation(self):
        g = await self.game()
        other = self.world % 999 + 1                               # (thirty stars on a world of their own)
        for i in range(30):
            if i and i % 5 == 0:
                await asyncio.sleep(1.05)                          # (5 a second at most)
            await self.say(g, dict(self.star(tier=9), world=other, y=2496 + 128 * (i % 13), x=1088 + 128 * (i // 13)))
        await asyncio.sleep(1.05)
        await self.say(g, self.star(tier=9))                       # (the 31st: ignored)
        self.assertIsNone(await self.mine())

    async def test_only_get(self):
        req = urllib.request.Request(http_url() + "/stars", method="POST", data=b"{}")
        with self.assertRaises(urllib.error.HTTPError) as e:
            urllib.request.urlopen(req, timeout=10)
        self.assertEqual(e.exception.code, 405)

class Kept(Base):
    """What the Array's feed and the stars list keep through a sleep (11/10/2026, Jace: "make the feed keep its events").
    The Worker's Directory holds no sockets, so Cloudflare puts it to sleep about 10 seconds after its last request; both
    had lived only in its memory, so a kill showed for those seconds and was gone. POST /test/forget (the Worker's
    TEST_FORGET; relay.py's --test-forget, where the process's memory is the keeping and nothing's forgotten) puts it to
    sleep as Cloudflare does, and everything here must come back from its storage."""

    async def asyncSetUp(self):
        await super().asyncSetUp()
        status, _, body = await asyncio.to_thread(array_read)
        self.assertEqual(status, 200)
        self.since = body["next"]                                              # (what came before this test: left out)
        self.world = 1 + secrets.randbelow(999)                                # (a star of its own, as Stars's)
        self.x = 1088 + 128 * secrets.randbelow(24)
        self.y = 2496 + 128 * secrets.randbelow(14)

    # (the feed's helpers, ArrayEvents's; the stars', Stars's)
    dink, mine, other, what = ArrayEvents.dink, ArrayEvents.mine, ArrayEvents.other, staticmethod(ArrayEvents.what)
    game, say, star, star_now = Stars.game, Stars.say, Stars.star, Stars.mine

    async def forget(self):
        status = (await asyncio.to_thread(http, "POST", "/test/forget"))[0]
        if status == 404:
            self.skipTest("this relay wasn't started with the test hook (TEST_FORGET, --test-forget)")
        self.assertEqual(status, 204)

    async def test_the_feed_keeps_its_events_their_ids_and_the_cursor(self):
        await self.tx()
        await self.pause()
        await self.dink({"type": "KILL_COUNT", "content": "", "extra": {"boss": "Giant Mole", "count": 2503}})
        await self.dink({"type": "PET", "content": "", "extra": {"petName": "Baby mole"}})
        before = await self.mine(2)
        self.assertEqual(self.what(before), [{"kind": "kill", "boss": "Giant Mole", "kc": 2503},
                                             {"kind": "pet", "name": "Baby mole"}])
        await self.forget()
        self.assertEqual(await self.mine(wait=0), before)                      # (the same events, the same ids)
        status, _, body = await asyncio.to_thread(array_read)
        self.assertEqual((status, body["events"][-2:], body["next"]), (200, before, before[-1]["id"]))
        status, _, body = await asyncio.to_thread(array_read, "?since=%d" % before[0]["id"])
        self.assertEqual(body, {"events": before[1:], "next": before[1]["id"]})
        # (a kill after the sleep, from the same live outstation: its id goes on up, and since finds only it)
        await self.dink({"type": "KILL_COUNT", "content": "", "extra": {"boss": "Giant Mole", "count": 2504}})
        after = (await self.mine(3))[2:]
        self.assertEqual(self.what(after), [{"kind": "kill", "boss": "Giant Mole", "kc": 2504}])
        self.assertGreater(after[0]["id"], before[-1]["id"])
        status, _, body = await asyncio.to_thread(array_read, "?since=%d" % before[-1]["id"])
        self.assertEqual(body, {"events": after, "next": after[0]["id"]})
        await self.forget()
        self.assertEqual(await self.mine(wait=0), before + after)

    async def test_ten_a_minute_holds_through_a_sleep(self):
        await self.tx()
        await self.pause()
        await self.dink({"type": "LEVEL", "content": "", "extra": {"levelledSkills": {s: 70 for s in SKILLS[:8]}}})
        await self.mine(8)
        await self.forget()
        await self.dink({"type": "LEVEL", "content": "", "extra": {"levelledSkills": {s: 71 for s in SKILLS[8:12]}}})
        await self.mine(10)
        got = await self.mine(wait=self.settle * 5)
        self.assertEqual([e["skill"] for e in got], SKILLS[:10])               # (the last two dropped, as without a sleep)

    async def test_the_stars_keep_their_finder_count_and_gone(self):
        g = await self.game()
        await self.say(g, self.star(tier=7))
        first = await self.star_now()
        self.assertEqual((first["tier"], first["by"], first["reports"]), (7, self.ch, 1))
        await self.forget()
        self.assertEqual(await self.star_now(), first)                         # (as it was: when found and seen, its finder)
        other, key = await self.other()
        h = await self.game(key=key, channel=other)
        await self.say(h, self.star(tier=6))
        s = await self.star_now()
        self.assertEqual((s["tier"], s["found"], s["by"], s["reports"]), (6, first["found"], self.ch, 2))
        await self.forget()
        await self.say(g, self.star(tier=5))                                   # (this one told of it already: still two)
        s = await self.star_now()
        self.assertEqual((s["tier"], s["by"], s["reports"]), (5, self.ch, 2))
        await self.say(h, self.star(gone=True))
        self.assertIsNone(await self.star_now())
        await self.forget()
        await self.say(g, self.star(tier=1))                                   # (a late report after the sleep: still gone)
        self.assertIsNone(await self.star_now())

    # The open outstations' list (GET /channels; 11/10/2026, Jace's decision): in the Directory's storage too, so a read
    # after a sleep doesn't ask every channel handed out (waking each). Following's "where" and the "activity" are never
    # stored (SAFETY.md): in memory only, so a sleep loses them, and the Directory asks a live channel with the plugin
    # once a wake to have them back. On the Worker, GET /test/listed/<channel> shows what's stored of a row and how often
    # the Directory's asked that channel since it woke (relay.py keeps nothing and asks nothing: 404, and those parts
    # are skipped).

    def rows(self):
        return {c["channel"]: c for c in http("GET", "/channels")[1]["channels"]}

    async def store(self, channel=None, body=None):
        """What the Worker's Directory stores of a channel's row and how often it's asked it since it woke ({"stored",
        "asked"}); with a body, those fields put into the row first, as a telling that was lost. None on relay.py."""
        status, got = await asyncio.to_thread(http, "POST" if body is not None else "GET", "/test/listed/" + (channel or self.ch), body)
        if status == 404 and not WORKER:
            return None
        self.assertEqual(status, 200)
        return got

    def never_stored(self, got):
        if got is not None:
            self.assertIsNotNone(got["stored"])
            for k in ("where", "whereAt", "activity", "activityAt"):
                self.assertNotIn(k, got["stored"]["row"])
                self.assertNotIn('"%s"' % k, json.dumps(got["stored"]))

    async def test_the_open_list_comes_back_from_storage_without_asking_a_channel(self):
        tx = await self.tx()
        await next_of(tx, "relay")
        desk = await self.desk()
        await desk.send(json.dumps({"title": "barrows"}))
        dark, key = await self.other()
        d = await Client().open("%s/transmit/%s?key=%s&code=" % (RELAY, dark, key))   # (on air once, then dark)
        await next_of(d, "relay")
        d.close()
        await self.pause(3)
        before = await asyncio.to_thread(self.rows)
        self.assertEqual((before[self.ch]["live"], before[self.ch]["title"], before[dark]["live"]), (True, "barrows", False))
        await self.forget()
        got = await asyncio.to_thread(self.rows)
        self.assertEqual((got[self.ch], got[dark]), (before[self.ch], before[dark]))
        for ch in (self.ch, dark):
            s = await self.store(ch)
            if s is not None:
                self.assertEqual(s["asked"], 0)                                # (answered from storage alone)
                self.assertEqual(s["stored"]["row"]["live"], ch == self.ch)
                self.never_stored(s)
        # (and what changes after the sleep is told, and kept through the next)
        await desk.send(json.dumps({"title": "the mole"}))
        await self.pause(2)
        await self.forget()
        self.assertEqual((await asyncio.to_thread(self.rows))[self.ch]["title"], "the mole")

    async def test_where_and_activity_are_never_stored_and_come_back_after_a_sleep(self):
        LUMBRIDGE, MINING = Following.LUMBRIDGE, Activity.MINING
        tx = await self.tx()
        await next_of(tx, "relay")
        g = await self.game()
        await g.send(json.dumps({"game": {"where": LUMBRIDGE, "activity": MINING}}))
        await self.pause(2)
        got = (await asyncio.to_thread(self.rows))[self.ch]
        self.assertEqual((got["where"], got["activity"], got["game"]), (LUMBRIDGE, MINING, True))
        s = await self.store()
        self.never_stored(s)
        if s is not None:
            self.assertEqual((s["stored"]["row"]["live"], s["stored"]["row"]["game"]), (True, True))
        await self.forget()
        s = await self.store()
        self.never_stored(s)                                                   # (none to bring back from storage)
        if s is not None:
            self.assertEqual(s["asked"], 0)
        got = (await asyncio.to_thread(self.rows))[self.ch]                     # (back on the very first read: asked once)
        self.assertEqual((got["where"], got["activity"]), (LUMBRIDGE, MINING))
        await asyncio.to_thread(self.rows)
        s = await self.store()
        if s is not None:
            self.assertEqual(s["asked"], 1)                                    # (once a wake, not once a read)
        self.never_stored(s)
        # (dropped: gone from the list, and still nothing stored of it, through another sleep)
        await g.send(json.dumps({"game": {"where": None, "activity": None}}))
        await self.pause(2)
        got = (await asyncio.to_thread(self.rows))[self.ch]
        self.assertEqual((got["where"], got["activity"]), (None, None))
        await self.forget()
        got = (await asyncio.to_thread(self.rows))[self.ch]
        self.assertEqual((got["live"], got["where"], got["activity"]), (True, None, None))
        self.never_stored(await self.store())

    async def test_a_channel_gone_dark_while_the_list_slept(self):
        tx = await self.tx()
        await next_of(tx, "relay")
        await self.pause(2)
        self.assertTrue((await asyncio.to_thread(self.rows))[self.ch]["live"])
        await self.forget()
        tx.close()                                                             # (told to the Directory, which wakes for it)
        await self.pause(2)
        await self.forget()
        self.assertFalse((await asyncio.to_thread(self.rows))[self.ch]["live"])
        s = await self.store()
        if s is None:
            return                                                             # (relay.py: nothing to lose)
        self.assertEqual(s["asked"], 0)
        # a telling lost (or a channel reset without telling): the row says live, and nothing tells it otherwise. It's
        # asked when it's LISTING_CHECK_S old, and shows dark from then on
        s = await self.store(body={"live": True, "open": True, "where": {"x": 3264, "y": 3264}})
        self.assertTrue(s["stored"]["row"]["live"])
        self.never_stored(s)                                                   # (not even the test's own hook stores it)
        await self.forget()
        self.assertTrue((await asyncio.to_thread(self.rows))[self.ch]["live"])  # (stale, for now)
        await asyncio.sleep(LISTING_CHECK_S + 0.5)
        await self.forget()
        self.assertFalse((await asyncio.to_thread(self.rows))[self.ch]["live"])
        self.assertEqual((await self.store())["asked"], 1)
        # with the plugin connected, a row that says live is asked once a wake: dark again at the first read
        g = await self.game()
        await self.pause(2)
        self.assertTrue((await asyncio.to_thread(self.rows))[self.ch]["game"])
        await self.store(body={"live": True, "open": True})
        await self.forget()
        got = (await asyncio.to_thread(self.rows))[self.ch]
        self.assertEqual((got["live"], got["game"]), (False, True))
        self.assertEqual((await self.store())["asked"], 1)
        g.close()

    async def test_thirty_stars_an_hour_holds_through_a_sleep(self):
        g = await self.game()
        other = self.world % 999 + 1                                           # (thirty stars on a world of their own)
        for i in range(30):
            if i and i % 5 == 0:
                await asyncio.sleep(1.05)                                      # (5 a second at most)
            if i == 20:
                await self.pause(2)
                await self.forget()
            await self.say(g, dict(self.star(tier=9), world=other, y=2496 + 128 * (i % 13), x=1088 + 128 * (i // 13)))
        await asyncio.sleep(1.05)
        await self.say(g, self.star(tier=9))                                   # (the 31st: ignored, as without a sleep)
        self.assertIsNone(await self.star_now())


class Backfill(Base):
    """The keeper's backfill (11/10/2026, Jace: his pet, lost to the Directory's sleeps, back on the record): POST
    /admin/array {"channel", "events": [{"kind", ..., "at"}]} puts a real event the feed lost back in its place by when
    it happened, with an id between its neighbours' (or, with no room there, a new one at the end, its time kept), for a
    channel handed out and on the open list. Each event exactly as a report would have made it; counted against nothing."""

    async def asyncSetUp(self):
        await super().asyncSetUp()
        if not WORKER:
            tx = await self.tx()                                               # (relay.py: the channel's claimed by it)
            await next_of(tx, "relay")
            tx.close()
            await self.pause()

    @staticmethod
    def keeper(body, auth=True):
        """(status, JSON or None) of POST /admin/array, an error's JSON too."""
        req = urllib.request.Request(http_url() + "/admin/array", method="POST", data=json.dumps(body).encode(),
                                     headers={"Content-Type": "application/json",
                                              **({"Authorization": "Bearer " + ADMIN} if auth else {})})
        try:
            with urllib.request.urlopen(req, timeout=10) as r:
                return r.status, json.loads(r.read() or b"null")
        except urllib.error.HTTPError as e:
            raw = e.read()
            try:
                return e.code, json.loads(raw) if raw else None
            except ValueError:
                return e.code, None

    async def put_back(self, events, channel=None, auth=True):
        return await asyncio.to_thread(self.keeper, {"channel": channel or self.ch, "events": events}, auth)

    @staticmethod
    def feed():
        return array_since(0)

    def in_order(self, feed):
        ids = [e["id"] for e in feed]
        self.assertEqual(ids, sorted(set(ids)))

    async def test_a_lost_pet_goes_back_in_its_place_and_stays(self):
        await self.tx()
        await self.pause()
        since = (await asyncio.to_thread(array_read))[2]["next"]
        await ArrayEvents.dink(self, {"type": "KILL_COUNT", "content": "", "extra": {"boss": "Giant Mole", "count": 2500}})
        await ArrayEvents.dink(self, {"type": "KILL_COUNT", "content": "", "extra": {"boss": "Giant Mole", "count": 2501}})
        self.since = since
        a, b = await ArrayEvents.mine(self, 2)
        at = a["at"] - 60_000                                                  # (a minute before the first kill)
        status, got = await self.put_back([{"kind": "pet", "name": "Baby mole", "at": at}])
        self.assertEqual(status, 200)
        pet = got["events"][0]
        self.assertEqual(list(pet), ["id", "at", "channel", "kind", "name"])
        self.assertEqual({k: pet[k] for k in ("at", "channel", "kind", "name")},
                         {"at": at, "channel": self.ch, "kind": "pet", "name": "Baby mole"})
        self.assertLess(pet["id"], a["id"])
        feed = await asyncio.to_thread(self.feed)
        self.in_order(feed)
        self.assertIn(pet, feed)
        mine = [e for e in feed if e["channel"] == self.ch]
        self.assertEqual(mine, [pet, a, b])                                    # (in its place by when it happened)
        status, _, body = await asyncio.to_thread(array_read, "?since=%d" % b["id"])
        self.assertEqual(body, {"events": [], "next": b["id"]})                # (a page past it isn't sent it again)
        status, _, body = await asyncio.to_thread(array_read, "?since=%d" % (pet["id"] - 1))
        self.assertEqual(body["events"][0], pet)
        if (await asyncio.to_thread(http, "POST", "/test/forget"))[0] == 204:
            self.assertIn(pet, await asyncio.to_thread(self.feed))             # (kept, as the rest)

    async def test_with_no_room_between_its_neighbours_it_gets_a_new_id_and_keeps_its_time(self):
        t = int(time.time() * 1000) - 2000
        status, got = await self.put_back([{"kind": "clog", "name": "Tanzanite fang", "at": t},
                                           {"kind": "clog", "name": "Magic fang", "at": t},
                                           {"kind": "clog", "name": "Serpentine visage", "at": t + 1}])
        self.assertEqual(status, 200)
        self.assertEqual([e["id"] for e in got["events"]][1:], [got["events"][0]["id"] + 1, got["events"][0]["id"] + 2])
        before = max(e["id"] for e in await asyncio.to_thread(self.feed))
        status, got = await self.put_back([{"kind": "clue", "tier": "Elite", "count": 12, "at": t}])
        self.assertEqual(status, 200)
        late = got["events"][0]
        self.assertEqual((late["at"], late["tier"], late["count"]), (t, "Elite", 12))
        self.assertGreater(late["id"], before)                                 # (at the end, as a new event would be)
        feed = await asyncio.to_thread(self.feed)
        self.in_order(feed)
        self.assertEqual(feed[-1], late)

    async def test_every_kind_exactly_as_a_report_makes_it_and_counted_against_nothing(self):
        t = int(time.time() * 1000) - 5000
        lost = [{"kind": "kill", "boss": "Vorkath", "kc": 412, "at": t}, {"kind": "loot", "name": "Skeletal visage", "value": 1206000, "at": t},
                {"kind": "loot", "value": 1000000, "at": t}, {"kind": "clog", "at": t}, {"kind": "clue", "at": t}, {"kind": "pet", "at": t}]
        lost += [{"kind": "level", "skill": s, "level": 80, "at": t + 1} for s in SKILLS]   # (twelve: past ten a minute)
        status, got = await self.put_back(lost)
        self.assertEqual(status, 200)
        self.assertEqual([{k: v for k, v in e.items() if k not in ("id", "channel")} for e in got["events"]], lost)
        await self.tx()
        await self.pause()
        self.since = (await asyncio.to_thread(array_read))[2]["next"]
        await ArrayEvents.dink(self, {"type": "LEVEL", "content": "", "extra": {"levelledSkills": {s: 81 for s in SKILLS}}})
        await ArrayEvents.mine(self, 10)
        self.assertEqual(len(await ArrayEvents.mine(self, wait=self.settle * 5)), 10)   # (its own ten still there)

    async def test_only_the_keeper_only_post_only_an_open_outstation(self):
        pet = [{"kind": "pet", "name": "Baby mole", "at": int(time.time() * 1000) - 1000}]
        self.assertEqual((await self.put_back(pet, auth=False))[0], 403)
        self.assertEqual((await asyncio.to_thread(admin, "GET", "/admin/array"))[0], 405)
        status, got = await self.put_back(pet, channel="t" + secrets.token_hex(5))
        self.assertEqual((status, sorted(got)), (404, ["error"]))
        hidden = "t" + secrets.token_hex(5)
        if WORKER:
            key = (await asyncio.to_thread(admin, "POST", "/admin/channels/" + hidden))[1]["key"]
        else:
            key = "secret-key-" + secrets.token_hex(4)
        c = await Client().open("%s/transmit/%s?key=%s&code=moss" % (RELAY, hidden, key))
        self.clients.append(c)
        await next_of(c, "relay")
        status, got = await self.put_back(pet, channel=hidden)
        self.assertEqual((status, sorted(got)), (409, ["error"]))
        self.assertNotIn(hidden, json.dumps(await asyncio.to_thread(self.feed)))

    async def test_anything_not_an_events_shape_is_refused_and_nothing_added(self):
        now = int(time.time() * 1000)
        before = await asyncio.to_thread(self.feed)
        for bad in ([], [{"kind": "quest", "at": now}], [{"kind": "kill", "boss": "Vorkath", "at": now}],
                    [{"kind": "kill", "boss": "Vorkath", "kc": 1.5, "at": now}], [{"kind": "level", "skill": "9", "level": 5, "at": now}],
                    [{"kind": "loot", "value": 999999, "at": now}], [{"kind": "pet", "name": "Baby\nmole", "at": now}],
                    [{"kind": "pet", "name": "x" * 61, "at": now}], [{"kind": "pet", "name": "", "at": now}],
                    [{"kind": "pet", "boss": "Giant Mole", "at": now}], [{"kind": "pet", "at": now + 600_000}],
                    [{"kind": "pet"}], [{"kind": "pet", "at": "1794286233000"}], [{"kind": "pet", "at": -1}],
                    [{"kind": "pet", "name": "Baby mole", "at": now}, "pet"],
                    [{"kind": "pet", "at": now - 1000}] * 51):
            status, got = await self.put_back(bad)
            self.assertEqual((status, sorted(got)), (400, ["error"]), bad)
        status, got = await asyncio.to_thread(self.keeper, {"events": [{"kind": "pet", "at": now}]})
        self.assertEqual(status, 400)
        self.assertEqual(await asyncio.to_thread(self.feed), before)

    async def test_none_older_than_a_full_feed_holds(self):
        t = int(time.time() * 1000) - 600_000
        for n in range(10):                                                    # (the feed filled: 500 of this channel's)
            status, _ = await self.put_back([{"kind": "level", "skill": "Mining", "level": 50, "at": t + 50 * n + i} for i in range(50)])
            self.assertEqual(status, 200)
        feed = await asyncio.to_thread(self.feed)
        self.assertEqual(len(feed), 500)
        oldest = feed[0]["at"]
        status, got = await self.put_back([{"kind": "pet", "at": oldest - 1}])
        self.assertEqual((status, sorted(got)), (400, ["error"]))
        status, got = await self.put_back([{"kind": "pet", "at": oldest}])
        self.assertEqual(status, 200)
        self.assertEqual(len(await asyncio.to_thread(self.feed)), 500)


async def nothing_game(c, wait=0.5):
    """The game line someone just come is sent, or None if none comes in `wait` seconds."""
    try:
        return await next_of(c, "game", timeout=wait)
    except asyncio.TimeoutError:
        return None


class Invites(Base):
    """An outstation's invites (11/07/2026): it hands out channels of its own, three to begin with and one more every 10
    hours on air (the earning is relay-py/test_relay.py's: ten hours on air can't be waited for here). An invite's code
    attaches a client as the keeper's does, and the new channel says whose invite it came from."""

    async def asyncSetUp(self):
        await super().asyncSetUp()
        if not WORKER:
            tx = await self.tx()           # (relay.py: a channel is handed out once its first transmitter claims it)
            await next_of(tx, "relay")
            tx.close()
            await self.pause(2)

    def status(self, key=None):
        return http("GET", "/invites/%s?key=%s" % (self.ch, key or self.key))

    def invite(self, player, key=None):
        return http("POST", "/invites/" + self.ch, {"key": key or self.key, "player": player})

    async def given(self, name):
        """A name with an outstation already: handed out by the keeper (the Worker), or claimed (relay.py)."""
        if WORKER:
            self.assertEqual(admin("POST", "/admin/channels/" + name)[0], 200)
        else:
            c = await Client().open("%s/transmit/%s?key=its-own-key-%s" % (RELAY, name, secrets.token_hex(3)))
            self.clients.append(c)
            await next_of(c, "relay")

    async def test_a_new_outstation_has_three_and_has_used_none(self):
        status, got = self.status()
        self.assertEqual(status, 200)
        self.assertEqual(sorted(got), ["earned", "hours", "left", "next_in_s", "used"])
        self.assertEqual((got["left"], got["earned"], got["used"], got["hours"]), (3, 3, [], 0))
        self.assertTrue(35990 <= got["next_in_s"] <= 36000, got["next_in_s"])

    async def test_an_invite_is_a_station_key_for_a_new_channel_that_says_whose_it_was(self):
        name = "i" + secrets.token_hex(5)
        status, got = self.invite(name)
        self.assertEqual(status, 200)
        self.assertEqual((got["channel"], got["left"]), (name, 2))
        self.assertTrue(got["code"].startswith("outstation1:"))
        raw = got["code"][len("outstation1:"):]
        info = json.loads(base64.urlsafe_b64decode(raw + "=" * (-len(raw) % 4)))
        self.assertEqual(info, {"relay": RELAY, "channel": name, "key": got["key"]})
        tx = await Client().open("%s/transmit/%s?key=%s&code=" % (RELAY, name, info["key"]))
        self.clients.append(tx)
        self.assertTrue((await next_of(tx, "relay"))["live"])
        status, st = self.status()
        self.assertEqual((status, st["left"], st["used"]), (200, 2, [name]))
        await self.pause(2)
        rows = {c["channel"]: c for c in http("GET", "/channels")[1]["channels"]}
        self.assertEqual(rows[name]["by"], self.ch)
        self.assertIsNone(rows[self.ch]["by"])
        if WORKER:
            kept = {c["channel"]: c for c in admin("GET", "/admin/channels")[1]}
            self.assertEqual(kept[name]["by"], self.ch)
            self.assertIsNone(kept[self.ch]["by"])                  # (the keeper's own)

    async def test_a_name_with_an_outstation_already_isnt_invited(self):
        other = "i" + secrets.token_hex(5)
        await self.given(other)
        status, got = self.invite(other)
        self.assertEqual((status, got["error"]), (409, "that name has an outstation already"))
        name = "i" + secrets.token_hex(5)
        self.assertEqual(self.invite(name)[0], 200)
        self.assertEqual(self.invite(name)[0], 409)                   # (it has one now: this invite's)
        self.assertEqual(self.status()[1]["used"], [name])            # (nothing refused was counted)

    async def test_the_wrong_key_and_a_channel_nobody_has_are_refused(self):
        self.assertEqual(self.status(key="not-the-key-at-all")[0], 403)
        status, got = self.invite("i" + secrets.token_hex(5), key="not-the-key-at-all")
        self.assertEqual(status, 403)
        self.assertTrue(got["error"])
        self.assertEqual(self.status()[1]["left"], 3)
        self.assertEqual(http("GET", "/invites/none%s?key=%s" % (secrets.token_hex(4), self.key))[0], 404)

    async def test_three_to_begin_with_and_then_none_left(self):
        names = ["i" + secrets.token_hex(5) for _ in range(4)]
        self.assertEqual([self.invite(n)[1].get("left") for n in names[:3]], [2, 1, 0])
        status, got = self.invite(names[3])
        self.assertEqual((status, got["error"]), (409, "no invites left: one more every 10 hours on air"))
        got = self.status()[1]
        self.assertEqual((got["left"], got["earned"], got["used"]), (0, 3, names[:3]))

    async def test_your_own_name_and_names_that_arent_a_players_are_refused(self):
        got = {name: self.invite(name) for name in (self.ch, "Moss", "moss man", "thirteenchars")}
        self.assertEqual([status for status, _ in got.values()], [400] * 4)
        self.assertEqual(got[self.ch][1]["error"], "that's your own outstation")
        self.assertEqual(self.status()[1]["used"], [])

    @unittest.skipUnless(WORKER, "a new key and taking back: the keeper's, the Worker only")
    async def test_a_new_key_keeps_whose_invite_it_was_and_taking_back_takes_nothing_back(self):
        name = "i" + secrets.token_hex(5)
        self.assertEqual(self.invite(name)[0], 200)
        self.assertEqual(admin("POST", "/admin/channels/" + name)[0], 200)   # (the keeper's new key for it)
        self.assertEqual({c["channel"]: c for c in admin("GET", "/admin/channels")[1]}[name]["by"], self.ch)
        self.assertEqual(admin("DELETE", "/admin/channels/" + name)[0], 204)
        self.assertEqual(self.status()[1]["used"], [name])                  # (the invite stays spent)
        other = "i" + secrets.token_hex(5)
        self.assertEqual(self.invite(other)[0], 200)
        self.assertEqual(admin("DELETE", "/admin/channels/" + self.ch)[0], 204)
        self.assertEqual({c["channel"]: c for c in admin("GET", "/admin/channels")[1]}[other]["by"], self.ch)

    async def test_z_past_five_posts_an_hour_wait(self):
        codes = [self.invite("i" + secrets.token_hex(5))[0] for _ in range(6)]
        self.assertEqual(codes, [200, 200, 200, 409, 409, 429])

    # pending (11/08/2026): an invite not taken up yet (its key never used) is left off GET /channels and only counted
    # under its inviter, for the keys beside it on outside.wiki's globe; never named

    async def open_list(self):
        """GET /channels as (its rows by channel, its pending counts by inviter, the raw body), after a moment for the
        Worker's channels to tell the Directory."""
        await self.pause(2)
        with urllib.request.urlopen(http_url() + "/channels", timeout=10) as r:
            raw = r.read().decode()
        got = json.loads(raw)
        for p in got["pending"]:
            self.assertEqual(sorted(p), ["by", "n"])
        bys = [p["by"] for p in got["pending"]]
        self.assertEqual(bys, sorted(bys))                                   # (sorted by inviter, each once)
        self.assertEqual(len(bys), len(set(bys)))
        return {c["channel"]: c for c in got["channels"]}, {p["by"]: p["n"] for p in got["pending"]}, raw

    async def test_an_invite_not_taken_up_is_counted_under_its_inviter_and_never_named(self):
        a, b = "i" + secrets.token_hex(5), "i" + secrets.token_hex(5)
        self.assertEqual(self.invite(a)[0], 200)
        rows, pending, raw = await self.open_list()
        self.assertEqual(pending.get(self.ch), 1)
        self.assertIn(self.ch, rows)
        self.assertNotIn(a, rows)
        self.assertNotIn(a, raw)                                             # (nowhere in the answer)
        self.assertEqual(self.invite(b)[0], 200)
        rows, pending, raw = await self.open_list()
        self.assertEqual(pending.get(self.ch), 2)
        for name in (a, b):
            self.assertNotIn(name, raw)

    async def test_an_invite_taken_up_is_listed_and_no_longer_counted(self):
        a, b = "i" + secrets.token_hex(5), "i" + secrets.token_hex(5)
        key_a, key_b = self.invite(a)[1]["key"], self.invite(b)[1]["key"]
        self.assertEqual((await self.open_list())[1].get(self.ch), 2)
        tx = await Client().open("%s/transmit/%s?key=%s&code=" % (RELAY, a, key_a))   # (the friend's client, attached)
        self.clients.append(tx)
        await next_of(tx, "relay")
        rows, pending, raw = await self.open_list()
        self.assertEqual(pending.get(self.ch), 1)
        self.assertEqual((rows[a]["by"], rows[a]["live"]), (self.ch, True))
        self.assertNotIn(b, raw)
        self.assertEqual(http("GET", "/invites/%s?key=not-the-key-at-all" % b)[0], 403)   # (a wrong key takes nothing up)
        self.assertEqual((await self.open_list())[1].get(self.ch), 1)
        self.assertEqual(http("GET", "/invites/%s?key=%s" % (b, key_b))[0], 200)       # (its key, used for anything)
        rows, pending, _ = await self.open_list()
        self.assertNotIn(self.ch, pending)
        self.assertEqual((rows[b]["by"], rows[b]["live"]), (self.ch, False))

    async def test_a_coded_inviters_invites_arent_counted(self):
        a = "i" + secrets.token_hex(5)
        self.assertEqual(self.invite(a)[0], 200)
        self.assertEqual((await self.open_list())[1].get(self.ch), 1)
        tx = await self.tx("a-code")                                         # (the inviter: friends only now)
        await next_of(tx, "relay")
        rows, pending, raw = await self.open_list()
        self.assertNotIn(self.ch, rows)
        self.assertNotIn(self.ch, pending)
        self.assertNotIn(self.ch, raw)                                       # (nothing said of a hidden outstation)
        self.assertNotIn(a, raw)

    @unittest.skipUnless(WORKER, "the keeper's own, a new key and taking back: the keeper's, the Worker only")
    async def test_the_keepers_own_are_listed_as_before_and_a_taken_back_inviters_invites_arent_counted(self):
        own = "k" + secrets.token_hex(5)
        self.assertEqual(admin("POST", "/admin/channels/" + own)[0], 200)   # (the keeper's, never used: listed, as ever)
        a = "i" + secrets.token_hex(5)
        self.assertEqual(self.invite(a)[0], 200)
        rows, pending, _ = await self.open_list()
        self.assertIsNone(rows[own]["by"])
        self.assertNotIn(own, pending)
        self.assertEqual(pending.get(self.ch), 1)
        self.assertEqual(admin("POST", "/admin/channels/" + a)[0], 200)     # (a new key from the keeper: still waiting)
        rows, pending, _ = await self.open_list()
        self.assertEqual(pending.get(self.ch), 1)
        self.assertNotIn(a, rows)
        self.assertEqual(admin("DELETE", "/admin/channels/" + self.ch)[0], 204)
        rows, pending, raw = await self.open_list()
        self.assertNotIn(self.ch, pending)
        self.assertNotIn(a, raw)


VECTORS = json.load(open(os.path.join(HERE, "fixtures", "record", "vectors.json"), encoding="utf-8"))
RECORD_CHUNKS = 6      # (a channel's chunks at most, as the relays under test are started; 2000 as they're run)


def raw(method, path, data=None, headers=None):
    """(status, headers, body bytes) from any route, with any body."""
    req = urllib.request.Request(http_url() + path, method=method, data=data, headers=headers or {})
    try:
        with urllib.request.urlopen(req, timeout=10) as r:
            return r.status, r.headers, r.read()
    except urllib.error.HTTPError as e:
        return e.code, e.headers, e.read()


def b64u(b):
    return base64.urlsafe_b64encode(b).rstrip(b"=").decode()


def made(key, label):
    return hmac.new(key, label.encode(), hashlib.sha256).digest()


def year_key(i):
    """A record key of a test's own (the vectors' for 0), and its tokens."""
    key = bytes.fromhex(VECTORS["key_hex"]) if i == 0 else hashlib.sha256(b"another record key %d" % i).digest()
    return key, b64u(made(key, "outstation record read v1")), b64u(made(key, "outstation record write v1"))


def a_chunk(n, size=200):
    """A made-up sealed chunk: the format byte, then bytes that only look encrypted (the relay can't tell)."""
    body = bytes([1]) + hashlib.sha256(b"chunk %d" % n).digest() * (size // 32 + 1)
    return body[:size]


def chunk_id(n):
    return b64u(hashlib.sha256(b"id %d" % n).digest()[:16])


class YourYear(Base):
    """Your year (11/10/2026; docs/record.md): the owner's own where, activity and events to their own desk only, as
    {"mine"} lines; the record's sealed chunks kept, read with the record key's read token and refused as plaintext; the
    public summary checked, kept and taken down. Known-key vectors: tests/fixtures/record/vectors.json (WebCrypto's)."""

    LUMBRIDGE = {"x": 3264, "y": 3264}

    async def claim(self):
        """(relay.py: a channel is claimed by its first socket with a key; the Worker's are handed out already)"""
        if not WORKER:
            await self.desk()
            await self.pause()

    async def game(self):
        g = await Client().open("%s/game/%s?key=%s&v=0.0.1" % (RELAY, self.ch, self.key))
        self.clients.append(g)
        return g

    async def lines(self, c, wait=0.8):
        """Every text line a client gets in `wait` seconds."""
        out, end = [], time.monotonic() + wait
        while True:
            try:
                k, line = await c.recv(timeout=max(0.01, end - time.monotonic()))
            except asyncio.TimeoutError:
                return out
            if k == "close":
                return out
            if k == "text":
                out.append(line)

    def rec(self, method, path="", data=None, key=None, token=None, query=""):
        headers = {"Authorization": "Bearer " + token} if token else {}
        if data is not None and not isinstance(data, bytes):
            data, headers["Content-Type"] = json.dumps(data).encode(), "application/json"
        q = ("key=" + urllib.parse.quote(key)) if key else ""
        q = "&".join(x for x in (q, query) if x)
        return raw(method, "/record/%s%s%s" % (self.ch, path, "?" + q if q else ""), data, headers)

    def register(self, i=0, key=None):
        _, read, write = year_key(i)
        return self.rec("POST", "/token", {"read": read, "write": write}, key=key or self.key)[0]

    def listing(self, token, query=""):
        st, h, body = self.rec("GET", token=token, query=query)
        return st, json.loads(body) if body else None

    def year(self, method, body=None, key=None, token=None):
        headers = {"Authorization": "Bearer " + token} if token else {}
        data = None
        if body is not None:
            data = body if isinstance(body, bytes) else json.dumps(body).encode()
            headers["Content-Type"] = "application/json"
        st, h, out = raw(method, "/year/%s%s" % (self.ch, "?key=" + urllib.parse.quote(key) if key else ""), data, headers)
        return st, h, json.loads(out) if out else None

    # ---- the desk's {"mine"} lines ----

    async def test_the_desk_hears_its_own_where_and_activity_and_no_viewer_does(self):
        tx = await self.tx()
        await next_of(tx, "relay")
        desk, v = await self.desk(), await self.watch()
        look = await self.connect("watch", "look=1")
        g = await self.game()
        await self.pause()
        began = time.time() * 1000
        await g.send(json.dumps({"game": {"world": 302, "where": dict(self.LUMBRIDGE, world=302),
                                          "activity": {"kind": "skilling", "skill": "Mining"}}}))
        w = await next_of(desk, "mine")
        self.assertEqual(w["where"], {"x": 3264, "y": 3264, "world": 302})
        self.assertTrue(began - 1000 <= w["at"] <= time.time() * 1000 + 1000, w)
        a = await next_of(desk, "mine")
        self.assertEqual(a["activity"], {"kind": "skilling", "skill": "Mining"})
        self.assertLess(abs(a["at"] - w["at"]), 1000)
        await asyncio.sleep(0.25)
        await g.send(json.dumps({"game": {"where": None, "activity": None}}))
        self.assertIsNone((await next_of(desk, "mine"))["where"])
        self.assertIsNone((await next_of(desk, "mine"))["activity"])
        for c in (v, look, tx, g):
            for line in await self.lines(c, 0.5):
                for word in ('"mine"', '"where"', '"activity"'):
                    self.assertNotIn(word, line)

    async def test_dark_and_coded_too_and_a_new_desk_gets_whats_held(self):
        tx = await self.tx("moss")
        await next_of(tx, "relay")
        tx.close()                                             # (dark, with a watch code: still the owner's own)
        await self.pause(2)
        g = await self.game()
        await self.pause()
        await g.send(json.dumps({"game": {"where": self.LUMBRIDGE, "activity": {"kind": "idle"}}}))
        await self.pause(3)
        desk = await self.desk()
        got = {}
        while len(got) < 2:
            m = await next_of(desk, "mine")
            got.update({k: v for k, v in m.items() if k != "at"})
        self.assertEqual(got, {"where": self.LUMBRIDGE, "activity": {"kind": "idle"}})
        g.close()                                              # (the plugin gone: what it said is dropped)
        cleared = {}
        while len(cleared) < 2:
            m = await next_of(desk, "mine")
            cleared.update({k: v for k, v in m.items() if k != "at"})
        self.assertEqual(cleared, {"where": None, "activity": None})

    # ---- the exact path (11/10/2026: "here", the owner's own desk only, never kept) ----

    HERE = {"x": 3222, "y": 3218, "plane": 0, "world": 302}

    async def test_the_exact_path_reaches_its_own_desk_only_and_is_never_kept(self):
        tx = await self.tx()
        await next_of(tx, "relay")
        desk, v = await self.desk(), await self.watch()
        look = await self.connect("watch", "look=1")
        g = await self.game()
        await self.pause()
        await g.send(json.dumps({"game": {"world": 302, "here": self.HERE}}))
        h = await next_of(desk, "mine")
        self.assertEqual(sorted(h), ["at", "here"])
        self.assertEqual(h["here"], self.HERE)
        # (another within 2 s: ignored; one at sea, past the old map, and Zanaris, after it: taken)
        await g.send(json.dumps({"game": {"here": dict(self.HERE, x=3223)}}))
        await asyncio.sleep(2.2)
        sea = {"x": 3180, "y": 2420, "plane": 0, "world": 302}
        await g.send(json.dumps({"game": {"here": sea}}))
        self.assertEqual((await next_of(desk, "mine"))["here"], sea)
        # (never on a viewer's, a looker's, the transmitter's or the plugin's own socket)
        for c in (v, look, tx, g):
            for line in await self.lines(c, 0.5):
                for word in ('"mine"', '"plane"'):
                    self.assertNotIn(word, line)
        # (never kept: not in GET /game, not on GET /channels, and a desk that comes later gets none of it)
        self.assertNotIn("3180", json.dumps(http("GET", "/game/%s?code=" % self.ch)[1]))
        await self.pause(2)
        self.assertNotIn("3180", json.dumps(http("GET", "/channels")[1]))
        late = await self.desk()
        for line in await self.lines(late, 1.0):
            self.assertNotIn('"here"', line)
        # (null clears it, once; logging out clears it)
        await asyncio.sleep(2.2)
        await g.send(json.dumps({"game": {"here": None}}))
        self.assertIsNone((await next_of(desk, "mine"))["here"])

    async def test_a_bad_here_is_ignored_and_logging_out_clears_it(self):
        await self.claim()
        desk = await self.desk()
        g = await self.game()
        await self.pause()
        for i, bad in enumerate([{"x": 3222, "y": 3218, "plane": 4, "world": 302}, {"x": 3222, "y": 3218, "world": 302},
                                 {"x": 3222, "y": 3218, "plane": 0, "world": 0}, {"x": 959, "y": 3218, "plane": 0, "world": 302},
                                 {"x": 3222, "y": 9618, "plane": 0, "world": 302}, {"x": 3222.5, "y": 3218, "plane": 0, "world": 302},
                                 {"x": "3222", "y": 3218, "plane": 0, "world": 302}, [3222, 3218, 0, 302],
                                 dict(self.HERE, tile=1)]):
            if i and i % 4 == 0:
                await asyncio.sleep(1.0)                           # (5 a second at most)
            await g.send(json.dumps({"game": {"here": bad}}))
        await asyncio.sleep(1.0)
        await g.send(json.dumps({"game": {"here": {"x": 2368, "y": 4416, "plane": 0, "world": 302}}}))   # (Zanaris)
        got = await next_of(desk, "mine")
        self.assertEqual(got["here"], {"x": 2368, "y": 4416, "plane": 0, "world": 302})
        await asyncio.sleep(0.3)
        await g.send(json.dumps({"game": {"off": True}}))
        self.assertIsNone((await next_of(desk, "mine"))["here"])

    async def test_its_own_events_reach_its_desk_live_or_dark(self):
        desk = await self.desk()                               # (dark: no transmitter)
        await self.pause()
        payload = {"type": "KILL_COUNT", "content": "", "extra": {"boss": "Vorkath", "count": 412}}
        self.assertEqual(await post("/dink/%s?key=%s" % (self.ch, self.key), json.dumps(payload).encode(), "application/json"), 204)
        m = await next_of(desk, "mine")
        self.assertEqual(m["event"], {"kind": "kill", "boss": "Vorkath", "kc": 412})
        self.assertIsInstance(m["at"], int)
        v = await self.watch()
        tx = await self.tx()
        await next_of(tx, "relay")
        await tx.send(json.dumps({"report": {"type": "LEVEL", "text": "", "data": {"skills": {"Mining": 72}}}}))
        self.assertEqual((await next_of(desk, "mine"))["event"], {"kind": "level", "skill": "Mining", "level": 72})
        for line in await self.lines(v, 0.5):
            self.assertNotIn('"mine"', line)

    # ---- the record ----

    async def test_a_sealed_chunk_is_kept_as_it_came_and_read_with_the_read_token(self):
        await self.claim()
        _, read, write = year_key(0)
        self.assertEqual(VECTORS["read_token"], read)                      # (the vectors' tokens: HMAC, as the page makes them)
        self.assertEqual(VECTORS["write_token"], write)
        self.assertEqual(self.listing(read)[0], 403)                       # (none registered yet)
        self.assertEqual(self.register(key="wrong-key-123456"), 403)
        self.assertEqual(self.register(), 204)
        self.assertEqual(self.register(), 204)                             # (the same pair again: nothing new)
        sealed, cid = bytes.fromhex(VECTORS["sealed_hex"]), VECTORS["chunk_id"]
        st, _, body = self.rec("PUT", "/" + cid, sealed, key=self.key)
        self.assertEqual(st, 201)
        put = json.loads(body)
        self.assertEqual((put["id"], put["size"]), (cid, len(sealed)))
        st, got = self.listing(read)
        self.assertEqual((st, got), (200, {"chunks": [{"id": cid, "at": put["at"], "size": len(sealed)}], "bytes": len(sealed)}))
        st, got = self.listing(read, "data=1")
        self.assertEqual(got["next"], None)
        self.assertEqual(base64.b64decode(got["chunks"][0]["data"]), sealed)
        for token in (None, write, "x" * 43, read[:-1]):                   # (the write token can't read)
            self.assertEqual(self.listing(token)[0], 403, token)
        # what the relay keeps is the ciphertext as it came: the plaintext is nowhere in its storage
        if MODE in ("python", "worker"):
            plain = VECTORS["plaintext"].encode()
            for marker in (VECTORS["device"].encode(), b'"where": {"x": 3264', plain[:40]):
                for root, _, files in os.walk(STATE):
                    for f in files:
                        try:
                            with open(os.path.join(root, f), "rb") as fh:
                                self.assertNotIn(marker, fh.read(), os.path.join(root, f))
                        except OSError:
                            pass

    async def test_plaintext_and_what_isnt_sealed_are_refused(self):
        await self.claim()
        plain = VECTORS["plaintext"].encode()
        for body, status in ((plain, 415), (b"\x01" + b"\x00" * 28, 415), (b"\x02" + b"\x00" * 64, 415),
                             (b"\x01" + b"\x00" * (256 * 1024), 413)):
            self.assertEqual(self.rec("PUT", "/" + chunk_id(1), body, key=self.key)[0], status, body[:8])
        self.assertEqual(self.rec("PUT", "/" + chunk_id(1), a_chunk(1), key="wrong-key-123456")[0], 403)
        self.assertEqual(self.rec("PUT", "/" + chunk_id(1), a_chunk(1))[0], 403)                  # (no key)
        self.assertEqual(self.rec("PUT", "/not-an-id", a_chunk(1), key=self.key)[0], 400)
        self.assertEqual(self.register(), 204)
        self.assertEqual(self.listing(year_key(0)[1]), (200, {"chunks": [], "bytes": 0}))

    async def test_a_chunk_is_replaced_by_its_id_and_a_channel_holds_so_many(self):
        await self.claim()
        self.assertEqual(self.register(), 204)
        for n in range(RECORD_CHUNKS):
            self.assertEqual(self.rec("PUT", "/" + chunk_id(n), a_chunk(n), key=self.key)[0], 201)
        self.assertEqual(self.rec("PUT", "/" + chunk_id(99), a_chunk(99), key=self.key)[0], 507)
        self.assertEqual(self.rec("PUT", "/" + chunk_id(0), a_chunk(0, 300), key=self.key)[0], 201)   # (a day grown)
        st, got = self.listing(year_key(0)[1])
        self.assertEqual(sorted(c["id"] for c in got["chunks"]), sorted(chunk_id(n) for n in range(RECORD_CHUNKS)))
        self.assertEqual([c["id"] for c in got["chunks"]], sorted(c["id"] for c in got["chunks"]))     # (in id order)
        self.assertEqual(got["bytes"], 300 + 200 * (RECORD_CHUNKS - 1))
        first = got["chunks"][0]["id"]
        st, page = self.listing(year_key(0)[1], "data=1&after=" + first)
        self.assertEqual([c["id"] for c in page["chunks"]], [c["id"] for c in got["chunks"][1:]])
        self.assertEqual(self.rec("DELETE", "/" + chunk_id(1), key=self.key)[0], 204)
        self.assertEqual(self.rec("DELETE", "/" + chunk_id(1), key=self.key)[0], 404)
        self.assertEqual(len(self.listing(year_key(0)[1])[1]["chunks"]), RECORD_CHUNKS - 1)

    async def test_four_record_keys_at_most_each_reads_and_a_new_station_key_keeps_it(self):
        await self.claim()
        for i in range(4):
            self.assertEqual(self.register(i), 204)
        self.assertEqual(self.register(4), 409)
        self.assertEqual(self.rec("PUT", "/" + chunk_id(1), a_chunk(1), key=self.key)[0], 201)
        for i in range(4):
            self.assertEqual(len(self.listing(year_key(i)[1])[1]["chunks"]), 1, i)
        self.assertEqual(self.listing(year_key(4)[1])[0], 403)
        self.assertEqual(self.rec("POST", "/token", {"read": "short", "write": "x"}, key=self.key)[0], 400)
        if WORKER:
            # (a new station key from the keeper: the record's untouched, and the old key no longer reaches it)
            old = self.key
            status, got = await asyncio.to_thread(admin, "POST", "/admin/channels/" + self.ch)
            self.assertEqual(status, 200)
            self.key = got["key"]
            self.assertEqual(len(self.listing(year_key(0)[1])[1]["chunks"]), 1)
            self.assertEqual(self.rec("DELETE", key=old)[0], 403)

    async def test_delete_everything_takes_the_chunks_the_tokens_and_the_year(self):
        await self.claim()
        self.assertEqual(self.register(), 204)
        self.assertEqual(self.rec("PUT", "/" + chunk_id(1), a_chunk(1), key=self.key)[0], 201)
        self.assertEqual(self.year("PUT", {"v": 1, "hours": [0] * 24}, key=self.key)[0], 200)
        self.assertEqual(self.rec("DELETE", key="wrong-key-123456")[0], 403)
        self.assertEqual(self.rec("DELETE", key=self.key)[0], 204)
        self.assertEqual(self.listing(year_key(0)[1])[0], 403)              # (its tokens gone too)
        self.assertEqual(self.register(), 204)
        self.assertEqual(self.listing(year_key(0)[1])[1], {"chunks": [], "bytes": 0})
        self.assertEqual(self.year("GET")[0], 404)

    async def test_routes_methods_and_a_pages_preflight(self):
        await self.claim()
        st, h, _ = raw("OPTIONS", "/record/" + self.ch, headers={"Origin": "https://ghillie.wiki",
                                                                   "Access-Control-Request-Method": "GET",
                                                                   "Access-Control-Request-Headers": "authorization"})
        self.assertEqual(st, 204)
        self.assertEqual(h.get("Access-Control-Allow-Origin"), "*")
        self.assertIn("authorization", h.get("Access-Control-Allow-Headers").lower())
        self.assertEqual(self.rec("POST")[0], 405)
        self.assertEqual(self.rec("GET", "/token", key=self.key)[0], 405)
        self.assertEqual(self.rec("PUT", "/token", a_chunk(1), key=self.key)[0], 400)
        self.assertEqual(raw("GET", "/record/bad%20name")[0], 400)
        st, h, _ = self.rec("GET", token=year_key(0)[1])
        self.assertEqual(h.get("Access-Control-Allow-Origin"), "*")

    # ---- the public summary ----

    def full_year(self):
        now = int(time.time() * 1000)
        return {
            "v": 1, "range": {"from": now - 86_400_000, "to": now},
            "totals": {"kills": 4120, "minutes": 48000.0, "pets": 2},
            "heat": [[3264, 3264, 600], [2368, 4416, 30]],
            "paths": [[[3264, 3264], [3264, 3392, now]]],
            "activities": [{"minutes": 90, "activity": {"kind": "boss", "boss": "Vorkath"}}],
            "hours": list(range(24)),
            "places": [{"x": 3264, "y": 3264, "minutes": 600, "first": now - 3_600_000, "last": now}],
            "moments": [{"event": {"kind": "pet", "name": "Vorki"}, "t": now}, {"event": {"kind": "kill", "boss": "Vorkath", "kc": 412}}],
            "replay": [[3264, 3264, now - 60_000], [3264, 3392]],
        }

    async def test_a_year_is_checked_kept_and_served_as_its_parts_in_order(self):
        await self.claim()
        y = self.full_year()
        st, h, got = self.year("PUT", y, key=self.key)
        self.assertEqual(st, 200)
        want = dict(y, totals={"minutes": 48000, "kills": 4120, "pets": 2},
                    activities=[{"activity": {"kind": "boss", "boss": "Vorkath"}, "minutes": 90}])
        self.assertEqual(got["year"], want)
        self.assertEqual(list(got["year"]), ["v", "range", "totals", "heat", "paths", "activities", "hours", "places",
                                             "moments", "replay"])
        self.assertEqual(list(got["year"]["totals"]), ["minutes", "kills", "pets"])
        st, h, again = self.year("GET")
        self.assertEqual((st, again), (200, got))
        self.assertEqual(h.get("Access-Control-Allow-Origin"), "*")
        # (the record key's write token posts and takes it down; the read token can't; a wrong key can't)
        self.assertEqual(self.register(), 204)
        _, read, write = year_key(0)
        self.assertEqual(self.year("PUT", {"v": 1}, token=read)[0], 403)
        self.assertEqual(self.year("PUT", {"v": 1}, key="wrong-key-123456")[0], 403)
        self.assertEqual(self.year("PUT", {"v": 1}, token=write)[1:][1]["year"], {"v": 1})
        self.assertEqual(self.year("DELETE", token=read)[0], 403)
        self.assertEqual(self.year("DELETE", token=write)[0], 204)
        self.assertEqual(self.year("GET")[0], 404)
        self.assertEqual(self.year("PUT", {"v": 1})[0], 403)

    async def test_a_year_that_isnt_its_shape_is_refused_by_its_part(self):
        await self.claim()
        now = int(time.time() * 1000)
        bad = [
            ({"v": 2}, "v"), ({}, "v"), ({"v": 1, "notes": "hi"}, "notes"), ([1], "a year"),
            ({"v": 1, "range": {"from": now, "to": now - 1}}, "range"), ({"v": 1, "range": {"from": 5, "to": now}}, "range"),
            ({"v": 1, "range": {"from": now, "to": now + 3 * 86_400_000}}, "range"),
            ({"v": 1, "totals": {"gold": 5}}, "totals"), ({"v": 1, "totals": {"kills": -1}}, "totals"),
            ({"v": 1, "totals": {"kills": 1.5}}, "totals"), ({"v": 1, "totals": {"kills": True}}, "totals"),
            ({"v": 1, "heat": [[3265, 3264, 5]]}, "heat"), ({"v": 1, "heat": [[3264, 3264, 5], [3264, 3264, 6]]}, "heat"),
            ({"v": 1, "heat": [[3264, 3264]]}, "heat"), ({"v": 1, "heat": [[3264, 3264, 5]] * 2}, "heat"),
            ({"v": 1, "heat": [[64 + 128 * i, 2560, 1] for i in range(4097)]}, "heat"),
            ({"v": 1, "paths": [[[3264, 3264]]]}, "paths"), ({"v": 1, "paths": [[[3264, 3264], [3264, 3264, 1]]]}, "paths"),
            ({"v": 1, "activities": [{"activity": {"kind": "boss", "boss": "Mum"}, "minutes": 5}]}, "activities"),
            ({"v": 1, "activities": [{"activity": {"kind": "idle"}, "minutes": 5, "note": "x"}]}, "activities"),
            ({"v": 1, "hours": [0] * 23}, "hours"), ({"v": 1, "hours": [0] * 23 + [-1]}, "hours"),
            ({"v": 1, "places": [{"x": 3264, "y": 3264, "minutes": 5, "name": "home"}]}, "places"),
            ({"v": 1, "places": [{"x": 3264, "y": 3264, "minutes": 5, "first": now, "last": now - 1}]}, "places"),
            ({"v": 1, "moments": [{"event": {"kind": "kill", "boss": "Vorkath"}}]}, "moments"),
            ({"v": 1, "moments": [{"event": {"kind": "loot", "value": 5}}]}, "moments"),
            ({"v": 1, "moments": [{"event": {"kind": "pet", "at": now}}]}, "moments"),
            ({"v": 1, "replay": [[3264, 3264, now, 1]]}, "replay"),
        ]
        for body, part in bad:
            st, _, got = self.year("PUT", body, key=self.key)
            self.assertEqual(st, 400, body if len(json.dumps(body)) < 200 else part)
            self.assertTrue(got["error"].startswith(part), (part, got["error"]))
        self.assertEqual(self.year("PUT", b"{not json", key=self.key)[0], 400)
        self.assertEqual(self.year("PUT", b'{"v": 1, "replay": [' + b", ".join([b"[3264, 3264]"] * 60000) + b"]}", key=self.key)[0], 413)
        self.assertEqual(self.year("GET")[0], 404)                         # (nothing refused was kept)


@unittest.skipUnless(WORKER, "the keeper takes channels back only on the Worker")
class YourYearTakenBack(Base):
    async def test_a_channel_taken_back_takes_its_record_and_year(self):
        _, read, write = year_key(0)
        self.assertEqual(raw("POST", "/record/%s/token?key=%s" % (self.ch, self.key), json.dumps({"read": read, "write": write}).encode(),
                             {"Content-Type": "application/json"})[0], 204)
        self.assertEqual(raw("PUT", "/year/%s?key=%s" % (self.ch, self.key), b'{"v": 1}', {"Content-Type": "application/json"})[0], 200)
        self.assertEqual(await asyncio.to_thread(admin, "DELETE", "/admin/channels/" + self.ch), (204, None))
        self.assertEqual(raw("GET", "/year/" + self.ch)[0], 404)
        self.assertEqual(raw("GET", "/record/" + self.ch, headers={"Authorization": "Bearer " + read})[0], 403)


class SettlingGone(Base):
    """Settling (11/08/2026) went on 11/10/2026 (docs/protocol.md, "Settling"): the globe shows real positions on the land
    and every other outstation at the receiver, so an outstation has no home. Its routes answer 410 with a line an app
    built while it was here shows as it is, and nothing lists a home. (relay.py's public side: relay-py/test_relay.py.)"""

    GONE = "settling has gone: the globe now shows each outstation where it really is, or at the receiver"

    def ask(self, method, path, body=None):
        """(status, headers, JSON or None), as the old app or the old globe page asked."""
        data = json.dumps(body).encode() if body is not None else (b"" if method == "POST" else None)
        req = urllib.request.Request(http_url() + path, method=method, data=data,
                                     headers={"Content-Type": "application/json"} if body is not None else {})
        try:
            with urllib.request.urlopen(req, timeout=10) as r:
                return r.status, r.headers, json.loads(r.read() or b"null")
        except urllib.error.HTTPError as e:
            raw = e.read()
            try:
                return e.code, e.headers, json.loads(raw) if raw else None
            except ValueError:
                return e.code, e.headers, None

    async def test_the_old_routes_say_settling_has_gone(self):
        tx = await self.tx("")
        await next_of(tx, "relay")
        for method, path, body in (("POST", "/settle/%s?key=%s&v=0.9.0" % (self.ch, self.key), None),   # (the old app's)
                                   ("GET", "/settle/%s?code=abcdefabcdef" % self.ch, None),            # (the old page's)
                                   ("POST", "/home/" + self.ch, {"code": "abcdefabcdef", "lon": 1, "lat": 2}),
                                   ("OPTIONS", "/home/" + self.ch, None), ("DELETE", "/settle/not%20one", None)):
            status, h, got = await asyncio.to_thread(self.ask, method, path, body)
            self.assertEqual((status, got), (410, {"error": self.GONE}), (method, path))
            self.assertEqual(h.get("access-control-allow-origin"), "*")
            self.assertEqual(h.get("cache-control"), "no-store")

    async def test_the_open_outstations_have_no_home(self):
        tx = await self.tx("")
        await next_of(tx, "relay")
        await self.pause(2)
        row = {c["channel"]: c for c in (await asyncio.to_thread(http, "GET", "/channels"))[1]["channels"]}[self.ch]
        self.assertEqual(sorted(row), ["activity", "by", "channel", "doing", "game", "live", "open", "static", "title",
                                       "viewers", "where"])
        if WORKER:   # (the keeper's list: no home, nor when it changed)
            mine = {c["channel"]: c for c in (await asyncio.to_thread(admin, "GET", "/admin/channels"))[1]}[self.ch]
            self.assertNotIn("home", mine)
            self.assertNotIn("homeAt", mine)


class Aether(Base):
    """The aether (11/07/2026): one room for every outstation and visitor on the relay, IRC's way. A visitor joins with a
    name and a device id, an outstation with its channel and key; a line may be several rows (ASCII art); the keeper
    takes lines back and bans. The room is the relay's one, shared by every test, so each test gives each visitor a
    device of its own and finds its own lines by their words. (The cleaning and the rate in full, 15 a minute among
    them: relay-py/test_relay.py's AetherWords and AetherRate.)"""

    async def visitor(self, name=None, device=None, extra=""):
        """A visitor's socket (its .name and .device as it gave them)."""
        name = name or "v" + secrets.token_hex(4)
        device = device or secrets.token_hex(16)
        c = await Client().open("%s/aether?name=%s&device=%s%s" % (RELAY, urllib.parse.quote(name), urllib.parse.quote(device), extra))
        self.clients.append(c)
        c.name, c.device = name, device
        return c

    async def station(self, key=None, v=""):
        """This test's outstation in the aether, under its channel."""
        c = await Client().open("%s/aether?channel=%s&key=%s%s" % (RELAY, self.ch, key or self.key, "&v=" + v if v else ""))
        self.clients.append(c)
        return c

    @staticmethod
    def words():
        return "words " + secrets.token_hex(6)

    async def say(self, c, text):
        await c.send(json.dumps({"say": text}))

    async def lines(self, c, wait=0.6):
        """The words of every aether line that comes in `wait` seconds."""
        got = []
        try:
            while True:
                got.append((await next_of(c, "aether", timeout=wait))["text"])
        except asyncio.TimeoutError:
            return got

    async def test_a_visitor_joins_to_the_room_so_far_and_how_many_are_here(self):
        a = await self.visitor()
        self.assertIsInstance(await next_of(a, "aether_so_far"), list)
        self.assertGreaterEqual(await next_of(a, "aether_here"), 1)
        text = self.words()
        await self.say(a, text)
        await next_of(a, "aether")
        b = await self.visitor()
        so_far = await next_of(b, "aether_so_far")
        self.assertLessEqual(len(so_far), 100)
        self.assertEqual(so_far[-1]["text"], text)
        self.assertEqual(sorted(so_far[-1]), ["at", "from", "id", "name", "text"])     # (never a device)
        self.assertGreaterEqual(await next_of(b, "aether_here"), 2)
        end = time.monotonic() + 5                       # (everyone else hears it too, at most every 2 s)
        while await next_of(a, "aether_here", timeout=max(0.1, end - time.monotonic())) < 2:
            pass

    async def test_a_line_reaches_everyone_with_its_name_and_when(self):
        a, b = await self.visitor(), await self.visitor()
        await next_of(b, "aether_so_far")
        text, t0 = self.words(), time.time() * 1000
        await self.say(a, text)
        for c in (a, b):
            line = await next_of(c, "aether")
            self.assertEqual(sorted(line), ["at", "from", "id", "name", "text"])
            self.assertEqual((line["text"], line["name"], line["from"]), (text, a.name, "visitor"))
            self.assertLess(abs(line["at"] - t0), 60000)
        text2 = self.words()
        await self.say(b, text2)
        self.assertGreater((await next_of(a, "aether"))["id"], line["id"])

    async def test_ascii_art_keeps_its_rows_twelve_of_eighty_at_most(self):
        # (more rows are cut to 12, a longer row to 80: cut, not refused; empty rows at the ends and trailing spaces go)
        a = await self.visitor()
        tag = secrets.token_hex(4)
        art = ["", "   ", "  /\\_/\\   ", " ( o.o ) " + tag, "x" * 81] + ["row %d" % i for i in range(3, 14)] + ["", "  "]
        await self.say(a, "\n".join(art))
        want = ["  /\\_/\\", " ( o.o ) " + tag, "x" * 80] + ["row %d" % i for i in range(3, 12)]
        self.assertEqual((await next_of(a, "aether"))["text"].split("\n"), want)

    async def test_control_characters_go_and_a_line_of_nothing_is_ignored(self):
        a, b = await self.visitor(), await self.visitor()
        await next_of(b, "aether_so_far")
        await self.say(a, "\x01\x02 \n \t\n\u200b")      # (nothing, once cleaned: ignored, and not counted)
        await a.send(json.dumps({"say": 5}))
        await a.send(json.dumps({"something": "else"}))
        tag = secrets.token_hex(4)
        await self.say(a, "a\x07b\tc\u202ed\r\ne " + tag + "\x85")
        self.assertEqual((await next_of(b, "aether"))["text"], "abcd\ne " + tag)
        self.assertEqual(await self.lines(b), [])

    async def test_a_second_line_within_two_seconds_waits_and_a_reconnect_doesnt_start_again(self):
        a, b = await self.visitor(), await self.visitor()
        await next_of(b, "aether_so_far")
        first, second = self.words(), self.words()
        await self.say(a, first)
        await self.say(a, second)
        wait = await next_of(a, "aether_slow")
        self.assertTrue(0 < wait <= 2, wait)
        self.assertEqual(await self.lines(b), [first])
        again = await self.visitor(name=a.name, device=a.device)       # (the same device: its count goes on)
        await next_of(again, "aether_so_far")
        await self.say(again, self.words())
        self.assertTrue(0 < await next_of(again, "aether_slow") <= 2)
        await asyncio.sleep(2.1)
        third = self.words()
        await self.say(again, third)
        self.assertEqual(await self.lines(b), [third])

    async def test_an_outstation_joins_with_its_key_and_speaks_under_its_channel(self):
        s = await self.station()
        self.assertIsInstance(await next_of(s, "aether_so_far"), list)
        b = await self.visitor()
        await next_of(b, "aether_so_far")
        text = self.words()
        await self.say(s, text)
        line = await next_of(b, "aether")
        self.assertEqual((line["text"], line["name"], line["from"]), (text, self.ch, "outstation"))

    async def test_a_wrong_or_short_key_is_refused_as_a_desks_would_be(self):
        if not WORKER:
            await next_of(await self.station(), "aether_so_far")       # (relay.py: the first key claims it, as a desk's)
        wrong = await self.station(key="wrong-key-" + secrets.token_hex(3))
        self.assertEqual(await wrong.recv_close(), ("close", 4003))
        short = await self.station(key="short")
        self.assertEqual(await short.recv_close(), ("close", 4001))

    @unittest.skipUnless(WORKER, "channels handed out by the keeper: the Worker only")
    async def test_an_outstation_nobody_handed_out_is_refused(self):
        c = await Client().open("%s/aether?channel=free%s&key=some-key-at-all" % (RELAY, secrets.token_hex(4)))
        self.clients.append(c)
        self.assertEqual(await c.recv_close(), ("close", 4003))

    @unittest.skipUnless(MIN_CLIENT, "the relay under test lets every version in (MIN_CLIENT not given)")
    async def test_an_older_outstation_is_turned_away_and_a_visitor_needs_no_version(self):
        old = await self.station(v="0.0.1")
        self.assertEqual(await old.recv_close(), ("close", 4026))
        self.assertIn(MIN_CLIENT, old.reason)
        v = await self.visitor(extra="&v=0.0.1")
        self.assertIsInstance(await next_of(v, "aether_so_far"), list)
        new = await self.station(v=MIN_CLIENT)
        self.assertIsInstance(await next_of(new, "aether_so_far"), list)

    async def test_a_visitor_cant_take_an_outstations_name_whatever_its_case_and_spaces(self):
        if not WORKER:
            await next_of(await self.station(), "aether_so_far")       # (relay.py: claimed)
        taken = await self.visitor(name=self.ch[:4].upper() + " " + self.ch[4:])
        self.assertEqual(await taken.recv_close(), ("close", 4009))
        self.assertIn("belongs to an outstation", taken.reason)
        free = await self.visitor(name=self.ch + "x")
        self.assertIsInstance(await next_of(free, "aether_so_far"), list)

    async def test_a_name_or_device_the_aether_cant_take_is_refused(self):
        for name, device in (("moss!", None), ("x" * 21, None), ("   ", None), ("moss\u00e9", None), ("moss", "short"),
                             ("moss", "has space " + secrets.token_hex(4))):
            c = await self.visitor(name=name, device=device)
            self.assertEqual(await c.recv_close(), ("close", 4002), (name, device))
        tidy = await self.visitor(name="  moss   fern ")
        await next_of(tidy, "aether_so_far")
        await self.say(tidy, self.words())
        self.assertEqual((await next_of(tidy, "aether"))["name"], "moss fern")

    async def test_the_read_shows_the_room_before_joining(self):
        a = await self.visitor()
        text = self.words()
        await self.say(a, text)
        await next_of(a, "aether")

        def read():
            with urllib.request.urlopen(http_url() + "/aether", timeout=10) as r:
                self.assertEqual(r.headers.get("Access-Control-Allow-Origin"), "*")
                return json.load(r)
        got = await asyncio.to_thread(read)
        self.assertEqual(sorted(got), ["here", "lines"])
        self.assertLessEqual(len(got["lines"]), 30)
        self.assertEqual(got["lines"][-1]["text"], text)
        self.assertEqual(sorted(got["lines"][-1]), ["at", "from", "id", "name", "text"])
        self.assertGreaterEqual(got["here"], 1)
        self.assertEqual(http("POST", "/aether", {"say": "x"})[0], 405)

    async def test_nothing_crosses_between_the_aether_and_a_channel(self):
        await self.tx()
        v, d = await self.watch(), await self.desk()
        s = await self.station()
        await next_of(s, "aether_so_far")
        await self.pause(2)
        await self.say(s, self.words())
        await next_of(s, "aether")
        for c in (v, d):
            with self.assertRaises(asyncio.TimeoutError):
                await next_of(c, "chat", timeout=0.5)
        other = self.words()
        await d.send(json.dumps({"say": other}))
        await v.send(json.dumps({"say": other + " too"}))
        self.assertEqual((await next_of(v, "chat"))["text"], other)
        self.assertEqual(await self.lines(s), [])

    @unittest.skipUnless(AETHER_KEEPER, "the keeper's routes need the relay's token")
    async def test_the_keeper_takes_a_line_back_off_everyones_page(self):
        a, b = await self.visitor(), await self.visitor()
        await next_of(b, "aether_so_far")
        text = self.words()
        await self.say(a, text)
        line = await next_of(b, "aether")
        status, got = admin("GET", "/admin/aether")
        self.assertEqual(status, 200)
        kept = {x["id"]: x for x in got["lines"]}[line["id"]]
        self.assertEqual((kept["text"], kept["who"]), (text, "device:" + a.device))
        self.assertEqual(admin_json("POST", "/admin/aether/unsay", {"id": line["id"]}), (200, {"unsaid": [line["id"]]}))
        self.assertEqual(await next_of(b, "aether_unsay"), line["id"])
        self.assertNotIn(line["id"], [x["id"] for x in http("GET", "/aether")[1]["lines"]])
        late = await self.visitor()
        self.assertNotIn(line["id"], [x["id"] for x in await next_of(late, "aether_so_far")])
        self.assertEqual(admin_json("POST", "/admin/aether/unsay", {"id": line["id"]})[0], 404)

    @unittest.skipUnless(AETHER_KEEPER, "the keeper's routes need the relay's token")
    async def test_the_keeper_bans_a_visitor_by_its_device_and_takes_back_all_its_lines(self):
        a, b = await self.visitor(), await self.visitor()
        await next_of(b, "aether_so_far")
        await self.say(a, self.words())
        first = await next_of(b, "aether")
        await asyncio.sleep(2.1)
        await self.say(a, self.words())
        second = await next_of(b, "aether")
        status, got = admin_json("POST", "/admin/aether/ban", {"id": second["id"]})
        self.assertEqual((status, got), (200, {"banned": "device:" + a.device, "unsaid": [first["id"], second["id"]]}))
        self.assertEqual([await next_of(b, "aether_unsay"), await next_of(b, "aether_unsay")], [first["id"], second["id"]])
        self.assertEqual(await a.recv_close(), ("close", 4033))
        again = await self.visitor(name="another name", device=a.device)
        self.assertEqual(await again.recv_close(), ("close", 4033))
        bans = {x["who"]: x for x in admin("GET", "/admin/aether")[1]["bans"]}
        self.assertEqual(bans["device:" + a.device]["name"], a.name)
        self.assertEqual(admin_json("POST", "/admin/aether/unban", {"who": "device:" + a.device})[0], 200)
        back = await self.visitor(device=a.device)
        self.assertIsInstance(await next_of(back, "aether_so_far"), list)
        self.assertEqual(admin_json("POST", "/admin/aether/unban", {"who": "device:" + a.device})[0], 404)

    @unittest.skipUnless(AETHER_KEEPER, "the keeper's routes need the relay's token")
    async def test_the_keeper_bans_an_outstation_by_its_channel(self):
        s = await self.station()
        await next_of(s, "aether_so_far")
        await self.say(s, self.words())
        line = await next_of(s, "aether")
        self.assertEqual(admin_json("POST", "/admin/aether/ban", {"id": line["id"]})[1]["banned"], "channel:" + self.ch)
        self.assertEqual(await s.recv_close(), ("close", 4033))
        again = await self.station()
        self.assertEqual(await again.recv_close(), ("close", 4033))
        self.assertEqual(admin_json("POST", "/admin/aether/unban", {"who": "channel:" + self.ch})[0], 200)
        self.assertIsInstance(await next_of(await self.station(), "aether_so_far"), list)

    async def test_the_keepers_routes_need_the_token(self):
        for method, path in (("GET", "/admin/aether"), ("POST", "/admin/aether/unsay"), ("POST", "/admin/aether/ban")):
            req = urllib.request.Request(http_url() + path, method=method, data=b'{"id": 1}' if method == "POST" else None,
                                         headers={"Authorization": "Bearer not-the-token-at-all"})
            with self.assertRaises(urllib.error.HTTPError) as e:
                urllib.request.urlopen(req, timeout=10)
            self.assertEqual(e.exception.code, 403, path)


@unittest.skipUnless(WORKER, "channels handed out by an owner: the Worker only")
class Channels(Base):
    async def test_a_channel_nobody_handed_out_cant_be_taken(self):
        c = await Client().open("%s/transmit/%s?key=some-key-at-all" % (RELAY, "free" + secrets.token_hex(4)))
        self.clients.append(c)
        self.assertEqual(await c.recv_close(), ("close", 4003))

    async def test_the_attach_code_carries_the_relay_channel_and_key(self):
        status, got = admin("POST", "/admin/channels/" + self.ch)
        self.assertEqual(status, 200)
        self.assertTrue(got["code"].startswith("outstation1:"))
        raw = got["code"][len("outstation1:"):]
        info = json.loads(base64.urlsafe_b64decode(raw + "=" * (-len(raw) % 4)))
        self.assertEqual((info["relay"], info["channel"], info["key"]), (RELAY, self.ch, got["key"]))

    async def test_a_new_key_cuts_off_the_old_one(self):
        old = await self.tx()
        await self.pause()
        status, got = admin("POST", "/admin/channels/" + self.ch)
        self.assertEqual(await old.recv_close(), ("close", 4003))
        again = await self.tx()                                        # the old key: refused
        self.assertEqual(await again.recv_close(), ("close", 4003))
        fresh = await self.tx(key=got["key"])
        self.assertTrue((await next_of(fresh, "relay"))["live"])

    async def test_taken_back_the_transmitter_goes_and_viewers_stay(self):
        tx = await self.tx()
        v = await self.watch()
        await self.pause()
        self.assertEqual(admin("DELETE", "/admin/channels/" + self.ch)[0], 204)
        self.assertEqual(await tx.recv_close(), ("close", 4003))
        while (await next_of(v, "relay"))["live"]:
            pass
        listed = [c["channel"] for c in admin("GET", "/admin/channels")[1]]
        self.assertNotIn(self.ch, listed)

    async def test_the_admin_route_needs_the_token(self):
        req = urllib.request.Request(http_url() + "/admin/channels/" + self.ch, method="POST",
                                     headers={"Authorization": "Bearer not-the-token-at-all"})
        with self.assertRaises(urllib.error.HTTPError) as e:
            urllib.request.urlopen(req, data=b"", timeout=10)
        self.assertEqual(e.exception.code, 403)

    async def test_too_many_wrong_keys_are_slowed_down(self):
        await self.tx()
        codes = []
        for _ in range(11):
            c = await self.tx(key="wrong-key-%s" % secrets.token_hex(3))
            codes.append((await c.recv_close())[1])
        self.assertEqual(codes[:10], [4003] * 10)
        self.assertEqual(codes[10], 4029)



@unittest.skipUnless(WORKER, "the keeper's notice: the Worker only (the keeper's relay)")
class Notices(Base):
    """One line from the keeper to every portal: every desk connected hears it at once, each that connects later
    hears it on connecting, and taking it down says so. Viewers never get it."""

    async def asyncTearDown(self):
        admin("DELETE", "/admin/notice")
        await super().asyncTearDown()

    async def test_a_notice_reaches_every_desk_now_and_later_and_comes_down(self):
        d = await self.desk()
        v = await self.watch()
        await self.pause(2)
        status, got = admin_json("PUT", "/admin/notice", {"text": "the relay moves on sunday"})
        self.assertEqual((status, got["notice"]["text"]), (200, "the relay moves on sunday"))
        self.assertEqual((await next_of(d, "notice", timeout=5))["text"], "the relay moves on sunday")
        late = await self.desk(name="late")
        self.assertEqual((await next_of(late, "notice"))["text"], "the relay moves on sunday")
        self.assertEqual(admin("GET", "/admin/notice")[1]["notice"]["text"], "the relay moves on sunday")
        self.assertEqual(admin("DELETE", "/admin/notice")[0], 200)
        self.assertIsNone(await next_of(d, "notice", timeout=5))
        self.assertIsNone(admin("GET", "/admin/notice")[1]["notice"])
        with self.assertRaises(asyncio.TimeoutError):
            await next_of(v, "notice", timeout=0.6)

    async def test_a_notice_needs_the_token_and_words(self):
        self.assertEqual(admin_json("PUT", "/admin/notice", {"text": "  "})[0], 400)
        req = urllib.request.Request(http_url() + "/admin/notice", method="PUT", data=b'{"text": "hi"}',
                                     headers={"Authorization": "Bearer not-the-token-at-all", "Content-Type": "application/json"})
        with self.assertRaises(urllib.error.HTTPError) as e:
            urllib.request.urlopen(req, timeout=10)
        self.assertEqual(e.exception.code, 403)


@unittest.skipUnless(WORKER, "asking for a portal: the Worker only (the keeper's relay)")
class Requests(unittest.IsolatedAsyncioTestCase):
    """A friend's client asks for its player's channel with a secret of its own (the pickup); the keeper hands it
    out or turns it down from his list; the client collects the answer once."""

    def setUp(self):
        self.name = "p" + secrets.token_hex(4)                   # (a player's name: 1-12 letters or numbers)
        self.pickup = secrets.token_urlsafe(32)

    def ask(self, name=None, pickup=None, note="moss from the clan"):
        return http("POST", "/requests/" + (name or self.name),
                    {"player": (name or self.name).title(), "note": note, "pickup": pickup or self.pickup})

    def check(self, pickup=None):
        return http("GET", "/requests/%s?pickup=%s" % (self.name, pickup or self.pickup))

    def waiting(self):
        return {r["channel"]: r for r in admin("GET", "/admin/requests")[1]}

    async def test_asked_handed_out_and_collected_once_and_the_code_transmits(self):
        status, got = self.ask()
        self.assertEqual((status, got["state"], got["given"]), (201, "waiting", False))
        row = self.waiting()[self.name]
        self.assertEqual((row["player"], row["note"], row["given"]), (self.name.title(), "moss from the clan", False))
        self.assertEqual(self.check(), (200, {"state": "waiting"}))
        status, minted = admin("POST", "/admin/requests/" + self.name)
        self.assertEqual(status, 200)
        self.assertNotIn(self.name, self.waiting())
        status, got = self.check()
        self.assertEqual((status, got["state"], got["code"]), (200, "given", minted["code"]))
        self.assertEqual(self.check()[0], 404)                                   # collected: gone
        raw = got["code"][len("outstation1:"):]
        info = json.loads(base64.urlsafe_b64decode(raw + "=" * (-len(raw) % 4)))
        tx = await Client().open("%s/transmit/%s?key=%s" % (RELAY, self.name, info["key"]))
        try:
            self.assertTrue((await next_of(tx, "relay"))["live"])
        finally:
            tx.close()
        self.assertIn(self.name, [c["channel"] for c in admin("GET", "/admin/channels")[1]])

    async def test_turned_down_is_said_once(self):
        self.ask()
        self.assertEqual(admin("DELETE", "/admin/requests/" + self.name)[0], 204)
        self.assertEqual(self.check(), (200, {"state": "turned down"}))
        self.assertEqual(self.check()[0], 404)
        self.assertNotIn(self.name, [c["channel"] for c in admin("GET", "/admin/channels")[1]])

    async def test_only_the_asker_can_see_or_take_back_the_answer(self):
        self.ask()
        self.assertEqual(self.check(pickup=secrets.token_urlsafe(32)), (404, {"state": "none"}))
        self.assertEqual(http("DELETE", "/requests/%s?pickup=%s" % (self.name, secrets.token_urlsafe(32)))[0], 404)
        self.assertEqual(http("DELETE", "/requests/%s?pickup=%s" % (self.name, self.pickup))[0], 204)
        self.assertNotIn(self.name, self.waiting())

    async def test_a_name_already_asked_for_isnt_taken_over_and_asking_again_changes_nothing_else(self):
        self.ask()
        status, got = self.ask(pickup=secrets.token_urlsafe(32))
        self.assertEqual(status, 409)
        self.assertIn("already asked", got["error"])
        self.assertEqual(self.ask(note="a new note")[0], 201)                    # the asker again: the note only
        self.assertEqual(self.waiting()[self.name]["note"], "a new note")

    async def test_the_keeper_sees_a_name_already_handed_out(self):
        admin("POST", "/admin/channels/" + self.name)
        status, got = self.ask()
        self.assertTrue(got["given"])
        self.assertTrue(self.waiting()[self.name]["given"])

    async def test_bad_names_short_pickups_and_nobody_waiting_are_refused(self):
        self.assertEqual(self.ask(name="Not%20A%20Channel")[0], 400)
        self.assertEqual(self.ask(name="thirteenchars")[0], 400)
        self.assertEqual(self.ask(pickup="short")[0], 400)
        self.assertEqual(admin("POST", "/admin/requests/" + self.name)[0], 404)
        self.assertEqual(admin("GET", "/admin/requests")[0], 200)
        req = urllib.request.Request(http_url() + "/admin/requests", headers={"Authorization": "Bearer nope-nope-nope-nope"})
        with self.assertRaises(urllib.error.HTTPError) as e:
            urllib.request.urlopen(req, timeout=10)
        self.assertEqual(e.exception.code, 403)

    async def test_yet_more_asks_from_one_place_wait_an_hour(self):
        # (last of these, by name: after it this address can't ask for an hour)
        codes = [self.ask(name="q%s" % secrets.token_hex(3), pickup=secrets.token_urlsafe(32))[0] for _ in range(ASKS_AN_HOUR + 1)]
        self.assertIn(429, codes)
        self.assertEqual(codes[codes.index(429):], [429] * (len(codes) - codes.index(429)))


@unittest.skipUnless(WORKER, "anomaly reports: the Worker only (the keeper's relay)")
class Reports(unittest.IsolatedAsyncioTestCase):
    """A friend's outstation sends a report (their words and the station log); its channel is named only when its key
    checks out; the keeper lists them, newest first, and deletes each when read."""

    def mine(self, report_id):
        status, got = admin("GET", "/admin/reports")
        self.assertEqual(status, 200)
        return next((r for r in got if r["id"] == report_id), None)

    async def test_a_report_with_its_key_names_its_channel_and_one_without_doesnt(self):
        ch = "r" + secrets.token_hex(4)
        status, minted = admin("POST", "/admin/channels/" + ch)
        self.assertEqual(status, 200)
        status, got = http("POST", "/reports", {"channel": ch, "key": minted["key"], "name": "Moss Fern", "note": "the tape stopped",
                                                 "log": "outstation 0.1.0 · its station log\nline", "version": "0.1.0", "windows": "Windows 11"})
        self.assertEqual(status, 201)
        r = self.mine(got["id"])
        self.assertEqual((r["channel"], r["name"], r["note"], r["version"], r["windows"]), (ch, "Moss Fern", "the tape stopped", "0.1.0", "Windows 11"))
        self.assertIn("its station log", r["log"])
        status, got2 = http("POST", "/reports", {"channel": ch, "key": "not-its-key-at-all", "name": "Bog", "note": "hm", "log": ""})
        self.assertEqual(status, 201)
        self.assertEqual(self.mine(got2["id"])["channel"], "")
        status, got3 = http("POST", "/reports", {"name": "nobody yet", "note": "can't attach"})
        self.assertEqual(self.mine(got3["id"])["channel"], "")
        self.assertEqual(self.mine(got3["id"])["kind"], "anomaly")
        status, got4 = http("POST", "/reports", {"kind": "requisition", "name": "Moss Fern", "note": "a guess on raids"})
        self.assertEqual(status, 201)
        self.assertEqual(self.mine(got4["id"])["kind"], "requisition")
        self.assertEqual(admin("DELETE", "/admin/reports/" + got4["id"])[0], 204)
        for i in (got["id"], got2["id"], got3["id"]):
            self.assertEqual(admin("DELETE", "/admin/reports/" + i)[0], 204)
            self.assertIsNone(self.mine(i))

    async def test_empty_or_too_big_reports_are_refused_and_the_list_is_the_keepers(self):
        self.assertEqual(http("POST", "/reports", {"name": "x", "note": "", "log": ""})[0], 400)
        self.assertEqual(http("POST", "/reports", {"name": "x", "note": "big", "log": "x" * (90 * 1024)})[0], 413)
        req = urllib.request.Request(http_url() + "/admin/reports", headers={"Authorization": "Bearer nope-nope-nope-nope"})
        with self.assertRaises(urllib.error.HTTPError) as e:
            urllib.request.urlopen(req, timeout=10)
        self.assertEqual(e.exception.code, 403)

    async def test_z_more_reports_from_one_place_wait_an_hour(self):
        codes = [http("POST", "/reports", {"name": "x", "note": "n%d" % i})[0] for i in range(REPORTS_AN_HOUR + 1)]
        self.assertIn(429, codes)
        for r in admin("GET", "/admin/reports")[1]:
            admin("DELETE", "/admin/reports/" + r["id"])


def almanac_read():
    """(status, headers, JSON) of GET /almanac."""
    try:
        with urllib.request.urlopen(http_url() + "/almanac", timeout=30) as r:
            return r.status, {k.lower(): v for k, v in r.headers.items()}, json.loads(r.read())
    except urllib.error.HTTPError as e:
        return e.code, {k.lower(): v for k, v in e.headers.items()}, None


def almanac_force():
    """The keeper's POST /admin/almanac: every source asked now. Its answer: each source's schedule."""
    req = urllib.request.Request(http_url() + "/admin/almanac", method="POST", data=b"",
                                 headers={"Authorization": "Bearer " + ADMIN})
    with urllib.request.urlopen(req, timeout=60) as r:
        return json.loads(r.read())


@unittest.skipIf(MODE.startswith("ws"), "the almanac's sources are served by these tests (not for a relay already running)")
class Almanac(unittest.TestCase):
    """The almanac (11/09/2026; docs/protocol.md "The almanac"): Jagex's news, the world list and the Wiki's prices,
    fetched by the relay alone on its own schedule (here, from Upstream: the fixtures) and shared. GET /almanac answers
    {"at", "updates", "worlds", "market", "sources"} exactly as tests/fixtures/almanac/expected.json has them, on both
    relays; a read never asks a source (but the first, cold); a source that fails keeps its last good data; /24h's day
    before is asked once, then rolled over from the kept day. (The schedule itself, a day of it on a clock of its own:
    relay-py/test_almanac.py and relay/test/almanac_test.mts.)"""

    expected = json.loads(fixture("expected.json"))

    def setUp(self):
        UPSTREAM.broken, UPSTREAM.day = {}, "24h-latest.json"
        if self._testMethodName != "test_0_a_cold_read_waits_for_one_refresh_shared":
            almanac_force()

    def test_0_a_cold_read_waits_for_one_refresh_shared(self):
        # (the first read of the module: several at once, all answered with everything, each source asked once.
        # relay.py's own schedule may have asked first, as it starts; the Worker's cron doesn't run under wrangler dev)
        got = [None] * 4

        def read(i):
            got[i] = almanac_read()
        ts = [threading.Thread(target=read, args=(i,)) for i in range(4)]
        for t in ts:
            t.start()
        for t in ts:
            t.join()
        for status, _, a in got:
            self.assertEqual(status, 200)
            self.assertEqual(a["updates"], self.expected["news"])
            self.assertEqual(a["market"], self.expected["market"])
        paths = UPSTREAM.paths()
        for p in ("/slr.ws?order=LPWM", "/m=news/latest_news.rss?oldschool=true", "/api/v2/osrs/mapping",
                  "/api/v2/osrs/24h", "/api/v2/osrs/24h?timestamp=%d" % (DAY_S - 86400)):
            self.assertEqual(paths.count(p), 1, p)

    def test_the_answer(self):
        status, headers, a = almanac_read()
        self.assertEqual(status, 200)
        self.assertEqual(headers.get("access-control-allow-origin"), "*")
        self.assertEqual(headers.get("cache-control"), "public, max-age=60")
        self.assertTrue(headers.get("content-type", "").startswith("application/json"))
        self.assertEqual(set(a), {"at", "updates", "worlds", "market", "sources"})
        self.assertAlmostEqual(a["at"] / 1000, time.time(), delta=60)
        self.assertEqual(a["updates"], self.expected["news"])
        self.assertEqual(a["market"], self.expected["market"])
        worlds = dict(a["worlds"])
        self.assertAlmostEqual(worlds.pop("at") / 1000, time.time(), delta=120)
        self.assertEqual(worlds, {k: v for k, v in self.expected["worlds"].items() if k != "at"})
        self.assertEqual(a["worlds"]["players"], 9569)
        self.assertEqual([(x["kind"], x["players"], x["worlds"]) for x in a["worlds"]["seasons"]],
                         [("leagues", 4500, 3), ("deadman", 900, 1)])
        self.assertEqual(set(a["sources"]), {"updates", "worlds", "market", "names"})
        for name, src in a["sources"].items():
            self.assertIs(src["ok"], True, name)
            self.assertIsInstance(src["at"], int)
            self.assertIsInstance(src["age_s"], int)
        self.assertEqual(a["sources"]["market"]["day"], DAY_S * 1000)
        # (the market's own at: the Wiki's day, not the fetch; and each update's id, the same fetch after fetch)
        self.assertEqual({m["at"] for m in a["market"]}, {DAY_S * 1000})
        self.assertEqual([u["id"] for u in a["updates"]], [u["id"] for u in almanac_read()[2]["updates"]])
        self.assertEqual(len({u["id"] for u in a["updates"]}), len(a["updates"]))

    def test_reads_never_ask_a_source(self):
        n = len(UPSTREAM.paths())
        for _ in range(6):
            self.assertEqual(almanac_read()[0], 200)
        self.assertEqual(len(UPSTREAM.paths()), n)

    def test_each_request_says_who_it_is(self):
        with UPSTREAM.lock:
            agents = {ua for _, ua in UPSTREAM.seen}
        self.assertEqual(len(agents), 1, agents)
        ua = agents.pop()
        self.assertIn("https://github.com/jhmcubed/outstation", ua)
        self.assertNotIn("@", ua)

    def test_a_failing_source_keeps_its_last_good_data(self):
        before = almanac_read()[2]
        UPSTREAM.broken = {"/m=news/latest_news.rss": 500, "/slr.ws": b"\x00\x00\x00\x09garbage"}
        try:
            status = almanac_force()["sources"]
            self.assertIs(status["updates"]["ok"], False)
            self.assertEqual(status["updates"]["fails"], 1)
            self.assertIn("500", status["updates"]["error"])
            self.assertIs(status["worlds"]["ok"], False)
            after = almanac_read()[2]
            self.assertEqual(after["updates"], before["updates"])
            self.assertEqual(after["worlds"], before["worlds"])
            self.assertEqual(after["market"], before["market"])
            for name in ("updates", "worlds"):
                self.assertIs(after["sources"][name]["ok"], False)
                self.assertEqual(after["sources"][name]["at"], before["sources"][name]["at"])
            self.assertIs(after["sources"]["market"]["ok"], True)
        finally:
            UPSTREAM.broken = {}
            almanac_force()
        self.assertIs(almanac_read()[2]["sources"]["updates"]["ok"], True)

    def test_the_next_day_rolls_the_kept_day_over(self):
        UPSTREAM.day = "24h-next.json"
        n = len(UPSTREAM.paths())
        almanac_force()
        asked = [p for p in UPSTREAM.paths()[n:] if p.startswith("/api/v2/osrs/24h")]
        self.assertEqual(asked, ["/api/v2/osrs/24h"])        # (the day before is the day kept: not asked)
        a = almanac_read()[2]
        self.assertEqual(a["market"], self.expected["market_next"])
        self.assertEqual(a["sources"]["market"]["day"], (DAY_S + 86400) * 1000)
        self.assertEqual({m["at"] for m in a["market"]}, {(DAY_S + 86400) * 1000})
        # (and back: the kept days aren't the one before it, so that's asked once)
        UPSTREAM.day = "24h-latest.json"
        n = len(UPSTREAM.paths())
        almanac_force()
        asked = [p for p in UPSTREAM.paths()[n:] if p.startswith("/api/v2/osrs/24h")]
        self.assertEqual(asked, ["/api/v2/osrs/24h", "/api/v2/osrs/24h?timestamp=%d" % (DAY_S - 86400)])
        self.assertEqual(almanac_read()[2]["market"], self.expected["market"])

    def test_the_same_day_asks_nothing_more(self):
        n = len(UPSTREAM.paths())
        almanac_force()
        asked = [p for p in UPSTREAM.paths()[n:] if p.startswith("/api/v2/osrs/24h")]
        self.assertEqual(asked, ["/api/v2/osrs/24h"])

    def test_only_get_and_the_keepers_routes(self):
        status, body = http("POST", "/almanac")
        self.assertEqual(status, 405)
        req = urllib.request.Request(http_url() + "/admin/almanac", method="POST", data=b"",
                                     headers={"Authorization": "Bearer nope-nope-nope-nope"})
        with self.assertRaises(urllib.error.HTTPError) as e:
            urllib.request.urlopen(req, timeout=10)
        self.assertEqual(e.exception.code, 403)
        status, got = admin("GET", "/admin/almanac")
        self.assertEqual(status, 200)
        self.assertEqual(list(got["sources"]), ["worlds", "updates", "names", "market"])
        for src in got["sources"].values():
            self.assertEqual(set(src), {"at", "tried", "ok", "fails", "next_in_s", "error"})

    @unittest.skipUnless(MODE == "worker", "the Worker's Cron Trigger (relay.py's schedule: its own tests)")
    def test_the_cron_asks_only_whats_due(self):
        n = len(UPSTREAM.paths())
        with urllib.request.urlopen(http_url() + "/__scheduled?cron=*/5+*+*+*+*", timeout=30) as r:
            r.read()
        time.sleep(1)
        self.assertEqual(UPSTREAM.paths()[n:], [])            # (everything was just asked: nothing's due)


if __name__ == "__main__":
    unittest.main(verbosity=1)
