// The Array's events (11/08/2026, Jace: each outstation on outside.wiki's front page should look alive, its icon popping
// "+1" as it gets a kill, a level, a pet, a big drop, a log slot or a clue; docs/protocol.md "The Array's events"): what's
// happening across the open outstations.
//
//   GET <relay>/array/events?since=<cursor>      {"events": [{"id", "at", "channel", "kind", ...}, ...], "next": <cursor>}
//
// A channel works out what each field report adds (arrayEvents) while it's live and listed (handed out, no watch code:
// on GET /channels), and tells the Directory (Channel.tellArray), which keeps the last ARRAY_KEPT in memory (ArrayFeed)
// and answers the read. So a dark or coded outstation's reports never show. relay-py/relay.py's array_events and
// ArrayFeed, the same.

import { SKILL } from "./game";
import { oneLine, pyjson } from "./text";

// the last ARRAY_KEPT kept, in memory; one channel adds ARRAY_A_MINUTE a minute at most (the rest are dropped); a read
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

/** The Array's events: the last ARRAY_KEPT, oldest first, in memory only (an eviction of the Directory forgets them). An
 *  id goes up with each event, and is about the moment's ms (one more than the last, when that's later), so ids keep
 *  going up after the Directory starts again and a page's cursor (since) still works. */
export class ArrayFeed {
  events: Kept[] = [];
  last: number; // the newest id given, or when the feed began
  recent = new Map<string, number[]>(); // a channel -> when (ms) its events of the last minute came (ARRAY_A_MINUTE)

  constructor(now = Date.now()) {
    this.last = now;
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

/** The read's answers: any page may read them (any origin), and they're never cached. */
export function arrayAnswer(body: unknown, status = 200): Response {
  const headers: Record<string, string> = { "access-control-allow-origin": "*", "cache-control": "no-cache" };
  if (body !== null) headers["content-type"] = "application/json";
  return new Response(body === null ? null : pyjson(body), { status, headers });
}
