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
             "--var", "POLL_MIN_SECONDS:1", "--var", "POLL_KEPT_SECONDS:1"],
            cwd=os.path.join(ROOT, "relay"), stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
            shell=sys.platform == "win32")
        up = wait_up(90)
    else:
        PROC = subprocess.Popen([sys.executable, os.path.join(ROOT, "relay-py", "relay.py"), "--port", str(port),
                                 "--no-forward"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        up = wait_up(15)
    if not up:
        tearDownModule()
        raise RuntimeError("the relay didn't start (%s)" % MODE)


def tearDownModule():
    if PROC is not None:
        if sys.platform == "win32":
            subprocess.run(["taskkill", "/F", "/T", "/PID", str(PROC.pid)], capture_output=True)
        else:
            PROC.kill()
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

    async def test_the_transmitter_hears_how_many_watch(self):
        tx = await self.tx()
        self.assertEqual((await next_of(tx, "relay"))["viewers"], 0)
        await self.watch()
        while (await next_of(tx, "relay"))["viewers"] != 1:
            pass


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


class Dink(Base):
    async def test_a_level_up_reaches_viewers_as_a_report_and_a_late_one_gets_the_last(self):
        await self.tx()
        v = await self.watch()
        await self.pause()
        body, ct = multipart({"type": "LEVEL", "playerName": "x", "content": "",
                              "embeds": [{"description": "**x** has levelled **Attack** to **70** [wiki](https://x)"}]},
                             screenshot=b"\x89PNG fake")
        self.assertEqual(await post("/dink/%s?key=%s" % (self.ch, self.key), body, ct), 204)
        r = json.loads((await v.recv_data())[1])["report"]
        self.assertEqual((r["type"], r["text"]), ("LEVEL", "x has levelled Attack to 70 wiki"))
        for i in range(2):
            await post("/dink/%s?key=%s" % (self.ch, self.key),
                       json.dumps({"type": "LOOT", "content": "loot %d" % i}).encode(), "application/json")
        await self.pause()
        late = await self.watch()
        got = [json.loads((await late.recv_data())[1])["report"]["text"] for _ in range(3)]
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
        r = json.loads((await v.recv_data())[1])["report"]
        self.assertEqual((r["type"], r["text"]), ("DEATH", ""))
        with self.assertRaises(asyncio.TimeoutError):
            await v.recv_data(timeout=0.4)

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


@unittest.skipUnless(WORKER, "channels handed out by an owner: the Worker only")
class Channels(Base):
    async def test_a_channel_nobody_handed_out_cant_be_taken(self):
        c = await Client().open("%s/transmit/%s?key=some-key-at-all" % (RELAY, "free" + secrets.token_hex(4)))
        self.clients.append(c)
        self.assertEqual(await c.recv_close(), ("close", 4003))

    async def test_the_attach_code_carries_the_relay_channel_and_key(self):
        status, got = admin("POST", "/admin/channels/" + self.ch)
        self.assertEqual(status, 200)
        self.assertTrue(got["code"].startswith("portal1:"))
        raw = got["code"][len("portal1:"):]
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


if __name__ == "__main__":
    unittest.main(verbosity=1)
