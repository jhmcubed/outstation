#!/usr/bin/env python3
"""Presence.java's table of areas, written from RuneLite's own Discord plugin (11/16/2026; ghillie's
claude/outstation.md, "What RuneLite's Discord plugin shares"): each area's name, its group and its regions, exactly as
runelite/runelite's runelite-client/src/main/java/net/runelite/client/plugins/discord/DiscordGameEventType.java has
them, so what the Hub's plugin says of where a player is never says more than Discord's status does.

    python3 plugin/discord_areas.py DiscordGameEventType.java     rewrite the table between Presence.java's markers
    python3 plugin/discord_areas.py --check DiscordGameEventType.java   say whether it matches; write nothing

Fetch the file from RuneLite's repository (raw.githubusercontent.com/runelite/runelite/master/...) when it changes.
hub_cut.py never copies this script into outside-plugin (only the table it writes goes).
"""
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
PRESENCE = os.path.join(HERE, "src", "main", "java", "wiki", "outside", "Presence.java")
START, END = "\t// discord:areas", "\t// discord:end"
KINDS = {"BOSSES": "BOSS", "CITIES": "CITY", "DUNGEONS": "DUNGEON", "MINIGAMES": "MINIGAME", "RAIDS": "RAID",
         "REGIONS": "REGION"}
ENTRY = re.compile(r'^\s*([A-Z0-9_]+)\("([^"]+)"\s*,\s*DiscordAreaType\.([A-Z]+)((?:\s*,\s*\d+)*)\)\s*[,;]', re.M)


def table(src):
    lines = []
    for const, name, kind, ids in ENTRY.findall(src):
        regions = re.findall(r"\d+", ids)
        lines.append('\t\ta("%s", %s, %s),' % (name.replace('"', '\\"'), KINDS[kind], ", ".join(regions)))
    if not lines:
        raise SystemExit("no areas found: is that DiscordGameEventType.java?")
    lines[-1] = lines[-1].rstrip(",")
    return lines


def main():
    args = sys.argv[1:]
    check = "--check" in args
    args = [a for a in args if a != "--check"]
    if len(args) != 1:
        raise SystemExit(__doc__)
    with open(args[0], encoding="utf-8") as fh:
        rows = table(fh.read())
    with open(PRESENCE, encoding="utf-8") as fh:
        text = fh.read().split("\n")
    a, b = text.index(START), text.index(END)
    new = text[:a + 1] + rows + text[b:]
    if check:
        same = new == text
        print("discord_areas: %s (%d areas)" % ("matches" if same else "differs", len(rows)))
        sys.exit(0 if same else 1)
    with open(PRESENCE, "w", encoding="utf-8", newline="") as fh:
        fh.write("\n".join(new))
    print("discord_areas: %d areas written" % len(rows))


if __name__ == "__main__":
    main()
