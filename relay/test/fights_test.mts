// Recorded fights on a player's page (src/fights.ts, 11/20/2026): what counts as a recording, the switches, the list
// trimmed. Node runs it as it is (it strips the types):
//
//   node test/fights_test.mts   (or npm test)
import { deepStrictEqual, strictEqual } from "node:assert";
import { gzipSync } from "node:zlib";
import { firstLine, fightNumbers, fightsOn, isHeader, newFightId, readFight, trimFights, FIGHT_ID, type Fight } from "../src/fights.ts";

let passed = 0;
const tests: [string, () => Promise<void> | void][] = [];
const test = (name: string, f: () => Promise<void> | void) => tests.push([name, f]);

const HEADER = '{"t":0,"c":18199,"e":"header","format":6,"label":"Vardorvis","startedMs":1795180328946}';
const rec = (lines: string[]) => new Uint8Array(gzipSync(lines.join("\n") + "\n"));

test("a recording's first line, unpacked; anything not gzip is none", async () => {
  strictEqual(await firstLine(rec([HEADER, '{"t":1,"e":"tick"}'])), HEADER);
  strictEqual(await firstLine(new TextEncoder().encode(HEADER + "\n")), null);
  strictEqual(await firstLine(new Uint8Array([0x1f, 0x8b, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16])), null);
});

test("Encounter Recorder's header, and nothing else", () => {
  strictEqual(isHeader(HEADER), true);
  strictEqual(isHeader('{"t":0,"e":"tick","format":6}'), false);
  strictEqual(isHeader('{"t":0,"e":"header","format":"6"}'), false);
  strictEqual(isHeader('{"t":0,"e":"header","format":0}'), false);
  strictEqual(isHeader("not json"), false);
  strictEqual(isHeader(null), false);
});

test("the switches: off unless FIGHTS is on; the defaults", () => {
  strictEqual(fightsOn({}), false);
  strictEqual(fightsOn({ FIGHTS: "off" }), false);
  strictEqual(fightsOn({ FIGHTS: " On " }), true);
  deepStrictEqual(fightNumbers({}), { public_default: false, most: 20, days: 30 });
  deepStrictEqual(fightNumbers({ FIGHTS_PUBLIC: "1", FIGHTS_MOST: "5", FIGHTS_DAYS: "0" }), { public_default: true, most: 5, days: 0 });
  deepStrictEqual(fightNumbers({ FIGHTS_MOST: "-1", FIGHTS_DAYS: "x" }), { public_default: false, most: 20, days: 30 });
});

test("an id: 22 base64url characters", () => {
  for (let i = 0; i < 50; i++) strictEqual(FIGHT_ID.test(newFightId()), true);
  strictEqual(FIGHT_ID.test("../../etc"), false);
});

const fight = (id: string, up: number): Fight => ({ id, foe: "x", label: "x", at: up, ticks: 1, died: false, taken: 0, dealt: 0, size: 1, public: true, up });

test("the list: newest first, past the most and too old go", () => {
  const now = 100 * 86_400_000;
  const list = [fight("a", now - 40 * 86_400_000), fight("b", now - 1000), fight("c", now - 2000), fight("d", now - 3000)];
  const { kept, gone } = trimFights(list, 2, 30, now);
  deepStrictEqual(kept.map((f) => f.id), ["b", "c"]);
  deepStrictEqual(gone.map((f) => f.id).sort(), ["a", "d"]);
  deepStrictEqual(trimFights(list, 20, 0, now).kept.map((f) => f.id), ["b", "c", "d", "a"]); // (days 0: never too old)
});

const upload = (meta: unknown, file: Uint8Array) => {
  const form = new FormData();
  form.set("meta", JSON.stringify(meta));
  form.set("fight", new File([file], "fight.jsonl.gz"));
  return new Request("https://relay/fights/x", { method: "POST", body: form });
};

test("an upload: its facts cleaned and bounded, public only if said", async () => {
  const got = await readFight(upload({ foe: "Vardorvis\n", label: "Vardorvis", at: 1795180328946, ticks: 210, died: "yes", taken: 51, dealt: 700, public: true, extra: 1 }, rec([HEADER])));
  if (Array.isArray(got)) throw new Error(got[1]);
  deepStrictEqual(got.meta, { foe: "Vardorvis", label: "Vardorvis", at: 1795180328946, ticks: 210, died: false, taken: 51, dealt: 700, size: got.file.length });
  strictEqual(got.public, true);
  const none = await readFight(upload({}, rec([HEADER])));
  if (Array.isArray(none)) throw new Error(none[1]);
  strictEqual(none.public, null);
  strictEqual(none.meta.foe, "a fight");
});

test("an upload that isn't a recording is refused", async () => {
  deepStrictEqual(await readFight(upload({}, new TextEncoder().encode(HEADER + "\n"))), [415, "that isn't an Encounter Recorder recording (gzipped)"]);
  deepStrictEqual(await readFight(upload({}, rec(['{"e":"tick"}']))), [415, "that isn't an Encounter Recorder recording (gzipped)"]);
  const bad = new FormData();
  bad.set("meta", "{}");
  deepStrictEqual(await readFight(new Request("https://relay/fights/x", { method: "POST", body: bad })), [400, "an upload is multipart: meta and fight"]);
});

for (const [name, f] of tests) {
  try { await f(); passed++; console.log("ok  ", name); } catch (e) { console.log("FAIL", name); console.log(e); }
}
console.log(`${passed} of ${tests.length} passed`);
if (passed !== tests.length) process.exit(1);
