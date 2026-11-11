// The lookups' shapes, keeping and asking (src/lookup.ts), on a clock and a network of its own. Node runs it as it is
// (it strips the types):
//
//   node test/lookup_test.mts   (or npm test)
//
// Nothing here asks a real host. relay-py/test_lookup.py is the same for the Python relay.
import { deepStrictEqual, ok, strictEqual } from "node:assert";
import { type Entry, type Fetched, FAIL_BASE, HOSTS, MISS_FRESH, PER_MINUTE, PER_MINUTE_WOM_KEYED, Shelf, type Source, USER_AGENT, lookupHeaders,
  perMinute, plainName, pruned, want }
  from "../src/lookup.ts";

let passed = 0;
const tests: [string, () => void | Promise<void>][] = [];
const test = (name: string, f: () => void | Promise<void>) => tests.push([name, f]);
const enc = new TextEncoder(), dec = new TextDecoder();
const PNG = new Uint8Array([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1, 2, 3]);
const MIN = 60_000, HOUR = 3_600_000, DAY = 86_400_000;

/** A shelf on a clock (t) and a network (answers by URL) of the test's own, counting what it asks. */
function rig(answers: Record<string, Fetched | (() => Fetched | Promise<Fetched>)> = {}, limits = PER_MINUTE) {
  const kept = new Map<string, Entry>(), asked: string[] = [];
  const r = { t: 1_000_000_000_000, kept, asked, answers, shelf: null as unknown as Shelf };
  r.shelf = new Shelf({ get: async (k) => kept.get(k), put: async (k, e) => { kept.set(k, e); } },
    async (url: string, _s: Source) => {
      asked.push(url);
      const a = r.answers[url];
      if (!a) return { status: 404, body: null, retryAfter: null };
      return typeof a === "function" ? a() : a;
    }, () => r.t, limits);
  return r;
}
const okJson = (v: unknown): Fetched => ({ status: 200, body: enc.encode(JSON.stringify(v)), retryAfter: null });
const W = (p: string) => want(p.split("/").filter(Boolean))!;
const WOM = "https://api.wiseoldman.net/v2/players/", RP = "https://api.runeprofile.com/v1/accounts/";

test("the shapes", () => {
  deepStrictEqual([W("lookup/wom/Sburb").key, W("lookup/wom/Sburb").url], ["wom:sburb", WOM + "sburb"]);
  strictEqual(W("lookup/wom/Iron_Man-2").url, WOM + "iron%20man%202");
  strictEqual(W("lookup/wom/a b/gained").url, WOM + "a%20b/gained?period=week");
  strictEqual(W("lookup/wom/x/achievements").url, WOM + "x/achievements");
  strictEqual(W("lookup/wom/x/groups").url, WOM + "x/groups");
  strictEqual(W("lookup/rp/X").url, RP + "x");
  strictEqual(W("lookup/rp/x/activities").url, RP + "x/activities?limit=20");
  strictEqual(W("icon/item/04151.png").url, "https://static.runelite.net/cache/item/icon/4151.png");
  strictEqual(W("icon/item/04151.png").key, "item:4151.png");
  strictEqual(W("icon/metric/clue_scrolls_hard.png").url,
    "https://cdn.jsdelivr.net/gh/wise-old-man/wise-old-man@master/app/public/img/metrics_small/clue_scrolls_hard.png");
  strictEqual(want(["lookup", "wom", "x"], "http://127.0.0.1:9/")!.url, "http://127.0.0.1:9/v2/players/x");
  strictEqual(want(["icon", "item", "1.png"], "http://127.0.0.1:9")!.url, "http://127.0.0.1:9/cache/item/icon/1.png");
  for (const bad of ["lookup/wom", "lookup/wom/thirteenchars", "lookup/wom/a.b", "lookup/wom/x/gained/more", "lookup/wom/x/snapshots",
                     "lookup/rp/x/groups", "lookup/hiscores/x", "lookup/wom/___", "icon/item/x.png", "icon/item/1234567.png",
                     "icon/item/1.gif", "icon/metric/Zulrah.png", "icon/metric/../x.png", "icon/other/1.png", "icon/item"]) {
    strictEqual(want(bad.split("/").filter(Boolean)), null, bad);
  }
  strictEqual(want(["lookup", "wom", "x", ""]), null);
  strictEqual(plainName("  "), null);
  strictEqual(plainName("A  b__c"), "a b c");
  ok(!USER_AGENT.includes("@"));
});

test("the Outside page's shapes (11/10/2026): periods, stretches, timelines, RuneProfile's parts and pages", () => {
  const now = Date.UTC(2026, 10, 10, 12), Q = (p: string, q: Record<string, string>) => want(p.split("/").filter(Boolean), null, q, now);
  strictEqual(Q("lookup/wom/x/gained", { period: "month" })!.url, WOM + "x/gained?period=month");
  strictEqual(Q("lookup/wom/x/gained", { period: "month" })!.key, "wom:x/gained?period=month");
  strictEqual(Q("lookup/wom/x/gained", {})!.key, "wom:x/gained?period=week");
  strictEqual(Q("lookup/wom/x/gained", { period: "forever" }), null);
  // (a stretch: its end clamped to now upstream, the key as asked; one wholly past an hour ago keeps 30 days)
  const a = Q("lookup/wom/x/gained", { from: "2026-11-01T07:00:00.000Z", to: "2026-11-30T07:00:00.000Z" })!;
  strictEqual(a.url, WOM + "x/gained?startDate=2026-11-01T07%3A00%3A00.000Z&endDate=2026-11-10T12%3A00%3A00.000Z");
  strictEqual(a.key, "wom:x/gained?from=2026-11-01T07:00:00.000Z&to=2026-11-30T07:00:00.000Z");
  strictEqual(a.fresh, HOUR);
  const past = Q("lookup/wom/x/gained", { from: "2026-10-01T06:00:00.000Z", to: "2026-11-01T06:00:00.000Z" })!;
  strictEqual(past.url, WOM + "x/gained?startDate=2026-10-01T06%3A00%3A00.000Z&endDate=2026-11-01T06%3A00%3A00.000Z");
  strictEqual(past.fresh, 30 * DAY);
  strictEqual(Q("lookup/wom/x/gained", { from: "2025-02-03T04:05:06.789Z" })!.url,
    WOM + "x/gained?startDate=2025-02-03T04%3A05%3A06.789Z&endDate=2026-11-10T12%3A00%3A00.000Z");
  for (const bad of [{ from: "yesterday" }, { from: "2026-11-02T00:00:00.000Z", to: "2026-11-01T00:00:00.000Z" },
                     { from: "2027-01-01T00:00:00.000Z" }, { from: "2026-11-01T00:00:00.000Z", to: "x" }]) {
    strictEqual(Q("lookup/wom/x/gained", bad), null, JSON.stringify(bad));
  }
  strictEqual(Q("lookup/wom/x/timeline", { metric: "zulrah", period: "year" })!.url, WOM + "x/snapshots/timeline?metric=zulrah&period=year");
  strictEqual(Q("lookup/wom/x/timeline", { metric: "overall", from: "2025-01-01T00:00:00.000Z" })!.url,
    WOM + "x/snapshots/timeline?metric=overall&startDate=2025-01-01T00%3A00%3A00.000Z&endDate=2026-11-10T12%3A00%3A00.000Z");
  strictEqual(Q("lookup/wom/x/timeline", { period: "year" }), null);
  strictEqual(Q("lookup/wom/x/timeline", { metric: "Zulrah", period: "year" }), null);
  strictEqual(Q("lookup/wom/x/timeline", { metric: "zulrah" }), null);
  strictEqual(Q("lookup/wom/x/progress", {})!.url, WOM + "x/achievements/progress");
  for (const [p, up] of [["skills", "/skills"], ["quests", "/quests"], ["diaries", "/achievement-diaries"],
                         ["combat", "/combat-achievements/tasks"], ["log", "/collection-log"]]) {
    strictEqual(Q("lookup/rp/x/" + p, {})!.url, RP + "x" + up);
  }
  strictEqual(Q("lookup/rp/x/activities", { limit: "15" })!.url, RP + "x/activities?limit=15");
  const pg = Q("lookup/rp/x/activities", { limit: "50", activityTypes: "new_item_obtained", cursor: "t-msc:n=7_i-3p" })!;
  strictEqual(pg.url, RP + "x/activities?limit=50&activityTypes=new_item_obtained&cursor=t-msc%3An%3D7_i-3p");
  strictEqual(pg.key, "rp:x/activities?limit=50&activityTypes=new_item_obtained&cursor=t-msc%3An%3D7_i-3p");
  strictEqual(pg.fresh, 6 * HOUR);
  for (const bad of [{ limit: "0" }, { limit: "51" }, { limit: "5x" }, { cursor: "a b" }, { activityTypes: "DROP" }]) {
    strictEqual(Q("lookup/rp/x/activities", bad), null, JSON.stringify(bad));
  }
  strictEqual(Q("lookup/wom/x/constructor", {}), null);
  strictEqual(Q("lookup/rp/x/gained", { period: "week" }), null);
  strictEqual(Q("lookup/wom/x/activities", {}), null);
  // (anything else in the query is ignored: the same key)
  strictEqual(Q("lookup/wom/x", { cachebust: "1" })!.key, "wom:x");
});

test("asked once, then kept while fresh", async () => {
  const r = rig({ [WOM + "sburb"]: okJson({ username: "sburb" }) });
  const a = await r.shelf.read(W("lookup/wom/sburb"));
  strictEqual(a.status, 200);
  deepStrictEqual(JSON.parse(dec.decode(a.body!)), { username: "sburb" });
  strictEqual(a.headers["access-control-allow-origin"], "*");
  strictEqual(a.headers["cache-control"], "public, max-age=300");
  strictEqual(a.headers["x-lookup-at"], String(r.t));
  strictEqual(a.headers["x-lookup-stale"], undefined);
  r.t += 59 * MIN;
  await r.shelf.read(W("lookup/wom/SBURB"));
  strictEqual(r.asked.length, 1);
  r.t += 2 * MIN; // (past the hour)
  await r.shelf.read(W("lookup/wom/sburb"));
  strictEqual(r.asked.length, 2);
});

test("many reads at once: one request", async () => {
  let release!: () => void;
  const gate = new Promise<void>((res) => { release = res; });
  const r = rig({ [RP + "x"]: async () => { await gate; return okJson({ username: "x" }); } });
  const reads = Array.from({ length: 10 }, () => r.shelf.read(W("lookup/rp/x")));
  release();
  for (const a of await Promise.all(reads)) strictEqual(a.status, 200);
  strictEqual(r.asked.length, 1);
});

test("not there: Wise Old Man's 404 and RuneProfile's 400 kept as 404 for six hours", async () => {
  const r = rig({ [RP + "nobody"]: { status: 400, body: null, retryAfter: null } });
  const a = await r.shelf.read(W("lookup/rp/nobody")), b = await r.shelf.read(W("lookup/wom/nobody"));
  strictEqual(a.status, 404);
  strictEqual(b.status, 404);
  strictEqual(a.headers["cache-control"], "public, max-age=3600");
  r.t += MISS_FRESH - 1;
  await r.shelf.read(W("lookup/rp/nobody"));
  strictEqual(r.asked.length, 2);
  r.t += 2;
  await r.shelf.read(W("lookup/rp/nobody"));
  strictEqual(r.asked.length, 3);
});

test("a failing source: the last good one stands (stale), and the key waits, doubling", async () => {
  const r = rig({ [WOM + "x"]: okJson({ v: 1 }) });
  await r.shelf.read(W("lookup/wom/x"));
  r.t += 2 * HOUR;
  r.answers[WOM + "x"] = { status: 500, body: null, retryAfter: null };
  let a = await r.shelf.read(W("lookup/wom/x"));
  strictEqual(a.status, 200);
  strictEqual(a.headers["x-lookup-stale"], "1");
  strictEqual(a.headers["cache-control"], "public, max-age=60");
  deepStrictEqual(JSON.parse(dec.decode(a.body!)), { v: 1 });
  strictEqual(r.asked.length, 2);
  await r.shelf.read(W("lookup/wom/x")); // (waiting: not asked)
  strictEqual(r.asked.length, 2);
  r.t += FAIL_BASE;
  await r.shelf.read(W("lookup/wom/x"));
  strictEqual(r.asked.length, 3);
  r.t += FAIL_BASE; // (the wait's two minutes now)
  await r.shelf.read(W("lookup/wom/x"));
  strictEqual(r.asked.length, 3);
  r.t += FAIL_BASE;
  r.answers[WOM + "x"] = okJson({ v: 2 });
  a = await r.shelf.read(W("lookup/wom/x"));
  strictEqual(r.asked.length, 4);
  deepStrictEqual(JSON.parse(dec.decode(a.body!)), { v: 2 });
  strictEqual(a.headers["x-lookup-stale"], undefined);
});

test("nothing kept and failing: 503 with Retry-After", async () => {
  const r = rig({ [WOM + "x"]: { status: 502, body: null, retryAfter: null } });
  const a = await r.shelf.read(W("lookup/wom/x"));
  strictEqual(a.status, 503);
  strictEqual(a.headers["retry-after"], "60");
  strictEqual(a.headers["cache-control"], "no-store");
});

test("what isn't JSON or a PNG isn't kept", async () => {
  const r = rig({ [WOM + "x"]: { status: 200, body: enc.encode("<html>"), retryAfter: null },
                  "https://static.runelite.net/cache/item/icon/1.png": { status: 200, body: enc.encode("{}"), retryAfter: null },
                  "https://static.runelite.net/cache/item/icon/2.png": { status: 200, body: PNG, retryAfter: null } });
  strictEqual((await r.shelf.read(W("lookup/wom/x"))).status, 503);
  strictEqual((await r.shelf.read(W("icon/item/1.png"))).status, 503);
  const a = await r.shelf.read(W("icon/item/2.png"));
  strictEqual(a.status, 200);
  strictEqual(a.headers["content-type"], "image/png");
  strictEqual(a.headers["cache-control"], "public, max-age=604800");
  r.t += 29 * DAY;
  await r.shelf.read(W("icon/item/2.png"));
  strictEqual(r.asked.filter((u) => u.endsWith("/2.png")).length, 1);
});

test("Wise Old Man's key: x-api-key only to api.wiseoldman.net, never to another host or without a key", () => {
  const key = "test-key-not-real";
  strictEqual(lookupHeaders(HOSTS.wom + "sburb", "wom", key)["x-api-key"], key);
  strictEqual(lookupHeaders(HOSTS.wom + "sburb", "wom", key)["user-agent"], USER_AGENT);
  strictEqual(lookupHeaders(HOSTS.wom + "sburb", "wom", null)["x-api-key"], undefined);
  strictEqual(lookupHeaders(HOSTS.wom + "sburb", "wom", "")["x-api-key"], undefined);
  // (RuneProfile, the icons, and a test's stand-in upstream for Wise Old Man never see it)
  strictEqual(lookupHeaders(HOSTS.rp + "sburb", "rp", key)["x-api-key"], undefined);
  strictEqual(lookupHeaders(HOSTS.item + "4151.png", "item", key)["x-api-key"], undefined);
  strictEqual(lookupHeaders("http://127.0.0.1:9/wom/sburb", "wom", key)["x-api-key"], undefined);
  strictEqual(lookupHeaders("https://api.wiseoldman.net.example.com/v2/players/x", "wom", key)["x-api-key"], undefined);
});

test("Wise Old Man's minute with a key: PER_MINUTE_WOM_KEYED, the others as they were", async () => {
  deepStrictEqual(perMinute(null), PER_MINUTE);
  strictEqual(perMinute("k").wom, PER_MINUTE_WOM_KEYED);
  strictEqual(perMinute("k").rp, PER_MINUTE.rp);
  ok(PER_MINUTE_WOM_KEYED > PER_MINUTE.wom && PER_MINUTE_WOM_KEYED < 100);
  const r = rig({}, perMinute("k"));
  for (let i = 0; i < 100; i++) r.answers[WOM + "p" + i] = okJson({ i });
  for (let i = 0; i < 100; i++) await r.shelf.read(W("lookup/wom/p" + i));
  strictEqual(r.asked.length, PER_MINUTE_WOM_KEYED);
});

test("each source's minute: past it, a stale one or 503, never more requests", async () => {
  const r = rig();
  for (let i = 0; i < 40; i++) r.answers[WOM + "p" + i] = okJson({ i });
  const got = [];
  for (let i = 0; i < 40; i++) got.push((await r.shelf.read(W("lookup/wom/p" + i))).status);
  strictEqual(r.asked.length, PER_MINUTE.wom);
  strictEqual(got.filter((s) => s === 200).length, PER_MINUTE.wom);
  const busy = await r.shelf.read(W("lookup/wom/p39"));
  strictEqual(busy.status, 503);
  ok(+busy.headers["retry-after"] >= 1 && +busy.headers["retry-after"] <= 60);
  // (RuneProfile's own minute is untouched)
  r.answers[RP + "p0"] = okJson({});
  strictEqual((await r.shelf.read(W("lookup/rp/p0"))).status, 200);
  r.t += MIN;
  strictEqual((await r.shelf.read(W("lookup/wom/p39"))).status, 200);
});

test("a 429 pauses the source for its Retry-After", async () => {
  const r = rig({ [RP + "a"]: { status: 429, body: null, retryAfter: 120 }, [RP + "b"]: okJson({}) });
  const a = await r.shelf.read(W("lookup/rp/a"));
  strictEqual(a.status, 503);
  strictEqual(a.headers["retry-after"], "120");
  strictEqual((await r.shelf.read(W("lookup/rp/b"))).status, 503);
  strictEqual(r.asked.length, 1);
  r.t += 2 * MIN;
  strictEqual((await r.shelf.read(W("lookup/rp/b"))).status, 200);
});

test("the prune", () => {
  const now = 100 * DAY;
  deepStrictEqual(pruned([["wom:a", now - 15 * DAY], ["wom:b", now - 13 * DAY], ["item:1.png", now - 60 * DAY],
                          ["metric:x.png", now - 91 * DAY]], now).sort(), ["metric:x.png", "wom:a"]);
  const many: [string, number][] = Array.from({ length: 5003 }, (_, i) => ["rp:p" + i, now - i]);
  deepStrictEqual(pruned(many, now).sort(), ["rp:p5000", "rp:p5001", "rp:p5002"]);
});

for (const [name, f] of tests) {
  try { await f(); passed++; } catch (e) { console.error("FAIL " + name); throw e; }
}
console.log(passed + " passed");
