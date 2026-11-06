// The portal's relay on Cloudflare: a Worker in front, a Durable Object per channel behind it (channel.ts).
//
//   ws(s)://<relay>/transmit/<channel>?key=<key>&code=<watch code>     the capture
//   ws(s)://<relay>/sound/<channel>?key=<key>                          the sound
//   ws(s)://<relay>/desk/<channel>?key=<key>&name=<name in the chat>   the chat window
//   ws(s)://<relay>/watch/<channel>?code=<watch code>                  a viewer (Outside)
//   POST <relay>/dink/<channel>?key=<key>                              Dink, as to a Discord webhook (a fallback)
//
// Channels are handed out by the relay's owner (Authorization: Bearer <ADMIN_TOKEN>, a Worker secret):
//   POST   /admin/channels/<channel>    a new key for it: {"channel", "key", "code"} (code: the attach code)
//   DELETE /admin/channels/<channel>    taken back: its transmitter cut off, its key refused
//   GET    /admin/channels              the channels handed out (live, title, client: its last transmitter's version)
// The attach code is "portal1:" and base64url JSON {"relay", "channel", "key"}: the client takes it in one paste.
//
// Asking for a portal (11/06/2026), so nobody has to email the keeper for a code:
//   POST   /requests/<channel>  {"player", "note", "pickup"}   a friend's client asks for that player's channel
//   GET    /requests/<channel>?pickup=<secret>                waiting / given (the attach code, once) / turned down
//   DELETE /requests/<channel>?pickup=<secret>                the asker takes it back
//   GET    /admin/requests                                    the keeper's list of those waiting
//   POST   /admin/requests/<channel>                          handed out: the code waits for the asker's client
//   DELETE /admin/requests/<channel>                          turned down
// Only the pickup's SHA-256 is kept; the attach code is kept only until the asker's client collects it.

import { DurableObject } from "cloudflare:workers";
import { Channel, type Env } from "./channel";
import { oneLine } from "./text";

export { Channel };

const ROLES = new Set(["transmit", "watch", "desk", "sound"]);
const NAME = /^[A-Za-z0-9_.~-]{1,64}$/;
const MAX_POST = 16 * 1024 * 1024;

const PLAYER = /^[a-z0-9]{1,12}$/; // a channel asked for: a player's name as the page has it (lower case, no spaces)
const ASKS_AN_HOUR = 3; // requests from one address in an hour
const WAITING_MOST = 50; // requests waiting at once, everyone's
const WAIT_DAYS = 14, PICKUP_DAYS = 7; // a request not answered in this long goes; an answer not collected, too
const DAY = 86_400_000;

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

  async fetch(request: Request): Promise<Response> {
    const url = new URL(request.url), name = url.searchParams.get("name") || "";
    if (url.pathname === "/requests") return this.requests(request, url);
    if (request.method === "PUT") await this.ctx.storage.put("c:" + name, { since: Date.now() });
    else if (request.method === "DELETE") await this.ctx.storage.delete("c:" + name);
    const all = await this.ctx.storage.list({ prefix: "c:" });
    return Response.json([...all.entries()].map(([k, v]) => ({ channel: k.slice(2), ...(v as object) })));
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

function attachCode(relay: string, channel: string, key: string): string {
  const json = JSON.stringify({ relay, channel, key });
  const b64 = btoa(String.fromCharCode(...new TextEncoder().encode(json)));
  return "portal1:" + b64.replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
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
  const u = new URL(request.url);
  const relay = (u.protocol === "https:" ? "wss://" : "ws://") + u.host;
  return { channel: name, key, code: attachCode(relay, name, key) };
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
    // the channels handed out, each with what its channel says now: live, its title, and the version of the client
    // that last transmitted on it (so the keeper can see who'd be turned away by a higher MIN_CLIENT)
    const given = (await (await dir.fetch("https://dir/")).json()) as { channel: string; since?: number }[];
    const rows = await Promise.all(given.map(async (c) => {
      try {
        const r = await channelStub(env, c.channel).fetch(toChannel(new Request(request.url), c.channel, "/_status"));
        const st = (await r.json()) as { live: boolean; viewers: number; title?: string; client?: string };
        return { ...c, live: st.live, viewers: st.viewers, title: st.title || "", client: st.client || "" };
      } catch { return { ...c, live: false, viewers: 0, title: "", client: "" }; }
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

    if (parts[0] === "admin" && (parts[1] === "channels" || parts[1] === "requests")) return admin(request, env, parts);

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

    // the open portals: every channel handed out that has no watch code, live ones first (Outside's portals page)
    if (request.method === "GET" && url.pathname === "/channels") {
      const dir = env.DIRECTORY.get(env.DIRECTORY.idFromName("directory"));
      const names = ((await (await dir.fetch("https://dir/")).json()) as { channel: string }[]).map((c) => c.channel);
      const rows = await Promise.all(names.map(async (n) => {
        try {
          const r = await channelStub(env, n).fetch(toChannel(new Request(url.toString()), n, "/_status"));
          return (await r.json()) as { channel: string; set: boolean; code: boolean; live: boolean; open: boolean; static: boolean; viewers: number; title?: string };
        } catch { return null; }
      }));
      const open = rows.filter((r) => r && r.set && !r.code).map((r) => ({ channel: r!.channel, live: r!.live, open: r!.open, static: r!.static, viewers: r!.viewers, title: r!.title || "" }));
      open.sort((a, b) => Number(b.live) - Number(a.live) || a.channel.localeCompare(b.channel));
      return new Response(JSON.stringify({ channels: open }), { headers: { "content-type": "application/json", "access-control-allow-origin": "*", "cache-control": "no-cache" } });
    }

    if (request.method === "POST" && parts.length === 2 && parts[0] === "dink" && NAME.test(parts[1])) {
      const n = parseInt(request.headers.get("content-length") || "0") || 0;
      if (n > MAX_POST) return new Response(null, { status: 413 });
      return channelStub(env, parts[1]).fetch(toChannel(request, parts[1], "/_dink"));
    }

    if (upgrade) {
      if (request.method !== "GET" || parts.length !== 2 || !ROLES.has(parts[0]) || !NAME.test(parts[1])) {
        return new Response(null, { status: 400 });
      }
      return channelStub(env, parts[1]).fetch(toChannel(request, parts[1], "/" + parts[0]));
    }

    if (url.pathname === "/" && (request.method === "GET" || request.method === "HEAD")) {
      return new Response("the portal's relay (outside-portal)\n", { headers: { "content-type": "text/plain" } });
    }
    return new Response(null, { status: 404 });
  },
} satisfies ExportedHandler<Env>;
