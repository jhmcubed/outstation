// The bank and the model kept for a player's page (src/keep.ts) and the emotes' kinds (src/emotes.ts), 11/14/2026 (the
// game's new charges and delve fields: relay-py/test_relay.py, as relay.py's game_fields mirrors game.ts's). Node runs it as it is (it strips the types):
//
//   node test/keep_test.mts   (or npm test)
import { deepStrictEqual, strictEqual } from "node:assert";
import { keepShape, BANK_SLOTS, DEATHBANK_SLOTS, GOALS_MORE, MOTION_NAME } from "../src/keep.ts";
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

test("a deathbank: where, locked, its items and names as they are, anything else left out", () => {
  const got = keepShape("deathbank", { where: "Hespori", locked: true, items: [[4151, 1], [995, 25000]], names: { "4151": ["Abyssal whip\t", 1500000] }, extra: 1 });
  deepStrictEqual(got, { where: "Hespori", locked: true, items: [[4151, 1], [995, 25000]], names: { "4151": ["Abyssal whip ", 1500000] } });
  strictEqual(typeof keepShape("deathbank", { where: "Hespori", locked: true, items: [] }), "string");                       // (an empty one is taken away instead)
  strictEqual(typeof keepShape("deathbank", { where: "Hespori", locked: true, items: Array(DEATHBANK_SLOTS + 1).fill([1, 1]) }), "string");
  strictEqual(typeof keepShape("deathbank", { where: "Hespori", locked: true, items: [[1, 0]] }), "string");                // (no placeholders in a deathbank)
  strictEqual(typeof keepShape("deathbank", { where: "<b>Hespori</b>", locked: true, items: [[1, 1]] }), "string");
  strictEqual(typeof keepShape("deathbank", { where: "Hespori", locked: "yes", items: [[1, 1]] }), "string");
  deepStrictEqual(keepShape("deathbank", { where: "Hespori", locked: true, items: [[1, 1]], from: "death" }), { where: "Hespori", locked: true, items: [[1, 1]], names: {}, from: "death" });
  strictEqual(typeof keepShape("deathbank", { where: "Hespori", locked: true, items: [[1, 1]], from: "window" }), "string");
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

test("a model as the game lit it, with its textures (11/15/2026)", () => {
  const m = { v: [0, 0, 0, 10, 0, 0, 0, 10, 0], f: [0, 1, 2], c: [-1], g: [50, 60, 70], a: [128], p: [2], t: [7], uv: [0, 0, 4096, 0, 0, 4096], tex: { "7": "iVBORw0KGgo=" }, b: 0.8 };
  deepStrictEqual(keepShape("model", m), m);
  strictEqual(typeof keepShape("model", { ...m, g: [1, 2] }), "string");
  strictEqual(typeof keepShape("model", { ...m, uv: [0] }), "string");
  strictEqual(typeof keepShape("model", { ...m, tex: { "7": "<script>" } }), "string");
  strictEqual(typeof keepShape("model", { ...m, b: 9 }), "string");
});

test("a model with its pet and their motions (11/15/2026)", () => {
  const frame = Buffer.alloc(18).toString("base64");   // (three vertices, x y z each, 16 bits)
  const pet = { v: [0, 0, 0, 5, 0, 0, 0, 5, 0], f: [0, 1, 2], c: [100], frames: { fps: 12.5, v: [frame] } };
  const m = { v: [0, 0, 0, 10, 0, 0, 0, 10, 0], f: [0, 1, 2], c: [100], frames: { fps: 12.5, v: [frame, frame] }, pet };
  deepStrictEqual(keepShape("model", m), m);
  strictEqual(typeof keepShape("model", { ...m, frames: { fps: 12.5, v: ["AAAA"] } }), "string");   // (not every vertex)
  strictEqual(typeof keepShape("model", { ...m, frames: { fps: 0, v: [frame] } }), "string");
  strictEqual(typeof keepShape("model", { ...m, pet: { ...pet, pet } }), "object");   // (a pet's own pet left out)
  deepStrictEqual((keepShape("model", { ...m, pet: { ...pet, pet } }) as Record<string, Record<string, unknown>>).pet.pet, undefined);
  strictEqual(typeof keepShape("model", { ...m, pet: { v: [1] } }), "string");
});

test("a model's effects and the ground, still and in a motion (11/15/2026)", () => {
  const frame = Buffer.alloc(18).toString("base64"), fx2 = Buffer.alloc(12).toString("base64");
  const tri = { v: [0, 0, 0, 10, 0, 0, 0, 10, 0], f: [0, 1, 2], c: [100], g: [100, 100, 100] };
  const effect = { v: [0, -50, 0, 9, -50, 0], f: [0, 1, 1], c: [200] };
  const m = { ...tri, fx: [effect], ground: tri, frames: { fps: 12.5, v: [frame, frame], fx: { m: { "123.2.1": effect }, f: [[{ k: "123.2.1", v: fx2 }], []] } } };
  deepStrictEqual(keepShape("model", m), m);
  strictEqual(typeof keepShape("model", { ...m, fx: Array(5).fill(effect) }), "string");   // (four at most)
  strictEqual(typeof keepShape("model", { ...m, ground: { ...tri, frames: { fps: 12.5, v: [frame] } } }), "string");   // (the ground doesn't move)
  strictEqual(typeof keepShape("model", { ...m, frames: { ...m.frames, fx: { m: m.frames.fx.m, f: [[]] } } }), "string");   // (a row a frame)
  strictEqual(typeof keepShape("model", { ...m, frames: { ...m.frames, fx: { m: m.frames.fx.m, f: [[{ k: "9.9.9", v: fx2 }], []] } } }), "string");   // (no such effect)
  strictEqual(typeof keepShape("model", { ...m, frames: { ...m.frames, fx: { m: m.frames.fx.m, f: [[{ k: "123.2.1", v: frame }], []] } } }), "string");   // (not its vertices)
  strictEqual(typeof keepShape("model", { ...m, frames: { ...m.frames, fx: { m: { "<b>": effect }, f: [[], []] } } }), "string");
  deepStrictEqual((keepShape("model", { ...m, pet: { ...tri, fx: [effect], ground: tri } }) as Record<string, Record<string, unknown>>).pet, tri);   // (only the player's)
});

test("a motion is a model with its frames; its name (11/15/2026)", () => {
  const frame = Buffer.alloc(18).toString("base64");
  const m = { v: [0, 0, 0, 10, 0, 0, 0, 10, 0], f: [0, 1, 2], c: [100], frames: { fps: 12.5, v: [frame] } };
  deepStrictEqual(keepShape("motion", m), m);
  strictEqual(typeof keepShape("motion", { ...m, frames: undefined }), "string");
  strictEqual(MOTION_NAME.test("idle"), true);
  strictEqual(MOTION_NAME.test("dance 2"), true);
  strictEqual(MOTION_NAME.test("Dance"), false);   // (the relay lowers it first)
  strictEqual(MOTION_NAME.test("x".repeat(25)), false);
});

test("the encounter: a boss's name and its model (11/15/2026)", () => {
  const model = { v: [0, 0, 0, 10, 0, 0, 0, 10, 0], f: [0, 1, 2], c: [100] };
  deepStrictEqual(keepShape("encounter", { name: "Vorkath", model, x: 1 }), { name: "Vorkath", model });
  strictEqual(typeof keepShape("encounter", { name: "<b>", model }), "string");
  strictEqual(typeof keepShape("encounter", { name: "Vorkath" }), "string");
});

test("the goal: the item, its model, and when it was got with the counts then (11/17/2026, update 3)", () => {
  const model = { v: [0, 0, 0, 10, 0, 0, 0, 10, 0], f: [0, 1, 2], c: [100] };
  deepStrictEqual(keepShape("goal", { id: 4151, name: "Abyssal whip\n", model, x: 1 }), { id: 4151, name: "Abyssal whip", model });
  deepStrictEqual(keepShape("goal", { id: 4151, name: "Abyssal whip" }), { id: 4151, name: "Abyssal whip" });
  const got = keepShape("goal", { id: 4151, name: "Abyssal whip", got: { at: 1_794_944_909_617, counts: { kills: { "Abyssal Sire": 412, bad: -1 }, clues: { hard: 3 }, other: {} } } });
  deepStrictEqual(got, { id: 4151, name: "Abyssal whip", got: { at: 1_794_944_909_617, counts: { kills: { "Abyssal Sire": 412 }, clues: { hard: 3 } } } });
  strictEqual(typeof keepShape("goal", { id: 0, name: "x" }), "string");
  strictEqual(typeof keepShape("goal", { id: 4151, name: "  " }), "string");
  strictEqual(typeof keepShape("goal", { id: 4151, name: "x", got: { at: 5 } }), "string");
  strictEqual(typeof keepShape("goal", { id: 4151, name: "x", model: { v: "no" } }), "string");
});

test("the goal's list: the rest beside the one shown, each checked as it is, no model, each item once (11/19/2026)", () => {
  const model = { v: [0, 0, 0, 10, 0, 0, 0, 10, 0], f: [0, 1, 2], c: [100] };
  const at = 1_794_944_909_617;
  deepStrictEqual(keepShape("goal", { id: 4151, name: "Abyssal whip", model, more: [{ id: 11286, name: "Draconic visage\n", x: 1 },
    { id: 13576, name: "Dragon warhammer", got: { at, counts: { kills: { "Lizardman shaman": 801, bad: -2 } } } }] }),
    { id: 4151, name: "Abyssal whip", model, more: [{ id: 11286, name: "Draconic visage" },
      { id: 13576, name: "Dragon warhammer", got: { at, counts: { kills: { "Lizardman shaman": 801 } } } }] });
  // (an empty list says nothing; an old plugin's goal, with none, is as it was)
  deepStrictEqual(keepShape("goal", { id: 4151, name: "Abyssal whip", more: [] }), { id: 4151, name: "Abyssal whip" });
  strictEqual(typeof keepShape("goal", { id: 4151, name: "x", more: [{ id: 11286, name: "y", model }] }), "string");
  strictEqual(typeof keepShape("goal", { id: 4151, name: "x", more: [{ id: 4151, name: "x" }] }), "string");
  strictEqual(typeof keepShape("goal", { id: 4151, name: "x", more: [{ id: 11286, name: "y" }, { id: 11286, name: "y" }] }), "string");
  strictEqual(typeof keepShape("goal", { id: 4151, name: "x", more: [{ id: 0, name: "y" }] }), "string");
  strictEqual(typeof keepShape("goal", { id: 4151, name: "x", more: [{ id: 11286, name: "y", got: { at: 5 } }] }), "string");
  strictEqual(typeof keepShape("goal", { id: 4151, name: "x", more: { id: 11286 } }), "string");
  strictEqual(typeof keepShape("goal", { id: 4151, name: "x", more: Array.from({ length: GOALS_MORE + 1 }, (_, i) => ({ id: 20000 + i, name: "z" })) }), "string");
  strictEqual(typeof keepShape("goal", { id: 4151, name: "x", more: Array.from({ length: GOALS_MORE }, (_, i) => ({ id: 20000 + i, name: "z" })) }), "object");
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
