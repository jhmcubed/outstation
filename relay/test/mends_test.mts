// The keeper's mends to a register's notes (src/mends.ts, 11/19/2026). Node runs it as it is (it strips the types):
//
//   node --import ./test/resolve.mjs test/mends_test.mts   (or npm test)
import { deepStrictEqual, strictEqual } from "node:assert";
import { mendNotes, MENDS, type Mend } from "../src/mends.ts";
import { pyjson, aetherText } from "../src/text.ts";

let passed = 0;
const tests: [string, () => void][] = [];
const test = (name: string, f: () => void) => tests.push([name, f]);
const note = (id: number, name: string, text: string) => pyjson({ note: { id, name, text, who: 7, at: 1795053600000 } });
const art = ["⠀⣠⠃", "⣿⣿", "row 3", "row 4"].join("\n");
const mend: Mend = { channel: "someone", id: 1, name: "Ja", cut: 2, text: art };

test("the cut note becomes the whole drawing; its id, name, writer, time and device stay; and only once", () => {
  const notes: [number, string, string][] = [[1, note(1, "Ja", art.split("\n").slice(0, 2).join("\n")), "dev"], [2, note(2, "Ja", "hi"), "d2"]];
  strictEqual(mendNotes("someone", notes, [mend]), true);
  deepStrictEqual(JSON.parse(notes[0][1]), { note: { id: 1, name: "Ja", text: art, who: 7, at: 1795053600000 } });
  strictEqual(notes[0][1], note(1, "Ja", art));
  deepStrictEqual([notes[0][0], notes[0][2], notes[1][1]], [1, "dev", note(2, "Ja", "hi")]);
  strictEqual(mendNotes("someone", notes, [mend]), false);
});

test("nothing for another channel, another name, other words or a note that's gone", () => {
  const cut = art.split("\n").slice(0, 2).join("\n");
  for (const [ch, notes] of [
    ["other", [[1, note(1, "Ja", cut), ""]]],
    ["someone", [[1, note(1, "Jb", cut), ""]]],
    ["someone", [[1, note(1, "Ja", cut + "!"), ""]]],
    ["someone", [[2, note(2, "Ja", cut), ""]]],
  ] as [string, [number, string, string][]][]) {
    const before = JSON.stringify(notes);
    strictEqual(mendNotes(ch, notes, [mend]), false);
    strictEqual(JSON.stringify(notes), before);
  }
});

test("Ja's drawing: 17 rows of 34, kept whole, its first 12 as the register kept them", () => {
  const m = MENDS.find((x) => x.channel === "salutecflagg" && x.id === 1)!;
  const rows = m.text.split("\n");
  deepStrictEqual([rows.length, new Set(rows.map((r) => Array.from(r).length)).size, Array.from(rows[0]).length], [17, 1, 34]);
  strictEqual(aetherText(m.text), m.text);
});

for (const [name, f] of tests) { f(); passed++; console.log("ok  " + name); }
console.log(passed + " passed");
