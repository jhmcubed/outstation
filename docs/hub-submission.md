# The Plugin Hub submission (11/16/2026)

What goes to runelite/plugin-hub for Outside's first version: the lean cut (`plugin/hub_cut.py`), merged into
jhmcubed/outside-plugin's `main` as 0b90619 (jhmcubed/outside-plugin#1 and #2), checked to match the cut exactly.

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

## Sent (11/16/2026)

The file is on jhmcubed/plugin-hub's branch `outside` (ea2f261, from runelite/plugin-hub's master); the pull request
to runelite/plugin-hub is opened from it with the words above (Jace's press: this repository's session can't write to
runelite's). After its build: a ✔ beside `build`, and `RuneLite Plugin Hub Checks` saying nothing's needed; changes
asked for go into outstation's plugin/, cut into outside-plugin, merged, and the new hash pushed to that branch.
