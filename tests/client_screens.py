"""Pictures of the client's screens, for looking at: the window with chat and a poll, the settings drawer, the dink
drawer, and the attach screen. Against relay-py on a spare port; needs RuneLite open for the sample.

    py tests/client_screens.py      (the pictures go to a temporary folder, printed at the end)
"""

import asyncio
import json
import os
import subprocess
import sys
import tempfile
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(os.path.dirname(HERE), "relay-py"))
import client_live as cl  # noqa: E402
import protocol_test as pt  # noqa: E402
from wsmini import Client  # noqa: E402


async def shot(folder, settings, name, extra=(), during=None, wait=4.0):
    proc = subprocess.Popen([cl.EXE, "--settings", settings] + list(extra))
    try:
        await asyncio.sleep(wait)
        if during:
            await during()
            await asyncio.sleep(1.5)
        ok = cl.picture("the portal ✼ chat", os.path.join(folder, name + ".png"))
        print(name, "ok" if ok else "no window")
    finally:
        proc.terminate()
        proc.wait()
        await asyncio.sleep(0.5)


async def main():
    pt.setUpModule()
    folder = tempfile.mkdtemp(prefix="portal-screens-")
    settings = os.path.join(folder, "settings.json")
    key = "screens-key-" + os.urandom(3).hex()
    with open(settings, "w", encoding="utf-8") as f:
        json.dump({"relay": pt.RELAY, "channel": "screens", "key": key, "fps": 8, "chat_name": "sburb"}, f)
    try:
        v = await Client().open("%s/watch/screens" % pt.RELAY)
        await v.send(json.dumps({"hello": {"name": "moss", "device": "device-screens-1"}}))

        async def talk():
            d = await Client().open("%s/desk/screens?key=%s&name=sburb" % (pt.RELAY, key))
            await v.send(json.dumps({"say": "where to next?"}))
            await asyncio.sleep(0.2)
            await d.send(json.dumps({"say": "you decide"}))
            await d.send(json.dumps({"poll": {"question": "next boss", "options": ["vorkath", "zulrah", "the nightmare"], "seconds": 120}}))
            await asyncio.sleep(0.3)
            await v.send(json.dumps({"vote": {"poll": 1, "pick": 1}}))
            await v.send(json.dumps({"ping": {"x": 0.4, "y": 0.6}}))
        await shot(folder, settings, "window", during=talk)
        await shot(folder, settings, "settings", ["--show-settings"], wait=6.0)
        await shot(folder, settings, "dink", ["--show-dink"])
        empty = os.path.join(folder, "empty.json")
        with open(empty, "w") as f:
            f.write("{}")
        await shot(folder, empty, "attach")
        v.close()
    finally:
        pt.tearDownModule()
    print(folder)


if __name__ == "__main__":
    asyncio.run(main())
