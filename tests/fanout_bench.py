"""How well a relay fans one transmitter out to many viewers: frames sent and frames each viewer got.

    py tests/fanout_bench.py --relay ws://127.0.0.1:8790 --key <the channel's key> --channel <it>
        [--viewers 10] [--fps 30] [--kb 67] [--seconds 10] [--stalled 1]

A stalled viewer connects and never reads, as a phone on a bad connection might: the others should not slow
down for it, and the relay should stop sending it frames (each viewer acknowledges what it got, {"got": bytes}). Prints, per run: frames sent, each viewer's frames (least, median, most) and the bytes a second
the relay sent in all. Run it against `wrangler dev` (this PC's workerd) or a deployed relay.
"""

import argparse
import asyncio
import os
import statistics
import struct
import sys
import time

sys.path.insert(0, os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "relay-py"))
from wsmini import Client  # noqa: E402


async def viewer(url, counts, i, stop):
    c = await Client().open(url)
    n, got, told = 0, 0, time.monotonic()
    try:
        while not stop.is_set():
            try:
                kind, payload = await c.recv(timeout=0.5)
            except asyncio.TimeoutError:
                continue
            if kind == "binary":
                n += 1
                got += len(payload)
                counts[i] = n
                if time.monotonic() - told >= 1.0:          # (flow control: what the page does too)
                    await c.send('{"got": %d}' % got)
                    told = time.monotonic()
            elif kind == "close":
                break
    finally:
        c.close()


async def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--relay", required=True)
    ap.add_argument("--channel", required=True)
    ap.add_argument("--key", required=True)
    ap.add_argument("--viewers", type=int, default=10)
    ap.add_argument("--fps", type=int, default=30)
    ap.add_argument("--kb", type=int, default=67)
    ap.add_argument("--seconds", type=float, default=10)
    ap.add_argument("--stalled", type=int, default=1)
    a = ap.parse_args()
    base = a.relay.rstrip("/")
    tx = await Client().open("%s/transmit/%s?key=%s" % (base, a.channel, a.key))
    stalled = [await Client().open("%s/watch/%s" % (base, a.channel)) for _ in range(a.stalled)]
    counts = [0] * a.viewers
    stop = asyncio.Event()
    tasks = [asyncio.ensure_future(viewer("%s/watch/%s" % (base, a.channel), counts, i, stop))
             for i in range(a.viewers)]
    await asyncio.sleep(1.0)
    body = os.urandom(a.kb * 1024)
    sent, t0 = 0, time.monotonic()
    while time.monotonic() - t0 < a.seconds:
        due = t0 + sent / a.fps
        await asyncio.sleep(max(0, due - time.monotonic()))
        await tx.send(struct.pack(">I", int(time.monotonic() * 1000) & 0xffffffff) + body)
        sent += 1
    took = time.monotonic() - t0
    await asyncio.sleep(1.5)            # (the last frames on their way)
    stop.set()
    await asyncio.gather(*tasks, return_exceptions=True)
    tx.close()
    for s in stalled:
        s.close()
    got = sorted(counts)
    total = sum(got) * (a.kb * 1024 + 4)
    print("%d viewers (+%d stalled), %d a second, %d KB: sent %d in %.1f s; each got %d / %d / %d"
          " (least / median / most); %.0f Mbit/s out of the relay"
          % (a.viewers, a.stalled, a.fps, a.kb, sent, took, got[0], statistics.median(got), got[-1],
             total * 8 / took / 1e6))


if __name__ == "__main__":
    asyncio.run(main())
