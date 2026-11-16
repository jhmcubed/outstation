// Proving a name from the game itself (11/15/2026, docs/protocol.md "Proving a name"; Jace: "keep the mark and start
// on stage 2"). A page claimed by the Outside plugin alone (POST /claim) is unproven: nothing the plugin says can be
// trusted, as anyone can send what it sends. What can't be faked is the future: the exact experience the plugin
// reports while they play, before the official hiscores have it, and found on the hiscores after they log out. Only
// whoever is playing that account can know it in advance.
//
//   1. While they're logged in the hiscores can't have this session's experience (Jagex writes it on logout). So
//      WITNESS_LOOK after a skill's experience last changed, the hiscores are read (a look): every skill the plugin last
//      said above what they show is marked hidden, its figure not public yet.
//   2. As they log out (the game's "off", or the plugin going), the hidden figures are claimed. A copy of what's
//      already on the hiscores (someone waiting for the real player to log out) is never hidden: a look shows it.
//   3. A while after (WITNESS_WAITS), the hiscores are read again: a claimed skill found there exactly proves it.
//      Lower: not updated yet, asked again later. Higher everywhere: they've played since; no proof this time.
// (11/15/2026, Jace's first try on his alt: the first way read the hiscores at the logout itself and kept what wasn't
// there yet, but Jagex's update comes with the logout, so his new experience was already public and nothing was kept.)
// The name on the hiscores is the one the plugin says they're logged in as, and it must be the page's own (as a
// channel has it), or someone could prove another's page with their own account.
// relay.py's witness_*, the same.

export type Xp = Record<string, number>;

/** How long after a skill's experience last changed the hiscores are looked at, while they're still logged in. */
export const WITNESS_LOOK = 45_000;

/** A skill as the plugin last said it: its experience, when that came, and whether a look since found it not public. */
export interface Seen { x: number; at: number; hidden?: boolean }

/** What the plugin says, against what was seen: a changed figure is new (not yet looked at). Whether any changed. */
export function noteSaid(seen: Record<string, Seen>, said: Xp, now: number): boolean {
  let changed = false;
  for (const [k, x] of Object.entries(said)) {
    if (seen[k]?.x === x) continue;
    seen[k] = { x, at: now };
    changed = true;
  }
  return changed;
}

/** A look (the hiscores read at `at`, while they were logged in): every figure said before it and above what the
 *  hiscores show is hidden; one they show already isn't. */
export function noteLook(seen: Record<string, Seen>, h: Xp, at: number) {
  for (const [k, v] of Object.entries(seen)) {
    if (v.at <= at && h[k] !== undefined) v.hidden = v.x > h[k];
  }
}

/** The figures claimed at logout: the hidden ones. */
export function claimsFrom(seen: Record<string, Seen>): Xp {
  const out: Xp = {};
  for (const [k, v] of Object.entries(seen)) if (v.hidden) out[k] = v.x;
  return out;
}

/** When the hiscores are asked again after a logout, from it: Jagex's update comes on logout, and its pages can lag. */
export const WITNESS_WAITS = [60_000, 180_000, 600_000, 1_800_000];

export const HISCORES = "https://secure.runescape.com";
export const WOM = "https://api.wiseoldman.net";

/** A player's name as a channel has it (the plugin's channelName): lower case, spaces, no-break spaces, - and _ out. */
export function channelForm(name: string): string {
  return name.toLowerCase().replace(/[\s _-]/g, "");
}

/** A skill's name as both sources agree on it: lower case, Wise Old Man's "runecrafting" as the game's "runecraft". */
function skillKey(name: string): string {
  const k = name.toLowerCase();
  return k === "runecrafting" ? "runecraft" : k;
}

/** Jagex's index_lite.json ({"skills": [{"name", "xp"}]}): each ranked skill's experience; -1 (unranked) left out. */
export function fromHiscores(j: unknown): Xp | null {
  const skills = (j as { skills?: unknown })?.skills;
  if (!Array.isArray(skills)) return null;
  const out: Xp = {};
  for (const s of skills) {
    const n = (s as { name?: unknown })?.name, x = (s as { xp?: unknown })?.xp;
    if (typeof n === "string" && n !== "Overall" && Number.isInteger(x) && (x as number) >= 0) out[skillKey(n)] = x as number;
  }
  return out;
}

/** Wise Old Man's player ({"latestSnapshot": {"data": {"skills": {"attack": {"experience"}}}}}), the same way. */
export function fromWom(j: unknown): Xp | null {
  const skills = (j as { latestSnapshot?: { data?: { skills?: unknown } } })?.latestSnapshot?.data?.skills;
  if (!skills || typeof skills !== "object") return null;
  const out: Xp = {};
  for (const [n, v] of Object.entries(skills as Record<string, { experience?: unknown }>)) {
    const x = v?.experience;
    if (n !== "overall" && Number.isInteger(x) && (x as number) >= 0) out[skillKey(n)] = x as number;
  }
  return out;
}

/** The plugin's skills ({"Attack": [level, boosted, xp]}) as experience by skill. */
export function reported(skills: unknown): Xp {
  const out: Xp = {};
  if (!skills || typeof skills !== "object") return out;
  for (const [n, v] of Object.entries(skills as Record<string, unknown>)) {
    if (Array.isArray(v) && Number.isInteger(v[2])) out[skillKey(n)] = v[2] as number;
  }
  return out;
}


/** The hiscores after against the claims: "proven" (one found exactly), "wait" (some not there yet) or "no". */
export function judge(claims: Xp, h1: Xp): "proven" | "wait" | "no" {
  let wait = false;
  for (const [k, x] of Object.entries(claims)) {
    if (h1[k] === x) return "proven";
    if (h1[k] !== undefined && h1[k] < x) wait = true;
  }
  return wait ? "wait" : "no";
}

/** A name's experience from the official hiscores, else Wise Old Man (`fresh`: its update, a new read of the hiscores);
 *  null when neither answers. `upstream`: the tests' stand-in for both hosts. */
export async function readXp(name: string, fresh: boolean, upstream: string | null, agent: string): Promise<Xp | null> {
  const n = encodeURIComponent(name.replace(/ /g, " "));
  const headers = { "user-agent": agent, accept: "application/json" };
  try {
    // (a cache in front of the hiscores can answer with an older page: each read asks for one of its own)
    const r = await fetch((upstream || HISCORES) + "/m=hiscore_oldschool/index_lite.json?player=" + n
      + (upstream ? "" : "&t=" + Date.now()), { headers: { ...headers, "cache-control": "no-cache" } });
    if (r.ok) {
      const x = fromHiscores(await r.json());
      if (x) return x;
    }
  } catch { /* (Wise Old Man's, below) */ }
  try {
    const r = await fetch((upstream || WOM) + "/v2/players/" + n, { method: fresh ? "POST" : "GET", headers });
    if (r.ok) return fromWom(await r.json());
  } catch { /* (neither) */ }
  return null;
}
