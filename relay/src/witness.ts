// Proving a name from the game itself (11/15/2026, docs/protocol.md "Proving a name"; Jace: "keep the mark and start
// on stage 2"). A page claimed by the Outside plugin alone (POST /claim) is unproven: nothing the plugin says can be
// trusted, as anyone can send what it sends. What can't be faked is the future: the exact experience the plugin
// reports while they play, before the official hiscores have it, and found on the hiscores after they log out. Only
// whoever is playing that account can know it in advance.
//
//   1. As the plugin's first skills come in a session, the hiscores are read (h0).
//   2. As they log out (the game's "off", or the plugin going), the hiscores are read again (hOff), and a claim is made
//      of every skill they reported above both: gained this session, and not yet public as they logged out. A copy of
//      what's already on the hiscores (someone waiting for the real player to log out) is never above hOff.
//   3. A while after (WITNESS_WAITS), the hiscores are read once more: a claimed skill found there exactly proves it.
//      Lower: not updated yet, asked again later. Higher everywhere: they've played since; no proof this time.
// The name on the hiscores is the one the plugin says they're logged in as, and it must be the page's own (as a
// channel has it), or someone could prove another's page with their own account.
// relay.py's witness_*, the same.

export type Xp = Record<string, number>;

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

/** What's claimed at logout: each skill reported above both the session's start (h0) and the hiscores at logout. */
export function claimsOf(h0: Xp, said: Xp, hOff: Xp): Xp {
  const out: Xp = {};
  for (const [k, x] of Object.entries(said)) {
    if (h0[k] !== undefined && hOff[k] !== undefined && x > h0[k] && x > hOff[k]) out[k] = x;
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
    const r = await fetch((upstream || HISCORES) + "/m=hiscore_oldschool/index_lite.json?player=" + n, { headers });
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
