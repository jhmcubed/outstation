// The Array's events (11/08/2026, Jace: each outstation on outside.wiki's front page should look alive, its icon popping
// "+1" as it gets a kill, a level, a pet, a big drop, a log slot or a clue; docs/protocol.md "The Array's events"): what's
// happening across the open outstations.
//
//   GET <relay>/array/events?since=<cursor>      {"events": [{"id", "at", "channel", "kind", ...}, ...], "next": <cursor>}
//
// A channel works out what each field report adds (arrayEvents) while it's live and listed (handed out, no watch code:
// on GET /channels), and tells the Directory (Channel.tellArray), which keeps the last ARRAY_KEPT (ArrayFeed) and answers
// the read. So a dark or coded outstation's reports never show. relay-py/relay.py's array_events and ArrayFeed, the same.
// (11/10/2026, Jace: "make the feed keep its events": the Directory holds no sockets, so Cloudflare puts it to sleep
// about 10 seconds after its last request, and the feed had lived only in its memory: a kill showed for those seconds
// and was gone. Now the Directory writes the feed to its storage with each report that adds to it (saved, from), and
// reads it back once a wake.)

import { SKILL } from "./game";
import { oneLine, pyjson } from "./text";

// the last ARRAY_KEPT kept; one channel adds ARRAY_A_MINUTE a minute at most (the rest are dropped); a read
// without since has the last ARRAY_LATEST, one with since at most ARRAY_PAGE after it; a drop goes in only when it's
// worth ARRAY_LOOT or more; a name in an event is ARRAY_WORD characters at most (Dink's are cut to 60 already)
export const ARRAY_KEPT = 500, ARRAY_A_MINUTE = 10, ARRAY_LATEST = 50, ARRAY_PAGE = 100, ARRAY_LOOT = 1_000_000;
const ARRAY_WORD = 60;
const CURSOR = /^[0-9]{1,16}$/; // since, as next gave it (Number.MAX_SAFE_INTEGER at most)

/** An event as a channel makes it: its kind and what goes with it (the feed adds its id, time and channel). */
export type ArrayEvent = { kind: string } & Record<string, string | number>;
/** An event as the feed keeps it. */
export type Kept = { id: number; at: number; channel: string } & ArrayEvent;

const isObj = (v: unknown): v is Record<string, unknown> => !!v && typeof v === "object" && !Array.isArray(v);

/** A number an event may carry: a whole one, 0 to Number.MAX_SAFE_INTEGER, or undefined. */
function whole(v: unknown): number | undefined {
  return typeof v === "number" && Number.isInteger(v) && v >= 0 && v <= Number.MAX_SAFE_INTEGER ? v + 0 : undefined;
}

/** A name an event may carry: one line, ARRAY_WORD characters at most, or undefined. */
function word(v: unknown): string | undefined {
  return oneLine(v, ARRAY_WORD) || undefined;
}

/** The fields given (an undefined one left out). */
function given(e: Record<string, string | number | undefined>): ArrayEvent {
  const out: Record<string, string | number> = {};
  for (const k of Object.keys(e)) if (e[k] !== undefined) out[k] = e[k] as string | number;
  return out as ArrayEvent;
}

/** What one field report adds to the Array's events, from its type and the numbers it carries (its data, as fold reads
 *  them): a kill (the boss and its count), a level for each skill levelled, a pet (its name if Dink gave one), a drop
 *  worth ARRAY_LOOT or more (the best item's name and the drop's whole value), a collection log slot (the item) and a
 *  clue (its tier and how many, if given). Anything else adds nothing. */
export function arrayEvents(report: unknown): ArrayEvent[] {
  if (!isObj(report)) return [];
  const d = isObj(report.data) ? report.data : {};
  switch (report.type) {
    case "KILL_COUNT": {
      const boss = word(d.boss), kc = whole(d.count);
      return boss && kc !== undefined ? [{ kind: "kill", boss, kc }] : [];
    }
    case "LEVEL": {
      const out: ArrayEvent[] = [];
      for (const [k, v] of Object.entries(isObj(d.skills) ? d.skills : {})) {
        const skill = word(k), level = whole(v);
        if (skill && SKILL.test(skill) && level !== undefined) out.push({ kind: "level", skill, level });
      }
      return out;
    }
    case "PET":
      return [given({ kind: "pet", name: word(d.pet) })];
    case "LOOT": {
      const value = whole(d.value);
      if (value === undefined || value < ARRAY_LOOT) return [];
      let name: string | undefined, most = -1;
      for (const i of Array.isArray(d.items) ? d.items : []) {
        if (!isObj(i)) continue;
        const n = word(i.name), worth = (whole(i.quantity) || 0) * (whole(i.price) || 0);
        if (n && worth > most) { name = n; most = worth; }
      }
      return [given({ kind: "loot", name, value })];
    }
    case "COLLECTION":
      return [given({ kind: "clog", name: word(d.item) })];
    case "CLUE":
      return [given({ kind: "clue", tier: word(d.tier), count: whole(d.count) })];
  }
  return [];
}

/** The feed as the Directory keeps it in storage ("array"): the events, the newest id given, and each channel's times of
 *  the last minute (so ARRAY_A_MINUTE holds across a sleep). One value, so a report that adds to it is one row written. */
export type SavedFeed = { last: number; events: Kept[]; recent: [string, number[]][] };

/** The Array's events: the last ARRAY_KEPT, oldest first. An id goes up with each event, and is about the moment's ms
 *  (one more than the last, when that's later), so ids keep going up after the Directory starts again (the newest is
 *  kept with them) and a page's cursor (since) still works. */
export class ArrayFeed {
  events: Kept[] = [];
  last: number; // the newest id given, or when the feed began
  recent = new Map<string, number[]>(); // a channel -> when (ms) its events of the last minute came (ARRAY_A_MINUTE)

  constructor(now = Date.now()) {
    this.last = now;
  }

  /** The feed from what was saved (none: a new one, beginning now). A saved one that isn't its shape starts afresh,
   *  its newest id kept if it has one. */
  static from(saved: unknown, now = Date.now()): ArrayFeed {
    const s = isObj(saved) ? saved : {}, f = new ArrayFeed(now);
    if (typeof s.last === "number" && Number.isSafeInteger(s.last)) f.last = Math.max(f.last, s.last);
    if (Array.isArray(s.events)) f.events = (s.events as Kept[]).filter((e) => isObj(e) && Number.isSafeInteger(e.id)).slice(-ARRAY_KEPT);
    if (f.events.length) f.last = Math.max(f.last, f.events[f.events.length - 1].id);
    if (Array.isArray(s.recent)) {
      for (const r of s.recent) if (Array.isArray(r) && typeof r[0] === "string" && Array.isArray(r[1])) f.recent.set(r[0], r[1]);
    }
    return f;
  }

  /** What the Directory writes to storage: the whole feed, as one value. */
  saved(): SavedFeed {
    return { last: this.last, events: this.events, recent: [...this.recent] };
  }

  /** A channel's events into the feed, as many as ARRAY_A_MINUTE leaves room for (the rest dropped); those kept. */
  add(channel: string, events: ArrayEvent[], now = Date.now()): Kept[] {
    for (const [c, t] of this.recent) if (now - t[t.length - 1] >= 60_000) this.recent.delete(c);
    const times = (this.recent.get(channel) || []).filter((t) => now - t < 60_000);
    const added: Kept[] = [];
    for (const e of events) {
      if (times.length >= ARRAY_A_MINUTE) break;
      times.push(now);
      this.last = Math.max(now, this.last + 1);
      added.push({ id: this.last, at: now, channel, ...e });
    }
    this.events.push(...added);
    if (this.events.length > ARRAY_KEPT) this.events.splice(0, this.events.length - ARRAY_KEPT);
    if (times.length) this.recent.set(channel, times);
    return added;
  }

  /** The keeper's backfill (arrayBackfill's events, for a channel handed out and listed): each put in its place by
   *  when it happened, with an id between its neighbours' (its own ms when that fits), so the feed stays in id order
   *  and every cursor still works. One with no room there (its neighbours' ids one apart) gets a new id at the end, as
   *  a new event would, and keeps its own time. Counted against nothing. None older than the oldest kept when the feed
   *  is full (it would go at once): the events kept, or why not (and nothing added). */
  backfill(channel: string, lost: Lost[], now = Date.now()): { events: Kept[] } | { error: string } {
    if (this.events.length >= ARRAY_KEPT && lost.some((e) => e.at < this.events[0].at)) {
      return { error: `older than the feed holds now (its oldest is from ${this.events[0].at})` };
    }
    const kept: Kept[] = [];
    for (const e of [...lost].sort((a, b) => a.at - b.at)) {
      const { at, ...rest } = e;
      let i = this.events.length;
      while (i > 0 && this.events[i - 1].at > at) i--;
      const lo = i > 0 ? this.events[i - 1].id : 0, hi = i < this.events.length ? this.events[i].id : Infinity;
      let id = Math.min(Math.max(at, lo + 1), hi - 1);
      if (id <= lo) { // (no room between its neighbours: a new id, at the end)
        id = this.last = Math.max(now, this.last + 1);
        i = this.events.length;
      }
      this.last = Math.max(this.last, id);
      const k = { id, at, channel, ...rest } as Kept;
      this.events.splice(i, 0, k);
      kept.push(k);
    }
    if (this.events.length > ARRAY_KEPT) this.events.splice(0, this.events.length - ARRAY_KEPT);
    return { events: kept };
  }

  /** GET /array/events: without since the last ARRAY_LATEST, with it the first ARRAY_PAGE after it; next, the cursor for
   *  the next read (the last event's id, or with none, since, or the newest id when since is past it). */
  read(since?: number): { events: Kept[]; next: number } {
    const got = since === undefined ? this.events.slice(-ARRAY_LATEST)
      : this.events.filter((e) => e.id > since).slice(0, ARRAY_PAGE);
    const next = got.length ? got[got.length - 1].id : since === undefined ? this.last : Math.min(since, this.last);
    return { events: got, next };
  }
}

/** since, from the read's address: undefined for none (or ""), null for one that isn't a cursor (400). */
export function arraySince(raw: string | null): number | undefined | null {
  if (!raw) return undefined;
  const n = Number(raw);
  return CURSOR.test(raw) && n <= Number.MAX_SAFE_INTEGER ? n : null;
}

/** The keeper's backfill (POST /admin/array, 11/10/2026, Jace: a pet lost to the Directory's sleeps, put back on the
 *  record): {"channel", "events": [{"kind", ...its fields, "at": <ms>}]}. Each event exactly as arrayEvents would have
 *  made it (the same kinds, the same fields, each field as it would have been: a name one line of ARRAY_WORD at most, a
 *  number whole; a kill's boss and count, a level's skill and level, and a drop's value of ARRAY_LOOT or more, needed;
 *  nothing else), with when it happened, not after now. ARRAY_BACKFILL at most at once. The events, or why not. */
export const ARRAY_BACKFILL = 50;
const KINDS: Record<string, [string, "word" | "whole" | "skill" | "loot", boolean][]> = {
  kill: [["boss", "word", true], ["kc", "whole", true]],
  level: [["skill", "skill", true], ["level", "whole", true]],
  pet: [["name", "word", false]],
  loot: [["name", "word", false], ["value", "loot", true]],
  clog: [["name", "word", false]],
  clue: [["tier", "word", false], ["count", "whole", false]],
};
export type Lost = ArrayEvent & { at: number };

export function arrayBackfill(body: unknown, now = Date.now()): { events: Lost[] } | { error: string } {
  const list = isObj(body) ? body.events : undefined;
  if (!Array.isArray(list) || !list.length) return { error: "events: a list of one event or more" };
  if (list.length > ARRAY_BACKFILL) return { error: `${ARRAY_BACKFILL} events at most at once` };
  const out: Lost[] = [];
  for (const [n, e] of list.entries()) {
    const no = (why: string) => ({ error: `event ${n + 1}: ${why}` });
    if (!isObj(e)) return no("not an event");
    const fields = typeof e.kind === "string" && Object.hasOwn(KINDS, e.kind) ? KINDS[e.kind] : undefined;
    if (!fields) return no("kind is one of " + Object.keys(KINDS).join(", "));
    const at = whole(e.at);
    if (at === undefined) return no("at: when it happened, in ms");
    if (at > now) return no("at is after now");
    for (const k of Object.keys(e)) if (k !== "kind" && k !== "at" && !fields.some(([f]) => f === k)) return no(`a ${e.kind} has no ${k}`);
    const ev: Record<string, string | number> = { kind: e.kind as string };
    for (const [f, what, needed] of fields) {
      const v = e[f];
      if (v === undefined) { if (needed) return no(`a ${e.kind} needs its ${f}`); continue; }
      const ok = what === "whole" ? whole(v) !== undefined
        : what === "loot" ? whole(v) !== undefined && (v as number) >= ARRAY_LOOT
        : typeof v === "string" && word(v) === v && (what !== "skill" || SKILL.test(v));
      if (!ok) {
        return no(what === "loot" ? `value: a whole number, ${ARRAY_LOOT} or more` : what === "whole" ? `${f}: a whole number`
                  : `${f}: one line of ${ARRAY_WORD} characters at most${what === "skill" ? ", a skill's name" : ""}`);
      }
      ev[f] = v as string | number;
    }
    out.push({ ...(ev as ArrayEvent), at });
  }
  return { events: out };
}

/** The read's answers: any page may read them (any origin), and they're never cached. */
export function arrayAnswer(body: unknown, status = 200): Response {
  const headers: Record<string, string> = { "access-control-allow-origin": "*", "cache-control": "no-cache" };
  if (body !== null) headers["content-type"] = "application/json";
  return new Response(body === null ? null : pyjson(body), { status, headers });
}
