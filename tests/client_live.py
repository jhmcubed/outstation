"""The client end to end against a relay: frames, the state line, the status, chat both ways, a ping, a poll, the
sound and Dink, seen as a viewer sees them.

    py tests/client_live.py                    # relay-py on a spare port
    PORTAL_RELAY=worker py tests/client_live.py    # the Worker under wrangler dev

Needs RuneLite open (it's what the client captures), and the client built (target/release/outstation.exe). It
runs the client on a settings file of its own in a temporary folder, so your own portal's settings are never
touched. The client's window opens while it runs; a picture of it is saved as client_live.png beside the settings.
"""

import asyncio
import json
import os
import subprocess
import sys
import tempfile
import time
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(ROOT, "relay-py"))
import protocol_test as pt  # noqa: E402
from wsmini import Client  # noqa: E402

EXE = os.path.join(ROOT, "target", "release", "outstation.exe")


TITLE = "⊂λ,ｍ⊙ ✼ the portal"


def picture(title, path, pid=None):
    """The window's own drawing (PrintWindow), as capture.py takes RuneLite's. pid: only that process's windows (his
    own Python chat window may be open beside it)."""
    try:
        from PIL import Image
    except ImportError:
        return False
    import ctypes
    import ctypes.wintypes as W
    u, g = ctypes.windll.user32, ctypes.windll.gdi32
    # the largest window with this title (the client's see-through overlays can carry it too)
    found = []
    proc = ctypes.WINFUNCTYPE(ctypes.c_bool, ctypes.c_void_p, ctypes.c_void_p)

    def each(hw, _):
        n = u.GetWindowTextLengthW(ctypes.c_void_p(hw))
        buf = ctypes.create_unicode_buffer(n + 1)
        u.GetWindowTextW(ctypes.c_void_p(hw), buf, n + 1)
        owner = ctypes.c_ulong()
        u.GetWindowThreadProcessId(ctypes.c_void_p(hw), ctypes.byref(owner))
        if buf.value == title and (pid is None or owner.value == pid):
            rr = W.RECT()
            u.GetWindowRect(ctypes.c_void_p(hw), ctypes.byref(rr))
            found.append(((rr.right - rr.left) * (rr.bottom - rr.top), hw))
        return True
    u.EnumWindows(proc(each), None)
    if not found:
        return False
    h = max(found)[1]
    r = W.RECT()
    u.GetWindowRect(ctypes.c_void_p(h), ctypes.byref(r))
    w, ht = r.right - r.left, r.bottom - r.top
    u.GetWindowDC.restype = ctypes.c_void_p
    g.CreateCompatibleDC.restype = ctypes.c_void_p
    g.CreateCompatibleBitmap.restype = ctypes.c_void_p
    wdc = u.GetWindowDC(ctypes.c_void_p(h))
    dc = g.CreateCompatibleDC(ctypes.c_void_p(wdc))
    bmp = g.CreateCompatibleBitmap(ctypes.c_void_p(wdc), w, ht)
    g.SelectObject(ctypes.c_void_p(dc), ctypes.c_void_p(bmp))
    u.PrintWindow(ctypes.c_void_p(h), ctypes.c_void_p(dc), 2)
    buf = ctypes.create_string_buffer(w * ht * 4)
    bmi = (ctypes.c_uint32 * 10)(40, w, (-ht) & 0xffffffff, 1 | (32 << 16), 0, 0, 0, 0, 0, 0)
    g.GetDIBits(ctypes.c_void_p(dc), ctypes.c_void_p(bmp), 0, ht, buf, bmi, 0)
    Image.frombuffer("RGB", (w, ht), buf, "raw", "BGRX", 0, 1).save(path)
    return True


def masked(jpeg):
    """The frame's left half black (the mask) and its right half not: (left's brightest, right's mean)."""
    import io
    from PIL import Image
    im = Image.open(io.BytesIO(jpeg)).convert("L")
    w, h = im.size
    left = im.crop((0, 0, w // 2 - 2, h))
    right = im.crop((w // 2 + 2, 0, w, h))
    return [max(left.getextrema()), round(sum(right.getdata()) / (right.size[0] * right.size[1]), 1)]


async def main():
    pt.setUpModule()
    folder = tempfile.mkdtemp(prefix="portal-client-")
    settings = os.path.join(folder, "settings.json")
    channel = "client" + os.urandom(3).hex()
    if pt.WORKER:
        req = urllib.request.Request(pt.http_url() + "/admin/channels/" + channel, method="POST",
                                     headers={"Authorization": "Bearer " + pt.ADMIN})
        key = json.load(urllib.request.urlopen(req, data=b""))["key"]
    else:
        key = "client-key-" + os.urandom(4).hex()
    dink_port = pt.free_port()
    with open(settings, "w", encoding="utf-8") as f:
        json.dump({"relay": pt.RELAY, "channel": channel, "key": key, "fps": 12, "width": 384, "quality": 60,
                   "sound": "all", "pings": True, "dink": {"port": dink_port}, "max_viewers": 5,
                   # your own masks: the left half blacked out (checked in a frame below)
                   "masks": [[0.0, 0.0, 0.5, 1.0]], "masks_on": True}, f)
    proc = subprocess.Popen([EXE, "--settings", settings])
    results = {}
    try:
        v = await Client().open("%s/watch/%s" % (pt.RELAY, channel))
        await v.send(json.dumps({"hello": {"name": "tester", "device": "device-client-test"}}))
        frames, states, sound_info, sound_packets, live, chats, pings = 0, [], None, 0, False, [], []
        t0 = time.monotonic()
        said = pinged = posted = False
        got = 0
        while time.monotonic() - t0 < 14:
            try:
                kind, payload = await v.recv(timeout=0.5)
            except asyncio.TimeoutError:
                kind = None
            if kind == "binary":
                if payload[4:6] == b"AU":
                    sound_packets += 1
                else:
                    frames += 1
                    if frames == 10:
                        results["masked"] = masked(payload[4:])
                got += len(payload)
                await v.send(json.dumps({"got": got}))
            elif kind == "text":
                m = json.loads(payload)
                if "relay" in m:
                    live = live or m["relay"]["live"]
                    results["cap"] = m["relay"].get("max")
                elif "state" in m:
                    states.append(m["state"])
                elif "sound" in m:
                    sound_info = m["sound"]
                elif "chat" in m:
                    chats.append(m["chat"])
                elif "ping" in m:
                    pings.append(m["ping"])
                elif "report" in m:
                    results["report"] = m["report"]
            elif kind == "close":
                break
            if time.monotonic() - t0 > 4 and not said:
                await v.send(json.dumps({"say": "hello from the test"}))
                said = True
            if time.monotonic() - t0 > 5 and not pinged and frames:
                await v.send(json.dumps({"ping": {"x": 0.5, "y": 0.5}}))
                pinged = True
            if time.monotonic() - t0 > 6 and not posted:
                body = json.dumps({"type": "LEVEL", "content": "", "embeds": [{"description": "**x** has levelled **Cooking** to **70**"}]}).encode()
                await asyncio.to_thread(lambda: urllib.request.urlopen(urllib.request.Request(
                    "http://127.0.0.1:%d/dink" % dink_port, data=body, method="POST",
                    headers={"Content-Type": "application/json"}), timeout=5).read())
                posted = True
            if time.monotonic() - t0 > 8 and "picture" not in results:
                results["picture"] = picture(TITLE, os.path.join(folder, "client_live.png"), proc.pid)
        results.update(frames=frames, states=sorted(set(states)), sound=sound_info, sound_packets=sound_packets,
                       live=live, chats=[c["text"] for c in chats], pings=len(pings))
        v.close()
    finally:
        proc.terminate()
        pt.tearDownModule()
    print(json.dumps(results, indent=1))
    print("settings and picture in", folder)
    ok = results["frames"] > 20 and results["live"] and "open" in results["states"] \
        and results["chats"] == ["hello from the test"] and results["pings"] == 1 \
        and results.get("report", {}).get("type") == "LEVEL" and results["sound"] is not None and results["cap"] == 5         and results.get("masked", [255])[0] <= 16 and results["masked"][1] > 20
    print("ok" if ok else "NOT OK")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
