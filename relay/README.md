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

It also answers `GET /channels`, the open portals' list, from what each channel has told it (in memory only). A channel tells the Directory its row whenever the row changes: live, the picture, viewers, title, what it's doing. A row the Directory hasn't heard (after it slept), or hasn't heard for 5 minutes, it asks that channel for. So a read costs one request, however many channels there are. Before 11/07/2026, every read asked every channel and woke each one. An invite not taken up yet (its key never used) tells it only that, and is counted under its inviter in the list's `pending`, never named (11/08/2026).

It keeps the shooting stars too (11/08/2026, `src/stars.ts`): the crashed stars the Outside plugins have found, one for each world and 128-tile square, with its landing site, the first listed outstation to find it that asked to be named (the report's `credit`), and how many did (11/09/2026), for `GET /stars` (outside.wiki's globe). A channel passes on each report its plugin sends; the Directory counts 30 an hour from one channel at most and keeps each star two hours at most, or till it's told gone. In memory only, like the Array's events (`../docs/protocol.md`, "Shooting stars").

It keeps the Array's events too (11/08/2026, `src/array.ts`): the last 500 kills, levels, pets, big drops, log slots and clues across the open outstations, for `GET /array/events` (outside.wiki's front page). A channel tells it each report's events while it's live with no watch code; it drops what's past 10 a minute from one channel. They're in memory only, so the feed starts empty whenever the Directory's evicted (`../docs/protocol.md`, "The Array's events").

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

## Settings

The `vars` in `wrangler.jsonc`:
- `MAX_VIEWERS`: a ceiling on any channel's viewers ("0": none beyond each channel's own cap).
- `MIN_CLIENT`: the oldest client let in (e.g. "0.2.0"; "" lets every version in). Older clients are turned away with 4026 and told to get a newer one (`../docs/protocol.md`, "The client's version"). Look at `GET /admin/channels` first: each channel's `client` is the version that last transmitted on it.
- `OPEN_CLAIM`: "1" lets the first transmitter claim a channel, as `relay.py` does. For local tests only; never on Cloudflare.
- `POLL_MIN_SECONDS`, `POLL_KEPT_SECONDS`: shorter polls, for the tests.
- `SETTLE_CODE_SECONDS`: how long a settling code lasts (600 when unset), shorter for the tests.
- `WHERE_FRESH_SECONDS`: how long a player's square (following, and its world) and what they're doing show on `GET /channels` after the plugin said them (240 when unset), shorter for the tests.
