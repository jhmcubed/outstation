// Kept for a player's Outside page (11/14/2026): what's too big to go with the game's fields, sent by the Outside plugin
// over HTTP when the player asks (each behind its own switch), kept in the channel's own storage, one of each:
//   - "bank" (Jace: "an option for people to capture their entire bank, just as the liveloadout twitch extension/plugin
//     offers"): {"items": [[id, how many], ...], "tabs": [the count in each of the bank's tabs 1-9], "names": {id:
//     [name, price]}}, the bank in its own order (the main tab's last, as the game lays it), at most BANK_SLOTS items
//   - "deathbank" (11/16/2026, Jace: "show an open Deathbank like the Hespori deathbank on someone's profile"): {"where":
//     the boss or place it's for (the plugin's own list, Hespori, Zulrah, Vorkath...), "locked": true while its fee is
//     unpaid, "items": [[id, how many], ...], "names": {id: [name, price]}}, as the game showed it when they last looked
//     in it (the Item Retrieval Service's window), at most DEATHBANK_SLOTS items; taken away when it's emptied. Or,
//     "from": "death", worked out as the death made it (what they carried less what they kept), till they look in it
//   - "encounter" (11/15/2026): the last boss they fought, {"name", "model"}, sent as the fight ends
//   - "goal" (11/17/2026, update 3; Goji Berries: "what if you could set a goal item from the plugin then it shows your kc
//     and dryness on the top of ur profile"): the item they're after, {"id", "name", "model"?, "got"?: {"at": ms,
//     "counts"?: the plugin's kill and clue counts then, {"kills": {name: n}, "clues": {tier: n}}}}; the page works out
//     how dry they are from the wiki's drop rates. Since 11/19/2026 (Cranky: "I'd like to have more than 1 goal item")
//     the rest of their goals with it, "more": [{"id", "name", "got"?}, ...], GOALS_MORE at most, each item once; the
//     one at the top is the one they chose to show turning, and the only one with a model. And since 11/21/2026 (round
//     nine) "nomodel": true on the one at the top when they chose none turning (the plugin's No 3D model): the page
//     builds a goal's model from the game's own files when it comes without one, and this tells it not to
//   - "clog" (update 7, 11/22/2026: the collection log read by Outside itself, for players who don't use RuneProfile):
//     the log as the plugin read it when it was opened, {"read": ms, "count", "max" (the game's counts then), "tabs":
//     [{"name", "pages": [{"name", "items": [ids]}]}] and "names": {id: name} (the log's pages, from the game's cache),
//     "had": [[id, how many], ...]}; or slots to add to what's kept, {"add": [[id, how many], ...]} (the chat's "New item
//     added to your collection log" lines, or a read that found fewer than the game's count), merged by clogMerge
//   - "model" (Jace: "upload a captured model of their character onto their profile page just as RuneProfile does"):
//     {"v": [x, y, z, ...] the vertices (whole numbers, the game's units, |n| <= 8192), "f": [a, b, c, ...] the faces
//     (each three vertices' places), "c": [one colour each face: the game's packed HSL, 0-65535, or -1: drawn grey
//     (textured)]}, at most MODEL_VERTICES and MODEL_FACES; the page draws it in its own way. And since 11/15/2026, each
//     optional: "g" (each face's three corners as the game lit them), "a" (opacities), "p" (draw priorities), "t" and
//     "uv" (each face's texture and its corners' coordinates times 4096), "tex" (each texture as a PNG in base64, 16 at
//     most) and "b" (the brightness the player has the game at): docs/protocol.md, "Kept for the page"
//
//   POST   /keep/<channel>/<what>?key=<key>     the JSON above (at most KEEP_MOST[what] bytes), KEEP_AN_HOUR × 2 an hour, all kinds together
//   GET    /keep/<channel>/<what>?code=<code>   {"<what>": ..., "at"} (404 none)
//   DELETE /keep/<channel>/<what>?key=<key>     taken away
//   and since 11/15/2026 the motion library: a motion is a model with its frames (and its pet's), kept under a name
//   (MOTION_NAME, lower case), MOTIONS_MOST a channel, one of a name replaced:
//   POST /keep/<channel>/motion?key=&name=   GET /keep/<channel>/motion?name=&code=   DELETE /keep/<channel>/motion?key=&name=
//   GET /keep/<channel>/motions?code=         {"motions": [{"name", "at", "frames"}], "first"}, oldest first
//   POST /keep/<channel>/motions?key=&first=  the one the page shows first ("" the still model)
// A change goes to the page's viewers, lookers and desks as {"kept": {"what", "at"}} (null "at": taken away), so the
// page fetches it again. Forgotten with the channel's game (a new key, or the channel taken back).

export const KEEP_MOST: Record<string, number> = { bank: 256 * 1024, deathbank: 64 * 1024, model: 4 * 1024 * 1024, motion: 4 * 1024 * 1024, encounter: 4 * 1024 * 1024,
  goal: 2 * 1024 * 1024,
  clog: 256 * 1024,   // (update 7, 11/22/2026: about 90 KB for a log of 1,700 slots with its pages' names and items)
  layout: 8 * 1024,   // (the page's arrangement and its theme song, 11/21/2026: read here as the others are; written by layoutWrite)
  live: 4 * 1024 * 1024, livemotion: 4 * 1024 * 1024 };
// (11/15/2026, Jace: "build the motion library"): a player's recorded motions, each under a name, MOTIONS_MOST of them
export const MOTIONS_MOST = 8, MOTION_NAME = /^[a-z0-9_ -]{1,24}$/;
// (11/15/2026, Jace: "the live view"): the model in the gear they wear now ("live"), and its live motions, one an
// animation (its id, or "idle"), LIVE_MOST at most, the oldest let go first; a new gear's live model lets the others go
export const LIVE_MOST = 24, GEAR = /^[0-9a-f]{1,16}$/, ANIM = /^(idle|[0-9]{1,6})$/;
export const MOTION_FRAMES = 60;
export const KEEP_AN_HOUR = 12;
// (11/19/2026: a goal's "more", the rest of a player's goals beside the one shown; the plugin keeps 10 in all)
export const GOALS_MORE = 19;
// (update 7: the collection log's slots, tabs, pages and a page's items, each at most; the game has about 1,700 slots in
// five tabs and 230 pages)
export const CLOG_SLOTS = 4000, CLOG_TABS = 8, CLOG_PAGES = 400, CLOG_PAGE_ITEMS = 600;
export const BANK_SLOTS = 1600, DEATHBANK_SLOTS = 200, MODEL_VERTICES = 20000, MODEL_FACES = 30000;
// (11/15/2026, Jace: "can you build the effects (spot animations) and the ground into the model viewer/capturer?"): a
// still model's effects ("fx": FX_MOST models), a motion's ("frames.fx": FX_MODELS models by key, and each frame's
// effects there, FX_MOST a frame, each its key and its vertices as a frame's), and the ground round them ("ground": a
// model, GROUND_FACES at most); docs/protocol.md, "Kept for the page"
export const FX_MOST = 4, FX_MODELS = 8, FX_KEY = /^[0-9]{1,6}\.[0-9]{1,5}\.[0-9]{1,5}$/, GROUND_FACES = 6000;
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
  // (11/16/2026: a deathbank, the Item Retrieval Service's: where, whether its fee's unpaid, its items and their names)
  if (what === "deathbank") {
    const items = d.items, names = d.names ?? {};
    if (typeof d.where !== "string" || !/^[A-Za-z0-9' .,-]{1,40}$/.test(d.where)) return "where: the deathbank's boss or place";
    if (typeof d.locked !== "boolean") return "locked: true or false";
    if (!Array.isArray(items) || !items.length || items.length > DEATHBANK_SLOTS) return "items: 1 to " + DEATHBANK_SLOTS;
    for (const s of items) if (!Array.isArray(s) || s.length !== 2 || !whole(s[0]) || !whole(s[1]) || s[1] < 1) return "items: [id, how many] each";
    if (!isObj(names)) return "names: an object";
    const n: Record<string, [string, number]> = {};
    for (const [k, v] of Object.entries(names).slice(0, DEATHBANK_SLOTS)) {
      if (ID.test(k) && Array.isArray(v) && v.length === 2 && typeof v[0] === "string" && whole(v[1])) {
        n[k] = [v[0].replace(/[\x00-\x1f\x7f]/g, " ").slice(0, 60), v[1] as number];
      }
    }
    if (d.from !== undefined && d.from !== "death") return "from: \"death\" or none";
    return { where: d.where, locked: d.locked, items: items.map((s) => [s[0], s[1]]), names: n, ...(d.from ? { from: d.from } : {}) };
  }
  if (what === "clog") return clogShape(d);
  if (what === "model") return modelShape(d, true);
  // (11/15/2026: the last boss they fought, "the encounter": its name and its model as the fight ended)
  if (what === "encounter") {
    if (typeof d.name !== "string" || !/^[A-Za-z0-9' .,-]{1,40}$/.test(d.name)) return "name: the boss's";
    if (!isObj(d.model)) return "model: the boss's";
    const m = modelShape(d.model, true);
    if (typeof m === "string") return "model's " + m;
    return { name: d.name, model: m, ...(whole(d.kc) ? { kc: d.kc } : {}) };
  }
  if (what === "goal") {
    const out = goalShape(d);
    if (typeof out === "string") return out;
    if (d.model !== undefined) {
      if (!isObj(d.model)) return "model: the item's";
      const m = modelShape(d.model, true);
      if (typeof m === "string") return "model's " + m;
      out.model = m;
    }
    // (11/21/2026, round nine: none turning, said; the page then builds none from the game's files. Only at the top, and
    // never beside a model)
    if (d.nomodel !== undefined) {
      if (d.nomodel !== true) return "nomodel: true or none";
      if (out.model) return "nomodel: a goal with a model has one";
      out.nomodel = true;
    }
    // (11/19/2026, Cranky: "I'd like to have more than 1 goal item"; Jace: "choose ONE of the goal items to be displayed
    // as a 3d model, while the rest of the goal items are listed under the first"): the rest of their goals, each as the
    // one shown is but with no model, in the order the plugin keeps them; the one at the top is the one the page turns
    if (d.more !== undefined) {
      if (!Array.isArray(d.more) || d.more.length > GOALS_MORE) return "more: " + GOALS_MORE + " goals at most";
      const more: Record<string, unknown>[] = [], ids = new Set([d.id]);
      for (const g of d.more) {
        if (!isObj(g)) return "more: each a goal";
        if (g.model !== undefined) return "more: only the goal at the top has a model";
        const one = goalShape(g);
        if (typeof one === "string") return "more's " + one;
        if (ids.has(one.id)) return "more: each item once";
        ids.add(one.id);
        more.push(one);
      }
      if (more.length) out.more = more;
    }
    return out;
  }
  if (what === "live") return modelShape(d, true);
  if (what === "motion" || what === "livemotion") {
    const m = modelShape(d, true);
    if (typeof m === "string") return m;
    if (!(m as Record<string, unknown>).frames) return "a motion has its frames";
    return m;
  }
  return "kept: bank, deathbank, clog or model";
}

const clogPairs = (v: unknown): number[][] | string => {
  if (!Array.isArray(v) || v.length > CLOG_SLOTS) return "at most " + CLOG_SLOTS + " slots";
  const out: number[][] = [], seen = new Set<number>();
  for (const s of v) {
    if (!Array.isArray(s) || s.length !== 2 || !whole(s[0], 200_000) || s[0] < 1 || !whole(s[1]) || s[1] < 1) return "[item, how many] each";
    if (!seen.has(s[0])) { seen.add(s[0]); out.push([s[0], s[1]]); }
  }
  return out;
};

/** The collection log, checked (keep.ts's header, "clog"): a whole read or slots to add, or a reason it isn't one. */
function clogShape(d: Record<string, unknown>): Record<string, unknown> | string {
  if (d.add !== undefined) {
    if (d.had !== undefined) return "clog: a whole read (had) or slots to add (add), not both";
    const add = clogPairs(d.add);
    if (typeof add === "string") return "add: " + add;
    if (!add.length) return "add: a slot at least";
    return { add };
  }
  const had = clogPairs(d.had);
  if (typeof had === "string") return "had: " + had;
  if (!whole(d.read, Number.MAX_SAFE_INTEGER) || (d.read as number) < 1_262_304_000_000) return "read: when the log was read (ms)";
  if (!whole(d.count, CLOG_SLOTS) || !whole(d.max, CLOG_SLOTS) || (d.count as number) > (d.max as number)) return "count, max: the game's counts";
  if (!Array.isArray(d.tabs) || !d.tabs.length || d.tabs.length > CLOG_TABS) return "tabs: 1 to " + CLOG_TABS;
  const tabs: unknown[] = [];
  let pages = 0;
  for (const t of d.tabs) {
    if (!isObj(t) || !Array.isArray(t.pages)) return "tabs: {name, pages} each";
    const name = plain(t.name, 40);
    if (!name) return "tabs: a name each";
    const ps: unknown[] = [];
    for (const p of t.pages) {
      if (++pages > CLOG_PAGES) return "tabs: " + CLOG_PAGES + " pages at most";
      if (!isObj(p) || !ints(p.items, 1, 200_000, CLOG_PAGE_ITEMS)) return "pages: {name, items: [ids]} each";
      const pn = plain(p.name, 60);
      if (!pn) return "pages: a name each";
      ps.push({ name: pn, items: (p.items as number[]).slice() });
    }
    tabs.push({ name, pages: ps });
  }
  if (!isObj(d.names)) return "names: {id: name}";
  const names: Record<string, string> = {};
  for (const [k, v] of Object.entries(d.names).slice(0, CLOG_SLOTS)) {
    if (ID.test(k) && typeof v === "string") names[k] = plain(v, 60);
  }
  return { read: d.read, count: d.count, max: d.max, tabs, names, had };
}

/** What's kept once a clog's taken: a whole read replaces what was; slots to add go into what's kept (a slot already
 *  there keeps its count; the count raised to the slots had), or start a log of slots alone, with no pages. */
export function clogMerge(kept: unknown, shaped: Record<string, unknown>): Record<string, unknown> {
  if (!shaped.add) return shaped;
  const was = isObj(kept) && Array.isArray(kept.had) ? kept : { had: [] as number[][] };
  const had = (was.had as number[][]).slice(0, CLOG_SLOTS), there = new Set(had.map((p) => p[0]));
  for (const p of shaped.add as number[][]) {
    if (!there.has(p[0]) && had.length < CLOG_SLOTS) { there.add(p[0]); had.push(p); }
  }
  const out: Record<string, unknown> = { ...was, had };
  if (whole(out.count)) out.count = Math.min(CLOG_SLOTS, Math.max(out.count as number, had.length));
  return out;
}

/** A goal's item and when it was got, checked (its model apart: only the goal at the top carries one), or a reason. */
function goalShape(d: Record<string, unknown>): Record<string, unknown> | string {
  if (!whole(d.id) || (d.id as number) < 1 || (d.id as number) > 200_000) return "id: the item's";
  if (typeof d.name !== "string") return "name: the item's";
  const name = d.name.replace(/[\x00-\x1f\x7f]/g, " ").trim().slice(0, 60);
  if (!name) return "name: the item's";
  const out: Record<string, unknown> = { id: d.id, name };
  if (d.got !== undefined) {
    const g = d.got as Record<string, unknown>;
    if (!isObj(g) || !whole(g.at, Number.MAX_SAFE_INTEGER) || (g.at as number) < 1_262_304_000_000) return "got: {\"at\": when, \"counts\"?}";
    const got: Record<string, unknown> = { at: g.at };
    if (g.counts !== undefined) {
      if (!isObj(g.counts)) return "got's counts: {\"kills\", \"clues\"}";
      const counts: Record<string, Record<string, number>> = {};
      for (const k of ["kills", "clues"]) {
        const src = (g.counts as Record<string, unknown>)[k];
        if (src === undefined) continue;
        if (!isObj(src)) return "got's counts' " + k + ": {name: count}";
        const kept: Record<string, number> = {};
        for (const [n, c] of Object.entries(src as Record<string, unknown>).slice(0, 400)) {
          if (typeof n === "string" && n.length <= 60 && whole(c)) kept[n.replace(/[\x00-\x1f\x7f]/g, " ")] = c as number;
        }
        counts[k] = kept;
      }
      got.counts = counts;
    }
    out.got = got;
  }
  return out;
}

/** A model's shape (keep.ts's header): the player's, which may carry its pet's ("pet": a model of its own, with no pet)
 *  and either may carry a motion ("frames": {"fps", "v": [each frame's vertices, x, y, z as little-endian 16-bit
 *  numbers in base64, as many as "v" has]}, MOTION_FRAMES at most; 11/15/2026). */
function modelShape(d: Record<string, unknown>, top: boolean, part = false): unknown | string {
  if (part && d.frames !== undefined) return "an effect or the ground has no frames";
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
    if (fr.fx !== undefined) {
      const fx = fr.fx;
      if (!isObj(fx) || !isObj(fx.m) || !Array.isArray(fx.f) || fx.f.length !== fr.v.length) return "frames.fx: {m, f}, a row each frame";
      const keys = Object.keys(fx.m);
      if (!keys.length || keys.length > FX_MODELS) return "frames.fx.m: " + FX_MODELS + " effects at most";
      const models: Record<string, unknown> = {}, counts: Record<string, number> = {};
      for (const k of keys) {
        const em = fx.m[k];
        if (!FX_KEY.test(k) || !isObj(em)) return "frames.fx.m: an effect's model under its key";
        const e = modelShape(em, false, true);
        if (typeof e === "string") return "an effect's " + e;
        models[k] = e;
        counts[k] = ((em.v as number[]).length) / 3;
      }
      const rows: unknown[] = [];
      for (const row of fx.f) {
        if (!Array.isArray(row) || row.length > FX_MOST) return "frames.fx.f: " + FX_MOST + " effects a frame at most";
        const r: unknown[] = [];
        for (const e of row) {
          if (!isObj(e) || typeof e.k !== "string" || !(e.k in counts) || typeof e.v !== "string") return "frames.fx.f: each {k, v}";
          if (e.v.length !== 4 * Math.ceil(counts[e.k] * 6 / 3) || !/^[A-Za-z0-9+/]*={0,2}$/.test(e.v)) return "frames.fx.f: each effect's every vertex, in base64";
          r.push({ k: e.k, v: e.v });
        }
        rows.push(r);
      }
      (out.frames as Record<string, unknown>).fx = { m: models, f: rows };
    }
  }
  if (top) {
    if (d.pet !== undefined) {
      if (!isObj(d.pet)) return "pet: a model";
      const pet = modelShape(d.pet, false);
      if (typeof pet === "string") return "pet's " + pet;
      out.pet = pet;
    }
    if (d.fx !== undefined) {
      if (!Array.isArray(d.fx) || !d.fx.length || d.fx.length > FX_MOST) return "fx: " + FX_MOST + " effects at most";
      const fx: unknown[] = [];
      for (const em of d.fx) {
        if (!isObj(em)) return "fx: each a model";
        const e = modelShape(em, false, true);
        if (typeof e === "string") return "an effect's " + e;
        fx.push(e);
      }
      out.fx = fx;
    }
    if (d.ground !== undefined) {
      if (!isObj(d.ground)) return "ground: a model";
      if (Array.isArray(d.ground.f) && d.ground.f.length > 3 * GROUND_FACES) return "ground: " + GROUND_FACES + " faces at most";
      const g = modelShape(d.ground, false, true);
      if (typeof g === "string") return "the ground's " + g;
      out.ground = g;
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

// The page's arrangement and its theme song (11/21/2026, update 6; Jace, of editing his own page and adding a song:
// "Build it, they should be a part of update 6"; ghillie's claude/outstation.md, "Arrange the file"). Kept as the keep's
// "layout", one of a channel, every part optional and each replaced on its own (a POST carries the parts it changes;
// null takes one away):
//   "arrange": the readings' sections (the page's arrange.js): {"boxes": {"reading.0": [ids], "reading.1": [ids],
//              "wide.0": [ids]}, "off": [ids kept from visitors]}
//   "hero":    the top of the file's panels on twelve columns (hero.js): {"p": {key: {"x", "w"}}, "o": [keys, in the
//              order they're packed], "off": [keys kept from visitors]}
//   "theme":   the subject's theme (theme.js): {"link", "title"?, "by"?, "start"?} (SoundCloud only, round nine); the link read as theme.js reads it, a SoundCloud,
//              YouTube or Spotify song's own address and nothing else, and kept in its plain form
// Only the sections and panels the page has today: a new one is added to these lists (at the end) as the page gains it.
export const ARRANGE_READING = ["sec-skills", "sec-deeds", "sec-mega", "sec-pets", "sec-next", "sec-easy-ca", "sec-gains", "sec-movements",
  "sec-kills", "sec-rates", "sec-field", "sec-delve"];
export const ARRANGE_WIDE = ["sec-year", "sec-heat", "sec-case"];
// (11/21/2026, round nine: "reports", the readings and the field reports, and "talk", the register and the aether, panels
// of their own apart from the outstation's screen, which keeps only the transmission and its chat; hero.js's KNOWN)
export const HERO_PANELS = ["who", "screen", "stage", "kit", "form", "goal", "where", "encounter", "clips", "bank", "reports", "talk"];
// (the subject's box, the outstation and the encounters' stage stay where every file has them, always shown)
export const HERO_FIXED = ["who", "screen", "stage"];
export const HERO_COLS = 12, LAYOUT_MOST = 16 * 1024;
// (a set's tracks kept with the theme, 50 at most: round nine; the layout's most raised from 8 KB for them)
export const THEME_TRACKS = 50;
// (written by the page as its owner arranges it, after "done" and as a song's set: its own count, apart from the plugin's
// uploads, so arranging never uses up what "Update my page" needs)
export const LAYOUT_AN_HOUR = 60;

/** A theme's link read (theme.js's read, the same): {kind, id, what?, start?, link} with the link in its plain form, or
 *  null when it isn't a song's or a set's own address on one of the three. */
export function themeLink(text: unknown): Record<string, unknown> | null {
  if (typeof text !== "string" || text.length > 400) return null;
  let u: URL;
  try { u = new URL(text.trim()); } catch { return null; }
  if (u.protocol !== "https:" && u.protocol !== "http:") return null;
  if (u.username || u.password || u.port) return null;
  const host = u.hostname.toLowerCase().replace(/^(www|m|music)\./, ""), path = u.pathname;
  const t = u.searchParams.get("t") || u.searchParams.get("start") || "";
  const n = /^[0-9]{1,6}/.test(t) ? parseInt(t, 10) : 0;
  const start = n > 0 && n < 86_400 ? n : 0;
  const yt = (id: string) => ({ kind: "youtube", id, ...(start ? { start } : {}),
    link: "https://www.youtube.com/watch?v=" + id + (start ? "&t=" + start : "") });
  let m: RegExpExecArray | null;
  const v = u.searchParams.get("v") || "";
  if (host === "youtube.com" && path === "/watch" && /^[\w-]{11}$/.test(v)) return yt(v);
  if (host === "youtube.com" && (m = /^\/(?:shorts|embed|live)\/([\w-]{11})(?:\/|$)/.exec(path))) return yt(m[1]);
  if (host === "youtu.be" && (m = /^\/([\w-]{11})\/?$/.exec(path))) return yt(m[1]);
  if (host === "soundcloud.com" && (m = /^\/([\w-]{1,100})\/((?:sets\/)?[\w-]{1,200})\/?$/.exec(path))) {
    const id = "https://soundcloud.com/" + m[1] + "/" + m[2];
    return { kind: "soundcloud", id, link: id };
  }
  if (host === "open.spotify.com" && (m = /^\/(?:intl-[\w-]{2,10}\/)?(track|album|playlist|episode)\/([A-Za-z0-9]{22})\/?$/.exec(path))) {
    return { kind: "spotify", what: m[1], id: m[2], link: "https://open.spotify.com/" + m[1] + "/" + m[2] };
  }
  return null;
}

const plain = (s: unknown, most: number) => typeof s === "string" ? s.replace(/[\x00-\x1f\x7f\u2028\u2029]/g, " ").replace(/\s+/g, " ").trim().slice(0, most) : "";

/** A layout's parts, checked (layout's header above): a copy with only what's known, or a reason it isn't one. A part
 *  that's null stays null (taken away). */
export function layoutShape(d: unknown): Record<string, unknown> | string {
  if (!isObj(d)) return "not a JSON object";
  const out: Record<string, unknown> = {};
  for (const k of Object.keys(d)) if (k !== "arrange" && k !== "hero" && k !== "theme") return "layout: arrange, hero and theme only";
  if (!Object.keys(d).length) return "layout: arrange, hero or theme";
  if (d.arrange !== undefined) {
    const a = d.arrange;
    if (a === null) out.arrange = null;
    else {
      if (!isObj(a) || !isObj(a.boxes) || !Array.isArray(a.off)) return "arrange: {boxes, off}";
      for (const k of Object.keys(a)) if (k !== "boxes" && k !== "off") return "arrange: boxes and off only";
      const boxes: Record<string, string[]> = {}, seen = new Set<string>();
      for (const [box, ids] of Object.entries(a.boxes)) {
        const known = box === "reading.0" || box === "reading.1" ? ARRANGE_READING : box === "wide.0" ? ARRANGE_WIDE : null;
        if (!known) return "arrange's boxes: reading.0, reading.1 and wide.0";
        if (!Array.isArray(ids) || ids.length > known.length) return "arrange's " + box + ": its sections";
        for (const id of ids) {
          if (typeof id !== "string" || !known.includes(id)) return "arrange's " + box + ": a section it doesn't hold";
          if (seen.has(id)) return "arrange: each section once";
          seen.add(id);
        }
        boxes[box] = ids.slice();
      }
      const all = [...ARRANGE_READING, ...ARRANGE_WIDE], off: string[] = [];
      if (a.off.length > all.length) return "arrange's off: its sections";
      for (const id of a.off) {
        if (typeof id !== "string" || !all.includes(id)) return "arrange's off: a section the page doesn't have";
        if (!off.includes(id)) off.push(id);
      }
      out.arrange = { boxes, off };
    }
  }
  if (d.hero !== undefined) {
    const h = d.hero;
    if (h === null) out.hero = null;
    else {
      if (!isObj(h) || !isObj(h.p) || !Array.isArray(h.o) || !Array.isArray(h.off)) return "hero: {p, o, off}";
      for (const k of Object.keys(h)) if (k !== "p" && k !== "o" && k !== "off") return "hero: p, o and off only";
      const p: Record<string, { x: number; w: number }> = {};
      for (const [k, s] of Object.entries(h.p)) {
        if (!HERO_PANELS.includes(k)) return "hero's p: a panel the page doesn't have";
        if (!isObj(s) || !whole(s.x, HERO_COLS - 1) || !whole(s.w, HERO_COLS) || (s.w as number) < 1 || (s.x as number) + (s.w as number) > HERO_COLS) return "hero's p: {x, w} on " + HERO_COLS + " columns";
        p[k] = { x: s.x as number, w: s.w as number };
      }
      const o: string[] = [];
      if (h.o.length > HERO_PANELS.length) return "hero's o: its panels";
      for (const k of h.o) {
        if (typeof k !== "string" || !HERO_PANELS.includes(k)) return "hero's o: a panel the page doesn't have";
        if (o.includes(k)) return "hero's o: each panel once";
        o.push(k);
      }
      const off: string[] = [];
      if (h.off.length > HERO_PANELS.length) return "hero's off: its panels";
      for (const k of h.off) {
        if (typeof k !== "string" || !HERO_PANELS.includes(k)) return "hero's off: a panel the page doesn't have";
        if (HERO_FIXED.includes(k)) return "hero's off: the subject, the outstation and the encounters are always shown";
        if (!off.includes(k)) off.push(k);
      }
      out.hero = { p, o, off };
    }
  }
  if (d.theme !== undefined) {
    const t = d.theme;
    if (t === null) out.theme = null;
    else {
      // (SoundCloud only since 11/21/2026, round nine, Jace: "let's only allow soundcloud links since all spotify and
      // youtube songs can be found on there anyway", the page's own player driving SoundCloud's; and where it starts,
      // whole seconds, the page's "starts at")
      if (!isObj(t)) return "theme: {link, title?, by?, start?}";
      for (const k of Object.keys(t)) if (k !== "link" && k !== "title" && k !== "by" && k !== "start" && k !== "tracks") return "theme: link, title, by, start and tracks only";
      const read = themeLink(t.link);
      if (!read || read.kind !== "soundcloud") return "theme's link: a song's or a set's own address on soundcloud";
      if (t.title !== undefined && typeof t.title !== "string") return "theme's title: words";
      if (t.by !== undefined && typeof t.by !== "string") return "theme's by: words";
      if (t.start !== undefined && !(Number.isInteger(t.start) && (t.start as number) >= 0 && (t.start as number) < 86_400)) return "theme's start: whole seconds into the song";
      // (a set's tracklist, round nine, Jace: "If someone links a soundcloud playlist could we show a small tracklist?":
      // as the owner's page read it from SoundCloud's player when the set was chosen, so a visitor's page shows it
      // without asking SoundCloud anything; each track's title, who made it and its length, 50 at most)
      let tracks: { title: string; by?: string; dur?: number }[] | null = null;
      if (t.tracks !== undefined) {
        if (!Array.isArray(t.tracks) || t.tracks.length > THEME_TRACKS) return `theme's tracks: a list, ${THEME_TRACKS} at most`;
        if (t.tracks.length && !/\/sets\//.test(String(read.id))) return "theme's tracks: only for a set";
        tracks = [];
        for (const x of t.tracks) {
          if (!isObj(x)) return "theme's tracks: {title, by?, dur?} each";
          for (const k of Object.keys(x)) if (k !== "title" && k !== "by" && k !== "dur") return "theme's tracks: title, by and dur only";
          if (typeof x.title !== "string" || (x.by !== undefined && typeof x.by !== "string")) return "theme's tracks: words";
          if (x.dur !== undefined && !(Number.isInteger(x.dur) && (x.dur as number) >= 0 && (x.dur as number) < 86_400_000)) return "theme's tracks: a length in whole milliseconds";
          const tt = plain(x.title, 120), tb = plain(x.by, 60);
          tracks.push({ title: tt, ...(tb ? { by: tb } : {}), ...(x.dur ? { dur: x.dur as number } : {}) });
        }
      }
      const title = plain(t.title, 120), by = plain(t.by, 60), start = (t.start as number) || 0;
      out.theme = { ...read, ...(title ? { title } : {}), ...(by ? { by } : {}), ...(start ? { start } : {}), ...(tracks && tracks.length ? { tracks } : {}) };
    }
  }
  return out;
}
