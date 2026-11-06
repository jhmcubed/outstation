# outside-portal

A live portal from your RuneLite to your Outside page on [ghillie.wiki](https://ghillie.wiki) (`ghillie.wiki/outside?player=<you>`): a few frames a second of your game, its sound if you want, and a chat with whoever's watching.

**Being built.** The relay and the client both work, tested on one PC. Nothing is released yet.

**Is it safe for my account?** It does what streaming software does and nothing more: it never touches RuneLite or the game, and it can't press a key or click for you. `SAFETY.md` says it in plain words, and says how to check every line yourself.

## In plain words

- **The client** runs on your PC beside RuneLite. It reads RuneLite's window the way streaming software does, and sends it to the relay. It never touches the game: nothing a viewer does becomes input to it.
- **The relay** passes your stream on to everyone watching, so your home connection sends one copy however many watch. It runs on Cloudflare.
- **Your channel** is named after your player. Its key is yours alone. Jace hands out channels as attach codes: paste yours into the client, and that's the set-up. No code yet? The client's first screen asks for one ("no code yet? ask for one": your player's name and a note so Jace knows you), and attaches by itself once he says yes.
- **What goes out:** RuneLite's game area, and nothing else on your screen. It stops while you're paused (the Pause key) or minimised, and the half second before either is taken back. If you use Dink, your level-ups, drops and the like go out too, but nothing that names another player.

## The parts

- `client/`: the broadcaster, `outside-portal.exe` (Rust): the capture, the sound, the chat window with its drawers, Dink, the tray. `client/PARITY.md` lists everything ghillie's Python portal did and what the client does for each.
  - Building it on Windows: Rust, with cmake and ninja on the PATH for libopus. GitHub's machines build the releases with MSVC; Jace's PC uses the gnullvm toolchain (`rust-toolchain.toml`). Then `cargo build --release`.
  - `outside-portal.exe --once frame.png` writes one frame; `--bench 30` times a frame stage by stage.
  - The keeper's own build, the field station: `cargo build --release --features station` (ghillie's `tools/launcher/build_station.py`). The same portal, with the keeper's tools in its tray (what ghillie's `launcher.json` starts, modes, links) and friends' portals handed out from a drawer (`client/src/station.rs`, `keeper.rs`). A release is never built with it.

- `relay/`: the relay on Cloudflare: a Worker, with a Durable Object for each channel (TypeScript). Its README says how to run it and deploy it.
- `relay-py/`: the same relay in Python, standard library only, for running on your own PC (`py relay-py/relay.py`). It's also the reference.
- `docs/protocol.md`: what the client, the relay and the page say to each other.
- `tests/protocol_test.py`: the same tests run against either relay.
- `tests/fanout_bench.py`, `tests/fanout_local.py`: how one channel holds up with many viewers and some stalled ones.
- `tests/client_live.py`: the client end to end against either relay, with RuneLite open. `tests/client_screens.py` takes pictures of its screens.
- `tests/safety_check.py`: what the built program can call in Windows, read from the file itself (SAFETY.md).

## Tests

```bash
py tests/protocol_test.py
```

That runs against the Python relay. To run against the Worker under `wrangler dev` (after `npm install` in `relay/`):

```bash
PORTAL_RELAY=worker py tests/protocol_test.py
```

```bash
py relay-py/test_relay.py
```

## Licence

MIT (`LICENSE`). The two RuneScape fonts in `client/assets/fonts` are WolfieMario's, under their own Creative Commons BY-NC-SA licence (`client/assets/fonts/README.md`).
