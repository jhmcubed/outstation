# The Plugin Hub submission (11/16/2026)

What goes to runelite/plugin-hub for Outside's first version: the lean cut (`plugin/hub_cut.py`), merged into
jhmcubed/outside-plugin's `main` as 0b90619 (jhmcubed/outside-plugin#1 and #2), checked to match the cut exactly.

## Before every update (11/19/2026)

Update 4 went out compiling and passing every test, and wouldn't switch on in anyone's RuneLite (a listener misnamed
for RuneLite's event bus: "Update 5", below). So before any commit= goes on the fork for the Hub, in this order:

1. `gradle test` and `hub_check.py`, for the whole plugin and for the cut (`hub_cut.py <folder>`): `SubscribeTest`
   checks every listener's name as the event bus does.
2. `python3 plugin/start_check.py <outside-plugin's checkout at the exact commit the Hub will get>`: RuneLite started
   with it, in a home of its own, until "Plugin OutsidePlugin is now running" with nothing of the plugin's failing
   after. It fails on update 4 as merged. GitHub runs it on every pull request here too (tests.yml, the plugin job).
3. Tested in the game (11/19/2026, rsbfox on update 5: "Please don't submit code to the Hub without testing it
   in-game", with `-ea`, which turns on RuneLite's assertions, such as a game method called off the client thread:
   off in release, so the same mistakes are errors for players). Jace runs the exact commit, `py run.py client` in a
   checkout of jhmcubed/outside-plugin at it (run.py starts RuneLite with `-ea`), logs in, and tries each change from
   a list Claude writes (what to press, what should happen, what the client log mustn't show); a cloud session can't
   log in to the game, so this step is his.
4. Only then the commit= on jhmcubed/plugin-hub's branch, once he says it works.

Keep an update small: a fix alone goes on its own, and a new feature waits for the next update once one is in review
(update 4 grew from size-s to size-m while it waited, and took 18 hours to merge).

## The file: `plugins/outside`

```
repository=https://github.com/jhmcubed/outside-plugin.git
commit=0b90619d2a45013494ee1f045b2ee1c78e0fd90a
warning=This plugin submits your character's name and account hash when you make your page, your levels, kill counts, collection log slots, pets, clue counts, quests, diary tiers, combat and slayer tasks and valuable drops, and your equipment, inventory, looting bag, rune pouch, item charges, skills, vitals, quests, achievement diaries, combat achievements and slayer task, and your IP address, to the Outside relay, a server not controlled or verified by the RuneLite developers; your Outside page at outside.wiki shows them publicly.
```

## The pull request

Title: `Add Outside`

> Outside puts what you wear and carry, your skills, quests, diaries, combat achievements and slayer task on your own
> page at outside.wiki, live while you play and as last seen while you're away, with a feed of your levels, kills and
> drops.
>
> - Each part behind its own switch; a page is made from the side tab under the character logged in, and proven theirs
>   by the official hiscores (no login, no password).
> - Only the player's own state: nothing about other players, no location, nothing from the Wilderness, a PvP area or
>   world (it pauses there), nothing the player types.
> - One WebSocket to the relay through RuneLite's OkHttp, and the third-party warning above (also on the setting that
>   sends). No overlays, menus, input or game changes.
> - Every piece of information it reads or sends is matched with a Plugin Hub plugin (or RuneLite's own) that already
>   does the same, at pinned commits: https://github.com/jhmcubed/outstation/blob/2b2f2713fd66262593e17b4f6ad7e211fdce5686/docs/hub-precedents.md
>
>   (That page covers the whole plugin as it's planned; this first version is the part without location, the year,
>   the model, the bank and the chat, each of which would come later as its own update.)
>
> Source: https://github.com/jhmcubed/outside-plugin (BSD 2-Clause).

## After it's merged

- The "Open your own" card's Hub link (ghillie's `outside-splash/open-panel.js`, `HUB`) checked against the real one.
- Update 1 (following and the globe): add `globe` to `hub_cut.py`'s SUBMITTED, cut into outside-plugin, merge, and a
  plugin-hub pull request with the new commit; set open-panel.js's `FOLLOW` true once it's on the Hub.
  Also in update 1 (11/16/2026, Jace, after a press inside the minute went unnoticed and his page kept a necklace he'd
  taken off): a press skipped by the once-a-minute limit made obvious, the button saying how many seconds are left
  until it can update, not only the status line's "updated a moment ago". And (Jace) the delve board for everyone: till
  then only his page shows its numbers, kept from the whole plugin (a snapshot leaves `delve` as it was; docs/protocol.md,
  "What a snapshot clears").

## Sent (11/16/2026)

The file is on jhmcubed/plugin-hub's branch `outside` (ea2f261, from runelite/plugin-hub's master); the pull request
to runelite/plugin-hub is opened from it with the words above (Jace's press: this repository's session can't write to
runelite's). After its build: a ✔ beside `build`, and `RuneLite Plugin Hub Checks` saying nothing's needed; changes
asked for go into outstation's plugin/, cut into outside-plugin, merged, and the new hash pushed to that branch.

## The snapshot version (11/16/2026)

The reviewer turned the lean cut down, then reopened #20811 for other reviewers: "An 'Update my page' action and an
hourly sync should be okay, as should matching Discord activity." The Hub's version is now the snapshot
(`plugin/hub_cut.py`'s header; docs/protocol.md, "The snapshot"), cut into jhmcubed/outside-plugin on its branch
`claude/laughing-ride-b0yjzb` (a600302; 31 tests, the Hub check, `hub_cut.py --check` matching).

In this order:
1. Merge outstation's branch, then `npm run deploy` in `relay/` (the Worker must take `POST /game` before any Hub
   player posts to it: the live relay answers 405 till then).
2. Merge outside-plugin's branch into its `main` (the commit the Hub builds should be on `main`).
3. On jhmcubed/plugin-hub's branch `outside`, `plugins/outside` becomes (the commit: outside-plugin's `main` after
   the merge, `git rev-parse HEAD` there, the full 40 characters):

```
repository=https://github.com/jhmcubed/outside-plugin.git
commit=<outside-plugin main's full hash>
warning=This plugin submits your gear, stats, quests, diaries, slayer task, what you're doing (as Discord shows it), your account hash and your IP address to a 3rd-party server when you press Update my page, and hourly. Your Outside page shows them publicly.
```

   Pushing that branch updates runelite/plugin-hub#20811 itself (no new pull request), and its build runs again.
4. A comment on #20811 saying what changed (drafted in ghillie's claude/permissions-review.md, "The snapshot, and the
   reply").

## The reviewer's second answer (11/16/2026)

"Please remove the exe download from the website the plugin directs players to, and don't link to or mention it in the
README… make the README end-user focused, since it's published on the RuneLite website at the pinned commit… move any
build instructions into a CONTRIBUTING.md or remove them." Done: outside.wiki shows no outstation.exe (ghillie's
claude/permissions-review.md, "The Hub's second answer"); the cut's README.md is `plugin/README.hub.md`, for players
only, and its CONTRIBUTING.md `plugin/CONTRIBUTING.hub.md` (`hub_cut.py`, HUB_FILES). The order above stands, with
outside-wiki's branch merged (it deploys outside.wiki) before the comment, so the reviewer finds the site already
changed.

## The first update, merged (11/17/2026)

runelite/plugin-hub#20826, at outside-plugin 88f3f23: the update countdown and Show what's sent, the delve board for
everyone, the collection log's count, Copy my Dink address, kill and clue counts. Before merging, the reviewer (rsbfox)
rewrote the warning: "The warning should not really be updated ever because it's only shown on install so I've gone
ahead and made it shorter while covering you for current and future uses. The not controlled or verified by Runelite
developers was also missing." The manifest now reads:

    warning=This plugin submits your IP address, and may submit various account information, to a 3rd-party server not controlled or verified by Runelite developers. Your Outside page shows them publicly.

So a later update changes only `commit=`: never the warning. Following (update 1's first idea, `globe` in
`hub_cut.py`'s SUBMITTED) was never sent: the snapshot's Discord presence took its place, so open-panel.js's `FOLLOW`
stays false.

## Update 2: the model, the bank and the deathbank (11/17/2026, built, not yet sent)

`hub_cut.py`'s SUBMITTED adds `model`, `bank` and `deathbank` (each on to begin with since 11/17/2026, Jace: "we should leave everything on by default"; a warning when switched back on), under the
reviewer's terms ("an 'Update my page' action and an hourly sync"):
- **The model:** sent when the player presses Capture my character (or `::outsidemodel`, `::outsidemotion`), their own
  press, as Update my page is. The live view (the model following their gear and animation every tick over the socket)
  and the encounter (the boss fought) stay out: they're fenced as `live` inside the model's regions now.
- **The bank and the deathbank:** read when the player uses the bank or a deathbank is made or looked in, as before, but
  in the Hub's version held (the latest of each, `keepHeld`, `HOLD_KEEPS`) and sent after the next update's post
  (Update my page, or the hourly one); the chat says so the first time. A switch turned off takes its part down at once.
  The whole plugin still sends each at once.
- `README.hub.md` says what each sends and when; the settings' descriptions and warnings say "with your next update" in
  the Hub's version (`hub:only` lines).
The manifest's warning stays as the reviewer wrote it; only `commit=` changes.

## Update 3: the goal item (11/18/2026)

Update 2 merged as runelite/plugin-hub#20872 (11/17/2026 23:24 UTC). Update 3 adds `goal` to `hub_cut.py`'s SUBMITTED
(Goji Berries' idea; Jace: "Cut update 3 as it stands"): **Set my goal item** on the side tab's Page tab, or
`::outsidegoal`, opens RuneLite's own item search in the chatbox (`ChatboxItemSearch`); the item picked goes to the
page's keep with its model, as the game draws it, on that press. When the game's collection log line names it, its "got"
(the time, the kill and clue counts then) is held in the Hub's version and sent after the next update's post, as the
bank's is: the reviewer's terms, a press or the hourly sync. Its own switch, "Goal item", on to begin with, with its
warning. And Outside.wiki with its capital O in what the player reads (no change to what's sent). The manifest's
warning stays as the reviewer wrote it; only `commit=` changes.

Cut into outside-plugin (jhmcubed/outside-plugin#18) at `63d66ae097dac04bc1a4daf4da4d851fa0f37f69`; `plugins/outside`'s
`commit=` set to it on jhmcubed/plugin-hub's branch `claude/keen-hopper-89gwe5` (1f332d1, from runelite/plugin-hub's
master), the pull request to runelite/plugin-hub opened from it by Jace. Once it's merged: ghillie's
`public/outside-portal.js` `ON_THE_HUB.goal` on (the profile's "no goal set" line).

## Update 4: the goal's model (11/18/2026)

Update 3 merged as runelite/plugin-hub#20919 (11/18/2026 07:58 UTC). Jace's first goal (the Ascension crossbows) reached
his page without its model: `sendGoal` copied the model's textures (`cloneTextures`) whether the item had any or not,
and an item with none threw there, the whole model let go. Now each copy only as the item needs it (its recolours,
its retextures), the model sent unlit if lighting it fails, and why it couldn't be read said in the chat. Only
`commit=` changes.

Then, before its review, three more in the same update (Jace, 11/18/2026, "do all three"): the goal got from the loot
itself as well as the collection log's line (RuneLite's `NpcLootReceived`, and the Loot Tracker's `LootReceived` for a
raid's chest or a casket when that plugin is on; the counts then still held for the next update, as the Hub's terms
ask); the goal shown on the Outside side tab with a ✕ that clears it; and the note when a goal has no drop rate, which
is the page's (outside.wiki's `listed`, from tools/drop_sources.py), not the plugin's: the plugin never knows the rates.

And Jace's look at the side tab (11/18/2026): Set my goal item moved from the top buttons into the Page tab under "Your
goal"; the More tab folded into the Page tab (his order: your goal, who sees it, Dink, the register, then Take my page
down last), so two tabs, Page and Model; and the line under Capture my character keeps to the model's own messages
(every line the plugin said had gone there, so "your goal is on your page" sat under the capture button).

Jace updated the pull request's title ("Update Outside: goal fixes and the side tab's Page tab") and description to list all of it (11/18/2026, after the bot's cooldown, before any review).

And Capture a still (11/18/2026, Cranky, a friend, through Jace: "a 'capture still' capturing the frame i click it like runeprofile does"): a button under Capture my character sends the model as it is the moment it's pressed (mid-emote too) as the page's still, on the player's press like ::outsidemodel, which now does the same. Until one is set, the still is the last capture's first moment, as before; once one is, captures leave it alone (an RS-profile flag, stillSet, cleared when the model's taken off or its switch turned off).

And Talk in the aether as me (11/18/2026, Jace): the Page tab's "The aether", after the register: opens the page with its key in the fragment (#aether=<key>, no server sees it; the page takes it out of the address at once), so the aether lets its owner in under the page's name without pasting the key. On the press only; nothing sent by the plugin.

Merged: runelite/plugin-hub#20922 merged 11/19/2026 02:51 UTC, at outside-plugin af8d683 (Jace had moved commit= one step past 11fea55, to take in the other session's "Effects at their own height, not raised twice"). The bot's compare link was its only comment; no review comments; labels plugin change and size-m (size-s until the second push).

## Update 5: the plugin switched on again (11/19/2026)

Update 4 merged 02:51 UTC and wouldn't start: RuneLite's event bus refuses a listener not named "on" and its event's name, and the goal's loot listeners were goalOnNpcLoot and goalOnLoot ("Subscribed method ... should be named onNpcLootReceived", PluginInstantiationException; found by starting the merged version in RuneLite under Xvfb). Now onNpcLootReceived (which hands the field reports theirs in the whole plugin) and onLootReceived; SubscribeTest checks every listener's name, and fails on update 4 as merged. The fixed Hub version started in RuneLite: running, both loot listeners registered.

The start check built after it (`plugin/start_check.py`, in tests.yml too): fails on update 4 as merged (the
PluginInstantiationException), passes on the fix, both the cut and the whole plugin, and on outside-plugin d6937fe
itself, the commit the fork's branch then named (restarted from the Hub's master, its update 4 branch merged).

Merged: runelite/plugin-hub#20967 merged 11/19/2026 06:02 UTC, at outside-plugin d6937fe, five hours before the bot's
"next review no sooner than 11:16:25Z" (a maintainer deleted that comment and merged it: the plugin was broken). rsbfox
commented first: don't submit to the Hub without testing in the game; run the client with `-ea`, whose assertions catch
mistakes like game methods called off the client thread, off in release. Step 3 of "Before every update" came from it.
Update 4's new code read for that kind of mistake afterwards: each game call it makes (the goal's item names and
canonical ids, the still's model) runs on the client thread (loot events, chat commands and the item search are
posted there, the side tab's buttons hand theirs over with clientThread.invokeLater); the Page tab's Talk in the
aether as me reads only the plugin's settings.

## Update 6: the key kept and found, the delve levels a death lost, more than one goal, encounters (11/19/2026, encounters 11/21/2026, rounds two and three the same day; built, waiting for Jace's test in the game)

Jace (11/19/2026): "Update 6 will include this and the other features we've added since update 5": the key (below),
the delve levels a death lost (held till now as jhmcubed/outside-plugin#27, which this update carries, so that pull
request isn't needed once it's in) and more than one goal. outside-plugin's claude/practical-rubin-pl0ob2 is the whole
cut of this folder (`hub_cut.py --check` matches), with `gradle test`, `hub_check.py` and `start_check.py` passing on it.

### The key kept and found

Jace's PC lost sburb's key on 11/19, the morning his MacBook first ran Outside (RuneLite's cloud sync between the two the likely cause; not the plugin, not the uninstall, not RuneLite's install defaults: each read in RuneLite 1.13.7's own code). His key, from his outstation, was only in Advanced, Station key: the one kept with the account is only made by Make my Outside page. And a player with only the Hub's plugin had no way to see their key at all, for another computer. So:

- A pasted key the relay takes (an update answered 200) is kept with the account too (`keepKey`), a second copy that a settings Reset or an emptied setting doesn't lose.
- **Copy my station key** in the Hub's version too, the Page tab's "Your page's key", for Outside on another computer.
- The no-page box and the "proven or vouched for already" answer say where the key is: the Page tab on a computer that shows the page (and, in the whole plugin, the outstation's settings).

Not in it, Jace's call: a proven page whose every key is lost can't be taken back (a proven name takes no challenge).

### The delve levels a death lost

A death in a delve loses every drop of its run, but the board beside the Doom of Mokhaiotl counts the run's levels all the same, so a page's odds from the board ran high for someone who dies mid-run (SaluteCFlagg, through Jace). `Delves.java` counts each level done from the game's chat ("Delve level: 3 duration: …", as the Delve Calculator plugin does), a run over once they're away from the boss, and adds a run's levels to those lost when they die in it; kept on the profile (`delveLost`) and sent as `delve_lost` beside the board, as they stood when it was last read (outstation c6d822e; the relay's half and the page's are live since 11/19/2026, outstation#64 and outside-wiki#98).

### More than one goal

Cranky, on Discord: "I'd like to have more than 1 goal item. maybe the button would be 'add a goal item' and it would just add to the list, and you'd just have to x any you don't want anymore". Jace: "we want to be able to choose ONE of the goal items to be displayed as a 3d model, while the rest of the goal items are listed under the first and each other with their dryness bars."

- **Add a goal item** (was Set my goal item; `::outsidegoal` the same) adds the item picked to the account's goals, 10 at most (`Goal.MOST`; the button waits when full, its tip says why). Each goal is a row in the Page tab's **Your goals**: a choice of the one shown (its 3D model on the page; the first added is shown, and taking the shown one off shows the first left) and a ✕. `::outsidegoal clear` takes them all off. Over the rows, **No 3D model** (Jace: "add the option to NOT show a 3D model on your page for your goal/goals list"): every goal listed on the page with its picture, none turning, none's model sent; a goal's dot turns that one again.
- Kept for the RuneScape profile as one setting, `goals` (`{"shown", "model"?: false, "list": [{"id", "got"?, "counts"?}]}`, `Goal.Kept`); the one goal kept before (`goalItem`, `goalGot`, `goalGotCounts`) is read into it once and let go.
- Sent as the keep's `goal`: the shown one as before (its model), the rest as `"more"` with no model (the relay's `keep.ts`, `GOALS_MORE`; docs/protocol.md). A change goes 2 seconds after the last press (`sendGoalSoon`), so a few ✕s in a row are one upload: the relay takes 24 an hour of every kind together. Each goal is got on its own (the collection log's line, or the loot), with the counts then.
- An item whose model can't be read is said once, not with every change to the list.

Order: the relay's half (outstation's `relay/`, e162bfc) goes live before this update: an older relay keeps only the fields it knows, so it would drop "more" (the goal at the top still shown), not refuse it. Then ghillie's profile (the list under the goal) synced to outside.wiki, and its `ON_THE_HUB.goals` on once the Hub has merged this.

### Encounters (11/21/2026)

Jace (11/21/2026): "include it in update 6 along with our other changes". Encounter Recorder (Mr Gnome's, on the Hub, BSD 2-Clause) folded into Outside as the part `encounters` (hub_cut.py, now in SUBMITTED), so a player needs only Outside: plugin/README.md, "Encounters", has all of it; ghillie's claude/outstation.md, "Encounters on Outside: the night's design", the why.

- **Recorded to the player's computer**, in `.runelite/plugin-data/outside/encounters` (`getPluginDirectory()`, which needs the descriptor's `internalName = "outside"`, the Hub's own name: the Hub's rules let a plugin's files go only through `Filepath`), in his file format (his docs/FORMAT.md, format 6) with Outside's additions in new fields and the new line kind `obj_anim`, so his replay and outside.wiki read both plugins' files. On a thread of its own, crash-safe as his; the newest kept within "Keep up to (MB)" (200).
- **By itself at bosses and raids** (RuneLite's Discord plugin's boss and raid areas, `Presence`; not Wintertodt or Tempoross) and for listed NPC ids (his list, and now a form change too), with "Record my encounters" on (on to begin with) and a page for the character; **by hand** from the side tab. **Ends** on the kill (the game's count line), the player's death (not in a raid) or leaving; splits at 5,000 ticks where his stopped. **Never** in the Wilderness, a PvP area or on a PvP or Deadman world (`OutsidePlugin.held`).
- **His gaps closed**, each checked in the local world's test fight (`fightcheck.py`: 55 of 56 on time, the sound untested for want of an audio device): the starting state (varps, ground items, graphics, projectiles in flight), a restarted animation, the raids' friends-chat completion lines (names taken out), objects' animation changes, projectiles over in a cycle.
- **Left out entirely**: mouse presses, clicks as his recorder writes them (the option, the target's name, the cursor), their screen places, the interface layout, markers, hotkeys and his overlay. (Round five, 11/21/2026, adds only which kind of click the game answered with its cross, "walk" or "act", and where in the scene: never the cursor.)
- **The side tab's third tab, Encounters**: Record my encounters, Record now / Stop, the recent ones (who, when, how long, killed or died), each with Put on my page (a window first, saying what was taken out and what goes), shown to anyone, Open, and ✕.
- **Sent only on the player's press** (round one; since round two, below, each goes up by itself unless the player switches that off): the recording stripped exactly as the page's strip.js strips one (other players' names, other people's chat, exact XP; checked byte for byte against strip.js), to the relay's existing `POST /fights/<channel>?key=`. Opening the tab reads `GET /fights/<channel>?key=` (what's on the page, whether a new one starts shown, and whether the relay takes encounters at all: its fights are off until Jace switches them on, and the tab says so plainly).

**For the reviewer.** This adds the first thing in the Hub's version that reads other players: with "Players around you" on (as his default), a recording keeps the players near you (positions, gear, animations), as Encounter Recorder does on the Hub. It's on the player's computer; only Put on my page sends one fight, every other player's name taken out first (in any text too) with their chat. The manifest's `warning=` gains it:

```
warning=This plugin submits your gear, stats, quests, diaries, slayer task, what you're doing (as Discord shows it), your account hash and your IP address to a 3rd-party server when you press Update my page, and hourly; and each boss fight it records as it ends (with a moment of its scene), other players' names taken out, unless you switch that off. Your Outside page shows them publicly.
```

(Not used, and never to be: the reviewer's warning from the first update stays for good, "covering you for current and
future uses" ("The first update, merged", above); an update changes only `commit=`.)

The order: nothing on the relay or the page changes for it (the routes are the relay's own since 11/20/2026); the relay's `FIGHTS` switch stays Jace's call, and until it's on the tab says encounters aren't taken yet and keeps them on the computer.

### Encounters, round two: each one up by itself, kept for good, featured, its scene moment (11/21/2026)

Jace (11/21/2026, his answers to the night's lab): "having it automatically upload every encounter takes the tediousness
out", with some kept on the page for good, any taken down from the side tab, and the featured one's moment kept.
ghillie's claude/outstation.md, "Encounters, round two", has the design; plugin/README.md, "Encounters", all of it;
docs/protocol.md, "The Outside plugin as a sender", the routes.

- **Put each encounter on my page** (a new setting, on to begin with; also a box in the Encounters tab): each recording
  goes up by itself as it ends (`EncounterQueue`: one at a time, off the client's thread; a 429 waits its Retry-After;
  out of reach, later, never twice: one whose answer never came is looked for on the page first). Never one stopped in
  the Wilderness or PvP. Shown or kept to themselves as the page starts new ones (the relay's `public_default`), unless
  the player ticked otherwise in its row. The confirm window is only for **Put on my page** now (with the switch off, or
  for one that didn't go).
- **The rows:** on your page ✓ · Open; **keep for good** (`?kept=`, "N of 12 kept for good", the relay's 409 said);
  **shown to anyone**; **feature** (`?featured=1`, which keeps it); **take down** (`DELETE`, after asking; never up
  again by itself); ✕ (the local file and its moment).
- **Its scene moment**, captured from the client's own scene round the kill and its drop (or the player's fall), sent in
  the same upload (`moment`, `moment_tick`): `EncounterScene` (a rolling buffer of the last 9 ticks' moving models, a
  frame every 2 client cycles; 0.47 ms a frame on the client's thread in the local world, the buffer 11-16 MB; the
  still scene read once at the kill) and `EncounterMoment` (the dots and the file, ghillie's MOMENT-FORMAT.md version 2,
  byte for byte moment.js's writer on its golden vectors); the ground items' names and GE/HA prices as RuneLite's Ground
  Items plugin shows them (ItemManager). The recording now waits up to 12 ticks after a kill for the drop to land, and 6
  past one that does (it ended 5 after the kill before).
- Checked: `gradle test` (150, the cut's below), hub_check.py and start_check.py both ways, and the local world with a
  local relay (plugin/README.md, "How round two was checked").

**The Hub risk, plainly.** The reviewer agreed to sends on the player's press ("Update my page") and an hourly sync
(11/16/2026, "The reviewer's second answer"); not to a send after every fight that nobody presses. This is new ground:
a recording (other players' names taken out, their chat too, but their positions and gear kept unnamed when "Players
around you" is on) leaves the player's computer with no press. The update's description says so plainly, the setting's
own words say so, and the manifest's `warning=` above names it. If the reviewer objects, the fallback is ready in one
line: `encounterSend()`'s default to false (then nothing goes without a press, as in round one), and the description
updated to match. That call is Jace's before it's sent: on by default (his ask) or off by default (the safer review).

**Jace's in-game steps for round two** (with the relay's fights on, `FIGHTS` "on", in the same run as the steps below):

1. In the Encounters tab: **Record my encounters** and **Put each on my page** both ticked; "N of 12 kept for good on
   your page" under Recent encounters once the tab's read the page.
2. Kill a boss you do (one with a drop on the floor, ideally): the recording ends a few seconds after the drop lands; a
   few seconds later its row says **on your page ✓** without anything pressed. **Open**: its page plays; your page's
   stage (or the encounter's tile) shows its moment turning, the drop on the ground with its name and price as Ground
   Items shows it in the game, a beam over a drop worth over 1M.
3. **keep for good** on it: "1 of 12 kept". **feature** on another: it says ✼ featured, and is kept too. **take down**
   on a third: it asks; then the row says "taken down from your page", and your page no longer lists it.
4. Die to a boss once (if you're doing one anyway): it goes up too, its moment your fall.
5. Untick **Put each on my page**, kill again: the row stays with **Put on my page**; press it: the window says what's
   taken out, then it goes up.
6. Close RuneLite right after a kill (before its row says on your page): next start, it goes up by itself within a
   minute or so, once only (your page lists it once). Not tried in the local world: if it doesn't, Put on my page
   puts it up, and say so.

### Encounters, round three: a raid whole, its chest as the moment, crash leftovers and Record now sent (11/21/2026)

Jace (11/21/2026): "Put each encounter on my page" stays on by default; recordings a crash left are sent by themselves at
the next start; "Record now" recordings go up by themselves too ("in case someone wants to record something that isn't
a fight, like them and their friends emoting together"); "it should be the whole raid when it's a raid and if it's just
an individual boss outside of a raid it will be a recording of the fight with that individual boss"; "A raid should
record from entering through clicking the door to leave the treasure/chest room at the end of every raid"; "no reward
chests in this game put your loot on the ground unless your inventory is full"; and bosses whose corpse you loot
(Araxxor's, the Maggot King's). ghillie's claude/outstation.md, "Encounters, round three", has the design; plugin/
README.md, "Encounters", all of it.

- **A raid is one recording, from entering to leaving its treasure room.** The raid's completion line, each room's
  "Challenge complete", a boss's count line inside it, a death and the loot all go on; it ends the first ticks the player
  is out of the raid after it's completed or looted (the exit's click and the map leaving: Tombs of Amascut's treasure
  room, Osmumten's Burial Chamber, counts as the raid; its lobby, Jaltevas Pyramid, doesn't, and starts nothing), or 30
  minutes after the completion line, or at 3 hours in all; a client closed, a log out or a hop end it as before.
- **Its parts go up as one.** Past 5,000 ticks a recording is still split into parts on the computer (as Encounter
  Recorder's files are); the parts aren't sent one by one: the last part's row stands for all of them, and what goes up
  is the parts joined, the ticks running on (`EncounterFiles.eachJoined`).
- **The loot, read from the game itself** (not the Loot Tracker plugin): a `loot` line in the recording when a reward
  window opens with its reward inventory (the windows RuneLite's Loot Tracker reads: Chambers of Xeric, Theatre of Blood,
  Tombs of Amascut, the Fractured Archive, Barrows, the Lunar Chest, the Fortis Colosseum), Doom of Mokhaiotl's pile
  claimed, or a corpse looted (an NPC's Loot or Harvest after a kill: what the inventory gained). Its moment is the
  chest's opening, with the window as the player saw it laid over (a picture of that window, cropped from the frame the
  client drew, carried in the moment file beside the recording; it leaves only with the encounter). A boss outside a
  raid with a corpse waits for it to be looted or to go (5 minutes at most).
- **Crash leftovers and Record now:** a recording the client closed or crashed in the middle of is packed at the next
  start and goes up by itself (with the switch on, only on the character it was recorded on, never twice). Record now's
  go up by themselves too.
- **The Hub risk, the same as round two's, a little wider:** a Record now recording isn't a fight; it goes with no press,
  as every other. The words of the setting and the update's description say "each recording".

**Jace's in-game steps for round three:**

1. **A raid, whole:** with Record my encounters and Put each on my page ticked, do a raid you do (any: Chambers of
   Xeric, Theatre of Blood, Tombs of Amascut, the Fractured Archive). The lime line starts as you enter and keeps going
   through every room, the completion line and the chest. Open the chest and take the loot; the line keeps going. Click
   the exit (the door, the stairs, the portal) and leave: the line stops within a couple of seconds and the row says
   **completed**, its length the whole raid. A few seconds later it's **on your page ✓**: its page plays the whole raid
   from the entrance to the treasure room's exit, and its moment (on your page's stage or its tile) is the chest
   opening: you at the chest, its animation, then the reward window as you saw it, its title and items.
2. **A raid past 50 minutes** (if you do one that long): the folder holds two or more parts; the tab lists one row; the
   page plays it whole, no jump at the 50-minute mark.
3. **A boss with a chest:** the Moons of Peril (Record now there, or list their ids in the settings), Barrows (Record
   now), or the Colosseum: open the Lunar Chest, the Barrows chest or the Colosseum's: the moment is the chest opening
   with its window. A boss with a corpse to loot: kill Araxxor (or the Maggot King) and loot the corpse: the recording
   ends a few seconds after the loot, and the moment shows the items coming into your inventory.
4. **A boss outside a raid** (Vorkath, Zulrah…): one recording a kill, as before.
5. **Record now:** press it, emote with a friend for half a minute, press Stop: it goes up by itself.
6. **A crash:** start Record now, then end RuneLite's process (Task Manager, End task) mid-recording. Start RuneLite
   again and log in on the same character: within a minute or two the row is **on your page ✓**, once (your page lists
   it once). Log in on another character first: it waits for the right one.

### Encounters, round four: a delve whole, a corpse's forfeit, a pet as the moment (11/21/2026)

Jace (11/21/2026): Doom of Mokhaiotl's "loot 'chest' is Doom's burrow that you can either claim your loot from or
descend deeper. Every doom/delve level as the player descends should be one continuous encounter recording, and it
should only end when the player dies or after they claim their loot and press the leave button on the burrow";
Araxxor's corpse has "Harvest" and "a right click option to destroy the corpse of Araxxor for a higher chance at the pet
at the cost of receiving no loot"; the Royal Titans "you also pull the loot from their corpse"; and "Pet rolls from
bosses/raid loot chests are counted as moments, correct?" (they weren't). ghillie's claude/outstation.md, "Encounters,
round four", has the design; plugin/README.md, "Encounters", all of it.

- **A delve is one recording.** No area of RuneLite's names Doom's arena, so Doom of Mokhaiotl appearing (any of its
  three forms) starts it (with Record my encounters on). Each level's kill count and "Delve level" lines, the burrow's
  window and Descend all go on. It ends with the player's death, or after the claim (the game's own claim script, as
  RuneLite's Loot Tracker reads it) and the burrow's **Leave** a few seconds on; a claim and a teleport out ends it too;
  unclaimed and out of the delve a while (a teleport), "left". Its moment is the claim's window as the player saw it,
  or the fall.
- **A corpse's forfeit:** Araxxor's **Destroy** and the Royal Titans' **Sacrifice** (the wiki's names; the Titans'
  third choice, **Take pages**, counts as their loot). The recording waits for the choice as it waits for a Harvest or
  Loot (the Titans' corpses by either titan's name: the count line names neither); a forfeit writes a `loot` line with
  no items (`"choice": "destroy"` or `"sacrifice"`; a looted one says `"harvest"`, `"loot"`…), and its moment is the
  kill and the destroying, or the destroying alone when the kill's too long before.
- **A pet joins its moment.** The game's line (as Dink reads it: "You have a funny feeling like you're being
  followed.", "You feel something weird sneaking into your backpack.", "...would have been followed..."), then the pet
  beside you or in your backpack, joins the stretch it came with: "the kill, its drop and a pet", "the chest and a
  pet", "the loot and a pet". A pet far from any of those is the moment by itself. The chat line is shown as the game
  drew it (a picture of the chatbox's row, the same way the reward window's is taken), the pet named in the moment.
- **Nothing new is sent or asked:** the pet's line is the game's own message to the player, already in the recording.

**Jace's in-game steps for round four:**

1. **A delve:** go down to Doom of Mokhaiotl with Record my encounters ticked: the lime line starts as Doom appears.
   Finish a level, open the burrow, **Descend**; finish another (the line keeps going through both). Open the burrow,
   **Claim and Leave**, take the loot, press **Leave**: the line stops a few seconds later, the row says **claimed**,
   its length the whole delve. Its moment is the burrow's window as you saw it at the claim. (Or die in a delve: the row
   says **died** and the moment is the fall.)
2. **Araxxor:** kill it and **Harvest**: the moment shows the loot coming into your inventory. Next kill, right-click
   **Destroy** (and answer the prompt): the recording ends a few seconds after the corpse goes; its moment is the kill
   and the destroying (or the destroying alone after a long wait), no window.
3. **The Royal Titans:** kill both, then **Loot** one: the recording waits for it, and the moment is the loot coming
   into your inventory. (**Sacrifice** next time: as Araxxor's Destroy.)
4. **A pet** (whenever one comes): its moment holds the pet's line as your chatbox showed it, low on the left, and the
   pet beside you (or the backpack line). Nothing to do but hope.

### Encounters, round five: each reward window as you saw it, the click crosses (11/21/2026)

Jace (11/21/2026): "I'd like if the replay showed loot too for each level, just as the replay shows the loot for the
chest at the end of the raid"; then "I just want to make sure the loot and inventory windows look clean and true to the
game in the replay viewers, and to the moments as well"; and click markers, "pink or lime, just as we use for our left
click animations on the website". plugin/README.md, "Encounters", has it all.

- **Each reward window, as your client drew it, goes in the recording** (a `window` line, and `window_close` when it
  goes): a raid's or a boss's reward chest every time it opens, Doom's burrow at every level (before you descend or
  claim) and the claim, and the pet's chat line. The picture is the window's box only, cut from the frame RuneLite
  already hands plugins (the same way round three's moment picture was taken; the burrow's window, which lies over the
  whole game view, cut to what it draws), written as a PNG as small as it goes without changing a pixel: 7 to 20 KB a
  window in the local world. The replay lays it over its view at that tick, pixel for pixel, where the game had it.
- **No picture of the inventory:** a corpse's loot comes into the replay's own inventory panel (the recording's
  inventory lines, as before), and the moment shows the items gained as a plain panel in the game's colours.
- **The click crosses:** a `mark` line for each click the game answers with its cross: "walk" (the yellow cross) with
  the tile walked to, or "act" (the red cross: an NPC, a player, an object or an item on the ground) with whom (by its
  index in the scene, never a name) or the object's tile. Never where the cursor was, never the option's words.
- **Nothing new is sent or asked:** these are part of the recording, stripped as before (a window's title is searched
  for other players' names; its picture is the player's own window).

**Jace's in-game steps for round five:**

1. **A delve, level by level:** with Record my encounters ticked, finish a level and open the burrow; **Descend**;
   again; then **Claim and Leave** and **Leave**. On your page (or the file opened in the replay), play it: at each
   level the burrow's window appears over the view at the tick it opened, exactly as you saw it (same size against the
   scene, sharp), and goes when you closed it; the claim's too. The timeline has a small lime chest where each opened;
   hovering it says the window's title.
2. **A raid's chest** (or Barrows, the Lunar Chest, the Colosseum): open it, close it, open it again: the replay shows
   the window each time it was open, centred, as the game shows it.
3. **Clicks:** in the replay, a lime cross where you walked and a pink one on what you attacked or clicked, at the
   moment you clicked (the drawing panel's **click crosses**, or x, turns them off).
4. **The panels:** the readout's **worn and carried** shows your inventory, worn equipment and prayers at every tick,
   with the game's tab stones to switch between them: eat, sip, switch gear and pray in the fight and watch each change
   at its tick. (i puts all three over the view as well.)
5. **A pet** (whenever one comes): its chat line appears flush in the replay's bottom-left corner at the tick it came.

### Encounters, round six: the Gauntlet and the Fortis Colosseum, each run one recording (11/21/2026)

Jace (11/21/2026): the Gauntlet (and the Corrupted Gauntlet) and the Fortis Colosseum should "start recording upon
entering the instance. each one has an object to click on to enter." All three are minigames to RuneLite's Discord
plugin, not bosses, so nothing started them before. plugin/README.md, "Encounters", round six, has it all.

- **The start:** the click on the way in (the Gauntlet's **Enter** or **Enter-corrupted** in its lobby; the arena's
  **Entrance** in the Colosseum's lobby), then the map of its instance: the recording starts as it loads, named "The
  Gauntlet", "The Corrupted Gauntlet" or "Fortis Colosseum". The places are RuneLite's own (Discord's areas, the
  Screenshot plugin's Gauntlet regions) and the objects the game's (revision 242's cache), none guessed.
- **A Gauntlet** is the prep and the Hunllef: its completion line ends nothing; sent back to the lobby, it waits for
  the **Reward Chest**. That chest has no window (the loot goes straight into your inventory), so its loot is read as a
  corpse's is, from what the inventory gained, and the moment is "the loot", the items shown on a plain panel titled
  "The Gauntlet". It ends a few seconds after the chest, or with your death, or on leaving the Gauntlet before the
  kill.
- **A Colosseum** is every wave: no wave's line or Sol Heredit's count line ends it. The rewards chest's window (already
  read, as the Loot Tracker reads it) is its moment, and it ends a few seconds after you shut that window (the claim),
  or once you're out of the arena; or with your death, or on leaving with nothing claimed.
- **Kill counts with the game's colour codes:** the kill count lines are read with or without the colour code the game
  has put in front of the number since its 08/26/2026 update (RuneLite's own chat commands and Dink read it the same).
- **Nothing new is sent or asked.**

**Jace's in-game steps for round six:**

1. **The Gauntlet:** with Record my encounters ticked, click **Enter** on the Gauntlet in its lobby: the lime line in
   the Encounters tab starts as the Gauntlet loads, named **The Gauntlet**. Prep, kill the Hunllef: the line keeps
   going through the kill and back to the lobby. **Open** the Reward Chest: the line stops a few seconds later and the
   row says **looted**, its length the whole run. Its moment (on your page) is you at the chest, your loot on a panel
   titled "The Gauntlet".
2. **The Corrupted Gauntlet** (if you do it): the same with **Enter-corrupted**, named **The Corrupted Gauntlet**.
3. **Leaving or dying:** another run, leave by the starting room's Teleport Platform (or die): the row says **left**
   (or **died**) a few seconds later, and nothing starts again till you're out of the Gauntlet.
4. **The Fortis Colosseum:** click the **Entrance** in the Colosseum's lobby: the line starts as the arena loads,
   named **Fortis Colosseum**. Clear a wave or more (each "Wave completed" line goes by), then search the rewards chest
   and claim: the line keeps going until you leave the arena, then the row says **claimed**, its moment the chest's
   window as you saw it. (Die in a wave instead: **died**.)

### Encounters, round eight: Jace's own raids (11/21/2026)

Jace's first Fractured Archive raids as recorded by update 6's plugin (five on his page): three went up without a
moment, the two that had one were "half of my poh and half of the moia arena after we died", a raid came up as two
encounters, and "sometimes ... the plugin doesn't start automatically recording when I enter the fractured archive".
Read from his own files (ghillie's claude/outstation.md, "Encounters, round eight", has each one tick by tick):

- **The Archive's first room and its Nexus are the raid.** RuneLite's Discord plugin names the Fractured Archive by eight
  regions (`DiscordGameEventType.RAIDS_THE_FRACTURED_ARCHIVE`, 1.13.7); the Archive Guardian's room and the Nexus
  between the bosses are a ninth, 16471, as an instance's template. So no raid started recording until the second boss
  (Ezekiel), and a wait at the Nexus past 50 ticks (the Quartermaster's shop) ended it as "left", the rest of the raid a
  recording of its own (his E12y… and zlOv…, 40 s apart). `Encounters.RAID_ROOMS` counts 16471 as the raid, only in an
  instance. A death never split one: a dead raider spectates in the room's instance until it ends, then goes on with
  the party ("You will respawn when your party completes or fails the challenge").
- **A raid's own state counts too**, wherever the game puts a dead raider: Chambers of Xeric's `RAIDS_CLIENT_INDUNGEON`
  (the Raids plugin's IN_RAID) and Theatre of Blood's `TOB_CLIENT_PARTYSTATUS` 2 or 3 (inside, or dead and spectating).
- **The raid over for the player** ("You failed to survive the Fractured Archive.", "You were unable to rejoin your
  party."): the first ticks out of it are the end, "failed", not 50 ticks later in the lobby or the house.
- **A moment never crosses a map load.** Each frame of the scene knows its map; the stretch is cut where it changed,
  keeping its anchor's side; the still scene is the one taken on that map at the anchor's own event: a fall's at the
  death, in the arena. It used to be read as the recording ended, by then in his house: two moments mixed the house's
  ground with the arena's last seconds, and three failed outright (nothing of the house near where he fell).
- **The buffer lets go**: shapes no kept frame has (every projectile and graphic, a player at each gear switch) went on
  counting toward its 96 MB; in his 7,882-tick recording of three raids it filled, no frame was taken after, and the
  chest's opening went without a moment. Let go each tick now.
- **Every encounter goes up with a moment, or says why**: the upload's meta carries `moment_missing` (the relay keeps
  only the fields it knows, so it's in the upload, the plugin's log and its index until the relay keeps it).

Checks: gradle test (EncounterMapsTest: the cut, the still scene's map, his raid fall, a fall just before the map goes,
three falls and a chest, a reason with none, the buffer, the Archive's rooms, the lines that end a raid), hub_check,
start_check both ways, `hub_cut.py --check`.

### Edit my page: arranging the page and its theme song (11/21/2026)

Jace (11/21/2026), of editing his own page and adding a song: "Build it, they should be a part of update 6". The part
`arrange` (hub_cut.py, in SUBMITTED): **Edit my page** beside Open my page in the side tab. On the press only, the plugin
makes a token, hands it to the relay with the page's key (`POST /edit/<channel>?key=`, docs/protocol.md "Editing the
page from the page") and opens `outside.wiki/<name>#edit=<token>` (with `?code=` when it's friends only). On the page,
for 2 hours, "arrange this file" (move and hide its sections and the top of the file's panels) and the theme's link box
(a SoundCloud, YouTube or Spotify link, shown in that platform's own player when a visitor presses it); every visitor
then sees the page as arranged and the song, with no controls. The token can do nothing else. Nothing new is sent from
the game: a token and a press. `Attach.editUrl`/`editPage` (AttachTest), the hook beside `tendChat`.

Order: the relay's routes (`/edit/`, the keep's `layout`) go live first; then outside.wiki's sync, which carries
arrange.js, hero.js and theme.js for the first time; then this update.

### Jace's test in the game, for the whole update

`py run.py client` in a checkout of jhmcubed/outside-plugin at the exact commit the fork will name (run.py starts RuneLite with `-ea`), logged in. The client's log mustn't show anything from `wiki.outside` failing at any step.

1. **The key:** paste your station key in the plugin's settings and press Update my page (it should update). Then in the Page tab, Copy my station key: the key on the clipboard is that one.
2. **Goals, carried over:** with the goal you set before (one), open the Page tab: it's the one row under Your goals, its dot filled. Your page still shows it turning.
3. **Goals, the list:** press Add a goal item and pick an item, twice more: three rows, the first still shown. Pick another row's dot: a couple of seconds later your page turns that one, the others under it with their kills and bars. ✕ the shown one: the first left is shown. Pick No 3D model: a couple of seconds later nothing turns on your page, every goal listed with its picture; pick a goal's dot and it turns again. `::outsidegoal clear`: every row gone, the page says no goals. Add ten: the button greys out, its tip says ten is the most.
4. **The delves:** if you're doing the Doom of Mokhaiotl anyway, die in a delve after finishing a level or two, then read the board beside the boss and press Update my page: your page leaves those levels out of the goal's odds where they drop (Show what's sent shows `delve_lost`). Not needed if you're not there: its counting is tested (`DelvesTest`).
5. **Encounters, by hand:** the side tab's third tab, **Encounters** (its name whole, not cut short). Record my encounters is ticked. Press **Record now**: the button says Stop and a lime line under it counts the time and size. Do anything for half a minute (fight a goblin, cast at a dummy), press **Stop**: a row comes under Recent encounters (who, when, how long), and the folder (Copy the folder's place, pasted into Explorer) holds a `.jsonl.gz` of that name. Open it in Encounter Replay (encounter-replay.pages.dev) too: it should play there as his own do.
6. **Encounters, by themselves:** go to a boss you do (any of Discord's bosses: Vorkath, Zulrah, the GWD rooms, the Giant Mole…): a recording starts as you arrive (the lime line, without pressing anything). Kill it: the game's kill count line ends it a few seconds later and the row says **killed**; the next kill there starts the next with your first hit. Leave by teleport: one running ends ("left"). Don't press Record now in the Wilderness: it should say no; walking into the Wilderness while one runs ends it at once.
7. **Putting one on your page:** only once Jace has switched the relay's fights on (`FIGHTS` "on"); until then the tab says "Outside.wiki isn't taking encounters on pages yet" and Put on my page is greyed out. Then: **Put on my page** on a row: a window says what's taken out (exact xp, and other players' names if any were near) and asks; OK: the row says "on your page" with **Open**, which opens outside.wiki/<you>/fight/<id> and plays it. Untick **shown to anyone** (or tick it): your page's own list follows. ✕ a row: it asks, then the file's gone from your computer (one on your page stays there).
8. **The client closing mid-recording:** start one, close RuneLite's window: it closes as before (a second or so), and the next start lists that recording, ended "client closed".
9. **Edit my page** (once the relay and outside.wiki have it): in the side tab, press **Edit my page** beside Open my page: the line under it says it's opened, and your browser opens your page with **arrange this file** under your name and a box for your theme song in the right margin (or the ✼ tab at the bottom right in a narrow window). Move a section (drag its bar, or its arrows), hide one with its eye, pull a panel at the top wider, press **done**: it says it's kept. Paste a YouTube, SoundCloud or Spotify link in the theme's box and press **set**. Then open your page in a private window (or another browser): it's arranged as you left it, the hidden section gone, your song in the margin to press, and no arrange button or link box. Press **as it was** in the first window: everything goes back for everyone. Twelve hours later the link stops working (the page says so); Edit my page again makes a new one. (Since round nine, 11/21/2026, Edit my page opens the page with the register's owner link, the same as the register's own button, now also called Edit my page: the encounters' moments and the register work from it too.)
10. **A raid, whole** (round eight): enter the Fractured Archive: a recording starts in the Archive Guardian's room (the lime line, nothing pressed). Stay at the Nexus between bosses as long as you like (the shop): it goes on. If you die, it goes on through the spectating and the next rooms. It ends as you leave the chest room ("completed"), or a few seconds after the raid's failed ("failed"). On your page it's one encounter, its moment the chest's opening, else your last fall in the arena you fell in, with no other place in it.

### Update 6: what to try in the game (11/22/2026)

The cut: jhmcubed/outside-plugin at e6da89f (`hub_cut.py --check` matches; it was 9e24eaf before goal reordering and the delve levels lost came out, 11/22/2026). Its `gradle test`, `hub_check.py` and
`start_check.py` passed on 11/22/2026 ("Plugin OutsidePlugin is now running", nothing of its own failing after). Jace's
part, `py run.py client` in outside-plugin at that commit (RuneLite with `-ea`), logged in, `.runelite\logs\client.log`
open: nothing naming `wiki.outside` failing, and no "must be called on client thread", all the way through.

1. **It starts:** the side tab opens with its tabs; nothing in the log at login.
2. **The key kept:** the Page tab shows "Your page's key"; Copy my station key copies it.
3. **More than one goal:** Add a goal item, pick one; add a second; change which is shown; ✕ one. outside.wiki/sburb
   follows within seconds (the shown one turning, the rest listed). "No 3D model": the page shows its picture only.
4. **Encounters:** Record my encounters on; kill a boss: it ends on the kill, is in the Encounters tab, goes up by
   itself, and is on the page with its moment within about a minute. In its row: keep for good, feature, take down
   (the page follows). Record now and Stop by hand. If it comes easily, a raid or a delve: one encounter start to finish,
   a death not splitting it. A chest's loot window in the moment as drawn.
5. **Edit my page:** opens the page as its owner: arrange, the theme, even the columns, right-click on encounters.
6. **The delve levels a death lost** (only if he's doing Doom): a death mid-delve, the board's count beside the boss
   the levels done.

Then: the warning with recorded fights (above) on the fork's file, the commit= there, and once merged the page's
`ON_THE_HUB.goals` on and the revision log's entry for the plugin.

## Update 8: trips (built 11/23/2026, not sent; after update 7)

Jace: "let's swap update 8 for update 9. i want trips to come after update 7 ships", then "start building trips for
update 8". Built from the plan under **Trips** in "Update 7 (planned, 11/22/2026)" below.

- **The plugin** (`Encounters`): in a boss's area in an instance (`tripPlace`: Presence's kind boss, not a raid, a
  minigame, a delve or a run), a recording that ends with a kill (killed or looted) while they stay there is followed at
  once by the next (`stopAuto`, `tripGoesOn`), not waiting for the next fight; the respawn's wait and the re-gearing are
  in it. Each carries `"trip"` (12 characters of base64url, random: `newTripId`) and `"tripNo"` in its header; the one
  the trip ends with gets `"tripEnd"` (its reason) in its end line. A part past the length limit keeps its trip and
  number. The walk out after two kills or more (`tripTail`) goes up as the trip's end; after one kill it stays on the
  computer and the kill shows alone. Nothing new is sent from the game: the header's fields ride in the recording that
  already goes up, and its meta (`EncounterStrip.meta`) carries `"trip": {"id", "no", "end"?}`.
- **The relay** (`relay/src/fights.ts`, `readTrip`): keeps a fight's trip as its upload says it (the id 8 to 22 of
  `A-Za-z0-9_-`, no 1 to 999, end 40 characters at most; anything else refuses the upload). **It has to be deployed
  before update 8 goes to the Hub**, or the trips are dropped from the meta (the files keep them).
- **The page** (ghillie's `fights.js`, `fight.js`, `replay/replay.js`, `profile.css`): a trip of two or more is one
  tile in the place of its newest (its kills, the whole time, how it ended: left, fell, or "going on" within 3 hours;
  the trip and each kill alone by number), opened in place or on its own page as the whole trip
  (`/<name>/fight/<id>?trip=1`: each recording fetched and joined as the plugin joins a long recording's parts, a pink
  mark on the replay's line at each kill). A kill kept for good or featured stays its own tile too. Not yet on
  outside.wiki (it syncs with the ship).
- **The listing** (11/23/2026, Jace asking how more people would find the plugin): the description names what only
  Outside does ("Your own page at Outside.wiki: your character in 3D, your goal item and how dry you are, your boss
  fights recorded and replayed, your collection log, gear, stats, quests and diaries"), and the tags the words players
  search (3d, model, goal, dry, drop, pet, collection, clog, pb, trips, bank...; `discord` taken out: it read as a
  Discord bot). In `runelite-plugin.properties`, so it goes with update 8. The Hub's page said 8 active installs that
  day (runelite.net/plugin-hub/show/outside): the baseline to read it against.
- **Not in it** (from the plan): what dropped on the tile (the list carries no loot yet), an open area's kills in a row.

Checked: gradle test (whole 207, the cut 151: `TripsTest`), hub_check both, hub_cut --check, start_check on the cut
(outside-plugin 93fec4d, on update 7's 8f812ad); the relay's tests and tsc. In the local world (the cut with `-ea`):
every stage passes, the expectations changed for trips (`stagecheck.py`: Vorkath's two kills one trip of three
recordings, the second begun as the first ended, the walk out the trip's end and sent; Vorkath's and the instanced
Mole's single kill followed by a walk out kept on the computer; the open lair, the raid, the Inferno and the Colosseum
as before). The real trip's three recordings played through the page against a mocked relay: 88 ticks, both kills as
chapters, the marks at 34, 73 and 88.

**Jace's test** (`Try the plugin.bat 25fe3ac`, once the relay's deployed; 93fec4d with the listing's new description and tags on top, start_check passed on it):
1. An instanced boss with respawns (Vorkath, Zulrah, the Mole's instance, a DT2 boss): two kills or more, then leave.
   The side tab lists each kill and the walk out as their own recordings; each goes up as it ends.
2. Your page: one tile for the trip, saying the kills, the whole time and "left"; "the trip ->" plays them end to end
   with a pink mark at each kill; each number opens that kill alone.
3. A single kill and leave: the kill alone, as before (no trip tile, the walk out not sent).
4. A death mid-trip: the tile says "fell".
5. The client log: nothing from wiki.outside failing.

## Update 9 (planned 11/22/2026 as update 8; moved to 9 on 11/23/2026): alts under one page

**The order since 11/23/2026** (Jace: "let's swap update 8 for update 9. i want trips to come after update 7 ships"): update 8 is **trips** (planned in "Update 7 (planned, 11/22/2026)" below, under **Trips**), update 9 is these alts.

Jace: "plan the alts linking for update 8" (after "people like might being able to have one profile that ties all of
their characters together as a package"; done without Outside.wiki accounts). Trips moved to update 9 that day, and back to 8 on 11/23/2026 (above).

- **Each character keeps its own key and page.** Updates never collide; nothing about how a character sends changes.
- **Linking by a one-time code, never the main's key.** The main's key is the whole page's control, so it never leaves
  the main's client (pasting it into an alt's, or a key derived from it, would put that control in a second place).
  - The main's side tab, Page tab: **Add a character** asks the relay for a code (6 to 8 characters, 10 minutes, one use,
    made with the main's key) and shows it with how long it lasts.
  - The alt's side tab: **Link to my main**, a box for the code. The alt's plugin sends it with the alt's own key; the
    relay checks both and records the link. Both side tabs say it's done, naming the other character.
  - At most a handful of alts a main (say 10); an alt has one main; a main can't be someone's alt.
- **Unlinking** from either side tab (Unlink this character / each alt's ✕ on the main), at once, no code.
- **Shown or not:** each link is public (both pages show it) or private (only the owner, opened from Edit my page, sees
  it), chosen on the main, private to begin with, so nobody's alts are shown by surprise.
- **The relay:** `POST /link/<channel>?key=` (the main asks for a code), `POST /link/<alt>?key=&code=` (the alt claims it),
  `DELETE /link/<channel>/<other>?key=` (either side), the links in the channel's record and on GET /game as
  `alts: [...]` / `main: "<name>"` when public. Codes kept hashed, 10 minutes, one use; tried codes limited.
- **The page:** outside.wiki/<main> gains a character switcher under the name (each character's own page, its own
  readings, as tabs or a small row of their names and account-type badges); an alt's page says "an alt of <main>" with a
  link, when public. Search by an alt's name lands on the alt's page as now.
- **For the Hub:** no new game data; two buttons and a code box; the README says what linking shares (that the
  characters are one person's, only when public). The warning stays as the reviewer wrote it.
- **Checks:** relay tests for every route (codes expire, are single-use, can't be guessed fast, can't link a main as an
  alt), the local world with two characters on one machine (two clients), the page at both widths.
- **Jace's calls (11/22/2026):** private to begin with; a switcher row under the name. Whether an alt's page lists its
  siblings wasn't answered: Claude's default, only while the link is public, the same row as the main's.

## Update 7 (planned, 11/22/2026)

**The order (11/22/2026, Jace: "yes, go with that order"):** update 7 is the collection log alone (small, one new kind
of data behind its own switch, readable in one sitting), with the "moment couldn't be made" warning made a one-line
note; update 8 is trips (the plugin's part: no gaps between an instance's kills, a trip id); meanwhile, on outside.wiki
alone with no Hub update, kill times and personal bests from the game's own lines the recordings already keep. Then the
year on areas only (on the log's diary), then a Discord post per PB. Update 7 goes to the Hub only once #21147 merges.

Added to update 7 the same day (Jace: "yes, add it to update 7"): the personal bests RuneLite already keeps (ChatCommands'
RSProfile group "personalbest", seconds by boss, written from the game's PB lines and the Adventure Log's boss times;
read as Dink does), sent with the snapshot, so a page shows its best times without fresh kills, as "the game's best,
not on file" until a recording matches it.

Jace: "plan it for update 7". The collection log read by Outside itself, for players who don't use RuneProfile
(SaluteCFlagg's page: "RuneProfile has no record of SaluteCFlagg ... the collection log's slots, drop rates and which
pets are had stay dark"). The game gives a plugin the log only while it's open (as RuneProfile, WikiSync and
TempleOSRS each find), so: a switch, Send my collection log, off to begin with; opening the log sends the slots had and
their counts (checked first in the local world: whether one open is enough, without every page); new slots from the
game's "New item added to your collection log" line; the side tab saying "open your collection log once" when the
count the plugin already sends has passed what was synced. The relay keeps it; the page uses it where RuneProfile has
nothing, or whichever is newer.

**Trips** (11/22/2026, Jace: "People consider instances with bosses that respawn a 'trip' with multiple kills in
succession over a period of 1-3 hours"; "yes plan trips for update 7"). Each kill stays its own recording (findable,
its own time and PB, the length limit's 50-minute parts never cutting a fight, a crash losing one kill), and the kills
of one instance visit are joined into a trip:
- no gaps: in an instance, the next recording starts as the last one ends (the respawn wait and re-gearing kept), so
  played end to end the trip is whole;
- a trip id on each, the same for every kill of one instance visit, ended by leaving it or dying;
- on the page, one card a trip (the boss, kills, the whole time, what dropped, how it ended), played straight through
  with a mark at each kill to jump to; a kill still shared alone;
- each kill uploaded as it ends, so a trip shows while it's going and a crash keeps what came before.
Instances first; an open area's kills in a row maybe later (others come and go, and the 90 s quiet ends one).

Moved into update 6 the same day (Jace: "include goal re-ordering in this update too", and of the levels a death
lost, "why wait for update 7 when you can just drop it now"): the goals reordered in the side tab, each row dragged by
its handle (`Goal.Kept.move`, a lime line where it'll go; the page lists the rest in that order under the one shown);
and `Delves.java` and `delve_lost` taken out (the page counts every level: each "already rolled for the loot", a unique
claimed before going deeper). The cut's `gradle test`, `hub_check.py` and `start_check.py` passed again after both.

### Update 7: built (11/22/2026; round two the same day, not sent)

The collection log, part `clog` (`Clog.java`, in `PARTS` and `SUBMITTED`): "Send my collection log", off to begin
with. What the reference plugins do (RuneProfile's plugin at b3615fc, WikiSync at 1ff4ea0, TempleOSRS at the Hub's
b8d1f89): the game hands a plugin the open log's slots by its script 4100 (args: item, how many), only the page shown
until the log's search is pressed; all three press it themselves with `Client.menuAction` (RuneProfile and TempleOSRS
as the log opens, WikiSync from a button), which `hub_check.py` forbids, so Outside asks the player to press Search
once (the chat, once a session, as the log opens while the page is behind; the side tab's line under Update my page).
The log's pages from the cache (enum 2102, params 683, 689, 690, replacements 3721, as WikiSync and TempleOSRS); the
chat's "New item added to your collection log" matched by name to the log's own items. Held for the next update in
both versions (never from anywhere dangerous). The relay's keep `clog` (docs/protocol.md, "Kept for the page"); the
page's `logGet` in ghillie's outside.js. With it, the encounter moment's "nothing drawn near the middle" is one info
line (`EncounterMoment.NoMoment`), not a warning with its stack.

Checked: gradle test (whole 194, cut 138), hub_check (both), hub_cut --check, start_check (both), the relay's tests and
tsc. In the local world (ghillie's tools/local-world, stage `clog`, the cut with `-ea`): the server's 4100 calls read
and held, the real cache's layout (5 tabs, 125 pages, 1,944 page items, about 63 KB), a short read sent as additions,
the chat's Twisted bow matched to 20997; the whole plugin against a local relay: Update my page sent the merged log,
the relay kept it, the switch off took it down; the page drew it (a player with no RuneProfile). Jace: deploy the relay,
then try it in the game (the list in the session's report).

Round two (11/22/2026, Jace's answers): RuneLite's wiki forbids `Client.menuAction` "Except for the 1 exception", and its
"Sensitive APIs" names it: "There is currently only 1 acceptable use case for this function - fetching collection log
data: client.menuAction(-1, InterfaceID.Collection.SEARCH_TOGGLE, MenuAction.CC_OP, 1, -1, "Search", null)".
`hub_check.py` now fails every other call and lists that one as sensitive (and takes a folder, so the cut is checked
itself: before, it always checked this folder whatever it was given). So, as WikiSync's and TempleOSRS's: an **Outside**
button in the log's header row (`ClogButton`, WikiSync's drawing, left of the other plugins' buttons), pressing it that
call and the log's start (2240), only on the player's press. And the log is **sent as it's read** (Jace: people would
think it broken), not held for the next update: a whole read at once (a minute apart at most), the chat's slots three
minutes apart at most, merged (`Clog.Outbox`); held anywhere dangerous and sent on leaving, said in the chat. The chat says
"your collection log is on your page: N slots"; the side tab's line says what the page has and when.

And the personal bests (11/22/2026, Jace: "yes, add it to update 7"), part `pbs` (`Pbs.java`): "Send my personal bests",
on, the bests RuneLite's Chat Commands plugin keeps (`personalbest` on the RuneLite profile, read as Dink's
`KillCountService.getPb` reads them, none while that plugin's off) as the snapshot's `pbs` when they've changed; the
relay's `gameFields` keeps them; the page's best column shows them as the game's best. The Hub's `warning=` stays as the
reviewer wrote it ("covering you for current and future uses": "The first update, merged"): update 7 changes only `commit=`.

### Update 7 made ready (11/23/2026, on the memory fix)

Jace: "get update 7 ready". outside-plugin `claude/exciting-lovelace-q14wvd` at
cf6c3399536f0611c1c88bf423fb7a84bc5a4674: the memory fix's 8b6d50c (the Hub's 6bed558 under it) with update 7's cut
(charming-wozniak's 84227d0, 0399cde, 72cfb28, 4f82bc4) merged on, no conflict. What's in it: the collection log (the
Outside button in the log's header, sent as it's read, "Send my collection log" off to begin with), the personal bests
RuneLite keeps ("Send my personal bests", on: the page's best column has had a place for them, empty till now), a
moment's ground at the world view's heights (ur_neat's Chambers of Xeric: the ground floated off flat ground), the side
tab's "The aether and registers" (notes in any register carry the lime star) and the README's register line.
hub_cut --check matches outstation's branch; gradle test 146; hub_check passed (the one menuAction listed as
sensitive); start_check passed on cf6c339 itself. The relay that keeps `clog` and `pbs` is live (deployed 11/22/2026
19:14 UTC, a9f07d6). Goes to the fork once #21195 merges and Jace has tried it.

**Jace's test** (`Try the plugin.bat cf6c339`, `-ea`):
1. The side tab: Send my collection log and Send my personal bests (both on to begin with) are there; the Page tab says "The aether
   and registers".
2. Personal bests: RuneLite's Chat Commands plugin on; Update my page. On the page, the kills' best column shows his
   bests as the game's.
3. The collection log: Send my collection log on, open the log in the game: an Outside button left of the header's
   other plugins' buttons. Press it: the chat says "your collection log is on your page: N slots", N about his count;
   the page shows the log's slots.
4. Send my collection log off: the page's log goes back to RuneProfile's (or dark).
5. Any fight off flat ground (a raid room, a slope): its moment's ground sits under the models, not floating.
6. The client log: nothing from wiki.outside failing, no assertion.

Jace's test of cf6c339 (11/23/2026), so far: the collection log, Send my collection log on, Outside pressed: "your
collection log is on your page: 649 slots", and the page has it whole (outside.wiki/sburb's keep: 5 tabs, 125 pages,
649 slots, the game's count 648, its most 1,734). The line came twice, a minute apart: the press's read, then the page
the log drew as its search closed (its slots read again, sent a minute on, the same line said). Now said only for a
press's read (outstation 535a3cc's change, cut as outside-plugin b562a88: gradle test 146, hub_check, start_check). The
personal bests: none reached the relay (outside.wiki/sburb's /game has no `pbs`); asked whether Update my page was
pressed with Chat Commands on.
Then: the bests showed after Update my page. Jace: "is it possible to have send my collection log enabled by default
like the rest of the options so whenever someone opens their collection log they just see the button there": on to
begin with (outstation's OutsideConfig, the README, protocol.md), cut as outside-plugin 8f812ad (gradle test 146,
hub_check, start_check). It had been off only as the plan's choice for a new kind of data (the bank's and the model's
are off), not a rule of the Hub's; nothing goes until Outside's pressed but the chat's new slots, as the count already
sends their names. The build for the Hub: 8f812ad.

### A fix: the moment's memory (11/23/2026; on update 6 as the Hub has it, before update 7)

Jace: "figure out why only one of salutecflagg's doom encounters has a thumbnail", then "yes build the fix".
SaluteCFlagg's ten Doom of Mokhaiotl delves on the relay: two with moments, eight with `moment_missing` "it couldn't
be made: Java heap space" (twice "failed reallocation of scalar replaced objects"). The plugin's moment thread ran the
client's Java out of memory: the stretch's frames (up to 96 MB kept), every frame's corners copied again into floats
(twice as big), then every moving model's track made whole at 25 frames a second before `fit` made the file smaller; a
delve's dozens of models over 34 ticks came to hundreds of MB, and the game keeps its own share of the client's memory.
An `OutOfMemoryError` on the plugin's thread can starve the client's own thread too, so this is a fix, on its own.

What changed (`EncounterMoment.java`, `EncounterScene.java`, `EncounterShelf.java`'s log line):
- `Pose` reads a frame's corners where the frame keeps them (whole units in shorts) instead of a float copy: the same
  numbers, so the same dots.
- Before the tracks are made, `Builder.dotsAbout` works out each run's dots without drawing any chances, and the build
  holds its tracks to `trackBudget()`: a third of what the client's Java has free (its most less what's in use, garbage
  and all), 256 MB at most, 16 MB at least. Over it, the frames are taken further apart (`Spaced`, 2, 4, 8 times: fit's
  own "thin" taken first), then each model's dots fewer in proportion (`Builder.trackMost`: fit's "sparse"). A moment
  that fits in what's free is built as before.
- The frame buffer's cap is `bufferMost()`: 96 MB, or an eighth of the client's most if that's less.
- Memory read through `java.lang.management` (`hub_check.py` keeps `Runtime` for starting programs).
- The log's moment line says what the tracks would have held, what they held, how far apart the frames were and the
  dots a model.

Checked: `EncounterMemoryTest` (a scene of 60 large moving models over a whole stretch built within a 32 MB budget, its
frames taken further apart and its models sparser, a moment all the same; a light scene built at 2 cycles a frame as
ever; the budget and the buffer's cap inside their bounds); gradle test (202); hub_check. A delve-sized scene built in a
768 MB Java (ghillie's claude/outstation.md, "The moment's memory"): before, "Java heap space" with 20 models and a
12 MB buffer, or 30 and 300 MB held elsewhere; after, made each time (1-4 s). With room to spare a moment's the same as
the old build's (still dots, colours, frames and tracks; only `made` differs) when built the same way: the order of
models arriving on the same frame follows Java's identity hashes (an IdentityHashMap, as before), so two builds of one
scene can order them, and their random dots, differently; that was so before this fix too.

Cut into outside-plugin on `claude/exciting-lovelace-q14wvd`: 8b6d50c, the Hub's own 6bed558 (update 6 as merged,
runelite/plugin-hub's `plugins/outside` read 11/23/2026; the Mole's fix 50837e2, the boss audit and "only a fight with a
result goes to the page by itself" are in it) with only this fix on top, without update 7. (It was first cut on
50837e2, from this file's note that the fork stayed at daf45c6 until the Mole's fix was tried: that was out of date,
the Hub had 6bed558.) Checked on 8b6d50c: gradle test (134), hub_check, start_check. Before the fork: Jace's test in the
game (below), then commit= on the fork.

**Jace's test** (`Try the plugin.bat 8b6d50c`: `py run.py client` in outside-plugin at the fix's commit, `-ea`):
1. A Doom of Mokhaiotl delve (or any long fight with lots going on: a raid room, Zulrah) with Record my encounters on.
   When it ends, the side tab's row for it shows a moment (no "couldn't be made"), and the client log has
   `outside: ...'s moment: N KB, built in ... ms; ... the tracks about X KB, built at Y KB (frames Zx apart, D dots a
   model at most)`, and no `OutOfMemoryError` or "Java heap space" anywhere.
2. The client stays smooth while the moment's built (a second or a few after the fight ends): no freeze, no stutter
   worse than before.

Jace's test of 8b6d50c (11/23/2026, `-ea`, his client log): the Giant Mole in the open lair, recorded from his first
hit to the kill, its moment made: `1484 KB, built in 410 ms; the client thread's share 168 ms over 1036 frames (162 us a
frame) ... the tracks about 12841 KB, built at 12841 KB (frames 1x apart, 6000 dots a model at most)`, nothing thinned;
the day before's Mole moments on 6bed558 took 254-359 ms at 153-182 us a frame, so no slower. Nothing from wiki.outside
failing, no assertion, no heap error. The fork's `outside-memory-fix` (from the Hub's master, `plugins/outside` the
only change): commit=8b6d50ca497f98a61fe5cc2cadcdfc7233d0a1fb, jhmcubed/plugin-hub ffa8784. Jace opened it 11/23/2026: runelite/plugin-hub#21195, "Update Outside: fix out-of-memory when making a long fight's thumbnail" (plugin change, size-m).
3. Put it on your page: its tile turns as a moment on your profile.
4. A short fight (a GWD boss, Vorkath): its moment as before (frames 1x apart, 6000 dots a model at most in the log).

### Update 6 to the Hub (11/22/2026)

Jace's test of e6da89f: it starts and its tabs work; the key kept and copied; more than one goal, the model's choice and
"No 3D model"; goals reordered by dragging ("goal re-ordering works"); encounters kept for good, featured and taken
down (a raid with a death in this exact build not tried: his call to send it as it is); Edit my page. The delve's
levels are SaluteCFlagg's to try once it's out (the page counts every level now). The fork's branch
`outside-update-6` (jhmcubed/plugin-hub 320de11, from the Hub's master): `plugins/outside` commit= daf45c63ca48 (e6da89f with the description and tags naming the encounters, and itemdef lines carrying GE and HA prices for the viewer's ground item names), the
warning as the reviewer last wrote it; Jace opens the pull request. His client logs from the test (11/21 and 11/22, `-ea`):
nothing failing in wiki.outside and no client-thread assertion; the only uncaught exceptions are the loot tracker's, the
agility plugin's and Dude Where's My Stuff's. After it merges: the page's `ON_THE_HUB.goals` on
and the revision log's entry for the plugin.

The Mole (11/22/2026, Jace's test of daf45c6): in the lair, which isn't an instance, a recording began on entering, and
his first kill (someone else hit first: no kill credit, no count line) never ended it; the second, with credit, did. Fixed
in outside-plugin 50837e2 (outstation 61b0078): in a boss area that isn't an instance a recording starts with the fight
(a hit either way, or the attack on its boss), the boss fought dying ends it with or without the count line, and 90 s
with no hits ends a fight given up. Its checks pass; the fork stays at daf45c6 until he's tried 50837e2 in the game. (11/23/2026: it went to the Hub after all: `plugins/outside` is at 6bed558, which holds it.)

The boss audit (outstation ab804a7, outside-plugin c89762c) and then the upload rule (11/22/2026, Jace: "upload by
itself only when the fight had a result"; leaving "when the boss is alive" is "a failure"): `Encounters.sendsByItself`,
outstation cb3b172, outside-plugin 8b4e3f7. A kill (with or without credit), a completion, a chest, a death, a failed run,
leaving once fought, and anything from Record now go by themselves; the open area's quiet, an instance left before any
fight, watched NPCs gone, the three-hour limit, logging out or hopping, the client closed and the plugin turned off stay
on the computer for Put on my page. Checks on 8b4e3f7: gradle test (both), hub_check passed, start_check passed; the local
world's twelve stages pass (ghillie's tools/local-world/stagecheck.py, the Mole's instance and Vorkath left before and
after a fight new), and with uploads on the plugin queued exactly the kill and the fought-then-left one. The fork stays at
daf45c6 until Jace has tried 8b4e3f7 in the game with `-ea`.

Jace's test of 8b4e3f7 (11/22/2026, `-ea`, his client logs): the public lair's quiet fight stayed on his computer, its kill
went up, the Mole's instance started on entering, an instance entered and left with no fight stayed, its kill went up
(the relay's list: exactly the two kills). Nothing failing in wiki.outside, no assertion; the only uncaught exceptions
the loot tracker's and Dude Where's My Stuff's. Then the Hub README's theme song made SoundCloud only (it still said
YouTube, SoundCloud or Spotify; they went 11/21): outside-plugin 6bed558, its only difference from 8b4e3f7 the README;
hub_cut --check, hub_check, gradle test and start_check passed on it. The fork's `outside-update-6`: commit=
6bed5584623c (jhmcubed/plugin-hub 5332153). Jace opened it 11/22/2026: runelite/plugin-hub#21147, "Update Outside: encounters, more than one goal, Edit my page". **Merged 11/22/2026**, within the hour, size-xl and all. The page's `ON_THE_HUB.goals` on and the revision log's entry 37 (outside-wiki#164). After it merges: the page's `ON_THE_HUB.goals` on, the revision log's plugin entry, the update 6 image for Discord.
