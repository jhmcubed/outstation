// Recorded fights on a player's Outside page (11/20/2026; ghillie's claude/outstation.md, "Fight replays and trophies";
// Jace: "a section on your profile that lists recent encounters in case you want to watch one back"). A fight is an
// Encounter Recorder file (the Plugin Hub's plugin by Mr Gnome; its docs/FORMAT.md), dropped by the player on their own
// page and stripped there first (ghillie's strip.js: other players' names, other people's chat, exact XP, mouse
// presses), then played back on outside.wiki/<name>/fight/<id> in the page's own 2D and 3D replay. docs/protocol.md,
// "Recorded fights".
//
//   GET    /fights/<channel>?code=<watch code>       {"fights": [Fight, ...] newest first, "history": [TimeEntry, ...]
//                                                    oldest first, "public_default", "most", "days", "kept"} (the owner's,
//                                                    with Authorization: Bearer <tend token> or ?key=, also lists the
//                                                    fights they've kept to themselves, and their times)
//   POST   /fights/<channel>?key= | Bearer <tend>    multipart: "meta" (JSON), "fight" (the recording, gzipped JSON lines),
//                                                    and optionally "moment" (its scene moment, below) with "moment_tick"
//   GET    /fights/<channel>/<id>.jsonl.gz           the recording (a fight kept to themselves: only with the owner's)
//   POST   /fights/<channel>/<id>?public=1|0         shown to anyone, or kept to themselves (the owner's)
//          /fights/<channel>/<id>?featured=1|0       the fight the page features (its stage), one at a time (the owner's)
//          /fights/<channel>/<id>?kept=1|0           kept for good or not (the owner's; any of the three in one request)
//   DELETE /fights/<channel>/<id>                    taken down (the owner's)
//   POST   /fights/<channel>/<id>/moment?tick=<t>    the fight's scene moment (the owner's; the body the file, at most
//                                                    MAX_MOMENT; the tick also as the X-Moment-Tick header), one a fight
//   GET    /fights/<channel>/<id>/moment.bin         the moment (whoever may see the fight)
//   DELETE /fights/<channel>/<id>/moment             the moment taken away (the owner's)
//   POST   /fights/<channel>?bestshow=<boss>&on=1|0  a boss's best shown with the kept ones or not (the owner's; off to
//                                                    begin with)
//   POST   /fights/<channel>/<id>?times=1            its kill times (the owner's; the body {"times": [FightTime, ...]},
//                                                    at most MAX_TIMES_BODY bytes, [] for "read: the game gave none")
//
// Kill times (11/22/2026; ghillie's replay/times.js): the game's own time lines in a recording ("Fight duration: 1:48.
// Personal best: 1:12", a raid's completion time...), read by the page from the file, since the relay can't unpack a
// whole recording in a request's few milliseconds (a 79-minute raid is 3.6 MB gzipped, 72 MB of lines). The page that
// has a recording open (its owner's, which reads each one that has no times yet) sends what it read, and the list
// carries it as `times`, so a visitor's page shows every encounter's time and each boss's best without downloading a
// recording. An upload's meta may carry them too (`times`, the same shape). Checked here for shape and size only: they're
// the owner's own words about their own recording, as the recording itself is.
//
// The scene moment (11/21/2026; ghillie's claude/outstation.md, "Encounters on Outside: the night's design"): a moment of
// a fight its owner kept, as dots sampled from the replay's 3D scene at a tick (ghillie's replay/3d/moment.js, its file
// "OMNT" gzipped), so the profile can show it with no game files. The relay checks only that it's gzip and starts "OMNT";
// it holds nothing but the scene's colours and places (never a name: the foes in its meta are NPCs).
//
// Kept for good (11/21/2026; ghillie's claude/outstation.md, "Encounters, round two": every encounter goes up by itself,
// so the player chooses a few to keep): a kept fight is never trimmed by FIGHTS_DAYS nor counted against FIGHTS_MOST; at
// most FIGHTS_KEPT (12) a page, keeping one past that refused (409) with the reason. Featuring a fight keeps it (the
// featured one is always kept: past the cap, featuring one not yet kept is refused alike); letting the featured one go
// (?kept=0) unfeatures it too, rather than refusing (the side tab's one press does what it says; the answer's
// "featured": false says so). A featured fight from before kept existed is spared by the trim all the same.
//
// One upload, the fight and its moment (11/21/2026): the Outside plugin sends each encounter by itself as it finishes,
// with the moment it captured from the client's own scene, so the multipart upload may carry a "moment" part (the same
// file as the moment route's, any version 1 to MOMENT_VERSION_MOST: looping moments are format 2) and its tick as a
// "moment_tick" part (or meta's moment_tick; else the tick in the moment's own meta). A bad moment refuses the whole
// upload, so nothing is half up. The hourly limits (FIGHTS_AN_HOUR, MOMENTS_AN_HOUR) were raised from 20 and 30 to 60
// for it: a raid splits into parts every 5,000 ticks (50 minutes, so two or three an hour), but a busy hour of short
// bosses (a 1-minute Zulrah or Vorkath, a 40-second Hunllef) can pass 40; 60 is one a minute, still a cap on a key run
// away. A 429 says when to try again (Retry-After), so the plugin can wait and send it then.
//
// The times' history (11/22/2026; Jace: "yes, build both"): a page's kill times outlive its recordings. Every timed kill
// is copied, as it's set (?times=1, or an upload's meta), into a history of its own in the channel's storage
// ("fightTimes": TimeEntry[], oldest first, at most HISTORY_MOST), which the trim never touches: a fight trimmed (its days
// up, or past the most) leaves its times there without its `id`, so a boss's record never shrinks and the page shows
// them unlinked. Taken down by its owner, a fight's times go with it (a take-down means gone); the page taken down, all
// of it. A time is shown as its fight was: one kept to themselves is in the owner's history only, and stays so once
// its fight is gone. Past HISTORY_MOST the oldest go first, never a boss's best nor a time whose fight is still up. The
// list carries it as `history` (historyShown). Fights that had times before the history existed are copied in the first
// time the list is read.
//
// Each boss's best kept (11/22/2026): the fight holding a boss's quickest time on file (per boss, and per team size when
// the time says it: a raid's) is spared by the trim as a kept one is, isn't counted against FIGHTS_MOST nor
// FIGHTS_KEPT, and is marked `"best": true` in the list (worked out here from the fights' own times each time the list
// is read: never stored, never set by the page). Beaten, it's let go and rejoins the trim; taken down, the next quickest
// still up holds it. A tie goes to the earlier.
//
// A boss's best with the kept ones (11/22/2026; Jace: "add a switch to keep the kept best in their encounters section at
// the top with their other kept encounters"): every boss's best is always kept; the owner chooses, boss by boss (the
// switch in its panel in entities slain), whether its best's tile sits with the kept ones at the top of the encounters
// rather than in time order among the rest: POST /fights/<channel>?bestshow=<boss or its key>&on=1|0. It follows the
// boss, not the fight: beaten, the new best goes where the old one went. Never counted against FIGHTS_KEPT. Kept in the
// channel's storage ("fightBestShown": boss keys, at most BEST_SHOWN_MOST); the owner's list carries the keys as
// `best_shown`, and every viewer's list marks such a best `"with_kept": true`, so each page places it alike.
//
// The owner is whoever holds the page's key (the Outside plugin, the outstation) or a tending token the plugin handed in
// with it (POST /tend/<channel>, channel.ts: the link the plugin opens to the player's own page, outside.wiki/<name>#tend=).
//
// Switches, all Jace's (wrangler.jsonc's vars; his calls in ghillie's lab): FIGHTS "on" or anything else for off (every
// route then 404s, and the page shows no section); FIGHTS_PUBLIC "1" if a new fight is shown to anyone unless the player
// says otherwise, else kept to themselves until they show it; FIGHTS_MOST a page's fights at most (the oldest going
// first); FIGHTS_DAYS how long one is kept (0: until the player takes it down or the page goes); FIGHTS_KEPT how many
// may be kept for good (12).
//
// What the relay checks: the file is gzip, its first line Encounter Recorder's header (format 1 to FORMAT_MOST), at most
// MAX_FIGHT. It can't unpack a whole file in a request's few milliseconds, so the stripping is the page's to do; the
// owner's own file either way.

export const MAX_FIGHT = 8 * 1024 * 1024;
export const FORMAT_MOST = 99;
export const FIGHT_ID = /^[A-Za-z0-9_-]{22}$/;
export const FIGHTS_AN_HOUR = 60;
export const MAX_MOMENT = 3 * 1024 * 1024; // (3 MB since 11/21/2026: looping moments carry each frame's moving dots)
export const MOMENT_VERSION_MOST = 9;
export const MOMENTS_AN_HOUR = 60;
const MOMENT_TOO_BIG = "a moment is 3 MB at most";

export interface Fight {
  id: string;
  foe: string; // who it was against (the NPC most of the damage went to, else the recording's label)
  label: string; // the recording's own label (Encounter Recorder's: the boss, or where)
  at: number; // when it began (unix ms, the recording's startedMs)
  ticks: number; // how long, in game ticks (0.6 s)
  died: boolean; // whether the player died in it
  taken: number; // damage on the player
  dealt: number; // damage they dealt
  size: number; // the file's bytes
  public: boolean; // shown to anyone, or kept to themselves
  up: number; // when it was put up (unix ms)
  moment?: { tick: number; size: number; at: number }; // its scene moment, if its owner kept one (at: when, unix ms)
  kept?: true; // kept for good: never trimmed by days or the most (11/21/2026)
  featured?: true; // (in a list only: the one the page features)
  moment_missing?: string; // why the plugin sent it with no moment, in its own words (11/21/2026; the page may make one)
  times?: FightTime[]; // the game's kill times in it, as its page read them ([]: read, none); absent: not read yet (11/22/2026)
  best?: true; // (in a list only, worked out here: it holds a boss's best time on file, so it's kept; 11/22/2026)
  with_kept?: true; // (in a list only: a best whose boss its owner shows with the kept ones, at the top; 11/22/2026)
  trip?: FightTrip; // the trip it's part of (update 8, 11/23/2026), as the plugin's header said
}

/** A trip (update 8, 11/23/2026; Jace: "People consider instances with bosses that respawn a 'trip' with multiple kills
 *  in succession over a period of 1-3 hours"): a boss's instance's kills, each its own recording, the next begun as the
 *  last ended, so played end to end the trip is whole. The page puts a trip's fights together as one card. */
export interface FightTrip {
  id: string; // the trip's (the plugin's, random: TRIP_ID)
  no: number; // this recording's number in it, from 1
  end?: string; // how the trip ended, on the recording it ended with ("left", "died", "logged out"...)
}

export const TRIP_ID = /^[A-Za-z0-9_-]{8,22}$/;

/** A trip as an upload's meta said it, checked: the trip, null for none, or why it's refused. */
export function readTrip(v: unknown): FightTrip | null | [number, string] {
  if (v === undefined || v === null) return null;
  if (typeof v !== "object" || Array.isArray(v)) return [400, "a trip is {id, no, end?}"];
  const o = v as Record<string, unknown>;
  if (typeof o.id !== "string" || !TRIP_ID.test(o.id)) return [400, "a trip's id is 8 to 22 letters, digits, _ or -"];
  if (typeof o.no !== "number" || !Number.isInteger(o.no) || o.no < 1 || o.no > 999) return [400, "a trip's no is 1 to 999"];
  const t: FightTrip = { id: o.id, no: o.no };
  if (o.end !== undefined) {
    if (typeof o.end !== "string") return [400, "a trip's end is words"];
    const end = o.end.replace(/[\u0000-\u001f\u007f]/g, " ").trim().slice(0, 40);
    if (end) t.end = end;
  }
  return t;
}

/** A kill's time, as the game's chat line gave it (ghillie's replay/times.js, short()). */
export interface FightTime {
  boss: string | null; // whose (the kill count line's name, else null: the fight's own label stands for it)
  time: string; // the game's time, as written: "1:48", "28:28.80", "1:02:03"
  t: number; // the recording's tick of the line
  pb?: string; // the personal best the line named, when it isn't this time
  best?: true; // a personal best: a new one, or the same as the one it named
  team?: string; // a raid's team size, as the line said it ("3 players", "Solo")
}

export const MAX_TIMES = 8;
export const MAX_TIMES_BODY = 4096;
const TIME_RE = /^\d{1,3}(?::\d{2}){1,2}(?:\.\d{1,3})?$/;

/** Kill times sent by the page (or an upload's meta), checked: at most MAX_TIMES, each its fields bounded; or why not. */
export function readTimes(v: unknown): FightTime[] | [number, string] {
  if (!Array.isArray(v)) return [400, "times is a list"];
  if (v.length > MAX_TIMES) return [400, `${MAX_TIMES} times at most`];
  const text = (x: unknown, most: number) => (typeof x === "string" ? x.replace(/[\u0000-\u001f\u007f]/g, " ").trim().slice(0, most) : "");
  const out: FightTime[] = [];
  for (const k of v) {
    if (!k || typeof k !== "object") return [400, "each time is an object"];
    const o = k as Record<string, unknown>;
    if (typeof o.time !== "string" || !TIME_RE.test(o.time)) return [400, "a time is the game's, like 1:48 or 28:28.80"];
    if (o.pb !== undefined && (typeof o.pb !== "string" || !TIME_RE.test(o.pb))) return [400, "a personal best is the game's, like 1:12"];
    const t: FightTime = { boss: text(o.boss, 48) || null, time: o.time, t: typeof o.t === "number" && Number.isInteger(o.t) && o.t >= 0 && o.t < 10_000_000 ? o.t : 0 };
    if (typeof o.pb === "string" && o.pb !== o.time) t.pb = o.pb;
    if (o.best === true) t.best = true;
    const team = text(o.team, 16);
    if (team) t.team = team;
    out.push(t);
  }
  return out;
}

/** Whether readTimes said no (its [status, words]) rather than giving times. */
export function timesRefused(x: FightTime[] | [number, string]): x is [number, string] {
  return x.length === 2 && typeof x[0] === "number";
}

/** The body of POST /<id>?times=1: {"times": [...]}, at most MAX_TIMES_BODY bytes; checked as readTimes does. */
export async function readTimesBody(request: Request): Promise<FightTime[] | [number, string]> {
  const n = parseInt(request.headers.get("content-length") || "0") || 0;
  if (n > MAX_TIMES_BODY) return [413, "times are 4 KB at most"];
  const body = await bodyAtMost(request, MAX_TIMES_BODY);
  if (!body) return [413, "times are 4 KB at most"];
  let j: unknown;
  try { j = JSON.parse(new TextDecoder().decode(body)); } catch { return [400, "times are JSON: {\"times\": [...]}"]; }
  return readTimes(j && typeof j === "object" ? (j as Record<string, unknown>).times : undefined);
}

export function fightKey(channel: string, id: string): string {
  return `fights/${channel}/${id}.jsonl.gz`;
}

export function momentKey(channel: string, id: string): string {
  return `fights/${channel}/${id}.moment.bin`;
}

/** The list as a viewer sees it: every fight for the owner, the shown ones for anyone else; the featured one marked, and
 *  named only if this viewer can see it. */
export function listed(list: Fight[], owner: boolean, featuredId: string | null | undefined): { fights: Fight[]; featured: string | null } {
  const shown = owner ? list : list.filter((f) => f.public);
  const featured = featuredId && shown.some((f) => f.id === featuredId) ? featuredId : null;
  return { fights: shown.map((f) => (f.id === featured ? { ...f, featured: true } : f)), featured };
}

export function newFightId(): string {
  const b = crypto.getRandomValues(new Uint8Array(16));
  return btoa(String.fromCharCode(...b)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

export function fightsOn(env: { FIGHTS?: string }): boolean {
  return (env.FIGHTS || "").trim().toLowerCase() === "on";
}

export function fightNumbers(env: { FIGHTS_PUBLIC?: string; FIGHTS_MOST?: string; FIGHTS_DAYS?: string; FIGHTS_KEPT?: string }) {
  const most = parseInt(env.FIGHTS_MOST || "");
  const days = parseFloat(env.FIGHTS_DAYS || "");
  const kept = parseInt(env.FIGHTS_KEPT || "");
  return {
    public_default: (env.FIGHTS_PUBLIC || "").trim() === "1",
    most: most > 0 ? Math.min(most, 500) : 20,
    days: isFinite(days) && days >= 0 ? days : 30,
    kept: kept >= 0 && /^\s*\d+\s*$/.test(env.FIGHTS_KEPT || "") ? Math.min(kept, 500) : 12,
  };
}

/** The `kept=` asked for with `featured=` (featuring keeps it): true, false or unchanged (null); or why not. */
export function keptAsked(kept: string | null, featured: string | null): boolean | null | [number, string] {
  if (kept === "0" && featured === "1") return [400, "a featured fight is always kept: kept=0 with featured=1 can't be"];
  if (kept === "1" || featured === "1") return true;
  return kept === "0" ? false : null;
}

/** The first line of a gzipped file, unpacked, or null (not gzip, or no line in its first 64 KB). */
export async function firstLine(file: Uint8Array): Promise<string | null> {
  if (file.length < 18 || file[0] !== 0x1f || file[1] !== 0x8b) return null;
  const reader = new Blob([file]).stream().pipeThrough(new DecompressionStream("gzip")).getReader();
  const dec = new TextDecoder();
  let text = "";
  try {
    while (text.length < 65536) {
      const { value, done } = await reader.read();
      if (done) break;
      text += dec.decode(value, { stream: true });
      const nl = text.indexOf("\n");
      if (nl >= 0) return text.slice(0, nl);
    }
    return text.length && text.length < 65536 ? text : null;
  } catch {
    return null;
  } finally {
    reader.cancel().catch(() => {});
  }
}

/** The first `n` bytes of a gzipped file, unpacked, or null (not gzip, or shorter). */
export async function firstBytes(file: Uint8Array, n: number): Promise<Uint8Array | null> {
  if (file.length < 18 || file[0] !== 0x1f || file[1] !== 0x8b) return null;
  const reader = new Blob([file]).stream().pipeThrough(new DecompressionStream("gzip")).getReader();
  const got = new Uint8Array(n);
  let have = 0;
  try {
    while (have < n) {
      const { value, done } = await reader.read();
      if (done) break;
      const take = Math.min(n - have, value.length);
      got.set(value.subarray(0, take), have);
      have += take;
    }
    return have === n ? got : null;
  } catch {
    return null;
  } finally {
    reader.cancel().catch(() => {});
  }
}

/** Whether a file is a scene moment: gzip, unpacking to "OMNT", a version from 1 to MOMENT_VERSION_MOST. */
export async function isMoment(file: Uint8Array): Promise<boolean> {
  const b = await firstBytes(file, 12);
  return !!b && b[0] === 0x4f && b[1] === 0x4d && b[2] === 0x4e && b[3] === 0x54 && b[4] >= 1 && b[4] <= MOMENT_VERSION_MOST;
}

/** A request's body, at most `most` bytes (null if it's more). */
export async function bodyAtMost(request: Request, most: number): Promise<Uint8Array | null> {
  if (!request.body) return new Uint8Array(0);
  const reader = request.body.getReader();
  const parts: Uint8Array[] = [];
  let n = 0;
  for (;;) {
    const { value, done } = await reader.read();
    if (done) break;
    n += value.length;
    if (n > most) { reader.cancel().catch(() => {}); return null; }
    parts.push(value);
  }
  const out = new Uint8Array(n);
  let o = 0;
  for (const p of parts) { out.set(p, o); o += p.length; }
  return out;
}

/** A moment's upload, checked: its file and tick, or why not (a status and words). `ticks`: the fight's length, 0 if
 *  unknown. */
export async function readMoment(request: Request, q: URLSearchParams, ticks: number): Promise<{ file: Uint8Array; tick: number } | [number, string]> {
  const n = parseInt(request.headers.get("content-length") || "0") || 0;
  if (n > MAX_MOMENT) return [413, MOMENT_TOO_BIG];
  const t = q.get("tick") ?? request.headers.get("x-moment-tick") ?? "";
  if (!/^\d{1,7}$/.test(t.trim())) return [400, "which tick? (?tick=)"];
  const tick = parseInt(t.trim());
  if (ticks > 0 && tick > ticks + 1) return [400, "that tick is past the fight's end"];
  const file = await bodyAtMost(request, MAX_MOMENT);
  if (!file) return [413, MOMENT_TOO_BIG];
  if (!(await isMoment(file))) return [415, "that isn't a scene moment (gzipped)"];
  return { file, tick };
}

/** The tick in a moment's own meta (the file's "OMNT", version, flags, the meta's length as a u16 at 6, the dots' count,
 *  then the meta's JSON at 12: ghillie's replay/3d/moment.js), or null if it can't be read. */
export async function momentTick(file: Uint8Array): Promise<number | null> {
  const head = await firstBytes(file, 12);
  if (!head) return null;
  const len = head[6] | (head[7] << 8);
  if (!len || len > 65535) return null;
  const all = await firstBytes(file, 12 + len);
  if (!all) return null;
  try {
    const t = (JSON.parse(new TextDecoder().decode(all.subarray(12))) as Record<string, unknown>).tick;
    return typeof t === "number" && Number.isInteger(t) && t >= 0 && t < 10_000_000 ? t : null;
  } catch {
    return null;
  }
}

/** Whether a line is Encounter Recorder's header: {"t": 0, "e": "header", "format": n, ...}. */
export function isHeader(line: string | null): boolean {
  if (!line) return false;
  try {
    const o = JSON.parse(line) as Record<string, unknown>;
    return o.e === "header" && typeof o.format === "number" && Number.isInteger(o.format) && o.format >= 1 && o.format <= FORMAT_MOST;
  } catch {
    return false;
  }
}

/** An upload's parts, checked: the fight's entry (without its id, public or up) and its file, or why not. */
export async function readFight(request: Request): Promise<{ meta: Omit<Fight, "id" | "public" | "up">; file: Uint8Array; public: boolean | null; moment: { file: Uint8Array; tick: number } | null } | [number, string]> {
  const n = parseInt(request.headers.get("content-length") || "0") || 0;
  if (n > MAX_FIGHT + MAX_MOMENT + 64 * 1024) return [413, "a recording is 8 MB at most (gzipped)"];
  let form: FormData;
  try { form = await request.formData(); } catch { return [400, "an upload is multipart: meta and fight"]; }
  const f = form.get("fight"), metaS = form.get("meta");
  if (!(f instanceof File) || typeof metaS !== "string") return [400, "an upload is multipart: meta and fight"];
  if (f.size > MAX_FIGHT) return [413, "a recording is 8 MB at most (gzipped)"];
  const file = new Uint8Array(await f.arrayBuffer());
  if (!isHeader(await firstLine(file))) return [415, "that isn't an Encounter Recorder recording (gzipped)"];
  let m: Record<string, unknown>;
  try { m = JSON.parse(metaS); } catch { return [400, "meta isn't JSON"]; }
  const num = (v: unknown, lo: number, hi: number) => (typeof v === "number" && isFinite(v) ? Math.min(hi, Math.max(lo, Math.round(v))) : lo);
  const text = (v: unknown, most: number) => (typeof v === "string" ? v.replace(/[\u0000-\u001f\u007f]/g, " ").trim().slice(0, most) : "");
  const now = Date.now();
  const meta = {
    foe: text(m.foe, 48) || text(m.label, 48) || "a fight", label: text(m.label, 48),
    at: num(m.at, 1_577_836_800_000, now + 60_000), ticks: num(m.ticks, 0, 1_000_000), died: m.died === true,
    taken: num(m.taken, 0, 100_000_000), dealt: num(m.dealt, 0, 100_000_000), size: file.length,
    ...(text(m.moment_missing, 120) ? { moment_missing: text(m.moment_missing, 120) } : {}),
  } as Omit<Fight, "id" | "public" | "up">;
  // (its kill times, if the sender read them: the same shape as POST /<id>?times=1's; bad ones refuse the upload)
  if (m.times !== undefined) {
    const times = readTimes(m.times);
    if (timesRefused(times)) return times;
    meta.times = times;
  }
  // (its trip, if it's part of one: update 8; a bad one refuses the upload)
  const trip = readTrip(m.trip);
  if (Array.isArray(trip)) return trip;
  if (trip) meta.trip = trip;
  // (its moment, if it carries one: checked as the moment route checks it, and the whole upload refused if it's bad)
  let moment: { file: Uint8Array; tick: number } | null = null;
  const mf = form.get("moment");
  if (mf !== null) {
    if (!(mf instanceof File)) return [400, "the moment is a file part"];
    if (mf.size > MAX_MOMENT) return [413, MOMENT_TOO_BIG];
    const mfile = new Uint8Array(await mf.arrayBuffer());
    if (!(await isMoment(mfile))) return [415, "that isn't a scene moment (gzipped)"];
    const said = form.get("moment_tick") ?? (m.moment_tick !== undefined ? String(m.moment_tick) : null);
    let tick: number | null;
    if (said !== null) {
      if (typeof said !== "string" || !/^\d{1,7}$/.test(said.trim())) return [400, "which tick is the moment? (moment_tick)"];
      tick = parseInt(said.trim());
    } else tick = await momentTick(mfile);
    if (tick === null) return [400, "which tick is the moment? (moment_tick)"];
    if (meta.ticks > 0 && tick > meta.ticks + 1) return [400, "that tick is past the fight's end"];
    moment = { file: mfile, tick };
  }
  return { meta, file, public: typeof m.public === "boolean" ? m.public : null, moment };
}

/** The list without what's too old or past the most, and what goes (whose files go too); newest first. days 0: none
 *  too old. A fight kept for good, the featured one, or one holding a boss's best time (bestIds) always stays and isn't
 *  counted against the most. */
export function trimFights(list: Fight[], most: number, days: number, now = Date.now(), featured: string | null = null): { stay: Fight[]; gone: Fight[] } {
  const sorted = [...list].sort((a, b) => b.up - a.up);
  const best = bestIds(list);
  const stay: Fight[] = [], gone: Fight[] = [];
  let counted = 0;
  for (const f of sorted) {
    if (f.kept || f.id === featured || best.has(f.id)) stay.push(f);
    else if (counted < most && (days <= 0 || now - f.up <= days * 86_400_000)) { stay.push(f); counted++; }
    else gone.push(f);
  }
  return { stay, gone };
}

// ---- the times' history and each boss's best (11/22/2026; the header's "The times' history" and "Each boss's best") --

export const HISTORY_MOST = 2000;

/** A timed kill on the page's record: kept apart from the list and never trimmed with it. */
export interface TimeEntry {
  key: string; // the boss's key (bossKey), the same as the page matches its kill rows by
  boss: string; // whose, as shown: the line's name, else its fight's label, else its foe
  at: number; // when its fight began (unix ms)
  t: number; // the line's tick in the recording
  time: string; // the game's time, as written
  pb?: string; // the personal best the line named, when it isn't this time
  best?: true; // the game called it a personal best
  team?: string; // a raid's team size, as the line said it
  id?: string; // its fight, while that's on the page
  hidden?: true; // its fight was kept to themselves (the owner's history only)
}

/** A boss's name as the page matches it (ghillie's fights.js and outside.js, bossKey): lower case, a leading "The" or
 *  "subdued" aside, anything not a letter or a digit a space, a trailing " mode" aside. */
export function bossKey(name: string): string {
  return String(name || "").toLowerCase().replace(/^(the|subdued) /, "").replace(/[^a-z0-9]+/g, " ").replace(/ mode$/, "").trim();
}

/** The game's time in seconds ("1:02:03.4"), as RuneLite's timeStringToSeconds (ghillie's replay/times.js, seconds). */
export function timeSeconds(s: string): number {
  const p = String(s).split(":");
  if (p.length === 2) return parseInt(p[0], 10) * 60 + parseFloat(p[1]);
  if (p.length === 3) return parseInt(p[0], 10) * 3600 + parseInt(p[1], 10) * 60 + parseFloat(p[2]);
  return parseFloat(s);
}

// (a time's group: its boss, and its team size when it says one)
const groupOf = (key: string, team: string | undefined) => key + (team ? "|" + team.toLowerCase() : "");

/** A fight's times as history entries. */
export function historyOf(f: Fight, times: FightTime[] = f.times ?? []): TimeEntry[] {
  return times.map((k) => {
    const boss = k.boss || f.label || f.foe || "a fight";
    const e: TimeEntry = { key: bossKey(boss), boss, at: f.at, t: k.t, time: k.time };
    if (k.pb) e.pb = k.pb;
    if (k.best) e.best = true;
    if (k.team) e.team = k.team;
    e.id = f.id;
    if (!f.public) e.hidden = true;
    return e;
  });
}

/** The history with a fight's times put in (in place of any it had: times set again), oldest first, at most `most`. */
export function historyPut(history: TimeEntry[], f: Fight, times: FightTime[] = f.times ?? [], most = HISTORY_MOST): TimeEntry[] {
  const rest = history.filter((e) => e.id !== f.id);
  return historyCap(rest.concat(historyOf(f, times)), most);
}

/** Oldest first; past `most`, the oldest go, never a group's quickest nor a time whose fight is still up (unless
 *  nothing else is left to go). */
export function historyCap(history: TimeEntry[], most = HISTORY_MOST): TimeEntry[] {
  const all = [...history].sort((a, b) => a.at - b.at || a.t - b.t);
  if (all.length <= most) return all;
  const quickest = new Map<string, TimeEntry>();
  for (const e of all) {
    const g = groupOf(e.key, e.team), q = quickest.get(g);
    if (!q || timeSeconds(e.time) < timeSeconds(q.time) - 0.005) quickest.set(g, e);
  }
  const keep = new Set<TimeEntry>([...quickest.values()]);
  for (const e of all) if (e.id) keep.add(e);
  let over = all.length - most;
  const out: TimeEntry[] = [];
  for (const e of all) {
    if (over > 0 && !keep.has(e)) { over--; continue; }
    out.push(e);
  }
  // (only bests and fights still up left, and still too many: the oldest of them go too)
  return out.length > most ? out.slice(out.length - most) : out;
}

/** The history once fights have gone: trimmed, their times stay without their ids; taken down, their times go. */
export function historyGone(history: TimeEntry[], ids: Iterable<string>, takenDown: boolean): TimeEntry[] {
  const gone = new Set(ids);
  if (takenDown) return history.filter((e) => !e.id || !gone.has(e.id));
  return history.map((e) => {
    if (!e.id || !gone.has(e.id)) return e;
    const { id: _, ...rest } = e;
    return rest;
  });
}

/** The history once a fight's been shown or kept to themselves: its times follow it. */
export function historyPublic(history: TimeEntry[], id: string, shown: boolean): TimeEntry[] {
  return history.map((e) => {
    if (e.id !== id) return e;
    const { hidden: _, ...rest } = e;
    return shown ? rest : { ...rest, hidden: true as const };
  });
}

/** The history as a viewer sees it: the owner's whole; anyone else's without the times of fights kept to themselves.
 *  An id only where its fight is in `list` (what this viewer is shown). */
export function historyShown(history: TimeEntry[], owner: boolean, list: Fight[]): TimeEntry[] {
  const up = new Set(list.map((f) => f.id));
  return history.filter((e) => owner || !e.hidden).map((e) => {
    let o = e;
    if (o.id && !up.has(o.id)) { const { id: _, ...rest } = o; o = rest; }
    if (!owner && o.hidden) { const { hidden: _, ...rest } = o; o = rest; }
    return o;
  });
}

/** The fights holding a boss's best time on file: per boss (and team size, when the time says one), the quickest of the
 *  fights' own times; a tie to the earlier. */
export function bestIds(list: Fight[]): Set<string> {
  return new Set(bestOf(list).keys());
}

/** The same, each with the bosses (their keys) it holds the best of. */
export function bestOf(list: Fight[]): Map<string, string[]> {
  const quickest = new Map<string, { id: string; key: string; sec: number; at: number }>();
  for (const f of list) {
    for (const k of f.times ?? []) {
      const sec = timeSeconds(k.time);
      if (!isFinite(sec)) continue;
      const key = bossKey(k.boss || f.label || f.foe || "a fight"), g = groupOf(key, k.team), q = quickest.get(g);
      if (!q || sec < q.sec - 0.005 || (Math.abs(sec - q.sec) <= 0.005 && f.at < q.at)) quickest.set(g, { id: f.id, key, sec, at: f.at });
    }
  }
  const out = new Map<string, string[]>();
  for (const q of quickest.values()) {
    const keys = out.get(q.id) ?? [];
    if (!keys.includes(q.key)) keys.push(q.key);
    out.set(q.id, keys);
  }
  return out;
}

/** The list with each fight holding a boss's best marked `best`, and `with_kept` where one of those bosses is in
 *  `shown` (its owner's choice: with the kept ones at the top). Copies; a stored `best` or `with_kept` never counts. */
export function markBest(list: Fight[], shown: Iterable<string> = []): Fight[] {
  const best = bestOf(list), with_ = new Set(shown);
  return list.map((f) => {
    const { best: _, with_kept: __, ...rest } = f;
    const keys = best.get(f.id);
    if (!keys) return rest;
    return keys.some((k) => with_.has(k)) ? { ...rest, best: true as const, with_kept: true as const } : { ...rest, best: true as const };
  });
}

export const BEST_SHOWN_MOST = 500;

/** A boss named for ?bestshow=: its key (bossKey), or why not. */
export function bestShowKey(v: string | null): string | [number, string] {
  const key = bossKey((v || "").slice(0, 120));
  if (!key || key.length > 80) return [400, "which boss? (bestshow=<its name>)"];
  return key;
}

/** The bosses whose best is shown with the kept ones, with one switched on or off; at most BEST_SHOWN_MOST (one more
 *  past that refused, with why). */
export function bestShownSet(shown: string[], key: string, on: boolean): string[] | [number, string] {
  const rest = shown.filter((k) => k !== key);
  if (!on) return rest;
  if (rest.length >= BEST_SHOWN_MOST) return [409, `${BEST_SHOWN_MOST} bosses at most with their best shown with the kept ones`];
  return [...rest, key].sort();
}

// ---- the Array's fights (11/26/2026; Jace: "we should add a feature to the front page of Outside.wiki that shows the
// most recently uploaded encounters that have been recorded by people's plugins so people can see what others are up to
// at the moment and how active certain bosses/raids are") ----
//
//   GET <relay>/array/fights?limit=<n>   {"fights": [FeedFight with "name", ...] newest first by when each went up, at
//                                         most n (FEED_LATEST to begin with, FEED_PAGE at most), "bosses": [{"boss", "key",
//                                         "day", "week"}, ...] the busiest FEED_BOSSES first, "day" and "week" (every
//                                         boss's, in all), "days", "since", "at"}
//
// Only fights shown to anyone, on the open list's pages (GET /channels's: handed out, no watch code, not an invite
// waiting): never one kept to themselves, nor a friends-only page's. Kept up as they change, not gathered at each read
// (a read used to wake every channel for GET /channels, and that was undone for the same reason: index.ts's
// Directory.listed): each channel tells the Directory (Channel.tellFeed) whenever one of its fights goes up, is shown or
// kept to themselves, gets its moment or its times, is trimmed or taken down, and the Directory keeps the newest
// FEED_KEPT of the last FEED_DAYS in one row of its storage ("fightFeed"), read once a wake, as the Array's events are.
// A read asks no channel: the open list's rows say which pages are open now, so a page given a watch code drops out at
// once and comes back when it's taken off. Except the first read ever: with no feed stored, the Directory gathers each
// open page's shown fights once (FEED_SEED_MOST pages at most), so what was up before the feed began is in it.
//
// A trimmed fight (its page's days up, or past its most) stays counted for its boss without its id: its page is gone,
// so it isn't listed. Taken down, or kept to themselves, it goes from both. A boss is the fight's label (Encounter
// Recorder's: the boss, or the raid), else its foe, as the page's kill board names it (bossKey); each recording counts
// once, a trip's each. The counts are whole from `since` (FEED_DAYS ago, or later where the feed was full and its
// oldest went): the page says "this week" only when they are. Answered with cache-control max-age=60.

// (FEED_SEED_MOST: the first read's gathering wakes each page it asks, eight at a time, all in that one read's request)
export const FEED_KEPT = 500, FEED_DAYS = 7, FEED_LATEST = 24, FEED_PAGE = 60, FEED_BOSSES = 12, FEED_SEED_MOST = 40;
const FEED_DAY = 86_400_000;

/** A fight as the Array's feed keeps it: what its tile says, and its page's name. */
export interface FeedFight {
  channel: string;
  id?: string; // while its fight is on its page (a trimmed one keeps its count, not its link)
  foe: string;
  label: string;
  at: number; // when it began (unix ms)
  up: number; // when it went up (unix ms): the feed's order
  ticks: number;
  died: boolean;
  moment?: { tick: number }; // its scene moment, if it has one (GET /fights/<channel>/<id>/moment.bin)
  time?: string; // the game's kill time in it, the last it gave (its page's times), as written
  pb?: true; // that time a personal best
  trip?: FightTrip;
}

/** A fight in the feed's shape. */
export function feedOf(channel: string, f: Fight): FeedFight {
  const e: FeedFight = { channel, id: f.id, foe: f.foe, label: f.label, at: f.at, up: f.up, ticks: f.ticks, died: !!f.died };
  if (f.moment) e.moment = { tick: f.moment.tick };
  const k = f.times?.length ? f.times[f.times.length - 1] : null;
  if (k) { e.time = k.time; if (k.best) e.pb = true; }
  if (f.trip) e.trip = { ...f.trip };
  return e;
}

/** The boss a feed entry counts for (its label, else its foe), as shown. */
export function feedBoss(e: { label?: string; foe?: string }): string {
  return (e.label || "").trim() || (e.foe || "").trim() || "a fight";
}

export interface FeedBoss { boss: string; key: string; day: number; week: number }

export interface FeedAnswer {
  fights: (FeedFight & { name: string })[];
  bosses: FeedBoss[];
  day: number; // every boss's over the last day (FEED_BOSSES are listed; these are all of them)
  week: number; // and over FEED_DAYS
  days: number;
  since: number;
  at: number;
}

/** The Array's fights, newest first (by `up`), the newest FEED_KEPT of the last FEED_DAYS. `seeded`: the open pages'
 *  shown fights gathered once (the Directory's first read). `cut`: when the newest one let go for room went up (0:
 *  none yet), so the counts are whole only from after it. */
export class FightFeed {
  list: FeedFight[] = [];
  seeded = false;
  cut = 0;

  static from(saved: unknown): FightFeed {
    const f = new FightFeed();
    if (saved && typeof saved === "object") {
      const s = saved as { list?: unknown; seeded?: unknown; cut?: unknown };
      if (Array.isArray(s.list)) f.list = (s.list as FeedFight[]).filter((e) => e && typeof e.channel === "string" && typeof e.up === "number");
      f.seeded = s.seeded === true;
      f.cut = typeof s.cut === "number" ? s.cut : 0;
    }
    f.list.sort((a, b) => b.up - a.up);
    return f;
  }

  saved(): { list: FeedFight[]; seeded: boolean; cut: number } {
    return { list: this.list, seeded: this.seeded, cut: this.cut };
  }

  /** A channel's fight as it now stands: in (or its entry brought up to date) if it's shown to anyone, else out. Whether
   *  the feed changed. */
  put(channel: string, f: Fight, now = Date.now()): boolean {
    const i = this.list.findIndex((e) => e.channel === channel && e.id === f.id);
    if (!f.public) {
      if (i < 0) return false;
      this.list.splice(i, 1);
      return true;
    }
    const e = feedOf(channel, f);
    if (i >= 0) {
      if (JSON.stringify(this.list[i]) === JSON.stringify(e)) return false;
      this.list[i] = e;
    } else this.list.push(e);
    this.list.sort((a, b) => b.up - a.up);
    this.expire(now);
    return true;
  }

  /** A channel's fights gone from its page: trimmed (`takenDown` false: still counted, no longer listed) or taken down
   *  (gone from the feed). Whether the feed changed. */
  gone(channel: string, ids: Iterable<string>, takenDown: boolean): boolean {
    const set = new Set(ids);
    let changed = false;
    this.list = this.list.flatMap((e) => {
      if (e.channel !== channel || !e.id || !set.has(e.id)) return [e];
      changed = true;
      if (takenDown) return [];
      const { id: _, ...rest } = e;
      return [rest];
    });
    return changed;
  }

  /** A channel's every fight out (its page taken back or taken down). Whether the feed changed. */
  drop(channel: string): boolean {
    const n = this.list.length;
    this.list = this.list.filter((e) => e.channel !== channel);
    return this.list.length !== n;
  }

  /** What's older than FEED_DAYS out, and past FEED_KEPT the oldest (`cut` noting the newest of those). Whether the
   *  feed changed. */
  expire(now = Date.now()): boolean {
    const n = this.list.length, from = now - FEED_DAYS * FEED_DAY;
    this.list = this.list.filter((e) => e.up >= from);
    while (this.list.length > FEED_KEPT) this.cut = Math.max(this.cut, (this.list.pop() as FeedFight).up);
    return this.list.length !== n;
  }

  /** The answer for GET /array/fights: only the open pages' (`open`: each open channel and its name as the game shows
   *  it, null for none), the newest `limit` with their links, and every boss's count over the last day and FEED_DAYS. */
  read(open: Map<string, string | null>, now = Date.now(), limit = FEED_LATEST, boss = ""): FeedAnswer {
    const week = now - FEED_DAYS * FEED_DAY, day = now - FEED_DAY;
    const shown = this.list.filter((e) => open.has(e.channel) && e.up >= week);
    // (one boss's: as many as the feed keeps, not a page of them; 11/26/2026)
    const n = Math.max(1, Math.min(boss ? FEED_KEPT : FEED_PAGE, Math.floor(limit) || FEED_LATEST));
    // (?boss=: only that boss's, by the key the counts below go by; 11/26/2026, Jace: a boss's name under "busiest this
    // week" pressed "shows all of the recent kills/recorded encounters at that boss")
    const keyOf = (e: FeedFight) => { const b = feedBoss(e); return bossKey(b) || b.toLowerCase(); };
    const fights = shown.filter((e) => e.id && (!boss || keyOf(e) === boss)).slice(0, n).map((e) => ({ ...e, name: open.get(e.channel) || e.channel }));
    const bosses = new Map<string, FeedBoss & { last: number }>();
    let inDay = 0;
    for (const e of shown) {
      if (e.up >= day) inDay++;
      const boss = feedBoss(e), key = bossKey(boss) || boss.toLowerCase();
      let b = bosses.get(key);
      // (named as its newest says it: the list is newest first)
      if (!b) bosses.set(key, (b = { boss, key, day: 0, week: 0, last: e.up }));
      b.week++;
      if (e.up >= day) b.day++;
    }
    const top = [...bosses.values()].sort((a, b) => b.week - a.week || b.day - a.day || b.last - a.last).slice(0, FEED_BOSSES)
      .map(({ last: _, ...b }) => b);
    return { fights, bosses: top, day: inDay, week: shown.length, days: FEED_DAYS, since: Math.max(week, this.cut), at: now };
  }
}
