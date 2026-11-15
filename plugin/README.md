# Outside, the RuneLite plugin

What you wear and carry, live on your Outside page: your equipment, inventory, looting bag, rune pouch and Dizana's quiver, your hitpoints, prayer, run and special, the prayers you have on, and every skill's level and experience. It sends them to your outstation's channel on the relay, the same one your transmission and its chat go through, and your page draws them beside them: live while you play, as last seen while you're away.

It works with or without the outstation program. With both, your page has your transmission, its chat and your loadout together. With only the plugin, it has your loadout.

## Setting it up

1. Install **Outside** from the Plugin Hub (RuneLite's wrench icon, then Plugin Hub; not there yet: see "Trying it before the Hub" below).
2. In the plugin's settings, paste your **station key** (it starts `outstation1:`): the one your outstation attached with, or a friend's invite.
3. Log in. The game's chat says "your outstation (name) is receiving what you wear and carry", with your page's address.

## Its side tab (11/15/2026)

The Outside tab in RuneLite's side bar (a lime ✼): whose page it is and **Open my page**; **Capture my character**, which records the next emote you do within 8 seconds from its start to its end (your pet's too), or you and your pet standing if you do nothing, and puts it on your page under the name you give it (blank: "idle" or "motion"), with your model standing still; the motions on your page, the one picked showing first to everyone who opens it, ✕ taking one away; and **Take my model off my page**. The same as `::outsidemodel`, `::outsidemotion <a name>` and `::outsideforget <a name>`. It needs "My character's model on my page" switched on.

## What it sends, and only that

To your relay (Jace's, on Cloudflare, unless your key says another), over one connection, and only while you're logged in:
- your character's name, your world, and your account type (normal, ironman…)
- **worn and carried** (one switch): what's in each equipment slot and inventory slot; the looting bag's contents once you've looked in it; the seed box's and the chugging barrel's once you've opened them (11/14/2026); the rune pouch's runes; Dizana's quiver's ammunition; and each of those items' name and Grand Exchange price, once each
- **skills** (one switch): each skill's level, boosted level and experience
- **hitpoints, prayer, run and special** (one switch), and which prayers are on
- **item charges** (one switch, on; 11/14/2026): each worn or carried item's charges, as the game tells you them in its chat when you check one or as it's used (only the number, by the item; kept on this RuneLite's profile so they're known after a restart)
- **Doom of Mokhaiotl delves** (one switch, on; 11/14/2026): when you read the board beside the Doom of Mokhaiotl, how many delves you've finished at each level and the waves past 8, as the Delve Calculator plugin reads it ("Credits")
- **my bank on my page** (one switch, off to begin with, behind its own warning; 11/14/2026): your whole bank (each item, how many, a placeholder as none, and the tabs' counts), three ticks after you last used it, to your relay's keep for your page (docs/protocol.md, "Kept for the page"); switching it off takes it off your page
- **my character's model on my page** (one switch, off to begin with, behind its own warning; 11/14/2026): your character's 3D model as you look now (its shape and colours, nothing else), when you switch it on and each time you type `::outsidemodel`, for your page to draw, with the pet following you if one is (11/15/2026); and `::outsidemotion <a name>` records the next thing you do (an emote, a special attack: from its start to its end, at most 4 seconds; nothing within 8 seconds, 3 seconds of you standing), your pet's motion with it, and keeps it on your page under that name (with no name, "idle" or "motion"): up to 8, one of the same name replaced, visitors choosing between them; `::outsideforget <a name>` takes one away; switching it off takes it down; and with it on (11/15/2026), the live view: your model in the gear you wear now (a few seconds after it changes, at most every 20 seconds), each animation you do recorded in the background the first time it's seen in that gear (and you standing), and which one you're doing now, so your page can show you as you are; and the last boss you fought, its model as the fight ends (20 seconds after your last hit on it), each boss at most once every 30 minutes, never from the Wilderness, a PvP area or a PvP or Deadman world
  - its effects (11/15/2026): the spot animations on you as it's captured (a special attack's, a teleport's, an emote's), each its own model, and through a recorded motion frame by frame; under the same switch, as they're part of how you look
- **the ground under my model** (one switch, off to begin with, behind its own warning; 11/15/2026): when you capture your character (::outsidemodel, ::outsidemotion or the side tab), the ground round you then, about five tiles each way (the tiles' paint and shape: no objects, people or items), so your page draws you standing on it. Never with the live view's own captures, which happen by themselves.
- that you've logged out
- **follow me on the globe** (one switch, off to begin with, behind its own warning; 11/08/2026): the rough area you were in two minutes ago, for your outstation's light on outside.wiki's globe to travel to. Only a square of the map about 128 tiles a side (the middle of the square, never your tile), at most once a minute (the same square again every 3 minutes while you stay in it). Never in the Wilderness (whatever "In the Wilderness too" says), a PvP area, or on a PvP or Deadman world: the moment you're in one, the area already sent is cleared, and nothing goes again till you've been clear of it two whole minutes. In an instance (a raid, a boss room, the Inferno), the square it's opened from (11/09/2026, Jace: "everyone knows where those instances get opened"): where its template lies on the map, read as RuneLite's own Discord plugin reads it, or for one off the map its entrance's square (the Karamja volcano for the Inferno and the Fight Caves, Mount Quidamortem, Ver Sinhaza, the Necropolis's pyramid, the Slayer Tower, Trollheim's God Wars way in; `Follow.ENTRANCES`); others off the map with no entrance on it (the Leviathan, the Whisperer, Duke Sucellus, the Phantom Muspah) send nothing. An instance in the Wilderness is still dangerous. The PvP minigames (Last Man Standing, Castle Wars, Clan Wars, Soul Wars, the Fight Pits, Emir's Arena) aren't dangerous since 11/10/2026 (Jace: "there is no danger to someone tracking them there"): the game's PvP-area flag doesn't clear the square in their regions (most of their arenas lie off the map, so send nothing anyway). Nor is Ferox Enclave, the Wilderness's safe town (11/10/2026, Jace: "a safe area/haven in the wilderness"): inside its barriers (`OutsidePlugin.FEROX`, from Shortest Path: "Credits") its own square goes, and stepping out into the Wilderness clears it at once. At sea (11/10/2026): the main map takes in Sailing's seas and islands (x 960 to 4031, y 2048 to 4223, measured from the game's own map squares: the band of sea Sailing put round the old map), and on a boat it's where the boat is at sea, read the way RuneLite itself reads it (the boat's place on the main world, never the deck's own tiles); no sea is dangerous, and the Wilderness and PvP-area flags count at sea as anywhere. Nowhere else off the main map (an underground area counts as the square above it; under the new seas, only the islands' caves), except Zanaris, which goes as one place whatever the tile (the globe shows it on its moon). Your relay shows it only while your outstation's transmitting and open, and drops it when you log out.
- **and my world** (one switch, off to begin with, behind its own warning, only with following on; 11/08/2026): the world you were on, with the square from the same moment. So the globe shows which world you were on and roughly where, two minutes ago. Never from a PvP, Deadman, high-risk, bounty, PvP Arena or Last Man Standing world (following clears there, as anywhere dangerous). Switching it off clears what was sent with it at once.
- **what I'm doing, on the globe** (one switch, off to begin with, behind its own warning; 11/08/2026; it doesn't need following): what you were doing two minutes ago, as one word for your light's mood, never more exactly: skilling (and which skill: your own latest experience drop, within about a minute), a boss (and which, from a fixed list of boss names, matched against the NPC you're attacking or that's attacking you: every boss on the official hiscores, from RuneLite's own list, 11/10/2026, the Wilderness's among them; the Gauntlet and the Corrupted Gauntlet for the whole run inside), a raid (the Chambers of Xeric, the Theatre of Blood, the Tombs of Amascut, the Fractured Archive) or a minigame (30, from Barbarian Assault to Zalcano, the Inferno, the Fight Caves, the Colosseum, Tempoross, Wintertodt and the PvP minigames among them), told by the region you're in, an instance's as its template lies, with RuneLite's Discord plugin's region ids (11/09/2026), and Barracuda Trials, Sailing's courses at sea, by the game's own in-trial flag (11/10/2026); Sailing's bosses (the Shellbane gryphon, the Mad Angel) are bosses like the rest, and its other work is skilling Sailing; fighting (anything else, a hit yours or on you within 15 seconds), or idle. A boss fight comes first wherever it is (Jad in the Fight Caves is Jad, then the Fight Caves again). The same safeguards as following: two minutes behind, at most once a minute, in instances too, never in a PvP area or on a PvP or Deadman world (Ferox Enclave and the PvP minigames are safe), cleared there at once and held till you've been clear of it two whole minutes. In the Wilderness (11/10/2026) only with "In the Wilderness too" on as well, since you've already chosen to transmit there: then this one word goes from there too (a Wilderness boss, say), two minutes late, while following's square, your world and the stars stay off in the Wilderness whatever that setting says.
- **shooting stars I find** (one switch, off to begin with, behind its own warning; 11/08/2026): each crashed shooting star you see, its size (tier 1 to 9), its world, the 128-tile square it's in (never its tile) and its landing site's name (11/09/2026, one of Shooting Star Info's sites: "Credits", below), two minutes late so it never says you're there now, and that it's gone once it's mined out while you watch. At most one report of a star every 3 minutes. Never one in the Wilderness (Jace's decision, 11/09/2026: Wilderness stars are never reported), nor one seen from it (whatever "In the Wilderness too" says), a PvP area or world, or Deadman (Ferox Enclave and the PvP minigames are safe). Your relay puts it on one list of stars for everyone.
- **name me as a star's finder** (one switch, off to begin with, behind its own warning, only with stars on; 11/09/2026): each star report says so (`"credit": true`), and the list of stars names your outstation as the finder of each star it told of first. Off, your stars still go on the list, counted, with no finder named. A hidden outstation (one with a watch code, or an invite not taken up yet) is never named either way: its stars show `by: null` (Jace's decision, 11/09/2026).
- **record my exact path for my year** (one switch, off to begin with, behind its own warning; 11/10/2026): the exact tile you're on, `{"here": {"x", "y", "plane", "world"}}`, for your year's record and nothing else. At once (not two minutes late, since only you see it), when it changes: at most one every 4 seconds while you move, and the tile you stop on 3 seconds after the last; nothing while you stand still (`Track`). Placed as following places it (`Follow.tile`): at sea, where the boat is; underground, the tile above; an instance where its template lies or at its entrance; Prifddinas where the world map draws it; Zanaris as its one place. Never in the Wilderness, a PvP area, or on a PvP or Deadman world (following's rules; Ferox Enclave and the PvP minigames are safe): there, off the map, on logging out and when it's switched off, `{"here": null}` goes once. Your relay passes it only to your own outstation's app (the desk's `{"mine"}` line), never keeps it, and never shows it to anyone (docs/protocol.md, "The exact path").

Only what changed goes, at most once a game tick (hitpoints and the like alone, at most every 3 seconds). In the Wilderness, a PvP area, or on a PvP or Deadman world, nothing more goes till you're out ("In the Wilderness too" turns that off, behind a warning), so nobody watching your page sees what you risk; your page says it's paused. Ferox Enclave and the PvP minigames (Last Man Standing, Castle Wars, Clan Wars, Soul Wars, the Fight Pits, Emir's Arena) are safe since 11/10/2026 (Jace: "stream should keep going"): it keeps going there, whatever the game's Wilderness and PvP-area flags say; a PvP or Deadman world still stops it. Never: your screen, your chat (only an item's charges, read from the game's own lines), the tile you're on (only the rough area, two minutes late, and only with following on), your bank (unless you switch "My bank on my page" on), other players, anything you type. The globe's four switches (following, your world, what you're doing, stars) all stay off on high-risk and bounty worlds too (not on the PvP Arena's and Last Man Standing's world types since 11/10/2026: those are only those minigames). In the Wilderness, "In the Wilderness too" lets only what you're doing through to the globe, never where you are. Your page shows what was sent to anyone who opens it, as your transmission is.

## Credits

The Doom of Mokhaiotl's board is read where the Delve Calculator plugin reads it (github.com/Speaax/Delve-calculator: its scoreboard interface, 920, and each level's count in child 46 + 3 a level), BSD 2-Clause, Copyright (c) 2025, Speaax. The character's model is read as RuneProfile's exporter reads it (github.com/ReinhardtR/runeprofile-plugin, its ModelMeshBuilder: the hidden faces' sentinel, the see-through ones left out), BSD 2-Clause, Copyright (c) 2022, Reinhardt Rijna. Both under the licence below, with their own names.

The shooting stars' landing sites (`Sites.java`) are Shooting Star Info's (github.com/pwatts6060/runelite-plugins, its `Location.java`), less its Wilderness sites and Prifddinas's, under its licence:

```
BSD 2-Clause License

Copyright (c) 2022, Cute Rock
All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice, this
   list of conditions and the following disclaimer.

2. Redistributions in binary form must reproduce the above copyright notice,
   this list of conditions and the following disclaimer in the documentation
   and/or other materials provided with the distribution.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```

Ferox Enclave's bounds (`OutsidePlugin.FEROX`) are the Shortest Path plugin's (github.com/Skretzo/shortest-path, its `WildernessChecker.java`, FEROX_ENCLAVE_1 to 5; BSD 2-Clause), five rectangles of the town inside its barriers: facts about the game's map, credited all the same.

The region ids that tell the raids, the minigames (the PvP minigames among them) and the Gauntlets (`Activity.java`), and the way an instance is placed where its template lies (`WorldPoint.fromLocalInstance`), are RuneLite's own Discord plugin's (`DiscordGameEventType`, `DiscordPlugin`; Copyright (c) 2018, Tomas Slusny and PandahRS, BSD 2-Clause like the rest of RuneLite). They're facts about the game (which region is which) rather than its code, but credited all the same. On a boat (11/10/2026), the player's place is read as RuneLite's `Perspective.localToMinimap` reads a point on a boat (`WorldEntity.transformToMainWorld`), and as the Sailing plugin (LlemonDuck/sailing, BSD 2-Clause) does; it tells a Barracuda Trial by the same game flag that plugin reads (`VarbitID.SAILING_BT_IN_TRIAL`).

## Trying it before the Hub

On a PC with RuneLite installed and Java 11 or newer:

```
py run.py client
```

(from the plugin's own folder: the root of jhmcubed/outside-plugin, or `plugin/` in jhmcubed/outstation)

It compiles the plugin against your installed RuneLite and opens RuneLite in developer mode with Outside loaded. A Jagex account needs the launcher's `--insecure-write-credentials` step once before that RuneLite can log in.

## For developers

- `build.gradle`: RuneLite's example-plugin template, as the Plugin Hub builds it (`gradle build` runs the tests).
- `src/main/java/wiki/outside/`:
  - `OutsidePlugin.java`: reading the game each tick, the connection to the relay
  - `Kit.java`: the fields, what changed, which items still need naming; kept apart from RuneLite so it's tested
  - `Attach.java`: the station key read back into the relay, the channel and the key
  - `Follow.java`, `Behind.java`: following's square (and the world), two minutes behind, and the timing and clearing it shares with the activity
  - `Activity.java`: what they're doing, from their own experience and their own fight, and the boss list
  - `Stars.java`: the shooting stars seen, each tier's object id, and the reports two minutes late (with the finder's credit only when asked for)
  - `Sites.java`: the stars' landing sites by name (Shooting Star Info's: "Credits")
  - `OutsideConfig.java`: the settings
- The relay's side: `docs/protocol.md`, "The game".

## For the Hub's review

Checked against RuneLite's own rules (the plugin-hub README, and the wiki's "Plugin Hub Review" and "Rejected or Rolled Back Features", read 11/07/2026). `py hub_check.py` (from the plugin's folder) checks the source for every forbidden feature and API before each submission or update.

**Precedents** (11/09/2026): [`docs/hub-precedents.md`](../docs/hub-precedents.md) goes through every piece of information this plugin reads or sends and names the Plugin Hub plugins (and RuneLite's own) that already read or send the same, with links to the exact lines, where this plugin is stricter, the Hub's written rules as they stand, and what the Hub requires of the licence and the repository (its own repository, with `runelite-plugin.properties` and a `LICENSE` at the root).

- **No forbidden features:** no reflection, JNI or JNA, other programs, code downloaded at runtime, `Desktop`/`LinkBrowser.open`, `Thread.sleep`/`interrupt`, `menuAction`, `java.io` files, `KeyboardFocusManager`, or network clients other than OkHttp. RuneLite's own Gson, OkHttp, executor and client thread are injected, never made. The package isn't `net.runelite`.
- **No new dependencies:** `build=standard`, nothing outside runelite-client.
- **Network IO** (a sensitive API, allowed): one WebSocket to the relay named in the station key the player pastes in, through RuneLite's OkHttp. It sends only the player's own game, as listed above; it reads nothing back.
- **The third-party warning:** on the "Send to my outstation" setting (`@ConfigItem(warning = …)`), and for the Hub's manifest (below).
- **Jagex's rules and the rejected list:**
  - It changes nothing in the game or RuneLite: no overlays, no menu entries, no input, no chat typed.
  - It reads only the player's own character: nothing about other players ("crowdsourcing" is on the rejected list).
  - Location: none, unless the player switches on "Follow me on the globe" (off to begin with, behind its own warning). Then only a 128-tile square of the main map, two minutes late, at most once a minute, cleared at once and held for two minutes in the Wilderness, PvP areas and worlds and Deadman worlds (the PvP minigames aren't dangerous since 11/10/2026: RuneLite's Discord plugin shows them as minigames too; nor is Ferox Enclave, the Wilderness's safe town, where no player can attack another). An instance goes as where it's opened (its template's square, or its entrance's), which everyone knows anyway. Prifddinas goes where the world map draws it, inside its gates in Tirannwn, with the Gauntlet and Zalcano at the city's middle (11/10/2026). Somewhere else safe but off the map (a player-owned house, the Abyss) sends nothing and only pauses the two minutes (11/10/2026): the places either side of it still go, two minutes behind, and the last one isn't said again while there. RuneLite's own Party plugin shares more (exact tiles, live, with the party's members); this is coarser and later, but public, so it may still be a question for the reviewers: `Follow.java` holds every rule, and `FollowTest` checks them.
  - The world (11/08/2026, "and my world", only with following): the player's own world, which the plugin already sends for their page and which the Hub's Star Calling Assist plugin submits too ("your current world number", behind its warning). It adds nothing about anyone else, and goes under following's rules.
  - The exact path (11/10/2026, "Record my exact path for my year"): the player's own exact tile, every few seconds while they move, for their own record only: the relay passes it to their own outstation's app and keeps nothing. The precedents send more, to others: RuneLite's own Party plugin sends each member's exact tile to the rest of the party through RuneLite's server, and Group Ironmen Tracker sends the exact tile to its website for the group (docs/hub-precedents.md, row 27). Never from anywhere dangerous.
  - What they're doing (11/08/2026, "What I'm doing, on the globe"): only the player's own state, as one coarse word. Skilling comes from their own experience (`StatChanged`), the boss or fighting from their own fight (a hitsplat on them, or theirs on an NPC), a raid or minigame from the region they're in (as RuneLite's Discord plugin shows it in Discord, the PvP minigames among them); the only names it ever sends are a boss's, a raid's or a minigame's, from fixed lists (the bosses from RuneLite's `HiscoreSkill` and `NpcID`), never a player's. Under following's rules (two minutes late, never anywhere dangerous), but for one: in the Wilderness it goes only when the player has also switched "In the Wilderness too" on (behind its own warning, which says so), and then only this word, never the square, the world or a star. `Activity.java`, `OutsidePlugin.activitySafe` and `ActivityTest`.
  - Shooting stars (11/08/2026, "Shooting stars I find"): a world object, not a player, the same thing the Hub's Shooting Stars plugin already submits ("the location and time of shooting stars", behind its warning). Coarser than that: the tier, the world, a 128-tile square and its landing site's name, two minutes late, never one in the Wilderness or seen from anywhere dangerous. The relay's list names the outstation that told of it first only if the player switched "Name me as a star's finder" on (off to begin with), as Star Miners' "Send in-game name" is opt-in; and it's their outstation's name, never their in-game name. Nothing about other players: not who's mining it, not how many. `Stars.java` and `StarsTest`.
  - It runs no server and exposes nothing over HTTP from the PC ("plugins which expose player information over HTTP" are rejected): it sends to the player's own outstation's relay, as RuneProfile and Wise Old Man's plugins send to theirs.
  - It's free, with nothing behind a paid service.
  - In the Wilderness, a PvP area, or on a PvP or Deadman world, it stops sending (unless the player turns "In the Wilderness too" on, behind its own warning), so nobody watching can see what a player risks. Not in Ferox Enclave or the PvP minigames (11/10/2026), where no one can take what they carry: `OutsidePlugin.held`, tested in `ActivityTest`.

## Where it lives (11/10/2026)

The plugin is written here, in `plugin/` of jhmcubed/outstation, beside the relays it talks to (their tests check its lists against theirs: the skills, bosses, raids, minigames and landing sites). jhmcubed/outside-plugin is its published copy, the repository the Hub builds: `plugin/`'s own history split out with git's subtree, so its root holds `runelite-plugin.properties` and the BSD 2-Clause `LICENSE`, as the Hub asks. To publish a change once it's merged here:

```
git subtree split --prefix=plugin origin/main -b plugin-only
git push https://github.com/jhmcubed/outside-plugin plugin-only:main
```

Change the plugin here, never in outside-plugin, or the next split won't carry it.

The manifest, `plugins/outside` in runelite/plugin-hub (the Hub builds outside-plugin's root):

```
repository=https://github.com/jhmcubed/outside-plugin.git
commit=<the full commit hash>
warning=This plugin submits your equipment, inventory, looting bag, rune pouch, item charges, skills and vitals, and the Doom of Mokhaiotl board's counts when you read it; if you switch them on, your bank and your character's model; if you switch them on, the rough area and world you were in two minutes ago, your exact path for your own record (passed only to your own outstation), what you were doing (skilling, bossing, raiding, a minigame, fighting or idle), and the location and size of shooting stars you see, with your outstation's name as their finder if you ask; and your IP address, to your outstation's relay, a server not controlled or verified by the RuneLite developers; your Outside page and outside.wiki show them publicly.
```

