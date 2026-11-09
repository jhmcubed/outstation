// The almanac (11/09/2026; docs/protocol.md "The almanac"): what the relay fetches on a schedule for outside.wiki's
// globe and shares with every visitor, so no page ever asks Jagex or the Wiki itself. relay-py/almanac.py is the same,
// line for line where it can be; tests/fixtures/almanac/expected.json holds what both make of the fixtures (this file's
// own tests: relay/test/almanac_test.mts, run with `npm test`; it imports nothing, so node can run it).
//
//   GET <relay>/almanac   {"at", "updates", "worlds", "market", "sources"}
//
// Kept by the Directory (index.ts), in its storage, so an eviction or a deploy doesn't ask again: the Worker's Cron
// Trigger (wrangler.jsonc, every 5 minutes) asks it to refresh whatever's due; a read never asks a source, except once
// when nothing's ever been tried (a cold start), one refresh shared by every read waiting for it.
//
// The sources:
// - Jagex's news (the Old School RSS feed): the newest UPDATES_KEPT items, every NEWS_EVERY.
// - The world list (slr.ws, the game's own binary list, read as RuneLite read it: its http-service's WorldsService, its
//   WorldType masks and WorldRegion locations): every world's players, region, activity and types, the total, and the
//   seasonal and deadman worlds grouped ("seasons"), every WORLDS_EVERY.
// - The OSRS Wiki's real-time prices (prices.runescape.wiki, its /24h against the day before's, /mapping for the
//   names): the day's biggest movers and most traded. /24h's day changes once a day (its Expires header: the next UTC
//   midnight), so it's asked once after midnight and then hourly only until the new day's there; the day before is the
//   kept day when it's the one before, else asked once. /mapping once a day.
// A source that fails keeps its last good data (with its age in "sources") and is asked again later, each failure in a
// row doubling the wait (to a cap). No visitor's request is passed on: the only thing that leaves is the relay's own
// request, with USER_AGENT.

export const NEWS_URL = "https://secure.runescape.com/m=news/latest_news.rss?oldschool=true";
export const WORLDS_URL = "https://oldschool.runescape.com/slr.ws?order=LPWM";
export const PRICES_URL = "https://prices.runescape.wiki/api/v2/osrs";
// (the Wiki's one ask, its "Acceptable use policy": a User-Agent that says what it's for, with a way to reach us)
export const USER_AGENT = "outstation-relay/1.0 (the almanac for outside.wiki's globe: fetched on a schedule and shared by every " +
  "visitor; https://github.com/jhmcubed/outstation)";

const MINUTE = 60_000, HOUR = 3_600_000, DAY = 86_400_000;
export const NEWS_EVERY = 20 * MINUTE, WORLDS_EVERY = 5 * MINUTE, PRICES_EVERY = HOUR, MAPPING_EVERY = DAY;
const PRICES_AFTER_MIDNIGHT = 15 * MINUTE; // a new day's /24h is asked for this long after UTC midnight
// a source is due this long before its time, so a schedule's tick that comes a moment early (the cron, every 5 minutes,
// after a fetch that took a moment) still asks it, and the world list stays every 5 minutes, not 10
const DUE_SLACK = 30_000;
// after n failures in a row, the next try waits min(base * 2^(n-1), cap)
const BACKOFF: Record<Source, [number, number]> = { updates: [NEWS_EVERY, 2 * HOUR], worlds: [WORLDS_EVERY, HOUR],
                                                   market: [HOUR, 6 * HOUR], names: [HOUR, DAY] };
export const SOURCES = ["worlds", "updates", "names", "market"] as const; // (the order they're asked in: names first)
export type Source = (typeof SOURCES)[number];

const FETCH_TIMEOUT_MS = 15_000;
const FETCH_MOST = 8 * 1024 * 1024; // an answer bigger than this is refused (the mapping is about 0.9 MB)
const ID_MAX = 200;
const UPDATES_KEPT = 8, NEWS_ITEMS_READ = 50;
const TITLE_MAX = 140, SUMMARY_MAX = 200, CATEGORY_MAX = 40, ACTIVITY_MAX = 60, NAME_MAX = 60;
const WORLDS_MOST = 1000;
const MARKET_POOL = 200, MARKET_MOVERS = 6, MARKET_TRADED = 4;

// RuneLite's WorldType masks (runelite-api's net.runelite.api.WorldType, the same in its http-api 1.2.26), in its order
const WORLD_TYPES: [string, number][] = [["MEMBERS", 1], ["PVP", 1 << 2], ["BOUNTY", 1 << 5], ["PVP_ARENA", 1 << 6],
  ["SKILL_TOTAL", 1 << 7], ["QUEST_SPEEDRUNNING", 1 << 8], ["HIGH_RISK", 1 << 10], ["HARDCORE_MAIN", 1 << 11],
  ["LAST_MAN_STANDING", 1 << 14], ["BETA_WORLD", 1 << 16], ["LEGACY_ONLY", 1 << 22], ["EOC_ONLY", 1 << 23],
  ["NOSAVE_MODE", 1 << 25], ["TOURNAMENT", 1 << 26], ["FRESH_START_WORLD", 1 << 27], ["DEADMAN", 1 << 29],
  ["SEASONAL", 1 << 30]];
// RuneLite's WorldRegion (http-api 1.2.26's valueOf(int)): the list's location byte, as ISO 3166 alpha-2
const WORLD_REGIONS: Record<number, string> = { 0: "US", 1: "GB", 3: "AU", 7: "DE", 8: "BR", 9: "JP", 10: "SG", 12: "ZA" };
// worlds that are never a season's island, whatever else they're flagged: betas, no-save worlds and tournaments
const NOT_SEASONS = ["BETA_WORLD", "NOSAVE_MODE", "TOURNAMENT"];

const CONTROL = /[\x00-\x08\x0b\x0c\x0e-\x1f\x7f-\x9f\ufeff]/g;
const SPACES = /\s+/g;
const ENTITY = /&(#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|[a-zA-Z]{2,6});/g;
const NAMED: Record<string, string> = { amp: "&", lt: "<", gt: ">", quot: '"', apos: "'", nbsp: " " };
const CDATA = /<!\[CDATA\[([\s\S]*?)\]\]>/g;
const ITEM = /<item\b[^>]*>([\s\S]*?)<\/item>/gi;
const TAG = /<[^>]*>/g;
const NEWS_LINK = /^https?:\/\/([a-z0-9-]+\.)*runescape\.com(\/[^\s"'<>]*)?$/i;
const PUB_DATE = /^(?:[A-Za-z]{3},\s*)?([0-9]{1,2})\s+([A-Za-z]{3})\s+([0-9]{4})\s+([0-9]{2}):([0-9]{2})(?::([0-9]{2}))?\s*(GMT|UT|UTC|Z|[+-][0-9]{4})?$/;
const MONTHS = ["jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec"];
const ITEM_ID = /^[0-9]{1,9}$/;

export class SourceError extends Error {}

export type Update = { id: string; title: string; url: string | null; at: number | null; category: string | null; summary: string | null };
export type World = { world: number; players: number | null; region: string | null; location: number; activity: string;
                      types: string[] };
export type Season = { kind: "leagues" | "deadman"; name: string; players: number; worlds: number; activity: string | null };
export type Worlds = { players: number; worlds: number; at: number; seasons: Season[]; list: World[] };
export type Day = { ts: number; items: Record<string, [number, number]> };
export type Mover = { item: string; at: number; id: number; price: number; change: number | null; volume: number; value: number;
                      why: "mover" | "traded" };

/** JavaScript's own rounding (Python's: floor(x + 0.5)). */
const roundHalfUp = (x: number) => Math.floor(x + 0.5);

/** At most `most` characters (code points), the last an ellipsis when it's cut. */
function cut(text: string, most: number): string {
  const cps = Array.from(text);
  return cps.length <= most ? text : cps.slice(0, most - 1).join("") + "…";
}

const oneLine = (text: string) => text.replace(CONTROL, "").replace(SPACES, " ").trim();

// ---- Jagex's news: the RSS feed ----

/** &amp; &lt; &gt; &quot; &apos; &nbsp; and numeric ones, in one pass; anything else stays as it is. */
export function decodeEntities(text: string): string {
  return text.replace(ENTITY, (all, e: string) => {
    if (e[0] === "#") {
      const cp = e[1] === "x" || e[1] === "X" ? parseInt(e.slice(2), 16) : parseInt(e.slice(1), 10);
      return cp > 0 && cp <= 0x10ffff && !(cp >= 0xd800 && cp <= 0xdfff) ? String.fromCodePoint(cp) : all;
    }
    return Object.prototype.hasOwnProperty.call(NAMED, e) ? NAMED[e] : all;
  });
}

/** An element's text: its CDATA as it is, the rest with its entities decoded. */
function xmlText(raw: string): string {
  let out = "", at = 0;
  for (const m of raw.matchAll(CDATA)) {
    out += decodeEntities(raw.slice(at, m.index)) + m[1];
    at = m.index! + m[0].length;
  }
  return out + decodeEntities(raw.slice(at));
}

function tagText(body: string, name: string): string | null {
  const m = new RegExp("<" + name + "\\b[^>]*>([\\s\\S]*?)</" + name + "\\s*>", "i").exec(body);
  return m ? xmlText(m[1]) : null;
}

/** Days since 1970-01-01 (Howard Hinnant's), so a day past a month's end rolls over as Date.UTC does. */
function daysFromCivil(y: number, m: number, d: number): number {
  y -= m <= 2 ? 1 : 0;
  const era = Math.floor((y >= 0 ? y : y - 399) / 400);
  const yoe = y - era * 400;
  const doy = Math.floor((153 * (m + (m > 2 ? -3 : 9)) + 2) / 5) + d - 1;
  const doe = yoe * 365 + Math.floor(yoe / 4) - Math.floor(yoe / 100) + doy;
  return era * 146097 + doe - 719468;
}

/** An RSS date (RFC 822) as ms, or null. */
export function pubDate(text: string): number | null {
  const m = text ? PUB_DATE.exec(text.trim()) : null;
  if (!m || !MONTHS.includes(m[2].toLowerCase())) return null;
  const d = +m[1], mo = MONTHS.indexOf(m[2].toLowerCase()), y = +m[3], hh = +m[4], mm = +m[5], ss = +(m[6] || 0);
  if (!(d >= 1 && d <= 31 && hh <= 23 && mm <= 59 && ss <= 60)) return null;
  let ms = ((daysFromCivil(y, mo + 1, d) * 24 + hh) * 60 + mm) * 60_000 + ss * 1000;
  const z = m[7] || "GMT";
  if (z[0] === "+" || z[0] === "-") {
    const off = (+z.slice(1, 3) * 60 + +z.slice(3, 5)) * 60_000;
    ms -= z[0] === "+" ? off : -off;
  }
  return ms;
}

// (the feed says its encoding in its XML declaration: Jagex's is ISO-8859-1, 11/09/2026. Those labels are read as
// windows-1252, as TextDecoder reads them; anything else as UTF-8)
const SINGLE_BYTE = ["iso-8859-1", "iso8859-1", "latin-1", "latin1", "l1", "windows-1252", "cp1252", "us-ascii", "ascii"];

/** The feed's bytes as text, in the encoding its XML declaration names. */
export function feedText(raw: Uint8Array | string): string {
  if (typeof raw === "string") return raw;
  let head = "";
  for (let k = 0; k < Math.min(raw.length, 200); k++) head += String.fromCharCode(raw[k]);
  const m = /^\s*<\?xml[^>]*?encoding\s*=\s*["']([A-Za-z0-9._-]+)["']/.exec(head);
  return new TextDecoder(m && SINGLE_BYTE.includes(m[1].toLowerCase()) ? "windows-1252" : "utf-8").decode(raw);
}

/** The feed as [{id, title, url, at, category, summary}], newest first, UPDATES_KEPT at most. A DOCTYPE or an ENTITY
 *  declaration is refused (nothing in the feed needs one). */
export function parseNews(raw: Uint8Array | string): Update[] {
  const text = feedText(raw);
  if (/<!DOCTYPE|<!ENTITY/i.test(text)) throw new SourceError("the feed declares a DOCTYPE or an ENTITY");
  if (!/<rss\b|<channel\b/i.test(text)) throw new SourceError("not an RSS feed");
  const items: Update[] = [];
  let i = 0;
  for (const m of text.matchAll(ITEM)) {
    if (i++ >= NEWS_ITEMS_READ) break;
    const body = m[1];
    const title = cut(oneLine((tagText(body, "title") || "").replace(TAG, " ")), TITLE_MAX);
    if (!title) continue;
    const link = oneLine(tagText(body, "link") || "");
    const category = cut(oneLine((tagText(body, "category") || "").replace(TAG, " ")), CATEGORY_MAX) || null;
    const summary = cut(oneLine(decodeEntities((tagText(body, "description") || "").replace(TAG, " "))), SUMMARY_MAX) || null;
    const at = pubDate(tagText(body, "pubDate") || "");
    // (its id: the feed's guid, else its link (when it's on runescape.com), else its title and date: the same item keeps it, refetch after refetch)
    const url = NEWS_LINK.test(link) ? link : null;
    const id = cut(oneLine(tagText(body, "guid") || ""), ID_MAX) || Array.from(url || "").slice(0, ID_MAX).join("") || title + "@" + at;
    items.push({ id, title, url, at, category, summary });
  }
  // newest first (one without a date last), the feed's own order between equals
  const order = items.map((_, k) => k).sort((a, b) => {
    const A = items[a].at, B = items[b].at;
    if ((A === null) !== (B === null)) return A === null ? 1 : -1;
    return (B || 0) - (A || 0) || a - b;
  });
  return order.map((k) => items[k]).slice(0, UPDATES_KEPT);
}

// ---- the world list: slr.ws ----

const worldTypes = (mask: number) => WORLD_TYPES.filter(([, bit]) => (mask & bit) !== 0).map(([name]) => name);

/** slr.ws, as RuneLite's WorldsService read it: a 4-byte length, then the worlds' count (2 bytes), then each world's
 *  number (2), type mask (4), address and activity (each a 0-ended string), location (1) and players (2, signed: -1
 *  offline). Big-endian. */
export function parseWorlds(raw: Uint8Array, now: number): Worlds {
  const b = raw, v = new DataView(b.buffer, b.byteOffset, b.byteLength);
  if (b.length < 6) throw new SourceError("the world list is too short");
  const length = v.getInt32(0);
  if (length < 2 || 4 + length > b.length) throw new SourceError("the world list's length isn't its size");
  const end = 4 + length, count = v.getUint16(4);
  let at = 6;
  if (count === 0 || count > WORLDS_MOST) throw new SourceError("the world list has " + count + " worlds");
  const need = (n: number) => { if (at + n > end) throw new SourceError("the world list ends inside a world"); };
  const cstring = () => {
    const z = b.subarray(0, end).indexOf(0, at);
    if (z < 0) throw new SourceError("the world list ends inside a string");
    let s = "";
    for (let k = at; k < z; k++) s += String.fromCharCode(b[k]); // (Latin-1)
    at = z + 1;
    return s;
  };
  const worlds: World[] = [];
  for (let n = 0; n < count; n++) {
    need(6);
    const number = v.getUint16(at), mask = v.getUint32(at + 2); // (unsigned: the top bit's a flag too)
    at += 6;
    cstring(); // (the address: not passed on)
    const activity = cut(oneLine(cstring()), ACTIVITY_MAX);
    need(3);
    const location = b[at], players = v.getInt16(at + 1);
    at += 3;
    worlds.push({ world: number, players: players >= 0 ? players : null, region: WORLD_REGIONS[location] ?? null,
                  location, activity, types: worldTypes(mask) });
  }
  worlds.sort((x, y) => x.world - y.world);
  const online = worlds.filter((w) => w.players !== null);
  const seasons: Season[] = [];
  for (const kind of ["leagues", "deadman"] as const) {
    // (both are SEASONAL, as RuneLite's WorldType says: "for leagues and seasonal deadman". The permanent deadman world,
    // flagged DEADMAN alone ("Deadman - Permanent" on the real list, 11/09/2026), is never a season)
    const mine = online.filter((w) => w.types.includes("SEASONAL") && !w.types.some((t) => NOT_SEASONS.includes(t)) &&
      w.types.includes("DEADMAN") === (kind === "deadman"));
    if (!mine.length) continue;
    const counts = new Map<string, number>();
    for (const w of mine) if (w.activity) counts.set(w.activity, (counts.get(w.activity) || 0) + 1);
    let best: string | null = null; // (the commonest; between equals, the lowest world's)
    for (const w of mine) if (w.activity && (best === null || counts.get(w.activity)! > counts.get(best)!)) best = w.activity;
    seasons.push({ kind, name: kind, players: mine.reduce((s, w) => s + w.players!, 0), worlds: mine.length, activity: best });
  }
  return { players: online.reduce((s, w) => s + w.players!, 0), worlds: online.length, at: now, seasons, list: worlds };
}

// ---- the market: the Wiki's /24h and /mapping ----

const isNumber = (v: unknown): v is number => typeof v === "number" && Number.isFinite(v) && v >= 0;

/** /24h as {ts: its day's start in s, items: {id: [price, volume]}}: price the day's average, the high and low averages
 *  weighted by their volumes; volume both together. */
export function parseDay(raw: string): Day {
  let d: any;
  try { d = JSON.parse(raw); } catch { throw new SourceError("/24h isn't JSON"); }
  if (!d || typeof d !== "object" || Array.isArray(d) || !d.data || typeof d.data !== "object" || Array.isArray(d.data) ||
      !Number.isInteger(d.timestamp) || d.timestamp <= 0) throw new SourceError("/24h isn't its shape");
  const items: Record<string, [number, number]> = {};
  for (const [k, x] of Object.entries(d.data as Record<string, any>)) {
    if (!ITEM_ID.test(k) || !x || typeof x !== "object" || Array.isArray(x)) continue;
    const h = isNumber(x.avgHighPrice) ? x.avgHighPrice : null, l = isNumber(x.avgLowPrice) ? x.avgLowPrice : null;
    const hv = isNumber(x.highPriceVolume) ? x.highPriceVolume : 0, lv = isNumber(x.lowPriceVolume) ? x.lowPriceVolume : 0;
    let p: number;
    if (h !== null && l !== null) p = hv + lv > 0 ? (h * hv + l * lv) / (hv + lv) : (h + l) / 2;
    else if (h !== null) p = h;
    else if (l !== null) p = l;
    else continue;
    items[k] = [p, hv + lv];
  }
  return { ts: d.timestamp, items };
}

/** /mapping as {id: name}. */
export function parseMapping(raw: string): Record<string, string> {
  let d: any;
  try { d = JSON.parse(raw); } catch { throw new SourceError("/mapping isn't JSON"); }
  if (!Array.isArray(d)) throw new SourceError("/mapping isn't a list");
  const names: Record<string, string> = {};
  for (const x of d) {
    if (x && typeof x === "object" && Number.isInteger(x.id) && x.id >= 0 && x.id < 1e9 && typeof x.name === "string") {
      const name = cut(oneLine(x.name), NAME_MAX);
      if (name) names[String(x.id)] = name;
    }
  }
  if (!Object.keys(names).length) throw new SourceError("/mapping has no items");
  return names;
}

/** The ticker (each line's "at": the Wiki's day its numbers are from, in ms): the MARKET_MOVERS biggest moves on the day before among the MARKET_POOL most traded by gp, then the
 *  MARKET_TRADED most traded of the rest. */
export function market(latest: Day, before: Day | null, names: Record<string, string>): Mover[] {
  type P = { k: string; p: number; v: number; value: number };
  const pool: P[] = Object.entries(latest.items).filter(([, [, v]]) => v > 0).map(([k, [p, v]]) => ({ k, p, v, value: p * v }));
  pool.sort((a, b) => b.value - a.value || +a.k - +b.k);
  pool.length = Math.min(pool.length, MARKET_POOL);
  const prev = before ? before.items : {};
  const changeOf = (x: P): number | null => {
    const b = Object.prototype.hasOwnProperty.call(prev, x.k) ? prev[x.k] : null;
    return b && b[0] > 0 && b[1] > 0 ? (x.p - b[0]) / b[0] * 100 : null;
  };
  const entry = (x: P, change: number | null, why: "mover" | "traded"): Mover => ({
    item: (Object.prototype.hasOwnProperty.call(names, x.k) && names[x.k]) || "item " + x.k, at: latest.ts * 1000, id: +x.k, price: roundHalfUp(x.p),
    change: change === null ? null : roundHalfUp(change * 10) / 10 + 0, volume: x.v, value: roundHalfUp(x.value), why });
  const moves = pool.map((x) => ({ x, c: changeOf(x) })).filter((m) => m.c !== null) as { x: P; c: number }[];
  moves.sort((a, b) => Math.abs(b.c) - Math.abs(a.c) || +a.x.k - +b.x.k);
  const out = moves.slice(0, MARKET_MOVERS).map((m) => entry(m.x, m.c, "mover"));
  const picked = new Set(out.map((e) => e.id));
  for (const x of pool) {
    if (out.length >= MARKET_MOVERS + MARKET_TRADED) break;
    if (picked.has(+x.k)) continue;
    out.push(entry(x, changeOf(x), "traded"));
  }
  return out;
}

const nextMidnight = (now: number) => (Math.floor(now / DAY) + 1) * DAY;

// ---- the schedule and what's kept ----

export type SourceState = { at: number | null; tried: number | null; ok: boolean | null; fails: number; next: number;
                            error: string | null };
/** Everything the Directory keeps for the almanac but the two days' prices and the names (kept apart: they're big). */
export type Kept = { state: Record<Source, SourceState>; updates: Update[] | null; worlds: Worlds | null;
                     market: Mover[] | null; day: number | null };

const freshState = (): SourceState => ({ at: null, tried: null, ok: null, fails: 0, next: 0, error: null });
export const freshKept = (): Kept => ({ state: { worlds: freshState(), updates: freshState(), names: freshState(), market: freshState() },
                                        updates: null, worlds: null, market: null, day: null });

/** Each source's address: the real hosts, or (tests) everything under base, at the real one's path and query. */
export function urls(base?: string | null): Record<"news" | "worlds" | "day" | "mapping", string> {
  const real = { news: NEWS_URL, worlds: WORLDS_URL, day: PRICES_URL + "/24h", mapping: PRICES_URL + "/mapping" };
  if (!base) return real;
  const b = base.replace(/\/+$/, "");
  const under = (u: string) => b + "/" + u.split("://")[1].split("/").slice(1).join("/");
  return { news: under(real.news), worlds: under(real.worlds), day: under(real.day), mapping: under(real.mapping) };
}

/** One GET with USER_AGENT, the body as bytes; throws on anything but a 200 or past FETCH_MOST. */
export async function httpFetch(url: string): Promise<Uint8Array> {
  const r = await fetch(url, { headers: { "user-agent": USER_AGENT }, signal: AbortSignal.timeout(FETCH_TIMEOUT_MS) });
  if (r.status !== 200) { await r.body?.cancel(); throw new SourceError("HTTP " + r.status); }
  if (+(r.headers.get("content-length") || 0) > FETCH_MOST) { await r.body?.cancel(); throw new SourceError("the answer is too big"); }
  const reader = r.body!.getReader(), parts: Uint8Array[] = [];
  let n = 0;
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    n += value.length;
    if (n > FETCH_MOST) { await reader.cancel(); throw new SourceError("the answer is bigger than " + FETCH_MOST + " bytes"); }
    parts.push(value);
  }
  const out = new Uint8Array(n);
  let o = 0;
  for (const p of parts) { out.set(p, o); o += p.length; }
  return out;
}

/** What's kept, and when each source is next asked. The Directory gives it what it stored and stores what it changes
 *  (dirty: which of "kept", "latest", "before", "names" to write). */
export class Almanac {
  dirty = new Set<"kept" | "latest" | "before" | "names">();
  refreshing: Promise<void> | null = null;
  warming = false; // whether the refresh running is trying a source for the first time
  kept: Kept;
  latest: Day | null;
  before: Day | null;
  names: Record<string, string>;
  get: (url: string) => Promise<Uint8Array>;
  at: ReturnType<typeof urls>;
  clock: () => number;
  constructor(kept: Kept, latest: Day | null, before: Day | null, names: Record<string, string>,
              get: (url: string) => Promise<Uint8Array>, at: ReturnType<typeof urls>, clock: () => number = Date.now) {
    this.kept = kept; this.latest = latest; this.before = before; this.names = names; this.get = get; this.at = at;
    this.clock = clock;
  }

  /** Asks each source that's due (every one, with force) and keeps what comes. One refresh at a time: asked for while
   *  one runs, it waits for that one (and with force, then runs its own). */
  async refresh(force = false): Promise<void> {
    while (this.refreshing) {
      await this.refreshing;
      if (!force) return;
    }
    this.refreshing = this.run(force).finally(() => { this.refreshing = null; this.warming = false; });
    await this.refreshing;
  }

  private async run(force: boolean) {
    this.warming = SOURCES.some((s) => this.kept.state[s].tried === null);
    const now = this.clock();
    for (const s of SOURCES) if (force || now + DUE_SLACK >= this.kept.state[s].next) await this.ask(s);
  }

  async ask(source: Source) {
    const st = this.kept.state[source], now = this.clock();
    st.tried = now;
    this.dirty.add("kept");
    try {
      const next = await this.asks[source](now);
      Object.assign(st, { ok: true, fails: 0, error: null, at: now, next });
    } catch (e) {
      st.ok = false;
      st.fails += 1;
      st.error = cut(oneLine(String((e as Error)?.message || (e as Error)?.name || e)), 120);
      const [base, cap] = BACKOFF[source];
      st.next = now + Math.min(base * 2 ** (st.fails - 1), cap);
      console.log("the almanac's " + source + ": " + st.error);
    }
  }

  private text = async (url: string) => new TextDecoder("utf-8").decode(await this.get(url));

  private asks: Record<Source, (now: number) => Promise<number>> = {
    updates: async (now) => { this.kept.updates = parseNews(await this.get(this.at.news)); return now + NEWS_EVERY; },
    worlds: async (now) => { this.kept.worlds = parseWorlds(await this.get(this.at.worlds), now); return now + WORLDS_EVERY; },
    names: async (now) => { this.names = parseMapping(await this.text(this.at.mapping)); this.dirty.add("names"); return now + MAPPING_EVERY; },
    market: async (now) => {
      const latest = parseDay(await this.text(this.at.day));
      // (the same day as kept: the new one isn't out yet; asked again in an hour. The ticker's made again, in case the
      // names came since)
      if (this.latest && latest.ts === this.latest.ts && this.kept.market) {
        this.kept.market = market(this.latest, this.before, this.names);
        return now + PRICES_EVERY;
      }
      const want = latest.ts - 86400;
      let before: Day;
      if (this.latest && this.latest.ts === want) before = this.latest;
      else if (this.before && this.before.ts === want) before = this.before;
      else {
        before = parseDay(await this.text(this.at.day + "?timestamp=" + want));
        if (before.ts !== want) throw new SourceError("/24h?timestamp= answered another day");
      }
      this.latest = latest;
      this.before = before;
      this.dirty.add("latest").add("before");
      this.kept.market = market(latest, before, this.names);
      this.kept.day = latest.ts * 1000;
      return Math.max(now + PRICES_EVERY, nextMidnight(now) + PRICES_AFTER_MIDNIGHT);
    },
  };

  /** Whether a read should wait for a refresh: a source has never been tried, or the refresh running is trying one
   *  for the first time (a cold start). Once each has been tried, a read never waits, whatever came of it. */
  cold(): boolean { return this.warming || SOURCES.some((s) => this.kept.state[s].tried === null); }

  /** GET /almanac's answer. */
  read(now = this.clock()) {
    const about = (s: Source, extra?: Record<string, unknown>) => {
      const st = this.kept.state[s];
      return { at: st.at, ok: st.ok, age_s: st.at === null ? null : Math.max(0, Math.floor((now - st.at) / 1000)), ...extra };
    };
    return { at: now, updates: this.kept.updates, worlds: this.kept.worlds, market: this.kept.market,
             sources: { updates: about("updates"), worlds: about("worlds"), market: about("market", { day: this.kept.day }),
                        names: about("names") } };
  }

  /** The keeper's (POST /admin/almanac): each source's schedule and last error. */
  status(now = this.clock()) {
    const sources: Record<string, unknown> = {};
    for (const s of SOURCES) {
      const st = this.kept.state[s];
      sources[s] = { at: st.at, tried: st.tried, ok: st.ok, fails: st.fails, next_in_s: Math.max(0, Math.floor((st.next - now) / 1000)),
                     error: st.error };
    }
    return { sources };
  }
}
