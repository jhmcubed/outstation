// Kept for a player's Outside page (11/14/2026): what's too big to go with the game's fields, sent by the Outside plugin
// over HTTP when the player asks (each behind its own switch), kept in the channel's own storage, one of each:
//   - "bank" (Jace: "an option for people to capture their entire bank, just as the liveloadout twitch extension/plugin
//     offers"): {"items": [[id, how many], ...], "tabs": [the count in each of the bank's tabs 1-9], "names": {id:
//     [name, price]}}, the bank in its own order (the main tab's last, as the game lays it), at most BANK_SLOTS items
//   - "model" (Jace: "upload a captured model of their character onto their profile page just as RuneProfile does"):
//     {"v": [x, y, z, ...] the vertices (whole numbers, the game's units, |n| <= 8192), "f": [a, b, c, ...] the faces
//     (each three vertices' places), "c": [one colour each face: the game's packed HSL, 0-65535, or -1: drawn grey
//     (textured)]}, at most MODEL_VERTICES and MODEL_FACES; the page draws it in its own way
//
//   POST   /keep/<channel>/<what>?key=<key>     the JSON above (at most KEEP_MOST[what] bytes), KEEP_AN_HOUR an hour
//   GET    /keep/<channel>/<what>?code=<code>   {"<what>": ..., "at"} (404 none)
//   DELETE /keep/<channel>/<what>?key=<key>     taken away
// A change goes to the page's viewers, lookers and desks as {"kept": {"what", "at"}} (null "at": taken away), so the
// page fetches it again. Forgotten with the channel's game (a new key, or the channel taken back).

export const KEEP_MOST: Record<string, number> = { bank: 256 * 1024, model: 640 * 1024 };
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
  if (what === "model") {
    const v = d.v, f = d.f, c = d.c;
    if (!ints(v, -8192, 8192, 3 * MODEL_VERTICES) || (v as number[]).length % 3) return "v: x, y, z for each vertex";
    const nv = (v as number[]).length / 3;
    if (!ints(f, 0, nv - 1, 3 * MODEL_FACES) || (f as number[]).length % 3) return "f: three vertices for each face";
    if (!ints(c, -1, 65535, MODEL_FACES) || (c as number[]).length !== (f as number[]).length / 3) return "c: a colour for each face";
    return { v, f, c };
  }
  return "kept: bank or model";
}
