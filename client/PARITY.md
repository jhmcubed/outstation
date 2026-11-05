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
4. ◐ **The masks** (chat, side, both) in both layouts: written from `capture.py`'s numbers, not yet compared picture to picture.
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

33. ✓ **Only Dink's own kinds,** nothing naming another player, no screenshot. The client filters by your switches, then posts to the relay's Dink route, which filters again as relay.py always has. `client_live.py`: a level-up reached a viewer as "x has levelled Cooking to 70".
34. ✓ **Forwarding to Discord** with `forward.py`'s rules: `cargo test` runs relay-py's own `Rules` cases. ◐ Not yet sent to a real webhook.

## What start.py and the field station did

35. ✓ **No first-run keys made on the PC:** a channel comes from an attach code, or by hand, or from ghillie's own settings ("take them"). Dink's address is in the dink drawer, with a copy button.
36. ✓ **The cap** goes to the relay as the desk's `{"cap"}` on connecting and whenever it changes. `client_live.py` saw `max: 5`.
37. ◐ **The tray:** open, go live / end stream, the sound's modes, quit. Closing while live asks: end the stream, keep it going in the tray, or never mind. Not yet clicked.
38. ✓ **By hand ▸:** a relay-py of your own (and so Tailscale Funnel, as now). `client_live.py` ran the client against relay-py.

## Still to do before the Python is retired

- Everything marked ◐, by hand on Jace's PC with his real RuneLite and real viewers.
- The client on a Windows account without Python or Rust.
- The field station starting the client in place of `start.py`. (ghillie's `tools/launcher`.)
