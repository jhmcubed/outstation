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
//   DELETE /fights/<channel>/<id>                    taken down (the owner's)
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
}

export function fightKey(channel: string, id: string): string {
  return `fights/${channel}/${id}.jsonl.gz`;
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
