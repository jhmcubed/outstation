// The aether (11/07/2026, Jace: "a global chat… seeing people spam ascii art and stuff. reminds me of IRC and AOL Instant
// Messenger. i want this to feel like a real network people are joining"): one room for every outstation and every
// visitor on the relay, as one Durable Object (the AETHER binding, its one object named "aether"). docs/protocol.md,
// "The aether"; relay-py/relay.py's Aether, the same.
//
//   ws(s)://<relay>/aether?name=<name>&device=<random id>               a visitor (Outside)
//   ws(s)://<relay>/aether?channel=<channel>&key=<key>&v=<its version>  an outstation, under its channel's name
//   GET <relay>/aether                                                  the last 30 lines and how many are here
// The keeper's (index.ts's admin, Authorization: Bearer <ADMIN_TOKEN>):
//   GET  /admin/aether                       every line kept, with who wrote it ("device:…" or "channel:…"), and the bans
//   POST /admin/aether/unsay  {"id"}         a line taken back off everyone's page
//   POST /admin/aether/ban    {"id"}         whoever wrote that line banned: all their lines taken back, their sockets
//                                            closed (4033), and refused from then on
//   POST /admin/aether/unban  {"who"}        let back in
//
// It hibernates as a channel does: each socket's own (who it is, the times of its last lines) is in its attachment. The
// last AETHER_KEPT lines are in memory and in storage, one row however many lines, written at most every AETHER_SAVE
// while lines come and when the room empties (never a write a line); the bans at once (they're rare). An outstation's
// key is checked by its channel's object, as a desk's is; a visitor's name against the channels handed out, by the
// Directory. Nothing an aether socket sends reaches a channel, and nothing from a channel reaches here.

import { DurableObject } from "cloudflare:workers";
import { type Env, tooOld } from "./channel";
import { aetherName, aetherRecent, aetherText, aetherWait, channelForm, pyjson, AETHER_A_MINUTE } from "./text";

export const AETHER_KEPT = 100; // lines kept, and handed to whoever joins
export const AETHER_READ = 30; // lines in the public read (GET /aether)
const AETHER_MESSAGE = 8192; // anything longer from a socket is thrown away unread (a full line, escaped, fits)
const AETHER_SAVE = 5000; // the lines into storage at most this often while they come
const AETHER_HERE_EVERY = 2000; // who's here told to everyone at most this often
const SAID_MOST = 1000; // senders' last lines' times kept in memory, past which the stale ones go
const NAME = /^[A-Za-z0-9_.~-]{1,64}$/; // a channel
const DEVICE = /^[\x21-\x7e]{8,64}$/; // a visitor's device id: 8 to 64 characters, printable, no spaces
const OPEN = 1; // WebSocket.OPEN

type From = "outstation" | "visitor";
interface Line { id: number; name: string; text: string; from: From; at: number; who: string }
interface Ban { who: string; name: string; at: number }
interface Att { from: From; name: string; who: string; says: number[]; refused?: boolean }

/** A line as everyone sees it: never who wrote it (a device or a channel's key), only the name. */
const pub = (l: Line) => ({ id: l.id, name: l.name, text: l.text, from: l.from, at: l.at });

export class Aether extends DurableObject<Env> {
  lines: Line[] = [];
  nextId = 0;
  bans = new Map<string, Ban>();
  atts = new WeakMap<WebSocket, Att>();
  here: WebSocket[] | null = null; // the sockets in the room, worked out again when someone comes or goes
  // each sender's last lines' times (who: "device:…" or "channel:…"), so a reconnect doesn't start the count again (in
  // memory: a sleep forgets, which only loosens it; each socket's own count, in its attachment, outlives one)
  said = new Map<string, number[]>();
  saveTimer: ReturnType<typeof setTimeout> | null = null;
  savedAt = 0;
  dirty = false;
  hereTimer: ReturnType<typeof setTimeout> | null = null;
  hereAt = 0; // when who's here was last told to everyone
  hereSent = -1; // what it said

  constructor(ctx: DurableObjectState, env: Env) {
    super(ctx, env);
    ctx.blockConcurrencyWhile(async () => {
      const got = await ctx.storage.get(["room", "bans"]);
      const room = got.get("room") as { id: number; lines: Line[] } | undefined;
      if (room) {
        this.lines = room.lines || [];
        this.nextId = Math.max(room.id || 0, ...this.lines.map((l) => l.id));
      }
      for (const b of (got.get("bans") as Ban[] | undefined) || []) this.bans.set(b.who, b);
    });
  }

  async fetch(request: Request): Promise<Response> {
    const url = new URL(request.url);
    if (url.pathname === "/socket") return this.open(request, url.searchParams);
    if (url.pathname === "/read") return this.read();
    if (url.pathname === "/admin" || url.pathname.startsWith("/admin/")) return this.keeper(request, url.pathname.slice(6));
    return new Response(null, { status: 404 });
  }

  // who's here

  sockets(): WebSocket[] {
    return (this.here ??= this.ctx.getWebSockets("here").filter((ws) => ws.readyState === OPEN));
  }
  att(ws: WebSocket): Att {
    let a = this.atts.get(ws);
    if (!a) this.atts.set(ws, (a = ws.deserializeAttachment() as Att));
    return a;
  }
  keep(ws: WebSocket, a: Att) {
    ws.serializeAttachment(a);
    this.atts.set(ws, a);
  }
  /** How many are here: each visitor's device and each outstation once, however many sockets it has open. */
  count(): number {
    return new Set(this.sockets().map((ws) => this.att(ws).who)).size;
  }
  send(ws: WebSocket, line: string) {
    try { ws.send(line); } catch { /* gone already */ }
  }
  broadcast(line: string) {
    for (const ws of this.sockets()) this.send(ws, line);
  }
  close(ws: WebSocket, code: number, reason: string) {
    try { ws.close(code, reason); } catch { /* gone already */ }
    this.here = null;
  }

  /** Who's here changed (or someone just came: joiner): everyone's told the count at most every AETHER_HERE_EVERY,
   *  the last change waiting for its turn; whoever just came hears it at once. (One told a count the others weren't
   *  means everyone's told at the next turn, even if the count's back to what they were told by then.) */
  hereChanged(joiner?: WebSocket) {
    const n = this.count(), now = Date.now();
    if (n !== this.hereSent && now - this.hereAt >= AETHER_HERE_EVERY) return this.tellHere();
    if (joiner) {
      this.send(joiner, pyjson({ aether_here: n }));
      if (n !== this.hereSent) this.hereSent = -1;
    }
    if (n !== this.hereSent && !this.hereTimer) {
      this.hereTimer = setTimeout(() => this.tellHere(), Math.max(0, this.hereAt + AETHER_HERE_EVERY - Date.now()));
    }
  }
  tellHere() {
    if (this.hereTimer) clearTimeout(this.hereTimer);
    this.hereTimer = null;
    const n = this.count();
    if (n === this.hereSent) return;
    this.hereSent = n;
    this.hereAt = Date.now();
    this.broadcast(pyjson({ aether_here: n }));
  }

  // the door

  async open(request: Request, q: URLSearchParams): Promise<Response> {
    const ip = request.headers.get("cf-connecting-ip") || "";
    const channel = q.get("channel");
    let no: [number, string] | null = null, from: From, name: string, who = "";
    if (channel !== null) {
      // an outstation: its version as a desk's (MIN_CLIENT), then its key, checked by its channel's object (4001, 4003,
      // 4029 as a desk's; a channel nobody handed out is 4003)
      from = "outstation";
      name = channel;
      who = "channel:" + channel;
      const min = this.env.MIN_CLIENT || "";
      if (!NAME.test(channel)) no = [4003, "no such channel: ask for an attach code"];
      else if (tooOld(q.get("v"), min)) no = [4026, `this outstation is too old for the relay: ${min} or newer is needed`];
      else {
        try {
          const r = await this.env.CHANNEL.get(this.env.CHANNEL.idFromName(channel)).fetch(new Request(
            "https://channel/_key?key=" + encodeURIComponent(q.get("key") || ""), { headers: { "x-channel": channel, "cf-connecting-ip": ip } }));
          no = ((await r.json()) as { no: [number, string] | null }).no;
        } catch {
          no = [1013, "the relay couldn't check the key: try again"];
        }
      }
    } else {
      // a visitor: a name the aether can take, and a device id (what a ban holds on to)
      from = "visitor";
      name = aetherName(q.get("name"));
      const device = q.get("device") || "";
      if (!name) no = [4002, "a name in the aether is 1 to 20 letters, digits, spaces, _ - or ."];
      else if (!DEVICE.test(device)) no = [4002, "a device id is 8 to 64 characters"];
      else who = "device:" + device;
    }
    if (!no && this.bans.has(who)) no = [4033, "banned from the aether"];
    if (!no && from === "visitor") {
      try {
        const r = await this.env.DIRECTORY.get(this.env.DIRECTORY.idFromName("directory"))
          .fetch("https://dir/taken?name=" + encodeURIComponent(channelForm(name)));
        if (((await r.json()) as { taken: boolean }).taken) no = [4009, "that name belongs to an outstation"];
      } catch {
        no = [1013, "the relay couldn't check the name: try again"];
      }
    }
    if (!no && this.bans.has(who)) no = [4033, "banned from the aether"]; // (banned while the name was being checked)
    const pair = new WebSocketPair();
    const [client, ws] = [pair[0], pair[1]];
    this.ctx.acceptWebSocket(ws, [no ? "refused" : "here"]);
    if (no) {
      this.keep(ws, { from, name, who, says: [], refused: true });
      this.close(ws, no[0], no[1]);
    } else {
      this.keep(ws, { from, name, who, says: [] });
      this.here = null;
      this.send(ws, pyjson({ aether_so_far: this.lines.map(pub) }));
      this.hereChanged(ws);
    }
    return new Response(null, { status: 101, webSocket: client });
  }

  // lines

  // (not async: nothing here waits)
  webSocketMessage(ws: WebSocket, msg: string | ArrayBuffer) {
    if (typeof msg !== "string" || msg.length > AETHER_MESSAGE || ws.readyState !== OPEN) return;
    const a = this.att(ws);
    if (a.refused) return;
    let m: any;
    try { m = JSON.parse(msg); } catch { return; }
    if (!m || typeof m !== "object" || Array.isArray(m) || !("say" in m)) return;
    const text = aetherText(m.say);
    if (!text) return;
    // one line every AETHER_EVERY and AETHER_A_MINUTE a minute, from this socket and from whoever it is (its device, or
    // its channel) however many sockets it opens
    const now = Date.now(), mine = aetherRecent(a.says || [], now), theirs = aetherRecent(this.said.get(a.who) || [], now);
    const wait = Math.max(aetherWait(mine, now), aetherWait(theirs, now));
    if (wait > 0) {
      this.send(ws, pyjson({ aether_slow: wait }));
      return;
    }
    a.says = [...mine, now].slice(-AETHER_A_MINUTE);
    this.keep(ws, a);
    this.said.set(a.who, [...theirs, now].slice(-AETHER_A_MINUTE));
    if (this.said.size > SAID_MOST) {
      for (const [k, t] of this.said) if (!aetherRecent(t, now).length) this.said.delete(k);
    }
    this.nextId++;
    const line: Line = { id: this.nextId, name: a.name, text, from: a.from, at: now, who: a.who };
    this.lines.push(line);
    if (this.lines.length > AETHER_KEPT) this.lines = this.lines.slice(-AETHER_KEPT);
    this.broadcast(pyjson({ aether: pub(line) }));
    this.saveSoon();
  }

  /** Lines taken back off everyone's page (the keeper's), each told {"aether_unsay": id}. */
  unsay(ids: number[]) {
    if (!ids.length) return;
    const gone = new Set(ids);
    this.lines = this.lines.filter((l) => !gone.has(l.id));
    for (const id of ids) this.broadcast(pyjson({ aether_unsay: id }));
    this.saveSoon();
  }

  async webSocketClose(ws: WebSocket, code: number, reason: string) {
    this.close(ws, code === 1005 || code === 1006 ? 1000 : code, reason);
    this.gone(ws);
  }

  async webSocketError(ws: WebSocket) {
    this.here = null;
    this.gone(ws);
  }

  gone(ws: WebSocket) {
    if (this.att(ws).refused) return;
    this.hereChanged();
    if (!this.sockets().length) this.save(); // (the room's empty: whatever's waiting is written now)
  }

  // storage

  /** The lines into storage, at most once every AETHER_SAVE while they come: soon after the first, then 5 s after the
   *  last save. (A timer waiting keeps the object from hibernating; one evicted by a deploy loses at most those 5 s.) */
  saveSoon() {
    this.dirty = true;
    if (this.saveTimer) return;
    this.saveTimer = setTimeout(() => { this.saveTimer = null; this.save(); }, Math.max(0, this.savedAt + AETHER_SAVE - Date.now()));
  }

  save() {
    if (this.saveTimer) clearTimeout(this.saveTimer);
    this.saveTimer = null;
    if (!this.dirty) return;
    this.dirty = false;
    this.savedAt = Date.now();
    this.ctx.storage.put("room", { id: this.nextId, lines: this.lines });
  }

  async saveBans() {
    await this.ctx.storage.put("bans", [...this.bans.values()]);
  }

  // the public read: the room before joining it (anyone may read it, cached a few seconds)

  read(): Response {
    return new Response(pyjson({ lines: this.lines.slice(-AETHER_READ).map(pub), here: this.count() }), {
      headers: { "content-type": "application/json", "access-control-allow-origin": "*", "cache-control": "public, max-age=5" },
    });
  }

  // the keeper's (index.ts's admin() has checked the token)

  async keeper(request: Request, what: string): Promise<Response> {
    if (what === "") {
      if (request.method !== "GET") return new Response(null, { status: 405 });
      return Response.json({ lines: this.lines.map((l) => ({ ...pub(l), who: l.who })), bans: [...this.bans.values()] });
    }
    if (what !== "/unsay" && what !== "/ban" && what !== "/unban") return new Response(null, { status: 404 });
    if (request.method !== "POST") return new Response(null, { status: 405 });
    const body = (await request.json().catch(() => null)) as { id?: unknown; who?: unknown } | null;
    if (what === "/unban") {
      const who = typeof body?.who === "string" ? body.who : "";
      if (!this.bans.delete(who)) return Response.json({ error: "nobody's banned by that" }, { status: 404 });
      await this.saveBans();
      return Response.json({ bans: [...this.bans.values()] });
    }
    const id = body?.id;
    const line = typeof id === "number" && Number.isInteger(id) ? this.lines.find((l) => l.id === id) : undefined;
    if (!line) return Response.json({ error: "no such line in the aether" }, { status: 404 });
    if (what === "/unsay") {
      this.unsay([line.id]);
      return Response.json({ unsaid: [line.id] });
    }
    // banned: by its device (a visitor) or its channel (an outstation), every line of theirs kept taken back, and their
    // sockets closed
    const who = line.who;
    this.bans.set(who, { who, name: line.name, at: Date.now() });
    await this.saveBans();
    const gone = this.lines.filter((l) => l.who === who).map((l) => l.id);
    this.unsay(gone);
    for (const ws of this.sockets()) if (this.att(ws).who === who) this.close(ws, 4033, "banned from the aether");
    this.hereChanged();
    return Response.json({ banned: who, unsaid: gone });
  }
}
