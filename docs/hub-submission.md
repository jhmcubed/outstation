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

## Update 6: the key kept and found, the delve levels a death lost, more than one goal, encounters (11/19/2026, encounters 11/21/2026; built, waiting for Jace's test in the game)

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
- **Left out entirely**: mouse presses, clicks, their screen places, the interface layout, markers, hotkeys and his overlay.
- **The side tab's third tab, Encounters**: Record my encounters, Record now / Stop, the recent ones (who, when, how long, killed or died), each with Put on my page (a window first, saying what was taken out and what goes), shown to anyone, Open, and ✕.
- **Sent only on the player's press**: the recording stripped exactly as the page's strip.js strips one (other players' names, other people's chat, exact XP; checked byte for byte against strip.js), to the relay's existing `POST /fights/<channel>?key=`. Opening the tab reads `GET /fights/<channel>?key=` (what's on the page, whether a new one starts shown, and whether the relay takes encounters at all: its fights are off until Jace switches them on, and the tab says so plainly).

**For the reviewer.** This adds the first thing in the Hub's version that reads other players: with "Players around you" on (as his default), a recording keeps the players near you (positions, gear, animations), as Encounter Recorder does on the Hub. It's on the player's computer; only Put on my page sends one fight, every other player's name taken out first (in any text too) with their chat. The manifest's `warning=` gains it:

```
warning=This plugin submits your gear, stats, quests, diaries, slayer task, what you're doing (as Discord shows it), your account hash and your IP address to a 3rd-party server when you press Update my page, and hourly; and a recorded fight, other players' names taken out, when you press Put on my page. Your Outside page shows them publicly.
```

The order: nothing on the relay or the page changes for it (the routes are the relay's own since 11/20/2026); the relay's `FIGHTS` switch stays Jace's call, and until it's on the tab says encounters aren't taken yet and keeps them on the computer.

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
