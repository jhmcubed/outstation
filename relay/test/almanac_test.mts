// The almanac's parsing and schedule (src/almanac.ts), against the fixtures relay-py/test_almanac.py reads too
// (tests/fixtures/almanac; expected.json, what both relays make of them). Node runs it as it is (it strips the types):
//
//   node test/almanac_test.mts   (or npm test)
//
// Nothing here asks a real host.
import { readFileSync } from "node:fs";
import { deepStrictEqual, ok, rejects, strictEqual, throws } from "node:assert";
import { Almanac, SourceError, USER_AGENT, freshKept, market, parseDay, parseMapping, parseNews, parseWorlds, pubDate, urls }
  from "../src/almanac.ts";

const F = new URL("../../tests/fixtures/almanac/", import.meta.url);
const bytes = (n: string) => new Uint8Array(readFileSync(new URL(n, F)));
const text = (n: string) => readFileSync(new URL(n, F), "utf-8");
const expected = JSON.parse(text("expected.json"));
const json = (v: unknown) => JSON.parse(JSON.stringify(v));
let passed = 0;
const tests: [string, () => void | Promise<void>][] = [];
const test = (name: string, f: () => void | Promise<void>) => tests.push([name, f]);

test("the news feed", () => deepStrictEqual(json(parseNews(bytes("news.rss"))), expected.news));
test("the real feed and world list", () => {
  // (Jagex's own, 11/09/2026, cut down: tests/fixtures/almanac/trim_real.py)
  deepStrictEqual(json(parseNews(bytes("news-real.rss"))), expected.news_real);
  deepStrictEqual(json(parseWorlds(bytes("slr-real.ws"), expected.worlds_at)), expected.worlds_real);
  deepStrictEqual(expected.worlds_real.seasons, []); // (the permanent deadman world isn't a season)
});
test("the feed's own encoding", () => {
  const latin = new Uint8Array([...new TextEncoder().encode('<?xml version="1.0" encoding="ISO-8859-1"?><rss><channel><item><title>Caf'),
    0xe9, 0x20, 0x93, 0x77, 0x94, ...new TextEncoder().encode("</title></item></channel></rss>")]);
  strictEqual(parseNews(latin)[0].title, "Caf\u00e9 \u201cw\u201d");
  strictEqual(parseNews(new TextEncoder().encode('<?xml version="1.0" encoding="UTF-8"?><rss><channel><item><title>Café</title></item></channel></rss>'))[0].title, "Caf\u00e9");
});
test("a feed with a DOCTYPE, or no feed", () => {
  throws(() => parseNews('<!DOCTYPE rss [<!ENTITY a "b">]><rss><channel></channel></rss>'), SourceError);
  throws(() => parseNews("<html>not a feed</html>"), SourceError);
  deepStrictEqual(parseNews("<rss><channel></channel></rss>"), []);
});
test("RSS dates", () => {
  strictEqual(pubDate("Wed, 04 Nov 2026 11:30:00 GMT"), Date.UTC(2026, 10, 4, 11, 30));
  strictEqual(pubDate("Mon, 02 Nov 2026 15:00:00 +0100"), Date.UTC(2026, 10, 2, 14, 0));
  strictEqual(pubDate("2 Nov 2026 15:00 -0230"), Date.UTC(2026, 10, 2, 17, 30));
  strictEqual(pubDate("Mon, 02 Nov 2026"), null);
  strictEqual(pubDate("Mon, 02 Foo 2026 15:00:00 GMT"), null);
});
test("the world list", () => deepStrictEqual(json(parseWorlds(bytes("slr.ws"), expected.worlds_at)), expected.worlds));
test("a world list cut short, or wrong", () => {
  const b = bytes("slr.ws");
  for (const n of [0, 5, 40, b.length - 10]) throws(() => parseWorlds(b.slice(0, n), 0), SourceError);
  const zero = new Uint8Array([0, 0, 0, 2, 0, 0]);
  throws(() => parseWorlds(zero, 0), SourceError);
  const long = b.slice();
  long[3] = 0xff; // (a length past its end)
  throws(() => parseWorlds(long, 0), SourceError);
});
test("the names", () => deepStrictEqual(parseMapping(text("mapping.json")), expected.names));
test("the market", () => {
  const names = parseMapping(text("mapping.json"));
  const latest = parseDay(text("24h-latest.json")), before = parseDay(text("24h-before.json")), next = parseDay(text("24h-next.json"));
  deepStrictEqual(json(market(latest, before, names)), expected.market);
  deepStrictEqual(json(market(next, latest, names)), expected.market_next);
  throws(() => parseDay("[]"), SourceError);
  throws(() => parseDay('{"data": {}}'), SourceError);
  throws(() => parseMapping("[]"), SourceError);
});
test("the user agent names the project and no email address", () => {
  ok(USER_AGENT.includes("https://github.com/jhmcubed/outstation"));
  ok(!USER_AGENT.includes("@"));
});
test("a base address for tests keeps each path", () => {
  deepStrictEqual(urls("http://127.0.0.1:9/"), { news: "http://127.0.0.1:9/m=news/latest_news.rss?oldschool=true",
    worlds: "http://127.0.0.1:9/slr.ws?order=LPWM", day: "http://127.0.0.1:9/api/v2/osrs/24h",
    mapping: "http://127.0.0.1:9/api/v2/osrs/mapping" });
});

// (a day of the schedule against the fixtures, on a clock of its own: the requests it makes)
function fake(start: number) {
  const T = 1794009600, asked: string[] = [];
  let now = start, publishedNext = Infinity, fail = new Set<string>();
  const serve = async (url: string): Promise<Uint8Array> => {
    asked.push(url);
    const path = url.replace("http://up", "");
    for (const f of fail) if (path.startsWith(f)) throw new SourceError("HTTP 500");
    if (path.startsWith("/m=news/")) return bytes("news.rss");
    if (path.startsWith("/slr.ws")) return bytes("slr.ws");
    if (path === "/api/v2/osrs/mapping") return bytes("mapping.json");
    if (path === "/api/v2/osrs/24h") return bytes(now >= publishedNext ? "24h-next.json" : "24h-latest.json");
    if (path === "/api/v2/osrs/24h?timestamp=" + (T - 86400)) return bytes("24h-before.json");
    throw new SourceError("HTTP 404");
  };
  const a = new Almanac(freshKept(), null, null, {}, serve, urls("http://up"), () => now);
  return { a, asked, set now(v: number) { now = v; }, get now() { return now; },
           publish(at: number) { publishedNext = at; }, fail(p: string) { fail.add(p); }, heal() { fail = new Set(); } };
}
test("a day's requests: within the budget", async () => {
  const start = Date.UTC(2026, 10, 9, 5, 0), f = fake(start);
  f.publish(Date.UTC(2026, 10, 10, 0, 5)); // (the next day's /24h is out at 00:05)
  for (let t = start; t < start + 86_400_000; t += 60_000) { f.now = t; await f.a.refresh(); }
  const count = (p: string) => f.asked.filter((u) => u.startsWith("http://up" + p)).length;
  strictEqual(count("/slr.ws"), 288);
  strictEqual(count("/m=news/"), 72);
  strictEqual(count("/api/v2/osrs/mapping"), 1);
  strictEqual(count("/api/v2/osrs/24h?timestamp="), 1);
  strictEqual(count("/api/v2/osrs/24h"), 3); // (the day, the day before, the next day at 00:15)
  strictEqual(f.a.kept.day, (1794009600 + 86400) * 1000);
  deepStrictEqual(json(f.a.kept.market), expected.market_next);
});
test("a source that fails keeps its last good data and backs off", async () => {
  const start = Date.UTC(2026, 10, 9, 5, 0), f = fake(start);
  await f.a.refresh();
  const had = json(f.a.read().updates);
  f.fail("/m=news/");
  const waits: number[] = [];
  for (let k = 0; k < 5; k++) {
    f.now = f.a.kept.state.updates.next;
    await f.a.refresh();
    waits.push((f.a.kept.state.updates.next - f.now) / 60_000);
  }
  deepStrictEqual(waits, [20, 40, 80, 120, 120]);
  const r = f.a.read();
  deepStrictEqual(json(r.updates), had);
  strictEqual(r.sources.updates.ok, false);
  strictEqual(r.sources.updates.at, start);
  ok(r.sources.updates.age_s! > 0);
  f.heal();
  f.now = f.a.kept.state.updates.next;
  await f.a.refresh();
  strictEqual(f.a.kept.state.updates.fails, 0);
  strictEqual(f.a.read().sources.updates.ok, true);
});
test("reads waiting on a cold start share one refresh", async () => {
  const f = fake(Date.UTC(2026, 10, 9, 5, 0));
  ok(f.a.cold());
  await Promise.all([f.a.refresh(), f.a.refresh(), f.a.refresh()]);
  strictEqual(f.asked.filter((u) => u.includes("/slr.ws")).length, 1);
  ok(!f.a.cold());
});
test("names that come late reach the ticker", async () => {
  const f = fake(Date.UTC(2026, 10, 9, 5, 0));
  f.fail("/api/v2/osrs/mapping");
  await f.a.refresh();
  strictEqual(f.a.kept.market![0].item, "item 561");
  f.heal();
  await f.a.refresh(true);
  deepStrictEqual(json(f.a.kept.market), expected.market);
});
test("a forced refresh asks every source again", async () => {
  const f = fake(Date.UTC(2026, 10, 9, 5, 0));
  await f.a.refresh();
  const n = f.asked.length;
  await f.a.refresh();
  strictEqual(f.asked.length, n);
  await f.a.refresh(true);
  // (each source once, and the day's /24h unchanged: the day before isn't asked again)
  deepStrictEqual(f.asked.slice(n).map((u) => u.replace("http://up", "")), ["/slr.ws?order=LPWM",
    "/m=news/latest_news.rss?oldschool=true", "/api/v2/osrs/mapping", "/api/v2/osrs/24h"]);
});

for (const [name, f] of tests) {
  try { await f(); passed++; } catch (e) { console.error("FAIL " + name + "\n", e); process.exitCode = 1; }
}
console.log(passed + " of " + tests.length + " passed");
