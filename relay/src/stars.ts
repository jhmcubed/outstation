// Shooting stars (11/08/2026, Jace's choice for outside.wiki's globe; docs/protocol.md "Shooting stars"): the crashed
// stars the outstations' players have found, and who found each first (11/09/2026, Jace: "not sure where you got the
// idea stars need to be anonymous").
//
//   GET <relay>/stars      {"stars": [{"world", "x", "y", "site", "tier", "found", "seen", "by", "reports"}, ...]}
//
// The plugin sends each star it sees on its game socket ("star": game.ts's gameStar), two minutes late. Its channel
// passes it to the Directory (Channel.takeStar), with whether the channel's on the open outstations' list, and the
// Directory keeps the list here (StarList), in its storage ("stars", 11/10/2026: it had been in memory only, and the
// Directory sleeps about 10 seconds after its last request; saved, from): one star for each world and square (several outstations
// telling of one star make one), the first of them on that list whose report asked for the credit as its finder ("by",
// 11/09/2026: opt-in, the report's "credit": true; a channel with a watch code, or an invite not taken up, is never
// named, so nothing's said of a hidden outstation), and how many outstations told of it (credit or not).
// One outstation tells of STARS_AN_HOUR in an hour at most (past that, ignored). relay-py/relay.py's StarList, the same.

import { type StarReport } from "./game";

// a star's kept for STAR_LIFE_MS after it was first told of (a star lasts about two hours), or till one's told gone; for
// STAR_GONE_MS after it's gone, a report of it (another outstation's, as late) doesn't bring it back; one outstation
// tells of STARS_AN_HOUR in an hour at most; STARS_KEPT at most at once (past that, a new one's ignored)
export const STAR_LIFE_MS = 2 * 3_600_000, STAR_GONE_MS = 10 * 60_000, STARS_AN_HOUR = 30, STARS_KEPT = 1000;

/** A star as it's shown: its world, its square's middle, its landing site's name (null if it isn't one of STAR_SITES),
 *  its tier, when it was first told of and last, the first listed outstation that told of it (null for none), and how
 *  many outstations told of it. */
export type Star = { world: number; x: number; y: number; site: string | null; tier: number; found: number; seen: number;
                     by: string | null; reports: number };

/** The list as the Directory keeps it in storage ("stars"): the stars, the channels that told of each, the gone ones and
 *  each channel's times of the last hour (so STARS_AN_HOUR and STAR_GONE_MS hold across a sleep). One value, so a report
 *  is one row written. */
export type SavedStars = { stars: Star[]; tellers: [string, string[]][]; gone: [string, number][]; recent: [string, number[]][] };

const isObj = (v: unknown): v is Record<string, unknown> => !!v && typeof v === "object" && !Array.isArray(v);
const pairs = (v: unknown): [string, unknown][] =>
  (Array.isArray(v) ? v : []).filter((p): p is [string, unknown] => Array.isArray(p) && typeof p[0] === "string");

export class StarList {
  stars = new Map<string, Star>(); // "world:x:y" -> the star
  tellers = new Map<string, Set<string>>(); // "world:x:y" -> the channels that told of it (for "reports")
  gone = new Map<string, number>(); // "world:x:y" -> when it was told gone
  recent = new Map<string, number[]>(); // a channel -> when (ms) it told of one, the last hour (STARS_AN_HOUR)
  changed = false; // whether it's changed since it was last saved (the Directory writes it, then clears this)

  /** The list from what was saved (none, or not its shape: an empty one), what's past its time forgotten. */
  static from(saved: unknown, now = Date.now()): StarList {
    const s = isObj(saved) ? saved : {}, l = new StarList();
    for (const st of Array.isArray(s.stars) ? s.stars : []) {
      if (isObj(st) && typeof st.world === "number" && typeof st.x === "number" && typeof st.y === "number") {
        l.stars.set(st.world + ":" + st.x + ":" + st.y, st as Star);
      }
    }
    for (const [k, t] of pairs(s.tellers)) if (Array.isArray(t) && l.stars.has(k)) l.tellers.set(k, new Set(t as string[]));
    for (const k of l.stars.keys()) if (!l.tellers.has(k)) l.tellers.set(k, new Set());
    for (const [k, t] of pairs(s.gone)) if (typeof t === "number") l.gone.set(k, t);
    for (const [c, t] of pairs(s.recent)) if (Array.isArray(t) && t.length) l.recent.set(c, t as number[]);
    l.expire(now);
    return l;
  }

  /** What the Directory writes to storage: the whole list, as one value. */
  saved(): SavedStars {
    return { stars: [...this.stars.values()], tellers: [...this.tellers].map(([k, t]) => [k, [...t]]),
             gone: [...this.gone], recent: [...this.recent] };
  }

  /** The stars and the gone past their time, and a quiet channel's times, forgotten (changed, if any was). */
  expire(now: number) {
    for (const [k, s] of this.stars) {
      if (now - s.found >= STAR_LIFE_MS) { this.stars.delete(k); this.tellers.delete(k); this.changed = true; }
    }
    for (const [k, t] of this.gone) if (now - t >= STAR_GONE_MS) { this.gone.delete(k); this.changed = true; }
    for (const [c, t] of this.recent) if (now - t[t.length - 1] >= 3_600_000) { this.recent.delete(c); this.changed = true; }
  }

  /** A channel's report (gameStar's), and whether the channel's on the open outstations' list: whether it was counted
   *  (false past its STARS_AN_HOUR). A new star's added; one kept takes a tier as low or lower (a star's only ever mined
   *  down: a higher one is older news) and when it was seen, and a site and a finder if it had none; gone takes it off,
   *  and keeps it off for STAR_GONE_MS. */
  add(channel: string, r: StarReport, listed: boolean, now = Date.now()): boolean {
    this.expire(now);
    const times = (this.recent.get(channel) || []).filter((t) => now - t < 3_600_000);
    if (times.length >= STARS_AN_HOUR) return false;
    times.push(now);
    this.recent.set(channel, times);
    this.changed = true; // (its count, at least)
    const k = r.world + ":" + r.x + ":" + r.y, had = this.stars.get(k);
    if ("gone" in r) {
      this.stars.delete(k);
      this.tellers.delete(k);
      this.gone.set(k, now);
      return true;
    }
    if (this.gone.has(k)) return true;
    if (had) {
      if (r.tier <= had.tier) { had.tier = r.tier; had.seen = now; }
      had.site ??= r.site ?? null;
      if (had.by === null && listed && r.credit === true) had.by = channel;
      const tellers = this.tellers.get(k)!;
      tellers.add(channel);
      had.reports = tellers.size;
      return true;
    }
    if (this.stars.size >= STARS_KEPT) return true; // (full: a new one's ignored)
    this.stars.set(k, { world: r.world, x: r.x, y: r.y, site: r.site ?? null, tier: r.tier, found: now, seen: now,
                        by: listed && r.credit === true ? channel : null, reports: 1 });
    this.tellers.set(k, new Set([channel]));
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
