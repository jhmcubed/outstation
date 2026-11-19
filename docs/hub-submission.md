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

## Update 6: the key kept and found (11/19/2026, built, waiting for Jace's test in the game)

Jace's PC lost sburb's key on 11/19, the morning his MacBook first ran Outside (RuneLite's cloud sync between the two the likely cause; not the plugin, not the uninstall, not RuneLite's install defaults: each read in RuneLite 1.13.7's own code). His key, from his outstation, was only in Advanced, Station key: the one kept with the account is only made by Make my Outside page. And a player with only the Hub's plugin had no way to see their key at all, for another computer. So:

- A pasted key the relay takes (an update answered 200) is kept with the account too (`keepKey`), a second copy that a settings Reset or an emptied setting doesn't lose.
- **Copy my station key** in the Hub's version too, the Page tab's "Your page's key", for Outside on another computer.
- The no-page box and the "proven or vouched for already" answer say where the key is: the Page tab on a computer that shows the page (and, in the whole plugin, the outstation's settings).

Not in it, Jace's call: a proven page whose every key is lost can't be taken back (a proven name takes no challenge).
