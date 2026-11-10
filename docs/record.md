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

Exactly what the relay already sees from the Outside plugin, nothing finer:

- following's `where`: the middle of the 128-tile square the player was in two minutes before, and the world with "and
  my world" on (game.ts, gameWhere), at most one every 50 s; or null when it's cleared;
- `activity`: one coarse label (game.ts, gameActivity), the same way;
- the player's own Array events: a kill with its kill count, a level, a pet, a drop worth 1,000,000 or more, a collection
  log slot, a clue (array.ts, arrayEvents), from the field reports their outstation sends (and Dink's posts);
- when they were live (transmitting) or dark.

Nothing from viewers, the chat, the picture or the sound; no tile finer than the square; nothing from the Wilderness or
anywhere else the plugin holds back (the plugin never sends it, so the relay never has it).

The record is made **only while the app is open**: it's the app that writes it, from what the relay tells it. The relay
never keeps a plain copy for the app to collect later.

## Getting the data to the app: the desk's `{"mine"}` lines

Today `where` and `activity` go only into GET /channels' rows (while live and listed), never to a socket. Now the relay
also passes the owner's own `where`, `activity` and events to **the channel's desk sockets only**:

    ws(s)://<relay>/desk/<channel>?key=<station key>&name=<name>&v=<version>

the chat window's socket, which only the station key opens and which the app keeps open whenever it runs (live or
dark). Each is one text line:

    {"mine": {"where": {"x": 3264, "y": 3264, "world": 302}, "at": 1794067212345}}
    {"mine": {"where": null, "at": 1794067512345}}
    {"mine": {"activity": {"kind": "skilling", "skill": "Mining"}, "at": 1794067212345}}
    {"mine": {"activity": null, "at": 1794067512345}}
    {"mine": {"event": {"kind": "kill", "boss": "Vorkath", "kc": 412}, "at": 1794067301000}}

- `at` is when the relay took it (ms since 1970, the relay's clock): the same number to every desk of the channel, so
  two PCs open at once record identical lines (merging drops the copy).
- `where`: sent each time the relay takes one (game.ts's WHERE_EVERY_MS, one every 50 s at most), and `null` when it's
  dropped (the plugin clears it, logs out, holds for the player's safety, or disconnects). `activity`: the same.
- `event`: one line per event of a field report (array.ts's arrayEvents: its kinds and fields exactly), from the
  transmit socket's `{"report"}` or a Dink post, live or dark. (The Array's public feed still takes them only while
  live and listed; this is the owner's own.)
- A desk that connects gets the `where` and the `activity` held now, if any, each with the `at` it came with.
- Sent whatever the channel's state: live or dark, with a watch code or not, an invite or not. Never to a viewer
  (watch), a looker, the transmitter, the sound, the game socket, GET /game or the aether. GET /channels is unchanged.

Live and dark come from what the desk already gets: the status line's `live` (`{"relay": {"live": …}}`).

## The local record

    %APPDATA%\outstation\record\2026-11.jsonl     one file per month (UTC), newline JSON, appended only
    %APPDATA%\outstation\record\device            this PC's device id (16 hex characters, random, made once)
    %APPDATA%\outstation\record\sent.json         what's been uploaded (the sync's own bookkeeping)

(With `--settings <file>`, the folder is `record` beside that file.) Each line is one JSON object with `t` (ms since
1970) and one or more of:

| field      | value                                                         | written                                           |
| ---------- | ------------------------------------------------------------- | ------------------------------------------------- |
| `where`    | `{"x", "y"}` or `{"x", "y", "world"}` (gameWhere's), or null  | when it changes; `t` is the relay's `at`          |
| `activity` | an activity label (gameActivity's), or null                   | when it changes; `t` is the relay's `at`          |
| `event`    | `{"kind", …}` (arrayEvents')                                  | each one; `t` is the relay's `at`                 |
| `live`     | true or false                                                 | when the relay's status says it changed; `t` local |

**The heartbeat:** while the desk is up and a `where` or an `activity` is held (not null), a line restating whichever
are held, `{"t", "where", "activity"}`, is written whenever a minute has passed with no `where`/`activity` line: at
most one a minute, so a long stay in one place counts as long. `t` is this PC's clock.

**Reading it:** a `where` (or `activity`) holds from its line until the next line that states it, but never more than
**3 minutes** past its last statement (`HOLD_MS` = 180,000): a gap longer than that (the app closed, the PC asleep, the
relay out of reach) is a gap, not a stay. A field missing from a line says nothing about it. Places and labels are what
they were about **two minutes before `t`** (the plugin sends them two minutes behind); a page may shift them back by
120 s, or not.

Example:

    {"t": 1794067200000, "live": true}
    {"t": 1794067212345, "where": {"x": 3264, "y": 3264, "world": 302}}
    {"t": 1794067212345, "activity": {"kind": "skilling", "skill": "Mining"}}
    {"t": 1794067272400, "where": {"x": 3264, "y": 3264, "world": 302}, "activity": {"kind": "skilling", "skill": "Mining"}}
    {"t": 1794067301000, "event": {"kind": "level", "skill": "Mining", "level": 72}}

The local files are **plain JSON, not encrypted**: they're the player's own, on their own PC, readable without the key.

## Encryption

**The record key:** 32 random bytes, made by the app the first time the relay copy is switched on (ring's system
random), kept in the app's settings (`record_key`, as below). It's never made from the station key, so a new station
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

**A chunk** is one device's lines of one UTC day (split into parts of at most 4,000 lines, which never happens in
practice). Its plaintext, before compressing, is UTF-8 newline JSON: a header line, then the day's lines exactly as the
local file has them, each ending in `\n`:

    {"record": 1, "device": "0123456789abcdef", "day": "2026-11-10", "part": 0}
    {"t": 1794067200000, "live": true}
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
except `v`; each place is a square's middle as `where` gives it (gameWhere: the main map's squares, or Zanaris), never a
world; every number is a whole number (minutes for time, ms since 1970 for times); and every time is optional ("with or
without times").

    {
      "v": 1,
      "range":      {"from": <ms>, "to": <ms>},
      "totals":     {"minutes", "live_minutes", "days", "squares", "kills", "levels", "pets", "drops", "clogs", "clues"},
      "heat":       [[x, y, minutes], …],                                   ≤ 4,096 squares, each once
      "paths":      [[[x, y] or [x, y, t], …], …],                          ≤ 256 paths of 2 to 2,000 steps, ≤ 20,000 in all
      "activities": [{"activity": <label>, "minutes"}, …],                  ≤ 64
      "hours":      [24 whole numbers],                                      minutes in each hour of the day
      "places":     [{"x", "y", "minutes", "first"?, "last"?}, …],          ≤ 64
      "moments":    [{"event": {"kind", …}, "t"?}, …],                      ≤ 64, arrayEvents' shapes
      "replay":     [[x, y] or [x, y, t], …]                                 ≤ 20,000 steps
    }

- `totals`: any of those keys, each 0 to 1,000,000,000. `minutes` 0 to 10,000,000 wherever it's a part's weight.
- `range`, `t`, `first`, `last`: from 2020-01-01 (1,577,836,800,000) to a day past the relay's now; `from` ≤ `to`,
  `first` ≤ `last`.
- `activities`' labels as gameActivity takes them; `moments`' events as arrayEvents makes them (a kill: `boss` (60
  characters) and `kc`; a level: `skill` and `level`; a pet: `name`?; a drop: `name`? and `value`; a log slot: `name`?;
  a clue: `tier`? and `count`?). These are the only words anywhere in a summary.
- Anything else (an unknown part or field, a wrong type, a square off the map, a number out of range, a list too long)
  is refused with 400 and the part named; the whole body at most 512 KB (413).

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
149 lines, 15.9 KB of JSON, **850 bytes** deflated (+29 sealed). So:

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

On the free plan the same fits under its daily limits (100,000 requests and 100,000 rows written a day, 5 GB in all)
up to a few thousand players.

## Wise Old Man (later, not built)

"What you did most" from before the recorder existed could come from Wise Old Man's public API (a player's
experience gained by skill and kills by boss over a period, from their tracked snapshots): the page could ask it
directly, by the player's name, with no record needed. Not built; worth a look once the year exists.
