# outside-portal

A live portal from your RuneLite to your Outside page on [ghillie.wiki](https://ghillie.wiki) (`ghillie.wiki/outside?player=<you>`): a few frames a second of your game, its sound if you want, and a chat with whoever's watching.

**Being built.** So far it has the relay. The client, a single Windows app, comes next.

## In plain words

- **The client** runs on your PC beside RuneLite. It reads RuneLite's window the way streaming software does, and sends it to the relay. It never touches the game: nothing a viewer does becomes input to it.
- **The relay** passes your stream on to everyone watching, so your home connection sends one copy however many watch. It runs on Cloudflare.
- **Your channel** is named after your player. Its key is yours alone. Jace hands out channels as attach codes: paste yours into the client, and that's the set-up.
- **What goes out:** RuneLite's game area, and nothing else on your screen. It stops while you're paused (the Pause key) or minimised, and the half second before either is taken back. If you use Dink, your level-ups, drops and the like go out too, but nothing that names another player.

## The parts

- `relay/`: the relay on Cloudflare: a Worker, with a Durable Object for each channel (TypeScript). Its README says how to run it and deploy it.
- `relay-py/`: the same relay in Python, standard library only, for running on your own PC (`py relay-py/relay.py`). It's also the reference.
- `docs/protocol.md`: what the client, the relay and the page say to each other.
- `tests/protocol_test.py`: the same tests run against either relay.
- `tests/fanout_bench.py`, `tests/fanout_local.py`: how one channel holds up with many viewers and some stalled ones.

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

MIT (`LICENSE`).
