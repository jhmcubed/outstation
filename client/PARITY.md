# Parity with the Python portal

The client replaces ghillie's Python portal (`tools/portal/capture.py`, `sound.py`, `portal-sound`, `tools/launcher/portal_chat.py`, `portal_settings.py`, the relay's Dink handling and `forward.py`), for Jace and everyone else. This is that list (ghillie's `claude/the-portal.md`, "Parity"), with what the client does for each line and how it was checked.

The marks:
- **✓**: built and checked as written.
- **◐**: built, but not checked yet in the way that counts (with the real client and real viewers, or on another PC).

Checked 11/05/2026 on Jace's PC with his RuneLite open:
- `tests/client_live.py` against relay-py and against the Worker under `wrangler dev`, both passing
- `tests/client_screens.py`, pictures of each screen
- `--once` and `--bench` against `capture.py`'s own
- `cargo test`: the attach code, the settings, the Dink rules (relay-py's `Rules` cases) and multipart posts

## The capture

1. ✓ **RuneLite's window:**
   - the largest visible window titled "RuneLite…" (the title is set in "more ▸"), looked for once a second
   - only its title read again in between (`win.rs`, `WindowCache`)
2. ✓ **The canvas:**
   - the largest AWT canvas, with fixed mode told apart by its shape
   - "fixed mode's 3D view" (`--view viewport`)
   - `--once` on his window: "resizable, 2529x1417 canvas", the same as `capture.py`.
3. ◐ **Windows Graphics Capture,** kept to the schedule:
   - cropped on the GPU, averaged down with a box filter (`capture.rs`, `Handler`)
   - PrintWindow and HALFTONE when it isn't available; the screen if that's black
   - The same frame as `capture.py`'s, 768×430: a mean difference of 0.8 in 255, a second apart.
   - PrintWindow and the screen fallback are written but not yet tried on a machine without Windows Graphics Capture.
   - Cost: a frame takes 4.8 ms against Python's 3.5 (2.9 ms crop and shrink, 1.6 ms JPEG, against OpenCV's 2.2 and libjpeg-turbo's 0.8). At 30 a second that's 14% of a core against 11%.
4. **The masks** (chat, side, both): retired on 11/05/2026 at Jace's word (a fixed size whatever the client's); your own masks take their place.
5. ✓ **Width** (never wider than the game), quality, 1–60 a second, paced to fixed deadlines.
6. ✓ **A frame** is 4 bytes, the ms it was taken on the portal's clock, then the JPEG.
7. ✓ **The gate:** no window, minimised, paused, or (if asked) logged out. The pause key is now `RegisterHotKey` (`hotkeys.rs`), not a look at the keyboard every 20 ms (SAFETY.md). The press itself is ◐: not tried by hand yet.
8. ✓ **The half-second hold-back,** dropped when the gate shuts.
9. ✓ **`{"state"}`** on every change and once a second. `client_live.py` saw "open".
10. ✓ **on_air:** off lets go of the relay altogether.
11. ✓ **Reconnecting** after 2, 5, 10, 30 s. It stops on 4000, 4001, 4003 and 4029 until the settings change.
12. ✓ **`--once FILE` and `--bench N`.**
13. ✓ **The log line every 5 s,** in `portal.log` beside the settings.

## The sound

14. ✓ **The modes and busses:**
    - RuneLite's tree (`win.rs`, `runelite_pid`), the same rule as `sound.py`
    - everything but it on bus 2
    - `client_live.py` with "all": `{"sound": {"mode": "all", "buses": [1, 2]}}`, and 448 messages in 14 s
15. ✓ **Process loopback** at 48 kHz stereo, then libopus 1.6.1 (`sound.rs`):
    - 20 ms frames, VBR, application "audio", three to a message
    - begun again after a gap or 80 ms of drift
    - at most 200 waiting
    - One difference: PyAV's encoder handed packets out a frame late, and `sound.py` made up for it; libopus called directly hands each frame's packet back at once.
    - ◐ Not yet listened to on Outside.
16. ✓ **`{"sound"}`** on every change and every 5 s.
17. ◐ **The sound key** steps the mode and saves it (`hotkeys.rs`, `RegisterHotKey`). Not tried by hand yet.
18. ✓ **RuneLite's process** looked up each second, and a bus started again when it changes.

## The window

19. ✓ **The title,** settings ▸ and (new) dink ▸, each a drawer on the right.
20. ✓ **The live light:** hotpink in steps while live, teal while paused, an empty ring off air.
21. ✓ **The status lines.** "the portal isn't running" is now "the relay isn't answering", since the client is the portal. "stopped: …" is new: another capture took over, or the key was refused.
22. ✓ **go live / end stream.**
23. ✓ **The upload line:** the relay's count, and this PC's from Windows' byte counter (`GetIfTable2` in place of `netstat -e`), against `upload_mbit`. On Cloudflare it's "the relay sends", not his upload.
24. ✓ **The sound row,** and "pings on my screen".
25. ◐ **The chat:**
    - ✓ names in cyan, the host's lime, times, "(in line)", ✼ notes, no line shown twice on a reconnect
    - ◐ right-click to take a line back, block or unblock: written, not yet clicked
    - ◐ the taskbar button flashing: written, not yet seen
26. ✓ **Polls:**
    - the form: 2–6 options, 30–600 s, "send it out", "never mind"
    - open: the countdown, "close it", block bars
    - closed: the most-picked pink, ×, gone on `poll_gone`
    - `client_screens.py` pictured an open poll with a vote counted.
27. ◐ **Pings:**
    - ✓ the chat's "✼ name pointed at something"
    - ◐ the ring over RuneLite: written (an egui window: see-through, click-through, on top, without a taskbar button), but not yet looked at on the screen
28. ✓ **The answer line:** Enter or send, "a poll…", Escape.
29. ✓ **`chat_name`** (in "more ▸").
30. ✓ **The settings drawer:**
    - the four bars (click, drag, the wheel, the arrow keys)
    - the sample, shrunk and JPEG'd as each bar moves
    - "take a new sample", "as saved", "save", and "not saved" while it differs
    - "save and restart the portal" is gone: everything takes at once in the client, the cap too
    - "more ▸" sets what `settings.json` held by hand: the view, masks, method, logged-out, keys, kbps, upload, title, watch code
31. ✓ **One window per settings file:** a second copy brings the first forward (`win.rs`, `first_copy`).
32. ✓ **The ✼ icon** on the window, the taskbar and the tray.

## Dink and forwarding

33. ✓ **Only Dink's own kinds,** nothing naming another player, no screenshot. The client filters by your switches, then posts to the relay's Dink route only what the relay uses of each post (11/06/2026, `dink.rs`, `for_relay`: its kind, its line and the readings' and drop guesses' fields; never the screenshot, Dink's account details, a party's names or a PvP killer), and the relay filters again as relay.py always has. `client_live.py`: a level-up reached a viewer as "x has levelled Cooking to 70". Checked 11/06/2026: 22 sample posts give both relays the same report trimmed as whole; under Wine, a post into the client reached a relay-py viewer as its report and the summary, and a stand-in relay received only the trimmed fields.
34. ✓ **Forwarding to Discord** with `forward.py`'s rules: `cargo test` runs relay-py's own `Rules` cases. ◐ Not yet sent to a real webhook.

## What start.py and the field station did

35. ✓ **No first-run keys made on the PC:** a channel comes from an attach code, or by hand (ghillie's own settings, "take them", until 11/06/2026: only the keeper had them). Dink's address is in the dink drawer, with a copy button.
36. ✓ **The cap** goes to the relay as the desk's `{"cap"}` on connecting and whenever it changes. `client_live.py` saw `max: 5`.
37. ◐ **The tray:** open, go live / end stream, the sound's modes, quit. Closing while live asks: end the stream, keep it going in the tray, or never mind. Not yet clicked.
38. ✓ **By hand ▸:** a relay-py of your own (and so Tailscale Funnel, as now). `client_live.py` ran the client against relay-py.

## Main's round two (jhmcubed/ghillie#90, 11/05/2026), and after

39. ✓ **The static key** (Pause): viewers see static, "signal obscured", while the sound and chat go on (`{"state": "static"}`). ◐ Not pressed by hand yet.
40. ✓ **Every hotkey changeable:** the static, sound, clip and mask keys, in the settings drawer. Press "change", then the key (Escape keeps the old one). Pause, Scroll Lock, Num Lock and Caps Lock are buttons, since egui can't hear them. A key already in use is refused. Each takes at once (`RegisterHotKey`).
41. ✓ **The replay buffer,** 0 to 30 s, and clips as .mp4.
    - The Python's relay kept and wrote them; a relay on Cloudflare can't write to a PC, so here the client keeps exactly what it sent and writes the clip itself.
    - It uses Windows' own H.264 and AAC (Media Foundation), both busses mixed by their stamps, into `clip_folder` (Videos\outside-portal).
    - `--clip-selftest DIR`: two seconds, 60 frames, the tone at its level (checked with PyAV).
    - ◐ Not tried on a real stream yet.
42. ◐ **The chat over the game:**
    - the newest 7 lines over RuneLite, fading after 20 s, in a see-through click-through window
    - "move it" turns it into a box to drag, kept with "done" or Enter as a share of the game (`game_chat_at`, read from the Python's settings too)
    - its text size its own
    - Written and built; not looked at over the game yet.
43. ✓ **The signal button:** the window counts each ✼. As Jace asked later ("allow for spamming"), each also rises over RuneLite (◐ not seen there yet; "✼ on my screen" turns it off). The relays' limit is now a flood guard of about 12 a second.
44. ✓ **Drop guesses:** the form (the boss, what's guessed) and the round (its count, "stop guesses", "it dropped", "call it off", the result). The relays decide them from Dink's counts and loot; the Worker has them too. `guess_min_value` goes to the relay as the desk's `{"guess_min_value"}`.
45. **Picture in picture, and the listening post tuned to the portal:** both the page's own (`outside-portal.js`, `portal-feed.js`). The Worker serves lookers, so they work against Cloudflare.
46. **The chat window opening with the stream:** the client is the window.

**Asked for in the same days, beyond the Python:**
- ✓ **Bans:** "ban" in a line's menu takes back every line of theirs and blocks their device; "banned (n)" lists them, each with "unban". The relays send the list.
- ✓ **Hiding a line:** "take this line back".
- ✓ **"viewers may ping":** the desk's `{"pings"}`; viewers' pings go nowhere while it's off.
- ✓ **Sizes:** the chat's text, the chat over the game's, and the whole window's (75–200%).
- ✓ **Your own masks:** "edit masks" draws them over the game. They're blacked out before a frame is made: `client_live.py` checks a masked half arrives black. The mask key turns them off and on.
- ✓ **The live view:** the portal as the site gets it, through the relay as a looker, and what viewers' pages say they've had (the relays' new `acked`). "the live view" off connects nothing.
- ✓ **The tray:** minimising sends the window there, a click on the ✼ brings it back, and the ✼ is pink while live.
- ✓ **Small frames for lookers:** a 192 px copy twice a second (`TH`), which the relays give lookers instead of full frames: 9 KB/s a looker where it was about 81.
- ✓ **The chat switch:** "viewers may chat" (the desk's `{"chat_on"}`): viewers' lines go nowhere while it's off, the host's still go out, and pages say so.
- ✓ **The viewer list:** "watching: n ▸", from the relays' `{"viewers"}` (names as given, never a device); right-click to ban.
- ✓ **A window of its own:** its own title bar, edges and border, and ghillie.wiki's cursor (`cursor.rs`), on the portal's own windows only.
- ✓ **jute's look** (`theme.rs`): the browns, plates and tint strip, and anyone's own colours.
- ✓ **"blacked out" retired:** the fixed-size chat and side boxes, in favour of your own masks (an old setting is ignored).
- ✓ **Dink posted twice** (an address listed twice in Dink): the same post within 10 s is one event, in the client and every relay.
- ◐ **The broadcast's title** (11/06/2026): "title:" on the stream's plate, sent on Enter (the desk's `{"title"}`, in every relay's status line and `/channels`). On Outside it sits beside "✼ portal" in the portal's bar, running slowly past when it's too long; on the open portals, under the name. The relays: the shared suite (58, both). Not yet seen in the real window.
- ◐ **"my cursor in the picture"** (11/06/2026): Windows Graphics Capture's own cursor drawn into the frames (the capture starts again when it's switched). PrintWindow, the fallback, can't draw it. Not yet seen on a real stream.
- ✓ **The client's version** (11/06/2026, before the first release): `v=` on every socket it opens; a relay with a minimum (`MIN_CLIENT`, `--min-client`) turns an older one away with 4026, and the client stops (the picture, the sound and the chat) and says where the newest is instead of trying again. The shared suite's `Versions` (both relays) and `settings::tests::urls`.
- ✓ **Asking for a portal** (11/06/2026): the attach screen's "no code yet? ask for one" (the player's name and a note for the keeper) sends a request to the keeper's relay with a pickup only this PC holds; the screen says how long it's waited, "never mind" takes it back, and it looks every 20 s and attaches by itself when the keeper says yes (or says he turned it down). The keeper's build lists those asking in the friends drawer ("asking (n)", "has a portal already", hand out / turn down, each asked about first) and each channel's client version. `ask::HOME_RELAY` is empty until the deploy, and the screen says asking isn't open yet. Checked under Wine against the Worker under `wrangler dev`: asked, listed, handed out, attached by itself in about 7 s; turned down; the drawer pictured. The shared suite's `Requests`.
- ✓ **When something goes wrong** (11/06/2026): a crash in any of the portal's threads is written into `portal.log` (what, and the line in the portal's own code), a box says so and where the log is, and the next start says it happened (`crash.txt`, read once). "copy my log" (settings ▸ more ▸ if something goes wrong) puts the version and the log's last 400 lines on the clipboard with keys, watch codes, attach codes, pickups and Discord webhook addresses taken out (`crash::scrub`, its test). `.github/ISSUE_TEMPLATE/something-went-wrong.yml` asks for it. Checked under Wine with a build made to crash: the box, the log line, the note on the next start, the clipboard.
- ✓ **The upload on Cloudflare** (11/06/2026): on a `wss://` relay the settings drawer gives the one figure this PC sends, however many watch, instead of "for each viewer" and "in all".
- ✓ **The ready check** (11/06/2026): above the title while off air, "ready?" and each ticked or not, with how to fix it under the hand: RuneLite open, logged in (its title saying who, when "nothing while logged out" is on), the relay answering, Dink heard (optional), and the sound's mode. "hide" puts it away; settings ▸ more brings it back. Checked under Wine with a stand-in window titled like RuneLite's and a pretend Dink post: each going from ✗ to ✓.
- ✓ **Notices from the keeper** (11/06/2026): a line from the listening post (the relay's `/admin/notice`) shows once in the chat, "from the listening post: …", remembered by its `at` (`notice_seen`) so a restart doesn't show it again. The keeper's build: "a notice to every portal" in the friends drawer (what's up now, "send it", "take it down"). The shared suite's `Notices`; under Wine, sent from the drawer and shown in the same window's chat, not shown again after a restart.
- ✓ **Hours on air this month** (11/06/2026, the keeper's build): the friends drawer's list gives each channel's time live since the 1st (UTC) and the total, from the relay's `live_s` (each stretch counted when it ends, the one going on included; one ended unseen counted to where it was last seen, saved about once a minute). The shared suite's `test_the_keepers_list_counts_hours_on_air_this_month`.
- ✓ **The Start menu** (11/06/2026): "put the portal in your Start menu?" once on the first screen; yes makes a shortcut in the user's own Start menu (`shortcut.rs`: IShellLink, the ✼ as `portal.ico` beside the settings, a PNG inside an ICO), kept pointing at this copy on each start; settings ▸ more puts it in or takes it out. Checked under Wine: the shortcut, its icon file and the setting. The exe itself still has no icon of its own (no resource step yet).
- ✓ **Go live when RuneLite opens** (11/06/2026, settings ▸ more, off to begin with), and "…and end the stream when RuneLite closes" (on with it; RuneLite gone 30 s): `autolive.rs`, a look for RuneLite's window once a second; it acts only on the window appearing, so "end stream" with RuneLite open is never undone. In the keeper's build, going live by itself starts the relay on this PC as "go live" does. Checked under Wine with the stand-in window: live within a second of it opening, the stream ended 30 s after it closed, live again when it reopened, and "end stream" left alone.
- ✓ **The friends list** (11/06/2026, friends ▸; Jace: "model it after the osrs friends list"): names in the order added, each one's state on the right (Live · viewers in lime, Off air in pink, No portal in grey), "add friend" / "del friend" with the game's prompts, 200 at most, the game's own refusals ("… is already on your friends list.", "you can't add yourself…"), "… has gone live." / "… has gone off air." in the chat (switchable), a click on a name opening their Outside page in the browser (`win::open_page`, ghillie.wiki's pages only), right-click for the page or delete, and the button counting who's live. From the relay's `GET /channels`, every minute, every 20 s while open. The keeper's own drawer is "portals ▸" now. `friendlist::tests` (2); under Wine against the Worker with two friends' channels: each state, the duplicate refused, "Birch Bark has gone live.", the tooltip, and a click handing `https://ghillie.wiki/outside?player=birchbark` to the browser.
- ◐ **The field station** (11/06/2026, the keeper's build: `--features station`): the services in ghillie's `launcher.json` in the tray (lights, start, stop, page, log, started elsewhere), modes, links, stop all, close the station; the relay on this PC started on going live while the portal's relay is this PC's; friends' portals in a drawer (hand out, new code, take back, the list, set up). Built here with `cargo check` for Windows; not yet run on Jace's PC.

## Still to do before the Python is retired

- Everything marked ◐, by hand on Jace's PC with his real RuneLite and real viewers. (Since 11/06/2026 the unit tests and the window also run under Wine in a cloud session, with a stand-in for coremessaging.dll, which Wine lacks: the window can be pictured and clicked there, but not the capture, the sound or the hotkeys.) Including: pressing each hotkey, the chat over the game moved, a mask drawn, a clip of a real stream, the tray minimise and the pink ✼.
- The client on a Windows account without Python or Rust.
- The field station starting the client in place of `start.py`: built (the station feature, ghillie's `tools/launcher/build_station.py`); to try on Jace's PC.
