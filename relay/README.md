# the relay on Cloudflare (⊂λ,ｍ⊙ outstation's)

A Worker (`src/index.ts`) routes each socket to its channel's Durable Object (`src/channel.ts`). The protocol is `../docs/protocol.md`, and it's the same as `../relay-py/relay.py`'s.

It uses WebSocket Hibernation. A channel that's off air, with only viewers and the chat waiting, sleeps between messages. What has to survive a sleep is kept in the object's own storage:
- the key's hash and the watch code
- the title, the cap on viewers, whether viewers may chat and ping, the desk's chat names
- the chat so far, the poll and the drop guess, the blocks and bans
- the reports, the stream's summary and each boss's last kill count
- the version that last transmitted, the time on air by month, and in all (for invites)
- the names it has invited
- messages held for the outstation, and the list of its clips (the clips' files are in R2)

The Directory (one object for the whole relay) keeps the channels handed out (each with whose invite it came from, if any), the requests for station keys, the keeper's notice, and the anomalies and requisitions sent to the listening post.

It also answers `GET /channels`, the open portals' list, from what each channel has told it (in memory only). A channel tells the Directory its row whenever the row changes: live, the picture, viewers, title, what it's doing. A row the Directory hasn't heard (after it slept), or hasn't heard for 5 minutes, it asks that channel for. So a read costs one request, however many channels there are. Before 11/07/2026, every read asked every channel and woke each one.

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

Each outstation can hand out channels too (invites, since 11/07/2026): three to begin with and one more every 10 hours on air, through `GET`/`POST /invites/<channel>` with its own key (`../docs/protocol.md`, "Invites"). Each channel's row in `GET /admin/channels` says whose invite it came from (`by`, `null` for yours).

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

It goes up as `https://relay.<subdomain>.workers.dev` (Jace's: `https://relay.ghillie.workers.dev`), and the sockets are `wss://` at the same address.

## Settings

The `vars` in `wrangler.jsonc`:
- `MAX_VIEWERS`: a ceiling on any channel's viewers ("0": none beyond each channel's own cap).
- `MIN_CLIENT`: the oldest client let in (e.g. "0.2.0"; "" lets every version in). Older clients are turned away with 4026 and told to get a newer one (`../docs/protocol.md`, "The client's version"). Look at `GET /admin/channels` first: each channel's `client` is the version that last transmitted on it.
- `OPEN_CLAIM`: "1" lets the first transmitter claim a channel, as `relay.py` does. For local tests only; never on Cloudflare.
- `POLL_MIN_SECONDS`, `POLL_KEPT_SECONDS`: shorter polls, for the tests.
