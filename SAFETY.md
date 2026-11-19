# Is it safe for my account?

The short answer: the outstation does what streaming software does (OBS, Discord's screen share) and nothing more. It never touches RuneLite or the game, and it can't press a key or click for you. Everything below can be checked: the code is all here, and every release is built by GitHub from it, not on anyone's computer.

**Only download it from this repository's [Releases](https://github.com/jhmcubed/outstation/releases) page.** A copy from anywhere else isn't ours: never run an .exe you don't trust ([Checking it yourself](#checking-it-yourself)).

On this page:
- [In plain words](#in-plain-words): the questions people ask first.
- [What's in the picture](#whats-in-the-picture): what viewers can see, your login screen included.
- [What leaves your PC](#what-leaves-your-pc): everything the outstation sends, where to, when, and who sees it.
- [What comes in, and what it can do](#what-comes-in-and-what-it-can-do): everything it hears back, and what none of it can do.
- [What it does on your PC](#what-it-does-on-your-pc): what it reads and writes, its keys, and how it affects RuneLite.
- [What the relay keeps](#what-the-relay-keeps), and for how long.
- [Jagex's rules](#jagexs-rules), [checking it yourself](#checking-it-yourself) and [the technical detail](#the-technical-detail).

The window's own words, where this page quotes them: your **station key** is the code that attaches it to your channel; **static** (the static key, Pause to begin with, which you can change) is what this page calls pausing; **transmitting** is live and **dark** off air; **observers** are viewers; **the tape** is the replay buffer; **redactions** are masks; **the monitor** is the small view of what went out; **field reports** are Dink's posts.

## In plain words

| Does it… | |
|---|---|
| touch RuneLite or the game (read or change its memory, add to it, plug into it)? | **No.** It isn't a RuneLite plugin, and it never opens RuneLite's process. RuneLite runs exactly as it would without it. |
| play for you: press keys, click, move the mouse? | **No.** It has no way to send a key or a click. |
| see your password, bank PIN or account? | **No.** It never asks for your Jagex login, and it can't read what you type into RuneLite. It sees only what's drawn in RuneLite's window, as a stream does, so whatever's on the screen is in the picture. Press **Pause** before typing a bank PIN: no picture goes out while paused, and the half second before the press is taken back. Your login screen needs a moment's care: see [What's in the picture](#whats-in-the-picture). |
| read what RuneLite and Jagex say to each other? | **No.** |
| watch your keyboard? | **No.** Windows tells it only when one of its own four keys is pressed: the static key (Pause), the sound key (Scroll Lock), the clip key (Insert) and the redaction key (End). Like any program, it gets what you type into its own window, and nothing typed anywhere else. You can change each key. |
| show something you'd rather keep to yourself? | **Only what you allow.** Draw your own redactions over any part of the game (the chat, the map, the world number): those parts are blacked out on your PC, before a frame is made, so they never leave it. The redaction key turns them off and on. |
| show your mouse pointer? | **Only if you switch it on.** "my cursor in the picture" (off to begin with) has Windows Graphics Capture draw the pointer into the frames where it's over RuneLite. Windows draws it: the outstation never reads where the pointer is. |
| let viewers control anything? | **No.** Viewers can chat, vote, guess, point at a spot and send a ✼. Nothing they send can press a key, click, open a page, change a setting or reach RuneLite. The ring, the ✼ and the chat over the game are drawn in see-through windows of the outstation's own, over RuneLite, never in it, and clicks pass through them. Each can be switched off ("pings: the site" keeps the ring off your screen, "✼ on my screen" and "chat over the game" the others), and "pings: off" and "observers may chat" switch pings and the chat off altogether. |
| need admin, install a service, or start with Windows? | **No.** It's one program. It runs when you open it, and closing it ends it. If you say yes on its first screen, it puts one shortcut in your own Start menu (settings ▸ more takes it out). |
| need anything else installed? | **No.** Everything it needs comes with Windows 10 and 11: the safety check below fails a release that would need anything more. |
| keep a record of where I play? | **The outstation doesn't** (since 11/17/2026: the Outside plugin keeps your year, with its "Record my year"). It reads the square the relay says you were in only to pass it to nothing; your PC's record from before stays where it was, in `%APPDATA%\outstation\record`, until you delete it. |
| update itself, or download anything? | **No.** Once on opening it asks GitHub whether there's a newer version, and says so if there is. Getting it is up to you. Settings ▸ more turns the asking off. |

## What's in the picture

Everything drawn in RuneLite's game area goes out while you're live. The outstation can't tell a secret from a boss fight, so these are yours to mind:
- **Your login screen.** Logging in through the Jagex Launcher (a Jagex account), nothing's typed on it. An older account types its username there, often an email address, in plain sight (the password shows as stars). Transmit after you've logged in, or press Pause while you log in, or switch on **nothing while logged out** (settings ▸ more; it needs RuneLite's "username in title", so the outstation can tell). "transmit by itself whenever RuneLite opens" starts transmitting at the login screen, so pair it with "nothing while logged out" if you log in there.
- **The game's chat:** other players' names, private messages, your clan's chat. Black out the chat box (**redact**) if you'd rather not show it.
- **RuneLite's overlays** on the game area (a plugin's boxes, timers, names over players) are part of the picture. RuneLite's side panel and title bar aren't.
- **Your bank PIN:** press Pause first.
- **The chat, if you put it there** ("chat in the picture", off to begin with; 11/07/2026): the chat over the game drawn into each frame on your PC, as it sits over the game, so observers and your clips see it. It's your viewers' names and lines, which are in the chat on Outside anyway.

**Windows over RuneLite** (a browser, Discord, the outstation's own pings and chat over the game) stay out of the picture: Windows Graphics Capture takes RuneLite's own window, whatever covers it. The exception is the last fallback, used only when a PC draws RuneLite's window black both other ways (see [What it does on your PC](#what-it-does-on-your-pc)): then the picture is that part of the screen, so whatever's over RuneLite shows. The monitor in the outstation's window is your stream as it came back from the relay, so if something shows there that shouldn't, it's in the picture: try another "taken by" in settings ▸ more (every choice still falls back to the screen when the window comes back black, unless you tick "never the screen" there: then nothing goes instead). And if the outstation can't find RuneLite's game area inside its window, it takes the whole window, RuneLite's side panel too.

## What leaves your PC

This is everything the outstation sends. Before it's attached to a channel (a station key from the listening post, a friend's invite or a card, or requesting one), it sends only the version check, a request for a station key when you press **request**, an anomaly report when you press its send, and your friends list's look at the open outstations; and, in "clips only" (on the first screen), its clips to your own Discord (below, "To your Discord").

**To the relay in your station key, while you're transmitting** (Jace's, on Cloudflare, unless your code names another), and from there to whoever opens your Outside page (anyone with the link, or only those with your friends' link, if the Outside plugin's "Who sees it" makes it friends only):
- **The picture:** RuneLite's game area, shrunk (to begin with 384 px wide and 8 frames a second, which measured about half a megabit a second), as JPEG pictures, with your redactions already blacked out. On the shared relay one transmission's pictures go up to 20 Mbit/s at most (the relay sends every observer a copy); a frame past that waits for the next. Once a second, a smaller copy too (192 px wide), for the little live pictures: the outstations page, a friend's peek, your own monitor.
  - No picture goes out while you're paused, minimised, or logged out with "nothing while logged out" on.
  - Each frame waits half a second before it goes, and is dropped if you pause meanwhile, so the moment before a press of Pause never goes out.
- **Whether the picture's live, on static (Pause) or stopped,** so viewers' pages can say so.
- **The sound,** only if you turn it on (it's off to begin with):
  - "the game" is RuneLite's own sound.
  - "game + desktop" adds everything else your PC plays: music, videos, a Discord call's voices, Windows' own sounds. It goes as a second track, which viewers can turn down.
  - Never a microphone: the outstation only hears what your PC plays.
  - The sound carries on through static (Pause). With "the sound stops with the picture" on (settings ▸ more, on to begin with) it stops while RuneLite's minimised, closed or logged out (with "nothing while logged out" on), as the picture does, and none goes onto the tape; switched off, only going dark stops it.
  - With nobody watching, one picture goes every 10 seconds (so whoever opens your page first sees a recent one) and no sound at all; the rest is kept on your PC for the tape.

**To the relay, whenever the outstation's open and attached,** live or not:
- **The chat window's side:**
  - the lines you write, under your chat name, and the lines you take back
  - who you ban
  - your polls and drop guesses
  - your broadcast's title and your cap on viewers
  - whether viewers may chat and ping
- **Your field reports:** never from the outstation (since 11/17/2026). Dink sends them to your page itself, through the Outside plugin's "Copy my address for Dink"; the outstation only hears Dink to know when to save a clip.
- **Messages to a friend's outstation** (friends ▸, right-click a name, "message"): your words, with your chat name and channel. They go to their outstation's window only, never on either stream.
- **The monitor:** a socket to the relay with your channel and watch code, while you're attached, dark too, so the window can show what goes out.
- **Who sees your page:** once a minute while you're attached, a request with your key for your page's watch code (the Outside plugin sets it: friends only, or anyone; 11/17/2026), so the monitor, the clips and the emotes can open a friends-only page. The outstation no longer sets it.
- **Your emotes,** only those you drop on settings ▸ emotes: each picture and its name, for your page's chat (at most 30, each at most 256 KB).
- **Your outstation's version,** on every connection, so a relay can turn away one too old for it.

**Clips, to the relay and onto your Outside page,** only with "and put them on my Outside page" on (dink ▸, off to begin with):
- Only the clips that saved themselves, from what Dink told of: a pet, a new collection log slot, a 99, a drop worth what you set (a million to begin with).
- Each one is up to 17 seconds round the moment (12 before, 5 after, as much as the tape holds), at most 768 px wide, with its sound, a still, and up to 20 small frames for the hover.
- A clip is made from the same frames and sound as the transmission, after the same redactions, static and logged-out checks, whether or not they were sent (with nobody watching, most aren't). It shows nothing that couldn't have gone out.
- Never the clips you save with the clip key: those stay on your PC. So do all clips while the switch is off.

**To Jace's relay, when you report an anomaly or file a requisition** (the pink **report an anomaly** and the **file a requisition** beside it, at the foot of the window), only when you press **send to the listening post**:
- your words, this version, and the name you chat under
- for an anomaly, the station log, as "copy the station log" makes it: its last 400 lines, with your keys, codes and webhook addresses taken out first. A requisition carries no log.
- your channel and its key, only if the outstation's attached to Jace's relay, so the report says whose it is
- The drawer's other two ways send nothing from the outstation: **open the form** opens this repository's GitHub form in your browser (the log on your clipboard, for an anomaly), and the email address is only shown.

**To Jace's relay, when you request a station key** (the first screen's "no key yet? request one"), only when you press **request**:
- your player's name as you typed it, and your note for him
- a random code your PC keeps, to collect the answer
- While the request waits, the outstation asks the same relay every 20 seconds whether he's answered.

**To your relay, for invites** (friends ▸, "invite a friend"; 11/07/2026), only while the drawer's open:
- your channel and its key, to read how many invites are left (on opening it, then at most every 30 seconds)
- when you press **make a key**: the name you typed for your friend, with your channel and key
- The station key that comes back is only shown to you and copied when you press copy. You send it on yourself.

**To the aether** (the chat's "the aether" tab; 11/07/2026), only once you open it: your channel and its key, to join under your channel's name, then each line you send there. Everyone in the aether sees your lines, and the relay keeps the room's last 100 lines.

**For your friends list** (friends ▸), while anyone's on it, to your relay (Jace's, before you're attached):
- a request for the relay's list of open outstations, every minute (every 20 seconds while the list is open). Nothing about you or your list goes with it: the names are matched on your PC.
- With the hand over a live friend's name, it watches their outstation through the relay for a small picture of it, the way the outstations page does: not counted as watching, and stopped when the hand moves away.

**To your Discord,** only the clips, and only with "and send them to Discord" on (dink ▸) and a webhook address of yours given there (11/17/2026: Dink posts to Discord itself, as you have it set; the outstation no longer passes Dink's posts on):
- when a big moment's clip saves itself (a pet, a new log slot, a 99, a drop worth what you set), while you're transmitting with the tape on or dark with the tape rolling, a copy of the tape round that moment (up to 17 s, at most 768 px wide, under 8 MB), the same copy your Outside page would get, to that webhook.
- Nowhere else ever gets a screenshot or a clip.

**To GitHub,** once on opening (settings ▸ more turns it off): "is there a newer version?". Nothing about you goes with it.

**From the Outside plugin in RuneLite** (a separate plugin, only if you install it; it isn't part of the outstation, and it needs no outstation: since 11/15/2026 it makes its own page and key from its side tab, "Make my Outside page"). Its full list is [plugin/README.md](plugin/README.md), "What it sends, and only that"; in short, while you're logged in and "Send to my Outside page" is on, to your relay:
- **On to begin with**, each with its own switch: what you wear and carry (with your looting bag, rune pouch, quiver, seed box and chugging barrel once the game has shown them), item charges, your skills, hitpoints, prayer, run and special, your quests, diaries and combat achievements, your slayer task, and your levels, kills, pets, log slots, clues and drops as you get them (drops from 100,000 coins). Your page shows them to anyone with its link, and while you play your light shows on Outside.wiki's globe and your events on its feed, unless your page is friends only.
- **Off to begin with**, each behind its own warning: the rough area you were in two minutes ago (a 128-tile square) and your world, what you were doing, shooting stars you see, your bank, your deathbank (what a death in an instance left with the Item Retrieval Service, as the death made it and as its window shows it), your character's model and the ground under it, your exact path for your year, and the page's chat in your game.
- **Claiming and proving your name:** making your page sends your name and your account hash; proving it reads the official hiscores, never anything of yours.
- **In the Wilderness, a PvP area, or on a PvP or Deadman world** it stops sending and empties your gear and vitals on your page till you're out (Ferox Enclave and the PvP minigames are safe), unless you switch "In the Wilderness too" on. Your place is never sent from there, whatever you switch on.
- **Your year:** kept on your PC by the plugin itself ("Record my year", on), nothing of it sent unless you switch on its encrypted copy.
- **Friends only, and taking it down:** the side tab's "Who sees it" makes the page friends only (a link with a code) and takes it down altogether (everything on it goes, and the name is free again).
- Switching any part off takes it off your page.

Never your screen, your password, other players, or anything you type (only what follows ::o or /o, with the page's chat on).

**Your internet address.** Every connection shows your internet address (IP) to the server it reaches, as any connection on the internet does: Cloudflare (the relay), GitHub, Discord. The relay doesn't write it down (see [What the relay keeps](#what-the-relay-keeps)).

**Dark, with the tape rolling** ("clips only", or "and while dark too" in dink ▸; 11/07/2026): the outstation takes RuneLite's picture and sound exactly as when transmitting, but keeps them only in memory, the last few seconds, for clips. Nothing goes to the relay. A clip that saves itself goes to your clips folder and, with "and send them to Discord" on, to your clips' Discord webhook, and nowhere else (never your Outside page while dark).

Nothing else leaves your PC. Your settings, your log, your clips folder and your Dink settings stay where they are, unless you copy them somewhere yourself. Your webhook addresses go only to Discord itself. The safety check (below) proves the program has no other address in it.

## What comes in, and what it can do

Here's everything the outstation hears back, and what it does with each. All of it is words and numbers the outstation shows, or pictures it draws in its own windows. None of it is run, opened, or passed to RuneLite.

**From your viewers,** through the relay, which limits each line: one line at most 300 characters, its control characters taken out, 20 lines in 10 seconds:
- **Chat lines:** shown in the chat as text, and over the game if "chat over the game" is on (fading out after the settings' time), and drawn into the picture if "chat in the picture" is. A web address in a line is only text: the outstation never opens it. The outstation's taskbar button blinks when a viewer writes while it's behind other windows.
- **Pings** (a viewer pointing at a spot, one every half second at most): a line in the chat, and, with "pings: the site + my screen", a ring over RuneLite in the outstation's own see-through window.
- **✼ signals:** counted, and a ✼ floats over the game, with "✼ on my screen" on.
- **Votes and drop guesses:** the counts.
- **Who's watching:** the names they typed for the chat. The outstation is never told anything else about them: no address, no account.

**From the relay:**
- whether you're live, how many watch, the upload it's sending them, and the chat so far as the outstation opens
- whether this version is too old for it: the outstation then stops and says where to get a newer one
- a station key, once Jace issues the one you requested: the outstation attaches itself with it, as if you'd pasted it

**From friends' outstations:** their messages, in a chat tab of their own.

**From Jace, the relay's keeper:** a bulletin, one line in the chat, shown once. Apart from the answer to your own request and who sees your page (the relay's code for it, read once a minute since 11/17/2026: the Outside plugin sets it), it's the only thing from outside that changes anything in your settings: it writes which bulletin was shown last, so it isn't shown again.

**From GitHub:** the newest version's number. If it's newer, a line in the window says so.

**From Dink, on your own PC:** its posts, at `127.0.0.1`, an address nothing beyond your PC can reach. Read only to know when something big happened; none of a post goes anywhere.

**None of it can:**
- press a key or click
- reach into RuneLite or the game
- change what the outstation captures or sends
- open a web page: the outstation opens only ghillie.wiki's and Outside.wiki's pages (your page, a friend's, an old year record's), this one, and this repository's GitHub forms for a report, and only when you click
- start a program, or read your files

**What Jace can and can't do,** as the relay's keeper:
- He can:
  - re-key your channel, or stand it down (your outstation stops and says why)
  - send every outstation a bulletin
  - take a clip off your page
  - see the relay's list of channels: each one's version, time on air this month, title, and whether it's live and how many watch
  - read everything the relay keeps, below, since it's in his Cloudflare account
- He can't reach your PC, see your settings, your webhook addresses or your Dink settings, change anything in your outstation, or see anything it doesn't send.

## What it does on your PC

**It reads:**
- **RuneLite's window, as pictures,** with Windows Graphics Capture, the way OBS's Window Capture and Discord's screen share do. On a PC without it, or where it fails: PrintWindow (Windows asks RuneLite's window to draw itself into a picture). If that comes back black: that part of the screen.
- **Its sound,** if you turn the sound on, with Windows' app audio capture, the way OBS's Application Audio Capture does.
- **Window titles and the list of running programs,** once a second, to find RuneLite (and, for "nothing while logged out", whether its title names who's logged in).
- **Your PC's upload counter,** for the upload line.
- **The clipboard,** only:
  - when you paste (Ctrl+V, or a paste button)
  - once on opening, while it isn't attached yet, to see whether a station key is waiting there (anything else on the clipboard is left alone)
- **The pictures you drop on settings ▸ emotes,** only those.
- **What you type into its own window,** as any program does. While you're changing one of its keys in the settings, it reads the next key pressed in its window.

**It writes, and nowhere else:**
- `%APPDATA%\outstation`:
  - `settings.json`, its settings. **This file holds your channel's key and your clips' Discord webhook address:** anyone with it could stream on your channel or post to that Discord channel, so keep it to yourself.
  - `outstation.log` (`portal.log` before v0.2.0), the station log: what it did (started again past 2 MB). "copy the station log" (settings ▸ more) copies its last lines with the key, codes and webhook addresses taken out.
  - `crash.txt` after a crash (and `crash.txt.seen` once it's been mentioned), `outstation.log.old` (the log before it started again), and `outstation.ico`, its icon.
  - (`dink-before.json`, from an outstation before 11/17/2026 that read your Dink settings: no longer written; delete it if it's there, it holds your Discord webhook addresses.)
- (`%APPDATA%\outstation\record`, your year as the outstation recorded it from 11/10 to 11/17/2026: no longer written; it stays until you delete it.)
- `Videos\outstation` (or the folder you pick): your clips. A clip's copy for your Outside page passes through a file in your temporary folder, deleted once read.
- The Start menu: one shortcut, only if you say yes.
- The clipboard: only when you copy (Ctrl+C, or a copy button).

**It listens** only on `127.0.0.1` (port 8796 to begin with), for Dink. Nothing beyond your PC can reach it.

**It shows:**
- its own window
- its see-through windows over RuneLite: the pings, the ✼ and the chat over the game. Clicks pass through them.
- the ✼ by the clock (the tray). Minimising the window puts it there, and the stream goes on.
- Windows' own notification when a friend goes live, only with the Start menu shortcut

**It starts nothing** but your browser, on a ghillie.wiki or Outside.wiki page, this one, or this repository's GitHub form for a report, when you click a link; and Explorer, to show your clips folder or play a clip, when you click for one. It doesn't start with Windows, installs nothing, needs no admin, and closing it ends it.

**Its four keys are its own while it's open.** Windows hands Pause, Scroll Lock, Insert and End (or the keys you pick) to the outstation wherever you press them, so RuneLite doesn't get them while the outstation's open. If RuneLite or one of its plugins uses one of them, give the outstation another (settings ▸ hotkeys). A key another program already holds says so there.

**How it affects RuneLite:**
- Nothing inside RuneLite changes: its files, settings, memory and plugins are untouched. It runs the same with the outstation open or closed.
- Like streaming software, the outstation shares your PC: some CPU and GPU to copy and shrink each frame (more for more frames a second and wider pictures), and some of your upload (the window's upload line shows it). `outstation.exe --bench 30` times each step on your PC. If RuneLite slows down, lower the outstation's frames a second or its width.
- On Cloudflare, more viewers don't cost you more upload: the relay sends each of them their own copy.

## What the relay keeps

The relay (Jace's, on Cloudflare) passes your stream on to whoever's watching. Here is everything it writes down, and for how long.

**For you, streaming:**
- your channel's name, and the SHA-256 of its key (never the key itself)
- your watch code, if the Outside plugin set one (friends only), and your broadcast's title
- your cap on viewers, whether viewers may chat and ping, and the chat names your outstation has used (so no viewer can take one)
- the version of the outstation you run, and your time on air by month (the last four months, so Jace can see what the relay costs)
- the chat so far (its last 50 lines), the poll and the drop guess going on, and who you've banned
- the SHA-256 of each link you've made to tend your page's chat (the Outside plugin's **Tend my page's chat**, 11/16/2026), at most 4, each for 12 hours: never the link itself, which only your browser has
- the last 8 reports from Dink (each one's kind, its line and its numbers), and each boss's last kill count, for drop guesses
- this stream's summary, for your page's readings and the recap after it: the bosses and their kill counts, the loot's total and best drop, the levels reached, your collection log's count and the last 5 pets. It's replaced when you next transmit, unless that's within 15 minutes of the stream ending (then it carries on).

Your picture and sound are passed on as they come and never written down. Only the newest frame is held, in memory, for whoever arrives next, and it's gone when the relay sleeps.

**What's public:** while your channel has no watch code, the relay's list of open outstations (on the outstations page) shows that it's there, whether it's live, how many watch, its title, and the boss and kill count it's on while it's live. With following on, it also shows the square you were in two minutes before (its middle), only while you're live and only for 4 minutes after the plugin last said it; with "and my world" on too, the world you were on with it. With "what I'm doing" on, it shows what you were doing two minutes before (skilling and which skill, a boss, raid or minigame and which, fighting or idle), the same way. In an instance, both say where it's opened (11/09/2026). A watch code takes it off the list. On Cloudflare the relay keeps that list in its storage (since 11/10/2026, so it isn't rebuilt by asking every outstation each time that part of the relay wakes): everything it shows but the square and what you were doing, which are never stored (below, "Following"), and when your outstation last told it; your row's taken out when your channel's taken away or given a new key. With "shooting stars I find" and "name me as a star's finder" on, the relay's list of stars shows your outstation's name beside each star it told of first (its world, site and rough area, two minutes after you saw it), unless your channel has a watch code. Your page's game panel shows your world anyway while the plugin sends (it's one of the game's fields, as below), so with following on, someone who opens both can put your world with your square even with "and my world" off.

While it's live with no watch code, the relay's feed of what's happening across the Array (Outside.wiki's front page, 11/08/2026) also shows, under your channel's name and as each comes from Dink: a kill (the boss and its count), a level (the skill and level), a pet (its name), a drop worth 1,000,000 coins or more (its best item and its value), a collection log slot (the item) and a clue (its tier and count). Nothing that comes while you're dark, or while you have a watch code, ever goes on it.

**Messages between outstations** (your friends list's "message"):
- A message waiting for an outstation that's closed is kept until that outstation next opens: who it's from (their channel and chat name), their words and when. At most 20, each for a week.
- One delivered isn't written down anywhere. Your outstation keeps a conversation only while it's open.

**Your clips on your Outside page,** only if you switched it on:
- each one's copy, its still and its few small frames, and what it was ("pet vorki"), when, and how long
- At most 20, the oldest going first, and each for 30 days.
- Your outstation lists them (clips ▸), files up to 3 for good (never pushed out by newer ones, never too old) and takes any down. Jace can take any down too.

**For viewers:**
- the name they type for the chat, and their lines in it (the chat's last 50, everyone's together)
- a random number their own browser made, which isn't tied to them: it counts one vote per device in polls and drop guesses, and it's what a block or a ban holds on to

A viewer's address (IP) isn't written down, and the relay never learns their name in the game or anything about their account.

**Anomalies and requisitions** (only those you send): your words, the name you chat under, the version, the station log for an anomaly, your channel if it checked out, and when. At most 50, each for 30 days, until Jace reads it and takes it off. The address one came from is held in memory for an hour (5 an hour from one place).

**Invites** (11/07/2026): how many each outstation has used and the names it invited, its time on air in all (invites are earned from it), and, for an outstation established through an invite, whose invite it was. That last one's public, on the relay's list of open outstations. So is how many of your invites haven't been taken up yet (11/08/2026, a key beside you on Outside.wiki's globe for each), only a count and only while your channel's on that list. The names you invited never are: an invited outstation isn't on the list until its key is first used.

**The Outside plugin's game** (only if you use the plugin; 11/07/2026): the last of what it sent (what you wore and carried, your looting bag, rune pouch and quiver, your seed box and chugging barrel, your vitals, prayers and skills, your name, world and account type, and the names and prices of those items), so your page shows it while you're away. Saved at most once a minute while it changes, and when the plugin disconnects. Your page shows it to anyone who opens it, as your transmission. Taken away when your channel is. With their switches on (11/14/2026), your bank, your deathbank (11/16/2026; also taken away when you empty it) and your character's model too, one of each, until you switch them off (which takes them away) or your channel's taken back; and your emotes, uploaded from the outstation, until you take them away or your channel's taken back.

**Following** (only with the plugin's "Follow me on the globe" on; 11/08/2026): the last square of the map the plugin said you were in two minutes before (its middle, a square about 128 tiles a side, or Zanaris, as one place), and when it came. In memory only, never saved: a restart of the relay forgets it. That holds for the list of open outstations too, which the relay keeps in its storage since 11/10/2026: it stores your row without the square and without when it came, and keeps those beside it in memory only (after that part of the relay sleeps, it asks your outstation for them again, or waits till it's next told). The relay takes one every 50 seconds at most and ignores the rest. It's dropped when the plugin disconnects, when you log out, when the plugin holds back for your safety (the Wilderness, a PvP area or world; not Ferox Enclave or the PvP minigames), when the plugin clears it (somewhere dangerous, or following switched off), and when your channel is taken away or given a new key. It never goes to your page's viewers, its game panel, the Array's feed or the aether; from 11/10/2026 it also went to your own outstation's chat window (only your station key opens one) for a year it kept, and still does, though since v0.2.0 the outstation throws it away (the Outside plugin keeps your year). With "and my world" on, the world you were on is kept with the square, the same way.

**Your exact path: never sent** (since 11/17/2026). The plugin's "Record my exact path for my year" keeps your exact tile in your year's record on your PC only. (From 11/10 to 11/17/2026 it passed through the relay, kept nowhere, to your own outstation's app, for the year the outstation recorded; the outstation no longer keeps a year, so the plugin no longer sends it.)

**What you're doing** (only with the plugin's "What I'm doing, on the globe" on; 11/08/2026): the last word the plugin said for it (skilling and which skill, a boss, raid or minigame and which, fighting or idle), and when it came. Kept, dropped and shown exactly as following's square: in memory only (never in the stored list of open outstations either), one every 50 seconds at most, dropped when the plugin disconnects, you log out, it holds back for your safety or clears it, and when your channel's taken away or given a new key.

**Shooting stars** (only with the plugin's "Shooting stars I find" on; 11/08/2026): each star's world, its 128-tile square, its landing site's name, its size and when it was first and last told of, on one list for the whole relay, with the first outstation to tell of it that asked to be named ("name me as a star's finder") as its finder, and how many outstations told of it (11/09/2026). An outstation that didn't ask, or one with a watch code or an invite not taken up yet, is never named as a finder (only counted). Kept two hours at most, or till someone's plugin says it's gone, with the names of the outstations that told of each, for the count. To stop one outstation flooding it, the relay counts how many stars each channel told of in the last hour (30 at most), and forgets the count an hour after its last. On Cloudflare all of it is in the relay's storage there (since 11/10/2026; before, in memory, and that part of the relay sleeps seconds after it's last used), so it lasts through a sleep or a deploy, still only for those two hours: a star past them is taken out of storage within 5 minutes, read or not. Anyone can read the list.

**No longer kept: settling** (11/08/2026 to 11/10/2026). For two days an outstation could choose where it stood on Outside.wiki's globe (a spot you chose, rounded to two decimals, or an outstation to stand beside), with a one-time code its key was traded for. It's gone: the globe shows only real positions on its land, and every other outstation at the receiver. The relay no longer keeps or shows where you settled, when you moved it, or a settling code. On Cloudflare, what was stored then is never read again and is written out of storage the next time the relay saves your channel's details (going on air does); the open list's copy, at the list's next write. No release of the outstation ever had the button: only a copy built from the repository in those two days, which now hears "settling has gone".

**The Array's events** (11/08/2026): the feed above, the last 500 from every outstation together (each one's channel, what it was and when), and when each outstation's last few came (10 a minute at most from one). On Cloudflare in the relay's storage there (since 11/10/2026, Jace: "make the feed keep its events"; before, in memory, and that part of the relay sleeps seconds after it's last used, so an event was gone almost as soon as it came), still only the last 500: each new one pushes the oldest out. The relay's keeper (Jace, with the relay's admin token) can put back a real event the feed lost (11/10/2026: his own pet, lost that morning when that part of the relay slept), with when it happened, and nothing else: only the kinds and fields above, only for an outstation on the open list (never one with a watch code or an invite not taken up), never after now.

**Your year** (only with "keep an encrypted copy on the relay" on; 11/10/2026; `docs/record.md`):
- each day of your record from each of your PCs, **encrypted on your PC** with AES-256-GCM under a key made from your record key, which is made on your PC, kept in its settings, and never sent: not to the relay, not to ⊂λ,ｍ⊙, not to anyone. Without it nobody can read your year, Jace and Cloudflare included.
- the SHA-256 of the two tokens made from the key (by the Outside plugin; an outstation before 11/17/2026 made them too): one lets your page read the encrypted copy (it proves it holds the key without sending it), the other lets your page post or take down your public year.
- what it can see: how many days there are, how big each is, and when each was sent, so roughly which days you played and how much; never where, what, or which day each one is.
- At most 2,000 days, each kept 400 days after it was last sent. the Outside plugin's "Take my page down" takes all of it at once, tokens and public year included; so does Jace taking your channel back. A new station key from Jace leaves it as it is.
- If you lose the record key on every PC, the copy can never be read again, by you or anyone; each PC's own record stays on it, and a new key starts the copy again from them.

**Your public year**, only if you post one (from your year's page): the parts you chose (where you spent your time, paths, what you did most, your hours, places, moments, a replay, with or without their times; since 11/10/2026 also your days (how long you played each day, and at what), and your time zone, if you include it, so your hours show in your clock; where you spent your time as finely as 8-tile cells, and paths and a replay at exact tiles, if you choose them; and your collection log's slots, each with what it was, and if you choose when, what you were doing, the clip of it on your page and where, never finer than the heat you posted (128-tile squares without one)), kept as they are, **unencrypted, because you chose to make them public**, and shown on your Outside page to anyone who opens it, even with a watch code. Take it down whenever you like (your year's page; an old record kept by the outstation before v0.2.0: settings ▸ more, "your old year record"). It holds no words but your field reports' own (a boss's, a pet's or an item's name).

When the Outside plugin's "Open my year" opens your year in your browser, the record key goes in the address after `#`, which a browser never sends to any server (docs/record.md asks the page to take it out of the address bar once it's read it). It does stay in that browser's history.

**The aether** (everyone's room; 11/07/2026): its last 100 lines (each one's name, words and when, and for a visitor the random number their browser made, which a ban holds on to), and who's banned from it. A visitor's address isn't written down.

**Asking for an outstation:**
- the name and note you typed, and the SHA-256 of your outstation's pickup code
- once Jace issues it, the station key, until your outstation collects it

A request nobody answers goes after 14 days, and an answer nobody collects goes after 7. The address a request came from is held in memory for an hour (3 requests an hour from one place). An address that sends wrong keys is held in memory for a minute.

**The almanac** (Outside.wiki's globe; 11/09/2026): the newest of Jagex's news posts, the world list (each world's players, region, activity and flags) and the day's prices of the items most traded, as last fetched (below, "What the relay fetches"), and when each was. Nothing in it is about you or any visitor.

**The lookups** (Outside.wiki and ghillie.wiki's Outside page; 11/10/2026): a player's public records as Wise Old Man and RuneProfile show them (stats, gains, achievements, groups, recent collection log items), and item and boss icons, each as it was last fetched for a page that asked (below, "What the relay fetches"). A record is kept 14 days after it was fetched (an icon 90). They're the same public pages anyone can open on those sites; nothing about who asked is kept with them.

All of it lives in Cloudflare, in Jace's account. Cloudflare also keeps its own short-lived logs of the relay's requests, which only that account can see.

## What the relay fetches

The relay asks three outside places for anything, all for Outside.wiki's globe (the almanac, 11/09/2026), each on its own schedule and once for everybody: a page on Outside.wiki never asks them, and opening the globe never makes the relay ask them either.

| what | from | how often |
|---|---|---|
| Jagex's news posts (their RSS feed) | secure.runescape.com | every 20 minutes |
| the world list (how many play on each world) | oldschool.runescape.com | every 5 minutes |
| the day's Grand Exchange prices, and the items' names | the OSRS Wiki's prices API (prices.runescape.wiki) | the prices once a day (just after midnight UTC, then hourly only until the new day's out); the names once a day |

So the Wiki gets about two requests a day from the relay, and Jagex about 360, however many people open the globe. When a place doesn't answer, the relay keeps what it had and asks less often, not more. The one exception: the very first time the globe's read reaches a relay that has never fetched anything (just after it's first put up), that read waits while the relay asks each place once.

Each request says what it is and where to find us (the relay's User-Agent, `outstation-relay/1.0 (the almanac for outside.wiki's globe: fetched on a schedule and shared by every visitor; https://github.com/jhmcubed/outstation)`, which is what the Wiki's rules ask). Nothing about you or any visitor goes with it: no address, no name, nothing a page sent. These are public pages anyone's browser can open; the relay reads them as they are and passes on only the parts the globe shows.


Since 11/10/2026 it also looks players up for the pages (Outside.wiki's globe and ghillie.wiki's Outside page), so a visitor's browser never asks those sites itself: Wise Old Man (api.wiseoldman.net) and RuneProfile (api.runeprofile.com), for the player whose page or station menu is open (their stats, gains and timelines, achievements, groups, skills, quests, diaries, combat achievements, collection log and recent activity), and the item and boss icons from RuneLite's own icon server (static.runelite.net) and Wise Old Man's GitHub (through cdn.jsdelivr.net). These are asked only when a page wants them, then kept and shared: a player's record is asked at most about once an hour however many people look, an icon about once a month. The relay keeps under each site's allowance (Wise Old Man 15 requests a minute, RuneProfile 20) and waits when told to. The same User-Agent rule: it says what it is (`outstation-relay/1.0 (lookups for outside.wiki and ghillie.wiki's Outside page: …)`), and nothing about the visitor goes with it: only the player's name the page is about.

## Jagex's rules

The [Rules of RuneScape](https://legal.jagex.com/docs/rules/rules-of-runescape) forbid software that plays for you, changes the game, or "tries to look at or change the communications" between your device and the game worlds. The outstation does none of those. Jagex's [Fan Content Policy](https://legal.jagex.com/docs/policies/fan-content-policy) allows streaming your gameplay (it names YouTube and Twitch).

Two things to know, said plainly:
- **The chat.** The same rules list software that "has its own chat feature separate from our game chat that does not follow our rules". The outstation's chat is outside the game, as a stream's chat is, and it's yours to keep within Jagex's rules: right-click a line to take it off everyone's page, or to ban whoever wrote it.
- **Pings over the game.** Jagex's [client guidelines](https://oldschool.runescape.wiki/w/Update:Third_Party_Client_Guidelines) don't allow extra indicators for boss mechanics, "except in cases where this is a manually triggered external helper". A ping is a person pointing, in a window that isn't RuneLite's. If you'd rather not have even that during bossing, choose "pings: the site": viewers' pings then show only in the chat and on their own pages.

Nobody but Jagex can promise what Jagex will decide; their rules say they "may choose to mute or ban an account for any reason". What the outstation can do is stay well inside what streaming software does, and let you check that it does.

**Not Jagex's.** Created using intellectual property belonging to Jagex Limited under the terms of Jagex's Fan Content Policy. This content is not endorsed by or affiliated with Jagex. It isn't made by or affiliated with RuneLite either.

## Checking it yourself

> **The only official download is this repository's [Releases](https://github.com/jhmcubed/outstation/releases) page** (github.com/jhmcubed/outstation). ⊂λ,ｍ⊙ never sends the outstation any other way: a copy passed round in Discord, a DM, a forum or another website isn't ours, whatever it's called and however it looks. Never run an .exe you don't trust. RuneScape players are a favourite target of programs that pretend to be game tools and steal accounts, so before you run any copy, check its SHA-256 against the release's ([SAFETY.md](SAFETY.md), "Checking it yourself"). If someone offers you an outstation from anywhere else, don't open it, and tell the listening post (research@ghillie.wiki).

1. **The code is all here**, under the MIT licence. The client is `client/src`, 32 files. Each one says at its top what it does.
2. **Releases are built by GitHub, from this code.** Each release lists the commit it was built from and the program's SHA-256. Once the repository is public, each also carries GitHub's build attestation: [GitHub CLI](https://cli.github.com) can confirm the file came from this repository's own build, unchanged:

   ```bash
   gh attestation verify outstation.exe --repo jhmcubed/outstation
   ```

3. **The safety check runs on every release's own program.** Any Windows program lists, inside itself, every Windows function it can call, the libraries' included. `tests/safety_check.py` reads that list out of the file and fails the release if anything that could reach into another program, play for you or watch the keyboard is in it. It also lists every library the program loads, and fails if any of them doesn't come with Windows. It then checks the client's own code the same way, and checks that the only addresses written into it are these:
   - `relay.ghillie.workers.dev`, the listening post's relay
   - `api.github.com/repos/jhmcubed/outstation/releases/latest`, the check for a newer version
   - `github.com/jhmcubed/outstation`: SAFETY.md and the report forms, opened in your browser
   - `ghillie.wiki` and `outside.wiki`, the pages it opens in your browser (the Outside pages are on Outside.wiki since 11/16/2026)
   - `127.0.0.1`, this PC, for Dink and for a relay running here for testing (the Worker under `wrangler dev`)
   - `discord.com/api/webhooks/` and `discordapp.com/api/webhooks/`, the start of a Discord webhook address you give it for your clips (the address itself is yours, never written into it)

    Run it on your own copy (Python, nothing to install):

   ```bash
   py tests/safety_check.py outstation.exe
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

Three of them are in the program's list all the same, brought by the libraries its window is built on. Each serves the outstation's own window only:
- `SendInput`:
  - **winit**, the window library almost every Rust desktop program uses, sends one pretend Alt key when asked to bring its own window to the front, a known workaround for Windows' focus rules (`winit/src/platform_impl/windows/window.rs`). The client never asks it to: it brings its window forward with Windows' own `SetForegroundWindow`.
  - **muda**, the tray menu, has it for ready-made copy and paste menu items. The client doesn't use them.
- `GetAsyncKeyState` and `GetKeyboardState`: winit reads whether Shift, Ctrl and Alt are held when its own window gets the keyboard focus (`winit/src/platform_impl/windows/keyboard.rs`).
- `Module32FirstW` and `Module32NextW`: Rust's standard library lists its own program's parts for a crash report.

The check fails on any function from the list that isn't one of these, so a new one can't slip in unseen.

Everything it touches in Windows and on the network, by file:
- `capture.rs`:
  - RuneLite's window as pictures: Windows Graphics Capture, or PrintWindow, or the screen if those come back black
  - the window's size and place, and whether it's minimised
- `transmit.rs`: the frames to the relay's transmit socket (each held half a second first), and the log.
- `sound.rs`: process loopback (`ActivateAudioInterfaceAsync`): one program's sound, or everything but it, cut into Opus packets.
- `win.rs`:
  - finding RuneLite's window and its process (window titles, the process list)
  - your network's byte counter (`GetIfTable2`)
  - the clipboard (when you copy or paste with Ctrl+C, X and V or the copy buttons; for the station key, and once on opening while not attached)
  - opening a page in your browser (`ShellExecuteW`), refused for anything but ghillie.wiki's and Outside.wiki's pages, this one and the repository's report forms
  - blinking the outstation's own taskbar button (`FlashWindowEx`)
- `hotkeys.rs`: `RegisterHotKey` for its four keys (and, while you're changing one in the settings, a press of one of them handed to the window instead of acting).
- `autolive.rs`: going live by itself when RuneLite's window appears, and ending the stream 30 s after it's gone (both only if you switch them on).
- `desk.rs`, `net.rs`: the chat window's socket to the relay, and the sockets themselves (TLS from rustls).
- `dink.rs`: a web server on `127.0.0.1` only, for Dink's posts, read to know when a clip should save itself; the clip to your Discord webhook.
- `clip.rs`: the replay buffer, written as an .mp4 by Windows' Media Foundation (its H.264 and AAC encoders), into your Videos folder; and, for your Outside page, the copy of its own (through a file in your temporary folder, deleted once read).
- `clipsup.rs`: with "put them on my Outside page" on, that copy sent to your relay, and the list of what's there.
- `aether.rs`: the aether, one chat room for every outstation and visitor, through the relay, while its window is open.
- `emotes.rs`: your own emotes: pictures you drop on settings ▸ emotes, sent to the relay for your page's chat.
- `invites.rs`: a station key made for a friend through the relay, and the invite text to send them.
- `whosees.rs`: your page's watch code, read from the relay with your key once a minute.
- `chatpic.rs`: the game's chat drawn into the picture, with that switch on. Nothing of Windows or the network.
- `stuck.rs`: a watch on its own window, so a stall is written in the log.
- `feed.rs`: the monitor (a socket to the relay with your channel and watch code, while you're attached, dark too), and a friend's peek: a channel watched through the relay as a looker (not counted as watching).
- `friendlist.rs`: the relay's list of open outstations, read while your list has anyone on it.
- `ask.rs`: asking for an outstation, and looking for the answer.
- `report.rs`: an anomaly or a requisition sent to the listening post's relay, only when you press send; the GitHub form's address.
- `notify.rs`: Windows' own notification when a friend goes live (`ToastNotificationManager`).
- `shortcut.rs`: the Start menu's shortcut (Windows' shell link, with the program's id for its notifications).
- `crash.rs`: a crash written into the log and `crash.txt`, and Windows' message box saying so.
- `settings.rs`: the settings file.
- `ui.rs`: its own windows (the see-through ones over RuneLite included), the tray icon, the check for a newer version on GitHub, and Windows' symbol font. It acts only on windows of its own program, never on another that happens to share a title.
- `cursor.rs`: the outstation's own cursor (ghillie.wiki's ✼), made from its pixels with `CreateIconIndirect` and shown with `SetCursor` (at the window's edges, Windows' own resize arrows, `LoadCursorW`). To show it, each window of the outstation's own program is wrapped with comctl32's `SetWindowSubclass` (found with `EnumWindows`, kept to windows whose process is the outstation's). The same wrapping reads the key pressed in the outstation's window while one of its keys is being changed. No other program's window is ever wrapped, RuneLite's least of all, and no hook is set: past the outstation's windows the cursor is Windows' own. On its own see-through windows over RuneLite it also turns off Windows' shadow, border and rounded corners (`DwmSetWindowAttribute`).
- `main.rs`, `theme.rs`, `clock.rs`: starting up, the look, and the frames' clock. Nothing of Windows beyond the rest.
- `station.rs`, `keeper.rs`: the keeper's build only (above).
