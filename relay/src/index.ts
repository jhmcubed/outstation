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
// nothing back from the channels it invited, or gives its inviter the invite back.
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

import { DurableObject } from "cloudflare:workers";
import { Channel, PLAYER, type Env, type Listing } from "./channel";
import { fileKey, serveFile, ID } from "./clips";
import { attachCode, oneLine, relayOf } from "./text";

export { Channel };

const ROLES = new Set(["transmit", "watch", "desk", "sound", "game"]);
const NAME = /^[A-Za-z0-9_.~-]{1,64}$/;
const MAX_POST = 16 * 1024 * 1024;

const ASKS_AN_HOUR = 3; // requests from one address in an hour
const WAITING_MOST = 50; // requests waiting at once, everyone's
const WAIT_DAYS = 14, PICKUP_DAYS = 7; // a request not answered in this long goes; an answer not collected, too
const DAY = 86_400_000;
const REPORTS_KEPT = 50, REPORT_DAYS = 30, REPORTS_AN_HOUR = 5, REPORT_LOG = 64 * 1024, REPORT_NOTE = 1000;
// a channel's row in the open portals' list is asked for again when it's older than this, though each channel tells
// its own as it changes (in case a telling was lost, or a channel went without saying)
const LISTING_CHECK = 5 * 60_000;

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
  // the open portals (GET /channels, 11/07/2026): each channel's row as it last told it (Channel.tellListing), or as it
  // answered when asked; in memory, so a sleep only means asking each channel once more. A read used to ask every
  // channel handed out for its status, waking each: a request to every channel each time the outstations page (every
  // 20 s while open) or any friend's client (every minute) read the list.
  // the channels handed out (their "c:" rows), each with whose invite it came from (null: the keeper's own), till one's
  // handed out or taken back
  given: Map<string, string | null> | null = null;
  listed = new Map<string, { row: Listing; seen: number }>();

  async fetch(request: Request): Promise<Response> {
    const url = new URL(request.url), name = url.searchParams.get("name") || "";
    if (url.pathname === "/channels") return this.openPortals();
    if (url.pathname === "/listed" && request.method === "PUT") {
      const row = (await request.json()) as Listing;
      const had = this.listed.get(row.channel);
      if (!had || had.row.at <= row.at) this.listed.set(row.channel, { row, seen: Date.now() });
      return new Response(null, { status: 204 });
    }
    if (url.pathname === "/requests") return this.requests(request, url);
    if (url.pathname === "/reports") return this.reports(request, url);
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
      this.listed.delete(name); // (a new key: whatever it said before, it's asked again)
      if (request.method === "PUT" && by !== null) return new Response(null, { status: 204 }); // (an invite's: no list)
    }
    const all = await this.ctx.storage.list({ prefix: "c:" });
    return Response.json([...all.entries()].map(([k, v]) => ({ channel: k.slice(2), by: null, ...(v as object) })));
  }

  /** GET /channels: every channel handed out that has no watch code, live ones first. Only a channel whose row is
   *  missing (or LISTING_CHECK old) is asked. */
  async openPortals(): Promise<Response> {
    if (!this.given) {
      const rows = await this.ctx.storage.list<{ by?: string | null }>({ prefix: "c:" });
      this.given = new Map([...rows].map(([k, v]) => [k.slice(2), v.by ?? null]));
    }
    const given = this.given, names = [...given.keys()], now = Date.now(); // (one handed out meanwhile: in the next read)
    const ask = names.filter((n) => { const l = this.listed.get(n); return !l || now - l.seen > LISTING_CHECK; });
    await Promise.all(ask.map(async (n) => {
      try {
        const r = await this.env.CHANNEL.get(this.env.CHANNEL.idFromName(n))
          .fetch(new Request("https://channel/_listing", { headers: { "x-channel": n } }));
        const row = (await r.json()) as Listing;
        const had = this.listed.get(n);
        if (!had || had.row.at <= row.at) this.listed.set(n, { row, seen: Date.now() });
      } catch { /* (left out this time) */ }
    }));
    const open = names.map((n) => this.listed.get(n)?.row).filter((r): r is Listing => !!r && r.set && !r.code)
      .map((r) => ({ channel: r.channel, live: !!r.live, open: !!r.open, static: !!r.static, viewers: r.viewers || 0,
                     title: r.title || "", doing: r.doing || null, by: given.get(r.channel) ?? null, game: !!r.game }));
    open.sort((a, b) => Number(b.live) - Number(a.live) || a.channel.localeCompare(b.channel));
    return new Response(JSON.stringify({ channels: open }), { headers: { "content-type": "application/json", "access-control-allow-origin": "*", "cache-control": "no-cache" } });
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

async function admin(request: Request, env: Env, parts: string[]): Promise<Response> {
  const auth = request.headers.get("authorization") || "";
  if (!env.ADMIN_TOKEN || env.ADMIN_TOKEN.length < 16 || !sameText(auth, "Bearer " + env.ADMIN_TOKEN)) {
    return new Response("no", { status: 403 });
  }
  const dir = env.DIRECTORY.get(env.DIRECTORY.idFromName("directory"));
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

    if (parts[0] === "admin" && (parts[1] === "channels" || parts[1] === "requests" || parts[1] === "notice" || parts[1] === "clips" || parts[1] === "reports")) return admin(request, env, parts);

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
} satisfies ExportedHandler<Env>;
