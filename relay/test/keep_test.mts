// The bank and the model kept for a player's page (src/keep.ts) and the emotes' kinds (src/emotes.ts), 11/14/2026 (the
// game's new charges and delve fields: relay-py/test_relay.py, as relay.py's game_fields mirrors game.ts's). Node runs it as it is (it strips the types):
//
//   node test/keep_test.mts   (or npm test)
import { deepStrictEqual, strictEqual } from "node:assert";
import { keepShape, BANK_SLOTS } from "../src/keep.ts";
import { emoteKind, EMOTE_NAME } from "../src/emotes.ts";

let passed = 0;
const tests: [string, () => void][] = [];
const test = (name: string, f: () => void) => tests.push([name, f]);

test("a bank: its items and tabs as they are, names cleaned, anything else left out", () => {
  const got = keepShape("bank", { items: [[995, 1000], [4151, 1]], tabs: [1, 0, 0], names: { "995": ["Coins\n", 1], x: ["no", 1] }, extra: 1 });
  deepStrictEqual(got, { items: [[995, 1000], [4151, 1]], tabs: [1, 0, 0], names: { "995": ["Coins ", 1] } });
  strictEqual(typeof keepShape("bank", { items: Array(BANK_SLOTS + 1).fill([1, 1]) }), "string");
  strictEqual(typeof keepShape("bank", { items: [[1, -1]] }), "string");
  strictEqual(typeof keepShape("bank", { items: [], tabs: Array(10).fill(0) }), "string");
});

test("a model: vertices in threes, faces on them, a colour a face", () => {
  const m = { v: [0, 0, 0, 10, 0, 0, 0, 10, 0], f: [0, 1, 2], c: [-1] };
  deepStrictEqual(keepShape("model", { ...m, x: 1 }), m);
  strictEqual(typeof keepShape("model", { ...m, f: [0, 1, 3] }), "string");    // (no vertex 3)
  strictEqual(typeof keepShape("model", { ...m, c: [] }), "string");
  strictEqual(typeof keepShape("model", { ...m, v: [0, 0] }), "string");
  strictEqual(typeof keepShape("model", { ...m, v: [0, 0, 9000, 1, 1, 1, 2, 2, 2] }), "string");
  strictEqual(typeof keepShape("nothing", m), "string");
});

test("emotes: PNG, JPEG and GIF by their own first bytes; names", () => {
  strictEqual(emoteKind(new Uint8Array([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a])), "png");
  strictEqual(emoteKind(new Uint8Array([0xff, 0xd8, 0xff, 0xe0])), "jpg");
  strictEqual(emoteKind(new Uint8Array([0x47, 0x49, 0x46, 0x38, 0x39, 0x61])), "gif");
  strictEqual(emoteKind(new Uint8Array([0x3c, 0x73, 0x76, 0x67])), null); // (an SVG: no)
  strictEqual(EMOTE_NAME.test("YEP"), true);
  strictEqual(EMOTE_NAME.test("a"), false);
  strictEqual(EMOTE_NAME.test("no spaces"), false);
});

for (const [name, f] of tests) {
  try { f(); passed++; console.log("ok  ", name); } catch (e) { console.log("FAIL", name); console.log(e); }
}
console.log(`${passed} of ${tests.length} passed`);
if (passed !== tests.length) process.exit(1);
