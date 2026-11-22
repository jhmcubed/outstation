// The game's fields as the relay keeps them (src/game.ts), 11/22/2026 (update 7: the personal bests). Node runs it as
// it is (it strips the types):
//
//   node --import ./test/resolve.mjs test/game_test.mts   (or npm test)
import { deepStrictEqual, strictEqual } from "node:assert";
import { gameFields, PBS_MOST } from "../src/game.ts";

let passed = 0;
const tests: [string, () => void][] = [];
const test = (name: string, f: () => void) => tests.push([name, f]);

test("the personal bests: each a time under a day, to the hundredth, its key lower-cased, never a Wilderness boss's", () => {
  const got = gameFields({ pbs: { "vorkath": 63.6, "Chambers of Xeric 3 players": 1534.2049, "theatre of blood solo": 1789,
    "callisto": 50, "zulrah": -1, "tempoross": 90_000, "nan": "1:03", "x\nline": 12 } });
  deepStrictEqual(got.pbs, { "vorkath": 63.6, "chambers of xeric 3 players": 1534.2, "theatre of blood solo": 1789, "x line": 12 });
});

test("the personal bests: at most PBS_MOST; null takes them away; anything else isn't them", () => {
  const many: Record<string, number> = {};
  for (let i = 0; i < PBS_MOST + 30; i++) many["boss " + i] = 60 + i;
  strictEqual(Object.keys(gameFields({ pbs: many }).pbs as object).length, PBS_MOST);
  strictEqual(gameFields({ pbs: null }).pbs, null);
  strictEqual("pbs" in gameFields({ pbs: [1, 2] }), false);
  strictEqual("pbs" in gameFields({}), false);
});

for (const [name, f] of tests) {
  f();
  passed++;
  console.log("ok   " + name);
}
console.log(passed + " of " + tests.length + " passed");
