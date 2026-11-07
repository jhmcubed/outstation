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
import time
import unittest
import urllib.error
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
SAY_BURST = 20
SOUND_HOLD = 0.5
RELAY = None          # ws://127.0.0.1:port
PROC = None
STATE = None


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


def setUpModule():
    global RELAY, PROC, STATE
    if MODE.startswith("ws"):
        RELAY = MODE.rstrip("/")
        return
    port = free_port()
    RELAY = "ws://127.0.0.1:%d" % port
    if MODE == "worker":
        STATE = tempfile.mkdtemp(prefix="portal-wrangler-")
        npx = shutil.which("npx") or "npx"
        PROC = subprocess.Popen(
            [npx, "wrangler", "dev", "--port", str(port), "--ip", "127.0.0.1", "--persist-to", STATE,
             "--show-interactive-dev-session=false", "--var", "ADMIN_TOKEN:" + ADMIN,
             "--var", "POLL_MIN_SECONDS:1", "--var", "POLL_KEPT_SECONDS:1", "--var", "MIN_CLIENT:" + MIN_CLIENT, "--var", "ASKS_AN_HOUR:" + str(ASKS_AN_HOUR), "--var", "REPORTS_AN_HOUR:" + str(REPORTS_AN_HOUR)],
            cwd=os.path.join(ROOT, "relay"), stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
            shell=sys.platform == "win32", start_new_session=sys.platform != "win32")
        up = wait_up(90)
    else:
        STATE = tempfile.mkdtemp(prefix="portal-clips-")
        PROC = subprocess.Popen([sys.executable, os.path.join(ROOT, "relay-py", "relay.py"), "--port", str(port),
                                 "--no-forward", "--min-client", MIN_CLIENT, "--clips", STATE],
                                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                                start_new_session=sys.platform != "win32")
        up = wait_up(15)
    if not up:
        tearDownModule()
        raise RuntimeError("the relay didn't start (%s)" % MODE)


def tearDownModule():
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


if __name__ == "__main__":
    unittest.main(verbosity=1)
