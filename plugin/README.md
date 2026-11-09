# Outside, the RuneLite plugin

What you wear and carry, live on your Outside page: your equipment, inventory, looting bag, rune pouch and Dizana's quiver, your hitpoints, prayer, run and special, the prayers you have on, and every skill's level and experience. It sends them to your outstation's channel on the relay, the same one your transmission and its chat go through, and your page draws them beside them: live while you play, as last seen while you're away.

It works with or without the outstation program. With both, your page has your transmission, its chat and your loadout together. With only the plugin, it has your loadout.

## Setting it up

1. Install **Outside** from the Plugin Hub (RuneLite's wrench icon, then Plugin Hub; not there yet: see "Trying it before the Hub" below).
2. In the plugin's settings, paste your **station key** (it starts `outstation1:`): the one your outstation attached with, or a friend's invite.
3. Log in. The game's chat says "your outstation (name) is receiving what you wear and carry", with your page's address.

## What it sends, and only that

To your relay (Jace's, on Cloudflare, unless your key says another), over one connection, and only while you're logged in:
- your character's name, your world, and your account type (normal, ironman…)
- **worn and carried** (one switch): what's in each equipment slot and inventory slot; the looting bag's contents once you've looked in it; the rune pouch's runes; Dizana's quiver's ammunition; and each of those items' name and Grand Exchange price, once each
- **skills** (one switch): each skill's level, boosted level and experience
- **hitpoints, prayer, run and special** (one switch), and which prayers are on
- that you've logged out
- **follow me on the globe** (one switch, off to begin with, behind its own warning; 11/08/2026): the rough area you were in two minutes ago, for your outstation's light on outside.wiki's globe to travel to. Only a square of the map about 128 tiles a side (the middle of the square, never your tile), at most once a minute (the same square again every 3 minutes while you stay in it). Never in the Wilderness, a PvP area or minigame (Last Man Standing, Castle Wars, Clan Wars, Soul Wars, the Fight Pits, Emir's Arena), or on a PvP or Deadman world: the moment you're in one, the area already sent is cleared, and nothing goes again till you've been clear of it two whole minutes. In an instance (a raid, a boss room, the Inferno), the square it's opened from (11/09/2026, Jace: "everyone knows where those instances get opened"): where its template lies on the map, read as RuneLite's own Discord plugin reads it, or for one off the map its entrance's square (the Karamja volcano for the Inferno and the Fight Caves, Mount Quidamortem, Ver Sinhaza, the Necropolis's pyramid, the Slayer Tower, Trollheim's God Wars way in; `Follow.ENTRANCES`); others off the map with no entrance on it (the Gauntlet, the Leviathan, the Whisperer, Duke Sucellus, the Phantom Muspah) send nothing. An instance in the Wilderness is still dangerous. Nowhere else off the main map (an underground area counts as the square above it), except Zanaris, which goes as one place whatever the tile (the globe shows it on its moon). Your relay shows it only while your outstation's transmitting and open, and drops it when you log out.
- **and my world** (one switch, off to begin with, behind its own warning, only with following on; 11/08/2026): the world you were on, with the square from the same moment. So the globe shows which world you were on and roughly where, two minutes ago. Never from a PvP, Deadman, high-risk, bounty, PvP Arena or Last Man Standing world (following clears there, as anywhere dangerous). Switching it off clears what was sent with it at once.
- **what I'm doing, on the globe** (one switch, off to begin with, behind its own warning; 11/08/2026; it doesn't need following): what you were doing two minutes ago, as one word for your light's mood, never more exactly: skilling (and which skill: your own latest experience drop, within about a minute), a boss (and which, from a fixed list of boss names, matched against the NPC you're attacking or that's attacking you), a raid (the Chambers of Xeric, the Theatre of Blood, the Tombs of Amascut, the Fractured Archive) or a minigame (24, from Barbarian Assault to Wintertodt, the Inferno, the Fight Caves, the Colosseum and the Gauntlets among them), told by the region you're in, an instance's as its template lies, with RuneLite's Discord plugin's region ids (11/09/2026); fighting (anything else, a hit yours or on you within 15 seconds), or idle. A boss fight comes first wherever it is (Jad in the Fight Caves is Jad). No PvP minigames. The same safeguards as following: two minutes behind, at most once a minute, in instances too, never in the Wilderness, a PvP area or minigame, or on a PvP or Deadman world, cleared there at once and held till you've been clear of it two whole minutes.
- **shooting stars I find** (one switch, off to begin with, behind its own warning; 11/08/2026): each crashed shooting star you see, its size (tier 1 to 9), its world, the 128-tile square it's in (never its tile) and its landing site's name (11/09/2026, one of Shooting Star Info's sites: "Credits", below), two minutes late so it never says you're there now, and that it's gone once it's mined out while you watch. At most one report of a star every 3 minutes. Never one in the Wilderness (for now: Jace hasn't decided), nor one seen from a PvP area, world or minigame, or Deadman. Your relay puts it on one list of stars for everyone, with your outstation's name as its finder if you told of it first (11/09/2026), unless your outstation has a watch code.

Only what changed goes, at most once a game tick (hitpoints and the like alone, at most every 3 seconds). In the Wilderness, a PvP area, or on a PvP or Deadman world, nothing more goes till you're out ("In the Wilderness too" turns that off, behind a warning), so nobody watching your page sees what you risk; your page says it's paused. Never: your screen, your chat, the tile you're on (only the rough area, two minutes late, and only with following on), your bank, other players, anything you type. The globe's four switches (following, your world, what you're doing, stars) all stay off on high-risk, bounty, PvP Arena and Last Man Standing worlds too. Your page shows what was sent to anyone who opens it, as your transmission is.

## Credits

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

The region ids that tell the raids, the minigames and the PvP minigames (`Activity.java`), and the way an instance is placed where its template lies (`WorldPoint.fromLocalInstance`), are RuneLite's own Discord plugin's (`DiscordGameEventType`, `DiscordPlugin`; Copyright (c) 2018, Tomas Slusny and PandahRS, BSD 2-Clause like the rest of RuneLite). They're facts about the game (which region is which) rather than its code, but credited all the same.

## Trying it before the Hub

On a PC with RuneLite installed and Java 11 or newer:

```
py plugin/run.py client
```

It compiles the plugin against your installed RuneLite and opens RuneLite in developer mode with Outside loaded. A Jagex account needs the launcher's `--insecure-write-credentials` step once before that RuneLite can log in.

## For developers

- `build.gradle`: RuneLite's example-plugin template, as the Plugin Hub builds it (`gradle build` runs the tests).
- `src/main/java/wiki/outside/`:
  - `OutsidePlugin.java`: reading the game each tick, the connection to the relay
  - `Kit.java`: the fields, what changed, which items still need naming; kept apart from RuneLite so it's tested
  - `Attach.java`: the station key read back into the relay, the channel and the key
  - `Follow.java`, `Behind.java`: following's square (and the world), two minutes behind, and the timing and clearing it shares with the activity
  - `Activity.java`: what they're doing, from their own experience and their own fight, and the boss list
  - `Stars.java`: the shooting stars seen, each tier's object id, and the reports two minutes late
  - `Sites.java`: the stars' landing sites by name (Shooting Star Info's: "Credits")
  - `OutsideConfig.java`: the settings
- The relay's side: `docs/protocol.md`, "The game".

## For the Hub's review

Checked against RuneLite's own rules (the plugin-hub README, and the wiki's "Plugin Hub Review" and "Rejected or Rolled Back Features", read 11/07/2026). `py plugin/hub_check.py` checks the source for every forbidden feature and API before each submission or update.

- **No forbidden features:** no reflection, JNI or JNA, other programs, code downloaded at runtime, `Desktop`/`LinkBrowser.open`, `Thread.sleep`/`interrupt`, `menuAction`, `java.io` files, `KeyboardFocusManager`, or network clients other than OkHttp. RuneLite's own Gson, OkHttp, executor and client thread are injected, never made. The package isn't `net.runelite`.
- **No new dependencies:** `build=standard`, nothing outside runelite-client.
- **Network IO** (a sensitive API, allowed): one WebSocket to the relay named in the station key the player pastes in, through RuneLite's OkHttp. It sends only the player's own game, as listed above; it reads nothing back.
- **The third-party warning:** on the "Send to my outstation" setting (`@ConfigItem(warning = …)`), and for the Hub's manifest (below).
- **Jagex's rules and the rejected list:**
  - It changes nothing in the game or RuneLite: no overlays, no menu entries, no input, no chat typed.
  - It reads only the player's own character: nothing about other players ("crowdsourcing" is on the rejected list).
  - Location: none, unless the player switches on "Follow me on the globe" (off to begin with, behind its own warning). Then only a 128-tile square of the main map, two minutes late, at most once a minute, cleared at once and held for two minutes in the Wilderness, PvP areas, worlds and minigames and Deadman worlds. An instance goes as where it's opened (its template's square, or its entrance's), which everyone knows anyway. RuneLite's own Party plugin shares more (exact tiles, live, with the party's members); this is coarser and later, but public, so it may still be a question for the reviewers: `Follow.java` holds every rule, and `FollowTest` checks them.
  - The world (11/08/2026, "and my world", only with following): the player's own world, which the plugin already sends for their page and which the Hub's Star Calling Assist plugin submits too ("your current world number", behind its warning). It adds nothing about anyone else, and goes under following's rules.
  - What they're doing (11/08/2026, "What I'm doing, on the globe"): only the player's own state, as one coarse word. Skilling comes from their own experience (`StatChanged`), the boss or fighting from their own fight (a hitsplat on them, or theirs on an NPC), a raid or minigame from the region they're in (as RuneLite's Discord plugin shows it in Discord); the only names it ever sends are a boss's, a raid's or a minigame's, from fixed lists, never a player's. Under following's rules (two minutes late, never anywhere dangerous). `Activity.java` and `ActivityTest`.
  - Shooting stars (11/08/2026, "Shooting stars I find"): a world object, not a player, the same thing the Hub's Shooting Stars plugin already submits ("the location and time of shooting stars", behind its warning). Coarser than that: the tier, the world, a 128-tile square and its landing site's name, two minutes late, never one in the Wilderness or seen from anywhere dangerous. The relay's list names the outstation that told of it first: the player's own outstation, by its own choice. Nothing about other players: not who's mining it, not how many. `Stars.java` and `StarsTest`.
  - It runs no server and exposes nothing over HTTP from the PC ("plugins which expose player information over HTTP" are rejected): it sends to the player's own outstation's relay, as RuneProfile and Wise Old Man's plugins send to theirs.
  - It's free, with nothing behind a paid service.
  - In the Wilderness, a PvP area, or on a PvP or Deadman world, it stops sending (unless the player turns "In the Wilderness too" on, behind its own warning), so nobody watching can see what a player risks.

The manifest, `plugins/outside` in runelite/plugin-hub, once the plugin has a repository of its own (the Hub builds a repository's root):

```
repository=https://github.com/jhmcubed/outside-plugin.git
commit=<the full commit hash>
warning=This plugin submits your equipment, inventory, looting bag, rune pouch, skills and vitals; if you switch them on, the rough area and world you were in two minutes ago, what you were doing (skilling, bossing, raiding, a minigame, fighting or idle), and the location and size of shooting stars you see, with your outstation's name as their finder; and your IP address, to your outstation's relay, a server not controlled or verified by the RuneLite developers; your Outside page and outside.wiki show them publicly.
```

