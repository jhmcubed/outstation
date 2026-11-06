"""the portal's relay, for testing on this computer (claude/the-portal.md).

One broadcaster per channel sends; any number of viewers watch. Viewers
send nothing that counts: whatever they send is thrown away. The real
relay goes on Cloudflare later; this one is the same protocol on
localhost, standard library only (no packages to install).

    py tools/portal/relay.py                 # ws://127.0.0.1:8786
    py tools/portal/relay.py --delay 30      # everything held back 30 s
    py tools/portal/relay.py --port 8898 --host 0.0.0.0

The protocol (the plugin's PortalSocket speaks it):
    transmit:  ws://HOST:PORT/transmit/<channel>?key=<secret>&code=<watch code>
    watch:     ws://HOST:PORT/watch/<channel>?code=<watch code>
  The first transmitter on a channel claims it with its key; later
  transmitters need the same key (one at a time: a new one replaces the
  old). Viewers need the code the transmitter gave (empty = anyone).
  Text messages are the game data (JSON), binary messages are frames
  (4-byte big-endian game tick, then a JPEG). The relay keeps the last of
  each, so a viewer who joins sees something at once, and sends viewers
  {"relay": {...}} text lines about the channel (live or not, viewers).

    dink:      POST http://HOST:PORT/dink/<channel>?key=<secret>
  Dink (the Plugin Hub's notifier, already approved) can post its
  notifications here as it would to a Discord webhook. Only the kinds in
  REPORTS pass, as {"report": {"type", "text", "at"}} to viewers (the last
  few kept for late joiners); screenshots and anything naming another
  player are dropped. The key is the channel's transmit key.
  With forward rules (forward.py; ~/Documents/ghillie portal/forward.json),
  the posts Discord would have got under Jace's old Dink settings go on to
  his friends' Discord webhook as Dink sent them, so Dink can be set more
  generously for the portal and point only here.

    py tools/portal/relay.py --no-forward    # the portal only, nothing on to Discord

Talking back (11/05/2026, Jace: "allow me to receive messages from viewers in
my own chat box on my computer that i can respond back in"):
    desk:      ws://HOST:PORT/desk/<channel>?key=<secret>&name=<his name in the chat>
  Jace's chat window (tools/launcher/portal_chat.py). Several may be open.
  It gets the chat (the last CHAT_KEPT lines at once), the poll, the pings
  and the status lines, and sends:
    {"say": text}                         to everyone, as him
    {"hide": id}                          takes a line back off everyone's page
    {"block": who} / {"unblock": who}     a viewer's lines and pings stop going anywhere
    {"poll": {"question", "options": [2-6], "seconds"}}   / {"poll_close": true}
    {"chat_on": bool}                     off: viewers' lines go nowhere (his still go out; the status line's "chat")
    {"title": text}                       the broadcast's title (TITLE_MAX long, "" for none): the status line's
                                          "title", beside "✼ portal" on Outside
  and gets {"viewers": [{"who", "name", "waiting"}]} whenever who's watching changes (a name as given, never
  a device).
  Viewers may send (anything else they send is still thrown away):
    {"hello": {"name", "device"}}         their name in the chat; device: a random id the page keeps
    {"say": text}                         at most SAY_BURST lines in SAY_WINDOW seconds, SAY_MAX long
    {"vote": {"poll": id, "pick": i}}     one vote a device, changeable while the poll is open
    {"ping": {"x", "y"}}                  a spot on the frame (0-1 each): it flashes on his screen
                                          and on every viewer's portal; one every PING_EVERY seconds
  and get {"chat": {...}}, {"unsay": id}, {"poll": {...}}, {"poll_gone": id} (POLL_KEPT
  seconds after it closed), {"ping": {...}}.
  No moderation beyond Jace's own hide and block; the limits are only there
  so one script can't freeze his window and everyone's page.

    sound:     ws://HOST:PORT/sound/<channel>?key=<secret>
  The sound (sound.py): binary messages, 4 bytes when it was taken (ms, the
  frames' own clock), b"AU", the bus (1 the game, 2 everything else), how
  many Opus packets, then each as a 2-byte length and the packet; and a
  {"sound": {"mode", "buses"}} line. The relay holds sound SOUND_HOLD seconds
  (as the capture holds frames back) and lets it through only while the
  picture is live and open: paused or minimised, nothing is heard either.
  The transmit, desk and sound roles need the key, and the public side
  (the Funnel's port) refuses all three.

The client's version (11/06/2026): outstation's client adds v=<its version> to the transmit, desk and
sound addresses. With --min-client set, a client that says an older version is turned away with 4026 and
the oldest version allowed in the reason, so it can say where to get a newer one instead of trying again.
A connection that says no version (ghillie's Python portal, which predates it) is let in as before.
"""

import argparse
import asyncio
import base64
import hashlib
import json
import os
import re
import struct
import sys
import time
from collections import deque
from email import policy
from email.parser import BytesParser
from urllib.parse import parse_qs, unquote, urlsplit

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from forward import DEFAULT as DEFAULT_FORWARD, Forward  # noqa: E402

GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
MAX_MESSAGE = 512 * 1024          # a frame is ~10-30 KB; anything bigger is refused
VIEWER_BUFFER = 1024 * 1024       # a slow viewer skips frames past this much queued
MAX_POST = 16 * 1024 * 1024       # a Dink post with a screenshot attached
# clips on a player's Outside page (11/06/2026; the Worker's clips.ts, docs/protocol.md "Clips"): in clips_dir/<channel>
MAX_CLIP, MAX_STRIP, MAX_STILL, MAX_DUR = 8 * 1024 * 1024, 256 * 1024, 16 * 1024, 20
CLIPS_MOST, CLIPS_DAYS, CLIPS_AN_HOUR = 20, 30, 30
# messages between portals (11/06/2026, Jace: "allow for messaging friends directly"): to a channel's desk, or held
# for it till its portal next opens (at most DMS_KEPT, DMS_DAYS each); a portal may send DMS_A_MINUTE a minute
DMS_KEPT, DMS_DAYS, DMS_A_MINUTE = 20, 7, 20
KEPT_MOST = 3                     # kept for good (never in the 20, never too old): the Worker's KEPT_MOST
CLIP_ID = re.compile(r"^[A-Za-z0-9_-]{16,32}$")
DEFAULT_CLIPS = os.path.join(os.path.expanduser("~"), "Documents", "ghillie portal", "clips")
VIEWER_TEXT = 2048                # anything longer from a viewer is thrown away unread
CHAT_KEPT = 50                    # lines a late viewer (or a newly opened desk) gets at once
SAY_MAX, SAY_BURST, SAY_WINDOW = 300, 20, 10.0   # 20 lines in 10 s a viewer: room to spam a little for fun (Jace, 11/05)
NAME_MAX = 20
TITLE_MAX = 100                   # the broadcast's title (11/06/2026, Jace: "the option to title broadcast")
PING_EVERY = 0.5                  # a ping a viewer every half second (1.5 until Jace asked for more, 11/05)
POLL_OPTIONS, POLL_SECONDS = (2, 6), (10, 900)
THUMB_FRESH = 3.0                 # a small frame this recent, and lookers get those instead of the full ones
LOOK_EVERY = 0.5                  # a looker (the listening post's receiver) gets a frame at most this often
SIGNAL_EVERY = 0.08               # a viewer's ✼ (the signal button): as often as a hand can press (Jace, 11/05: "allow for
                                  # spamming"), a dozen a second at most, so one script can't flood everyone's page
GUESS_MIN_VALUE = 1_000_000       # a drop from the guessed boss this big ends a guess round by itself (settings' guess_min_value)
GUESS_KEPT = 120.0                # a decided round's result stays this long
POLL_KEPT = 90.0                  # a closed poll's result stays this long, then {"poll_gone"} (Jace, 11/05)
DINK_SAME = 10.0                  # the same Dink post again within this long is dropped (a webhook listed twice in Dink)
CLIENT_ROLES = ("transmit", "desk", "sound")   # the roles that say the client's version (v=)
SOUND_HOLD = 0.5                  # the capture's HOLD_BACK: a pause takes the last moment's sound back too
CONTROL = re.compile(r"[\x00-\x1f\x7f​-‏‪-‮⁦-⁩]")
# Dink's notification kinds that pass to viewers (none of them name another player,
# except a death in PvP, whose text is dropped)
REPORTS = {"LEVEL", "XP_MILESTONE", "LOOT", "QUEST", "CLUE", "KILL_COUNT", "COLLECTION", "PET",
           "SLAYER", "SPEEDRUN", "COMBAT_ACHIEVEMENT", "ACHIEVEMENT_DIARY", "BARBARIAN_ASSAULT_GAMBLE",
           "DEATH", "LEAGUES_AREA", "LEAGUES_RELIC", "LEAGUES_TASK", "GRAND_EXCHANGE"}
# the page the relay serves (self-hosting: a friend opens http://<this PC>:8786/?channel=...&code=...)
HERE = os.path.dirname(os.path.abspath(__file__))
WEB = os.path.join(HERE, "web")
PAGE_FILES = {
    "/": (os.path.join(WEB, "portal.html"), "text/html; charset=utf-8"),
    "/portal.html": (os.path.join(WEB, "portal.html"), "text/html; charset=utf-8"),
    "/portal.js": (os.path.join(WEB, "portal.js"), "text/javascript; charset=utf-8"),
    "/portal.css": (os.path.join(WEB, "portal.css"), "text/css; charset=utf-8"),
    # portal.css reaches the site's font by ../../../public/..., which from / lands here
    "/public/media/fonts/runescape-chat.woff2": (
        os.path.join(HERE, "..", "..", "public", "media", "fonts", "runescape-chat.woff2"), "font/woff2"),
}


class Closed(Exception):
    pass


class Socket:
    """One WebSocket connection (server side)."""

    def __init__(self, reader, writer):
        self.reader, self.writer = reader, writer
        self.closed = False

    async def recv(self):
        """The next text (str) or binary (bytes) message; Closed at the end."""
        parts, first_op = [], None
        while True:
            head = await self.reader.readexactly(2)
            fin, op = head[0] & 0x80, head[0] & 0x0F
            masked, n = head[1] & 0x80, head[1] & 0x7F
            if n == 126:
                n = struct.unpack(">H", await self.reader.readexactly(2))[0]
            elif n == 127:
                n = struct.unpack(">Q", await self.reader.readexactly(8))[0]
            if n > MAX_MESSAGE:
                raise Closed("too big")
            mask = await self.reader.readexactly(4) if masked else None
            data = await self.reader.readexactly(n)
            if mask:
                data = bytes(b ^ mask[i & 3] for i, b in enumerate(data))
            if op == 8:
                raise Closed("closed")
            if op == 9:
                await self.send_raw(10, data)
                continue
            if op == 10:
                continue
            if op in (1, 2):
                first_op, parts = op, [data]
            elif op == 0 and first_op:
                parts.append(data)
            if fin and first_op:
                payload = b"".join(parts)
                if sum(map(len, parts)) > MAX_MESSAGE:
                    raise Closed("too big")
                return payload.decode("utf-8") if first_op == 1 else payload

    async def send_raw(self, op, data):
        if self.closed:
            return
        n = len(data)
        if n < 126:
            head = struct.pack(">BB", 0x80 | op, n)
        elif n < 65536:
            head = struct.pack(">BBH", 0x80 | op, 126, n)
        else:
            head = struct.pack(">BBQ", 0x80 | op, 127, n)
        try:
            self.writer.write(head + data)
            await self.writer.drain()
        except (ConnectionError, RuntimeError):
            self.closed = True

    def send_nowait(self, msg):
        """Queue a message without waiting (fan-out); False if dropped."""
        if self.closed:
            return False
        binary = isinstance(msg, (bytes, bytearray))
        if binary and self.writer.transport.get_write_buffer_size() > VIEWER_BUFFER:
            return False
        data = bytes(msg) if binary else msg.encode("utf-8")
        op, n = (2 if binary else 1), len(data)
        if n < 126:
            head = struct.pack(">BB", 0x80 | op, n)
        elif n < 65536:
            head = struct.pack(">BBH", 0x80 | op, 126, n)
        else:
            head = struct.pack(">BBQ", 0x80 | op, 127, n)
        try:
            self.writer.write(head + data)
        except (ConnectionError, RuntimeError):
            self.closed = True
            return False
        return True

    async def close(self, code=1000, reason=""):
        if not self.closed:
            await self.send_raw(8, struct.pack(">H", code) + reason.encode()[:120])
            self.closed = True
        try:
            self.writer.close()
        except Exception:
            pass


SUMMARY_GAP = 15 * 60             # off air longer than this, and the next go-live begins a new stream's summary
SUMMARY_PETS = 5


def fold(summary, report):
    """One field report into a stream's summary (11/06/2026, Jace: the readings the same for everyone, and a recap
    off air): what the stream's done so far, from Dink's numbers. Returns whether it changed."""
    d = report.get("data") if isinstance(report, dict) else None
    if not isinstance(d, dict):
        return False
    kind = report.get("type")
    if kind == "KILL_COUNT" and d.get("boss") and isinstance(d.get("count"), int):
        k = summary.setdefault("kc", {})
        b = k.setdefault(d["boss"].lower(), {"name": d["boss"], "first": d["count"] - 1, "last": d["count"]})
        b["last"] = max(b["last"], d["count"])
        if d.get("pb"):
            b["pb"] = True
        summary["boss"] = d["boss"]
    elif kind == "LOOT" and isinstance(d.get("value"), int) and d["value"] > 0:
        summary["loot"] = summary.get("loot", 0) + d["value"]
        for i in d.get("items") or []:
            v = (i.get("quantity") or 0) * (i.get("price") or 0)
            if v > (summary.get("best") or {}).get("value", 0):
                summary["best"] = dict({"name": i.get("name"), "value": v}, **({"id": i["id"]} if i.get("id") else {}))
    elif kind == "LEVEL" and isinstance(d.get("skills"), dict):
        summary.setdefault("levels", {}).update(d["skills"])
        if d.get("total"):
            summary["total"] = d["total"]
    elif kind == "COLLECTION" and d.get("total"):
        summary["log"] = {k: d[k] for k in ("item", "id", "done", "total") if d.get(k) is not None}
    elif kind == "PET" and d.get("pet"):
        summary["pets"] = (summary.get("pets") or [])[-(SUMMARY_PETS - 1):] + [d["pet"]]
    else:
        return False
    return True


class Channel:
    def __init__(self, name, key):
        self.name, self.key = name, key
        self.code = ""
        self.sender = None
        self.viewers = set()
        self.waiting = []            # past the cap: in line for a seat, told the status, sent no frames
        self.cap = 0                 # (the relay's max_viewers, for the status line: 0 no cap)
        self.own_cap = None          # the desk's {"cap"}: this channel's own, in place of the relay's
        self.last_data = None
        self.last_frame = None
        self.held = deque()          # (due time, message) while a delay is set
        self.reports = deque(maxlen=8)
        self.state = "open"          # the capture's last {"state"}: sound passes only while open
        self.desks = set()           # Jace's chat windows
        self.dink_seen = {}          # a Dink post's hash -> when: the same one twice in a row is one event
        self.last_thumb = None       # the newest small frame (4 bytes, b"TH", a small JPEG), for lookers only
        self.thumb_at = 0.0
        self.lookers = set()         # the listening post's receivers: no seat, a frame or two a second while they ask
        self.chat = deque(maxlen=CHAT_KEPT)
        self.chat_id = 0
        self.poll = None             # {"id", "question", "options", "votes": {device: pick}, "ends", "open"}
        self.poll_id = 0
        self.blocked = set()         # viewers' "who" and devices
        self.bans = {}               # who -> {"who", "name", "tags"}: banned from the desk (blocked, their lines taken back)
        self.pings_on = True         # the desk's {"pings": false}: viewers' pings go nowhere
        self.chat_on = True          # the desk's {"chat_on": false}: viewers' lines go nowhere (the host's still go out)
        self.title = ""              # the desk's {"title"}: the broadcast's title, in every status line
        self.host_names = {name.lower()}
        self.sounder = None
        self.sound_info = None
        self.sound_held = deque()
        self.out = 0                 # bytes sent to viewers so far (frames, sound, lines): his upload, for the desk
        self.acked = 0               # bytes viewers say they've had ({"got"}): proof the site is getting it
        self.acked_seen = (0, time.monotonic())
        self.replay = deque()        # (arrival, message): the last replay_seconds of frames and sound, for a clip
        self.kc = {}                 # boss (lower case) -> (its name, the kill count Dink last told of)
        self.guess = None            # the drop guess round (11/05/2026): see from_desk's guess_open
        self.guess_id = 0
        self.out_seen = (0, time.monotonic())
        self.clips_uploads = []      # when its clips came, for CLIPS_AN_HOUR
        self.dms = []                # messages to this portal held till its desk opens (send_dm)
        self.dms_sent = []           # when this portal sent its last messages, for DMS_A_MINUTE
        self.summary = None          # this stream's summary (fold): {"since", "ended", "boss", "kc", "loot", ...}

    def summary_line(self):
        return json.dumps({"summary": self.summary})

    def on_air(self):
        """A transmitter's come: a new stream's summary, unless it went off air only a little while ago."""
        now = int(time.time() * 1000)
        if self.summary is None or (self.summary.get("ended") and now - self.summary["ended"] > SUMMARY_GAP * 1000):
            self.summary = {"since": now}
        else:
            self.summary.pop("ended", None)
        self.tell_summary()

    def off_air(self):
        if self.summary is not None:
            self.summary["ended"] = int(time.time() * 1000)
            self.tell_summary()

    def tell_clip(self, msg):
        line = json.dumps(msg)
        for v in list(self.viewers) + list(self.desks) + list(self.lookers):
            v.send_nowait(line)

    def tell_summary(self):
        if self.summary is None:
            return
        line = self.summary_line()
        for v in list(self.viewers) + list(self.desks) + list(self.lookers):
            v.send_nowait(line)

    def doing(self):
        """What the stream's at now, for the open portals: the boss of the last kill count and its count."""
        s = self.summary or {}
        b = (s.get("kc") or {}).get((s.get("boss") or "").lower())
        return {"boss": b["name"], "kc": b["last"]} if b and self.sender is not None else None

    def status(self, place=0, you=None):
        """place: 0 a seat (or the transmitter); 1 and on, where a viewer stands in line for one
        (11/05/2026, Jace: at the cap the portal still shows, its viewers waiting for a seat).
        you: a seated viewer's own number in the chat, to know its own lines.
        open: the picture's going out (live, and not paused or minimised): the chat window's live light."""
        st = {"channel": self.name, "live": self.sender is not None, "viewers": len(self.viewers),
              "max": self.cap, "waiting": len(self.waiting),
              "open": self.sender is not None and self.state == "open",
              "static": self.sender is not None and self.state == "static",
              "set": self.key is not None, "pings": self.pings_on, "chat": self.chat_on,     # (set: someone has claimed it; Outside says "no portal here yet" when not)
              "title": self.title}
        if place:
            st["seat"], st["place"] = False, place
        if you is not None:
            st["you"] = you
        return json.dumps({"relay": st})

    def tell_viewers(self):
        """The channel's status line to every viewer, the desks, and the transmitter (how many watch);
        those in line hear it too, with their place. The desks get who's watching too."""
        line = self.status()
        for v in list(self.viewers) + list(self.desks) + list(self.lookers) + ([self.sender] if self.sender else []):
            v.send_nowait(line)
        for i, v in enumerate(list(self.waiting)):
            v.send_nowait(self.status(i + 1, you=getattr(v, "who", None)))
        self.tell_watching()

    def viewer_list(self):
        """Who's watching, for the desk (11/05/2026, Jace: "add viewer list"): each viewer's number, the name it gave
        in the chat ("visitor" until it gives one), and whether it's in line for a spot. Never a device or address."""
        out = [{"who": v.who, "name": getattr(v, "name", "") or "visitor", "waiting": False}
               for v in sorted(self.viewers, key=lambda v: getattr(v, "who", 0)) if hasattr(v, "who")]
        out += [{"who": v.who, "name": getattr(v, "name", "") or "visitor", "waiting": True}
                for v in self.waiting if hasattr(v, "who")]
        return json.dumps({"viewers": out})

    def tell_watching(self):
        if self.desks:
            line = self.viewer_list()
            for d in list(self.desks):
                d.send_nowait(line)

    def broadcast(self, line):
        """A line to every viewer, every desk and everyone in line for a spot (the chat, the poll, pings): those
        who can't see the picture yet can still talk (11/05/2026, Jace: "people who cannot view the live feed
        of the stream can still send messages in the chat")."""
        for v in list(self.viewers) + list(self.waiting) + list(self.desks):
            v.send_nowait(line)

    def seat(self, sock):
        """A seat: the viewer's status (with its number in the chat), the last data and frame, the
        reports, and the chat, poll and sound so far, as on arriving."""
        self.viewers.add(sock)
        sock.send_nowait(self.status(you=getattr(sock, "who", None)))
        if self.last_data:
            sock.send_nowait(self.last_data)
        if self.last_frame and self.sender is not None:
            sock.send_nowait(self.last_frame)    # (off air, no old frame for someone just come)
        for r in self.reports:
            sock.send_nowait(r)
        if self.summary is not None:
            sock.send_nowait(self.summary_line())
        self.late_joiner(sock)

    def seat_waiting(self, cap):
        """Seats freed (a viewer gone, the cap raised): the first in line take them."""
        self.cap = cap
        moved = False
        while self.waiting and (not cap or len(self.viewers) < cap):
            self.seat(self.waiting.pop(0))
            moved = True
        return moved

    def deliver(self, msg):
        # a small frame (11/05/2026: the open portals' tiles, the listening post, the client's live view): for lookers
        # only, never a viewer, and never the last frame a newcomer gets
        if isinstance(msg, (bytes, bytearray)) and len(msg) > 8 and msg[4:6] == b"TH":
            self.last_thumb, self.thumb_at = msg, time.monotonic()
            now, n = self.thumb_at, len(msg)
            for v in list(self.lookers):
                if v.look_frames and now - v.look_at >= LOOK_EVERY and v.send_nowait(msg):
                    v.look_at = now
                    self.out += n
            return
        if isinstance(msg, str) and msg.startswith('{"report"'):
            self.reports.append(msg)
            if self.summary is not None:
                try:
                    changed = fold(self.summary, json.loads(msg).get("report"))
                except ValueError:
                    changed = False
                if changed:
                    # (after the report itself has gone to viewers, below)
                    asyncio.get_event_loop().call_soon(self.tell_summary)
        elif isinstance(msg, str):
            self.last_data = msg
            if msg.startswith('{"state"'):
                try:
                    was, self.state = self.state, json.loads(msg).get("state", "open")
                except (ValueError, AttributeError):
                    was = self.state
                if self.state != was:
                    self.tell_viewers()      # (the desks' live light: paused, or back)
        else:
            self.last_frame = msg
        n = len(msg)
        for v in list(self.viewers):
            if v.send_nowait(msg):
                self.out += n
        # (lookers get full frames only from a transmitter that sends no small ones: an older capture)
        if self.lookers and isinstance(msg, (bytes, bytearray)) and time.monotonic() - self.thumb_at > THUMB_FRESH:
            now = time.monotonic()
            for v in list(self.lookers):
                if v.look_frames and now - v.look_at >= LOOK_EVERY and v.send_nowait(msg):
                    v.look_at = now
                    self.out += n

    def keep(self, msg, seconds):
        """The replay buffer: frames and sound as they came, the last `seconds` of them (0: none kept)."""
        if seconds <= 0:
            if self.replay:
                self.replay.clear()
            return
        now = time.monotonic()
        self.replay.append((now, msg))
        while self.replay and now - self.replay[0][0] > seconds:
            self.replay.popleft()

    def deliver_sound(self, msg):
        """Sound only while the picture is live and open (a pause or a minimise takes it too)."""
        if self.sender is None or self.state not in ("open", "static"):
            return                   # (static: the picture obscured by his static key, the sound going on)
        n = len(msg)
        for v in list(self.viewers):
            if v.send_nowait(msg):
                self.out += n

    # the chat

    def say(self, name, text, host=False, who=0, queued=False):
        """queued: said by someone in line for a spot, who can't see the picture yet (marked by the chat window and
        the page beside their name, 11/05/2026, Jace)."""
        self.chat_id += 1
        line = json.dumps({"chat": {"id": self.chat_id, "name": name, "text": text, "host": host, "who": who,
                                    "queued": queued, "at": int(time.time() * 1000)}})
        self.chat.append((self.chat_id, line))
        self.broadcast(line)

    def unsay(self, cid):
        before = len(self.chat)
        self.chat = deque(((i, l) for i, l in self.chat if i != cid), maxlen=CHAT_KEPT)
        if len(self.chat) != before:
            self.broadcast(json.dumps({"unsay": cid}))

    def last_name(self, who):
        """The name a viewer last spoke under, from the chat so far."""
        for _, line in reversed(self.chat):
            c = json.loads(line)["chat"]
            if c.get("who") == who:
                return c.get("name", "")
        return ""

    def banned_line(self):
        """The banned viewers, for the desks (their numbers and names; never a device)."""
        return json.dumps({"banned": [{"who": b["who"], "name": b["name"]} for b in self.bans.values()]})

    def tell_banned(self):
        line = self.banned_line()
        for d in list(self.desks):
            d.send_nowait(line)

    def poll_line(self):
        p = self.poll
        counts = [0] * len(p["options"])
        for pick in p["votes"].values():
            counts[pick] += 1
        return json.dumps({"poll": {"id": p["id"], "question": p["question"], "options": p["options"],
                                    "counts": counts, "open": p["open"],
                                    "left": max(0, round(p["ends"] - time.monotonic())) if p["open"] else 0}})

    def tell_poll(self):
        if self.poll:
            self.broadcast(self.poll_line())

    def close_poll(self):
        self.poll["open"] = False
        self.poll["closed"] = time.monotonic()
        self.tell_poll()

    def late_joiner(self, sock):
        """What a viewer or desk gets at once: the chat so far, the poll, the sound."""
        for _, line in self.chat:
            sock.send_nowait(line)
        if self.poll:
            sock.send_nowait(self.poll_line())
        if self.sound_info and sock not in self.desks:
            sock.send_nowait(self.sound_info)
        if self.guess:
            sock.send_nowait(self.guess_line())

    # the drop guesses (11/05/2026, Jace: "add drop guesses"): he opens a round for a boss, and viewers guess the kill
    # count the next good drop lands on (one guess a device). Dink's kill counts keep the count; a drop from that boss
    # worth guess_min_value (Dink's LOOT) or his "it dropped" decides it, the closest guess winning

    def now_kc(self, boss):
        got = self.kc.get(str(boss).lower())
        return got[1] if got else None

    def guess_line(self):
        g = self.guess
        out = {"id": g["id"], "boss": g["boss"], "what": g["what"], "open": g["open"], "kc": self.now_kc(g["boss"]),
               "count": len(g["guesses"]), "result": g.get("result")}
        return json.dumps({"guess": out})

    def tell_guess(self):
        if self.guess:
            self.broadcast(self.guess_line())

    def decide_guess(self, kc, item):
        g = self.guess
        if not g or g.get("result"):
            return
        picks = [(abs(v["kc"] - kc), v["name"], v["kc"]) for v in g["guesses"].values()] if kc is not None else []
        best = min((p[0] for p in picks), default=None)
        winners = sorted({n for off, n, _ in picks if off == best}) if best is not None else []
        g["open"] = False
        g["result"] = {"kc": kc, "item": item, "winners": winners, "off": best, "at": time.monotonic()}
        self.tell_guess()
        if winners:
            self.say("✼", "%s at kill %s: %s guessed closest (%s off)" % (item or "the drop", kc, ", ".join(winners), best), host=True)


def version_numbers(v):
    """"0.10.2" as (0, 10, 2) for comparing; anything after a "-" or "+" (a pre-release) is left out, and a part
    that isn't a number counts as 0."""
    core = re.split(r"[-+]", (v or "").strip().lstrip("vV"), maxsplit=1)[0]
    nums = []
    for part in core.split(".")[:3]:
        nums.append(int(part) if part.isdigit() else 0)
    while len(nums) < 3:
        nums.append(0)
    return tuple(nums)


def too_old(v, min_client):
    """Whether a client saying version v is older than min_client (neither set: never)."""
    if not min_client or v is None:
        return False
    return version_numbers(v) < version_numbers(min_client)


class Relay:
    def __init__(self, delay=0.0, log=print, forward=None, max_viewers=0, min_client="", clips_dir=None):
        self.delay = delay
        self.clips_dir = clips_dir     # where clips are kept (None: the relay takes none)
        self.min_client = min_client   # the oldest client version let in (v=); "" lets every version in
        # how many may watch a channel at once (0: no limit). Each viewer gets their own copy
        # from the transmitter's home upload, so it's that upload that sets the number
        self.max_viewers = max_viewers
        self.channels = {}
        self.next_who = 0            # each viewer's number in the chat, for the desk's block
        self.replay_seconds = 0      # settings.json's replay_seconds (0 to 30), read again while running
        self.guess_min_value = GUESS_MIN_VALUE
        self.clip_folder = None      # settings.json's clip_folder (clip.py's default when it's missing)
        self.clipping = False
        self.log = log
        self.forward = forward       # forward.Forward: Dink's posts on to Discord, by rules

    def cap_for(self, ch):
        """How many may watch this channel: its own cap (the desk's {\"cap\"}) or the relay's."""
        return self.max_viewers if ch.own_cap is None else ch.own_cap

    async def handle_public(self, reader, writer):
        await self.handle(reader, writer, public=True)

    async def handle(self, reader, writer, public=False):
        """public: the port the internet reaches (Tailscale Funnel): the page and watching only."""
        try:
            request = await asyncio.wait_for(reader.readuntil(b"\r\n\r\n"), 10)
        except Exception:
            writer.close()
            return
        lines = request.decode("latin-1").split("\r\n")
        try:
            method, target, _ = lines[0].split(" ", 2)
        except ValueError:
            writer.close()
            return
        headers = {}
        for line in lines[1:]:
            if ":" in line:
                k, v = line.split(":", 1)
                headers[k.strip().lower()] = v.strip()
        key = headers.get("sec-websocket-key")
        url = urlsplit(target)
        path = [unquote(p) for p in url.path.split("/") if p]
        q = {k: v[0] for k, v in parse_qs(url.query).items()}
        if public and (method not in ("GET", "HEAD") or (key and (not path or path[0] != "watch"))):
            # from the internet: no transmitting and no Dink posts, whatever key comes with them
            writer.write(b"HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
            await writer.drain()
            writer.close()
            return
        if path and path[0] == "clips" and 2 <= len(path) <= 3 and not key:
            await self.clips(reader, writer, method, headers, path, q)
            return
        if method == "POST" and len(path) == 2 and path[0] == "dink":
            await self.dink(reader, writer, headers, path[1], q.get("key", ""))
            return
        if method == "GET" and not key and url.path == "/channels":
            await self.channel_list(writer)
            return
        if method in ("GET", "HEAD") and not key:
            await self.page(writer, url.path, method == "HEAD")
            return
        if method != "GET" or not key or len(path) != 2 or path[0] not in ("transmit", "watch", "desk", "sound") \
                or not (1 <= len(path[1]) <= 64):
            writer.write(b"HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\n\r\n")
            await writer.drain()
            writer.close()
            return
        accept = base64.b64encode(hashlib.sha1((key + GUID).encode()).digest()).decode()
        writer.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\n"
                      "Connection: Upgrade\r\nSec-WebSocket-Accept: %s\r\n\r\n" % accept).encode())
        await writer.drain()
        sock = Socket(reader, writer)
        role, name = path
        try:
            if role in CLIENT_ROLES and too_old(q.get("v"), self.min_client):
                await sock.close(4026, "this outstation is too old for the relay: %s or newer is needed" % self.min_client)
                return
            if role == "transmit":
                await self.transmit(sock, name, q.get("key", ""), q.get("code", ""))
            elif role == "desk":
                await self.desk(sock, name, q.get("key", ""), q.get("name", ""))
            elif role == "sound":
                await self.sound(sock, name, q.get("key", ""))
            else:
                if q.get("look") == "1":
                    await self.look(sock, name, q.get("code", ""))
                else:
                    await self.watch(sock, name, q.get("code", ""))
        except (Closed, asyncio.IncompleteReadError, ConnectionError):
            pass
        finally:
            await sock.close()

    async def transmit(self, sock, name, key, code):
        if len(key) < 8:
            await sock.close(4001, "a transmit key needs at least 8 characters")
            return
        ch = self.channels.get(name)
        if ch is None:
            ch = self.channels[name] = Channel(name, key)
        elif ch.key is None:         # made by a viewer waiting for it: this key claims it
            ch.key = key
        elif ch.key != key:
            await sock.close(4003, "wrong key for this channel")
            return
        if ch.sender is not None:
            await ch.sender.close(4000, "replaced by a newer transmitter")
        ch.sender, ch.code, ch.state = sock, code, "open"
        ch.on_air()
        self.log("transmit  %s  (code %s)" % (name, "set" if code else "none"))
        # viewers whose code no longer matches go
        for v in list(ch.viewers) + list(ch.waiting):
            if getattr(v, "code", "") != code:
                ch.viewers.discard(v)
                if v in ch.waiting:
                    ch.waiting.remove(v)
                await v.close(4003, "the watch code changed")
        ch.tell_viewers()
        try:
            while True:
                msg = await sock.recv()
                if isinstance(msg, str) and msg.startswith('{"clip"'):
                    self.clip(ch)             # (the clip key, from the capture: never on to viewers)
                    continue
                if isinstance(msg, (bytes, bytearray)) and msg[4:6] != b"TH":
                    ch.keep(msg, self.replay_seconds)      # (the replay buffer: full frames, never the small ones)
                if self.delay > 0:
                    ch.held.append((time.monotonic() + self.delay, msg))
                else:
                    ch.deliver(msg)
        finally:
            if ch.sender is sock:
                ch.sender = None
                ch.off_air()
                self.log("off air   %s" % name)
                ch.tell_viewers()

    async def look(self, sock, name, code):
        """A looker (11/05/2026: the listening post's receiver tuned to the portal, public/portal-feed.js): it takes no
        seat and isn't counted as watching, so it never keeps a viewer out or costs his upload a viewer's share. It
        hears the status lines (live, open, static), and frames only while it asks ({"look": {"frames": true}}), at
        most one every LOOK_EVERY seconds. No chat, no sound."""
        ch = self.channels.get(name)
        if ch is not None and ch.code and code != ch.code:
            await sock.close(4003, "wrong watch code")
            return
        if ch is None:
            ch = self.channels[name] = Channel(name, None)
        sock.look_frames, sock.look_at = False, 0.0
        ch.lookers.add(sock)
        sock.send_nowait(ch.status())
        try:
            while True:
                msg = await sock.recv()
                if isinstance(msg, str) and len(msg) <= VIEWER_TEXT:
                    try:
                        m = json.loads(msg)
                    except ValueError:
                        continue
                    if isinstance(m, dict) and isinstance(m.get("look"), dict):
                        sock.look_frames = m["look"].get("frames") is True
                        if sock.look_frames and ch.sender is not None:
                            fresh = ch.last_thumb if time.monotonic() - ch.thumb_at <= THUMB_FRESH else None
                            if fresh or ch.last_frame:
                                sock.send_nowait(fresh or ch.last_frame)
                                sock.look_at = time.monotonic()
        finally:
            ch.lookers.discard(sock)
            if ch.key is None and not ch.viewers and not ch.waiting and not ch.desks and not ch.lookers \
                    and self.channels.get(name) is ch:
                del self.channels[name]

    async def watch(self, sock, name, code):
        ch = self.channels.get(name)
        if ch is not None and ch.code and code != ch.code:
            await sock.close(4003, "wrong watch code")
            return
        if ch is None:
            # nobody has claimed it yet: wait as a viewer of an empty channel
            ch = self.channels[name] = Channel(name, None)
        sock.code = code
        self.next_who += 1
        sock.who, sock.device, sock.name = self.next_who, "", "visitor"
        sock.says, sock.last_ping, sock.last_signal = deque(), 0.0, 0.0
        cap = ch.cap = self.cap_for(ch)
        if cap and (len(ch.viewers) >= cap or ch.waiting):
            # full: in line for a seat (it used to be told 4009 and sent away), status lines only
            ch.waiting.append(sock)
            ch.late_joiner(sock)             # the chat so far (and the poll): in line, they can still talk
            self.log("wait      %s  (%d watching, %d in line)" % (name, len(ch.viewers), len(ch.waiting)))
        else:
            ch.seat(sock)
            self.log("watch     %s  (%d watching)" % (name, len(ch.viewers)))
        ch.tell_viewers()
        try:
            while True:
                msg = await sock.recv()
                # frames and data from a viewer are thrown away; only these few kinds of line count
                if isinstance(msg, str) and len(msg) <= VIEWER_TEXT:
                    self.from_viewer(ch, sock, msg)
        finally:
            ch.viewers.discard(sock)
            if sock in ch.waiting:
                ch.waiting.remove(sock)
            if ch.seat_waiting(self.cap_for(ch)):
                self.log("seated    %s  (%d watching, %d in line)" % (name, len(ch.viewers), len(ch.waiting)))
            ch.tell_viewers()
            if ch.key is None and not ch.viewers and not ch.waiting and not ch.desks and self.channels.get(name) is ch:
                del self.channels[name]      # nobody's channel, nobody waiting: not kept

    def from_viewer(self, ch, sock, msg):
        try:
            m = json.loads(msg)
        except ValueError:
            return
        if not isinstance(m, dict):
            return
        now = time.monotonic()
        if isinstance(m.get("got"), int) and not isinstance(m["got"], bool):
            # what this viewer has had so far (the page's flow control line): counted, for the desk's "viewers have it"
            last = getattr(sock, "got_last", 0)
            if m["got"] > last:
                ch.acked += m["got"] - last
            sock.got_last = m["got"]
            return
        if isinstance(m.get("hello"), dict):
            h = m["hello"]
            dev = h.get("device")
            if isinstance(dev, str) and 8 <= len(dev) <= 64 and not sock.device:
                sock.device = dev
            name = one_line(h.get("name"), NAME_MAX)
            # nobody may chat under the subject's own name (his lines are marked as his anyway)
            sock.name = name if name and name.lower() not in ch.host_names else "visitor"
            ch.tell_watching()
            return
        # in line for a seat: chat and votes all the same (11/05/2026, Jace: "people who cannot view the live feed
        # of the stream can still send messages in the chat"); pings only once seated, with a frame to point at
        if sock not in ch.viewers and "ping" in m:
            return
        blocked = sock.who in ch.blocked or (sock.device and sock.device in ch.blocked)
        if "say" in m:
            text = one_line(m.get("say"), SAY_MAX)
            while sock.says and now - sock.says[0] > SAY_WINDOW:
                sock.says.popleft()
            if not text or blocked or not ch.chat_on or len(sock.says) >= SAY_BURST:
                return
            sock.says.append(now)
            ch.say(sock.name, text, who=sock.who, queued=sock not in ch.viewers)
        elif isinstance(m.get("vote"), dict):
            p, v = ch.poll, m["vote"]
            pick = v.get("pick")
            if p and p["open"] and v.get("poll") == p["id"] and isinstance(pick, int) \
                    and not isinstance(pick, bool) and 0 <= pick < len(p["options"]):
                p["votes"][sock.device or "who:%d" % sock.who] = pick
                ch.tell_poll()
        elif m.get("signal") is True:
            # the signal button: a ✼ over everyone's frame (and a count in his chat window), once a second at most
            if blocked or now - sock.last_signal < SIGNAL_EVERY:
                return
            sock.last_signal = now
            ch.broadcast(json.dumps({"signal": {"name": sock.name, "who": sock.who}}))
        elif isinstance(m.get("guess"), dict):
            g, v = ch.guess, m["guess"]
            kc = v.get("kc")
            if not g or not g["open"] or v.get("id") != g["id"] or blocked or not isinstance(kc, int) or isinstance(kc, bool):
                return
            now_kc = ch.now_kc(g["boss"]) or 0
            if not (now_kc < kc <= now_kc + 100000):
                return                       # (a kill still to come)
            g["guesses"][sock.device or "who:%d" % sock.who] = {"kc": kc, "name": sock.name}
            ch.tell_guess()
        elif isinstance(m.get("ping"), dict):
            x, y = m["ping"].get("x"), m["ping"].get("y")
            if blocked or not ch.pings_on or now - sock.last_ping < PING_EVERY or not all(
                    isinstance(c, (int, float)) and not isinstance(c, bool) and 0 <= c <= 1 for c in (x, y)):
                return
            sock.last_ping = now
            ch.broadcast(json.dumps({"ping": {"x": round(x, 4), "y": round(y, 4), "name": sock.name,
                                              "who": sock.who, "at": int(time.time() * 1000)}}))

    async def claim(self, sock, name, key):
        """The channel, if this key may speak for it (the transmit key), else None (the socket closed)."""
        if len(key) < 8:
            await sock.close(4001, "a transmit key needs at least 8 characters")
            return None
        ch = self.channels.get(name)
        if ch is None:
            ch = self.channels[name] = Channel(name, key)
        elif ch.key is None:
            ch.key = key
        elif ch.key != key:
            await sock.close(4003, "wrong key for this channel")
            return None
        return ch

    async def desk(self, sock, name, key, host_name):
        """Jace's chat window: the chat, the poll and the pings in; his lines, polls, hides and blocks out."""
        ch = await self.claim(sock, name, key)
        if ch is None:
            return
        host_name = one_line(host_name, NAME_MAX) or name
        ch.host_names.add(host_name.lower())
        ch.desks.add(sock)
        self.log("desk      %s  (%d open)" % (name, len(ch.desks)))
        sock.send_nowait(ch.status())
        ch.late_joiner(sock)
        sock.send_nowait(ch.banned_line())
        sock.send_nowait(ch.viewer_list())
        # (messages that came while the portal was closed)
        now = time.time()
        for d in [d for d in ch.dms if now - d["at"] / 1000 <= DMS_DAYS * 86400]:
            sock.send_nowait(json.dumps({"dm": d}))
        ch.dms = []
        try:
            while True:
                msg = await sock.recv()
                if isinstance(msg, str):
                    self.from_desk(ch, host_name, msg, sock)
        finally:
            ch.desks.discard(sock)

    def send_dm(self, ch, host_name, dm, sock):
        """A message from this portal to another channel's: to its desks, or held till it opens; the sender told
        {"dm_sent"} or {"dm_failed"}."""
        to = str(dm.get("to", "")).strip().lower() if isinstance(dm, dict) else ""
        text = one_line(dm.get("text") if isinstance(dm, dict) else "", SAY_MAX)
        now = time.time()
        ch.dms_sent = [t for t in ch.dms_sent if now - t < 60]

        def fail(why):
            sock.send_nowait(json.dumps({"dm_failed": {"to": to, "text": text, "why": why}}))
        if not text or not re.match(r"^[A-Za-z0-9_.~-]{1,64}$", to):
            return fail("a message needs a channel and some words")
        if to == ch.name:
            return fail("that's your own portal")
        if len(ch.dms_sent) >= DMS_A_MINUTE:
            return fail("%d messages a minute at most: wait a moment" % DMS_A_MINUTE)
        other = self.channels.get(to)
        if other is None or other.key is None:
            return fail("no portal called %s on this relay" % to)
        ch.dms_sent.append(now)
        d = {"from": ch.name, "name": host_name, "text": text, "at": int(now * 1000)}
        if other.desks:
            for s in list(other.desks):
                s.send_nowait(json.dumps({"dm": d}))
        else:
            other.dms = [x for x in other.dms if now - x["at"] / 1000 <= DMS_DAYS * 86400][-(DMS_KEPT - 1):] + [d]
        sock.send_nowait(json.dumps({"dm_sent": {"to": to, "text": text, "at": d["at"], "held": not other.desks}}))

    def from_desk(self, ch, host_name, msg, sock=None):
        try:
            m = json.loads(msg)
        except ValueError:
            return
        if not isinstance(m, dict):
            return
        if "dm" in m and sock is not None:
            self.send_dm(ch, host_name, m.get("dm"), sock)
        elif "say" in m:
            text = one_line(m.get("say"), SAY_MAX * 2)
            if text:
                ch.say(host_name, text, host=True)
        elif isinstance(m.get("hide"), int):
            ch.unsay(m["hide"])
        elif isinstance(m.get("block"), int) or isinstance(m.get("unblock"), int):
            who = m.get("block", m.get("unblock"))
            found = [v for v in ch.viewers if v.who == who]
            tags = {who} | {v.device for v in found if v.device}
            if "block" in m:
                ch.blocked |= tags
                self.log("blocked   %s  viewer %d" % (ch.name, who))
            else:
                ch.blocked -= tags
        elif isinstance(m.get("poll"), dict):
            p = m["poll"]
            question = one_line(p.get("question"), 120)
            options = [one_line(o, 60) for o in (p.get("options") or []) if isinstance(o, str)]
            options = [o for o in options if o][:POLL_OPTIONS[1]]
            try:
                seconds = int(p.get("seconds", 60))
            except (TypeError, ValueError):
                seconds = 60
            if len(options) < POLL_OPTIONS[0]:
                return
            seconds = max(POLL_SECONDS[0], min(POLL_SECONDS[1], seconds))
            ch.poll_id += 1
            ch.poll = {"id": ch.poll_id, "question": question, "options": options, "votes": {},
                       "ends": time.monotonic() + seconds, "open": True}
            self.log("poll      %s  %d options, %d s" % (ch.name, len(options), seconds))
            ch.tell_poll()
        elif m.get("poll_close") and ch.poll and ch.poll["open"]:
            ch.close_poll()
        elif isinstance(m.get("ban"), int) and not isinstance(m["ban"], bool):
            # banned: blocked (their device too), and every line of theirs taken back off everyone's page
            who = m["ban"]
            found = [v for v in list(ch.viewers) + list(ch.waiting) if v.who == who]
            tags = {who} | {v.device for v in found if v.device}
            ch.blocked |= tags
            name = next((getattr(v, "name", "") for v in found), "") or ch.last_name(who) or "visitor"
            ch.bans[who] = {"who": who, "name": name, "tags": sorted(tags, key=str)}
            for cid in [i for i, line in ch.chat if json.loads(line)["chat"].get("who") == who]:
                ch.unsay(cid)
            self.log("banned    %s  viewer %d" % (ch.name, who))
            ch.tell_banned()
        elif isinstance(m.get("unban"), int) and not isinstance(m["unban"], bool):
            gone = ch.bans.pop(m["unban"], None)
            ch.blocked -= set(gone["tags"]) if gone else {m["unban"]}
            ch.tell_banned()
        elif isinstance(m.get("pings"), bool):
            if m["pings"] != ch.pings_on:
                ch.pings_on = m["pings"]
                ch.tell_viewers()
        elif isinstance(m.get("chat_on"), bool):
            # (11/05/2026, Jace: "add ability to toggle chat off")
            if m["chat_on"] != ch.chat_on:
                ch.chat_on = m["chat_on"]
                ch.tell_viewers()
        elif isinstance(m.get("title"), str):
            # the broadcast's title (11/06/2026): one line, TITLE_MAX long; every status line carries it
            title = one_line(m["title"], TITLE_MAX)
            if title != ch.title:
                ch.title = title
                self.log("title     %s  %s" % (ch.name, title or "(none)"))
                ch.tell_viewers()
        elif isinstance(m.get("cap"), int) and not isinstance(m["cap"], bool) and 0 <= m["cap"] <= 10000:
            # (outside-portal's addition: the channel's own cap on viewers, from the client's settings)
            ch.own_cap = m["cap"]
            ch.seat_waiting(ch.own_cap)
            ch.tell_viewers()
        elif m.get("clip"):
            self.clip(ch)
        elif isinstance(m.get("guess_open"), dict):
            boss = one_line(m["guess_open"].get("boss"), 40)
            if not boss:
                return
            ch.guess_id += 1
            ch.guess = {"id": ch.guess_id, "boss": boss, "what": one_line(m["guess_open"].get("what"), 60) or "the next good drop",
                        "open": True, "guesses": {}}
            self.log("guess     %s  %s" % (ch.name, boss))
            ch.tell_guess()
        elif m.get("guess_close") and ch.guess and ch.guess["open"]:
            ch.guess["open"] = False         # (no more guesses; still waiting for the drop)
            ch.tell_guess()
        elif m.get("guess_drop") and ch.guess and not ch.guess.get("result"):
            ch.decide_guess(ch.now_kc(ch.guess["boss"]), one_line(m.get("item") if isinstance(m.get("item"), str) else "", 60))
        elif m.get("guess_off") and ch.guess:
            gone, ch.guess = ch.guess["id"], None
            ch.broadcast(json.dumps({"guess_gone": gone}))

    async def sound(self, sock, name, key):
        """The sound (sound.py), beside the picture: held, then let through only while the picture is open."""
        ch = await self.claim(sock, name, key)
        if ch is None:
            return
        if ch.sounder is not None:
            await ch.sounder.close(4000, "replaced by a newer sound")
        ch.sounder = sock
        self.log("sound     %s" % name)
        try:
            while True:
                msg = await sock.recv()
                if isinstance(msg, bytes):
                    if len(msg) > 8 and msg[4:6] == b"AU":
                        ch.sound_held.append((time.monotonic() + self.delay + SOUND_HOLD, msg))
                        ch.keep(msg, self.replay_seconds)
                elif msg.startswith('{"sound"') and len(msg) <= VIEWER_TEXT:
                    ch.sound_info = msg
                    for v in list(ch.viewers):
                        v.send_nowait(msg)
        finally:
            if ch.sounder is sock:
                ch.sounder = None
                ch.sound_held.clear()
                ch.sound_info = json.dumps({"sound": {"mode": "off", "buses": []}})
                for v in list(ch.viewers):
                    v.send_nowait(ch.sound_info)
                self.log("no sound  %s" % name)

    async def channel_list(self, writer):
        """The open portals (11/05/2026, Jace: "a page ... that shows all active connected public portals"): every channel
        that's set up and has no watch code, live ones first, with whether each is live. Anyone may read it (the
        public side too); a channel with a watch code is never listed."""
        rows = [{"channel": ch.name, "live": ch.sender is not None, "open": ch.sender is not None and ch.state == "open",
                 "static": ch.sender is not None and ch.state == "static", "viewers": len(ch.viewers), "title": ch.title,
                 "doing": ch.doing()}
                for ch in self.channels.values() if ch.key is not None and not ch.code]
        rows.sort(key=lambda r: (not r["live"], r["channel"]))
        body = json.dumps({"channels": rows}).encode()
        writer.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: %d\r\n"
                      "Access-Control-Allow-Origin: *\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n" % len(body)).encode() + body)
        try:
            await writer.drain()
        finally:
            writer.close()

    async def page(self, writer, path, head_only=False):
        """The watching page itself, so a friend needs only this relay's address."""
        found = PAGE_FILES.get(path)
        body, kind, status = b"", "text/plain", "404 Not Found"
        if found:
            try:
                with open(found[0], "rb") as f:
                    body = f.read()
                kind, status = found[1], "200 OK"
                if path in ("/", "/portal.html"):
                    # tells portal.js its relay is the one that served it
                    body = body.replace(b'data-relay=""', b'data-relay="self"', 1)
            except OSError:
                body = b""
        writer.write(("HTTP/1.1 %s\r\nContent-Type: %s\r\nContent-Length: %d\r\nCache-Control: no-cache\r\n"
                      "Connection: close\r\n\r\n" % (status, kind, len(body))).encode() + (b"" if head_only else body))
        try:
            await writer.drain()
        finally:
            writer.close()

    # ---- clips on a player's Outside page ----

    def clip_folder_of(self, name):
        return os.path.join(self.clips_dir, name) if self.clips_dir and re.match(r"^[A-Za-z0-9_.~-]{1,64}$", name) \
            and name not in (".", "..") else None

    def clip_list(self, name):
        """The channel's clips, newest first, without what's past the most or too old (whose files go too)."""
        folder = self.clip_folder_of(name)
        try:
            with open(os.path.join(folder, "list.json"), encoding="utf-8") as f:
                clips = json.load(f)
        except (OSError, ValueError, TypeError):
            return []
        clips.sort(key=lambda c: -c.get("at", 0))
        now = time.time()
        kept = [c for c in clips if c.get("kept")] + \
            [c for c in clips if not c.get("kept") and now - c.get("at", 0) <= CLIPS_DAYS * 86400][:CLIPS_MOST]
        gone = [c for c in clips if c not in kept]
        if gone:
            self.drop_clips(name, gone, kept)
        return kept

    def save_list(self, name, clips):
        folder = self.clip_folder_of(name)
        os.makedirs(folder, exist_ok=True)
        tmp = os.path.join(folder, "list.json.tmp")
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(clips, f)
        os.replace(tmp, os.path.join(folder, "list.json"))

    def drop_clips(self, name, gone, kept):
        folder = self.clip_folder_of(name)
        for c in gone:
            for ext in ("mp4", "jpg"):
                try:
                    os.remove(os.path.join(folder, "%s.%s" % (c["id"], ext)))
                except OSError:
                    pass
        self.save_list(name, kept)
        ch = self.channels.get(name)
        for c in gone:
            if ch is not None:
                ch.tell_clip({"clip_gone": c["id"]})

    async def clips(self, reader, writer, method, headers, path, q):
        cors = "Access-Control-Allow-Origin: *\r\n"

        async def answer(status, body=None, extra=""):
            data = json.dumps(body).encode() if body is not None else b""
            kind = "Content-Type: application/json\r\nCache-Control: no-cache\r\n" if body is not None else ""
            writer.write(("HTTP/1.1 %s\r\n%s%s%sContent-Length: %d\r\nConnection: close\r\n\r\n"
                          % (status, cors, kind, extra, len(data))).encode() + data)
            try:
                await writer.drain()
            finally:
                writer.close()
        name = path[1]
        folder = self.clip_folder_of(name)
        if method == "OPTIONS":
            return await answer("204 No Content", None, "Access-Control-Allow-Methods: GET, HEAD, POST, DELETE\r\n"
                                "Access-Control-Allow-Headers: range, content-type\r\nAccess-Control-Max-Age: 86400\r\n")
        if folder is None:
            return await answer("404 Not Found")
        ch = self.channels.get(name)
        if len(path) == 3:
            m = re.match(r"^([A-Za-z0-9_-]{16,32})\.(mp4|jpg)$", path[2])
            if method in ("GET", "HEAD") and m:
                return await self.clip_file(writer, os.path.join(folder, path[2]), "video/mp4" if m.group(2) == "mp4" else "image/jpeg",
                                            headers.get("range", ""), method == "HEAD")
            if method == "POST" and CLIP_ID.match(path[2]):
                # kept for good, or let go again
                if ch is None or ch.key is None or q.get("key", "") != ch.key:
                    return await answer("403 Forbidden", {"error": "wrong key for this channel"})
                clips = self.clip_list(name)
                c = next((c for c in clips if c["id"] == path[2]), None)
                if c is None:
                    return await answer("404 Not Found", {"error": "no such clip"})
                keep = q.get("keep") == "1"
                if keep and not c.get("kept") and sum(1 for x in clips if x.get("kept")) >= KEPT_MOST:
                    return await answer("409 Conflict", {"error": "%d kept at most: let one go first" % KEPT_MOST})
                if keep:
                    c["kept"] = True
                else:
                    c.pop("kept", None)
                self.save_list(name, clips)
                ch.tell_clip({"clip_kept": {"id": c["id"], "kept": keep}})
                self.clip_list(name)
                return await answer("200 OK", {"id": c["id"], "kept": keep})
            if method == "DELETE" and CLIP_ID.match(path[2]):
                if ch is None or ch.key is None or q.get("key", "") != ch.key:
                    return await answer("403 Forbidden", {"error": "wrong key for this channel"})
                clips = self.clip_list(name)
                c = next((c for c in clips if c["id"] == path[2]), None)
                if c is None:
                    return await answer("404 Not Found", {"error": "no such clip"})
                self.drop_clips(name, [c], [x for x in clips if x is not c])
                return await answer("204 No Content")
            return await answer("404 Not Found")
        if method == "GET":
            if ch is not None and ch.code and q.get("code", "") != ch.code:
                return await answer("403 Forbidden", {"error": "wrong watch code"})
            return await answer("200 OK", {"clips": self.clip_list(name)})
        if method != "POST":
            return await answer("405 Method Not Allowed")
        if ch is None or ch.key is None or len(q.get("key", "")) < 8 or q.get("key", "") != ch.key:
            return await answer("403 Forbidden", {"error": "wrong key for this channel"})
        try:
            n = int(headers.get("content-length", "0") or 0)
        except ValueError:
            n = 0
        if n > MAX_CLIP + MAX_STRIP + 64 * 1024 or n <= 0:
            return await answer("413 Payload Too Large", {"error": "a clip is 8 MB at most"})
        now = time.time()
        ch.clips_uploads = [t for t in ch.clips_uploads if now - t < 3600]
        if len(ch.clips_uploads) >= CLIPS_AN_HOUR:
            return await answer("429 Too Many Requests", {"error": "%d clips an hour at most: try again later" % CLIPS_AN_HOUR})
        try:
            body = await reader.readexactly(n)
        except asyncio.IncompleteReadError:
            return await answer("400 Bad Request")
        got = clip_upload(headers.get("content-type", ""), body)
        if isinstance(got, tuple):
            return await answer(got[0], {"error": got[1]})
        meta, clip, strip = got["meta"], got["clip"], got["strip"]
        ch.clips_uploads.append(now)
        meta["id"] = base64.urlsafe_b64encode(os.urandom(16)).decode().rstrip("=")
        os.makedirs(folder, exist_ok=True)
        with open(os.path.join(folder, meta["id"] + ".mp4"), "wb") as f:
            f.write(clip)
        with open(os.path.join(folder, meta["id"] + ".jpg"), "wb") as f:
            f.write(strip)
        self.save_list(name, self.clip_list(name) + [meta])
        if any(c["id"] == meta["id"] for c in self.clip_list(name)):
            ch.tell_clip({"clip": meta})
        self.log("clip      %s  %s (%d KB)" % (name, meta["what"], len(clip) // 1024))
        return await answer("201 Created", {"id": meta["id"]})

    async def clip_file(self, writer, path, kind, rng, head_only):
        """A clip's file, whole or the range asked for (a video's player asks in ranges)."""
        try:
            size = os.path.getsize(path)
        except OSError:
            size = -1
        status, start, end = "200 OK", 0, size - 1
        m = re.match(r"^bytes=(\d*)-(\d*)$", rng.strip()) if rng and size >= 0 else None
        if m and (m.group(1) or m.group(2)):
            if m.group(1):
                start, end = int(m.group(1)), min(size - 1, int(m.group(2)) if m.group(2) else size - 1)
            else:
                start, end = max(0, size - int(m.group(2))), size - 1
            status = "206 Partial Content" if start <= end else "416 Range Not Satisfiable"
        if size < 0 or status.startswith("416"):
            writer.write(("HTTP/1.1 %s\r\nAccess-Control-Allow-Origin: *\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                          % ("404 Not Found" if size < 0 else status)).encode())
        else:
            with open(path, "rb") as f:
                f.seek(start)
                data = b"" if head_only else f.read(end - start + 1)
            writer.write(("HTTP/1.1 %s\r\nAccess-Control-Allow-Origin: *\r\nContent-Type: %s\r\nAccept-Ranges: bytes\r\n"
                          "Cache-Control: public, max-age=31536000, immutable\r\n%sContent-Length: %d\r\nConnection: close\r\n\r\n"
                          % (status, kind, "Content-Range: bytes %d-%d/%d\r\n" % (start, end, size) if status.startswith("206") else "",
                             end - start + 1)).encode() + data)
        try:
            await writer.drain()
        finally:
            writer.close()

    async def dink(self, reader, writer, headers, name, key):
        async def answer(status):
            writer.write(("HTTP/1.1 %s\r\nContent-Length: 0\r\nConnection: close\r\n\r\n" % status).encode())
            try:
                await writer.drain()
            finally:
                writer.close()
        try:
            if headers.get("transfer-encoding", "").lower() == "chunked":
                body = await read_chunked(reader)
            else:
                n = int(headers.get("content-length", "0") or 0)
                if n > MAX_POST:
                    return await answer("413 Payload Too Large")
                body = await reader.readexactly(n)
        except (ValueError, asyncio.IncompleteReadError):
            return await answer("400 Bad Request")
        if len(key) < 8 or not (1 <= len(name) <= 64):
            return await answer("403 Forbidden")
        ch = self.channels.get(name)
        if ch is None:
            ch = self.channels[name] = Channel(name, key)
        elif ch.key is None:
            ch.key = key
        elif ch.key != key:
            return await answer("403 Forbidden")
        content_type = headers.get("content-type", "")
        payload = dink_payload(content_type, body)
        # (Dink posts each event once to each address it lists: listed twice, every event came twice, to the portal and
        # on to Discord. 11/05/2026: his profile had this relay's address on two lines)
        now = time.monotonic()
        seen = hashlib.sha1(json.dumps(payload, sort_keys=True).encode() if payload is not None else body).hexdigest()
        for k in [k for k, t in ch.dink_seen.items() if now - t > DINK_SAME]:
            del ch.dink_seen[k]
        if seen in ch.dink_seen:
            self.log("dink      %s  the same post again: dropped" % name)
            return await answer("204 No Content")
        ch.dink_seen[seen] = now
        if self.forward is not None and self.forward.wants(payload):
            # Dink's own post, untouched, at once (the portal's delay is the portal's)
            self.log("forward   %s  %s to Discord" % (name, payload.get("type")))
            asyncio.ensure_future(self.forward.send(body, content_type))
        self.dink_counts(ch, payload)
        report = dink_report(payload)
        if report:
            line = json.dumps({"report": report})
            self.log("report    %s  %s" % (name, report["type"]))
            if self.delay > 0:
                ch.held.append((time.monotonic() + self.delay, line))
            else:
                ch.deliver(line)
        await answer("204 No Content")

    def dink_counts(self, ch, p):
        """Dink's kill counts and loot, for the drop guesses: the count kept per boss, and a big enough drop from the
        guessed boss deciding the round."""
        if not isinstance(p, dict):
            return
        extra = p.get("extra") if isinstance(p.get("extra"), dict) else {}
        if p.get("type") == "KILL_COUNT":
            boss = str(extra.get("boss") or "").strip()
            try:
                count = int(extra.get("count") or 0)
            except (TypeError, ValueError):
                count = 0
            if boss and count > 0:
                ch.kc[boss.lower()] = (boss, count)
                if ch.guess and ch.guess["boss"].lower() == boss.lower():
                    ch.tell_guess()
        elif p.get("type") == "LOOT" and ch.guess and not ch.guess.get("result"):
            source = str(extra.get("source") or "").strip().lower()
            if source != ch.guess["boss"].lower():
                return
            best = None
            for i in extra.get("items") or []:
                try:
                    worth = int(i.get("quantity", 0)) * int(i.get("priceEach", 0))
                except (TypeError, ValueError, AttributeError):
                    continue
                if worth >= self.guess_min_value and (best is None or worth > best[0]):
                    best = (worth, str(i.get("name") or "a drop"))
            if best:
                kc = extra.get("killCount")
                try:
                    kc = int(kc) if kc is not None else ch.now_kc(source)
                except (TypeError, ValueError):
                    kc = ch.now_kc(source)
                ch.decide_guess(kc, best[1])

    def clip(self, ch):
        """The replay buffer saved as a clip (clip.py), on a thread of its own; the desks hear where it went."""
        def tell(what):
            line = json.dumps({"clip": what})
            for d in list(ch.desks):
                d.send_nowait(line)
        if self.clipping:
            return tell({"error": "a clip is still being written"})
        if not self.replay_seconds:
            return tell({"error": "the replay buffer is off (its seconds in the settings)"})
        items = list(ch.replay)
        if not items:
            return tell({"error": "nothing in the replay buffer yet"})
        self.clipping = True
        tell({"writing": True, "seconds": round(items[-1][0] - items[0][0], 1)})

        async def go():
            try:
                import clip as clipmod
                path = await asyncio.to_thread(clipmod.write, items, self.clip_folder)
                self.log("clip      %s  %s" % (ch.name, path))
                tell({"saved": path})
            except Exception as e:           # (PyAV missing, a disk full: said, never the relay down)
                tell({"error": "couldn't write the clip: %s" % e})
            finally:
                self.clipping = False
        asyncio.ensure_future(go())

    async def watch_settings(self, path, every=2.0):
        """max_viewers from a settings file (start.py's ~/Documents/ghillie portal/settings.json),
        read again every couple of seconds, so a change (the field station's portal window) takes
        at once, without a restart. Viewers already watching stay; only new ones count against it,
        and a raised cap seats those waiting in line."""
        seen = None
        while True:
            try:
                st = os.stat(path)
                if (st.st_mtime, st.st_size) != seen:
                    seen = (st.st_mtime, st.st_size)
                    with open(path, encoding="utf-8") as f:
                        sj = json.load(f)
                    n = int(sj.get("max_viewers", self.max_viewers) or 0)
                    rs = max(0, min(30, int(sj.get("replay_seconds", 0) or 0)))
                    if rs != self.replay_seconds:
                        self.log("replay    %s" % ("off" if not rs else "the last %d s" % rs))
                        self.replay_seconds = rs
                    self.clip_folder = sj.get("clip_folder") or None
                    try:
                        self.guess_min_value = int(sj.get("guess_min_value", GUESS_MIN_VALUE))
                    except (TypeError, ValueError):
                        pass
                    if n != self.max_viewers:
                        self.log("max viewers %s" % (n or "no limit"))
                        self.max_viewers = max(0, n)
                        # (raised: those in line take the new seats; everyone hears the new cap)
                        for ch in list(self.channels.values()):
                            ch.seat_waiting(self.cap_for(ch))
                            ch.tell_viewers()
            except (OSError, ValueError, TypeError, AttributeError):
                pass
            await asyncio.sleep(every)

    async def tell_upload(self, every=1.0):
        """What the portal sends its viewers, a second at a time, to the desks (the chat window's upload line):
        {"upload": {"bps": bits a second, "viewers": n}}. It's his home upload's share for the portal; the
        Funnel adds a little on top (11/05/2026, Jace: "show my realtime upload usage")."""
        while True:
            await asyncio.sleep(every)
            now = time.monotonic()
            for ch in list(self.channels.values()):
                was, at = ch.out_seen
                ch.out_seen = (ch.out, now)
                had, had_at = ch.acked_seen
                ch.acked_seen = (ch.acked, now)
                if ch.desks:
                    # acked: what viewers' pages say they've had, a second at a time (the client's live view)
                    line = json.dumps({"upload": {"bps": int((ch.out - was) * 8 / max(0.001, now - at)),
                                                  "viewers": len(ch.viewers),
                                                  "acked": int((ch.acked - had) * 8 / max(0.001, now - had_at))}})
                    for d in list(ch.desks):
                        d.send_nowait(line)

    async def release_held(self):
        while True:
            now = time.monotonic()
            for ch in list(self.channels.values()):
                while ch.held and ch.held[0][0] <= now:
                    ch.deliver(ch.held.popleft()[1])
                while ch.sound_held and ch.sound_held[0][0] <= now:
                    ch.deliver_sound(ch.sound_held.popleft()[1])
                if ch.poll and ch.poll["open"] and now >= ch.poll["ends"]:
                    ch.close_poll()
                if ch.guess and ch.guess.get("result") and now >= ch.guess["result"]["at"] + GUESS_KEPT:
                    gone, ch.guess = ch.guess["id"], None
                    ch.broadcast(json.dumps({"guess_gone": gone}))
                elif ch.poll and not ch.poll["open"] and now >= ch.poll["closed"] + POLL_KEPT:
                    # a closed poll's result stays a little, then goes for everyone (late joiners too)
                    gone, ch.poll = ch.poll["id"], None
                    ch.broadcast(json.dumps({"poll_gone": gone}))
            await asyncio.sleep(0.01)


async def read_chunked(reader):
    out = []
    while True:
        size = int((await reader.readuntil(b"\r\n")).split(b";")[0], 16)
        if size == 0:
            await reader.readuntil(b"\r\n")
            return b"".join(out)
        if sum(map(len, out)) + size > MAX_POST:
            raise ValueError("too big")
        out.append(await reader.readexactly(size))
        await reader.readexactly(2)


def clip_upload(content_type, body):
    """An upload's parts, checked (the Worker's readUpload): {"meta", "clip", "strip"}, or (status, why not)."""
    if not content_type.lower().startswith("multipart/"):
        return ("400 Bad Request", "an upload is multipart: meta, strip and clip")
    try:
        msg = BytesParser(policy=policy.HTTP).parsebytes(b"Content-Type: " + content_type.encode("latin-1") + b"\r\n\r\n" + body)
        parts = {p.get_param("name", header="content-disposition"): p.get_payload(decode=True) for p in msg.iter_parts()}
    except (ValueError, LookupError, TypeError):
        return ("400 Bad Request", "an upload is multipart: meta, strip and clip")
    clip, strip, meta = parts.get("clip"), parts.get("strip"), parts.get("meta")
    if not isinstance(clip, bytes) or not isinstance(strip, bytes) or not isinstance(meta, bytes):
        return ("400 Bad Request", "an upload is multipart: meta, strip and clip")
    if len(clip) > MAX_CLIP:
        return ("413 Payload Too Large", "a clip is 8 MB at most")
    if len(strip) > MAX_STRIP:
        return ("413 Payload Too Large", "a flipbook is 256 KB at most")
    if len(clip) < 12 or clip[4:8] != b"ftyp":
        return ("415 Unsupported Media Type", "the clip isn't an .mp4")
    if strip[:2] != b"\xff\xd8":
        return ("415 Unsupported Media Type", "the flipbook isn't a JPEG")
    try:
        m = json.loads(meta.decode("utf-8"))
        assert isinstance(m, dict)
    except (ValueError, UnicodeDecodeError, AssertionError):
        return ("400 Bad Request", "meta isn't JSON")

    def num(v, lo, hi):
        return min(hi, max(lo, v)) if isinstance(v, (int, float)) and not isinstance(v, bool) and v == v else lo

    def text(v, most):
        return re.sub(r"[\x00-\x1f\x7f]", " ", v).strip()[:most] if isinstance(v, str) else ""
    still = text(m.get("still"), MAX_STILL + 1)
    if len(still) > MAX_STILL or not re.match(r"^[A-Za-z0-9+/=]*$", still):
        return ("413 Payload Too Large", "the still is 16 KB at most, base64")
    now = time.time()
    out = {"what": text(m.get("what"), 60) or "a clip", "kind": text(m.get("kind"), 24).upper(),
           "at": round(num(m.get("at"), now - 86400, now + 60)), "dur": round(num(m.get("dur"), 0, MAX_DUR) * 10) / 10,
           "w": round(num(m.get("w"), 0, 4096)), "h": round(num(m.get("h"), 0, 4096)), "still": still,
           "frames": round(num(m.get("frames"), 1, 60)), "fps": round(num(m.get("fps"), 1, 30) * 10) / 10, "size": len(clip)}
    if not out["dur"]:
        return ("400 Bad Request", "a clip needs its length (dur, up to 20 s)")
    return {"meta": out, "clip": clip, "strip": strip}


def dink_payload(content_type, body):
    """Dink's JSON: the payload_json part of a multipart post, or a plain JSON body."""
    try:
        if content_type.lower().startswith("multipart/"):
            msg = BytesParser(policy=policy.HTTP).parsebytes(
                b"Content-Type: " + content_type.encode("latin-1") + b"\r\n\r\n" + body)
            for part in msg.iter_parts():
                if part.get_param("name", header="content-disposition") == "payload_json":
                    return json.loads(part.get_payload(decode=True).decode("utf-8"))
            return None
        return json.loads(body.decode("utf-8"))
    except (ValueError, UnicodeDecodeError, LookupError):
        return None


def one_line(text, most):
    """A viewer's (or the desk's) words: one line, no control or direction characters, at most `most` long."""
    if not isinstance(text, str):
        return ""
    return re.sub(r"\s+", " ", CONTROL.sub(" ", text)).strip()[:most]


def clean(text):
    """Discord markdown to plain text."""
    text = re.sub(r"\[([^\]]*)\]\([^)]*\)", r"\1", text)       # [words](link) -> words
    text = re.sub(r"<a?:\w+:\d+>|<[@#!&]*\d+>|:\w+:", "", text)  # emoji and mentions
    text = re.sub(r"[*_~`>|]+", "", text)
    return re.sub(r"\s+", " ", text).strip()


def short(v, most=60):
    return clean(str(v))[:most] if isinstance(v, (str, int, float)) and not isinstance(v, bool) else None


def number(v):
    try:
        return int(v) if not isinstance(v, bool) and v is not None else None
    except (TypeError, ValueError):
        return None


def report_data(kind, extra):
    """The numbers a report carries for the portal's readings (the boss and its count, levels, the log's count, the
    loot): only fields that can't name another player. Never "party", a player kill's source, or a dropper."""
    d = {}
    if kind == "KILL_COUNT":
        d = {"boss": short(extra.get("boss")), "count": number(extra.get("count")), "pb": bool(extra.get("isPersonalBest"))}
    elif kind == "LEVEL":
        skills = extra.get("levelledSkills") if isinstance(extra.get("levelledSkills"), dict) else {}
        d["skills"] = {short(k, 20): number(v) for k, v in list(skills.items())[:24] if short(k, 20) and number(v) is not None}
        every = extra.get("allSkills") if isinstance(extra.get("allSkills"), dict) else {}
        levels = [number(v) for v in every.values()]
        if every and all(x is not None for x in levels):
            d["total"] = sum(levels)
        combat = extra.get("combatLevel")
        if isinstance(combat, dict) and number(combat.get("value")) is not None:
            d["combat"] = number(combat.get("value"))
    elif kind == "LOOT":
        items = [i for i in (extra.get("items") or []) if isinstance(i, dict)][:12]
        # (each item's id too, for its picture: RuneLite's icons go by id; 11/06/2026)
        d["items"] = [dict({"name": short(i.get("name")), "quantity": number(i.get("quantity")) or 0,
                            "price": number(i.get("priceEach")) or 0},
                           **({"id": number(i.get("id"))} if number(i.get("id")) is not None else {}))
                      for i in items if short(i.get("name"))]
        d["value"] = sum(i["quantity"] * i["price"] for i in d["items"])
        if str(extra.get("category", "")).upper() != "PLAYER":
            d["source"] = short(extra.get("source"))
    elif kind == "COLLECTION":
        d = {"item": short(extra.get("itemName")), "id": number(extra.get("itemId")), "done": number(extra.get("completedEntries")),
             "total": number(extra.get("totalEntries"))}
    elif kind == "PET":
        d = {"pet": short(extra.get("petName")), "duplicate": bool(extra.get("duplicate")),
             "milestone": short(extra.get("milestone"))}
    elif kind == "QUEST":
        d = {"quest": short(extra.get("questName")), "done": number(extra.get("completedQuests")),
             "total": number(extra.get("totalQuests"))}
    elif kind == "CLUE":
        d = {"tier": short(extra.get("clueType")), "count": number(extra.get("numberCompleted"))}
    elif kind == "SLAYER":
        d = {"task": short(extra.get("slayerTask")), "done": number(extra.get("slayerCompleted")),
             "points": number(extra.get("slayerPoints"))}
    elif kind == "COMBAT_ACHIEVEMENT":
        d = {"tier": short(extra.get("tier")), "task": short(extra.get("task"))}
    elif kind == "ACHIEVEMENT_DIARY":
        d = {"area": short(extra.get("area")), "tier": short(extra.get("difficulty"))}
    return {k: v for k, v in d.items() if v is not None and v != {} and v != []}


def dink_report(p):
    if not isinstance(p, dict) or p.get("type") not in REPORTS:
        return None
    text = ""
    embeds = p.get("embeds") or []
    if embeds and isinstance(embeds[0], dict):
        text = embeds[0].get("description") or embeds[0].get("title") or ""
    text = text or p.get("content") or ""
    extra = p.get("extra") if isinstance(p.get("extra"), dict) else {}
    if p["type"] == "DEATH" and (extra.get("isPvp") or extra.get("pvp") or extra.get("killerName")):
        text = ""
    if p["type"] == "LOOT" and str(extra.get("category", "")).upper() == "PLAYER":
        text = ""                # (a player kill's loot names the player: its text never goes)
    r = {"type": p["type"], "text": clean(str(text))[:200], "at": int(time.time())}
    data = report_data(p["type"], extra)
    if data:
        r["data"] = data
    return r


async def serve(host, port, delay, ready=None, log=print, forward=None, public_port=None, max_viewers=0,
                settings=None, min_client="", clips_dir=None):
    """port: everything (keep it on this PC). public_port: the page and watching only,
    for Tailscale Funnel (or anything else) to hand to the internet."""
    relay = Relay(delay, log, forward, max_viewers, min_client, clips_dir)
    server = await asyncio.start_server(relay.handle, host, port)
    public = await asyncio.start_server(relay.handle_public, host, public_port) if public_port else None
    holder = asyncio.ensure_future(relay.release_held())
    uploads = asyncio.ensure_future(relay.tell_upload())
    watcher = asyncio.ensure_future(relay.watch_settings(settings)) if settings else None
    log("the outstation relay on ws://%s:%d%s" % (host, port, "  (delay %g s)" % delay if delay else ""))
    if public:
        log("its public side (the page and watching only) on http://%s:%d" % (host, public_port))
    if forward is not None:
        log("forwarding Dink to Discord: %s" % forward.summary())
    if ready:
        ready(server, relay)
    try:
        if public:
            async with server, public:
                await asyncio.gather(server.serve_forever(), public.serve_forever())
        else:
            async with server:
                await server.serve_forever()
    finally:
        holder.cancel()
        uploads.cancel()
        if watcher:
            watcher.cancel()


def main(argv=None):
    ap = argparse.ArgumentParser(description="the portal's local relay")
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=8786)
    ap.add_argument("--public-port", type=int, default=None,
                    help="a second port with the page and watching only (for Tailscale Funnel)")
    ap.add_argument("--max-viewers", type=int, default=0,
                    help="how many may watch a channel at once (0: no limit)")
    ap.add_argument("--settings", default=None,
                    help="a settings file whose max_viewers is read again while running (start.py's)")
    ap.add_argument("--delay", type=float, default=0.0, help="seconds to hold everything back")
    ap.add_argument("--forward", default=DEFAULT_FORWARD,
                    help="Dink-to-Discord rules (forward.py); used when the file exists")
    ap.add_argument("--no-forward", action="store_true", help="forward nothing to Discord")
    ap.add_argument("--clips", default=DEFAULT_CLIPS,
                    help="where clips on players' Outside pages are kept (\"\" takes none)")
    ap.add_argument("--min-client", default="",
                    help="the oldest outstation client let in (e.g. 0.2.0); older ones are told to update")
    a = ap.parse_args(argv)
    forward = None
    if not a.no_forward and a.forward and os.path.exists(a.forward):
        forward = Forward.load(a.forward)
        if not forward.urls:
            print("the forward rules have no Discord webhook yet (%s): nothing is forwarded" % a.forward)
            forward = None
    try:
        asyncio.run(serve(a.host, a.port, a.delay, forward=forward, public_port=a.public_port,
                          max_viewers=a.max_viewers, settings=a.settings, min_client=a.min_client,
                          clips_dir=a.clips or None))
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    sys.exit(main())
