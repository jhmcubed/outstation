"""The Plugin Hub's rules, checked against this plugin's source before it's submitted (plugin/README.md, "For the
Hub's review"). From RuneLite's own pages, read 11/07/2026: the plugin-hub README and the wiki's "Rejected or Rolled
Back Features" (forbidden language features, functions and patterns; sensitive and restricted APIs).

    py plugin/hub_check.py      every rule, and whether the main source keeps it (tests are left out: never shipped)
"""

import glob
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))

# (what's forbidden, the pattern that finds it in the source)
FORBIDDEN = [
    ("Java reflection", r"java\.lang\.reflect|\.getDeclared(Field|Method|Constructor)|Class\.forName|\.setAccessible\("),
    ("JNI / JNA", r"\bnative\s+\w+\s*\(|com\.sun\.jna|System\.loadLibrary"),
    ("running other programs", r"ProcessBuilder|Runtime\.getRuntime|java\.lang\.Runtime|\.exec\("),
    ("downloading code at runtime", r"URLClassLoader|defineClass|ScriptEngine"),
    ("Desktop.open / Desktop.browse / LinkBrowser.open", r"Desktop\.(open|browse)|LinkBrowser\.open"),
    ("Thread.interrupt / Thread.sleep", r"Thread\.(sleep|interrupt)|\.interrupt\(\)"),
    ("Client.menuAction", r"\.menuAction\("),
    ("java.io file APIs", r"java\.io\.(File|FileInputStream|FileOutputStream|FileReader|FileWriter|RandomAccessFile)\b|new\s+File\("),
    ("java.awt.KeyboardFocusManager", r"KeyboardFocusManager"),
    ("a network client other than OkHttp", r"HttpURLConnection|java\.net\.http|new\s+Socket\(|java\.net\.URL\b"),
    ("its own Gson", r"new\s+Gson\s*\(|new\s+GsonBuilder\s*\("),
    ("its own OkHttp", r"new\s+OkHttpClient\s*\("),
    ("net.runelite as its package", r"^\s*package\s+net\.runelite"),
    ("a JVM language other than Java", None),
]
# allowed, but reviewed more closely (said, not failed)
SENSITIVE = [
    ("network IO (the relay's socket, through RuneLite's own OkHttp)", r"newWebSocket|\.newCall\("),
    ("Client.runScript", r"\.runScript\("),
]
RESTRICTED = [
    ("Client.hopToWorld", r"\.hopToWorld\("),
    ("Filepath.Unchecked", r"Filepath\.Unchecked"),
    ("ScriptEventBuilder.setCanSendPackets", r"setCanSendPackets"),
]


def main():
    main_src = glob.glob(os.path.join(HERE, "src", "main", "**", "*"), recursive=True)
    files = [p for p in main_src if os.path.isfile(p)]
    java = [p for p in files if p.endswith(".java")]
    other = [p for p in files if re.search(r"\.(kt|kts|scala|groovy|clj)$", p)]
    text = {p: open(p, encoding="utf-8").read() for p in java}
    bad = 0
    for what, pat in FORBIDDEN:
        if pat is None:
            hits = [os.path.relpath(p, HERE) for p in other]
        else:
            hits = ["%s:%d" % (os.path.relpath(p, HERE), t[:m.start()].count("\n") + 1)
                    for p, t in text.items() for m in re.finditer(pat, t, re.M)]
        print(("✗ " if hits else "✓ ") + what + (": " + ", ".join(hits) if hits else ""))
        bad += bool(hits)
    for title, rules in (("sensitive (allowed, reviewed closely)", SENSITIVE), ("restricted (a manual review)", RESTRICTED)):
        for what, pat in rules:
            hits = ["%s:%d" % (os.path.relpath(p, HERE), t[:m.start()].count("\n") + 1)
                    for p, t in text.items() for m in re.finditer(pat, t, re.M)]
            if hits:
                print("· %s, %s: %s" % (title, what, ", ".join(hits)))
                bad += title.startswith("restricted")
    # the template's files and the third-party warning
    props = open(os.path.join(HERE, "runelite-plugin.properties"), encoding="utf-8").read()
    for k in ("displayName", "author", "description", "tags", "plugins"):
        ok = re.search(r"^%s=.+" % k, props, re.M)
        print(("✓ " if ok else "✗ ") + "runelite-plugin.properties: " + k)
        bad += not ok
    for f, why in (("LICENSE", "a licence (BSD 2-Clause, as the Hub asks)"), ("icon.png", "an icon (48 x 72 at most)"),
                   ("README.md", "a README")):
        ok = os.path.exists(os.path.join(HERE, f))
        print(("✓ " if ok else "✗ ") + why)
        bad += not ok
    cfg = text.get(os.path.join(HERE, "src", "main", "java", "wiki", "outside", "OutsideConfig.java"), "")
    ok = "warning =" in cfg
    print(("✓ " if ok else "✗ ") + "a warning on the setting that sends to a third-party server")
    bad += not ok
    print("passed" if not bad else "%d to fix" % bad)
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
