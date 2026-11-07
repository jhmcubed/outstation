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

Only what changed goes, at most once a game tick (hitpoints and the like alone, at most every 3 seconds). Never: your screen, your chat, where you are, your bank, other players, anything you type. Your page shows what was sent to anyone who opens it, as your transmission is.

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
  - `OutsideConfig.java`: the settings
- The relay's side: `docs/protocol.md`, "The game".
