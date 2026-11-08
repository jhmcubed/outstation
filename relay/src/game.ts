// The game (11/07/2026; docs/protocol.md "The game"): the Outside plugin, a RuneLite plugin of our own, sends the player's
// live game state on a socket of its own, and Outside (the viewer page) draws it beside the transmission and the chat,
// live, and as last known while dark.
//
//   ws(s)://<relay>/game/<channel>?key=<key>&v=<the plugin's version>   {"game": {...fields}}, at most GAME_MESSAGE long,
//                                                                       GAME_A_SECOND a second
//   GET <relay>/game/<channel>?code=<watch code>                         {"game": {...}, "at", "connected"}
// and, apart from the game's fields, "where" (11/08/2026: following, and the world with it; gameWhere, below) and
// "activity" (11/08/2026: what they're doing; gameActivity), only ever on GET /channels; and "star" (11/08/2026: a
// shooting star they found; gameStar), only ever on GET /stars, with nobody's name (stars.ts).
//
// Here: a message's fields checked and cleaned (gameFields), and the state kept from them (GameState): each field
// replaces the one kept, but "items", a name and a price for each item id, which is merged. channel.ts has the socket,
// who hears what changed, and the saving. relay-py/relay.py's game_fields and Game, the same.

import { oneLine, pyjson } from "./text";

// a message at most GAME_MESSAGE long, and GAME_A_SECOND a second (more are dropped); the kept state at most GAME_KEPT as
// JSON (a message that would take it past is ignored), its dictionary of item names and prices at most GAME_ITEMS ids
// (past that, new ones are ignored). relay.py's, the same.
export const GAME_MESSAGE = 64 * 1024, GAME_KEPT = 192 * 1024, GAME_ITEMS = 3000, GAME_A_SECOND = 5;
const GAME_INT = 2 ** 31 - 1; // the most any number in the game holds (a Java int: RuneLite's)
export const SKILL = /^[A-Za-z][A-Za-z _-]{0,23}$/; // a skill's name (the Array's events' too: array.ts)
const ID = /^(0|[1-9][0-9]{0,9})$/; // an item's id as the dictionary has it
const ITEMS = '"items": {}'.length;

type Slot = [number, number] | null;
type Item = [string, number]; // its name, its price on the Grand Exchange
export type Fields = Record<string, unknown> & { items?: Record<string, Item> };

const isObj = (v: unknown): v is Record<string, unknown> => !!v && typeof v === "object" && !Array.isArray(v);

/** A whole number from 0 to most, or undefined. */
function whole(v: unknown, most = GAME_INT): number | undefined {
  return typeof v === "number" && Number.isInteger(v) && v >= 0 && v <= most ? v : undefined;
}

/** [a, b], two whole numbers (an item and how many; a vital now and at most), or undefined. */
function pair(v: unknown): [number, number] | undefined {
  if (!Array.isArray(v) || v.length !== 2) return undefined;
  const a = whole(v[0]), b = whole(v[1]);
  return a !== undefined && b !== undefined ? [a, b] : undefined;
}

/** At most `most` slots, each [item, how many] or null (empty), or undefined. */
function slots(v: unknown, most: number): Slot[] | undefined {
  if (!Array.isArray(v) || v.length > most) return undefined;
  const out: Slot[] = [];
  for (const s of v) {
    const p = s === null ? null : pair(s);
    if (p === undefined) return undefined;
    out.push(p);
  }
  return out;
}

/** A {"game"} message's fields as they're kept: each known field checked and cleaned (its words one line, its numbers
 *  whole), one that isn't its shape left out, and unknown ones left out. Always in this order, whatever order they came
 *  in, so both relays write them out the same; "items" in id order (as an object keeps number-like keys: relay.py sorts
 *  them so). */
export function gameFields(g: unknown): Fields {
  const out: Fields = {};
  if (!isObj(g)) return out;
  if (typeof g.name === "string") out.name = oneLine(g.name, 12);
  if (whole(g.world) !== undefined) out.world = g.world;
  if (typeof g.type === "string") out.type = oneLine(g.type, 40);
  if (typeof g.off === "boolean") out.off = g.off;
  // (why it's stopped sending what's worn and carried, for the player's safety: "wilderness"; false, carrying on)
  if (g.paused === false || typeof g.paused === "string") out.paused = g.paused === false ? false : oneLine(g.paused, 32);
  if (isObj(g.vitals)) {
    const v = g.vitals, vitals: Record<string, unknown> = {};
    for (const k of ["hp", "prayer"]) { const p = pair(v[k]); if (p) vitals[k] = p; }
    for (const k of ["run", "spec"]) { const n = whole(v[k], 100); if (n !== undefined) vitals[k] = n; }
    out.vitals = vitals;
  }
  if (isObj(g.skills)) {
    const skills: Record<string, number[]> = {};
    for (const [k, v] of Object.entries(g.skills)) {
      if (SKILL.test(k) && Array.isArray(v) && v.length === 3 && v.every((x) => whole(x) !== undefined)) skills[k] = v as number[];
    }
    out.skills = skills;
  }
  for (const [k, most] of [["worn", 14], ["inv", 28], ["bag", 28], ["pouch", 8]] as const) {
    if (g[k] === undefined) continue;
    const s = g[k] === null && k === "bag" ? null : slots(g[k], most); // (the bag: null, not known yet)
    if (s !== undefined) out[k] = s;
  }
  if (g.quiver !== undefined) {
    const s = g.quiver === null ? null : pair(g.quiver);
    if (s !== undefined) out.quiver = s;
  }
  const p = g.prayers;
  if (Array.isArray(p) && p.length <= 64 && p.every((x) => typeof x === "string")) out.prayers = p.map((x) => oneLine(x, 40));
  if (isObj(g.items)) {
    const items: Record<string, Item> = {};
    for (const [k, v] of Object.entries(g.items)) {
      const price = Array.isArray(v) && v.length === 2 && typeof v[0] === "string" ? whole(v[1]) : undefined;
      if (ID.test(k) && Number(k) <= GAME_INT && price !== undefined) items[k] = [oneLine((v as unknown[])[0], 60), price];
    }
    if (Object.keys(items).length) out.items = items;
  }
  return out;
}

// Following (11/08/2026, Jace approved it with these safeguards): the plugin may send "where", the middle of the 128-tile
// square of the main map the player was in two minutes before ({"x", "y"}; Zanaris as one place), or null to clear it. It's never one of the
// game's fields: never kept with them, never sent to viewers, lookers or desks, never in GET /game. The channel holds it
// in memory only (channel.ts, takeWhere), at most one every WHERE_EVERY_MS (null whenever it comes), and the open
// outstations' list (GET /channels) shows it while the channel's live and listed and it came within WHERE_FRESH_MS
// (index.ts; the Worker's WHERE_FRESH_SECONDS shortens it, for tests). relay.py's game_where, the same.
export const WHERE_SQUARE = 128, WHERE_X = [1024, 4096], WHERE_Y = [2496, 4224];
export const WHERE_EVERY_MS = 50_000, WHERE_FRESH_MS = 240_000;
export type Where = { x: number; y: number; world?: number };
// Which world (11/08/2026, Jace's choice for the globe): with the plugin's "and my world" on as well as following, the
// world the player was on goes in "where", with the square from the same moment, {"x", "y", "world"}. A world is a whole
// number from WORLD_RANGE[0] to WORLD_RANGE[1]: wide enough for the worlds RuneLite reports (301 and up for years, and
// lower if Jagex numbers them from 1), and nothing that isn't a world's number. The plugin never sends it from a PvP,
// Deadman or other dangerous world. Shooting stars' worlds are checked the same.
export const WORLD_RANGE = [1, 999];
// Zanaris (11/08/2026, Jace: "if someone goes to zanaris through a fairy ring on Gielinor make sure it shows their
// location as being on Zanaris"): the plugin sends any tile there (x 2368-2495, y 4352-4479) as this one place, which
// the globe draws on its moon. The only "where" off the main map that's taken; its neighbours aren't.
export const WHERE_ZANARIS: Readonly<Where> = { x: 2368, y: 4416 };

/** A message's "where": {x, y} for a square's middle (each a whole number k*128 + 64, x from 1024 up to 4096, y from
 *  2496 up to 4224, and nothing else in it) or for Zanaris (WHERE_ZANARIS, exactly), null to clear it, or undefined for
 *  anything else (none, or not its shape: ignored, and the rest of the message taken as ever). */
export function gameWhere(g: unknown): Where | null | undefined {
  if (!isObj(g) || !("where" in g)) return undefined;
  const w = g.where;
  if (w === null) return null;
  if (!isObj(w)) return undefined;
  const n = Object.keys(w).length;
  if (!(n === 2 || (n === 3 && "world" in w))) return undefined;
  if (n === 3 && !isWorld(w.world)) return undefined;
  const world = n === 3 ? { world: w.world as number } : {};
  if (w.x === WHERE_ZANARIS.x && w.y === WHERE_ZANARIS.y) return { ...WHERE_ZANARIS, ...world };
  return isSquare(w.x, WHERE_X) && isSquare(w.y, WHERE_Y) ? { x: w.x as number, y: w.y as number, ...world } : undefined;
}

/** A square's middle along one side of the main map: a whole number k*128 + 64 from lo up to hi. */
function isSquare(v: unknown, [lo, hi]: number[]): boolean {
  return typeof v === "number" && Number.isInteger(v) && v >= lo && v < hi && v % WHERE_SQUARE === WHERE_SQUARE / 2;
}

/** A world's number (WORLD_RANGE). */
export function isWorld(v: unknown): v is number {
  return typeof v === "number" && Number.isInteger(v) && v >= WORLD_RANGE[0] && v <= WORLD_RANGE[1];
}

// What they're doing (11/08/2026, Jace's choice for the globe: the light's mood): the plugin may send "activity", one
// coarse label of what the player was doing two minutes before, or null to clear it. Never one of the game's fields: it's
// kept, dropped and shown exactly as "where" is (channel.ts, takeActivity; one every WHERE_EVERY_MS, shown on GET
// /channels while live and listed and within WHERE_FRESH_MS), and it doesn't need following. Only these, exactly:
//   {"kind": "skilling", "skill": <one of ACTIVITY_SKILLS>}   {"kind": "boss", "boss": <one of ACTIVITY_BOSSES>}
//   {"kind": "fighting"}                                       {"kind": "idle"}
// ACTIVITY_BOSSES is the plugin's Activity.BOSSES's labels (relay-py/test_relay.py checks the three lists match):
// none of the Wilderness's, where nothing's sent. relay.py's game_activity, the same.
export const ACTIVITY_SKILLS = ["Agility", "Attack", "Construction", "Cooking", "Crafting", "Defence", "Farming",
  "Firemaking", "Fishing", "Fletching", "Herblore", "Hitpoints", "Hunter", "Magic", "Mining", "Prayer", "Ranged",
  "Runecraft", "Sailing", "Slayer", "Smithing", "Strength", "Thieving", "Woodcutting"];
export const ACTIVITY_BOSSES = ["Abyssal Sire", "Alchemical Hydra", "Amoxliatl", "Araxxor", "Barrows", "Bryophyta",
  "Cerberus", "Commander Zilyana", "Corporeal Beast", "Dagannoth Prime", "Dagannoth Rex", "Dagannoth Supreme",
  "Duke Sucellus", "General Graardor", "Giant Mole", "Grotesque Guardians", "Hespori", "K'ril Tsutsaroth",
  "Kalphite Queen", "King Black Dragon", "Kraken", "Kree'arra", "Moons of Peril", "Nex", "Obor", "Phantom Muspah",
  "Phosani's Nightmare", "Royal Titans", "Sarachnis", "Scurrius", "Skotizo", "Sol Heredit", "The Hueycoatl",
  "The Leviathan", "The Nightmare", "The Whisperer", "Thermonuclear smoke devil", "TzKal-Zuk", "TzTok-Jad", "Vardorvis",
  "Vorkath", "Yama", "Zalcano", "Zulrah"];
export type Activity = { kind: "idle" | "fighting" } | { kind: "skilling"; skill: string } | { kind: "boss"; boss: string };

/** A message's "activity": one of the labels above (a copy), null to clear it, or undefined for anything else (none, or
 *  not its shape: ignored, and the rest of the message taken as ever). */
export function gameActivity(g: unknown): Activity | null | undefined {
  if (!isObj(g) || !("activity" in g)) return undefined;
  const a = g.activity;
  if (a === null) return null;
  if (!isObj(a)) return undefined;
  const n = Object.keys(a).length;
  if ((a.kind === "idle" || a.kind === "fighting") && n === 1) return { kind: a.kind };
  if (a.kind === "skilling" && n === 2 && typeof a.skill === "string" && ACTIVITY_SKILLS.includes(a.skill)) return { kind: "skilling", skill: a.skill };
  if (a.kind === "boss" && n === 2 && typeof a.boss === "string" && ACTIVITY_BOSSES.includes(a.boss)) return { kind: "boss", boss: a.boss };
  return undefined;
}

// Shooting stars (11/08/2026, Jace's choice for the globe; stars.ts keeps them): the plugin may send "star", a crashed
// star the player saw two minutes before: its world (WORLD_RANGE), the middle of the 128-tile square of the main map
// it's in (as "where"'s, Zanaris never), and its tier (1 to 9), or "gone": true once it's mined out. Exactly
// {"world", "x", "y", "tier"} or {"world", "x", "y", "gone": true}. relay.py's game_star, the same.
export type StarReport = { world: number; x: number; y: number; tier: number } | { world: number; x: number; y: number; gone: true };

/** A message's "star", or undefined for none or anything not its shape (ignored, and the rest taken as ever). */
export function gameStar(g: unknown): StarReport | undefined {
  if (!isObj(g) || !isObj(g.star)) return undefined;
  const s = g.star, keys = Object.keys(s).sort().join(",");
  if (!isWorld(s.world) || !isSquare(s.x, WHERE_X) || !isSquare(s.y, WHERE_Y)) return undefined;
  const at = { world: s.world, x: s.x as number, y: s.y as number };
  if (keys === "tier,world,x,y" && typeof s.tier === "number" && Number.isInteger(s.tier) && s.tier >= 1 && s.tier <= 9) return { ...at, tier: s.tier };
  if (keys === "gone,world,x,y" && s.gone === true) return { ...at, gone: true };
  return undefined;
}

const itemLen = (i: string, e: Item) => pyjson(i).length + 2 + pyjson(e).length;

/** A channel's game state: each field as the plugin last sent it, and "items" merged (a name and a price for each item id
 *  it's been told of, GAME_ITEMS ids at most). Its length as JSON is kept as it changes, so a message that would take it
 *  past GAME_KEPT is found without writing the whole of it out each time (a page of item names written out five times
 *  a second would cost more CPU than the free plan's 10 ms a request allows). */
export class GameState {
  kept: Fields = {};
  at = 0; // when it last changed (ms)
  lens: Record<string, number> = {}; // each field's length in the kept state's JSON, as '"field": value'
  itemLens = new Map<string, number>(); // each item's, as '"id": [name, price]'
  itemsBody = 0; // those summed
  line: string | null = null; // the whole of it as someone just come gets it, written once a change

  /** From what was saved (channel.ts, saveGame), or empty. */
  constructor(saved?: { kept: Fields; at: number } | null) {
    if (!saved || !isObj(saved.kept)) return;
    this.kept = saved.kept;
    this.at = saved.at || 0;
    for (const [k, v] of Object.entries(this.kept)) {
      if (k !== "items") { this.lens[k] = pyjson(k).length + 2 + pyjson(v).length; continue; }
      for (const [i, e] of Object.entries(v as Record<string, Item>)) {
        const n = itemLen(i, e);
        this.itemLens.set(i, n);
        this.itemsBody += n;
      }
      this.lens.items = ITEMS + this.itemsBody + 2 * Math.max(0, this.itemLens.size - 1);
    }
  }

  get empty(): boolean { return !Object.keys(this.kept).length; }

  /** The kept state's length as JSON (pyjson's; relay.py's json.dumps writes the same). */
  static size(lens: Record<string, number>): number {
    const n = Object.keys(lens).length;
    return 2 + Object.values(lens).reduce((a, b) => a + b, 0) + 2 * Math.max(0, n - 1);
  }

  /** A message's fields (gameFields) taken in. What goes on to viewers: the fields given (items: those not ignored), or
   *  null when nothing changed or when it would take the kept state past GAME_KEPT (then none of it is kept). */
  apply(fields: Fields, now = Date.now()): Fields | null {
    let changed = false;
    const out: Fields = {}, lens = { ...this.lens };
    for (const [k, v] of Object.entries(fields)) {
      if (k === "items") continue;
      const line = pyjson(v);
      if (this.kept[k] === undefined || line !== pyjson(this.kept[k])) changed = true;
      lens[k] = pyjson(k).length + 2 + line.length;
      out[k] = v;
    }
    const have = this.kept.items || {}, items: Record<string, Item> = {}, newLens = new Map<string, number>();
    let body = this.itemsBody, n = this.itemLens.size;
    for (const [i, e] of Object.entries(fields.items || {})) {
      const had = have[i];
      if (had === undefined) {
        if (n >= GAME_ITEMS) continue; // (the dictionary's full: a new id is ignored)
        n++;
        changed = true;
      } else if (had[0] !== e[0] || had[1] !== e[1]) changed = true;
      const len = itemLen(i, e);
      body += len - (this.itemLens.get(i) || 0);
      newLens.set(i, len);
      items[i] = e;
    }
    if (newLens.size) lens.items = ITEMS + body + 2 * (n - 1);
    if (!changed || GameState.size(lens) > GAME_KEPT) return null;
    Object.assign(this.kept, out);
    if (newLens.size) {
      const kept = (this.kept.items ??= {});
      for (const [i, e] of Object.entries(items)) { kept[i] = e; this.itemLens.set(i, newLens.get(i)!); }
      this.itemsBody = body;
      out.items = items;
    }
    this.lens = lens;
    this.at = now;
    this.line = null;
    return out;
  }

  /** The whole of it, for someone just come (a seat, a desk, a looker): {"game": {...the fields, "at"}}; null while
   *  there's none. */
  wholeLine(): string | null {
    if (this.empty) return null;
    return (this.line ??= pyjson({ game: { ...this.kept, at: this.at } }));
  }
}
