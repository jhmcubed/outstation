"""A small WebSocket client, standard library only (capture.py and the relay's tests).

    c = await Client().open("ws://127.0.0.1:8786/transmit/sburb?key=...")
    await c.send(b"...") / await c.send("text")
    kind, payload = await c.recv()      # 'text', 'binary' or 'close' (payload = the close code; c.reason its words)

wss:// works too (the real relay on Cloudflare), through an HTTPS proxy when HTTPS_PROXY says there is one (as in a
cloud session; never on Jace's PC, where it isn't set).
"""

import asyncio
import base64
import os
import ssl
import struct
from urllib.parse import urlsplit


def _proxy_for(host):
    """(host, port) of the HTTPS proxy to reach `host` through, or None (no proxy, or the host is in NO_PROXY)."""
    proxy = os.environ.get("HTTPS_PROXY") or os.environ.get("https_proxy")
    if not proxy:
        return None
    skip = [x.strip() for x in (os.environ.get("NO_PROXY") or os.environ.get("no_proxy") or "").split(",") if x.strip()]
    if any(host == x or host.endswith("." + x.lstrip(".")) for x in skip):
        return None
    u = urlsplit(proxy if "://" in proxy else "http://" + proxy)
    return u.hostname, u.port or 80


class Client:
    """Masks what it sends, as a browser or OkHttp does."""

    status = None
    reason = ""     # the words the other end closed with

    async def open(self, url_or_port, path=None):
        """open("ws://host:port/path") or, for the tests, open(port, "/path") on 127.0.0.1."""
        if path is not None:
            host, port, target, secure = "127.0.0.1", int(url_or_port), path, False
        else:
            u = urlsplit(url_or_port)
            secure = u.scheme == "wss"
            host = u.hostname
            port = u.port or (443 if secure else 80)
            target = (u.path or "/") + ("?" + u.query if u.query else "")
        ctx = ssl.create_default_context() if secure else None
        via = _proxy_for(host) if secure else None
        if via:
            # (through the proxy: a CONNECT tunnel, then TLS to the host inside it)
            self.r, self.w = await asyncio.open_connection(*via)
            self.w.write(("CONNECT %s:%d HTTP/1.1\r\nHost: %s:%d\r\n\r\n" % (host, port, host, port)).encode())
            await self.w.drain()
            answer = await self.r.readuntil(b"\r\n\r\n")
            if b" 200" not in answer.split(b"\r\n", 1)[0]:
                raise ConnectionError("the proxy wouldn't connect: %s" % answer.split(b"\r\n", 1)[0].decode("latin-1"))
            await self.w.start_tls(ctx, server_hostname=host)
        else:
            self.r, self.w = await asyncio.open_connection(host, port, ssl=ctx, server_hostname=host if secure else None)
        key = base64.b64encode(os.urandom(16)).decode()
        hostline = host if port in (80, 443) else "%s:%d" % (host, port)
        self.w.write(("GET %s HTTP/1.1\r\nHost: %s\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                      "Sec-WebSocket-Key: %s\r\nSec-WebSocket-Version: 13\r\n\r\n" % (target, hostline, key)).encode())
        await self.w.drain()
        head = await self.r.readuntil(b"\r\n\r\n")
        self.status = int(head.split(b" ")[1])
        return self

    async def send(self, msg):
        data = msg.encode() if isinstance(msg, str) else bytes(msg)
        op = 1 if isinstance(msg, str) else 2
        mask = os.urandom(4)
        n = len(data)
        head = struct.pack(">BB", 0x80 | op, 0x80 | n) if n < 126 else (
            struct.pack(">BBH", 0x80 | op, 0x80 | 126, n) if n < 65536 else
            struct.pack(">BBQ", 0x80 | op, 0x80 | 127, n))
        # masking a frame byte by byte is slow in Python; do it a word at a time
        m = int.from_bytes(mask * ((n + 3) // 4), "little")
        masked = (int.from_bytes(data, "little") ^ m).to_bytes(len(mask) * ((n + 3) // 4), "little")[:n] if n else b""
        self.w.write(head + mask + masked)
        await self.w.drain()

    async def recv(self, timeout=2.0):
        head = await asyncio.wait_for(self.r.readexactly(2), timeout)
        op, n = head[0] & 0x0F, head[1] & 0x7F
        if n == 126:
            n = struct.unpack(">H", await self.r.readexactly(2))[0]
        elif n == 127:
            n = struct.unpack(">Q", await self.r.readexactly(8))[0]
        data = await self.r.readexactly(n)
        if op == 8:
            self.reason = data[2:].decode("utf-8", "replace")
            return "close", struct.unpack(">H", data[:2])[0] if len(data) >= 2 else None
        if op == 9:
            self.w.write(struct.pack(">BB", 0x8A, 0x80) + os.urandom(4))
            return await self.recv(timeout)
        return ("text", data.decode()) if op == 1 else ("binary", data)

    async def recv_data(self, timeout=2.0):
        """The next message that isn't the relay's own status line."""
        while True:
            kind, payload = await self.recv(timeout)
            if kind == "text" and payload.startswith('{"relay"'):
                continue
            return kind, payload

    async def recv_close(self, timeout=2.0):
        """Skip to the close frame: ('close', code)."""
        while True:
            kind, payload = await self.recv(timeout)
            if kind == "close":
                return kind, payload

    def close(self):
        self.w.close()
