"""Build and run the Outside plugin without Gradle (plugin/README.md).

The Plugin Hub builds it with Gradle (build.gradle, as RuneLite's example-plugin template). This compiles the same
sources with the JDK's javac against the RuneLite installed on this PC (~/.runelite/repository2, the launcher's copy),
plus the RuneLite API jar with the game's named constants, fetched once from RuneLite's own Maven repository into
build/lib (the installed copy has them compiled out). As ghillie's tools/portal/build.py does for the portal's.

    py plugin/run.py            compile (main and test)
    py plugin/run.py client     RuneLite with Outside loaded (developer mode)

A Jagex account needs the launcher's --insecure-write-credentials step once before `client` can log in (RuneLite's
wiki, "Using Jagex Accounts").
"""

import glob
import os
import re
import shutil
import subprocess
import sys
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
BUILD = os.path.join(HERE, "build", "javac")
LIB = os.path.join(HERE, "build", "lib")
REPO2 = os.path.join(os.path.expanduser("~"), ".runelite", "repository2")
MAVEN = "https://repo.runelite.net/net/runelite/runelite-api/{v}/runelite-api-{v}.jar"
SEP = os.pathsep


def vkey(v):
    return [int(x) if x.isdigit() else 0 for x in re.split(r"[.\-]", v)]


def installed():
    """The installed RuneLite's jars, the newest version of each, and that version."""
    jars = glob.glob(os.path.join(REPO2, "*.jar"))
    if not jars:
        sys.exit("no RuneLite in %s: install and run RuneLite once" % REPO2)
    newest = {}
    for j in jars:
        m = re.match(r"(.+?)-(\d[\w.\-]*?)(-runtime|-natives-\w+)?\.jar$", os.path.basename(j))
        name, ver, extra = (m.group(1), m.group(2), m.group(3) or "") if m else (os.path.basename(j), "0", "")
        k = name + extra
        if k not in newest or vkey(ver) > vkey(newest[k][0]):
            newest[k] = (ver, j)
    client = newest.get("client")
    if not client:
        sys.exit("no client jar in %s" % REPO2)
    return [j for _, j in newest.values()], client[0]


def api_jar(version):
    os.makedirs(LIB, exist_ok=True)
    path = os.path.join(LIB, "runelite-api-%s.jar" % version)
    if not os.path.exists(path):
        url = MAVEN.format(v=version)
        print("fetching", url)
        with urllib.request.urlopen(url, timeout=60) as r, open(path + ".part", "wb") as f:
            shutil.copyfileobj(r, f)
        os.replace(path + ".part", path)
    return path


def compile_all():
    jars, version = installed()
    # (the full API, with the gameval constants, ahead of the installed runtime copy)
    cp = [api_jar(version)] + jars
    sources = glob.glob(os.path.join(HERE, "src", "**", "*.java"), recursive=True)
    if os.path.isdir(BUILD):
        shutil.rmtree(BUILD)
    os.makedirs(BUILD)
    args = os.path.join(HERE, "build", "javac-args.txt")
    with open(args, "w", encoding="utf-8") as f:
        f.write("\n".join('"%s"' % s.replace("\\", "/") for s in sources))
    cmd = ["javac", "--release", "11", "-encoding", "UTF-8", "-Xlint:deprecation,unchecked,-options",
           "-proc:none", "-d", BUILD, "-cp", SEP.join(cp), "@" + args]
    if subprocess.run(cmd).returncode:
        sys.exit(1)
    print("compiled %d sources against RuneLite %s" % (len(sources), version))
    return cp


def main(argv):
    what = argv[0] if argv else "compile"
    cp = compile_all()
    if what == "compile":
        return 0
    if what == "client":
        return subprocess.run(["java", "-ea", "-cp", SEP.join([BUILD] + cp), "wiki.outside.OutsidePluginTest",
                               "--developer-mode", "--debug", *argv[1:]]).returncode
    sys.exit(__doc__)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
