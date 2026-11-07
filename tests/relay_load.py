"""The relay's own CPU under a stream as heavy as the shared relay allows, for before/after numbers (11/07/2026).

    py tests/relay_load.py                       # relay-py/relay.py, started on a spare port
    PORTAL_RELAY=worker py tests/relay_load.py   # the Worker under `wrangler dev` (workerd on this PC)
        [--seconds 20] [--fps 60] [--kb 35] [--viewers 20] [--lookers 2]

One transmitter (frames at --fps, a small "TH" copy once a second, {"state"} every 5 s, as the client sends them since
v0.1.2), one sound socket (both busses, five 20 ms packets a message: 20 messages a second, and {"sound"} every 5 s), --viewers viewers that
acknowledge what they got about once a second as the page does ({"got"}), --lookers lookers asking for frames, and a
desk. It prints the CPU the relay's processes used (user + system, from /proc: Linux only) per second and per message
that came in, and what each viewer got. The load itself runs in this process, so a busy machine slows it, not the
relay's figure.
"""

import argparse
import asyncio
import json
import os
import statistics
import struct
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(os.path.dirname(HERE), "relay-py"))
import protocol_test as pt  # noqa: E402  (its relay starting and stopping)
from wsmini import Client  # noqa: E402

TICK = os.sysconf("SC_CLK_TCK")


def tree(pid):
    """pid and every process under it."""
    kids = {}
    for d in os.listdir("/proc"):
        if d.isdigit():
            try:
                with open("/proc/%s/stat" % d) as f:
                    parts = f.read().rsplit(")", 1)[1].split()
                kids.setdefault(int(parts[1]), []).append(int(d))
            except OSError:
                pass
    out, todo = [], [pid]
    while todo:
        p = todo.pop()
        out.append(p)
        todo += kids.get(p, [])
    return out


def cpu(pids):
    """{pid: (name, seconds of CPU so far)}."""
    got = {}
    for p in pids:
        try:
            with open("/proc/%d/stat" % p) as f:
                s = f.read()
            name = s[s.index("(") + 1:s.rindex(")")]
            parts = s.rsplit(")", 1)[1].split()
            got[p] = (name, (int(parts[11]) + int(parts[12])) / TICK, int(parts[11]) / TICK)
        except OSError:
            pass
    return got


async def viewer(url, stats, stop):
    c = await Client().open(url)
    frames = sound = got = 0
    told = time.monotonic()
    try:
        while not stop.is_set():
            try:
                kind, payload = await c.recv(timeout=0.5)
            except asyncio.TimeoutError:
                continue
            if kind == "binary":
                got += len(payload)
                if payload[4:6] == b"AU":
                    sound += 1
                else:
                    frames += 1
                if time.monotonic() - told >= 1.0:       # (as the page does: outside-portal.js, connect)
                    await c.send('{"got": %d}' % got)
                    told = time.monotonic()
            elif kind == "close":
                break
    finally:
        stats.append((frames, sound))
        c.close()


async def looker(url, stats, stop):
    c = await Client().open(url)
    await c.send(json.dumps({"look": {"frames": True}}))
    n = 0
    try:
        while not stop.is_set():
            try:
                kind, _ = await c.recv(timeout=0.5)
            except asyncio.TimeoutError:
                continue
            if kind == "binary":
                n += 1
    finally:
        stats.append(n)
        c.close()


async def drain(c, stop):
    while not stop.is_set():
        try:
            await c.recv(timeout=0.5)
        except asyncio.TimeoutError:
            continue
        except Exception:
            return


async def run(a):
    base, ch = pt.RELAY, "load" + os.urandom(4).hex()
    if pt.WORKER:
        status, got = await asyncio.to_thread(pt.admin, "POST", "/admin/channels/" + ch)
        key = got["key"]
    else:
        key = "load-key-" + os.urandom(4).hex()
    stop = asyncio.Event()
    tx = await Client().open("%s/transmit/%s?key=%s" % (base, ch, key))
    snd = await Client().open("%s/sound/%s?key=%s" % (base, ch, key))
    desk = await Client().open("%s/desk/%s?key=%s&name=%s" % (base, ch, key, ch))
    vstats, lstats = [], []
    tasks = [asyncio.ensure_future(drain(c, stop)) for c in (tx, snd, desk)]
    tasks += [asyncio.ensure_future(viewer("%s/watch/%s" % (base, ch), vstats, stop)) for _ in range(a.viewers)]
    tasks += [asyncio.ensure_future(looker("%s/watch/%s?look=1" % (base, ch), lstats, stop)) for _ in range(a.lookers)]
    await tx.send('{"state": "open"}')
    await snd.send('{"sound": {"mode": "all", "buses": [1, 2]}}')
    await asyncio.sleep(1.5)
    body = b"\xff\xd8" + os.urandom(a.kb * 1024 - 2)
    thumb = b"TH\xff\xd8" + os.urandom(5 * 1024)
    packets = b"".join(struct.pack(">H", 160) + os.urandom(160) for _ in range(5))
    pids = tree(pt.PROC.pid)
    before = cpu(pids)
    sent = {"frames": 0, "thumbs": 0, "state": 0, "sound": 0, "got": 0}
    t0 = time.monotonic()
    next_thumb = next_state = next_sound = t0
    while time.monotonic() - t0 < a.seconds:
        due = t0 + sent["frames"] / a.fps
        now = time.monotonic()
        if now >= next_sound:
            for bus in (1, 2):
                await snd.send(struct.pack(">I", int(now * 1000) & 0xffffffff) + b"AU" + bytes([bus, 5]) + packets)
                sent["sound"] += 1
            next_sound += 0.1
        if now >= next_thumb:
            await tx.send(struct.pack(">I", int(now * 1000) & 0xffffffff) + thumb)
            sent["thumbs"] += 1
            next_thumb += 1.0
        if now >= next_state:                    # (the state line and the sound's line: on a change and every 5 s)
            await tx.send('{"state": "open"}')
            await snd.send('{"sound": {"mode": "all", "buses": [1, 2]}}')
            sent["state"] += 2
            next_state += 5.0
        if now >= due:
            await tx.send(struct.pack(">I", int(now * 1000) & 0xffffffff) + body)
            sent["frames"] += 1
        else:
            await asyncio.sleep(max(0.0, min(due, next_sound, next_thumb, next_state) - now))
    took = time.monotonic() - t0
    await asyncio.sleep(1.0)
    after = cpu(pids)
    stop.set()
    await asyncio.gather(*tasks, return_exceptions=True)
    for c in (tx, snd, desk):
        c.close()
    incoming = sum(sent.values()) + int(a.viewers * took)          # (and each viewer's {"got"} about once a second)
    used = {p: after[p][1] - before[p][1] for p in after if p in before}
    user = {p: after[p][2] - before[p][2] for p in after if p in before}
    print("%s, %.1f s: %d frames (%d KB) at %d a second, %d small, %d sound messages, %d state and sound lines; %d viewers, %d lookers"
          % (pt.MODE, took, sent["frames"], a.kb, a.fps, sent["thumbs"], sent["sound"], sent["state"], a.viewers, a.lookers))
    for p, s in sorted(used.items(), key=lambda x: -x[1]):
        if s > 0.005:
            print("  %-16s pid %-7d %6.2f s CPU  (%4.1f%% of a core; %.2f s user, %.2f s system)"
                  % (after[p][0], p, s, 100 * s / (took + 1.0), user[p], s - user[p]))
    total = sum(used.values())
    print("  in all %.2f s CPU: %.1f ms a second, %.3f ms a message in (about %d came in)"
          % (total, 1000 * total / (took + 1.0), 1000 * total / max(1, incoming), incoming))
    frames = sorted(f for f, _ in vstats)
    sounds = sorted(s for _, s in vstats)
    print("  each viewer got %d / %d / %d frames (least / median / most) and %d / %d sound; lookers %s"
          % (frames[0], statistics.median(frames), frames[-1], sounds[0], sounds[-1], sorted(lstats)))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--seconds", type=float, default=20)
    ap.add_argument("--fps", type=int, default=60)
    ap.add_argument("--kb", type=int, default=35)
    ap.add_argument("--viewers", type=int, default=20)
    ap.add_argument("--lookers", type=int, default=2)
    a = ap.parse_args()
    if pt.MODE.startswith("ws"):
        sys.exit("start the relay here (PORTAL_RELAY=worker or unset): its CPU is read from its process")
    pt.setUpModule()
    try:
        asyncio.run(run(a))
    finally:
        pt.tearDownModule()


if __name__ == "__main__":
    main()
