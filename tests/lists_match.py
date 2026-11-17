"""The closed lists the same in the plugin and the relay (11/08/2026; from the Python relay's own tests when it was
retired, 11/17/2026): the skills, the bosses' labels, the raids, the minigames and the shooting stars' landing sites,
read from each one's source, so a boss added to one and not the other fails.

    py tests/lists_match.py
"""
import json
import os
import re
import unittest

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
JAVA = ("plugin", "src", "main", "java", "wiki", "outside")


def source(*path):
    with open(os.path.join(ROOT, *path), encoding="utf-8") as f:
        return f.read()


def ts_list(text, name):
    body = re.search(r"export const %s = \[(.*?)\];" % name, text, re.S).group(1)
    return re.findall(r'"([^"]+)"', body)


class Lists(unittest.TestCase):
    def setUp(self):
        self.game = source("relay", "src", "game.ts")
        self.java = source(*JAVA, "Activity.java")

    def test_the_skills_and_bosses(self):
        skills, bosses = ts_list(self.game, "ACTIVITY_SKILLS"), ts_list(self.game, "ACTIVITY_BOSSES")
        js = re.search(r"SKILLS = .*?asList\((.*?)\)\)\);", self.java, re.S).group(1)
        self.assertEqual(sorted(re.findall(r'"([^"]+)"', js)), sorted(skills))
        block = re.search(r"static\s*\{(.*?)BOSSES = ", self.java, re.S).group(1)
        labels = set()
        for names in re.findall(r"new String\[\]\{(.*?)\}\)\s*\{\s*b\.put\(n, n\);", block, re.S):
            labels.update(re.findall(r'"([^"]+)"', names))
        labels.update(re.findall(r'b\.put\(n, "([^"]+)"\)', block))
        labels.update(re.findall(r'b\.put\("[^"]+", "([^"]+)"\)', block))
        places = re.search(r"static final Map<Integer, String> BOSS_REGIONS = regions\((.*?)\);", self.java, re.S).group(1)
        labels.update(re.findall(r'"([^"]+)", new int', places))                    # (the Gauntlets, by where)
        self.assertEqual(sorted(labels), sorted(bosses))
        self.assertEqual(sorted(bosses), bosses)                                    # (sorted, as the docs list them)
        wild = re.search(r"WILDERNESS_BOSSES = .*?asList\((.*?)\)\)\);", self.java, re.S).group(1)
        self.assertEqual(len(re.findall(r'"([^"]+)"', wild)), 10)
        self.assertLessEqual(set(re.findall(r'"([^"]+)"', wild)), set(bosses))

    def test_the_raids_and_minigames(self):
        raids, minigames = ts_list(self.game, "ACTIVITY_RAIDS"), ts_list(self.game, "ACTIVITY_MINIGAMES")
        trials = re.search(r'static final String TRIALS = "([^"]+)";', self.java).group(1)  # (by the game's flag)
        self.assertEqual(trials, "Barracuda Trials")
        for name, kept, more in (("RAIDS", raids, []), ("MINIGAMES", minigames, [trials])):
            body = re.search(r"static final Map<Integer, String> %s = regions\((.*?)\);" % name, self.java, re.S).group(1)
            self.assertEqual(sorted(re.findall(r'"([^"]+)", new int', body) + more), sorted(kept))
        self.assertEqual(sorted(minigames), minigames)

    def test_the_landing_sites(self):
        body = re.search(r"STAR_SITES[^=]*= \{(.*?)\};", self.game, re.S).group(1)
        ts = {json.loads(n): (int(x), int(y)) for n, x, y in re.findall(r'^  ("[^"]+"): \[(\d+), (\d+)\],$', body, re.M)}
        java = {n: (int(x) // 128 * 128 + 64, int(y) // 128 * 128 + 64)
                for x, y, n in re.findall(r'site\((\d+), (\d+), "([^"]+)"\);', source(*JAVA, "Sites.java"))}
        self.assertEqual(java, ts)
        self.assertEqual(len(ts), 71)


if __name__ == "__main__":
    unittest.main()
