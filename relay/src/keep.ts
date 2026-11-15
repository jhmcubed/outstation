// Kept for a player's Outside page (11/14/2026): what's too big to go with the game's fields, sent by the Outside plugin
// over HTTP when the player asks (each behind its own switch), kept in the channel's own storage, one of each:
//   - "bank" (Jace: "an option for people to capture their entire bank, just as the liveloadout twitch extension/plugin
//     offers"): {"items": [[id, how many], ...], "tabs": [the count in each of the bank's tabs 1-9], "names": {id:
//     [name, price]}}, the bank in its own order (the main tab's last, as the game lays it), at most BANK_SLOTS items
//   - "encounter" (11/15/2026): the last boss they fought, {"name", "model"}, sent as the fight ends
//   - "model" (Jace: "upload a captured model of their character onto their profile page just as RuneProfile does"):
//     {"v": [x, y, z, ...] the vertices (whole numbers, the game's units, |n| <= 8192), "f": [a, b, c, ...] the faces
//     (each three vertices' places), "c": [one colour each face: the game's packed HSL, 0-65535, or -1: drawn grey
//     (textured)]}, at most MODEL_VERTICES and MODEL_FACES; the page draws it in its own way. And since 11/15/2026, each
//     optional: "g" (each face's three corners as the game lit them), "a" (opacities), "p" (draw priorities), "t" and
//     "uv" (each face's texture and its corners' coordinates times 4096), "tex" (each texture as a PNG in base64, 16 at
//     most) and "b" (the brightness the player has the game at): docs/protocol.md, "Kept for the page"
//
//   POST   /keep/<channel>/<what>?key=<key>     the JSON above (at most KEEP_MOST[what] bytes), KEEP_AN_HOUR an hour
//   GET    /keep/<channel>/<what>?code=<code>   {"<what>": ..., "at"} (404 none)
//   DELETE /keep/<channel>/<what>?key=<key>     taken away
//   and since 11/15/2026 the motion library: a motion is a model with its frames (and its pet's), kept under a name
//   (MOTION_NAME, lower case), MOTIONS_MOST a channel, one of a name replaced:
//   POST /keep/<channel>/motion?key=&name=   GET /keep/<channel>/motion?name=&code=   DELETE /keep/<channel>/motion?key=&name=
//   GET /keep/<channel>/motions?code=         {"motions": [{"name", "at", "frames"}], "first"}, oldest first
//   POST /keep/<channel>/motions?key=&first=  the one the page shows first ("" the still model)
// A change goes to the page's viewers, lookers and desks as {"kept": {"what", "at"}} (null "at": taken away), so the
// page fetches it again. Forgotten with the channel's game (a new key, or the channel taken back).

export const KEEP_MOST: Record<string, number> = { bank: 256 * 1024, model: 4 * 1024 * 1024, motion: 4 * 1024 * 1024, encounter: 4 * 1024 * 1024 };
// (11/15/2026, Jace: "build the motion library"): a player's recorded motions, each under a name, MOTIONS_MOST of them
export const MOTIONS_MOST = 8, MOTION_NAME = /^[a-z0-9_ -]{1,24}$/;
export const MOTION_FRAMES = 60;
export const KEEP_AN_HOUR = 12;
export const BANK_SLOTS = 1600, MODEL_VERTICES = 20000, MODEL_FACES = 30000;
const ID = /^(0|[1-9][0-9]{0,9})$/;
const INT = 2 ** 31 - 1;

const isObj = (v: unknown): v is Record<string, unknown> => !!v && typeof v === "object" && !Array.isArray(v);
const whole = (v: unknown, most = INT) => typeof v === "number" && Number.isInteger(v) && v >= 0 && v <= most;
const ints = (v: unknown, lo: number, hi: number, most: number) =>
  Array.isArray(v) && v.length <= most && v.every((x) => typeof x === "number" && Number.isInteger(x) && x >= lo && x <= hi);

/** What's kept, checked: the bank's or the model's shape (a copy, nothing else in it), or a reason it isn't. */
export function keepShape(what: string, d: unknown): unknown | string {
  if (!isObj(d)) return "not a JSON object";
  if (what === "bank") {
    const items = d.items, tabs = d.tabs ?? [], names = d.names ?? {};
    if (!Array.isArray(items) || items.length > BANK_SLOTS) return "items: at most " + BANK_SLOTS;
    for (const s of items) if (!Array.isArray(s) || s.length !== 2 || !whole(s[0]) || !whole(s[1])) return "items: [id, how many] each";
    if (!ints(tabs, 0, BANK_SLOTS, 9)) return "tabs: at most 9 counts";
    if (!isObj(names)) return "names: an object";
    const n: Record<string, [string, number]> = {};
    for (const [k, v] of Object.entries(names).slice(0, BANK_SLOTS)) {
      if (ID.test(k) && Array.isArray(v) && v.length === 2 && typeof v[0] === "string" && whole(v[1])) {
        n[k] = [v[0].replace(/[\x00-\x1f\x7f]/g, " ").slice(0, 60), v[1] as number];
      }
    }
    return { items: items.map((s) => [s[0], s[1]]), tabs, names: n };
  }
  if (what === "model") return modelShape(d, true);
  // (11/15/2026: the last boss they fought, "the encounter": its name and its model as the fight ended)
  if (what === "encounter") {
    if (typeof d.name !== "string" || !/^[A-Za-z0-9' .,-]{1,40}$/.test(d.name)) return "name: the boss's";
    if (!isObj(d.model)) return "model: the boss's";
    const m = modelShape(d.model, true);
    if (typeof m === "string") return "model's " + m;
    return { name: d.name, model: m, ...(whole(d.kc) ? { kc: d.kc } : {}) };
  }
  if (what === "motion") {
    const m = modelShape(d, true);
    if (typeof m === "string") return m;
    if (!(m as Record<string, unknown>).frames) return "a motion has its frames";
    return m;
  }
  return "kept: bank or model";
}

/** A model's shape (keep.ts's header): the player's, which may carry its pet's ("pet": a model of its own, with no pet)
 *  and either may carry a motion ("frames": {"fps", "v": [each frame's vertices, x, y, z as little-endian 16-bit
 *  numbers in base64, as many as "v" has]}, MOTION_FRAMES at most; 11/15/2026). */
function modelShape(d: Record<string, unknown>, top: boolean): unknown | string {
  const v = d.v, f = d.f, c = d.c;
  if (!ints(v, -8192, 8192, 3 * MODEL_VERTICES) || (v as number[]).length % 3) return "v: x, y, z for each vertex";
  const nv = (v as number[]).length / 3;
  if (!ints(f, 0, nv - 1, 3 * MODEL_FACES) || (f as number[]).length % 3) return "f: three vertices for each face";
  if (!ints(c, -1, 65535, MODEL_FACES) || (c as number[]).length !== (f as number[]).length / 3) return "c: a colour for each face";
  // (11/15/2026: the game's own lighting and textures, for the page to draw it as the game does; each optional)
  const nf = (f as number[]).length / 3, out: Record<string, unknown> = { v, f, c };
  if (d.g !== undefined) { if (!ints(d.g, -1, 65535, 3 * MODEL_FACES) || (d.g as number[]).length !== 3 * nf) return "g: three lit colours for each face"; out.g = d.g; }
  if (d.a !== undefined) { if (!ints(d.a, 0, 255, MODEL_FACES) || (d.a as number[]).length !== nf) return "a: an opacity for each face"; out.a = d.a; }
  if (d.p !== undefined) { if (!ints(d.p, 0, 255, MODEL_FACES) || (d.p as number[]).length !== nf) return "p: a priority for each face"; out.p = d.p; }
  if (d.t !== undefined || d.uv !== undefined) {
    if (!ints(d.t, -1, 65535, MODEL_FACES) || (d.t as number[]).length !== nf) return "t: a texture for each face";
    if (!ints(d.uv, -1_000_000, 1_000_000, 6 * MODEL_FACES) || (d.uv as number[]).length !== 6 * nf) return "uv: six for each face";
    out.t = d.t; out.uv = d.uv;
  }
  if (d.frames !== undefined) {
    const fr = d.frames;
    if (!isObj(fr) || typeof fr.fps !== "number" || !(fr.fps > 0 && fr.fps <= 60) || !Array.isArray(fr.v) || !fr.v.length || fr.v.length > MOTION_FRAMES) return "frames: {fps, v}, " + MOTION_FRAMES + " at most";
    const len = 4 * Math.ceil(nv * 6 / 3);
    for (const x of fr.v) if (typeof x !== "string" || x.length !== len || !/^[A-Za-z0-9+/]*={0,2}$/.test(x)) return "frames: each frame every vertex, in base64";
    out.frames = { fps: fr.fps, v: fr.v };
  }
  if (top) {
    if (d.pet !== undefined) {
      if (!isObj(d.pet)) return "pet: a model";
      const pet = modelShape(d.pet, false);
      if (typeof pet === "string") return "pet's " + pet;
      out.pet = pet;
    }
    if (d.tex !== undefined) {
      if (!isObj(d.tex) || Object.keys(d.tex).length > 16) return "tex: 16 textures at most";
      const tex: Record<string, string> = {};
      for (const [k, b64] of Object.entries(d.tex)) {
        if (!ID.test(k) || typeof b64 !== "string" || b64.length > 96 * 1024 || !/^[A-Za-z0-9+/=]*$/.test(b64)) return "tex: a PNG in base64 for each texture id";
        tex[k] = b64;
      }
      out.tex = tex;
    }
    if (d.b !== undefined) { if (typeof d.b !== "number" || !(d.b >= 0.3 && d.b <= 1.2)) return "b: the game's brightness"; out.b = d.b; }
  }
  return out;
}
