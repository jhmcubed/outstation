// Recorded fights on a player's page (src/fights.ts, 11/20/2026): what counts as a recording, the switches, the list
// trimmed. Node runs it as it is (it strips the types):
//
//   node test/fights_test.mts   (or npm test)
import { deepStrictEqual, strictEqual } from "node:assert";
import { gzipSync } from "node:zlib";
import { BEST_SHOWN_MOST, bestIds, bestShowKey, bestShownSet, bossKey, firstLine, fightNumbers, fightsOn, historyCap, historyGone, historyOf, historyPublic, historyPut, historyShown, isHeader, isMoment, keptAsked, listed, markBest, momentKey, momentTick, newFightId, readFight, readMoment, readTimes, readTimesBody, timeSeconds, timesRefused, trimFights, FIGHT_ID, MAX_MOMENT, MAX_TIMES, type Fight, type FightTime, type TimeEntry } from "../src/fights.ts";

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
  deepStrictEqual(fightNumbers({}), { public_default: false, most: 20, days: 30, kept: 12 });
  deepStrictEqual(fightNumbers({ FIGHTS_PUBLIC: "1", FIGHTS_MOST: "5", FIGHTS_DAYS: "0" }), { public_default: true, most: 5, days: 0, kept: 12 });
  deepStrictEqual(fightNumbers({ FIGHTS_MOST: "-1", FIGHTS_DAYS: "x" }), { public_default: false, most: 20, days: 30, kept: 12 });
});

test("an id: 22 base64url characters", () => {
  for (let i = 0; i < 50; i++) strictEqual(FIGHT_ID.test(newFightId()), true);
  strictEqual(FIGHT_ID.test("../../etc"), false);
});

const fight = (id: string, up: number): Fight => ({ id, foe: "x", label: "x", at: up, ticks: 1, died: false, taken: 0, dealt: 0, size: 1, public: true, up });

test("the list: newest first, past the most and too old go", () => {
  const now = 100 * 86_400_000;
  const list = [fight("a", now - 40 * 86_400_000), fight("b", now - 1000), fight("c", now - 2000), fight("d", now - 3000)];
  const { stay: kept, gone } = trimFights(list, 2, 30, now);
  deepStrictEqual(kept.map((f) => f.id), ["b", "c"]);
  deepStrictEqual(gone.map((f) => f.id).sort(), ["a", "d"]);
  deepStrictEqual(trimFights(list, 20, 0, now).stay.map((f) => f.id), ["b", "c", "d", "a"]); // (days 0: never too old)
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

test("a trip's fight: its trip kept as said, a bad one refused (update 8)", async () => {
  const base = { foe: "Vorkath", label: "Vorkath", at: 1795180328946, ticks: 90, died: false, taken: 0, dealt: 750 };
  const got = await readFight(upload({ ...base, trip: { id: "abcDEF123_-x", no: 3 } }, rec([HEADER])));
  if (Array.isArray(got)) throw new Error(got[1]);
  deepStrictEqual(got.meta.trip, { id: "abcDEF123_-x", no: 3 });
  const ended = await readFight(upload({ ...base, trip: { id: "abcDEF123_-x", no: 4, end: "left\n", x: 1 } }, rec([HEADER])));
  if (Array.isArray(ended)) throw new Error(ended[1]);
  deepStrictEqual(ended.meta.trip, { id: "abcDEF123_-x", no: 4, end: "left" });
  const none = await readFight(upload(base, rec([HEADER])));
  if (Array.isArray(none)) throw new Error(none[1]);
  strictEqual("trip" in none.meta, false);
  deepStrictEqual(await readFight(upload({ ...base, trip: { id: "no/slash!", no: 1 } }, rec([HEADER]))), [400, "a trip's id is 8 to 22 letters, digits, _ or -"]);
  deepStrictEqual(await readFight(upload({ ...base, trip: { id: "abcDEF123_-x", no: 0 } }, rec([HEADER]))), [400, "a trip's no is 1 to 999"]);
  deepStrictEqual(await readFight(upload({ ...base, trip: "abc" }, rec([HEADER]))), [400, "a trip is {id, no, end?}"]);
});

test("an upload that isn't a recording is refused", async () => {
  deepStrictEqual(await readFight(upload({}, new TextEncoder().encode(HEADER + "\n"))), [415, "that isn't an Encounter Recorder recording (gzipped)"]);
  deepStrictEqual(await readFight(upload({}, rec(['{"e":"tick"}']))), [415, "that isn't an Encounter Recorder recording (gzipped)"]);
  const bad = new FormData();
  bad.set("meta", "{}");
  deepStrictEqual(await readFight(new Request("https://relay/fights/x", { method: "POST", body: bad })), [400, "an upload is multipart: meta and fight"]);
});

// The scene moment (11/21/2026): ghillie's replay/3d/moment.js's file, "OMNT" and a version byte, gzipped
const MOMENT = new Uint8Array(gzipSync(Buffer.concat([Buffer.from("OMNT"), Buffer.from([1, 0, 2, 0, 1, 0, 0, 0]), Buffer.from("{}"), Buffer.alloc(10)])));
const momentPost = (body: Uint8Array, q = "tick=60", headers: Record<string, string> = {}) =>
  [new Request("https://relay/fights/x/" + "a".repeat(22) + "/moment?" + q, { method: "POST", body, headers }), new URLSearchParams(q)] as const;

test("a scene moment: gzip of OMNT and a version; nothing else", async () => {
  strictEqual(await isMoment(MOMENT), true);
  strictEqual(await isMoment(new Uint8Array(Buffer.concat([Buffer.from("OMNT"), Buffer.alloc(20)]))), false); // (not gzipped)
  strictEqual(await isMoment(new Uint8Array(gzipSync(Buffer.from("OMNT\u0000" + "x".repeat(20))))), false); // (version 0)
  strictEqual(await isMoment(rec([HEADER])), false);
  strictEqual(await isMoment(new Uint8Array(gzipSync(Buffer.from("OMN")))), false);
  strictEqual(momentKey("sburb", "a".repeat(22)), "fights/sburb/" + "a".repeat(22) + ".moment.bin");
});

test("a moment's upload: its tick from the query or the header; too big, no tick, past the end or not a moment refused", async () => {
  const ok = await readMoment(...momentPost(MOMENT), 688);
  if (Array.isArray(ok)) throw new Error(ok[1]);
  deepStrictEqual([ok.tick, ok.file.length], [60, MOMENT.length]);
  const [r, q] = momentPost(MOMENT, "", { "x-moment-tick": "380" });
  const byHeader = await readMoment(r, q, 688);
  if (Array.isArray(byHeader)) throw new Error(byHeader[1]);
  strictEqual(byHeader.tick, 380);
  deepStrictEqual(await readMoment(...momentPost(MOMENT, "tick=-1"), 688), [400, "which tick? (?tick=)"]);
  deepStrictEqual(await readMoment(...momentPost(MOMENT, ""), 688), [400, "which tick? (?tick=)"]);
  deepStrictEqual(await readMoment(...momentPost(MOMENT, "tick=900"), 688), [400, "that tick is past the fight's end"]);
  strictEqual(Array.isArray(await readMoment(...momentPost(MOMENT, "tick=900"), 0)), false); // (a fight of unknown length)
  deepStrictEqual(await readMoment(...momentPost(rec([HEADER])), 688), [415, "that isn't a scene moment (gzipped)"]);
  const big = new Uint8Array(MAX_MOMENT + 1);
  big.set(MOMENT);
  deepStrictEqual(await readMoment(...momentPost(big), 688), [413, "a moment is 3 MB at most"]);
});

test("the list: the featured fight marked, and named only to whoever can see it", () => {
  const a = { ...fight("a", 3), public: true }, b = { ...fight("b", 2), public: false }, c = { ...fight("c", 1), public: true };
  const mine = listed([a, b, c], true, "b");
  deepStrictEqual([mine.featured, mine.fights.map((f) => f.id), mine.fights.map((f) => !!f.featured)], ["b", ["a", "b", "c"], [false, true, false]]);
  const theirs = listed([a, b, c], false, "b");
  deepStrictEqual([theirs.featured, theirs.fights.map((f) => f.id)], [null, ["a", "c"]]); // (kept to themselves: not named)
  deepStrictEqual(listed([a, b, c], false, "c").featured, "c");
  deepStrictEqual(listed([a, c], false, "gone").featured, null); // (taken down since)
  deepStrictEqual(listed([a, c], false, null).fights.some((f) => f.featured), false);
});

// Kept for good, and one upload carrying the fight and its moment (11/21/2026; fights.ts's header)
test("kept for good: never trimmed by days or the most, nor counted against it; the featured one spared alike", () => {
  const now = 100 * 86_400_000, day = 86_400_000;
  const list = [{ ...fight("old", now - 90 * day), kept: true as const }, fight("b", now - 1000), fight("c", now - 2000),
                { ...fight("k", now - 2500), kept: true as const }, fight("d", now - 3000), fight("feat", now - 50 * day), fight("e", now - 60 * day)];
  const { stay, gone } = trimFights(list, 2, 30, now, "feat");
  deepStrictEqual(stay.map((f) => f.id), ["b", "c", "k", "feat", "old"]); // (newest first; two counted: b and c)
  deepStrictEqual(gone.map((f) => f.id), ["d", "e"]);
  deepStrictEqual(trimFights(list, 2, 30, now).gone.map((f) => f.id), ["d", "feat", "e"]); // (nothing featured)
  deepStrictEqual(trimFights(list, 0, 0, now).stay.map((f) => f.id), ["k", "old"]);
});

test("FIGHTS_KEPT: 12 unless said; and what kept= and featured= ask together", () => {
  strictEqual(fightNumbers({ FIGHTS_KEPT: "3" }).kept, 3);
  strictEqual(fightNumbers({ FIGHTS_KEPT: "0" }).kept, 0);
  strictEqual(fightNumbers({ FIGHTS_KEPT: "x" }).kept, 12);
  strictEqual(fightNumbers({ FIGHTS_KEPT: "-2" }).kept, 12);
  strictEqual(keptAsked("1", null), true);
  strictEqual(keptAsked(null, "1"), true); // (featuring keeps it)
  strictEqual(keptAsked("0", null), false);
  strictEqual(keptAsked("0", "0"), false);
  strictEqual(keptAsked(null, "0"), null);
  strictEqual(keptAsked(null, null), null);
  strictEqual(Array.isArray(keptAsked("0", "1")), true);
});

// a moment whose own meta says its tick, as moment.js writes it; version 2 (looping) as well
const momentFile = (tick: number, version = 1) => {
  const meta = Buffer.from(JSON.stringify({ kind: "outside-moment", v: version, tick }));
  const head = Buffer.alloc(12);
  head.write("OMNT", 0, "latin1"); head[4] = version; head.writeUInt16LE(meta.length, 6); head.writeUInt32LE(0, 8);
  return new Uint8Array(gzipSync(Buffer.concat([head, meta, Buffer.alloc(30)])));
};
const uploadWith = (meta: unknown, moment: Uint8Array | null, tick?: string) => {
  const form = new FormData();
  form.set("meta", JSON.stringify(meta));
  form.set("fight", new File([rec([HEADER])], "fight.jsonl.gz"));
  if (moment) form.set("moment", new File([moment], "moment.bin"));
  if (tick !== undefined) form.set("moment_tick", tick);
  return new Request("https://relay/fights/x", { method: "POST", body: form });
};

test("a moment's tick read from its own meta", async () => {
  strictEqual(await momentTick(momentFile(412)), 412);
  strictEqual(await momentTick(momentFile(7, 2)), 7);
  strictEqual(await momentTick(MOMENT), null); // (its meta is "{}")
  strictEqual(await momentTick(rec([HEADER])), null);
});

test("an upload carrying its moment: the tick from moment_tick, meta, or the file; a bad one refuses the whole", async () => {
  const none = await readFight(uploadWith({ ticks: 500 }, null));
  if (Array.isArray(none)) throw new Error(none[1]);
  strictEqual(none.moment, null);
  const part = await readFight(uploadWith({ ticks: 500 }, momentFile(400, 2), "450"));
  if (Array.isArray(part)) throw new Error(part[1]);
  deepStrictEqual([part.moment?.tick, part.moment?.file.length], [450, momentFile(400, 2).length]);
  const byMeta = await readFight(uploadWith({ ticks: 500, moment_tick: 300 }, momentFile(400)));
  if (Array.isArray(byMeta)) throw new Error(byMeta[1]);
  strictEqual(byMeta.moment?.tick, 300);
  const byFile = await readFight(uploadWith({ ticks: 500 }, momentFile(400)));
  if (Array.isArray(byFile)) throw new Error(byFile[1]);
  strictEqual(byFile.moment?.tick, 400);
  deepStrictEqual(await readFight(uploadWith({ ticks: 500 }, MOMENT)), [400, "which tick is the moment? (moment_tick)"]);
  deepStrictEqual(await readFight(uploadWith({ ticks: 500 }, momentFile(400), "soon")), [400, "which tick is the moment? (moment_tick)"]);
  deepStrictEqual(await readFight(uploadWith({ ticks: 500 }, momentFile(900))), [400, "that tick is past the fight's end"]);
  deepStrictEqual(await readFight(uploadWith({ ticks: 500 }, rec([HEADER]), "5")), [415, "that isn't a scene moment (gzipped)"]);
  const big = new Uint8Array(MAX_MOMENT + 1);
  big.set(MOMENT);
  deepStrictEqual(await readFight(uploadWith({ ticks: 500 }, big, "5")), [413, "a moment is 3 MB at most"]);
});

test("kill times: the game's, bounded; [] is read and none; anything else refused", async () => {
  deepStrictEqual(readTimes([]), []);
  deepStrictEqual(readTimes([{ boss: "Fractured Archive\n", time: "28:28.80", pb: "24:45.00", t: 7678, team: "3 players", extra: 1 }]),
    [{ boss: "Fractured Archive", time: "28:28.80", t: 7678, pb: "24:45.00", team: "3 players" }]);
  // (a new personal best: pb the same as the time isn't kept twice; best only if true)
  deepStrictEqual(readTimes([{ boss: "", time: "1:12", pb: "1:12", best: true, t: -3 }]), [{ boss: null, time: "1:12", t: 0, best: true }]);
  deepStrictEqual(readTimes([{ time: "1:02:03.4", best: "yes" }]), [{ boss: null, time: "1:02:03.4", t: 0 }]);
  strictEqual(timesRefused(readTimes([{ time: "soon" }])), true);
  strictEqual(timesRefused(readTimes([{ time: "1:12", pb: "<b>" }])), true);
  strictEqual(timesRefused(readTimes({ time: "1:12" })), true);
  strictEqual(timesRefused(readTimes(Array.from({ length: MAX_TIMES + 1 }, () => ({ time: "1:12" })))), true);
  strictEqual(timesRefused(readTimes([])), false);
  const post = (body: string) => new Request("https://relay/fights/x/id?times=1", { method: "POST", body });
  deepStrictEqual(await readTimesBody(post('{"times":[{"time":"1:48","pb":"1:12","t":90}]}')), [{ boss: null, time: "1:48", t: 90, pb: "1:12" }]);
  deepStrictEqual(await readTimesBody(post("not json")), [400, 'times are JSON: {"times": [...]}']);
  deepStrictEqual(await readTimesBody(post(JSON.stringify({ times: [], pad: "x".repeat(5000) }))), [413, "times are 4 KB at most"]);
});

test("an upload's meta may carry its kill times; bad ones refuse it", async () => {
  const got = await readFight(upload({ foe: "Giant Mole", times: [{ boss: "Giant Mole", time: "1:12", best: true, t: 140 }] }, rec([HEADER])));
  if (Array.isArray(got)) throw new Error(got[1]);
  deepStrictEqual(got.meta.times, [{ boss: "Giant Mole", time: "1:12", t: 140, best: true }]);
  deepStrictEqual(await readFight(upload({ times: [{ time: "later" }] }, rec([HEADER]))), [400, "a time is the game's, like 1:48 or 28:28.80"]);
  const none = await readFight(upload({}, rec([HEADER])));
  if (Array.isArray(none)) throw new Error(none[1]);
  strictEqual("times" in none.meta, false);
});

// The times' history and each boss's best (11/22/2026; fights.ts's header). The channel's own steps, as channel.ts
// takes them: an upload or ?times=1 puts a fight's times in (historyPut), the trim's gone go without their ids
// (historyGone, false), a take-down's go whole (historyGone, true), ?public= carries over (historyPublic), and the list
// is served as historyShown.
const day = 86_400_000, NOW = 200 * day;
const timed = (id: string, ago: number, times: FightTime[], o: Partial<Fight> = {}): Fight =>
  ({ ...fight(id, NOW - ago * day), foe: "Vardorvis", label: "Vardorvis", times, ...o });
const kill = (time: string, o: Partial<FightTime> = {}): FightTime => ({ boss: "Vardorvis", time, t: 100, ...o });
// (the channel as a whole: its fights and its history, trimmed as fightList trims them)
function channel(fights: Fight[], most = 20, days = 30) {
  let history: TimeEntry[] = [];
  for (const f of fights) history = historyPut(history, f);
  const { stay, gone } = trimFights(fights, most, days, NOW);
  history = historyGone(history, gone.map((f) => f.id), false);
  return { stay, gone, history };
}

test("a boss's key, as the page's; the game's times in seconds", () => {
  strictEqual(bossKey("The Nightmare"), "nightmare");
  strictEqual(bossKey("subdued Wintertodt"), "wintertodt");
  strictEqual(bossKey("Tombs of Amascut: Expert Mode"), "tombs of amascut expert");
  strictEqual(timeSeconds("1:12"), 72);
  strictEqual(timeSeconds("28:28.80"), 1708.8);
  strictEqual(timeSeconds("1:02:03"), 3723);
});

test("a fight's times as history: the fight's name when the line named none, its id, hidden if kept to themselves", () => {
  const f = timed("a".repeat(22), 1, [kill("1:12", { best: true }), { boss: null, time: "1:30", t: 9 }], { public: false, label: "Duke Sucellus" });
  deepStrictEqual(historyOf(f), [
    { key: "vardorvis", boss: "Vardorvis", at: f.at, t: 100, time: "1:12", best: true, id: f.id, hidden: true },
    { key: "duke sucellus", boss: "Duke Sucellus", at: f.at, t: 9, time: "1:30", id: f.id, hidden: true }]);
});

test("the history outlives a trim: past the most and past the days, the times stay without their ids", () => {
  const fights = [timed("old", 45, [kill("2:59")]), ...Array.from({ length: 22 }, (_, i) => timed("f" + i, i * 0.1, [kill("2:" + (10 + i))]))];
  const { stay, gone, history } = channel(fights, 20, 30);
  deepStrictEqual(gone.map((f) => f.id), ["f21", "old"]); // (f0, the best, kept uncounted: so twenty more)
  strictEqual(history.length, 23); // (every time still there)
  const ups = new Set(stay.map((f) => f.id));
  for (const e of history) strictEqual(!!e.id, ups.has(e.id ?? ""), "an id only while its fight is up");
  strictEqual(history.find((e) => e.time === "2:59")?.id, undefined); // (the 45-day-old one: trimmed, but on record)
  strictEqual(history[0].time, "2:59"); // (oldest first)
});

test("a take-down takes its times with it; a trim doesn't", () => {
  let h = historyPut([], timed("a", 1, [kill("1:12"), kill("1:20", { t: 300 })]));
  h = historyPut(h, timed("b", 2, [kill("1:40")]));
  deepStrictEqual(historyGone(h, ["a"], true).map((e) => e.time), ["1:40"]);
  deepStrictEqual(historyGone(h, ["a"], false).map((e) => [e.time, e.id ?? null]), [["1:40", "b"], ["1:12", null], ["1:20", null]]);
  // (times set again: the fight's old ones replaced, not doubled)
  deepStrictEqual(historyPut(h, timed("a", 1, [kill("1:11")])).map((e) => e.time), ["1:40", "1:11"]);
  deepStrictEqual(historyPut(h, timed("a", 1, [])).map((e) => e.time), ["1:40"]);
});

test("the history never shows a visitor the times of a fight kept to themselves, up or gone", () => {
  const pub = timed("pub", 1, [kill("1:20")]), priv = timed("priv", 2, [kill("1:10")], { public: false });
  let h = historyPut(historyPut([], pub), priv);
  const list = [pub, priv];
  deepStrictEqual(historyShown(h, false, list.filter((f) => f.public)).map((e) => e.time), ["1:20"]);
  deepStrictEqual(historyShown(h, true, list).map((e) => [e.time, e.id, !!e.hidden]), [["1:10", "priv", true], ["1:20", "pub", false]]);
  strictEqual(historyShown(h, false, [pub]).some((e) => "hidden" in e), false);
  // (trimmed while kept to themselves: still the owner's only)
  const trimmed = historyGone(h, ["priv"], false);
  deepStrictEqual(historyShown(trimmed, false, [pub]).map((e) => e.time), ["1:20"]);
  // (shown later, kept to themselves again: the times follow)
  h = historyPublic(h, "priv", true);
  deepStrictEqual(historyShown(h, false, list).map((e) => e.time), ["1:10", "1:20"]);
  h = historyPublic(h, "pub", false);
  deepStrictEqual(historyShown(h, false, [priv]).map((e) => e.time), ["1:10"]);
  // (an id only for a fight this viewer is shown)
  deepStrictEqual(historyShown(h, false, []).map((e) => e.id ?? null), [null]);
});

test("the history's cap: the oldest go first, never a boss's best nor a fight still up", () => {
  let h: TimeEntry[] = [];
  h = historyPut(h, timed("best", 100, [kill("0:59")]));
  for (let i = 0; i < 30; i++) h = historyGone(historyPut(h, timed("g" + i, 90 - i, [kill("1:" + (10 + i))])), ["g" + i], false);
  h = historyPut(h, timed("up", 95, [kill("2:00")]));
  h = historyGone(h, ["best"], false);
  const capped = historyCap(h, 10);
  strictEqual(capped.length, 10);
  strictEqual(capped[0].time, "0:59"); // (the best, oldest of all, kept)
  strictEqual(capped.some((e) => e.id === "up"), true); // (its fight's still up)
  deepStrictEqual(capped.slice(2).map((e) => e.time), ["1:32", "1:33", "1:34", "1:35", "1:36", "1:37", "1:38", "1:39"]);
});

test("each boss's best is kept past the most and past the days, uncounted", () => {
  const best = timed("best", 50, [kill("1:02")]);
  const rest = Array.from({ length: 20 }, (_, i) => timed("f" + i, i * 0.1, [kill("1:" + (30 + i))]));
  const { stay, gone } = trimFights([best, ...rest], 20, 30, NOW);
  deepStrictEqual(gone, []);
  strictEqual(stay.length, 21); // (twenty counted, and the best)
  strictEqual(stay.some((f) => f.id === "best"), true);
  // (the best of another boss too, and a raid's per team size)
  const raid = (id: string, ago: number, time: string, team: string) => timed(id, ago, [{ boss: "Chambers of Xeric", time, t: 1, team }]);
  const raids = [raid("solo", 60, "40:00", "Solo"), raid("trio", 61, "30:00", "3 players"), raid("trio2", 62, "31:00", "3 players")];
  deepStrictEqual([...bestIds([best, ...rest, ...raids])].sort(), ["best", "solo", "trio"]);
  deepStrictEqual(trimFights([best, ...rest, ...raids], 20, 30, NOW).gone.map((f) => f.id), ["trio2"]);
  // (nothing timed: none kept for it)
  strictEqual(bestIds([fight("x", 1)]).size, 0);
});

test("a best beaten is let go (and its time stays on record); taken down, the next quickest holds it", () => {
  const old = timed("old", 40, [kill("1:05")]), mid = timed("mid", 35, [kill("1:08")]), now = timed("new", 0, [kill("1:01")]);
  deepStrictEqual(trimFights([old, mid], 20, 30, NOW).stay.map((f) => f.id), ["old"]); // (old holds it; mid's days are up)
  const after = channel([old, mid, now], 20, 30);
  deepStrictEqual(after.stay.map((f) => f.id), ["new"]); // (beaten: old rejoins the trim and goes)
  deepStrictEqual(after.history.map((e) => [e.time, e.id ?? null]), [["1:05", null], ["1:08", null], ["1:01", "new"]]);
  // (the best taken down: the next quickest still up holds it)
  deepStrictEqual([...bestIds([old, mid])], ["old"]);
  deepStrictEqual([...bestIds([mid])], ["mid"]);
  // (a tie: the earlier keeps it)
  deepStrictEqual([...bestIds([timed("later", 1, [kill("1:05")]), old])], ["old"]);
});

test("the best marked in the list, worked out here: a stored best never counts", () => {
  const a = timed("a", 1, [kill("1:05")]), b = { ...timed("b", 2, [kill("1:30")]), best: true as const }, c = fight("c", 3);
  deepStrictEqual(markBest([a, b, c]).map((f) => !!f.best), [true, false, false]);
  strictEqual("best" in markBest([b])[0], true); // (alone, b is the best)
  strictEqual("best" in markBest([c])[0], false);
});

test("the 12 kept for good don't count a best: the count is of kept alone", () => {
  // (channel.ts's ?kept=1 counts the list's `kept`; a best isn't kept unless its owner kept it)
  const list = markBest([timed("best", 1, [kill("1:00")]), { ...timed("k", 2, []), kept: true as const }]);
  strictEqual(list.filter((x) => x.kept).length, 1);
  strictEqual(list.filter((x) => x.best).length, 1);
});

// A boss's best with the kept ones (11/22/2026): its owner's switch in its panel; channel.ts keeps the keys and hands
// them to markBest, which marks such a best `with_kept` for every viewer
test("a boss's best shown with the kept ones: marked with_kept; every best still kept, none counted against the 12", () => {
  const vard = timed("vard", 50, [kill("1:02")]), duke = timed("duke", 55, [{ boss: "Duke Sucellus", time: "1:50", t: 1 }]);
  const rest = Array.from({ length: 3 }, (_, i) => timed("f" + i, i, [kill("1:" + (30 + i))]));
  const list = [vard, duke, ...rest];
  deepStrictEqual(trimFights(list, 20, 30, NOW).gone, []); // (both bests kept, shown or not)
  const marked = markBest(list, ["vardorvis"]);
  deepStrictEqual(marked.map((f) => [f.id, !!f.best, !!f.with_kept]),
    [["vard", true, true], ["duke", true, false], ["f0", false, false], ["f1", false, false], ["f2", false, false]]);
  strictEqual(marked.filter((f) => f.kept).length, 0); // (with the kept ones isn't kept for good: the 12 untouched)
  // (it follows the boss, not the fight: beaten, the new best's the one shown)
  const now = timed("new", 0, [kill("0:58")]);
  deepStrictEqual(markBest([...list, now], ["vardorvis"]).filter((f) => f.with_kept).map((f) => f.id), ["new"]);
  // (taken down: the next quickest still up)
  deepStrictEqual(markBest(rest, ["vardorvis"]).filter((f) => f.with_kept).map((f) => f.id), ["f0"]);
  // (a raid's, every team size; a stored with_kept never counts)
  const raid = (id: string, team: string, time: string) => timed(id, 60, [{ boss: "Chambers of Xeric", time, t: 1, team }]);
  deepStrictEqual(markBest([raid("solo", "Solo", "40:00"), raid("trio", "3 players", "30:00"), raid("trio2", "3 players", "31:00")], ["chambers of xeric"])
    .map((f) => !!f.with_kept), [true, true, false]);
  strictEqual("with_kept" in markBest([{ ...fight("x", 1), with_kept: true as const }], ["vardorvis"])[0], false);
  strictEqual("with_kept" in markBest(list)[0], false); // (none chosen: none)
});

test("?bestshow=: a boss by its name or key; on and off, kept sorted and once; at most BEST_SHOWN_MOST", () => {
  strictEqual(bestShowKey("The Fractured Archive"), "fractured archive");
  strictEqual(bestShowKey("vardorvis"), "vardorvis");
  deepStrictEqual(bestShowKey(""), [400, "which boss? (bestshow=<its name>)"]);
  deepStrictEqual(bestShowKey("!!!"), [400, "which boss? (bestshow=<its name>)"]);
  deepStrictEqual(bestShownSet([], "vardorvis", true), ["vardorvis"]);
  deepStrictEqual(bestShownSet(["vardorvis"], "vardorvis", true), ["vardorvis"]);
  deepStrictEqual(bestShownSet(["vardorvis"], "duke sucellus", true), ["duke sucellus", "vardorvis"]);
  deepStrictEqual(bestShownSet(["duke sucellus", "vardorvis"], "vardorvis", false), ["duke sucellus"]);
  deepStrictEqual(bestShownSet([], "vardorvis", false), []);
  const full = Array.from({ length: BEST_SHOWN_MOST }, (_, i) => "boss " + i);
  deepStrictEqual(bestShownSet(full, "one more", true), [409, BEST_SHOWN_MOST + " bosses at most with their best shown with the kept ones"]);
  strictEqual((bestShownSet(full, "boss 3", false) as string[]).length, BEST_SHOWN_MOST - 1);
  strictEqual((bestShownSet(full, "boss 3", true) as string[]).length, BEST_SHOWN_MOST); // (already on: no more)
});

for (const [name, f] of tests) {
  try { await f(); passed++; console.log("ok  ", name); } catch (e) { console.log("FAIL", name); console.log(e); }
}
console.log(`${passed} of ${tests.length} passed`);
if (passed !== tests.length) process.exit(1);
