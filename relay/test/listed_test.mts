// The open outstations' list as the Directory keeps it (src/listed.ts): what's stored of a row (never "where" or
// "activity"), what's asked after a sleep, and when the list is written. Node runs it as it is (it strips the types):
//
//   node test/listed_test.mts   (or npm test)
import { deepStrictEqual, ok, strictEqual } from "node:assert";
import { KEPT_FIELDS, LISTING_CHECK_MS, LISTING_DARK_MS, ListedRows, keptRow } from "../src/listed.ts";

let passed = 0;
const tests: [string, () => void][] = [];
const test = (name: string, f: () => void) => tests.push([name, f]);
const MIN = 60_000;

// a row as a live channel with the plugin and following tells it
const row = (channel: string, at: number, extra: Record<string, unknown> = {}) => ({
  channel, set: true, code: false, at, live: true, open: true, static: false, viewers: 2, title: "barrows",
  doing: { boss: "Giant Mole", kc: 12 }, game: true,
  where: { x: 3264, y: 3264, world: 302 }, whereAt: at, activity: { kind: "skilling", skill: "Mining" }, activityAt: at, ...extra });
const NEVER = ["where", "whereAt", "activity", "activityAt"];
const stored = (l: ListedRows) => JSON.parse(JSON.stringify(l.saved()));

test("what's stored of a row: never where or activity, nor their times", () => {
  for (const k of NEVER) ok(!(KEPT_FIELDS as readonly string[]).includes(k), k);
  const r = row("sburb", 1000), kept = keptRow(r) as Record<string, unknown>;
  for (const k of NEVER) ok(!(k in kept), k);
  deepStrictEqual(kept, { channel: "sburb", set: true, code: false, at: 1000, live: true, open: true, static: false, viewers: 2,
                          title: "barrows", doing: { boss: "Giant Mole", kc: 12 }, game: true });
  ok(!("extra" in (keptRow({ ...r, extra: 1 } as never) as Record<string, unknown>))); // (a field not named: not stored)
  const l = new ListedRows();
  l.heard(row("sburb", 1000), 5000);
  const s = stored(l), text = JSON.stringify(s);
  for (const k of NEVER) ok(!text.includes('"' + k + '"'), k);
  deepStrictEqual(s, { rows: [{ row: kept, seen: 5000 }] });
  // (held in memory all the same, for GET /channels)
  deepStrictEqual(l.rows.get("sburb")!.row.where, { x: 3264, y: 3264, world: 302 });
});

test("a saved list comes back without where or activity, even if they'd been written", () => {
  const l = ListedRows.from({ rows: [{ row: row("sburb", 1000), seen: 5000 }, { row: { at: 1 }, seen: 1 }, "junk"] });
  deepStrictEqual([...l.rows.keys()], ["sburb"]);
  for (const k of NEVER) ok(!(k in l.rows.get("sburb")!.row), k);
  deepStrictEqual(ListedRows.from(undefined).rows.size, 0);
  deepStrictEqual(ListedRows.from({ rows: "no" }).rows.size, 0);
});

test("a row stored before settling went comes back without its home, and is written without it", () => {
  ok(!(KEPT_FIELDS as readonly string[]).includes("home"));
  const l = ListedRows.from({ rows: [{ row: row("sburb", 1000, { home: { near: "moss" } }), seen: 5000 },
                                     { row: row("moss", 1000, { home: { lon: 1.5, lat: -2.25 } }), seen: 5000 }] });
  for (const n of ["sburb", "moss"]) ok(!("home" in l.rows.get(n)!.row), n);
  ok(!JSON.stringify(stored(l)).includes('"home"'));
});

test("after a sleep only the missing, the old, and the live with the plugin (once) are asked", () => {
  const now = 100 * MIN, l = new ListedRows();
  l.heard(row("live-game", 1), now - MIN);
  l.heard(row("live-alone", 1, { game: false }), now - MIN);
  l.heard(row("dark", 1, { live: false, open: false, game: true }), now - MIN);
  l.heard(row("live-old", 1, { game: false }), now - LISTING_CHECK_MS - 1);
  l.heard(row("dark-old", 1, { live: false }), now - LISTING_DARK_MS - 1);
  l.heard(row("dark-oldish", 1, { live: false }), now - LISTING_CHECK_MS - 1);
  const names = ["live-game", "live-alone", "dark", "live-old", "dark-old", "dark-oldish", "never"];
  deepStrictEqual(l.due(names, now), ["live-old", "dark-old", "never"]); // (awake: all heard this wake)
  const woke = ListedRows.from(stored(l));
  deepStrictEqual(woke.due(names, now), ["live-game", "live-old", "dark-old", "never"]);
  woke.heard(row("live-game", 2), now);
  deepStrictEqual(woke.due(names, now), ["live-old", "dark-old", "never"]); // (once a wake)
  deepStrictEqual(woke.rows.get("live-game")!.row.where, { x: 3264, y: 3264, world: 302 }); // (back from the ask)
});

test("an older row than the one held is ignored", () => {
  const l = new ListedRows();
  l.heard(row("sburb", 2000, { viewers: 5 }), 1);
  l.heard(row("sburb", 1000, { viewers: 1 }), 2);
  strictEqual(l.rows.get("sburb")!.row.viewers, 5);
});

test("written when what's stored changes, not for a new where alone; and a seen a whole check on", () => {
  const l = new ListedRows();
  l.heard(row("sburb", 1000), 0);
  ok(l.changed());
  l.saved();
  ok(!l.changed());
  l.heard(row("sburb", 2000, { where: { x: 1088, y: 2496 }, whereAt: 2000, activity: null }), 50_000); // (where only)
  ok(!l.changed());
  l.heard(row("sburb", 3000), LISTING_CHECK_MS);
  ok(l.changed()); // (heard a whole check since its seen was written)
  l.saved();
  l.heard(row("sburb", 4000, { viewers: 3 }), LISTING_CHECK_MS + 1);
  ok(l.changed());
  l.saved();
  l.heard(row("sburb", 5000, { live: false, open: false, viewers: 0 }), LISTING_CHECK_MS + 2);
  l.saved();
  l.heard(row("sburb", 6000, { live: false, open: false, viewers: 0 }), 3 * LISTING_CHECK_MS);
  ok(!l.changed()); // (a dark row's check is LISTING_DARK_MS)
  ok(l.drop("sburb") && l.changed() && !l.drop("sburb"));
  l.saved();
  ok(!l.changed());
});

for (const [name, f] of tests) {
  try { f(); passed++; } catch (e) { console.error("FAIL " + name + "\n", e); process.exitCode = 1; }
}
console.log(passed + " of " + tests.length + " passed");
