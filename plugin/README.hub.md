# Outside

Your gear, stats, quests, diaries and slayer task on a page of your own at **Outside.wiki/<your name>**: your equipment, inventory, looting bag, seed box, chugging barrel, rune pouch and Dizana's quiver, each skill's level and experience, your quests, Achievement Diaries, combat achievements and slayer task. Your page updates when you press **Update my page**, and by itself once an hour while you're logged in. It can also show what you're doing, the way RuneLite's own Discord plugin shows it in your Discord status (the skill you're training, the area you're in by name).

## Getting started

1. Install **Outside** from the Plugin Hub (RuneLite's wrench icon, then Plugin Hub).
2. Log in, open the **Outside** side tab (a lime ✼) and press **Make my Outside page**. Your page is made under the name you're logged in as, at Outside.wiki/<your name>, and its key is saved in the plugin's settings for you. Its first update goes as it's made.
3. Prove the name is yours. Until then, the page marks it **unproven**. Gain some experience, stop, press **Update my page**, then log out once the side tab says **ready** (a second or two): within a few minutes the official hiscores confirm it. If you gain more experience after it says ready, the side tab tells you to press Update my page again first. The side tab says whether it's proven, and if it didn't work, why.
4. From then on, press **Update my page** whenever you want your page to show what you have now (once a minute at most: pressed sooner, the button counts down and it goes by itself when the minute's up). It also updates by itself every hour you're logged in. Your page says when it was last updated.

**More than one character:** each has its own page and key. Logging into another character shows that one's page in the side tab, or the button to make one. An alt never shows on a main's page.

**A name someone else took:** if someone made a page under your name but never proved it, the side tab says so, and proving it the same way (gain experience, stop, Update my page, log out once the side tab says ready) makes the page yours. A proven name can't be taken.

## The side tab

At the top: your page's address, a box with whether it's proven yours, who sees it and when it was last updated, and
three buttons: **Update my page** (it counts down if you press it within a minute of the last), **Open my page** and
**Show what's sent** (exactly what Update my page would send now, every field of it, in a window; nothing is sent by
opening it). Under them, two tabs:

- **Page:** when your page updates, and what goes with it; then, in this order:
  - **Your goal:** **Set my goal item**, and under it the goal you set with a ✕ that takes it off (the Goal item feature, below).
  - **Who sees it:** whether your page is open to anyone (and on Outside.wiki's globe while you play) or friends only. **Make it friends only** / **Open it to anyone**, and **Copy the friends' link**.
  - **Copy my Dink address:** if you use the [Dink](https://github.com/pajlads/DinkPlugin) plugin, this copies your page's address for it. Paste it on its own line in Dink's **Primary Webhook URLs**, and the notifications you switch on in Dink (levels, kills, drops, pets, clues, collection log slots…) show on your page and on Outside.wiki's globe as field reports as they happen. Outside itself sends nothing for this: Dink does, as it does to Discord. Nothing from the Wilderness or PvP is ever shown (a kill, death, drop or level there, a Wilderness boss, a player kill), nothing that names another player, and Dink's world, location and screenshot aren't kept. The address holds your page's key, so keep it to yourself; to stop, delete the line in Dink.
  - **Your page's register:** visitors sign your page's register (its guestbook). The side tab says how many notes are new, and once a login so does a line in your chat box. **Read and tend my register** opens your page in your browser with **take back** and **ban** beside each visitor's note, and who you've banned (with **unban**); the notes there count as read. The link works for 12 hours, only in that browser, and can do nothing else. The plugin only reads the count (with the proof, when you log in or press Refresh); it sends nothing for it.
  - **Take my page down** (it asks first: everything on the page goes and the name is free again).
- **Model** (with "Character model" on): **Capture my character** records the next emote you do within 8 seconds, from its start to its end (your pet's too), or you and your pet standing if you do nothing, and puts it on your page under the name you give it. **Ground under my model**, just under it, sends the ground round you (about five tiles each way: its paint and shape, nothing on it) with each capture, so your page draws you standing on it; ticking it asks first. The motions on your page are listed: pick the one shown first, take one off with ✕, or **Take my model off my page**. **Refresh** reads the list again, and whether your page is proven yours; it sends nothing. `::outsidemodel`, `::outsidemotion <name>` and `::outsideforget <name>` do the same from the chat.

## Settings

Under four headings, each switch on to begin with:

- **Your page:** **Send to my Outside page**, the main switch. Off, nothing is sent, and what it sent comes off your page.
<!-- hub:cut goal -->
- **Goal item:** **Set my goal item** (in the Outside side tab's Page tab, under Your goal, or `::outsidegoal`) opens the game's own item search in the chatbox: type part of a name, click the item you're after. Your page shows it under your character with its 3D model, your kill count where it drops, and how dry you are for it from the wiki's drop rates. The Page tab shows your goal under that button, with a ✕ to take it off (or `::outsidegoal clear`). When it drops for you (the loot of a kill, a raid's chest or a casket) or the game's collection log says you got it, your page says when, and at how many kills, with your next update.
<!-- hub:end goal -->
- **On your page:** **Gear and inventory**, **Skills**, **Quests, diaries and progress** (and the collection log's count, kill counts and clue counts), **Slayer task**, **Item charges**, **Doom of Mokhaiotl delves**, **Bank**, **Deathbank** and **Character model**: each its own switch, and switching one off takes it off your page. Bank, Deathbank and Character model show a warning when they're switched back on. (**Ground under my model** isn't here: it's a box in the side tab's Model tab, under Capture my character, and asks before it's switched back on.)
- **What you're doing:** **What I'm doing**, as RuneLite's Discord plugin shows it.
- **Advanced** (closed): **Station key**, your page's key, only if you were given one: an outstation's (its settings ▸ more ▸ copy my station key) puts your updates on the outstation's page, in place of a page you made here.

## What it sends, and only that

Everything goes to the Outside relay, a server on Cloudflare run by the plugin's author. As with any website, the relay sees your IP address.

**When you make your page** (only when you press Make my Outside page): your character's name and your account hash (the number RuneLite gives plugins for your account), once. The relay keeps only a hash of it, to prove the page is yours, and shows it to nobody.

**With each update** (when you press Update my page, once an hour while you're logged in, and once as your page is made), whatever is switched on:

- your character's name and account type (normal, ironman…)
- **worn and carried:** your equipment and inventory; the looting bag's contents once you've looked in it; the seed box's and chugging barrel's once you've opened them; your rune pouch's runes; Dizana's quiver's ammunition; and each item's name and Grand Exchange price
- **skills:** each skill's level, boosted level and experience
- **quests, diaries and combat achievements:** your quest points and which quests you've done or started, each Achievement Diary area's tiers and tasks done, and your combat achievement points and tasks done in each tier, and your collection log's count (slots filled and in all) with the names of the slots the game's chat said you added since the last update (the game's "Collection log - New addition notification" setting), and your kill counts and clue counts as the game's chat last said each one ("Your Vorkath kill count is: 418", "You have completed 152 hard Treasure Trails"; never a Wilderness boss's), kept on this RuneLite for the account
- **my slayer task:** the monster, how many are left of how many, the area if your master named one, your slayer points and your task streak
- **item charges:** each worn or carried item's charges, as the game tells you them in its chat when you check one or as it's used
- **Doom of Mokhaiotl delves:** when you read the board beside the Doom of Mokhaiotl, how many delves you've finished at each level (as the Delve Calculator plugin reads it)
- that you've logged out

**With the next update after you look** (only with its switch on):

- **my bank:** your whole bank, every item, how many and its tabs, read when you use your bank
- **my deathbank:** the Item Retrieval Service's deathbank (as at Hespori, Zulrah or Vorkath), which one it is and what's in it: what a death put there, read once you're back on your feet, and again as its window shows it when you look in it; emptied, it comes off your page

<!-- hub:cut goal -->
**When you set a goal item** (only with "Goal item" on): the item's name and its 3D model as the game draws it; and, when it drops for you or the collection log says you got it, your kill and clue counts then (sent with your next update).

<!-- hub:end goal -->
**When you capture your character** (only with "Character model" on, and only when you press Capture my character or use `::outsidemodel` or `::outsidemotion`): your character's model as you look then (its shape, colours and effects, nothing else), the motion's frames if you did an emote, your pet's the same; and, with "Ground under my model" on, the ground round you then, about five tiles each way (the tiles' paint and shape: no objects, people or items).

**What I'm doing** (sent as it changes, once a minute at most, and with each update): only what RuneLite's own Discord plugin puts in your Discord status: the skill you're training, and the area you're in by its name (a boss, a city, a dungeon, a minigame, a raid or a region), with one fixed point of that area so the globe can light you there. Never your exact tile or your world.

**In the Wilderness, a PvP area, or on a PvP or Deadman world**, no update is taken (pressed there, it waits and goes once you leave) and what you're doing says nothing. Ferox Enclave, the Wilderness's safe town, counts as safe.

**Never sent:** your screen, your chat (only an item's charges, read from the game's own messages), your exact location or world, your hitpoints or prayer, your bank (unless you switch on Bank), other players, or anything you type.

Your page shows what was sent to anyone who opens it, unless you make it friends only.

## Questions and problems

Open an issue at https://github.com/jhmcubed/outside-plugin/issues.

## Credits

The Achievement Diaries' completion rule and the game's diary summary scripts are read as Dink reads them (github.com/pajlads/DinkPlugin, its AchievementDiary and DiaryNotifier), BSD 2-Clause, Copyright (c) 2022, Jake Barter. The slayer task is read as RuneLite's own slayer plugin reads it (github.com/runelite/runelite, its SlayerPlugin), BSD 2-Clause, Copyright (c) 2017, Tyler, and (c) 2018, Shaun Dreclin.

What you're doing matches RuneLite's own Discord plugin on purpose: its areas' names, groups and region ids (`DiscordGameEventType`) and the way it reads them (`DiscordPlugin`, `DiscordState`), Copyright (c) 2018, Tomas Slusny and PandahRS, BSD 2-Clause. On a boat, the player's place is read as RuneLite's `Perspective.localToMinimap` reads it, and as the Sailing plugin (LlemonDuck/sailing, BSD 2-Clause) does.

Ferox Enclave's bounds are the Shortest Path plugin's (github.com/Skretzo/shortest-path, its `WildernessChecker.java`; BSD 2-Clause).

Outside is BSD 2-Clause licensed (`LICENSE`).
