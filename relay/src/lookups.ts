// The lookups' object (11/10/2026; lookup.ts says what they are): one for the relay, so every visitor's read of a player
// or an icon shares one copy and one allowance upstream. What it keeps is in its storage ("e:<key>": when, the status,
// the type and the body), read as it's asked, so an eviction or a deploy asks nothing again; once a day its alarm drops
// what's past KEEP_DAYS (lookup.ts's pruned). The allowances, pauses and failures are in its memory: a sleep only
// loosens them.

import { DurableObject } from "cloudflare:workers";
import { type Env } from "./channel";
import { type Entry, Shelf, httpLookup, lookupHeaders, perMinute, pruned, want, womKeys, womUpdateUrl } from "./lookup";

const PRUNE_EVERY = 86_400_000;

export class Lookups extends DurableObject<Env> {
  shelf = new Shelf({
    get: (key) => this.ctx.storage.get<Entry>("e:" + key),
    put: async (key, e) => {
      await this.ctx.storage.put("e:" + key, e);
      if ((await this.ctx.storage.getAlarm()) == null) await this.ctx.storage.setAlarm(Date.now() + PRUNE_EVERY);
    },
  }, (url, source) => httpLookup(url, source, this.env.WOM_API_KEY), Date.now, perMinute(this.env.WOM_API_KEY));

  async fetch(request: Request): Promise<Response> {
    const url = new URL(request.url);
    const parts = url.pathname.split("/").filter(Boolean).map((p) => { try { return decodeURIComponent(p); } catch { return ""; } });
    // (the Directory's, never a page's: the Worker passes only /lookup/ and /icon/ here) Wise Old Man asked to update a
    // player (lookup.ts, womUpdateUrl), its answer's status back; what was kept of them forgotten, so it's read anew
    if (request.method === "POST" && parts[0] === "track" && parts[1] === "wom" && parts.length === 3) {
      const at = womUpdateUrl(parts[2], this.env.LOOKUP_UPSTREAM || null);
      if (!at) return new Response(null, { status: 400 });
      let status = 0;
      try {
        const r = await fetch(at, { method: "POST", headers: lookupHeaders(at, "wom", this.env.WOM_API_KEY), signal: AbortSignal.timeout(15_000) });
        status = r.status;
        await r.body?.cancel();
      } catch { status = 0; }
      if (status >= 200 && status < 300) await this.ctx.storage.delete(womKeys(parts[2]).map((k) => "e:" + k));
      return Response.json({ status });
    }
    const q: Record<string, string> = {};
    for (const [k, v] of url.searchParams) if (!(k in q)) q[k] = v;
    const w = want(parts, this.env.LOOKUP_UPSTREAM || null, q);
    if (!w) return new Response(null, { status: 404, headers: { "access-control-allow-origin": "*" } });
    const a = await this.shelf.read(w);
    return new Response(a.body, { status: a.status, headers: a.headers });
  }

  async alarm(): Promise<void> {
    const kept = await this.ctx.storage.list<Entry>({ prefix: "e:" }), now = Date.now();
    const gone = pruned([...kept].map(([k, e]) => [k.slice(2), e.at]), now).map((k) => "e:" + k);
    for (let i = 0; i < gone.length; i += 128) await this.ctx.storage.delete(gone.slice(i, i + 128));
    if (kept.size > gone.length) await this.ctx.storage.setAlarm(now + PRUNE_EVERY);
  }
}
