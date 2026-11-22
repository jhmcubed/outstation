#!/usr/bin/env python3
"""The Plugin Hub's Outside, cut from the whole plugin (11/16/2026, Jace: "lean, as the table has it": the first
submission with what makes a page worth having, the rest brought in as updates, each small enough to read at once).

This folder (outstation's plugin/) stays the one plugin, everything in it. The Hub builds jhmcubed/outside-plugin, and
this script writes that repository's tree from here, leaving out the parts not submitted yet:

  - whole files, by PART_FILES below;
  - regions of a file between marker lines, the markers' own lines with them:
        Java, Gradle:   // hub:cut <part>[,<part>…]       …       // hub:end <part>
        Markdown:       <!-- hub:cut <part> -->          …       <!-- hub:end <part> -->
    a region goes when every part it names is out (code two parts share stays while either is in); regions nest (since
    11/16/2026: a line goes when any region round it goes, so the live part can hold the globe's and the year's);
  - `// hub:only` lines: kept only in the cut, their marker taken off (a line the lean plugin needs in place of a
    region, e.g. a default), and dropped from nothing else: in the whole plugin they're comments;
  - Markdown's words for the cut alone, hidden in an HTML comment in the whole plugin's own:
        <!-- hub:else <part>[,<part>…]
        …the cut's wording…
        -->
    kept (its comment taken off) while every part it names is out, dropped once one is in.

The Hub's README is its own (11/16/2026, the reviewer on runelite/plugin-hub#20811: "make the README end-user focused,
since it's published on the RuneLite website at the pinned commit. Remove anything about the plugin not yet being approved
or the submission process, and move any build instructions into a CONTRIBUTING.md"; and "don't link to or mention [the
exe] in the README"): README.hub.md becomes the cut's README.md and CONTRIBUTING.hub.md its CONTRIBUTING.md (HUB_FILES);
this folder's README.md, the whole plugin's developer notes, never goes. Both .hub.md files take the markers too. When an
update brings a part in, say what it does in README.hub.md, for players, and never name the outstation there.

The Hub's version is the snapshot (11/16/2026, the Hub's answer on runelite/plugin-hub#20811: "An 'Update my page'
action and an hourly sync should be okay, as should matching Discord activity"; ghillie's claude/outstation.md, "The
Hub's answer, and the snapshot version"): gear, stats, quests, diaries, combat achievements and the slayer task on
"Update my page" and hourly, and what they're doing as Discord shows it (Presence), posted; claiming, proving, who sees
it. The whole plugin (developer mode) keeps everything below as well.

The parts left out (SUBMITTED: those in the Hub's version now; add a part's name to it when its update is submitted):
  live    never, as things stand: the game socket, the kit and vitals every tick, the world, and every tie to the
          outstation ({"app"}, "while transmitting", Copy my station key): "Hub plugins can't interface with external
          programs outside of Hub review". Every part below but reports needs it.
  reports to ask the reviewers about once the snapshot is in: levels, kills, drops and log slots (Reports, Dink's route)
  globe   following, the world with it, the two-minutes-late activity, shooting stars, "In the Wilderness too"
  delve   the Doom of Mokhaiotl's board
  year    your year and its exact path
  model   the character's model, its motions, the live view, the boss fought, effects, the ground
  bank    the bank on the page
  deathbank the deathbank (the Item Retrieval Service's) on the page, as they last looked in it
  talk    the page's chat in the game (::o, /o) and emotes
  goal    the goal item (update 3): the item search, its model, got by the collection log's line
  encounters  (update 6, 11/21/2026) fights at bosses and raids recorded to the player's computer (Encounter Recorder's
          recorder folded in, BSD 2-Clause, NOTICE), and one put on their page on their press, stripped first
  arrange (update 6, 11/21/2026) "Edit my page": the page opened with a link that arranges it and sets its theme song
          (a SoundCloud, YouTube or Spotify link), for 2 hours; Jace: "Build it, they should be a part of update 6"

    python3 plugin/hub_cut.py ../outside-plugin           write the cut there (its .git and build left alone)
    python3 plugin/hub_cut.py --check ../outside-plugin   say whether it matches; write nothing
    python3 plugin/hub_cut.py --list                      the parts, what each takes out, and what's submitted
"""
import argparse
import filecmp
import os
import re
import shutil
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
PARTS = ["live", "reports", "globe", "delve", "year", "model", "bank", "deathbank", "talk", "goal", "encounters", "arrange"]
SUBMITTED = ["delve", "model", "bank", "deathbank", "goal", "encounters", "arrange"]   # (11/21/2026: "arrange", Edit my page, for update 6 too; the parts in the Hub's version beyond the snapshot; 11/16/2026: the delve board; 11/17/2026, update 2: the model, the bank and the deathbank, the bank's and the deathbank's held for the next update; 11/18/2026, update 3: the goal item, its "got" held for the next update; 11/21/2026, update 6: the encounters, Jace: "include it in update 6 along with our other changes")

# whole files that belong to a part (paths under this folder)
J = "src/main/java/wiki/outside/"
T = "src/test/java/wiki/outside/"
PART_FILES = {
    "globe": [J + "Follow.java", J + "Behind.java", J + "Activity.java", J + "Stars.java", J + "Sites.java",
              T + "FollowTest.java", T + "ActivityTest.java", T + "StarsTest.java"],
    "year": [J + "Record.java", J + "Track.java", T + "RecordTest.java", T + "TrackTest.java", "src/test/resources"],
    "model": [],
    "arrange": [],
    "talk": [J + "Talk.java", J + "Emotes.java", T + "TalkTest.java", T + "EmotesTest.java"],
    "reports": [J + "Reports.java", T + "ReportsTest.java"],
    "goal": [J + "Goal.java", T + "GoalTest.java"],
    "delve": [],   # (Delves.java, the levels a death lost, taken out 11/22/2026)
    "encounters": [J + "Encounters.java", J + "EncounterSnap.java", J + "EncounterFiles.java", J + "EncounterStrip.java",
                   J + "EncounterShelf.java", J + "EncounterMoment.java", J + "EncounterScene.java", J + "EncounterQueue.java",
                   T + "EncounterStripTest.java", T + "EncounterFilesTest.java", T + "EncountersTest.java",
                   T + "EncounterMomentTest.java", T + "EncounterQueueTest.java", T + "RunsTest.java", "NOTICE",
                   # (the boss audit's table, its test and write-up, 11/22/2026)
                   J + "EncounterBosses.java", T + "EncounterBossesTest.java", "docs/encounter-bosses.md"],
}
# never copied: what's built, and what only this repository needs
SKIP = {"build", ".gradle", ".git", "hub_cut.py", "discord_areas.py", "__pycache__", "README.md"}
# the cut's own files, written under another name there (this folder's README.md, in SKIP, is the whole plugin's)
HUB_FILES = {"README.hub.md": "README.md", "CONTRIBUTING.hub.md": "CONTRIBUTING.md"}
TEXT = (".java", ".md", ".gradle", ".properties", ".py", ".txt", ".json")

CUT = re.compile(r"^\s*(?://|<!--)\s*hub:cut\s+([a-z,]+)\s*(?:-->)?\s*$")
END = re.compile(r"^\s*(?://|<!--)\s*hub:end\s+([a-z,]+)\s*(?:-->)?\s*$")
ONLY = re.compile(r"^(\s*)//\s*hub:only\s?(.*)$")
ELSE = re.compile(r"^\s*<!--\s*hub:else\s+([a-z,]+)\s*$")


def out_parts():
    return [p for p in PARTS if p not in SUBMITTED]


def cut_text(text, out, name="?"):
    """The text with every region naming a part that's out taken away, and the markers of those kept taken off."""
    lines, keep, stack, else_ = text.split("\n"), [], [], None
    for n, line in enumerate(lines, 1):
        if else_ is not None:
            if line.strip() == "-->":
                else_ = None
            elif else_:
                keep.append(line)
            continue
        el = ELSE.match(line)
        if el:
            parts = el.group(1).split(",")
            bad = [p for p in parts if p not in PARTS]
            if bad:
                raise SystemExit("%s:%d: no such part %s" % (name, n, bad))
            else_ = all(p in out for p in parts) and not any(gone for _, gone, _ in stack)
            continue
        c, e = CUT.match(line), END.match(line)
        if c:
            parts = c.group(1).split(",")
            bad = [p for p in parts if p not in PARTS]
            if bad:
                raise SystemExit("%s:%d: no such part %s" % (name, n, bad))
            stack.append((parts, all(p in out for p in parts), n))
            continue
        if e:
            if not stack or e.group(1).split(",") != stack[-1][0]:
                raise SystemExit("%s:%d: hub:end %s doesn't close the hub:cut before it" % (name, n, e.group(1)))
            stack.pop()
            continue
        if any(gone for _, gone, _ in stack):
            continue
        o = ONLY.match(line)
        keep.append(o.group(1) + o.group(2) if o else line)
    if stack:
        raise SystemExit("%s:%d: a hub:cut never ended" % (name, stack[-1][2]))
    # (what a cut region leaves behind: two blank lines or more, one)
    tidy = []
    for line in keep:
        if line.strip() == "" and len(tidy) >= 1 and tidy[-1].strip() == "":
            continue
        tidy.append(line)
    return "\n".join(tidy)


def gone_files(out):
    return {f for p in out for f in PART_FILES.get(p, [])}


def write(dest, out):
    gone = gone_files(out)
    for root, dirs, files in os.walk(HERE):
        rel_root = os.path.relpath(root, HERE)
        dirs[:] = [d for d in dirs if d not in SKIP and os.path.normpath(os.path.join(rel_root, d)).replace(os.sep, "/") not in gone]
        for f in files:
            rel = os.path.normpath(os.path.join(rel_root, f)).replace(os.sep, "/")
            if f in SKIP or rel in gone:
                continue
            src = os.path.join(HERE, rel)
            dst = os.path.join(dest, HUB_FILES.get(rel, rel))
            os.makedirs(os.path.dirname(dst), exist_ok=True)
            if f.endswith(TEXT):
                with open(src, encoding="utf-8") as fh:
                    text = cut_text(fh.read(), out, rel)
                with open(dst, "w", encoding="utf-8", newline="") as fh:
                    fh.write(text)
            else:
                shutil.copy2(src, dst)


def clear(dest):
    for f in os.listdir(dest):
        if f in (".git", "build", ".gradle"):
            continue
        p = os.path.join(dest, f)
        shutil.rmtree(p) if os.path.isdir(p) else os.remove(p)


def same(a, b):
    c = filecmp.dircmp(a, b, ignore=[".git", "build", ".gradle"])
    if c.left_only or c.right_only or c.diff_files or c.funny_files:
        return False
    return all(same(os.path.join(a, d), os.path.join(b, d)) for d in c.common_dirs)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("dest", nargs="?")
    ap.add_argument("--check", action="store_true")
    ap.add_argument("--list", action="store_true")
    a = ap.parse_args()
    out = out_parts()
    if a.list or not a.dest:
        print("submitted beyond the first: %s" % (", ".join(SUBMITTED) or "none"))
        print("left out: %s" % (", ".join(out) or "none"))
        for p in PARTS:
            print("  %-6s %s" % (p, ", ".join(os.path.basename(f) for f in PART_FILES.get(p, [])) or "(regions only)"))
        return
    if a.check:
        with tempfile.TemporaryDirectory() as t:
            write(t, out)
            ok = same(t, a.dest)
        print("hub_cut: %s" % ("matches" if ok else "differs from the cut (run it without --check)"))
        sys.exit(0 if ok else 1)
    os.makedirs(a.dest, exist_ok=True)
    clear(a.dest)
    write(a.dest, out)
    print("hub_cut: written to %s, leaving out %s" % (a.dest, ", ".join(out) or "nothing"))


if __name__ == "__main__":
    main()
