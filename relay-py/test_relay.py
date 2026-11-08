"""The portal's local relay (relay.py), tested end to end on a spare port.

    py tools/portal/test_relay.py

Standard library only: wsmini.Client plays the plugin and the viewers.
"""

import asyncio
import json
import os
import socket
import struct
import sys
import time
import unittest
from unittest import mock

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import forward  # noqa: E402
import relay  # noqa: E402
from wsmini import Client  # noqa: E402


def free_port():
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    port = s.getsockname()[1]
    s.close()
    return port


class RelayTest(unittest.IsolatedAsyncioTestCase):
    delay = 0.0
    forward = None

    async def asyncSetUp(self):
        self.port = free_port()
        self.public_port = free_port()
        started = asyncio.Event()
        self.relay = None
        self.clients = []

        def ready(server, r):
            self.relay = r
            started.set()
        self.task = asyncio.ensure_future(relay.serve("127.0.0.1", self.port, self.delay, ready, log=lambda *_: None,
                                                      forward=self.make_forward(), public_port=self.public_port))
        await asyncio.wait_for(started.wait(), 5)

    def make_forward(self):
        return None

    async def asyncTearDown(self):
        for c in self.clients:
            c.close()
            try:
                await c.w.wait_closed()
            except Exception:
                pass
        self.task.cancel()
        try:
            await self.task
        except BaseException:
            pass

    async def connect(self, path):
        c = await Client().open(self.port, path)
        self.clients.append(c)
        return c


class Fanout(RelayTest):
    async def test_a_frame_and_data_reach_every_viewer(self):
        tx = await self.connect("/transmit/sburb?key=secret-key&code=moss")
        a = await self.connect("/watch/sburb?code=moss")
        b = await self.connect("/watch/sburb?code=moss")
        await asyncio.sleep(0.05)
        frame = struct.pack(">I", 1234) + b"\xff\xd8 fake jpeg"
        await tx.send('{"tick": 1234}')
        await tx.send(frame)
        for v in (a, b):
            self.assertEqual(await v.recv_data(), ("text", '{"tick": 1234}'))
            self.assertEqual(await v.recv_data(), ("binary", frame))

    async def test_a_late_viewer_gets_the_last_data_and_frame_at_once(self):
        tx = await self.connect("/transmit/sburb?key=secret-key")
        await tx.send('{"tick": 1}')
        await tx.send(b"\x00\x00\x00\x01frame one")
        await tx.send('{"tick": 2}')
        await asyncio.sleep(0.05)
        late = await self.connect("/watch/sburb")
        self.assertEqual(await late.recv_data(), ("text", '{"tick": 2}'))
        self.assertEqual(await late.recv_data(), ("binary", b"\x00\x00\x00\x01frame one"))

    async def test_what_a_viewer_sends_goes_nowhere(self):
        tx = await self.connect("/transmit/sburb?key=secret-key")
        v1 = await self.connect("/watch/sburb")
        v2 = await self.connect("/watch/sburb")
        await asyncio.sleep(0.05)
        await v1.send('{"tick": 999, "spoof": true}')
        await v1.send(b"\x00\x00\x03\xe7spoofed frame")
        await asyncio.sleep(0.1)
        with self.assertRaises(asyncio.TimeoutError):
            await v2.recv_data(timeout=0.3)
        self.assertIsNone(self.relay.channels["sburb"].last_data)
        self.assertIsNone(self.relay.channels["sburb"].last_frame)

    async def test_viewers_hear_when_the_channel_goes_live_and_off_air(self):
        v = await self.connect("/watch/sburb")
        kind, line = await v.recv()
        self.assertFalse(json.loads(line)["relay"]["live"])
        tx = await self.connect("/transmit/sburb?key=secret-key")
        lines = []
        while True:
            kind, line = await v.recv()
            if line.startswith('{"summary"'):
                continue            # (the stream's summary, 11/06/2026: not a status line)
            lines.append(json.loads(line)["relay"])
            if lines[-1]["live"]:
                break
        tx.close()
        while True:
            kind, line = await v.recv()
            if line.startswith('{"relay"') and not json.loads(line)["relay"]["live"]:
                break


    async def test_off_air_a_newcomer_gets_no_old_frame(self):
        # (11/05/2026: the portal stays on Outside off air; an old frame would show there as if live)
        tx = await self.connect("/transmit/sburb?key=secret-key")
        await asyncio.sleep(0.05)
        await tx.send(struct.pack(">I", 1234) + b"\xff\xd8 fake jpeg")
        await asyncio.sleep(0.1)
        tx.close()
        await asyncio.sleep(0.1)
        v = await self.connect("/watch/sburb")
        with self.assertRaises(asyncio.TimeoutError):
            while True:
                kind, _ = await v.recv_data(timeout=0.3)
                self.assertNotEqual(kind, "binary")


class Count(RelayTest):
    async def test_the_transmitter_hears_how_many_watch(self):
        tx = await self.connect("/transmit/sburb?key=secret-key")
        kind, line = await tx.recv()
        self.assertEqual(json.loads(line)["relay"]["viewers"], 0)
        await self.connect("/watch/sburb")
        while True:
            kind, line = await tx.recv()
            if json.loads(line)["relay"]["viewers"] == 1:
                break


class Keys(RelayTest):
    async def test_the_wrong_key_cant_take_a_channel(self):
        await self.connect("/transmit/sburb?key=secret-key")
        thief = await self.connect("/transmit/sburb?key=another-key")
        self.assertEqual(await thief.recv_close(), ("close", 4003))

    async def test_a_short_key_is_refused(self):
        tx = await self.connect("/transmit/sburb?key=short")
        self.assertEqual(await tx.recv_close(), ("close", 4001))

    async def test_the_wrong_watch_code_is_refused(self):
        await self.connect("/transmit/sburb?key=secret-key&code=moss")
        await asyncio.sleep(0.05)
        v = await self.connect("/watch/sburb?code=lichen")
        self.assertEqual(await v.recv(), ("close", 4003))

    async def test_a_viewer_waiting_early_is_let_go_if_the_code_differs(self):
        v = await self.connect("/watch/sburb")
        await v.recv()
        await self.connect("/transmit/sburb?key=secret-key&code=moss")
        while True:
            kind, payload = await v.recv()
            if kind == "close":
                self.assertEqual(payload, 4003)
                break

    async def test_the_same_key_replaces_the_old_transmitter(self):
        old = await self.connect("/transmit/sburb?key=secret-key")
        await asyncio.sleep(0.05)
        await self.connect("/transmit/sburb?key=secret-key")
        self.assertEqual(await old.recv_close(), ("close", 4000))

    async def test_a_bad_path_is_not_upgraded(self):
        c = await self.connect("/somewhere/else")
        self.assertEqual(c.status, 400)


CRLF = "\r\n"


async def post(port, path, body, content_type):
    """An HTTP POST as Dink makes one; the status code back."""
    r, w = await asyncio.open_connection("127.0.0.1", port)
    head = CRLF.join(["POST %s HTTP/1.1" % path, "Host: x", "Content-Type: %s" % content_type,
                      "Content-Length: %d" % len(body), "", ""])
    w.write(head.encode() + body)
    await w.drain()
    status = int((await r.readuntil(CRLF.encode())).split(b" ")[1])
    w.close()
    return status


def multipart(payload, screenshot=b""):
    """Dink's body: payload_json, and a screenshot when it sends one."""
    b = "dinkboundary"
    parts = [CRLF.join(["--" + b, 'Content-Disposition: form-data; name="payload_json"',
                        "Content-Type: application/json", "", json.dumps(payload), ""]).encode()]
    if screenshot:
        parts.append(CRLF.join(["--" + b, 'Content-Disposition: form-data; name="file"; filename="image.png"',
                                "Content-Type: image/png", "", ""]).encode() + screenshot + CRLF.encode())
    parts.append(("--%s--" % b + CRLF).encode())
    return b"".join(parts), "multipart/form-data; boundary=" + b


class Dink(RelayTest):
    async def test_a_level_up_reaches_viewers_as_a_report(self):
        await self.connect("/transmit/sburb?key=secret-key")
        v = await self.connect("/watch/sburb")
        await asyncio.sleep(0.05)
        body, ct = multipart({"type": "LEVEL", "playerName": "sburb", "content": "",
                              "embeds": [{"description": "**sburb** has levelled **Attack** to **70** [wiki](https://x)"}]},
                             screenshot=b"\x89PNG fake")
        self.assertEqual(await post(self.port, "/dink/sburb?key=secret-key", body, ct), 204)
        kind, line = await v.recv_data()
        r = json.loads(line)["report"]
        self.assertEqual(r["type"], "LEVEL")
        self.assertEqual(r["text"], "sburb has levelled Attack to 70 wiki")

    async def test_the_wrong_key_is_refused(self):
        await self.connect("/transmit/sburb?key=secret-key")
        body, ct = multipart({"type": "LEVEL", "content": "x"})
        self.assertEqual(await post(self.port, "/dink/sburb?key=another-key", body, ct), 403)

    async def test_kinds_that_name_other_players_never_pass(self):
        await self.connect("/transmit/sburb?key=secret-key")
        v = await self.connect("/watch/sburb")
        await asyncio.sleep(0.05)
        for kind in ("PLAYER_KILL", "TRADE", "CHAT", "GROUP_STORAGE"):
            body, ct = multipart({"type": kind, "content": "someone else's name"})
            self.assertEqual(await post(self.port, "/dink/sburb?key=secret-key", body, ct), 204)
        body, ct = multipart({"type": "DEATH", "content": "killed by someone", "extra": {"isPvp": True}})
        await post(self.port, "/dink/sburb?key=secret-key", body, ct)
        kind, line = await v.recv_data()
        r = json.loads(line)["report"]
        self.assertEqual((r["type"], r["text"]), ("DEATH", ""))
        with self.assertRaises(asyncio.TimeoutError):
            await v.recv_data(timeout=0.3)

    async def test_a_late_viewer_gets_the_last_reports(self):
        await self.connect("/transmit/sburb?key=secret-key")
        await self.connect("/watch/sburb")
        for i in range(3):
            await post(self.port, "/dink/sburb?key=secret-key",
                       json.dumps({"type": "LOOT", "content": "loot %d" % i}).encode(), "application/json")
        late = await self.connect("/watch/sburb")
        got = []
        for _ in range(3):
            kind, line = await late.recv_data()
            got.append(json.loads(line)["report"]["text"])
        self.assertEqual(got, ["loot 0", "loot 1", "loot 2"])


EXAMPLE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "forward.example.json")


def loot(*stacks):
    return {"type": "LOOT", "content": "loot", "extra": {"items": [
        {"name": n, "quantity": q, "priceEach": p} for n, q, p in stacks], "source": "Vorkath"}}


def kc(count, pb=False, laps=False):
    return {"type": "KILL_COUNT", "content": "has completed %d laps at X" % count if laps else "kc",
            "extra": {"boss": "X", "count": count, "isPersonalBest": pb}}


class Rules(unittest.TestCase):
    """Jace's 11/04/2026 Dink settings, as the relay re-checks them for Discord."""

    def setUp(self):
        with open(EXAMPLE, encoding="utf-8") as f:
            self.f = forward.Forward(json.load(f))

    def test_loot_needs_a_stack_worth_a_million_or_an_allowed_item(self):
        self.assertFalse(self.f.wants(loot(("Dragon bones", 2, 3000))))
        self.assertTrue(self.f.wants(loot(("Dragon bones", 2, 3000), ("Draconic visage", 1, 2500000))))
        self.assertTrue(self.f.wants(loot(("Coins", 1000000, 1))))
        self.assertTrue(self.f.wants(loot(("Blood quartz", 1, 0))))
        self.assertTrue(self.f.wants(loot(("Awakener's orb", 1, 900000))))

    def test_kill_counts_every_25_the_first_and_personal_bests(self):
        self.assertFalse(self.f.wants(kc(26)))
        self.assertTrue(self.f.wants(kc(50)))
        self.assertTrue(self.f.wants(kc(1)))
        self.assertTrue(self.f.wants(kc(37, pb=True)))
        self.assertFalse(self.f.wants(kc(25, laps=True)))
        self.assertTrue(self.f.wants(kc(2000, laps=True)))

    def test_what_was_on_still_goes_and_what_was_off_doesnt(self):
        for kind in ("LEVEL", "DEATH", "QUEST", "PET", "COLLECTION", "PLAYER_KILL"):
            self.assertTrue(self.f.wants({"type": kind}), kind)
        for kind in ("SLAYER", "SPEEDRUN", "GRAND_EXCHANGE", "TRADE", "CHAT", "XP_MILESTONE", "SOMETHING_NEW"):
            self.assertFalse(self.f.wants({"type": kind}), kind)


class InviteNumbers(unittest.TestCase):
    """Invites (11/07/2026): three to begin with and one more every 10 hours on air (relay.invite_numbers; the Worker's
    inviteNumbers is the same), and the time on air in all that earns them. The shared suite (tests/protocol_test.py,
    Invites) tests the routes on both relays; ten hours on air can only be tested here."""

    def test_three_to_begin_with_and_one_more_every_ten_hours(self):
        n = relay.invite_numbers
        self.assertEqual(n(0, []), {"left": 3, "earned": 3, "used": [], "hours": 0.0, "next_in_s": 36000})
        self.assertEqual(n(35999.9, ["a", "b", "c"]),
                         {"left": 0, "earned": 3, "used": ["a", "b", "c"], "hours": 9.9, "next_in_s": 1})
        self.assertEqual(n(36000, ["a", "b", "c"])["left"], 1)
        got = n(3 * 36000 + 90, ["a"])
        self.assertEqual((got["earned"], got["left"], got["hours"], got["next_in_s"]), (6, 5, 30.0, 35910))
        self.assertEqual(n(0, ["a", "b", "c", "d"])["left"], 0)           # (never below none)

    def test_time_on_air_in_all_counts_every_stretch_and_the_one_going_on(self):
        ch = relay.Channel("moss", "secret-key")
        clock = [1000.0]
        with mock.patch.object(relay.time, "monotonic", lambda: clock[0]):
            ch.on_air()
            clock[0] += 3600
            ch.on_air()                                                     # (a transmitter replaced: the same stretch)
            clock[0] += 3600
            ch.off_air()
            self.assertEqual(ch.seconds_in_all(), 7200)
            clock[0] += 50000                                               # off air: nothing
            self.assertEqual(ch.seconds_in_all(), 7200)
            ch.on_air()
            clock[0] += 30000
            self.assertEqual(ch.seconds_in_all(), 37200)                    # (the stretch going on, counted)
            self.assertEqual(relay.invite_numbers(ch.seconds_in_all(), [])["earned"], 4)

    def test_an_attach_code_reads_as_the_workers(self):
        code = relay.attach_code("ws://127.0.0.1:8786", "moss", "k3y-k3y-k3y")
        # (the Worker's attachCode writes compact JSON: this is what it makes of the same three)
        self.assertEqual(code, "outstation1:eyJyZWxheSI6IndzOi8vMTI3LjAuMC4xOjg3ODYiLCJjaGFubmVsIjoibW9zcyIsImtleSI6ImszeS1r"
                               "M3ktazN5In0")


class GameFields(unittest.TestCase):
    """The game (11/07/2026): each field of the Outside plugin's messages checked and cleaned (relay.game_fields; the
    Worker's gameFields in game.ts is the same). The shared suite (tests/protocol_test.py, Game) tests the socket."""

    def test_each_field_is_checked_cleaned_and_put_in_order_and_unknown_ones_left_out(self):
        got = relay.game_fields({
            "secret": "not kept", "items": {"385": ["Shark\n", 870.0], "007": ["Bond", 1], "99999999999": ["Big", 1],
                                            "12": ["Price as words", "870"], "4": ["Bronze bar", 30]},
            "prayers": ["PIETY", " RIGOUR "], "quiver": None, "pouch": None, "bag": None,
            "inv": [[385, 2], None, [1, -1]], "worn": [[1163, 1]] + [None] * 13,
            "skills": {"Attack": [99, 112, 13034431], "1st": [1, 1, 0], "Magic": [99, 99], "Hitpoints": [10.0, 10, 1154]},
            "vitals": {"hp": [80, 99], "prayer": [1, 2, 3], "run": 140, "spec": 50.0, "mood": "fine"},
            "off": "yes", "paused": "  wilderness, or a world where players fight each other ", "type": "IRONMAN",
            "world": 302.0, "name": " So\u202ebig  longername "})
        self.assertEqual(list(got), ["name", "world", "type", "paused", "vitals", "skills", "worn", "bag", "quiver",
                                     "prayers", "items"])
        self.assertEqual(got["paused"], "wilderness, or a world where pla")     # (one line, 32 at most)
        self.assertEqual(got["name"], "So big longe")                      # (one line, no direction marks, 12 at most)
        self.assertEqual((got["world"], got["type"], got["bag"], got["quiver"]), (302, "IRONMAN", None, None))
        self.assertEqual(got["vitals"], {"hp": [80, 99], "spec": 50})
        self.assertEqual(got["skills"], {"Attack": [99, 112, 13034431], "Hitpoints": [10, 10, 1154]})
        self.assertEqual(got["prayers"], ["PIETY", "RIGOUR"])
        self.assertEqual(list(got["items"].items()), [("4", ["Bronze bar", 30]), ("385", ["Shark", 870])])   # (by id)
        self.assertNotIn("inv", got)                                       # (a slot that isn't [item, how many])
        self.assertNotIn("off", got)
        self.assertNotIn("pouch", got)                                     # (only the bag may be null)
        self.assertEqual(relay.game_fields({"quiver": [11212, 120], "off": False, "world": True, "paused": False}),
                         {"off": False, "paused": False, "quiver": [11212, 120]})
        self.assertEqual(relay.game_fields({"paused": True}), {})          # (a word or two, or false)
        self.assertEqual(relay.game_fields({"worn": [None] * 15, "items": {"1": ["x"]}}), {})
        self.assertEqual(relay.game_fields(["not", "an", "object"]), {})

    def test_nan_and_infinity_are_not_json(self):
        ch = relay.Channel("moss", "secret-key")
        ch.take_game('{"game": {"world": 302, "name": NaN}}')
        ch.take_game('{"game": {"world": Infinity}}')
        self.assertEqual(ch.game.kept, {})
        ch.take_game('{"game": {"world": 1e400}}')          # (a number too big for anything: left out)
        ch.take_game('{"game": {"world": 302}}')
        self.assertEqual(ch.game.kept, {"world": 302})


class GameState(unittest.TestCase):
    """The game state kept (relay.Game; the Worker's GameState, the same): each field replaced whole but "items", merged
    into one dictionary (GAME_ITEMS ids at most), the whole at most GAME_KEPT as JSON."""

    def test_each_field_is_replaced_whole_but_items_which_merge(self):
        g = relay.Game()
        out = g.apply({"inv": [[385, 1]], "items": {"385": ["Shark", 870]}, "off": True}, now=1)
        self.assertEqual(out, {"inv": [[385, 1]], "off": True, "items": {"385": ["Shark", 870]}})
        out = g.apply({"inv": [None, [2434, 4]], "items": {"2434": ["Prayer potion(4)", 9000]}}, now=2)
        self.assertEqual(out, {"inv": [None, [2434, 4]], "items": {"2434": ["Prayer potion(4)", 9000]}})
        self.assertEqual(g.kept, {"inv": [None, [2434, 4]], "off": True,
                                  "items": {"385": ["Shark", 870], "2434": ["Prayer potion(4)", 9000]}})
        self.assertEqual(g.at, 2)

    def test_what_changes_nothing_goes_nowhere_and_with_a_change_all_of_it_goes(self):
        g = relay.Game()
        g.apply({"world": 302, "items": {"385": ["Shark", 870]}}, now=1)
        self.assertIsNone(g.apply({"world": 302, "items": {"385": ["Shark", 870]}}, now=2))
        self.assertEqual(g.at, 1)
        self.assertEqual(g.apply({"world": 302, "items": {"385": ["Shark", 900]}}, now=3),
                         {"world": 302, "items": {"385": ["Shark", 900]}})
        self.assertIsNone(g.apply({}, now=4))
        self.assertEqual(g.apply({"bag": None}, now=5), {"bag": None})     # (null, where there was nothing: new)

    def test_no_new_ids_past_the_dictionarys_most_but_known_ones_still_change(self):
        g = relay.Game()
        with mock.patch.object(relay, "GAME_ITEMS", 3):
            g.apply({"items": {"1": ["a", 1], "2": ["b", 2]}}, now=1)
            out = g.apply({"items": {"3": ["c", 3], "4": ["d", 4], "5": ["e", 5]}}, now=2)
            self.assertEqual(out, {"items": {"3": ["c", 3]}})
            self.assertIsNone(g.apply({"items": {"6": ["f", 6]}}, now=3))
            self.assertEqual(g.apply({"world": 1, "items": {"6": ["f", 6], "1": ["a", 10]}}, now=4),
                             {"world": 1, "items": {"1": ["a", 10]}})
        self.assertEqual(g.kept["items"], {"1": ["a", 10], "2": ["b", 2], "3": ["c", 3]})

    def test_its_length_is_its_json_and_a_message_past_the_most_is_ignored_whole(self):
        g = relay.Game()
        self.assertEqual(g.size(), len(json.dumps(g.kept)))
        g.apply(relay.game_fields({"name": "Sburb", "vitals": {"hp": [80, 99]}, "bag": None, "prayers": ["PIETY"],
                                   "items": {str(i): ["Item é %d" % i, i * 7] for i in range(50)}}), now=1)
        g.apply({"name": "Sb", "items": {"3": ["Changed", 1], "1000": ["New", 2]}}, now=2)
        self.assertEqual(g.size(), len(json.dumps(g.kept)))
        kept, size = json.dumps(g.kept), g.size()
        with mock.patch.object(relay, "GAME_KEPT", size + 20):
            self.assertIsNone(g.apply({"world": 302, "type": "x" * 40}, now=3))     # (past it: none of it kept)
            self.assertEqual(json.dumps(g.kept), kept)
            self.assertEqual(g.apply({"world": 302}, now=4), {"world": 302})
        self.assertEqual(g.size(), len(json.dumps(g.kept)))


async def ask(port, method, path, body=None):
    """(status, JSON or None) of a request with a JSON body, as the outstation client makes one."""
    data = json.dumps(body).encode() if body is not None else b""
    r, w = await asyncio.open_connection("127.0.0.1", port)
    w.write(CRLF.join(["%s %s HTTP/1.1" % (method, path), "Host: 127.0.0.1:%d" % port, "Content-Type: application/json",
                       "Content-Length: %d" % len(data), "", ""]).encode() + data)
    await w.drain()
    raw = await r.read()
    w.close()
    head, _, got = raw.partition((CRLF * 2).encode())
    return int(head.split(b" ")[1]), json.loads(got) if got else None


class Invites(RelayTest):
    async def test_hours_on_air_earn_more_invites(self):
        await self.connect("/transmit/sburb?key=secret-key")           # (claimed: handed out, here)
        await asyncio.sleep(0.05)
        self.relay.channels["sburb"].live_all = 36000 + 5               # ten hours on air, and a little
        status, got = await ask(self.port, "GET", "/invites/sburb?key=secret-key")
        self.assertEqual((status, got["earned"], got["left"], got["hours"]), (200, 4, 4, 10.0))
        self.assertTrue(35990 <= got["next_in_s"] <= 35995, got["next_in_s"])
        lefts = []
        for name in ("moss", "fern", "lichen", "bog"):
            status, got = await ask(self.port, "POST", "/invites/sburb", {"key": "secret-key", "player": name})
            lefts.append((status, got["left"]))
        self.assertEqual(lefts, [(200, 3), (200, 2), (200, 1), (200, 0)])
        status, got = await ask(self.port, "POST", "/invites/sburb", {"key": "secret-key", "player": "peat"})
        self.assertEqual((status, got["error"]), (409, "no invites left: one more every 10 hours on air"))
        self.assertEqual(self.relay.channels["moss"].by, "sburb")

    async def test_an_invited_name_viewers_were_waiting_on_is_handed_out_and_they_hear_so(self):
        async def status(c):
            while True:
                kind, line = await c.recv()
                if kind == "text" and line.startswith('{"relay"'):
                    return json.loads(line)["relay"]
        await self.connect("/transmit/sburb?key=secret-key")
        v = await self.connect("/watch/moss")
        self.assertFalse((await status(v))["set"])
        status_code, got = await ask(self.port, "POST", "/invites/sburb", {"key": "secret-key", "player": "moss"})
        self.assertEqual(status_code, 200)
        while not (await status(v))["set"]:
            pass
        tx = await self.connect("/transmit/moss?key=" + got["key"])
        self.assertTrue((await status(tx))["live"])
        self.assertEqual(self.relay.channels["moss"].by, "sburb")

    async def test_an_invite_is_pending_till_its_key_is_used_for_anything(self):
        # (11/08/2026: tests/protocol_test.py's take it up with a transmitter and GET /invites; here a desk and Dink,
        # each with a key check of its own, and the public side's list)
        async def lists(port=None):
            status, got = await ask(port or self.port, "GET", "/channels")
            self.assertEqual(status, 200)
            return {c["channel"] for c in got["channels"]}, {p["by"]: p["n"] for p in got["pending"]}
        async def status_of(c):
            st = None
            while True:
                try:
                    kind, line = await c.recv(0.2)
                except asyncio.TimeoutError:
                    return st
                if kind == "text" and line.startswith('{"relay"'):
                    st = json.loads(line)["relay"]
        await self.connect("/transmit/sburb?key=secret-key")
        await asyncio.sleep(0.05)
        v = await self.connect("/watch/moss")                       # (a viewer waiting on a name, then invited)
        keys = {}
        for name in ("moss", "fern", "bog"):
            status, got = await ask(self.port, "POST", "/invites/sburb", {"key": "secret-key", "player": name})
            self.assertEqual(status, 200)
            keys[name] = got["key"]
        for port in (self.port, self.public_port):
            self.assertEqual(await lists(port), ({"sburb"}, {"sburb": 3}))
        self.assertTrue(self.relay.channels["moss"].pending)
        bad = await self.connect("/desk/moss?key=not-the-key-at-all")
        self.assertEqual(await bad.recv_close(), ("close", 4003))
        self.assertEqual((await lists())[1], {"sburb": 3})        # (a wrong key takes nothing up)
        await self.connect("/desk/moss?key=" + keys["moss"])
        await asyncio.sleep(0.05)
        self.assertEqual(await lists(), ({"sburb", "moss"}, {"sburb": 2}))
        body, ct = multipart({"type": "LEVEL", "content": "fern levelled", "extra": {}})
        self.assertEqual(await post(self.port, "/dink/fern?key=" + keys["fern"], body, ct), 204)
        self.assertEqual(await lists(), ({"sburb", "moss", "fern"}, {"sburb": 1}))
        self.assertTrue((await status_of(v))["set"])               # (the viewer waiting on moss: it's there now)

    async def test_the_public_side_refuses_invites_even_with_the_key(self):
        await self.connect("/transmit/sburb?key=secret-key")
        await asyncio.sleep(0.05)
        status, _, _ = await get(self.public_port, "/invites/sburb?key=secret-key")
        self.assertEqual(status, 403)
        status = await post(self.public_port, "/invites/sburb",
                            json.dumps({"key": "secret-key", "player": "moss"}).encode(), "application/json")
        self.assertEqual(status, 403)
        self.assertNotIn("moss", self.relay.channels)


class FollowingWords(unittest.TestCase):
    """Following's "where" (11/08/2026; game_where): a 128-tile square's middle on the main map, null, or ignored."""

    def test_a_squares_middle_is_taken_and_anything_else_is_not(self):
        W = relay.game_where
        self.assertEqual(W({"where": {"x": 3264, "y": 3264}}), {"x": 3264, "y": 3264})
        self.assertEqual(W({"where": {"y": 2496, "x": 1088}}), {"x": 1088, "y": 2496})        # (the corners)
        self.assertEqual(W({"where": {"x": 4032, "y": 4160}}), {"x": 4032, "y": 4160})
        self.assertEqual(W({"where": {"x": 3264.0, "y": 3264}}), {"x": 3264, "y": 3264})    # (as JavaScript reads it)
        self.assertIsNone(W({"where": None}))
        for g in ({}, {"world": 302}, None, [], {"where": {"x": 1024, "y": 3264}}, {"where": {"x": 960, "y": 3264}},
                  {"where": {"x": 4160, "y": 3264}}, {"where": {"x": 3264, "y": 2368}}, {"where": {"x": 3264, "y": 4224}},
                  {"where": {"x": 3264, "y": 4288}}, {"where": {"x": 3265, "y": 3264}}, {"where": {"x": 3264.5, "y": 3264}},
                  {"where": {"x": True, "y": 3264}}, {"where": {"x": "3264", "y": 3264}}, {"where": {"x": 3264}},
                  {"where": {"x": 3264, "y": 3264, "plane": 0}}, {"where": [3264, 3264]}, {"where": "lumbridge"},
                  {"where": 3264}, {"where": True}):
            self.assertIs(W(g), relay._BAD, g)
        self.assertNotIn("where", relay.game_fields({"world": 302, "where": {"x": 3264, "y": 3264}}))   # (never kept)


class Following(RelayTest):
    async def listed_where(self):
        status, got = await ask(self.port, "GET", "/channels")
        return {c["channel"]: c for c in got["channels"]}["sburb"]["where"]

    async def test_a_new_square_is_taken_once_50_seconds_have_gone(self):
        await self.connect("/transmit/sburb?key=secret-key")
        g = await self.connect("/game/sburb?key=secret-key&v=0.0.1")
        await g.send(json.dumps({"game": {"where": {"x": 3264, "y": 3264}}}))
        await asyncio.sleep(0.05)
        await g.send(json.dumps({"game": {"where": {"x": 3136, "y": 3264}}}))
        await asyncio.sleep(0.05)
        self.assertEqual(await self.listed_where(), {"x": 3264, "y": 3264})    # (too soon)
        self.relay.channels["sburb"].where_took -= 50                           # (50 seconds on)
        await g.send(json.dumps({"game": {"where": {"x": 3136, "y": 3264}}}))
        await asyncio.sleep(0.05)
        self.assertEqual(await self.listed_where(), {"x": 3136, "y": 3264})
        self.relay.channels["sburb"].where_at -= relay.WHERE_FRESH_S + 1      # (240 seconds on: stale)
        self.assertIsNone(await self.listed_where())


class FollowingDelayed(RelayTest):
    delay = 0.4

    async def test_a_square_held_back_by_the_delay_isnt_taken_once_the_plugin_has_gone(self):
        await self.connect("/transmit/sburb?key=secret-key")
        g = await self.connect("/game/sburb?key=secret-key&v=0.0.1")
        await g.send(json.dumps({"game": {"where": {"x": 3264, "y": 3264}}}))
        await asyncio.sleep(0.6)
        self.assertEqual((await ask(self.port, "GET", "/channels"))[1]["channels"][0]["where"], {"x": 3264, "y": 3264})
        self.relay.channels["sburb"].where_took -= 50
        await g.send(json.dumps({"game": {"where": {"x": 3136, "y": 3264}}}))
        g.close()                                                    # (gone before the delay lets it go)
        await asyncio.sleep(0.6)
        ch = self.relay.channels["sburb"]
        self.assertIsNone(ch.gamer)
        self.assertIsNone(ch.where)
        self.assertIsNone((await ask(self.port, "GET", "/channels"))[1]["channels"][0]["where"])


class FakeDiscord:
    """A stand-in webhook: records each post's body and content type."""

    def __init__(self):
        self.posts = []
        self.got = asyncio.Event()

    async def start(self):
        self.server = await asyncio.start_server(self.handle, "127.0.0.1", 0)
        self.url = "http://127.0.0.1:%d/api/webhooks/1/test" % self.server.sockets[0].getsockname()[1]

    async def handle(self, r, w):
        head = (await r.readuntil(CRLF.encode() * 2)).decode("latin-1")
        h = {k.strip().lower(): v.strip() for k, _, v in (line.partition(":") for line in head.split(CRLF)[1:]) if k}
        body = await r.readexactly(int(h.get("content-length", 0)))
        self.posts.append((h.get("content-type"), body, h.get("user-agent")))
        w.write(("HTTP/1.1 204 No Content" + CRLF + "Content-Length: 0" + CRLF + CRLF).encode())
        await w.drain()
        w.close()
        self.got.set()


class Forwarding(RelayTest):
    def make_forward(self):
        with open(EXAMPLE, encoding="utf-8") as f:
            rules = json.load(f)
        rules["discord"] = [self.discord.url]
        return forward.Forward(rules, log=lambda *_: None)

    async def asyncSetUp(self):
        self.discord = FakeDiscord()
        await self.discord.start()
        await super().asyncSetUp()

    async def asyncTearDown(self):
        self.discord.server.close()
        await super().asyncTearDown()

    async def test_a_big_drop_goes_to_discord_as_dink_sent_it_and_to_the_portal(self):
        await self.connect("/transmit/sburb?key=secret-key")
        v = await self.connect("/watch/sburb")
        await asyncio.sleep(0.05)
        body, ct = multipart(loot(("Draconic visage", 1, 2500000)), screenshot=b"PNG bytes")
        self.assertEqual(await post(self.port, "/dink/sburb?key=secret-key", body, ct), 204)
        await asyncio.wait_for(self.discord.got.wait(), 5)
        self.assertEqual(self.discord.posts[0][:2], (ct, body))
        self.assertTrue(self.discord.posts[0][2].startswith("DiscordBot"))
        kind, line = await v.recv_data()
        self.assertEqual(json.loads(line)["report"]["type"], "LOOT")

    async def test_a_small_drop_reaches_only_the_portal(self):
        await self.connect("/transmit/sburb?key=secret-key")
        v = await self.connect("/watch/sburb")
        await asyncio.sleep(0.05)
        await post(self.port, "/dink/sburb?key=secret-key", json.dumps(loot(("Bones", 1, 100))).encode(),
                   "application/json")
        kind, line = await v.recv_data()
        self.assertEqual(json.loads(line)["report"]["type"], "LOOT")
        await asyncio.sleep(0.3)
        self.assertEqual(self.discord.posts, [])

    async def test_the_wrong_key_forwards_nothing(self):
        await self.connect("/transmit/sburb?key=secret-key")
        await post(self.port, "/dink/sburb?key=another-key", json.dumps({"type": "LEVEL"}).encode(), "application/json")
        await asyncio.sleep(0.3)
        self.assertEqual(self.discord.posts, [])


async def get(port, path):
    """(status, content type, body) of a plain GET."""
    r, w = await asyncio.open_connection("127.0.0.1", port)
    w.write(CRLF.join(["GET %s HTTP/1.1" % path, "Host: x", "", ""]).encode())
    await w.drain()
    raw = await r.read()
    w.close()
    head, _, body = raw.partition((CRLF * 2).encode())
    lines = head.decode("latin-1").split(CRLF)
    h = {k.strip().lower(): v.strip() for k, _, v in (line.partition(":") for line in lines[1:])}
    return int(lines[0].split(" ")[1]), h.get("content-type", ""), body


# (the self-hosted page is ghillie.wiki's, in its own repository: here only when copied beside the relay)
HAS_PAGE = os.path.exists(relay.PAGE_FILES["/"][0])


@unittest.skipUnless(HAS_PAGE, "no web/ beside relay.py")
class Page(RelayTest):
    """Self-hosting: the relay serves the watching page, so a friend needs only its address."""

    async def test_the_page_comes_from_the_relay_and_knows_it(self):
        status, kind, body = await get(self.port, "/?channel=sburb&code=lichen")
        self.assertEqual(status, 200)
        self.assertIn("text/html", kind)
        self.assertIn(b'data-relay="self"', body)

    async def test_its_script_style_and_font_come_too(self):
        for path, kind in (("/portal.js", "javascript"), ("/portal.css", "css"),
                           ("/public/media/fonts/runescape-chat.woff2", "woff2")):
            status, got, body = await get(self.port, path)
            self.assertEqual((status, kind in got), (200, True), path)
            self.assertTrue(body, path)

    async def test_nothing_else_is_served(self):
        for path in ("/relay.py", "/../forward.py", "/settings.json", "/web/portal.html"):
            status, _, _ = await get(self.port, path)
            self.assertEqual(status, 404, path)


class PublicSide(RelayTest):
    """The port the internet reaches through Funnel: the page and watching, nothing else."""

    async def test_watching_and_the_page_work_there(self):
        tx = await self.connect("/transmit/sburb?key=secret-key&code=moss")
        v = await Client().open(self.public_port, "/watch/sburb?code=moss")
        self.clients.append(v)
        self.assertEqual(v.status, 101)
        await asyncio.sleep(0.05)
        await tx.send('{"tick": 5}')
        self.assertEqual(await v.recv_data(), ("text", '{"tick": 5}'))
        if HAS_PAGE:
            status, kind, body = await get(self.public_port, "/?channel=sburb&code=moss")
            self.assertEqual(status, 200)

    async def test_transmitting_there_is_refused_even_with_the_key(self):
        await self.connect("/transmit/sburb?key=secret-key")
        c = await Client().open(self.public_port, "/transmit/sburb?key=secret-key")
        self.clients.append(c)
        self.assertEqual(c.status, 403)

    async def test_the_game_is_read_there_but_its_socket_is_refused_even_with_the_key(self):
        g = await self.connect("/game/sburb?key=secret-key")
        v = await self.connect("/watch/sburb")
        await asyncio.sleep(0.05)
        await g.send(json.dumps({"game": {"world": 302}}))
        await next_of(v, "game")
        status, kind, body = await get(self.public_port, "/game/sburb")
        self.assertEqual((status, json.loads(body)["game"]), (200, {"world": 302}))
        c = await Client().open(self.public_port, "/game/sburb?key=secret-key")
        self.clients.append(c)
        self.assertEqual(c.status, 403)

    async def test_dink_posts_there_are_refused_even_with_the_key(self):
        await self.connect("/transmit/sburb?key=secret-key")
        v = await self.connect("/watch/sburb")
        await asyncio.sleep(0.05)
        status = await post(self.public_port, "/dink/sburb?key=secret-key",
                            json.dumps({"type": "LEVEL", "content": "spoof"}).encode(), "application/json")
        self.assertEqual(status, 403)
        with self.assertRaises(asyncio.TimeoutError):
            await v.recv_data(timeout=0.3)

    async def test_the_arrays_events_are_read_there_and_nothing_else(self):
        await self.connect("/transmit/sburb?key=secret-key")
        await asyncio.sleep(0.05)
        await post(self.port, "/dink/sburb?key=secret-key", json.dumps({"type": "PET", "content": "", "extra": {
            "petName": "Vorki"}}).encode(), "application/json")
        status, kind, body = await get(self.public_port, "/array/events")
        got = json.loads(body)
        self.assertEqual((status, kind), (200, "application/json"))
        self.assertEqual([(e["channel"], e["kind"], e["name"]) for e in got["events"]], [("sburb", "pet", "Vorki")])
        self.assertEqual(await post(self.public_port, "/array/events", b"{}", "application/json"), 403)


class Full(RelayTest):
    """A cap on how many watch at once: the transmitter's upload sends each their own copy."""

    async def last_status(self, c, settle=0.15):
        """The newest status line a viewer has been sent (after a moment for the rest to arrive)."""
        st = None
        end = time.monotonic() + settle
        while True:
            try:
                kind, line = await c.recv(timeout=max(0.01, end - time.monotonic()))
            except asyncio.TimeoutError:
                return st
            if kind == "text" and line.startswith('{"relay"'):
                st = json.loads(line)["relay"]

    async def test_past_the_cap_a_viewer_waits_in_line_with_no_frames(self):
        # (11/05/2026, Jace: at the cap the portal still shows; it used to be told 4009 and sent away)
        self.relay.max_viewers = 2
        tx = await self.connect("/transmit/sburb?key=secret-key")
        a = await self.connect("/watch/sburb")
        b = await self.connect("/watch/sburb")
        third = await self.connect("/watch/sburb")
        st = await self.last_status(third)
        self.assertEqual((st["seat"], st["place"], st["viewers"], st["max"], st["live"]), (False, 1, 2, 2, True))
        sa = await self.last_status(a)
        self.assertNotIn("seat", sa)
        self.assertEqual((sa["viewers"], sa["max"], sa["waiting"]), (2, 2, 1))
        await tx.send(b"\x00\x00\x00\x01frame")
        self.assertEqual(await a.recv_data(), ("binary", b"\x00\x00\x00\x01frame"))
        with self.assertRaises(asyncio.TimeoutError):
            await third.recv_data(timeout=0.3)

    async def test_a_seat_freed_goes_to_the_first_in_line_with_the_last_frame(self):
        self.relay.max_viewers = 1
        tx = await self.connect("/transmit/sburb?key=secret-key")
        a = await self.connect("/watch/sburb")
        await asyncio.sleep(0.05)
        second = await self.connect("/watch/sburb")
        await asyncio.sleep(0.05)
        third = await self.connect("/watch/sburb")
        self.assertEqual((await self.last_status(third))["place"], 2)
        await tx.send(b"\x00\x00\x00\x02frame two")
        await asyncio.sleep(0.05)
        a.close()
        self.assertEqual(await second.recv_data(), ("binary", b"\x00\x00\x00\x02frame two"))
        self.assertEqual((await self.last_status(third))["place"], 1)

    async def test_a_raised_cap_seats_those_waiting(self):
        import tempfile
        path = os.path.join(tempfile.mkdtemp(), "settings.json")
        with open(path, "w", encoding="utf-8") as f:
            json.dump({"max_viewers": 1}, f)
        task = asyncio.ensure_future(self.relay.watch_settings(path, every=0.05))
        try:
            await asyncio.sleep(0.15)
            await self.connect("/transmit/sburb?key=secret-key")
            await self.connect("/watch/sburb")
            waiting = await self.connect("/watch/sburb")
            self.assertFalse((await self.last_status(waiting))["seat"])
            with open(path, "w", encoding="utf-8") as f:
                json.dump({"max_viewers": 3}, f)
            await asyncio.sleep(0.3)
            st = await Full.last_status(self, waiting)      # (Full's helper)
            self.assertNotIn("seat", st)
            self.assertEqual((st["viewers"], st["max"]), (2, 3))
        finally:
            task.cancel()

    async def test_a_looker_takes_no_seat_and_gets_few_frames_only_when_it_asks(self):
        self.relay.max_viewers = 1
        tx = await self.connect("/transmit/sburb?key=secret-key")
        look = await self.connect("/watch/sburb?look=1")
        seated = await self.connect("/watch/sburb")
        st = await self.last_status(seated)
        self.assertNotIn("seat", st)                         # the one viewer has the one seat: the looker took none
        self.assertEqual(st["viewers"], 1)
        frame = struct.pack(">I", 1) + b"\xff\xd8 frame"
        await tx.send(frame)
        with self.assertRaises(asyncio.TimeoutError):
            await look.recv_data(timeout=0.3)             # not asked for frames yet
        await look.send(json.dumps({"look": {"frames": True}}))
        self.assertEqual(await look.recv_data(), ("binary", frame))   # the last one at once
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
        self.assertLessEqual(got, 1)                         # six frames in 0.3 s: one at most, by LOOK_EVERY

    async def test_anyone_may_watch_when_the_channel_has_no_code(self):
        await self.connect("/transmit/sburb?key=secret-key&code=")
        await asyncio.sleep(0.05)
        old_link = await self.connect("/watch/sburb?code=8XKJX173")      # a link from when it had one
        plain = await self.connect("/watch/sburb")
        for v in (old_link, plain):
            kind, line = await v.recv()
            self.assertTrue(json.loads(line)["relay"]["live"])


class CapFromSettings(unittest.IsolatedAsyncioTestCase):
    """The cap read again from the settings file while the relay runs (the field station's window)."""

    async def test_a_change_to_the_file_takes_without_a_restart(self):
        import tempfile
        d = tempfile.mkdtemp()
        path = os.path.join(d, "settings.json")
        with open(path, "w", encoding="utf-8") as f:
            json.dump({"max_viewers": 4}, f)
        r = relay.Relay(log=lambda *_: None)
        task = asyncio.ensure_future(r.watch_settings(path, every=0.05))
        try:
            await asyncio.sleep(0.2)
            self.assertEqual(r.max_viewers, 4)
            await asyncio.sleep(0.05)
            with open(path, "w", encoding="utf-8") as f:
                json.dump({"max_viewers": 12, "fps": 30}, f)
            os.utime(path, (time.time() + 5, time.time() + 5))      # (a new time, whatever the clock's grain)
            await asyncio.sleep(0.2)
            self.assertEqual(r.max_viewers, 12)
        finally:
            task.cancel()


class Delay(RelayTest):
    delay = 0.4

    async def test_everything_is_held_back_by_the_delay(self):
        tx = await self.connect("/transmit/sburb?key=secret-key")
        v = await self.connect("/watch/sburb")
        await asyncio.sleep(0.05)
        await tx.send('{"tick": 7}')
        with self.assertRaises(asyncio.TimeoutError):
            await v.recv_data(timeout=0.25)
        self.assertEqual(await v.recv_data(timeout=1.0), ("text", '{"tick": 7}'))

    async def test_the_game_is_held_back_too(self):
        g = await self.connect("/game/sburb?key=secret-key")
        v = await self.connect("/watch/sburb")
        await asyncio.sleep(0.05)
        await g.send(json.dumps({"game": {"world": 302}}))
        with self.assertRaises(asyncio.TimeoutError):
            await next_of(v, "game", timeout=0.25)
        self.assertEqual((await next_of(v, "game", timeout=1.0))["world"], 302)

    async def test_the_arrays_events_are_held_back_too_and_none_once_its_dark(self):
        tx = await self.connect("/transmit/sburb?key=secret-key")
        await asyncio.sleep(0.05)
        await post(self.port, "/dink/sburb?key=secret-key", json.dumps({"type": "KILL_COUNT", "content": "", "extra": {
            "boss": "Vorkath", "count": 5}}).encode(), "application/json")
        self.assertEqual(self.relay.array.read()["events"], [])
        await asyncio.sleep(0.6)
        self.assertEqual([e["kc"] for e in self.relay.array.read()["events"]], [5])
        await post(self.port, "/dink/sburb?key=secret-key", json.dumps({"type": "KILL_COUNT", "content": "", "extra": {
            "boss": "Vorkath", "count": 6}}).encode(), "application/json")
        tx.close()                       # (dark before the report reaches viewers: it never shows)
        await asyncio.sleep(0.6)
        self.assertEqual([e["kc"] for e in self.relay.array.read()["events"]], [5])


async def next_of(client, kind, timeout=1.0):
    """The next line of this kind ("chat", "poll", "ping", "unsay", "sound"), skipping the rest."""
    while True:
        k, payload = await client.recv(timeout)
        if k == "text":
            m = json.loads(payload)
            if kind in m:
                return m[kind]


class Talk(RelayTest):
    """The chat (11/05/2026): viewers to Jace's desk and back, polls, pings and blocks."""

    async def setup_room(self):
        tx = await self.connect("/transmit/sburb?key=secret-key")
        desk = await self.connect("/desk/sburb?key=secret-key&name=sburb")
        a = await self.connect("/watch/sburb")
        b = await self.connect("/watch/sburb")
        await a.send(json.dumps({"hello": {"name": "moss", "device": "device-aaaa"}}))
        await b.send(json.dumps({"hello": {"name": "fern", "device": "device-bbbb"}}))
        await asyncio.sleep(0.05)
        return tx, desk, a, b

    async def test_a_viewers_line_reaches_the_desk_and_every_viewer(self):
        tx, desk, a, b = await self.setup_room()
        await a.send(json.dumps({"say": "  hello\n there  "}))
        for c in (desk, a, b):
            line = await next_of(c, "chat")
            self.assertEqual((line["name"], line["text"], line["host"], line["queued"]), ("moss", "hello there", False, False))

    async def test_his_lines_are_marked_as_his_and_nobody_can_take_his_name(self):
        tx, desk, a, b = await self.setup_room()
        await desk.send(json.dumps({"say": "try the north path"}))
        line = await next_of(b, "chat")
        self.assertEqual((line["name"], line["host"]), ("sburb", True))
        await a.send(json.dumps({"hello": {"name": "SBURB"}}))
        await a.send(json.dumps({"say": "it's me"}))
        line = await next_of(b, "chat")
        self.assertEqual((line["name"], line["host"]), ("visitor", False))

    async def test_a_late_viewer_gets_the_chat_so_far(self):
        tx, desk, a, b = await self.setup_room()
        await a.send(json.dumps({"say": "one"}))
        await desk.send(json.dumps({"say": "two"}))
        await next_of(b, "chat")
        await next_of(b, "chat")
        late = await self.connect("/watch/sburb")
        self.assertEqual([(await next_of(late, "chat"))["text"] for _ in range(2)], ["one", "two"])

    async def test_a_flood_is_cut_off_and_nothing_long_or_odd_gets_through(self):
        tx, desk, a, b = await self.setup_room()
        for i in range(relay.SAY_BURST + 4):
            await a.send(json.dumps({"say": "line %d" % i}))
        await a.send(json.dumps({"say": "x" * 5000}))          # past VIEWER_TEXT: thrown away unread
        got = []
        try:
            while True:
                got.append((await next_of(desk, "chat", timeout=0.4))["text"])
        except asyncio.TimeoutError:
            pass
        self.assertEqual(got, ["line %d" % i for i in range(relay.SAY_BURST)])

    async def test_hide_takes_a_line_back_and_block_silences_a_viewer(self):
        tx, desk, a, b = await self.setup_room()
        await a.send(json.dumps({"say": "rude"}))
        line = await next_of(desk, "chat")
        await desk.send(json.dumps({"hide": line["id"]}))
        self.assertEqual(await next_of(b, "unsay"), line["id"])
        await desk.send(json.dumps({"block": line["who"]}))
        await asyncio.sleep(0.05)
        await a.send(json.dumps({"say": "again"}))
        await a.send(json.dumps({"ping": {"x": 0.5, "y": 0.5}}))
        await b.send(json.dumps({"say": "fine"}))
        self.assertEqual((await next_of(desk, "chat"))["text"], "fine")
        late = await self.connect("/watch/sburb")
        texts = []
        try:
            while True:
                texts.append((await next_of(late, "chat", timeout=0.3))["text"])
        except asyncio.TimeoutError:
            pass
        self.assertEqual(texts, ["fine"])     # the hidden line is gone for late viewers too; the blocked never came

    async def test_a_poll_counts_one_vote_a_device_and_closes(self):
        tx, desk, a, b = await self.setup_room()
        await desk.send(json.dumps({"poll": {"question": "next?", "options": ["vorkath", "zulrah", ""],
                                             "seconds": 60}}))
        p = await next_of(a, "poll")
        self.assertEqual((p["options"], p["counts"], p["open"]), (["vorkath", "zulrah"], [0, 0], True))
        await a.send(json.dumps({"vote": {"poll": p["id"], "pick": 0}}))
        await a.send(json.dumps({"vote": {"poll": p["id"], "pick": 1}}))     # a change of mind, not a second vote
        await b.send(json.dumps({"vote": {"poll": p["id"], "pick": 1}}))
        await b.send(json.dumps({"vote": {"poll": p["id"], "pick": 7}}))     # no such option
        counts = None
        while counts != [0, 2]:
            counts = (await next_of(desk, "poll"))["counts"]
        await desk.send(json.dumps({"poll_close": True}))
        while (await next_of(b, "poll"))["open"]:
            pass
        await a.send(json.dumps({"vote": {"poll": p["id"], "pick": 0}}))     # too late
        await asyncio.sleep(0.05)
        late = await self.connect("/watch/sburb")
        p = await next_of(late, "poll")
        self.assertEqual((p["counts"], p["open"]), ([0, 2], False))

    async def test_a_poll_ends_by_itself(self):
        tx, desk, a, b = await self.setup_room()
        await desk.send(json.dumps({"poll": {"question": "", "options": ["a", "b"], "seconds": 10}}))
        await next_of(a, "poll")
        self.relay.channels["sburb"].poll["ends"] = time.monotonic()     # (rather than wait out ten seconds)
        self.assertFalse((await next_of(a, "poll"))["open"])

    async def test_a_closed_poll_goes_after_a_while_for_late_viewers_too(self):
        tx, desk, a, b = await self.setup_room()
        await desk.send(json.dumps({"poll": {"question": "", "options": ["a", "b"], "seconds": 60}}))
        pid = (await next_of(a, "poll"))["id"]
        await desk.send(json.dumps({"poll_close": True}))
        while (await next_of(a, "poll"))["open"]:
            pass
        self.relay.channels["sburb"].poll["closed"] -= relay.POLL_KEPT        # (rather than wait it out)
        self.assertEqual(await next_of(b, "poll_gone"), pid)
        self.assertIsNone(self.relay.channels["sburb"].poll)
        late = await self.connect("/watch/sburb")
        with self.assertRaises(asyncio.TimeoutError):
            await next_of(late, "poll", timeout=0.3)

    async def test_a_ping_reaches_everyone_but_not_too_often_or_off_the_frame(self):
        tx, desk, a, b = await self.setup_room()
        await a.send(json.dumps({"ping": {"x": 0.25, "y": 0.75}}))
        await a.send(json.dumps({"ping": {"x": 0.3, "y": 0.3}}))        # too soon
        await b.send(json.dumps({"ping": {"x": 1.5, "y": 0.3}}))        # off the frame
        p = await next_of(desk, "ping")
        self.assertEqual((p["x"], p["y"], p["name"]), (0.25, 0.75, "moss"))
        self.assertEqual((await next_of(b, "ping"))["x"], 0.25)
        with self.assertRaises(asyncio.TimeoutError):
            await next_of(desk, "ping", timeout=0.3)

    async def test_someone_in_line_for_a_spot_can_still_talk(self):
        self.relay.max_viewers = 1
        tx = await self.connect("/transmit/sburb?key=secret-key")
        desk = await self.connect("/desk/sburb?key=secret-key&name=sburb")
        seated = await self.connect("/watch/sburb")
        await desk.send(json.dumps({"say": "before"}))
        await next_of(seated, "chat")
        waiting = await self.connect("/watch/sburb")
        self.assertEqual((await next_of(waiting, "chat"))["text"], "before")     # the chat so far
        st = await Full.last_status(self, waiting)      # (Full's helper)
        self.assertEqual(st["seat"], False)
        self.assertTrue(st["you"])
        await waiting.send(json.dumps({"hello": {"name": "fern", "device": "device-wait-1"}}))
        await waiting.send(json.dumps({"say": "let me in"}))
        line = await next_of(desk, "chat")
        while line["text"] != "let me in":                  # (the desk hears its own "before" too)
            line = await next_of(desk, "chat")
        self.assertEqual((line["name"], line["who"], line["queued"]), ("fern", st["you"], True))   # marked: in line
        self.assertEqual((await next_of(seated, "chat"))["text"], "let me in")
        self.assertEqual((await next_of(waiting, "chat"))["text"], "let me in")  # and they see it, and what follows
        await tx.send(b"\x00\x00\x00\x09frame")
        with self.assertRaises(asyncio.TimeoutError):
            await self._no_frame(waiting)      # (no frame comes before the recv times out)

    async def _no_frame(self, c):
        while True:
            k, payload = await c.recv(timeout=0.3)
            if k == "binary":
                raise AssertionError("someone in line got a frame")

    async def test_a_signal_reaches_everyone_once_a_second_at_most(self):
        tx, desk, a, b = await self.setup_room()
        await a.send(json.dumps({"signal": True}))
        await a.send(json.dumps({"signal": True}))          # too soon
        self.assertEqual((await next_of(b, "signal"))["name"], "moss")
        self.assertEqual((await next_of(desk, "signal"))["name"], "moss")
        with self.assertRaises(asyncio.TimeoutError):
            await next_of(desk, "signal", timeout=0.4)

    async def test_a_drop_guess_round_kept_by_dink_and_decided_by_a_drop(self):
        tx, desk, a, b = await self.setup_room()

        async def dink(payload):
            body = json.dumps(payload).encode()
            self.assertEqual(await post(self.port, "/dink/sburb?key=secret-key", body, "application/json"), 204)
        await dink({"type": "KILL_COUNT", "content": "", "extra": {"boss": "Vorkath", "count": 40}})
        await desk.send(json.dumps({"guess_open": {"boss": "vorkath", "what": "a unique"}}))
        g = await next_of(a, "guess")
        self.assertEqual((g["boss"], g["kc"], g["open"], g["count"]), ("vorkath", 40, True, 0))
        await a.send(json.dumps({"guess": {"id": g["id"], "kc": 45}}))
        await b.send(json.dumps({"guess": {"id": g["id"], "kc": 52}}))
        await b.send(json.dumps({"guess": {"id": g["id"], "kc": 39}}))        # already past: thrown away
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
        line = await next_of(b, "chat")
        self.assertIn("moss guessed closest", line["text"])

    async def test_the_desk_hears_what_the_portal_uploads_each_second(self):
        tx, desk, a, b = await self.setup_room()
        frame = struct.pack(">I", 5) + b"\xff\xd8" + b"x" * 10000
        for _ in range(4):
            await tx.send(frame)
        up = await next_of(desk, "upload", timeout=2.5)
        while up["bps"] == 0:
            up = await next_of(desk, "upload", timeout=2.5)
        # two viewers got four frames of ~10 KB each: about 80 KB in that second, give or take the timing
        self.assertGreater(up["bps"], 200000)
        self.assertEqual(up["viewers"], 2)

    async def test_the_desk_hears_when_the_picture_is_paused_and_back(self):
        tx, desk, a, b = await self.setup_room()
        await tx.send('{"state": "shut"}')
        r = await next_of(desk, "relay")
        while r["open"]:
            r = await next_of(desk, "relay")
        self.assertTrue(r["live"])                       # still on air: only paused
        await tx.send('{"state": "open"}')
        while not (await next_of(desk, "relay"))["open"]:
            pass

    async def test_the_broadcasts_title_reaches_viewers_and_newcomers_in_one_line(self):
        # (11/06/2026: the title beside "✼ portal" on Outside)
        tx, desk, a, b = await self.setup_room()
        await desk.send(json.dumps({"title": "  zulrah\n until the pet "}))
        r = await next_of(a, "relay")
        while not r["title"]:
            r = await next_of(a, "relay")
        self.assertEqual(r["title"], "zulrah until the pet")
        late = await self.connect("/watch/sburb")
        self.assertEqual((await next_of(late, "relay"))["title"], "zulrah until the pet")
        await desk.send(json.dumps({"title": "y" * 500}))
        while len(r["title"]) != relay.TITLE_MAX:
            r = await next_of(a, "relay")

    async def test_the_desk_needs_the_key_and_the_public_side_refuses_it(self):
        await self.connect("/transmit/sburb?key=secret-key")
        bad = await self.connect("/desk/sburb?key=wrong-key-xx")
        self.assertEqual(await bad.recv_close(), ("close", 4003))
        for path in ("/desk/sburb?key=secret-key", "/sound/sburb?key=secret-key"):
            c = await Client().open(self.public_port, path)
            self.clients.append(c)
            self.assertEqual(c.status, 403)


class Sound(RelayTest):
    """The sound (11/05/2026): held as the frames are, and only while the picture is open."""

    def packet(self, bus=1):
        return struct.pack(">I", 99) + b"AU" + bytes([bus, 1]) + struct.pack(">H", 3) + b"opu"

    async def test_sound_reaches_viewers_after_the_hold_while_open(self):
        tx = await self.connect("/transmit/sburb?key=secret-key")
        snd = await self.connect("/sound/sburb?key=secret-key")
        v = await self.connect("/watch/sburb")
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
        self.assertGreaterEqual(time.monotonic() - t0, relay.SOUND_HOLD - 0.05)

    async def test_static_keeps_the_sound_going_and_says_so(self):
        tx = await self.connect("/transmit/sburb?key=secret-key")
        snd = await self.connect("/sound/sburb?key=secret-key")
        desk = await self.connect("/desk/sburb?key=secret-key&name=sburb")
        v = await self.connect("/watch/sburb")
        await tx.send('{"state": "static"}')             # his static key: the picture obscured
        st = await next_of(desk, "relay")
        while not st.get("static"):
            st = await next_of(desk, "relay")
        self.assertFalse(st["open"])
        await snd.send(self.packet())
        while True:
            k, payload = await v.recv(timeout=relay.SOUND_HOLD + 1)
            if k == "binary":
                break
        self.assertEqual(payload, self.packet())          # heard all the same

    async def test_paused_or_off_air_nothing_is_heard(self):
        tx = await self.connect("/transmit/sburb?key=secret-key")
        snd = await self.connect("/sound/sburb?key=secret-key")
        v = await self.connect("/watch/sburb")
        await tx.send('{"state": "open"}')
        await asyncio.sleep(0.05)
        await snd.send(self.packet())
        await asyncio.sleep(0.1)
        await tx.send('{"state": "shut"}')             # paused before the hold was up: taken back
        got = []
        try:
            while True:
                k, payload = await v.recv(timeout=relay.SOUND_HOLD + 0.3)
                if k == "binary":
                    got.append(payload)
        except asyncio.TimeoutError:
            pass
        self.assertEqual(got, [])
        self.assertIsNone(self.relay.channels["sburb"].last_frame)    # sound is never kept as the last frame

    async def test_when_the_sound_stops_viewers_hear_it_is_off(self):
        await self.connect("/transmit/sburb?key=secret-key")
        snd = await self.connect("/sound/sburb?key=secret-key")
        v = await self.connect("/watch/sburb")
        await snd.send('{"sound": {"mode": "game", "buses": [1]}}')
        await next_of(v, "sound")
        snd.close()
        self.assertEqual((await next_of(v, "sound"))["mode"], "off")


class SettleWords(unittest.TestCase):
    """Settling (11/08/2026; docs/protocol.md "Settling"): what a POST /home asks for, its two decimals and the code, as
    the Worker's settle.ts has them."""

    def test_two_decimals_with_a_half_going_up_as_javascript_rounds(self):
        # (each as node's Math.round(x * 100) / 100 gives it; Python's round(0.125, 2) is 0.12)
        got = [relay.two_places(x) for x in (0.125, -0.125, 12.345, -122.4567, 1.005, -45.555, 180, -0.001, 84.999)]
        self.assertEqual(got, [0.13, -0.12, 12.35, -122.46, 1, -45.55, 180, 0, 85])

    def test_one_of_a_place_an_outstation_or_clear(self):
        w = relay.settle_wanted
        self.assertEqual(w({"code": "x", "lon": 1.234, "lat": -5}), ("home", {"lon": 1.23, "lat": -5}))
        self.assertEqual(w({"near": "moss", "other": 1}), ("home", {"near": "moss"}))
        self.assertEqual(w({"clear": True}), ("home", None))
        self.assertEqual(w([]), ("error", relay.SETTLE_SHAPE))
        for body in ({}, {"clear": True, "near": "moss"}, {"lon": 1, "lat": 2, "near": "moss"}):
            self.assertEqual(w(body), ("error", "one of a place (lon and lat), an outstation (near) or clear"), body)
        for body in ({"lon": float("nan"), "lat": 0}, {"lon": float("inf"), "lat": 0}, {"lon": 10 ** 400, "lat": 0},
                     {"lon": False, "lat": 0}, {"lon": 180.001, "lat": 0}):
            self.assertEqual(w(body), ("error", "lon is a number from -180 to 180"), body)
        self.assertEqual(w({"lon": 0, "lat": -85.001}), ("error", "lat is a number from -85 to 85"))
        self.assertEqual(w({"near": "../moss"}), ("error", "no outstation called that here"))
        self.assertEqual(w({"clear": "yes"}), ("error", "clear is true"))

    def test_a_code_is_twelve_letters_and_digits_and_never_the_same(self):
        codes = {relay.settle_code() for _ in range(500)}
        self.assertEqual(len(codes), 500)
        self.assertTrue(all(relay.SETTLE_CODE.fullmatch(c) for c in codes))
        self.assertEqual(set("".join(codes)), set(relay.SETTLE_ALPHABET))


class Settling(RelayTest):
    """Settling (11/08/2026): what the shared tests (tests/protocol_test.py, Settling) can't wait for, and the public side."""

    async def code(self, channel="sburb", key="secret-key"):
        status, got = await ask(self.port, "POST", "/settle/%s?key=%s&v=0.9.0" % (channel, key))
        self.assertEqual(status, 200)
        return got["code"]

    async def test_a_week_on_it_may_move_again_and_a_clear_takes_it_back_to_beside_its_inviter(self):
        await self.connect("/transmit/sburb?key=secret-key")
        await self.connect("/transmit/moss?key=moss-key-1")
        await asyncio.sleep(0.05)
        status, got = await ask(self.port, "POST", "/home/sburb", {"code": await self.code(), "near": "moss"})
        self.assertEqual((status, got["home"]), (200, {"near": "moss"}))
        ch = self.relay.channels["sburb"]
        self.assertEqual(ch.home_at + relay.SETTLE_EVERY_S * 1000, got["next"])
        code = await self.code()
        status, got = await ask(self.port, "POST", "/home/sburb", {"code": code, "clear": True})
        self.assertEqual(status, 429)
        ch.home_at -= relay.SETTLE_EVERY_S * 1000 + 1                      # (a week and a moment ago)
        status, got = await ask(self.port, "GET", "/settle/sburb?code=" + code)
        self.assertEqual((status, got["home"], got["next"]), (200, {"near": "moss"}, None))
        now = time.time() * 1000
        status, got = await ask(self.port, "POST", "/home/sburb", {"code": code, "clear": True})
        self.assertEqual((status, got["home"]), (200, None))
        self.assertTrue(now + relay.SETTLE_EVERY_S * 1000 - 1000 <= got["next"] <= now + relay.SETTLE_EVERY_S * 1000 + 1000)
        self.assertIsNone(ch.home)
        status, _, body = await get(self.port, "/channels")
        self.assertEqual({c["channel"]: c["home"] for c in json.loads(body)["channels"]}, {"sburb": None, "moss": None})

    async def test_a_code_lasts_as_long_as_the_relay_says(self):
        self.relay.settle_code_s = 0.2
        await self.connect("/transmit/sburb?key=secret-key")
        await asyncio.sleep(0.05)
        code = await self.code()
        self.assertEqual((await ask(self.port, "GET", "/settle/sburb?code=" + code))[0], 200)
        await asyncio.sleep(0.3)
        status, got = await ask(self.port, "GET", "/settle/sburb?code=" + code)
        self.assertEqual((status, got["error"]), (403, "that code has expired: open settling again from your outstation"))

    async def test_the_public_side_lets_the_page_settle_but_never_takes_a_key(self):
        await self.connect("/transmit/sburb?key=secret-key")
        await asyncio.sleep(0.05)
        self.assertEqual((await ask(self.public_port, "POST", "/settle/sburb?key=secret-key"))[0], 403)
        code = await self.code()
        status, got = await ask(self.public_port, "GET", "/settle/sburb?code=" + code)
        self.assertEqual((status, got["home"]), (200, None))
        for path in ("/settle/sburb", "/home/sburb"):
            self.assertEqual((await ask(self.public_port, "OPTIONS", path))[0], 204, path)
        self.assertEqual((await ask(self.public_port, "PUT", "/home/sburb", {"code": code}))[0], 403)
        status, got = await ask(self.public_port, "POST", "/home/sburb", {"code": code, "lon": 1, "lat": 2})
        self.assertEqual((status, got["home"]), (200, {"lon": 1, "lat": 2}))


class ArrayWords(unittest.TestCase):
    """The Array's events (11/08/2026): what a field report adds (relay.array_events; the Worker's arrayEvents, the
    same). The shared suite (tests/protocol_test.py, ArrayEvents) tests the feed on both relays."""

    def ev(self, kind, data=None):
        return relay.array_events({"type": kind, "text": "", "at": 1, "data": data} if data is not None else
                                  {"type": kind, "text": "", "at": 1})

    def test_each_kind_and_what_goes_with_it(self):
        self.assertEqual(self.ev("KILL_COUNT", {"boss": "Vorkath", "count": 412, "pb": True}),
                         [{"kind": "kill", "boss": "Vorkath", "kc": 412}])
        self.assertEqual(self.ev("LEVEL", {"skills": {"Ranged": 92, "Hitpoints": 90}, "total": 2000, "combat": 120}),
                         [{"kind": "level", "skill": "Ranged", "level": 92}, {"kind": "level", "skill": "Hitpoints", "level": 90}])
        self.assertEqual(self.ev("PET", {"pet": "Vorki", "duplicate": False}), [{"kind": "pet", "name": "Vorki"}])
        self.assertEqual(self.ev("PET", {"duplicate": True}), [{"kind": "pet"}])
        self.assertEqual(self.ev("PET"), [{"kind": "pet"}])                     # (no numbers at all: still a pet)
        self.assertEqual(self.ev("COLLECTION", {"item": "Draconic visage", "id": 11286, "done": 812, "total": 1568}),
                         [{"kind": "clog", "name": "Draconic visage"}])
        self.assertEqual(self.ev("CLUE", {"tier": "Elite", "count": 12}), [{"kind": "clue", "tier": "Elite", "count": 12}])
        self.assertEqual(self.ev("CLUE", {"tier": "Beginner"}), [{"kind": "clue", "tier": "Beginner"}])
        for kind in ("QUEST", "SLAYER", "DEATH", "XP_MILESTONE", "COMBAT_ACHIEVEMENT", "SPEEDRUN", None):
            self.assertEqual(self.ev(kind, {"boss": "Vorkath", "count": 1, "pet": "x"}), [], kind)
        for nothing in (None, "x", 5, ["x"], {"data": {"boss": "x", "count": 1}}):
            self.assertEqual(relay.array_events(nothing), [], nothing)
        self.assertEqual(self.ev("KILL_COUNT", {"boss": "Vorkath"}), [])        # (a kill needs its boss and its count)
        self.assertEqual(self.ev("KILL_COUNT", {"count": 5}), [])
        self.assertEqual(self.ev("LEVEL", {"total": 2000}), [])

    def test_a_drop_counts_from_a_million_its_best_item_named(self):
        def drop(*items):
            return self.ev("LOOT", {"items": [{"name": n, "quantity": q, "price": p} for n, q, p in items],
                                    "value": sum(q * p for _, q, p in items)})
        self.assertEqual(drop(("Dragon bones", 300, 3333)), [])                 # (999,900)
        self.assertEqual(drop(("Coins", 1000000, 1)), [{"kind": "loot", "name": "Coins", "value": 1000000}])
        self.assertEqual(drop(("Dragon bones", 2, 3000), ("Skeletal visage", 1, 1200000), ("Coins", 600000, 2)),
                         [{"kind": "loot", "name": "Skeletal visage", "value": 2406000}])
        self.assertEqual(drop(("Coins", 500000, 2), ("Gold bar", 1000000, 1)),  # (a tie: the first)
                         [{"kind": "loot", "name": "Coins", "value": 2000000}])
        self.assertEqual(self.ev("LOOT", {"value": 5000000}), [{"kind": "loot", "value": 5000000}])

    def test_numbers_are_whole_and_names_one_line(self):
        w = relay.array_whole
        self.assertEqual([w(5), w(5.0), w(0), w(2 ** 53 - 1)], [5, 5, 0, 2 ** 53 - 1])
        self.assertIsInstance(w(5.0), int)                                     # (5, not 5.0: as the Worker writes it)
        for bad in (-1, 5.5, 2 ** 53, True, False, "5", None, float("nan"), float("inf"), [5]):
            self.assertIsNone(w(bad), bad)
        self.assertEqual(self.ev("KILL_COUNT", {"boss": "  The\nNightmare\t " + "x" * 80, "count": 3}),
                         [{"kind": "kill", "boss": ("The Nightmare " + "x" * 80)[:60], "kc": 3}])
        self.assertEqual(self.ev("LEVEL", {"skills": {"Attack": "99", "Magic": -1, "Prayer": True, "  ": 5, "9": 9,
                                                      "Strength": 99.0, "x" * 25: 1}}),
                         [{"kind": "level", "skill": "Strength", "level": 99}])  # (a skill's name: a letter, 24 at most)
        self.assertEqual(self.ev("PET", {"pet": 5}), [{"kind": "pet"}])


class ArrayFeedRing(unittest.TestCase):
    """The Array's feed (relay.ArrayFeed; the Worker's ArrayFeed, the same): the last 500, ten a minute from a channel,
    ids that go up, and reading by cursor."""

    def test_ten_a_minute_from_one_channel_the_rest_dropped(self):
        f, pet = relay.ArrayFeed(now=0), {"kind": "pet"}
        self.assertEqual(len(f.add("moss", [pet] * 12, now=1000)), 10)
        self.assertEqual(f.add("moss", [pet], now=30000), [])
        self.assertEqual(len(f.add("fern", [pet] * 3, now=30000)), 3)        # (another channel: its own ten)
        self.assertEqual(f.add("moss", [pet], now=60999), [])
        self.assertEqual(len(f.add("moss", [pet] * 12, now=61000)), 10)      # (a minute on: ten more)
        self.assertEqual(sorted(f.recent), ["fern", "moss"])
        f.add("peat", [pet], now=200000)
        self.assertEqual(sorted(f.recent), ["peat"])                          # (a quiet channel's times go)

    def test_ids_go_up_each_its_own_even_in_one_ms_and_past_a_restart(self):
        f = relay.ArrayFeed(now=5000)
        got = f.add("moss", [{"kind": "pet"}] * 3, now=5000) + f.add("fern", [{"kind": "pet"}], now=4000)
        self.assertEqual([(e["id"], e["at"]) for e in got], [(5001, 5000), (5002, 5000), (5003, 5000), (5004, 4000)])
        self.assertEqual(f.add("moss", [{"kind": "clue"}], now=9000)[0]["id"], 9000)
        later = relay.ArrayFeed(now=20000)                                     # (started again: its ids are past them all)
        self.assertEqual(later.read(since=9000), {"events": [], "next": 9000})
        e = later.add("moss", [{"kind": "pet"}], now=20000)[0]
        self.assertEqual((e["id"], later.read(since=9000)["events"]), (20001, [e]))

    def test_the_last_500_kept_and_read_by_cursor(self):
        f = relay.ArrayFeed(now=0)
        for i in range(60):                                                    # (60 channels of 10: 600 events)
            f.add("c%d" % i, [{"kind": "level", "skill": "Attack", "level": n} for n in range(10)], now=1000 + i)
        ids = [e["id"] for e in f.events]
        self.assertEqual((len(ids), ids[0], ids[-1]), (500, ids[-1] - 499, f.last))
        first = f.read()
        self.assertEqual((len(first["events"]), first["next"]), (50, f.last))
        self.assertEqual(first["events"], list(f.events)[-50:])
        page = f.read(since=0)
        self.assertEqual((len(page["events"]), page["events"][0]["id"], page["next"]), (100, ids[0], ids[99]))
        self.assertEqual(f.read(since=ids[449])["events"], list(f.events)[450:])
        self.assertEqual(f.read(since=f.last), {"events": [], "next": f.last})
        self.assertEqual(f.read(since=f.last + 10 ** 6), {"events": [], "next": f.last})   # (past the newest: the newest)
        empty = relay.ArrayFeed(now=7)
        self.assertEqual((empty.read(), empty.read(since=3)), ({"events": [], "next": 7}, {"events": [], "next": 3}))


class AetherWords(unittest.TestCase):
    """The aether (11/07/2026): a line cleaned row by row (relay.aether_text; the Worker's aetherText, the same), and a
    visitor's name (relay.aether_name). The shared suite (tests/protocol_test.py, Aether) tests the room on both relays."""

    def test_a_line_keeps_its_rows_twelve_of_eighty_at_most_its_ends_tidied(self):
        t = relay.aether_text
        self.assertEqual(t("  a  \n\n   b\t \n"), "  a\n\n   b")              # (leading spaces and inner empty rows stay)
        self.assertEqual(t("\n \n x\n\n  \n"), " x")                           # (empty rows at the ends go)
        self.assertEqual(t("y" * 81), "y" * 80)                                # (a row's cut, not refused)
        self.assertEqual(t("x" * 78 + " " * 5 + "y"), "x" * 78)                # (cut at 80, then its trailing spaces)
        self.assertEqual(t("\n".join(str(i) for i in range(13))), "\n".join(str(i) for i in range(12)))
        self.assertEqual(t("\n".join(["a"] + [""] * 11 + ["b"])), "a")         # (the 12th row empty: dropped; b, the 13th, gone)
        full = t("\n".join(["z" * 85] * 14))
        self.assertEqual((len(full.replace("\n", "")), full.count("\n")), (960, 11))   # (960 in all, the newlines aside)
        self.assertEqual(t("\u00e9" * 81 + "\n" + "\U0001f600" * 81), "\u00e9" * 80 + "\n" + "\U0001f600" * 80)   # (code points)

    def test_control_and_direction_characters_go_but_the_newline(self):
        t = relay.aether_text
        self.assertEqual(t("a\x07b\tc\u202ed\r\ne\u200bf\x85g\u2028h\u3000"), "abcd\nefgh")
        self.assertEqual(t(" \u00a0x\u00a0 \u3000"), " \u00a0x")                       # (any space leads; none trails)
        self.assertEqual(t("\x01\x02 \n \t\n\u200b\ufeff"), "")                      # (nothing once cleaned: ignored)
        for v in (None, 5, ["x"], {"say": "x"}):
            self.assertEqual(t(v), "")

    def test_a_visitors_name(self):
        n = relay.aether_name
        self.assertEqual(n("  moss   fern "), "moss fern")
        self.assertEqual(n("Moss_Fern-2.0"), "Moss_Fern-2.0")
        self.assertEqual(n("x" * 20), "x" * 20)
        for bad in ("", "   ", "x" * 21, "moss!", "mos\ts", "moss\u00e9", "moss\u200b", None, 5):
            self.assertEqual(n(bad), "", bad)
        self.assertEqual(relay.channel_form("Moss Fern"), "mossfern")


class AetherRate(unittest.TestCase):
    """The aether's rate (relay.aether_wait and aether_recent; the Worker's aetherWait and aetherRecent, the same): a line
    every 2 s and 15 a minute from someone at most, and how long to wait past that."""

    def test_one_line_every_two_seconds(self):
        w = relay.aether_wait
        self.assertEqual(w([], 10000), 0)
        self.assertEqual(w([9500], 10000), 1.5)
        self.assertEqual(w([8000], 10000), 0)
        self.assertEqual(w([8001], 10000), 0.1)                                # (rounded up to a tenth)
        self.assertEqual(w([9999], 10000), 2)
        self.assertIsInstance(w([9999], 10000), int)                           # (2, not 2.0: as the Worker writes it)

    def test_fifteen_a_minute(self):
        w, now = relay.aether_wait, 100000
        times = [now - 59000 + i * 3000 for i in range(15)]                    # (the last 17 s ago)
        self.assertEqual(w(times, now), 1)                                     # (till the oldest is a minute old)
        self.assertEqual(w(times[1:], now), 0)
        self.assertEqual(w(times, now + 1000), 0)
        self.assertEqual(relay.aether_recent([now - 60000, now - 59999] + times, now), times)   # (the minute's last 15)


async def keeper(port, method, path, token, body=None):
    """(status, JSON or the body) of one of the aether keeper's routes, with this token."""
    data = json.dumps(body).encode() if body is not None else b""
    r, w = await asyncio.open_connection("127.0.0.1", port)
    w.write(CRLF.join(["%s %s HTTP/1.1" % (method, path), "Host: x", "Authorization: Bearer " + token,
                       "Content-Type: application/json", "Content-Length: %d" % len(data), "", ""]).encode() + data)
    await w.drain()
    raw = await r.read()
    w.close()
    head, _, got = raw.partition((CRLF * 2).encode())
    try:
        got = json.loads(got)
    except ValueError:
        pass
    return int(head.split(b" ")[1]), got


class AetherRoom(RelayTest):
    """The aether's room on this relay: who's here, its public side, and its keeper's token."""

    async def test_whos_here_is_told_at_most_every_two_seconds_and_a_device_counts_once(self):
        a = await self.connect("/aether?name=moss&device=" + "a" * 16)
        self.assertEqual(await next_of(a, "aether_here"), 1)
        t = time.monotonic()
        b = await self.connect("/aether?name=fern&device=" + "b" * 16)
        self.assertEqual(await next_of(b, "aether_here"), 2)                   # (whoever comes hears it at once)
        b2 = await self.connect("/aether?name=fern&device=" + "b" * 16)        # (the same device again: still 2)
        self.assertEqual(await next_of(b2, "aether_here"), 2)
        self.assertEqual(await next_of(a, "aether_here", timeout=3), 2)        # (everyone else, its turn come)
        self.assertGreater(time.monotonic() - t, 1.5)

    async def test_one_told_a_count_the_others_werent_hears_the_next_turns(self):
        # (everyone's told 2; both go; one comes and is told 1 at once, then another: the count's 2 again, what the rest
        # were last told, and the first still hears it at the next turn)
        a = await self.connect("/aether?name=moss&device=" + "a" * 16)
        await self.connect("/aether?name=fern&device=" + "b" * 16)
        while await next_of(a, "aether_here", timeout=3) != 2:
            pass
        for x in self.clients:
            x.close()
        await asyncio.sleep(0.1)
        c = await self.connect("/aether?name=peat&device=" + "c" * 16)
        self.assertEqual(await next_of(c, "aether_here"), 1)
        d = await self.connect("/aether?name=bog&device=" + "d" * 16)
        self.assertEqual(await next_of(d, "aether_here"), 2)
        self.assertEqual(await next_of(c, "aether_here", timeout=3), 2)

    async def test_the_public_side_lets_visitors_in_and_the_read_but_no_outstation_or_keeper(self):
        self.relay.admin_token = "the-keepers-token-" + "x" * 8
        v = await Client().open(self.public_port, "/aether?name=moss&device=" + "a" * 16)
        self.clients.append(v)
        self.assertEqual(v.status, 101)
        self.assertEqual(await next_of(v, "aether_so_far"), [])
        await self.connect("/transmit/sburb?key=secret-key")
        for path in ("/aether?channel=sburb&key=secret-key", "/aether?channel=&name=moss&device=" + "b" * 16):
            c = await Client().open(self.public_port, path)
            self.clients.append(c)
            self.assertEqual(c.status, 403, path)
        status, kind, body = await get(self.public_port, "/aether")
        self.assertEqual((status, json.loads(body)), (200, {"lines": [], "here": 1}))
        self.assertEqual((await keeper(self.public_port, "GET", "/admin/aether", self.relay.admin_token))[0], 403)
        self.assertEqual((await keeper(self.port, "GET", "/admin/aether", self.relay.admin_token))[0], 200)

    async def test_without_a_token_the_keepers_routes_are_refused(self):
        for token in ("", "short"):
            self.relay.admin_token = token
            self.assertEqual(await keeper(self.port, "GET", "/admin/aether", token), (403, b"no"))
            self.assertEqual((await keeper(self.port, "POST", "/admin/aether/ban", token, {"id": 1}))[0], 403)

    async def test_the_delay_doesnt_hold_it_back_and_a_bad_channel_isnt_upgraded(self):
        self.relay.delay = 30.0
        a = await self.connect("/aether?name=moss&device=" + "a" * 16)
        await a.send(json.dumps({"say": "now"}))
        self.assertEqual((await next_of(a, "aether"))["text"], "now")
        c = await Client().open(self.port, "/aether?channel=no%20such&key=secret-key")
        self.clients.append(c)
        self.assertEqual(c.status, 400)


if __name__ == "__main__":
    unittest.main(verbosity=1)
