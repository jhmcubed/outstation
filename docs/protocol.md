# ⊂λ,ｍ⊙ outstation's protocol

Both relays speak it: the Cloudflare Worker (`relay/`) and the self-hosted Python relay (`relay-py/relay.py`).
`tests/protocol_test.py` runs the same tests against either, so they stay the same.

## The sockets

| | address | who |
|---|---|---|
| transmit | `/transmit/<channel>?key=<key>&code=<watch code>` | the capture |
| sound | `/sound/<channel>?key=<key>` | the sound |
| desk | `/desk/<channel>?key=<key>&name=<your name in the chat>` | the chat window (several may be open) |
| watch | `/watch/<channel>?code=<watch code>` | a viewer (Outside) |
| look | `/watch/<channel>?look=1&code=<watch code>` | a looker: the listening post's receiver, the outstations page's tiles, the client's monitor and a friend's peek (below, "Lookers") |

`ws://` on this PC, `wss://` on Cloudflare. A channel is 1 to 64 characters: letters, digits and `_ . ~ -`.

The client adds `&v=<its version>` to every address it opens (below, "The client's version").

Closing codes:
- 4000: replaced by a newer transmitter or sound.
- 4001: a key under 8 characters.
- 4003: the wrong key, the wrong watch code, the watch code changed, a channel nobody handed out (Cloudflare), or the channel given a new key or taken back.
- 4026: this client is older than the relay allows; the reason says which version is needed ("… 0.2.0 or newer is needed"). Trying again won't help: the client stops and says where to get a newer one.
- 4029: too many wrong keys from one address in a minute (Cloudflare).

## The client's version

The client says its version (`client/Cargo.toml`'s, which a release's tag must match) as `v=` on its transmit, desk and sound sockets, and on its monitor's and a friend's peek's watch sockets.
- A relay started with a minimum (`MIN_CLIENT` in the Worker's `wrangler.jsonc`, `--min-client` for `relay-py/relay.py`) turns a transmit, desk or sound socket from an older client away with 4026, before its key is looked at. That doesn't count as a wrong key.
- Versions are compared by number (`0.10.0` is newer than `0.9.0`), and a pre-release's suffix is left out (`0.5.0-rc.1` counts as `0.5.0`).
- A socket that says no version is let in: ghillie's Python portal predates it.
- Viewers never need one.
- No minimum (the default) lets every version in. Raise it only when the protocol changes in a way older clients can't follow, and after the keeper's list (`GET /admin/channels`, `client`) shows who'd be turned away.

## The transmitter

- **Binary:** a frame, a 4-byte big-endian number (the moment it was taken, in ms, wrapping), then a JPEG.
- **A small frame:** the same 4 bytes, then `TH`, then a small JPEG (192 px wide), about once a second. Lookers only: never sent to a viewer, never kept as the last frame. While small frames are coming (one in the last 3 s), lookers get them instead of the full ones.
- **Text:** the game data, passed to viewers untouched. The last line is kept for whoever joins next.
  - `{"state": "open" | "static" | "shut"}`: the picture's gate. `static` is the static key: viewers see static, and the sound still passes. Sound passes only while it's `open` or `static`.
  - `{"report": {"type", "text", "at"}}`: a field report (from Dink), as ghillie's Python portal sends them. The outstation client posts to the Dink route instead (below, "Dink"). The last 8 are kept for late joiners.
- **It hears:** the status line (below).

The first transmitter sets the channel's watch code. Viewers whose code no longer matches are let go.

## Viewers

**They hear:**
- the status line: `{"relay": {"channel", "live", "viewers", "max", "waiting", "open", "static", "set", "pings", "chat", "title"}}`. `static`: the static key is down. `pings` and `chat`: whether viewers may ping and chat. `set` says whether the channel has been handed out (on the Python relay: claimed by a transmitter). `title` is the broadcast's title, from the desk (`""` for none). In line for a seat it also has `"seat": false` and `"place": n`; with a seat, `"you": n`, their number in the chat.
- frames and data, as the transmitter sent them. On arriving they get the last data line, the last frame (only while live), the reports, the summary, the chat so far, the poll, the drop guess and the sound's line.
- `{"report": {"type", "text", "at", "data"}}`: a field report (from Dink; below, "Dink").
- `{"summary": {...} | null}`: this stream's summary (below, "The stream's summary").
- `{"chat": {"id", "name", "text", "host", "who", "queued", "at"}}`, `{"unsay": id}`
- `{"poll": {"id", "question", "options", "counts", "open", "left"}}`, `{"poll_gone": id}`
- `{"guess": {"id", "boss", "what", "open", "kc", "count", "result": {"kc", "item", "winners", "off"} | null}}`, `{"guess_gone": id}`: a drop guess (below, "Drop guesses").
- `{"ping": {"x", "y", "name", "who", "at"}}`
- `{"signal": {"name", "who"}}`: someone pressed the ✼.
- `{"clip": Clip}`, `{"clip_gone": id}`, `{"clip_kept": {"id", "kept"}}` (below, "Clips").
- `{"sound": {"mode", "buses"}}`, and the sound's binary messages (below)

**They may send** (anything else is thrown away; nothing over 2 KB is read):
- `{"hello": {"name", "device"}}`: their name in the chat, and a random id their device keeps (8–64 characters). Nobody may use the channel's or a desk's name.
- `{"say": text}`: at most 20 lines in 10 s, 300 characters each.
- `{"vote": {"poll": id, "pick": i}}`: one vote a device, which they can change while the poll is open.
- `{"ping": {"x", "y"}}`: a spot on the frame, 0–1 each. One every half second, and only with a seat.
- `{"signal": true}`: the ✼ button, as often as a hand can press (one every 80 ms at most).
- `{"guess": {"id", "kc"}}`: a kill count still to come, for the drop guess going on, while it's open. One a device, which they can change.
- `{"got": bytes}` (Cloudflare): every binary byte received so far, about once a second. Past 4 MB sent and not yet acknowledged, a viewer gets no more frames or sound until it catches up. A viewer that never says it gets the first 4 MB and then text only. The Python relay ignores it: it reads its own send buffer.

**The waiting line:** past the cap, a viewer waits in line, with status lines and the chat but no frames. The first in line takes the first seat that frees.

## The desk

**It hears:** the status line, the chat so far and all of it after, the poll, the drop guess, pings, ✼ signals, the reports and the summary, who's banned (`{"banned": [{"who", "name"}]}`), messages from other outstations (below, "Messages between outstations"), who's watching (`{"viewers": [{"who", "name", "waiting"}]}` whenever it changes: names as given, never a device), `{"upload": {"bps", "viewers", "acked"}}` (`acked`: what viewers say they've had, on Cloudflare) each second while live, and on Cloudflare the keeper's `{"notice"}` ("A notice from the keeper", below). The upload is what the relay sends viewers: on the Python relay it's the PC's own upload; on Cloudflare, Cloudflare's.

**It sends:**
- `{"say": text}`: as the host (`"host": true`), under its `name`.
- `{"hide": id}`: takes a line back off everyone's page, late joiners included.
- `{"block": who}` / `{"unblock": who}`: that viewer's number and device. Their lines and pings stop going anywhere.
- `{"ban": who}` / `{"unban": who}`: blocked, and every line of theirs taken back off everyone's page; the desk's list of bans (`{"banned"}`) says who.
- `{"poll": {"question", "options": [2–6], "seconds": 10–900}}` and `{"poll_close": true}`. A closed poll's result stays 90 s, then `{"poll_gone"}`.
- `{"chat_on": bool}`: off, viewers' lines go nowhere (the host's still go out); the status line's `chat` says so.
- `{"pings": bool}`: off, viewers' pings go nowhere; the status line's `pings` says so.
- `{"guess_open": {"boss", "what"}}`, `{"guess_close": true}`, `{"guess_drop": true, "item"}`, `{"guess_off": true}`, `{"guess_min_value": gp}`: a drop guess (below, "Drop guesses").
- `{"dm": {"to", "text"}}`: a message to another outstation (below, "Messages between outstations").
- `{"title": text}`: the broadcast's title, in every status line from then on (and in `GET /channels`): one line, at most 100 characters, `""` for none. The client sends it on connecting and whenever it changes; the Worker keeps it through a sleep.
- `{"cap": n}`: the channel's own cap on viewers (0 means none). On the Python relay it takes the place of `--max-viewers` for that channel. On Cloudflare the Worker's `MAX_VIEWERS` is a ceiling over it.

## Lookers

A looker (`/watch/<channel>?look=1`) has no seat and isn't counted as watching: the listening post's receiver, the outstations page's tiles, the client's monitor and a friend's peek in its friends list. It needs the watch code, as a viewer does.
- It hears the status lines, the summary and the clips' lines.
- It gets frames only while it asks, `{"look": {"frames": true}}` (`false` to stop), at most one every half second. While the transmitter is sending small frames (one in the last 3 s), a looker gets those instead of the full ones.
- Anything else it sends is thrown away.

## The open outstations

`GET /channels` (both relays; anyone may read it): `{"channels": [{"channel", "live", "open", "static", "viewers", "title", "doing"}]}`, live ones first. Only channels handed out and with no watch code are on it. `doing` is `{"boss", "kc"}` while live with a kill count in this stream's summary, else `null`. The outstations page on ghillie.wiki (`portals.html`) and the client's friends list read it.

## The stream's summary

The relay folds each field report into one summary a stream, so every viewer's readings are the same and the page can show a recap off air. `{"summary": {"since", "ended", "boss", "kc": {boss: {"name", "first", "last", "pb"}}, "loot", "best": {"name", "value", "id"}, "levels": {skill: level}, "total", "log": {"item", "id", "done", "total"}, "pets": [...]}}`, each part there only once something's told of it; `pets` is the last 5. Going live starts a new one, unless the last stream ended less than 15 minutes ago (then it carries on). It's kept with the channel, so it outlives a sleep.

## Drop guesses

The desk opens a round on a boss (`guess_open`); viewers guess the kill count the drop will come at; the relay decides it from Dink's reports.
- The relay keeps each boss's last kill count from Dink's `KILL_COUNT` reports. A guess must be a count still to come (above the boss's count now).
- `guess_close` stops new guesses; the round still waits for the drop.
- A `LOOT` report from that boss worth `guess_min_value` or more (1,000,000 to begin with) decides it, at the boss's count then. `guess_drop` decides it by hand, with the item's name.
- Decided, the result says who guessed closest and how far off, and the relay says so in the chat as `✼`. It stays two minutes. `guess_off` calls a round off.

## The sound

Binary messages:
- 4 bytes: when the sound was taken (ms, the frames' clock)
- `AU`
- the bus: 1 the game, 2 everything else
- how many Opus packets follow
- each packet: a 2-byte length, then the packet

Text: `{"sound": {"mode": "off" | "game" | "all", "buses": [...]}}`.

The relay holds sound half a second (as the capture holds frames back). It lets sound through only while the picture is live and open. When the sound's socket goes, viewers hear `{"sound": {"mode": "off", "buses": []}}`.

## Dink

`POST /dink/<channel>?key=<key>`, as Dink posts to a Discord webhook: multipart with `payload_json`, or plain JSON.
- Only Dink's own kinds pass (`REPORTS` in `relay/src/text.ts` and `relay-py/relay.py`).
- Screenshots are dropped.
- A death in PvP loses its text.
- Nothing that names another player gets through: trades, chat, player kills.

Each report reaches viewers as `{"report": {"type", "text", "at", "data"}}`: `text` its line (at most 200 characters, Discord's markdown taken out), `data` the numbers the page's readings use (`relay/src/text.ts`'s `reportData`, relay-py's `report_data`: the boss and its count, the levels, the loot's items and value, the collection log's count).

The outstation client takes Dink's posts on its own PC (`127.0.0.1`) and posts only what the relay keeps of each to this route (`client/src/dink.rs`, `for_relay`): its `type`, the line as `content`, and the `extra` fields `reportData` reads. The rest of Dink's post never leaves the PC. ghillie's Python portal sent reports over its transmit socket instead.

## Handing out channels (Cloudflare)

`Authorization: Bearer <ADMIN_TOKEN>`:
- `POST /admin/channels/<channel>`: a new key, `{"channel", "key", "code"}`. Any old key stops working, and whatever was connected with it is cut off.
- `DELETE /admin/channels/<channel>`: taken back. Its key is refused, and its transmitter, sound and desks are cut off. Viewers stay, off air.
- `GET /admin/channels`: the list, each with `channel`, `since`, `live`, `viewers`, `title`, `client` (the version the last transmitter said, `""` for none) and `live_s` (seconds on air since the 1st of this month, UTC, the stretch going on now included: what Cloudflare's bill follows).

The attach code (the station key, in the client's words) is `outstation1:` followed by base64url JSON `{"relay": "wss://…", "channel", "key"}`. A friend pastes it into the client, and that's all their set-up. Keys made before 11/06/2026 start `portal1:`, with the same inside; the client still reads them.

Only the key's SHA-256 is kept.

## Asking for a station key (Cloudflare)

So a friend needn't email the keeper for a code: their client asks, the keeper answers from his list, and the client collects the answer itself.

A friend's client:
- `POST /requests/<channel>` with `{"player", "note", "pickup"}` asks for a channel.
  - `channel` is the player's name as the page has it: 1 to 12 letters or numbers, lower case, no spaces.
  - `player` is the name as they typed it, and `note` how the keeper knows them (one line, 200 characters at most).
  - `pickup` is a secret of the client's own, 32 to 128 characters. Only its SHA-256 is kept.
  - Answers: 201 `{"state": "waiting", "given"}`, where `given` says the channel is already handed out (a new code would cut off whoever has it).
  - Refused: 409 when someone else is already waiting for that name, 429 past 3 requests an hour from one address, 503 past 50 waiting in all.
  - Asking again with the same pickup only changes the note.
- `GET /requests/<channel>?pickup=<secret>` answers `{"state": "waiting"}`, `{"state": "given", "code"}` or `{"state": "turned down"}`, or 404 `{"state": "none"}` (no request, or the wrong pickup). An answer is given once: collected, the request is gone, and the attach code with it.
- `DELETE /requests/<channel>?pickup=<secret>`: the asker takes it back.

The keeper (`Authorization: Bearer <ADMIN_TOKEN>`):
- `GET /admin/requests`: those waiting, oldest first, each `{"channel", "player", "note", "at", "given"}`.
- `POST /admin/requests/<channel>`: handed out, as `POST /admin/channels/<channel>` (the same answer); the code then waits for the asker's client. 404 if nobody is waiting for it.
- `DELETE /admin/requests/<channel>`: turned down.

A request nobody answers goes after 14 days, and an answer nobody collects after 7.

## Clips (11/06/2026)

An outstation with "put them on my Outside page" on (client/src/clipsup.rs) uploads the clips that saved themselves; the
player's Outside page shows them (ghillie's public/outside-portal.js, "recovered footage"). Both relays: the Worker
keeps the files in its R2 bucket `CLIPS` (relay/src/clips.ts; `npx wrangler r2 bucket create outside-clips` once),
relay-py in a folder (`--clips`, ~/Documents/ghillie portal/clips; "" takes none).

    POST   /clips/<channel>?key=<key>           multipart/form-data: meta (JSON), strip (image/jpeg), clip (video/mp4)
                                                201 {"id"}; 403 wrong key; 413 over 8 MB (the strip 256 KB, the still 16 KB);
                                                415 not an .mp4 or not a JPEG; 429 over 30 an hour
    GET    /clips/<channel>?code=<watch code>   {"clips": [Clip, …]} newest first (403 without a friends-only channel's code)
    GET    /clips/<channel>/<id>.mp4            the clip: whole, or the Range asked for (206), cached for a year
    GET    /clips/<channel>/<id>.jpg            its flipbook
    DELETE /clips/<channel>/<id>?key=<key>      taken down (204)
    POST   /clips/<channel>/<id>?key=<key>&keep=1|0   kept for good, or let go again (200; 409 past 3 kept)
    GET    /admin/clips/<channel>               (the Worker, the keeper's token) the list, whatever the channel's code
    DELETE /admin/clips/<channel>/<id>          taken down

meta: `{"what": "pet vorki", "kind": "PET", "at": <unix s, the moment>, "dur": <s, at most 20>, "w", "h", "frames": <the
flipbook's frames>, "fps": <their rate>, "still": <a small JPEG, base64>}`. A Clip is the same with its `id` and `size`.
A clip's files have unguessable addresses (16 random bytes), so the Worker serves them straight from the bucket
without waking the channel. At most 20 a channel and 30 days each, the oldest going first; up to 3 more kept for good
(`kept: true`: never counted in the 20, never too old; listed first). Viewers, desks and lookers are told
`{"clip": Clip}` as one comes, `{"clip_gone": "<id>"}` as one goes and `{"clip_kept": {"id", "kept"}}` as one's kept
or let go. The page's copy (client/src/clip.rs,
page_copy): 12 s before the moment to 5 s after, at most 768 px wide, 1.5 Mbit/s; the still is the moment, 160 px wide;
the flipbook, the moment's 1.5 s before to 1 s after, at most 20 frames the still's size, one under another.

## Messages between outstations (11/06/2026)

One outstation's window to another's, through the relay, never on either stream (the client's chat tabs: ui.rs, Thread).
On the desk socket, both relays:

    → {"dm": {"to": "<channel>", "text": "..."}}            from a desk only (a viewer's is ignored)
    ← {"dm_sent": {"to", "text", "at", "held"}}              held: their outstation's closed, the relay keeps it for them
    ← {"dm_failed": {"to", "text", "why"}}                   no such outstation on this relay, your own, or past 20 a minute
    ← {"dm": {"from": "<channel>", "name": "<their chat name>", "text", "at"}}   to the other outstation's desks

`at` in ms. A message for an outstation with no desk connected is held, at most 20 and 7 days each, and handed over as its
desk next connects. Both outstations must be on the same relay.

## Anomalies and requisitions (Cloudflare)

A friend's outstation reports something gone wrong (an anomaly) or asks for something (a requisition), only when they press "send to the listening post" (client/src/report.rs); the keeper reads them in his station's drawer.
- `POST /reports` with `{"kind": "anomaly" | "requisition", "name", "note", "log", "version", "channel", "key"}` (and `"windows"`, which the relay keeps if it's given; the client sends none): 201 `{"id"}`.
  - `log` is the station log with its keys, codes and webhook addresses already taken out by the client (`crash.rs`, `scrub`); a requisition sends none.
  - `channel` and `key` only from an outstation attached to this relay: when the key checks out, the report names the channel; otherwise its `channel` is `""` and only the typed `name` says who.
  - Refused: 400 with no note and no log, 413 past 64 KB, 429 past 5 an hour from one address.
- `GET /admin/reports` (the keeper): newest first, each `{"id", "kind", "at", "channel", "name", "note", "log", "version", "windows"}`.
- `DELETE /admin/reports/<id>`: read and done with.

At most 50 are kept, each for 30 days.

## A notice from the keeper (Cloudflare)

One line from the keeper that every friend's client shows in its chat, once (the relay moving, a newer version to get).
- `PUT /admin/notice` with `{"text"}` (one line, 300 characters at most): every desk connected now hears `{"notice": {"text", "at"}}` at once, and each desk that connects later hears it on connecting.
- `DELETE /admin/notice` takes it down: desks connected hear `{"notice": null}`.
- `GET /admin/notice`: `{"notice": {"text", "at"}}` or `{"notice": null}`.

The client shows a notice once, by its `at`, so a reconnect or a restart doesn't show it again. Viewers never get it.
