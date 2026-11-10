// Your year (11/10/2026; docs/record.md, docs/protocol.md "Your year"): each player's record of their own travels and
// activity, kept encrypted with a key only their own computers hold, and the public summary they may choose to post.
//
//   POST   /record/<channel>/token?key=<station key>  {"read", "write"}   the tokens' SHA-256 kept (RECORD_KEYS pairs)
//   PUT    /record/<channel>/<id>?key=<station key>    a sealed chunk     stored (replacing one of that id)
//   DELETE /record/<channel>/<id>?key=<station key>                       one chunk gone
//   DELETE /record/<channel>?key=<station key>                            everything gone: chunks, tokens, the summary
//   GET    /record/<channel>[?data=1&after=<id>]       Authorization: Bearer <read token>
//   PUT    /year/<channel>    ?key=<station key> or Authorization: Bearer <write token>   the public summary (checked)
//   GET    /year/<channel>                                                anyone's
//   DELETE /year/<channel>    the same as PUT
//
// One Durable Object a channel (RECORD, "Records"), apart from its Channel: a page reading a year never wakes or slows a
// live channel, and a new station key leaves the record as it was. The Worker (index.ts) checks the station key with
// the channel (/_keyok) and tells this object so (x-station: 1); the tokens are checked here. Only ciphertext is ever
// stored here: a chunk is checked to be sealed (its first byte the format, 1, and long enough for a nonce and a tag),
// and a plaintext one (starting "{") is refused. relay-py/relay.py's, the same.

import { DurableObject } from "cloudflare:workers";
import { ACTIVITY_BOSSES, ACTIVITY_MINIGAMES, ACTIVITY_RAIDS, ACTIVITY_SKILLS, WHERE_X, WHERE_Y, WHERE_ZANARIS, gameActivity, gameWhere } from "./game";
import { arrayBackfill } from "./array";
import { pyjson } from "./text";

export const RECORD_CHUNK = 256 * 1024; // a sealed chunk at most
export const RECORD_CHUNK_LEAST = 1 + 12 + 1 + 16; // the format, the nonce, a byte, the tag
export const RECORD_CHUNKS = 2000, RECORD_BYTES = 32 * 1024 * 1024, RECORD_DAYS = 400, RECORD_AN_HOUR = 240;
export const RECORD_KEYS = 4; // token pairs registered at most (a key pasted wrong on one PC never wipes another's)
export const RECORD_PAGE = 4 * 1024 * 1024; // a ?data=1 answer's chunks, in bytes of data, at most
export const CHUNK_ID = /^[A-Za-z0-9_-]{22}$/;
export const TOKEN = /^[A-Za-z0-9_-]{43}$/;
export const YEAR_MOST = 5 * 1024 * 1024; // a public summary's body at most (every part at its most fits: docs/record.md)
const DAY = 86_400_000;

const isObj = (v: unknown): v is Record<string, unknown> => !!v && typeof v === "object" && !Array.isArray(v);
const whole = (v: unknown, lo: number, hi: number): v is number => typeof v === "number" && Number.isInteger(v) && v >= lo && v <= hi;

// ---- the public summary ----

export const YEAR_PARTS = ["v", "tz", "range", "totals", "days", "heat", "paths", "activities", "hours", "places", "moments", "log", "replay"];
export const YEAR_LOG = 2000; // the log diary's slots at most (11/10/2026)
const CLIP = /^[A-Za-z0-9_-]{16,32}$/; // a clip's id on the channel's page (clips.ts's ID)
export const YEAR_DAYS = 400, YEAR_TZ = /^(?:UTC|[A-Za-z][A-Za-z0-9_+-]{0,29}(?:\/[A-Za-z0-9][A-Za-z0-9_+-]{0,29}){1,2})$/, YEAR_TZ_MOST = 64;
const DATE = /^([0-9]{4})-([0-9]{2})-([0-9]{2})$/;
export const YEAR_TOTALS = ["minutes", "live_minutes", "days", "squares", "kills", "levels", "pets", "drops", "clogs", "clues"];
// (11/10/2026, for the exact path: heat in cells as fine as 8 tiles, paths in exact tiles. A busy year measured 27,000
// 8-tile cells touched and 210,000 exact steps, so the heat takes 32,768 cells and the paths 50,000 steps, thinned by
// the page; docs/record.md, "The public summary")
export const YEAR_HEAT = 32_768, YEAR_PATHS = 4096, YEAR_PATH_STEPS = 5000, YEAR_STEPS = 50_000, YEAR_REPLAY = 20_000, YEAR_LIST = 64;
export const YEAR_CELLS = [8, 16, 32, 64, 128];
const TOTAL_MOST = 1_000_000_000, MINUTES_MOST = 10_000_000, T_LEAST = 1_577_836_800_000; // (2020-01-01)

class No { why: string; constructor(why: string) { this.why = why; } }

/** An activity as a short closed label (11/10/2026: on heat cells, paths and days): "idle", "fighting", or
 *  "skilling:<skill>", "boss:<boss>", "raid:<raid>", "minigame:<minigame>" from the plugin's lists (game.ts). */
export function actLabel(v: unknown): boolean {
  if (v === "idle" || v === "fighting") return true;
  if (typeof v !== "string") return false;
  const i = v.indexOf(":"), kind = v.slice(0, i), name = v.slice(i + 1);
  const lists: Record<string, string[]> = { skilling: ACTIVITY_SKILLS, boss: ACTIVITY_BOSSES, raid: ACTIVITY_RAIDS, minigame: ACTIVITY_MINIGAMES };
  return i > 0 && Object.hasOwn(lists, kind) && lists[kind].includes(name);
}
function act(v: unknown, part: string): string {
  if (!actLabel(v)) throw new No(`${part}: act is "idle", "fighting" or "<skilling|boss|raid|minigame>:<one the plugin names>"`);
  return v as string;
}

/** A square's middle as following's "where" takes it (the main map's, or Zanaris), or throws. */
function square(x: unknown, y: unknown, part: string): [number, number] {
  const w = gameWhere({ where: { x, y } });
  if (!w) throw new No(`${part}: a square's middle, as following's "where" gives it`);
  return [w.x, w.y];
}
function time(v: unknown, part: string, now: number): number {
  if (!whole(v, T_LEAST, now + DAY)) throw new No(`${part}: a time in ms, from 2020 to a day past now`);
  return v;
}
function minutes(v: unknown, part: string): number {
  if (!whole(v, 0, MINUTES_MOST)) throw new No(`${part}: whole minutes, 0 to ${MINUTES_MOST}`);
  return v;
}
function list(v: unknown, most: number, part: string): unknown[] {
  if (!Array.isArray(v)) throw new No(`${part}: a list`);
  if (v.length > most) throw new No(`${part}: ${most} at most`);
  return v;
}
function only(o: Record<string, unknown>, keys: string[], part: string) {
  for (const k of Object.keys(o)) if (!keys.includes(k)) throw new No(`${part}: no ${k} here`);
}
/** A step: [x, y] or [x, y, t]. */
/** A cell's middle at this size (k*cell + cell/2), within the main map and its seas, or Zanaris's one place. */
function cellMid(x: unknown, y: unknown, cell: number, part: string): [number, number] {
  if (x === WHERE_ZANARIS.x && y === WHERE_ZANARIS.y) return [x, y];
  const mid = (v: unknown, [lo, hi]: number[]) => typeof v === "number" && Number.isInteger(v) && v >= lo && v < hi && v % cell === cell / 2;
  if (!mid(x, WHERE_X) || !mid(y, WHERE_Y)) throw new No(`${part}: a ${cell}-tile cell's middle (x and y each k * ${cell} + ${cell / 2}) on the main map, or Zanaris`);
  return [x as number, y as number];
}
/** A tile of the main map and its seas (as the exact path's "here"), or Zanaris's one place. */
function tile(x: unknown, y: unknown, part: string): [number, number] {
  if (x === WHERE_ZANARIS.x && y === WHERE_ZANARIS.y) return [x, y];
  const ok = (v: unknown, [lo, hi]: number[]) => typeof v === "number" && Number.isInteger(v) && v >= lo && v < hi;
  if (!ok(x, WHERE_X) || !ok(y, WHERE_Y)) throw new No(`${part}: a tile on the main map (x ${WHERE_X[0]} up to ${WHERE_X[1]}, y ${WHERE_Y[0]} up to ${WHERE_Y[1]}), or Zanaris`);
  return [x as number, y as number];
}
function step(s: unknown, part: string, now: number): number[] {
  if (!Array.isArray(s) || (s.length !== 2 && s.length !== 3)) throw new No(`${part}: each step [x, y] or [x, y, t]`);
  const xy = tile(s[0], s[1], part);
  return s.length === 3 ? [...xy, time(s[2], part, now)] : xy;
}

/** A public summary checked (docs/record.md, "The public summary"): the summary as it's stored, its parts in their
 *  order, or why not (the part named). relay.py's year_summary, the same. */
export function yearSummary(body: unknown, now = Date.now()): { year: Record<string, unknown> } | { error: string } {
  try {
    if (!isObj(body)) throw new No("a year is an object of its parts");
    for (const k of Object.keys(body)) if (!YEAR_PARTS.includes(k)) throw new No(`${k}: not a part of a year`);
    if (body.v !== 1) throw new No("v: 1");
    const out: Record<string, unknown> = { v: 1 };
    if ("tz" in body) {
      const z = body.tz;
      const ok = typeof z === "string" ? z.length <= YEAR_TZ_MOST && YEAR_TZ.test(z) : whole(z, -840, 840);
      if (!ok) {
        throw new No("tz: an IANA time zone's name (\"Europe/London\") or a UTC offset in whole minutes, -840 to 840");
      }
      out.tz = z;
    }
    if ("range" in body) {
      const r = body.range;
      if (!isObj(r)) throw new No("range: {from, to}");
      only(r, ["from", "to"], "range");
      const from = time(r.from, "range", now), to = time(r.to, "range", now);
      if (from > to) throw new No("range: from comes before to");
      out.range = { from, to };
    }
    if ("totals" in body) {
      const t = body.totals;
      if (!isObj(t)) throw new No("totals: an object");
      only(t, YEAR_TOTALS, "totals");
      const o: Record<string, number> = {};
      for (const k of YEAR_TOTALS) {
        if (!(k in t)) continue;
        if (!whole(t[k], 0, TOTAL_MOST)) throw new No(`totals: ${k} a whole number, 0 to ${TOTAL_MOST}`);
        o[k] = t[k] as number;
      }
      out.totals = o;
    }
    if ("days" in body) {
      const seen = new Set<string>();
      out.days = list(body.days, YEAR_DAYS, "days").map((d) => {
        if (!isObj(d)) throw new No("days: each {date, minutes, act?}");
        only(d, ["date", "minutes", "act"], "days");
        const m = typeof d.date === "string" ? DATE.exec(d.date) : null;
        const ms = m ? Date.UTC(+m[1], +m[2] - 1, +m[3]) : NaN;
        if (!m || new Date(ms).toISOString().slice(0, 10) !== d.date || ms < T_LEAST || ms > now + DAY) {
          throw new No("days: date a day as YYYY-MM-DD, from 2020 to a day past now");
        }
        if (seen.has(d.date as string)) throw new No("days: a date twice");
        seen.add(d.date as string);
        if (!whole(d.minutes, 0, 1440)) throw new No("days: minutes a whole number, 0 to 1440");
        const o: Record<string, unknown> = { date: d.date, minutes: d.minutes };
        if ("act" in d) o.act = act(d.act, "days");
        return o;
      });
    }
    if ("heat" in body) {
      // (its cells as a list (128 tiles, as before 11/10/2026), or {"cell": 8|16|32|64|128, "cells": [...]})
      const h = body.heat, seen = new Set<string>();
      if (isObj(h)) {
        only(h, ["cell", "cells"], "heat");
        if (!YEAR_CELLS.includes(h.cell as number)) throw new No("heat: cell is 8, 16, 32, 64 or 128 tiles");
      }
      const size = isObj(h) ? (h.cell as number) : 128;
      const cells = list(isObj(h) ? h.cells : h, YEAR_HEAT, "heat").map((c) => {
        if (!Array.isArray(c) || (c.length !== 3 && c.length !== 4)) throw new No("heat: each [x, y, minutes] or [x, y, minutes, act]");
        const xy = cellMid(c[0], c[1], size, "heat");
        if (seen.has(xy.join())) throw new No("heat: a cell twice");
        seen.add(xy.join());
        const cell: (number | string)[] = [...xy, minutes(c[2], "heat")];
        if (c.length === 4) cell.push(act(c[3], "heat"));
        return cell;
      });
      out.heat = isObj(h) ? { cell: size, cells } : cells;
    }
    if ("paths" in body) {
      let n = 0;
      out.paths = list(body.paths, YEAR_PATHS, "paths").map((p) => {
        // (a path is its steps, or {"steps", "act"}: 11/10/2026)
        if (isObj(p)) only(p, ["steps", "act"], "paths");
        const steps = list(isObj(p) ? p.steps : p, YEAR_PATH_STEPS, "paths");
        if (steps.length < 2) throw new No("paths: two steps or more each");
        n += steps.length;
        if (n > YEAR_STEPS) throw new No(`paths: ${YEAR_STEPS} steps at most in all`);
        const done = steps.map((s) => step(s, "paths", now));
        if (!isObj(p)) return done;
        return "act" in p ? { steps: done, act: act(p.act, "paths") } : { steps: done };
      });
    }
    if ("activities" in body) {
      out.activities = list(body.activities, YEAR_LIST, "activities").map((a) => {
        if (!isObj(a)) throw new No("activities: each {activity, minutes}");
        only(a, ["activity", "minutes"], "activities");
        const label = gameActivity(a);
        if (!label) throw new No("activities: an activity as the plugin names one");
        return { activity: label, minutes: minutes(a.minutes, "activities") };
      });
    }
    if ("hours" in body) {
      const h = list(body.hours, 24, "hours");
      if (h.length !== 24) throw new No("hours: 24 of them, one for each hour of the day");
      out.hours = h.map((m) => minutes(m, "hours"));
    }
    if ("places" in body) {
      out.places = list(body.places, YEAR_LIST, "places").map((p) => {
        if (!isObj(p)) throw new No("places: each {x, y, minutes, first?, last?}");
        only(p, ["x", "y", "minutes", "first", "last"], "places");
        const [x, y] = square(p.x, p.y, "places");
        const o: Record<string, number> = { x, y, minutes: minutes(p.minutes, "places") };
        if ("first" in p) o.first = time(p.first, "places", now);
        if ("last" in p) o.last = time(p.last, "places", now);
        if (o.first !== undefined && o.last !== undefined && o.first > o.last) throw new No("places: first comes before last");
        return o;
      });
    }
    if ("moments" in body) {
      out.moments = list(body.moments, YEAR_LIST, "moments").map((m) => {
        if (!isObj(m) || !isObj(m.event)) throw new No("moments: each {event, t?}");
        only(m, ["event", "t"], "moments");
        if ("at" in m.event) throw new No("moments: an event has no at (t is its time)");
        const got = arrayBackfill({ events: [{ ...m.event, at: 0 }] }, now);
        if ("error" in got) throw new No("moments: " + got.error.replace(/^event 1: /, ""));
        const { at: _at, ...event } = got.events[0];
        return "t" in m ? { event, t: time(m.t, "moments", now) } : { event };
      });
    }
    if ("log" in body) {
      // the log diary (11/10/2026): collection log slots, each field optional; a place no finer than the heat's cells
      const h = out.heat, size = isObj(h) ? (h.cell as number) : 128;
      out.log = list(body.log, YEAR_LOG, "log").map((e) => {
        if (!isObj(e)) throw new No("log: each {name?, t?, x?, y?, act?, clip?}");
        only(e, ["name", "t", "x", "y", "act", "clip"], "log");
        const o: Record<string, unknown> = {};
        if ("name" in e) {
          const got = arrayBackfill({ events: [{ kind: "clog", name: e.name, at: 0 }] }, now);
          if ("error" in got) throw new No("log: name one line of 60 characters at most");
          o.name = e.name;
        }
        if ("t" in e) o.t = time(e.t, "log", now);
        if ("x" in e || "y" in e) {
          if (!("x" in e && "y" in e)) throw new No("log: x and y together");
          [o.x, o.y] = cellMid(e.x, e.y, size, "log"); // (the heat's own cell size, or 128 without a heat: never finer)
        }
        if ("act" in e) o.act = act(e.act, "log");
        if ("clip" in e) {
          if (typeof e.clip !== "string" || !CLIP.test(e.clip)) throw new No("log: clip a clip's id, 16 to 32 base64url characters");
          o.clip = e.clip;
        }
        return o;
      });
    }
    if ("replay" in body) out.replay = list(body.replay, YEAR_REPLAY, "replay").map((s) => step(s, "replay", now));
    return { year: out };
  } catch (e) {
    if (e instanceof No) return { error: e.why };
    throw e;
  }
}

// ---- the object ----

export interface RecordEnv {
  RECORD_CHUNKS?: string; // (tests) a channel's chunks at most (RECORD_CHUNKS)
}

type Meta = { at: number; size: number };
type Pair = { read: string; write: string }; // the tokens' SHA-256, hex

async function sha256(text: string): Promise<string> {
  const d = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text));
  return [...new Uint8Array(d)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

export const CORS = { "access-control-allow-origin": "*" };
export function answer(body: unknown, status = 200): Response {
  if (body === null) return new Response(null, { status, headers: { ...CORS, "cache-control": "no-store" } });
  return new Response(typeof body === "string" ? body : pyjson(body),
                      { status, headers: { ...CORS, "content-type": "application/json", "cache-control": "no-store" } });
}
const no = (status: number, error: string) => answer({ error }, status);

function bearer(request: Request): string {
  const m = /^Bearer (.+)$/.exec(request.headers.get("authorization") || "");
  return m ? m[1].trim() : "";
}

function base64(bytes: Uint8Array): string {
  let s = "";
  for (let i = 0; i < bytes.length; i += 0x8000) s += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
  return btoa(s);
}

export class Records extends DurableObject<RecordEnv> {
  changes: number[] = []; // when its last uploads and deletes came (RECORD_AN_HOUR; in memory: a sleep only loosens it)

  /** Whether a token is one registered ("read" or "write"). */
  async tokenOk(token: string, which: "read" | "write"): Promise<boolean> {
    if (!TOKEN.test(token)) return false;
    const pairs = (await this.ctx.storage.get<Pair[]>("tokens")) || [], h = await sha256(token);
    return pairs.some((p) => p[which] === h);
  }

  /** The chunks' entries, without any not uploaded again in RECORD_DAYS (those taken out of storage). */
  async metas(now = Date.now()): Promise<Map<string, Meta>> {
    const all = await this.ctx.storage.list<Meta>({ prefix: "m:" }), out = new Map<string, Meta>(), old: string[] = [];
    for (const [k, m] of all) {
      if (now - m.at > RECORD_DAYS * DAY) old.push(k.slice(2));
      else out.set(k.slice(2), m);
    }
    for (let i = 0; i < old.length; i += 64) await this.ctx.storage.delete(old.slice(i, i + 64).flatMap((id) => ["m:" + id, "d:" + id]));
    return out;
  }

  /** One more upload or delete this hour, or false past RECORD_AN_HOUR. */
  counted(now: number): boolean {
    this.changes = this.changes.filter((t) => now - t < 3_600_000);
    if (this.changes.length >= RECORD_AN_HOUR) return false;
    this.changes.push(now);
    return true;
  }

  async fetch(request: Request): Promise<Response> {
    const url = new URL(request.url), q = url.searchParams, station = request.headers.get("x-station") === "1";
    const what = url.pathname.slice(1), id = q.get("id") || "", now = Date.now();

    if (what === "token") {
      if (!station) return no(403, "wrong key for this channel");
      const b = (await request.json().catch(() => null)) as { read?: unknown; write?: unknown } | null;
      if (!b || typeof b.read !== "string" || typeof b.write !== "string" || !TOKEN.test(b.read) || !TOKEN.test(b.write)) {
        return no(400, "the tokens: read and write, 43 base64url characters each");
      }
      const pair = { read: await sha256(b.read), write: await sha256(b.write) };
      const pairs = (await this.ctx.storage.get<Pair[]>("tokens")) || [];
      if (pairs.some((p) => p.read === pair.read && p.write === pair.write)) return answer(null, 204);
      if (pairs.length >= RECORD_KEYS) return no(409, `${RECORD_KEYS} record keys at most: delete the relay's copy to start again`);
      await this.ctx.storage.put("tokens", [...pairs, pair]);
      return answer(null, 204);
    }

    if (what === "chunk") {
      if (!station) return no(403, "wrong key for this channel");
      if (!CHUNK_ID.test(id)) return no(400, "a chunk's id: 22 base64url characters");
      if (request.method === "DELETE") {
        if (!this.counted(now)) return no(429, `${RECORD_AN_HOUR} uploads and deletes an hour at most: try again later`);
        if (!(await this.ctx.storage.get("m:" + id))) return no(404, "no such chunk");
        await this.ctx.storage.delete(["m:" + id, "d:" + id]);
        return answer(null, 204);
      }
      const body = new Uint8Array(await request.arrayBuffer());
      if (body.length > RECORD_CHUNK) return no(413, `a chunk is ${RECORD_CHUNK / 1024} KB at most`);
      if (body.length < RECORD_CHUNK_LEAST || body[0] !== 1) return no(415, "not a sealed chunk: the relay keeps only what's encrypted");
      if (!this.counted(now)) return no(429, `${RECORD_AN_HOUR} uploads and deletes an hour at most: try again later`);
      const metas = await this.metas(now), had = metas.get(id);
      const most = parseInt(this.env.RECORD_CHUNKS || "") || RECORD_CHUNKS;
      if (!had && metas.size >= most) return no(507, `the relay's copy is full (${most} chunks)`);
      let bytes = body.length;
      for (const [k, m] of metas) if (k !== id) bytes += m.size;
      if (bytes > RECORD_BYTES) return no(507, `the relay's copy is full (${RECORD_BYTES / 1048576} MB)`);
      const meta: Meta = { at: now, size: body.length };
      await this.ctx.storage.put({ ["m:" + id]: meta, ["d:" + id]: body });
      return answer({ id, at: meta.at, size: meta.size }, 201);
    }

    if (what === "all") {
      if (!station) return no(403, "wrong key for this channel");
      await this.ctx.storage.deleteAll();
      return answer(null, 204);
    }

    if (what === "list") {
      if (!(await this.tokenOk(bearer(request), "read"))) return no(403, "a read token: the record key's, registered by the app");
      const metas = [...(await this.metas(now))].sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0));
      const bytes = metas.reduce((s, [, m]) => s + m.size, 0);
      if (q.get("data") !== "1") return answer({ chunks: metas.map(([i, m]) => ({ id: i, at: m.at, size: m.size })), bytes });
      const after = q.get("after") || "", rows: Record<string, unknown>[] = [];
      let taken = 0, next: string | null = null;
      for (const [i, m] of metas) {
        if (after && i <= after) continue;
        if (rows.length && taken + m.size > RECORD_PAGE) { next = rows[rows.length - 1].id as string; break; }
        const d = await this.ctx.storage.get<Uint8Array | ArrayBuffer>("d:" + i);
        if (!d) continue;
        taken += m.size;
        rows.push({ id: i, at: m.at, size: m.size, data: base64(d instanceof Uint8Array ? d : new Uint8Array(d)) });
      }
      return answer({ chunks: rows, bytes, next });
    }

    if (what === "year") {
      if (request.method === "GET") {
        const y = await this.ctx.storage.get<{ text: string; at: number }>("year");
        return y ? answer('{"year": ' + y.text + ', "at": ' + y.at + "}") : no(404, "no year posted for that outstation");
      }
      if (!station && !(await this.tokenOk(bearer(request), "write"))) return no(403, "the station key, or the record key's write token");
      if (request.method === "DELETE") {
        await this.ctx.storage.delete("year");
        return answer(null, 204);
      }
      const raw = await request.text();
      if (raw.length > YEAR_MOST) return no(413, `a year is ${YEAR_MOST / 1024} KB at most`);
      let body: unknown;
      try { body = JSON.parse(raw); } catch { return no(400, "a year is JSON"); }
      const got = yearSummary(body, now);
      if ("error" in got) return no(400, got.error);
      const text = pyjson(got.year);
      await this.ctx.storage.put("year", { text, at: now });
      return answer('{"year": ' + text + ', "at": ' + now + "}");
    }
    return no(404, "no such thing here");
  }
}
