# the relay on Cloudflare (⊂λ,ｍ⊙ outstation's)

A Worker (`src/index.ts`) routes each socket to its channel's Durable Object (`src/channel.ts`). The protocol is `../docs/protocol.md`, and it's the same as `../relay-py/relay.py`'s.

It uses WebSocket Hibernation. A channel that's off air, with only viewers and the chat waiting, sleeps between messages. What has to survive a sleep is kept in the object's own storage:
- the key's hash and the watch code
- the title, the cap on viewers, whether viewers may chat and ping, the desk's chat names
- the chat so far, the poll and the drop guess, the blocks and bans
- the reports, the stream's summary and each boss's last kill count
- the version that last transmitted, the time on air by month, and in all (for invites)
- the names it has invited
- where it's settled on outside.wiki's globe and when that last changed, and the app's settling code (its SHA-256) while it lasts (11/08/2026, `src/settle.ts`)
- the game state the Outside plugin last sent (11/07/2026), saved at most once a minute while it sends and when it goes
- messages held for the outstation, and the list of its clips (the clips' files are in R2)

The Directory (one object for the whole relay) keeps the channels handed out (each with whose invite it came from, if any), the requests for station keys, the keeper's notice, and the anomalies and requisitions sent to the listening post.

The Aether (one object for the whole relay, `src/aether.ts`, since 11/07/2026) is the aether: one room for every outstation and visitor on `/aether`, with `GET /aether` for a page before it joins. It keeps the last 100 lines and the bans; the lines are one row, written at most every 5 s while they come and when the room empties. The keeper takes lines back and bans with `/admin/aether` (`../docs/protocol.md`, "The aether"). Its migration (`v2` in `wrangler.jsonc`) goes up with the next `npm run deploy`.

It also answers `GET /channels`, the open portals' list, from what each channel has told it (in memory only). A channel tells the Directory its row whenever the row changes: live, the picture, viewers, title, what it's doing. A row the Directory hasn't heard (after it slept), or hasn't heard for 5 minutes, it asks that channel for. So a read costs one request, however many channels there are, while the Directory's awake; the Directory holds no sockets or timers, so Cloudflare puts it to sleep about 10 seconds after its last request, and the first read after that asks every channel handed out again. A channel that's told the Directory something and failed (a stub broken by the Directory being reset) tells it again once on a new stub (11/10/2026), so a live channel, which never sleeps, isn't left talking to a dead one. Before 11/07/2026, every read asked every channel and woke each one. An invite not taken up yet (its key never used) tells it only that, and is counted under its inviter in the list's `pending`, never named (11/08/2026).

It keeps the shooting stars too (11/08/2026, `src/stars.ts`): the crashed stars the Outside plugins have found, one for each world and 128-tile square, with its landing site, the first listed outstation to find it that asked to be named (the report's `credit`), and how many did (11/09/2026), for `GET /stars` (outside.wiki's globe). A channel passes on each report its plugin sends; the Directory counts 30 an hour from one channel at most and keeps each star two hours at most, or till it's told gone. In its storage (the key `stars`, one value: the stars, who told of each, the gone ones and each channel's count; since 11/10/2026), written when a report changes it and read back once a wake; the Cron Trigger's look every 5 minutes takes a star past its two hours out of storage (`../docs/protocol.md`, "Shooting stars").

It keeps the Array's events too (11/08/2026, `src/array.ts`): the last 500 kills, levels, pets, big drops, log slots and clues across the open outstations, for `GET /array/events` (outside.wiki's front page). A channel tells it each report's events while it's live with no watch code; it drops what's past 10 a minute from one channel. They're in its storage (the key `array`, one value: the events, the newest id and each channel's last minute; since 11/10/2026, Jace: "make the feed keep its events"), written with each report that adds an event (one row written per report, however many events it makes) and read back once a wake, so the feed and its ids outlive the Directory's sleeps and deploys. Before, they were in its memory only, and a kill showed for the 10 seconds or so till the Directory slept (`../docs/protocol.md`, "The Array's events"). The keeper can put back a real event it lost, in its place by when it happened, with `POST /admin/array` (11/10/2026; the ghillie repo's `tools/portal/friends.py array`).

It keeps the almanac too (11/09/2026, `src/almanac.ts`): Jagex's news, the world list and the Wiki's prices, for outside.wiki's globe (`GET /almanac`), fetched by the relay on a schedule and shared by every visitor (below, "The almanac's sources"; `../docs/protocol.md`, "The almanac"). It's in the Directory's storage, so an eviction or a deploy doesn't fetch anything again.

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

Each outstation can choose where it stands on outside.wiki's globe (settling, since 11/08/2026): its app trades the key for a one-time code with `POST /settle/<channel>?key=`, and the globe page checks it (`GET /settle/<channel>?code=`) and settles with it (`POST /home/<channel>`), once a week after the first (`src/settle.ts`; `../docs/protocol.md`, "Settling"). The home shows in `GET /channels`, and in `GET /admin/channels` with when it changed (`homeAt`).

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

`npm test` runs `test/almanac_test.mts` (the parsing, against `../tests/fixtures/almanac`, and a day of the schedule on a clock of its own: the request counts above); `../relay-py/test_almanac.py` is the same for the Python relay, and `../tests/protocol_test.py`'s `Almanac` tests both relays' `GET /almanac` with the sources served from the fixtures on the test's own computer.

## Settings

The `vars` in `wrangler.jsonc`:
- `MAX_VIEWERS`: a ceiling on any channel's viewers ("0": none beyond each channel's own cap).
- `MIN_CLIENT`: the oldest client let in (e.g. "0.2.0"; "" lets every version in). Older clients are turned away with 4026 and told to get a newer one (`../docs/protocol.md`, "The client's version"). Look at `GET /admin/channels` first: each channel's `client` is the version that last transmitted on it.
- `OPEN_CLAIM`: "1" lets the first transmitter claim a channel, as `relay.py` does. For local tests only; never on Cloudflare.
- `POLL_MIN_SECONDS`, `POLL_KEPT_SECONDS`: shorter polls, for the tests.
- `SETTLE_CODE_SECONDS`: how long a settling code lasts (600 when unset), shorter for the tests.
- `WHERE_FRESH_SECONDS`: how long a player's square (following, and its world) and what they're doing show on `GET /channels` after the plugin said them (240 when unset), shorter for the tests.
- `ALMANAC_UPSTREAM`: the almanac's sources all fetched under this address (at the real ones' paths) instead of Jagex's and the Wiki's hosts. For the tests only; never on Cloudflare.
- `TEST_FORGET`: "1" answers `POST /test/forget` by putting the Directory to sleep as Cloudflare does (its memory gone), so `../tests/protocol_test.py`'s `Kept` can see the Array's events and the stars come back from storage. For the tests only; never on Cloudflare (unset, the route is 404).
