# the relay on Cloudflare (⊂λ,ｍ⊙ outstation's)

A Worker (`src/index.ts`) routes each socket to its channel's Durable Object (`src/channel.ts`). The protocol is `../docs/protocol.md`, and it's the same as `../relay-py/relay.py`'s.

It uses WebSocket Hibernation. A channel that's off air, with only viewers and the chat waiting, sleeps between messages. What has to survive a sleep is kept in the object's own storage:
- the key's hash and the watch code
- the title, the cap on viewers, whether viewers may chat and ping, the desk's chat names
- the chat so far, the poll and the drop guess, the blocks and bans
- the reports, the stream's summary and each boss's last kill count
- the version that last transmitted, the time on air by month, and in all (for invites)
- the names it has invited
- the game state the Outside plugin last sent (11/07/2026), saved at most once a minute while it sends and when it goes
- messages held for the outstation, and the list of its clips (the clips' files are in R2)

The Directory (one object for the whole relay) keeps the channels handed out (each with whose invite it came from, if any), the requests for station keys, the keeper's notice, and the anomalies and requisitions sent to the listening post.

The Aether (one object for the whole relay, `src/aether.ts`, since 11/07/2026) is the aether: one room for every outstation and visitor on `/aether`, with `GET /aether` for a page before it joins. It keeps the last 100 lines and the bans; the lines are one row, written at most every 5 s while they come and when the room empties. The keeper takes lines back and bans with `/admin/aether` (`../docs/protocol.md`, "The aether"). Its migration (`v2` in `wrangler.jsonc`) goes up with the next `npm run deploy`.

It also answers `GET /channels`, the open portals' list, from what each channel has told it (`src/listed.ts`). A channel tells the Directory its row whenever the row changes: live, the picture, viewers, title, what it's doing, the plugin, following's square and the activity. The rows are in its storage (the key `listed`, one value: each row and when it was last heard; since 11/10/2026, Jace's decision), read back once a wake and written when a row's stored part changes (a new square alone writes nothing) or it's been heard a whole check since its time was written. Following's `where` and the `activity` (and when each came) are never written: the stored row is made field by field from a list that leaves them out (`KEPT_FIELDS`, which the types won't let name them), and they're held in memory beside it. The Directory asks a channel for its row only when it's missing, or older than 5 minutes (a row that says live) or 30 (any other), and, once a wake, when it says live with the plugin connected: that one ask puts `where` and `activity` back for the read after a sleep, and a live channel's awake anyway. A dark channel, or a live one without the plugin, isn't asked after a sleep. So a read costs one request while nothing's due, and a wake no longer asks every channel handed out. The Directory holds no sockets or timers, so Cloudflare puts it to sleep about 10 seconds after its last request, and with the globe reading the list every 20 s that was nearly every read: before 11/10/2026 the rows were in its memory only, and each of those reads asked every channel again (about 90,000 Durable Object requests a day at 20 channels, near the Free plan's 100,000). Staleness at worst: a channel that went dark without telling (a lost telling, or a deploy closing its sockets) shows live for 5 minutes and a read (the next read, with the plugin); one that came on air without telling shows dark for 30 minutes, or till its next change. Taken back or given a new key, its row's taken out at once. The cost, at 20 channels with the globe open all day (a read every 20 s, about 4,320 reads a day): the reads themselves, plus about one ask per live channel with the plugin per read (about 4,300 a day for each such channel while it's live), one per other live channel every 5 minutes (288 a day) and one per dark channel every 30 (48 a day); and about one row written per change of a row, plus one per live channel every 5 minutes and per dark one every 30 at most, well inside the Free plan's 100,000 rows written a day. A channel that's told the Directory something and failed (a stub broken by the Directory being reset) tells it again once on a new stub (11/10/2026), so a live channel, which never sleeps, isn't left talking to a dead one. Before 11/07/2026, every read asked every channel and woke each one. An invite not taken up yet (its key never used) tells it only that, and is counted under its inviter in the list's `pending`, never named (11/08/2026).

It keeps the shooting stars too (11/08/2026, `src/stars.ts`): the crashed stars the Outside plugins have found, one for each world and 128-tile square, with its landing site, the first listed outstation to find it that asked to be named (the report's `credit`), and how many did (11/09/2026), for `GET /stars` (outside.wiki's globe). A channel passes on each report its plugin sends; the Directory counts 30 an hour from one channel at most and keeps each star two hours at most, or till it's told gone. In its storage (the key `stars`, one value: the stars, who told of each, the gone ones and each channel's count; since 11/10/2026), written when a report changes it and read back once a wake; the Cron Trigger's look every 5 minutes takes a star past its two hours out of storage (`../docs/protocol.md`, "Shooting stars").

It keeps the Array's events too (11/08/2026, `src/array.ts`): the last 500 kills, levels, pets, big drops, log slots and clues across the open outstations, for `GET /array/events` (outside.wiki's front page). A channel tells it each report's events while it's live with no watch code; it drops what's past 10 a minute from one channel. They're in its storage (the key `array`, one value: the events, the newest id and each channel's last minute; since 11/10/2026, Jace: "make the feed keep its events"), written with each report that adds an event (one row written per report, however many events it makes) and read back once a wake, so the feed and its ids outlive the Directory's sleeps and deploys. Before, they were in its memory only, and a kill showed for the 10 seconds or so till the Directory slept (`../docs/protocol.md`, "The Array's events"). The keeper can put back a real event it lost, in its place by when it happened, with `POST /admin/array` (11/10/2026; the ghillie repo's `tools/portal/friends.py array`).

It keeps the almanac too (11/09/2026, `src/almanac.ts`): Jagex's news, the world list and the Wiki's prices, for outside.wiki's globe (`GET /almanac`), fetched by the relay on a schedule and shared by every visitor (below, "The almanac's sources"; `../docs/protocol.md`, "The almanac"). It's in the Directory's storage, so an eviction or a deploy doesn't fetch anything again.

The Lookups (one object for the whole relay, `src/lookup.ts` and `src/lookups.ts`, since 11/10/2026) ask Wise Old Man, RuneProfile and the icons' hosts for the pages and keep what came, so a page never asks them and many visitors make one request (`../docs/protocol.md`, "The lookups"). In its storage (`e:<key>`: when, the status, the type, the body), so an eviction or a deploy asks nothing again; an alarm once a day drops what's past 14 days (an icon's 90) or past 5,000 kept. Its migration (`v4` in `wrangler.jsonc`) goes up with the next deploy; nothing to make by hand. Wise Old Man's key, when there is one, isn't used yet: it goes in as a secret and a header in `httpLookup` once Wise Old Man gives one.

The Records (one object per channel, `src/record.ts`, since 11/10/2026) keep each player's year (`../docs/record.md`): the SHA-256 of the record key's tokens (`tokens`), each sealed chunk (`d:<id>`) with its time and size (`m:<id>`), and the public summary as checked (`year`). Only ciphertext: the app seals each chunk with a key the relay never sees, and a body that isn't sealed is refused. Apart from the channel, so a page reading a year never wakes or slows a live channel and a new key from the keeper leaves the record alone; the Worker checks the station key with the channel (`/_keyok`) and passes on only its verdict (`x-station`), the bearer token and the body. Taking a channel back deletes its object's storage. Its migration (`v3` in `wrangler.jsonc`) goes up with the next deploy; nothing to make by hand (unlike the clips' bucket). `RECORD_CHUNKS` (a var) lowers the 2,000 chunks a channel may hold, for the tests.

While live, the frames keep the object awake. The last frame lives in memory only. SAFETY.md, "What the relay keeps", says all of it for the people it's about, and for how long.

## On this PC

```bash
npm install
```

```bash
npx wrangler dev --port 8790 --var ADMIN_TOKEN:<a long random string>
```

`wrangler dev` runs the Worker in workerd, with no Cloudflare account needed. To hand out a channel:

```bash
curl -X POST -H "Authorization: Bearer <the token>" http://127.0.0.1:8790/admin/channels/<player>
```

That answers `{"channel", "key", "code"}`. `npm run check` checks the types.

The Outside plugin (a RuneLite plugin of our own, 11/07/2026) sends the player's game state on `/game/<channel>?key=` with the channel's key, and pages read what's kept with `GET /game/<channel>?code=` (`src/game.ts`; `../docs/protocol.md`, "The game").

Each outstation can hand out channels too (invites, since 11/07/2026): three to begin with and one more every 10 hours on air, through `GET`/`POST /invites/<channel>` with its own key (`../docs/protocol.md`, "Invites"). Each channel's row in `GET /admin/channels` says whose invite it came from (`by`, `null` for yours).

Settling (an outstation choosing where it stood on outside.wiki's globe, 11/08/2026) went on 11/10/2026: `/settle/<channel>` and `/home/<channel>` answer 410 with a line an app built in those two days shows, and nothing has a home (`../docs/protocol.md`, "Settling"). A channel's meta saved then may still hold `home`, `homeAt` and `settle`: they're dropped as it's read and gone from storage at its next save; a stored row of the open list with a `home` loses it as the list is read, and in storage at the list's next write.

## On Cloudflare

These are done once, by the account's owner:
- Sign up, and take the Workers Paid plan ($5 a month).
- Pick the `workers.dev` subdomain in the dashboard.
- Then run each of these:

```bash
npx wrangler login
```

```bash
npx wrangler secret put ADMIN_TOKEN
```

```bash
npm run deploy
```

**Since 11/08/2026 it deploys itself:** Cloudflare's Workers Builds is connected to this repository (the relay Worker ▸ Settings ▸ Builds: branch `main`, path `/relay`, `npx wrangler deploy`), so merging a pull request into `main` puts the relay up, about a minute later. Check the pull request's tests before merging: the build deploys whatever lands on `main`. `npm run deploy` by hand still works from a PC logged in with `npx wrangler login`.

It goes up as `https://relay.<subdomain>.workers.dev` (Jace's: `https://relay.ghillie.workers.dev`), and the sockets are `wss://` at the same address.

## The almanac's sources

`GET /almanac` (outside.wiki's globe) is made from three outside sources, each fetched by the relay alone, on its own schedule, and shared by every visitor: a page never asks them, and a visitor's read never makes the relay ask them (but once, cold: below). Jace's rule (11/08/2026): the Wiki "stated they are fed up with websites and plugins and apps using their data to scrape things all the time constantly… we should refrain from putting unnecessary strain on their servers."

How it runs: the Cron Trigger in `wrangler.jsonc` (`"triggers": {"crons": ["*/5 * * * *"]}`) wakes the Worker every 5 minutes, and it asks the Directory to refresh whichever sources are due. The Directory keeps what came in its storage (`alm:kept`, `alm:latest`, `alm:before`, `alm:names`), so a read costs one request to it and nothing upstream, and an eviction or a deploy loses nothing. Why the Directory: it already answers `/channels` and `/stars` for the globe, so there's no new object (and no migration), its storage outlives evictions (the Cache API is per data centre and can be dropped any time, and KV would be one more binding, with a write limit), and one object means one refresh at a time: reads that come while it's cold wait for that one refresh together.

| source | address | how often | requests a day |
|---|---|---|---|
| Jagex's news | `https://secure.runescape.com/m=news/latest_news.rss?oldschool=true` | every 20 minutes | 72 |
| the world list | `https://oldschool.runescape.com/slr.ws?order=LPWM` | every 5 minutes | 288 |
| the Wiki's day of prices | `https://prices.runescape.wiki/api/v2/osrs/24h` | 15 minutes after UTC midnight; hourly after it only while the new day isn't out | 1 normally; 24 at most |
| the Wiki's day before | `…/24h?timestamp=<the day before>` | only when the kept day isn't the one before (never, once it's running) | 0 normally; 1 after a fresh start |
| the Wiki's item names | `https://prices.runescape.wiki/api/v2/osrs/mapping` | once a day | 1 |

So the Wiki gets about 2 requests a day from the relay (at most 26), and Jagex about 360. A failing source is asked less, not more: each failure in a row doubles its wait, to a cap (the news 2 hours, the world list an hour, `/24h` 6 hours, `/mapping` a day). Besides the schedule, only two things ask: the first `GET /almanac` after a deploy with nothing kept yet (one refresh of each source, shared by every read that comes meanwhile), and the keeper's `POST /admin/almanac` (each once, by hand). `GET /admin/almanac` shows each source's last fetch, its failures and its next try, without asking anything.

The prices are the Wiki's v2 API: its page (RuneScape:Real-time Prices) asks users of v1, which "may be removed in the future", to move to v2. `/24h` is a whole UTC day that's the same all day (the Wiki's own `Expires` is the next UTC midnight), so asking it hourly would only fetch the same day again. Only `/24h` and `/mapping` are asked, never one item at a time (the policy's other ask).

Every request has this User-Agent (the Wiki's acceptable-use policy's one ask: say what it's for and how to reach us; the project's page, no email address), and nothing else from anyone:

```
outstation-relay/1.0 (the almanac for outside.wiki's globe: fetched on a schedule and shared by every visitor; https://github.com/jhmcubed/outstation)
```

The answers are capped at 8 MB and 15 seconds each. The sizes, as asked on 11/09/2026: `/24h` 390 KB (70 KB gzipped, which the Worker asks for), `/mapping` 870 KB. The world list is 30,000 bytes (16 KB of it the list, 352 worlds on 11/09/2026, the rest zeros) and the news feed 10 KB (15 items, in ISO-8859-1), each fetched once on 11/09/2026 to check both relays' parsers against them; cut-down copies are the fixtures `news-real.rss` and `slr-real.ws`. If the world list's address ever stops answering, `https://www.runescape.com/g=oldscape/slr.ws?order=LPWM` is the one RuneLite's own service used (`WORLDS_URL` in `src/almanac.ts` and `relay-py/almanac.py`).

The Python relay does the same in a task of its own (a look each minute), with `--almanac off` to fetch nothing. It keeps what came in memory only, so each start asks each source once.

`npm test` runs `test/almanac_test.mts` (the parsing, against `../tests/fixtures/almanac`, and a day of the schedule on a clock of its own: the request counts above) and `test/listed_test.mts` (the open list's stored rows: never `where` or `activity`, what a wake asks, when it's written), and `test/lookup_test.mts` (the lookups' rules on a clock of their own; `../relay-py/test_lookup.py` the same for the Python relay); `../relay-py/test_almanac.py` is the same for the Python relay, and `../tests/protocol_test.py`'s `Almanac` tests both relays' `GET /almanac` with the sources served from the fixtures on the test's own computer.

## Settings

The `vars` in `wrangler.jsonc`:
- `MAX_VIEWERS`: a ceiling on any channel's viewers ("0": none beyond each channel's own cap).
- `MIN_CLIENT`: the oldest client let in (e.g. "0.2.0"; "" lets every version in). Older clients are turned away with 4026 and told to get a newer one (`../docs/protocol.md`, "The client's version"). Look at `GET /admin/channels` first: each channel's `client` is the version that last transmitted on it.
- `OPEN_CLAIM`: "1" lets the first transmitter claim a channel, as `relay.py` does. For local tests only; never on Cloudflare.
- `POLL_MIN_SECONDS`, `POLL_KEPT_SECONDS`: shorter polls, for the tests.
- `WHERE_FRESH_SECONDS`: how long a player's square (following, and its world) and what they're doing show on `GET /channels` after the plugin said them (240 when unset), shorter for the tests.
- `LOOKUP_UPSTREAM`: the lookups' sources (Wise Old Man, RuneProfile, the icons) all fetched under this address (at the real ones' paths). For the tests only; never on Cloudflare.
- `ALMANAC_UPSTREAM`: the almanac's sources all fetched under this address (at the real ones' paths) instead of Jagex's and the Wiki's hosts. For the tests only; never on Cloudflare.
- `TEST_FORGET`: "1" answers `POST /test/forget` by putting the Directory to sleep as Cloudflare does (its memory gone), so `../tests/protocol_test.py`'s `Kept` can see the Array's events, the stars and the open list come back from storage; and `GET /test/listed/<channel>`, what the Directory's storage holds of that channel's row and how often it's asked the channel since it woke (`POST`, with fields to put into the stored row, as a telling that was lost). For the tests only; never on Cloudflare (unset, the routes are 404).
- `LISTING_CHECK_SECONDS`: how old a row on the open list that says live may be before the Directory asks its channel again (300 when unset; any other row, six times that), shorter for the tests.
