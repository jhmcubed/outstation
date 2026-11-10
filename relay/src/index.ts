// The portal's relay on Cloudflare: a Worker in front, a Durable Object per channel behind it (channel.ts).
//
//   ws(s)://<relay>/transmit/<channel>?key=<key>&code=<watch code>     the capture
//   ws(s)://<relay>/sound/<channel>?key=<key>                          the sound
//   ws(s)://<relay>/desk/<channel>?key=<key>&name=<name in the chat>   the chat window
//   ws(s)://<relay>/watch/<channel>?code=<watch code>                  a viewer (Outside)
//   ws(s)://<relay>/game/<channel>?key=<key>&v=<its version>           the Outside plugin: the player's game state (game.ts)
//   GET <relay>/game/<channel>?code=<watch code>                       the game state kept, and whether the plugin's connected
//   POST <relay>/dink/<channel>?key=<key>                              Dink, as to a Discord webhook (a fallback)
//   /clips/<channel>...                                                 clips on the player's Outside page (clips.ts)
//
// Channels are handed out by the relay's owner (Authorization: Bearer <ADMIN_TOKEN>, a Worker secret):
//   POST   /admin/channels/<channel>    a new key for it: {"channel", "key", "code"} (code: the attach code)
//   DELETE /admin/channels/<channel>    taken back: its transmitter cut off, its key refused
//   GET    /admin/channels              the channels handed out (live, title, client: its last transmitter's version;
//                                       by: the channel whose invite it came from, null for the keeper's own)
// The attach code (the station key) is "outstation1:" ("portal1:" before 11/06/2026) and base64url JSON {"relay", "channel", "key"}: the client takes it in one paste.
//
// Invites (11/07/2026, Jace: "each of my friends being able to pass out a few keys so that way the platform propagates
// easier", "people earning more invites through streaming"): an outstation hands out channels of its own, as the keeper
// does, three to begin with and one more every 10 hours on air (channel.ts, invites):
//   GET    /invites/<channel>?key=<key>                       {"left", "earned", "used", "hours", "next_in_s"}
//   POST   /invites/<channel>  {"key", "player"}              a new channel for that player: {"channel", "key", "code", "left"}
// The new channel's row in the Directory says whose invite it came from ("by"), in GET /admin/channels and in the open
// outstations' list (GET /channels); the inviter's channel keeps the names it's invited. Taking a channel back takes
// nothing back from the channels it invited, or gives its inviter the invite back. Till its key is first used (an invite
// not taken up yet, 11/08/2026) the open outstations' list leaves it out and counts it under its inviter ("pending").
//
// Asking for a portal (11/06/2026), so nobody has to email the keeper for a code:
//   POST   /requests/<channel>  {"player", "note", "pickup"}   a friend's client asks for that player's channel
//   GET    /requests/<channel>?pickup=<secret>                waiting / given (the attach code, once) / turned down
//   DELETE /requests/<channel>?pickup=<secret>                the asker takes it back
//   GET    /admin/requests                                    the keeper's list of those waiting
//   POST   /admin/requests/<channel>                          handed out: the code waits for the asker's client
//   DELETE /admin/requests/<channel>                          turned down
// Only the pickup's SHA-256 is kept; the attach code is kept only until the asker's client collects it.
//
// Anomaly reports (11/06/2026, Jace: "build both routes"): a friend's outstation sends a report of something gone wrong
// (their words, and the station log with its keys, codes and webhook addresses taken out by the client), only when
// they press "send to the listening post"; the keeper reads them in his station's drawer.
//   POST   /reports  {"kind", "channel", "key", "name", "note", "log", "version", "windows"}   kind: "anomaly" (the
//                    default) or "requisition" (11/06/2026, Jace: "requests can also come straight to my outstation
//                    client": something wanted, a feature request, with no log)   (channel and key optional:
//                    with them the report says it's that channel's, checked; without, the name is as they typed it)
//   GET    /admin/reports              the reports, newest first
//   DELETE /admin/reports/<id>         one read and done with
// At most REPORTS_KEPT (50) and REPORT_DAYS (30) days each; REPORTS_AN_HOUR (5) from one address; a log at most
// REPORT_LOG (64 KB), a note at most 1,000 characters.
//
// A notice from the keeper to every portal (11/06/2026): one line every friend's client shows in its chat.
//   GET    /admin/notice              the notice now ({"notice": {"text", "at"}} or {"notice": null})
//   PUT    /admin/notice  {"text"}    a new one, sent at once to every desk connected and to each that connects
//   DELETE /admin/notice              taken down
//
// The aether (11/07/2026, Jace: "a global chat… reminds me of IRC and AOL Instant Messenger. i want this to feel like a
// real network people are joining"): one room for every outstation and visitor, one Durable Object (aether.ts):
//   ws(s)://<relay>/aether?name=<name>&device=<random id>          a visitor (its name not an outstation's: the Directory's)
//   ws(s)://<relay>/aether?channel=<channel>&key=<key>&v=<version>  an outstation, its key checked by its channel
//   GET    /aether                    the last 30 lines and how many are here, for a page before it joins
//   GET    /admin/aether              the keeper's: every line kept with who wrote it, and the bans
//   POST   /admin/aether/unsay {"id"}  /ban {"id"}  /unban {"who"}
//
// The Array's events (11/08/2026, Jace: each outstation on outside.wiki's front page should look alive, its icon popping
// "+1"; array.ts): a kill, a level, a pet, a big drop, a log slot or a clue, from each open outstation's field reports
// while it's live, kept in the Directory's storage (the last 500, since 11/10/2026; one channel adds 10 a minute at most):
//   GET    /array/events?since=<cursor>   {"events": [{"id", "at", "channel", "kind", ...}], "next": <cursor>}
//   POST   /admin/array  {"channel", "events": [{"kind", ..., "at"}]}   the keeper's: a real event the feed lost, put back
//
// Settling (11/08/2026 to 11/10/2026, gone): an outstation once chose where it stood on outside.wiki's globe. The globe
// now shows only real positions on the land, and every other outstation at the receiver, so homes have no use (Jace's
// decision, 11/10/2026). An app built between the two still has the button: its POST /settle, and anything else on
// /settle/<channel> or /home/<channel>, is answered 410 with a line it shows (SETTLING_GONE).
//
// Shooting stars (11/08/2026; stars.ts): the crashed stars the plugins have found, kept in the Directory's storage
// (11/10/2026; two hours each) with the outstation that found each first (11/09/2026), for the globe:
//   GET    /stars    {"stars": [{"world", "x", "y", "site", "tier", "found", "seen", "by", "reports"}]}
//
// The almanac (11/09/2026; almanac.ts): Jagex's news, the world list (its seasons) and the Wiki's prices (the day's
// movers), for the globe, each fetched by the relay alone on its own schedule and kept in the Directory's storage. The
// Cron Trigger (wrangler.jsonc, every 5 minutes: scheduled, below) asks it to refresh whatever's due:
//   GET    /almanac          {"at", "updates", "worlds", "market", "sources"}
//   GET    /admin/almanac    each source's schedule and last error; POST: every source asked now

import { DurableObject } from "cloudflare:workers";
import { Channel, PLAYER, type Env, type Listing } from "./channel";
import { Aether } from "./aether";
import { type ArrayEvent, ArrayFeed, type Lost, arrayAnswer, arrayBackfill, arraySince } from "./array";
import { type StarReport, WHERE_FRESH_MS } from "./game";
import { StarList } from "./stars";
import { fileKey, serveFile, ID } from "./clips";
import { attachCode, oneLine, pyjson, relayOf } from "./text";
import { Almanac, type Day, type Kept, freshKept, httpFetch, urls } from "./almanac";
import { LISTING_CHECK_MS, ListedRows, keptRow } from "./listed";

export { Channel, Aether };

const ROLES = new Set(["transmit", "watch", "desk", "sound", "game"]);
const NAME = /^[A-Za-z0-9_.~-]{1,64}$/;
/** What /settle and /home answer now settling's gone (11/10/2026): the old app shows a 410's "error" as it is. */
const SETTLING_GONE = "settling has gone: the globe now shows each outstation where it really is, or at the receiver";
const MAX_POST = 16 * 1024 * 1024;

const ASKS_AN_HOUR = 3; // requests from one address in an hour
const WAITING_MOST = 50; // requests waiting at once, everyone's
const WAIT_DAYS = 14, PICKUP_DAYS = 7; // a request not answered in this long goes; an answer not collected, too
const DAY = 86_400_000;
const REPORTS_KEPT = 50, REPORT_DAYS = 30, REPORTS_AN_HOUR = 5, REPORT_LOG = 64 * 1024, REPORT_NOTE = 1000;

interface Report {
  id: string;
  kind: "anomaly" | "requisition"; // something gone wrong, or something wanted (a feature request)
  at: number;
  channel: string; // checked against its key ("" when none was given, or it didn't match)
  name: string; // as they typed it (or the channel)
  note: string;
  log: string;
  version: string;
  windows: string;
}

interface Ask {
  channel: string;
  player: string; // the name as they typed it
  note: string; // how the keeper knows them (their words, one line)
  at: number;
  pickup: string; // the SHA-256 of the secret only the asker's client holds
  state: "waiting" | "given" | "turned down";
  code?: string; // the attach code, until it's collected
  decided?: number;
}

async function sha256(text: string): Promise<string> {
  const d = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text));
  return [...new Uint8Array(d)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

// the channels handed out, for the owner's list; and the requests for one
export class Directory extends DurableObject<Env> {
  asks = new Map<string, number[]>(); // an address -> when it asked (in memory: a sleep forgets, which only loosens it)
  reportsFrom = new Map<string, number[]>(); // an address -> when it sent a report (the same)
  // the channels handed out (their "c:" rows), each with whose invite it came from (null: the keeper's own), till one's
  // handed out or taken back
  given: Map<string, string | null> | null = null;
  // the open portals (GET /channels, 11/07/2026): each channel's row as it last told it (Channel.tellListing), or as it
  // answered when asked. A read used to ask every channel handed out for its status, waking each: a request to every
  // channel each time the outstations page (every 20 s while open) or any friend's client (every minute) read the list.
  // In storage ("listed") since 11/10/2026, read once a wake (listedRows; listed.ts): in memory only, the first read
  // after each sleep asked every channel again, and the Directory sleeps about 10 s after its last request. Following's
  // "where" and the "activity" are never written there, only held in memory with the row (SAFETY.md).
  listed: Promise<ListedRows> | null = null;
  // how many times each channel's been asked for its row since this wake (for the tests' GET /test/listed only)
  asked = new Map<string, number>();
  // the Array's events (11/08/2026; array.ts): each told by its channel (Channel.tellArray) while it's live and listed.
  // In storage ("array") since 11/10/2026, read once a wake (arrayFeed): the Directory holds no sockets or timers, so
  // Cloudflare puts it to sleep about 10 seconds after its last request, and in memory only the feed lasted that long.
  array: Promise<ArrayFeed> | null = null;
  // shooting stars (11/08/2026; stars.ts): each told by the channel whose plugin found it (Channel.takeStar), kept with
  // its first listed finder. In storage ("stars") since 11/10/2026, the same way (starList).
  stars: Promise<StarList> | null = null;
  // the almanac (11/09/2026; almanac.ts): what's kept is in storage ("alm:kept", the two days' prices "alm:latest" and
  // "alm:before", the names "alm:names"), read once a wake; refreshed by the Cron Trigger (POST /almanac/tick)
  alm: Promise<Almanac> | null = null;

  /** The open portals' rows, read from storage once a wake (a failed read is tried again by the next request). */
  listedRows(): Promise<ListedRows> {
    this.listed ??= this.ctx.storage.get("listed").then((saved) => ListedRows.from(saved), (e) => { this.listed = null; throw e; });
    return this.listed;
  }

  /** The rows written to storage if they've changed (ListedRows.changed): one row. Nothing's waited on between the
   *  change and this, so each write is the whole list as it stands; saved() leaves "where" and "activity" out. */
  saveListed(rows: ListedRows): Promise<void> | undefined {
    if (!rows.changed(this.listingCheck())) return;
    return this.ctx.storage.put("listed", rows.saved());
  }

  /** How old a live row may be before it's asked for again (LISTING_CHECK_MS; the tests' LISTING_CHECK_SECONDS). */
  listingCheck(): number {
    return (parseFloat(this.env.LISTING_CHECK_SECONDS || "") * 1000) || LISTING_CHECK_MS;
  }

  /** The Array's feed, read from storage once a wake (a failed read is tried again by the next request). */
  arrayFeed(): Promise<ArrayFeed> {
    this.array ??= this.ctx.storage.get("array").then((saved) => ArrayFeed.from(saved), (e) => { this.array = null; throw e; });
    return this.array;
  }

  /** The shooting stars, read from storage once a wake (the same). */
  starList(): Promise<StarList> {
    this.stars ??= this.ctx.storage.get("stars").then((saved) => StarList.from(saved), (e) => { this.stars = null; throw e; });
    return this.stars;
  }

  /** The stars written to storage if they've changed (a report taken, or one past its time gone): one row. Nothing's
   *  waited on between the change and this, so each write is the whole list as it stands, in the order they came. */
  saveStars(list: StarList): Promise<void> | undefined {
    if (!list.changed) return;
    list.changed = false;
    return this.ctx.storage.put("stars", list.saved());
  }

  almanac(): Promise<Almanac> {
    this.alm ??= (async () => {
      const got = await this.ctx.storage.get<unknown>(["alm:kept", "alm:latest", "alm:before", "alm:names"]);
      return new Almanac((got.get("alm:kept") as Kept | undefined) ?? freshKept(), (got.get("alm:latest") as Day | undefined) ?? null,
                         (got.get("alm:before") as Day | undefined) ?? null, (got.get("alm:names") as Record<string, string> | undefined) ?? {},
                         httpFetch, urls(this.env.ALMANAC_UPSTREAM));
    })();
    return this.alm;
  }

  /** The almanac refreshed (every source, with force; else what's due), and what changed written. */
  async almanacRefresh(force: boolean): Promise<Almanac> {
    const a = await this.almanac();
    await a.refresh(force);
    const out: Record<string, unknown> = {};
    for (const k of a.dirty) out["alm:" + k] = k === "kept" ? a.kept : a[k];
    a.dirty.clear();
    if (Object.keys(out).length) await this.ctx.storage.put(out);
    return a;
  }

  async fetch(request: Request): Promise<Response> {
    const url = new URL(request.url), name = url.searchParams.get("name") || "";
    if (url.pathname === "/channels") return this.openPortals();
    if (url.pathname === "/almanac") {
      // (a read: never a source asked, except while it's cold, the one refresh shared by every read meanwhile)
      let a = await this.almanac();
      if (a.cold()) a = await this.almanacRefresh(false);
      return almanacAnswer(a.read());
    }
    if (url.pathname === "/almanac/tick") {
      // (and the stars past their two hours taken out of storage, though nobody's read or told of one since)
      const list = await this.starList();
      list.expire(Date.now());
      await this.saveStars(list);
      return Response.json((await this.almanacRefresh(false)).status());
    }
    if (url.pathname === "/almanac/force") return Response.json((await this.almanacRefresh(true)).status());
    if (url.pathname === "/almanac/status") return Response.json((await this.almanac()).status());
    if (url.pathname === "/array") {
      if (request.method === "PUT") {
        const { channel, events } = (await request.json()) as { channel: string; events: ArrayEvent[] };
        const feed = await this.arrayFeed();
        // (the whole feed, one row, written with each report that adds to it: a report's several events are one write.
        // Nothing's waited on between the add and the put, so two reports can't race: each write has every event before
        // it, and the answer waits for the write.)
        if (feed.add(channel, events).length) await this.ctx.storage.put("array", feed.saved());
        return new Response(null, { status: 204 });
      }
      return arrayAnswer((await this.arrayFeed()).read(arraySince(url.searchParams.get("since")) ?? undefined));
    }
    if (url.pathname === "/array/backfill" && request.method === "PUT") {
      // the keeper's (admin(), its events already checked): only for a channel handed out
      const { channel, events } = (await request.json()) as { channel: string; events: Lost[] };
      if (!(await this.givenNames()).has(channel)) return Response.json({ error: "no outstation called that here" }, { status: 404 });
      const feed = await this.arrayFeed(), got = feed.backfill(channel, events);
      if ("error" in got) return Response.json(got, { status: 400 });
      await this.ctx.storage.put("array", feed.saved());
      return Response.json(got);
    }
    if (url.pathname === "/stars") {
      const list = await this.starList();
      if (request.method === "PUT") {
        const { channel, star, listed } = (await request.json()) as { channel: string; star: StarReport; listed: boolean };
        list.add(channel, star, !!listed);
        await this.saveStars(list);
        return new Response(null, { status: 204 });
      }
      const read = list.read();
      await this.saveStars(list); // (one gone past its time meanwhile: out of storage too)
      return arrayAnswer(read);
    }
    if (url.pathname === "/forget" && request.method === "POST" && this.env.TEST_FORGET === "1") {
      // (tests only, under `wrangler dev` with TEST_FORGET: this object put to sleep as Cloudflare does it, everything in
      // its memory gone, so a test can see what its storage brings back; never set on Cloudflare)
      this.ctx.abort("forgotten, for a test");
      return new Response(null, { status: 204 }); // (not reached: abort throws)
    }
    if (url.pathname === "/listed" && request.method === "PUT") {
      const row = (await request.json()) as Listing, rows = await this.listedRows();
      rows.heard(row);
      await this.saveListed(rows); // (only if what's stored of it changed: a new "where" alone writes nothing)
      return new Response(null, { status: 204 });
    }
    if (url.pathname === "/test-listed" && this.env.TEST_FORGET === "1") {
      // (tests only, with TEST_FORGET: what storage holds of a channel's row and how often it's been asked since this
      // wake; a POST's fields put into its row as if the channel had told them (a telling lost, for the staleness
      // test), its "seen" left as it was. Never set on Cloudflare)
      const rows = await this.listedRows();
      if (request.method === "POST") {
        const e = rows.rows.get(name);
        if (!e) return new Response(null, { status: 404 });
        e.row = { ...e.row, ...keptRow({ ...((await request.json()) as Listing), channel: name }) };
        await this.ctx.storage.put("listed", rows.saved());
      }
      const saved = (await this.ctx.storage.get<{ rows: { row: Listing; seen: number }[] }>("listed"))?.rows ?? [];
      return Response.json({ stored: saved.find((r) => r.row.channel === name) ?? null, asked: this.asked.get(name) ?? 0 });
    }
    if (url.pathname === "/requests") return this.requests(request, url);
    if (url.pathname === "/reports") return this.reports(request, url);
    if (url.pathname === "/taken") {
      // the aether's (11/07/2026, aether.ts): whether a visitor's name, in a channel's form (lower case, no spaces), is
      // a channel handed out's, whatever its case
      const form = url.searchParams.get("name") || "";
      return Response.json({ taken: [...(await this.givenNames()).keys()].some((n) => n.toLowerCase() === form) });
    }
    if (url.pathname === "/notice") {
      if (request.method === "PUT") {
        const { text } = (await request.json()) as { text: string };
        await this.ctx.storage.put("notice", { text, at: Date.now() });
      } else if (request.method === "DELETE") {
        await this.ctx.storage.delete("notice");
      }
      return Response.json({ notice: (await this.ctx.storage.get("notice")) || null });
    }
    // a channel handed out: its row is {since, by}. "by" (11/07/2026) is the channel whose invite it came from, and
    // null for the keeper's own; a new key from the keeper keeps it. An invite (?by=) takes only a name nobody has:
    // the read and the write here can't be split by another request (nothing but storage is waited on between them),
    // so two invites for one name, or an invite and the keeper, never both get it.
    const k = "c:" + name, by = url.searchParams.get("by");
    if (request.method === "PUT") {
      const had = await this.ctx.storage.get<{ since: number; by?: string | null }>(k);
      if (by !== null && had) return Response.json({ error: "that name has an outstation already" }, { status: 409 });
      await this.ctx.storage.put(k, { since: Date.now(), by: by ?? had?.by ?? null });
    } else if (request.method === "DELETE") await this.ctx.storage.delete(k);
    if (request.method === "PUT" || request.method === "DELETE") {
      this.given = null;
      const rows = await this.listedRows(); // (a new key: whatever it said before, it's asked again; taken back: gone)
      if (rows.drop(name)) await this.saveListed(rows);
      if (request.method === "PUT" && by !== null) return new Response(null, { status: 204 }); // (an invite's: no list)
    }
    const all = await this.ctx.storage.list({ prefix: "c:" });
    return Response.json([...all.entries()].map(([k, v]) => ({ channel: k.slice(2), by: null, ...(v as object) })));
  }

  /** The channels handed out, each with whose invite it came from: read from storage once, till one's handed out or
   *  taken back. */
  async givenNames(): Promise<Map<string, string | null>> {
    if (!this.given) {
      const rows = await this.ctx.storage.list<{ by?: string | null }>({ prefix: "c:" });
      this.given = new Map([...rows].map(([k, v]) => [k.slice(2), v.by ?? null]));
    }
    return this.given;
  }

  /** GET /channels: every channel handed out that has no watch code, live ones first. Only a channel whose row is
   *  missing or old, or that's live with the plugin and not heard since this wake, is asked (listed.ts's due).
   *  "pending" (11/08/2026, for the keys beside an inviter on outside.wiki's globe): the invites not taken up yet,
   *  counted under each inviter, [{"by", "n"}] sorted by "by". Never the invited names (the inviter typed them; the
   *  friends haven't agreed to anything), and only under an inviter that's on the list itself (no watch code), so
   *  nothing's said of a hidden outstation.
   *  "where" (11/08/2026, following; game.ts): the square the plugin last said, {"x", "y"} (and "world", with "and my
   *  world" on), only while the channel's live and on the list, and only if it came within WHERE_FRESH_MS; else null.
   *  "activity" (11/08/2026; game.ts): what they were doing, the same way. Nowhere else. */
  async openPortals(): Promise<Response> {
    const given = await this.givenNames(), names = [...given.keys()], now = Date.now(); // (one handed out meanwhile: in the next read)
    const listed = await this.listedRows();
    for (const n of [...listed.rows.keys()]) if (!given.has(n)) listed.drop(n); // (no longer handed out)
    await Promise.all(listed.due(names, now, this.listingCheck()).map(async (n) => {
      this.asked.set(n, (this.asked.get(n) ?? 0) + 1);
      try {
        const r = await this.env.CHANNEL.get(this.env.CHANNEL.idFromName(n))
          .fetch(new Request("https://channel/_listing", { headers: { "x-channel": n } }));
        listed.heard((await r.json()) as Listing);
      } catch { /* (left out this time, and asked again at the next read) */ }
    }));
    await this.saveListed(listed); // (what the asks changed, all of it in one write)
    // (an invite not taken up: only an invite's channel, which has an inviter, is ever pending)
    const waits = (r: Listing) => !!r.pending && (given.get(r.channel) ?? null) !== null;
    const rows = names.map((n) => listed.rows.get(n)?.row).filter((r): r is Listing => !!r && r.set);
    const fresh = (parseFloat(this.env.WHERE_FRESH_SECONDS || "") * 1000) || WHERE_FRESH_MS, at = Date.now();
    const whereOf = (r: Listing) => (r.live && r.where && at - (r.whereAt || 0) <= fresh ? { ...r.where } : null);
    const activityOf = (r: Listing) => (r.live && r.activity && at - (r.activityAt || 0) <= fresh ? { ...r.activity } : null);
    const open = rows.filter((r) => !r.code && !waits(r))
      .map((r) => ({ channel: r.channel, live: !!r.live, open: !!r.open, static: !!r.static, viewers: r.viewers || 0,
                     title: r.title || "", doing: r.doing || null, by: given.get(r.channel) ?? null, game: !!r.game,
                     where: whereOf(r), activity: activityOf(r) }));
    open.sort((a, b) => Number(b.live) - Number(a.live) || a.channel.localeCompare(b.channel));
    const shown = new Set(open.map((r) => r.channel)), counts = new Map<string, number>();
    for (const r of rows) {
      const by = given.get(r.channel) ?? null;
      if (waits(r) && by !== null && shown.has(by)) counts.set(by, (counts.get(by) || 0) + 1);
    }
    const pending = [...counts].map(([by, n]) => ({ by, n })).sort((a, b) => (a.by < b.by ? -1 : a.by > b.by ? 1 : 0));
    return new Response(JSON.stringify({ channels: open, pending }), { headers: { "content-type": "application/json", "access-control-allow-origin": "*", "cache-control": "no-cache" } });
  }

  // anomaly reports: "add" (a friend's outstation, its channel already checked by the Worker), "list" and "delete" (the
  // keeper, already let through by admin())
  async reports(request: Request, url: URL): Promise<Response> {
    const what = url.searchParams.get("what") || "", now = Date.now();
    const all = await this.ctx.storage.list<Report>({ prefix: "r:" });
    const live: Report[] = [];
    for (const [k, r] of all) {
      if (now - r.at > REPORT_DAYS * DAY) await this.ctx.storage.delete(k);
      else live.push(r);
    }
    live.sort((a, b) => b.at - a.at);
    if (what === "list") return Response.json(live);
    if (what === "delete") {
      await this.ctx.storage.delete("r:" + (url.searchParams.get("id") || ""));
      return new Response(null, { status: 204 });
    }
    if (what !== "add") return new Response(null, { status: 400 });
    const ip = request.headers.get("cf-connecting-ip") || "";
    const times = (this.reportsFrom.get(ip) || []).filter((t) => now - t < 3_600_000);
    if (times.length >= (parseInt(this.env.REPORTS_AN_HOUR || "") || REPORTS_AN_HOUR)) {
      return Response.json({ error: "too many reports from here: try again in an hour, or write to research@ghillie.wiki" }, { status: 429 });
    }
    const body = (await request.json().catch(() => null)) as Record<string, unknown> | null;
    if (!body) return Response.json({ error: "a report is JSON: name, note, log, version" }, { status: 400 });
    const text = (v: unknown, most: number) => (typeof v === "string" ? v.replace(/\u0000/g, "").slice(0, most) : "");
    const note = text(body.note, REPORT_NOTE).trim(), log = text(body.log, REPORT_LOG);
    if (!note && !log) return Response.json({ error: "say what happened, or send the station log" }, { status: 400 });
    times.push(now);
    this.reportsFrom.set(ip, times);
    const r: Report = { id: crypto.randomUUID().replace(/-/g, "").slice(0, 16), kind: body.kind === "requisition" ? "requisition" : "anomaly", at: now, channel: text(url.searchParams.get("checked"), 64),
                        name: oneLine(body.name, 40), note, log, version: oneLine(body.version, 20), windows: oneLine(body.windows, 60) };
    await this.ctx.storage.put("r:" + r.id, r);
    for (const old of live.slice(REPORTS_KEPT - 1)) await this.ctx.storage.delete("r:" + old.id);
    return Response.json({ id: r.id }, { status: 201 });
  }

  // what's asked, by whom: "ask" (a friend's client), "check" and "cancel" (with the pickup), "list", "give" and
  // "turn down" (the keeper, already let through by admin())
  async requests(request: Request, url: URL): Promise<Response> {
    const what = url.searchParams.get("what") || "", channel = url.searchParams.get("name") || "";
    const now = Date.now();
    const stale = (a: Ask) => now - a.at > WAIT_DAYS * DAY || (a.decided !== undefined && now - a.decided > PICKUP_DAYS * DAY);
    if (what === "list") {
      const all = await this.ctx.storage.list<Ask>({ prefix: "q:" });
      const rows = [];
      for (const [k, a] of all) {
        if (stale(a)) { await this.ctx.storage.delete(k); continue; }
        if (a.state !== "waiting") continue;
        const given = (await this.ctx.storage.get("c:" + a.channel)) !== undefined;
        rows.push({ channel: a.channel, player: a.player, note: a.note, at: a.at, given });
      }
      rows.sort((x, y) => x.at - y.at);
      return Response.json(rows);
    }
    const k = "q:" + channel;
    const a = await this.ctx.storage.get<Ask>(k);
    const live = a && !stale(a) ? a : undefined;
    if (what === "ask") {
      const body = (await request.json().catch(() => null)) as { player?: unknown; note?: unknown; pickup?: unknown } | null;
      const pickup = typeof body?.pickup === "string" ? body.pickup : "";
      if (pickup.length < 32 || pickup.length > 128) return Response.json({ error: "a pickup of 32 to 128 characters" }, { status: 400 });
      const hash = await sha256(pickup);
      if (live && live.pickup !== hash && live.state !== "turned down") {
        return Response.json({ error: "someone has already asked for this name: the keeper will answer them first" }, { status: 409 });
      }
      if (live && live.pickup === hash && live.state !== "waiting") {
        return Response.json({ state: live.state }); // (already answered: collected with a check, never reset)
      }
      if (!live || live.pickup !== hash) {
        const ip = request.headers.get("cf-connecting-ip") || "";
        const times = (this.asks.get(ip) || []).filter((t) => now - t < 3_600_000);
        if (times.length >= (parseInt(this.env.ASKS_AN_HOUR || "") || ASKS_AN_HOUR)) return Response.json({ error: "too many requests from here: try again in an hour" }, { status: 429 });
        const waiting = [...(await this.ctx.storage.list<Ask>({ prefix: "q:" })).values()].filter((x) => x.state === "waiting" && !stale(x)).length;
        if (waiting >= WAITING_MOST) return Response.json({ error: "too many requests waiting: try again in a few days" }, { status: 503 });
        times.push(now);
        this.asks.set(ip, times);
      }
      const ask: Ask = { channel, player: oneLine(body?.player, 12) || channel, note: oneLine(body?.note, 200), at: live && live.pickup === hash ? live.at : now,
                         pickup: hash, state: "waiting" };
      await this.ctx.storage.put(k, ask);
      return Response.json({ state: "waiting", given: (await this.ctx.storage.get("c:" + channel)) !== undefined }, { status: 201 });
    }
    if (what === "check" || what === "cancel") {
      const pickup = url.searchParams.get("pickup") || "";
      if (!live || pickup.length < 32 || live.pickup !== (await sha256(pickup))) return Response.json({ state: "none" }, { status: 404 });
      if (what === "cancel") { await this.ctx.storage.delete(k); return new Response(null, { status: 204 }); }
      if (live.state === "waiting") return Response.json({ state: "waiting" });
      await this.ctx.storage.delete(k); // an answer is collected once
      return Response.json(live.state === "given" ? { state: "given", code: live.code } : { state: "turned down" });
    }
    if (what === "give" || what === "turn down") {
      if (!live || live.state !== "waiting") return Response.json({ error: "nobody is waiting for that name" }, { status: 404 });
      live.state = what === "give" ? "given" : "turned down";
      live.decided = now;
      if (what === "give") live.code = url.searchParams.get("code") || "";
      await this.ctx.storage.put(k, live);
      return new Response(null, { status: 204 });
    }
    return new Response(null, { status: 400 });
  }
}


function sameText(a: string, b: string): boolean {
  if (a.length !== b.length) return false;
  let d = 0;
  for (let i = 0; i < a.length; i++) d |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return d === 0;
}

function channelStub(env: Env, name: string) {
  return env.CHANNEL.get(env.CHANNEL.idFromName(name));
}

function toChannel(request: Request, name: string, path: string): Request {
  const url = new URL(request.url);
  url.pathname = path;
  const headers = new Headers(request.headers);
  headers.set("x-channel", name);
  return new Request(url.toString(), { method: request.method, headers, body: request.body });
}

/** A channel handed out (a new key for it): {channel, key, code}. */
async function mint(request: Request, env: Env, name: string): Promise<{ channel: string; key: string; code: string }> {
  const dir = env.DIRECTORY.get(env.DIRECTORY.idFromName("directory"));
  const r = await channelStub(env, name).fetch(toChannel(new Request(request.url, { method: "POST" }), name, "/_mint"));
  const { key } = (await r.json()) as { key: string };
  await dir.fetch("https://dir/?name=" + encodeURIComponent(name), { method: "PUT" });
  return { channel: name, key, code: attachCode(relayOf(request.url), name, key) };
}

function dirRequests(env: Env, what: string, name: string, extra = ""): Request {
  return new Request("https://dir/requests?what=" + encodeURIComponent(what) + "&name=" + encodeURIComponent(name) + extra);
}

/** GET /almanac's answers: any page may read them (any origin), and a browser may keep one a minute. */
function almanacAnswer(body: unknown, status = 200): Response {
  const headers: Record<string, string> = { "access-control-allow-origin": "*", "cache-control": body === null ? "no-cache" : "public, max-age=60" };
  if (body !== null) headers["content-type"] = "application/json";
  return new Response(body === null ? null : pyjson(body), { status, headers });
}

async function admin(request: Request, env: Env, parts: string[]): Promise<Response> {
  const auth = request.headers.get("authorization") || "";
  if (!env.ADMIN_TOKEN || env.ADMIN_TOKEN.length < 16 || !sameText(auth, "Bearer " + env.ADMIN_TOKEN)) {
    return new Response("no", { status: 403 });
  }
  const dir = env.DIRECTORY.get(env.DIRECTORY.idFromName("directory"));
  if (parts[1] === "almanac" && parts.length === 2) {
    // the almanac's keeper: each source's schedule and last error; POST: every source asked now, whatever its schedule
    if (request.method === "GET") return dir.fetch("https://dir/almanac/status");
    if (request.method === "POST") return dir.fetch("https://dir/almanac/force", { method: "POST" });
    return new Response(null, { status: 405 });
  }
  if (parts[1] === "array" && parts.length === 2) {
    // the keeper's backfill (11/10/2026; array.ts's arrayBackfill): a real event the feed lost, put back in its place.
    // Only for a channel handed out and on the open outstations' list (no watch code, not an invite waiting), as only
    // such a channel's reports ever make events.
    if (request.method !== "POST") return new Response(null, { status: 405 });
    const raw = await request.text();
    if (raw.length > 16 * 1024) return Response.json({ error: "16 KB at most" }, { status: 413 });
    let body: unknown = null;
    try { body = JSON.parse(raw); } catch { /* (below) */ }
    const channel = (body && typeof body === "object" && (body as { channel?: unknown }).channel) || "";
    if (typeof channel !== "string" || !NAME.test(channel)) return Response.json({ error: "channel: which outstation" }, { status: 400 });
    const got = arrayBackfill(body);
    if ("error" in got) return Response.json(got, { status: 400 });
    const row = (await (await channelStub(env, channel).fetch(toChannel(new Request(request.url), channel, "/_listing"))).json()) as Listing;
    if (!row.set) return Response.json({ error: "no outstation called that here" }, { status: 404 });
    if (row.code || row.pending) return Response.json({ error: "that outstation isn't on the open list: its events never go on the feed" }, { status: 409 });
    return dir.fetch(new Request("https://dir/array/backfill", { method: "PUT", body: JSON.stringify({ channel, events: got.events }) }));
  }
  if (parts[1] === "aether") {
    // the aether's keeper (aether.ts): the lines with who wrote each, and taking one back or banning its writer
    const aether = env.AETHER.get(env.AETHER.idFromName("aether"));
    if (parts.length === 2) return request.method === "GET" ? aether.fetch("https://aether/admin") : new Response(null, { status: 405 });
    if (parts.length !== 3 || !["unsay", "ban", "unban"].includes(parts[2])) return new Response(null, { status: 404 });
    if (request.method !== "POST") return new Response(null, { status: 405 });
    const raw = await request.text();
    if (raw.length > 4096) return new Response(null, { status: 413 });
    return aether.fetch(new Request("https://aether/admin/" + parts[2], { method: "POST", body: raw }));
  }
  if (parts[1] === "clips") {
    // a channel's clips, whatever its watch code; and any one taken down
    const name = parts[2] || "";
    if (!NAME.test(name) || parts.length > 4) return new Response("which channel?", { status: 400 });
    const url = new URL(request.url);
    if (parts.length === 4) url.searchParams.set("id", parts[3]);
    if (request.method !== "GET" && !(request.method === "DELETE" && parts.length === 4)) return new Response(null, { status: 405 });
    return channelStub(env, name).fetch(toChannel(new Request(url.toString(), request), name, "/_clips_admin"));
  }
  if (parts[1] === "notice" && parts.length === 2) {
    if (request.method === "GET") return dir.fetch("https://dir/notice");
    if (request.method !== "PUT" && request.method !== "DELETE") return new Response(null, { status: 405 });
    let text = "";
    if (request.method === "PUT") {
      const body = (await request.json().catch(() => null)) as { text?: unknown } | null;
      text = oneLine(body?.text, 300);
      if (!text) return Response.json({ error: "a notice needs some words (DELETE takes one down)" }, { status: 400 });
    }
    const r = await dir.fetch(new Request("https://dir/notice", request.method === "PUT"
      ? { method: "PUT", body: JSON.stringify({ text }) } : { method: "DELETE" }));
    const now = (await r.json()) as { notice: { text: string; at: number } | null };
    // to every channel handed out, so each desk connected now hears it (one that connects later asks for it)
    const names = ((await (await dir.fetch("https://dir/")).json()) as { channel: string }[]).map((c) => c.channel);
    await Promise.all(names.map((n) => channelStub(env, n).fetch(toChannel(
      new Request(request.url, { method: "POST", body: JSON.stringify(now) }), n, "/_notice")).catch(() => null)));
    return Response.json(now);
  }
  if (parts[1] === "reports") {
    if (parts.length === 2 && request.method === "GET") return dir.fetch("https://dir/reports?what=list");
    if (parts.length === 3 && request.method === "DELETE") return dir.fetch("https://dir/reports?what=delete&id=" + encodeURIComponent(parts[2]));
    return new Response(null, { status: 405 });
  }
  if (parts[1] === "requests") {
    if (parts.length === 2 && request.method === "GET") return dir.fetch(dirRequests(env, "list", ""));
    const name = parts[2] || "";
    if (parts.length !== 3 || !PLAYER.test(name)) return new Response("which name?", { status: 400 });
    if (request.method === "POST") {
      // handed out: only if someone's waiting for it, and then the code waits for their client
      const waiting = ((await (await dir.fetch(dirRequests(env, "list", ""))).json()) as { channel: string }[]).some((r) => r.channel === name);
      if (!waiting) return Response.json({ error: "nobody is waiting for that name" }, { status: 404 });
      const got = await mint(request, env, name);
      await dir.fetch(dirRequests(env, "give", name, "&code=" + encodeURIComponent(got.code)));
      return Response.json(got);
    }
    if (request.method === "DELETE") return dir.fetch(dirRequests(env, "turn down", name));
    return new Response(null, { status: 405 });
  }
  if (parts.length === 2 && request.method === "GET") {
    // the channels handed out, each with what its channel says now: live, its title, the version of the client
    // that last transmitted on it (so the keeper can see who'd be turned away by a higher MIN_CLIENT), and its seconds
    // on air this month, UTC (the keeper's bill)
    const given = (await (await dir.fetch("https://dir/")).json()) as { channel: string; since?: number }[];
    const rows = await Promise.all(given.map(async (c) => {
      try {
        const r = await channelStub(env, c.channel).fetch(toChannel(new Request(request.url), c.channel, "/_status"));
        const st = (await r.json()) as { live: boolean; viewers: number; title?: string; client?: string; live_s?: number };
        return { ...c, live: st.live, viewers: st.viewers, title: st.title || "", client: st.client || "", live_s: st.live_s || 0 };
      } catch { return { ...c, live: false, viewers: 0, title: "", client: "", live_s: 0 }; } // (c: channel, since, by)
    }));
    return Response.json(rows);
  }
  const name = parts[2] || "";
  if (parts.length !== 3 || !NAME.test(name)) return new Response("which channel?", { status: 400 });
  const stub = channelStub(env, name);
  if (request.method === "POST") return Response.json(await mint(request, env, name));
  if (request.method === "DELETE") {
    await stub.fetch(toChannel(new Request(request.url, { method: "POST" }), name, "/_revoke"));
    await dir.fetch("https://dir/?name=" + encodeURIComponent(name), { method: "DELETE" });
    return new Response(null, { status: 204 });
  }
  return new Response(null, { status: 405 });
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);
    const parts = url.pathname.split("/").filter(Boolean).map(decodeURIComponent);
    const upgrade = (request.headers.get("upgrade") || "").toLowerCase() === "websocket";

    if (parts[0] === "admin" && (parts[1] === "channels" || parts[1] === "requests" || parts[1] === "notice" || parts[1] === "clips" || parts[1] === "reports" || parts[1] === "aether" || parts[1] === "almanac" || parts[1] === "array")) return admin(request, env, parts);

    // the aether (11/07/2026; aether.ts): one room for every outstation and visitor, and its read for a page
    if (parts[0] === "aether" && parts.length === 1) {
      const aether = env.AETHER.get(env.AETHER.idFromName("aether"));
      if (upgrade) {
        const channel = url.searchParams.get("channel");
        if (request.method !== "GET" || (channel !== null && !NAME.test(channel))) return new Response(null, { status: 400 });
        return aether.fetch(new Request("https://aether/socket" + url.search, { headers: request.headers }));
      }
      if (request.method !== "GET") return new Response(null, { status: 405 });
      return aether.fetch("https://aether/read");
    }

    // an anomaly report from a friend's outstation: its channel checked against its key when it gives them
    if (parts[0] === "reports" && parts.length === 1) {
      if (request.method !== "POST") return new Response(null, { status: 405 });
      const n = parseInt(request.headers.get("content-length") || "0") || 0;
      if (n > REPORT_LOG + 16 * 1024) return Response.json({ error: "a report is 64 KB at most" }, { status: 413 });
      const raw = await request.text();
      if (raw.length > REPORT_LOG + 16 * 1024) return Response.json({ error: "a report is 64 KB at most" }, { status: 413 });
      let checked = "";
      try {
        const b = JSON.parse(raw) as { channel?: unknown; key?: unknown };
        if (typeof b.channel === "string" && NAME.test(b.channel) && typeof b.key === "string" && b.key) {
          const r = await channelStub(env, b.channel).fetch(toChannel(new Request(url.origin + "/?key=" + encodeURIComponent(b.key), { headers: request.headers }), b.channel, "/_keyok"));
          if (((await r.json()) as { ok: boolean }).ok) checked = b.channel;
        }
      } catch { /* not JSON: the Directory says so */ }
      const dir = env.DIRECTORY.get(env.DIRECTORY.idFromName("directory"));
      return dir.fetch(new Request("https://dir/reports?what=add&checked=" + encodeURIComponent(checked), { method: "POST", headers: request.headers, body: raw }));
    }

    // clips on a player's Outside page (clips.ts): the files straight from the bucket, the rest through the channel
    if (parts[0] === "clips" && (parts.length === 2 || parts.length === 3) && NAME.test(parts[1])) {
      if (request.method === "OPTIONS") {
        return new Response(null, { status: 204, headers: { "access-control-allow-origin": "*", "access-control-allow-methods": "GET, HEAD, POST, DELETE",
                                                             "access-control-allow-headers": "range, content-type", "access-control-max-age": "86400" } });
      }
      if (parts.length === 3) {
        const m = /^([A-Za-z0-9_-]{16,32})\.(mp4|jpg)$/.exec(parts[2]);
        if ((request.method === "GET" || request.method === "HEAD") && m) return serveFile(env.CLIPS, fileKey(parts[1], m[1], m[2] as "mp4" | "jpg"), request);
        if ((request.method === "DELETE" || request.method === "POST") && ID.test(parts[2])) {
          url.searchParams.set("id", parts[2]);
          return channelStub(env, parts[1]).fetch(toChannel(new Request(url.toString(), request), parts[1], "/_clips"));
        }
        return new Response(null, { status: 404 });
      }
      if (request.method === "GET" || request.method === "POST") return channelStub(env, parts[1]).fetch(toChannel(request, parts[1], "/_clips"));
      return new Response(null, { status: 405 });
    }

    // asking for a portal: a friend's client, with a secret of its own (the pickup) that collects the answer
    if (parts[0] === "requests" && parts.length === 2) {
      const name = parts[1];
      if (!PLAYER.test(name)) return Response.json({ error: "a player's name: 1 to 12 letters or numbers, lower case" }, { status: 400 });
      const dir = env.DIRECTORY.get(env.DIRECTORY.idFromName("directory"));
      const pickup = "&pickup=" + encodeURIComponent(url.searchParams.get("pickup") || "");
      if (request.method === "POST") {
        const n = parseInt(request.headers.get("content-length") || "0") || 0;
        if (n > 4096) return new Response(null, { status: 413 });
        return dir.fetch(new Request(dirRequests(env, "ask", name).url, { method: "POST", headers: request.headers, body: request.body }));
      }
      if (request.method === "GET") return dir.fetch(dirRequests(env, "check", name, pickup));
      if (request.method === "DELETE") return dir.fetch(dirRequests(env, "cancel", name, pickup));
      return new Response(null, { status: 405 });
    }

    // invites: an outstation's own (its key in the query to read them, in the body to use one), all in its channel
    if (parts[0] === "invites" && parts.length === 2) {
      const name = parts[1];
      if (!NAME.test(name)) return Response.json({ error: "which outstation?" }, { status: 400 });
      if (request.method === "GET") return channelStub(env, name).fetch(toChannel(request, name, "/_invites"));
      if (request.method !== "POST") return new Response(null, { status: 405 });
      const raw = await request.text();
      if (raw.length > 4096) return Response.json({ error: "an invite is a key and a name" }, { status: 413 });
      return channelStub(env, name).fetch(toChannel(new Request(request.url, { method: "POST", headers: request.headers, body: raw }), name, "/_invites"));
    }

    // settling, gone (11/10/2026; the file's head): an app built while it was here gets a line it shows, not a 404 that
    // reads as an older relay; a page on another origin can read it too
    if ((parts[0] === "settle" || parts[0] === "home") && parts.length === 2) {
      return Response.json({ error: SETTLING_GONE }, { status: 410, headers: { "access-control-allow-origin": "*", "cache-control": "no-store" } });
    }

    // the Array's events (11/08/2026; array.ts): what's happening across the open outstations, for outside.wiki's front
    // page; any page may read it
    if (parts[0] === "array" && parts[1] === "events" && parts.length === 2) {
      if (request.method !== "GET") return arrayAnswer(null, 405);
      const since = arraySince(url.searchParams.get("since"));
      if (since === null) return arrayAnswer({ error: "since is a cursor: an earlier read's next" }, 400);
      return env.DIRECTORY.get(env.DIRECTORY.idFromName("directory"))
        .fetch("https://dir/array" + (since === undefined ? "" : "?since=" + since));
    }

    // shooting stars (11/08/2026; stars.ts): the stars the plugins have found, and who found each; any page may read it
    if (parts[0] === "stars" && parts.length === 1) {
      if (request.method !== "GET") return arrayAnswer(null, 405);
      return env.DIRECTORY.get(env.DIRECTORY.idFromName("directory")).fetch("https://dir/stars");
    }

    // (tests only, with TEST_FORGET: the Directory put to sleep, so a test can see what its storage brings back)
    if (url.pathname === "/test/forget" && request.method === "POST" && env.TEST_FORGET === "1") {
      await env.DIRECTORY.get(env.DIRECTORY.idFromName("directory")).fetch("https://dir/forget", { method: "POST" }).catch(() => null);
      return new Response(null, { status: 204 });
    }
    // (tests only, with TEST_FORGET: what the Directory's storage holds of a channel's row on the open list, and how
    // often it's asked that channel since it woke; a POST's fields put into the row, as a telling that was lost)
    if (parts[0] === "test" && parts[1] === "listed" && parts.length === 3 && env.TEST_FORGET === "1") {
      return env.DIRECTORY.get(env.DIRECTORY.idFromName("directory")).fetch(new Request(
        "https://dir/test-listed?name=" + encodeURIComponent(parts[2]), { method: request.method, body: request.method === "POST" ? await request.text() : null }));
    }

    // the almanac (11/09/2026; almanac.ts): the news, the worlds and the market, as the relay last fetched them; any page
    // may read it
    if (parts[0] === "almanac" && parts.length === 1) {
      if (request.method !== "GET") return almanacAnswer(null, 405);
      return env.DIRECTORY.get(env.DIRECTORY.idFromName("directory")).fetch("https://dir/almanac");
    }

    // the open portals: every channel handed out that has no watch code, live ones first (Outside's portals page)
    // (the Directory answers it from what each channel has told it: one request, whatever the number of channels)
    if (request.method === "GET" && url.pathname === "/channels") {
      return env.DIRECTORY.get(env.DIRECTORY.idFromName("directory")).fetch("https://dir/channels");
    }

    if (request.method === "POST" && parts.length === 2 && parts[0] === "dink" && NAME.test(parts[1])) {
      const n = parseInt(request.headers.get("content-length") || "0") || 0;
      if (n > MAX_POST) return new Response(null, { status: 413 });
      return channelStub(env, parts[1]).fetch(toChannel(request, parts[1], "/_dink"));
    }

    // the game state kept (11/07/2026; game.ts): for Outside while the outstation's dark, with the watch code as watching
    if (parts[0] === "game" && parts.length === 2 && !upgrade) {
      if (request.method !== "GET") return new Response(null, { status: 405 });
      if (!NAME.test(parts[1])) return Response.json({ error: "which outstation?" }, { status: 400 });
      return channelStub(env, parts[1]).fetch(toChannel(request, parts[1], "/_game"));
    }

    if (upgrade) {
      if (request.method !== "GET" || parts.length !== 2 || !ROLES.has(parts[0]) || !NAME.test(parts[1])) {
        return new Response(null, { status: 400 });
      }
      return channelStub(env, parts[1]).fetch(toChannel(request, parts[1], "/" + parts[0]));
    }

    if (url.pathname === "/" && (request.method === "GET" || request.method === "HEAD")) {
      return new Response("the relay for ⊂λ,ｍ⊙ outstation\n", { headers: { "content-type": "text/plain" } });
    }
    return new Response(null, { status: 404 });
  },

  // the Cron Trigger (wrangler.jsonc, every 5 minutes): the almanac's due sources asked, in the Directory
  async scheduled(_controller: ScheduledController, env: Env, ctx: ExecutionContext): Promise<void> {
    ctx.waitUntil(env.DIRECTORY.get(env.DIRECTORY.idFromName("directory")).fetch("https://dir/almanac/tick", { method: "POST" }).then(() => {}));
  },
} satisfies ExportedHandler<Env>;
