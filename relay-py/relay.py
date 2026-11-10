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

Invites (11/07/2026; docs/protocol.md "Invites"): an outstation hands out channels of its own, INVITES_START to begin
with and one more every INVITE_EVERY_S (10 hours) on air, counted from when it was claimed (in memory: a restart
starts every count again, as it forgets every channel).
    GET   http://HOST:PORT/invites/<channel>?key=<secret>          {"left", "earned", "used", "hours", "next_in_s"}
    POST  http://HOST:PORT/invites/<channel>  {"key", "player"}     {"channel", "key", "code", "left"}
  The new channel is claimed with a new key, and the code attaches a client to it; GET /channels says whose invite
  each channel came from ("by"). Till its key is first used (an invite not taken up yet, 11/08/2026) GET /channels
  leaves it out and only counts it under its inviter ("pending": [{"by", "n"}], never its name). The public side
  refuses both.

The game (11/07/2026; docs/protocol.md "The game"): the Outside plugin, a RuneLite plugin of our own, sends the player's
live game state (vitals, skills, what's worn and carried, prayers, item names and prices) and Outside draws it beside the
transmission and the chat, live, and as last known while dark.
    game:      ws://HOST:PORT/game/<channel>?key=<secret>&v=<the plugin's version>
  The same key as transmitting (--min-client never applies: the plugin has versions of its own); one at a time, a newer
  one replacing the older (4000). It sends {"game": {...fields}}, GAME_MESSAGE long at most, GAME_A_SECOND a second.
  Each field replaces the one kept, but "items", which is merged; what changed goes on to seated viewers, lookers and
  desks as {"game": {...the fields, "at"}}, and someone just come gets the whole of it. The status line's "game" says
  whether one is connected. Kept in memory, as everything here, while the relay runs.
    read:      GET http://HOST:PORT/game/<channel>?code=<watch code>    {"game": {...}, "at", "connected"}
  Following (11/08/2026): apart from the game's fields, "where", the middle of the 128-tile square the player was in two
  minutes before ({"x", "y"}; Zanaris as one place, WHERE_ZANARIS), or null to clear it (game_where). Held in memory (one every WHERE_EVERY_S at most; null
  whenever it comes), dropped when the plugin goes, logs out ({"off": true}) or holds for the player's safety ({"paused"}),
  and shown only on GET /channels, while the channel's live and listed and it came within WHERE_FRESH_S
  (--where-fresh-seconds, for tests). Never to viewers, lookers or desks, nor in the read. With the plugin's "and my
  world" on too, "where" carries the world with the square, {"x", "y", "world"} (WORLD_RANGE).
  What they're doing (11/08/2026): "activity", one coarse label ({"kind": "skilling", "skill"}, {"kind": "boss",
  "boss"}, {"kind": "fighting"}, {"kind": "idle"}: game_activity's closed lists), or null; held, dropped and shown on
  GET /channels exactly as "where" is.
  Shooting stars (11/08/2026): "star", a crashed star the player found two minutes before ({"world", "x", "y", "tier"},
  or "gone": true; "site" with either), into the relay's StarList: GET /stars (below).
  The public side allows the read (it's what viewers see anyway) and refuses the game socket.

The aether (11/07/2026, Jace: "a global chat... reminds me of IRC and AOL Instant Messenger. i want this to feel like a
real network people are joining"; docs/protocol.md "The aether"; the Worker's aether.ts, the same): one room for every
outstation and every visitor on the relay.
    aether:    ws://HOST:PORT/aether?name=<name>&device=<random id>               a visitor
               ws://HOST:PORT/aether?channel=<channel>&key=<secret>&v=<version>   an outstation (its key as its desk's)
  Either sends {"say": text}: a line of up to AETHER_ROWS rows of AETHER_WIDTH characters (ASCII art keeps its shape),
  one every 2 s and 15 a minute from someone at most (past that, only they hear {"aether_slow": seconds}). Everyone hears
  {"aether": {"id", "name", "text", "from", "at"}}; whoever joins, the last AETHER_KEPT lines ({"aether_so_far"}) and how
  many are here ({"aether_here"}, told to all at most every 2 s as it changes). A visitor's name may not be a claimed
  channel's (4009). Nothing crosses between the aether and any channel, and --delay doesn't hold it back.
    read:      GET http://HOST:PORT/aether                                         {"lines": [the last 30], "here"}
    keeper:    GET /admin/aether; POST /admin/aether/unsay {"id"}, /ban {"id"}, /unban {"who"}
  The keeper's routes need --admin-token (Authorization: Bearer <it>); without one they're refused. The public side lets
  visitors in and allows the read, and refuses an outstation's socket and the keeper's routes.

The Array's events (11/08/2026; docs/protocol.md "The Array's events"; the Worker's array.ts, the same): what's
happening across the open outstations, for outside.wiki's front page (each outstation's icon pops "+1").
    read:      GET http://HOST:PORT/array/events?since=<cursor>      {"events": [...], "next": <cursor>}
    keeper:    POST /admin/array {"channel", "events": [{"kind", ..., "at": ms}]}   (--admin-token) a real event the feed
               lost, put back in its place by when it happened (array_backfill, ArrayFeed.backfill; 11/10/2026)
  Each event is {"id", "at", "channel", "kind", ...}: a kill, a level (one a skill), a pet, a drop worth 1,000,000 or
  more, a collection log slot, a clue (array_events), made from a field report only while its channel is live and on
  GET /channels (no watch code). The last ARRAY_KEPT in memory, for as long as the relay runs (the Worker keeps them in
  its Directory's storage, 11/10/2026); one channel adds ARRAY_A_MINUTE a minute at most. The public side allows the
  read.

Shooting stars (11/08/2026; docs/protocol.md "Shooting stars"; the Worker's stars.ts, the same): the crashed stars the
plugins have found, and who found each, for outside.wiki's globe.
    read:      GET http://HOST:PORT/stars
               {"stars": [{"world", "x", "y", "site", "tier", "found", "seen", "by", "reports"}, ...]}
  One for each world and square, however many told of it, with the first listed outstation to tell of it with "credit":
  true as its finder ("by", 11/09/2026: opt-in; null for none, or a hidden one) and how many did; kept STAR_LIFE_S after it was first told of, or till it's told
  gone (and then not brought back for STAR_GONE_S); one outstation tells of STARS_AN_HOUR in an hour at most. In memory,
  for as long as the relay runs (the Worker keeps them in its Directory's storage, 11/10/2026). The public side allows
  the read.
    tests:     POST http://HOST:PORT/test/forget   (only with --test-forget) 204, and nothing forgotten: the Worker's
               puts its Directory to sleep, to show its storage keeps the Array's events and the stars; this relay's
               memory is its keeping, so there's nothing to show. tests/protocol_test.py's Kept, against either.
               (The Worker's GET/POST /test/listed/<channel>, what its Directory stores of a row on the open list and
               how often it's asked that channel since it woke (11/10/2026), is 404 here: this relay builds GET /channels
               from its channels at each read, in memory, and stores and asks nothing; Kept skips those parts.)

The almanac (11/09/2026; docs/protocol.md "The almanac"; almanac.py, and the Worker's almanac.ts, the same): Jagex's
news, the world list (its seasons) and the Wiki's prices (the day's movers), for outside.wiki's globe, each fetched by
the relay alone on its own schedule (almanac_loop; --almanac on, the default, off, or an address for tests) and shared.
    read:      GET http://HOST:PORT/almanac     {"at", "updates", "worlds", "market", "sources"}
    keeper:    GET /admin/almanac (each source's schedule), POST /admin/almanac (every source asked now)
  A read never asks a source, except the first when nothing's been tried (shared by every read meanwhile). A source
  that fails keeps its last good data, with its age, and backs off. The public side allows the read.

Settling (11/08/2026) went on 11/10/2026 (docs/protocol.md "Settling"; Jace's decision: the globe shows real positions
on the land and every other outstation at the receiver, so homes have no use). Anything on /settle/<channel> or
/home/<channel> is answered 410 {"error": SETTLING_GONE}, so an app built while it was here says why (the Worker, the
same). Nothing of it was ever kept here past a restart.
"""

import argparse
import asyncio
import base64
import hashlib
import hmac
import json
import math
import os
import re
import shutil
import struct
import sys
import time
from collections import deque
from email import policy
from email.parser import BytesParser
from urllib.parse import parse_qs, unquote, urlsplit

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from forward import DEFAULT as DEFAULT_FORWARD, Forward  # noqa: E402
import almanac as almanac_sources  # noqa: E402

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
# invites (11/07/2026, Jace: "each of my friends being able to pass out a few keys so that way the platform propagates
# easier", "people earning more invites through streaming"): an outstation hands out channels of its own, this many to
# begin with and one more every INVITE_EVERY_S on air; INVITES_AN_HOUR posts an hour at most (the Worker's, the same)
INVITES_START, INVITE_EVERY_S, INVITES_AN_HOUR = 3, 36000, 5
PLAYER = re.compile(r"[a-z0-9]{1,12}")     # a channel invited: a player's name as the page has it (fullmatch)
# the game (11/07/2026; docs/protocol.md "The game"): the Outside plugin's socket. A message at most GAME_MESSAGE long, and
# GAME_A_SECOND a second (more are dropped); the kept state at most GAME_KEPT as JSON (a message that would take it past is
# ignored), its dictionary of item names and prices at most GAME_ITEMS ids (past that, new ones are ignored). The
# Worker's, the same.
GAME_MESSAGE, GAME_KEPT, GAME_ITEMS, GAME_A_SECOND = 64 * 1024, 192 * 1024, 3000, 5
GAME_INT = 2 ** 31 - 1            # the most any number in the game holds (a Java int: RuneLite's)
GAME_SKILL = re.compile(r"[A-Za-z][A-Za-z _-]{0,23}")     # a skill's name (fullmatch)
GAME_ID = re.compile(r"0|[1-9][0-9]{0,9}")                # an item's id as the dictionary has it (fullmatch)
# following (11/08/2026; docs/protocol.md "The game"; the Worker's game.ts, the same): "where" is the middle of a
# WHERE_SQUARE-tile square of the main map (x from WHERE_X[0] up to WHERE_X[1], y the same with WHERE_Y), taken at most
# one every WHERE_EVERY_S, and shown on GET /channels for WHERE_FRESH_S after it came (the relay's where_fresh_s,
# --where-fresh-seconds)
WHERE_SQUARE, WHERE_X, WHERE_Y, WHERE_EVERY_S, WHERE_FRESH_S = 128, (1024, 4096), (2496, 4224), 50, 240
# Zanaris (11/08/2026, Jace: "if someone goes to zanaris through a fairy ring on Gielinor make sure it shows their
# location as being on Zanaris"): the plugin sends any tile there (x 2368-2495, y 4352-4479) as this one place, which
# the globe draws on its moon. The only "where" off the main map that's taken; its neighbours aren't.
WHERE_ZANARIS = {"x": 2368, "y": 4416}
# which world (11/08/2026, with following's "and my world"; the Worker's WORLD_RANGE, the same): a whole number in this
# range, wide enough for the worlds RuneLite reports (301 and up for years, and lower if Jagex numbers them from 1).
# Shooting stars' worlds are checked the same.
WORLD_RANGE = (1, 999)
# what they're doing (11/08/2026; the Worker's ACTIVITY_SKILLS and ACTIVITY_BOSSES, the same; the bosses are the
# plugin's Activity.bossLabels(): test_relay.py checks the three lists match; made whole from the official hiscores'
# list 11/10/2026, the Wilderness's bosses and the Gauntlets with them, Zalcano a minigame)
ACTIVITY_SKILLS = ("Agility", "Attack", "Construction", "Cooking", "Crafting", "Defence", "Farming", "Firemaking",
                   "Fishing", "Fletching", "Herblore", "Hitpoints", "Hunter", "Magic", "Mining", "Prayer", "Ranged",
                   "Runecraft", "Sailing", "Slayer", "Smithing", "Strength", "Thieving", "Woodcutting")
ACTIVITY_BOSSES = ("Abyssal Sire", "Alchemical Hydra", "Amoxliatl", "Araxxor", "Artio", "Barrows", "Brutus",
                   "Bryophyta", "Callisto", "Calvar'ion", "Cerberus", "Chaos Elemental", "Chaos Fanatic",
                   "Commander Zilyana", "Corporeal Beast", "Crazy archaeologist", "Dagannoth Prime", "Dagannoth Rex",
                   "Dagannoth Supreme", "Deranged archaeologist", "Doom of Mokhaiotl", "Duke Sucellus",
                   "General Graardor", "Giant Mole", "Grotesque Guardians", "Hespori", "K'ril Tsutsaroth",
                   "Kalphite Queen", "King Black Dragon", "Kraken", "Kree'arra", "Mad Angel", "Maggot King",
                   "Moons of Peril", "Nex", "Obor", "Phantom Muspah", "Phosani's Nightmare", "Royal Titans",
                   "Sarachnis", "Scorpia", "Scurrius", "Shellbane gryphon", "Skotizo", "Sol Heredit", "Spindel",
                   "The Hueycoatl", "The Leviathan", "The Mimic", "The Nightmare", "The Whisperer",
                   "Thermonuclear smoke devil", "TzKal-Zuk", "TzTok-Jad", "Vardorvis", "Venenatis", "Vet'ion",
                   "Vorkath", "Yama", "Zulrah", "the Corrupted Gauntlet", "the Gauntlet")
# raids and minigames (11/09/2026, Jace: "include all activities that utilize instances"; the Worker's ACTIVITY_RAIDS and
# ACTIVITY_MINIGAMES, the same; the plugin's Activity.RAIDS and MINIGAMES's labels, by RuneLite's Discord plugin's region
# ids). The PvP minigames are minigames like the rest (11/10/2026).
ACTIVITY_RAIDS = ("Chambers of Xeric", "The Fractured Archive", "Theatre of Blood", "Tombs of Amascut")
ACTIVITY_MINIGAMES = ("Barbarian Assault", "Blast Furnace", "Brimhaven Agility Arena", "Castle Wars", "Clan Wars",
                      "Emir's Arena", "Fishing Trawler", "Giants' Foundry", "Guardians of the Rift",
                      "Hallowed Sepulchre", "Last Man Standing", "Mage Training Arena", "Nightmare Zone",
                      "Pest Control", "Pyramid Plunder", "Rogues' Den", "Sorceress's Garden", "Soul Wars",
                      "Temple Trekking", "Tempoross", "Tithe Farm", "Trouble Brewing", "Volcanic Mine", "Wintertodt",
                      "Zalcano", "the Colosseum", "the Fight Caves", "the Fight Pits", "the Inferno")
# the landing sites (11/09/2026, Jace: "named landing sites: yes"; the Worker's STAR_SITES, the same): Shooting Star
# Info's (its Location.java; BSD 2-Clause, Copyright (c) 2022, Cute Rock: plugin/README.md, "Credits"), less the
# Wilderness's and Prifddinas's, each name with the square it's in. A star's "site" must be one of these, in its square.
STAR_SITES = {
    "Rimmington mine": (3008, 3264),
    "Crafting guild": (2880, 3264),
    "West Falador mine": (2880, 3392),
    "East Falador bank": (3008, 3392),
    "North Dwarven Mine entrance": (3008, 3392),
    "Taverley house portal": (2880, 3520),
    "Brimhaven northwest gold mine": (2752, 3264),
    "Brimhaven south dungeon entrance": (2752, 3136),
    "Nature Altar mine north of Shilo": (2880, 3008),
    "Shilo Village gem mine": (2880, 3008),
    "North Crandor": (2880, 3264),
    "South Crandor": (2880, 3264),
    "Al Kharid mine": (3264, 3264),
    "Al Kharid bank": (3264, 3136),
    "Mage Training Arena entrance": (3392, 3264),
    "Nw of Uzer (Eagle's Eyrie)": (3392, 3136),
    "Nardah bank": (3392, 2880),
    "Agility Pyramid mine": (3264, 2880),
    "Desert Quarry mine": (3136, 2880),
    "Corsair Cove bank": (2624, 2880),
    "Corsair Resource Area": (2496, 2880),
    "Myths' Guild": (2496, 2880),
    "Feldip Hills (aks fairy ring)": (2624, 3008),
    "Rantz cave": (2624, 3008),
    "Soul Wars south mine": (2240, 2752),
    "Fossil Island Volcanic Mine entrance": (3776, 3776),
    "Fossil Island rune rocks": (3776, 3776),
    "Mos Le'Harmless west bank": (3648, 3008),
    "Keldagrim entrance mine": (2752, 3648),
    "Rellekka mine": (2624, 3648),
    "Jatizso mine entrance": (2368, 3776),
    "Neitiznot south of rune rock": (2368, 3776),
    "Miscellania mine (cip fairy ring)": (2496, 3904),
    "Lunar Isle mine entrance": (2112, 3904),
    "Yanille bank": (2624, 3136),
    "Port Khazard mine": (2624, 3136),
    "Ardougne Monastery": (2624, 3264),
    "South of Legends' Guild": (2752, 3392),
    "Catherby bank": (2752, 3392),
    "Coal Trucks west of Seers'": (2624, 3520),
    "Hosidius mine": (1728, 3520),
    "Port Piscarilius mine in Kourend": (1728, 3648),
    "Shayzien mine south of Kourend Castle": (1600, 3648),
    "South Lovakengj bank": (1472, 3776),
    "Lovakite mine": (1472, 3904),
    "Arceuus dense essence mine": (1728, 3904),
    "Mount Karuulm bank": (1344, 3776),
    "Mount Karuulm mine": (1216, 3776),
    "Kebos Swamp mine": (1216, 3648),
    "Chambers of Xeric bank": (1216, 3520),
    "Varrock east bank": (3264, 3392),
    "South-east Varrock mine": (3264, 3392),
    "Champions' Guild mine": (3136, 3392),
    "Draynor Village": (3136, 3264),
    "West Lumbridge Swamp mine": (3136, 3136),
    "East Lumbridge Swamp mine": (3264, 3136),
    "Darkmeyer ess. mine entrance": (3648, 3392),
    "Theatre of Blood bank": (3648, 3264),
    "Canifis bank": (3520, 3520),
    "Burgh de Rott bank": (3520, 3264),
    "Abandoned Mine west of Burgh": (3392, 3264),
    "West of Grand Tree": (2496, 3520),
    "Gnome Stronghold spirit tree": (2496, 3392),
    "Piscatoris (akq fairy ring)": (2368, 3648),
    "Lletya": (2368, 3136),
    "Isafdar runite rocks": (2240, 3136),
    "Arandar mine north of Lleyta": (2368, 3264),
    "Mynydd nw of Prifddinas": (2112, 3392),
    "Varlamore South East mine": (1728, 3008),
    "Varlamore colosseum entrance bank": (1728, 3136),
    "Mine north-west of hunter guild": (1472, 3136),
}
# shooting stars (11/08/2026; docs/protocol.md "Shooting stars"; the Worker's stars.ts, the same): a star's kept
# STAR_LIFE_S after it was first told of (a star lasts about two hours), or till it's told gone; for STAR_GONE_S after,
# a late report of it doesn't bring it back; one outstation tells of STARS_AN_HOUR in an hour at most; STARS_KEPT at most
STAR_LIFE_S, STAR_GONE_S, STARS_AN_HOUR, STARS_KEPT = 2 * 3600, 600, 30, 1000
# the aether (11/07/2026; docs/protocol.md "The aether"): one room for every outstation and visitor. A line at most
# AETHER_ROWS rows of AETHER_WIDTH characters (so 960 in all); one from someone every AETHER_EVERY ms at most, and
# AETHER_A_MINUTE a minute; the last AETHER_KEPT kept (and handed to whoever joins), the last AETHER_READ in GET /aether;
# who's here told to everyone at most every AETHER_HERE_EVERY seconds; a socket's message past AETHER_MESSAGE thrown away
# unread. The Worker's (aether.ts, text.ts), the same.
AETHER_ROWS, AETHER_WIDTH, AETHER_KEPT, AETHER_READ = 12, 80, 100, 30
AETHER_EVERY, AETHER_A_MINUTE, AETHER_HERE_EVERY, AETHER_MESSAGE = 2000, 15, 2.0, 8192
# control characters but the newline (C1's too), and the direction and zero-width ones: taken out, not made spaces
AETHER_CONTROL = re.compile(r"[\x00-\x09\x0b-\x1f\x7f-\x9f\u200b-\u200f\u2028-\u202e\u2066-\u2069\ufeff]")
# a row's trailing spaces: Unicode's spaces (Zs), written out so both relays trim the same ones
AETHER_TRAILING = re.compile(r"[ \u00a0\u1680\u2000-\u200a\u202f\u205f\u3000]+$")
AETHER_NAME = re.compile(r"[A-Za-z0-9_. -]{1,20}")        # a visitor's name, its spaces tidied (fullmatch)
AETHER_DEVICE = re.compile(r"[\x21-\x7e]{8,64}")           # a visitor's device id: printable, no spaces (fullmatch)
CHANNEL_NAME = re.compile(r"[A-Za-z0-9_.~-]{1,64}")       # a channel (fullmatch)
# the Array's events (11/08/2026; docs/protocol.md "The Array's events"; the Worker's array.ts, the same): what's
# happening across the open outstations, for outside.wiki's front page. The last ARRAY_KEPT kept, in memory; one channel
# adds ARRAY_A_MINUTE a minute at most (the rest are dropped); a read without since has the last ARRAY_LATEST, one with
# since at most ARRAY_PAGE after it; a drop goes in only when it's worth ARRAY_LOOT or more; a name in an event is
# ARRAY_WORD characters at most (Dink's are cut to 60 already); a number, 0 to SAFE_INT (as JavaScript holds one exactly)
ARRAY_KEPT, ARRAY_A_MINUTE, ARRAY_LATEST, ARRAY_PAGE, ARRAY_LOOT, ARRAY_WORD = 500, 10, 50, 100, 1_000_000, 60
SAFE_INT = 2 ** 53 - 1
ARRAY_CURSOR = re.compile(r"[0-9]{1,16}")                  # since, as next gave it (fullmatch; SAFE_INT at most)
# what /settle and /home answer now settling's gone (11/10/2026; the Worker's SETTLING_GONE, the same)
SETTLING_GONE = "settling has gone: the globe now shows each outstation where it really is, or at the receiver"
KEPT_MOST = 3                     # kept for good (never in the 20, never too old): the Worker's KEPT_MOST
CLIP_ID = re.compile(r"^[A-Za-z0-9_-]{16,32}$")
DEFAULT_CLIPS = os.path.join(os.path.expanduser("~"), "Documents", "ghillie portal", "clips")
DEFAULT_RECORDS = os.path.join(os.path.expanduser("~"), "Documents", "ghillie portal", "records")
VIEWER_TEXT = 2048                # anything longer from a viewer is thrown away unread
CHAT_KEPT = 50                    # lines a late viewer (or a newly opened desk) gets at once
SAY_MAX, SAY_BURST, SAY_WINDOW = 300, 20, 10.0   # 20 lines in 10 s a viewer: room to spam a little for fun (Jace, 11/05)
NAME_MAX = 20
TITLE_MAX = 100                   # the broadcast's title (11/06/2026, Jace: "the option to title broadcast")
PING_EVERY = 0.5                  # a ping a viewer every half second (1.5 until Jace asked for more, 11/05)
POLL_OPTIONS, POLL_SECONDS = (2, 6), (10, 900)
ACKED_OVER = 5.0                  # the acknowledged rate (tell_upload's "acked") over this long, in seconds
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
HELD_LOOK = 0.25                  # the longest the holder sleeps (release_held): what isn't woken for is seen this soon
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


def unmask(data, mask):
    """A client's frame unmasked (RFC 6455, 5.3), as one big number XORed with the mask repeated: byte by byte in
    Python, a 35 KB frame took about 2 ms (now 0.1), most of what each frame cost the relay (11/07/2026)."""
    n = len(data)
    if not n:
        return data
    words = (n + 3) // 4
    return (int.from_bytes(data, "little") ^ int.from_bytes(mask * words, "little")).to_bytes(4 * words, "little")[:n]


_framed = (None, None)


def framed(msg):
    """(binary, its header, its payload) as a server sends it. A message going to every viewer is framed once, not
    once a viewer: the last one framed is kept (held by reference, so `is` can't mistake another for it; never a
    bytearray, which could change), 11/07/2026."""
    global _framed
    if _framed[0] is msg:
        return _framed[1]
    binary = isinstance(msg, (bytes, bytearray))
    data = bytes(msg) if binary else msg.encode("utf-8")
    op, n = (2 if binary else 1), len(data)
    if n < 126:
        head = struct.pack(">BB", 0x80 | op, n)
    elif n < 65536:
        head = struct.pack(">BBH", 0x80 | op, 126, n)
    else:
        head = struct.pack(">BBQ", 0x80 | op, 127, n)
    if type(msg) is bytearray:
        return binary, head, data
    _framed = (msg, (binary, head, data))
    return _framed[1]


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
                data = unmask(data, mask)
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
        binary, head, data = framed(msg)
        if binary and self.writer.transport.get_write_buffer_size() > VIEWER_BUFFER:
            return False
        try:
            # (header and payload as they are, not joined into a copy for each viewer: one sendmsg on Python 3.12 and
            # later; a join, as before, where the event loop has no writelines of its own, e.g. Windows' proactor)
            self.writer.writelines((head, data))
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


def invite_numbers(seconds, used):
    """An outstation's invites, from its time on air in all (seconds) and the names it's invited (the Worker's
    inviteNumbers, the same). hours: one decimal, rounded down, so it never says 10.0 before the invite at 10."""
    s = max(0, int(seconds))
    earned = INVITES_START + s // INVITE_EVERY_S
    return {"left": max(0, earned - len(used)), "earned": earned, "used": list(used), "hours": (s // 360) / 10,
            "next_in_s": INVITE_EVERY_S - s % INVITE_EVERY_S}


def attach_code(relay, channel, key):
    """The attach code (the station key): "outstation1:" and base64url JSON {"relay", "channel", "key"} (the Worker's
    attachCode, the same)."""
    raw = json.dumps({"relay": relay, "channel": channel, "key": key}, separators=(",", ":")).encode()
    return "outstation1:" + base64.urlsafe_b64encode(raw).decode().rstrip("=")


def key_refusal(ch, key):
    """Why an HTTP route's key won't do for this channel, as (its status, its reason), or None: the invites' check (the
    Worker's keyRefusal). Not claimed, 404; a key under 8 characters or the wrong one, 403."""
    if ch is None or ch.key is None:
        return "404 Not Found", "no outstation called that here"
    if not isinstance(key, str) or len(key) < 8:
        return "403 Forbidden", "a transmit key needs at least 8 characters"
    if not hmac.compare_digest(key.encode("utf-8", "surrogatepass"), ch.key.encode("utf-8", "surrogatepass")):
        return "403 Forbidden", "wrong key for this channel"
    ch.key_used()
    return None


async def read_body(reader, headers, most):
    """A request's body, or None when it's past most bytes. ValueError (or IncompleteReadError): one that can't be read."""
    if headers.get("transfer-encoding", "").lower() == "chunked":
        raw = await read_chunked(reader)
    else:
        n = int(headers.get("content-length", "0") or 0)
        if n > most:
            if n <= 16 * most:
                await reader.readexactly(n)   # (read and dropped, so a reset doesn't take the answer with it)
            return None
        raw = await reader.readexactly(n)
    return raw if len(raw) <= most else None


# The game (11/07/2026; docs/protocol.md "The game"): the Outside plugin's fields checked, and kept. The Worker's
# gameFields and GameState (channel.ts), the same.

_BAD = object()                   # (a field, or a part of one, that isn't its shape)


class GameText(str):
    """A game socket's message held back by --delay (Relay.game): taken into the kept state when it's let go."""


def no_constant(name):
    """NaN and Infinity aren't JSON (the Worker's JSON.parse refuses them): a message with one is ignored."""
    raise ValueError(name)


def game_whole(v, most=GAME_INT):
    """A whole number from 0 to most (5.0 counts as 5), or None."""
    if isinstance(v, float) and v.is_integer():
        v = int(v)
    return v if isinstance(v, int) and not isinstance(v, bool) and 0 <= v <= most else None


def game_pair(v):
    """[a, b], two whole numbers (an item and how many; a vital now and at most), or _BAD."""
    if isinstance(v, list) and len(v) == 2:
        a, b = game_whole(v[0]), game_whole(v[1])
        if a is not None and b is not None:
            return [a, b]
    return _BAD


def game_slots(v, most):
    """At most `most` slots, each [item, how many] or null (empty), or _BAD."""
    if not isinstance(v, list) or len(v) > most:
        return _BAD
    out = [None if s is None else game_pair(s) for s in v]
    return _BAD if any(s is _BAD for s in out) else out


def game_fields(g):
    """A {"game"} message's fields as they're kept: each known field checked and cleaned (its words one line, its numbers
    whole), one that isn't its shape left out, and unknown ones left out. Always in this order, whatever order they
    came in, so both relays write them out the same; "items" in id order (the Worker's objects keep number-like keys
    so)."""
    out = {}
    if not isinstance(g, dict):
        return out
    if isinstance(g.get("name"), str):
        out["name"] = one_line(g["name"], 12)
    if game_whole(g.get("world")) is not None:
        out["world"] = game_whole(g["world"])
    if isinstance(g.get("type"), str):
        out["type"] = one_line(g["type"], 40)
    if isinstance(g.get("off"), bool):
        out["off"] = g["off"]
    if g.get("paused") is False or isinstance(g.get("paused"), str):
        # (why it's stopped sending what's worn and carried, for the player's safety: "wilderness"; false, carrying on)
        out["paused"] = False if g["paused"] is False else one_line(g["paused"], 32)
    if isinstance(g.get("vitals"), dict):
        v, vitals = g["vitals"], {}
        for k in ("hp", "prayer"):
            if game_pair(v.get(k)) is not _BAD:
                vitals[k] = game_pair(v[k])
        for k in ("run", "spec"):
            if game_whole(v.get(k), 100) is not None:
                vitals[k] = game_whole(v[k], 100)
        out["vitals"] = vitals
    if isinstance(g.get("skills"), dict):
        out["skills"] = {k: [game_whole(x) for x in v] for k, v in g["skills"].items()
                         if GAME_SKILL.fullmatch(k) and isinstance(v, list) and len(v) == 3
                         and all(game_whole(x) is not None for x in v)}
    for k, most in (("worn", 14), ("inv", 28), ("bag", 28), ("pouch", 8)):
        if k in g:
            s = None if g[k] is None and k == "bag" else game_slots(g[k], most)   # (the bag: null, not known yet)
            if s is not _BAD:
                out[k] = s
    if "quiver" in g:
        s = None if g["quiver"] is None else game_pair(g["quiver"])
        if s is not _BAD:
            out["quiver"] = s
    p = g.get("prayers")
    if isinstance(p, list) and len(p) <= 64 and all(isinstance(x, str) for x in p):
        out["prayers"] = [one_line(x, 40) for x in p]
    if isinstance(g.get("items"), dict):
        items = {}
        for k in sorted((k for k in g["items"] if GAME_ID.fullmatch(k) and int(k) <= GAME_INT), key=int):
            v = g["items"][k]
            if isinstance(v, list) and len(v) == 2 and isinstance(v[0], str) and game_whole(v[1]) is not None:
                items[k] = [one_line(v[0], 60), game_whole(v[1])]
        if items:
            out["items"] = items
    return out


def game_where(g):
    """A {"game"} message's "where" (following, 11/08/2026): {"x", "y"} for a square's middle (each a whole number
    k*128 + 64, x from 1024 up to 4096, y from 2496 up to 4224, and nothing else in it) or for Zanaris (WHERE_ZANARIS,
    exactly), None to clear it (null), or _BAD for anything else (none, or not its shape: ignored, and the rest of the message taken as ever). Never one of
    the game's fields (game_fields leaves it out). The Worker's gameWhere, the same."""
    if not isinstance(g, dict) or "where" not in g:
        return _BAD
    w = g["where"]
    if w is None:
        return None
    if not isinstance(w, dict) or not (len(w) == 2 or (len(w) == 3 and "world" in w)):
        return _BAD
    out = {}
    for k in ("x", "y"):
        v = game_int(w.get(k))                           # (3136.0 is 3136, as JavaScript reads it)
        if v is None:
            return _BAD
        out[k] = v
    world = {}
    if len(w) == 3:
        if not is_world(w["world"]):
            return _BAD
        world = {"world": game_int(w["world"])}
    if out == WHERE_ZANARIS:
        return dict(WHERE_ZANARIS, **world)
    if not (is_square(out["x"], WHERE_X) and is_square(out["y"], WHERE_Y)):
        return _BAD
    return dict(out, **world)


def game_int(v):
    """A whole number as JavaScript reads one (3136.0 is 3136; true isn't one), or None."""
    if isinstance(v, float) and v.is_integer():
        v = int(v)
    return v if isinstance(v, int) and not isinstance(v, bool) else None


def is_square(v, bounds):
    """A square's middle along one side of the main map: a whole number k*128 + 64 from bounds[0] up to bounds[1]."""
    v = game_int(v)
    return v is not None and bounds[0] <= v < bounds[1] and v % WHERE_SQUARE == WHERE_SQUARE // 2


def is_world(v):
    """A world's number (WORLD_RANGE). The Worker's isWorld, the same."""
    v = game_int(v)
    return v is not None and WORLD_RANGE[0] <= v <= WORLD_RANGE[1]


def game_activity(g):
    """A {"game"} message's "activity" (what they're doing, 11/08/2026): one of {"kind": "skilling", "skill"} (one of
    ACTIVITY_SKILLS), {"kind": "boss", "boss"} (one of ACTIVITY_BOSSES), {"kind": "fighting"} or {"kind": "idle"},
    exactly (a copy); None to clear it (null); or _BAD for anything else (none, or not its shape: ignored, and the rest
    of the message taken as ever). Never one of the game's fields. The Worker's gameActivity, the same."""
    if not isinstance(g, dict) or "activity" not in g:
        return _BAD
    a = g["activity"]
    if a is None:
        return None
    if not isinstance(a, dict):
        return _BAD
    kind = a.get("kind")
    if kind in ("idle", "fighting") and len(a) == 1:
        return {"kind": kind}
    if kind == "skilling" and len(a) == 2 and isinstance(a.get("skill"), str) and a["skill"] in ACTIVITY_SKILLS:
        return {"kind": "skilling", "skill": a["skill"]}
    if kind == "boss" and len(a) == 2 and isinstance(a.get("boss"), str) and a["boss"] in ACTIVITY_BOSSES:
        return {"kind": "boss", "boss": a["boss"]}
    if kind == "raid" and len(a) == 2 and isinstance(a.get("raid"), str) and a["raid"] in ACTIVITY_RAIDS:
        return {"kind": "raid", "raid": a["raid"]}
    if kind == "minigame" and len(a) == 2 and isinstance(a.get("minigame"), str) and a["minigame"] in ACTIVITY_MINIGAMES:
        return {"kind": "minigame", "minigame": a["minigame"]}
    return _BAD


def game_star(g):
    """A {"game"} message's "star" (a shooting star found, 11/08/2026): {"world", "x", "y", "tier"} (a world, a square's
    middle on the main map as "where"'s, a tier 1 to 9) or {"world", "x", "y", "gone": True}, exactly, each with "site"
    (11/09/2026: one of STAR_SITES, in its own square) or without, and "credit": True (11/09/2026: the player's asked to
    be named as its finder; opt-in) or without; or None for none or anything not its shape. The Worker's gameStar, the
    same."""
    s = g.get("star") if isinstance(g, dict) else None
    if not isinstance(s, dict) or not is_world(s.get("world")) or not is_square(s.get("x"), WHERE_X) \
            or not is_square(s.get("y"), WHERE_Y):
        return None
    at = {"world": game_int(s["world"]), "x": game_int(s["x"]), "y": game_int(s["y"])}
    if "credit" in s and s["credit"] is not True:
        return None
    if "site" in s:
        sq = STAR_SITES.get(s["site"]) if isinstance(s["site"], str) else None
        if sq is None or sq != (at["x"], at["y"]):
            return None
        at["site"] = s["site"]
    if s.get("credit") is True:
        at["credit"] = True
    keys = ",".join(sorted(k for k in s if k not in ("site", "credit")))
    tier = game_int(s.get("tier"))
    if keys == "tier,world,x,y" and tier is not None and 1 <= tier <= 9:
        return dict(at, tier=tier)
    if keys == "gone,world,x,y" and s["gone"] is True:
        return dict(at, gone=True)
    return None


class StarList:
    """Shooting stars (11/08/2026; docs/protocol.md "Shooting stars"): one for each world and square, however many told
    of it, with the first outstation on the open outstations' list whose report asked for the credit as its finder
    ("by", 11/09/2026: opt-in, "credit": true; a hidden outstation is never named) and how many did; in memory, while the
    relay runs. One outstation tells of STARS_AN_HOUR in an hour at most. The Worker's StarList (stars.ts), the same,
    kept in its Directory's storage (11/10/2026). Times in ms."""

    def __init__(self):
        self.stars = {}              # "world:x:y" -> {"world", "x", "y", "site", "tier", "found", "seen", "by", "reports"}
        self.tellers = {}            # "world:x:y" -> the channels that told of it (for "reports")
        self.gone = {}               # "world:x:y" -> when it was told gone
        self.recent = {}             # a channel -> when it told of one, the last hour

    def expire(self, now):
        """The stars and the gone past their time, and a quiet channel's times, forgotten."""
        for k in [k for k, s in self.stars.items() if now - s["found"] >= STAR_LIFE_S * 1000]:
            del self.stars[k]
            self.tellers.pop(k, None)
        for k in [k for k, t in self.gone.items() if now - t >= STAR_GONE_S * 1000]:
            del self.gone[k]
        for c in [c for c, t in self.recent.items() if now - t[-1] >= 3_600_000]:
            del self.recent[c]

    def add(self, channel, r, listed, now=None):
        """A channel's report (game_star's), and whether the channel's on the open outstations' list: whether it was
        counted (False past its STARS_AN_HOUR). A new star's added; one kept takes a tier as low or lower (a star's only
        ever mined down) and when it was seen, and a site and a finder if it had none; gone takes it off, and keeps it
        off for STAR_GONE_S."""
        now = int(time.time() * 1000) if now is None else now
        self.expire(now)
        times = [t for t in self.recent.get(channel, []) if now - t < 3_600_000]
        if len(times) >= STARS_AN_HOUR:
            return False
        times.append(now)
        self.recent[channel] = times
        k = "%d:%d:%d" % (r["world"], r["x"], r["y"])
        if r.get("gone"):
            self.stars.pop(k, None)
            self.tellers.pop(k, None)
            self.gone[k] = now
            return True
        if k in self.gone:
            return True
        had = self.stars.get(k)
        if had is not None:
            if r["tier"] <= had["tier"]:
                had["tier"], had["seen"] = r["tier"], now
            if had["site"] is None:
                had["site"] = r.get("site")
            if had["by"] is None and listed and r.get("credit") is True:
                had["by"] = channel
            self.tellers[k].add(channel)
            had["reports"] = len(self.tellers[k])
            return True
        if len(self.stars) < STARS_KEPT:
            self.stars[k] = {"world": r["world"], "x": r["x"], "y": r["y"], "site": r.get("site"), "tier": r["tier"],
                             "found": now, "seen": now,
                             "by": channel if listed and r.get("credit") is True else None, "reports": 1}
            self.tellers[k] = {channel}
        return True

    def read(self, now=None):
        """GET /stars: every star kept, by world, then x, then y."""
        now = int(time.time() * 1000) if now is None else now
        self.expire(now)
        return {"stars": sorted((dict(s) for s in self.stars.values()), key=lambda s: (s["world"], s["x"], s["y"]))}


class Game:
    """A channel's game state: each field as the plugin last sent it, and "items" merged (a name and a price for each
    item id it's been told of, GAME_ITEMS ids at most). Its length as JSON is kept as it changes, so a message that
    would take it past GAME_KEPT is found without writing the whole of it out each time."""

    ITEMS = len('"items": {}')

    def __init__(self):
        self.kept = {}
        self.at = 0                  # when it last changed (ms)
        self.lens = {}               # each field's length in the kept state's JSON, as '"field": value'
        self.item_lens = {}          # each item's, as '"id": [name, price]'
        self.items_body = 0          # those summed

    def size(self, lens=None):
        """The kept state's length as JSON (json.dumps's; the Worker's pyjson writes the same)."""
        lens = self.lens if lens is None else lens
        return 2 + sum(lens.values()) + 2 * max(0, len(lens) - 1)

    @staticmethod
    def item_len(i, e):
        return len(json.dumps(i)) + 2 + len(json.dumps(e))

    def apply(self, fields, now=None):
        """A message's fields (game_fields) taken in. What goes on to viewers: the fields given (items: those not
        ignored), or None when nothing changed or when it would take the kept state past GAME_KEPT (then none of it is
        kept)."""
        changed, out, lens = False, {}, dict(self.lens)
        for k, v in fields.items():
            if k != "items":
                line = json.dumps(v)
                changed = changed or k not in self.kept or line != json.dumps(self.kept[k])
                lens[k] = len(json.dumps(k)) + 2 + len(line)
                out[k] = v
        have = self.kept.get("items", {})
        items, new_lens, body, n = {}, {}, self.items_body, len(have)
        for i, e in fields.get("items", {}).items():
            if i not in have:
                if n >= GAME_ITEMS:
                    continue             # (the dictionary's full: a new id is ignored)
                n += 1
                changed = True
            elif e != have[i]:
                changed = True
            new_lens[i] = self.item_len(i, e)
            body += new_lens[i] - self.item_lens.get(i, 0)
            items[i] = e
        if items:
            lens["items"] = self.ITEMS + body + 2 * (n - 1)
        if not changed or self.size(lens) > GAME_KEPT:
            return None
        self.kept.update(out)
        if items:
            self.kept.setdefault("items", {}).update(items)
            self.item_lens.update(new_lens)
            self.items_body = body
            out["items"] = items
        self.lens = lens
        self.at = int(time.time() * 1000) if now is None else now
        return out


# The Array's events (11/08/2026, Jace: each outstation on outside.wiki's front page should look alive, its icon popping
# "+1" as it gets a kill, a level, a pet, a big drop, a log slot or a clue; docs/protocol.md "The Array's events"): what
# each field report adds (array_events), and the feed that keeps the last of them (ArrayFeed). A channel adds a report's
# events only while it's live and listed (Channel.deliver), so a dark or coded outstation's reports never show. The
# Worker's arrayEvents and ArrayFeed (array.ts), the same.

def array_whole(v):
    """A number an event may carry: a whole one, 0 to SAFE_INT, or None. 5.0 is 5 (the Worker can't tell them apart)."""
    if isinstance(v, bool):
        return None
    if isinstance(v, float) and v.is_integer():
        v = int(v)
    return v if isinstance(v, int) and 0 <= v <= SAFE_INT else None


def array_word(v):
    """A name an event may carry: one line, ARRAY_WORD characters at most, or None."""
    return one_line(v, ARRAY_WORD) or None


def array_events(report):
    """What one field report adds to the Array's events, each {"kind", ...} (its id, time and channel are the feed's),
    from the report's type and the numbers it carries (its data, as fold reads them): a kill (the boss and its count),
    a level for each skill levelled, a pet (its name if Dink gave one), a drop worth ARRAY_LOOT or more (the best item's
    name and the drop's whole value), a collection log slot (the item) and a clue (its tier and how many, if given).
    Anything else adds nothing."""
    if not isinstance(report, dict):
        return []
    d = report.get("data") if isinstance(report.get("data"), dict) else {}
    kind = report.get("type")

    def given(e):
        return {k: v for k, v in e.items() if v is not None}
    if kind == "KILL_COUNT":
        boss, kc = array_word(d.get("boss")), array_whole(d.get("count"))
        return [{"kind": "kill", "boss": boss, "kc": kc}] if boss and kc is not None else []
    if kind == "LEVEL":
        skills = d.get("skills") if isinstance(d.get("skills"), dict) else {}
        out = []
        for k, v in skills.items():
            skill, level = array_word(k), array_whole(v)
            if skill and GAME_SKILL.fullmatch(skill) and level is not None:
                out.append({"kind": "level", "skill": skill, "level": level})
        return out
    if kind == "PET":
        return [given({"kind": "pet", "name": array_word(d.get("pet"))})]
    if kind == "LOOT":
        value = array_whole(d.get("value"))
        if value is None or value < ARRAY_LOOT:
            return []
        name, most = None, -1
        for i in d.get("items") if isinstance(d.get("items"), list) else []:
            if not isinstance(i, dict):
                continue
            n, worth = array_word(i.get("name")), (array_whole(i.get("quantity")) or 0) * (array_whole(i.get("price")) or 0)
            if n and worth > most:
                name, most = n, worth
        return [given({"kind": "loot", "name": name, "value": value})]
    if kind == "COLLECTION":
        return [given({"kind": "clog", "name": array_word(d.get("item"))})]
    if kind == "CLUE":
        return [given({"kind": "clue", "tier": array_word(d.get("tier")), "count": array_whole(d.get("count"))})]
    return []


# the keeper's backfill (POST /admin/array, 11/10/2026): a real event the feed lost, put back in its place. Each kind's
# fields as array_events makes them: (field, what it is, needed). ARRAY_BACKFILL at most at once. The Worker's
# arrayBackfill and ArrayFeed.backfill (array.ts), the same.
ARRAY_BACKFILL = 50
ARRAY_KINDS = {"kill": [("boss", "word", True), ("kc", "whole", True)],
               "level": [("skill", "skill", True), ("level", "whole", True)],
               "pet": [("name", "word", False)],
               "loot": [("name", "word", False), ("value", "loot", True)],
               "clog": [("name", "word", False)],
               "clue": [("tier", "word", False), ("count", "whole", False)]}


def array_backfill(body, now=None):
    """The keeper's events ({"channel", "events": [{"kind", ...its fields, "at": ms}]}), each exactly as array_events
    would have made it (the same kinds and fields, a name one line of ARRAY_WORD at most, a number whole; a kill's boss
    and count, a level's skill and level, a drop's value of ARRAY_LOOT or more needed; nothing else), with when it
    happened, not after now: (events, None), or (None, why not)."""
    now = int(time.time() * 1000) if now is None else now
    lst = body.get("events") if isinstance(body, dict) else None
    if not isinstance(lst, list) or not lst:
        return None, "events: a list of one event or more"
    if len(lst) > ARRAY_BACKFILL:
        return None, "%d events at most at once" % ARRAY_BACKFILL
    out = []
    for n, e in enumerate(lst):
        def no(why):
            return None, "event %d: %s" % (n + 1, why)
        if not isinstance(e, dict):
            return no("not an event")
        fields = ARRAY_KINDS.get(e.get("kind")) if isinstance(e.get("kind"), str) else None
        if fields is None:
            return no("kind is one of " + ", ".join(ARRAY_KINDS))
        at = array_whole(e.get("at")) if "at" in e else None
        if at is None:
            return no("at: when it happened, in ms")
        if at > now:
            return no("at is after now")
        for k in e:
            if k not in ("kind", "at") and not any(f == k for f, _, _ in fields):
                return no("a %s has no %s" % (e["kind"], k))
        ev = {"kind": e["kind"]}
        for f, what, needed in fields:
            if f not in e:
                if needed:
                    return no("a %s needs its %s" % (e["kind"], f))
                continue
            v = e[f]
            if what == "whole":
                ok = array_whole(v) is not None
            elif what == "loot":
                ok = array_whole(v) is not None and v >= ARRAY_LOOT
            else:
                ok = isinstance(v, str) and array_word(v) == v and (what != "skill" or bool(GAME_SKILL.fullmatch(v)))
            if not ok:
                return no("value: a whole number, %d or more" % ARRAY_LOOT if what == "loot" else
                          "%s: a whole number" % f if what == "whole" else
                          "%s: one line of %d characters at most%s" % (f, ARRAY_WORD, ", a skill's name" if what == "skill" else ""))
            ev[f] = array_whole(v) if what in ("whole", "loot") else v
        ev["at"] = at
        out.append(ev)
    return out, None


class ArrayFeed:
    """The Array's events: the last ARRAY_KEPT, oldest first, each {"id", "at", "channel", "kind", ...}, in memory while
    the relay runs (the Worker's ArrayFeed keeps them in its Directory's storage, 11/10/2026). An id goes up with each event, and is about the moment's ms (one more than the last, when that's later), so ids
    keep going up after a restart and a page's cursor (since) still works."""

    def __init__(self, now=None):
        self.events = deque(maxlen=ARRAY_KEPT)
        self.last = int(time.time() * 1000) if now is None else now   # the newest id given, or when the feed began
        self.recent = {}             # a channel -> when (ms) its events of the last minute came, for ARRAY_A_MINUTE

    def add(self, channel, events, now=None):
        """A channel's events into the feed, as many as ARRAY_A_MINUTE leaves room for (the rest dropped); those kept."""
        now = int(time.time() * 1000) if now is None else now
        for c in [c for c, t in self.recent.items() if now - t[-1] >= 60000]:
            del self.recent[c]
        times = [t for t in self.recent.get(channel, []) if now - t < 60000]
        added = []
        for e in events:
            if len(times) >= ARRAY_A_MINUTE:
                break
            times.append(now)
            self.last = max(now, self.last + 1)
            added.append(dict({"id": self.last, "at": now, "channel": channel}, **e))
        self.events.extend(added)
        if times:
            self.recent[channel] = times
        return added

    def backfill(self, channel, lost, now=None):
        """The keeper's backfill (array_backfill's events, for a channel claimed and listed): each put in its place by
        when it happened, with an id between its neighbours' (its own ms when that fits), so the feed stays in id order
        and every cursor still works. One with no room there (its neighbours' ids one apart) gets a new id at the end,
        as a new event would, and keeps its own time. Counted against nothing. None older than the oldest kept when the
        feed is full (it would go at once): (the events kept, None), or (None, why not) and nothing added."""
        now = int(time.time() * 1000) if now is None else now
        events = list(self.events)
        if len(events) >= ARRAY_KEPT and any(e["at"] < events[0]["at"] for e in lost):
            return None, "older than the feed holds now (its oldest is from %d)" % events[0]["at"]
        kept = []
        for e in sorted(lost, key=lambda e: e["at"]):
            at = e["at"]
            i = len(events)
            while i > 0 and events[i - 1]["at"] > at:
                i -= 1
            lo = events[i - 1]["id"] if i > 0 else 0
            hi = events[i]["id"] if i < len(events) else None
            ident = max(at, lo + 1) if hi is None else min(max(at, lo + 1), hi - 1)
            if ident <= lo:             # (no room between its neighbours: a new id, at the end)
                ident = self.last = max(now, self.last + 1)
                i = len(events)
            self.last = max(self.last, ident)
            k = dict({"id": ident, "at": at, "channel": channel}, **{f: v for f, v in e.items() if f != "at"})
            events.insert(i, k)
            kept.append(k)
        self.events = deque(events[-ARRAY_KEPT:], maxlen=ARRAY_KEPT)
        return kept, None

    def read(self, since=None):
        """GET /array/events: without since the last ARRAY_LATEST, with it the first ARRAY_PAGE after it; next, the
        cursor for the next read (the last event's id, or with none, since, or the newest id when since is past it)."""
        if since is None:
            got = list(self.events)[-ARRAY_LATEST:]
        else:
            got = [e for e in self.events if e["id"] > since][:ARRAY_PAGE]
        if got:
            nxt = got[-1]["id"]
        else:
            nxt = self.last if since is None else min(since, self.last)
        return {"events": got, "next": nxt}


# The aether (11/07/2026; docs/protocol.md "The aether"): a line cleaned row by row (it may be ASCII art, so it isn't made
# one line), a visitor's name, and the rate. The Worker's aetherText, aetherName, channelForm, aetherRecent and
# aetherWait (text.ts), the same.

def aether_text(text):
    """A line for the aether, or "" (ignored): its control characters taken out (but the newlines between its rows),
    each row cut to AETHER_WIDTH characters and its trailing spaces trimmed, the empty rows at its start and end dropped,
    and its first AETHER_ROWS rows kept. A row's leading spaces stay (the art's shape). Characters are code points, as
    the Worker counts them."""
    if not isinstance(text, str):
        return ""
    rows = [AETHER_TRAILING.sub("", r[:AETHER_WIDTH]) for r in AETHER_CONTROL.sub("", text).split("\n")]
    a, b = 0, len(rows)
    while a < b and not rows[a]:
        a += 1
    while b > a and not rows[b - 1]:
        b -= 1
    kept = rows[a:b][:AETHER_ROWS]
    while kept and not kept[-1]:
        kept.pop()
    return "\n".join(kept)


def aether_name(name):
    """A visitor's name in the aether, or "" (refused): runs of spaces made one and its ends trimmed, then 1 to 20
    letters, digits, spaces, "_", "-" or "." (ASCII only, so no name can pass for another by its look)."""
    if not isinstance(name, str):
        return ""
    s = re.sub(" +", " ", name).strip(" ")
    return s if AETHER_NAME.fullmatch(s) else ""


def channel_form(name):
    """A name as a channel would have it: lower case, no spaces (a visitor's may not be an outstation's, so)."""
    return name.lower().replace(" ", "")


def aether_recent(times, now):
    """The times (ms, oldest first) of someone's last lines in the aether, kept to the last minute's AETHER_A_MINUTE."""
    return [t for t in times if now - t < 60000][-AETHER_A_MINUTE:]


def aether_wait(times, now):
    """How long someone must wait before a line goes (seconds, rounded up to a tenth), or 0 when it may go now: a line
    every AETHER_EVERY ms at most, and AETHER_A_MINUTE in a minute. times: their last lines' (aether_recent), in ms."""
    until = 0
    if times and now - times[-1] < AETHER_EVERY:
        until = times[-1] + AETHER_EVERY
    minute = [t for t in times if now - t < 60000]
    if len(minute) >= AETHER_A_MINUTE:
        until = max(until, minute[-AETHER_A_MINUTE] + 60000)
    if until <= now:
        return 0
    wait = math.ceil((until - now) / 100) / 10
    return int(wait) if wait == int(wait) else wait      # (2, not 2.0: as the Worker writes it)


class Aether:
    """The aether's room (the Worker's aether.ts, the same): who's here, the last AETHER_KEPT lines, the bans, and the
    times of each one's last lines. In memory, as everything here, while the relay runs. Each socket in it has .aether,
    {"from", "name", "who"}: who is "device:<id>" for a visitor and "channel:<name>" for an outstation, what a ban holds
    on to and what the rate's counted by (so a reconnect doesn't start it again)."""

    def __init__(self):
        self.socks = set()
        self.lines = deque(maxlen=AETHER_KEPT)   # each {"id", "name", "text", "from", "at", "who"}
        self.next_id = 0
        self.bans = {}                           # who -> {"who", "name", "at"}
        self.said = {}                           # who -> their last lines' times (ms; aether_recent)
        self.here_sent, self.here_at, self.here_timer = None, 0.0, None

    @staticmethod
    def public(line):
        """A line as everyone sees it: never who wrote it (a device, a channel), only the name."""
        return {k: line[k] for k in ("id", "name", "text", "from", "at")}

    def count(self):
        """How many are here: each visitor's device and each outstation once, however many sockets it has open."""
        return len({s.aether["who"] for s in self.socks})

    def broadcast(self, line):
        for s in list(self.socks):
            s.send_nowait(line)

    def here_changed(self, joiner=None):
        """Who's here changed (or someone just came: joiner): everyone's told the count at most every
        AETHER_HERE_EVERY, the last change waiting its turn; whoever just came hears it at once. (One told a count the
        others weren't means everyone's told at the next turn, even if the count's back to what they were told by then.)"""
        n, now = self.count(), time.monotonic()
        if n != self.here_sent and now - self.here_at >= AETHER_HERE_EVERY:
            return self.tell_here()
        if joiner is not None:
            joiner.send_nowait(json.dumps({"aether_here": n}))
            if n != self.here_sent:
                self.here_sent = None
        if n != self.here_sent and self.here_timer is None:
            self.here_timer = asyncio.get_running_loop().call_later(
                max(0.0, self.here_at + AETHER_HERE_EVERY - now), self.tell_here)

    def tell_here(self):
        if self.here_timer is not None:
            self.here_timer.cancel()
        self.here_timer = None
        n = self.count()
        if n == self.here_sent:
            return
        self.here_sent, self.here_at = n, time.monotonic()
        self.broadcast(json.dumps({"aether_here": n}))

    def take(self, sock, msg):
        """A message from a socket in the room: a {"say"} line, cleaned (aether_text), to everyone here, unless its
        sender's last line was under AETHER_EVERY ago or it's said AETHER_A_MINUTE this minute (then only it hears
        {"aether_slow": seconds}, and the line goes nowhere). Anything else is thrown away."""
        if sock not in self.socks:
            return                           # (banned: its socket's on its way out)
        try:
            m = json.loads(msg)
        except (ValueError, RecursionError):
            return
        if not isinstance(m, dict) or "say" not in m:
            return
        text = aether_text(m["say"])
        if not text:
            return
        now, who = int(time.monotonic() * 1000), sock.aether["who"]
        times = aether_recent(self.said.get(who, []), now)
        wait = aether_wait(times, now)
        if wait:
            sock.send_nowait(json.dumps({"aether_slow": wait}))
            return
        self.said[who] = (times + [now])[-AETHER_A_MINUTE:]
        if len(self.said) > 1000:
            self.said = {k: t for k, t in self.said.items() if aether_recent(t, now)}
        self.next_id += 1
        line = {"id": self.next_id, "name": sock.aether["name"], "text": text, "from": sock.aether["from"],
                "at": int(time.time() * 1000), "who": who}
        self.lines.append(line)
        self.broadcast(json.dumps({"aether": self.public(line)}))

    def unsay(self, ids):
        """Lines taken back off everyone's page (the keeper's), each told {"aether_unsay": id}."""
        gone = set(ids)
        self.lines = deque((l for l in self.lines if l["id"] not in gone), maxlen=AETHER_KEPT)
        for i in ids:
            self.broadcast(json.dumps({"aether_unsay": i}))

    def ban(self, line):
        """Whoever wrote this line banned (by its device or its channel): every line of theirs kept taken back, and
        their sockets closed (4033). The ids taken back."""
        who = line["who"]
        self.bans[who] = {"who": who, "name": line["name"], "at": int(time.time() * 1000)}
        gone = [l["id"] for l in self.lines if l["who"] == who]
        self.unsay(gone)
        for s in [s for s in self.socks if s.aether["who"] == who]:
            self.socks.discard(s)
            asyncio.ensure_future(s.close(4033, "banned from the aether"))
        self.here_changed()
        return gone

    def read(self):
        """GET /aether's: the last AETHER_READ lines as everyone sees them, and how many are here."""
        return {"lines": [self.public(l) for l in list(self.lines)[-AETHER_READ:]], "here": self.count()}


class Channel:
    def __init__(self, name, key, array=None, stars=None):
        self.name, self.key = name, key
        self.array = array           # the relay's ArrayFeed: this channel's events go in while it's live and listed
        self.stars = stars           # the relay's StarList: the shooting stars its plugin finds go in
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
        self.acked_seen = [(0, time.monotonic())]   # (samples of the last 5 s or so: tell_upload)
        self.replay = deque()        # (arrival, message): the last replay_seconds of frames and sound, for a clip
        self.kc = {}                 # boss (lower case) -> (its name, the kill count Dink last told of)
        self.guess = None            # the drop guess round (11/05/2026): see from_desk's guess_open
        self.guess_id = 0
        self.out_seen = (0, time.monotonic())
        self.clips_uploads = []      # when its clips came, for CLIPS_AN_HOUR
        self.dms = []                # messages to this portal held till its desk opens (send_dm)
        self.dms_sent = []           # when this portal sent its last messages, for DMS_A_MINUTE
        self.summary = None          # this stream's summary (fold): {"since", "ended", "boss", "kc", "loot", ...}
        # invites (11/07/2026): time on air in all (in memory, as everything here), the names it's invited, and whose
        # invite it came from (None: claimed by its first transmitter)
        self.live_all = 0.0          # seconds on air in all, the stretch going on now not included
        self.live_since = None       # when this stretch on air began (time.monotonic()), while it lasts
        self.invited = []
        self.invite_posts = []       # when it last posted for an invite, for INVITES_AN_HOUR
        self.by = None
        # an invite not taken up yet (11/08/2026): made by another outstation's invite, its key never used since. Till
        # it is, GET /channels leaves it out and only counts it under its inviter ("pending"): the inviter typed the
        # name, and the friend hasn't agreed to anything. The first right key, from anything, takes it up (key_used).
        self.pending = False
        # the game (11/07/2026): the Outside plugin's socket, and the state it's sent (kept while the relay runs, through
        # streams and the plugin's going: a page shows the last of it while the player's away)
        self.gamer = None
        self.game = Game()
        # following (11/08/2026; game_where): the square the plugin last said, when it came, and when the last one was
        # taken (for WHERE_EVERY_S), by time.monotonic(); in memory, as everything here
        self.where = None
        self.where_at = None
        self.where_took = None
        self.where_ms = 0            # (your year: when it came, ms since 1970, for the desks' {"mine"})
        # what they're doing (11/08/2026; game_activity): kept, taken and dropped exactly as "where" is
        self.activity = None
        self.activity_at = None
        self.activity_took = None
        self.activity_ms = 0

    def seconds_in_all(self):
        """Seconds on air in all, the stretch going on now included (for invites)."""
        return self.live_all + (time.monotonic() - self.live_since if self.live_since is not None else 0.0)

    def summary_line(self):
        return json.dumps({"summary": self.summary})

    def game_line(self):
        """The whole game state, for someone just come (a seat, a desk, a looker); None while there's none."""
        return json.dumps({"game": dict(self.game.kept, at=self.game.at)}) if self.game.kept else None

    def take_game(self, msg):
        """A message from the game socket (Relay.game) into the kept state, and what it changed on to the seated
        viewers, the lookers and the desks."""
        try:
            m = json.loads(msg, parse_constant=no_constant)
        except (ValueError, RecursionError):
            return
        if not isinstance(m, dict) or not isinstance(m.get("game"), dict):
            return
        fields = game_fields(m["game"])
        self.take_where(game_where(m["game"]), fields)
        self.take_activity(game_activity(m["game"]), fields)
        self.take_star(game_star(m["game"]), fields)
        got = self.game.apply(fields)       # (never "where", "activity" or "star": game_fields leaves them out)
        if got is None:
            return
        line = json.dumps({"game": dict(got, at=self.game.at)})
        for v in list(self.viewers) + list(self.lookers) + list(self.desks):
            v.send_nowait(line)

    def take_where(self, w, fields):
        """Following (game_where): a message's "where" taken (one every WHERE_EVERY_S at most; more are ignored), or the
        one held dropped (null, whenever it comes). Logged out ({"off": true}) or held for the player's safety
        ({"paused": "wilderness"}), it's dropped, whatever the message says of it; and nothing's taken while no plugin's
        connected (a message --delay held back, let go after it went). Only GET /channels hears of it."""
        if fields.get("off") is True or isinstance(fields.get("paused"), str) or w is None:
            self.drop_where()
            return
        now = time.monotonic()
        if w is _BAD or self.gamer is None or (self.where_took is not None and now - self.where_took < WHERE_EVERY_S):
            return
        self.where, self.where_at, self.where_took = w, now, now
        self.where_ms = int(time.time() * 1000)
        self.tell_mine({"where": w, "at": self.where_ms})

    def drop_where(self):
        held = self.where is not None
        self.where = self.where_at = None
        if held:
            self.tell_mine({"where": None, "at": int(time.time() * 1000)})

    def tell_mine(self, m):
        """Your year (11/10/2026; docs/record.md): one of the owner's own things, {"mine": {...}}, to this channel's
        desks only (the app's chat window, which only the key opens): never a viewer, a looker or anything else. The
        Worker's tellMine, the same."""
        if not self.desks:
            return
        line = json.dumps({"mine": m})
        for d in list(self.desks):
            d.send_nowait(line)

    def take_activity(self, a, fields):
        """What they're doing (game_activity): taken and dropped exactly as take_where takes "where", one every
        WHERE_EVERY_S at most, null whenever it comes; logged out or held for the player's safety, dropped; nothing taken
        while no plugin's connected."""
        if fields.get("off") is True or isinstance(fields.get("paused"), str) or a is None:
            self.drop_activity()
            return
        now = time.monotonic()
        if a is _BAD or self.gamer is None or (self.activity_took is not None and now - self.activity_took < WHERE_EVERY_S):
            return
        self.activity, self.activity_at, self.activity_took = a, now, now
        self.activity_ms = int(time.time() * 1000)
        self.tell_mine({"activity": a, "at": self.activity_ms})

    def drop_activity(self):
        held = self.activity is not None
        self.activity = self.activity_at = None
        if held:
            self.tell_mine({"activity": None, "at": int(time.time() * 1000)})

    def take_star(self, star, fields):
        """A shooting star the plugin found (game_star): into the relay's StarList, which counts this channel's reports
        and names it as the star's finder only if it's listed. Not from a message that logs out or holds for the player's safety, nor once
        the plugin's gone (a report --delay held back)."""
        if star is None or self.stars is None or self.gamer is None or fields.get("off") is True \
                or isinstance(fields.get("paused"), str):
            return
        self.stars.add(self.name, star, self.listed())

    def on_air(self):
        """A transmitter's come: a new stream's summary, unless it went off air only a little while ago."""
        now = int(time.time() * 1000)
        if self.summary is None or (self.summary.get("ended") and now - self.summary["ended"] > SUMMARY_GAP * 1000):
            self.summary = {"since": now}
        else:
            self.summary.pop("ended", None)
        self.tell_summary()
        if self.live_since is None:  # (a transmitter replacing another: the same stretch goes on)
            self.live_since = time.monotonic()

    def off_air(self):
        if self.live_since is not None:
            self.live_all += time.monotonic() - self.live_since
            self.live_since = None
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

    def listed(self):
        """Whether it's on the open outstations' list (GET /channels): claimed, with no watch code, and not an invite
        still waiting to be taken up. The Array's events come only from a channel listed (and live)."""
        return self.key is not None and not self.code and not self.pending

    def key_used(self):
        """Its key's been used, rightly (a socket, Dink, clips or invites): an invite not taken up yet is
        taken up, and on the open outstations' list from now on (the Worker's checkKey, the same)."""
        self.pending = False

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
              "title": self.title, "game": self.gamer is not None}     # (game: the Outside plugin's connected, 11/07/2026)
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
        if self.game.kept:
            sock.send_nowait(self.game_line())
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
        if isinstance(msg, GameText):
            return self.take_game(msg)       # (the game's, held back by the delay: Relay.game)
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
            try:
                report = json.loads(msg).get("report")
            except (ValueError, RecursionError):
                report = None
            # (a malformed report folds to nothing rather than dropping the connection, as the Worker does)
            try:
                summed = self.summary is not None and fold(self.summary, report)
            except (TypeError, ValueError, AttributeError, KeyError):
                summed = False
            if summed:
                # (after the report itself has gone to viewers, below)
                asyncio.get_event_loop().call_soon(self.tell_summary)
            if self.array is not None and self.sender is not None and self.listed():
                # the Array's events (11/08/2026): only while it's live and on the open outstations' list, so a dark
                # or coded outstation's reports never show (with --delay, as the report reaches viewers)
                try:
                    self.array.add(self.name, array_events(report))
                except (TypeError, ValueError, AttributeError, KeyError):
                    pass
            if self.desks:
                # (your year: the owner's own events, live or dark, listed or not, to its desks only)
                try:
                    events = array_events(report)
                except (TypeError, ValueError, AttributeError, KeyError):
                    events = []
                at = int(time.time() * 1000)
                for e in events:
                    self.tell_mine({"event": e, "at": at})
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


# Your year (11/10/2026; docs/record.md, docs/protocol.md "Your year"; the Worker's record.ts, the same): each player's
# record of their own travels, sealed by their app with a key only their own computers hold, so only ciphertext is ever
# kept here, and the public summary they may choose to post. Kept in a folder (--records), one a channel: its tokens'
# SHA-256 (tokens.json), its chunks (chunks/<id>, with meta.json: each one's time and size) and its summary (year.json).
RECORD_CHUNK = 256 * 1024                   # a sealed chunk at most
RECORD_CHUNK_LEAST = 1 + 12 + 1 + 16        # the format, the nonce, a byte, the tag
RECORD_CHUNKS, RECORD_BYTES, RECORD_DAYS, RECORD_AN_HOUR = 2000, 32 * 1024 * 1024, 400, 240
RECORD_KEYS = 4                             # token pairs at most (a key pasted wrong on one PC never wipes another's)
RECORD_PAGE = 4 * 1024 * 1024               # a ?data=1 answer's chunks, in bytes of data, at most
CHUNK_ID = re.compile(r"[A-Za-z0-9_-]{22}")  # (fullmatch)
RECORD_TOKEN = re.compile(r"[A-Za-z0-9_-]{43}")
YEAR_MOST = 512 * 1024
YEAR_PARTS = ("v", "range", "totals", "heat", "paths", "activities", "hours", "places", "moments", "replay")
YEAR_TOTALS = ("minutes", "live_minutes", "days", "squares", "kills", "levels", "pets", "drops", "clogs", "clues")
YEAR_HEAT, YEAR_PATHS, YEAR_PATH_STEPS, YEAR_STEPS, YEAR_LIST = 4096, 256, 2000, 20000, 64
YEAR_TOTAL_MOST, YEAR_MINUTES_MOST, YEAR_T_LEAST = 1_000_000_000, 10_000_000, 1_577_836_800_000


class YearNo(Exception):
    pass


def year_whole(v, lo, hi):
    """A whole number from lo to hi (5.0 is 5, as JavaScript reads it), or None."""
    if isinstance(v, bool):
        return None
    if isinstance(v, float) and v.is_integer():
        v = int(v)
    return v if isinstance(v, int) and lo <= v <= hi else None


def year_square(x, y, part):
    w = game_where({"where": {"x": x, "y": y}})
    if w is _BAD or w is None:
        raise YearNo('%s: a square\'s middle, as following\'s "where" gives it' % part)
    return [w["x"], w["y"]]


def year_time(v, part, now):
    t = year_whole(v, YEAR_T_LEAST, now + 86_400_000)
    if t is None:
        raise YearNo("%s: a time in ms, from 2020 to a day past now" % part)
    return t


def year_minutes(v, part):
    m = year_whole(v, 0, YEAR_MINUTES_MOST)
    if m is None:
        raise YearNo("%s: whole minutes, 0 to %d" % (part, YEAR_MINUTES_MOST))
    return m


def year_list(v, most, part):
    if not isinstance(v, list):
        raise YearNo("%s: a list" % part)
    if len(v) > most:
        raise YearNo("%s: %d at most" % (part, most))
    return v


def year_only(o, keys, part):
    for k in o:
        if k not in keys:
            raise YearNo("%s: no %s here" % (part, k))


def year_step(s, part, now):
    if not isinstance(s, list) or len(s) not in (2, 3):
        raise YearNo("%s: each step [x, y] or [x, y, t]" % part)
    xy = year_square(s[0], s[1], part)
    return xy + [year_time(s[2], part, now)] if len(s) == 3 else xy


def year_summary(body, now=None):
    """A public summary checked (docs/record.md, "The public summary"): (the summary as it's stored, its parts in their
    order, None), or (None, why not), the part named. The Worker's yearSummary, the same."""
    now = int(time.time() * 1000) if now is None else now
    try:
        if not isinstance(body, dict):
            raise YearNo("a year is an object of its parts")
        for k in body:
            if k not in YEAR_PARTS:
                raise YearNo("%s: not a part of a year" % k)
        if body.get("v") != 1 or isinstance(body.get("v"), bool):
            raise YearNo("v: 1")
        out = {"v": 1}
        if "range" in body:
            r = body["range"]
            if not isinstance(r, dict):
                raise YearNo("range: {from, to}")
            year_only(r, ("from", "to"), "range")
            a, b = year_time(r.get("from"), "range", now), year_time(r.get("to"), "range", now)
            if a > b:
                raise YearNo("range: from comes before to")
            out["range"] = {"from": a, "to": b}
        if "totals" in body:
            t = body["totals"]
            if not isinstance(t, dict):
                raise YearNo("totals: an object")
            year_only(t, YEAR_TOTALS, "totals")
            o = {}
            for k in YEAR_TOTALS:
                if k not in t:
                    continue
                v = year_whole(t[k], 0, YEAR_TOTAL_MOST)
                if v is None:
                    raise YearNo("totals: %s a whole number, 0 to %d" % (k, YEAR_TOTAL_MOST))
                o[k] = v
            out["totals"] = o
        if "heat" in body:
            seen, heat = set(), []
            for c in year_list(body["heat"], YEAR_HEAT, "heat"):
                if not isinstance(c, list) or len(c) != 3:
                    raise YearNo("heat: each [x, y, minutes]")
                xy = year_square(c[0], c[1], "heat")
                if tuple(xy) in seen:
                    raise YearNo("heat: a square twice")
                seen.add(tuple(xy))
                heat.append(xy + [year_minutes(c[2], "heat")])
            out["heat"] = heat
        if "paths" in body:
            n, paths = 0, []
            for p in year_list(body["paths"], YEAR_PATHS, "paths"):
                steps = year_list(p, YEAR_PATH_STEPS, "paths")
                if len(steps) < 2:
                    raise YearNo("paths: two steps or more each")
                n += len(steps)
                if n > YEAR_STEPS:
                    raise YearNo("paths: %d steps at most in all" % YEAR_STEPS)
                paths.append([year_step(s, "paths", now) for s in steps])
            out["paths"] = paths
        if "activities" in body:
            acts = []
            for a in year_list(body["activities"], YEAR_LIST, "activities"):
                if not isinstance(a, dict):
                    raise YearNo("activities: each {activity, minutes}")
                year_only(a, ("activity", "minutes"), "activities")
                label = game_activity(a)
                if label is _BAD or label is None:
                    raise YearNo("activities: an activity as the plugin names one")
                acts.append({"activity": label, "minutes": year_minutes(a.get("minutes"), "activities")})
            out["activities"] = acts
        if "hours" in body:
            h = year_list(body["hours"], 24, "hours")
            if len(h) != 24:
                raise YearNo("hours: 24 of them, one for each hour of the day")
            out["hours"] = [year_minutes(m, "hours") for m in h]
        if "places" in body:
            places = []
            for p in year_list(body["places"], YEAR_LIST, "places"):
                if not isinstance(p, dict):
                    raise YearNo("places: each {x, y, minutes, first?, last?}")
                year_only(p, ("x", "y", "minutes", "first", "last"), "places")
                x, y = year_square(p.get("x"), p.get("y"), "places")
                o = {"x": x, "y": y, "minutes": year_minutes(p.get("minutes"), "places")}
                if "first" in p:
                    o["first"] = year_time(p["first"], "places", now)
                if "last" in p:
                    o["last"] = year_time(p["last"], "places", now)
                if "first" in o and "last" in o and o["first"] > o["last"]:
                    raise YearNo("places: first comes before last")
                places.append(o)
            out["places"] = places
        if "moments" in body:
            moments = []
            for m in year_list(body["moments"], YEAR_LIST, "moments"):
                if not isinstance(m, dict) or not isinstance(m.get("event"), dict):
                    raise YearNo("moments: each {event, t?}")
                year_only(m, ("event", "t"), "moments")
                if "at" in m["event"]:
                    raise YearNo("moments: an event has no at (t is its time)")
                got, why = array_backfill({"events": [dict(m["event"], at=0)]}, now)
                if why is not None:
                    raise YearNo("moments: " + re.sub(r"^event 1: ", "", why))
                event = {k: v for k, v in got[0].items() if k != "at"}
                moments.append({"event": event, "t": year_time(m["t"], "moments", now)} if "t" in m else {"event": event})
            out["moments"] = moments
        if "replay" in body:
            out["replay"] = [year_step(s, "replay", now) for s in year_list(body["replay"], YEAR_STEPS, "replay")]
        return out, None
    except YearNo as e:
        return None, str(e)


class Records:
    """Each channel's record and summary, in a folder of its own under the relay's records folder (None: none kept)."""

    def __init__(self, base, chunks_most=RECORD_CHUNKS):
        self.base = base
        self.chunks_most = chunks_most
        self.changes = {}            # channel -> when its last uploads and deletes came (RECORD_AN_HOUR)

    def folder(self, name):
        if not self.base or not CHANNEL_NAME.fullmatch(name) or name in (".", ".."):
            return None
        return os.path.join(self.base, name)

    @staticmethod
    def read_json(path, default):
        try:
            with open(path, encoding="utf-8") as f:
                return json.load(f)
        except (OSError, ValueError):
            return default

    @staticmethod
    def write_json(path, value):
        os.makedirs(os.path.dirname(path), exist_ok=True)
        tmp = path + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(value, f)
        os.replace(tmp, path)

    def token_ok(self, name, token, which):
        if not RECORD_TOKEN.fullmatch(token or ""):
            return False
        h = hashlib.sha256(token.encode()).hexdigest()
        pairs = self.read_json(os.path.join(self.folder(name), "tokens.json"), [])
        return any(hmac.compare_digest(p.get(which, ""), h) for p in pairs if isinstance(p, dict))

    def metas(self, name, now):
        """The chunks' entries, without any not uploaded again in RECORD_DAYS (those taken away)."""
        folder = self.folder(name)
        metas = self.read_json(os.path.join(folder, "meta.json"), {})
        old = [i for i, m in metas.items() if now - m.get("at", 0) > RECORD_DAYS * 86_400_000]
        if old:
            for i in old:
                metas.pop(i, None)
                try:
                    os.remove(os.path.join(folder, "chunks", i))
                except OSError:
                    pass
            self.write_json(os.path.join(folder, "meta.json"), metas)
        return metas

    def counted(self, name, now):
        times = [t for t in self.changes.get(name, []) if now - t < 3_600_000]
        if len(times) >= RECORD_AN_HOUR:
            self.changes[name] = times
            return False
        self.changes[name] = times + [now]
        return True

    def register(self, name, body):
        if not isinstance(body, dict) or not all(isinstance(body.get(k), str) and RECORD_TOKEN.fullmatch(body[k])
                                                 for k in ("read", "write")):
            return "400 Bad Request", {"error": "the tokens: read and write, 43 base64url characters each"}
        pair = {k: hashlib.sha256(body[k].encode()).hexdigest() for k in ("read", "write")}
        path = os.path.join(self.folder(name), "tokens.json")
        pairs = self.read_json(path, [])
        if pair in pairs:
            return "204 No Content", None
        if len(pairs) >= RECORD_KEYS:
            return "409 Conflict", {"error": "%d record keys at most: delete the relay's copy to start again" % RECORD_KEYS}
        self.write_json(path, pairs + [pair])
        return "204 No Content", None

    def put(self, name, cid, data, now):
        if len(data) > RECORD_CHUNK:
            return "413 Payload Too Large", {"error": "a chunk is %d KB at most" % (RECORD_CHUNK // 1024)}
        if len(data) < RECORD_CHUNK_LEAST or data[0] != 1:
            return "415 Unsupported Media Type", {"error": "not a sealed chunk: the relay keeps only what's encrypted"}
        if not self.counted(name, now):
            return "429 Too Many Requests", {"error": "%d uploads and deletes an hour at most: try again later" % RECORD_AN_HOUR}
        folder = self.folder(name)
        metas = self.metas(name, now)
        if cid not in metas and len(metas) >= self.chunks_most:
            return "507 Insufficient Storage", {"error": "the relay's copy is full (%d chunks)" % self.chunks_most}
        if len(data) + sum(m["size"] for i, m in metas.items() if i != cid) > RECORD_BYTES:
            return "507 Insufficient Storage", {"error": "the relay's copy is full (%d MB)" % (RECORD_BYTES // 1048576)}
        os.makedirs(os.path.join(folder, "chunks"), exist_ok=True)
        tmp = os.path.join(folder, "chunks", cid + ".tmp")
        with open(tmp, "wb") as f:
            f.write(data)
        os.replace(tmp, os.path.join(folder, "chunks", cid))
        metas[cid] = {"at": now, "size": len(data)}
        self.write_json(os.path.join(folder, "meta.json"), metas)
        return "201 Created", {"id": cid, "at": now, "size": len(data)}

    def delete(self, name, cid, now):
        if not self.counted(name, now):
            return "429 Too Many Requests", {"error": "%d uploads and deletes an hour at most: try again later" % RECORD_AN_HOUR}
        folder = self.folder(name)
        metas = self.metas(name, now)
        if cid not in metas:
            return "404 Not Found", {"error": "no such chunk"}
        del metas[cid]
        try:
            os.remove(os.path.join(folder, "chunks", cid))
        except OSError:
            pass
        self.write_json(os.path.join(folder, "meta.json"), metas)
        return "204 No Content", None

    def delete_all(self, name):
        folder = self.folder(name)
        if folder and os.path.isdir(folder):
            shutil.rmtree(folder, ignore_errors=True)
        return "204 No Content", None

    def listing(self, name, data, after, now):
        folder = self.folder(name)
        metas = sorted(self.metas(name, now).items())
        total = sum(m["size"] for _, m in metas)
        if not data:
            return {"chunks": [{"id": i, "at": m["at"], "size": m["size"]} for i, m in metas], "bytes": total}
        rows, taken, nxt = [], 0, None
        for i, m in metas:
            if after and i <= after:
                continue
            if rows and taken + m["size"] > RECORD_PAGE:
                nxt = rows[-1]["id"]
                break
            try:
                with open(os.path.join(folder, "chunks", i), "rb") as f:
                    raw = f.read()
            except OSError:
                continue
            taken += m["size"]
            rows.append({"id": i, "at": m["at"], "size": m["size"], "data": base64.b64encode(raw).decode()})
        return {"chunks": rows, "bytes": total, "next": nxt}

    def year_get(self, name):
        y = self.read_json(os.path.join(self.folder(name), "year.json"), None)
        return None if y is None else '{"year": %s, "at": %d}' % (y["text"], y["at"])

    def year_put(self, name, text, now):
        self.write_json(os.path.join(self.folder(name), "year.json"), {"text": text, "at": now})
        return '{"year": %s, "at": %d}' % (text, now)

    def year_delete(self, name):
        try:
            os.remove(os.path.join(self.folder(name), "year.json"))
        except OSError:
            pass


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
    def __init__(self, delay=0.0, log=print, forward=None, max_viewers=0, min_client="", clips_dir=None, admin_token="",
                 where_fresh_s=WHERE_FRESH_S, almanac="off", test_forget=False, records_dir=None,
                 record_chunks=RECORD_CHUNKS):
        self.delay = delay
        self.records = Records(records_dir, record_chunks)  # your year (11/10/2026): None as the folder keeps none
        self.test_forget = test_forget  # POST /test/forget answered (tests only: the Worker's TEST_FORGET)
        # the almanac (11/09/2026; almanac.py): Jagex's news, the world list and the Wiki's prices, fetched on a
        # schedule (almanac_loop) and shared: "off" (nothing's fetched; GET /almanac answers its shape, empty), "on"
        # (the real hosts) or an address everything's fetched under instead (tests)
        self.almanac_on = almanac != "off"
        self.almanac = almanac_sources.Almanac(almanac_sources.async_fetch, None if almanac in ("on", "off") else almanac,
                                               log=log)
        self.where_fresh_s = where_fresh_s  # how long a "where" shows on GET /channels (WHERE_FRESH_S; tests shorten it)
        self.aether = Aether()         # the aether (11/07/2026): one room for every outstation and visitor
        self.array = ArrayFeed()       # the Array's events (11/08/2026): what's happening across the open outstations
        self.stars = StarList()        # shooting stars (11/08/2026): the ones the plugins found, and who found each
        self.admin_token = admin_token  # the keeper's (the aether's routes; 16 characters at least): "" refuses them
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
        self.wake = None             # the holder woken early (due); made in its own loop (release_held)
        self.next_wake = 0.0         # when the holder means to look next

    def cap_for(self, ch):
        """How many may watch this channel: its own cap (the desk's {\"cap\"}) or the relay's."""
        return self.max_viewers if ch.own_cap is None else ch.own_cap

    async def handle_public(self, reader, writer):
        await self.handle(reader, writer, public=True)

    async def handle(self, reader, writer, public=False):
        """public: the port the internet reaches (Tailscale Funnel): the page, watching and reading only."""
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
        aether = path == ["aether"]
        if aether:
            # (a blank one kept: "channel=" is an outstation's, as on the Worker)
            q = {k: v[0] for k, v in parse_qs(url.query, keep_blank_values=True).items()}
        visitor = aether and "channel" not in q
        # (settling's, gone: answered 410 on both sides, as nothing's read from it; Relay.settling_gone)
        settling = len(path) == 2 and path[0] in ("settle", "home") and not key
        if public and not settling and (method not in ("GET", "HEAD") or (key and (not path or path[0] != "watch") and not visitor)
                                             or (path and path[0] in ("invites", "admin"))):
            # from the internet: no transmitting, no Dink posts, no invites, no game socket, no outstation in the aether and none of the keeper's routes, whatever key comes with them
            # (the game's read, GET /game, the aether's read, a visitor in the aether, the Array's events, the shooting
            # stars are let through: what anyone on Outside may do anyway)
            writer.write(b"HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
            await writer.drain()
            writer.close()
            return
        if path and ((path[0] == "record" and len(path) in (2, 3)) or (path[0] == "year" and len(path) == 2)) and not key:
            await self.record(reader, writer, method, headers, path, q)
            return
        if path and path[0] == "clips" and 2 <= len(path) <= 3 and not key:
            await self.clips(reader, writer, method, headers, path, q)
            return
        if method == "POST" and len(path) == 2 and path[0] == "dink":
            await self.dink(reader, writer, headers, path[1], q.get("key", ""))
            return
        if len(path) == 2 and path[0] == "invites" and not key:
            await self.invites(reader, writer, method, headers, path[1], q)
            return
        if settling:
            await self.settling_gone(reader, writer, headers)
            return
        if method == "GET" and not key and url.path == "/channels":
            await self.channel_list(writer)
            return
        if len(path) == 2 and path[0] == "game" and not key:
            await self.game_read(writer, method, path[1], q)
            return
        if aether and not key:
            await self.aether_read(writer, method)
            return
        if path == ["array", "events"] and not key:
            await self.array_read(writer, method, q)
            return
        if path == ["test", "forget"] and method == "POST" and self.test_forget:
            # (the Worker's: its Directory put to sleep, to show what its storage keeps; here the process's memory is
            # the keeping, so there's nothing to forget while it runs)
            writer.write(b"HTTP/1.1 204 No Content\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
            try:
                await writer.drain()
            finally:
                writer.close()
            return
        if path == ["stars"] and not key:
            await self.stars_read(writer, method)
            return
        if path == ["almanac"] and not key:
            await self.almanac_read(writer, method)
            return
        if path == ["admin", "almanac"] and not key:
            await self.almanac_keeper(writer, method, headers)
            return
        if path == ["admin", "array"] and not key:
            await self.array_keeper(reader, writer, method, headers)
            return
        if path[:2] == ["admin", "aether"] and not key:
            await self.aether_keeper(reader, writer, method, headers, path)
            return
        if method in ("GET", "HEAD") and not key:
            await self.page(writer, url.path, method == "HEAD")
            return
        if (method != "GET" or not key or len(path) != 2 or path[0] not in ("transmit", "watch", "desk", "sound", "game")
                or not (1 <= len(path[1]) <= 64)) \
                and not (aether and method == "GET" and ("channel" not in q or CHANNEL_NAME.fullmatch(q["channel"]))):
            writer.write(b"HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\n\r\n")
            await writer.drain()
            writer.close()
            return
        accept = base64.b64encode(hashlib.sha1((key + GUID).encode()).digest()).decode()
        writer.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\n"
                      "Connection: Upgrade\r\nSec-WebSocket-Accept: %s\r\n\r\n" % accept).encode())
        await writer.drain()
        sock = Socket(reader, writer)
        if aether:
            try:
                await self.aether_socket(sock, q)
            except (Closed, asyncio.IncompleteReadError, ConnectionError):
                pass
            finally:
                await sock.close()
            return
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
            elif role == "game":
                await self.game(sock, name, q.get("key", ""))
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
            ch = self.channels[name] = Channel(name, key, self.array, self.stars)
        elif ch.key is None:         # made by a viewer waiting for it: this key claims it
            ch.key = key
        elif ch.key != key:
            await sock.close(4003, "wrong key for this channel")
            return
        ch.key_used()
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
                    self.due(ch.held[-1][0])
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
            ch = self.channels[name] = Channel(name, None, self.array, self.stars)
        sock.look_frames, sock.look_at = False, 0.0
        ch.lookers.add(sock)
        sock.send_nowait(ch.status())
        if ch.game.kept:
            sock.send_nowait(ch.game_line())
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
            ch = self.channels[name] = Channel(name, None, self.array, self.stars)
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
            ch = self.channels[name] = Channel(name, key, self.array, self.stars)
        elif ch.key is None:
            ch.key = key
        elif ch.key != key:
            await sock.close(4003, "wrong key for this channel")
            return None
        ch.key_used()
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
        if ch.game.kept:
            sock.send_nowait(ch.game_line())
        sock.send_nowait(ch.banned_line())
        sock.send_nowait(ch.viewer_list())
        # (your year: what's held now, each with when it came)
        if ch.where is not None:
            sock.send_nowait(json.dumps({"mine": {"where": ch.where, "at": ch.where_ms}}))
        if ch.activity is not None:
            sock.send_nowait(json.dumps({"mine": {"activity": ch.activity, "at": ch.activity_ms}}))
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
            return fail("that's your own outstation")
        if len(ch.dms_sent) >= DMS_A_MINUTE:
            return fail("%d messages a minute at most: wait a moment" % DMS_A_MINUTE)
        other = self.channels.get(to)
        if other is None or other.key is None:
            return fail("no outstation called %s on this relay" % to)
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
            self.due(ch.poll["ends"])
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
                        self.due(ch.sound_held[-1][0])
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

    async def game(self, sock, name, key):
        """The game (11/07/2026; docs/protocol.md "The game"): the Outside plugin's {"game"} messages into the channel's
        kept state (Channel.take_game), GAME_A_SECOND a second at most and GAME_MESSAGE long at most (the rest
        dropped, with no answer). One at a time: a newer one replaces the older. Its v= is the plugin's own version,
        which --min-client never looks at. Held back by --delay as everything is."""
        ch = await self.claim(sock, name, key)
        if ch is None:
            return
        if ch.gamer is not None:
            await ch.gamer.close(4000, "replaced by a newer game")
        ch.gamer = sock
        self.log("game      %s" % name)
        ch.tell_viewers()                    # (the status line's "game")
        read = deque()                       # when its last messages were read, for GAME_A_SECOND
        try:
            while ch.gamer is sock:
                msg = await sock.recv()
                if not isinstance(msg, str) or ch.gamer is not sock:
                    continue
                now = time.monotonic()
                while read and now - read[0] >= 1.0:
                    read.popleft()
                if len(read) >= GAME_A_SECOND:
                    continue
                read.append(now)
                if len(msg) > GAME_MESSAGE:
                    continue
                if self.delay > 0:
                    ch.held.append((now + self.delay, GameText(msg)))
                    self.due(ch.held[-1][0])
                else:
                    ch.take_game(msg)
        finally:
            if ch.gamer is sock:
                ch.gamer = None
                ch.drop_where()              # (following: only while the plugin's connected)
                ch.drop_activity()           # (what they're doing, the same)
                self.log("no game   %s" % name)
                ch.tell_viewers()

    async def channel_list(self, writer):
        """The open portals (11/05/2026, Jace: "a page ... that shows all active connected public portals"): every channel
        that's set up and has no watch code, live ones first, with whether each is live. Anyone may read it (the
        public side too); a channel with a watch code is never listed.
        "pending" (11/08/2026, for the keys beside an inviter on outside.wiki's globe): the invites not taken up yet,
        counted under each inviter, [{"by", "n"}] sorted by "by" (the Worker's openPortals, the same). Never the
        invited names, and only under an inviter that's on the list itself, so nothing's said of a hidden outstation.
        "where" (11/08/2026, following; game_where): the square the plugin last said (and its world, with "and my world"
        on), only while the channel's live and listed, and only if it came within where_fresh_s; else null.
        "activity" (11/08/2026; game_activity): what they were doing, the same way. Nowhere else."""
        rows = [{"channel": ch.name, "live": ch.sender is not None, "open": ch.sender is not None and ch.state == "open",
                 "static": ch.sender is not None and ch.state == "static", "viewers": len(ch.viewers), "title": ch.title,
                 "doing": ch.doing(), "by": ch.by, "game": ch.gamer is not None,
                 "where": dict(ch.where) if ch.where is not None and ch.sender is not None
                          and time.monotonic() - ch.where_at <= self.where_fresh_s else None,
                 "activity": dict(ch.activity) if ch.activity is not None and ch.sender is not None
                             and time.monotonic() - ch.activity_at <= self.where_fresh_s else None}
                for ch in self.channels.values() if ch.listed()]
        rows.sort(key=lambda r: (not r["live"], r["channel"]))
        shown, counts = {r["channel"] for r in rows}, {}
        for ch in self.channels.values():
            if ch.key is not None and ch.pending and ch.by is not None and ch.by in shown:
                counts[ch.by] = counts.get(ch.by, 0) + 1
        pending = [{"by": by, "n": n} for by, n in sorted(counts.items())]
        body = json.dumps({"channels": rows, "pending": pending}).encode()
        writer.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: %d\r\n"
                      "Access-Control-Allow-Origin: *\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n" % len(body)).encode() + body)
        try:
            await writer.drain()
        finally:
            writer.close()

    async def game_read(self, writer, method, name, q):
        """GET /game/<channel>?code=<watch code> (11/07/2026): the game state kept, for a page while the outstation's
        dark, and whether the plugin's connected now. It needs the watch code when the channel has one, as watching
        does. Anyone may read it (the public side too: it's what viewers see anyway)."""
        async def answer(status, body=None):
            data = json.dumps(body).encode() if body is not None else b""
            kind = "Content-Type: application/json\r\nCache-Control: no-cache\r\n" if body is not None else ""
            writer.write(("HTTP/1.1 %s\r\nAccess-Control-Allow-Origin: *\r\n%sContent-Length: %d\r\nConnection: close\r\n\r\n"
                          % (status, kind, len(data))).encode() + data)
            try:
                await writer.drain()
            finally:
                writer.close()
        if method != "GET":
            return await answer("405 Method Not Allowed")
        if not re.fullmatch(r"[A-Za-z0-9_.~-]{1,64}", name):
            return await answer("400 Bad Request", {"error": "which outstation?"})
        ch = self.channels.get(name)
        if ch is not None and ch.code and q.get("code", "") != ch.code:
            return await answer("403 Forbidden", {"error": "wrong watch code"})
        if ch is None or not ch.game.kept:
            return await answer("404 Not Found", {"error": "no game state for that outstation"})
        await answer("200 OK", {"game": ch.game.kept, "at": ch.game.at, "connected": ch.gamer is not None})

    async def aether_socket(self, sock, q):
        """The aether (11/07/2026; Aether): a visitor (?name=&device=) or an outstation (?channel=&key=: its version and
        its key as its desk's, so a key claims a channel nobody has, as a desk's does) into the one room, and its lines
        to everyone in it (Aether.take). Refused: 4002 a name or device the aether can't take, 4033 banned, 4009 a
        visitor's name that's a claimed channel's (case and spaces aside); 4001, 4003 and 4026 as a desk's."""
        a = self.aether
        if "channel" in q:
            name = q["channel"]
            if too_old(q.get("v"), self.min_client):
                await sock.close(4026, "this outstation is too old for the relay: %s or newer is needed" % self.min_client)
                return
            if await self.claim(sock, name, q.get("key", "")) is None:
                return
            me = {"from": "outstation", "name": name, "who": "channel:" + name}
        else:
            name, device = aether_name(q.get("name")), q.get("device", "")
            if not name:
                await sock.close(4002, "a name in the aether is 1 to 20 letters, digits, spaces, _ - or .")
                return
            if not AETHER_DEVICE.fullmatch(device):
                await sock.close(4002, "a device id is 8 to 64 characters")
                return
            me = {"from": "visitor", "name": name, "who": "device:" + device}
        if me["who"] in a.bans:
            await sock.close(4033, "banned from the aether")
            return
        form = channel_form(me["name"])
        if me["from"] == "visitor" and any(ch.key is not None and ch.name.lower() == form for ch in self.channels.values()):
            await sock.close(4009, "that name belongs to an outstation")
            return
        sock.aether = me
        a.socks.add(sock)
        self.log("aether    %s  (%d here)" % (me["name"], a.count()))
        sock.send_nowait(json.dumps({"aether_so_far": [a.public(l) for l in a.lines]}))
        a.here_changed(sock)
        try:
            while True:
                msg = await sock.recv()
                if isinstance(msg, str) and len(msg) <= AETHER_MESSAGE:
                    a.take(sock, msg)
        finally:
            if sock in a.socks:
                a.socks.discard(sock)
                a.here_changed()

    async def aether_read(self, writer, method):
        """GET /aether (11/07/2026): the room's last AETHER_READ lines and how many are here, for a page before it
        joins. Anyone may read it (the public side too); cached a few seconds."""
        if method == "GET":
            body = json.dumps(self.aether.read()).encode()
            head = ("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nCache-Control: public, max-age=5\r\n"
                    "Access-Control-Allow-Origin: *\r\nContent-Length: %d\r\nConnection: close\r\n\r\n" % len(body))
        else:
            body, head = b"", "HTTP/1.1 405 Method Not Allowed\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
        writer.write(head.encode() + body)
        try:
            await writer.drain()
        finally:
            writer.close()

    async def array_read(self, writer, method, q):
        """GET /array/events?since=<cursor> (11/08/2026; ArrayFeed): what's happening across the open outstations, for
        outside.wiki's front page: {"events": [...], "next"}. Anyone may read it (any origin, the public side too), and
        it's never cached. since: a cursor, as an earlier read's next gave it."""
        since, status, body = q.get("since"), "200 OK", None
        if method != "GET":
            status = "405 Method Not Allowed"
        elif since is not None and not (ARRAY_CURSOR.fullmatch(since) and int(since) <= SAFE_INT):
            status, body = "400 Bad Request", {"error": "since is a cursor: an earlier read's next"}
        else:
            body = self.array.read(int(since) if since is not None else None)
        data = json.dumps(body).encode() if body is not None else b""
        kind = "Content-Type: application/json\r\n" if body is not None else ""
        writer.write(("HTTP/1.1 %s\r\n%sAccess-Control-Allow-Origin: *\r\nCache-Control: no-cache\r\n"
                      "Content-Length: %d\r\nConnection: close\r\n\r\n" % (status, kind, len(data))).encode() + data)
        try:
            await writer.drain()
        finally:
            writer.close()

    async def stars_read(self, writer, method):
        """GET /stars (11/08/2026; StarList): the shooting stars the plugins have found, and who found each, for
        outside.wiki's globe: {"stars": [...]}. Anyone may read it (any origin, the public side too), never cached."""
        status, body = ("200 OK", self.stars.read()) if method == "GET" else ("405 Method Not Allowed", None)
        data = json.dumps(body).encode() if body is not None else b""
        kind = "Content-Type: application/json\r\n" if body is not None else ""
        writer.write(("HTTP/1.1 %s\r\n%sAccess-Control-Allow-Origin: *\r\nCache-Control: no-cache\r\n"
                      "Content-Length: %d\r\nConnection: close\r\n\r\n" % (status, kind, len(data))).encode() + data)
        try:
            await writer.drain()
        finally:
            writer.close()

    async def almanac_read(self, writer, method):
        """GET /almanac (11/09/2026; almanac.py, docs/protocol.md "The almanac"): Jagex's news, the world list and the
        market, as last fetched on the relay's own schedule. Anyone may read it (any origin, the public side too); a
        browser may keep it a minute. A read never asks a source, except the first when nothing's ever been tried (a
        cold start: one refresh, shared by every read that comes while it runs)."""
        if method != "GET":
            status, body = "405 Method Not Allowed", None
        else:
            if self.almanac_on and self.almanac.cold():
                await self.almanac.refresh()
            status, body = "200 OK", self.almanac.read()
        data = json.dumps(body).encode() if body is not None else b""
        kind = "Content-Type: application/json\r\n" if body is not None else ""
        cache = "public, max-age=60" if body is not None else "no-cache"
        writer.write(("HTTP/1.1 %s\r\n%sAccess-Control-Allow-Origin: *\r\nCache-Control: %s\r\n"
                      "Content-Length: %d\r\nConnection: close\r\n\r\n" % (status, kind, cache, len(data))).encode() + data)
        try:
            await writer.drain()
        finally:
            writer.close()

    async def almanac_keeper(self, writer, method, headers):
        """The almanac's keeper (the Worker's, the same), with --admin-token: GET /admin/almanac, each source's schedule
        and last error; POST /admin/almanac, every source asked now, whatever its schedule (then the same)."""
        auth = headers.get("authorization", "").encode("latin-1")
        if len(self.admin_token) < 16 or not hmac.compare_digest(auth, ("Bearer " + self.admin_token).encode("utf-8")):
            status, body = "403 Forbidden", None
        elif method == "POST":
            if self.almanac_on:
                await self.almanac.refresh(force=True)
            status, body = "200 OK", self.almanac.status()
        elif method == "GET":
            status, body = "200 OK", self.almanac.status()
        else:
            status, body = "405 Method Not Allowed", None
        data = json.dumps(body).encode() if body is not None else b""
        kind = "Content-Type: application/json\r\n" if body is not None else ""
        writer.write(("HTTP/1.1 %s\r\n%sContent-Length: %d\r\nConnection: close\r\n\r\n" % (status, kind, len(data))).encode()
                     + data)
        try:
            await writer.drain()
        finally:
            writer.close()

    async def almanac_loop(self):
        """The almanac's schedule (the Worker's Cron Trigger, every 5 minutes, the same): each minute, whatever source
        is due is asked (almanac.py's NEWS_EVERY, WORLDS_EVERY, PRICES_EVERY, MAPPING_EVERY, and its backoff)."""
        while True:
            try:
                await self.almanac.refresh()
            except Exception as e:  # noqa: BLE001 (never the loop's end)
                self.log("the almanac: %s" % e)
            await asyncio.sleep(60)

    async def array_keeper(self, reader, writer, method, headers):
        """POST /admin/array {"channel", "events": [{"kind", ..., "at"}]} (11/10/2026; the Worker's, the same), with
        --admin-token: the keeper puts a real event the Array's feed lost back in its place (array_backfill,
        ArrayFeed.backfill), for a channel claimed and on the open outstations' list (only such a channel's reports ever
        make events): 200 {"events": [as kept]}; 400 {"error"}; 404 for a channel nobody's claimed; 409 for a hidden one."""
        async def answer(status, body=None, plain=None):
            data = json.dumps(body).encode() if body is not None else (plain or b"")
            kind = "Content-Type: application/json\r\n" if body is not None else ""
            writer.write(("HTTP/1.1 %s\r\n%sContent-Length: %d\r\nConnection: close\r\n\r\n" % (status, kind, len(data))).encode()
                         + data)
            try:
                await writer.drain()
            finally:
                writer.close()
        auth = headers.get("authorization", "").encode("latin-1")
        if len(self.admin_token) < 16 or not hmac.compare_digest(auth, ("Bearer " + self.admin_token).encode("utf-8")):
            return await answer("403 Forbidden", plain=b"no")
        if method != "POST":
            return await answer("405 Method Not Allowed")
        try:
            if headers.get("transfer-encoding", "").lower() == "chunked":
                raw = await read_chunked(reader)
            else:
                n = int(headers.get("content-length", "0") or 0)
                if n > 16 * 1024:
                    return await answer("413 Payload Too Large", {"error": "16 KB at most"})
                raw = await reader.readexactly(n)
        except (ValueError, asyncio.IncompleteReadError):
            return await answer("400 Bad Request")
        if len(raw) > 16 * 1024:
            return await answer("413 Payload Too Large", {"error": "16 KB at most"})
        try:
            body = json.loads(raw)
        except ValueError:
            body = None
        channel = body.get("channel") if isinstance(body, dict) else None
        if not isinstance(channel, str) or not CHANNEL_NAME.fullmatch(channel):
            return await answer("400 Bad Request", {"error": "channel: which outstation"})
        events, why = array_backfill(body)
        if why:
            return await answer("400 Bad Request", {"error": why})
        ch = self.channels.get(channel)
        if ch is None or ch.key is None:
            return await answer("404 Not Found", {"error": "no outstation called that here"})
        if not ch.listed():
            return await answer("409 Conflict", {"error": "that outstation isn't on the open list: its events never go on the feed"})
        kept, why = self.array.backfill(channel, events)
        if why:
            return await answer("400 Bad Request", {"error": why})
        self.log("array     the keeper put back %d on %s" % (len(kept), channel))
        return await answer("200 OK", {"events": kept})

    async def aether_keeper(self, reader, writer, method, headers, path):
        """The aether's keeper (11/07/2026; the Worker's, the same), with --admin-token (Authorization: Bearer <it>):
            GET  /admin/aether                  every line kept, with who wrote it, and the bans
            POST /admin/aether/unsay  {"id"}    a line taken back off everyone's page
            POST /admin/aether/ban    {"id"}    whoever wrote it banned: their lines taken back, their sockets closed
            POST /admin/aether/unban  {"who"}   let back in
        Without a token (16 characters at least) all of them are refused; the public side refuses them whatever."""
        async def answer(status, body=None, plain=None):
            data = json.dumps(body).encode() if body is not None else (plain or b"")
            kind = "Content-Type: application/json\r\n" if body is not None else ""
            writer.write(("HTTP/1.1 %s\r\n%sContent-Length: %d\r\nConnection: close\r\n\r\n" % (status, kind, len(data))).encode()
                         + data)
            try:
                await writer.drain()
            finally:
                writer.close()
        auth = headers.get("authorization", "").encode("latin-1")
        if len(self.admin_token) < 16 or not hmac.compare_digest(auth, ("Bearer " + self.admin_token).encode("utf-8")):
            return await answer("403 Forbidden", plain=b"no")
        a = self.aether
        if len(path) == 2:
            if method != "GET":
                return await answer("405 Method Not Allowed")
            return await answer("200 OK", {"lines": [dict(a.public(l), who=l["who"]) for l in a.lines],
                                           "bans": list(a.bans.values())})
        if len(path) != 3 or path[2] not in ("unsay", "ban", "unban"):
            return await answer("404 Not Found")
        if method != "POST":
            return await answer("405 Method Not Allowed")
        try:
            if headers.get("transfer-encoding", "").lower() == "chunked":
                raw = await read_chunked(reader)
            else:
                n = int(headers.get("content-length", "0") or 0)
                if n > 4096:
                    return await answer("413 Payload Too Large")
                raw = await reader.readexactly(n)
        except (ValueError, asyncio.IncompleteReadError):
            return await answer("400 Bad Request")
        if len(raw) > 4096:
            return await answer("413 Payload Too Large")
        try:
            body = json.loads(raw)
        except ValueError:
            body = None
        body = body if isinstance(body, dict) else {}
        if path[2] == "unban":
            if not isinstance(body.get("who"), str) or a.bans.pop(body["who"], None) is None:
                return await answer("404 Not Found", {"error": "nobody's banned by that"})
            return await answer("200 OK", {"bans": list(a.bans.values())})
        i = body.get("id")
        if isinstance(i, float) and i.is_integer():
            i = int(i)
        line = next((l for l in a.lines if l["id"] == i), None) if isinstance(i, int) and not isinstance(i, bool) else None
        if line is None:
            return await answer("404 Not Found", {"error": "no such line in the aether"})
        if path[2] == "unsay":
            a.unsay([line["id"]])
            return await answer("200 OK", {"unsaid": [line["id"]]})
        gone = a.ban(line)
        self.log("aether    banned %s" % line["who"])
        return await answer("200 OK", {"banned": line["who"], "unsaid": gone})

    async def invites(self, reader, writer, method, headers, name, q):
        """Invites (11/07/2026; docs/protocol.md "Invites"; the Worker's channel.ts invites, the same): an outstation
        hands out channels of its own, INVITES_START to begin with and one more every INVITE_EVERY_S on air.
            GET  /invites/<channel>?key=<key>          {"left", "earned", "used", "hours", "next_in_s"}
            POST /invites/<channel>  {"key", "player"}  {"channel", "key", "code", "left"}: a new channel for that
                                                        player, claimed with a new key, which says whose invite it was
        Here a channel is "handed out" once it's claimed (its first transmitter, or an invite). Nothing is awaited
        between the checks and the new channel's key, so two posts at once can't both take the last invite."""
        async def answer(status, body=None):
            data = json.dumps(body).encode() if body is not None else b""
            kind = "Content-Type: application/json\r\nCache-Control: no-cache\r\n" if body is not None else ""
            writer.write(("HTTP/1.1 %s\r\n%sContent-Length: %d\r\nConnection: close\r\n\r\n"
                          % (status, kind, len(data))).encode() + data)
            try:
                await writer.drain()
            finally:
                writer.close()
        if method not in ("GET", "POST"):
            return await answer("405 Method Not Allowed")
        if not re.fullmatch(r"[A-Za-z0-9_.~-]{1,64}", name):
            return await answer("400 Bad Request", {"error": "which outstation?"})
        body = {}
        if method == "POST":
            try:
                if headers.get("transfer-encoding", "").lower() == "chunked":
                    raw = await read_chunked(reader)
                else:
                    n = int(headers.get("content-length", "0") or 0)
                    if n > 4096:
                        return await answer("413 Payload Too Large", {"error": "an invite is a key and a name"})
                    raw = await reader.readexactly(n)
            except (ValueError, asyncio.IncompleteReadError):
                return await answer("400 Bad Request")
            if len(raw) > 4096:
                return await answer("413 Payload Too Large", {"error": "an invite is a key and a name"})
            try:
                body = json.loads(raw)
            except ValueError:
                body = {}
            if not isinstance(body, dict):
                body = {}
        ch = self.channels.get(name)
        no = key_refusal(ch, q.get("key", "") if method == "GET" else body.get("key"))
        if no:
            return await answer(no[0], {"error": no[1]})
        if method == "GET":
            return await answer("200 OK", invite_numbers(ch.seconds_in_all(), ch.invited))
        now = time.monotonic()
        ch.invite_posts = [t for t in ch.invite_posts if now - t < 3600]
        if len(ch.invite_posts) >= INVITES_AN_HOUR:
            return await answer("429 Too Many Requests", {"error": "%d invites an hour at most: try again later" % INVITES_AN_HOUR})
        ch.invite_posts.append(now)
        player = body.get("player")
        if not isinstance(player, str) or not PLAYER.fullmatch(player):
            return await answer("400 Bad Request", {"error": "a player's name: 1 to 12 letters or numbers, lower case"})
        if player == name.lower():
            return await answer("400 Bad Request", {"error": "that's your own outstation"})
        if invite_numbers(ch.seconds_in_all(), ch.invited)["left"] <= 0:
            return await answer("409 Conflict", {"error": "no invites left: one more every %d hours on air" % (INVITE_EVERY_S // 3600)})
        other = self.channels.get(player)
        if other is not None and other.key is not None:
            return await answer("409 Conflict", {"error": "that name has an outstation already"})
        new_key = base64.urlsafe_b64encode(os.urandom(18)).decode().rstrip("=")   # (the Worker's mint: 18 random bytes)
        if other is None:
            other = self.channels[player] = Channel(player, new_key, self.array, self.stars)
        else:
            other.key = new_key       # (a channel only viewers were waiting on: now it's handed out, and they hear so)
            other.tell_viewers()
        other.by = name
        other.pending = True          # (not taken up till its key's used: counted under this one, never named)
        ch.invited.append(player)
        self.log("invite    %s  -> %s" % (name, player))
        code = attach_code("ws://" + (headers.get("host") or "127.0.0.1"), player, new_key)
        return await answer("200 OK", {"channel": player, "key": new_key, "code": code,
                                       "left": invite_numbers(ch.seconds_in_all(), ch.invited)["left"]})

    async def settling_gone(self, reader, writer, headers):
        """Settling went on 11/10/2026 (the module's head): anything on /settle/<channel> or /home/<channel> is a 410
        with SETTLING_GONE, which an app built while it was here shows as it is, and a page on another origin can read
        (the Worker's, the same). A body is read (2 KB at most, so the answer isn't lost to a reset) and thrown away."""
        try:
            await read_body(reader, headers, 2048)
        except (ValueError, asyncio.IncompleteReadError):
            pass
        data = json.dumps({"error": SETTLING_GONE}).encode()
        writer.write(("HTTP/1.1 410 Gone\r\nContent-Type: application/json\r\nAccess-Control-Allow-Origin: *\r\n"
                      "Cache-Control: no-store\r\nContent-Length: %d\r\nConnection: close\r\n\r\n" % len(data)).encode() + data)
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
                ch.key_used()
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
                ch.key_used()
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
        ch.key_used()
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

    async def record(self, reader, writer, method, headers, path, q):
        """Your year (11/10/2026; docs/record.md; the Worker's index.ts record and record.ts, the same):
            POST   /record/<channel>/token?key=<key>  {"read", "write"}   the record key's tokens, their SHA-256 kept
            PUT    /record/<channel>/<id>?key=<key>   a sealed chunk;  DELETE /record/<channel>/<id>?key=  one gone
            DELETE /record/<channel>?key=<key>        everything: chunks, tokens, the public summary
            GET    /record/<channel>[?data=1&after=]  Authorization: Bearer <read token>
            PUT, DELETE /year/<channel>  ?key= or Authorization: Bearer <write token>;   GET /year/<channel>  anyone's"""
        cors = "Access-Control-Allow-Origin: *\r\n"

        async def answer(status, body=None, extra=""):
            data = (body if isinstance(body, str) else json.dumps(body)).encode() if body is not None else b""
            kind = "Content-Type: application/json\r\n" if body is not None else ""
            writer.write(("HTTP/1.1 %s\r\n%s%sCache-Control: no-store\r\n%sContent-Length: %d\r\nConnection: close\r\n\r\n"
                          % (status, cors, kind, extra, len(data))).encode() + data)
            try:
                await writer.drain()
            finally:
                writer.close()
        if method == "OPTIONS":
            return await answer("204 No Content", None, "Access-Control-Allow-Methods: GET, PUT, POST, DELETE\r\n"
                                "Access-Control-Allow-Headers: authorization, content-type\r\nAccess-Control-Max-Age: 86400\r\n")
        kind, name = path[0], path[1]
        if not CHANNEL_NAME.fullmatch(name):
            return await answer("400 Bad Request", {"error": "which outstation?"})
        if self.records.folder(name) is None:
            return await answer("404 Not Found", {"error": "this relay doesn't keep records"})
        third = path[2] if len(path) == 3 else ""
        if kind == "record" and third == "token" and method == "POST":
            what, most = "token", 4096
        elif kind == "record" and third and method in ("PUT", "DELETE"):
            if not CHUNK_ID.fullmatch(third):
                return await answer("400 Bad Request", {"error": "a chunk's id: 22 base64url characters"})
            what, most = "chunk", RECORD_CHUNK
        elif kind == "record" and not third and method in ("GET", "DELETE"):
            what, most = ("list" if method == "GET" else "all"), 0
        elif kind == "year" and method in ("GET", "PUT", "DELETE"):
            what, most = "year", YEAR_MOST
        else:
            return await answer("405 Method Not Allowed", {"error": "no such thing here"})
        body = b""
        if method in ("PUT", "POST"):
            try:
                body = await read_body(reader, headers, most)
            except (ValueError, asyncio.IncompleteReadError):
                return await answer("400 Bad Request")
            if body is None:
                return await answer("413 Payload Too Large", {"error": "a chunk is %d KB at most" % (RECORD_CHUNK // 1024)
                                                              if what == "chunk" else "a year is %d KB at most" % (YEAR_MOST // 1024)
                                                              if what == "year" else "the tokens: read and write"})
        station = False
        if "key" in q:
            ch = self.channels.get(name)
            station = ch is not None and ch.key is not None and len(q["key"]) >= 8 and hmac.compare_digest(q["key"], ch.key)
            if not station:
                return await answer("403 Forbidden", {"error": "wrong key for this channel"})
            ch.key_used()
        m = re.match(r"^Bearer (.+)$", headers.get("authorization", ""))
        token = m.group(1).strip() if m else ""
        now = int(time.time() * 1000)
        rec = self.records
        if what == "token":
            if not station:
                return await answer("403 Forbidden", {"error": "wrong key for this channel"})
            try:
                b = json.loads(body)
            except ValueError:
                b = None
            return await answer(*rec.register(name, b))
        if what == "chunk":
            if not station:
                return await answer("403 Forbidden", {"error": "wrong key for this channel"})
            return await answer(*(rec.put(name, third, body, now) if method == "PUT" else rec.delete(name, third, now)))
        if what == "all":
            if not station:
                return await answer("403 Forbidden", {"error": "wrong key for this channel"})
            return await answer(*rec.delete_all(name))
        if what == "list":
            if not rec.token_ok(name, token, "read"):
                return await answer("403 Forbidden", {"error": "a read token: the record key's, registered by the app"})
            # (a blank after= is none, as on the Worker)
            return await answer("200 OK", rec.listing(name, q.get("data") == "1", q.get("after", ""), now))
        if method == "GET":
            got = rec.year_get(name)
            return await answer("200 OK", got) if got is not None else \
                await answer("404 Not Found", {"error": "no year posted for that outstation"})
        if not station and not rec.token_ok(name, token, "write"):
            return await answer("403 Forbidden", {"error": "the station key, or the record key's write token"})
        if method == "DELETE":
            rec.year_delete(name)
            return await answer("204 No Content")
        try:
            y = json.loads(body, parse_constant=no_constant)
        except (ValueError, RecursionError):
            return await answer("400 Bad Request", {"error": "a year is JSON"})
        year, why = year_summary(y, now)
        if why is not None:
            return await answer("400 Bad Request", {"error": why})
        return await answer("200 OK", rec.year_put(name, json.dumps(year), now))

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
            ch = self.channels[name] = Channel(name, key, self.array, self.stars)
        elif ch.key is None:
            ch.key = key
        elif ch.key != key:
            return await answer("403 Forbidden")
        ch.key_used()
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
                self.due(ch.held[-1][0])
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
                # (acked over about the last 5 s: pages acknowledge every 1-3 s since 11/07/2026, so a second at a time
                # read 0 between acks)
                ch.acked_seen.append((ch.acked, now))
                while len(ch.acked_seen) > 2 and now - ch.acked_seen[1][1] >= ACKED_OVER:
                    ch.acked_seen.pop(0)
                had, had_at = ch.acked_seen[0]
                if ch.desks:
                    # acked: what viewers' pages say they've had, a second at a time (the client's live view)
                    line = json.dumps({"upload": {"bps": int((ch.out - was) * 8 / max(0.001, now - at)),
                                                  "viewers": len(ch.viewers),
                                                  "acked": int((ch.acked - had) * 8 / max(0.001, now - had_at))}})
                    for d in list(ch.desks):
                        d.send_nowait(line)

    def due(self, t):
        """Something held till t (a frame or report with a delay, sound): the holder woken if that's sooner than it meant
        to look."""
        if t < self.next_wake and self.wake is not None:
            self.wake.set()

    async def release_held(self):
        """What's held let go when it's due, and polls and drop guesses ended and taken down. (11/07/2026: it looked every
        10 ms whatever was held, a hundred wake-ups a second with nobody on; now it sleeps till the next thing's due,
        HELD_LOOK at most, and is woken sooner by due().)"""
        self.wake = asyncio.Event()
        while True:
            now = time.monotonic()
            soonest = now + HELD_LOOK
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
                for q in (ch.held, ch.sound_held):
                    if q:
                        soonest = min(soonest, q[0][0])
                if ch.poll:
                    soonest = min(soonest, ch.poll["ends"] if ch.poll["open"] else ch.poll["closed"] + POLL_KEPT)
            self.next_wake = soonest
            self.wake.clear()
            try:
                await asyncio.wait_for(self.wake.wait(), max(0.0, soonest - time.monotonic()))
            except asyncio.TimeoutError:
                pass


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
                settings=None, min_client="", clips_dir=None, admin_token="",
                where_fresh_s=WHERE_FRESH_S, almanac="off", test_forget=False, records_dir=None,
                record_chunks=RECORD_CHUNKS):
    """port: everything (keep it on this PC). public_port: the page and watching only,
    for Tailscale Funnel (or anything else) to hand to the internet. admin_token: the keeper's (the aether's routes).
    almanac: "off" (the default here: nothing fetched), "on" (the real hosts, main()'s default) or an address to fetch
    everything under instead (tests)."""
    relay = Relay(delay, log, forward, max_viewers, min_client, clips_dir, admin_token, where_fresh_s,
                  almanac, test_forget, records_dir, record_chunks)
    server = await asyncio.start_server(relay.handle, host, port)
    public = await asyncio.start_server(relay.handle_public, host, public_port) if public_port else None
    holder = asyncio.ensure_future(relay.release_held())
    uploads = asyncio.ensure_future(relay.tell_upload())
    watcher = asyncio.ensure_future(relay.watch_settings(settings)) if settings else None
    almanac_task = asyncio.ensure_future(relay.almanac_loop()) if relay.almanac_on else None
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
        if almanac_task:
            almanac_task.cancel()


def main(argv=None):
    ap = argparse.ArgumentParser(description="the outstation's local relay")
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
    ap.add_argument("--records", default=DEFAULT_RECORDS,
                    help="where players' encrypted records and public years are kept (\"\" keeps none)")
    ap.add_argument("--record-chunks", type=int, default=RECORD_CHUNKS,
                    help="a channel's record chunks at most (%d; fewer for tests)" % RECORD_CHUNKS)
    ap.add_argument("--min-client", default="",
                    help="the oldest outstation client let in (e.g. 0.2.0); older ones are told to update")
    ap.add_argument("--admin-token", default="",
                    help="the keeper's token (16 characters at least) for the aether's keeper routes; none refuses them")
    ap.add_argument("--where-fresh-seconds", type=float, default=WHERE_FRESH_S,
                    help="how long a player's square shows on the open outstations' list after it came (%d; shorter "
                         "for tests)" % WHERE_FRESH_S)
    ap.add_argument("--almanac", default="on",
                    help="the almanac's sources (GET /almanac): on (Jagex's news and world list and the Wiki's prices, "
                         "each on its own schedule), off, or an address to fetch them all under instead (tests)")
    ap.add_argument("--test-forget", action="store_true",
                    help="answer POST /test/forget, as the Worker does for its tests (nothing is forgotten here)")
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
                          clips_dir=a.clips or None, admin_token=a.admin_token,
                          where_fresh_s=a.where_fresh_seconds, almanac=a.almanac, test_forget=a.test_forget,
                          records_dir=a.records or None, record_chunks=a.record_chunks))
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    sys.exit(main())
