# Is it safe for my account?

The short answer: the portal does what streaming software does (OBS, Discord's screen share) and nothing more. It never touches RuneLite or the game, and it can't press a key or click for you. Everything below can be checked: the code is all here, and every release is built by GitHub from it, not on anyone's computer.

## In plain words

| Does it… | |
|---|---|
| touch RuneLite or the game (read or change its memory, add to it, plug into it)? | **No.** It isn't a RuneLite plugin, and it never opens RuneLite's process. |
| play for you: press keys, click, move the mouse? | **No.** It has no way to send a key or a click. |
| see your password, bank PIN or account? | **No.** It never asks for your Jagex login. It sees only what's drawn in RuneLite's window, as a stream does, so press **Pause** before typing a bank PIN: nothing goes out while paused, and the half second before the press is taken back. |
| read what RuneLite and Jagex say to each other? | **No.** |
| watch your keyboard? | **No.** Windows tells it only when one of its own four keys is pressed: the static key (Pause), the sound key (Scroll Lock), the clip key (Insert) and the mask key (End). You can change each. |
| show something you'd rather keep to yourself? | **Only what you allow.** Draw your own masks over any part of the game (the chat, the map, the world number): those parts are blacked out on your PC, before a frame is made, so they never leave it. The mask key turns them off and on. |
| show your mouse pointer? | **Only if you switch it on.** "my cursor in the picture" (off to begin with) has Windows Graphics Capture draw the pointer into the frames where it's over RuneLite. Windows draws it: the portal never reads where the pointer is. |
| need admin, install a service, or start with Windows? | **No.** It's one program. It runs when you open it, and closing it ends it. If you say yes on its first screen, it puts one shortcut in your own Start menu (settings ▸ more takes it out). |
| need anything else installed? | **No.** Everything it needs comes with Windows 10 and 11: the safety check below fails a release that would need anything more. |
| let viewers control anything? | **No.** Viewers can chat, vote, guess, point at a spot and send a ✼. The ring, the ✼ and the chat over the game are drawn in see-through windows of the portal's own, over RuneLite, never in it, and clicks pass through them. Each can be switched off, and "viewers may ping" switches pings off altogether. |

**What it reads:**
- **RuneLite's window, as pictures.** It uses Windows Graphics Capture, the way OBS's Window Capture and Discord's screen share do.
- **Its sound, if you turn the sound on.** It uses Windows' app audio capture, the way OBS's Application Audio Capture does.
- **Window titles and the list of running programs,** to find RuneLite.
- **Your PC's upload counter,** for the upload line.

**Where things go:**
- **Your picture, sound and chat:** only to the relay in your attach code.
- **Dink's posts:** only to the portal on your own PC (`127.0.0.1`, which nothing outside your PC can reach). They go on to your Discord webhook if you set one.
- **GitHub:** once on opening, to see whether there's a newer version. "more ▸" in the settings turns that off.
- **The friends list** (friends ▸): while anyone's on it, the relay's list of open portals, read every minute (every 20 seconds while the list is open), to show who's live. Nothing about you goes with it. Clicking a name asks Windows to open that player's Outside page on ghillie.wiki in your browser. Those pages, and this one (the attach screen's link to SAFETY.md on GitHub), are the only pages the portal ever opens, and opening them is the only thing it ever asks Windows to start. With the hand over a live friend's name, it watches their portal through the same relay, as their page would, for a small picture of it; the hand gone, it stops. When a friend goes live, Windows shows its own notification (only if the portal's in your Start menu: Windows shows a program's notifications only then); nothing about it is sent anywhere.
- **Asking for a portal** (the first screen's "no code yet? ask for one"): only when you press **ask**, to the portal's relay (Jace's, on Cloudflare: `relay.ghillie.workers.dev`): your player's name, your note for him, and a random code your PC keeps to collect the answer. While the request waits, the portal asks the same relay every 20 seconds whether he has answered.

Clips (the replay buffer) are written on your own PC, into Videos\outside-portal, by Windows' own video encoder, when you press the clip key or, if you leave "clips by themselves" on, when Dink tells of a pet, a new collection log slot, a 99 or a big drop. They stay on your PC, unless you switch on **"and put them on my Outside page"** (dink ▸, off to begin with): then each clip that saved itself also goes to your relay as a copy of its own (the 17 seconds round the moment, at most 768 px wide, with a still and a few small frames for the hover), and anyone who opens your Outside page can watch it. Only those clips, never the ones from the clip key. The live view in the window watches your own portal through the relay, as any viewer would.

Nothing else. The safety check below proves the program has no other address in it.

## What the relay keeps

The relay (Jace's, on Cloudflare) passes your stream on to whoever's watching. Here is everything it writes down, and for how long.

**For you, streaming:**
- your channel's name, and the SHA-256 of its key (never the key itself)
- your watch code, if you set one, and your broadcast's title
- the version of the portal you run, and your time on air by month (the last four months, so Jace can see what the relay costs)
- the chat so far (its last 50 lines), the poll and the drop guess going on, who you've banned, and the last 8 field reports from Dink

Your picture and sound are passed on as they come and never written down. Only the newest frame is held, in memory, for whoever arrives next, and it's gone when the relay sleeps.

**Your clips on your Outside page,** only if you switched it on: each one's copy, its still and its few small frames, and what it was ("pet vorki"), when, and how long. At most 20, the oldest going first, and each for 30 days. Your portal lists them (dink ▸), keeps up to 3 for good (never pushed out by newer ones, never too old) and takes any down; Jace can take any down too.

**For viewers:**
- the name they type for the chat, and their lines in it (the chat's last 50, everyone's together)
- a random number their own browser made, which isn't tied to them: it counts one vote per device in polls and drop guesses, and it's what a block or a ban holds on to

A viewer's address (IP) isn't written down, and the relay never learns their name in the game or anything about their account.

**Asking for a portal:**
- the name and note you typed, and the SHA-256 of your portal's pickup code
- once Jace says yes, the attach code, until your portal collects it

A request nobody answers goes after 14 days, and an answer nobody collects goes after 7. The address a request came from is held in memory for an hour (3 requests an hour from one place). An address that sends wrong keys is held in memory for a minute.

All of it lives in Cloudflare, in Jace's account. Cloudflare also keeps its own short-lived logs of the relay's requests, which only that account can see. On a relay of your own (`relay-py`), it's all in that PC's memory and gone when it stops.

## Jagex's rules

The [Rules of RuneScape](https://legal.jagex.com/docs/rules/rules-of-runescape) forbid software that plays for you, changes the game, or "tries to look at or change the communications" between your device and the game worlds. The portal does none of those. Jagex's [Fan Content Policy](https://legal.jagex.com/docs/policies/fan-content-policy) allows streaming your gameplay (it names YouTube and Twitch).

Two things to know, said plainly:
- **The chat.** The same rules list software that "has its own chat feature separate from our game chat that does not follow our rules". The portal's chat is outside the game, as a stream's chat is, and it's yours to keep within Jagex's rules: right-click a line to take it off everyone's page, or to block a viewer.
- **Pings over the game.** Jagex's [client guidelines](https://oldschool.runescape.wiki/w/Update:Third_Party_Client_Guidelines) don't allow extra indicators for boss mechanics, "except in cases where this is a manually triggered external helper". A ping is a person pointing, in a window that isn't RuneLite's. If you'd rather not have even that during bossing, untick "pings on my screen": viewers' pings then show only in the chat and on their own pages.

Nobody but Jagex can promise what Jagex will decide; their rules say they "may choose to mute or ban an account for any reason". What the portal can do is stay well inside what streaming software does, and let you check that it does.

## Checking it yourself

1. **The code is all here**, under the MIT licence. The client is `client/src`, about a dozen files.
2. **Releases are built by GitHub, from this code.** Each release lists the commit it was built from and the program's SHA-256. Once the repository is public, each also carries GitHub's build attestation: [GitHub CLI](https://cli.github.com) can confirm the file came from this repository's own build, unchanged:

   ```bash
   gh attestation verify outside-portal.exe --repo jhmcubed/outside-portal
   ```

3. **The safety check runs on every release's own program.** Any Windows program lists, inside itself, every Windows function it can call, the libraries' included. `tests/safety_check.py` reads that list out of the file and fails the release if anything that could reach into another program, play for you or watch the keyboard is in it. It also lists every library the program loads, and fails if any of them doesn't come with Windows. It then checks the client's own code the same way, and checks that the only addresses written into it are the ones above. Run it on your own copy (Python, nothing to install):

   ```bash
   py tests/safety_check.py outside-portal.exe
   ```

4. **Look up the file.** Search [VirusTotal](https://www.virustotal.com) for the release's SHA-256, or upload the program there.
5. **Watch it.** Windows' Resource Monitor (Network tab) shows every connection it makes while it runs.

## The technical detail

The check's list of functions no program here may use, and why each:

| | |
|---|---|
| `WriteProcessMemory`, `ReadProcessMemory`, `VirtualAllocEx`, `VirtualProtectEx`, `CreateRemoteThread(Ex)`, `QueueUserAPC`, `SetThreadContext`, `NtReadVirtualMemory`, `NtWriteVirtualMemory`, `OpenThread`, `DebugActiveProcess`, `MiniDumpWriteDump` | reading or writing another program's memory, or running code inside it |
| `SendInput`, `keybd_event`, `mouse_event`, `BlockInput`, `AttachThreadInput` | pretend keys and clicks, or joining another program's input |
| `SetWindowsHookEx`, `GetAsyncKeyState`, `GetKeyboardState` | watching the keyboard or mouse everywhere |

The client's own code uses none of them. (That includes `station.rs` and `keeper.rs`, the field station: built only into the keeper's own copy, with `--features station`, never into a release. They start the keeper's own tools as child programs and stop them with Windows' `taskkill`; the check reads them as it reads the rest.) It also never loads a function by hand (`GetProcAddress`, `LoadLibrary`), never opens another process (`OpenProcess`), and never posts or sends a message to another program's window.

Three of them are in the program's list all the same, brought by the libraries its window is built on. Each serves the portal's own window only:
- `SendInput`:
  - **winit**, the window library almost every Rust desktop program uses, sends one pretend Alt key when asked to bring its own window to the front, a known workaround for Windows' focus rules (`winit/src/platform_impl/windows/window.rs`). The client never asks it to: it brings its window forward with Windows' own `SetForegroundWindow`.
  - **muda**, the tray menu, has it for ready-made copy and paste menu items. The client doesn't use them.
- `GetAsyncKeyState` and `GetKeyboardState`: winit reads whether Shift, Ctrl and Alt are held when its own window gets the keyboard focus (`winit/src/platform_impl/windows/keyboard.rs`).
- `Module32FirstW` and `Module32NextW`: Rust's standard library lists its own program's parts for a crash report.

The check fails on any function from the list that isn't one of these, so a new one can't slip in unseen.

Everything it touches in Windows, by file:
- `capture.rs`:
  - RuneLite's window as pictures: Windows Graphics Capture, or PrintWindow, or the screen if those come back black
  - the window's size and place
  - whether it's minimised
- `sound.rs`: process loopback (`ActivateAudioInterfaceAsync`): one program's sound, or everything but it.
- `win.rs`:
  - finding RuneLite's window and its process (window titles, the process list)
  - your network's byte counter (`GetIfTable2`)
  - the clipboard (when you copy or paste with Ctrl+C, X and V or the copy buttons; for the attach code and Dink's address; and Dink's own export, read only when you press "read my Dink settings", with an import for Dink put back in its place)
- `hotkeys.rs`: `RegisterHotKey` for its four keys.
- `clip.rs`: the replay buffer, written as an .mp4 by Windows' Media Foundation (its H.264 and AAC encoders), into your Videos folder; and, for your Outside page, the copy of its own (through a file in your temporary folder, deleted once read).
- `clipsup.rs`: with "put them on my Outside page" on, that copy sent to your relay, and the list of what's there.
- `feed.rs`: the live view: your own channel watched through the relay.
- `dink.rs`: a web server on `127.0.0.1` only.
- `ui.rs`: its own windows (the see-through ones over RuneLite included), the tray icon, and Windows' symbol font. It acts only on windows of its own program, never on another that happens to share a title.
- `cursor.rs`: the portal's own cursor (ghillie.wiki's ✼), made from its pixels with `CreateIconIndirect` and shown with `SetCursor`. To show it, each window of the portal's own program is wrapped with comctl32's `SetWindowSubclass` (found with `EnumWindows`, kept to windows whose process is the portal's). No other program's window is ever wrapped, RuneLite's least of all, and no hook is set: past the portal's windows the cursor is Windows' own.
