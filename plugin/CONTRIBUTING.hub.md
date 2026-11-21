# Contributing to Outside

Issues and pull requests are welcome at https://github.com/jhmcubed/outside-plugin.

## Building and testing

The build is RuneLite's example-plugin template, the same one the Plugin Hub builds:

```
gradle build
```

compiles the plugin and runs its tests.

To try it in RuneLite in developer mode, on a PC with RuneLite installed and Java 11 or newer, from this folder:

```
python run.py client
```

It compiles the plugin against your installed RuneLite and opens RuneLite with Outside loaded. A Jagex account needs the launcher's `--insecure-write-credentials` step once before that RuneLite can log in.

`python hub_check.py` checks the source against the Plugin Hub's rules (forbidden language features and APIs, sensitive and restricted ones).

## The source

`src/main/java/wiki/outside/`:

- `OutsidePlugin.java`: reading the game, "Update my page" and the hourly update, the posts to the relay
- `OutsidePanel.java`: the side tab
- `Kit.java`: the fields and the items in them, kept apart from RuneLite so they can be tested
- `Kept.java`: an item's charges, read from the game's own chat line
- `Progress.java`: quests, diaries, combat achievements and the slayer task
- `Presence.java`: what you're doing, as RuneLite's Discord plugin shows it, and its table of areas
- `Attach.java`: the page's key: the relay, the channel and the key
<!-- hub:cut encounters -->
- `Encounters.java`: the encounters' recorder (Encounter Recorder's, by Mr Gnome, folded in: `NOTICE`), with `EncounterSnap.java` (its lines), `EncounterFiles.java` (writing, packing and naming its files), `EncounterShelf.java` (the side tab's list) and `EncounterStrip.java` (what's taken out before one goes on the page, as the page's own strip.js)
<!-- hub:end encounters -->
- `OutsideConfig.java`: the settings

## Where changes are made

This repository is written by a script (`hub_cut.py`) from the `plugin/` folder of jhmcubed/outstation, where the plugin is developed beside the relay it talks to and that relay's protocol (`docs/protocol.md`, "The snapshot"). A pull request here is welcome; it's carried over there by hand, so the next cut keeps it.
