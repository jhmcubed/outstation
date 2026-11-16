# Outside

Your gear, stats, quests, diaries and slayer task on a page of your own at **outside.wiki/<your name>**: your equipment, inventory, looting bag, seed box, chugging barrel, rune pouch and Dizana's quiver, each skill's level and experience, your quests, Achievement Diaries, combat achievements and slayer task. Your page updates when you press **Update my page**, and by itself once an hour while you're logged in. It can also show what you're doing, the way RuneLite's own Discord plugin shows it in your Discord status (the skill you're training, the area you're in by name).

## Getting started

1. Install **Outside** from the Plugin Hub (RuneLite's wrench icon, then Plugin Hub).
2. Log in, open the **Outside** side tab (a lime ✼) and press **Make my Outside page**. Your page is made under the name you're logged in as, at outside.wiki/<your name>, and its key is saved in the plugin's settings for you. Its first update goes as it's made.
3. Prove the name is yours. Until then, the page marks it **unproven**. Gain any experience, press **Update my page**, wait a minute, then log out: within a few minutes the official hiscores confirm it. The side tab says whether it's proven.
4. From then on, press **Update my page** whenever you want your page to show what you have now (once a minute at most: pressed sooner, the button counts down and it goes by itself when the minute's up). It also updates by itself every hour you're logged in. Your page says when it was last updated.

**More than one character:** each has its own page and key. Logging into another character shows that one's page in the side tab, or the button to make one. An alt never shows on a main's page.

**A name someone else took:** if someone made a page under your name but never proved it, the side tab says so, and proving it the same way (gain experience, Update my page, wait a minute, log out) makes the page yours. A proven name can't be taken.

## The side tab

- Whose page it is, **Open my page**, **Make my Outside page**, and whether it's proven yours.
- **Update my page**, and when it was last updated. **Refresh** asks again whether it's proven.
- **Show what's sent:** exactly what Update my page would send now, every field of it, in a window. Nothing is sent by opening it.
- **Copy my Dink address:** if you use the [Dink](https://github.com/pajlads/DinkPlugin) plugin, this copies your page's address for it. Paste it on its own line in Dink's **Primary Webhook URLs**, and the notifications you switch on in Dink (levels, kills, drops, pets, clues, collection log slots…) show on your page and on outside.wiki's globe as field reports as they happen. Outside itself sends nothing for this: Dink does, as it does to Discord. Nothing from the Wilderness or PvP is ever shown (a kill, death, drop or level there, a Wilderness boss, a player kill), nothing that names another player, and Dink's world, location and screenshot aren't kept. The address holds your page's key, so keep it to yourself; to stop, delete the line in Dink.
- **Who sees it:** whether your page is open to anyone (and on outside.wiki's globe while you play) or friends only. **Make it friends only** / **Open it to anyone**, **Copy the friends' link**, and **Take my page down** (it asks first: everything on the page goes and the name is free again).
- **Your page's chat:** visitors can leave lines on your page. **Tend my page's chat** opens your page in your browser with **take back** and **ban** beside each visitor's line, and who you've banned (with **unban**). The link works for 12 hours, only in that browser, and can do nothing else.

## Settings

- **Send to my Outside page:** the main switch. Off, nothing is sent, and what it sent comes off your page.
- **Worn and carried**, **Skills**, **Quests, diaries and combat achievements** (and the collection log's count), **My slayer task**, **Item charges**, **Doom of Mokhaiotl delves**, **What I'm doing, as Discord shows it:** what goes with each update, each its own switch.

## What it sends, and only that

Everything goes to the Outside relay, a server on Cloudflare run by the plugin's author. As with any website, the relay sees your IP address.

**When you make your page** (only when you press Make my Outside page): your character's name and your account hash (the number RuneLite gives plugins for your account), once. The relay keeps only a hash of it, to prove the page is yours, and shows it to nobody.

**With each update** (when you press Update my page, once an hour while you're logged in, and once as your page is made), whatever is switched on:

- your character's name and account type (normal, ironman…)
- **worn and carried:** your equipment and inventory; the looting bag's contents once you've looked in it; the seed box's and chugging barrel's once you've opened them; your rune pouch's runes; Dizana's quiver's ammunition; and each item's name and Grand Exchange price
- **skills:** each skill's level, boosted level and experience
- **quests, diaries and combat achievements:** your quest points and which quests you've done or started, each Achievement Diary area's tiers and tasks done, and your combat achievement points and tasks done in each tier, and your collection log's count (slots filled and in all) with the names of the slots the game's chat said you added since the last update (the game's "Collection log - New addition notification" setting)
- **my slayer task:** the monster, how many are left of how many, the area if your master named one, your slayer points and your task streak
- **item charges:** each worn or carried item's charges, as the game tells you them in its chat when you check one or as it's used
- **Doom of Mokhaiotl delves:** when you read the board beside the Doom of Mokhaiotl, how many delves you've finished at each level (as the Delve Calculator plugin reads it)
- that you've logged out

**What I'm doing, as Discord shows it** (sent as it changes, once a minute at most, and with each update): only what RuneLite's own Discord plugin puts in your Discord status: the skill you're training, and the area you're in by its name (a boss, a city, a dungeon, a minigame, a raid or a region), with one fixed point of that area so the globe can light you there. Never your exact tile or your world.

**In the Wilderness, a PvP area, or on a PvP or Deadman world**, no update is taken (pressed there, it waits and goes once you leave) and what you're doing says nothing. Ferox Enclave, the Wilderness's safe town, counts as safe.

**Never sent:** your screen, your chat (only an item's charges, read from the game's own messages), your exact location or world, your hitpoints or prayer, your bank, other players, or anything you type.

Your page shows what was sent to anyone who opens it, unless you make it friends only.

## Questions and problems

Open an issue at https://github.com/jhmcubed/outside-plugin/issues.

## Credits

The Achievement Diaries' completion rule and the game's diary summary scripts are read as Dink reads them (github.com/pajlads/DinkPlugin, its AchievementDiary and DiaryNotifier), BSD 2-Clause, Copyright (c) 2022, Jake Barter. The slayer task is read as RuneLite's own slayer plugin reads it (github.com/runelite/runelite, its SlayerPlugin), BSD 2-Clause, Copyright (c) 2017, Tyler, and (c) 2018, Shaun Dreclin.

What you're doing matches RuneLite's own Discord plugin on purpose: its areas' names, groups and region ids (`DiscordGameEventType`) and the way it reads them (`DiscordPlugin`, `DiscordState`), Copyright (c) 2018, Tomas Slusny and PandahRS, BSD 2-Clause. On a boat, the player's place is read as RuneLite's `Perspective.localToMinimap` reads it, and as the Sailing plugin (LlemonDuck/sailing, BSD 2-Clause) does.

Ferox Enclave's bounds are the Shortest Path plugin's (github.com/Skretzo/shortest-path, its `WildernessChecker.java`; BSD 2-Clause).

Outside is BSD 2-Clause licensed (`LICENSE`).
