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
| game | `/game/<channel>?key=<key>&v=<the plugin's version>` | the Outside plugin: the player's game state (below, "The game") |
| aether | `/aether?name=<name>&device=<random id>`, or `/aether?channel=<channel>&key=<key>&v=<version>` | a visitor, or an outstation: the one room for everyone (below, "The aether") |

`ws://` on this PC, `wss://` on Cloudflare. A channel is 1 to 64 characters: letters, digits and `_ . ~ -`.

The client adds `&v=<its version>` to every address it opens (below, "The client's version").

Closing codes:
- 4000: replaced by a newer transmitter, sound or game.
- 4001: a key under 8 characters.
- 4003: the wrong key, the wrong watch code, the watch code changed, a channel nobody handed out (Cloudflare), or the channel given a new key or taken back.
- 4026: this client is older than the relay allows; the reason says which version is needed ("… 0.2.0 or newer is needed"). Trying again won't help: the client stops and says where to get a newer one.
- 4029: too many wrong keys from one address in a minute (Cloudflare).
- 4002: a name or device id the aether can't take (below, "The aether"). The same again won't help.
- 4009: a visitor's name in the aether that's an outstation's.
- 4033: banned from the aether.
- 1013: the relay couldn't check an aether socket's key or name just then (Cloudflare): try again.

## The client's version

The client says its version (`client/Cargo.toml`'s, which a release's tag must match) as `v=` on its transmit, desk and sound sockets, its aether socket, and on its monitor's and a friend's peek's watch sockets.
- A relay started with a minimum (`MIN_CLIENT` in the Worker's `wrangler.jsonc`, `--min-client` for `relay-py/relay.py`) turns a transmit, desk or sound socket, or an outstation's aether socket, from an older client away with 4026, before its key is looked at. That doesn't count as a wrong key.
- Versions are compared by number (`0.10.0` is newer than `0.9.0`), and a pre-release's suffix is left out (`0.5.0-rc.1` counts as `0.5.0`).
- A socket that says no version is let in: ghillie's Python portal predates it.
- Viewers never need one.
- The game socket's `v=` is the Outside plugin's own version (below, "The game"): the minimum never applies to it.
- No minimum (the default) lets every version in. Raise it only when the protocol changes in a way older clients can't follow, and after the keeper's list (`GET /admin/channels`, `client`) shows who'd be turned away.

## The transmitter

- **Binary:** a frame, a 4-byte big-endian number (the moment it was taken, in ms, wrapping), then a JPEG.
- **A small frame:** the same 4 bytes, then `TH`, then a small JPEG (192 px wide), about once a second. Lookers only: never sent to a viewer, never kept as the last frame. While small frames are coming (one in the last 3 s), lookers get them instead of the full ones.
- **Text:** the game data, passed to viewers untouched. The last line is kept for whoever joins next.
  - `{"state": "open" | "static" | "shut"}`: the picture's gate. `static` is the static key: viewers see static, and the sound still passes. Sound passes only while it's `open` or `static`.
  - `{"report": {"type", "text", "at"}}`: a field report (from Dink), as ghillie's Python portal sends them. The outstation client posts to the Dink route instead (below, "Dink"). The last 8 are kept for late joiners.
- **It hears:** the status line (below).

The first transmitter sets the channel's watch code. Viewers whose code no longer matches are let go.

### Who's watching (11/10/2026)

Every message a transmitter sends counts against the relay's Durable Object requests (Cloudflare counts incoming socket
messages 20 to 1, outgoing ones not at all), watched or not: on 11/10/2026 a long unwatched test stream used up the free
plan's 100,000 for the day. So the status line it hears carries `viewers` (seated) and `lookers` (lookers asking for
frames; both relays, since 11/10/2026), and the client (transmit.rs, sound.rs) acts on them:
- no viewer seated: one full frame every 10 s (`IDLE_FRAME_EVERY`), so the relay's last frame stays recent for whoever
  comes first, and no sound; the full stream again the moment a status line says one's seated (the frames held back
  half a second go at once);
- no looker asking: no small frames; a relay that doesn't say `lookers` gets them as before;
- every frame and sound packet is still taken and kept for the client's clips, watched or not.
An unwatched stream at the default 8 frames a second with the game's sound goes from about 19 messages a second (8
frames, 1 small, 10 of sound) to about 0.3 (a frame every 10 s, the `state` line every 5 s): about 3,400 requests an hour
to about 55 (the Outside plugin's own socket, and HTTP reads like GET /channels, aside).


**They hear:**
- the status line: `{"relay": {"channel", "live", "viewers", "max", "waiting", "open", "static", "set", "pings", "chat", "title", "game", "lookers"}}` (`lookers`: lookers asking for frames, for the transmitter: "Who's watching", above). `static`: the static key is down. `pings` and `chat`: whether viewers may ping and chat. `set` says whether the channel has been handed out (on the Python relay: claimed by a transmitter). `title` is the broadcast's title, from the desk (`""` for none). `game`: whether the Outside plugin is connected now (below, "The game"). In line for a seat it also has `"seat": false` and `"place": n`; with a seat, `"you": n`, their number in the chat.
- frames and data, as the transmitter sent them. On arriving they get the last data line, the last frame (only while live), the reports, the summary, the game state, the chat so far, the poll, the drop guess and the sound's line.
- `{"game": {...}}`: the player's game state, from the Outside plugin (below, "The game"), once seated.
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
- `{"got": bytes}` (Cloudflare): every binary byte received so far: the first at once, then every 3 s or once 2 MB more has come, never more than once a second (about once a second until 11/07/2026; each is a request the relay counts). The desk's `acked` is the rate over about the last 5 s. Past 4 MB sent and not yet acknowledged, a viewer gets no more frames or sound until it catches up. A viewer that never says it gets the first 4 MB and then text only. The Python relay ignores it: it reads its own send buffer.

**The waiting line:** past the cap, a viewer waits in line, with status lines and the chat but no frames. The first in line takes the first seat that frees.

## The desk

**It hears:** the status line, the chat so far and all of it after, the poll, the drop guess, pings, ✼ signals, the reports and the summary, the game state (below, "The game"), who's banned (`{"banned": [{"who", "name"}]}`), messages from other outstations (below, "Messages between outstations"), who's watching (`{"viewers": [{"who", "name", "waiting"}]}` whenever it changes: names as given, never a device), `{"upload": {"bps", "viewers", "acked"}}` (`acked`: what viewers say they've had, on Cloudflare) each second while live, and on Cloudflare the keeper's `{"notice"}` ("A notice from the keeper", below); and, since 11/10/2026, the player's own `{"mine"}` lines, which no other socket ever gets (below, "Your year"). The upload is what the relay sends viewers: on the Python relay it's the PC's own upload; on Cloudflare, Cloudflare's.

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
- It hears the status lines, the summary, the clips' lines and the game state (below, "The game").
- It gets frames only while it asks, `{"look": {"frames": true}}` (`false` to stop), at most one every half second. While the transmitter is sending small frames (one in the last 3 s), a looker gets those instead of the full ones.
- It never says `{"got"}`, so the Worker takes it to read 128 KB a second (since 11/07/2026: before, a looker's frames stopped for good after its first 4 MB).
- Anything else it sends is thrown away.

## The open outstations

`GET /channels` (both relays; anyone may read it): `{"channels": [{"channel", "live", "open", "static", "viewers", "title", "doing", "by", "game", "where", "activity"}], "pending": [{"by", "n"}]}`, live ones first. Only channels handed out and with no watch code are on it, less the invites not taken up yet (below). `doing` is `{"boss", "kc"}` while live with a kill count in this stream's summary, else `null`. `by` is the channel whose invite it came from, or `null` (below, "Invites"). `game`: whether the Outside plugin is connected now (below, "The game"). `where` (11/08/2026, following): the square of the map the Outside plugin last said the player was in, `{"x", "y"}`, only while the channel's live and only if it came within the last 240 seconds, else `null` (below, "The game"); with the plugin's "and my world" on it's `{"x", "y", "world"}`. `activity` (11/08/2026): what the player was doing two minutes before, `{"kind": "skilling", "skill"}`, `{"kind": "boss", "boss"}`, `{"kind": "raid", "raid"}`, `{"kind": "minigame", "minigame"}`, `{"kind": "fighting"}` or `{"kind": "idle"}`, the same way as `where` (live, within 240 seconds), else `null` (below, "What they're doing"). This list is the only place either is ever shown (the player's own desk also gets their own, as `{"mine"}`, for their record: below, "Your year"); the shooting stars the plugins find have a list of their own (below, "Shooting stars"). The outstations page on ghillie.wiki (`portals.html`) and the client's friends list read it. What's happening across them, as it happens: below, "The Array's events".

How each relay keeps it (11/10/2026). The Python relay builds it from its channels, in its own memory, at each read. The Worker's Directory keeps each channel's row as the channel last told it (a channel tells it whenever its row changes: on air or off, the picture, viewers, title, what it's doing, the plugin, `where`, `activity`), in its storage (`relay/src/listed.ts`), so a read after the Directory's slept (about 10 seconds after its last request) is answered without asking every channel handed out. What's stored of a row is everything above but `where` and `activity` (and when each came), which are only ever held in memory beside it: after the Directory sleeps they're gone until the channel tells it again, or until the first read after the wake asks the channel, which it does once a wake for each row that says live with the plugin connected (the only rows that can have either). Besides that, it asks a channel only when its row is missing (never told, or a new key), or older than 5 minutes for a row that says live, 30 for any other (`LISTING_CHECK_SECONDS` shortens the 5, and the 30 with it, for tests): in case a telling was lost, or a channel's sockets closed without it telling (a deploy). So a channel that went dark without telling shows live for 5 minutes and one read at most (at once, the next read, when the plugin was connected), and one that came on air without telling shows dark for 30 minutes at most. A channel taken back, or given a new key, has its row taken out at once. The readers see the same answer from either relay.

Rows had a `home` from 11/08/2026 to 11/10/2026 (below, "Settling", gone). A row the Worker's Directory stored then may still hold one: it's left out as the list is read (`keptRow`), so no answer has it, and it's gone from storage at the list's next write.

`pending` (11/08/2026, for outside.wiki's globe: a key beside an inviter for each invite still waiting) counts the invites not taken up yet: one entry per inviter with any, `n` how many, sorted by `by`; `[]` for none. Readers from before it ignore it.
- An invite is taken up the first time its key is used rightly, for anything: a socket (transmitting, the desk, the sound, the game, the aether), Dink, clips or invites. A wrong key takes nothing up. Till then the channel isn't in `channels`, only counted under its inviter.
- Never the invited channel's name: the inviter typed it, and the friend hasn't agreed to anything.
- Only under an inviter that's in `channels` itself: an inviter with a watch code (or, on Cloudflare, one taken back) has its invites counted nowhere, so nothing's said of a hidden outstation.
- Only an invite's channel is ever pending. The keeper's own (`by` `null`) are listed from the moment they're handed out, as before, and a new key from the keeper leaves a channel as it was. On Cloudflare, channels invited before 11/08/2026 count as taken up.

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
- `POST /admin/channels/<channel>`: a new key, `{"channel", "key", "code"}`. Any old key stops working, and whatever was connected with it is cut off. Its game state is forgotten (below, "The game").
- `DELETE /admin/channels/<channel>`: taken back. Its key is refused, and its transmitter, sound, desks and game socket are cut off, and its game state forgotten. Viewers stay, off air.
- `GET /admin/channels`: the list, each with `channel`, `since`, `by` (the channel whose invite it came from, `null` for the keeper's own; below, "Invites"), `live`, `viewers`, `title`, `client` (the version the last transmitter said, `""` for none), `live_s` (seconds on air since the 1st of this month, UTC, the stretch going on now included: what Cloudflare's bill follows).

The attach code (the station key, in the client's words) is `outstation1:` followed by base64url JSON `{"relay": "wss://…", "channel", "key"}`. A friend pastes it into the client, and that's all their set-up. Keys made before 11/06/2026 start `portal1:`, with the same inside; the client still reads them.

Only the key's SHA-256 is kept.

## Asking for a station key (Cloudflare)

So a friend needn't email the keeper for a code: their client asks, the keeper answers from his list, and the client collects the answer itself. Since 11/11/2026 a page may ask and collect the same way (outside.wiki's "Open your own", on the globe): the answers carry `Access-Control-Allow-Origin: *`, an `OPTIONS` is answered for `DELETE`'s preflight, and the body is read as JSON whatever its `Content-Type`, so a page sends it as plain text and needs no preflight. The same limits hold.

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

## Invites (11/07/2026)

Each outstation hands out a few channels of its own, so a friend can bring a friend without the keeper (Jace: "each of my friends being able to pass out a few keys so that way the platform propagates easier", "people earning more invites through streaming"). Both relays.

    GET   /invites/<channel>?key=<key>             200 {"left", "earned", "used": ["moss", …], "hours", "next_in_s"}
    POST  /invites/<channel>  {"key", "player"}    200 {"channel", "key", "code", "left"}

The numbers:
- Three to begin with (`INVITES_START`), and one more for every 10 hours on air (`INVITE_EVERY_S`, 36000), counted over the channel's whole life.
- `earned` is 3 + (seconds on air ÷ 36000, rounded down). `left` is `earned` less the invites used, never below 0. `used` is the names invited, in order.
- `hours` is the time on air, one decimal, rounded down (so it never says 10.0 before the invite at 10). `next_in_s` is how many more seconds on air until the next one (1 to 36000).
- The stretch on air going on now counts.
- On the Worker, a channel from before 11/07/2026 starts from the months it still keeps (the last four). On the Python relay, the count starts when the channel's claimed, and a restart starts it again, as everything there is in memory.

`POST` hands out a channel for `player`, exactly as the keeper's `POST /admin/channels/<channel>` does: a new key, and `code` the attach code (above, "Handing out channels"), which a friend pastes into the client. `left` is what's left after it.
- `player` is the channel's name: 1 to 12 letters or numbers, lower case, no spaces (the same as `/requests`).
- An invite is counted only once the new channel has its key, so nothing that fails uses one up. Two posts at once can't both take the last one.
- The new channel says whose invite it came from (`by`, the inviter's channel name) in `GET /admin/channels` and in `GET /channels`: the register's "established through …'s outstation". A new key from the keeper keeps it.
- Till its key is first used (taken up), `GET /channels` leaves the new channel out and only counts it under its inviter (`pending`, above, "The open outstations").
- Taking a channel back (`DELETE /admin/channels/<channel>`) doesn't take back the channels it invited, and doesn't give its inviter the invite back.
- Invites don't touch `/requests`: a request waiting for a name that's then invited shows `given` in the keeper's list.

Refused, each with `{"error": "…"}`:
- 404 `no outstation called that here`: the channel isn't handed out (on the Python relay: nobody's claimed it).
- 403: the wrong key (`wrong key for this channel`), or a key under 8 characters. On Cloudflare a wrong key counts towards the wrong-key limit (10 a minute from one address), and past it the reason is `too many wrong keys: try again in a minute`, still 403.
- 429 `5 invites an hour at most: try again later`: past 5 posts an hour from one outstation (`INVITES_AN_HOUR`). Every post with the right key counts, whatever its answer.
- 400 `a player's name: 1 to 12 letters or numbers, lower case`, or `that's your own outstation` (and `which outstation?` for a channel in the address that can't be one).
- 409 `no invites left: one more every 10 hours on air`.
- 409 `that name has an outstation already`: the name is handed out (on the Python relay: claimed).
- 503 `the invite didn't go through: try again` (Cloudflare): the new channel couldn't be made. No invite is used.
- 413 past 4 KB; 405 any other method.

The Python relay refuses both routes on its public side (the Funnel's port), as it refuses transmitting.

## The game: the Outside plugin (11/07/2026)

The Outside plugin, a RuneLite plugin of our own, sends the player's live game state to their channel, and Outside (the
viewer page) draws it beside the transmission and the chat: live, and as last known while dark. Both relays
(`relay/src/game.ts` and channel.ts; relay-py's `game_fields`, `Game` and `Relay.game`).

    ws(s)://<relay>/game/<channel>?key=<key>&v=<the plugin's version>     the plugin
    GET <relay>/game/<channel>?code=<watch code>                         the state kept: {"game", "at", "connected"}

**The socket.** The same key as transmitting, with the same refusals: 4001 for a key under 8 characters, 4003 for the
wrong one (on Cloudflare also for a channel nobody handed out), and on Cloudflare 4029 past 10 wrong keys a minute from one
address. Its `v=` is the plugin's own version: `MIN_CLIENT` (`--min-client`) never looks at it. One at a time: a newer
one replaces the older (4000). It hears nothing, and nothing it sends is answered.

**It sends** text, `{"game": {...}}`, with any of these fields:
- `name`: the player's name (one line, 12 characters at most)
- `world`: the world they're on
- `type`: the account type (one line, 40 at most)
- `off`: `true` when the player logs out. Kept like the others: a later message without it leaves it as it was, and `"off": false` clears it.
- `paused`: why the plugin has stopped sending what the player wears and carries, for their safety (one line, 32 characters at most, e.g. `"wilderness"`: the Wilderness, a PvP area or world; never in Ferox Enclave or a PvP minigame since 11/10/2026), or `false` when it carries on. Kept like the others (`true` isn't its shape: left out). What was kept before it paused stays as it was, unless the plugin clears it (`"worn": []`, `"inv": []`).
- `vitals`: `{"hp": [now, most], "prayer": [now, most], "run": 0–100, "spec": 0–100}`
- `skills`: `{"<Skill name>": [real level, boosted level, xp]}`; a name is a letter, then letters, spaces, `_` or `-`, 24 at most
- `worn`: 14 slots, each `[item id, how many]` or `null` (the place in the list is RuneLite's `EquipmentInventorySlot` index)
- `inv`: 28 slots, the same
- `bag`: the looting bag, its slots (at most 28), or `null` while it isn't known
- `pouch`: the rune pouch's slots (at most 8)
- `quiver`: `[item id, how many]` or `null`
- `prayers`: the prayers on, by name (at most 64, 40 characters each)
- `items`: `{"<item id>": [name, Grand Exchange price]}`, merged into the dictionary kept (below)
- `where` (11/08/2026, following): not one of the game's fields, and never kept or sent with them (below, "Following").
- `activity` (11/08/2026, what they're doing): the same (below, "What they're doing").
- `star` (11/08/2026, a shooting star they found): the same (below, "Shooting stars").

Each field given replaces the one kept, whole; `items` is merged instead (a name and a price for each id). Checked
loosely:
- The message must be a JSON object, and `game` one too. Unknown fields are left out, and never kept.
- Numbers are whole, 0 to 2,147,483,647 (a Java int: RuneLite's); words are made one line.
- A field that isn't its shape is left out (one slot that isn't `[id, how many]` or `null` leaves its whole list out). In `vitals` and `skills` only the parts that aren't are left out; in `items` only the entries (an id is digits, no leading zeros).

The limits:
- A message at most 64 KB (65,536 characters); a longer one is ignored.
- 5 messages a second from one socket; more are dropped, with no answer.
- The item dictionary at most 3,000 ids; past that, new ids are ignored (those it has can still change).
- The kept state at most 192 KB as JSON; a message that would take it past is ignored, all of it.

**What others get:**
- A message that changed something goes on to seated viewers, lookers and desks as `{"game": {...the fields given, less anything left out, "at": <ms>}}`. One that changed nothing goes nowhere.
- Someone just come (a viewer as it takes its seat, a looker, a desk) gets `{"game": {...the whole state kept, "at": <ms, when it last changed>}}`, if there's any. A viewer in line for a seat gets it once seated, as it does frames.
- The status line's `game` (above, "Viewers"), and each row of `GET /channels`: whether a game socket is connected now.

**Following** (11/08/2026, Jace approved it with these safeguards). With the plugin's following on, it sends where the
player was two minutes before, as `{"game": {"where": {"x", "y"}}}`: the middle of a 128-tile square of the main map
(or Zanaris, as one place: below), at most once a minute, and the same square again every 3 minutes while it stays put. `{"game": {"where": null}}` clears
it: sent at once on going into the Wilderness (whatever the plugin's "In the Wilderness too" says), a PvP area or world,
or Deadman, and when following's switched off. The PvP minigames (Last Man Standing, Castle Wars, Clan Wars, Soul Wars,
the Fight Pits, Emir's Arena) aren't dangerous (11/10/2026, Jace: "there is no danger to someone tracking them there"):
in their regions the game's PvP-area flag doesn't clear it. Nor is Ferox Enclave (11/10/2026, Jace: "a safe area/haven
in the wilderness"), the town inside its barriers (the plugin's `OutsidePlugin.FEROX`, five rectangles from the Shortest
Path plugin's `WildernessChecker`): it goes as its own square, `{"x": 3136, "y": 3648}`, and stepping out into the
Wilderness clears it at once. **Instances** (11/09/2026, Jace: "allow instances, everyone knows where those instances get opened") go as
the square where their template lies on the map (the plugin reads it with RuneLite's `WorldPoint.fromLocalInstance`, as
RuneLite's Discord plugin does): Vorkath's as Ungael's, Zulrah's, the Colosseum's in Varlamore, the Nightmare's under
Slepe. One off the main map goes as its entrance's square (the plugin's `Follow.ENTRANCES`, by the template's region):
the Inferno and the Fight Caves as the Karamja volcano's `{"x": 2880, "y": 3136}`, the Chambers of Xeric as Mount
Quidamortem's `{"x": 1216, "y": 3520}`, the Theatre of Blood as Ver Sinhaza's `{"x": 3648, "y": 3264}`, the Tombs of
Amascut as the Necropolis pyramid's `{"x": 3392, "y": 2752}`, the Grotesque Guardians as the Slayer Tower's `{"x": 3392,
"y": 3520}`, the God Wars Dungeon's rooms as its Trollheim way in, `{"x": 2880, "y": 3776}`, the Gauntlets' lobby and
both Gauntlets and Zalcano's mine as Prifddinas's middle where the world map draws it (11/10/2026), the Giant Mole's lair
as Falador Park's `{"x": 3008, "y": 3392}` (11/10/2026). The rest off the map (the Leviathan, the Whisperer, Duke
Sucellus, the Phantom Muspah, the Abyssal Sire, the Corporeal Beast, the Fractured Archive, the PvP minigames' arenas)
send nothing, as anywhere else off the map. An instance whose template lies in the Wilderness is
still dangerous. Nothing changes on the relays: an instance's square is a square like any other.
Both relays (`relay/src/game.ts`'s `gameWhere` and channel.ts's `takeWhere`; relay-py's `game_where` and
`Channel.take_where`).

**At sea** (11/10/2026, Jace: "do we support location tracking when people are out at sea?"): Sailing (11/19/2025) put
a band of sea and islands round the old map (x 1024–3967, y 2496–4159): x 960–1023 to the west, 3968–4031 to the east,
y 2048–2495 to the south (Sunbleak Island, Last Light, Charred Island, Wyrmscraig, the Great Conch) and 4160–4223 to the
north, measured from the game's own map squares (the OSRS cache on archive.openrs2.org: the last before Sailing, 2341,
against launch day's, 2363, and the newest, 2757 of 11/03/2026, which adds nothing past them; Shortest Path's collision
map covers the same). The main map's bounds take it in, on the plugin (`Follow.X0`, `Y0`) and both relays (`WHERE_X`,
`WHERE_Y`). Underground still goes as the square above it only under the old map (y 2496 up), and under the new sea
only for the islands' caves the wiki places there (`Follow.SEA_CAVES`: Sunbleak Cave, Wyrmscraig Cavern, the Charred
Dungeon, the Great Conch's), since older squares south of the old underground lie under no sea. A player on a boat
stands in the boat's own world view; the plugin reads where the boat is on the main world (`OutsidePlugin.onMainWorld`,
as RuneLite's `Perspective.localToMinimap` does it, with `WorldEntity.transformToMainWorld`), never the deck's own
tiles (read as they were before, they gave the boat's own scene, not the sea, so nothing went). No sea is
dangerous: the wiki's Sailing page names no PvP or Wilderness sea, and boat combat is against monsters; the game's own
Wilderness and PvP-area flags are read at sea as anywhere.

- **Which world** (11/08/2026): with the plugin's "and my world" on as well as following, `where` carries the world the
  player was on with the square, from the same moment two minutes before: `{"x", "y", "world"}`. Never on its own,
  never from a PvP, Deadman or other dangerous world (the plugin clears `where` there, as anywhere dangerous), and
  switching "and my world" off clears `where` at once. `world` is a whole number from 1 to 999 (`WORLD_RANGE`): RuneLite
  has reported 301 and up for years, and the range takes lower numbers too in case Jagex numbers worlds from 1. Anything
  else in it (0, 1000, `"302"`, `true`, another key) and the whole `where` is ignored.
- A `where` that isn't `null` must be an object with exactly `x` and `y` (and `world`, above), each a whole number `k*128 + 64`, `x` from
  960 up to (not including) 4096 and `y` from 2048 up to 4224 (so the middles `x` 960–4032 and `y` 2112–4160); or exactly `{"x": 2368, "y": 4416}`, Zanaris
  (`WHERE_ZANARIS`, 11/08/2026: the plugin sends any tile there, x 2368–2495 and y 4352–4479, as that one place, and
  the globe draws it on its moon). Anything else is ignored, Zanaris's neighbouring squares included, and the rest of
  the message is taken as ever.
- The relay takes one every 50 seconds at most; one sooner is ignored (a `where` that was ignored for its shape doesn't
  count). `null` is taken whenever it comes.
- It's dropped when the game socket closes (with no newer one connected), when the plugin sends `"off": true` (logged
  out) or a `paused` that's a string (held for the player's safety: the plugin's `Kit.held`), whatever `where` that
  message holds; and on Cloudflare when the keeper gives the channel a new key or takes it back. On the Python relay a
  `where` that `--delay` held back isn't taken once the plugin's gone.
- It's held in memory only, with when it came, never in storage (the Worker's Directory stores each channel's row on
  the open list, 11/10/2026, but never this: above, "The open outstations"): a channel's object that sleeps (Cloudflare), or a
  restart, forgets it, and the plugin says it again within 3 minutes (the same square again every 3 minutes, a new one
  a minute after the last at most, and at once on a new connection). On Cloudflare a channel doesn't sleep while it's
  live (the transmitter's frames, and the desk's upload line each second, keep it awake), which is the only time it's
  shown; one that slept while dark shows `where` `null` from going live till the plugin's next, 3 minutes at worst.
- It's shown only in the open outstations' rows (`GET /channels`, above), while the channel's live and on that list (no
  watch code, not an invite waiting to be taken up), for 240 seconds after it came (`WHERE_FRESH_SECONDS` on the
  Worker, `--where-fresh-seconds` on the Python relay, shorten it for tests). It never reaches viewers, lookers or
  desks, the whole state someone just come gets, `GET /game`, the Array's events, the aether or the keeper's list.

**What they're doing** (11/08/2026, Jace's choice for the globe: the light's mood). With the plugin's "What I'm doing,
on the globe" on (off to begin with; it doesn't need following), it sends what the player was doing two minutes
before, as `{"game": {"activity": {...}}}`, one of exactly these:

| `activity` | when |
|---|---|
| `{"kind": "skilling", "skill": "<skill>"}` | the skill of the player's own latest experience drop, within about a minute (one of `ACTIVITY_SKILLS`: RuneLite's skill names, `Agility` to `Woodcutting` and `Sailing`; not the total) |
| `{"kind": "boss", "boss": "<boss>"}` | a fight with a boss: the NPC they're attacking, or one attacking them, is on the plugin's list (one of `ACTIVITY_BOSSES`, below); or inside the Gauntlet or the Corrupted Gauntlet, the whole run (11/10/2026) |
| `{"kind": "raid", "raid": "<raid>"}` | in a raid (11/09/2026), by the region they're in, an instance's as its template lies: one of `ACTIVITY_RAIDS`, `Chambers of Xeric`, `Theatre of Blood`, `Tombs of Amascut`, `The Fractured Archive` |
| `{"kind": "minigame", "minigame": "<minigame>"}` | in a minigame (11/09/2026), the same: one of `ACTIVITY_MINIGAMES` (below) |
| `{"kind": "fighting"}` | a fight with anything else (a hit theirs or on them within 15 seconds) |
| `{"kind": "idle"}` | none of those |

The bosses (`ACTIVITY_BOSSES`; made whole 11/10/2026 from RuneLite's `HiscoreSkill`, the official hiscores' list, with
each NPC's name as RuneLite's `NpcID` has it): every boss the hiscores count, but the raids (above) and Tempoross,
Wintertodt and Zalcano (minigames, below). The plugin's `Activity.BOSSES` gives each NPC's name the label that goes:
- Each as itself: Abyssal Sire, Alchemical Hydra, Amoxliatl, Araxxor, Bryophyta, Cerberus, Commander Zilyana, Corporeal
  Beast, Dagannoth Prime, Dagannoth Rex, Dagannoth Supreme, Deranged archaeologist, Duke Sucellus, General Graardor,
  Giant Mole, Hespori, K'ril Tsutsaroth, Kalphite Queen, King Black Dragon, Kraken, Kree'arra, Mad Angel, Maggot King,
  Nex, Obor, Phantom Muspah, Phosani's Nightmare, Sarachnis, Scurrius, Shellbane gryphon, Skotizo, Sol Heredit, The
  Leviathan, The Mimic, The Nightmare, The Whisperer, Thermonuclear smoke devil, TzKal-Zuk, TzTok-Jad, Vardorvis,
  Vorkath, Yama, Zulrah.
- The Wilderness's (11/10/2026, Jace: "Wilderness bosses should be included since we have the option for 'In the
  Wilderness too'"): Artio, Callisto, Calvar'ion, Chaos Elemental, Chaos Fanatic, Crazy archaeologist, Scorpia,
  Spindel, Venenatis, Vet'ion. The plugin sends anything from the Wilderness only with "In the Wilderness too" on, and
  then only `activity` (below).
- Several NPCs, or one by several names, as one: the six brothers as `Barrows`; Dusk and Dawn as `Grotesque
  Guardians`; Branda and Eldric as `Royal Titans`; the Blue, Eclipse and Blood Moons as `Moons of Peril`; Brutus and
  Demonic Brutus as `Brutus`; the Doom of Mokhaiotl, shielded or burrowed, as `Doom of Mokhaiotl`; the Hueycoatl, its
  tail and its body as `The Hueycoatl`; the Crystalline Hunllef as `the Gauntlet`, the Corrupted Hunllef as `the
  Corrupted Gauntlet`. Inside either Gauntlet (11/10/2026, Jace: "The Gauntlet and the Corrupted Gauntlet are bosses"),
  the whole run is its label, the preparation too, by the instance's region (not the lobby in Prifddinas the two share).

Those fought in an instance keep their labels there (11/09/2026), and a boss fight comes first wherever it is (Jad in the
Fight Caves is `TzTok-Jad`, Zuk in the Inferno `TzKal-Zuk`, Sol Heredit in the Colosseum `Sol Heredit`, then the
minigame again a minute after the last hit; Olm in the Chambers is the raid: Olm isn't on the list).

The minigames (`ACTIVITY_MINIGAMES`, 11/09/2026): Barbarian Assault, Barracuda Trials (11/10/2026), Blast Furnace, Brimhaven Agility Arena, Castle
Wars, Clan Wars, Emir's Arena, Fishing Trawler, Giants' Foundry, Guardians of the Rift, Hallowed Sepulchre, Last Man
Standing, Mage Training Arena, Nightmare Zone, Pest Control, Pyramid Plunder, Rogues' Den, Sorceress's Garden, Soul
Wars, Temple Trekking, Tempoross, Tithe Farm, Trouble Brewing, Volcanic Mine, Wintertodt, Zalcano, the Colosseum, the
Fight Caves, the Fight Pits, the Inferno. The plugin tells a raid or a minigame by RuneLite's own Discord plugin's
region ids (`DiscordGameEventType`), less its rat pits and games room, and Barracuda Trials, Sailing's obstacle courses
at sea, which have no region there, by the game's own in-trial flag (`VarbitID.SAILING_BT_IN_TRIAL` not 0, as the
Sailing plugin reads it; the plugin's `Activity.TRIALS`). Sailing's bosses, the Shellbane gryphon and the Mad Angel, are
bosses like the rest; its other work (courier and bounty tasks, charting, salvaging, trawling) is skilling Sailing. Tempoross, Wintertodt and Zalcano, which it and the
hiscores count as bosses, are minigames here (11/10/2026); their kills still pop on the Array as any boss's do (below,
"The Array's events"). The PvP minigames (Last Man Standing, Castle Wars, Clan Wars, Soul Wars, the Fight Pits, Emir's
Arena) are minigames like the rest since 11/10/2026 (Jace: "they are just minigames"). relay-py's tests check the
plugin's, the Worker's and the Python relay's lists (skills, bosses, raids, minigames) match.

The plugin sends it as following sends `where` (two minutes behind, at most once a minute, the same again every 3
minutes; `{"activity": null}` at once on going anywhere dangerous, and when it's switched off; then
nothing till two minutes clear of it). In the Wilderness (11/10/2026), only with the plugin's "In the Wilderness too"
on, since the player's already chosen to transmit there: then `activity` goes from there as anywhere else, two minutes
behind, while `where` (and its world) and the stars stay off there whatever the setting. Never from a PvP, Deadman,
high-risk or Bounty Hunter world. Both relays take it the same way (`relay/src/game.ts`'s `gameActivity` and
channel.ts's `takeActivity`; relay-py's `game_activity` and `Channel.take_activity`):
- Anything not exactly one of those shapes (another key, a skill or boss not on the list, a different case) is ignored,
  and the rest of the message is taken as ever. `null` clears it whenever it comes.
- One every 50 seconds at most; one sooner is ignored (one ignored for its shape doesn't count).
- Dropped when the game socket closes, when the plugin sends `"off": true` or a `paused` that's a string, and on
  Cloudflare when the keeper gives the channel a new key or takes it back.
- Held in memory only, with when it came, never in storage (as `where`: the Directory's stored rows leave it out). Shown
  only on `GET /channels` (above), while the channel's live and on that list, for 240 seconds after it came (the same `WHERE_FRESH_SECONDS` / `--where-fresh-seconds`). Never to viewers or
  lookers, `GET /game`, the Array's events, the aether or the keeper's list; the channel's own desks get it, and `where`, only
  as `{"mine"}` (below, "Your year").

**The exact path** (11/10/2026, Jace: "your year" records the exact tile, privately; `docs/record.md`, "The exact
path"): with the plugin's "Record my exact path for my year" on (off to begin with, its own warning), the tile the
player's on goes as `{"game": {"here": {"x", "y", "plane", "world"}}}`, at once (not two minutes behind), when it
changes, at most one every 4 s while they move and once when they stop; placed as following places it (at sea, the
boat's tile; underground, the tile above; an instance where its template lies or at its entrance; Prifddinas where the
world map draws it; Zanaris as `{"x": 2368, "y": 4416}`); `{"here": null}` somewhere dangerous (following's rules), off
the map, and when it's switched off. Both relays (`gameHere`, channel.ts's `takeHere`; relay-py's `game_here`,
`Channel.take_here`):
- take it only on the game socket, exactly `{"x", "y", "plane", "world"}`: `x` a whole number from 960 up to (not
  including) 4096 and `y` from 2048 up to 4224 (a tile of the main map or its seas), or exactly Zanaris's; `plane` 0 to
  3 (`HERE_PLANES`); `world` 1 to 999 (`WORLD_RANGE`). Anything else is ignored, and the rest of the message taken as ever.
- take one every 2 s at most (`HERE_EVERY_MS` / `HERE_EVERY_S`; the rest ignored); `null` whenever it comes;
- pass each one taken at once to the channel's desks only, as `{"mine": {"here", "at"}}` (below, "Your year"), and
  `{"mine": {"here": null, "at"}}` when it's cleared, the plugin logs out (`"off": true`) or holds for the player's
  safety (`paused`), the game socket closes, or the keeper gives a new key or takes the channel back (only if a tile was
  passed on since);
- never keep it, in memory or in storage: never on GET /channels, GET /game, a viewer's, looker's, transmitter's or the
  game socket's lines, the Array or the aether; a desk that connects later gets none of it.

**Shooting stars** (11/08/2026): with the plugin's "Shooting stars I find" on, a crashed star the player saw goes as
`{"game": {"star": {...}}}`, for the relay's list of stars (below, "Shooting stars"). Never one of the game's fields: the
list of stars shows it, with the outstation that found it first.

**The read:** `GET /game/<channel>?code=<watch code>`, for a page while the outstation's dark:
- 200 `{"game": {...the whole state}, "at": <ms, when it last changed>, "connected": bool}`
- 404 `{"error": "no game state for that outstation"}` when there's none
- 403 `{"error": "wrong watch code"}` when the channel has a watch code and it isn't the one given, as watching would be
- 400 for a channel that can't be one, 405 for anything but `GET`

Anyone may read it (any origin). The Python relay's public side (the Funnel's port) allows the read, since it's what
viewers see anyway, and refuses the socket.

**What's kept, and for how long:** the state stays when the stream ends and when the plugin goes, so a page shows the
last of it while the player's away, until the plugin next changes it.
- On Cloudflare: in memory, and saved in the channel's storage at most once a minute while messages come (the first at once, then a minute after the last save), and when the game socket closes; a channel that's slept starts from what was saved, so a restart of the channel's object while the plugin's sending loses at most that minute. Forgotten when the keeper gives the channel a new key or takes it back.
- On the Python relay: in memory only, as everything there, while the relay runs. `--delay` holds the game's messages back too.

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

## Your year (11/10/2026)

A year of the player's own travels and activity, recorded by their app, kept on the relay **encrypted with a key only
their own computers hold**, and a public summary they may choose to post. The design, with the exact format the page
must match: `docs/record.md`. Both relays: the Worker in a Durable Object of its own per channel (`Records`,
relay/src/record.ts; binding `RECORD`, migration `v3`), relay-py in a folder (`--records`, ~/Documents/ghillie
portal/records; "" keeps none).

**The owner's own feed, to the desk only.** The relay passes the channel's own following square, activity and Array
events to its desk sockets (the app's chat window: only the station key opens one), live or dark, with or without a
watch code, and to nothing else (no viewer, looker, transmitter, game socket, GET /game or the aether; GET /channels is
as it was):

    {"mine": {"where": {"x", "y"[, "world"]} | null, "at": <ms>}}      each one the relay takes (one every 50 s), null when dropped
    {"mine": {"activity": <label> | null, "at": <ms>}}                  the same
    {"mine": {"event": {"kind", …}, "at": <ms>}}                         each Array event of a field report (array.ts's arrayEvents)
    {"mine": {"here": {"x", "y", "plane", "world"} | null, "at": <ms>}}   the exact path (below), each one taken (one every 2 s), never kept

`at` is the relay's time of taking it, the same for every desk of the channel. A desk that connects gets the `where` and
`activity` held now, with their own `at` (never a `here`: none is held). Live and dark are the status line's `live`, as ever.

**The record** (ciphertext only: a body must start with the format byte, 1, and be at least 30 bytes; a plaintext one is
refused):

    POST   /record/<channel>/token?key=<key>   {"read": <token>, "write": <token>}   204; 400 not two 43-character
                                               base64url tokens; 403 wrong key; 409 past 4 pairs. Only their SHA-256 kept.
    PUT    /record/<channel>/<id>?key=<key>    a sealed chunk (application/octet-stream), <id> 22 base64url characters
                                               201 {"id", "at", "size"}; 413 over 256 KB; 415 not sealed; 429 over 240 an
                                               hour; 507 past 2,000 chunks or 32 MB
    DELETE /record/<channel>/<id>?key=<key>    204; 404 no such chunk
    DELETE /record/<channel>?key=<key>         204: every chunk, every token pair and the public summary
    GET    /record/<channel>                   Authorization: Bearer <read token>
                                               {"chunks": [{"id", "at", "size"}, …] in id order, "bytes"}; 403 otherwise
    GET    /record/<channel>?data=1[&after=<id>]   the same, each chunk with "data" (base64), up to 4 MB of data, and
                                               "next" (the id to go after, or null at the end)

A chunk not uploaded again in 400 days goes. Any origin may read (CORS `*`, `OPTIONS` answered). The keeper taking a
channel back (DELETE /admin/channels/<channel>, the Worker) deletes its record and summary too; a new key from the
keeper leaves them as they are.

**The public summary**, a fixed shape checked part by part (`docs/record.md`, "The public summary"), stored as checked
(its parts in their order, numbers whole), at most 5 MB. Its parts: `v`, `tz`, `range`, `totals`, `days`, `heat`, `paths`,
`activities`, `hours`, `places`, `moments`, `log`, `replay` (`log`, the log diary: up to 2,000
`{"name"?, "t"?, "x"?, "y"?, "act"?, "clip"?}`, its place at the heat's own cell size or 128; `tz`, `days` and the heat cells' and paths' `act` since 11/10/2026;
and, for the exact path, `heat` as `{"cell": 8|16|32|64|128, "cells"}` with each cell's middle, and steps in paths and
the replay at any tile):

    PUT    /year/<channel>?key=<key>, or Authorization: Bearer <write token>   200 {"year", "at"}; 400 {"error"} naming the
                                               part; 403; 413
    GET    /year/<channel>                     anyone: 200 {"year", "at"}; 404 none posted
    DELETE /year/<channel>  (as PUT)           204

## Messages between outstations (11/06/2026)

One outstation's window to another's, through the relay, never on either stream (the client's chat tabs: ui.rs, Thread).
On the desk socket, both relays:

    → {"dm": {"to": "<channel>", "text": "..."}}            from a desk only (a viewer's is ignored)
    ← {"dm_sent": {"to", "text", "at", "held"}}              held: their outstation's closed, the relay keeps it for them
    ← {"dm_failed": {"to", "text", "why"}}                   no such outstation on this relay, your own, or past 20 a minute
    ← {"dm": {"from": "<channel>", "name": "<their chat name>", "text", "at"}}   to the other outstation's desks

`at` in ms. A message for an outstation with no desk connected is held, at most 20 and 7 days each, and handed over as its
desk next connects. Both outstations must be on the same relay.

## The aether (11/07/2026)

One room for every outstation and every visitor on the relay, as IRC's channels were (Jace: "a global chat… seeing people
spam ascii art and stuff. reminds me of IRC and AOL Instant Messenger. i want this to feel like a real network people are
joining"). Both relays: the Worker's `relay/src/aether.ts`, one Durable Object for the whole relay; relay-py's `Aether`
and `Relay.aether_socket`. Outside shows it as a tab beside a transmission's chat, the client as a tab of its own
(client/src/aether.rs).

    ws(s)://<relay>/aether?name=<name>&device=<random id>                 a visitor
    ws(s)://<relay>/aether?channel=<channel>&key=<key>&v=<its version>    an outstation, under its channel's name
    GET <relay>/aether                                                     the room before joining: {"lines", "here"}

**Joining.** With `channel` it's an outstation's socket; without, a visitor's.
- An outstation: its version and its key are checked as its desk's would be: 4026 for a client older than `MIN_CLIENT`
  (`--min-client`), then 4001 for a key under 8 characters, 4003 for the wrong one (on Cloudflare also for a channel
  nobody handed out; on the Python relay a key claims a channel nobody has, as a desk's does), and on Cloudflare 4029
  past 10 wrong keys a minute from one address. Its name in the room is its channel. A `channel` that can't be one
  ("The sockets", above) isn't upgraded: 400.
- A visitor: `name`, with its runs of spaces made one and its ends trimmed, must then be 1 to 20 letters, digits, spaces,
  `_`, `-` or `.`, ASCII only; `device` is the random id its page keeps, 8 to 64 printable characters with no spaces
  (what a ban holds on to). Either wrong: 4002. A name that's an outstation's, case and spaces aside ("Moss Fern" is
  `mossfern`'s), is refused with 4009, "that name belongs to an outstation": on Cloudflare any channel handed out (the
  Directory's list), on the Python relay any channel claimed. A visitor's `v=` is never looked at.
- Banned (below, the keeper): 4033 "banned from the aether", a visitor by its device, an outstation by its channel.
- On Cloudflare, 1013 when the relay couldn't check the key or the name just then: try again.

**On joining** it hears `{"aether_so_far": [line, …]}`, the last 100 lines, oldest first, and then `{"aether_here": n}`.

**It sends** text, `{"say": text}`. Anything else is thrown away, and anything over 8 KB isn't read.
- A line may be several rows (ASCII art). Control characters are taken out, all but `\n` (C1's too, and the direction
  and zero-width ones); each row is cut to 80 characters and its trailing spaces trimmed (its leading spaces stay, for
  the art's shape); empty rows at the start and the end are dropped; and the first 12 rows are kept. So a line is at
  most 960 characters, the newlines between its rows aside. Characters are code points. A row over 80 is cut, not
  refused, and a 13th row and on are left out.
- A line that's empty after that is ignored, and isn't counted.
- At most one line every 2 s and 15 a minute, counted for the socket and for whoever it is (a visitor's device, an
  outstation's channel), so opening another socket or reconnecting doesn't start the count again. Past either, the line
  goes nowhere, and only that socket hears `{"aether_slow": s}`: the seconds to wait, rounded up to a tenth.

**Everyone in the room hears:**
- `{"aether": {"id", "name", "text", "from", "at"}}`: a line, its sender included. `id` goes up with each line; `from` is
  `"outstation"` or `"visitor"`; `at` in ms. Never a device or a key.
- `{"aether_here": n}`: how many are here, each visitor's device and each outstation counted once, however many sockets
  it has open. Sent to everyone as it changes, at most once every 2 s (a change in between waits its turn); whoever's
  just come hears it at once.
- `{"aether_unsay": id}`: a line the keeper's taken back (below).

Nothing an aether socket sends reaches any channel, and nothing from a channel (its chat, its desk, its viewers) reaches
the aether. The Python relay's `--delay` doesn't hold it back.

**The read:** `GET /aether` answers `{"lines": [the last 30, as above], "here": n}`, for a page to show the room before
it joins. Anyone may read it (any origin), and it may be cached for 5 s (`Cache-Control: public, max-age=5`). 405 for
anything but `GET`.

**What's kept:** the last 100 lines, each with who wrote it (for the keeper), and the bans.
- On Cloudflare: in memory, and in the Aether object's storage as one row, written at most every 5 s while lines come
  (soon after the first, then 5 s after the last write) and when the room empties; the bans as they change. A restart
  of the object while lines come loses at most those 5 s. Each sender's count for the rate is in memory (a sleep
  forgets it, which only loosens it); each socket's own outlives a sleep.
- On the Python relay: in memory, as everything there, while the relay runs.

**The keeper** (`Authorization: Bearer <ADMIN_TOKEN>`; on the Python relay `--admin-token`, 16 characters at least,
without which they're all refused):
- `GET /admin/aether`: `{"lines": [{"id", "name", "text", "from", "at", "who"}, …], "bans": [{"who", "name", "at"}, …]}`.
  `who` is `"device:<id>"` for a visitor and `"channel:<channel>"` for an outstation.
- `POST /admin/aether/unsay` with `{"id"}`: the line taken back. Everyone hears `{"aether_unsay": id}`, and it's gone from
  what's kept (and from the read). Answers `{"unsaid": [id]}`, or 404 `{"error": "no such line in the aether"}`.
- `POST /admin/aether/ban` with `{"id"}`: whoever wrote that line is banned, a visitor by its device and an outstation by
  its channel. Every line of theirs still kept is taken back (each `{"aether_unsay": id}`), and their sockets are closed
  with 4033 "banned from the aether"; trying again is refused the same. Answers `{"banned": who, "unsaid": [ids]}`, or
  404 as above.
- `POST /admin/aether/unban` with `{"who"}` (as the list has it): let back in. Answers `{"bans": [...]}`, or 404
  `{"error": "nobody's banned by that"}`.
- 403 without the token, 405 for another method, 413 past 4 KB.

The Python relay's public side (the Funnel's port) lets visitors in and allows the read; it refuses an outstation's
socket and the keeper's routes, whatever key or token comes with them.

## The Array's events (11/08/2026)

What's happening across the open outstations, for outside.wiki's front page, which shows every outstation in the Array
as a light and pops its icon with "+1" as one gets a kill, a level, a pet, a big drop, a collection log slot or a clue.
Both relays: the Worker's `relay/src/array.ts` (kept by the Directory, the object that answers `GET /channels`);
relay-py's `array_events` and `ArrayFeed`.

    GET <relay>/array/events                  the last 50 at most: {"events": [...], "next": <cursor>}
    GET <relay>/array/events?since=<cursor>   the ones after the cursor, 100 at most, oldest first

**An event** is `{"id", "at", "channel", "kind", ...}`: `id` (below, "The cursor"), `at` when the relay took it (ms),
`channel` the outstation's, and with each `kind` its own fields:

| kind | from | its fields |
|---|---|---|
| `"kill"` | a `KILL_COUNT` report | `boss`, `kc` (the kill count): any boss's name Dink gives, the minigames the hiscores count as bosses too (11/10/2026: Dink names them from the game's messages, `Wintertodt`, `Tempoross`, `Zalcano`, the Gauntlets as `Crystalline Hunllef` and `Corrupted Hunllef`) |
| `"level"` | a `LEVEL` report: one event for each skill it levelled | `skill`, `level` |
| `"pet"` | a `PET` report | `name`, if Dink gave one |
| `"loot"` | a `LOOT` report whose drop is worth 1,000,000 coins or more in all (less isn't one) | `name` (the drop's best item, by quantity × price; the first, in a tie), `value` (the drop's whole value) |
| `"clog"` | a `COLLECTION` report (a new collection log slot) | `name` (the item), if given |
| `"clue"` | a `CLUE` report | `tier`, `count` (how many of that tier), each if given |

They're made from the numbers each report carries (its `data`, above in "Dink": what `reportData` / `report_data` keep),
the same as the stream's summary is, so a report that comes as a Dink post and one that comes over the transmit socket
count alike. Any other kind of report makes none, and nothing that names another player is in one (a drop's `source`
never is). A name is one line, 60 characters at most; a number is whole, 0 to 2⁵³ − 1 (one that isn't leaves its event,
or that one skill, out; a kill needs its boss and its count). Events are ordered as the reports came, a report's levels
in the order Dink gave them.

**Which reports count** (privacy): only those that reach the relay while their channel is **live** (transmitting) and
**open** (handed out, with no watch code: the same test that puts it on `GET /channels`). A dark outstation's reports,
and a watch-coded one's, never make an event, then or later. What the relay already refuses or ignores (the wrong key,
a kind not in `REPORTS`, the same Dink post twice) makes none either. On the Python relay with `--delay`, a report counts
as it reaches viewers, so one that comes in the delay's last moments before the outstation goes dark doesn't.

**The cursor:** `next` is what to send as `since` next time. It's an event's `id`, which only goes up: each is about the
moment the event came, in ms (one more than the last, when that's later), so ids keep going up when the relay (or the
Worker's object) starts again, and a cursor from before still works. Treat it as a number with no meaning of its own.
- With events in the answer, `next` is the last one's `id`.
- With none: `since` back (nothing new yet), or the newest `id` there is if `since` is past it. Without `since`, the
  newest `id` there is (or, before any event, the moment the feed began).
- So a page reads once without `since` for what's just happened, then with `since` set to each answer's `next`. Past 100
  new ones, `next` is the 100th's, and the next read has the rest.
- `since` must be digits, at most 2⁵³ − 1: anything else is 400 `{"error": "since is a cursor: an earlier read's next"}`.
  `since=` (empty) is the same as none.

**The limits:**
- One outstation adds 10 events a minute at most (counted over the last 60 s); past that, they're dropped, so one noisy
  plugin can't flood the feed. A report of three levels with room for one adds the first.
- The last 500 are kept, the oldest going first.

**The read:** anyone may read it (any origin: `Access-Control-Allow-Origin: *`), and it's never cached
(`Cache-Control: no-cache`). 405 for anything but `GET`. The Python relay's public side (the Funnel's port) allows it.

**What's kept:** the last 500 events, the newest `id` given, and when each outstation's events of the last minute came
(for the limit). On Cloudflare in the Directory's storage (since 11/10/2026: the key `array`, one value, written with
each report that adds an event and read back once each time the object wakes), so the feed, its ids and the limit
outlive the Directory's sleeps and deploys. (Before, it was kept in the Directory's memory only. That object holds no
sockets, so Cloudflare puts it to sleep about 10 seconds after its last request, and an event showed for those seconds
and was gone: seen live on 11/10/2026, a Giant Mole kill and a pet, with `GET /array/events` empty minutes later.) On
the Python relay in memory, while it runs: a restart starts it empty, and ids still go up.

**The keeper's backfill** (11/10/2026, Jace: his pet, lost to the Directory's sleeps that morning, put back on the
record): `POST /admin/array` with the keeper's token (`Authorization: Bearer <ADMIN_TOKEN>` on the Worker,
`--admin-token` on the Python relay; 403 without it, 405 for anything but `POST`), for a real event the feed lost:

    {"channel": "<channel>", "events": [{"kind": "pet", "name": "Baby mole", "at": 1794286233000}, ...]}

- Each event exactly as a report would have made it (the table above): one of the six kinds, only its own fields, each
  as the relay would have written it (a name one line of 60 characters at most, as it stands; a number whole, 0 to
  2⁵³ − 1; a kill's `boss` and `kc`, a level's `skill` and `level`, and a drop's `value` of 1,000,000 or more, needed),
  and `at`, when it happened (ms), not after now. 50 at most at once. Anything else is 400 `{"error"}`, naming the
  event and why, and nothing's added.
- Only for a channel handed out (404 otherwise) and on the open outstations' list, no watch code and not an invite
  waiting (409 otherwise): only such a channel's reports ever make events.
- Each goes in its place by `at`, after any event with the same time or earlier, with an `id` between its neighbours'
  (its own `at` when that fits), so the feed stays in `id` order and every cursor still works. One with no room there
  (its neighbours' ids one apart) gets a new `id` at the end, as an event that came now would, and keeps its own `at`:
  so the feed's `at`s aren't always in order, its `id`s always are. A page already past its place doesn't get it (it's
  history, not news); a read without `since`, or from a cursor before it, does.
- Not counted against the outstation's 10 a minute, and it doesn't use any of them. When the feed's full (500), none
  older than its oldest (it would go at once): 400.
- 200 `{"events": [...]}`: each as kept, with its `id` and `channel`. It's kept as the rest are (above).

**For tests:** `POST /test/forget` (204) puts the Worker's Directory to sleep as Cloudflare does, everything in its
memory gone, so a test can see what its storage brings back. Only with the Worker's `TEST_FORGET` var at `"1"` (never on
Cloudflare: without it the route is 404) and relay.py's `--test-forget` (where it forgets nothing: the process's memory
is the keeping). `tests/protocol_test.py`'s `Kept`, against either.

## Shooting stars (11/08/2026)

The crashed shooting stars the Outside plugins have found, for outside.wiki's globe, with the outstation that found each
first (11/09/2026, Jace: "not sure where you got the idea stars need to be anonymous"). Both
relays: the Worker's `relay/src/stars.ts` (kept by the Directory, the object that answers `GET /channels`), and
`game.ts`'s `gameStar`; relay-py's `game_star` and `StarList`.

    GET <relay>/stars        {"stars": [{"world", "x", "y", "site", "tier", "found", "seen", "by", "reports"}, ...]}

**The plugin** (with "Shooting stars I find" on; off to begin with) sends, on its game socket:
- `{"game": {"star": {"world", "x", "y", "tier"}}}`: a star it saw two minutes before. `world` the world it's on (1 to
  999, as `where`'s), `x` and `y` the middle of the 128-tile square of the main map it's in (as `where`'s; never
  Zanaris), `tier` its size, 1 to 9 (the crashed star's object: RuneLite's `STAR_SIZE_ONE_STAR` to
  `STAR_SIZE_NINE_STAR`, ids 41229 to 41020).
- `{"game": {"star": {"world", "x", "y", "gone": true}}}`: it was mined out while the player watched (not a hop or a
  new scene), two minutes before.
- Either with `"credit": true` (11/09/2026, Jace: opt-in): the player's switched "Name me as a star's finder" on, so
  the relay may name their outstation as the star's finder. Without it, the report is taken and counted as ever, but
  names nobody. `credit` that isn't `true` makes the whole `star` not its shape.
- Either with `"site"` (11/09/2026): its landing site's name, when the star's tile is one of the sites in Shooting Star
  Info's table (its `Location.java`, BSD 2-Clause, Copyright (c) 2022, Cute Rock; the plugin's `Sites.java`), less its
  Wilderness sites and Prifddinas's: 71 in all. The relays take only those names (`STAR_SITES`), each with the square
  it's in, and only in that square; a star elsewhere goes without one.
- At most one report of a star every 3 minutes (the newest of what's two minutes old), and only of a star seen from
  somewhere safe: never one lying in the Wilderness (Jace's decision, 11/09/2026: never reported), nor seen from it
  (whatever "In the Wilderness too" says), a PvP area or world, or Deadman (Ferox Enclave and the PvP minigames are safe, 11/10/2026). A star seen and gone within the two minutes is never told of.

**The relay** takes exactly those shapes (anything else in `star` is ignored, and the rest of the message taken as
ever), not from a message with `"off": true` or a `paused` that's a string, and keeps:
- one star for each world and square, however many told of it: several outstations telling of one star make one. Its
  finder (`by`) is the first of them whose report carried `"credit": true` and that's on the open outstations' list
  (handed out, no watch code, not an invite waiting to be taken up), or `null` while none is: an outstation that didn't
  ask for the credit, or a hidden one, is never named, only counted. `reports` is how many
  outstations have told of it. Its `site` is the first one told, or `null`.
- a lower or equal tier replaces the kept one's (and `seen`); a higher one is older news and is ignored. A star's only
  ever mined down.
- `gone` takes it off the list, and for 10 minutes after, a report of it (another outstation's, as late) doesn't bring
  it back.
- each star for 2 hours after it was first told of (`found`), however often it's seen since: about a star's life.
- 1,000 at most at once; past that, a new one's ignored.
- one outstation tells of 30 an hour at most (counted over the last hour, by channel, forgotten an hour after its last
  report); past that, its reports are ignored.

**The read** answers every star kept, by world, then `x`, then `y`: `world`, `x`, `y`, `site` (or `null`), `tier`,
`found` (when it was first told of, ms: two minutes or more after it was seen), `seen` (when its tier was last told
of), `by` (its finder's channel, or `null`) and `reports`. Anyone may read
it (any origin: `Access-Control-Allow-Origin: *`), it's never cached, and it's 405 for anything but `GET`. The Python
relay's public side (the Funnel's port) allows it.

**What's kept:** the stars, and for each the channels that told of it (for `reports`, and so one telling again isn't
counted twice), the gone ones for their 10 minutes, and when each outstation told of one in the last hour (for the
30). On Cloudflare in the Directory's storage (since 11/10/2026: the key `stars`, one value, written when a report
changes it or a star passes its two hours, and read back once each time the object wakes; the Cron Trigger's look every
5 minutes takes a star past its two hours out of storage, though nobody's read or told of one since). Before, it was in
the Directory's memory only, and that object sleeps about 10 seconds after its last request (above, "The Array's
events"). On the Python relay in memory, while it runs. `POST /test/forget` (above) shows it.

## The almanac (11/09/2026)

What the globe on outside.wiki shows of the game around the outstations: Jagex's game updates, the worlds (how many are
playing, and any league or deadman season on worlds of its own) and the market's day. The relay fetches each source
itself, on its own schedule, and every visitor reads the same copy, so no page ever asks Jagex or the Wiki (Jace's rule:
the Wiki is fed up with sites scraping it constantly; nothing heavy, fetched once and shared). Both relays: the Worker's
`relay/src/almanac.ts` (kept by the Directory, the object that answers `GET /channels`, refreshed by a Cron Trigger);
relay-py's `almanac.py` (refreshed by a task in the relay). The globe's side is ghillie's
`tools/release-kit/prototypes/outside-splash/almanac.js` (its `fromRelay`).

    GET <relay>/almanac         {"at", "updates", "worlds", "market", "sources"}
    GET <relay>/admin/almanac   the keeper's: each source's schedule and last error
    POST <relay>/admin/almanac  the keeper's: every source asked now, whatever its schedule (then the same)

**The answer**, with `at` when it was made (ms):
- `updates`: Jagex's newest news posts, newest first, 8 at most (one without a date last):
  `[{"id", "title", "url", "at", "category", "summary"}]`. `id` stays the same for the same post, fetch after fetch:
  the feed's `guid` (one line, 200 characters at most), else its link when that's on runescape.com, else its title,
  `@` and its `at` (`"<title>@null"` without a date); tell posts apart by it. `url` is the post's link only when it's on runescape.com (`https`
  or `http`), else `null`; `at` the post's date (ms) or `null`; `category` as the feed has it ("Game Updates",
  "Community", "Future Updates"…) or `null`; `summary` its description as one line of text, 200 characters at most (an
  ellipsis when cut), or `null`. Titles are one line, 140 at most.
- `worlds`: `{"players", "worlds", "at", "seasons", "list"}` (`seasons` always there: `[]` while none runs). `players` all the players on every world that's up, and
  `worlds` how many worlds are up; `at` when the list was fetched (ms). `seasons` (in this order, each only while it has
  a world): `[{"kind": "leagues" | "deadman", "name", "players", "worlds", "activity"}]`, `name` the same as `kind`,
  `activity` the commonest of its worlds' activity texts (between equals, the lowest world's) or `null`. Both are
  `SEASONAL` worlds (RuneLite's `WorldType`: "for leagues and seasonal deadman"): a league's those not flagged
  `DEADMAN`, deadman's those flagged it. The permanent deadman world (on the real list, 11/09/2026: world 345,
  "Deadman - Permanent", flagged `MEMBERS` and `DEADMAN` only) is never a season, nor any `BETA_WORLD`, `NOSAVE_MODE`
  or `TOURNAMENT` world. (No season ran on 11/09/2026: the real list's `seasons` was `[]`.)
  `list`: every world, by number, `{"world", "players", "region", "location", "activity", "types"}`: `players` `null`
  while it's down, `region` an ISO 3166 alpha-2 code from its location (`US`, `GB`, `AU`, `DE`, `BR`, `JP`, `SG`, `ZA`;
  `null` for one RuneLite doesn't know), `location` the list's own number for it, `activity` its one-line text (60
  characters at most, `""` for none) and `types` the names of its flags (below).
- `market`: the day's ticker, 10 at most: `[{"item", "at", "id", "price", "change", "volume", "value", "why"}]`. First the 6
  biggest moves on the day before (by the size of `change`, `"why": "mover"`), picked among the 200 items most traded
  in gp that day, then the 4 most traded in gp of the rest (`"why": "traded"`). `at` is the same on every line: when
  the prices are from, the start (UTC midnight, ms) of the Wiki's day they're the averages of (its `/24h` `timestamp`),
  not when the relay fetched them. It changes only when the Wiki's day does (about once a day, a day or two behind), so
  a page that keeps the last `at` it saw can tell a new market from the same one read again. `item` its name (`"item <id>"` when the
  Wiki has none), `id` the item's, `price` the day's average price in gp (the Wiki's high and low averages, each
  weighted by its volume), `change` in % on the day before's average, to one decimal (`null` for a traded item with no
  day before), `volume` how many traded in the day (high and low together), `value` price × volume, in gp.
- `sources`: `{"updates", "worlds", "market", "names"}`, each `{"at", "ok", "age_s"}`: `at` when it was last fetched
  well (ms, `null` for never), `ok` whether the last try was (`null` before any), `age_s` seconds since `at`. `market`
  also has `day`, the start of the Wiki's day its numbers are (ms, UTC midnight). `names` is the Wiki's item names.
- A part never fetched well is `null`. A source that fails keeps its last good part in the answer, with `ok` false and
  its `at` (so its age) as it was: one failing never takes the others down.

**The sources and their schedule** (the request counts: relay/README.md, "The almanac's sources"):
- **Jagex's news:** `https://secure.runescape.com/m=news/latest_news.rss?oldschool=true`, every 20 minutes. RSS 2.0, in
  the encoding its XML declaration names (Jagex's says ISO-8859-1, read as windows-1252, as browsers read that label;
  without one, UTF-8): each `<item>`'s `title`, `link`, `pubDate` (RFC 822), `category` and `description` (CDATA taken as it is, entities
  decoded, tags dropped). A feed that declares a DOCTYPE or an ENTITY is refused. The first 50 items are read.
- **The world list:** `https://oldschool.runescape.com/slr.ws?order=LPWM`, every 5 minutes. The game's own binary list,
  read as RuneLite read it (its http-service's `WorldsService`, until RuneLite 1.8.0): big-endian, a 4-byte length (of
  what follows; bytes past it are ignored: the real answer is always 30,000 bytes, zeros after the list), the worlds' count (2 bytes), then each world's number (2), its flags (4),
  its address and its activity (each ended by a 0 byte, Latin-1), its location (1) and its players (2, signed: -1 while
  it's down). The address isn't passed on. The flags' names and values are RuneLite's own (`runelite-api`'s
  `WorldType`, the same in the `http-api` 1.2.26 the client builds with): `MEMBERS` 1, `PVP` 1<<2, `BOUNTY` 1<<5,
  `PVP_ARENA` 1<<6, `SKILL_TOTAL` 1<<7, `QUEST_SPEEDRUNNING` 1<<8, `HIGH_RISK` 1<<10, `HARDCORE_MAIN` 1<<11,
  `LAST_MAN_STANDING` 1<<14, `BETA_WORLD` 1<<16, `LEGACY_ONLY` 1<<22, `EOC_ONLY` 1<<23, `NOSAVE_MODE` 1<<25,
  `TOURNAMENT` 1<<26, `FRESH_START_WORLD` 1<<27, `DEADMAN` 1<<29, `SEASONAL` 1<<30 (any other bit is ignored). The
  locations are RuneLite's `WorldRegion` (`http-api` 1.2.26): 0 US, 1 GB, 3 AU, 7 DE, 8 BR, 9 JP, 10 SG, 12 ZA. A list
  that's cut short, says another length than its size, or has no worlds (or more than 1,000) is refused whole.
- **The Wiki's prices:** `https://prices.runescape.wiki/api/v2/osrs/24h` and `/mapping`. The Wiki's page (RuneScape:
  Real-time Prices) asks every user of its API to move from `/api/v1/osrs` to `/api/v2/osrs` (v1 "may be removed in
  the future"; v2's averages may have two decimals), so the relay asks v2. `/24h` (`{"timestamp", "data": {id:
  {"avgHighPrice", "highPriceVolume", "avgLowPrice", "lowPriceVolume"}}}`) is a whole UTC day, the same all day (its
  `Expires` is the next UTC midnight): it's asked 15 minutes after UTC midnight and, while its `timestamp` is still the
  day kept, again each hour until the new day's there. The day before it (to measure the change against) is the day
  kept when it's the one before, so normally nothing more is asked; else `/24h?timestamp=<the day before>` once. Only
  `/24h` and `/mapping` are asked, never one item at a time. `/mapping` (`[{"id", "name", …}]`) once a day, for the
  names.

**Every request** carries the relay's own User-Agent, `outstation-relay/1.0 (the almanac for outside.wiki's globe:
fetched on a schedule and shared by every visitor; https://github.com/jhmcubed/outstation)`, the one thing the Wiki's
acceptable-use policy asks; nothing else goes with it: no visitor's address, header or anything a page sent. Each answer
is 8 MB at most; a request gives up after 15 seconds.

**Failures** back off: after a source's nth failure in a row, it's asked again after its usual wait doubled n − 1 times,
to a cap (the news: 20 minutes, then 40, 80, then every 2 hours; the world list: 5 minutes up to an hour; `/24h`: an hour
up to 6; `/mapping`: an hour up to a day). A good answer puts it back on its usual schedule.

**The read never asks a source,** with one exception: the first read when a source has never been tried (a cold start:
the Worker's first read after it's deployed, before the cron's first run; relay.py's first, if it comes before its own
schedule's first round) waits for one refresh, and every read that comes meanwhile waits for that same one. After that,
only the schedule asks: the Worker's Cron Trigger every 5 minutes (`wrangler.jsonc`, `triggers`), relay.py's task every
minute, each asking whichever sources are due (30 s early counts, so a cron that fires a moment early still takes the
world list every 5 minutes). Anyone may read it (any origin: `Access-Control-Allow-Origin: *`); a browser may keep an
answer a minute (`Cache-Control: public, max-age=60`). 405 for anything but `GET`. The Python relay's public side (the
Funnel's port) allows the read and refuses the keeper's routes, which need the keeper's token as `/admin/aether`'s do.

**What's kept:** on Cloudflare in the Directory's storage (so an eviction or a deploy doesn't ask anything again): the
answer's parts and each source's schedule (`alm:kept`), the Wiki's last two days of `/24h` reduced to each item's average
price and volume (`alm:latest`, `alm:before`), and the item names (`alm:names`). On the Python relay in memory, while it
runs. Nothing about any visitor is kept or sent.

**relay.py's switch:** `--almanac on` (the default: the real hosts), `--almanac off` (nothing fetched; `GET /almanac`
answers its shape, every part `null`), or an address, everything fetched under it at the real ones' paths (the tests'
server). The Worker's `ALMANAC_UPSTREAM` var is the same address (tests only; unset on Cloudflare).

**An example** (from the tests' fixtures, cut down):

```json
{"at": 1794201788313,
 "updates": [{"id": "https://secure.runescape.com/m=news/leagues-vi-out-now?oldschool=1",
              "title": "Leagues VI: Demonic Pacts — Out Now!",
              "url": "https://secure.runescape.com/m=news/leagues-vi-out-now?oldschool=1", "at": 1793880000000,
              "category": "Game Updates", "summary": "The sixth League is here. Unlock relics & regions…"}],
 "worlds": {"players": 9569, "worlds": 13, "at": 1794201788295,
            "seasons": [{"kind": "leagues", "name": "leagues", "players": 4500, "worlds": 3, "activity": "Leagues VI"},
                        {"kind": "deadman", "name": "deadman", "players": 900, "worlds": 1,
                         "activity": "Deadman: Annihilation"}],
            "list": [{"world": 301, "players": 1200, "region": "US", "location": 0, "activity": "Trade - Free",
                      "types": []},
                     {"world": 305, "players": 2000, "region": "US", "location": 0, "activity": "Leagues VI",
                      "types": ["MEMBERS", "SEASONAL"]},
                     {"world": 310, "players": null, "region": "ZA", "location": 12, "activity": "-",
                      "types": ["MEMBERS"]}]},
 "market": [{"item": "Nature rune", "at": 1794009600000, "id": 561, "price": 120, "change": 10.1, "volume": 10900000,
             "value": 1307900000, "why": "mover"},
            {"item": "Twisted bow", "at": 1794009600000, "id": 20997, "price": 1438888889, "change": 3.6, "volume": 45,
             "value": 64750000010, "why": "traded"}],
 "sources": {"updates": {"at": 1794201788307, "ok": true, "age_s": 0},
             "worlds": {"at": 1794201788295, "ok": true, "age_s": 0},
             "market": {"at": 1794201788310, "ok": true, "age_s": 0, "day": 1794009600000},
             "names": {"at": 1794201788309, "ok": true, "age_s": 0}}}
```

**What the globe reads of it** (almanac.js's `fromRelay`, ghillie's dd2b794): `market` whole (its "market" kind),
each of `updates` it hasn't had (its "update" kind: `title`, `url`, `at`, `summary`; told apart by `url` and `title`)
and `worlds` (its "worlds" kind: `players`, `worlds`, `at`, `seasons` as `[{kind, name, players, worlds}]`). The shape
is the one it was written for, with more beside it (`id`, `category`, `list`, `activity`, the market lines' `at`,
`value`, `why`, `sources`), which it can ignore. Stars aren't in the almanac: the globe reads `GET /stars` itself
("Shooting stars", above).

**What the globe should change to read it well** (none of it breaks today; it's in ghillie's repository):
1. Tell a new market from the same one by its `at`. In `fromRelay`, in place of `if (a.market) emit("market", a.market);`:
   `if (a.market && a.market.length && a.market[0].at !== marketAt) { marketAt = a.market[0].at; emit("market", a.market); }`
   with `var marketAt = null, worldsAt = null;` beside `almanacOff`; and the same for the worlds, by `a.worlds.at` (it
   changes every 5 minutes, as the list is fetched), in place of `if (a.worlds) emit("worlds", a.worlds);`:
   `if (a.worlds && a.worlds.at !== worldsAt) { worldsAt = a.worlds.at; emit("worlds", a.worlds); }`.
   login.js's market card says when the numbers are from with `ALM.marketAt` (when the page got them); `m.at` is when
   they're from (a whole UTC day).
2. Tell updates apart by `id` (`url` is `null` when the feed's link isn't on runescape.com, and two posts could share a
   title): `list("update").some(function (o) { return o.id === u.id; })` in place of the `url` and `title` test.
3. `fromRelay` is off unless the page has `?almanac=relay`, and turns itself off for the page's life after one failed
   read (`!r.ok`, or any network error). Once this relay is deployed: ask by default, and on a failure just wait for the
   next read (every 2 minutes, as now). A cold relay still answers 200 (every part `null`), never 404.
4. login.js sorts the ticker by the size of `change` and keeps 6, which drops the most-traded lines unless they moved
   most. Keep the relay's order (6 movers, then 4 most traded) or use `why`; `change` is `null` for a traded line with
   no day before (now shown as ▲0.0%).
5. A season's island is named from `name` ("leagues"); `activity` carries the worlds' own words ("Leagues VI"), if the
   island should say which.
6. `updates` are all of Jagex's news, every category; to show only game updates, filter on `category` ("Game Updates").
7. When a source's `ok` is `false` its part is older than usual; `age_s` says by how much, if the page wants to say so.
8. almanac.js's header still gives the old shape (`"worlds": {"players","worlds","at"}`): this section is the shape now.

## The lookups (11/10/2026)

Wise Old Man's and RuneProfile's records of a player, and the item and metric icons, asked by the relay when a page
reads them and kept, so a page never asks those hosts itself and many visitors looking at one player make one request
upstream (Jace, 11/10/2026: every per-visitor third-party call behind the relay, cached and shared, before asking Wise
Old Man and RuneProfile for keys). Both relays: the Worker's `relay/src/lookup.ts` (the rules) and `lookups.ts` (one
Durable Object for the relay, keeping what came in its storage); relay-py's `lookup.py` (in memory). The pages' side is
ghillie's `tools/release-kit/prototypes/outside-splash/` (almanac.js, station-menu.js, icons.js).

    GET <relay>/lookup/wom/<name>                 Wise Old Man's player      (api.wiseoldman.net/v2/players/<name>)
    GET <relay>/lookup/wom/<name>/achievements    its achievements           (…/achievements)
    GET <relay>/lookup/wom/<name>/gained          the week's gains           (…/gained?period=week)
        ?period=day|week|month|year               a period's                 (…/gained?period=)
        ?from=<moment>[&to=<moment>]              between two moments        (…/gained?startDate=&endDate=)
    GET <relay>/lookup/wom/<name>/timeline?metric=<metric>&period=… or &from=…[&to=…]
                                                  a metric over time         (…/snapshots/timeline?metric=&…)
    GET <relay>/lookup/wom/<name>/progress        achievements in progress   (…/achievements/progress)
    GET <relay>/lookup/wom/<name>/groups          its groups                 (…/groups)
    GET <relay>/lookup/rp/<name>                  RuneProfile's account      (api.runeprofile.com/v1/accounts/<name>)
    GET <relay>/lookup/rp/<name>/activities       its newest 20 activities   (…/activities?limit=20)
        ?limit=1-50, ?activityTypes=<a,b>, ?cursor=<its nextCursor>          (…/activities?limit=&activityTypes=&cursor=)
    GET <relay>/lookup/rp/<name>/skills, /quests, /diaries, /combat, /log
                                                  (…/skills, /quests, /achievement-diaries, /combat-achievements/tasks,
                                                   /collection-log)
    GET <relay>/icon/item/<id>.png                RuneLite's item icon       (static.runelite.net/cache/item/icon/<id>.png)
    GET <relay>/icon/metric/<metric>.png          Wise Old Man's metric icon (its GitHub's metrics_small, through jsDelivr)

Only these shapes are passed on (anything else: 404; anything but GET: 405). Of a page's query only the parameters
above are read; each is checked (a bad one: 404), and they go upstream in one order whatever order they came in, so the
same record is one record; anything else in the query is ignored and never sent. The Outside page's (ghillie.wiki)
added `progress`, `timeline`, gains by period or between two moments, RuneProfile's other parts and its activities by
page on 11/10/2026: a `<moment>` is what JavaScript's `toISOString` makes (`2026-11-01T07:00:00.000Z`), from 2010 on and
not after now; `to`, when given, after `from`; upstream the end is `to`, or now if that's sooner (a stretch still going
ends now), and without `to`, now. The record is kept by what was asked, so every visitor asking for the same stretch
shares it. `<name>` is one the game allows (1–12 of letters, digits, space, `_` and `-`), asked and kept as Wise Old Man
reads it: lower case, a run of spaces, `_` and `-` as one space (`Iron_Man` and `iron man` are one record). `<id>` is 1–6
digits (a leading 0 dropped), `<metric>` 1–40 of `a-z`, `0-9` and `_`. Every answer has `Access-Control-Allow-Origin: *`.

- **200**: the source's own body, as it sent it (a JSON source's checked to be JSON, an icon's to be a PNG), with
  `x-lookup-at` (when the relay fetched it, ms). Fresh for an hour (Wise Old Man's groups six; RuneProfile's newest
  activities half an hour, a later page by its cursor six hours; a stretch of gains or a timeline whose `to` is over an
  hour past, 30 days, since it can't change; an icon 30 days); the first read after that asks again. `Cache-Control: public, max-age=300` (an icon's
  `max-age=604800`). When it's past fresh and the source fails or is paused, the last good one is answered with
  `x-lookup-stale: 1` (`max-age=60`), for as long as it's kept.
- **404** `{"error": "not found"}`: the source hasn't the player (Wise Old Man's 404, RuneProfile's 400) or the icon;
  kept six hours (an icon's a day), `max-age=3600`.
- **503** `{"error": …}` with `Retry-After` (seconds) and `no-store`: not asked just now, with nothing kept: the
  source's minute is used up, it said to wait (its 429), or this record failed lately.

How it asks: each source has an allowance a minute across every visitor (Wise Old Man 15, or 80 with its API key, RuneProfile 20, each icon host
60); a 429 pauses that source for its `Retry-After` (a minute when it gives none; 10 minutes at most); reads of one
record while it's being asked wait for that one request; a record that fails waits a minute before it's asked again,
doubling with each failure in a row, to an hour. Every request carries the relay's User-Agent
(`outstation-relay/1.0 (lookups for outside.wiki and ghillie.wiki's Outside page: each player asked once and shared by
every visitor; https://github.com/jhmcubed/outstation)`) and nothing from the visitor.

Wise Old Man's API key (11/11/2026): the Worker's secret `WOM_API_KEY` (relay-py: the environment variable of the
same name). With it, each request to api.wiseoldman.net carries `x-api-key: <key>` (Wise Old Man's docs, "Rate Limits
& API Keys": 100 a minute and 20,000 a day for the key, instead of 20, 500 an hour and 3,000 a day for an address, which
Cloudflare's shared addresses were using up); the key goes to no other host, and never to a page.

Kept: the Worker in its object's storage (so a deploy or an eviction asks nothing again), a record dropped 14 days after
it was fetched (an icon 90), and 5,000 at most (the oldest fetched go first), by an alarm once a day; relay-py in memory
(a restart asks again; `--lookup off` answers every lookup 503). The tests: `relay/test/lookup_test.mts` and
`relay-py/test_lookup.py` (the rules, on a clock of their own), `tests/protocol_test.py`'s `Lookups` (both relays, the
sources served from `tests/fixtures/lookup`).

## Settling (11/08/2026; gone 11/10/2026)

From 11/08/2026 an outstation's owner could choose where it stood on outside.wiki's globe (its home: a place, beside
another outstation, or by default beside its inviter): the app traded its key for a one-time code (`POST /settle`), and
the globe page checked it (`GET /settle`) and settled with it (`POST /home`), once a week after the first. It went on
11/10/2026 (Jace's decision): the globe now shows on its land only real positions (the squares following gives, or a known
place for a boss, raid or minigame), and every other outstation gathers as a constellation at the receiver on the far
side, so a home has no use.

What's left, on both relays:
- `/settle/<channel>` and `/home/<channel>`, any method, answer 410 `{"error": "settling has gone: the globe now shows
  each outstation where it really is, or at the receiver"}`, with `Access-Control-Allow-Origin: *` and
  `Cache-Control: no-store`, and read nothing (relay.py reads and drops a body of 2 KB at most). An app built from the
  repository between 11/08/2026 and 11/10/2026 has the button (no release has: v0.1.2, the last, is from 11/06/2026),
  and shows the `error` as it is; without it, it would have read a 404 as "an older relay". relay.py answers it on its
  public side too.
- `GET /channels` has no `home`, and the keeper's `GET /admin/channels` no `home` or `homeAt`.
- Nothing about a home is kept. On the Worker, a channel's meta saved while settling was here may hold `home`,
  `homeAt` and `settle` (the code's SHA-256): they're dropped as the meta's read, and gone from storage at its next
  save. A row the Directory stored with a `home`: above, "The open outstations". relay.py kept it all in memory, so a
  restart cleared it.
- `SETTLE_CODE_SECONDS` (the Worker's) and `--settle-code-seconds` (relay.py's) are gone with it.

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
