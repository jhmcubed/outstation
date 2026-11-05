"""clip.py: the replay buffer saved as a clip (11/05/2026, Jace: "add a scalable replay buffer in the settings, 0 to 30").

The relay keeps the last `replay_seconds` (settings.json, 0 to 30; 0 keeps nothing) of what the portal sends: the
frames (4 bytes when each was taken, in ms on the capture's clock, then a JPEG) and the sound (relay.py's protocol:
the same clock, b"AU", the bus, Opus packets). The clip key (the capture's, Insert by default) or the chat window's
"clip" asks for it, and this writes it as an ordinary video:

    an .mp4: H.264 (yuv420p, the frames at their own times, so a dropped or held frame stays as it was) and AAC
    (both sound busses, the game and everything else, mixed into one stereo track, lined up with the frames by
    their stamps)

in the clips folder (settings.json's "clip_folder"; Videos\\ghillie portal by default), named for when it was saved.
It needs PyAV (`py -m pip install av`, already there for the sound) and Pillow. Run on its own thread: a 30 second
clip at 30 frames a second takes a few seconds.

    python clip.py --selftest DIR     a two-second clip from made-up frames and a tone, into DIR
"""

import io
import os
import struct
import sys
import time
from fractions import Fraction

RATE = 48000
OPUS_FRAME = 960          # sound.py's 20 ms packets


def default_folder():
    return os.path.join(os.path.expanduser("~"), "Videos", "ghillie portal")


def _rel(stamp, base):
    """A stamp in ms from base, on the 32-bit wrapping clock both the frames and the sound use."""
    d = (stamp - base) & 0xffffffff
    return d - (1 << 32) if d >= (1 << 31) else d


def write(items, folder=None, name=None):
    """items: what the relay kept, [(arrival time, message)], frames and sound in any order. -> the clip's path.
    Raises ValueError when there's nothing to make one from."""
    import av
    import numpy as np
    from PIL import Image

    frames, sounds = [], []
    for _, msg in items:
        if not isinstance(msg, (bytes, bytearray)) or len(msg) < 8:
            continue
        stamp = struct.unpack(">I", msg[:4])[0]
        if msg[4:6] == b"AU":
            sounds.append((stamp, msg))
        elif msg[4:6] == b"\xff\xd8":
            frames.append((stamp, bytes(msg[4:])))
    if not frames:
        raise ValueError("no frames in the replay buffer (is it on, and is the portal live?)")
    base = frames[0][0]
    frames = sorted(((_rel(s, base), j) for s, j in frames), key=lambda f: f[0])
    t_end = frames[-1][0] + 40

    folder = folder or default_folder()
    os.makedirs(folder, exist_ok=True)
    path = os.path.join(folder, name or ("clip %s.mp4" % time.strftime("%Y-%m-%d %H.%M.%S")))

    first = Image.open(io.BytesIO(frames[0][1]))
    W, H = first.size[0] // 2 * 2, first.size[1] // 2 * 2          # (H.264 wants even sizes)
    out = av.open(path, "w")
    codec = "libx264"
    try:
        av.codec.Codec(codec, "w")
    except Exception:
        codec = "h264_mf"                                            # (Windows' own encoder, if PyAV lacks x264)
    vs = out.add_stream(codec, rate=30)
    vs.width, vs.height, vs.pix_fmt = W, H, "yuv420p"
    vs.codec_context.time_base = Fraction(1, 1000)
    if codec == "libx264":
        vs.options = {"crf": "18", "preset": "veryfast"}
    aud = out.add_stream("aac", rate=RATE)
    aud.codec_context.layout = "stereo"
    aud.codec_context.sample_rate = RATE

    # the picture: each frame at its own time (ms), never two at the same one
    last = -1
    for t, jpeg in frames:
        if t <= last:
            continue
        last = t
        img = Image.open(io.BytesIO(jpeg)).convert("RGB")
        if img.size != (W, H):
            img = img.resize((W, H), Image.BILINEAR)
        vf = av.VideoFrame.from_image(img).reformat(format="yuv420p")
        vf.pts, vf.time_base = t, Fraction(1, 1000)
        for pkt in vs.encode(vf):
            out.mux(pkt)
    for pkt in vs.encode(None):
        out.mux(pkt)

    # the sound: every bus's packets decoded and laid on one timeline by their stamps, mixed
    n = max(1, int(t_end * RATE / 1000))
    mix = np.zeros((2, n), dtype=np.float32)
    decoders = {}
    for stamp, msg in sorted(sounds, key=lambda s: _rel(s[0], base)):
        bus, count, at = msg[6], msg[7], 8
        dec = decoders.get(bus)
        if dec is None:
            dec = decoders[bus] = av.CodecContext.create("libopus", "r")
            dec.sample_rate, dec.layout = RATE, "stereo"
        t0 = _rel(stamp, base)
        for i in range(count):
            if at + 2 > len(msg):
                break
            ln = struct.unpack(">H", msg[at:at + 2])[0]
            data = bytes(msg[at + 2:at + 2 + ln])
            at += 2 + ln
            start = int((t0 + i * 20) * RATE / 1000)
            for f in dec.decode(av.Packet(data)):
                fmt = f.format.name
                a = f.to_ndarray().astype(np.float32)
                if fmt.startswith("s16"):
                    a = a / 32768.0                                   # (by its format, never by how loud it looks:
                elif fmt.startswith("s32"):                           #  a quiet packet read as loud was full-scale noise)
                    a = a / 2147483648.0
                if a.shape[0] != 2:                                   # interleaved: one row of L R L R ...
                    a = a.reshape(-1, 2).T
                lo, hi = max(0, start), min(n, start + a.shape[1])
                if hi > lo:
                    mix[:, lo:hi] += a[:, lo - start:hi - start]
                start += a.shape[1]
    np.clip(mix, -1.0, 1.0, out=mix)
    step = 1024
    for k in range(0, n, step):
        chunk = np.ascontiguousarray(mix[:, k:k + step])
        af = av.AudioFrame.from_ndarray(chunk, format="fltp", layout="stereo")
        af.sample_rate, af.pts = RATE, k
        for pkt in aud.encode(af):
            out.mux(pkt)
    for pkt in aud.encode(None):
        out.mux(pkt)
    out.close()
    return path


def _selftest(folder):
    """Two seconds of made-up frames (a lime block moving) and a 440 Hz tone on bus 1, through write()."""
    import av
    import numpy as np
    from PIL import Image, ImageDraw
    items, t = [], 1000
    for i in range(60):                                    # 30 frames a second
        img = Image.new("RGB", (384, 216), (20, 40, 25))
        ImageDraw.Draw(img).rectangle([10 + i * 5, 90, 46 + i * 5, 126], fill=(185, 255, 0))
        b = io.BytesIO()
        img.save(b, "JPEG", quality=80)
        items.append((0, struct.pack(">I", t + i * 33) + b.getvalue()))
    enc = av.CodecContext.create("libopus", "w")
    enc.sample_rate, enc.layout, enc.format, enc.bit_rate = RATE, "stereo", "s16", 128000
    tone = (0.2 * np.sin(2 * np.pi * 440 * np.arange(RATE * 2) / RATE) * 32767).astype(np.int16)
    pk = []
    for k in range(0, len(tone) - OPUS_FRAME, OPUS_FRAME):
        st = np.repeat(tone[k:k + OPUS_FRAME], 2).reshape(1, -1)
        fr = av.AudioFrame.from_ndarray(st, format="s16", layout="stereo")
        fr.sample_rate, fr.pts = RATE, k
        pk += [bytes(p) for p in enc.encode(fr)]
    for g in range(0, len(pk) - 3, 3):
        body = b"".join(struct.pack(">H", len(p)) + p for p in pk[g:g + 3])
        items.append((0, struct.pack(">I", t + g * 20) + b"AU" + bytes([1, 3]) + body))
    return write(items, folder, "selftest.mp4")


if __name__ == "__main__":
    if len(sys.argv) == 3 and sys.argv[1] == "--selftest":
        print(_selftest(sys.argv[2]))
    else:
        sys.exit(__doc__)
