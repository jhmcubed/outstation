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
//   GET    /admin/channels              the channels handed out
// The attach code is "portal1:" and base64url JSON {"relay", "channel", "key"}: the client takes it in one paste.

import { DurableObject } from "cloudflare:workers";
import { Channel, type Env } from "./channel";

export { Channel };

const ROLES = new Set(["transmit", "watch", "desk", "sound"]);
const NAME = /^[A-Za-z0-9_.~-]{1,64}$/;
const MAX_POST = 16 * 1024 * 1024;

// the channels handed out, for the owner's list
export class Directory extends DurableObject<Env> {
  async fetch(request: Request): Promise<Response> {
    const url = new URL(request.url), name = url.searchParams.get("name") || "";
    if (request.method === "PUT") await this.ctx.storage.put("c:" + name, { since: Date.now() });
    else if (request.method === "DELETE") await this.ctx.storage.delete("c:" + name);
    const all = await this.ctx.storage.list({ prefix: "c:" });
    return Response.json([...all.entries()].map(([k, v]) => ({ channel: k.slice(2), ...(v as object) })));
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

async function admin(request: Request, env: Env, parts: string[]): Promise<Response> {
  const auth = request.headers.get("authorization") || "";
  if (!env.ADMIN_TOKEN || env.ADMIN_TOKEN.length < 16 || !sameText(auth, "Bearer " + env.ADMIN_TOKEN)) {
    return new Response("no", { status: 403 });
  }
  const dir = env.DIRECTORY.get(env.DIRECTORY.idFromName("directory"));
  if (parts.length === 2 && request.method === "GET") return dir.fetch("https://dir/");
  const name = parts[2] || "";
  if (parts.length !== 3 || !NAME.test(name)) return new Response("which channel?", { status: 400 });
  const stub = channelStub(env, name);
  if (request.method === "POST") {
    const r = await stub.fetch(toChannel(new Request(request.url, { method: "POST" }), name, "/_mint"));
    const { key } = (await r.json()) as { key: string };
    await dir.fetch("https://dir/?name=" + encodeURIComponent(name), { method: "PUT" });
    const u = new URL(request.url);
    const relay = (u.protocol === "https:" ? "wss://" : "ws://") + u.host;
    return Response.json({ channel: name, key, code: attachCode(relay, name, key) });
  }
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

    if (parts[0] === "admin" && parts[1] === "channels") return admin(request, env, parts);

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
