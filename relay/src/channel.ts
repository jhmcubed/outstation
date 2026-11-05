// One channel of the portal: its transmitter, its viewers, its desks (the chat window) and its sound.
//
// The same protocol as relay-py/relay.py (docs/protocol.md), as a Durable Object: one object per channel,
// using Cloudflare's WebSocket Hibernation, so a channel with only viewers and the chat waiting (off air)
// sleeps between messages and costs almost nothing. A sleeping object forgets what's in memory, so what
// must outlive a sleep (the key's hash, the watch code, the chat so far, the poll, the blocks, the reports)
// is in the object's own storage; each socket's own state is in its attachment. While a channel is live its
// frames keep it awake, so the last frame lives in memory only.

import { DurableObject } from "cloudflare:workers";
import { dinkReport, oneLine, pyjson } from "./text";

export interface Env {
  CHANNEL: DurableObjectNamespace<Channel>;
  DIRECTORY: DurableObjectNamespace;
  ADMIN_TOKEN?: string;
  OPEN_CLAIM?: string; // "1": the first transmitter claims a channel, as relay.py does (local tests only)
  MAX_VIEWERS?: string; // a ceiling on any channel's viewers ("0" or unset: none beyond the channel's own cap)
  POLL_MIN_SECONDS?: string; // (tests: a poll that ends quickly)
  POLL_KEPT_SECONDS?: string;
}

const MAX_MESSAGE = 512 * 1024; // a frame is 10-70 KB; anything bigger is thrown away
const VIEWER_TEXT = 2048; // anything longer from a viewer is thrown away unread
const CHAT_KEPT = 50;
const SAY_MAX = 300, SAY_BURST = 20, SAY_WINDOW = 10_000;
const NAME_MAX = 20;
const PING_EVERY = 500;
const POLL_OPTIONS = [2, 6], POLL_SECONDS = [10, 900];
const POLL_KEPT = 90_000;
const SOUND_HOLD = 500; // the capture's hold-back: a pause takes the last moment's sound back too
const REPORTS_KEPT = 8;
const WRONG_KEYS = 10; // wrong keys from one address in a minute, then refused for a minute
const OPEN = 1; // WebSocket.OPEN
// Flow control: a viewer says {"got": <binary bytes received so far>} about once a second, and past this much
// sent and not yet acknowledged it's sent no more frames or sound until it catches up. (Cloudflare's server side
// shows no send buffer to read, and a viewer that stops reading would otherwise pile up in the object's memory.)
const WINDOW = 4 * 1024 * 1024;

type Role = "transmit" | "watch" | "desk" | "sound" | "refused";

interface Att {
  role: Role;
  who?: number; // a viewer's number in the chat
  seq?: number; // when it came, for the order of the waiting line
  seated?: boolean;
  code?: string;
  device?: string;
  name?: string;
  says?: number[];
  lastPing?: number;
  host?: string; // a desk's name in the chat
}

interface Poll {
  id: number;
  question: string;
  options: string[];
  votes: Record<string, number>;
  ends: number; // epoch ms
  open: boolean;
  closed?: number;
}

interface Meta {
  keyHash: string | null;
  code: string;
  cap: number; // the channel's own cap on viewers (the desk's {"cap"}): 0 none
  chatId: number;
  pollId: number;
  nextWho: number;
  seq: number;
  hostNames: string[];
  blocked: string[]; // "w:<who>" and "d:<device>"
  soundInfo: string | null;
  state: string; // the capture's last {"state"}: sound passes only while "open"
}

export class Channel extends DurableObject<Env> {
  name = "";
  meta!: Meta;
  chat: [number, string][] = [];
  poll: Poll | null = null;
  reports: string[] = [];
  lastData: string | null = null;
  lastFrame: ArrayBuffer | null = null;
  soundHeld: [number, ArrayBuffer][] = [];
  soundTimer: ReturnType<typeof setTimeout> | null = null;
  uploadTimer: ReturnType<typeof setInterval> | null = null;
  out = 0; // bytes sent to viewers (frames, sound, lines), for the desk's upload line
  outSeen: [number, number] = [0, Date.now()];
  wrong = new Map<string, number[]>();
  flow = new WeakMap<WebSocket, { sent: number; got: number }>();

  constructor(ctx: DurableObjectState, env: Env) {
    super(ctx, env);
    ctx.blockConcurrencyWhile(async () => {
      const got = await ctx.storage.get(["name", "meta", "chat", "poll", "reports"]);
      this.name = (got.get("name") as string) || "";
      this.meta = (got.get("meta") as Meta) || {
        keyHash: null, code: "", cap: 0, chatId: 0, pollId: 0, nextWho: 0, seq: 0,
        hostNames: [], blocked: [], soundInfo: null, state: "open",
      };
      this.chat = (got.get("chat") as [number, string][]) || [];
      this.poll = (got.get("poll") as Poll) || null;
      this.reports = (got.get("reports") as string[]) || [];
    });
  }

  // storage

  saveMeta() { this.ctx.storage.put("meta", this.meta); }
  saveChat() { this.ctx.storage.put("chat", this.chat); }
  savePoll() { this.poll ? this.ctx.storage.put("poll", this.poll) : this.ctx.storage.delete("poll"); }

  // who's here

  sockets(role: Role): WebSocket[] {
    return this.ctx.getWebSockets(role).filter((ws) => ws.readyState === OPEN);
  }
  att(ws: WebSocket): Att { return ws.deserializeAttachment() as Att; }
  seated(): WebSocket[] { return this.sockets("watch").filter((ws) => this.att(ws).seated); }
  waiting(): WebSocket[] {
    return this.sockets("watch").filter((ws) => !this.att(ws).seated)
      .sort((a, b) => (this.att(a).seq || 0) - (this.att(b).seq || 0));
  }
  live(): boolean { return this.sockets("transmit").length > 0; }
  cap(): number {
    const ceiling = parseInt(this.env.MAX_VIEWERS || "0") || 0, own = this.meta.cap || 0;
    return ceiling && own ? Math.min(ceiling, own) : ceiling || own;
  }

  send(ws: WebSocket, msg: string | ArrayBuffer): boolean {
    try {
      ws.send(msg);
      return true;
    } catch {
      return false;
    }
  }
  // frames and sound: only as fast as the viewer takes them (WINDOW)
  sendBinary(ws: WebSocket, msg: ArrayBuffer): boolean {
    let f = this.flow.get(ws);
    if (!f) this.flow.set(ws, (f = { sent: 0, got: 0 }));
    if (f.sent - f.got > WINDOW) return false;
    if (!this.send(ws, msg)) return false;
    f.sent += msg.byteLength;
    return true;
  }

  close(ws: WebSocket, code: number, reason: string) {
    try { ws.close(code, reason); } catch { /* gone already */ }
  }

  status(place = 0, you?: number): string {
    const live = this.live();
    const st: Record<string, unknown> = {
      channel: this.name, live, viewers: this.seated().length, max: this.cap(),
      waiting: this.waiting().length, open: live && this.meta.state === "open",
    };
    if (place) { st.seat = false; st.place = place; }
    if (you !== undefined) st.you = you;
    return pyjson({ relay: st });
  }

  tellViewers() {
    const line = this.status();
    for (const ws of [...this.seated(), ...this.sockets("desk"), ...this.sockets("transmit")]) this.send(ws, line);
    this.waiting().forEach((ws, i) => this.send(ws, this.status(i + 1, this.att(ws).who)));
  }

  broadcast(line: string) {
    for (const ws of [...this.sockets("watch"), ...this.sockets("desk")]) this.send(ws, line);
  }

  seat(ws: WebSocket) {
    const a = this.att(ws);
    a.seated = true;
    ws.serializeAttachment(a);
    this.send(ws, this.status(0, a.who));
    if (this.lastData) this.send(ws, this.lastData);
    if (this.lastFrame && this.live()) this.sendBinary(ws, this.lastFrame); // (off air, no old frame)
    for (const r of this.reports) this.send(ws, r);
    this.lateJoiner(ws, "watch");
  }

  seatWaiting(): boolean {
    const cap = this.cap();
    let moved = false, n = this.seated().length;
    for (const ws of this.waiting()) {
      if (cap && n >= cap) break;
      this.seat(ws);
      n++;
      moved = true;
    }
    return moved;
  }

  lateJoiner(ws: WebSocket, role: Role) {
    for (const [, line] of this.chat) this.send(ws, line);
    if (this.poll) this.send(ws, this.pollLine());
    if (this.meta.soundInfo && role !== "desk") this.send(ws, this.meta.soundInfo);
  }

  // keys

  async hash(key: string): Promise<string> {
    const d = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(key));
    return [...new Uint8Array(d)].map((b) => b.toString(16).padStart(2, "0")).join("");
  }

  // "ok", or why not (a close code and its reason)
  async checkKey(key: string, ip: string): Promise<[number, string] | null> {
    const now = Date.now(), tries = (this.wrong.get(ip) || []).filter((t) => now - t < 60_000);
    if (tries.length >= WRONG_KEYS) return [4029, "too many wrong keys: try again in a minute"];
    if (key.length < 8) return [4001, "a transmit key needs at least 8 characters"];
    const h = await this.hash(key);
    if (this.meta.keyHash === null) {
      if (this.env.OPEN_CLAIM === "1") {
        this.meta.keyHash = h;
        this.saveMeta();
        return null;
      }
      tries.push(now);
      this.wrong.set(ip, tries);
      return [4003, "no such channel: ask for an attach code"];
    }
    if (this.meta.keyHash !== h) {
      tries.push(now);
      this.wrong.set(ip, tries);
      return [4003, "wrong key for this channel"];
    }
    return null;
  }

  // the door

  async fetch(request: Request): Promise<Response> {
    const url = new URL(request.url);
    const name = request.headers.get("x-channel") || "";
    if (name && this.name !== name) {
      this.name = name;
      this.ctx.storage.put("name", name);
      if (!this.meta.hostNames.includes(name.toLowerCase())) {
        this.meta.hostNames.push(name.toLowerCase());
        this.saveMeta();
      }
    }
    const ip = request.headers.get("cf-connecting-ip") || "";
    const q = url.searchParams;
    const [, role] = url.pathname.split("/");

    if (role === "_mint") return this.mint();
    if (role === "_revoke") return this.revoke();
    if (role === "_dink") return this.dink(request, q.get("key") || "", ip);

    // the key first, so a refused socket never counts as the channel's, even for a moment
    const no = role === "watch" ? null : await this.checkKey(q.get("key") || "", ip);
    const pair = new WebSocketPair();
    const [client, ws] = [pair[0], pair[1]];
    this.ctx.acceptWebSocket(ws, [no ? "refused" : role]);
    const a: Att = { role: (no ? "refused" : role) as Role };
    ws.serializeAttachment(a);

    if (no) {
      this.close(ws, no[0], no[1]);
    } else if (role === "watch") {
      this.openWatch(ws, a, q.get("code") || "");
    } else {
      if (role === "transmit") {
        this.openTransmit(ws, q.get("code") || "");
      } else if (role === "desk") {
        this.openDesk(ws, a, q.get("name") || "");
      } else {
        this.openSound(ws);
      }
    }
    return new Response(null, { status: 101, webSocket: client });
  }

  openTransmit(ws: WebSocket, code: string) {
    for (const old of this.sockets("transmit")) {
      if (old !== ws) this.close(old, 4000, "replaced by a newer transmitter");
    }
    this.meta.code = code;
    this.meta.state = "open";
    this.saveMeta();
    // viewers whose code no longer matches go
    for (const v of this.sockets("watch")) {
      if ((this.att(v).code || "") !== code) this.close(v, 4003, "the watch code changed");
    }
    this.tellViewers();
    if (!this.uploadTimer) {
      this.outSeen = [this.out, Date.now()];
      this.uploadTimer = setInterval(() => this.tellUpload(), 1000);
    }
  }

  openWatch(ws: WebSocket, a: Att, code: string) {
    if (this.meta.code && code !== this.meta.code) {
      this.close(ws, 4003, "wrong watch code");
      return;
    }
    this.meta.nextWho++;
    this.meta.seq++;
    Object.assign(a, { code, who: this.meta.nextWho, seq: this.meta.seq, seated: false, device: "",
                       name: "visitor", says: [], lastPing: 0 });
    this.saveMeta();
    ws.serializeAttachment(a);
    const cap = this.cap();
    if (cap && (this.seated().length >= cap || this.waiting().length > 1)) {
      this.lateJoiner(ws, "watch"); // in line for a seat: the chat so far all the same
    } else {
      this.seat(ws);
    }
    this.tellViewers();
  }

  openDesk(ws: WebSocket, a: Att, hostName: string) {
    const host = oneLine(hostName, NAME_MAX) || this.name;
    a.host = host;
    ws.serializeAttachment(a);
    if (!this.meta.hostNames.includes(host.toLowerCase())) {
      this.meta.hostNames.push(host.toLowerCase());
      this.saveMeta();
    }
    this.send(ws, this.status());
    this.lateJoiner(ws, "desk");
  }

  openSound(ws: WebSocket) {
    for (const old of this.sockets("sound")) {
      if (old !== ws) this.close(old, 4000, "replaced by a newer sound");
    }
  }

  // messages

  async webSocketMessage(ws: WebSocket, msg: string | ArrayBuffer) {
    const size = typeof msg === "string" ? msg.length : msg.byteLength;
    if (size > MAX_MESSAGE) return;
    const a = this.att(ws);
    if (a.role === "transmit") this.deliver(msg);
    else if (a.role === "watch") {
      if (typeof msg === "string" && msg.length <= VIEWER_TEXT) this.fromViewer(ws, a, msg);
    } else if (a.role === "desk") {
      if (typeof msg === "string") this.fromDesk(a.host || this.name, msg);
    } else if (a.role === "sound") this.fromSound(msg);
  }

  async webSocketClose(ws: WebSocket, code: number, reason: string) {
    this.close(ws, code === 1005 || code === 1006 ? 1000 : code, reason);
    this.gone(ws);
  }

  async webSocketError(ws: WebSocket) {
    this.gone(ws);
  }

  gone(ws: WebSocket) {
    const a = this.att(ws);
    if (a.role === "transmit") {
      if (!this.live()) this.offAir();
    } else if (a.role === "watch") {
      this.seatWaiting();
      this.tellViewers();
    } else if (a.role === "sound") {
      if (!this.sockets("sound").length) {
        this.soundHeld = [];
        this.meta.soundInfo = pyjson({ sound: { mode: "off", buses: [] } });
        this.saveMeta();
        for (const v of this.seated()) this.send(v, this.meta.soundInfo);
      }
    }
  }

  // the transmitter gone (or cut off): viewers hear it, and the upload line stops
  offAir() {
    if (this.live()) return;
    if (this.uploadTimer) clearInterval(this.uploadTimer);
    this.uploadTimer = null;
    this.soundHeld = [];
    this.tellViewers();
  }

  deliver(msg: string | ArrayBuffer) {
    if (typeof msg === "string" && msg.startsWith('{"report"')) {
      this.reports.push(msg);
      this.reports = this.reports.slice(-REPORTS_KEPT);
      this.ctx.storage.put("reports", this.reports);
    } else if (typeof msg === "string") {
      this.lastData = msg;
      if (msg.startsWith('{"state"')) {
        let state = this.meta.state;
        try { state = JSON.parse(msg).state || "open"; } catch { /* keep it */ }
        if (state !== this.meta.state) {
          this.meta.state = state;
          this.saveMeta();
          this.tellViewers(); // (the desks' live light: paused, or back)
        }
      }
    } else {
      this.lastFrame = msg;
    }
    if (typeof msg === "string") {
      for (const v of this.seated()) if (this.send(v, msg)) this.out += msg.length;
    } else {
      for (const v of this.seated()) if (this.sendBinary(v, msg)) this.out += msg.byteLength;
    }
  }

  fromSound(msg: string | ArrayBuffer) {
    if (typeof msg !== "string") {
      const b = new Uint8Array(msg);
      if (b.length > 8 && b[4] === 0x41 && b[5] === 0x55) { // "AU"
        this.soundHeld.push([Date.now() + SOUND_HOLD, msg]);
        if (!this.soundTimer) this.soundTimer = setTimeout(() => this.releaseSound(), SOUND_HOLD);
      }
    } else if (msg.startsWith('{"sound"') && msg.length <= VIEWER_TEXT) {
      this.meta.soundInfo = msg;
      this.saveMeta();
      for (const v of this.seated()) this.send(v, msg);
    }
  }

  releaseSound() {
    this.soundTimer = null;
    const now = Date.now();
    while (this.soundHeld.length && this.soundHeld[0][0] <= now) {
      const msg = this.soundHeld.shift()![1];
      // only while the picture is live and open (a pause or a minimise takes it too)
      if (!this.live() || this.meta.state !== "open") continue;
      for (const v of this.seated()) if (this.sendBinary(v, msg)) this.out += msg.byteLength;
    }
    if (this.soundHeld.length) {
      this.soundTimer = setTimeout(() => this.releaseSound(), Math.max(1, this.soundHeld[0][0] - now));
    }
  }

  tellUpload() {
    const now = Date.now(), [was, at] = this.outSeen;
    this.outSeen = [this.out, now];
    const desks = this.sockets("desk");
    if (!desks.length) return;
    const line = pyjson({ upload: { bps: Math.floor(((this.out - was) * 8) / Math.max(0.001, (now - at) / 1000)),
                                    viewers: this.seated().length } });
    for (const d of desks) this.send(d, line);
  }

  // the chat

  say(name: string, text: string, host = false, who = 0, queued = false) {
    this.meta.chatId++;
    const id = this.meta.chatId;
    const line = pyjson({ chat: { id, name, text, host, who, queued, at: Date.now() } });
    this.chat.push([id, line]);
    this.chat = this.chat.slice(-CHAT_KEPT);
    this.saveMeta();
    this.saveChat();
    this.broadcast(line);
  }

  unsay(id: number) {
    const before = this.chat.length;
    this.chat = this.chat.filter(([i]) => i !== id);
    if (this.chat.length !== before) {
      this.saveChat();
      this.broadcast(pyjson({ unsay: id }));
    }
  }

  isBlocked(a: Att): boolean {
    return this.meta.blocked.includes("w:" + a.who) || (!!a.device && this.meta.blocked.includes("d:" + a.device));
  }

  fromViewer(ws: WebSocket, a: Att, msg: string) {
    let m: any;
    try { m = JSON.parse(msg); } catch { return; }
    if (!m || typeof m !== "object" || Array.isArray(m)) return;
    const now = Date.now();
    if (typeof m.got === "number" && Number.isFinite(m.got)) {
      let f = this.flow.get(ws);
      if (!f) this.flow.set(ws, (f = { sent: 0, got: 0 }));
      f.got = m.got;
      if (f.got > f.sent) f.sent = f.got; // (the object slept and woke since: start the count again from theirs)
      return;
    }
    if (m.hello && typeof m.hello === "object") {
      const dev = m.hello.device;
      if (typeof dev === "string" && dev.length >= 8 && dev.length <= 64 && !a.device) a.device = dev;
      const name = oneLine(m.hello.name, NAME_MAX);
      a.name = name && !this.meta.hostNames.includes(name.toLowerCase()) ? name : "visitor";
      ws.serializeAttachment(a);
      return;
    }
    // in line for a seat: chat and votes all the same; pings only once seated, with a frame to point at
    if (!a.seated && "ping" in m) return;
    const blocked = this.isBlocked(a);
    if ("say" in m) {
      const text = oneLine(m.say, SAY_MAX);
      const says = (a.says || []).filter((t) => now - t <= SAY_WINDOW);
      if (!text || blocked || says.length >= SAY_BURST) {
        a.says = says;
        ws.serializeAttachment(a);
        return;
      }
      says.push(now);
      a.says = says;
      ws.serializeAttachment(a);
      this.say(a.name || "visitor", text, false, a.who, !a.seated);
    } else if (m.vote && typeof m.vote === "object") {
      const p = this.poll, pick = m.vote.pick;
      if (p && p.open && m.vote.poll === p.id && Number.isInteger(pick) && pick >= 0 && pick < p.options.length) {
        p.votes[a.device || "who:" + a.who] = pick;
        this.savePoll();
        this.tellPoll();
      }
    } else if (m.ping && typeof m.ping === "object") {
      const { x, y } = m.ping;
      const ok = (c: unknown) => typeof c === "number" && c >= 0 && c <= 1;
      if (blocked || now - (a.lastPing || 0) < PING_EVERY || !ok(x) || !ok(y)) return;
      a.lastPing = now;
      ws.serializeAttachment(a);
      this.broadcast(pyjson({ ping: { x: Math.round(x * 1e4) / 1e4, y: Math.round(y * 1e4) / 1e4,
                                      name: a.name || "visitor", who: a.who, at: now } }));
    }
  }

  fromDesk(host: string, msg: string) {
    let m: any;
    try { m = JSON.parse(msg); } catch { return; }
    if (!m || typeof m !== "object" || Array.isArray(m)) return;
    if ("say" in m) {
      const text = oneLine(m.say, SAY_MAX * 2);
      if (text) this.say(host, text, true);
    } else if (Number.isInteger(m.hide)) {
      this.unsay(m.hide);
    } else if (Number.isInteger(m.block) || Number.isInteger(m.unblock)) {
      const who = Number.isInteger(m.block) ? m.block : m.unblock;
      const tags = new Set(["w:" + who]);
      for (const v of this.sockets("watch")) {
        const va = this.att(v);
        if (va.who === who && va.device) tags.add("d:" + va.device);
      }
      if ("block" in m) this.meta.blocked = [...new Set([...this.meta.blocked, ...tags])];
      else this.meta.blocked = this.meta.blocked.filter((t) => !tags.has(t));
      this.saveMeta();
    } else if (m.poll && typeof m.poll === "object") {
      const p = m.poll;
      const question = oneLine(p.question, 120);
      const options = (Array.isArray(p.options) ? p.options : [])
        .filter((o: unknown) => typeof o === "string").map((o: string) => oneLine(o, 60))
        .filter((o: string) => o).slice(0, POLL_OPTIONS[1]);
      if (options.length < POLL_OPTIONS[0]) return;
      let seconds = parseInt(p.seconds ?? 60);
      if (!Number.isFinite(seconds)) seconds = 60;
      const least = parseFloat(this.env.POLL_MIN_SECONDS || "") || POLL_SECONDS[0];
      seconds = Math.max(least, Math.min(POLL_SECONDS[1], seconds));
      this.meta.pollId++;
      this.saveMeta();
      this.poll = { id: this.meta.pollId, question, options, votes: {}, ends: Date.now() + seconds * 1000, open: true };
      this.savePoll();
      this.tellPoll();
      this.schedule();
    } else if (m.poll_close && this.poll && this.poll.open) {
      this.closePoll();
    } else if (Number.isInteger(m.cap) && m.cap >= 0 && m.cap <= 10_000) {
      // (an addition to relay.py's protocol: the channel's own cap on viewers, from the client's settings)
      this.meta.cap = m.cap;
      this.saveMeta();
      this.seatWaiting();
      this.tellViewers();
    }
  }

  pollLine(): string {
    const p = this.poll!;
    const counts = p.options.map(() => 0);
    for (const pick of Object.values(p.votes)) counts[pick]++;
    return pyjson({ poll: { id: p.id, question: p.question, options: p.options, counts, open: p.open,
                            left: p.open ? Math.max(0, Math.round((p.ends - Date.now()) / 1000)) : 0 } });
  }

  tellPoll() { if (this.poll) this.broadcast(this.pollLine()); }

  closePoll() {
    this.poll!.open = false;
    this.poll!.closed = Date.now();
    this.savePoll();
    this.tellPoll();
    this.schedule();
  }

  pollKept(): number { return (parseFloat(this.env.POLL_KEPT_SECONDS || "") * 1000) || POLL_KEPT; }

  // the poll's ending and its going, by alarm (an alarm outlasts a sleep)
  schedule() {
    const p = this.poll;
    if (!p) return;
    this.ctx.storage.setAlarm(p.open ? p.ends : (p.closed || 0) + this.pollKept());
  }

  async alarm() {
    const p = this.poll, now = Date.now();
    if (!p) return;
    if (p.open && now >= p.ends - 5) this.closePoll();
    else if (!p.open && now >= (p.closed || 0) + this.pollKept() - 5) {
      this.poll = null;
      this.savePoll();
      this.broadcast(pyjson({ poll_gone: p.id }));
    } else this.schedule();
  }

  // Dink (a fallback: the client normally takes Dink's posts itself and sends reports as the transmitter)

  async dink(request: Request, key: string, ip: string): Promise<Response> {
    const no = await this.checkKey(key, ip);
    if (no) return new Response(null, { status: 403 });
    const report = dinkReport(await dinkPayload(request));
    if (report) this.deliver(pyjson({ report }));
    return new Response(null, { status: 204 });
  }

  // Jace's admin (index.ts checks the admin token before it reaches here)

  async mint(): Promise<Response> {
    const bytes = crypto.getRandomValues(new Uint8Array(18));
    const key = btoa(String.fromCharCode(...bytes)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
    this.meta.keyHash = await this.hash(key);
    this.saveMeta();
    for (const role of ["transmit", "desk", "sound"] as Role[]) {
      for (const ws of this.sockets(role)) this.close(ws, 4003, "this channel has a new key");
    }
    this.offAir();
    return Response.json({ key });
  }

  async revoke(): Promise<Response> {
    this.meta.keyHash = null;
    this.saveMeta();
    for (const role of ["transmit", "desk", "sound"] as Role[]) {
      for (const ws of this.sockets(role)) this.close(ws, 4003, "this channel was taken back");
    }
    this.offAir();
    return new Response(null, { status: 204 });
  }
}

// Dink's JSON: the payload_json part of a multipart post, or a plain JSON body
async function dinkPayload(request: Request): Promise<unknown> {
  try {
    const type = (request.headers.get("content-type") || "").toLowerCase();
    if (type.startsWith("multipart/")) {
      const form = await request.formData();
      const part = form.get("payload_json");
      if (part === null) return null;
      return JSON.parse(typeof part === "string" ? part : await (part as Blob).text());
    }
    return JSON.parse(await request.text());
  } catch {
    return null;
  }
}

