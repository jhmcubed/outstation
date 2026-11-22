// The game (11/07/2026; docs/protocol.md "The game"): the Outside plugin, a RuneLite plugin of our own, sends the player's
// live game state on a socket of its own, and Outside (the viewer page) draws it beside the transmission and the chat,
// live, and as last known while dark.
//
//   ws(s)://<relay>/game/<channel>?key=<key>&v=<the plugin's version>   {"game": {...fields}}, at most GAME_MESSAGE long,
//                                                                       GAME_A_SECOND a second
//   GET <relay>/game/<channel>?code=<watch code>                         {"game": {...}, "at", "connected"}
// and, apart from the game's fields, "where" (11/08/2026: following, and the world with it; gameWhere, below) and
// "activity" (11/08/2026: what they're doing; gameActivity), only ever on GET /channels; and "star" (11/08/2026: a
// shooting star they found; gameStar), only ever on GET /stars, with the outstation that found it first (stars.ts).
//
// Here: a message's fields checked and cleaned (gameFields), and the state kept from them (GameState): each field
// replaces the one kept, but "items", a name and a price for each item id, which is merged. channel.ts has the socket,
// who hears what changed, and the saving. relay-py/relay.py's game_fields and Game, the same.

import { oneLine, pyjson, wildernessBoss } from "./text";

// a message at most GAME_MESSAGE long, and GAME_A_SECOND a second (more are dropped); the kept state at most GAME_KEPT as
// JSON (a message that would take it past is ignored), its dictionary of item names and prices at most GAME_ITEMS ids
// (past that, new ones are ignored). relay.py's, the same.
export const GAME_MESSAGE = 64 * 1024, GAME_KEPT = 192 * 1024, GAME_ITEMS = 3000, GAME_A_SECOND = 5;
export const CHARGES_MOST = 64;
const GAME_INT = 2 ** 31 - 1; // the most any number in the game holds (a Java int: RuneLite's)
export const SKILL = /^[A-Za-z][A-Za-z _-]{0,23}$/; // a skill's name (the Array's events' too: array.ts)
const ID = /^(0|[1-9][0-9]{0,9})$/; // an item's id as the dictionary has it
const ITEMS = '"items": {}'.length;

type Slot = [number, number] | null;
type Item = [string, number]; // its name, its price on the Grand Exchange
export type Fields = Record<string, unknown> & { items?: Record<string, Item> };

// (11/15/2026) the achievement diaries' areas, as the plugin names them (Progress.AREAS), in this order; a quest list at
// most NAMES_MOST names, each at most 80 characters
export const DIARY_AREAS = ["Ardougne", "Desert", "Falador", "Fremennik", "Kandarin", "Karamja", "Kourend & Kebos",
  "Lumbridge & Draynor", "Morytania", "Varrock", "Western Provinces", "Wilderness"];
const NAMES_MOST = 400;
// (the collection log: its slots at most, and its newly added names with one update at most)
export const CLOG_MOST = 5000, CLOG_NEW_MOST = 8;
// (11/16/2026: kill counts by boss, at most COUNTS_MOST, and clue counts by tier, each at most COUNT_TOP)
export const COUNTS_MOST = 250, COUNT_TOP = 10_000_000;
export const CLUE_TIERS = ["beginner", "easy", "medium", "hard", "elite", "master"];
// (update 7, 11/22/2026: the personal bests RuneLite's Chat Commands plugin keeps, at most PBS_MOST, each under a day)
export const PBS_MOST = 400, PB_TOP = 86_400;
const names = (v: unknown): string[] | undefined =>
  Array.isArray(v) && v.length <= NAMES_MOST && v.every((x) => typeof x === "string") ? v.map((x) => oneLine(x, 80)) : undefined;

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
  // (the seed box and the chugging barrel, 11/14/2026: each its slots, as the game keeps them, empty ones null)
  for (const [k, most] of [["worn", 14], ["inv", 28], ["bag", 28], ["pouch", 8], ["seeds", 6], ["barrel", 5]] as const) {
    if (g[k] === undefined) continue;
    // (the bag, the seed box and the barrel: null, not known yet: the game tells RuneLite once they're looked in)
    const s = g[k] === null && (k === "bag" || k === "seeds" || k === "barrel") ? null : slots(g[k], most);
    if (s !== undefined) out[k] = s;
  }
  if (g.quiver !== undefined) {
    const s = g.quiver === null ? null : pair(g.quiver);
    if (s !== undefined) out.quiver = s;
  }
  // (11/14/2026, Jace: "show charges on the items that have charges like my tonalztics of ralos"): each worn or carried
  // item's charges as the game last told of them, by item id, CHARGES_MOST at most
  if (isObj(g.charges)) {
    const charges: Record<string, number> = {};
    for (const [k, v] of Object.entries(g.charges).slice(0, CHARGES_MOST)) {
      const n = whole(v);
      if (ID.test(k) && Number(k) <= GAME_INT && n !== undefined) charges[k] = n;
    }
    out.charges = charges;
  }
  // (11/14/2026, Jace: "capture the wave data from the Doom of Mokhaiotl board nearby the boss"): the board's delves
  // completed at each level, 1 to 8, then the waves past 8, as the Delve Calculator plugin reads it; null, not read yet
  if (g.delve === null) out.delve = null;
  else if (Array.isArray(g.delve) && g.delve.length === 9 && g.delve.every((x) => whole(x) !== undefined)) out.delve = g.delve as number[];
  // (11/19/2026; SaluteCFlagg, through Jace: "count the levels each death throws away"): the levels a death in a delve
  // lost (its run's drops gone with it), counted by the plugin's Delves.java and sent as they stood when the board was
  // last read, the same nine; null, none counted yet
  if (g.delve_lost === null) out.delve_lost = null;
  else if (Array.isArray(g.delve_lost) && g.delve_lost.length === 9 && g.delve_lost.every((x) => whole(x) !== undefined)) out.delve_lost = g.delve_lost as number[];
  // (11/15/2026, Jace: "add quests, achievement diaries, combat achievements and slayer tasks to the plugin"; the
  // plugin's Progress.java): each whole or null (switched off: taken off the page)
  if (g.quests === null) out.quests = null;
  else if (isObj(g.quests)) {
    const q = g.quests, points = whole(q.points), total = whole(q.total), done = names(q.done), started = names(q.started), todo = names(q.todo);
    if (points !== undefined && total !== undefined && done && started && todo) {
      const quests: Record<string, unknown> = { points, total, done, started, todo };
      // (11/16/2026: grouped as the game's list is: the free quests' names, the miniquests', each quest's points; each
      // left out when it isn't its shape)
      const free = names(q.free), mini = names(q.mini);
      if (free) quests.free = free;
      if (mini) quests.mini = mini;
      if (isObj(q.qp) && Object.keys(q.qp).length <= NAMES_MOST) {
        const qp: Record<string, number> = {};
        for (const [k, v] of Object.entries(q.qp)) { const n = whole(v, 1000); if (n !== undefined) qp[oneLine(k, 80)] = n; }
        quests.qp = qp;
      }
      out.quests = quests;
    }
  }
  if (g.diaries === null) out.diaries = null;
  else if (isObj(g.diaries)) {
    // (the twelve areas by name, in this order; four tiers 0 or 1, then the area's tasks done and in all)
    const d: Record<string, number[]> = {};
    for (const a of DIARY_AREAS) {
      const v = g.diaries[a];
      if (Array.isArray(v) && v.length === 6 && v.every((x, i) => whole(x, i < 4 ? 1 : GAME_INT) !== undefined)) d[a] = v as number[];
    }
    if (Object.keys(d).length) out.diaries = d;
  }
  if (g.combat === null) out.combat = null;
  else if (isObj(g.combat)) {
    const c = g.combat, points = whole(c.points), t = c.tiers;
    if (points !== undefined && Array.isArray(t) && t.length === 6 && t.every((x) => pair(x) !== undefined))
      out.combat = { points, tiers: (t as unknown[]).map((x) => pair(x) as [number, number]) };
  }
  if (g.slayer === null) out.slayer = null;
  else if (isObj(g.slayer)) {
    const s = g.slayer, left = whole(s.left), of = whole(s.of), points = whole(s.points), streak = whole(s.streak);
    const task = s.task === null ? null : typeof s.task === "string" ? oneLine(s.task, 80) : undefined;
    const area = s.area === null ? null : typeof s.area === "string" ? oneLine(s.area, 80) : undefined;
    if (left !== undefined && of !== undefined && points !== undefined && streak !== undefined && task !== undefined && area !== undefined)
      out.slayer = { task, left, of, area, points, streak };
  }
  // (11/16/2026: the collection log's counter, slots filled and in all, and the names of those the game's chat said were
  // added since the last update, CLOG_NEW_MOST at most; null, switched off)
  if (g.clog === null) out.clog = null;
  else if (isObj(g.clog)) {
    const count = whole(g.clog.count, CLOG_MOST), max = whole(g.clog.max, CLOG_MOST);
    const fresh = Array.isArray(g.clog.new) ? g.clog.new.filter((x): x is string => typeof x === "string").slice(0, CLOG_NEW_MOST).map((x) => oneLine(x, 60)).filter(Boolean) : [];
    if (count !== undefined && max !== undefined && count <= max) out.clog = { count, max, new: fresh };
  }
  // (11/16/2026: kill counts and clue counts as the game's chat last said each, {"kills": {boss: n}, "clues": {tier: n}};
  // never a Wilderness boss's; null, switched off)
  if (g.counts === null) out.counts = null;
  else if (isObj(g.counts) && isObj(g.counts.kills) && isObj(g.counts.clues)) {
    const kills: Record<string, number> = {}, clues: Record<string, number> = {};
    for (const [k, v] of Object.entries(g.counts.kills).slice(0, COUNTS_MOST)) {
      const boss = oneLine(k, 60), n = whole(v, COUNT_TOP);
      if (boss && n !== undefined && !wildernessBoss(boss)) kills[boss] = n;
    }
    for (const [k, v] of Object.entries(g.counts.clues)) {
      const n = whole(v, COUNT_TOP);
      if (CLUE_TIERS.includes(k) && n !== undefined) clues[k] = n;
    }
    out.counts = { kills, clues };
  }
  // (update 7, 11/22/2026: the personal bests RuneLite keeps, {its key: seconds}: the boss's name lower-cased, a raid's by
  // team size ("chambers of xeric 3 players"); each a number of seconds over 0 and under a day, to the hundredth; never a
  // Wilderness boss's; sent only when they've changed, so kept till the next; null, switched off)
  if (g.pbs === null) out.pbs = null;
  else if (isObj(g.pbs)) {
    const pbs: Record<string, number> = {};
    for (const [k, v] of Object.entries(g.pbs).slice(0, PBS_MOST)) {
      const name = oneLine(k, 80).toLowerCase();
      if (name && typeof v === "number" && Number.isFinite(v) && v > 0 && v < PB_TOP && !wildernessBoss(name)) pbs[name] = Math.round(v * 100) / 100;
    }
    out.pbs = pbs;
  }
  // (11/15/2026, the live view: the animation they're doing now, its id or -1, with the gear it's in; null, none)
  if (g.anim === null) out.anim = null;
  else if (Array.isArray(g.anim) && g.anim.length === 2 && Number.isInteger(g.anim[0]) && (g.anim[0] as number) >= -1 && (g.anim[0] as number) < 1_000_000
           && typeof g.anim[1] === "string" && /^[0-9a-f]{1,16}$/.test(g.anim[1])) out.anim = [g.anim[0], g.anim[1]];
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
// game's fields: never kept with them, never sent to viewers or lookers, never in GET /game; the channel's own desks get it only
// as {"mine"} (your year, 11/10/2026; channel.ts, tellMine). The channel holds it
// in memory only (channel.ts, takeWhere), at most one every WHERE_EVERY_MS (null whenever it comes), and the open
// outstations' list (GET /channels) shows it while the channel's live and listed and it came within WHERE_FRESH_MS
// (index.ts; the Worker's WHERE_FRESH_SECONDS shortens it, for tests). relay.py's game_where, the same.
// The main map's bounds take in Sailing's seas (11/10/2026: the band of sea and islands round the old map, x 960-1023 and
// 3968-4031, y 2048-2495 and 4160-4223, from the game's own map squares; the plugin's Follow.X0 and Y0).
export const WHERE_SQUARE = 128, WHERE_X = [960, 4096], WHERE_Y = [2048, 4224];
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

/** A message's "where": {x, y} for a square's middle (each a whole number k*128 + 64, x from 960 up to 4096, y from
 *  2048 up to 4224, and nothing else in it) or for Zanaris (WHERE_ZANARIS, exactly), null to clear it, or undefined for
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

// The exact path (11/10/2026, Jace: "your year" records the exact tile, privately; docs/record.md, "The exact path"):
// with the plugin's "Record my exact path for my year" on, "here" is the tile the player's on, {"x", "y", "plane",
// "world"}, as following places it (on the main map or its seas, underground as the tile above, an instance at its
// entrance, Zanaris as WHERE_ZANARIS), or null to clear it. It's never one of the game's fields: the channel passes it
// at once to its own desks only, as {"mine": {"here", "at"}} (channel.ts, takeHere), at most one every HERE_EVERY_MS,
// and never keeps it, not even in memory: never to viewers, lookers, GET /game, GET /channels, the Array or the aether.
// relay.py's game_here, the same.
export const HERE_EVERY_MS = 2_000, HERE_PLANES = [0, 3];
export type Here = { x: number; y: number; plane: number; world: number };

/** A message's "here": exactly {x, y, plane, world}, x a whole number from WHERE_X[0] up to WHERE_X[1] and y the same
 *  with WHERE_Y (a tile, not a square's middle), or exactly Zanaris's (WHERE_ZANARIS), plane 0 to 3 and world as
 *  WORLD_RANGE; null to clear it; undefined for anything else (none, or not its shape: ignored, and the rest of the
 *  message taken as ever). */
export function gameHere(g: unknown): Here | null | undefined {
  if (!isObj(g) || !("here" in g)) return undefined;
  const h = g.here;
  if (h === null) return null;
  if (!isObj(h) || Object.keys(h).sort().join(",") !== "plane,world,x,y") return undefined;
  const ok = (v: unknown, [lo, hi]: number[]) => typeof v === "number" && Number.isInteger(v) && v >= lo && v < hi;
  const zanaris = h.x === WHERE_ZANARIS.x && h.y === WHERE_ZANARIS.y;
  if (!zanaris && !(ok(h.x, WHERE_X) && ok(h.y, WHERE_Y))) return undefined;
  if (!ok(h.plane, [HERE_PLANES[0], HERE_PLANES[1] + 1]) || !isWorld(h.world)) return undefined;
  return { x: h.x as number, y: h.y as number, plane: h.plane as number, world: h.world as number };
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
// ACTIVITY_BOSSES is the plugin's Activity.bossLabels() (relay-py/test_relay.py checks the three lists match): every boss
// on the official hiscores (RuneLite's HiscoreSkill, 11/10/2026) but the raids and Tempoross, Wintertodt and Zalcano
// (minigames); the Wilderness's too, which the plugin sends only with "In the Wilderness too" on; the Gauntlets by
// where they are. relay.py's game_activity, the same.
export const ACTIVITY_SKILLS = ["Agility", "Attack", "Construction", "Cooking", "Crafting", "Defence", "Farming",
  "Firemaking", "Fishing", "Fletching", "Herblore", "Hitpoints", "Hunter", "Magic", "Mining", "Prayer", "Ranged",
  "Runecraft", "Sailing", "Slayer", "Smithing", "Strength", "Thieving", "Woodcutting"];
export const ACTIVITY_BOSSES = ["Abyssal Sire", "Alchemical Hydra", "Amoxliatl", "Araxxor", "Artio", "Barrows",
  "Brutus", "Bryophyta", "Callisto", "Calvar'ion", "Cerberus", "Chaos Elemental", "Chaos Fanatic",
  "Commander Zilyana", "Corporeal Beast", "Crazy archaeologist", "Dagannoth Prime", "Dagannoth Rex",
  "Dagannoth Supreme", "Deranged archaeologist", "Doom of Mokhaiotl", "Duke Sucellus", "General Graardor",
  "Giant Mole", "Grotesque Guardians", "Hespori", "K'ril Tsutsaroth", "Kalphite Queen", "King Black Dragon", "Kraken",
  "Kree'arra", "Mad Angel", "Maggot King", "Moons of Peril", "Nex", "Obor", "Phantom Muspah", "Phosani's Nightmare",
  "Royal Titans", "Sarachnis", "Scorpia", "Scurrius", "Shellbane gryphon", "Skotizo", "Sol Heredit", "Spindel",
  "The Hueycoatl", "The Leviathan", "The Mimic", "The Nightmare", "The Whisperer", "Thermonuclear smoke devil",
  "TzKal-Zuk", "TzTok-Jad", "Vardorvis", "Venenatis", "Vet'ion", "Vorkath", "Yama", "Zulrah",
  "the Corrupted Gauntlet", "the Gauntlet"];
// Raids and minigames (11/09/2026, Jace: "include all activities that utilize instances"): the plugin tells them by the
// region the player's in, an instance's as its template lies (its Activity.RAIDS and MINIGAMES, from RuneLite's Discord
// plugin's region ids; Barracuda Trials by the game's own in-trial flag, its Activity.TRIALS, 11/10/2026), a boss fight
// first. The PvP minigames are minigames like the rest (11/10/2026).
//   {"kind": "raid", "raid": <one of ACTIVITY_RAIDS>}          {"kind": "minigame", "minigame": <one of ACTIVITY_MINIGAMES>}
export const ACTIVITY_RAIDS = ["Chambers of Xeric", "The Fractured Archive", "Theatre of Blood", "Tombs of Amascut"];
export const ACTIVITY_MINIGAMES = ["Barbarian Assault", "Barracuda Trials", "Blast Furnace", "Brimhaven Agility Arena",
  "Castle Wars", "Clan Wars", "Emir's Arena", "Fishing Trawler", "Giants' Foundry", "Guardians of the Rift", "Hallowed Sepulchre",
  "Last Man Standing", "Mage Training Arena", "Nightmare Zone", "Pest Control", "Pyramid Plunder", "Rogues' Den",
  "Sorceress's Garden", "Soul Wars", "Temple Trekking", "Tempoross", "Tithe Farm", "Trouble Brewing", "Volcanic Mine",
  "Wintertodt", "Zalcano", "the Colosseum", "the Fight Caves", "the Fight Pits", "the Inferno"];
export type Activity = { kind: "idle" | "fighting" } | { kind: "skilling"; skill: string } | { kind: "boss"; boss: string }
  | { kind: "raid"; raid: string } | { kind: "minigame"; minigame: string };

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
  if (a.kind === "raid" && n === 2 && typeof a.raid === "string" && ACTIVITY_RAIDS.includes(a.raid)) return { kind: "raid", raid: a.raid };
  if (a.kind === "minigame" && n === 2 && typeof a.minigame === "string" && ACTIVITY_MINIGAMES.includes(a.minigame)) return { kind: "minigame", minigame: a.minigame };
  return undefined;
}

// The snapshot, and what they're doing as Discord shows it (11/16/2026, the Plugin Hub's answer: "An 'Update my page'
// action and an hourly sync should be okay, as should matching Discord activity"; docs/protocol.md, "The snapshot").
// The Hub's plugin keeps no socket: it POSTs {"game": {...}} to /game/<channel>?key= when the player presses "Update my
// page", hourly while logged in, as its "presence" changes, and as they log out. A POST is taken as a socket's message
// is, but never its "where", "activity", "here" or "star" (only the whole plugin's socket sends those), at most
// GAME_POSTS_A_MINUTE a minute a channel. One that carries any of SNAP_FIELDS is a snapshot: the channel keeps when
// (GameState.snap), for the page's "updated 14 minutes ago".
export const GAME_POSTS_A_MINUTE = 6;
export const SNAP_FIELDS = ["worn", "inv", "skills"];
// What only the whole plugin's socket sends (11/16/2026, Jace: his outstation's orbs and world stayed on his page under
// the Hub's plugin, which never sends them): a snapshot clears each still kept, as null, so the page drops it. The delve
// board's numbers stay (his alone, from the whole plugin, till the Hub's plugin sends them for everyone).
export const SNAP_CLEARS = ["world", "vitals", "prayers"];
// "presence": what RuneLite's own Discord plugin shows as rich presence, never more: the skill being trained (one of
// ACTIVITY_SKILLS) and the area by Discord's own name (DiscordGameEventType's: a boss's, a city's, a dungeon's, a
// minigame's, a raid's or a region's), each or both null ("in game"), and with the area, where the globe stands it:
// {"x", "y"}, one fixed point of the area (the plugin's Presence.java: the region of it nearest its regions' middle, or
// its entrance), each a whole number that's a multiple of 32 within the main map's bounds (WHERE_X, WHERE_Y), or
// Zanaris's (WHERE_ZANARIS). null clears it. Kept with when it last came (also through a sleep) and shown on GET
// /channels and GET /game while the plugin's heard from (PRESENCE_FRESH_MS after its last POST, till it logs out) or
// its socket's connected. relay.py's game_presence, the same.
export const PRESENCE_KINDS = ["boss", "city", "dungeon", "minigame", "raid", "region"];
export const PRESENCE_FRESH_MS = 70 * 60_000, PRESENCE_GRID = 32;
export const AREA_NAME = /^[A-Za-z0-9][A-Za-z0-9 '.()&-]{0,47}$/;
export type Presence = { skill: string | null; area: string | null; kind: string | null; x?: number; y?: number };

/** A message's "presence": exactly {skill, area, kind} (and x, y with an area), as above; null to clear it; undefined
 *  for anything else (none, or not its shape: ignored, and the rest of the message taken as ever). */
export function gamePresence(g: unknown): Presence | null | undefined {
  if (!isObj(g) || !("presence" in g)) return undefined;
  const p = g.presence;
  if (p === null) return null;
  if (!isObj(p)) return undefined;
  const keys = Object.keys(p).sort().join(",");
  const at = keys === "area,kind,skill,x,y";
  if (!at && keys !== "area,kind,skill") return undefined;
  if (p.skill !== null && !(typeof p.skill === "string" && ACTIVITY_SKILLS.includes(p.skill))) return undefined;
  if (p.area === null) {
    if (p.kind !== null || at) return undefined;
    return { skill: p.skill as string | null, area: null, kind: null };
  }
  if (typeof p.area !== "string" || !AREA_NAME.test(p.area)) return undefined;
  if (typeof p.kind !== "string" || !PRESENCE_KINDS.includes(p.kind)) return undefined;
  const out: Presence = { skill: p.skill as string | null, area: p.area, kind: p.kind };
  if (!at) return out;
  const zanaris = p.x === WHERE_ZANARIS.x && p.y === WHERE_ZANARIS.y;
  const ok = (v: unknown, [lo, hi]: number[]) => typeof v === "number" && Number.isInteger(v) && v >= lo && v < hi && v % PRESENCE_GRID === 0;
  if (!zanaris && !(ok(p.x, WHERE_X) && ok(p.y, WHERE_Y))) return undefined;
  return { ...out, x: p.x as number, y: p.y as number };
}

// Shooting stars (11/08/2026, Jace's choice for the globe; stars.ts keeps them): the plugin may send "star", a crashed
// star the player saw two minutes before: its world (WORLD_RANGE), the middle of the 128-tile square of the main map
// it's in (as "where"'s, Zanaris never), and its tier (1 to 9), or "gone": true once it's mined out; and since
// 11/09/2026 "site", its landing site's name when it's one of STAR_SITES. Exactly {"world", "x", "y", "tier"} or
// {"world", "x", "y", "gone": true}, each with "site" or without. relay.py's game_star, the same.
// The landing sites (11/09/2026, Jace: "named landing sites: yes"): Shooting Star Info's (its Location.java; BSD 2-Clause,
// Copyright (c) 2022, Cute Rock: plugin/README.md, "Credits"), less the Wilderness's and Prifddinas's, each name with
// the square it's in: a "site" must be one of these, and its square the report's. The plugin's Sites.java, the same
// (relay-py/test_relay.py checks the three match).
export const STAR_SITES: Record<string, [number, number]> = {
  "Rimmington mine": [3008, 3264],
  "Crafting guild": [2880, 3264],
  "West Falador mine": [2880, 3392],
  "East Falador bank": [3008, 3392],
  "North Dwarven Mine entrance": [3008, 3392],
  "Taverley house portal": [2880, 3520],
  "Brimhaven northwest gold mine": [2752, 3264],
  "Brimhaven south dungeon entrance": [2752, 3136],
  "Nature Altar mine north of Shilo": [2880, 3008],
  "Shilo Village gem mine": [2880, 3008],
  "North Crandor": [2880, 3264],
  "South Crandor": [2880, 3264],
  "Al Kharid mine": [3264, 3264],
  "Al Kharid bank": [3264, 3136],
  "Mage Training Arena entrance": [3392, 3264],
  "Nw of Uzer (Eagle's Eyrie)": [3392, 3136],
  "Nardah bank": [3392, 2880],
  "Agility Pyramid mine": [3264, 2880],
  "Desert Quarry mine": [3136, 2880],
  "Corsair Cove bank": [2624, 2880],
  "Corsair Resource Area": [2496, 2880],
  "Myths' Guild": [2496, 2880],
  "Feldip Hills (aks fairy ring)": [2624, 3008],
  "Rantz cave": [2624, 3008],
  "Soul Wars south mine": [2240, 2752],
  "Fossil Island Volcanic Mine entrance": [3776, 3776],
  "Fossil Island rune rocks": [3776, 3776],
  "Mos Le'Harmless west bank": [3648, 3008],
  "Keldagrim entrance mine": [2752, 3648],
  "Rellekka mine": [2624, 3648],
  "Jatizso mine entrance": [2368, 3776],
  "Neitiznot south of rune rock": [2368, 3776],
  "Miscellania mine (cip fairy ring)": [2496, 3904],
  "Lunar Isle mine entrance": [2112, 3904],
  "Yanille bank": [2624, 3136],
  "Port Khazard mine": [2624, 3136],
  "Ardougne Monastery": [2624, 3264],
  "South of Legends' Guild": [2752, 3392],
  "Catherby bank": [2752, 3392],
  "Coal Trucks west of Seers'": [2624, 3520],
  "Hosidius mine": [1728, 3520],
  "Port Piscarilius mine in Kourend": [1728, 3648],
  "Shayzien mine south of Kourend Castle": [1600, 3648],
  "South Lovakengj bank": [1472, 3776],
  "Lovakite mine": [1472, 3904],
  "Arceuus dense essence mine": [1728, 3904],
  "Mount Karuulm bank": [1344, 3776],
  "Mount Karuulm mine": [1216, 3776],
  "Kebos Swamp mine": [1216, 3648],
  "Chambers of Xeric bank": [1216, 3520],
  "Varrock east bank": [3264, 3392],
  "South-east Varrock mine": [3264, 3392],
  "Champions' Guild mine": [3136, 3392],
  "Draynor Village": [3136, 3264],
  "West Lumbridge Swamp mine": [3136, 3136],
  "East Lumbridge Swamp mine": [3264, 3136],
  "Darkmeyer ess. mine entrance": [3648, 3392],
  "Theatre of Blood bank": [3648, 3264],
  "Canifis bank": [3520, 3520],
  "Burgh de Rott bank": [3520, 3264],
  "Abandoned Mine west of Burgh": [3392, 3264],
  "West of Grand Tree": [2496, 3520],
  "Gnome Stronghold spirit tree": [2496, 3392],
  "Piscatoris (akq fairy ring)": [2368, 3648],
  "Lletya": [2368, 3136],
  "Isafdar runite rocks": [2240, 3136],
  "Arandar mine north of Lleyta": [2368, 3264],
  "Mynydd nw of Prifddinas": [2112, 3392],
  "Varlamore South East mine": [1728, 3008],
  "Varlamore colosseum entrance bank": [1728, 3136],
  "Mine north-west of hunter guild": [1472, 3136],
};
// The finder's credit (11/09/2026, Jace: opt-in, as Star Miners'): "credit": true, with either, when the player's asked
// to be named as the star's finder (the plugin's "Name me as a star's finder"); without it, stars.ts names nobody for
// that report, though it still counts it. Anything but true is not its shape.
export type StarReport = ({ world: number; x: number; y: number; tier: number } | { world: number; x: number; y: number; gone: true })
  & { site?: string; credit?: true };

/** A message's "star", or undefined for none or anything not its shape (ignored, and the rest taken as ever). */
export function gameStar(g: unknown): StarReport | undefined {
  if (!isObj(g) || !isObj(g.star)) return undefined;
  const s = g.star, keys = Object.keys(s).filter((k) => k !== "site" && k !== "credit").sort().join(",");
  if ("credit" in s && s.credit !== true) return undefined;
  if (!isWorld(s.world) || !isSquare(s.x, WHERE_X) || !isSquare(s.y, WHERE_Y)) return undefined;
  const at = { world: s.world, x: s.x as number, y: s.y as number };
  if ("site" in s) {
    const sq = typeof s.site === "string" && Object.hasOwn(STAR_SITES, s.site) ? STAR_SITES[s.site] : undefined;
    if (!sq || sq[0] !== at.x || sq[1] !== at.y) return undefined;
  }
  const site = { ...("site" in s ? { site: s.site as string } : {}), ...(s.credit === true ? { credit: true as const } : {}) };
  if (keys === "tier,world,x,y" && typeof s.tier === "number" && Number.isInteger(s.tier) && s.tier >= 1 && s.tier <= 9) return { ...at, ...site, tier: s.tier };
  if (keys === "gone,world,x,y" && s.gone === true) return { ...at, ...site, gone: true };
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
  snap = 0; // when the last snapshot was posted (ms; 11/16/2026, the Hub's plugin), or 0
  lens: Record<string, number> = {}; // each field's length in the kept state's JSON, as '"field": value'
  itemLens = new Map<string, number>(); // each item's, as '"id": [name, price]'
  itemsBody = 0; // those summed
  line: string | null = null; // the whole of it as someone just come gets it, written once a change

  /** From what was saved (channel.ts, saveGame), or empty. */
  constructor(saved?: { kept: Fields; at: number; snap?: number } | null) {
    if (!saved || !isObj(saved.kept)) return;
    this.kept = saved.kept;
    this.at = saved.at || 0;
    this.snap = saved.snap || 0;
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
    return (this.line ??= pyjson({ game: { ...this.kept, at: this.at, ...(this.snap ? { snap: this.snap } : {}) } }));
  }
}
