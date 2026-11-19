"""Start this plugin inside RuneLite and say whether it switched on (11/19/2026).

Update 4 went to the Plugin Hub compiling and passing every test, and wouldn't switch on: RuneLite refuses to start a
plugin whose @Subscribe method isn't named "on" and its event's name, and nothing short of starting it inside RuneLite
showed that. This does: RuneLite (the version build.gradle names, as the Hub builds against) with the plugin loaded as
OutsidePluginTest loads it, in developer mode, its home a fresh folder of its own (never your own .runelite: no
settings, keys or logs of yours read or touched), and its log read until the plugin is running or has failed. The game
itself needn't load: plugins start before the login screen.

    python3 plugin/start_check.py              this folder (the whole plugin)
    python3 plugin/start_check.py --cut        the Hub's version, cut into a scratch folder first (hub_cut.py)
    python3 plugin/start_check.py <folder>     another checkout (outside-plugin's, say)

Passes when the log says "Plugin OutsidePlugin is now running" and, in the seconds after, nothing names an error from
the plugin's own code (wiki.outside). On Linux with no screen it runs under xvfb-run. Gradle and Java as for
`gradle test`. Run it on the Hub's version before every commit= the Hub is sent (docs/hub-submission.md).
"""

import argparse
import os
import re
import shutil
import signal
import subprocess
import sys
import tempfile
import time

HERE = os.path.dirname(os.path.abspath(__file__))
RUNNING = "Plugin OutsidePlugin is now running"
FAILED = re.compile(r"Unable to start plugin OutsidePlugin|PluginInstantiationException")
# an error the plugin's own code is in, once it's running (a stack frame or a logger line of wiki.outside)
OURS = re.compile(r"(ERROR|WARN)[^\n]*wiki\.outside|^\s+at wiki\.outside\.(?!OutsidePluginTest)", re.M)

INIT = """
allprojects {
	afterEvaluate {
		if (project.plugins.hasPlugin('java')) {
			tasks.register('outsideStartCheck', JavaExec) {
				classpath = sourceSets.test.runtimeClasspath
				mainClass = 'wiki.outside.OutsidePluginTest'
				args '--developer-mode', '--debug'
				jvmArgs '-ea', '-Duser.home=%s'
			}
		}
	}
}
"""


def kill(p):
    """The whole tree: gradle, and the RuneLite it started."""
    try:
        if os.name == "nt":
            subprocess.run(["taskkill", "/T", "/F", "/PID", str(p.pid)], capture_output=True)
        else:
            os.killpg(os.getpgid(p.pid), signal.SIGTERM)
            time.sleep(2)
            os.killpg(os.getpgid(p.pid), signal.SIGKILL)
    except (ProcessLookupError, OSError):
        pass


def check(folder, wait):
    home = tempfile.mkdtemp(prefix="outside-start-")
    init = os.path.join(home, "init.gradle")
    with open(init, "w") as f:
        f.write(INIT % home.replace("\\", "/"))
    cmd = ["gradle", "-q", "--console=plain", "-I", init, "outsideStartCheck"]
    if os.name != "nt" and sys.platform != "darwin" and not os.environ.get("DISPLAY") and shutil.which("xvfb-run"):
        cmd = ["xvfb-run", "-a", "-s", "-screen 0 1600x1000x24"] + cmd
    out = open(os.path.join(home, "gradle.log"), "w")
    kw = {"start_new_session": True} if os.name != "nt" else {"creationflags": subprocess.CREATE_NEW_PROCESS_GROUP}
    p = subprocess.Popen(cmd, cwd=folder, stdout=out, stderr=subprocess.STDOUT, **kw)
    log = os.path.join(home, ".runelite", "logs", "client.log")
    t0, ran, text = time.time(), None, ""
    try:
        while time.time() - t0 < wait:
            time.sleep(2)
            if os.path.exists(log):
                with open(log, encoding="utf-8", errors="replace") as f:
                    text = f.read()
            if FAILED.search(text):
                break
            if RUNNING in text and ran is None:
                ran = time.time()
            # (a few seconds after it's running: an error its start left for later shows by then)
            if ran is not None and time.time() - ran > 8:
                break
            if p.poll() is not None:
                break
    finally:
        kill(p)
        out.close()
    if os.path.exists(log):
        with open(log, encoding="utf-8", errors="replace") as f:
            text = f.read()
    lines = text.splitlines()
    bad = [i for i, l in enumerate(lines) if FAILED.search(l) or OURS.search(l)]
    if RUNNING in text and not bad:
        print("start_check: passed: RuneLite started the plugin (%s), and nothing of its own failed after" % RUNNING)
        shutil.rmtree(home, ignore_errors=True)
        return 0
    if bad:
        print("start_check: FAILED: RuneLite couldn't start the plugin, or it failed once running:")
        for i in bad[:3]:
            for l in lines[max(0, i - 1):i + 6]:
                print("    " + l[:300])
            print("    ...")
    elif not text:
        with open(os.path.join(home, "gradle.log"), encoding="utf-8", errors="replace") as f:
            tail = f.read()[-3000:]
        print("start_check: FAILED: RuneLite never wrote its log (did it build? gradle said:)\n" + tail)
    else:
        print("start_check: FAILED: no \"%s\" in %d s" % (RUNNING, wait))
    print("start_check: RuneLite's home for this run, its log in .runelite/logs: " + home)
    return 1


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("folder", nargs="?", default=HERE, help="the plugin's folder (default: this one)")
    ap.add_argument("--cut", action="store_true", help="check the Hub's version, cut first into a scratch folder")
    ap.add_argument("--wait", type=int, default=240, help="seconds to wait for it to start (default 240)")
    a = ap.parse_args()
    folder = os.path.abspath(a.folder)
    if not a.cut:
        return check(folder, a.wait)
    folder = tempfile.mkdtemp(prefix="outside-cut-")
    try:
        subprocess.run([sys.executable, os.path.join(HERE, "hub_cut.py"), folder], check=True)
        return check(folder, a.wait)
    finally:
        shutil.rmtree(folder, ignore_errors=True)


if __name__ == "__main__":
    sys.exit(main())
