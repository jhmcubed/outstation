"""fanout_bench.py against this PC's `wrangler dev`, start to finish: the Worker started on a spare port, a
channel handed out, the bench run for each viewer count, workerd's memory read while it runs, everything stopped.

    py tests/fanout_local.py [--viewers 10,30] [--stalled 1] [--seconds 10]
"""

import argparse
import json
import os
import secrets
import shutil
import subprocess
import sys
import tempfile
import threading
import time
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import protocol_test as pt  # noqa: E402  (its free port, its start and stop)


def workerd_mb():
    out = subprocess.run(["tasklist", "/FI", "IMAGENAME eq workerd.exe", "/FO", "CSV", "/NH"],
                         capture_output=True, text=True).stdout
    return sum(int(line.split('","')[4].rstrip('"\n').replace(" K", "").replace(",", "")) // 1024
               for line in out.splitlines() if line.startswith('"workerd'))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--viewers", default="10,30")
    ap.add_argument("--stalled", type=int, default=1)
    ap.add_argument("--seconds", type=float, default=10)
    a = ap.parse_args()
    pt.MODE = "worker"
    pt.setUpModule()
    try:
        name = "bench" + secrets.token_hex(3)
        req = urllib.request.Request(pt.http_url() + "/admin/channels/" + name, method="POST",
                                     headers={"Authorization": "Bearer " + pt.ADMIN})
        key = json.load(urllib.request.urlopen(req, data=b""))["key"]
        for n in [int(x) for x in a.viewers.split(",")]:
            peak = [workerd_mb()]
            before = peak[0]
            done = threading.Event()

            def sample():
                while not done.wait(1.0):
                    peak.append(workerd_mb())
            t = threading.Thread(target=sample, daemon=True)
            t.start()
            r = subprocess.run([sys.executable, os.path.join(HERE, "fanout_bench.py"), "--relay", pt.RELAY,
                                "--channel", name, "--key", key, "--viewers", str(n), "--stalled", str(a.stalled),
                                "--seconds", str(a.seconds)], capture_output=True, text=True, timeout=a.seconds + 60)
            done.set()
            print(r.stdout.strip() or r.stderr.strip()[-500:])
            print("  workerd: %d MB before, %d MB at most while it ran" % (before, max(peak)))
    finally:
        pt.tearDownModule()


if __name__ == "__main__":
    main()
