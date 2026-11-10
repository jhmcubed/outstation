# Your year: the record (design, 11/10/2026)

Each player can see a year of their own travels and activity: where they spent time (heat), the paths between places,
what they did most, and a replay. Jace's decisions (11/10/2026):

- Each PC keeps its own record. The relay keeps a copy too, so the records of several PCs merge, and **the relay's copy is
  encrypted with a key only the player's own computers hold**: the relay can't read it, and nor can ⊂λ,ｍ⊙.
- A player who wants to may post a **public summary** on their Outside page, choosing each part of it; it's stored
  unencrypted only by that choice, and taken down whenever they like.
- A launch feature, built to last.

This file is the format and the routes, for the app (client/src/record.rs, recordfmt.rs), both relays
(relay/src/record.ts, relay-py/relay.py) and the page that draws the year (another repository). docs/protocol.md
has the routes in its own words; SAFETY.md says what's kept and by whom.

## What the record holds

What the relay already sees from the Outside plugin, and, only with the plugin's "Record my exact path for my year" on
(11/10/2026, Jace: the record keeps the exact tile, privately), the exact path (below, "The exact path"):

- following's `where`: the middle of the 128-tile square the player was in two minutes before, and the world with "and
  my world" on (game.ts, gameWhere), at most one every 50 s; or null when it's cleared;
- `activity`: one coarse label (game.ts, gameActivity), the same way;
- the player's own Array events: a kill with its kill count, a level, a pet, a drop worth 1,000,000 or more, a collection
  log slot, a clue (array.ts, arrayEvents), from the field reports their outstation sends (and Dink's posts);
- when they were live (transmitting) or dark;
- with "Record my exact path for my year" on: `here`, the exact tile they're on, at once, at most one every few seconds
  while they move and once when they stop, which the relay passes to their own desk and never keeps.

Nothing from viewers, the chat, the picture or the sound; no tile finer than the square unless the player switched the
exact path on; nothing from the Wilderness or anywhere else the plugin holds back (the plugin never sends it, so the
relay never has it).

The record is made **only while the app is open**: it's the app that writes it, from what the relay tells it. The relay
never keeps a plain copy for the app to collect later.

## Getting the data to the app: the desk's `{"mine"}` lines

Today `where` and `activity` go only into GET /channels' rows (while live and listed), never to a socket. Now the relay
also passes the owner's own `where`, `activity` and events to **the channel's desk sockets only**:

    ws(s)://<relay>/desk/<channel>?key=<station key>&name=<name>&v=<version>

the chat window's socket, which only the station key opens and which the app keeps open whenever it runs (live or
dark). Each is one text line:

    {"mine": {"where": {"x": 3264, "y": 3264, "world": 302}, "at": 1794268812345}}
    {"mine": {"where": null, "at": 1794067512345}}
    {"mine": {"activity": {"kind": "skilling", "skill": "Mining"}, "at": 1794268812345}}
    {"mine": {"activity": null, "at": 1794067512345}}
    {"mine": {"event": {"kind": "kill", "boss": "Vorkath", "kc": 412}, "at": 1794067301000}}
    {"mine": {"here": {"x": 3222, "y": 3218, "plane": 0, "world": 302}, "at": 1794268814012}}
    {"mine": {"here": null, "at": 1794268990012}}

- `at` is when the relay took it (ms since 1970, the relay's clock): the same number to every desk of the channel, so
  two PCs open at once record identical lines (merging drops the copy).
- `where`: sent each time the relay takes one (game.ts's WHERE_EVERY_MS, one every 50 s at most), and `null` when it's
  dropped (the plugin clears it, logs out, holds for the player's safety, or disconnects). `activity`: the same.
- `event`: one line per event of a field report (array.ts's arrayEvents: its kinds and fields exactly), from the
  transmit socket's `{"report"}` or a Dink post, live or dark. (The Array's public feed still takes them only while
  live and listed; this is the owner's own.)
- `here` (11/10/2026, the exact path): passed on at once each time the relay takes one (game.ts's HERE_EVERY_MS, one
  every 2 s at most), and `null` when the plugin clears it, logs out, holds for the player's safety or disconnects (only
  if a tile was passed on). The relay never keeps it, not even in memory: a desk that connects later gets none.
- A desk that connects gets the `where` and the `activity` held now, if any, each with the `at` it came with.
- Sent whatever the channel's state: live or dark, with a watch code or not, an invite or not. Never to a viewer
  (watch), a looker, the transmitter, the sound, the game socket, GET /game or the aether. GET /channels is unchanged.

Live and dark come from what the desk already gets: the status line's `live` (`{"relay": {"live": …}}`).

## The local record

    %APPDATA%\outstation\record\2026-11.jsonl     one file per month (UTC), newline JSON, appended only
    %APPDATA%\outstation\record\device            this PC's device id (16 hex characters, random, made once)
    %APPDATA%\outstation\record\sent.json         what's been uploaded (the sync's own bookkeeping)

(With `--settings <file>`, the folder is `record` beside that file.) Recording is on to begin with (settings ▸ your year,
"record my year on this PC"): nothing leaves the PC for it. The relay's copy is off until "keep an encrypted copy on
the relay" is switched on, which makes the record key. Each line is one JSON object with `t` (ms since
1970) and one or more of:

| field      | value                                                         | written                                           |
| ---------- | ------------------------------------------------------------- | ------------------------------------------------- |
| `where`    | `{"x", "y"}` or `{"x", "y", "world"}` (gameWhere's), or null  | when it changes; `t` is the relay's `at`          |
| `activity` | an activity label (gameActivity's), or null                   | when it changes; `t` is the relay's `at`          |
| `event`    | `{"kind", …}` (arrayEvents')                                  | each one; `t` is the relay's `at`                 |
| `live`     | true or false                                                 | when the relay's status says it changed; `t` local |
| `here`     | `{"x", "y", "plane", "world"}` (gameHere's), or null          | as it comes, at most one a 3 s; `t` the relay's `at` |

**The heartbeat:** while the desk is up and a `where`, an `activity` or a `here` is held (not null), a line restating
whichever are held, `{"t", "where", "activity", "here"}`, is written whenever a minute has passed with no `where`/`activity` line: at
most one a minute, so a long stay in one place counts as long. `t` is this PC's clock.

**Reading it:** a `where` (or `activity`, or `here`) holds from its line until the next line that states it, but never more than
**3 minutes** past its last statement (`HOLD_MS` = 180,000): a gap longer than that (the app closed, the PC asleep, the
relay out of reach) is a gap, not a stay. A field missing from a line says nothing about it. Places and labels are what
they were about **two minutes before `t`** (the plugin sends them two minutes behind); a page may shift them back by
120 s, or not. A `here` is the tile at `t` itself, never behind.

Example:

    {"t": 1794268800000, "live": true}
    {"t": 1794268812345, "where": {"x": 3264, "y": 3264, "world": 302}}
    {"t": 1794268812345, "activity": {"kind": "skilling", "skill": "Mining"}}
    {"t": 1794268872400, "where": {"x": 3264, "y": 3264, "world": 302}, "activity": {"kind": "skilling", "skill": "Mining"}}
    {"t": 1794067301000, "event": {"kind": "level", "skill": "Mining", "level": 72}}

The local files are **plain JSON, not encrypted**: they're the player's own, on their own PC, readable without the key.

## The exact path

With the Outside plugin's "Record my exact path for my year" on (off to begin with, behind its own warning; the plugin's
`Track`), the plugin sends `{"game": {"here": {"x", "y", "plane", "world"}}}`:

- the tile the player's on, **at once** (it's for the player alone): when it changes, at most one every 4 s while they
  move, and the tile they stop on 3 s after the last; nothing while they stand still;
- placed as following places it (`Follow.tile`): on a boat, where the boat is at sea; underground, the tile above; an
  instance where its template lies, or one off the map at its entrance's square's middle; Prifddinas where the world map
  draws it; Zanaris as its one place (2368, 4416); anywhere else off the map, `null`;
- never from somewhere dangerous (following's rules: the Wilderness whatever "In the Wilderness too" says, a PvP area,
  a PvP, Deadman or other risky world; Ferox Enclave and the PvP minigames are safe): going there sends `null` at once;
  logging out (`"off": true`), switching it off and the plugin going clear it too.

The relays (game.ts's `gameHere`, channel.ts's `takeHere`; relay.py's `game_here`, `Channel.take_here`) take it only from
the game socket, exactly `{"x", "y", "plane", "world"}`: `x` a whole number from 960 up to 4096 and `y` from 2048 up to
4224 (the main map and its seas, `WHERE_X` and `WHERE_Y`, as tiles), or exactly Zanaris's; `plane` 0 to 3; `world`
1 to 999. One every 2 s at most (`HERE_EVERY_MS`), the rest ignored. Each one taken goes at once to the channel's desks
as `{"mine": {"here", "at"}}`, and nowhere else: never kept (not in memory, not in storage), never on GET /channels,
GET /game, a viewer's, a looker's or the transmitter's socket, the Array or the aether.

The app writes each into the local record as it comes, at most one line every 3 s (`record::HERE_MS`; one that comes
sooner waits, the latest of them with its own `t`, and is written once the 3 s are up, so the tile they stopped on is
kept), and `null` at once. Into the relay's copy it goes as every other line does, sealed with the record key. The page
draws the tiles: a path through them in order, a gap at a `null` or past `HOLD_MS`. The public summary may carry them
as finely as the player chooses (11/10/2026): its heat in cells down to 8 tiles, its paths and replay at exact tiles;
its top places are still squares (below).

## Encryption

**The record key:** 32 random bytes, made by the app the first time the relay copy is switched on (ring's system
random; the app uses ring for the HMACs and AES-256-GCM too, rather than the `aes-gcm` crate: ring is in the build
already for TLS, so nothing new is linked, and its AES is constant-time and hardware-accelerated), kept in the app's settings (`record_key`, as below). It's never made from the station key, so a new station
key from the keeper loses nothing, and the relay never gets it.

As text (copy and paste, the settings file): `record1:` and the 32 bytes as base64url without padding (43 characters).

**Keys made from it** (key separation: no one key does two jobs). `HMAC(k, s)` is HMAC-SHA256 keyed with the raw 32
bytes, over the UTF-8 of `s`; `b64u` is base64url without padding.

| name         | what it is                                                                                  | used for                                  |
| ------------ | ------------------------------------------------------------------------------------------- | ----------------------------------------- |
| seal key     | `HMAC(k, "outstation record seal v1")`, 32 bytes                                            | the AES-256-GCM key                       |
| read token   | `b64u(HMAC(k, "outstation record read v1"))`, 43 characters                                  | reading the relay's copy                  |
| write token  | `b64u(HMAC(k, "outstation record write v1"))`, 43 characters                                 | posting or taking down the public summary |
| chunk id     | `b64u(first 16 bytes of HMAC(k, "outstation record chunk v1\n" + device + "\n" + day + "\n" + part))`, 22 characters | a chunk's name on the relay |

`day` is `YYYY-MM-DD` (UTC), `part` a decimal number from 0. The tokens and ids are one-way: none of them gives back k.

**A chunk** is one device's lines of one UTC day (split into parts of at most 4,000 lines, which happens only on a long day with the exact path on). Its plaintext, before compressing, is UTF-8 newline JSON: a header line, then the day's lines exactly as the
local file has them, each ending in `\n`:

    {"record": 1, "device": "0123456789abcdef", "day": "2026-11-10", "part": 0}
    {"t": 1794268800000, "live": true}
    …

**The sealed chunk**, as the relay stores it and the page gets it (bytes):

    offset 0      1 byte    the format: 1
    offset 1      12 bytes  the nonce (random, new for every seal)
    offset 13     …         AES-256-GCM ciphertext of the compressed plaintext, with its 16-byte tag appended

- compressed: raw DEFLATE (RFC 1951, no zlib or gzip header): the browser's `DecompressionStream("deflate-raw")`,
  Python's `zlib` with `wbits=-15`, Rust's flate2 `DeflateEncoder`;
- encrypted: AES-256-GCM with the seal key, the 12-byte nonce, a 128-bit tag, and as **additional data the UTF-8 of
  the chunk's id** (so a chunk can't be passed off under another name). This is exactly WebCrypto's AES-GCM:
  `crypto.subtle.decrypt({name: "AES-GCM", iv: nonce, additionalData: utf8(id), tagLength: 128}, sealKey, rest)`
  takes bytes 13 to the end (ciphertext then tag) as they are.

Test vectors (a fixed key, device, day, nonce and plaintext, and every value above computed by Node's WebCrypto):
`tests/fixtures/record/vectors.json`, made by `tests/fixtures/record/make_vectors.mjs`. The app's unit tests and both
relays' tests check against them.

**A second PC:** "copy my record key" on the first, "paste a record key" on the second. Both then seal under the same
key, register the same tokens, and their chunks (named by their own device ids) sit side by side on the relay.

**A lost key:** the relay's copy can never be read again, by anyone: ⊂λ,ｍ⊙ can't recover it. Each PC's local files
are still there and readable. A new key (and "delete my record" for the old copy) starts again: each PC that still has
its files uploads them afresh under the new key.

## Sync

Each PC uploads its own sealed chunks; the relay stores ciphertext only.

**Where the relay keeps them:** on Cloudflare, in a Durable Object of its own for each channel's record (`Record`,
`relay/src/record.ts`, SQLite-backed storage), not R2. Why:

- a chunk is small (about 1 KB a day of play, compressed) and is rewritten through its day (each upload replaces it):
  a Durable Object row written costs $1 a million beyond 50 million a month, an R2 write $4.50 a million;
- the page reads a whole year in one or two requests (the list with the data, `?data=1`) instead of one R2 read for
  each day (and no Worker subrequest limits to mind);
- the read token's hash, the chunks and the public summary sit together and are checked in one place; "delete
  everything" is one `deleteAll()`;
- nothing is ever served from a public address (clips' R2 files have unguessable addresses; these have none at all);
- its own object, not the channel's: a page reading a year never wakes or slows a live channel, and the channel's
  key can change without touching it.

R2 would cost less per GB stored ($0.015 a GB-month against $0.20), which at these sizes is pennies either way.

relay-py keeps the same in a folder (`--records <dir>`; the tests give it a temporary one), as it keeps clips.

**The routes** (both relays, the same answers; `<id>` is a chunk id, 22 base64url characters):

    POST   /record/<channel>/token?key=<station key>   {"read": <read token>, "write": <write token>}   registers them
    PUT    /record/<channel>/<id>?key=<station key>     the sealed chunk's bytes                         stores (replaces) it
    DELETE /record/<channel>/<id>?key=<station key>                                                       one chunk gone
    DELETE /record/<channel>?key=<station key>                                                            everything gone
    GET    /record/<channel>             Authorization: Bearer <read token>   {"chunks": [{"id", "at", "size"}], "bytes"}
    GET    /record/<channel>?data=1[&after=<id>]   the same, each chunk with "data" (base64), and "next"

- **Registering** (`token`): the relay keeps only the SHA-256 (hex) of each token, never the token or the key. The same
  pair again changes nothing; up to `RECORD_KEYS` (4) different pairs (a key pasted wrong on one PC doesn't wipe
  another's); a fifth: 409. Answers 204.
- **Uploading** (`PUT`): the station key, checked as everywhere else (wrong keys counted the same). The body is
  checked to be a sealed chunk: byte 0 is 1 and it's 30 bytes or more (1 + 12 + a byte + 16), else 415; at most
  `RECORD_CHUNK` (256 KB), else 413. A plaintext upload (`{…`) is refused. Answers 201 `{"id", "at", "size"}`, `at`
  the relay's time of this upload.
- Per channel at most `RECORD_CHUNKS` (2,000) chunks and `RECORD_BYTES` (32 MB) in all (507 past either); a chunk not
  uploaded again in `RECORD_DAYS` (400) days goes; `RECORD_AN_HOUR` (240) uploads and deletes an hour (429).
- **Reading** needs the read token (`Authorization: Bearer`): the page proves it holds the record key without
  sending it. A wrong token, or none registered: 403. The list is in id order (`after` pages on it); with `data=1` a
  page holds chunks up to 4 MB of data, and `next` is the id to go `after`, or null at the end. Any origin may read
  (CORS `*`, and `OPTIONS` answered).
- **Deleting everything** (`DELETE /record/<channel>`) takes every chunk, every registered token and the public
  summary. The keeper taking a channel back (DELETE /admin/channels, the Worker) does the same: nobody could manage
  it any more.
- The relay can see how many chunks there are, their sizes and when each was uploaded (roughly which days the player
  played, and how much); never where, what, or which day a chunk is.

**The app's schedule** (client/src/record.rs): with the relay copy on and a record key, it registers the tokens and
uploads each day whose lines changed since the last upload (sent.json, per relay, channel and key): a minute after it
opens, then hourly, on going dark, after a key is pasted, and before "open my year". The relay keeps what was sent;
later lines of the same day replace the chunk (the same id) whole.

**Merging** is union by time, in the app and the page: every chunk's lines from every device, sorted by `t`, a line
identical to another (the same `t` and the same JSON, which two PCs open at once write from the relay's `at`) taken
once. Time spent is worked out from the merged timeline (a minute's state is the latest `where`/`activity` stated by
any device within `HOLD_MS`), never by adding up each device's durations, so two PCs open at once don't count twice.

## Viewing

"open my year" in the app opens, in the browser:

    https://ghillie.wiki/outside?player=<channel>&view=year#k=<the record key, base64url, 43 characters>

A URL's fragment (`#…`) is never sent to any server, so the key reaches only the page. The page should:

1. read `k`, then `history.replaceState` the fragment away (so it doesn't sit in the address bar; it may stay in this
   tab's sessionStorage);
2. make the read token (HMAC, above), `GET /record/<channel>?data=1` (and again with `after` while `next` isn't null);
3. for each chunk: byte 0 must be 1; AES-GCM decrypt bytes 13 on with the seal key, nonce bytes 1 to 13 and the id as
   additional data; `DecompressionStream("deflate-raw")`; split the lines; the first is the header;
4. a chunk that won't decrypt is from another record key (one pasted wrong somewhere): skip it and say how many;
5. merge as above, and draw.

(The address can move to outside.wiki once the globe lives there: one constant in the app, `record::YEAR_PAGE`, and
`win::opens` let through.) The key in the browser's history is the one place it lands outside the app: the page
clearing the fragment keeps it out of the address bar; the history entry is the player's own browser's.

## The public summary

A JSON the player builds, part by part, in the page (or the app), from their decrypted year. Each part is optional
except `v`; a heat cell is a cell's middle at the heat's cell size, a step (in paths and the replay) any tile, and a top
place a 128-tile square's middle (all on the main map and its seas, x 960 up to 4096 and y 2048 up to 4224, or Zanaris
as its one place, 2368, 4416), never a world nor a floor; every number is a whole number (minutes for time, ms since 1970 for times); and every time is optional ("with or
without times").

    {
      "v": 1,
      "tz":         "Europe/London" or <minutes east of UTC>,               the poster's clock (11/10/2026)
      "range":      {"from": <ms>, "to": <ms>},
      "totals":     {"minutes", "live_minutes", "days", "squares", "kills", "levels", "pets", "drops", "clogs", "clues"},
      "days":       [{"date": "YYYY-MM-DD", "minutes", "act"?}, …],         ≤ 400, each date once (11/10/2026)
      "heat":       <cells> or {"cell": 8|16|32|64|128, "cells": <cells>},  ≤ 32,768 cells, each once; a plain list is 128
                                                                             <cells> is [[x, y, minutes] or [x, y, minutes, act], …]
      "paths":      [<steps> or {"steps": <steps>, "act"?}, …],             ≤ 4,096 paths of 2 to 5,000 steps, ≤ 50,000 in all;
                                                                             <steps> is [[x, y] or [x, y, t], …], any tiles
      "activities": [{"activity": <label>, "minutes"}, …],                  ≤ 64
      "hours":      [24 whole numbers],                                      minutes in each hour of the day
      "places":     [{"x", "y", "minutes", "first"?, "last"?}, …],          ≤ 64
      "moments":    [{"event": {"kind", …}, "t"?}, …],                      ≤ 64, arrayEvents' shapes
      "replay":     [[x, y] or [x, y, t], …]                                 ≤ 20,000 steps, any tiles
    }

- `tz` (11/10/2026): the time zone `hours` and `days` are counted in, so the page shows them in the poster's clock.
  Either an IANA name, `UTC` or two or three parts of letters, digits and `_ + -` split by `/` (each part starting with
  a letter or digit, at most 30 characters; 64 in all), such as `Europe/London`, `America/Argentina/Buenos_Aires` or
  `Etc/GMT+5`; or a whole number of minutes east of UTC, -840 to 840 (`-300` for UTC-5). The relay checks only the
  pattern, not that the zone exists: a page that doesn't know the name falls back to UTC. Without `tz`, UTC.
- `days` (11/10/2026, the calendar strip): each `date` a real day, 2020-01-01 to a day past the relay's now, in `tz`'s
  clock; `minutes` that day 0 to 1,440; `act` that day's main activity, optional. Any order; the relay keeps it.
- `act` (11/10/2026; on a day, a heat cell or a path): an activity as one short closed label: `"idle"`, `"fighting"`,
  or `"skilling:<skill>"`, `"boss:<boss>"`, `"raid:<raid>"`, `"minigame:<minigame>"`, the name exactly as one of the
  plugin's lists has it (game.ts's ACTIVITY_SKILLS, ACTIVITY_BOSSES, ACTIVITY_RAIDS, ACTIVITY_MINIGAMES): for example
  `"skilling:Mining"`, `"boss:Vorkath"`, `"minigame:the Inferno"`. A string, not gameActivity's object, so a heat cell
  stays small. A path written as plain `<steps>` (as before) is kept that way; one written as an object is kept as
  `{"steps", "act"?}`.
- `totals`: any of those keys, each 0 to 1,000,000,000. `minutes` 0 to 10,000,000 wherever it's a part's weight.
- `range`, `t`, `first`, `last`: from 2020-01-01 (1,577,836,800,000) to a day past the relay's now; `from` ≤ `to`,
  `first` ≤ `last`.
- `activities`' labels as gameActivity takes them; `moments`' events as arrayEvents makes them (a kill: `boss` (60
  characters) and `kc`; a level: `skill` and `level`; a pet: `name`?; a drop: `name`? and `value`; a log slot: `name`?;
  a clue: `tier`? and `count`?). These are the only words anywhere in a summary.
- **Heat cells** (11/10/2026, for the exact path): `cell` is the cell's size in tiles, 8, 16, 32, 64 or 128; each cell
  is given by its middle, `x = k * cell + cell / 2` (and `y` the same), so at 8 tiles `3260, 3268` is the cell of tiles
  3256 to 3263 by 3264 to 3271; Zanaris is `2368, 4416` at any size. A plain list (as before) is 128-tile squares, which
  are where's squares. `minutes` may be 0: a cell only passed through. The relay keeps the form it was given.
- **Steps** in paths and the replay are any tile (x 960 up to 4096, y 2048 up to 4224, or Zanaris), so exact-path
  tiles go in as they are; old steps at squares' middles are tiles too.
- Anything else (an unknown part or field, a wrong type, a place off the map or not a cell's middle, a number out of
  range, a list too long) is refused with 400 and the part named; the whole body at most 5 MB (413; 512 KB before
  11/10/2026, then 2 MB).

**Why those caps** (measured 11/10/2026 with a made-up year of the exact path, its `here` every 4 s while moving, the
rest of each day's play in stays a few tiles across, teleporting most of the way between ~35 real places;
tests/fixtures/record isn't involved): an ordinary year (three days in four, mostly two hours, ten favourite places)
touched 14,000 8-tile cells (5,500 at 16, 1,800 at 32) and made 46,000 exact steps; a busy one (most days, four to
eight hours, everywhere) touched 27,000 8-tile cells (7,900 at 16) and made 210,000 steps. So the heat takes 32,768
cells, a busy year's every 8-tile cell with room to spare (about 0.95 MB of JSON, against 11 KB for only the cells with
a minute or more); the paths take 50,000 steps in up to 4,096 journeys (a busy year made 4,099), which an ordinary
year's exact path fits as it is and a busy one's after the page thins it (every second or fourth step, or dropping
steps along a straight line); the replay stays at 20,000. Every part at its most, with the longest names and labels
and times on every step, measured 4.02 MB, inside the 5 MB. (The page's own demo year, in the ghillie repo's
`year.js`, has no exact path, so it can't measure this; its 128-tile year uses a few hundred squares.)

Every part is optional, so a summary from before `tz`, `days`, `act` and fine cells is still valid as it was.

The relay stores it as checked (re-written in the order above, so both relays store and answer the same bytes):

    PUT    /year/<channel>?key=<station key>            (or Authorization: Bearer <write token>)   200 {"year", "at"}
    GET    /year/<channel>                              anyone                                     200 {"year", "at"} / 404
    DELETE /year/<channel>?key=<station key>            (or the write token)                       204

The write token lets the page post or take down the summary without the station key ever reaching the browser; it
can't touch the encrypted chunks. A GET is any origin's (CORS `*`): it's public by the player's choice, even for an
outstation with a watch code. "delete everything" takes it down too.

## Delete everything

The app's "delete my record…" asks first, then `DELETE /record/<channel>?key=` (every chunk, every token, the public
summary) and switches the relay copy off, so nothing's uploaded again until it's switched back on. With "and the
files on this PC" ticked, the month files and sent.json go too (the device id stays).

## Sizes and costs

Measured on a made-up day of two hours' play (a heartbeat a minute, a move every dozen minutes, a few events):
149 lines, 15.9 KB of JSON, **850 bytes** deflated (+29 sealed). So, without the exact path:

- per player per year at two hours a day: about **330 KB** on the relay (~1 MB at six hours a day), and as much again
  for each further PC played on the same days; 4 to 15 MB of plain JSON on each PC;
- each upload: 1 Worker request, 2 Durable Object requests (the channel checks the key, the record stores it), 2 rows
  written; about 3 a day of play;
- each year opened on the page: 1 or 2 requests, about 400 rows read.

On Cloudflare (Workers Paid, Cloudflare's docs 11/10/2026: SQLite-backed Durable Objects' storage $0.20 a GB-month
past 5 GB included, rows written $1.00 a million past 50 million, rows read $0.001 a million past 25 billion, requests
$0.15 a million past 1 million; R2 for comparison $0.015 a GB-month, writes $4.50 and reads $0.36 a million):

| players | stored after a year | Durable Object requests a month (uploads) | cost a month             |
| ------- | ------------- | -------------------------- | -------------------------------- |
| 100     | ~35 MB        | ~18,000                    | nothing past the plan            |
| 1,000   | ~350 MB       | ~180,000                   | nothing past the plan            |
| 10,000  | ~3.5 GB       | ~1,800,000                 | about $0.12 (requests)           |
| 100,000 | ~35 GB        | ~18,000,000                | about $6 storage + $2.60 requests |

**With the exact path** (re-measured 11/10/2026 the same way, raw DEFLATE: a `here` every 4 s while moving, one on
each stop, the heartbeat carrying it): a two-hour day with about 26 minutes of walking between long stays, 474 lines,
47.5 KB of JSON, **3.5 KB** deflated (against 0.65 KB for the same day without it); a travel-heavy day, about half of it
moving, 801 lines, 70.6 KB, **5.7 KB**. So, for a player with it on, at two hours a day: about **1.3 to 2.1 MB** a year
on the relay (4 to 6 MB at six hours a day) and 17 to 26 MB of plain JSON on each PC. If every player switched it on,
1,000 players would store about 1.3 to 2 GB after a year (nothing past the plan), 10,000 about 13 to 21 GB (about $2 to
$3 a month past the 5 GB included), 100,000 about 130 to 210 GB (about $25 to $41 a month). It's off to begin with. The
uploads (requests, rows) barely change: a day is still one part of at most 4,000 lines unless it holds more than about
four hours of moving (then two or three), and a year of it stays far under a channel's `RECORD_BYTES` (32 MB).

On the free plan the same fits under its daily limits (100,000 requests and 100,000 rows written a day, 5 GB in all)
up to a few thousand players.

## Wise Old Man (later, not built)

"What you did most" from before the recorder existed could come from Wise Old Man's public API (a player's
experience gained by skill and kills by boss over a period, from their tracked snapshots): the page could ask it
directly, by the player's name, with no record needed. Not built; worth a look once the year exists.
