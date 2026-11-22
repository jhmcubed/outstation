// Recorded fights on a player's Outside page (11/20/2026; ghillie's claude/outstation.md, "Fight replays and trophies";
// Jace: "a section on your profile that lists recent encounters in case you want to watch one back"). A fight is an
// Encounter Recorder file (the Plugin Hub's plugin by Mr Gnome; its docs/FORMAT.md), dropped by the player on their own
// page and stripped there first (ghillie's strip.js: other players' names, other people's chat, exact XP, mouse
// presses), then played back on outside.wiki/<name>/fight/<id> in the page's own 2D and 3D replay. docs/protocol.md,
// "Recorded fights".
//
//   GET    /fights/<channel>?code=<watch code>       {"fights": [Fight, ...] newest first, "public_default", "most", "days"}
//                                                    (the owner's, with Authorization: Bearer <tend token> or ?key=, also
//                                                    lists the fights they've kept to themselves)
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
 *  too old. A fight kept for good, or the featured one, always stays and isn't counted against the most. */
export function trimFights(list: Fight[], most: number, days: number, now = Date.now(), featured: string | null = null): { stay: Fight[]; gone: Fight[] } {
  const sorted = [...list].sort((a, b) => b.up - a.up);
  const stay: Fight[] = [], gone: Fight[] = [];
  let counted = 0;
  for (const f of sorted) {
    if (f.kept || f.id === featured) stay.push(f);
    else if (counted < most && (days <= 0 || now - f.up <= days * 86_400_000)) { stay.push(f); counted++; }
    else gone.push(f);
  }
  return { stay, gone };
}
