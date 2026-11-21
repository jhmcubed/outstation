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
//   POST   /fights/<channel>?key= | Bearer <tend>    multipart: "meta" (JSON), "fight" (the recording, gzipped JSON lines)
//   GET    /fights/<channel>/<id>.jsonl.gz           the recording (a fight kept to themselves: only with the owner's)
//   POST   /fights/<channel>/<id>?public=1|0         shown to anyone, or kept to themselves (the owner's)
//          /fights/<channel>/<id>?featured=1|0       the fight the page features (its stage), one at a time (the owner's)
//   DELETE /fights/<channel>/<id>                    taken down (the owner's)
//   POST   /fights/<channel>/<id>/moment?tick=<t>    the fight's scene moment (the owner's; the body the file, at most
//                                                    MAX_MOMENT; the tick also as the X-Moment-Tick header), one a fight
//   GET    /fights/<channel>/<id>/moment.bin         the moment (whoever may see the fight)
//   DELETE /fights/<channel>/<id>/moment             the moment taken away (the owner's)
//
// The scene moment (11/21/2026; ghillie's claude/outstation.md, "Encounters on Outside: the night's design"): a moment of
// a fight its owner kept, as dots sampled from the replay's 3D scene at a tick (ghillie's replay/3d/moment.js, its file
// "OMNT" gzipped), so the profile can show it with no game files. The relay checks only that it's gzip and starts "OMNT";
// it holds nothing but the scene's colours and places (never a name: the foes in its meta are NPCs).
//
// The owner is whoever holds the page's key (the Outside plugin, the outstation) or a tending token the plugin handed in
// with it (POST /tend/<channel>, channel.ts: the link the plugin opens to the player's own page, outside.wiki/<name>#tend=).
//
// Switches, all Jace's (wrangler.jsonc's vars; his calls in ghillie's lab): FIGHTS "on" or anything else for off (every
// route then 404s, and the page shows no section); FIGHTS_PUBLIC "1" if a new fight is shown to anyone unless the player
// says otherwise, else kept to themselves until they show it; FIGHTS_MOST a page's fights at most (the oldest going
// first); FIGHTS_DAYS how long one is kept (0: until the player takes it down or the page goes).
//
// What the relay checks: the file is gzip, its first line Encounter Recorder's header (format 1 to FORMAT_MOST), at most
// MAX_FIGHT. It can't unpack a whole file in a request's few milliseconds, so the stripping is the page's to do; the
// owner's own file either way.

export const MAX_FIGHT = 8 * 1024 * 1024;
export const FORMAT_MOST = 99;
export const FIGHT_ID = /^[A-Za-z0-9_-]{22}$/;
export const FIGHTS_AN_HOUR = 20;
export const MAX_MOMENT = 2 * 1024 * 1024;
export const MOMENT_VERSION_MOST = 9;
export const MOMENTS_AN_HOUR = 30;

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
  featured?: true; // (in a list only: the one the page features)
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

export function fightNumbers(env: { FIGHTS_PUBLIC?: string; FIGHTS_MOST?: string; FIGHTS_DAYS?: string }) {
  const most = parseInt(env.FIGHTS_MOST || "");
  const days = parseFloat(env.FIGHTS_DAYS || "");
  return {
    public_default: (env.FIGHTS_PUBLIC || "").trim() === "1",
    most: most > 0 ? Math.min(most, 500) : 20,
    days: isFinite(days) && days >= 0 ? days : 30,
  };
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
  if (n > MAX_MOMENT) return [413, "a moment is 2 MB at most"];
  const t = q.get("tick") ?? request.headers.get("x-moment-tick") ?? "";
  if (!/^\d{1,7}$/.test(t.trim())) return [400, "which tick? (?tick=)"];
  const tick = parseInt(t.trim());
  if (ticks > 0 && tick > ticks + 1) return [400, "that tick is past the fight's end"];
  const file = await bodyAtMost(request, MAX_MOMENT);
  if (!file) return [413, "a moment is 2 MB at most"];
  if (!(await isMoment(file))) return [415, "that isn't a scene moment (gzipped)"];
  return { file, tick };
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
export async function readFight(request: Request): Promise<{ meta: Omit<Fight, "id" | "public" | "up">; file: Uint8Array; public: boolean | null } | [number, string]> {
  const n = parseInt(request.headers.get("content-length") || "0") || 0;
  if (n > MAX_FIGHT + 64 * 1024) return [413, "a recording is 8 MB at most (gzipped)"];
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
  };
  return { meta, file, public: typeof m.public === "boolean" ? m.public : null };
}

/** The list without what's too old or past the most, and what goes (whose files go too); newest first. days 0: none
 *  too old. */
export function trimFights(list: Fight[], most: number, days: number, now = Date.now()): { kept: Fight[]; gone: Fight[] } {
  const sorted = [...list].sort((a, b) => b.up - a.up);
  const kept: Fight[] = [], gone: Fight[] = [];
  for (const f of sorted) {
    if (kept.length < most && (days <= 0 || now - f.up <= days * 86_400_000)) kept.push(f);
    else gone.push(f);
  }
  return { kept, gone };
}
