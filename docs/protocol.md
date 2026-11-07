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

## Viewers

**They hear:**
- the status line: `{"relay": {"channel", "live", "viewers", "max", "waiting", "open", "static", "set", "pings", "chat", "title", "game"}}`. `static`: the static key is down. `pings` and `chat`: whether viewers may ping and chat. `set` says whether the channel has been handed out (on the Python relay: claimed by a transmitter). `title` is the broadcast's title, from the desk (`""` for none). `game`: whether the Outside plugin is connected now (below, "The game"). In line for a seat it also has `"seat": false` and `"place": n`; with a seat, `"you": n`, their number in the chat.
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

**It hears:** the status line, the chat so far and all of it after, the poll, the drop guess, pings, ✼ signals, the reports and the summary, the game state (below, "The game"), who's banned (`{"banned": [{"who", "name"}]}`), messages from other outstations (below, "Messages between outstations"), who's watching (`{"viewers": [{"who", "name", "waiting"}]}` whenever it changes: names as given, never a device), `{"upload": {"bps", "viewers", "acked"}}` (`acked`: what viewers say they've had, on Cloudflare) each second while live, and on Cloudflare the keeper's `{"notice"}` ("A notice from the keeper", below). The upload is what the relay sends viewers: on the Python relay it's the PC's own upload; on Cloudflare, Cloudflare's.

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

`GET /channels` (both relays; anyone may read it): `{"channels": [{"channel", "live", "open", "static", "viewers", "title", "doing", "by", "game"}]}`, live ones first. Only channels handed out and with no watch code are on it. `doing` is `{"boss", "kc"}` while live with a kill count in this stream's summary, else `null`. `by` is the channel whose invite it came from, or `null` (below, "Invites"). `game`: whether the Outside plugin is connected now (below, "The game"). The outstations page on ghillie.wiki (`portals.html`) and the client's friends list read it.

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
- `GET /admin/channels`: the list, each with `channel`, `since`, `by` (the channel whose invite it came from, `null` for the keeper's own; below, "Invites"), `live`, `viewers`, `title`, `client` (the version the last transmitter said, `""` for none) and `live_s` (seconds on air since the 1st of this month, UTC, the stretch going on now included: what Cloudflare's bill follows).

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
- `paused`: why the plugin has stopped sending what the player wears and carries, for their safety (one line, 32 characters at most, e.g. `"wilderness"`: the Wilderness, a PvP world), or `false` when it carries on. Kept like the others (`true` isn't its shape: left out). What was kept before it paused stays as it was, unless the plugin clears it (`"worn": []`, `"inv": []`).
- `vitals`: `{"hp": [now, most], "prayer": [now, most], "run": 0–100, "spec": 0–100}`
- `skills`: `{"<Skill name>": [real level, boosted level, xp]}`; a name is a letter, then letters, spaces, `_` or `-`, 24 at most
- `worn`: 14 slots, each `[item id, how many]` or `null` (the place in the list is RuneLite's `EquipmentInventorySlot` index)
- `inv`: 28 slots, the same
- `bag`: the looting bag, its slots (at most 28), or `null` while it isn't known
- `pouch`: the rune pouch's slots (at most 8)
- `quiver`: `[item id, how many]` or `null`
- `prayers`: the prayers on, by name (at most 64, 40 characters each)
- `items`: `{"<item id>": [name, Grand Exchange price]}`, merged into the dictionary kept (below)

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
