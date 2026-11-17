// What a snapshot adds to the Array, Dink's filter, and the feed's dedupe (src/array.ts, src/text.ts; 11/16/2026),
// moved from the Python relay's own tests when it was retired (11/17/2026). The protocol suite tests them end to end.
//
//   node --import ./test/resolve.mjs test/array_test.mts   (or npm test)
import { deepStrictEqual, ok, strictEqual } from "node:assert";
import { ArrayFeed, SNAPSHOT_EVENTS_MOST, snapshotEvents } from "../src/array.ts";
import { dinkSafe, wildernessBoss, wildernessRegion } from "../src/text.ts";

let passed = 0;
const tests: [string, () => void][] = [];
const test = (name: string, f: () => void) => tests.push([name, f]);
const U = { from: "update" };

test("a first snapshot, or a part the last lacked, says nothing", () => {
  deepStrictEqual(snapshotEvents({}, { skills: { Attack: [99, 99, 1] }, quests: { done: ["X"] } }), []);
  deepStrictEqual(snapshotEvents({ skills: { Attack: [98, 98, 1] } }, { skills: { Attack: [99, 99, 2] }, quests: { done: ["X"] } }),
                  [{ kind: "level", skill: "Attack", level: 99, ...U }]);
});

test("a level only going up, and never a bad name", () => {
  deepStrictEqual(snapshotEvents({ skills: { Attack: [60, 60, 1], Mining: [70, 70, 1] } },
                                 { skills: { Attack: [59, 59, 1], Mining: [70, 70, 1], Sailing: [5, 5, 1], "<b>x</b>": [9, 9, 1] } }), []);
});

test("diary and combat tiers as they turn", () => {
  const p = { diaries: { Varrock: [1, 0, 0, 0, 1, 2] }, combat: { tiers: [[2, 3], [4, 4], [0, 1], [0, 1], [0, 1], [0, 1]] } };
  const n = { diaries: { Varrock: [1, 1, 1, 0, 2, 2] }, combat: { tiers: [[3, 3], [4, 4], [0, 1], [0, 1], [0, 1], [0, 1]] } };
  deepStrictEqual(snapshotEvents(p, n), [{ kind: "diary", tier: "medium", area: "Varrock", ...U },
                                         { kind: "diary", tier: "hard", area: "Varrock", ...U }, { kind: "ca", tier: "easy", ...U }]);
});

test("at most a few from one snapshot", () => {
  const done = Array.from({ length: 30 }, (_, i) => "Quest " + i);
  strictEqual(snapshotEvents({ quests: { done: [] } }, { quests: { done } }).length, SNAPSHOT_EVENTS_MOST);
});

test("a kill count or a clue count gone up", () => {
  const p = { counts: { kills: { Vorkath: 417, Zulrah: 10 }, clues: { hard: 5 } } };
  const n = { counts: { kills: { Vorkath: 417, Zulrah: 12, Kraken: 1 }, clues: { hard: 6, elite: 1 } } };
  deepStrictEqual(snapshotEvents(p, n), [{ kind: "kill", boss: "Zulrah", kc: 12, ...U }, { kind: "clue", tier: "hard", count: 6, ...U }]);
});

test("the collection log's count: a slot each, named where the chat named it", () => {
  deepStrictEqual(snapshotEvents({ clog: { count: 100, max: 1700, new: [] } }, { clog: { count: 102, max: 1700, new: ["Bandos chaps"] } }),
                  [{ kind: "clog", name: "Bandos chaps", ...U }, { kind: "clog", count: 102, ...U }]);
});

test("never a Wilderness boss, region or player", () => {
  ok(wildernessBoss("Callisto") && wildernessBoss("vet'ion") && !wildernessBoss("Vorkath"));
  ok(wildernessRegion((48 << 8) | 60) && !wildernessRegion(12850));
  ok(dinkSafe({ type: "KILL_COUNT", extra: { boss: "Vorkath", count: 1 } }));
  ok(!dinkSafe({ type: "KILL_COUNT", extra: { boss: "Callisto", count: 1 } }));
  ok(!dinkSafe({ type: "PET", regionId: (48 << 8) | 60, extra: {} }));
  ok(!dinkSafe({ type: "PLAYER_KILL", extra: {} }));
  ok(!dinkSafe({ type: "DEATH", extra: { isPvp: true } }));
  ok(!dinkSafe({ type: "LOOT", extra: { category: "PLAYER" } }));
});

test("an update's event the feed has already (from Dink) isn't added again, another channel's is", () => {
  const f = new ArrayFeed(0);
  f.add("sburb", [{ kind: "kill", boss: "Vorkath", kc: 418 }], 1000);
  const got = f.add("sburb", [{ kind: "kill", boss: "Vorkath", kc: 418, ...U }, { kind: "kill", boss: "Zulrah", kc: 11, ...U }], 90_000);
  deepStrictEqual(got.map((e) => e.boss), ["Zulrah"]);
  strictEqual(f.add("moss", [{ kind: "kill", boss: "Vorkath", kc: 418, ...U }], 90_000).length, 1);
});

for (const [name, f] of tests) {
  try { f(); passed++; } catch (e) { console.error("FAIL " + name + "\n", e); process.exitCode = 1; }
}
console.log(passed + " of " + tests.length + " passed");
