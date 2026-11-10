// The lookups (11/10/2026; docs/protocol.md "The lookups"): Wise Old Man's and RuneProfile's records of a player, and
// the item and metric icons, fetched by the relay and kept, so a page never asks those hosts itself and a thousand
// visitors looking at one player make one request upstream, not a thousand. Jace, 11/10/2026: move every per-visitor
// third-party call behind the relay, cached and shared, before asking Wise Old Man and RuneProfile for keys.
// relay-py/lookup.py is the same, line for line where it can be; tests/fixtures/lookup holds the answers both are
// tested against (this file's own tests: relay/test/lookup_test.mts, run with `npm test`; it imports nothing, so node
// can run it).
//
//   GET <relay>/lookup/wom/<name>                 Wise Old Man's player (api.wiseoldman.net/v2/players/<name>)
//   GET <relay>/lookup/wom/<name>/achievements    …/achievements
//   GET <relay>/lookup/wom/<name>/gained          …/gained?period=week (the week only)
//   GET <relay>/lookup/wom/<name>/groups          …/groups
//   GET <relay>/lookup/rp/<name>                  RuneProfile's account (api.runeprofile.com/v1/accounts/<name>)
//   GET <relay>/lookup/rp/<name>/activities       …/activities?limit=20
//   GET <relay>/icon/item/<id>.png                RuneLite's item icon (static.runelite.net/cache/item/icon/<id>.png)
//   GET <relay>/icon/metric/<metric>.png          Wise Old Man's metric icon (its GitHub's metrics_small, by jsDelivr)
//
// Only these shapes: nothing else is passed on, and a page's own query string is never sent upstream. A name is one
// the game allows (1-12 of letters, digits, space, _ and -), asked and kept as Wise Old Man reads it: lower case, a
// run of spaces, _ and - as one space. The answer is the source's own body (a JSON source's checked as JSON, an
// icon's as a PNG), with:
//   200   the record, kept for its FRESH time, then asked again when next read
//   404   not there: Wise Old Man's 404 and RuneProfile's 400 ("no such account"), kept for MISS_FRESH
//   503   not asked (the source's minute is used up, it said to wait, or it's failing) and nothing kept: Retry-After
// x-lookup-at is when the relay fetched what it answers (ms); x-lookup-stale: 1 when it's past its fresh time and a new
// one couldn't be had (the source failing, or paused), so the last good one stands, up to KEEP_DAYS old.
//
// Upstream: each source has its own allowance a minute (PER_MINUTE), across every visitor; a 429 pauses that source
// (its Retry-After, 60 s when it gives none, 10 minutes at most); one request at a time for a key (reads that come
// while it's asked wait for it); a key that fails waits FAIL_BASE, doubling each failure in a row, to FAIL_CAP, before
// it's asked again. Every request carries USER_AGENT and nothing from the visitor.

export const USER_AGENT = "outstation-relay/1.0 (lookups for outside.wiki and ghillie.wiki's Outside page: each player " +
  "asked once and shared by every visitor; https://github.com/jhmcubed/outstation)";

const MINUTE = 60_000, HOUR = 3_600_000, DAY = 86_400_000;
export type Source = "wom" | "rp" | "item" | "metric";
export const SOURCES: Source[] = ["wom", "rp", "item", "metric"];
export const HOSTS: Record<Source, string> = {
  wom: "https://api.wiseoldman.net/v2/players/",
  rp: "https://api.runeprofile.com/v1/accounts/",
  item: "https://static.runelite.net/cache/item/icon/",
  metric: "https://cdn.jsdelivr.net/gh/wise-old-man/wise-old-man@master/app/public/img/metrics_small/",
};
// (each a little under what's asked of us: Wise Old Man's 20 a minute without a key, RuneProfile's none stated)
export const PER_MINUTE: Record<Source, number> = { wom: 15, rp: 20, item: 60, metric: 60 };
export const FRESH_ICON = 30 * DAY, MISS_FRESH = 6 * HOUR, MISS_FRESH_ICON = DAY;
export const KEEP_DAYS = 14, KEEP_DAYS_ICON = 90; // a record fetched longer ago than this is dropped (the daily prune)
export const KEPT_MOST = 5000; // entries at most; past it the oldest fetched go first
export const FAIL_BASE = MINUTE, FAIL_CAP = HOUR, PAUSE_DEFAULT = MINUTE, PAUSE_MOST = 10 * MINUTE;
const BODY_MOST: Record<Source, number> = { wom: 1024 * 1024, rp: 1024 * 1024, item: 64 * 1024, metric: 64 * 1024 };
const FETCH_TIMEOUT_MS = 15_000;
const NAME_OK = /^[A-Za-z0-9 _-]{1,12}$/;
const ITEM_ID = /^[0-9]{1,6}\.png$/;
const METRIC = /^[a-z0-9_]{1,40}\.png$/;
const PNG = [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a];

// what each record is: [the path under the source's host (after the name), how long it's fresh]
const PARTS: Record<"wom" | "rp", Record<string, [string, number]>> = {
  wom: { "": ["", HOUR], achievements: ["/achievements", HOUR], gained: ["/gained?period=week", HOUR],
         groups: ["/groups", 6 * HOUR] },
  rp: { "": ["", HOUR], activities: ["/activities?limit=20", 30 * 60_000] },
};

export type Want = { source: Source; key: string; url: string; fresh: number; missFresh: number; json: boolean };
export type Entry = { at: number; status: 200 | 404; type: string; body: Uint8Array };
export type Fetched = { status: number; body: Uint8Array | null; retryAfter: number | null };
export type Fetcher = (url: string, source: Source) => Promise<Fetched>;
export type Store = {
  get(key: string): Promise<Entry | undefined>;
  put(key: string, e: Entry): Promise<void>;
};
export type Answer = { status: number; headers: Record<string, string>; body: Uint8Array | null };

/** A name as Wise Old Man reads it (lower case, a run of spaces, _ and - as one space), or null if the game wouldn't allow it. */
export function plainName(raw: string): string | null {
  if (!NAME_OK.test(raw)) return null;
  const n = raw.toLowerCase().replace(/[ _-]+/g, " ").trim();
  return n ? n : null;
}

/** The address's parts (after the relay's own: ["lookup", "wom", name, part?] or ["icon", "item", "4151.png"]) as a
 *  record to look up, or null if it isn't one of the shapes. base: (tests) every source under this address instead. */
export function want(parts: string[], base?: string | null): Want | null {
  let source: Source, key: string, path: string, fresh: number, missFresh = MISS_FRESH, json = true;
  if (parts[0] === "lookup" && (parts[1] === "wom" || parts[1] === "rp") && (parts.length === 3 || parts.length === 4)) {
    const name = plainName(parts[2]), part = parts.length === 4 ? parts[3] : "", spec = PARTS[parts[1]][part];
    if (!name || !spec || (parts.length === 4 && !part)) return null;
    source = parts[1];
    key = source + ":" + name + (part ? "/" + part : "");
    path = encodeURIComponent(name) + spec[0];
    fresh = spec[1];
  } else if (parts[0] === "icon" && parts.length === 3 && (parts[1] === "item" || parts[1] === "metric")) {
    const file = parts[2];
    if (parts[1] === "item" ? !ITEM_ID.test(file) : !METRIC.test(file)) return null;
    source = parts[1];
    // (an item's id as a number: 04151.png is 4151.png)
    const name = parts[1] === "item" ? String(parseInt(file, 10)) + ".png" : file;
    key = source + ":" + name;
    path = name;
    fresh = FRESH_ICON;
    missFresh = MISS_FRESH_ICON;
    json = false;
  } else {
    return null;
  }
  return { source, key, url: under(HOSTS[source], base) + path, fresh, missFresh, json };
}

function under(host: string, base?: string | null): string {
  if (!base) return host;
  return base.replace(/\/+$/, "") + "/" + host.split("://")[1].split("/").slice(1).join("/");
}

/** Whether a body is what its source should send: JSON that parses, or a PNG. */
export function looksRight(w: Want, body: Uint8Array): boolean {
  if (!w.json) return body.length >= 8 && PNG.every((b, i) => body[i] === b);
  try { JSON.parse(new TextDecoder("utf-8", { fatal: true, ignoreBOM: false }).decode(body)); return true; } catch { return false; }
}

const CORS = { "access-control-allow-origin": "*", "access-control-expose-headers": "x-lookup-at, x-lookup-stale" };
const enc = new TextEncoder();

/** An answer's headers and body. */
function answerOf(w: Want, e: Entry | null, stale: boolean, retry: number): Answer {
  if (!e) {
    return { status: 503, body: enc.encode(JSON.stringify({ error: "not asked just now: try again later" })),
             headers: { ...CORS, "content-type": "application/json", "cache-control": "no-store",
                        "retry-after": String(Math.max(1, Math.ceil(retry / 1000))) } };
  }
  const h: Record<string, string> = { ...CORS, "content-type": e.type, "x-lookup-at": String(e.at) };
  if (stale) h["x-lookup-stale"] = "1";
  // (a browser keeps an icon a week, a record five minutes, a miss an hour; a stale one a minute, to look again soon)
  h["cache-control"] = stale ? "public, max-age=60" : e.status === 404 ? "public, max-age=3600"
    : w.json ? "public, max-age=300" : "public, max-age=604800";
  return { status: e.status, headers: h, body: e.body };
}

/** The lookups' keeping and asking. One for the relay (the Worker's Lookups object, the Python relay's own); the store
 *  is its storage, the fetcher the network (the tests give their own of each, and a clock). */
export class Shelf {
  starts: Record<Source, number[]> = { wom: [], rp: [], item: [], metric: [] };
  paused: Record<Source, number> = { wom: 0, rp: 0, item: 0, metric: 0 };
  fails = new Map<string, { n: number; next: number }>();
  asking = new Map<string, Promise<Entry | null>>();

  store: Store;
  fetcher: Fetcher;
  now: () => number;

  constructor(store: Store, fetcher: Fetcher, now: () => number = Date.now) {
    this.store = store;
    this.fetcher = fetcher;
    this.now = now;
  }

  /** When the source may next be asked (0: now). */
  wait(source: Source): number {
    const t = this.now(), s = this.starts[source];
    while (s.length && t - s[0] >= MINUTE) s.shift();
    let at = Math.max(0, this.paused[source] - t);
    if (s.length >= PER_MINUTE[source]) at = Math.max(at, s[0] + MINUTE - t);
    return at;
  }

  async read(w: Want): Promise<Answer> {
    const kept = (await this.store.get(w.key)) || null, t = this.now();
    if (kept && t - kept.at < (kept.status === 200 ? w.fresh : w.missFresh)) return answerOf(w, kept, false, 0);
    const f = this.fails.get(w.key);
    let retry = Math.max(this.wait(w.source), f ? f.next - t : 0);
    if (retry <= 0) {
      let p = this.asking.get(w.key);
      if (!p) {
        p = this.ask(w).finally(() => this.asking.delete(w.key));
        this.asking.set(w.key, p);
      }
      const got = await p;
      if (got) return answerOf(w, got, false, 0);
      const f2 = this.fails.get(w.key);
      retry = Math.max(this.wait(w.source), f2 ? f2.next - this.now() : FAIL_BASE);
    }
    return kept ? answerOf(w, kept, true, retry) : answerOf(w, null, false, retry);
  }

  /** One request upstream; what it brought kept (and returned), or null when it failed. */
  async ask(w: Want): Promise<Entry | null> {
    this.starts[w.source].push(this.now());
    let got: Fetched;
    try { got = await this.fetcher(w.url, w.source); } catch { got = { status: 0, body: null, retryAfter: null }; }
    const t = this.now();
    let e: Entry | null = null;
    if (got.status === 200 && got.body && got.body.length <= BODY_MOST[w.source] && looksRight(w, got.body)) {
      e = { at: t, status: 200, type: w.json ? "application/json" : "image/png", body: got.body };
    } else if (got.status === 404 || (w.source === "rp" && got.status === 400)) {
      e = { at: t, status: 404, type: "application/json", body: enc.encode(JSON.stringify({ error: "not found" })) };
    } else if (got.status === 429) {
      const ra = got.retryAfter != null && got.retryAfter > 0 ? got.retryAfter * 1000 : PAUSE_DEFAULT;
      this.paused[w.source] = t + Math.min(ra, PAUSE_MOST);
    }
    if (e) {
      this.fails.delete(w.key);
      await this.store.put(w.key, e);
      return e;
    }
    const n = (this.fails.get(w.key)?.n || 0) + 1;
    this.fails.set(w.key, { n, next: t + Math.min(FAIL_BASE * 2 ** (n - 1), FAIL_CAP) });
    if (this.fails.size > KEPT_MOST) this.fails.delete(this.fails.keys().next().value!);
    return null;
  }
}

/** Which kept keys the daily prune drops: fetched longer ago than their source's KEEP_DAYS, and past KEPT_MOST the
 *  oldest fetched. */
export function pruned(entries: [string, number][], now: number): string[] {
  const out: string[] = [], left: [string, number][] = [];
  for (const [k, at] of entries) {
    const icon = k.startsWith("item:") || k.startsWith("metric:");
    if (now - at > (icon ? KEEP_DAYS_ICON : KEEP_DAYS) * DAY) out.push(k); else left.push([k, at]);
  }
  if (left.length > KEPT_MOST) {
    left.sort((a, b) => a[1] - b[1]);
    for (const [k] of left.slice(0, left.length - KEPT_MOST)) out.push(k);
  }
  return out;
}

/** One GET with USER_AGENT and nothing else: its status, its body (capped, else null), its Retry-After in seconds. */
export async function httpLookup(url: string, source: Source): Promise<Fetched> {
  const r = await fetch(url, { headers: { "user-agent": USER_AGENT, accept: source === "wom" || source === "rp" ? "application/json" : "image/png" },
                               signal: AbortSignal.timeout(FETCH_TIMEOUT_MS) });
  const ra = parseInt(r.headers.get("retry-after") || "", 10);
  const retryAfter = Number.isFinite(ra) ? ra : null;
  if (r.status !== 200 || +(r.headers.get("content-length") || 0) > BODY_MOST[source]) {
    await r.body?.cancel();
    return { status: r.status === 200 ? 502 : r.status, body: null, retryAfter };
  }
  const reader = r.body!.getReader(), parts: Uint8Array[] = [];
  let n = 0;
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    n += value.length;
    if (n > BODY_MOST[source]) { await reader.cancel(); return { status: 502, body: null, retryAfter }; }
    parts.push(value);
  }
  const out = new Uint8Array(n);
  let o = 0;
  for (const p of parts) { out.set(p, o); o += p.length; }
  return { status: 200, body: out, retryAfter };
}
