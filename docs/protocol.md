# The portal's protocol

Both relays speak it: the Cloudflare Worker (`relay/`) and the self-hosted Python relay (`relay-py/relay.py`).
`tests/protocol_test.py` runs the same tests against either, so they stay the same.

## The sockets

| | address | who |
|---|---|---|
| transmit | `/transmit/<channel>?key=<key>&code=<watch code>` | the capture |
| sound | `/sound/<channel>?key=<key>` | the sound |
| desk | `/desk/<channel>?key=<key>&name=<your name in the chat>` | the chat window (several may be open) |
| watch | `/watch/<channel>?code=<watch code>` | a viewer (Outside) |

`ws://` on this PC, `wss://` on Cloudflare. A channel is 1 to 64 characters: letters, digits and `_ . ~ -`.

Closing codes:
- 4000: replaced by a newer transmitter or sound.
- 4001: a key under 8 characters.
- 4003: the wrong key, the wrong watch code, the watch code changed, a channel nobody handed out (Cloudflare), or the channel given a new key or taken back.
- 4029: too many wrong keys from one address in a minute (Cloudflare).

## The transmitter

- **Binary:** a frame, a 4-byte big-endian number (the moment it was taken, in ms, wrapping), then a JPEG.
- **A small frame:** the same 4 bytes, then `TH`, then a small JPEG (192 px wide), about twice a second. Lookers only: never sent to a viewer, never kept as the last frame. While small frames are coming (one in the last 3 s), lookers get them instead of the full ones.
- **Text:** the game data, passed to viewers untouched. The last line is kept for whoever joins next.
  - `{"state": "open" | "shut"}`: the picture's gate. Sound passes only while it's open.
  - `{"report": {"type", "text", "at"}}`: a field report (from Dink). The last 8 are kept for late joiners.
- **It hears:** the status line (below).

The first transmitter sets the channel's watch code. Viewers whose code no longer matches are let go.

## Viewers

**They hear:**
- the status line: `{"relay": {"channel", "live", "viewers", "max", "waiting", "open", "set"}}`. `set` says whether the channel has been handed out (on the Python relay: claimed by a transmitter). In line for a seat it also has `"seat": false` and `"place": n`; with a seat, `"you": n`, their number in the chat.
- frames and data, as the transmitter sent them. On arriving they get the last data line, the last frame (only while live), the reports, the chat so far, the poll and the sound's line.
- `{"chat": {"id", "name", "text", "host", "who", "queued", "at"}}`, `{"unsay": id}`
- `{"poll": {"id", "question", "options", "counts", "open", "left"}}`, `{"poll_gone": id}`
- `{"ping": {"x", "y", "name", "who", "at"}}`
- `{"sound": {"mode", "buses"}}`, and the sound's binary messages (below)

**They may send** (anything else is thrown away; nothing over 2 KB is read):
- `{"hello": {"name", "device"}}`: their name in the chat, and a random id their device keeps (8–64 characters). Nobody may use the channel's or a desk's name.
- `{"say": text}`: at most 20 lines in 10 s, 300 characters each.
- `{"vote": {"poll": id, "pick": i}}`: one vote a device, which they can change while the poll is open.
- `{"ping": {"x", "y"}}`: a spot on the frame, 0–1 each. One every half second, and only with a seat.
- `{"got": bytes}` (Cloudflare): every binary byte received so far, about once a second. Past 4 MB sent and not yet acknowledged, a viewer gets no more frames or sound until it catches up. A viewer that never says it gets the first 4 MB and then text only. The Python relay ignores it: it reads its own send buffer.

**The waiting line:** past the cap, a viewer waits in line, with status lines and the chat but no frames. The first in line takes the first seat that frees.

## The desk

**It hears:** the status line, the chat so far and all of it after, the poll, pings, who's watching (`{"viewers": [{"who", "name", "waiting"}]}` whenever it changes: names as given, never a device), and `{"upload": {"bps", "viewers"}}` each second while live. That's what the relay sends viewers: on the Python relay it's the PC's own upload; on Cloudflare, Cloudflare's.

**It sends:**
- `{"say": text}`: as the host (`"host": true`), under its `name`.
- `{"hide": id}`: takes a line back off everyone's page, late joiners included.
- `{"block": who}` / `{"unblock": who}`: that viewer's number and device. Their lines and pings stop going anywhere.
- `{"poll": {"question", "options": [2–6], "seconds": 10–900}}` and `{"poll_close": true}`. A closed poll's result stays 90 s, then `{"poll_gone"}`.
- `{"chat_on": bool}`: off, viewers' lines go nowhere (the host's still go out); the status line's `chat` says so.
- `{"cap": n}`: the channel's own cap on viewers (0 means none). On the Python relay it takes the place of `--max-viewers` for that channel. On Cloudflare the Worker's `MAX_VIEWERS` is a ceiling over it.

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

The client normally takes Dink's posts on its own PC and sends the reports over its transmit socket. This route is the fallback.

## Handing out channels (Cloudflare)

`Authorization: Bearer <ADMIN_TOKEN>`:
- `POST /admin/channels/<channel>`: a new key, `{"channel", "key", "code"}`. Any old key stops working, and whatever was connected with it is cut off.
- `DELETE /admin/channels/<channel>`: taken back. Its key is refused, and its transmitter, sound and desks are cut off. Viewers stay, off air.
- `GET /admin/channels`: the list.

The attach code is `portal1:` followed by base64url JSON `{"relay": "wss://…", "channel", "key"}`. A friend pastes it into the client, and that's all their set-up.

Only the key's SHA-256 is kept.
