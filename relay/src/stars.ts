// Shooting stars (11/08/2026, Jace's choice for outside.wiki's globe; docs/protocol.md "Shooting stars"): the crashed
// stars the open outstations' players have found, with nobody's name on them.
//
//   GET <relay>/stars      {"stars": [{"world", "x", "y", "tier", "found", "seen"}, ...]}
//
// The plugin sends each star it sees on its game socket ("star": game.ts's gameStar), two minutes late. Its channel
// passes it to the Directory (Channel.takeStar), which keeps the list here (StarList), in memory only: one star for each
// world and square, whoever told of it (several outstations telling of one star make one), and never who did. A
// channel's only trace is how many it's told of in the last hour (STARS_AN_HOUR at most; past that, ignored), forgotten
// an hour after its last. relay-py/relay.py's StarList, the same.

import { type StarReport } from "./game";

// a star's kept for STAR_LIFE_MS after it was first told of (a star lasts about two hours), or till one's told gone; for
// STAR_GONE_MS after it's gone, a report of it (another outstation's, as late) doesn't bring it back; one outstation
// tells of STARS_AN_HOUR in an hour at most; STARS_KEPT at most at once (past that, a new one's ignored)
export const STAR_LIFE_MS = 2 * 3_600_000, STAR_GONE_MS = 10 * 60_000, STARS_AN_HOUR = 30, STARS_KEPT = 1000;

/** A star as it's kept and shown: its world, its square's middle, its tier, when it was first told of and last. */
export type Star = { world: number; x: number; y: number; tier: number; found: number; seen: number };

export class StarList {
  stars = new Map<string, Star>(); // "world:x:y" -> the star
  gone = new Map<string, number>(); // "world:x:y" -> when it was told gone
  recent = new Map<string, number[]>(); // a channel -> when (ms) it told of one, the last hour (STARS_AN_HOUR)

  /** The stars and the gone past their time, and a quiet channel's times, forgotten. */
  expire(now: number) {
    for (const [k, s] of this.stars) if (now - s.found >= STAR_LIFE_MS) this.stars.delete(k);
    for (const [k, t] of this.gone) if (now - t >= STAR_GONE_MS) this.gone.delete(k);
    for (const [c, t] of this.recent) if (now - t[t.length - 1] >= 3_600_000) this.recent.delete(c);
  }

  /** A channel's report (gameStar's): whether it was counted (false past its STARS_AN_HOUR). A new star's added; one
   *  kept takes a tier as low or lower (a star's only ever mined down: a higher one is older news) and when it was
   *  seen; gone takes it off, and keeps it off for STAR_GONE_MS. */
  add(channel: string, r: StarReport, now = Date.now()): boolean {
    this.expire(now);
    const times = (this.recent.get(channel) || []).filter((t) => now - t < 3_600_000);
    if (times.length >= STARS_AN_HOUR) return false;
    times.push(now);
    this.recent.set(channel, times);
    const k = r.world + ":" + r.x + ":" + r.y, had = this.stars.get(k);
    if ("gone" in r) {
      this.stars.delete(k);
      this.gone.set(k, now);
      return true;
    }
    if (this.gone.has(k)) return true;
    if (had) {
      if (r.tier <= had.tier) { had.tier = r.tier; had.seen = now; }
      return true;
    }
    if (this.stars.size < STARS_KEPT) this.stars.set(k, { world: r.world, x: r.x, y: r.y, tier: r.tier, found: now, seen: now });
    return true;
  }

  /** GET /stars: every star kept, by world, then x, then y. */
  read(now = Date.now()): { stars: Star[] } {
    this.expire(now);
    const stars = [...this.stars.values()].map((s) => ({ ...s }));
    stars.sort((a, b) => a.world - b.world || a.x - b.x || a.y - b.y);
    return { stars };
  }
}
